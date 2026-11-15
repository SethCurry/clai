(ns clai.agent.tools.builtin.lsp
  "Built-in navigation tools backed by `clojure-lsp`: `lsp_definition`,
  `lsp_references`, `lsp_hover`, `lsp_symbols`, `lsp_outline`, and
  `lsp_diagnostics`.

  An agent reading Clojure code otherwise spends a lot of wall-clock time
  grepping for `defn`, opening whole files and guessing which `foo` on which
  line is meant. `clojure-lsp` already knows the answer -- it analyzes the
  project once and then answers navigation questions in milliseconds -- so
  these tools hand it the questions instead. The server is shared per project
  and kept alive by `clai.lsp`, so the expensive analysis is paid once per
  session, not once per question.

  Every tool accepts either a `symbol` name (`\"clai.util/error-message\"` or
  just `\"error-message\"`) or a `file` plus a 1-based `line` (with an optional
  `column`/`symbol` to pick the identifier on that line), so an agent can point
  at code it just read with `read_file` without computing byte offsets."
  (:require [clai.agent.tools.builtin.common :as common]
            [clai.agent.tools.schema :as schema]
            [clai.lsp :as lsp]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Schemas and limits
;; ---------------------------------------------------------------------------

(def position-args
  "Arguments shared by the position-taking tools: either a `:symbol` name, or a
  `:file` with a 1-based `:line` and, optionally, a 1-based `:column` or the
  `:symbol` to look at on that line."
  [:map
   [:symbol {:optional true} [:maybe :string]]
   [:file {:optional true} [:maybe :string]]
   [:line {:optional true} [:maybe [:or :int :string]]]
   [:column {:optional true} [:maybe [:or :int :string]]]])

(def max-references
  "Cap on the number of reference locations listed by `lsp_references`."
  200)

(def max-symbols
  "Cap on the number of symbols listed by `lsp_symbols` and `lsp_outline`."
  60)

(def max-diagnostics
  "Cap on the number of diagnostics listed by `lsp_diagnostics`."
  100)

(def max-excerpt-lines
  "Cap on the number of source lines shown for a definition."
  40)

(def max-excerpt-chars
  "Cap on the number of characters shown for a definition."
  4000)

(def diagnostics-timeout-ms
  "How long `lsp_diagnostics` waits for clojure-lsp to report on one file."
  30000)

(def analysis-timeout-ms
  "How long project-wide `lsp_diagnostics` waits for analysis to settle."
  180000)

(def symbol-kinds
  "LSP `SymbolKind` numbers rendered as readable names."
  {1 "file" 2 "module" 3 "namespace" 4 "package" 5 "class" 6 "method"
   7 "property" 8 "field" 9 "constructor" 10 "enum" 11 "interface"
   12 "function" 13 "variable" 14 "constant" 15 "string" 16 "number"
   17 "boolean" 18 "array" 19 "object" 20 "key" 21 "null" 22 "enum-member"
   23 "struct" 24 "event" 25 "operator" 26 "type-parameter"})

(def diagnostic-severities
  "LSP `DiagnosticSeverity` numbers rendered as readable names."
  {1 "error" 2 "warning" 3 "info" 4 "hint"})

(def declaration-forms
  "Form names whose *next* token is the name of the var being defined, so a
  bare `line` in a file points at the var rather than at `defn`."
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol"
    "defrecord" "deftype" "defonce" "definterface" "declare" "ns"})
;; ---------------------------------------------------------------------------
;; Argument and path helpers
;; ---------------------------------------------------------------------------

(defn- json-int
  "Coerce a JSON argument to a long, accepting numbers and numeric strings.
  Returns nil when `value` is missing or not an integer."
  {:malli/schema [:-> :any [:maybe :int]]}
  [value]
  (cond
    (nil? value)    nil
    (number? value) (long value)
    (string? value) (when (re-matches #"-?\d+" (str/trim value))
                      (Long/parseLong (str/trim value)))
    :else           nil))

(defn- relative-path
  "Return `path` relative to `root` when it lives inside it, else absolute."
  {:malli/schema [:-> #'lsp/PathLike #'lsp/PathLike :string]}
  [root path]
  (let [root-path (.normalize (.toAbsolutePath (.toPath (io/file root))))
        path-path (.normalize (.toAbsolutePath (.toPath (io/file path))))]
    (if (str/starts-with? (str path-path) (str root-path File/separator))
      (str (.relativize root-path path-path))
      (str path-path))))

(defn- split-symbol
  "Split a `symbol` name into `[namespace name]`, e.g.
  `\"clai.util/error-message\"` becomes `[\"clai.util\" \"error-message\"]`.
  A name without a namespace returns `[nil name]`."
  {:malli/schema [:-> :string [:tuple [:maybe :string] :string]]}
  [symbol]
  (let [symbol (str/trim symbol)
        slash  (str/index-of symbol "/")]
    (if slash
      [(subs symbol 0 slash) (subs symbol (inc slash))]
      [nil symbol])))

(defn- identifier-index
  "Return the character offset in `text` of the identifier `name`, or of the
  first identifier when `name` is nil.

  Tokens that name a declaration form or carry metadata are skipped, so
  `\"  (defn ^:private foo [x]\"` points at `foo`. Falls back to 0 when the line
  holds no identifier at all."
  {:malli/schema [:-> :string [:maybe :string] nat-int?]}
  [text name]
  (let [text   (or text "")
        tokens (re-seq #"[^\s()\[\]{}\"',;`]+" text)
        tokens (if (declaration-forms (first tokens)) (rest tokens) tokens)
        tokens (remove #(or (str/starts-with? % "^") (str/starts-with? % ":")) tokens)
        wanted (if (str/blank? (str name)) (first tokens) name)]
    (if-let [index (and wanted (str/index-of text wanted))]
      (long index)
      0)))

;; ---------------------------------------------------------------------------
;; LSP lookups
;; ---------------------------------------------------------------------------

(defn- location-namespace
  "Return the namespace the file of `location` defines, read from its `ns`
  form, or nil when that cannot be determined."
  {:malli/schema [:-> #'lsp/Location [:maybe :string]]}
  [location]
  (let [path  (lsp/uri->path (:uri location))
        line  (get-in location [:range :start :line])
        lines (vec (str/split-lines (slurp path)))]
    (some->> (subvec lines 0 (min (count lines) (inc (or line 0))))
             (keep #(second (re-find #"^\(ns\s+([^\s()\[\]{}\"^]+)" %)))
             last)))

(defn- symbol-position
  "Resolve a var or namespace `symbol` to the position of its definition.

  Returns `{:path :uri :position :location}`, where `:location` is the whole
  definition form as clojure-lsp reported it, or nil when no project symbol
  matches. A qualified symbol (`ns/name`) only matches definitions in `ns`."
  {:malli/schema [:-> #'lsp/Server :string
                  [:maybe [:map
                           [:path :string]
                           [:uri :string]
                           [:position #'lsp/Position]
                           [:location #'lsp/Location]]]]}
  [server symbol]
  (let [[ns name] (split-symbol symbol)
        matches   (filterv #(= name (:name %)) (lsp/workspace-symbols server name))
        matches   (if ns
                    (filterv #(= ns (location-namespace (:location %))) matches)
                    matches)]
    (when-let [{:keys [name location]} (first matches)]
      (let [path  (lsp/uri->path (:uri location))
            line  (get-in location [:range :start :line])
            text  (get (vec (str/split-lines (slurp path))) line "")]
        {:path     path
         :uri      (:uri location)
         :position {:line line :character (identifier-index text name)}
         :location location}))))

(defn- file-position
  "Resolve `:file`, `:line`, `:column`, and `:symbol` in `args` to a position.

  `:line` and `:column` are 1-based, the way an editor shows them. Without a
  `:column` the identifier named by `:symbol` on that line is used, or the first
  identifier of the line when no name is given. The file is synced to `server`
  first, so a position in a file the agent just wrote is analyzed as it is now."
  {:malli/schema [:-> #'lsp/Server #'position-args #'schema/tool-ctx
                  [:map
                   [:path :string]
                   [:uri :string]
                   [:position #'lsp/Position]]]}
  [server args ctx]
  (let [file (common/resolve-path (:file args) ctx)]
    (when-not (.isFile file)
      (throw (ex-info (str "No such file: " (.getAbsolutePath file))
                      {:file (:file args)})))
    (let [uri    (lsp/sync-file! server file)
          lines  (vec (str/split-lines (slurp file)))
          line   (max 0 (min (dec (or (json-int (:line args)) 1))
                             (max 0 (dec (count lines)))))
          text   (get lines line "")
          column (json-int (:column args))]
      {:path     (.getAbsolutePath file)
       :uri      uri
       :position {:line      line
                  :character (if column
                               (max 0 (dec column))
                               (identifier-index text (:symbol args)))}})))

(defn- missing-symbol-message
  "Explain that `symbol` has no definition in the project, suggesting close
  matches and pointing at `eval_clojure` for vars outside the project."
  {:malli/schema [:-> #'lsp/Server :string :string]}
  [server symbol]
  (let [[_ name] (split-symbol symbol)
        lower    (str/lower-case name)
        matches  (->> (lsp/workspace-symbols server name)
                      (map :name)
                      distinct
                      (filter (fn [candidate]
                                (let [candidate (str/lower-case candidate)]
                                  (or (str/includes? candidate lower)
                                      (str/includes? lower candidate)))))
                      (take 5))]
    (str "No definition found in the project for '" symbol "'."
         (when (seq matches)
           (str " Close matches: " (str/join ", " matches) "."))
         " clojure-lsp only indexes this project's sources; for a clojure.core"
         " or library var use eval_clojure with (doc " name ") or (source "
         name ").")))

(defn- resolve-position
  "Resolve `args` to the position the navigation tools should ask about.

  Returns `{:path :uri :position}` plus, for a `:symbol` lookup, the whole
  definition form under `:fallback`; or `{:message ...}` when nothing can be
  resolved."
  {:malli/schema [:-> #'lsp/Server #'position-args #'schema/tool-ctx
                  [:map
                   [:message {:optional true} [:maybe :string]]
                   [:path {:optional true} :string]
                   [:uri {:optional true} :string]
                   [:position {:optional true} #'lsp/Position]
                   [:fallback {:optional true} [:maybe #'lsp/Location]]]]}
  [server args ctx]
  (cond
    (not (str/blank? (str (:file args))))
    (file-position server args ctx)

    (not (str/blank? (str (:symbol args))))
    (if-let [{:keys [path uri position location]} (symbol-position server (:symbol args))]
      {:path path :uri uri :position position :fallback location}
      {:message (missing-symbol-message server (:symbol args))})

    :else
    {:message "Pass `symbol` (a var or namespace name), or `file` with a 1-based `line`, to say what to look at."}))

(defn- target-text
  "Describe what `args` pointed at: the symbol name, or `path:line:column`."
  {:malli/schema [:-> :string :map #'position-args :string]}
  [root resolved args]
  (if-not (str/blank? (str (:symbol args)))
    (str "'" (str/trim (:symbol args)) "'")
    (let [{:keys [path position]} resolved]
      (str (relative-path root path)
           ":" (inc (:line position))
           ":" (inc (:character position))))))

(defn- location-text
  "Render `location` as `path:line:column`, with one-based numbers."
  {:malli/schema [:-> :string #'lsp/Location :string]}
  [root location]
  (let [{:keys [uri range]} location]
    (str (relative-path root (lsp/uri->path uri))
         ":" (inc (get-in range [:start :line]))
         ":" (inc (get-in range [:start :character])))))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn- excerpt
  "Return the source form starting at zero-based `line` in `path`, capped by
  [[max-excerpt-lines]] and [[max-excerpt-chars]].

  The form grows line by line until a line starts in column 0 with `(` or `;`
  (the start of the next top-level form or comment) or the caps are reached.
  Blank lines at the end are dropped."
  {:malli/schema [:-> :string int? :string]}
  [path line]
  (let [lines (vec (str/split-lines (slurp path)))
        start (max 0 (min line (dec (count lines))))
        body  (loop [index start
                     taken []]
                (let [text (get lines index)]
                  (cond
                    (nil? text)                         taken
                    (>= (count taken) max-excerpt-lines) taken
                    (and (seq taken)
                         (or (str/starts-with? text "(")
                             (str/starts-with? text ";"))) taken
                    :else (recur (inc index) (conj taken text)))))
        body  (->> body (reverse) (drop-while str/blank?) (reverse))]
    (common/truncate (str/join "\n" body) max-excerpt-chars)))

(defn- definition-text
  "Render one definition `location` with a snippet of its source."
  {:malli/schema [:-> :string #'lsp/Location :string]}
  [root location]
  (str (location-text root location)
       "\n"
       (excerpt (lsp/uri->path (:uri location))
                (get-in location [:range :start :line]))))

(defn- hover-text
  "Render the `:contents` of LSP hover information as plain text, or nil."
  {:malli/schema [:-> [:maybe :map] [:maybe :string]]}
  [hover]
  (let [contents (:contents hover)]
    (cond
      (string? contents)  contents
      (map? contents)     (:value contents)
      (sequential? contents) (str/join "\n\n" (map #(if (map? %) (:value %) (str %))
                                                   contents))
      :else               nil)))

(defn- symbol-text
  "Render one LSP `SymbolInformation` as `path:line:column kind name (ns)`."
  {:malli/schema [:-> :string :map :string]}
  [root {:keys [name kind location containerName]}]
  (let [ns (or containerName (location-namespace location))]
    (str (location-text root location)
         "  " (get symbol-kinds kind "symbol")
         "  " name
         (when (and ns (not= 3 kind)) (str " (" ns ")")))))

(defn- outline-lines
  "Render `symbols` (LSP `DocumentSymbol`s, possibly nested) as indented lines
  of `kind name :line`, indented by `depth` levels."
  {:malli/schema [:-> [:sequential :map] nat-int? [:vector :string]]}
  [symbols depth]
  (vec (mapcat (fn [symbol]
                 (let [range (or (:range symbol) (get-in symbol [:location :range]))
                       line  (inc (get-in range [:start :line]))
                       label (str (apply str (repeat (* 2 depth) " "))
                                  (get symbol-kinds (:kind symbol) "symbol")
                                  "  " (:name symbol)
                                  "  :" line)]
                   (cons label (outline-lines (:children symbol) (inc depth)))))
               symbols)))

(defn- diagnostic-text
  "Render one LSP `Diagnostic` as `path:line:column severity message [code]`."
  {:malli/schema [:-> :string :map :string]}
  [path {:keys [range severity message code]}]
  (str path
       ":" (inc (get-in range [:start :line]))
       ":" (inc (get-in range [:start :character]))
       "  " (get diagnostic-severities severity "warning")
       "  " (str/trim (str/replace (str message) #"\s+" " "))
       (when code (str "  [" code "]"))))

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(defn- session
  "Return `{:root :server}` for the project the session in `ctx` works in,
  starting a shared `clojure-lsp` server on first use."
  {:malli/schema [:-> #'schema/tool-ctx
                  [:map
                   [:root :string]
                   [:server #'lsp/Server]]]}
  [ctx]
  (let [opts (or (:lsp ctx) {})
        root (lsp/project-root (or (:root opts)
                                   (:cwd ctx)
                                   (System/getProperty "user.dir")))]
    {:root   root
     :server (lsp/server-for root opts)}))

(defn- definition-fn
  "Implementation of the `lsp_definition` tool: find where the var or
  namespace at the described position is defined, with a source excerpt."
  {:malli/schema [:-> #'position-args #'schema/tool-ctx #'schema/tool-result]}
  [args ctx]
  (let [{:keys [root server]} (session ctx)
        resolved              (resolve-position server args ctx)]
    (if-let [message (:message resolved)]
      message
      (let [{:keys [uri position fallback]} resolved
            found (lsp/definition server uri position)
            found (if (seq found) found (when fallback [fallback]))]
        (if (empty? found)
          (str "No definition found at " (target-text root resolved args) ".")
          (str/join "\n\n" (map #(definition-text root %) found)))))))

(defn- references-fn
  "Implementation of the `lsp_references` tool: list every place the var or
  namespace at the described position is used in the project."
  {:malli/schema [:-> #'position-args #'schema/tool-ctx #'schema/tool-result]}
  [args ctx]
  (let [{:keys [root server]} (session ctx)
        resolved              (resolve-position server args ctx)]
    (if-let [message (:message resolved)]
      message
      (let [{:keys [uri position]} resolved
            locations (sort-by (juxt #(relative-path root (lsp/uri->path (:uri %)))
                                     #(get-in % [:range :start :line])
                                     #(get-in % [:range :start :character]))
                               (lsp/references server uri position))
            shown     (take max-references locations)]
        (if (empty? locations)
          (str "No references to " (target-text root resolved args) " in the project.")
          (str (count locations) " reference" (when (not= 1 (count locations)) "s")
               " to " (target-text root resolved args) ":\n"
               (str/join "\n" (map #(location-text root %) shown))
               (when (> (count locations) (count shown))
                 (str "\n... [" (- (count locations) (count shown))
                      " more references]"))))))))

(defn- hover-fn
  "Implementation of the `lsp_hover` tool: show the signature, docstring and
  source link of the var at the described position."
  {:malli/schema [:-> #'position-args #'schema/tool-ctx #'schema/tool-result]}
  [args ctx]
  (let [{:keys [root server]} (session ctx)
        resolved              (resolve-position server args ctx)]
    (if-let [message (:message resolved)]
      message
      (let [{:keys [uri position]} resolved]
        (or (hover-text (lsp/hover server uri position))
            (str "No documentation found at " (target-text root resolved args) "."))))))

(defn- symbols-fn
  "Implementation of the `lsp_symbols` tool: search the project for vars and
  namespaces whose name matches the query."
  {:malli/schema [:-> [:map [:query [:maybe :string]]
                      [:limit {:optional true} [:maybe [:or :int :string]]]]
                  #'schema/tool-ctx #'schema/tool-result]}
  [args ctx]
  (let [{:keys [root server]} (session ctx)
        query                 (str/trim (str (:query args)))]
    (if (str/blank? query)
      "Pass `query`: part of the name of a var or namespace to look for."
      (let [matches (lsp/workspace-symbols server query)
            limit   (min (or (json-int (:limit args)) max-symbols) max-symbols)
            shown   (take limit matches)]
        (if (empty? matches)
          (str "No project symbols match '" query "'.")
          (str (count matches) " symbol" (when (not= 1 (count matches)) "s")
               " matching '" query "':\n"
               (str/join "\n" (map #(symbol-text root %) shown))
               (when (> (count matches) (count shown))
                 (str "\n... [" (- (count matches) (count shown)) " more; pass a"
                      " narrower query]"))))))))

(defn- outline-fn
  "Implementation of the `lsp_outline` tool: list the namespaces and vars one
  file defines, with line numbers."
  {:malli/schema [:-> [:map [:file [:maybe :string]]] #'schema/tool-ctx #'schema/tool-result]}
  [args ctx]
  (let [{:keys [root server]} (session ctx)]
    (if (str/blank? (str (:file args)))
      "Pass `file`: the Clojure source file to outline."
      (let [file (common/resolve-path (:file args) ctx)]
        (when-not (.isFile file)
          (throw (ex-info (str "No such file: " (.getAbsolutePath file)) {:file (:file args)})))
        (let [symbols (lsp/document-symbols server (lsp/sync-file! server file))]
          (if (empty? symbols)
            (str "No symbols found in " (relative-path root file) ".")
            (str (relative-path root file) ":\n"
                 (str/join "\n" (take max-symbols (outline-lines symbols 0))))))))))

(defn- file-diagnostics
  "Return a report of the diagnostics clojure-lsp has for `file`, asking it to
  re-analyze the file first so an edit made a moment ago is included."
  {:malli/schema [:-> :string #'lsp/Server File :string]}
  [root server file]
  (let [uri    (lsp/refresh-file! server file)
        result (lsp/await-diagnostics server uri diagnostics-timeout-ms)
        path   (relative-path root file)]
    (cond
      (= lsp/timeout result)
      (str "clojure-lsp did not report diagnostics for " path " within "
           (quot diagnostics-timeout-ms 1000) "s; its analysis may still be"
           " running. Try again, or ask for the whole project.")

      (empty? result)
      (str "No diagnostics for " path ".")

      :else
      (str (count result) " diagnostic" (when (not= 1 (count result)) "s")
           " in " path ":\n"
           (str/join "\n" (map #(diagnostic-text path %) result))))))

(defn- project-diagnostics
  "Return a report of every diagnostic clojure-lsp published for the project,
  waiting for its initial analysis to finish first."
  {:malli/schema [:-> :string #'lsp/Server :string]}
  [root server]
  (let [_         (lsp/refresh-project! server)
        published (lsp/await-analysis server {:timeout-ms analysis-timeout-ms})
        entries   (->> published
                       (keep (fn [[uri diagnostics]]
                               (when (seq diagnostics)
                                 [(relative-path root (lsp/uri->path uri)) diagnostics])))
                       (sort-by first))
        total     (reduce + 0 (map (comp count second) entries))
        lines     (mapcat (fn [[path diagnostics]]
                            (map #(diagnostic-text path %) diagnostics))
                          entries)]
    (if (zero? total)
      (str "No diagnostics reported for " root ".")
      (str total " diagnostic" (when (not= 1 total) "s") " in "
           (count entries) " file" (when (not= 1 (count entries)) "s")
           " across " root ":\n"
           (str/join "\n" (take max-diagnostics lines))
           (when (> total max-diagnostics)
             (str "\n... [" (- total max-diagnostics) " more; ask about a"
                  " single file to see the rest]"))))))

(defn- diagnostics-fn
  "Implementation of the `lsp_diagnostics` tool: report lint findings for one
  file, or for the whole project when no file is given."
  {:malli/schema [:-> [:map [:file {:optional true} [:maybe :string]]]
                  #'schema/tool-ctx #'schema/tool-result]}
  [args ctx]
  (let [{:keys [root server]} (session ctx)]
    (if (str/blank? (str (:file args)))
      (project-diagnostics root server)
      (file-diagnostics root server (common/resolve-path (:file args) ctx)))))

;; ---------------------------------------------------------------------------
;; Tool specs
;; ---------------------------------------------------------------------------

(def position-properties
  "The JSON-schema properties shared by the position-taking tools."
  {"symbol" {:type "string"
             :description (str "A var or namespace name, e.g. "
                               "\"clai.util/error-message\" or \"error-message\"."
                               " A bare name is matched across the project.")}
   "file"   {:type "string"
             :description "Path to a source file, relative to the session cwd."}
   "line"   {:type "integer"
             :description "1-based line number in `file`."}
   "column" {:type "integer"
             :description (str "1-based column number in `file`. Defaults to the"
                               " first identifier on the line, or the one named"
                               " by `symbol`.")}})

(def position-description
  "How the position-taking tools are pointed at code, shared by their
  descriptions."
  (str " Point at code with `symbol`, or with `file` plus a 1-based `line` and"
       " optionally `column` (defaults to the first identifier on the line, or"
       " to `symbol` when given)."))

(def definition-spec
  "The Ollama tool definition of `lsp_definition`."
  {:type "function"
   :function
   {:name "lsp_definition"
    :description (str "Find where a Clojure var or namespace is defined, using"
                      " clojure-lsp's project analysis. Returns the"
                      " `path:line:column` and a snippet of the source."
                      position-description
                      " Much faster and more accurate than grepping for `defn`."
                      " Only this project's sources are indexed; for"
                      " clojure.core or library vars use `eval_clojure` with"
                      " `(doc ...)` or `(source ...)`. The first call in a"
                      " session may take a few seconds while clojure-lsp"
                      " analyzes the project.")
    :parameters {:type "object"
                 :properties position-properties
                 :required []}}})

(def references-spec
  "The Ollama tool definition of `lsp_references`."
  {:type "function"
   :function
   {:name "lsp_references"
    :description (str "List every place in the project a Clojure var or"
                      " namespace is used, as `path:line:column`, using"
                      " clojure-lsp's analysis. Use it before changing a"
                      " function's signature or behaviour, or to find callers."
                      position-description)
    :parameters {:type "object"
                 :properties position-properties
                 :required []}}})

(def hover-spec
  "The Ollama tool definition of `lsp_hover`."
  {:type "function"
   :function
   {:name "lsp_hover"
    :description (str "Show what a Clojure var is: its signature, docstring and"
                      " source location, as Markdown. Cheaper than opening the"
                      " file, and works for vars in this project. For a"
                      " clojure.core var, `eval_clojure` with `(doc ...)` also"
                      " works."
                      position-description)
    :parameters {:type "object"
                 :properties position-properties
                 :required []}}})

(def symbols-spec
  "The Ollama tool definition of `lsp_symbols`."
  {:type "function"
   :function
   {:name "lsp_symbols"
    :description (str "Search the project for vars and namespaces whose name"
                      " matches `query` (a substring, e.g. \"error-message\" or"
                      " \"clai.util\"). Returns `path:line:column`, the kind,"
                      " the name and the namespace, which makes it the quickest"
                      " way to find out where something lives when you only"
                      " know part of its name.")
    :parameters {:type "object"
                 :properties {"query" {:type "string"
                                       :description "Part of a var or namespace name."}
                              "limit" {:type "integer"
                                       :description (str "Maximum results (default "
                                                         max-symbols ").")}}
                 :required ["query"]}}})

(def outline-spec
  "The Ollama tool definition of `lsp_outline`."
  {:type "function"
   :function
   {:name "lsp_outline"
    :description (str "List the namespaces and vars one Clojure file defines,"
                      " with line numbers, instead of reading the whole file."
                      " Useful to get an overview before reading or editing"
                      " parts of it.")
    :parameters {:type "object"
                 :properties {"file" {:type "string"
                                      :description "Path to a Clojure source file."}}
                 :required ["file"]}}})

(def diagnostics-spec
  "The Ollama tool definition of `lsp_diagnostics`."
  {:type "function"
   :function
   {:name "lsp_diagnostics"
    :description (str "List lint problems (clj-kondo errors and warnings found"
                      " by clojure-lsp) for one file, or for the whole project"
                      " when `file` is omitted. Ask about a file right after"
                      " editing it to check that the edit is valid; ask for the"
                      " whole project to find what is broken.")
    :parameters {:type "object"
                 :properties {"file" {:type "string"
                                      :description (str "Path to a source file;"
                                                        " omit for the whole"
                                                        " project.")}}
                 :required []}}})

(defn tools
  "Return the `clojure-lsp` navigation tools: `lsp_definition`,
  `lsp_references`, `lsp_hover`, `lsp_symbols`, `lsp_outline` and
  `lsp_diagnostics`."
  {:malli/schema [:-> [:vector #'schema/Tool]]}
  []
  [{:id "lsp_definition" :handler definition-fn :spec definition-spec}
   {:id "lsp_references" :handler references-fn :spec references-spec}
   {:id "lsp_hover" :handler hover-fn :spec hover-spec}
   {:id "lsp_symbols" :handler symbols-fn :spec symbols-spec}
   {:id "lsp_outline" :handler outline-fn :spec outline-spec}
   {:id "lsp_diagnostics" :handler diagnostics-fn :spec diagnostics-spec}])
