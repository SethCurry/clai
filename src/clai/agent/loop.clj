(ns clai.agent.loop
  "The tool-calling loop behind one `session/prompt`.

  Sends the session history to Ollama's `/api/chat`, executes any tool calls
  the model requests through the agent's tool registry, records the results in
  the session history, and repeats until the model produces a final text reply
  (or the configured round cap is reached). Tool activity is streamed to the
  ACP client as `tool_call` / `tool_call_update` session updates."
  (:require [acp.agent :as acp]
            [clai.log]
            [clai.providers.ollama :as ollama]
            [clai.state :as state]
            [clai.tools :as tools]
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
  {:cwd           (-> (state/load-session session-id) :cwd)
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
       :name (ollama/tool-call-name call)
       :title          (ollama/tool-call-title call)
       :rawInput (tool-call-raw-input call)
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
  "Emit the finished state of `call` as a `tool_call_update` carrying the
  result and a terminal `:status` (`completed`/`failed`).

  Some ACP clients only render a tool's output from a terminal-status update,
  so the result is attached to the same update that closes the call. See
  [[tool-result-content]] for why the output lives in `:content`."
  [conn session-id call id result]
  (let [failed? (str/starts-with? result "Error:")
        status  (if failed? "failed" "completed")
        content (tool-result-content result)]
    (acp/send-session-update conn session-id
      {:_meta        nil
       :sessionUpdate "tool_call_update"
       :content       content
       :toolCallId    id})
    (acp/send-session-update conn session-id
      {:sessionUpdate "tool_call_update"
       :toolCallId id
       :status status})))

;; ---------------------------------------------------------------------------
;; Tool rounds
;; ---------------------------------------------------------------------------

(defn- run-tool-round
  "Execute the tool calls in `msg` against `tool-registry`, append the assistant
  message and each `tool`-role result to the session history, and return the
  updated history.

  Emits a `tool_call` SessionUpdate as each call starts, then a finished
  `tool_call_update` when it completes, so the client can render structured
  tool activity -- and its output -- instead of plain agent messages. Does
  not call Ollama; the caller chats again with the new history."
  [conn session-id msg {:keys [tool-registry] :as opts}]
  (let [tool-calls (:tool_calls msg)]
    ;; Record the assistant message (with its tool_calls) verbatim so the
    ;; model sees its own request in the next round.
    (state/append-history session-id msg)
    (let [ctx (session-tool-ctx session-id opts)]
      (doseq [call tool-calls]
        (let [tool-name (or (ollama/tool-call-name call) "?")
              call-id   (send-tool-call-started conn session-id call)]
          (tel/log! {:level :info
                     :id    ::tool-call
                     :data  {:sessionId session-id
                             :tool      tool-name}}
                    "Executing tool call")
          (let [[id args] (tools/tool-call->id+args call)
                result    (tools/invoke tool-registry (or id tool-name) args ctx)]
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

;; ---------------------------------------------------------------------------
;; Agent loop
;; ---------------------------------------------------------------------------

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
  [conn session-id {:keys [client model tool-registry tool-specs chat-opts
                           max-rounds] :as opts}]
  (let [tool-specs (or tool-specs (tools/specs tool-registry))]
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
                (do (run-tool-round conn session-id msg opts)
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
                  content)))))))))
