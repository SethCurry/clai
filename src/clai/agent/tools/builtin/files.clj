(ns clai.agent.tools.builtin.files
  "Built-in filesystem tools: `read_file`, `write_file`, and
  `list_directory`. Paths are resolved against the session `:cwd` unless they
  are absolute.

  `read_file` can return part of a file rather than all of it: `start_line` and
  `end_line` are optional 1-based inclusive line numbers, so a model can walk
  through a long file a range at a time instead of pulling the whole thing into
  the session history."
  (:require [clai.agent.tools.builtin.common :as common]
            [clai.agent.tools.schema :as schema]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedReader File]))

;; ---------------------------------------------------------------------------
;; Line ranges
;; ---------------------------------------------------------------------------

(def ^:private max-output-chars
  "How much text one `read_file` call returns, so a single file cannot flood the
  session history. The same cap the other tools use for their output, counted in
  characters here because the file is read as lines of text."
  common/max-output-bytes)

(defn- given?
  "True when an optional tool argument was actually supplied, i.e. it is neither
  nil nor a blank string. A model that fills in an unused JSON field with `\"\"`
  means \"not given\" rather than an invalid line number."
  {:malli/schema [:-> :any :boolean]}
  [value]
  (not (or (nil? value) (str/blank? (str value)))))

(defn- optional-line
  "Coerce an optional `start_line`/`end_line` argument to a positive line
  number, or nil when it was not given or is not one. JSON numbers arrive as
  numbers and JSON strings as strings, so both are accepted."
  {:malli/schema [:-> :any [:maybe pos-int?]]}
  [value]
  (when (given? value)
    (common/positive-int value)))

(defn- line-range
  "Work out the 1-based inclusive range `[start end]` of lines to read from the
  optional `start_line` and `end_line` arguments, where a nil `end` means \"to
  the end of the file\".

  Returns `{:start n :end n}`, or `{:error <message>}` when a value is not a
  positive line number or the two contradict each other."
  {:malli/schema [:-> #'schema/tool-args
                  [:map
                   [:start {:optional true} pos-int?]
                   [:end {:optional true} [:maybe pos-int?]]
                   [:error {:optional true} :string]]]}
  [{:keys [start_line end_line]}]
  (let [start (optional-line start_line)
        end   (optional-line end_line)]
    (cond
      (and (given? start_line) (nil? start))
      {:error (str "Error: `start_line` must be a positive line number, not "
                   (pr-str start_line) ".")}

      (and (given? end_line) (nil? end))
      {:error (str "Error: `end_line` must be a positive line number, not "
                   (pr-str end_line) ".")}

      (and end (< end (or start 1)))
      {:error (str "Error: `end_line` " end " is before `start_line` "
                   (or start 1) ".")}

      :else
      {:start (or start 1) :end end})))

(defn- read-line-range
  "Read the 1-based inclusive line range [`start` `end`] from `reader`, where a
  nil `end` means \"to the end of the file\".

  The lines are joined with newlines and reading stops as soon as `max-chars`
  characters have been collected, so a huge range costs no more than a small
  one. The selected lines are returned verbatim. Returns

    {:text       the selected lines
     :truncated? true when the character cap cut the range short
     :total      how many lines the file has, once reading reached its end}"
  {:malli/schema [:-> :any pos-int? [:maybe pos-int?] nat-int?
                  [:map
                   [:text :string]
                   [:truncated? :boolean]
                   [:total {:optional true} nat-int?]]]}
  [^BufferedReader reader start end max-chars]
  (loop [line 1
         kept []
         chars 0]
    (if (and end (> line end))
      {:text (str/join "\n" kept) :truncated? false}
      (let [text (.readLine reader)]
        (cond
          (nil? text)
          {:text (str/join "\n" kept) :truncated? false :total (dec line)}

          (< line start)
          (recur (inc line) kept chars)

          :else
          (let [sep  (if (empty? kept) 0 1)
                room (- max-chars chars sep)]
            (if (< room (count text))
              {:text       (str/join "\n" (conj kept (subs text 0 (max 0 room))))
               :truncated? true}
              (recur (inc line)
                     (conj kept text)
                     (+ chars sep (count text))))))))))

;; ---------------------------------------------------------------------------
;; Filesystem tools
;; ---------------------------------------------------------------------------

(defn read-file-fn
  "Tool: read the UTF-8 text of a file, optionally limited to a range of lines.

  `start_line` and `end_line` are 1-based and inclusive. With neither, the whole
  file is read; with only `start_line`, everything from that line to the end of
  the file; with both, just that range. At most [[max-output-chars]] characters
  are returned, so a long file is best read a range at a time."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [path] :as args} ctx]
  (let [^File f (common/resolve-path path ctx)]
    (cond
      (not (.exists f)) (str "Error: no such file: " path)
      (.isDirectory f)  (str "Error: not a file (is a directory): " path)
      :else
      (let [{:keys [start end error]} (line-range args)]
        (if error
          error
          (let [{:keys [text truncated? total]}
                (with-open [^BufferedReader reader (io/reader f)]
                  (read-line-range reader start end max-output-chars))]
            (cond
              (and total (< total start))
              (str "Error: " path " has " total " line"
                   (when (not= 1 total) "s")
                   ", so there is no line " start ".")

              truncated?
              (str text "\n... [truncated at " max-output-chars
                   " characters; pass `start_line` and `end_line` to read on]")

              :else text)))))))

(defn write-file-fn
  "Tool: write `content` to `path`, creating parent directories as needed."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [path content]} ctx]
  (let [^File f (common/resolve-path path ctx)]
    (.. f (getParentFile) (mkdirs))
    (spit f (str content))
    (str "Wrote " (.length f) " bytes to " (.getPath f))))

(defn list-directory-fn
  "Tool: list the entries of a directory (files and subdirectories)."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [path]} ctx]
  (let [^File f (common/resolve-path (or path ".") ctx)]
    (cond
      (not (.exists f))      (str "Error: no such directory: " path)
      (not (.isDirectory f)) (str "Error: not a directory: " path)
      :else
      (->> (.listFiles f)
           (sort-by (fn [^File x] (.getName x)))
           (map (fn [^File x]
                  (str (if (.isDirectory x) "d " "- ") (.getName x))))
           (str/join "\n")))))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def ^:private file-read-spec
  {:type "function"
   :function
   {:name "read_file"
    :description
    (str "Read the UTF-8 text contents of a file at `path` (relative to the"
         " session working directory, or absolute), returning at most "
         max-output-chars " characters. `start_line` and `end_line` are"
         " optional 1-based, inclusive line numbers that limit the read to that"
         " range of lines; with only `start_line`, everything from that line to"
         " the end of the file is returned, and with neither the whole file is"
         " read. Use a range to read a long file in pieces instead of pulling"
         " all of it into the conversation.")
    :parameters
    {:type "object"
     :properties
     {"path"       {:type "string"
                    :description "Path to the file to read."}
      "start_line" {:type "integer"
                    :description (str "First line to read, 1-based. Defaults to"
                                      " the first line of the file.")}
      "end_line"   {:type "integer"
                    :description (str "Last line to read, 1-based and inclusive."
                                      " Defaults to the last line of the file.")}}
     :required ["path"]}}})

(def ^:private file-write-spec
  {:type "function"
   :function
   {:name "write_file"
    :description
    "Write `content` to `path` (relative to the session working directory, or
    absolute), creating parent directories as needed. Overwrites existing
    files."
    :parameters
    {:type "object"
     :properties
     {"path"    {:type "string"
                 :description "Path to the file to write."}
      "content" {:type "string"
                 :description "Full text contents to write."}}
     :required ["path" "content"]}}})

(def ^:private list-directory-spec
  {:type "function"
   :function
   {:name "list_directory"
    :description
    "List the entries of a directory at `path` (relative to the session
    working directory, or absolute). Each line is prefixed with `d ` for a
    directory or `- ` for a file."
    :parameters
    {:type "object"
     :properties
     {"path" {:type "string"
              :description "Directory to list. Defaults to the cwd."}}
     :required []}}})

(defn tools
  "Return the built-in filesystem tool maps."
  {:malli/schema [:-> [:vector #'schema/Tool]]}
  []
  [{:id "read_file"      :handler read-file-fn      :spec file-read-spec}
   {:id "write_file"     :handler write-file-fn     :spec file-write-spec}
   {:id "list_directory" :handler list-directory-fn :spec list-directory-spec}])
