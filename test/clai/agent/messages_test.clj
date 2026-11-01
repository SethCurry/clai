(ns clai.agent.messages-test
  "Tests for ACP -> Ollama prompt normalization in `clai.agent.messages`."
  (:require [clai.agent.messages :as messages]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(def ^:private prompt->messages
  "The ACP prompt normalizer."
  #'messages/acp-prompt->ollama-messages)

;; ---------------------------------------------------------------------------
;; Prompt normalization
;; ---------------------------------------------------------------------------

(deftest content-block-prompt-becomes-user-message
  ;; ACP sends `:prompt` as a vector of ContentBlocks; their text must survive
  ;; the conversion (regression: it used to be dropped, sending an empty
  ;; user message and prompting the model to report "no request").
  (is (= [{:role "user" :content "hello"}]
         (prompt->messages [{:type "text" :text "hello"}])))
  (is (= [{:role "user" :content "hello\nworld"}]
         (prompt->messages [{:type "text" :text "hello"}
                            {:type "text" :text "world"}])))
  (is (= [{:role "user" :content "look"}]
         (prompt->messages [{:type "text" :text "look"}
                            {:type "image" :data "..."}]))))

(deftest tolerant-prompt-shapes
  (is (= [] (prompt->messages nil)))
  (is (= [{:role "user" :content "hi"}] (prompt->messages "hi")))
  (is (= [{:role "user" :content "hi"}]
         (prompt->messages {:type "text" :text "hi"})))
  ;; role-tagged messages keep their role
  (is (= [{:role "user" :content "hi"}]
         (prompt->messages [{:role "user"
                             :content [{:type "text" :text "hi"}]}])))
  (is (= [{:role "assistant" :content "yo"}]
         (prompt->messages [{:role "assistant" :content "yo"}]))))
