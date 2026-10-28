(ns clai.log
  "Telemere-backed logging for `clai`.

  An ACP agent speaks newline-delimited JSON-RPC over stdout, so **every log
  signal must go to stderr**. Telemere's default console handler writes
  non-error signals to `*out*`, which would corrupt the protocol. Loading this
  namespace replaces that handler with one pinned to `*err*`.

  ## Usage

  Require this namespace for its side effects (preferably before any other
  `clai` namespace that logs), then use Telemere directly:

      (require '[taoensso.telemere :as tel])

      (tel/log! {:level :debug, :id ::request, :data {:url url}}
                \"Sending request\")

      (tel/error! {:id ::request-failed, :data {:url url}} ex)

  `log!`/`event!` default to `:info`; `:debug` and `:trace` signals are hidden
  under Telemere's default `:info` minimum level.

  ## Configuration

  Telemere's standard environmental config works as usual, including
  `TAOENSSO_TELEMERE_RT_MIN_LEVEL` and
  `TAOENSSO_TELEMERE_RT_NS_FILTER`. For convenience, `CLAI_LOG_LEVEL` is also
  honored (for example `CLAI_LOG_LEVEL=debug` when launching `clai`)."
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]))

(defn- env-log-level
  "Return `CLAI_LOG_LEVEL` as a keyword, or nil when unset/blank."
  []
  (some-> (System/getenv "CLAI_LOG_LEVEL")
          (str/trim)
          (not-empty)
          (str/lower-case)
          (keyword)))

(defn init!
  "Install the stderr console handler and apply `CLAI_LOG_LEVEL`, if set.
  Idempotent; called automatically when this namespace loads."
  []
  ;; Telemere's default console handler writes non-error signals to *out*.
  ;; Stdout is reserved for ACP JSON-RPC traffic, so replace it with a handler
  ;; that always writes to stderr.
  (tel/remove-handler! :default/console)
  (tel/add-handler! :clai/console
    (tel/handler:console {:stream :err})
    {:async nil})

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
