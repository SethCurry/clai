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

| Functionality               | Location                                 |
| --------------------------- | ---------------------------------------- |
| Ollama API client           | ./src/clai/agent/providers/ollama.clj    |
| Logging                     | ./src/clai/log.clj                       |
| Plugin runtime              | ./src/clai/plugins.clj                   |
| Today's date plugin         | ./src/clai_plugin_todays_date/plugin.clj |
| PostgreSQL client           | ./src/clai/daemon/postgres.clj           |
| Session storage             | ./src/clai/daemon/postgres/sessions.clj  |
| Git repo/worktree storage   | ./src/clai/daemon/postgres/git.clj       |
| `@`-mention file completion | ./src/clai/daemon/files.clj              |
| Storage DDL                 | ./schema/schema.sql                      |
| Web UI                      | ./web                                    |

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
