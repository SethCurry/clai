(ns clai.acp-test
  "Tests for the agent-side ACP stdio connection.

  The connection is driven against in-memory pipes: we write client messages
  to a `PipedOutputStream` the agent reads from, and capture the agent's
  stdout from a `ByteArrayOutputStream`. Because the reader loop blocks on
  stdin, each test runs the agent on a background thread and stops it by
  closing the input pipe (which delivers EOF)."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clai.acp :as acp])
  (:import [java.io PipedInputStream PipedOutputStream ByteArrayInputStream
            ByteArrayOutputStream InputStream OutputStream]
           [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- pipe-input
  "Return `[input-stream output-stream]` where bytes written to the output
  side become available on the input side."
  []
  (let [pin  (PipedInputStream. 65536)
        pout (PipedOutputStream. pin)]
    [pin pout]))

(defn- write-line
  "Write one JSON-RPC message (map) as a single NDJSON line."
  [^OutputStream out msg]
  (doto out
    (.write (.getBytes (json/generate-string msg) "UTF-8"))
    (.write (int \newline))
    (.flush)))

(defn- read-all
  "Read and parse every newline-delimited JSON message the agent has written
  to `baos`. Returns a vector of parsed maps (keyword keys)."
  [^ByteArrayOutputStream baos]
  (let [s (.toString baos "UTF-8")]
    (->> (clojure.string/split s #"\n")
         (remove clojure.string/blank?)
         (mapv (fn [s] (json/parse-string-strict s true))))))

(defn- drain-until
  "Poll `baos` until `pred` holds over the parsed messages so far, then return
  them. Times out after `ms` and returns whatever was collected."
  [^ByteArrayOutputStream baos pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [msgs (read-all baos)]
        (cond
          (pred msgs) msgs
          (> (System/currentTimeMillis) deadline) msgs
          :else (do (Thread/sleep 5) (recur)))))))

(defn- run-agent
  "Start an agent with `handler` reading from `in` and writing to `out`.
  Returns the running Thread."
  [handler in out]
  (doto (Thread. #(acp/run handler {:in in :out out}))
    (.setDaemon true)
    (.start)))

;; ---------------------------------------------------------------------------
;; A tiny echo agent for integration tests
;; ---------------------------------------------------------------------------

(defn- echo-agent
  "An agent that initializes at protocol version 2, creates UUID sessions,
  and echoes prompt text back as an agent message before going idle."
  []
  (reify acp/AgentHandler
    (handle-initialize [_ _conn {:keys [protocolVersion]}]
      {:protocolVersion (min 2 (long protocolVersion))
       :info {:name "echo" :version "0.1.0"}
       :capabilities {}})
    (handle-session-new [_ _conn _params]
      {:sessionId (str (UUID/randomUUID))})
    (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
      (let [user-id   (str (UUID/randomUUID))
            agent-id  (str (UUID/randomUUID))
            text      (->> prompt
                           (filter #(= "text" (:type %)))
                           (mapcat :content)
                           (apply str))]
        (acp/send-session-update conn sessionId
          {:sessionUpdate "user_message"
           :messageId user-id
           :role "user"
           :content prompt})
        (acp/send-session-update conn sessionId
          {:sessionUpdate "agent_message"
           :messageId agent-id
           :role "agent"
           :content [{:type "text" :content text}]})
        (acp/send-session-update conn sessionId
          {:sessionUpdate "state_update" :state "idle"})
        {:messageId user-id}))
    (on-session-cancel [_ _conn _params] nil)
    (on-cancel-request [_ _conn _params] nil)
    (on-notification [_ _conn _method _params] nil)
    (on-ext-request [_ _conn _method _params]
      (acp/acp-error acp/err-method))))

(defn- init-request
  ([] (init-request 2))
  ([version]
   {:jsonrpc "2.0" :id 1 :method "initialize"
    :params {:protocolVersion version
             :info {:name "test-client" :version "0.0.1"}
             :capabilities {}}}))

;; ---------------------------------------------------------------------------
;; Initialization
;; ---------------------------------------------------------------------------

(deftest initialize-responds-with-negotiated-version
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        agent      (echo-agent)
        _          (run-agent agent pin baos)]
    (write-line pout (init-request 2))
    (let [msgs (drain-until baos #(some :result %) 2000)
          resp (some :result msgs)]
      (is (some? resp))
      (is (= 2 (:protocolVersion resp)))
      (is (= "echo" (-> resp :info :name))))
    (.close pout)
    (.join (Thread.) 0)))

(deftest initialize-rejects-unsupported-protocol-version
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (write-line pout (init-request 1))
    (let [msgs (drain-until baos #(some :error %) 2000)
          err  (some :error msgs)]
      (is (some? err))
      (is (= acp/err-protocol-ver (:code err)))
      (is (= {:requested 1 :supported [2]} (:data err))))
    (.close pout)))

(deftest default-handler-returns-method-not-found
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (write-line pout (init-request 2))
    (write-line pout {:jsonrpc "2.0" :id 2 :method "session/new"
                      :params {:cwd "/tmp"}})
    (let [msgs (drain-until baos
                            #(and (some :result %) (some :error %))
                            2000)
          by-id (into {} (map (juxt :id identity)) msgs)]
      (is (= 2 (-> (by-id 1) :result :protocolVersion)))
      (is (= acp/err-method (-> (by-id 2) :error :code))))
    (.close pout)))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(deftest session-new-and-prompt-produce-updates
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (echo-agent) pin baos)]
    (write-line pout (init-request 2))
    (write-line pout {:jsonrpc "2.0" :id 2 :method "session/new"
                      :params {:cwd "/tmp"}})
    ;; Wait for the session id.
    (let [init-msgs (drain-until baos
                                  #(some (fn [x] (-> x :result :sessionId)) %)
                                  2000)
          session-id (some #(-> % :result :sessionId) init-msgs)]
      (is (some? session-id))
      (write-line pout
                  {:jsonrpc "2.0" :id 3 :method "session/prompt"
                   :params {:sessionId session-id
                            :prompt [{:type "text" :content "hello"}]}})
      (let [msgs (drain-until
                  baos
                  #(some (fn [m]
                           (and (= "session/update" (:method m))
                                (= "idle" (-> m :params :update :state))))
                         %)
                  2000)
            prompt-resp (some #(and (= 3 (:id %)) (:result %)) msgs)
            updates     (eduction
                         (filter #(= "session/update" (:method %)))
                         (map :params) msgs)
            kinds       (set (map #(-> % :update :sessionUpdate) updates))
            agent-text  (->> updates
                             (filter #(= "agent_message"
                                         (-> % :update :sessionUpdate)))
                             first
                             :update :content first :content)]
        (is (= "hello" agent-text))
        (is (contains? kinds "user_message"))
        (is (contains? kinds "agent_message"))
        (is (contains? kinds "state_update"))
        (is (some? (:messageId prompt-resp)))))
    (.close pout)))

;; ---------------------------------------------------------------------------
;; Outbound requests & notifications
;; ---------------------------------------------------------------------------

(deftest agent-can-send-request-and-receive-response
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        handler    (reify acp/AgentHandler
                     (handle-initialize [_ _conn _params]
                       {:protocolVersion 2
                        :info {:name "perm" :version "0.1.0"}
                        :capabilities {}})
                     (handle-session-prompt [this conn _params]
                       ;; Ask the client for permission, await the answer.
                       (let [p (acp/request-permission
                                conn
                                {:sessionId "s1"
                                 :title "Run rm -rf?"
                                 :options [{:optionId "yes" :kind "allow"}
                                           {:optionId "no" :kind "deny"}]})]
                         (future
                           (try
                             (let [outcome (acp/await-request p 2000)]
                               (acp/send-notification
                                conn "session/update"
                                {:sessionId "s1"
                                 :update {:sessionUpdate "notice"
                                          :message (str "got: " (:outcome outcome))}}))
                             (catch Throwable _
                               (acp/send-notification
                                conn "session/update"
                                {:sessionId "s1"
                                 :update {:sessionUpdate "notice"
                                          :message "permission failed"}})))))
                       {:messageId "m1"})
                     (on-session-cancel [_ _conn _params] nil)
                     (on-cancel-request [_ _conn _params] nil)
                     (on-notification [_ _conn _m _p] nil)
                     (on-ext-request [_ _conn _m _p]
                       (acp/acp-error acp/err-method)))
        _ (run-agent handler pin baos)]
    (write-line pout (init-request 2))
    (write-line pout {:jsonrpc "2.0" :id 2 :method "session/prompt"
                      :params {:sessionId "s1" :prompt []}})
    ;; Wait for the agent's outbound request_permission.
    (let [perm-req (drain-until
                    baos
                    #(some (fn [m] (= "session/request_permission" (:method m))) %)
                    2000)
          req      (some #(when (= "session/request_permission" (:method %)) %) perm-req)
          req-id   (:id req)]
      (is (some? req-id))
      ;; Answer it.
      (write-line pout
                  {:jsonrpc "2.0" :id req-id
                   :result {:outcome {:optionId "yes" :kind "allow"}}})
      (let [msgs (drain-until
                  baos
                  #(some (fn [m]
                           (and (= "session/update" (:method m))
                                (= "notice" (-> m :params :update :sessionUpdate))))
                         %)
                  2000)]
        (is (some #(= "got: {:optionId \"yes\", :kind \"allow\"}"
                      (-> % :params :update :message))
                  msgs))))
    (.close pout)))

(deftest notifications-receive-no-response
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        seen       (atom nil)
        handler    (reify acp/AgentHandler
                     (handle-initialize [_ _conn _params]
                       {:protocolVersion 2
                        :info {:name "n" :version "0.1.0"}
                        :capabilities {}})
                     (on-session-cancel [_ _conn {:keys [sessionId]}]
                       (reset! seen sessionId))
                     (on-cancel-request [_ _conn _params] nil)
                     (on-notification [_ _conn method params]
                       (reset! seen [method params]))
                     (on-ext-request [_ _conn _m _p]
                       (acp/acp-error acp/err-method)))
        _ (run-agent handler pin baos)]
    (write-line pout (init-request 2))
    (write-line pout {:jsonrpc "2.0" :method "session/cancel"
                      :params {:sessionId "abc"}})
    (let [msgs (drain-until baos #(some :result %) 2000)]
      ;; Only the initialize result should be on the wire -- no notification ack.
      (is (= 1 (count msgs)))
      (is (= 2 (-> (first msgs) :result :protocolVersion))))
    ;; Give the notification handler a moment to run.
    (drain-until baos (fn [_] (= "abc" @seen)) 1000)
    (is (= "abc" @seen))
    (.close pout)))

;; ---------------------------------------------------------------------------
;; Batch & error framing
;; ---------------------------------------------------------------------------

(deftest batch-of-requests-returns-response-array
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (write-line pout
                [{:jsonrpc "2.0" :id 1 :method "initialize"
                  :params {:protocolVersion 2
                           :info {:name "c" :version "0"}
                           :capabilities {}}}
                 {:jsonrpc "2.0" :id 2 :method "session/new"
                  :params {:cwd "/tmp"}}])
    (let [msgs (drain-until baos #(some vector? %) 2000)
          batch (some #(when (vector? %) %) msgs)]
      (is (some? batch))
      (is (= 2 (count batch)))
      (is (= #{1 2} (set (map :id batch))))
      (is (= 2 (-> (batch 0) :result :protocolVersion)))
      (is (= acp/err-method (-> (batch 1) :error :code))))
    (.close pout)))

(deftest all-notification-batch-produces-no-output
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (write-line pout
                [{:jsonrpc "2.0" :method "session/cancel"
                  :params {:sessionId "x"}}])
    (Thread/sleep 200)
    (is (= [] (read-all baos)))
    (.close pout)))

(deftest empty-batch-returns-invalid-request
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (write-line pout [])
    (let [msgs (drain-until baos #(seq %) 1000)]
      (is (= 1 (count msgs)))
      (is (nil? (:id (first msgs))))
      (is (= acp/err-invalid (-> (first msgs) :error :code))))
    (.close pout)))

(deftest malformed-line-returns-parse-error
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (doto pout
      (.write (.getBytes "{not valid json" "UTF-8"))
      (.write (int \newline))
      (.flush))
    (let [msgs (drain-until baos #(seq %) 1000)]
      (is (= 1 (count msgs)))
      (is (nil? (:id (first msgs))))
      (is (= acp/err-parse (-> (first msgs) :error :code))))
    (.close pout)))

(deftest blank-lines-are-ignored
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (doto pout
      (.write (.getBytes "\n\n" "UTF-8"))
      (.write (.getBytes (json/generate-string (init-request 2)) "UTF-8"))
      (.write (int \newline))
      (.flush))
    (let [msgs (drain-until baos #(some :result %) 1000)]
      (is (= 1 (count msgs))))
    (.close pout)))

;; ---------------------------------------------------------------------------
;; Unit-level helpers
;; ---------------------------------------------------------------------------

(deftest acp-error-defaults-message-from-code
  (let [e (acp/acp-error acp/err-method)]
    (is (= acp/err-method (:code e)))
    (is (= "Method not found" (:message e)))))

(deftest clean-strips-nil-top-level-keys
  (is (= {:a 1 :b false} (#'acp/clean {:a 1 :b false :c nil})))
  (is (= {:nested {:x nil}} (#'acp/clean {:nested {:x nil} :drop nil}))))

(deftest classify-recognizes-each-message-type
  (is (= [:request 7 "foo" {}]
         (#'acp/classify {:jsonrpc "2.0" :id 7 :method "foo"})))
  (is (= [:notification "foo" nil]
         (#'acp/classify {:jsonrpc "2.0" :method "foo"})))
  (is (= [:response 7 {:result {}}]
         (#'acp/classify {:jsonrpc "2.0" :id 7 :result {}})))
  (is (= [:response 7 {:error {:code -1 :message "x"}}]
         (#'acp/classify {:jsonrpc "2.0" :id 7
                          :error {:code -1 :message "x"}})))
  (is (= [:invalid] (#'acp/classify {:jsonrpc "2.0"})))
  (is (= [:invalid] (#'acp/classify [1 2 3]))))