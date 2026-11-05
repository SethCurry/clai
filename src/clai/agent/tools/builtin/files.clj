(ns clai.agent.tools.builtin.files
  "Built-in filesystem tools: `read_file`, `write_file`, and
  `list_directory`. Paths are resolved against the session `:cwd` unless they
  are absolute."
  (:require [clai.agent.tools.builtin.common :as common]
            [clai.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Filesystem tools
;; ---------------------------------------------------------------------------

(defn read-file-fn
  "Tool: read the contents of a file (UTF-8), truncating past 64 KiB."
  [{:keys [path]} ctx]
  (let [^File f   (common/resolve-path path ctx)
        max-bytes common/max-output-bytes]
    (cond
      (not (.exists f)) (str "Error: no such file: " path)
      (.isDirectory f)  (str "Error: not a file (is a directory): " path)
      :else
      (with-open [in (io/input-stream f)]
        (let [{:keys [text truncated?]} (util/read-text in max-bytes)]
          (cond-> text
            truncated? (str "\n... [truncated at " max-bytes " bytes]")))))))

(defn write-file-fn
  "Tool: write `content` to `path`, creating parent directories as needed."
  [{:keys [path content]} ctx]
  (let [^File f (common/resolve-path path ctx)]
    (.. f (getParentFile) (mkdirs))
    (spit f (str content))
    (str "Wrote " (.length f) " bytes to " (.getPath f))))

(defn list-directory-fn
  "Tool: list the entries of a directory (files and subdirectories)."
  [{:keys [path]} ctx]
  (let [^File f (common/resolve-path (or path ".") ctx)]
    (cond
      (not (.exists f))   (str "Error: no such directory: " path)
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
    "Read the UTF-8 text contents of a file at `path` (relative to the session
    working directory, or absolute). Returns up to 64 KiB of text."
    :parameters
    {:type "object"
     :properties
     {"path" {:type "string"
              :description "Path to the file to read."}}
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
  []
  [{:id "read_file"      :handler read-file-fn      :spec file-read-spec}
   {:id "write_file"     :handler write-file-fn     :spec file-write-spec}
   {:id "list_directory" :handler list-directory-fn :spec list-directory-spec}])
