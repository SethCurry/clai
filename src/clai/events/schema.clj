(ns clai.events.schema
  "Malli schemas for the PostgreSQL session and event tables.

  The canonical storage DDL lives in `schema/schema.sql`. Event types mirror
  the `agent_session_event_type_t` enum, and event content describes the JSONB
  payload persisted for that event type.")

;; Every value allowed by the `agent_session_event_type_t` PostgreSQL enum.
(def EventTypeName
  [:enum "user_prompt" "agent_message" "agent_thought" "tool_call"
   "clojure_form"])

;; Every content-block type currently understood by the event log.
(def ContentTypeName [:enum "text"])

;; A text content block stored in an event's JSONB `content` column.
(def TextContent
  [:map
   [:type [:= "text"]]
   [:content :string]])

;; One content block inside a list of event content.
(def ^:private Content
  [:or #'TextContent])

;; The ordered content list persisted for message and thought events.
(def ContentList
  [:vector
   #'Content])

;; The structured payload persisted for a tool call event.
(def ToolCallContent
  [:map
   [:id :string]
   [:start_time inst?]
   [:input [:map]]
   [:output [:map]]])

;; An `agent_session` row as exposed by the session store.
(def AgentSession
  [:map
   [:id :int]
   [:working_directory :string]
   [:created_at :string]])

;; The columns shared by every `agent_session_event` row.
(def ^:private AgentSessionEventBase
  [:map
   [:id :int]
   [:agent_session_id :int]])

(defn- create-agent-session-event
  "Build the schema for an `agent_session_event` row with `type-name` and
  `content-type`."
  {:malli/schema [:-> EventTypeName :any :map]}
  [type-name content-type]
  (into AgentSessionEventBase [[:type [:= type-name]] [:content content-type]]))

;; A user turn (including the internal system prompt that opens a session).
(def UserPromptEvent
  (create-agent-session-event "user_prompt" #'ContentList))

;; A model thought emitted while producing a response.
(def AgentThoughtEvent
  (create-agent-session-event "agent_thought" #'ContentList))

;; A completed assistant message.
(def AgentMessageEvent
  (create-agent-session-event "agent_message" #'ContentList))

;; A tool invocation and its result.
(def ToolCallEvent
  (create-agent-session-event "tool_call" #'ToolCallContent))

;; A recorded Clojure form. The form representation is intentionally open
;; until the evaluator starts writing these events.
(def ClojureFormEvent
  (create-agent-session-event "clojure_form" :map))

;; Any row that can appear in `agent_session_event`.
(def AgentSessionEvent
  [:or
   #'UserPromptEvent
   #'AgentThoughtEvent
   #'AgentMessageEvent
   #'ToolCallEvent
   #'ClojureFormEvent])
