(ns clai.tools.builtin.subagent
  "Built-in `spawn_agent` tool: run an isolated sub-agent.

  A sub-agent is a fresh chat conversation with its own system prompt, model,
  and tool selection. It shares the parent's Ollama client and working
  directory, but its history is transient: it is not written to the parent
  session (or to `clai.state` at all), and only its final reply is returned as
  the tool result. This keeps the parent's context small and lets it delegate a
  focused task -- exploring a directory, drafting a file, running a command --
  to a narrower agent.

  Tool selection is drawn from the parent's registry, so a sub-agent can use
  the tools the parent has access to (including custom tools) but cannot
  invent new ones. By default `spawn_agent` itself is withheld from the child
  to avoid unbounded recursion; an explicit `tools` list may include it, up to
  [[default-max-depth]] levels of nesting."
  (:require [clai.providers.ollama :as ollama]
            [clai.tools.invoke :as invoke]
            [clai.tools.registry :as registry]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Limits and defaults
;; ---------------------------------------------------------------------------

(def tool-id
  "The id of the sub-agent tool."
  "spawn_agent")

(def default-max-depth
  "Default maximum sub-agent nesting depth. Depth 0 is the top-level agent;
  a child runs at depth 1. A spawn request at this depth is refused."
  2)

(def default-max-rounds
  "Default cap on tool-call rounds inside one sub-agent conversation."
  8)

(def max-rounds-limit
  "Upper bound on a requested sub-agent `max_rounds`, to keep a runaway child
  from occupying the process indefinitely."
  32)

(def default-system-prompt
  "System prompt used when `spawn_agent` is called without `system_prompt`."
  (str "You are a focused sub-agent working on a single delegated task. "
       "Use the tools available to you when they help, keep working until the "
       "task is complete, and reply with a concise result for the agent that "
       "delegated the task. Do not ask the delegating agent questions unless "
       "you are blocked; state any assumptions you made in your final reply."))

;; ---------------------------------------------------------------------------
;; Argument helpers
;; ---------------------------------------------------------------------------

(defn- blank?
  [value]
  (str/blank? (str value)))

(defn- as-long
  "Coerce `value` to a long, returning `default` when it is nil or invalid."
  [value default]
  (cond
    (number? value)  (long value)
    (string? value)  (or (parse-long (str/trim value)) default)
    :else            default))

(defn- requested-tool-ids
  "Normalize the `tools` argument to a vector of ids, or nil when it was not
  supplied. A comma/whitespace-separated string is accepted for convenience."
  [tools]
  (cond
    (nil? tools)        nil
    (string? tools)     (->> (str/split tools #"[\s,]+")
                             (remove str/blank?)
                             vec)
    (sequential? tools) (mapv str tools)
    :else               [(str tools)]))

(defn- child-tool-registry
  "Select the child agent's registry from the parent's `:tool-registry`.

  With no explicit `tools` argument, every parent tool is offered except
  `spawn_agent` itself. An explicit empty vector gives the child no tools."
  [ctx requested]
  (let [parent (or (:tool-registry ctx) {})
        ids    (if (some? requested)
                 requested
                 (remove #{tool-id} (registry/tool-ids parent)))]
    (registry/select-tools parent ids)))

(defn- child-ctx
  "Build the context handed to the child's tools. It carries the child's own
  registry and model, so a nested `spawn_agent` call sees the child's tools
  and increments the depth."
  [ctx {:keys [client model chat-opts max-rounds cwd depth registry]}]
  {:cwd          (or cwd (:cwd ctx))
   :session-id   (:session-id ctx)
   :client       client
   :model        model
   :tool-registry registry
   :chat-opts    chat-opts
   :max-rounds   max-rounds
   :agent-depth  (inc depth)})

;; ---------------------------------------------------------------------------
;; Conversation loop
;; ---------------------------------------------------------------------------

(defn- chat-request
  "Send one non-streaming `/api/chat` request. Returns either
  `{:message ...}` or `{:error <throwable>}`."
  [client model history tool-registry chat-opts]
  (let [specs   (registry/specs tool-registry)
        request (cond-> {:model    model
                         :messages history
                         :stream   false}
                  (seq specs) (assoc :tools specs))
        request (merge request chat-opts)]
    (try
      {:message (ollama/assistant-message (ollama/chat client request))}
      (catch Throwable e
        {:error e}))))

(defn- run-conversation
  "Drive the child conversation to completion, executing its tool calls with
  its own registry and context. Returns the final assistant text or an error
  string."
  [client model history child-registry child-context chat-opts max-rounds]
  (loop [history history
         round   0]
    (if (>= round max-rounds)
      (str "Error: sub-agent stopped after " max-rounds
           " tool rounds without a final reply.")
      (let [{:keys [message error]}
            (chat-request client model history child-registry chat-opts)]
        (cond
          (some? error)
          (str "Error: sub-agent request failed: "
               (or (ex-message error) (str (class error))))

          (ollama/has-tool-calls? message)
          (let [tool-calls (:tool_calls message)
                results    (invoke/execute-calls child-registry tool-calls
                                                 child-context)
                next-hist  (into (conj history message) results)]
            (tel/log! {:level :debug
                       :id    ::subagent-tool-round
                       :data  {:round round
                               :tools (mapv #(or (ollama/tool-call-name %)
                                                 "?")
                                            tool-calls)}}
                      "Sub-agent executed tool calls")
            (recur next-hist (inc round)))

          :else
          (let [content (str (or (:content message) ""))]
            (if (blank? content)
              "(sub-agent returned no text)"
              content)))))))

;; ---------------------------------------------------------------------------
;; Tool implementation
;; ---------------------------------------------------------------------------

(defn spawn-agent-fn
  "Tool: run a sub-agent with its own system prompt, model, and tool selection.

  Arguments:

    prompt         the task for the sub-agent (required)
    system_prompt  the sub-agent's system prompt (optional; defaults to
                   [[default-system-prompt]])
    tools          tool ids the sub-agent may use (optional; defaults to all
                   parent tools except `spawn_agent`)
    model          model name for the sub-agent (optional; inherits parent)
    max_rounds     tool-call round cap (optional; inherits parent, clamped to
                   [[max-rounds-limit]])
    cwd            working directory for the sub-agent's tools (optional;
                   inherits parent)

  `ctx` must carry the parent's `:client`, `:model`, `:tool-registry`,
  `:chat-opts`, and `:max-rounds` (the agent loop supplies all of these).
  Returns the sub-agent's final reply, or a string beginning with `Error:`."
  [{:keys [prompt system_prompt tools model max_rounds cwd]} ctx]
  (let [depth       (as-long (:agent-depth ctx) 0)
        parent-max  (as-long (:max-rounds ctx) default-max-rounds)
        child-max   (-> (as-long max_rounds parent-max)
                        (max 1)
                        (min max-rounds-limit))
        client      (:client ctx)
        child-model (or (some-> model str not-empty) (:model ctx))]
    (cond
      (>= depth default-max-depth)
      (str "Error: sub-agent depth limit reached (" default-max-depth
           "); refusing to spawn a nested sub-agent.")

      (blank? prompt)
      "Error: spawn_agent requires a non-blank `prompt`."

      (nil? client)
      "Error: spawn_agent requires an Ollama client in the tool context."

      (blank? child-model)
      "Error: spawn_agent requires a model name in the tool context."

      :else
      (let [requested   (requested-tool-ids tools)
            child-reg   (child-tool-registry ctx requested)
            sys-prompt  (if (blank? system_prompt)
                          default-system-prompt
                          (str system_prompt))
            history     [{:role "system" :content sys-prompt}
                         {:role "user" :content (str prompt)}]
            child-context (child-ctx ctx
                                     {:client       client
                                      :model        child-model
                                      :chat-opts    (:chat-opts ctx)
                                      :max-rounds   child-max
                                      :cwd          cwd
                                      :depth        depth
                                      :registry     child-reg})]
        (tel/log! {:level :info
                   :id    ::subagent-start
                   :data  {:depth      depth
                           :model      child-model
                           :tools      (vec (registry/tool-ids child-reg))
                           :max-rounds child-max
                           :cwd        (:cwd child-context)
                           :prompt-chars (count (str prompt))}}
                  "Starting sub-agent")
        (let [result (try
                       (run-conversation client child-model history child-reg
                                         child-context (:chat-opts ctx) child-max)
                       (catch Throwable e
                         (tel/error! {:id ::subagent-failed
                                      :data {:depth depth
                                             :model child-model}}
                                     e)
                         (str "Error: sub-agent failed: "
                              (or (ex-message e) (str (class e))))))]
          (tel/log! {:level :info
                     :id    ::subagent-complete
                     :data  {:depth depth
                             :error? (str/starts-with? result "Error:")
                             :result-chars (count result)}}
                    "Sub-agent complete")
          result)))))

;; ---------------------------------------------------------------------------
;; Tool definition
;; ---------------------------------------------------------------------------

(def ^:private spawn-agent-spec
  {:type "function"
   :function
   {:name tool-id
    :description
    "Delegate a focused task to a sub-agent -- a fresh chat conversation with
    its own system prompt, model, and tool selection. The sub-agent works
    independently and returns only its final reply; its intermediate messages
    and tool calls are not added to your history. Use it to explore or modify
    a project without filling your own context, or to give a narrower agent a
    different system prompt or tool set. The sub-agent shares your working
    directory unless `cwd` is given. Nested sub-agents are allowed up to a
    small depth limit; `spawn_agent` is not available to the child unless
    explicitly listed in `tools`."
    :parameters
    {:type "object"
     :properties
     {"prompt"        {:type "string"
                       :description "The task for the sub-agent. Be specific about the desired result."}
      "system_prompt" {:type "string"
                       :description "System prompt for the sub-agent. Defaults to a focused sub-agent prompt."}
      "tools"         {:type "array"
                       :items {:type "string"}
                       :description "Tool ids the sub-agent may use. Defaults to all of your tools except spawn_agent. Pass [] for no tools."}
      "model"         {:type "string"
                       :description "Model name for the sub-agent. Defaults to the parent agent's model."}
      "max_rounds"    {:type "integer"
                       :description "Maximum consecutive tool-call rounds for the sub-agent. Defaults to the parent's cap, clamped to 32."}
      "cwd"           {:type "string"
                       :description "Working directory for the sub-agent's tools. Defaults to the parent's working directory."}}
     :required ["prompt"]}}})

(defn tools
  "Return the built-in sub-agent tool maps."
  []
  [{:id tool-id :handler spawn-agent-fn :spec spawn-agent-spec}])
