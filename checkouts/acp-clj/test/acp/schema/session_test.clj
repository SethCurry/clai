(ns acp.schema.session-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.session :as session]
            [acp.schema.content :as content]
            [acp.schema.tool :as tool]))


;; -------------------------------------------------------------------------
;; Helpers
;; -------------------------------------------------------------------------

(defn- returned
  "Return the map produced by calling `f`, or the `ExceptionInfo` it threw."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      e)))


(defn- accepts
  "Assert that `schema` accepts every value in `values`."
  [schema values]
  (doseq [value values]
    (test/is (m/validate schema value)
             (str "expected " (pr-str value) " to be accepted"))))


(defn- rejects
  "Assert that `schema` rejects every value in `values`."
  [schema values]
  (doseq [value values]
    (test/is (not (m/validate schema value))
             (str "expected " (pr-str value) " to be rejected"))))


(defn- builds
  "Assert that `ctor` called with `args` returns `expected` and that the
  result satisfies `schema`."
  [schema ctor args expected]
  (let [value (apply ctor args)]
    (test/is (= expected value)
             (str "unexpected value: " (pr-str value)))
    (test/is (m/validate schema value)
             (str "expected " (pr-str value) " to satisfy the schema"))))


(defn- ctor-rejects
  "Assert that `ctor` rejects every argument list, either by arity or by
  throwing an `ExceptionInfo` for an invalid value."
  [ctor arglists]
  (doseq [args arglists]
    (test/is (thrown? Exception (apply ctor args))
             (str "expected " (pr-str args) " to be rejected"))))


(defn- ex-data-of
  "Ex-data of the `ExceptionInfo` thrown by `f`."
  [f]
  (let [e (returned f)]
    (test/is (instance? clojure.lang.ExceptionInfo e)
             "expected an ExceptionInfo")
    (ex-data e)))


;; -------------------------------------------------------------------------
;; Fixtures
;; -------------------------------------------------------------------------

(def ^:private text-block {:type "text" :text "hello"})

(def ^:private message-chunk {:sessionUpdate "agent_message_chunk"
                              :content text-block})

(def ^:private mcp-server {:type "stdio"
                           :name "test"
                           :command "npx"
                           :args ["server"]
                           :env []})

(def ^:private tool-call-update {:toolCallId "call-1"
                                 :status "pending"
                                 :title "Read a file"})

(def ^:private permission-option {:optionId "opt-allow"
                                  :name "Allow once"
                                  :kind "allow_once"
                                  :_meta nil})

(def ^:private session-mode {:id "code" :name "Code" :description nil :_meta nil})

(def ^:private config-select-option {:value "fast"
                                     :name "Fast"
                                     :description nil
                                     :_meta nil})

(def ^:private select-config-option {:id "model"
                                     :name "Model"
                                     :type "select"
                                     :currentValue "fast"
                                     :description nil
                                     :category nil
                                     :_meta nil
                                     :options [config-select-option]})


;; -------------------------------------------------------------------------
;; SessionId and the other string identifiers
;; -------------------------------------------------------------------------

(defn- string-id-types
  "The `:string` identifier schemas and constructors in this namespace."
  []
  [[session/SessionId session/->SessionId "session-1"]
   [session/MessageId session/->MessageId "message-1"]
   [session/SessionModeId session/->SessionModeId "code"]
   [session/SessionConfigId session/->SessionConfigId "model"]
   [session/SessionConfigValueId session/->SessionConfigValueId "fast"]
   [session/SessionConfigGroupId session/->SessionConfigGroupId "group-1"]
   [session/CompactionId session/->CompactionId "compaction-1"]
   [session/PermissionOptionId session/->PermissionOptionId "opt-allow"]])


(test/deftest string-identifier-schemas
  (doseq [[schema _ valid] (string-id-types)]
    (test/testing (str "accepts strings for " valid)
      (accepts schema [valid "" "with/slashes" "with spaces"]))
    (test/testing "rejects non-string values"
      (rejects schema [nil 1 1.5 :id 'id true [:id] {:id "id"}]))))


(test/deftest string-identifier-constructors
  (doseq [[schema ctor valid] (string-id-types)]
    (test/testing (str "round-trips " valid)
      (test/is (= valid (ctor valid)))
      (test/is (m/validate schema (ctor valid))))

    (test/testing "rejects invalid identifiers"
      (ctor-rejects ctor [[nil] [1] [:id] [[:id]] [{}] []]))

    (test/testing "requires exactly one argument"
      (test/is (thrown? clojure.lang.ArityException (ctor valid valid)))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [data (ex-data-of #(session/->SessionId 42))]
      (test/is (= 42 (:value data)))
      (test/is (false? (m/validate (:schema data) (:value data))))
      (test/is (some? (:errors data))))))


;; -------------------------------------------------------------------------
;; Enumerated / closed value types
;; -------------------------------------------------------------------------

(test/deftest StopReason
  (test/testing "accepts every stop reason"
    (accepts session/StopReason
             ["end_turn" "max_tokens" "max_turn_requests" "refusal" "cancelled"]))

  (test/testing "rejects unknown stop reasons"
    (rejects session/StopReason ["end" "EndTurn" "cancelled " "" nil 1 :end_turn]))

  (test/testing "constructs every stop reason"
    (doseq [reason ["end_turn" "max_tokens" "max_turn_requests" "refusal"
                    "cancelled"]]
      (test/is (= reason (session/->StopReason reason)))))

  (test/testing "rejects invalid stop reasons"
    (ctor-rejects session/->StopReason [["end"] [nil] [1] [] ["end_turn" "refusal"]])))


(test/deftest PlanEntryPriority-and-PlanEntryStatus
  (test/testing "accepts the enumerated values"
    (accepts session/PlanEntryPriority ["high" "medium" "low"])
    (accepts session/PlanEntryStatus ["pending" "in_progress" "completed"]))

  (test/testing "rejects values outside the enumeration"
    (rejects session/PlanEntryPriority ["urgent" "High" "" nil 1])
    (rejects session/PlanEntryStatus ["done" "Pending" "" nil 1]))

  (test/testing "constructors round-trip valid values"
    (doseq [priority ["high" "medium" "low"]]
      (test/is (= priority (session/->PlanEntryPriority priority))))
    (doseq [status ["pending" "in_progress" "completed"]]
      (test/is (= status (session/->PlanEntryStatus status)))))

  (test/testing "constructors reject invalid values"
    (ctor-rejects session/->PlanEntryPriority [["urgent"] [nil]])
    (ctor-rejects session/->PlanEntryStatus [["done"] [nil]])))


(test/deftest PermissionOptionKind
  (test/testing "accepts every permission option kind"
    (accepts session/PermissionOptionKind
             ["allow_once" "allow_always" "reject_once" "reject_always"]))

  (test/testing "rejects unknown kinds"
    (rejects session/PermissionOptionKind ["allow" "ALLOW_ONCE" "" nil 1]))

  (test/testing "constructor round-trips and rejects"
    (test/is (= "allow_always" (session/->PermissionOptionKind "allow_always")))
    (ctor-rejects session/->PermissionOptionKind [["allow"] [nil]])))


(test/deftest SessionConfigOptionCategory
  (test/testing "accepts the documented categories and extension names"
    (accepts session/SessionConfigOptionCategory
             ["mode" "model" "model_config" "thought_level" "_custom" "other"]))

  (test/testing "rejects non-string values"
    (rejects session/SessionConfigOptionCategory [nil 1 :mode [:mode] {}]))

  (test/testing "constructor round-trips and rejects"
    (test/is (= "model" (session/->SessionConfigOptionCategory "model")))
    (test/is (= "_x" (session/->SessionConfigOptionCategory "_x")))
    (ctor-rejects session/->SessionConfigOptionCategory [[nil] [1] [:mode]])))


(test/deftest NoticeSeverity
  (test/testing "accepts the documented severities and extension names"
    (accepts session/NoticeSeverity ["info" "warning" "error" "_custom" "debug"]))

  (test/testing "rejects non-string values"
    (rejects session/NoticeSeverity [nil 1 :info [:info] {}]))

  (test/testing "constructor round-trips and rejects"
    (test/is (= "warning" (session/->NoticeSeverity "warning")))
    (ctor-rejects session/->NoticeSeverity [[nil] [1] [:info]])))


(test/deftest CompactionStatus
  (test/testing "accepts the documented statuses and extension names"
    (accepts session/CompactionStatus
             ["in_progress" "completed" "failed" "cancelled" "_custom"]))

  (test/testing "rejects non-string values"
    (rejects session/CompactionStatus [nil 1 :completed [:completed] {}]))

  (test/testing "constructor round-trips and rejects"
    (test/is (= "completed" (session/->CompactionStatus "completed")))
    (ctor-rejects session/->CompactionStatus [[nil] [1] [:completed]])))


;; -------------------------------------------------------------------------
;; Session capabilities
;; -------------------------------------------------------------------------

(test/deftest SessionCapabilities
  (test/testing "accepts an empty capability set"
    (accepts session/SessionCapabilities [{} {:list nil} {:list {}}]))

  (test/testing "accepts every advertised capability"
    (accepts session/SessionCapabilities
             [{:list {}
               :delete {}
               :additionalDirectories {}
               :resume {}
               :close {}}
              {:list {:_meta {:traceId "t-1"}}}]))

  (test/testing "rejects capabilities that are not maps"
    (rejects session/SessionCapabilities
             [{:list "yes"}
              {:delete true}
              {:resume []}
              {:close 1}
              nil
              "capabilities"]))

  (test/testing "constructor fills every slot with nil"
    (builds session/SessionCapabilities session/->SessionCapabilities
            []
            {:list nil
             :delete nil
             :additionalDirectories nil
             :resume nil
             :close nil
             :_meta nil}))

  (test/testing "constructor accepts capability keyword arguments"
    (builds session/SessionCapabilities session/->SessionCapabilities
            [:list (session/->SessionListCapabilities)
             :close (session/->SessionCloseCapabilities)
             :meta {:traceId "t-1"}]
            {:list {:_meta nil}
             :delete nil
             :additionalDirectories nil
             :resume nil
             :close {:_meta nil}
             :_meta {:traceId "t-1"}}))

  (test/testing "constructor rejects malformed capabilities"
    (ctor-rejects session/->SessionCapabilities [[:list "yes"] [:resume 1]])))


(test/deftest empty-session-capabilities
  (doseq [[schema ctor]
          [[session/SessionListCapabilities session/->SessionListCapabilities]
           [session/SessionDeleteCapabilities session/->SessionDeleteCapabilities]
           [session/SessionAdditionalDirectoriesCapabilities
            session/->SessionAdditionalDirectoriesCapabilities]
           [session/SessionResumeCapabilities session/->SessionResumeCapabilities]
           [session/SessionCloseCapabilities session/->SessionCloseCapabilities]]]
    (test/testing "accepts an empty map and metadata"
      (accepts schema [{} {:_meta {:traceId "t-1"}}])
      (rejects schema [{:_meta "meta"} nil "capabilities"]))

    (test/testing "constructor fills the metadata slot with nil"
      (builds schema ctor [] {:_meta nil})
      (builds schema ctor [:meta {:traceId "t-1"}] {:_meta {:traceId "t-1"}}))

    (test/testing "constructor rejects non-map metadata"
      (ctor-rejects ctor [[:meta "meta"]])))

  (test/testing "capabilities without fields take no arguments"
    (doseq [[schema ctor]
            [[session/CompactionCapabilities session/->CompactionCapabilities]
             [session/NoticeCapabilities session/->NoticeCapabilities]]]
      (test/is (= {} (ctor)))
      (test/is (m/validate schema (ctor)))
      (test/is (thrown? clojure.lang.ArityException (ctor :meta {}))))))


(test/deftest ClientSessionCapabilities
  (test/testing "accepts an empty capability set"
    (accepts session/ClientSessionCapabilities
             [{} {:compaction {}} {:configOptions {:boolean {}}} {:notices {}}]))

  (test/testing "rejects malformed capabilities"
    (rejects session/ClientSessionCapabilities
             [{:compaction "yes"}
              {:configOptions {:boolean "yes"}}
              {:notices []}
              nil]))

  (test/testing "constructor fills every slot with nil"
    (builds session/ClientSessionCapabilities session/->ClientSessionCapabilities
            []
            {:compaction nil :configOptions nil :notices nil :_meta nil}))

  (test/testing "constructor accepts nested capabilities"
    (builds session/ClientSessionCapabilities session/->ClientSessionCapabilities
            [:compaction (session/->CompactionCapabilities)
             :configOptions (session/->SessionConfigOptionsCapabilities
                             :boolean (session/->BooleanConfigOptionCapabilities))
             :notices (session/->NoticeCapabilities)
             :meta {:traceId "t-1"}]
            {:compaction {}
             :configOptions {:boolean {:_meta nil} :_meta nil}
             :notices {}
             :_meta {:traceId "t-1"}})))


;; -------------------------------------------------------------------------
;; Session lifecycle requests and responses
;; -------------------------------------------------------------------------

(test/deftest NewSessionRequest
  (test/testing "accepts cwd and mcp servers"
    (accepts session/NewSessionRequest
             [{:cwd "/tmp" :mcpServers []}
              {:cwd "/tmp"
               :mcpServers [mcp-server]
               :additionalDirectories ["/tmp/extra"]
               :_meta {:traceId "t-1"}}
              {:cwd "/tmp" :mcpServers [] :_meta nil}]))

  (test/testing "requires cwd and mcpServers"
    (rejects session/NewSessionRequest
             [{:mcpServers []}
              {:cwd "/tmp"}
              {:cwd 1 :mcpServers []}
              {:cwd "/tmp" :mcpServers {}}
              {:cwd "/tmp" :mcpServers [] :additionalDirectories "/extra"}])))

  (test/testing "constructor omits the non-nullable optional field"
    (builds session/NewSessionRequest session/->NewSessionRequest
            ["/tmp" [mcp-server]]
            {:cwd "/tmp" :mcpServers [mcp-server] :_meta nil}))

  (test/testing "constructor accepts additionalDirectories and meta"
    (builds session/NewSessionRequest session/->NewSessionRequest
            ["/tmp" [] :additionalDirectories ["/extra"] :meta {:traceId "t-1"}]
            {:cwd "/tmp"
             :mcpServers []
             :additionalDirectories ["/extra"]
             :_meta {:traceId "t-1"}}))

  (test/testing "constructor rejects malformed requests"
    (ctor-rejects session/->NewSessionRequest
                  [[nil []]
                   ["/tmp" nil]
                   ["/tmp" {}]
                   ["/tmp" [] :additionalDirectories "/extra"]]))


(test/deftest NewSessionResponse-and-other-session-state-responses
  (test/testing "NewSessionResponse requires a session id"
    (accepts session/NewSessionResponse [{:sessionId "session-1"}])
    (rejects session/NewSessionResponse [{} {:sessionId nil} {:sessionId 1}]))

  (test/testing "NewSessionResponse accepts modes and config options"
    (let [value {:sessionId "session-1"
                 :modes {:currentModeId "code" :availableModes [session-mode]}
                 :configOptions [select-config-option]}]
      (accepts session/NewSessionResponse [value])
      (builds session/NewSessionResponse session/->NewSessionResponse
              ["session-1"
               :modes (:modes value)
               :configOptions (:configOptions value)
               :meta {:traceId "t-1"}]
              (assoc value :_meta {:traceId "t-1"}))))

  (test/testing "LoadSessionResponse and ResumeSessionResponse accept the same shape"
    (doseq [[schema ctor]
            [[session/LoadSessionResponse session/->LoadSessionResponse]
             [session/ResumeSessionResponse session/->ResumeSessionResponse]]]
      (accepts schema [{} {:modes nil :configOptions nil}])
      (builds schema ctor [] {:modes nil :configOptions nil :_meta nil})
      (rejects schema [{:modes {}}
                       {:configOptions {}}
                       {:modes {:currentModeId 1 :availableModes []}}])))

  (test/testing "SetSessionModeResponse and CloseSessionResponse carry only metadata"
    (doseq [[schema ctor]
            [[session/SetSessionModeResponse session/->SetSessionModeResponse]
             [session/CloseSessionResponse session/->CloseSessionResponse]]]
      (accepts schema [{}])
      (builds schema ctor [] {:_meta nil})
      (builds schema ctor [:meta {:traceId "t-1"}] {:_meta {:traceId "t-1"}})))

  (test/testing "DeleteSessionResponse carries only metadata"
    (accepts session/DeleteSessionResponse [{}])
    (builds session/DeleteSessionResponse session/->DeleteSessionResponse
            [] {:_meta nil})))


(test/deftest LoadSessionRequest
  (test/testing "accepts mcp servers, cwd and session id"
    (accepts session/LoadSessionRequest
             [{:mcpServers [] :cwd "/tmp" :sessionId "session-1"}
              {:mcpServers [mcp-server]
               :cwd "/tmp"
               :sessionId "session-1"
               :additionalDirectories ["/extra"]}]))

  (test/testing "requires every mandatory field"
    (rejects session/LoadSessionRequest
             [{:cwd "/tmp" :sessionId "session-1"}
              {:mcpServers [] :sessionId "session-1"}
              {:mcpServers [] :cwd "/tmp"}
              {:mcpServers [] :cwd "/tmp" :sessionId nil}]))

  (test/testing "constructor keeps the schema argument order"
    (builds session/LoadSessionRequest session/->LoadSessionRequest
            [[] "/tmp" "session-1"]
            {:mcpServers [] :cwd "/tmp" :sessionId "session-1" :_meta nil}))

  (test/testing "constructor accepts additionalDirectories when supplied"
    (builds session/LoadSessionRequest session/->LoadSessionRequest
            [[] "/tmp" "session-1" :additionalDirectories ["/extra"]]
            {:mcpServers []
             :cwd "/tmp"
             :sessionId "session-1"
             :additionalDirectories ["/extra"]
             :_meta nil}))

  (test/testing "constructor rejects malformed requests"
    (ctor-rejects session/->LoadSessionRequest
                  [[nil "/tmp" "session-1"]
                   [[] nil "session-1"]
                   [[] "/tmp" nil]
                   [[] "/tmp" "session-1" :additionalDirectories "/extra"]])))


(test/deftest ResumeSessionRequest
  (test/testing "accepts session id and cwd"
    (accepts session/ResumeSessionRequest
             [{:sessionId "session-1" :cwd "/tmp"}
              {:sessionId "session-1"
               :cwd "/tmp"
               :additionalDirectories ["/extra"]
               :mcpServers [mcp-server]}]))

  (test/testing "requires session id and cwd"
    (rejects session/ResumeSessionRequest
             [{:cwd "/tmp"}
              {:sessionId "session-1"}
              {:sessionId 1 :cwd "/tmp"}
              {:sessionId "session-1" :cwd "/tmp" :mcpServers {}}]))

  (test/testing "constructor omits the optional mcp servers"
    (builds session/ResumeSessionRequest session/->ResumeSessionRequest
            ["session-1" "/tmp"]
            {:sessionId "session-1" :cwd "/tmp" :_meta nil}))

  (test/testing "constructor accepts optional directories and servers"
    (builds session/ResumeSessionRequest session/->ResumeSessionRequest
            ["session-1"
             "/tmp"
             :additionalDirectories ["/extra"]
             :mcpServers [mcp-server]
             :meta {:traceId "t-1"}]
            {:sessionId "session-1"
             :cwd "/tmp"
             :additionalDirectories ["/extra"]
             :mcpServers [mcp-server]
             :_meta {:traceId "t-1"}})))


(test/deftest ListSessionsRequest-and-Response
  (test/testing "requests accept every optional field"
    (accepts session/ListSessionsRequest [{} {:cwd "/tmp"} {:cursor "abc"}
                                          {:cwd "/tmp" :cursor "abc"}])
    (rejects session/ListSessionsRequest [{:cwd 1} {:cursor 1}]))

  (test/testing "request constructor fills optional slots with nil"
    (builds session/ListSessionsRequest session/->ListSessionsRequest
            []
            {:cwd nil :cursor nil :_meta nil})
    (builds session/ListSessionsRequest session/->ListSessionsRequest
            [:cwd "/tmp" :cursor "abc" :meta {:traceId "t-1"}]
            {:cwd "/tmp" :cursor "abc" :_meta {:traceId "t-1"}}))

  (test/testing "responses require the session list"
    (accepts session/ListSessionsResponse
             [{:sessions []}
              {:sessions [{:sessionId "session-1" :cwd "/tmp"}]
               :nextCursor "next"}])
    (rejects session/ListSessionsResponse
             [{} {:sessions {}} {:sessions [{:sessionId "session-1"}]}]))

  (test/testing "response constructor fills optional slots with nil"
    (builds session/ListSessionsResponse session/->ListSessionsResponse
            [[]]
            {:sessions [] :nextCursor nil :_meta nil})
    (builds session/ListSessionsResponse session/->ListSessionsResponse
            [[{:sessionId "session-1" :cwd "/tmp"}]
             :nextCursor "next"
             :meta {:traceId "t-1"}]
            {:sessions [{:sessionId "session-1" :cwd "/tmp"}]
             :nextCursor "next"
             :_meta {:traceId "t-1"}})))


(test/deftest SessionInfo
  (test/testing "accepts a session id and cwd"
    (accepts session/SessionInfo
             [{:sessionId "session-1" :cwd "/tmp"}
              {:sessionId "session-1"
               :cwd "/tmp"
               :additionalDirectories ["/extra"]
               :title "Notes"
               :updatedAt "2026-01-01T00:00:00Z"}]))

  (test/testing "rejects malformed values"
    (rejects session/SessionInfo
             [{} {:sessionId "session-1"} {:cwd "/tmp"}
              {:sessionId "session-1" :cwd "/tmp" :additionalDirectories "/extra"}
              {:sessionId "session-1" :cwd "/tmp" :title 1}]))

  (test/testing "constructor fills nullable slots with nil"
    (builds session/SessionInfo session/->SessionInfo
            ["session-1" "/tmp"]
            {:sessionId "session-1"
             :cwd "/tmp"
             :title nil
             :updatedAt nil
             :_meta nil}))

  (test/testing "constructor accepts every optional field"
    (builds session/SessionInfo session/->SessionInfo
            ["session-1"
             "/tmp"
             :additionalDirectories ["/extra"]
             :title "Notes"
             :updatedAt "2026-01-01T00:00:00Z"
             :meta {:traceId "t-1"}]
            {:sessionId "session-1"
             :cwd "/tmp"
             :additionalDirectories ["/extra"]
             :title "Notes"
             :updatedAt "2026-01-01T00:00:00Z"
             :_meta {:traceId "t-1"}}))

  (test/testing "constructor rejects malformed values"
    (ctor-rejects session/->SessionInfo
                  [[nil "/tmp"]
                   ["session-1" nil]
                   ["session-1" "/tmp" :additionalDirectories "/extra"]])))


(test/deftest SessionInfoUpdate
  (test/testing "accepts partial updates"
    (accepts session/SessionInfoUpdate
             [{} {:title "Notes"} {:title nil} {:updatedAt "2026-01-01T00:00:00Z"}]))

  (test/testing "rejects malformed values"
    (rejects session/SessionInfoUpdate [{:title 1} {:updatedAt 1} "update"]))

  (test/testing "constructor fills every slot with nil"
    (builds session/SessionInfoUpdate session/->SessionInfoUpdate
            []
            {:title nil :updatedAt nil :_meta nil})
    (builds session/SessionInfoUpdate session/->SessionInfoUpdate
            [:title "Notes" :meta {:traceId "t-1"}]
            {:title "Notes" :updatedAt nil :_meta {:traceId "t-1"}})))


(test/deftest DeleteSessionRequest-and-CloseSessionRequest
  (doseq [[schema ctor]
          [[session/DeleteSessionRequest session/->DeleteSessionRequest]
           [session/CloseSessionRequest session/->CloseSessionRequest]]]
    (test/testing "accepts a session id"
      (accepts schema [{:sessionId "session-1"}
                       {:sessionId "session-1" :_meta {:traceId "t-1"}}])
      (rejects schema [{} {:sessionId nil} {:sessionId 1}]))

    (test/testing "constructor fills the metadata slot with nil"
      (builds schema ctor ["session-1"] {:sessionId "session-1" :_meta nil})
      (builds schema ctor ["session-1" :meta {:traceId "t-1"}]
              {:sessionId "session-1" :_meta {:traceId "t-1"}}))

    (test/testing "constructor rejects invalid session ids"
      (ctor-rejects ctor [[nil] [1] []]))))


;; -------------------------------------------------------------------------
;; Session modes
;; -------------------------------------------------------------------------

(test/deftest SessionMode
  (test/testing "accepts ids, names and descriptions"
    (accepts session/SessionMode
             [{:id "code" :name "Code"}
              {:id "code" :name "Code" :description "Writes code"}
              {:id "code" :name "Code" :description nil}]))

  (test/testing "rejects malformed modes"
    (rejects session/SessionMode
             [{} {:id "code"} {:name "Code"} {:id 1 :name "Code"}
              {:id "code" :name 1} {:id "code" :name "Code" :description 1}]))

  (test/testing "constructor fills the description with nil"
    (builds session/SessionMode session/->SessionMode
            ["code" "Code"]
            {:id "code" :name "Code" :description nil :_meta nil})
    (builds session/SessionMode session/->SessionMode
            ["code" "Code" :description "Writes code" :meta {:traceId "t-1"}]
            {:id "code"
             :name "Code"
             :description "Writes code"
             :_meta {:traceId "t-1"}})))


(test/deftest SessionModeState
  (test/testing "accepts a current mode and the available modes"
    (accepts session/SessionModeState
             [{:currentModeId "code" :availableModes [session-mode]}
              {:currentModeId "code" :availableModes []}]))

  (test/testing "requires a current mode and a vector of modes"
    (rejects session/SessionModeState
             [{} {:currentModeId "code"} {:availableModes [session-mode]}
              {:currentModeId "code" :availableModes session-mode}
              {:currentModeId "code" :availableModes [{:id "code"}]}]))

  (test/testing "constructor keeps its argument order"
    (builds session/SessionModeState session/->SessionModeState
            ["code" [session-mode]]
            {:currentModeId "code" :availableModes [session-mode] :_meta nil})
    (builds session/SessionModeState session/->SessionModeState
            ["code" [] :meta {:traceId "t-1"}]
            {:currentModeId "code" :availableModes [] :_meta {:traceId "t-1"}}))

  (test/testing "constructor rejects malformed states"
    (ctor-rejects session/->SessionModeState
                  [[nil []]
                   ["code" nil]
                   ["code" session-mode]])))


(test/deftest SetSessionModeRequest-and-CurrentModeUpdate
  (test/testing "SetSessionModeRequest requires session and mode ids"
    (accepts session/SetSessionModeRequest
             [{:sessionId "session-1" :modeId "code"}])
    (rejects session/SetSessionModeRequest
             [{} {:sessionId "session-1"} {:modeId "code"}
              {:sessionId "session-1" :modeId 1}]))

  (test/testing "SetSessionModeRequest constructor keeps its argument order"
    (builds session/SetSessionModeRequest session/->SetSessionModeRequest
            ["session-1" "code"]
            {:sessionId "session-1" :modeId "code" :_meta nil}))

  (test/testing "CurrentModeUpdate requires a mode id"
    (accepts session/CurrentModeUpdate [{:currentModeId "code"}])
    (rejects session/CurrentModeUpdate [{} {:currentModeId nil}]))

  (test/testing "CurrentModeUpdate constructor fills metadata with nil"
    (builds session/CurrentModeUpdate session/->CurrentModeUpdate
            ["code"]
            {:currentModeId "code" :_meta nil})
    (builds session/CurrentModeUpdate session/->CurrentModeUpdate
            ["code" :meta {:traceId "t-1"}]
            {:currentModeId "code" :_meta {:traceId "t-1"}})))


;; -------------------------------------------------------------------------
;; Session configuration options
;; -------------------------------------------------------------------------

(test/deftest SessionConfigSelectOption-and-Group
  (test/testing "select options require a value and a name"
    (accepts session/SessionConfigSelectOption
             [{:value "fast" :name "Fast"}
              {:value "fast" :name "Fast" :description "Low latency"}])
    (rejects session/SessionConfigSelectOption
             [{} {:value "fast"} {:name "Fast"} {:value 1 :name "Fast"}]))

  (test/testing "select option constructor fills the description with nil"
    (builds session/SessionConfigSelectOption
            session/->SessionConfigSelectOption
            ["fast" "Fast"]
            {:value "fast" :name "Fast" :description nil :_meta nil}))

  (test/testing "select groups require a group id, name and options"
    (accepts session/SessionConfigSelectGroup
             [{:group "group-1" :name "Group" :options []}
              {:group "group-1"
               :name "Group"
               :options [config-select-option]}])
    (rejects session/SessionConfigSelectGroup
             [{} {:group "group-1" :name "Group"}
              {:group "group-1" :name "Group" :options {}}]))

  (test/testing "select group constructor keeps its argument order"
    (builds session/SessionConfigSelectGroup session/->SessionConfigSelectGroup
            ["group-1" "Group" [config-select-option]]
            {:group "group-1"
             :name "Group"
             :options [config-select-option]
             :_meta nil})))


(test/deftest SessionConfigSelectOptions
  (test/testing "accepts plain options and groups"
    (accepts session/SessionConfigSelectOptions
             [[]
              [config-select-option]
              [{:group "group-1" :name "Group" :options []}]])
    (rejects session/SessionConfigSelectOptions
             [nil {} "fast" [:fast] [{:value "fast"}]]))

  (test/testing "constructor round-trips the options vector"
    (test/is (= [config-select-option]
                (session/->SessionConfigSelectOptions [config-select-option])))
    (ctor-rejects session/->SessionConfigSelectOptions [[nil] [{}] [:fast]])))


(test/deftest SessionConfigSelect-and-Boolean
  (test/testing "select payloads require a current value and options"
    (accepts session/SessionConfigSelect
             [{:currentValue "fast" :options [config-select-option]}
              {:currentValue "fast" :options []}])
    (rejects session/SessionConfigSelect
             [{} {:currentValue "fast"} {:options []}
              {:currentValue 1 :options []}]))

  (test/testing "select constructor takes positional arguments"
    (builds session/SessionConfigSelect session/->SessionConfigSelect
            ["fast" []]
            {:currentValue "fast" :options []}))

  (test/testing "boolean payloads require a boolean current value"
    (accepts session/SessionConfigBoolean [{:currentValue true}
                                          {:currentValue false}])
    (rejects session/SessionConfigBoolean [{} {:currentValue nil}
                                           {:currentValue "true"}]))

  (test/testing "boolean constructor takes the current value"
    (builds session/SessionConfigBoolean session/->SessionConfigBoolean
            [true]
            {:currentValue true})))


(test/deftest SessionConfigOption
  (test/testing "accepts select and boolean variants"
    (accepts session/SessionConfigOption
             [select-config-option
              {:id "flag" :name "Flag" :type "boolean" :currentValue true}
              {:id "model"
               :name "Model"
               :type "select"
               :currentValue "fast"
               :options []
               :description "Which model to use"
               :category "model"}]))

  (test/testing "rejects unknown discriminators and missing variant fields"
    (rejects session/SessionConfigOption
             [{}
              {:id "model" :name "Model"}
              {:id "model" :name "Model" :type "radio"}
              {:id "model" :name "Model" :type "select"}
              {:id "model" :name "Model" :type "select" :currentValue "fast"}
              {:id "model"
               :name "Model"
               :type "boolean"
               :currentValue "true"}]))

  (test/testing "constructor builds a select option"
    (builds session/SessionConfigOption session/->SessionConfigOption
            ["model"
             "Model"
             :type "select"
             :currentValue "fast"
             :options []
             :description "Which model to use"
             :category "model"
             :meta {:traceId "t-1"}]
            {:id "model"
             :name "Model"
             :type "select"
             :currentValue "fast"
             :description "Which model to use"
             :category "model"
             :_meta {:traceId "t-1"}
             :options []}))

  (test/testing "constructor builds a boolean option without options"
    (builds session/SessionConfigOption session/->SessionConfigOption
            ["flag" "Flag" :type "boolean" :currentValue true]
            {:id "flag"
             :name "Flag"
             :type "boolean"
             :currentValue true
             :description nil
             :category nil
             :_meta nil}))

  (test/testing "constructor rejects malformed options"
    (ctor-rejects session/->SessionConfigOption
                  [["model" "Model"]
                   ["model" "Model" :type "radio" :currentValue "fast"]
                   ["model" "Model" :type "boolean" :currentValue "true"]])))


(test/deftest SetSessionConfigOptionRequest-and-Response
  (test/testing "requests accept a string value or a boolean value"
    (accepts session/SetSessionConfigOptionRequest
             [{:sessionId "session-1" :configId "model" :value "fast"}
              {:sessionId "session-1"
               :configId "flag"
               :type "boolean"
               :value true}]))

  (test/testing "requests reject malformed values"
    (rejects session/SetSessionConfigOptionRequest
             [{} {:sessionId "session-1" :configId "model"}
              {:sessionId "session-1" :configId "model" :value 1}
              {:sessionId "session-1" :configId "flag" :type "boolean"}
              {:sessionId 1 :configId "model" :value "fast"}]))

  (test/testing "request constructor omits type when not supplied"
    (builds session/SetSessionConfigOptionRequest
            session/->SetSessionConfigOptionRequest
            ["session-1" "model" "fast"]
            {:sessionId "session-1" :configId "model" :value "fast" :_meta nil}))

  (test/testing "request constructor accepts the boolean variant"
    (builds session/SetSessionConfigOptionRequest
            session/->SetSessionConfigOptionRequest
            ["session-1" "flag" true :type "boolean" :meta {:traceId "t-1"}]
            {:sessionId "session-1"
             :configId "flag"
             :value true
             :type "boolean"
             :_meta {:traceId "t-1"}}))

  (test/testing "responses carry the resulting config options"
    (accepts session/SetSessionConfigOptionResponse
             [{:configOptions []} {:configOptions [select-config-option]}])
    (rejects session/SetSessionConfigOptionResponse
             [{} {:configOptions {}} {:configOptions [{:id "model"}]}]))

  (test/testing "response and update constructors share their shape"
    (doseq [[schema ctor]
            [[session/SetSessionConfigOptionResponse
              session/->SetSessionConfigOptionResponse]
             [session/ConfigOptionUpdate session/->ConfigOptionUpdate]]]
      (builds schema ctor
              [[select-config-option]]
              {:configOptions [select-config-option] :_meta nil})
      (builds schema ctor
              [[] :meta {:traceId "t-1"}]
              {:configOptions [] :_meta {:traceId "t-1"}}))))


;; -------------------------------------------------------------------------
;; Content chunks and session updates
;; -------------------------------------------------------------------------

(test/deftest ContentChunk
  (test/testing "accepts a content block"
    (accepts session/ContentChunk
             [{:content text-block}
              {:content text-block :messageId "message-1"}
              {:content text-block :messageId nil}]))

  (test/testing "rejects malformed chunks"
    (rejects session/ContentChunk
             [{} {:content nil} {:content "hello"} {:content {:type "text"}}
              {:content text-block :messageId 1}]))

  (test/testing "constructor fills optional slots with nil"
    (builds session/ContentChunk session/->ContentChunk
            [text-block]
            {:content text-block :messageId nil :_meta nil})
    (builds session/ContentChunk session/->ContentChunk
            [text-block :messageId "message-1" :meta {:traceId "t-1"}]
            {:content text-block
             :messageId "message-1"
             :_meta {:traceId "t-1"}}))

  (test/testing "constructor accepts a constructed content block"
    (test/is (m/validate session/ContentChunk
                         (session/->ContentChunk
                          (content/->ContentBlock (assoc text-block :type "text"))))))

  (test/testing "constructor rejects malformed chunks"
    (ctor-rejects session/->ContentChunk
                  [[nil] ["hello"] [{}] [text-block :messageId 1]])))


(test/deftest SessionUpdate
  (test/testing "accepts every update variant"
    (accepts session/SessionUpdate
             [message-chunk
              {:sessionUpdate "user_message_chunk" :content text-block}
              {:sessionUpdate "agent_thought_chunk" :content text-block}
              {:sessionUpdate "tool_call"
               :toolCallId "call-1"
               :title "Read a file"
               :kind "read"
               :status "pending"}
              {:sessionUpdate "tool_call_update" :toolCallId "call-1"}
              {:sessionUpdate "plan"
               :entries [{:content "Do it" :priority "high" :status "pending"}]}
              {:sessionUpdate "available_commands_update"
               :availableCommands [{:name "run" :description "Run it"}]}
              {:sessionUpdate "current_mode_update" :currentModeId "code"}
              {:sessionUpdate "config_option_update"
               :configOptions [select-config-option]}
              {:sessionUpdate "session_info_update" :title "Notes"}
              {:sessionUpdate "usage_update" :used 10 :size 100}
              {:sessionUpdate "notice" :severity "info" :title "Heads up"}
              {:sessionUpdate "compaction_update"
               :compactionId "compaction-1"
               :status "in_progress"}
              {:sessionUpdate "compaction_summary_chunk"
               :compactionId "compaction-1"
               :content text-block}]))

  (test/testing "rejects unknown or missing discriminators"
    (rejects session/SessionUpdate
             [{}
              {:sessionUpdate "unknown" :content text-block}
              {:sessionUpdate "agent_message_chunk"}
              {:sessionUpdate nil :content text-block}
              {:content text-block}]))

  (test/testing "rejects payloads that do not match their variant"
    (rejects session/SessionUpdate
             [{:sessionUpdate "current_mode_update" :currentModeId 1}
              {:sessionUpdate "usage_update" :used -1 :size 100}
              {:sessionUpdate "notice" :severity "info"}
              {:sessionUpdate "plan" :entries [{}]}]))

  (test/testing "constructor round-trips every variant"
    (doseq [update [message-chunk
                    {:sessionUpdate "plan" :entries []}
                    {:sessionUpdate "notice" :severity "error" :title "Oops"}]]
      (test/is (= update (session/->SessionUpdate update)))
      (test/is (m/validate session/SessionUpdate
                           (session/->SessionUpdate update)))))

  (test/testing "constructor rejects invalid updates"
    (ctor-rejects session/->SessionUpdate
                  [[{}] [{:sessionUpdate "unknown"}] [nil] [message-chunk {}]]))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [data (ex-data-of
                (fn [] (session/->SessionUpdate {:sessionUpdate "unknown"})))]
      (test/is (= {:sessionUpdate "unknown"} (:value data)))
      (test/is (false? (m/validate (:schema data) (:value data))))
      (test/is (some? (:errors data))))))


(test/deftest SessionNotification
  (test/testing "accepts a session id and an update"
    (accepts session/SessionNotification
             [{:sessionId "session-1" :update message-chunk}
              {:sessionId "session-1"
               :update message-chunk
               :_meta {:traceId "t-1"}}]))

  (test/testing "rejects malformed notifications"
    (rejects session/SessionNotification
             [{} {:sessionId "session-1"} {:update message-chunk}
              {:sessionId 1 :update message-chunk}
              {:sessionId "session-1" :update {}}]))

  (test/testing "constructor fills the metadata slot with nil"
    (builds session/SessionNotification session/->SessionNotification
            ["session-1" message-chunk]
            {:sessionId "session-1" :update message-chunk :_meta nil}))

  (test/testing "constructor rejects malformed notifications"
    (ctor-rejects session/->SessionNotification
                  [[nil message-chunk]
                   ["session-1" nil]
                   ["session-1" {}]])))


;; -------------------------------------------------------------------------
;; Prompt turn
;; -------------------------------------------------------------------------

(test/deftest PromptRequest-and-PromptResponse
  (test/testing "requests require a session id and a prompt"
    (accepts session/PromptRequest
             [{:sessionId "session-1" :prompt []}
              {:sessionId "session-1" :prompt [text-block]}])
    (rejects session/PromptRequest
             [{} {:sessionId "session-1"} {:prompt []}
              {:sessionId "session-1" :prompt text-block}
              {:sessionId "session-1" :prompt [{:type "text"}]}]))

  (test/testing "request constructor fills the metadata slot with nil"
    (builds session/PromptRequest session/->PromptRequest
            ["session-1" [text-block]]
            {:sessionId "session-1" :prompt [text-block] :_meta nil}))

  (test/testing "responses require a stop reason"
    (accepts session/PromptResponse [{:stopReason "end_turn"}
                                     {:stopReason "cancelled"}])
    (rejects session/PromptResponse
             [{} {:stopReason nil} {:stopReason "done"}]))

  (test/testing "response constructor fills the metadata slot with nil"
    (builds session/PromptResponse session/->PromptResponse
            ["end_turn"]
            {:stopReason "end_turn" :_meta nil})
    (builds session/PromptResponse session/->PromptResponse
            ["end_turn" :meta {:traceId "t-1"}]
            {:stopReason "end_turn" :_meta {:traceId "t-1"}})))


;; -------------------------------------------------------------------------
;; Plans
;; -------------------------------------------------------------------------

(test/deftest PlanEntry-and-Plan
  (test/testing "plan entries require content, priority and status"
    (accepts session/PlanEntry
             [{:content "Do it" :priority "high" :status "pending"}
              {:content "Do it" :priority "low" :status "completed"}])
    (rejects session/PlanEntry
             [{} {:content "Do it"} {:content "Do it" :priority "high"}
              {:content 1 :priority "high" :status "pending"}
              {:content "Do it" :priority "urgent" :status "pending"}]))

  (test/testing "plan entry constructor keeps its argument order"
    (builds session/PlanEntry session/->PlanEntry
            ["Do it" "high" "in_progress"]
            {:content "Do it"
             :priority "high"
             :status "in_progress"
             :_meta nil}))

  (test/testing "plans require their entries"
    (accepts session/Plan [{:entries []}
                           {:entries [{:content "Do it"
                                       :priority "high"
                                       :status "pending"}]}])
    (rejects session/Plan [{} {:entries nil} {:entries [{}]}]))

  (test/testing "plan constructor fills the metadata slot with nil"
    (builds session/Plan session/->Plan
            [[]]
            {:entries [] :_meta nil})
    (builds session/Plan session/->Plan
            [[{:content "Do it" :priority "high" :status "pending"}]
             :meta {:traceId "t-1"}]
            {:entries [{:content "Do it" :priority "high" :status "pending"}]
             :_meta {:traceId "t-1"}})))


;; -------------------------------------------------------------------------
;; Available commands
;; -------------------------------------------------------------------------

(test/deftest AvailableCommand
  (test/testing "unstructured input requires a hint"
    (accepts session/UnstructuredCommandInput [{:hint "command arguments"}])
    (rejects session/UnstructuredCommandInput [{} {:hint nil} {:hint 1}]))

  (test/testing "unstructured input constructor fills metadata with nil"
    (builds session/UnstructuredCommandInput session/->UnstructuredCommandInput
            ["command arguments"]
            {:hint "command arguments" :_meta nil}))

  (test/testing "command input is unstructured input"
    (test/is (= {:hint "arguments"}
                (session/->AvailableCommandInput {:hint "arguments"})))
    (accepts session/AvailableCommandInput [{:hint "arguments"}])
    (rejects session/AvailableCommandInput [{} "arguments" nil]))

  (test/testing "commands require a name and a description"
    (accepts session/AvailableCommand
             [{:name "run" :description "Run it"}
              {:name "run"
               :description "Run it"
               :input {:hint "arguments"}}])
    (rejects session/AvailableCommand
             [{} {:name "run"} {:description "Run it"} {:name 1 :description "x"}]))

  (test/testing "command constructor fills the input with nil"
    (builds session/AvailableCommand session/->AvailableCommand
            ["run" "Run it"]
            {:name "run" :description "Run it" :input nil :_meta nil})
    (builds session/AvailableCommand session/->AvailableCommand
            ["run" "Run it" :input {:hint "arguments"} :meta {:traceId "t-1"}]
            {:name "run"
             :description "Run it"
             :input {:hint "arguments"}
             :_meta {:traceId "t-1"}}))

  (test/testing "command updates require the available commands"
    (accepts session/AvailableCommandsUpdate [{:availableCommands []}])
    (rejects session/AvailableCommandsUpdate [{} {:availableCommands {}}]))

  (test/testing "command update constructor fills metadata with nil"
    (builds session/AvailableCommandsUpdate session/->AvailableCommandsUpdate
            [[{:name "run" :description "Run it"}]]
            {:availableCommands [{:name "run" :description "Run it"}]
             :_meta nil})))


;; -------------------------------------------------------------------------
;; Usage and cost
;; -------------------------------------------------------------------------

(test/deftest Cost-and-UsageUpdate
  (test/testing "costs require an amount and a currency"
    (accepts session/Cost [{:amount 0.5 :currency "USD"}
                           {:amount 0 :currency "credits"}])
    (rejects session/Cost [{} {:amount 0.5} {:currency "USD"}
                           {:amount "0.5" :currency "USD"}]))

  (test/testing "cost constructor fills the metadata slot with nil"
    (builds session/Cost session/->Cost
            [0.5 "USD"]
            {:amount 0.5 :currency "USD" :_meta nil}))

  (test/testing "usage updates require used and size"
    (accepts session/UsageUpdate
             [{:used 0 :size 10}
              {:used 10 :size 100 :cost {:amount 0.5 :currency "USD"}}
              {:used 10 :size 100 :cost nil}])
    (rejects session/UsageUpdate
             [{} {:used 10} {:size 100} {:used -1 :size 100}
              {:used 1.5 :size 100} {:used 10 :size 100 :cost {}}]))

  (test/testing "usage update constructor fills the cost with nil"
    (builds session/UsageUpdate session/->UsageUpdate
            [10 100]
            {:used 10 :size 100 :cost nil :_meta nil})
    (builds session/UsageUpdate session/->UsageUpdate
            [10 100 :cost {:amount 1.5 :currency "USD"} :meta {:traceId "t-1"}]
            {:used 10
             :size 100
             :cost {:amount 1.5 :currency "USD"}
             :_meta {:traceId "t-1"}})))


;; -------------------------------------------------------------------------
;; Notices
;; -------------------------------------------------------------------------

(test/deftest Notice
  (test/testing "accepts a severity and a non-empty title"
    (accepts session/Notice
             [{:severity "info" :title "Heads up"}
              {:severity "error" :title "Oops" :description "Something broke"}
              {:severity "_custom" :title "Custom"}]))

  (test/testing "rejects missing or empty titles"
    (rejects session/Notice
             [{} {:severity "info"} {:title "Heads up"}
              {:severity "info" :title ""}
              {:severity "info" :title nil}
              {:severity "info" :title "Heads up" :description 1}]))

  (test/testing "constructor fills the description with nil"
    (builds session/Notice session/->Notice
            ["info" "Heads up"]
            {:severity "info" :title "Heads up" :description nil :_meta nil})
    (builds session/Notice session/->Notice
            ["warning" "Careful" :description "Look out" :meta {:traceId "t-1"}]
            {:severity "warning"
             :title "Careful"
             :description "Look out"
             :_meta {:traceId "t-1"}}))

  (test/testing "constructor rejects empty titles"
    (ctor-rejects session/->Notice
                  [["info" ""] ["info" nil] [nil "Title"] ["info" 1]])))


;; -------------------------------------------------------------------------
;; Compaction
;; -------------------------------------------------------------------------

(test/deftest CompactionUpdate-and-CompactionSummaryChunk
  (test/testing "compaction updates require an id and a status"
    (accepts session/CompactionUpdate
             [{:compactionId "compaction-1" :status "in_progress"}
              {:compactionId "compaction-1"
               :status "completed"
               :summary []
               :error nil}])
    (rejects session/CompactionUpdate
             [{} {:compactionId "compaction-1"} {:status "in_progress"}
              {:compactionId 1 :status "in_progress"}
              {:compactionId "compaction-1" :status nil}
              {:compactionId "compaction-1" :status "completed" :summary {}}]))

  (test/testing "compaction update constructor fills optional slots with nil"
    (builds session/CompactionUpdate session/->CompactionUpdate
            ["compaction-1" "in_progress"]
            {:compactionId "compaction-1"
             :status "in_progress"
             :summary nil
             :error nil
             :_meta nil})
    (builds session/CompactionUpdate session/->CompactionUpdate
            ["compaction-1"
             "failed"
             :summary [text-block]
             :error "boom"
             :meta {:traceId "t-1"}]
            {:compactionId "compaction-1"
             :status "failed"
             :summary [text-block]
             :error "boom"
             :_meta {:traceId "t-1"}}))

  (test/testing "summary chunks require an id and a content block"
    (accepts session/CompactionSummaryChunk
             [{:compactionId "compaction-1" :content text-block}])
    (rejects session/CompactionSummaryChunk
             [{} {:compactionId "compaction-1"}
              {:compactionId "compaction-1" :content nil}
              {:compactionId 1 :content text-block}]))

  (test/testing "summary chunk constructor fills metadata with nil"
    (builds session/CompactionSummaryChunk session/->CompactionSummaryChunk
            ["compaction-1" text-block]
            {:compactionId "compaction-1" :content text-block :_meta nil})))


;; -------------------------------------------------------------------------
;; Permission requests
;; -------------------------------------------------------------------------

(test/deftest PermissionOption
  (test/testing "accepts an option id, name and kind"
    (accepts session/PermissionOption [permission-option]))
  (test/testing "rejects malformed options"
    (rejects session/PermissionOption
             [{} {:optionId "opt-allow" :name "Allow"}
              {:optionId "opt-allow" :name "Allow" :kind "maybe"}
              {:optionId 1 :name "Allow" :kind "allow_once"}]))

  (test/testing "constructor keeps its argument order"
    (builds session/PermissionOption session/->PermissionOption
            ["opt-allow" "Allow once" "allow_once"]
            permission-option))

  (test/testing "constructor rejects malformed options"
    (ctor-rejects session/->PermissionOption
                  [["opt-allow" "Allow" "maybe"] [nil "Allow" "allow_once"]])))


(test/deftest RequestPermissionRequest
  (test/testing "accepts a session id, tool call update and options"
    (accepts session/RequestPermissionRequest
             [{:sessionId "session-1"
               :toolCall tool-call-update
               :options [permission-option]}
              {:sessionId "session-1"
               :toolCall tool-call-update
               :options []
               :_meta {:traceId "t-1"}}]))

  (test/testing "rejects malformed requests"
    (rejects session/RequestPermissionRequest
             [{} {:sessionId "session-1"}
              {:sessionId "session-1" :toolCall tool-call-update}
              {:sessionId "session-1" :toolCall {:toolCallId 1} :options []}
              {:sessionId "session-1" :toolCall tool-call-update :options {}}]))

  (test/testing "constructor keeps its argument order"
    (builds session/RequestPermissionRequest session/->RequestPermissionRequest
            ["session-1" tool-call-update [permission-option]]
            {:sessionId "session-1"
             :toolCall tool-call-update
             :options [permission-option]
             :_meta nil}))

  (test/testing "constructor accepts a tool call update built by acp.schema.tool"
    (let [tool-call (tool/->ToolCallUpdate "call-1"
                                           :title "Read a file"
                                           :kind "read"
                                           :status "pending")]
      (test/is (m/validate session/RequestPermissionRequest
                           (session/->RequestPermissionRequest
                            "session-1" tool-call [permission-option]))))))


(test/deftest RequestPermissionOutcome-and-Response
  (test/testing "accepts the cancelled and selected outcomes"
    (accepts session/RequestPermissionOutcome
             [{:outcome "cancelled"}
              {:outcome "selected" :optionId "opt-allow"}]))
  (test/testing "rejects unknown outcomes"
    (rejects session/RequestPermissionOutcome
             [{} {:outcome "maybe"} {:outcome "selected"}
              {:outcome 1}]))

  (test/testing "outcome constructor round-trips"
    (doseq [outcome [{:outcome "cancelled"}
                     {:outcome "selected" :optionId "opt-allow"}]]
      (test/is (= outcome (session/->RequestPermissionOutcome outcome)))))

  (test/testing "selected outcomes require an option id"
    (accepts session/SelectedPermissionOutcome
             [{:optionId "opt-allow"} {:optionId "opt-allow" :_meta nil}])
    (rejects session/SelectedPermissionOutcome [{:optionId 1} {}]))

  (test/testing "selected outcome constructor fills metadata with nil"
    (builds session/SelectedPermissionOutcome session/->SelectedPermissionOutcome
            ["opt-allow"]
            {:optionId "opt-allow" :_meta nil}))

  (test/testing "responses require an outcome"
    (accepts session/RequestPermissionResponse
             [{:outcome {:outcome "cancelled"}}
              {:outcome {:outcome "selected" :optionId "opt-allow"}}])
    (rejects session/RequestPermissionResponse
             [{} {:outcome nil} {:outcome {:outcome "maybe"}}]))

  (test/testing "response constructor fills metadata with nil"
    (builds session/RequestPermissionResponse session/->RequestPermissionResponse
            [{:outcome "cancelled"}]
            {:outcome {:outcome "cancelled"} :_meta nil})))


;; -------------------------------------------------------------------------
;; Cancellation
;; -------------------------------------------------------------------------

(test/deftest CancelNotification
  (test/testing "accepts a session id"
    (accepts session/CancelNotification
             [{:sessionId "session-1" :_meta {:traceId "t-1"}}])
    (rejects session/CancelNotification [{} {:sessionId 1} {}]))

  (test/testing "constructor fills the metadata slot with nil"
    (builds session/CancelNotification session/->CancelNotification
            ["session-1"]
            {:sessionId "session-1" :_meta nil})
    (builds session/CancelNotification session/->CancelNotification
            ["session-1" :meta {:traceId "t-1"}]
            {:sessionId "session-1" :_meta {:traceId "t-1"}}))

  (test/testing "constructor rejects invalid session ids"
    (ctor-rejects session/->CancelNotification [[nil] [1] []])))
