(ns clai.tools.builtin.eval
  "Built-in `eval_clojure` tool, which evaluates Clojure code in the agent's
  own running runtime."
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]))

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
                               :error     (or (ex-message e) (str (class e)))}}
                      "Could not load namespace for eval_clojure")
            nil)))))

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
        sw   (java.io.StringWriter.)
        pr   (java.io.PrintWriter. sw)
        val  (binding [*out* pr
                       *err* pr]
               (try
                 (if (str/blank? code)
                   nil
                   (if-let [target-ns (resolve-eval-namespace ns-name)]
                     (binding [*ns* target-ns]
                       (eval (read-string code)))
                     (throw (ex-info (str "No such namespace: " ns-name)
                                     {:namespace ns-name}))))
                 (catch Throwable e
                   (ex-info (or (ex-message e) (str (class e)))
                            {:cause e}))))
        _      (.flush pr)
        printed (.toString sw)
        result  (cond
                  (instance? clojure.lang.ExceptionInfo val)
                  (str "Error: " (ex-message val)
                       (when-let [^Throwable c (:cause (ex-data val))]
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
