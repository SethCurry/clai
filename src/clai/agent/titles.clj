(ns clai.agent.titles
  "Automatic titles for new sessions.

  A session is named from its first message: [[generate-title]] asks the model
  the session chats with for a 1 to 5 word title, in one direct provider call.
  That call is deliberately outside the agent loop -- it carries no tools, no
  session history, and nothing about it is recorded -- so naming a session
  costs one small chat turn and leaves the conversation untouched.

  Models do not always obey a request for a bare title, so [[clean-title]]
  normalizes the reply: the first non-blank line, without quoting, list
  markers, or trailing punctuation, truncated to five words.

      (require '[clai.agent.titles :as titles])

      (titles/clean-title \"\\\"Fix the flaky CI build.\\\"\")
      ;; => \"Fix the flaky CI build\""
  (:require [clai.agent.providers :as providers]
            [clai.events.schema :as events]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Title shape
;; ---------------------------------------------------------------------------

(def max-title-words
  "The most words a generated title keeps. A longer reply is truncated, since
  a model asked for a short title sometimes answers with a sentence."
  5)

(def max-prompt-chars
  "The most characters of the first message a title request quotes. A first
  message can be a pasted file, and the gist is all a title needs."
  2000)

(def instruction
  "The instruction that turns a session's first message into a title. It lives
  here so the request and the parsing of its reply are described together."
  (str "Write a title for this chat session in 1 to 5 words. "
       "Use the language of the message and name what the session is about. "
       "Reply with the title only: no quotes, no trailing punctuation, "
       "no explanation."))

;; ---------------------------------------------------------------------------
;; Request
;; ---------------------------------------------------------------------------

(defn- clip
  "`text` limited to [[max-prompt-chars]] characters, marked as truncated."
  {:malli/schema [:-> :string :string]}
  [text]
  (if (<= (count text) max-prompt-chars)
    text
    (str (subs text 0 max-prompt-chars) "…")))

(defn- title-history
  "The one-message history asking the model to name a session whose first
  message is `text`."
  {:malli/schema [:-> :string [:vector :map]]}
  [text]
  [{:role "user" :content (str instruction "\n\n" (clip text))}])

;; ---------------------------------------------------------------------------
;; Reply parsing
;; ---------------------------------------------------------------------------

(defn- strip-markup
  "Remove the list markers, quoting, and trailing punctuation a model wraps a
  title in. `text` is one line with its whitespace already collapsed."
  {:malli/schema [:-> :string :string]}
  [text]
  (-> text
      ;; List markers first, so a numbered title keeps its own digits.
      (str/replace #"^(?:[#>*-]+\s+|\d+[.)]\s+)+" "")
      (str/replace #"^[\s\"'`*_#\[]+" "")
      (str/replace #"[\s\"'`*_#\]]+$" "")
      (str/replace #"[\s.,:;]+$" "")
      str/trim))

(defn clean-title
  "The usable title inside a model's `reply` to a title request.

  Only the first non-blank line is kept, its markup stripped by
  [[strip-markup]], its whitespace collapsed to single spaces, and anything
  past [[max-title-words]] words dropped. Returns nil when nothing usable
  remains, so a blank or evasive reply simply leaves the session untitled."
  {:malli/schema [:-> :any [:maybe :string]]}
  [reply]
  (when-let [line (->> (events/content->text reply)
                       str/split-lines
                       (map str/trim)
                       (remove str/blank?)
                       first)]
    (->> (str/split (strip-markup (str/replace line #"\s+" " ")) #"\s+")
         (take max-title-words)
         (str/join " ")
         not-empty)))

;; ---------------------------------------------------------------------------
;; Title generation
;; ---------------------------------------------------------------------------

(defn generate-title
  "Ask the model behind `agent` for a 1 to 5 word title describing `prompt`.

  `agent` is the agent map from `clai.agent.core/make-agent`, after any
  session configuration is applied, so the title uses the same provider and
  model the session chats with. The request goes straight to the provider:
  no tools are offered and nothing is read from or written to the session's
  history.

  `prompt` is the session's first message, as a string or as event content
  blocks. Returns the cleaned title, or nil when the prompt is blank or the
  model's reply holds nothing usable."
  {:malli/schema [:-> [:map [:client :map] [:model :string]] :any [:maybe :string]]}
  [agent prompt]
  (let [text (str/trim (events/content->text prompt))]
    (when-not (str/blank? text)
      (->> (providers/complete (:client agent)
                               {:model   (:model agent)
                                :history (title-history text)})
           :content
           clean-title))))
