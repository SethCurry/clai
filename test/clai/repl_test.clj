(ns clai.repl-test
  "Tests for the in-process REPL client in `clai.repl`.

  A stub `AgentHandler` is run as [[clai.repl/current-agent]] so no Ollama
  server or network access is involved: the tests exercise the channel
  plumbing, the session helpers, and update rendering."
  (:require [acp.agent :as acp]
            [acp.errors :as acp-errors]
            [clai.repl :as repl]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io StringWriter]))

;; ---------------------------------------------------------------------------
;; Stub agent
;; ---------------------------------------------------------------------------

(defrecord StubHandler []
  acp/AgentHandler
  (handle-initialize [_ _ {:keys [protocolVersion]}]
    {:protocolVersion (long (or protocolVersion acp/protocol-version-2))
     :info            {:name "stub" :version "0"}
     :capabilities    {:loadSession true}})

  (handle-session-new [_ _ {:keys [cwd]}]
    {:sessionId "stub-session" :cwd cwd})

  (handle-session-list [_ _ _]
    {:sessions [{:sessionId "older"  :cwd "/tmp"
                 :updatedAt "2024-01-01T00:00:00Z"}
                {:sessionId "newest" :cwd "/tmp"
                 :updatedAt "2024-02-01T00:00:00Z"}]})

  (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
    (let [text (get-in prompt [0 :text])]
      (case text
        "fail" (acp-errors/acp-error acp-errors/err-internal "boom")

        "tool" (do
                 (acp/send-session-update
                  conn sessionId
                  {:sessionUpdate "tool_call"
                   :toolCallId    "call-1"
                   :title         "reading AGENTS.md"})
                 (acp/send-session-update
                  conn sessionId
                  {:sessionUpdate "tool_call_update"
                   :toolCallId    "call-1"
                   :status        "completed"
                   :content       [{:type    "content"
                                    :content {:type "text" :text "contents"}}]})
                 {:stopReason "end_turn"})

        (do
          (acp/send-agent-message-chunk conn sessionId "echo: ")
          (acp/send-agent-message-chunk conn sessionId text)
          {:stopReason "end_turn"}))))

  (on-ext-request [_ _ method _params]
    (if (= "session/load" method)
      {}
      (acp-errors/acp-error acp-errors/err-method))))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)
(use-fixtures :each
  (fn [f]
    (try
      (with-redefs [repl/current-agent (->StubHandler)]
        (alter-var-root #'repl/current-session (constantly nil))
        (f))
      (finally
        (repl/disconnect!)
        (alter-var-root #'repl/current-session (constantly nil))))))

(defn- with-cli-out
  "Run `f`, capturing everything written to `*out*` and returning
  `[result output]`."
  [f]
  (let [out (StringWriter.)
        result (binding [*out* out] (f))]
    [result (str out)]))

;; ---------------------------------------------------------------------------
;; new!
;; ---------------------------------------------------------------------------

(deftest new!-creates-and-selects-a-session
  (let [session (repl/new! {:cwd "/tmp"})]
    (is (= "stub-session" (:sessionId session)))
    (is (= "/tmp" (:cwd session)))
    (is (= "stub-session" (repl/current-session-id)))
    (is (= session repl/current-session))
    (is (repl/connected?))))

(deftest new!-accepts-a-bare-cwd
  (is (= "/tmp" (:cwd (repl/new! "/tmp")))))

;; ---------------------------------------------------------------------------
;; prompt!
;; ---------------------------------------------------------------------------

(deftest prompt!-streams-the-agent-reply
  (repl/new!)
  (let [[result out] (with-cli-out #(repl/prompt! "ping"))]
    (is (= {:stopReason "end_turn"} result))
    (is (str/includes? out "clai> echo: ping"))))

(deftest prompt!-sends-content-blocks
  (repl/new!)
  (let [[result out] (with-cli-out
                      #(repl/prompt!
                        [{:type "text" :text "one"}
                         {:type "text" :text "two"}]))]
    (is (= {:stopReason "end_turn"} result))
    (is (str/includes? out "echo: one"))))

(deftest prompt!-renders-tool-calls
  (repl/new!)
  (let [[_ out] (with-cli-out #(repl/prompt! "tool"))]
    (is (str/includes? out "[tool] reading AGENTS.md"))
    (is (str/includes? out "[tool] call-1 completed"))
    (is (str/includes? out "contents"))))

(deftest prompt!-throws-on-an-agent-error
  (repl/new!)
  (let [ex (try
             (binding [*out* (StringWriter.)] (repl/prompt! "fail"))
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (str/includes? (ex-message ex) "boom"))
    (is (= acp-errors/err-internal (-> ex ex-data :acp/error :code)))))

(deftest prompt!-requires-a-session
  (is (thrown? clojure.lang.ExceptionInfo (repl/prompt! "ping"))))

;; ---------------------------------------------------------------------------
;; resume!
;; ---------------------------------------------------------------------------

(deftest resume!-loads-the-most-recent-session
  (let [session (repl/resume!)]
    (is (= "newest" (:sessionId session)))
    (is (= "newest" (repl/current-session-id)))
    (is (= "/tmp" (:cwd session)))))

(deftest resume!-accepts-an-explicit-session-id
  (is (= "older" (:sessionId (repl/resume! "older"))))
  (is (= "older" (repl/current-session-id))))

(deftest resume!-uses-the-selected-session
  (repl/new!)
  (is (= "stub-session" (:sessionId (repl/resume!)))))

;; ---------------------------------------------------------------------------
;; disconnect!
;; ---------------------------------------------------------------------------

(deftest disconnect!-closes-the-connection
  (repl/new!)
  (is (true? (repl/disconnect!)))
  (is (not (repl/connected?)))
  (is (nil? (repl/disconnect!))))
