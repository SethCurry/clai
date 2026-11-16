(ns clai.agent.core-test
  "Tests for the in-process agent in `clai.agent.core`, in particular that a
  prompt actually reaches the model and that streamed updates validate against
  the canonical session event schema."
  (:require [clai.agent.compaction :as compaction]
            [clai.agent.core :as agent]
            [clai.agent.providers.ollama :as ollama]
            [clai.agent.providers.openrouter :as openrouter]
            [clai.daemon.postgres.configs :as agent-configs]
            [clai.daemon.postgres.git :as git]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.events.compaction :as event-compaction]
            [clai.events.schema :as events]
            [clai.plugins :as plugins]
            [clai.daemon.postgres.plugins :as plugins-store]
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

(deftest agent-records-the-provider-that-serves-its-model
  (let [handler (agent/make-agent {:cfg   {}
                                   :model "openrouter:anthropic/claude-sonnet-4"})]
    (is (= :openrouter (:provider handler)))
    (is (= "anthropic/claude-sonnet-4" (:model handler))
        "the provider prefix is stripped before the model is sent")
    (is (= #{:ollama :openrouter} (set (keys (:clients handler))))
        "both provider clients are available to every session")))

(deftest session-configuration-switches-provider
  (let [ollama-requests     (atom [])
        openrouter-requests (atom [])
        updates             (atom [])]
    (with-redefs [ollama/chat
                  (fn [_ req]
                    (swap! ollama-requests conj req)
                    {:message {:role "assistant" :content "from ollama"}})

                  openrouter/chat
                  (fn [_ req]
                    (if-not (contains? req :tools)
                      {:choices [{:message {:role    "assistant"
                                            :content "stub title"}}]}
                      (do
                        (swap! openrouter-requests conj req)
                        {:choices [{:message {:role    "assistant"
                                              :content "from openrouter"}}]})))

                  agent-configs/load-config
                  (fn [_ id]
                    (when (= "42" id)
                      {:configId "42"
                       :name     "openrouter"
                       :model    "openrouter:anthropic/claude-sonnet-4"}))]
      (let [handler (agent/make-agent {:cfg    {}
                                       :client test-client
                                       :model  "stub-model"})
            {:keys [sessionId]} (agent/new-session! handler {:cwd      "/tmp"
                                                              :configId "42"})
            resp (prompt! handler sessionId "ping" updates)]
        (is (= {:stopReason "end_turn"} resp))
        (is (empty? @ollama-requests)
            "the session's model must not reach the daemon's default provider")
        (is (= 1 (count @openrouter-requests)))
        (let [request (first @openrouter-requests)]
          (is (= "anthropic/claude-sonnet-4" (:model request)))
          (is (= [{:role "user" :content "ping"}] (:messages request))
              "history is converted into OpenRouter's message format"))
        (is (= "from openrouter"
               (events/content->text (:content (last @updates)))))))))

(defn- run-with-stubbed-ollama
  "Run `f` with `/api/chat` stubbed. `f` receives the agent plus atoms
  collecting the chat request bodies (`requests`) and the event updates sent
  to the caller (`updates`).

  A title request -- the direct call that names a new session, which offers
  the model no tools -- is answered with a fixed title and kept out of
  `requests`, so a test sees exactly the chat turns it asserts on.

  `reply` is either the assistant message text or a full message map (for
  example to include a `:thinking` field). `agent-opts` is merged into the
  options passed to `agent/make-agent`, so tests can supply a `:cfg`."
  ([reply f] (run-with-stubbed-ollama reply {} f))
  ([reply agent-opts f]
   (let [requests (atom [])
         updates  (atom [])]
     (with-redefs [ollama/chat
                   (fn [_client req]
                     (if-not (contains? req :tools)
                       {:message {:role "assistant" :content "stub title"}}
                       (do
                         (swap! requests conj req)
                         {:message (if (map? reply)
                                     (merge {:role "assistant"} reply)
                                     {:role "assistant"
                                      :content reply})})))]
       (let [handler (agent/make-agent
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

(defn- run-with-compacting-ollama
  "Run `f` with `/api/chat` stubbed for a session small enough to compact.

  `/api/chat` answers three kinds of request: a chat turn (`:tools` present)
  with `pong`, a summarization request (the direct call
  `clai.agent.compaction` makes, whose system message is
  `compaction/summarize-instruction`) with `summary`, and a session-title
  request with a fixed title. `f` receives the chat request bodies and the
  summarization requests, each collected in an atom."
  [summary f]
  (let [requests  (atom [])
        summaries (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (cond
                      (contains? req :tools)
                      (do (swap! requests conj req)
                          {:message {:role "assistant" :content "pong"}})

                      (str/includes? (str (-> req :messages first :content))
                                     compaction/summarize-instruction)
                      (do (swap! summaries conj req)
                          {:message {:role "assistant" :content summary}})

                      :else
                      {:message {:role "assistant" :content "stub title"}}))]
      (f requests summaries))))

(deftest prompt-compacts-a-session-that-outgrows-its-token-budget
  (run-with-compacting-ollama
   "The user asked for pongs."
   (fn [requests summaries]
     (let [handler (agent/make-agent {:cfg    {:compact-after-tokens 1000}
                                      :client test-client
                                      :model  "stub-model"})
           {:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})
           updates (atom [])]
       ;; The first prompt alone is over the budget, but it is also the only
       ;; message there is: there is nothing older to summarize yet.
       (prompt! handler sessionId (apply str (repeat 5000 "x")) updates)
       (is (empty? @summaries))

       (reset! updates [])
       (prompt! handler sessionId "and again" updates)
       (is (= 1 (count @summaries))
           "the older messages are summarized once the session is over budget")

       (let [request  (last @requests)
             messages (:messages request)
             summary  (event-compaction/append-summary
                       [] "The user asked for pongs.")]
         (is (= (:content (first summary)) (:content (first messages)))
             "the chat turn sends the summary in place of the older messages")
         (is (not-any? #(> (count (str (:content %))) 1000) messages)
             "the oversized first prompt is no longer sent")
         (is (= {:role "user" :content "and again"} (last messages))
             "the newest prompt is still there")
         (is (= ["session_compaction" "agent_message"]
                (mapv :type (filter #(contains? #{"session_compaction"
                                                 "agent_message"}
                                                (:type %))
                                    @updates)))
             "the caller is told about the compaction before the reply")
         (is (= "The user asked for pongs."
                (events/content->text
                 (:content (first (filter #(= "session_compaction" (:type %))
                                          @updates)))))
             "the update carries the summary")
         (is (= (conj (mapv :content messages) "pong")
                (mapv :content (:history (sessions/load-session test-client
                                                                sessionId))))
             "the compacted history replaces the stored one, and the reply is
              appended to it"))))))

(deftest prompt-continues-after-compacting-in-the-middle-of-a-turn
  (let [requests  (atom [])
        summaries (atom [])
        echo      {:id      "echo"
                   :handler (fn [_args _ctx] (apply str (repeat 2000 "z")))
                   :spec    {:type "function"
                             :function {:name "echo"
                                        :parameters {:type "object"}}}}]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (cond
                      ;; A summarization request: the instruction opens it and
                      ;; it offers the model no tools.
                      (str/includes? (str (-> req :messages first :content))
                                     compaction/summarize-instruction)
                      (do (swap! summaries conj req)
                          {:message {:role "assistant"
                                     :content "The user asked for echoes."}})

                      ;; The untitled session's title request.
                      (not (contains? req :tools))
                      {:message {:role "assistant" :content "stub title"}}

                      :else
                      (let [n (count (swap! requests conj req))]
                        (if (= 3 n)
                          {:message {:role "assistant"
                                     :content ""
                                     :tool_calls
                                     [{:id "call-1"
                                       :function
                                       {:name "echo"
                                        :arguments "{\"text\":\"hi\"}"}}]}}
                          {:message {:role "assistant" :content "done"}}))))]
      (let [handler (agent/make-agent {:cfg           {:compact-after-tokens 1000}
                                       :client        test-client
                                       :model         "stub-model"
                                       :tool-registry [echo]})
            {:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})
            updates (atom [])
            prompt3 (str "please echo this: " (apply str (repeat 5000 "y")))]
        (prompt! handler sessionId (apply str (repeat 5000 "x")) updates)
        (prompt! handler sessionId "and again" updates)
        (reset! updates [])
        (reset! summaries [])
        (is (= {:stopReason "end_turn"}
               (prompt! handler sessionId prompt3 updates))
            "the turn completes: compacting mid-turn does not end it")
        (is (= 1 (count @summaries))
            "the older turns are summarized once; the turn in progress is
             never summarized on top of them")
        (let [messages (:messages (last @requests))]
          (is (= [prompt3] (mapv :content (filter #(= "user" (:role %))
                                                  messages)))
              "the only user turn sent is the prompt being answered, so the
               model has the instruction the tool results answer")
          (is (= "tool" (:role (last messages)))
              "and the tool result the model is answering is still sent"))
        (is (= "done" (events/content->text (:content (last @updates))))
            "the agent's reply reaches the caller after the compaction")))))

(deftest a-session-configuration-can-turn-compaction-off
  (with-redefs [agent-configs/load-config
                (fn [_ id]
                  (when (= "7" id)
                    {:configId "7"
                     :name     "wide-context"
                     :compact-after-tokens 0}))]
    (run-with-compacting-ollama
     "unused summary"
     (fn [_requests summaries]
       (let [handler (agent/make-agent {:cfg    {:compact-after-tokens 1000}
                                        :client test-client
                                        :model  "stub-model"})
             {:keys [sessionId]} (agent/new-session! handler {:cwd      "/tmp"
                                                              :configId "7"})
             updates (atom [])]
         (prompt! handler sessionId (apply str (repeat 5000 "x")) updates)
         (prompt! handler sessionId "and again" updates)
         (is (empty? @summaries)
             "a budget of 0 in the session's configuration disables compaction")
         (is (not-any? #(= "session_compaction" (:type %)) @updates)))))))

(defn- temp-dir
  "Create an empty temporary directory for a test."
  []
  (doto (.toFile (java.nio.file.Files/createTempDirectory
                  "clai-agent-test"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
    (.deleteOnExit)))

(deftest new-session-uses-selected-agent-configuration
  (let [config {:configId      "42"
                :name          "fast"
                :model         "tiny"
                :system-prompt "config prompt"
                :max-tool-rounds 2
                :think         true
                :options       {:temperature 0}
                :format        "json"
                :tools         []}]
    (with-redefs [agent-configs/load-config
                  (fn [_ id] (when (= "42" id) config))]
      (run-with-stubbed-ollama
       "pong"
       {:cfg {:system-prompt "daemon prompt"}}
       (fn [handler requests updates]
         (let [{:keys [sessionId configId]}
               (agent/new-session! handler {:cwd "/tmp" :configId "42"})]
           (is (= "42" configId)
               "the session records the selected configuration")
           (is (= [{:role "system" :content "config prompt"}]
                  (take 1 (:history (sessions/load-session test-client
                                                       sessionId))))
               "the configuration's prompt replaces the daemon prompt")
           (prompt! handler sessionId "ping" updates)
           (let [request (first @requests)]
             (is (= "tiny" (:model request)))
             (is (= [] (:tools request))
                 "an empty tool list disables every tool")
             (is (= {:temperature 0} (:options request)))
             (is (true? (:think request)))
             (is (= "json" (:format request))))))))))

(deftest new-session-selects-active-plugins-from-config
  (plugins/clear!)
  (try
    (plugins/register! {:name "alpha" :priority 10}
                       {:hooks {:system-prompt
                                (fn [ctx next] (str (next ctx) "|alpha"))}})
    (plugins/register! {:name "beta" :priority 20}
                       {:hooks {:system-prompt
                                (fn [ctx next] (str (next ctx) "|beta"))}})
    (let [system-prompt (fn [config]
                          (with-redefs [agent-configs/load-config
                                        (fn [_ _] config)]
                            (run-with-stubbed-ollama
                             "pong"
                             {:cfg {:system-prompt "base"}}
                             (fn [handler _requests _updates]
                               (let [{:keys [sessionId]}
                                     (agent/new-session!
                                      handler
                                      {:cwd      "/tmp"
                                       :configId (:configId config)})]
                                 (-> (sessions/load-session test-client
                                                            sessionId)
                                     :history
                                     first
                                     :content))))))]
      (is (= "base|beta|alpha"
             (system-prompt {:configId "5" :name "all"}))
          "without :plugins every registered plugin stays active")
      (is (= "base|alpha"
             (system-prompt {:configId "6"
                             :name "only-alpha"
                             :plugins ["alpha"]})))
      (is (= "base"
             (system-prompt {:configId "7" :name "none" :plugins []}))
          "an empty plugin list disables plugins for the session"))
    (finally
      (plugins/clear!))))

(deftest prompt-runs-only-the-sessions-active-plugins
  (plugins/clear!)
  (try
    (plugins/register! {:name "tag" :priority 10}
                       {:hooks {:agent-message-post
                                (fn [{:keys [update]}]
                                  (assoc-in update [:content 0 :content]
                                            (str (-> update :content first :content)
                                                 "|tag")))}})
    (let [reply (fn [config]
                  (with-redefs [agent-configs/load-config
                                (fn [_ _] config)]
                    (run-with-stubbed-ollama
                     "pong"
                     {}
                     (fn [handler _requests updates]
                       (let [{:keys [sessionId]}
                             (agent/new-session!
                              handler
                              {:cwd "/tmp" :configId (:configId config)})]
                         (prompt! handler sessionId "ping" updates)
                         (events/content->text
                          (:content (last @updates))))))))]
      (is (= "pong|tag"
             (reply {:configId "8" :name "tagged" :plugins ["tag"]})))
      (is (= "pong"
             (reply {:configId "9" :name "plain" :plugins []}))))
    (finally
      (plugins/clear!))))

(deftest new-session-rejects-unknown-agent-configuration
  (with-redefs [agent-configs/load-config (constantly nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No agent configuration 99"
                          (agent/new-session! {:postgres ::postgres}
                                              {:configId "99"})))))

(deftest agent-configurations-can-be-listed-and-loaded
  (let [configs [{:configId "1" :name "fast"}
                 {:configId "2" :name "careful"}]
        handler {:postgres ::postgres}]
    (with-redefs [agent-configs/list-configs (fn [_] configs)
                  agent-configs/load-config  (fn [_ id]
                                               (first (filter #(= id (:configId %))
                                                              configs)))
                  agent-configs/select-config (fn [_ name]
                                                (first (filter #(= name (:name %))
                                                               configs)))]
      (is (= {:configs configs} (agent/list-agent-configs! handler {})))
      (is (= (first configs)
             (agent/load-agent-config! handler {:configId "1"})))
      (is (= (second configs)
             (agent/load-agent-config! handler {:name "careful"})))
      (is (nil? (agent/load-agent-config! handler {}))))))

(deftest agent-configurations-can-be-created-updated-and-deleted
  (let [handler {:postgres ::postgres}]
    (with-redefs [agent-configs/create-config
                  (fn [_ config]
                    (assoc config :configId "10"))

                  agent-configs/update-config
                  (fn [_ config-id config]
                    (when (= "10" config-id)
                      (assoc config :configId config-id)))

                  agent-configs/delete-config
                  (fn [_ config-id]
                    (= "10" config-id))]
      (is (= {:name "fast" :configId "10"}
             (agent/create-agent-config! handler {:name "fast"})))
      (is (= {:name "slow" :configId "10"}
             (agent/update-agent-config! handler {:configId "10"
                                                  :name     "slow"})))
      (is (nil? (agent/update-agent-config! handler {:configId "99"
                                                     :name     "missing"})))
      (is (true? (agent/delete-agent-config! handler {:configId "10"})))
      (is (false? (agent/delete-agent-config! handler {:configId "99"}))))))

(deftest plugin-writes-reload-the-running-registry
  (let [handler {:postgres ::postgres}
        row     {:pluginId       "7"
                 :name           "audit"
                 :jarPath        "/opt/clai/audit.jar"
                 :entryNamespace "example.audit"
                 :config         {}
                 :enabled        true
                 :priority       100}
        reloads (atom [])]
    (with-redefs [plugins-store/create-plugin (fn [client plugin]
                                                (is (= ::postgres client))
                                                (assoc plugin :pluginId "7"))
                  plugins-store/update-plugin (fn [_ plugin-id plugin]
                                                (when (= "7" plugin-id)
                                                  row))
                  plugins-store/delete-plugin (fn [_ plugin-id]
                                                (= "7" plugin-id))
                  plugins/load-configured!   (fn [client]
                                               (swap! reloads conj client)
                                               {:loaded ["audit"] :failed []})]
      (let [created (agent/create-plugin! handler
                                          (dissoc row :pluginId))]
        (is (= "7" (:pluginId created)))
        (is (false? (:loaded created))
            "the plugin is not registered until the loader runs")
        (is (= [::postgres] @reloads)))

      (is (= "audit" (:name (agent/update-plugin! handler row))))
      (is (true? (agent/delete-plugin! handler {:pluginId "7"})))
      (is (nil? (agent/update-plugin! handler {:pluginId "99"
                                               :name     "missing"
                                               :jarPath  "/tmp/x.jar"
                                               :entryNamespace "x.y"})))
      (is (false? (agent/delete-plugin! handler {:pluginId "99"})))
      (is (= [::postgres ::postgres ::postgres] @reloads)
          "creates, updates, and deletes reload; failed lookups do not"))))

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

(deftest first-prompt-names-an-untitled-session
  (let [requests (atom [])
        updates  (atom [])
        titled   (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    ;; A title request offers no tools; the chat turn does.
                    {:message {:role    "assistant"
                               :content (if (some? (:tools req))
                                          "pong"
                                          "\"Fix the flaky build.\"")}})]
      (let [handler (agent/make-agent {:cfg    {}
                                       :client test-client
                                       :model  "stub-model"})
            {:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})]
        (agent/run-prompt! handler
                           {:sessionId sessionId
                            :prompt    "the build keeps flaking on CI"
                            :on-update #(swap! updates conj %)
                            :on-title  #(swap! titled conj %)})
        (is (= 2 (count @requests))
            "the title is one direct provider call, separate from the chat turn")
        (let [title-request (first @requests)]
          (is (nil? (:tools title-request))
              "the title request offers the model no tools")
          (is (= 1 (count (:messages title-request)))
              "the title request carries no session history")
          (is (str/includes? (-> title-request :messages first :content)
                             "the build keeps flaking on CI")
              "the title request quotes the session's first message"))
        (is (= [{:role "user" :content "the build keeps flaking on CI"}]
               (:messages (second @requests)))
            "the chat turn sees the prompt but nothing from the title request")
        (is (= "Fix the flaky build"
               (:title (sessions/load-session test-client sessionId))))
        (is (= [{:sessionId sessionId
                 :title     "Fix the flaky build"
                 :cwd       "/tmp"}]
               (mapv #(select-keys % [:sessionId :title :cwd]) @titled))
            "the renamed summary is relayed to :on-title")
        (is (= [{:role "user" :content "the build keeps flaking on CI"}
                {:role "assistant" :content "pong"}]
               (:history (sessions/load-session test-client sessionId)))
            "the title exchange leaves no events in the session history")
        (is (= ["agent_message"] (mapv :type @updates))
            "the title is not streamed as a chat update")))))

(deftest only-the-first-prompt-names-a-session
  (let [requests (atom [])
        titled   (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:message {:role    "assistant"
                               :content (if (some? (:tools req))
                                          "pong"
                                          "Session title")}})]
      (let [handler (agent/make-agent {:cfg    {}
                                       :client test-client
                                       :model  "stub-model"})]
        (testing "a second prompt does not rename the session"
          (let [{:keys [sessionId]} (agent/new-session! handler {:cwd "/tmp"})
                prompt-session! (fn [text]
                                  (agent/run-prompt!
                                   handler
                                   {:sessionId sessionId
                                    :prompt    text
                                    :on-title  #(swap! titled conj %)}))]
            (prompt-session! "first")
            (prompt-session! "second")
            (is (= 3 (count @requests))
                "one title request, then one chat request per prompt")
            (is (= 1 (count @titled)))
            (is (= "Session title"
                   (:title (sessions/load-session test-client sessionId))))))

        (testing "a session created with a title keeps it"
          (reset! requests [])
          (let [{:keys [sessionId]} (agent/new-session! handler
                                                        {:cwd   "/tmp"
                                                         :title "Already named"})]
            (agent/run-prompt! handler
                               {:sessionId sessionId
                                :prompt    "hi"
                                :on-title  #(swap! titled conj %)})
            (is (= 1 (count @requests))
                "no title request is sent for an already-named session")
            (is (= "Already named"
                   (:title (sessions/load-session test-client sessionId))))))))))

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
                    (if-not (contains? req :tools)
                      {:message {:role "assistant" :content "stub title"}}
                      (do
                        (swap! requests conj req)
                        (if (= 1 (swap! calls inc))
                          {:message {:role "assistant"
                                     :content ""
                                     :tool_calls
                                     [{:id "call-1"
                                       :function {:name "echo"
                                                  :arguments "{\"text\":\"hi\"}"}}]}}
                          {:message {:role "assistant"
                                     :content "done"}}))))]
      (let [handler (agent/make-agent {:cfg           {:tools ["echo"]}
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
                    (if-not (contains? req :tools)
                      {:message {:role "assistant" :content "stub title"}}
                      (do
                        (swap! requests conj req)
                        (if (= 1 (swap! calls inc))
                          {:message {:role "assistant"
                                     :content ""
                                     :tool_calls
                                     [{:id "call-1"
                                       :function {:name "chatter"
                                                  :arguments "{}"}}]}}
                          {:message {:role "assistant"
                                     :content "done"}}))))]
      (let [handler (agent/make-agent {:cfg           {}
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

(deftest git-registry-wrappers-delegate-to-storage
  (let [agent {:postgres :fake-postgres}
        repo  {:repoId "1" :name "clai" :url "https://example.com/clai.git"}
        worktree {:worktreeId "3"
                  :repoId     "1"
                  :repoName   "clai"
                  :repoUrl    "https://example.com/clai.git"
                  :branch     "main"
                  :directory  "/tmp/clai"}]
    (with-redefs [git/list-repos     (fn [client]
                                       (is (= :fake-postgres client))
                                       [repo])
                  git/load-repo      (fn [_ repo-id]
                                       (when (= "1" repo-id) repo))
                  git/select-repo    (fn [_ name]
                                       (when (= "clai" name) repo))
                  git/create-repo    (fn [_ input]
                                       (is (= {:name "clai" :url "u"} input))
                                       (assoc repo :name "clai" :url "u"))
                  git/update-repo    (fn [_ repo-id input]
                                       (when (= "1" repo-id)
                                         (merge repo input)))
                  git/delete-repo    (fn [_ repo-id]
                                       (= "1" repo-id))
                  git/list-worktrees (fn ([_]
                                        [worktree])
                                       ([_ repo-id]
                                        (if (= "1" repo-id)
                                          [worktree]
                                          [])))
                  git/load-worktree  (fn [_ worktree-id]
                                       (when (= "3" worktree-id) worktree))
                  git/create-worktree (fn [_ input]
                                        (is (= {:repoId    "1"
                                                :branch    "main"
                                                :directory "/tmp/clai"}
                                               input))
                                        worktree)
                  git/update-worktree (fn [_ worktree-id input]
                                        (when (= "3" worktree-id)
                                          (merge worktree input)))
                  git/delete-worktree (fn [_ worktree-id]
                                        (= "3" worktree-id))]
      (is (= {:repos [repo]} (agent/list-git-repos! agent {})))
      (is (= repo (agent/load-git-repo! agent {:repoId "1"})))
      (is (= repo (agent/load-git-repo! agent {:name "clai"})))
      (is (nil? (agent/load-git-repo! agent {})))
      (is (= "u" (:url (agent/create-git-repo! agent {:name "clai" :url "u"}))))
      (is (= "renamed" (:name (agent/update-git-repo!
                                agent {:repoId "1" :name "renamed" :url "u"}))))
      (is (nil? (agent/update-git-repo! agent {:repoId "9" :name "x" :url "u"})))
      (is (true? (agent/delete-git-repo! agent {:repoId "1"})))
      (is (false? (agent/delete-git-repo! agent {:repoId "9"})))

      (is (= {:worktrees [worktree]} (agent/list-git-worktrees! agent {})))
      (is (= {:worktrees [worktree]}
             (agent/list-git-worktrees! agent {:repoId "1"})))
      (is (= [] (:worktrees (agent/list-git-worktrees! agent {:repoId "2"}))))
      (is (= worktree (agent/load-git-worktree! agent {:worktreeId "3"})))
      (is (= worktree
             (agent/create-git-worktree! agent {:repoId    "1"
                                                :branch    "main"
                                                :directory "/tmp/clai"})))
      (is (= "dev" (:branch (agent/update-git-worktree!
                              agent {:worktreeId "3"
                                     :repoId      "1"
                                     :branch      "dev"
                                     :directory   "/tmp/clai"}))))
      (is (nil? (agent/update-git-worktree!
                 agent {:worktreeId "9"
                        :repoId      "1"
                        :branch      "dev"
                        :directory   "/tmp/clai"})))
      (is (true? (agent/delete-git-worktree! agent {:worktreeId "3"})))
      (is (false? (agent/delete-git-worktree! agent {:worktreeId "9"}))))))
