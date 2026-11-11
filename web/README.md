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
{"type":"new_session","cwd":"/optional/working/directory"}
{"type":"load_session","sessionId":"..."}
{"type":"delete_session","sessionId":"..."}
{"type":"prompt","sessionId":"...","prompt":"..."}
{"type":"eval","code":"(+ 1 2)","ns":"user"}
```

Server messages:

```json
{"type":"hello","model":"gemma3","nreplPort":12345}
{"type":"sessions","sessions":[...]}
{"type":"session","session":{"sessionId":"...","cwd":"...","updates":[...]}}
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
  messages into app state.
- `src/App.jsx` is the shell: top bar, sidebar, chat, and optional REPL.
- `src/components/UpdateView.jsx` renders messages, thoughts, and tool calls.
- `src/components/Chat.jsx` is the prompt timeline and composer.
- `src/components/ReplPanel.jsx` evaluates Clojure through the daemon's nREPL
  connection.
