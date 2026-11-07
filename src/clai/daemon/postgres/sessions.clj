(ns clai.daemon.postgres.sessions
  "Session storage on PostgreSQL for `clai`'s daemon.

  Sessions live in the two tables created by
  `clai.daemon.postgres/ensure-schema!`:

      clai_sessions          session metadata and `:params`
      clai_session_messages  append-only message history, one JSONB per row

  Every function here takes a `clai.daemon.postgres/Client` as its first
  argument. Build one with `clai.daemon.postgres/open` (which also creates the
  tables), or with `clai.daemon.postgres/make-client` plus an explicit
  `clai.daemon.postgres/ensure-schema!` call.

  `clai.agent.state` is the thin facade the agent uses, so those functions
  take a client too. Because a session is database rows, mutations are
  transactional and a session is visible to every process sharing the
  database.

  ## Example

      (require '[clai.daemon.postgres :as pg]
               '[clai.daemon.postgres.sessions :as sessions])

      (def client (pg/open {:host \"localhost\"
                            :database \"clai\"
                            :user \"clai\"
                            :password \"secret\"}))

      (def session (sessions/create-session client {:cwd \"/tmp/project\"
                                                    :model \"gemma3\"}))
      (sessions/append-history client (:sessionId session)
                               {:role \"user\" :content \"hello\"})
      (sessions/load-session client (:sessionId session))"
  (:require [clai.daemon.postgres :as postgres]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [taoensso.telemere :as tel])
  (:import [java.io File]
           [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def message
  "A chat message stored in a session's `:history`, shaped like an Ollama
  chat message."
  [:map
   [:role non-empty-string]
   [:content {:optional true} [:or :string [:sequential [:map]]]]
   [:thinking {:optional true} [:maybe :string]]
   [:images {:optional true} [:sequential :string]]
   [:tool_calls {:optional true} [:sequential [:map]]]])

(def session
  "A session state map, as returned by [[create-session]], [[load-session]],
  and [[list-sessions]]."
  [:map
   [:sessionId non-empty-string]
   [:cwd :string]
   [:model {:optional true} [:maybe :string]]
   [:params {:optional true} [:map]]
   [:createdAt :string]
   [:updatedAt {:optional true} [:maybe :string]]
   [:history {:optional true} [:vector message]]])

(def session-summary
  "A session state map without `:history`."
  [:map
   [:sessionId non-empty-string]
   [:cwd :string]
   [:model {:optional true} [:maybe :string]]
   [:params {:optional true} [:map]]
   [:createdAt :string]
   [:updatedAt {:optional true} [:maybe :string]]])

(def create-session-opts
  "Options accepted by [[create-session]]."
  [:map
   [:cwd {:optional true} [:or :string [:fn #(instance? File %)]]]
   [:model {:optional true} [:maybe :string]]
   [:params {:optional true} [:map]]])

;; ---------------------------------------------------------------------------
;; Row conversion
;; ---------------------------------------------------------------------------

(defn- row->summary
  "Convert a `clai_sessions` row to the session summary shape."
  [row]
  {:sessionId (:session-id row)
   :cwd       (:cwd row)
   :model     (:model row)
   :params    (or (postgres/parse-json (:params row) nil) {})
   :createdAt (postgres/timestamp->iso (:created-at row))
   :updatedAt (postgres/timestamp->iso (:updated-at row))})

(defn- row->session
  "Convert a `clai_sessions` row plus its `history` to a session state map."
  [row history]
  (assoc (row->summary row) :history history))

;; ---------------------------------------------------------------------------
;; Session SQL
;; ---------------------------------------------------------------------------

(def ^:private select-session-sql
  "SELECT session_id, cwd, model, params::text AS params,
          created_at, updated_at
     FROM clai_sessions
    WHERE session_id = ?")

(def ^:private list-sessions-sql
  "SELECT session_id, cwd, model, params::text AS params,
          created_at, updated_at
     FROM clai_sessions
    ORDER BY created_at, session_id")

(def ^:private upsert-session-sql
  "INSERT INTO clai_sessions
         (session_id, cwd, model, params, created_at, updated_at)
   VALUES (?, ?, ?, CAST(? AS jsonb),
           CAST(? AS timestamptz), CAST(? AS timestamptz))
   ON CONFLICT (session_id) DO UPDATE
      SET cwd        = EXCLUDED.cwd,
          model      = EXCLUDED.model,
          params     = EXCLUDED.params,
          created_at = EXCLUDED.created_at,
          updated_at = EXCLUDED.updated_at")

(def ^:private insert-message-sql
  "INSERT INTO clai_session_messages (session_id, message)
   VALUES (?, CAST(? AS jsonb))")

(def ^:private select-history-sql
  "SELECT message::text AS message
     FROM clai_session_messages
    WHERE session_id = ?
    ORDER BY id")

(defn- upsert-session!
  "Insert or update session metadata. History is managed separately."
  [connectable state]
  (postgres/exec-one! connectable ::upsert-session upsert-session-sql
                      [(:sessionId state)
                       (:cwd state)
                       (:model state)
                       (postgres/->json (or (:params state) {})
                                        "Session params")
                       (:createdAt state)
                       (:updatedAt state)]))

(defn- row->message
  "Parse the JSON message of a `clai_session_messages` row."
  [row]
  (or (postgres/parse-json (:message row) nil) {}))

(defn- history-from
  "Read a session's full message history from `connectable`."
  [connectable session-id]
  (mapv row->message
        (postgres/exec! connectable ::load-history select-history-sql
                        [session-id])))

(defn- warn-unknown-session
  "Log a warning for a mutation targeting a session that does not exist."
  [op session-id]
  (tel/log! {:level :warn
             :id    ::unknown-session
             :data  {:op op :sessionId session-id}}
            "Session not found"))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn new-session-id
  "Generate a fresh random session id (a UUID string)."
  {:malli/schema [:-> #'non-empty-string]}
  []
  (str (UUID/randomUUID)))

(defn save-session
  "Persist `state` as a session, replacing its message history. Refreshes
  `:updatedAt` and returns the saved state map."
  {:malli/schema [:-> #'postgres/client-schema #'session #'session]}
  [client state]
  (let [state (-> state
                  (assoc :history (vec (or (:history state) [])))
                  (update :params #(or % {}))
                  (cond-> (not (contains? state :model)) (assoc :model nil))
                  (assoc :updatedAt (postgres/now-iso)))]
    (postgres/validate! session state "save-session")
    (jdbc/with-transaction [tx (:datasource client)]
      (upsert-session! tx state)
      (postgres/exec! tx ::clear-history
                      "DELETE FROM clai_session_messages WHERE session_id = ?"
                      [(:sessionId state)])
      (doseq [msg (:history state)]
        (postgres/exec-one! tx ::insert-message insert-message-sql
                            [(:sessionId state)
                             (postgres/->json msg "Session message")])))
    (tel/log! {:level :debug
               :id    ::session-saved
               :data  {:sessionId (:sessionId state)
                       :messages  (count (:history state))}}
              "Saved PostgreSQL session")
    state))

(defn create-session
  "Create and persist a new session, returning its state map.

  Options:

    :cwd     the working directory for the session (defaults to the JVM's
             current directory).
    :model   the model name the session chats with (optional).
    :params  a map of additional session parameters to store."
  {:malli/schema [:function
                  [:=> [:cat #'postgres/client-schema] #'session]
                  [:=> [:cat #'postgres/client-schema
                         [:maybe #'create-session-opts]]
                   #'session]]}
  ([client] (create-session client nil))
  ([client {:keys [cwd model params] :or {params {}}}]
   (let [now     (postgres/now-iso)
         cwd     (cond
                   (instance? File cwd) (str cwd)
                   (string? cwd) cwd
                   :else (str (.getCanonicalFile (io/file "."))))
         session {:sessionId (new-session-id)
                  :cwd       cwd
                  :model     model
                  :params    (or params {})
                  :createdAt now
                  :updatedAt now
                  :history   []}]
     (save-session client session)
     (tel/log! {:level :info
                :id    ::session-created
                :data  {:sessionId (:sessionId session)
                        :cwd       cwd
                        :model     model}}
               "Created PostgreSQL session")
     session)))

(defn register-session
  "Persist a session under an explicit `id` rather than generating one. Used
  when a client resumes a session this agent has no record of. Returns the
  saved state map."
  {:malli/schema [:-> #'postgres/client-schema
                  #'non-empty-string
                  [:maybe :string]
                  [:maybe [:vector #'message]]
                  #'session]}
  [client id cwd history]
  (let [now     (postgres/now-iso)
        session {:sessionId id
                 :cwd       (or cwd ".")
                 :model     nil
                 :params    {}
                 :createdAt now
                 :updatedAt now
                 :history   (vec (or history []))}]
    (save-session client session)
    (tel/log! {:level :info
               :id    ::session-registered
               :data  {:sessionId id :cwd (:cwd session)}}
              "Registered PostgreSQL session under an explicit id")
    session))

(defn load-session
  "Read and return the state map for `session-id`, or nil when no such session
  is recorded."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'session]]}
  [client session-id]
  (let [datasource (:datasource client)]
    (when-let [row (postgres/exec-one! datasource ::load-session
                                       select-session-sql [session-id])]
      (row->session row (history-from datasource session-id)))))

(defn lookup-session
  "Alias for [[load-session]]."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'session]]}
  [client session-id]
  (load-session client session-id))

(defn delete-session
  "Delete `session-id` and its history. Returns true when a session was
  removed, false otherwise."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client session-id]
  (let [result   (postgres/exec-one! (:datasource client) ::delete-session
                                     "DELETE FROM clai_sessions
                                       WHERE session_id = ?"
                                     [session-id])
        deleted? (pos? (postgres/updated-count result))]
    (tel/log! {:level :debug
               :id    ::session-deleted
               :data  {:sessionId session-id :deleted? deleted?}}
              "Deleted PostgreSQL session")
    deleted?))

(defn session-exists?
  "Return true when `session-id` is recorded."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client session-id]
  (some? (postgres/exec-one! (:datasource client) ::session-exists
                             "SELECT 1 AS one FROM clai_sessions
                               WHERE session_id = ?"
                             [session-id])))

(defn list-sessions
  "Return every session state map, sorted by `:createdAt` ascending, with full
  histories. Use [[list-session-summaries]] for metadata only."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'session]]}
  [client]
  (let [datasource (:datasource client)
        rows       (postgres/exec! datasource ::list-sessions
                                   list-sessions-sql [])
        messages   (postgres/exec! datasource ::list-messages
                                   "SELECT session_id, message::text AS message
                                      FROM clai_session_messages
                                     ORDER BY id"
                                   [])
        by-session (reduce (fn [m row]
                             (update m (:session-id row)
                                     (fnil conj [])
                                     (row->message row)))
                           {}
                           messages)]
    (mapv (fn [row]
            (row->session row (get by-session (:session-id row) [])))
          rows)))

(defn list-session-summaries
  "Like [[list-sessions]] but returns only metadata (no histories), suitable
  for session pickers and ACP `session/list` responses."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'session-summary]]}
  [client]
  (mapv row->summary
        (postgres/exec! (:datasource client) ::list-session-summaries
                        list-sessions-sql [])))

;; ---------------------------------------------------------------------------
;; Session history and parameters
;; ---------------------------------------------------------------------------

(defn session-history
  "Return the message history vector for `session-id`, or `[]` when the
  session is unknown or has no messages."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:vector #'message]]}
  [client session-id]
  (history-from (:datasource client) session-id))

(defn append-history
  "Append `msg` to session `session-id` and touch its `:updatedAt`. Returns the
  message that was appended, or nil (with a warning) when the session is
  unknown."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string #'message
                  [:maybe #'message]]}
  [client session-id msg]
  (postgres/validate! message msg "append-history")
  (let [datasource (:datasource client)
        msg-json   (postgres/->json msg "Session message")]
    (jdbc/with-transaction [tx datasource]
      (let [result (postgres/exec-one! tx ::append-history
                                       "INSERT INTO clai_session_messages
                                          (session_id, message)
                                        SELECT ?, CAST(? AS jsonb)
                                         WHERE EXISTS (SELECT 1
                                                         FROM clai_sessions
                                                        WHERE session_id = ?)"
                                       [session-id msg-json session-id])]
        (if (pos? (postgres/updated-count result))
          (do
            (postgres/exec-one! tx ::touch-session
                                "UPDATE clai_sessions
                                    SET updated_at = CAST(? AS timestamptz)
                                  WHERE session_id = ?"
                                [(postgres/now-iso) session-id])
            (tel/log! {:level :debug
                       :id    ::history-appended
                       :data  {:sessionId session-id :role (:role msg)}}
                      "Appended message to PostgreSQL session history")
            msg)
          (do
            (warn-unknown-session ::append-history session-id)
            nil))))))

(defn- mutate-session!
  "Run an UPDATE that ends in `WHERE session_id = ?`, then return the updated
  full session state. Returns nil (with a warning) when the session is unknown."
  [client op session-id update-sql update-params]
  (jdbc/with-transaction [tx (:datasource client)]
    (let [result (postgres/exec-one! tx op update-sql
                                     (conj (vec update-params) session-id))]
      (if (pos? (postgres/updated-count result))
        (let [row (postgres/exec-one! tx ::reload-session
                                      select-session-sql [session-id])]
          (row->session row (history-from tx session-id)))
        (do
          (warn-unknown-session op session-id)
          nil)))))

(defn set-param
  "Associate `key` with `value` in the session's `:params` map. `value` must be
  JSON-serializable. Returns the updated full state map, or nil when the
  session is unknown."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string :any :any
                  [:maybe #'session]]}
  [client session-id key value]
  (let [key*   (postgres/normalize-name key "Session parameter key")
        value* (postgres/->json value "Session parameter value")]
    (mutate-session! client ::set-param session-id
                     "UPDATE clai_sessions
                         SET params = jsonb_set(params, ARRAY[?]::text[],
                                                CAST(? AS jsonb), true),
                             updated_at = CAST(? AS timestamptz)
                       WHERE session_id = ?"
                     [key* value* (postgres/now-iso)])))

(defn set-params
  "Merge `m` into the session's `:params` map. Values must be JSON-serializable.
  Returns the updated full state map, or nil when the session is unknown."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string [:map]
                  [:maybe #'session]]}
  [client session-id m]
  (let [value* (postgres/->json m "Session params")]
    (mutate-session! client ::set-params session-id
                     "UPDATE clai_sessions
                         SET params = params || CAST(? AS jsonb),
                             updated_at = CAST(? AS timestamptz)
                       WHERE session_id = ?"
                     [value* (postgres/now-iso)])))

(defn set-model
  "Record the model name used by `session-id`. Returns the updated full state
  map, or nil when the session is unknown."
  {:malli/schema [:-> #'postgres/client-schema
                  #'non-empty-string
                  [:maybe :string]
                  [:maybe #'session]]}
  [client session-id model]
  (mutate-session! client ::set-model session-id
                   "UPDATE clai_sessions
                       SET model = ?,
                           updated_at = CAST(? AS timestamptz)
                     WHERE session_id = ?"
                   [model (postgres/now-iso)]))

(defn clear-history
  "Delete every message in `session-id` and touch its `:updatedAt`. Returns the
  updated full state map, or nil when the session is unknown."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'session]]}
  [client session-id]
  (jdbc/with-transaction [tx (:datasource client)]
    (let [result (postgres/exec-one! tx ::clear-history
                                     "UPDATE clai_sessions
                                         SET updated_at = CAST(? AS timestamptz)
                                       WHERE session_id = ?"
                                     [(postgres/now-iso) session-id])]
      (if (pos? (postgres/updated-count result))
        (let [_   (postgres/exec! tx ::delete-history
                                  "DELETE FROM clai_session_messages
                                    WHERE session_id = ?"
                                  [session-id])
              row (postgres/exec-one! tx ::reload-session
                                      select-session-sql [session-id])]
          (row->session row []))
        (do
          (warn-unknown-session ::clear-history session-id)
          nil)))))
