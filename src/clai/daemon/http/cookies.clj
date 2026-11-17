(ns clai.daemon.http.cookies
  "The session cookie: reading a request's credentials and writing its
  `Set-Cookie` header.

  `clai.daemon.auth` mints and hashes the session tokens; this namespace is
  the HTTP layer around them. It parses the `cookie` header, finds the session
  token a request carries -- either the cookie a browser sends back, or an
  `Authorization: Bearer <token>` header that any other client (curl, scripts,
  tests) can send -- and formats the `Set-Cookie` values that store and clear
  the cookie.

  Nothing here talks to the database: `clai.daemon.http` hands the token from
  [[request-token]] to the account store, and puts [[session-cookie]] on the
  login response.

  ## Example

      (require '[clai.daemon.http.cookies :as cookies])

      (cookies/request-token {:headers {\"cookie\" \"clai_session=abc\"}})
      ; => \"abc\"

      (cookies/session-cookie \"abc\")
      ; => \"clai_session=abc; Path=/; HttpOnly; SameSite=Lax; Max-Age=604800\""
  (:require [clai.daemon.auth :as auth]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def CookieMap
  "A request's cookies, decoded into a `{\"name\" \"value\"}` map."
  [:map-of :string :string])

;; ---------------------------------------------------------------------------
;; Reading a request's credentials
;; ---------------------------------------------------------------------------

(def cookie-name
  "The name of the cookie the daemon stores a session token in."
  "clai_session")

(defn parse-cookies
  "Decode a request's `cookie` header into a `{\"name\" \"value\"}` map.

  Pairs without an `=` are dropped rather than treated as an error, so a
  malformed cookie from something else on the origin cannot break a request."
  {:malli/schema [:-> [:maybe :string] #'CookieMap]}
  [header]
  (if (str/blank? header)
    {}
    (into {}
          (keep (fn [pair]
                  (let [[name value] (str/split (str/trim pair) #"=" 2)]
                    (when (and (some? value) (not (str/blank? name)))
                      [(str/trim name) (str/trim value)]))))
          (str/split (str header) #";"))))

(defn request-token
  "The session token `request` carries, or nil when it carries none.

  The browser sends it back in the session cookie; other clients can use an
  `Authorization: Bearer <token>` header instead, which wins over a cookie
  when both are present."
  {:malli/schema [:-> :map [:maybe #'auth/Token]]}
  [request]
  (let [header (get-in request [:headers "authorization"])
        bearer (when (string? header)
                 (second (re-find #"(?i)^bearer\s+(\S+)$" (str/trim header))))]
    (or (not-empty (str bearer))
        (let [token (get (parse-cookies (get-in request [:headers "cookie"]))
                         cookie-name)]
          (when-not (str/blank? (str token))
            token)))))

;; ---------------------------------------------------------------------------
;; Writing the session cookie
;; ---------------------------------------------------------------------------

(defn session-cookie
  "The `Set-Cookie` value that stores `token` as the session cookie, lasting
  `max-age` seconds ([[auth/token-ttl-seconds]] by default).

  `HttpOnly` keeps the token away from page scripts and `SameSite=Lax` keeps
  it off cross-site form posts. `Secure` is deliberately not set: the daemon
  serves plain HTTP on loopback, and browsers would then drop the cookie. Add
  it (here and in [[cleared-session-cookie]]) if you serve clai over TLS."
  {:malli/schema [:function
                  [:=> [:cat #'auth/Token] :string]
                  [:=> [:cat #'auth/Token [:maybe pos-int?]] :string]]}
  ([token] (session-cookie token auth/token-ttl-seconds))
  ([token max-age]
   (str/join "; "
             (cond-> [(str cookie-name "=" token)
                      "Path=/"
                      "HttpOnly"
                      "SameSite=Lax"]
               max-age (conj (str "Max-Age=" max-age))))))

(defn cleared-session-cookie
  "The `Set-Cookie` value that makes the browser drop the session cookie."
  {:malli/schema [:-> :string]}
  []
  (str cookie-name "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"))
