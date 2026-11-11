(ns clai.agent.providers.ollama.prompt
  "Conversion between event-shaped prompts/history and Ollama chat messages.

  Prompts use the content vocabulary from `clai.events.schema` and may include
  resource blocks. Ollama's chat API only accepts text, so this namespace
  flattens those parts into `{:role ... :content ...}` maps and turns persisted
  history back into event-shaped updates for the REPL."
  (:require [clai.agent.resources :as resources]
            [clai.events.schema :as events]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Prompt -> Ollama messages
;; ---------------------------------------------------------------------------

(defn- name->str
  "Normalize a keyword or string to its string form. Returns nil for nil and
  falls back to `str` for anything else."
  [x]
  (cond
    (nil? x)     nil
    (string? x)  x
    (keyword? x) (name x)
    :else        (str x)))

(defn- part->text
  "Extract a plain-text string from one prompt content part.

  Text parts contribute their event-schema `:content` field (with `:text`
  accepted for tolerance). Resource parts are rendered by `clai.agent.resources`
  so file references reach the model instead of being dropped. Other parts
  (images, audio, etc.) are dropped, since Ollama chat wants text here.

  `opts` is threaded to the resource renderers and may contain a `:cwd` for
  resolving relative resource paths."
  [part opts]
  (cond
    (string? part) part

    (map? part)
    (case (name->str (:type part))
      "text"          (or (:content part) (:text part))
      "resource_link" (resources/resource-link->text part opts)
      "resource"      (resources/embedded-resource->text part opts)
      ;; Tolerant of variants that carry text without a known :type.
      (or (:content part) (:text part)))

    :else nil))

(defn- content->text
  "Flatten a prompt `content` field (a string or a sequence of parts) to a
  single string, dropping nils and joining with newlines."
  ([content] (content->text content nil))
  ([content opts]
   (cond
     (nil? content)        ""
     (string? content)     content
     (sequential? content) (str/join "\n" (keep #(part->text % opts) content))
     :else                 (str content))))

(defn- message->ollama
  "Convert one role-tagged message map to an Ollama chat message."
  [message opts]
  {:role    (or (name->str (:role message)) "user")
   :content (content->text (:content message) opts)})

(defn- prompt-entry->ollama
  "Convert one entry of a prompt vector to an Ollama chat message.

  A role-tagged map is treated as an explicit message. Anything else is a
  content block from the user, so it joins the surrounding blocks in a single
  user message."
  [entry opts]
  (if (contains? entry :role)
    (message->ollama entry opts)
    {:role "user" :content (content->text [entry] opts)}))

(defn prompt->messages
  "Normalize a prompt to a vector of Ollama messages.

  A prompt may be a string, one content block, a vector of content blocks, or
  a vector of role-tagged message maps. Content blocks are flattened into one
  `user` message; explicit messages produce one message each.

  Resource blocks (`resource_link` and `resource`) are rendered as text rather
  than dropped; see `clai.agent.resources`. The optional `opts` map is threaded
  to those renderers and may contain a `:cwd` for relative resource paths."
  ([prompt] (prompt->messages prompt nil))
  ([prompt opts]
   (cond
     (nil? prompt)        []
     (string? prompt)     [{:role "user" :content prompt}]
     (map? prompt)        [(prompt-entry->ollama prompt opts)]
     (sequential? prompt) (if (every? #(and (map? %) (contains? % :role)) prompt)
                            (mapv #(prompt-entry->ollama % opts) prompt)
                            [{:role "user" :content (content->text prompt opts)}])
     :else                [{:role "user" :content (str prompt)}])))

;; ---------------------------------------------------------------------------
;; Ollama history -> event updates
;; ---------------------------------------------------------------------------

(defn- history-message->updates
  "Convert one persisted history message to the event updates a viewer should
  see when a session is loaded.

  System and tool messages are internal and are not replayed. User messages
  become user prompts. Assistant messages become an `agent_thought` event
  (when the stored message carries reasoning in `:thinking`) followed by an
  `agent_message`."
  [{:keys [role content thinking]}]
  (let [role    (name->str role)
        text    (events/content->text content)
        thought (when-not (str/blank? (str thinking))
                  (events/text-event "agent_thought" (str thinking)))
        message (events/text-event "agent_message" text)]
    (case role
      "user"                [(events/text-event "user_prompt" text)]
      ("assistant" "agent") (if thought [thought message] [message])
      [])))

(defn history->updates
  "Convert persisted `history` into the event updates a viewer should render.
  Returns a vector in conversation order."
  {:malli/schema [:-> [:maybe [:sequential :map]] [:vector :map]]}
  [history]
  (into [] (mapcat history-message->updates) history))
