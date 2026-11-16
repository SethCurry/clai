(ns clai.log
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]))

(defn- env-log-level
  "Return `CLAI_LOG_LEVEL` as a keyword, or nil when unset/blank. The value
  is not validated here; `init!` ignores levels Telemere rejects."
  {:malli/schema [:-> [:maybe :keyword]]}
  []
  (if-let [log-level (some-> (System/getenv "CLAI_LOG_LEVEL")
          (str/trim)
          (not-empty)
          (str/lower-case)
          (keyword))]
  log-level
  :debug))

(defn init!
  "Install the stderr console handler and apply `CLAI_LOG_LEVEL`, if set.
  Idempotent; called automatically when this namespace loads."
  {:malli/schema [:-> :nil]}
  []
  (when-let [level (env-log-level)]
    (try
      (tel/set-min-level! level)
      (catch Throwable e
        (tel/log! {:level :warn
                   :id    ::invalid-log-level
                   :data  {:value (str level)
                           :error (or (ex-message e) (str (class e)))}}
                  "Ignoring invalid CLAI_LOG_LEVEL"))))
  nil)

(defonce ^:private _init
  ;; Run once per process; reloading this namespace keeps the existing
  ;; handler rather than stacking duplicates.
  (init!))
