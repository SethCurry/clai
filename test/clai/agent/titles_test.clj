(ns clai.agent.titles-test
  "Tests for `clai.agent.titles`: asking a provider for a session title and
  normalizing whatever it answers with."
  (:require [clai.agent.providers.ollama :as ollama]
            [clai.agent.titles :as titles]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(def ^:private test-client
  "An Ollama client whose transport the tests stub."
  (ollama/make-client {:host "http://localhost:11434" :timeout 1000}))

(def ^:private stub-agent
  "The slice of an agent map that [[titles/generate-title]] reads."
  {:client test-client :model "stub-model"})

;; ---------------------------------------------------------------------------
;; Cleaning a model's reply
;; ---------------------------------------------------------------------------

(deftest clean-title-keeps-a-plain-title
  (is (= "Fix the flaky build" (titles/clean-title "Fix the flaky build"))))

(deftest clean-title-drops-quoting-and-punctuation
  (are [reply title] (= title (titles/clean-title reply))
    "\"Fix the flaky build.\""       "Fix the flaky build"
    "'Fix the flaky build'"          "Fix the flaky build"
    "**Fix the flaky build**"        "Fix the flaky build"
    "`Fix the flaky build`"          "Fix the flaky build"
    "Title: Fix the flaky build"     "Title: Fix the flaky build"
    "- Fix the flaky build"          "Fix the flaky build"
    "1. Fix the flaky build"         "Fix the flaky build"
    "  Fix   the flaky build  "      "Fix the flaky build"
    "Fix the flaky build:"           "Fix the flaky build"))

(deftest clean-title-keeps-the-first-line-only
  (is (= "Fix the flaky build"
         (titles/clean-title
          "\n  \nFix the flaky build\n\nThis title names the session."))))

(deftest clean-title-truncates-to-five-words
  (is (= "Fix the flaky CI build"
         (titles/clean-title
          "Fix the flaky CI build now before the release"))))

(deftest clean-title-rejects-an-unusable-reply
  (are [reply] (nil? (titles/clean-title reply))
    nil
    ""
    "   \n\t "
    "\"\""))

(deftest clean-title-reads-content-blocks
  (is (= "Fix the flaky build"
         (titles/clean-title [{:type "text" :content "Fix the flaky build"}]))))

;; ---------------------------------------------------------------------------
;; Asking the provider
;; ---------------------------------------------------------------------------

(deftest generate-title-asks-the-configured-model-directly
  (let [requests (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:message {:role "assistant" :content "\"Fix the build.\""}})]
      (is (= "Fix the build"
             (titles/generate-title stub-agent "the build keeps flaking")))
      (let [request (first @requests)]
        (is (= "stub-model" (:model request)))
        (is (= 1 (count @requests)) "one direct call, no agent loop")
        (is (nil? (:tools request)) "no tools are offered")
        (is (= 1 (count (:messages request))))
        (is (= "user" (-> request :messages first :role)))
        (is (str/starts-with? (-> request :messages first :content)
                              titles/instruction))
        (is (str/includes? (-> request :messages first :content)
                           "the build keeps flaking"))))))

(deftest generate-title-clips-a-huge-first-message
  (let [requests (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:message {:role "assistant" :content "Big paste"}})]
      (is (= "Big paste"
             (titles/generate-title stub-agent (apply str (repeat 5000 "x")))))
      (is (= (+ (count titles/instruction)
                2 ;; the blank line between the instruction and the message
                1 ;; the ellipsis marking the truncated message
                titles/max-prompt-chars)
             (count (-> @requests first :messages first :content)))))))

(deftest generate-title-does-nothing-for-a-blank-prompt
  (let [requests (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:message {:role "assistant" :content "Nope"}})]
      (is (nil? (titles/generate-title stub-agent "   \n ")))
      (is (empty? @requests) "a blank first message asks the model nothing"))))

(deftest generate-title-returns-nothing-when-the-reply-is-unusable
  (with-redefs [ollama/chat
                (fn [_client _req]
                  {:message {:role "assistant" :content "  "}})]
    (is (nil? (titles/generate-title stub-agent "the build keeps flaking")))))
