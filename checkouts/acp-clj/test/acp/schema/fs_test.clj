(ns acp.schema.fs-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.fs :as fs]
            [acp.schema.test-support :as support]))


;; -------------------------------------------------------------------------
;; FileSystemCapabilities
;; -------------------------------------------------------------------------

(test/deftest FileSystemCapabilities-schema
  (test/testing "accepts an empty map and capability flags"
    (support/accepts fs/FileSystemCapabilities
                     [{}
                      {:readTextFile true}
                      {:writeTextFile false}
                      {:readTextFile true :writeTextFile true}
                      {:_meta {:traceId "t-1"}}
                      {:readTextFile true :notInSpec true}]))

  (test/testing "rejects malformed capability values"
    (support/rejects fs/FileSystemCapabilities
                     [{:readTextFile nil}
                      {:readTextFile "true"}
                      {:writeTextFile 1}
                      {:writeTextFile []}
                      {:_meta "meta"}
                      nil
                      "capabilities"
                      [:readTextFile]]))


)
(test/deftest ->FileSystemCapabilities
  (test/testing "fills the metadata slot with nil"
    (support/builds fs/FileSystemCapabilities fs/->FileSystemCapabilities
                    []
                    {:_meta nil}))

  (test/testing "accepts capability flags and metadata"
    (support/builds fs/FileSystemCapabilities fs/->FileSystemCapabilities
                    [:readTextFile true
                     :writeTextFile true
                     :meta {:traceId "t-1"}]
                    {:readTextFile true
                     :writeTextFile true
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid capabilities"
    (support/ctor-rejects fs/->FileSystemCapabilities
                          [[:readTextFile "yes"]
                           [:writeTextFile 1]
                           [:meta "meta"]])))


;; -------------------------------------------------------------------------
;; ReadTextFileRequest
;; -------------------------------------------------------------------------

(test/deftest ReadTextFileRequest-schema
  (test/testing "requires a session id and a path"
    (support/accepts fs/ReadTextFileRequest
                     [{:sessionId "session-1" :path "notes.txt"}
                      {:sessionId "session-1"
                       :path "notes.txt"
                       :line 0
                       :limit 10
                       :_meta {:traceId "t-1"}}
                      {:sessionId "session-1" :path "notes.txt" :line nil}
                      {:sessionId "session-1" :path "notes.txt" :notInSpec true}])
    (support/rejects fs/ReadTextFileRequest
                     [{}
                      {:sessionId "session-1"}
                      {:path "notes.txt"}
                      {:sessionId nil :path "notes.txt"}
                      {:sessionId "session-1" :path nil}
                      {:sessionId 1 :path "notes.txt"}
                      {:sessionId "session-1" :path 1}]))

  (test/testing "rejects malformed line and limit values"
    (support/rejects fs/ReadTextFileRequest
                     [{:sessionId "session-1" :path "notes.txt" :line -1}
                      {:sessionId "session-1" :path "notes.txt" :line 1.5}
                      {:sessionId "session-1" :path "notes.txt" :line "1"}
                      {:sessionId "session-1" :path "notes.txt" :limit -1}
                      {:sessionId "session-1" :path "notes.txt" :limit 1.5}
                      {:sessionId "session-1" :path "notes.txt" :limit "1"}
                      {:sessionId "session-1" :path "notes.txt" :_meta "meta"}]))

  (test/testing "rejects non-map values"
    (support/rejects fs/ReadTextFileRequest
                     [nil "request" [:request] true 42])))


(test/deftest ->ReadTextFileRequest
  (test/testing "fills the nullable slots with nil"
    (support/builds fs/ReadTextFileRequest fs/->ReadTextFileRequest
                    ["session-1" "notes.txt"]
                    {:sessionId "session-1"
                     :path "notes.txt"
                     :line nil
                     :limit nil
                     :_meta nil}))

  (test/testing "accepts line, limit and metadata"
    (support/builds fs/ReadTextFileRequest fs/->ReadTextFileRequest
                    ["session-1"
                     "notes.txt"
                     :line 2
                     :limit 5
                     :meta {:traceId "t-1"}]
                    {:sessionId "session-1"
                     :path "notes.txt"
                     :line 2
                     :limit 5
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects fs/->ReadTextFileRequest
                          [[nil "notes.txt"]
                           ["session-1" nil]
                           ["session-1" "notes.txt" :line -1]
                           ["session-1" "notes.txt" :limit 1.5]
                           ["session-1" "notes.txt" :meta "meta"]]))

  (test/testing "requires a session id and a path"
    (test/is (thrown? Exception
                      (fs/->ReadTextFileRequest)))
    (test/is (thrown? Exception
                      (fs/->ReadTextFileRequest "session-1")))))


;; -------------------------------------------------------------------------
;; ReadTextFileResponse
;; -------------------------------------------------------------------------

(test/deftest ReadTextFileResponse-schema
  (test/testing "requires text content"
    (support/accepts fs/ReadTextFileResponse
                     [{:content ""}
                      {:content "hello"}
                      {:content "hello" :_meta {:traceId "t-1"}}
                      {:content "hello" :notInSpec true}])
    (support/rejects fs/ReadTextFileResponse
                     [{}
                      {:content nil}
                      {:content 1}
                      {:_meta {:traceId "t-1"}}
                      {:_meta "meta"}]))

  (test/testing "rejects non-map values"
    (support/rejects fs/ReadTextFileResponse
                     [nil "hello" [:hello] true 42])))


(test/deftest ->ReadTextFileResponse
  (test/testing "builds a response from text content"
    (support/builds fs/ReadTextFileResponse fs/->ReadTextFileResponse
                    ["hello"]
                    {:content "hello" :_meta nil}))

  (test/testing "accepts metadata"
    (support/builds fs/ReadTextFileResponse fs/->ReadTextFileResponse
                    ["hello" :meta {:traceId "t-1"}]
                    {:content "hello" :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects fs/->ReadTextFileResponse
                          [[nil] [1] [:hello] ["hello" :meta "meta"]])))


;; -------------------------------------------------------------------------
;; WriteTextFileRequest
;; -------------------------------------------------------------------------

(test/deftest WriteTextFileRequest-schema
  (test/testing "requires a session id, a path and content"
    (support/accepts fs/WriteTextFileRequest
                     [{:sessionId "session-1"
                       :path "notes.txt"
                       :content ""}
                      {:sessionId "session-1"
                       :path "notes.txt"
                       :content "hello"
                       :_meta {:traceId "t-1"}}
                      {:sessionId "session-1"
                       :path "notes.txt"
                       :content "hello"
                       :notInSpec true}])
    (support/rejects fs/WriteTextFileRequest
                     [{}
                      {:sessionId "session-1" :path "notes.txt"}
                      {:sessionId "session-1" :content "hello"}
                      {:path "notes.txt" :content "hello"}
                      {:sessionId nil :path "notes.txt" :content "hello"}
                      {:sessionId "session-1" :path nil :content "hello"}
                      {:sessionId "session-1" :path "notes.txt" :content nil}
                      {:sessionId 1 :path "notes.txt" :content "hello"}
                      {:sessionId "session-1" :path 1 :content "hello"}
                      {:sessionId "session-1" :path "notes.txt" :content 1}
                      {:sessionId "session-1"
                       :path "notes.txt"
                       :content "hello"
                       :_meta "meta"}]))

  (test/testing "rejects non-map values"
    (support/rejects fs/WriteTextFileRequest
                     [nil "request" [:request] true 42])))


(test/deftest ->WriteTextFileRequest
  (test/testing "keeps the argument order"
    (support/builds fs/WriteTextFileRequest fs/->WriteTextFileRequest
                    ["session-1" "notes.txt" "hello"]
                    {:sessionId "session-1"
                     :path "notes.txt"
                     :content "hello"
                     :_meta nil}))

  (test/testing "accepts metadata"
    (support/builds fs/WriteTextFileRequest fs/->WriteTextFileRequest
                    ["session-1"
                     "notes.txt"
                     "hello"
                     :meta {:traceId "t-1"}]
                    {:sessionId "session-1"
                     :path "notes.txt"
                     :content "hello"
                     :_meta {:traceId "t-1"}}))

  (test/testing "rejects invalid arguments"
    (support/ctor-rejects fs/->WriteTextFileRequest
                          [[nil "notes.txt" "hello"]
                           ["session-1" nil "hello"]
                           ["session-1" "notes.txt" nil]
                           ["session-1" "notes.txt" "hello" :meta "meta"]]))

  (test/testing "requires all three positional arguments"
    (test/is (thrown? Exception
                      (fs/->WriteTextFileRequest)))
    (test/is (thrown? Exception
                      (fs/->WriteTextFileRequest "session-1" "notes.txt")))))


;; -------------------------------------------------------------------------
;; WriteTextFileResponse
;; -------------------------------------------------------------------------

(test/deftest WriteTextFileResponse-schema
  (test/testing "accepts an empty map and metadata"
    (support/accepts fs/WriteTextFileResponse
                     [{}
                      {:_meta nil}
                      {:_meta {:traceId "t-1"}}
                      {:notInSpec true}]))

  (test/testing "rejects malformed values"
    (support/rejects fs/WriteTextFileResponse
                     [{:_meta "meta"}
                      {:_meta 42}
                      nil
                      "response"
                      [:response]
                      true])))


(test/deftest ->WriteTextFileResponse
  (test/testing "fills the metadata slot"
    (support/builds fs/WriteTextFileResponse fs/->WriteTextFileResponse
                    []
                    {:_meta nil})
    (support/builds fs/WriteTextFileResponse fs/->WriteTextFileResponse
                    [:meta {:traceId "t-1"}]
                    {:_meta {:traceId "t-1"}}))

  (test/testing "rejects non-map metadata"
    (support/ctor-rejects fs/->WriteTextFileResponse
                          [[:meta "meta"]
                           [:meta 42]]))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (support/returned #(fs/->WriteTextFileResponse :meta "meta"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (re-find #"Invalid WriteTextFileResponse" (ex-message e))))))
