(ns acp.schema.initialize-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.initialize :as initialize]
            [acp.schema.auth :as auth]
            [acp.schema.elicitation :as elicitation]
            [acp.schema.fs :as fs]
            [acp.schema.mcp :as mcp]
            [acp.schema.session :as session]
            [acp.schema.test-support :as support]))


;; -------------------------------------------------------------------------
;; ProtocolVersion
;; -------------------------------------------------------------------------

(test/deftest ProtocolVersion-schema
  (test/testing "accepts the full unsigned-short range"
    (support/accepts initialize/ProtocolVersion [0 1 100 65535]))

  (test/testing "rejects out-of-range and non-integral versions"
    (support/rejects initialize/ProtocolVersion
                     [-1 -100 65536 1.5 "1" :one nil])))


(test/deftest ->ProtocolVersion
  (test/testing "returns a valid version unchanged"
    (test/is (= 1 (initialize/->ProtocolVersion 1)))
    (test/is (= 65535 (initialize/->ProtocolVersion 65535)))
    (test/is (m/validate initialize/ProtocolVersion
                         (initialize/->ProtocolVersion 0))))

  (test/testing "rejects invalid versions"
    (support/ctor-rejects initialize/->ProtocolVersion
                          [[-1] [65536] [1.5] ["1"] [nil]]))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [data (support/ex-data-of #(initialize/->ProtocolVersion 65536))]
      (test/is (= 65536 (:value data)))
      (test/is (false? (m/validate (:schema data) (:value data))))
      (test/is (some? (:errors data))))))


;; -------------------------------------------------------------------------
;; Implementation
;; -------------------------------------------------------------------------

(test/deftest Implementation-schema
  (test/testing "requires a name and a version"
    (support/accepts initialize/Implementation
                     [{:name "client" :version "1.0"}
                      {:name "client"
                       :version "1.0"
                       :title "Client"
                       :_meta {:traceId "t-1"}}
                      {:name "client" :version "1.0" :title nil}
                      {:name "client" :version "1.0" :notInSpec true}])
    (support/rejects initialize/Implementation
                     [{}
                      {:name "client"}
                      {:version "1.0"}
                      {:name nil :version "1.0"}
                      {:name "client" :version nil}
                      {:name 1 :version "1.0"}
                      {:name "client" :version 1}
                      {:name "client" :version "1.0" :title 1}
                      {:name "client" :version "1.0" :_meta "meta"}]))

  (test/testing "rejects non-map values"
    (support/rejects initialize/Implementation
                     [nil "client" [:client] true 42])))


(test/deftest ->Implementation
  (test/testing "keeps the argument order and fills nullable slots"
    (support/builds initialize/Implementation initialize/->Implementation
                    ["client" "1.0"]
                    {:name "client" :version "1.0" :title nil :_meta nil}))

  (test/testing "accepts a title and metadata"
    (support/builds initialize/Implementation initialize/->Implementation
                    ["client"
                     "1.0"
                     {:title "Client" :_meta {:traceId "t-1"}}]
                    {:name "client"
                     :version "1.0"
                     :title "Client"
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects initialize/->Implementation
                          [[nil "1.0"]
                           ["client" nil]
                           ["client" "1.0" {:title 1}]
                           ["client" "1.0" {:_meta "meta"}]])))


;; -------------------------------------------------------------------------
;; PromptCapabilities
;; -------------------------------------------------------------------------

(test/deftest PromptCapabilities-schema
  (test/testing "accepts an empty map and capability flags"
    (support/accepts initialize/PromptCapabilities
                     [{}
                      {:image true}
                      {:audio false}
                      {:embeddedContext true}
                      {:image true :audio true :embeddedContext true}
                      {:_meta {:traceId "t-1"}}
                      {:image true :notInSpec true}]))

  (test/testing "rejects malformed capability values"
    (support/rejects initialize/PromptCapabilities
                     [{:image nil}
                      {:image "true"}
                      {:audio 1}
                      {:embeddedContext []}
                      {:_meta "meta"}
                      nil
                      "capabilities"]))


)
(test/deftest ->PromptCapabilities
  (test/testing "fills the metadata slot with nil"
    (support/builds initialize/PromptCapabilities
                    initialize/->PromptCapabilities
                    []
                    {:_meta nil}))

  (test/testing "accepts capability flags and metadata"
    (support/builds initialize/PromptCapabilities
                    initialize/->PromptCapabilities
                    [{:image true :_meta {:traceId "t-1"}}]
                    {:image true :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid capabilities"
    (support/ctor-rejects initialize/->PromptCapabilities
                          [[{:image "yes"}]
                           [{:audio nil}]
                           [{:_meta "meta"}]])))


;; -------------------------------------------------------------------------
;; AgentCapabilities
;; -------------------------------------------------------------------------

(def ^:private agent-capabilities
  {:loadSession    true
   :promptCapabilities {:image true}
   :mcpCapabilities {:http true}
   :sessionCapabilities {}
   :auth {:logout {}}
   :_meta {:traceId "t-1"}})


(test/deftest AgentCapabilities-schema
  (test/testing "accepts an empty map and every capability"
    (support/accepts initialize/AgentCapabilities
                     [{}
                      {:loadSession true}
                      agent-capabilities
                      {:promptCapabilities {}}
                      {:mcpCapabilities {:sse true}}
                      {:sessionCapabilities {:list {}}}
                      {:auth {:logout {}}}
                      {:notInSpec true}]))

  (test/testing "rejects malformed capabilities"
    (support/rejects initialize/AgentCapabilities
                     [{:loadSession nil}
                      {:loadSession "true"}
                      {:promptCapabilities {:image "yes"}}
                      {:mcpCapabilities {:http 1}}
                      {:sessionCapabilities {:list "yes"}}
                      {:auth {:logout "yes"}}
                      {:_meta "meta"}
                      nil
                      "capabilities"]))


)
(test/deftest ->AgentCapabilities
  (test/testing "fills the metadata slot with nil"
    (support/builds initialize/AgentCapabilities
                    initialize/->AgentCapabilities
                    []
                    {:_meta nil}))

  (test/testing "accepts every capability"
    (support/builds initialize/AgentCapabilities
                    initialize/->AgentCapabilities
                    [{:loadSession true
                      :promptCapabilities {:image true}
                      :mcpCapabilities {:http true}
                      :sessionCapabilities {}
                      :auth {:logout {}}
                      :_meta {:traceId "t-1"}}]
                    agent-capabilities))

  (test/testing "rejects malformed capabilities"
    (support/ctor-rejects initialize/->AgentCapabilities
                          [[{:loadSession "yes"}]
                           [{:promptCapabilities {:image "yes"}}]
                           [{:mcpCapabilities {:http 1}}]
                           [{:sessionCapabilities {:list "yes"}}]
                           [{:auth {:logout "yes"}}]
                           [{:_meta "meta"}]])))


;; -------------------------------------------------------------------------
;; ClientCapabilities
;; -------------------------------------------------------------------------

(def ^:private client-capabilities
  {:fs {:readTextFile true :writeTextFile true}
   :terminal true
   :session {}
   :auth {:terminal true}
   :elicitation {:form {} :url {}}
   :_meta {:traceId "t-1"}})


(test/deftest ClientCapabilities-schema
  (test/testing "accepts an empty map and every capability"
    (support/accepts initialize/ClientCapabilities
                     [{}
                      {:fs {:readTextFile true}}
                      {:terminal true}
                      {:session {}}
                      {:session nil}
                      {:auth {:terminal true}}
                      {:elicitation {:form {}}}
                      {:elicitation nil}
                      client-capabilities
                      {:notInSpec true}]))

  (test/testing "rejects malformed capabilities"
    (support/rejects initialize/ClientCapabilities
                     [{:fs {:readTextFile "yes"}}
                      {:terminal nil}
                      {:session "yes"}
                      {:auth {:terminal "yes"}}
                      {:elicitation {:form "yes"}}
                      {:_meta "meta"}
                      nil
                      "capabilities"]))


)
(test/deftest ->ClientCapabilities
  (test/testing "fills nullable capability slots with nil"
    (support/builds initialize/ClientCapabilities
                    initialize/->ClientCapabilities
                    []
                    {:session nil :elicitation nil :_meta nil}))

  (test/testing "accepts every capability"
    (support/builds initialize/ClientCapabilities
                    initialize/->ClientCapabilities
                    [{:fs {:readTextFile true :writeTextFile true}
                      :terminal true
                      :session {}
                      :auth {:terminal true}
                      :elicitation {:form {} :url {}}
                      :_meta {:traceId "t-1"}}]
                    client-capabilities))

  (test/testing "rejects malformed capabilities"
    (support/ctor-rejects initialize/->ClientCapabilities
                          [[{:fs {:readTextFile "yes"}}]
                           [{:terminal "yes"}]
                           [{:session "yes"}]
                           [{:auth {:terminal "yes"}}]
                           [{:elicitation {:form "yes"}}]
                           [{:_meta "meta"}]])))


;; -------------------------------------------------------------------------
;; InitializeRequest
;; -------------------------------------------------------------------------

(test/deftest InitializeRequest-schema
  (test/testing "requires a protocol version"
    (support/accepts initialize/InitializeRequest
                     [{:protocolVersion 1}
                      {:protocolVersion 1 :clientCapabilities {}}
                      {:protocolVersion 1
                       :clientCapabilities client-capabilities
                       :clientInfo {:name "client" :version "1.0"}
                       :_meta {:traceId "t-1"}}
                      {:protocolVersion 1 :clientInfo nil}
                      {:protocolVersion 1 :notInSpec true}])
    (support/rejects initialize/InitializeRequest
                     [{}
                      {:protocolVersion nil}
                      {:protocolVersion -1}
                      {:protocolVersion 1.5}
                      {:protocolVersion "1"}
                      {:protocolVersion 1 :clientCapabilities nil}
                      {:protocolVersion 1 :clientCapabilities "caps"}
                      {:protocolVersion 1 :clientInfo "client"}
                      {:protocolVersion 1 :_meta "meta"}]))

  (test/testing "rejects non-map values"
    (support/rejects initialize/InitializeRequest
                     [nil "request" [:request] true 42])))


(test/deftest ->InitializeRequest
  (test/testing "fills the nullable client info slot"
    (support/builds initialize/InitializeRequest
                    initialize/->InitializeRequest
                    [1]
                    {:protocolVersion 1 :clientInfo nil :_meta nil}))

  (test/testing "accepts capabilities and client info"
    (support/builds initialize/InitializeRequest
                    initialize/->InitializeRequest
                    [1
                     {:clientCapabilities client-capabilities
                      :clientInfo {:name "client" :version "1.0"}
                      :_meta {:traceId "t-1"}}]
                    {:protocolVersion 1
                     :clientCapabilities client-capabilities
                     :clientInfo {:name "client" :version "1.0"}
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects initialize/->InitializeRequest
                          [[nil]
                           [-1]
                           [1 {:clientCapabilities nil}]
                           [1 {:clientInfo "client"}]
                           [1 {:_meta "meta"}]]))

  (test/testing "requires a protocol version"
    (test/is (thrown? Exception
                      (initialize/->InitializeRequest)))))


;; -------------------------------------------------------------------------
;; InitializeResponse
;; -------------------------------------------------------------------------

(test/deftest InitializeResponse-schema
  (test/testing "requires a protocol version"
    (support/accepts initialize/InitializeResponse
                     [{:protocolVersion 1}
                      {:protocolVersion 1 :agentCapabilities {}}
                      {:protocolVersion 1
                       :agentCapabilities agent-capabilities
                       :authMethods [{:id "method-1" :name "Agent login"}]
                       :agentInfo {:name "agent" :version "1.0"}
                       :_meta {:traceId "t-1"}}
                      {:protocolVersion 1 :agentInfo nil}
                      {:protocolVersion 1 :authMethods []}
                      {:protocolVersion 1 :notInSpec true}])
    (support/rejects initialize/InitializeResponse
                     [{}
                      {:protocolVersion nil}
                      {:protocolVersion -1}
                      {:protocolVersion "1"}
                      {:protocolVersion 1 :agentCapabilities "caps"}
                      {:protocolVersion 1 :authMethods {}}
                      {:protocolVersion 1 :authMethods [{}]}
                      {:protocolVersion 1 :agentInfo "agent"}
                      {:protocolVersion 1 :_meta "meta"}]))

  (test/testing "rejects non-map values"
    (support/rejects initialize/InitializeResponse
                     [nil "response" [:response] true 42])))


(test/deftest ->InitializeResponse
  (test/testing "fills the nullable agent info slot"
    (support/builds initialize/InitializeResponse
                    initialize/->InitializeResponse
                    [1]
                    {:protocolVersion 1 :agentInfo nil :_meta nil}))

  (test/testing "accepts capabilities, auth methods and agent info"
    (support/builds initialize/InitializeResponse
                    initialize/->InitializeResponse
                    [1
                     {:agentCapabilities agent-capabilities
                      :authMethods [{:id "method-1" :name "Agent login"}]
                      :agentInfo {:name "agent" :version "1.0"}
                      :_meta {:traceId "t-1"}}]
                    {:protocolVersion 1
                     :agentCapabilities agent-capabilities
                     :authMethods [{:id "method-1" :name "Agent login"}]
                     :agentInfo {:name "agent" :version "1.0"}
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects initialize/->InitializeResponse
                          [[nil]
                           [-1]
                           [1 {:agentCapabilities "caps"}]
                           [1 {:authMethods {}}]
                           [1 {:agentInfo "agent"}]
                           [1 {:_meta "meta"}]])))


;; -------------------------------------------------------------------------
;; Composition with the feature capability namespaces
;; -------------------------------------------------------------------------

(test/deftest capabilities-compose-with-feature-schemas
  (test/testing "client capabilities accept feature capability values"
    (support/accepts initialize/ClientCapabilities
                     [{:fs (fs/->FileSystemCapabilities)
                       :auth (auth/->AuthCapabilities)
                       :elicitation (elicitation/->ElicitationCapabilities)
                       :session (session/->ClientSessionCapabilities)}]))

  (test/testing "agent capabilities accept feature capability values"
    (support/accepts initialize/AgentCapabilities
                     [{:promptCapabilities (initialize/->PromptCapabilities)
                       :mcpCapabilities (mcp/->McpCapabilities)
                       :sessionCapabilities (session/->SessionCapabilities)
                       :auth (auth/->AgentAuthCapabilities)}]))

  (test/testing "initialize request and response compose both sides"
    (support/accepts initialize/InitializeRequest
                     [(initialize/->InitializeRequest
                       (initialize/->ProtocolVersion 1)
                       {:clientCapabilities (initialize/->ClientCapabilities)
                        :clientInfo (initialize/->Implementation
                                     "client" "1.0")})])
    (support/accepts initialize/InitializeResponse
                     [(initialize/->InitializeResponse
                       (initialize/->ProtocolVersion 1)
                       {:agentCapabilities (initialize/->AgentCapabilities)
                        :agentInfo (initialize/->Implementation "agent" "1.0")})])))
