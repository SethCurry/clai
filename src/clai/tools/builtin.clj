(ns clai.tools.builtin
  "The default built-in tools an agent can use within a session's `cwd`.

  Built-ins are grouped by category in `clai.tools.builtin.files`,
  `clai.tools.builtin.shell`, `clai.tools.builtin.eval`, and
  `clai.tools.builtin.subagent`; this namespace collects them. They are not
  installed into any global state -- each agent builds its own registry from
  `built-in-tools` (or a configured subset)."
  (:require [clai.tools.builtin.eval :as builtin-eval]
            [clai.tools.builtin.files :as builtin-files]
            [clai.tools.builtin.shell :as builtin-shell]
            [clai.tools.builtin.subagent :as builtin-subagent]
            [clai.tools.registry :as registry]
            [clai.tools.schema :as schema]))

(defn built-in-tools
  "Return a vector of the default tool maps (`:id`, `:fn`, `:spec`) an agent
  can use to read/write files, run shell commands within its session cwd,
  evaluate Clojure code in the running process, and delegate work to a
  sub-agent with its own tools and system prompt."
  {:malli/schema [:-> [:vector #'schema/tool]]}
  []
  (into []
        (concat (builtin-files/tools)
                (builtin-shell/tools)
                (builtin-eval/tools)
                (builtin-subagent/tools))))

(defn built-in-registry
  "Return a fresh registry containing the default built-in tools. Each agent
  owns the returned value, so registering or removing tools for one agent does
  not affect any other."
  {:malli/schema [:-> #'schema/tool-registry]}
  []
  (registry/make-registry (built-in-tools)))
