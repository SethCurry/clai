(ns clai.daemon.postgres-test
  (:require [clai.daemon.postgres :as pg]
            [clai.test-support :refer [quiet-logs]]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))


;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :once quiet-logs)

(defn- env-map
  "Build an `pg/env` replacement that reads from `m`."
  [m]
  (fn [& names]
    (some m names)))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(deftest make-client-uses-explicit-options
  (let [client (pg/make-client {:host             "db.example"
                                :port             1234
                                :database         "clai_test"
                                :user             "alice"
                                :password         "s3cret"
                                :ssl-mode         "require"
                                :connect-timeout  5
                                :socket-timeout   10
                                :application-name "clai-test"})]
    (is (instance? javax.sql.DataSource (:datasource client)))
    (is (= {:host             "db.example"
            :port             1234
            :database         "clai_test"
            :user             "alice"
            :password         "s3cret"
            :ssl-mode         "require"
            :connect-timeout  5
            :socket-timeout   10
            :application-name "clai-test"}
           (:options client)))))

(deftest make-client-defaults
  (with-redefs [pg/env (constantly nil)]
    (let [client (pg/make-client)]
      (is (instance? javax.sql.DataSource (:datasource client)))
      (is (= "localhost" (get-in client [:options :host])))
      (is (= 5432 (get-in client [:options :port])))
      (is (= "clai" (get-in client [:options :database])))
      (is (= "clai" (get-in client [:options :user])))
      (is (= "secret" (get-in client [:options :password])))
      (is (= "clai" (get-in client [:options :application-name])))
      (is (nil? (get-in client [:options :url]))))))

(deftest make-client-reads-environment
  (with-redefs [pg/env (env-map {"CLAI_POSTGRES_HOST"     "env-host"
                                 "PGPORT"                 "6543"
                                 "PGDATABASE"             "env-db"
                                 "CLAI_POSTGRES_USER"     "env-user"
                                 "PGPASSWORD"             "env-pass"
                                 "CLAI_POSTGRES_SSLMODE"  "verify-full"
                                 "CLAI_POSTGRES_CONNECT_TIMEOUT" "7"})]
    (let [opts (:options (pg/make-client))]
      (is (= "env-host" (:host opts)))
      (is (= 6543 (:port opts)))
      (is (= "env-db" (:database opts)))
      (is (= "env-user" (:user opts)))
      (is (= "env-pass" (:password opts)))
      (is (= "verify-full" (:ssl-mode opts)))
      (is (= 7 (:connect-timeout opts)))
      (is (= "clai" (:application-name opts))))))

(deftest make-client-explicit-options-beat-environment
  (with-redefs [pg/env (env-map {"CLAI_POSTGRES_HOST" "env-host"
                                 "PGPORT"             "6543"
                                 "PGDATABASE"         "env-db"})]
    (let [opts (:options (pg/make-client {:host     "cfg-host"
                                          :port     1111
                                          :database "cfg-db"}))]
      (is (= "cfg-host" (:host opts)))
      (is (= 1111 (:port opts)))
      (is (= "cfg-db" (:database opts))))))

(deftest make-client-url-wins
  (let [client (pg/make-client {:url  "jdbc:postgresql://elsewhere:5432/other"
                                :host "ignored"})]
    (is (= "jdbc:postgresql://elsewhere:5432/other"
           (get-in client [:options :url])))
    ;; A URL carries its own credentials; the local development defaults must
    ;; not override them.
    (is (nil? (get-in client [:options :user])))
    (is (nil? (get-in client [:options :password])))))

(deftest make-client-url-keeps-explicit-credentials
  (let [client (pg/make-client {:url      "jdbc:postgresql://elsewhere:5432/other"
                                :user     "alice"
                                :password "s3cret"})]
    (is (= "alice" (get-in client [:options :user])))
    (is (= "s3cret" (get-in client [:options :password])))))

(deftest make-client-accepts-existing-datasource
  (let [datasource (jdbc/get-datasource {:dbtype "postgresql" :dbname "clai"})
        client     (pg/make-client {:datasource datasource})]
    (is (identical? datasource (:datasource client)))))

(deftest make-client-rejects-invalid-port
  (is (thrown? clojure.lang.ExceptionInfo
               (pg/make-client {:port 0}))))

(deftest make-client-rejects-invalid-ssl-mode
  (is (thrown? clojure.lang.ExceptionInfo
               (pg/make-client {:ssl-mode "sometimes"}))))

(deftest close-is-a-no-op-for-stateless-datasources
  (is (nil? (pg/close! (pg/make-client {:host "localhost"})))))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(deftest schema-sql-is-read-from-the-classpath
  (let [statements (pg/sql-statements (slurp (io/resource pg/schema-resource)))]
    (is (seq statements))
    (is (some #(re-find #"(?is)^CREATE TABLE IF NOT EXISTS agent_session\b" %)
              statements))
    (is (some #(re-find #"(?is)^CREATE TABLE IF NOT EXISTS agent_session_event\b" %)
              statements))
    (is (some #(re-find #"(?is)^CREATE TABLE IF NOT EXISTS plugin\b" %)
              statements))
    (is (some #(re-find #"agent_session_event_type_t" %) statements)
        "the event-type enum is declared")))

(deftest sql-statements-splits-a-script
  (is (= ["CREATE TABLE IF NOT EXISTS a (id INT)"
          "CREATE INDEX IF NOT EXISTS a_idx ON a (id)"]
         (pg/sql-statements
          (str "-- leading comment\n"
               "BEGIN;\n"
               "CREATE TABLE IF NOT EXISTS a (id INT); -- trailing comment\n"
               "\n"
               "CREATE INDEX IF NOT EXISTS a_idx ON a (id);\n"
               "COMMIT;\n")))))

(deftest sql-statements-keeps-quoted-semicolons
  (is (= ["SELECT 'a;b'"
          "DO $$ BEGIN PERFORM 1; END $$"
          "SELECT \"x;y\""]
         (pg/sql-statements
          (str "SELECT 'a;b';\n"
               "-- $$ not a comment start, ; not a separator\n"
               "DO $$ BEGIN PERFORM 1; END $$;\n"
               "SELECT \"x;y\";\n")))))

;; ---------------------------------------------------------------------------
;; Optional integration tests
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.daemon.postgres-test clai.daemon.postgres.sessions-test

(defn- relation-exists?
  "True when the table or type named `relation` exists in the connection's
  current schema."
  [client relation]
  (some? (:oid (jdbc/execute-one! (:datasource client)
                                  ["SELECT COALESCE(to_regclass(CAST(? AS text))::text,
                                                    to_regtype(CAST(? AS text))::text) AS oid"
                                   relation relation]
                                  {:builder-fn rs/as-unqualified-kebab-maps}))))

(deftest ensure-schema-creates-the-declared-tables
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client (pg/open {:url url})]
      (try
        (is (pg/ping client))
        (is (relation-exists? client "agent_session"))
        (is (relation-exists? client "agent_session_event"))
        (is (relation-exists? client "plugin"))
        (is (relation-exists? client "agent_session_event_type_t"))

        ;; The DDL is idempotent, so applying it again is a no-op
        (is (nil? (pg/ensure-schema! client)))
        (is (relation-exists? client "agent_session"))

        (finally
          (pg/close! client))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
