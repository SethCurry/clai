(ns clai.lsp
  "A small client for `clojure-lsp`, speaking LSP over stdio.

  `clojure-lsp` analyzes a Clojure project once and then answers navigation
  questions (where is this defined, who calls it, what does its docstring say)
  in milliseconds. Starting it costs a few seconds, so this namespace keeps one
  long-lived server per project root in a process-wide registry and reuses it
  for every query. Servers are started lazily by [[server-for]] and stopped by
  [[stop!]] or by a JVM shutdown hook.

  The client implements only what the agent's navigation tools need:

    * framing and request/response plumbing over stdio;
    * `textDocument/definition`, `references`, `hover`, `documentSymbol`,
      `workspace/symbol`;
    * `textDocument/publishDiagnostics` notifications, cached per file, plus
      helpers to wait for fresh ones after a file changes.

  Editing operations (`rename`, `format`, code actions) are deliberately not
  implemented: they change files, and the agent can do that with `write_file`.

  ## Example

      (require '[clai.lsp :as lsp])

      (def server (lsp/server-for \"/path/to/project\" nil))
      (lsp/hover server (lsp/path->uri \"/path/to/project/src/x.clj\")
                 {:line 72 :character 6})
      (lsp/workspace-symbols server \"error-message\")
      (lsp/stop-all!)"
  (:require [cheshire.core :as json]
            [clai.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayOutputStream File InputStream OutputStream]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file Paths]
           [java.util.concurrent TimeUnit]
           [java.lang ProcessHandle]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  util/non-empty-string)

(def Position
  "An LSP position: `:line` and `:character`, both zero-based. `:character`
  counts UTF-16 code units, exactly like the protocol requires."
  [:map
   [:line :int]
   [:character :int]])

(def Range
  "An LSP range: a `:start` and an `:end` [[Position]]."
  [:map
   [:start Position]
   [:end Position]])

(def Location
  "An LSP location: the `:uri` of a file and the [[Range]] inside it."
  [:map
   [:uri :string]
   [:range Range]])

(def PathLike
  "A filesystem path: a string or a `java.io.File`."
  [:or :string [:fn #(instance? File %)]])

(def Atom
  "A `clojure.lang.IAtom`, used to describe the server's mutable state."
  [:fn #(instance? clojure.lang.IAtom %)])

(def Server
  "A running `clojure-lsp` process and the client state around it, as returned
  by [[server-for]]."
  [:map
   [:root non-empty-string]
   [:executable :string]
   [:process [:fn #(instance? Process %)]]
   [:in [:fn #(instance? InputStream %)]]
   [:out [:fn #(instance? OutputStream %)]]
   [:ids Atom]
   [:pending Atom]
   [:diagnostics Atom]
   [:publishes Atom]
   [:files Atom]
   [:stderr Atom]
   [:closed? Atom]
   [:server-info {:optional true} [:maybe [:map]]]
   [:capabilities {:optional true} [:maybe [:map]]]])

(def default-executable
  "The executable name looked up on `PATH` when no other path is configured."
  "clojure-lsp")

(def initialize-timeout-ms
  "How long to wait for `initialize` to return. The first start of a large
  project analyzes every source file, which can take minutes."
  300000)

(def request-timeout-ms
  "How long to wait for an ordinary request once the server is running."
  60000)

(def shutdown-timeout-ms
  "How long to wait for the server to exit gracefully before killing it."
  3000)

(def timeout
  "Returned by [[await-diagnostics]] when the server did not publish in time."
  ::timeout)

(def project-markers
  "Files and directories that mark the root of a Clojure project."
  ["project.clj" "deps.edn" "pom.xml" "build.clj" "bb.edn" "shadow-cljs.edn" ".git"])

;; ---------------------------------------------------------------------------
;; Paths and URIs
;; ---------------------------------------------------------------------------

(defn- absolute-path
  "Coerce `x` (a string or `File`) to an absolute `java.nio.file.Path`."
  {:malli/schema [:-> #'PathLike [:fn #(instance? java.nio.file.Path %)]]}
  [x]
  (.toAbsolutePath (.toPath (io/file x))))

(defn project-root
  "Return the absolute path of the directory of the project containing `dir`.

  The root is the closest ancestor of `dir` that holds one of
  [[project-markers]]. `dir` itself is returned (absolute) when nothing marks a
  project, so an unversioned directory still works."
  {:malli/schema [:-> #'PathLike :string]}
  [dir]
  (let [start (absolute-path dir)]
    (loop [^java.nio.file.Path current start]
      (if (some #(.exists (.toFile (.resolve current ^String (str %)))) project-markers)
        (str current)
        (if-let [parent (.getParent current)]
          (recur parent)
          (str start))))))

(defn path->uri
  "Return the `file:` URI LSP uses for `path` (a string or `File`)."
  {:malli/schema [:-> #'PathLike :string]}
  [path]
  (str (.toUri (absolute-path path))))

(defn uri->path
  "Return the filesystem path named by the `file:` `uri`."
  {:malli/schema [:-> non-empty-string :string]}
  [uri]
  (str (Paths/get (URI. uri))))

;; ---------------------------------------------------------------------------
;; Framing
;; ---------------------------------------------------------------------------

(defn- read-header-line
  "Read one CRLF-terminated header line from `in` as an ASCII string. Returns
  nil at end of stream."
  [^InputStream in]
  (let [buffer (ByteArrayOutputStream.)]
    (loop []
      (let [byte-read (.read in)]
        (cond
          (neg? byte-read)
          (when (pos? (.size buffer))
            (String. (.toByteArray buffer) StandardCharsets/US_ASCII))

          (= byte-read 10)
          (let [line (String. (.toByteArray buffer) StandardCharsets/US_ASCII)]
            (if (str/ends-with? line "\r") (subs line 0 (dec (count line))) line))

          :else
          (do (.write buffer (int byte-read))
              (recur)))))))

(defn- read-headers
  "Read the headers of one LSP message from `in` as a map of lower-case header
  name to value. Returns nil at end of stream."
  [^InputStream in]
  (loop [headers {}]
    (let [line (read-header-line in)]
      (cond
        (nil? line)   nil
        (empty? line) (when (seq headers) headers)
        :else         (let [[k v] (str/split line #":" 2)]
                        (recur (assoc headers
                                      (str/lower-case (str/trim k))
                                      (str/trim (str v)))))))))

(defn- read-message
  "Read one JSON-RPC message from `in`.

  Returns the parsed message (keywordized keys), nil at end of stream, or
  `::malformed` when the framing or the JSON is broken."
  [^InputStream in]
  (if-let [headers (read-headers in)]
    (let [length (some-> (get headers "content-length") Long/parseLong)]
      (if (nil? length)
        ::malformed
        (let [data (util/read-at-most in (int length))]
          (if (= (alength ^bytes data) length)
            (try
              (json/parse-string (String. ^bytes data StandardCharsets/UTF_8) true)
              (catch Exception _
                (tel/log! {:level :debug :id ::unparseable-message
                           :data {:length length}}
                          "clojure-lsp sent a message that is not JSON")
                ::malformed))
            ::malformed))))
    nil))

(defn- write-message!
  "Write `message` to `out` using LSP's `Content-Length` framing.

  Writes are serialized on `out`, so several threads may share one server."
  [^OutputStream out message]
  (let [bytes  (.getBytes (json/generate-string message) StandardCharsets/UTF_8)
        header (.getBytes (str "Content-Length: " (alength bytes) "\r\n\r\n")
                           StandardCharsets/US_ASCII)]
    (locking out
      (.write out header)
      (.write out bytes)
      (.flush out))))

;; ---------------------------------------------------------------------------
;; Server lifecycle
;; ---------------------------------------------------------------------------

(defn- server->marker
  "Return a short label for `server`, for log messages."
  {:malli/schema [:-> Server :string]}
  [server]
  (str "clojure-lsp[" (:root server) "]"))

(defn- mark-closed!
  "Record that `server` stopped talking, failing every pending request so
  callers do not wait for a reply that cannot come."
  {:malli/schema [:-> Server :string :map]}
  [server reason]
  (reset! (:closed? server) true)
  (doseq [[_ pending] @(:pending server)]
    (deliver pending ::closed))
  (reset! (:pending server) {})
  (tel/log! {:level :debug :id ::server-closed
             :data {:server (server->marker server) :reason reason}}
            "clojure-lsp server closed")
  server)

(defn- handle-server-request!
  "Answer a request `clojure-lsp` sent us (the client).

  The client advertises no interesting capabilities, so every request is
  answered with the harmless default the protocol expects: nothing for
  registrations and progress, `null` configuration values, and an
  `applyEdit` result that reports the edit was not applied."
  {:malli/schema [:-> Server :map :any]}
  [server message]
  (let [method (:method message)
        params (:params message)
        result (cond
                 (= method "workspace/configuration")
                 (vec (repeat (count (:items params)) nil))

                 (= method "workspace/workspaceFolders")
                 [{:uri (path->uri (:root server)) :name (last (str/split (:root server) #"/"))}]

                 (= method "workspace/applyEdit")
                 {:applied false}

                 :else nil)]
    (tel/log! {:level :debug :id ::server-request
               :data {:server (server->marker server) :method method}}
              "Answering a clojure-lsp request")
    (write-message! (:out server) {:jsonrpc "2.0" :id (:id message) :result result})))

(defn- handle-notification!
  "Update client state from a notification `clojure-lsp` sent us.

  Diagnostics are the only notification the client keeps: they are cached per
  file URI so a diagnostics tool can read them without asking the server. Every
  publish also bumps a counter, which is how [[await-analysis]] notices that
  analysis produced new results."
  {:malli/schema [:-> Server :map :any]}
  [server message]
  (cond
    (= "textDocument/publishDiagnostics" (:method message))
    (let [{:keys [uri diagnostics]} (:params message)]
      (swap! (:diagnostics server) assoc uri (vec diagnostics))
      (swap! (:publishes server) inc))

    (= "window/logMessage" (:method message))
    (tel/log! {:level :debug :id ::server-log
               :data {:server (server->marker server)
                      :message (get-in message [:params :message])}}
              "clojure-lsp log message")

    :else nil))

(defn- dispatch!
  "Route one message read from `clojure-lsp` to its handler."
  {:malli/schema [:-> Server :any :any]}
  [server message]
  (cond
    (nil? message)
    (mark-closed! server "end of stream")

    (= ::malformed message)
    (mark-closed! server "malformed message")

    (and (contains? message :method) (contains? message :id))
    (handle-server-request! server message)

    (contains? message :id)
    (when-let [pending (get @(:pending server) (:id message))]
      (swap! (:pending server) dissoc (:id message))
      (deliver pending message))

    (contains? message :method)
    (handle-notification! server message)

    :else nil))

(defn- start-reader-thread!
  "Start the daemon thread that reads messages from `server` forever.

  Keeping the reading on its own thread is what lets requests block on a
  promise with a timeout instead of blocking on the process's stdout."
  {:malli/schema [:-> Server [:fn #(instance? Thread %)]]}
  [server]
  (doto (Thread. (fn []
                   (try
                     (loop []
                       (let [message (read-message (:in server))]
                         (dispatch! server message)
                         (when-not (nil? message) (recur))))
                     (catch Throwable e
                       (tel/error! {:id   ::reader-failed
                                    :data {:server (server->marker server)}}
                                   e)
                       (mark-closed! server (util/error-message e)))))
                 (str "clai-lsp-reader-" (:root server)))
    (.setDaemon true)
    (.start)))

(defn- start-stderr-pump!
  "Start the daemon thread that drains `server`'s stderr.

  The last few lines are kept in the server map so a startup failure can be
  reported with the server's own explanation. The pipe must be drained whether
  or not anyone reads those lines, or a chatty server would block on it."
  {:malli/schema [:-> Server [:fn #(instance? Thread %)]]}
  [server]
  (doto (Thread. (fn []
                   (try
                     (with-open [reader (io/reader (.getErrorStream ^Process (:process server)))]
                       (doseq [line (line-seq reader)]
                         (swap! (:stderr server)
                                (fn [lines]
                                  (-> (conj (vec lines) line)
                                      (subvec (max 0 (- (count lines) 49))))))))
                     (catch Throwable _ nil)))
                 (str "clai-lsp-stderr-" (:root server)))
    (.setDaemon true)
    (.start)))

(defn- configured-executable
  "Return the `clojure-lsp` command as configured, before it is resolved.

  `opts` is optional client configuration; its `:executable` wins, then the
  `CLOJURE_LSP_BIN` environment variable, then `clojure-lsp` on `PATH`."
  {:malli/schema [:-> [:maybe [:map]] :string]}
  [opts]
  (or (not-empty (str (:executable opts)))
      (not-empty (str (System/getenv "CLOJURE_LSP_BIN")))
      default-executable))

(defn- executable-file?
  "True when `file` can be run -- or is a jar, which is run with `java -jar`."
  {:malli/schema [:-> [:fn #(instance? File %)] :boolean]}
  [file]
  (and (.isFile file)
       (or (.canExecute file) (str/ends-with? (.getName file) ".jar"))))

(defn- search-directories
  "Return the directories an executable named on the command line is looked
  for in: the entries of `PATH` plus the usual install locations."
  {:malli/schema [:-> [:vector :string]]}
  []
  (->> (concat (str/split (or (System/getenv "PATH") "")
                          (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
               [(str (io/file (System/getProperty "user.home") ".local" "bin"))
                "/usr/local/bin"
                "/usr/bin"
                "/opt/homebrew/bin"])
       (remove str/blank?)
       vec))

(defn resolve-executable
  "Return the absolute path of the `clojure-lsp` executable `exe` names.

  `exe` may be an absolute path, a path relative to the working directory, or a
  bare command name, which is looked up on `PATH` (and in the usual install
  locations). A `.jar` is accepted and run with `java -jar`.

  Throws an `ex-info` with installation instructions when nothing is found, so
  a user without clojure-lsp gets told what to do instead of a stack trace from
  `ProcessBuilder`."
  {:malli/schema [:-> non-empty-string :string]}
  [exe]
  (let [as-file (io/file exe)]
    (cond
      (or (.isAbsolute as-file) (str/includes? exe (str File/separatorChar)))
      (if (executable-file? (.getAbsoluteFile as-file))
        (.getAbsolutePath as-file)
        (throw (ex-info (str "clojure-lsp not found at " (.getAbsolutePath as-file))
                        {:executable exe})))

      :else
      (if-let [found (some (fn [dir]
                             (let [file (io/file dir exe)]
                               (when (executable-file? file)
                                 (.getAbsolutePath file))))
                           (search-directories))]
        found
        (throw (ex-info (str "Could not find '" exe "'. Install clojure-lsp"
                             " (https://clojure-lsp.io/installation/) or set"
                             " CLOJURE_LSP_BIN to its path.")
                        {:executable exe}))))))

(defn- command-line
  "Return the argv used to start a server with `executable`.

  `listen` is the default `clojure-lsp` command; naming it keeps the intent
  clear. The project root is passed as the process's working directory."
  {:malli/schema [:-> non-empty-string [:vector :string]]}
  [executable]
  (vec (concat (if (str/ends-with? executable ".jar")
                 ["java" "-jar" executable]
                 [executable])
               ["listen"])))

(defn request
  "Send a JSON-RPC `method` call to `server` and return its `:result`.

  Blocks until the server answers, up to `timeout-ms`. Throws an `ex-info`
  naming the method when the server reports an error, closes the connection, or
  does not answer in time."
  ([server method params] (request server method params request-timeout-ms))
  ([server method params timeout-ms]
   (when @(:closed? server)
     (throw (ex-info (str (server->marker server) " is not running")
                     {:method method :root (:root server)})))
   (let [id       (swap! (:ids server) inc)
         response (promise)]
     (swap! (:pending server) assoc id response)
     (write-message! (:out server)
                     (cond-> {:jsonrpc "2.0" :id id :method method}
                       (some? params) (assoc :params params)))
     (let [message (deref response timeout-ms ::timeout)]
       (swap! (:pending server) dissoc id)
       (cond
         (= message ::timeout)
         (throw (ex-info (str (server->marker server) " did not answer '" method
                              "' within " timeout-ms " ms")
                         {:method method :root (:root server)}))

         (= message ::closed)
         (throw (ex-info (str (server->marker server) " closed the connection while"
                              " answering '" method "'")
                         {:method method :root (:root server)
                          :stderr (vec @(:stderr server))}))

         (contains? message :error)
         (throw (ex-info (str (server->marker server) " failed '" method "': "
                              (or (get-in message [:error :message]) "unknown error"))
                         {:method method :root (:root server)
                          :error (:error message)}))

         :else (:result message))))))

(defn notify
  "Send a JSON-RPC notification (a call without a response) to `server`."
  {:malli/schema [:-> Server non-empty-string [:maybe [:map]] :any]}
  [server method params]
  (write-message! (:out server)
                  (cond-> {:jsonrpc "2.0" :method method}
                    (some? params) (assoc :params params))))

(defn- initialize!
  "Complete the LSP handshake with `server` and return it with the server's
  `:server-info` and `:capabilities` recorded."
  {:malli/schema [:-> Server Server]}
  [server]
  (let [root     (:root server)
        root-uri (path->uri root)
        result   (request server "initialize"
                          {:processId (-> (ProcessHandle/current) .pid)
                           :clientInfo {:name "clai"}
                           :rootUri root-uri
                           :workspaceFolders [{:uri root-uri
                                               :name (last (str/split root #"/"))}]
                           :capabilities
                           {:window {:workDoneProgress true}
                            :workspace {:workspaceFolders true
                                        :configuration true
                                        :symbol {}
                                        :applyEdit true}
                            :textDocument
                            {:synchronization {:didSave true}
                             :hover {:contentFormat ["markdown" "plaintext"]}
                             :definition {}
                             :references {}
                             :documentSymbol {:hierarchicalDocumentSymbolSupport true}
                             :publishDiagnostics {:relatedInformation true}}}
                           :initializationOptions {}}
                          initialize-timeout-ms)]
    (notify server "initialized" nil)
    (assoc server
           :server-info (:serverInfo result)
           :capabilities (:capabilities result))))

(defn- wait-for-exit
  "Wait up to `timeout-ms` for `server`'s process to exit; kill it otherwise."
  {:malli/schema [:-> Server pos-int? :any]}
  [server timeout-ms]
  (let [^Process process (:process server)]
    (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
    (when (.isAlive process)
      (.destroyForcibly process)
      (.waitFor process shutdown-timeout-ms TimeUnit/MILLISECONDS))))

(defn start-server!
  "Start a `clojure-lsp` server for `root` and run the LSP handshake.

  `opts` is optional configuration (see [[configured-executable]]). Throws an
  `ex-info` with an actionable message when the executable is missing or the
  server fails to start."
  {:malli/schema [:-> #'PathLike [:maybe [:map]] Server]}
  [root opts]
  (let [root  (project-root root)
        exe   (resolve-executable (configured-executable opts))
        argv  (command-line exe)
        process (try
                  (-> (ProcessBuilder. ^java.util.List argv)
                      (.directory (io/file root))
                      (.start))
                  (catch Exception e
                    (throw (ex-info (str "Could not start " exe ": "
                                         (util/error-message e))
                                    {:root root :executable exe :cause e}))))]
    (tel/log! {:level :debug :id ::starting-server
               :data {:root root :executable exe}}
              "Starting clojure-lsp")
    (let [server {:root         root
                  :executable   exe
                  :process      process
                  :in           (.getInputStream process)
                  :out          (.getOutputStream process)
                  :ids          (atom 0)
                  :pending      (atom {})
                  :diagnostics  (atom {})
                  :publishes    (atom 0)
                  :files        (atom {})
                  :stderr       (atom [])
                  :closed?      (atom false)}]
      (start-reader-thread! server)
      (start-stderr-pump! server)
      (try
        (initialize! server)
        (catch Throwable e
          (mark-closed! server "initialization failed")
          (wait-for-exit server shutdown-timeout-ms)
          (throw (ex-info (str "clojure-lsp failed to initialize for " root ": "
                               (util/error-message e))
                          {:root root :executable exe :cause e
                           :stderr (vec @(:stderr server))})))))))

(defn- alive?
  "True when `server` is still running and talking."
  {:malli/schema [:-> Server :boolean]}
  [server]
  (and (not @(:closed? server))
       (.isAlive ^Process (:process server))))

(defn stop!
  "Stop `server` gracefully, killing its process if it does not exit."
  {:malli/schema [:-> Server :any]}
  [server]
  (when (alive? server)
    (try
      (request server "shutdown" nil shutdown-timeout-ms)
      (notify server "exit" nil)
      (catch Throwable _ nil)))
  (mark-closed! server "stopped")
  (when (.isAlive ^Process (:process server))
    (wait-for-exit server shutdown-timeout-ms))
  nil)

(defonce ^:private servers
  ;; Servers started by this JVM, keyed by project root. `defonce` because a
  ;; namespace reload must not orphan a running server process.
  (atom {}))

(defn server-for
  "Return a running [[Server]] for `root`, starting one if needed.

  Starting is guarded by a lock so two tools (or two sub-agents) asking for the
  same project at once share one server. A server that died is replaced.
  `opts` is optional configuration, see [[configured-executable]]."
  {:malli/schema [:-> #'PathLike [:maybe [:map]] Server]}
  [root opts]
  (let [root (project-root root)]
    (locking servers
      (let [existing (get @servers root)]
        (if (and existing (alive? existing))
          existing
          (let [server (start-server! root opts)]
            (when existing
              (tel/log! {:level :debug :id ::replacing-server
                         :data {:root root}}
                        "Replacing a clojure-lsp server that stopped"))
            (swap! servers assoc root server)
            server))))))

(defn stop-all!
  "Stop every server this JVM started, forgetting them all."
  {:malli/schema [:-> :any]}
  []
  (let [current (vals @servers)]
    (reset! servers {})
    (doseq [server current]
      (try
        (stop! server)
        (catch Throwable e
          (tel/log! {:level :debug :id ::stop-failed
                     :data {:server (server->marker server)}}
                    (str "Failed to stop clojure-lsp: " (util/error-message e)))))))
  nil)

(defonce ^:private shutdown-hook
  ;; Kills the servers when the JVM exits, so a user who quits clai is not left
  ;; with orphaned clojure-lsp processes.
  (let [hook (doto (Thread. (fn [] (try (stop-all!) (catch Throwable _ nil))))
               (.setName "clai-lsp-shutdown"))]
    (doto (Runtime/getRuntime)
      (.addShutdownHook hook))
    hook))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn- locations
  "Normalize an LSP location result (nil, one location, or a vector of them)
  to a vector of locations."
  {:malli/schema [:-> [:maybe [:or :map [:sequential :map]]] [:vector Location]]}
  [result]
  (cond
    (nil? result)  []
    (map? result)  [result]
    (vector? result) result
    :else          (vec result)))

(defn definition
  "Return the definitions of whatever is at `position` in `uri`, as a vector of
  [[Location]]s."
  {:malli/schema [:-> Server non-empty-string Position [:vector Location]]}
  [server uri position]
  (locations (request server "textDocument/definition"
                      {:textDocument {:uri uri} :position position})))

(defn references
  "Return the usages of whatever is at `position` in `uri`, as a vector of
  [[Location]]s. The declaration itself is not included."
  {:malli/schema [:-> Server non-empty-string Position [:vector Location]]}
  [server uri position]
  (locations (request server "textDocument/references"
                      {:textDocument {:uri uri}
                       :position position
                       :context {:includeDeclaration false}})))

(defn hover
  "Return the hover information for `position` in `uri`: signature, docstring
  and source link for the var under the cursor. Nil when there is none."
  {:malli/schema [:-> Server non-empty-string Position [:maybe :map]]}
  [server uri position]
  (request server "textDocument/hover"
           {:textDocument {:uri uri} :position position}))

(defn document-symbols
  "Return the outline of `uri`: the namespaces and vars it defines, as a vector
  of LSP `DocumentSymbol` maps (possibly nested under `:children`)."
  {:malli/schema [:-> Server non-empty-string [:vector :map]]}
  [server uri]
  (vec (request server "textDocument/documentSymbol" {:textDocument {:uri uri}})))

(defn workspace-symbols
  "Return the project symbols whose name matches `query`, as a vector of LSP
  `SymbolInformation` maps (`:name`, `:kind`, `:location`)."
  {:malli/schema [:-> Server :string [:vector :map]]}
  [server query]
  (vec (request server "workspace/symbol" {:query query})))

;; ---------------------------------------------------------------------------
;; Files and diagnostics
;; ---------------------------------------------------------------------------

(defn- send-file!
  "Send `text` for `uri` to `server`, opening the file if this is the first
  time.

  A full-content `didChange` is sent whenever the text differs from what the
  server last saw, and always when `force?` is true -- clojure-lsp publishes
  fresh diagnostics for every `didChange`, which is how [[refresh-file!]] gets
  an up-to-date answer."
  {:malli/schema [:-> Server non-empty-string :string :boolean :any]}
  [server uri text force?]
  (let [seen    (get @(:files server) uri)
        version (inc (or (:version seen) 0))]
    (when (nil? seen)
      (notify server "textDocument/didOpen"
              {:textDocument {:uri uri :languageId "clojure"
                              :version version :text text}}))
    (when (or force? (not= (:text seen) text))
      (notify server "textDocument/didChange"
              {:textDocument {:uri uri :version version}
               :contentChanges [{:text text}]}))
    (swap! (:files server) assoc uri {:version version :text text})
    uri))

(defn sync-file!
  "Make `server`'s analysis see the current contents of `path`.

  The first call opens the file (as an editor would); later calls send a
  `didChange` only when the text differs. Returns the file's URI."
  {:malli/schema [:-> Server #'PathLike :string]}
  [server path]
  (let [path (str (absolute-path path))]
    (send-file! server (path->uri path) (slurp path) false)))

(defn refresh-file!
  "Forget the cached diagnostics of `path` and make `server` re-analyze it.

  Returns the file's URI, ready for [[await-diagnostics]]."
  {:malli/schema [:-> Server #'PathLike :string]}
  [server path]
  (let [path (str (absolute-path path))
        uri  (path->uri path)]
    (swap! (:diagnostics server) dissoc uri)
    (send-file! server uri (slurp path) true)))

(defn refresh-project!
  "Tell `server` about the files it has already analyzed whose contents changed
  on disk since it last saw them, so a project-wide query is up to date.

  Only files clojure-lsp has reported on are considered, and only changed ones
  are sent, so this stays a handful of small reads per call. Returns the number
  of files that changed."
  {:malli/schema [:-> Server :int]}
  [server]
  (let [changed (->> (keys @(:diagnostics server))
                     (map uri->path)
                     distinct
                     (filter (fn [path]
                               (and (.isFile (io/file path))
                                    (not= (:text (get @(:files server) (path->uri path)))
                                          (slurp path))))))]
    (doseq [path changed]
      (sync-file! server path))
    (count changed)))

(defn await-diagnostics
  "Wait up to `timeout-ms` for fresh diagnostics for `uri`.

  Returns the vector of diagnostics (possibly empty) or `::timeout` when the
  server did not publish any in time."
  {:malli/schema [:-> Server non-empty-string pos-int?
                  [:or [:sequential :map] [:= ::timeout]]]}
  [server uri timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if-some [diagnostics (get @(:diagnostics server) uri)]
        diagnostics
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 20) (recur))
          ::timeout)))))

(defn await-analysis
  "Wait until `server` has published diagnostics and then gone quiet for
  `quiet-ms`, or until `timeout-ms` passes; return the diagnostics published so
  far.

  clojure-lsp publishes diagnostics for the whole project once analysis is
  done, so a quiet window is a reliable (if indirect) signal that the first
  analysis finished. Returns a map of file URI to diagnostics."
  {:malli/schema [:-> Server [:map
                              [:quiet-ms {:optional true} pos-int?]
                              [:timeout-ms {:optional true} pos-int?]]
                  [:map-of :string [:sequential :map]]]}
  [server {:keys [quiet-ms timeout-ms] :or {quiet-ms 300 timeout-ms 120000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [published @(:publishes server)]
        (Thread/sleep quiet-ms)
        (if (or (> (System/currentTimeMillis) deadline)
                (= published @(:publishes server)))
          @(:diagnostics server)
          (recur))))))

(defn diagnostics
  "Return the diagnostics `server` has published, as a map of file URI to a
  vector of LSP `Diagnostic` maps."
  {:malli/schema [:-> Server [:map-of :string [:sequential :map]]]}
  [server]
  @(:diagnostics server))

(defn status
  "Return a small map describing `server`: project root, executable, whether it
  is alive, and the version clojure-lsp reported."
  {:malli/schema [:-> Server [:map
                              [:root :string]
                              [:executable :string]
                              [:alive? :boolean]
                              [:version {:optional true} [:maybe :string]]
                              [:files-with-diagnostics {:optional true} :int]]]}
  [server]
  (cond-> {:root (:root server)
           :executable (:executable server)
           :alive? (alive? server)
           :files-with-diagnostics (count @(:diagnostics server))}
    (get-in server [:server-info :version])
    (assoc :version (get-in server [:server-info :version]))))
