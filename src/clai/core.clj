(ns clai.core
  "Entry point for the `clai` ACP agent over stdio.

  The agent itself lives in `clai.agent`; this namespace only wires it to
  `acp.agent/run`."
  (:require [acp.agent :as acp]
            [clai.agent :as agent]
            [clai.log]
            [taoensso.telemere :as tel])
  (:gen-class))

(defn -main
  "Start the `clai` agent over stdio: read ACP messages from stdin and answer
  `session/prompt` requests by calling the Ollama chat API. Blocks until stdin
  reaches EOF (the client disconnected)."
  [& _args]
  (tel/log! {:level :info :id ::agent-starting}
            "Starting clai ACP agent over stdio")
  (try
    (acp/run (agent/ollama-agent))
    (catch Throwable e
      (tel/error! {:id ::agent-failed} e)
      (throw e))
    (finally
      (tel/log! {:level :info :id ::agent-stopping}
                "clai ACP agent stopped"))))
