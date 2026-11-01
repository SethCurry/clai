(ns clai.tools.builtin
  "The default built-in tools an agent can use within a session's `cwd`.

  Built-ins are grouped by category in `clai.tools.builtin.files`,
  `clai.tools.builtin.shell`, and `clai.tools.builtin.eval`; this namespace
  collects them and installs them into the global registry at load time."
  (:require [clai.tools.builtin.eval :as builtin-eval]
            [clai.tools.builtin.files :as builtin-files]
            [clai.tools.builtin.shell :as builtin-shell]
            [clai.tools.registry :as registry]
            [clai.tools.schema :as schema]))

(defn built-in-tools
  "Return a vector of the default tool maps (`:id`, `:fn`, `:spec`) an agent
  can use to read/write files, run shell commands within its session cwd, and
  evaluate Clojure code in the running process."
  {:malli/schema [:-> [:vector #'schema/tool]]}
  []
  (into []
        (concat (builtin-files/tools)
                (builtin-shell/tools)
                (builtin-eval/tools))))

(defn install-built-ins!
  "Register every built-in tool in the global registry, replacing any
  existing tools with the same ids."
  []
  (run! registry/register-tool! (built-in-tools)))

;; Populate the registry with the built-ins at load time.
(install-built-ins!)
