(ns clai.agent.loop
  "The tool-calling loop behind one `session/prompt`.

  Sends the session history to Ollama's `/api/chat`, executes any tool calls
  the model requests through the agent's tool registry, records the results in
  the session history, and repeats until the model produces a final text reply
  (or the configured round cap is reached). Tool activity is streamed to the
  ACP client as `tool_call` / `tool_call_update` session updates, and model
  reasoning returned by Ollama in an assistant message's `:thinking` field is
  streamed as `agent_thought_chunk` updates."
  (:require [acp.agent.helpers :as helpers]
            [clai.log]
            [clai.providers.ollama :as ollama]
            [clai.state :as state]
            [clai.tools :as tools]
            [clai.util :as util]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Session context
;; ---------------------------------------------------------------------------

(defn- session-tool-ctx
  "Build the context map handed to tool functions for `session-id`. Besides
  the `:cwd`, it carries the loop's live client, model, registry, and limits so
  tools like `spawn_agent` can run their own conversations."
  [session-id {:keys [client model tool-registry chat-opts max-rounds
                      agent-depth]}]
  {:cwd           (:cwd (state/load-session session-id))
   :session-id    session-id
   :client        client
   :model         model
   :tool-registry tool-registry
   :chat-opts     chat-opts
   :max-rounds    max-rounds
   :agent-depth   (long (or agent-depth 0))})

;; ---------------------------------------------------------------------------
;; ACP tool-call updates
;; ---------------------------------------------------------------------------

(defn- tool-call-raw-input
  "Return the parsed arguments of an Ollama tool call, as a map suitable for
  the ACP `rawInput` field."
  [call]
  (second (tools/tool-call->id+args call)))

(defn- send-tool-call-started
  "Emit a `tool_call` SessionUpdate announcing that `call` is starting.
  Returns the id the update was sent under."
  [conn session-id call]
  (let [id (ollama/tool-call-id call)]
    (helpers/send-tool-call-started conn session-id id
      :raw-input (tool-call-raw-input call)
      :name      (ollama/tool-call-name call)
      :title     (ollama/tool-call-title call))
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

(defn- send-tool-call-update
  "Emit a `tool_call_update` for tool call `id` carrying `content`. When
  `status` is non-nil it is sent as the update's terminal status.

  ACP treats the update's `:content` as a *replacement* for what the client
  already holds, so callers always pass the full output collected so far --
  see [[send-tool-call-output]] and [[send-tool-call-finished]]."
  ([conn session-id id content]
   (send-tool-call-update conn session-id id content nil))
  ([conn session-id id content status]
   (helpers/send-session-update conn session-id
     (cond-> {:_meta        nil
              :sessionUpdate "tool_call_update"
              :content       (tool-result-content content)
              :toolCallId    id}
       status (assoc :status status)))))

(defn- send-tool-call-finished
  "Emit the finished state of tool call `id` as a `tool_call_update` carrying
  the result and a terminal `:status` (`completed`/`failed`).

  Some ACP clients only render a tool's output from a terminal-status update,
  so the result is attached to the same update that closes the call. See
  [[tool-result-content]] for why the output lives in `:content`."
  [conn session-id id result]
  (send-tool-call-update conn session-id id result
                         (if (str/starts-with? result "Error:")
                           "failed"
                           "completed")))

(defn- send-tool-call-output
  "Emit a `tool_call_update` carrying the output a running tool has produced
  so far, so long-running work (a chatty `run_command`, say) is visible while
  it happens instead of only once it finishes.

  No `:status` is sent: the call is still in progress until
  [[send-tool-call-finished]] closes it."
  [conn session-id id output]
  (send-tool-call-update conn session-id id output))

;; ---------------------------------------------------------------------------
;; ACP thought updates
;; ---------------------------------------------------------------------------

(defn- send-thought
  "Relay the model's reasoning text in `msg` to the client.

  Ollama returns chain-of-thought for thinking models in the assistant
  message's `:thinking` field. ACP clients render that as an
  `agent_thought_chunk`, distinct from the final `agent_message_chunk`, so it
  is sent through `acp.agent.helpers/send-thought-text`. Messages without
  (non-blank) thinking are ignored."
  [conn session-id msg]
  (let [thinking (:thinking msg)]
    (when-not (str/blank? (str thinking))
      (helpers/send-thought-text conn session-id (str thinking)))))

;; ---------------------------------------------------------------------------
;; Tool rounds
;; ---------------------------------------------------------------------------

(defn- call-tool-ctx
  "Build the context for one tool call: the session context plus an
  `:on-output` callback that relays a tool's incremental output to the client
  as `tool_call_update`s.

  Tools that ignore `:on-output` -- every built-in but `run_command` -- are
  unaffected; the callback stays out of the context when there is no client
  to stream to (a sub-agent's child tools, or a bare `invoke`)."
  [conn session-id call-id ctx]
  (if (and conn call-id)
    (assoc ctx :on-output
           (fn [output]
             (send-tool-call-output conn session-id call-id output)))
    ctx))

(defn- execute-tool-call
  "Run one Ollama tool call, streaming its activity to the client, and return
  the `tool`-role history message recording its result."
  [conn session-id ctx call]
  (let [tool-name (or (ollama/tool-call-name call) "?")
        call-id   (send-tool-call-started conn session-id call)
        [id args] (tools/tool-call->id+args call)]
    (tel/log! {:level :info
               :id    ::tool-call
               :data  {:sessionId session-id
                       :tool      tool-name}}
              "Executing tool call")
    (let [result (tools/invoke (:tool-registry ctx)
                               (or id tool-name)
                               args
                               (call-tool-ctx conn session-id call-id ctx))]
      (send-tool-call-finished conn session-id call-id result)
      (tools/tool-result-message call (or id tool-name) result))))

(defn- run-tool-round
  "Execute the tool calls in `msg` against the loop's tool registry, append the
  assistant message and each `tool`-role result to the session history, and
  return the updated history.

  Emits a `tool_call` SessionUpdate as each call starts, then a finished
  `tool_call_update` when it completes, so the client can render structured
  tool activity -- and its output -- instead of plain agent messages. Tools
  that produce output incrementally (e.g. `run_command`) push it out through
  the context's `:on-output` callback as they go, which [[call-tool-ctx]]
  turns into further `tool_call_update`s. Does not call Ollama; the caller
  chats again with the new history."
  [conn session-id msg opts]
  (let [tool-calls (:tool_calls msg)
        ctx        (session-tool-ctx session-id opts)]
    ;; Record the assistant message (with its tool_calls) verbatim so the
    ;; model sees its own request in the next round.
    (state/append-history session-id msg)
    (doseq [call tool-calls]
      (state/append-history session-id
                            (execute-tool-call conn session-id ctx call)))
    (tel/log! {:level :debug
               :id    ::tool-round-complete
               :data  {:sessionId session-id
                       :results   (count tool-calls)}}
              "Tool round complete")
    (state/session-history session-id)))

;; ---------------------------------------------------------------------------
;; Agent loop
;; ---------------------------------------------------------------------------

(defn- chat-once
  "Send one non-streaming `/api/chat` request for `history`.

  Returns `{:message <assistant message>}` on success or `{:error <text>}` on
  failure, so the loop can relay the failure to the client instead of
  throwing."
  [session-id {:keys [client model tool-specs chat-opts]} history round]
  (tel/log! {:level :debug
             :id    ::chat-request
             :data  {:sessionId session-id
                     :round     round
                     :model     model
                     :messages  (count history)
                     :tools     (count tool-specs)}}
            "Sending Ollama chat request")
  (try
    {:message (ollama/assistant-message
               (ollama/chat client
                            (merge {:model    model
                                    :messages history
                                    :tools    tool-specs
                                    :stream   false}
                                   chat-opts)))}
    (catch Throwable e
      (tel/error! {:id   ::chat-request-failed
                   :data {:sessionId session-id
                          :round     round
                          :model     model}}
                  e)
      {:error (str "Ollama request failed: " (util/error-message e))})))

(defn- finish-reply
  "Record the assistant's final `msg` in the session history and return its
  text."
  [session-id msg round]
  (let [content  (or (:content msg) "")
        thinking (:thinking msg)]
    (state/append-history session-id
                          (cond-> {:role "assistant" :content content}
                            (not (str/blank? (str thinking)))
                            (assoc :thinking (str thinking))))
    (tel/log! {:level :debug
               :id    ::chat-response
               :data  {:sessionId session-id
                       :round     round
                       :chars     (count content)}}
              "Received Ollama chat response")
    content))

(defn- tool-round-cap-reply
  "Log that the loop hit its tool-round cap and return the text to relay."
  [session-id max-rounds]
  (tel/log! {:level :warn
             :id    ::tool-round-cap
             :data  {:sessionId session-id
                     :maxRounds max-rounds}}
            "Stopped after tool round cap without a final reply")
  (str "Stopped after " max-rounds
       " tool rounds without a final reply."))

(defn run-agent-loop
  "Drive the Ollama chat conversation for `session-id` to completion.

  Starts from the current session history, sends it to `/api/chat` with the
  agent's tool definitions, and loops: while the reply contains `tool_calls`,
  execute them against `:tool-registry`, append the results, and chat again.
  When the model replies with a plain message, record and return its content.

  `opts` keys:

    :client         the `clai.providers.ollama` client to chat with.
    :model          the model name sent to `/api/chat`.
    :tool-registry  this agent's `{id tool}` registry, used to execute calls.
    :tool-specs     optional vector of Ollama tool definitions to offer;
                    defaults to `(clai.tools/specs tool-registry)`.
    :chat-opts      extra Ollama request keys (e.g. `:options`, `:think`,
                    `:format`) merged into each `/api/chat` request.
    :max-rounds     cap on consecutive tool-call rounds per prompt, so a model
                    that keeps calling tools can't loop forever.

  On error (HTTP failure or hitting the round cap) returns a descriptive
  string rather than throwing, so the caller can relay it to the client."
  [conn session-id {:keys [tool-registry tool-specs max-rounds] :as opts}]
  (let [opts (assoc opts :tool-specs (or tool-specs (tools/specs tool-registry)))]
    (loop [round 0]
      (if (>= round max-rounds)
        (tool-round-cap-reply session-id max-rounds)
        (let [history                 (state/session-history session-id)
              {:keys [message error]} (chat-once session-id opts history round)]
          (if error
            error
            (do
              (send-thought conn session-id message)
              (if (ollama/has-tool-calls? message)
                (do (run-tool-round conn session-id message opts)
                    (recur (inc round)))
                (finish-reply session-id message round)))))))))
