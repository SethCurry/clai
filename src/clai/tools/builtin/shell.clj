(ns clai.tools.builtin.shell
  "Built-in shell tool: `run_command`."
  (:require [clai.tools.builtin.common :as common]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Shell tool
;; ---------------------------------------------------------------------------

(defn run-command-fn
  "Tool: run a shell command in the session `cwd` and return combined
  stdout/stderr. Capped at ~30 seconds and 64 KiB of output."
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
    (let [proc (.start builder)]
      (try
        (let [^String joined (with-open [r (io/reader (.getInputStream proc))]
                               (str/join "\n" (line-seq r)))
              raw    (.getBytes joined "UTF-8")
              out    (java.io.ByteArrayInputStream. raw)
              ^bytes buf (byte-array common/max-output-bytes)
              n      (.read out buf 0 common/max-output-bytes)
              body   (String. buf 0 (max 0 n) "UTF-8")
              exited (try
                       (.waitFor proc 30 TimeUnit/SECONDS)
                       (catch InterruptedException _ false))
              code   (when exited (.exitValue proc))]
          (if exited
            (tel/log! {:level (if (zero? code) :debug :warn)
                       :id    ::run-command-complete
                       :data  {:exit-code code :chars (count body)}}
                      "Shell command completed")
            (tel/log! {:level :warn
                       :id    ::run-command-timeout
                       :data  {:command (str command)}}
                      "Shell command timed out after 30s"))
          (cond-> (str body)
            (not exited) (str "\n[timed out after 30s]")
            (and exited (not (zero? code))) (str "\n[exit code " code "]")))
        (finally
          (.destroyForcibly proc))))))

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
