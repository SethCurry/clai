# Local database: pg_textsearch in the Postgres container

## Decision

The development database in `docker-compose.yml` runs a custom image,
`docker/postgres/Dockerfile` (`clai-postgres:18-pg_textsearch`), instead of the
stock `postgres:18-alpine`. The only difference is the
[pg_textsearch](https://github.com/timescale/pg_textsearch) extension, compiled
from its release tarball in a throwaway build stage and copied into the same
base image the server runs.

Why a custom image: pg_textsearch ships no apk package (only glibc prebuilt
zips and source), so it must be compiled against the exact server it loads
into. Sharing the base image between the build stage and the runtime stage
guarantees matching ABI, include paths, and `$libdir`; `make install
DESTDIR=/install` lets the runtime stage copy one tree without hard-coding
PostgreSQL's prefix.

## Three things the extension needs

1. `pg_textsearch.so` plus its control file and SQL scripts in the server's
   `$libdir`/`$sharedir` — the image build.
2. `shared_preload_libraries = 'pg_textsearch'` before the server starts; its
   `_PG_init` allocates shared memory and errors out otherwise. Passed as a
   `command: [postgres, -c, shared_preload_libraries=pg_textsearch]` argument
   rather than editing `postgresql.conf` in the mounted data directory, because
   a server argument also applies to clusters that already exist.
3. `CREATE EXTENSION pg_textsearch` per database — the `schema` service runs
   `--command=CREATE EXTENSION IF NOT EXISTS pg_textsearch` before
   `--file=/schema.sql` (psql runs multiple `-c`/`-f` in order).

`schema/schema.sql` deliberately does *not* create the extension: it is also
applied by `clai.daemon.postgres/ensure-schema!` against servers the user
supplies, which may not have pg_textsearch installed.

## Build gotchas

- PGXS tries to emit LLVM bitcode because the official image is built
  `with_llvm=yes`, and then needs the clang that built the server
  (`make: clang-21: No such file or directory`). `make with_llvm=no` skips
  bitcode; it only affects JIT inlining of the extension's functions.
- `bm25` indexes require `WITH (text_config = '...')`; the error hint names the
  parameter. Verified against 1.5.1 (extension version is pinned by the
  `PG_TEXTSEARCH_VERSION` build arg).
- Verified end to end: image build, preload after `docker compose restart`,
  `CREATE EXTENSION`, `CREATE INDEX ... USING bm25`, and `ORDER BY col <@>
  'terms'` scoring (negative scores, ascending = most relevant first).

## PostgreSQL 18 data directory

`postgres:18-alpine` changed `PGDATA` to `/var/lib/postgresql/18/docker` and
declares `/var/lib/postgresql` as its volume, so the compose bind mount is
`./local/postgres:/var/lib/postgresql` (it used to mount
`/var/lib/postgresql/data`). Anything under `./local/postgres` from a 17-era
cluster is a different, incompatible cluster: PG 18 initializes a fresh empty
cluster in the versioned subdirectory beside it, so dump the old one first if
its sessions matter (`pg_dumpall`; see `agent-wiki/database.md`).

## Where

- `docker-compose.yml` — `postgres` (build + `-c` preload) and `schema`
  (extension + DDL) services, plus the usage/upgrade comments.
- `docker/postgres/Dockerfile` — the image, with the pinned extension version.
- `agent-wiki/database.md` — user-facing instructions.
