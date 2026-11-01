(ns clai.tools.schema
  "Malli schemas shared by the `clai` tool registry and its built-in tools.

  A tool is a map with three keys:

    :id    the tool name (matches the Ollama `function.name`).
    :fn    a Clojure function `(args ctx) -> string`, where `args` is the
           parsed JSON arguments map and `ctx` carries session context
           (`:cwd`, ...). Returning a string keeps the tool-result message
           simple; non-string return values are pr-str'd.
    :spec  the Ollama tool definition map:
           `{:type \"function\" :function {:name ... :description ...
            :parameters {:type \"object\" :properties {...} :required [...]}}}`")

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  [:re #".+"])

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
  "Session context handed to a tool function. Carries at least `:cwd`."
  [:map
   [:cwd {:optional true} [:maybe :string]]])

(def tool-result
  "The string result of a tool function (fed back to the model as the content
  of the `tool`-role message)."
  :string)

(def tool
  "A tool map as stored in the registry: `{:id ... :fn ... :spec ...}`."
  [:map
   [:id tool-id]
   [:fn [:=> [:cat tool-args tool-ctx] [:maybe :any]]]
   [:spec tool-spec]])

(def tool-registry
  "The global registry, as returned by `registered-tools`."
  [:map-of tool-id tool])

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
