(ns clai.repl.cli-test
  "Tests for the shared CLI process helpers in `clai.repl.cli`."
  (:require [clai.repl.cli :as cli]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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
  (.toFile (Files/createTempDirectory "clai-cli-test"
                                      (make-array FileAttribute 0))))

(defn- with-temp-dir
  "Run `f` with a fresh temporary directory, cleaning it up afterwards."
  [f]
  (let [dir (make-temp-dir)]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

(deftest run-captures-output-and-exit
  (with-temp-dir
    (fn [dir]
      (let [command ["sh" "-c" "echo out; echo err >&2"]
            result  (cli/run "sh" (rest command) {:dir dir})]
        (is (= command (:command result)))
        (is (= 0 (:exit result)))
        (is (= "out\n" (:out result)))
        (is (= "err\n" (:err result)))
        (is (true? (:success? result)))
        (is (false? (:timed-out? result)))
        (is (= (.getAbsolutePath dir) (:dir result)))))))

(deftest run-does-not-throw-on-a-non-zero-exit
  (with-temp-dir
    (fn [dir]
      (let [result (cli/run "sh" ["-c" "echo bad >&2; exit 7"] {:dir dir})]
        (is (= 7 (:exit result)))
        (is (false? (:success? result)))
        (is (= "bad\n" (:err result)))))))

(deftest run!-throws-with-namespaced-error-data
  (with-temp-dir
    (fn [dir]
      (let [ex (try
                 (cli/run! "sh" ["-c" "exit 7"]
                           {:dir dir :label "demo" :data-prefix "demo"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= 7 (:demo/exit (ex-data ex))))
        (is (str/includes? (ex-message ex) "demo command failed"))))))

(deftest run-feeds-stdin-and-closes-it
  (with-temp-dir
    (fn [dir]
      (let [result (cli/run "cat" [] {:dir dir :in "hello\n"})]
        (is (:success? result))
        (is (= "hello\n" (:out result)))))))

(deftest run-uses-the-virtual-working-directory
  (with-temp-dir
    (fn [dir]
      (binding [cli/*cwd* (.getAbsolutePath dir)]
        (let [result (cli/run "pwd" [])]
          (is (:success? result))
          (is (= (.getAbsolutePath dir)
                 (str/trim (:out result))))))
      (let [result (cli/run "pwd" [] {:cwd dir})]
        (is (= (.getAbsolutePath dir)
               (str/trim (:out result))))))))

(deftest run-can-time-out
  (with-temp-dir
    (fn [dir]
      (let [result (cli/run "sh" ["-c" "sleep 5"] {:dir dir :timeout 100})]
        (is (true? (:timed-out? result)))
        (is (nil? (:exit result)))
        (is (false? (:success? result)))))))
