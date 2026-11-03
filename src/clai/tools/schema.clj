(ns clai.tools.schema
  "Malli schemas shared by the `clai` tool registries and their built-in
  tools.

  A tool is a map with three keys:

    :id      the tool name (matches the Ollama `function.name`).
    :handler a Clojure function `(args ctx) -> string`, where `args` is the
             parsed JSON arguments map and `ctx` carries session context
             (`:cwd`, ...). Returning a string keeps the tool-result message
             simple; non-string return values are pr-str'd.
    :spec    the Ollama tool definition map:
             `{:type \"function\" :function {:name ... :description ...
              :parameters {:type \"object\" :properties {...} :required [...]}}}`"
  (:require [clai.util :as util]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  util/non-empty-string)

(def tool-id
  "The id (name) of a tool; matches the Ollama `function.name`."
  non-empty-string)

(def tool-parameters
  "The JSON Schema `:parameters` object of an Ollama tool definition."
  [:map
   [:type [:= "object"]]
   [:properties {:optional true} [:map-of :string [:map]]]
   [:required {:optional true} [:sequential :string]]])

(def tool-spec
  "An Ollama tool definition, ready to send as an entry of a `:tools` vector:
  `{:type \"function\" :function {:name ... :description ... :parameters ...}}`."
  [:map
   [:type [:= "function"]]
   [:function
    [:map
     [:name tool-id]
     [:description {:optional true} :string]
     [:parameters {:optional true} tool-parameters]]]])

(def tool-args
  "The parsed JSON arguments map handed to a tool function."
  [:map])

(def tool-ctx
  "Session context handed to a tool function. Carries at least `:cwd`; the
  agent loop also supplies the live session's client, model, tool registry,
  and limits so tools such as `spawn_agent` can start their own conversations.

  `:on-output` is an optional `(fn [output] ...)` a long-running tool may call
  with the output it has produced so far; the agent loop relays each call to
  the client as a `tool_call_update`. It is absent when nothing is listening
  (a sub-agent's child tools), so tools must treat it as optional."
  [:map
   [:cwd {:optional true} [:maybe :string]]
   [:session-id {:optional true} [:maybe :string]]
   [:client {:optional true} :any]
   [:model {:optional true} [:maybe :string]]
   [:tool-registry {:optional true} :any]
   [:chat-opts {:optional true} [:map]]
   [:max-rounds {:optional true} [:maybe pos-int?]]
   [:agent-depth {:optional true} [:maybe :int]]
   [:on-output {:optional true} [:maybe fn?]]])

(def tool-result
  "The string result of a tool function (fed back to the model as the content
  of the `tool`-role message)."
  :string)

(def Tool
  "A tool map as stored in a registry: `{:id ... :handler ... :spec ...}`."
  [:map
   [:id tool-id]
   [:handler [:=> [:cat tool-args tool-ctx] [:maybe :any]]]
   [:spec tool-spec]])

(def ToolRegistry
  "A per-agent tool registry: a map of tool id to tool, as returned by
  `make-registry` and `built-in-registry`."
  [:map-of tool-id Tool])

(def tool-call
  "One entry of an Ollama `tool_calls` vector. Both the nested
  `{:function {:name ... :arguments ...}}` and flattened `{:name ...}` shapes
  are accepted, so this schema is intentionally open."
  [:map])

(def tool-call-message
  "A `tool`-role message appended to chat history after a tool call."
  [:map
   [:role [:= "tool"]]
   [:content tool-result]
   [:name string?]
   [:tool_call_id {:optional true} [:maybe string?]]])
