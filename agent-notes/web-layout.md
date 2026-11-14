# Web UI layout notes

## Timeline entries must not flex-shrink

Both `Chat.jsx`'s timeline (`.timeline` in `web/src/styles.css`) and the REPL
panel's output list (`.repl__output`) are **column flex containers with
`overflow-y: auto`**. Their children (`.message`, `.thought`, `.tool-call`,
`.repl__entry`) previously relied on the default `flex-shrink: 1`, so once the
content was taller than the pane, flexbox compressed every child vertically
instead of overflowing — tool calls collapsed into unreadable slivers and the
scrollbar never appeared.

Decision: timeline/REPL children get `flex: 0 0 auto`, so they keep their
natural height and the pane scrolls. Long tool output still scrolls *inside*
`.tool-call__output` / `.tool-call__section pre` (`max-height: 260px;
overflow: auto`), which is the intended nested-scroll behavior.

Rule of thumb: any new scrollable flex column should pin its children's flex
basis (`flex: 0 0 auto`) rather than let them shrink.

## Shared list page shell

The git repository page and the notes page are both "list on the left, detail
on the right" pages, so they share the shell classes in `web/src/styles.css`:

- `.list-page`, `.list-page__header`, `.list-page__heading`, `.list-page__body`,
  `.list-page__list`, `.list-page__detail`, `.list-page__empty`, ...
- `.list-panel`, `.list-panel__head` for the right-hand content panels.

These were named `git-page*` / `git-panel*` until the notes page needed them;
the rename touched only `styles.css` and `GitReposPage.jsx`. Page-specific
pieces stay prefixed with their own page (`.git-repo-card`, `.note-card`,
`.note-links`, ...). A third list page should reuse the shell rather than
copy those rules.

`web/src/format.js` holds `formatTimestamp` (the full timestamp a detail panel
shows) and `formatRelativeTime` (the compact `3m ago` a list row shows); both
pages use them.
