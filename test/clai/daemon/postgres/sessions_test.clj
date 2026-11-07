(ns clai.daemon.postgres.sessions-test
  (:require [clojure.test :refer :all]
            [clai.daemon.postgres :as pg]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.test-support :refer [quiet-logs]]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :once quiet-logs)

;; ---------------------------------------------------------------------------
;; Optional integration test
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.daemon.postgres.sessions-test

(deftest session-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client  (pg/open {:url url})
          session (sessions/create-session client {:cwd     "/tmp/clai-test"
                                                   :model   "gemma3"
                                                   :params  {:a 1}
                                                   :history []})
          id      (:sessionId session)]
      (try
        (is (= "gemma3" (:model (sessions/load-session client id))))
        (is (= {:a 1} (:params (sessions/load-session client id))))
        (is (= {:role "user" :content "hello"}
               (sessions/append-history client id {:role "user" :content "hello"})))
        (is (= [{:role "user" :content "hello"}]
               (sessions/session-history client id)))
        (is (= [1 2]
               (get-in (sessions/set-param client id :b [1 2]) [:params :b])))
        (is (= 1
               (get-in (sessions/set-params client id {:c 3}) [:params :a])))
        (is (= "llama" (:model (sessions/set-model client id "llama"))))
        (is (= id (:sessionId (sessions/lookup-session client id))))
        (is (contains? (:params (sessions/set-param client id :empty nil))
                       :empty))
        (sessions/save-session client
                               (assoc (sessions/load-session client id)
                                      :history [{:role "assistant"
                                                 :content "saved"}]))
        (is (= [{:role "assistant" :content "saved"}]
               (:history (sessions/load-session client id))))
        (is (= [] (:history (sessions/clear-history client id))))
        (is (sessions/session-exists? client id))
        (is (some #(= id (:sessionId %)) (sessions/list-sessions client)))
        (is (some #(= id (:sessionId %))
                  (sessions/list-session-summaries client)))

        ;; save-session normalizes and returns the saved state, not the input
        (let [saved (sessions/save-session client {:sessionId id
                                                   :cwd       "/tmp/clai-test"
                                                   :createdAt (:createdAt session)})]
          (is (some? (:updatedAt saved)))
          (is (= {} (:params saved)))
          (is (= [] (:history saved)))
          (is (= saved (sessions/load-session client id))))

        ;; A caller-chosen session id
        (let [registered-id (str id "-registered")]
          (is (= registered-id
                 (:sessionId (sessions/register-session client registered-id
                                                        "/tmp/clai-test" []))))
          (is (sessions/session-exists? client registered-id))
          (is (true? (sessions/delete-session client registered-id))))

        ;; Mutations targeting an unknown session are no-ops
        (is (nil? (sessions/append-history client "nope" {:role "user"})))
        (is (nil? (sessions/set-model client "nope" "llama")))
        (is (= [] (sessions/session-history client "nope")))

        ;; Cleanup
        (is (true? (sessions/delete-session client id)))
        (is (false? (sessions/session-exists? client id)))
        (finally
          (sessions/delete-session client id)
          (sessions/delete-session client (str id "-registered"))
          (pg/close! client))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
