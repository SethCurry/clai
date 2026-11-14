# Notes

`clai` stores notes in PostgreSQL: a note is a titled document with a mandatory
`title` and `content`, and notes can link to each other directionally. Note
content is indexed for full-text search, so a note can be found by what it says
rather than only by its id.

The tables are created by the DDL in [`schema/schema.sql`](../schema/schema.sql),
which `clai.daemon.postgres/ensure-schema!` applies (and which `docker compose
up` applies through its one-shot `schema` service).

| Table       | Columns                                                            |
| ----------- | ------------------------------------------------------------------ |
| `note`      | `id`, `title`, `content`, `created_at`, `updated_at`               |
| `note_link` | `from_note_id`, `to_note_id`, `created_at`                         |

`note_link` points from `from_note_id` to `to_note_id`, so a link has a
direction: "note 1 cites note 2" is not the same row as "note 2 cites note 1".
The pair is the table's primary key (a note cannot link to the same note
twice), both columns are foreign keys to `note(id)` with `ON DELETE CASCADE`,
and deleting a note therefore deletes the links that point at it.

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
- **Read** — click a note in the list. The right-hand pane renders its Markdown
  and lists its **Links** (notes it points at) and **Backlinks** (notes that
  point at it). Each chip opens that note, so you can follow links in either
  direction.
- **Create** — **+ New note** opens an editor for the title, the Markdown
  content, and the outgoing links. Links are picked from a list of the stored
  notes, and the chips can be removed again before saving.
- **Edit** — **Edit** on an open note reopens the same editor. Saving replaces
  the note, including its links, so removing every chip clears its outgoing
  links.
- **Delete** — **Delete** asks for confirmation and removes the note along with
  the links pointing at it.

`Ctrl`/`Cmd`+`Enter` saves from inside the editor. A blank title or a blank
content is refused before the request is sent, and the daemon's own validation
errors appear under the form (and in the banner at the top of the page).

## In the agent

The chat agent has five built-in tools for the same store. They are
part of the default tool set, so a conversation can use them without any
setup beyond the PostgreSQL connection every session already needs:

| Tool           | Arguments                                    | Does                                                      |
| -------------- | -------------------------------------------- | --------------------------------------------------------- |
| `notes_list`   | none                                         | list every note, newest edit first, with ids and previews |
| `notes_search` | `query`, optional `limit` (default 20, max 100) | search note content, BM25-ranked when available        |
| `notes_read`   | `id`                                         | one note with its content, links, and backlinks           |
| `notes_update` | `id`, and any of `title`, `content`, `links` | change only the fields given                              |
| `notes_delete` | `id`                                         | delete the note and the links pointing at it              |

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
fields you give, so renaming a note does not resend its content. `links` is
an array of note ids and replaces the outgoing links wholesale -- pass `[]`
to remove them. Titles and content are trimmed, a blank one is rejected, and
a link to a note that does not exist fails the update.

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
                                      :content "This note points at the ranking note."
                                      :links   [(:noteId ranking)]}))
;; => {..., :links ["1"], :backlinks []}

(notes/load-note client (:noteId ranking))
;; => {..., :links [], :backlinks ["2"]}

(notes/list-notes client)                  ; every note, newest edit first
(notes/search-notes client {:q "ranked"})  ; BM25-ranked matches
(notes/search-notes client {:q "ranked" :limit 5})
```

Titles and content are trimmed, and a blank one is rejected. `:links` names
the notes this one links to; it may hold ids as numbers or as strings, and is
replaced wholesale on update:

```clojure
(notes/update-note client "1" {:title   "BM25 ranking"
                               :content "Notes are ranked by BM25 relevance."
                               :links   ["2"]})

(notes/delete-note client "1")   ; also deletes the links pointing at note 1
```

Because a link target must be a stored note, linking to a missing id fails and
rolls the whole update back. Updating a note without `:links` leaves it with no
outgoing links, since the note is replaced as a whole.

## HTTP API

The daemon (`lein run web`, which serves the web UI and its API) exposes the
same operations as JSON under `/api/notes`:

| Method   | Path                                | Does                                     |
| -------- | ----------------------------------- | ---------------------------------------- |
| `GET`    | `/api/notes`                        | list notes, newest first                 |
| `POST`   | `/api/notes`                        | create a note                            |
| `GET`    | `/api/notes/<id>`                   | read one note with its links/backlinks   |
| `PUT`    | `/api/notes/<id>`                   | replace a note                           |
| `DELETE` | `/api/notes/<id>`                   | delete a note and its incoming links     |
| `GET`    | `/api/notes/search?q=<text>&limit=<n>` | search notes by relevance             |

The list and search responses wrap their results in a `notes` key; single-note
responses are the note itself. `POST` and `PUT` take a JSON body with `title`,
`content`, and an optional `links` array.

```sh
# create
curl -s -X POST localhost:8080/api/notes \
  -H 'content-type: application/json' \
  -d '{"title": "BM25", "content": "Notes are ranked by BM25."}'

# read
curl -s localhost:8080/api/notes/1

# update (links replace the note's outgoing links)
curl -s -X PUT localhost:8080/api/notes/2 \
  -H 'content-type: application/json' \
  -d '{"title": "Links", "content": "Points at BM25.", "links": ["1"]}'

# search
curl -s 'localhost:8080/api/notes/search?q=ranked&limit=5'

# delete
curl -s -X DELETE localhost:8080/api/notes/2
```

A missing note answers `404`, and a rejected body (blank title or content, a
link to a note that does not exist, a blank `q`) answers `400` with an `error`
message.
