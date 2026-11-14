(ns clai.auth-test
  "Tests for password hashing, session tokens, and cookie parsing."
  (:require [clai.auth :as auth]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(deftest hashes-are-self-describing-and-verifiable
  (let [encoded (auth/hash-password "correct horse battery staple")
        [prefix rounds salt digest] (str/split encoded #"\$")]
    (is (= "pbkdf2-sha256" prefix))
    (is (= auth/iterations (parse-long rounds))
        "the round count travels with the digest")
    (is (= 24 (count salt)) "a 16-byte salt is 24 base64 characters")
    (is (= 44 (count digest))
        "a 256-bit digest is 44 base64 characters")
    (is (not (str/includes? encoded "correct horse"))
        "the password itself is never part of the digest")
    (is (auth/verify-password "correct horse battery staple" encoded))
    (is (not (auth/verify-password "wrong" encoded)))
    (is (not (auth/verify-password "" encoded)))))

(deftest every-hash-uses-a-fresh-salt
  (let [a (auth/hash-password "same password")
        b (auth/hash-password "same password")]
    (is (not= a b))
    (is (auth/verify-password "same password" a))
    (is (auth/verify-password "same password" b))))

(deftest malformed-hashes-do-not-match
  (doseq [encoded [nil ""
                   "pbkdf2-sha256"
                   "pbkdf2-sha256$0$c2FsdA==$ZGlnZXN0"
                   "pbkdf2-sha256$not-a-number$c2FsdA==$ZGlnZXN0"
                   "pbkdf2-sha256$10$not base64$ZGlnZXN0"
                   "bcrypt$10$c2FsdA==$ZGlnZXN0"
                   "pbkdf2-sha256$10$c2FsdA==$ZGlnZXN0$extra"]]
    (is (false? (auth/verify-password "password" encoded))
        (str "should not verify against " (pr-str encoded)))))

(deftest tokens-are-random-and-url-safe
  (let [tokens (into #{} (repeatedly 50 auth/new-token))]
    (is (= 50 (count tokens)) "tokens do not repeat")
    (is (every? #(re-matches #"[A-Za-z0-9_\-]+" %) tokens)
        "tokens are safe in a cookie without quoting")))

(deftest token-hashes-are-sha-256-hex-digests
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (auth/token-hash "abc")))
  (is (= (auth/token-hash "abc") (auth/token-hash "abc")))
  (is (not= (auth/token-hash "abc") (auth/token-hash "abd")))
  (is (not (str/includes? (auth/token-hash "abc") "abc"))))

(deftest cookie-headers-parse-into-maps
  (is (= {} (auth/parse-cookies nil)))
  (is (= {} (auth/parse-cookies "")))
  (is (= {"a" "1"} (auth/parse-cookies "a=1")))
  (is (= {"a" "1" "clai_session" "tok"}
         (auth/parse-cookies "a=1; clai_session=tok")))
  (is (= {"a" "x=y"} (auth/parse-cookies "a=x=y")))
  (is (= {"a" ""} (auth/parse-cookies "a=")))
  (is (= {} (auth/parse-cookies "junk")))
  (is (= {"a" "1"} (auth/parse-cookies " a = 1 "))))

(deftest request-tokens-come-from-the-cookie-or-a-bearer-header
  (is (= "tok"
         (auth/request-token {:headers {"cookie" "clai_session=tok"}})))
  (is (= "tok"
         (auth/request-token {:headers {"authorization" "Bearer tok"}})))
  (is (= "tok"
         (auth/request-token {:headers {"authorization" "bearer  tok"}})))
  (is (nil? (auth/request-token {:headers {}})))
  (is (nil? (auth/request-token {:headers {"cookie" "other=1"}})))
  (is (nil? (auth/request-token {:headers {"cookie" "clai_session="}})))
  (is (nil? (auth/request-token {:headers {"authorization" "Basic abc"}})))
  (is (= "other" (auth/request-token {:headers {"cookie" "clai_session=tok"
                                                 "authorization" "Bearer other"}}))
      "an explicit authorization header wins over a cookie"))

(deftest session-cookies-are-http-only-and-expire
  (is (= (str "clai_session=tok; Path=/; HttpOnly; SameSite=Lax; Max-Age="
              auth/token-ttl-seconds)
         (auth/session-cookie "tok")))
  (is (= "clai_session=tok; Path=/; HttpOnly; SameSite=Lax"
         (auth/session-cookie "tok" nil)))
  (is (= "clai_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
         (auth/cleared-session-cookie))))
