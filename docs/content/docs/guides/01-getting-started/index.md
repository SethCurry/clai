---
title: "Getting Started"
description: "Install clai, start a local model or provider, open your first session from the REPL, and learn the safety rules before letting an agent run code."
summary: "Get a local PostgreSQL, a config file, and your first prompt running."
date: 2023-09-07T16:13:18+02:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 1
toc: true
---

## What is clai?

clai is an AI agent harness written in Clojure, built so that the agent and
the developer work in the same live JVM. It is a personal tool rather than a
product: opinionated, changing most days, and with no compatibility promise.

The defining idea is that the agent's most powerful tool, `eval_clojure`,
evaluates code in the harness's own running Clojure process &mdash; the same
process that serves the web UI, streams events, and holds your REPL. The agent
can read its source, redefine a function, and reload a namespace. So can you,
at the same time. Clojure is the shared language between the human and the
agent, not just the implementation language.

## How it fits together

```text
clai (one JVM)
├── clai.daemon.http ── the React UI (/ws, /api) and the JSON API
│   └── nREPL bridge ── the REPL panel and `clai shell` evaluate through it
├── clai.agent.core ── the chat loop, sessions, tool calls
│   ├── providers ── Ollama, OpenRouter
│   ├── tools/builtin ── files, shell, notes, lsp, sub-agents
│   │   └── eval_clojure ── evaluates in this same JVM
│   └── postgres ── sessions, event log, notes, plugins, stats
└── clai.repl ── new!, prompt!, resume!: the agent driven from a REPL
```

A prompt enters through the REPL, `clai.repl`, or the WebSocket. The agent
loop sends the session history to the provider that serves the selected model,
runs any tool calls the model asks for, appends the results, and repeats until
the model replies in text or the tool-round cap is reached. Every step is
streamed as an event and appended to the session log in PostgreSQL.

Sessions can be driven from two places without changing their storage:

- **The REPL** &mdash; `lein repl` starts in `user`, with the `clai.repl`
  helpers referred. `new!`, `prompt!`, and `resume!` talk to the agent
  directly.
- **The web UI** &mdash; `clai web` (or `lein run web`) serves a React app, a
  JSON API, and a WebSocket on port 8080.

## Safety first

**clai is not sandboxed.** The agent can run arbitrary shell commands, write
any file the JVM can write, and evaluate any Clojure form in the harness's own
process. That is the feature, and it is also the risk.

- Run it in a container or a VM you do not mind losing.
- The nREPL server binds to `127.0.0.1`. An nREPL client can execute arbitrary
  code in that JVM, so do not expose the port.
- The web daemon is authenticated, but binds to `0.0.0.0` by default. Set
  `:host` to `127.0.0.1` on `clai.daemon.http/start-server!` if you want it
  local-only.
- Change the seeded `admin` / `admin` account before the daemon is reachable
  by anything but you.
- A PostgreSQL dump is sensitive: it holds everything you and the model said
  to each other.

## Prerequisites

- A recent JDK (21 or newer; the project is developed on 25).
- [Leiningen](https://leiningen.org).
- Docker, for the development PostgreSQL.
- Node.js and [Task](https://taskfile.dev) if you want the web UI.
- An Ollama server (local or hosted) and/or an OpenRouter API key.

## Quick start

```sh
# 1. Local PostgreSQL for sessions (localhost:5432/clai, user clai, secret)
docker compose up -d

# 2. Optional: point clai at a model
cp config.example.edn ./clai.edn     # then edit :model, :provider, keys

# 3a. Web UI + API + nREPL on :8080
task web

# 3b. …or the REPL, which drives the same agent in-process
lein repl
```

The Docker Compose database already matches the built-in connection defaults,
so a fresh checkout needs no configuration to reach it. `config.example.edn`
is commented key by key; configuration is resolved from the first file that
exists of `$CLAI_CONFIG`, `./clai.edn`, and `~/.config/clai/config.edn`.

The web UI opens on a sign-in form. A fresh database is seeded with
`admin / admin`; change it before exposing the daemon. See
[Configuring models and providers](/docs/guides/configuring-models/) for the
full configuration story.

## Your first session

From the REPL:

```clojure
(new!)                                   ; new session, REPL moves into sessions.<id>
;; => {:sessionId "12" :cwd "/Users/me/code/clai" ...}

(prompt! "How does compaction decide what to keep?")
;; clai> Compaction keeps the system prompt, the summary, the newest
;;       messages and the turn in progress...
;;   [tool] 418 completed
;;      ...
;;   [title] Compaction rules

(history!)                               ; reprint the conversation so far
(resume! "9")                            ; jump back into an old session
```

From the browser:

```text
http://127.0.0.1:8080
```

Sign in, start a session, choose a working directory, and type a prompt. The
REPL panel in the same window evaluates Clojure in the daemon's JVM, and the
**Namespaces** explorer shows the live runtime the agent is editing.

## Next steps

- [Using the REPL](/docs/guides/using-the-repl/) &mdash; the helpers, session
  namespaces, and shell integrations.
- [The web UI](/docs/guides/the-web-ui/) &mdash; sessions, explorer, agent
  configs, notes, plugins, and stats.
- [Configuring models and providers](/docs/guides/configuring-models/) &mdash;
  Ollama, OpenRouter, chat options, compaction, and `AGENTS.md`.
- [Tools and sub-agents](/docs/guides/tools-and-subagents/) &mdash; what the
  agent can do and how per-agent tool registries work.
- [Writing plugins](/docs/guides/writing-plugins/) &mdash; change session
  namespaces and system prompts without forking clai.
