(ns clai.tools.builtin.eval
  "Built-in `eval_clojure` tool, which evaluates Clojure code in the agent's
  own running runtime."
  (:require [clai.util :as util]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io PrintWriter StringWriter]))

;; ---------------------------------------------------------------------------
;; Clojure evaluation tool
;; ---------------------------------------------------------------------------

(def default-eval-namespace
  "Namespace that `eval_clojure` code runs in when the tool call does not
  specify one."
  "clai.home.core")

(defn resolve-eval-namespace
  "Resolve `ns-name` (a string or symbol) to a loaded namespace, requiring it
  if it is not loaded yet. Returns nil if no such namespace can be loaded."
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
            nil)))))

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
                         (throw (ex-info (str "No such namespace: " ns-name)
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

(defn- error-text
  "Render `error` as an `Error: ...` line followed by its stack trace."
  [^Throwable error]
  (str "Error: " (util/error-message error)
       (with-out-str
         (.printStackTrace error (PrintWriter. *out*)))))

(defn eval-clojure-fn
  "Tool: evaluate a string of Clojure code in the agent's own Clojure
  runtime and return the printed result. The code is evaluated inside the
  namespace named by `namespace` (defaulting to `default-eval-namespace`), so
  unqualified symbols resolve against that namespace's refers/imports. Both
  printed stdout (`*out*`) and the evaluation value (via `pr-str`) are
  captured. Exceptions are reported, not rethrown."
  [{:keys [code namespace]} _ctx]
  (let [ns-name (if (str/blank? (str namespace))
                  default-eval-namespace
                  (str namespace))
        {:keys [printed value error]} (evaluate code ns-name)
        result  (if error (error-text error) (pr-str value))]
    (str (when-not (str/blank? printed)
           (str printed (when-not (str/ends-with? printed "\n") "\n")))
         "=> " result)))

;; ---------------------------------------------------------------------------
;; Tool definition
;; ---------------------------------------------------------------------------

(def ^:private eval-clojure-spec
  {:type "function"
   :function
   {:name "eval_clojure"
    :description
    "Evaluate a string of Clojure source code in the same Clojure runtime the
    agent is running in (the live process), and return the printed output
    plus the value (as `=> <pr-str value>`). The code is evaluated inside the
    namespace given by `namespace` (default: `clai.home.core`), so unqualified
    symbols resolve against that namespace. Use this to inspect or mutate
    the agent's in-memory state, call project functions, or modify your
    runtime, plugins, inference providers, tools, etc. Exceptions are returned as error strings, not thrown.

    If you run something you think that you'll re-use, be sure to persist it.
    You can use built-in clojure tools to find the path to the source file for the namespace.

    You can also use this to discover tools you have left for yourself in the past."
    :parameters
    {:type "object"
     :properties
     {"code" {:type "string"
               "description" "A single Clojure expression to evaluate."}
      "namespace" {:type "string"
                    "description" (str "Namespace to evaluate the code in. "
                                       "Defaults to " default-eval-namespace
                                       ". The namespace is required if it is "
                                       "not already loaded.")}}
     :required ["code"]}}})

(defn tools
  "Return the built-in Clojure evaluation tool maps."
  []
  [{:id "eval_clojure" :handler eval-clojure-fn :spec eval-clojure-spec}])
