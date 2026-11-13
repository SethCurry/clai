(ns clai.agent.providers.messages
  "Provider-neutral helpers for assistant messages and tool calls.

  Every provider client normalizes its response into the same assistant-message
  shape before the agent sees it:

      {:role       \"assistant\"
       :content    \"...\"                     ; text, possibly empty
       :thinking   \"...\"                     ; optional model reasoning
       :tool_calls [{:id \"call-1\"
                     :function {:name \"read_file\"
                                :arguments {:path \"README.md\"}}}]}

  That is Ollama's own wire shape, and it is what `clai.agent.core` records in
  session history and what `clai.agent.tools.invoke` understands. OpenRouter's
  OpenAI-style `tool_calls` (JSON-string arguments, `:type \"function\"`) are
  converted into it by `clai.agent.providers.openrouter/assistant-message`, so
  the helpers here work on any provider's messages."
  (:require [clai.events.schema :as events]
            [clojure.string :as str])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def NonEmptyString
  "A string of at least one character."
  [:and :string [:fn #(not (str/blank? %))]])

(def ChatContent
  "Message content: a plain string or the event log's content list."
  [:or :string #'events/ContentList])

(def ToolCall
  "One entry of an assistant message's `:tool_calls` vector. Both the nested
  `{:function {:name ... :arguments ...}}` and flattened `{:name ...}` shapes
  are accepted, so this schema is intentionally open."
  [:map])

(def AssistantMessage
  "The normalized assistant message every provider client returns."
  [:map
   [:role :string]
   [:content {:optional true} ChatContent]
   [:thinking {:optional true} [:maybe :string]]
   [:images {:optional true} [:sequential :string]]
   [:tool_calls {:optional true} [:sequential ToolCall]]])

(def ChatResponse
  "The parts of a provider chat response these helpers inspect (the Ollama
  shape; OpenRouter's is converted by its own namespace)."
  [:map
   [:message {:optional true} [:map]]])

;; ---------------------------------------------------------------------------
;; Assistant messages and tool calls
;; ---------------------------------------------------------------------------

(defn assistant-message
  "Return the assistant `:message` map from a chat response, normalized to at
  least a map with role `assistant` and empty content."
  {:malli/schema [:-> [:maybe ChatResponse] AssistantMessage]}
  [resp]
  (-> resp :message (or {:role "assistant" :content ""})))

(defn has-tool-calls?
  "True when an assistant message requests one or more tool calls."
  {:malli/schema [:-> [:maybe :map] boolean?]}
  [msg]
  (boolean (and (map? msg) (seq (:tool_calls msg)))))

(defn tool-call-id
  "Return the tool call's id, generating one when the provider omitted it."
  {:malli/schema [:-> ToolCall NonEmptyString]}
  [call]
  (or (:id call) (str (UUID/randomUUID))))

(defn tool-call-name
  "Extract the function name from a `tool_calls` entry.

  Canonical `clai.events.schema/ToolCallContent` values have no function name,
  so this returns nil for them."
  {:malli/schema [:-> ToolCall [:maybe :string]]}
  [call]
  (or (get-in call [:function :name]) (:name call)))

(defn tool-call-arguments
  "Extract the arguments map from a `tool_calls` entry, falling back to the
  canonical `ToolCallContent` `:input` field."
  {:malli/schema [:-> ToolCall :any]}
  [call]
  (or (get-in call [:function :arguments]) (:arguments call) (:input call)))

(defn tool-call-title
  "Render a short human-readable title for a tool call.

  Handles both raw `tool_calls` entries and canonical event
  `ToolCallContent` maps, using `:input` as the arguments for the latter."
  {:malli/schema [:-> ToolCall :string]}
  [call]
  (str (or (tool-call-name call) "?") " " (pr-str (tool-call-arguments call))))

(defn tool-result-status
  "The terminal `clai.events.schema/ToolCallStatus` a tool call's recorded
  `result` implies: `failed` when the result is an error message, `completed`
  otherwise."
  {:malli/schema [:-> :any #'events/ToolCallStatus]}
  [result]
  (if (str/starts-with? (str result) "Error:") "failed" "completed"))
