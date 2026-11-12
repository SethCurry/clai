# clai web

The browser UI for clai. It is a Vite + React app that talks to
`clai.daemon.http` over `/ws`, with a few JSON endpoints under `/api` for
health checks, non-streaming session access, and filesystem reads.

## Scripts

```sh
npm install
npm run dev     # Vite on :5173, proxying /api and /ws to :8080
npm run build   # writes dist/, which `lein run web` serves by default
npm run preview # serve dist/ with Vite
npm test        # unit tests for the pure helpers (node:test)
```

Start the daemon separately while developing:

```sh
lein run web
```

Set `CLAI_DAEMON_URL` to point the Vite proxy at another daemon
(`http://127.0.0.1:8080` by default).

## WebSocket protocol

Every client message may include a `requestId`; responses echo it. The daemon
also pushes unsolicited messages for streaming updates.

Client messages:

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

Agent configurations can also be managed over HTTP; the sidebar's
**Manage** button uses these endpoints:

```text
GET    /api/agent-configs
GET    /api/agent-configs/:id
POST   /api/agent-configs
PUT    /api/agent-configs/:id
DELETE /api/agent-configs/:id
GET    /api/plugins
GET    /api/sessions/:id/completions?path=src/cl&limit=50
GET    /api/sessions/:id/files?path=src/&limit=500
GET    /api/sessions/:id/file?path=src/clai/core.clj
```

Request and response bodies use the same shape as the `agent-config` rows,
with kebab-case keys such as `system-prompt` and `max-tool-rounds`. A `null`
`tools` value means the agent's default registry, while an empty array
disables tools for sessions that select the configuration. A `null` `plugins`
value means every enabled plugin, while an empty array disables plugins.

The completions endpoint powers the composer's `@` mentions. `path` is the
text typed after the `@`: everything before the last `/` picks the directory
to list (relative to the session's `cwd`) and the final segment is a
case-insensitive name prefix. Directories are listed first and their `path`
ends in `/` so the UI can keep descending. Paths that escape the session's
`cwd` return no entries.

```json
{"directory":"src/clai","prefix":"da","entries":[{"name":"daemon","path":"src/clai/daemon/","type":"directory"}]}
```

The file explorer uses two more endpoints, both scoped to the session's `cwd`
and both refusing paths that escape it:

`/files` lists one directory -- a `path` ending in `/` lists all of it -- and
includes dotfiles, which completions hides until you type a `.`:

```json
{"directory":"src","prefix":"","entries":[{"name":"app.clj","path":"src/app.clj","type":"file"}]}
```

`/file` reads one file for the preview. `content` holds at most 128 KiB, and a
file that looks binary comes back with `binary` true and an empty `content`:

```json
{"path":"src/app.clj","name":"app.clj","content":"(ns app)","truncated":false,"binary":false}
```

Server messages:

```json
{"type":"hello","model":"gemma3","nreplPort":12345}
{"type":"sessions","sessions":[...]}
{"type":"session","session":{"sessionId":"...","cwd":"...","updates":[...]}}
{"type":"plugins","plugins":[{"pluginId":"1","name":"todays-date","enabled":true}]}
{"type":"update","sessionId":"...","update":{"type":"agent_message", "...":"..."}}
{"type":"prompt_done","sessionId":"...","result":{"stopReason":"end_turn"}}
{"type":"deleted","sessionId":"..."}
{"type":"eval","requestId":"...","response":{"ns":"user","value":"3","status":["done"]}}
{"type":"eval_done","requestId":"..."}
{"type":"error","message":"..."}
```

`update` messages are event-shaped values from `clai.events.schema`. Tool
calls arrive in fragments with the same `id`, so the UI merges them into one
card until the status becomes `completed` or `failed`.

## Layout

- `src/useClaiSocket.js` owns the WebSocket, reconnects, and reduces server
  messages into app state. Agent-configuration writes go through the HTTP API
  and update the same state.
- `src/App.jsx` is the shell: top bar, sidebar, chat, optional REPL, and the
  agent-configuration dialog.
- `src/components/AgentConfigManager.jsx` creates, edits, and deletes stored
  agent configurations, including their active-plugin list.
- `src/components/UpdateView.jsx` renders messages, thoughts, and tool calls.
- `src/components/Chat.jsx` is the prompt timeline and composer. Typing `@`
  in the composer opens the file/folder completion popup.
- `src/usePathAutocomplete.js` owns that popup's state: debounced requests to
  the completions endpoint, keyboard navigation, and inserting the picked
  path into the draft. `src/mentions.js` holds the pure caret/token helpers
  it builds on, and `src/components/PathAutocomplete.jsx` renders the list.
- `src/components/ReplPanel.jsx` evaluates Clojure through the daemon's nREPL
  connection, defaulting to the selected session's `sessions.<id>` namespace.
- `src/components/FileExplorer.jsx` is the Files panel beside the REPL. It
  lists the selected session's `cwd` lazily -- a folder is fetched when it is
  first opened -- and previews one file's text at a time.
  `src/fileExplorer.js` holds the pure helpers it renders with, including
  `visibleRows`, which flattens the caching tree into rows.
- `src/api.js` holds the shared `fetch` body reader.
- `src/sessionNamespace.js` derives that namespace from a session id, matching
  `clai.repl/session-ns`.
