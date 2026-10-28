(ns clai.core
  "Main entrypoint for `clai`.

  Wires together the three halves of the project:

    * `clai.acp`          -- the agent side of the Agent Client Protocol,
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
  `clai.providers.ollama` (`OLLAMA_HOST`, `OLLAMA_API_KEY`). The model used
  for chat defaults to `gemma3` and can be overridden with the `CLAI_MODEL`
  environment variable. The per-prompt tool-round cap defaults to 8 and can
  be overridden with `CLAI_MAX_TOOL_ROUNDS`.

  ## Running

      lein run            # start the agent over stdio
      CLAI_MODEL=llama3.2 lein run

  A client (e.g. an ACP-compatible editor) launches this as a subprocess and
  speaks newline-delimited JSON-RPC 2.0 to it; see `clai.acp` for the wire
  format."
  (:require [clai.acp :as acp]
            [clai.providers.ollama :as ollama]
            [clai.tools :as tools])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private default-model
  "Model used for chat when `CLAI_MODEL` is unset."
  "gemma3")

(defn- configured-model
  "Resolve the model to chat with, honoring the `CLAI_MODEL` env var."
  []
  (or (System/getenv "CLAI_MODEL") default-model))

(defn- make-ollama-client
  "Build the Ollama client used for generating responses. Reads `OLLAMA_HOST`
  and `OLLAMA_API_KEY` from the environment via `ollama/make-client`."
  []
  (ollama/make-client))

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
    {:sessionId id}))

(defn- lookup-session
  "Return the session map for `id`, or nil."
  [id]
  (get @sessions id))

(defn- drop-session
  "Remove `id` from the session store."
  [id]
  (swap! sessions dissoc id)
  {})

(defn- append-history
  "Append a `{role content}` message to session `id` and return it."
  [id message]
  (swap! sessions update-in [id :history] (fnil conj []) message)
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
  simpler clients (and the echo example in `clai.acp`), a bare string or a
  single message map is also accepted."
  [prompt]
  (cond
    (nil? prompt)             []
    (string? prompt)          [{:role "user" :content prompt}]
    (map? prompt)             [(acp-message->ollama prompt)]
    (sequential? prompt)      (mapv acp-message->ollama prompt)
    :else                     [{:role "user" :content (str prompt)}]))

(defn- ollama->acp-content
  "Wrap a plain-text assistant reply as ACP content parts."
  [text]
  [{:type "text" :text text}])

;; ---------------------------------------------------------------------------
;; Tool-calling loop
;; ---------------------------------------------------------------------------

;; Cap on consecutive tool-call rounds per prompt, so a model that keeps
;; calling tools can't loop forever. Override with `CLAI_MAX_TOOL_ROUNDS`.
(def ^:private default-max-tool-rounds 8)

(defn- configured-max-tool-rounds
  "Resolve the tool-round cap, honoring `CLAI_MAX_TOOL_ROUNDS`."
  []
  (or (some-> (System/getenv "CLAI_MAX_TOOL_ROUNDS")
              (Integer/parseInt))
      default-max-tool-rounds))

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

(defn- describe-tool-call
  "Render one Ollama `tool_calls` entry as a short human-readable string."
  [call]
  (let [f (or (:function call) call)]
    (str "calling tool " (or (:name f) "?")
         " with " (pr-str (:arguments f)))))

(defn- run-tool-round
  "Execute the tool calls in `msg`, append the assistant message and each
  `tool`-role result to the session history, and return the updated history.

  Sends a brief `agent_message` update per tool call so the client can see
  activity. Does not call Ollama; the caller chats again with the new
  history."
  [conn session-id msg]
  (let [tool-calls (:tool_calls msg)]
    ;; Record the assistant message (with its tool_calls) verbatim so the
    ;; model sees its own request in the next round.
    (append-history session-id msg)
    (let [ctx      (session-tool-ctx session-id)
          results  (tools/execute-calls tool-calls ctx)]
      (doseq [call tool-calls]
        (acp/send-session-update conn session-id
          {:sessionUpdate "agent_message"
           :messageId     (str (UUID/randomUUID))
           :role          "agent"
           :content       (ollama->acp-content
                           (describe-tool-call call))}))
      (run! #(append-history session-id %) results)
      (session-history session-id))))

(defn- run-agent-loop
  "Drive the Ollama chat conversation for `session-id` to completion.

  Starts from the current session history, sends it to `/api/chat` with the
  registered tools, and loops: while the reply contains `tool_calls`, execute
  them (via `clai.tools`), append the results, and chat again. When the model
  replies with a plain message, record and return its content.

  On error (HTTP failure or hitting the round cap) returns a descriptive
  string rather than throwing, so the caller can relay it to the client."
  [conn client model session-id]
  (let [max-rounds (configured-max-tool-rounds)]
    (loop [round 0]
      (if (>= round max-rounds)
        (str "Stopped after " max-rounds
             " tool rounds without a final reply.")
        (let [resp (try
                     (ollama/chat client
                       {:model    model
                        :messages (session-history session-id)
                        :tools    (tools/specs)
                        :stream   false})
                     (catch Throwable e
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
                  content)))))))))

;; ---------------------------------------------------------------------------
;; Agent handler
;; ---------------------------------------------------------------------------

(defn ollama-agent
  "Build an `AgentHandler` that answers prompts using an Ollama chat model.

  Options:

    :client  the `clai.providers.ollama.Client` to use. Defaults to one built
             from the environment (`OLLAMA_HOST`, `OLLAMA_API_KEY`).
    :model   the model name to send to `/api/chat`. Defaults to the value of
             `CLAI_MODEL` or `gemma3`."
  ([] (ollama-agent nil))
  ([{:keys [client model]
     :or {client (make-ollama-client)
          model  (configured-model)}}]
   (reify acp/AgentHandler
     (handle-initialize [_ _conn {:keys [protocolVersion]}]
       {:protocolVersion (min acp/current-protocol-version
                              (long (or protocolVersion 2)))
        :info            {:name    "clai"
                          :title   "clai agent"
                          :version "0.1.0-SNAPSHOT"}
        :capabilities    {}})

     (handle-auth-login [_ _conn _params]
       (acp/acp-error acp/err-method))

     (handle-auth-logout [_ _conn _params]
       (acp/acp-error acp/err-method))

     (handle-session-new [_ _conn {:keys [cwd]}]
       (create-session cwd))

     (handle-session-list [_ _conn _params]
       {:sessions (for [[id {:keys [cwd]}] @sessions]
                    {:sessionId id :cwd cwd})})

     (handle-session-delete [_ _conn {:keys [sessionId]}]
       (drop-session sessionId))

     (handle-session-resume [this conn {:keys [sessionId cwd]}]
       ;; If the session is unknown, create a fresh one so resume degrades
       ;; to new rather than erroring.
       (when-not (lookup-session sessionId)
         (swap! sessions assoc sessionId {:cwd cwd :history []}))
       {:sessionId sessionId})

     (handle-session-close [_ _conn {:keys [sessionId]}]
       (drop-session sessionId))

     (handle-session-set-config-option [_ _conn _params]
       (acp/acp-error acp/err-method))

     (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
       (let [msg-id (str (UUID/randomUUID))]
         (if-not (lookup-session sessionId)
           ;; Unknown session: tell the client and bail.
           (do
             (acp/send-session-update conn sessionId
               {:sessionUpdate "agent_message"
                :messageId     msg-id
                :role          "agent"
                :content       (ollama->acp-content
                                (str "No session " sessionId))})
             (acp/send-session-update conn sessionId
               {:sessionUpdate "state_update"
                :state         "idle"
                :stopReason    "error"})
             {:messageId msg-id})
           ;; Append the incoming prompt, then drive the Ollama conversation
           ;; to completion -- executing any tool calls the model makes along
           ;; the way -- and relay the final reply.
           (let [incoming (acp-prompt->ollama-messages prompt)
                 _        (run! #(append-history sessionId %) incoming)
                 reply    (run-agent-loop conn client model sessionId)]
             (acp/send-session-update conn sessionId
               {:sessionUpdate "agent_message"
                :messageId     msg-id
                :role          "agent"
                :content       (ollama->acp-content reply)})
             (acp/send-session-update conn sessionId
               {:sessionUpdate "state_update"
                :state         "idle"
                :stopReason    "end_turn"})
             {:messageId msg-id}))))

     (on-session-cancel [_ conn {:keys [sessionId]}]
       ;; No background work to cancel for the synchronous handler; just
       ;; return to idle so the client can issue the next prompt.
       (acp/send-session-update conn sessionId
         {:sessionUpdate "state_update"
          :state         "idle"
          :stopReason    "cancelled"})
       nil)

     (on-cancel-request [_ _conn _params] nil)
     (on-notification [_ _conn _method _params] nil)
     (on-ext-request [_ _conn _method _params]
       (acp/acp-error acp/err-method)))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Start the `clai` agent over stdio: read ACP messages from stdin and answer
  `session/prompt` requests by calling the Ollama chat API. Blocks until stdin
  reaches EOF (the client disconnected)."
  [& _args]
  (acp/run (ollama-agent)))