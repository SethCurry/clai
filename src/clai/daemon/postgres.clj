(ns clai.daemon.postgres
  "A PostgreSQL client for `clai`'s daemon.

  The client owns no global connection or mutable state: requiring this
  namespace has no side effects. Call [[make-client]] (or [[open]]) to build a
  client, then use the session and arbitrary-data functions with it.

  ## Connections

  [[make-client]] builds a lazy `javax.sql.DataSource`; it does not contact the
  database until the first query. Connection options may be supplied directly
  or through the environment:

      :host  CLAI_POSTGRES_HOST, PGHOST       (default \"localhost\")
      :port  CLAI_POSTGRES_PORT, PGPORT       (default 5432)
      :database CLAI_POSTGRES_DATABASE,
                CLAI_POSTGRES_DB, PGDATABASE (default \"clai\")
      :user  CLAI_POSTGRES_USER, PGUSER
      :password CLAI_POSTGRES_PASSWORD, PGPASSWORD
      :ssl-mode CLAI_POSTGRES_SSL_MODE,
                CLAI_POSTGRES_SSLMODE, PGSSLMODE
      :connect-timeout CLAI_POSTGRES_CONNECT_TIMEOUT (seconds)
      :socket-timeout  CLAI_POSTGRES_SOCKET_TIMEOUT  (seconds)
      :application-name CLAI_POSTGRES_APPLICATION_NAME (default \"clai\")

  A full JDBC URL can be given as `:url` (or `CLAI_POSTGRES_URL` /
  `DATABASE_URL`). When present it wins over the individual host/port/database
  options; `:user` and `:password` are still passed to the driver as JDBC
  properties.

  ## Schema

  Call [[ensure-schema!]] once ([[open]] does it for you) before using the
  storage functions. It creates three idempotent tables:

      clai_sessions          session metadata and `:params`
      clai_session_messages  append-only message history, one JSONB per row
      clai_data              arbitrary namespaced key/value data

  The session tables mirror the shape used by `clai.agent.state`; that
  namespace is a thin facade over the session functions here.

  ## Example

      (require '[clai.daemon.postgres :as pg])

      (def client (pg/open {:host \"localhost\"
                            :database \"clai\"
                            :user \"clai\"
                            :password \"secret\"}))

      (def session (pg/create-session client {:cwd \"/tmp/project\"
                                              :model \"gemma3\"}))
      (pg/append-history client (:sessionId session)
                         {:role \"user\" :content \"hello\"})

      (pg/put-data! client \"notes\" \"todo\" {:text \"write the docs\"})
      (pg/get-data client \"notes\" \"todo\")
      ;; => {:text \"write the docs\"}"
  (:require [cheshire.core :as json]
            [clai.log]
            [clai.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn]
            [next.jdbc.result-set :as rs]
            [taoensso.telemere :as tel])
  (:import [java.io File]
           [java.sql Timestamp]
           [java.time Instant OffsetDateTime]
           [java.time.temporal ChronoUnit]
           [java.util Date UUID]
           [javax.sql DataSource]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  util/non-empty-string)

(def client-options
  "Options accepted by [[make-client]]."
  [:map
   [:url {:optional true} non-empty-string]
   [:host {:optional true} non-empty-string]
   [:port {:optional true} [:int {:min 1 :max 65535}]]
   [:database {:optional true} non-empty-string]
   [:user {:optional true} [:maybe :string]]
   [:password {:optional true} [:maybe :string]]
   [:ssl-mode {:optional true}
    [:enum "disable" "allow" "prefer" "require" "verify-ca" "verify-full"]]
   [:connect-timeout {:optional true} pos-int?]
   [:socket-timeout {:optional true} pos-int?]
   [:application-name {:optional true} non-empty-string]
   [:datasource {:optional true} [:fn #(instance? DataSource %)]]])

(def client-schema
  "The `Client` record."
  [:map
   [:datasource [:fn #(instance? DataSource %)]]
   [:options client-options]])

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

(def data-entry
  "A value returned by the arbitrary-data store."
  [:map
   [:namespace non-empty-string]
   [:key non-empty-string]
   [:value :any]
   [:createdAt :string]
   [:updatedAt {:optional true} [:maybe :string]]])

(def data-name
  "A namespace or key accepted by the arbitrary-data store."
  [:or non-empty-string :keyword])

;; ---------------------------------------------------------------------------
;; Defaults and validation
;; ---------------------------------------------------------------------------

(def ^:private default-host "localhost")
(def ^:private default-port 5432)
(def ^:private default-database "clai")
(def ^:private default-application-name "clai")

(defn- validate!
  "Validate `value` against `schema`, returning `value` when valid or throwing
  an `ex-info` with a human-readable malli explanation otherwise."
  [schema value fn-name]
  (if-let [errors (and schema (m/explain schema value))]
    (let [explained (me/humanize errors)]
      (tel/log! {:level :error
                 :id    ::invalid-value
                 :data  {:fn fn-name :errors explained}}
                (str "Invalid " fn-name " value"))
      (throw (ex-info (str "Invalid " fn-name " value: " (pr-str explained))
                      {:fn fn-name :schema schema :errors errors})))
    value))

;; ---------------------------------------------------------------------------
;; Option resolution
;; ---------------------------------------------------------------------------

(defn ^:no-doc env
  "Return the first non-nil environment variable named in `names`, or nil.
  Exposed so tests and embedders can substitute the environment."
  [& names]
  (some #(System/getenv %) names))

(defn- env-raw
  "Return the first environment variable named in `names` whose value is set,
  preserving empty strings."
  [& names]
  (some #(env %) names))

(defn- env-non-blank
  "Return the first non-blank environment variable named in `names`, or nil."
  [& names]
  (some (fn [n] (let [v (env n)] (when-not (str/blank? v) v))) names))

(defn- env-int
  "Read the first environment variable named in `names` as an integer. Invalid
  values are logged and ignored, falling back to the next source."
  [label & names]
  (when-let [s (apply env-non-blank names)]
    (or (parse-long s)
        (do
          (tel/log! {:level :warn
                     :id    ::invalid-env-int
                     :data  {:label label :value s}}
                    "Ignoring invalid PostgreSQL integer environment variable")
          nil))))

(defn- resolve-options
  "Resolve `opts` over the environment and the built-in defaults."
  [opts]
  (let [opts             (or opts {})
        url              (or (:url opts)
                             (env-non-blank "CLAI_POSTGRES_URL" "DATABASE_URL"))
        host             (or (:host opts)
                             (env-non-blank "CLAI_POSTGRES_HOST" "PGHOST")
                             default-host)
        port             (or (:port opts)
                             (env-int "port" "CLAI_POSTGRES_PORT" "PGPORT")
                             default-port)
        database         (or (:database opts)
                             (env-non-blank "CLAI_POSTGRES_DATABASE"
                                            "CLAI_POSTGRES_DB"
                                            "PGDATABASE")
                             default-database)
        user             (or (:user opts)
                             (env-non-blank "CLAI_POSTGRES_USER" "PGUSER"))
        password         (if (contains? opts :password)
                           (:password opts)
                           (env-raw "CLAI_POSTGRES_PASSWORD" "PGPASSWORD"))
        ssl-mode         (or (:ssl-mode opts)
                             (env-non-blank "CLAI_POSTGRES_SSL_MODE"
                                            "CLAI_POSTGRES_SSLMODE"
                                            "PGSSLMODE"))
        connect-timeout  (or (:connect-timeout opts)
                             (env-int "connect-timeout"
                                      "CLAI_POSTGRES_CONNECT_TIMEOUT"))
        socket-timeout   (or (:socket-timeout opts)
                             (env-int "socket-timeout"
                                      "CLAI_POSTGRES_SOCKET_TIMEOUT"))
        application-name (or (:application-name opts)
                             (env-non-blank "CLAI_POSTGRES_APPLICATION_NAME")
                             default-application-name)]
    (cond-> {:host             host
             :port             port
             :database         database
             :application-name application-name}
      url                    (assoc :url url)
      (some? user)           (assoc :user user)
      (some? password)       (assoc :password password)
      (some? ssl-mode)       (assoc :ssl-mode ssl-mode)
      (some? connect-timeout) (assoc :connect-timeout connect-timeout)
      (some? socket-timeout)  (assoc :socket-timeout socket-timeout)
      (contains? opts :datasource) (assoc :datasource (:datasource opts)))))

(defn- connection-spec
  "Build the `next.jdbc` data-source specification for resolved `opts`.

  Credentials are passed as JDBC properties rather than embedded in the URL so
  passwords with URL-significant characters work and so the URL stays safe to
  log."
  [opts]
  (let [url (or (:url opts)
                (let [spec (cond-> {:dbtype "postgresql"
                                    :dbname (:database opts)
                                    :host   (:host opts)
                                    :port   (:port opts)}
                             (:ssl-mode opts)
                             (assoc :sslmode (:ssl-mode opts))
                             (:connect-timeout opts)
                             (assoc :connectTimeout (:connect-timeout opts))
                             (:socket-timeout opts)
                             (assoc :socketTimeout (:socket-timeout opts))
                             (:application-name opts)
                             (assoc :ApplicationName (:application-name opts)))]
                  (conn/jdbc-url spec)))]
    (cond-> {:jdbcUrl url}
      (some? (:user opts))     (assoc :user (:user opts))
      (some? (:password opts)) (assoc :password (:password opts)))))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defrecord Client [datasource options])

;; [[open]] and [[ensure-schema!]] are defined below with the SQL helpers they
;; depend on.
(declare ensure-schema! exec!)

(defn make-client
  "Create a PostgreSQL client.

  Options (all optional) are described by [[client-options]]. Individual
  connection settings fall back to `CLAI_POSTGRES_*` / standard `PG*`
  environment variables and then to localhost:5432/clai. Pass `:url` for a
  complete JDBC URL, or `:datasource` to use an existing `javax.sql.DataSource`
  (for example a HikariCP pool) instead of building one.

  No connection is opened by this function; the first query does that. Call
  [[ensure-schema!]] before using the storage functions."
  {:malli/schema [:function
                  [:=> [:cat] #'client-schema]
                  [:=> [:cat [:maybe #'client-options]] #'client-schema]]}
  ([] (make-client nil))
  ([opts]
   (let [opts       (resolve-options opts)
         _          (validate! client-options opts "make-client")
         datasource (or (:datasource opts)
                        (jdbc/get-datasource (connection-spec opts)))
         client     (map->Client {:datasource datasource :options opts})]
     (validate! client-schema client "make-client")
     (tel/log! {:level :debug
                :id    ::client-created
                :data  {:host     (:host opts)
                        :port     (:port opts)
                        :database (:database opts)
                        :user     (:user opts)
                        :url?     (some? (:url opts))}}
               "Created PostgreSQL client")
     client)))

(defn open
  "Create a client with [[make-client]] and ensure the storage schema exists.
  Returns the client. See [[make-client]] for options."
  {:malli/schema [:function
                  [:=> [:cat] #'client-schema]
                  [:=> [:cat [:maybe #'client-options]] #'client-schema]]}
  ([] (open nil))
  ([opts]
   (let [client (make-client opts)]
     (ensure-schema! client)
     client)))

(defn ping
  "Return true when the database answers `SELECT 1`, false otherwise. A failed
  ping is logged but never thrown."
  {:malli/schema [:-> #'client-schema boolean?]}
  [client]
  (try
    (some? (jdbc/execute-one! (:datasource client)
                              ["SELECT 1 AS one"]
                              {:builder-fn rs/as-unqualified-kebab-maps}))
    (catch Throwable e
      (tel/log! {:level :warn
                 :id    ::ping-failed
                 :data  {:error (util/error-message e)}}
                "PostgreSQL ping failed")
      false)))

(defn close!
  "Close the client's data source when it is `AutoCloseable` (as a connection
  pool is). Clients built by [[make-client]] without an explicit datasource use
  a stateless datasource, so this is a no-op for them. Returns nil."
  {:malli/schema [:-> #'client-schema :nil]}
  [client]
  (let [datasource (:datasource client)]
    (when (instance? java.lang.AutoCloseable datasource)
      (try
        (.close ^java.lang.AutoCloseable datasource)
        (catch Throwable e
          (tel/log! {:level :warn
                     :id    ::close-failed
                     :data  {:error (util/error-message e)}}
                    "Failed to close PostgreSQL datasource")))))
  nil)

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(def ^:private schema-ddl
  ["CREATE TABLE IF NOT EXISTS clai_sessions (
      session_id text PRIMARY KEY,
      cwd        text NOT NULL,
      model      text,
      params     jsonb NOT NULL DEFAULT '{}'::jsonb,
      created_at timestamptz NOT NULL,
      updated_at timestamptz NOT NULL
    )"

   "CREATE TABLE IF NOT EXISTS clai_session_messages (
      id         bigserial PRIMARY KEY,
      session_id text NOT NULL
                 REFERENCES clai_sessions(session_id) ON DELETE CASCADE,
      message    jsonb NOT NULL,
      created_at timestamptz NOT NULL DEFAULT now()
    )"

   "CREATE INDEX IF NOT EXISTS clai_session_messages_session_idx
      ON clai_session_messages (session_id, id)"

   "CREATE TABLE IF NOT EXISTS clai_data (
      namespace  text NOT NULL,
      data_key   text NOT NULL,
      value      jsonb NOT NULL,
      created_at timestamptz NOT NULL,
      updated_at timestamptz NOT NULL,
      PRIMARY KEY (namespace, data_key)
    )"])

(defn ensure-schema!
  "Create the session and arbitrary-data tables if they do not exist.
  Idempotent and safe to call on every process start. Returns nil."
  {:malli/schema [:-> #'client-schema :nil]}
  [client]
  (jdbc/with-transaction [tx (:datasource client)]
    (doseq [ddl schema-ddl]
      (exec! tx ::ensure-schema ddl [])))
  (tel/log! {:level :info :id ::schema-ensured}
            "Ensured PostgreSQL storage schema")
  nil)

;; ---------------------------------------------------------------------------
;; SQL helpers
;; ---------------------------------------------------------------------------

(def ^:private row-opts
  {:builder-fn rs/as-unqualified-kebab-maps})

(defn- sql-params
  "Build the `[sql & params]` vector accepted by `next.jdbc`."
  [sql params]
  (into [sql] params))

(defn- sql-failed!
  "Log and rethrow a failed SQL operation."
  [op e]
  (tel/error! {:id ::query-failed :data {:op op}} e)
  (throw (ex-info (str "PostgreSQL operation failed: " (name op)
                       " (" (util/error-message e) ")")
                  {:op op} e)))

(defn- exec!
  "Execute `sql` with `params`, returning a vector of rows (or update-count
  maps)."
  [connectable op sql params]
  (try
    (jdbc/execute! connectable (sql-params sql params) row-opts)
    (catch Throwable e
      (sql-failed! op e))))

(defn- exec-one!
  "Execute `sql` with `params`, returning the first row (or update-count map)."
  [connectable op sql params]
  (try
    (jdbc/execute-one! connectable (sql-params sql params) row-opts)
    (catch Throwable e
      (sql-failed! op e))))

(defn- updated-count
  "The number of rows affected by an `exec-one!` INSERT/UPDATE/DELETE."
  [result]
  (long (or (:next.jdbc/update-count result) 0)))

;; ---------------------------------------------------------------------------
;; JSON and timestamp helpers
;; ---------------------------------------------------------------------------

(defn- parse-json
  "Parse `s` as JSON, keywordizing object keys. Returns `default` for nil or
  blank input."
  [s default]
  (if (or (nil? s) (str/blank? s))
    default
    (json/parse-string s true)))

(defn- ->json
  "Serialize `value` as JSON, naming `label` in the error when it cannot be
  serialized."
  [value label]
  (try
    (json/generate-string value)
    (catch Throwable e
      (throw (ex-info (str label " is not JSON-serializable: "
                           (util/error-message e))
                      {:label label} e)))))

(defn- now-iso
  "Current UTC time as an ISO-8601 string, truncated to microseconds so it
  round-trips through a PostgreSQL `timestamptz` unchanged."
  []
  (str (.truncatedTo (Instant/now) ChronoUnit/MICROS)))

(defn- timestamp->iso
  "Render a JDBC/`java.time` timestamp as an ISO-8601 string."
  [v]
  (cond
    (nil? v) nil
    (instance? Timestamp v) (str (.toInstant ^Timestamp v))
    (instance? OffsetDateTime v) (str (.toInstant ^OffsetDateTime v))
    (instance? Date v) (str (.toInstant ^Date v))
    :else (str v)))

(defn- normalize-name
  "Normalize a keyword/string namespace or key to a non-blank string.
  Namespaced keywords keep their namespace (`:a/b` becomes `\"a/b\"`)."
  [x label]
  (let [s (cond
            (keyword? x) (if-let [ns (namespace x)] (str ns "/" (name x)) (name x))
            (string? x) x
            :else (str x))]
    (when (str/blank? s)
      (throw (ex-info (str label " must not be blank") {:value x})))
    s))

;; ---------------------------------------------------------------------------
;; Row conversion
;; ---------------------------------------------------------------------------

(defn- row->summary
  "Convert a `clai_sessions` row to the session summary shape."
  [row]
  {:sessionId (:session-id row)
   :cwd       (:cwd row)
   :model     (:model row)
   :params    (or (parse-json (:params row) nil) {})
   :createdAt (timestamp->iso (:created-at row))
   :updatedAt (timestamp->iso (:updated-at row))})

(defn- row->session
  "Convert a `clai_sessions` row plus its `history` to a session state map."
  [row history]
  (assoc (row->summary row) :history history))

(defn- row->data-entry
  "Convert a `clai_data` row to a data entry."
  [row]
  {:namespace (:namespace row)
   :key       (:data-key row)
   :value     (parse-json (:value row) nil)
   :createdAt (timestamp->iso (:created-at row))
   :updatedAt (timestamp->iso (:updated-at row))})

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
  (exec-one! connectable ::upsert-session upsert-session-sql
             [(:sessionId state)
              (:cwd state)
              (:model state)
              (->json (or (:params state) {}) "Session params")
              (:createdAt state)
              (:updatedAt state)]))

(defn- history-from
  "Read a session's full message history from `connectable`."
  [connectable session-id]
  (mapv (fn [row] (or (parse-json (:message row) nil) {}))
        (exec! connectable ::load-history select-history-sql [session-id])))

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
  {:malli/schema [:-> #'client-schema #'session #'session]}
  [client state]
  (let [state (-> state
                  (assoc :history (vec (or (:history state) [])))
                  (update :params #(or % {}))
                  (cond-> (not (contains? state :model)) (assoc :model nil))
                  (assoc :updatedAt (now-iso)))]
    (validate! session state "save-session")
    (jdbc/with-transaction [tx (:datasource client)]
      (upsert-session! tx state)
      (exec! tx ::clear-history
             "DELETE FROM clai_session_messages WHERE session_id = ?"
             [(:sessionId state)])
      (doseq [msg (:history state)]
        (exec-one! tx ::insert-message insert-message-sql
                   [(:sessionId state) (->json msg "Session message")])))
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
                  [:=> [:cat #'client-schema] #'session]
                  [:=> [:cat #'client-schema [:maybe #'create-session-opts]]
                   #'session]]}
  ([client] (create-session client nil))
  ([client {:keys [cwd model params] :or {params {}}}]
   (let [now     (now-iso)
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
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe :string]
                  [:maybe [:vector #'message]] #'session]}
  [client id cwd history]
  (let [now     (now-iso)
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
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe #'session]]}
  [client session-id]
  (let [datasource (:datasource client)]
    (when-let [row (exec-one! datasource ::load-session
                              select-session-sql [session-id])]
      (row->session row (history-from datasource session-id)))))

(defn lookup-session
  "Alias for [[load-session]]."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe #'session]]}
  [client session-id]
  (load-session client session-id))

(defn delete-session
  "Delete `session-id` and its history. Returns true when a session was
  removed, false otherwise."
  {:malli/schema [:-> #'client-schema #'non-empty-string boolean?]}
  [client session-id]
  (let [result   (exec-one! (:datasource client) ::delete-session
                            "DELETE FROM clai_sessions WHERE session_id = ?"
                            [session-id])
        deleted? (pos? (updated-count result))]
    (tel/log! {:level :debug
               :id    ::session-deleted
               :data  {:sessionId session-id :deleted? deleted?}}
              "Deleted PostgreSQL session")
    deleted?))

(defn session-exists?
  "Return true when `session-id` is recorded."
  {:malli/schema [:-> #'client-schema #'non-empty-string boolean?]}
  [client session-id]
  (some? (exec-one! (:datasource client) ::session-exists
                    "SELECT 1 AS one FROM clai_sessions WHERE session_id = ?"
                    [session-id])))

(defn list-sessions
  "Return every session state map, sorted by `:createdAt` ascending, with full
  histories. Use [[list-session-summaries]] for metadata only."
  {:malli/schema [:-> #'client-schema [:vector #'session]]}
  [client]
  (let [datasource (:datasource client)
        sessions   (exec! datasource ::list-sessions list-sessions-sql [])
        messages   (exec! datasource ::list-messages
                          "SELECT session_id, message::text AS message
                             FROM clai_session_messages
                            ORDER BY id"
                          [])
        by-session (reduce (fn [m row]
                             (update m (:session-id row)
                                     (fnil conj [])
                                     (or (parse-json (:message row) nil) {})))
                           {}
                           messages)]
    (mapv (fn [row]
            (row->session row (get by-session (:session-id row) [])))
          sessions)))

(defn list-session-summaries
  "Like [[list-sessions]] but returns only metadata (no histories), suitable
  for session pickers and ACP `session/list` responses."
  {:malli/schema [:-> #'client-schema [:vector #'session-summary]]}
  [client]
  (mapv row->summary
        (exec! (:datasource client) ::list-session-summaries
               list-sessions-sql [])))

;; ---------------------------------------------------------------------------
;; Session history and parameters
;; ---------------------------------------------------------------------------

(defn session-history
  "Return the message history vector for `session-id`, or `[]` when the
  session is unknown or has no messages."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:vector #'message]]}
  [client session-id]
  (history-from (:datasource client) session-id))

(defn append-history
  "Append `msg` to session `session-id` and touch its `:updatedAt`. Returns the
  message that was appended, or nil (with a warning) when the session is
  unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string #'message
                  [:maybe #'message]]}
  [client session-id msg]
  (validate! message msg "append-history")
  (let [datasource (:datasource client)
        msg-json   (->json msg "Session message")]
    (jdbc/with-transaction [tx datasource]
      (let [result (exec-one! tx ::append-history
                              "INSERT INTO clai_session_messages (session_id, message)
                               SELECT ?, CAST(? AS jsonb)
                                WHERE EXISTS (SELECT 1 FROM clai_sessions
                                               WHERE session_id = ?)"
                              [session-id msg-json session-id])]
        (if (pos? (updated-count result))
          (do
            (exec-one! tx ::touch-session
                       "UPDATE clai_sessions
                           SET updated_at = CAST(? AS timestamptz)
                         WHERE session_id = ?"
                       [(now-iso) session-id])
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
    (let [result (exec-one! tx op update-sql (conj (vec update-params) session-id))]
      (if (pos? (updated-count result))
        (let [row (exec-one! tx ::reload-session
                             select-session-sql [session-id])]
          (row->session row (history-from tx session-id)))
        (do
          (warn-unknown-session op session-id)
          nil)))))

(defn set-param
  "Associate `key` with `value` in the session's `:params` map. `value` must be
  JSON-serializable. Returns the updated full state map, or nil when the
  session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string :any :any
                  [:maybe #'session]]}
  [client session-id key value]
  (let [key*   (normalize-name key "Session parameter key")
        value* (->json value "Session parameter value")]
    (mutate-session! client ::set-param session-id
                     "UPDATE clai_sessions
                         SET params = jsonb_set(params, ARRAY[?]::text[],
                                                CAST(? AS jsonb), true),
                             updated_at = CAST(? AS timestamptz)
                       WHERE session_id = ?"
                     [key* value* (now-iso)])))

(defn set-params
  "Merge `m` into the session's `:params` map. Values must be JSON-serializable.
  Returns the updated full state map, or nil when the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:map]
                  [:maybe #'session]]}
  [client session-id m]
  (let [value* (->json m "Session params")]
    (mutate-session! client ::set-params session-id
                     "UPDATE clai_sessions
                         SET params = params || CAST(? AS jsonb),
                             updated_at = CAST(? AS timestamptz)
                       WHERE session_id = ?"
                     [value* (now-iso)])))

(defn set-model
  "Record the model name used by `session-id`. Returns the updated full state
  map, or nil when the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe :string]
                  [:maybe #'session]]}
  [client session-id model]
  (mutate-session! client ::set-model session-id
                   "UPDATE clai_sessions
                       SET model = ?,
                           updated_at = CAST(? AS timestamptz)
                     WHERE session_id = ?"
                   [model (now-iso)]))

(defn clear-history
  "Delete every message in `session-id` and touch its `:updatedAt`. Returns the
  updated full state map, or nil when the session is unknown."
  {:malli/schema [:-> #'client-schema #'non-empty-string [:maybe #'session]]}
  [client session-id]
  (jdbc/with-transaction [tx (:datasource client)]
    (let [result (exec-one! tx ::clear-history
                            "UPDATE clai_sessions
                                SET updated_at = CAST(? AS timestamptz)
                              WHERE session_id = ?"
                            [(now-iso) session-id])]
      (if (pos? (updated-count result))
        (let [_   (exec! tx ::delete-history
                         "DELETE FROM clai_session_messages WHERE session_id = ?"
                         [session-id])
              row (exec-one! tx ::reload-session
                             select-session-sql [session-id])]
          (row->session row []))
        (do
          (warn-unknown-session ::clear-history session-id)
          nil)))))

;; ---------------------------------------------------------------------------
;; Arbitrary data
;; ---------------------------------------------------------------------------

(def ^:private select-data-sql
  "SELECT namespace, data_key, value::text AS value, created_at, updated_at
     FROM clai_data")

(defn put-data!
  "Store JSON-serializable `value` under `ns-name`/`data-key`, creating or
  replacing it. Returns the stored data entry.

  Namespaces and keys may be strings or keywords. Keyword keys are stored as
  their printed name (preserving a keyword namespace, so `:a/b` is stored as
  `\"a/b\"`)."
  {:malli/schema [:-> #'client-schema #'data-name #'data-name :any
                  #'data-entry]}
  [client ns-name data-key value]
  (let [ns*    (normalize-name ns-name "Data namespace")
        key*   (normalize-name data-key "Data key")
        value* (->json value "Data value")
        now    (now-iso)
        row    (exec-one! (:datasource client) ::put-data
                          "INSERT INTO clai_data
                                 (namespace, data_key, value, created_at, updated_at)
                           VALUES (?, ?, CAST(? AS jsonb),
                                   CAST(? AS timestamptz), CAST(? AS timestamptz))
                           ON CONFLICT (namespace, data_key) DO UPDATE
                              SET value      = EXCLUDED.value,
                                  updated_at = EXCLUDED.updated_at
                           RETURNING namespace, data_key, value::text AS value,
                                     created_at, updated_at"
                          [ns* key* value* now now])]
    (tel/log! {:level :debug
               :id    ::data-put
               :data  {:namespace ns* :key key*}}
              "Stored PostgreSQL data entry")
    (row->data-entry row)))

(defn lookup-data
  "Return the full data entry stored under `ns-name`/`data-key`, or nil when it
  does not exist. Unlike [[get-data]] this can distinguish a missing entry from
  a stored JSON `null`."
  {:malli/schema [:-> #'client-schema #'data-name #'data-name
                  [:maybe #'data-entry]]}
  [client ns-name data-key]
  (let [ns*  (normalize-name ns-name "Data namespace")
        key* (normalize-name data-key "Data key")]
    (when-let [row (exec-one! (:datasource client) ::lookup-data
                              (str select-data-sql
                                   " WHERE namespace = ? AND data_key = ?")
                              [ns* key*])]
      (row->data-entry row))))

(defn get-data
  "Return the value stored under `ns-name`/`data-key`, or nil when no such
  entry exists. Use [[lookup-data]] when a stored JSON `null` must be
  distinguished from a missing entry."
  {:malli/schema [:-> #'client-schema #'data-name #'data-name :any]}
  [client ns-name data-key]
  (:value (lookup-data client ns-name data-key)))

(defn data-exists?
  "Return true when an entry exists under `ns-name`/`data-key`."
  {:malli/schema [:-> #'client-schema #'data-name #'data-name boolean?]}
  [client ns-name data-key]
  (let [ns*  (normalize-name ns-name "Data namespace")
        key* (normalize-name data-key "Data key")]
    (some? (exec-one! (:datasource client) ::data-exists
                      "SELECT 1 AS one FROM clai_data
                        WHERE namespace = ? AND data_key = ?"
                      [ns* key*]))))

(defn delete-data!
  "Delete the entry stored under `ns-name`/`data-key`. Returns true when an
  entry was removed, false otherwise."
  {:malli/schema [:-> #'client-schema #'data-name #'data-name boolean?]}
  [client ns-name data-key]
  (let [ns*    (normalize-name ns-name "Data namespace")
        key*   (normalize-name data-key "Data key")
        result (exec-one! (:datasource client) ::delete-data
                          "DELETE FROM clai_data
                            WHERE namespace = ? AND data_key = ?"
                          [ns* key*])]
    (pos? (updated-count result))))

(defn list-data
  "Return stored data entries. With no namespace, every entry is returned
  ordered by namespace and key; with `ns-name`, only that namespace's entries
  are returned."
  {:malli/schema [:function
                  [:=> [:cat #'client-schema] [:vector #'data-entry]]
                  [:=> [:cat #'client-schema #'data-name]
                   [:vector #'data-entry]]]}
  ([client]
   (mapv row->data-entry
         (exec! (:datasource client) ::list-data
                (str select-data-sql " ORDER BY namespace, data_key")
                [])))
  ([client ns-name]
   (let [ns* (normalize-name ns-name "Data namespace")]
     (mapv row->data-entry
           (exec! (:datasource client) ::list-data
                  (str select-data-sql
                       " WHERE namespace = ? ORDER BY data_key")
                  [ns*])))))
