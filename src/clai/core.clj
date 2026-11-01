(ns clai.core
  (:require [clai.log]
            [acp.agent :as acp]
            [acp.errors :as acp-errors]
            [clai.config :as config]
            [clai.state :as state]
            [clai.providers.ollama :as ollama]
            [clai.agent :as agent]
            [acp.agent.helpers :as helpers]
            [clai.tools :as tools]
            [clojure.string :as str]
            [malli.instrument :as mi]
            [taoensso.telemere :as tel])
  (:import [java.util UUID])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private default-model
  "Model used for chat when `CLAI_MODEL` is unset."
  "gemma3")

(defn- configured-model
  "Resolve the model to chat with. Precedence: `cfg`, then the `CLAI_MODEL`
  env var, then the default."
  [cfg]
  (or (:model cfg)
      (System/getenv "CLAI_MODEL")
      default-model))

(defn- make-ollama-client
  "Build the Ollama client used for generating responses. `cfg` is the loaded
  config map; its `:ollama` sub-map (with `:host`, `:api-key`, `:timeout`)
  is forwarded to `ollama/make-client`, and any key not supplied there falls
  back to the environment (`OLLAMA_HOST`, `OLLAMA_API_KEY`) and defaults."
  [cfg]
  (let [ollama-cfg (:ollama cfg)
        opts       (into {}
                         (keep (fn [k]
                                 (when-some [v (get ollama-cfg k)]
                                   [k v])))
                         [:host :api-key :timeout])]
    (ollama/make-client opts)))

(defn- create-session
  "Create and persist a new session, returning its `NewSessionResponse`."
  ([cwd] (create-session cwd nil))
  ([cwd model]
   (let [s  (state/create-session {:cwd cwd :model model})
         id (:sessionId s)]
     (tel/log! {:level :info
                :id    ::session-created
                :data  {:sessionId id :cwd cwd}}
               "Created session")
     {:sessionId id})))

(defn- register-session
  "Persist a session under an explicit `id`. Used by `session/resume` when a
  client resumes a session this agent has no record of, so resume degrades to
  new rather than erroring."
  [id cwd history]
  (state/save-session
   {:sessionId id
    :cwd       (str (or cwd "."))
    :params    {}
    :createdAt (str (java.time.Instant/now))
    :history   history}))

(defn- drop-session
  "Remove `id` from the persistent session store."
  [id]
  (state/delete-session id)
  (tel/log! {:level :debug
             :id    ::session-dropped
             :data  {:sessionId id}}
            "Dropped session")
  {})

;; ---------------------------------------------------------------------------
;; ACP <-> Ollama message conversion
;; ---------------------------------------------------------------------------

(defn- part->text
  "Extract a plain-text string from a single ACP content part. Non-text
  parts (images, etc.) are dropped, since Ollama chat wants text here."
  [part]
  (cond
    (string? part)            part
    (map? part)
    (cond
      (= (:type part) "text") (:text part)
      (some? (:text part))    (:text part)     ; tolerant of variants
      :else                   nil)
    :else nil))

(defn- content->text
  "Flatten an ACP `content` field (a string or a sequence of parts) to a
  single string, dropping nils and joining with newlines."
  [content]
  (cond
    (nil? content)        ""
    (string? content)     content
    (sequential? content) (->> content (map part->text) (remove nil?) (interpose "\n") (apply str))
    :else                 (str content)))

(defn- role->string
  "Normalize an ACP `role` (string or keyword) to its Ollama string form,
  defaulting to `user`."
  [role]
  (cond
    (nil? role)    "user"
    (string? role) role
    :else          (name role)))

(defn- acp-message->ollama
  "Convert one role-tagged ACP `Message` (`{:role ... :content [...]}`) to an
  Ollama chat message (`{:role ... :content \"...\"}`)."
  [{:keys [role content]}]
  {:role    (role->string role)
   :content (content->text content)})

(defn- prompt-entry->ollama
  "Convert one entry of an ACP `session/prompt` `:prompt` vector to an Ollama
  chat message.

  Per the ACP schema, `:prompt` is a vector of `ContentBlock`s (for example
  `{:type \"text\" :text \"...\"}`), all of which come from the user -- the
  blocks carry the text itself, not a `:content` field. A role-tagged message
  map is accepted too, for tolerance of simpler clients and the echo example
  in [[clai.acp.agent]]."
  [entry]
  (if (contains? entry :content)
    (acp-message->ollama entry)
    {:role "user" :content (content->text [entry])}))

(defn- acp-prompt->ollama-messages
  "Normalize the `:prompt` field of a `session/prompt` request to a vector of
  Ollama messages.

  ACP v2 sends `:prompt` as a vector of `ContentBlock`s, so the blocks are
  flattened into a single `user` message whose content is their concatenated
  text. A vector of role-tagged messages is also handled (one Ollama message
  per entry), as are a bare string and a single map."
  [prompt]
  (cond
    (nil? prompt)             []
    (string? prompt)          [{:role "user" :content prompt}]
    (map? prompt)             [(prompt-entry->ollama prompt)]
    (sequential? prompt)      (if (every? #(and (map? %) (contains? % :content))
                                          prompt)
                                (mapv prompt-entry->ollama prompt)
                                [{:role "user" :content (content->text prompt)}])
    :else                     [{:role "user" :content (str prompt)}]))

(defn- history-message->update
  "Convert one persisted history message to the `session/update` the client
  should see when a session is loaded. System and tool messages are internal
  and are not replayed; user/assistant messages become message chunks."
  [{:keys [role content]}]
  (let [role (cond
               (nil? role)     nil
               (string? role)  role
               (keyword? role) (name role)
               :else           (str role))
        text (content->text content)]
    (case role
      "user"      {:sessionUpdate "user_message_chunk"
                    :content       {:type "text" :text text}}
      "assistant" {:sessionUpdate "agent_message_chunk"
                    :content       {:type "text" :text text}}
      "agent"     {:sessionUpdate "agent_message_chunk"
                    :content       {:type "text" :text text}}
      nil)))

(defn- replay-session-history
  "Send the persisted `history` for `session-id` to the client as a sequence
  of `session/update` notifications."
  [conn session-id history]
  (let [updates (into [] (keep history-message->update) history)]
    (doseq [update updates]
      (acp/send-session-update conn session-id update))
    (tel/log! {:level :debug
               :id    ::history-replayed
               :data  {:sessionId session-id
                       :messages  (count updates)}}
              "Replayed session history")
    (count updates)))

(defn- handle-session-load
  "Handle an ACP `session/load` request: restore the persisted session, replay
  its conversation history to the client, and return a `LoadSessionResponse`."

  [conn {:keys [sessionId cwd]}]
  (cond
    (str/blank? (str sessionId))
    (acp-errors/acp-error acp-errors/err-params "Missing sessionId")

    :else
    (if-let [session (state/load-session sessionId)]
      (let [session (if (and (some? cwd) (not= (str cwd) (:cwd session)))
                      (state/save-session (assoc session :cwd (str cwd)))
                      session)
            history (or (:history session) [])]
        (tel/log! {:level :info
                   :id    ::session-loaded
                   :data  {:sessionId sessionId
                           :cwd       (:cwd session)
                           :messages  (count history)}}
                  "Loaded session")
        (replay-session-history conn sessionId history)
        {})
      {})))

;; ---------------------------------------------------------------------------
;; Tool-calling loop
;; ---------------------------------------------------------------------------

;; Cap on consecutive tool-call rounds per prompt, so a model that keeps
;; calling tools can't loop forever. Override with `CLAI_MAX_TOOL_ROUNDS`.
(def ^:private default-max-tool-rounds 8)

(defn- configured-max-tool-rounds
  "Resolve the tool-round cap. Precedence: `cfg`, then the
  `CLAI_MAX_TOOL_ROUNDS` env var, then the default."
  [cfg]
  (or (some-> cfg :max-tool-rounds)
      (some-> (System/getenv "CLAI_MAX_TOOL_ROUNDS")
              (Integer/parseInt))
      default-max-tool-rounds))

(defn- configured-tool-specs
  "Resolve the vector of Ollama tool definitions to offer. When `cfg` has a
  `:tools` key, only the listed built-in tool ids are enabled (in the given
  order; unknown ids are skipped). When `:tools` is absent, every registered
  tool is offered."
  [cfg]
  (if (contains? cfg :tools)
    (let [requested (:tools cfg)
          known     (set (keys (tools/registered-tools)))
          unknown   (remove known requested)]
      (when (seq unknown)
        (tel/log! {:level :warn
                   :id    ::unknown-configured-tools
                   :data  {:tools (vec unknown)}}
                  "Ignoring unknown tool ids from config"))
      (tools/specs-for requested))
    (tools/specs)))

(defn- session-tool-ctx
  "Build the context map handed to tool functions for `session-id`."
  [session-id]
  {:cwd (-> (state/load-session session-id) :cwd)})

(def ^:private tool-name->kind
  "Maps built-in tool ids to ACP `ToolKind` values. Unknown tools fall
  back to `other`."
  {"read_file"      "read"
   "write_file"     "edit"
   "list_directory" "search"
   "run_command"    "execute"
   "eval_clojure"   "execute"})

(defn- tool-call-kind
  "Resolve the ACP `ToolKind` for an Ollama tool call."
  [call]
  (or (tool-name->kind (ollama/tool-call-name call)) "other"))

(defn- tool-call-raw-input
  "Return the parsed arguments of an Ollama tool call as a map suitable for
  the ACP `rawInput` field."
  [call]
  (let [f    (or (:function call) call)
        args (tools/parse-arguments (:arguments f))]
    (or args {})))

(defn- send-tool-call-started
  "Emit a `tool_call` SessionUpdate announcing that `call` is starting."
  [conn session-id call]
  (let [id (ollama/tool-call-id call)]
    (acp/send-session-update conn session-id
      {:_meta        nil
       :sessionUpdate "tool_call"
       :kind          (tool-call-kind call)
       :name          (ollama/tool-call-name call)
       :rawInput      (tool-call-raw-input call)
       :title         (ollama/tool-call-title call)
       :toolCallId    id
       :status        "in_progress"})
    id))

(defn- tool-result-content
  "Wrap a tool result string as an ACP `ToolCallContent` vector.

  ACP clients render tool output from a `tool_call_update`'s `:content`
  (`Vec<ToolCallContent>`), not from `:rawOutput` -- the latter is only
  debug metadata, so a result carried solely there is invisible. Each entry
  is a `{\"content\" {...ContentBlock}}` wrapper around a text block."
  [result]
  [{:type    "content"
    :content {:type "text" :text (str result)}}])

(defn- send-tool-call-finished
  "Emit the finished state of `call`: a full `tool_call` SessionUpdate whose
  `:status` is `completed`/`failed`, plus a `tool_call_update` carrying the
  same result.

  Some ACP clients only render a tool's output from a `ToolCall` (i.e. a
  `tool_call` update) with a terminal status, and hide results delivered
  solely through `tool_call_update`, so both are sent. See
  [[tool-result-content]] for why the output lives in `:content`."
  [conn session-id call id result]
  (let [failed? (str/starts-with? result "Error:")
        status  (if failed? "failed" "completed")
        content (tool-result-content result)]
  (acp/send-session-update conn session-id
    {:_meta        nil
     :sessionUpdate "tool_call_update"
     :content       content
     :rawOutput     {:result result}
     :toolCallId    id
     :status        status})
    ))

(defn- run-tool-round
  "Execute the tool calls in `msg`, append the assistant message and each
  `tool`-role result to the session history, and return the updated history.

  Emits a `tool_call` SessionUpdate as each call starts, then a finished
  `tool_call` (terminal status) plus a `tool_call_update` when it completes,
  so the client can render structured tool activity -- and its output --
  instead of plain agent messages. Does not call
  Ollama; the caller chats again with the new history."
  [conn session-id msg]
  (let [tool-calls (:tool_calls msg)]
    ;; Record the assistant message (with its tool_calls) verbatim so the
    ;; model sees its own request in the next round.
    (state/append-history session-id msg)
    (let [ctx (session-tool-ctx session-id)]
      (doseq [call tool-calls]
        (let [tool-name (or (ollama/tool-call-name call) "?")
              call-id   (send-tool-call-started conn session-id call)]
          (tel/log! {:level :info
                     :id    ::tool-call
                     :data  {:sessionId session-id
                             :tool      tool-name}}
                    "Executing tool call")
          (let [[id args] (tools/tool-call->id+args call)
                result    (tools/invoke (or id tool-name) args ctx)]
            (send-tool-call-finished conn session-id call call-id result)
            (state/append-history session-id
                            (cond-> {:role    "tool"
                                     :content result
                                     :name    (or id tool-name)}
                              (some? (:id call)) (assoc :tool_call_id (:id call)))))))
      (tel/log! {:level :debug
                 :id    ::tool-round-complete
                 :data  {:sessionId session-id
                         :results   (count tool-calls)}}
                "Tool round complete")
      (state/session-history session-id))))

(defn- run-agent-loop
  "Drive the Ollama chat conversation for `session-id` to completion.

  Starts from the current session history, sends it to `/api/chat` with the
  given `tool-specs`, and loops: while the reply contains `tool_calls`,
  execute them (via `clai.tools`), append the results, and chat again. When
  the model replies with a plain message, record and return its content.

  `chat-opts` is a map of extra Ollama request keys (e.g. `:options`,
  `:think`, `:format`) merged into each `/api/chat` request.

  On error (HTTP failure or hitting the round cap) returns a descriptive
  string rather than throwing, so the caller can relay it to the client."
  [conn client model tool-specs chat-opts max-rounds session-id]
  (loop [round 0]
    (if (>= round max-rounds)
      (do
        (tel/log! {:level :warn
                   :id    ::tool-round-cap
                   :data  {:sessionId session-id
                           :maxRounds max-rounds}}
                  "Stopped after tool round cap without a final reply")
        (str "Stopped after " max-rounds
             " tool rounds without a final reply."))
      (let [history (state/session-history session-id)
            _       (tel/log! {:level :debug
                               :id    ::chat-request
                               :data  {:sessionId session-id
                                       :round     round
                                       :model     model
                                       :messages  (count history)
                                       :tools     (count tool-specs)}}
                              "Sending Ollama chat request")
            resp    (try
                      (ollama/chat client
                        (merge {:model    model
                                :messages history
                                :tools    tool-specs
                                :stream   false}
                               chat-opts))
                      (catch Throwable e
                        (tel/error! {:id   ::chat-request-failed
                                     :data {:sessionId session-id
                                            :round     round
                                            :model     model}}
                                    e)
                        {:__error__ (ex-message e)}))]
        (cond
          (:__error__ resp)
          (str "Ollama request failed: " (:__error__ resp))

          :else
          (let [msg (ollama/assistant-message resp)]
            (if (ollama/has-tool-calls? msg)
              (do (run-tool-round conn session-id msg)
                  (recur (inc round)))
              (let [content (or (:content msg) "")]
                (state/append-history session-id
                                {:role "assistant" :content content})
                (tel/log! {:level :debug
                           :id    ::chat-response
                           :data  {:sessionId session-id
                                   :round     round
                                   :chars     (count content)}}
                          "Received Ollama chat response")
                content))))))))

;; ---------------------------------------------------------------------------
;; Agent handler
;; ---------------------------------------------------------------------------

(defn ollama-agent
  "Build an `AgentHandler` that answers prompts using an Ollama chat model.

  Options (all optional; defaults come from `cfg` then the environment):

    :cfg     the loaded config map (`clai.config/load-config`). Defaults to
             `(config/load-config)`.
    :client  the `clai.providers.ollama.Client` to use. Defaults to one built
             from `cfg` and the environment (`OLLAMA_HOST`, `OLLAMA_API_KEY`).
    :model   the model name to send to `/api/chat`. Defaults to `cfg`'s
             `:model`, then `CLAI_MODEL`, then `gemma3`."
  ([] (ollama-agent nil))
  ([opts]
   (let [cfg         (:cfg opts (config/load-config))
         client      (:client opts (make-ollama-client cfg))
         model       (:model opts (configured-model cfg))
         tool-specs  (configured-tool-specs cfg)
         max-rounds  (configured-max-tool-rounds cfg)
         sys-prompt  (:system-prompt cfg)
         chat-opts   (into {}
                            (keep (fn [k]
                                    (when-some [v (get cfg k)]
                                      [k v])))
                            [:options :think :format])]
     (tel/log! {:level :info
                :id    ::agent-configured
                :data  {:model          model
                        :tools          (count tool-specs)
                        :max-rounds     max-rounds
                        :system-prompt? (some? sys-prompt)
                        :host           (:host client)}}
               "Configured clai agent")
     (reify acp/AgentHandler
       (handle-initialize [_ _conn {:keys [protocolVersion]}]
         (let [negotiated (min acp/protocol-version-2
                               (long (or protocolVersion 2)))
               capabilities {:loadSession true
                             :sessionCapabilities {:list   {}
                                                   :delete {}
                                                   :resume {}
                                                   :close  {}}}
               info         {:name    "clai"
                             :title   "clai agent"
                             :version "0.1.0-SNAPSHOT"}]
           (tel/log! {:level :debug
                      :id    ::initialize
                      :data  {:client-protocol protocolVersion
                              :negotiated      negotiated}}
                     "Initialized ACP connection")
           {:protocolVersion   negotiated
            :info              info
            :agentInfo         info
            :capabilities      capabilities
            :agentCapabilities capabilities}))

       (handle-authenticate [_ _conn _params]
         (acp-errors/acp-error acp-errors/err-method))

       (handle-logout [_ _conn _params]
         (acp-errors/acp-error acp-errors/err-method))

       (handle-session-new [_ _conn {:keys [cwd]}]
         (let [res (create-session cwd model)]
           (when sys-prompt
             (state/append-history (:sessionId res)
                             {:role "system" :content sys-prompt}))
           res))

       (handle-session-list [_ _conn {:keys [cwd]}]
         (let [sessions (cond->> (state/list-session-summaries)
                          (some? cwd) (filter #(= cwd (:cwd %))))]
           (tel/log! {:level :debug
                      :id    ::session-list
                      :data  {:count (count sessions)}}
                     "Listed sessions")
           {:sessions (mapv (fn [{:keys [sessionId cwd updatedAt]}]
                              (cond-> {:sessionId sessionId
                                       :cwd       (or cwd "")}
                                (some? updatedAt) (assoc :updatedAt updatedAt)))
                            sessions)}))

       (handle-session-delete [_ _conn {:keys [sessionId]}]
         (drop-session sessionId))

       (handle-session-resume [_ _ {:keys [sessionId cwd]}]
         ;; If the session is unknown, register a fresh one under the
         ;; requested id (with the configured system prompt, if any) so
         ;; resume degrades to new rather than erroring.
         (if-let [session (state/load-session sessionId)]
           (do
             (when (and (some? cwd) (not= (str cwd) (:cwd session)))
               (state/save-session (assoc session :cwd (str cwd))))
             (tel/log! {:level :debug
                        :id    ::session-resumed
                        :data  {:sessionId sessionId}}
                       "Resumed existing session"))
           (do
             (register-session sessionId cwd
                               (if sys-prompt
                                 [{:role "system" :content sys-prompt}]
                                 []))
             (tel/log! {:level :info
                        :id    ::session-registered
                        :data  {:sessionId sessionId :cwd cwd}}
                       "Registered session during resume")))
         {:sessionId sessionId})

       (handle-session-close [_ _conn {:keys [sessionId]}]
         ;; Closing frees the active session but keeps its persisted history so
         ;; the client can load it again later; `session/delete` removes it.
         (tel/log! {:level :debug
                    :id    ::session-closed
                    :data  {:sessionId sessionId}}
                   "Closed session")
         {})

       (handle-session-set-config-option [_ _conn _params]
         (acp-errors/acp-error acp-errors/err-method))

       (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
         (let [msg-id (str (UUID/randomUUID))]
           (if-not (state/load-session sessionId)
             ;; Unknown session: tell the client and bail.
             (do
               (tel/log! {:level :warn
                          :id    ::unknown-session-prompt
                          :data  {:sessionId sessionId}}
                         "Received prompt for unknown session")
               (acp/send-agent-message-chunk conn sessionId
                                  (str "No session " sessionId))
               {:stopReason "end_turn"})
             ;; Append the incoming prompt, then drive the Ollama conversation
             ;; to completion -- executing any tool calls the model makes along
             ;; the way -- and relay the final reply.
             (let [incoming (acp-prompt->ollama-messages prompt)
                   _        (run! #(state/append-history sessionId %) incoming)
                   _        (tel/log! {:level :info
                                      :id    ::prompt-received
                                      :data  {:sessionId sessionId
                                              :messages  (count incoming)
                                              :chars     (reduce + 0
                                                                 (map #(count (str (:content %)))
                                                                      incoming))}}
                                     "Received prompt")
                   reply    (run-agent-loop conn client model
                                            tool-specs chat-opts
                                            max-rounds sessionId)]
               (tel/log! {:level :info
                          :id    ::prompt-complete
                          :data  {:sessionId sessionId
                                  :messageId msg-id
                                  :chars     (count (str reply))}}
                         "Prompt complete")
               (acp/send-agent-message-chunk conn sessionId (str reply))
               {:stopReason "end_turn"}))))

       (on-session-cancel [_ _conn {:keys [sessionId]}]
         ;; No background work to cancel for the synchronous handler; the
         ;; in-flight `session/prompt` already returns on its own.
         (tel/log! {:level :debug
                    :id    ::session-cancel
                    :data  {:sessionId sessionId}}
                   "Session cancelled")
         nil)

       (on-cancel-request [_ _conn params]
         (tel/log! {:level :debug
                    :id    ::cancel-request
                    :data  {:requestId (:requestId params)}}
                   "Cancel request received")
         nil)
       (on-notification [_ _conn method _params]
         (tel/log! {:level :debug
                    :id    ::extension-notification
                    :data  {:method method}}
                   "Ignoring extension notification")
         nil)
       (on-ext-request [_ conn method params]
         (if (= method "session/load")
           (handle-session-load conn params)
           (do
             (tel/log! {:level :debug
                        :id    ::extension-request
                        :data  {:method method}}
                       "Rejecting extension request")
             (acp-errors/acp-error acp-errors/err-method))))))))

     (mi/collect!)
;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Start the `clai` agent over stdio: read ACP messages from stdin and answer
  `session/prompt` requests by calling the Ollama chat API. Blocks until stdin
  reaches EOF (the client disconnected)."
  [& _args]
  (tel/log! {:level :info :id ::agent-starting}
            "Starting clai ACP agent over stdio")
  (try
    (acp/run (ollama-agent))
    (catch Throwable e
      (tel/error! {:id ::agent-failed} e)
      (throw e))
    (finally
      (tel/log! {:level :info :id ::agent-stopping}
                "clai ACP agent stopped"))))
