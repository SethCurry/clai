(ns clai.tools.registry
  "Immutable per-agent tool registries.

  A registry is a plain map `{id tool}` owned by an agent (or a test), not
  shared global state. `make-registry` builds one from a collection of tool
  maps; the remaining functions return updated registries instead of mutating
  anything, so two agents can offer different tool sets in the same process."
  (:require [clai.tools.schema :as schema]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Registry construction
;; ---------------------------------------------------------------------------

(defn make-registry
  "Build a tool registry from `tools`, a collection of `{:id :fn :spec}` maps.
  A map `{id tool}` is accepted and returned as-is. Later duplicate ids win."
  {:malli/schema [:-> [:or [:sequential #'schema/tool] #'schema/tool-registry]
                  #'schema/tool-registry]}
  [tools]
  (if (map? tools)
    tools
    (into {} (map (juxt :id identity)) tools)))

(defn register-tool
  "Return `tool-registry` with `tool` added (or replaced) under its id."
  {:malli/schema [:-> #'schema/tool-registry #'schema/tool
                  #'schema/tool-registry]}
  [tool-registry tool]
  (tel/log! {:level :debug
             :id    ::register-tool
             :data  {:tool (:id tool)}}
            "Registered tool")
  (assoc tool-registry (:id tool) tool))

(defn unregister-tool
  "Return `tool-registry` without the tool named `id`, if present."
  {:malli/schema [:-> #'schema/tool-registry #'schema/tool-id
                  #'schema/tool-registry]}
  [tool-registry id]
  (let [removed? (contains? tool-registry id)]
    (tel/log! {:level :debug
               :id    ::unregister-tool
               :data  {:tool id :removed? removed?}}
              "Unregistered tool")
    (dissoc tool-registry id)))

(defn registered-tools
  "Return the registry map `{id tool}`. Mainly for inspection and tests."
  {:malli/schema [:-> #'schema/tool-registry #'schema/tool-registry]}
  [tool-registry]
  tool-registry)

(defn tool-ids
  "Return the ids in `tool-registry`."
  {:malli/schema [:-> #'schema/tool-registry [:vector #'schema/tool-id]]}
  [tool-registry]
  (vec (keys tool-registry)))

(defn select-tools
  "Return a registry containing only the tools named by `ids`, preserving the
  order of `ids` where the registry is inspected through `specs-for`. Unknown
  ids are skipped (with a warning). A nil `ids` means every tool is kept; an
  empty collection disables tools entirely."
  {:malli/schema [:-> #'schema/tool-registry
                  [:maybe [:sequential #'schema/tool-id]]
                  #'schema/tool-registry]}
  [tool-registry ids]
  (if (nil? ids)
    tool-registry
    (let [unknown (remove #(contains? tool-registry %) ids)]
      (when (seq unknown)
        (tel/log! {:level :warn
                   :id    ::unknown-tools
                   :data  {:tools (vec unknown)}}
                  "Ignoring unknown tool ids"))
      (reduce (fn [selected id]
                (if-let [tool (get tool-registry id)]
                  (assoc selected id tool)
                  selected))
              {}
              ids))))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(defn spec
  "Return the Ollama tool definition for `id` in `tool-registry`, or nil."
  {:malli/schema [:-> #'schema/tool-registry #'schema/tool-id
                  [:maybe #'schema/tool-spec]]}
  [tool-registry id]
  (-> (get tool-registry id) :spec))

(defn specs
  "Return a vector of every tool definition in `tool-registry`, suitable for
  passing as the `:tools` field of an Ollama `/api/chat` request."
  {:malli/schema [:-> #'schema/tool-registry [:vector #'schema/tool-spec]]}
  [tool-registry]
  (mapv :spec (vals tool-registry)))

(defn specs-for
  "Return a vector of tool definitions for `ids` (in the order given), skipping
  any ids that are not present in `tool-registry`."
  {:malli/schema [:-> #'schema/tool-registry
                  [:sequential #'schema/tool-id]
                  [:vector #'schema/tool-spec]]}
  [tool-registry ids]
  (vec (keep #(spec tool-registry %) ids)))
