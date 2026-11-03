(ns user
  "REPL helpers for driving the clai agent in-process.

  `clai.log` normally sends Telemere signals to stderr. From this REPL they go
  to a file instead, so log output doesn't interleave with the conversation."
  (:require [clai.log]
            [taoensso.telemere :as tel]))

(def log-file
  "Where this REPL appends Telemere signals."
  "target/logs/clai.log")

(defn log-to-file!
  "Replace Telemere's stderr handler with one that appends signals to `path`.
  Returns the path."
  ([] (log-to-file! log-file))
  ([path]
   (tel/remove-handler! :clai/console)
   (tel/add-handler! :clai/file
                     (tel/handler:file {:path path})
                     {:async nil})
   path))

;; Switch handlers before loading `clai.repl`: loading it creates the default
;; agent, which emits a configuration signal.
(log-to-file!)

(require '[clai.repl :refer :all])
(require '[clai.repl.git :as git])
