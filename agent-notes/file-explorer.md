# Web UI file explorer

## Decision

The web UI has a read-only file explorer panel, mounted to the right of the
Clojure REPL panel. It browses the selected session's `cwd`, opens folders
lazily, and previews one file's text at a time.

## Why a tree beside the REPL

- The REPL is where you poke at the running daemon; reading the file you are
  about to evaluate is the obvious next thing to do, and doing it in the same
  screen beats switching to an editor and back.
- The sidebar and chat already own their columns, so the panel is a fourth
  grid column rather than a drawer or modal. Hiding it is one button in the
  top bar.

## Why it reuses `clai.daemon.files`

- The daemon already had the only piece that must not live in the browser:
  resolving a relative path safely inside a session's `cwd`. `list-entries`
  (prefix completion) and `read-file` (preview) share the same
  `canonical-dir` / `resolve-in-root` / `normalize-path` helpers, so there is
  one place where "never leave the working directory" is enforced.
- The explorer asks `list-entries` with `:include-hidden? true` and a `:path`
  that ends in `/`, which is exactly the "list this whole directory" case of
  the completion query, so no second listing algorithm was needed.

## Implementation

- `src/clai/daemon/files.clj` -- `:include-hidden?` on `CompletionRequest`
  (dotfiles are otherwise offered only when the typed prefix starts with
  `.`), plus `ReadRequest`, `FileContent`, and `read-file`. `read-file`
  returns nil for a blank path, a directory, a missing file, or a path that
  escapes `:root`; otherwise `{:path :name :content :truncated :binary}` (plain names, so the
  JSON keys need no `?`).
  `MaxPreviewBytes` (128 KiB) caps `:content`, and `binary-bytes?` (a NUL
  byte scan) blanks `:content` for binaries.
- `src/clai/daemon/http.clj` -- `GET /api/sessions/<id>/files?path=src/&limit=N`
  (`directory-listing`, 200 with `{:directory :prefix :entries}`, 404 for an
  unknown session) and `GET /api/sessions/<id>/file?path=src/app.clj`
  (`file-preview`, 404 when there is no such file). Query parsing moved into
  `query-params` so the three filesystem endpoints decode the query string
  the same way.
- `web/src/fileExplorer.js` -- pure helpers: `visibleRows` flattens the lazy
  `{path -> listing}` cache plus the set of open folders into the flat rows
  the panel renders (with `depth`, and `note`/`error` rows for branches that
  are loading, empty, truncated, or unreadable), `fileName`, and `LIST_LIMIT`
  (500, the per-directory page size).
- `web/src/components/FileExplorer.jsx` -- the panel: `expanded` (a Set,
  `""` open by default), `listings` cache, `selected` path, and the fetched
  `preview`. Effects fetch every open folder that has no listing yet and
  re-read the selected file, aborting in flight reads on unmount and when the
  selection changes. `App.jsx` mounts it with `key={sessionId}` so switching
  sessions starts from a fresh tree.
- `web/src/api.js` -- `responseBody`, the JSON-or-`{}` body reader that used
  to live inside `useClaiSocket.js`.
- `web/src/styles.css` -- `.layout--with-files` and
  `.layout--with-repl.layout--with-files` add the column; the `explorer`
  block styles the panel. Below 1180px both panels drop to full-width rows.

## Testing

- `test/clai/daemon/files_test.clj` -- hidden-entry opt-in, `read-file`
  content, truncation, binary detection, and refusal of directories, missing
  files, and escaping paths.
- `test/clai/daemon/http_test.clj` -- the two endpoints through the Ring
  handler, including 404s for a missing file and an unknown session.
- `web/src/fileExplorer.test.js` -- `visibleRows` (indentation, open
  folders, loading/empty/error notes, truncation note) and `fileName`, run by
  `npm test` in `web/`.

## Deliberately not done

- No editing, creating, renaming, or deleting files from the panel. The
  agent's tools do that.
- No syntax highlighting or line numbers in the preview; it is a `<pre>`.
- No file watching. `Refresh` is explicit so the panel never competes with a
  prompt that is rewriting the tree.
