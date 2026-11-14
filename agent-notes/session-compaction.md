# Session compaction

## What it does

Every chat turn sends the session's whole history to the model, so a long
session eventually exceeds the model's context window and the provider rejects
the request. Compaction keeps the history bounded: when the estimated size of a
session's history is over `:compact-after-tokens`, the agent asks the session's
own model for a summary of the older messages and stores the history as
`[system prompt] [summary] [newest messages...]`.

Default budget: `clai.agent.compaction/default-after-tokens` (60000), overridable
by `clai.edn`'s `:compact-after-tokens`, `CLAI_COMPACT_AFTER_TOKENS`, or a
session's stored agent configuration (`agent_config.compact_after_tokens`).
`0` disables compaction (0 is truthy in Clojure, so `(or 0 default)` keeps it --
see `apply-agent-config`).

## Decisions

**Token counts are estimated, not counted.** `estimate-tokens` is
`ceil(chars / 4)` plus a fixed `message-overhead-tokens` per message. Real
tokenizers are model-specific and would mean shipping one per provider; the
budget only has to be a good guess that errs toward compacting early.

**The trigger is checked before every chat turn, inside the loop.**
`clai.agent.core/chat-history` runs at the top of each `run-agent-loop` round, so
a single enormous tool result gets compacted before the next request rather than
after the prompt ends. It reads the history once and returns what to send, so the
loop still loads the history exactly once per round.

**A compaction keeps the tail, not N messages.** `plan` walks the history
backwards accumulating `message-tokens` until `tail-budget` (= half the trigger
budget, `tail-fraction`) is exceeded, always keeping at least the last message.
Half-budget is the headroom that stops a just-compacted session from compacting
again on the next turn. `widen-for-tool-results` then moves the cut back over
`tool`-role messages: a provider rejects a `tool` message whose assistant
`tool_calls` message is not in the same request.

**The summary is a `system` message with a marker line, not a new event type.**
Providers only accept `role`/`content` on a message (Ollama's decoder rejects
unknown fields), and the event log stores the whole message, so the summary had
to be recognizable from those two fields. `summary-marker` is the first line of
`summary-message`'s content; `summary?`/`summary-text` read it back. That is what
lets `plan` detect an earlier summary and fold it into the next one (so a session
keeps exactly one summary) and what lets `history->updates` replay it as a
`session_compaction` update. Adding an `agent_summary` value to
`agent_session_event_type_t` was rejected: `ALTER TYPE ... ADD VALUE` cannot be
used in the same transaction as the insert on older PostgreSQL, and the enum
value would buy nothing the marker does not.

**Summarizing is a fold over chunks.** `chunks` splits the older messages into
runs of at most `budget` tokens and `summarize` reduces them, feeding each
summary forward as "Summary so far". Compacting a 500k-token history therefore
costs a bounded number of bounded requests instead of one request the model
cannot accept. Each request has a plain system instruction plus user turns --
no tools (a summary must not call tools) and no `chat-opts` (the agent's
`:format "json"` would break the plain-text reply).

**A failed summary is skipped, not fatal.** `compact-history` catches Throwable,
logs `::compaction-failed`, and returns nil; the chat turn then sends the
uncompacted history (which may still fit, or fail on its own). A compaction
problem must never turn into "your prompt failed".

**A compaction must shrink the history.** `compact-history` compares the token
estimate before and after and returns nil (logging `::no-shrink`) when the
summary did not make the history smaller. Without that check a verbose summary
of a tiny older chunk would leave the session permanently "over budget" and
compact -- and stream an update -- on every turn.

**Storage replaces the history.** `clai.daemon.postgres.sessions/replace-history`
(new public function, one transaction, validation per message) swaps the event
log for the compacted one. The raw transcript is gone -- that is the point of
compaction -- and the summary is what the UI and the model see from then on.

## Where

- `src/clai/agent/compaction.clj` -- everything: `estimate-tokens`,
  `message-tokens`, `history-tokens`, `summary-message`/`summary?`/
  `summary-text`, `CompactionUpdate`/`compaction-update`, `plan`,
  `chunks`, `summarize`, `compact-history`, and the `default-after-tokens` /
  `chars-per-token` / `message-overhead-tokens` / `tail-fraction` knobs.
  Tests in `test/clai/agent/compaction_test.clj`.
- `src/clai/config.clj` -- `:compact-after-tokens` in the `config` schema,
  `resolve-compact-after-tokens`, and the new private `env-int` helper (also
  used by `resolve-max-tool-rounds`, which used to throw on a non-numeric
  `CLAI_MAX_TOOL_ROUNDS`).
- `src/clai/agent/core.clj` -- `build-agent` resolves the budget into
  `:compact-after-tokens`, `apply-agent-config` lets a session configuration
  override it, and `chat-history` compacts before each chat turn.
- `src/clai/agent/configs.clj` + `schema/schema.sql` -- the
  `compact_after_tokens` column (with an `ALTER TABLE ... ADD COLUMN IF NOT
  EXISTS` for existing databases), SQL parameters, and `row->config`.
- `src/clai/daemon/postgres/sessions.clj` -- `replace-history`.
- `src/clai/agent/providers/ollama/prompt.clj` -- `history->updates` replays a
  summary as `session_compaction` before the messages after it.
- `src/clai/repl.clj` -- `session_compaction` renders under the `compacted`
  label.
- `web/src/components/UpdateView.jsx` + `web/src/styles.css` -- the
  `session_compaction` update renders as a collapsed `.compaction` block.
- `web/src/components/AgentConfigManager.jsx` -- the "Compact after tokens"
  field of the agent configuration dialog.
- `test/clai/test_support.clj` -- the in-memory session store implements
  `replace-history`, so agent tests still need no database.
- `agent-wiki/compaction.md` -- the user-facing page.

## Test-suite consequences

- `clai.agent.core-test/run-with-compacting-ollama` stubs `/api/chat` for three
  kinds of request: a chat turn (`:tools` present), a summarization request
  (recognized by `summarization-instruction` in its system message), and a
  session-title request (neither). `run-with-stubbed-ollama` is unchanged and
  still answers "no `:tools`" requests with a title, since none of its tests
  compact.
- Compaction is on by default at 60000 tokens, so no existing agent test
  triggers it; the tests that do use `:cfg {:compact-after-tokens 1000}`.
