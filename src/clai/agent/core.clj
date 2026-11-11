(ns clai.agent.core
  "The in-process `clai` agent: its configuration, sessions, and chat loop.

  `ollama-agent` builds a plain configuration map that answers prompts with a
  `clai.agent.providers.ollama` chat model, persists sessions in PostgreSQL via
  `clai.daemon.postgres.sessions`, and executes model tool calls through its own
  per-agent tool registry from `clai.agent.tools.registry`.

  A prompt runs through [[run-prompt!]], which appends it to the session and
  drives [[run-agent-loop]]: the session history is sent to Ollama's
  `/api/chat`, any tool calls the model requests are executed against the
  agent's registry and recorded, and the loop repeats until the model produces
  a final text reply (or the configured tool-round cap is reached). Tool
  activity and model reasoning stream to the caller as event-shaped maps from
  `clai.events.schema`.

  Supporting pieces live in sibling namespaces under `clai.agent.*`:

    * `clai.agent.providers.ollama`        -- the Ollama HTTP client.
    * `clai.agent.providers.ollama.prompt` -- prompt/history conversion.
    * `clai.agent.resources`               -- resource blocks rendered as text
                                              for the model.
    * `clai.daemon.postgres.sessions`      -- PostgreSQL-backed session storage.
    * `clai.agent.tools.registry`          -- immutable tool registries.
    * `clai.agent.tools.builtin`           -- the default built-in tools.
    * `clai.agent.tools.invoke`            -- running one Ollama tool call.
    * `clai.config`                        -- config/env resolution for the
                                              agent's model, client, tools, and
                                              chat options."
  (:require [clai.agent.providers.ollama :as ollama]
            [clai.agent.providers.ollama.prompt :as ollama-prompt]
            [clai.agent.tools.builtin :as tools-builtin]
            [clai.agent.tools.invoke :as tool-invoke]
            [clai.agent.tools.registry :as tools-registry]
            [clai.config :as config]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.events.schema :as events]
            [clai.log]
            [clai.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Project instructions
;; ---------------------------------------------------------------------------

(def ^:private agents-md-filename
  "The project-instructions file read from a session's working directory."
  "AGENTS.md")

(defn- read-agents-md
  "Read `<cwd>/AGENTS.md`, returning its contents as a string.

  Returns nil when `cwd` is blank, the file is absent, unreadable, or blank,
  so a missing project-instructions file is simply a no-op. Read failures are
  logged and treated like a missing file rather than failing session setup."
  [cwd]
  (when-not (str/blank? cwd)
    (let [file (io/file (str cwd) agents-md-filename)]
      (try
        (when (and (.isFile file) (.canRead file))
          (let [contents (slurp file :encoding "UTF-8")]
            (when-not (str/blank? contents)
              contents)))
        (catch Throwable e
          (tel/log! {:level :warn
                     :id    ::agents-md-read-failed
                     :data  {:path  (str file)
                             :error (util/error-message e)}}
                    "Failed to read AGENTS.md; continuing without it")
          nil)))))

(defn- system-prompt-for
  "Build a session's system prompt from the configured `sys-prompt` and the
  `AGENTS.md` in `cwd`.

  Project instructions are appended to the configured system prompt (with a
  blank line between them); when no system prompt is configured, they become
  the system prompt. Returns nil when neither is available."
  [sys-prompt cwd]
  (let [agents-md (read-agents-md cwd)]
    (when (or (some? sys-prompt) (some? agents-md))
      (str/join "\n\n" (remove nil? [sys-prompt agents-md])))))

;; ---------------------------------------------------------------------------
;; Session helpers
;; ---------------------------------------------------------------------------

(defn- sync-session-cwd
  "Return `session` with `:cwd` replaced by `cwd` -- persisting the change --
  when the caller supplied a different working directory."
  [postgres session cwd]
  (if (and (some? cwd) (not= (str cwd) (:cwd session)))
    (sessions/save-session postgres (assoc session :cwd (str cwd)))
    session))

(defn- system-history
  "The opening history for a session: a system message built from the
  configured `sys-prompt` plus any `AGENTS.md` in `cwd`, or nothing when
  neither is present."
  [sys-prompt cwd]
  (if-let [prompt (system-prompt-for sys-prompt cwd)]
    [{:role "system" :content prompt}]
    []))

(defn- session-summary
  "Shape one session-store summary for the session picker.

  Sessions carry `createdAt` rather than an update timestamp, so that is what
  the picker sorts and displays."
  [{:keys [sessionId cwd createdAt]}]
  (cond-> {:sessionId sessionId
           :cwd       (or cwd "")}
    (some? createdAt) (assoc :createdAt createdAt)))

;; ---------------------------------------------------------------------------
;; Agent tools
;; ---------------------------------------------------------------------------

(defn- base-registry
  "Build the tool registry described by `opts`, before config-driven
  narrowing.

  `:tool-registry` (or its `:tools` alias) may be a registry map, a
  collection of tool maps, or a collection of built-in ids. Absent, the
  built-ins are used."
  [opts]
  (if-some [provided (or (:tool-registry opts) (:tools opts))]
    (if (and (sequential? provided) (not-every? map? provided))
      (tools-registry/select-tools (tools-builtin/built-in-registry) provided)
      (tools-registry/make-registry provided))
    (tools-builtin/built-in-registry)))

(defn- resolve-tool-registry
  "Build this agent's private tool registry from `opts` and `cfg`.

  When `cfg` contains a `:tools` vector, the registry is narrowed to those
  ids."
  [cfg opts]
  (cond-> (base-registry opts)
    (contains? cfg :tools) (tools-registry/select-tools (:tools cfg))))

;; ---------------------------------------------------------------------------
;; Session operations
;; ---------------------------------------------------------------------------

(defn new-session!
  "Create a session for `{:keys [cwd]}` and record its system prompt
  (configured prompt plus any `AGENTS.md`). Returns `{:sessionId id}`."
  {:malli/schema [:-> :map [:map [:cwd {:optional true} [:maybe :string]]] :map]}
  [agent {:keys [cwd]}]
  (let [{:keys [postgres sys-prompt]} agent
        session (sessions/create-session postgres {:cwd cwd})]
    (run! #(sessions/append-history postgres (:sessionId session) %)
          (system-history sys-prompt (:cwd session)))
    {:sessionId (:sessionId session)}))

(defn load-session!
  "Load and optionally re-root a persisted session.

  `opts` is `{:keys [sessionId cwd]}`. When `cwd` differs from the stored
  working directory it is persisted. Returns the full session map, or nil when
  `sessionId` names no recorded session."
  {:malli/schema [:-> :map [:map [:sessionId {:optional true} [:maybe :string]]
                            [:cwd {:optional true} [:maybe :string]]]
                  [:maybe :map]]}
  [agent {:keys [sessionId cwd]}]
  (let [{:keys [postgres]} agent]
    (when-not (str/blank? (str sessionId))
      (when-let [session (sessions/load-session postgres sessionId)]
        (let [session (sync-session-cwd postgres session cwd)]
          (tel/log! {:level :info
                     :id    ::session-loaded
                     :data  {:sessionId sessionId
                             :cwd       (:cwd session)
                             :messages  (count (:history session))}}
                    "Loaded session")
          session)))))

(defn list-sessions!
  "List recorded sessions, optionally filtered to `opts`'s exact `:cwd`.
  Returns `{:sessions [summary ...]}`."
  {:malli/schema [:-> :map [:map [:cwd {:optional true} [:maybe :string]]] :map]}
  [agent {:keys [cwd]}]
  (let [postgres  (:postgres agent)
        summaries (cond->> (sessions/list-session-summaries postgres)
                    (some? cwd) (filter #(= cwd (:cwd %))))]
    (tel/log! {:level :debug
               :id    ::session-list
               :data  {:count (count summaries)}}
              "Listed sessions")
    {:sessions (mapv session-summary summaries)}))

(defn delete-session!
  "Delete the session named by `opts`'s `:sessionId`."
  {:malli/schema [:-> :map [:map [:sessionId :string]] :map]}
  [agent {:keys [sessionId]}]
  (sessions/delete-session (:postgres agent) sessionId)
  (tel/log! {:level :debug
             :id    ::session-dropped
             :data  {:sessionId sessionId}}
            "Dropped session")
  {})

(defn resume-session!
  "Resume the session named by `opts`.

  Session ids are assigned by the database, so a requested id that is not
  recorded cannot be reused: an unknown session degrades to a new session
  (with the configured system prompt plus any `AGENTS.md` in `cwd`) and the
  reply carries the new id."
  {:malli/schema [:-> :map [:map [:sessionId {:optional true} [:maybe :string]]
                            [:cwd {:optional true} [:maybe :string]]] :map]}
  [agent {:keys [cwd] :as opts}]
  (if-let [session (load-session! agent opts)]
    (do
      (tel/log! {:level :debug
                 :id    ::session-resumed
                 :data  {:sessionId (:sessionId session)}}
                "Resumed existing session")
      {:sessionId (:sessionId session)})
    {:sessionId (:sessionId (new-session! agent {:cwd cwd}))}))

;; ---------------------------------------------------------------------------
;; Streaming updates
;; ---------------------------------------------------------------------------

(defn- emit!
  "Send `update` to the caller's `:on-update` callback when one is present."
  [on-update update]
  (when on-update
    (on-update update))
  update)

(defn- send-tool-call-started
  "Emit a running `tool_call` event announcing that `call` has started with
  parsed `args`. Returns the id used for the event."
  [on-update call args]
  (let [id (ollama/tool-call-id call)]
    (emit! on-update
           {:type       "tool_call"
            :id         id
            :start_time (Instant/now)
            :input      args
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
        [id args] (tool-invoke/tool-call->id+args call)
        tool-id   (or id tool-name)
        call-id   (send-tool-call-started on-update call args)]
    (tel/log! {:level :info
               :id    ::tool-call
               :data  {:sessionId session-id
                       :tool      tool-name}}
              "Executing tool call")
    (let [result (tool-invoke/invoke (:tool-registry ctx)
                                     tool-id
                                     args
                                     (call-tool-ctx on-update call-id ctx))]
      (send-tool-call-finished on-update call-id result)
      (tool-invoke/tool-result-message call tool-id result))))

(defn- run-tool-round
  "Execute the tool calls in `msg` against the loop's tool registry, append the
  assistant message and each `tool`-role result to the session history, and
  return the updated history.

  Emits a running `tool_call` event as each call starts, incremental output
  events while it runs, and a terminal event when it completes. Does not call
  Ollama; the caller chats again with the new history."
  [session-id msg {:keys [postgres on-update] :as opts}]
  (let [tool-calls (:tool_calls msg)
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

(defn- run-agent-loop
  "Drive the Ollama chat conversation for `session-id` to completion.

  Starts from the current session history, sends it to `/api/chat` with the
  agent's tool definitions, and loops: while the reply contains `tool_calls`,
  execute them against `:tool-registry`, append the results, and chat again.
  When the model replies with a plain message, record and return its content.

  `opts` is the agent map from [[ollama-agent]] (`:postgres`, `:client`,
  `:model`, `:tool-registry`, `:tool-specs`, `:chat-opts`, `:max-rounds`)
  plus the caller's `:on-update` callback.

  On error (HTTP failure or hitting the round cap) returns a descriptive
  string rather than throwing, so the caller can relay it to the user."
  [session-id {:keys [postgres tool-registry tool-specs max-rounds on-update]
               :as   opts}]
  (let [opts (assoc opts :tool-specs (or tool-specs
                                        (tools-registry/specs tool-registry)))]
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
                (do (run-tool-round session-id message opts)
                    (recur (inc round)))
                (finish-reply postgres session-id message round)))))))))

;; ---------------------------------------------------------------------------
;; Prompt execution
;; ---------------------------------------------------------------------------

(defn- prompt-char-count
  "Total number of characters in the `:content` of `messages`."
  [messages]
  (transduce (map (comp count str :content)) + 0 messages))

(defn- send-reply!
  "Relay `text` as the prompt's final agent message and return the caller's
  stop reason."
  [on-update text]
  (emit! on-update (events/text-event "agent_message" (str text)))
  {:stopReason "end_turn"})

(defn run-prompt!
  "Append `opts`'s `:prompt` to a session, drive the Ollama conversation to
  completion -- executing any tool calls the model makes along the way -- and
  relay the final reply through `:on-update`.

  `opts` keys:

    :sessionId  the session to prompt.
    :prompt     a string or content blocks (see
                `clai.agent.providers.ollama.prompt/prompt->messages`).
    :on-update  optional callback receiving event-shaped updates while the
                prompt runs, including the final `agent_message`.

  Returns `{:stopReason \"end_turn\"}` so callers that mirror a chat protocol
  can use the result directly. An unknown session yields an explanatory
  agent message instead of throwing."
  {:malli/schema [:-> :map [:map
                            [:sessionId {:optional true} [:maybe :string]]
                            [:prompt {:optional true} :any]
                            [:on-update {:optional true} [:maybe fn?]]]
                  :map]}
  [agent {:keys [sessionId prompt on-update]}]
  (let [postgres (:postgres agent)]
    (if-let [session (sessions/load-session postgres sessionId)]
      (let [incoming (ollama-prompt/prompt->messages prompt {:cwd (:cwd session)})]
        (run! #(sessions/append-history postgres sessionId %) incoming)
        (tel/log! {:level :info
                   :id    ::prompt-received
                   :data  {:sessionId sessionId
                           :messages  (count incoming)
                           :chars     (prompt-char-count incoming)}}
                  "Received prompt")
        (let [reply (run-agent-loop sessionId
                                    (assoc agent :on-update on-update))]
          (tel/log! {:level :info
                     :id    ::prompt-complete
                     :data  {:sessionId sessionId
                             :chars     (count (str reply))}}
                    "Prompt complete")
          (send-reply! on-update reply)))
      ;; Unknown session: tell the caller and bail.
      (do
        (tel/log! {:level :warn
                   :id    ::unknown-session-prompt
                   :data  {:sessionId sessionId}}
                  "Received prompt for unknown session")
        (send-reply! on-update (str "No session " sessionId))))))

;; ---------------------------------------------------------------------------
;; Agent configuration
;; ---------------------------------------------------------------------------

(defn- build-agent
  "Resolve `opts` and the loaded config into the agent's configuration map.

  The map holds everything the prompt loop needs: the clients, model, tool
  registry and its specs, the tool-round cap, the system prompt, and extra
  chat options. It is plain data, so the operations above can be written as
  ordinary functions of it."
  [opts]
  (let [cfg           (or (:cfg opts) (config/load-config))
        client        (or (:client opts) (config/make-ollama-client cfg))
        postgres      (or (:postgres opts)
                          (config/make-postgres-client cfg))
        model         (or (:model opts) (config/resolve-model cfg))
        tool-registry (resolve-tool-registry cfg opts)
        tool-specs    (tools-registry/specs tool-registry)
        max-rounds    (config/resolve-max-tool-rounds cfg)
        sys-prompt    (:system-prompt cfg)
        chat-opts     (config/chat-options cfg)]
    (tel/log! {:level :info
               :id    ::agent-configured
               :data  {:model          model
                       :tools          (count tool-specs)
                       :max-rounds     max-rounds
                       :system-prompt? (some? sys-prompt)
                       :host           (:host client)}}
              "Configured clai agent")
    {:cfg           cfg
     :client        client
     :postgres      postgres
     :model         model
     :tool-registry tool-registry
     :tool-specs    tool-specs
     :max-rounds    max-rounds
     :sys-prompt    sys-prompt
     :chat-opts     chat-opts}))

(defn ollama-agent
  "Build an in-process agent that answers prompts using an Ollama chat model.

  Options (all optional; defaults come from `cfg` then the environment):

    :cfg            the loaded config map (`clai.config/load-config`).
                    Defaults to `(config/load-config)`.
    :client         the `clai.agent.providers.ollama.Client` to use. Defaults to
                    one built from `cfg` and the environment (`OLLAMA_HOST`,
                    `OLLAMA_API_KEY`).
    :model          the model name to send to `/api/chat`. Defaults to `cfg`'s
                    `:model`, then `CLAI_MODEL`, then `gemma3`.
    :tool-registry  the agent's tool registry (`{id tool}`), or a collection of
                    tool maps. A collection of built-in ids (e.g.
                    `[\"read_file\"]`) selects from the built-ins. Defaults to a
                    fresh built-in registry, so each agent owns its tools. The
                    `:tools` key is accepted as an alias. `cfg`'s `:tools`
                    vector further selects ids.
    :postgres       the `clai.daemon.postgres/Client` used for session storage.
                    Defaults to one built from `cfg`'s `:postgres` sub-map and
                    the environment. The storage schema is created lazily on
                    the first session operation (`clai.daemon.postgres.sessions`
                    ensures it).

  A new session's system prompt is the configured `:system-prompt` followed by
  the contents of an `AGENTS.md` in the session's `cwd`, if one exists."
  ([] (ollama-agent nil))
  ([opts]
   (build-agent opts)))
