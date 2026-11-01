(ns clai.core
  "Entry point for the `clai` ACP agent over stdio.

  The agent itself lives in `clai.agent`; this namespace wires it to
  `acp.agent/run` and gives each launched agent its own tool registry from
  `clai.tools` rather than relying on global tool state."
  (:require [acp.agent :as acp]
            [clai.agent :as agent]
            [clai.log]
            [clai.tools :as tools]
            [taoensso.telemere :as tel])
  (:gen-class))

(defn make-agent
  "Build a fresh agent with its own built-in tool registry.

  `opts` is passed through to `clai.agent/ollama-agent`; supply
  `:tool-registry` (or `:tools`) to give the agent a custom registry. Config
  `:tools` ids still narrow whichever registry is supplied."
  ([] (make-agent {}))
  ([opts]
   (agent/ollama-agent
    (merge {:tool-registry (tools/built-in-tools)} opts))))

(defn -main
  "Start the `clai` agent over stdio: read ACP messages from stdin and answer
  `session/prompt` requests by calling the Ollama chat API. Blocks until stdin
  reaches EOF (the client disconnected)."
  [& _args]
  (tel/log! {:level :info :id ::agent-starting}
            "Starting clai ACP agent over stdio")
  (try
    (acp/run (make-agent))
    (catch Throwable e
      (tel/error! {:id ::agent-failed} e)
      (throw e))
    (finally
      (tel/log! {:level :info :id ::agent-stopping}
                "clai ACP agent stopped"))))
