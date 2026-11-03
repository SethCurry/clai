(ns acp.acp-test
  "Tests for the agent-side ACP stdio connection.

  The connection is driven against in-memory pipes: we write client messages
  to a `PipedOutputStream` the agent reads from, and capture the agent's
  stdout from a `ByteArrayOutputStream`. Because the reader loop blocks on
  stdin, each test runs the agent on a background thread and stops it by
  closing the input pipe (which delivers EOF)."
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [acp.agent :as acp]
            [acp.jsonrpc :as jsonrpc]
            [acp.errors :as errors]
            [acp.schema.core :as schemas]
            [acp.test-support :as test-support]
            [clojure.string]
            [malli.core :as m])
  (:import [java.io PipedInputStream PipedOutputStream
            ByteArrayOutputStream OutputStream]
           [java.util UUID]))

(use-fixtures :each test-support/join-agent-threads)

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
  (test-support/track-thread!
   (doto (Thread. #(acp/run handler {:in in :out out}))
     (.setDaemon true)
     (.start))))

;; ---------------------------------------------------------------------------
;; A tiny echo agent for integration tests
;; ---------------------------------------------------------------------------

(defn- echo-agent
  "An agent that initializes at protocol version 2, creates UUID sessions,
  and echoes prompt text back as an agent message before finishing the turn."
  []
  (reify acp/AgentHandler
    (handle-initialize [_ _conn {:keys [protocolVersion]}]
      {:protocolVersion (min 2 (long protocolVersion))
       :info {:name "echo" :version "0.1.0"}
       :capabilities {}})
    (handle-session-new [_ _conn _params]
      {:sessionId (str (UUID/randomUUID))})
    (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
      (let [text (->> prompt
                      (filter #(= "text" (:type %)))
                      (map :text)
                      (apply str))]
        (acp/send-session-update conn sessionId
          {:sessionUpdate "user_message_chunk"
           :content {:type "text" :text text}})
        (acp/send-session-update conn sessionId
          {:sessionUpdate "agent_message_chunk"
           :content {:type "text" :text text}})
        {:stopReason "end_turn"}))
    (on-session-cancel [_ _conn _params] nil)
    (on-cancel-request [_ _conn _params] nil)
    (on-notification [_ _conn _method _params] nil)
    (on-ext-request [_ _conn _method _params]
      (errors/acp-error errors/err-method))))

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
      (is (= errors/err-protocol-ver (:code err)))
      (is (= {:requested 1 :supported [2]} (:data err))))
    (.close pout)))

(deftest default-handler-returns-method-not-found
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        _          (run-agent (acp/default-handler) pin baos)]
    (write-line pout (init-request 2))
    (write-line pout {:jsonrpc "2.0" :id 2 :method "session/new"
                      :params {:cwd "/tmp" :mcpServers []}})
    (let [msgs (drain-until baos
                            #(and (some :result %) (some :error %))
                            2000)
          by-id (into {} (map (juxt :id identity)) msgs)]
      (is (= 2 (-> (by-id 1) :result :protocolVersion)))
      (is (= errors/err-method (-> (by-id 2) :error :code))))
    (.close pout)))

;; ---------------------------------------------------------------------------
;; Authentication
;; ---------------------------------------------------------------------------

(deftest authenticate-and-logout-use-current-method-names
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        seen       (atom [])
        handler    (reify acp/AgentHandler
                     (handle-initialize [_ _conn _params]
                       {:protocolVersion 2
                        :info {:name "auth" :version "0.1.0"}
                        :capabilities {}})
                     (handle-authenticate [_ _conn params]
                       (swap! seen conj [:authenticate params])
                       {})
                     (handle-logout [_ _conn params]
                       (swap! seen conj [:logout params])
                       {})
                     (on-session-cancel [_ _conn _params] nil)
                     (on-cancel-request [_ _conn _params] nil)
                     (on-notification [_ _conn _method _params] nil)
                     (on-ext-request [_ _conn _method _params]
                       (errors/acp-error errors/err-method)))
        _          (run-agent handler pin baos)]
    (write-line pout (init-request 2))
    (write-line pout {:jsonrpc "2.0" :id 2 :method "authenticate"
                      :params {:methodId "method-1"}})
    (write-line pout {:jsonrpc "2.0" :id 3 :method "logout"
                      :params {}})
    ;; `authenticate` requires a methodId, so this must fail param validation
    ;; before reaching the handler.
    (write-line pout {:jsonrpc "2.0" :id 4 :method "authenticate"
                      :params {}})
    (let [msgs (drain-until baos
                            #(every? (fn [id]
                                       (some (fn [m] (= id (:id m))) %))
                                     [2 3 4])
                            2000)
          by-id (into {} (map (juxt :id identity)) msgs)]
      (is (= {} (-> (get by-id 2) :result)))
      (is (= {} (-> (get by-id 3) :result)))
      (is (= errors/err-params (-> (get by-id 4) :error :code)))
      (is (= #{[:authenticate {:methodId "method-1"}] [:logout {}]}
             (set @seen))))
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
                      :params {:cwd "/tmp" :mcpServers []}})
    ;; Wait for the session id.
    (let [init-msgs (drain-until baos
                                  #(some (fn [x] (-> x :result :sessionId)) %)
                                  2000)
          session-id (some #(-> % :result :sessionId) init-msgs)]
      (is (some? session-id))
      (write-line pout
                  {:jsonrpc "2.0" :id 3 :method "session/prompt"
                   :params {:sessionId session-id
                            :prompt [{:type "text" :text "hello"}]}})
      (let [msgs (drain-until
                  baos
                  (fn [ms]
                    (some #(and (= 3 (:id %)) (:result %)) ms))
                  2000)
            prompt-resp (some #(and (= 3 (:id %)) (:result %)) msgs)
            updates     (eduction
                         (filter #(= "session/update" (:method %)))
                         (map :params) msgs)
            kinds       (set (map #(-> % :update :sessionUpdate) updates))
            agent-text  (->> updates
                             (filter #(= "agent_message_chunk"
                                         (-> % :update :sessionUpdate)))
                             first
                             :update :content :text)]
        (is (= "hello" agent-text))
        (is (contains? kinds "user_message_chunk"))
        (is (contains? kinds "agent_message_chunk"))
        (is (= "end_turn" (:stopReason prompt-resp)))))
    (.close pout)))

;; ---------------------------------------------------------------------------
;; Outbound requests & notifications
;; ---------------------------------------------------------------------------

(deftest send-agent-message-chunk-emits-a-valid-content-block
  (let [baos (ByteArrayOutputStream.)
        conn (acp/make-connection (acp/default-handler)
                                  {:in  (java.io.StringReader. "")
                                   :out baos})]
    (try
      (acp/send-agent-message-chunk conn "session-1" "hello there")
      (let [messages (read-all baos)
            update   (-> messages first :params :update)]
        (is (= 1 (count messages)))
        (is (= "session/update" (-> messages first :method)))
        (is (= "session-1" (-> messages first :params :sessionId)))
        (is (= "agent_message_chunk" (:sessionUpdate update)))
        ;; `ContentBlock` is a union discriminated by `:type`; without the tag
        ;; the chunk is not a valid block (and `->SessionUpdate` would throw).
        (is (= "text" (-> update :content :type)))
        (is (= "hello there" (-> update :content :text)))
        (is (m/validate schemas/ContentBlock (:content update)))
        (is (m/validate schemas/SessionUpdate update)))
      (finally
        (acp/shutdown conn 1)))))

(deftest agent-can-send-request-and-receive-response
  (let [[pin pout] (pipe-input)
        baos       (ByteArrayOutputStream.)
        handler    (reify acp/AgentHandler
                     (handle-initialize [_ _conn _params]
                       {:protocolVersion 2
                        :info {:name "perm" :version "0.1.0"}
                        :capabilities {}})
                     (handle-session-prompt [_ conn _params]
                       ;; Ask the client for permission, await the answer.
                       (let [p (acp/request-permission
                                conn
                                {:sessionId "s1"
                                 :toolCall {:toolCallId "tc1"
                                            :title "Run rm -rf?"}
                                 :options [{:optionId "yes"
                                            :name "Allow"
                                            :kind "allow_once"}
                                           {:optionId "no"
                                            :name "Deny"
                                            :kind "reject_once"}]})]
                         (future
                           (try
                             (let [outcome (jsonrpc/await-request p 2000)]
                               (jsonrpc/send-notification
                                conn "session/update"
                                {:sessionId "s1"
                                 :update {:sessionUpdate "notice"
                                          :severity "info"
                                          :title (str "got: "
                                                      (-> outcome :outcome :optionId))}}))
                             (catch Throwable _
                               (jsonrpc/send-notification
                                conn "session/update"
                                {:sessionId "s1"
                                 :update {:sessionUpdate "notice"
                                          :severity "error"
                                          :title "permission failed"}})))))
                       {:stopReason "end_turn"})
                     (on-session-cancel [_ _conn _params] nil)
                     (on-cancel-request [_ _conn _params] nil)
                     (on-notification [_ _conn _m _p] nil)
                     (on-ext-request [_ _conn _m _p]
                       (errors/acp-error errors/err-method)))
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
                   :result {:outcome {:outcome "selected" :optionId "yes"}}})
      (let [msgs (drain-until
                  baos
                  #(some (fn [m]
                           (and (= "session/update" (:method m))
                                (= "notice" (-> m :params :update :sessionUpdate))))
                         %)
                  2000)]
        (is (some #(= "got: yes" (-> % :params :update :title))
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
                       (errors/acp-error errors/err-method)))
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
                  :params {:cwd "/tmp" :mcpServers []}}])
    (let [msgs (drain-until baos #(some vector? %) 2000)
          batch (some #(when (vector? %) %) msgs)]
      (is (some? batch))
      (is (= 2 (count batch)))
      (is (= #{1 2} (set (map :id batch))))
      (is (= 2 (-> (batch 0) :result :protocolVersion)))
      (is (= errors/err-method (-> (batch 1) :error :code))))
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
      (is (= errors/err-invalid (-> (first msgs) :error :code))))
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
      (is (= errors/err-parse (-> (first msgs) :error :code))))
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
  (let [e (errors/acp-error errors/err-method)]
    (is (= errors/err-method (:code e)))
    (is (= "Method not found" (:message e)))))

(deftest clean-strips-nil-top-level-keys
  (is (= {:a 1 :b false} (#'jsonrpc/clean {:a 1 :b false :c nil})))
  (is (= {:nested {:x nil}} (#'jsonrpc/clean {:nested {:x nil} :drop nil}))))

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
