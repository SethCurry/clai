(ns acp.schema.elicitation-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.elicitation :as elicitation]
            [acp.schema.test-support :as support]))


;; -------------------------------------------------------------------------
;; Helpers
;; -------------------------------------------------------------------------

(def ^:private form-request
  {:message "Pick a value"
   :mode "form"
   :requestedSchema {:type "object"}
   :sessionId "session-1"})

(def ^:private url-request
  {:message "Sign in"
   :mode "url"
   :elicitationId "elicitation-1"
   :url "https://example.test/elicitation"
   :requestId "request-1"})


;; -------------------------------------------------------------------------
;; ElicitationId, ElicitationSchemaType and StringFormat
;; -------------------------------------------------------------------------

(test/deftest identifier-and-enum-schemas
  (test/testing "ElicitationId accepts strings"
    (support/accepts elicitation/ElicitationId ["elicitation-1" ""])
    (support/rejects elicitation/ElicitationId
                     [nil 1 :id true [:id] {:id "elicitation-1"}]))

  (test/testing "ElicitationSchemaType accepts only object"
    (support/accepts elicitation/ElicitationSchemaType ["object"])
    (support/rejects elicitation/ElicitationSchemaType
                     ["array" "Object" "" nil 1 :object]))

  (test/testing "StringFormat accepts the supported formats"
    (support/accepts elicitation/StringFormat
                     ["email" "uri" "date" "date-time"])
    (support/rejects elicitation/StringFormat
                     ["time" "datetime" "Email" "" nil 1 :email])))


(test/deftest ->identifier-and-enum-constructors
  (test/testing "value constructors return valid values"
    (test/is (= "elicitation-1"
                (elicitation/->ElicitationId "elicitation-1")))
    (test/is (= "object"
                (elicitation/->ElicitationSchemaType "object")))
    (test/is (= "email" (elicitation/->StringFormat "email"))))

  (test/testing "value constructors reject invalid values"
    (support/ctor-rejects elicitation/->ElicitationId [[nil] [1] [:id]])
    (support/ctor-rejects elicitation/->ElicitationSchemaType
                          [["array"] [nil]])
    (support/ctor-rejects elicitation/->StringFormat
                          [["time"] [nil]]))

  (test/testing "value constructors accept at most one argument"
    (test/is (thrown? Exception
                      (elicitation/->ElicitationId)))
    (test/is (thrown? Exception
                      (elicitation/->ElicitationId "a" "b")))
    (test/is (thrown? Exception
                      (elicitation/->StringFormat)))))


;; -------------------------------------------------------------------------
;; ElicitationContentValue
;; -------------------------------------------------------------------------

(test/deftest ElicitationContentValue-schema
  (test/testing "accepts primitive and string-vector values"
    (support/accepts elicitation/ElicitationContentValue
                     ["value" "" 0 1 -1 1.5 true false ["a" "b"] []]))

  (test/testing "rejects unsupported values"
    (support/rejects elicitation/ElicitationContentValue
                     [nil :value {:value "x"} [1] ["a" 1] #{1}]))

  (test/testing "the constructor returns valid values"
    (test/is (= "value"
                (elicitation/->ElicitationContentValue "value")))
    (test/is (= ["a" "b"]
                (elicitation/->ElicitationContentValue ["a" "b"]))))

  (test/testing "the constructor rejects unsupported values"
    (support/ctor-rejects elicitation/->ElicitationContentValue
                          [[nil] [:value] [{:value "x"}] [[1]]])))


;; -------------------------------------------------------------------------
;; Scopes
;; -------------------------------------------------------------------------

(test/deftest ElicitationRequestScope-schema
  (support/accepts elicitation/ElicitationRequestScope
                   [{:requestId "request-1"}
                    {:requestId 1}
                    {:requestId nil}
                    {:requestId "request-1" :notInSpec true}])
  (support/rejects elicitation/ElicitationRequestScope
                   [{} {:requestId :request-1} {:requestId 1.5} nil "scope"]))


(test/deftest ->ElicitationRequestScope
  (support/builds elicitation/ElicitationRequestScope
                  elicitation/->ElicitationRequestScope
                  ["request-1"]
                  {:requestId "request-1"})
  (support/ctor-rejects elicitation/->ElicitationRequestScope
                        [[1.5] [:request-1]]))


(test/deftest ElicitationSessionScope-schema
  (support/accepts elicitation/ElicitationSessionScope
                   [{:sessionId "session-1"}
                    {:sessionId "session-1" :toolCallId "call-1"}
                    {:sessionId "session-1" :toolCallId nil}
                    {:sessionId "session-1" :notInSpec true}])
  (support/rejects elicitation/ElicitationSessionScope
                   [{}
                    {:sessionId nil}
                    {:sessionId 1}
                    {:sessionId "session-1" :toolCallId 1}
                    nil
                    "scope"]))


(test/deftest ->ElicitationSessionScope
  (support/builds elicitation/ElicitationSessionScope
                  elicitation/->ElicitationSessionScope
                  ["session-1"]
                  {:sessionId "session-1" :toolCallId nil})
  (support/builds elicitation/ElicitationSessionScope
                  elicitation/->ElicitationSessionScope
                  ["session-1" :toolCallId "call-1"]
                  {:sessionId "session-1" :toolCallId "call-1"})
  (support/ctor-rejects elicitation/->ElicitationSessionScope
                        [[nil] [1] ["session-1" :toolCallId 1]]))


;; -------------------------------------------------------------------------
;; Capabilities
;; -------------------------------------------------------------------------

(test/deftest ElicitationFormCapabilities-schema
  (support/accepts elicitation/ElicitationFormCapabilities
                   [{} {:_meta nil} {:_meta {:traceId "t-1"}} {:notInSpec true}])
  (support/rejects elicitation/ElicitationFormCapabilities
                   [{:_meta "meta"} nil "capabilities"]))


(test/deftest ElicitationUrlCapabilities-schema
  (support/accepts elicitation/ElicitationUrlCapabilities
                   [{} {:_meta nil} {:_meta {:traceId "t-1"}} {:notInSpec true}])
  (support/rejects elicitation/ElicitationUrlCapabilities
                   [{:_meta "meta"} nil "capabilities"]))


(test/deftest ->empty-elicitation-capabilities
  (doseq [[schema ctor what]
          [[elicitation/ElicitationFormCapabilities
            elicitation/->ElicitationFormCapabilities
            "ElicitationFormCapabilities"]
           [elicitation/ElicitationUrlCapabilities
            elicitation/->ElicitationUrlCapabilities
            "ElicitationUrlCapabilities"]]]
    (test/testing (str what " fills the metadata slot")
      (support/builds schema ctor [] {:_meta nil})
      (support/builds schema ctor
                      [:meta {:traceId "t-1"}]
                      {:_meta {:traceId "t-1"}}))
    (test/testing (str what " rejects malformed metadata")
      (support/ctor-rejects ctor [[:meta "meta"] [:meta 42]]))))


(test/deftest ElicitationCapabilities-schema
  (support/accepts elicitation/ElicitationCapabilities
                   [{}
                    {:form {}}
                    {:url {}}
                    {:form {:url true}}
                    {:form nil :url nil}
                    {:form {} :url {:_meta {:traceId "t-1"}}}
                    {:_meta {:traceId "t-1"}}
                    {:notInSpec true}])
  (support/rejects elicitation/ElicitationCapabilities
                   [{:form "yes"}
                    {:url []}
                    {:form {:_meta "meta"}}
                    {:_meta "meta"}
                    nil
                    "capabilities"]))


(test/deftest ->ElicitationCapabilities
  (support/builds elicitation/ElicitationCapabilities
                  elicitation/->ElicitationCapabilities
                  []
                  {:form nil :url nil :_meta nil})
  (support/builds elicitation/ElicitationCapabilities
                  elicitation/->ElicitationCapabilities
                  [:form {} :url {} :meta {:traceId "t-1"}]
                  {:form {} :url {} :_meta {:traceId "t-1"}})
  (support/ctor-rejects elicitation/->ElicitationCapabilities
                        [[:form "yes"]
                         [:url []]
                         [:meta "meta"]]))


;; -------------------------------------------------------------------------
;; Modes
;; -------------------------------------------------------------------------

(test/deftest ElicitationFormMode-schema
  (support/accepts elicitation/ElicitationFormMode
                   [{:requestedSchema {} :sessionId "session-1"}
                    {:requestedSchema {:type "object"} :requestId "request-1"}
                    {:requestedSchema {} :sessionId "session-1" :notInSpec true}])
  (support/rejects elicitation/ElicitationFormMode
                   [{}
                    {:requestedSchema {}}
                    {:sessionId "session-1"}
                    {:requestedSchema "schema" :sessionId "session-1"}
                    {:requestedSchema {:type "array"} :sessionId "session-1"}
                    nil
                    "mode"]))


(test/deftest ->ElicitationFormMode
  (support/builds elicitation/ElicitationFormMode
                  elicitation/->ElicitationFormMode
                  [{:type "object"} :sessionId "session-1"]
                  {:requestedSchema {:type "object"} :sessionId "session-1"})
  (support/builds elicitation/ElicitationFormMode
                  elicitation/->ElicitationFormMode
                  [{} :requestId "request-1"]
                  {:requestedSchema {} :requestId "request-1"})
  (support/ctor-rejects elicitation/->ElicitationFormMode
                        [[{:type "object"}]
                         [:sessionId "session-1"]
                         [{} :requestId 1.5]]))


(test/deftest ElicitationUrlMode-schema
  (support/accepts elicitation/ElicitationUrlMode
                   [{:elicitationId "elicitation-1"
                     :url "https://example.test"
                     :sessionId "session-1"}
                    {:elicitationId "elicitation-1"
                     :url "https://example.test"
                     :requestId "request-1"}
                    {:elicitationId "elicitation-1"
                     :url "https://example.test"
                     :requestId "request-1"
                     :notInSpec true}])
  (support/rejects elicitation/ElicitationUrlMode
                   [{}
                    {:elicitationId "elicitation-1" :url "https://example.test"}
                    {:url "https://example.test" :sessionId "session-1"}
                    {:elicitationId "elicitation-1" :sessionId "session-1"}
                    {:elicitationId 1
                     :url "https://example.test"
                     :sessionId "session-1"}
                    {:elicitationId "elicitation-1"
                     :url 1
                     :sessionId "session-1"}
                    nil
                    "mode"]))


(test/deftest ->ElicitationUrlMode
  (support/builds elicitation/ElicitationUrlMode
                  elicitation/->ElicitationUrlMode
                  ["elicitation-1" "https://example.test" :sessionId "session-1"]
                  {:elicitationId "elicitation-1"
                   :url "https://example.test"
                   :sessionId "session-1"})
  (support/builds elicitation/ElicitationUrlMode
                  elicitation/->ElicitationUrlMode
                  ["elicitation-1" "https://example.test" :requestId "request-1"]
                  {:elicitationId "elicitation-1"
                   :url "https://example.test"
                   :requestId "request-1"})
  (support/ctor-rejects elicitation/->ElicitationUrlMode
                        [[nil "https://example.test" :sessionId "session-1"]
                         ["elicitation-1" nil :sessionId "session-1"]
                         ["elicitation-1" "https://example.test"]]))


;; -------------------------------------------------------------------------
;; CreateElicitationRequest
;; -------------------------------------------------------------------------

(test/deftest CreateElicitationRequest-schema
  (test/testing "accepts form and url elicitation modes"
    (support/accepts elicitation/CreateElicitationRequest
                     [form-request
                      (assoc form-request :_meta {:traceId "t-1"})
                      url-request
                      {:message "Custom"
                       :mode "custom"
                       :sessionId "session-1"}
                      (assoc form-request :notInSpec true)]))

  (test/testing "requires a message and a known mode"
    (support/rejects elicitation/CreateElicitationRequest
                     [{}
                      {:message "hello"}
                      {:mode "form"
                       :requestedSchema {}
                       :sessionId "session-1"}
                      {:message "hello"
                       :requestedSchema {}
                       :sessionId "session-1"}
                      {:message nil
                       :mode "form"
                       :requestedSchema {}
                       :sessionId "session-1"}]))

  (test/testing "rejects form modes without a scope or schema"
    (support/rejects elicitation/CreateElicitationRequest
                     [{:message "hello" :mode "form" :requestedSchema {}}
                      {:message "hello" :mode "form" :sessionId "session-1"}
                      {:message "hello"
                       :mode "form"
                       :requestedSchema "schema"
                       :sessionId "session-1"}]))

  (test/testing "rejects url modes without the url fields"
    (support/rejects elicitation/CreateElicitationRequest
                     [{:message "hello" :mode "url" :sessionId "session-1"}
                      {:message "hello"
                       :mode "url"
                       :elicitationId "elicitation-1"
                       :sessionId "session-1"}
                      {:message "hello"
                       :mode "url"
                       :url "https://example.test"
                       :sessionId "session-1"}]))

  (test/testing "rejects non-map values"
    (support/rejects elicitation/CreateElicitationRequest
                     [nil "request" [:request] true 42])))


(test/deftest ->CreateElicitationRequest
  (test/testing "accepts a complete form request map"
    (support/builds elicitation/CreateElicitationRequest
                    elicitation/->CreateElicitationRequest
                    ["Pick a value"
                     "form"
                     :requestedSchema {:type "object"}
                     :sessionId "session-1"]
                    (assoc form-request :_meta nil)))

  (test/testing "accepts a complete url request map"
    (support/builds elicitation/CreateElicitationRequest
                    elicitation/->CreateElicitationRequest
                    ["Sign in"
                     "url"
                     :elicitationId "elicitation-1"
                     :url "https://example.test/elicitation"
                     :requestId "request-1"]
                    (assoc url-request :_meta nil)))

  (test/testing "rejects incomplete requests"
    (support/ctor-rejects elicitation/->CreateElicitationRequest
                          [["hello"]
                           ["hello" "form"]
                           ["hello" "url"]
                           [nil "form"]]))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (support/returned
             #(elicitation/->CreateElicitationRequest "hello" "form"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (re-find #"Invalid CreateElicitationRequest" (ex-message e))))))


;; -------------------------------------------------------------------------
;; CreateElicitationResponse and ElicitationAcceptAction
;; -------------------------------------------------------------------------

(test/deftest ElicitationAcceptAction-schema
  (support/accepts elicitation/ElicitationAcceptAction
                   [{}
                    {:content nil}
                    {:content {}}
                    {:content {"name" "value"}}
                    {:content {"count" 1 "ratio" 1.5 "ok" true
                               "tags" ["a" "b"]}}
                    {:content {} :notInSpec true}])
  (support/rejects elicitation/ElicitationAcceptAction
                   [{:content "value"}
                    {:content []}
                    {:content {"name" :value}}
                    {:content {"name" {"nested" "value"}}}
                    nil
                    "action"]))


(test/deftest ->ElicitationAcceptAction
  (support/builds elicitation/ElicitationAcceptAction
                  elicitation/->ElicitationAcceptAction
                  []
                  {:content nil})
  (support/builds elicitation/ElicitationAcceptAction
                  elicitation/->ElicitationAcceptAction
                  [:content {"name" "value"}]
                  {:content {"name" "value"}})
  (support/ctor-rejects elicitation/->ElicitationAcceptAction
                        [[:content "value"]
                         [:content {"name" :value}]]))


(test/deftest CreateElicitationResponse-schema
  (support/accepts elicitation/CreateElicitationResponse
                   [{:action "accept"}
                    {:action "accept" :content {"name" "value"}}
                    {:action "decline"}
                    {:action "cancel"}
                    {:action "defer"}
                    {:action "accept" :_meta {:traceId "t-1"}}
                    {:action "cancel" :notInSpec true}])
  (support/rejects elicitation/CreateElicitationResponse
                   [{}
                    {:action nil}
                    {:action 1}
                    {:action :accept}
                    {:action "accept" :content "value"}
                    {:action "accept" :content {"name" :value}}
                    {:action "accept" :_meta "meta"}
                    nil
                    "response"]))


(test/deftest ->CreateElicitationResponse
  (support/builds elicitation/CreateElicitationResponse
                  elicitation/->CreateElicitationResponse
                  ["decline"]
                  {:action "decline" :_meta nil})
  (support/builds elicitation/CreateElicitationResponse
                  elicitation/->CreateElicitationResponse
                  ["accept" :content {"name" "value"}]
                  {:action "accept" :content {"name" "value"} :_meta nil})
  (support/ctor-rejects elicitation/->CreateElicitationResponse
                        [[1]
                         ["accept" :content "value"]
                         ["decline" :meta "meta"]]))


;; -------------------------------------------------------------------------
;; CompleteElicitationNotification
;; -------------------------------------------------------------------------

(test/deftest CompleteElicitationNotification-schema
  (support/accepts elicitation/CompleteElicitationNotification
                   [{:elicitationId "elicitation-1"}
                    {:elicitationId "elicitation-1" :_meta {:traceId "t-1"}}
                    {:elicitationId "elicitation-1" :notInSpec true}])
  (support/rejects elicitation/CompleteElicitationNotification
                   [{}
                    {:elicitationId nil}
                    {:elicitationId 1}
                    {:_meta {:traceId "t-1"}}
                    {:_meta "meta"}
                    nil
                    "notification"]))


(test/deftest ->CompleteElicitationNotification
  (support/builds elicitation/CompleteElicitationNotification
                  elicitation/->CompleteElicitationNotification
                  ["elicitation-1"]
                  {:elicitationId "elicitation-1" :_meta nil})
  (support/builds elicitation/CompleteElicitationNotification
                  elicitation/->CompleteElicitationNotification
                  ["elicitation-1" :meta {:traceId "t-1"}]
                  {:elicitationId "elicitation-1"
                   :_meta {:traceId "t-1"}})
  (support/ctor-rejects elicitation/->CompleteElicitationNotification
                        [[nil] [1] ["elicitation-1" :meta "meta"]]))


;; -------------------------------------------------------------------------
;; EnumOption
;; -------------------------------------------------------------------------

(test/deftest EnumOption-schema
  (support/accepts elicitation/EnumOption
                   [{:const "a" :title "Option A"}
                    {:const "a" :title "Option A" :description "The first option"}
                    {:const "" :title "" :description nil}
                    {:const "a" :title "Option A" :_meta {:traceId "t-1"}}
                    {:const "a" :title "Option A" :notInSpec true}])
  (support/rejects elicitation/EnumOption
                   [{}
                    {:const "a"}
                    {:title "Option A"}
                    {:const nil :title "Option A"}
                    {:const "a" :title nil}
                    {:const 1 :title "Option A"}
                    {:const "a" :title 1}
                    {:const "a" :title "Option A" :description 1}
                    nil
                    "option"]))


(test/deftest ->EnumOption
  (support/builds elicitation/EnumOption elicitation/->EnumOption
                  ["a" "Option A"]
                  {:const "a"
                   :title "Option A"
                   :description nil
                   :_meta nil})
  (support/builds elicitation/EnumOption elicitation/->EnumOption
                  ["a" "Option A" :description "The first option"]
                  {:const "a"
                   :title "Option A"
                   :description "The first option"
                   :_meta nil})
  (support/ctor-rejects elicitation/->EnumOption
                        [[nil "Option A"]
                         ["a" nil]
                         ["a" "Option A" :description 1]]))


;; -------------------------------------------------------------------------
;; String property schemas
;; -------------------------------------------------------------------------

(test/deftest StringPropertySchema-schema
  (support/accepts elicitation/StringPropertySchema
                   [{}
                    {:title "Name"}
                    {:description "Your name"}
                    {:enum ["a" "b"]}
                    {:format "email"}
                    {:default "value"}
                    {:minLength 0 :maxLength 10}
                    {:oneOf [{:const "a" :title "Option A"}]}
                    {:pattern "^[a-z]+$"}
                    {:title "Name"
                     :description "Your name"
                     :enum ["a" "b"]
                     :format "email"
                     :default "a"
                     :minLength 1
                     :maxLength 10
                     :oneOf [{:const "a" :title "Option A"}]
                     :pattern "^[a-z]+$"
                     :_meta {:traceId "t-1"}}])
  (support/rejects elicitation/StringPropertySchema
                   [{:title 1}
                    {:description 1}
                    {:enum "a"}
                    {:enum [1]}
                    {:format "time"}
                    {:format 1}
                    {:default 1}
                    {:minLength -1}
                    {:maxLength 1.5}
                    {:oneOf [{}]}
                    {:oneOf {:const "a" :title "Option A"}}
                    {:pattern 1}
                    {:_meta "meta"}]))


(test/deftest ->StringPropertySchema
  (support/builds elicitation/StringPropertySchema
                  elicitation/->StringPropertySchema
                  []
                  {:description nil
                   :enum nil
                   :format nil
                   :_meta nil
                   :default nil
                   :minLength nil
                   :title nil
                   :maxLength nil
                   :oneOf nil
                   :pattern nil})
  (support/builds elicitation/StringPropertySchema
                  elicitation/->StringPropertySchema
                  [:title "Name" :enum ["a"]]
                  {:description nil
                   :enum ["a"]
                   :format nil
                   :_meta nil
                   :default nil
                   :minLength nil
                   :title "Name"
                   :maxLength nil
                   :oneOf nil
                   :pattern nil})
  (support/ctor-rejects elicitation/->StringPropertySchema
                        [[:enum [1]]
                         [:format "time"]
                         [:minLength -1]
                         [:oneOf [{}]]]))


;; -------------------------------------------------------------------------
;; Numeric and boolean property schemas
;; -------------------------------------------------------------------------

(test/deftest NumberPropertySchema-schema
  (support/accepts elicitation/NumberPropertySchema
                   [{}
                    {:minimum 0}
                    {:maximum 10.5}
                    {:default 1.5}
                    {:title "Ratio"
                     :description "A ratio"
                     :minimum 0
                     :maximum 1
                     :default 0.5
                     :_meta {:traceId "t-1"}}])
  (support/rejects elicitation/NumberPropertySchema
                   [{:minimum "0"}
                    {:maximum :ten}
                    {:default "1.5"}
                    {:title 1}
                    {:description 1}
                    {:_meta "meta"}]))


(test/deftest ->NumberPropertySchema
  (support/builds elicitation/NumberPropertySchema
                  elicitation/->NumberPropertySchema
                  []
                  {:title nil
                   :description nil
                   :minimum nil
                   :maximum nil
                   :default nil
                   :_meta nil})
  (support/builds elicitation/NumberPropertySchema
                  elicitation/->NumberPropertySchema
                  [:minimum 0.5 :maximum 10.5]
                  {:title nil
                   :description nil
                   :minimum 0.5
                   :maximum 10.5
                   :default nil
                   :_meta nil})
  (support/ctor-rejects elicitation/->NumberPropertySchema
                        [[:minimum "0"]
                         [:maximum :ten]
                         [:default "1.5"]]))


(test/deftest IntegerPropertySchema-schema
  (support/accepts elicitation/IntegerPropertySchema
                   [{}
                    {:minimum 0}
                    {:maximum 10}
                    {:default 1}
                    {:title "Count"
                     :description "A count"
                     :minimum 0
                     :maximum 10
                     :default 5
                     :_meta {:traceId "t-1"}}])
  (support/rejects elicitation/IntegerPropertySchema
                   [{:minimum 0.5}
                    {:maximum 10.5}
                    {:default 1.5}
                    {:title 1}
                    {:description 1}
                    {:_meta "meta"}]))


(test/deftest ->IntegerPropertySchema
  (support/builds elicitation/IntegerPropertySchema
                  elicitation/->IntegerPropertySchema
                  []
                  {:title nil
                   :description nil
                   :minimum nil
                   :maximum nil
                   :default nil
                   :_meta nil})
  (support/builds elicitation/IntegerPropertySchema
                  elicitation/->IntegerPropertySchema
                  [:minimum 0 :maximum 10]
                  {:title nil
                   :description nil
                   :minimum 0
                   :maximum 10
                   :default nil
                   :_meta nil})
  (support/ctor-rejects elicitation/->IntegerPropertySchema
                        [[:minimum 0.5]
                         [:maximum 10.5]
                         [:default 1.5]]))


(test/deftest BooleanPropertySchema-schema
  (support/accepts elicitation/BooleanPropertySchema
                   [{}
                    {:default true}
                    {:default false}
                    {:title "Enabled"
                     :description "Whether it is enabled"
                     :default true
                     :_meta {:traceId "t-1"}}])
  (support/rejects elicitation/BooleanPropertySchema
                   [{:default "true"}
                    {:default 1}
                    {:title 1}
                    {:description 1}
                    {:_meta "meta"}]))


(test/deftest ->BooleanPropertySchema
  (support/builds elicitation/BooleanPropertySchema
                  elicitation/->BooleanPropertySchema
                  []
                  {:title nil :description nil :default nil :_meta nil})
  (support/builds elicitation/BooleanPropertySchema
                  elicitation/->BooleanPropertySchema
                  [:title "Enabled" :default true]
                  {:title "Enabled"
                   :description nil
                   :default true
                   :_meta nil})
  (support/ctor-rejects elicitation/->BooleanPropertySchema
                        [[:default "true"]
                         [:default 1]
                         [:title 1]]))


;; -------------------------------------------------------------------------
;; Multi-select item and property schemas
;; -------------------------------------------------------------------------

(test/deftest StringMultiSelectItems-schema
  (support/accepts elicitation/StringMultiSelectItems
                   [{:enum []}
                    {:enum ["a" "b"]}
                    {:enum ["a"] :_meta {:traceId "t-1"}}
                    {:enum ["a"] :notInSpec true}])
  (support/rejects elicitation/StringMultiSelectItems
                   [{}
                    {:enum "a"}
                    {:enum [1]}
                    {:enum nil}
                    {:_meta "meta"}
                    nil
                    "items"]))


(test/deftest ->StringMultiSelectItems
  (support/builds elicitation/StringMultiSelectItems
                  elicitation/->StringMultiSelectItems
                  [["a" "b"]]
                  {:enum ["a" "b"] :_meta nil})
  (support/ctor-rejects elicitation/->StringMultiSelectItems
                        [[nil] ["a"] [[1]] ["a" :meta "meta"]]))


(test/deftest TitledMultiSelectItems-schema
  (support/accepts elicitation/TitledMultiSelectItems
                   [{:anyOf []}
                    {:anyOf [{:const "a" :title "Option A"}]}
                    {:anyOf [{:const "a" :title "Option A"}]
                     :_meta {:traceId "t-1"}}
                    {:anyOf [] :notInSpec true}])
  (support/rejects elicitation/TitledMultiSelectItems
                   [{}
                    {:anyOf {}}
                    {:anyOf [{}]}
                    {:anyOf [{:const "a"}]}
                    {:anyOf [{:const 1 :title "Option A"}]}
                    {:anyOf nil}
                    {:_meta "meta"}]))


(test/deftest ->TitledMultiSelectItems
  (support/builds elicitation/TitledMultiSelectItems
                  elicitation/->TitledMultiSelectItems
                  [[{:const "a" :title "Option A"}]]
                  {:anyOf [{:const "a" :title "Option A"}]
                   :_meta nil})
  (support/ctor-rejects elicitation/->TitledMultiSelectItems
                        [[nil] [{}] [[{}]] [[{:const "a"}]]]))


(test/deftest MultiSelectItems-schema
  (support/accepts elicitation/MultiSelectItems
                   [{:type "string" :enum ["a" "b"]}
                    {:type "string" :enum [] :_meta {:traceId "t-1"}}
                    {:anyOf [{:const "a" :title "Option A"}]}
                    {:anyOf [] :_meta {:traceId "t-1"}}])
  (support/rejects elicitation/MultiSelectItems
                   [{}
                    {:type "string"}
                    {:type "string" :enum [1]}
                    {:anyOf [{}]}
                    nil
                    "items"]))


(test/deftest ->MultiSelectItems
  (support/builds elicitation/MultiSelectItems
                  elicitation/->MultiSelectItems
                  [{:type "string" :enum ["a"]}]
                  {:type "string" :enum ["a"]})
  (support/builds elicitation/MultiSelectItems
                  elicitation/->MultiSelectItems
                  [{:anyOf [{:const "a" :title "Option A"}]}]
                  {:anyOf [{:const "a" :title "Option A"}]})
  (support/accepts elicitation/MultiSelectItems
                   [{:type "other"} {:type "number"}])
  (support/ctor-rejects elicitation/->MultiSelectItems
                        [[{}] [{:type "string"}] [{:type nil}]] ))


(test/deftest MultiSelectPropertySchema-schema
  (support/accepts elicitation/MultiSelectPropertySchema
                   [{:items {:type "string" :enum ["a"]}}
                    {:items {:type "string" :enum ["a"]}
                     :title "Tags"
                     :description "Choose tags"
                     :minItems 0
                     :maxItems 3
                     :default ["a"]
                     :_meta {:traceId "t-1"}}
                    {:items {:anyOf [{:const "a" :title "Option A"}]}}])
  (support/rejects elicitation/MultiSelectPropertySchema
                   [{}
                    {:items nil}
                    {:items {}}
                    {:items {:type "string"}}
                    {:items {:type "string" :enum [1]}}
                    {:items {:type "string" :enum []} :minItems -1}
                    {:items {:type "string" :enum []} :maxItems 1.5}
                    {:items {:type "string" :enum []} :default [1]}
                    {:items {:type "string" :enum []} :title 1}
                    {:items {:type "string" :enum []} :_meta "meta"}]))


(test/deftest ->MultiSelectPropertySchema
  (support/builds elicitation/MultiSelectPropertySchema
                  elicitation/->MultiSelectPropertySchema
                  [{:type "string" :enum ["a"]}]
                  {:items {:type "string" :enum ["a"]}
                   :title nil
                   :description nil
                   :minItems nil
                   :maxItems nil
                   :default nil
                   :_meta nil})
  (support/builds elicitation/MultiSelectPropertySchema
                  elicitation/->MultiSelectPropertySchema
                  [{:type "string" :enum ["a"]} :default []]
                  {:items {:type "string" :enum ["a"]}
                   :default []
                   :title nil
                   :description nil
                   :minItems nil
                   :maxItems nil
                   :_meta nil})
  (support/ctor-rejects elicitation/->MultiSelectPropertySchema
                        [[{}]
                         [nil]
                         [{:type "string"}]
                         [{:type "string" :enum []} :default [1]]]))


;; -------------------------------------------------------------------------
;; ElicitationPropertySchema and ElicitationSchema
;; -------------------------------------------------------------------------

(test/deftest ElicitationPropertySchema-schema
  (support/accepts elicitation/ElicitationPropertySchema
                   [{:type "string"}
                    {:type "string" :title "Name"}
                    {:type "number" :minimum 0.5}
                    {:type "integer" :minimum 0}
                    {:type "boolean" :default true}
                    {:type "array" :items {:type "string" :enum ["a"]}}
                    {:type "array"
                     :items {:anyOf [{:const "a" :title "Option A"}]}}
                    {:type "date"}])
  (support/rejects elicitation/ElicitationPropertySchema
                   [{}
                    {:type nil}
                    {:type "array"}
                    {:type "string" :enum [1]}
                    {:type "number" :minimum "0"}
                    {:type "integer" :default 1.5}
                    {:type "boolean" :default "true"}
                    nil
                    "property"]))


(test/deftest ->ElicitationPropertySchema
  (doseq [value [{:type "string"}
                 {:type "number" :minimum 0.5}
                 {:type "integer" :minimum 0}
                 {:type "boolean" :default true}
                 {:type "array" :items {:type "string" :enum ["a"]}}]]
    (support/builds elicitation/ElicitationPropertySchema
                    elicitation/->ElicitationPropertySchema
                    [value]
                    value))
  (support/accepts elicitation/ElicitationPropertySchema
                   [{:type "object"}])
  (support/ctor-rejects elicitation/->ElicitationPropertySchema
                        [[{}] [{:type "array"}] [nil]]))


(test/deftest ElicitationSchema-schema
  (support/accepts elicitation/ElicitationSchema
                   [{}
                    {:type "object"}
                    {:title "Profile"}
                    {:description "Tell us about yourself"}
                    {:properties {}}
                    {:properties {"name" {:type "string"}}}
                    {:required []}
                    {:required ["name"]}
                    {:type "object"
                     :title "Profile"
                     :description "Tell us about yourself"
                     :properties {"name" {:type "string"}
                                  "age" {:type "integer"}}
                     :required ["name"]
                     :_meta {:traceId "t-1"}}
                    {:properties {} :notInSpec true}])
  (support/rejects elicitation/ElicitationSchema
                   [{:type "array"}
                    {:type "Object"}
                    {:title 1}
                    {:description 1}
                    {:properties []}
                    {:properties {"name" "string"}}
                    {:required "name"}
                    {:required [1]}
                    {:_meta "meta"}
                    nil
                    "schema"]))


(test/deftest ->ElicitationSchema
  (support/builds elicitation/ElicitationSchema
                  elicitation/->ElicitationSchema
                  []
                  {:title nil :required nil :description nil :_meta nil})
  (support/builds elicitation/ElicitationSchema
                  elicitation/->ElicitationSchema
                  [:type "object"
                   :properties {"name" {:type "string"}}
                   :required ["name"]]
                  {:type "object"
                   :properties {"name" {:type "string"}}
                   :required ["name"]
                   :title nil
                   :description nil
                   :_meta nil})
  (support/accepts elicitation/ElicitationSchema
                   [{:properties {"name" {:type "object"}}}])
  (support/ctor-rejects elicitation/->ElicitationSchema
                        [[:type "array"]
                         [:properties []]
                         [:properties {"name" "string"}]
                         [:required [1]]]))


(test/deftest elicitation-schema-composes
  (test/testing "a form request carries a schema whose properties validate"
    (support/accepts elicitation/CreateElicitationRequest
                     [{:message "Profile"
                       :mode "form"
                       :requestedSchema
                       (elicitation/->ElicitationSchema
                        {:type "object"
                         :properties {"name" (elicitation/->ElicitationPropertySchema
                                              {:type "string"})
                                      "age" (elicitation/->ElicitationPropertySchema
                                             {:type "integer" :minimum 0})}
                         :required ["name"]})
                       :sessionId "session-1"}]))

  (test/testing "multi-select and titled item constructors compose"
    (support/accepts elicitation/MultiSelectPropertySchema
                     [(elicitation/->MultiSelectPropertySchema
                       (elicitation/->TitledMultiSelectItems
                        [{:const "a" :title "Option A"}]))])
    (support/accepts elicitation/MultiSelectPropertySchema
                     [(elicitation/->MultiSelectPropertySchema
                       (assoc (elicitation/->StringMultiSelectItems ["a" "b"])
                              :type "string"))])))
