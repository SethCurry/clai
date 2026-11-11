# Session storage

This document describes how `clai` persists agent sessions. Sessions are
stored in PostgreSQL by `clai.daemon.postgres.sessions`
(`src/clai/daemon/postgres/sessions.clj`). The connection/client code lives
beside it in `clai.daemon.postgres` (`src/clai/daemon/postgres.clj`).

> **Note:** the ACP session compatibility layer is being removed. Session ids
> are now database keys rather than client-chosen ids, and an ACP
> `session/resume` for an unknown id degrades to a new session.

## Overview

`clai` is stateless between prompts except for one thing: *sessions*. Every
session records its working directory and its full conversation in
PostgreSQL, so the agent can restart and later load the conversation back into
place.

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
startup instead, call `clai.daemon.postgres/ensure-schema!` (or build the client
with `clai.daemon.postgres/open`, which ensures the schema immediately).

## Tables

The DDL lives in `schema/schema.sql`; it is the single source of truth, read
from the classpath and applied by `clai.daemon.postgres/ensure-schema!` (and,
in development, by the one-shot `schema` service in `docker-compose.yml`).
Every statement in it is idempotent, so applying it on each process start is
safe.

```sql
agent_session (
  id                 bigserial PRIMARY KEY,
  working_directory  text NOT NULL,        -- absolute path
  created_at         timestamptz DEFAULT now()
)

agent_session_event_type_t                 -- enum
  = 'user_prompt' | 'agent_message' | 'agent_thought'
  | 'tool_call' | 'clojure_form'

agent_session_event (
  id                serial PRIMARY KEY,
  event_type        agent_session_event_type_t NOT NULL,
  content           jsonb NOT NULL,
  agent_session_id  int NOT NULL
                    REFERENCES agent_session(id) ON DELETE CASCADE
)
```

A session is one `agent_session` row. Its conversation is the ordered
`agent_session_event` rows that reference it (ordered by `id`, the insertion
order). Deleting a session deletes its events.

## Session shape

A session map looks like this:

```clojure
{:sessionId "42"
 :cwd       "/home/me/project"
 :createdAt "2025-01-02T03:04:05.678901Z"
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
| `:sessionId`| non-empty string         | required | The `agent_session.id` primary key, rendered as a string. Assigned by the database on insert. |
| `:cwd`      | string                   | required | Working directory for tool execution (`clai.agent.loop` hands this to tools as their `:cwd`). Stored as `working_directory`. |
| `:createdAt`| ISO-8601 string          | optional | Creation time, UTC. |
| `:history`  | vector of message maps   | optional | The conversation, oldest first. Read from `agent_session_event.content`. |

The session no longer persists a model or a free-form params map, and there is
no `updatedAt` column; a session's ordering key is `createdAt`.

The authoritative description of the shape is the Malli schema
`clai.daemon.postgres.sessions/session`;
`clai.daemon.postgres.sessions/session-summary` is the same map
minus `:history`. These schemas are enforced at runtime by Malli
instrumentation (see `clai.instrument`).

Session ids are database keys, so an id that is not a recorded row (including
the UUIDs ACP clients used to mint) is simply an unknown session:
`load-session` returns `nil`, `append-history`/`clear-history`/`save-session`
return `nil`, `delete-session` and `session-exists?` return `false`, and
`session-history` returns `[]`.

### Message maps (`:history` entries)

History entries are Ollama chat messages, defined by the schema
`clai.daemon.postgres.sessions/message`:

| Key           | Type                          | Presence | Meaning |
|---------------|-------------------------------|----------|---------|
| `:role`       | non-empty string              | required | `"system"`, `"user"`, `"assistant"`, or `"tool"`. |
| `:content`    | string or sequential of maps | optional | Message text. |
| `:thinking`   | string or `nil`               | optional | Chain-of-thought returned by a thinking model. Persisted on the final assistant message so it can be replayed as an `agent_thought_chunk`. |
| `:images`     | sequential of strings         | optional | Base64 images (Ollama convention). Not written by the agent today. |
| `:tool_calls` | sequential of maps            | optional | Tool calls requested by an `assistant` message. |
| `:name`       | string                        | optional | Tool name, on `"tool"` messages. |
| `:tool_call_id` | string                      | optional | Id of the tool call a `"tool"` message answers. |

The history is the *provider* format rather than a private one:
`clai.agent.loop` passes it verbatim as the `:messages` field of an Ollama
`/api/chat` request. Anything in the vector must therefore stay acceptable to
the Ollama chat API.

### Event types

Each history entry becomes one `agent_session_event` row: the whole message map
is stored in `content` and `event_type` is derived from it by
`clai.daemon.postgres.sessions/message->event-type`:

| Message                                        | `event_type` |
|------------------------------------------------|--------------|
| `{:role "user" ...}`                           | `user_prompt` |
| `{:role "system" ...}` (session's system prompt) | `user_prompt` |
| `{:role "assistant" :tool_calls [...]}`        | `tool_call` |
| `{:role "tool" ...}`                           | `tool_call` |
| `{:role "assistant" :thinking "..."}`          | `agent_thought` |
| any other `{:role "assistant" ...}`            | `agent_message` |

The whole message lives in `content`, so reading a history needs only
`content` and the chat request round-trips unchanged; `event_type` exists to
make the log queryable. `clojure_form` is currently unused, reserved for
recording evaluated Clojure forms as events of their own.

What the agent writes, in order:

1. `{:role "system" :content <system-prompt>}` — only at session creation,
   and only when the config has a `:system-prompt` or the session's `cwd`
   contains an `AGENTS.md` (whose contents are appended to the configured
   system prompt, or used as the system prompt when none is configured).
2. `{:role "user" :content "..."}` — one per incoming prompt.
3. `{:role "assistant" :content "" :tool_calls [...]}` — the model's own tool
   request, stored verbatim so the next round can see it.
4. `{:role "tool" :content "<result>" :name "read_file" :tool_call_id "..."}`
   — one per executed tool call.
5. `{:role "assistant" :content "<final reply>"}` — once the model stops
   calling tools. When the model returned reasoning, the message also carries
   `:thinking "<chain of thought>"` (stored as an `agent_thought` event).

## Writing, concurrency, and durability

* `save-session` rewrites a session's `working_directory` and replaces its
  whole event log in a single transaction.
* `append-history` inserts one `agent_session_event` row, atomically and only
  when the session exists.
* `clear-history` deletes the session's events.
* Postgres row locks and transactions replace the per-session in-process locks
  the old file store used. Multiple processes sharing a database can update
  the same session concurrently; concurrent appends are serialized by the
  database rather than lost.
* A failed query is logged (`::query-failed`, from `clai.daemon.postgres`) and
  rethrown as an `ExceptionInfo` naming the operation. Unlike the old file
  store, one bad session cannot corrupt the store; other sessions remain
  readable.

## API surface

All functions live in `clai.daemon.postgres.sessions` and take a
`clai.daemon.postgres/Client` as their first argument. The agent keeps its client in the `:postgres` field of
its handler record and passes it to `clai.agent.loop` for the reads and writes
along the chat loop.

### Lifecycle

| Function | Persists | Returns |
|----------|----------|---------|
| `create-session` `[client {:cwd}]` | New `agent_session` row; empty event log | The state map (with the new `:sessionId`) |
| `load-session` `[client id]` / `lookup-session` `[client id]` | — | State map, or `nil` if absent |
| `save-session` `[client state]` | Working directory and the whole event log; a state with no `:sessionId` creates a session | The saved state map, or `nil` if `:sessionId` names no row |
| `delete-session` `[client id]` | Removes the session and (by cascade) its events | `true` if a row was removed, else `false` |
| `session-exists?` `[client id]` | — | Boolean |

### Reading

| Function | Returns |
|----------|---------|
| `list-sessions` `[client]` | Vector of full state maps, sorted by `:createdAt` ascending |
| `list-session-summaries` `[client]` | Same, without `:history` (for session pickers / ACP `session/list`) |
| `session-history` `[client id]` | `:history` vector, or `[]` if the session is unknown |

### Mutation

| Function | Effect |
|----------|--------|
| `append-history` `[client id msg]` | Appends one event; returns the *message*, or `nil` if unknown |
| `clear-history` `[client id]` | Deletes all events; returns the updated state map, or `nil` if unknown |

## Relationship to the ACP session lifecycle

`clai.agent` still maps ACP protocol calls onto this store:

| ACP call | Storage effect |
|----------|----------------|
| `session/new` | `create-session` with the client's `cwd`; appends a system message combining the configured system prompt with the `cwd`'s `AGENTS.md` (when either exists) |
| `session/prompt` | Refuses unknown sessions; otherwise appends the user message(s), then each assistant/tool message the loop produces |
| `session/load` | `load-session`, persists a changed `cwd` if the client supplies one, then replays `:history` to the client as `session/update` chunks (`user` → `user_message_chunk`, `assistant`/`agent` → `agent_message_chunk`, preceded by `agent_thought_chunk` when the message has `:thinking`; `system` and `tool` messages are internal and not replayed) |
| `session/list` | `list-session-summaries`, optionally filtered by exact `:cwd` match |
| `session/resume` | `load-session`; because ids are database keys, an unknown id cannot be reused, so resume degrades to a new session whose id is returned |
| `session/delete` | `delete-session` |
| `session/close` | Nothing — history is deliberately kept so the session can be loaded again |

## Working with sessions by hand

Use `psql` (or any PostgreSQL client) against the configured database:

```sql
-- recent sessions
SELECT id, working_directory, created_at
  FROM agent_session
 ORDER BY created_at DESC
 LIMIT 20;

-- one session's event log
SELECT id, event_type, content
  FROM agent_session_event
 WHERE agent_session_id = 42
 ORDER BY id;

-- events of one type across sessions
SELECT agent_session_id, count(*)
  FROM agent_session_event
 WHERE event_type = 'tool_call'
 GROUP BY agent_session_id;

-- forget a session (its events cascade)
DELETE FROM agent_session WHERE id = 42;
```

Deleting the `agent_session` row is equivalent to `session/delete`; closing a
session does not remove it. Deleting events
(`DELETE FROM agent_session_event WHERE agent_session_id = 42`) is a supported
way to trim context: the next prompt simply sends the smaller history to the
model.

## No legacy state

The previous EDN-file store under `~/.local/clai/sessions/` is no longer read,
and the earlier `clai_sessions` / `clai_session_messages` / `clai_data` tables
are no longer created or used. `clai` has not been used in production, so no
migration path is provided; existing files and tables can simply be deleted.
