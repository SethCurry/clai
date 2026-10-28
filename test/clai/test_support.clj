(ns clai.test-support
  "Shared helpers for the test suite."
  (:require [clai.log]
            [taoensso.telemere :as tel]))

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
