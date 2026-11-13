(ns clai.agent.tools.builtin.eval
  "Built-in `eval_clojure` tool, which evaluates Clojure code in the agent's
  own running runtime.

  Code runs in the agent's REPL namespace, `clai.home.core` (see that
  namespace), which is seeded with helpers for inspecting the live session:
  the tools the agent can call, the session's stored history, the namespaces
  and Vars of the running process, and the active plugins.

  While a call is being evaluated, its session context is bound to
  [[*context*]] so that evaluated code can look at the live session."
  (:require [clai.agent.tools.builtin.common :as common]
            [clai.util :as util]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io PrintWriter StringWriter]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Limits
;; ---------------------------------------------------------------------------

(def max-result-bytes
  "How much of an `eval_clojure` result is returned to the model. Results
  longer than this are cut so one evaluation cannot flood the session history."
  common/max-output-bytes)

(def ^:private max-trace-frames
  "How many stack frames [[error-text]] shows from a failed evaluation."
  10)

(def ^:private print-length
  "`*print-length*` used for the result value, so printing an infinite or huge
  collection terminates. Truncated output ends in `...`."
  200)

(def ^:private print-level
  "`*print-level*` used for the result value, so printing deeply nested data
  stays readable. Truncated output ends in `#`."
  20)

;; ---------------------------------------------------------------------------
;; Evaluation environment
;; ---------------------------------------------------------------------------

(def default-eval-namespace
  "Namespace that `eval_clojure` code runs in when the tool call does not
  specify one: the agent's own REPL, `clai.home.core`."
  "clai.home.core")

(def ^:dynamic *context*
  "The session context of the `eval_clojure` call being evaluated: the `ctx`
  map described by `clai.agent.tools.schema/tool-ctx`, carrying at least
  `:cwd` and, in a live agent, `:session-id`, `:model`, `:tool-registry`, and
  `:postgres`.

  The helpers in `clai.home.core` read it, so evaluated code can inspect the
  session it is running in. It is nil outside a tool call, for example when
  code is evaluated from a plain REPL."
  nil)

(defn- home-namespace
  "Create the default namespace of `sym` with nothing but `clojure.core`
  referred, for a runtime where `clai.home.core` cannot be loaded. Returns the
  namespace."
  [sym]
  (let [n (create-ns sym)]
    (binding [*ns* n]
      (refer 'clojure.core))
    n))

(defn resolve-eval-namespace
  "Resolve `ns-name` (a string or symbol) to a loaded namespace, requiring it
  if it is not loaded yet.

  The default namespace is created empty rather than reported as missing, so
  `eval_clojure` still runs in a runtime without the agent's home namespace.
  Returns nil when any other namespace cannot be loaded."
  {:malli/schema [:-> [:or :string :symbol] :any]}
  [ns-name]
  (let [sym (symbol (str ns-name))]
    (or (find-ns sym)
        (try
          (require sym)
          (find-ns sym)
          (catch Throwable e
            (tel/log! {:level :debug
                       :id    ::eval-namespace-require-failed
                       :data  {:namespace (str sym)
                               :error     (util/error-message e)}}
                      "Could not load namespace for eval_clojure")
            nil))
        (when (= (str sym) default-eval-namespace)
          (home-namespace sym)))))

;; ---------------------------------------------------------------------------
;; Evaluation
;; ---------------------------------------------------------------------------

(defn- evaluate
  "Evaluate `code` in namespace `ns-name`, capturing its printed output.

  Returns `{:printed <string> :value <value>}` on success, or
  `{:printed <string> :error <throwable>}` when evaluation throws."
  [code ns-name]
  (if (str/blank? code)
    {:printed "" :value nil}
    (let [sw (StringWriter.)
          pw (PrintWriter. sw)]
      (try
        (let [target (or (resolve-eval-namespace ns-name)
                         (throw (ex-info (str "No such namespace: " ns-name
                                              "; pass `namespace` as a loaded "
                                              "namespace such as \"clojure.string\"")
                                         {:namespace ns-name})))
              value  (binding [*out* pw
                               *err* pw
                               *ns*  target]
                       (eval (read-string code)))]
          (.flush pw)
          {:printed (str sw) :value value})
        (catch Throwable e
          (.flush pw)
          {:printed (str sw) :error e})))))

(defn- root-cause
  "The innermost cause of `error`, which is where the real message of a wrapped
  exception such as the compiler's lives."
  [^Throwable error]
  (loop [e error]
    (if-let [cause (.getCause e)] (recur cause) e)))

(defn- error-text
  "Render `error` as an `Error: ...` line, the message of its innermost cause
  when that says more, the `ex-data` of the exception when it carries any, and
  the first [[max-trace-frames]] stack frames."
  [^Throwable error]
  (let [cause (root-cause error)]
    (str "Error: " (util/error-message error)
         (when-not (identical? cause error)
           (str "\n  caused by: " (util/error-message cause)))
         (when-let [data (ex-data error)]
           (str "\n" (pr-str data)))
         (when-let [frames (seq (.getStackTrace error))]
           (str "\n" (str/join "\n" (map str (take max-trace-frames frames))))))))

(defn- value-text
  "Print `value` as `pr-str` does, bounded by [[print-length]] and
  [[print-level]]. A value that cannot be printed is reported instead of
  failing the evaluation."
  [value]
  (try
    (binding [*print-length* print-length
              *print-level*  print-level]
      (pr-str value))
    (catch Throwable e
      (str "<unprintable value: " (util/error-message e) ">"))))

(defn- truncate-result
  "Cut `text` to at most `max-bytes` UTF-8 bytes, appending a marker that says
  a prefix is all that is shown."
  [text max-bytes]
  (let [^String s     (str text)
        byte-length   (fn [^String x]
                        (alength (.getBytes x StandardCharsets/UTF_8)))]
    (if (<= (byte-length s) max-bytes)
      s
      (loop [n (min (count s) max-bytes)]
        (let [prefix (subs s 0 n)]
          (if (or (zero? n) (<= (byte-length prefix) max-bytes))
            (str prefix "\n… [truncated at " max-bytes " bytes]")
            (recur (quot n 2))))))))

(defn eval-clojure-fn
  "Tool: evaluate a string of Clojure code in the agent's own Clojure
  runtime and return the printed result. The code is evaluated inside the
  namespace named by `namespace` (defaulting to `default-eval-namespace`), so
  unqualified symbols resolve against that namespace's refers/imports. Both
  printed stdout/stderr (`*out*`/`*err*`) and the evaluation value (via
  `pr-str`) are captured, and the session context is bound to [[*context*]].
  Exceptions are reported, not rethrown."
  {:malli/schema [:-> [:map [:code {:optional true} [:maybe :string]]
                       [:namespace {:optional true} [:maybe :string]]]
                  [:maybe [:map]] :string]}
  [{:keys [code namespace]} ctx]
  (let [ns-name (if (str/blank? (str namespace))
                  default-eval-namespace
                  (str namespace))]
    (binding [*context* ctx]
      (let [{:keys [printed value error]} (evaluate (str code) ns-name)
            result (if error (error-text error) (value-text value))]
        (truncate-result
         (str (when-not (str/blank? printed)
                (str printed (when-not (str/ends-with? printed "\n") "\n")))
              "=> " result)
         max-result-bytes)))))

;; ---------------------------------------------------------------------------
;; Tool definition
;; ---------------------------------------------------------------------------

;; The `eval_clojure` description shown to the model. It doubles as the tool's
;; usage documentation: models reach for a tool they understand, so it says
;; when to prefer evaluation over another tool round, what the default
;; namespace already provides, which helpers report the live session, and that
;; `def` is how a value survives to the next call and into the user's own
;; REPL in the same namespace. Keep it in sync with `clai.home.core`.
(def tool-description
  "Evaluate one Clojure expression in the same live JVM the agent runs in,
  and get back what it printed plus `=> <pr-str value>`. State persists
  between calls (same process), and `*out*`/`*err*` are captured. Errors come
  back as text, not thrown.

  USE THIS TOOL OFTEN. Before writing code that calls a function, ask one
  question about the running system, or grep the project, evaluate instead --
  it is usually one cheap call where read_file/run_command/another tool round
  would take several. Reaching for it is expected, not a last resort:
  - Look things up: (doc clai.util/error-message), (ns-publics 'clai.plugins),
    (apropos \"session\"), (source clai.util/error-message),
    (vars 'clai.agent.core), (var-detail \"clai.util\" \"error-message\").
  - Inspect the live agent: (tools) lists every tool you can call right now
    (id + description, straight from the live registry, including ones a
    config or plugin added); (tool \"run_command\") gives that tool's full
    schema, i.e. its exact argument names, so you can call it correctly the
    first time. Also (context), (session), (history), (active-plugins).
  - Read the project: (slurp \"src/clai/agent/core.clj\"),
    (->> (file-seq (io/file \".\")) (map str) (filter #(.endsWith ^String % \".clj\")) (take 20)).
  - Compute or transform data with real code instead of by hand: cheshire
    (`json` alias) for JSON, clojure.string, regexes, sorting, counting,
    (shell/sh \"git\" \"status\").
  - Try a fix before reporting it: (require 'my.ns :reload) then call the
    function; run the suite with
    (shell/sh \"lein\" \"test\" :dir \"/path/to/project\") and read :out.

  SAVE WHAT YOU FIND WITH `def`. The user has a REPL open in this same
  namespace, so a Var you define is immediately visible to them, inspectable
  in their next input, and cheap for both of you to reuse:
  - (def failing (filter :failed? (run-tests))) -- then (count failing) or
    (map :name failing) here, or the user types it themselves.
  - Prefer (def x ...) over recomputing an expensive value in a later call,
    and name Vars for what they hold (slow-test-names, not a or tmp1) so the
    user can read your scratchpad.
  - `def` a value instead of hiding it in a `let` when it is worth a second
    look; a `let` binding disappears when the call returns.
  - Only real state that must outlive the process belongs in a file instead;
    scratch values are fine as Vars.

  What the default namespace gives you:
  - Code runs in `clai.home.core` unless `namespace` says otherwise. It is
    seeded for exploration: aliases eval, registry, sessions, clj, plugins,
    json, shell, io, str and refers pprint, doc, source, dir, apropos,
    find-doc, plus the helpers (help), (tools), (tool id), (context),
    (session), (history), (vars ns), (var-detail ns var), (active-plugins).
    Call (help) for a cheat sheet.
  - (context) returns the live session: its :cwd is where relative paths and
    shell commands should point, and :session-id identifies this session.
    Code runs in the JVM's own directory ((System/getProperty \"user.dir\")),
    so build absolute paths from (:cwd (context)) when the two differ.
  - (history) returns this session's stored messages (user, assistant, tool
    calls and results) as maps -- read it before repeating work or claiming
    something was not tried.
  - Only one form is read: wrap several in (do ...) or (->> ...).

  Keep results small (a few dozen lines); huge values are truncated. Use the
  `namespace` argument to evaluate elsewhere, e.g. \"clojure.string\"; the
  namespace must be loadable.")

(def ^:private eval-clojure-spec
  {:type "function"
   :function
   {:name "eval_clojure"
    :description tool-description
    :parameters
    {:type "object"
     :properties
     {"code"
      {"type" "string"
       "description" (str "One Clojure expression to evaluate. Wrap several "
                          "forms in (do ...). Use (def name ...) to keep a "
                          "value for later calls and for the user's REPL in "
                          "the same namespace. Its printed output and value "
                          "are returned.")}
      "namespace"
      {"type" "string"
       "description" (str "Namespace to evaluate the code in. Defaults to "
                          default-eval-namespace ", the agent's REPL, "
                          "preloaded with session-inspection helpers. A "
                          "namespace that is not loaded yet is required "
                          "from the classpath.")}}
     :required ["code"]}}})

(defn tools
  "Return the built-in Clojure evaluation tool maps."
  {:malli/schema [:-> [:vector :map]]}
  []
  [{:id "eval_clojure" :handler eval-clojure-fn :spec eval-clojure-spec}])
