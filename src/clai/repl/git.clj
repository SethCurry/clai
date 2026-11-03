(ns clai.repl.git
  "REPL-friendly helpers for driving the `git` CLI.

  The core primitive is [[run]]. It starts `git` directly (never through a
  shell), captures stdout and stderr separately, and returns a result map
  without throwing when git exits non-zero:

      (run [\"status\" \"--short\"] {:dir \"/path/to/repo\"})
      ;; => {:command [\"git\" \"status\" \"--short\"]
      ;;     :dir \"/path/to/repo\"
      ;;     :exit 0
      ;;     :out \"...\"
      ;;     :err \"\"
      ;;     :success? true
      ;;     :timed-out? false}

  [[git]] is the interactive counterpart: it prints stdout/stderr as git
  itself would and returns the same map. [[run!]] and [[git!]] throw an
  `ex-info` when git fails; the exception's data carries the command, exit
  code, stdout, and stderr.

  The named helpers ([status], [log], [diff], [add], [commit], ...) are silent
  and return result maps or parsed values. That makes them easy to compose:

      (->> (status {:dir \"/repo\"})
           :untracked
           (map :path))

      (commit \"Fix the parser\" {:dir \"/repo\"})

  The working directory defaults to [[*dir*]], then to the JVM's working
  directory. It can be set for a single call with the `:dir` option or bound
  for a series of calls:

      (binding [git/*dir* \"/repo\"] (status))

  Commands are non-interactive by default: the pager is disabled, the editor
  is set to `true` (so a missing commit message fails instead of hanging), and
  terminal credential prompts are turned off. Override any of those with the
  `:env` option, for example `{:env {\"GIT_EDITOR\" \"vim\"}}`."
  (:refer-clojure :exclude [merge run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream File InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *dir*
  "Default working directory for git commands. When nil, the JVM's working
  directory (`user.dir`) is used."
  nil)

(def ^:dynamic *timeout-ms*
  "Default number of milliseconds to wait for a git command before killing it.
  Bind or pass `:timeout` to change it; pass `:timeout nil` to wait forever."
  30000)

(def ^:private default-env
  "Environment defaults that keep git commands from blocking a REPL."
  {"GIT_PAGER"           "cat"
   "PAGER"               "cat"
   "GIT_EDITOR"          "true"
   "GIT_SEQUENCE_EDITOR" "true"
   "GIT_TERMINAL_PROMPT" "0"})

;; ---------------------------------------------------------------------------
;; Process plumbing
;; ---------------------------------------------------------------------------

(defn- arg->string
  "Render one git argument. Keywords and symbols lose their leading colon, so
  `:--oneline` and `'--oneline` both work as flags."
  [arg]
  (cond
    (keyword? arg) (name arg)
    (symbol? arg)  (name arg)
    :else          (str arg)))

(defn- normalize-args
  "Normalize an args value to a vector of strings."
  [args]
  (cond
    (nil? args)        []
    (string? args)     [args]
    (sequential? args) (mapv arg->string args)
    :else              [(arg->string args)]))

(defn- resolve-dir
  "Resolve the working directory for a command, verifying that it exists."
  [opts]
  (let [dir (io/file (arg->string (or (:dir opts) *dir*
                                      (System/getProperty "user.dir"))))]
    (when-not (.isDirectory dir)
      (throw (ex-info (str "Not a git working directory: " (.getPath dir))
                      {:git/dir (.getAbsolutePath dir)})))
    (.getAbsoluteFile dir)))

(defn- start-pump!
  "Start a daemon thread copying `in` into `buf`. Returns the thread."
  [^InputStream in ^ByteArrayOutputStream buf thread-name]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     (with-open [in in]
                       (io/copy in buf))
                     (catch Throwable _ nil)))
                 thread-name)
    (.setDaemon true)
    (.start)))

(defn- start-input!
  "Start a daemon thread feeding `in` to the process's stdin and closing it.

  `in` may be nil, a string, a byte array, or an `InputStream`. Closing stdin
  is important: otherwise a command that reads stdin can wait forever."
  [^OutputStream out in]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     (with-open [out out]
                       (when (some? in)
                         (cond
                           (instance? InputStream in) (io/copy in out)
                           (bytes? in)                (.write out ^bytes in)
                           :else
                           (.write out (.getBytes (str in)
                                                  StandardCharsets/UTF_8)))))
                     (catch Throwable _ nil)))
                 "clai-git-stdin")
    (.setDaemon true)
    (.start)))

(defn- join-thread!
  "Wait briefly for a pump thread to finish."
  [^Thread thread]
  (when thread
    (try
      (.join thread 2000)
      (catch InterruptedException _ nil))))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(defn run
  "Run `git` with `args` in `opts`' `:dir` and capture its output.

  `args` is a string or a sequential collection of strings, keywords, and
  symbols; arguments are passed to git verbatim, so no shell quoting or
  escaping is involved. `opts` may contain:

    :dir      working directory (defaults to [[*dir*]] or `user.dir`)
    :timeout  milliseconds to wait, or nil to wait forever
    :in       stdin contents (a string, byte array, or InputStream)
    :env      extra environment variables; a nil value removes one

  Returns a map with `:command`, `:dir`, `:exit`, `:out`, `:err`, `:success?`,
  and `:timed-out?`. A non-zero exit is not an error; use [[run!]] for that."
  ([args] (run args {}))
  ([args opts]
   (let [args     (normalize-args args)
         dir      (resolve-dir opts)
         command  (into ["git"] args)
         timeout  (if (contains? opts :timeout) (:timeout opts) *timeout-ms*)
         builder  (doto (ProcessBuilder. ^java.util.List command)
                    (.directory dir)
                    (.redirectErrorStream false))
         env      (.environment builder)]
     (doseq [[k v] (clojure.core/merge default-env (:env opts))]
       (if (nil? v)
         (.remove env (arg->string k))
         (.put env (arg->string k) (str v))))
     (let [proc (try
                  (.start builder)
                  (catch java.io.IOException e
                    (throw (ex-info (str "Could not start git: "
                                         (or (ex-message e) (str (class e))))
                                    {:git/command command
                                     :git/dir     (.getAbsolutePath dir)}
                                    e))))
           out-buf    (ByteArrayOutputStream.)
           err-buf    (ByteArrayOutputStream.)
           out-thread (start-pump! (.getInputStream proc) out-buf
                                   "clai-git-stdout")
           err-thread (start-pump! (.getErrorStream proc) err-buf
                                   "clai-git-stderr")
           in-thread  (start-input! (.getOutputStream proc) (:in opts))
           finished   (try
                        (if (some? timeout)
                          (.waitFor proc (long timeout) TimeUnit/MILLISECONDS)
                          (do (.waitFor proc) true))
                        (catch InterruptedException _
                          false))
           timed-out? (not finished)
           _          (when timed-out? (.destroyForcibly proc))
           _          (when timed-out?
                        (try
                          (.waitFor proc 2000 TimeUnit/MILLISECONDS)
                          (catch InterruptedException _ nil)))
           exit       (when-not timed-out? (.exitValue proc))]
       (join-thread! out-thread)
       (join-thread! err-thread)
       (join-thread! in-thread)
       {:command    command
        :dir        (.getAbsolutePath dir)
        :exit       exit
        :out        (String. (.toByteArray out-buf) StandardCharsets/UTF_8)
        :err        (String. (.toByteArray err-buf) StandardCharsets/UTF_8)
        :success?   (and (some? exit) (zero? exit))
        :timed-out? timed-out?}))))

(defn check!
  "Return `result` when git succeeded, or throw an `ex-info` describing the
  failure. The exception's data contains the command, exit, stdout, and
  stderr."
  [{:keys [command exit out err timed-out?] :as result}]
  (if (:success? result)
    result
    (let [detail (str/trim (if (str/blank? (str err)) (str out) (str err)))]
      (throw (ex-info (str "git command failed"
                           (when (some? exit) (str " (exit " exit ")"))
                           (when timed-out? " (timed out)")
                           ": " (str/join " " command)
                           (when (seq detail) (str "\n" detail)))
                      {:git/command    command
                       :git/exit       exit
                       :git/out        out
                       :git/err        err
                       :git/timed-out? timed-out?})))))

(defn run!
  "Like [[run]], but throw when git exits non-zero. Returns the result map."
  ([args] (run! args {}))
  ([args opts] (check! (run args opts))))

(defn- echo-result!
  "Print a result map's stdout and stderr, without altering the map."
  [{:keys [out err timed-out?] :as result}]
  (when (pos? (count (str out)))
    (print (str out))
    (flush))
  (when (pos? (count (str err)))
    (binding [*out* *err*]
      (print (str err))
      (flush)))
  (when timed-out?
    (binding [*out* *err*]
      (println "[git timed out]")
      (flush)))
  result)

(defn- split-git-args
  "Separate an optional options map from variadic [[git]] arguments.

  The map may be first or last, and a single sequential argument is treated as
  the full argument list, so `(git {:dir d} \"status\")`, `(git \"status\"
  {:dir d})`, and `(git [\"status\"])` all work."
  [args]
  (let [args (vec args)
        [opts args] (cond
                      (and (seq args) (map? (first args)))
                      [(first args) (subvec args 1)]

                      (and (seq args) (map? (peek args)))
                      [(peek args) (pop args)]

                      :else
                      [{} args])
        args (if (and (= 1 (count args)) (sequential? (first args)))
               (vec (first args))
               args)]
    [opts args]))

(defn git
  "Run git and print its stdout and stderr, like the `git` command itself.

  Takes variadic arguments plus an optional options map, in any position:

      (git \"status\" \"--short\")
      (git {:dir \"/repo\"} \"log\" \"--oneline\" \"-5\")
      (git [\"diff\" \"--stat\"])

  Returns [[run]]'s result map. A non-zero exit is not thrown; use [[git!]]
  for that."
  [& args]
  (let [[opts args] (split-git-args args)]
    (echo-result! (run args opts))))

(defn git!
  "Like [[git]], but throw when git exits non-zero after printing its output."
  [& args]
  (let [[opts args] (split-git-args args)]
    (-> (run args opts)
        echo-result!
        check!)))

(defn out
  "Run git and return its trimmed stdout, throwing when it fails."
  ([args] (out args {}))
  ([args opts] (str/trim (:out (run! args opts)))))

;; ---------------------------------------------------------------------------
;; Status parsing
;; ---------------------------------------------------------------------------

(def ^:private conflict-statuses
  "Porcelain v1 index/worktree pairs that mean an unmerged path."
  #{"DD" "AU" "UD" "UA" "DU" "AA" "UU"})

(def ^:private ahead-pattern #"ahead (\d+)")
(def ^:private behind-pattern #"behind (\d+)")

(defn- unquote-path
  "Remove the double quotes git puts around unusual paths.

  Git's C-style escapes inside the quotes are left alone; this is a
  convenience for ordinary quoted paths, not a full unescaper."
  [path]
  (if (and (>= (count path) 2)
           (str/starts-with? path "\"")
           (str/ends-with? path "\""))
    (subs path 1 (dec (count path)))
    path))

(defn- parse-branch-line
  "Parse the `## ...` header of `git status --porcelain=v1 --branch`."
  [line]
  (when line
    (let [text (subs line 3)]
      (cond
        (str/starts-with? text "No commits yet on ")
        {:branch   (subs text (count "No commits yet on "))
         :initial? true}

        (str/starts-with? text "Initial commit on ")
        {:branch   (subs text (count "Initial commit on "))
         :initial? true}

        (= text "HEAD (no branch)")
        {:detached? true}

        :else
        (let [bracket  (str/index-of text " [")
              refs     (if bracket (subs text 0 bracket) text)
              tracking (when bracket
                         (subs text (+ bracket 2) (dec (count text))))
              dots     (str/index-of refs "...")
              branch   (if dots (subs refs 0 dots) refs)
              upstream (when dots (subs refs (+ dots 3)))]
          (cond-> {:branch (not-empty branch)}
            upstream
            (assoc :upstream upstream
                   :ahead    (or (when tracking
                                   (some-> (re-find ahead-pattern tracking)
                                           second
                                           parse-long))
                                 0)
                   :behind   (or (when tracking
                                   (some-> (re-find behind-pattern tracking)
                                           second
                                           parse-long))
                                 0))))))))

(defn- parse-status-entry
  "Parse one non-header porcelain v1 status line."
  [line]
  (let [xy       (subs line 0 2)
        path+    (subs line 3)
        rename   (str/index-of path+ " -> ")
        from     (when rename (subs path+ 0 rename))
        path     (if rename (subs path+ (+ rename 4)) path+)]
    (cond-> {:index    (subs xy 0 1)
             :worktree (subs xy 1 2)
             :status   (str/trim xy)
             :path     (unquote-path path)}
      from                  (assoc :from (unquote-path from))
      (= xy "??")           (assoc :untracked? true)
      (= xy "!!")           (assoc :ignored? true)
      (conflict-statuses xy) (assoc :conflicted? true))))

(defn status
  "Return a parsed `git status`, or throw when git fails.

  Uses porcelain v1 output and returns a map with the branch information
  (`:branch`, `:upstream`, `:ahead`, `:behind`, `:detached?`, `:initial?`),
  the raw `:entries`, and filtered views of them:

    :staged      entries with an index change
    :unstaged    entries with a worktree change
    :untracked   untracked paths
    :conflicted  unmerged paths

  Also includes `:dirty?`/`:clean?` and the raw porcelain output as `:raw`.
  Options may contain `:ignored?` to include ignored files and `:untracked`
  (`:normal`, `:all`, or `:no`) to control untracked file reporting."
  ([] (status {}))
  ([opts]
   (let [{:keys [out]}
         (run! (cond-> ["status" "--porcelain=v1" "--branch"]
                 (:ignored? opts) (conj "--ignored")
                 (:untracked opts) (conj (str "--untracked-files="
                                              (name (:untracked opts)))))
               opts)
         lines       (str/split-lines out)
         branch-line (first (filter #(str/starts-with? % "## ") lines))
         entries     (->> lines
                          (remove #(str/starts-with? % "## "))
                          (remove str/blank?)
                          (mapv parse-status-entry))
         branch      (or (parse-branch-line branch-line) {})
         changed?    (boolean (some #(not (:ignored? %)) entries))]
     (assoc branch
            :entries    entries
            :staged     (filterv #(and (not (:untracked? %))
                                       (not (:ignored? %))
                                       (not (#{" " "?" "!"} (:index %))))
                                 entries)
            :unstaged   (filterv #(and (not (:untracked? %))
                                       (not (:ignored? %))
                                       (not (#{" " "?" "!"} (:worktree %))))
                                 entries)
            :untracked  (filterv :untracked? entries)
            :conflicted (filterv :conflicted? entries)
            :dirty?     changed?
            :clean?     (not changed?)
            :raw        out))))

(defn clean?
  "True when the working tree has no changes (ignoring ignored files)."
  ([] (clean? {}))
  ([opts] (:clean? (status opts))))

(defn dirty?
  "True when the working tree has staged, unstaged, or untracked changes."
  ([] (dirty? {}))
  ([opts] (:dirty? (status opts))))

;; ---------------------------------------------------------------------------
;; Repository queries
;; ---------------------------------------------------------------------------

(defn current-branch
  "Return the current branch name, or nil when HEAD is detached."
  ([] (current-branch {}))
  ([opts]
   (let [{:keys [success? out]}
         (run ["symbolic-ref" "--short" "-q" "HEAD"] opts)]
     (when success?
       (not-empty (str/trim out))))))

(defn head
  "Return the full SHA of HEAD."
  ([] (head {}))
  ([opts] (out ["rev-parse" "HEAD"] opts)))

(defn root
  "Return the absolute path of the repository's top-level directory."
  ([] (root {}))
  ([opts] (out ["rev-parse" "--show-toplevel"] opts)))

(defn rev-parse
  "Resolve `rev` to a SHA (or whatever else `git rev-parse` prints)."
  ([rev] (rev-parse rev {}))
  ([rev opts] (out ["rev-parse" (arg->string rev)] opts)))

(defn branches
  "Return the local branch names as a vector of strings."
  ([] (branches {}))
  ([opts]
   (->> (out ["for-each-ref" "--format=%(refname:short)" "refs/heads"] opts)
        str/split-lines
        (remove str/blank?)
        vec)))

(defn tags
  "Return the tag names as a vector of strings."
  ([] (tags {}))
  ([opts]
   (->> (out ["for-each-ref" "--format=%(refname:short)" "refs/tags"] opts)
        str/split-lines
        (remove str/blank?)
        vec)))

(defn ls-files
  "Return the tracked file paths as a vector of strings."
  ([] (ls-files {}))
  ([opts]
   (->> (out ["ls-files"] opts)
        str/split-lines
        (remove str/blank?)
        vec)))

(defn remotes
  "Return the configured remote names as a vector of strings."
  ([] (remotes {}))
  ([opts]
   (->> (out ["remote"] opts)
        str/split-lines
        (remove str/blank?)
        vec)))

(defn remote-url
  "Return the URL configured for `remote` (default `\"origin\"`)."
  ([] (remote-url "origin" {}))
  ([remote] (remote-url remote {}))
  ([remote opts] (out ["remote" "get-url" (str remote)] opts)))

(defn config
  "Return the value of a git config key."
  ([key] (config key {}))
  ([key opts] (out ["config" "--get" (str key)] opts)))

(defn version
  "Return the installed git version string."
  ([] (version {}))
  ([opts] (out ["--version"] opts)))

;; ---------------------------------------------------------------------------
;; History and diffs
;; ---------------------------------------------------------------------------

(defn log
  "Run `git log` and return its result map.

  Options include `:limit`, `:oneline?`, `:format`, `:stat?`, `:all?`,
  `:revision`, and `:paths` (a string or collection of paths)."
  ([] (log {}))
  ([opts]
   (let [{:keys [limit oneline? format stat? all? revision paths]} opts
         args (cond-> ["log"]
                (some? limit) (conj "-n" (str limit))
                oneline?      (conj "--oneline" "--decorate")
                (some? format) (conj (str "--format=" format))
                stat?         (conj "--stat")
                all?          (conj "--all")
                (some? revision) (conj (arg->string revision))
                (seq paths)   (into (cons "--" (map arg->string paths))))]
     (run! args opts))))

(defn show
  "Run `git show` for `rev` (default `\"HEAD\"`) and return its result map.

  Options include `:stat?`, `:name-only?`, and `:paths`."
  ([] (show "HEAD" {}))
  ([rev] (show rev {}))
  ([rev opts]
   (let [{:keys [stat? name-only? paths]} opts
         args (cond-> ["show" (arg->string rev)]
                stat?      (conj "--stat")
                name-only? (conj "--name-only")
                (seq paths) (into (cons "--" (map arg->string paths))))]
     (run! args opts))))

(defn diff
  "Run `git diff` and return its result map.

  Options include `:staged?`/`:cached?`, `:stat?`, `:name-only?`, `:revision`,
  and `:paths`."
  ([] (diff {}))
  ([opts]
   (let [{:keys [staged? cached? stat? name-only? revision paths]} opts
         args (cond-> ["diff"]
                (or staged? cached?) (conj "--cached")
                stat?                (conj "--stat")
                name-only?           (conj "--name-only")
                (some? revision)     (conj (arg->string revision))
                (seq paths)          (into (cons "--" (map arg->string paths))))]
     (run! args opts))))

(defn blame
  "Run `git blame` on `path` and return its result map.

  Options include `:revision`, `:line-start`/`:line-end`, and
  `:ignore-whitespace?`."
  ([path] (blame path {}))
  ([path opts]
   (let [{:keys [revision line-start line-end ignore-whitespace?]} opts
         args (cond-> ["blame"]
                ignore-whitespace? (conj "-w")
                (and line-start line-end)
                (conj (str "-L" line-start "," line-end))
                (some? revision) (conj (arg->string revision))
                :always (conj (arg->string path)))]
     (run! args opts))))

(defn grep
  "Run `git grep` for `pattern` and return its result map.

  Options include `:revision`, `:ignore-case?`, and `:paths`."
  ([pattern] (grep pattern {}))
  ([pattern opts]
   (let [{:keys [revision ignore-case? paths]} opts
         args (cond-> ["grep"]
                ignore-case?     (conj "-i")
                (some? revision) (conj (arg->string revision))
                :always          (conj (arg->string pattern)))
         args (if (seq paths)
                (into args (cons "--" (map arg->string paths)))
                args)]
     (run! args opts))))

;; ---------------------------------------------------------------------------
;; Working tree
;; ---------------------------------------------------------------------------

(defn add
  "Stage `paths` (a string or collection). Options include `:all?`,
  `:update?`, and `:force?`."
  ([paths] (add paths {}))
  ([paths opts]
   (let [paths (if (string? paths) [paths] paths)
         args  (cond-> ["add"]
                 (:all? opts)    (conj "--all")
                 (:update? opts) (conj "--update")
                 (:force? opts)  (conj "--force"))
         args  (if (seq paths)
                 (into args (cons "--" (map arg->string paths)))
                 args)]
     (run! args opts))))

(defn add-all
  "Stage all changes, including deletions."
  ([] (add-all {}))
  ([opts] (run! ["add" "--all"] opts)))

(defn unstage
  "Unstage `paths` (a string or collection) without changing the worktree."
  ([paths] (unstage paths {}))
  ([paths opts]
   (let [paths (if (string? paths) [paths] paths)]
     (run! (into ["reset" "HEAD" "--"] (map arg->string paths)) opts))))

(defn restore
  "Restore `paths` (a string or collection). Options include `:staged?`,
  `:worktree?`, and `:source`."
  ([paths] (restore paths {}))
  ([paths opts]
   (let [paths (if (string? paths) [paths] paths)
         args  (cond-> ["restore"]
                 (:staged? opts)   (conj "--staged")
                 (:worktree? opts) (conj "--worktree")
                 (some? (:source opts))
                 (conj (str "--source=" (:source opts))))
         args  (into args (cons "--" (map arg->string paths)))]
     (run! args opts))))

(defn commit
  "Commit staged changes with `message`.

  Options include `:all?`, `:amend?`, `:allow-empty?`, `:no-verify?`,
  `:signoff?`, and `:author`."
  ([message] (commit message {}))
  ([message opts]
   (let [message (or message (:message opts))
         args    (cond-> ["commit"]
                   (some? message) (conj "-m" (str message))
                   (:all? opts)    (conj "--all")
                   (:amend? opts)  (conj "--amend")
                   (:allow-empty? opts) (conj "--allow-empty")
                   (:no-verify? opts)   (conj "--no-verify")
                   (:signoff? opts)     (conj "--signoff")
                   (some? (:author opts))
                   (conj (str "--author=" (:author opts))))]
     (run! args opts))))

(defn checkout
  "Check out `target`. Options include `:create?`, `:force?`, and `:detach?`."
  ([target] (checkout target {}))
  ([target opts]
   (run! (conj (cond-> ["checkout"]
                 (:create? opts) (conj "-b")
                 (:force? opts)  (conj "-f")
                 (:detach? opts) (conj "--detach"))
               (arg->string target))
         opts)))

(defn switch
  "Switch to `target` using `git switch`. Options include `:create?` and
  `:detach?`."
  ([target] (switch target {}))
  ([target opts]
   (run! (conj (cond-> ["switch"]
                 (:create? opts) (conj "-c")
                 (:detach? opts) (conj "--detach"))
               (arg->string target))
         opts)))

(defn create-branch
  "Create a branch named `name`, optionally from `:start-point`."
  ([name] (create-branch name {}))
  ([name opts]
   (let [args (cond-> ["branch"]
                (:force? opts) (conj "--force")
                :always        (conj (arg->string name))
                (some? (:start-point opts))
                (conj (arg->string (:start-point opts))))]
     (run! args opts))))

(defn delete-branch
  "Delete branch `name`. Pass `:force?` to delete an unmerged branch."
  ([name] (delete-branch name {}))
  ([name opts]
   (run! ["branch" (if (:force? opts) "-D" "-d") (arg->string name)] opts)))

(defn merge
  "Merge `branch`. Options include `:no-ff?`, `:squash?`, `:ff-only?`,
  `:abort?`, `:continue?`, and `:message`."
  ([branch] (merge branch {}))
  ([branch opts]
   (let [{:keys [no-ff? squash? ff-only? abort? continue? message]} opts
         args (cond-> ["merge"]
                abort?    (conj "--abort")
                continue? (conj "--continue")
                no-ff?    (conj "--no-ff")
                squash?   (conj "--squash")
                ff-only?  (conj "--ff-only")
                (some? message) (conj "-m" (str message))
                (and (some? branch) (not abort?) (not continue?))
                (conj (arg->string branch)))]
     (run! args opts))))

(defn rebase
  "Rebase onto `onto`. Options include `:interactive?`, `:continue?`,
  `:abort?`, `:skip?`, and `:autostash?`.

  Call with no `onto` to continue, abort, or skip a rebase in progress."
  ([] (rebase nil {}))
  ([opts-or-onto]
   (if (map? opts-or-onto)
     (rebase nil opts-or-onto)
     (rebase opts-or-onto {})))
  ([onto opts]
   (let [{:keys [interactive? continue? abort? skip? autostash?]} opts
         args (cond-> ["rebase"]
                interactive? (conj "--interactive")
                continue?    (conj "--continue")
                abort?       (conj "--abort")
                skip?        (conj "--skip")
                autostash?   (conj "--autostash")
                (and (some? onto)
                     (not (or continue? abort? skip?)))
                (conj (arg->string onto)))]
     (run! args opts))))

(defn reset
  "Reset the current branch to `target` (default `\"HEAD\"`).

  Options include `:soft?`, `:mixed?`, `:hard?`, `:keep?`, `:merge?`, and
  `:paths`."
  ([] (reset "HEAD" {}))
  ([target] (reset target {}))
  ([target opts]
   (let [mode (cond
                (:soft? opts)  "--soft"
                (:hard? opts)  "--hard"
                (:keep? opts)  "--keep"
                (:merge? opts) "--merge"
                :else          "--mixed")
         args (cond-> ["reset" mode (arg->string target)]
                (seq (:paths opts))
                (into (cons "--" (map arg->string (:paths opts)))))]
     (run! args opts))))

;; ---------------------------------------------------------------------------
;; Stashes
;; ---------------------------------------------------------------------------

(defn stash
  "Stash current changes. Options include `:message`, `:include-untracked?`,
  `:keep-index?`, `:staged?`, and `:paths`."
  ([] (stash {}))
  ([opts]
   (let [{:keys [message include-untracked? keep-index? staged? paths]} opts
         args (cond-> ["stash" "push"]
                (some? message)       (conj "-m" (str message))
                include-untracked?    (conj "--include-untracked")
                keep-index?           (conj "--keep-index")
                staged?               (conj "--staged")
                (seq paths)           (into (cons "--" (map arg->string paths))))]
     (run! args opts))))

(defn stash-list
  "Run `git stash list` and return its result map."
  ([] (stash-list {}))
  ([opts] (run! ["stash" "list"] opts)))

(defn- stash-action
  "Run a stash subcommand, optionally for `:index`."
  [action opts]
  (run! (cond-> ["stash" action]
          (some? (:index opts)) (conj (str "stash@{" (:index opts) "}")))
        opts))

(defn stash-pop
  "Apply and drop the stash at `:index` (default the most recent)."
  ([] (stash-pop {}))
  ([opts] (stash-action "pop" opts)))

(defn stash-apply
  "Apply the stash at `:index` without dropping it."
  ([] (stash-apply {}))
  ([opts] (stash-action "apply" opts)))

(defn stash-drop
  "Drop the stash at `:index` without applying it."
  ([] (stash-drop {}))
  ([opts] (stash-action "drop" opts)))

;; ---------------------------------------------------------------------------
;; Remotes and repository setup
;; ---------------------------------------------------------------------------

(defn fetch
  "Run `git fetch`. With a string argument, fetch that remote; with a map,
  use it as options. Options include `:prune?` and `:all?`."
  ([] (fetch {}))
  ([opts-or-remote]
   (if (map? opts-or-remote)
     (fetch nil opts-or-remote)
     (fetch opts-or-remote {})))
  ([remote opts]
   (run! (cond-> ["fetch"]
           (:prune? opts) (conj "--prune")
           (:all? opts)   (conj "--all")
           (some? remote) (conj (arg->string remote)))
         opts)))

(defn pull
  "Run `git pull`. With a string argument, pull from that remote; with a map,
  use it as options. Options include `:branch`, `:rebase?`, `:no-rebase?`, and
  `:ff-only?`."
  ([] (pull {}))
  ([opts-or-remote]
   (if (map? opts-or-remote)
     (pull nil opts-or-remote)
     (pull opts-or-remote {})))
  ([remote opts]
   (let [{:keys [branch rebase? no-rebase? ff-only?]} opts
         args (cond-> ["pull"]
                rebase?    (conj "--rebase")
                no-rebase? (conj "--no-rebase")
                ff-only?   (conj "--ff-only")
                (some? remote) (conj (arg->string remote))
                (some? branch) (conj (arg->string branch)))]
     (run! args opts))))

(defn push
  "Run `git push`. With a string argument, push to that remote; with a map,
  use it as options. Options include `:branch`, `:set-upstream?`, `:force?`
  (true for `--force`, `:lease` for `--force-with-lease`), `:tags?`, and
  `:dry-run?`."
  ([] (push {}))
  ([opts-or-remote]
   (if (map? opts-or-remote)
     (push nil opts-or-remote)
     (push opts-or-remote {})))
  ([remote opts]
   (let [{:keys [branch set-upstream? force? tags? dry-run?]} opts
         force-flag (cond
                      (= force? :lease) "--force-with-lease"
                      force?            "--force")
         args (cond-> ["push"]
                set-upstream? (conj "--set-upstream")
                force-flag    (conj force-flag)
                tags?         (conj "--tags")
                dry-run?      (conj "--dry-run")
                (some? remote) (conj (arg->string remote))
                (some? branch) (conj (arg->string branch)))]
     (run! args opts))))

(defn clone
  "Clone `url`. Options include `:path`, `:branch`, `:depth`, `:recursive?`,
  and `:bare?`."
  ([url] (clone url {}))
  ([url opts]
   (let [{:keys [path branch depth recursive? bare?]} opts
         args (cond-> ["clone"]
                (some? branch) (conj "--branch" (arg->string branch))
                (some? depth)  (conj "--depth" (str depth))
                recursive?     (conj "--recursive")
                bare?          (conj "--bare")
                :always        (conj (arg->string url))
                (some? path)   (conj (arg->string path)))]
     (run! args opts))))

(defn init
  "Initialize a repository. Options include `:path`, `:bare?`, and
  `:initial-branch`."
  ([] (init {}))
  ([opts]
   (let [{:keys [path bare? initial-branch]} opts
         args (cond-> ["init"]
                bare? (conj "--bare")
                (some? initial-branch)
                (conj (str "--initial-branch=" initial-branch))
                :always (conj (arg->string (or path "."))))]
     (run! args opts))))
