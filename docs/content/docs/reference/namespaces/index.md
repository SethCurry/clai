---
title: "Namespaces"
description: "A map of the clai source tree: the REPL client, agent loop, providers, tools, daemon, storage, plugins, events, and shell helpers."
summary: "Where things live in the source tree."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 6
toc: true
---

## Landmarks

| Namespace                            | Role                                                                            |
| ------------------------------------ | ------------------------------------------------------------------------------- |
| `clai.core`                          | CLI: `nrepl`, `shell`, `web`.                                                   |
| `clai.repl`                          | The in-process REPL client: `new!`, `prompt!`, `resume!`, session namespaces.   |
| `clai.agent.core`                    | The agent: configuration, sessions, the chat loop, everything the daemon calls. |
| `clai.agent.providers` / `.protocol` | Provider resolution and the provider interface.                                 |
| `clai.agent.tools.*`                 | Tool schemas, immutable registries, invocation, and the built-ins.              |
| `clai.events.schema`                 | The event vocabulary shared by the loop, the log, the REPL and the UI.          |
| `clai.events.compaction`             | Compaction summaries and how a client replays them.                             |
| `clai.daemon.postgres.*`             | Session, note, plugin, config, git and stats storage.                           |
| `clai.daemon.http` / `.nrepl`        | The web daemon, the JSON API and the nREPL bridge.                              |
| `clai.home.core`                     | The namespace `eval_clojure` runs in &mdash; the agent's REPL.                  |
| `clai.shell.*`                       | REPL-friendly `git`, `docker`, `task`, browser and process helpers.             |
| `clai.plugins`                       | Jar plugin loading and the middleware hooks.                                    |
| `clai.instrument`                    | Malli collection and runtime instrumentation.                                   |
| `clai.config`                        | Config discovery, resolution, and provider/storage client construction.          |
| `clai.lsp`                           | `clojure-lsp` process management behind the LSP tools.                          |

Four files carry most of the design: `src/clai/repl.clj` (driving a session),
`src/clai/agent/core.clj` (the loop), `src/clai/agent/tools/registry.clj`
(tools as values), and `src/clai/agent/providers/protocol.clj` (the provider
seam).

## `clai.repl`

| Var / function            | Purpose                                                                 |
| ------------------------- | ----------------------------------------------------------------------- |
| `current-session`         | The session selected in the calling namespace.                          |
| `current-agent`           | The agent the REPL is connected to.                                     |
| `current-session-id`      | The id of the selected session, or nil.                                 |
| `connected?`              | Whether an agent connection exists.                                     |
| `connect!` / `disconnect!`| Open or close the in-process connection.                                |
| `new!`                    | Start a session and move the REPL into `sessions.<id>`.                 |
| `resume!`                 | Resume a session by id (or the current/most recent one) and replay it.  |
| `history!`                | Reprint a session's history.                                            |
| `prompt!`                 | Send a prompt and stream the reply, tools, title, and compaction output.|
| `session-ns`              | The namespace symbol for a session id.                                  |
| `namespace-builder`       | Create a session namespace through the plugin hooks.                    |
| `$`                       | Run a shell command through `sh -c`.                                    |

## `clai.home.core`

This is the agent's REPL. It is an ordinary namespace, preloaded with:

| Function         | Reply                                                              |
| ---------------- | ------------------------------------------------------------------- |
| `help`           | A cheat sheet of what the namespace offers.                        |
| `tools`          | Every tool the calling agent can use, sorted by id.                |
| `tool`           | One tool's full JSON schema, or nil.                               |
| `context`        | The live `eval_clojure` context (cwd, session id, provider, model). |
| `session`        | The stored session without its conversation.                       |
| `history`        | The stored conversation, oldest first.                             |
| `active-plugins` | The plugins loaded in this process.                                |
| `vars`           | A namespace's public Vars.                                         |
| `var-detail`     | One Var's metadata, schema, source, and value.                     |

Also referred: `doc`, `source`, `apropos`, `dir`, `find-doc`, and `pprint`.
Aliases cover `eval`, `registry`, `sessions`, `clj`, `plugins`, `json`,
`shell`, `io`, and `str`.

## `clai.shell.*`

| Namespace      | Examples                                                                 |
| -------------- | ------------------------------------------------------------------------ |
| `clai.shell.git`    | `status`, `diff`, `log`, `commit`, `checkout`, `branches`, `blame`   |
| `clai.shell.docker` | `ps`, `containers`, `inspect`, `logs`, `exec`, `compose-up`, `compose-down` |
| `clai.shell.task`   | `list`, `tasks`, `run`, `status`, `dry-run`                          |
| `clai.shell.cli`    | `run`, `run!`, `check!`, `pwd`, `cd`, `out`, `echo-result!`          |
| `clai.shell.browser`| `browse`, `browse!`, `command`                                       |
| `clai.shell.help`   | `help`, `help!`, `help-text`                                         |

Each returns a result map (`{:command :exit :out :err :success? ...}`) rather
than throwing; the `!` variants throw.

## Providers

| Namespace                              | Role                                                     |
| -------------------------------------- | -------------------------------------------------------- |
| `clai.agent.providers`                 | Model name &rarr; provider client; config &rarr; clients.|
| `clai.agent.providers.protocol`        | The `Provider` protocol and the neutral pipeline.        |
| `clai.agent.providers.ollama`          | Ollama client (local and `ollama.com` cloud).            |
| `clai.agent.providers.openrouter`      | OpenRouter client.                                       |
| `clai.agent.providers.messages`        | Normalized assistant messages and tool calls.            |
| `clai.agent.providers.http`            | clj-http/JSON plumbing and NDJSON streaming.             |

`protocol/respond` returns `{:message :usage :response}`; `protocol/complete`
returns just the assistant message. Both are what the agent loop, titles, and
compaction use.

## Storage

| Namespace                          | Tables / role                                      |
| ---------------------------------- | -------------------------------------------------- |
| `clai.daemon.postgres`             | Connection handling and `ensure-schema!`.          |
| `clai.daemon.postgres.sessions`    | `agent_session`, `agent_session_event`.            |
| `clai.daemon.postgres.configs`     | `agent_config`.                                    |
| `clai.daemon.postgres.plugins`     | `plugin`.                                          |
| `clai.daemon.postgres.git`         | `git_repo`, `git_worktree`.                        |
| `clai.daemon.postgres.notes`       | `note`, links, and search.                         |
| `clai.daemon.postgres.stats`       | Usage aggregates over the event log.               |
| `clai.daemon.postgres.users`       | `app_user`, `auth_token`.                          |

`schema/schema.sql` is the canonical DDL. `ensure-schema!` creates what is
missing and never touches what exists.

## Reading order

If you are skimming rather than reading:

1. `src/clai/repl.clj` &mdash; how a session is driven.
2. `src/clai/agent/core.clj` &mdash; the loop and its boundaries.
3. `src/clai/agent/tools/registry.clj` &mdash; why tools are just maps.
4. `src/clai/agent/providers/protocol.clj` &mdash; the provider seam.
5. `doc/plugins.md` and `web/README.md` &mdash; the plugin contract and the
   full web protocol.
