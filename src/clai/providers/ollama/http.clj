(ns clai.providers.ollama.http
  "HTTP plumbing shared by the Ollama endpoint namespaces.

  This namespace deliberately knows nothing about the public `Client` record;
  it only reads the `:host`, `:api-key`, and `:timeout` keys of the map it is
  given, so endpoint namespaces can be loaded without a dependency cycle."
  (:require [clai.log]
            [clai.util :as util]
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

(defn ok?
  "True when `status` is a 2xx HTTP status."
  [status]
  (<= 200 status 299))

;; ---------------------------------------------------------------------------
;; Request execution
;; ---------------------------------------------------------------------------

(defn request
  "Execute an HTTP request for `client` and return the raw clj-http response
  map.

  `opts` keys:

    :body          the already-encoded request body (omitted when nil)
    :headers       extra headers merged over the defaults
    :as            response coercion (default `:byte-array`)
    :query-params  query parameters to append to the URL"
  [client method path {:keys [body headers as query-params]
                       :or   {as :byte-array}}]
  (http/request
   (cond-> (assoc (base-http-opts client)
                  :request-method method
                  :url            (api-url client path)
                  :headers        (auth-headers client headers)
                  :as             as)
     (some? body)        (assoc :body body)
     (seq query-params)  (assoc :query-params query-params))))

(defn throw-api-error
  "Raise an `ex-info` describing a failed API response. The body is parsed as
  JSON when possible (Ollama returns `{\"error\": \"...\"}`)."
  [method url status body]
  (let [^bytes raw (util/->bytes body)
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
        (when-not (str/blank? s)
          (json/parse-string s true)))
      (string? body)
      (when-not (str/blank? body)
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

(defn perform
  "Execute a non-streaming HTTP request and return the parsed JSON body, or
  throw on a non-2xx response. `opts` is passed to [[request]]."
  ([client method path req]
   (perform client method path req nil))
  ([client method path req opts]
   (let [start (System/nanoTime)
         resp  (request client method path (assoc opts :body (request-body req)))
         msecs (quot (- (System/nanoTime) start) 1000000)]
     (tel/log! {:level :debug
                :id    ::http-request
                :data  {:method     method
                        :url        (api-url client path)
                        :status     (:status resp)
                        :elapsed-ms msecs}}
               (str "Ollama " (name method) " " path))
     (when-not (ok? (:status resp))
       (throw-api-error method (api-url client path) (:status resp) (:body resp)))
     (parse-response resp))))

(defn perform-stream
  "Execute a streaming HTTP request and return a lazy sequence of parsed
  NDJSON event maps. Throws on a non-2xx response. `opts` is passed to
  [[request]]."
  ([client method path req]
   (perform-stream client method path req nil))
  ([client method path req opts]
   (let [resp (request client method path
                       (assoc opts :as :stream :body (request-body req)))]
     (if (ok? (:status resp))
       (do
         (tel/log! {:level :debug
                    :id    ::http-stream-opened
                    :data  {:method method
                            :url    (api-url client path)
                            :status (:status resp)}}
                   (str "Ollama stream " (name method) " " path))
         (ndjson-seq (:body resp)))
       (throw-api-error method (api-url client path) (:status resp) (:body resp))))))

(defn streaming?
  "True when the request map asks for streaming. Ollama defaults to streaming
  when `:stream` is omitted, so only an explicit `false` disables it."
  [req]
  (not= false (:stream req)))

(defn post
  "POST `req` to `path`, streaming the response when the request asks for it."
  [client path req]
  (if (streaming? req)
    (perform-stream client :post path req)
    (perform client :post path req)))
