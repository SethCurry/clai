# Plugins

`clai` loads plugins from Clojure jars registered in PostgreSQL. A plugin can
customize session namespaces, system prompts, user prompts, and agent messages.
See [`doc/plugins.md`](../doc/plugins.md) for the full hook contract.

## Building the example plugin

The repository includes a plugin that prepends today's date to every system
prompt:

```sh
lein plugin-jar
# or
task plugin:build
```

The jar is written to:

```text
target/plugin/clai-plugin-todays-date.jar
```

## Installing it

Start PostgreSQL (`docker compose up -d`) and register the plugin from a REPL:

```clojure
(require '[clai.daemon.postgres :as pg]
         '[clai.plugins.store :as store])

(def client (pg/make-client))

(store/create-plugin
 client
 {:name            "todays-date"
  :jar-path        "/absolute/path/to/clai/target/plugin/clai-plugin-todays-date.jar"
  :entry-namespace "clai-plugin-todays-date.plugin"
  :config          {:date-format "EEEE, MMMM d, yyyy"}
  :enabled         true
  :priority        100})
```

Reload the enabled plugins after changing a row:

```clojure
(require '[clai.plugins :as plugins]
         '[clai.repl :as repl])

(plugins/load-configured! (:postgres (repl/connect!)))
```

The `:date-format` setting is optional and defaults to `yyyy-MM-dd`. Omitting
the configuration entirely is fine.

## Choosing which plugins are active

Installed plugins are registered when the daemon starts, but each agent
configuration decides which of them run. In the web UI:

1. Open **Agent config** in the sidebar and choose **Manage**.
2. Create or edit a configuration.
3. Under **Plugins**, choose **All enabled plugins**, **No plugins**, or
   **Selected plugins**.
4. When you pick selected plugins, tick the installed plugins you want, or
   type names in the comma-separated field.
5. Save, then create a session with that configuration selected.

The same setting is available in the CLI and from Clojure:

```clojure
(require '[clai.agent.configs :as configs])
(require '[clai.daemon.postgres :as pg])

(def client (pg/make-client))

(configs/create-config
 client
 {:name     "with-date"
  :model    "gemma3"
  :plugins  ["todays-date"]})
```

Then create a session with that configuration's id (from the web UI, or with
`clai.agent.core/new-session!` and `{:configId id}`). A `nil` plugin list means
every enabled plugin runs, and `[]` disables plugins for sessions that use the
configuration. The config file's `:plugins` key sets the same default for the
whole agent.

## Writing your own

Copy `src/clai_plugin_todays_date/plugin.clj` as a starting point. The entry
namespace must define a `plugin` Var containing a descriptor map, or a function
that accepts the plugin's JSON configuration and returns one. The build alias
in `project.clj` shows how to package a plugin without shipping the rest of
`clai`.
