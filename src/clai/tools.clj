(ns clai.tools
  "A tiny tool registry for the `clai` agent.

  Ollama's `/api/chat` accepts a `:tools` vector describing function-style
  tools the model may call. When the model decides to call one, the response
  message carries a `:tool_calls` vector; the agent is expected to execute
  each call, append a `{:role \"tool\" ...}` message with the result, and chat
  again. This namespace provides:

    * `register-tool!` / `unregister-tool!` -- mutate the global registry.
    * `spec` / `specs`     -- the Ollama tool definitions, ready to send.
    * `invoke`             -- run a named tool by id with parsed arguments.
    * `execute-calls`      -- run a batch of Ollama `tool_calls`, returning
                              the `tool`-role result messages.
    * `built-in-tools`     -- a default set of filesystem/shell tools an
                              agent can use within a session's `cwd`.

  A tool is a map with three keys:

    :id    the tool name (matches the Ollama `function.name`).
    :fn    a Clojure function `(args ctx) -> string`, where `args` is the
           parsed JSON arguments map and `ctx` carries session context
           (`:cwd`, ...). Returning a string keeps the tool-result message
           simple; non-string return values are pr-str'd.
    :spec  the Ollama tool definition map:
           `{:type \"function\" :function {:name ... :description ...
            :parameters {:type \"object\" :properties {...} :required [...]}}}`

  Tool execution never throws out to the caller: if a tool function raises,
  the exception message is returned as the tool result so the model can react.
  "
  (:require [clai.log]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

;; `{id {:id :fn :spec}}`. The default registry is populated with
;; `built-in-tools` below; callers may add or replace entries.
(def ^:private registry (atom {}))

(defn register-tool!
  "Register `tool` (a map with `:id`, `:fn`, `:spec`) in the global registry,
  replacing any existing tool with the same id. Returns the tool."
  [tool]
  (swap! registry assoc (:id tool) tool)
  (tel/log! {:level :debug
             :id    ::register-tool
             :data  {:tool (:id tool)}}
            "Registered tool")
  tool)

(defn unregister-tool!
  "Remove the tool with `id` from the registry, if present. Returns nil."
  [id]
  (let [removed? (contains? @registry id)]
    (swap! registry dissoc id)
    (tel/log! {:level :debug
               :id    ::unregister-tool
               :data  {:tool id :removed? removed?}}
              "Unregistered tool")
    nil))

(defn registered-tools
  "Return the current registry as a map `{id tool}`. Mainly for inspection
  and tests."
  []
  @registry)

(defn spec
  "Return the Ollama tool definition for the registered tool `id`, or nil."
  [id]
  (-> (get @registry id) :spec))

(defn specs
  "Return a vector of all registered tool definitions, suitable for passing
  as the `:tools` field of an Ollama `/api/chat` request."
  []
  (mapv :spec (vals @registry)))

(defn specs-for
  "Return a vector of tool definitions for the given tool `ids` (in the
  order given), skipping any ids that are not registered. Useful when a
  config file selects a subset of the built-in tools."
  [ids]
  (vec (keep #(spec %) ids)))

;; ---------------------------------------------------------------------------
;; Invocation
;; ---------------------------------------------------------------------------

(defn- parse-arguments
  "Ollama sends tool call `:arguments` as a JSON *string* (some clients send a
  parsed map). Normalize to a Clojure map either way."
  [arguments]
  (cond
    (nil? arguments)     {}
    (map? arguments)     arguments
    (string? arguments)
    (if (str/blank? arguments)
      {}
      (try
        (json/parse-string arguments true)
        (catch Exception _
          (tel/log! {:level :debug
                     :id    ::unparseable-arguments
                     :data  {:length (count arguments)}}
                    "Could not parse tool arguments as JSON")
          {:__parse_error__ arguments})))
    :else                {}))

(defn- coerce-result
  "Coerce a tool function's return value to a string for the tool message."
  [value]
  (cond
    (nil? value)   ""
    (string? value) value
    :else          (pr-str value)))

(defn- normalize-args
  "Keywordize any string keys in `args` so tool functions can destructure with
  `:keys` regardless of whether arguments arrived as a parsed map or with
  string keys."
  [args]
  (into {}
        (for [[k v] args]
          [(if (string? k) (keyword k) k) v])))

(defn invoke
  "Run the registered tool `id` with parsed `args` and session context `ctx`.

  `args` keys are keywordized first, so both `{:path \"x\"}` and
  `{\"path\" \"x\"}` work. Returns a string result. If the tool is unknown or
  throws, an error string is returned instead of propagating, so the agent
  loop can feed the failure back to the model."
  [id args ctx]
  (let [tool (get @registry id)]
    (cond
      (nil? tool)
      (do
        (tel/log! {:level :warn
                   :id    ::unknown-tool
                   :data  {:tool id}}
                  "Attempted to invoke unknown tool")
        (str "Error: unknown tool '" id "'"))

      :else
      (let [args'  (normalize-args args)
            start  (System/nanoTime)]
        (tel/log! {:level :debug
                   :id    ::tool-invoke
                   :data  {:tool     id
                           :arg-keys (vec (keys args'))
                           :cwd      (:cwd ctx)}}
                  "Invoking tool")
        (try
          (let [result (coerce-result ((:fn tool) args' ctx))
                msecs  (quot (- (System/nanoTime) start) 1000000)]
            (tel/log! {:level :debug
                       :id    ::tool-complete
                       :data  {:tool         id
                               :elapsed-ms   msecs
                               :result-chars (count result)
                               :error?       (str/starts-with? result "Error:")}}
                      "Tool completed")
            result)
          (catch Throwable e
            (tel/error! {:id   ::tool-failed
                         :data {:tool     id
                                :arg-keys (vec (keys args'))}}
                        e)
            (str "Error: " (or (ex-message e) (class e)))))))))

(defn- tool-call->id+args
  "Extract `[tool-id args]` from one Ollama `tool_calls` entry. Tolerates both
  the nested `{:function {:name ... :arguments ...}}` shape and a flattened
  `{:name ... :arguments ...}` shape."
  [call]
  (let [f    (or (:function call) call)
        name (:name f)
        args (parse-arguments (:arguments f))]
    [name args]))

(defn execute-calls
  "Execute a vector of Ollama `tool_calls` against the registry, returning a
  vector of `{:role \"tool\" :content ... :name ...}` messages (one per call)
  ready to append to chat history.

  `ctx` is passed to each tool function; it should carry at least `:cwd` for
  the filesystem/shell tools."
  [tool-calls ctx]
  (tel/log! {:level :debug
             :id    ::execute-tool-calls
             :data  {:count (count tool-calls)
                     :tools (mapv #(or (-> % :function :name) (:name %) "?")
                                  tool-calls)}}
            "Executing tool calls")
  (vec
   (for [call tool-calls]
     (let [[id args] (tool-call->id+args call)
           result    (invoke id args ctx)]
       (cond-> {:role    "tool"
                :content result
                :name    (or id "unknown")}
         (some? (:id call)) (assoc :tool_call_id (:id call)))))))

;; ---------------------------------------------------------------------------
;; Built-in tools
;; ---------------------------------------------------------------------------

(defn- resolve-path
  "Resolve `path` against the session `cwd`. Absolute paths are returned
  unchanged. Returns a `java.io.File`."
  [path ctx]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (io/file (or (:cwd ctx) ".") path))))

(def ^:private max-file-bytes
  "Cap on the number of bytes read into a tool result, to keep history small."
  (* 64 1024))

(defn- read-file-fn
  "Tool: read the contents of a file (UTF-8), truncating past 64 KiB."
  [{:keys [path]} ctx]
  (let [^File f (resolve-path path ctx)]
    (cond
      (not (.exists f))   (str "Error: no such file: " path)
      (.isDirectory f)    (str "Error: not a file (is a directory): " path)
      :else
      (with-open [in (io/input-stream f)]
        (let [buf (byte-array max-file-bytes)
              n   (.read in buf 0 max-file-bytes)
              s   (String. buf 0 (max 0 n) "UTF-8")]
          (if (>= n max-file-bytes)
            (str s "\n... [truncated at " max-file-bytes " bytes]")
            s))))))

(defn- write-file-fn
  "Tool: write `content` to `path`, creating parent directories as needed."
  [{:keys [path content]} ctx]
  (let [^File f (resolve-path path ctx)]
    (.. f (getParentFile) (mkdirs))
    (spit f (str content))
    (str "Wrote " (.length f) " bytes to " (.getPath f))))

(defn- list-directory-fn
  "Tool: list the entries of a directory (files and subdirectories)."
  [{:keys [path]} ctx]
  (let [^File f (resolve-path (or path ".") ctx)]
    (cond
      (not (.exists f))   (str "Error: no such directory: " path)
      (not (.isDirectory f)) (str "Error: not a directory: " path)
      :else
      (->> (.listFiles f)
           (sort-by (fn [^File x] (.getName x)))
           (map (fn [^File x]
                  (str (if (.isDirectory x) "d " "- ") (.getName x))))
           (str/join "\n")
           (str)))))

(defn- run-command-fn
  "Tool: run a shell command in the session `cwd` and return combined
  stdout/stderr. Capped at ~30 seconds and 64 KiB of output."
  [{:keys [command]} ctx]
  (let [builder (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["sh" "-c" (str command)]))
                  (.directory (-> (or (:cwd ctx) ".")
                                  (io/file)
                                  .getAbsoluteFile))
                  (.redirectErrorStream true))]
    (tel/log! {:level :debug
               :id    ::run-command
               :data  {:command (str command)
                       :cwd     (:cwd ctx)}}
              "Running shell command")
    (let [proc (.start builder)]
      (try
        (let [^String joined (with-open [r (io/reader (.getInputStream proc))]
                       (str/join "\n" (line-seq r)))
              raw    (.getBytes joined "UTF-8")
              out    (java.io.ByteArrayInputStream. raw)
              ^bytes buf (byte-array max-file-bytes)
              n      (.read out buf 0 max-file-bytes)
              body   (String. buf 0 (max 0 n) "UTF-8")
              exited (try
                       (.waitFor proc 30 TimeUnit/SECONDS)
                       (catch InterruptedException _ false))
              code   (when exited (.exitValue proc))]
          (if exited
            (tel/log! {:level (if (zero? code) :debug :warn)
                       :id    ::run-command-complete
                       :data  {:exit-code code :chars (count body)}}
                      "Shell command completed")
            (tel/log! {:level :warn
                       :id    ::run-command-timeout
                       :data  {:command (str command)}}
                      "Shell command timed out after 30s"))
          (cond-> (str body)
            (not exited) (str "\n[timed out after 30s]")
            (and exited (not (zero? code))) (str "\n[exit code " code "]")))
        (finally
          (.destroyForcibly proc))))))

(defn- eval-clojure-fn
  "Tool: evaluate a string of Clojure code in the agent's own Clojure
  runtime and return the printed result. Both printed stdout (`*out*`) and
  the evaluation value (via `pr-str`) are captured. Exceptions are reported,
  not rethrown."
  [{:keys [code]} _ctx]
  (let [sw   (java.io.StringWriter.)
        pr   (java.io.PrintWriter. sw)
        val  (binding [*out* pr
                       *err* pr]
               (try
                 (if (str/blank? code)
                   nil
                   (eval (read-string code)))
                 (catch Throwable e
                   (ex-info (or (ex-message e) (str (class e)))
                            {:cause e}))))
        _      (.flush pr)
        printed (.toString sw)
        result  (cond
                  (instance? clojure.lang.ExceptionInfo val)
                  (str "Error: " (ex-message val)
                       (when-let [c (:cause (ex-data val))]
                         (str "\n"
                              (with-out-str
                                (.printStackTrace c (java.io.PrintWriter. *out*))))))

                  (nil? val)
                  "nil"

                  :else
                  (pr-str val))]
    (str (when-not (str/blank? printed)
           (str printed (when-not (str/ends-with? printed "\n") "\n")))
         "=> " result)))

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

(def ^:private run-command-spec
  {:type "function"
   :function
   {:name "run_command"
    :description
    "Run a shell command (via `sh -c`) in the session working directory and
    return combined stdout/stderr. Runs for up to 30 seconds."
    :parameters
    {:type "object"
     :properties
     {"command" {:type "string"
                 :description "The shell command to execute."}}
     :required ["command"]}}})

(def ^:private eval-clojure-spec
  {:type "function"
   :function
   {:name "eval_clojure"
    :description
    "Evaluate a string of Clojure source code in the same Clojure runtime the
    agent is running in (the live process), and return the printed output
    plus the value (as `=> <pr-str value>`). Use this to inspect or mutate
    the agent's in-memory state, call project functions, or experiment with
    Clojure. Exceptions are returned as error strings, not thrown."
    :parameters
    {:type "object"
     :properties
     {"code" {:type "string"
               "description" "A single Clojure expression to evaluate."}}
     :required ["code"]}}})

(defn built-in-tools
  "Return a vector of the default tool maps (`:id`, `:fn`, `:spec`) an agent
  can use to read/write files, run shell commands within its session cwd, and
  evaluate Clojure code in the running process."
  []
  [{:id "read_file"      :fn read-file-fn      :spec file-read-spec}
   {:id "write_file"     :fn write-file-fn     :spec file-write-spec}
   {:id "list_directory" :fn list-directory-fn :spec list-directory-spec}
   {:id "run_command"    :fn run-command-fn    :spec run-command-spec}
   {:id "eval_clojure"   :fn eval-clojure-fn   :spec eval-clojure-spec}])

;; Populate the registry with the built-ins at load time.
(defn- install-built-ins!
  []
  (run! register-tool! (built-in-tools)))

(install-built-ins!)