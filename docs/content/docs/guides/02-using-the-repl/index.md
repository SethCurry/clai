---
title: "Using the REPL"
description: "Drive a clai session from a Clojure REPL with new!, prompt!, and resume!, and learn how session namespaces and shell helpers keep work addressable."
summary: "The agent as a handful of composable Clojure functions."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 2
toc: true
---

`lein repl` starts in the `user` namespace, which loads the harness, redirects
its logs to a file so they do not interleave with the conversation, and refers
the REPL helpers and the shell integration namespaces. The whole agent is then
a handful of functions.

## The core helpers

```clojure
(new!)                    ; start a session; the REPL moves into sessions.<id>
(new! "/path/to/project") ; …with an explicit working directory

(prompt! "Why does this test fail?")
;; clai> Reading the failure and the implementation...
;;   [tool] read_file completed
;;   [tool] eval_clojure completed
;;   [title] Failing test investigation

(history!)                ; reprint the current conversation
(resume!)                 ; resume the current or most recent session
(resume! "9")             ; resume a specific session
```

`new!` and `resume!` both move the REPL into the session's namespace. That
namespace is `sessions.<id>`, seeded from `user` with the same helpers and
aliases, plus its own `current-session` and `*cwd*` Vars. Creating or resuming
a session therefore changes where subsequent evaluations run.

`prompt!` streams events as they arrive: model text, thoughts, tool calls and
their output, generated session titles, and compaction summaries. The REPL
reads like a conversation rather than a blocking call.

## Session namespaces

Every session gets its own namespace for three reasons:

- Two sessions do not fight over `*cwd*` or over a helper Var one of them
  redefined.
- A session namespace survives for the life of the process, so returning to it
  restores its definitions along with its conversation.
- The web REPL panel defaults to the same namespace, so the panel and
  `clai.repl` address the same session, working directory, and definitions.

Namespace creation goes through `clai.repl/namespace-builder`, which is a
plugin hook. A plugin can seed session namespaces with extra Vars or replace
the builder entirely.

```clojure
(in-ns 'sessions.12)      ; or just stay where new!/resume! put you
current-session           ; the session map selected in this namespace
*cwd*                     ; the directory shell helpers resolve against
```

## Shell helpers

The `clai.shell.*` namespaces are ordinary, composable Clojure functions for
`git`, `docker`, `task`, and the browser. They are what the agent's
`run_command` and `eval_clojure` reach for, and they are what you type in the
REPL.

```clojure
(->> (git/status {:dir "/repo"}) :untracked (map :path))
(-> (docker/inspect "my-container") first :state)
(binding [*cwd* "/repo"] (git/status) (task/list)) ; in a session ns
($ "lein test")           ; anything, via sh -c
(help! git/commit)        ; the docstring of a Var, without quoting it
```

Each helper returns a result map (`{:command :exit :out :err :success? ...}`)
instead of throwing, so it composes in a pipeline. The `!` variants throw when
you want that instead. Because a session namespace carries its own `*cwd*` and
`current-session`, the same helper called from the agent and from your REPL in
that session resolves to the same directory.

## The agent's REPL and yours

`eval_clojure` does not shell out to a Clojure. It evaluates the form in this
JVM, in `clai.home.core` by default. That namespace is seeded with helpers for
exactly this situation:

```clojure
;; what the agent runs, and what prints in your REPL
(help)                     ; a cheat sheet of what this namespace offers
(tools)                    ; the tool registry of the calling agent, live
(context)                  ; this session's id, model, cwd
(history)                  ; the stored messages of this session
(vars 'clai.util)          ; the Vars of a namespace in this process
(var-detail "clai.util" "error-message")
```

During the call, the session context is bound to `*context*`, so evaluated
code can ask what session it is running in. Everything it defines stays: a
`def` made by one tool call is there in the next one. And `clai.home.core` is
an ordinary namespace in your JVM. You can `in-ns` into it, or name a Var
fully qualified, and inspect exactly what the agent left behind.

The agent is encouraged to `def` values worth keeping rather than hiding them
in a `let`, so a `(def ...)` appears in transcripts where you would not expect
it. A `def` is a shared scratchpad that both of you can see.

## A worked example

```clojure
(new! "/home/me/code/clai")

(prompt! "Find the function that renders tool calls in the REPL, then make
          the completed status print in green.")

;; The agent reads src/clai/repl.clj, edits it, reloads the namespace, and
;; evaluates a small call to check the change. You can load and inspect the
;; same code while it is live:

(require 'clai.repl :reload)
(clojure.repl/source clai.repl/prompt!)
```

Nothing about that is special-cased. A file write and `require ... :reload`
both land in the running system, which is why extending the harness is a
conversation rather than a release.
