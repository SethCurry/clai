(ns clai.shell.task
  "REPL-friendly helpers for driving the `task` binary (go-task).

  The core primitive is [[run]]. It starts `task` directly (never through a
  shell), captures stdout and stderr separately, and returns a result map
  without throwing when task exits non-zero:

      (run [\"build\"] {:dir \"/path/to/project\"})
      ;; => {:command [\"task\" \"build\"]
      ;;     :dir \"/path/to/project\"
      ;;     :exit 0
      ;;     :out \"...\"
      ;;     :err \"\"
      ;;     :success? true
      ;;     :timed-out? false}

  [[task]] is the interactive counterpart: it prints stdout/stderr as task
  itself would and returns the same map. [[run!]] and [[task!]] throw an
  `ex-info` when task fails; the exception's data carries the command, exit
  code, stdout, and stderr.

  The named helpers ([list], [summary], [status], [version], ...) are silent
  and return result maps or parsed values:

      (->> (list {:dir \"/project\"})
           :tasks
           (map :name))

      (run! \"build\" {:dir \"/project\"})

  The working directory is virtual and shared with the other CLI helper
  namespaces. It comes from `:dir`, then `clai.shell.cli/*cwd*`, then the JVM's
  working directory. It can be set for a single call with the `:dir` option or
  bound for a series of calls:

      (binding [clai.shell.cli/*cwd* \"/project\"]
        (task/list))

  Common options are accepted in a map, in any position:

    :taskfile    path to a Taskfile (`--taskfile`)
    :force?      run even when a task is up-to-date
    :dry-run?    print the task commands without executing them
    :silent?     disable command echoing
    :verbose?    enable verbose output
    :parallel?   run the named tasks in parallel
    :concurrency limit the number of concurrent tasks
    :output      output style (`interleaved`, `group`, or `prefixed`)
    :color       true or false (maps to `--color=...`)
    :watch?      watch the task for changes
    :exit-code?  pass through the task command's exit code
    :status?     check whether a task is up-to-date
    :summary?    show a task summary instead of running it
    :vars        task variables as a map or sequence of `KEY=value` strings

  As with the other CLI helpers, commands are run with the configured timeout
  (`clai.shell.cli/*timeout-ms*`, 30 seconds by default)."
  (:refer-clojure :exclude [list run!])
  (:require [clai.shell.cli :as cli]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Argument construction
;; ---------------------------------------------------------------------------

(defn- global-args
  "Build task's global options from an options map."
  [{:keys [taskfile force? dry? dry-run? silent? verbose? parallel?
           concurrency output color interval watch? exit-code? status?
           summary? init? list? list-all?]}]
  (cond-> []
    (some? taskfile)    (conj "--taskfile" (cli/arg->string taskfile))
    force?              (conj "--force")
    (or dry? dry-run?)  (conj "--dry")
    silent?             (conj "--silent")
    verbose?            (conj "--verbose")
    parallel?           (conj "--parallel")
    (some? concurrency) (conj "--concurrency" (str concurrency))
    (some? output)      (conj "--output" (cli/arg->string output))
    (some? color)       (conj (str "--color=" color))
    (some? interval)    (conj "--interval" (cli/arg->string interval))
    watch?              (conj "--watch")
    exit-code?          (conj "--exit-code")
    status?             (conj "--status")
    summary?            (conj "--summary")
    init?               (conj "--init")
    list?               (conj "--list")
    list-all?           (conj "--list-all")))

(defn- var-args
  "Render task variables as command-line `KEY=value` arguments."
  [vars]
  (cond
    (nil? vars)
    []

    (map? vars)
    (mapv (fn [[k v]] (str (cli/arg->string k) "=" (cli/arg->string v)))
          vars)

    (sequential? vars)
    (mapv cli/arg->string vars)

    :else
    [(cli/arg->string vars)]))

(defn- build-args
  "Build the complete argument vector for a task invocation."
  [tasks opts]
  (let [tasks (cli/normalize-args tasks)
        args  (into (global-args opts) tasks)]
    (into args (var-args (:vars opts)))))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(defn run
  "Run `task` with `tasks` and `opts`, capturing its output.

  `tasks` may be a task name, a collection of task names, or nil. Options are
  described in the namespace docstring. The virtual working directory is
  selected from `:dir`, then `clai.shell.cli/*cwd*`, then `user.dir`.

  Returns a map with `:command`, `:dir`, `:exit`, `:out`, `:err`, `:success?`,
  and `:timed-out?`. A non-zero exit is not an error; use [[run!]] for that."
  ([tasks] (run tasks {}))
  ([tasks opts]
   (cli/run "task" (build-args tasks opts)
            (assoc opts :label "task" :data-prefix "task"))))

(defn check!
  "Return `result` when task succeeded, or throw an `ex-info` describing the
  failure. The exception's data contains the command, exit, stdout, and
  stderr."
  [result]
  (cli/check! result {:label "task" :data-prefix "task"}))

(defn run!
  "Like [[run]], but throw when task exits non-zero. Returns the result map."
  ([tasks] (run! tasks {}))
  ([tasks opts] (check! (run tasks opts))))

(defn task
  "Run task and print its stdout and stderr, like the `task` command itself.

  Takes variadic task arguments plus an optional options map, in any position:

      (task \"build\")
      (task {:dir \"/project\"} \"test\" \"--force\")
      (task [\"build\" \"test\"])

  Returns [[run]]'s result map. A non-zero exit is not thrown; use [[task!]]
  for that."
  [& args]
  (let [[opts args] (cli/split-args args)]
    (cli/echo-result! "task" (run args opts))))

(defn task!
  "Like [[task]], but throw when task exits non-zero after printing its
  output."
  [& args]
  (let [[opts args] (cli/split-args args)]
    (check! (cli/echo-result! "task" (run args opts)))))

(defn out
  "Run task and return its trimmed stdout, throwing when it fails."
  ([tasks] (out tasks {}))
  ([tasks opts] (str/trim (:out (run! tasks opts)))))

;; ---------------------------------------------------------------------------
;; Task listing
;; ---------------------------------------------------------------------------

(defn- parse-task-line
  "Parse one `* name: description (aliases: ...)` line from `task --list`."
  [line]
  (when-let [[_ name description]
             (re-matches #"^\*\s+(.+?):(?:\s+(.*))?$" line)]
    (let [description (str/trim (or description ""))
          alias-pattern #"\s*\(aliases:\s*([^)]*)\)\s*$"
          aliases (some-> (re-find alias-pattern description)
                          second
                          (str/split #"\s*,\s*"))
          description (-> (str/replace description alias-pattern "")
                          str/trim
                          not-empty)]
      {:name        name
       :description description
       :aliases     (vec (remove str/blank? aliases))})))

(defn- parse-task-list
  "Parse the output of `task --list` / `task --list-all`."
  [text]
  (->> (str/split-lines (or text ""))
       (keep parse-task-line)
       vec))

(defn- with-list-mode
  "Choose `--list` or `--list-all` for a listing call."
  [opts]
  (cond
    (contains? opts :all?)
    (-> opts
        (dissoc :all?)
        (assoc :list?     (not (:all? opts))
               :list-all? (boolean (:all? opts))))

    (or (:list? opts) (:list-all? opts))
    opts

    :else
    (assoc opts :list-all? true)))

(defn list
  "List tasks and return `{:tasks [...], :raw \"...\", :result {...}}`.

  Each task is a map with `:name`, `:description` (nil when absent), and
  `:aliases` (a vector). By default all tasks are listed, including those
  without descriptions. Pass `:all? false` to list only described tasks."
  ([] (list {}))
  ([opts]
   (let [result (run! nil (with-list-mode opts))]
     {:tasks  (parse-task-list (:out result))
      :raw    (:out result)
      :result result})))

(defn tasks
  "Return the parsed task list (without the raw output or result map).

  Options are the same as [[list]]."
  ([] (tasks {}))
  ([opts] (:tasks (list opts))))

(defn list-all
  "List every task, including tasks without descriptions. See [[list]]."
  ([] (list-all {}))
  ([opts] (list (assoc opts :all? true))))

;; ---------------------------------------------------------------------------
;; Task summaries and status
;; ---------------------------------------------------------------------------

(def ^:private summary-section-pattern #"^[a-zA-Z][a-zA-Z -]*:$")

(defn- trim-blank-lines
  "Remove leading and trailing blank lines from a sequence."
  [lines]
  (->> lines
       (drop-while str/blank?)
       reverse
       (drop-while str/blank?)
       reverse))

(defn- parse-section-values
  "Parse the ` - value` entries of a summary section, appending unindented
  continuation lines to the previous entry."
  [lines]
  (reduce (fn [values line]
            (let [line (str/trim line)]
              (cond
                (str/blank? line) values

                (str/starts-with? line "- ")
                (conj values (str/trim (subs line 2)))

                (seq values)
                (update values (dec (count values)) #(str % "\n" line))

                :else
                (conj values line))))
          []
          lines))

(defn- parse-summary
  "Parse the output of `task --summary`."
  [text]
  (let [lines           (mapv str/trimr (str/split-lines (or text "")))
        header          (first lines)
        name            (some-> header
                                (str/replace #"^task:\s*" "")
                                not-empty)
        body            (vec (rest lines))
        section-starts  (->> (map-indexed (fn [i line]
                                            (when (re-matches
                                                   summary-section-pattern
                                                   line)
                                              i))
                                          body)
                             (remove nil?)
                             vec)
        first-section   (or (first section-starts) (count body))
        description     (->> (subvec body 0 first-section)
                             trim-blank-lines
                             (str/join "\n")
                             not-empty)
        description     (when-not (= description
                                      "(task does not have description or summary)")
                          description)
        bounds          (map vector section-starts
                             (rest (conj section-starts (count body))))
        sections        (reduce (fn [m [start end]]
                                  (let [k (-> (body start)
                                              (str/replace #":$" "")
                                              str/trim
                                              keyword)]
                                    (assoc m k (parse-section-values
                                                (subvec body (inc start) end)))))
                                {}
                                bounds)]
    (merge {:name        name
            :description description
            :raw         text}
           sections)))

(defn summary
  "Return a parsed `task --summary` for `task-name`.

  The result contains `:name`, `:description`, `:raw`, and one vector per
  summary section, such as `:commands`, `:dependencies`, `:sources`, and
  `:generates`."
  ([task-name] (summary task-name {}))
  ([task-name opts]
   (parse-summary (:out (run! (cli/arg->string task-name)
                             (assoc opts :summary? true))))))

(defn status
  "Run `task --status`, which exits non-zero when the task is not up-to-date.

  Returns a result map; a not-up-to-date task is not thrown. See
  [[up-to-date?]] for the boolean."
  ([] (status nil {}))
  ([tasks-or-opts]
   (if (map? tasks-or-opts)
     (status nil tasks-or-opts)
     (status tasks-or-opts {})))
  ([tasks opts]
   (run tasks (assoc opts :status? true))))

(defn up-to-date?
  "True when `task --status` reports that the task is up-to-date."
  ([] (up-to-date? nil {}))
  ([tasks-or-opts]
   (if (map? tasks-or-opts)
     (up-to-date? nil tasks-or-opts)
     (up-to-date? tasks-or-opts {})))
  ([tasks opts]
   (:success? (status tasks opts))))

(defn dry-run
  "Compile the task and print the commands without executing them."
  ([] (dry-run nil {}))
  ([tasks-or-opts]
   (if (map? tasks-or-opts)
     (dry-run nil tasks-or-opts)
     (dry-run tasks-or-opts {})))
  ([tasks opts]
   (run! tasks (assoc opts :dry-run? true))))

;; ---------------------------------------------------------------------------
;; Miscellaneous commands
;; ---------------------------------------------------------------------------

(defn version
  "Return the installed task version string."
  ([] (version {}))
  ([opts] (out ["--version"] opts)))

(defn init
  "Create a new Taskfile in the virtual working directory. Returns the result
  map."
  ([] (init {}))
  ([opts] (run! nil (assoc opts :init? true))))
