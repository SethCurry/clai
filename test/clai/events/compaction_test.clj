(ns clai.events.compaction-test
  "Tests for `clai.events.compaction`: how a compaction summary is written into
  a session's system prompt, read back out of it, and replayed to a client."
  (:require [clai.events.compaction :as compaction]
            [clai.test-support :refer [instrument]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once instrument)

(def ^:private prompt
  "A session's opening system prompt."
  {:role "system" :content "You are clai."})

(deftest append-summary-appends-to-the-system-prompt
  (let [[message :as merged] (compaction/append-summary
                              [prompt] "  The user fixed the build.  ")]
    (is (= 1 (count merged)))
    (is (= "system" (:role message)))
    (is (str/starts-with? (:content message) "You are clai."))
    (is (str/includes? (:content message) compaction/summary-marker))
    (is (compaction/summary? message))
    (is (= "The user fixed the build." (compaction/summary-text message)))))

(deftest append-summary-stands-alone-without-a-system-prompt
  (let [[message :as merged] (compaction/append-summary [] "The user fixed it.")]
    (is (= 1 (count merged)))
    (is (= "system" (:role message)))
    (is (= "The user fixed it." (compaction/summary-text message)))))

(deftest append-summary-replaces-an-earlier-summary
  (let [merged   (compaction/append-summary [prompt] "The user fixed it.")
        remerged (compaction/append-summary merged "The user fixed it and left.")]
    (is (= 1 (count remerged))
        "a session keeps exactly one summary")
    (is (= "The user fixed it and left."
           (compaction/summary-text (first remerged))))
    (is (= "You are clai."
           (-> remerged first :content
               (str/split (re-pattern (java.util.regex.Pattern/quote
                                       compaction/summary-marker)))
               first
               str/trim))
        "the system prompt in front of it is untouched")))

(deftest without-summaries-keeps-only-what-was-not-a-summary
  (is (= [prompt] (compaction/without-summaries [prompt])))
  (is (= [prompt]
         (compaction/without-summaries
          (compaction/append-summary [prompt] "The user fixed it."))))
  (is (= [] (compaction/without-summaries
             (compaction/append-summary [] "The user fixed it.")))
      "a summary standing in for the whole prompt is dropped"))

(deftest other-system-messages-are-not-summaries
  (are [message] (nil? (compaction/summary-text message))
    nil
    {}
    prompt
    {:role "user" :content compaction/summary-marker}
    {:role "assistant" :content ""}))

(deftest compaction-update-carries-the-summary-as-text
  (is (= {:type "session_compaction"
          :content [{:type "text" :content "The user fixed the build."}]}
         (compaction/compaction-update "The user fixed the build."))))
