(ns acp.schema.tool
  "Tool call schemas, plus the `terminal/*` method requests and responses
  that agents use while executing tools."
  (:require [acp.schema-runtime :as rt]
            [acp.schema.common :as common]
            [acp.schema.content :as content]
            [acp.schema.mcp :as mcp]
            [acp.schema.terminal :as terminal]))


;; -------------------------------------------------------------------------
;; ToolKind
;; -------------------------------------------------------------------------

(def ToolKind
  "Categories of tools that can be invoked. Tool kinds help clients choose appropriate icons and optimize how they display tool execution progress. See protocol docs: [Creating](https://agentclientprotocol.com/protocol/v1/tool-calls#creating)"
  [:enum
   "read"
   "edit"
   "delete"
   "move"
   "search"
   "execute"
   "think"
   "fetch"
   "switch_mode"
   "other"])

(defn ->ToolKind
  "Create a `ToolKind` value."
  [kind]
  (rt/validate! #'ToolKind kind "ToolKind"))


;; -------------------------------------------------------------------------
;; ToolCallContent
;; -------------------------------------------------------------------------

(def ToolCallContent
  "Content produced by a tool call. Tool calls can produce different types of content including standard content blocks (text, images) or file diffs. See protocol docs: [Content](https://agentclientprotocol.com/protocol/v1/tool-calls#content)"
  [:or
   [:and [:map {:closed false} [:type [:= "content"]]] #'content/Content]
   [:and [:map {:closed false} [:type [:= "diff"]]] #'content/Diff]
   [:and [:map {:closed false} [:type [:= "terminal"]]] #'terminal/Terminal]])

(defn ->ToolCallContent
  "Create a `ToolCallContent` value."
  [content]
  (rt/validate! #'ToolCallContent content "ToolCallContent"))


;; -------------------------------------------------------------------------
;; ToolCallId
;; -------------------------------------------------------------------------

(def ToolCallId
  "Unique identifier for a tool call within a session."
  :string)

(defn ->ToolCallId
  "Create a `ToolCallId` value."
  [toolCallId]
  (rt/validate! #'ToolCallId toolCallId "ToolCallId"))


;; -------------------------------------------------------------------------
;; ToolCallStatus
;; -------------------------------------------------------------------------

(def ToolCallStatus
  "Execution status of a tool call. Tool calls progress through different statuses during their lifecycle. See protocol docs: [Status](https://agentclientprotocol.com/protocol/v1/tool-calls#status)"
  [:enum "pending" "in_progress" "completed" "failed"])

(defn ->ToolCallStatus
  "Create a `ToolCallStatus` value."
  [status]
  (rt/validate! #'ToolCallStatus status "ToolCallStatus"))


;; -------------------------------------------------------------------------
;; ToolCallLocation
;; -------------------------------------------------------------------------

(def ToolCallLocation
  "A file location being accessed or modified by a tool. Enables clients to implement \"follow-along\" features that track which files the agent is working with in real-time. See protocol docs: [Following the Agent](https://agentclientprotocol.com/protocol/v1/tool-calls#following-the-agent)"
  [:map
   {:closed false}
   [:path :string]
   [:line {:optional true} [:maybe [:int {:min 0}]]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ToolCallLocation
  "Create a `ToolCallLocation` value."
  [path & {:keys [line meta]}]
  (let [value {:path  path
               :line  line
               :_meta meta}]
    (rt/validate! #'ToolCallLocation value "ToolCallLocation")))


;; -------------------------------------------------------------------------
;; ToolCall
;; -------------------------------------------------------------------------

(def ToolCall
  "Represents a tool call that the language model has requested. Tool calls are actions that the agent executes on behalf of the language model, such as reading files, executing code, or fetching data from external sources. See protocol docs: [Tool Calls](https://agentclientprotocol.com/protocol/v1/tool-calls)"
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]
   [:content {:optional true} [:vector #'ToolCallContent]]
   [:name {:optional true} [:maybe :string]]
   [:toolCallId #'ToolCallId]
   [:title :string]
   [:locations {:optional true} [:vector #'ToolCallLocation]]
   [:rawOutput {:optional true} :any]
   [:status {:optional true} #'ToolCallStatus]
   [:kind {:optional true} #'ToolKind]
   [:rawInput {:optional true} :any]])

(defn ->ToolCall
  "Create a `ToolCall` value."
  [toolCallId title
   & {:keys [meta content name locations rawOutput status kind rawInput]}]
  (let [value (cond-> {:toolCallId toolCallId
                       :title      title
                       :_meta      meta
                       :name       name}
                (some? content) (assoc :content content)
                (some? locations) (assoc :locations locations)
                (some? rawOutput) (assoc :rawOutput rawOutput)
                (some? status) (assoc :status status)
                (some? kind) (assoc :kind kind)
                (some? rawInput) (assoc :rawInput rawInput))]
    (rt/validate! #'ToolCall value "ToolCall")))


;; -------------------------------------------------------------------------
;; ToolCallUpdate
;; -------------------------------------------------------------------------

(def ToolCallUpdate
  "An update to an existing tool call. Used to report progress and results as tools execute. All fields except the tool call ID are optional - only changed fields need to be included. See protocol docs: [Updating](https://agentclientprotocol.com/protocol/v1/tool-calls#updating)"
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]
   [:content {:optional true} [:maybe [:vector #'ToolCallContent]]]
   [:name {:optional true} [:maybe :string]]
   [:toolCallId #'ToolCallId]
   [:title {:optional true} [:maybe :string]]
   [:locations {:optional true} [:maybe [:vector #'ToolCallLocation]]]
   [:rawOutput {:optional true} :any]
   [:status {:optional true} [:maybe #'ToolCallStatus]]
   [:kind {:optional true} [:maybe #'ToolKind]]
   [:rawInput {:optional true} :any]])

(defn ->ToolCallUpdate
  "Create a `ToolCallUpdate` value."
  [toolCallId
   & {:keys [meta content name title locations rawOutput status kind rawInput]}]
  (let [value (cond-> {:toolCallId toolCallId
                       :_meta      meta
                       :content    content
                       :name       name
                       :title      title
                       :locations  locations
                       :status     status
                       :kind       kind}
                (some? rawOutput) (assoc :rawOutput rawOutput)
                (some? rawInput) (assoc :rawInput rawInput))]
    (rt/validate! #'ToolCallUpdate value "ToolCallUpdate")))


;; -------------------------------------------------------------------------
;; CreateTerminalRequest
;; -------------------------------------------------------------------------

(def CreateTerminalRequest
  "Request to create a new terminal and execute a command."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:command :string]
   [:args {:optional true} [:vector :string]]
   [:env {:optional true} [:vector #'mcp/EnvVariable]]
   [:cwd {:optional true} [:maybe :string]]
   [:outputByteLimit {:optional true} [:maybe [:int {:min 0}]]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CreateTerminalRequest
  "Create a `CreateTerminalRequest` value."
  [sessionId command & {:keys [args env cwd outputByteLimit meta]}]
  (let [value (cond-> {:sessionId       sessionId
                       :command         command
                       :cwd             cwd
                       :outputByteLimit outputByteLimit
                       :_meta           meta}
                (some? args) (assoc :args args)
                (some? env) (assoc :env env))]
    (rt/validate! #'CreateTerminalRequest value "CreateTerminalRequest")))


;; -------------------------------------------------------------------------
;; CreateTerminalResponse
;; -------------------------------------------------------------------------

(def CreateTerminalResponse
  "Response containing the ID of the created terminal."
  [:map
   {:closed false}
   [:terminalId #'terminal/TerminalId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CreateTerminalResponse
  "Create a `CreateTerminalResponse` value."
  [terminalId & {:keys [meta]}]
  (let [value {:terminalId terminalId
               :_meta      meta}]
    (rt/validate! #'CreateTerminalResponse value "CreateTerminalResponse")))


;; -------------------------------------------------------------------------
;; TerminalOutputRequest
;; -------------------------------------------------------------------------

(def TerminalOutputRequest
  "Request to get the current output and status of a terminal."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:terminalId #'terminal/TerminalId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->TerminalOutputRequest
  "Create a `TerminalOutputRequest` value."
  [sessionId terminalId & {:keys [meta]}]
  (let [value {:sessionId  sessionId
               :terminalId terminalId
               :_meta      meta}]
    (rt/validate! #'TerminalOutputRequest value "TerminalOutputRequest")))


;; -------------------------------------------------------------------------
;; KillTerminalRequest
;; -------------------------------------------------------------------------

(def KillTerminalRequest
  "Request to kill a terminal without releasing it."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:terminalId #'terminal/TerminalId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->KillTerminalRequest
  "Create a `KillTerminalRequest` value."
  [sessionId terminalId & {:keys [meta]}]
  (let [value {:sessionId  sessionId
               :terminalId terminalId
               :_meta      meta}]
    (rt/validate! #'KillTerminalRequest value "KillTerminalRequest")))


;; -------------------------------------------------------------------------
;; KillTerminalResponse
;; -------------------------------------------------------------------------

(def KillTerminalResponse
  "Response to `terminal/kill` method"
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->KillTerminalResponse
  "Create a `KillTerminalResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'KillTerminalResponse value "KillTerminalResponse")))


;; -------------------------------------------------------------------------
;; WaitForTerminalExitRequest
;; -------------------------------------------------------------------------

(def WaitForTerminalExitRequest
  "Request to wait for a terminal command to exit."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:terminalId #'terminal/TerminalId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->WaitForTerminalExitRequest
  "Create a `WaitForTerminalExitRequest` value."
  [sessionId terminalId & {:keys [meta]}]
  (let [value {:sessionId  sessionId
               :terminalId terminalId
               :_meta      meta}]
    (rt/validate! #'WaitForTerminalExitRequest
                  value "WaitForTerminalExitRequest")))


;; -------------------------------------------------------------------------
;; WaitForTerminalExitResponse
;; -------------------------------------------------------------------------

(def WaitForTerminalExitResponse
  "Response containing the exit status of a terminal command."
  [:map
   {:closed false}
   [:exitCode {:optional true} [:maybe [:int {:min 0}]]]
   [:signal {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->WaitForTerminalExitResponse
  "Create a `WaitForTerminalExitResponse` value."
  [& {:keys [exitCode signal meta]}]
  (let [value {:exitCode exitCode
               :signal   signal
               :_meta    meta}]
    (rt/validate! #'WaitForTerminalExitResponse
                  value "WaitForTerminalExitResponse")))


;; -------------------------------------------------------------------------
;; ReleaseTerminalRequest
;; -------------------------------------------------------------------------

(def ReleaseTerminalRequest
  "Request to release a terminal and free its resources."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:terminalId #'terminal/TerminalId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ReleaseTerminalRequest
  "Create a `ReleaseTerminalRequest` value."
  [sessionId terminalId & {:keys [meta]}]
  (let [value {:sessionId  sessionId
               :terminalId terminalId
               :_meta      meta}]
    (rt/validate! #'ReleaseTerminalRequest value "ReleaseTerminalRequest")))


;; -------------------------------------------------------------------------
;; ReleaseTerminalResponse
;; -------------------------------------------------------------------------

(def ReleaseTerminalResponse
  "Response to terminal/release method"
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ReleaseTerminalResponse
  "Create a `ReleaseTerminalResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'ReleaseTerminalResponse value "ReleaseTerminalResponse")))
