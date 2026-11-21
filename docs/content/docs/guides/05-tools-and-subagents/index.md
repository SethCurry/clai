---
title: "Tools and Sub-agents"
description: "See what clai's built-in tools do, how per-agent tool registries work, and how spawn_agent runs an isolated sub-agent with its own prompt and model."
summary: "Files, shell, Clojure evaluation, LSP, notes, OpenProject, and sub-agents."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 5
toc: true
---

The built-in tools are grouped by concern, and each group is a namespace under
`clai.agent.tools.builtin`. An agent configuration can enable a subset of
them; `clai.edn` can too.

| Tool                                                                                             | What it does                                                                                                                                                                                         |
| ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `read_file`, `write_file`, `list_directory`                                                      | Read and write files, resolved against the session's `cwd`. `read_file` takes an optional line range so a long file can be read in slices instead of flooding the history.                           |
| `run_command`                                                                                    | Run a shell command in the session's `cwd`, with output capped.                                                                                                                                      |
| `eval_clojure`                                                                                   | Evaluate Clojure in the harness's own JVM, in `clai.home.core`. Results persist between calls; the agent is encouraged to `def` values worth keeping so they are visible in your REPL too.            |
| `spawn_agent`                                                                                    | Run an isolated sub-agent with its own system prompt, model, tool subset, and working directory. Only its final reply comes back, so the parent's context stays small. Nesting is capped.            |
| `lsp_definition`, `lsp_references`, `lsp_hover`, `lsp_symbols`, `lsp_outline`, `lsp_diagnostics` | Navigation over `clojure-lsp`, kept warm per project. Beats grepping for `defn` when the agent needs to know which `error-message` on which line is meant.                                           |
| `notes_list`, `notes_search`, `notes_read`, `notes_update`, `notes_delete`                       | Titled Markdown notes in PostgreSQL, BM25-ranked.                                                                                                                                                    |
| `openproject_*`                                                                                  | List projects and work packages, create and update tasks, and read, add and edit task comments &mdash; when `:openproject` is configured.                                                            |

The OpenProject tools are `openproject_list_projects`,
`openproject_list_work_packages`, `openproject_create_work_package`,
`openproject_update_work_package`, `openproject_list_comments`,
`openproject_add_comment`, and `openproject_update_comment`, so an agent can
work a task the way you would.

## Tools are values

A tool is a map of `{:id :handler :spec}`: an LLM-facing JSON schema plus a
plain function `(fn [args ctx] ...)`. There is no global tool registry.

```clojure
(require '[clai.agent.tools.registry :as registry]
         '[clai.agent.tools.builtin :as builtin])

(def tools (builtin/built-in-registry))   ; an immutable map, owned by you
(def narrowed (registry/select-tools tools ["read_file" "eval_clojure"]))
```

Registries are immutable maps owned per agent, so two agents in one process
can offer different tool sets. Adding a tool is `assoc`.

`eval_clojure` can inspect the calling agent's live registry:

```clojure
(tools)          ; every tool this agent can use, sorted by id
(tool "read_file") ; the full JSON schema of one tool
```

## Sub-agents

`spawn_agent` starts a child agent with its own system prompt, model, tool
subset, tool-round cap, and working directory. It defaults to a focused
sub-agent prompt and to every tool except `spawn_agent` itself.

```clojure
{:prompt "Find every place the WebSocket protocol is documented."
 :system_prompt "You are a precise codebase researcher."
 :tools ["read_file" "list_directory" "lsp_references"]
 :model "openrouter:anthropic/claude-sonnet-4"
 :cwd "/home/me/code/clai"}
```

Only the child's final reply returns to the parent, so the parent's context
stays small. Nesting is capped.

## Behaviour around the tools

- **Compaction.** When a session's history outgrows its token budget, the agent
  summarizes it in one request and appends a `compaction` event. Reading the
  history skips the events the summary covers and keeps the system prompt, the
  summary, the newest messages, and the turn in progress.
- **Automatic titles.** A new session is named from its first prompt by one
  extra model call, so the sidebar is readable without typing titles.
- **Output caps.** Every tool result is capped (64 KiB by default) so a single
  runaway command cannot eat the context window.
- **Per-agent registries.** Tools are not global. Each agent owns a registry
  map, which is what makes a narrowly-tooled sub-agent, or two agents with
  different capabilities, possible in one process.

The [tool reference](/docs/reference/tools/) lists every tool id with its
arguments.
