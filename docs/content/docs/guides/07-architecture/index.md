---
title: "Architecture"
description: "How clai fits one JVM, the agent loop, PostgreSQL session log, provider protocol, plugin hooks, and authentication into a single-process harness."
summary: "One process, several views, and a session log as the source of truth."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 7
toc: true
---

## One JVM

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

The daemon, the agent, the nREPL server, and your REPL are one process. That
is the design, not an implementation detail. It is why `eval_clojure` can
redefine a function that the web UI is about to call, and why a namespace
loaded in your REPL is visible to the agent's next tool call.

## One turn

A prompt enters through the REPL (`clai.repl`), the WebSocket, or the JSON API.
The agent loop then:

1. Appends the prompt to the session's event log.
2. Compacts the history if it is over budget.
3. Sends the history and the agent's tool definitions to the provider that
   serves the session's model.
4. Executes any tool calls the model asks for against that agent's registry.
5. Appends the tool results and repeats.

It stops when the model replies in text or when `:max-tool-rounds` is reached.
Every step is streamed as an event and appended to the log, so the REPL, the
web UI, and the database see the same vocabulary.

## Sessions are databases

Sessions live in PostgreSQL. `schema/schema.sql` is the canonical DDL, and the
daemon applies it on first use.

```text
agent_session        id, title, working_directory, created_at
agent_session_event  append-only, one typed JSONB payload per row
```

The `agent_session_event_type_t` enum &mdash; `user_prompt`, `agent_message`,
`agent_thought`, `tool_call`, `clojure_form`, `compaction` &mdash; is also the
vocabulary the agent loop streams to the UI and the REPL. A value moves between
the chat loop and the log without a second representation.

Because a session is rows, mutations are transactional and every process
sharing the database sees the same conversations. Resume after a crash, the
**Stats** page, and compaction that never rewrites history are all reads over
that log.

Other tables in the same schema hold agent configurations, plugins, git
repositories and worktrees, notes, users, and authentication tokens.

## Providers are a protocol

Ollama and OpenRouter are two implementations of a seven-method interface:

```clojure
(defprotocol Provider
  (-provider-id       [client])
  (-capabilities      [client])
  (-chat-request      [client spec])
  (-chat              [client req])
  (-assistant-message [client resp])
  (-tools             [client tool-specs])
  (-token-usage       [client resp]))
```

The dashed methods are the extension points. The public functions check that a
client really implements the protocol and translate the provider's response
into the normalized shapes the rest of the system uses. A provider that cannot
do something says so through its capability map instead of pretending.

Adding a provider is a conversation rather than a release: write a `defrecord`
extending the protocol, `require ... :reload`, and send it a message. The
harness is editable while it runs.

## Tools are values

A tool is a map of `{:id :handler :spec}`. Registries are immutable maps owned
per agent, so a sub-agent can have a narrower tool set without affecting its
parent. See [Tools and sub-agents](/docs/guides/tools-and-subagents/).

## Contracts at runtime

Every schema'd function carries a `:malli/schema`, and requiring
`clai.instrument` instruments all of them, so argument and return contracts
are enforced while the system runs. In a system where the agent rewrites live
code, runtime contracts are the safety net a compiler would otherwise be.
The test suite loads instrumentation, so schema drift fails a test rather than
a user.

## Authentication

The JSON API and the WebSocket are closed by default; only the login page's
own three calls are open. The UI starts on a sign-in form, and every other
`/api/` route plus the `/ws` upgrade answers `401` without a valid session.

- `POST /api/auth/login` returns the user and an `HttpOnly` session cookie.
- `GET /api/auth/me` reports whether the request carries a live session.
- `POST /api/auth/logout` revokes it.
- Non-browser clients can send `Authorization: Bearer <token>` instead.

Accounts live in `app_user`. Passwords are salted PBKDF2 digests
(`clai.daemon.auth/hash-password`) and the plaintext is never stored. Login
sessions last seven days and are stored as SHA-256 digests in `auth_token`, so
a database dump cannot be replayed. The WebSocket also checks `Origin`,
because browsers do not enforce same-origin for WebSockets.

One username and password is not a real authorization boundary between people,
which is why the daemon is meant for one user on a trusted network, and why
the answer to "can several people share an instance?" is "no".

## Logging

One Telemere signal per interesting event, with ids and data maps, so a session
can be followed through the log instead of guessed at. `lein repl` redirects
those signals to `target/logs/clai.log` so they do not interleave with the
conversation.

## Reading the code

Four files carry most of the ideas:

| File                                       | Role                                                             |
| ------------------------------------------ | ---------------------------------------------------------------- |
| `src/clai/repl.clj`                        | Driving a session from the REPL.                                 |
| `src/clai/agent/core.clj`                  | The agent loop and everything the daemon calls.                  |
| `src/clai/agent/tools/registry.clj`        | Tools as values and per-agent registries.                        |
| `src/clai/agent/providers/protocol.clj`    | The provider seam.                                               |

The [namespace reference](/docs/reference/namespaces/) maps the rest of the
tree.
