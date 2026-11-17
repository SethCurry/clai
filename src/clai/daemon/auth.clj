(ns clai.daemon.auth
  "Password hashing and session tokens for `clai`'s daemon.

  This namespace is pure cryptography: it never talks to the database and
  never touches HTTP. `clai.daemon.postgres.users` stores the values produced
  here, and `clai.daemon.http.cookies` puts a token into a cookie and reads it
  back off a request.

  ## Passwords

  Passwords are never stored. [[hash-password]] derives a salted
  PBKDF2-HMAC-SHA256 digest using only the JDK's `javax.crypto`, so no extra
  dependency is needed, and encodes it as one self-describing string:

      pbkdf2-sha256$210000$<base64 salt>$<base64 digest>

  The iteration count travels with the digest, so it can be raised later
  without invalidating stored hashes. [[verify-password]] compares in constant
  time.

  ## Session tokens

  A logged-in client holds a random [[new-token]]. The database stores only
  [[token-hash]] of it (a SHA-256 digest), so a database dump does not hand out
  working sessions. [[token-ttl-seconds]] is how long an issued token is
  accepted.

  ## Example

      (require '[clai.daemon.auth :as auth])

      (def encoded (auth/hash-password \"correct horse\"))

      (auth/verify-password \"correct horse\" encoded) ; => true
      (auth/verify-password \"wrong\" encoded)         ; => false

      (auth/token-hash (auth/new-token))              ; => \"9f2c...\""
  (:require [clai.util :as util]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.crypto SecretKeyFactory]
           [javax.crypto.spec PBEKeySpec]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def PasswordHash
  "A password digest as written by [[hash-password]] and read by
  [[verify-password]]."
  util/non-empty-string)

(def Token
  "A session token as minted by [[new-token]]."
  util/non-empty-string)

;; ---------------------------------------------------------------------------
;; Password hashing
;; ---------------------------------------------------------------------------

(def ^:private algorithm
  "The JDK key-derivation algorithm used for password digests."
  "PBKDF2WithHmacSHA256")

(def ^:private hash-prefix
  "The leading field of an encoded digest, naming the algorithm it used."
  "pbkdf2-sha256")

(def iterations
  "The PBKDF2 round count new digests are derived with. It is stored in each
  encoded digest, so a stored hash keeps working after this changes."
  210000)

(def ^:private salt-bytes
  "The number of random bytes in a password salt."
  16)

(def ^:private digest-bits
  "The length of a derived password digest, in bits."
  256)

(def ^:private random
  "The process-wide source of salts and session tokens."
  (SecureRandom.))

(def ^:private base64-encoder
  "Base64 encoder used for salts and digests (standard alphabet, padded)."
  (Base64/getEncoder))

(def ^:private base64-decoder
  "Base64 decoder used for salts and digests (standard alphabet, padded)."
  (Base64/getDecoder))

(def ^:private token-encoder
  "Base64url encoder for session tokens, unpadded so the value needs no
  quoting inside a cookie."
  (.withoutPadding (Base64/getUrlEncoder)))

(defn- encode-bytes
  "Base64-encode the byte array `value`."
  {:malli/schema [:-> :any :string]}
  [value]
  (.encodeToString base64-encoder ^bytes value))

(defn- decode-bytes
  "Base64-decode `value`, returning a byte array."
  {:malli/schema [:-> :string :any]}
  [value]
  (.decode base64-decoder ^String value))

(defn- random-bytes
  "A fresh array of `n` cryptographically random bytes."
  {:malli/schema [:-> pos-int? :any]}
  [n]
  (let [value (byte-array n)]
    (.nextBytes random value)
    value))

(defn- derive-key
  "Derive a raw PBKDF2 key of [[digest-bits]] bits from `password`, `salt`,
  and `rounds`, as a byte array."
  {:malli/schema [:-> :string :any pos-int? :any]}
  [password salt rounds]
  (let [spec (PBEKeySpec. (.toCharArray ^String password) ^bytes salt rounds
                          digest-bits)]
    (try
      (.getEncoded (.generateSecret (SecretKeyFactory/getInstance algorithm)
                                    spec))
      (finally
        (.clearPassword spec)))))

(defn hash-password
  "Derive a salted digest of `password`, in the encoded form described in the
  namespace docstring."
  {:malli/schema [:-> util/non-empty-string #'PasswordHash]}
  [password]
  (let [salt   (random-bytes salt-bytes)
        digest (derive-key password salt iterations)]
    (str/join "$" [hash-prefix iterations (encode-bytes salt)
                   (encode-bytes digest)])))

(defn- parse-password-hash
  "Split an encoded digest into `[rounds salt digest]`, or nil when `encoded`
  is not one this namespace wrote."
  {:malli/schema [:-> :any [:maybe [:tuple pos-int? :any :any]]]}
  [encoded]
  (when (string? encoded)
    (let [[prefix rounds salt digest :as parts]
          (str/split encoded #"\$")]
      (when (and (= 4 (count parts)) (= prefix hash-prefix))
        (when-let [rounds (parse-long rounds)]
          (when (pos? rounds)
            (try
              [rounds (decode-bytes salt) (decode-bytes digest)]
              (catch IllegalArgumentException _ nil))))))))

(defn verify-password
  "True when `password` derives the digest encoded in `encoded`.

  A malformed or unknown `encoded` value is simply not a match, so a corrupted
  row locks that user out instead of failing the whole request. The comparison
  is constant time for equal-length digests."
  {:malli/schema [:-> :any :any boolean?]}
  [password encoded]
  (if-let [[rounds salt digest] (parse-password-hash encoded)]
    (MessageDigest/isEqual ^bytes digest
                           ^bytes (derive-key (str password) salt rounds))
    false))

;; ---------------------------------------------------------------------------
;; Session tokens
;; ---------------------------------------------------------------------------

(def token-ttl-seconds
  "How long an issued session token stays valid, in seconds (seven days)."
  (* 7 24 60 60))

(defn new-token
  "A fresh random session token: 32 bytes, base64url-encoded without padding."
  {:malli/schema [:-> #'Token]}
  []
  (.encodeToString token-encoder (random-bytes 32)))

(defn token-hash
  "The SHA-256 digest of `token`, hex-encoded. This is what the database
  stores, so a leaked table cannot be replayed as sessions."
  {:malli/schema [:-> #'Token util/non-empty-string]}
  [token]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String token StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" %) digest))))
