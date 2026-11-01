(ns clai.providers.ollama.http
  "HTTP plumbing shared by the Ollama endpoint namespaces.

  This namespace deliberately knows nothing about the public `Client` record;
  it only reads the `:host`, `:api-key`, and `:timeout` keys of the map it is
  given, so endpoint namespaces can be loaded without a dependency cycle."
  (:require [clai.log]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io BufferedReader InputStream]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn api-url
  "Join the client host with an API path, avoiding doubled slashes."
  [client path]
  (let [base (str/replace (:host client) #"/+$" "")
        rel  (if (str/starts-with? path "/") path (str "/" path))]
    (str base rel)))

(defn auth-headers
  "Build the request headers for a client, adding a bearer token when present."
  [client extra]
  (cond-> (merge {"Content-Type" "application/json"} extra)
    (some? (:api-key client))
    (assoc "Authorization" (str "Bearer " (:api-key client)))))

(defn request-body
  "Encode the request map as a JSON byte array. `nil` bodies are returned as
  nil so callers can issue bodyless requests."
  [body]
  (when (some? body)
    (-> (json/encode body)
        (.getBytes "UTF-8"))))

(defn base-http-opts
  "Common clj-http options for every request."
  [client]
  {:throw-exceptions  false
   :decompress-body   true
   :socket-timeout    (:timeout client)
   :conn-timeout      (:timeout client)})

(defn body->bytes
  "Coerce a clj-http response body (byte array, InputStream, String, or nil)
  to a byte array for error reporting."
  [body]
  (cond
    (nil? body)                nil
    (bytes? body)              body
    (string? body)             (.getBytes ^String body "UTF-8")
    (instance? InputStream body)
    (let [ba (with-open [in ^InputStream body
                         out (java.io.ByteArrayOutputStream.)]
               (io/copy in out)
               (.toByteArray out))]
      ba)
    :else nil))

(defn throw-api-error
  "Raise an `ex-info` describing a failed API response. The body is parsed as
  JSON when possible (Ollama returns `{\"error\": \"...\"}`)."
  [method url status body]
  (let [^bytes raw (body->bytes body)
        parsed (when (and (bytes? raw) (pos? (alength raw)))
                 (try (json/parse-string (String. raw "UTF-8") true)
                      (catch Exception _ nil)))
        message (or (and parsed (:error parsed))
                    (and parsed (str parsed))
                    (when (bytes? raw) (String. raw "UTF-8"))
                    "Ollama API error")]
    (tel/log! {:level :error
               :id    ::api-error
               :data  {:method method
                       :url    url
                       :status status
                       :message message}}
              (str "Ollama API " (name method) " " url " failed: "
                   status " " message))
    (throw (ex-info (str "Ollama API " (name method) " " url " failed: "
                         status " " message)
                    {:method method :url url :status status
                     :body parsed}))))

(defn parse-response
  "Decode a successful response body into Clojure data. Empty bodies become nil."
  [resp]
  (let [body (:body resp)]
    (cond
      (nil? body)    nil
      (bytes? body)
      (let [s (String. ^bytes body "UTF-8")]
        (when (not (str/blank? s))
          (json/parse-string s true)))
      (string? body)
      (when (not (str/blank? body))
        (json/parse-string body true))
      :else body)))

(defn ndjson-seq
  "Return a lazy sequence of parsed JSON objects read line by line from an
  InputStream of newline-delimited JSON. Blank lines are skipped. The stream
  is closed when the sequence is fully consumed."
  [^InputStream stream]
  (let [reader (BufferedReader. (io/reader stream))]
    (letfn [(next-events []
              (lazy-seq
                (if-let [line (.readLine reader)]
                  (if (str/blank? line)
                    (next-events)               ; skip keepalive/blank lines
                    (cons (json/parse-string line true)
                          (next-events)))
                  (.close reader))))]
      (next-events))))

;; ---------------------------------------------------------------------------
;; Low-level request execution
;; ---------------------------------------------------------------------------

(defn perform
  "Execute a non-streaming HTTP request and return the parsed JSON body, or
  throw on a non-2xx response."
  [client method path req]
  (let [url    (api-url client path)
        start  (System/nanoTime)
        opts   (-> (base-http-opts client)
                   (assoc :request-method method
                          :url url
                          :headers (auth-headers client nil)
                          :as :byte-array)
                   (cond-> (some? req)
                     (assoc :body (request-body req))))
        resp   (http/request opts)
        msecs  (quot (- (System/nanoTime) start) 1000000)]
    (tel/log! {:level :debug
               :id    ::http-request
               :data  {:method     method
                       :url        url
                       :status     (:status resp)
                       :elapsed-ms msecs}}
              (str "Ollama " (name method) " " path))
    (when-not (<= 200 (:status resp) 299)
      (throw-api-error method url (:status resp) (:body resp)))
    (parse-response resp)))

(defn perform-stream
  "Execute a streaming HTTP request and return a lazy sequence of parsed
  NDJSON event maps. Throws on a non-2xx response."
  [client method path req]
  (let [url    (api-url client path)
        opts   (-> (base-http-opts client)
                   (assoc :request-method method
                          :url url
                          :headers (auth-headers client nil)
                          :as :stream)
                   (cond-> (some? req)
                     (assoc :body (request-body req))))
        resp   (http/request opts)]
    (cond
      (<= 200 (:status resp) 299)
      (do
        (tel/log! {:level :debug
                   :id    ::http-stream-opened
                   :data  {:method method
                           :url    url
                           :status (:status resp)}}
                  (str "Ollama stream " (name method) " " path))
        (ndjson-seq (:body resp)))

      :else
      (throw-api-error method url (:status resp) (:body resp)))))

(defn streaming?
  "True when the request map asks for streaming. Ollama defaults to streaming
  when `:stream` is omitted, so only an explicit `false` disables it."
  [req]
  (not= false (:stream req)))
