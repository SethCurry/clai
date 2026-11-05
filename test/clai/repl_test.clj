(ns clai.repl-test
  "Tests for the in-process REPL client in `clai.repl`.

  A stub `AgentHandler` is run as [[clai.repl/current-agent]] so no Ollama
  server or network access is involved: the tests exercise the channel
  plumbing, the session helpers, and update rendering."
  (:require [acp.agent :as acp]
            [acp.errors :as acp-errors]
            [clai.repl :as repl]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io StringWriter]))

;; ---------------------------------------------------------------------------
;; Stub agent
;; ---------------------------------------------------------------------------

(defrecord StubHandler []
  acp/AgentHandler
  (handle-initialize [_ _ {:keys [protocolVersion]}]
    {:protocolVersion (long (or protocolVersion acp/protocol-version-2))
     :info            {:name "stub" :version "0"}
     :capabilities    {:loadSession true}})

  (handle-session-new [_ _ {:keys [cwd]}]
    {:sessionId "stub-session" :cwd cwd})

  (handle-session-list [_ _ _]
    {:sessions [{:sessionId "older"  :cwd "/tmp"
                 :updatedAt "2024-01-01T00:00:00Z"}
                {:sessionId "newest" :cwd "/tmp"
                 :updatedAt "2024-02-01T00:00:00Z"}]})

  (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
    (let [text (get-in prompt [0 :text])]
      (case text
        "fail" (acp-errors/acp-error acp-errors/err-internal "boom")

        "tool" (do
                 (acp/send-session-update
                  conn sessionId
                  {:sessionUpdate "tool_call"
                   :toolCallId    "call-1"
                   :title         "reading AGENTS.md"})
                 (acp/send-session-update
                  conn sessionId
                  {:sessionUpdate "tool_call_update"
                   :toolCallId    "call-1"
                   :status        "completed"
                   :content       [{:type    "content"
                                    :content {:type "text" :text "contents"}}]})
                 {:stopReason "end_turn"})

        "stream" (do
                   (acp/send-session-update
                    conn sessionId
                    {:sessionUpdate "agent_thought_chunk"
                     :content       {:type "text" :text "pondering"}})
                   (acp/send-agent-message-chunk conn sessionId "answer")
                   (acp/send-session-update
                    conn sessionId
                    {:sessionUpdate "tool_call"
                     :toolCallId    "call-2"
                     :title         "a tool"})
                   {:stopReason "end_turn"})

        (do
          (acp/send-agent-message-chunk conn sessionId "echo: ")
          (acp/send-agent-message-chunk conn sessionId text)
          {:stopReason "end_turn"}))))

  (on-ext-request [_ conn method {:keys [sessionId]}]
    (if (= "session/load" method)
      (do
        (when (= "with-history" sessionId)
          (acp/send-session-update
           conn sessionId
           {:sessionUpdate "user_message_chunk"
            :content       {:type "text" :text "hello"}})
          (acp/send-session-update
           conn sessionId
           {:sessionUpdate "agent_message_chunk"
            :content       {:type "text" :text "hi there"}}))
        {})
      (acp-errors/acp-error acp-errors/err-method))))

(defrecord NoLoadHandler []
  acp/AgentHandler
  (handle-initialize [_ _ {:keys [protocolVersion]}]
    {:protocolVersion (long (or protocolVersion acp/protocol-version-2))
     :info            {:name "no-load" :version "0"}
     :capabilities    {}})

  (handle-session-new [_ _ {:keys [cwd]}]
    {:sessionId "no-load" :cwd cwd}))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)
(use-fixtures :each
  (fn [f]
    ;; `new!` and `resume!` switch `*ns*` into the session's namespace; put
    ;; the runner back where it was so later tests do not inherit it.
    (let [test-ns *ns*]
      (try
        (with-redefs [repl/current-agent (->StubHandler)]
          (alter-var-root #'repl/current-session (constantly nil))
          (f))
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
                        [{:type "text" :text "one"}
                         {:type "text" :text "two"}]))]
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

(deftest prompt!-throws-on-an-agent-error
  (repl/new!)
  (let [ex (try
             (binding [*out* (StringWriter.)] (repl/prompt! "fail"))
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (str/includes? (ex-message ex) "boom"))
    (is (= acp-errors/err-internal (-> ex ex-data :acp/error :code)))))

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
    (is (= {} result))
    (is (= ["you> hello" "clai> hi there"] (str/split-lines out)))))

(deftest history!-accepts-an-explicit-session-id
  (repl/new!)
  (let [[_ out] (with-cli-out #(repl/history! "with-history"))]
    (is (str/includes? out "you> hello"))))

(deftest history!-requires-a-session
  (is (thrown? clojure.lang.ExceptionInfo (repl/history!))))

(deftest history!-requires-load-session-support
  (with-redefs [repl/current-agent (->NoLoadHandler)]
    (repl/new!)
    (let [ex (try
               (binding [*out* (StringWriter.)] (repl/history!))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (true? (:clai/no-history (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; disconnect!
;; ---------------------------------------------------------------------------

(deftest disconnect!-closes-the-connection
  (repl/new!)
  (is (true? (repl/disconnect!)))
  (is (not (repl/connected?)))
  (is (nil? (repl/disconnect!))))
