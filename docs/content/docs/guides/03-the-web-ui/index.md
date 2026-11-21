---
title: "The Web UI"
description: "Build and run the clai web UI, then use its session console, REPL panel, namespace explorer, agent configs, notes, plugins, and usage dashboard."
summary: "A browser in front of the same JVM the agent is editing."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 3
toc: true
---

`task web` builds the React app in `web/` and serves it from the daemon on
port 8080, together with the JSON API, the WebSocket, and an nREPL server:

```sh
task web              # build the UI, then run the daemon
lein run web          # daemon only, serving the existing web/dist
lein run web 9000     # daemon on :9000
```

The daemon prints the two ports it uses:

```text
nREPL server listening on port 45123
Web UI listening on http://127.0.0.1:8080
```

A fresh database is seeded with `admin / admin`. Change it before the daemon
is reachable by anything but you; the [architecture guide](/docs/guides/architecture/#authentication)
has the details.

## Sign in

The UI starts on a sign-in form. Only the login page's own three calls are
open; every other `/api/` route and the `/ws` upgrade answers `401` without a
valid session. Non-browser clients can send `Authorization: Bearer <token>`
instead of a cookie.

## Sessions console

The page at `/` is the sessions console:

- **Sidebar** &mdash; past conversations with their working directory and
  generated title. New sessions get their title from the first prompt.
- **Chat timeline** &mdash; streamed model text, thoughts, tool calls and
  their output, and Markdown replies. Tool calls arrive in fragments with the
  same `id`, and the UI merges them into one card until the status becomes
  `completed` or `failed`.
- **Composer** &mdash; typing `./` opens a file and folder completion popup,
  scoped to the session's working directory.
- **Manage** &mdash; creates, edits, and deletes stored agent configurations.

Chat streaming and Clojure evaluation both use one WebSocket. Starting a
session, loading one, prompting, and evaluating are messages on that socket;
everything else uses the JSON API. The [HTTP API reference](/docs/reference/http-api/)
documents the protocol.

## REPL panel

The REPL panel evaluates Clojure in the daemon's own JVM through nREPL. It
defaults to the selected session's `sessions.<id>` namespace.

It is the same runtime the agent's `eval_clojure` runs in, just with a browser
in front of it. A function you redefine in the panel is live for the agent's
next tool call, and a `def` the agent makes in `clai.home.core` is one
`in-ns` away.

## Explorer panel

Two read-only trees sit beside the REPL:

- **Files** browses the session's working directory lazily and previews one
  file's text at a time. Both the listing and the preview refuse paths that
  escape the session's `cwd`.
- **Namespaces** browses the live Clojure runtime: loaded namespaces, their
  public Vars, and each Var's docstring, argument lists, malli schema, source,
  and value. It is a window onto the process the agent is editing.

## Agent configurations

A named configuration can select a model, system prompt, tool subset, plugin
set, token budget, and chat options. Sessions choose a configuration from the
sidebar, and the stored configuration overrides the file default for that
session.

A `null` tool list means the agent's default registry; an empty array disables
tools for sessions that select the configuration. A `null` plugin list means
every enabled plugin; an empty array disables plugins.

## Other pages

- **Git repos** &mdash; a registry of the repositories and worktrees you work
  in (name, url, branch, directory), so a checkout's path is recorded once.
- **Notes** &mdash; titled Markdown notes in PostgreSQL, with BM25-ranked
  search, `[[Note title]]` links, and backlinks.
- **Plugins** &mdash; installed plugin jars, the hooks each one registered,
  and create/edit/delete controls. Every write reloads the daemon's plugin
  registry, so a change applies to the next prompt without a restart.
- **Stats** &mdash; KPI cards and charts read from the session event log:
  calls per tool, average output per tool, tool calls per session, and the
  share of calls each tool takes.

## UI development

```sh
lein run web              # daemon on :8080
npm run dev --prefix web  # Vite on :5173, proxying /api and /ws to the daemon
npm test --prefix web     # the UI's node:test suite for its pure helpers
```

`CLAI_DAEMON_URL` repoints the Vite proxy; `CLAI_WEB_ROOT` repoints the daemon
at a different build directory. `task web:build` builds the React app without
starting the daemon, and `task web:install` installs its dependencies.
