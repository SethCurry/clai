(ns clai.acp.schemas
  (:require [clai.log]
            [malli.core :as m]
            [malli.error :as me]
            [taoensso.telemere :as tel]
            [clojure.core :as c]))


;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------


(def StopReason [:enum "end_turn" "max_tokens" "max_turn_requests" "refusal" "cancelled"])

(def non-empty-string
  "A string of at least one character."
  [:re #".+"])

(def session-id
  "ACP session identifier (opaque string)."
  non-empty-string)

(def request-id
  "A JSON-RPC id: integer, string, or nil (for notifications)."
  [:or int? string? :nil])

(def Meta [:maybe [:map {:closed false}]])

(def Annotations [:maybe [:map {:closed false}]])

(def TextContent
  [:map
    {:closed true}
    [:meta #'Meta]
    [:annotations #'Annotations]
    [:text :string]
    [:type [:= "text"]]
    ])

(def Content [:or #'TextContent])

(def UserMessageChunk
  [:map
    {:closed true}
    [:_meta #'Meta]
    [:content #'Content]
    [:sessionUpdate [:= "user_message_chunk"]]])

(def AgentMessageChunk
  [:map
    {:closed true}
    [:_meta #'Meta]
    [:content #'Content]
    [:sessionUpdate [:= "agent_message_chunk"]]])

(def AgentThoughtChunk
  [:map
    {:closed true}
    [:_meta #'Meta]
    [:content #'Content]
    [:sessionUpdate [:= "agent_thought_chunk"]]])

(def ToolKind [:enum "read"
                     "edit"
                     "delete"
                     "move"
                     "search"
                     "execute"
                     "think"
                     "fetch"
                     "switch_mode"
                     "other"])

(def ToolCallStatus [:enum "pending" "in_progress" "completed" "failed"])

(def ToolCallLocation [:map
  {:closed true}
  [:_meta #'Meta]
  [:line {:optional true} :integer]
  [:path :string]])

(def ToolCallContent
  [:map
    {:closed true}
    [:_meta #'Meta]
    [:content [:map
      {:closed true}
      [:_meta #'Meta]
      [:content #'Content]
      [:type [:= "content"]]]]
    [:diff [:map
      {:closed true}
      [:_meta #'Meta]
      [:newText :string]
      [:oldText {:optional true} :string]
      [:path :string]
      [:type [:= "diff"]]]]
    [:terminal [:map
      {:closed true}
      [:_meta #'Meta]
      [:terminalId :string]
      [:type [:= "terminal"]]]]])

(def ToolCall
  [:map
    {:closed true}
    [:_meta #'Meta]
    [:content [:vector #'ToolCallContent]]
    [:kind #'ToolKind]
    [:locations [:vector #'ToolCallLocation]]
    [:name [:maybe :string]]
    [:rawInput [:map]]
    [:rawOutput [:map]]
    [:title [:maybe :string]]
    [:toolCallId :string]
    [:status #'ToolCallStatus]])


(def ToolCallUpdate
  [:map
    {:closed true}
    [:_meta #'Meta]
    [:content [:maybe [:vector #'ToolCallContent]]]
    [:kind [:maybe #'ToolKind]]
    [:locations [:maybe [:vector #'ToolCallLocation]]]
    [:name [:maybe :string]]
    [:rawInput [:map]]
    [:rawOutput [:map]]
    [:title [:maybe :string]]
    [:toolCallId :string]
    [:status [:maybe #'ToolCallStatus]]])

(def SessionUpdate [:enum #'UserMessageChunk
                          #'AgentMessageChunk
                          #'AgentThoughtChunk
                          #'ToolCall
                          #'ToolCallUpdate])

(def json-rpc-request
  "A JSON-RPC 2.0 request object."
  [:map
   [:jsonrpc [:= "2.0"]]
   [:id request-id]
   [:method non-empty-string]
   [:params {:optional true} [:map]]])

(def json-rpc-notification
  "A JSON-RPC 2.0 notification (no id)."
  [:map
   [:jsonrpc [:= "2.0"]]
   [:method non-empty-string]
   [:params {:optional true} [:map]]])

(def json-rpc-error-object
  "The `error` object inside an error response."
  [:map
   [:code int?]
   [:message string?]
   [:data {:optional true} :any]])

(def json-rpc-response
  "A JSON-RPC 2.0 response object (success or error)."
  [:and
   [:map
    [:jsonrpc [:= "2.0"]]
    [:id request-id]]
   [:or
    [:map [:result :any]]
    [:map [:error json-rpc-error-object]]]])

(def implementation-info
  "The `:info` map advertised by an agent/client."
  [:map
   [:name non-empty-string]
   [:version non-empty-string]
   [:title {:optional true} string?]])

(def agent-capabilities
  "The `capabilities` map advertised by an agent."
  [:map])

(def auth-method
  "A single entry in `authMethods`."
  [:map
   [:methodId non-empty-string]
   [:title {:optional true} string?]
   [:description {:optional true} string?]])

(def initialize-params
  "Params for the `initialize` request."
  [:map
   [:protocolVersion pos-int?]
   [:info {:optional true} implementation-info]
   [:capabilities {:optional true} [:map]]])

(def initialize-response
  "Result of the `initialize` request."
  [:map
   [:protocolVersion pos-int?]
   [:info implementation-info]
   [:capabilities {:optional true} agent-capabilities]
   [:authMethods {:optional true} [:sequential auth-method]]])

(def session-new-params
  "Params for `session/new`."
  [:map
   [:cwd {:optional true} string?]
   [:additionalDirectories {:optional true} [:sequential string?]]
   [:mcpServers {:optional true} [:sequential [:map]]]])

(def session-new-response
  "Result of `session/new`."
  [:map
   [:sessionId session-id]])

(def session-list-params
  "Params for `session/list`."
  [:map
   [:cwd {:optional true} string?]
   [:cursor {:optional true} string?]])

(def session-list-response
  "Result of `session/list`."
  [:map
   [:sessions {:optional true} [:sequential [:map]]]
   [:nextCursor {:optional true} string?]])

(def session-id-params
  "Params for methods that only take a `:sessionId` (delete/close)."
  [:map
   [:sessionId session-id]])

(def session-resume-params
  "Params for `session/resume`."
  [:map
   [:sessionId session-id]
   [:cwd {:optional true} string?]
   [:additionalDirectories {:optional true} [:sequential string?]]
   [:mcpServers {:optional true} [:sequential [:map]]]
   [:replayFrom {:optional true} string?]])

(def session-prompt-params
  "Params for `session/prompt`."
  [:map
   [:sessionId session-id]
   [:prompt [:or string? [:sequential [:map]]]]])

(def session-prompt-response
  "Result of `session/prompt`."
  [:map
   [:messageId non-empty-string]])

(def session-cancel-params
  "Params for the `session/cancel` notification."
  [:map
   [:sessionId session-id]])

(def cancel-request-params
  "Params for the `$/cancel_request` notification."
  [:map
   [:requestId [:or int? string?]]])

(def session-update
  "One `SessionUpdate` object, discriminated by `:sessionUpdate`."
  [:map
   [:sessionUpdate non-empty-string]])

(def session-update-params
  "Params carried by a `session/update` notification."
  [:map
   [:sessionId session-id]
   [:update session-update]])

(def request-permission-opts
  "Body of `session/request_permission`."
  [:map
   [:sessionId session-id]
   [:title string?]
   [:options [:sequential [:map]]]
   [:description {:optional true} string?]
   [:subject {:optional true} [:map]]])

(def create-elicitation-opts
  "Body of `elicitation/create`."
  [:map])

(def complete-elicitation-params
  "Body of `elicitation/complete`."
  [:map
   [:elicitationId non-empty-string]])

(def acp-error-schema
  "An `AcpError` value (code/message/data)."
  [:map
   [:code int?]
   [:message string?]
   [:data {:optional true} :any]])

(defn validate!
  "Validate `value` against `schema`, returning `value` when valid or throwing
  an `ex-info` with a human-readable malli error explanation otherwise."
  [schema value what]
  (if-let [errors (and schema (m/explain schema value))]
    (let [explained (me/humanize errors)]
      (tel/log! {:level :error
                 :id    ::validation-failed
                 :data  {:what what :errors explained}}
                (str "Invalid " what))
      (throw (ex-info (str "Invalid " what ": " (pr-str explained))
                      {:schema schema :value value :errors errors})))
    value))
