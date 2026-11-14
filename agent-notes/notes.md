# Notes

## Decision

Notes are first-class storage, not session history: a `note` row is a titled
document (`title` and `content`, both `NOT NULL` and both rejected when blank),
and `note_link` records directional links between them. The user asked for the
links table "to be used to track links"; the storage namespace therefore reads
and writes it, so it is not dead weight:

- `clai.daemon.postgres.notes/create-note` and `update-note` accept an optional
  `:links` vector and replace the note's outgoing links in the same
  transaction.
- `load-note` returns `:links` (outgoing) and `:backlinks` (incoming), which is
  the pair a note view needs.
- `list-notes` and `search-notes` return the plain note rows (no links), so a
  list stays one query instead of 1 + 2N.

Direction (rather than a symmetric "related notes" table) means one row per
`from -> to` pair, with the pair as the primary key: no duplicate links, no
link id, and both columns cascade on note delete. Backlinks are served by the
`note_link_to_note_idx` index; forward lookups use the primary key.

Link targets must exist. `insert-link-sql` uses
`INSERT ... SELECT ?, id FROM note WHERE id = ? RETURNING to_note_id`, so an
unknown target returns no row instead of raising a foreign key error, and
`replace-links!` turns that into an `ex-info` (HTTP 400) that rolls the whole
update back.

## Search and the BM25 index

`schema/schema.sql` creates `note_content_bm25_idx`
(`USING bm25(content) WITH (text_config = 'english')`) inside a `DO` block that
first checks `pg_extension` for `pg_textsearch`. `ensure-schema!` applies the
DDL on every process start, and the file deliberately stays applicable to a
server without the extension (see `agent-wiki/database.md`), so the index has
to be conditional.

Search has to match that:

- With the extension, `search-notes` runs
  `content <@> to_bm25query(?, 'note_content_bm25_idx') < 0`, ordered by the
  same expression. The index is named explicitly because `content <@> ?` with a
  bound parameter fails ("no BM25 index found for text <@> text expression") —
  only a literal or a `bm25query` value resolves the operator. Scores are
  negative, and `0` means "no match", hence the `< 0` filter.
- Without the extension, it falls back to
  `position(lower(?) in lower(content)) > 0` ordered by `updated_at`. No
  ranking, but no error either. Chosen over `ILIKE '%' || ? || '%'` so user
  input cannot inject `%`/`_` wildcards.

Which mode a datasource uses is remembered in the private `bm25-datasources`
atom (positive answers only, so installing the extension later is picked up).

## HTTP surface

Six routes in `clai.daemon/http.clj`, following the git-repo pattern
(route -> `clai.agent.core` wrapper -> storage namespace):

    GET    /api/notes
    POST   /api/notes
    GET    /api/notes/search?q=&limit=
    GET    /api/notes/<id>
    PUT    /api/notes/<id>
    DELETE /api/notes/<id>

`/api/notes/search` is matched before the `/api/notes/<id>` regex, which would
otherwise read `search` as an id. A blank or missing `q` is a 400 with "A
search query is required" rather than a storage validation error.

The agent wrapper exists for consistency with git repos/agent configs (and
gives the routes something tests can `with-redefs`). The chat loop does not
use the wrappers: its tools call `clai.daemon.postgres.notes` directly (see
below).

## Agent tools

`src/clai/agent/tools/builtin/notes.clj` adds five built-in tools --
`notes_list`, `notes_search`, `notes_read`, `notes_update`, and
`notes_delete` -- which `clai.agent.tools.builtin/built-in-tools` appends to
the default set. A session's `:tools` selection can still drop them like any
other built-in.

The handlers call `clai.daemon.postgres.notes` directly with `(:postgres ctx)`.
Going through `clai.agent.core` would be a circular require (`agent.core`
already requires the built-in tools), and going through `/api/notes` would
need the daemon running for a CLI session. A context without a client throws
an `ex-info`, which `clai.agent.tools.invoke` turns into `Error: ...` for the
model.

`notes_update` is a partial update: storage replaces the whole note, so the
tool reads the stored note and merges the given fields over it. An omitted
`links` therefore keeps the stored links, while an explicit `[]` clears them.
`links` accepts one id or an array, with ids as JSON numbers or strings, since
models are inconsistent about both.

`notes_read` renders timestamps, links, backlinks, and the content, cutting
content past 20,000 characters (there is no paging argument). List and search
results render a 200-character one-line preview per note instead of the
content. `common/format-timestamp` and `common/truncate` were moved out of
`openproject.clj` so the two tool namespaces share them.

Tests in `test/clai/agent/tools/builtin/notes_test.clj` `with-redefs` the
storage functions, so they need no database; the storage integration round
trip stays in `notes_test.clj` under `CLAI_TEST_POSTGRES_URL`.

## Shared schema readiness

`clai.daemon.postgres/ready` now holds the per-client "schema ensured" cache
that `sessions.clj` and `git.clj` each carried a copy of; `notes.clj` uses it
too. Clients are still not touched until the first storage operation.

## Web UI

The Notes page (`web/src/components/NotesPage.jsx`, reachable from the topbar)
is a two-column page in the git-repo page's shape: the searchable list on the
left, the open note -- or its editor -- on the right.

- `web/src/useNotes.js` owns the data. It keeps the full list (`stored`), the
  search results (`results`, null when no query is active), and the selected
  note's detail. `notes` is `results ?? sortNotes(stored)`, which is what the
  list renders.
- Search is debounced 250ms in a `useEffect` keyed on the query, with an
  `AbortController` per query so a slow response cannot overwrite newer
  results. It asks for `limit=50` rather than the endpoint's default 20.
- `select` guards against out-of-order detail responses with a sequence ref.
  `saveNote` replaces the row in both lists and selects the saved detail;
  `deleteNote` removes the row from both and clears the detail when it was the
  open one. That is why the hook returns the raw `stored` list too: the link
  picker must offer every note, not just the current search's matches.
- `web/src/notes.js` holds the pure helpers: `formToNoteInput` mirrors
  `prepare-note` (trim, reject blanks, de-duplicate link ids), `noteToForm`
  round-trips a detail, `linkCandidates` filters the picker, and `sortNotes`
  re-sorts after a write. `web/src/notes.test.js` covers them.
- The editor always sends `links`, even when empty: `PUT` replaces the whole
  note, so omitting `links` would silently drop the existing ones. (The server
  treats a missing key the same as an empty list, but being explicit keeps the
  client honest.)
- Form CSS reuses the `.config-form` classes the git page already reuses; the
  page shell uses `.list-page` / `.list-panel`, renamed from `git-page` /
  `git-panel` when the notes page started sharing them.

## Files

- `schema/schema.sql` — `note`, `note_link`, `note_content_bm25_idx`, and the
  `note_link_to_note_idx` backlink index.
- `src/clai/daemon/postgres/notes.clj` — storage, link handling, search.
- `src/clai/agent/core.clj` — `list-notes!`, `load-note!`, `create-note!`,
  `update-note!`, `delete-note!`, `search-notes!` (the HTTP routes' wrappers).
- `src/clai/agent/tools/builtin/notes.clj` — the agent's `notes_list`,
  `notes_search`, `notes_read`, `notes_update`, and `notes_delete` tools.
- `src/clai/daemon/http.clj` — the `/api/notes` routes and `note-path`.
- `test/clai/daemon/postgres/notes_test.clj`,
  `test/clai/agent/tools/builtin/notes_test.clj`, `test/clai/daemon/http_test.clj`
  — unit tests everywhere, plus an integration round trip gated on
  `CLAI_TEST_POSTGRES_URL` (run it with
  `CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai
  CLAI_POSTGRES_USER=clai CLAI_POSTGRES_PASSWORD=secret lein test`).
- `web/src/components/NotesPage.jsx`, `web/src/useNotes.js`,
  `web/src/notes.js` (`notes.test.js`) — the web Notes page.
- `web/src/format.js` (`format.test.js`) — shared `formatTimestamp` (moved out
  of `gitRepos.js`) and the list-row `formatRelativeTime`.
- `agent-wiki/notes.md` — the user documentation.
