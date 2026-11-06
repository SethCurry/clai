(ns clai.pond.client
  "A small Datomic library for the `todo` schema.

  This namespace deliberately owns no global client, connection, or database
  value: requiring it has no side effects. Call [[open]] (or build the pieces
  yourself with [[make-client]], [[ensure-database!]], [[connect]], and
  [[install-schema!]]) to get a connection, then use the insert and query
  functions with that connection.

  A typical REPL session:

      (require '[clai.pond.pond :as pond]
               '[datomic.client.api :as d])

      (def {:keys [conn]} (pond/open))

      (pond/create-todo! conn \"Write the docs\")
      (pond/insert-todo! conn {:todo/title       \"Ship it\"
                               :todo/status      \"In Progress\"
                               :todo/description \"Cut a release\"})

      (pond/all-todos (d/db conn))
      ;; => [{:db/id 17592186045418
      ;;      :todo/title \"Ship it\"
      ;;      :todo/status \"In Progress\"
      ;;      :todo/description \"Cut a release\"}
      ;;     ...]

      (pond/todo-by-title (d/db conn) \"Write the docs\")

  `:todo/title` is unique, so inserting a map with an existing title updates
  that todo instead of creating a duplicate."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; Client and database lifecycle
;; ---------------------------------------------------------------------------

(def default-client-config
  "Settings passed to `datomic.client.api/client` by [[make-client]] when no
  explicit configuration is supplied."
  {:server-type :datomic-local
   :system      "dev"})

(def default-db-name
  "Database opened by [[open]] when no database name is supplied."
  "todos")

(defn make-client
  "Create a Datomic client.

  `config` is merged over [[default-client-config]], so by default this is a
  `:datomic-local` client for the `\"dev\"` system. Supply `:storage-dir` (or
  any other `d/client` option) to point the local system somewhere else."
  ([] (make-client nil))
  ([config]
   (d/client (merge default-client-config config))))

(defn database-exists?
  "Return true when `db-name` exists in `client`."
  [client db-name]
  (boolean (some #{db-name} (d/list-databases client {}))))

(defn ensure-database!
  "Create `db-name` in `client` if it does not exist. Returns true when the
  database was created, false when it already existed."
  ([client] (ensure-database! client default-db-name))
  ([client db-name]
   (if (database-exists? client db-name)
     false
     (do
       (d/create-database client {:db-name db-name})
       true))))

(defn connect
  "Connect to `db-name` in `client`, returning a Datomic connection."
  ([client] (connect client default-db-name))
  ([client db-name]
   (d/connect client {:db-name db-name})))

(defn install-schema!
  "Transact `schema` into `conn`. Datomic schema transactions are idempotent,
  so it is safe to call this every time a database is opened.

  Returns the transaction result from `datomic.client.api/transact`."
  ([conn schema]
   (d/transact conn {:tx-data schema})))

(defn open
  "Open the todo database, creating it and installing the schema if needed.

  Returns a map containing:

    :client    the Datomic client
    :conn      the connection
    :db        a database value (a snapshot of the current database)
    :db-name   the database name
    :created?  true when this call created the database

  Options:

    :client          an existing client to use instead of calling [[make-client]]
    :client-config   configuration for [[make-client]] when `:client` is absent
    :db-name         database name (defaults to [[default-db-name]])
    :schema          schema to install (defaults to [[todos-schema]])
    :install-schema? false to skip the schema transaction"
  ([]
   (open nil))
  ([{:keys [client client-config db-name]
     :or   {client-config  default-client-config
            db-name        default-db-name
            }}]
   (let [client   (or client (make-client client-config))
         created? (ensure-database! client db-name)
         conn     (connect client db-name)]
     {:client   client
      :conn     conn
      :db       (d/db conn)
      :db-name  db-name
      :created? created?})))

(defn init!
  "Alias for [[open]], emphasizing that it creates the database/schema as
  needed."
  ([] (init! nil))
  ([opts] (open opts)))

;; ---------------------------------------------------------------------------
;; Transactions
;; ---------------------------------------------------------------------------

(defn transact!
  "Transact raw Datomic `tx-data` through `conn`."
  [conn tx-data]
  (d/transact conn {:tx-data tx-data}))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn query
  "Run a Datomic query against `db`, passing any additional `inputs` to
  `datomic.client.api/q`.

      (query db '[:find ?e :where [?e :todo/title]])
      (query db '[:find ?e :in $ ?status :where [?e :todo/status ?status]]
             \"Done\")"
  [db query & inputs]
  (apply d/q query db inputs))
