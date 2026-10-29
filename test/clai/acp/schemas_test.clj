(ns clai.acp.schemas-test
  "Tests for the ACP schemas generated from `schema.json`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [malli.core :as m]
            [malli.error :as me]
            [clai.acp.schemas :as schemas]
            [clai.test-support :refer [quiet-logs]]))

(use-fixtures :once quiet-logs)

(def protocol-types
  "Every type name declared in the protocol's JSON schema."
  (->> (json/parse-string (slurp (io/resource "clai/acp/schema.json")) true)
       (:$defs)
       keys
       (map name)
       sort))

;; ---------------------------------------------------------------------------
;; Generated schemas and constructors
;; ---------------------------------------------------------------------------

(deftest every-protocol-type-has-a-schema-and-constructor
  (doseq [type-name protocol-types]
    (let [schema-var (ns-resolve 'clai.acp.schemas (symbol type-name))
          ctor-var   (ns-resolve 'clai.acp.schemas
                                 (symbol (str "->" type-name)))]
      (is (some? schema-var) (str type-name " schema is missing"))
      (is (bound? schema-var) (str type-name " schema is unbound"))
      (is (some? (m/schema @schema-var))
          (str type-name " is not a valid Malli schema"))
      (is (some? ctor-var) (str "->" type-name " constructor is missing"))
      (is (ifn? @ctor-var) (str "->" type-name " is not callable")))))

;; ---------------------------------------------------------------------------
;; StopReason
;; ---------------------------------------------------------------------------

(def valid-stop-reasons
  ["end_turn" "max_tokens" "max_turn_requests" "refusal" "cancelled"])

(deftest stop-reason-accepts-every-defined-value
  (doseq [reason valid-stop-reasons]
    (is (m/validate schemas/StopReason reason)
        (str "expected StopReason to accept " (pr-str reason)))))

(deftest stop-reason-rejects-unknown-strings
  (doseq [reason ["" "END_TURN" "end-turn" "endTurn" "end_turn " " end_turn"
                  "max_turns" "stop" "unknown"]]
    (is (not (m/validate schemas/StopReason reason))
        (str "expected StopReason to reject " (pr-str reason)))))

(deftest stop-reason-rejects-non-string-values
  (doseq [reason [nil :end_turn 'end_turn 0 1 1.0 true false [] {}
                  ["end_turn"]]]
    (is (not (m/validate schemas/StopReason reason))
        (str "expected StopReason to reject " (pr-str reason)))))

(deftest stop-reason-is-an-enum-of-the-expected-values
  (is (= :enum (m/type schemas/StopReason)))
  (is (= (set valid-stop-reasons)
         (set (m/children schemas/StopReason)))))

(deftest stop-reason-explain-reports-allowed-values
  (let [explained (m/explain schemas/StopReason "not-a-stop-reason")
        message   (first (me/humanize explained))]
    (is (some? explained))
    (doseq [reason valid-stop-reasons]
      (is (str/includes? message reason)
          (str "expected explanation to mention " (pr-str reason))))))

;; ---------------------------------------------------------------------------
;; TextContent and ContentBlock
;; ---------------------------------------------------------------------------

(defn- text-content
  ([] (text-content {}))
  ([overrides]
   (merge {:text "hello"} overrides)))

(deftest text-content-accepts-valid-values
  (is (m/validate schemas/TextContent (text-content)))
  (is (m/validate schemas/TextContent (text-content {:text ""})))
  (is (m/validate schemas/TextContent (text-content {:text "multi\nline"}))))

(deftest text-content-constructor-adds-content-block-discriminator
  (let [content (schemas/->TextContent "hello")]
    (is (m/validate schemas/TextContent content))
    (is (m/validate schemas/ContentBlock content))
    (is (= "hello" (:text content)))))

(deftest text-content-only-requires-text
  (is (not (m/validate schemas/TextContent (dissoc (text-content) :text))))
  (is (m/validate schemas/TextContent (dissoc (text-content) :annotations)))
  (is (m/validate schemas/TextContent (dissoc (text-content) :_meta))))

(deftest text-content-accepts-meta-and-annotations
  (is (m/validate schemas/TextContent
                  (text-content {:_meta        {:anything "goes"}
                                 :annotations {:audience ["user"]}})))
  (is (m/validate schemas/TextContent
                  (text-content {:annotations {:audience ["assistant" "user"]
                                               :priority    0.5}}))))

(deftest text-content-is-an-open-json-schema-object
  ;; schema.json never uses additionalProperties: false, so extension keys are
  ;; permitted just as they are by the wire protocol.
  (is (m/validate schemas/TextContent (assoc (text-content) :extra 1)))
  (is (m/validate schemas/TextContent (text-content {:type "text"}))))

(deftest text-content-rejects-non-string-text
  (doseq [v [nil :text 1 [] {} true]]
    (is (not (m/validate schemas/TextContent (text-content {:text v})))
        (str "expected TextContent to reject :text " (pr-str v)))))

(deftest text-content-rejects-non-map-values
  (doseq [v [nil "text" [] 1]]
    (is (not (m/validate schemas/TextContent v))
        (str "expected TextContent to reject " (pr-str v)))))

(deftest text-content-constructor
  (is (= "hi" (:text (schemas/->TextContent "hi"))))
  (is (m/validate schemas/TextContent (schemas/->TextContent "hi")))
  (is (thrown? clojure.lang.ExceptionInfo (schemas/->TextContent 42))))

(deftest content-block-requires-a-valid-discriminator
  (is (m/validate schemas/ContentBlock {:type "text" :text "hello"}))
  (is (m/validate schemas/ContentBlock
                  {:type "image" :data "aGVsbG8=" :mimeType "image/png"}))
  (is (not (m/validate schemas/ContentBlock {:text "hello"})))
  (is (not (m/validate schemas/ContentBlock {:type "TEXT" :text "hello"})))
  (is (not (m/validate schemas/ContentBlock {:type "text" :text 42}))))

(deftest content-constructor-wraps-and-coerces-content-blocks
  (doseq [value ["hello"
                 (schemas/->TextContent "hello")
                 {:type "text" :text "hello"}]]
    (let [content (schemas/->Content value)]
      (is (m/validate schemas/Content content))
      (is (= "hello" (get-in content [:content :text]))))))

;; ---------------------------------------------------------------------------
;; Message chunks
;; ---------------------------------------------------------------------------

(def chunk-cases
  [[schemas/UserMessageChunk  schemas/->UserMessageChunk  "user_message_chunk"  "UserMessageChunk"]
   [schemas/AgentMessageChunk schemas/->AgentMessageChunk "agent_message_chunk" "AgentMessageChunk"]
   [schemas/AgentThoughtChunk schemas/->AgentThoughtChunk "agent_thought_chunk" "AgentThoughtChunk"]])

(deftest chunk-constructors-produce-valid-updates
  (doseq [[schema ctor update label] chunk-cases]
    (let [chunk (ctor (schemas/->TextContent "hello"))]
      (is (= update (:sessionUpdate chunk)) (str label " update type"))
      (is (= "text" (get-in chunk [:content :type]))
          (str label " should coerce TextContent to a ContentBlock"))
      (is (m/validate schema chunk) (str label " should validate"))
      (is (m/validate schemas/SessionUpdate chunk)
          (str label " should be a valid SessionUpdate")))))

(deftest compaction-summary-chunk-coerces-content
  (doseq [content ["hello" (schemas/->TextContent "hello")]]
    (let [chunk (schemas/->CompactionSummaryChunk "compaction-1" content)]
      (is (m/validate schemas/CompactionSummaryChunk chunk))
      (is (= "hello" (get-in chunk [:content :text])))
      (is (m/validate schemas/SessionUpdate
                      (assoc chunk
                             :sessionUpdate "compaction_summary_chunk"))))))

(deftest chunk-constructors-accept-content-blocks-and-strings
  (doseq [[schema ctor _ label] chunk-cases]
    (is (m/validate schema (ctor {:type "text" :text "hello"})) label)
    (is (m/validate schema (ctor "hello")) label)
    (is (thrown? clojure.lang.ExceptionInfo (ctor {:text 42}))
        (str label " should reject invalid content"))))

(deftest message-chunks-allow-optional-meta-and-message-id
  (doseq [[schema ctor update _] chunk-cases]
    (let [chunk (assoc (ctor "hello") :messageId "m1")]
      (is (m/validate schema chunk) update)
      (is (m/validate schema (dissoc chunk :_meta)) update)
      (is (m/validate schema (assoc chunk :extra 1)) update))))

(deftest message-chunks-reject-wrong-session-update
  (doseq [[schema ctor _ label] chunk-cases
          wrong                ["user_message_chunk"
                                "agent_message_chunk"
                                "agent_thought_chunk"
                                "user_message"
                                "agent_message"
                                ""
                                nil
                                :user_message_chunk]]
    (let [correct (some (fn [[s _ u _]] (when (= s schema) u)) chunk-cases)]
      (when (not= wrong correct)
        (is (not (m/validate schema (assoc (ctor "hello")
                                           :sessionUpdate wrong)))
            (str "expected " label " to reject sessionUpdate "
                 (pr-str wrong)))))))

(deftest message-chunks-reject-non-map-values
  (doseq [[schema _ _ label] chunk-cases
          v                  [nil "chunk" [] 1]]
    (is (not (m/validate schema v))
        (str "expected " label " to reject " (pr-str v)))))

;; ---------------------------------------------------------------------------
;; Session types
;; ---------------------------------------------------------------------------

(deftest session-info-constructor-and-schema
  (let [info (schemas/->SessionInfo "s1" "/tmp" ["/workspace"] {:title "Demo"})]
    (is (= {:sessionId            "s1"
            :cwd                  "/tmp"
            :additionalDirectories ["/workspace"]
            :title                "Demo"
            :updatedAt            nil
            :_meta                nil}
           info))
    (is (m/validate schemas/SessionInfo info))
    (is (not (m/validate schemas/SessionInfo (dissoc info :sessionId))))
    (is (not (m/validate schemas/SessionInfo (dissoc info :cwd))))
    (is (m/validate schemas/SessionInfo (assoc info :custom true)))))

(deftest list-sessions-response-constructor
  (let [response (schemas/->ListSessionsResponse
                  {:next-cursor "next"
                   :sessions    [{:sessionId "s1" :cwd "/tmp"}]})]
    (is (= "next" (:nextCursor response)))
    (is (not (contains? response :next-cursor)))
    (is (m/validate schemas/ListSessionsResponse response))
    (is (not (m/validate schemas/ListSessionsResponse
                           (dissoc response :sessions))))))

(deftest list-sessions-request-constructor
  (let [request (schemas/->ListSessionsRequest)]
    (is (m/validate schemas/ListSessionsRequest request))
    (is (m/validate schemas/ListSessionsRequest
                    (schemas/->ListSessionsRequest {:cwd "/tmp" :cursor "c"}))))
  (is (not (m/validate schemas/ListSessionsRequest {:cwd 42}))))

;; ---------------------------------------------------------------------------
;; Permissions, prompts and configuration
;; ---------------------------------------------------------------------------

(deftest request-permission-request-schema
  (let [request
        (schemas/->RequestPermissionRequest
         "s1"
         {:toolCallId "call-1"}
         [{:optionId "allow" :name "Allow once" :kind "allow_once"}
          {:optionId "reject" :name "Reject" :kind "reject_once"}])]
    (is (m/validate schemas/RequestPermissionRequest request))
    (is (not (m/validate schemas/RequestPermissionRequest
                           (assoc-in request [:options 0 :kind] "bogus"))))
    (is (not (m/validate schemas/RequestPermissionRequest
                           (assoc request :toolCall {}))))))

(deftest request-permission-response-schema
  (is (m/validate schemas/RequestPermissionResponse
                  {:outcome {:outcome "cancelled"}}))
  (is (m/validate schemas/RequestPermissionResponse
                  {:outcome {:outcome "selected" :optionId "allow"}}))
  (is (not (m/validate schemas/RequestPermissionResponse
                           {:outcome {:outcome "selected"}}))))

(deftest prompt-request-schema
  (let [request (schemas/->PromptRequest
                 "s1"
                 [{:type "text" :text "hello"}
                  {:type "resource_link" :name "file" :uri "file:///tmp/x"}])]
    (is (m/validate schemas/PromptRequest request))
    (is (not (m/validate schemas/PromptRequest
                           (assoc-in request [:prompt 0 :type] "bogus"))))))

(deftest session-config-option-schema
  (is (m/validate schemas/SessionConfigOption
                  {:id           "model"
                   :name         "Model"
                   :type         "select"
                   :currentValue "fast"
                   :options      [{:value "fast" :name "Fast"}
                                  {:value "slow" :name "Slow"}]}))
  (is (m/validate schemas/SessionConfigOption
                  {:id           "beta"
                   :name         "Beta features"
                   :type         "boolean"
                   :currentValue true}))
  (is (not (m/validate schemas/SessionConfigOption
                           {:id "model" :name "Model" :type "unknown"}))))

(deftest set-session-config-option-request-schema
  (is (m/validate schemas/SetSessionConfigOptionRequest
                  {:sessionId "s1" :configId "beta" :type "boolean" :value true}))
  (is (m/validate schemas/SetSessionConfigOptionRequest
                  {:sessionId "s1" :configId "model" :type "select" :value "fast"}))
  (is (not (m/validate schemas/SetSessionConfigOptionRequest
                           {:sessionId "s1" :configId "beta" :type "boolean"}))))

;; ---------------------------------------------------------------------------
;; MCP servers and authentication
;; ---------------------------------------------------------------------------

(deftest mcp-server-schema
  (is (m/validate schemas/McpServer
                  {:name "stdio" :command "node" :args ["server.js"] :env []}))
  (is (m/validate schemas/McpServer
                  {:type "http" :name "http" :url "https://example.com" :headers []}))
  (is (m/validate schemas/McpServer
                  {:type "sse" :name "sse" :url "https://example.com" :headers []}))
  (is (not (m/validate schemas/McpServer {:name "missing-command"}))))

(deftest auth-method-schema
  (is (m/validate schemas/AuthMethod
                  {:type "terminal" :id "login" :name "Log in"}))
  (is (m/validate schemas/AuthMethod
                  {:id "agent" :name "Agent-managed"}))
  (is (not (m/validate schemas/AuthMethod {:id "missing-name"}))))

;; ---------------------------------------------------------------------------
;; Elicitation
;; ---------------------------------------------------------------------------

(deftest elicitation-form-request-schema
  (let [request {:message         "Please provide your name"
                 :mode            "form"
                 :sessionId       "s1"
                 :requestedSchema {:type       "object"
                                   :properties {"name" {:type "string"}
                                                "age"  {:type "integer"
                                                        :minimum 0
                                                        :maximum 120}}
                                   :required   ["name"]}}]
    (is (m/validate schemas/CreateElicitationRequest request))
    (is (not (m/validate schemas/CreateElicitationRequest
                             (assoc-in request [:requestedSchema :properties "age" :minimum]
                                       "not-a-number"))))))

(deftest elicitation-fallback-property-types-are-allowed
  ;; The protocol reserves unknown property schema types for extensions.
  (is (m/validate schemas/ElicitationPropertySchema
                  {:type "future-widget" :config {:anything true}})))

(deftest elicitation-url-request-schema
  (is (m/validate schemas/CreateElicitationRequest
                  {:message      "Open this URL"
                   :mode         "url"
                   :elicitationId "e1"
                   :url          "https://example.com/auth"
                   :sessionId    "s1"}))
  (is (not (m/validate schemas/CreateElicitationRequest
                           {:message "Open this URL" :mode "url"}))))

(deftest elicitation-response-schema
  (is (m/validate schemas/CreateElicitationResponse
                  {:action "accept" :content {"name" "Ada" "age" 36}}))
  (is (m/validate schemas/CreateElicitationResponse {:action "decline"}))
  (is (m/validate schemas/CreateElicitationResponse {:action "cancel"}))
  ;; Unknown actions are explicitly allowed as forward-compatible extensions.
  (is (m/validate schemas/CreateElicitationResponse {:action "bogus"}))
  (is (not (m/validate schemas/CreateElicitationResponse {:action 42}))))

(deftest elicitation-content-values
  (doseq [value ["text" 1 1.5 true ["a" "b"]]]
    (is (m/validate schemas/ElicitationContentValue value)
        (str "expected content value " (pr-str value))))
  (doseq [value [nil :keyword {} [1]]]
    (is (not (m/validate schemas/ElicitationContentValue value))
        (str "expected content value rejection " (pr-str value)))))

;; ---------------------------------------------------------------------------
;; JSON-RPC envelopes
;; ---------------------------------------------------------------------------

(deftest agent-request-and-response-schema
  (is (m/validate schemas/AgentRequest {:id 1 :method "x"}))
  (is (m/validate schemas/AgentResponse
                  {:id 1 :result {:protocolVersion 1}}))
  (is (m/validate schemas/AgentResponse
                  {:id 1 :error {:code -32600 :message "bad request"}}))
  (is (not (m/validate schemas/AgentResponse {:id 1})))
  (is (not (m/validate schemas/AgentResponse
                           {:id 1 :error {:code "bad" :message "bad"}}))))

(deftest client-request-schema
  (is (m/validate schemas/ClientRequest
                  {:id 1 :method "initialize" :params {:protocolVersion 1}}))
  ;; `ExtRequest` is the protocol's forward-compatibility escape hatch, so
  ;; unknown parameter shapes are intentionally accepted.
  (is (m/validate schemas/ClientRequest
                  {:id 1 :method "initialize" :params {:protocolVersion "one"}}))
  (is (not (m/validate schemas/ClientRequest {:method "initialize"}))))

;; ---------------------------------------------------------------------------
;; Numeric constraints
;; ---------------------------------------------------------------------------

(deftest protocol-version-bounds
  (is (m/validate schemas/ProtocolVersion 0))
  (is (m/validate schemas/ProtocolVersion 65535))
  (is (not (m/validate schemas/ProtocolVersion -1)))
  (is (not (m/validate schemas/ProtocolVersion 65536)))
  (is (not (m/validate schemas/ProtocolVersion 1.5))))

;; ---------------------------------------------------------------------------
;; Legacy compatibility
;; ---------------------------------------------------------------------------

(deftest legacy-message-and-state-updates-still-validate
  (is (m/validate schemas/UserMessage
                  (schemas/->UserMessage "m1" [(schemas/->MessageTextContent "hi")])))
  (is (m/validate schemas/AgentMessage
                  (schemas/->AgentMessage "m1" [(schemas/->MessageTextContent "hi")])))
  (is (m/validate schemas/AgentThought
                  (schemas/->AgentThought "m1" [(schemas/->MessageTextContent "hi")])))
  (is (m/validate schemas/StateUpdate (schemas/->StateUpdate "idle")))
  (is (m/validate schemas/SessionUpdate (schemas/->StateUpdate "idle")))
  (is (m/validate schemas/SessionUpdate
                  (schemas/->UserMessage "m1"
                                         [(schemas/->MessageTextContent "hi")]))))

;; ---------------------------------------------------------------------------
;; validate! integration
;; ---------------------------------------------------------------------------

(deftest validate-accepts-valid-values
  (is (= "end_turn" (schemas/validate! schemas/StopReason "end_turn" "StopReason")))
  (let [content (text-content)]
    (is (= content (schemas/validate! schemas/TextContent content "TextContent")))
    (doseq [[schema ctor _ label] chunk-cases]
      (let [chunk (ctor content)]
        (is (= chunk (schemas/validate! schema chunk label)))))))

(deftest validate-throws-on-invalid-values
  (doseq [[schema value label]
          [[schemas/StopReason "bogus" "StopReason"]
           [schemas/TextContent {:text 42} "TextContent"]
           [schemas/UserMessageChunk
            (schemas/->AgentMessageChunk "hello") "UserMessageChunk"]
           [schemas/AgentMessageChunk
            (schemas/->UserMessageChunk "hello") "AgentMessageChunk"]]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo (re-pattern (str "Invalid " label))
         (schemas/validate! schema value label))
        (str "expected validate! to reject invalid " label))))

(deftest validate-exception-carries-schema-value-and-errors
  (let [reason "bogus"
        e      (try
                 (schemas/validate! schemas/StopReason reason "StopReason")
                 (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo e))
    (is (= schemas/StopReason (:schema (ex-data e))))
    (is (= reason (:value (ex-data e))))
    (is (some? (:errors (ex-data e))))))
