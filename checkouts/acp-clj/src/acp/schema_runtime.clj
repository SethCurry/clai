(ns acp.schema-runtime
  "Runtime support for the ACP malli schemas.

  This namespace holds the validation helper and the `->Type` constructor
  builders.  It is deliberately free of any `schema.json` handling so a
  generated namespace can depend on it without paying for a JSON parse at
  startup.  `acp.schema-tools` uses the same helpers to generate such a
  namespace."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate!
  "Validate `value` against `schema`, returning `value` when valid or throwing
  an `ex-info` with a human-readable malli error explanation otherwise."
  [schema value what]
  (if-let [errors (and schema (m/explain schema value))]
    (let [explained (me/humanize errors)]
      (throw (ex-info (str "Invalid " what ": " (pr-str explained))
                      {:schema schema :value value :errors errors})))
    value))

;; ---------------------------------------------------------------------------
;; Property metadata
;; ---------------------------------------------------------------------------

(defn- null-node?
  [node]
  (and (map? node) (= "null" (:type node))))

(defn- nullable-node?
  "True when `node` explicitly permits JSON `null`."
  [node]
  (or (null-node? node)
      (let [t (:type node)]
        (and (vector? t) (some #{"null"} t)))
      (some null-node? (:anyOf node))
      (some null-node? (:oneOf node))))

(defn plain-object-node?
  "True for definitions that declare object fields, even when they also have a
  top-level union.  Those get positional-argument constructors for their base
  fields; union-specific fields can be supplied via the map form."
  [node]
  (and (map? node)
       (not (contains? node :$ref))
       (or (= "object" (:type node))
           (seq (:properties node))
           (seq (:required node)))))

(defn kebab-key
  [k]
  (keyword (str/replace (name k)
                        #"[A-Z]"
                        (fn [s] (str "-" (str/lower-case s))))))

(defn property-infos
  [node]
  (let [props (:properties node)
        required (mapv keyword (:required node))
        required-set (set required)
        ;; Required properties keep the order declared in the JSON schema;
        ;; optional properties follow in their declared order.
        order (concat required (remove required-set (keys props)))]
    (mapv (fn [k]
            (let [property-node (get props k)]
              {:key       (keyword k)
               :required? (contains? required-set k)
               :nullable? (boolean (nullable-node? property-node))}))
          order)))

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn lookup-key
  "Look up `k` in `m`, also accepting the kebab-case spelling of a
  camelCase protocol key (`:updatedAt` / `:updated-at`)."
  [m k]
  (let [kebab (kebab-key k)]
    (cond
      (contains? m k)     (get m k)
      (contains? m kebab) (get m kebab)
      :else               ::missing)))

(defn normalize-known-keys
  "Copy kebab-case spellings of known protocol keys to their camelCase form."
  [m infos]
  (reduce (fn [acc {:keys [key]}]
            (let [kebab (kebab-key key)
                  v     (get acc kebab ::missing)]
              (if (and (not= key kebab)
                       (not= v ::missing)
                       (not (contains? acc key)))
                (-> acc (assoc key v) (dissoc kebab))
                acc)))
          m infos))

(defn make-object-constructor
  [schema type-name infos union?]
  (let [required-count (count (filter :required? infos))
        optional-keys  (mapv :key (remove :required? infos))
        optional-key-set (into #{}
                               (mapcat (fn [k] [k (kebab-key k)]))
                               optional-keys)
        complete-map    (fn [m]
                          (reduce (fn [m {:keys [key required? nullable?]}]
                                    (if (and (or required? nullable?)
                                             (not (contains? m key)))
                                      (assoc m key nil)
                                      m))
                                  m
                                  infos))]
    (fn [& args]
      (let [value
            (cond
              ;; A single map is the explicit "build from a map" form when it
              ;; is a valid value of this type.  Otherwise, for types with one
              ;; required field (ContentChunk, EmbeddedResource, ...), the map
              ;; is that field's value.
              (and (= 1 (count args))
                   (map? (first args))
                   (m/validate schema
                               (normalize-known-keys (first args) infos)))
              (normalize-known-keys (first args) infos)

              (empty? args)
              (complete-map {})

              :else
              (let [last-arg        (last args)
                    extra-count     (- (count args) required-count)
                    ;; An empty map after the required arguments is usually a
                    ;; map-valued optional field, e.g. InitializeRequest's
                    ;; clientCapabilities.  If that positional interpretation
                    ;; is invalid, fall back to treating it as an empty opts
                    ;; map.
                    positional-empty
                    (when (and (pos? extra-count)
                               (map? last-arg)
                               (empty? last-arg)
                               (<= (count args) (count infos))
                               ;; An empty map in the `_meta` slot is more
                               ;; likely the empty opts map than metadata.
                               (not= :_meta (:key (nth infos (dec (count args))))))
                      (complete-map
                       (zipmap (map :key (take (count args) infos)) args)))
                    opts?           (and (pos? extra-count)
                                         (map? last-arg)
                                         (or union?
                                             (if (empty? last-arg)
                                               (not (m/validate schema
                                                                positional-empty))
                                               (some optional-key-set
                                                     (keys last-arg)))))
                    [vals opts]     (if opts?
                                      [(butlast args) last-arg]
                                      [args nil])
                    _               (when (> (count vals) (count infos))
                                      (throw (ex-info
                                              (str type-name
                                                   " constructor received too many arguments")
                                              {:args args})))
                    positional      (zipmap (map :key (take (count vals) infos))
                                            vals)
                    with-options    (if union?
                                      ;; Object types with a top-level union
                                      ;; keep their base fields positional;
                                      ;; any other keys (the union-specific
                                      ;; fields) come from the opts map.
                                      (merge positional
                                             (normalize-known-keys opts infos)
                                             opts)
                                      (reduce
                                       (fn [m k]
                                         (let [v (lookup-key opts k)]
                                           (if (= v ::missing)
                                             m
                                             (assoc m k v))))
                                       positional
                                       optional-keys))]
                with-options))]
        (validate! schema (complete-map value) type-name)))))

(defn make-value-constructor
  [schema type-name]
  (fn [& args]
    (let [value (cond
                  (empty? args) {}
                  (= 1 (count args)) (first args)
                  :else (throw (ex-info
                                (str type-name
                                     " constructor expects zero or one argument")
                                {:args args})))]
      (validate! schema value type-name))))
