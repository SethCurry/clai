(ns clai.core
  "Command-line entry point for `clai`.

  With no arguments (or with `agent`) it serves the ACP agent over stdio, the
  way an ACP client such as an editor launches it. With `repl` it launches a
  REPLY REPL instead: `clai.repl` wires a client and the agent back to back
  over queue channels (see `src/user.clj` for the helpers it refers into
  `user`), so the agent can be driven from the REPL while stdout stays free
  for the conversation."
  (:require [acp.agent :as acp]
            [clai.agent :as agent]
            [clai.instrument :as instrument]
            [clai.log]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Agent over stdio
;; ---------------------------------------------------------------------------

(defn make-agent
  "Build a fresh agent with its own built-in tool registry.

  `opts` is passed through to `clai.agent/ollama-agent`; supply
  `:tool-registry` (or `:tools`) to give the agent a custom registry. Config
  `:tools` ids still narrow whichever registry is supplied."
  ([] (make-agent {}))
  ([opts] (agent/ollama-agent opts)))

(defn serve-agent
  "Start the `clai` agent over stdio: read ACP messages from stdin and answer
  `session/prompt` requests by calling the Ollama chat API. Blocks until stdin
  reaches EOF (the client disconnected)."
  []
  (instrument/start!)
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

;; ---------------------------------------------------------------------------
;; REPLY REPL
;; ---------------------------------------------------------------------------

(def repl-init
  "The form evaluated in `user` before the REPL's first prompt.

  `user` is the project's REPL bootstrap (`src/user.clj`): it routes Telemere
  output to a log file and refers the `clai.repl` helpers (plus the shell
  integration namespaces) into `user`, so `clai repl` starts with the same
  helpers a `lein repl` session does."
  '(require 'user))

(defn- with-clai-init
  "Add [[repl-init]] to REPLY `options`, preserving a user-provided `--init`."
  [options]
  (update options :custom-init
          (fn [custom]
            (if custom
              `(do ~repl-init ~custom)
              repl-init))))

(defn launch-repl
  "Launch a REPLY REPL, forwarding `args` to REPLY's own option parser.

  The options are the ones `reply.main/parse-args` understands, such as
  `--standalone` (evaluate in this JVM without an nREPL server), `--port` or
  `--attach` (choose an nREPL server or remote session), `--color`, and
  `--history-file`; run `clai repl --help` to see them all. Whatever is
  passed, the REPL loads the `clai` bootstrap ([[repl-init]]) before the first
  prompt, and an explicit `--init` form runs after it.

  Returns nil after the REPL exits."
  [args]
  (let [parse-args (requiring-resolve 'reply.main/parse-args)
        launch     (requiring-resolve 'reply.main/launch)
        [options _remaining banner]
        (try
          (parse-args (vec args))
          (catch Exception e
            ;; Report bad options the way REPLY's own `-main` does.
            (println (ex-message e))
            (parse-args ["--help"])))]
    (if (:help options)
      (println banner)
      (launch (with-clai-init options)))
    nil))

;; ---------------------------------------------------------------------------
;; Command dispatch
;; ---------------------------------------------------------------------------

(def commands
  "The `clai` subcommands, as `[name description]` pairs."
  [["agent" "Serve the ACP agent over stdio; the default command."]
   ["repl"  "Launch an interactive REPLY REPL that drives the agent in-process."]])

(defn usage
  "The `clai` usage text."
  []
  (str/join "\n"
            (into ["Usage: clai [command] [args...]" "" "Commands:"]
                  (concat (map (fn [[command description]]
                                 (format "  %-6s %s" command description))
                               commands)
                          ["" "Run `clai repl --help` for the REPL's options."]))))

(defn run
  "Run the `clai` CLI described by `args` and return an exit status.

  `args` does not include the program name. With no command the ACP agent is
  served, which keeps `clai` backwards compatible with clients that launch it
  without arguments. Prints the usage text for `help` (or `--help`/`-h`) and
  reports an unknown command on stderr with a non-zero status."
  [args]
  (let [command (or (first args) "agent")]
    (case command
      "agent" (do (serve-agent) 0)
      "repl"  (do (launch-repl (rest args)) 0)
      ("help" "--help" "-h") (do (println (usage)) 0)
      (do
        (binding [*out* *err*]
          (println (str "Unknown command: " command))
          (println)
          (println (usage)))
        1))))

(defn -main
  "Run the `clai` CLI with `args`."
  [& args]
  (let [status (run args)]
    ;; REPLY uses futures for parsing and completion, and the agent may leave
    ;; pool threads behind; shutting the pools down lets the JVM exit once the
    ;; REPL or the stdio server finishes.
    (shutdown-agents)
    (when-not (zero? status)
      (System/exit status))))
