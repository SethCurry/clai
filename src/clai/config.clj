(ns clai.config
  "Load agent configuration for `clai` from an EDN config file.

  The config file is plain EDN (https://github.com/edn-format/edn) and may
  contain any subset of the recognized keys; anything omitted falls back to
  the usual environment variables / defaults documented in this namespace
  and `clai.providers.ollama`.

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

  `:tools` is a vector of tool ids (see `clai.tools/built-in-tools`) to
  enable. When omitted, every tool in the agent's registry is enabled; when
  present but empty, no tools are offered to the model.

  ## File discovery

  The first existing file is used, searched in this order:

    1. the path given by the `CLAI_CONFIG` environment variable
    2. `./clai.edn` in the current working directory
    3. `~/.config/clai/config.edn`

  If none of these exist, an empty map is returned and `clai` behaves exactly
  as it did before config-file support (env vars + defaults)."
  (:require [clai.log]
            [clai.providers.ollama :as ollama]
            [clai.tools :as tools]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  [:re #".+"])

(def config-file
  "A `java.io.File` naming a config file."
  [:fn #(instance? File %)])

(def ollama-config
  "The `:ollama` sub-map of a config file, forwarded to
  `clai.providers.ollama/make-client`."
  [:map
   [:host {:optional true} :string]
   [:api-key {:optional true} [:maybe :string]]
   [:timeout {:optional true} pos-int?]])

(def config
  "An agent config map as returned by `load-config`. Every key is optional
  (omitted keys fall back to environment variables and defaults), and unknown
  keys are allowed."
  [:map
   [:model {:optional true} non-empty-string]
   [:system-prompt {:optional true} :string]
   [:max-tool-rounds {:optional true} pos-int?]
   [:think {:optional true} boolean?]
   [:options {:optional true} [:map]]
   [:format {:optional true} [:or :string [:map]]]
   [:ollama {:optional true} ollama-config]
   [:tools {:optional true} [:sequential :string]]])

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
      (tel/error! {:id   ::config-read-failed
                   :data {:file (.getAbsolutePath file)}}
                  e)
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
  {:malli/schema [:-> [:sequential :string]]}
  []
  (map #(.getAbsolutePath ^File %) (candidate-files)))

(defn config-path
  "Return the absolute path of the config file that *would* be loaded (the
  first existing candidate), or nil when no candidate file is present."
  {:malli/schema [:-> [:maybe :string]]}
  []
  (some-> ^File (first-existing (candidate-files))
          (.getAbsolutePath)))

(defn load-config
  "Load and return the agent config map.

  With no arguments, discovers the first existing config file (see the
  namespace docs) and parses it. Pass an explicit `path` to read a specific
  file instead. Returns an empty map when no file is found so callers can
  treat the result as a sparse overlay on top of the built-in defaults."
  {:malli/schema [:function
                  [:=> [:cat] #'config]
                  [:=> [:cat [:or #'config-file :string]] #'config]]}
  ([]
   (if-let [^File f (first-existing (candidate-files))]
     (load-config f)
     (do
       (tel/log! {:level :debug
                  :id    ::no-config-file
                  :data  {:candidates (vec (candidates))}}
                 "No clai config file found; using defaults")
       {})))
  ([path]
   (let [^File f (if (instance? File path) path (File. (str path)))]
     (cond
       (not (.exists f))
       (do
         (tel/log! {:level :debug
                    :id    ::config-not-found
                    :data  {:path (.getAbsolutePath f)}}
                   "Config file does not exist; using defaults")
         {})

       (.isDirectory f)
       (do
         (tel/log! {:level :warn
                    :id    ::config-is-directory
                    :data  {:path (.getAbsolutePath f)}}
                   "Config path is a directory; using defaults")
         {})

       :else
       (let [parsed (read-edn-file f)]
         (if (map? parsed)
           (do
             (tel/log! {:level :debug
                        :id    ::config-loaded
                        :data  {:path (.getAbsolutePath f)
                                :keys (vec (keys parsed))}}
                       "Loaded clai config")
             parsed)
           (do
             (tel/log! {:level :warn
                        :id    ::config-not-a-map
                        :data  {:path (.getAbsolutePath f)
                                :type (str (class parsed))}}
                       "Config file did not contain a map; using defaults")
             {})))))))

;; ---------------------------------------------------------------------------
;; Runtime settings
;; ---------------------------------------------------------------------------

(def ^:private default-model
  "Model used for chat when neither `cfg` nor `CLAI_MODEL` names one."
  "gemma3")

(def ^:private default-max-tool-rounds 8)

(defn resolve-model
  "Resolve the model to chat with. Precedence: `cfg`'s `:model`, then the
  `CLAI_MODEL` environment variable, then the built-in default."
  {:malli/schema [:-> #'config [:maybe :string]]}
  [cfg]
  (or (:model cfg)
      (System/getenv "CLAI_MODEL")
      default-model))

(defn resolve-max-tool-rounds
  "Resolve the cap on consecutive tool-call rounds per prompt. Precedence:
  `cfg`'s `:max-tool-rounds`, then `CLAI_MAX_TOOL_ROUNDS`, then the default."
  {:malli/schema [:-> #'config pos-int?]}
  [cfg]
  (or (some-> cfg :max-tool-rounds)
      (some-> (System/getenv "CLAI_MAX_TOOL_ROUNDS")
              (Integer/parseInt))
      default-max-tool-rounds))

(defn make-ollama-client
  "Build the Ollama client described by `cfg`. The `:ollama` sub-map (with
  `:host`, `:api-key`, `:timeout`) is forwarded to `ollama/make-client`, and
  any key not supplied there falls back to the environment (`OLLAMA_HOST`,
  `OLLAMA_API_KEY`) and defaults."
  {:malli/schema [:-> #'config #'ollama/client-schema]}
  [cfg]
  (let [ollama-cfg (:ollama cfg)
        opts       (into {}
                         (keep (fn [k]
                                 (when-some [v (get ollama-cfg k)]
                                   [k v])))
                         [:host :api-key :timeout])]
    (ollama/make-client opts)))

(defn chat-options
  "Return the Ollama `/api/chat` request options carried by `cfg` (only the
  keys that are present)."
  {:malli/schema [:-> #'config [:map]]}
  [cfg]
  (into {}
        (keep (fn [k]
                (when-some [v (get cfg k)]
                  [k v])))
        [:options :think :format]))

(defn tool-ids
  "Return the tool ids selected by `cfg`, or nil when every tool in the
  agent's registry should be enabled."
  {:malli/schema [:-> #'config [:maybe [:sequential :string]]]}
  [cfg]
  (when (contains? cfg :tools)
    (:tools cfg)))

(defn tool-specs
  "Resolve the vector of Ollama tool definitions to offer the model from
  `tool-registry`. When `cfg` has a `:tools` key, only the listed ids are
  enabled (in the given order; unknown ids are skipped). When `:tools` is
  absent, every tool in `tool-registry` is offered.

  The one-argument arity uses a fresh built-in registry and is retained for
  callers that have not yet built their own per-agent registry; agents should
  pass the registry they own via the two-argument arity."
  {:malli/schema [:function
                  [:=> [:cat #'config] [:vector #'tools/tool-spec]]
                  [:=> [:cat #'config #'tools/tool-registry]
                   [:vector #'tools/tool-spec]]]}
  ([cfg]
   (tool-specs cfg (tools/built-in-registry)))
  ([cfg tool-registry]
   (let [ids      (tool-ids cfg)
         selected (tools/select-tools tool-registry ids)]
     (if (nil? ids)
       (tools/specs selected)
       (tools/specs-for selected ids)))))
