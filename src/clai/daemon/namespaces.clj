(ns clai.daemon.namespaces
  "Introspection of the Clojure runtime for the web UI's namespace explorer.

  The REPL panel evaluates forms in the daemon's JVM, so browsing that JVM's
  namespaces is how a user finds what there is to call. This namespace answers
  the three questions the explorer asks, one per level of its tree:

    * [[list-namespaces]] -- which namespaces are loaded, with their docstrings
      and how many public Vars they hold (the tree's root);
    * [[namespace-vars]] -- which public Vars a namespace holds, with their
      docstrings and argument lists (fetched when a namespace is opened);
    * [[var-detail]] -- everything about one Var: docstring, argument lists,
      malli schema, source, and current value (fetched when a Var is clicked).

  Nothing is cached, so a Var defined at the REPL shows up the next time the
  explorer refreshes.

  ## Example

      (require '[clai.daemon.namespaces :as clj])

      (->> (clj/list-namespaces) :namespaces (map :name) (take 3))
      ;; => (\"clai.agent.core\" \"clai.agent.tools.builtin\" ...)

      (-> (clj/namespace-vars \"clojure.string\") :vars (map :name) (take 3))
      ;; => (\"blank?\" \"capitalize\" \"ends-with?\")

      (:arglists (clj/var-detail \"clojure.string\" \"join\"))
      ;; => \"[coll] [separator coll]\""
  (:require [clojure.repl :as repl]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Limits
;; ---------------------------------------------------------------------------

(def MaxSourceChars
  "The longest source excerpt [[var-detail]] returns. A definition longer than
  this is truncated, so one huge form cannot flood the browser."
  20000)

(def MaxValueChars
  "The longest printed value [[var-detail]] returns. A value longer than this
  is truncated, so a big collection cannot flood the browser."
  4000)

(def PrintLength
  "How many entries [[var-detail]] prints of a collection value."
  20)

(def PrintLevel
  "How deeply [[var-detail]] prints a nested value."
  4)

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def NamespaceSummary
  "One row of the explorer's root listing: a loaded namespace's name, its
  docstring, and how many public Vars it holds."
  [:map
   [:name :string]
   [:doc [:maybe :string]]
   [:varCount :int]])

(def NamespaceList
  "The reply to a request for the loaded namespaces."
  [:map [:namespaces [:vector NamespaceSummary]]])

(def VarSummary
  "One row under an open namespace: a public Var's name, its docstring, its
  argument lists as one readable line, and whether it is a macro."
  [:map
   [:name :string]
   [:doc [:maybe :string]]
   [:arglists [:maybe :string]]
   [:macro :boolean]])

(def NamespaceVars
  "The reply to a request for one namespace's Vars. `:missing` is true when no
  namespace of that name is loaded, so the explorer can say \"not loaded yet\"
  instead of showing an error."
  [:map
   [:namespace :string]
   [:doc [:maybe :string]]
   [:missing :boolean]
   [:vars [:vector VarSummary]]])

(def VarDetail
  "The reply to a request for one Var. `:source`, `:value`, `:file`, and
  `:malliSchema` are nil when they are unavailable."
  [:map
   [:namespace :string]
   [:name :string]
   [:doc [:maybe :string]]
   [:arglists [:maybe :string]]
   [:malliSchema [:maybe :string]]
   [:macro :boolean]
   [:private :boolean]
   [:dynamic :boolean]
   [:deprecated [:maybe [:or :string :boolean]]]
   [:file [:maybe :string]]
   [:line [:maybe :int]]
   [:source [:maybe :string]]
   [:value [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Namespace and Var summaries
;; ---------------------------------------------------------------------------

(defn- public?
  "True when Var `v` is interned as public, which is what the explorer offers.

  Private Vars are left out because they cannot be called from the REPL the
  explorer is feeding."
  [v]
  (not (:private (meta v))))

(defn- public-interns
  "The public Vars interned in namespace `n`, as `[sym var]` pairs sorted by
  name.

  Refers are left out -- they belong to the namespace that defined them -- so
  `clojure.core` does not appear under every namespace in the tree."
  [n]
  (->> (ns-interns n)
       (filter (fn [[_ v]] (public? v)))
       (sort-by (fn [[sym _]] (str sym)))))

(defn- arglists-text
  "`arglists` -- a Var's `:arglists` metadata -- as one readable line such as
  `\"[coll] [separator coll]\"`, or nil when the Var has no argument lists."
  [arglists]
  (when (seq arglists)
    (try
      (str/join " " (map pr-str arglists))
      (catch Throwable _ nil))))

(defn- var-summary
  "The explorer's row for the Var `v` interned as `sym`."
  [sym v]
  (let [{:keys [doc arglists macro]} (meta v)]
    {:name     (str sym)
     :doc      doc
     :arglists (arglists-text arglists)
     :macro    (boolean macro)}))

(defn- ns-doc
  "The docstring of namespace `n`, or nil when it was defined without one."
  [n]
  (:doc (meta n)))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(defn list-namespaces
  "Every namespace loaded in this JVM, sorted by name, as a [[NamespaceList]].

  Session namespaces (`sessions.<id>`) are included: they are where the REPL
  defines things, and the explorer sorts them to the top."
  {:malli/schema [:-> NamespaceList]}
  []
  {:namespaces (->> (all-ns)
                    (map (fn [n]
                           {:name     (str (ns-name n))
                            :doc      (ns-doc n)
                            :varCount (count (public-interns n))}))
                    (sort-by :name)
                    vec)})

(defn namespace-vars
  "The public Vars of namespace `namespace-name`, sorted by name, as a
  [[NamespaceVars]].

  An unknown namespace answers with `:missing true` and no Vars instead of
  throwing, because the explorer asks about namespaces that may belong to a
  session that has not been loaded in this JVM yet."
  {:malli/schema [:-> :string NamespaceVars]}
  [namespace-name]
  (let [namespace-name (str namespace-name)]
    (if-let [n (find-ns (symbol namespace-name))]
      {:namespace (str (ns-name n))
       :doc       (ns-doc n)
       :missing   false
       :vars      (mapv (fn [[sym v]] (var-summary sym v))
                        (public-interns n))}
      {:namespace namespace-name
       :doc       nil
       :missing   true
       :vars      []})))

;; ---------------------------------------------------------------------------
;; Var detail
;; ---------------------------------------------------------------------------

(defn- source-text
  "The form that defines `v`, read back from its source file, or nil when the
  source is not on the classpath (a Var defined at the REPL, for example).

  Definitions longer than [[MaxSourceChars]] are truncated."
  [v]
  (let [{:keys [ns name]} (meta v)]
    (when (and ns name)
      (try
        (when-let [text (repl/source-fn (symbol (str (ns-name ns)) (str name)))]
          (if (> (count text) MaxSourceChars)
            (str (subs text 0 MaxSourceChars) "\n…")
            text))
        (catch Throwable _ nil)))))

(defn- value-text
  "`v`'s current value printed with bounded breadth and depth, or nil when the
  Var is unbound or holds a function, whose printed form would say nothing the
  explorer's reader wants to see."
  [v]
  (when (.isBound ^clojure.lang.Var v)
    (try
      (let [value (deref v)]
        (when-not (fn? value)
          (let [text (binding [*print-length* PrintLength
                               *print-level*  PrintLevel
                               *print-meta*   false]
                       (pr-str value))]
            (if (> (count text) MaxValueChars)
              (str (subs text 0 MaxValueChars) "…")
              text))))
      (catch Throwable _ nil))))

(defn- source-file
  "The file Var `v` was defined in, or nil when it has none (a REPL definition
  reports `NO_SOURCE_PATH`)."
  [v]
  (let [file (:file (meta v))]
    (when-not (or (str/blank? file) (= "NO_SOURCE_PATH" file))
      file)))

(defn var-detail
  "Everything the explorer shows about Var `var-name` in namespace
  `namespace-name`, as a [[VarDetail]], or nil when no such Var exists.

  The Var is resolved the way `ns-resolve` does it, so a name referred into
  `namespace-name` is found too; `:namespace` is then the namespace the Var is
  actually interned in."
  {:malli/schema [:-> :string :string [:maybe VarDetail]]}
  [namespace-name var-name]
  (when-let [v (some-> (find-ns (symbol (str namespace-name)))
                       (ns-resolve (symbol (str var-name))))]
    (let [m (meta v)]
      {:namespace   (str (ns-name (:ns m)))
       :name        (str (:name m))
       :doc         (:doc m)
       :arglists    (arglists-text (:arglists m))
       :malliSchema (some-> (get m :malli/schema) pr-str)
       :macro       (boolean (:macro m))
       :private     (boolean (:private m))
       :dynamic     (boolean (:dynamic m))
       :deprecated  (:deprecated m)
       :file        (source-file v)
       :line        (:line m)
       :source      (source-text v)
       :value       (value-text v)})))
