---
title: "Development"
description: "Run clai's test suite with malli instrumentation, build an uberjar or plugin jar, serve cljdoc locally, and develop the React UI against a live daemon."
summary: "Tests, builds, cljdoc, and the web UI dev loop."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 8
toc: true
---

## Running the tests

```sh
lein test                # the full Clojure suite
task test                # same thing
npm test --prefix web    # the UI's node:test suite for its pure helpers
```

The Clojure suite runs with `clai.instrument` loaded, so every schema'd
function is checked against its malli schema while the tests run. A schema that
has drifted from its function shows up as a test failure rather than a surprise
in production.

## Running the harness

```sh
lein run nrepl           # nREPL only, publishes .nrepl-port
lein run shell           # attach a REPLY REPL to a running server
lein run web [port]      # nREPL + web daemon
lein repl                # the in-process REPL, with the agent helpers
```

`lein repl` is the recommended way to poke at the system: it boots into `user`
with the agent helpers referred, logs redirected to `target/logs/clai.log`, and
a live agent you can talk to without the web UI.

## Building

```sh
lein uberjar             # standalone jar
lein plugin-jar          # build the example today's-date plugin jar
```

The uberjar profile lists the dependency namespaces that generate classes
explicitly, because `:aot :all` only scans this project's own source paths. If
a build starts failing with a `NoClassDefFoundError` for a class a dependency
generates, add that namespace to the `:aot` list. Run `lein clean` after
upgrading one of those dependencies, since dependency sources have no timestamp
to compare against.

## Documentation

```sh
task cljdoc              # serve cljdoc locally and ingest this tree
```

The task starts a local cljdoc server, installs clai into the local Maven
repository, and ingests the project with `doc/` from the working tree. It is a
preview of what cljdoc.org would publish.

This site is a Hugo project in `docs/`:

```sh
cd docs
npm install
npm run dev              # http://localhost:1313
npm run build            # production build into public/
```

## Web UI development

```sh
lein run web              # daemon on :8080
npm run dev --prefix web  # Vite on :5173, proxying /api and /ws to the daemon
```

Set `CLAI_DAEMON_URL` to point the Vite proxy at another daemon, and
`CLAI_WEB_ROOT` to serve a different build directory from the daemon. The
WebSocket protocol and the JSON API are documented in the
[HTTP API reference](/docs/reference/http-api/).

## Style notes

- Malli schema types start with an upper-case letter, and schemas live close
  to the functions they describe.
- Every `def` and `defn` has a docstring explaining what it is for.
- Prefer composable functions that are pleasant in a pipeline from the REPL.
- Instrumentation is the contract: if a function carries a schema, it is
  checked at runtime.
