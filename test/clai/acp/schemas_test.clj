(ns clai.acp.schemas-test
  "Tests for the ACP schemas, focused on `StopReason`, `TextContent`, and
  the message-chunk session updates."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [malli.core :as m]
            [malli.error :as me]
            [clai.acp.schemas :as schemas]
            [clai.test-support :refer [quiet-logs]]))

(use-fixtures :once quiet-logs)

;; ---------------------------------------------------------------------------
;; StopReason
;; ---------------------------------------------------------------------------

(def valid-stop-reasons
  ["end_turn" "max_tokens" "max_turn_requests" "refusal" "cancelled"])

(deftest stop-reason-accepts-every-defined-value
  (doseq [reason valid-stop-reasons]
    (is (m/validate schemas/StopReason reason)
        (str "expected StopReason to accept " (pr-str reason)))))

(deftest stop-reason-accepts-all-values-together
  (is (every? #(m/validate schemas/StopReason %) valid-stop-reasons)))

(deftest stop-reason-rejects-unknown-strings
  (doseq [reason ["" "END_TURN" "end-turn" "endTurn" "end_turn " " end_turn"
                  "max_turns" "stop" "unknown"]]
    (is (not (m/validate schemas/StopReason reason))
        (str "expected StopReason to reject " (pr-str reason)))))

(deftest stop-reason-rejects-non-string-values
  (doseq [reason [nil :end_turn 'end_turn 0 1 1.0 true false [] {}
                  ["end_turn"]]]
    (is (not (m/validate schemas/StopReason reason))
        (str "expected StopReason to reject " (pr-str reason)))))

(deftest stop-reason-is-an-enum-of-the-expected-values
  (is (= :enum (m/type schemas/StopReason)))
  (is (= (set valid-stop-reasons)
         (set (m/children schemas/StopReason)))))

(deftest stop-reason-explain-reports-allowed-values
  (let [explained (m/explain schemas/StopReason "not-a-stop-reason")
        message   (first (me/humanize explained))]
    (is (some? explained))
    (doseq [reason valid-stop-reasons]
      (is (str/includes? message reason)
          (str "expected explanation to mention " (pr-str reason))))))

;; ---------------------------------------------------------------------------
;; TextContent
;; ---------------------------------------------------------------------------

(defn- text-content
  "A valid TextContent map, with optional overrides."
  ([] (text-content {}))
  ([overrides]
   (merge {:_meta        nil
           :annotations nil
           :text        "hello"
           :type        "text"}
          overrides)))

(deftest text-content-accepts-valid-values
  (is (m/validate schemas/TextContent (text-content)))
  (is (m/validate schemas/TextContent (text-content {:text ""})))
  (is (m/validate schemas/TextContent (text-content {:text "multi\nline"}))))

(deftest text-content-accepts-meta-and-annotations-maps
  (is (m/validate schemas/TextContent
                  (text-content {:_meta        {:anything "goes"}
                                 :annotations {:audience ["user"]}}))))

(deftest text-content-requires-every-key
  (doseq [k [:_meta :annotations :text :type]]
    (is (not (m/validate schemas/TextContent (dissoc (text-content) k)))
        (str "expected TextContent to require " k))))

(deftest text-content-rejects-extra-keys
  (is (not (m/validate schemas/TextContent (assoc (text-content) :extra 1)))))

(deftest text-content-rejects-wrong-type
  (doseq [v ["" "TEXT" "text " :text nil 1]]
    (is (not (m/validate schemas/TextContent (text-content {:type v})))
        (str "expected TextContent to reject :type " (pr-str v)))))

(deftest text-content-rejects-non-string-text
  (doseq [v [nil :text 1 [] {} true]]
    (is (not (m/validate schemas/TextContent (text-content {:text v})))
        (str "expected TextContent to reject :text " (pr-str v)))))

(deftest text-content-rejects-non-map-values
  (doseq [v [nil "text" [] 1]]
    (is (not (m/validate schemas/TextContent v))
        (str "expected TextContent to reject " (pr-str v)))))

;; ---------------------------------------------------------------------------
;; Message chunks
;; ---------------------------------------------------------------------------

(defn- message-chunk
  "A valid message chunk for `session-update`, with optional overrides."
  ([session-update] (message-chunk session-update {}))
  ([session-update overrides]
   (merge {:_meta         nil
           :content       (text-content)
           :sessionUpdate session-update}
          overrides)))

(def chunk-cases
  [[schemas/UserMessageChunk  "user_message_chunk"  "UserMessageChunk"]
   [schemas/AgentMessageChunk "agent_message_chunk" "AgentMessageChunk"]
   [schemas/AgentThoughtChunk "agent_thought_chunk" "AgentThoughtChunk"]])

(deftest message-chunks-accept-valid-values
  (doseq [[schema update _] chunk-cases]
    (is (m/validate schema (message-chunk update))
        (str "expected " update " to validate"))))

(deftest message-chunks-accept-meta-map
  (doseq [[schema update _] chunk-cases]
    (is (m/validate schema (message-chunk update {:_meta {:traceId "t1"}})))))

(deftest message-chunks-require-every-key
  (doseq [[schema update _] chunk-cases
          k               [:_meta :content :sessionUpdate]]
    (is (not (m/validate schema (dissoc (message-chunk update) k)))
        (str "expected " update " to require " k))))

(deftest message-chunks-reject-extra-keys
  (doseq [[schema update _] chunk-cases]
    (is (not (m/validate schema (assoc (message-chunk update) :extra 1)))
        (str "expected " update " to reject extra keys"))))

(deftest message-chunks-reject-wrong-session-update
  (doseq [[schema _ label] chunk-cases
          wrong            ["user_message_chunk"
                            "agent_message_chunk"
                            "agent_thought_chunk"
                            "user_message"
                            "agent_message"
                            ""
                            nil
                            :user_message_chunk]]
    ;; Skip the value that is actually correct for this schema.
    (when (not= wrong (some (fn [[s u _]] (when (= s schema) u)) chunk-cases))
      (is (not (m/validate schema (message-chunk wrong)))
          (str "expected " label " to reject sessionUpdate "
               (pr-str wrong))))))

(deftest message-chunks-reject-invalid-content
  (doseq [[schema update label] chunk-cases
          content              [nil
                                "hello"
                                []
                                {}
                                (dissoc (text-content) :text)
                                (dissoc (text-content) :type)
                                (text-content {:type "TEXT"})
                                (text-content {:text 42})
                                (assoc (text-content) :extra 1)]]
    (is (not (m/validate schema (message-chunk update {:content content})))
        (str "expected " label " to reject content " (pr-str content)))))

(deftest message-chunks-reject-non-map-values
  (doseq [[schema _ label] chunk-cases
          v               [nil "chunk" [] 1]]
    (is (not (m/validate schema v))
        (str "expected " label " to reject " (pr-str v)))))

;; ---------------------------------------------------------------------------
;; validate! integration
;; ---------------------------------------------------------------------------

(deftest validate-accepts-valid-stop-reasons
  (doseq [reason valid-stop-reasons]
    (is (= reason (schemas/validate! schemas/StopReason reason "StopReason")))))

(deftest validate-throws-on-invalid-stop-reason
  (doseq [reason ["" "bogus" nil :end_turn 42]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invalid StopReason"
         (schemas/validate! schemas/StopReason reason "StopReason"))
        (str "expected validate! to reject " (pr-str reason)))))

(deftest validate-exception-carries-schema-value-and-errors
  (let [reason "bogus"
        e      (try
                 (schemas/validate! schemas/StopReason reason "StopReason")
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= schemas/StopReason (:schema (ex-data e))))
    (is (= reason (:value (ex-data e))))
    (is (some? (:errors (ex-data e))))))

(deftest validate-accepts-valid-text-content-and-chunks
  (let [content (text-content)]
    (is (= content (schemas/validate! schemas/TextContent content "TextContent")))
    (doseq [[schema update label] chunk-cases
            value                [(message-chunk update)
                                  (message-chunk update
                                                  {:_meta {:traceId "t1"}
                                                   :content content})]]
      (is (= value (schemas/validate! schema value label))))))

(deftest validate-throws-on-invalid-text-content-and-chunks
  (doseq [[schema value label]
          [[schemas/TextContent (dissoc (text-content) :text) "TextContent"]
           [schemas/TextContent (assoc (text-content) :extra 1) "TextContent"]
           [schemas/UserMessageChunk
            (message-chunk "agent_message_chunk") "UserMessageChunk"]
           [schemas/AgentMessageChunk
            (message-chunk "user_message_chunk") "AgentMessageChunk"]
           [schemas/AgentThoughtChunk
            (message-chunk "agent_thought_chunk" {:content "nope"})
            "AgentThoughtChunk"]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo (re-pattern (str "Invalid " label))
         (schemas/validate! schema value label))
        (str "expected validate! to reject invalid " label))))
