(ns clai.shell.browser
  "REPL-friendly helpers for opening a URL in the user's web browser.

  [[browse]] starts the browser named by the `BROWSER` environment variable,
  falling back to `firefox` when `BROWSER` is unset or blank:

      (browse \"https://clojure.org\")

  `BROWSER` may name a program with arguments, and a `%s` placeholder in it is
  replaced with the URL; without a placeholder the URL is appended:

      BROWSER=\"firefox --new-tab\"    ; => firefox --new-tab https://...
      BROWSER=\"chromium --app=%s\"    ; => chromium --app=https://...

  Quotes and backslash escapes work in `BROWSER`, so a program whose path
  contains spaces can be named:

      BROWSER=\"/path/to/my browser\" --new-window

  The command is resolved in this order: the `:browser` option, [[*browser*]],
  the `BROWSER` environment variable (`:env` overrides included), then
  [[default-browser]]. The first two make it easy to override the browser for
  one call or one series of calls:

      (browse url {:browser \"chromium\"})
      (binding [browser/*browser* \"chromium\"] (browse url))

  The browser is launched detached by default: [[browse]] returns as soon as
  the process starts, so a browser that stays open does not hold up the REPL.
  Pass `:wait? true` to wait for it instead and get the usual
  [[clai.shell.cli/run]] result map, which is useful for command-line browsers
  or tests. [[browse!]] is [[browse]] plus a failure check.

  Options:

    :browser   command string used to open the URL
    :wait?     true to wait for the browser process (default false)
    :dir       working directory (defaults to the calling namespace's
               `*cwd*`, then `user.dir`)
    :timeout   milliseconds to wait in `:wait?` mode; nil waits forever
    :env       extra environment variables; a nil value removes one

  In `:wait?` mode the result map contains `:command`, `:dir`, `:exit`,
  `:out`, `:err`, `:success?`, and `:timed-out?`, plus `:url`. The detached
  result contains `:command`, `:dir`, `:url`, `:pid`, and `:detached?`."
  (:require [clai.shell.cli :as cli]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def default-browser
  "Browser used when the `BROWSER` environment variable is unset or blank."
  "firefox")

(def ^:dynamic *browser*
  "Optional browser command override. When non-blank it takes precedence over
  the `BROWSER` environment variable; the `:browser` option takes precedence
  over both. It may include arguments and a `%s` URL placeholder, just like
  `BROWSER`."
  nil)

(defn- non-blank
  "Coerce `x` to a string and return it, or nil when it is blank."
  [x]
  (when (some? x)
    (let [s (cli/arg->string x)]
      (when-not (str/blank? s) s))))

(defn- env-browser
  "The `BROWSER` value, after applying `opts`' `:env` overrides (a nil value
  removes the variable, matching [[clai.shell.cli/run]])."
  [opts]
  (let [value (reduce-kv (fn [current k v]
                           (if (= "BROWSER" (cli/arg->string k))
                             v
                             current))
                         (System/getenv "BROWSER")
                         (:env opts))]
    (non-blank value)))

(defn- browser-setting
  "Resolve the browser command string for `opts`, ignoring blank overrides."
  [opts]
  (or (non-blank (:browser opts))
      (non-blank *browser*)
      (env-browser opts)
      default-browser))

;; ---------------------------------------------------------------------------
;; Command construction
;; ---------------------------------------------------------------------------

(defn split-command
  "Split a browser command string into tokens on whitespace, honoring single
  quotes, double quotes, and backslash escapes.

  The browser command is a small command line, not a shell script: there are
  no variables, globs, pipes, or redirections. Throws an `ex-info` for an
  unterminated quote or a trailing backslash."
  [command]
  (let [s (str command)
        n (count s)]
    (loop [i         0
           quote     0
           escaped?  false
           token     (StringBuilder.)
           has-token? false
           tokens    []]
      (if (= i n)
        (cond
          (or (pos? quote) escaped?)
          (throw (ex-info (str "Unterminated quote or escape in browser command: " s)
                          {:command s}))

          has-token?
          (conj tokens (str token))

          :else
          tokens)
        (let [c (int (.charAt s i))]
          (cond
            escaped?
            (do (.append token (char c))
                (recur (inc i) quote false token true tokens))

            (= c 92)                    ; backslash
            (recur (inc i) quote true token has-token? tokens)

            (pos? quote)
            (if (= c quote)
              (recur (inc i) 0 false token true tokens)
              (do (.append token (char c))
                  (recur (inc i) quote false token true tokens)))

            (or (= c 39) (= c 34))      ; single or double quote
            (recur (inc i) c false token true tokens)

            (Character/isWhitespace (char c))
            (if has-token?
              (recur (inc i) 0 false (StringBuilder.) false
                     (conj tokens (str token)))
              (recur (inc i) 0 false token false tokens))

            :else
            (do (.append token (char c))
                (recur (inc i) 0 false token true tokens))))))))

(defn command
  "Return the argument vector [[browse]] would run for `url`.

  Resolves the browser with the same rules as [[browse]] and substitutes
  `url` for every `%s` in the browser command, appending it when the command
  has no placeholder."
  ([url] (command url {}))
  ([url opts]
   (let [url    (str url)
         tokens (split-command (browser-setting opts))]
     (when (empty? tokens)
       (throw (ex-info "The browser command is empty"
                       {:browser (browser-setting opts)})))
     (if (some #(str/includes? % "%s") tokens)
       (mapv #(str/replace % "%s" url) tokens)
       (conj (vec tokens) url)))))

;; ---------------------------------------------------------------------------
;; Launching
;; ---------------------------------------------------------------------------

(defn- apply-env!
  "Apply `opts`' `:env` map to a `ProcessBuilder`'s environment."
  [^ProcessBuilder builder opts]
  (let [env (.environment builder)]
    (doseq [[k v] (:env opts)]
      (if (nil? v)
        (.remove env (cli/arg->string k))
        (.put env (cli/arg->string k) (str v))))
    builder))

(defn- start-detached!
  "Start `command` without waiting for it, discarding its output and closing
  its stdin. Returns the started `Process`."
  [command opts]
  (let [^java.io.File dir (cli/resolve-dir opts)
        ^ProcessBuilder builder
        (-> (ProcessBuilder. ^java.util.List (vec command))
            (.directory dir)
            (.redirectOutput ProcessBuilder$Redirect/DISCARD)
            (.redirectError ProcessBuilder$Redirect/DISCARD)
            (apply-env! opts))
        ^Process proc
        (try
          (.start builder)
          (catch java.io.IOException e
            (throw (ex-info (str "Could not start browser "
                                 (first command) ": "
                                 (or (ex-message e) (str (class e))))
                            {:command         (vec command)
                             :dir             (.getAbsolutePath dir)
                             :browser/command (vec command)
                             :browser/dir     (.getAbsolutePath dir)}
                            e))))]
    ;; The browser has no use for clai's stdin, and holding the pipe open can
    ;; leave a terminal program waiting for input.
    (try
      (.close (.getOutputStream proc))
      (catch Throwable _ nil))
    proc))

(defn- launch
  "Start the browser for `url`, waiting when `opts` says so."
  [url opts]
  (let [argv (command url opts)
        ^java.io.File dir (cli/resolve-dir opts)]
    (if (:wait? opts)
      (-> (cli/run (first argv) (rest argv) opts)
          (assoc :url (str url)))
      (let [^Process proc (start-detached! argv opts)]
        {:command   argv
         :dir       (.getAbsolutePath dir)
         :url       (str url)
         :pid       (.pid proc)
         :detached? true}))))

(defn browse
  "Open `url` in the browser named by `BROWSER`, or `firefox` when it is
  unset.

  Returns as soon as the browser process starts, without waiting for it, so an
  open browser window does not hold up the REPL. Pass `:wait? true` to wait for
  the process and capture its output; see the namespace docstring for the full
  list of options and the shape of the result map.

      (browse \"https://clojure.org\")
      (browse \"https://clojure.org\" {:browser \"chromium\" :wait? true})"
  ([url] (browse url {}))
  ([url opts] (launch url opts)))

(defn browse!
  "Like [[browse]], but throw an `ex-info` when the browser fails.

  A failure to start the process is always thrown. In `:wait?` mode a non-zero
  exit is thrown too, with the command, exit code, stdout, and stderr in the
  exception data. A detached browser's exit status is not collected, so only a
  launch failure is detectable there."
  ([url] (browse! url {}))
  ([url opts]
   (let [result (launch url opts)]
     (if (:detached? result)
       result
       (cli/check! result {:label "Browser" :data-prefix "browser"})))))
