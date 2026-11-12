# Web UI namespace explorer

## Decision

The web UI's explorer panel has two tabs: **Files** (the session's `cwd`, see
[file-explorer.md](file-explorer.md)) and **Namespaces** (the Clojure runtime
the REPL panel evaluates in). The Namespaces tab lists the namespaces loaded in
the daemon's JVM, the public Vars of each namespace when it is opened, and one
Var's docstring, argument lists, malli schema, source, and value when it is
clicked. The selected session's namespace (`sessions.<id>`, or `user` when no
session is selected) starts open.

## Why tabs instead of a fifth column

The grid was already sidebar + chat + REPL + Files. A fifth column squeezes the
chat below usefulness at 1440px, so the two explorers share the one column and
the tab switcher lives in the panel header (`ExplorerPanel.jsx`). Only the
selected tab is mounted: `FileExplorer` keeps a `{path -> listing}` cache and
`ClojureExplorer` a `{namespace -> vars}` cache, and neither is worth keeping
alive while hidden. Each is keyed by the session id, so switching sessions
starts both trees from scratch.

The panel now owns the header's tabs and close button; the two explorers render
a `.explorer__panel` body with a context line (the `cwd`, the session's
namespace) and a Refresh button.

## Why in-process introspection, not nREPL

`clai web` starts the nREPL server and the HTTP daemon in the same JVM
(`clai.core/serve-web`), and the REPL panel's `eval` calls go to that server.
So `all-ns` / `ns-interns` / `var-get` in the daemon see exactly what the REPL
sees, with no bencode round trip, no printing/parsing of values, and no need
for the browser to hold an nREPL connection. `clai.daemon.namespaces` is the
only place that touches the runtime.

## Implementation

- `src/clai/daemon/namespaces.clj`
  - `list-namespaces` -> `{:namespaces [{:name :doc :varCount}]}` for every
    `all-ns`, sorted by name. `varCount` counts public interns only.
  - `namespace-vars` -> `{:namespace :doc :missing :vars [...]}`. Vars are
    `ns-interns` minus private ones, sorted by name; **refers are excluded**
    (they belong to the namespace that defined them, and including them would
    put all of `clojure.core` under every namespace). An unknown namespace
    answers `:missing true` rather than erroring, because the explorer asks
    about `sessions.<id>` namespaces that may not exist yet.
  - `var-detail` -> doc, arglists (`"[coll] [separator coll]"`), pr-str'd
    `:malli/schema`, `:macro`/`:private`/`:dynamic`/`:deprecated`, `:file` and
    `:line`, `:source` (`clojure.repl/source-fn`, capped at 20k chars), and
    `:value` (`pr-str` with `*print-length*` 20 / `*print-level*` 4, capped at
    4k chars). The value is skipped for unbound Vars (`Var.isBound`) and for
    functions, whose printed form is just an object id. Resolving goes through
    `ns-resolve`, so a referred name is found and `:namespace` is its home.
- `src/clai/daemon/http.clj` -- `GET /api/namespaces`,
  `GET /api/namespaces/<ns>/vars`, `GET /api/namespaces/<ns>/var?name=`
  (400 without `name`, 404 for an unknown Var). Path segments are decoded with
  `codec/url-decode` via `path-namespace`.
- `src/clai/instrument.clj` -- requires `clai.daemon.namespaces` so its
  `:malli/schema`s are collected.
- `web/src/cljExplorer.js` -- pure helpers: `visibleRows` flattens the lazy
  namespace tree plus the filter into rows, `sortNamespaces` pins the session
  namespace (then other `sessions.*`) above library namespaces, `docSummary`
  takes the first docstring line for the row hint, `varSymbol` builds
  `ns/name`.
- `web/src/components/ClojureExplorer.jsx` -- the tab: `expanded` (a Set
  seeded with the session namespace), the namespace listing, the `{ns -> vars}`
  cache, the filter, the selected Var, and the fetched detail. Effects load the
  namespace list, load every open namespace that has no listing yet, and
  re-read the selection, aborting in flight reads on unmount (same shape as
  `FileExplorer`).
- `web/src/components/ExplorerPanel.jsx` -- tabs ("Files", "Namespaces") and
  the close button.
- `web/src/App.jsx` -- `explorerOpen` + `explorerTab` replace `filesOpen`; the
  layout class is now `layout--with-explorer`.
- `web/src/styles.css` -- `.explorer__tabs`, `.explorer__tab`, `.explorer__filter`,
  `.explorer__panel`, `.explorer__hint`, and the `.clj-preview*` block.

## Filter semantics

Filtering happens in the browser over what has been fetched: namespace rows
match on name or docstring, Var rows on name or docstring. A namespace that
matched keeps all of its Vars; a namespace that only *contains* a matching Var
narrows to the matching Vars. Namespaces whose Vars have not been fetched
cannot be matched by their Vars -- that is the price of lazy loading, and it is
why the session namespace starts open.

## Testing

- `test/clai/daemon/namespaces_test.clj` -- a fixture namespace (documented
  fn, macro, private Var, value, unbound Var) checking listing, ordering,
  private filtering, detail, and source reading.
- `test/clai/daemon/http_test.clj` -- `clojure-namespaces-can-be-browsed`
  covers the three routes, the missing-namespace reply, the 400 without
  `name`, and the 404 for an unknown Var.
- `web/src/cljExplorer.test.js` -- `sortNamespaces`, `visibleRows`
  (indentation, session ordering, notes, filters), `docSummary`, `varSymbol`.

## Deliberately not done

- No eval or copy-into-REPL button beyond copying the qualified symbol; the
  REPL panel keeps its own draft state.
- No caching or watching of the runtime: a Var defined at the REPL shows up
  after **Refresh**.
- No Vars of a namespace's refers, and no Java methods/classes: this is a
  Clojure namespace browser, not a `dir` replacement.
