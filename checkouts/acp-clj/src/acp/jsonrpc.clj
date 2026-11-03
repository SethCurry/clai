(ns acp.jsonrpc
  (:require
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [acp.errors :as errors]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStream OutputStream Reader Writer]
           [java.util.concurrent BlockingQueue LinkedBlockingQueue]
           [java.util.concurrent.atomic AtomicLong]))

;; ---------------------------------------------------------------------------
;; Transport: channels
;; ---------------------------------------------------------------------------

(defprotocol Channel
  "A bidirectional JSON-RPC transport. Channels deal in decoded message maps:
  `send!` takes a message map, `take!` returns one. Framing, encoding, and
  decoding are the channel's business."
  (send! [channel msg]
    "Send `msg` (a JSON-RPC message map) to the peer. Sending `nil` closes a
    queue-backed channel.")
  (take! [channel]
    "Block until the peer sends a message and return the decoded map, or return
    `nil` once the channel is closed. A transport that receives a frame it
    cannot decode returns `parse-error`."))

(def eof
  "Sentinel that closes a queue-backed channel when read from its input
  queue. `(send! channel nil)` has the same effect."
  ::eof)

(def parse-error
  "Returned by `take!` when the peer sent a frame that is not valid JSON."
  ::parse-error)

(defn parse-error?
  "True when `x` is the value `take!` returns for an undecodable frame."
  [x]
  (identical? parse-error x))

(deftype QueueChannel [^BlockingQueue input ^BlockingQueue output]
  Channel
  (send! [_ msg]
    (.put output (if (nil? msg) eof msg))
    nil)
  (take! [_]
    (let [msg (.take input)]
      (when-not (identical? msg eof) msg))))

(defn new-queue-channel
  "Build an in-process `Channel` backed by two `java.util.concurrent`
  `BlockingQueue`s.

  Options:

    :input   Queue holding messages arriving from the peer; `take!` blocks on
             it. Defaults to a fresh `LinkedBlockingQueue`.
    :output  Queue receiving messages written by `send!`. Defaults to a fresh
             `LinkedBlockingQueue`.

  To close the channel, put `eof` on the input queue (or `send!` `nil` from
  the peer side). Most callers use `channel-pair` instead."
  ([] (new-queue-channel nil))
  ([{:keys [input output]
     :or {input  (LinkedBlockingQueue.)
          output (LinkedBlockingQueue.)}}]
   (QueueChannel. input output)))

(defn channel-pair
  "Build two channels connected back to back: whatever is sent on one is
  received by the other. Returns `[a b]`. Useful for driving a connection in
  process (for example, in a REPL or a test)."
  []
  (let [a->b (LinkedBlockingQueue.)
        b->a (LinkedBlockingQueue.)]
    [(new-queue-channel {:input a->b :output b->a})
     (new-queue-channel {:input b->a :output a->b})]))

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

(defn- write-line
  "Encode `obj` as a single-line JSON document and write it to `out`, followed
  by a newline and a flush. Serialized through `out-lock` so concurrent
  writers never interleave bytes."
  [^Writer out ^Object out-lock obj]
  (locking out-lock
    (.write out (json/generate-string (json-safe (clean obj))))
    (.write out "\n")
    (.flush out))
  nil)

(deftype StdioChannel [^BufferedReader in ^Writer out ^Object out-lock]
  Channel
  (send! [_ msg]
    (write-line out out-lock msg))
  (take! [_]
    (loop []
      (when-let [line (.readLine in)]
        (let [line (str/trim line)]
          (if (str/blank? line)
            (recur)
            (try
              (json/parse-string-strict line true)
              (catch Throwable _
                parse-error))))))))

(defn new-stdio-channel
  "Build a `Channel` that reads newline-delimited JSON from `:in` and writes
  it to `:out`, the classic ACP stdio transport.

  Options:

    :in        A `java.io.Reader` or `java.io.InputStream` to read messages
               from. Defaults to `System/in`.
    :out       A `java.io.Writer` or `java.io.OutputStream` to write messages
               to. Defaults to `System/out`.
    :out-lock  Object used to serialize concurrent writes. Defaults to `out`."
  ([] (new-stdio-channel nil))
  ([{:keys [in out out-lock]
     :or {in  System/in
          out System/out}}]
   (let [^Reader in  (if (instance? InputStream in) (io/reader in) in)
         ^Writer out (if (instance? OutputStream out) (io/writer out) out)]
     (StdioChannel. (BufferedReader. in) out (or out-lock out)))))

(defn write-message
  "Send `obj` to the peer over `conn`'s transport, an `acp.jsonrpc/Channel`
  held in `:channel`."
  [conn obj]
  (send! (:channel conn) obj))

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
