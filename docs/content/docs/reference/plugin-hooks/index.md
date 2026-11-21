---
title: "Plugin Hooks"
description: "Complete reference for clai plugin descriptors, hook priority and ordering, the context each hook receives, and the values a hook may return."
summary: "The middleware contract behind plugin jars."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 5
toc: true
---

A plugin jar contains one entry namespace with a `plugin` Var. The Var is
either a descriptor map or a function of the plugin's JSON configuration that
returns one.

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

```clojure
(ns example.plugin)

(defn plugin [{:keys [suffix]}]
  {:hooks
   {:system-prompt
    (fn [ctx next]
      (str (next ctx) "\n\n" suffix))}})
```

## Ordering

Hooks run in this order:

1. Lower `:priority` values first (a plugin row's `priority`).
2. Then by plugin name.
3. Then in the order they appear in the plugin's `:hooks` map.

Every hook receives a `:plugin` key containing `{:name ... :config ...}`.

## Hook contracts

| Hook                  | Context                                                        | Return                                                                                       |
| --------------------- | -------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `:namespace-builder`  | `{:opts ... :plugin ...}` plus `next`                           | A namespace. Call `(next ctx)` for the default builder.                                      |
| `:system-prompt`      | `{:sys-prompt ... :cwd ... :agents-md ... :plugin ...}` plus `next` | The final system-prompt string. Call `(next ctx)` for the default prompt.                 |
| `:user-prompt-pre`    | `{:session ... :messages [...] :plugin ...}`                    | Replacement message(s), or nil to keep the original.                                         |
| `:user-prompt-post`   | `{:session ... :messages [...] :plugin ...}`                    | Message(s) to append, or nil.                                                                |
| `:agent-message-pre`  | `{:session ... :message ... :plugin ...}`                       | A replacement message map, or nil.                                                           |
| `:agent-message-post` | `{:session ... :message ... :update ... :plugin ...}`           | A replacement update map, or nil.                                                            |

A message hook may return a single message map or a sequence of message maps.
A `:user-prompt-*` hook may also return a map with a `:messages` key. Hook
exceptions are logged and ignored, so a broken plugin does not take down a
session.

## Adding Vars to session namespaces

`:namespace-builder` is middleware around
`clai.repl/namespace-builder`. Call `next` to get the default namespace, then
intern into it:

```clojure
(defn plugin [_config]
  {:hooks
   {:namespace-builder
    (fn [ctx next]
      (let [ns (next ctx)]
        (intern ns 'deploy! (fn [] (println "deploying")))
        ns))}}})
```

Replacing the builder outright is allowed: return a different namespace
without calling `next`.

## System prompts

`:system-prompt` wraps the assembled prompt. The context carries the file's
`:sys-prompt`, the session `:cwd`, and the contents of an `AGENTS.md` in that
directory as `:agents-md`. Call `(next ctx)` to get the default result, then
return the string you want the model to see.

## Selecting plugins

The `plugin` table stores each plugin's name, jar path, entry namespace, JSON
configuration, priority, and enabled flag. Which plugins run for a session is
decided by:

1. The session's stored agent configuration `plugins` column.
2. The config file's `:plugins` key.
3. Every enabled plugin.

`NULL`/omitted means every registered plugin, `[]` means none, and a vector of
names or database ids means exactly those. Only hooks from active plugins are
called.

## Loading

`clai.plugins/load-configured!` loads the enabled rows at startup and on
reload. The loader caches one `URLClassLoader` per jar path and rebinds
Clojure's compiler loader while requiring the entry namespace, so the plugin
can require its own namespaces from the jar as well as clai namespaces from the
parent class path.

Configuration changes apply on reload. Replacing code inside a jar requires
restarting the JVM, because Clojure namespaces are not unloaded.

For manual experimentation:

```clojure
(plugins/register! {:name "manual" :priority 50 :config {}}
                   {:hooks {:system-prompt (fn [_ctx next] (next {}))}})
```

The guide with worked examples is [Writing plugins](/docs/guides/writing-plugins/).
