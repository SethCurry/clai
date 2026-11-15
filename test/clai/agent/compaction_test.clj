(ns clai.agent.compaction-test
  "Tests for `clai.agent.compaction`: estimating the size of a session history,
  deciding what a compaction keeps, and summarizing the rest with the session's
  own model."
  (:require [clai.agent.compaction :as compaction]
            [clai.agent.providers :as providers]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- message
  "A history message of `role` whose content is `chars` characters long, so a
  test can size a history precisely."
  [role chars]
  {:role role :content (str role "-" (apply str (repeat chars "x")))})

(defn- history
  "A conversation of `count` messages, opening with a system prompt, each
  roughly 100 estimated tokens."
  [count]
  (vec (cons {:role "system" :content "You are clai."}
             (map #(message (if (even? %) "user" "assistant") 396)
                  (range count)))))

(defn- tool-round
  "One assistant tool call followed by `results` tool result messages, all
  roughly 100 estimated tokens, as a round of a tool-using turn leaves them in
  a session's history."
  [id results]
  (cons {:role    "assistant"
         :content ""
         :tool_calls [{:id id
                       :function {:name "read_file" :arguments {:path "a.txt"}}}]}
        (map (fn [_] (assoc (message "tool" 396) :tool_call_id id))
             (range results))))

(def ^:private stub-agent
  "The slice of an agent map that [[compaction/compact-history]] reads."
  {:client {:provider :ollama} :model "stub-model" :compact-after-tokens 1000})

(defn- stub-summaries
  "Run `f` with the provider's chat turn replaced by a stub that answers with
  `summary-1`, `summary-2`, ... and records every request in `requests`."
  [f]
  (let [requests (atom [])]
    (with-redefs [providers/complete
                  (fn [_client spec]
                    (swap! requests conj spec)
                    {:role    "assistant"
                     :content (str "summary-" (count @requests))})]
      (f requests))))

;; ---------------------------------------------------------------------------
;; Token accounting
;; ---------------------------------------------------------------------------

(deftest estimate-tokens-rounds-up
  (are [text tokens] (= tokens (compaction/estimate-tokens text))
    nil   0
    ""    0
    "abc" 1
    "abcd" 1
    "abcde" 2))

(deftest message-tokens-counts-content-reasoning-and-tool-calls
  (let [plain   (compaction/message-tokens {:role "user" :content "abcd"})
        thought (compaction/message-tokens {:role "assistant"
                                            :content "abcd"
                                            :thinking "abcd"})]
    (is (= (+ compaction/message-overhead-tokens 1) plain))
    (is (> thought plain) "reasoning is context the model paid for")
    (is (> (compaction/message-tokens
            {:role "assistant"
             :content ""
             :tool_calls [{:function {:name "read_file" :arguments {:path "a"}}}]})
           compaction/message-overhead-tokens)
        "tool-call arguments are counted too")))

(deftest history-tokens-adds-up-its-messages
  (is (= 0 (compaction/history-tokens [])))
  (is (= (+ (compaction/message-tokens {:role "user" :content "abcd"})
            (compaction/message-tokens {:role "assistant" :content "abcd"}))
         (compaction/history-tokens [{:role "user" :content "abcd"}
                                     {:role "assistant" :content "abcd"}]))))

;; ---------------------------------------------------------------------------
;; Summary messages
;; ---------------------------------------------------------------------------

(deftest summary-messages-round-trip
  (let [summary (compaction/summary-message "  The user fixed the build.  ")]
    (is (= "system" (:role summary)))
    (is (str/starts-with? (:content summary) compaction/summary-marker))
    (is (compaction/summary? summary))
    (is (= "The user fixed the build." (compaction/summary-text summary)))))

(deftest other-system-messages-are-not-summaries
  (are [message] (nil? (compaction/summary-text message))
    nil
    {:role "system" :content "You are clai."}
    {:role "user" :content compaction/summary-marker}
    {:role "assistant" :content ""}))

(deftest compaction-update-carries-the-summary-as-text
  (is (= {:type "session_compaction"
          :content [{:type "text" :content "The user fixed the build."}]}
         (compaction/compaction-update "The user fixed the build."))))

;; ---------------------------------------------------------------------------
;; What a compaction keeps
;; ---------------------------------------------------------------------------

(deftest plan-ignores-a-history-that-fits
  (is (nil? (compaction/plan (history 3) 100000))
      "a history under the budget is left alone")
  (is (nil? (compaction/plan [{:role "system" :content "sys"}] 1))
      "there is nothing to summarize in a system prompt alone"))

(deftest plan-keeps-the-system-prompt-and-the-recent-messages
  (let [full   (history 10)
        plan   (compaction/plan full 1000)
        recent (count (:recent plan))]
    (is (= [{:role "system" :content "You are clai."}] (:system plan)))
    (is (nil? (:previous plan)))
    (is (pos? recent))
    (is (< (compaction/history-tokens (:recent plan))
           (compaction/tail-budget 1000)))
    (is (= ["system"] (mapv :role (:system plan)))
        "the system prompt is never summarized away")
    (is (= (vec (concat (:system plan) (:older plan) (:recent plan))) full)
        "the plan partitions the history without reordering it")))

(deftest plan-keeps-a-tool-call-with-its-results
  (let [full (vec (concat [{:role "system" :content "sys"}
                           (message "user" 396)]
                          (tool-round "c1" 2)
                          [{:role "assistant" :content "done one"}
                           {:role "user" :content "and two"}
                           {:role "assistant" :content "done two"}]))
        plan (compaction/plan full 440)]
    (is (= ["assistant" "tool" "tool" "assistant" "user" "assistant"]
           (mapv :role (:recent plan)))
        "a kept tail never starts with a result whose call was summarized")
    (is (seq (:tool_calls (first (:recent plan))))
        "the call the kept results answer travels with them")))

(deftest plan-keeps-the-turn-in-progress-whole
  (let [full  (vec (concat [{:role "system" :content "You are clai."}]
                           (rest (history 4))
                           [{:role "user" :content "fix the build"}]
                           (mapcat #(tool-round (str "c" %) 1) (range 10))))
        plan  (compaction/plan full 1000)
        turn  (drop-while (fn [m] (not= "fix the build" (:content m))) full)]
    (is (some? plan) "the older turn is compacted")
    (is (= (vec turn) (vec (take-last (count turn) (:recent plan))))
        "everything from the turn's prompt on is kept verbatim")
    (is (= "fix the build" (:content (first (:recent plan))))
        "the prompt the agent is answering opens the kept tail")
    (is (not-any? #(= "fix the build" (:content %)) (:older plan))
        "and is never summarized away")
    (is (= (vec (concat (:system plan) (:older plan) (:recent plan))) full)
        "the plan still partitions the history without reordering it")))

(deftest plan-folds-an-earlier-summary-into-the-next-one
  (let [summary (compaction/summary-message "Earlier: the user fixed the build.")
        full    (vec (cons {:role "system" :content "You are clai."}
                           (cons summary (rest (history 10)))))
        plan    (compaction/plan full 1000)]
    (is (= "Earlier: the user fixed the build." (:previous plan)))
    (is (not-any? compaction/summary? (:older plan))
        "the earlier summary is replaced rather than summarized again")
    (is (= (vec (concat (:system plan) (:older plan) (:recent plan)))
           (vec (remove #{summary} full)))
        "the plan no longer contains the message it folds in")))

;; ---------------------------------------------------------------------------
;; Summarizing
;; ---------------------------------------------------------------------------

(deftest chunks-split-by-budget-without-losing-a-message
  (let [messages (mapv (fn [_] (message "user" 396)) (range 5))
        chunks   (compaction/chunks messages 250)]
    (is (= messages (vec (apply concat chunks))))
    (is (< 1 (count chunks)))
    (is (every? #(<= (compaction/history-tokens %) 250) (butlast chunks))
        "every chunk but the last stays inside the budget")))

(deftest summarize-carries-the-previous-summary-forward
  (stub-summaries
   (fn [requests]
     (let [plan (compaction/plan (history 20) 1000)
           text (compaction/summarize stub-agent plan 1000)]
       (is (< 1 (count @requests))
           "a long history costs more than one summary request")
       (is (= (str "summary-" (count @requests)) text)
           "the last answer is the summary")
       (is (every? #(= "stub-model" (:model %)) @requests))
       (is (nil? (:tools (first @requests)))
           "summarizing never offers the model tools")
       (is (= compaction/summarize-instruction
              (-> @requests first :history first :content)))
       (is (not-any? #(str/includes? % "Summary so far")
                     (-> @requests first :history (->> (map :content))))
           "the first request has no summary to fold in yet")
       (is (str/includes? (-> @requests second :history second :content)
                          "Summary so far:")
           "later requests fold the earlier summary forward")))))

(deftest summarize-reuses-an-earlier-summary
  (stub-summaries
   (fn [requests]
     (compaction/summarize stub-agent {:previous "Earlier work" :older (history 3)}
                           100000)
     (is (= 1 (count @requests)))
     (is (str/includes? (-> @requests first :history second :content)
                        "Earlier work")))))

(deftest summarize-returns-nothing-for-an-empty-reply
  (with-redefs [providers/complete (fn [_ _] {:role "assistant" :content "   "})]
    (is (nil? (compaction/summarize stub-agent {:older (history 2)} 100000)))))

;; ---------------------------------------------------------------------------
;; Compaction
;; ---------------------------------------------------------------------------

(deftest compact-history-leaves-a-small-history-alone
  (stub-summaries
   (fn [requests]
     (is (nil? (compaction/compact-history stub-agent (history 2))))
     (is (empty? @requests) "nothing is sent to the model"))))

(deftest compact-history-can-be-turned-off
  (stub-summaries
   (fn [requests]
     (is (nil? (compaction/compact-history (assoc stub-agent
                                                 :compact-after-tokens 0)
                                          (history 40))))
     (is (nil? (compaction/compact-history (dissoc stub-agent
                                                  :compact-after-tokens)
                                          (history 40))))
     (is (empty? @requests)))))

(deftest compact-history-replaces-the-older-messages-with-a-summary
  (stub-summaries
   (fn [_requests]
     (let [full   (history 20)
           result (compaction/compact-history stub-agent full)
           compact (:history result)]
       (is (= {:type "session_compaction"}
              (select-keys (:update result) [:type])))
       (is (= ["system" "system"] (mapv :role (take 2 compact)))
           "the system prompt is kept and the summary follows it")
       (is (compaction/summary? (second compact)))
       (is (< (compaction/history-tokens compact)
              (compaction/history-tokens full))
           "compaction leaves a smaller history than it found")
       (is (<= (compaction/history-tokens compact)
               (compaction/tail-budget (:compact-after-tokens stub-agent)))
           "and leaves headroom, so the next turn does not compact again")
       (is (= (:recent (compaction/plan full 1000)) (vec (drop 2 compact)))
           "the most recent messages are kept verbatim")))))

(deftest compact-history-keeps-the-turn-in-progress
  (stub-summaries
   (fn [_requests]
     (let [prompt  {:role "user" :content "fix the build"}
           rounds  (vec (mapcat #(tool-round (str "c" %) 1) (range 6)))
           full    (vec (concat [{:role "system" :content "You are clai."}]
                                (rest (history 4))
                                [prompt]
                                rounds))
           compact (:history (compaction/compact-history stub-agent full))]
       (is (some? compact) "the older turn is summarized")
       (is (= (vec (cons prompt rounds)) (vec (drop 2 compact)))
           "the turn the agent is working on survives the compaction whole")
       (is (= ["system" "system" "user"] (mapv :role (take 3 compact)))
           "the system prompt, the summary, then the turn that was compacted
            around")))))

(deftest compact-history-does-not-repeat-what-it-already-summarized
  (stub-summaries
   (fn [requests]
     (let [first-pass      (:history (compaction/compact-history stub-agent
                                                               (history 20)))
           requests-so-far (count @requests)
           grown           (conj (vec first-pass) (message "user" 3000))
           second-pass     (compaction/compact-history stub-agent grown)]
       (is (pos? requests-so-far))
       (is (some? second-pass) "a session that grew again compacts again")
       (is (= 1 (- (count @requests) requests-so-far))
           "the earlier summary is reused, not summarized from scratch")
       (is (= 1 (count (filter compaction/summary? (:history second-pass))))
           "a session keeps exactly one summary")))))

(deftest compact-history-keeps-a-summary-that-would-not-shrink-the-history
  (with-redefs [providers/complete
                (fn [_ _]
                  {:role "assistant" :content (apply str (repeat 50000 "s"))})]
    (is (nil? (compaction/compact-history stub-agent (history 20)))
        "a summary longer than what it summarizes is not stored")))

(deftest compact-history-survives-a-failing-summary-request
  (with-redefs [providers/complete (fn [_ _]
                                     (throw (ex-info "provider down" {})))]
    (is (nil? (compaction/compact-history stub-agent (history 20)))
        "a failed summary is skipped rather than failing the prompt")))
