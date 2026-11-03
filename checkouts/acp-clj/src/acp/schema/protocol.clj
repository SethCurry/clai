(ns acp.schema.protocol
  "JSON-RPC envelope schemas that carry ACP requests, responses and
  notifications between the client and the agent."
  (:require [acp.schema-runtime :as rt]
            [acp.schema.auth :as auth]
            [acp.schema.common :as common]
            [acp.schema.elicitation :as elicitation]
            [acp.schema.fs :as fs]
            [acp.schema.initialize :as initialize]
            [acp.schema.session :as session]
            [acp.schema.terminal :as terminal]
            [acp.schema.tool :as tool]))

;; The protocol defines a type named `Error`, so drop java.lang's
;; automatic class mapping before it becomes a var.
(when (class? (get (ns-map *ns*) 'Error))
  (ns-unmap *ns* 'Error))


;; Schemas refer to one another, so declare every name up front.
(declare
 AgentNotification
 AgentRequest
 AgentResponse
 CancelRequestNotification
 ClientNotification
 ClientRequest
 ClientResponse
 Error
 ErrorCode
 ExtNotification
 ExtRequest
 ExtResponse)


;; -------------------------------------------------------------------------
;; AgentNotification
;; -------------------------------------------------------------------------

(def AgentNotification
  "A JSON-RPC notification object."
  [:map
   {:closed false}
   [:method :string]
   [:params
    {:optional true}
    [:maybe
     [:or
      #'session/SessionNotification
      #'elicitation/CompleteElicitationNotification
      #'ExtNotification]]]])

(def ->AgentNotification
  "Create a `AgentNotification` value."
  (rt/make-object-constructor
    #'AgentNotification
    "AgentNotification"
    [{:key :method, :required? true, :nullable? false}
     {:key :params, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; AgentRequest
;; -------------------------------------------------------------------------

(def AgentRequest
  "A JSON-RPC request object."
  [:map
   {:closed false}
   [:id #'common/RequestId]
   [:method :string]
   [:params
    {:optional true}
    [:maybe
     [:or
      #'fs/WriteTextFileRequest
      #'fs/ReadTextFileRequest
      #'session/RequestPermissionRequest
      #'tool/CreateTerminalRequest
      #'tool/TerminalOutputRequest
      #'tool/ReleaseTerminalRequest
      #'tool/WaitForTerminalExitRequest
      #'tool/KillTerminalRequest
      #'elicitation/CreateElicitationRequest
      #'ExtRequest]]]])

(def ->AgentRequest
  "Create a `AgentRequest` value."
  (rt/make-object-constructor
    #'AgentRequest
    "AgentRequest"
    [{:key :id, :required? true, :nullable? false}
     {:key :method, :required? true, :nullable? false}
     {:key :params, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; AgentResponse
;; -------------------------------------------------------------------------

(def AgentResponse
  "A JSON-RPC response object."
  [:or
   [:map
    {:closed false}
    [:id #'common/RequestId]
    [:result
     [:or
      #'initialize/InitializeResponse
      #'auth/AuthenticateResponse
      #'auth/LogoutResponse
      #'session/NewSessionResponse
      #'session/LoadSessionResponse
      #'session/ListSessionsResponse
      #'session/DeleteSessionResponse
      #'session/ResumeSessionResponse
      #'session/CloseSessionResponse
      #'session/SetSessionModeResponse
      #'session/SetSessionConfigOptionResponse
      #'session/PromptResponse
      #'ExtResponse]]]
   [:map {:closed false} [:id #'common/RequestId] [:error #'Error]]])

(def ->AgentResponse
  "Create a `AgentResponse` value."
  (rt/make-value-constructor #'AgentResponse "AgentResponse"))


;; -------------------------------------------------------------------------
;; CancelRequestNotification
;; -------------------------------------------------------------------------

(def CancelRequestNotification
  "Notification to cancel an ongoing request. See protocol docs: [Cancellation](https://agentclientprotocol.com/protocol/v1/cancellation)"
  [:map
   {:closed false}
   [:requestId #'common/RequestId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->CancelRequestNotification
  "Create a `CancelRequestNotification` value."
  (rt/make-object-constructor
    #'CancelRequestNotification
    "CancelRequestNotification"
    [{:key :requestId, :required? true, :nullable? false}
     {:key :_meta, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; ClientNotification
;; -------------------------------------------------------------------------

(def ClientNotification
  "A JSON-RPC notification object."
  [:map
   {:closed false}
   [:method :string]
   [:params
    {:optional true}
    [:maybe [:or #'session/CancelNotification #'ExtNotification]]]])

(def ->ClientNotification
  "Create a `ClientNotification` value."
  (rt/make-object-constructor
    #'ClientNotification
    "ClientNotification"
    [{:key :method, :required? true, :nullable? false}
     {:key :params, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; ClientRequest
;; -------------------------------------------------------------------------

(def ClientRequest
  "A JSON-RPC request object."
  [:map
   {:closed false}
   [:id #'common/RequestId]
   [:method :string]
   [:params
    {:optional true}
    [:maybe
     [:or
      #'initialize/InitializeRequest
      #'auth/AuthenticateRequest
      #'auth/LogoutRequest
      #'session/NewSessionRequest
      #'session/LoadSessionRequest
      #'session/ListSessionsRequest
      #'session/DeleteSessionRequest
      #'session/ResumeSessionRequest
      #'session/CloseSessionRequest
      #'session/SetSessionModeRequest
      #'session/SetSessionConfigOptionRequest
      #'session/PromptRequest
      #'ExtRequest]]]])

(def ->ClientRequest
  "Create a `ClientRequest` value."
  (rt/make-object-constructor
    #'ClientRequest
    "ClientRequest"
    [{:key :id, :required? true, :nullable? false}
     {:key :method, :required? true, :nullable? false}
     {:key :params, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; ClientResponse
;; -------------------------------------------------------------------------

(def ClientResponse
  "A JSON-RPC response object."
  [:or
   [:map
    {:closed false}
    [:id #'common/RequestId]
    [:result
     [:or
      #'fs/WriteTextFileResponse
      #'fs/ReadTextFileResponse
      #'session/RequestPermissionResponse
      #'tool/CreateTerminalResponse
      #'terminal/TerminalOutputResponse
      #'tool/ReleaseTerminalResponse
      #'tool/WaitForTerminalExitResponse
      #'tool/KillTerminalResponse
      #'elicitation/CreateElicitationResponse
      #'ExtResponse]]]
   [:map {:closed false} [:id #'common/RequestId] [:error #'Error]]])

(def ->ClientResponse
  "Create a `ClientResponse` value."
  (rt/make-value-constructor #'ClientResponse "ClientResponse"))


;; -------------------------------------------------------------------------
;; Error
;; -------------------------------------------------------------------------

(def Error
  "JSON-RPC error object. Represents an error that occurred during method execution, following the JSON-RPC 2.0 error object specification with optional additional data. See protocol docs: [JSON-RPC Error Object](https://www.jsonrpc.org/specification#error_object)"
  [:map
   {:closed false}
   [:code #'ErrorCode]
   [:message :string]
   [:data {:optional true} :any]])

(def ->Error
  "Create a `Error` value."
  (rt/make-object-constructor
    #'Error
    "Error"
    [{:key :code, :required? true, :nullable? false}
     {:key :message, :required? true, :nullable? false}
     {:key :data, :required? false, :nullable? false}]
    false))


;; -------------------------------------------------------------------------
;; ErrorCode
;; -------------------------------------------------------------------------

(def ErrorCode
  "Predefined error codes for common JSON-RPC and ACP-specific errors. Standard errors use the JSON-RPC 2.0 codes. ACP-specific errors use the JSON-RPC server-error range (-32000 to -32099), and request cancellation uses -32800 from the range LSP reserves (-32899 to -32800)."
  [:or
   [:= -32700]
   [:= -32600]
   [:= -32601]
   [:= -32602]
   [:= -32603]
   [:= -32800]
   [:= -32000]
   [:= -32002]
   :int])

(def ->ErrorCode
  "Create a `ErrorCode` value."
  (rt/make-value-constructor #'ErrorCode "ErrorCode"))


;; -------------------------------------------------------------------------
;; ExtNotification
;; -------------------------------------------------------------------------

(def ExtNotification
  "Allows either side to send an arbitrary notification that is not part of the ACP spec. Extension notifications provide a way to send one-way messages for custom functionality while maintaining protocol compatibility. See protocol docs: [Extensibility](https://agentclientprotocol.com/protocol/v1/extensibility)"
  :any)

(def ->ExtNotification
  "Create a `ExtNotification` value."
  (rt/make-value-constructor #'ExtNotification "ExtNotification"))


;; -------------------------------------------------------------------------
;; ExtRequest
;; -------------------------------------------------------------------------

(def ExtRequest
  "Allows for sending an arbitrary request that is not part of the ACP spec. Extension methods provide a way to add custom functionality while maintaining protocol compatibility. See protocol docs: [Extensibility](https://agentclientprotocol.com/protocol/v1/extensibility)"
  :any)

(def ->ExtRequest
  "Create a `ExtRequest` value."
  (rt/make-value-constructor #'ExtRequest "ExtRequest"))


;; -------------------------------------------------------------------------
;; ExtResponse
;; -------------------------------------------------------------------------

(def ExtResponse
  "Allows for sending an arbitrary response to an extension request that is not part of the ACP spec. Extension methods provide a way to add custom functionality while maintaining protocol compatibility. See protocol docs: [Extensibility](https://agentclientprotocol.com/protocol/v1/extensibility)"
  :any)

(def ->ExtResponse
  "Create a `ExtResponse` value."
  (rt/make-value-constructor #'ExtResponse "ExtResponse"))
