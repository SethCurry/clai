(ns clai.daemon.postgres.sessions
  "Agent session storage on PostgreSQL for `clai`'s daemon.

  Sessions live in the two tables declared in `schema/schema.sql` and created
  by `clai.daemon.postgres/ensure-schema!`:

      agent_session        id, working_directory, created_at
      agent_session_event  append-only event log, one typed JSONB per row

  Every function here takes a `clai.daemon.postgres/Client` as its first
  argument. Build one with `clai.daemon.postgres/open` (which applies the
  schema immediately), or with `clai.daemon.postgres/make-client`; either way
  the first storage operation ensures the schema (once per client, see
  [[ready]]), so a `make-client` client needs no explicit
  `clai.daemon.postgres/ensure-schema!` call. Because a session is database
  rows, mutations are transactional and a session is visible to every process
  sharing the database.

  ## Sessions and events

  A session is one `agent_session` row: a database-assigned id (exposed as a
  string) plus the session's working directory. Its conversation is the
  ordered `agent_session_event` rows that reference it.

  The event log is message-granular: `append-history` derives the event's
  `event_type` from the chat message's role (`user`/`system` -> `user_prompt`,
  a plain `assistant` message -> `agent_message`, an assistant message
  carrying `:thinking` -> `agent_thought`, an assistant message with
  `:tool_calls` or a `tool` result -> `tool_call`) and stores the whole message
  map in the event's `content`. Reading a history therefore needs only
  `content`, which keeps the Ollama chat request exactly as it was written;
  `event_type` makes the log queryable. `clojure_form` is reserved for
  recording evaluated Clojure forms as events of their own.

  ## Example

      (require '[clai.daemon.postgres :as pg]
               '[clai.daemon.postgres.sessions :as sessions])

      (def client (pg/open {:host \"localhost\"
                            :database \"clai\"
                            :user \"clai\"
                            :password \"secret\"}))

      (def session (sessions/create-session client {:cwd \"/tmp/project\"}))
      (sessions/append-history client (:sessionId session)
                               {:role \"user\" :content \"hello\"})
      (sessions/load-session client (:sessionId session))"
  (:require [clai.daemon.postgres :as postgres]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [taoensso.telemere :as tel])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def message
  "A chat message stored in a session's history, shaped like an Ollama chat
  message."
  [:map
   [:role non-empty-string]
   [:content {:optional true} [:or :string [:sequential [:map]]]]
   [:thinking {:optional true} [:maybe :string]]
   [:images {:optional true} [:sequential :string]]
   [:tool_calls {:optional true} [:sequential [:map]]]])

(def session
  "A session state map, as returned by [[create-session]], [[load-session]],
  and [[list-sessions]]."
  [:map
   [:sessionId non-empty-string]
   [:cwd :string]
   [:createdAt {:optional true} [:maybe :string]]
   [:history {:optional true} [:vector message]]])

(def session-summary
  "A session state map without `:history`."
  [:map
   [:sessionId non-empty-string]
   [:cwd :string]
   [:createdAt {:optional true} [:maybe :string]]])

(def create-session-opts
  "Options accepted by [[create-session]]."
  [:map
   [:cwd {:optional true} [:or :string [:fn #(instance? File %)]]]])

(def save-session-state
  "A session map accepted by [[save-session]]: the history is replaced and a
  missing `:sessionId` creates a new session."
  [:map
   [:sessionId {:optional true} [:maybe [:or non-empty-string :int]]]
   [:cwd [:or :string [:fn #(instance? File %)]]]
   [:createdAt {:optional true} [:maybe :string]]
   [:history {:optional true} [:vector message]]])

;; ---------------------------------------------------------------------------
;; Schema readiness
;; ---------------------------------------------------------------------------

(defonce ^:private schema-ready
  ;; Clients whose storage tables have already been ensured. Keeping this here
  ;; lets callers build a client (and even an agent) without touching the
  ;; database; the schema is created on the first storage operation.
  (atom #{}))

(defn- ready
  "Ensure the storage tables exist for `client` (once per client) and return
  it."
  [client]
  (when-not (contains? @schema-ready client)
    (locking schema-ready
      (when-not (contains? @schema-ready client)
        (postgres/ensure-schema! client)
        (swap! schema-ready conj client))))
  client)

;; ---------------------------------------------------------------------------
;; Row conversion
;; ---------------------------------------------------------------------------

(defn- row->summary
  "Convert an `agent_session` row to the session summary shape."
  [row]
  {:sessionId (str (:id row))
   :cwd       (:working-directory row)
   :createdAt (postgres/timestamp->iso (:created-at row))})

(defn- row->session
  "Convert an `agent_session` row plus its `history` to a session state map."
  [row history]
  (assoc (row->summary row) :history history))

(defn- row->message
  "The chat message stored in an `agent_session_event` row."
  [row]
  (or (postgres/parse-json (:content row) nil) {}))

(defn ^:no-doc session-key
  "`id` as the `agent_session.id` key it names, or nil when it does not name a
  row.

  Session ids are exposed as strings, and `INSERT` returns the numeric key, so
  both are accepted. Anything non-numeric (for example an externally minted
  UUID) returns nil instead of throwing."
  [id]
  (when (some? id)
    (when-let [n (if (number? id) id (parse-long (str id)))]
      (when (pos? n) n))))

(defn ^:no-doc message->event-type
  "The `agent_session_event_type_t` value recorded for chat `message`."
  [message]
  (let [role (:role message)]
    (cond
      (= role "assistant") (cond
                             (seq (:tool_calls message)) "tool_call"
                             (some? (:thinking message)) "agent_thought"
                             :else                       "agent_message")
      (= role "tool")      "tool_call"
      ;; User turns, and the internal system prompt that opens a session.
      :else                "user_prompt")))

;; ---------------------------------------------------------------------------
;; Session SQL
;; ---------------------------------------------------------------------------

(def ^:private select-session-sql
  "SELECT id, working_directory, created_at
     FROM agent_session
    WHERE id = ?")

(def ^:private list-sessions-sql
  "SELECT id, working_directory, created_at
     FROM agent_session
    ORDER BY created_at, id")

(def ^:private insert-session-sql
  "INSERT INTO agent_session (working_directory)
   VALUES (?)
   RETURNING id, working_directory, created_at")

(def ^:private update-session-sql
  "UPDATE agent_session
      SET working_directory = ?
    WHERE id = ?")

(def ^:private select-history-sql
  "SELECT content::text AS content
     FROM agent_session_event
    WHERE agent_session_id = ?
    ORDER BY id")

(def ^:private insert-event-sql
  "INSERT INTO agent_session_event (agent_session_id, event_type, content)
   VALUES (?, CAST(? AS agent_session_event_type_t), CAST(? AS jsonb))")

(def ^:private insert-event-if-session-sql
  "INSERT INTO agent_session_event (agent_session_id, event_type, content)
   SELECT ?, CAST(? AS agent_session_event_type_t), CAST(? AS jsonb)
    WHERE EXISTS (SELECT 1 FROM agent_session WHERE id = ?)")

(defn- canonical-cwd
  "The working directory recorded for a session: `File`s and strings are used
  as given, anything else falls back to the JVM's current directory."
  [cwd]
  (cond
    (instance? File cwd) (str cwd)
    (string? cwd)        cwd
    :else                (str (.getCanonicalFile (io/file ".")))))

(defn- history-from
  "Read a session's full message history from `connectable`."
  [connectable id]
  (mapv row->message
        (postgres/exec! connectable ::load-history select-history-sql [id])))

(defn- insert-event!
  "Append `message` to session `id`'s event log."
  [connectable id message]
  (postgres/exec-one! connectable ::insert-event insert-event-sql
                      [id
                       (message->event-type message)
                       (postgres/->json message "Session message")]))

(defn- replace-history!
  "Replace session `id`'s event log with `history`."
  [connectable id history]
  (postgres/exec! connectable ::clear-history
                  "DELETE FROM agent_session_event WHERE agent_session_id = ?"
                  [id])
  (doseq [message history]
    (insert-event! connectable id message)))

(defn- warn-unknown-session
  "Log a warning for a mutation targeting a session that does not exist."
  [op session-id]
  (tel/log! {:level :warn
             :id    ::unknown-session
             :data  {:op op :sessionId session-id}}
            "Session not found"))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn save-session
  "Persist `state` as a session, replacing its message history.

  A `state` without `:sessionId` creates a new session row and returns the
  saved state (including the database-assigned `:sessionId`). A `:sessionId`
  that does not name a row (including an id that is not a database key, such
  as a UUID) returns nil with a warning instead of creating a caller-named
  row."
  {:malli/schema [:-> #'postgres/client-schema #'save-session-state
                  [:maybe #'session]]}
  [client state]
  (let [client  (ready client)
        state   (-> state
                    (assoc :cwd (canonical-cwd (:cwd state)))
                    (assoc :history (vec (or (:history state) []))))
        _       (postgres/validate! save-session-state state "save-session")
        given   (:sessionId state)
        id      (session-key given)
        history (:history state)]
    (jdbc/with-transaction [tx (:datasource client)]
      (if-let [row (cond
                     (nil? given)
                     (postgres/exec-one! tx ::create-session
                                         insert-session-sql [(:cwd state)])

                     id
                     (let [result (postgres/exec-one! tx ::save-session
                                                      update-session-sql
                                                      [(:cwd state) id])]
                       (when (pos? (postgres/updated-count result))
                         (postgres/exec-one! tx ::reload-session
                                             select-session-sql [id]))))]
        (let [saved (do (replace-history! tx (:id row) history)
                        (row->session row history))]
          (tel/log! {:level :debug
                     :id    ::session-saved
                     :data  {:sessionId (:sessionId saved)
                             :messages  (count history)}}
                    "Saved PostgreSQL session")
          saved)
        (do
          (warn-unknown-session ::save-session given)
          nil)))))

(defn create-session
  "Create and persist a new session, returning its state map.

  Options:

    :cwd  the working directory for the session (defaults to the JVM's
          current directory). The agent also reads `<cwd>/AGENTS.md` for
          project instructions."
  {:malli/schema [:function
                  [:=> [:cat #'postgres/client-schema] #'session]
                  [:=> [:cat #'postgres/client-schema
                         [:maybe #'create-session-opts]]
                   #'session]]}
  ([client] (create-session client nil))
  ([client {:keys [cwd]}]
   (let [session (save-session client {:cwd (canonical-cwd cwd) :history []})]
     (tel/log! {:level :info
                :id    ::session-created
                :data  {:sessionId (:sessionId session) :cwd (:cwd session)}}
               "Created PostgreSQL session")
     session)))

(defn load-session
  "Read and return the state map for `session-id`, or nil when no such session
  is recorded."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'session]]}
  [client session-id]
  (let [client (ready client)]
    (when-let [id (session-key session-id)]
      (let [datasource (:datasource client)]
        (when-let [row (postgres/exec-one! datasource ::load-session
                                          select-session-sql [id])]
          (row->session row (history-from datasource id)))))))

(defn lookup-session
  "Alias for [[load-session]]."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'session]]}
  [client session-id]
  (load-session client session-id))

(defn delete-session
  "Delete `session-id` and its event log. Returns true when a session was
  removed, false otherwise."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client session-id]
  (let [client   (ready client)
        id       (session-key session-id)
        result   (when id
                   (postgres/exec-one! (:datasource client) ::delete-session
                                       "DELETE FROM agent_session WHERE id = ?"
                                       [id]))
        deleted? (pos? (postgres/updated-count result))]
    (tel/log! {:level :debug
               :id    ::session-deleted
               :data  {:sessionId session-id :deleted? deleted?}}
              "Deleted PostgreSQL session")
    deleted?))

(defn session-exists?
  "Return true when `session-id` is recorded."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client session-id]
  (let [client (ready client)]
    (boolean
     (when-let [id (session-key session-id)]
       (postgres/exec-one! (:datasource client) ::session-exists
                           "SELECT 1 AS one FROM agent_session WHERE id = ?"
                           [id])))))

(defn list-sessions
  "Return every session state map, sorted by `:createdAt` ascending, with full
  histories. Use [[list-session-summaries]] for metadata only."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'session]]}
  [client]
  (let [datasource (:datasource (ready client))
        rows       (postgres/exec! datasource ::list-sessions
                                   list-sessions-sql [])
        events     (postgres/exec! datasource ::list-messages
                                   "SELECT agent_session_id, content::text AS content
                                      FROM agent_session_event
                                     ORDER BY agent_session_id, id"
                                   [])
        by-session (reduce (fn [m row]
                             (update m (:agent-session-id row)
                                     (fnil conj [])
                                     (row->message row)))
                           {}
                           events)]
    (mapv (fn [row]
            (row->session row (get by-session (:id row) [])))
          rows)))

(defn list-session-summaries
  "Like [[list-sessions]] but returns only metadata (no histories), suitable
  for session pickers and other lightweight listings."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'session-summary]]}
  [client]
  (mapv row->summary
        (postgres/exec! (:datasource (ready client)) ::list-session-summaries
                        list-sessions-sql [])))

;; ---------------------------------------------------------------------------
;; Session history
;; ---------------------------------------------------------------------------

(defn session-history
  "Return the message history vector for `session-id`, or `[]` when the
  session is unknown or has no events."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:vector #'message]]}
  [client session-id]
  (if-let [id (session-key session-id)]
    (history-from (:datasource (ready client)) id)
    []))

(defn append-history
  "Append `msg` to session `session-id`'s event log. Returns the message that
  was appended, or nil (with a warning) when the session is unknown."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string #'message
                  [:maybe #'message]]}
  [client session-id msg]
  (postgres/validate! message msg "append-history")
  (let [client (ready client)
        result (when-let [id (session-key session-id)]
                 (postgres/exec-one!
                  (:datasource client) ::append-history
                  insert-event-if-session-sql
                  [id
                   (message->event-type msg)
                   (postgres/->json msg "Session message")
                   id]))]
    (if (pos? (postgres/updated-count result))
      (do
        (tel/log! {:level :debug
                   :id    ::history-appended
                   :data  {:sessionId session-id :role (:role msg)}}
                  "Appended message to PostgreSQL session history")
        msg)
      (do
        (warn-unknown-session ::append-history session-id)
        nil))))

(defn clear-history
  "Delete every event in `session-id` and return the updated state map, or nil
  when the session is unknown."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'session]]}
  [client session-id]
  (let [client (ready client)]
    (if-let [id (session-key session-id)]
      (jdbc/with-transaction [tx (:datasource client)]
        (if-let [row (postgres/exec-one! tx ::load-session
                                        select-session-sql [id])]
          (do
            (postgres/exec! tx ::clear-history
                            "DELETE FROM agent_session_event
                            WHERE agent_session_id = ?"
                            [id])
            (row->session row []))
          (do
            (warn-unknown-session ::clear-history session-id)
            nil)))
      (do
        (warn-unknown-session ::clear-history session-id)
        nil))))
