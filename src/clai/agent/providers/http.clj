(ns clai.agent.providers.http
  "HTTP plumbing shared by the provider clients (`ollama`, `openrouter`, ...).

  This namespace deliberately knows nothing about any provider's `Client`
  record; it only reads the `:provider`, `:host`/`:base-url`, `:api-key`, and
  `:timeout` keys of the map it is given, so provider namespaces can be loaded
  without a dependency cycle.

  It also carries the NDJSON helpers Ollama uses for its streaming endpoints.
  OpenRouter (OpenAI-style SSE) is used non-streaming here, so it does not need
  them."
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

(def ^:private provider-labels
  "Display names for the provider keywords, used in logs and error messages."
  {:ollama "Ollama" :openrouter "OpenRouter"})

(defn provider-label
  "Human-readable provider name for `client`, used in logs and errors.
  Falls back to `\"LLM\"` for a client that names no known provider."
  {:malli/schema [:-> :map :string]}
  [client]
  (get provider-labels (:provider client) "LLM"))

(defn api-url
  "Join the client's base URL with an API path, avoiding doubled slashes.

  The base URL is `:host` (the Ollama client) or `:base-url` (the OpenRouter
  client), whichever the client carries."
  {:malli/schema [:-> :map :string [:maybe :string]]}
  [client path]
  (let [base (str/replace (str (or (:base-url client) (:host client)))
                          #"/+$" "")
        rel  (if (str/starts-with? (str path) "/") (str path) (str "/" path))]
    (str base rel)))

(defn auth-headers
  "Build the request headers for a client, adding a bearer token when present.
  `extra` headers are merged over the `Content-Type` default."
  {:malli/schema [:-> :map [:maybe [:map-of :string :string]]
                  [:map-of :string :string]]}
  [client extra]
  (cond-> (merge {"Content-Type" "application/json"} extra)
    (some? (:api-key client))
    (assoc "Authorization" (str "Bearer " (:api-key client)))))

(defn request-body
  "Encode the request map as a JSON byte array. `nil` bodies are returned as
  nil so callers can issue bodyless requests."
  {:malli/schema [:-> [:maybe :map] [:maybe bytes?]]}
  [body]
  (when (some? body)
    (-> (json/encode body)
        (.getBytes "UTF-8"))))

(defn base-http-opts
  "Common clj-http options for every request."
  {:malli/schema [:-> :map :map]}
  [client]
  {:throw-exceptions  false
   :decompress-body   true
   :socket-timeout    (:timeout client)
   :conn-timeout      (:timeout client)})

(defn ok?
  "True when `status` is a 2xx HTTP status."
  {:malli/schema [:-> :int boolean?]}
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

(defn- response-error-message
  "Pull a human-readable message out of a failed response.

  Ollama returns `{\"error\": \"...\"}`; OpenRouter returns
  `{\"error\": {\"message\": \"...\"}}`. Anything else falls back to the raw
  body text."
  [parsed raw]
  (let [error (:error parsed)]
    (cond
      (map? error)    (or (:message error) (str error))
      (some? error)   (str error)
      (some? parsed)  (str parsed)
      (bytes? raw)    (String. ^bytes raw "UTF-8")
      :else           "API error")))

(defn throw-api-error
  "Raise an `ex-info` describing a failed response from `client`.
  The body is parsed as JSON when possible."
  {:malli/schema [:-> :map :keyword :string :int [:maybe :any]]}
  [client method url status body]
  (let [^bytes raw (util/->bytes body)
        parsed (when (and (bytes? raw) (pos? (alength raw)))
                 (try (json/parse-string (String. raw "UTF-8") true)
                      (catch Exception _ nil)))
        label   (provider-label client)
        message (response-error-message parsed raw)]
    (tel/log! {:level :error
               :id    ::api-error
               :data  {:method method
                       :url    url
                       :status status
                       :message message}}
              (str label " API " (name method) " " url " failed: "
                   status " " message))
    (throw (ex-info (str label " API " (name method) " " url " failed: "
                         status " " message)
                    {:provider (:provider client)
                     :method   method
                     :url      url
                     :status   status
                     :body     parsed}))))

(defn parse-response
  "Decode a successful response body into Clojure data. Empty bodies become nil."
  {:malli/schema [:-> :map [:maybe :any]]}
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
  {:malli/schema [:-> [:fn #(instance? InputStream %)] [:fn sequential?]]}
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
                :data  {:provider   (:provider client)
                        :method     method
                        :url        (api-url client path)
                        :status     (:status resp)
                        :elapsed-ms msecs}}
               (str (provider-label client) " " (name method) " " path))
     (when-not (ok? (:status resp))
       (throw-api-error client method (api-url client path)
                        (:status resp) (:body resp)))
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
                    :data  {:provider (:provider client)
                            :method   method
                            :url      (api-url client path)
                            :status   (:status resp)}}
                   (str (provider-label client) " stream "
                        (name method) " " path))
         (ndjson-seq (:body resp)))
       (throw-api-error client method (api-url client path)
                        (:status resp) (:body resp))))))

(defn streaming?
  "True when the request map asks for streaming. Ollama defaults to streaming
  when `:stream` is omitted, so only an explicit `false` disables it."
  {:malli/schema [:-> :map boolean?]}
  [req]
  (not= false (:stream req)))

(defn post
  "POST `req` to `path`, streaming the response when the request asks for it."
  [client path req]
  (if (streaming? req)
    (perform-stream client :post path req)
    (perform client :post path req)))
