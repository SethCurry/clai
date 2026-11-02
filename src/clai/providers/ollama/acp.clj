(ns clai.providers.ollama.acp
  "Conversion between ACP wire messages and Ollama chat messages.

  ACP clients send prompts as vectors of `ContentBlock`s and expect replies as
  `session/update` notifications, while `clai.providers.ollama` chats with
  `{:role ... :content ...}` maps. This namespace flattens the former into the
  latter and renders persisted history back to ACP updates when a client loads
  a session."
  (:require [acp.agent :as acp]
            [clai.log]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; ACP -> Ollama message conversion
;; ---------------------------------------------------------------------------

(defn- part->text
  "Extract a plain-text string from a single ACP content part. Non-text
  parts (images, etc.) are dropped, since Ollama chat wants text here."
  [part]
  (cond
    (string? part)            part
    (map? part)
    (cond
      (= (:type part) "text") (:text part)
      (some? (:text part))    (:text part)     ; tolerant of variants
      :else                   nil)
    :else nil))

(defn- content->text
  "Flatten an ACP `content` field (a string or a sequence of parts) to a
  single string, dropping nils and joining with newlines."
  [content]
  (cond
    (nil? content)        ""
    (string? content)     content
    (sequential? content) (->> content (map part->text) (remove nil?) (interpose "\n") (apply str))
    :else                 (str content)))

(defn- role->string
  "Normalize an ACP `role` (string or keyword) to its Ollama string form,
  defaulting to `user`."
  [role]
  (cond
    (nil? role)    "user"
    (string? role) role
    :else          (name role)))

(defn- acp-message->ollama
  "Convert one role-tagged ACP `Message` (`{:role ... :content [...]}`) to an
  Ollama chat message (`{:role ... :content \"...\"}`)."
  [{:keys [role content]}]
  {:role    (role->string role)
   :content (content->text content)})

(defn- prompt-entry->ollama
  "Convert one entry of an ACP `session/prompt` `:prompt` vector to an Ollama
  chat message.

  Per the ACP schema, `:prompt` is a vector of `ContentBlock`s (for example
  `{:type \"text\" :text \"...\"}`), all of which come from the user -- the
  blocks carry the text itself, not a `:content` field. A role-tagged message
  map is accepted too, for tolerance of simpler clients and the echo example
  in `acp.agent`."
  [entry]
  (if (contains? entry :content)
    (acp-message->ollama entry)
    {:role "user" :content (content->text [entry])}))

(defn acp-prompt->ollama-messages
  "Normalize the `:prompt` field of a `session/prompt` request to a vector of
  Ollama messages.

  ACP v2 sends `:prompt` as a vector of `ContentBlock`s, so the blocks are
  flattened into a single `user` message whose content is their concatenated
  text. A vector of role-tagged messages is also handled (one Ollama message
  per entry), as are a bare string and a single map."
  [prompt]
  (cond
    (nil? prompt)             []
    (string? prompt)          [{:role "user" :content prompt}]
    (map? prompt)             [(prompt-entry->ollama prompt)]
    (sequential? prompt)      (if (every? #(and (map? %) (contains? % :content))
                                          prompt)
                                (mapv prompt-entry->ollama prompt)
                                [{:role "user" :content (content->text prompt)}])
    :else                     [{:role "user" :content (str prompt)}]))

;; ---------------------------------------------------------------------------
;; Ollama history -> ACP session updates
;; ---------------------------------------------------------------------------

(defn- history-message->updates
  "Convert one persisted history message to the `session/update`s the client
  should see when a session is loaded. System and tool messages are internal
  and are not replayed; user messages become user chunks, and assistant
  messages become an `agent_thought_chunk` (when the stored message carries
  reasoning in `:thinking`) followed by an `agent_message_chunk`."
  [{:keys [role content thinking]}]
  (let [role (cond
               (nil? role)     nil
               (string? role)  role
               (keyword? role) (name role)
               :else           (str role))
        text (content->text content)
        thought (when-not (str/blank? (str thinking))
                  {:sessionUpdate "agent_thought_chunk"
                   :content       {:type "text" :text (str thinking)}})
        message {:sessionUpdate "agent_message_chunk"
                 :content       {:type "text" :text text}}]
    (case role
      "user"                [{:sessionUpdate "user_message_chunk"
                              :content       {:type "text" :text text}}]
      ("assistant" "agent") (if thought [thought message] [message])
      [])))

(defn replay-session-history
  "Send the persisted `history` for `session-id` to the client as a sequence
  of `session/update` notifications. Returns the number of updates sent."
  [conn session-id history]
  (let [updates (into [] (mapcat history-message->updates) history)]
    (doseq [update updates]
      (acp/send-session-update conn session-id update))
    (tel/log! {:level :debug
               :id    ::history-replayed
               :data  {:sessionId session-id
                       :messages  (count updates)}}
              "Replayed session history")
    (count updates)))
