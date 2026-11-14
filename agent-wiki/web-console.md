# The web console

`clai web` serves the browser UI. After [signing in](authentication.md) you land
on the console, and the topbar switches between its three pages:

| Page             | URL          | What it is                                                             |
| ---------------- | ------------ | ---------------------------------------------------------------------- |
| Sessions         | `/`          | The console: conversations, the REPL, and the file/namespace explorer. |
| Git repos        | `/git-repos` | The repositories and worktrees clai works with.                        |
| Notes            | `/notes`     | The Markdown knowledge base clai's note tools read and write.          |

The URLs are real pages, so you can bookmark one, reload it, and use the
browser's back and forward buttons. `/login` is the sign-in form; visiting any
page while signed out sends you there, and signing out sends you back.

## The sessions page

```
┌───────────────┬─────────────────────────────┬──────────────┬───────────┐
│ Sessions      │ chat                        │ REPL         │ explorer  │
│ agent config  │                             │              │           │
│ + New session │   ...timeline + composer    │  user=>      │ Files     │
│ panels: REPL  │                             │              │ Namespaces│
│         Explorer                             │              │           │
│ session list  │                             │              │           │
└───────────────┴─────────────────────────────┴──────────────┴───────────┘
```

- **+ New session** starts a conversation in the daemon's working directory;
  **Agent config** picks the configuration it uses (see
  [plugins](plugins.md) and the model settings in [providers](providers.md)).
- **panels** hides and shows the two right-hand panes. Their own close buttons
  do the same, so you can reclaim the space and bring them back from there.
- The REPL runs Clojure in the daemon's own JVM; see
  [evaluating Clojure with the agent](eval-clojure.md) for the namespace it
  starts in.
- The explorer has a [Files](file-explorer.md) tab and a
  [Namespaces](namespaces.md) tab.
- Typing `./` in the composer completes paths from the session's working
  directory; see [path completion](path-completion.md).
- **Session titles.** A new session is named for you: after its first
  message, clai asks the model for a 1 to 5 word title and puts it in the
  sidebar and above the conversation while the reply is still being written.
  Click the title to rename it, and clear the text to go back to "Untitled
  session". Naming is a separate, tiny model call, so it never becomes part of
  the conversation you see or reload.
- **Compaction.** When a long session's history gets too big for the model, the
  older messages are replaced by a summary and an *Earlier messages compacted*
  block appears in the timeline. See [session compaction](compaction.md).

The conversation, the session list, and the daemon connection stay alive while
you visit the git and notes pages: switching tabs does not reload the timeline.
The panels and dialogs belong to the sessions page, so they go back to their
defaults (REPL and explorer open) when you return.

The status dot beside "Sessions" and the topbar's connection label tell you
whether the WebSocket is up. A dropped connection reconnects on its own, and
the loaded conversation is replayed when it does.
