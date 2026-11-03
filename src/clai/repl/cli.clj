(ns clai.repl.cli
  "Shared REPL-friendly helpers for driving command-line programs.

  This is the process plumbing used by the program-specific namespaces such as
  `clai.repl.git`, `clai.repl.task`, and `clai.repl.docker`. The core primitive
  is [[run]]: it
  starts a program directly (never through a shell), captures stdout and
  stderr separately, and returns a result map without throwing when the
  program exits non-zero:

      (run \"git\" [\"status\" \"--short\"] {:dir \"/path/to/repo\"})
      ;; => {:command [\"git\" \"status\" \"--short\"]
      ;;     :dir \"/path/to/repo\"
      ;;     :exit 0
      ;;     :out \"...\"
      ;;     :err \"\"
      ;;     :success? true
      ;;     :timed-out? false}

  The working directory is virtual: it is chosen from the `:dir` option (or
  its `:cwd` alias), then [[*cwd*]], then the JVM's working directory. The
  child process sees that directory as its current directory, so relative
  paths passed as arguments are resolved by the program itself. Bind [[*cwd*]]
  to share a working directory across a series of calls:

      (binding [cli/*cwd* \"/repo\"] (git/status) (task/list))

  [[check!]] turns a failed result into an `ex-info`; [[echo-result!]] prints
  a result the way the underlying program would; [[run!]] combines them.

  Commands are non-interactive only insofar as the caller's environment
  defaults make them so. Pass `:env` to add or remove environment variables
  for a single call (`nil` removes a variable)."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream File InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:dynamic *cwd*
  "Default virtual working directory for CLI commands. When nil, the JVM's
  working directory (`user.dir`) is used. Bind this to share a working
  directory between commands and between program-specific helper namespaces."
  nil)

(def ^:dynamic *timeout-ms*
  "Default number of milliseconds to wait for a command before killing it.
  Bind or pass `:timeout` to change it; pass `:timeout nil` to wait forever."
  30000)

;; ---------------------------------------------------------------------------
;; Argument and directory handling
;; ---------------------------------------------------------------------------

(defn arg->string
  "Render one command argument. Keywords and symbols lose their leading colon,
  so `:--oneline` and `'--oneline` both work as flags."
  [arg]
  (cond
    (keyword? arg) (name arg)
    (symbol? arg)  (name arg)
    :else          (str arg)))

(defn normalize-args
  "Normalize an args value to a vector of strings."
  [args]
  (cond
    (nil? args)        []
    (string? args)     [args]
    (sequential? args) (mapv arg->string args)
    :else              [(arg->string args)]))

(defn resolve-dir
  "Resolve the virtual working directory for a command, verifying that it
  exists. Uses `:dir`, then `:cwd`, then [[*cwd*]], then `user.dir`."
  [opts]
  (let [dir (io/file (arg->string (or (:dir opts)
                                      (:cwd opts)
                                      *cwd*
                                      (System/getProperty "user.dir"))))]
    (when-not (.isDirectory dir)
      (throw (ex-info (str "Not a working directory: " (.getPath dir))
                      {:dir     (.getAbsolutePath dir)
                       :cli/dir (.getAbsolutePath dir)})))
    (.getAbsoluteFile dir)))

;; ---------------------------------------------------------------------------
;; Process plumbing
;; ---------------------------------------------------------------------------

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
  [^OutputStream out in thread-name]
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
                 thread-name)
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
  "Run `command` with `args` in `opts`' virtual working directory.

  `command` is the program name (or path) to execute. `args` is a string or a
  sequential collection of strings, keywords, and symbols; arguments are
  passed verbatim, so no shell quoting or escaping is involved. `opts` may
  contain:

    :dir      working directory (defaults to [[*cwd*]] or `user.dir`)
    :cwd      alias for `:dir`
    :timeout  milliseconds to wait, or nil to wait forever
    :in       stdin contents (a string, byte array, or InputStream)
    :env      extra environment variables; a nil value removes one

  Returns a map with `:command`, `:dir`, `:exit`, `:out`, `:err`, `:success?`,
  and `:timed-out?`. A non-zero exit is not an error; use [[run!]] for that."
  ([command args] (run command args {}))
  ([command args opts]
   (let [program  (arg->string command)
         args     (normalize-args args)
         dir      (resolve-dir opts)
         command  (into [program] args)
         timeout  (if (contains? opts :timeout) (:timeout opts) *timeout-ms*)
         builder  (doto (ProcessBuilder. ^java.util.List command)
                    (.directory dir)
                    (.redirectErrorStream false))
         env      (.environment builder)]
     (doseq [[k v] (:env opts)]
       (if (nil? v)
         (.remove env (arg->string k))
         (.put env (arg->string k) (str v))))
     (let [proc (try
                  (.start builder)
                  (catch java.io.IOException e
                    (throw (ex-info (str "Could not start " program ": "
                                         (or (ex-message e) (str (class e))))
                                    {:command     command
                                     :dir         (.getAbsolutePath dir)
                                     :cli/command command
                                     :cli/dir     (.getAbsolutePath dir)}
                                    e))))
           out-buf    (ByteArrayOutputStream.)
           err-buf    (ByteArrayOutputStream.)
           out-thread (start-pump! (.getInputStream proc) out-buf
                                   (str "clai-cli-" program "-stdout"))
           err-thread (start-pump! (.getErrorStream proc) err-buf
                                   (str "clai-cli-" program "-stderr"))
           in-thread  (start-input! (.getOutputStream proc) (:in opts)
                                    (str "clai-cli-" program "-stdin"))
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
  "Return `result` when the command succeeded, or throw an `ex-info`
  describing the failure.

  Options may contain `:label` (the program name used in the message) and
  `:data-prefix` (a namespace for exception data keys, such as `\"git\"` to
  include `:git/exit` alongside the generic `:exit`)."
  ([result] (check! result {}))
  ([{:keys [command exit out err timed-out?] :as result}
    {:keys [label data-prefix] :or {label "Command"}}]
   (if (:success? result)
     result
     (let [detail (str/trim (if (str/blank? (str err)) (str out) (str err)))
           prefix (some-> data-prefix name)]
       (throw (ex-info (str label " command failed"
                            (when (some? exit) (str " (exit " exit ")"))
                            (when timed-out? " (timed out)")
                            ": " (str/join " " command)
                            (when (seq detail) (str "\n" detail)))
                       (cond-> {:command    command
                                :exit       exit
                                :out        out
                                :err        err
                                :timed-out? timed-out?}
                         prefix
                         (assoc (keyword prefix "command")    command
                                (keyword prefix "exit")       exit
                                (keyword prefix "out")        out
                                (keyword prefix "err")        err
                                (keyword prefix "timed-out?") timed-out?))))))))

(defn run!
  "Like [[run]], but throw when the command exits non-zero. Returns the result
  map. Accepts the same `:label` and `:data-prefix` options as [[check!]]."
  ([command args] (run! command args {}))
  ([command args opts] (check! (run command args opts) opts)))

(defn echo-result!
  "Print a result map's stdout and stderr, without altering the map. `label`
  is used for the timeout notice."
  [label {:keys [out err timed-out?] :as result}]
  (when (pos? (count (str out)))
    (print (str out))
    (flush))
  (when (pos? (count (str err)))
    (binding [*out* *err*]
      (print (str err))
      (flush)))
  (when timed-out?
    (binding [*out* *err*]
      (println (str "[" label " timed out]"))
      (flush)))
  result)

(defn split-args
  "Separate an optional options map from variadic command arguments.

  The map may be first or last, and a single sequential argument is treated as
  the full argument list, so `(f {:dir d} \"status\")`, `(f \"status\"
  {:dir d})`, and `(f [\"status\"])` all work."
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

(defn out
  "Run `command` and return its trimmed stdout, throwing when it fails."
  ([command args] (out command args {}))
  ([command args opts] (str/trim (:out (run! command args opts)))))
