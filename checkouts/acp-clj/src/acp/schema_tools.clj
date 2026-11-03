(ns acp.schema-tools
  "Malli schemas for the Agent Client Protocol.

  The canonical list of protocol types lives in `clai/acp/schema.json`.  This
  namespace loads that file at startup and converts every entry in `$defs`
  into a Malli schema.  It also generates a `->Type` constructor for every
  type, so the protocol can be used without duplicating the schema by hand.

  It can also *write those definitions out as source*: `spit-generated!`
  emits a standalone namespace containing every `Type` and `->Type` var, so a
  process can load compiled schemas instead of re-parsing schema.json at
  startup."
  (:require [acp.schema-runtime :as rt]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; JSON schema -> Malli
;; ---------------------------------------------------------------------------

(def ^:private schema-doc
  (let [resource (io/resource "acp/schema.json")]
    (when-not resource
      (throw (ex-info "acp/schema.json is missing from the classpath" {})))
    (json/parse-string (slurp resource) true)))

(def ^:private schema-defs
  "The `$defs` map from schema.json, keyed by protocol type name."
  ;; Cheshire keywordizes all object keys.  Type names are used as symbols and
  ;; in `$ref` strings, so keep those as strings.
  (into {}
        (map (fn [[k v]] [(name k) v]))
        (:$defs schema-doc)))

;; The protocol defines a type named `Error`.  Remove java.lang's automatic
;; class mapping before creating the schema var so the protocol name does not
;; produce a shadowing warning.  On a namespace reload the mapping is already
;; our var, so leave it alone.
(when (class? (get (ns-map *ns*) 'Error))
  (ns-unmap *ns* 'Error))

(defonce ^:private schema-vars
  ;; Create every var up front.  Schemas may refer to types defined later in
  ;; the file (or to one another), just like a recursive JSON schema.
  (into {}
        (map (fn [type-name]
               [type-name (intern *ns* (symbol type-name))]))
        (keys schema-defs)))

(defn- schema-var
  [type-name]
  (or (get schema-vars type-name)
      (throw (ex-info (str "Unknown ACP schema type: " type-name)
                      {:type type-name}))))

(defn- ref->type-name
  [ref]
  (last (str/split ref #"/")))

(defn- union
  [schemas]
  (let [schemas (vec (remove nil? schemas))
        has-nil? (some #(= :nil %) schemas)
        non-nil (remove #(= :nil %) schemas)]
    (cond
      (empty? schemas) :any
      (and (= 1 (count schemas)) (not has-nil?)) (first schemas)
      has-nil? (if (empty? non-nil)
                 :nil
                 [:maybe (if (= 1 (count non-nil))
                           (first non-nil)
                           (into [:or] non-nil))])
      :else (into [:or] schemas))))

(defn- const-branches->enum
  "Collapse a `oneOf`/`anyOf` made entirely of `const` values into an enum."
  [branches]
  (when (and (seq branches)
             (every? #(and (map? %) (contains? % :const)) branches))
    (into [:enum] (map :const branches))))

(defn- object-with-base?
  "True when a node itself declares object keys in addition to any union or
  conjunction it has."
  [node]
  (or (= "object" (:type node))
      (seq (:properties node))
      (seq (:required node))))

(declare convert convert-object)

(defn- int-schema
  [node]
  (let [opts (cond-> {}
               (number? (:minimum node)) (assoc :min (:minimum node))
               (number? (:maximum node)) (assoc :max (:maximum node)))]
    (if (seq opts) [:int opts] :int)))

(defn- number-schema
  [node]
  ;; JSON Schema "number" includes integers.  `number?` keeps that behaviour
  ;; while still allowing integral values.  Bounds use malli's comparator
  ;; schemas (rather than opaque predicate functions) so the result stays
  ;; printable source; see `generated-source` below.
  (let [min (:minimum node)
        max (:maximum node)
        bounds (cond-> []
                 (number? min) (conj [:>= min])
                 (number? max) (conj [:<= max]))]
    (if (seq bounds)
      (into [:and 'number?] bounds)
      'number?)))

(defn- string-schema
  [node]
  (let [opts (cond-> {}
               (number? (:minLength node)) (assoc :min (:minLength node))
               (number? (:maxLength node)) (assoc :max (:maxLength node)))]
    (if (seq opts) [:string opts] :string)))

(defn- array-schema
  [node]
  (let [item (if (contains? node :items)
               (convert (:items node))
               :any)
        opts (cond-> {}
               (number? (:minItems node)) (assoc :min (:minItems node))
               (number? (:maxItems node)) (assoc :max (:maxItems node)))]
    (if (seq opts) [:vector opts item] [:vector item])))

(defn- type-schema
  [type node]
  (case type
    "string"  (string-schema node)
    "integer" (int-schema node)
    "number"  (number-schema node)
    "boolean" :boolean
    "null"    :nil
    "array"   (array-schema node)
    "object"  (convert-object node)
    :any))

(defn- object-property-entries
  [node]
  (let [required (set (map keyword (:required node)))]
    (for [[k property-node] (:properties node)]
      (let [prop-key (keyword k)
            schema (convert property-node)]
        (if (contains? required prop-key)
          [prop-key schema]
          [prop-key {:optional true} schema])))))

(defn- object-base-schema
  [node]
  (let [entries (object-property-entries node)
        additional (:additionalProperties node)]
    (cond
      ;; `additionalProperties` with a schema and no declared properties is a
      ;; string-keyed map.
      (and (map? additional)
           (empty? (:properties node)))
      [:map-of :string (convert additional)]

      (seq entries)
      (into [:map (if (false? additional) {:closed true} {:closed false})]
            entries)

      ;; A bare object accepts arbitrary keys.  `additionalProperties: false`
      ;; is the only way to close it (schema.json currently never does this).
      (false? additional)
      [:map {:closed true}]

      :else
      [:map {:closed false}])))

(defn- and-schema
  [schemas]
  (let [schemas (vec (remove nil? schemas))]
    (case (count schemas)
      0 :any
      1 (first schemas)
      (into [:and] schemas))))

(defn- convert-object
  [node]
  (let [base (object-base-schema node)
        constraints
        (concat
         (map convert (:allOf node))
         (when-let [branches (:oneOf node)]
           [(union (map convert branches))])
         (when-let [branches (:anyOf node)]
           [(union (map convert branches))])
         (when-let [not-node (:not node)]
           [[:not (convert not-node)]]))]
    (and-schema (cons base constraints))))

(defn- convert
  [node]
  (cond
    (nil? node)  :any
    (true? node) :any
    (false? node) [:fn (constantly false)]

    (contains? node :$ref)
    (schema-var (ref->type-name (:$ref node)))

    (contains? node :const)
    [:= (:const node)]

    (contains? node :enum)
    (let [values (:enum node)]
      (if (= 1 (count values))
        [:= (first values)]
        (into [:enum] values)))

    (and (contains? node :not)
         (not (object-with-base? node))
         (not (contains? node :allOf))
         (not (contains? node :oneOf))
         (not (contains? node :anyOf)))
    [:not (convert (:not node))]

    ;; A pure `allOf` is just a conjunction, e.g. a property that references a
    ;; string type.  Do not wrap it in an object map.
    (and (contains? node :allOf)
         (not (object-with-base? node))
         (not (contains? node :oneOf))
         (not (contains? node :anyOf)))
    (and-schema (map convert (:allOf node)))

    (and (contains? node :oneOf)
         (not (object-with-base? node)))
    (let [branches (:oneOf node)]
      (and-schema
       (cons (or (const-branches->enum branches)
                 (union (map convert branches)))
             (map convert (:allOf node)))))

    (and (contains? node :anyOf)
         (not (object-with-base? node)))
    (let [branches (:anyOf node)]
      (and-schema
       (cons (or (const-branches->enum branches)
                 (union (map convert branches)))
             (map convert (:allOf node)))))

    (or (contains? node :properties)
        (contains? node :allOf)
        (= "object" (:type node)))
    (convert-object node)

    (contains? node :type)
    (let [t (:type node)]
      (if (vector? t)
        (let [types (remove #{"null"} t)]
          (if (empty? types)
            :nil
            (let [base (union (map #(type-schema % node) types))]
              (if (some #{"null"} t)
                [:maybe base]
                base))))
        (type-schema t node)))

    :else :any))

;; Bind every schema var.  References between schemas are vars, so the order
;; of this loop does not matter.
(doseq [[type-name node] schema-defs]
  (alter-var-root (schema-var type-name) (constantly (convert node))))

;; ---------------------------------------------------------------------------
;; Source generation
;; ---------------------------------------------------------------------------
;;
;; `schema.json` is the source of truth while the protocol is in flux, but
;; parsing and converting it on every startup is wasted work.  The functions
;; below turn the converted schemas back into Clojure source, so a project can
;; check in a plain namespace and compile it instead.  Schema references are
;; emitted as unqualified `#'Type` var quotes, which malli resolves lazily.

(def ^:private source-width
  "Preferred maximum width of a generated source line."
  76)

(def ^:private rule
  (str ";; " (apply str (repeat (- source-width 3) \-))))

(defn- indent-str
  [n]
  (apply str (repeat n \space)))

(defn- var-source
  "Render a schema reference var as an unqualified var quote, so generated code
  resolves it in its own namespace."
  [v]
  (str "#'" (:name (meta v))))

(declare emit-form)

(defn- form->inline
  "Render `form` on a single line."
  [form]
  (cond
    (var? form) (var-source form)

    (map? form)
    (str "{" (str/join ", "
                        (map (fn [[k v]]
                               (str (form->inline k) " " (form->inline v)))
                             form))
         "}")

    (vector? form)
    (str "[" (str/join " " (map form->inline form)) "]")

    (set? form)
    (str "#{" (str/join " " (map form->inline form)) "}")

    (seq? form)
    (str "(" (str/join " " (map form->inline form)) ")")

    :else (pr-str form)))

(defn- emit-map-entry
  "Append one key/value pair of a map, starting at column `column`."
  [^StringBuilder sb [k v] column]
  (let [key-source (form->inline k)
        val-source (form->inline v)]
    (if (<= (+ column (count key-source) 1 (count val-source)) source-width)
      (do (.append sb key-source)
          (.append sb " ")
          (.append sb val-source))
      (do (.append sb key-source)
          (.append sb "\n")
          (.append sb (indent-str (inc column)))
          (emit-form sb v (inc column))))))

(defn- emit-form
  "Append `form` to `sb`, breaking collections across lines when they do not
  fit within `source-width`."
  [^StringBuilder sb form indent]
  (let [one-line (form->inline form)]
    (cond
      (or (not (coll? form))
          (empty? form)
          (<= (+ indent (count one-line)) source-width))
      (.append sb one-line)

      (map? form)
      (let [column (inc indent)]
        (.append sb "{")
        (doseq [[i entry] (map-indexed vector form)]
          (when (pos? i)
            (.append sb "\n")
            (.append sb (indent-str column)))
          (emit-map-entry sb entry column))
        (.append sb "}"))

      :else
      (let [items (vec form)
            [open close] (cond
                           (vector? form) ["[" "]"]
                           (set? form)    ["#{" "}"]
                           :else          ["(" ")"])
            first-column (+ indent (count open))
            head-source (when (symbol? (first items))
                          (form->inline (first items)))
            ;; Short heads align the remaining items under the first argument;
            ;; long ones keep the arguments two spaces in from the form.
            column (if (and head-source (<= (count head-source) 8))
                     (+ first-column (count head-source) 1)
                     (if (vector? form) first-column (+ indent 2)))]
        (.append sb open)
        (doseq [[i child] (map-indexed vector items)]
          (when (pos? i)
            (.append sb "\n")
            (.append sb (indent-str column)))
          (emit-form sb child (if (zero? i) first-column column)))
        (.append sb close)))))

(defn- def-source
  "Source text for `(def name doc form)`."
  [name-symbol doc form]
  (let [sb (StringBuilder.)]
    (.append sb "(def ")
    (.append sb (str name-symbol))
    (when (seq doc)
      (.append sb "\n  ")
      (.append sb (pr-str doc)))
    (.append sb "\n  ")
    (emit-form sb form 2)
    (.append sb ")")
    (str sb)))

(defn- section-source
  [title]
  (str rule "\n;; " title "\n" rule))

(defn- description
  "The JSON Schema description of `node`, collapsed onto one line."
  [node]
  (some-> (:description node)
          (str/replace #"\s+" " ")
          str/trim
          not-empty))

(defn- make-constructor
  [type-name infos union?]
  (if (some? infos)
    (rt/make-object-constructor (schema-var type-name) type-name infos union?)
    (rt/make-value-constructor (schema-var type-name) type-name)))

(defn- constructor-form
  [type-name infos union?]
  (if (some? infos)
    (list 'rt/make-object-constructor
          (schema-var type-name) type-name infos union?)
    (list 'rt/make-value-constructor (schema-var type-name) type-name)))

;; Bind the `->Type` constructor vars and collect the source text of every var
;; this namespace creates from schema.json.  Keeping both in one loop means the
;; in-memory namespace and the generated file cannot drift apart.
(def ^:private generated-definitions
  (into []
        (mapcat
         (fn [[type-name node]]
           (let [infos  (when (rt/plain-object-node? node)
                          (rt/property-infos node))
                 union? (boolean (or (contains? node :oneOf)
                                     (contains? node :anyOf)))]
             (intern *ns* (symbol (str "->" type-name))
                     (make-constructor type-name infos union?))
             [(section-source type-name)
              (def-source (symbol type-name)
                          (description node)
                          @(schema-var type-name))
              (def-source (symbol (str "->" type-name))
                          (str "Create a `" type-name "` value.")
                          (constructor-form type-name infos union?))])))
        schema-defs))

(defn- ns-header
  [ns-symbol schema-path]
  (str rule "\n"
       ";; ACP protocol schemas for namespace " ns-symbol ".\n"
       ";;\n"
       ";; Generated from " schema-path " by `acp.schema-tools`:\n"
       ";;\n"
       ";;     lein run -m acp.schema-tools <path> [namespace]\n"
       ";;\n"
       ";; This is ordinary Clojure source, so it can be compiled, read and\n"
       ";; commented.  Regenerating rewrites every form below this header; keep\n"
       ";; notes that must survive in a separate file.\n"
       rule "\n\n"))

(defn path->ns-symbol
  "Infer the namespace of a source file from its path, e.g.
  `src/acp/schemas_generated.clj` -> `acp.schemas-generated`."
  [path]
  (let [segments (->> (str/split (str path) #"/")
                      (remove #(or (empty? %)
                                    (= "." %)
                                    (= ".." %)
                                    (str/starts-with? % ".")))
                      vec)
        ;; Use the part of the path below the first `src` directory, if any.
        segments (if-let [i (first (keep-indexed (fn [i s] (when (= "src" s) i))
                                                 segments))]
                   (subvec segments (inc i))
                   segments)]
    (-> (str/join "/" segments)
        (str/replace #"\.clj[cs]?$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn generated-source
  "Return the full source text of a namespace that defines every schema and
  `->Type` constructor in schema.json.

  Options:

    :ns-symbol    namespace of the generated file (default `acp.schemas-generated`)
    :schema-path  path named in the header (default `\"acp/schema.json\"`)"
  ([] (generated-source {}))
  ([{:keys [ns-symbol schema-path]
     :or   {ns-symbol 'acp.schemas-generated
            schema-path "acp/schema.json"}}]
   (let [sb (StringBuilder.)]
     (emit-form sb (cons 'declare (map (comp symbol first) schema-defs)) 0)
     (str (ns-header ns-symbol schema-path)
          "(ns " ns-symbol "\n"
          "  \"Malli schemas for the Agent Client Protocol.\n\n"
          "  Generated from " schema-path " by `acp.schema-tools`.\"\n"
          "  (:require [acp.schema-runtime :as rt]))\n\n"
          ";; The protocol defines a type named `Error`, so drop java.lang's\n"
          ";; automatic class mapping before it becomes a var.\n"
          "(when (class? (get (ns-map *ns*) 'Error))\n"
          "  (ns-unmap *ns* 'Error))\n\n"
          ";; Schemas refer to one another, so declare every name up front.\n"
          sb "\n\n"
          (str/join "\n\n" generated-definitions)
          "\n"))))

(defn spit-generated!
  "Write the generated namespace for schema.json to `path`.

  Options:

    :ns-symbol    namespace of the generated file (default: inferred from `path`)
    :schema-path  path named in the header (default `\"acp/schema.json\"`)

  Returns `path`."
  [path & [opts]]
  (let [opts (or opts {})
        ns-symbol (or (:ns-symbol opts) (path->ns-symbol path))
        source (generated-source (assoc opts :ns-symbol ns-symbol))]
    (io/make-parents path)
    (spit path source)
    path))

(defn -main
  "Generate a standalone namespace holding every ACP schema and constructor.

  Usage: lein run -m acp.schema-tools [path] [namespace]

  Pass `-` as `path` to write the generated source to stdout instead."
  [& [path ns-name]]
  (let [path (or path "src/acp/schemas.clj")
        opts (cond-> {} ns-name (assoc :ns-symbol (symbol ns-name)))]
    (if (= path "-")
      (print (generated-source opts))
      (println "Wrote" (spit-generated! path opts)))))

(comment
  ;; Regenerate the checked-in schemas namespace:
  (spit-generated! "src/acp/schemas.clj")

  ;; Preview it without touching the filesystem:
  (print (generated-source)))
