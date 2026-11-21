---
title: "HTTP API"
description: "Reference for the clai daemon's authentication, WebSocket protocol, session and configuration endpoints, file and namespace explorers, notes, and usage stats."
summary: "Auth, the WebSocket protocol, and every JSON endpoint."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 3
toc: true
---

The daemon serves the React UI, the JSON API under `/api/`, and the WebSocket
upgrade at `/ws` on one port (8080 by default). It binds to `0.0.0.0`; set
`:host` on `clai.daemon.http/start-server!` to `127.0.0.1` for local-only
access.

## Authentication

Only the login page's own calls are reachable without a session. Every other
`/api/` route and the `/ws` upgrade answers `401` without a valid cookie or
bearer token.

| Method | Path                | Body                     | Response                                      |
| ------ | ------------------- | ------------------------ | --------------------------------------------- |
| `POST` | `/api/auth/login`   | `{"username","password"}`| `200 {"authenticated":true,"user":{...}}` plus an `HttpOnly` cookie; `401` on bad credentials |
| `GET`  | `/api/auth/me`      | none                     | `200 {"authenticated":true|false,"user":...}` |
| `POST` | `/api/auth/logout`  | none                     | `200 {"authenticated":false,"user":null}` and a cleared cookie |

Non-browser clients can send `Authorization: Bearer <token>` instead of the
cookie. Login sessions last seven days. Passwords are PBKDF2-SHA256 digests,
and tokens are stored as SHA-256 digests.

## Health

`GET /api/health` reports the daemon's liveness, provider, model, nREPL port,
open WebSocket connections, and session count. A database failure answers
`503` with `"ok": false`.

```json
{"ok":true,"provider":"ollama","model":"gemma3","nreplPort":45123,
 "connections":1,"sessions":12}
```

## Sessions

| Method   | Path                                  | Purpose                                                  |
| -------- | ------------------------------------- | -------------------------------------------------------- |
| `GET`    | `/api/sessions`                       | List session summaries.                                  |
| `POST`   | `/api/sessions`                       | Create one from `{"cwd","configId"}`; `201` with the chat payload. |
| `GET`    | `/api/sessions/:sessionId`            | One session summary plus the updates a chat view replays. |
| `DELETE` | `/api/sessions/:sessionId`            | Delete the session.                                      |
| `GET`    | `/api/sessions/:id/completions`       | Path completions for the composer's `./` popup.          |
| `GET`    | `/api/sessions/:id/files`             | List a directory under the session's `cwd`.              |
| `GET`    | `/api/sessions/:id/file`              | Read one file under the session's `cwd`.                 |

The completions endpoint takes `path` (the text after `./`) and `limit`.
Everything before the last `/` picks the directory; the final segment is a
case-insensitive prefix. Directories come first and their `path` ends in `/`.
Paths that escape the session's `cwd` return no entries.

```json
{"directory":"src/clai","prefix":"da",
 "entries":[{"name":"daemon","path":"src/clai/daemon/","type":"directory"}]}
```

`/files` lists one directory and includes dotfiles; a `path` ending in `/`
lists all of it. `/file` reads at most 128 KiB and reports `binary` with empty
content for a file that looks binary.

## Agent configurations, plugins, repositories

These resources use the same conventional REST shape:

| Resource            | Path                  |
| ------------------- | --------------------- |
| Agent configs       | `/api/agent-configs`  |
| Plugins             | `/api/plugins`        |
| Git repositories    | `/api/git-repos`      |
| Git worktrees       | `/api/git-worktrees`  |

Each supports `GET` (list), `POST` (create), `GET /:id` (read), `PUT /:id`
(update), and `DELETE /:id`. Bodies use the same kebab-case keys as the stored
rows, such as `system-prompt`, `max-tool-rounds`, `tools`, and `plugins`.

A `null` `tools` value means the agent's default registry; an empty array
disables tools for sessions that select the configuration. A `null` `plugins`
value means every enabled plugin; an empty array disables plugins.

## Notes

| Method   | Path                    | Purpose                                             |
| -------- | ----------------------- | --------------------------------------------------- |
| `GET`    | `/api/notes`            | List notes.                                         |
| `GET`    | `/api/notes/search`     | Search with `?q=...&limit=...` (BM25 when available).|
| `GET`    | `/api/notes/:id`        | One note with its `links` and `backlinks`.          |
| `POST`   | `/api/notes`            | Create from `title`, `content`, optional `links`.   |
| `PUT`    | `/api/notes/:id`        | Update the same fields.                             |
| `DELETE` | `/api/notes/:id`        | Delete.                                             |

List and search responses wrap their results in a `notes` key; a single-note
response is the note itself. Links are normally written in the content as
`[[Note title]]`, so the `links` array is optional.

## Namespace explorer

These endpoints read the daemon's own JVM, which is the runtime the REPL panel
evaluates in.

| Method | Path                                  | Purpose                                              |
| ------ | ------------------------------------- | ---------------------------------------------------- |
| `GET`  | `/api/namespaces`                     | Loaded namespaces, sorted, with docstring and var count. |
| `GET`  | `/api/namespaces/:namespace/vars`     | One namespace's public Vars; `missing: true` if unloaded. |
| `GET`  | `/api/namespaces/:namespace/var`      | One Var (`?name=`), with schema, source, and value.  |

`source` and `value` are `null` when they cannot be read, such as a REPL
definition with no file or an unbound Var.

## Stats

The stats endpoints read the whole session event log and take no parameters.

| Method | Path                    | Contents                                                   |
| ------ | ----------------------- | ---------------------------------------------------------- |
| `GET`  | `/api/stats/overview`   | Totals and averages: sessions, prompts, tool calls, characters. |
| `GET`  | `/api/stats/tools`      | One row per tool, most-called first.                       |
| `GET`  | `/api/stats/sessions`   | One row per session, oldest first.                         |

```json
{"sessionCount":12,"toolCount":7,"promptCount":340,"agentMessageCount":330,
 "thoughtCount":41,"toolCallCount":1210,"completedToolCallCount":1204,
 "avgPromptsPerSession":28.33,"avgToolCallsPerSession":100.83,
 "totalPromptChars":412000,"avgPromptChars":1212,
 "totalReplyChars":512000,"avgReplyChars":1551,
 "totalToolOutputChars":9123456,"avgToolOutputChars":7580,
 "maxToolOutputChars":64000,"avgToolOutputCharsPerSession":760288}
```

Averages with no data are `null`. Character counts are measured on the JSON
stored in the event log.

## WebSocket protocol

Every client message may include a `requestId`; responses echo it. The daemon
also pushes unsolicited messages for streaming updates.

### Client messages

```json
{"type":"list_sessions"}
{"type":"new_session","cwd":"/optional/working/directory","configId":"3"}
{"type":"load_session","sessionId":"..."}
{"type":"delete_session","sessionId":"..."}
{"type":"list_agent_configs"}
{"type":"load_agent_config","configId":"3"}
{"type":"load_agent_config","name":"fast"}
{"type":"list_plugins"}
{"type":"prompt","sessionId":"...","prompt":"..."}
{"type":"eval","code":"(+ 1 2)","ns":"sessions.123"}
```

An `eval` without `ns` defaults to the namespace attached to the active
session (`sessions.<session-id>`), or `user` when no session is selected.

### Server messages

```json
{"type":"hello","model":"gemma3","nreplPort":12345}
{"type":"sessions","sessions":[...]}
{"type":"session","session":{"sessionId":"...","cwd":"...","updates":[...]}}
{"type":"plugins","plugins":[{"pluginId":"1","name":"todays-date","enabled":true}]}
{"type":"update","sessionId":"...","update":{"type":"agent_message","...":"..."}}
{"type":"prompt_done","sessionId":"...","result":{"stopReason":"end_turn"}}
{"type":"deleted","sessionId":"..."}
{"type":"eval","requestId":"...","response":{"ns":"user","value":"3","status":["done"]}}
{"type":"eval_done","requestId":"..."}
{"type":"error","message":"..."}
```

`update` messages are event-shaped values from `clai.events.schema`. Tool
calls arrive in fragments with the same `id`, so the UI merges them into one
card until the status becomes `completed` or `failed`. See
[Events](/docs/reference/events/).
