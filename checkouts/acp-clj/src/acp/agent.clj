(ns acp.agent
  (:require
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [acp.schema.core :as schemas]
            [acp.schema-runtime :as rt]
            [acp.jsonrpc :as jsonrpc]
            [acp.errors :as errors])
  (:import [java.io BufferedReader BufferedWriter InputStream OutputStream
            Reader Writer]
           [java.util.concurrent Callable Executors ExecutorService
            ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

;; ACP v2 is protocol version 2.
(def ^:const protocol-version-2 2)

(def ^:const ^:private default-supported-versions
  "Protocol versions the default handler advertises, highest last."
  [protocol-version-2])

;; ---------------------------------------------------------------------------
;; Agent handler protocol
;; ---------------------------------------------------------------------------

(defprotocol AgentHandler
  "The interface an ACP agent implements.

  Each `handle-*` method receives the handler, the `StdioConnection`, and the
  parsed request `params` map (camelCase keyword keys, verbatim from the wire;
  `{}` when params were omitted or `null`). It returns the JSON-RPC `result`
  map, or an `AcpError` (returned or thrown) to produce an error response.

  Notification methods (`on-*`) return nothing of interest; their side effects
  (if any) are sent as `session/update` notifications via the connection."

  (handle-initialize [h conn params]
    "Respond to `initialize`. Returns an `InitializeResponse` map
    (`:protocolVersion`, `:info`, optionally `:capabilities`/`:authMethods`).")

  (handle-authenticate [h conn params]
    "Respond to `authenticate`. Only called when the agent advertised
    authentication methods. `params` has `:methodId`.")

  (handle-logout [h conn params]
    "Respond to `logout`. Only called when the agent advertised `logout` in
    its `auth` capabilities.")

  (handle-session-new [h conn params]
    "Create a session. `params` has `:cwd` and optionally
    `:additionalDirectories`, `:mcpServers`. Returns a `NewSessionResponse`
    (`:sessionId`, ...).")

  (handle-session-list [h conn params]
    "List known sessions. `params` may have `:cwd`, `:cursor`. Returns a
    `ListSessionsResponse` (`:sessions`, optional `:nextCursor`).")

  (handle-session-delete [h conn params]
    "Delete a session. `params` has `:sessionId`. Returns `{}`.")

  (handle-session-resume [h conn params]
    "Resume a session. `params` has `:sessionId`, `:cwd`, optionally
    `:additionalDirectories`, `:mcpServers`, `:replayFrom`.")

  (handle-session-close [h conn params]
    "Close a session, cancelling any in-flight work. `params` has
    `:sessionId`.")

  (handle-session-set-config-option [h conn params]
    "Set a session config option. `params` has `:sessionId`, `:configId`.")

  (handle-session-prompt [h conn params]
    "Accept a user prompt. `params` has `:sessionId`, `:prompt`. Returns a
    `PromptResponse` (`:stopReason`). Processing is reported separately via
    `session/update` notifications.")

  (on-session-cancel [h conn params]
    "Notification `session/cancel` for `:sessionId`. Stop foreground work and
    eventually respond to the in-flight `session/prompt` with the
    `cancelled` stop reason.")

  (on-cancel-request [h conn params]
    "Protocol notification `$/cancel_request` for `:requestId`. Cancel the
    matching in-flight request if still running.")

  (on-notification [h conn method params]
    "Any other (extension) notification from the client. `method` is the raw
    method string.")

  (on-ext-request [h conn method params]
    "Any other (extension) request from the client. Return a result map or an
    `AcpError`. `method` is the raw method string."))

;; ---------------------------------------------------------------------------
;; Default handler
;; ---------------------------------------------------------------------------

(def ^:private default-info
  {:name "acp-clj"
   :title "acp-clj"
   :version "0.1.0-SNAPSHOT"})

(defrecord DefaultHandler
    [info supported-versions capabilities auth-methods]
  AgentHandler
  (handle-initialize [_ _conn {:keys [protocolVersion]}]
    (let [supported  (or (seq supported-versions) default-supported-versions)
          candidates (->> supported
                         (filter #(<= (long %) (long protocolVersion)))
                         seq)]
      (if-let [chosen (and candidates (reduce max candidates))]
        (cond-> {:protocolVersion (long chosen)
                 :info            (or info default-info)}
          (some? capabilities)  (assoc :capabilities capabilities)
          (seq auth-methods)    (assoc :authMethods auth-methods))
        (errors/throw-acp
         (errors/acp-error errors/err-protocol-ver
                    (errors/default-error-messages errors/err-protocol-ver)
                    {:requested protocolVersion
                     :supported (vec supported)})))))
  (handle-authenticate [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-logout [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-session-new [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-session-list [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-session-delete [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-session-resume [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-session-close [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-session-set-config-option [_ _conn _params]
    (errors/acp-error errors/err-method))
  (handle-session-prompt [_ _conn _params]
    (errors/acp-error errors/err-method))
  (on-session-cancel [_ _conn _params] nil)
  (on-cancel-request [_ _conn _params] nil)
  (on-notification [_ _conn _method _params] nil)
  (on-ext-request [_ _conn _method _params]
    (errors/acp-error errors/err-method)))

(defn default-handler
  "Build a `DefaultHandler` with the given options:

    :info              Implementation map (`:name`, `:version`, optional
                       `:title`). Defaults to a `clai` placeholder.
    :supported-versions  Vector of protocol versions the agent can speak.
                       Defaults to `[2]`.
    :capabilities      `AgentCapabilities` map to advertise, or `nil`.
    :auth-methods      `authMethods` vector to advertise, or `nil/empty`.

  The default handler answers `initialize` (negotiating the highest supported
  version at or below the client's request) and returns `method_not_found`
  for every other method. Compose it with `reify`/`defrecord` to override
  individual methods."
  ([] (default-handler nil))
  ([{:keys [info supported-versions capabilities auth-methods]}]
   (->DefaultHandler info
                     (or supported-versions default-supported-versions)
                     capabilities
                     auth-methods)))

;; ---------------------------------------------------------------------------
;; Method dispatch tables
;; ---------------------------------------------------------------------------

(def ^:private request-method->handler-fn
  {"initialize"                #'handle-initialize
   "authenticate"              #'handle-authenticate
   "logout"                    #'handle-logout
   "session/new"               #'handle-session-new
   "session/list"              #'handle-session-list
   "session/delete"            #'handle-session-delete
   "session/resume"            #'handle-session-resume
   "session/close"             #'handle-session-close
   "session/set_config_option" #'handle-session-set-config-option
   "session/prompt"            #'handle-session-prompt})

(def ^:private request-method->params-schema
  "Maps each known request method to the malli schema for its `params`."
  {"initialize"                 schemas/InitializeRequest
   "authenticate"               schemas/AuthenticateRequest
   "logout"                     schemas/LogoutRequest
   "session/new"                schemas/NewSessionRequest
   "session/list"               schemas/ListSessionsRequest
   "session/delete"             schemas/DeleteSessionRequest
   "session/resume"             schemas/ResumeSessionRequest
   "session/close"              schemas/CloseSessionRequest
   "session/prompt"             schemas/PromptRequest})

(def ^:private notification-method->handler-fn
  {"session/cancel"     #'on-session-cancel
   "$/cancel_request"   #'on-cancel-request})

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defrecord StdioConnection
    [^BufferedReader in
     ^BufferedWriter out
     ^Object out-lock                      ; serializes all stdout writes
     ^AtomicLong next-id                   ; outbound request id generator
     pending                               ; atom: id -> promise of response envelope
     cancellable                           ; atom: inbound id -> future (for $/cancel_request)
     state                                 ; atom: connection-level state
     ^ExecutorService executor
     handler])

(defn connection?
  [x]
  (instance? StdioConnection x))

(defn- daemon-thread-factory
  ^ThreadFactory [^String prefix]
  (reify ThreadFactory
    (newThread [_ r]
      (doto (Thread. ^Runnable r (str prefix "-" (java.util.UUID/randomUUID)))
        (.setDaemon true)))))

(defn- make-executor
  ^ExecutorService []
  (Executors/newCachedThreadPool (daemon-thread-factory "clai-acp")))

(defn make-connection
  "Construct a `StdioConnection` for `handler`.

  Options:

    :in   A `java.io.Reader` or `java.io.InputStream` to read messages from.
          Defaults to `System/in`.
    :out  A `java.io.Writer` or `java.io.OutputStream` to write messages to.
          Defaults to `System/out`.

  The returned connection is not yet reading; call `run` to start the reader
  loop. Construction does no I/O."
  ([handler] (make-connection handler nil))
  ([handler {:keys [in out]
             :or {in  System/in
                  out System/out}}]
   (let [^Reader   reader (cond-> in (instance? InputStream in) (io/reader))
         ^Writer   writer (cond-> out (instance? OutputStream out) (io/writer))
         conn
         (->StdioConnection
          (BufferedReader. reader)
          (BufferedWriter. writer)
          (Object.)
          (AtomicLong. 1)
          (atom {})                            ; pending
          (atom {})                            ; cancellable
          (atom {:initialized? false
                 :protocol-version nil
                 :client-info nil
                 :client-capabilities nil})
          (make-executor)
          handler)]
     conn)))

(defn shutdown
  "Stop the connection's worker pool. The reader loop exits on stdin EOF and
  calls this automatically; call it explicitly when integrating elsewhere."
  ([conn] (shutdown conn 1))
  ([conn ^long await-seconds]
   (let [^ExecutorService ex (:executor conn)]
     (when ex
       (.shutdownNow ex)
       (.awaitTermination ex await-seconds TimeUnit/SECONDS)))))

;; ---------------------------------------------------------------------------
;; Convenience senders for common agent -> client messages
;; ---------------------------------------------------------------------------

(defn send-session-update
  "Send a `session/update` notification carrying one `SessionUpdate` for
  `session-id`. `update` is the full update map including its `:sessionUpdate`
  discriminator (e.g. `{:sessionUpdate \"agent_message_chunk\" :content
  {:type \"text\" :text \"...\"}}`)."
  [conn sid update]
  (rt/validate! schemas/SessionId sid "send-session-update sessionId")
  (rt/validate! schemas/SessionUpdate update "send-session-update update")
  (jsonrpc/send-notification conn "session/update"
                     {:sessionId sid :update update}))

(defn send-agent-message-chunk
  "Send a plain-text `agent_message_chunk` `session/update` for `sid`.

  `content` is the text to display. `ContentBlock` is a union discriminated by
  `:type`, so the text payload built by `schemas/->TextContent` is tagged as
  `\"text\"` to make it a content block. The chunk is built with
  `schemas/->SessionUpdate` so it is validated before it goes on the wire."
  [conn sid content]
  (send-session-update conn sid
    (schemas/->SessionUpdate
     {:sessionUpdate "agent_message_chunk"
      :content
                      (assoc (schemas/->TextContent content) :type "text")})))

(defn request-permission
  "Send a `session/request_permission` request and return its promise. Blocks
  the caller only if they `await-request` the result. `opts` is the
  `RequestPermissionRequest` body (`:sessionId`, `:toolCall`, `:options`,
  optional `:_meta`)."
  [conn opts]
  (rt/validate! schemas/RequestPermissionRequest opts "request-permission")
  (jsonrpc/send-request conn "session/request_permission" opts))

;; ---------------------------------------------------------------------------
;; Input: classification & dispatch
;; ---------------------------------------------------------------------------

(defn- invoke-handler
  "Call the handler var for `method` with `conn` and `params`, returning an
  `AcpError` on failure. When a params schema is registered for `method`, the
  params are validated first and an `err-params` error is returned on
  failure."
  [conn handler-fn-var method params]
  (try
    (when-let [schema (request-method->params-schema method)]
      (rt/validate! schema params (str "params for " method)))
    (@handler-fn-var (:handler conn) conn params)
    (catch clojure.lang.ExceptionInfo e
      (if-let [errors (-> (ex-data e) :errors)]
        ;; `rt/validate!` has already logged the validation failure. Keep the
        ;; raw Malli schema out of the response data; it is not JSON.
        (errors/acp-error errors/err-params
                          (errors/default-error-messages errors/err-params)
                          (-> (ex-data e) (dissoc :schema)))
        (errors/ex->acp-error e)))
    (catch Throwable e
      (errors/ex->acp-error e))))

(defn- resolve-result
  "Normalize a handler return value into either `{:result ...}` or
  `{:error ...}`."
  [value]
  (if (errors/acp-error? value)
    {:error value}
    {:result value}))

(defn- handle-incoming-request
  "Run an incoming client request on the worker pool, register it as
  cancellable, and send its response when complete. Returns nil; the response
  write is scheduled asynchronously."
  [{:keys [handler cancellable executor] :as conn} id method params]
  (let [run-fn (fn []
                 (try
                   (let [result-map
                         (if-let [hf (request-method->handler-fn method)]
                           (resolve-result
                            (invoke-handler conn hf method params))
                           ;; Extension method.
                           (resolve-result
                            (try
                              (on-ext-request handler conn method params)
                              (catch Throwable e
                                (errors/ex->acp-error e)))))]
                     (if (:error result-map)
                       (jsonrpc/send-error conn id (:error result-map))
                       (jsonrpc/send-response conn id (:result result-map))))
                   (finally
                    (swap! cancellable dissoc id))))]
    (let [fut (.submit ^ExecutorService executor ^Runnable run-fn)]
      (swap! cancellable assoc id fut))))

(defn- handle-incoming-notification
  "Run an incoming client notification on the worker pool. No response."
  [{:keys [handler executor cancellable] :as conn} method params]
  ;; $/cancel_request is handled partly at the transport level: cancel the
  ;; registered in-flight request future before delegating to the handler.
  (when (= method "$/cancel_request")
    (when-let [rid (some-> params :requestId)]
      (when-let [fut (get @cancellable rid)]
        (future-cancel fut))))
  (let [run-fn (fn []
                 (try
                   (if-let [hf (notification-method->handler-fn method)]
                     (@hf handler conn params)
                     (on-notification handler conn method params))
                   (catch Throwable e
                                 )))]
    (.submit ^ExecutorService executor ^Runnable run-fn))
  nil)

(defn- handle-incoming-response
  "Resolve a pending outbound request promise with its response envelope."
  [{:keys [pending]} id envelope]
  (if-let [promise (get @pending id)]
    (do
      (swap! pending dissoc id)
      (deliver promise envelope)))
  nil)

(defn- classify
  "Classify a parsed message map. Returns one of:
    `[:request id method params]`
    `[:notification method params]`
    `[:response id envelope]`   ; envelope has :result or :error
    `[:invalid]`                ; not a recognizable JSON-RPC message"
  [msg]
  (cond
    (not (map? msg))                [:invalid]
    (and (contains? msg :method)
         (contains? msg :id))       [:request (:id msg) (:method msg)
                                     (or (:params msg) {})]
    (contains? msg :method)         [:notification (:method msg)
                                     (:params msg)]
    (contains? msg :result)         [:response (:id msg)
                                     (select-keys msg [:result])]
    (contains? msg :error)          [:response (:id msg)
                                     (select-keys msg [:error])]
    :else                           [:invalid]))

(defn- process-entry
  "Process a single parsed message object (not a batch). Performs the needed
  side effects and returns nil."
  [conn msg]
  (let [classified (classify msg)]
    (case (first classified)
      :request
      (let [[_ id method params] classified]
        (handle-incoming-request conn id method params))

      :notification
      (let [[_ method params] classified]
        (handle-incoming-notification conn method params))

      :response
      (let [[_ id envelope] classified]
        (handle-incoming-response conn id envelope))

      :invalid
        (jsonrpc/write-message conn (jsonrpc/error-envelope nil (errors/acp-error errors/err-invalid))))))

;; `process-entry` sends request responses asynchronously from within
;; `handle-incoming-request`, which is correct for single messages but would
;; not produce one JSON-RPC batch response array. `process-batch` therefore
;; uses `run-request-for-batch` to compute each entry's response object and a
;; coordinator future to send them as a single array once all complete.

(defn- run-request-for-batch
  "Compute a single request's response object (synchronously relative to the
  batch), returning the JSON-RPC response map. Used only inside batch
  aggregation, where a coordinator future waits on all entries."
  [conn id method params]
  (let [result-map
        (if-let [hf (request-method->handler-fn method)]
          (resolve-result (invoke-handler conn hf method params))
          (resolve-result
           (try
             (on-ext-request (:handler conn) conn method params)
             (catch Throwable e
               (errors/ex->acp-error e)))))]
    (if (:error result-map)
      (jsonrpc/error-envelope id (:error result-map))
      (jsonrpc/response-envelope id (:result result-map)))))

(defn- process-batch
  "Handle a JSON-RPC batch array. Each request entry runs on the worker pool;
  a coordinator future collects the response objects and sends them as a
  single array (or sends nothing if there are none). Notifications and
  responses inside the batch are dispatched for their side effects only."
  [conn entries]
  (let [response-futs (atom [])
        coordinator   (fn []
                        (let [responses (doall (keep deref @response-futs))]
                          (when (seq responses)
                            (jsonrpc/write-message conn responses))))]
    (doseq [entry entries]
      (cond
        (not (map? entry))
        (swap! response-futs conj
               (future (jsonrpc/error-envelope nil (errors/acp-error errors/err-invalid))))

        (and (contains? entry :method) (contains? entry :id))
        (let [id     (:id entry)
              method (:method entry)
              params (or (:params entry) {})
              fut    (.submit ^ExecutorService (:executor conn)
                              ^Callable
                              (fn [] (run-request-for-batch
                                      conn id method params)))]
          (swap! response-futs conj fut))

        (contains? entry :method)
        (handle-incoming-notification conn (:method entry) (:params entry))

        (or (contains? entry :result) (contains? entry :error))
        (handle-incoming-response conn (:id entry)
                                  (select-keys entry [:result :error]))

        :else
        (swap! response-futs conj
               (future (jsonrpc/error-envelope nil (errors/acp-error errors/err-invalid))))))
    ;; If any responses are expected, schedule the coordinator to send them.
    (when (seq @response-futs)
      (.submit ^ExecutorService (:executor conn) ^Runnable coordinator)))
  nil)

(defn- process-message
  "Process one parsed top-level message (object or array)."
  [conn parsed]
  (cond
    (vector? parsed)
    (cond
      (empty? parsed)
        (jsonrpc/write-message conn (jsonrpc/error-envelope nil (errors/acp-error errors/err-invalid)))
      :else
      (process-batch conn parsed))

    (map? parsed)
    (process-entry conn parsed)

    :else
      (jsonrpc/write-message conn (jsonrpc/error-envelope nil (errors/acp-error errors/err-invalid)))))

;; ---------------------------------------------------------------------------
;; Reader loop
;; ---------------------------------------------------------------------------

(defn- read-loop
  "Read newline-delimited JSON from `in`, dispatching each message. Blocks
  until EOF (the client closed stdin) and then returns."
  [conn]
  (let [^BufferedReader reader (:in conn)]
    (loop []
      (if-let [line (.readLine reader)]
        (do
          (let [line (str/trim line)]
            (when-not (str/blank? line)
              (try
                (let [parsed (json/parse-string-strict line true)]
                  (process-message conn parsed))
                (catch Throwable e
                  (jsonrpc/write-message conn
                                 (jsonrpc/error-envelope nil (errors/acp-error errors/err-parse)))))))
          (recur))
        ;; EOF: stop.
          nil))))

(defn run
  "Start the agent: read ACP messages from stdin until EOF, dispatching to
  `handler`. Blocks the calling thread until the connection ends.

  `handler` may be an `AgentHandler` or a map of options for
  `default-handler` (in which case a `DefaultHandler` is built). Returns
  `nil` on clean exit.

  Options (for the connection, not the handler):

    :in  / :out  override stdin/stdout (see `make-connection`)."
  ([handler] (run handler nil))
  ([handler opts]
   (let [handler (if (satisfies? AgentHandler handler)
                   handler
                   (default-handler handler))
         conn    (make-connection handler opts)]
     (try
       (read-loop conn)
       (finally
         (shutdown conn 5))))))

(defn -main
  "Run a minimal `DefaultHandler` agent over stdio. Useful as a smoke test:
  it answers `initialize` and rejects every other method with
  `method_not_found`."
  [& _args]
  (run (default-handler)))
