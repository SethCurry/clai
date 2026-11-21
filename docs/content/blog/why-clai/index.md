---
title: "Why clai Runs the Agent in Your Own JVM"
description: "Most agent harnesses put the model behind a process boundary. clai removes it, and that one decision changes how the harness is built, extended, and debugged."
summary: "One runtime for the agent and the developer."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 50
categories: []
tags: []
contributors: []
pinned: false
homepage: false
params:
  seo:
    title: "" # custom title (optional)
    description: "" # custom description (recommended)
    canonical: "" # custom canonical URL (optional)
    robots: "" # custom robot tags (optional)
---

Most agent harnesses hand the model a fixed set of text tools and treat the
language runtime as something on the other side of a process boundary. clai
collapses that boundary. The agent's most powerful tool, `eval_clojure`,
evaluates code in the harness's own running Clojure process &mdash; the same
process that serves the web UI, streams events, and holds your REPL.

## What that buys

The agent can read its own source, redefine a function, reload a namespace, and
keep going. So can you, in the same process, at the same time. A fix you make
in the REPL is live for the agent's next tool call: no restart, no build step.

That makes extending the harness a conversation rather than a release. A new
LLM provider is a `defrecord` extending a protocol, a `require ... :reload`,
and a test message. The same move adds a tool (`assoc` into a registry), a
plugin hook, or a shell helper.

## Why the pieces are shaped this way

Once the runtime is shared, several other decisions follow naturally:

- **Tools are Clojure values.** A tool is `{:id :handler :spec}`: an
  LLM-facing JSON schema plus a plain function. Registries are immutable maps
  owned per agent, so two agents in one process can offer different tool sets.
  There is no bespoke extension DSL, because the language already is one.
- **Session namespaces keep work separate.** Every session gets
  `sessions.<id>`, seeded from `user`, with its own `*cwd*` and
  `current-session`. Two sessions do not fight over a directory or a helper
  Var, and a session's definitions survive for the life of the process.
- **Sessions are an append-only event log.** Every prompt, thought, message,
  and tool call is one typed row in PostgreSQL. History, resume, compaction,
  and the usage dashboard are reads over that log. Nothing is reconstructed
  from in-memory state.
- **Providers are a protocol.** Ollama and OpenRouter are two implementations
  of one small interface. A provider that cannot do something says so through
  its capability map instead of pretending.
- **Contracts are checked at runtime.** Every schema'd function carries a
  malli schema, and instrumentation is loaded while the tests run. In a system
  where the agent rewrites live code, runtime contracts are the safety net a
  compiler would otherwise be.

## The trade-off

The same property that makes clai powerful makes it unsafe by default. An
agent that can evaluate arbitrary Clojure in your REPL can also run arbitrary
shell commands and write any file your JVM can write. There is no sandbox, and
nothing in the repository is a security boundary between you and the model.
Run it in a container or a VM you do not mind losing.

That is a deliberate trade. clai is a personal tool for one developer on a
trusted machine. It is not a hosted product, and the docs do not pretend
otherwise.

## Where to look next

- [Getting Started](/docs/guides/getting-started/) for the quick start and the
  safety rules.
- [Using the REPL](/docs/guides/using-the-repl/) for the part that makes clai
  different.
- [Architecture](/docs/guides/architecture/) for the loop, storage, and auth.
- [The source](https://github.com/SethCurry/clai) itself &mdash; most of it was
  written by clai, running in this harness.
