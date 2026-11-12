# Browsing files in the web UI

The web UI has a **Files** panel. It shows the working directory of the
session you have selected, and it sits to the right of the Clojure REPL
panel.

- Click a folder to open it, and click it again to close it. Folders are
  listed before files, both in name order, and dotfiles such as `.github` or
  `.clai` are included.
- Click a file to preview it in the pane at the bottom of the panel. Click it
  again (or the `×`) to stop previewing it.
- **Refresh** re-reads every open folder and the file you are previewing, so
  it picks up changes the agent made while you were watching.
- The `×` in the panel header hides the panel; **Show files** in the top bar
  brings it back.

The panel is read-only. It is there to answer "what is in this session's
directory?" without leaving the browser, not to edit files -- ask the agent
for that.

## What it reads

The panel only browses the selected session's `cwd`, exactly like
[`@` mentions](file-mentions.md). The daemon answers two requests:

```text
GET /api/sessions/<session-id>/files?path=src/&limit=500
GET /api/sessions/<session-id>/file?path=src/clai/core.clj
```

`path` is relative to the session's `cwd`, and a `path` that ends in `/` lists
the whole folder. Anything that escapes the working directory -- `../`,
an absolute path, or a symlink pointing outside -- is refused, so the panel
can never read more of the filesystem than the session can.

Previews are capped at 128 KiB. A larger file shows its first 128 KiB with a
note, and a file that turns out to be binary (it contains a NUL byte) is
named but not shown.

## Settings worth knowing

- Folders are fetched lazily, the first time you open them, so opening a big
  repository does not read all of it.
- A folder with more than 500 entries says so instead of listing the rest.
  Narrow it down by opening a subfolder, or use `@` completion in the prompt
  composer, which filters by name as you type.

## See also

- [Mentioning files and folders](file-mentions.md): the `@` completion popup,
  which searches the same directory.
- [Plugins](plugins.md): add behavior to every prompt.
