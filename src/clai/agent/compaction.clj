(ns clai.agent.compaction
  "Keeping a session's history inside a token budget.

  Every chat turn sends a session's whole history to the model, so a long
  session eventually outgrows the model's context window and the provider
  starts rejecting requests. Compaction keeps the history bounded: once it is
  estimated to be over the agent's `:compact-after-tokens` budget, the
  conversation is summarized by the session's own model -- the whole
  conversation in one request, so the summary stays as short as the model can
  make it -- and that summary takes the place of the messages it covers. The
  session's system prompt and its most recent messages are kept verbatim, so
  the model keeps its instructions and the thread it is working on.

  A compaction never summarizes the messages of the turn in progress. The
  prompt the agent is answering has to travel with the tool calls and results
  it produced (see [[keep-current-turn]]), or the next request has no user
  turn at all: providers reject it and models answer it by stopping instead of
  carrying on. So a compaction ends the conversation's older turns and leaves
  the current one whole, which is what lets an agent keep working after its
  history was compacted mid-turn.

  Token counts are estimated from character counts ([[chars-per-token]]
  characters per token), because tokenizers are model-specific and a budget
  only needs to be a good guess.

  Nothing here touches storage. [[compact-history]] takes a history vector and
  returns the summary to record; `clai.agent.core` appends it as a
  `compaction` event (see `clai.events.compaction`), so the older messages stay
  in the log and reading the session back ignores them.

      (require '[clai.agent.compaction :as compaction])

      (compaction/history-tokens history)
      ;; => 15234

      (compaction/compact-history agent history)
      ;; => {:summary \"The user fixed the build ...\"
      ;;     :keep    2
      ;;     :update  {:type \"session_compaction\" ...}}"
  (:require [clai.agent.providers :as providers]
            [clai.events.compaction :as event-compaction]
            [clai.events.schema :as events]
            [clai.util :as util]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Token accounting
;; ---------------------------------------------------------------------------

(def default-after-tokens
  "The token budget a session's history may reach before it is compacted, when
  neither the config file (`:compact-after-tokens`), the
  `CLAI_COMPACT_AFTER_TOKENS` environment variable, nor a session's stored
  agent configuration names one."
  250000)

(def chars-per-token
  "How many characters one token is estimated to cover. Four is the usual rule
  of thumb for English prose and code."
  4)

(def message-overhead-tokens
  "Tokens charged to every message for its role marker and the provider's
  message framing, which a character count does not see."
  4)

(defn estimate-tokens
  "Estimate how many tokens `text` occupies, rounding up so any non-blank text
  costs at least one token. Non-strings are counted as their printed form, and
  nil costs nothing."
  {:malli/schema [:-> [:maybe :any] [:int {:min 0}]]}
  [text]
  (let [chars (count (str text))]
    (quot (+ chars (dec chars-per-token)) chars-per-token)))

(defn message-text
  "Everything in `message` a model reads: its content, its reasoning, and the
  tool calls it asked for. Blank parts are dropped."
  {:malli/schema [:-> [:map] :string]}
  [message]
  (str/join "\n"
            (remove str/blank?
                    [(events/content->text (:content message))
                     (str (:thinking message))
                     (when (seq (:tool_calls message))
                       (pr-str (:tool_calls message)))])))

(defn message-tokens
  "Estimate the tokens one history `message` occupies, including the framing
  overhead every message pays."
  {:malli/schema [:-> [:map] [:int {:min 1}]]}
  [message]
  (+ message-overhead-tokens (estimate-tokens (message-text message))))

(defn history-tokens
  "Estimate the tokens `history` occupies, as a chat request would send it."
  {:malli/schema [:-> [:sequential :map] [:int {:min 0}]]}
  [history]
  (transduce (map message-tokens) + 0 history))

;; ---------------------------------------------------------------------------
;; What a compaction keeps
;; ---------------------------------------------------------------------------

(def tail-fraction
  "The share of the token budget a compaction leaves for recent messages. The
  remainder is headroom for the next turns, so a compacted session does not
  immediately need compacting again."
  1/2)

(defn tail-budget
  "The tokens a compaction leaves for the messages it keeps verbatim."
  {:malli/schema [:-> [:int {:min 1}] [:int {:min 1}]]}
  [after-tokens]
  (max 1 (long (* after-tokens tail-fraction))))

(defn- suffix-start
  "The index in `messages` where the longest suffix that fits in `budget`
  tokens begins, always keeping at least the last message."
  [messages budget]
  (let [last-index (dec (count messages))]
    (loop [index last-index
           used  0]
      (if (neg? index)
        0
        (let [used' (+ used (message-tokens (nth messages index)))]
          (if (or (= index last-index) (<= used' budget))
            (recur (dec index) used')
            (inc index)))))))

(defn- widen-for-tool-results
  "Move `start` back over `tool` result messages, so the kept suffix never
  begins with a result whose assistant tool call was summarized away --
  providers reject a `tool` message that does not answer a call in the same
  request."
  [messages start]
  (loop [start start]
    (if (and (pos? start) (= "tool" (:role (nth messages start))))
      (recur (dec start))
      start)))

(defn- turn-start
  "The index of the message that began the turn in progress: the last `user`
  message in `messages`. Returns nil when there is no user message, so a
  caller can tell \"nothing in progress\" from \"began at the first\"."
  [messages]
  (last (keep-indexed (fn [index message]
                        (when (= "user" (:role message)) index))
                      messages)))

(defn- keep-current-turn
  "Move `start` back to the prompt the turn in progress is answering, when a
  compaction would otherwise replace it.

  The tool calls and results the agent produced since are part of that turn:
  summarizing the prompt away leaves the next chat request with no user turn
  at all, which providers reject and models answer by stopping instead of
  carrying on. Keeping the whole turn verbatim means compaction ends the older
  conversation rather than interrupting the one in progress."
  [messages start]
  (if-let [turn (turn-start messages)]
    (min start turn)
    start))

(defn- system-count
  "The number of messages opening `history` that are system messages: the
  session's instructions, with the summary of an earlier compaction appended
  to one of them."
  [history]
  (count (take-while #(= "system" (:role %)) history)))

(defn plan
  "Decide what a compaction of `history` with `after-tokens` budget would
  summarize and what it would keep.

  Returns `{:system [...] :previous <summary text or nil> :older [...]
  :recent [...]}`:

    :system    the session's opening system messages, with the summary of an
               earlier compaction removed (it is folded into the new one)
    :previous  the summary of that earlier compaction, when there is one
    :older     the messages the new summary stands in for
    :recent    the messages kept verbatim: the turn in progress (see
               [[keep-current-turn]]) plus as much recent history as
               [[tail-budget]] allows

  Returns nil when there is nothing to compact: the history already fits the
  budget, or everything left is the system prompt and the turn in progress."
  {:malli/schema [:-> [:sequential :map] [:int {:min 1}] [:maybe :map]]}
  [history after-tokens]
  (let [full     (vec history)
        systems  (subvec full 0 (system-count full))
        messages (subvec full (count systems))
        start    (->> (suffix-start messages (tail-budget after-tokens))
                      (widen-for-tool-results messages)
                      (keep-current-turn messages))
        older    (subvec messages 0 start)]
    (when (seq older)
      {:system   (event-compaction/without-summaries systems)
       :previous (some event-compaction/summary-text systems)
       :older    older
       :recent   (subvec messages start)})))

;; ---------------------------------------------------------------------------
;; Summarizing
;; ---------------------------------------------------------------------------

(def summarize-instruction
  "The instruction that turns a conversation into a summary. It lives here so
  the request and the way its reply is used are described together."
  (str "You are compacting a long chat session between a user and an AI "
       "coding agent so it fits the model's context window.\n"
       "Write a dense summary of the conversation you are given, keeping what "
       "the agent needs to carry on: the user's goals and requests, the "
       "decisions made and why, the files, commands and APIs involved, what "
       "was tried and what failed, and what is still open.\n"
       "Reply with the summary only, in the language of the conversation. Do "
       "not address the user, ask questions, or mention that you summarized "
       "anything."))

(defn- transcript-entry
  "One role-prefixed rendering of a history `message` for a summary request."
  [message]
  (let [text (message-text message)]
    (when-not (str/blank? text)
      (str (str/upper-case (str (or (:role message) "user"))) ": " text))))

(defn transcript
  "Render `messages` as the plain-text transcript a summary request quotes."
  {:malli/schema [:-> [:sequential :map] :string]}
  [messages]
  (str/join "\n\n" (keep transcript-entry messages)))

(defn summarize
  "Summarize the messages `plan` says are due for replacement, in one request
  to `agent`'s model.

  The whole conversation -- the summary of an earlier compaction, when there
  was one, plus every message being replaced -- is sent at once, so the model
  writes one summary of it rather than a summary of each part. Returns the
  summary text, or nil when the model produced nothing usable."
  {:malli/schema [:-> [:map [:client :map] [:model :string]]
                  [:map [:older [:sequential :map]]]
                  [:maybe :string]]}
  [agent {:keys [previous older]}]
  (-> (providers/complete (:client agent)
                          {:model   (:model agent)
                           :history (cond-> [{:role "system"
                                              :content summarize-instruction}]
                                      previous
                                      (conj {:role "user"
                                             :content (str "Summary so far:\n\n"
                                                           previous)})
                                      true
                                      (conj {:role "user"
                                             :content (str "Conversation:\n\n"
                                                           (transcript older))}))})
      :content
      events/content->text
      str/trim
      not-empty))

;; ---------------------------------------------------------------------------
;; Compaction
;; ---------------------------------------------------------------------------

(def CompactionResult
  "What a compaction did: the summary that now stands in for the messages it
  covers, how many messages it left verbatim, and the update announcing it."
  [:map
   [:summary :string]
   [:keep [:int {:min 0}]]
   [:update #'event-compaction/CompactionUpdate]])

(defn compact-history
  "Compact `history` when it has grown past `agent`'s `:compact-after-tokens`
  budget.

  `agent` supplies the client and model that write the summary as well as the
  budget itself; a missing, nil, or zero budget leaves the history alone. On
  success returns `{:summary <summary text> :keep <messages left verbatim>
  :update <session_compaction update>}`, where the caller records the summary
  as a compaction event (see `clai.events.compaction`): the summary replaces
  every message before it except the `:keep` most recent ones -- the turn in
  progress always among them, so the agent can carry on with it.

  Returns nil when nothing was compacted -- because the history fits, because
  there is nothing worth summarizing, or because the summary request failed. A
  failed summary is logged and skipped rather than failing the prompt: the
  conversation can still be sent, and the next turn tries again."
  {:malli/schema [:-> [:map
                       [:client :map]
                       [:model :string]
                       [:compact-after-tokens {:optional true}
                        [:maybe [:int {:min 0}]]]]
                  [:sequential :map]
                  [:maybe #'CompactionResult]]}
  [agent history]
  (let [budget (long (or (:compact-after-tokens agent) 0))]
    (when-let [plan (when (pos? budget) (plan history budget))]
      (try
        (when-let [text (summarize agent plan)]
          (let [compacted (into (event-compaction/append-summary (:system plan) text)
                                (:recent plan))
                before    (history-tokens history)
                after     (history-tokens compacted)]
            (if (< after before)
              (do
                (tel/log! {:level :info
                           :id    ::compacted
                           :data  {:tokensBefore before
                                   :tokensAfter  after
                                   :summarized   (count (:older plan))
                                   :kept         (count (:recent plan))}}
                          "Compacted session history")
                {:summary text
                 :keep    (count (:recent plan))
                 :update  (event-compaction/compaction-update text)})
              ;; A model that answers with more text than it was given is not
              ;; worth storing: compacting must make the history smaller.
              (tel/log! {:level :warn
                         :id    ::no-shrink
                         :data  {:tokensBefore before
                                 :tokensAfter  after}}
                        "Summary did not shrink the session history; skipping compaction"))))
        (catch Throwable e
          (tel/log! {:level :warn
                     :id    ::compaction-failed
                     :data  {:error (util/error-message e)}}
                    "Could not summarize the session history; skipping compaction")
          nil)))))
