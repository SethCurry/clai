(ns clai.core
  "Command-line entry point for `clai`.

  With `nrepl` it starts an nREPL server and publishes the port it listens on
  in `.nrepl-port`, the same convention Leiningen and `clj` use. With `shell`
  it launches a REPLY REPL that attaches to that server instead of starting an
  nREPL server of its own, so the REPL and the agent run in the server's JVM.
  `clai.repl` drives the in-process agent directly (see `src/user.clj` for the
  helpers it refers into `user`), so the agent can be used from the REPL while
  stdout stays free for the conversation. Pass `--standalone` to `shell` to
  run the REPL in this process instead.

  With no arguments (or `help`) it prints usage."
  (:require [clai.log]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; nREPL server
;; ---------------------------------------------------------------------------

(def port-file
  "The file `clai nrepl` publishes its port in and `clai shell` reads it from.

  The name matches the convention Leiningen and `clj` use, so other nREPL
  clients can find the server too."
  ".nrepl-port")

(defn running-nrepl-port
  "The port advertised by [[port-file]], or nil when no server published one."
  []
  (let [file (io/file port-file)]
    (when (.exists file)
      (try
        (parse-long (str/trim (slurp file)))
        (catch Exception _ nil)))))

(defn- delete-port-file!
  "Delete [[port-file]] if it still advertises `port`."
  [port]
  (try
    (let [file (io/file port-file)]
      (when (and (.exists file)
                 (= (str port) (str/trim (slurp file))))
        (.delete file)))
    (catch Exception _ nil)))

(defn- parse-nrepl-port
  "The port requested by `clai nrepl`'s arguments: either none (the OS picks a
  free port) or a single port number, where 0 also means a free port."
  [args]
  (let [args (vec args)]
    (cond
      (empty? args) nil
      (not= 1 (count args))
      (throw (ex-info "Usage: clai nrepl [port]"
                      {:clai/usage true}))
      :else
      (let [port (parse-long (first args))]
        (if (and port (<= 0 port 65535))
          port
          (throw (ex-info (str "Invalid nREPL port: " (first args)
                               "\nUsage: clai nrepl [port]")
                          {:clai/usage true})))))))

(defn start-nrepl!
  "Start an nREPL server, record its port in [[port-file]], and return the
  server map.

  `port` is the port to listen on; nil or 0 lets the OS pick a free one. The
  port file is removed when the JVM shuts down."
  [port]
  (let [start-server (requiring-resolve 'nrepl.server/start-server)
        server       (start-server :port (or port 0))
        bound        (:port server)]
    (spit port-file (str bound))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(delete-port-file! bound)))
    server))

(defn- await-shutdown
  "Block until the JVM exits; the server keeps running until then."
  []
  @(promise))

(defn serve-nrepl
  "Start an nREPL server and block until the JVM exits.

  The port comes from the optional argument, and is written to [[port-file]]
  so `clai shell` (and any other nREPL client) can attach. Returns an exit
  status: 0 once the JVM shuts down, 1 when the arguments or the server are
  bad."
  [args]
  (try
    (let [server (start-nrepl! (parse-nrepl-port args))
          port   (:port server)]
      (println (str "nREPL server listening on port " port))
      (println "Run `clai shell` to attach, or connect another nREPL client.")
      (flush)
      (await-shutdown)
      0)
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println (ex-message e)))
      1)
    (catch Throwable e
      (binding [*out* *err*]
        (println (str "Could not start the nREPL server: " (ex-message e))))
      1)))

;; ---------------------------------------------------------------------------
;; REPLY REPL
;; ---------------------------------------------------------------------------

(def repl-init
  "The form evaluated in `user` before the REPL's first prompt.

  `user` is the project's REPL bootstrap (`src/user.clj`): it routes Telemere
  output to a log file and refers the `clai.repl` helpers (plus the shell
  integration namespaces) into `user`, so `clai shell` starts with the same
  helpers a `lein repl` session does."
  '(require 'user))

(def ^:private nrepl-print-options
  "Options that ask an nREPL server to pretty-print evaluated values.

  REPLY receives each value from the server already printed, so the server is
  the only place where the default single-line `pr` output can be changed.
  Pointing the printing middleware at `nrepl.util.print/pprint` wraps long
  and nested results at the pretty printer's right margin."
  {:nrepl.middleware.print/print 'nrepl.util.print/pprint})

(defn- pprint-value
  "Pretty-print `value` to a string, for REPLY's standalone mode."
  [value]
  (pprint/write value :stream nil))

(defn- with-pprint-values
  "Make REPLY pretty-print returned values instead of using `pr`'s single line.

  In nREPL mode the printing happens on the server, so the options REPLY
  merges into every `eval` request are extended. In standalone mode REPLY
  prints locally, so the default `--value-to-string` (`pr-str`) is replaced
  with [[pprint-value]]. An explicitly configured printer wins, since the
  existing entries of `:nrepl-context` and a non-default `:value-to-string`
  are kept."
  [options]
  (cond-> (update options :nrepl-context
                  (fn [context]
                    (update (or context {}) :interactive-eval
                            #(merge nrepl-print-options %))))
    (and (:standalone options)
         (contains? #{"pr-str" nil} (:value-to-string options)))
    (assoc :value-to-string pprint-value)))

(defn- with-clai-init
  "Add [[repl-init]] to REPLY `options`, preserving a user-provided `--init`,
  and pretty-print returned values (see [[with-pprint-values]])."
  [options]
  (-> options
      (update :custom-init
              (fn [custom]
                (if custom
                  `(do ~repl-init ~custom)
                  repl-init)))
      with-pprint-values))

(defn- attach-to-server
  "Point REPLY `options` at the server advertised by [[port-file]].

  The port is only added when the user did not choose a server themselves
  with `--attach`, start one with `--port`, run without one with
  `--standalone`, or ask for `--help`. Returns the options, or nil when no
  server is running."
  [options]
  (cond
    (or (:help options) (:standalone options) (:attach options) (:port options))
    options

    :else
    (when-let [port (running-nrepl-port)]
      (assoc options :attach (str port)))))

(defn launch-repl
  "Attach a REPLY REPL to the running nREPL server, forwarding `args` to
  REPLY's own option parser.

  The server is the one whose port is published in [[port-file]] (normally
  started with `clai nrepl`). The options are the ones
  `reply.main/parse-args` understands: `--attach` or `--port` choose another
  server, `--standalone` runs the REPL in this process instead, and
  `--color`/`--history-file` configure the REPL; run `clai shell --help` to
  see them all. Whatever is passed, the REPL loads the `clai` bootstrap
  ([[repl-init]]) before the first prompt, and an explicit `--init` form runs
  after it. Returned values are pretty-printed by default, so long and nested
  results wrap instead of running together on one line.

  Returns an exit status: 0 after the REPL exits, or 1 when no nREPL server
  is running and REPLY was not asked to start or find one itself."
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
      (do (println banner) 0)
      (if-let [options (attach-to-server options)]
        (do (launch (with-clai-init options)) 0)
        (do
          (binding [*out* *err*]
            (println "No nREPL server found; start one with `clai nrepl` first.")
            (println "Pass --standalone to run the REPL in this process instead,"
                     "or --attach PORT to connect to another server."))
          1)))))

;; ---------------------------------------------------------------------------
;; Command dispatch
;; ---------------------------------------------------------------------------

(def commands
  "The `clai` subcommands, as `[name description]` pairs."
  [["nrepl" "Start an nREPL server and publish its port in .nrepl-port."]
   ["shell" "Attach an interactive REPLY REPL to a running nREPL server."]])

(defn usage
  "The `clai` usage text."
  []
  (str/join "\n"
            (into ["Usage: clai [command] [args...]" "" "Commands:"]
                  (concat (map (fn [[command description]]
                                 (format "  %-6s %s" command description))
                               commands)
                          [""
                           "Run `clai nrepl [port]` to start a server."
                           "Run `clai shell --help` for the REPL's options."]))))

(defn run
  "Run the `clai` CLI described by `args` and return an exit status.

  `args` does not include the program name. With no command it prints the
  usage text, as it does for `help` (or `--help`/`-h`). Reports an unknown
  command on stderr with a non-zero status."
  [args]
  (let [command (first args)]
    (case command
      "nrepl" (serve-nrepl (rest args))
      "shell" (launch-repl (rest args))
      (nil "help" "--help" "-h") (do (println (usage)) 0)
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
    ;; REPL finishes.
    (shutdown-agents)
    (when-not (zero? status)
      (System/exit status))))
