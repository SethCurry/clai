# clai

**Be very careful running this, you have been warned.**

This is me toying with the idea of running an agent in Clojure and then
exposing the REPL for that JVM to the agent so it can modify its own
runtime live.

It should enable the agent to modify itself at runtime, in major ways.

At the end of this, you should end up with an agent where you can just say
"I want to use a new LLM provider. My key is $x, and the API docs are at $y.
Write a provider for them, and then run this prompt: $z"

It should be able to write a provider that implements that and then send that
prompt, or even add subagent support to itself and use that.

**WARNING**

This is not sandboxed at all. Your LLM can modify its harness to steal your
memecoins and tell your cat that Santa isn't real.

Be extremely careful. You should _not_ run this outside of Docker, a VM, something like that.

Also, _the entire repo is vibecoded_. This is mostly a toy, I'm not reviewing what goes on in here
for the most part. There could be cryptominers in here, I have no idea, and I make no promises.

## Development database

Sessions are stored in PostgreSQL. `docker-compose.yml` runs a local instance
tuned to the `:postgres` settings documented in `src/clai/config.clj` and
`config.example.edn` (`localhost:5432`, database `clai`, user `clai`,
password `secret`), keeps its data in `./local/postgres`, and applies the
storage schema on startup when the tables are missing:

```sh
docker compose up -d
```

Copy `config.example.edn` to `./clai.edn` only if you want to change those
settings. The daemon's built-in connection defaults already match the
container (`localhost:5432`, database `clai`, user `clai`, password `secret`),
so `task web` and `lein run` connect without any extra configuration. You can
also override individual values with the corresponding `CLAI_POSTGRES_*`
variables. The data directory is owned by the container's `postgres` user; to
reset it, run `docker compose down` and remove `./local/postgres` as root.

## Web UI

The React app in `web/` talks to the daemon over a WebSocket (`/ws`) for chat
streaming and for evaluating Clojure in the running JVM through nREPL. The
daemon also exposes a small JSON API under `/api`.

Build the UI and start everything with:

```sh
task web
```

That runs `npm install`, builds `web/dist`, starts an nREPL server, and serves
the UI and API on http://127.0.0.1:8080. Pass a different port with
`lein run web 3000`. The nREPL port is printed at startup and is also visible
in the UI. Both servers bind to loopback; an nREPL client can run arbitrary
code in the daemon's JVM, so do not expose either port to an untrusted
network.

For UI development, start the daemon once and run Vite separately:

```sh
lein run web
npm run dev --prefix web
```

Vite serves the UI on http://127.0.0.1:5173 and proxies `/api` and `/ws` to
the daemon at `http://127.0.0.1:8080`. Override the proxy target with
`CLAI_DAEMON_URL`. `clai web` serves the built UI from `web/dist`; set
`CLAI_WEB_ROOT` to serve a different build directory.

## Plugins

Plugins are Clojure jars configured in PostgreSQL. They can change session
namespace creation, system-prompt assembly, and the handling of user prompts
and agent messages. Each agent configuration chooses its active plugins with
the `plugins` list (the web UI exposes it in the configuration dialog); see
[doc/plugins.md](doc/plugins.md) for the hook contract, the `plugin` table, and
a complete example.

The repository includes an example plugin that prepends today's date to the
system prompt. Build it into a standalone jar with:

```sh
lein plugin-jar
```

The jar is written to `target/plugin/clai-plugin-todays-date.jar`; register it
with entry namespace `clai-plugin-todays-date.plugin`. An optional
`:date-format` setting accepts a `java.time.format` pattern (for example,
`{:date-format "EEEE, MMMM d, yyyy"}`).

## cljdocs

You can get a local copy by running `task cljd` and navigating
to http://localhost:9122/d/clai/clai/0.1.0-SNAPSHOT/doc/readme
