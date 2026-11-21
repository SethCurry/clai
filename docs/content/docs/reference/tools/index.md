---
title: "Tools"
description: "Reference for every built-in clai tool: file and shell access, Clojure evaluation, sub-agents, clojure-lsp navigation, notes, and OpenProject work packages."
summary: "Every built-in tool id and its arguments."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 2
toc: true
---

A tool is a map of `{:id :handler :spec}`. The `:spec` is the JSON schema the
model sees; the `:handler` is `(fn [args ctx] ...)`. Registries are immutable
maps owned per agent, so enabling a subset for one agent does not affect
another.

## Files

| Tool             | Arguments                        | Notes                                                            |
| ---------------- | -------------------------------- | ---------------------------------------------------------------- |
| `read_file`      | `path`, `start_line?`, `end_line?` | Paths resolve against the session's `cwd`. Line ranges are 1-based and inclusive. |
| `write_file`     | `path`, `content`                | Writes the full text of the file.                                |
| `list_directory` | `path?`                          | Defaults to the session's `cwd`.                                 |

## Shell

| Tool          | Arguments | Notes                                                              |
| ------------- | --------- | ------------------------------------------------------------------ |
| `run_command` | `command` | Runs via the shell in the session's `cwd`; output is capped.       |

## Clojure

| Tool           | Arguments            | Notes                                                                                                                                 |
| -------------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `eval_clojure` | `code`, `namespace?` | Evaluates one form in the harness's JVM. Defaults to `clai.home.core`; a namespace that is not loaded is required from the classpath. |

`eval_clojure` code runs in the same process as the REPL and the web daemon.
Values defined with `def` persist between calls. The default namespace
provides `help`, `tools`, `tool`, `context`, `session`, `history`, `vars`,
`var-detail`, and `active-plugins`, plus aliases for the namespaces an agent
most often wants.

## Sub-agents

| Tool          | Arguments                                                                 | Notes                                                                                       |
| ------------- | ------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `spawn_agent` | `prompt`, `system_prompt?`, `tools?`, `model?`, `max_rounds?`, `cwd?`     | Runs a child agent and returns only its final reply. Nested sub-agents are allowed up to a small depth limit. |

The child defaults to every parent tool except `spawn_agent`, the parent's
model and working directory, and a focused sub-agent system prompt. Pass
`tools: []` for no tools. `max_rounds` is clamped to 32.

## clojure-lsp

All six tools use `clojure-lsp`, kept warm per project. Position-taking tools
accept `symbol`, or `file` plus a 1-based `line` and optional `column`.

| Tool              | Arguments                    | Notes                                                          |
| ----------------- | ---------------------------- | -------------------------------------------------------------- |
| `lsp_definition`  | `symbol?`, `file?`, `line?`, `column?` | Where a var or namespace is defined.                 |
| `lsp_references`  | `symbol?`, `file?`, `line?`, `column?` | Every place a var or namespace is used.              |
| `lsp_hover`       | `symbol?`, `file?`, `line?`, `column?` | Signature, docstring, and source location.           |
| `lsp_symbols`     | `query`, `limit?`            | Search vars and namespaces by name substring.                   |
| `lsp_outline`     | `file`                       | The namespaces and vars one file defines.                       |
| `lsp_diagnostics` | `file?`                      | Lint problems for one file, or the whole project when omitted.  |

Only the project's sources are indexed. For `clojure.core` or library vars,
`eval_clojure` with `(doc ...)` or `(source ...)` is the fallback.

## Notes

Notes are titled Markdown documents in PostgreSQL. Links are written as
`[[Note title]]` and read out of the content rather than stored separately.

| Tool           | Arguments             | Notes                                                              |
| -------------- | --------------------- | ------------------------------------------------------------------- |
| `notes_list`   | none                  | Most recently updated first, with id, title, time, and a preview.   |
| `notes_search` | `query`, `limit?`     | BM25-ranked when `pg_textsearch` is available, substring otherwise. |
| `notes_read`   | `id`                  | Content, links, backlinks; long content is truncated.               |
| `notes_update` | `id`, `title?`, `content?` | Partial update; omitted fields keep their stored value.        |
| `notes_delete` | `id`                  | Permanent.                                                          |

## OpenProject

These tools are available only when `:openproject` is configured.

| Tool                                | Arguments                                                                 |
| ----------------------------------- | -------------------------------------------------------------------------- |
| `openproject_list_projects`         | `query?`, `page_size?`, `offset?`                                          |
| `openproject_list_work_packages`    | `project?`, `status?`, `type?`, `assignee?`, `priority?`, `subject?`, `filters?`, `sort_by?`, `page_size?`, `offset?` |
| `openproject_create_work_package`   | `subject`, `project?`, plus `description`, `status`, `type`, `priority`, `assignee`, `parent`, `start_date`, `due_date`, `estimated_time` |
| `openproject_update_work_package`   | `id`, plus any writable field, `project?`, `lock_version?`                 |
| `openproject_list_comments`         | `id`, `limit?`, `include_changes?`                                         |
| `openproject_add_comment`           | `id`, `comment`                                                            |
| `openproject_update_comment`        | `id` (comment/activity id), `comment`                                      |

`openproject_list_work_packages` applies OpenProject's default open-work-package
filter when no filters are given; pass `filters: "[]"` to include closed work
packages too. Updates fetch the current lock version automatically when it is
not supplied.

## Limits

- Every tool result is capped (64 KiB by default) so a runaway command cannot
  consume the context window.
- `:max-tool-rounds` caps consecutive tool-call rounds per prompt.
- A session's history is compacted when it exceeds `:compact-after-tokens`.
