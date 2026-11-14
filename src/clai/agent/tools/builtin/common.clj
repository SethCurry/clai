(ns clai.agent.tools.builtin.common
  "Shared helpers and limits for the built-in tools."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

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

(defn format-timestamp
  "Render an ISO-8601 timestamp as `YYYY-MM-DD HH:MM UTC`, or nil when `value`
  is blank.

  The timestamps the tools receive are already UTC -- PostgreSQL serializes
  `timestamptz` to UTC and OpenProject reports UTC -- so the value is labelled
  rather than converted."
  {:malli/schema [:-> :any [:maybe :string]]}
  [value]
  (let [text (str value)]
    (when-not (str/blank? text)
      (str (-> (subs text 0 (min 16 (count text)))
               (str/replace "T" " "))
           " UTC"))))

(defn truncate
  "Truncate `text` to at most `max-chars` characters, appending `...` when it
  was shortened."
  {:malli/schema [:-> :any nat-int? :string]}
  [text max-chars]
  (let [text (str text)]
    (if (> (count text) max-chars)
      (str (subs text 0 max-chars) "...")
      text)))
