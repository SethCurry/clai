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
            [clai.providers.ollama.acp :as ollama-acp]
            [clai.log]
            [clai.state :as state]
            [clai.tools :as tools]
            [clojure.string :as str]
            [malli.instrument :as mi]
            [taoensso.telemere :as tel])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; session/load
;; ---------------------------------------------------------------------------

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
        (ollama-acp/replay-session-history conn sessionId history)
        {})
      {})))

;; ---------------------------------------------------------------------------
;; Agent tools
;; ---------------------------------------------------------------------------

(defn- resolve-tool-registry
  "Build this agent's private tool registry from `opts` and `cfg`.

  `opts` may carry a tool registry or collection of tool maps as
  `:tool-registry` (or the `:tools` alias). A collection of ids is resolved
  against the built-in tools, which is also the default when neither option is
  supplied. When `cfg` contains a `:tools` vector, the registry is narrowed to
  those ids."
  [cfg opts]
  (let [provided (or (:tool-registry opts) (:tools opts))
        base     (cond
                   (nil? provided)
                   (tools/built-in-registry)

                   (and (sequential? provided)
                        (not-every? map? provided))
                   (tools/select-tools (tools/built-in-registry) provided)

                   :else
                   (tools/make-registry provided))]
    (if (contains? cfg :tools)
      (tools/select-tools base (:tools cfg))
      base)))

;; ---------------------------------------------------------------------------
;; Agent handler
;; ---------------------------------------------------------------------------

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
                    vector further selects ids."
  ([] (ollama-agent nil))
  ([opts]
   (let [cfg           (:cfg opts (config/load-config))
         client        (:client opts (config/make-ollama-client cfg))
         model         (:model opts (config/resolve-model cfg))
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
         (let [session (state/create-session {:cwd cwd :model model})]
           (when sys-prompt
             (state/append-history (:sessionId session)
                             {:role "system" :content sys-prompt}))
           {:sessionId (:sessionId session)}))

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
         (state/delete-session sessionId)
         (tel/log! {:level :debug
                    :id    ::session-dropped
                    :data  {:sessionId sessionId}}
                   "Dropped session")
         {})

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
           (state/register-session sessionId cwd
                                   (if sys-prompt
                                     [{:role "system" :content sys-prompt}]
                                     [])))
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
           (if-let [session (state/load-session sessionId)]
             ;; Append the incoming prompt, then drive the Ollama conversation
             ;; to completion -- executing any tool calls the model makes along
             ;; the way -- and relay the final reply. Passing `:cwd` lets the
             ;; prompt converter read local resource links relative to it.
             (let [incoming (ollama-acp/acp-prompt->ollama-messages
                              prompt {:cwd (:cwd session)})
                   _        (run! #(state/append-history sessionId %) incoming)
                   _        (tel/log! {:level :info
                                      :id    ::prompt-received
                                      :data  {:sessionId sessionId
                                              :messages  (count incoming)
                                              :chars     (reduce + 0
                                                                 (map #(count (str (:content %)))
                                                                      incoming))}}
                                     "Received prompt")
                   reply    (agent-loop/run-agent-loop
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
                                  :messageId msg-id
                                  :chars     (count (str reply))}}
                         "Prompt complete")
               (acp/send-agent-message-chunk conn sessionId (str reply))
               {:stopReason "end_turn"})
             ;; Unknown session: tell the client and bail.
             (do
               (tel/log! {:level :warn
                          :id    ::unknown-session-prompt
                          :data  {:sessionId sessionId}}
                         "Received prompt for unknown session")
               (acp/send-agent-message-chunk conn sessionId
                                  (str "No session " sessionId))
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
