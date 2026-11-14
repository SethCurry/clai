# Web UI routing (react-router)

## Decision

`web/src/App.jsx` used to be one `Console` component holding a `view` string in
`useState` and picking between the session console, `GitReposPage`, and
`NotesPage` with a ternary. The pages are now real routes, so a URL names a
page, the back button works, and the pages are bookmarkable and reloadable.

```
/login          LoginPage (only page an anonymous browser may see)
/               SessionsPage        the console: sidebar, chat, REPL, explorer
/git-repos      GitReposPage
/notes          NotesPage
*               NotFoundPage        unknown path, still inside the shell
```

Everything except `/login` sits under one pathless layout route whose element
is `RequireAuth`; the pages render into the `<Outlet/>` that `AppLayout`
renders.

## Where each piece lives

- `web/src/main.jsx` — mounts `<BrowserRouter><App/></BrowserRouter>`. Putting
  the router here keeps `App` to "auth gate plus route table".
- `web/src/App.jsx` — `useAuth`, the `/login` route (which redirects to `/`
  once the user is signed in), and `RequireAuth`.
- `web/src/ClaiContext.jsx` — `ClaiProvider` (mounts `useClaiSocket` once) and
  `useClai` (the hook pages read).
- `web/src/components/AppLayout.jsx` — the signed-in shell: topbar with the
  page tabs as `NavLink`s, connection/model/nREPL pills, user, sign-out,
  `<Outlet/>`, and the daemon's error toasts.
- `web/src/components/SessionsPage.jsx` — the console page and its own state
  (which panels are open, the explorer tab, the agent-config dialog).
- `web/src/components/NotFoundPage.jsx` — the `*` route.

## Why the WebSocket moved above the pages

`useClaiSocket` used to live inside `Console`, which was fine while the git and
notes pages replaced the console (the connection dropped and reconnected
anyway). With routes it is no longer acceptable: navigating to `/notes` and
back would drop the socket, lose the streamed conversation, and re-replay it
from `load_session`.

So the connection is mounted by `ClaiProvider` inside `RequireAuth`, above the
route table, and pages read it with `useClai()`. `AppLayout` and
`SessionsPage` are the only consumers today (`AppLayout` for the status pills
and error toasts, `SessionsPage` for everything else).

Corollary: `RequireAuth` renders `ClaiProvider` only for an authenticated user,
because mounting the provider is what starts the WebSocket. Signing out
unmounts it and closes the socket.

## Page state vs app state

App-level (survives navigation, lives in `useClai`): connection status, session
list, agent configs, plugins, the loaded conversation and its streamed
updates, REPL entries.

Page-level (resets when you leave the page): `replOpen`, `explorerOpen`,
`explorerTab`, `configManagerOpen` in `SessionsPage`; the git and notes page
editors and selections.

## The panel toggles moved into the sidebar

The topbar used to carry "Hide/Show REPL" and "Hide/Show explorer" buttons, but
those control the sessions page only, and that page's state no longer lives in
the shell. They are now a `panels` row in `SessionSidebar` (`PanelToggles`),
styled with the topbar's `.view-tab` classes (`aria-pressed` marks the state).
The "Back to sessions" buttons on the git and notes pages went away for the
same reason: the topbar's tabs already do that job.

## Session ids are deliberately not in the URL

The selected session is still WebSocket state (`load_session` / `new_session`),
not `/sessions/:id`. Making it a route would mean the page has to reconcile
URL, socket, and the daemon's active session in both directions, and the socket
already replays the active conversation on reconnect (`activeSession` in
`useClaiSocket`). Do that as a separate change if deep links to a conversation
are ever wanted.

## The daemon must serve `index.html` for unknown paths

`clai.daemon.http/static-response` already falls back to `index.html` for any
path whose last segment has no dot and which is not under `/api` or `/assets`,
so `/git-repos` and `/notes` survive a reload. Keep new routes dotless, or
extend that fallback. Vite's dev server does the same for `npm run dev`.
