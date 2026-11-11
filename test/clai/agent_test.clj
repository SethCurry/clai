(ns clai.agent-test
  "Tests for the in-process agent in `clai.agent`, in particular that a prompt
  actually reaches the model and that streamed updates validate against the
  canonical session event schema."
  (:require [clai.agent :as agent]
            [clai.agent.providers.ollama :as ollama]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.events.schema :as events]
            [clai.test-support :refer [quiet-logs instrument memory-session-store]]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [malli.core :as m])
  (:import [java.io File]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)
(use-fixtures :each memory-session-store)

(def ^:private test-client
  (ollama/make-client {:host "http://localhost:11434" :timeout 1000}))

(def ^:private event-type->schema
  "Maps a text-bearing update type to the canonical event schema it represents."
  {"user_prompt"   #'events/UserPromptEvent
   "agent_message" #'events/AgentMessageEvent
   "agent_thought" #'events/AgentThoughtEvent})

(defn- validate-update!
  "Validate the canonical event content represented by `update`."
  [update]
  (if (= "tool_call" (:type update))
    (let [event {:id               0
                 :agent_session_id 0
                 :type             "tool_call"
                 :content          (dissoc update :type)}]
      (when-not (m/validate #'events/ToolCallEvent event)
        (throw (ex-info "Invalid tool-call event content"
                        {:update update
                         :event  event
                         :errors (m/explain #'events/ToolCallEvent event)}))))
    (when-let [schema (event-type->schema (:type update))]
      (let [event {:id               0
                   :agent_session_id 0
                   :type             (:type update)
                   :content          (:content update)}]
        (when-not (m/validate schema event)
          (throw (ex-info "Invalid session event content"
                          {:update update
                           :event  event
                           :errors (m/explain schema event)}))))))
  update)

(defn- prompt!
  "Run one prompt against `handler`, validating and collecting every streamed
  update in `updates`."
  [handler session-id prompt updates]
  (agent/run-prompt! handler
                     {:sessionId session-id
                      :prompt    prompt
                      :on-update #(swap! updates conj (validate-update! %))}))

;; ---------------------------------------------------------------------------
;; Prompt end to end (Ollama stubbed)
;; ---------------------------------------------------------------------------

(defn- run-with-stubbed-ollama
  "Run `f` with `/api/chat` stubbed. `f` receives the agent plus atoms
  collecting the chat request bodies (`requests`) and the event updates sent
  to the caller (`updates`).

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
                                 {:role "assistant" :content reply})})]
       (let [handler (agent/ollama-agent
                      (merge {:cfg    {}
                              :client test-client
                              :model  "stub-model"}
                             agent-opts))]
         (f handler requests updates))))))

(deftest prompt-sends-user-text-to-ollama
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests updates]
     (let [{:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})
           resp                (prompt! handler sessionId
                                        [{:type "text" :content "ping"}]
                                        updates)]
       (is (= {:stopReason "end_turn"} resp))
       (is (= 1 (count @requests)))
       (is (= [{:role "user" :content "ping"}]
              (:messages (first @requests)))
           "the prompt text must be in the request sent to Ollama")
       (is (= "stub-model" (:model (first @requests))))
       (is (= 1 (count @updates)))
       (let [update (first @updates)]
         (is (= "agent_message" (:type update)))
         (is (= "pong" (events/content->text (:content update)))))))))

(defn- temp-dir
  "Create an empty temporary directory for a test."
  []
  (doto (.toFile (java.nio.file.Files/createTempDirectory
                  "clai-agent-test"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
    (.deleteOnExit)))

(deftest new-session-appends-agents-md-to-system-prompt
  (let [dir    (temp-dir)
        agents (File. dir "AGENTS.md")]
    (try
      (spit agents "Project rules.")
      (run-with-stubbed-ollama
       "pong"
       {:cfg {:system-prompt "base prompt"}}
       (fn [handler requests updates]
         (let [{:keys [sessionId]} (agent/new-session! handler {:cwd (.getPath dir)})]
           (is (= [{:role "system" :content "base prompt\n\nProject rules."}]
                  (take 1 (:history (sessions/load-session test-client
                                                       sessionId))))
               "AGENTS.md is appended to the configured system prompt")
           (prompt! handler sessionId "ping" updates)
           (is (= "base prompt\n\nProject rules."
                  (-> @requests first :messages first :content))
               "the model receives AGENTS.md before the first user turn"))))
      (finally
        (.delete agents)
        (.delete dir)))))

(deftest new-session-uses-agents-md-without-configured-system-prompt
  (let [dir    (temp-dir)
        agents (File. dir "AGENTS.md")]
    (try
      (spit agents "Only project rules.")
      (run-with-stubbed-ollama
       "pong"
       (fn [handler requests updates]
         (let [{:keys [sessionId]} (agent/new-session! handler {:cwd (.getPath dir)})]
           (is (= [{:role "system" :content "Only project rules."}]
                  (take 1 (:history (sessions/load-session test-client
                                                       sessionId)))))
           (prompt! handler sessionId "ping" updates)
           (is (= "Only project rules."
                  (-> @requests first :messages first :content))))))
      (finally
        (.delete agents)
        (.delete dir)))))

(deftest new-session-ignores-blank-agents-md
  (let [dir    (temp-dir)
        agents (File. dir "AGENTS.md")]
    (try
      (spit agents "   \n\t")
      (run-with-stubbed-ollama
       "pong"
       (fn [handler _requests updates]
         (let [{:keys [sessionId]} (agent/new-session! handler {:cwd (.getPath dir)})]
           (prompt! handler sessionId "ping" updates)
           (is (= [{:role "user" :content "ping"}]
                  (take 1 (:history (sessions/load-session test-client
                                                       sessionId))))
               "a blank AGENTS.md adds no system message"))))
      (finally
        (.delete agents)
        (.delete dir)))))

(deftest prompt-relays-model-thinking
  (run-with-stubbed-ollama
   {:content "pong" :thinking "let me think about this"}
   (fn [handler _requests updates]
     (let [{:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})
           resp                (prompt! handler sessionId "ping" updates)]
       (is (= {:stopReason "end_turn"} resp))
       (is (= ["agent_thought" "agent_message"]
              (mapv :type @updates))
           "thinking must be sent as a thought event before the final reply")
       (is (= "let me think about this"
              (events/content->text (:content (first @updates)))))
       (is (= "pong"
              (events/content->text (:content (second @updates)))))
       (is (= "let me think about this"
              (:thinking (last (:history (sessions/load-session
                                          test-client sessionId)))))
           "thinking is persisted so a resumed session can replay it")))))

(deftest prompt-accumulates-history
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests updates]
     (let [{:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})]
       (prompt! handler sessionId "first" updates)
       (prompt! handler sessionId "second" updates)
       (is (= [{:role "user" :content "first"}
               {:role "assistant" :content "pong"}
               {:role "user" :content "second"}]
              (:messages (second @requests))))))))

(deftest prompt-uses-the-agents-own-tool-registry
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
                      {:message {:role "assistant" :content "done"}}))]
      (let [handler (agent/ollama-agent {:cfg           {:tools ["echo"]}
                                         :client        test-client
                                         :model         "stub-model"
                                         :tool-registry [echo other]})
            {:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})
            resp (prompt! handler sessionId "ping" updates)]
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
        (is (= "done"
               (events/content->text (:content (last @updates)))))))))

(deftest tool-output-is-streamed-as-tool-call-events
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
                      {:message {:role "assistant" :content "done"}}))]
      (let [handler (agent/ollama-agent {:cfg           {}
                                         :client        test-client
                                         :model         "stub-model"
                                         :tool-registry [chatter]})
            {:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})]
        (is (= {:stopReason "end_turn"}
               (prompt! handler sessionId "ping" updates)))
        (let [tool-updates (->> @updates
                                (filter #(= "tool_call" (:type %))))]
          (is (= 4 (count tool-updates))
              "the tool call is opened, streamed twice, and closed")
          (is (= [nil "first" "first second" "first second"]
                 (mapv :output tool-updates))
              "each update carries the output produced so far")
          (is (= ["running" nil nil "completed"]
                 (mapv :status tool-updates)))
          (is (= "call-1" (:id (first tool-updates)))))
        (is (= "first second"
               (->> (:messages (second @requests))
                    (filter #(= "tool" (:role %)))
                    first
                    :content))
            "the model still sees the complete result")))))

(deftest prompt-reads-local-resource-links
  ;; `run-prompt!` passes the session cwd to the prompt converter so resource
  ;; links (here a cwd-relative path) reach the model as text.
  (let [dir (doto (File/createTempFile "clai-agent-resource" ".d")
              (.delete)
              (.mkdir))
        f   (File. dir "notes.txt")]
    (try
      (spit f "resource body")
      (run-with-stubbed-ollama
       "done"
       (fn [handler requests updates]
         (let [{:keys [sessionId]} (agent/new-session! handler {:cwd (.getPath dir)})]
           (prompt! handler sessionId
                    [{:type "resource_link"
                      :name "notes.txt"
                      :uri  (.getName f)}]
                    updates)
           (let [content (-> @requests first :messages first :content)]
             (is (str/includes? content "notes.txt"))
             (is (str/includes? content "resource body")
                 "the referenced file's contents must be sent to Ollama")))))
      (finally
        (.delete f)
        (.delete dir)))))

(deftest prompt-for-unknown-session-relays-message
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests updates]
     (let [resp (prompt! handler "missing" "ping" updates)]
       (is (= {:stopReason "end_turn"} resp))
       (is (empty? @requests))
       (is (= 1 (count @updates)))
       (let [update (first @updates)]
         (is (= "agent_message" (:type update)))
         (is (= "No session missing"
                (events/content->text (:content update)))))))))
