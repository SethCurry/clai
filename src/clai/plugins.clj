(ns clai.plugins
  "Runtime plugin support for `clai`.

  A plugin is a Clojure jar containing a namespace that defines a `plugin`
  Var. The Var may be a descriptor map or a function that receives the
  plugin's JSON configuration and returns one:

      (ns example.plugin)

      (defn plugin [config]
        {:hooks
         {:system-prompt (fn [ctx next]
                           (str (next ctx) \"\\n\\nBe concise.\"))}})

  Installed plugins are configured in PostgreSQL by `clai.plugins.store`;
  [[load-configured!]] reads the enabled rows, adds each plugin jar to a
  class loader, requires its entry namespace, and registers its hooks.

  ## Hooks

  Hooks run in plugin priority order (lower first, then by plugin name).

  Every hook receives a `:plugins` selection from its context: a collection of
  plugin names (or ids). `nil` runs every registered plugin, while an empty
  collection disables plugins for that invocation. Agent configurations use
  this to choose the active plugins for their sessions.

    :namespace-builder  Middleware around `clai.repl/namespace-builder`.
                        `(fn [ctx next] ...)`, where `ctx` has `:opts` (the
                        namespace-builder options) and `:plugin`. Call
                        `(next ctx)` to continue with the next plugin and
                        ultimately the default builder. Returns a namespace.
    :system-prompt      Middleware around system-prompt assembly.
                        `(fn [ctx next] ...)`, where `ctx` has `:sys-prompt`,
                        `:cwd`, and `:agents-md`. Call `(next ctx)` to get
                        the assembled prompt and return the final prompt.
    :user-prompt-pre    Called before user messages are persisted. Receives
                        `{:session ... :messages [...] :plugin ...}` and may
                        return replacement messages (a message map or a
                        sequence of message maps); nil keeps them unchanged.
    :user-prompt-post   Called after the user messages are persisted and
                        before the model runs. Receives the same context and
                        may return message(s) to append to the session.
    :agent-message-pre  Called before the final assistant message is
                        persisted. Receives `{:session ... :message ...}` and
                        may return a replacement message map.
    :agent-message-post Called after the assistant message is persisted and
                        before its event is sent to the caller. Receives
                        `{:session ... :message ... :update ...}` and may
                        return a replacement event update map.

  Hook failures are logged and ignored so a broken plugin cannot take down a
  session. The `:plugin` context key carries `{:name ... :config ...}`.

  ## Jar loading

  [[load-jar!]] caches a `URLClassLoader` per jar path. [[load-plugin!]]
  requires the entry namespace with that loader bound as Clojure's compiler
  loader (and as the thread's context loader), so the plugin can require its
  own namespaces from the jar as well as `clai` namespaces from the parent
  class path."
  (:require [clai.daemon.postgres :as postgres]
            [clai.plugins.store :as store]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel])
  (:import [java.net URL URLClassLoader]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def HookName
  "The hook names a plugin descriptor may provide."
  [:enum :namespace-builder
   :system-prompt
   :user-prompt-pre
   :user-prompt-post
   :agent-message-pre
   :agent-message-post])

(def HookFns
  "One hook function, or a sequence of them run in order."
  [:or fn? [:sequential fn?]])

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def PluginDescriptor
  "The map returned by a plugin's `plugin` Var (or constructor function)."
  [:map
   [:description {:optional true} [:maybe :string]]
   [:version {:optional true} [:maybe :string]]
   [:hooks {:optional true} [:map-of HookName HookFns]]])

(def Plugin
  "A plugin row accepted by [[load-plugin!]]."
  store/Plugin)

(def Registration
  "A plugin accepted by [[register!]]. Only `:name` is required; the other
  fields are optional so callers can register hooks directly from a REPL."
  [:map
   [:name non-empty-string]
   [:pluginId {:optional true} [:maybe [:or :string :int]]]
   [:jarPath {:optional true} [:maybe :string]]
   [:entryNamespace {:optional true} [:maybe :string]]
   [:config {:optional true} [:maybe :map]]
   [:enabled {:optional true} [:maybe boolean?]]
   [:priority {:optional true} [:maybe int?]]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

(def ActivePlugins
  "The plugins active for one hook invocation: plugin names or ids. `nil`
  means every registered plugin, and an empty collection disables plugins."
  [:maybe [:sequential :string]])

(def LoadResult
  "The result of [[load-configured!]]."
  [:map
   [:loaded [:vector :string]]
   [:failed [:vector :string]]
   [:error {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Hook registry
;; ---------------------------------------------------------------------------

(defonce ^:private registry
  ;; Registered plugins. Each entry is `{:name ... :priority ... :config ...
  ;; :hooks {hook-name [fn ...]}}`; the hooks map has already been normalized
  ;; to vectors.
  (atom []))

(defonce ^:private jar-loaders
  ;; `{canonical-path URLClassLoader}` so each jar is added to the class path
  ;; once, even when several plugins live in the same jar.
  (atom {}))

(defn- normalize-hooks
  "Normalize a descriptor's `:hooks` map so every value is a vector of
  functions."
  [hooks]
  (into {}
        (map (fn [[hook fns]]
               [hook (vec (if (sequential? fns) fns [fns]))]))
        (or hooks {})))

(defn- plugin-entry
  "Build a registry entry from a plugin row and a descriptor."
  [plugin descriptor]
  {:name     (:name plugin)
   :id       (:pluginId plugin)
   :priority (int (or (:priority plugin) 100))
   :config   (or (:config plugin) {})
   :hooks    (normalize-hooks (:hooks descriptor))})

(defn active-plugins
  "Return descriptions of the currently registered plugins in load order."
  {:malli/schema [:-> [:vector :map]]}
  []
  (->> @registry
       (sort-by (juxt :priority :name))
       (mapv (fn [{:keys [name id priority config hooks]}]
               {:name     name
                :pluginId id
                :priority priority
                :config   config
                :hooks    (vec (sort (keys hooks)))}))))

(defn register!
  "Register `plugin` with `descriptor`, replacing any plugin with the same
  name. Returns the registered plugin description."
  {:malli/schema [:-> #'Registration #'PluginDescriptor :map]}
  [plugin descriptor]
  (let [descriptor (postgres/validate! PluginDescriptor descriptor
                                       "register-plugin")
        entry      (plugin-entry plugin descriptor)]
    (swap! registry
           (fn [plugins]
             (conj (vec (remove #(= (:name %) (:name entry)) plugins))
                   entry)))
    (tel/log! {:level :info
               :id    ::plugin-registered
               :data  {:name    (:name entry)
                       :hooks   (vec (sort (keys (:hooks entry))))
                       :priority (:priority entry)}}
              "Registered plugin")
    (dissoc entry :hooks)))

(defn unregister!
  "Remove the plugin named `name`. Returns true when one was registered."
  {:malli/schema [:-> :string boolean?]}
  [name]
  (let [before (count @registry)]
    (swap! registry (fn [plugins] (vec (remove #(= name (:name %)) plugins))))
    (not= before (count @registry))))

(defn clear!
  "Remove every registered plugin. Jar class loaders are kept so a plugin can
  be reloaded without leaking a new loader. Returns nil."
  {:malli/schema [:-> :nil]}
  []
  (reset! registry [])
  nil)

;; ---------------------------------------------------------------------------
;; Hook resolution
;; ---------------------------------------------------------------------------

(defn- plugin-selected?
  "True when `entry` should run for the `active` plugin selection. A nil
  selection runs everything; otherwise a plugin matches by name or id."
  [active {:keys [name id]}]
  (or (nil? active)
      (let [wanted (into #{} (map str) active)]
        (or (contains? wanted (str name))
            (and (some? id) (contains? wanted (str id)))))))

(defn- resolve-hooks
  "The hook functions registered for `hook-name` that are selected by
  `active`, in run order, annotated with their plugin name, configuration, and
  position within the plugin."
  [hook-name active]
  (->> @registry
       (filter #(plugin-selected? active %))
       (mapcat (fn [{:keys [name config priority hooks]}]
                 (map-indexed (fn [index hook-fn]
                                {:fn     hook-fn
                                 :plugin {:name name :config config}
                                 :order  [priority name index]})
                              (get hooks hook-name))))
       (sort-by :order)
       vec))

(defn- hook-context
  "Add the invoking hook's `:plugin` context to `ctx`."
  [ctx hook]
  (assoc ctx :plugin (:plugin hook)))

(defn- warn-hook-failure!
  "Log a failed hook and return nil so the caller can continue."
  [hook-name {:keys [plugin]} error]
  (tel/log! {:level :warn
             :id    ::hook-failed
             :data  {:hook   hook-name
                     :plugin (:name plugin)
                     :error  (or (ex-message error) (str (class error)))}}
            "Plugin hook failed; continuing without it")
  nil)

(defn- compose-middleware
  "Compose plugin middleware around `base`. Lower-priority middleware is
  outermost and therefore runs first; `active` selects the plugins that
  participate."
  [hook-name active base]
  (reduce (fn [next-hook hook]
            (fn [ctx]
              (try
                ((:fn hook) (hook-context ctx hook) next-hook)
                (catch Throwable e
                  (warn-hook-failure! hook-name hook e)
                  (next-hook ctx)))))
          base
          (reverse (resolve-hooks hook-name active))))

(defn build-namespace
  "Run the `:namespace-builder` hooks around `base` and return the resulting
  namespace. `opts` is the map normally passed to
  `clai.repl/namespace-builder`; its `:plugins` key selects the active
  plugins."
  {:malli/schema [:-> :map [:fn fn?] [:fn #(instance? clojure.lang.Namespace %)]]}
  [opts base]
  ((compose-middleware :namespace-builder (:plugins opts)
                       (fn [ctx] (base (:opts ctx))))
   {:opts opts}))

(defn assemble-system-prompt
  "Run the `:system-prompt` hooks around `base` and return the assembled
  prompt. `ctx` carries `:sys-prompt`, `:cwd`, `:agents-md`, and `:plugins`;
  `base` turns a (possibly modified) context into the default prompt."
  {:malli/schema [:-> :map [:fn fn?] [:maybe :string]]}
  [ctx base]
  ((compose-middleware :system-prompt (:plugins ctx) base) ctx))

(defn- normalize-messages
  "Interpret a hook return value as a sequence of messages. `nil` means no
  change, a map is one message, and a sequence is the messages themselves. A
  map with `:messages` is also accepted so hooks can return a context map."
  [result]
  (cond
    (nil? result)        nil
    (map? result)        (if (contains? result :messages)
                           (vec (:messages result))
                           [result])
    (sequential? result) (vec result)
    :else                (throw (ex-info "Plugin hook must return nil, a message, or a sequence of messages"
                                         {:value result}))))

(defn user-prompt-pre
  "Run the `:user-prompt-pre` hooks selected by the session's `:plugins` and
  return the messages to persist."
  {:malli/schema [:-> :map [:sequential :map] [:sequential :map]]}
  [session messages]
  (reduce (fn [messages hook]
            (try
              (if-let [updated (normalize-messages
                                ((:fn hook)
                                 (hook-context {:session session
                                                :messages messages}
                                               hook)))]
                updated
                messages)
              (catch Throwable e
                (warn-hook-failure! :user-prompt-pre hook e)
                messages)))
          (vec messages)
          (resolve-hooks :user-prompt-pre (:plugins session))))

(defn user-prompt-post
  "Run the `:user-prompt-post` hooks selected by the session's `:plugins` and
  return any additional messages to append after the user prompt."
  {:malli/schema [:-> :map [:sequential :map] [:sequential :map]]}
  [session messages]
  (reduce (fn [appended hook]
            (try
              (into appended
                    (or (normalize-messages
                         ((:fn hook)
                          (hook-context {:session session
                                         :messages messages}
                                        hook)))
                        []))
              (catch Throwable e
                (warn-hook-failure! :user-prompt-post hook e)
                appended)))
          []
          (resolve-hooks :user-prompt-post (:plugins session))))

(defn agent-message-pre
  "Run the `:agent-message-pre` hooks selected by the session's `:plugins`
  and return the message to persist."
  {:malli/schema [:-> :map :map :map]}
  [session message]
  (reduce (fn [message hook]
            (try
              (if-let [updated ((:fn hook)
                                (hook-context {:session session
                                               :message message}
                                              hook))]
                (if (map? updated)
                  updated
                  (throw (ex-info "Agent-message-pre hook must return a message map or nil"
                                  {:value updated})))
                message)
              (catch Throwable e
                (warn-hook-failure! :agent-message-pre hook e)
                message)))
          message
          (resolve-hooks :agent-message-pre (:plugins session))))

(defn agent-message-post
  "Run the `:agent-message-post` hooks selected by the session's `:plugins`
  and return the event update to send."
  {:malli/schema [:-> [:maybe :map] :map :map :map]}
  [session message update]
  (reduce (fn [update hook]
            (try
              (if-let [updated ((:fn hook)
                                (hook-context {:session session
                                               :message message
                                               :update update}
                                              hook))]
                (if (map? updated)
                  updated
                  (throw (ex-info "Agent-message-post hook must return an update map or nil"
                                  {:value updated})))
                update)
              (catch Throwable e
                (warn-hook-failure! :agent-message-post hook e)
                update)))
          update
          (resolve-hooks :agent-message-post (:plugins session))))

;; ---------------------------------------------------------------------------
;; Jar loading
;; ---------------------------------------------------------------------------

(defn- canonical-path
  "The absolute path of `path`."
  [path]
  (str (.getAbsoluteFile (io/file (str path)))))

(defn load-jar!
  "Return a cached `URLClassLoader` that can see the jar at `path`. The loader
  uses the current context class loader as its parent, so plugin code also
  sees `clai` and its dependencies."
  {:malli/schema [:-> :string [:fn #(instance? URLClassLoader %)]]}
  [path]
  (let [path (canonical-path path)]
    (or (get @jar-loaders path)
        (let [file (io/file path)]
          (when-not (.isFile file)
            (throw (ex-info (str "Plugin jar does not exist: " path)
                            {:path path})))
          (let [parent (.getContextClassLoader (Thread/currentThread))
                loader (URLClassLoader.
                        (into-array URL [(.toURL (.toURI file))])
                        parent)]
            (swap! jar-loaders assoc path loader)
            loader)))))

(defn- require-with-loader!
  "Require namespace `ns-sym` with `loader` installed as Clojure's compiler
  loader and the thread's context class loader."
  [loader ns-sym]
  (let [thread   (Thread/currentThread)
        previous (.getContextClassLoader thread)]
    (try
      (.setContextClassLoader thread loader)
      ;; `clojure.lang.Compiler/LOADER` is a dynamic Var without a name, so
      ;; `binding` cannot address it; push the thread binding directly.
      (clojure.lang.Var/pushThreadBindings {clojure.lang.Compiler/LOADER loader})
      (try
        (require ns-sym)
        (finally
          (clojure.lang.Var/popThreadBindings)))
      (finally
        (.setContextClassLoader thread previous)))))

(defn- plugin-descriptor
  "Read and validate the `plugin` Var in namespace `ns-sym`, calling it with
  `config` when it is a function."
  [ns-sym config]
  (let [entry-ns (or (find-ns ns-sym)
                     (throw (ex-info (str "Plugin entry namespace not loaded: "
                                          ns-sym)
                                     {:namespace ns-sym})))
        plugin-var (ns-resolve entry-ns 'plugin)]
    (when-not plugin-var
      (throw (ex-info (str "Plugin entry namespace " ns-sym
                           " does not define a `plugin` Var")
                      {:namespace ns-sym})))
    (let [value      (var-get plugin-var)
          descriptor (cond
                       (map? value) value
                       (ifn? value) (value config)
                       :else
                       (throw (ex-info (str "The `plugin` Var in " ns-sym
                                            " must be a map or a function")
                                       {:namespace ns-sym
                                        :value     value})))]
      (postgres/validate! PluginDescriptor descriptor "load-plugin"))))

(defn load-plugin!
  "Load one plugin row: add its jar to the class path, require its entry
  namespace, read its `plugin` descriptor, and register its hooks. Returns a
  description of the loaded plugin."
  {:malli/schema [:-> #'Plugin :map]}
  [plugin]
  (let [loader     (load-jar! (:jarPath plugin))
        ns-sym     (symbol (:entryNamespace plugin))
        _          (require-with-loader! loader ns-sym)
        descriptor (plugin-descriptor ns-sym (:config plugin))]
    (register! plugin descriptor)
    (tel/log! {:level :info
               :id    ::plugin-loaded
               :data  {:name      (:name plugin)
                       :jar       (:jarPath plugin)
                       :namespace (:entryNamespace plugin)}}
              "Loaded plugin")
    {:name      (:name plugin)
     :jarPath   (:jarPath plugin)
     :namespace (:entryNamespace plugin)
     :hooks     (vec (sort (keys (:hooks descriptor))))}))

(defn load-configured!
  "Replace the registered plugins with the enabled plugins stored in
  PostgreSQL and return a [[LoadResult]].

  A plugin that cannot be loaded (a missing jar, a bad namespace, an invalid
  descriptor, ...) is logged and reported in `:failed`; the remaining plugins
  still load. If the database itself cannot be read, the current registry is
  left untouched and `:error` describes the failure."
  {:malli/schema [:-> [:maybe #'postgres/client-schema] #'LoadResult]}
  [client]
  (if-not client
    {:loaded [] :failed []}
    (try
      (let [plugins (store/list-enabled client)]
        (clear!)
        (reduce (fn [result plugin]
                  (try
                    (load-plugin! plugin)
                    (update result :loaded conj (:name plugin))
                    (catch Throwable e
                      (tel/log! {:level :warn
                                 :id    ::plugin-load-failed
                                 :data  {:name  (:name plugin)
                                         :jar   (:jarPath plugin)
                                         :error (or (ex-message e)
                                                    (str (class e)))}}
                                "Failed to load plugin")
                      (update result :failed conj (:name plugin)))))
                {:loaded [] :failed []}
                plugins))
      (catch Throwable e
        (tel/log! {:level :warn
                   :id    ::plugins-unavailable
                   :data  {:error (or (ex-message e) (str (class e)))}}
                  "Could not load plugins from PostgreSQL")
        {:loaded [] :failed [] :error (or (ex-message e) (str (class e)))}))))
