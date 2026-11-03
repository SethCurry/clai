(ns clai.agent
  "The `clai` ACP agent handler.

  `ollama-agent` builds an `acp/AgentHandler` that answers `session/prompt`
  requests with a `clai.providers.ollama` chat model, persists sessions via
  `clai.state`, and executes model tool calls via its own per-agent tool
  registry from `clai.tools`.

  Supporting pieces live in sibling namespaces:

    * `clai.providers.ollama.acp` -- ACP <-> Ollama message conversion and
                                     history replay.
    * `clai.agent.loop`     -- the tool-calling chat loop.
    * `clai.config`         -- config/env resolution for the agent's model,
                               client, tools, and chat options."
  (:require [acp.agent :as acp]
            [acp.errors :as acp-errors]
            [clai.agent.loop :as agent-loop]
            [clai.config :as config]
            [clai.log]
            [clai.providers.ollama.acp :as ollama-acp]
            [clai.state :as state]
            [clai.tools :as tools]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Protocol negotiation
;; ---------------------------------------------------------------------------

(def ^:private agent-info
  "Implementation info advertised in the `initialize` response."
  {:name    "clai"
   :title   "clai agent"
   :version "0.1.0-SNAPSHOT"})

(def ^:private agent-capabilities
  "Capabilities advertised in the `initialize` response."
  {:loadSession         true
   :sessionCapabilities {:list   {}
                         :delete {}
                         :resume {}
                         :close  {}}})

(defn- initialize-response
  "Build the `initialize` response, negotiating the highest ACP version the
  agent supports at or below the client's request."
  [requested-version]
  (let [negotiated (min acp/protocol-version-2
                        (long (or requested-version acp/protocol-version-2)))]
    (tel/log! {:level :debug
               :id    ::initialize
               :data  {:client-protocol requested-version
                       :negotiated      negotiated}}
              "Initialized ACP connection")
    {:protocolVersion   negotiated
     :info              agent-info
     :agentInfo         agent-info
     :capabilities      agent-capabilities
     :agentCapabilities agent-capabilities}))

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
  (when-not (str/blank? (str cwd))
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
                             :error (ex-message e)}}
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
  when the client supplied a different working directory."
  [session cwd]
  (if (and (some? cwd) (not= (str cwd) (:cwd session)))
    (state/save-session (assoc session :cwd (str cwd)))
    session))

(defn- system-history
  "The opening history for a new session: a system message when `prompt` is
  non-nil, otherwise nothing."
  [prompt]
  (if (some? prompt)
    [{:role "system" :content prompt}]
    []))

(defn- session-summary
  "Shape one `state/list-session-summaries` entry as an ACP session summary."
  [{:keys [sessionId cwd updatedAt]}]
  (cond-> {:sessionId sessionId
           :cwd       (or cwd "")}
    (some? updatedAt) (assoc :updatedAt updatedAt)))

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
  (let [provided (or (:tool-registry opts) (:tools opts))]
    (cond
      (nil? provided)
      (tools/built-in-registry)

      (and (sequential? provided) (not-every? map? provided))
      (tools/select-tools (tools/built-in-registry) provided)

      :else
      (tools/make-registry provided))))

(defn- resolve-tool-registry
  "Build this agent's private tool registry from `opts` and `cfg`.

  When `cfg` contains a `:tools` vector, the registry is narrowed to those
  ids."
  [cfg opts]
  (cond-> (base-registry opts)
    (contains? cfg :tools) (tools/select-tools (:tools cfg))))

;; ---------------------------------------------------------------------------
;; Request handlers
;; ---------------------------------------------------------------------------

(defn- load-session!
  "Handle the `session/load` extension request: restore the persisted session,
  replay its conversation history to the client, and return an empty result."
  [conn {:keys [sessionId cwd]}]
  (if (str/blank? (str sessionId))
    (acp-errors/acp-error acp-errors/err-params "Missing sessionId")
    (if-let [session (state/load-session sessionId)]
      (let [session (sync-session-cwd session cwd)
            history (:history session [])]
        (tel/log! {:level :info
                   :id    ::session-loaded
                   :data  {:sessionId sessionId
                           :cwd       (:cwd session)
                           :messages  (count history)}}
                  "Loaded session")
        (ollama-acp/replay-session-history conn sessionId history)
        {})
      {})))

(defn- new-session!
  "Handle `session/new`: create a session with the configured model and
  record its system prompt (configured prompt plus any `AGENTS.md`)."
  [sys-prompt model {:keys [cwd]}]
  (let [session (state/create-session {:cwd cwd :model model})]
    (run! #(state/append-history (:sessionId session) %)
          (system-history (system-prompt-for sys-prompt (:cwd session))))
    {:sessionId (:sessionId session)}))

(defn- list-session-summaries
  "Handle `session/list`, optionally filtered to a working directory."
  [{:keys [cwd]}]
  (let [sessions (cond->> (state/list-session-summaries)
                   (some? cwd) (filter #(= cwd (:cwd %))))]
    (tel/log! {:level :debug
               :id    ::session-list
               :data  {:count (count sessions)}}
              "Listed sessions")
    {:sessions (mapv session-summary sessions)}))

(defn- delete-session!
  "Handle `session/delete`: drop the persisted session."
  [{:keys [sessionId]}]
  (state/delete-session sessionId)
  (tel/log! {:level :debug
             :id    ::session-dropped
             :data  {:sessionId sessionId}}
            "Dropped session")
  {})

(defn- resume-session!
  "Handle `session/resume`.

  An unknown session is registered under the requested id (with the
  configured system prompt plus any `AGENTS.md` in `cwd`) so resume degrades
  to new rather than erroring."
  [sys-prompt {:keys [sessionId cwd]}]
  (if-let [session (state/load-session sessionId)]
    (do
      (sync-session-cwd session cwd)
      (tel/log! {:level :debug
                 :id    ::session-resumed
                 :data  {:sessionId sessionId}}
                "Resumed existing session"))
    (state/register-session sessionId cwd
                            (system-history
                             (system-prompt-for sys-prompt (str (or cwd "."))))))
  {:sessionId sessionId})

(defn- close-session!
  "Handle `session/close`.

  Closing frees the active session but keeps its persisted history so the
  client can load it again later; `session/delete` removes it."
  [{:keys [sessionId]}]
  (tel/log! {:level :debug
             :id    ::session-closed
             :data  {:sessionId sessionId}}
            "Closed session")
  {})

(defn- prompt-char-count
  "Total number of characters in the `:content` of `messages`."
  [messages]
  (transduce (map (comp count str :content)) + 0 messages))

(defn- run-prompt!
  "Handle `session/prompt`.

  Append the incoming prompt, drive the Ollama conversation to completion --
  executing any tool calls the model makes along the way -- and relay the
  final reply. Passing `:cwd` to the prompt converter lets it read local
  resource links relative to the session's working directory."
  [{:keys [client model tool-registry tool-specs chat-opts max-rounds]}
   conn
   {:keys [sessionId prompt]}]
  (let [message-id (str (UUID/randomUUID))]
    (if-let [session (state/load-session sessionId)]
      (let [incoming (ollama-acp/acp-prompt->ollama-messages
                      prompt {:cwd (:cwd session)})]
        (run! #(state/append-history sessionId %) incoming)
        (tel/log! {:level :info
                   :id    ::prompt-received
                   :data  {:sessionId sessionId
                           :messages  (count incoming)
                           :chars     (prompt-char-count incoming)}}
                  "Received prompt")
        (let [reply (agent-loop/run-agent-loop
                     conn sessionId
                     {:client        client
                      :model         model
                      :tool-registry tool-registry
                      :tool-specs    tool-specs
                      :chat-opts     chat-opts
                      :max-rounds    max-rounds})]
          (tel/log! {:level :info
                     :id    ::prompt-complete
                     :data  {:sessionId sessionId
                             :messageId message-id
                             :chars     (count (str reply))}}
                    "Prompt complete")
          (acp/send-agent-message-chunk conn sessionId (str reply))
          {:stopReason "end_turn"}))
      ;; Unknown session: tell the client and bail.
      (do
        (tel/log! {:level :warn
                   :id    ::unknown-session-prompt
                   :data  {:sessionId sessionId}}
                  "Received prompt for unknown session")
        (acp/send-agent-message-chunk conn sessionId (str "No session " sessionId))
        {:stopReason "end_turn"}))))

;; ---------------------------------------------------------------------------
;; Agent handler
;; ---------------------------------------------------------------------------

;; The configured agent handler. Its fields are the per-agent state the
;; request handlers need: the resolved config, the Ollama client, the model,
;; the tool registry and the specs derived from it, the tool-round cap, and
;; the system prompt.
(defrecord ClaiAgent
  [cfg client model tool-registry tool-specs max-rounds sys-prompt chat-opts]
  acp/AgentHandler
  (handle-initialize [_ _conn {:keys [protocolVersion]}]
    (initialize-response protocolVersion))

  (handle-authenticate [_ _conn _params]
    (acp-errors/acp-error acp-errors/err-method))

  (handle-logout [_ _conn _params]
    (acp-errors/acp-error acp-errors/err-method))

  (handle-session-new [_ _conn params]
    (new-session! sys-prompt model params))

  (handle-session-list [_ _conn params]
    (list-session-summaries params))

  (handle-session-delete [_ _conn params]
    (delete-session! params))

  (handle-session-resume [_ _conn params]
    (resume-session! sys-prompt params))

  (handle-session-close [_ _conn params]
    (close-session! params))

  (handle-session-set-config-option [_ _conn _params]
    (acp-errors/acp-error acp-errors/err-method))

  (handle-session-prompt [agent conn params]
    (run-prompt! agent conn params))

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
    (case method
      "session/load" (load-session! conn params)
      (do
        (tel/log! {:level :debug
                   :id    ::extension-request
                   :data  {:method method}}
                  "Rejecting extension request")
        (acp-errors/acp-error acp-errors/err-method)))))

(defn ollama-agent
  "Build an `AgentHandler` that answers prompts using an Ollama chat model.

  Options (all optional; defaults come from `cfg` then the environment):

    :cfg            the loaded config map (`clai.config/load-config`).
                    Defaults to `(config/load-config)`.
    :client         the `clai.providers.ollama.Client` to use. Defaults to one
                    built from `cfg` and the environment (`OLLAMA_HOST`,
                    `OLLAMA_API_KEY`).
    :model          the model name to send to `/api/chat`. Defaults to `cfg`'s
                    `:model`, then `CLAI_MODEL`, then `gemma3`.
    :tool-registry  the agent's tool registry (`{id tool}`), or a collection of
                    tool maps. A collection of built-in ids (e.g.
                    `[\"read_file\"]`) selects from the built-ins. Defaults to a
                    fresh built-in registry, so each agent owns its tools. The
                    `:tools` key is accepted as an alias. `cfg`'s `:tools`
                    vector further selects ids.

  A new session's system prompt is the configured `:system-prompt` followed by
  the contents of an `AGENTS.md` in the session's `cwd`, if one exists."
  ([] (ollama-agent nil))
  ([opts]
   (let [cfg           (or (:cfg opts) (config/load-config))
         client        (or (:client opts) (config/make-ollama-client cfg))
         model         (or (:model opts) (config/resolve-model cfg))
         tool-registry (resolve-tool-registry cfg opts)
         tool-specs    (tools/specs tool-registry)
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
     (map->ClaiAgent {:cfg           cfg
                      :client        client
                      :model         model
                      :tool-registry tool-registry
                      :tool-specs    tool-specs
                      :max-rounds    max-rounds
                      :sys-prompt    sys-prompt
                      :chat-opts     chat-opts}))))
