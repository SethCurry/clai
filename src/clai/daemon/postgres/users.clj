(ns clai.daemon.postgres.users
  "User accounts and session tokens for `clai`'s daemon, stored on PostgreSQL.

  The tables are declared in `schema/schema.sql` and created by
  `clai.daemon.postgres/ensure-schema!`:

      app_user    id, username, password_hash, created_at
      auth_token  id, token_hash, app_user_id, created_at, expires_at

  A user is seeded by the DDL itself: `admin`, with the password `admin` (see
  the comment on the `app_user` table in `schema/schema.sql`). Change it before
  exposing the daemon to anything but your own machine.

  A login is checked with [[authenticate]] against the salted PBKDF2 digest
  `clai.daemon.auth/hash-password` wrote. A successful login mints a random
  token with [[issue-token]]; only its SHA-256 digest is stored, so the token
  exists in the browser's cookie and nowhere else. [[resolve-token]] turns a
  presented token back into a user while the row is unexpired, and
  [[revoke-token]] logs the session out.

  ## Example

      (require '[clai.daemon.postgres :as pg]
               '[clai.daemon.postgres.users :as users])

      (def client (pg/open {:host \"localhost\"}))

      (users/authenticate client \"admin\" \"admin\")  ; => {:userId \"1\" :username \"admin\"}
      (def session (users/issue-token client \"1\"))
      (users/resolve-token client (:token session))   ; => {:userId \"1\" :username \"admin\"}
      (users/revoke-token client (:token session))    ; => true"
  (:require [clai.daemon.auth :as auth]
            [clai.daemon.postgres :as postgres]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def User
  "A user account as exposed to the API: its id (always a string) and name.
  The password digest is never returned."
  [:map
   [:userId non-empty-string]
   [:username non-empty-string]])

(def Credentials
  "A username and password submitted at login, or accepted by
  [[create-user!]]."
  [:map
   [:username non-empty-string]
   [:password non-empty-string]])

(def IssuedToken
  "The result of [[issue-token]]: the token to hand to the browser, and when
  it stops working."
  [:map
   [:token #'auth/Token]
   [:expiresAt non-empty-string]])

(def AuthService
  "The authentication operations `clai.daemon.http` calls, bound to one
  database client by [[make-service]].

  `:authenticate` checks a username and password, `:issue-token` mints a
  session for a user id, `:resolve-token` finds the user behind a presented
  token, and `:revoke-token` ends that session."
  [:map
   [:authenticate [:=> [:cat non-empty-string non-empty-string]
                   [:maybe User]]]
   [:issue-token [:=> [:cat non-empty-string] IssuedToken]]
   [:resolve-token [:=> [:cat non-empty-string] [:maybe User]]]
   [:revoke-token [:=> [:cat non-empty-string] boolean?]]])

;; ---------------------------------------------------------------------------
;; Input normalization and row conversion
;; ---------------------------------------------------------------------------

(defn- blank->nil
  "`value` trimmed, or nil when it is nil or blank."
  {:malli/schema [:-> :any [:maybe non-empty-string]]}
  [value]
  (let [text (some-> value str str/trim)]
    (when-not (str/blank? text) text)))

(defn ^:no-doc row-key
  "`id` as the numeric database key it names, or nil when it does not name a
  row. Ids are exposed as strings and SQL returns numbers, so both are
  accepted."
  {:malli/schema [:-> [:maybe [:or :string :int]] [:maybe pos-int?]]}
  [id]
  (when (some? id)
    (when-let [n (if (number? id) id (parse-long (str id)))]
      (when (pos? n) n))))

(defn ^:no-doc row->user
  "Convert an `app_user` row to the public [[User]] shape."
  {:malli/schema [:-> :map #'User]}
  [row]
  {:userId   (str (:id row))
   :username (:username row)})

(defn ^:no-doc prepare-credentials
  "Validate a [[Credentials]] map: the username is trimmed (a password is
  stored and compared exactly as typed, so it is not)."
  {:malli/schema [:-> :map #'Credentials]}
  [credentials]
  (postgres/validate! Credentials
                      (-> (select-keys credentials [:username :password])
                          (update :username blank->nil)
                          (update :password str))
                      "credentials"))

;; ---------------------------------------------------------------------------
;; A digest to verify against when no user matches
;; ---------------------------------------------------------------------------

(def ^:private no-such-user-hash
  "A digest of a random throwaway password, derived on first use.

  [[authenticate]] verifies against it when the username matches no row, so a
  wrong username costs the same PBKDF2 work as a wrong password and response
  time does not reveal which usernames exist. It is a delay because deriving it
  takes a few hundred milliseconds."
  (delay (auth/hash-password (auth/new-token))))

;; ---------------------------------------------------------------------------
;; User SQL
;; ---------------------------------------------------------------------------

(def ^:private select-user-sql
  "The account matching a username, compared case-insensitively and ignoring
  surrounding space so `Admin` logs in as `admin`."
  "SELECT id, username, password_hash
     FROM app_user
    WHERE lower(trim(username)) = lower(trim(?))")

(def ^:private insert-user-sql
  "Insert an account and return its row."
  "INSERT INTO app_user (username, password_hash)
        VALUES (?, ?)
        RETURNING id, username")

(def ^:private list-users-sql
  "Every account, ordered by name."
  "SELECT id, username FROM app_user ORDER BY lower(username)")

;; ---------------------------------------------------------------------------
;; Token SQL
;; ---------------------------------------------------------------------------

(def ^:private insert-token-sql
  "Store a token digest for a user, expiring `?` seconds from now, and return
  the expiry the database chose."
  "INSERT INTO auth_token (token_hash, app_user_id, expires_at)
        VALUES (?, ?, now() + make_interval(secs => ?))
        RETURNING expires_at")

(def ^:private select-token-sql
  "The unexpired token's user, joined through the account it belongs to."
  "SELECT u.id, u.username
     FROM auth_token t
     JOIN app_user u ON u.id = t.app_user_id
    WHERE t.token_hash = ?
      AND t.expires_at > now()")

(def ^:private delete-token-sql
  "Delete one token, logging that session out."
  "DELETE FROM auth_token WHERE token_hash = ?")

(def ^:private delete-expired-tokens-sql
  "Delete every expired token. Run when a token is issued, so the table does
  not grow without bound."
  "DELETE FROM auth_token WHERE expires_at <= now()")

;; ---------------------------------------------------------------------------
;; Users
;; ---------------------------------------------------------------------------

(defn list-users
  "Every stored account, ordered by username."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'User]]}
  [client]
  (mapv row->user
        (postgres/exec! (:datasource (postgres/ready client)) ::list-users
                        list-users-sql [])))

(defn create-user!
  "Store a new account and return it.

  `credentials` is validated with [[Credentials]]; the password is hashed, and
  the digest is all that is kept. Throws an `ex-info` when the username is
  already taken."
  {:malli/schema [:-> #'postgres/client-schema #'Credentials #'User]}
  [client credentials]
  (let [{:keys [username password]} (prepare-credentials credentials)
        datasource                  (:datasource (postgres/ready client))]
    (when (postgres/exec-one! datasource ::select-user select-user-sql
                              [username])
      (throw (ex-info (str "A user named " (pr-str username) " already exists")
                      {:username username})))
    (let [user (row->user (postgres/exec-one! datasource ::create-user
                                              insert-user-sql
                                              [username
                                               (auth/hash-password password)]))]
      (tel/log! {:level :debug
                 :id    ::user-created
                 :data  {:userId (:userId user) :username (:username user)}}
                "Created clai user")
      user)))

(defn authenticate
  "The account named by `username` when `password` matches its digest, else
  nil.

  Unknown usernames are verified against a throwaway digest so they cost the
  same time as a wrong password."
  {:malli/schema [:-> #'postgres/client-schema :any :any [:maybe #'User]]}
  [client username password]
  (let [datasource (:datasource (postgres/ready client))
        row        (postgres/exec-one! datasource ::select-user select-user-sql
                                       [(str username)])]
    (if row
      (when (auth/verify-password (str password) (:password-hash row))
        (row->user row))
      (do
        (auth/verify-password (str password) @no-such-user-hash)
        nil))))

;; ---------------------------------------------------------------------------
;; Tokens
;; ---------------------------------------------------------------------------

(defn issue-token
  "Mint a session token for the user `user-id` and store its digest.

  The token is returned once, in `:token`; `:expiresAt` is the expiry the
  database recorded (`clai.daemon.auth/token-ttl-seconds` from now). Expired
  tokens are deleted along the way."
  {:malli/schema [:-> #'postgres/client-schema non-empty-string #'IssuedToken]}
  [client user-id]
  (let [datasource (:datasource (postgres/ready client))
        user-id    (or (row-key user-id)
                       (throw (ex-info (str "Unknown user " (pr-str user-id))
                                       {:userId user-id})))
        token      (auth/new-token)
        _          (postgres/exec! datasource ::delete-expired-tokens
                                   delete-expired-tokens-sql [])
        row        (postgres/exec-one! datasource ::issue-token insert-token-sql
                                       [(auth/token-hash token) user-id
                                        auth/token-ttl-seconds])]
    {:token     token
     :expiresAt (postgres/timestamp->iso (:expires-at row))}))

(defn resolve-token
  "The user behind `token`, or nil when it is unknown, revoked, or expired."
  {:malli/schema [:-> #'postgres/client-schema non-empty-string
                  [:maybe #'User]]}
  [client token]
  (some-> (postgres/exec-one! (:datasource (postgres/ready client))
                              ::resolve-token select-token-sql
                              [(auth/token-hash (str token))])
          row->user))

(defn revoke-token
  "Delete `token`, logging that session out. Returns true when a live session
  was removed."
  {:malli/schema [:-> #'postgres/client-schema non-empty-string boolean?]}
  [client token]
  (pos? (postgres/updated-count
         (postgres/exec-one! (:datasource (postgres/ready client))
                             ::revoke-token delete-token-sql
                             [(auth/token-hash (str token))]))))

;; ---------------------------------------------------------------------------
;; Service
;; ---------------------------------------------------------------------------

(defn make-service
  "Bind the authentication operations to `client`, returning an
  [[AuthService]] for `clai.daemon.http`."
  {:malli/schema [:-> #'postgres/client-schema #'AuthService]}
  [client]
  {:authenticate  (fn [username password]
                    (authenticate client username password))
   :issue-token   (fn [user-id] (issue-token client user-id))
   :resolve-token (fn [token] (resolve-token client token))
   :revoke-token  (fn [token] (revoke-token client token))})
