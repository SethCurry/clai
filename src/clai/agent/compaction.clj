(ns clai.agent.compaction
  "Keeping a session's history inside a token budget.

  Every chat turn sends a session's whole history to the model, so a long
  session eventually outgrows the model's context window and the provider
  starts rejecting requests. Compaction keeps the history bounded: once it is
  estimated to be over the agent's `:compact-after-tokens` budget, the older
  messages are summarized by the session's own model and replaced with that
  summary. The session's system prompt and its most recent messages are kept
  verbatim, so the model keeps its instructions and the thread it is working
  on.

  A compaction never summarizes the messages of the turn in progress. The
  prompt the agent is answering has to travel with the tool calls and results
  it produced (see [[keep-current-turn]]), or the next request has no user
  turn at all: providers reject it and models answer it by stopping instead of
  carrying on. So a compaction ends the conversation's older turns and leaves
  the current one whole, which is what lets an agent keep working after its
  history was compacted mid-turn.

  Token counts are estimated from character counts ([[chars-per-token]]
  characters per token), because tokenizers are model-specific and a budget
  only needs to be a good guess. Summarizing costs as few model calls as
  possible: the older messages are summarized in chunks of at most the budget,
  each pass folding the previous summary forward, so compacting an enormous
  history stays bounded instead of sending all of it to the summarizer at
  once.

  Nothing here touches storage. [[compact-history]] takes a history vector and
  returns the compacted one; `clai.agent.core` writes it back to the session
  and relays [[compaction-update]] to the caller.

      (require '[clai.agent.compaction :as compaction])

      (compaction/history-tokens history)
      ;; => 15234

      (:history (compaction/compact-history agent history))
      ;; => [{:role \"system\" :content \"You are a ...\"}
      ;;     {:role \"system\" :content \"[Session summary: ...] ...\"}
      ;;     {:role \"user\" :content \"now fix the test\"} ...]"
  (:require [clai.agent.providers :as providers]
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
;; Summary messages
;; ---------------------------------------------------------------------------

(def summary-marker
  "The line every compaction summary starts with.

  A stored summary is an ordinary `system` message -- providers only accept
  role and content -- so the marker is what makes one recognizable: it is
  folded into the next summary when the session is compacted again, and it is
  what a client replays as `session_compaction` rather than as a message."
  "[Session summary: earlier messages were compacted]")

(defn summary-message
  "The `system` message that stands in for the messages a compaction removed."
  {:malli/schema [:-> :string [:map
                               [:role [:= "system"]]
                               [:content :string]]]}
  [text]
  {:role "system" :content (str summary-marker "\n\n" (str/trim (str text)))})

(defn summary-text
  "The summary inside `message` when it is a compaction summary, else nil."
  {:malli/schema [:-> [:maybe :map] [:maybe :string]]}
  [message]
  (let [content (events/content->text (:content message))]
    (when (str/starts-with? content summary-marker)
      (not-empty (str/trim (subs content (count summary-marker)))))))

(defn summary?
  "True when `message` is a compaction summary written by
  [[summary-message]]."
  {:malli/schema [:-> [:maybe :map] boolean?]}
  [message]
  (some? (summary-text message)))

(def CompactionUpdate
  "The update a client receives when a session's history was compacted, and
  replays when it loads a session that holds a summary. It carries the summary
  so the client can show what the older messages became."
  [:map
   [:type [:= "session_compaction"]]
   [:content #'events/ContentList]])

(defn compaction-update
  "The `session_compaction` update announcing that `text` now stands in for the
  messages a compaction removed."
  {:malli/schema [:-> :string #'CompactionUpdate]}
  [text]
  {:type    "session_compaction"
   :content (events/content-list text)})

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
  "The index in `history` where the longest suffix that fits in `budget`
  tokens begins, always keeping at least the last message."
  [history budget]
  (let [last-index (dec (count history))]
    (loop [index last-index
           used  0]
      (if (neg? index)
        0
        (let [used' (+ used (message-tokens (nth history index)))]
          (if (or (= index last-index) (<= used' budget))
            (recur (dec index) used')
            (inc index)))))))

(defn- widen-for-tool-results
  "Move `start` back over `tool` result messages, so the kept suffix never
  begins with a result whose assistant tool call was summarized away --
  providers reject a `tool` message that does not answer a call in the same
  request."
  [history start]
  (loop [start start]
    (if (and (pos? start) (= "tool" (:role (nth history start))))
      (recur (dec start))
      start)))

(defn- turn-start
  "The index of the message that began the turn in progress: the last `user`
  message in `history`. Returns nil when the history holds no user message, so
  a caller can tell \"nothing in progress\" from \"began at the first\"."
  [history]
  (last (keep-indexed (fn [index message]
                        (when (= "user" (:role message)) index))
                      history)))

(defn- keep-current-turn
  "Move `start` back to the prompt the turn in progress is answering, when a
  compaction would otherwise replace it.

  The tool calls and results the agent produced since are part of that turn:
  summarizing the prompt away leaves the next chat request with no user turn
  at all, which providers reject and models answer by stopping instead of
  carrying on. Keeping the whole turn verbatim means compaction ends the older
  conversation rather than interrupting the one in progress."
  [history start]
  (if-let [turn (turn-start history)]
    (min start turn)
    start))

(defn- leading-system
  "The number of messages opening `history` that are system messages other than
  a compaction summary -- a session's instructions."
  [history]
  (count (take-while #(and (= "system" (:role %)) (not (summary? %))) history)))

(defn plan
  "Decide what a compaction of `history` with `after-tokens` budget would keep
  and what it would replace.

  Returns `{:system [...] :previous <summary text or nil> :older [...]
  :recent [...]}`:

    :system    the session's opening system prompt, kept verbatim
    :previous  the summary of an earlier compaction, when the history has one
    :older     the messages the new summary replaces
    :recent    the messages kept verbatim: the turn in progress (see
               [[keep-current-turn]]) plus as much recent history as
               [[tail-budget]] allows

  Returns nil when there is nothing to compact: the history already fits the
  budget, or everything left is the system prompt, the earlier summary, and
  the turn in progress."
  {:malli/schema [:-> [:sequential :map] [:int {:min 1}] [:maybe :map]]}
  [history after-tokens]
  (let [full    (vec history)
        start   (->> (suffix-start full (tail-budget after-tokens))
                     (widen-for-tool-results full)
                     (keep-current-turn full))
        prefix  (subvec full 0 start)
        systems (subvec prefix 0 (leading-system prefix))
        rest    (subvec prefix (count systems))
        previous (some summary-text (take 1 rest))
        older    (vec (if previous (subvec rest 1) rest))]
    (when (seq older)
      {:system   systems
       :previous previous
       :older    older
       :recent   (subvec full start)})))

;; ---------------------------------------------------------------------------
;; Summarizing
;; ---------------------------------------------------------------------------

(def summarize-instruction
  "The instruction that turns part of a conversation into a summary. It lives
  here so the request and the way its reply is used are described together."
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

(defn- transcript
  "Render `messages` as the plain-text transcript a summary request quotes."
  [messages]
  (str/join "\n\n" (keep transcript-entry messages)))

(defn- summarize-chunk
  "Ask `agent`'s model to fold `messages` into `previous` -- an earlier
  summary, or nil -- and return the new summary."
  [agent previous messages]
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
                                                           (transcript
                                                            messages))}))})
      :content
      events/content->text
      str/trim
      not-empty))

(defn chunks
  "Split `messages` into runs of at most `budget` estimated tokens each, so no
  summary request outgrows the budget it is compressing. A single message
  larger than the budget gets a chunk of its own."
  {:malli/schema [:-> [:sequential :map] [:int {:min 1}]
                  [:sequential [:vector :map]]]}
  [messages budget]
  (loop [remaining (seq messages)
         current   []
         used      0
         chunks    []]
    (if-let [message (first remaining)]
      (let [tokens (message-tokens message)]
        (if (or (empty? current) (<= (+ used tokens) budget))
          (recur (next remaining) (conj current message) (+ used tokens) chunks)
          (recur remaining [] 0 (conj chunks current))))
      (cond-> chunks (seq current) (conj current)))))

(defn summarize
  "Summarize the messages `plan` says are due for replacement, in as few model
  calls as `budget` allows.

  Each chunk of older messages is folded into the summary of the messages
  before it (starting from the plan's `:previous` summary, when the history was
  compacted before), so a summary always describes the whole conversation it
  stands in for. Returns the summary text, or nil when the model produced
  nothing usable."
  {:malli/schema [:-> [:map [:client :map] [:model :string]]
                  [:map [:older [:sequential :map]]]
                  [:int {:min 1}]
                  [:maybe :string]]}
  [agent {:keys [previous older]} budget]
  (reduce (fn [summary chunk] (summarize-chunk agent summary chunk))
          (some-> previous str/trim not-empty)
          (chunks older budget)))

;; ---------------------------------------------------------------------------
;; Compaction
;; ---------------------------------------------------------------------------

(def CompactionResult
  "What a compaction did: the history that replaces the stored one, and the
  update announcing it."
  [:map
   [:history [:vector :map]]
   [:update #'CompactionUpdate]])

(defn compact-history
  "Compact `history` when it has grown past `agent`'s `:compact-after-tokens`
  budget.

  `agent` supplies the client and model that write the summary as well as the
  budget itself; a missing, nil, or zero budget leaves the history alone. On
  success returns `{:history <compacted history> :update <session_compaction
  update>}`, where the history is the session's system prompt, the summary of
  everything compacted away so far, and the messages that stayed verbatim --
  the turn in progress always among them, so the agent can carry on with it.

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
        (when-let [text (summarize agent plan budget)]
          (let [compacted (-> (vec (:system plan))
                              (conj (summary-message text))
                              (into (:recent plan)))
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
                {:history compacted
                 :update  (compaction-update text)})
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
