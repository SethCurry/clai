# The `eval_clojure` tool and the agent's REPL

## Decision

`eval_clojure` evaluates one Clojure form in the daemon's own JVM. Its default
namespace is `clai.home.core`, a real namespace (`src/clai/home/core.clj`) that
the tool refers to as the agent's REPL. That namespace exists so the first
form an agent evaluates has something useful in scope, and the tool's
description points at those helpers.

## Why the description got long

Agents were not reaching for `eval_clojure` even when it was the cheapest way
to answer a question. A tool description is the only place a model learns
*when* to use a tool, so the description now says, in order:

1. what the tool does (print output + `=> value`, state persists, errors are
   returned not thrown);
2. when to prefer it ("USE THIS TOOL OFTEN", with concrete examples: look up a
   Var, grep the project by `file-seq`, compute something, try a fix);
3. how to discover the agent's own tools: `(tools)` and `(tool "id")` read the
   live registry, so the model can see ids, descriptions, and argument names
   that the system prompt may not spell out;
4. what the default namespace preloads, including `(context)`, `(session)`,
   and `(history)`;
5. that `def` is how a value survives (see below);
6. the limits (one form per call, small results, `namespace` must be loadable).

Keep the description and `clai.home.core`'s cheat sheet in sync: the
description is `clai.agent.tools.builtin.eval/tool-description`, the cheat
sheet is `clai.home.core/cheat-sheet` (printed by `(help)`).

## Why the description pushes `def`

The agent and the user evaluate in the *same* namespace, so a Var the agent
defines is a Var the user can read and re-run from their own REPL, and a Var
the user defines is in scope for the agent's next call. Values hidden in a
`let` are therefore a dead end for the user, and values recomputed in every
call are wasted work. The description says so in a "SAVE WHAT YOU FIND WITH
`def`" section: name Vars for what they hold (not `a`/`tmp1`), prefer `def`
over a `let` for anything worth a second look, and keep only state that must
outlive the process in a file.

`test/clai/agent/tools/builtin/eval_test.clj` asserts the description still
contains `(def ` so this guidance does not get lost in a rewrite.

## The live session, from inside an evaluation

The tool binds `clai.agent.tools.builtin.eval/*context*` to the tool call's
`ctx` for the duration of the evaluation. `clai.home.core` reads it:

- `(context)`  the raw `ctx` map
- `(tools)`    `[{:id :description}]` from `:tool-registry`
- `(tool id)`  one tool's full Ollama spec
- `(session)`  `clai.daemon.postgres.sessions/load-session`, minus `:history`
- `(history)`  `clai.daemon.postgres.sessions/session-history`

`session` and `history` need a storage client, so `clai.agent.core`'s
`session-tool-ctx` now includes `:postgres` alongside `:cwd`, `:client`,
`:model`, and `:tool-registry`. `clai.agent.tools.schema/tool-ctx` documents
it. Everything degrades to `nil`/`[]` outside a tool call (a plain REPL, a
sub-agent without the key), so the helpers are safe to call anywhere.

## Other changes made at the same time

- The default namespace is created empty rather than "not found" if
  `clai.home.core` cannot be loaded, so the tool still works in a trimmed-down
  runtime.
- `(range)` no longer hangs the tool: the result value is printed with
  `*print-length*` 200 and `*print-level*` 20. A truncated print still ends in
  `...`/`#`, so it is visible.
- Results are capped at `common/max-output-bytes` (64 KiB) with a
  `… [truncated at N bytes]` marker, matching the other tools.
- Stack traces in errors are cut to 10 frames, and the message of the
  innermost cause is reported too -- a `CompilerException` otherwise reads
  only `Syntax error compiling at (0:0).`, hiding "Unable to resolve symbol".

## Gotcha: the running daemon keeps its old registry

An agent's tool registry is built when the agent is built, and tools are plain
function values. Editing `eval.clj` and reloading the namespace does *not*
change the handler (or description) an already-created agent uses, and
`*context*` binding is part of the handler. Restart the daemon (`clai serve`,
`lein run`, ...) to pick up tool changes; the source is what the next process
loads.

## Tests

`test/clai/agent/tools/builtin/eval_test.clj` covers evaluation in the default
namespace, printed output, errors, truncation, the `*context*` binding, the
`tools`/`tool` helpers, `session`/`history` (through the in-memory session
store fixture), and the home namespace helpers without a context.
