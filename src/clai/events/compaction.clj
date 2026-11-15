(ns clai.events.compaction
  "The compaction event's vocabulary: what a summary looks like inside a
  session history, and how a client replays it.

  A session's history is an append-only event log (see
  `clai.daemon.postgres.sessions`). Compacting never rewrites that log: it
  appends one `compaction` event (see `clai.events.schema/CompactionEvent`)
  saying that every earlier event is summarized by its `:summary`, except the
  last `:keep` messages. Reading the session back therefore keeps the system
  messages that open it, appends the summary to them, drops the messages the
  summary covers, and keeps the rest.

  A summary lives in a `system` message after [[summary-marker]], so a history
  that has been compacted still says what its older messages became.
  [[append-summary]] appends a summary to the system prompt of a history and
  replaces the summary of an earlier compaction, so a session keeps exactly
  one of them.

      (require '[clai.events.compaction :as compaction])

      (compaction/append-summary [{:role \"system\" :content \"You are clai.\"}]
                                 \"The user asked for pongs.\")
      ;; => [{:role \"system\"
      ;;      :content \"You are clai.\\n\\n[Session summary: ...]\\n\\nThe user asked for pongs.\"}]

      (compaction/summary-text (first *1))
      ;; => \"The user asked for pongs.\""
  (:require [clai.events.schema :as events]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Summaries in a history
;; ---------------------------------------------------------------------------

(def summary-marker
  "The line that introduces a compaction summary inside a system message.

  It is what makes a stored summary recognizable: [[summary-text]] reads the
  summary after it, and it lets a compaction replace the summary of an earlier
  one instead of stacking another on top."
  "[Session summary: earlier messages were compacted]")

(defn- summary-line
  "The text a summary is written as: [[summary-marker]] and `text`."
  [text]
  (str summary-marker "\n\n" (str/trim (str text))))

(defn summary-text
  "The summary inside `message` when it carries a compaction summary, else
  nil."
  {:malli/schema [:-> [:maybe :map] [:maybe :string]]}
  [message]
  (let [content (events/content->text (:content message))]
    (when-let [index (str/index-of content summary-marker)]
      (not-empty (str/trim (subs content (+ index (count summary-marker))))))))

(defn summary?
  "True when `message` carries a compaction summary."
  {:malli/schema [:-> [:maybe :map] boolean?]}
  [message]
  (some? (summary-text message)))

(defn- strip-summary
  "`message` without the compaction summary it carries, or nil when nothing
  would be left of it. A message without a summary is returned unchanged."
  [message]
  (let [content (events/content->text (:content message))]
    (if-let [index (str/index-of content summary-marker)]
      (let [prompt (str/trim (subs content 0 index))]
        (when-not (str/blank? prompt)
          (assoc message :content prompt)))
      message)))

(defn without-summaries
  "`messages` without the compaction summaries they carry, dropping the ones
  that were nothing but a summary."
  {:malli/schema [:-> [:sequential :map] [:vector :map]]}
  [messages]
  (into [] (keep strip-summary) messages))

(defn append-summary
  "`messages` -- the system messages that open a history -- with `text`
  appended to the last of them as their compaction summary, replacing the
  summary of an earlier compaction.

  A history without system messages gets a `system` message of its own
  carrying the summary."
  {:malli/schema [:-> [:sequential :map] :string [:vector :map]]}
  [messages text]
  (let [prompts (without-summaries messages)
        summary (summary-line text)]
    (if (seq prompts)
      (update-in prompts [(dec (count prompts)) :content]
                 #(str (events/content->text %) "\n\n" summary))
      [{:role "system" :content summary}])))

;; ---------------------------------------------------------------------------
;; Client updates
;; ---------------------------------------------------------------------------

(def CompactionUpdate
  "The update a client receives when a session's history was compacted, and
  replays when it loads a session whose history holds a summary. It carries
  the summary so the client can show what the older messages became."
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
