(ns clai.providers.ollama.messages
  "Helpers for normalizing Ollama chat responses and tool calls."
  (:require [clai.providers.ollama.schemas :as schemas])
  (:import [java.util UUID]))

(defn assistant-message
  "Return the assistant `:message` map from an Ollama `/api/chat` response,
  normalized to at least a map with role `assistant` and empty content."
  {:malli/schema [:-> [:maybe #'schemas/chat-response]
                   #'schemas/assistant-message-schema]}
  [resp]
  (-> resp :message (or {:role "assistant" :content ""})))

(defn has-tool-calls?
  "True when an Ollama assistant message requests one or more tool calls."
  {:malli/schema [:-> [:maybe [:map]] boolean?]}
  [msg]
  (boolean (and (map? msg) (seq (:tool_calls msg)))))

(defn tool-call-id
  "Return the Ollama tool call's id, generating one when absent."
  {:malli/schema [:-> #'schemas/tool-call schemas/non-empty-string]}
  [call]
  (or (:id call) (str (UUID/randomUUID))))

(defn tool-call-name
  "Extract the function name from an Ollama `tool_calls` entry."
  {:malli/schema [:-> #'schemas/tool-call [:maybe string?]]}
  [call]
  (let [f (or (:function call) call)]
    (:name f)))

(defn tool-call-title
  "Render a short human-readable title for an Ollama tool call."
  {:malli/schema [:-> #'schemas/tool-call string?]}
  [call]
  (str (or (tool-call-name call) "?")
       " " (pr-str (:arguments (or (:function call) call)))))
