# OpenProject tools

## Where

- `src/clai/agent/tools/builtin/openproject.clj` — all OpenProject tools,
  client construction, HTTP plumbing, name resolution, formatting.
- `test/clai/agent/tools/builtin/openproject_test.clj` — tests; they redefine
  `clj-http.client/request`, so no network is used.
- Config: `:openproject` map (`src/clai/config.clj`,
  `config.example.edn`); env fallbacks `OPENPROJECT_*`.
- User docs: `agent-wiki/openproject.md`.

Tools: `openproject_list_projects`, `openproject_list_work_packages`,
`openproject_create_work_package`, `openproject_update_work_package`,
`openproject_list_comments`, `openproject_add_comment`,
`openproject_update_comment`.

## API v3 facts (verified against live OpenProject 17.9.7)

Comments are journal entries ("activities"). Change-only entries are
`_type "Activity"`, comments are `_type "Activity::Comment"` and may also carry
`details` (field changes) in the same entry.

- Read: `GET /api/v3/work_packages/{id}/activities` → collection of activities,
  oldest first. **The endpoint ignores `pageSize` and `filters`** (verified:
  `pageSize=1` still returned both entries; a `type` filter changed nothing), so
  `list-comments` reads everything and applies `limit` locally.
- Create: `POST /api/v3/work_packages/{id}/activities` with
  `{"comment": {"raw": "..."}}` → 201 with the created activity. A bare string
  here returns 400 `comment is invalid`.
- Update: `PATCH /api/v3/activities/{id}` with **`{"comment": "..."}` (a bare
  string)** → 200. The nested map form that create wants returns 400
  `comment is invalid` on this version. Asymmetry is documented in
  `update-comment-fn`; if a future instance flips it, that's the place to fix.
- `_links.user` on activities has only an `href` (no `title`), unlike the work
  package's `author` link. Author names are resolved with a per-call memoized
  `GET /api/v3/users/{id}` (`user-name-fn`), falling back to `user <id>` if that
  read is forbidden.
- `internal: true` (private comments) → 400 `Internal Journal requires at least
  the Professional enterprise plan.` plus "Internal Journal is disabled for this
  project." Not supported on purpose.
- There is no public delete-comment endpoint; editing is the only mutation of an
  existing comment.
- `updatedAt` differs from `createdAt` by milliseconds on freshly created
  comments, so it cannot be used to label a comment "edited". `version` stays 1
  across edits. No "(edited)" marker is rendered.

## Conventions in the file

- Private helpers take the client first; `cond->`-friendly helpers such as
  `assoc-link` take the payload first.
- `resource-id` validates numeric ids and names the resource in the error
  (`"work package"` / `"comment"`) so the message tells the model what to fix.
- Rendering helpers (`format-activity`, `format-timestamp`, `indent-lines`)
  live in the Formatting section; `user-name-fn` sits with the other
  request-making resolution helpers.

## Live verification

Probing was done on a scratch work package in the `Inbox` project, which was
then deleted (`DELETE /api/v3/work_packages/54` → 204), so nothing was left
behind on the user's instance.
