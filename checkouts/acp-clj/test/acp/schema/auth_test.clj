(ns acp.schema.auth-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.auth :as auth]
            [acp.schema.test-support :as support]))


;; -------------------------------------------------------------------------
;; Helpers
;; -------------------------------------------------------------------------

(def ^:private metadata-only-types
  [[auth/AuthenticateResponse auth/->AuthenticateResponse
    "AuthenticateResponse"]
   [auth/LogoutCapabilities auth/->LogoutCapabilities
    "LogoutCapabilities"]
   [auth/LogoutRequest auth/->LogoutRequest "LogoutRequest"]
   [auth/LogoutResponse auth/->LogoutResponse "LogoutResponse"]])


;; -------------------------------------------------------------------------
;; AuthCapabilities
;; -------------------------------------------------------------------------

(test/deftest AuthCapabilities-schema
  (test/testing "accepts an empty map and terminal support"
    (support/accepts auth/AuthCapabilities
                     [{}
                      {:terminal true}
                      {:terminal false}
                      {:_meta {:traceId "t-1"}}
                      {:terminal true :notInSpec true}]))

  (test/testing "rejects non-boolean terminal capability values"
    (support/rejects auth/AuthCapabilities
                     [{:terminal nil}
                      {:terminal "true"}
                      {:terminal 1}
                      {:terminal []}
                      {:_meta "meta"}
                      nil
                      "capabilities"
                      [:terminal]]))


)
(test/deftest ->AuthCapabilities
  (test/testing "fills the metadata slot with nil"
    (support/builds auth/AuthCapabilities auth/->AuthCapabilities
                    []
                    {:_meta nil}))

  (test/testing "accepts capabilities as keyword arguments"
    (support/builds auth/AuthCapabilities auth/->AuthCapabilities
                    [:terminal true :meta {:traceId "t-1"}]
                    {:terminal true :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid capabilities"
    (support/ctor-rejects auth/->AuthCapabilities
                          [[:terminal "yes"]
                           [:meta "meta"]]))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [data (support/ex-data-of
                #(auth/->AuthCapabilities :terminal "yes"))]
      (test/is (= {:terminal "yes" :_meta nil} (:value data)))
      (test/is (false? (m/validate (:schema data) (:value data))))
      (test/is (some? (:errors data))))))


;; -------------------------------------------------------------------------
;; AgentAuthCapabilities
;; -------------------------------------------------------------------------

(test/deftest AgentAuthCapabilities-schema
  (test/testing "accepts an empty map, logout and metadata"
    (support/accepts auth/AgentAuthCapabilities
                     [{}
                      {:logout {}}
                      {:logout nil}
                      {:logout {:_meta {:traceId "t-1"}}}
                      {:_meta {:traceId "t-1"}}
                      {:notInSpec true}]))

  (test/testing "rejects malformed logout capabilities"
    (support/rejects auth/AgentAuthCapabilities
                     [{:logout "yes"}
                      {:logout []}
                      {:logout 1}
                      {:_meta "meta"}
                      nil
                      "capabilities"]))


)
(test/deftest ->AgentAuthCapabilities
  (test/testing "fills the nullable logout slot with nil"
    (support/builds auth/AgentAuthCapabilities
                    auth/->AgentAuthCapabilities
                    []
                    {:logout nil :_meta nil}))

  (test/testing "accepts logout capabilities and metadata"
    (support/builds auth/AgentAuthCapabilities
                    auth/->AgentAuthCapabilities
                    [:logout {} :meta {:traceId "t-1"}]
                    {:logout {} :_meta {:traceId "t-1"}}))

  (test/testing "rejects malformed logout capabilities"
    (support/ctor-rejects auth/->AgentAuthCapabilities
                          [[:logout "yes"]
                           [:logout []]
                           [:meta "meta"]])))


;; -------------------------------------------------------------------------
;; AuthMethodId
;; -------------------------------------------------------------------------

(test/deftest AuthMethodId-schema
  (test/testing "accepts strings"
    (support/accepts auth/AuthMethodId
                     ["method-1" "" "method/with/slashes"]))

  (test/testing "rejects non-string values"
    (support/rejects auth/AuthMethodId
                     [nil 1 1.5 :method true [:method] {:id "method"}])))


(test/deftest ->AuthMethodId
  (test/testing "returns the identifier"
    (test/is (= "method-1" (auth/->AuthMethodId "method-1")))
    (test/is (m/validate auth/AuthMethodId
                         (auth/->AuthMethodId "method-1"))))

  (test/testing "rejects invalid identifiers"
    (support/ctor-rejects auth/->AuthMethodId
                          [[nil] [1] [:method] [{:id "method"}]]))

  (test/testing "requires exactly one argument"
    (test/is (thrown? Exception
                      (auth/->AuthMethodId)))
    (test/is (thrown? Exception
                      (auth/->AuthMethodId "a" "b")))))


;; -------------------------------------------------------------------------
;; AuthMethodAgent
;; -------------------------------------------------------------------------

(test/deftest AuthMethodAgent-schema
  (test/testing "accepts an id, a name and optional metadata"
    (support/accepts auth/AuthMethodAgent
                     [{:id "method-1" :name "Agent login"}
                      {:id "method-1"
                       :name "Agent login"
                       :description "Sign in"
                       :_meta {:traceId "t-1"}}
                      {:id "method-1"
                       :name "Agent login"
                       :description nil}
                      {:id "method-1"
                       :name "Agent login"
                       :notInSpec true}]))

  (test/testing "requires an id and a name"
    (support/rejects auth/AuthMethodAgent
                     [{}
                      {:id "method-1"}
                      {:name "Agent login"}
                      {:id nil :name "Agent login"}
                      {:id "method-1" :name nil}
                      {:id 1 :name "Agent login"}
                      {:id "method-1" :name 1}
                      {:id "method-1"
                       :name "Agent login"
                       :description 1}]))

  (test/testing "rejects non-map values"
    (support/rejects auth/AuthMethodAgent
                     [nil "method" [:method] true 42])))


(test/deftest ->AuthMethodAgent
  (test/testing "keeps the argument order and fills nullable slots"
    (support/builds auth/AuthMethodAgent auth/->AuthMethodAgent
                    ["method-1" "Agent login"]
                    {:id "method-1"
                     :name "Agent login"
                     :description nil
                     :_meta nil}))

  (test/testing "accepts description and metadata"
    (support/builds auth/AuthMethodAgent auth/->AuthMethodAgent
                    ["method-1"
                     "Agent login"
                     :description "Sign in"
                     :meta {:traceId "t-1"}]
                    {:id "method-1"
                     :name "Agent login"
                     :description "Sign in"
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects auth/->AuthMethodAgent
                          [[nil "Agent login"]
                           ["method-1" nil]
                           ["method-1" "Agent login" :description 1]]))

  (test/testing "requires an id and a name"
    (test/is (thrown? Exception
                      (auth/->AuthMethodAgent)))
    (test/is (thrown? Exception
                      (auth/->AuthMethodAgent "method-1")))))


;; -------------------------------------------------------------------------
;; AuthMethodTerminal
;; -------------------------------------------------------------------------

(test/deftest AuthMethodTerminal-schema
  (test/testing "accepts terminal arguments and environment variables"
    (support/accepts auth/AuthMethodTerminal
                     [{:id "method-1" :name "Terminal login"}
                      {:id "method-1"
                       :name "Terminal login"
                       :description "Sign in"
                       :args ["--login"]
                       :env {"HOME" "/tmp"}
                       :_meta {:traceId "t-1"}}
                      {:id "method-1" :name "Terminal login" :args [] :env {}}]))

  (test/testing "requires an id and a name"
    (support/rejects auth/AuthMethodTerminal
                     [{}
                      {:id "method-1"}
                      {:name "Terminal login"}
                      {:id nil :name "Terminal login"}
                      {:id "method-1" :name nil}]))

  (test/testing "rejects malformed optional fields"
    (support/rejects auth/AuthMethodTerminal
                     [{:id "method-1" :name "Terminal login" :args nil}
                      {:id "method-1" :name "Terminal login" :args "--login"}
                      {:id "method-1" :name "Terminal login" :args [1]}
                      {:id "method-1" :name "Terminal login" :env nil}
                      {:id "method-1" :name "Terminal login" :env []}
                      {:id "method-1" :name "Terminal login" :env {"A" 1}}
                      {:id "method-1" :name "Terminal login" :description 1}]))


)
(test/deftest ->AuthMethodTerminal
  (test/testing "omits the non-nullable optional fields when not supplied"
    (support/builds auth/AuthMethodTerminal auth/->AuthMethodTerminal
                    ["method-1" "Terminal login"]
                    {:id "method-1"
                     :name "Terminal login"
                     :description nil
                     :_meta nil}))

  (test/testing "accepts args, env and metadata"
    (support/builds auth/AuthMethodTerminal auth/->AuthMethodTerminal
                    ["method-1"
                     "Terminal login"
                     :args ["--login"]
                     :env {"HOME" "/tmp"}
                     :meta {:traceId "t-1"}]
                    {:id "method-1"
                     :name "Terminal login"
                     :args ["--login"]
                     :env {"HOME" "/tmp"}
                     :description nil
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects auth/->AuthMethodTerminal
                          [[nil "Terminal login"]
                           ["method-1" nil]
                           ["method-1" "Terminal login" :args "--login"]
                           ["method-1" "Terminal login" :env []]])))


;; -------------------------------------------------------------------------
;; AuthMethod
;; -------------------------------------------------------------------------

(test/deftest AuthMethod-schema
  (test/testing "accepts agent and terminal method variants"
    (support/accepts auth/AuthMethod
                     [{:id "method-1" :name "Agent login"}
                      {:type "terminal" :id "method-1" :name "Terminal login"}
                      {:type "terminal"
                       :id "method-1"
                       :name "Terminal login"
                       :args ["--login"]
                       :env {"HOME" "/tmp"}}
                      {:id "method-1" :name "Agent login" :notInSpec true}]))

  (test/testing "rejects methods without an id or a name"
    (support/rejects auth/AuthMethod
                     [{}
                      {:id "method-1"}
                      {:name "Agent login"}
                      {:type "terminal"}
                      {:type "terminal" :id "method-1"}
                      {:type "terminal" :name "Terminal login"}
                      {:id 1 :name "Agent login"}]))

  (test/testing "rejects non-map values"
    (support/rejects auth/AuthMethod
                     [nil "agent" [:agent] true 42])))


(test/deftest ->AuthMethod
  (test/testing "accepts a method map"
    (support/builds auth/AuthMethod auth/->AuthMethod
                    [{:id "method-1" :name "Agent login"}]
                    {:id "method-1" :name "Agent login"})
    (support/builds auth/AuthMethod auth/->AuthMethod
                    [{:type "terminal" :id "method-1" :name "Terminal login"}]
                    {:type "terminal"
                     :id "method-1"
                     :name "Terminal login"}))

  (test/testing "rejects invalid methods"
    (support/ctor-rejects auth/->AuthMethod
                          [[{}]
                           [{:id "method-1"}]
                           [{:id 1 :name "Agent login"}]
                           [nil]
                           ["agent"]]))

  (test/testing "requires at most one argument"
    (test/is (thrown? Exception (auth/->AuthMethod)))
    (test/is (thrown? Exception
                      (auth/->AuthMethod {} {})))))


;; -------------------------------------------------------------------------
;; AuthenticateRequest
;; -------------------------------------------------------------------------

(test/deftest AuthenticateRequest-schema
  (test/testing "requires a method id"
    (support/accepts auth/AuthenticateRequest
                     [{:methodId "method-1"}
                      {:methodId "method-1" :_meta {:traceId "t-1"}}
                      {:methodId "method-1" :notInSpec true}])
    (support/rejects auth/AuthenticateRequest
                     [{}
                      {:methodId nil}
                      {:methodId 1}
                      {:methodId :method-1}
                      {:_meta {:traceId "t-1"}}
                      {:_meta "meta"}]))

  (test/testing "rejects non-map values"
    (support/rejects auth/AuthenticateRequest
                     [nil "method-1" [:method-1] true 42])))


(test/deftest ->AuthenticateRequest
  (test/testing "builds a request from a method id"
    (support/builds auth/AuthenticateRequest auth/->AuthenticateRequest
                    ["method-1"]
                    {:methodId "method-1" :_meta nil}))

  (test/testing "accepts metadata"
    (support/builds auth/AuthenticateRequest auth/->AuthenticateRequest
                    ["method-1" :meta {:traceId "t-1"}]
                    {:methodId "method-1" :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects auth/->AuthenticateRequest
                          [[nil] [1] [:method-1] ["method-1" :meta "meta"]])))


;; -------------------------------------------------------------------------
;; Metadata-only responses and requests
;; -------------------------------------------------------------------------

(test/deftest metadata-only-schemas
  (doseq [[schema _ what] metadata-only-types]
    (test/testing (str what " accepts an empty map and metadata")
      (support/accepts schema
                       [{}
                        {:_meta nil}
                        {:_meta {:traceId "t-1"}}
                        {:notInSpec true}]))

    (test/testing (str what " rejects malformed metadata")
      (support/rejects schema
                       [{:_meta "meta"}
                        {:_meta 42}
                        nil
                        "response"
                        [:response]
                        true])))


)
(test/deftest metadata-only-constructors
  (doseq [[schema ctor what] metadata-only-types]
    (test/testing (str what " fills the metadata slot")
      (support/builds schema ctor [] {:_meta nil})
      (support/builds schema ctor
                      [:meta {:traceId "t-1"}]
                      {:_meta {:traceId "t-1"}}))

    (test/testing (str what " rejects non-map metadata")
      (support/ctor-rejects ctor
                            [[:meta "meta"]
                             [:meta 42]])))
  (test/testing "constructor failures name the schema"
    (let [e (support/returned #(auth/->LogoutResponse :meta "meta"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (re-find #"Invalid LogoutResponse" (ex-message e))))))
