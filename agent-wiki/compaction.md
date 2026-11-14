# Session compaction

A long conversation eventually stops fitting: every chat turn sends the whole
session history to the model, and once that history is larger than the model's
context window the provider refuses the request. clai compacts sessions to
avoid that. Once a session's history is estimated to be over a configurable
token budget, the agent summarizes the older messages with the session's own
model and replaces them with that summary. The conversation carries on from the
summary, with the newest messages still there in full.

You do not have to do anything: compaction is on by default at 60000 tokens.

## What a compaction keeps

A compaction leaves this history behind:

1. the session's **system prompt**, unchanged — the configured prompt, any
   `AGENTS.md`, and plugin additions;
2. one **summary** of everything older, written by the model;
3. the **most recent messages**, unchanged, up to about half the budget.

Keeping half the budget free means the next turns have room to run before the
session needs compacting again, so a long session settles into "compact every
so often" rather than "compact on every turn". A tool call is never separated
from the results it produced, even when the cut between "older" and "newest"
falls in the middle of them.

When the session is compacted a second time, the earlier summary is folded into
the new one instead of being summarized from scratch, so a session keeps exactly
one summary no matter how many times it is compacted. Very long histories are
summarized in chunks, so the summarization request itself stays inside the
budget.

## Configuring the budget

Compaction triggers when the history is estimated to be over
`:compact-after-tokens`. Set it in `clai.edn`:

```clojure
{:model                 "gemma3"
 :compact-after-tokens  30000}   ; 0 turns compaction off
```

or with the `CLAI_COMPACT_AFTER_TOKENS` environment variable, or per session
through a stored agent configuration (see below). Precedence is: the agent
configuration a session selected, then `clai.edn`, then `CLAI_COMPACT_AFTER_TOKENS`,
then 60000.

A budget of `0` disables compaction for that session, which is what you want
when the model's context window is bigger than anything you plan to send:

```clojure
{:model                 "openrouter:anthropic/claude-sonnet-4"
 :compact-after-tokens  0}
```

Token counts are estimated from character counts (about four characters per
token), because tokenizers are model specific and the budget only needs to be a
good guess. Pick a budget well under your model's real context window: the
response needs room too, and a summary is not free.

### Per session, in the web UI

New sessions can select a stored agent configuration, and that configuration
can carry its own `Compact after tokens` value. Open **Agent configurations**
from the sessions page, set the field, save, and start a session with that
configuration. Leave it blank to inherit the daemon's budget; enter `0` to turn
compaction off for that session only.

## What you see

- **Web UI**: a collapsed *Earlier messages compacted* block appears in the
  timeline, holding the summary. It is replayed when you reload the session, so
  a reloaded conversation still shows where its older messages went.
- **CLI REPL**: the summary is printed under a `compacted>` label.
- The stored history really is replaced, so the summary is what the model sees
  from then on. Anything in the older messages that the summary omits is gone
  from the session; write the parts you care about into a
  [note](notes.md) if you need them later.

If the summarization request fails (the provider is down, for example), the
failure is logged and the session simply is not compacted that turn; the next
turn tries again. A failure never breaks the prompt you sent.

## Working with the budget from the REPL

The compaction functions are plain functions of a history vector, so you can
see exactly what a budget would do from the REPL:

```clojure
(require '[clai.agent.compaction :as compaction])

;; The conversation of the session this REPL namespace belongs to
;; (`clai.repl/history!` prints it as it loads it).
(def messages (:history (clai.repl/history!)))

;; How big is this session, by the agent's estimate?
(compaction/history-tokens messages)
;; => 41234

;; What would a compaction keep, and what would it summarize?
(compaction/plan messages 60000)
;; => {:system [...], :previous nil, :older [...], :recent [...]}
```

Compaction itself happens in the agent loop that
`clai.agent.core/run-prompt!` drives: before each chat turn, a session that is
over budget is compacted first.

## Related

- [Models and providers](providers.md): pick the model whose context window
  your budget has to fit.
- [Notes](notes.md): keep the details of a long session somewhere durable.
- [The web console](web-console.md): where the agent-configuration dialog and
  the new-session configuration picker live.
- [Plugins](plugins.md): plugins can add to a session's system prompt, which
  compaction always keeps.
