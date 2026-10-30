(ns clai.core
  "Main entrypoint for `clai`.

  Wires together the three halves of the project:

    * [[clai.acp.agent]]  -- the agent side of the Agent Client Protocol,
                              spoken over stdio.
    * `clai.providers.ollama` -- an HTTP client for a local or cloud Ollama
                              server, used to generate agent responses.
    * `clai.tools`        -- a registry of tools the model may call.

  `(-main)` starts the ACP server with an `AgentHandler` that, on each
  `session/prompt`, forwards the conversation to Ollama's `/api/chat` and
  relays the reply back to the client as `session/update` notifications.

  ## Tool calling

  When the model's reply carries `tool_calls`, the handler executes each
  call against `clai.tools`, appends `{:role \"tool\" ...}` result messages
  to the history, and chats again -- looping until the model replies with a
  plain assistant message (or the round cap in `CLAI_MAX_TOOL_ROUNDS` is hit).
  This is the standard Ollama tool-calling flow; see `clai.tools` for how to
  register additional tools.

  ## Configuration

  The Ollama client is built from the same environment variables as
  `clai.providers.ollama` (`OLLAMA_HOST`, `OLLAMA_API_KEY`), unless a config
  file overrides them (see `clai.config`). The model used
  for chat defaults to `gemma3` and can be overridden via the config file's
  `:model` key or the `CLAI_MODEL` environment variable. The per-prompt
  tool-round cap defaults to 8 and can be overridden via the config file's
  `:max-tool-rounds` key or `CLAI_MAX_TOOL_ROUNDS`.

  ## Running

      lein run            # start the agent over stdio
      CLAI_MODEL=llama3.2 lein run
      CLAI_CONFIG=./my-clai.edn lein run

  A client (e.g. an ACP-compatible editor) launches this as a subprocess and
  speaks newline-delimited JSON-RPC 2.0 to it; see [[clai.acp.agent]] for the
  wire format."
  (:require [clai.log]
            [acp.agent :as acp]
            [acp.errors :as acp-errors]
            [clai.config :as config]
            [clai.providers.ollama :as ollama]
            [clai.tools :as tools]
            [clojure.string :as str]
            [taoensso.telemere :as tel]
            [acp.schema.content :refer [->TextContent]]
            [acp.schema.core :as schemas])
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

;; ---------------------------------------------------------------------------
;; Session state
;; ---------------------------------------------------------------------------

;; In-memory session store: `{session-id {:cwd ... :history [message ...]}}`.
;; Each Ollama message is `{:role \"user\"|\"assistant\"|\"tool\" :content \"...\"}`;
;; assistant messages with `:tool_calls` and `tool`-role result messages are
;; kept verbatim so the conversation history round-trips through `/api/chat`.
(def ^:private sessions (atom {}))

(defn- create-session
  "Register a new session and return its `NewSessionResponse`."
  [cwd]
  (let [id (str (UUID/randomUUID))]
    (swap! sessions assoc id {:cwd cwd :history []})
    (tel/log! {:level :info
               :id    ::session-created
               :data  {:sessionId id :cwd cwd}}
              "Created session")
    {:sessionId id}))

(defn- lookup-session
  "Return the session map for `id`, or nil."
  [id]
  (get @sessions id))

(defn- drop-session
  "Remove `id` from the session store."
  [id]
  (swap! sessions dissoc id)
  (tel/log! {:level :debug
             :id    ::session-dropped
             :data  {:sessionId id}}
            "Dropped session")
  {})

(defn- append-history
  "Append a `{role content}` message to session `id` and return it."
  [id message]
  (swap! sessions update-in [id :history] (fnil conj []) message)
  (tel/log! {:level :debug
             :id    ::history-append
             :data  {:sessionId id
                     :role      (:role message)
                     :chars     (count (str (:content message)))}}
            "Appended message to history")
  message)

(defn- session-history
  "Return the Ollama message history for session `id` (empty if unknown)."
  [id]
  (-> (lookup-session id) :history (or [])))

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

(defn- acp-message->ollama
  "Convert one ACP `Message` (`{:role ... :content [...]}`) to an Ollama chat
  message (`{:role ... :content \"...\"}`)."
  [{:keys [role content]}]
  {:role    (name (or role "user"))
   :content (content->text content)})

(defn- acp-prompt->ollama-messages
  "Normalize the `:prompt` field of a `session/prompt` request to a vector of
  Ollama messages.

  ACP v2 sends `:prompt` as a vector of `Message` objects. To be tolerant of
  simpler clients (and the echo example in [[clai.acp.agent]]), a bare string
  or a single message map is also accepted."
  [prompt]
  (cond
    (nil? prompt)             []
    (string? prompt)          [{:role "user" :content prompt}]
    (map? prompt)             [(acp-message->ollama prompt)]
    (sequential? prompt)      (mapv acp-message->ollama prompt)
    :else                     [{:role "user" :content (str prompt)}]))

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
  {:cwd (-> (lookup-session session-id) :cwd)})

(defn- assistant-message
  "Return the assistant `:message` map from an Ollama `/api/chat` response,
  normalized to at least a map with role `assistant` and empty content."
  [resp]
  (-> resp :message (or {:role "assistant" :content ""})))

(defn- has-tool-calls?
  "True when an Ollama assistant message requests one or more tool calls."
  [msg]
  (and (map? msg) (seq (:tool_calls msg))))

(defn- tool-call-id
  "Return the Ollama tool call's id, generating one when absent."
  [call]
  (or (:id call) (str (UUID/randomUUID))))

(defn- tool-call-name
  "Extract the function name from an Ollama `tool_calls` entry."
  [call]
  (let [f (or (:function call) call)]
    (:name f)))

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
  (or (tool-name->kind (tool-call-name call)) "other"))

(defn- tool-call-raw-input
  "Return the parsed arguments of an Ollama tool call as a map suitable for
  the ACP `rawInput` field."
  [call]
  (let [f    (or (:function call) call)
        args (tools/parse-arguments (:arguments f))]
    (or args {})))

(defn- tool-call-title
  "Render a short human-readable title for an Ollama tool call."
  [call]
  (str (or (tool-call-name call) "?")
       " " (pr-str (:arguments (or (:function call) call)))))

(defn- send-tool-call-started
  "Emit a `tool_call` SessionUpdate announcing that `call` is starting."
  [conn session-id call]
  (let [id (tool-call-id call)]
    (acp/send-session-update conn session-id
      {:_meta        nil
       :sessionUpdate "tool_call"
       :content       []
       :kind          (tool-call-kind call)
       :locations     []
       :name          (tool-call-name call)
       :rawInput      (tool-call-raw-input call)
       :rawOutput     {}
       :title         (tool-call-title call)
       :toolCallId    id
       :status        "in_progress"})
    id))

(defn- send-tool-call-finished
  "Emit a `tool_call_update` SessionUpdate with the result of `call`."
  [conn session-id id result]
  (let [failed? (str/starts-with? result "Error:")]
    (acp/send-session-update conn session-id
      {:_meta        nil
       :sessionUpdate "tool_call_update"
       :content       nil
       :kind          nil
       :locations     nil
       :name          nil
       :rawInput      {}
       :rawOutput     {:result result}
       :title         nil
       :toolCallId    id
       :status        (if failed? "failed" "completed")})))

(defn- run-tool-round
  "Execute the tool calls in `msg`, append the assistant message and each
  `tool`-role result to the session history, and return the updated history.

  Emits a `tool_call` SessionUpdate as each call starts and a
  `tool_call_update` SessionUpdate when it finishes, so the client can render
  structured tool activity instead of plain agent messages. Does not call
  Ollama; the caller chats again with the new history."
  [conn session-id msg]
  (let [tool-calls (:tool_calls msg)]
    ;; Record the assistant message (with its tool_calls) verbatim so the
    ;; model sees its own request in the next round.
    (append-history session-id msg)
    (let [ctx (session-tool-ctx session-id)]
      (doseq [call tool-calls]
        (let [tool-name (or (tool-call-name call) "?")
              call-id   (send-tool-call-started conn session-id call)]
          (tel/log! {:level :info
                     :id    ::tool-call
                     :data  {:sessionId session-id
                             :tool      tool-name}}
                    "Executing tool call")
          (let [[id args] (tools/tool-call->id+args call)
                result    (tools/invoke (or id tool-name) args ctx)]
            (send-tool-call-finished conn session-id call-id result)
            (append-history session-id
                            (cond-> {:role    "tool"
                                     :content result
                                     :name    (or id tool-name)}
                              (some? (:id call)) (assoc :tool_call_id (:id call)))))))
      (tel/log! {:level :debug
                 :id    ::tool-round-complete
                 :data  {:sessionId session-id
                         :results   (count tool-calls)}}
                "Tool round complete")
      (session-history session-id))))

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
      (let [history (session-history session-id)
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
          (let [msg (assistant-message resp)]
            (if (has-tool-calls? msg)
              (do (run-tool-round conn session-id msg)
                  (recur (inc round)))
              (let [content (or (:content msg) "")]
                (append-history session-id
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
                               (long (or protocolVersion 2)))]
           (tel/log! {:level :debug
                      :id    ::initialize
                      :data  {:client-protocol protocolVersion
                              :negotiated      negotiated}}
                     "Initialized ACP connection")
           {:protocolVersion negotiated
            :info            {:name    "clai"
                              :title   "clai agent"
                              :version "0.1.0-SNAPSHOT"}
            :capabilities    {}}))

       (handle-authenticate [_ _conn _params]
         (acp-errors/acp-error acp-errors/err-method))

       (handle-logout [_ _conn _params]
         (acp-errors/acp-error acp-errors/err-method))

       (handle-session-new [_ _conn {:keys [cwd]}]
         (let [res (create-session cwd)]
           (when sys-prompt
             (append-history (:sessionId res)
                             {:role "system" :content sys-prompt}))
           res))

       (handle-session-list [_ _conn _params]
         (tel/log! {:level :debug
                    :id    ::session-list
                    :data  {:count (count @sessions)}}
                   "Listed sessions")
         {:sessions (for [[id {:keys [cwd]}] @sessions]
                      {:sessionId id :cwd cwd})})

       (handle-session-delete [_ _conn {:keys [sessionId]}]
         (drop-session sessionId))

       (handle-session-resume [_ _ {:keys [sessionId cwd]}]
         ;; If the session is unknown, register a fresh one under the
         ;; requested id (with the configured system prompt, if any) so
         ;; resume degrades to new rather than erroring.
         (if (lookup-session sessionId)
           (tel/log! {:level :debug
                      :id    ::session-resumed
                      :data  {:sessionId sessionId}}
                     "Resumed existing session")
           (do
             (swap! sessions assoc sessionId
                    {:cwd     cwd
                     :history (if sys-prompt
                                [{:role "system" :content sys-prompt}]
                                [])})
             (tel/log! {:level :info
                        :id    ::session-registered
                        :data  {:sessionId sessionId :cwd cwd}}
                       "Registered session during resume")))
         {:sessionId sessionId})

       (handle-session-close [_ _conn {:keys [sessionId]}]
         (drop-session sessionId))

       (handle-session-set-config-option [_ _conn _params]
         (acp-errors/acp-error acp-errors/err-method))

       (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
         (let [msg-id (str (UUID/randomUUID))]
           (if-not (lookup-session sessionId)
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
                   _        (run! #(append-history sessionId %) incoming)
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
               (acp/send-session-update conn sessionId
                  (schemas/->SessionNotification sessionId {
                    :type "agent_message_chunk"
                    :content (->TextContent reply)
                    }))
               {:stopReason "end_turn"}))))

       (on-session-cancel [_ conn {:keys [sessionId]}]
         ;; No background work to cancel for the synchronous handler; just
         ;; return to idle so the client can issue the next prompt.
         (tel/log! {:level :debug
                    :id    ::session-cancel
                    :data  {:sessionId sessionId}}
                   "Session cancelled")
         (acp/send-session-update conn sessionId
           {:sessionUpdate "state_update"
            :state         "idle"
            :stopReason    "cancelled"})
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
       (on-ext-request [_ _conn method _params]
         (tel/log! {:level :debug
                    :id    ::extension-request
                    :data  {:method method}}
                   "Rejecting extension request")
         (acp-errors/acp-error acp-errors/err-method))))))

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
