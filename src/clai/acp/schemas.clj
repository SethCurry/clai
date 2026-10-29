(ns clai.acp.schemas
  "Malli schemas for the Agent Client Protocol.

  The canonical list of protocol types lives in `clai/acp/schema.json`.  This
  namespace loads that file at startup and converts every entry in `$defs`
  into a Malli schema.  It also generates a `->Type` constructor for every
  type, so the protocol can be used without duplicating the schema by hand.",
  (:require [cheshire.core :as json]
            [clai.log]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate!
  "Validate `value` against `schema`, returning `value` when valid or throwing
  an `ex-info` with a human-readable malli error explanation otherwise."
  [schema value what]
  (if-let [errors (and schema (m/explain schema value))]
    (let [explained (me/humanize errors)]
      (tel/log! {:level :error
                 :id    ::validation-failed
                 :data  {:what what :errors explained}}
                (str "Invalid " what))
      (throw (ex-info (str "Invalid " what ": " (pr-str explained))
                      {:schema schema :value value :errors errors})))
    value))

;; ---------------------------------------------------------------------------
;; JSON schema -> Malli
;; ---------------------------------------------------------------------------

(def ^:private schema-doc
  (let [resource (io/resource "clai/acp/schema.json")]
    (when-not resource
      (throw (ex-info "clai/acp/schema.json is missing from the classpath" {})))
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
  ;; JSON Schema "number" includes integers.  A predicate keeps that
  ;; behaviour while still allowing integral values.
  (let [min (:minimum node)
        max (:maximum node)
        predicates (cond-> []
                     (number? min) (conj #(>= % min))
                     (number? max) (conj #(<= % max)))]
    (if (seq predicates)
      (into [:and number?] (map (fn [pred] [:fn pred]) predicates))
      number?)))

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
;; Constructors
;; ---------------------------------------------------------------------------

(defn- plain-object-node?
  "True for definitions that declare object fields, even when they also have a
  top-level union.  Those get positional-argument constructors for their base
  fields; union-specific fields can be supplied via the map form."
  [node]
  (and (map? node)
       (not (contains? node :$ref))
       (or (= "object" (:type node))
           (seq (:properties node))
           (seq (:required node)))))

(defn- kebab-key
  [k]
  (keyword (str/replace (name k)
                        #"[A-Z]"
                        (fn [s] (str "-" (str/lower-case s))))))

(defn- property-infos
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
               :nullable? (nullable-node? property-node)}))
          order)))

(defn- lookup-key
  "Look up `k` in `m`, also accepting the kebab-case spelling of a
  camelCase protocol key (`:updatedAt` / `:updated-at`)."
  [m k]
  (let [kebab (kebab-key k)]
    (cond
      (contains? m k)     (get m k)
      (contains? m kebab) (get m kebab)
      :else               ::missing)))

(defn- normalize-known-keys
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

(defn- make-object-constructor
  [schema-var type-name infos union?]
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
                   (m/validate schema-var
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
                                               (not (m/validate schema-var
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
        (validate! schema-var (complete-map value) type-name)))))

(defn- make-value-constructor
  [schema-var type-name]
  (fn [& args]
    (let [value (cond
                  (empty? args) {}
                  (= 1 (count args)) (first args)
                  :else (throw (ex-info
                                (str type-name
                                     " constructor expects zero or one argument")
                                {:args args})))]
      (validate! schema-var value type-name))))

(doseq [[type-name node] schema-defs]
  (let [constructor (if (plain-object-node? node)
                      (make-object-constructor
                       (schema-var type-name)
                       type-name
                       (property-infos node)
                       (or (contains? node :oneOf)
                           (contains? node :anyOf)))
                      (make-value-constructor
                       (schema-var type-name)
                       type-name))]
    (intern *ns* (symbol (str "->" type-name)) constructor)))

(defn ->TextContent
  "Create a TextContent value.

  Passing a string produces the convenient ContentBlock-shaped value used by
  the message-chunk constructors (the :type discriminator is included).
  Passing a map uses the map form directly."
  ([value] (->TextContent value nil))
  ([value opts]
   (validate! TextContent
              (merge (if (map? value)
                       value
                       {:_meta nil
                        :annotations nil
                        :type "text"
                        :text value})
                     opts)
              "TextContent")))

;; ---------------------------------------------------------------------------
;; Handy aliases and legacy helpers
;; ---------------------------------------------------------------------------

(def Meta
  "The ACP `_meta` object: an open map or nil."
  [:maybe [:map {:closed false}]])

(def non-empty-string
  "A string of at least one character."
  [:re #".+"])

(def session-id
  "ACP session identifier (opaque, non-empty string)."
  [:and #'SessionId non-empty-string])

(def request-id
  "A JSON-RPC id: integer, string, or nil (for notifications)."
  #'RequestId)

;; The original code exposed these names before the schemas were generated
;; from schema.json.  Keep them as aliases so existing callers keep working.
(def implementation-info #'Implementation)
(def agent-capabilities #'AgentCapabilities)
(def auth-method #'AuthMethod)
(def acp-error-schema #'Error)
(def json-rpc-error-object #'Error)

(def json-rpc-request
  "A JSON-RPC 2.0 request object."
  [:map
   [:jsonrpc [:= "2.0"]]
   [:id #'request-id]
   [:method non-empty-string]
   [:params {:optional true} [:map {:closed false}]]])

(def json-rpc-notification
  "A JSON-RPC 2.0 notification (no id)."
  [:map
   [:jsonrpc [:= "2.0"]]
   [:method non-empty-string]
   [:params {:optional true} [:map {:closed false}]]])

(def json-rpc-response
  "A JSON-RPC 2.0 response object (success or error)."
  [:and
   [:map
    [:jsonrpc [:= "2.0"]]
    [:id #'request-id]]
   [:or
    [:map [:result :any]]
    [:map [:error #'json-rpc-error-object]]]])

;; Request-parameter aliases used by the agent connection.  These are kept
;; deliberately permissive because the connection also accepts a few legacy
;; fields, while the canonical `InitializeRequest`, `NewSessionRequest`, etc.
;; types generated above are exact.

(def initialize-params
  [:map {:closed false}
   [:protocolVersion pos-int?]
   [:info {:optional true} #'Implementation]
   [:capabilities {:optional true} [:map {:closed false}]]])

(def initialize-response #'InitializeResponse)

(def session-new-params
  [:map {:closed false}
   [:cwd {:optional true} :string]
   [:additionalDirectories {:optional true} [:sequential :string]]
   [:mcpServers {:optional true} [:sequential [:map {:closed false}]]]])

(def session-new-response #'NewSessionResponse)

(def session-id-params
  [:map {:closed false}
   [:sessionId #'session-id]])

(def session-resume-params
  [:map {:closed false}
   [:sessionId #'session-id]
   [:cwd {:optional true} :string]
   [:additionalDirectories {:optional true} [:sequential :string]]
   [:mcpServers {:optional true} [:sequential [:map {:closed false}]]]
   [:replayFrom {:optional true} :string]])

(def session-prompt-params
  [:map {:closed false}
   [:sessionId #'session-id]
   [:prompt [:or :string [:sequential [:map {:closed false}]]]]])

(def session-prompt-response #'PromptResponse)

(def session-cancel-params #'CancelNotification)
(def cancel-request-params #'CancelRequestNotification)

(def request-permission-opts
  [:map {:closed false}
   [:sessionId #'session-id]
   [:title :string]
   [:options [:sequential [:map {:closed false}]]]
   [:description {:optional true} :string]
   [:subject {:optional true} [:map {:closed false}]]])

(def create-elicitation-opts
  [:map {:closed false}])

(def complete-elicitation-params #'CompleteElicitationNotification)

;; ---------------------------------------------------------------------------
;; Legacy session updates
;; ---------------------------------------------------------------------------
;;
;; These predate the schema.json-backed updates.  They are kept because the
;; bundled agent still emits `user_message`, `agent_message`, and
;; `state_update` notifications.  The schema.json `SessionUpdate` union is
;; extended with them at the end of this section.

(def MessageTextContent
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:type [:= "text"]]
   [:content :string]])

(defn ->MessageTextContent
  "Create a legacy full-message text content block."
  [content]
  (validate! MessageTextContent
             {:_meta nil :type "text" :content content}
             "MessageTextContent"))

(def MessageContent [:or #'MessageTextContent])

(def UserMessage
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:sessionUpdate [:= "user_message"]]
   [:messageId non-empty-string]
   [:role [:= "user"]]
   [:content [:vector #'MessageContent]]])

(def AgentMessage
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:sessionUpdate [:= "agent_message"]]
   [:messageId non-empty-string]
   [:role [:= "agent"]]
   [:content [:vector #'MessageContent]]])

(def AgentThought
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:sessionUpdate [:= "agent_thought"]]
   [:messageId non-empty-string]
   [:role [:= "agent"]]
   [:content [:vector #'MessageContent]]])

(def SessionState [:enum "active" "idle"])

(def StateUpdate
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:sessionUpdate [:= "state_update"]]
   [:state #'SessionState]
   [:stopReason {:optional true} #'StopReason]])

(defn- ->content-block
  "Coerce a text/legacy content value into a schema.json ContentBlock."
  [content]
  (cond
    (and (map? content) (contains? content :type)) content
    (and (map? content) (contains? content :text)) (assoc content :type "text")
    (string? content) {:type "text" :text content}
    :else content))

(defn ->Content
  "Create a standard-content wrapper around a ContentBlock.

  Strings and TextContent maps are coerced to text content blocks.  A map that
  already has a `:content` key is treated as the full wrapper value."
  ([value] (->Content value nil))
  ([value opts]
   (let [base (if (and (map? value) (contains? value :content))
                value
                {:content (->content-block value)})]
     (validate! Content
                (merge base opts)
                "Content"))))

(defn ->CompactionSummaryChunk
  "Create a compaction-summary chunk.

  `content` may be a ContentBlock, a TextContent map, or a plain string."
  ([value]
   (validate! CompactionSummaryChunk value "CompactionSummaryChunk"))
  ([compaction-id content]
   (->CompactionSummaryChunk compaction-id content nil))
  ([compaction-id content opts]
   (validate! CompactionSummaryChunk
              (merge {:compactionId compaction-id
                      :content (->content-block content)
                      :_meta nil}
                     opts)
              "CompactionSummaryChunk")))

(def UserMessageChunk
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:sessionUpdate [:= "user_message_chunk"]]
   [:content #'ContentBlock]
   [:messageId {:optional true} [:maybe #'MessageId]]])

(defn ->UserMessageChunk
  "Create a `user_message_chunk` session update.

  `content` may be a ContentBlock, a TextContent map (the `:type` discriminator
  is added automatically), or a plain string.  The second argument may be a
  message id or an options map."
  ([content]
   (->UserMessageChunk content nil))
  ([content message-id-or-opts]
   (let [base {:_meta nil
               :sessionUpdate "user_message_chunk"
               :content (->content-block content)}]
     (validate! UserMessageChunk
                (cond
                  (map? message-id-or-opts)
                  (merge base message-id-or-opts)
                  (nil? message-id-or-opts)
                  base
                  :else
                  (assoc base :messageId message-id-or-opts))
                "UserMessageChunk"))))

(def AgentMessageChunk
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:sessionUpdate [:= "agent_message_chunk"]]
   [:content #'ContentBlock]
   [:messageId {:optional true} [:maybe #'MessageId]]])

(defn ->AgentMessageChunk
  "Create an `agent_message_chunk` session update.

  `content` may be a ContentBlock, a TextContent map (the `:type` discriminator
  is added automatically), or a plain string.  The second argument may be a
  message id or an options map."
  ([content]
   (->AgentMessageChunk content nil))
  ([content message-id-or-opts]
   (let [base {:_meta nil
               :sessionUpdate "agent_message_chunk"
               :content (->content-block content)}]
     (validate! AgentMessageChunk
                (cond
                  (map? message-id-or-opts)
                  (merge base message-id-or-opts)
                  (nil? message-id-or-opts)
                  base
                  :else
                  (assoc base :messageId message-id-or-opts))
                "AgentMessageChunk"))))

(def AgentThoughtChunk
  [:map {:closed false}
   [:_meta {:optional true} #'Meta]
   [:sessionUpdate [:= "agent_thought_chunk"]]
   [:content #'ContentBlock]
   [:messageId {:optional true} [:maybe #'MessageId]]])

(defn ->AgentThoughtChunk
  "Create an `agent_thought_chunk` session update.

  `content` may be a ContentBlock, a TextContent map, or a plain string.  The
  second argument may be a message id or an options map."
  ([content]
   (->AgentThoughtChunk content nil))
  ([content message-id-or-opts]
   (let [base {:_meta nil
               :sessionUpdate "agent_thought_chunk"
               :content (->content-block content)}]
     (validate! AgentThoughtChunk
                (cond
                  (map? message-id-or-opts)
                  (merge base message-id-or-opts)
                  (nil? message-id-or-opts)
                  base
                  :else
                  (assoc base :messageId message-id-or-opts))
                "AgentThoughtChunk"))))

(defn ->UserMessage
  [message-id content]
  (validate! UserMessage
             {:_meta nil
              :sessionUpdate "user_message"
              :messageId message-id
              :role "user"
              :content content}
             "UserMessage"))

(defn ->AgentMessage
  [message-id content]
  (validate! AgentMessage
             {:_meta nil
              :sessionUpdate "agent_message"
              :messageId message-id
              :role "agent"
              :content content}
             "AgentMessage"))

(defn ->AgentThought
  [message-id content]
  (validate! AgentThought
             {:_meta nil
              :sessionUpdate "agent_thought"
              :messageId message-id
              :role "agent"
              :content content}
             "AgentThought"))

(defn ->StateUpdate
  ([state] (->StateUpdate state nil))
  ([state stop-reason]
   (validate! StateUpdate
              (cond-> {:_meta nil
                       :sessionUpdate "state_update"
                       :state state}
                stop-reason (assoc :stopReason stop-reason))
              "StateUpdate")))

;; Include the legacy updates in the public SessionUpdate union.  This keeps
;; the connection's existing notifications valid while still accepting every
;; update type described by schema.json.
(let [json-session-update (convert (get schema-defs "SessionUpdate"))
      json-branches      (if (= :or (m/type json-session-update))
                           (rest (m/form json-session-update))
                           [json-session-update])]
  (alter-var-root
   (schema-var "SessionUpdate")
   (constantly
    (into [:or]
          (concat
           json-branches
           [#'UserMessageChunk
            #'AgentMessageChunk
            #'AgentThoughtChunk
            #'UserMessage
            #'AgentMessage
            #'AgentThought
            #'StateUpdate])))))
