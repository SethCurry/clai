(ns clai.config
  "Load agent configuration for `clai` from an EDN config file.

  The config file is plain EDN (https://github.com/edn-format/edn) and may
  contain any subset of the recognized keys; anything omitted falls back to
  the usual environment variables / defaults documented in `clai.core`.

  ## Recognized keys

      {:model          \"gemma3\"            ; name sent to /api/chat
       :system-prompt  \"You are a ...\"     ; optional system message
       :max-tool-rounds 8                    ; per-prompt tool-call round cap
       :think          false                 ; pass :think to Ollama chat
       :options        {:temperature 0.7}    ; Ollama model options map
       :format         \"json\"              ; optional Ollama :format
       :ollama         {:host     \"http://localhost:11434\"
                        :api-key  \"sk-...\"
                        :timeout  600000}    ; ms
       :tools          [\"read_file\" \"write_file\" ...]}

  `:tools` is a vector of built-in tool ids (see `clai.tools/built-in-tools`)
  to enable. When omitted, every built-in tool is enabled; when present but
  empty, no tools are offered to the model.

  ## File discovery

  The first existing file is used, searched in this order:

    1. the path given by the `CLAI_CONFIG` environment variable
    2. `./clai.edn` in the current working directory
    3. `~/.config/clai/config.edn`

  If none of these exist, an empty map is returned and `clai` behaves exactly
  as it did before config-file support (env vars + defaults)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Candidate file locations
;; ---------------------------------------------------------------------------

(defn- env-config-path
  "Return the path from `CLAI_CONFIG`, or nil if unset."
  []
  (System/getenv "CLAI_CONFIG"))

(defn- cwd-config-file
  "Return `./clai.edn` as a `java.io.File`."
  []
  (File. "clai.edn"))

(defn- home-config-file
  "Return `~/.config/clai/config.edn` as a `java.io.File`."
  []
  (let [home (or (System/getenv "XDG_CONFIG_HOME")
                 (System/getenv "HOME")
                 (System/getProperty "user.home"))]
    (File. (str home "/.config/clai/config.edn"))))

(defn- candidate-files
  "Return the ordered list of candidate config files. `nil` entries (e.g. an
  unset `CLAI_CONFIG`) are dropped."
  []
  (keep identity
        [(when-let [p (env-config-path)] (File. ^String p))
         (cwd-config-file)
         (home-config-file)]))

(defn- first-existing
  "Return the first `File` in `files` that exists and is a regular file, or
  nil."
  [files]
  (->> files
       (filter (fn [^File f] (and (.exists f) (.isFile f))))
       first))

;; ---------------------------------------------------------------------------
;; Parsing
;; ---------------------------------------------------------------------------

(defn- read-edn-file
  "Parse `file` as EDN and return the resulting map. Throws an `ex-info`
  with the file path on failure so a malformed config is easy to spot."
  [^File file]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader file))]
      (edn/read {:readers *data-readers* :eof nil} r))
    (catch Throwable e
      (throw (ex-info (str "Failed to read clai config file "
                           (.getAbsolutePath file) ": "
                           (or (ex-message e) (class e)))
                      {:file file :cause e})))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn candidates
  "Return the ordered list of candidate config file paths (as absolute
  strings), for diagnostics. Skips the `CLAI_CONFIG` entry when that env var
  is unset."
  []
  (map #(.getAbsolutePath ^File %) (candidate-files)))

(defn config-path
  "Return the absolute path of the config file that *would* be loaded (the
  first existing candidate), or nil when no candidate file is present."
  []
  (some-> ^File (first-existing (candidate-files))
          (.getAbsolutePath)))

(defn load-config
  "Load and return the agent config map.

  With no arguments, discovers the first existing config file (see the
  namespace docs) and parses it. Pass an explicit `path` to read a specific
  file instead. Returns an empty map when no file is found so callers can
  treat the result as a sparse overlay on top of the built-in defaults."
  ([]
   (if-let [^File f (first-existing (candidate-files))]
     (load-config f)
     {}))
  ([path]
   (let [^File f (if (instance? File path) path (File. (str path)))]
     (cond
       (not (.exists f)) {}
       (.isDirectory f)  {}
       :else             (let [parsed (read-edn-file f)]
                           (if (map? parsed) parsed {}))))))