(ns clai.agent.configs
  "Persisted agent configurations for `clai`.

  An agent configuration is one `agent_config` row (see `schema/schema.sql`).
  It names a reusable set of model, prompt, and tool settings that a session
  can select. Columns that are NULL fall back to the agent's own
  configuration, so a row can override only the parts it cares about.

  Functions take a `clai.daemon.postgres/Client` as their first argument and
  ensure the storage schema exists on first use, just like
  `clai.daemon.postgres.sessions`.

  ## Example

      (require '[clai.agent.configs :as configs]
               '[clai.daemon.postgres :as pg])

      (def client (pg/make-client {:host \"localhost\"}))
      (configs/list-configs client)
      (configs/load-config client \"1\")
      (configs/select-config client \"fast\")"
  (:require [clai.daemon.postgres :as postgres]
            [clojure.string :as str])
  (:import [java.sql Array]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def AgentConfigInput
  "A configuration accepted by [[create-config]] and [[update-config]].

  `:options`, `:format`, `:tools`, and `:plugins` use the same shapes as the
  config file keys documented in `clai.config`. A missing or nil value clears
  the corresponding override, except `:options`, which defaults to `{}`.
  `:plugins` names the plugins a session may run; nil means every enabled
  plugin, while an empty list disables plugins."
  [:map
   [:name non-empty-string]
   [:model {:optional true} [:maybe :string]]
   [:system-prompt {:optional true} [:maybe :string]]
   [:max-tool-rounds {:optional true} [:maybe pos-int?]]
   [:think {:optional true} [:maybe boolean?]]
   [:options {:optional true} [:maybe [:map]]]
   [:format {:optional true} [:maybe [:or :string [:map]]]]
   [:tools {:optional true} [:maybe [:sequential :string]]]
   [:plugins {:optional true} [:maybe [:sequential :string]]]])

(def agent-config
  "An agent configuration row, as returned by [[list-configs]],
  [[load-config]], and [[select-config]].

  `:options`, `:format`, `:tools`, and `:plugins` use the same shapes as the
  config file keys documented in `clai.config`."
  [:map
   [:configId non-empty-string]
   [:name non-empty-string]
   [:model {:optional true} [:maybe :string]]
   [:system-prompt {:optional true} [:maybe :string]]
   [:max-tool-rounds {:optional true} [:maybe pos-int?]]
   [:think {:optional true} [:maybe boolean?]]
   [:options {:optional true} [:map]]
   [:format {:optional true} [:maybe [:or :string [:map]]]]
   [:tools {:optional true} [:maybe [:sequential :string]]]
   [:plugins {:optional true} [:maybe [:sequential :string]]]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Schema readiness
;; ---------------------------------------------------------------------------

(defonce ^:private schema-ready
  ;; Clients whose storage tables have already been ensured. Keeping this here
  ;; lets callers build a client without touching the database; the schema is
  ;; created on the first configuration operation.
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

(defn ^:no-doc prepare-config
  "Validate and normalize a configuration input for storage.

  Names and models are trimmed, blank models become NULL, tool ids and plugin
  names are trimmed, and `:options` defaults to an empty map. Missing optional
  keys are treated as cleared overrides."
  {:malli/schema [:-> :map #'AgentConfigInput]}
  [config]
  (let [blank->nil (fn [value]
                     (let [text (some-> value str str/trim)]
                       (when-not (str/blank? text) text)))
        config     (cond-> config
                     (contains? config :name)
                     (update :name blank->nil)

                     (contains? config :model)
                     (update :model blank->nil)

                     (contains? config :tools)
                     (update :tools #(when (some? %)
                                       (mapv (comp str/trim str) %)))

                     (contains? config :plugins)
                     (update :plugins #(when (some? %)
                                         (mapv (comp str/trim str) %)))

                     true
                     (update :options #(or % {})))]
    (postgres/validate! AgentConfigInput config "agent configuration")))

(defn- config-params
  "The SQL parameters shared by [[create-config]] and [[update-config]].

  The tools and plugins lists are each passed twice as JSON because
  PostgreSQL statements cannot reuse a positional parameter: the first copy
  decides whether the column is NULL (every tool/plugin) and the second
  expands the array."
  [config]
  (let [tools-json   (some-> (:tools config)
                             (postgres/->json "Agent config tools"))
        plugins-json (some-> (:plugins config)
                             (postgres/->json "Agent config plugins"))]
    [(:name config)
     (:model config)
     (:system-prompt config)
     (:max-tool-rounds config)
     (:think config)
     (postgres/->json (or (:options config) {}) "Agent config options")
     (some-> (:format config) (postgres/->json "Agent config format"))
     tools-json
     tools-json
     plugins-json
     plugins-json]))

;; ---------------------------------------------------------------------------
;; Row conversion
;; ---------------------------------------------------------------------------

(defn ^:no-doc config-key
  "`id` as the `agent_config.id` key it names, or nil when it does not name a
  row. Configuration ids are exposed as strings, so both strings and numbers
  are accepted."
  {:malli/schema [:-> [:maybe [:or :string :int]] [:maybe pos-int?]]}
  [id]
  (when (some? id)
    (when-let [n (if (number? id) id (parse-long (str id)))]
      (when (pos? n) n))))

(defn- text-array->vec
  "Convert a PostgreSQL `text[]` value to a vector of strings, or nil."
  [value]
  (cond
    (nil? value)            nil
    (instance? Array value) (mapv str (.getArray ^Array value))
    :else                   (mapv str value)))

(defn ^:no-doc row->config
  "Convert an `agent_config` row to the public configuration shape. Columns
  that are NULL are omitted so the row only overrides the settings it names."
  {:malli/schema [:-> :map #'agent-config]}
  [row]
  (let [options (or (postgres/parse-json (:model-options row) {}) {})
        format  (postgres/parse-json (:response-format row) nil)]
    (cond-> {:configId (str (:id row))
             :name     (:name row)}
      (some? (:model row))
      (assoc :model (:model row))

      (some? (:system-prompt row))
      (assoc :system-prompt (:system-prompt row))

      (some? (:max-tool-rounds row))
      (assoc :max-tool-rounds (:max-tool-rounds row))

      (some? (:think row))
      (assoc :think (:think row))

      (seq options)
      (assoc :options options)

      (some? format)
      (assoc :format format)

      (some? (:tools row))
      (assoc :tools (text-array->vec (:tools row)))

      (some? (:plugins row))
      (assoc :plugins (text-array->vec (:plugins row)))

      (some? (:created-at row))
      (assoc :createdAt (postgres/timestamp->iso (:created-at row)))

      (some? (:updated-at row))
      (assoc :updatedAt (postgres/timestamp->iso (:updated-at row))))))

;; ---------------------------------------------------------------------------
;; Configuration SQL
;; ---------------------------------------------------------------------------

(def ^:private config-columns
  "The column list shared by every configuration query."
  "id,
   name,
   model,
   system_prompt,
   max_tool_rounds,
   think,
   model_options::text AS model_options,
   response_format::text AS response_format,
   tools,
   plugins,
   created_at,
   updated_at")

(def ^:private select-config-columns
  "A SELECT of the configuration columns, before any WHERE clause."
  (str "SELECT " config-columns " FROM agent_config"))

(def ^:private list-configs-sql
  (str select-config-columns " ORDER BY name, id"))

(def ^:private load-config-sql
  (str select-config-columns " WHERE id = ?"))

(def ^:private select-config-sql
  (str select-config-columns " WHERE name = ?"))

(def ^:private insert-config-sql
  (str "INSERT INTO agent_config
          (name, model, system_prompt, max_tool_rounds, think,
           model_options, response_format, tools, plugins)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                CASE WHEN ?::jsonb IS NULL
                     THEN NULL
                     ELSE ARRAY(SELECT jsonb_array_elements_text(?::jsonb))
                END,
                CASE WHEN ?::jsonb IS NULL
                     THEN NULL
                     ELSE ARRAY(SELECT jsonb_array_elements_text(?::jsonb))
                END)
        RETURNING " config-columns))

(def ^:private update-config-sql
  (str "UPDATE agent_config
           SET name = ?,
               model = ?,
               system_prompt = ?,
               max_tool_rounds = ?,
               think = ?,
               model_options = CAST(? AS jsonb),
               response_format = CAST(? AS jsonb),
               tools = CASE WHEN ?::jsonb IS NULL
                            THEN NULL
                            ELSE ARRAY(SELECT jsonb_array_elements_text(?::jsonb))
                       END,
               plugins = CASE WHEN ?::jsonb IS NULL
                              THEN NULL
                              ELSE ARRAY(SELECT jsonb_array_elements_text(?::jsonb))
                         END,
               updated_at = now()
         WHERE id = ?
         RETURNING " config-columns))

(defn- query-configs
  "Run `sql` with `params` and convert the rows to configurations."
  [connectable sql params]
  (mapv row->config
        (postgres/exec! connectable ::list-configs sql params)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn list-configs
  "Return every stored agent configuration, sorted by name."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'agent-config]]}
  [client]
  (let [client (ready client)]
    (query-configs (:datasource client) list-configs-sql [])))

(defn load-config
  "Return the agent configuration named by `config-id`, or nil when no such
  configuration is stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'agent-config]]}
  [client config-id]
  (let [client (ready client)]
    (when-let [id (config-key config-id)]
      (some-> (postgres/exec-one! (:datasource client) ::load-config
                                  load-config-sql [id])
              row->config))))

(defn select-config
  "Return the agent configuration named by `name`, or nil when no such
  configuration is stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'agent-config]]}
  [client name]
  (let [client (ready client)]
    (some-> (postgres/exec-one! (:datasource client) ::select-config
                                select-config-sql [name])
            row->config)))

(defn create-config
  "Store a new agent configuration and return it.

  `config` is validated with [[AgentConfigInput]]; names are unique, so a
  duplicate name makes the database reject the insert."
  {:malli/schema [:-> #'postgres/client-schema #'AgentConfigInput #'agent-config]}
  [client config]
  (let [client (ready client)
        config (prepare-config config)]
    (row->config
     (postgres/exec-one! (:datasource client) ::create-config
                         insert-config-sql (config-params config)))))

(defn update-config
  "Replace the configuration named by `config-id` and return the updated row,
  or nil when no such configuration exists."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  #'AgentConfigInput [:maybe #'agent-config]]}
  [client config-id config]
  (let [client (ready client)
        config (prepare-config config)]
    (when-let [id (config-key config-id)]
      (some-> (postgres/exec-one! (:datasource client) ::update-config
                                  update-config-sql
                                  (conj (config-params config) id))
              row->config))))

(defn delete-config
  "Delete the configuration named by `config-id`.

  Sessions that selected it fall back to their own agent configuration
  (`agent_session.agent_config_id` is `ON DELETE SET NULL`). Returns true when
  a row was deleted."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client config-id]
  (let [client (ready client)]
    (boolean
     (when-let [id (config-key config-id)]
       (pos? (postgres/updated-count
              (postgres/exec-one! (:datasource client) ::delete-config
                                  "DELETE FROM agent_config WHERE id = ?"
                                  [id])))))))

(defn config-exists?
  "Return true when `config-id` names a stored configuration."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client config-id]
  (let [client (ready client)]
    (boolean
     (when-let [id (config-key config-id)]
       (postgres/exec-one! (:datasource client) ::config-exists
                           "SELECT 1 AS one FROM agent_config WHERE id = ?"
                           [id])))))
