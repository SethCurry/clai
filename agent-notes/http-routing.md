# HTTP routing (`clai.daemon.http`)

## The route table

`route` used to be one ~250-line `cond` in which every endpoint repeated the
same three moves: build a regex, `re-matches` it, and pull `(second ...)` out of
the result (sometimes also `codec/url-decode`-ing it). Every resource repeated
five nearly identical `get`/`post`/`put`/`delete` branches, differing only in
the agent function it called and the noun in its 404 message.

It is now table-driven:

- A route is `{:method :get :template "/api/notes/:noteId" :handler f}`.
  `compile-route` turns `:template` into an anchored `:regex` (`:name` segments
  become `([^/]+)`) plus the keyword `:params` it captures; the table is
  compiled once, at namespace load.
- `find-route` walks the table in order and returns `[route params]` with the
  captures percent-decoded, or nil. First match wins, so a literal path
  (`/api/notes/search`) or a deeper template
  (`/api/sessions/:sessionId/files`) must be listed before the capture template
  that would swallow it.
- `route` dispatches by calling `(:handler route)` with a single
  `RouteContext` map: `{:state :request :agent :params :query}`. Handlers are
  small private functions, one per endpoint, and know nothing about regexes.
- `crud-routes` generates the five conventional REST routes from a `CrudSpec`
  (`:path :id :label :list :load :create :update :delete`). The four stored
  resources (agent configs, git repos, git worktrees, notes) are just four
  specs in `agent-config-spec` / `git-repo-spec` / `git-worktree-spec` /
  `note-spec`; `api-routes` is `special-routes` plus `(mapcat crud-routes ...)`.

## Adding an endpoint

- Shares the CRUD shape -> add or extend a spec. `:list` takes
  `[agent query]`, `:load`/`:delete` take `[agent id]`, `:update` takes
  `[agent id body]`, and ids arrive already decoded.
- Anything else -> write a handler over `RouteContext` and add a row to
  `special-routes`, above the capture templates that could shadow it.
- Non-`/api/` paths fall through to `fallback-response`, which serves the React
  build (with an `index.html` fallback for client-side routes) and 404s JSON for
  unknown `/api/` paths.

## Authentication guard

`route` checks the session before it consults the table, in three steps:

- `api-path?` decides whether the request is API/WebSocket (`/api/...`, `/ws`)
  or the static React build. Static files skip auth entirely, because the login
  page is part of the build and serving it must never touch the database.
- `public-route?` matches `public-routes` (`POST /api/auth/login`,
  `GET /api/auth/me`, `POST /api/auth/logout`). Those go straight to
  `dispatch`; `/api/auth/me` and the logout handler resolve the token
  themselves when they need it.
- Everything else resolves the cookie or bearer token through
  `request-user` (the `users/AuthService` on the daemon `State`) and 401s
  without one. The check runs before route matching, so an anonymous caller
  cannot tell a real `/api/` path from a made-up one.

Handlers therefore trust that they are authenticated; `RouteContext` does not
carry the user, since every account has the same reach. Details and rationale
are in `agent-notes/auth.md`.

## Shared plumbing

- `with-json-body` decodes the request body and turns a malformed or rejected
  payload into a 400; a handler throwing inside it is a client error, matching
  what the old `cond` did for every write.
- `with-session-cwd` resolves a session's working directory or 404s, shared by
  the completions/files/file endpoints.
- `not-found` keeps the `{"error": "No <thing> <id>"}` shape the UI relies on.

`test/clai/daemon/http_test.clj` has
`the-routing-table-prefers-literal-paths-and-decodes-captures`, which exercises
`find-route` directly (through `#'http/find-route`, `#'http/api-routes`).
