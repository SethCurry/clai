(ns clai.events.schema
  "Malli schemas for the PostgreSQL session and event tables.

  The canonical storage DDL lives in `schema/schema.sql`. Event types mirror
  the `agent_session_event_type_t` enum, and event content describes the JSONB
  payload persisted for that event type. These types are also the vocabulary
  the agent and REPL use for prompts, replies, thoughts, and tool activity, so
  values can move between the chat loop and the event log without a second
  representation."
  (:require [clojure.string :as str]))

;; Every value allowed by the `agent_session_event_type_t` PostgreSQL enum.
(def EventTypeName
  [:enum "user_prompt" "agent_message" "agent_thought" "tool_call"
   "clojure_form"])

;; Event types whose payload is a plain text/message content list.
(def TextEventType
  [:enum "user_prompt" "agent_message" "agent_thought"])

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

;; The lifecycle status carried by a streamed tool-call update.
(def ToolCallStatus
  [:enum "running" "completed" "failed"])

;; The structured payload persisted (and streamed) for a tool call event.
(def ToolCallContent
  [:map
   [:id :string]
   [:start_time {:optional true} inst?]
   [:input {:optional true} [:map]]
   [:output {:optional true} [:maybe :string]]
   [:name {:optional true} [:maybe :string]]
   [:title {:optional true} :string]
   [:status {:optional true} #'ToolCallStatus]])

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
  {:malli/schema [:-> EventTypeName :any :any]}
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

(defn text-content
  "Build a `TextContent` block for `text`."
  {:malli/schema [:-> :any #'TextContent]}
  [text]
  {:type "text" :content (str text)})

(defn content-list
  "Build a `ContentList` holding `text` as one text block."
  {:malli/schema [:-> :any #'ContentList]}
  [text]
  [(text-content text)])

(defn text-event
  "Build a text-bearing event or stream update of `type-name` for `text`."
  {:malli/schema [:-> #'TextEventType :any [:map
                                         [:type #'TextEventType]
                                         [:content #'ContentList]]]}
  [type-name text]
  {:type type-name :content (content-list text)})

(defn content->text
  "Flatten a string, a content block, or a `ContentList` to plain text.

  Text blocks use their `:content` field. The legacy `:text` field is still
  accepted for tolerance, so callers holding older message maps keep working."
  {:malli/schema [:-> :any :string]}
  [content]
  (cond
    (nil? content)        ""
    (string? content)     content
    (map? content)        (let [inner (:content content)]
                            (if (or (string? inner) (sequential? inner))
                              (content->text inner)
                              (or (some-> (:text content) str) "")))
    (sequential? content) (str/join "\n"
                                    (remove str/blank?
                                            (map content->text content)))
    :else                 (str content)))
