(ns clai.test-support
  "Shared helpers for the test suite."
  (:require [clai.log]
            [taoensso.telemere :as tel]))

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
