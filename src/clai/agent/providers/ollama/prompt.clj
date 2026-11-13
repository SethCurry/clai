(ns clai.agent.providers.ollama.prompt
  "Conversion between event-shaped prompts/history and chat messages.

  Prompts use the content vocabulary from `clai.events.schema` and may include
  resource blocks. Chat models only accept text, so this namespace flattens
  those parts into `{:role ... :content ...}` maps and turns persisted history
  back into event-shaped updates for the REPL and the web UI.

  The messages produced here are the provider-neutral stored shape (see
  `clai.agent.providers.messages`); each provider converts them into its own
  wire format when a chat turn is sent (`clai.agent.providers/chat-request`).
  The namespace keeps its `ollama` name because the shape is Ollama's."
  (:require [clai.agent.providers.messages :as messages]
            [clai.agent.resources :as resources]
            [clai.events.schema :as events]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Prompt -> stored chat messages
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
  (images, audio, etc.) are dropped, since the chat models used here take text
  in this field.

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
  "Convert one role-tagged message map to a stored chat message."
  [message opts]
  {:role    (or (name->str (:role message)) "user")
   :content (content->text (:content message) opts)})

(defn- prompt-entry->ollama
  "Convert one entry of a prompt vector to a stored chat message.

  A role-tagged map is treated as an explicit message. Anything else is a
  content block from the user, so it joins the surrounding blocks in a single
  user message."
  [entry opts]
  (if (contains? entry :role)
    (message->ollama entry opts)
    {:role "user" :content (content->text [entry] opts)}))

(defn prompt->messages
  "Normalize a prompt to a vector of stored chat messages.

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
;; Stored history -> event updates
;; ---------------------------------------------------------------------------

(defn- tool-result-texts
  "Map each tool call id in `history` to the text of the `tool`-role message
  that recorded its result."
  [history]
  (into {}
        (keep (fn [{:keys [role tool_call_id content]}]
                (when (and (= "tool" (name->str role)) (some? tool_call_id))
                  [tool_call_id (events/content->text content)])))
        history))

(defn- tool-call-updates
  "Replay the tool calls requested by assistant `message` as `tool_call`
  updates, filling in the output that `results` (a call-id -> text map) holds
  for each. A call with no recorded result stays `running`, so a conversation
  interrupted mid-tool still shows what it was doing."
  [message results]
  (mapv (fn [call]
          (let [id     (messages/tool-call-id call)
                input  (messages/tool-call-arguments call)
                output (get results id)]
            (cond-> {:type   "tool_call"
                     :id     id
                     :name   (messages/tool-call-name call)
                     :title  (messages/tool-call-title call)
                     :status "running"}
              (some? input)
              (assoc :input input)

              (some? output)
              (assoc :output output
                     :status (messages/tool-result-status output)))))
        (:tool_calls message)))

(defn- assistant-updates
  "Replay one persisted assistant `message` as the updates the agent streamed
  while producing it: its reasoning, its text, and its tool calls.

  Blank text produces no `agent_message`, so the tool-call-only turns a model
  emits do not leave empty bubbles in a reloaded conversation."
  [results message]
  (let [text    (events/content->text (:content message))
        thought (str (:thinking message))]
    (cond-> []
      (not (str/blank? thought))
      (conj (events/text-event "agent_thought" thought))

      (not (str/blank? text))
      (conj (events/text-event "agent_message" text))

      (seq (:tool_calls message))
      (into (tool-call-updates message results)))))

(defn- history-message->updates
  "Convert one persisted history message to the event updates a viewer should
  see when a session is loaded.

  User turns become `user_prompt` events and assistant turns are replayed by
  [[assistant-updates]]. System messages (the internal instructions that open
  a session) are dropped, and `tool` results are not updates of their own:
  [[tool-result-texts]] folds each one into the tool call it answers."
  [results message]
  (case (name->str (:role message))
    "user"                [(events/text-event "user_prompt"
                                              (events/content->text
                                               (:content message)))]
    ("assistant" "agent") (assistant-updates results message)
    []))

(defn history->updates
  "Convert persisted `history` into the event updates a viewer should render,
  in conversation order.

  A reloaded conversation therefore looks like it did while it streamed:
  thoughts, text, and tool calls in the order the agent produced them, with
  each tool call's output attached."
  {:malli/schema [:-> [:maybe [:sequential :map]] [:vector :map]]}
  [history]
  (let [results (tool-result-texts history)]
    (into [] (mapcat #(history-message->updates results %)) history)))
