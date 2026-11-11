(ns clai.agent-test
  "Tests for the ACP agent handler in `clai.agent`, in particular that a
  `session/prompt` actually reaches the model and that the reply validates as
  an ACP `session/update`."
  (:require [acp.agent :as acp]
            [acp.agent.helpers :as helpers]
            [acp.schema.core :as schemas]
            [clai.agent :as agent]
            [clai.agent.providers.ollama :as ollama]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.test-support :refer [quiet-logs instrument memory-session-store]]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)
(use-fixtures :each memory-session-store)

(def ^:private test-client
  (ollama/make-client {:host "http://localhost:11434" :timeout 1000}))

;; ---------------------------------------------------------------------------
;; session/prompt end to end (Ollama stubbed)
;; ---------------------------------------------------------------------------

(defn- run-with-stubbed-ollama
  "Run `f` with `/api/chat` and the ACP senders stubbed. `f` receives the
  handler plus atoms collecting the chat request bodies (`requests`) and the
  `[sessionId update]` pairs sent to the client (`updates`).

  `reply` is either the assistant message text or a full message map (for
  example to include a `:thinking` field). `agent-opts` is merged into the
  options passed to `agent/ollama-agent`, so tests can supply a `:cfg`."
  ([reply f] (run-with-stubbed-ollama reply {} f))
  ([reply agent-opts f]
   (let [requests (atom [])
        updates  (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:message (if (map? reply)
                                (merge {:role "assistant"} reply)
                                {:role "assistant" :content reply})})
                  acp/send-session-update
                  (fn [_conn sid update]
                    ;; Validate exactly as the real sender would, so a
                    ;; malformed update fails the test.
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))
                  helpers/send-session-update
                  (fn [_conn sid update]
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))]
      (let [handler (agent/ollama-agent
                     (merge {:cfg    {}
                             :client test-client
                             :model  "stub-model"}
                            agent-opts))]
        (f handler requests updates))))))

(deftest session-prompt-sends-user-text-to-ollama
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests updates]
     (let [{:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})
           resp                (acp/handle-session-prompt
                                handler nil
                                {:sessionId sessionId
                                 :prompt    [{:type "text" :text "ping"}]})]
       (is (= {:stopReason "end_turn"} resp))
       (is (= 1 (count @requests)))
       (is (= [{:role "user" :content "ping"}]
              (:messages (first @requests)))
           "the prompt text must be in the request sent to Ollama")
       (is (= "stub-model" (:model (first @requests))))
       (is (= 1 (count @updates)))
       (let [[sid update] (first @updates)]
         (is (= sessionId sid))
         (is (= "agent_message_chunk" (:sessionUpdate update)))
         (is (= "pong" (get-in update [:content :text]))))))))

(defn- temp-dir
  "Create an empty temporary directory for a test."
  []
  (doto (.toFile (java.nio.file.Files/createTempDirectory
                  "clai-agent-test"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
    (.deleteOnExit)))

(deftest session-new-appends-agents-md-to-system-prompt
  (let [dir    (temp-dir)
        agents (File. dir "AGENTS.md")]
    (try
      (spit agents "Project rules.")
      (run-with-stubbed-ollama
       "pong"
       {:cfg {:system-prompt "base prompt"}}
       (fn [handler requests _updates]
         (let [{:keys [sessionId]} (acp/handle-session-new
                                    handler nil {:cwd (.getPath dir)})]
           (is (= [{:role "system" :content "base prompt\n\nProject rules."}]
                  (take 1 (:history (sessions/load-session (:postgres handler)
                                                       sessionId))))
               "AGENTS.md is appended to the configured system prompt")
           (acp/handle-session-prompt
            handler nil
            {:sessionId sessionId
             :prompt    [{:type "text" :text "ping"}]})
           (is (= "base prompt\n\nProject rules."
                  (-> @requests first :messages first :content))
               "the model receives AGENTS.md before the first user turn"))))
      (finally
        (.delete agents)
        (.delete dir)))))

(deftest session-new-uses-agents-md-without-configured-system-prompt
  (let [dir    (temp-dir)
        agents (File. dir "AGENTS.md")]
    (try
      (spit agents "Only project rules.")
      (run-with-stubbed-ollama
       "pong"
       (fn [handler requests _updates]
         (let [{:keys [sessionId]} (acp/handle-session-new
                                    handler nil {:cwd (.getPath dir)})]
           (is (= [{:role "system" :content "Only project rules."}]
                  (take 1 (:history (sessions/load-session (:postgres handler)
                                                       sessionId)))))
           (acp/handle-session-prompt
            handler nil
            {:sessionId sessionId
             :prompt    [{:type "text" :text "ping"}]})
           (is (= "Only project rules."
                  (-> @requests first :messages first :content))))))
      (finally
        (.delete agents)
        (.delete dir)))))

(deftest session-new-ignores-blank-agents-md
  (let [dir    (temp-dir)
        agents (File. dir "AGENTS.md")]
    (try
      (spit agents "   \n\t")
      (run-with-stubbed-ollama
       "pong"
       (fn [handler _requests _updates]
         (let [{:keys [sessionId]} (acp/handle-session-new
                                    handler nil {:cwd (.getPath dir)})]
           (acp/handle-session-prompt
            handler nil
            {:sessionId sessionId
             :prompt    [{:type "text" :text "ping"}]})
           (is (= [{:role "user" :content "ping"}]
                  (take 1 (:history (sessions/load-session (:postgres handler)
                                                       sessionId))))
               "a blank AGENTS.md adds no system message"))))
      (finally
        (.delete agents)
        (.delete dir)))))

(deftest session-prompt-relays-model-thinking
  (run-with-stubbed-ollama
   {:content "pong" :thinking "let me think about this"}
   (fn [handler _requests updates]
     (let [{:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})
           resp                (acp/handle-session-prompt
                                handler nil
                                {:sessionId sessionId
                                 :prompt    [{:type "text" :text "ping"}]})]
       (is (= {:stopReason "end_turn"} resp))
       (is (= ["agent_thought_chunk" "agent_message_chunk"]
              (mapv (comp :sessionUpdate second) @updates))
           "thinking must be sent as a thought chunk before the final reply")
       (is (= "let me think about this"
              (get-in (first @updates) [1 :content :text])))
       (is (= "pong"
              (get-in (second @updates) [1 :content :text])))
       (is (= "let me think about this"
              (:thinking (last (:history (sessions/load-session
                                          (:postgres handler) sessionId)))))
           "thinking is persisted so session/load can replay it")))))

(deftest session-prompt-accumulates-history
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests _updates]
     (let [{:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})]
       (acp/handle-session-prompt handler nil
                                  {:sessionId sessionId
                                   :prompt    [{:type "text" :text "first"}]})
       (acp/handle-session-prompt handler nil
                                  {:sessionId sessionId
                                   :prompt    [{:type "text" :text "second"}]})
       (is (= [{:role "user" :content "first"}
               {:role "assistant" :content "pong"}
               {:role "user" :content "second"}]
              (:messages (second @requests))))))))

(deftest session-prompt-uses-the-agents-own-tool-registry
  (let [requests (atom [])
        updates  (atom [])
        calls    (atom 0)
        echo     {:id      "echo"
                  :handler (fn [{:keys [text]} _ctx] (str "echo:" text))
                  :spec    {:type "function"
                            :function {:name "echo"
                                       :parameters {:type "object"}}}}
        other    {:id      "other"
                  :handler (fn [_args _ctx] "other")
                  :spec    {:type "function"
                            :function {:name "other"
                                       :parameters {:type "object"}}}}]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    (if (= 1 (swap! calls inc))
                      {:message {:role "assistant"
                                 :content ""
                                 :tool_calls
                                 [{:id "call-1"
                                   :function {:name "echo"
                                              :arguments "{\"text\":\"hi\"}"}}]}}
                      {:message {:role "assistant" :content "done"}}))
                  acp/send-session-update
                  (fn [_conn sid update]
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))
                  helpers/send-session-update
                  (fn [_conn sid update]
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))]
      (let [handler (agent/ollama-agent {:cfg           {:tools ["echo"]}
                                         :client        test-client
                                         :model         "stub-model"
                                         :tool-registry [echo other]})
            {:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})
            resp (acp/handle-session-prompt
                  handler nil
                  {:sessionId sessionId
                   :prompt    [{:type "text" :text "ping"}]})]
        (is (= {:stopReason "end_turn"} resp))
        (is (= 2 (count @requests)))
        (is (= ["echo"]
               (mapv #(get-in % [:function :name])
                     (:tools (first @requests))))
            "config :tools must narrow the agent's own registry")
        (is (= "echo:hi"
               (->> (:messages (second @requests))
                    (filter #(= "tool" (:role %)))
                    first
                    :content))
            "the tool call must execute against the same per-agent registry")
        (is (= "done" (get-in (last @updates) [1 :content :text])))))))

(deftest tool-output-is-streamed-as-tool-call-updates
  (let [requests (atom [])
        updates  (atom [])
        calls    (atom 0)
        chatter  {:id      "chatter"
                  :handler (fn [_args ctx]
                             ;; A long-running tool reports its output as it goes.
                             (when-let [on-output (:on-output ctx)]
                               (on-output "first")
                               (on-output "first second"))
                             "first second")
                  :spec    {:type "function"
                            :function {:name "chatter"
                                       :parameters {:type "object"}}}}]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    (if (= 1 (swap! calls inc))
                      {:message {:role "assistant"
                                 :content ""
                                 :tool_calls
                                 [{:id "call-1"
                                   :function {:name "chatter"
                                              :arguments "{}"}}]}}
                      {:message {:role "assistant" :content "done"}}))
                  acp/send-session-update
                  (fn [_conn sid update]
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))
                  helpers/send-session-update
                  (fn [_conn sid update]
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))]
      (let [handler (agent/ollama-agent {:cfg           {}
                                         :client        test-client
                                         :model         "stub-model"
                                         :tool-registry [chatter]})
            {:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})]
        ;; A live connection is what makes streaming possible: without one
        ;; there is nobody to send `tool_call_update`s to.
        (is (= {:stopReason "end_turn"}
               (acp/handle-session-prompt
                handler {}
                {:sessionId sessionId
                 :prompt    [{:type "text" :text "ping"}]})))
        (let [tool-updates (->> @updates
                               (map second)
                               (filter #(str/starts-with? (:sessionUpdate %) "tool_call")))]
          (is (= ["tool_call" "tool_call_update" "tool_call_update"
                  "tool_call_update"]
                 (mapv :sessionUpdate tool-updates))
              "the tool call is opened, streamed twice, and closed")
          (is (= ["first" "first second"]
                 (mapv #(get-in % [:content 0 :content :text])
                       (take 2 (rest tool-updates))))
              "each chunk carries the output produced so far")
          (is (every? nil? (mapv :status (take 2 (rest tool-updates))))
              "streamed output does not close the call")
          (is (= "completed" (:status (last tool-updates))))
          (is (= "first second"
                 (get-in (last tool-updates) [:content 0 :content :text]))))
        (is (= "first second"
               (->> (:messages (second @requests))
                    (filter #(= "tool" (:role %)))
                    first
                    :content))
            "the model still sees the complete result")))))

(deftest session-prompt-reads-local-resource-links
  ;; `handle-session-prompt` passes the session cwd to the prompt converter so
  ;; resource links (here a cwd-relative path) reach the model as text.
  (let [dir (doto (File/createTempFile "clai-agent-resource" ".d")
              (.delete)
              (.mkdir))
        f   (File. dir "notes.txt")]
    (try
      (spit f "resource body")
      (run-with-stubbed-ollama
       "done"
       (fn [handler requests _updates]
         (let [{:keys [sessionId]} (acp/handle-session-new
                                    handler nil {:cwd (.getPath dir)})]
           (acp/handle-session-prompt
            handler nil
            {:sessionId sessionId
             :prompt    [{:type "resource_link"
                          :name "notes.txt"
                          :uri  (.getName f)}]})
           (let [content (-> @requests first :messages first :content)]
             (is (str/includes? content "notes.txt"))
             (is (str/includes? content "resource body")
                 "the referenced file's contents must be sent to Ollama")))))
      (finally
        (.delete f)
        (.delete dir)))))

(deftest session-cancel-does-not-throw
  (run-with-stubbed-ollama
   "pong"
   (fn [handler _requests _updates]
     (let [{:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})]
       (is (nil? (acp/on-session-cancel handler nil {:sessionId sessionId})))))))

(deftest prompt-for-unknown-session-relays-message
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests updates]
     (let [resp (acp/handle-session-prompt
                 handler nil
                 {:sessionId "missing"
                  :prompt    [{:type "text" :text "ping"}]})]
       (is (= {:stopReason "end_turn"} resp))
       (is (empty? @requests))
       (let [[sid update] (first @updates)]
         (is (= ["missing"] [sid]))
         (is (= "agent_message_chunk" (:sessionUpdate update)))
         (is (= "No session missing" (get-in update [:content :text]))))))))
