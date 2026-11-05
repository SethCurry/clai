(ns clai.agent.tools.builtin.shell
  "Built-in shell tool: `run_command`."
  (:require [clai.agent.tools.builtin.common :as common]
            [clai.util :as util]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Streaming output
;; ---------------------------------------------------------------------------

(def ^:private read-chunk-bytes
  "Size of the buffer read from the process at a time. Output is handed to the
  context's `:on-output` callback once per read, so this bounds how much text
  can accumulate between updates."
  4096)

(defn- decode-output
  "Decode the bytes captured so far as UTF-8 text."
  [^ByteArrayOutputStream buf]
  (String. (.toByteArray buf) "UTF-8"))

(defn- emit-output!
  "Hand the output captured so far to `ctx`'s `:on-output` callback, when one
  is present.

  The callback receives *everything* captured so far rather than just the
  newest chunk, so a consumer can simply replace whatever it rendered last
  time instead of having to reassemble the stream.

  A failing consumer must not take the command down with it, so errors are
  logged and swallowed."
  [ctx text]
  (when-let [on-output (:on-output ctx)]
    (try
      (on-output text)
      (catch Throwable e
        (tel/log! {:level :warn
                   :id    ::output-stream-failed
                   :data  {:chars (count text)}}
                  (str "Could not stream command output: "
                       (util/error-message e)))))))

(defn- pump-output
  "Copy `stream` into `buf` until EOF, capped at `common/max-output-bytes`,
  reporting progress to `ctx` after each read.

  Reads past the cap are still drained, so a chatty command can never block
  forever on a full pipe; the excess is simply not captured, which can
  leave the captured text cut mid-character."
  [^InputStream stream ^ByteArrayOutputStream buf ctx]
  (let [chunk (byte-array read-chunk-bytes)]
    (loop []
      (let [n (.read stream chunk)]
        (when (pos? n)
          (let [room (- common/max-output-bytes (.size buf))
                keep (min n (max 0 room))]
            (when (pos? keep)
              (.write buf chunk 0 keep)
              (emit-output! ctx (decode-output buf))))
          (recur))))))

(defn- start-output-pump
  "Start a daemon thread draining `proc`'s (merged) stdout/stderr into `buf`
  and reporting progress to `ctx`. Returns the thread.

  Draining happens off the calling thread so that the command can be awaited
  with a timeout: a blocking read on the pipe would otherwise hang the tool
  for as long as the process keeps the pipe open."
  [^Process proc ^ByteArrayOutputStream buf ctx]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     (with-open [in (.getInputStream proc)]
                       (pump-output in buf ctx))
                     (catch Throwable e
                       (tel/log! {:level :debug
                                  :id    ::output-pump-stopped
                                  :data  {:chars (.size buf)}}
                                 (str "Stopped reading command output: "
                                      (util/error-message e))))))
                 "clai-run-command-output")
    (.setDaemon true)
    (.start)))

;; ---------------------------------------------------------------------------
;; Shell tool
;; ---------------------------------------------------------------------------

(defn run-command-fn
  "Tool: run a shell command in the session `cwd` and return combined
  stdout/stderr. Capped at ~30 seconds and 64 KiB of output.

  Output is streamed to the context's `:on-output` callback as the command
  produces it (see [[emit-output!]]), so a caller can show progress instead of
  waiting for the whole result to land at the end. The returned string is
  always the complete final output, whatever was or wasn't streamed."
  [{:keys [command]} ctx]
  (let [builder (doto (ProcessBuilder. ^"[Ljava.lang.String;"
                                       (into-array String ["sh" "-c" (str command)]))
                  (.directory (-> (or (:cwd ctx) ".")
                                  (io/file)
                                  .getAbsoluteFile))
                  (.redirectErrorStream true))]
    (tel/log! {:level :debug
               :id    ::run-command
               :data  {:command (str command)
                       :cwd     (:cwd ctx)}}
              "Running shell command")
    (let [proc        (.start builder)
          buf         (ByteArrayOutputStream.)
          ^Thread reader (start-output-pump proc buf ctx)]
      (try
        (let [exited (try
                       (.waitFor proc 30 TimeUnit/SECONDS)
                       (catch InterruptedException _ false))
              ;; Kill a timed-out command before draining, so the pump sees EOF
              ;; and the partial output is captured below.
              _      (when-not exited (.destroyForcibly proc))
              _      (try
                       (.join reader 1000)
                       (catch InterruptedException _ nil))
              body   (decode-output buf)
              code   (when exited (.exitValue proc))]
          (if exited
            (tel/log! {:level (if (zero? code) :debug :warn)
                       :id    ::run-command-complete
                       :data  {:exit-code code :chars (count body)}}
                      "Shell command completed")
            (tel/log! {:level :warn
                       :id    ::run-command-timeout
                       :data  {:command (str command)
                               :chars   (count body)}}
                      "Shell command timed out after 30s"))
          (cond-> body
            (not exited) (str "\n[timed out after 30s]")
            (and exited (not (zero? code))) (str "\n[exit code " code "]")))
        (finally
          (.destroyForcibly proc)
          (try
            (.join reader 1000)
            (catch InterruptedException _ nil)))))))

;; ---------------------------------------------------------------------------
;; Tool definition
;; ---------------------------------------------------------------------------

(def ^:private run-command-spec
  {:type "function"
   :function
   {:name "run_command"
    :description
    "Run a shell command (via `sh -c`) in the session working directory and
    return combined stdout/stderr. Runs for up to 30 seconds."
    :parameters
    {:type "object"
     :properties
     {"command" {:type "string"
                 :description "The shell command to execute."}}
     :required ["command"]}}})

(defn tools
  "Return the built-in shell tool maps."
  []
  [{:id "run_command" :handler run-command-fn :spec run-command-spec}])
