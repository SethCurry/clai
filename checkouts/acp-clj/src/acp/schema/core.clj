;; -------------------------------------------------------------------------
;; ACP protocol schemas for namespace acp.schemas.
;;
;; Generated from acp/schema.json by `acp.schema-tools`:
;;
;;     lein run -m acp.schema-tools <path> [namespace]
;;
;; This is ordinary Clojure source, so it can be compiled, read and
;; commented.  Regenerating rewrites every form below this header; keep
;; notes that must survive in a separate file.
;;
;; Every protocol type now lives in one of the topical `acp.schema.*`
;; namespaces (content, common, terminal, tool, mcp, fs, auth,
;; elicitation, initialize, session, protocol) and is re-exported here as a
;; plain `def` alias, so `acp.schemas` stays the single import for callers.
;; The aliases below are maintained by hand.
;; -------------------------------------------------------------------------

(ns acp.schema.core
  "Malli schemas for the Agent Client Protocol.

  Every type is defined in an `acp.schema.*` namespace and re-exported
  here, so callers can keep importing a single namespace."
  (:require [acp.schema.content :as content]
            [acp.schema.common :as common]
            [acp.schema.terminal :as terminal]
            [acp.schema.tool :as tool]
            [acp.schema.mcp :as mcp]
            [acp.schema.fs :as fs]
            [acp.schema.auth :as auth]
            [acp.schema.elicitation :as elicitation]
            [acp.schema.initialize :as initialize]
            [acp.schema.session :as session]
            [acp.schema.protocol :as protocol]))

;; The protocol defines a type named `Error`, so drop java.lang's
;; automatic class mapping before it becomes a var.
(when (class? (get (ns-map *ns*) 'Error))
  (ns-unmap *ns* 'Error))


;; Re-exported from `acp.schema.content`.

(def Annotations content/Annotations)
(def ->Annotations content/->Annotations)

(def AudioContent content/AudioContent)
(def ->AudioContent content/->AudioContent)

(def BlobResourceContents content/BlobResourceContents)
(def ->BlobResourceContents content/->BlobResourceContents)

(def Content content/Content)
(def ->Content content/->Content)

(def ContentBlock content/ContentBlock)
(def ->ContentBlock content/->ContentBlock)

(def Diff content/Diff)
(def ->Diff content/->Diff)

(def EmbeddedResource content/EmbeddedResource)
(def ->EmbeddedResource content/->EmbeddedResource)

(def EmbeddedResourceResource content/EmbeddedResourceResource)
(def ->EmbeddedResourceResource content/->EmbeddedResourceResource)

(def ImageContent content/ImageContent)
(def ->ImageContent content/->ImageContent)

(def ResourceLink content/ResourceLink)
(def ->ResourceLink content/->ResourceLink)

(def Role content/Role)
(def ->Role content/->Role)

(def TextContent content/TextContent)
(def ->TextContent content/->TextContent)

(def TextResourceContents content/TextResourceContents)
(def ->TextResourceContents content/->TextResourceContents)


;; Re-exported from `acp.schema.common`.

(def RequestId common/RequestId)
(def ->RequestId common/->RequestId)

(def SessionId common/SessionId)
(def ->SessionId common/->SessionId)


;; Re-exported from `acp.schema.terminal`.

(def Terminal terminal/Terminal)
(def ->Terminal terminal/->Terminal)

(def TerminalExitStatus terminal/TerminalExitStatus)
(def ->TerminalExitStatus terminal/->TerminalExitStatus)

(def TerminalId terminal/TerminalId)
(def ->TerminalId terminal/->TerminalId)

(def TerminalOutputResponse terminal/TerminalOutputResponse)
(def ->TerminalOutputResponse terminal/->TerminalOutputResponse)


;; Re-exported from `acp.schema.tool`.

(def CreateTerminalRequest tool/CreateTerminalRequest)
(def ->CreateTerminalRequest tool/->CreateTerminalRequest)

(def CreateTerminalResponse tool/CreateTerminalResponse)
(def ->CreateTerminalResponse tool/->CreateTerminalResponse)

(def KillTerminalRequest tool/KillTerminalRequest)
(def ->KillTerminalRequest tool/->KillTerminalRequest)

(def KillTerminalResponse tool/KillTerminalResponse)
(def ->KillTerminalResponse tool/->KillTerminalResponse)

(def ReleaseTerminalRequest tool/ReleaseTerminalRequest)
(def ->ReleaseTerminalRequest tool/->ReleaseTerminalRequest)

(def ReleaseTerminalResponse tool/ReleaseTerminalResponse)
(def ->ReleaseTerminalResponse tool/->ReleaseTerminalResponse)

(def TerminalOutputRequest tool/TerminalOutputRequest)
(def ->TerminalOutputRequest tool/->TerminalOutputRequest)

(def ToolCall tool/ToolCall)
(def ->ToolCall tool/->ToolCall)

(def ToolCallContent tool/ToolCallContent)
(def ->ToolCallContent tool/->ToolCallContent)

(def ToolCallId tool/ToolCallId)
(def ->ToolCallId tool/->ToolCallId)

(def ToolCallLocation tool/ToolCallLocation)
(def ->ToolCallLocation tool/->ToolCallLocation)

(def ToolCallStatus tool/ToolCallStatus)
(def ->ToolCallStatus tool/->ToolCallStatus)

(def ToolCallUpdate tool/ToolCallUpdate)
(def ->ToolCallUpdate tool/->ToolCallUpdate)

(def ToolKind tool/ToolKind)
(def ->ToolKind tool/->ToolKind)

(def WaitForTerminalExitRequest tool/WaitForTerminalExitRequest)
(def ->WaitForTerminalExitRequest tool/->WaitForTerminalExitRequest)

(def WaitForTerminalExitResponse tool/WaitForTerminalExitResponse)
(def ->WaitForTerminalExitResponse tool/->WaitForTerminalExitResponse)


;; Re-exported from `acp.schema.mcp`.

(def EnvVariable mcp/EnvVariable)
(def ->EnvVariable mcp/->EnvVariable)

(def HttpHeader mcp/HttpHeader)
(def ->HttpHeader mcp/->HttpHeader)

(def McpCapabilities mcp/McpCapabilities)
(def ->McpCapabilities mcp/->McpCapabilities)

(def McpServer mcp/McpServer)
(def ->McpServer mcp/->McpServer)

(def McpServerHttp mcp/McpServerHttp)
(def ->McpServerHttp mcp/->McpServerHttp)

(def McpServerSse mcp/McpServerSse)
(def ->McpServerSse mcp/->McpServerSse)

(def McpServerStdio mcp/McpServerStdio)
(def ->McpServerStdio mcp/->McpServerStdio)


;; Re-exported from `acp.schema.fs`.

(def FileSystemCapabilities fs/FileSystemCapabilities)
(def ->FileSystemCapabilities fs/->FileSystemCapabilities)

(def ReadTextFileRequest fs/ReadTextFileRequest)
(def ->ReadTextFileRequest fs/->ReadTextFileRequest)

(def ReadTextFileResponse fs/ReadTextFileResponse)
(def ->ReadTextFileResponse fs/->ReadTextFileResponse)

(def WriteTextFileRequest fs/WriteTextFileRequest)
(def ->WriteTextFileRequest fs/->WriteTextFileRequest)

(def WriteTextFileResponse fs/WriteTextFileResponse)
(def ->WriteTextFileResponse fs/->WriteTextFileResponse)


;; Re-exported from `acp.schema.auth`.

(def AgentAuthCapabilities auth/AgentAuthCapabilities)
(def ->AgentAuthCapabilities auth/->AgentAuthCapabilities)

(def AuthCapabilities auth/AuthCapabilities)
(def ->AuthCapabilities auth/->AuthCapabilities)

(def AuthMethod auth/AuthMethod)
(def ->AuthMethod auth/->AuthMethod)

(def AuthMethodAgent auth/AuthMethodAgent)
(def ->AuthMethodAgent auth/->AuthMethodAgent)

(def AuthMethodId auth/AuthMethodId)
(def ->AuthMethodId auth/->AuthMethodId)

(def AuthMethodTerminal auth/AuthMethodTerminal)
(def ->AuthMethodTerminal auth/->AuthMethodTerminal)

(def AuthenticateRequest auth/AuthenticateRequest)
(def ->AuthenticateRequest auth/->AuthenticateRequest)

(def AuthenticateResponse auth/AuthenticateResponse)
(def ->AuthenticateResponse auth/->AuthenticateResponse)

(def LogoutCapabilities auth/LogoutCapabilities)
(def ->LogoutCapabilities auth/->LogoutCapabilities)

(def LogoutRequest auth/LogoutRequest)
(def ->LogoutRequest auth/->LogoutRequest)

(def LogoutResponse auth/LogoutResponse)
(def ->LogoutResponse auth/->LogoutResponse)


;; Re-exported from `acp.schema.elicitation`.

(def BooleanPropertySchema elicitation/BooleanPropertySchema)
(def ->BooleanPropertySchema elicitation/->BooleanPropertySchema)

(def CompleteElicitationNotification elicitation/CompleteElicitationNotification)
(def ->CompleteElicitationNotification elicitation/->CompleteElicitationNotification)

(def CreateElicitationRequest elicitation/CreateElicitationRequest)
(def ->CreateElicitationRequest elicitation/->CreateElicitationRequest)

(def CreateElicitationResponse elicitation/CreateElicitationResponse)
(def ->CreateElicitationResponse elicitation/->CreateElicitationResponse)

(def ElicitationAcceptAction elicitation/ElicitationAcceptAction)
(def ->ElicitationAcceptAction elicitation/->ElicitationAcceptAction)

(def ElicitationCapabilities elicitation/ElicitationCapabilities)
(def ->ElicitationCapabilities elicitation/->ElicitationCapabilities)

(def ElicitationContentValue elicitation/ElicitationContentValue)
(def ->ElicitationContentValue elicitation/->ElicitationContentValue)

(def ElicitationFormCapabilities elicitation/ElicitationFormCapabilities)
(def ->ElicitationFormCapabilities elicitation/->ElicitationFormCapabilities)

(def ElicitationFormMode elicitation/ElicitationFormMode)
(def ->ElicitationFormMode elicitation/->ElicitationFormMode)

(def ElicitationId elicitation/ElicitationId)
(def ->ElicitationId elicitation/->ElicitationId)

(def ElicitationPropertySchema elicitation/ElicitationPropertySchema)
(def ->ElicitationPropertySchema elicitation/->ElicitationPropertySchema)

(def ElicitationRequestScope elicitation/ElicitationRequestScope)
(def ->ElicitationRequestScope elicitation/->ElicitationRequestScope)

(def ElicitationSchema elicitation/ElicitationSchema)
(def ->ElicitationSchema elicitation/->ElicitationSchema)

(def ElicitationSchemaType elicitation/ElicitationSchemaType)
(def ->ElicitationSchemaType elicitation/->ElicitationSchemaType)

(def ElicitationSessionScope elicitation/ElicitationSessionScope)
(def ->ElicitationSessionScope elicitation/->ElicitationSessionScope)

(def ElicitationUrlCapabilities elicitation/ElicitationUrlCapabilities)
(def ->ElicitationUrlCapabilities elicitation/->ElicitationUrlCapabilities)

(def ElicitationUrlMode elicitation/ElicitationUrlMode)
(def ->ElicitationUrlMode elicitation/->ElicitationUrlMode)

(def EnumOption elicitation/EnumOption)
(def ->EnumOption elicitation/->EnumOption)

(def IntegerPropertySchema elicitation/IntegerPropertySchema)
(def ->IntegerPropertySchema elicitation/->IntegerPropertySchema)

(def MultiSelectItems elicitation/MultiSelectItems)
(def ->MultiSelectItems elicitation/->MultiSelectItems)

(def MultiSelectPropertySchema elicitation/MultiSelectPropertySchema)
(def ->MultiSelectPropertySchema elicitation/->MultiSelectPropertySchema)

(def NumberPropertySchema elicitation/NumberPropertySchema)
(def ->NumberPropertySchema elicitation/->NumberPropertySchema)

(def StringFormat elicitation/StringFormat)
(def ->StringFormat elicitation/->StringFormat)

(def StringMultiSelectItems elicitation/StringMultiSelectItems)
(def ->StringMultiSelectItems elicitation/->StringMultiSelectItems)

(def StringPropertySchema elicitation/StringPropertySchema)
(def ->StringPropertySchema elicitation/->StringPropertySchema)

(def TitledMultiSelectItems elicitation/TitledMultiSelectItems)
(def ->TitledMultiSelectItems elicitation/->TitledMultiSelectItems)


;; Re-exported from `acp.schema.initialize`.

(def AgentCapabilities initialize/AgentCapabilities)
(def ->AgentCapabilities initialize/->AgentCapabilities)

(def ClientCapabilities initialize/ClientCapabilities)
(def ->ClientCapabilities initialize/->ClientCapabilities)

(def Implementation initialize/Implementation)
(def ->Implementation initialize/->Implementation)

(def InitializeRequest initialize/InitializeRequest)
(def ->InitializeRequest initialize/->InitializeRequest)

(def InitializeResponse initialize/InitializeResponse)
(def ->InitializeResponse initialize/->InitializeResponse)

(def PromptCapabilities initialize/PromptCapabilities)
(def ->PromptCapabilities initialize/->PromptCapabilities)

(def ProtocolVersion initialize/ProtocolVersion)
(def ->ProtocolVersion initialize/->ProtocolVersion)


;; Re-exported from `acp.schema.session`.

(def AvailableCommand session/AvailableCommand)
(def ->AvailableCommand session/->AvailableCommand)

(def AvailableCommandInput session/AvailableCommandInput)
(def ->AvailableCommandInput session/->AvailableCommandInput)

(def AvailableCommandsUpdate session/AvailableCommandsUpdate)
(def ->AvailableCommandsUpdate session/->AvailableCommandsUpdate)

(def BooleanConfigOptionCapabilities session/BooleanConfigOptionCapabilities)
(def ->BooleanConfigOptionCapabilities session/->BooleanConfigOptionCapabilities)

(def CancelNotification session/CancelNotification)
(def ->CancelNotification session/->CancelNotification)

(def ClientSessionCapabilities session/ClientSessionCapabilities)
(def ->ClientSessionCapabilities session/->ClientSessionCapabilities)

(def CloseSessionRequest session/CloseSessionRequest)
(def ->CloseSessionRequest session/->CloseSessionRequest)

(def CloseSessionResponse session/CloseSessionResponse)
(def ->CloseSessionResponse session/->CloseSessionResponse)

(def CompactionCapabilities session/CompactionCapabilities)
(def ->CompactionCapabilities session/->CompactionCapabilities)

(def CompactionId session/CompactionId)
(def ->CompactionId session/->CompactionId)

(def CompactionStatus session/CompactionStatus)
(def ->CompactionStatus session/->CompactionStatus)

(def CompactionSummaryChunk session/CompactionSummaryChunk)
(def ->CompactionSummaryChunk session/->CompactionSummaryChunk)

(def CompactionUpdate session/CompactionUpdate)
(def ->CompactionUpdate session/->CompactionUpdate)

(def ConfigOptionUpdate session/ConfigOptionUpdate)
(def ->ConfigOptionUpdate session/->ConfigOptionUpdate)

(def ContentChunk session/ContentChunk)
(def ->ContentChunk session/->ContentChunk)

(def Cost session/Cost)
(def ->Cost session/->Cost)

(def CurrentModeUpdate session/CurrentModeUpdate)
(def ->CurrentModeUpdate session/->CurrentModeUpdate)

(def DeleteSessionRequest session/DeleteSessionRequest)
(def ->DeleteSessionRequest session/->DeleteSessionRequest)

(def DeleteSessionResponse session/DeleteSessionResponse)
(def ->DeleteSessionResponse session/->DeleteSessionResponse)

(def ListSessionsRequest session/ListSessionsRequest)
(def ->ListSessionsRequest session/->ListSessionsRequest)

(def ListSessionsResponse session/ListSessionsResponse)
(def ->ListSessionsResponse session/->ListSessionsResponse)

(def LoadSessionRequest session/LoadSessionRequest)
(def ->LoadSessionRequest session/->LoadSessionRequest)

(def LoadSessionResponse session/LoadSessionResponse)
(def ->LoadSessionResponse session/->LoadSessionResponse)

(def MessageId session/MessageId)
(def ->MessageId session/->MessageId)

(def NewSessionRequest session/NewSessionRequest)
(def ->NewSessionRequest session/->NewSessionRequest)

(def NewSessionResponse session/NewSessionResponse)
(def ->NewSessionResponse session/->NewSessionResponse)

(def Notice session/Notice)
(def ->Notice session/->Notice)

(def NoticeCapabilities session/NoticeCapabilities)
(def ->NoticeCapabilities session/->NoticeCapabilities)

(def NoticeSeverity session/NoticeSeverity)
(def ->NoticeSeverity session/->NoticeSeverity)

(def PermissionOption session/PermissionOption)
(def ->PermissionOption session/->PermissionOption)

(def PermissionOptionId session/PermissionOptionId)
(def ->PermissionOptionId session/->PermissionOptionId)

(def PermissionOptionKind session/PermissionOptionKind)
(def ->PermissionOptionKind session/->PermissionOptionKind)

(def Plan session/Plan)
(def ->Plan session/->Plan)

(def PlanEntry session/PlanEntry)
(def ->PlanEntry session/->PlanEntry)

(def PlanEntryPriority session/PlanEntryPriority)
(def ->PlanEntryPriority session/->PlanEntryPriority)

(def PlanEntryStatus session/PlanEntryStatus)
(def ->PlanEntryStatus session/->PlanEntryStatus)

(def PromptRequest session/PromptRequest)
(def ->PromptRequest session/->PromptRequest)

(def PromptResponse session/PromptResponse)
(def ->PromptResponse session/->PromptResponse)

(def RequestPermissionOutcome session/RequestPermissionOutcome)
(def ->RequestPermissionOutcome session/->RequestPermissionOutcome)

(def RequestPermissionRequest session/RequestPermissionRequest)
(def ->RequestPermissionRequest session/->RequestPermissionRequest)

(def RequestPermissionResponse session/RequestPermissionResponse)
(def ->RequestPermissionResponse session/->RequestPermissionResponse)

(def ResumeSessionRequest session/ResumeSessionRequest)
(def ->ResumeSessionRequest session/->ResumeSessionRequest)

(def ResumeSessionResponse session/ResumeSessionResponse)
(def ->ResumeSessionResponse session/->ResumeSessionResponse)

(def SelectedPermissionOutcome session/SelectedPermissionOutcome)
(def ->SelectedPermissionOutcome session/->SelectedPermissionOutcome)

(def SessionAdditionalDirectoriesCapabilities session/SessionAdditionalDirectoriesCapabilities)
(def ->SessionAdditionalDirectoriesCapabilities session/->SessionAdditionalDirectoriesCapabilities)

(def SessionCapabilities session/SessionCapabilities)
(def ->SessionCapabilities session/->SessionCapabilities)

(def SessionCloseCapabilities session/SessionCloseCapabilities)
(def ->SessionCloseCapabilities session/->SessionCloseCapabilities)

(def SessionConfigBoolean session/SessionConfigBoolean)
(def ->SessionConfigBoolean session/->SessionConfigBoolean)

(def SessionConfigGroupId session/SessionConfigGroupId)
(def ->SessionConfigGroupId session/->SessionConfigGroupId)

(def SessionConfigId session/SessionConfigId)
(def ->SessionConfigId session/->SessionConfigId)

(def SessionConfigOption session/SessionConfigOption)
(def ->SessionConfigOption session/->SessionConfigOption)

(def SessionConfigOptionCategory session/SessionConfigOptionCategory)
(def ->SessionConfigOptionCategory session/->SessionConfigOptionCategory)

(def SessionConfigOptionsCapabilities session/SessionConfigOptionsCapabilities)
(def ->SessionConfigOptionsCapabilities session/->SessionConfigOptionsCapabilities)

(def SessionConfigSelect session/SessionConfigSelect)
(def ->SessionConfigSelect session/->SessionConfigSelect)

(def SessionConfigSelectGroup session/SessionConfigSelectGroup)
(def ->SessionConfigSelectGroup session/->SessionConfigSelectGroup)

(def SessionConfigSelectOption session/SessionConfigSelectOption)
(def ->SessionConfigSelectOption session/->SessionConfigSelectOption)

(def SessionConfigSelectOptions session/SessionConfigSelectOptions)
(def ->SessionConfigSelectOptions session/->SessionConfigSelectOptions)

(def SessionConfigValueId session/SessionConfigValueId)
(def ->SessionConfigValueId session/->SessionConfigValueId)

(def SessionDeleteCapabilities session/SessionDeleteCapabilities)
(def ->SessionDeleteCapabilities session/->SessionDeleteCapabilities)

(def SessionInfo session/SessionInfo)
(def ->SessionInfo session/->SessionInfo)

(def SessionInfoUpdate session/SessionInfoUpdate)
(def ->SessionInfoUpdate session/->SessionInfoUpdate)

(def SessionListCapabilities session/SessionListCapabilities)
(def ->SessionListCapabilities session/->SessionListCapabilities)

(def SessionMode session/SessionMode)
(def ->SessionMode session/->SessionMode)

(def SessionModeId session/SessionModeId)
(def ->SessionModeId session/->SessionModeId)

(def SessionModeState session/SessionModeState)
(def ->SessionModeState session/->SessionModeState)

(def SessionNotification session/SessionNotification)
(def ->SessionNotification session/->SessionNotification)

(def SessionResumeCapabilities session/SessionResumeCapabilities)
(def ->SessionResumeCapabilities session/->SessionResumeCapabilities)

(def SessionUpdate session/SessionUpdate)
(def ->SessionUpdate session/->SessionUpdate)

(def SetSessionConfigOptionRequest session/SetSessionConfigOptionRequest)
(def ->SetSessionConfigOptionRequest session/->SetSessionConfigOptionRequest)

(def SetSessionConfigOptionResponse session/SetSessionConfigOptionResponse)
(def ->SetSessionConfigOptionResponse session/->SetSessionConfigOptionResponse)

(def SetSessionModeRequest session/SetSessionModeRequest)
(def ->SetSessionModeRequest session/->SetSessionModeRequest)

(def SetSessionModeResponse session/SetSessionModeResponse)
(def ->SetSessionModeResponse session/->SetSessionModeResponse)

(def StopReason session/StopReason)
(def ->StopReason session/->StopReason)

(def UnstructuredCommandInput session/UnstructuredCommandInput)
(def ->UnstructuredCommandInput session/->UnstructuredCommandInput)

(def UsageUpdate session/UsageUpdate)
(def ->UsageUpdate session/->UsageUpdate)


;; Re-exported from `acp.schema.protocol`.

(def AgentNotification protocol/AgentNotification)
(def ->AgentNotification protocol/->AgentNotification)

(def AgentRequest protocol/AgentRequest)
(def ->AgentRequest protocol/->AgentRequest)

(def AgentResponse protocol/AgentResponse)
(def ->AgentResponse protocol/->AgentResponse)

(def CancelRequestNotification protocol/CancelRequestNotification)
(def ->CancelRequestNotification protocol/->CancelRequestNotification)

(def ClientNotification protocol/ClientNotification)
(def ->ClientNotification protocol/->ClientNotification)

(def ClientRequest protocol/ClientRequest)
(def ->ClientRequest protocol/->ClientRequest)

(def ClientResponse protocol/ClientResponse)
(def ->ClientResponse protocol/->ClientResponse)

(def Error protocol/Error)
(def ->Error protocol/->Error)

(def ErrorCode protocol/ErrorCode)
(def ->ErrorCode protocol/->ErrorCode)

(def ExtNotification protocol/ExtNotification)
(def ->ExtNotification protocol/->ExtNotification)

(def ExtRequest protocol/ExtRequest)
(def ->ExtRequest protocol/->ExtRequest)

(def ExtResponse protocol/ExtResponse)
(def ->ExtResponse protocol/->ExtResponse)
