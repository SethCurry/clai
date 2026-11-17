# clai

**An AI agent harness written in Clojure, where the agent and I work in the
same live JVM.**

`clai` is the harness I use every day to write code with a language model. It
is a personal tool: built for my workflow, opinionated, and changing most days.
It has exactly one user. But the ideas in it turned out to be more interesting
than the original weekend experiment, so this README is an honest tour of what
it does and why it is put together the way it is.

The short version of the idea: most agent harnesses hand the model a fixed set
of text tools and treat the language runtime as something on the other side of
a process boundary. `clai` collapses that boundary. The agent's most powerful
tool, `eval_clojure`, evaluates code **in the harness's own running
Clojure process** — the same process that serves the web UI, streams the
events, and holds my REPL. The agent can read its own source, redefine a
function, reload a namespace and keep going. So can I, in the same process,
at the same time. Clojure is the shared language between the human and the
agent, not just the implementation language of the server.

> **This is a personal project, not a product.**
> There is no released version, no compatibility promise, and no support. I
> wrote it for myself; you are welcome to read it, steal from it, or run it,
> but expect a moving target. Most of the code in this repository was written
> by `clai` itself, running in this harness — which is the point, and also a
> fair warning that the style drifts from namespace to namespace.

---

## ⚠️ Safety first

**`clai` is not sandboxed.** The agent can run arbitrary shell commands, write
any file the JVM can write, and evaluate any Clojure form in the harness's own
process. That is the feature. It is also the risk.

- Run it in a container or a VM you do not mind losing. Nothing in this
  repository is a security boundary between you and the model.
- The nREPL server binds to `127.0.0.1`; an nREPL client can execute arbitrary
  code in that JVM, so do not expose the port.
- The web daemon is authenticated but, as shipped, listens on `0.0.0.0`
  (`start-server!`'s `:host` option; `lein run web` does not override it). The
  login form, the bearer/cookie check and the WebSocket `Origin` check are then
  all that stand between your network and code execution. Set `:host` to
  `127.0.0.1` if you want it local-only, and change the seeded `admin`/`admin`
  account before anything else.
- Session state lives in a local PostgreSQL database. Treat a dump of it as
  sensitive: it holds everything you and the model said to each other.

With that said, on to the interesting parts.

---

## What is worth looking at

If you only read one section of this repository, these are the ideas I would
point at:

1. **One runtime for the agent and the developer.** `eval_clojure` runs in
   `clai.home.core`, inside the same JVM my REPL runs in. Its Vars are one
   `in-ns` away, and a session's namespace (`sessions.<id>`) is shared outright.
   A fix I make in the REPL is live for the agent's next tool call: no restart,
   no build step.
2. **Tools are Clojure values, not a DSL.** A tool is a map of
   `{:id :handler :spec}`: an LLM-facing JSON schema, plus a plain function
   `(fn [args ctx] ...)`. Registries are immutable maps owned per agent, so two
   agents in one process can offer different tool sets. Adding a tool is
   `assoc`.
3. **Sessions are an append-only event log.** Every prompt, thought, message
   and tool call is one typed row in PostgreSQL. History, resume, compaction,
   and the usage dashboard are all just reads over that log — nothing is
   reconstructed from in-memory state.
4. **Providers are a protocol.** Ollama and OpenRouter are two implementations
   of a seven-method interface (`provider-id`, `capabilities`, `chat-request`,
   `chat`, `assistant-message`, `tools`, `token-usage`). A provider that cannot
   do something says so through its capability map instead of pretending.
5. **Malli everywhere, checked at runtime.** Every schema'd function carries a
   `:malli/schema`, and requiring `clai.instrument` instruments all of them, so
   argument and return contracts are enforced while the system runs — 369
   schemas in the current tree. In a system where the agent rewrites live code,
   runtime contracts are the safety net a compiler would otherwise be.

The rest of this README walks through how those ideas show up in practice.

---

## Quick start

Prerequisites: a recent JDK (21+; I run it on 25),
[Leiningen](https://leiningen.org), Docker, and — for the web UI — Node and
[Task](https://taskfile.dev). An Ollama server (local, or hosted) and/or an
OpenRouter API key.

```sh
# 1. Local PostgreSQL for sessions (localhost:5432/clai, user clai, secret)
docker compose up -d

# 2. Optional: point clai at a model
cp config.example.edn ./clai.edn     # then edit :model, :provider, keys

# 3a. Web UI + API + nREPL on :8080
task web

# 3b. …or the REPL, which drives the same agent in-process
lein repl
```

`config.example.edn` is commented key by key. Configuration is resolved from
the first file that exists of `$CLAI_CONFIG`, `./clai.edn`, and
`~/.config/clai/config.edn`; anything omitted falls back to environment
variables (`CLAI_MODEL`, `OLLAMA_HOST`, `OPENROUTER_API_KEY`,
`CLAI_POSTGRES_*`, …) and then to the built-in defaults. The Docker Compose
database already matches those defaults, so a fresh checkout needs no
configuration to reach it.

The web UI opens on a sign-in form. A fresh database is seeded with
`admin / admin`; change it before the daemon is reachable by anything but you
(see [Authentication](#authentication)).

Run the test suite with:

```sh
lein test        # 553 tests, 1754 assertions
```

---

## Using it

### From the REPL

`lein repl` starts in the `user` namespace, which loads the harness, redirects
its logs to a file (so they do not interleave with the conversation), and
refers the REPL helpers and the shell integration namespaces. The whole agent
is then a handful of functions:

```clojure
(new!)                                   ; new session, REPL moves into sessions.<id>
;; => {:sessionId "12" :cwd "/Users/me/code/clai" ...}

(prompt! "How does compaction decide what to keep?")
;; clai> Compaction keeps the system prompt, the summary, the newest
;;       messages and the turn in progress...
;;   [tool] 418 completed
;;      ...
;;   [title] Compaction rules

(history!)                               ; reprint the conversation so far
(resume! "9")                            ; jump back into an old session
(git/status)                             ; shell helpers, in the session's cwd
($ "lein test")                          ; anything else, via sh -c
(help git/commit)                        ; docstring of any Var, no quoting
```

`prompt!` streams events as they arrive — model text, thoughts, tool calls and
their output, generated session titles — so the REPL reads like a conversation
rather than a blocking call. Sessions are resumable across processes because
they are rows in PostgreSQL, not state in the REPL.

### From the web UI

`task web` builds the React app in `web/` and serves it from the daemon on
port 8080. The UI talks to the daemon over a WebSocket for chat streaming and
Clojure evaluation, and over a small JSON API for everything else.

- **Sessions** — a sidebar of past conversations with their working directory,
  a streaming chat timeline that renders thoughts, tool calls and Markdown,
  and `./` path completion in the composer.
- **REPL panel** — a Clojure REPL that evaluates in the daemon's own JVM
  through nREPL, defaulting to the selected session's `sessions.<id>`
  namespace. It is the same runtime the agent's `eval_clojure` runs in, just
  with a browser in front of it.
- **Explorer panel** — two read-only trees: **Files** browses the session's
  working directory, and **Namespaces** browses the live Clojure runtime —
  namespaces, Vars, docstrings, arglists, malli schemas, source. It is a
  window onto the process the agent is editing.
- **Agent configs** — named configurations (model, system prompt, tool
  subset, plugin set, token budget, chat options) that a session can select
  from the sidebar.
- **Git repos** — a registry of the repositories and worktrees I work in
  (name, url, branch, directory), so a checkout's path is recorded once.
- **Notes**, **Plugins**, **Stats** — described below.

### For UI development

```sh
lein run web            # daemon on :8080
npm run dev --prefix web # Vite on :5173, proxying /api and /ws to the daemon
```

`CLAI_DAEMON_URL` repoints the Vite proxy; `CLAI_WEB_ROOT` repoints the daemon
at a different build directory.

---

## What the agent can do

The built-in tools are grouped by concern and each group is a namespace under
`clai.agent.tools.builtin`. An agent configuration can enable a subset of them;
`clai.edn` can too.

| Tool                                                                                             | What it does                                                                                                                                                                                         |
| ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `read_file`, `write_file`, `list_directory`                                                      | Read and write files, resolved against the session's `cwd`. `read_file` takes an optional line range so a long file can be read in slices instead of flooding the history.                           |
| `run_command`                                                                                    | Run a shell command in the session's `cwd`, with output capped.                                                                                                                                      |
| `eval_clojure`                                                                                   | Evaluate Clojure in the harness's own JVM, in `clai.home.core`. Results persist between calls; the agent is encouraged to `def` values worth keeping so they are visible in my REPL too.             |
| `spawn_agent`                                                                                    | Run an isolated sub-agent with its own system prompt, model and tool subset, in the same working directory. Only its final reply comes back, so the parent's context stays small. Nesting is capped. |
| `lsp_definition`, `lsp_references`, `lsp_hover`, `lsp_symbols`, `lsp_outline`, `lsp_diagnostics` | Navigation over `clojure-lsp`, kept warm per project. Beats grepping for `defn` when the agent needs to know which `error-message` on which line is meant.                                           |
| `notes_list`, `notes_search`, `notes_read`, `notes_update`, `notes_delete`                       | Titled Markdown notes in PostgreSQL, BM25-ranked (see [Notes](#notes)).                                                                                                                              |
| `openproject_*`                                                                                  | List projects and work packages, create and update tasks, and read, add and edit task comments — when `:openproject` is configured.                                                                  |

The OpenProject tools are `openproject_list_projects`,
`openproject_list_work_packages`, `openproject_create_work_package`,
`openproject_update_work_package`, `openproject_list_comments`,
`openproject_add_comment` and `openproject_update_comment`, so an agent can
work a task the way I would.

Around the tools sit a few behaviours that matter more than the list:

- **Compaction.** When a session's history outgrows its token budget, the agent
  summarizes it in one request and appends a `compaction` event. Reading the
  history skips the events the summary covers and keeps the system prompt, the
  summary, the newest messages and the turn in progress — so compacting never
  interrupts work mid-turn, and the log stays a complete audit trail.
- **Automatic titles.** A new session is named from its first prompt by one
  extra model call, so the sidebar is readable without me typing titles.
- **Output caps.** Every tool result is capped (64 KiB by default) so a single
  runaway command cannot eat the context window.
- **Per-agent registries.** Tools are not global. Each agent owns a registry
  map, which is what makes `spawn_agent` with a narrower tool set, or two
  agents with different capabilities, possible in one process.

---

## Clojure is the bridge

This is the part I would want a reader to take away.

Clojure is unusually well suited to this. A REPL that can redefine a running
function, namespaces as first-class values, code as data, and a schema library
that attaches contracts to Vars rather than to a build step — the harness is
built on those four things. The agent does not need a bespoke extension API,
because the language already is one: `require` and `assoc` are enough.

### The same process, the agent's own namespace

`eval_clojure` does not shell out to a Clojure. It evaluates the form in this
JVM, in `clai.home.core`, which is seeded with helpers for exactly this
situation:

```clojure
;; what the agent runs, and what prints in my REPL
(help)                     ; a cheat sheet of what this namespace offers
(tools)                    ; the tool registry of the calling agent, live
(context)                  ; this session's id, model, cwd
(history)                  ; the stored messages of this session
(vars 'clai.util)          ; the Vars of a namespace in this process
(var-detail "clai.util" "error-message")
```

During the call, the session context is bound to `*context*`, so evaluated code
can ask what session it is running in. Everything it defines stays: a `def`
made by one tool call is there in the next one. And `clai.home.core` is an
ordinary namespace in my JVM: I can `in-ns` into it, or name the Var fully
qualified, and inspect exactly what the agent left behind. That is why the
agent's system prompt encourages `(def x ...)` over hiding values in a `let`,
and why `(def ...)` appears in transcripts where you would not expect it.

### The harness is editable while it runs

Because file writes and code evaluation both land in the live system, extending
the harness is a conversation, not a release:

```text
me    I want to use a new LLM provider. The key is in $NEW_KEY and the API
      docs are at <url>. Write a provider for it, then send it "hi".

clai  [read_file] src/clai/agent/providers/openrouter.clj
      [read_file] src/clai/agent/providers/protocol.clj
      [write_file] src/clai/agent/providers/newco.clj
      [eval_clojure] (require 'clai.agent.providers.newco :reload)
      [eval_clojure] (providers/respond client {...})
      ...
      The provider works; "hi" came back as "hello".
```

Nothing about that is special-cased. A provider is a `defrecord` extending
`clai.agent.providers.protocol/Provider`; `clai.agent.providers` resolves a
model name to the client that serves it; `require ... :reload` makes the new
namespace live in this process. The same move adds a tool (`assoc` into a
registry), a plugin hook, or a shell helper.

### Tools are thin wrappers around code I use myself

The shell integrations under `clai.shell.*` are ordinary, composable Clojure
functions for `git`, `docker`, `task`, and the browser. They are what the
agent's `run_command` and `eval_clojure` reach for, and they are what I type in
the REPL:

```clojure
(->> (git/status {:dir "/repo"}) :untracked (map :path))
(-> (docker/inspect "my-container") first :state)
(binding [*cwd* "/repo"] (git/status) (task/list)) ; in a session ns
```

Each returns a result map (`{:command :exit :out :err :success? ...}`) instead
of throwing, so it composes in a pipeline; the `!` variants throw when you want
that instead. Because a session namespace carries its own `*cwd*` and
`current-session`, the same helper called from the agent and from my REPL in
that session resolves to the same directory.

### Session namespaces keep the work separate

Every session gets its own namespace, `sessions.<id>`, seeded from `user` with
the same helpers and aliases, plus its own `current-session` and `*cwd*` Vars.
Creating or resuming a session moves the REPL there with `in-ns`. So:

- two sessions do not fight over `*cwd*` or over a helper Var one of them
  redefined;
- a session's namespace survives for the life of the process, so returning to
  it restores its definitions along with its conversation;
- the web REPL panel defaults to that namespace, so the panel and
  `clai.repl` address the same session, the same working directory and the
  same definitions; the agent reaches them through its tools and its own
  `eval_clojure` namespace (`clai.home.core`), in the same process.

Namespace creation goes through `clai.repl/namespace-builder`, which is a
plugin hook — so a plugin can seed session namespaces with extra Vars, or
replace the builder entirely.

### The browser is another window into the same process

The UI does not have its own notion of state. Chat streaming and `eval` both go
over one WebSocket to the daemon; `eval` is forwarded to the daemon's nREPL
server. The **Namespaces** explorer reads the live runtime: when the agent
redefines a Var with `eval_clojure`, the explorer shows its new value and
docstring. The **Stats** page reads the event log. One process, several views.

---

## How it fits together

```text
clai (one JVM)
├── clai.daemon.http ── the React UI (/ws, /api) and the JSON API
│   └── nREPL bridge ── the REPL panel and `clai shell` evaluate through it
├── clai.agent.core ── the chat loop, sessions, tool calls
│   ├── providers ── Ollama, OpenRouter
│   ├── tools/builtin ── files, shell, notes, lsp, sub-agents
│   │   └── eval_clojure ── evaluates in this same JVM
│   └── postgres ── sessions, event log, notes, plugins, stats
└── clai.repl ── new!, prompt!, resume!: the agent driven from a REPL
```

A prompt goes in through the REPL, `clai.repl`, or the WebSocket. `run-prompt!`
appends it to the session, and the agent loop sends the history to the provider
that serves the session's model, executes any tool calls the model asks for
against that agent's registry, appends the results, and repeats until the model
replies in text or the tool-round cap is reached. Every step is streamed as an
event and appended to the log.

A few namespace landmarks, if you are reading the code:

| Namespace                            | Role                                                                            |
| ------------------------------------ | ------------------------------------------------------------------------------- |
| `clai.core`                          | CLI: `nrepl`, `shell`, `web`.                                                   |
| `clai.repl`                          | The in-process REPL client: `new!`, `prompt!`, `resume!`, session namespaces.   |
| `clai.agent.core`                    | The agent: configuration, sessions, the chat loop, everything the daemon calls. |
| `clai.agent.providers` / `.protocol` | Provider resolution and the provider interface.                                 |
| `clai.agent.tools.*`                 | Tool schemas, immutable registries, invocation, and the built-ins.              |
| `clai.events.schema`                 | The event vocabulary shared by the loop, the log, the REPL and the UI.          |
| `clai.daemon.postgres.*`             | Session, note, plugin, config, git and stats storage.                           |
| `clai.daemon.http` / `.nrepl`        | The web daemon, the JSON API and the nREPL bridge.                              |
| `clai.home.core`                     | The namespace `eval_clojure` runs in — the agent's REPL.                        |
| `clai.shell.*`                       | REPL-friendly `git`, `docker`, `task`, browser and process helpers.             |
| `clai.plugins`                       | Jar plugin loading and the middleware hooks.                                    |
| `clai.instrument`                    | Malli collection and runtime instrumentation.                                   |

If you are skimming the code rather than reading it, four files carry most of
the ideas: `src/clai/repl.clj` (driving a session), `src/clai/agent/core.clj`
(the loop), `src/clai/agent/tools/registry.clj` (tools as values) and
`src/clai/agent/providers/protocol.clj` (the provider seam).

If you want the design notes rather than the code, `doc/plugins.md` covers the
plugin hook contract, `doc/design/` holds the working notes for session
namespaces, the command registry and the plugin architecture, and
`web/README.md` documents the WebSocket protocol and the JSON API in detail.

---

## The unglamorous parts

The flashy idea is a live runtime shared with the model. The parts that make
it survivable are ordinary engineering, and they got the same attention:

- **Storage DDL is idempotent and applied on first use.** `ensure-schema!`
  creates what is missing and never touches what exists, so a fresh
  checkout and a year-old database behave the same way, and the Compose
  file can apply the same DDL from a one-shot container.
- **Configuration is an explicit, documented precedence chain** — file, then
  environment, then defaults — with the config map described as a malli schema
  and checked wherever a setting is read.
- **Auth is not an afterthought.** PBKDF2-SHA256 password digests with the
  iteration count carried in the digest, session tokens stored as SHA-256
  digests, `HttpOnly` cookies, bearer tokens for scripts, and an `Origin`
  check on the WebSocket because browsers do not enforce same-origin for
  WebSockets.
- **Structured logging.** One Telemere signal per interesting event, with
  ids and data maps, so a session can be followed through the log instead
  of guessed at.
- **Tests that are actually run.** 553 tests in 43 test files, plus a
  `node:test` suite for the UI's pure helpers; the Clojure suite runs with
  malli instrumentation on, so schema drift fails a test rather than a user.
- **A build that works.** `lein uberjar` with an explicit AOT list for the
  dependency namespaces that generate classes, and cljdoc docs built from
  the same tree.

---

## Sessions are databases

Sessions live in PostgreSQL (`schema/schema.sql` is the canonical DDL; the
daemon applies it on first use):

```
agent_session        id, title, working_directory, created_at
agent_session_event  append-only, one typed JSONB payload per row
```

The `agent_session_event_type_t` enum — `user_prompt`, `agent_message`,
`agent_thought`, `tool_call`, `clojure_form`, `compaction` — is also the
vocabulary the agent loop streams to the UI and the REPL, so a value moves
between the chat loop and the log without a second representation. Because a
session is rows, mutations are transactional and every process sharing the
database sees the same conversations.

That single decision buys a lot: resume after a crash, the **Stats** page
(calls per tool, average output per tool, tool calls per session, straight from
the log), compaction that never rewrites history, and a complete audit trail of
what the agent actually did.

The development database runs from `docker-compose.yml`: PostgreSQL 18 plus
[`pg_textsearch`](https://github.com/timescale/pg_textsearch) for BM25 ranking,
data in `./local/postgres`, and a one-shot `schema` service that enables the
extension and applies the DDL on every `docker compose up` (a no-op once the
tables exist). Delete `./local/postgres` as root to start over.

---

## Notes

Notes are titled Markdown documents stored in PostgreSQL — my scratchpad and
the agent's, in one place. `[[Note title]]` links are read out of the content
rather than stored separately, so a link is always in step with the text and a
reference to a note that does not exist yet simply starts resolving when that
note is written. Titles are unique (case-insensitively), because a title is how
notes address each other.

`notes_search` ranks by BM25 through the `pg_textsearch` index, and falls back
to a case-insensitive substring match on a server without the extension, so
callers never have to know which database they are talking to.

---

## Plugins

Plugins are Clojure jars that change how the harness behaves: session namespace
creation, system-prompt assembly, and the handling of user prompts and agent
messages. They are stored in the `plugin` table, installed and toggled from the
**Plugins** page, and selected per agent configuration.

A plugin is one entry namespace with a `plugin` Var — either a descriptor map
or a function of its JSON configuration — exposing middleware-style hooks:

```clojure
(ns example.plugin)

(def plugin
  {:description "Adds a house style to every system prompt"
   :version "1.0.0"
   :hooks
   {:system-prompt
    (fn [ctx next]
      (str (next ctx) "\n\nAlways write tests before implementation."))}})
```

Hooks run in priority order and call `next` to continue; a hook that throws is
logged and ignored, so a broken plugin does not take a session down. The
repository includes a complete example (`src/clai_plugin_todays_date/`) that
prepends today's date to the system prompt. Build it with `lein plugin-jar`
(or `task plugin:build`) and install it with entry namespace
`clai-plugin-todays-date.plugin`; `[:plugins]` in `clai.edn` or the agent
configuration decides which plugins a session runs.

Full contract, hook table and examples: [`doc/plugins.md`](doc/plugins.md).

---

## Authentication

The JSON API and the WebSocket are closed by default; only the login page's
own three calls are open. The UI starts on a sign-in form, and every other
`/api/` route plus the `/ws` upgrade answers `401` without a valid session.

- `POST /api/auth/login` → the user and an `HttpOnly` session cookie.
- `GET /api/auth/me` → whether the request carries a live session.
- `POST /api/auth/logout` → revokes it.
- Non-browser clients can send `Authorization: Bearer <token>` instead.

Accounts live in `app_user`; passwords are salted PBKDF2 digests
(`clai.auth/hash-password`) and the plaintext is never stored. Login sessions
last seven days and are stored as SHA-256 digests in `auth_token`, so a
database dump cannot be replayed. Change the seeded account before exposing the daemon:

```clojure
;; lein repl
(require '[clai.daemon.postgres :as pg] '[clai.daemon.postgres.users :as users])
(def client (pg/open {}))
(users/create-user! client {:username "you" :password "a long passphrase"})
```

```sh
docker compose exec postgres psql -U clai -d clai \
  -c "DELETE FROM app_user WHERE username = 'admin'"
```

One username and password is not a real authorization boundary between people,
which is why the daemon is meant for one user on a trusted network, and
why the answer to "can several people share an instance?" is "no".

---

## Configuration

`config.example.edn` is the reference, and it is commented key by key. The
pieces worth knowing:

```clojure
{:model    "gemma3"     ; or "openrouter:anthropic/claude-sonnet-4"
 :provider :ollama      ; default provider for a model name that names none
 :system-prompt "You are a concise, helpful coding agent."
 :max-tool-rounds 8
 :compact-after-tokens 250000
 :tools    ["read_file" "write_file" "list_directory" "run_command"
            "eval_clojure" "spawn_agent" "notes_list" "notes_search"]
 :options  {:temperature 0.7}}
```

A model name picks its own provider when it can: `openrouter:...` and
`ollama:...` pin one explicitly, and an id containing `/` is treated as an
OpenRouter id. Both providers can be configured at once, and a stored agent
configuration can name a model from either.

Environment variables (`CLAI_MODEL`, `CLAI_PROVIDER`, `OLLAMA_HOST`,
`OLLAMA_API_KEY`, `OPENROUTER_API_KEY`, `CLAI_MAX_TOOL_ROUNDS`,
`CLAI_COMPACT_AFTER_TOKENS`, `CLAI_POSTGRES_*`, `PG*`, `OPENPROJECT_*`) fill in
anything the file leaves out, and the file wins over the environment.

An `AGENTS.md` in a new session's working directory is appended to the system
prompt, so per-project instructions travel with the project.

---

## Development

```sh
lein test                # the full suite
task test                # same thing
lein run nrepl           # nREPL only, publishes .nrepl-port
lein run shell           # attach a REPLY REPL to a running server
lein run web [port]      # nREPL + web daemon
lein uberjar             # standalone jar
lein plugin-jar          # build the example plugin jar
task cljdoc              # serve the cljdoc docs locally
npm test --prefix web    # the UI's node:test suite for its pure helpers
```

The suite runs with `clai.instrument` loaded, so every schema'd function is
checked against its malli schema while the tests run — a schema that has
drifted from its function shows up as a test failure rather than a surprise in
production.

`lein repl` is the recommended way to poke at the system: it boots into `user`
with the agent helpers referred, logs redirected to `target/logs/clai.log`, and
a live agent you can talk to without the web UI.

---

## Status

This is a personal harness on a fast-moving branch. There is no released
version, no backwards-compatibility promise, and the tests describe what works
today rather than a contract. `TODO.md` and `doc/design/` are the working notes
for what is next — mostly more ways for the harness to grow itself safely:
better session isolation, a command registry, and a cleaner plugin/extension
story.

Sizes, if you like numbers: roughly 21k lines of Clojure in `src/`, 10k lines of
test, 8k lines of React, 61 source namespaces, 553 tests.

If something here is useful to you, take it. If you find a hole in the safety
story, I would genuinely like to hear about it.

---

## License

Distributed under the Eclipse Public License 2.0, or GPL-2.0-or-later with the
Classpath exception — see [LICENSE](LICENSE).
