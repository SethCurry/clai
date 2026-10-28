(ns clai.providers.ollama-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clai.providers.ollama :as ollama]
            [clai.test-support :refer [quiet-logs]]))

;; ---------------------------------------------------------------------------
;; Fixtures and helpers
;; ---------------------------------------------------------------------------

(def ^:dynamic *recorded-requests* (atom nil))
(def ^:dynamic *stub-response* (atom nil))

(defn- with-stubbed-http
  "Replace clj-http's `request` with a stub that records the options it was
  called with and returns the response stored in `*stub-response*`."
  [f]
  (binding [*recorded-requests* (atom [])
            *stub-response*     (atom {:status 200 :body nil})]
    (with-redefs [http/request (fn [opts]
                                 (swap! *recorded-requests* conj opts)
                                 @*stub-response*)]
      (f))))

(use-fixtures :once quiet-logs)
(use-fixtures :each with-stubbed-http)

(defn last-request []
  (peek @*recorded-requests*))

(defn ndjson-stream
  "Build an InputStream of newline-delimited JSON from a seq of maps."
  [events]
  (java.io.ByteArrayInputStream.
   (.getBytes (->> events
                   (map #(json/encode %))
                   (clojure.string/join "\n"))
              "UTF-8")))

(def sample-client
  (ollama/make-client {:host "http://ollama.test:11434"
                       :api-key "secret-key"
                       :timeout 12345}))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(deftest make-client-defaults
  (let [c (ollama/make-client)]
    (is (= "http://localhost:11434" (:host c)))
    (is (nil? (:api-key c)))
    (is (pos? (:timeout c)))))

(deftest make-client-options
  (let [c sample-client]
    (is (= "http://ollama.test:11434" (:host c)))
    (is (= "secret-key" (:api-key c)))
    (is (= 12345 (:timeout c)))))

(deftest make-client-is-a-record
  (is (instance? clai.providers.ollama.Client (ollama/make-client))))

;; ---------------------------------------------------------------------------
;; Request building (URL, headers, body)
;; ---------------------------------------------------------------------------

(deftest api-url-joins-host-and-path
  (is (= "http://ollama.test:11434/api/chat"
         (#'ollama/api-url sample-client "/api/chat")))
  (is (= "http://ollama.test:11434/api/chat"
         (#'ollama/api-url sample-client "api/chat")))
  ;; trailing slash on host is handled
  (is (= "http://ollama.test:11434/api/chat"
         (#'ollama/api-url (ollama/make-client {:host "http://ollama.test:11434/"})
                           "/api/chat"))))

(deftest auth-headers-add-bearer-when-key-present
  (is (= "Bearer secret-key"
         (get (#'ollama/auth-headers sample-client nil) "Authorization"))))

(deftest auth-headers-omit-bearer-when-no-key
  (is (nil? (get (#'ollama/auth-headers (ollama/make-client) nil)
                 "Authorization"))))

(deftest auth-headers-merges-extra
  (is (= "application/octet-stream"
         (get (#'ollama/auth-headers (ollama/make-client)
                                     {"Content-Type" "application/octet-stream"})
              "Content-Type"))))

(deftest request-body-encodes-json
  (is (= "{\"model\":\"gemma4\"}"
         (String. ^bytes (#'ollama/request-body {:model "gemma4"}) "UTF-8"))))

(deftest request-body-nil-for-nil
  (is (nil? (#'ollama/request-body nil))))

;; ---------------------------------------------------------------------------
;; Streaming decision
;; ---------------------------------------------------------------------------

(deftest streaming-defaults-true
  (is (true? (#'ollama/streaming? {})))
  (is (true? (#'ollama/streaming? {:stream true}))))

(deftest streaming-disabled-by-explicit-false
  (is (false? (#'ollama/streaming? {:stream false}))))

;; ---------------------------------------------------------------------------
;; NDJSON parsing
;; ---------------------------------------------------------------------------

(deftest ndjson-seq-parses-each-line
  (let [events [{:status "pulling" :completed 1}
                {:status "pulling" :completed 2}
                {:status "success"}]
        result (doall (#'ollama/ndjson-seq (ndjson-stream events)))]
    (is (= events result))))

(deftest ndjson-seq-skips-blank-lines
  (let [stream (java.io.ByteArrayInputStream.
                (.getBytes "{\"a\":1}\n\n{\"b\":2}\n" "UTF-8"))
        result (doall (#'ollama/ndjson-seq stream))]
    (is (= [{:a 1} {:b 2}] result))))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(deftest throw-api-error-parses-ollama-error-body
  (reset! *stub-response* {:status 404
                           :body (.getBytes "{\"error\":\"model not found\"}"
                                            "UTF-8")})
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"model not found"
       (#'ollama/throw-api-error :post "http://x/api/chat" 404
                                 (.getBytes "{\"error\":\"model not found\"}"
                                            "UTF-8")))))

(deftest throw-api-error-includes-status-in-ex-data
  (let [e (try (#'ollama/throw-api-error :get "http://x/api/tags" 500
                                         (.getBytes "{\"error\":\"boom\"}" "UTF-8"))
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= 500 (:status (ex-data e))))
    (is (= :get (:method (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Endpoint dispatch (stubbed HTTP)
;; ---------------------------------------------------------------------------

(deftest chat-non-streaming-posts-json-and-parses-body
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode
                             {:model "gemma4"
                              :message {:role "assistant" :content "hi"}
                              :done true})
                            "UTF-8")})
  (let [result (ollama/chat sample-client
                            {:model "gemma4"
                             :messages [{:role "user" :content "hi"}]
                             :stream false})
        req    (last-request)]
    (is (= :post (:request-method req)))
    (is (= "http://ollama.test:11434/api/chat" (:url req)))
    (is (= "Bearer secret-key" (get (:headers req) "Authorization")))
    (is (= "{\"model\":\"gemma4\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}"
           (String. ^bytes (:body req) "UTF-8")))
    (is (= "gemma4" (:model result)))
    (is (= "assistant" (-> result :message :role)))))

(deftest chat-streaming-returns-lazy-seq
  (reset! *stub-response*
          {:status 200
           :body (ndjson-stream [{:model "gemma4" :message {:role "assistant"
                                                            :content "h"}
                                  :done false}
                                 {:model "gemma4" :message {:role "assistant"
                                                            :content "i"}
                                  :done true}])})
  (let [events (doall (ollama/chat sample-client
                                   {:model "gemma4"
                                    :messages [{:role "user" :content "hi"}]}))]
    (is (= 2 (count events)))
    (is (every? #(= "gemma4" (:model %)) events))
    (is (= "i" (-> events second :message :content)))))

(deftest generate-non-streaming
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:model "gemma4" :response "x" :done true})
                            "UTF-8")})
  (let [result (ollama/generate sample-client
                                {:model "gemma4" :prompt "hi" :stream false})
        req    (last-request)]
    (is (= "http://ollama.test:11434/api/generate" (:url req)))
    (is (= "x" (:response result)))))

(deftest embed-never-streams
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:model "e" :embeddings [[0.1 0.2]]})
                            "UTF-8")})
  (let [result (ollama/embed sample-client {:model "e" :input "hi"})]
    (is (= [[0.1 0.2]] (:embeddings result)))))

(deftest list-models-issues-get
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:models [{:name "gemma4"}]}) "UTF-8")})
  (let [result (ollama/list-models sample-client)
        req    (last-request)]
    (is (= :get (:request-method req)))
    (is (= "http://ollama.test:11434/api/tags" (:url req)))
    (is (nil? (:body req)))
    (is (= ["gemma4"] (map :name (:models result))))))

(deftest list-running-models-issues-get
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:models []}) "UTF-8")})
  (is (= [] (:models (ollama/list-running-models sample-client))))
  (is (= "http://ollama.test:11434/api/ps" (:url (last-request)))))

(deftest show-model-posts-body
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:license "MIT"}) "UTF-8")})
  (let [result (ollama/show-model sample-client {:model "gemma4" :verbose true})]
    (is (= "MIT" (:license result)))
    (is (= "http://ollama.test:11434/api/show" (:url (last-request))))))

(deftest create-model-streams-status-events
  (reset! *stub-response*
          {:status 200
           :body (ndjson-stream [{:status "writing manifest"}
                                 {:status "success"}])})
  (let [events (doall (ollama/create-model sample-client
                                           {:model "mario" :from "gemma4"}))]
    (is (= ["writing manifest" "success"] (map :status events)))))

(deftest copy-model-posts-source-and-destination
  (reset! *stub-response* {:status 200 :body (.getBytes "{\"status\":\"success\"}" "UTF-8")})
  (let [result (ollama/copy-model sample-client
                                  {:source "a" :destination "b"})]
    (is (= "success" (:status result)))
    (is (= "http://ollama.test:11434/api/copy" (:url (last-request))))))

(deftest pull-model-non-streaming
  (reset! *stub-response* {:status 200 :body (.getBytes "{\"status\":\"success\"}" "UTF-8")})
  (let [result (ollama/pull-model sample-client {:model "gemma4" :stream false})]
    (is (= "success" (:status result)))))

(deftest push-model-streams-by-default
  (reset! *stub-response*
          {:status 200 :body (ndjson-stream [{:status "pushing"} {:status "success"}])})
  (let [events (doall (ollama/push-model sample-client {:model "u/m"}))]
    (is (= 2 (count events)))))

(deftest delete-model-uses-delete-method
  (reset! *stub-response* {:status 200 :body nil})
  (let [result (ollama/delete-model sample-client {:model "gemma4"})
        req    (last-request)]
    (is (= :delete (:request-method req)))
    (is (= "http://ollama.test:11434/api/delete" (:url req)))
    (is (= "{\"model\":\"gemma4\"}" (String. ^bytes (:body req) "UTF-8")))
    (is (nil? result))))

(deftest version-issues-get
  (reset! *stub-response* {:status 200
                           :body (.getBytes "{\"version\":\"0.12.6\"}" "UTF-8")})
  (is (= "0.12.6" (:version (ollama/version sample-client))))
  (is (= "http://ollama.test:11434/api/version" (:url (last-request)))))

(deftest balance-issues-get
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:included {}
                                          :purchased {:balance_usd 1.5}})
                            "UTF-8")})
  (is (= 1.5 (-> (ollama/balance sample-client) :purchased :balance_usd))))

(deftest cloud-usage-sends-query-params
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:range "24h" :totals {}}) "UTF-8")})
  (let [result (ollama/cloud-usage sample-client {:range "24h" :scope "team"})]
    (is (= "24h" (:range result)))
    (is (= {:range "24h" :scope "team"}
           (:query-params (last-request))))))

;; ---------------------------------------------------------------------------
;; Blob endpoints
;; ---------------------------------------------------------------------------

(deftest blob-exists-true-on-200
  (reset! *stub-response* {:status 200 :body nil})
  (is (true? (ollama/blob-exists? sample-client "sha256:abc")))
  (let [req (last-request)]
    (is (= :head (:request-method req)))
    (is (= "http://ollama.test:11434/api/blobs/sha256:abc" (:url req)))))

(deftest blob-exists-false-on-404
  (reset! *stub-response* {:status 404 :body nil})
  (is (false? (ollama/blob-exists? sample-client "sha256:missing"))))

(deftest create-blob-posts-octet-stream
  (reset! *stub-response* {:status 201 :body nil})
  (let [result (ollama/create-blob sample-client "sha256:abc" (.getBytes "hello" "UTF-8"))
        req    (last-request)]
    (is (true? result))
    (is (= :post (:request-method req)))
    (is (= "application/octet-stream"
           (get (:headers req) "Content-Type")))
    (is (= "hello" (String. ^bytes (:body req) "UTF-8")))))

;; ---------------------------------------------------------------------------
;; Default-client arity (omitting the client argument)
;; ---------------------------------------------------------------------------

(deftest functions-work-without-explicit-client
  (reset! *stub-response*
          {:status 200
           :body (.getBytes "{\"version\":\"0.1\"}" "UTF-8")})
  (is (= "0.1" (:version (ollama/version)))))

(deftest chat-without-explicit-client-uses-default
  (reset! *stub-response*
          {:status 200
           :body (.getBytes (json/encode {:done true :message {:content "ok"}})
                            "UTF-8")})
  (let [result (ollama/chat {:model "gemma4"
                             :messages [{:role "user" :content "hi"}]
                             :stream false})]
    (is (= "ok" (-> result :message :content)))
    (is (= "http://localhost:11434/api/chat" (:url (last-request))))))

;; ---------------------------------------------------------------------------
;; with-client macro
;; ---------------------------------------------------------------------------

(deftest with-client-binds-default
  (reset! *stub-response*
          {:status 200
           :body (.getBytes "{\"version\":\"9.9\"}" "UTF-8")})
  (ollama/with-client sample-client
    (ollama/version))
  (is (= "http://ollama.test:11434/api/version" (:url (last-request)))))

;; ---------------------------------------------------------------------------
;; Image encoding helper
;; ---------------------------------------------------------------------------

(deftest encode-image-from-bytes
  (is (= "aGVsbG8="
         (ollama/encode-image (.getBytes "hello" "UTF-8")))))

(deftest encode-image-from-file
  (let [tmp (java.io.File/createTempFile "clai" ".bin")]
    (try
      (spit tmp "hello")
      (is (= "aGVsbG8=" (ollama/encode-image tmp)))
      (is (= "aGVsbG8=" (ollama/encode-image (.getPath tmp))))
      (finally (.delete tmp)))))

(deftest encode-image-rejects-unsupported
  (is (thrown? clojure.lang.ExceptionInfo
               (ollama/encode-image (Object.)))))
