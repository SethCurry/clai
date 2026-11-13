(ns clai.agent.providers.ollama.messages
  "Helpers for normalizing Ollama chat responses and tool calls."
  (:require [clai.agent.providers.ollama.schemas :as schemas]
            [clai.events.schema :as events]
            [clojure.string :as str])
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
  "Extract the function name from an Ollama `tool_calls` entry.

  Canonical `clai.events.schema/ToolCallContent` values have no function name,
  so this returns nil for them."
  {:malli/schema [:-> #'schemas/tool-call [:maybe string?]]}
  [call]
  (or (get-in call [:function :name]) (:name call)))

(defn tool-call-arguments
  "Extract the arguments map from an Ollama `tool_calls` entry, falling back to
  the canonical `ToolCallContent` `:input` field."
  {:malli/schema [:-> #'schemas/tool-call :any]}
  [call]
  (or (get-in call [:function :arguments]) (:arguments call) (:input call)))

(defn tool-call-title
  "Render a short human-readable title for a tool call.

  Handles both raw Ollama `tool_calls` entries and canonical event
  `ToolCallContent` maps, using `:input` as the arguments for the latter."
  {:malli/schema [:-> #'schemas/tool-call string?]}
  [call]
  (str (or (tool-call-name call) "?") " " (pr-str (tool-call-arguments call))))

(defn tool-result-status
  "The terminal `clai.events.schema/ToolCallStatus` a tool call's recorded
  `result` implies: `failed` when the result is an error message, `completed`
  otherwise."
  {:malli/schema [:-> :any #'events/ToolCallStatus]}
  [result]
  (if (str/starts-with? (str result) "Error:") "failed" "completed"))
