(ns clai.state
  "Persistent session storage for `clai`.

  Each ACP session is recorded as a JSON file under
  `~/.local/clai/sessions/`, so the full state of a session -- its
  parameters (`cwd`, model, etc.) and its message history -- survives across
  process restarts and can be inspected or resumed later.

  ## File layout

      ~/.local/clai/sessions/
        <sessionId>.json

  Each file is a single JSON object with the shape:

      {\"sessionId\"  \"<uuid>\"
       \"cwd\"        \"/abs/path\"
       \"model\"      \"gemma3\"        ; optional
       \"params\"     { ... }         ; arbitrary extra session parameters
       \"createdAt\"  \"2024-10-28T...\"
       \"updatedAt\"  \"2024-10-28T...\"
       \"history\"    [{\"role\" \"user\" \"content\" \"...\"} ...]}

  The `history` vector uses the same `{role content}` message maps that the
  Ollama chat API expects.

  ## Thread safety

  Session files are mutated by `append-history`/`set-param` using
  `clojure.core/locking` keyed on the session id, so concurrent updates to a
  single session are serialized. Different sessions use independent files and
  do not contend."
  (:require [clai.log]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.time Instant]
           [java.util UUID]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; Filesystem layout
;; ---------------------------------------------------------------------------

(defn sessions-dir
  "Return the `~/.local/clai/sessions` directory as a `java.io.File`,
  resolving `~` against the user's home directory."
  []
  (let [home (or (System/getenv "HOME")
                 (System/getProperty "user.home"))
        dir  (File. (str home "/.local/clai/sessions"))]
    dir))

(defn ensure-sessions-dir
  "Create the sessions directory (and parents) if it does not already exist.
  Idempotent."
  []
  (let [dir (sessions-dir)]
    (when-not (.exists dir)
      (tel/log! {:level :debug
                 :id    ::create-sessions-dir
                 :data  {:path (.getAbsolutePath dir)}}
                "Creating sessions directory")
      (.mkdirs dir))
    dir))

(defn- session-file
  "Return the JSON file path for `session-id`."
  [session-id]
  (File. (sessions-dir) (str session-id ".json")))

;; ---------------------------------------------------------------------------
;; Serialization helpers
;; ---------------------------------------------------------------------------

(defn- now-iso
  "Current UTC time as an ISO-8601 string."
  []
  (str (Instant/now)))

(defn- read-json-file
  "Parse the contents of `file` as JSON, returning a keyword-keyed map.
  Returns nil if the file does not exist."
  [^File file]
  (when (.exists file)
    (try
      (with-open [r (io/reader file)]
        (json/parse-stream r true))
      (catch Throwable e
        (tel/error! {:id   ::session-read-failed
                     :data {:file (.getAbsolutePath file)}}
                    e)
        (throw e)))))

(defn- write-json-file
  "Write `data` (a map) to `file` as pretty-printed JSON."
  [^File file data]
  (ensure-sessions-dir)
  (try
    (with-open [w (io/writer file)]
      (json/generate-stream data w {:pretty true}))
    (catch Throwable e
      (tel/error! {:id   ::session-write-failed
                   :data {:file      (.getAbsolutePath file)
                          :sessionId (:sessionId data)}}
                  e)
      (throw e))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn new-session-id
  "Generate a fresh random session id (a UUID string)."
  []
  (str (UUID/randomUUID)))

(defn create-session
  "Create and persist a new session, returning its state map.

  Options:

    :cwd     the working directory for the session (required in practice,
             defaults to the JVM's current directory).
    :model   the model name the session chats with (optional).
    :params  a map of any additional session parameters to store.

  The returned map includes `:sessionId`, `:cwd`, `:params`, `:createdAt`,
  `:updatedAt`, and an empty `:history`. The same map shape is returned by
  `load-session` and `list-sessions`."
  ([]
   (create-session nil))
  ([{:keys [cwd model params]
     :or {cwd    (.getCanonicalFile (io/file "."))
          params {}}}]
   (let [id       (new-session-id)
         created  (now-iso)
         state    {:sessionId  id
                   :cwd        (str cwd)
                   :model      model
                   :params     params
                   :createdAt  created
                   :updatedAt  created
                   :history    []}
         file     (session-file id)]
     (write-json-file file state)
     (tel/log! {:level :info
                :id    ::session-created
                :data  {:sessionId id :cwd (str cwd) :model model}}
               "Created persistent session")
     state)))

(defn load-session
  "Read and return the state map for `session-id`, or nil if no such session
  is recorded."
  [session-id]
  (let [state (read-json-file (session-file session-id))]
    (tel/log! {:level :debug
               :id    ::session-loaded
               :data  {:sessionId session-id :found? (some? state)}}
              "Loaded session")
    state))

(defn lookup-session
  "Alias for `load-session`, mirroring the naming used in `clai.core`."
  [session-id]
  (load-session session-id))

(defn save-session
  "Persist `state` (a session map) to its JSON file. Refreshes `:updatedAt`.
  Returns the saved state."
  [state]
  (let [state' (assoc state :updatedAt (now-iso))]
    (write-json-file (session-file (:sessionId state')) state')
    state'))

(defn delete-session
  "Delete the JSON file for `session-id`, if present. Returns true if a file
  was removed, false otherwise."
  [session-id]
  (let [file     (session-file session-id)
        deleted? (if (.exists file) (.delete file) false)]
    (tel/log! {:level :debug
               :id    ::session-deleted
               :data  {:sessionId session-id :deleted? deleted?}}
              "Deleted session file")
    deleted?))

(defn list-sessions
  "Return a vector of all recorded session state maps, sorted by `:createdAt`
  ascending. Each entry is the full state map (including `:history`); use
  `list-session-summaries` for lightweight metadata only."
  []
  (ensure-sessions-dir)
  (let [sessions (->> (sessions-dir)
                      (file-seq)
                      (filter #(and (.isFile %)
                                    (str/ends-with? (.getName %) ".json")))
                      (keep read-json-file)
                      (sort-by :createdAt)
                      (vec))]
    (tel/log! {:level :debug
               :id    ::sessions-listed
               :data  {:count (count sessions)}}
              "Listed sessions")
    sessions))

(defn list-session-summaries
  "Like `list-sessions` but returns only metadata (no `:history`), suitable
  for displaying in a session picker or for ACP `session/list` responses."
  []
  (->> (list-sessions)
       (map #(dissoc % :history))
       (vec)))

(defn session-exists?
  "Return true if a session file for `session-id` is recorded."
  [session-id]
  (some? (load-session session-id)))

;; ---------------------------------------------------------------------------
;; History & parameter mutation
;; ---------------------------------------------------------------------------

(defn- warn-unknown-session
  "Emit a warning for a mutation targeting a session that is not recorded."
  [op session-id]
  (tel/log! {:level :warn
             :id    ::unknown-session
             :data  {:op op :sessionId session-id}}
            "Session not found"))

(defn append-history
  "Append a `{role content}` message to session `session-id` and persist it.
  Returns the message that was appended.

  If the session is unknown, it is treated as a no-op and the message is
  returned unchanged (callers should `create-session` first)."
  [session-id message]
  (locking session-id
    (if-let [state (load-session session-id)]
      (do
        (save-session (update state :history (fnil conj []) message))
        (tel/log! {:level :debug
                   :id    ::history-appended
                   :data  {:sessionId session-id
                           :role      (:role message)}}
                  "Appended message to session history")
        message)
      (do (warn-unknown-session :append-history session-id) nil))))

(defn session-history
  "Return the message history vector for `session-id`, or an empty vector if
  the session is unknown."
  [session-id]
  (or (-> (load-session session-id) :history) []))

(defn set-param
  "Associate `key` with `value` in the session's `:params` map and persist.
  Returns the updated full state map, or nil if the session is unknown."
  [session-id key value]
  (locking session-id
    (if-let [state (load-session session-id)]
      (let [state' (-> state
                       (assoc-in [:params key] value)
                       (save-session))]
        (tel/log! {:level :debug
                   :id    ::set-param
                   :data  {:sessionId session-id :key key}}
                  "Set session parameter")
        state')
      (do (warn-unknown-session :set-param session-id) nil))))

(defn set-params
  "Merge `m` into the session's `:params` map and persist. Returns the
  updated full state map, or nil if the session is unknown."
  [session-id m]
  (locking session-id
    (if-let [state (load-session session-id)]
      (let [state' (-> state
                       (update :params (fnil merge {}) m)
                       (save-session))]
        (tel/log! {:level :debug
                   :id    ::set-params
                   :data  {:sessionId session-id :keys (vec (keys m))}}
                  "Merged session parameters")
        state')
      (do (warn-unknown-session :set-params session-id) nil))))

(defn set-model
  "Record the model name used by `session-id` and persist. Returns the
  updated full state map, or nil if the session is unknown."
  [session-id model]
  (locking session-id
    (if-let [state (load-session session-id)]
      (let [state' (save-session (assoc state :model model))]
        (tel/log! {:level :debug
                   :id    ::set-model
                   :data  {:sessionId session-id :model model}}
                  "Set session model")
        state')
      (do (warn-unknown-session :set-model session-id) nil))))

(defn clear-history
  "Empty the message history for `session-id` and persist. Returns the
  updated full state map, or nil if the session is unknown."
  [session-id]
  (locking session-id
    (if-let [state (load-session session-id)]
      (let [state' (save-session (assoc state :history []))]
        (tel/log! {:level :debug
                   :id    ::clear-history
                   :data  {:sessionId session-id}}
                  "Cleared session history")
        state')
      (do (warn-unknown-session :clear-history session-id) nil))))