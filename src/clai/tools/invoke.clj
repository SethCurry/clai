(ns clai.tools.invoke
  "Invocation of registered tools from Ollama `tool_calls`.

  Ollama's `/api/chat` response message carries a `:tool_calls` vector when
  the model decides to call a tool. The agent executes each call, appends a
  `{:role \"tool\" ...}` message with the result, and chats again. This
  namespace turns those raw calls into lookups in the agent's own tool
  registry and tool-role messages."
  (:require [cheshire.core :as json]
            [clai.tools.schema :as schema]
            [clai.util :as util]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Invocation
;; ---------------------------------------------------------------------------

(defn parse-arguments
  "Ollama sends tool call `:arguments` as a JSON *string* (some clients send a
  parsed map). Normalize to a Clojure map either way."
  {:malli/schema [:-> [:maybe [:or [:map] :string]] [:map]]}
  [arguments]
  (cond
    (nil? arguments)     {}
    (map? arguments)     arguments
    (string? arguments)
    (if (str/blank? arguments)
      {}
      (try
        (json/parse-string arguments true)
        (catch Exception _
          (tel/log! {:level :debug
                     :id    ::unparseable-arguments
                     :data  {:length (count arguments)}}
                    "Could not parse tool arguments as JSON")
          {:__parse_error__ arguments})))
    :else                {}))

(defn- coerce-result
  "Coerce a tool function's return value to a string for the tool message."
  [value]
  (cond
    (nil? value)   ""
    (string? value) value
    :else          (pr-str value)))

(defn- normalize-args
  "Keywordize any string keys in `args` so tool functions can destructure with
  `:keys` regardless of whether arguments arrived as a parsed map or with
  string keys."
  [args]
  (into {}
        (for [[k v] args]
          [(if (string? k) (keyword k) k) v])))

(defn invoke
  "Run the tool `id` from `tool-registry` with parsed `args` and session
  context `ctx`.

  `args` keys are keywordized first, so both `{:path \"x\"}` and
  `{\"path\" \"x\"}` work. Returns a string result. If the tool is unknown or
  throws, an error string is returned instead of propagating, so the agent
  loop can feed the failure back to the model."
  {:malli/schema [:-> #'schema/ToolRegistry #'schema/tool-id
                  [:maybe #'schema/tool-args]
                  [:maybe #'schema/tool-ctx] #'schema/tool-result]}
  [tool-registry id args ctx]
  (let [tool (get tool-registry id)]
    (cond
      (nil? tool)
      (do
        (tel/log! {:level :warn
                   :id    ::unknown-tool
                   :data  {:tool id}}
                  "Attempted to invoke unknown tool")
        (str "Error: unknown tool '" id "'"))

      :else
      (let [args'  (normalize-args args)
            start  (System/nanoTime)]
        (tel/log! {:level :debug
                   :id    ::tool-invoke
                   :data  {:tool     id
                           :arg-keys (vec (keys args'))
                           :cwd      (:cwd ctx)}}
                  "Invoking tool")
        (try
          (let [result (coerce-result ((:handler tool) args' ctx))
                msecs  (quot (- (System/nanoTime) start) 1000000)]
            (tel/log! {:level :debug
                       :id    ::tool-complete
                       :data  {:tool         id
                               :elapsed-ms   msecs
                               :result-chars (count result)
                               :error?       (str/starts-with? result "Error:")}}
                      "Tool completed")
            result)
          (catch Throwable e
            (tel/error! {:id   ::tool-failed
                         :data {:tool     id
                                :arg-keys (vec (keys args'))}}
                        e)
            (str "Error: " (util/error-message e))))))))

(defn tool-call->id+args
  "Extract `[tool-id args]` from one Ollama `tool_calls` entry. Tolerates both
  the nested `{:function {:name ... :arguments ...}}` shape and a flattened
  `{:name ... :arguments ...}` shape."
  {:malli/schema [:-> #'schema/tool-call
                  [:tuple [:maybe :string] [:map]]]}
  [call]
  (let [f    (or (:function call) call)
        name (:name f)
        args (parse-arguments (:arguments f))]
    [name args]))

(defn tool-result-message
  "Build the `tool`-role history message recording `result` for `call`.

  `name` is the tool's name; it is recorded in `:name` so the model can tell
  the results apart when several tools answer in one round."
  {:malli/schema [:-> #'schema/tool-call [:maybe :string] #'schema/tool-result
                  #'schema/tool-call-message]}
  [call name result]
  (cond-> {:role    "tool"
           :content result
           :name    (or name "unknown")}
    (some? (:id call)) (assoc :tool_call_id (:id call))))

(defn execute-calls
  "Execute a vector of Ollama `tool_calls` against `tool-registry`, returning
  a vector of `{:role \"tool\" :content ... :name ...}` messages (one per
  call) ready to append to chat history.

  `ctx` is passed to each tool function; it should carry at least `:cwd` for
  the filesystem/shell tools."
  {:malli/schema [:-> #'schema/ToolRegistry
                  [:sequential #'schema/tool-call]
                  [:maybe #'schema/tool-ctx]
                  [:vector #'schema/tool-call-message]]}
  [tool-registry tool-calls ctx]
  (tel/log! {:level :debug
             :id    ::execute-tool-calls
             :data  {:count (count tool-calls)
                     :tools (mapv #(or (-> % :function :name) (:name %) "?")
                                  tool-calls)}}
            "Executing tool calls")
  (vec
   (for [call tool-calls]
     (let [[id args] (tool-call->id+args call)]
       (tool-result-message call id (invoke tool-registry id args ctx))))))
