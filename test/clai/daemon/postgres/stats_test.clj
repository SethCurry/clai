(ns clai.daemon.postgres.stats-test
  "Tests for the usage statistics that `clai.daemon.postgres.stats` derives
  from the session event log."
  (:require [clai.daemon.postgres :as pg]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.daemon.postgres.stats :as stats]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(defn- call-row
  "A row as the tool-call query returns it."
  [session-id call-id tool-name input-chars]
  {:session-id  (long session-id)
   :call-id     call-id
   :tool-name   tool-name
   :input-chars (long input-chars)})

(defn- result-row
  "A row as the tool-result query returns it."
  [session-id call-id tool-name output-chars]
  {:session-id  (long session-id)
   :call-id     call-id
   :tool-name   tool-name
   :output-chars (long output-chars)})

(defn- event-row
  "A row as the conversation-event query returns it."
  [session-id event-type role chars]
  {:session-id (long session-id)
   :event-type event-type
   :role       role
   :chars      (long chars)})

(deftest tool-calls-pair-with-their-results
  (let [calls    [(call-row 1 "a" "read_file" 10)
                  (call-row 1 "b" "read_file" 20)
                  (call-row 2 "c" "run_command" 30)]
        results  [(result-row 1 "a" "read_file" 100)
                  (result-row 2 "c" "run_command" 200)
                  ;; A result whose request was not logged still counts.
                  (result-row 2 "d" "eval_clojure" 300)]
        records  (stats/tool-call-records calls results)]
    (is (= [{:sessionId "1" :toolName "read_file" :inputChars 10
             :outputChars 100 :completed? true}
            {:sessionId "1" :toolName "read_file" :inputChars 20
             :outputChars 0 :completed? false}
            {:sessionId "2" :toolName "run_command" :inputChars 30
             :outputChars 200 :completed? true}
            {:sessionId "2" :toolName "eval_clojure" :inputChars 0
             :outputChars 300 :completed? true}]
           records))))

(deftest tool-stats-average-over-the-calls-that-returned-something
  (let [records  [(call-row 1 "a" "read_file" 10)
                  (call-row 1 "b" "read_file" 20)
                  (call-row 2 "c" "read_file" 30)
                  (call-row 2 "d" "run_command" 40)]
        results  [(result-row 1 "a" "read_file" 100)
                  (result-row 2 "c" "read_file" 300)
                  (result-row 2 "d" "run_command" 50)]
        tool-stats (stats/tool-stats
                    (stats/tool-call-records records results))]
    (testing "the most-called tool comes first"
      (is (= ["read_file" "run_command"] (mapv :toolName tool-stats))))

    (testing "counts are calls, completed calls, and distinct sessions"
      (is (= {:toolName         "read_file"
              :callCount        3
              ;; "b" never returned a result, so it is not averaged in.
              :completedCount   2
              :sessionCount     2
              :totalInputChars  60
              :avgInputChars    20
              :totalOutputChars 400
              :avgOutputChars   200
              :maxOutputChars   300}
             (first tool-stats))))))

(deftest tool-stats-of-no-calls-is-empty
  (is (= [] (stats/tool-stats []))))

(deftest session-stats-count-what-each-session-did
  (let [sessions [{:id 1 :title "Fix the build" :working-directory "/tmp/one"}
                  {:id 2 :title nil :working-directory "/tmp/two"}]
        events   [(event-row 1 "user_prompt" "system" 1000)
                  (event-row 1 "user_prompt" "user" 100)
                  (event-row 1 "user_prompt" "user" 200)
                  (event-row 1 "agent_message" "assistant" 400)
                  (event-row 1 "agent_thought" "assistant" 50)]
        calls    [(call-row 1 "a" "read_file" 10)
                  (call-row 1 "b" "run_command" 20)]
        results  [(result-row 1 "a" "read_file" 300)
                  (result-row 1 "b" "run_command" 100)]
        by-id    (into {} (map (juxt :sessionId identity))
                       (stats/session-stats
                        (mapv #(assoc % :created-at nil) sessions)
                        events
                        (stats/tool-call-records calls results)))]
    (testing "the opening system prompt is not a prompt the user typed"
      (is (= 2 (get-in by-id ["1" :promptCount])))
      (is (= 300 (get-in by-id ["1" :promptChars])))
      (is (= 150 (get-in by-id ["1" :avgPromptChars]))))

    (testing "replies and thoughts are counted separately"
      (is (= 1 (get-in by-id ["1" :agentMessageCount])))
      (is (= 400 (get-in by-id ["1" :replyChars])))
      (is (= 1 (get-in by-id ["1" :thoughtCount]))))

    (testing "tool calls are grouped by session and tool"
      (is (= 2 (get-in by-id ["1" :toolCallCount])))
      (is (= 400 (get-in by-id ["1" :toolOutputChars])))
      (is (= 200 (get-in by-id ["1" :avgToolOutputChars])))
      (is (= 2 (get-in by-id ["1" :distinctToolCount]))))

    (testing "a session with no activity still has a row of zeros"
      (is (= {:promptCount       0
              :promptChars       0
              :agentMessageCount 0
              :replyChars        0
              :thoughtCount      0
              :toolCallCount     0
              :toolOutputChars   0
              :distinctToolCount 0}
             (select-keys (get by-id "2")
                          [:promptCount :promptChars :agentMessageCount
                           :replyChars :thoughtCount :toolCallCount
                           :toolOutputChars :distinctToolCount])))
      (is (nil? (get-in by-id ["2" :avgPromptChars])))
      (is (nil? (get-in by-id ["2" :avgToolOutputChars]))))

    (testing "the session summary fields are carried through"
      (is (= "/tmp/one" (get-in by-id ["1" :cwd])))
      (is (= "Fix the build" (get-in by-id ["1" :title])))
      (is (nil? (get-in by-id ["1" :createdAt]))))))

(deftest overview-averages-per-session-and-per-call
  (let [session-stats [{:sessionId "1" :title "one" :cwd "/tmp/one"
                        :createdAt nil
                        :promptCount 2 :promptChars 300 :avgPromptChars 150
                        :agentMessageCount 1 :replyChars 400 :avgReplyChars 400
                        :thoughtCount 1 :toolCallCount 2 :toolOutputChars 400
                        :avgToolOutputChars 200 :distinctToolCount 1}
                       {:sessionId "2" :title nil :cwd "/tmp/two"
                        :createdAt nil
                        :promptCount 1 :promptChars 100 :avgPromptChars 100
                        :agentMessageCount 1 :replyChars 100 :avgReplyChars 100
                        :thoughtCount 0 :toolCallCount 1 :toolOutputChars 0
                        :avgToolOutputChars nil :distinctToolCount 1}]
        tool-stats    (stats/tool-stats
                       (stats/tool-call-records
                        [(call-row 1 "a" "read_file" 0)
                         (call-row 1 "b" "read_file" 0)
                         (call-row 2 "c" "run_command" 0)]
                        [(result-row 1 "a" "read_file" 100)
                         (result-row 1 "b" "read_file" 300)]))
        overview      (stats/overview session-stats tool-stats
                                      (stats/tool-call-records
                                       [(call-row 1 "a" "read_file" 0)
                                        (call-row 1 "b" "read_file" 0)
                                        (call-row 2 "c" "run_command" 0)]
                                       [(result-row 1 "a" "read_file" 100)
                                        (result-row 1 "b" "read_file" 300)]))]
    (is (= 2 (:sessionCount overview)))
    (is (= 2 (:toolCount overview)))
    (is (= 3 (:promptCount overview)))
    (is (= 3 (:toolCallCount overview)))
    (is (= 2 (:completedToolCallCount overview)))
    (is (= 1.5 (:avgPromptsPerSession overview)))
    (is (= 1.5 (:avgToolCallsPerSession overview)))
    (is (= 400 (:totalPromptChars overview)))
    (is (= 500 (:totalReplyChars overview)))
    (is (= 400 (:totalToolOutputChars overview)))
    (is (= 200 (:avgToolOutputChars overview)))
    (is (= 300 (:maxToolOutputChars overview)))
    (is (= 200 (:avgToolOutputCharsPerSession overview)))))

(deftest overview-with-no-sessions-has-no-averages
  (let [overview (stats/overview [] [] [])]
    (is (= 0 (:sessionCount overview)))
    (is (= 0 (:toolCallCount overview)))
    (is (nil? (:avgToolCallsPerSession overview)))
    (is (nil? (:avgToolOutputChars overview)))))

;; ---------------------------------------------------------------------------
;; Optional integration test
;; ---------------------------------------------------------------------------
;;
;; It only runs when CLAI_TEST_POSTGRES_URL points at a disposable database:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.daemon.postgres.stats-test

(deftest usage-stats-read-the-event-log
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client  (pg/open {:url url})
          session (sessions/create-session client {:cwd "/tmp/clai-stats-test"})
          id      (:sessionId session)]
      (try
        (sessions/append-history
         client id
         {:role    "assistant"
          :content ""
          :tool_calls [{:id "call_1"
                        :function {:name "read_file"
                                   :arguments {:path "README.md"}}}]})
        (sessions/append-history
         client id
         {:role         "tool"
          :name         "read_file"
          :tool_call_id "call_1"
          :content      "0123456789"})
        (let [{:keys [overview tools sessions]} (stats/usage-stats client)
              session-stat (some #(when (= id (:sessionId %)) %) sessions)
              read-file    (some #(when (= "read_file" (:toolName %)) %) tools)]
          (is (= 1 (:toolCallCount session-stat)))
          (is (= 10 (:toolOutputChars session-stat)))
          (is (= 10 (:avgOutputChars read-file)))
          (is (pos? (:toolCallCount overview))))
        (finally
          (sessions/delete-session client id))))
    (is true "Skipped; set CLAI_TEST_POSTGRES_URL to run against a database.")))
