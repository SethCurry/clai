(ns clai.daemon.auth-test
  "Tests for password hashing and session tokens."
  (:require [clai.daemon.auth :as auth]
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

(deftest issued-tokens-last-a-week
  (is (= (* 7 24 60 60) auth/token-ttl-seconds)))
