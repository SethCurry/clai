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
;; `clai.agent.state` delegates to `clai.daemon.postgres.sessions`. Unit tests
;; should not need a live PostgreSQL server, so the fixture below replaces the
;; session functions in `clai.daemon.postgres.sessions` with an equivalent
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
      (update :params #(or % {}))
      (cond-> (not (contains? state :model)) (assoc :model nil))
      (assoc :updatedAt (memory-now))))

(defn- memory-create-session
  ([store client] (memory-create-session store client nil))
  ([store _client {:keys [cwd model params]}]
   (let [created (memory-now)
         session {:sessionId (str (UUID/randomUUID))
                  :cwd       (cond
                               (instance? File cwd) (str cwd)
                               (string? cwd) cwd
                               :else (str (.getCanonicalFile (File. "."))))
                  :model     model
                  :params    (or params {})
                  :createdAt created
                  :updatedAt created
                  :history   []}]
     (swap! store assoc (:sessionId session) session)
     session)))

(defn- memory-load-session
  [store _client session-id]
  (get @store session-id))

(defn- memory-save-session
  [store _client state]
  (let [saved (memory-normalize-session state)]
    (swap! store assoc (:sessionId saved) saved)
    saved))

(defn- memory-delete-session
  [store _client session-id]
  (let [existed? (contains? @store session-id)]
    (swap! store dissoc session-id)
    existed?))

(defn- memory-register-session
  [store _client id cwd history]
  (let [created (memory-now)
        session {:sessionId id
                 :cwd       (or cwd ".")
                 :model     nil
                 :params    {}
                 :createdAt created
                 :updatedAt created
                 :history   (vec (or history []))}]
    (swap! store assoc id session)
    session))

(defn- memory-list-sessions
  [store _client]
  (->> @store vals (sort-by :createdAt) vec))

(defn- memory-list-session-summaries
  [store _client]
  (mapv #(dissoc % :history) (memory-list-sessions store nil)))

(defn- memory-session-exists?
  [store _client session-id]
  (contains? @store session-id))

(defn- memory-mutate
  "Apply `f` to session `session-id`, refresh `:updatedAt`, and return the
  saved session, or nil when it is unknown."
  [store session-id f]
  (let [result (atom nil)]
    (swap! store
           (fn [all]
             (if-let [session (get all session-id)]
               (let [saved (-> (f session)
                               (assoc :updatedAt (memory-now)))]
                 (reset! result saved)
                 (assoc all session-id saved))
               all)))
    @result))

(defn- memory-append-history
  [store _client session-id msg]
  (when (memory-mutate store session-id
                       #(update % :history (fnil conj []) msg))
    msg))

(defn- memory-session-history
  [store _client session-id]
  (or (get-in @store [session-id :history]) []))

(defn- memory-set-param
  [store _client session-id key value]
  (memory-mutate store session-id #(assoc-in % [:params key] value)))

(defn- memory-set-params
  [store _client session-id m]
  (memory-mutate store session-id #(update % :params (fnil merge {}) m)))

(defn- memory-set-model
  [store _client session-id model]
  (memory-mutate store session-id #(assoc % :model model)))

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
                  sessions/register-session       (bind memory-register-session)
                  sessions/list-sessions          (bind memory-list-sessions)
                  sessions/list-session-summaries (bind memory-list-session-summaries)
                  sessions/session-exists?        (bind memory-session-exists?)
                  sessions/append-history         (bind memory-append-history)
                  sessions/session-history        (bind memory-session-history)
                  sessions/set-param              (bind memory-set-param)
                  sessions/set-params             (bind memory-set-params)
                  sessions/set-model              (bind memory-set-model)
                  sessions/clear-history          (bind memory-clear-history)]
      (f))))
