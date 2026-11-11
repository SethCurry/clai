(ns clai.repl-test
  "Tests for the in-process REPL client in `clai.repl`.

  The agent's session and prompt operations are stubbed so no Ollama server or
  network access is involved: the tests exercise the session helpers, update
  rendering, and namespace bookkeeping."
  (:require [clai.agent :as agent]
            [clai.agent.providers.ollama.prompt :as ollama-prompt]
            [clai.events.schema :as events]
            [clai.repl :as repl]
            [clai.shell.cli :as cli]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io StringWriter]))

;; ---------------------------------------------------------------------------
;; Stub agent operations
;; ---------------------------------------------------------------------------

(defn- prompt-text
  "Flatten a prompt the way the agent's Ollama converter does."
  [prompt]
  (->> (ollama-prompt/prompt->messages prompt)
       (map :content)
       (str/join "\n")))

(defn- stub-load-session!
  "Return a fake session for any non-blank id, with history for
  `with-history`."
  [_ {:keys [sessionId cwd]}]
  (when-not (str/blank? (str sessionId))
    (let [id (str sessionId)]
      {:sessionId id
       :cwd       (or cwd "/tmp")
       :history   (if (= "with-history" id)
                    [{:role "user" :content "hello"}
                     {:role "assistant" :content "hi there"}]
                    [])})))

(defn- stub-run-prompt!
  "Answer prompts with simple event updates, including tool and thought cases."
  [_ {:keys [prompt on-update]}]
  (let [text (prompt-text prompt)]
    (case text
      "fail" (throw (ex-info "boom" {:clai/test true}))

      "tool" (do
               (on-update {:type   "tool_call"
                           :id     "call-1"
                           :title  "reading AGENTS.md"
                           :status "running"})
               (on-update {:type   "tool_call"
                           :id     "call-1"
                           :status "completed"
                           :output "contents"})
               {:stopReason "end_turn"})

      "stream" (do
                 (on-update (events/text-event "agent_thought" "pondering"))
                 (on-update (events/text-event "agent_message" "answer"))
                 (on-update {:type   "tool_call"
                             :id     "call-2"
                             :title  "a tool"
                             :status "running"})
                 {:stopReason "end_turn"})

      (do
        (on-update (events/text-event "agent_message" (str "echo: " text)))
        {:stopReason "end_turn"}))))

(defn- with-stub-agent
  "Run `f` with the agent's session and prompt operations stubbed."
  [f]
  (with-redefs [agent/new-session! (fn [_ {:keys [cwd]}]
                                     {:sessionId "stub-session" :cwd cwd})
                agent/load-session! stub-load-session!
                agent/list-sessions! (fn [_ _]
                                       {:sessions
                                        [{:sessionId "older"
                                          :cwd       "/tmp"
                                          :createdAt "2024-01-01T00:00:00Z"}
                                         {:sessionId "newest"
                                          :cwd       "/tmp"
                                          :createdAt "2024-02-01T00:00:00Z"}]})
                agent/run-prompt! stub-run-prompt!]
    (f)))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)
(use-fixtures :each
  (fn [f]
    ;; `new!` and `resume!` switch `*ns*` into the session's namespace; put
    ;; the runner back where it was so later tests do not inherit it.
    (let [test-ns *ns*]
      (try
        (with-stub-agent
          #(do
             (alter-var-root #'repl/current-session (constantly nil))
             (f)))
        (finally
          (repl/disconnect!)
          (alter-var-root #'repl/current-session (constantly nil))
          (in-ns (ns-name test-ns)))))))

(defn- with-cli-out
  "Run `f`, capturing everything written to `*out*` and returning
  `[result output]`."
  [f]
  (let [out (StringWriter.)
        result (binding [*out* out] (f))]
    [result (str out)]))

;; ---------------------------------------------------------------------------
;; $
;; ---------------------------------------------------------------------------

(deftest $-prints-stdout-and-stderr
  (let [out    (StringWriter.)
        err    (StringWriter.)
        result (binding [*out* out *err* err]
                 (repl/$ "printf hello; printf oops >&2"))]
    (is (= "hello" (str out)))
    (is (= "oops" (str err)))
    (is (= 0 (:exit result)))
    (is (= "hello" (:out result)))
    (is (= "oops" (:err result)))))

(deftest $-does-not-throw-on-a-non-zero-exit
  (let [[result out] (with-cli-out #(repl/$ "printf failed; exit 3"))]
    (is (= 3 (:exit result)))
    (is (= "failed" out))))

;; ---------------------------------------------------------------------------
;; new!
;; ---------------------------------------------------------------------------

(deftest new!-creates-and-selects-a-session
  (let [session (repl/new! {:cwd "/tmp"})]
    (is (= "stub-session" (:sessionId session)))
    (is (= "/tmp" (:cwd session)))
    (is (= "stub-session" (repl/current-session-id)))
    (is (= session repl/current-session))
    (is (repl/connected?))))

(deftest new!-accepts-a-bare-cwd
  (is (= "/tmp" (:cwd (repl/new! "/tmp")))))

;; ---------------------------------------------------------------------------
;; session namespaces
;; ---------------------------------------------------------------------------

(deftest session-ns-names-the-session-namespace
  (is (= 'sessions.123 (repl/session-ns "123")))
  (is (= 'sessions.abc-def (repl/session-ns {:sessionId "abc-def"})))
  (is (= 'sessions.a_b (repl/session-ns "a/b"))))

(deftest new!-enters-the-session-namespace
  (binding [*ns* (the-ns 'user)]
    (let [session (repl/new! {:cwd "/tmp"})
          ns-sym  (repl/session-ns session)
          ns      (find-ns ns-sym)]
      (is (= 'sessions.stub-session ns-sym))
      (is (identical? ns *ns*))
      (is (= "sessions.stub-session" (str (ns-name *ns*))))
      (is (= session (var-get (get (ns-map ns) 'current-session))))
      (is (= "stub-session" (repl/current-session-id))))))

(deftest new!-builds-the-session-namespace-through-namespace-builder
  (let [built (promise)
        build repl/namespace-builder]
    (with-redefs [repl/namespace-builder
                  (fn [opts]
                    (deliver built opts)
                    (build opts))]
      (let [session (repl/new! {:cwd "/tmp"})]
        (is (= {:session session :cwd "/tmp"} @built))
        (is (= "sessions.stub-session" (str (ns-name *ns*))))))))

(deftest namespace-builder-accepts-options
  (let [ns-sym  'sessions.clai-repl-test-builder
        session {:sessionId "builder-test" :cwd "/tmp"}]
    (remove-ns ns-sym)
    (try
      (let [ns (repl/namespace-builder {:session   session
                                        :namespace ns-sym
                                        :cwd       "/"})]
        (is (= ns-sym (ns-name ns)))
        (is (= (assoc session :cwd "/")
               (var-get (get (ns-interns ns) 'current-session))))
        (is (= "/" (var-get (get (ns-interns ns) '*cwd*)))))
      (finally
        (remove-ns ns-sym)))))

(deftest namespace-builder-can-seed-from-another-namespace
  (let [ns-sym     'sessions.clai-repl-test-seeded
        source-sym 'clai.repl-test-seed-source]
    (remove-ns ns-sym)
    (try
      (let [source-var (intern (create-ns source-sym) 'seeded-value 42)
            ns         (repl/namespace-builder
                        {:session   {:sessionId "seeded"}
                         :namespace ns-sym
                         :seed-from source-sym})]
        (is (identical? source-var (get (ns-map ns) 'seeded-value))))
      (finally
        (remove-ns ns-sym)
        (remove-ns source-sym)))))

(deftest session-namespaces-are-seeded-from-user
  (let [user-ns    (the-ns 'user)
        session-id "seed-session-ns-from-user"
        ns-sym     (repl/session-ns session-id)
        tmp-alias  'clai-repl-test-tmp-alias
        tmp-var    (intern user-ns 'clai-repl-test-tmp-var 42)]
    ;; Make sure this session namespace is created fresh, even when the tests
    ;; run more than once in the same JVM.
    (remove-ns ns-sym)
    (binding [*ns* user-ns]
      (alias tmp-alias 'clojure.string))
    (try
      (binding [*ns* user-ns]
        (let [session    (repl/resume! session-id)
              session-ns (find-ns ns-sym)]
          (is (= session-id (repl/current-session-id)))
          (is (identical? session-ns *ns*))

          ;; The aliases and Vars of `user` are inserted into the session ns.
          (is (= 'clojure.string
                 (some-> (get (ns-aliases session-ns) tmp-alias) ns-name)))
          (is (identical? tmp-var
                          (get (ns-map session-ns) 'clai-repl-test-tmp-var)))

          ;; And its own current-session points at this session.
          (is (= session
                 (var-get (get (ns-map session-ns) 'current-session))))))
      (finally
        (remove-ns ns-sym)
        (ns-unalias user-ns tmp-alias)
        (ns-unmap user-ns 'clai-repl-test-tmp-var)))))

(deftest namespaces-keep-their-own-current-session
  (binding [*ns* (the-ns 'user)]
    (repl/resume! "older")
    (is (= "older" (repl/current-session-id)))
    (repl/new!)
    (is (= "stub-session" (repl/current-session-id)))
    ;; Returning to an earlier session's namespace selects its session again,
    ;; even though the global current-session now points at the new one.
    (binding [*ns* (the-ns (repl/session-ns "older"))]
      (is (= "older" (repl/current-session-id))))
    (is (= "stub-session" (repl/current-session-id)))))

(deftest session-namespaces-have-their-own-cwd
  (binding [*ns* (the-ns 'user)]
    (repl/resume! {:sessionId "older" :cwd "/tmp"})
    (repl/new! {:cwd "/"})
    (let [older  (find-ns (repl/session-ns "older"))
          newer  (find-ns (repl/session-ns "stub-session"))
          cwd-of (fn [ns] (get (ns-interns ns) '*cwd*))]
      (is (= "/tmp" (var-get (cwd-of older))))
      (is (= "/" (var-get (cwd-of newer))))
      (is (:dynamic (meta (cwd-of older))))
      (is (.isDynamic ^clojure.lang.Var (cwd-of older)))
      ;; The Var is dynamic, so a `binding` can shadow it per call.
      (is (= "/usr"
             (binding [*ns* older]
               (eval '(binding [*cwd* "/usr"] (clai.shell.cli/pwd))))))
      ;; Shell helpers read the cwd of the namespace they are called from.
      (is (= (.getAbsolutePath (io/file "/tmp"))
             (binding [*ns* older] (cli/pwd))))
      (is (= (.getAbsolutePath (io/file "/"))
             (binding [*ns* newer] (cli/pwd))))
      ;; Changing one session's cwd leaves the other session alone.
      (binding [*ns* older] (cli/cd "/usr"))
      (is (= "/usr" (var-get (cwd-of older))))
      (is (= "/" (var-get (cwd-of newer)))))))

;; ---------------------------------------------------------------------------
;; prompt!
;; ---------------------------------------------------------------------------

(deftest prompt!-streams-the-agent-reply
  (repl/new!)
  (let [[result out] (with-cli-out #(repl/prompt! "ping"))]
    (is (= {:stopReason "end_turn"} result))
    (is (str/includes? out "clai> echo: ping"))))

(deftest prompt!-sends-content-blocks
  (repl/new!)
  (let [[result out] (with-cli-out
                      #(repl/prompt!
                        [{:type "text" :content "one"}
                         {:type "text" :content "two"}]))]
    (is (= {:stopReason "end_turn"} result))
    (is (str/includes? out "echo: one"))))

(deftest prompt!-renders-tool-calls
  (repl/new!)
  (let [[_ out] (with-cli-out #(repl/prompt! "tool"))]
    (is (str/includes? out "[tool] reading AGENTS.md"))
    (is (str/includes? out "[tool] call-1 completed"))
    (is (str/includes? out "contents"))))

(deftest prompt!-ends-a-reply-with-a-newline
  (repl/new!)
  (let [[_ out] (with-cli-out #(repl/prompt! "ping"))]
    (is (= "clai> echo: ping\n" out))))

(deftest prompt!-starts-tool-calls-on-their-own-line
  (repl/new!)
  (let [[_ out] (with-cli-out #(repl/prompt! "stream"))]
    (is (= ["thinking> pondering"
            "clai> answer"
            "  [tool] a tool"]
           (str/split-lines out)))))

(deftest prompt!-propagates-agent-errors
  (repl/new!)
  (let [ex (try
             (binding [*out* (StringWriter.)] (repl/prompt! "fail"))
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (str/includes? (ex-message ex) "boom"))))

(deftest prompt!-requires-a-session
  (is (thrown? clojure.lang.ExceptionInfo (repl/prompt! "ping"))))

;; ---------------------------------------------------------------------------
;; resume!
;; ---------------------------------------------------------------------------

(deftest resume!-loads-the-most-recent-session
  (let [session (repl/resume!)]
    (is (= "newest" (:sessionId session)))
    (is (= "newest" (repl/current-session-id)))
    (is (= "/tmp" (:cwd session)))))

(deftest resume!-accepts-an-explicit-session-id
  (is (= "older" (:sessionId (repl/resume! "older"))))
  (is (= "older" (repl/current-session-id))))

(deftest resume!-uses-the-selected-session
  (repl/new!)
  (is (= "stub-session" (:sessionId (repl/resume!)))))

;; ---------------------------------------------------------------------------
;; history!
;; ---------------------------------------------------------------------------

(deftest history!-replays-the-current-session
  (repl/new!)
  (alter-var-root #'repl/current-session
                  (constantly {:sessionId "with-history" :cwd "/tmp"}))
  ;; Read the global current-session rather than the session namespace's copy.
  (let [[result out] (binding [*ns* (the-ns 'user)]
                       (with-cli-out #(repl/history!)))]
    (is (= "with-history" (:sessionId result)))
    (is (= ["you> hello" "clai> hi there"] (str/split-lines out)))))

(deftest history!-accepts-an-explicit-session-id
  (repl/new!)
  (let [[_ out] (with-cli-out #(repl/history! "with-history"))]
    (is (str/includes? out "you> hello"))))

(deftest history!-requires-a-session
  (is (thrown? clojure.lang.ExceptionInfo (repl/history!))))

;; ---------------------------------------------------------------------------
;; disconnect!
;; ---------------------------------------------------------------------------

(deftest disconnect!-closes-the-connection
  (repl/new!)
  (is (true? (repl/disconnect!)))
  (is (not (repl/connected?)))
  (is (nil? (repl/disconnect!))))
