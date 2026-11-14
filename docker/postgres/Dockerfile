# PostgreSQL 18 with the pg_textsearch extension compiled in.
#
# pg_textsearch (https://github.com/timescale/pg_textsearch) provides
# BM25-relevance-ranked full-text search: `ORDER BY content <@> 'terms'` and
# `CREATE INDEX ... USING bm25(...)`. It has no apk package, so it is built
# from source here.
#
# The build stage and the runtime stage share the same base image, so the
# extension is always compiled against the exact PostgreSQL it is loaded into
# (ABI, include paths and $libdir all line up). The compiler toolchain stays
# in the throwaway build stage.
#
# docker-compose.yml builds this image for its `postgres` service and starts
# the server with `-c shared_preload_libraries=pg_textsearch`: the extension
# refuses to load unless it is in shared_preload_libraries (it allocates
# shared memory in its `_PG_init`).

FROM postgres:18-alpine AS build

# pg_textsearch release to build. Bump deliberately: some releases require a
# `REINDEX` of existing bm25 indexes (see the extension's UPGRADING.md), which
# `make install` here cannot do for you.
ARG PG_TEXTSEARCH_VERSION=1.5.1

# Toolchain for the C build. `curl` fetches the release tarball.
RUN apk add --no-cache --virtual .build-deps build-base curl

WORKDIR /src

# Install into a private tree with DESTDIR instead of the system prefix: the
# runtime stage can then copy every installed file without hard-coding where
# PostgreSQL keeps its libraries, extension control files and SQL scripts.
#
# with_llvm=no skips PGXS's LLVM bitcode emission. Bitcode only enables JIT
# inlining of the extension's functions, and it needs the clang that built the
# server, which the runtime image does not ship.
RUN set -eux; \
    curl -fsSL -o pg_textsearch.tar.gz \
        "https://github.com/timescale/pg_textsearch/archive/refs/tags/v${PG_TEXTSEARCH_VERSION}.tar.gz"; \
    tar -xzf pg_textsearch.tar.gz --strip-components=1; \
    rm pg_textsearch.tar.gz; \
    make -j"$(nproc)" with_llvm=no; \
    make install DESTDIR=/install with_llvm=no

FROM postgres:18-alpine

# $libdir/pg_textsearch.so, $sharedir/extension/pg_textsearch.control and the
# versioned SQL scripts, at the paths the build stage's `make install` chose.
COPY --from=build /install/ /
