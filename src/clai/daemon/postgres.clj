(ns clai.daemon.postgres
  "A PostgreSQL client for `clai`'s daemon, plus storage helpers.

  The client owns no global connection or mutable state: requiring this
  namespace has no side effects. Call [[make-client]] (or [[open]]) to build a
  client, then pass it to the functions here or to the session storage in
  `clai.daemon.postgres.sessions`.

  ## Connections

  [[make-client]] builds a lazy `javax.sql.DataSource`; it does not contact the
  database until the first query. Connection options may be supplied directly
  or through the environment:

      :host  CLAI_POSTGRES_HOST, PGHOST       (default \"localhost\")
      :port  CLAI_POSTGRES_PORT, PGPORT       (default 5432)
      :database CLAI_POSTGRES_DATABASE,
                CLAI_POSTGRES_DB, PGDATABASE (default \"clai\")
      :user  CLAI_POSTGRES_USER, PGUSER       (default \"clai\")
      :password CLAI_POSTGRES_PASSWORD, PGPASSWORD (default \"secret\")
      :ssl-mode CLAI_POSTGRES_SSL_MODE,
                CLAI_POSTGRES_SSLMODE, PGSSLMODE
      :connect-timeout CLAI_POSTGRES_CONNECT_TIMEOUT (seconds)
      :socket-timeout  CLAI_POSTGRES_SOCKET_TIMEOUT  (seconds)
      :application-name CLAI_POSTGRES_APPLICATION_NAME (default \"clai\")

  The default user and password match the local development database in
  `docker-compose.yml`, so a fresh checkout can connect without a config file.
  They are only used when no `:url` is given; a full JDBC URL supplies its own
  credentials. Set `:user`/`:password` or the environment variables above to
  connect anywhere else.

  A full JDBC URL can be given as `:url` (or `CLAI_POSTGRES_URL` /
  `DATABASE_URL`). When present it wins over the individual host/port/database
  options; `:user` and `:password` are still passed to the driver as JDBC
  properties.

  ## Schema

  The DDL is not written here. `schema/schema.sql` is the single source of
  truth; it is read from the classpath and applied by [[ensure-schema!]],
  which [[open]] calls for you. It declares:

      agent_session                one row per agent session
      agent_session_event          events belonging to a session
      agent_session_event_content  JSONB content attached to an event
      plugin                       installed Clojure-jar plugins

  Every statement in that file is idempotent, so applying it on every process
  start (or from the container's `psql` init step) is safe.

  ## Example

      (require '[clai.daemon.postgres :as pg])

      (def client (pg/open {:host \"localhost\"
                            :database \"clai\"
                            :user \"clai\"
                            :password \"secret\"}))

      (pg/exec! (:datasource client) ::insert-session
                \"INSERT INTO agent_session DEFAULT VALUES RETURNING id\" [])"
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
  (:import [java.sql Timestamp]
           [java.time Instant OffsetDateTime]
           [java.time.temporal ChronoUnit]
           [java.util Date]
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

;; ---------------------------------------------------------------------------
;; Defaults and validation
;; ---------------------------------------------------------------------------

(def ^:private default-host "localhost")
(def ^:private default-port 5432)
(def ^:private default-database "clai")
(def ^:private default-user "clai")
(def ^:private default-password "secret")
(def ^:private default-application-name "clai")

(defn ^:no-doc validate!
  "Validate `value` against `schema`, returning `value` when valid or throwing
  an `ex-info` with a human-readable malli explanation otherwise.

  Shared with `clai.daemon.postgres.sessions`."
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
        ;; A JDBC URL carries its own target and credentials, so the local
        ;; development user/password defaults only apply to the
        ;; host/port/database form.
        user             (or (:user opts)
                             (env-non-blank "CLAI_POSTGRES_USER" "PGUSER")
                             (when-not url default-user))
        password         (if (contains? opts :password)
                           (:password opts)
                           (or (env-raw "CLAI_POSTGRES_PASSWORD" "PGPASSWORD")
                               (when-not url default-password)))
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

;; [[ensure-schema!]] is defined below with the SQL helpers it depends on.
(declare ensure-schema! exec!)

(defn make-client
  "Create a PostgreSQL client.

  Options (all optional) are described by [[client-options]]. Individual
  connection settings fall back to `CLAI_POSTGRES_*` / standard `PG*`
  environment variables and then to the local development database
  (localhost:5432/clai as user `clai`, password `secret`, matching
  `docker-compose.yml`). Pass `:url` for a complete JDBC URL (which supplies
  its own credentials), or `:datasource` to use an existing
  `javax.sql.DataSource` (for example a HikariCP pool) instead of building one.

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

(def schema-resource
  "Classpath location of the canonical storage DDL.

  The file lives at `schema/schema.sql` in the repository; `project.clj` adds
  the `schema` directory to `:resource-paths`, so on the classpath it is
  plain `schema.sql`."
  "schema.sql")

(defn ^:private schema-sql
  "Read the canonical DDL from [[schema-resource]] on the classpath."
  []
  (if-let [resource (io/resource schema-resource)]
    (slurp resource)
    (throw (ex-info (str "PostgreSQL schema not found on the classpath: "
                         schema-resource)
                    {:resource schema-resource}))))

(def ^:private transaction-statement?
  "True when `statement` is SQL transaction control, which [[ensure-schema!]]
  supplies itself."
  (let [re #"(?i)^(begin|commit|rollback|start\s+transaction)\b"]
    (fn [statement] (boolean (re-find re statement)))))

(defn ^:private skip-sql-quoted
  "Index just past the quoted SQL region starting at `i`, where `quote` is
  either `'` (a string literal) or `\"` (a quoted identifier).

  Doubled quotes escape the quote character. Backslash escapes are also
  honoured inside string literals, for databases running with
  `standard_conforming_strings` off."
  [^String sql ^long i quote]
  (let [n   (.length sql)
        esc? (= quote \')]
    (loop [j (inc (int i))]
      (if (>= j n)
        n
        (let [c (.charAt sql (int j))]
          (cond
            (and esc? (= c \\)) (recur (+ j 2))
            (= c quote)
            (if (and (< (inc j) n) (= quote (.charAt sql (int (inc j)))))
              (recur (+ j 2))
              (inc j))
            :else (recur (inc j))))))))

(defn ^:private dollar-tag-at
  "The dollar-quote tag (`$$` or `$name$`) starting at `i` in `sql`, or nil."
  [^String sql ^long i]
  (when (= \$ (.charAt sql (int i)))
    (re-find #"^\$(?:[A-Za-z_][A-Za-z0-9_]*)?\$"
             (subs sql (int i) (min (.length sql) (+ (int i) 65))))))

(defn ^:no-doc sql-statements
  "Split a SQL script into executable statements.

  Semicolons inside string literals, quoted identifiers, and dollar-quoted
  blocks (for example `DO $$ ... $$` bodies) do not end a statement, and `--`
  comments are dropped. Blank statements and transaction control (`BEGIN`, `COMMIT`,
  `ROLLBACK`, `START TRANSACTION`) are omitted because [[ensure-schema!]]
  supplies its own transaction. Exposed for tests."
  [sql]
  (let [^String sql (str sql)
        n (.length sql)
        sb (StringBuilder.)]
    (loop [i 0 acc []]
      (if (>= i n)
        (->> (conj acc (str/trim (.toString sb)))
             (remove str/blank?)
             (remove transaction-statement?)
             vec)
        (let [c (.charAt sql (int i))]
          (cond
            ;; `--` line comment: replace it with a space so tokens either
            ;; side of the comment cannot run together.
            (and (= c \-) (< (inc i) n) (= \- (.charAt sql (int (inc i)))))
            (let [j (.indexOf sql "\n" (int i))]
              (.append sb \space)
              (recur (if (neg? j) n (inc j)) acc))

            ;; String literal or quoted identifier, copied verbatim.
            (or (= c \') (= c \"))
            (let [end (skip-sql-quoted sql i c)]
              (.append sb (subs sql (int i) (int end)))
              (recur end acc))

            ;; Dollar-quoted block, copied verbatim.
            (= c \$)
            (if-let [tag (dollar-tag-at sql i)]
              (let [tag-length (.length ^String tag)
                    body       (+ (int i) tag-length)
                    end        (.indexOf sql ^String tag (int body))]
                (if (neg? end)
                  (do (.append sb (subs sql (int i)))
                      (recur n acc))
                  (let [after (+ end tag-length)]
                    (.append sb (subs sql (int i) (int after)))
                    (recur after acc))))
              (do (.append sb c)
                  (recur (inc i) acc)))

            ;; Statement separator.
            (= c \;)
            (let [statement (str/trim (.toString sb))]
              (.setLength sb 0)
              (recur (inc i) (if (str/blank? statement)
                               acc
                               (conj acc statement))))

            :else
            (do (.append sb c)
                (recur (inc i) acc))))))))

(defn ensure-schema!
  "Apply the DDL in [[schema-resource]] (`schema/schema.sql`) to the database.

  Every statement there is idempotent, so this is safe to call on every
  process start and against a database that already has the tables. Returns
  nil."
  {:malli/schema [:-> #'client-schema :nil]}
  [client]
  (let [statements (sql-statements (schema-sql))]
    (jdbc/with-transaction [tx (:datasource client)]
      (doseq [statement statements]
        (exec! tx ::ensure-schema statement [])))
    (tel/log! {:level :info
               :id    ::schema-ensured
               :data  {:resource   schema-resource
                       :statements (count statements)}}
              "Ensured PostgreSQL storage schema")
    nil))

;; ---------------------------------------------------------------------------
;; SQL helpers
;; ---------------------------------------------------------------------------
;;
;; The `^:no-doc` functions here and in the next section are the shared
;; plumbing used by this namespace and by `clai.daemon.postgres.sessions`.

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

(defn ^:no-doc exec!
  "Execute `sql` with `params`, returning a vector of rows (or update-count
  maps)."
  [connectable op sql params]
  (try
    (jdbc/execute! connectable (sql-params sql params) row-opts)
    (catch Throwable e
      (sql-failed! op e))))

(defn ^:no-doc exec-one!
  "Execute `sql` with `params`, returning the first row (or update-count map)."
  [connectable op sql params]
  (try
    (jdbc/execute-one! connectable (sql-params sql params) row-opts)
    (catch Throwable e
      (sql-failed! op e))))

(defn ^:no-doc updated-count
  "The number of rows affected by an [[exec-one!]] INSERT/UPDATE/DELETE."
  [result]
  (long (or (:next.jdbc/update-count result) 0)))

;; ---------------------------------------------------------------------------
;; JSON and timestamp helpers
;; ---------------------------------------------------------------------------

(defn ^:no-doc parse-json
  "Parse `s` as JSON, keywordizing object keys. Returns `default` for nil or
  blank input."
  [s default]
  (if (or (nil? s) (str/blank? s))
    default
    (json/parse-string s true)))

(defn ^:no-doc ->json
  "Serialize `value` as JSON, naming `label` in the error when it cannot be
  serialized."
  [value label]
  (try
    (json/generate-string value)
    (catch Throwable e
      (throw (ex-info (str label " is not JSON-serializable: "
                           (util/error-message e))
                      {:label label} e)))))

(defn ^:no-doc now-iso
  "Current UTC time as an ISO-8601 string, truncated to microseconds so it
  round-trips through a PostgreSQL `timestamptz` unchanged."
  []
  (str (.truncatedTo (Instant/now) ChronoUnit/MICROS)))

(defn ^:no-doc timestamp->iso
  "Render a JDBC/`java.time` timestamp as an ISO-8601 string."
  [v]
  (cond
    (nil? v) nil
    (instance? Timestamp v) (str (.toInstant ^Timestamp v))
    (instance? OffsetDateTime v) (str (.toInstant ^OffsetDateTime v))
    (instance? Date v) (str (.toInstant ^Date v))
    :else (str v)))
