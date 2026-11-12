# clai web

The browser UI for clai. It is a Vite + React app that talks to
`clai.daemon.http` over `/ws`, with a few JSON endpoints under `/api` for
health checks and non-streaming session access.

## Scripts

```sh
npm install
npm run dev     # Vite on :5173, proxying /api and /ws to :8080
npm run build   # writes dist/, which `lein run web` serves by default
npm run preview # serve dist/ with Vite
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
```

Request and response bodies use the same shape as the `agent-config` rows,
with kebab-case keys such as `system-prompt` and `max-tool-rounds`. A `null`
`tools` value means the agent's default registry, while an empty array
disables tools for sessions that select the configuration. A `null` `plugins`
value means every enabled plugin, while an empty array disables plugins.

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
- `src/components/Chat.jsx` is the prompt timeline and composer.
- `src/components/ReplPanel.jsx` evaluates Clojure through the daemon's nREPL
  connection, defaulting to the selected session's `sessions.<id>` namespace.
- `src/sessionNamespace.js` derives that namespace from a session id, matching
  `clai.repl/session-ns`.
