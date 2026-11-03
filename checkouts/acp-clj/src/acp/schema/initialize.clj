(ns acp.schema.initialize
  "Initialization handshake schemas: the protocol version, implementation
  metadata and the capability sets that both sides advertise."
  (:require [acp.schema-runtime :as rt]
            [acp.schema.auth :as auth]
            [acp.schema.elicitation :as elicitation]
            [acp.schema.fs :as fs]
            [acp.schema.mcp :as mcp]
            [acp.schema.session :as session]))


;; Schemas refer to one another, so declare every name up front.
(declare
 AgentCapabilities
 ClientCapabilities
 Implementation
 InitializeRequest
 InitializeResponse
 PromptCapabilities
 ProtocolVersion)


;; -------------------------------------------------------------------------
;; AgentCapabilities
;; -------------------------------------------------------------------------

(def AgentCapabilities
  "Capabilities supported by the agent. Advertised during initialization to inform the client about available features and content types. See protocol docs: [Agent Capabilities](https://agentclientprotocol.com/protocol/v1/initialization#agent-capabilities)"
  [:map
   {:closed false}
   [:loadSession {:optional true} :boolean]
   [:promptCapabilities {:optional true} #'PromptCapabilities]
   [:mcpCapabilities {:optional true} #'mcp/McpCapabilities]
   [:sessionCapabilities {:optional true} #'session/SessionCapabilities]
   [:auth {:optional true} #'auth/AgentAuthCapabilities]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->AgentCapabilities
  "Create a `AgentCapabilities` value."
  (rt/make-object-constructor
    #'AgentCapabilities
    "AgentCapabilities"
    [{:key :loadSession, :required? false, :nullable? false}
     {:key :promptCapabilities, :required? false, :nullable? false}
     {:key :mcpCapabilities, :required? false, :nullable? false}
     {:key :sessionCapabilities, :required? false, :nullable? false}
     {:key :auth, :required? false, :nullable? false}
     {:key :_meta, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; ClientCapabilities
;; -------------------------------------------------------------------------

(def ClientCapabilities
  "Capabilities supported by the client. Advertised during initialization to inform the agent about available features and methods. See protocol docs: [Client Capabilities](https://agentclientprotocol.com/protocol/v1/initialization#client-capabilities)"
  [:map
   {:closed false}
   [:fs {:optional true} #'fs/FileSystemCapabilities]
   [:terminal {:optional true} :boolean]
   [:session {:optional true} [:maybe #'session/ClientSessionCapabilities]]
   [:auth {:optional true} #'auth/AuthCapabilities]
   [:elicitation {:optional true} [:maybe #'elicitation/ElicitationCapabilities]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->ClientCapabilities
  "Create a `ClientCapabilities` value."
  (rt/make-object-constructor
    #'ClientCapabilities
    "ClientCapabilities"
    [{:key :fs, :required? false, :nullable? false}
     {:key :terminal, :required? false, :nullable? false}
     {:key :session, :required? false, :nullable? true}
     {:key :auth, :required? false, :nullable? false}
     {:key :elicitation, :required? false, :nullable? true}
     {:key :_meta, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; Implementation
;; -------------------------------------------------------------------------

(def Implementation
  "Metadata about the implementation of the client or agent. Describes the name and version of an ACP implementation, with an optional title for UI representation."
  [:map
   {:closed false}
   [:name :string]
   [:title {:optional true} [:maybe :string]]
   [:version :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->Implementation
  "Create a `Implementation` value."
  (rt/make-object-constructor
    #'Implementation
    "Implementation"
    [{:key :name, :required? true, :nullable? false}
     {:key :version, :required? true, :nullable? false}
     {:key :title, :required? false, :nullable? true}
     {:key :_meta, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; InitializeRequest
;; -------------------------------------------------------------------------

(def InitializeRequest
  "Request parameters for the initialize method. Sent by the client to establish connection and negotiate capabilities. See protocol docs: [Initialization](https://agentclientprotocol.com/protocol/v1/initialization)"
  [:map
   {:closed false}
   [:protocolVersion #'ProtocolVersion]
   [:clientCapabilities {:optional true} #'ClientCapabilities]
   [:clientInfo {:optional true} [:maybe #'Implementation]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->InitializeRequest
  "Create a `InitializeRequest` value."
  (rt/make-object-constructor
    #'InitializeRequest
    "InitializeRequest"
    [{:key :protocolVersion, :required? true, :nullable? false}
     {:key :clientCapabilities, :required? false, :nullable? false}
     {:key :clientInfo, :required? false, :nullable? true}
     {:key :_meta, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; InitializeResponse
;; -------------------------------------------------------------------------

(def InitializeResponse
  "Response to the `initialize` method. Contains the negotiated protocol version and agent capabilities. See protocol docs: [Initialization](https://agentclientprotocol.com/protocol/v1/initialization)"
  [:map
   {:closed false}
   [:protocolVersion #'ProtocolVersion]
   [:agentCapabilities {:optional true} #'AgentCapabilities]
   [:authMethods {:optional true} [:vector #'auth/AuthMethod]]
   [:agentInfo {:optional true} [:maybe #'Implementation]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->InitializeResponse
  "Create a `InitializeResponse` value."
  (rt/make-object-constructor
    #'InitializeResponse
    "InitializeResponse"
    [{:key :protocolVersion, :required? true, :nullable? false}
     {:key :agentCapabilities, :required? false, :nullable? false}
     {:key :authMethods, :required? false, :nullable? false}
     {:key :agentInfo, :required? false, :nullable? true}
     {:key :_meta, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; PromptCapabilities
;; -------------------------------------------------------------------------

(def PromptCapabilities
  "Prompt capabilities supported by the agent in `session/prompt` requests. Baseline agent functionality requires support for `text` and `resource_link` content blocks in prompt requests. Other variants must be explicitly opted in to. Capabilities for different types of content in prompt requests. Indicates which content types beyond the baseline (text and resource links) the agent can process. See protocol docs: [Prompt Capabilities](https://agentclientprotocol.com/protocol/v1/initialization#prompt-capabilities)"
  [:map
   {:closed false}
   [:image {:optional true} :boolean]
   [:audio {:optional true} :boolean]
   [:embeddedContext {:optional true} :boolean]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->PromptCapabilities
  "Create a `PromptCapabilities` value."
  (rt/make-object-constructor
    #'PromptCapabilities
    "PromptCapabilities"
    [{:key :image, :required? false, :nullable? false}
     {:key :audio, :required? false, :nullable? false}
     {:key :embeddedContext, :required? false, :nullable? false}
     {:key :_meta, :required? false, :nullable? true}]
    false))


;; -------------------------------------------------------------------------
;; ProtocolVersion
;; -------------------------------------------------------------------------

(def ProtocolVersion
  "Protocol version identifier. This version is only bumped for breaking changes. Non-breaking changes should be introduced via capabilities."
  [:int {:min 0, :max 65535}])

(def ->ProtocolVersion
  "Create a `ProtocolVersion` value."
  (rt/make-value-constructor #'ProtocolVersion "ProtocolVersion"))
