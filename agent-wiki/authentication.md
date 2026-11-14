# Signing in

The web UI and the JSON API are private to the accounts stored in the daemon's
PostgreSQL database. `clai web` therefore starts at a sign-in form, and signing
in lands you on [the web console](web-console.md).

```
┌──────────────────────────────┐
│ λ  clai / web                │
│    Sign in                   │
│                              │
│ Username  [ admin          ] │
│ Password  [ •••••          ] │
│                              │
│          [   Sign in   ]     │
└──────────────────────────────┘
```

## The sample account

`schema/schema.sql` seeds one account so a fresh local database can log in:

| username | password |
| -------- | -------- |
| `admin`  | `admin`  |

The container's `schema` service in `docker-compose.yml` applies that DDL, and
`clai` applies it again on first use, so the account exists as soon as the
database has the tables. See [Local database](database.md) for starting it.

**Change it before anyone else can reach the daemon.** The password is stored
as a salted PBKDF2 digest, never in plaintext, so replace the digest with one
of your own:

```clojure
;; lein repl (or the agent's eval_clojure tool)
(require '[clai.daemon.postgres :as pg] '[clai.daemon.postgres.users :as users])

(def client (pg/open {}))                     ; uses clai.edn / CLAI_POSTGRES_*
(users/create-user! client {:username "you" :password "a long passphrase"})
(users/list-users client)                     ; => [{:userId "1" :username "admin"} ...]
```

Then remove the sample account (or reset its password):

```sh
docker compose exec postgres psql -U clai -d clai \
  -c "DELETE FROM app_user WHERE username = 'admin'"
```

To reset a password without deleting the account, compute a digest and store
it:

```clojure
(require '[clai.auth :as auth])
(auth/hash-password "a long passphrase")
;; => "pbkdf2-sha256$210000$...$..."
```

```sh
docker compose exec postgres psql -U clai -d clai \
  -c "UPDATE app_user SET password_hash = '<paste the digest>' WHERE username = 'you'"
```

Usernames are compared trimmed and case-insensitively, so `Admin` signs in as
`admin`.

## Sessions

Signing in sets an `HttpOnly` cookie named `clai_session`, valid for seven
days; **Sign out** in the top bar revokes it immediately. The cookie is
`SameSite=Lax`, which is enough because the UI and the API share an origin
(the Vite dev server proxies `/api` and `/ws` to the daemon).

If a session expires while the tab is open, the next request takes you back to
the sign-in form; the console stops reconnecting in the meantime.

## Using the API without a browser

The same cookie or a bearer token works from `curl`. A login prints the cookie
into a jar, and every later request reuses it:

```sh
curl -c cookie.txt -X POST -H 'content-type: application/json' \
  -d '{"username":"admin","password":"admin"}' \
  http://127.0.0.1:8080/api/auth/login

curl -b cookie.txt http://127.0.0.1:8080/api/sessions
```

Or take the raw token (the login response body does not contain it; copy it
out of the `Set-Cookie` header) and send it as a header:

```sh
curl -X POST -H 'content-type: application/json' \
  -d '{"username":"admin","password":"admin"}' -D - \
  http://127.0.0.1:8080/api/auth/login | grep -i set-cookie
# set-cookie: clai_session=<token>; Path=/; HttpOnly; SameSite=Lax; ...

curl -H "authorization: Bearer <token>" http://127.0.0.1:8080/api/sessions
```

Without either, `/api/*` and the `/ws` upgrade answer:

```
401 {"error": "Authentication required"}
```

The two exceptions are `POST /api/auth/login` and `GET /api/auth/me`, which the
sign-in form itself calls.

## What is not protected

- The HTML, CSS, and JavaScript of the UI are served to anyone; only the data
  behind them is private.
- Every account sees every session, note, and repository. Accounts are a door,
  not a permission system.
- The REPL tab is a live Clojure REPL in the daemon's JVM, and the agent can
  run commands. Anyone with an account has that power, so keep the daemon on
  loopback (`clai web` binds `127.0.0.1` by default) rather than treating a
  login as a sandbox.

## Related

- [The web console](web-console.md): the pages you reach after signing in.
- [Local database](database.md): the PostgreSQL container that holds the users.
- [Models and providers](providers.md): configure the model behind the agent.
- [Plugins](plugins.md): install and write plugins.
