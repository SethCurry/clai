(ns clai.daemon.http
  "HTTP and WebSocket daemon for the `clai` web UI.

  The browser cannot speak nREPL's bencode transport, so this namespace
  serves two things from the same JVM as the agent:

    * a JSON HTTP API for sessions, agent configurations, plugins, the
      registered git repositories and worktrees, notes, session-scoped
      filesystem reads, and introspection of this JVM's Clojure namespaces
      (`/api/sessions`, `/api/agent-configs`, `/api/plugins`,
      `/api/git-repos`, `/api/git-worktrees`, `/api/notes`,
      `/api/notes/search`, `/api/sessions/<id>/completions`,
      `/api/sessions/<id>/files`, `/api/sessions/<id>/file`,
      `/api/namespaces`, `/api/namespaces/<ns>/vars`,
      `/api/namespaces/<ns>/var`, `/api/health`),
      and
    * a WebSocket at `/ws` that streams agent updates and forwards Clojure
      evaluations to the running nREPL server.

  It also serves the built React application from `web/dist` (configurable via
  [[start-server!]]'s `:root` option), so `clai web` can host both the API and
  the UI from one port. During development the Vite dev server proxies `/api`
  and `/ws` to this daemon.

  Agent operations run in-process through `clai.agent.core`; the nREPL
  connection is used only for the REPL tab. A prompt runs in a background
  future and its event updates are pushed to the socket as they happen."
  (:require [cheshire.core :as json]
            [clai.agent.core :as agent]
            [clai.agent.providers.ollama.prompt :as ollama-prompt]
            [clai.daemon.files :as files]
            [clai.daemon.namespaces :as namespaces]
            [clai.daemon.nrepl :as nrepl-bridge]
            [clai.events.schema :as events]
            [clai.log]
            [clai.plugins :as plugins]
            [clai.repl :as repl]
            [clai.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ring.adapter.jetty :as jetty]
            [ring.util.codec :as codec]
            [ring.util.mime-type :as mime]
            [ring.util.response :as response]
            [ring.websocket :as ws]
            [taoensso.telemere :as tel])
  (:import [java.io File]
           [java.time Instant]
           [java.util Date]
           [java.util.regex Pattern]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def StartOptions
  "Options accepted by [[start-server!]]."
  [:map
   [:host {:optional true} :string]
   [:port {:optional true} [:int {:min 0 :max 65535}]]
   [:root {:optional true} [:or :string [:fn #(instance? File %)]]]
   [:nrepl-port {:optional true} [:maybe [:int {:min 1 :max 65535}]]]
   [:agent {:optional true} [:maybe :map]]])

(def State
  "Shared server state passed to the Ring handler."
  [:map
   [:agent :map]
   [:root [:fn #(instance? File %)]]
   [:nrepl-port [:maybe [:int {:min 1 :max 65535}]]]
   [:connections [:fn #(instance? clojure.lang.Atom %)]]
   [:prompts [:fn #(instance? clojure.lang.Atom %)]]])

(def Connection
  "Per-WebSocket connection state. Tests can supply a fake `:send` function
  instead of a real socket."
  [:map
   [:send fn?]
   [:nrepl [:fn #(instance? clojure.lang.Atom %)]]
   [:prompt-running [:fn #(instance? clojure.lang.Atom %)]]
   [:socket {:optional true} :any]])

;; ---------------------------------------------------------------------------
;; JSON helpers
;; ---------------------------------------------------------------------------

(defn- json-safe
  "Recursively make `value` safe for `cheshire.core/generate-string`.

  Agent tool events carry `java.time.Instant` values and nREPL responses can
  contain sets, neither of which Cheshire encodes by default."
  [value]
  (cond
    (nil? value)                    nil
    (or (string? value)
        (number? value)
        (boolean? value))           value
    (instance? Instant value)       (str value)
    (instance? Date value)          (str (.toInstant ^Date value))
    (keyword? value)                (name value)
    (symbol? value)                 (str value)
    (map? value)                    (into {}
                                          (map (fn [[k v]]
                                                 [(if (keyword? k) (name k) (str k))
                                                  (json-safe v)]))
                                          value)
    (coll? value)                   (mapv json-safe value)
    :else                           (str value)))

(defn- ->json
  "Encode `value` as a JSON string."
  [value]
  (json/generate-string (json-safe value)))

(defn- json-response
  "Build a Ring response carrying `body` as JSON."
  ([status body] (json-response status body {}))
  ([status body headers]
   {:status  status
    :headers (merge {"content-type" "application/json; charset=utf-8"
                     "cache-control" "no-store"}
                    headers)
    :body    (->json body)}))

(defn- loopback-origin?
  "True when `request` is a browser request from a loopback origin.

  Browsers do not apply the same-origin policy to WebSockets, so the daemon
  must check the Origin header itself. Non-browser clients (which send no
  Origin) are allowed, and the Vite dev server is allowed because it runs on
  loopback."
  {:malli/schema [:-> :map :boolean]}
  [request]
  (if-let [origin (get-in request [:headers "origin"])]
    (try
      (contains? #{"localhost" "127.0.0.1" "::1" "[::1]"}
                 (some-> (.getHost (java.net.URI. origin)) str/lower-case))
      (catch Throwable _ false))
    true))

(defn- read-json-body
  "Read `request`'s body as a JSON object, returning `{}` for an empty body.
  Throws an `ex-info` when the body is not a JSON object."
  [request]
  (let [text (some-> (:body request) slurp)
        value (if (str/blank? text)
                {}
                (json/parse-string text true))]
    (if (map? value)
      value
      (throw (ex-info "Request body must be a JSON object"
                      {:body value})))))

(defn- query-params
  "Decode `request`'s query string into a `{\"key\" \"value\"}` map."
  [request]
  (codec/form-decode (or (:query-string request) "")))

(defn- parse-limit
  "Parse a `limit` query parameter, returning nil when it is absent or not a
  number so [[files/list-entries]] falls back to its default."
  [value]
  (when (string? value)
    (try
      (parse-long (str/trim value))
      (catch Throwable _ nil))))

;; ---------------------------------------------------------------------------
;; Session helpers
;; ---------------------------------------------------------------------------

(defn- session-summary
  "The public shape of a session: identifiers, title, cwd, and selected
  config, without history."
  [session]
  (select-keys session [:sessionId :title :cwd :configId :createdAt]))

(defn- session-payload
  "Load `session-id` and return the summary plus the event updates a chat view
  should replay. Returns nil when the session does not exist.

  The session's REPL namespace (`sessions.<id>`) is created or refreshed as a
  side effect, so the web REPL can evaluate in the namespace attached to the
  session."
  [agent session-id]
  (when-let [session (agent/load-session! agent {:sessionId (str session-id)})]
    (repl/namespace-builder {:session (select-keys session [:sessionId :cwd])})
    (assoc (session-summary session)
           :updates (ollama-prompt/history->updates (:history session)))))

(defn- list-sessions
  "List sessions for the UI."
  [agent]
  (:sessions (agent/list-sessions! agent {})))

(defn- list-agent-configs
  "List the stored agent configurations for the UI."
  [agent]
  (:configs (agent/list-agent-configs! agent {})))

(defn- list-plugins
  "List the installed plugins for the UI's plugin picker."
  [agent]
  (:plugins (agent/list-plugins! agent {})))

(defn- completions
  "Resolve `request`'s `path` and `limit` query parameters into the path
  completion entries the composer offers, relative to `cwd`."
  [cwd request]
  (let [{:strs [path limit]} (query-params request)]
    (files/list-entries {:root  cwd
                         :path  path
                         :limit (parse-limit limit)})))

(defn- directory-listing
  "Resolve `request`'s `path` and `limit` query parameters into the directory
  listing the file explorer shows.

  Unlike [[completions]] this always lists dotfiles: an explorer that hid
  `.github` or `.clai` would be lying about the tree."
  [cwd request]
  (let [{:strs [path limit]} (query-params request)]
    (files/list-entries {:root            cwd
                         :path            path
                         :limit           (parse-limit limit)
                         :include-hidden? true})))

(defn- file-preview
  "Read the file named by `request`'s `path` query parameter, relative to
  `cwd`. Returns nil when `cwd` holds no readable file at that path."
  [cwd request]
  (files/read-file {:root cwd :path (get (query-params request) "path")}))

;; ---------------------------------------------------------------------------
;; WebSocket message handling
;; ---------------------------------------------------------------------------

(defn send!
  "Send `message` to the WebSocket connection `conn` as JSON.

  Sending on a closed socket is a no-op: prompts may outlive the tab that
  started them, and that should not fail the agent loop."
  {:malli/schema [:-> #'Connection :map :nil]}
  [conn message]
  (try
    ((:send conn) message)
    (catch Throwable e
      (tel/log! {:level :debug
                 :id    ::websocket-send-failed
                 :data  {:error (util/error-message e)}}
                "Could not send WebSocket message")))
  nil)

(defn- send-error!
  "Send an error response, echoing `request-id` when the request carried one."
  [conn request-id message]
  (send! conn (cond-> {:type "error" :message (str message)}
                (some? request-id) (assoc :requestId request-id))))

(defn- send-update!
  "Send one agent event update for `session-id`."
  [conn session-id update]
  (send! conn {:type      "update"
               :sessionId (str session-id)
               :update    update}))

(defn- ensure-nrepl!
  "Return `conn`'s nREPL connection, opening one on first use.

  Throws an `ex-info` when the daemon was started without an nREPL port."
  [state conn]
  (or @(:nrepl conn)
      (if-let [port (:nrepl-port state)]
        (let [connection (nrepl-bridge/connect! {:port port})]
          (reset! (:nrepl conn) connection)
          connection)
        (throw (ex-info "This daemon has no nREPL server configured"
                        {:clai/no-nrepl true})))))

(defn- claim-prompt!
  "Atomically reserve `session-id` in the global `prompts` set.

  Returns true when this caller acquired the session, false when another
  WebSocket is already prompting the same session."
  {:malli/schema [:-> [:fn #(instance? clojure.lang.Atom %)] :string :boolean]}
  [prompts session-id]
  (let [claimed? (volatile! false)]
    (swap! prompts
           (fn [running]
             (if (contains? running session-id)
               (do (vreset! claimed? false) running)
               (do (vreset! claimed? true) (conj running session-id)))))
    @claimed?))

(defn- run-prompt!
  "Start a prompt on `session-id` in a background future.

  The user prompt and every agent update are streamed to the socket. Only one
  prompt may run per WebSocket, and only one prompt may run per session across
  all WebSockets, so reconnects and multiple tabs cannot interleave history."
  [state conn request-id session-id prompt]
  (let [session-id (str session-id)
        prompts    (:prompts state)]
    (cond
      (not (compare-and-set! (:prompt-running conn) false true))
      (send-error! conn request-id
                   "A prompt is already running for this connection")

      (not (claim-prompt! prompts session-id))
      (do
        (reset! (:prompt-running conn) false)
        (send-error! conn request-id
                     "A prompt is already running for this session"))

      :else
      (do
        (send-update! conn session-id (events/text-event "user_prompt" prompt))
        (future
          (try
            (let [result (agent/run-prompt! (:agent state)
                                            {:sessionId session-id
                                             :prompt    prompt
                                             :on-update #(send-update! conn
                                                                       session-id
                                                                       %)})]
              (send! conn {:type      "prompt_done"
                           :requestId request-id
                           :sessionId session-id
                           :result    result}))
            (catch Throwable e
              (tel/error! {:id   ::prompt-failed
                           :data {:sessionId session-id}}
                          e)
              (send-error! conn request-id
                           (str "Prompt failed: " (util/error-message e))))
            (finally
              (swap! prompts disj session-id)
              (reset! (:prompt-running conn) false))))))))

(defn- handle-eval!
  "Evaluate `code` through nREPL and stream each response to `conn`."
  [state conn request-id code ns]
  (let [connection (ensure-nrepl! state conn)]
    (doseq [response (nrepl-bridge/eval! connection code ns)]
      (send! conn {:type      "eval"
                   :requestId request-id
                   :response  response}))
    (send! conn {:type "eval_done" :requestId request-id})))

(defn handle-message!
  "Handle one JSON WebSocket message from the browser.

  Client messages are:

      {\"type\": \"list_sessions\"}
      {\"type\": \"new_session\", \"cwd\": \"/path\", \"configId\": \"3\"}
      {\"type\": \"load_session\", \"sessionId\": \"12\"}
      {\"type\": \"rename_session\", \"sessionId\": \"12\", \"title\": \"Fix the build\"}
      {\"type\": \"delete_session\", \"sessionId\": \"12\"}
      {\"type\": \"list_agent_configs\"}
      {\"type\": \"load_agent_config\", \"configId\": \"3\"}
      {\"type\": \"load_agent_config\", \"name\": \"fast\"}
      {\"type\": \"list_plugins\"}
      {\"type\": \"prompt\", \"sessionId\": \"12\", \"prompt\": \"...\"}
      {\"type\": \"eval\", \"code\": \"(+ 1 1)\", \"ns\": \"user\"}

  Every response echoes the request's optional `requestId`. Prompt updates
  arrive separately as `update` messages so a chat view can render them as
  they happen."
  {:malli/schema [:-> #'State #'Connection :any :nil]}
  [state conn raw]
  (try
    (let [{:keys [type requestId cwd sessionId configId name title prompt code ns]
           :as _message}
          (if (string? raw)
            (json/parse-string raw true)
            (throw (ex-info "WebSocket messages must be text" {})))]
      (case (str type)
        "list_sessions"
        (send! conn {:type      "sessions"
                     :requestId requestId
                     :sessions  (list-sessions (:agent state))})

        "list_agent_configs"
        (send! conn {:type      "agent_configs"
                     :requestId requestId
                     :configs   (list-agent-configs (:agent state))})

        "list_plugins"
        (send! conn {:type      "plugins"
                     :requestId requestId
                     :plugins   (list-plugins (:agent state))})

        "load_agent_config"
        (if-let [config (agent/load-agent-config! (:agent state)
                                                  {:configId configId
                                                   :name     name})]
          (send! conn {:type      "agent_config"
                       :requestId requestId
                       :config    config})
          (send-error! conn requestId "No matching agent configuration"))

        "new_session"
        (let [{:keys [sessionId]} (agent/new-session! (:agent state)
                                                      {:cwd      (some-> cwd str)
                                                       :configId (some-> configId str)})]
          (send! conn {:type      "session"
                       :requestId requestId
                       :session   (session-payload (:agent state) sessionId)}))

        "load_session"
        (if-let [session (session-payload (:agent state) sessionId)]
          (send! conn {:type    "session"
                       :requestId requestId
                       :session session})
          (send-error! conn requestId (str "No session " sessionId)))

        "delete_session"
        (do
          (agent/delete-session! (:agent state) {:sessionId (str sessionId)})
          (send! conn {:type      "deleted"
                       :requestId requestId
                       :sessionId (str sessionId)}))

        "rename_session"
        (if (and (string? sessionId) (or (nil? title) (string? title)))
          (if-let [session (agent/rename-session! (:agent state)
                                                  {:sessionId (str sessionId)
                                                   :title     title})]
            (send! conn {:type      "session_renamed"
                         :requestId requestId
                         :sessionId (:sessionId session)
                         :session   session})
            (send-error! conn requestId (str "No session " sessionId)))
          (send-error! conn requestId
                       "rename_session requires sessionId and an optional title"))

        "prompt"
        (if (and (string? sessionId) (string? prompt))
          (run-prompt! state conn requestId sessionId prompt)
          (send-error! conn requestId "prompt requires sessionId and prompt"))

        "eval"
        (if (string? code)
          (handle-eval! state conn requestId code ns)
          (send-error! conn requestId "eval requires code"))

        (send-error! conn requestId (str "Unknown message type: " type))))
    (catch Throwable e
      (tel/error! {:id ::websocket-message-failed} e)
      (send-error! conn nil (util/error-message e))))
  nil)

;; ---------------------------------------------------------------------------
;; WebSocket listener
;; ---------------------------------------------------------------------------

(defn- make-listener
  "Create the Ring WebSocket listener for one browser connection."
  [state]
  (let [conn (atom nil)]
    {:on-open
     (fn [socket]
       (let [connection {:socket         socket
                         :send           (fn [message]
                                           (when (ws/open? socket)
                                             (ws/send socket (->json message))))
                         :nrepl          (atom nil)
                         :prompt-running (atom false)}]
         (reset! conn connection)
         (swap! (:connections state) conj socket)
         (send! connection {:type       "hello"
                            :nreplPort  (:nrepl-port state)
                            :provider   (:provider (:agent state))
                            :model      (:model (:agent state))})))

     :on-message
     (fn [_socket raw]
       (when-let [connection @conn]
         (handle-message! state connection raw)))

     :on-error
     (fn [_socket throwable]
       (tel/error! {:id ::websocket-error} throwable))

     :on-close
     (fn [socket _code _reason]
       (when-let [connection @conn]
         (nrepl-bridge/close! @(:nrepl connection))
         (swap! (:connections state) disj socket)
         (reset! conn nil)))}))

;; ---------------------------------------------------------------------------
;; Static file serving
;; ---------------------------------------------------------------------------

(def ^:private static-index
  "The file served for `/` and for client-side routes."
  "index.html")

(defn- request-path
  "The path of `request`, without any query string."
  {:malli/schema [:-> :map :string]}
  [request]
  (or (:path-info request) (:uri request) "/"))

(defn- file-response
  "Serve a file below `root`, adding a cache-friendly content type.

  `ring.util.response/file-response` already rejects paths that escape the
  root."
  {:malli/schema [:-> [:fn #(instance? File %)] :string [:maybe :map]]}
  [root path]
  (when-let [resp (response/file-response path {:root (.getPath ^File root)})]
    (if (response/get-header resp "content-type")
      resp
      (response/content-type resp
                             (or (mime/ext-mime-type path)
                                 "application/octet-stream")))))

(defn- static-response
  "Serve the React build, falling back to `index.html` for client-side routes."
  {:malli/schema [:-> [:fn #(instance? File %)] :string :map]}
  [root uri]
  (let [path (if (= uri "/") static-index (subs uri 1))]
    (or (file-response root path)
        (when-not (or (str/includes? (last (str/split path #"/")) ".")
                      (str/starts-with? uri "/api")
                      (str/starts-with? uri "/assets/"))
          (file-response root static-index))
        (json-response 404 {:error "Not found"}))))

;; ---------------------------------------------------------------------------
;; HTTP routing
;; ---------------------------------------------------------------------------

(def Response
  "A Ring response, as returned by every route handler."
  [:map
   [:status :int]
   [:headers [:maybe :map]]
   [:body :any]])

(def RouteContext
  "What one route handler receives: the daemon [[State]], the Ring `request`,
  the `agent` it operates on, the path `params` captured by the route template,
  and the decoded `query` parameters."
  [:map
   [:state #'State]
   [:request :map]
   [:agent :map]
   [:params [:map-of :keyword :string]]
   [:query [:map-of :string :string]]])

(def CrudSpec
  "How [[crud-routes]] reaches one stored resource.

  `:id` names the path parameter (for example `:noteId`); every function
  receives the agent first and already-decoded string ids. `:label` is the
  human name used in 404 messages, and `:list` may filter on the query
  parameters."
  [:map
   [:path :string]
   [:id keyword?]
   [:label :string]
   [:list fn?]
   [:load fn?]
   [:create fn?]
   [:update fn?]
   [:delete fn?]])

(defn- not-found
  "A JSON 404 response carrying `message` as its `:error`."
  {:malli/schema [:-> :string #'Response]}
  [message]
  (json-response 404 {:error message}))

(defn- with-json-body
  "Call `f` with `request`'s decoded JSON body.

  A malformed body, and anything `f` throws while interpreting it, becomes a
  400 response: that is what an invalid payload or a rejected write means to
  the caller."
  {:malli/schema [:-> :map [:=> [:cat :map] :map] :map]}
  [request f]
  (try
    (f (read-json-body request))
    (catch Throwable e
      (json-response 400 {:error (util/error-message e)}))))

;; ---------------------------------------------------------------------------
;; Route handlers
;; ---------------------------------------------------------------------------

(defn- health-handler
  "Report the daemon's liveness, model, connection count, and database status."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [state agent]}]
  (let [base {:nreplPort   (:nrepl-port state)
              :provider    (:provider agent)
              :model       (:model agent)
              :connections (count @(:connections state))}]
    (try
      (json-response 200 (assoc base
                                :ok true
                                :sessions (count (list-sessions agent))))
      (catch Throwable e
        (json-response 503 (assoc base
                                  :ok false
                                  :database "error"
                                  :error (util/error-message e)))))))

(defn- sessions-handler
  "List every session, as summaries, for the UI's session picker."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [agent]}]
  (json-response 200 {:sessions (list-sessions agent)}))

(defn- create-session-handler
  "Create a session from the request body's `cwd` and `configId` and return its
  chat view payload."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [agent request]}]
  (with-json-body request
    (fn [{:keys [cwd configId]}]
      (let [{:keys [sessionId]} (agent/new-session! agent
                                                    {:cwd      (some-> cwd str)
                                                     :configId (some-> configId str)})]
        (json-response 201 (session-payload agent sessionId))))))

(defn- load-session-handler
  "Return one session's summary plus the updates a chat view should replay."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [agent params]}]
  (let [session-id (:sessionId params)]
    (if-let [session (session-payload agent session-id)]
      (json-response 200 session)
      (not-found (str "No session " session-id)))))

(defn- delete-session-handler
  "Delete the session named by the route."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [agent params]}]
  (let [session-id (:sessionId params)]
    (agent/delete-session! agent {:sessionId session-id})
    (json-response 200 {:deleted session-id})))

(defn- with-session-cwd
  "Call `f` with the working directory of the session named by the route's
  `:sessionId`, or answer 404 when the session is unknown."
  {:malli/schema [:-> #'RouteContext [:=> [:cat :string] :map] :map]}
  [{:keys [agent params]} f]
  (let [session-id (:sessionId params)]
    (if-let [cwd (agent/session-cwd! agent {:sessionId session-id})]
      (f cwd)
      (not-found (str "No session " session-id)))))

(defn- session-completions-handler
  "List the path completions offered for a session's working directory."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [ctx]
  (with-session-cwd ctx
    (fn [cwd]
      (json-response 200 (completions cwd (:request ctx))))))

(defn- session-files-handler
  "List one directory inside a session's working directory, dotfiles included."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [ctx]
  (with-session-cwd ctx
    (fn [cwd]
      (json-response 200 (directory-listing cwd (:request ctx))))))

(defn- session-file-handler
  "Preview one file inside a session's working directory."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [ctx]
  (with-session-cwd ctx
    (fn [cwd]
      (if-let [file (file-preview cwd (:request ctx))]
        (json-response 200 file)
        (not-found "No such file")))))

(defn- plugins-handler
  "List the installed plugins for the UI's plugin picker."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [agent]}]
  (json-response 200 {:plugins (list-plugins agent)}))

(defn- note-search-handler
  "Search notes by BM25 relevance using the `q` and `limit` query parameters."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [agent query]}]
  (let [q     (get query "q")
        limit (parse-limit (get query "limit"))]
    (if (str/blank? (str q))
      (json-response 400 {:error "A search query is required"})
      (json-response 200 (agent/search-notes! agent {:q q :limit limit})))))

(defn- namespaces-handler
  "List every namespace loaded in this JVM, for the namespace explorer."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [_ctx]
  (json-response 200 (namespaces/list-namespaces)))

(defn- namespace-vars-handler
  "List the public Vars of the namespace named by the route."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [params]}]
  (json-response 200 (namespaces/namespace-vars (:namespace params))))

(defn- namespace-var-handler
  "Return one Var's detail, named by the route's namespace and the `name`
  query parameter."
  {:malli/schema [:-> #'RouteContext #'Response]}
  [{:keys [params query]}]
  (let [namespace-name (:namespace params)
        var-name       (get query "name")]
    (if (str/blank? (str var-name))
      (json-response 400 {:error "A var name is required"})
      (if-let [detail (namespaces/var-detail namespace-name var-name)]
        (json-response 200 detail)
        (not-found (str "No var " namespace-name "/" var-name))))))

;; ---------------------------------------------------------------------------
;; Route construction
;; ---------------------------------------------------------------------------

(defn- crud-routes
  "Build the five conventional REST routes -- list, create, read, update,
  delete -- for one stored resource described by `spec`.

  This is where the resources' shared shape lives: their paths differ only by
  base and id parameter, and their 404 messages only by `:label`."
  {:malli/schema [:-> #'CrudSpec [:vector :map]]}
  [{:keys [path id label list load create update delete]}]
  (let [item-path (str path "/:" (name id))
        missing   (fn [resource-id]
                    (not-found (str "No " label " " resource-id)))]
    [{:method   :get
      :template path
      :handler  (fn [{:keys [agent query]}]
                  (json-response 200 (list agent query)))}

     {:method   :post
      :template path
      :handler  (fn [{:keys [agent request]}]
                  (with-json-body request
                    #(json-response 201 (create agent %))))}

     {:method   :get
      :template item-path
      :handler  (fn [{:keys [agent params]}]
                  (let [resource-id (get params id)]
                    (if-let [resource (load agent resource-id)]
                      (json-response 200 resource)
                      (missing resource-id))))}

     {:method   :put
      :template item-path
      :handler  (fn [{:keys [agent params request]}]
                  (let [resource-id (get params id)]
                    (with-json-body request
                      #(if-let [resource (update agent resource-id %)]
                         (json-response 200 resource)
                         (missing resource-id)))))}

     {:method   :delete
      :template item-path
      :handler  (fn [{:keys [agent params]}]
                  (let [resource-id (get params id)]
                    (if (delete agent resource-id)
                      (json-response 200 {:deleted resource-id})
                      (missing resource-id))))}]))

(defn- compile-route
  "Turn `route`'s `:template` into a `:regex` plus the keyword `:params` it
  captures.

  A template is a path like `/api/notes/:noteId`, where a `:name` segment
  captures exactly one (percent-encoded) path segment."
  {:malli/schema [:-> :map :map]}
  [route]
  (let [segments  (remove str/blank? (str/split (:template route) #"/"))
        capture?  (fn [segment] (str/starts-with? segment ":"))
        regex     (str "^"
                       (str/join "" (map (fn [segment]
                                           (str "/"
                                                (if (capture? segment)
                                                  "([^/]+)"
                                                  (Pattern/quote segment))))
                                         segments))
                       "$")]
    (assoc route
           :regex  (re-pattern regex)
           :params (into [] (comp (filter capture?)
                                  (map #(keyword (subs % 1))))
                         segments))))

(defn- find-route
  "The first route in `routes` that matches `request`, as `[route params]`,
  with the path captures percent-decoded. Nil when nothing matches."
  {:malli/schema [:-> [:vector :map] :map
                  [:maybe [:tuple :map [:map-of :keyword :string]]]]}
  [routes request]
  (let [method (:request-method request)
        path   (request-path request)]
    (some (fn [{:keys [regex params] :as route}]
            (when-let [captures (and (= method (:method route))
                                     (re-matches regex path))]
              [route (zipmap params (map codec/url-decode (rest captures)))]))
          routes)))

(def ^:private agent-config-spec
  "How the API stores agent configurations."
  {:path   "/api/agent-configs"
   :id     :configId
   :label  "agent configuration"
   :list   (fn [agent _query] {:configs (list-agent-configs agent)})
   :load   (fn [agent config-id]
             (agent/load-agent-config! agent {:configId config-id}))
   :create (fn [agent config]
             (agent/create-agent-config! agent config))
   :update (fn [agent config-id config]
             (agent/update-agent-config! agent (assoc config :configId config-id)))
   :delete (fn [agent config-id]
             (agent/delete-agent-config! agent {:configId config-id}))})

(def ^:private git-repo-spec
  "How the API stores git repositories."
  {:path   "/api/git-repos"
   :id     :repoId
   :label  "git repository"
   :list   (fn [agent _query] (agent/list-git-repos! agent {}))
   :load   (fn [agent repo-id] (agent/load-git-repo! agent {:repoId repo-id}))
   :create (fn [agent repo] (agent/create-git-repo! agent repo))
   :update (fn [agent repo-id repo]
             (agent/update-git-repo! agent (assoc repo :repoId repo-id)))
   :delete (fn [agent repo-id] (agent/delete-git-repo! agent {:repoId repo-id}))})

(def ^:private git-worktree-spec
  "How the API stores git worktrees. The listing filters on `?repoId=`."
  {:path   "/api/git-worktrees"
   :id     :worktreeId
   :label  "git worktree"
   :list   (fn [agent query]
             (agent/list-git-worktrees! agent {:repoId (get query "repoId")}))
   :load   (fn [agent worktree-id]
             (agent/load-git-worktree! agent {:worktreeId worktree-id}))
   :create (fn [agent worktree] (agent/create-git-worktree! agent worktree))
   :update (fn [agent worktree-id worktree]
             (agent/update-git-worktree! agent
                                        (assoc worktree :worktreeId worktree-id)))
   :delete (fn [agent worktree-id]
             (agent/delete-git-worktree! agent {:worktreeId worktree-id}))})

(def ^:private note-spec
  "How the API stores notes. The search route lives in [[special-routes]]
  instead, because it must be matched before `/api/notes/:noteId`."
  {:path   "/api/notes"
   :id     :noteId
   :label  "note"
   :list   (fn [agent _query] (agent/list-notes! agent {}))
   :load   (fn [agent note-id] (agent/load-note! agent {:noteId note-id}))
   :create (fn [agent note] (agent/create-note! agent note))
   :update (fn [agent note-id note]
             (agent/update-note! agent (assoc note :noteId note-id)))
   :delete (fn [agent note-id] (agent/delete-note! agent {:noteId note-id}))})

(def ^:private special-routes
  "The API routes that do not fit the CRUD shape [[crud-routes]] builds.

  They are listed before the generated resource routes, because the templates
  that capture a path segment would otherwise swallow a literal path
  (`/api/notes/search`) or a deeper route (`/api/sessions/:sessionId/files`)."
  [{:method :get :template "/api/health" :handler health-handler}
   {:method :get :template "/api/sessions" :handler sessions-handler}
   {:method :post :template "/api/sessions" :handler create-session-handler}
   {:method :get :template "/api/sessions/:sessionId/completions" :handler session-completions-handler}
   {:method :get :template "/api/sessions/:sessionId/files" :handler session-files-handler}
   {:method :get :template "/api/sessions/:sessionId/file" :handler session-file-handler}
   {:method :get :template "/api/sessions/:sessionId" :handler load-session-handler}
   {:method :delete :template "/api/sessions/:sessionId" :handler delete-session-handler}
   {:method :get :template "/api/plugins" :handler plugins-handler}
   {:method :get :template "/api/notes/search" :handler note-search-handler}
   {:method :get :template "/api/namespaces" :handler namespaces-handler}
   {:method :get :template "/api/namespaces/:namespace/vars" :handler namespace-vars-handler}
   {:method :get :template "/api/namespaces/:namespace/var" :handler namespace-var-handler}])

(def ^:private api-routes
  "The JSON API's routing table, first match wins. Literal paths and deeper
  templates come first so a capture template cannot swallow them."
  (into (mapv compile-route special-routes)
        (comp (mapcat crud-routes) (map compile-route))
        [agent-config-spec
         git-repo-spec
         git-worktree-spec
         note-spec]))

(defn- websocket-response
  "Upgrade `request` to a WebSocket listener, refusing browser origins outside
  loopback."
  {:malli/schema [:-> #'State :map :map]}
  [state request]
  (if (loopback-origin? request)
    {::ws/listener (make-listener state)}
    (json-response 403 {:error "WebSocket origin is not allowed"})))

(defn- fallback-response
  "Answer a path no route claimed: a JSON 404 under `/api/`, otherwise the
  static React build (or its 404)."
  {:malli/schema [:-> #'State :map :map]}
  [state request]
  (let [uri (request-path request)]
    (if (str/starts-with? uri "/api/")
      (json-response 404 {:error (str "No route for "
                                      (:request-method request) " " uri)})
      (static-response (:root state) uri))))

(defn- route
  "Dispatch one Ring request to the WebSocket, the API routing table, or the
  static UI."
  {:malli/schema [:-> #'State :map :map]}
  [state request]
  (let [uri (request-path request)]
    (cond
      (and (= uri "/ws") (ws/upgrade-request? request))
      (websocket-response state request)

      (and (= uri "/ws") (= :get (:request-method request)))
      (json-response 400 {:error "Expected a WebSocket upgrade"})

      :else
      (let [[route params] (find-route api-routes request)]
        (if route
          ((:handler route) {:state   state
                             :request request
                             :agent   (:agent state)
                             :params  params
                             :query   (query-params request)})
          (fallback-response state request))))))

(defn handler
  "Build the Ring handler for `state`.

  The return value is a plain Ring handler; tests can call it with request
  maps without starting Jetty."
  {:malli/schema [:-> #'State [:fn fn?]]}
  [state]
  (fn [request]
    (try
      ;; Ring's WebSocket responses are handled by the adapter, not written as
      ;; ordinary HTTP responses, so they are returned unchanged.
      (route state request)
      (catch Throwable e
        (tel/error! {:id   ::request-failed
                     :data {:method (:request-method request)
                            :uri    (request-path request)}}
                    e)
        (json-response 500 {:error (util/error-message e)})))))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defn- bound-port
  "The port Jetty actually bound to, useful when `:port` was 0."
  [^org.eclipse.jetty.server.Server server]
  (when server
    (let [^org.eclipse.jetty.server.ServerConnector connector
          (first (.getConnectors server))]
      (when connector
        (.getLocalPort connector)))))

(defn start-server!
  "Start the HTTP/WebSocket daemon and return `{:server ... :port ...}`.

  Options:

    :host        interface to bind (default `127.0.0.1`; use `0.0.0.0` to
                 expose it on the network, which is unsafe because the agent
                 can run arbitrary commands)
    :port        HTTP port (default 8080; 0 lets the OS choose)
    :root        directory containing the React build (default `web/dist`)
    :nrepl-port  port of the nREPL server the REPL tab should use
    :agent       agent map to use instead of `(agent/make-agent)`

  The server does not block; pass the returned `:server` to [[stop-server!]]."
  {:malli/schema [:-> [:maybe #'StartOptions] :map]}
  [{:keys [host port root nrepl-port agent]
    :or   {host "0.0.0.0" port 8080 root "web/dist"}}]
  (let [agent (or agent (agent/make-agent))
         _     (when-let [postgres (:postgres agent)]
                 (plugins/load-configured! postgres))
         state {:agent       agent
                :root        (.getCanonicalFile (io/file root))
                :nrepl-port  nrepl-port
                :connections (atom #{})
                :prompts     (atom #{})}
         server (jetty/run-jetty (handler state)
                                 {:host              host
                                  :port              port
                                  :join?             false
                                  :ws-idle-timeout   600000
                                  ;; Tool output can be large; the Ring
                                  ;; default would close the socket mid-stream.
                                  :ws-max-text-size  (* 16 1024 1024)
                                  :ws-max-binary-size (* 16 1024 1024)})]
     (tel/log! {:level :info
                :id    ::started
                :data  {:host host
                        :port (bound-port server)
                        :root (str (:root state))
                        :nrepl-port nrepl-port}}
               "Started clai HTTP daemon")
     {:server server
      :host   host
      :port   (bound-port server)
      :state  state}))

(defn stop-server!
  "Stop a server returned by [[start-server!]]."
  {:malli/schema [:-> :map :nil]}
  [{:keys [server]}]
  (when server
    (.stop ^org.eclipse.jetty.server.Server server))
  nil)
