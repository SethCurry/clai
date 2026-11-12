(ns clai.plugins.store
  "PostgreSQL storage for installed `clai` plugins.

  Each row in the `plugin` table (declared in `schema/schema.sql`) names:

    * a plugin's unique name,
    * the path of the Clojure jar that contains it,
    * the namespace in that jar that defines the `plugin` Var,
    * an optional JSON configuration map passed to the plugin, and
    * whether the plugin is enabled and its hook priority.

  Functions take a `clai.daemon.postgres/Client` as their first argument and
  ensure the storage schema exists on first use, like
  `clai.agent.configs` and `clai.daemon.postgres.sessions`.

  ## Example

      (require '[clai.daemon.postgres :as pg]
               '[clai.plugins.store :as store])

      (def client (pg/make-client))
      (store/create-plugin client
                           {:name            \"example\"
                            :jar-path        \"/opt/clai/example.jar\"
                            :entry-namespace \"example.plugin\"})
      (store/list-enabled client)"
  (:require [clai.daemon.postgres :as postgres]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def PluginInput
  "A plugin configuration accepted by [[create-plugin]] and [[update-plugin]].

  `:config` is passed to the plugin's constructor. `:enabled` defaults to
  true and `:priority` defaults to 100; lower priorities run first."
  [:map
   [:name non-empty-string]
   [:jar-path non-empty-string]
   [:entry-namespace non-empty-string]
   [:config {:optional true} [:maybe [:map]]]
   [:enabled {:optional true} [:maybe boolean?]]
   [:priority {:optional true} [:maybe int?]]])

(def Plugin
  "A plugin row, as returned by the functions in this namespace."
  [:map
   [:pluginId non-empty-string]
   [:name non-empty-string]
   [:jarPath non-empty-string]
   [:entryNamespace non-empty-string]
   [:config [:map]]
   [:enabled boolean?]
   [:priority int?]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Schema readiness
;; ---------------------------------------------------------------------------

(defonce ^:private schema-ready
  ;; Clients whose storage tables have already been ensured. Keeping this here
  ;; lets callers build a client without touching the database; the schema is
  ;; created on the first plugin operation.
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

;; ---------------------------------------------------------------------------
;; Input normalization
;; ---------------------------------------------------------------------------

(defn ^:no-doc prepare-plugin
  "Validate and normalize a plugin input for storage.

  Names, paths, and namespaces are trimmed; `:config` defaults to `{}`,
  `:enabled` to true, and `:priority` to 100."
  {:malli/schema [:-> :map #'PluginInput]}
  [plugin]
  (let [blank->nil (fn [value]
                     (let [text (some-> value str str/trim)]
                       (when-not (str/blank? text) text)))
        plugin     (cond-> plugin
                     (contains? plugin :name)
                     (update :name blank->nil)

                     (contains? plugin :jar-path)
                     (update :jar-path blank->nil)

                     (contains? plugin :entry-namespace)
                     (update :entry-namespace blank->nil)

                     true
                     (update :config #(or % {}))

                     true
                     (update :enabled #(if (nil? %) true %))

                     true
                     (update :priority #(if (nil? %) 100 %)))]
    (postgres/validate! PluginInput plugin "plugin configuration")))

(defn- plugin-params
  "The SQL parameters shared by [[create-plugin]] and [[update-plugin]]."
  [plugin]
  [(:name plugin)
   (:jar-path plugin)
   (:entry-namespace plugin)
   (postgres/->json (or (:config plugin) {}) "Plugin config")
   (:enabled plugin)
   (:priority plugin)])

;; ---------------------------------------------------------------------------
;; Row conversion
;; ---------------------------------------------------------------------------

(defn ^:no-doc plugin-key
  "`id` as the `plugin.id` key it names, or nil when it does not name a row."
  {:malli/schema [:-> [:maybe [:or :string :int]] [:maybe pos-int?]]}
  [id]
  (when (some? id)
    (when-let [n (if (number? id) id (parse-long (str id)))]
      (when (pos? n) n))))

(defn ^:no-doc row->plugin
  "Convert a `plugin` row to the public plugin shape."
  {:malli/schema [:-> :map #'Plugin]}
  [row]
  (cond-> {:pluginId       (str (:id row))
           :name           (:name row)
           :jarPath        (:jar-path row)
           :entryNamespace (:entry-namespace row)
           :config         (or (postgres/parse-json (:config row) {}) {})
           :enabled        (boolean (:enabled row))
           :priority       (int (or (:priority row) 100))}
    (some? (:created-at row))
    (assoc :createdAt (postgres/timestamp->iso (:created-at row)))

    (some? (:updated-at row))
    (assoc :updatedAt (postgres/timestamp->iso (:updated-at row)))))

;; ---------------------------------------------------------------------------
;; Plugin SQL
;; ---------------------------------------------------------------------------

(def ^:private plugin-columns
  "The column list shared by every plugin query."
  "id,
   name,
   jar_path,
   entry_namespace,
   config::text AS config,
   enabled,
   priority,
   created_at,
   updated_at")

(def ^:private select-plugin-columns
  "A SELECT of the plugin columns, before any WHERE clause."
  (str "SELECT " plugin-columns " FROM plugin"))

(def ^:private list-plugins-sql
  "SELECT every plugin in load order."
  (str select-plugin-columns " ORDER BY priority, name, id"))

(def ^:private list-enabled-plugins-sql
  "SELECT enabled plugins in load order."
  (str select-plugin-columns
       " WHERE enabled ORDER BY priority, name, id"))

(def ^:private load-plugin-sql
  "SELECT one plugin by its database id."
  (str select-plugin-columns " WHERE id = ?"))

(def ^:private select-plugin-sql
  "SELECT one plugin by its unique name."
  (str select-plugin-columns " WHERE name = ?"))

(def ^:private insert-plugin-sql
  "INSERT a new plugin and return the stored row."
  (str "INSERT INTO plugin
          (name, jar_path, entry_namespace, config, enabled, priority)
        VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?)
        RETURNING " plugin-columns))

(def ^:private update-plugin-sql
  "UPDATE a plugin by id and return the stored row."
  (str "UPDATE plugin
           SET name = ?,
               jar_path = ?,
               entry_namespace = ?,
               config = CAST(? AS jsonb),
               enabled = ?,
               priority = ?,
               updated_at = now()
         WHERE id = ?
         RETURNING " plugin-columns))

(defn- query-plugins
  "Run `sql` with `params` and convert the rows to plugins."
  [connectable sql params]
  (mapv row->plugin
        (postgres/exec! connectable ::list-plugins sql params)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn list-plugins
  "Return every installed plugin, ordered by priority and name."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'Plugin]]}
  [client]
  (query-plugins (:datasource (ready client)) list-plugins-sql []))

(defn list-enabled
  "Return enabled plugins in load order (priority, then name)."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'Plugin]]}
  [client]
  (query-plugins (:datasource (ready client)) list-enabled-plugins-sql []))

(defn load-plugin
  "Return the plugin named by `plugin-id`, or nil when no such row exists."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'Plugin]]}
  [client plugin-id]
  (when-let [id (plugin-key plugin-id)]
    (some-> (postgres/exec-one! (:datasource (ready client)) ::load-plugin
                                load-plugin-sql [id])
            row->plugin)))

(defn select-plugin
  "Return the plugin named by `name`, or nil when no such row exists."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'Plugin]]}
  [client name]
  (some-> (postgres/exec-one! (:datasource (ready client)) ::select-plugin
                              select-plugin-sql [name])
          row->plugin))

(defn create-plugin
  "Store a new plugin and return it. Names are unique, so a duplicate name
  makes the database reject the insert."
  {:malli/schema [:-> #'postgres/client-schema #'PluginInput #'Plugin]}
  [client plugin]
  (let [plugin (prepare-plugin plugin)]
    (row->plugin
     (postgres/exec-one! (:datasource (ready client)) ::create-plugin
                         insert-plugin-sql (plugin-params plugin)))))

(defn update-plugin
  "Replace the plugin named by `plugin-id` and return the updated row, or nil
  when no such plugin exists."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  #'PluginInput [:maybe #'Plugin]]}
  [client plugin-id plugin]
  (let [plugin (prepare-plugin plugin)]
    (when-let [id (plugin-key plugin-id)]
      (some-> (postgres/exec-one! (:datasource (ready client)) ::update-plugin
                                  update-plugin-sql
                                  (conj (plugin-params plugin) id))
              row->plugin))))

(defn delete-plugin
  "Delete the plugin named by `plugin-id`. Returns true when a row was
  deleted."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client plugin-id]
  (let [deleted? (boolean
                  (when-let [id (plugin-key plugin-id)]
                    (pos? (postgres/updated-count
                           (postgres/exec-one! (:datasource (ready client))
                                               ::delete-plugin
                                               "DELETE FROM plugin WHERE id = ?"
                                               [id])))))]
    (tel/log! {:level (if deleted? :info :warn)
               :id    ::plugin-deleted
               :data  {:pluginId plugin-id :deleted? deleted?}}
              "Deleted plugin")
    deleted?))
