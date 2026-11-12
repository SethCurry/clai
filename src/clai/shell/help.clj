(ns clai.shell.help
  "REPL help for the shell integration helpers.

  [[help]] prints a Var's docstring and call signatures, so a REPL user can
  look up a helper without leaving the REPL:

      (help git/commit)
      ;; clai.shell.git/commit (function)
      ;;   (commit message)
      ;;   (commit message opts)
      ;;
      ;;   Commit staged changes with `message`. ...
      ;;
      ;;   :repo     repository directory ...

  A quoted symbol, a string, and a Var work too, which makes the argument easy
  to compute:

      (help 'git/commit)
      (help \"git/commit\")
      (help #'git/commit)

  A symbol naming a namespace prints that namespace's docstring followed by
  the names of its public Vars, which is how each integration documents its
  options and lists what it offers:

      (help clai.shell.git)

  [[help!]] behaves the same, and called with no argument it lists the current
  namespace, which is handy for exploring a session:

      (help!)

  Aliases are resolved in the namespace the call is written in, so
  `(help git/commit)` works from `user` where `git` is an alias, and
  `(help str/join)` works from any namespace that requires `clojure.string`
  as `str`.

  ## Why [[help]] is a macro

  A function would receive the *value* of an unquoted symbol: `(help
  git/commit)` would pass the helper function itself, not its name, so the
  user would always have to write `(help 'git/commit)` or `(help
  #'git/commit)`. The macro instead sees the symbol as the user typed it,
  resolves it against the calling namespace while the form is compiled, and
  expands to a call that prints the resolved Var. Nothing is evaluated at
  runtime, so `(help git/commit)` costs no more than `(help #'git/commit)`.

  Resolving at compile time also means the Var is found the way the compiler
  would find it, including aliases and referred vars, and an explicit
  namespace name is loaded on demand if it is not loaded yet.

  [[help*]] is the function the macro expands to; it takes an already
  resolved Var (or, for a quoted symbol or string, resolves it at call time)
  and prints its help. [[help-text]] returns that text as a string instead of
  printing it."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Targets
;; ---------------------------------------------------------------------------

(defn- var-target?
  "True when `x` is a Var, the target type [[help*]] prints directly."
  [x]
  (instance? clojure.lang.Var x))

(defn- namespace-target?
  "True when `x` is a Namespace, which [[help*]] prints as a namespace."
  [x]
  (instance? clojure.lang.Namespace x))

(defn- var-symbol
  "The fully qualified symbol naming `v`."
  [v]
  (let [{:keys [ns name]} (meta v)]
    (symbol (str (ns-name ns)) (str name))))

(defn- target-symbol
  "Coerce a help target (a symbol or a string) to a symbol, or nil."
  [target]
  (cond
    (symbol? target) target
    (string? target) (symbol target)
    :else            nil))

(defn- resolve-var
  "Resolve `sym` to a Var the way the compiler would in [[*ns*]], loading the
  namespace named by an explicit namespace prefix when necessary.

  Returns nil when `sym` does not name a Var, so a class or special form is
  reported as not found rather than as a broken Var."
  [sym]
  (or (let [v (ns-resolve *ns* sym)]
        (when (var-target? v) v))
      (when (namespace sym)
        (try
          (let [v (requiring-resolve sym)]
            (when (var-target? v) v))
          (catch Throwable _ nil)))))

(defn- resolve-namespace
  "Resolve `sym` to a namespace, accepting an ns name or an alias of the
  current namespace. Returns nil when `sym` names no namespace."
  [sym]
  (when (nil? (namespace sym))
    (or (find-ns sym)
        (get (ns-aliases *ns*) sym)
        (try
          (require sym)
          (find-ns sym)
          (catch Throwable _ nil)))))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- var-kind
  "Describe what kind of Var `v` is, as printed in the help header."
  [v]
  (let [m (meta v)]
    (cond
      (:macro m)    "macro"
      (:arglists m) "function"
      (:dynamic m)  "dynamic var"
      :else         "var")))

(defn- signature-line
  "Render one call signature of `name`, such as `  (commit message opts)`."
  [name arglist]
  (str "  (" name
       (when (and (sequential? arglist) (seq arglist))
         (str " " (str/join " " (map pr-str arglist))))
       ")"))

(defn- signature-lines
  "Render every call signature of the Var named `name`."
  [name arglists]
  (when (sequential? arglists)
    (mapv #(signature-line name %) arglists)))

(defn- indent
  "Indent the non-blank lines of `text` by two spaces, leaving blank lines
  empty so they carry no trailing whitespace."
  [text]
  (->> (str/split-lines text)
       (map (fn [line] (if (str/blank? line) "" (str "  " line))))
       (str/join "\n")))

(defn- doc-text
  "The docstring of a Var or namespace, or nil when it has none."
  [m]
  (when-let [doc (:doc m)]
    (when-not (str/blank? doc)
      (str/trimr doc))))

(defn- var-help-text
  "Render the help for a resolved Var."
  [v]
  (let [{:keys [name arglists malli/schema] :as m} (meta v)
        header (concat [(str (var-symbol v) " (" (var-kind v) ")")]
                       (signature-lines name arglists)
                       (when schema [(str "  Schema: " (pr-str schema))]))]
    (if-let [doc (doc-text m)]
      (str (str/join "\n" header) "\n\n" (indent doc))
      (str/join "\n" header))))

(defn- public-var-lines
  "Render the name of every public Var in namespace `n`, sorted by name and
  without its docstring."
  [n]
  (->> (keys (ns-publics n))
       (sort-by str)
       (mapv #(str "  " %))))

(defn- namespace-help-text
  "Render the help for a namespace: its docstring followed by the names of
  its public Vars."
  [n]
  (let [doc  (doc-text (meta n))
        vars (public-var-lines n)]
    (->> [(str (ns-name n) " (namespace)")
          doc
          (when (seq vars)
            (str "Public vars:\n" (str/join "\n" vars)))]
         (remove nil?)
         (str/join "\n\n"))))

(defn help-text
  "Return the help text for `target` as a string, without printing it.

  `target` may be a Var, a Namespace, a symbol, or a string. A symbol or
  string naming a Var is resolved in the current namespace (`*ns*`), so
  aliases work and an explicit namespace prefix is loaded on demand; a symbol,
  string, or Namespace naming a namespace prints that namespace's docstring
  followed by the names of its public Vars. An unresolvable target returns a
  short message instead of throwing."
  [target]
  (cond
    (var-target? target)
    (var-help-text target)

    (namespace-target? target)
    (namespace-help-text target)

    :else
    (let [sym (target-symbol target)]
      (if-let [v (some-> sym resolve-var)]
        (var-help-text v)
        (if-let [n (some-> sym resolve-namespace)]
          (namespace-help-text n)
          (str "No Var or namespace found for " target "."))))))

;; ---------------------------------------------------------------------------
;; Printing
;; ---------------------------------------------------------------------------

(defn help*
  "Print the help generated by [[help-text]] for `target` and return nil.

  [[help]] expands to a call to this function with the symbol already
  resolved to a Var; calling it directly is useful for a target computed at
  runtime, in which case a symbol is resolved in the current namespace. A Var,
  a Namespace, a symbol, or a string are all accepted."
  [target]
  (println (help-text target))
  nil)

(defmacro help
  "Print the documentation and call signatures of a Var, without quoting it.

      (help git/commit)
      (help cli/*cwd*)
      (help clai.shell.git)

  `sym` is normally an unquoted symbol, which is resolved in the calling
  namespace while the form is compiled (aliases included). A quoted symbol, a
  string, or a Var is also accepted, and so is a symbol or string naming a
  namespace, which prints that namespace's docstring. With no argument it
  prints the help for [[help]] itself. Returns nil.

  See [[help-text]] for the same text as a string."
  ([]
   `(help* (var ~(var-symbol #'help))))
  ([sym]
   (cond
     ;; `(help 'git/commit)`: the caller quoted the symbol for us.
     (and (seq? sym) (= 2 (count sym)) (= 'quote (first sym)))
     `(help* ~sym)

     (symbol? sym)
     (if-let [v (resolve-var sym)]
       `(help* (var ~(var-symbol v)))
       ;; Leave the lookup to runtime so an unknown name prints a message
       ;; instead of failing to compile.
       `(help* '~sym))

     :else
     `(help* ~sym))))

(defmacro help!
  "Alias for [[help]], kept for callers that use the side-effecting name.

  With no argument it prints the help for the current namespace (`*ns*`): the
  namespace's docstring followed by the names of its public Vars. A namespace
  target prints the same listing for that namespace."
  ([]
   `(help* *ns*))
  ([target]
   `(help ~target)))
