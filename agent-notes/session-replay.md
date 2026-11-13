# Session replay in the web UI

## What went wrong

Loading a session in the web UI showed chat bubbles with no text. The cause was
`clai.agent.providers.ollama.prompt/history->updates`, which turned every
persisted `assistant` message into an `agent_message` event even when the
message carried no text.

Ollama's native tool-calling models emit an assistant message whose `content`
is `""` while the actual work is in `:tool_calls`. Those messages are the
majority of a long agentic conversation (session 3 had 79 of them), so a
reloaded conversation was mostly empty bubbles. Tool calls and their outputs
were not replayed at all, so a reloaded conversation also lost the record of
what the agent did.

## Decision

`history->updates` now replays a conversation the way it streamed:

- a `user` message becomes a `user_prompt` event,
- an assistant message becomes an `agent_thought` (when `:thinking` is
  non-blank), an `agent_message` (only when its text is non-blank), and one
  `tool_call` update per entry in `:tool_calls`,
- each `tool`-role message is folded into the `tool_call` update its
  `:tool_call_id` names, providing `:output` and a terminal `:status`,
- `system` messages (the internal instructions that open a session) are
  dropped.

`tool_call` updates use the same shape the agent streams live (`:id`, `:name`,
`:title`, `:input`, `:output`, `:status`), so `UpdateView.jsx` and the CLI
REPL's `render-tool-call!` need no replay-specific branch. A tool call with no
recorded result replays as `running`, which is what an interrupted session
actually looks like.

`web/src/components/UpdateView.jsx` also skips `agent_message` updates whose
text is blank, so a live stream can never produce an empty bubble either.

## Where

- `src/clai/agent/providers/ollama/prompt.clj` — `tool-result-texts`,
  `tool-call-updates`, `assistant-updates`, `history-message->updates`,
  `history->updates`.
- `src/clai/agent/providers/ollama/messages.clj` — `tool-call-arguments` and
  `tool-result-status` (re-exported by `clai.agent.providers.ollama`). The
  `"Error:"`-prefix rule that decides `failed` vs `completed` now lives in one
  place, used by both the live agent loop (`clai.agent.core`) and replay.
- `test/clai/agent/providers/ollama/prompt_test.clj` — replay tests.

Replay is shared by every consumer of `history->updates`: the web UI's
`session-payload` (`clai.daemon.http`), `GET /api/sessions/<id>`, and the CLI
REPL's `render-history!`.

## Caveat

Replaying a long session now ships every tool output to the browser (about
0.5 MB for 100 tool calls in session 3). That matches what streaming sends
live, so it is accepted; if it becomes a problem, page or truncate tool output
in the payload rather than dropping the calls.
