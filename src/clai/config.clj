(ns clai.config
  "Load agent configuration for `clai` from an EDN config file.

  The config file is plain EDN (https://github.com/edn-format/edn) and may
  contain any subset of the recognized keys; anything omitted falls back to
  the usual environment variables / defaults documented in this namespace,
  `clai.agent.providers.ollama`, `clai.agent.providers.openrouter`, and
  `clai.daemon.postgres`.

  ## Recognized keys

      {:model          \"gemma3\"            ; model to chat with
       :provider       :ollama               ; provider for unprefixed models
       :system-prompt  \"You are a ...\"     ; optional system message
       :max-tool-rounds 8                    ; per-prompt tool-call round cap
       :think          false                 ; request model reasoning
       :options        {:temperature 0.7}    ; model options map
       :format         \"json\"              ; optional structured output
       :ollama         {:host     \"http://localhost:11434\"
                        :api-key  \"sk-...\"
                        :timeout  600000}    ; ms
       :openrouter     {:api-key  \"sk-or-...\"
                        :base-url \"https://openrouter.ai/api/v1\"
                        :timeout  600000
                        :referer  \"https://example.com\"
                        :title    \"clai\"}
       :postgres       {:host     \"localhost\"
                        :port     5432
                        :database \"clai\"
                        :user     \"clai\"
                        :password \"...\"
                        :ssl-mode \"prefer\"}
       :openproject    {:url      \"https://openproject.example.com\"
                        :api-key  \"opapi-...\"
                        :project  \"my-project\" ; default for new tasks
                        :type     \"Task\"}
       :tools          [\"read_file\" \"write_file\" ...]
       :plugins        [\"todays-date\" ...]}

  `:openproject` configures the OpenProject work-package tools. `:url` (or
  `:base-url`) is the instance root or its `/api/v3` API root. Authenticate
  with `:api-key` (an OpenProject API token, sent as a bearer token) or with
  `:username`/`:password` (HTTP basic auth). `:project` and `:type` are
  optional defaults for newly created work packages; `:timeout` is in
  milliseconds. Omitted values fall back to the `OPENPROJECT_*` environment
  variables documented in `clai.agent.tools.builtin.openproject`.

  ## Models and providers

  `clai` can talk to more than one LLM provider; the model name selects the
  provider (see `clai.agent.providers/model-spec`):

  * `\"openrouter:anthropic/claude-sonnet-4\"` -- the `openrouter:` prefix pins
    OpenRouter and the rest is the model id;
  * `\"ollama:gemma3:7b\"` -- pins the local Ollama server (useful for a model
    whose name contains `/`);
  * `\"anthropic/claude-sonnet-4\"` -- an OpenRouter id, because OpenRouter
    namespaces its models with `/`;
  * `\"gemma3\"` -- no clue in the name, so `:provider` decides
    (`CLAI_PROVIDER` when the file does not say, default `:ollama`).

  Both providers can be configured at once and a single session's stored agent
  configuration can name a model from either one.

  `:tools` is a vector of tool ids (see `clai.agent.tools.builtin/built-in-tools`) to
  enable. When omitted, every tool in the agent's registry is enabled; when
  present but empty, no tools are offered to the model.

  `:plugins` is a vector of plugin names (or database ids) that the agent's
  sessions may run. When omitted, every enabled plugin is active; when present
  but empty, plugins are disabled. A session's stored agent configuration can
  override this list.

  ## File discovery

  The first existing file is used, searched in this order:

    1. the path given by the `CLAI_CONFIG` environment variable
    2. `./clai.edn` in the current working directory
    3. `~/.config/clai/config.edn`

  If none of these exist, an empty map is returned and `clai` behaves exactly
  as it did before config-file support (env vars + defaults)."
  (:require [clai.agent.providers :as providers]
            [clai.agent.tools.builtin :as tools-builtin]
            [clai.agent.tools.registry :as tools-registry]
            [clai.agent.tools.schema :as tools-schema]
            [clai.daemon.postgres :as postgres]
            [clai.log]
            [clai.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  util/non-empty-string)

(def config-file
  "A `java.io.File` naming a config file."
  [:fn #(instance? File %)])

(def ollama-config
  "The `:ollama` sub-map of a config file, forwarded to
  `clai.agent.providers.ollama/make-client`."
  [:map
   [:host {:optional true} :string]
   [:api-key {:optional true} [:maybe :string]]
   [:timeout {:optional true} pos-int?]])

(def openrouter-config
  "The `:openrouter` sub-map of a config file, forwarded to
  `clai.agent.providers.openrouter/make-client`."
  [:map
   [:api-key {:optional true} [:maybe :string]]
   [:base-url {:optional true} non-empty-string]
   [:timeout {:optional true} pos-int?]
   [:referer {:optional true} [:maybe :string]]
   [:title {:optional true} [:maybe :string]]])

(def postgres-config
  "The `:postgres` sub-map of a config file, forwarded to
  `clai.daemon.postgres/make-client`."
  [:map
   [:url {:optional true} non-empty-string]
   [:host {:optional true} non-empty-string]
   [:port {:optional true} [:int {:min 1 :max 65535}]]
   [:database {:optional true} non-empty-string]
   [:user {:optional true} [:maybe :string]]
   [:password {:optional true} [:maybe :string]]
   [:ssl-mode {:optional true}
    [:enum "disable" "allow" "prefer" "require" "verify-ca" "verify-full"]]
   [:connect-timeout {:optional true} pos-int?]
   [:socket-timeout {:optional true} pos-int?]
   [:application-name {:optional true} non-empty-string]])

(def openproject-config
  "The `:openproject` sub-map of a config file, read by the OpenProject
  work-package tools. Omitted keys fall back to `OPENPROJECT_*` environment
  variables."
  [:map
   [:url {:optional true} non-empty-string]
   [:base-url {:optional true} non-empty-string]
   [:api-key {:optional true} [:maybe :string]]
   [:token {:optional true} [:maybe :string]]
   [:username {:optional true} [:maybe :string]]
   [:password {:optional true} [:maybe :string]]
   [:project {:optional true} [:maybe [:or non-empty-string :int]]]
   [:type {:optional true} [:maybe [:or non-empty-string :int]]]
   [:timeout {:optional true} pos-int?]])

(def config
  "An agent config map as returned by `load-config`. Every key is optional
  (omitted keys fall back to environment variables and defaults), and unknown
  keys are allowed."
  [:map
   [:model {:optional true} non-empty-string]
   [:provider {:optional true} [:or providers/Provider non-empty-string]]
   [:system-prompt {:optional true} :string]
   [:max-tool-rounds {:optional true} pos-int?]
   [:think {:optional true} boolean?]
   [:options {:optional true} [:map]]
   [:format {:optional true} [:or :string [:map]]]
   [:ollama {:optional true} ollama-config]
   [:openrouter {:optional true} openrouter-config]
   [:postgres {:optional true} postgres-config]
   [:openproject {:optional true} openproject-config]
   [:tools {:optional true} [:sequential :string]]
   [:plugins {:optional true} [:sequential :string]]])

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
  (io/file "clai.edn"))

(defn- home-config-file
  "Return `~/.config/clai/config.edn` as a `java.io.File`."
  []
  (let [home (or (System/getenv "XDG_CONFIG_HOME")
                 (System/getenv "HOME")
                 (System/getProperty "user.home"))]
    (io/file home ".config/clai/config.edn")))

(defn- candidate-files
  "Return the ordered list of candidate config files. `nil` entries (e.g. an
  unset `CLAI_CONFIG`) are dropped."
  []
  (remove nil?
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
;; Public API
;; ---------------------------------------------------------------------------

(defn- use-defaults!
  "Log why configuration is falling back to the built-in defaults and return
  the empty config overlay."
  [level id data message]
  (tel/log! {:level level :id id :data data} message)
  {})

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
     (use-defaults! :debug ::no-config-file {:candidates (vec (candidates))}
                    "No clai config file found; using defaults")))
  ([path]
   (let [^File f (if (instance? File path) path (File. (str path)))
         path*  (.getAbsolutePath f)]
     (cond
       (not (.exists f))
       (use-defaults! :debug ::config-not-found {:path path*}
                      "Config file does not exist; using defaults")

       (.isDirectory f)
       (use-defaults! :warn ::config-is-directory {:path path*}
                      "Config path is a directory; using defaults")

       :else
       (let [parsed (util/read-edn-file
                     f
                     {:error-id ::config-read-failed
                      :label    "clai config file"
                      :readers  *data-readers*})]
         (if (map? parsed)
           (do
             (tel/log! {:level :debug
                        :id    ::config-loaded
                        :data  {:path path* :keys (vec (keys parsed))}}
                       "Loaded clai config")
             parsed)
           (use-defaults! :warn ::config-not-a-map
                          {:path path* :type (str (class parsed))}
                          "Config file did not contain a map; using defaults")))))))

;; ---------------------------------------------------------------------------
;; Runtime settings
;; ---------------------------------------------------------------------------

(def ^:private default-model
  "Model used for chat when neither `cfg` nor `CLAI_MODEL` names one."
  "gemma3")

(def ^:private default-provider
  "Provider used for a model name that does not name one."
  :ollama)

(def ^:private default-max-tool-rounds 8)

(defn resolve-model
  "Resolve the model to chat with. Precedence: `cfg`'s `:model`, then the
  `CLAI_MODEL` environment variable, then the built-in default.

  The result may carry a `<provider>:` prefix; see
  `clai.agent.providers/model-spec`."
  {:malli/schema [:-> #'config [:maybe :string]]}
  [cfg]
  (or (:model cfg)
      (System/getenv "CLAI_MODEL")
      default-model))

(defn resolve-provider
  "Resolve the provider used for model names that do not name one.
  Precedence: `cfg`'s `:provider`, then `CLAI_PROVIDER`, then `:ollama`.

  A keyword or a string is accepted; an unknown provider name is logged and
  ignored, so a typo costs a warning rather than every prompt."
  {:malli/schema [:-> #'config providers/Provider]}
  [cfg]
  (let [configured (or (:provider cfg) (System/getenv "CLAI_PROVIDER"))
        provider   (cond
                     (keyword? configured) configured
                     (string? configured)  (keyword (str/lower-case
                                                     (str/trim configured)))
                     :else                 nil)]
    (cond
      (nil? provider)              default-provider
      (providers/supported-provider? provider) provider
      :else
      (do (tel/log! {:level :warn
                     :id    ::unknown-provider
                     :data  {:provider configured
                             :supported (providers/providers)}}
                    "Unknown provider; falling back to the default")
          default-provider))))

(defn resolve-max-tool-rounds
  "Resolve the cap on consecutive tool-call rounds per prompt. Precedence:
  `cfg`'s `:max-tool-rounds`, then `CLAI_MAX_TOOL_ROUNDS`, then the default."
  {:malli/schema [:-> #'config pos-int?]}
  [cfg]
  (or (some-> cfg :max-tool-rounds)
      (some-> (System/getenv "CLAI_MAX_TOOL_ROUNDS")
              (Integer/parseInt))
      default-max-tool-rounds))

(defn make-clients
  "Build one client per provider from `cfg`.

  The `:ollama` sub-map is forwarded to `clai.agent.providers.ollama/make-client`
  and `:openrouter` to `clai.agent.providers.openrouter/make-client`; keys
  neither supplies fall back to the environment (`OLLAMA_HOST`,
  `OLLAMA_API_KEY`, `OPENROUTER_API_KEY`, ...) and the client defaults.
  Returns `{:ollama <client> :openrouter <client>}`, ready for
  `clai.agent.providers/model-client`."
  {:malli/schema [:-> #'config providers/Clients]}
  [cfg]
  (providers/make-clients cfg))

(defn make-postgres-client
  "Build the PostgreSQL client described by `cfg`. The `:postgres` sub-map
  (with connection keys such as `:host`, `:port`, `:database`, `:user`,
  `:password`, and `:ssl-mode`) is forwarded to
  `clai.daemon.postgres/make-client`. Omitted keys fall back to the
  `CLAI_POSTGRES_*` / `PG*` environment variables and then the client defaults.

  Creating a client does not open a connection; callers should call
  `clai.daemon.postgres/ensure-schema!` (or use `clai.daemon.postgres/open`)
  before relying on the storage tables."
  {:malli/schema [:-> #'config #'postgres/client-schema]}
  [cfg]
  (postgres/make-client
   (util/select-present (:postgres cfg)
                        [:url :host :port :database :user :password :ssl-mode
                         :connect-timeout :socket-timeout :application-name])))

(defn chat-options
  "Return the provider-neutral chat options carried by `cfg`: `:options`,
  `:think`, and `:format` (only the keys that are present).

  Each provider translates them into its own wire format (Ollama forwards them
  verbatim; OpenRouter maps them onto OpenAI's parameters)."
  {:malli/schema [:-> #'config [:map]]}
  [cfg]
  (util/select-present cfg [:options :think :format]))

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
                  [:=> [:cat #'config] [:vector #'tools-schema/tool-spec]]
                  [:=> [:cat #'config #'tools-schema/ToolRegistry]
                   [:vector #'tools-schema/tool-spec]]]}
  ([cfg]
   (tool-specs cfg (tools-builtin/built-in-registry)))
  ([cfg tool-registry]
   (let [ids      (tool-ids cfg)
         selected (tools-registry/select-tools tool-registry ids)]
     (if (nil? ids)
       (tools-registry/specs selected)
       (tools-registry/specs-for selected ids)))))
