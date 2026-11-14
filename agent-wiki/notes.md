# Notes

`clai` stores notes in PostgreSQL: a note is a titled Markdown document with a
mandatory `title` and `content`. Note content is indexed for full-text search,
so a note can be found by what it says rather than only by its id.

Notes link to each other by writing the other note's title in double square
brackets:

```markdown
Notes are ranked by [[BM25 ranking]]; see also [[pg_textsearch]].
```

A title is therefore how notes address each other, and it is unique across the
store. Titles are compared case-insensitively and ignoring surrounding space,
so `[[bm25 ranking]]` and `[[BM25 ranking]]` name the same note. Creating or
renaming a note to a title another note already uses is rejected.

Links are not stored separately. They are read back out of the content every
time a note is loaded, which means a link always matches the text, a link to a
note that does not exist yet works as soon as that note is written, and
deleting a note needs no link cleanup.

The table is created by the DDL in [`schema/schema.sql`](../schema/schema.sql),
which `clai.daemon.postgres/ensure-schema!` applies (and which `docker compose
up` applies through its one-shot `schema` service).

| Table  | Columns                                              |
| ------ | ---------------------------------------------------- |
| `note` | `id`, `title`, `content`, `created_at`, `updated_at` |

`schema/schema.sql` also creates `note_title_unique_idx`, the unique index over
`lower(trim(title))` that keeps titles addressable, and `note_content_bm25_idx`,
the search index described below.

## Search

`schema/schema.sql` creates a BM25 index over `note.content`:

```sql
CREATE INDEX note_content_bm25_idx ON note USING bm25(content)
    WITH (text_config = 'english');
```

It is created only when the [`pg_textsearch` extension](database.md#full-text-search-with-pg_textsearch)
is installed, which the development database in `docker-compose.yml` takes care
of. On a server without the extension the same search function falls back to a
case-insensitive substring match over the content, so search keeps working —
just without relevance ranking.

## In the web UI

`lein run web` serves the same notes in the browser. Open **Notes** in the top
bar (next to **Sessions** and **Git repos**) and you get the note list on the
left and the open note on the right.

- **Search** — type in the field above the list. Every pause sends the text to
  `/api/notes/search`, so matches come back ranked by relevance rather than
  filtered by hand; an empty field shows the whole list again, newest edit
  first. The count under the field says how many notes matched.
- **Read** — click a note in the list. The right-hand pane renders its Markdown.
  A `[[Title]]` reference whose note exists becomes a link you can click; one
  whose note has not been written yet stays as literal text. The **Links** and
  **Backlinks** sections under the content list the notes this one points at
  and the notes that point at it, as chips that open the note.
- **Create** — **+ New note** opens an editor for the title and the Markdown
  content. There is no link picker: write `[[Another note's title]]` in the
  content. The **Links** section under the editor previews the references it
  found, with a dashed chip for a title that does not exist yet.
- **Edit** — **Edit** on an open note reopens the same editor. Saving replaces
  the note, so links follow whatever `[[Title]]` references the new content
  has.
- **Delete** — **Delete** asks for confirmation and removes the note. Links to
  it in other notes simply stop resolving.

`Ctrl`/`Cmd`+`Enter` saves from inside the editor. A blank title or a blank
content is refused before the request is sent, and the daemon's own validation
errors (including a title another note already uses) appear under the form and
in the banner at the top of the page.

## In the agent

The chat agent has five built-in tools for the same store. They are
part of the default tool set, so a conversation can use them without any
setup beyond the PostgreSQL connection every session already needs:

| Tool           | Arguments                                       | Does                                                      |
| -------------- | ----------------------------------------------- | --------------------------------------------------------- |
| `notes_list`   | none                                            | list every note, newest edit first, with ids and previews |
| `notes_search` | `query`, optional `limit` (default 20, max 100) | search note content, BM25-ranked when available           |
| `notes_read`   | `id`                                            | one note with its content, links, and backlinks           |
| `notes_update` | `id`, and either or both of `title`, `content`  | change only the fields given                              |
| `notes_delete` | `id`                                            | delete the note                                           |

Tool ids are stable, so a `:tools` list in `clai.edn` can name them
(see [Models and providers](providers.md)):

```clojure
{:tools ["notes_list" "notes_search" "notes_read" "notes_update" "notes_delete"]}
```

The tools talk to PostgreSQL through the session's own `:postgres` client, so
they work in the CLI REPL and in the web chat without the HTTP daemon in
between. A session started without storage answers with a clear error
instead of failing.

`notes_update` is a partial update: it reads the note first and merges the
fields you give, so renaming a note does not resend its content. Links follow
from the content, so there is no `links` argument: add, change, or remove a
`[[Title]]` reference in the content and save. Titles and content are trimmed,
a blank one is rejected, and a title another note already uses is refused.

`notes_read` cuts content past 20,000 characters with a
`[truncated at 20000 characters]` marker; the whole note is still stored
and visible in the web UI. List and search results show a one-line,
200-character preview rather than the whole content.

## From the REPL

```clojure
(require '[clai.daemon.postgres :as pg]
         '[clai.daemon.postgres.notes :as notes])

(def client (pg/make-client))   ; localhost:5432/clai by default

(def ranking (notes/create-note client {:title   "BM25"
                                        :content "Notes are ranked by BM25."}))
;; => {:noteId "1" :title "BM25" :content "Notes are ranked by BM25."
;;     :links [] :backlinks [] :createdAt "..." :updatedAt "..."}

(def links (notes/create-note client {:title   "Links"
                                      :content "This note points at [[BM25]]."}))
;; => {..., :links ["1"], :backlinks []}

(notes/load-note client (:noteId ranking))
;; => {..., :links [], :backlinks ["2"]}

(notes/list-notes client)                  ; every note, newest edit first
(notes/search-notes client {:q "ranked"})  ; BM25-ranked matches
(notes/search-notes client {:q "ranked" :limit 5})
```

Titles and content are trimmed, and a blank one is rejected. `:links` and
`:backlinks` are outputs, not inputs: they are the ids resolved from the
`[[Title]]` references in the content. Updating a note replaces its content and
so replaces its links:

```clojure
(notes/update-note client "1" {:title   "BM25 ranking"
                               :content "Notes are ranked by BM25; see [[Links]]."})

(notes/delete-note client "1")   ; links to note 1 stop resolving
```

A reference to a title with no note is simply left unresolved, which is how a
link can be written before its target exists.

## HTTP API

The daemon (`lein run web`, which serves the web UI and its API) exposes the
same operations as JSON under `/api/notes`:

| Method   | Path                                | Does                                     |
| -------- | ----------------------------------- | ---------------------------------------- |
| `GET`    | `/api/notes`                        | list notes, newest first                 |
| `POST`   | `/api/notes`                        | create a note                            |
| `GET`    | `/api/notes/<id>`                   | read one note with its links/backlinks   |
| `PUT`    | `/api/notes/<id>`                   | replace a note                           |
| `DELETE` | `/api/notes/<id>`                   | delete a note                            |
| `GET`    | `/api/notes/search?q=<text>&limit=<n>` | search notes by relevance             |

The list and search responses wrap their results in a `notes` key; single-note
responses are the note itself. `POST` and `PUT` take a JSON body with `title`
and `content`; links are whatever `[[Title]]` references the content has.

```sh
# create
curl -s -X POST localhost:8080/api/notes \
  -H 'content-type: application/json' \
  -d '{"title": "BM25", "content": "Notes are ranked by BM25."}'

# read
curl -s localhost:8080/api/notes/1

# update (links follow the content, so this points at note 1)
curl -s -X PUT localhost:8080/api/notes/2 \
  -H 'content-type: application/json' \
  -d '{"title": "Links", "content": "Points at [[BM25]]."}'

# search
curl -s 'localhost:8080/api/notes/search?q=ranked&limit=5'

# delete
curl -s -X DELETE localhost:8080/api/notes/2
```

A missing note answers `404`, and a rejected body (a blank title or content, a
title another note already uses, a blank `q`) answers `400` with an `error`
message.
