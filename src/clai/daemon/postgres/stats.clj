(ns clai.daemon.postgres.stats
  "Usage statistics about `clai`'s sessions and tool calls, read from
  PostgreSQL.

  The session event log (`agent_session_event`, see
  `clai.daemon.postgres.sessions`) records everything a session does: its
  prompts, the model's replies and thoughts, and the tool calls it made. Those
  rows are the source of every number here, so the statistics cover the whole
  history of the database rather than one daemon's lifetime.

  Three questions the aggregates answer:

    * per tool -- how often is it called, how many characters does it return
      on average, and how many sessions use it (`:tools`);
    * per session -- how many prompts, replies, and tool calls did it use, and
      how many characters did each produce (`:sessions`);
    * overall -- the totals and the averages derived from them, such as the
      average number of tool calls per session (`:overview`).

  ## Shape

  A tool call is recorded twice in the log: the assistant message that requests
  it (its `tool_calls` array) and the `tool`-role message that carries the
  result, joined by the call's id. [[tool-call-records]] pairs them, so each
  record knows both what the model asked for and what the tool returned. A
  call whose result never arrived -- the prompt failed, or is still running --
  counts as a call with no output, and a result without a recorded request
  still counts.

  Character counts are measured on the JSON text stored in the log: for a
  result that is its `content`, and for a call request its arguments. That is
  close enough to the payload size for a usage chart, and it lets the database
  do the measuring instead of shipping every tool result into the JVM.

  ## Example

      (require '[clai.daemon.postgres :as pg]
               '[clai.daemon.postgres.stats :as stats])

      (def client (pg/open {:host \"localhost\"
                            :database \"clai\"
                            :user \"clai\"
                            :password \"secret\"}))

      (:overview (stats/usage-stats client))
      ;; => {:sessionCount 12, :toolCallCount 340, ...}

      (map :toolName (:tools (stats/usage-stats client)))
      ;; => (\"eval_clojure\" \"read_file\" ...)"
  (:require [clai.daemon.postgres :as postgres]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ToolCallRow
  "One tool call the model requested, as read from an event's `tool_calls`
  array."
  [:map
   [:session-id :int]
   [:call-id [:maybe :string]]
   [:tool-name [:maybe :string]]
   [:input-chars [:maybe :int]]])

(def ToolResultRow
  "One `tool`-role event: the result the daemon recorded for a tool call."
  [:map
   [:session-id :int]
   [:call-id [:maybe :string]]
   [:tool-name [:maybe :string]]
   [:output-chars [:maybe :int]]])

(def EventRow
  "The message-level part of one event: which session it belongs to, what kind
  of event it is, the message's role, and how many characters its content
  holds."
  [:map
   [:session-id :int]
   [:event-type :string]
   [:role [:maybe :string]]
   [:chars [:maybe :int]]])

(def ToolCallRecord
  "One tool call with its request and result joined."
  [:map
   [:sessionId :string]
   [:toolName :string]
   [:inputChars :int]
   [:outputChars :int]
   [:completed? :boolean]])

(def ToolStat
  "How one tool was used: call counts, session reach, and the characters it
  moved."
  [:map
   [:toolName :string]
   [:callCount :int]
   [:completedCount :int]
   [:sessionCount :int]
   [:totalInputChars :int]
   [:avgInputChars [:maybe [:or :int :double]]]
   [:totalOutputChars :int]
   [:avgOutputChars [:maybe [:or :int :double]]]
   [:maxOutputChars :int]])

(def SessionStat
  "What one session did: its prompts, replies, thoughts, and tool calls, with
  the characters each produced."
  [:map
   [:sessionId :string]
   [:title [:maybe :string]]
   [:cwd :string]
   [:createdAt [:maybe :string]]
   [:promptCount :int]
   [:promptChars :int]
   [:avgPromptChars [:maybe [:or :int :double]]]
   [:agentMessageCount :int]
   [:replyChars :int]
   [:avgReplyChars [:maybe [:or :int :double]]]
   [:thoughtCount :int]
   [:toolCallCount :int]
   [:toolOutputChars :int]
   [:avgToolOutputChars [:maybe [:or :int :double]]]
   [:distinctToolCount :int]])

(def Overview
  "Totals across every session and tool, plus the averages derived from them."
  [:map
   [:sessionCount :int]
   [:toolCount :int]
   [:promptCount :int]
   [:agentMessageCount :int]
   [:thoughtCount :int]
   [:toolCallCount :int]
   [:completedToolCallCount :int]
   [:avgPromptsPerSession [:maybe [:or :int :double]]]
   [:avgToolCallsPerSession [:maybe [:or :int :double]]]
   [:totalPromptChars :int]
   [:avgPromptChars [:maybe [:or :int :double]]]
   [:totalReplyChars :int]
   [:avgReplyChars [:maybe [:or :int :double]]]
   [:totalToolOutputChars :int]
   [:avgToolOutputChars [:maybe [:or :int :double]]]
   [:maxToolOutputChars :int]
   [:avgToolOutputCharsPerSession [:maybe [:or :int :double]]]])

(def Stats
  "Everything [[usage-stats]] reports."
  [:map
   [:overview #'Overview]
   [:tools [:vector #'ToolStat]]
   [:sessions [:vector #'SessionStat]]])

;; ---------------------------------------------------------------------------
;; Arithmetic helpers
;; ---------------------------------------------------------------------------

(defn- mean
  "The arithmetic mean of `values`, or nil when there are none."
  {:malli/schema [:-> [:sequential [:or :int :double]] [:maybe :double]]}
  [values]
  (when (seq values)
    (/ (reduce + 0.0 values) (double (count values)))))

(defn- rounded
  "Round `value` to `digits` decimal places, leaving nil alone. Zero digits
  yields a whole number."
  {:malli/schema [:-> [:maybe [:or :int :double]] :int
                  [:maybe [:or :int :double]]]}
  [value digits]
  (when (some? value)
    (let [factor (Math/pow 10.0 (double digits))
          scaled (Math/round (* factor (double value)))]
      (if (zero? (long digits))
        (long scaled)
        (/ scaled factor)))))

(defn- per
  "`total` divided by `n`, rounded to `digits` places. Nil when `n` is zero,
  which is how an average over no data is reported."
  {:malli/schema [:-> [:or :int :double] :int :int
                  [:maybe [:or :int :double]]]}
  [total n digits]
  (when (pos? (long n))
    (rounded (/ (double total) (double n)) digits)))

(defn- sum
  "The sum of the numbers in `values`, treating nil as zero."
  {:malli/schema [:-> [:sequential [:maybe [:or :int :double]]]
                  [:or :int :double]]}
  [values]
  (reduce + 0 (remove nil? values)))

;; ---------------------------------------------------------------------------
;; SQL
;; ---------------------------------------------------------------------------

(def ^:private sessions-sql
  "Every session, oldest first, so statistics include sessions with no
  activity yet."
  "SELECT id, title, working_directory, created_at
     FROM agent_session
    ORDER BY created_at, id")

(def ^:private tool-calls-sql
  "One row per tool call the model requested, with the character length of the
  arguments it passed."
  "SELECT e.agent_session_id                                  AS session_id,
          call->>'id'                                        AS call_id,
          call->'function'->>'name'                          AS tool_name,
          COALESCE(length((call->'function'->'arguments')::text), 0)
                                                             AS input_chars
     FROM agent_session_event e
     CROSS JOIN LATERAL jsonb_array_elements(
         CASE WHEN jsonb_typeof(e.content->'tool_calls') = 'array'
              THEN e.content->'tool_calls'
              ELSE '[]'::jsonb
         END) AS call
    WHERE e.event_type = 'tool_call'")

(def ^:private tool-results-sql
  "One row per recorded `tool` result, with the character length of the output
  it returned."
  "SELECT e.agent_session_id                                  AS session_id,
          e.content->>'tool_call_id'                         AS call_id,
          e.content->>'name'                                 AS tool_name,
          COALESCE(length(e.content->>'content'), 0)         AS output_chars
     FROM agent_session_event e
    WHERE e.event_type = 'tool_call'
      AND e.content->>'role' = 'tool'")

(def ^:private events-sql
  "One row per conversation event: its session, type, role, and content
  length."
  "SELECT e.agent_session_id                                   AS session_id,
          e.event_type::text                                  AS event_type,
          e.content->>'role'                                  AS role,
          COALESCE(length(e.content->>'content'), 0)          AS chars
     FROM agent_session_event e
    WHERE e.event_type IN ('user_prompt', 'agent_message', 'agent_thought')")

;; ---------------------------------------------------------------------------
;; Row conversion
;; ---------------------------------------------------------------------------

(defn- row->session
  "Convert an `agent_session` row to the summary the statistics extend."
  {:malli/schema [:-> :map :map]}
  [row]
  {:sessionId (str (:id row))
   :title     (:title row)
   :cwd       (:working-directory row)
   :createdAt (some-> (:created-at row) postgres/timestamp->iso)})

;; ---------------------------------------------------------------------------
;; Tool call aggregation
;; ---------------------------------------------------------------------------

(defn tool-call-records
  "Pair the tool calls in `call-rows` with the results in `result-rows`, one
  record per call.

  A call without a result (the prompt failed, or the call is still running)
  keeps a zero `:outputChars` and `:completed?` false; a result whose request
  was not logged is still counted, with zero `:inputChars`. Both are keyed by
  the session and the call id the provider assigned."
  {:malli/schema [:-> [:vector #'ToolCallRow] [:vector #'ToolResultRow]
                  [:vector #'ToolCallRecord]]}
  [call-rows result-rows]
  (let [call-keys (into #{} (map (juxt :session-id :call-id)) call-rows)
        results   (into {}
                        (map (fn [row] [[(:session-id row) (:call-id row)] row]))
                        result-rows)]
    (into []
          (concat
           (map (fn [{:keys [session-id call-id tool-name input-chars]}]
                  (let [result (get results [session-id call-id])]
                    {:sessionId   (str session-id)
                     :toolName    (or tool-name (:tool-name result) "unknown")
                     :inputChars  (long (or input-chars 0))
                     :outputChars (long (or (:output-chars result) 0))
                     :completed?  (some? result)}))
                call-rows)
           (map (fn [{:keys [session-id tool-name output-chars]}]
                  {:sessionId   (str session-id)
                   :toolName    (or tool-name "unknown")
                   :inputChars  0
                   :outputChars (long (or output-chars 0))
                   :completed?  true})
                (remove #(contains? call-keys [(:session-id %) (:call-id %)])
                        result-rows))))))

(defn tool-stats
  "Aggregate `records` into one [[ToolStat]] per tool, most-called first.

  Averages are taken over the calls that actually produced a value: input
  characters over every call, output characters over the completed ones."
  {:malli/schema [:-> [:vector #'ToolCallRecord] [:vector #'ToolStat]]}
  [records]
  (->> records
       (group-by :toolName)
       (map (fn [[tool-name records]]
              (let [completed (filter :completed? records)
                    inputs    (map :inputChars records)
                    outputs   (map :outputChars completed)]
                {:toolName         tool-name
                 :callCount        (count records)
                 :completedCount   (count completed)
                 :sessionCount     (count (distinct (map :sessionId records)))
                 :totalInputChars  (sum inputs)
                 :avgInputChars    (rounded (mean inputs) 0)
                 :totalOutputChars (sum outputs)
                 :avgOutputChars   (rounded (mean outputs) 0)
                 :maxOutputChars   (long (reduce max 0 outputs))})))
       (sort-by (juxt (comp - :callCount) :toolName))
       vec))

;; ---------------------------------------------------------------------------
;; Session aggregation
;; ---------------------------------------------------------------------------

(defn- prompt-event?
  "True when `event` is a prompt the user typed. The system prompt that opens
  a session is recorded as a `user_prompt` event too, so it is excluded."
  {:malli/schema [:-> :map boolean?]}
  [event]
  (and (= "user_prompt" (:event-type event))
       (not= "system" (:role event))))

(defn- event-stat
  "The derived counters for one session's `events`."
  {:malli/schema [:-> [:sequential :map] :map]}
  [events]
  (let [prompts  (filter prompt-event? events)
        replies  (filter #(= "agent_message" (:event-type %)) events)
        thoughts (filter #(= "agent_thought" (:event-type %)) events)]
    {:promptCount       (count prompts)
     :promptChars       (sum (map :chars prompts))
     :avgPromptChars    (rounded (mean (map :chars prompts)) 0)
     :agentMessageCount (count replies)
     :replyChars        (sum (map :chars replies))
     :avgReplyChars     (rounded (mean (map :chars replies)) 0)
     :thoughtCount      (count thoughts)}))

(defn- call-stat
  "The derived counters for one session's tool `records`."
  {:malli/schema [:-> [:vector #'ToolCallRecord] :map]}
  [records]
  (let [outputs (map :outputChars records)]
    {:toolCallCount      (count records)
     :toolOutputChars    (sum outputs)
     :avgToolOutputChars (rounded (mean outputs) 0)
     :distinctToolCount  (count (distinct (map :toolName records)))}))

(defn session-stats
  "Aggregate `session-rows` with each session's `event-rows` and `records`
  into one [[SessionStat]] per session, oldest first."
  {:malli/schema [:-> [:vector :map] [:vector #'EventRow]
                  [:vector #'ToolCallRecord] [:vector #'SessionStat]]}
  [session-rows event-rows records]
  (let [events-by-session (group-by (comp str :session-id) event-rows)
        calls-by-session  (group-by :sessionId records)]
    (mapv (fn [row]
            (let [session (row->session row)
                  id      (:sessionId session)]
              (merge session
                     (event-stat (get events-by-session id []))
                     (call-stat (get calls-by-session id [])))))
          session-rows)))

;; ---------------------------------------------------------------------------
;; Overview
;; ---------------------------------------------------------------------------

(defn overview
  "The totals and averages across `session-stats`, `tool-stats`, and the
  `records` they are drawn from."
  {:malli/schema [:-> [:vector #'SessionStat] [:vector #'ToolStat]
                  [:vector #'ToolCallRecord] #'Overview]}
  [session-stats tool-stats records]
  (let [sessions  (count session-stats)
        prompts   (sum (map :promptCount session-stats))
        replies   (sum (map :agentMessageCount session-stats))
        calls     (count records)
        completed (filter :completed? records)
        outputs   (map :outputChars completed)
        prompt-chars (sum (map :promptChars session-stats))
        reply-chars  (sum (map :replyChars session-stats))
        output-chars (sum outputs)]
    {:sessionCount               sessions
     :toolCount                  (count tool-stats)
     :promptCount                prompts
     :agentMessageCount          replies
     :thoughtCount               (sum (map :thoughtCount session-stats))
     :toolCallCount              calls
     :completedToolCallCount     (count completed)
     :avgPromptsPerSession       (per prompts sessions 2)
     :avgToolCallsPerSession     (per calls sessions 2)
     :totalPromptChars           prompt-chars
     :avgPromptChars             (per prompt-chars prompts 0)
     :totalReplyChars            reply-chars
     :avgReplyChars              (per reply-chars replies 0)
     :totalToolOutputChars       output-chars
     :avgToolOutputChars         (rounded (mean outputs) 0)
     :maxToolOutputChars         (long (reduce max 0 outputs))
     :avgToolOutputCharsPerSession (per output-chars sessions 0)}))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(defn usage-stats
  "Read the event log through `client` and return every [[Stats]] the daemon
  reports about its sessions and tools."
  {:malli/schema [:-> #'postgres/client-schema #'Stats]}
  [client]
  (let [datasource   (:datasource (postgres/ready client))
        session-rows (postgres/exec! datasource ::list-sessions sessions-sql [])
        call-rows    (postgres/exec! datasource ::tool-calls tool-calls-sql [])
        result-rows  (postgres/exec! datasource ::tool-results tool-results-sql [])
        event-rows   (postgres/exec! datasource ::events events-sql [])
        records      (tool-call-records call-rows result-rows)
        tools        (tool-stats records)
        sessions     (session-stats session-rows event-rows records)]
    (tel/log! {:level :debug
               :id    ::usage-stats
               :data  {:sessions  (count sessions)
                       :tools     (count tools)
                       :toolCalls (count records)}}
              "Read usage statistics")
    {:overview (overview sessions tools records)
     :tools    tools
     :sessions sessions}))
