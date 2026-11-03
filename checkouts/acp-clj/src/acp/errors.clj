(ns acp.errors)


;; ---------------------------------------------------------------------------
;; JSON-RPC / ACP error codes
;; ---------------------------------------------------------------------------

(def ^:const err-parse        -32700)
(def ^:const err-invalid      -32600)
(def ^:const err-method       -32601)
(def ^:const err-params       -32602)
(def ^:const err-internal     -32603)
(def ^:const err-cancelled    -32800)
(def ^:const err-auth         -32000)
(def ^:const err-not-found    -32002)
(def ^:const err-protocol-ver -32022)

(def default-error-messages
  {err-parse        "Parse error"
   err-invalid      "Invalid request"
   err-method       "Method not found"
   err-params       "Invalid params"
   err-internal     "Internal error"
   err-cancelled    "Request cancelled"
   err-auth         "Authentication required"
   err-not-found    "Resource not found"
   err-protocol-ver "Unsupported protocol version"})

(defrecord AcpError
    [^long code ^String message data])

(defn acp-error
  "Build an ACP error value. Handlers return or throw these to signal a
  JSON-RPC error response. `data` is optional, opaque to the protocol."
  ([code] (acp-error code nil nil))
  ([code message] (acp-error code message nil))
  ([code message data]
   (->AcpError (long code)
               (or message (default-error-messages code "ACP error"))
               data)))

(defn ^:no-doc acp-error?
  [x]
  (instance? AcpError x))

(defn ^:no-doc throw-acp
  "Throw an `AcpError` as an `ex-info` carrying `:acp/error`."
  [^AcpError err]
  (throw (ex-info (or (.-message err) "ACP error")
                  {:acp/error err})))

(defn ex->acp-error
  "Extract an `AcpError` from an exception, defaulting to internal error."
  [^Exception e]
  (if-let [err (-> (ex-data e) :acp/error)]
    err
    (let [msg (or (ex-message e) "Internal error")]
      (cond-> (acp-error err-internal msg)
        (instance? InterruptedException e)
        (assoc :code err-cancelled
               :message (default-error-messages err-cancelled))))))
