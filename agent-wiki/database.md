# Local PostgreSQL database

clai stores sessions, messages, agent configurations, registered git
repositories, and [notes](notes.md) in PostgreSQL. `docker-compose.yml` starts a development database
that already matches clai's defaults, so nothing has to be configured to get
going.

## Start the database

```sh
docker compose up -d
lein run
```

That brings up two things:

- `postgres` — the server, listening on `127.0.0.1:5432`.
- `schema` — a one-shot job that runs on every `docker compose up`. It enables
  the `pg_textsearch` extension and applies `schema/schema.sql` (the same
  idempotent DDL the daemon applies on startup), so an existing data directory
  picks up new tables and columns too.

Defaults, which clai reads out of the box:

| Setting  | Value      |
| -------- | ---------- |
| host     | `localhost`|
| port     | `5432`     |
| database | `clai`     |
| user     | `clai`     |
| password | `secret`   |

Override them with `CLAI_POSTGRES_DATABASE`, `CLAI_POSTGRES_USER`,
`CLAI_POSTGRES_PASSWORD`, and `CLAI_POSTGRES_PORT` if you need to, and point
clai at a different server entirely with the `:postgres` section of `clai.edn`
(see `config.example.edn`).

Data lives in the bind mount `./local/postgres`, so `docker compose down` and
recreating the containers do not lose your sessions. `docker compose down -v`
and deleting that directory do.

Because the container has no TLS, leave `:ssl-mode` unset (the driver default)
or set it to `"prefer"`/`"disable"`; `"require"` fails to connect.

## Full-text search with pg_textsearch

The `postgres` service is not stock PostgreSQL: it is built from
`docker/postgres/Dockerfile`, which compiles the
[pg_textsearch](https://github.com/timescale/pg_textsearch) extension (BM25
relevance-ranked full-text search) into the same image. The extension is
already loaded and created for you, so you can use it from the REPL:

```sql
CREATE INDEX session_title_search ON agent_session USING bm25(title)
    WITH (text_config = 'english');

SELECT id, title
  FROM agent_session
 ORDER BY title <@> 'git repositories'
 LIMIT 10;
```

`<@>` returns a negative BM25 score, so `ORDER BY ... <@>` ascending puts the
most relevant rows first. Notes ship with such an index already: see
[notes.md](notes.md#search). Boolean filtering, partial and expression indexes,
and explicit index selection with `to_bm25query(term, index)` all work as
documented in the extension's README.

The extension needs three things, all of which the compose file takes care of:

- the shared library in the server's `$libdir` — done by the image build;
- `shared_preload_libraries = 'pg_textsearch'` before the server starts — done
  with a server argument, so it also applies to an existing data directory;
- `CREATE EXTENSION pg_textsearch` in the database — done by the `schema`
  service.

`schema/schema.sql` deliberately does not create the extension, so the storage
DDL stays applicable to a server that does not have it installed.

### Upgrading the extension

The version is pinned by `PG_TEXTSEARCH_VERSION` in
`docker/postgres/Dockerfile`. After changing it, rebuild:

```sh
docker compose build postgres && docker compose up -d
```

Read the release's `UPGRADING.md` first: some versions require a `REINDEX` of
existing BM25 indexes, which the build cannot do for you.

### Upgrading PostgreSQL

PostgreSQL 18 keeps its clusters in a versioned subdirectory of the mount
(`/var/lib/postgresql/18/docker`), so bumping the major version in the
Dockerfile initializes a fresh, empty cluster next to the old one instead of
failing to start. To keep the sessions in the old cluster, dump them first:

```sh
docker compose exec -T postgres pg_dump -U clai -d clai \
    --clean --if-exists > clai-dump.sql
```

then bump the `FROM postgres:<version>-alpine` lines in
`docker/postgres/Dockerfile`, run

```sh
docker compose build postgres && docker compose up -d
```

and load the dump into the new, empty database, over the tables the `schema`
service just created (`--clean --if-exists` drops them first):

```sh
docker compose exec -T postgres psql -U clai -d clai -v ON_ERROR_STOP=1 \
    < clai-dump.sql
```
