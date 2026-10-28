(ns clai.acp
  "Agent-side [Agent Client Protocol][acp] (v2) connection over stdio.

  [acp]: https://agentclientprotocol.com/protocol/v2/overview

  This namespace implements the **agent** role of ACP: a program launched as a
  subprocess by a client, speaking newline-delimited JSON-RPC 2.0 over its
  stdin/stdout. WebSocket and HTTP transports are out of scope.

  ## Wire format

  Each transport message is a single UTF-8 JSON-RPC object (or a JSON-RPC
  batch array) terminated by a newline, with no embedded newlines. The agent
  reads messages from `*in*` and writes to `*out*`; it may log to `*err*`.
  Nothing that is not a valid ACP message is written to stdout.

  ## Architecture

  The reader thread only frames, parses, and dispatches; it never blocks on
  agent work. Incoming requests and notifications run on a dedicated thread
  pool, so an agent handler may itself send requests to the client (e.g.
  `session/request_permission` or `elicitation/create`) and await their
  responses without deadlocking the reader. All output is serialized through
  a single lock so transport message order matches send order.

  ## Implementing an agent

  Provide a `clai.acp.AgentHandler` implementation (e.g. via `reify` or
  `defrecord`) and pass it to `run`:

      (def my-agent
        (reify acp/AgentHandler
          (handle-initialize [_ conn params]
            {:protocolVersion 2
             :info {:name \"my-agent\" :version \"0.1.0\"}
             :capabilities {}})
          (handle-session-new [_ conn {:keys [cwd]}]
            {:sessionId (str (java.util.UUID/randomUUID))})
          (handle-session-prompt [_ conn {:keys [sessionId prompt]}]
            ;; Echo the user's text back as an agent message, then go idle.
            (let [msg-id (str (java.util.UUID/randomUUID))]
              (acp/send-session-update conn sessionId
                {:sessionUpdate \"agent_message\"
                 :messageId msg-id
                 :role \"agent\"
                 :content prompt})
              (acp/send-session-update conn sessionId
                {:sessionUpdate \"state_update\"
                 :state \"idle\"})
              {:messageId (str (java.util.UUID/randomUUID))}))))

      (defn -main [& args]
        (acp/run my-agent))

  `DefaultHandler` provides a baseline `initialize` (negotiating protocol
  version 2) and returns `method_not_found` (-32601) for every other method,
  so you can subclass it and override only what you need.

  Keys in request params and response maps are preserved verbatim from the
  ACP schema (`:protocolVersion`, `:sessionId`, `:sessionUpdate`, ...), the
  same convention used by the rest of `clai`.
  "
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.io BufferedReader BufferedWriter InputStream OutputStream
            Reader Writer]
           [java.util.concurrent Callable Executors ExecutorService
            ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

;; ---------------------------------------------------------------------------
;; Protocol constants
;; ---------------------------------------------------------------------------

;; ACP v2 is protocol version 2.
(def ^:const protocol-version-2 2)

(def ^:const current-protocol-version
  "The newest ACP protocol version this implementation speaks."
  protocol-version-2)

(def ^:const ^:private default-supported-versions
  "Protocol versions the default handler advertises, highest last."
  [protocol-version-2])

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

(def ^:private default-error-messages
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

(defn- ex->acp-error
  "Extract an `AcpError` from an exception, defaulting to internal error."
  [^Exception e]
  (if-let [err (-> (ex-data e) :acp/error)]
    err
    (let [msg (or (ex-message e) "Internal error")]
      (cond-> (acp-error err-internal msg)
        (instance? InterruptedException e)
        (assoc :code err-cancelled
               :message (default-error-messages err-cancelled))))))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  [:re #".+"])

(def session-id
  "ACP session identifier (opaque string)."
  non-empty-string)

(def request-id
  "A JSON-RPC id: integer, string, or nil (for notifications)."
  [:or int? string? :nil])

(def json-rpc-request
  "A JSON-RPC 2.0 request object."
  [:map
   [:jsonrpc [:= "2.0"]]
   [:id request-id]
   [:method non-empty-string]
   [:params {:optional true} [:map]]])

(def json-rpc-notification
  "A JSON-RPC 2.0 notification (no id)."
  [:map
   [:jsonrpc [:= "2.0"]]
   [:method non-empty-string]
   [:params {:optional true} [:map]]])

(def json-rpc-error-object
  "The `error` object inside an error response."
  [:map
   [:code int?]
   [:message string?]
   [:data {:optional true} :any]])

(def json-rpc-response
  "A JSON-RPC 2.0 response object (success or error)."
  [:and
   [:map
    [:jsonrpc [:= "2.0"]]
    [:id request-id]]
   [:or
    [:map [:result :any]]
    [:map [:error json-rpc-error-object]]]])

(def implementation-info
  "The `:info` map advertised by an agent/client."
  [:map
   [:name non-empty-string]
   [:version non-empty-string]
   [:title {:optional true} string?]])

(def agent-capabilities
  "The `capabilities` map advertised by an agent."
  [:map])

(def auth-method
  "A single entry in `authMethods`."
  [:map
   [:methodId non-empty-string]
   [:title {:optional true} string?]
   [:description {:optional true} string?]])

(def initialize-params
  "Params for the `initialize` request."
  [:map
   [:protocolVersion pos-int?]
   [:info {:optional true} implementation-info]
   [:capabilities {:optional true} [:map]]])

(def initialize-response
  "Result of the `initialize` request."
  [:map
   [:protocolVersion pos-int?]
   [:info implementation-info]
   [:capabilities {:optional true} agent-capabilities]
   [:authMethods {:optional true} [:sequential auth-method]]])

(def session-new-params
  "Params for `session/new`."
  [:map
   [:cwd {:optional true} string?]
   [:additionalDirectories {:optional true} [:sequential string?]]
   [:mcpServers {:optional true} [:sequential [:map]]]])

(def session-new-response
  "Result of `session/new`."
  [:map
   [:sessionId session-id]])

(def session-list-params
  "Params for `session/list`."
  [:map
   [:cwd {:optional true} string?]
   [:cursor {:optional true} string?]])

(def session-list-response
  "Result of `session/list`."
  [:map
   [:sessions {:optional true} [:sequential [:map]]]
   [:nextCursor {:optional true} string?]])

(def session-id-params
  "Params for methods that only take a `:sessionId` (delete/close)."
  [:map
   [:sessionId session-id]])

(def session-resume-params
  "Params for `session/resume`."
  [:map
   [:sessionId session-id]
   [:cwd {:optional true} string?]
   [:additionalDirectories {:optional true} [:sequential string?]]
   [:mcpServers {:optional true} [:sequential [:map]]]
   [:replayFrom {:optional true} string?]])

(def session-prompt-params
  "Params for `session/prompt`."
  [:map
   [:sessionId session-id]
   [:prompt [:or string? [:sequential [:map]]]]])

(def session-prompt-response
  "Result of `session/prompt`."
  [:map
   [:messageId non-empty-string]])

(def session-cancel-params
  "Params for the `session/cancel` notification."
  [:map
   [:sessionId session-id]])

(def cancel-request-params
  "Params for the `$/cancel_request` notification."
  [:map
   [:requestId [:or int? string?]]])

(def session-update
  "One `SessionUpdate` object, discriminated by `:sessionUpdate`."
  [:map
   [:sessionUpdate non-empty-string]])

(def session-update-params
  "Params carried by a `session/update` notification."
  [:map
   [:sessionId session-id]
   [:update session-update]])

(def request-permission-opts
  "Body of `session/request_permission`."
  [:map
   [:sessionId session-id]
   [:title string?]
   [:options [:sequential [:map]]]
   [:description {:optional true} string?]
   [:subject {:optional true} [:map]]])

(def create-elicitation-opts
  "Body of `elicitation/create`."
  [:map])

(def complete-elicitation-params
  "Body of `elicitation/complete`."
  [:map
   [:elicitationId non-empty-string]])

(def acp-error-schema
  "An `AcpError` value (code/message/data)."
  [:map
   [:code int?]
   [:message string?]
   [:data {:optional true} :any]])

(defn validate!
  "Validate `value` against `schema`, returning `value` when valid or throwing
  an `ex-info` with a human-readable malli error explanation otherwise."
  [schema value what]
  (if-let [errors (and schema (m/explain schema value))]
    (throw (ex-info (str "Invalid " what ": "
                         (-> errors me/humanize pr-str))
                    {:schema schema :value value :errors errors})))
  value)

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

  (handle-auth-login [h conn params]
    "Respond to `auth/login`. Only called when the agent advertised
    `authMethods`. `params` has `:methodId`.")

  (handle-auth-logout [h conn params]
    "Respond to `auth/logout`. Only called when the agent advertised
    `authMethods`.")

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
    `PromptResponse` (`:messageId`). Processing and completion are reported
    separately via `session/update` notifications.")

  (on-session-cancel [h conn params]
    "Notification `session/cancel` for `:sessionId`. Stop foreground work and
    eventually emit an idle `state_update` with the `cancelled` stop reason.")

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
  {:name "clai"
   :title "clai agent"
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
        (throw-acp
         (acp-error err-protocol-ver
                    (default-error-messages err-protocol-ver)
                    {:requested protocolVersion
                     :supported (vec supported)})))))
  (handle-auth-login [_ _conn _params]
    (acp-error err-method))
  (handle-auth-logout [_ _conn _params]
    (acp-error err-method))
  (handle-session-new [_ _conn _params]
    (acp-error err-method))
  (handle-session-list [_ _conn _params]
    (acp-error err-method))
  (handle-session-delete [_ _conn _params]
    (acp-error err-method))
  (handle-session-resume [_ _conn _params]
    (acp-error err-method))
  (handle-session-close [_ _conn _params]
    (acp-error err-method))
  (handle-session-set-config-option [_ _conn _params]
    (acp-error err-method))
  (handle-session-prompt [_ _conn _params]
    (acp-error err-method))
  (on-session-cancel [_ _conn _params] nil)
  (on-cancel-request [_ _conn _params] nil)
  (on-notification [_ _conn _method _params] nil)
  (on-ext-request [_ _conn _method _params]
    (acp-error err-method)))

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
  {"initialize"               #'handle-initialize
   "auth/login"               #'handle-auth-login
   "auth/logout"              #'handle-auth-logout
   "session/new"              #'handle-session-new
   "session/list"             #'handle-session-list
   "session/delete"           #'handle-session-delete
   "session/resume"           #'handle-session-resume
   "session/close"            #'handle-session-close
   "session/set_config_option" #'handle-session-set-config-option
   "session/prompt"           #'handle-session-prompt})

(def ^:private request-method->params-schema
  "Maps each known request method to the malli schema for its `params`."
  {"initialize"                 initialize-params
   "session/new"                session-new-params
   "session/list"               session-list-params
   "session/delete"             session-id-params
   "session/resume"             session-resume-params
   "session/close"              session-id-params
   "session/prompt"             session-prompt-params})

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
             :or {in  (System/in)
                  out (System/out)}}]
   (let [^Reader   reader (cond-> in (instance? InputStream in) (io/reader))
         ^Writer   writer (cond-> out (instance? OutputStream out) (io/writer))]
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
      handler))))

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
;; Output: JSON-RPC framing
;; ---------------------------------------------------------------------------

(defn- clean
  "Drop top-level keys whose value is `nil`, so omitted fields stay off the
  wire. Nested maps are left untouched."
  [m]
  (if (map? m)
    (into {} (remove (comp nil? val)) m)
    m))

(defn- write-message
  "Encode `obj` as a single-line JSON document, write it to stdout followed by
  a newline, and flush. Serialized through `out-lock` so concurrent writers
  never interleave bytes."
  [{:keys [^Writer out ^Object out-lock]} obj]
  (locking out-lock
    (.write out (json/generate-string (clean obj)))
    (.write out "\n")
    (.flush out)))

(defn- response-envelope
  [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- error-envelope
  [id ^AcpError err]
  {:jsonrpc "2.0"
   :id id
   :error (cond-> {:code (.-code err) :message (.-message err)}
            (some? (.-data err)) (assoc :data (.-data err)))})

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
              (acp-error? err) err
              (vector? err)    (apply acp-error err)
              :else            (acp-error err-internal))]
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
                       {:acp/error (acp-error err-internal "ACP request timed out")}))

       (:error envelope)
       (throw (ex-info (-> envelope :error :message)
                       {:acp/error (->AcpError
                                     (-> envelope :error :code)
                                     (-> envelope :error :message)
                                     (-> envelope :error :data))}))

       :else (:result envelope)))))

;; ---------------------------------------------------------------------------
;; Convenience senders for common agent -> client messages
;; ---------------------------------------------------------------------------

(defn send-session-update
  "Send a `session/update` notification carrying one `SessionUpdate` for
  `session-id`. `update` is the full update map including its `:sessionUpdate`
  discriminator (e.g. `{:sessionUpdate \"agent_message\" :messageId ...
  :role \"agent\" :content [...]}`)."
  [conn sid update]
  (validate! session-id sid "send-session-update sessionId")
  (validate! session-update update "send-session-update update")
  (send-notification conn "session/update"
                     {:sessionId sid :update update}))

(defn request-permission
  "Send a `session/request_permission` request and return its promise. Blocks
  the caller only if they `await-request` the result. `opts` is the
  `RequestPermissionRequest` body (`:sessionId`, `:title`, `:options`,
  optional `:description`/`:subject`)."
  ([conn opts]
   (validate! request-permission-opts opts "request-permission")
   (send-request conn "session/request_permission" opts)))

(defn create-elicitation
  "Send an `elicitation/create` request and return its promise. `opts` is the
  `CreateElicitationRequest` body."
  ([conn opts]
   (validate! create-elicitation-opts opts "create-elicitation")
   (send-request conn "elicitation/create" opts)))

(defn complete-elicitation
  "Send an `elicitation/complete` notification for `elicitation-id`."
  [conn elicitation-id]
  (validate! non-empty-string elicitation-id "complete-elicitation elicitationId")
  (send-notification conn "elicitation/complete"
                     {:elicitationId elicitation-id}))

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
      (validate! schema params (str "params for " method)))
    (@handler-fn-var (:handler conn) conn params)
    (catch clojure.lang.ExceptionInfo e
      (if (-> (ex-data e) :errors)
        (acp-error err-params
                   (default-error-messages err-params)
                   (-> (ex-data e) :errors))
        (ex->acp-error e)))
    (catch Throwable e
      (ex->acp-error e))))

(defn- resolve-result
  "Normalize a handler return value into either `{:result ...}` or
  `{:error ...}`."
  [value]
  (if (acp-error? value)
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
                              (catch Throwable e (ex->acp-error e)))))]
                     (if (:error result-map)
                       (send-error conn id (:error result-map))
                       (send-response conn id (:result result-map))))
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
                     ;; Notifications get no response; log to stderr.
                     (.println System/err
                               (str "clai.acp: notification handler '"
                                    method "' threw: "
                                    (ex-message e))))))]
    (.submit ^ExecutorService executor ^Runnable run-fn))
  nil)

(defn- handle-incoming-response
  "Resolve a pending outbound request promise with its response envelope."
  [{:keys [pending]} id envelope]
  (when-let [promise (get @pending id)]
    (swap! pending dissoc id)
    (deliver promise envelope))
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
      (write-message conn (error-envelope nil (acp-error err-invalid))))))

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
             (catch Throwable e (ex->acp-error e)))))]
    (if (:error result-map)
      (error-envelope id (:error result-map))
      (response-envelope id (:result result-map)))))

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
                            (write-message conn responses))))]
    (doseq [entry entries]
      (cond
        (not (map? entry))
        (swap! response-futs conj
               (future (error-envelope nil (acp-error err-invalid))))

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
               (future (error-envelope nil (acp-error err-invalid))))))
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
      (write-message conn (error-envelope nil (acp-error err-invalid)))
      :else
      (process-batch conn parsed))

    (map? parsed)
    (process-entry conn parsed)

    :else
    (write-message conn (error-envelope nil (acp-error err-invalid)))))

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
                  (write-message conn
                                 (error-envelope nil (acp-error err-parse)))))))
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