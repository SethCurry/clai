(ns acp.jsonrpc
  (:require
            [cheshire.core :as json]
            [acp.errors :as errors])
  (:import [java.io Writer]
           [java.util.concurrent.atomic AtomicLong]))


;; ---------------------------------------------------------------------------
;; Output: JSON-RPC framing
;; ---------------------------------------------------------------------------

(defn clean
  "Drop top-level keys whose value is `nil`, so omitted fields stay off the
  wire. Nested maps are left untouched."
  [m]
  (if (map? m)
    (into {} (remove (comp nil? val)) m)
    m))

(defn- json-safe
  "Recursively convert `x` into a value Cheshire can encode. Protocol values
  are plain maps/vectors/scalars, but error data can contain arbitrary objects
  (for example Malli schema objects); stringify those rather than letting a
  failed encode drop the response entirely."
  [x]
  (cond
    (or (nil? x) (string? x) (number? x) (boolean? x) (keyword? x)) x
    (map? x)    (into {} (map (fn [[k v]] [(json-safe k) (json-safe v)])) x)
    (set? x)    (mapv json-safe x)
    (sequential? x) (mapv json-safe x)
    :else       (str x)))

(defn write-message
  "Encode `obj` as a single-line JSON document, write it to stdout followed by
  a newline, and flush. Serialized through `out-lock` so concurrent writers
  never interleave bytes."
  [{:keys [^Writer out ^Object out-lock]} obj]
  (locking out-lock
    (.write out (json/generate-string (json-safe (clean obj))))
    (.write out "\n")
    (.flush out)))

(defn response-envelope
  [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn error-envelope
  [id err]
  {:jsonrpc "2.0"
   :id id
   :error (cond-> {:code    (:code err)
                    :message (:message err)}
            (some? (:data err)) (assoc :data (:data err)))})

(defn send-response
  "Send a successful JSON-RPC response for `id` with `result`."
  [conn id result]
  (write-message conn (response-envelope id result))
  nil)

(defn send-error
  "Send a JSON-RPC error response for `id`. `err` is an `AcpError` or a
  `[code message data]` triple."
  [conn id err]
  (let [err (cond
              (errors/acp-error? err) err
              (vector? err)    (apply errors/acp-error err)
              :else            (errors/acp-error errors/err-internal))]
    (write-message conn (error-envelope id err)))
  nil)

(defn send-notification
  "Send a JSON-RPC notification (no `id`, no response expected). `params` may
  be `nil` to omit the `params` field."
  ([conn method] (send-notification conn method nil))
  ([conn method params]
   (let [msg (cond-> {:jsonrpc "2.0" :method method}
               (some? params) (assoc :params params))]
     (write-message conn msg))
   nil))

(defn send-request
  "Send a JSON-RPC request to the client and return a `clojure.core/promise`
  that will deliver a response envelope: `{:result ...}` on success or
  `{:error {:code ... :message ... :data ...}}` on failure.

  Use `await-request` to block for the result."
  ([conn method] (send-request conn method nil))
  ([conn method params]
   (let [id      (.getAndIncrement ^AtomicLong (:next-id conn))
         promise (promise)
         msg     (cond-> {:jsonrpc "2.0" :id id :method method}
                   (some? params) (assoc :params params))]
     (swap! (:pending conn) assoc id promise)
     (write-message conn msg)
     promise)))

(defn await-request
  "Block on a promise from `send-request` for up to `timeout-ms` milliseconds,
  returning the `result` on success. On error or timeout, throws an `ex-info`
  carrying `:acp/error`."
  ([promise] (await-request promise 60000))
  ([promise ^long timeout-ms]
   (let [envelope (deref promise timeout-ms ::timeout)]
     (cond
       (identical? envelope ::timeout)
         (throw (ex-info "ACP request timed out"
                         {:acp/error (errors/acp-error errors/err-internal "ACP request timed out")}))

       (:error envelope)
         (throw (ex-info (-> envelope :error :message)
                         {:acp/error (errors/->AcpError
                                       (-> envelope :error :code)
                                       (-> envelope :error :message)
                                       (-> envelope :error :data))}))

       :else (:result envelope)))))
