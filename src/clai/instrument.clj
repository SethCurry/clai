(ns clai.instrument
  "Runtime Malli instrumentation for `clai`.

  `:malli/schema` metadata on a Var is only documentation until the schema is
  collected and the Var is instrumented. Requiring this namespace enables that
  at runtime: every loaded `clai` namespace is scanned for public Vars with a
  `:malli/schema`, and the corresponding functions validate their arguments and
  return values when called.

  Test namespaces are skipped so loading the test suite (or a REPL that has
  loaded it) never instruments test helpers. Use [[start!]] to re-scan after
  loading new `clai` namespaces and [[stop!]] to remove instrumentation."
  (:require [clai.agent.providers.ollama]
            [clai.agent.providers.ollama.image]
            [clai.agent.providers.ollama.messages]
            [clai.agent.tools.builtin]
            [clai.agent.tools.invoke]
            [clai.agent.tools.registry]
            [clai.config]
            [clai.daemon.postgres]
            [clai.daemon.postgres.sessions]
            [clai.log]
            [clai.plugins]
            [clai.plugins.store]
            [clai.util]
            [clojure.string :as str]
            [malli.instrument :as mi]
            [taoensso.telemere :as tel]))

(defn- test-namespace?
  "True when `ns-name` is one of the project's test namespaces."
  [ns-name]
  (let [n (str ns-name)]
    (or (= n "clai.test-support")
        (str/ends-with? n "-test"))))

(defn namespaces
  "Return the loaded `clai` namespaces eligible for instrumentation, sorted by
  name. Dynamically loaded namespaces (for example, a provider written at
  runtime) are included the next time [[start!]] runs."
  []
  (->> (all-ns)
       (map ns-name)
       (filter #(str/starts-with? (str %) "clai."))
       (remove test-namespace?)
       sort
       vec))

(defn collect!
  "Collect the `:malli/schema` metadata from every public Var in the loaded
  `clai` namespaces, registering them with Malli. Returns the collected vars."
  []
  (let [nses   (namespaces)
        schema (mi/clj-collect! {:ns nses})]
    (tel/log! {:level :debug
               :id    ::collected
               :data  {:namespaces (count nses)
                       :schemas    (count schema)}}
              "Collected malli function schemas")
    schema))

(defn instrument!
  "Register the loaded `clai` function schemas with Malli and instrument their
  Vars. Returns the instrumented Vars."
  []
  (collect!)
  (let [vars (mi/instrument!)]
    (tel/log! {:level :debug
               :id    ::instrumented
               :data  {:vars (count vars)}}
              "Enabled malli runtime instrumentation")
    vars))

(defn start!
  "Enable Malli instrumentation for every loaded `clai` namespace. Safe to
  call repeatedly: existing instrumentation is replaced, not nested."
  []
  (instrument!)
  nil)

(defn stop!
  "Remove Malli instrumentation from the instrumented Vars."
  []
  (mi/unstrument!)
  nil)

(defonce ^:private _instrumented
  ;; Requiring this namespace is enough to turn instrumentation on; loading
  ;; `clai.agent.core` or the REPL helpers makes runtime schema checks available.
  (do (start!) true))
