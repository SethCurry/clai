(ns clai.repl
  "A REPL-friendly, in-process ACP client for the `clai` agent.

  The client and the agent are wired back to back with a pair of
  `acp.jsonrpc/QueueChannel`s: [[current-agent]] runs over one end (in a daemon
  thread) and the functions here speak JSON-RPC over the other. Nothing goes
  through stdio, so the agent can be driven from a REPL while stdout stays
  available for the conversation itself.

  The transport has two directions:

    * outbound requests (`session/new`, `session/prompt`, ...) are written to
      the agent's input queue;
    * inbound messages are taken off the client's input queue by a reader
      thread. Responses resolve the matching request promise; `session/update`
      notifications are put on a display queue that the waiting request drains
      and renders, so model text streams as it arrives.

  Typical use:

      (new!)                             ; start and select a session
      (prompt! \"summarize AGENTS.md\")    ; stream a reply
      (history!)                         ; print the conversation so far
      (resume! \"<session-id>\")           ; replay a previous conversation
      (disconnect!)                      ; stop the in-process agent

  [[current-session]] and [[current-agent]] are vars rather than atoms so a
  REPL can rebind or redefine them: `(with-redefs [current-agent my-agent]
  (new!))` drives a different handler."
  (:require [acp.agent :as acp]
            [acp.errors :as acp-errors]
            [acp.jsonrpc :as jsonrpc]
            [clai.agent :as agent]
            [clojure.string :as str])
  (:import [java.util.concurrent BlockingQueue LinkedBlockingQueue TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

;; ---------------------------------------------------------------------------
;; REPL state
;; ---------------------------------------------------------------------------

(def current-session
  "The session the helpers act on: a map carrying at least `:sessionId` (a bare
  id string is accepted too), or nil before [[new!]]/[[resume!]]."
  nil)

(def current-agent
  "The ACP agent [[connect!]] runs in-process and sends requests to. It is read
  when the connection is established, so redefining or rebinding it takes
  effect on the next connection."
  (agent/ollama-agent))

(def ^:private client-info
  "Implementation info advertised in the `initialize` request."
  {:name    "clai-repl"
   :title   "clai REPL"
   :version "0.1.0-SNAPSHOT"})

(defonce ^:private runtime
  ;; The live in-process connection, or nil: `{:client ... :handler ...
  ;; :from-agent ... :agent-thread ... :reader-thread ... :initialize ...}`.
  (atom nil))

(defn connected?
  "True when an in-process agent connection is live."
  []
  (some? @runtime))

(defn- default-cwd
  "The working directory used when a session is created without one."
  []
  (str (System/getProperty "user.dir")))

(defn- session-id
  "Read a session id out of a session map or a bare id string."
  [session]
  (cond
    (nil? session)  nil
    (map? session)  (some-> (:sessionId session) str)
    :else           (str session)))

(defn current-session-id
  "The id of [[current-session]], or nil when no session is selected."
  []
  (session-id current-session))

;; ---------------------------------------------------------------------------
;; Inbound dispatch
;; ---------------------------------------------------------------------------

(def ^:private closed-message
  "Sentinel the reader thread puts on the display queue when the connection
  closes, so a waiting request can stop instead of blocking forever."
  ::closed)

(def ^:private poll-ms
  "How long a waiting request blocks on the display queue before checking
  whether its response has arrived."
  20)

(defn- deliver-response!
  "Resolve the pending request promise for a response envelope."
  [{:keys [pending]} {:keys [id] :as msg}]
  (when-let [response (get @pending id)]
    (swap! pending dissoc id)
    (deliver response
             (if (contains? msg :error)
               {:error (:error msg)}
               {:result (:result msg)}))))

(defn- answer-agent-request!
  "Answer a request the agent made of this client.

  The clai agent only issues `session/request_permission`, and this REPL has no
  prompt for it, so it is declined; every other client-side method is answered
  with `method_not_found` so the agent is never left waiting on us."
  [client {:keys [id method]}]
  (case method
    "session/request_permission"
    (jsonrpc/send-response (:channel client) id
                           {:outcome {:outcome "cancelled"}})

    (jsonrpc/send-error (:channel client) id
                        (acp-errors/acp-error acp-errors/err-method))))

(defn- dispatch!
  "Route one inbound message: requests get an answer, responses resolve their
  promise, and notifications go on the display queue."
  [{:keys [inbox] :as client} msg]
  (cond
    (and (contains? msg :id) (contains? msg :method))
    (answer-agent-request! client msg)

    (contains? msg :id)
    (deliver-response! client msg)

    (contains? msg :method)
    (.put ^BlockingQueue inbox msg)

    :else nil))

(defn- read-loop!
  "Take inbound messages off the client channel until it closes."
  [{:keys [inbox] :as client}]
  (try
    (loop []
      (when-let [msg (jsonrpc/take! (:channel client))]
        (dispatch! client msg)
        (recur)))
    (catch Throwable _
      ;; A closed transport ends the loop; the sentinel below unblocks waiters.
      nil)
    (finally
      (.put ^BlockingQueue inbox closed-message))))

;; ---------------------------------------------------------------------------
;; Rendering session updates
;; ---------------------------------------------------------------------------

(def ^:private chunk-labels
  "Streamed update kinds and the label each is printed under."
  {"user_message_chunk"  "you"
   "agent_message_chunk" "clai"
   "agent_thought_chunk" "thinking"})

(defn- content-text
  "Flatten ACP content (a block, a string, a vector of either) to plain text."
  [content]
  (cond
    (nil? content)        ""
    (string? content)     content
    (keyword? content)    (name content)
    (map? content)        (or (when (contains? content :text)
                                (str (:text content)))
                              (when (contains? content :content)
                                (content-text (:content content)))
                              (when (contains? content :type)
                                (str "[" (:type content) "]"))
                              (pr-str content))
    (sequential? content) (str/join "\n"
                                    (remove str/blank?
                                            (map content-text content)))
    :else                 (str content)))

(defn- close-stream!
  "Terminate the currently open streamed chunk, if any."
  [stream]
  (when (:open @stream)
    (println)
    (flush))
  (reset! stream {}))

(defn- render-chunk!
  "Print one streamed content chunk, opening a new labelled line when the
  update kind changes."
  [stream kind update]
  (when (not= (:open @stream) kind)
    (close-stream! stream)
    (print (str (get chunk-labels kind kind) "> "))
    (swap! stream assoc :open kind))
  (print (content-text (:content update)))
  (flush))

(defn- render-plan!
  "Print the entries of a `plan` update."
  [{:keys [entries]}]
  (doseq [{:keys [content status]} entries]
    (println (str "  [" (name (or status "pending")) "] " content))))

(defn- render-tool-call!
  "Print the announcement of a tool call."
  [{:keys [title name]}]
  (println (str "  [tool] " (or title name "tool"))))

(def ^:private terminal-tool-statuses
  "Tool call statuses that mean the call will not report any more output."
  #{"completed" "failed"})

(defn- render-tool-call-update!
  "Print a tool call's result once it reaches a terminal status.

  Intermediate updates carry the output collected so far, and ACP treats that
  output as a replacement, so printing only the terminal update avoids
  repeating a long-running tool's output on every chunk."
  [{:keys [toolCallId status content]}]
  (when (contains? terminal-tool-statuses status)
    (println (str "  [tool] " toolCallId " " status))
    (doseq [line (remove str/blank? (str/split-lines (content-text content)))]
      (println (str "     " line)))))

(defn- render!
  "Render one inbound notification on `*out*`."
  [stream msg]
  (let [{:keys [method params]} msg]
    (if (not= method "session/update")
      (do
        (close-stream! stream)
        (println (str "  [" method "] " (pr-str params))))
      (let [{:keys [update]} params
            kind (:sessionUpdate update)]
        (cond
          (contains? chunk-labels kind)
          (render-chunk! stream kind update)

          (= "tool_call" kind)
          (do (close-stream! stream) (render-tool-call! update))

          (= "tool_call_update" kind)
          (do (close-stream! stream) (render-tool-call-update! update))

          (= "plan" kind)
          (do (close-stream! stream) (render-plan! update))

          :else
          (do (close-stream! stream)
              (println (str "  [" (or kind "update") "] "
                            (pr-str update)))))
        ))))

(defn- drain-display!
  "Render every notification already queued for display."
  [client stream]
  (loop []
    (when-let [msg (.poll ^BlockingQueue (:inbox client))]
      (when-not (= closed-message msg)
        (render! stream msg))
      (recur))))

(defn- await-envelope!
  "Block until `response` resolves, rendering queued notifications meanwhile.

  Returns the response envelope (`{:result ...}` or `{:error ...}`)."
  [client response stream]
  (loop []
    (when-let [msg (.poll ^BlockingQueue (:inbox client)
                           poll-ms TimeUnit/MILLISECONDS)]
      (if (= closed-message msg)
        (throw (ex-info "The clai agent connection closed before it responded."
                        {:clai/closed true}))
        (render! stream msg)))
    (let [envelope (deref response 0 ::waiting)]
      (if (identical? envelope ::waiting)
        (recur)
        (do
          (drain-display! client stream)
          envelope)))))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defn- request!
  "Send a request to the agent, render the updates it sends while working, and
  return its result. Throws `ex-info` carrying `:acp/error` on an error
  response."
  ([client method] (request! client method nil))
  ([client method params]
   (let [stream   (atom {})
         response (jsonrpc/send-request client method params)]
     (try
       (let [envelope (await-envelope! client response stream)]
         (if-let [err (:error envelope)]
           (throw (ex-info (str method " failed: "
                                (or (:message err) "unknown error"))
                           {:acp/error err :method method}))
           (:result envelope)))
       (finally
         (close-stream! stream))))))

(defn connect!
  "Connect to [[current-agent]], starting it in a daemon thread on first use,
  and return the client connection. Idempotent while connected.

  The optional `handler` is an `acp.agent/AgentHandler` to run instead of
  [[current-agent]]; it is used only when a new connection is created."
  ([] (connect! (deref #'current-agent)))
  ([handler]
   (or (:client @runtime)
       (let [to-agent      (LinkedBlockingQueue.)
             from-agent    (LinkedBlockingQueue.)
             client        {:channel   (jsonrpc/new-queue-channel
                                        {:input from-agent :output to-agent})
                            :next-id   (AtomicLong. 1)
                            :pending   (atom {})
                            :inbox     (LinkedBlockingQueue.)}
             agent-channel (jsonrpc/new-queue-channel
                            {:input to-agent :output from-agent})
             agent-thread  (doto (Thread.
                                  ^Runnable #(acp/run handler
                                                      {:channel agent-channel})
                                  "clai-repl-agent")
                             (.setDaemon true)
                             (.start))
             reader-thread (doto (Thread. ^Runnable #(read-loop! client)
                                          "clai-repl-client")
                             (.setDaemon true)
                             (.start))
             handshake-params {:protocolVersion    acp/protocol-version-2
                               :clientInfo         client-info
                               :clientCapabilities {}}
             handshake     (try
                             (request! client "initialize" handshake-params)
                             (catch Throwable e
                               ;; Don't leak the threads when the agent rejects
                               ;; the handshake.
                               (jsonrpc/send! (:channel client) nil)
                               (.put ^BlockingQueue from-agent jsonrpc/eof)
                               (throw e)))]
         (reset! runtime {:client        client
                          :handler       handler
                          :from-agent    from-agent
                          :agent-thread  agent-thread
                          :reader-thread reader-thread
                          :initialize    handshake})
         (:client @runtime)))))

(defn disconnect!
  "Stop the in-process agent and close the connection. Safe to call when
  already disconnected; returns true when it closed a connection."
  []
  (when-let [{:keys [client from-agent agent-thread reader-thread]} @runtime]
    (reset! runtime nil)
    ;; Closing the client channel ends the agent's reader loop; the eof on the
    ;; client's input queue ends ours.
    (jsonrpc/send! (:channel client) nil)
    (.put ^BlockingQueue from-agent jsonrpc/eof)
    (some-> ^Thread agent-thread (.join 2000))
    (some-> ^Thread reader-thread (.join 2000))
    true))

;; ---------------------------------------------------------------------------
;; REPL helpers
;; ---------------------------------------------------------------------------

(defn- ->content-blocks
  "Normalize `content` to a vector of ACP content blocks.

  Strings and other scalars become text blocks; content blocks and sequential
  collections of them are passed through (flattened one level per nesting)."
  [content]
  (cond
    (nil? content)         []
    (string? content)      [{:type "text" :text content}]
    (and (map? content) (contains? content :type))
    [content]
    (map? content)         [{:type "text" :text (content-text content)}]
    (sequential? content)  (into [] (mapcat ->content-blocks) content)
    :else                  [{:type "text" :text (str content)}]))

(defn- latest-session
  "The most recently updated session reported by `session/list`, or nil."
  [client]
  (let [{:keys [sessions]} (request! client "session/list" {})]
    (last (sort-by #(str (:updatedAt %)) sessions))))

(defn- load-session-method
  "The request method to restore a session with: `session/load` replays the
  conversation, `session/resume` is the fallback when the agent cannot load."
  []
  (if (get-in @runtime [:initialize :capabilities :loadSession])
    "session/load"
    "session/resume"))

(defn new!
  "Start a new agent session and make it the [[current-session]].

  `opts` is a map with optional `:cwd` (defaults to the JVM's working
  directory) and `:mcpServers` (defaults to none); a bare string is read as the
  working directory. Returns the new session map."
  ([] (new! nil))
  ([opts]
   (let [opts     (if (string? opts) {:cwd opts} opts)
         client   (connect!)
         cwd      (str (or (:cwd opts) (default-cwd)))
         response (request! client "session/new"
                            {:cwd        cwd
                             :mcpServers (vec (or (:mcpServers opts) []))})
         session  (assoc response :cwd cwd)]
     (alter-var-root #'current-session (constantly session))
     session)))

(defn resume!
  "Resume an existing session, replaying its history to the display, and make
  it the [[current-session]].

  With no argument this resumes [[current-session]] when one is selected, and
  otherwise the most recently updated session the agent lists. An argument may
  be a session id or a map with `:sessionId` and optionally `:cwd`. Returns the
  resumed session map."
  ([] (resume! nil))
  ([arg]
   (let [client  (connect!)
         opts    (cond
                   (nil? arg)  {}
                   (map? arg)  arg
                   :else       {:sessionId (str arg)})
         chosen  (or (:sessionId opts) (current-session-id))
         summary (when-not chosen (latest-session client))
         id      (or chosen (:sessionId summary))]
     (when (nil? id)
       (throw (ex-info "No session to resume; pass a session id."
                       {:clai/no-session true})))
     (let [cwd      (str (or (:cwd opts) (:cwd summary) (default-cwd)))
           response (request! client (load-session-method)
                              {:sessionId  (str id)
                               :cwd        cwd
                               :mcpServers []})
           session  (assoc response :sessionId (str id) :cwd cwd)]
       (alter-var-root #'current-session (constantly session))
       session))))

(defn history!
  "Print the history of the current session.

  ACP has no dedicated history request: an agent replays a session's messages
  as `session/update` notifications when the session is loaded. This sends
  `session/load` for [[current-session]] and renders the replayed conversation
  without changing the selected session. It requires an agent that advertises
  `loadSession` support.

  With no argument this reads [[current-session]]. An argument may be a
  session id or a map with `:sessionId` and optionally `:cwd` to read another
  session's history. Returns the `session/load` result."
  ([] (history! nil))
  ([arg]
   (let [client (connect!)
         opts   (cond
                  (nil? arg) {}
                  (map? arg) arg
                  :else      {:sessionId (str arg)})
         id     (or (:sessionId opts) (current-session-id))]
     (when (nil? id)
       (throw (ex-info "No current session; call (new!) or (resume! ...) first."
                       {:clai/no-session true})))
     (when-not (get-in @runtime [:initialize :capabilities :loadSession])
       (throw (ex-info "This agent cannot replay history (no loadSession)."
                       {:clai/no-history true})))
     (request! client "session/load"
               (cond-> {:sessionId (str id)}
                 (some? (:cwd opts)) (assoc :cwd (str (:cwd opts))))))))

(defn prompt!
  "Send `content` to the current session and stream the agent's reply.

  `content` is a string or a collection of ACP content blocks. `opts` may carry
  an explicit `:sessionId`. Returns the `session/prompt` result
  (`{:stopReason \"end_turn\"}`), and throws `ex-info` carrying `:acp/error`
  when the agent answers with an error."
  ([content] (prompt! content nil))
  ([content opts]
   (let [client (connect!)
         id     (or (:sessionId opts) (current-session-id))]
     (when (nil? id)
       (throw (ex-info "No current session; call (new!) or (resume! ...) first."
                       {:clai/no-session true})))
     (request! client "session/prompt"
               {:sessionId (str id)
                :prompt    (->content-blocks content)}))))
