## Instructions

Before saying that you are done, run the unit tests with `lein test` and fix any issues that arise.

Malli schema types should start with an upper case letter, and all functions should have a malli validator.

All def's and defn's should have comments explaining what they do.

If you see code that can be simplified, please do so while still doing whatever task was asked of you.
That includes partial rewrites, but try not to refactor more than 3 namespaces at once (finish refactoring until unit tests work if you've already started).

Don't worry about backwards compatibility. There are no users. Just write the code in the cleanest way that makes the most sense.

Make sure that your Clojure is idiomatic and composable in pipes so that
its nice for a user to use from the REPL.

## Functionality Locations

| Functionality                                                            | Location                                                                               |
| ------------------------------------------------------------------------ | -------------------------------------------------------------------------------------- |
| Provider selection (model -> provider, chat turn)                        | ./src/clai/agent/providers.clj                                                         |
| Ollama API client                                                        | ./src/clai/agent/providers/ollama.clj                                                  |
| OpenRouter API client                                                    | ./src/clai/agent/providers/openrouter.clj                                              |
| Shared provider HTTP + message normalization                             | ./src/clai/agent/providers/http.clj, ./src/clai/agent/providers/messages.clj           |
| Logging                                                                  | ./src/clai/log.clj                                                                     |
| Plugin runtime                                                           | ./src/clai/plugins.clj                                                                 |
| Today's date plugin                                                      | ./src/clai_plugin_todays_date/plugin.clj                                               |
| PostgreSQL client                                                        | ./src/clai/daemon/postgres.clj                                                         |
| Password hashing, session tokens, cookies                                | ./src/clai/auth.clj, ./test/clai/auth_test.clj                                         |
| User accounts + login sessions (database)                                | ./src/clai/daemon/postgres/users.clj, ./test/clai/daemon/postgres/users_test.clj       |
| API authentication (login/logout routes, 401 guard)                      | ./src/clai/daemon/http.clj                                                             |
| Session storage                                                          | ./src/clai/daemon/postgres/sessions.clj                                                |
| Session titles (auto-naming a session from its first message)            | ./src/clai/agent/titles.clj, ./test/clai/agent/titles_test.clj                        |
| Git repo/worktree storage                                                | ./src/clai/daemon/postgres/git.clj                                                     |
| Git repo/worktree HTTP API                                               | ./src/clai/daemon/http.clj                                                             |
| Note storage + BM25 note search                                          | ./src/clai/daemon/postgres/notes.clj                                                   |
| Note HTTP API (CRUD + search)                                            | ./src/clai/daemon/http.clj                                                             |
| OpenProject task/comment tools                                           | ./src/clai/agent/tools/builtin/openproject.clj                                         |
| Note tools (list/search/read/update/delete)                              | ./src/clai/agent/tools/builtin/notes.clj                                               |
| `eval_clojure` tool (agent REPL)                                         | ./src/clai/agent/tools/builtin/eval.clj                                                |
| Agent REPL namespace (`eval_clojure` default, `tools`/`history` helpers) | ./src/clai/home/core.clj                                                               |
| File listing/preview (web)                                               | ./src/clai/daemon/files.clj                                                            |
| Clojure runtime introspection (web)                                      | ./src/clai/daemon/namespaces.clj                                                       |
| Storage DDL                                                              | ./schema/schema.sql                                                                    |
| Local dev database (compose, pg_textsearch image)                        | ./docker-compose.yml, ./docker/postgres/Dockerfile                                     |
| Web UI                                                                   | ./web                                                                                  |
| Web routes + sign-in guard                                               | ./web/src/App.jsx, ./web/src/main.jsx                                                  |
| Web console shell (topbar, page tabs, error toasts)                      | ./web/src/components/AppLayout.jsx                                                     |
| Web daemon connection shared by the pages                                | ./web/src/ClaiContext.jsx, ./web/src/useClaiSocket.js                                  |
| Web sessions page (sidebar, chat, REPL, explorer, config dialog)         | ./web/src/components/SessionsPage.jsx                                                  |
| Web login page + session state                                           | ./web/src/components/LoginPage.jsx, ./web/src/useAuth.js                               |
| Web credentials/validation helpers + tests                               | ./web/src/auth.js, ./web/src/auth.test.js, ./web/src/api.js                            |
| Web git repository page                                                  | ./web/src/components/GitReposPage.jsx                                                  |
| Web git repository state                                                 | ./web/src/useGitRepos.js                                                               |
| Web file explorer                                                        | ./web/src/components/FileExplorer.jsx                                                  |
| Web namespace explorer                                                   | ./web/src/components/ClojureExplorer.jsx                                               |
| Web notes page (view, search, create, edit)                              | ./web/src/components/NotesPage.jsx                                                     |
| Web notes state + API calls                                              | ./web/src/useNotes.js                                                                  |
| Web note form/validation helpers + tests                                 | ./web/src/notes.js, ./web/src/notes.test.js                                            |
| Web `[[Title]]` link parsing + Markdown rendering                        | ./web/src/noteLinks.js, ./web/src/noteLinks.test.js, ./web/src/components/Markdown.jsx |
| Web shared date formatting                                               | ./web/src/format.js                                                                    |

Make sure to maintain files like this that list major file and subdirectories and what they contain.

## Wiki

Maintain a user wiki in ./agent-wiki. It should contain everything a user
would need to install clai, create sessions in both the CLI REPL and the UI,
create plugins, and anything else you think would be applicable.

Write it in Markdown, and make sure to link between pages where appropriate.

## Notes

Maintain a set of notes for yourself in ./agent-notes. Make sure to record
important decisions, why they were made, where relevant files are, etc.
Make sure to update them if you see something in them you know is incorrect.
