(ns clai.agent.tools.builtin
  "The default built-in tools an agent can use within a session's `cwd`.

  Built-ins are grouped by category in `clai.agent.tools.builtin.files`,
  `clai.agent.tools.builtin.shell`, `clai.agent.tools.builtin.eval`,
  `clai.agent.tools.builtin.subagent`, `clai.agent.tools.builtin.openproject`,
  and `clai.agent.tools.builtin.notes`; this namespace collects them. They are
  not installed into any global state -- each agent builds its own registry
  from `built-in-tools` (or a configured subset)."
  (:require [clai.agent.tools.builtin.eval :as builtin-eval]
            [clai.agent.tools.builtin.files :as builtin-files]
            [clai.agent.tools.builtin.notes :as builtin-notes]
            [clai.agent.tools.builtin.openproject :as builtin-openproject]
            [clai.agent.tools.builtin.shell :as builtin-shell]
            [clai.agent.tools.builtin.subagent :as builtin-subagent]
            [clai.agent.tools.registry :as registry]
            [clai.agent.tools.schema :as schema]))

(defn built-in-tools
  "Return a vector of the default tool maps (`:id`, `:handler`, `:spec`) an agent
  can use to read/write files, run shell commands within its session cwd,
  evaluate Clojure code in the running process, delegate work to a sub-agent
  with its own tools and system prompt, manage OpenProject work packages and
  their comments, and list, search, read, update, and delete the notes stored
  in PostgreSQL."
  {:malli/schema [:-> [:vector #'schema/Tool]]}
  []
  (into []
        (concat (builtin-files/tools)
                (builtin-shell/tools)
                (builtin-eval/tools)
                (builtin-subagent/tools)
                (builtin-openproject/tools)
                (builtin-notes/tools))))

(defn built-in-registry
  "Return a fresh registry containing the default built-in tools. Each agent
  owns the returned value, so registering or removing tools for one agent does
  not affect any other."
  {:malli/schema [:-> #'schema/ToolRegistry]}
  []
  (registry/make-registry (built-in-tools)))
