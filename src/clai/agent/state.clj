(ns clai.agent.state
  "Persistent session storage for `clai`, backed by PostgreSQL.

  Sessions live in the two tables managed by
  `clai.daemon.postgres.sessions`:

      clai_sessions          session metadata and `:params`
      clai_session_messages  append-only message history, one JSONB per row

  This namespace is the session-storage facade used by the agent. It
  re-exports the storage schemas and delegates every operation to
  `clai.daemon.postgres.sessions` with the caller's client, so each function
  takes a `clai.daemon.postgres/Client` as its first argument.

  A client is built once per agent (see `clai.agent/ollama-agent`) from the
  `:postgres` config and passed through to these functions. The tables are
  created lazily on the first session operation; call [[open]] or
  [[ensure-schema!]] to create them eagerly. Because the store is a database,
  mutations are transactional and a session is visible to every process
  sharing the database; the old per-session file locks are gone.

  The `history` vector holds the same `{role content}` message maps that the
  Ollama chat API expects. `:params` is a JSON object, so values stored there
  must be JSON-serializable."
  (:require [clai.daemon.postgres :as postgres]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.util :as util]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  util/non-empty-string)

(def client-schema
  "A `clai.daemon.postgres/Client` used to reach the session tables."
  postgres/client-schema)

(def message
  "A chat message as stored in a session's `:history`, shaped like an Ollama
  chat message."
  sessions/message)

(def session
  "A session state map, as returned by `create-session`, `load-session`, and
  `list-sessions`, and stored in `clai_sessions` plus
  `clai_session_messages`."
  sessions/session)

(def session-summary
  "A session state map without `:history`, as returned by
  `list-session-summaries`."
  sessions/session-summary)

(def create-session-opts
  "Options accepted by `create-session`."
  sessions/create-session-opts)

;; ---------------------------------------------------------------------------
;; Client
;; ---------------------------------------------------------------------------

(defonce ^:private schema-ready
  ;; Clients whose storage tables have already been ensured. Keeping this here
  ;; lets an agent be constructed without touching the database; the schema is
  ;; created on the first session operation.
  (atom #{}))

(defn- ready
  "Ensure the storage tables exist for `client` (once per client) and return
  it."
  [client]
  (when-not (contains? @schema-ready client)
    (locking schema-ready
      (when-not (contains? @schema-ready client)
        (postgres/ensure-schema! client)
        (swap! schema-ready conj client))))
  client)

(defn open
  "Open a PostgreSQL-backed session store and ensure the storage tables exist.
  Options are forwarded to `clai.daemon.postgres/open` (see that namespace for
  the accepted connection keys and their environment fallbacks).

  The returned client is passed as the first argument to every storage
  function in this namespace. Callers that want lazy connection should build
  one with `clai.daemon.postgres/make-client` (or
  `clai.config/make-postgres-client`) instead; the tables are then ensured on
  the first storage operation."
  {:malli/schema [:function
                  [:=> [:cat] #'client-schema]
                  [:=> [:cat [:maybe #'postgres/client-options]] #'client-schema]]}
  ([] (open nil))
  ([opts]
   (let [client (postgres/open opts)]
     (swap! schema-ready conj client)
     client)))

(defn ensure-schema!
  "Create the session tables if they do not exist. Idempotent; the storage
  functions call this for you, so it is only needed when failing fast at
  startup is preferable to discovering the database on first use."
  {:malli/schema [:-> #'client-schema :nil]}
  [client]
  (ready client)
  nil)

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn new-session-id
  "Generate a fresh random session id (a UUID string)."
  {:malli/schema [:-> #'non-empty-string]}
  []
  (sessions/new-session-id))

(defn create-session
  "Create and persist a new session, returning its state map.

  Options:

    :cwd     the working directory for the session (required in practice,
             defaults to the JVM's current directory).
    :model   the model name the session chats with (optional).
    :params  a map of any additional session parameters to store.

  The returned map includes `:sessionId`, `:cwd`, `:params`, `:createdAt`,
  `:updatedAt`, and an empty `:history`. The same map shape is returned by
  `load-session` and `list-sessions`."
  {:malli/schema [:function
                  [:=> [:cat #'client-schema] #'session]
                  [:=> [:cat #'client-schema [:maybe #'create-session-opts]]
                   #'session]]}
  ([client]
   (sessions/create-session (ready client)))
  ([client opts]
   (sessions/create-session (ready client) opts)))

(defn load-session
  "Read and return the state map for `session-id`, or nil if no such session
  is recorded."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe #'session]]}
  [client session-id]
  (sessions/load-session (ready client) session-id))

(defn lookup-session
  "Alias for `load-session`, for callers that prefer the `lookup` verb."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe #'session]]}
  [client session-id]
  (sessions/lookup-session (ready client) session-id))

(defn save-session
  "Persist `state` (a session map), replacing its message history. Refreshes
  `:updatedAt`. Returns the saved state."
  {:malli/schema [:-> #'client-schema #'session #'session]}
  [client state]
  (sessions/save-session (ready client) state))

(defn delete-session
  "Delete `session-id` and its history. Returns true if a session was
  removed, false otherwise."
  {:malli/schema [:-> #'client-schema #'non-empty-string boolean?]}
  [client session-id]
  (sessions/delete-session (ready client) session-id))

(defn register-session
  "Persist a session under an explicit `id`, rather than generating one.
  Used when a client resumes a session this agent has no record of, so resume
  degrades to new rather than erroring. Returns the saved state."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe :string]
                  [:maybe [:vector #'message]] #'session]}
  [client id cwd history]
  (sessions/register-session (ready client) id cwd history))

(defn list-sessions
  "Return a vector of all recorded session state maps, sorted by `:createdAt`
  ascending. Each entry is the full state map (including `:history`); use
  `list-session-summaries` for lightweight metadata only."
  {:malli/schema [:-> #'client-schema [:vector #'session]]}
  [client]
  (sessions/list-sessions (ready client)))

(defn list-session-summaries
  "Like `list-sessions` but returns only metadata (no `:history`), suitable
  for displaying in a session picker or for ACP `session/list` responses."
  {:malli/schema [:-> #'client-schema [:vector #'session-summary]]}
  [client]
  (sessions/list-session-summaries (ready client)))

(defn session-exists?
  "Return true if `session-id` is recorded."
  {:malli/schema [:-> #'client-schema #'non-empty-string boolean?]}
  [client session-id]
  (sessions/session-exists? (ready client) session-id))

;; ---------------------------------------------------------------------------
;; History & parameter mutation
;; ---------------------------------------------------------------------------

(defn append-history
  "Append a `{role content}` message to session `session-id` and persist it.
  Returns the message that was appended.

  If the session is unknown, it is treated as a no-op and nil is returned
  (callers should `create-session` first)."
  {:malli/schema [:-> #'client-schema #'non-empty-string #'message
                  [:maybe #'message]]}
  [client session-id msg]
  (sessions/append-history (ready client) session-id msg))

(defn session-history
  "Return the message history vector for `session-id`, or an empty vector if
  the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:vector #'message]]}
  [client session-id]
  (sessions/session-history (ready client) session-id))

(defn set-param
  "Associate `key` with `value` in the session's `:params` map and persist.
  `value` must be JSON-serializable. Returns the updated full state map, or
  nil if the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string :any :any
                  [:maybe #'session]]}
  [client session-id key value]
  (sessions/set-param (ready client) session-id key value))

(defn set-params
  "Merge `m` into the session's `:params` map and persist. Returns the
  updated full state map, or nil if the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:map]
                  [:maybe #'session]]}
  [client session-id m]
  (sessions/set-params (ready client) session-id m))

(defn set-model
  "Record the model name used by `session-id` and persist. Returns the
  updated full state map, or nil if the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe :string]
                  [:maybe #'session]]}
  [client session-id model]
  (sessions/set-model (ready client) session-id model))

(defn clear-history
  "Empty the message history for `session-id` and persist. Returns the
  updated full state map, or nil if the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe #'session]]}
  [client session-id]
  (sessions/clear-history (ready client) session-id))
