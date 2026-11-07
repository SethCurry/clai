(ns clai.daemon.postgres-test
  (:require [clojure.test :refer :all]
            [clai.daemon.postgres :as pg]
            [clai.test-support :refer [quiet-logs]]
            [next.jdbc :as jdbc]))

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
      (is (= "clai" (get-in client [:options :application-name])))
      (is (nil? (get-in client [:options :user])))
      (is (nil? (get-in client [:options :password])))
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
           (get-in client [:options :url])))))

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
;; Optional integration test
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.daemon.postgres-test

(deftest postgres-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client  (pg/open {:url url})
          session (pg/create-session client {:cwd     "/tmp/clai-test"
                                             :model   "gemma3"
                                             :params  {:a 1}
                                             :history []})
          id      (:sessionId session)
          data-ns (str "clai-test-" id)
          data-key "entry"]
      (try
        (is (pg/ping client))

        ;; Sessions
        (is (= "gemma3" (:model (pg/load-session client id))))
        (is (= {:a 1} (:params (pg/load-session client id))))
        (is (= {:role "user" :content "hello"}
               (pg/append-history client id {:role "user" :content "hello"})))
        (is (= [{:role "user" :content "hello"}]
               (pg/session-history client id)))
        (is (= [1 2]
               (get-in (pg/set-param client id :b [1 2]) [:params :b])))
        (is (= 1
               (get-in (pg/set-params client id {:c 3}) [:params :a])))
        (is (= "llama" (:model (pg/set-model client id "llama"))))
        (is (= id (:sessionId (pg/lookup-session client id))))
        (is (contains? (:params (pg/set-param client id :empty nil)) :empty))
        (pg/save-session client
                        (assoc (pg/load-session client id)
                               :history [{:role "assistant" :content "saved"}]))
        (is (= [{:role "assistant" :content "saved"}]
               (:history (pg/load-session client id))))
        (is (= [] (:history (pg/clear-history client id))))
        (is (pg/session-exists? client id))
        (is (some #(= id (:sessionId %)) (pg/list-sessions client)))
        (is (some #(= id (:sessionId %)) (pg/list-session-summaries client)))

        ;; save-session normalizes and returns the saved state, not the input
        (let [saved (pg/save-session client {:sessionId id
                                            :cwd       "/tmp/clai-test"
                                            :createdAt (:createdAt session)})]
          (is (some? (:updatedAt saved)))
          (is (= {} (:params saved)))
          (is (= [] (:history saved)))
          (is (= saved (pg/load-session client id))))

        ;; A caller-chosen session id
        (let [registered-id (str id "-registered")]
          (is (= registered-id
                 (:sessionId (pg/register-session client registered-id "/tmp/clai-test" []))))
          (is (pg/session-exists? client registered-id))
          (is (true? (pg/delete-session client registered-id))))

        ;; Arbitrary data
        (is (= {:text "hello"}
               (:value (pg/put-data! client data-ns data-key {:text "hello"}))))
        (is (= {:text "hello"} (pg/get-data client data-ns data-key)))
        (is (pg/data-exists? client data-ns data-key))
        (is (= 1 (count (pg/list-data client data-ns))))
        (is (= "hello"
               (get-in (pg/put-data! client data-ns data-key {:text "hello"
                                                              :n 2})
                       [:value :text])))
        (is (= 2 (get-in (pg/lookup-data client data-ns data-key) [:value :n])))
        (is (true? (pg/delete-data! client data-ns data-key)))
        (is (false? (pg/data-exists? client data-ns data-key)))

        ;; JSON null is a stored value, distinct from a missing entry
        (is (nil? (get-in (pg/put-data! client data-ns "null" nil) [:value])))
        (is (nil? (pg/get-data client data-ns "null")))
        (is (some? (pg/lookup-data client data-ns "null")))
        (is (true? (pg/delete-data! client data-ns "null")))
        (is (nil? (pg/lookup-data client data-ns "null")))

        ;; Cleanup
        (is (true? (pg/delete-session client id)))
        (is (false? (pg/session-exists? client id)))
        (finally
          (pg/delete-data! client data-ns data-key)
          (pg/delete-data! client data-ns "null")
          (pg/delete-session client id)
          (pg/delete-session client (str id "-registered"))
          (pg/close! client))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
