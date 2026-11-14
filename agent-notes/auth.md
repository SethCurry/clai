# Authentication (users, sessions, login page)

## Decision

The daemon used to serve its JSON API and WebSocket to anyone who could reach
the port. It now requires a session for everything except the login page, and
accounts live in PostgreSQL next to sessions and notes.

Pieces:

- `src/clai/auth.clj` — pure crypto and header parsing: PBKDF2 password
  digests, random session tokens, token digests, cookie parsing/formatting.
  No database, no Ring, so it is cheap to test.
- `src/clai/daemon/postgres/users.clj` — the `app_user` and `auth_token`
  tables, plus `make-service`, which binds `:authenticate`, `:issue-token`,
  `:resolve-token`, `:revoke-token` to one client.
- `src/clai/daemon/http.clj` — the `/api/auth/{login,me,logout}` routes, the
  `401` guard in `route`, and the `:auth` service on the daemon `State`.
- `web/src/{auth.js,useAuth.js,components/LoginPage.jsx}` — the login form and
  the session state. `web/src/App.jsx` owns the routes: `/login` is the only
  page an anonymous browser reaches, `RequireAuth` redirects everything else
  there, and only once `useAuth` reports `authenticated` does it mount
  `ClaiProvider` (which opens the WebSocket) and the console shell. See
  [web-routing.md](web-routing.md).

## Why no new dependency

Passwords use `javax.crypto` PBKDF2-HMAC-SHA256 (210k rounds, 16-byte salt),
not bcrypt/scrypt/argon2. Both would need a new library (`buddy-hashers`,
`bcrypt`, ...); the JDK primitive is strong enough for one daemon behind a
loopback bind, and the encoded digest
(`pbkdf2-sha256$<rounds>$<salt>$<digest>`) carries its round count so it can be
raised later without invalidating stored hashes.

The seed digest in `schema/schema.sql` was produced once with
`(clai.auth/hash-password "admin")` and pasted in, because SQL cannot derive
PBKDF2. `ON CONFLICT DO NOTHING` keeps the seed from resetting a changed
password on the next `ensure-schema!` (which runs on every daemon start).

## Sessions

- A login mints 32 random bytes, base64url-encoded, and stores only its
  SHA-256 hex digest in `auth_token`. A table dump therefore hands out no
  working sessions.
- The browser gets the token in `clai_session`, `HttpOnly` + `SameSite=Lax`,
  seven-day `Max-Age`. `Secure` is deliberately off: the daemon serves plain
  HTTP on loopback, and a `Secure` cookie would be dropped.
- Non-browser clients may send `Authorization: Bearer <token>`; the header
  wins over the cookie (see `clai.auth/request-token`).
- Expired rows are deleted whenever a token is issued, so no cron job is
  needed. Logout deletes the row and clears the cookie.
- `authenticate` verifies unknown usernames against a throwaway digest
  (`no-such-user-hash`, a delay) so login timing does not reveal which
  usernames exist.

## Choices worth remembering

- `GET /api/auth/me` answers **200** with `{"authenticated": false}` when the
  request has no session. The login page asks it on every load, and a 401 there
  would be an expected error in the browser console. It is one of the three
  public routes (`public-routes` in `http.clj`), with `POST /api/auth/login`
  and `POST /api/auth/logout`; logout is public so an already-expired session
  can still clear its cookie.
- The React build is served **without** authentication (`api-path?` draws the
  line at `/api/` and `/ws`), because the login page is part of it. Static
  file serving never touches the database.
- `request-user` is only called for non-public API paths, so serving
  `/assets/*.js` costs no query.
- Every authenticated route 401s before route matching, so unknown `/api/`
  paths do not reveal whether a route exists to an anonymous caller.
- `start-server!` now defaults to `:host "127.0.0.1"` — the docstring always
  said so, the code said `0.0.0.0`. With a seeded `admin`/`admin` documented
  in a public repo, binding the network by default would be a footgun.
- Web UI: `api.js` announces a `clai:unauthorized` window event on any 401
  from an authenticated call; `useAuth` listens and returns to the login page.
  `useClaiSocket` checks `/api/auth/me` once before retrying a dropped socket,
  so an expired session stops the reconnect loop instead of hammering the
  daemon. The login form uses `auth.js` (raw `fetch`) so a wrong password does
  not fire that global event.
- Failed logins log the username at `:warn` (`::login-failed`); passwords are
  never logged.

## Tests

- `test/clai/auth_test.clj` — digests, salts, malformed hashes, token
  randomness, cookie parsing, `Set-Cookie` shaping.
- `test/clai/daemon/postgres/users_test.clj` — pure helpers always; the
  `admin`/`admin` seed and the account/token round trip only with
  `CLAI_TEST_POSTGRES_URL` set (include credentials in the URL, for example
  `jdbc:postgresql://localhost:5432/clai?user=clai&password=secret`).
- `test/clai/daemon/http_test.clj` — its `state` helper installs a fake
  `AuthService` (`test-auth`) and `request` sends
  `cookie: clai_session=test-token`, so the existing tests stay logged in;
  `anonymous` strips the cookie for the 401 tests.
- `web/src/auth.test.js` — credential trimming and validation.

## Not done (deliberately)

- No multi-user isolation: every account sees every session, note, and
  repository. Accounts are an outer door, not a permission model.
- No login rate limiting or lockout.
- No password-change HTTP endpoint; `users/create-user!` and a direct `UPDATE
  app_user SET password_hash = ...` are the way (see `agent-wiki/auth.md`).
