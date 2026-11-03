(ns acp.schema.protocol-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.protocol :as protocol]
            [acp.schema.common :as common]
            [acp.schema.auth :as auth]
            [acp.schema.elicitation :as elicitation]
            [acp.schema.fs :as fs]
            [acp.schema.initialize :as initialize]
            [acp.schema.session :as session]
            [acp.schema.test-support :as support]
            [acp.schema.tool :as tool]))


;; -------------------------------------------------------------------------
;; RequestId (re-exported from acp.schema.common)
;; -------------------------------------------------------------------------

(test/deftest RequestId-schema
  (support/accepts common/RequestId
                   [nil 0 1 -1 "request-1" ""])
  (support/rejects common/RequestId
                   [1.5 :request true [:request] {:id "request"}]))


(test/deftest ->RequestId
  (test/is (= "request-1"
              (common/->RequestId "request-1")))
  (test/is (nil? (common/->RequestId nil)))
  (test/is (m/validate common/RequestId
                       (common/->RequestId 1)))
  (support/ctor-rejects common/->RequestId
                        [[1.5] [:request] [true] [{:id "request"}]]))


;; -------------------------------------------------------------------------
;; ErrorCode and Error
;; -------------------------------------------------------------------------

(test/deftest ErrorCode-schema
  (test/testing "accepts the protocol's named error codes"
    (support/accepts protocol/ErrorCode
                     [-32700 -32600 -32601 -32602 -32603 -32800 -32000 -32002]))

  (test/testing "accepts any other integer code"
    (support/accepts protocol/ErrorCode [0 1 -1 42 -32001 -99]))

  (test/testing "rejects non-integer codes"
    (support/rejects protocol/ErrorCode
                     [nil 1.5 "1" :code [:code] {:code 1}])))


(test/deftest ->ErrorCode
  (test/is (= -32000 (protocol/->ErrorCode -32000)))
  (test/is (m/validate protocol/ErrorCode (protocol/->ErrorCode 42)))
  (support/ctor-rejects protocol/->ErrorCode
                        [[nil] [1.5] ["1"] [:code]]))


(test/deftest Error-schema
  (support/accepts protocol/Error
                   [{:code -32000 :message "bad"}
                    {:code -32000 :message ""}
                    {:code 42 :message "bad" :data {:detail "more"}}
                    {:code -32000 :message "bad" :data nil}
                    {:code -32000 :message "bad" :notInSpec true}])
  (support/rejects protocol/Error
                   [{}
                    {:code -32000}
                    {:message "bad"}
                    {:code nil :message "bad"}
                    {:code 1.5 :message "bad"}
                    {:code "32000" :message "bad"}
                    {:code -32000 :message nil}
                    {:code -32000 :message 1}
                    nil
                    "error"]))


(test/deftest ->Error
  (support/builds protocol/Error protocol/->Error
                  [-32000 "bad"]
                  {:code -32000 :message "bad"})
  (support/builds protocol/Error protocol/->Error
                  [-32000 "bad" {:data {:detail "more"}}]
                  {:code -32000 :message "bad" :data {:detail "more"}})
  (support/ctor-rejects protocol/->Error
                        [[nil "bad"]
                         [-32000 nil]
                         [-32000 1]]))


;; -------------------------------------------------------------------------
;; Extension envelopes
;; -------------------------------------------------------------------------

(test/deftest extension-envelope-schemas
  (doseq [[schema what] [[protocol/ExtRequest "ExtRequest"]
                         [protocol/ExtResponse "ExtResponse"]
                         [protocol/ExtNotification "ExtNotification"]]]
    (test/testing (str what " accepts arbitrary values")
      (support/accepts schema
                       [nil
                        "value"
                        1
                        :value
                        [:value]
                        {:value "x"}
                        {"value" "x"}]))))


(test/deftest ->extension-envelope-constructors
  (doseq [[ctor what] [[protocol/->ExtRequest "ExtRequest"]
                       [protocol/->ExtResponse "ExtResponse"]
                       [protocol/->ExtNotification "ExtNotification"]]]
    (test/testing (str what " accepts a value")
      (test/is (= {:value "x"} (ctor {:value "x"})))
      (test/is (= "value" (ctor "value"))))))


;; -------------------------------------------------------------------------
;; CancelRequestNotification
;; -------------------------------------------------------------------------

(test/deftest CancelRequestNotification-schema
  (support/accepts protocol/CancelRequestNotification
                   [{:requestId "request-1"}
                    {:requestId 1}
                    {:requestId nil}
                    {:requestId "request-1" :_meta {:traceId "t-1"}}
                    {:requestId "request-1" :notInSpec true}])
  (support/rejects protocol/CancelRequestNotification
                   [{}
                    {:requestId 1.5}
                    {:requestId :request-1}
                    {:_meta {:traceId "t-1"}}
                    {:_meta "meta"}
                    nil
                    "notification"]))


(test/deftest ->CancelRequestNotification
  (support/builds protocol/CancelRequestNotification
                  protocol/->CancelRequestNotification
                  ["request-1"]
                  {:requestId "request-1" :_meta nil})
  (support/builds protocol/CancelRequestNotification
                  protocol/->CancelRequestNotification
                  ["request-1" {:_meta {:traceId "t-1"}}]
                  {:requestId "request-1" :_meta {:traceId "t-1"}})
  (support/ctor-rejects protocol/->CancelRequestNotification
                        [[1.5] [:request-1]
                         ["request-1" {:_meta "meta"}]]))


;; -------------------------------------------------------------------------
;; AgentRequest and ClientRequest
;; -------------------------------------------------------------------------

(def ^:private agent-request-params
  [{:sessionId "session-1" :path "notes.txt"}
   {:sessionId "session-1" :path "notes.txt" :content "hello"}
   {:sessionId "session-1" :command "ls"}
   {:toolCallId "call-1"}
   {:sessionId "session-1" :terminalId "terminal-1"}
   {:message "Pick a value"
    :mode "form"
    :requestedSchema {:type "object"}
    :sessionId "session-1"}
   {"custom" "params"}])

(def ^:private client-request-params
  [{:protocolVersion 1}
   {:methodId "method-1"}
   {}
   {:cwd "/tmp" :mcpServers []}
   {:sessionId "session-1" :prompt [{:type "text" :text "hello"}]}
   {"custom" "params"}])


(test/deftest AgentRequest-schema
  (test/testing "accepts every agent request payload"
    (support/accepts protocol/AgentRequest
                     (for [params agent-request-params]
                       {:id "request-1" :method "method" :params params})))

  (test/testing "requires an id and a method"
    (support/rejects protocol/AgentRequest
                     [{}
                      {:id "request-1"}
                      {:method "method"}
                      {:id 1.5 :method "method"}
                      {:id "request-1" :method nil}
                      {:id "request-1" :method 1}
                      nil
                      "request"]))

  (test/testing "params are optional and may be null"
    (support/accepts protocol/AgentRequest
                     [{:id "request-1" :method "method"}
                      {:id "request-1" :method "method" :params nil}]))

  (test/testing "accepts extra keys (map is open)"
    (support/accepts protocol/AgentRequest
                     [{:id "request-1" :method "method" :notInSpec true}])))


(test/deftest ->AgentRequest
  (support/builds protocol/AgentRequest protocol/->AgentRequest
                  ["request-1" "method"]
                  {:id "request-1" :method "method" :params nil})
  (support/builds protocol/AgentRequest protocol/->AgentRequest
                  ["request-1"
                   "method"
                   {:params {:sessionId "session-1" :command "ls"}}]
                  {:id "request-1"
                   :method "method"
                   :params {:sessionId "session-1" :command "ls"}})
  (support/ctor-rejects protocol/->AgentRequest
                        [[1.5 "method"]
                         ["request-1" nil]
                         ["request-1" 1]])
  (test/is (thrown? Exception
                    (protocol/->AgentRequest "request-1"))))


(test/deftest ClientRequest-schema
  (test/testing "accepts every client request payload"
    (support/accepts protocol/ClientRequest
                     (for [params client-request-params]
                       {:id "request-1" :method "method" :params params})))

  (test/testing "requires an id and a method"
    (support/rejects protocol/ClientRequest
                     [{}
                      {:id "request-1"}
                      {:method "method"}
                      {:id 1.5 :method "method"}
                      {:id "request-1" :method nil}
                      {:id "request-1" :method 1}
                      nil
                      "request"]))

  (test/testing "params are optional and may be null"
    (support/accepts protocol/ClientRequest
                     [{:id "request-1" :method "method"}
                      {:id "request-1" :method "method" :params nil}])))


(test/deftest ->ClientRequest
  (support/builds protocol/ClientRequest protocol/->ClientRequest
                  ["request-1" "initialize"]
                  {:id "request-1" :method "initialize" :params nil})
  (support/builds protocol/ClientRequest protocol/->ClientRequest
                  ["request-1" "initialize" {:params {:protocolVersion 1}}]
                  {:id "request-1"
                   :method "initialize"
                   :params {:protocolVersion 1}})
  (support/ctor-rejects protocol/->ClientRequest
                        [[1.5 "initialize"]
                         ["request-1" nil]
                         ["request-1" 1]]))


;; -------------------------------------------------------------------------
;; AgentResponse and ClientResponse
;; -------------------------------------------------------------------------

(test/deftest AgentResponse-schema
  (support/accepts protocol/AgentResponse
                   [{:id "request-1"
                     :result (initialize/->InitializeResponse 1)}
                    {:id "request-1"
                     :result (initialize/->InitializeResponse
                              (initialize/->ProtocolVersion 1)
                              {:agentInfo (initialize/->Implementation
                                           "agent" "1.0")})}
                    {:id "request-1" :result {}}
                    {:id nil :result {}}
                    {:id 1 :result {}}
                    {:id "request-1" :error {:code -32000 :message "bad"}}
                    {:id "request-1"
                     :result {}
                     :notInSpec true}])
  (support/rejects protocol/AgentResponse
                   [{}
                    {:id "request-1"}
                    {:result {}}
                    {:id 1.5 :result {}}
                    {:id "request-1" :error nil}
                    {:id "request-1" :error "bad"}
                    {:id "request-1" :error {:message "bad"}}
                    nil
                    "response"]))


(test/deftest ->AgentResponse
  (test/testing "accepts a response map"
    (test/is (= {:id "request-1" :result {}}
                (protocol/->AgentResponse {:id "request-1" :result {}})))
    (test/is (= {:id "request-1" :error {:code -32000 :message "bad"}}
                (protocol/->AgentResponse
                 {:id "request-1" :error {:code -32000 :message "bad"}}))))

  (test/testing "rejects invalid response maps"
    (support/ctor-rejects protocol/->AgentResponse
                          [[{}]
                           [{:id "request-1"}]
                           [{:result {}}]]))

  (test/testing "rejects extra arguments"
    (test/is (thrown? Exception
                      (protocol/->AgentResponse {} {})))))


(test/deftest ClientResponse-schema
  (support/accepts protocol/ClientResponse
                   [{:id "request-1" :result (fs/->ReadTextFileResponse "hello")}
                    {:id "request-1"
                     :result (tool/->CreateTerminalResponse "terminal-1")}
                    {:id "request-1"
                     :result (session/->RequestPermissionResponse
                              {:outcome "cancelled"})}
                    {:id "request-1"
                     :result (elicitation/->CreateElicitationResponse
                              "decline")}
                    {:id "request-1" :result {}}
                    {:id "request-1" :error {:code -32000 :message "bad"}}])
  (support/rejects protocol/ClientResponse
                   [{}
                    {:id "request-1"}
                    {:result {}}
                    {:id 1.5 :result {}}
                    {:id "request-1" :error nil}
                    {:id "request-1" :error "bad"}
                    nil
                    "response"]))


(test/deftest ->ClientResponse
  (test/is (= {:id "request-1" :result {}}
              (protocol/->ClientResponse {:id "request-1" :result {}})))
  (test/is (= {:id "request-1" :error {:code -32000 :message "bad"}}
              (protocol/->ClientResponse
               {:id "request-1" :error {:code -32000 :message "bad"}})))
  (support/ctor-rejects protocol/->ClientResponse
                        [[{}] [{:id "request-1"}] [{:result {}}]]))


;; -------------------------------------------------------------------------
;; AgentNotification and ClientNotification
;; -------------------------------------------------------------------------

(def ^:private session-update
  {:sessionUpdate "agent_message_chunk"
   :content {:type "text" :text "hello"}})


(test/deftest AgentNotification-schema
  (support/accepts protocol/AgentNotification
                   [{:method "session/update"
                     :params {:sessionId "session-1" :update session-update}}
                    {:method "elicitation/complete"
                     :params {:elicitationId "elicitation-1"}}
                    {:method "custom/notification"
                     :params {"custom" "params"}}
                    {:method "custom/notification"}
                    {:method "custom/notification" :params nil}
                    {:method "custom/notification" :notInSpec true}])
  (support/rejects protocol/AgentNotification
                   [{}
                    {:method nil}
                    {:method 1}
                    nil
                    "notification"
                    [:notification]]))


(test/deftest ->AgentNotification
  (support/builds protocol/AgentNotification
                  protocol/->AgentNotification
                  ["session/update"]
                  {:method "session/update" :params nil})
  (support/builds protocol/AgentNotification
                  protocol/->AgentNotification
                  ["session/update"
                   {:params {:sessionId "session-1" :update session-update}}]
                  {:method "session/update"
                   :params {:sessionId "session-1" :update session-update}})
  (support/ctor-rejects protocol/->AgentNotification
                        [[nil] [1]]))


(test/deftest ClientNotification-schema
  (support/accepts protocol/ClientNotification
                   [{:method "session/cancel"
                     :params {:sessionId "session-1"}}
                    {:method "custom/notification"
                     :params {"custom" "params"}}
                    {:method "custom/notification"}
                    {:method "custom/notification" :params nil}
                    {:method "custom/notification" :notInSpec true}])
  (support/rejects protocol/ClientNotification
                   [{}
                    {:method nil}
                    {:method 1}
                    nil
                    "notification"
                    [:notification]]))


(test/deftest ->ClientNotification
  (support/builds protocol/ClientNotification
                  protocol/->ClientNotification
                  ["session/cancel"]
                  {:method "session/cancel" :params nil})
  (support/builds protocol/ClientNotification
                  protocol/->ClientNotification
                  ["session/cancel" {:params {:sessionId "session-1"}}]
                  {:method "session/cancel"
                   :params {:sessionId "session-1"}})
  (support/ctor-rejects protocol/->ClientNotification
                        [[nil] [1]]))


;; -------------------------------------------------------------------------
;; Composition across the feature namespaces
;; -------------------------------------------------------------------------

(test/deftest envelopes-compose-with-feature-schemas
  (test/testing "agent requests carry terminal, fs and elicitation payloads"
    (support/accepts protocol/AgentRequest
                     [{:id "request-1"
                       :method "terminal/create"
                       :params (tool/->CreateTerminalRequest "session-1" "ls")}
                      {:id "request-1"
                       :method "fs/read_text_file"
                       :params (fs/->ReadTextFileRequest "session-1" "notes.txt")}
                      {:id "request-1"
                       :method "elicitation/create"
                       :params (elicitation/->CreateElicitationRequest
                                "Pick"
                                "form"
                                :requestedSchema {:type "object"}
                                :sessionId "session-1")}]))

  (test/testing "client requests carry initialize and auth payloads"
    (support/accepts protocol/ClientRequest
                     [{:id "request-1"
                       :method "initialize"
                       :params (initialize/->InitializeRequest 1)}
                      {:id "request-1"
                       :method "authenticate"
                       :params (auth/->AuthenticateRequest "method-1")}]))

  (test/testing "responses carry feature response payloads"
    (support/accepts protocol/AgentResponse
                     [{:id "request-1"
                       :result (initialize/->InitializeResponse 1)}])
    (support/accepts protocol/ClientResponse
                     [{:id "request-1"
                       :result (fs/->ReadTextFileResponse "hello")}
                      {:id "request-1"
                       :result (tool/->CreateTerminalResponse "terminal-1")}]))

  (test/testing "notifications carry session and elicitation payloads"
    (support/accepts protocol/AgentNotification
                     [{:method "session/update"
                       :params (session/->SessionNotification
                                "session-1" session-update)}
                      {:method "elicitation/complete"
                       :params (elicitation/->CompleteElicitationNotification
                                "elicitation-1")}])
    (support/accepts protocol/ClientNotification
                     [{:method "session/cancel"
                       :params (session/->CancelNotification "session-1")}])))
