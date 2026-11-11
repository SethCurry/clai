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
;; Event typing
;; ---------------------------------------------------------------------------

(deftest message-event-types
  (is (= "user_prompt" (sessions/message->event-type {:role "user"})))
  (is (= "user_prompt" (sessions/message->event-type {:role "system"})))
  (is (= "agent_message" (sessions/message->event-type {:role "assistant"})))
  (is (= "agent_thought" (sessions/message->event-type {:role "assistant"
                                                        :thinking "hm"})))
  (is (= "tool_call" (sessions/message->event-type
                      {:role "assistant" :tool_calls [{:id "call_1"}]})))
  (is (= "tool_call" (sessions/message->event-type {:role "tool"}))))

(deftest session-ids-must-name-a-row
  (is (= 12 (sessions/session-key 12)))
  (is (= 12 (sessions/session-key "12")))
  (is (nil? (sessions/session-key "3f2b1c8e-1a2b-4c3d-9e4f-5a6b7c8d9e0f")))
  (is (nil? (sessions/session-key "nope")))
  (is (nil? (sessions/session-key nil))))

;; ---------------------------------------------------------------------------
;; Optional integration test
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.daemon.postgres-test clai.daemon.postgres.sessions-test

(deftest session-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client  (pg/open {:url url})
          session (sessions/create-session client {:cwd "/tmp/clai-test"})
          id      (:sessionId session)]
      (try
        (is (string? id))
        (is (= "/tmp/clai-test" (:cwd (sessions/load-session client id))))
        (is (some? (:createdAt (sessions/load-session client id))))

        (is (= {:role "user" :content "hello"}
               (sessions/append-history client id {:role "user" :content "hello"})))
        (is (= [{:role "user" :content "hello"}]
               (sessions/session-history client id)))

        ;; Assistant messages, tool calls, and thinking keep their event
        ;; types while round-tripping verbatim through the event log.
        (let [assistant {:role "assistant" :content "hi" :thinking "thought"}
              call      {:role "assistant" :content "" :tool_calls [{:id "c1"}]}
              result    {:role "tool" :content "out" :name "read_file"
                         :tool_call_id "c1"}]
          (doseq [msg [assistant call result]]
            (is (= msg (sessions/append-history client id msg))))
          (is (= [{:role "user" :content "hello"} assistant call result]
                 (sessions/session-history client id))))

        (is (= id (:sessionId (sessions/lookup-session client id))))
        (is (sessions/session-exists? client id))
        (is (some #(= id (:sessionId %)) (sessions/list-sessions client)))
        (is (some #(= id (:sessionId %))
                  (sessions/list-session-summaries client)))

        ;; save-session replaces the event log and can move the cwd
        (let [saved (sessions/save-session client
                                           {:sessionId id
                                            :cwd       "/tmp/clai-other"
                                            :history   [{:role "assistant"
                                                         :content "saved"}]})]
          (is (= "/tmp/clai-other" (:cwd saved)))
          (is (= [{:role "assistant" :content "saved"}]
                 (:history (sessions/load-session client id)))))

        (is (= [] (:history (sessions/clear-history client id))))
        (is (= [] (sessions/session-history client id)))

        ;; Unknown sessions are no-ops rather than errors, including ids that
        ;; are not database keys at all.
        (is (nil? (sessions/load-session client "not-a-row")))
        (is (nil? (sessions/append-history client "999999999" {:role "user"})))
        (is (nil? (sessions/append-history client "not-a-row" {:role "user"})))
        (is (= [] (sessions/session-history client "not-a-row")))
        (is (nil? (sessions/save-session client {:sessionId "not-a-row"
                                                 :cwd       "/tmp"
                                                 :history   []})))
        (is (false? (sessions/session-exists? client "not-a-row")))

        (is (true? (sessions/delete-session client id)))
        (is (false? (sessions/session-exists? client id)))
        (is (nil? (sessions/load-session client id)))

        (finally
          (sessions/delete-session client id))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
