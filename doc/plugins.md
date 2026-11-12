# Plugins

`clai` can load plugins from Clojure jars. A plugin can change how session
namespaces are created, how system prompts are assembled, and what happens
before and after prompts and agent messages.

Installed plugins are stored in the `plugin` table in PostgreSQL. The daemon
loads enabled plugins at startup, and `clai.repl/connect!` loads them when it
connects to an agent that has a PostgreSQL client.

## Writing a plugin

A plugin jar contains one entry namespace that defines a `plugin` Var. The Var
may be a descriptor map:

```clojure
(ns example.plugin)

(def plugin
  {:description "Adds a house style to every system prompt"
   :version "1.0.0"
   :hooks
   {:system-prompt
    (fn [ctx next]
      (str (next ctx) "\n\nAlways write tests before implementation."))}})
```

or a function that receives the plugin's JSON configuration and returns the
descriptor:

```clojure
(ns example.plugin)

(defn plugin [{:keys [suffix]}]
  {:hooks
   {:system-prompt
    (fn [ctx next]
      (str (next ctx) "\n\n" suffix))}})
```

The plugin's dependencies must either be on `clai`'s classpath already or be
shaded into the plugin jar; the plugin loader adds the jar itself to the class
path, not other jars nested inside it.

## Hooks

Hooks run in priority order: lower `priority` values first, then by plugin
name, then in the order they appear in the plugin's `:hooks` map. Every hook
receives a `:plugin` key containing `{:name ... :config ...}`.

| Hook | Contract |
| ---- | -------- |
| `:namespace-builder` | Middleware around `clai.repl/namespace-builder`. Receives `{:opts ... :plugin ...}` and `next`; returns a namespace. Call `(next ctx)` to continue. |
| `:system-prompt` | Middleware around system-prompt assembly. `ctx` has `:sys-prompt`, `:cwd`, and `:agents-md`; call `(next ctx)` for the default prompt, then return the final string. |
| `:user-prompt-pre` | Runs before user messages are persisted. Receives `{:session ... :messages [...]}`. Return replacement message(s), or nil to keep them unchanged. |
| `:user-prompt-post` | Runs after user messages are persisted and before the model call. Receives the same context. Return message(s) to append, or nil. |
| `:agent-message-pre` | Runs before the final assistant message is persisted. Receives `{:session ... :message ...}`. Return a replacement message map, or nil. |
| `:agent-message-post` | Runs after the assistant message is persisted and before its event is sent. Receives `{:session ... :message ... :update ...}`. Return a replacement update map, or nil. |

A message hook may return a single message map or a sequence of message maps.
A `:user-prompt-*` hook may also return a map with a `:messages` key. Hook
exceptions are logged and ignored, so a broken plugin does not take down a
session.

`:namespace-builder` hooks are useful for adding functions to session
namespaces. For example:

```clojure
(defn plugin [_config]
  {:hooks
   {:namespace-builder
    (fn [ctx next]
      (let [ns (next ctx)]
        (intern ns 'deploy! (fn [] (println "deploying")))
        ns))}})
```

## Installing a plugin

Store an enabled row in PostgreSQL:

```clojure
(require '[clai.daemon.postgres :as pg]
         '[clai.plugins.store :as store])

(def client (pg/make-client))

(store/create-plugin
 client
 {:name            "example"
  :jar-path        "/opt/clai/plugins/example.jar"
  :entry-namespace "example.plugin"
  :config          {:suffix "Be concise."}
  :priority        100
  :enabled         true})
```

The `plugin` table is declared in `schema/schema.sql` and is created by
`clai.daemon.postgres/ensure-schema!`. `store/list-plugins`,
`store/list-enabled`, `store/select-plugin`, `store/load-plugin`,
`store/update-plugin`, and `store/delete-plugin` provide the rest of the CRUD
API.

Plugin rows are loaded by `clai.plugins/load-configured!`. To reload after
changing a row:

```clojure
(require '[clai.plugins :as plugins]
         '[clai.repl :as repl])

(plugins/load-configured! (:postgres (repl/connect!)))
```

The loader caches one `URLClassLoader` per jar path and rebinds Clojure's
compiler loader while requiring the entry namespace, so the plugin can require
its own namespaces from the jar as well as `clai` namespaces from the parent
class path. Configuration changes are applied on reload; replacing the code in
a jar requires restarting the JVM, because Clojure namespaces are not unloaded.

Plugins can also be registered directly for experimentation:

```clojure
(plugins/register! {:name "manual" :priority 50 :config {}}
                   {:hooks {:system-prompt (fn [_ctx next] (next {}))}})
```
