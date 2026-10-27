(ns clai.providers.ollama
  "A Clojure client for the Ollama HTTP API.

  The Ollama API speaks JSON with snake_case keys; this client preserves
  those keys verbatim (for both requests and responses) so that the shape
  matches the official documentation at
  https://docs.ollama.com/api/introduction exactly.

  Two base URLs are supported:

    * a local Ollama server, by default `http://localhost:11434`
    * Ollama's hosted cloud, `https://ollama.com`

  Cloud requests require an API key, supplied via the `:api-key` option to
  `make-client` (or the `OLLAMA_API_KEY` environment variable).

  Streaming endpoints (`/api/generate`, `/api/chat`, `/api/create`,
  `/api/pull`, `/api/push`) default to streaming. When streaming, the client
  returns a lazy sequence of parsed event maps (one per NDJSON line); consume
  the sequence fully to release the underlying HTTP connection. Pass
  `:stream false` in the request to receive a single response map instead.

  Example:

      (require '[clai.core :as clai])

      (def c (clai/make-client))                 ;; localhost:11434
      (clai/chat c {:model \"gemma4\"
                    :messages [{:role \"user\"
                                :content \"Why is the sky blue?\"}]
                    :stream false})
      ;; => {:model \"gemma4\" :message {:role \"assistant\" :content \"...\"} ...}
  "
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URL]
           [java.util Base64]
           [java.io InputStream BufferedReader]))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:private default-host "http://localhost:11434")

(def ^:private default-timeout-ms 600000) ; 10 minutes; models can be slow.

(defrecord Client [host api-key timeout])

(def ^:no-doc
  ^:dynamic *client*
  (map->Client {:host    default-host
                :api-key nil
                :timeout default-timeout-ms}))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defn make-client
  "Create an Ollama client.

  Options:

    :host      Base URL of the Ollama server.
               Defaults to `http://localhost:11434` (or the `OLLAMA_HOST`
               environment variable when set).
    :api-key   Bearer token for Ollama's cloud API. Defaults to the
               `OLLAMA_API_KEY` environment variable.
    :timeout   Request timeout in milliseconds. Defaults to 600000 (10 min).

  Returns a `clai.core.Client` record. With no arguments the default local
  client is returned."
  ([] (make-client nil))
  ([{:keys [host api-key timeout]
     :or {host     (or (System/getenv "OLLAMA_HOST") default-host)
          api-key  (System/getenv "OLLAMA_API_KEY")
          timeout  default-timeout-ms}}]
   (map->Client {:host host :api-key api-key :timeout timeout})))

(defn- resolve-client
  "Accept either a Client or a request map as the first argument. Returns a
  vector of `[client request-map]` so public functions can be called as
  either `(f client req)` or `(f req)` (using the default client)."
  [client-or-req req-or-nil]
  (if (nil? req-or-nil)
    [*client* (or client-or-req {})]
    [client-or-req (or req-or-nil {})]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- api-url
  "Join the client host with an API path, avoiding doubled slashes."
  [^Client client path]
  (let [base (str/replace (:host client) #"/+$" "")
        rel  (if (str/starts-with? path "/") path (str "/" path))]
    (str base rel)))

(defn- auth-headers
  "Build the request headers for a client, adding a bearer token when present."
  [^Client client extra]
  (cond-> (merge {"Content-Type" "application/json"} extra)
    (some? (:api-key client))
    (assoc "Authorization" (str "Bearer " (:api-key client)))))

(defn- request-body
  "Encode the request map as a JSON byte array. `nil` bodies are returned as
  nil so callers can issue bodyless requests."
  [body]
  (when (some? body)
    (-> (json/encode body)
        (.getBytes "UTF-8"))))

(defn- base-http-opts
  "Common clj-http options for every request."
  [^Client client]
  {:throw-exceptions  false
   :decompress-body   true
   :socket-timeout    (:timeout client)
   :conn-timeout      (:timeout client)})

(defn- body->bytes
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

(defn- throw-api-error
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
                    (str "Ollama API error"))]
    (throw (ex-info (str "Ollama API " (name method) " " url " failed: "
                         status " " message)
                    {:method method :url url :status status
                     :body parsed}))))

(defn- parse-response
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

(defn- ndjson-seq
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

(defn- perform
  "Execute a non-streaming HTTP request and return the parsed JSON body, or
  throw on a non-2xx response."
  [^Client client method path req]
  (let [url    (api-url client path)
        opts   (-> (base-http-opts client)
                   (assoc :request-method method
                          :url url
                          :headers (auth-headers client nil)
                          :as :byte-array)
                   (cond-> (some? req)
                     (assoc :body (request-body req))))
        resp   (http/request opts)]
    (when-not (<= 200 (:status resp) 299)
      (throw-api-error method url (:status resp) (:body resp)))
    (parse-response resp)))

(defn- perform-stream
  "Execute a streaming HTTP request and return a lazy sequence of parsed
  NDJSON event maps. Throws on a non-2xx response."
  [^Client client method path req]
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
      (ndjson-seq (:body resp))

      :else
      (throw-api-error method url (:status resp) (:body resp)))))

(defn- streaming?
  "True when the request map asks for streaming. Ollama defaults to streaming
  when `:stream` is omitted, so only an explicit `false` disables it."
  [req]
  (not= false (:stream req)))

;; ---------------------------------------------------------------------------
;; Image helpers
;; ---------------------------------------------------------------------------

(defn encode-image
  "Base64-encode an image for inclusion in an `:images` array.

  Accepts:

    * a `java.io.File` or path string
    * a byte array
    * a `java.io.InputStream`
    * a URL string/`java.net.URL` (the contents are fetched and encoded)

  Returns a base64 string ready to send to Ollama."
  [src]
  (cond
    (bytes? src)
    (.encodeToString (Base64/getEncoder) ^bytes src)

    (or (instance? InputStream src)
        (instance? java.io.File src)
        (instance? URL src)
        (string? src))
    (let [^bytes ba (with-open [in (io/input-stream src)
                                out (java.io.ByteArrayOutputStream.)]
                      (io/copy in out)
                      (.toByteArray out))]
      (.encodeToString (Base64/getEncoder) ba))

    :else
    (throw (ex-info "Unsupported image source for encode-image"
                    {:src (type src)}))))

;; ---------------------------------------------------------------------------
;; Generation endpoints
;; ---------------------------------------------------------------------------

(defn generate
  "Generate a response for a prompt. Calls `POST /api/generate`.

  Required: `:model`. Common options: `:prompt`, `:system`, `:images`,
  `:format`, `:options`, `:think`, `:keep_alive`, `:raw`, `:suffix`,
  `:logprobs`, `:top_logprobs`.

  Streaming is the default: a lazy sequence of event maps is returned.
  Pass `:stream false` to receive a single response map."
  ([req] (let [[c r] (resolve-client req nil)] (generate c r)))
  ([client req]
   (if (streaming? req)
     (perform-stream client :post "/api/generate" req)
     (perform client :post "/api/generate" req))))

(defn chat
  "Generate the next chat message. Calls `POST /api/chat`.

  Required: `:model`, `:messages` (a vector of `{:role :content}` maps).
  Common options: `:tools`, `:format`, `:options`, `:think`, `:keep_alive`,
  `:logprobs`, `:top_logprobs`.

  Streaming is the default: a lazy sequence of event maps is returned.
  Pass `:stream false` to receive a single response map."
  ([req] (let [[c r] (resolve-client req nil)] (chat c r)))
  ([client req]
   (if (streaming? req)
     (perform-stream client :post "/api/chat" req)
     (perform client :post "/api/chat" req))))

(defn embed
  "Generate vector embeddings for input text. Calls `POST /api/embed`.

  Required: `:model`, `:input` (a string or vector of strings). Options:
  `:truncate`, `:dimensions`, `:keep_alive`, `:options`.

  Always returns a single response map (this endpoint does not stream)."
  ([req] (let [[c r] (resolve-client req nil)] (embed c r)))
  ([client req]
   (perform client :post "/api/embed" req)))

(defn systemone
  "Answer choice, yes/no, and scoring questions with a System One model.
  Calls `POST /v1/systemone`.

  Required: `:model`, `:state`, `:questions`. Option: `:keep_alive`,
  `:images`.

  Always returns a single response map (this endpoint does not stream)."
  ([req] (let [[c r] (resolve-client req nil)] (systemone c r)))
  ([client req]
   (perform client :post "/v1/systemone" req)))

;; ---------------------------------------------------------------------------
;; Model management endpoints
;; ---------------------------------------------------------------------------

(defn list-models
  "Fetch the list of locally available models. Calls `GET /api/tags`.
  Returns a map with a `:models` vector."
  ([ ] (list-models *client*))
  ([client] (perform client :get "/api/tags" nil)))

(defn list-running-models
  "Fetch the list of models currently loaded into memory. Calls `GET /api/ps`.
  Returns a map with a `:models` vector."
  ([ ] (list-running-models *client*))
  ([client] (perform client :get "/api/ps" nil)))

(defn show-model
  "Show details for a model. Calls `POST /api/show`.

  Required: `:model`. Option: `:verbose`."
  ([req] (let [[c r] (resolve-client req nil)] (show-model c r)))
  ([client req]
   (perform client :post "/api/show" req)))

(defn create-model
  "Create a model. Calls `POST /api/create`.

  Required: `:model`. Options: `:from`, `:files`, `:template`, `:renderer`,
  `:parser`, `:system`, `:parameters`, `:messages`, `:license`, `:quantize`,
  `:capabilities`, `:requires`.

  Streaming is the default: a lazy sequence of status-event maps is
  returned. Pass `:stream false` to receive the final status map."
  ([req] (let [[c r] (resolve-client req nil)] (create-model c r)))
  ([client req]
   (if (streaming? req)
     (perform-stream client :post "/api/create" req)
     (perform client :post "/api/create" req))))

(defn copy-model
  "Copy a model to a new name. Calls `POST /api/copy`.

  Required: `:source`, `:destination`."
  ([req] (let [[c r] (resolve-client req nil)] (copy-model c r)))
  ([client req]
   (perform client :post "/api/copy" req)))

(defn pull-model
  "Download a model from a registry. Calls `POST /api/pull`.

  Required: `:model`. Option: `:insecure`.

  Streaming is the default: a lazy sequence of progress-event maps is
  returned. Pass `:stream false` to receive the final status map."
  ([req] (let [[c r] (resolve-client req nil)] (pull-model c r)))
  ([client req]
   (if (streaming? req)
     (perform-stream client :post "/api/pull" req)
     (perform client :post "/api/pull" req))))

(defn push-model
  "Publish a model to a registry. Calls `POST /api/push`.

  Required: `:model`. Option: `:insecure`.

  Streaming is the default: a lazy sequence of progress-event maps is
  returned. Pass `:stream false` to receive the final status map."
  ([req] (let [[c r] (resolve-client req nil)] (push-model c r)))
  ([client req]
   (if (streaming? req)
     (perform-stream client :post "/api/push" req)
     (perform client :post "/api/push" req))))

(defn delete-model
  "Delete a model. Calls `DELETE /api/delete`.

  Required: `:model`."
  ([req] (let [[c r] (resolve-client req nil)] (delete-model c r)))
  ([client req]
   (perform client :delete "/api/delete" req)))

;; ---------------------------------------------------------------------------
;; Blob endpoints
;; ---------------------------------------------------------------------------

(defn blob-exists?
  "Check whether a blob (model weights file) is already present. Calls
  `HEAD /api/blobs/{digest}`. Returns `true` when the blob exists (HTTP 200)
  and `false` otherwise (HTTP 404). Other errors are raised."
  ([digest] (blob-exists? *client* digest))
  ([client digest]
   (let [url   (api-url client (str "/api/blobs/" digest))
         opts  (-> (base-http-opts client)
                   (assoc :request-method :head
                          :url url
                          :headers (auth-headers client nil)
                          :as :byte-array))
         resp  (http/request opts)]
     (cond
       (= (:status resp) 200) true
       (= (:status resp) 404) false
       :else (throw-api-error :head url (:status resp) (:body resp))))))

(defn create-blob
  "Upload raw bytes as a blob. Calls `POST /api/blobs/{digest}`.

  `content` may be a byte array, `java.io.File`, path string, or
  `java.io.InputStream`. Returns `true` when the blob was created (HTTP 201)
  and `true` as well when it already existed (HTTP 200)."
  ([digest content] (create-blob *client* digest content))
  ([client digest content]
   (let [url   (api-url client (str "/api/blobs/" digest))
         bytes (cond
                 (bytes? content)        content
                 (instance? InputStream content)
                 (with-open [in ^InputStream content
                             out (java.io.ByteArrayOutputStream.)]
                   (io/copy in out)
                   (.toByteArray out))
                 (or (instance? java.io.File content)
                     (string? content))
                 (with-open [in (io/input-stream content)
                             out (java.io.ByteArrayOutputStream.)]
                   (io/copy in out)
                   (.toByteArray out))
                 :else (throw (ex-info "Unsupported blob content"
                                       {:content (type content)})))
         opts  (-> (base-http-opts client)
                   (assoc :request-method :post
                          :url url
                          :headers (auth-headers client
                                                   {"Content-Type"
                                                    "application/octet-stream"})
                          :body bytes
                          :as :byte-array))
         resp  (http/request opts)]
     (when-not (<= 200 (:status resp) 299)
       (throw-api-error :post url (:status resp) (:body resp)))
     true)))

;; ---------------------------------------------------------------------------
;; Server / cloud metadata endpoints
;; ---------------------------------------------------------------------------

(defn version
  "Retrieve the Ollama server version. Calls `GET /api/version`.
  Returns a map such as `{:version \"0.12.6\"}`."
  ([ ] (version *client*))
  ([client] (perform client :get "/api/version" nil)))

(defn cloud-usage
  "Retrieve cloud usage statistics. Calls `GET /api/usage` (cloud only).

  Query options: `:range` (`24h`, `7d`, `30d`), `:scope` (`self`, `team`),
  `:group_by` (`model`). These are sent as query parameters."
  ([opts] (let [[c r] (resolve-client opts nil)] (cloud-usage c r)))
  ([client opts]
   (let [url   (api-url client "/api/usage")
         qopts (into {} (filter val opts))
         opts* (-> (base-http-opts client)
                   (assoc :request-method :get
                          :url url
                          :query-params qopts
                          :headers (auth-headers client nil)
                          :as :byte-array))
         resp  (http/request opts*)]
     (when-not (<= 200 (:status resp) 299)
       (throw-api-error :get url (:status resp) (:body resp)))
     (parse-response resp))))

(defn balance
  "Check remaining usage credits. Calls `GET /api/balance` (cloud only).
  Returns a map with `:included` and `:purchased` balances."
  ([ ] (balance *client*))
  ([client] (perform client :get "/api/balance" nil)))

;; ---------------------------------------------------------------------------
;; Convenience: bind a default client
;; ---------------------------------------------------------------------------

(defmacro with-client
  "Bind `clai.core/*client*` for the body, so calls that omit an explicit
  client use `client`.

      (clai/with-client (clai/make-client {:host \"https://ollama.com\"
                                           :api-key key})
        (clai/chat {:model \"gemma4\" :messages ... :stream false}))"
  [client & body]
  `(binding [*client* ~client]
     ~@body))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Print the Ollama server version as a smoke test of connectivity."
  [& args]
  (let [c (make-client)]
    (println "Ollama" (:version (version c)))))
