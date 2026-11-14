# Automatic session titles

## What it does

A session starts untitled (`agent_session.title` is null) and the sidebar shows
only `#id` and its working directory. Now the first prompt names the session:
clai asks the model for a 1 to 5 word title, writes it to the session row, and
pushes it to the browser while the reply is still being produced. Clicking the
title still renames it by hand (`rename_session`), and a blank title clears it.

## Decision: one direct provider call, nothing recorded

`clai.agent.titles/generate-title` builds a single user message (an instruction
plus the first message, clipped to `max-prompt-chars`) and calls
`clai.agent.providers/complete` with only `{:model ... :history [...]}`:

- **No tools.** A title turn must not be able to call tools, and neither
  provider rejects a request that offers none (OpenRouter omits an empty
  `:tools` list on purpose, see `openrouter/request-body`).
- **No `chat-opts`.** The agent's `:format`/`:think`/`:options` stay out of it:
  a `json` format or forced thinking would break the plain-text reply the
  parser expects.
- **Nothing recorded.** The exchange is not appended to the session's event
  log and creates no session row, which is what "don't track them beyond
  setting the title" means here: the title lives on the session row, the
  request lives nowhere. `sessions/rename-session` is the only write.

`clean-title` normalizes the reply because models answer with more than a
title: first non-blank line, list markers/quoting stripped, whitespace
collapsed, five words max. `nil` (no usable reply) leaves the session
untitled rather than storing junk.

## Decision: only the first prompt, and it happens before the loop

`clai.agent.core/untitled-first-turn?` gates it: the session has no title and
its history holds nothing but the opening `system` prompt. So a session
created with a title (`new-session!`'s `:title`) is left alone, a resumed
session is named on its first real prompt, and the second prompt never asks
again.

`run-prompt!` calls `name-session!` after the user prompt is appended and
before `run-agent-loop` runs. That is one small extra chat turn on the way to
the first reply; the alternative (a `future` racing the loop) was rejected for
the extra concurrency and because the title is most useful *before* the agent
starts working -- the sidebar gets its name immediately. Failures are logged
(`::session-title-failed`) and swallowed: a missing title must never fail a
prompt.

## How the browser hears about it

`clai.daemon.http/run-prompt!` passes `:on-title` to the agent, and the
callback sends the existing `session_renamed` WebSocket message via the new
`send-session-renamed!` helper -- the same message an explicit `rename_session`
produces, so `web/src/useClaiSocket.js` retitles the open conversation and its
sidebar entry without changes. An automatic title has no `requestId` to echo;
an explicit rename echoes the request's.

The CLI REPL (`clai.repl/prompt!`) passes `:on-title` too and prints the title
as a `[title]` line.

## Where

- `src/clai/agent/titles.clj` -- `generate-title`, `clean-title`,
  `strip-markup`, `title-history`, `clip`, `instruction`, `max-title-words`,
  `max-prompt-chars`; tests in `test/clai/agent/titles_test.clj`.
- `src/clai/agent/core.clj` -- `untitled-first-turn?`, `prompt-text`,
  `name-session!`, and the `:on-title` key of `run-prompt!`.
- `src/clai/daemon/http.clj` -- `send-session-renamed!` (also used by the
  `rename_session` message) and the `:on-title` wiring in its private
  `run-prompt!`.
- `src/clai/repl.clj` -- `render-title!`.
- `test/clai/test_support.clj` -- the in-memory session store now implements
  `rename-session` (and `create-session` keeps a `:title`), so agent tests need
  no database.

## Test-suite consequence

Every real prompt against a stubbed Ollama now makes a title request first.
`run-with-stubbed-ollama` and the tests that stub `ollama/chat` themselves
answer a request without a `:tools` key (the title request) with a fixed title
and keep it out of the collected chat `requests`.
