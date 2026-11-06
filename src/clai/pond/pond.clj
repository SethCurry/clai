(ns clai.pond.pond
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

(def default-status
  "Status assigned by [[create-todo!]] and [[insert-todo!]] when a todo does
  not specify one."
  "Not Started")

(def default-description
  "Description assigned by [[create-todo!]] and [[insert-todo!]] when a todo
  does not specify one."
  "")

(def todos-schema
  "The Datomic schema for todo entities."
  [{:db/ident       :todo/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "The title of the to do item"}

   {:db/ident       :todo/status
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "The status of the to do item"}

   {:db/ident       :todo/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Longer form notes"}])

(def non-blank-string
  "A string containing at least one non-whitespace character."
  [:and :string [:fn (fn [s] (not (str/blank? s)))]])

(def todo
  "A todo entity, in the shape returned by the query functions."
  [:map
   [:db/id {:optional true} :int]
   [:todo/title non-blank-string]
   [:todo/status {:optional true} :string]
   [:todo/description {:optional true} :string]])

(def todo-input
  "A todo accepted by [[insert-todo!]]. Keys may be namespaced (`:todo/title`)
  or plain (`:title`); plain keys are normalized before transacting."
  [:or
   [:map
    [:todo/title non-blank-string]
    [:todo/status {:optional true} :string]
    [:todo/description {:optional true} :string]]
   [:map
    [:title non-blank-string]
    [:status {:optional true} :string]
    [:description {:optional true} :string]]])

(def todo-pull
  "The pull pattern used by the todo query functions."
  [:db/id :todo/title :todo/status :todo/description])

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
  ([conn] (install-schema! conn todos-schema))
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
  ([{:keys [client client-config db-name schema install-schema?]
     :or   {client-config  default-client-config
            db-name        default-db-name
            schema         todos-schema
            install-schema? true}}]
   (let [client   (or client (make-client client-config))
         created? (ensure-database! client db-name)
         conn     (connect client db-name)]
     (when install-schema?
       (install-schema! conn schema))
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

(defn- todo->tx
  "Normalize a todo input map into a Datomic transaction map."
  [todo]
  (when-not (map? todo)
    (throw (ex-info "A todo must be a map"
                    {:todo todo :type (str (class todo))})))
  (let [title       (or (:todo/title todo) (:title todo))
        status      (or (:todo/status todo) (:status todo) default-status)
        description (or (:todo/description todo)
                        (:description todo)
                        default-description)]
    (when-not (and (string? title) (not (str/blank? title)))
      (throw (ex-info "A todo requires a non-blank title"
                      {:todo todo :title title})))
    (when-not (string? status)
      (throw (ex-info "A todo status must be a string"
                      {:todo todo :status status})))
    (when-not (string? description)
      (throw (ex-info "A todo description must be a string"
                      {:todo todo :description description})))
    (cond-> (-> todo
                (dissoc :title :status :description)
                (assoc :todo/title       title
                       :todo/status      status
                       :todo/description description))
      (nil? (:db/id todo)) (dissoc :db/id))))

(defn insert-todo!
  "Insert (or upsert) one todo through `conn`.

  `todo` is a map with a required `:todo/title` (or `:title`) and optional
  `:todo/status` and `:todo/description` (or plain `:status`/`:description`).
  Missing status/description use [[default-status]] and
  [[default-description]]. Because `:todo/title` is unique, inserting a title
  that already exists updates the existing entity.

  Returns the transaction result from `datomic.client.api/transact`."
  {:malli/schema [:-> :any #'todo-input :any]}
  [conn todo]
  (transact! conn [(todo->tx todo)]))

(defn insert-todos!
  "Insert (or upsert) a collection of todos in a single transaction.

  `todos` may be a collection of maps or a single map. Returns the transaction
  result, or nil when `todos` is empty."
  {:malli/schema [:function
                  [:=> [:cat :any [:or #'todo-input [:sequential #'todo-input]]]
                   [:maybe :any]]]}
  [conn todos]
  (let [todos (if (map? todos) [todos] (seq todos))]
    (when todos
      (transact! conn (mapv todo->tx todos)))))

(defn create-todo!
  "Create (or upsert) a todo from a title and optional attributes.

  `opts` may contain `:status` and `:description`; omitted values use
  [[default-status]] and [[default-description]].

  Returns the transaction result from `datomic.client.api/transact`."
  ([conn title]
   (create-todo! conn title nil))
  ([conn title {:keys [status description]
                :or   {status      default-status
                       description default-description}}]
   (insert-todo! conn {:todo/title       title
                       :todo/status      status
                       :todo/description description})))

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

(defn- pull-todos
  "Pull each entity id in `eids`, sorted by title."
  [db eids]
  (->> eids
       (map #(d/pull db todo-pull %))
       (sort-by :todo/title)
       vec))

(defn all-todos
  "Return every todo, sorted by title."
  {:malli/schema [:-> :any [:vector #'todo]]}
  [db]
  (pull-todos db (map first (d/q '[:find ?e
                                   :where [?e :todo/title]]
                                 db))))

(defn todo-by-title
  "Return the todo whose `:todo/title` is `title`, or nil when there is no
  such todo."
  {:malli/schema [:-> :any non-blank-string [:maybe #'todo]]}
  [db title]
  (when-let [eid (ffirst (d/q '[:find ?e
                                :in $ ?title
                                :where [?e :todo/title ?title]]
                              db title))]
    (d/pull db todo-pull eid)))

(defn todos-by-status
  "Return all todos with `status`, sorted by title."
  {:malli/schema [:-> :any :string [:vector #'todo]]}
  [db status]
  (pull-todos db (map first (d/q '[:find ?e
                                   :in $ ?status
                                   :where [?e :todo/status ?status]]
                                 db status))))

(defn all-titles
  "Return every todo title, sorted alphabetically."
  {:malli/schema [:-> :any [:vector non-blank-string]]}
  [db]
  (->> (d/q '[:find ?title
              :where [_ :todo/title ?title]]
            db)
       (map first)
       sort
       vec))

(defn- criteria->attrs
  "Normalize a criteria map's plain keys to their namespaced equivalents."
  [criteria]
  (reduce-kv (fn [attrs k v]
               (if (some? v)
                 (assoc attrs
                        (case k
                          (:title :todo/title)             :todo/title
                          (:status :todo/status)           :todo/status
                          (:description :todo/description) :todo/description
                          k)
                        v)
                 attrs))
             {}
             criteria))

(defn find-todos
  "Return todos matching every entry in `criteria`, sorted by title.

  Criteria keys may be namespaced (`:todo/status`) or plain (`:status`) and
  values are compared for equality. A nil value is ignored, so an empty or
  all-nil criteria map returns all todos.

      (find-todos db {:status \"Done\"})
      (find-todos db {:todo/title \"Ship it\"})"
  {:malli/schema [:-> :any [:map] [:vector #'todo]]}
  [db criteria]
  (let [attrs (criteria->attrs (or criteria {}))]
    (if (empty? attrs)
      (all-todos db)
      (let [ks      (vec (keys attrs))
            vars    (mapv #(symbol (str "?v" %)) (range (count ks)))
            query   {:find  '[?e]
                     :in    (into '[$] vars)
                     :where (mapv (fn [k v] ['?e k v]) ks vars)}]
        (pull-todos db (map first (apply d/q query db (vals attrs))))))))
