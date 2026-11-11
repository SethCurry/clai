(ns clai.test-support
  "Shared helpers for the test suite."
  (:require [clai.daemon.postgres :as pg]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.instrument :as instrument]
            [clai.log]
            [taoensso.telemere :as tel])
  (:import [java.io File]
           [java.time Instant]
           [java.util UUID]))

(def ^:private agent-threads
  "Threads started by `track-thread!`, awaiting `join-agent-threads!`."
  (atom []))

(defn track-thread!
  "Register `thread` so `join-agent-threads!` will wait on it. Returns
  `thread` unchanged."
  [thread]
  (swap! agent-threads conj thread)
  thread)

(defn join-agent-threads!
  "Join every tracked thread (best-effort, 1s each) and clear the list.
  Background agent threads log `::agent-stop` as they wind down; joining
  them keeps that output inside the suppressing `quiet-logs` window
  instead of leaking into the next test namespace."
  []
  (let [threads (deref agent-threads)]
    (reset! agent-threads [])
    (doseq [^Thread t threads]
      (try (.join t 1000) (catch Throwable _)))))

(defn join-agent-threads
  "`clojure.test` fixture that joins tracked agent threads after the test."
  [f]
  (try
    (f)
    (finally
      (join-agent-threads!))))

(defn quiet-logs
  "Fixture that suppresses Telemere output while tests run.

  Several tests deliberately trigger error paths, so their expected log
  signals would otherwise clutter the test report. The previous runtime
  minimum level is restored afterwards."
  [f]
  (let [previous (:runtime (tel/get-min-levels))]
    (tel/set-min-level! :fatal)
    (try
      (f)
      (finally
        (tel/set-min-level! (or previous :info))))))

(defn instrument
  "Fixture that enables Malli instrumentation for the test's duration."
  [f]
  (instrument/start!)
  (try
    (f)
    (finally
      (instrument/stop!))))

;; ---------------------------------------------------------------------------
;; In-memory session store
;; ---------------------------------------------------------------------------
;;
;; `clai.daemon.postgres.sessions` is the session store the agent uses. Unit
;; tests should not need a live PostgreSQL server, so the fixture below replaces
;; the session functions in `clai.daemon.postgres.sessions` with an equivalent
;; atom-backed store.

(defn- memory-now
  "Current UTC time as an ISO-8601 string, like the PostgreSQL store writes."
  []
  (str (Instant/now)))

(defn- memory-normalize-session
  "Normalize a session the way `clai.daemon.postgres.sessions/save-session`
  does."
  [state]
  (-> state
      (assoc :history (vec (or (:history state) [])))
      (update :cwd #(cond
                      (instance? File %) (str %)
                      (string? %) %
                      :else (str (.getCanonicalFile (File. ".")))))
      (cond-> (not (contains? state :createdAt)) (assoc :createdAt (memory-now)))))

(declare ^:private memory-save-session)

(defn- memory-create-session
  ([store client] (memory-create-session store client nil))
  ([store _client {:keys [cwd]}]
   (memory-save-session store nil
                        (memory-normalize-session {:cwd     cwd
                                                   :history []}))))

(defn- memory-load-session
  [store _client session-id]
  (get @store (str session-id)))

(defn- memory-save-session
  "Store a session, creating a row when it carries no `:sessionId` and
  returning nil for an unknown one, like the PostgreSQL store."
  [store _client state]
  (let [id    (some-> (:sessionId state) str)
        saved (memory-normalize-session state)]
    (cond
      (nil? id)
      (let [saved (assoc saved :sessionId (str (UUID/randomUUID)))]
        (swap! store assoc (:sessionId saved) saved)
        saved)

      (contains? @store id)
      (do (swap! store assoc id saved) saved)

      :else nil)))

(defn- memory-delete-session
  [store _client session-id]
  (let [existed? (contains? @store (str session-id))]
    (swap! store dissoc (str session-id))
    existed?))

(defn- memory-list-sessions
  [store _client]
  (->> @store vals (sort-by :createdAt) vec))

(defn- memory-list-session-summaries
  [store _client]
  (mapv #(dissoc % :history) (memory-list-sessions store nil)))

(defn- memory-session-exists?
  [store _client session-id]
  (contains? @store (str session-id)))

(defn- memory-mutate
  "Apply `f` to session `session-id` and return the saved session, or nil when
  it is unknown."
  [store session-id f]
  (let [result (atom nil)]
    (swap! store
           (fn [all]
             (if-let [session (get all (str session-id))]
               (let [saved (f session)]
                 (reset! result saved)
                 (assoc all (str session-id) saved))
               all)))
    @result))

(defn- memory-append-history
  [store _client session-id msg]
  (when (memory-mutate store session-id
                       #(update % :history (fnil conj []) msg))
    msg))

(defn- memory-session-history
  [store _client session-id]
  (or (get-in @store [(str session-id) :history]) []))

(defn- memory-clear-history
  [store _client session-id]
  (memory-mutate store session-id #(assoc % :history [])))

(defn memory-session-store
  "Fixture that installs an in-memory session store in place of the
  `clai.daemon.postgres.sessions` functions, so tests do not need a live
  database. The store is fresh for every test."
  [f]
  (let [store (atom {})
        bind  (fn [mem-fn] (partial mem-fn store))]
    (with-redefs [pg/ensure-schema!               (constantly nil)
                  sessions/create-session         (bind memory-create-session)
                  sessions/load-session           (bind memory-load-session)
                  sessions/lookup-session         (bind memory-load-session)
                  sessions/save-session           (bind memory-save-session)
                  sessions/delete-session         (bind memory-delete-session)
                  sessions/list-sessions          (bind memory-list-sessions)
                  sessions/list-session-summaries (bind memory-list-session-summaries)
                  sessions/session-exists?        (bind memory-session-exists?)
                  sessions/append-history         (bind memory-append-history)
                  sessions/session-history        (bind memory-session-history)
                  sessions/clear-history          (bind memory-clear-history)]
      (f))))
