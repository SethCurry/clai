(ns acp.schema.terminal-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.terminal :as terminal]))


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


(test/deftest TerminalExitStatus-schema
  (test/testing "accepts an empty map"
    (test/is (m/validate terminal/TerminalExitStatus {})))

  (test/testing "accepts exit codes and signals"
    (test/is (m/validate terminal/TerminalExitStatus {:exitCode 0}))
    (test/is (m/validate terminal/TerminalExitStatus {:exitCode 137 :signal "SIGKILL"}))
    (test/is (m/validate terminal/TerminalExitStatus {:exitCode nil :signal nil})))

  (test/testing "accepts metadata and extra keys (map is open)"
    (test/is (m/validate terminal/TerminalExitStatus {:_meta {:traceId "abc"}}))
    (test/is (m/validate terminal/TerminalExitStatus {:exitCode 1 :notInSpec true})))

  (test/testing "rejects malformed values"
    (test/is (not (m/validate terminal/TerminalExitStatus {:exitCode -1})))
    (test/is (not (m/validate terminal/TerminalExitStatus {:exitCode 1.5})))
    (test/is (not (m/validate terminal/TerminalExitStatus {:exitCode "0"})))
    (test/is (not (m/validate terminal/TerminalExitStatus {:signal 9})))
    (test/is (not (m/validate terminal/TerminalExitStatus {:_meta "meta"})))))


(test/deftest ->TerminalExitStatus
  (test/testing "fills every slot with nil when called without arguments"
    (test/is (= {:exitCode nil :signal nil :_meta nil}
                (terminal/->TerminalExitStatus))))

  (test/testing "accepts exitCode, signal and meta keyword arguments"
    (test/is (= {:exitCode 0 :signal nil :_meta nil}
                (terminal/->TerminalExitStatus :exitCode 0)))
    (test/is (= {:exitCode nil :signal "SIGTERM" :_meta nil}
                (terminal/->TerminalExitStatus :signal "SIGTERM")))
    (test/is (= {:exitCode 143 :signal "SIGTERM" :_meta {:run "abc"}}
                (terminal/->TerminalExitStatus
                 :exitCode 143
                 :signal "SIGTERM"
                 :meta {:run "abc"}))))

  (test/testing "maps the :meta argument onto :_meta"
    (let [value (terminal/->TerminalExitStatus :meta {:traceId "t-1"})]
      (test/is (= {:traceId "t-1"} (:_meta value)))
      (test/is (not (contains? value :meta)))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate terminal/TerminalExitStatus
                        (terminal/->TerminalExitStatus :exitCode 1))))

  (test/testing "rejects invalid exit codes"
    (doseq [exitCode [-1 -100 1.5 "0" :zero]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (terminal/->TerminalExitStatus :exitCode exitCode))
               (str "expected exitCode " (pr-str exitCode) " to be rejected"))))

  (test/testing "rejects a non-string signal"
    (doseq [signal [9 :sig [:a] true]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (terminal/->TerminalExitStatus :signal signal))
               (str "expected signal " (pr-str signal) " to be rejected"))))

  (test/testing "rejects non-map metadata"
    (doseq [meta-value ["meta" 42 [:a]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (terminal/->TerminalExitStatus :meta meta-value))
               (str "expected meta " (pr-str meta-value) " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(terminal/->TerminalExitStatus :exitCode -1))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= {:exitCode -1 :signal nil :_meta nil} (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid TerminalExitStatus" (ex-message e))))))


(test/deftest TerminalOutputResponse-schema
  (test/testing "requires output and truncated"
    (test/is (m/validate terminal/TerminalOutputResponse
                        {:output "" :truncated false}))
    (test/is (not (m/validate terminal/TerminalOutputResponse {:output "hi"})))
    (test/is (not (m/validate terminal/TerminalOutputResponse {:truncated false}))))

  (test/testing "accepts optional exit status and metadata"
    (test/is (m/validate terminal/TerminalOutputResponse
                        {:output "hi"
                         :truncated false
                         :exitStatus {:exitCode 0}
                         :_meta {:traceId "t"}}))
    (test/is (m/validate terminal/TerminalOutputResponse
                        {:output "hi" :truncated true :exitStatus nil})))

  (test/testing "rejects malformed values"
    (test/is (not (m/validate terminal/TerminalOutputResponse
                              {:output nil :truncated false})))
    (test/is (not (m/validate terminal/TerminalOutputResponse
                              {:output 1 :truncated false})))
    (test/is (not (m/validate terminal/TerminalOutputResponse
                              {:output "hi" :truncated nil})))
    (test/is (not (m/validate terminal/TerminalOutputResponse
                              {:output "hi" :truncated "false"})))
    (test/is (not (m/validate terminal/TerminalOutputResponse
                              {:output "hi"
                               :truncated false
                               :exitStatus {:exitCode -1}})))
    (test/is (not (m/validate terminal/TerminalOutputResponse
                              {:output "hi" :truncated false :exitStatus "0"})))))


(test/deftest ->TerminalOutputResponse
  (test/testing "fills optional slots with nil"
    (test/is (= {:output "hello" :truncated false :exitStatus nil :_meta nil}
                (terminal/->TerminalOutputResponse "hello" false))))

  (test/testing "accepts output and truncated positionally"
    (test/is (= {:output "" :truncated true :exitStatus nil :_meta nil}
                (terminal/->TerminalOutputResponse "" true))))

  (test/testing "accepts exitStatus and meta keyword arguments"
    (test/is (= {:output "hello"
                 :truncated true
                 :exitStatus {:exitCode 1 :signal nil :_meta nil}
                 :_meta nil}
                (terminal/->TerminalOutputResponse
                 "hello" true :exitStatus (terminal/->TerminalExitStatus :exitCode 1))))
    (test/is (= {:output "hello"
                 :truncated false
                 :exitStatus nil
                 :_meta {:traceId "t-1"}}
                (terminal/->TerminalOutputResponse
                 "hello" false :meta {:traceId "t-1"}))))

  (test/testing "maps the :meta argument onto :_meta"
    (let [value (terminal/->TerminalOutputResponse "out" false :meta {:a 1})]
      (test/is (= {:a 1} (:_meta value)))
      (test/is (not (contains? value :meta)))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate terminal/TerminalOutputResponse
                        (terminal/->TerminalOutputResponse "out" false))))

  (test/testing "rejects invalid output and truncated flags"
    (doseq [[output truncated] [[nil false]
                                [1 false]
                                [:out false]
                                ["out" nil]
                                ["out" "false"]
                                ["out" 0]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (terminal/->TerminalOutputResponse output truncated))
               (str "expected output " (pr-str output)
                    " / truncated " (pr-str truncated) " to be rejected"))))

  (test/testing "rejects invalid exit statuses"
    (doseq [exitStatus [{:exitCode -1} "0" 7 [:a]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (terminal/->TerminalOutputResponse
                         "out" false :exitStatus exitStatus))
               (str "expected exitStatus " (pr-str exitStatus) " to be rejected"))))

  (test/testing "rejects non-map metadata"
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (terminal/->TerminalOutputResponse "out" false :meta "meta"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(terminal/->TerminalOutputResponse nil false))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= {:output nil :truncated false :exitStatus nil :_meta nil}
                  (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid TerminalOutputResponse" (ex-message e))))))


(test/deftest TerminalId-schema
  (test/testing "accepts strings"
    (test/is (m/validate terminal/TerminalId "terminal-1"))
    (test/is (m/validate terminal/TerminalId ""))
    (test/is (m/validate terminal/TerminalId "terminal/with/slashes")))

  (test/testing "rejects non-string values"
    (doseq [value [nil 1 1.5 :terminal 'terminal true [:terminal] {:id "terminal"}]]
      (test/is (not (m/validate terminal/TerminalId value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->TerminalId
  (test/testing "returns the identifier"
    (test/is (= "terminal-1" (terminal/->TerminalId "terminal-1")))
    (test/is (= "" (terminal/->TerminalId ""))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate terminal/TerminalId
                        (terminal/->TerminalId "terminal-1"))))

  (test/testing "rejects non-string identifiers"
    (doseq [id [nil 1 1.5 :terminal 'terminal true [:terminal] {:id "terminal"}]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (terminal/->TerminalId id))
               (str "expected id " (pr-str id) " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(terminal/->TerminalId 42))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= 42 (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid TerminalId" (ex-message e))))))


(test/deftest Terminal-schema
  (test/testing "accepts a terminal id"
    (test/is (m/validate terminal/Terminal {:terminalId "terminal-1"}))
    (test/is (m/validate terminal/Terminal
                        {:terminalId "terminal-1" :_meta {:traceId "t-1"}})))

  (test/testing "accepts extra keys (map is open)"
    (test/is (m/validate terminal/Terminal
                        {:terminalId "terminal-1" :notInSpec true})))

  (test/testing "requires a valid terminalId"
    (test/is (not (m/validate terminal/Terminal {})))
    (test/is (not (m/validate terminal/Terminal {:terminalId nil})))
    (test/is (not (m/validate terminal/Terminal {:terminalId 1}))))

  (test/testing "rejects non-map values"
    (doseq [value [nil "terminal-1" [:terminal] true 42]]
      (test/is (not (m/validate terminal/Terminal value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->Terminal
  (test/testing "builds a terminal reference from an id"
    (test/is (= {:terminalId "terminal-1" :_meta nil}
                (terminal/->Terminal "terminal-1"))))

  (test/testing "accepts metadata"
    (test/is (= {:terminalId "terminal-1" :_meta {:traceId "t-1"}}
                (terminal/->Terminal "terminal-1"
                                     :meta {:traceId "t-1"}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate terminal/Terminal
                        (terminal/->Terminal "terminal-1"))))

  (test/testing "rejects invalid terminal ids"
    (doseq [id [nil 1 :terminal {:id "terminal"}]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (terminal/->Terminal id))
               (str "expected id " (pr-str id) " to be rejected"))))

  (test/testing "rejects non-map metadata"
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (terminal/->Terminal "terminal-1" :meta "meta"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(terminal/->Terminal 42))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid Terminal" (ex-message e))))))
