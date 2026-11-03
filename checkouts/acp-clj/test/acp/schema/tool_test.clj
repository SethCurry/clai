(ns acp.schema.tool-test

  (:require [clojure.test :as test]
            [malli.core :as m]
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

(def ^:private valid-kinds
  ["read" "edit" "delete" "move" "search"
   "execute" "think" "fetch" "switch_mode" "other"])

(def ^:private content-value
  {:type "content"
   :content {:type "text" :text "hello"}})

(def ^:private diff-value
  {:type "diff" :path "notes.txt" :newText "hello"})

(def ^:private terminal-value
  {:type "terminal" :terminalId "terminal-1"})


(test/deftest ToolKind-schema
  (test/testing "accepts every tool kind"
    (doseq [kind valid-kinds]
      (test/is (m/validate tool/ToolKind kind)
               (str "expected " (pr-str kind) " to be accepted"))))

  (test/testing "rejects unknown and differently-cased kinds"
    (doseq [kind ["a" "readl" "Read" "Other" "" "switch-mode" "switchMode"]]
      (test/is (not (m/validate tool/ToolKind kind))
               (str "expected " (pr-str kind) " to be rejected"))))

  (test/testing "rejects non-string values"
    (doseq [value [nil 1 1.5 :read 'read true [:read] {:kind "read"}]]
      (test/is (not (m/validate tool/ToolKind value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->ToolKind
  (test/testing "returns every valid tool kind unchanged"
    (doseq [kind valid-kinds]
      (test/is (= kind (tool/->ToolKind kind))
               (str "expected " (pr-str kind) " to round-trip"))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate tool/ToolKind (tool/->ToolKind "execute"))))

  (test/testing "rejects invalid tool kinds"
    (doseq [kind ["a" "readl" "Read" "Other" "" nil 1 :read [:read]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (tool/->ToolKind kind))
               (str "expected kind " (pr-str kind) " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->ToolKind "a"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= "a" (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid ToolKind" (ex-message e))))))


(test/deftest ToolCallContent-schema
  (test/testing "accepts content, diff and terminal variants"
    (test/is (m/validate tool/ToolCallContent content-value))
    (test/is (m/validate tool/ToolCallContent diff-value))
    (test/is (m/validate tool/ToolCallContent terminal-value)))

  (test/testing "accepts optional fields and metadata on each variant"
    (test/is (m/validate tool/ToolCallContent
                        {:type "content"
                         :content {:type "text" :text "hello"}
                         :_meta {:traceId "t-1"}}))
    (test/is (m/validate tool/ToolCallContent
                        {:type "diff"
                         :path "notes.txt"
                         :oldText "old"
                         :newText "new"
                         :_meta {:traceId "t-2"}}))
    (test/is (m/validate tool/ToolCallContent
                        {:type "terminal"
                         :terminalId "terminal-1"
                         :_meta {:traceId "t-3"}})))

  (test/testing "accepts extra keys (maps are open)"
    (test/is (m/validate tool/ToolCallContent
                        (assoc content-value :notInSpec true)))
    (test/is (m/validate tool/ToolCallContent
                        (assoc terminal-value :notInSpec true))))

  (test/testing "requires a known type discriminator"
    (test/is (not (m/validate tool/ToolCallContent {})))
    (test/is (not (m/validate tool/ToolCallContent {:type "unknown"})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "Content" :content {:type "text" :text "hi"}})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type nil :content {:type "text" :text "hi"}}))))

  (test/testing "rejects malformed content variants"
    (test/is (not (m/validate tool/ToolCallContent {:type "content"})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "content" :content nil})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "content" :content {:type "text"}})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "content" :content "hello"}))))

  (test/testing "rejects malformed diff variants"
    (test/is (not (m/validate tool/ToolCallContent {:type "diff"})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "diff" :path "notes.txt"})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "diff" :newText "hello"})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "diff" :path 1 :newText "hello"})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "diff" :path "notes.txt" :newText nil})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "diff"
                               :path "notes.txt"
                               :oldText 1
                               :newText "hello"}))))

  (test/testing "rejects malformed terminal variants"
    (test/is (not (m/validate tool/ToolCallContent {:type "terminal"})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "terminal" :terminalId nil})))
    (test/is (not (m/validate tool/ToolCallContent
                              {:type "terminal" :terminalId 1}))))

  (test/testing "rejects non-map values"
    (doseq [value [nil "content" :content [:content] true 42]]
      (test/is (not (m/validate tool/ToolCallContent value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->ToolCallContent
  (test/testing "returns each valid variant unchanged"
    (test/is (= content-value (tool/->ToolCallContent content-value)))
    (test/is (= diff-value (tool/->ToolCallContent diff-value)))
    (test/is (= terminal-value (tool/->ToolCallContent terminal-value))))

  (test/testing "preserves metadata and extra keys"
    (let [value {:type "content"
                 :content {:type "text" :text "hello"}
                 :_meta {:traceId "t-1"}
                 :notInSpec true}]
      (test/is (= value (tool/->ToolCallContent value)))))

  (test/testing "returns a value that satisfies the schema"
    (doseq [value [content-value diff-value terminal-value]]
      (test/is (m/validate tool/ToolCallContent
                           (tool/->ToolCallContent value)))))

  (test/testing "rejects invalid content values"
    (doseq [value [{}
                   {:type "unknown"}
                   {:type "content"}
                   {:type "content" :content {:type "text"}}
                   {:type "diff" :path "notes.txt"}
                   {:type "diff" :path 1 :newText "hello"}
                   {:type "terminal"}
                   {:type "terminal" :terminalId 1}
                   nil
                   "content"
                   [:content]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (tool/->ToolCallContent value))
               (str "expected " (pr-str value) " to be rejected"))))


  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->ToolCallContent {:type "unknown"}))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= {:type "unknown"} (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid ToolCallContent" (ex-message e))))))


;; -------------------------------------------------------------------------
;; Tool calls
;; -------------------------------------------------------------------------

(def ^:private tool-call-location {:path "notes.txt" :line 3 :_meta nil})

(def ^:private tool-call
  {:toolCallId "call-1"
   :title "Read notes.txt"
   :_meta nil
   :name nil
   :kind "read"
   :status "in_progress"})


(test/deftest ToolCallId-schema
  (test/testing "accepts strings"
    (test/is (m/validate tool/ToolCallId "call-1"))
    (test/is (m/validate tool/ToolCallId "")))

  (test/testing "rejects non-string values"
    (doseq [value [nil 1 1.5 :call true [:call] {:id "call"}]]
      (test/is (not (m/validate tool/ToolCallId value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->ToolCallId
  (test/testing "returns the identifier"
    (test/is (= "call-1" (tool/->ToolCallId "call-1")))
    (test/is (m/validate tool/ToolCallId (tool/->ToolCallId "call-1"))))

  (test/testing "rejects invalid identifiers"
    (doseq [id [nil 1 :call {:id "call"}]]
      (test/is (thrown? clojure.lang.ExceptionInfo (tool/->ToolCallId id))
               (str "expected id " (pr-str id) " to be rejected")))))



(test/deftest ToolCallStatus-schema
  (test/testing "accepts every status"
    (doseq [status ["pending" "in_progress" "completed" "failed"]]
      (test/is (m/validate tool/ToolCallStatus status)
               (str "expected " (pr-str status) " to be accepted"))))

  (test/testing "rejects unknown statuses"
    (doseq [status ["done" "in-progress" "Pending" "" nil 1 :pending]]
      (test/is (not (m/validate tool/ToolCallStatus status))
               (str "expected " (pr-str status) " to be rejected")))))


(test/deftest ->ToolCallStatus
  (test/testing "round-trips every status"
    (doseq [status ["pending" "in_progress" "completed" "failed"]]
      (test/is (= status (tool/->ToolCallStatus status)))))

  (test/testing "rejects invalid statuses"
    (doseq [status ["done" nil 1 [:pending]]]
      (test/is (thrown? clojure.lang.ExceptionInfo (tool/->ToolCallStatus status))
               (str "expected status " (pr-str status) " to be rejected")))))


(test/deftest ToolCallLocation-schema
  (test/testing "accepts a path with an optional line"
    (test/is (m/validate tool/ToolCallLocation {:path "notes.txt"}))
    (test/is (m/validate tool/ToolCallLocation {:path "notes.txt" :line 0}))
    (test/is (m/validate tool/ToolCallLocation {:path "notes.txt" :line nil})))

  (test/testing "accepts extra keys (map is open)"
    (test/is (m/validate tool/ToolCallLocation
                        {:path "notes.txt" :notInSpec true})))

  (test/testing "rejects malformed locations"
    (test/is (not (m/validate tool/ToolCallLocation {})))
    (test/is (not (m/validate tool/ToolCallLocation {:path nil})))
    (test/is (not (m/validate tool/ToolCallLocation {:path 1})))
    (test/is (not (m/validate tool/ToolCallLocation {:path "a" :line -1})))
    (test/is (not (m/validate tool/ToolCallLocation {:path "a" :line 1.5})))

    (doseq [value [nil "notes.txt" [:notes.txt] true 42]]
      (test/is (not (m/validate tool/ToolCallLocation value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->ToolCallLocation
  (test/testing "fills the optional slots with nil"
    (test/is (= {:path "notes.txt" :line nil :_meta nil}
                (tool/->ToolCallLocation "notes.txt")))
    (test/is (= {:path "notes.txt" :line 3 :_meta {:traceId "t-1"}}
                (tool/->ToolCallLocation "notes.txt"
                                         :line 3
                                         :meta {:traceId "t-1"}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate tool/ToolCallLocation
                        (tool/->ToolCallLocation "notes.txt" :line 1))))

  (test/testing "rejects invalid arguments"
    (test/is (thrown? clojure.lang.ExceptionInfo (tool/->ToolCallLocation nil)))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (tool/->ToolCallLocation "a" :line -1)))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (tool/->ToolCallLocation "a" :meta "meta")))))


(test/deftest ToolCall-schema
  (test/testing "accepts a tool call id and title"
    (test/is (m/validate tool/ToolCall {:toolCallId "call-1" :title "Read"}))
    (test/is (m/validate tool/ToolCall tool-call))
    (test/is (m/validate tool/ToolCall
                        {:toolCallId "call-1"
                         :title "Read"
                         :kind "read"
                         :status "completed"
                         :content [content-value]
                         :locations [tool-call-location]
                         :rawInput {:path "notes.txt"}
                         :rawOutput "done"})))

  (test/testing "accepts an explicit null name but not a null status"
    (test/is (m/validate tool/ToolCall
                        {:toolCallId "call-1" :title "Read" :name nil}))
    (test/is (not (m/validate tool/ToolCall
                              {:toolCallId "call-1" :title "Read" :status nil}))))

  (test/testing "accepts extra keys (map is open)"
    (test/is (m/validate tool/ToolCall
                        {:toolCallId "call-1" :title "Read" :notInSpec true})))

  (test/testing "requires a tool call id and a title"
    (test/is (not (m/validate tool/ToolCall {})))
    (test/is (not (m/validate tool/ToolCall {:title "Read"})))
    (test/is (not (m/validate tool/ToolCall {:toolCallId "call-1"})))
    (test/is (not (m/validate tool/ToolCall {:toolCallId 1 :title "Read"})))
    (test/is (not (m/validate tool/ToolCall {:toolCallId "call-1" :title nil}))))

  (test/testing "rejects malformed optional fields"
    (test/is (not (m/validate tool/ToolCall
                              {:toolCallId "call-1" :title "Read" :kind "a"})))
    (test/is (not (m/validate tool/ToolCall
                              {:toolCallId "call-1"
                               :title "Read"
                               :status "done"})))
    (test/is (not (m/validate tool/ToolCall
                              {:toolCallId "call-1"
                               :title "Read"
                               :content [{:type "unknown"}]})))
    (test/is (not (m/validate tool/ToolCall
                              {:toolCallId "call-1"
                               :title "Read"
                               :locations [{:path 1}]}))))

  (test/testing "rejects non-map values"
    (doseq [value [nil "call-1" [:call-1] true 42]]
      (test/is (not (m/validate tool/ToolCall value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->ToolCall
  (test/testing "builds a tool call from the required fields"
    (test/is (= {:toolCallId "call-1" :title "Read" :_meta nil :name nil}
                (tool/->ToolCall "call-1" "Read"))))

  (test/testing "omits the optional fields that are not supplied"
    (let [value (tool/->ToolCall "call-1" "Read")]
      (doseq [k [:content :locations :rawOutput :status :kind :rawInput]]
        (test/is (not (contains? value k))
                 (str "expected " k " to be omitted")))))

  (test/testing "accepts every optional field"
    (test/is (= {:toolCallId "call-1"
                 :title "Read"
                 :_meta {:traceId "t-1"}
                 :name "read_file"
                 :kind "read"
                 :status "pending"
                 :content [{:type "diff" :path "a.txt" :newText "b"}]
                 :locations [{:path "a.txt"}]
                 :rawInput {:path "a.txt"}
                 :rawOutput {:ok true}}
                (tool/->ToolCall "call-1"
                                 "Read"
                                 :name "read_file"
                                 :kind "read"
                                 :status "pending"
                                 :content [{:type "diff" :path "a.txt" :newText "b"}]
                                 :locations [{:path "a.txt"}]
                                 :rawInput {:path "a.txt"}
                                 :rawOutput {:ok true}
                                 :meta {:traceId "t-1"}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate tool/ToolCall (tool/->ToolCall "call-1" "Read")))
    (test/is (m/validate tool/ToolCall
                        (tool/->ToolCall "call-1" "Read" :kind "read"))))

  (test/testing "rejects invalid arguments"
    (doseq [args [[nil "Read"]
                  ["call-1" nil]
                  ["call-1" "Read" :kind "a"]
                  ["call-1" "Read" :status "done"]
                  ["call-1" "Read" :content [{}]]
                  ["call-1" "Read" :locations [{:path 1}]]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (apply tool/->ToolCall args))
               (str "expected " (pr-str args) " to be rejected"))))

  (test/testing "rejects non-map metadata"
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (tool/->ToolCall "call-1" "Read" :meta "meta")))))


(test/deftest ToolCallUpdate-schema
  (test/testing "accepts a tool call id on its own"
    (test/is (m/validate tool/ToolCallUpdate {:toolCallId "call-1"})))

  (test/testing "accepts partial updates with explicit nulls"
    (test/is (m/validate tool/ToolCallUpdate
                        {:toolCallId "call-1"
                         :content nil
                         :name nil
                         :title nil
                         :locations nil
                         :status nil
                         :kind nil})))

  (test/testing "accepts every optional field"
    (test/is (m/validate tool/ToolCallUpdate
                        {:toolCallId "call-1"
                         :content [content-value]
                         :name "read_file"
                         :title "Read"
                         :locations [tool-call-location]
                         :rawOutput "done"
                         :status "completed"
                         :kind "read"
                         :rawInput {:path "notes.txt"}})))

  (test/testing "requires a tool call id"
    (test/is (not (m/validate tool/ToolCallUpdate {})))
    (test/is (not (m/validate tool/ToolCallUpdate {:toolCallId nil})))
    (test/is (not (m/validate tool/ToolCallUpdate {:toolCallId 1}))))

  (test/testing "rejects malformed optional fields"
    (test/is (not (m/validate tool/ToolCallUpdate
                              {:toolCallId "call-1" :status "done"})))
    (test/is (not (m/validate tool/ToolCallUpdate
                              {:toolCallId "call-1" :kind "a"})))
    (test/is (not (m/validate tool/ToolCallUpdate
                              {:toolCallId "call-1" :content [{}]})))

    (doseq [value [nil "call-1" [:call-1] true 42]]
      (test/is (not (m/validate tool/ToolCallUpdate value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->ToolCallUpdate
  (test/testing "fills the nullable slots with nil"
    (test/is (= {:toolCallId "call-1"
                 :_meta nil
                 :content nil
                 :name nil
                 :title nil
                 :locations nil
                 :status nil
                 :kind nil}
                (tool/->ToolCallUpdate "call-1"))))

  (test/testing "omits the non-nullable optional fields when not supplied"
    (let [value (tool/->ToolCallUpdate "call-1")]
      (test/is (not (contains? value :rawOutput)))
      (test/is (not (contains? value :rawInput)))))

  (test/testing "accepts every optional field"
    (test/is (= {:toolCallId "call-1"
                 :_meta {:traceId "t-1"}
                 :content nil
                 :name nil
                 :title nil
                 :locations nil
                 :status "completed"
                 :kind nil
                 :rawOutput "done"
                 :rawInput {:path "notes.txt"}}
                (tool/->ToolCallUpdate "call-1"
                                       :status "completed"
                                       :rawOutput "done"
                                       :rawInput {:path "notes.txt"}
                                       :meta {:traceId "t-1"}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate tool/ToolCallUpdate
                        (tool/->ToolCallUpdate "call-1")))
    (test/is (m/validate tool/ToolCallUpdate
                        (tool/->ToolCallUpdate "call-1"
                                               :status "completed"
                                               :content [content-value]))))

  (test/testing "rejects invalid arguments"
    (doseq [args [[nil] ["call-1" :status "done"] ["call-1" :kind "a"]
                  ["call-1" :content [{}]]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (apply tool/->ToolCallUpdate args))
               (str "expected " (pr-str args) " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->ToolCallUpdate nil))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid ToolCallUpdate" (ex-message e))))))



;; -------------------------------------------------------------------------
;; Requests that only carry a session id and a terminal id
;; -------------------------------------------------------------------------

(def ^:private session-terminal-requests
  [[tool/TerminalOutputRequest
    tool/->TerminalOutputRequest
    "TerminalOutputRequest"]
   [tool/KillTerminalRequest
    tool/->KillTerminalRequest
    "KillTerminalRequest"]
   [tool/WaitForTerminalExitRequest
    tool/->WaitForTerminalExitRequest
    "WaitForTerminalExitRequest"]
   [tool/ReleaseTerminalRequest
    tool/->ReleaseTerminalRequest
    "ReleaseTerminalRequest"]])

(test/deftest session-terminal-request-schemas
  (doseq [[schema _ what] session-terminal-requests]
    (test/testing (str what " accepts a session id and terminal id")
      (test/is (m/validate schema {:sessionId "session-1"
                                   :terminalId "terminal-1"}))
      (test/is (m/validate schema {:sessionId "session-1"
                                   :terminalId "terminal-1"
                                   :_meta {:traceId "t-1"}}))
      (test/is (m/validate schema {:sessionId "session-1"
                                   :terminalId "terminal-1"
                                   :notInSpec true})))

    (test/testing (str what " requires both ids")
      (test/is (not (m/validate schema {})))
      (test/is (not (m/validate schema {:sessionId "session-1"})))
      (test/is (not (m/validate schema {:terminalId "terminal-1"})))
      (test/is (not (m/validate schema {:sessionId nil
                                        :terminalId "terminal-1"})))
      (test/is (not (m/validate schema {:sessionId "session-1"
                                        :terminalId nil})))
      (test/is (not (m/validate schema {:sessionId 1
                                        :terminalId "terminal-1"})))
      (test/is (not (m/validate schema {:sessionId "session-1"
                                        :terminalId 1}))))

    (test/testing (str what " rejects non-map values")
      (doseq [value [nil "request" [:request] true 42]]
        (test/is (not (m/validate schema value))
                 (str "expected " (pr-str value) " to be rejected"))))))

(test/deftest session-terminal-request-constructors
  (doseq [[schema ctor what] session-terminal-requests]
    (test/testing (str what " fills the metadata slot")
      (test/is (= {:sessionId  "session-1"
                   :terminalId "terminal-1"
                   :_meta      nil}
                  (ctor "session-1" "terminal-1")))
      (test/is (= {:sessionId  "session-1"
                   :terminalId "terminal-1"
                   :_meta      {:traceId "t-1"}}
                  (ctor "session-1" "terminal-1"
                        :meta {:traceId "t-1"}))))

    (test/testing (str what " returns a value that satisfies the schema")
      (test/is (m/validate schema (ctor "session-1" "terminal-1"))))

    (test/testing (str what " rejects invalid arguments")
      (doseq [args [[nil "terminal-1"]
                    ["session-1" nil]
                    [1 "terminal-1"]
                    ["session-1" 1]
                    ["session-1" "terminal-1" :meta "meta"]]]
        (test/is (thrown? clojure.lang.ExceptionInfo (apply ctor args))
                 (str "expected " (pr-str args) " to be rejected"))))

    (test/testing (str what " requires the two ids")
      (test/is (thrown? clojure.lang.ArityException (ctor)))
      (test/is (thrown? clojure.lang.ArityException (ctor "session-1")))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->KillTerminalRequest nil "terminal-1"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid KillTerminalRequest" (ex-message e))))))


;; -------------------------------------------------------------------------
;; CreateTerminalRequest
;; -------------------------------------------------------------------------

(def ^:private env-variable {:name "PATH" :value "/bin"})

(test/deftest CreateTerminalRequest-schema
  (test/testing "requires a session id and command"
    (test/is (m/validate tool/CreateTerminalRequest
                        {:sessionId "session-1" :command "ls"}))
    (test/is (not (m/validate tool/CreateTerminalRequest {})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:command "ls"})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId nil :command "ls"})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1" :command nil})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId 1 :command "ls"})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1" :command 1}))))

  (test/testing "accepts every optional field"
    (test/is (m/validate tool/CreateTerminalRequest
                        {:sessionId       "session-1"
                         :command         "ls"
                         :args            ["-l" "--color"]
                         :env             [env-variable]
                         :cwd             "/tmp"
                         :outputByteLimit 1024
                         :_meta           {:traceId "t-1"}})))

  (test/testing "accepts explicit nulls for nullable optionals"
    (test/is (m/validate tool/CreateTerminalRequest
                        {:sessionId       "session-1"
                         :command         "ls"
                         :cwd             nil
                         :outputByteLimit nil
                         :_meta           nil})))

  (test/testing "rejects explicit nulls for non-nullable optionals"
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1" :command "ls" :args nil})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1" :command "ls" :env nil}))))

  (test/testing "rejects malformed optional fields"
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"
                               :command "ls"
                               :args ["-l" 1]})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"
                               :command "ls"
                               :env [{:name "PATH"}]})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"
                               :command "ls"
                               :env ["PATH=/bin"]})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"
                               :command "ls"
                               :cwd 1})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"
                               :command "ls"
                               :outputByteLimit -1})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"
                               :command "ls"
                               :outputByteLimit 1.5})))
    (test/is (not (m/validate tool/CreateTerminalRequest
                              {:sessionId "session-1"
                               :command "ls"
                               :_meta "meta"}))))

  (test/testing "accepts extra keys (map is open)"
    (test/is (m/validate tool/CreateTerminalRequest
                        {:sessionId "session-1"
                         :command "ls"
                         :notInSpec true})))

  (test/testing "rejects non-map values"
    (doseq [value [nil "ls" [:ls] true 42]]
      (test/is (not (m/validate tool/CreateTerminalRequest value))
               (str "expected " (pr-str value) " to be rejected")))))

(test/deftest ->CreateTerminalRequest
  (test/testing "fills nullable slots with nil and omits non-nullable ones"
    (test/is (= {:sessionId       "session-1"
                 :command         "ls"
                 :cwd             nil
                 :outputByteLimit nil
                 :_meta           nil}
                (tool/->CreateTerminalRequest "session-1" "ls")))
    (let [value (tool/->CreateTerminalRequest "session-1" "ls")]
      (test/is (not (contains? value :args)))
      (test/is (not (contains? value :env)))))

  (test/testing "accepts every optional field"
    (test/is (= {:sessionId       "session-1"
                 :command         "ls"
                 :args            ["-l"]
                 :env             [env-variable]
                 :cwd             "/tmp"
                 :outputByteLimit 1024
                 :_meta           {:traceId "t-1"}}
                (tool/->CreateTerminalRequest
                 "session-1" "ls"
                 :args ["-l"]
                 :env [env-variable]
                 :cwd "/tmp"
                 :outputByteLimit 1024
                 :meta {:traceId "t-1"}))))

  (test/testing "omits non-nullable optionals passed as nil"
    (let [value (tool/->CreateTerminalRequest
                 "session-1" "ls" :args nil :env nil)]
      (test/is (not (contains? value :args)))
      (test/is (not (contains? value :env)))))

  (test/testing "maps the :meta argument onto :_meta"
    (let [value (tool/->CreateTerminalRequest
                 "session-1" "ls" :meta {:traceId "t-1"})]
      (test/is (= {:traceId "t-1"} (:_meta value)))
      (test/is (not (contains? value :meta)))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate tool/CreateTerminalRequest
                        (tool/->CreateTerminalRequest "session-1" "ls")))
    (test/is (m/validate tool/CreateTerminalRequest
                        (tool/->CreateTerminalRequest
                         "session-1" "ls" :env [env-variable]))))

  (test/testing "rejects invalid arguments"
    (doseq [args [[nil "ls"]
                  ["session-1" nil]
                  ["session-1" "ls" :args ["-l" 1]]
                  ["session-1" "ls" :env [{:name "PATH"}]]
                  ["session-1" "ls" :cwd 1]
                  ["session-1" "ls" :outputByteLimit -1]
                  ["session-1" "ls" :meta "meta"]]]
      (test/is (thrown? clojure.lang.ExceptionInfo (apply tool/->CreateTerminalRequest args))
               (str "expected " (pr-str args) " to be rejected"))))


  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->CreateTerminalRequest nil "ls"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid CreateTerminalRequest" (ex-message e))))))


;; -------------------------------------------------------------------------
;; CreateTerminalResponse
;; -------------------------------------------------------------------------

(test/deftest CreateTerminalResponse-schema
  (test/testing "accepts a terminal id"
    (test/is (m/validate tool/CreateTerminalResponse
                        {:terminalId "terminal-1"}))
    (test/is (m/validate tool/CreateTerminalResponse
                        {:terminalId "terminal-1" :_meta {:traceId "t-1"}})))

  (test/testing "accepts extra keys (map is open)"
    (test/is (m/validate tool/CreateTerminalResponse
                        {:terminalId "terminal-1" :notInSpec true})))

  (test/testing "requires a valid terminal id"
    (test/is (not (m/validate tool/CreateTerminalResponse {})))
    (test/is (not (m/validate tool/CreateTerminalResponse
                              {:terminalId nil})))
    (test/is (not (m/validate tool/CreateTerminalResponse
                              {:terminalId 1}))))

  (test/testing "rejects non-map values"
    (doseq [value [nil "terminal-1" [:terminal-1] true 42]]
      (test/is (not (m/validate tool/CreateTerminalResponse value))
               (str "expected " (pr-str value) " to be rejected")))))

(test/deftest ->CreateTerminalResponse
  (test/testing "builds a response from a terminal id"
    (test/is (= {:terminalId "terminal-1" :_meta nil}
                (tool/->CreateTerminalResponse "terminal-1")))
    (test/is (= {:terminalId "terminal-1" :_meta {:traceId "t-1"}}
                (tool/->CreateTerminalResponse
                 "terminal-1" :meta {:traceId "t-1"}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate tool/CreateTerminalResponse
                        (tool/->CreateTerminalResponse "terminal-1"))))

  (test/testing "rejects invalid arguments"
    (doseq [args [[nil] [1] [:terminal-1] ["terminal-1" :meta "meta"]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (apply tool/->CreateTerminalResponse args))
               (str "expected " (pr-str args) " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->CreateTerminalResponse 42))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid CreateTerminalResponse" (ex-message e))))))


;; -------------------------------------------------------------------------
;; Responses that only carry metadata
;; -------------------------------------------------------------------------

(def ^:private empty-terminal-responses
  [[tool/KillTerminalResponse
    tool/->KillTerminalResponse
    "KillTerminalResponse"]
   [tool/ReleaseTerminalResponse
    tool/->ReleaseTerminalResponse
    "ReleaseTerminalResponse"]])

(test/deftest empty-terminal-response-schemas
  (doseq [[schema _ what] empty-terminal-responses]
    (test/testing (str what " accepts an empty map and metadata")
      (test/is (m/validate schema {}))
      (test/is (m/validate schema {:_meta {:traceId "t-1"}}))
      (test/is (m/validate schema {:_meta nil}))
      (test/is (m/validate schema {:notInSpec true})))

    (test/testing (str what " rejects malformed metadata")
      (test/is (not (m/validate schema {:_meta "meta"})))
      (test/is (not (m/validate schema {:_meta 42}))))

    (test/testing (str what " rejects non-map values")
      (doseq [value [nil "response" [:response] true 42]]
        (test/is (not (m/validate schema value))
                 (str "expected " (pr-str value) " to be rejected"))))))

(test/deftest empty-terminal-response-constructors
  (doseq [[schema ctor what] empty-terminal-responses]
    (test/testing (str what " fills the metadata slot")
      (test/is (= {:_meta nil} (ctor)))
      (test/is (= {:_meta {:traceId "t-1"}}
                  (ctor :meta {:traceId "t-1"}))))

    (test/testing (str what " returns a value that satisfies the schema")
      (test/is (m/validate schema (ctor)))
      (test/is (m/validate schema (ctor :meta {:traceId "t-1"}))))

    (test/testing (str what " rejects non-map metadata")
      (test/is (thrown? clojure.lang.ExceptionInfo (ctor :meta "meta")))
      (test/is (thrown? clojure.lang.ExceptionInfo (ctor :meta 42)))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->KillTerminalResponse :meta "meta"))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= {:_meta "meta"} (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid KillTerminalResponse" (ex-message e))))))


;; -------------------------------------------------------------------------
;; WaitForTerminalExitResponse
;; -------------------------------------------------------------------------

(test/deftest WaitForTerminalExitResponse-schema
  (test/testing "accepts an empty map"
    (test/is (m/validate tool/WaitForTerminalExitResponse {})))

  (test/testing "accepts exit codes, signals and metadata"
    (test/is (m/validate tool/WaitForTerminalExitResponse {:exitCode 0}))
    (test/is (m/validate tool/WaitForTerminalExitResponse
                        {:exitCode 137 :signal "SIGKILL"}))
    (test/is (m/validate tool/WaitForTerminalExitResponse
                        {:exitCode nil :signal nil :_meta nil}))
    (test/is (m/validate tool/WaitForTerminalExitResponse
                        {:_meta {:traceId "t-1"}})))

  (test/testing "rejects malformed values"
    (test/is (not (m/validate tool/WaitForTerminalExitResponse
                              {:exitCode -1})))
    (test/is (not (m/validate tool/WaitForTerminalExitResponse
                              {:exitCode 1.5})))
    (test/is (not (m/validate tool/WaitForTerminalExitResponse
                              {:exitCode "0"})))
    (test/is (not (m/validate tool/WaitForTerminalExitResponse
                              {:signal 9})))
    (test/is (not (m/validate tool/WaitForTerminalExitResponse
                              {:_meta "meta"}))))

  (test/testing "rejects non-map values"
    (doseq [value [nil "0" [0] true 42]]
      (test/is (not (m/validate tool/WaitForTerminalExitResponse value))
               (str "expected " (pr-str value) " to be rejected")))))

(test/deftest ->WaitForTerminalExitResponse
  (test/testing "fills every slot with nil when called without arguments"
    (test/is (= {:exitCode nil :signal nil :_meta nil}
                (tool/->WaitForTerminalExitResponse))))

  (test/testing "accepts exitCode, signal and meta keyword arguments"
    (test/is (= {:exitCode 0 :signal nil :_meta nil}
                (tool/->WaitForTerminalExitResponse :exitCode 0)))
    (test/is (= {:exitCode nil :signal "SIGTERM" :_meta nil}
                (tool/->WaitForTerminalExitResponse :signal "SIGTERM")))
    (test/is (= {:exitCode 143 :signal "SIGTERM" :_meta {:traceId "t-1"}}
                (tool/->WaitForTerminalExitResponse
                 :exitCode 143
                 :signal "SIGTERM"
                 :meta {:traceId "t-1"}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate tool/WaitForTerminalExitResponse
                        (tool/->WaitForTerminalExitResponse :exitCode 1))))

  (test/testing "rejects invalid exit codes"
    (doseq [exitCode [-1 -100 1.5 "0" :zero]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (tool/->WaitForTerminalExitResponse
                         :exitCode exitCode))
               (str "expected exitCode " (pr-str exitCode)
                    " to be rejected"))))

  (test/testing "rejects a non-string signal"
    (doseq [signal [9 :sig [:a] true]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (tool/->WaitForTerminalExitResponse
                         :signal signal))
               (str "expected signal " (pr-str signal) " to be rejected"))))

  (test/testing "rejects non-map metadata"
    (doseq [meta-value ["meta" 42 [:a]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (tool/->WaitForTerminalExitResponse
                         :meta meta-value))
               (str "expected meta " (pr-str meta-value)
                    " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(tool/->WaitForTerminalExitResponse :exitCode -1))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= {:exitCode -1 :signal nil :_meta nil}
                  (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid WaitForTerminalExitResponse"
                        (ex-message e))))))
