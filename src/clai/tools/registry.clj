(ns clai.tools.registry
  "The global `clai` tool registry.

  Tools are registered by id and looked up when the model requests a call.
  The default built-in tools are installed by `clai.tools.builtin`; callers
  may add or replace entries at runtime."
  (:require [clai.tools.schema :as schema]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

;; `{id {:id :fn :spec}}`.
(def ^:private registry (atom {}))

(defn register-tool!
  "Register `tool` (a map with `:id`, `:fn`, `:spec`) in the global registry,
  replacing any existing tool with the same id. Returns the tool."
  {:malli/schema [:-> #'schema/tool #'schema/tool]}
  [tool]
  (swap! registry assoc (:id tool) tool)
  (tel/log! {:level :debug
             :id    ::register-tool
             :data  {:tool (:id tool)}}
            "Registered tool")
  tool)

(defn unregister-tool!
  "Remove the tool with `id` from the registry, if present. Returns nil."
  {:malli/schema [:-> #'schema/tool-id :nil]}
  [id]
  (let [removed? (contains? @registry id)]
    (swap! registry dissoc id)
    (tel/log! {:level :debug
               :id    ::unregister-tool
               :data  {:tool id :removed? removed?}}
              "Unregistered tool")
    nil))

(defn registered-tools
  "Return the current registry as a map `{id tool}`. Mainly for inspection
  and tests."
  {:malli/schema [:-> #'schema/tool-registry]}
  []
  @registry)

(defn spec
  "Return the Ollama tool definition for the registered tool `id`, or nil."
  {:malli/schema [:-> #'schema/tool-id [:maybe #'schema/tool-spec]]}
  [id]
  (-> (get @registry id) :spec))

(defn specs
  "Return a vector of all registered tool definitions, suitable for passing
  as the `:tools` field of an Ollama `/api/chat` request."
  {:malli/schema [:-> [:vector #'schema/tool-spec]]}
  []
  (mapv :spec (vals @registry)))

(defn specs-for
  "Return a vector of tool definitions for the given tool `ids` (in the
  order given), skipping any ids that are not registered. Useful when a
  config file selects a subset of the built-in tools."
  {:malli/schema [:-> [:sequential #'schema/tool-id]
                  [:vector #'schema/tool-spec]]}
  [ids]
  (vec (keep #(spec %) ids)))
