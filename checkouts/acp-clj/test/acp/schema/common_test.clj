(ns acp.schema.common-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.common :as common]
            [acp.schema.test-support :as support]))


;; -------------------------------------------------------------------------
;; SessionId
;; -------------------------------------------------------------------------

(test/deftest SessionId-schema
  (support/accepts common/SessionId
                   ["session-1" "" "session/with/slashes"])
  (support/rejects common/SessionId
                   [nil 1 1.5 :session true [:session] {:id "session"}]))


(test/deftest ->SessionId
  (test/testing "returns the identifier"
    (test/is (= "session-1" (common/->SessionId "session-1")))
    (test/is (m/validate common/SessionId
                         (common/->SessionId "session-1"))))

  (test/testing "rejects invalid identifiers"
    (support/ctor-rejects common/->SessionId
                          [[nil] [1] [:session] [{:id "session"}]])))

;; -------------------------------------------------------------------------
;; RequestId
;; -------------------------------------------------------------------------

(test/deftest RequestId-schema
  (support/accepts common/RequestId
                   [nil 0 1 -1 "request-1" ""])
  (support/rejects common/RequestId
                   [1.5 :request true [:request] {:id "request"}]))


(test/deftest ->RequestId
  (test/testing "returns the identifier"
    (test/is (= "request-1" (common/->RequestId "request-1")))
    (test/is (nil? (common/->RequestId nil)))
    (test/is (m/validate common/RequestId
                         (common/->RequestId 1))))

  (test/testing "rejects invalid identifiers"
    (support/ctor-rejects common/->RequestId
                          [[1.5] [:request] [true] [{:id "request"}]]))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (support/returned #(common/->RequestId 1.5))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= 1.5 (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid RequestId" (ex-message e))))))
