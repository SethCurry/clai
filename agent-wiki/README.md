# clai user wiki

- [Models and providers](providers.md): configure Ollama and OpenRouter at the
  same time and pick a provider by naming its model.
- [Local database](database.md): start the development PostgreSQL
  container, and use the pg_textsearch full-text search extension it
  ships.
- [Plugins](plugins.md): build, install, activate, and write plugins.
- [Evaluating Clojure with the agent](eval-clojure.md): the `eval_clojure`
  tool, the agent's REPL namespace, and the helpers it preloads.
- [OpenProject tasks and comments](openproject.md): configure the
  OpenProject tools and let the agent list, create, and update tasks and
  read, add, or edit their comments.
- [Git repositories and worktrees](git-repositories.md): register repositories
  and the worktrees checked out from them.
- [Notes](notes.md): create, read, update, delete, and full-text search notes,
  link them to each other, browse them in the web UI, and let the agent manage
  them with the `notes_list`/`notes_search`/`notes_read`/`notes_update`/`notes_delete`
  tools.
- [Completing file and folder paths in the web UI](path-completion.md):
  type `./` in the prompt composer to autocomplete paths from the session's
  working directory.
- [Browsing files in the web UI](file-explorer.md): the Files tab beside the
  REPL, which shows and previews the session's working directory.
- [Browsing Clojure namespaces in the web UI](namespaces.md): the Namespaces
  tab, which shows the runtime's namespaces, their Vars, docstrings, and
  source.
