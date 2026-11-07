# Session storage

This document describes how `clai` persists ACP sessions. Sessions are stored
in PostgreSQL by `clai.agent.state` (`src/clai/agent/state.clj`), which is a
thin session-storage facade over `clai.daemon.postgres.sessions`
(`src/clai/daemon/postgres/sessions.clj`). The connection/client code and the
arbitrary-data store live beside it in `clai.daemon.postgres`
(`src/clai/daemon/postgres.clj`).

## Overview

`clai` is stateless between prompts except for one thing: *sessions*. Every
ACP session records its parameters and full message history in PostgreSQL, so
a client can disconnect, restart the agent, and later `session/load` the
session back into place.

There is no on-disk session file and no in-memory registry. The database is the
source of truth, and every process pointed at the same database sees the same
sessions.

## Configuration

The agent builds its storage client from the `:postgres` sub-map of the config
file (see `config.example.edn`). Omitted keys fall back to the
`CLAI_POSTGRES_*` / `PG*` environment variables and then to
`localhost:5432/clai`:

```clojure
{:postgres {:host     "localhost"
            :port     5432
            :database "clai"
            :user     "clai"
            :password "secret"
            :ssl-mode "prefer"}}
```

A full JDBC URL can be supplied as `:url` (or `CLAI_POSTGRES_URL` /
`DATABASE_URL`). The client is built eagerly but connects lazily; the storage
tables are created the first time a session operation runs. To fail fast at
startup instead, call `clai.agent.state/ensure-schema!` (or build the client
with `clai.agent.state/open`, which ensures the schema immediately).

## Tables

`clai.daemon.postgres/ensure-schema!` creates three idempotent tables:

```sql
clai_sessions (
  session_id  text PRIMARY KEY,
  cwd         text NOT NULL,
  model       text,
  params      jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz NOT NULL,
  updated_at  timestamptz NOT NULL
)

clai_session_messages (
  id         bigserial PRIMARY KEY,
  session_id text NOT NULL REFERENCES clai_sessions(session_id) ON DELETE CASCADE,
  message    jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
)

clai_data (
  namespace  text NOT NULL,
  data_key   text NOT NULL,
  value      jsonb NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (namespace, data_key)
)
```

Only the first two tables hold sessions; their queries live in
`clai.daemon.postgres.sessions`. `clai_data` is a separate arbitrary
key/value store exposed by `clai.daemon.postgres`.

## Session shape

A session map looks like this:

```clojure
{:sessionId "3f2b1c8e-1a2b-4c3d-9e4f-5a6b7c8d9e0f"
 :cwd       "/home/me/project"
 :model     "gemma3"
 :params    {:temperature 0.7}
 :createdAt "2025-01-02T03:04:05.678901Z"
 :updatedAt "2025-01-02T03:05:06.789012Z"
 :history   [{:role "system" :content "You are clai."}
             {:role "user" :content "read foo.txt"}
             {:role "assistant"
              :content ""
              :tool_calls [{:id "call_1"
                            :function {:name "read_file"
                                       :arguments {:path "foo.txt"}}}]}
             {:role "tool"
              :content "hello"
              :name "read_file"
              :tool_call_id "call_1"}
             {:role "assistant" :content "It says hello."}]}
```

### Top-level keys

| Key         | Type                     | Presence | Meaning |
|-------------|--------------------------|----------|---------|
| `:sessionId`| non-empty string         | required | The session id; primary key of `clai_sessions`. |
| `:cwd`      | string                   | required | Working directory for tool execution (`clai.agent.loop` hands this to tools as their `:cwd`). |
| `:model`    | string or `nil`          | optional | Model the session chats with. |
| `:params`   | JSON object              | optional | Arbitrary session parameters, stored as `jsonb`. Values must be JSON-serializable. |
| `:createdAt`| ISO-8601 string          | required | Creation time, UTC. |
| `:updatedAt`| ISO-8601 string or `nil` | optional | Refreshed by every mutation. |
| `:history`  | vector of message maps   | optional | The conversation, oldest first. Read from `clai_session_messages`. |

Timestamps are stored as `timestamptz` and rendered as ISO-8601 strings by the
client, truncated to microseconds so they round-trip unchanged.

The authoritative description of the shape is the Malli schema
`clai.agent.state/session`; `clai.agent.state/session-summary` is the same map
minus `:history`. These schemas are enforced at runtime by Malli
instrumentation (see `clai.instrument`).

### Message maps (`:history` entries)

History entries are Ollama chat messages, defined by the schema
`clai.agent.state/message`:

| Key           | Type                          | Presence | Meaning |
|---------------|-------------------------------|----------|---------|
| `:role`       | non-empty string              | required | `"system"`, `"user"`, `"assistant"`, or `"tool"`. |
| `:content`    | string or sequential of maps | optional | Message text. |
| `:thinking`   | string or `nil`               | optional | Chain-of-thought returned by a thinking model. Persisted on the final assistant message so `session/load` can replay it as an `agent_thought_chunk`. |
| `:images`     | sequential of strings         | optional | Base64 images (Ollama convention). Not written by the agent today. |
| `:tool_calls` | sequential of maps            | optional | Tool calls requested by an `assistant` message. |
| `:name`       | string                        | optional | Tool name, on `"tool"` messages. |
| `:tool_call_id` | string                      | optional | Id of the tool call a `"tool"` message answers. |

The history is the *provider* format rather than a private one:
`clai.agent.loop` passes it verbatim as the `:messages` field of an Ollama
`/api/chat` request. Anything in the vector must therefore stay acceptable to
the Ollama chat API.

What the agent writes, in order:

1. `{:role "system" :content <system-prompt>}` — only at session creation,
   and only when the config has a `:system-prompt` or the session's `cwd`
   contains an `AGENTS.md` (whose contents are appended to the configured
   system prompt, or used as the system prompt when none is configured).
2. `{:role "user" :content "..."}` — one per incoming ACP prompt.
3. `{:role "assistant" :content "" :tool_calls [...]}` — the model's own tool
   request, stored verbatim so the next round can see it.
4. `{:role "tool" :content "<result>" :name "read_file" :tool_call_id "..."}`
   — one per executed tool call.
5. `{:role "assistant" :content "<final reply>"}` — once the model stops
   calling tools. When the model returned reasoning, the message also carries
   `:thinking "<chain of thought>"`.

## Writing, concurrency, and durability

* `save-session` replaces a session's metadata and rewrites its history in a
  single transaction.
* `append-history` inserts one `clai_session_messages` row and touches the
  session's `updated_at`, also in a transaction.
* Postgres row locks and transactions replace the per-session in-process locks
  the old file store used. Multiple processes sharing a database can update
  the same session concurrently; concurrent appends are serialized by the
  database rather than lost.
* A failed query is logged (`::query-failed`, from `clai.daemon.postgres`) and
  rethrown as an `ExceptionInfo` naming the operation. Unlike the old file
  store, one bad session cannot corrupt the store; other sessions remain
  readable.

## API surface

All functions live in `clai.agent.state` (and, one level down, in
`clai.daemon.postgres.sessions`) and take a `clai.daemon.postgres/Client` as
their first argument. The agent keeps its client in the `:postgres` field of
its handler record and passes it to `clai.agent.loop` for the reads and writes
along the chat loop.

### Lifecycle

| Function | Persists | Returns |
|----------|----------|---------|
| `create-session` `[client {:cwd :model :params}]` | New `clai_sessions` row; empty history | The state map |
| `register-session` `[client id cwd history]` | Row under a caller-chosen id; `:cwd` defaults to `"."`, `:params {}` | The saved state map |
| `load-session` `[client id]` / `lookup-session` `[client id]` | — | State map, or `nil` if absent |
| `save-session` `[client state]` | Whole session, refreshing `:updatedAt` | The saved state map |
| `delete-session` `[client id]` | Removes the session and its messages | `true` if a row was removed, else `false` |
| `session-exists?` `[client id]` | — | Boolean |

### Reading

| Function | Returns |
|----------|---------|
| `list-sessions` `[client]` | Vector of full state maps, sorted by `:createdAt` ascending |
| `list-session-summaries` `[client]` | Same, without `:history` (for session pickers / ACP `session/list`) |
| `session-history` `[client id]` | `:history` vector, or `[]` if the session is unknown |

### Mutation

All of these return the updated full state map, or `nil` (plus a
`::unknown-session` warning log, emitted by `clai.daemon.postgres.sessions`) if
the session does not exist:

| Function | Effect |
|----------|--------|
| `append-history` `[client id msg]` | Appends one message; returns the *message*, or `nil` if unknown |
| `clear-history` `[client id]` | Deletes all messages |
| `set-param` `[client id k v]` | Sets one `:params` entry |
| `set-params` `[client id m]` | Merges `m` into `:params` |
| `set-model` `[client id model]` | Replaces `:model` |

## Relationship to the ACP session lifecycle

`clai.agent` maps protocol calls onto this store:

| ACP call | Storage effect |
|----------|----------------|
| `session/new` | `create-session` with the client's `cwd` and the configured model; appends a system message combining the configured system prompt with the `cwd`'s `AGENTS.md` (when either exists) |
| `session/prompt` | Refuses unknown sessions; otherwise appends the user message(s), then each assistant/tool message the loop produces |
| `session/load` | `load-session`, persists a changed `cwd` if the client supplies one, then replays `:history` to the client as `session/update` chunks (`user` → `user_message_chunk`, `assistant`/`agent` → `agent_message_chunk`, preceded by `agent_thought_chunk` when the message has `:thinking`; `system` and `tool` messages are internal and not replayed) |
| `session/list` | `list-session-summaries`, optionally filtered by exact `:cwd` match |
| `session/resume` | `load-session`, or `register-session` under the requested id when unknown, so resume degrades to "new" instead of erroring |
| `session/delete` | `delete-session` |
| `session/close` | Nothing — history is deliberately kept so the client can load the session again |

## Working with sessions by hand

Use `psql` (or any PostgreSQL client) against the configured database:

```sql
-- recent sessions
SELECT session_id, cwd, model, created_at, updated_at
  FROM clai_sessions
 ORDER BY created_at DESC
 LIMIT 20;

-- one session's history
SELECT message
  FROM clai_session_messages
 WHERE session_id = '<id>'
 ORDER BY id;

-- forget a session (its messages cascade)
DELETE FROM clai_sessions WHERE session_id = '<id>';
```

Deleting the `clai_sessions` row is equivalent to `session/delete`; closing a
session does not remove it. Truncating a history with
`DELETE FROM clai_session_messages ...` is a supported way to trim context: the
next prompt simply sends the smaller history to the model.

## No legacy state

The previous EDN-file store under `~/.local/clai/sessions/` is no longer read.
`clai` has not been used in production, so no migration path is provided;
existing files can simply be deleted.
