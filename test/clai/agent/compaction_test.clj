(ns clai.agent.compaction-test
  "Tests for `clai.agent.compaction`: estimating the size of a session history,
  deciding what a compaction keeps, and summarizing the rest with the session's
  own model."
  (:require [clai.agent.compaction :as compaction]
            [clai.agent.providers :as providers]
            [clai.events.compaction :as event-compaction]
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

(defn- compacted
  "The history a session reads back after `result`: the summary appended to
  the session's system prompt, then the messages the compaction kept verbatim
  (which is what `clai.daemon.postgres.sessions` reconstructs)."
  [history result]
  (let [[systems messages] (split-with #(= "system" (:role %)) history)]
    (into (event-compaction/append-summary systems (:summary result))
          (take-last (:keep result) messages))))

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
        "and is never summarized away")))

(deftest plan-folds-an-earlier-summary-into-the-next-one
  (let [prompt  {:role "system" :content "You are clai."}
        merged  (first (event-compaction/append-summary
                        [prompt] "Earlier: the user fixed the build."))
        full    (vec (cons merged (rest (history 10))))
        plan    (compaction/plan full 1000)]
    (is (= "Earlier: the user fixed the build." (:previous plan)))
    (is (= [prompt] (:system plan))
        "the earlier summary is dropped from the system prompt the new one
         replaces")
    (is (not-any? event-compaction/summary? (:older plan))
        "and is never summarized as if it were a message")))

;; ---------------------------------------------------------------------------
;; Summarizing
;; ---------------------------------------------------------------------------

(deftest summarize-sends-the-whole-conversation-in-one-request
  (stub-summaries
   (fn [requests]
     (let [plan (compaction/plan (history 20) 1000)
           text (compaction/summarize stub-agent plan)
           spec (first @requests)]
       (is (= 1 (count @requests))
           "the older messages are summarized in one request, not one per chunk")
       (is (= "summary-1" text))
       (is (= "stub-model" (:model spec)))
       (is (nil? (:tools spec)) "summarizing never offers the model tools")
       (is (= compaction/summarize-instruction
              (-> spec :history first :content)))
       (is (not-any? #(str/includes? % "Summary so far")
                     (map :content (:history spec)))
           "the first request has no summary to fold in yet")
       (is (str/includes? (-> spec :history last :content)
                          (:content (first (:older plan))))
           "the conversation is quoted from its first message")
       (is (str/includes? (-> spec :history last :content)
                          (:content (last (:older plan))))
           "... through its last")))))

(deftest summarize-reuses-an-earlier-summary
  (stub-summaries
   (fn [requests]
     (compaction/summarize stub-agent {:previous "Earlier work"
                                       :older    (history 3)})
     (is (= 1 (count @requests)))
     (is (str/includes? (-> @requests first :history second :content)
                        "Earlier work")))))

(deftest summarize-returns-nothing-for-an-empty-reply
  (with-redefs [providers/complete (fn [_ _] {:role "assistant" :content "   "})]
    (is (nil? (compaction/summarize stub-agent {:older (history 2)})))))

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

(deftest compact-history-records-a-summary-of-the-whole-conversation
  (stub-summaries
   (fn [requests]
     (let [full   (history 20)
           result (compaction/compact-history stub-agent full)
           read   (compacted full result)]
       (is (= 1 (count @requests))
           "one request summarizes the whole conversation")
       (is (= {:type "session_compaction"}
              (select-keys (:update result) [:type])))
       (is (= "summary-1" (:summary result)))
       (is (event-compaction/summary? (first read))
           "the summary is appended to the session's system prompt")
       (is (= [{:role "system" :content "You are clai."}]
              (event-compaction/without-summaries [(first read)]))
           "and keeps the prompt the session was given")
       (is (< (compaction/history-tokens read)
              (compaction/history-tokens full))
           "compaction leaves a smaller history than it found")
       (is (<= (compaction/history-tokens read)
               (compaction/tail-budget (:compact-after-tokens stub-agent)))
           "and leaves headroom, so the next turn does not compact again")
       (is (= (:recent (compaction/plan full 1000)) (vec (drop 1 read)))
           "the most recent messages are kept verbatim")))))

(deftest compact-history-keeps-the-turn-in-progress
  (stub-summaries
   (fn [_requests]
     (let [prompt {:role "user" :content "fix the build"}
           rounds (vec (mapcat #(tool-round (str "c" %) 1) (range 6)))
           full   (vec (concat [{:role "system" :content "You are clai."}]
                               (rest (history 4))
                               [prompt]
                               rounds))
           result (compaction/compact-history stub-agent full)
           read   (compacted full result)]
       (is (some? result) "the older turn is summarized")
       (is (= (vec (cons prompt rounds)) (vec (drop 1 read)))
           "everything from the turn's prompt on survives the compaction whole")
       (is (= ["system" "user"]
              (mapv :role (take 2 read)))
           "the summary is appended to the system prompt, then the turn
            the agent is working on")))))

(deftest compact-history-folds-what-it-already-summarized
  (stub-summaries
   (fn [requests]
     (let [full     (history 20)
           first-r  (compaction/compact-history stub-agent full)
           first-h  (compacted full first-r)
           so-far   (count @requests)
           grown    (conj first-h (message "user" 3000))
           second-r (compaction/compact-history stub-agent grown)]
       (is (pos? so-far))
       (is (some? second-r) "a session that grew again compacts again")
       (is (= 1 (- (count @requests) so-far))
           "one request summarizes the grown conversation")
       (is (str/includes? (-> @requests last :history second :content)
                          "Summary so far:")
           "the earlier summary is folded into the new one")
       (is (= 1 (count (filter event-compaction/summary?
                               (compacted grown second-r))))
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
