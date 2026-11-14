# Path completion (`./`) in the web UI

## Decision

Typing a word that starts with `./` in the web UI's prompt composer opens a
popup of files and folders from the session's `cwd`, filtered as the user
types. The daemon owns the listing; the browser only renders it. The trigger
used to be `@`, which was changed to `./` because a leading `./` reads as a
path (and does not collide with `@` in email addresses and handles).

## Why the daemon lists files

- The browser must not need filesystem access, and the daemon is already the
  process that runs the agent's tools, so the paths clai offers are exactly
  the ones it can read.
- The session's `cwd` lives in PostgreSQL, not in the UI, so the client would
  otherwise need to be told a path it might have stale.
- A tiny endpoint is cheaper than shipping a file tree to the client and
  keeping it fresh as files change.

## Implementation

- `src/clai/daemon/files.clj` -- `list-entries`, the pure directory lister.
  It treats the typed text as a path relative to `:root`: everything before
  the last `/` names the directory and the final segment is a
  case-insensitive prefix. A leading `./` is normalized away, so the UI may
  send the typed text as-is. Directories are listed first and their `:path`
  ends in `/` so the UI can keep descending. Dotfiles are offered only when
  the prefix starts with `.`. A resolved directory that escapes `:root`
  (via `..` or a symlink) returns no entries. `DefaultLimit` (100) caps a
  result unless the request passes `:limit`.
- `src/clai/agent/core.clj` -- `session-cwd!` resolves a session id to its
  working directory. `src/clai/daemon/postgres/sessions.clj` gained
  `session-cwd`, a metadata-only read, because completion requests happen on
  every keystroke pause and must not load the session's event history or log
  like `load-session!` does.
- `src/clai/daemon/http.clj` -- `GET /api/sessions/<id>/completions?path=...`
  returns `{:directory :prefix :entries}`; 404 for an unknown session. Query
  parsing uses `ring.util.codec/form-decode`, and a non-numeric `limit` falls
  back to the default instead of erroring.
- `web/src/paths.js` -- pure `tokenAt`/`pathAt` helpers, plus
  `PATH_PREFIX` (`"./"`). A path token is a whitespace-delimited word that
  starts with `./`; `path` is the text after the prefix, so `./src/cl` sends
  `src/cl` to the daemon and a pick is written as `./` + the entry's path.
  `src/cl` alone, `...` and `a./b` do not trigger the popup.
- `web/src/usePathAutocomplete.js` -- popup state, 120 ms debounce, aborted
  in-flight requests, arrow/Enter/Tab/Escape handling, and applying a pick
  back into the draft. Picking a directory reopens the list for it; picking a
  file appends a trailing space and closes.
- `web/src/components/PathAutocomplete.jsx` -- the listbox above the
  composer. Rows act on `mousedown` (with `preventDefault`) so focus stays in
  the textarea. Styles are the `autocomplete` block in `web/src/styles.css`.

## Testing

- `test/clai/daemon/files_test.clj` covers ordering, prefixes, hidden
  entries, nested paths, escaping the root, missing directories, and limits,
  using a temporary tree.
- `test/clai/daemon/http_test.clj` covers the endpoint through the Ring
  handler and its 404. The fake agent stubs `agent/session-cwd!`.
- `web/src/paths.test.js` covers the token helpers and runs with
  `npm test` in `web/` (`node --test`). There is no browser test runner; the
  rest of the hook is exercised by hand.

## See also

- `agent-notes/file-explorer.md` -- the file explorer panel, which reuses
  `clai.daemon.files` for browsing and previewing.

## Deliberately not done

- No completion in the Clojure REPL panel -- it is not a prompt.
- No file-content preview or fuzzy matching; it is a plain prefix match on
  names.
- Absolute paths and `~` are not accepted. The popup only browses the
  session's `cwd`, so nothing outside it can be discovered.
