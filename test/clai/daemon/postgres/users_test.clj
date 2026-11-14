(ns clai.daemon.postgres.users-test
  "Tests for user accounts and session tokens.

  The unit tests exercise the pure helpers; the round trip only runs when
  `CLAI_TEST_POSTGRES_URL` points at a disposable database."
  (:require [clai.daemon.postgres :as pg]
            [clai.daemon.postgres.users :as users]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.test :refer :all])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(defn- unique-username
  "A username that will not collide with another test run."
  [prefix]
  (str prefix "-" (UUID/randomUUID)))

;; ---------------------------------------------------------------------------
;; Unit tests
;; ---------------------------------------------------------------------------

(deftest row-keys-must-name-a-row
  (is (= 7 (users/row-key 7)))
  (is (= 7 (users/row-key "7")))
  (is (nil? (users/row-key "0")))
  (is (nil? (users/row-key "-1")))
  (is (nil? (users/row-key "not-a-row")))
  (is (nil? (users/row-key nil))))

(deftest rows-convert-to-the-public-user-shape
  (is (= {:userId "3" :username "admin"}
         (users/row->user {:id 3
                           :username "admin"
                           :password-hash "pbkdf2-sha256$..."}))
      "the password digest never leaves the storage layer"))

(deftest credentials-are-normalized-and-validated
  (is (= {:username "admin" :password " secret "}
         (users/prepare-credentials {:username "  admin  "
                                     :password " secret "
                                     :userId  "3"}))
      "the username is trimmed, the password is kept verbatim, extra keys drop")
  (is (thrown? clojure.lang.ExceptionInfo
               (users/prepare-credentials {:username "   " :password "x"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (users/prepare-credentials {:username "admin"}))))

;; ---------------------------------------------------------------------------
;; Round trip
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/clai?user=clai&password=secret' \
;;     lein test clai.daemon.postgres.users-test
;;
;; This file is the one place the `admin`/`admin` seed is asserted, so the local
;; development database (see `docker-compose.yml`) is a valid target.

(deftest the-schema-seeds-an-admin-account
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client (pg/open {:url url})]
      (is (= {:userId "1" :username "admin"}
             (users/authenticate client "admin" "admin"))
          "the DDL seeds admin/admin for local use")
      (is (= {:userId "1" :username "admin"}
             (users/authenticate client "  ADMIN  " "admin"))
          "usernames are compared case-insensitively")
      (is (nil? (users/authenticate client "admin" "wrong")))
      (is (nil? (users/authenticate client "nobody" "admin")))
      (is (some #(= "admin" (:username %)) (users/list-users client))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))

(deftest accounts-and-sessions-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client   (pg/open {:url url})
          username (unique-username "clai-test")
          user     (users/create-user! client {:username (str "  " username "  ")
                                               :password "s3cret"})
          user-id  (:userId user)]
      (try
        (is (= username (:username user)))
        (is (string? user-id))
        (is (= user (users/authenticate client username "s3cret")))
        (is (nil? (users/authenticate client username "other")))
        (is (some #(= username (:username %)) (users/list-users client)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (users/create-user! client {:username username
                                                 :password "s3cret"}))
            "a username can only be taken once")

        (let [{:keys [token expiresAt]} (users/issue-token client user-id)]
          (is (string? token))
          (is (string? expiresAt))
          (is (= user (users/resolve-token client token)))
          (is (nil? (users/resolve-token client "not-a-token")))
          (is (true? (users/revoke-token client token)))
          (is (nil? (users/resolve-token client token))
              "a revoked token stops working")
          (is (false? (users/revoke-token client token))))

        (finally
          (pg/exec-one! (:datasource client) ::delete-test-user
                        "DELETE FROM app_user WHERE id = ?"
                        [(users/row-key user-id)]))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))

(deftest a-service-binds-every-operation-to-one-client
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client  (pg/open {:url url})
          service (users/make-service client)
          user    ((:authenticate service) "admin" "admin")]
      (is (= {:userId "1" :username "admin"} user))
      (let [{:keys [token]} ((:issue-token service) (:userId user))]
        (is (= user ((:resolve-token service) token)))
        (is (true? ((:revoke-token service) token)))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
