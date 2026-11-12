(ns clai.repl
  "A REPL-friendly, in-process client for the `clai` agent.

  [[current-agent]] is a delay producing a plain agent map built by
  `clai.agent.core/ollama-agent`, so loading this namespace does not create an
  agent by itself.
  The helpers here call its session and prompt operations directly; nothing
  goes through stdio or a wire protocol, so the agent can be driven from a
  REPL while stdout stays available for the conversation itself.

  Prompt updates arrive as event-shaped maps from `clai.events.schema` and are
  rendered as they happen, so model text and tool activity are visible while
  the prompt runs.

  Typical use:

      (new!)                             ; start and select a session
      (prompt! \"summarize AGENTS.md\")    ; stream a reply
      (history!)                         ; print the conversation so far
      (resume! \"<session-id>\")           ; replay a previous conversation
      (disconnect!)                      ; drop the in-process agent reference

  By default every session gets its own namespace, `sessions.<session-id>`.
  [[new!]] and [[resume!]] create or reuse that namespace, seed it with the
  Vars and aliases of `user`, and move the REPL into it with `in-ns`. The
  namespace also gets its own `current-session` and `*cwd*` Vars, so returning
  to it keeps the helpers pointed at its session and its shell commands
  pointed at its working directory. Namespace creation goes through
  [[namespace-builder]], which plugins can replace or wrap to customize the
  namespaces new sessions run in.

  [[current-session]] and [[current-agent]] are vars rather than atoms so a
  REPL can rebind or redefine them: `(with-redefs [current-agent my-agent]
  (new!))` drives a different agent."
  (:require [clai.agent.core :as agent]
            [clai.agent.providers.ollama.prompt :as ollama-prompt]
            [clai.events.schema :as events]
            [clai.plugins :as plugins]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; REPL state
;; ---------------------------------------------------------------------------

(def current-session
  "The session the helpers act on: a map carrying at least `:sessionId` (a bare
  id string is accepted too), or nil before [[new!]]/[[resume!]].

  [[new!]] and [[resume!]] keep this global selection up to date, but each
  session's namespace also gets a local `current-session`, which
  [[current-session-id]] prefers when the helpers are called there."
  nil)

(def current-agent
  "A delay producing the in-process agent [[connect!]] uses. It is forced
  when the connection is established, so redefining or rebinding it takes
  effect on the next connection and loading this namespace does not create an
  agent (or a database client) by itself."
  (delay (agent/ollama-agent)))

(defonce ^:private runtime
  ;; The live in-process agent, or nil: `{:agent <agent map>}`.
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
  "The id of the session selected in the calling namespace, or nil.

  A session's namespace has its own `current-session`, so its helpers keep
  acting on that session even after another session is selected elsewhere.
  Namespaces that do not map `current-session` (application code calling the
  helpers directly, for example) fall back to the global [[current-session]]."
  []
  (let [v (ns-resolve *ns* 'current-session)]
    (session-id (if (var? v) @v current-session))))

;; ---------------------------------------------------------------------------
;; Rendering event updates
;; ---------------------------------------------------------------------------

(def ^:private event-labels
  "Text-bearing event kinds and the label each is printed under."
  {"user_prompt"   "you"
   "agent_message" "clai"
   "agent_thought" "thinking"})

(defn- close-stream!
  "Terminate the currently open streamed text event, if any."
  [stream]
  (when (:open @stream)
    (println)
    (flush))
  (reset! stream {}))

(defn- render-text-event!
  "Print one text-bearing event, opening a new labelled line when the event
  kind changes."
  [stream kind update]
  (when (not= (:open @stream) kind)
    (close-stream! stream)
    (print (str (get event-labels kind kind) "> "))
    (swap! stream assoc :open kind))
  (print (events/content->text (:content update)))
  (flush))

(def ^:private terminal-tool-statuses
  "Tool call statuses that mean the call will not report any more output."
  #{"completed" "failed"})

(defn- render-tool-call!
  "Print a tool call's announcement and, once it reaches a terminal status,
  its id, status, and full output."
  [{:keys [id title name status output]}]
  (when (and (some? title) (not (contains? terminal-tool-statuses status)))
    (println (str "  [tool] " (or title name "tool"))))
  (when (contains? terminal-tool-statuses status)
    (println (str "  [tool] " id " " status))
    (doseq [line (remove str/blank? (str/split-lines (str output)))]
      (println (str "     " line)))))

(defn- render-event!
  "Render one event update on `*out*`."
  [stream update]
  (let [kind (:type update)]
    (cond
      (contains? event-labels kind)
      (render-text-event! stream kind update)

      (= "tool_call" kind)
      (do (close-stream! stream) (render-tool-call! update))

      :else
      (do (close-stream! stream)
          (println (str "  [" (or kind "event") "] " (pr-str update)))))))

(defn- render-history!
  "Render the persisted `history` as event updates."
  [history]
  (let [stream (atom {})]
    (try
      (doseq [update (ollama-prompt/history->updates history)]
        (render-event! stream update))
      (finally
        (close-stream! stream)))))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defn connect!
  "Connect to [[current-agent]] and return it. Idempotent while connected.

  The optional `agent` argument is an agent map to use instead of
  [[current-agent]]; it is used only when a new connection is created. When
  the agent carries a PostgreSQL client, the enabled plugins stored there are
  loaded before the connection is published."
  ([] (connect! (force (deref #'current-agent))))
  ([agent]
   (or (:agent @runtime)
       (do
         (when-let [postgres (:postgres agent)]
           (plugins/load-configured! postgres))
         (reset! runtime {:agent agent})
         agent))))

(defn disconnect!
  "Drop the in-process agent connection. Safe to call when already
  disconnected; returns true when it closed a connection."
  []
  (when @runtime
    (reset! runtime nil)
    true))

;; ---------------------------------------------------------------------------
;; Shell
;; ---------------------------------------------------------------------------

(defn $
  "Run `command` with `sh -c`, printing its stdout and stderr.

  Output is printed after the command finishes: stdout goes to `*out*` and
  stderr to `*err*`. Returns the `clojure.java.shell/sh` result map
  (`{:out ... :err ... :exit ...}`); a non-zero exit status is not an error."
  [command]
  (let [{:keys [out err] :as result} (shell/sh "sh" "-c" (str command))]
    (print out)
    (flush)
    (binding [*out* *err*]
      (print err)
      (flush))
    result))

;; ---------------------------------------------------------------------------
;; Session namespaces
;; ---------------------------------------------------------------------------

(def ^:private session-ns-prefix
  "Prefix of the namespace a session's REPL runs in."
  "sessions")

(defn- session-ns-segment
  "A namespace-safe version of session id `id`.

  Session ids are database keys, but agents are free to render them however
  they like; characters that cannot appear in a namespace name are replaced
  with `_`."
  [id]
  (let [id (str id)]
    (if (str/blank? id)
      "unnamed"
      (str/replace id #"[^A-Za-z0-9_.-]" "_"))))

(defn session-ns
  "The symbol naming the REPL namespace of `session`: `sessions.<id>`.

  `session` is a session map or a bare session id. The id is made
  namespace-safe by replacing everything but letters, digits, `.`, `_` and
  `-` with `_`."
  {:malli/schema [:-> [:or :string :map] :symbol]}
  [session]
  (symbol (str session-ns-prefix "." (session-ns-segment (session-id session)))))

(def Session
  "Malli schema for the session maps accepted by [[namespace-builder]]."
  [:map
   [:sessionId :string]
   [:cwd {:optional true} [:maybe :string]]])

(def Namespace
  "Malli schema for a Clojure namespace object."
  [:fn #(instance? clojure.lang.Namespace %)])

(def NamespaceBuilderOpts
  "Malli schema for the options map accepted by [[namespace-builder]].

  `:session` is the session the namespace belongs to. `:namespace` overrides
  the default `sessions.<id>` name, `:cwd` overrides the working directory
  stored on the namespace's `*cwd*` Var, and `:seed-from` names the namespace
  whose public Vars and aliases a fresh session namespace is seeded from
  (defaults to `user`)."
  [:map
   [:session Session]
   [:namespace {:optional true} [:maybe :symbol]]
   [:cwd {:optional true} [:maybe :string]]
   [:seed-from {:optional true} [:maybe :symbol]]])

(defn- refer-publics!
  "Refer every public Var of namespace `from` into `ns`."
  [ns from]
  (doseq [[sym v] (ns-publics from)]
    (.refer ^clojure.lang.Namespace ns sym v))
  ns)

(defn- seed-session-ns!
  "Fill a fresh session namespace `ns` with the REPL's helpers.

  The `clai.repl` helpers are always referred. When `from` names a namespace
  that exists, its aliases (`git`, `docker`, ...) and Vars (`help!`, `pprint`,
  definitions made there, ...) are referred as well, so code written in a
  session namespace can use the same helpers as code written in `from`. The
  builder defaults `from` to `user`. Referring the helpers explicitly keeps a
  session namespace usable even when `from` is sparse or absent (as `user` is
  under `lein test`, for example)."
  [ns from]
  (binding [*ns* ns]
    (refer 'clojure.core))
  (refer-publics! ns 'clai.repl)
  (when-let [from-ns (some-> from find-ns)]
    (doseq [[alias target] (ns-aliases from-ns)]
      (.addAlias ^clojure.lang.Namespace ns alias target))
    (doseq [[sym v] (ns-map from-ns)
            :when (var? v)]
      (.refer ^clojure.lang.Namespace ns sym v)))
  ns)

(defn- intern-session-var!
  "Intern `sym` with `value` in session namespace `ns` and return the Var.

  `seed-session-ns!` refers the `user` Vars, including any that happen to
  share these names; drop such a refer first so the namespace gets its own
  Var instead of the \"already refers to\" warning a replacing `refer` prints."
  [ns sym value]
  (when-let [existing (get (ns-map ns) sym)]
    (when-not (and (var? existing)
                   (identical? ns (.ns ^clojure.lang.Var existing)))
      (ns-unmap ns sym)))
  (intern ns sym value))

(defn- default-namespace-builder
  "Create and return the REPL namespace described by `opts`, without running
  plugin hooks.

  The namespace is `sessions.<id>` unless `opts` supplies `:namespace`. On
  first use it is seeded from `:seed-from` (default `user`) and its `:doc` is
  set; a namespace that already exists (because the session was visited
  earlier in this JVM) keeps the definitions made in it. Either way, its
  `current-session` Var is set to `:session` and its `*cwd*` Var is set to the
  effective working directory (`:cwd`, then the session's `:cwd`, then the
  JVM's working directory). The caller is responsible for switching into the
  returned namespace."
  [{:keys [session namespace cwd seed-from] :or {seed-from 'user}}]
  (let [cwd     (str (or cwd (:cwd session) (default-cwd)))
        session (assoc session :cwd cwd)
        id      (session-id session)
        ns-sym  (or namespace (session-ns session))
        ns      (find-ns ns-sym)
        fresh?  (nil? ns)
        ns      (or ns (create-ns ns-sym))]
    (when fresh?
      (seed-session-ns! ns seed-from)
      (alter-meta! ns assoc
                   :doc (str "REPL namespace for clai session " id ".")))
    (let [v (intern-session-var! ns 'current-session session)]
      (alter-meta! v assoc
                   :doc (str "The clai session selected in this namespace ("
                             id ").")))
    (let [v (intern-session-var! ns '*cwd* cwd)]
      ;; Clojure 1.12 keeps dynamic-ness on the Var itself, so mark it there
      ;; as well as in the metadata (`def ^:dynamic` does both).
      (.setDynamic ^clojure.lang.Var v true)
      (alter-meta! v assoc
                   :dynamic true
                   :doc (str "Working directory for shell helpers called in "
                             "this clai session namespace (" id ").")))
    ns))

(defn namespace-builder
  "Create and return the REPL namespace described by `opts`, a
  [[NamespaceBuilderOpts]] map.

  The default builder ([[default-namespace-builder]]) creates or refreshes
  `sessions.<id>` as documented there. The `:namespace-builder` plugin hooks
  wrap it, so plugins can change options, seed extra Vars, or replace the
  resulting namespace entirely. Hooks run in plugin priority order and call
  `next` to continue.

  This remains the extension point for callers that prefer `alter-var-root`;
  replacing this Var bypasses the plugin hooks."
  {:malli/schema [:-> NamespaceBuilderOpts Namespace]}
  [opts]
  (plugins/build-namespace opts default-namespace-builder))

(defn- select-session!
  "Make `session` current and move the REPL into the namespace built for it.

  [[namespace-builder]] creates or updates the namespace from `session`;
  `*ns*` is then switched there with `in-ns`, so the prompt and subsequent
  evaluations run in the session's namespace."
  [session]
  (alter-var-root #'current-session (constantly session))
  (let [ns (namespace-builder {:session session
                               :cwd     (str (or (:cwd session)
                                                (default-cwd)))})]
    (in-ns (ns-name ns))
    session))

;; ---------------------------------------------------------------------------
;; REPL helpers
;; ---------------------------------------------------------------------------

(defn- latest-session
  "The most recently created session reported by the agent, or nil."
  [agent]
  (let [{:keys [sessions]} (agent/list-sessions! agent {})]
    (last (sort-by #(str (:createdAt %)) sessions))))

(def NewSessionOpts
  "Malli schema for the options map accepted by [[new!]]."
  [:map
   [:cwd {:optional true} [:maybe :string]]])

(defn new!
  "Start a new agent session, make it the [[current-session]], and move the
  REPL into the namespace [[namespace-builder]] creates for it (by default
  `sessions.<id>`, seeded from `user`).

  `opts` is a map with optional `:cwd` (defaults to the JVM's working
  directory); a bare string is read as the working directory. Returns the new
  session map."
  {:malli/schema [:function
                  [:=> [:cat] Session]
                  [:=> [:cat [:maybe [:or NewSessionOpts :string]]] Session]]}
  ([] (new! nil))
  ([opts]
   (let [opts    (if (string? opts) {:cwd opts} opts)
         agent   (connect!)
         cwd     (str (or (:cwd opts) (default-cwd)))
         session (assoc (agent/new-session! agent {:cwd cwd}) :cwd cwd)]
     (select-session! session))))

(def ResumeOpts
  "Malli schema for the argument accepted by [[resume!]]."
  [:map
   [:sessionId {:optional true} [:maybe :string]]
   [:cwd {:optional true} [:maybe :string]]])

(defn resume!
  "Resume an existing session, replaying its history to the display, make it
  the [[current-session]], and move the REPL into the namespace
  [[namespace-builder]] creates for it (by default `sessions.<id>`).

  With no argument this resumes the session selected in the calling namespace
  when one is selected, and otherwise the most recently created session the
  agent lists. An argument may be a session id or a map with `:sessionId` and
  optionally `:cwd`. An unknown id degrades to a new session. Returns the
  resumed session map."
  {:malli/schema [:function
                  [:=> [:cat] Session]
                  [:=> [:cat [:maybe [:or ResumeOpts :string]]] Session]]}
  ([] (resume! nil))
  ([arg]
   (let [agent   (connect!)
         opts    (cond
                   (nil? arg)  {}
                   (map? arg)  arg
                   :else       {:sessionId (str arg)})
         chosen  (or (:sessionId opts) (current-session-id))
         summary (when-not chosen (latest-session agent))
         id      (or chosen (:sessionId summary))]
     (if-let [session (when id
                        (agent/load-session!
                         agent
                         (cond-> {:sessionId (str id)}
                           (some? (:cwd opts)) (assoc :cwd (str (:cwd opts))))))]
       (do
         (render-history! (:history session))
         (select-session! session))
       (let [cwd (str (or (:cwd opts)
                          (:cwd summary)
                          (default-cwd)))]
         (select-session!
          (assoc (agent/new-session! agent {:cwd cwd}) :cwd cwd)))))))

(defn history!
  "Print the history of the current session.

  With no argument this reads [[current-session]]. An argument may be a
  session id or a map with `:sessionId` and optionally `:cwd` to read another
  session's history. Returns the loaded session map, or nil when it does not
  exist."
  ([] (history! nil))
  ([arg]
   (let [agent (connect!)
         opts  (cond
                 (nil? arg)  {}
                 (map? arg)  arg
                 :else       {:sessionId (str arg)})
         id    (or (:sessionId opts) (current-session-id))]
     (when (nil? id)
       (throw (ex-info "No current session; call (new!) or (resume! ...) first."
                       {:clai/no-session true})))
     (when-let [session (agent/load-session!
                         agent
                         (cond-> {:sessionId (str id)}
                           (some? (:cwd opts)) (assoc :cwd (str (:cwd opts)))))]
       (render-history! (:history session))
       session))))

(defn prompt!
  "Send `content` to the current session and stream the agent's reply.

  `content` is a string or event content blocks. `opts` may carry an explicit
  `:sessionId`. Returns the prompt result (`{:stopReason \"end_turn\"}`)."
  ([content] (prompt! content nil))
  ([content opts]
   (let [agent  (connect!)
         id     (or (:sessionId opts) (current-session-id))]
     (when (nil? id)
       (throw (ex-info "No current session; call (new!) or (resume! ...) first."
                       {:clai/no-session true})))
     (let [stream (atom {})]
       (try
         (agent/run-prompt! agent
                            {:sessionId (str id)
                             :prompt    content
                             :on-update #(render-event! stream %)})
         (finally
           (close-stream! stream)))))))
