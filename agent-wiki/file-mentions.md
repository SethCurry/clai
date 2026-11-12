# Mentioning files and folders in the web UI

When you are typing a prompt in the web UI, typing `@` starts a file mention.
A popup opens above the composer listing the files and folders in the
session's working directory (`cwd`), and the list narrows as you keep typing.

- `@` alone lists the top level of the session's `cwd`.
- `@src/cl` lists the entries of `src/` whose names start with `cl`.
- `@src/clai/daemon/` lists the contents of that directory.

Matching is case-insensitive. Directories come first, followed by files, each
ordered by name. Dotfiles are hidden until you type a `.` yourself, so `@` on
a repository root is not buried in `.git` and `.github` entries.

Pick an entry with the mouse, or use the keyboard:

| Key            | Action                          |
| -------------- | ------------------------------- |
| `↓` / `↑`      | Move through the list           |
| `Enter` or `Tab` | Insert the highlighted entry   |
| `Esc`          | Dismiss the popup               |

Picking a **folder** inserts `@path/to/folder/` and immediately lists that
folder, so you can walk down a tree one level at a time. Picking a **file**
inserts `@path/to/file` followed by a space and closes the popup.

The mention is plain text in the prompt. clai reads the repository with its
tools, so `@` is a way to point at a path quickly, not a separate attachment
mechanism -- the agent sees exactly the text you send.

## Where the listing comes from

The browser asks the daemon:

```text
GET /api/sessions/<session-id>/completions?path=src/cl
```

The daemon resolves `path` against the session's `cwd` and replies with the
entries it is willing to offer. Paths that escape the working directory (for
example `@../secrets` or an absolute path) return no entries, so completions
only ever show what the session can reach from its own directory.

Nothing needs to be configured: as long as the session has a `cwd`, `@`
completion works. A session created without a working directory has nothing to
list, and the popup says so.

## See also

- [Browsing files in the web UI](file-explorer.md): the Files panel, which
  shows the same directory as a tree and previews file contents.
- [Plugins](plugins.md): add behavior to every prompt.
- [Git repositories and worktrees](git-repositories.md): where worktrees live
  when you want a session pointed at a checkout.
