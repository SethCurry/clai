# OpenProject tasks and comments

`clai` can read and write OpenProject work packages (tasks) and their
comments, so you can ask the agent to triage your backlog, log a status update,
or file a task from a REPL session.

Everything below is the behaviour of the built-in tools in
`src/clai/agent/tools/builtin/openproject.clj`.

## Configuration

Add an `:openproject` map to your config file (`./clai.edn`,
`~/.config/clai/config.edn`, or the path in `$CLAI_CONFIG`):

```clojure
{:openproject {:url     "https://openproject.example.com" ; instance root or /api/v3
               :api-key "opapi-..."                      ; API token -> bearer auth
               :project "my-project"                     ; default project for new tasks
               :type    "Task"}}                         ; default type for new tasks
```

- `:url` (or `:base-url`) is required. It may be the instance root or the
  full `/api/v3` API root.
- Authenticate with `:api-key` (an OpenProject API token, sent as a bearer
  token) or with `:username`/`:password` (HTTP basic auth). Read-only access to
  an instance that allows anonymous users works without credentials.
- `:project` and `:type` are defaults for `openproject_create_work_package`.
- `:timeout` is in milliseconds (default 30000).

Omitted keys fall back to the environment: `OPENPROJECT_URL` /
`OPENPROJECT_BASE_URL`, `OPENPROJECT_API_KEY` / `OPENPROJECT_TOKEN`,
`OPENPROJECT_USERNAME`, `OPENPROJECT_PASSWORD`, `OPENPROJECT_PROJECT`,
`OPENPROJECT_TYPE`, `OPENPROJECT_TIMEOUT`.

If OpenProject is not configured, the tools fail with a message telling you to
set `:openproject {:url ...}` or `OPENPROJECT_URL` — nothing else breaks.

To let a model see the tools, keep their ids enabled in `:tools` (by default
every registered tool is offered):

```clojure
{:tools ["openproject_list_projects"
         "openproject_list_work_packages"
         "openproject_create_work_package"
         "openproject_update_work_package"
         "openproject_list_comments"
         "openproject_add_comment"
         "openproject_update_comment"]}
```

## Ids and names

Every tool takes numeric ids, and most also accept names. Linked values
(project, type, status, priority, assignee, parent) may be given as a numeric
id, an API href, or a name; names are resolved case-insensitively against the
matching OpenProject collection, so `"status": "In progress"` works without a
lookup first.

List results always include the numeric ids you need for the write tools.

## Tools

### `openproject_list_projects`

Arguments: `query` (name/identifier substring), `page_size` (default 25, max
100), `offset` (page number). Returns one line per visible project with its id,
identifier, and web URL.

### `openproject_list_work_packages`

Arguments: `project`, `status`, `type`, `assignee`, `priority`, `subject`,
`filters` (raw OpenProject filter JSON), `sort_by` (e.g. `"updated_at desc"`),
`page_size`, `offset`.

With no filters OpenProject's own default applies — open work packages only.
Pass `"filters": "[]"` to include closed ones.

### `openproject_create_work_package`

Requires `subject` and a project (`project` argument or the configured
default). Optional: `description` (Markdown), `type`, `status`, `priority`,
`assignee`, `parent`, `start_date`, `due_date` (YYYY-MM-DD), `estimated_time`
(ISO-8601 duration such as `"PT2H"`).

### `openproject_update_work_package`

Requires `id`. Accepts the same writable fields as create, plus `project` to
move the task and `lock_version` to skip the automatic read. The current
`lockVersion` is fetched for you so OpenProject's optimistic locking succeeds.

### `openproject_list_comments`

Requires `id` (the work package). Optional `limit` keeps only the N most recent
entries; `include_changes` also lists the field changes recorded on the task,
which turns the output into the task's activity log.

Comments are printed oldest first, each with its **activity id**, author, and
timestamp:

```
Found 2 comment(s) on OpenProject work package 54 "Ship it":

comment 107 by Seth Curry on 2026-11-13 19:37 UTC
  First line of the comment,
  wrapped in Markdown.

comment 108 by Seth Curry on 2026-11-13 19:38 UTC
  second comment
  changes:
    - Subject changed from Old to Ship it
```

### `openproject_add_comment`

Requires `id` (the work package) and `comment` (the Markdown body). Returns the
new comment with its activity id, which you need to edit it later.

```
clai> add a comment to task 54 saying "Deployed to staging, looks good"
```

### `openproject_update_comment`

Requires `id` — the **comment (activity) id**, not the work package id — and
`comment`, the replacement Markdown body. OpenProject only lets the comment's
author (or an administrator) edit it, so other users get a 403.

## Trying it from the REPL

The tools are plain functions, so you can drive them without a model. Every
handler takes `(args ctx)` where `ctx` is `{:openproject {…}}`:

```clojure
(require '[clai.agent.tools.builtin.openproject :as op]
         '[clai.config :as config])

(def ctx {:openproject (:openproject (config/load-config))})

(op/list-work-packages-fn {:assignee "me" :sort_by "updated_at desc"} ctx)
(op/list-comments-fn {:id 54 :include_changes true} ctx)
(op/add-comment-fn {:id 54 :comment "Deployed to staging."} ctx)
(op/update-comment-fn {:id 109 :comment "Deployed to staging and prod."} ctx)
```

`(op/tools)` returns the tool maps (`:id`, `:handler`, `:spec`) that the agent
registry uses, in case you want to add them to a custom registry.

## Behaviour worth knowing

- Comment bodies and descriptions are Markdown. OpenProject returns both
  `:raw` and rendered `:html`; only the raw text is sent to the model.
- Timestamps are UTC, as reported by OpenProject.
- Reading a work package's activities fetches the whole list (the endpoint
  ignores `pageSize` and `filters`); `limit` is applied locally.
- Internal (private) comments need an OpenProject Professional plan and are not
  used by these tools.
- The OpenProject URL in results is the browser URL
  (`/work_packages/42`), not the API href.
