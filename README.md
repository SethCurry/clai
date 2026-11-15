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
daemon also exposes a small JSON API under `/api`. Both are private: the UI
opens on a sign-in form, and the API needs a session cookie or bearer token
(see [Authentication](#authentication)).

Besides the chat and the session sidebar, the UI has a Clojure REPL panel and
a read-only explorer panel with two tabs: **Files** browses the selected
session's working directory, and **Namespaces** browses the loaded Clojure
namespaces, their Vars, docstrings, and source, starting from the session's own
namespace. Each panel can be hidden from the top bar.

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

## Authentication

The daemon's JSON API and its WebSocket are closed by default. Every request
but the login page's own calls needs a session, so the UI starts on a sign-in
form:

- `POST /api/auth/login` with `{"username": ..., "password": ...}` answers with
  the user and an `HttpOnly` session cookie (or 401 for wrong credentials).
- `GET /api/auth/me` reports whether the request carries a live session.
- `POST /api/auth/logout` revokes it and clears the cookie.

Anything else under `/api/`, and the `/ws` upgrade, answers
`401 {"error": "Authentication required"}` without a valid cookie. Non-browser
clients can send `Authorization: Bearer <token>` instead of the cookie.

Accounts live in the `app_user` table and passwords are stored as salted
PBKDF2 digests (`clai.auth/hash-password`); the plaintext is never stored.
`schema/schema.sql` seeds one account so a fresh local database can log in:

```
admin / admin
```

Change it before the daemon is reachable by anything but you:

```clojure
;; lein repl
(require '[clai.daemon.postgres :as pg] '[clai.daemon.postgres.users :as users])
(def client (pg/open {}))
(users/create-user! client {:username "you" :password "a long passphrase"})
```

Then delete the sample account:

```sh
docker compose exec postgres psql -U clai -d clai -c "DELETE FROM app_user WHERE username = 'admin'"
```

Sessions last seven days (`auth/token-ttl-seconds`) and are stored as SHA-256
digests in `auth_token`, so a database dump cannot be replayed. `clai web`
binds to loopback (`127.0.0.1`) for the same reason: the agent can run
arbitrary commands, and one account name and password is not a real
authorization boundary between users.

## Plugins

Plugins are Clojure jars configured in PostgreSQL. They can change session
namespace creation, system-prompt assembly, and the handling of user prompts
and agent messages. Install, edit, and remove them from the web UI's
**Plugins** page, or with the functions in `clai.plugins.store`. Each agent
configuration chooses its active plugins with the `plugins` list (tick them
under **Plugins** on the web UI's **Agent configs** page); see
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
