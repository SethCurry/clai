# Evaluating Clojure with the agent (`eval_clojure`)

`eval_clojure` lets the agent run one Clojure form in the same JVM the agent
itself runs in, and read back what it printed plus its value:

```
=> 3
```

That gives the agent a REPL of its own: it can look up a Var's docstring, read
project files, compute something instead of guessing, call a function to check
what it returns, or inspect the session it is in. Everything it does stays in
your process -- there is no second JVM and no shell involved.

## Where the code runs

Unless the call passes a `namespace` argument, code runs in
`clai.home.core`. That namespace is the agent's REPL and is loaded with a few
conveniences:

| Alias     | Namespace                       |
| --------- | ------------------------------- |
| `eval`    | `clai.agent.tools.builtin.eval` |
| `registry`| `clai.agent.tools.registry`     |
| `sessions`| `clai.daemon.postgres.sessions` |
| `clj`     | `clai.daemon.namespaces`        |
| `plugins` | `clai.plugins`                  |
| `json`    | `cheshire.core`                 |
| `shell`   | `clojure.java.shell`            |
| `io`      | `clojure.java.io`               |
| `str`     | `clojure.string`                |

It also refers `pprint`, `doc`, `source`, `dir`, `apropos`, and `find-doc` from
the standard library, and defines these helpers:

- `(help)` -- print a cheat sheet of everything below.
- `(tools)` -- every tool the agent can call right now, as
  `{:id :description}` maps, read from the live tool registry.
- `(tool "run_command")` -- one tool's full definition, including its exact
  argument names.
- `(context)` -- the live call's session context (`:cwd`, `:session-id`,
  `:model`, `:postgres`, `:tool-registry`, limits).
- `(session)` -- this session's id, title, and working directory.
- `(history)` -- this session's stored messages, oldest first.
- `(active-plugins)` -- the plugins loaded in this process.
- `(vars 'clai.agent.core)` and `(var-detail "clai.util" "error-message")` --
  public Vars of a namespace, and one Var's doc, arglists, source, and value.
- `(list-namespaces)` -- every namespace loaded in the process.

## You share the namespace with the agent

The agent evaluates in `clai.home.core`, and so can you: connect a REPL to the
daemon (`lein repl` from a checkout, or the `clai` CLI REPL) and switch to that
namespace. Anything the agent saves with `def` is then an ordinary Var you can
read, call, or re-evaluate, and anything you `def` is right there for the agent
in its next `eval_clojure` call.

The tool description asks the agent to lean on this, so expect to see it define
Vars instead of hiding results in a `let`:

```clojure
(def failing (filter :failed? (run-tests)))   ; the agent's call
(count failing)                               ; your call, same Var
```

Definitions persist for the life of the process, so the agent can build a
scratchpad across calls. Anything that must outlive the process (config,
findings you want to keep) belongs in a file in the project instead.

## How it behaves

- One form per call. Wrap several in `(do ...)` or thread them with `(->> ...)`.
- `*out*` and `*err*` are captured and returned above the value.
- Errors are returned as text (`Error: ...` plus the innermost cause and a few
  stack frames); a failing form does not abort the agent's turn.
- Huge results are cut at 64 KiB and huge values are printed with
  `*print-length*`/`*print-level*` bounds, so a runaway sequence cannot flood
  the conversation.
- The `namespace` argument evaluates elsewhere, for example
  `{"namespace": "clojure.string"}`. A namespace that is not loaded yet is
  required from the classpath first.

## Encouraging the agent to use it

The tool is described to the model in
`src/clai/agent/tools/builtin/eval.clj` (`tool-description`), and that text is
most of what decides whether the model reaches for it. If your own agent
under-uses the tool, mention it in the system prompt too, for example:

```
Prefer eval_clojure over guessing. Use (tools) to see what you can call and
(:cwd (context)) instead of assuming the working directory.
```

The agent's tool list and descriptions live in `clai.agent.tools.builtin`; see
[Plugins](plugins.md) if you want to add tools of your own, and
[Browsing Clojure namespaces](namespaces.md) for the same introspection from
the web UI.
