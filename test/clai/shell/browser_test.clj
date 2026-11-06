(ns clai.shell.browser-test
  "Tests for the browser launcher in `clai.shell.browser`."
  (:require [clai.shell.browser :as browser]
            [clai.shell.cli :as cli]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temporary directories and fake browsers
;; ---------------------------------------------------------------------------

(defn- delete-tree!
  "Recursively delete `file` and its contents."
  [^File file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (.listFiles file)]
        (delete-tree! child)))
    (.delete file)))

(defn- make-temp-dir
  "Create a temporary directory and return it as a `File`."
  []
  (.toFile (Files/createTempDirectory "clai-browser-test"
                                      (make-array FileAttribute 0))))

(defn- with-temp-dir
  "Run `f` with a fresh temporary directory, cleaning it up afterwards."
  [f]
  (let [dir (make-temp-dir)]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

(defn- args-file
  "The file a fake browser writes its arguments to."
  [^File dir]
  (io/file dir "args.txt"))

(defn- fake-browser
  "Write an executable fake browser into `dir` and return its absolute path.

  The script records each argument on its own line in args.txt, prints the
  first argument to stdout, and exits with `exit`."
  ([dir] (fake-browser dir 0))
  ([^File dir exit]
   (let [script (io/file dir "fake-browser")
         args   (str "'" (.getPath (args-file dir)) "'")]
     (spit script
           (str "#!/bin/sh\n"
                "printf '%s\\n' \"$@\" > " args "\n"
                "printf 'opened %s\\n' \"$1\"\n"
                "exit " exit "\n"))
     (.setExecutable script true)
     (.getAbsolutePath script))))

(defn- recorded-args
  "Read the arguments the fake browser recorded, or nil when it has not run
  yet."
  [^File dir]
  (let [file (args-file dir)]
    (when (.exists file)
      (vec (str/split-lines (str/trim (slurp file)))))))

(defn- await-args
  "Wait up to five seconds for the fake browser to record its arguments."
  [^File dir]
  (loop [remaining 50]
    (or (recorded-args dir)
        (when (pos? remaining)
          (Thread/sleep 100)
          (recur (dec remaining))))))

;; ---------------------------------------------------------------------------
;; Command resolution
;; ---------------------------------------------------------------------------

(deftest command-splits-the-browser-command
  (is (= ["firefox" "--new-tab"]
         (browser/split-command "firefox --new-tab")))
  (is (= ["firefox"]
         (browser/split-command "  firefox  ")))
  (is (= ["/path with spaces/browser" "--flag" "a b"]
         (browser/split-command
          "\"/path with spaces/browser\" --flag \"a b\"")))
  (is (= ["/path with spaces/browser"]
         (browser/split-command "'/path with spaces/browser'")))
  (is (= ["a b" "c"]
         (browser/split-command "a\\ b c")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unterminated quote"
                        (browser/split-command "\"firefox")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Unterminated quote"
                        (browser/split-command "firefox\\"))))

(deftest command-falls-back-to-firefox
  (let [url "https://clojure.org"]
    (is (= ["firefox" url]
           (browser/command url {:env {"BROWSER" nil}})))
    (is (= ["firefox" url]
           (browser/command url {:env {"BROWSER" "   "}})))
    (is (= ["firefox" url]
           (browser/command url {:env {"BROWSER" nil}
                                 :browser "  "})))))

(deftest command-uses-the-browser-environment-variable
  (let [url "https://clojure.org"]
    (is (= ["chromium" "--new-window" url]
           (browser/command url {:env {"BROWSER" "chromium --new-window"}})))
    (is (= ["chromium" "--new-window" url]
           (browser/command url {:env {:BROWSER "chromium --new-window"}})))))

(deftest command-substitutes-the-url-placeholder
  (let [url "https://clojure.org"]
    (is (= ["chromium" (str "--app=" url)]
           (browser/command url {:env {"BROWSER" "chromium --app=%s"}})))
    (is (= [(str "open:" url)]
           (browser/command url {:env {"BROWSER" "open:%s"}})))))

(deftest command-prefers-explicit-overrides
  (let [url "https://clojure.org"]
    (is (= ["xdg-open" url]
           (browser/command url {:browser "xdg-open"
                                 :env     {"BROWSER" "chromium"}})))
    (is (= ["epiphany" url]
           (binding [browser/*browser* "epiphany"]
             (browser/command url {:env {"BROWSER" "chromium"}}))))
    (is (= ["xdg-open" url]
           (binding [browser/*browser* "epiphany"]
             (browser/command url {:browser "xdg-open"
                                   :env     {"BROWSER" "chromium"}}))))))

;; ---------------------------------------------------------------------------
;; Launching
;; ---------------------------------------------------------------------------

(deftest browse-waits-and-captures-output-when-asked
  (with-temp-dir
    (fn [dir]
      (let [script (fake-browser dir)
            url    "https://clojure.org"
            result (browser/browse url {:browser script
                                        :dir     dir
                                        :wait?   true})]
        (is (= [script url] (:command result)))
        (is (= url (:url result)))
        (is (= 0 (:exit result)))
        (is (true? (:success? result)))
        (is (= (str "opened " url "\n") (:out result)))
        (is (= "" (:err result)))
        (is (= (.getAbsolutePath dir) (:dir result)))
        (is (= [url] (recorded-args dir)))))))

(deftest browse-launches-detached-by-default
  (with-temp-dir
    (fn [dir]
      (let [script (fake-browser dir)
            url    "https://clojure.org"
            result (browser/browse url {:browser script :dir dir})]
        (is (true? (:detached? result)))
        (is (= [script url] (:command result)))
        (is (= url (:url result)))
        (is (pos? (:pid result)))
        (is (= (.getAbsolutePath dir) (:dir result)))
        (is (= [url] (await-args dir)))))))

(deftest browse-uses-the-shared-virtual-cwd
  (with-temp-dir
    (fn [dir]
      (let [script (fake-browser dir)]
        (binding [cli/*cwd* (.getAbsolutePath dir)]
          (let [result (browser/browse "https://clojure.org"
                                       {:browser script :wait? true})]
            (is (= (.getAbsolutePath dir) (:dir result)))
            (is (seq (recorded-args dir)))))))))

(deftest browse-throws-when-the-browser-cannot-start
  (with-temp-dir
    (fn [dir]
      (let [ex (try
                 (browser/browse "https://clojure.org"
                                 {:browser "/no/such/browser" :dir dir})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= ["/no/such/browser" "https://clojure.org"]
               (:browser/command (ex-data ex))))
        (is (str/includes? (ex-message ex) "/no/such/browser"))))))

(deftest browse!-throws-on-a-non-zero-exit
  (with-temp-dir
    (fn [dir]
      (let [script (fake-browser dir 3)
            ex     (try
                     (browser/browse! "https://clojure.org"
                                      {:browser script
                                       :dir     dir
                                       :wait?   true})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= 3 (:browser/exit (ex-data ex))))
        (is (= ["https://clojure.org"] (recorded-args dir)))))))

(deftest browse!-returns-a-successful-result
  (with-temp-dir
    (fn [dir]
      (let [script (fake-browser dir)
            result (browser/browse! "https://clojure.org"
                                    {:browser script
                                     :dir     dir
                                     :wait?   true})]
        (is (true? (:success? result)))))))
