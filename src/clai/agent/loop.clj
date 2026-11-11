(ns clai.agent.loop
  "The tool-calling loop behind one prompt.

  Sends the session history to Ollama's `/api/chat`, executes any tool calls
  the model requests through the agent's tool registry, records the results in
  the session history, and repeats until the model produces a final text reply
  (or the configured round cap is reached). Tool activity and model reasoning
  are streamed to the caller through an `:on-update` callback as event-shaped
  maps from `clai.events.schema`."
  (:require [clai.agent.providers.ollama :as ollama]
            [clai.agent.tools.invoke :as tool-invoke]
            [clai.agent.tools.registry :as tool-registry]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.events.schema :as events]
            [clai.log]
            [clai.util :as util]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Session context
;; ---------------------------------------------------------------------------

(defn- session-tool-ctx
  "Build the context map handed to tool functions for `session-id`. Besides
  the `:cwd`, it carries the loop's live client, model, registry, and limits so
  tools like `spawn_agent` can run their own conversations."
  [session-id {:keys [postgres client model tool-registry chat-opts max-rounds
                      agent-depth]}]
  {:cwd           (:cwd (sessions/load-session postgres session-id))
   :session-id    session-id
   :client        client
   :model         model
   :tool-registry tool-registry
   :chat-opts     chat-opts
   :max-rounds    max-rounds
   :agent-depth   (long (or agent-depth 0))})

;; ---------------------------------------------------------------------------
;; Event updates
;; ---------------------------------------------------------------------------

(defn- emit!
  "Send `update` to the caller's `:on-update` callback when one is present."
  [on-update update]
  (when on-update
    (on-update update))
  update)

(defn- tool-call-raw-input
  "Return the parsed arguments of an Ollama tool call as a map."
  [call]
  (second (tool-invoke/tool-call->id+args call)))

(defn- send-tool-call-started
  "Emit a running `tool_call` event announcing that `call` has started.
  Returns the id used for the event."
  [on-update call]
  (let [id (ollama/tool-call-id call)]
    (emit! on-update
           {:type       "tool_call"
            :id         id
            :start_time (Instant/now)
            :input      (tool-call-raw-input call)
            :name       (ollama/tool-call-name call)
            :title      (ollama/tool-call-title call)
            :status     "running"})
    id))

(defn- send-tool-call-output
  "Emit a running `tool_call` event carrying the output produced so far."
  [on-update id output]
  (emit! on-update {:type "tool_call" :id id :output (str output)}))

(defn- send-tool-call-finished
  "Emit the terminal `tool_call` event carrying the full result and a status
  of `completed` or `failed`."
  [on-update id result]
  (emit! on-update
         {:type   "tool_call"
          :id     id
          :output (str result)
          :status (if (str/starts-with? (str result) "Error:")
                    "failed"
                    "completed")}))

(defn- send-thought
  "Relay the model's reasoning text in `msg` as an `agent_thought` event.
  Messages without non-blank thinking are ignored."
  [on-update msg]
  (let [thinking (:thinking msg)]
    (when-not (str/blank? (str thinking))
      (emit! on-update
             (events/text-event "agent_thought" (str thinking))))))

;; ---------------------------------------------------------------------------
;; Tool rounds
;; ---------------------------------------------------------------------------

(defn- call-tool-ctx
  "Build the context for one tool call: the session context plus an
  `:on-output` callback that relays a tool's incremental output as running
  `tool_call` events.

  Tools that ignore `:on-output` -- every built-in but `run_command` -- are
  unaffected. The callback is omitted when there is nowhere to stream."
  [on-update call-id ctx]
  (if (and on-update call-id)
    (assoc ctx :on-output
           (fn [output]
             (send-tool-call-output on-update call-id output)))
    ctx))

(defn- execute-tool-call
  "Run one Ollama tool call, streaming its activity to the caller, and return
  the `tool`-role history message recording its result."
  [on-update session-id ctx call]
  (let [tool-name (or (ollama/tool-call-name call) "?")
        call-id   (send-tool-call-started on-update call)
        [id args] (tool-invoke/tool-call->id+args call)]
    (tel/log! {:level :info
               :id    ::tool-call
               :data  {:sessionId session-id
                       :tool      tool-name}}
              "Executing tool call")
    (let [result (tool-invoke/invoke (:tool-registry ctx)
                                     (or id tool-name)
                                     args
                                     (call-tool-ctx on-update call-id ctx))]
      (send-tool-call-finished on-update call-id result)
      (tool-invoke/tool-result-message call (or id tool-name) result))))

(defn- run-tool-round
  "Execute the tool calls in `msg` against the loop's tool registry, append the
  assistant message and each `tool`-role result to the session history, and
  return the updated history.

  Emits a running `tool_call` event as each call starts, incremental output
  events while it runs, and a terminal event when it completes. Does not call
  Ollama; the caller chats again with the new history."
  [on-update session-id msg opts]
  (let [tool-calls (:tool_calls msg)
        postgres   (:postgres opts)
        ctx        (session-tool-ctx session-id opts)]
    ;; Record the assistant message (with its tool_calls) verbatim so the
    ;; model sees its own request in the next round.
    (sessions/append-history postgres session-id msg)
    (doseq [call tool-calls]
      (sessions/append-history postgres session-id
                               (execute-tool-call on-update session-id ctx call)))
    (tel/log! {:level :debug
               :id    ::tool-round-complete
               :data  {:sessionId session-id
                       :results   (count tool-calls)}}
              "Tool round complete")
    (sessions/session-history postgres session-id)))

;; ---------------------------------------------------------------------------
;; Agent loop
;; ---------------------------------------------------------------------------

(defn- chat-once
  "Send one non-streaming `/api/chat` request for `history`.

  Returns `{:message <assistant message>}` on success or `{:error <text>}` on
  failure, so the loop can relay the failure to the caller instead of
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
  [postgres session-id msg round]
  (let [content  (or (:content msg) "")
        thinking (:thinking msg)]
    (sessions/append-history postgres session-id
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

    :postgres       the `clai.daemon.postgres/Client` holding session state.
    :client         the `clai.agent.providers.ollama` client to chat with.
    :model          the model name sent to `/api/chat`.
    :tool-registry  this agent's `{id tool}` registry, used to execute calls.
    :tool-specs     optional vector of Ollama tool definitions to offer;
                    defaults to `(clai.agent.tools.registry/specs tool-registry)`.
    :chat-opts      extra Ollama request keys (e.g. `:options`, `:think`,
                    `:format`) merged into each `/api/chat` request.
    :max-rounds     cap on consecutive tool-call rounds per prompt, so a model
                    that keeps calling tools can't loop forever.
    :on-update      optional callback receiving event-shaped thought, message,
                    and tool-call updates while the loop runs.

  On error (HTTP failure or hitting the round cap) returns a descriptive
  string rather than throwing, so the caller can relay it to the user."
  [session-id {:keys [postgres tool-registry tool-specs max-rounds on-update]
               :as opts}]
  (let [opts (assoc opts :tool-specs (or tool-specs
                                        (tool-registry/specs tool-registry)))]
    (loop [round 0]
      (if (>= round max-rounds)
        (tool-round-cap-reply session-id max-rounds)
        (let [history                 (sessions/session-history postgres session-id)
              {:keys [message error]} (chat-once session-id opts history round)]
          (if error
            error
            (do
              (send-thought on-update message)
              (if (ollama/has-tool-calls? message)
                (do (run-tool-round on-update session-id message opts)
                    (recur (inc round)))
                (finish-reply postgres session-id message round)))))))))
