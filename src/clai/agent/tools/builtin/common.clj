(ns clai.agent.tools.builtin.common
  "Shared helpers and limits for the built-in tools."
  (:require [clojure.java.io :as io]))

(def max-output-bytes
  "Cap on the number of bytes read into a tool result, to keep history small."
  (* 64 1024))

(defn resolve-path
  "Resolve `path` against the session `cwd`. Absolute paths are returned
  unchanged. Returns a `java.io.File`."
  [path ctx]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (io/file (or (:cwd ctx) ".") path))))
