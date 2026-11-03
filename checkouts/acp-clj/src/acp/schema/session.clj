(ns acp.schema.session
  "Session lifecycle, prompt turn, and session update schemas.

  Schemas that describe a conversation session: `session/new` through
  `session/close`, session modes and config options, the prompt turn and
  its stop reasons, and the `session/update` notification variants
  (message and thought chunks, plans, available commands, usage, notices,
  compaction, ...).

  Constructors follow the `acp.schema.tool` convention: required fields
  are positional arguments, optional fields are keyword arguments (`_meta`
  is passed as `:meta`) and default to `nil` in the returned map. A
  keyword argument that the protocol does not allow to be `nil` is only
  added to the map when it is supplied."
  (:require [acp.schema-runtime :as rt]
            [acp.schema.common :as common]
            [acp.schema.content :as content]
            [acp.schema.mcp :as mcp]
            [acp.schema.tool :as tool]))


;; Schemas refer to one another, so declare every name up front.
(declare
 AvailableCommand
 AvailableCommandInput
 AvailableCommandsUpdate
 BooleanConfigOptionCapabilities
 CancelNotification
 ClientSessionCapabilities
 CloseSessionRequest
 CloseSessionResponse
 CompactionCapabilities
 CompactionId
 CompactionStatus
 CompactionSummaryChunk
 CompactionUpdate
 ConfigOptionUpdate
 ContentChunk
 Cost
 CurrentModeUpdate
 DeleteSessionRequest
 DeleteSessionResponse
 ListSessionsRequest
 ListSessionsResponse
 LoadSessionRequest
 LoadSessionResponse
 MessageId
 NewSessionRequest
 NewSessionResponse
 Notice
 NoticeCapabilities
 NoticeSeverity
 PermissionOption
 PermissionOptionId
 PermissionOptionKind
 Plan
 PlanEntry
 PlanEntryPriority
 PlanEntryStatus
 PromptRequest
 PromptResponse
 RequestPermissionOutcome
 RequestPermissionRequest
 RequestPermissionResponse
 ResumeSessionRequest
 ResumeSessionResponse
 SelectedPermissionOutcome
 SessionAdditionalDirectoriesCapabilities
 SessionCapabilities
 SessionCloseCapabilities
 SessionConfigBoolean
 SessionConfigGroupId
 SessionConfigId
 SessionConfigOption
 SessionConfigOptionCategory
 SessionConfigOptionsCapabilities
 SessionConfigSelect
 SessionConfigSelectGroup
 SessionConfigSelectOption
 SessionConfigSelectOptions
 SessionConfigValueId
 SessionDeleteCapabilities
 SessionInfo
 SessionInfoUpdate
 SessionListCapabilities
 SessionMode
 SessionModeId
 SessionModeState
 SessionNotification
 SessionResumeCapabilities
 SessionUpdate
 SetSessionConfigOptionRequest
 SetSessionConfigOptionResponse
 SetSessionModeRequest
 SetSessionModeResponse
 StopReason
 UnstructuredCommandInput
 UsageUpdate)


;; -------------------------------------------------------------------------
;; SessionId
;; -------------------------------------------------------------------------
;; Sessions and terminals both use session identifiers, so the schema lives
;; in `acp.schema.common` to keep the feature namespaces acyclic.  It is
;; re-exported here for callers that expect it alongside the session types.

(def SessionId common/SessionId)
(def ->SessionId common/->SessionId)


;; -------------------------------------------------------------------------
;; SessionCapabilities
;; -------------------------------------------------------------------------

(def SessionCapabilities
  "Session capabilities supported by the agent. As a baseline, all Agents **MUST** support `session/new`, `session/prompt`, `session/cancel`, and `session/update`. Optionally, they **MAY** support other session methods and notifications by specifying additional capabilities. Note: `session/load` is still handled by the top-level `loadSession` capability. This will be unified in future versions of the protocol. See protocol docs: [Session Capabilities](https://agentclientprotocol.com/protocol/v1/initialization#session-capabilities)"
  [:map
   {:closed false}
   [:list {:optional true} [:maybe #'SessionListCapabilities]]
   [:delete {:optional true} [:maybe #'SessionDeleteCapabilities]]
   [:additionalDirectories
    {:optional true}
    [:maybe #'SessionAdditionalDirectoriesCapabilities]]
   [:resume {:optional true} [:maybe #'SessionResumeCapabilities]]
   [:close {:optional true} [:maybe #'SessionCloseCapabilities]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionCapabilities
  "Create a `SessionCapabilities` value."
  [& {:keys [list delete additionalDirectories resume close meta]}]
  (let [value {:list                  list
               :delete                delete
               :additionalDirectories additionalDirectories
               :resume                resume
               :close                 close
               :_meta                 meta}]
    (rt/validate! #'SessionCapabilities value "SessionCapabilities")))


;; -------------------------------------------------------------------------
;; SessionListCapabilities
;; -------------------------------------------------------------------------

(def SessionListCapabilities
  "Capabilities for the `session/list` method. Supplying `{}` means the agent supports listing sessions."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionListCapabilities
  "Create a `SessionListCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'SessionListCapabilities value "SessionListCapabilities")))


;; -------------------------------------------------------------------------
;; SessionDeleteCapabilities
;; -------------------------------------------------------------------------

(def SessionDeleteCapabilities
  "Capabilities for the `session/delete` method. Supplying `{}` means the agent supports deleting sessions from `session/list`."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionDeleteCapabilities
  "Create a `SessionDeleteCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate!
      #'SessionDeleteCapabilities value "SessionDeleteCapabilities")))


;; -------------------------------------------------------------------------
;; SessionAdditionalDirectoriesCapabilities
;; -------------------------------------------------------------------------

(def SessionAdditionalDirectoriesCapabilities
  "Capabilities for additional session directories support. Supplying `{}` means the agent supports the `additionalDirectories` field on supported session lifecycle requests. Agents that also support `session/list` may return `SessionInfo.additionalDirectories` to report the complete ordered additional-root list associated with a listed session."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionAdditionalDirectoriesCapabilities
  "Create a `SessionAdditionalDirectoriesCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate!
      #'SessionAdditionalDirectoriesCapabilities value "SessionAdditionalDirectoriesCapabilities")))


;; -------------------------------------------------------------------------
;; SessionResumeCapabilities
;; -------------------------------------------------------------------------

(def SessionResumeCapabilities
  "Capabilities for the `session/resume` method. Supplying `{}` means the agent supports resuming sessions."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionResumeCapabilities
  "Create a `SessionResumeCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate!
      #'SessionResumeCapabilities value "SessionResumeCapabilities")))


;; -------------------------------------------------------------------------
;; SessionCloseCapabilities
;; -------------------------------------------------------------------------

(def SessionCloseCapabilities
  "Capabilities for the `session/close` method. Supplying `{}` means the agent supports closing sessions."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionCloseCapabilities
  "Create a `SessionCloseCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate!
      #'SessionCloseCapabilities value "SessionCloseCapabilities")))


;; -------------------------------------------------------------------------
;; ClientSessionCapabilities
;; -------------------------------------------------------------------------

(def ClientSessionCapabilities
  "Session-related capabilities supported by the client."
  [:map
   {:closed false}
   [:compaction {:optional true} [:maybe #'CompactionCapabilities]]
   [:configOptions
    {:optional true}
    [:maybe #'SessionConfigOptionsCapabilities]]
   [:notices {:optional true} [:maybe #'NoticeCapabilities]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ClientSessionCapabilities
  "Create a `ClientSessionCapabilities` value."
  [& {:keys [compaction configOptions notices meta]}]
  (let [value {:compaction    compaction
               :configOptions configOptions
               :notices       notices
               :_meta         meta}]
    (rt/validate!
      #'ClientSessionCapabilities value "ClientSessionCapabilities")))


;; -------------------------------------------------------------------------
;; SessionConfigOptionsCapabilities
;; -------------------------------------------------------------------------

(def SessionConfigOptionsCapabilities
  "Session configuration option capabilities supported by the client."
  [:map
   {:closed false}
   [:boolean {:optional true} [:maybe #'BooleanConfigOptionCapabilities]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionConfigOptionsCapabilities
  "Create a `SessionConfigOptionsCapabilities` value."
  [& {:keys [boolean meta]}]
  (let [value {:boolean boolean
               :_meta   meta}]
    (rt/validate!
      #'SessionConfigOptionsCapabilities value "SessionConfigOptionsCapabilities")))


;; -------------------------------------------------------------------------
;; BooleanConfigOptionCapabilities
;; -------------------------------------------------------------------------

(def BooleanConfigOptionCapabilities
  "Capabilities for boolean session configuration options. Supplying `{}` means the client supports boolean session configuration options."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->BooleanConfigOptionCapabilities
  "Create a `BooleanConfigOptionCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate!
      #'BooleanConfigOptionCapabilities value "BooleanConfigOptionCapabilities")))


;; -------------------------------------------------------------------------
;; CompactionCapabilities
;; -------------------------------------------------------------------------

(def CompactionCapabilities
  "Client support for ID-addressed context compaction updates."
  [:map {:closed false}])

(defn ->CompactionCapabilities
  "Create a `CompactionCapabilities` value."
  []
  (let [value {}]
    (rt/validate! #'CompactionCapabilities value "CompactionCapabilities")))


;; -------------------------------------------------------------------------
;; NoticeCapabilities
;; -------------------------------------------------------------------------

(def NoticeCapabilities
  "Client support for presenting live notices to the user."
  [:map {:closed false}])

(defn ->NoticeCapabilities
  "Create a `NoticeCapabilities` value."
  []
  (let [value {}]
    (rt/validate! #'NoticeCapabilities value "NoticeCapabilities")))


;; -------------------------------------------------------------------------
;; NewSessionRequest
;; -------------------------------------------------------------------------

(def NewSessionRequest
  "Request parameters for creating a new session. See protocol docs: [Creating a Session](https://agentclientprotocol.com/protocol/v1/session-setup#creating-a-session)"
  [:map
   {:closed false}
   [:cwd :string]
   [:additionalDirectories {:optional true} [:vector :string]]
   [:mcpServers [:vector #'mcp/McpServer]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->NewSessionRequest
  "Create a `NewSessionRequest` value."
  [cwd mcpServers & {:keys [additionalDirectories meta]}]
  (let [value (cond-> {:cwd        cwd
                       :mcpServers mcpServers
                       :_meta      meta}
                (some? additionalDirectories)
                (assoc :additionalDirectories additionalDirectories))]
    (rt/validate! #'NewSessionRequest value "NewSessionRequest")))


;; -------------------------------------------------------------------------
;; NewSessionResponse
;; -------------------------------------------------------------------------

(def NewSessionResponse
  "Response from creating a new session. See protocol docs: [Creating a Session](https://agentclientprotocol.com/protocol/v1/session-setup#creating-a-session)"
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:modes {:optional true} [:maybe #'SessionModeState]]
   [:configOptions
    {:optional true}
    [:maybe [:vector #'SessionConfigOption]]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->NewSessionResponse
  "Create a `NewSessionResponse` value."
  [sessionId & {:keys [modes configOptions meta]}]
  (let [value {:sessionId     sessionId
               :modes         modes
               :configOptions configOptions
               :_meta         meta}]
    (rt/validate! #'NewSessionResponse value "NewSessionResponse")))


;; -------------------------------------------------------------------------
;; LoadSessionRequest
;; -------------------------------------------------------------------------

(def LoadSessionRequest
  "Request parameters for loading an existing session. Only available if the Agent supports the `loadSession` capability. See protocol docs: [Loading Sessions](https://agentclientprotocol.com/protocol/v1/session-setup#loading-sessions)"
  [:map
   {:closed false}
   [:mcpServers [:vector #'mcp/McpServer]]
   [:cwd :string]
   [:additionalDirectories {:optional true} [:vector :string]]
   [:sessionId #'SessionId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->LoadSessionRequest
  "Create a `LoadSessionRequest` value."
  [mcpServers cwd sessionId & {:keys [additionalDirectories meta]}]
  (let [value (cond-> {:mcpServers mcpServers
                       :cwd        cwd
                       :sessionId  sessionId
                       :_meta      meta}
                (some? additionalDirectories)
                (assoc :additionalDirectories additionalDirectories))]
    (rt/validate! #'LoadSessionRequest value "LoadSessionRequest")))


;; -------------------------------------------------------------------------
;; LoadSessionResponse
;; -------------------------------------------------------------------------

(def LoadSessionResponse
  "Response from loading an existing session."
  [:map
   {:closed false}
   [:modes {:optional true} [:maybe #'SessionModeState]]
   [:configOptions
    {:optional true}
    [:maybe [:vector #'SessionConfigOption]]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->LoadSessionResponse
  "Create a `LoadSessionResponse` value."
  [& {:keys [modes configOptions meta]}]
  (let [value {:modes         modes
               :configOptions configOptions
               :_meta         meta}]
    (rt/validate! #'LoadSessionResponse value "LoadSessionResponse")))


;; -------------------------------------------------------------------------
;; ResumeSessionRequest
;; -------------------------------------------------------------------------

(def ResumeSessionRequest
  "Request parameters for resuming an existing session. Resumes an existing session without returning previous messages (unlike `session/load`). This is useful for agents that can resume sessions but don't implement full session loading. Only available if the Agent supports the `sessionCapabilities.resume` capability."
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:cwd :string]
   [:additionalDirectories {:optional true} [:vector :string]]
   [:mcpServers {:optional true} [:vector #'mcp/McpServer]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ResumeSessionRequest
  "Create a `ResumeSessionRequest` value."
  [sessionId cwd & {:keys [additionalDirectories mcpServers meta]}]
  (let [value (cond-> {:sessionId sessionId
                       :cwd       cwd
                       :_meta     meta}
                (some? additionalDirectories)
                (assoc :additionalDirectories additionalDirectories)
                (some? mcpServers) (assoc :mcpServers mcpServers))]
    (rt/validate! #'ResumeSessionRequest value "ResumeSessionRequest")))


;; -------------------------------------------------------------------------
;; ResumeSessionResponse
;; -------------------------------------------------------------------------

(def ResumeSessionResponse
  "Response from resuming an existing session."
  [:map
   {:closed false}
   [:modes {:optional true} [:maybe #'SessionModeState]]
   [:configOptions
    {:optional true}
    [:maybe [:vector #'SessionConfigOption]]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ResumeSessionResponse
  "Create a `ResumeSessionResponse` value."
  [& {:keys [modes configOptions meta]}]
  (let [value {:modes         modes
               :configOptions configOptions
               :_meta         meta}]
    (rt/validate! #'ResumeSessionResponse value "ResumeSessionResponse")))


;; -------------------------------------------------------------------------
;; ListSessionsRequest
;; -------------------------------------------------------------------------

(def ListSessionsRequest
  "Request parameters for listing existing sessions. Only available if the Agent supports the `sessionCapabilities.list` capability."
  [:map
   {:closed false}
   [:cwd {:optional true} [:maybe :string]]
   [:cursor {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ListSessionsRequest
  "Create a `ListSessionsRequest` value."
  [& {:keys [cwd cursor meta]}]
  (let [value {:cwd    cwd
               :cursor cursor
               :_meta  meta}]
    (rt/validate! #'ListSessionsRequest value "ListSessionsRequest")))


;; -------------------------------------------------------------------------
;; ListSessionsResponse
;; -------------------------------------------------------------------------

(def ListSessionsResponse
  "Response from listing sessions."
  [:map
   {:closed false}
   [:sessions [:vector #'SessionInfo]]
   [:nextCursor {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ListSessionsResponse
  "Create a `ListSessionsResponse` value."
  [sessions & {:keys [nextCursor meta]}]
  (let [value {:sessions   sessions
               :nextCursor nextCursor
               :_meta      meta}]
    (rt/validate! #'ListSessionsResponse value "ListSessionsResponse")))


;; -------------------------------------------------------------------------
;; SessionInfo
;; -------------------------------------------------------------------------

(def SessionInfo
  "Information about a session returned by session/list"
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:cwd :string]
   [:additionalDirectories {:optional true} [:vector :string]]
   [:title {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionInfo
  "Create a `SessionInfo` value."
  [sessionId cwd & {:keys [additionalDirectories title updatedAt meta]}]
  (let [value (cond-> {:sessionId sessionId
                       :cwd       cwd
                       :title     title
                       :updatedAt updatedAt
                       :_meta     meta}
                (some? additionalDirectories)
                (assoc :additionalDirectories additionalDirectories))]
    (rt/validate! #'SessionInfo value "SessionInfo")))


;; -------------------------------------------------------------------------
;; SessionInfoUpdate
;; -------------------------------------------------------------------------

(def SessionInfoUpdate
  "Update to session metadata. All fields are optional to support partial updates. Agents send this notification to update session information like title or custom metadata. This allows clients to display dynamic session names and track session state changes."
  [:map
   {:closed false}
   [:title {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionInfoUpdate
  "Create a `SessionInfoUpdate` value."
  [& {:keys [title updatedAt meta]}]
  (let [value {:title     title
               :updatedAt updatedAt
               :_meta     meta}]
    (rt/validate! #'SessionInfoUpdate value "SessionInfoUpdate")))


;; -------------------------------------------------------------------------
;; DeleteSessionRequest
;; -------------------------------------------------------------------------

(def DeleteSessionRequest
  "Request parameters for deleting an existing session from `session/list`. Only available if the Agent supports the `sessionCapabilities.delete` capability."
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->DeleteSessionRequest
  "Create a `DeleteSessionRequest` value."
  [sessionId & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :_meta     meta}]
    (rt/validate! #'DeleteSessionRequest value "DeleteSessionRequest")))


;; -------------------------------------------------------------------------
;; DeleteSessionResponse
;; -------------------------------------------------------------------------

(def DeleteSessionResponse
  "Response from deleting a session."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->DeleteSessionResponse
  "Create a `DeleteSessionResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'DeleteSessionResponse value "DeleteSessionResponse")))


;; -------------------------------------------------------------------------
;; CloseSessionRequest
;; -------------------------------------------------------------------------

(def CloseSessionRequest
  "Request parameters for closing an active session. If supported, the agent **must** cancel any ongoing work related to the session (treat it as if `session/cancel` was called) and then free up any resources associated with the session. Only available if the Agent supports the `sessionCapabilities.close` capability."
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CloseSessionRequest
  "Create a `CloseSessionRequest` value."
  [sessionId & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :_meta     meta}]
    (rt/validate! #'CloseSessionRequest value "CloseSessionRequest")))


;; -------------------------------------------------------------------------
;; CloseSessionResponse
;; -------------------------------------------------------------------------

(def CloseSessionResponse
  "Response from closing a session."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CloseSessionResponse
  "Create a `CloseSessionResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'CloseSessionResponse value "CloseSessionResponse")))


;; -------------------------------------------------------------------------
;; ContentChunk
;; -------------------------------------------------------------------------

(def ContentChunk
  "A streamed item of content"
  [:map
   {:closed false}
   [:content #'content/ContentBlock]
   [:messageId {:optional true} [:maybe #'MessageId]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ContentChunk
  "Create a `ContentChunk` value."
  [content & {:keys [messageId meta]}]
  (let [value {:content   content
               :messageId messageId
               :_meta     meta}]
    (rt/validate! #'ContentChunk value "ContentChunk")))


;; -------------------------------------------------------------------------
;; MessageId
;; -------------------------------------------------------------------------

(def MessageId
  "Unique identifier for a message within a session."
  :string)

(defn ->MessageId
  "Create a `MessageId` value."
  [messageId]
  (rt/validate! #'MessageId messageId "MessageId"))


;; -------------------------------------------------------------------------
;; SessionUpdate
;; -------------------------------------------------------------------------

(def SessionUpdate
  "Different types of updates that can be sent during session processing. These updates provide real-time feedback about the agent's progress. See protocol docs: [Agent Reports Output](https://agentclientprotocol.com/protocol/v1/prompt-turn#3-agent-reports-output)"
  [:or
   [:and
    [:map {:closed false} [:sessionUpdate [:= "user_message_chunk"]]]
    #'ContentChunk]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "agent_message_chunk"]]]
    #'ContentChunk]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "agent_thought_chunk"]]]
    #'ContentChunk]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "tool_call"]]]
    #'tool/ToolCall]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "tool_call_update"]]]
    #'tool/ToolCallUpdate]
   [:and [:map {:closed false} [:sessionUpdate [:= "plan"]]] #'Plan]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "available_commands_update"]]]
    #'AvailableCommandsUpdate]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "current_mode_update"]]]
    #'CurrentModeUpdate]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "config_option_update"]]]
    #'ConfigOptionUpdate]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "session_info_update"]]]
    #'SessionInfoUpdate]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "usage_update"]]]
    #'UsageUpdate]
   [:and [:map {:closed false} [:sessionUpdate [:= "notice"]]] #'Notice]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "compaction_update"]]]
    #'CompactionUpdate]
   [:and
    [:map {:closed false} [:sessionUpdate [:= "compaction_summary_chunk"]]]
    #'CompactionSummaryChunk]])

(defn ->SessionUpdate
  "Create a `SessionUpdate` value."
  [update]
  (rt/validate! #'SessionUpdate update "SessionUpdate"))


;; -------------------------------------------------------------------------
;; SessionNotification
;; -------------------------------------------------------------------------

(def SessionNotification
  "Notification containing a session update from the agent. Used to stream real-time progress and results during prompt processing. See protocol docs: [Agent Reports Output](https://agentclientprotocol.com/protocol/v1/prompt-turn#3-agent-reports-output)"
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:update #'SessionUpdate]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionNotification
  "Create a `SessionNotification` value."
  [sessionId update & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :update    update
               :_meta     meta}]
    (rt/validate! #'SessionNotification value "SessionNotification")))


;; -------------------------------------------------------------------------
;; StopReason
;; -------------------------------------------------------------------------

(def StopReason
  "Reasons why an agent stops processing a prompt turn. See protocol docs: [Stop Reasons](https://agentclientprotocol.com/protocol/v1/prompt-turn#stop-reasons)"
  [:enum "end_turn" "max_tokens" "max_turn_requests" "refusal" "cancelled"])

(defn ->StopReason
  "Create a `StopReason` value."
  [stopReason]
  (rt/validate! #'StopReason stopReason "StopReason"))


;; -------------------------------------------------------------------------
;; PromptRequest
;; -------------------------------------------------------------------------

(def PromptRequest
  "Request parameters for sending a user prompt to the agent. Contains the user's message and any additional context. See protocol docs: [User Message](https://agentclientprotocol.com/protocol/v1/prompt-turn#1-user-message)"
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:prompt [:vector #'content/ContentBlock]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->PromptRequest
  "Create a `PromptRequest` value."
  [sessionId prompt & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :prompt    prompt
               :_meta     meta}]
    (rt/validate! #'PromptRequest value "PromptRequest")))


;; -------------------------------------------------------------------------
;; PromptResponse
;; -------------------------------------------------------------------------

(def PromptResponse
  "Response from processing a user prompt. See protocol docs: [Check for Completion](https://agentclientprotocol.com/protocol/v1/prompt-turn#4-check-for-completion)"
  [:map
   {:closed false}
   [:stopReason #'StopReason]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->PromptResponse
  "Create a `PromptResponse` value."
  [stopReason & {:keys [meta]}]
  (let [value {:stopReason stopReason
               :_meta      meta}]
    (rt/validate! #'PromptResponse value "PromptResponse")))


;; -------------------------------------------------------------------------
;; SessionModeId
;; -------------------------------------------------------------------------

(def SessionModeId
  "Unique identifier for a Session Mode."
  :string)

(defn ->SessionModeId
  "Create a `SessionModeId` value."
  [modeId]
  (rt/validate! #'SessionModeId modeId "SessionModeId"))


;; -------------------------------------------------------------------------
;; SessionMode
;; -------------------------------------------------------------------------

(def SessionMode
  "A mode the agent can operate in. See protocol docs: [Session Modes](https://agentclientprotocol.com/protocol/v1/session-modes)"
  [:map
   {:closed false}
   [:id #'SessionModeId]
   [:name :string]
   [:description {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionMode
  "Create a `SessionMode` value."
  [id name & {:keys [description meta]}]
  (let [value {:id          id
               :name        name
               :description description
               :_meta       meta}]
    (rt/validate! #'SessionMode value "SessionMode")))


;; -------------------------------------------------------------------------
;; SessionModeState
;; -------------------------------------------------------------------------

(def SessionModeState
  "The set of modes and the one currently active."
  [:map
   {:closed false}
   [:currentModeId #'SessionModeId]
   [:availableModes [:vector #'SessionMode]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionModeState
  "Create a `SessionModeState` value."
  [currentModeId availableModes & {:keys [meta]}]
  (let [value {:currentModeId  currentModeId
               :availableModes availableModes
               :_meta          meta}]
    (rt/validate! #'SessionModeState value "SessionModeState")))


;; -------------------------------------------------------------------------
;; SetSessionModeRequest
;; -------------------------------------------------------------------------

(def SetSessionModeRequest
  "Request parameters for setting a session mode."
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:modeId #'SessionModeId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SetSessionModeRequest
  "Create a `SetSessionModeRequest` value."
  [sessionId modeId & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :modeId    modeId
               :_meta     meta}]
    (rt/validate! #'SetSessionModeRequest value "SetSessionModeRequest")))


;; -------------------------------------------------------------------------
;; SetSessionModeResponse
;; -------------------------------------------------------------------------

(def SetSessionModeResponse
  "Response to `session/set_mode` method."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SetSessionModeResponse
  "Create a `SetSessionModeResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'SetSessionModeResponse value "SetSessionModeResponse")))


;; -------------------------------------------------------------------------
;; CurrentModeUpdate
;; -------------------------------------------------------------------------

(def CurrentModeUpdate
  "The current mode of the session has changed See protocol docs: [Session Modes](https://agentclientprotocol.com/protocol/v1/session-modes)"
  [:map
   {:closed false}
   [:currentModeId #'SessionModeId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CurrentModeUpdate
  "Create a `CurrentModeUpdate` value."
  [currentModeId & {:keys [meta]}]
  (let [value {:currentModeId currentModeId
               :_meta         meta}]
    (rt/validate! #'CurrentModeUpdate value "CurrentModeUpdate")))


;; -------------------------------------------------------------------------
;; SessionConfigId
;; -------------------------------------------------------------------------

(def SessionConfigId
  "Unique identifier for a session configuration option."
  :string)

(defn ->SessionConfigId
  "Create a `SessionConfigId` value."
  [configId]
  (rt/validate! #'SessionConfigId configId "SessionConfigId"))


;; -------------------------------------------------------------------------
;; SessionConfigValueId
;; -------------------------------------------------------------------------

(def SessionConfigValueId
  "Unique identifier for a session configuration option value."
  :string)

(defn ->SessionConfigValueId
  "Create a `SessionConfigValueId` value."
  [valueId]
  (rt/validate! #'SessionConfigValueId valueId "SessionConfigValueId"))


;; -------------------------------------------------------------------------
;; SessionConfigGroupId
;; -------------------------------------------------------------------------

(def SessionConfigGroupId
  "Unique identifier for a session configuration option value group."
  :string)

(defn ->SessionConfigGroupId
  "Create a `SessionConfigGroupId` value."
  [groupId]
  (rt/validate! #'SessionConfigGroupId groupId "SessionConfigGroupId"))


;; -------------------------------------------------------------------------
;; SessionConfigOptionCategory
;; -------------------------------------------------------------------------

(def SessionConfigOptionCategory
  "Semantic category for a session configuration option. This is intended to help Clients distinguish broadly common selectors (e.g. model selector vs session mode selector vs thought/reasoning level) for UX purposes (keyboard shortcuts, icons, placement). It MUST NOT be required for correctness. Clients MUST handle missing or unknown categories gracefully. Category names beginning with `_` are free for custom use, like other ACP extension methods. Category names that do not begin with `_` are reserved for the ACP spec."
  [:or
   [:= "mode"]
   [:= "model"]
   [:= "model_config"]
   [:= "thought_level"]
   :string])

(defn ->SessionConfigOptionCategory
  "Create a `SessionConfigOptionCategory` value."
  [category]
  (rt/validate!
   #'SessionConfigOptionCategory category "SessionConfigOptionCategory"))


;; -------------------------------------------------------------------------
;; SessionConfigSelectOption
;; -------------------------------------------------------------------------

(def SessionConfigSelectOption
  "A possible value for a session configuration option."
  [:map
   {:closed false}
   [:value #'SessionConfigValueId]
   [:name :string]
   [:description {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionConfigSelectOption
  "Create a `SessionConfigSelectOption` value."
  [value name & {:keys [description meta]}]
  (let [value {:value       value
               :name        name
               :description description
               :_meta       meta}]
    (rt/validate!
      #'SessionConfigSelectOption value "SessionConfigSelectOption")))


;; -------------------------------------------------------------------------
;; SessionConfigSelectGroup
;; -------------------------------------------------------------------------

(def SessionConfigSelectGroup
  "A group of possible values for a session configuration option."
  [:map
   {:closed false}
   [:group #'SessionConfigGroupId]
   [:name :string]
   [:options [:vector #'SessionConfigSelectOption]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SessionConfigSelectGroup
  "Create a `SessionConfigSelectGroup` value."
  [group name options & {:keys [meta]}]
  (let [value {:group   group
               :name    name
               :options options
               :_meta   meta}]
    (rt/validate!
      #'SessionConfigSelectGroup value "SessionConfigSelectGroup")))


;; -------------------------------------------------------------------------
;; SessionConfigSelectOptions
;; -------------------------------------------------------------------------

(def SessionConfigSelectOptions
  "Possible values for a session configuration option."
  [:or
   [:vector #'SessionConfigSelectOption]
   [:vector #'SessionConfigSelectGroup]])

(defn ->SessionConfigSelectOptions
  "Create a `SessionConfigSelectOptions` value."
  [options]
  (rt/validate!
   #'SessionConfigSelectOptions options "SessionConfigSelectOptions"))


;; -------------------------------------------------------------------------
;; SessionConfigSelect
;; -------------------------------------------------------------------------

(def SessionConfigSelect
  "A single-value selector (dropdown) session configuration option payload."
  [:map
   {:closed false}
   [:currentValue #'SessionConfigValueId]
   [:options #'SessionConfigSelectOptions]])

(defn ->SessionConfigSelect
  "Create a `SessionConfigSelect` value."
  [currentValue options]
  (let [value {:currentValue currentValue
               :options      options}]
    (rt/validate! #'SessionConfigSelect value "SessionConfigSelect")))


;; -------------------------------------------------------------------------
;; SessionConfigBoolean
;; -------------------------------------------------------------------------

(def SessionConfigBoolean
  "A boolean on/off toggle session configuration option payload."
  [:map {:closed false} [:currentValue :boolean]])

(defn ->SessionConfigBoolean
  "Create a `SessionConfigBoolean` value."
  [currentValue]
  (let [value {:currentValue currentValue}]
    (rt/validate! #'SessionConfigBoolean value "SessionConfigBoolean")))


;; -------------------------------------------------------------------------
;; SessionConfigOption
;; -------------------------------------------------------------------------

(def SessionConfigOption
  "A session configuration option selector and its current state."
  [:and
   [:map
    {:closed false}
    [:id #'SessionConfigId]
    [:name :string]
    [:description {:optional true} [:maybe :string]]
    [:category {:optional true} [:maybe #'SessionConfigOptionCategory]]
    [:_meta {:optional true} [:maybe [:map {:closed false}]]]]
   [:or
    [:and
     [:map {:closed false} [:type [:= "select"]]]
     #'SessionConfigSelect]
    [:and
     [:map {:closed false} [:type [:= "boolean"]]]
     #'SessionConfigBoolean]]])

(defn ->SessionConfigOption
  "Create a `SessionConfigOption` value.

  `:type` is the variant discriminator: select options take
  `:currentValue` and `:options`, boolean options only `:currentValue`."
  [id name & {:keys [type currentValue options description category meta]}]
  (let [value (cond-> {:id           id
                       :name         name
                       :type         type
                       :currentValue currentValue
                       :description  description
                       :category     category
                       :_meta        meta}
                (some? options) (assoc :options options))]
    (rt/validate! #'SessionConfigOption value "SessionConfigOption")))


;; -------------------------------------------------------------------------
;; SetSessionConfigOptionRequest
;; -------------------------------------------------------------------------

(def SetSessionConfigOptionRequest
  "Request parameters for setting a session configuration option."
  [:and
   [:map
    {:closed false}
    [:sessionId #'SessionId]
    [:configId #'SessionConfigId]
    [:_meta {:optional true} [:maybe [:map {:closed false}]]]]
   [:or
    [:map {:closed false} [:type [:= "boolean"]] [:value :boolean]]
    [:map {:closed false} [:value #'SessionConfigValueId]]]])

(defn ->SetSessionConfigOptionRequest
  "Create a `SetSessionConfigOptionRequest` value.

  `optionValue` is a `SessionConfigValueId`, or a boolean when `:type` is
  set to the boolean variant."
  [sessionId configId optionValue & {:keys [type meta]}]
  (let [value (cond-> {:sessionId sessionId
                       :configId  configId
                       :value     optionValue
                       :_meta     meta}
                (some? type) (assoc :type type))]
    (rt/validate! #'SetSessionConfigOptionRequest
                  value "SetSessionConfigOptionRequest")))


;; -------------------------------------------------------------------------
;; SetSessionConfigOptionResponse
;; -------------------------------------------------------------------------

(def SetSessionConfigOptionResponse
  "Response to `session/set_config_option` method."
  [:map
   {:closed false}
   [:configOptions [:vector #'SessionConfigOption]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SetSessionConfigOptionResponse
  "Create a `SetSessionConfigOptionResponse` value."
  [configOptions & {:keys [meta]}]
  (let [value {:configOptions configOptions
               :_meta         meta}]
    (rt/validate!
      #'SetSessionConfigOptionResponse value "SetSessionConfigOptionResponse")))


;; -------------------------------------------------------------------------
;; ConfigOptionUpdate
;; -------------------------------------------------------------------------

(def ConfigOptionUpdate
  "Session configuration options have been updated."
  [:map
   {:closed false}
   [:configOptions [:vector #'SessionConfigOption]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ConfigOptionUpdate
  "Create a `ConfigOptionUpdate` value."
  [configOptions & {:keys [meta]}]
  (let [value {:configOptions configOptions
               :_meta         meta}]
    (rt/validate! #'ConfigOptionUpdate value "ConfigOptionUpdate")))


;; -------------------------------------------------------------------------
;; PlanEntryPriority
;; -------------------------------------------------------------------------

(def PlanEntryPriority
  "Priority levels for plan entries. Used to indicate the relative importance or urgency of different tasks in the execution plan. See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/v1/agent-plan#plan-entries)"
  [:enum "high" "medium" "low"])

(defn ->PlanEntryPriority
  "Create a `PlanEntryPriority` value."
  [priority]
  (rt/validate! #'PlanEntryPriority priority "PlanEntryPriority"))


;; -------------------------------------------------------------------------
;; PlanEntryStatus
;; -------------------------------------------------------------------------

(def PlanEntryStatus
  "Status of a plan entry in the execution flow. Tracks the lifecycle of each task from planning through completion. See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/v1/agent-plan#plan-entries)"
  [:enum "pending" "in_progress" "completed"])

(defn ->PlanEntryStatus
  "Create a `PlanEntryStatus` value."
  [status]
  (rt/validate! #'PlanEntryStatus status "PlanEntryStatus"))


;; -------------------------------------------------------------------------
;; PlanEntry
;; -------------------------------------------------------------------------

(def PlanEntry
  "A single entry in the execution plan. Represents a task or goal that the assistant intends to accomplish as part of fulfilling the user's request. See protocol docs: [Plan Entries](https://agentclientprotocol.com/protocol/v1/agent-plan#plan-entries)"
  [:map
   {:closed false}
   [:content :string]
   [:priority #'PlanEntryPriority]
   [:status #'PlanEntryStatus]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->PlanEntry
  "Create a `PlanEntry` value."
  [content priority status & {:keys [meta]}]
  (let [value {:content  content
               :priority priority
               :status   status
               :_meta    meta}]
    (rt/validate! #'PlanEntry value "PlanEntry")))


;; -------------------------------------------------------------------------
;; Plan
;; -------------------------------------------------------------------------

(def Plan
  "An execution plan for accomplishing complex tasks. Plans consist of multiple entries representing individual tasks or goals. Agents report plans to clients to provide visibility into their execution strategy. Plans can evolve during execution as the agent discovers new requirements or completes tasks. See protocol docs: [Agent Plan](https://agentclientprotocol.com/protocol/v1/agent-plan)"
  [:map
   {:closed false}
   [:entries [:vector #'PlanEntry]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->Plan
  "Create a `Plan` value."
  [entries & {:keys [meta]}]
  (let [value {:entries entries
               :_meta   meta}]
    (rt/validate! #'Plan value "Plan")))


;; -------------------------------------------------------------------------
;; AvailableCommandInput
;; -------------------------------------------------------------------------

(def AvailableCommandInput
  "The input specification for a command."
  #'UnstructuredCommandInput)

(defn ->AvailableCommandInput
  "Create a `AvailableCommandInput` value."
  [input]
  (rt/validate! #'AvailableCommandInput input "AvailableCommandInput"))


;; -------------------------------------------------------------------------
;; UnstructuredCommandInput
;; -------------------------------------------------------------------------

(def UnstructuredCommandInput
  "All text that was typed after the command name is provided as input."
  [:map
   {:closed false}
   [:hint :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->UnstructuredCommandInput
  "Create a `UnstructuredCommandInput` value."
  [hint & {:keys [meta]}]
  (let [value {:hint  hint
               :_meta meta}]
    (rt/validate!
      #'UnstructuredCommandInput value "UnstructuredCommandInput")))


;; -------------------------------------------------------------------------
;; AvailableCommand
;; -------------------------------------------------------------------------

(def AvailableCommand
  "Information about a command."
  [:map
   {:closed false}
   [:name :string]
   [:description :string]
   [:input {:optional true} [:maybe #'AvailableCommandInput]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AvailableCommand
  "Create a `AvailableCommand` value."
  [name description & {:keys [input meta]}]
  (let [value {:name        name
               :description description
               :input       input
               :_meta       meta}]
    (rt/validate! #'AvailableCommand value "AvailableCommand")))


;; -------------------------------------------------------------------------
;; AvailableCommandsUpdate
;; -------------------------------------------------------------------------

(def AvailableCommandsUpdate
  "Available commands are ready or have changed"
  [:map
   {:closed false}
   [:availableCommands [:vector #'AvailableCommand]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AvailableCommandsUpdate
  "Create a `AvailableCommandsUpdate` value."
  [availableCommands & {:keys [meta]}]
  (let [value {:availableCommands availableCommands
               :_meta             meta}]
    (rt/validate! #'AvailableCommandsUpdate value "AvailableCommandsUpdate")))


;; -------------------------------------------------------------------------
;; Cost
;; -------------------------------------------------------------------------

(def Cost
  "Cost information for a session."
  [:map
   {:closed false}
   [:amount number?]
   [:currency :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->Cost
  "Create a `Cost` value."
  [amount currency & {:keys [meta]}]
  (let [value {:amount   amount
               :currency currency
               :_meta    meta}]
    (rt/validate! #'Cost value "Cost")))


;; -------------------------------------------------------------------------
;; UsageUpdate
;; -------------------------------------------------------------------------

(def UsageUpdate
  "Context window and cost update for a session."
  [:map
   {:closed false}
   [:used [:int {:min 0}]]
   [:size [:int {:min 0}]]
   [:cost {:optional true} [:maybe #'Cost]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->UsageUpdate
  "Create a `UsageUpdate` value."
  [used size & {:keys [cost meta]}]
  (let [value {:used  used
               :size  size
               :cost  cost
               :_meta meta}]
    (rt/validate! #'UsageUpdate value "UsageUpdate")))


;; -------------------------------------------------------------------------
;; NoticeSeverity
;; -------------------------------------------------------------------------

(def NoticeSeverity
  "Severity hint for a session notice."
  [:or [:= "info"] [:= "warning"] [:= "error"] :string])

(defn ->NoticeSeverity
  "Create a `NoticeSeverity` value."
  [severity]
  (rt/validate! #'NoticeSeverity severity "NoticeSeverity"))


;; -------------------------------------------------------------------------
;; Notice
;; -------------------------------------------------------------------------

(def Notice
  "Fire-and-forget information for the user. Notices are live events rather than session history. Agents must not rely on a notice being received, displayed, or seen by the user. Agents MUST only send notices when the Client advertised the `session.notices` Client capability. Otherwise, Agents may use an agent message when the information should still be surfaced to the user. See RFD: [Session Notices](https://agentclientprotocol.com/rfds/session-notices)"
  [:map
   {:closed false}
   [:severity #'NoticeSeverity]
   [:title [:string {:min 1}]]
   [:description {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->Notice
  "Create a `Notice` value."
  [severity title & {:keys [description meta]}]
  (let [value {:severity    severity
               :title       title
               :description description
               :_meta       meta}]
    (rt/validate! #'Notice value "Notice")))


;; -------------------------------------------------------------------------
;; CompactionId
;; -------------------------------------------------------------------------

(def CompactionId
  "Unique identifier for a context compaction within a session."
  :string)

(defn ->CompactionId
  "Create a `CompactionId` value."
  [compactionId]
  (rt/validate! #'CompactionId compactionId "CompactionId"))


;; -------------------------------------------------------------------------
;; CompactionStatus
;; -------------------------------------------------------------------------

(def CompactionStatus
  "Lifecycle state of a context compaction."
  [:or
   [:= "in_progress"]
   [:= "completed"]
   [:= "failed"]
   [:= "cancelled"]
   :string])

(defn ->CompactionStatus
  "Create a `CompactionStatus` value."
  [status]
  (rt/validate! #'CompactionStatus status "CompactionStatus"))


;; -------------------------------------------------------------------------
;; CompactionUpdate
;; -------------------------------------------------------------------------

(def CompactionUpdate
  "A context compaction upsert. The first notification fixes the compaction's timeline position. Later updates with the same ID patch that entity in place. Agents MUST only send this update when the Client advertised the `session.compaction` Client capability. `summary`, `error`, and `_meta` have patch semantics: omission leaves the stored value unchanged, `null` clears it, and a concrete value replaces it. `summary: []` also clears the summary."
  [:map
   {:closed false}
   [:compactionId #'CompactionId]
   [:status #'CompactionStatus]
   [:summary {:optional true} [:maybe [:vector #'content/ContentBlock]]]
   [:error {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CompactionUpdate
  "Create a `CompactionUpdate` value."
  [compactionId status & {:keys [summary error meta]}]
  (let [value {:compactionId compactionId
               :status       status
               :summary      summary
               :error        error
               :_meta        meta}]
    (rt/validate! #'CompactionUpdate value "CompactionUpdate")))


;; -------------------------------------------------------------------------
;; CompactionSummaryChunk
;; -------------------------------------------------------------------------

(def CompactionSummaryChunk
  "A content block appended to a compaction's summary. A first-seen ID creates an in-progress compaction. Chunks append in receive order. Agents MUST only send this update when the Client advertised the `session.compaction` Client capability."
  [:map
   {:closed false}
   [:compactionId #'CompactionId]
   [:content #'content/ContentBlock]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CompactionSummaryChunk
  "Create a `CompactionSummaryChunk` value."
  [compactionId content & {:keys [meta]}]
  (let [value {:compactionId compactionId
               :content      content
               :_meta        meta}]
    (rt/validate! #'CompactionSummaryChunk value "CompactionSummaryChunk")))


;; -------------------------------------------------------------------------
;; PermissionOptionId
;; -------------------------------------------------------------------------

(def PermissionOptionId
  "Unique identifier for a permission option."
  :string)

(defn ->PermissionOptionId
  "Create a `PermissionOptionId` value."
  [optionId]
  (rt/validate! #'PermissionOptionId optionId "PermissionOptionId"))


;; -------------------------------------------------------------------------
;; PermissionOptionKind
;; -------------------------------------------------------------------------

(def PermissionOptionKind
  "The type of permission option being presented to the user. Helps clients choose appropriate icons and UI treatment."
  [:enum "allow_once" "allow_always" "reject_once" "reject_always"])

(defn ->PermissionOptionKind
  "Create a `PermissionOptionKind` value."
  [kind]
  (rt/validate! #'PermissionOptionKind kind "PermissionOptionKind"))


;; -------------------------------------------------------------------------
;; PermissionOption
;; -------------------------------------------------------------------------

(def PermissionOption
  "An option presented to the user when requesting permission."
  [:map
   {:closed false}
   [:optionId #'PermissionOptionId]
   [:name :string]
   [:kind #'PermissionOptionKind]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->PermissionOption
  "Create a `PermissionOption` value."
  [optionId name kind & {:keys [meta]}]
  (let [value {:optionId optionId
               :name     name
               :kind     kind
               :_meta    meta}]
    (rt/validate! #'PermissionOption value "PermissionOption")))


;; -------------------------------------------------------------------------
;; RequestPermissionRequest
;; -------------------------------------------------------------------------

(def RequestPermissionRequest
  "Request for user permission to execute a tool call. Sent when the agent needs authorization before performing a sensitive operation. See protocol docs: [Requesting Permission](https://agentclientprotocol.com/protocol/v1/tool-calls#requesting-permission)"
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:toolCall #'tool/ToolCallUpdate]
   [:options [:vector #'PermissionOption]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->RequestPermissionRequest
  "Create a `RequestPermissionRequest` value."
  [sessionId toolCall options & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :toolCall  toolCall
               :options   options
               :_meta     meta}]
    (rt/validate!
      #'RequestPermissionRequest value "RequestPermissionRequest")))


;; -------------------------------------------------------------------------
;; RequestPermissionOutcome
;; -------------------------------------------------------------------------

(def RequestPermissionOutcome
  "The outcome of a permission request."
  [:or
   [:map {:closed false} [:outcome [:= "cancelled"]]]
   [:and
    [:map {:closed false} [:outcome [:= "selected"]]]
    #'SelectedPermissionOutcome]])

(defn ->RequestPermissionOutcome
  "Create a `RequestPermissionOutcome` value."
  [outcome]
  (rt/validate!
   #'RequestPermissionOutcome outcome "RequestPermissionOutcome"))


;; -------------------------------------------------------------------------
;; SelectedPermissionOutcome
;; -------------------------------------------------------------------------

(def SelectedPermissionOutcome
  "The user selected one of the provided options."
  [:map
   {:closed false}
   [:optionId #'PermissionOptionId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->SelectedPermissionOutcome
  "Create a `SelectedPermissionOutcome` value."
  [optionId & {:keys [meta]}]
  (let [value {:optionId optionId
               :_meta    meta}]
    (rt/validate!
      #'SelectedPermissionOutcome value "SelectedPermissionOutcome")))


;; -------------------------------------------------------------------------
;; RequestPermissionResponse
;; -------------------------------------------------------------------------

(def RequestPermissionResponse
  "Response to a permission request."
  [:map
   {:closed false}
   [:outcome #'RequestPermissionOutcome]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->RequestPermissionResponse
  "Create a `RequestPermissionResponse` value."
  [outcome & {:keys [meta]}]
  (let [value {:outcome outcome
               :_meta   meta}]
    (rt/validate!
      #'RequestPermissionResponse value "RequestPermissionResponse")))


;; -------------------------------------------------------------------------
;; CancelNotification
;; -------------------------------------------------------------------------

(def CancelNotification
  "Notification to cancel ongoing operations for a session. See protocol docs: [Cancellation](https://agentclientprotocol.com/protocol/v1/prompt-turn#cancellation)"
  [:map
   {:closed false}
   [:sessionId #'SessionId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CancelNotification
  "Create a `CancelNotification` value."
  [sessionId & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :_meta     meta}]
    (rt/validate! #'CancelNotification value "CancelNotification")))
