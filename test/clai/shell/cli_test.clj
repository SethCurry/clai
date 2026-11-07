(ns clai.shell.cli-test
  "Tests for the shared CLI process helpers in `clai.shell.cli`."
  (:require [clai.shell.cli :as cli]
            [clojure.java.io :as io]
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

(defn- with-root-cwd
  "Run `f` with [[cli/*cwd*]]'s root binding set to `dir`, restoring the
  original root afterwards."
  [dir f]
  (let [original (var-get #'cli/*cwd*)]
    (try
      (alter-var-root #'cli/*cwd* (constantly dir))
      (f)
      (finally
        (alter-var-root #'cli/*cwd* (constantly original))))))

(deftest pwd-reports-the-virtual-working-directory
  (with-temp-dir
    (fn [dir]
      (binding [cli/*cwd* (.getAbsolutePath dir)]
        (is (= (.getAbsolutePath dir) (cli/pwd))))
      (with-root-cwd
        nil
        (fn []
          (is (= (.getAbsolutePath (io/file (System/getProperty "user.dir")))
                 (cli/pwd))))))))

(deftest cd-updates-the-default-working-directory
  (with-temp-dir
    (fn [dir]
      (let [sub (io/file dir "sub")]
        (is (.mkdir sub))
        (with-root-cwd
          nil
          (fn []
            (is (= (.getCanonicalPath dir)
                   (cli/cd (.getAbsolutePath dir))))
            (is (= (.getCanonicalPath dir) (cli/pwd)))
            (is (= (.getCanonicalPath dir)
                   (str/trim (:out (cli/run "pwd" [])))))
            (is (= (.getCanonicalPath sub) (cli/cd "sub")))
            (is (= (.getCanonicalPath sub)
                   (str/trim (:out (cli/run "pwd" [])))))))))))

(deftest cd-rejects-a-missing-directory
  (with-temp-dir
    (fn [dir]
      (with-root-cwd
        nil
        (fn []
          (let [missing (.getAbsolutePath (io/file dir "nope"))
                ex      (try
                          (cli/cd missing)
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
            (is (some? ex))
            (is (= missing (:dir (ex-data ex))))
            (is (str/starts-with? (ex-message ex) "Not a working directory"))
            (is (nil? cli/*cwd*))))))))

(deftest cd-defaults-to-the-home-directory
  (with-root-cwd
    nil
    (fn []
      (let [home (System/getProperty "user.home")]
        (is (= (.getCanonicalPath (io/file home)) (cli/cd)))
        (is (= (.getCanonicalPath (io/file home)) (cli/pwd)))))))

(defn- with-namespace-cwd
  "Run `f` in a fresh namespace that has its own dynamic `*cwd*` Var set to
  `dir`, the way a session namespace does."
  [dir f]
  (let [ns (create-ns (gensym "clai-cli-cwd-test-"))]
    (try
      (let [v (intern ns '*cwd* (some-> dir str))]
        (.setDynamic ^clojure.lang.Var v true)
        (alter-meta! v assoc :dynamic true))
      (binding [*ns* ns] (f))
      (finally
        (remove-ns (ns-name ns))))))

(deftest a-namespace-cwd-wins-over-the-global-one
  (with-temp-dir
    (fn [dir]
      (let [global (io/file dir "global")]
        (is (.mkdir global))
        (with-namespace-cwd (.getAbsolutePath dir)
          (fn []
            (binding [cli/*cwd* (.getAbsolutePath global)]
              (is (= (.getAbsolutePath dir) (cli/pwd)))
              (is (= (.getAbsolutePath dir)
                     (str/trim (:out (cli/run "pwd" []))))))))))))

(deftest cd-updates-only-the-namespace-cwd
  (with-temp-dir
    (fn [dir]
      (let [sub (io/file dir "sub")]
        (is (.mkdir sub))
        (with-root-cwd
          nil
          (fn []
            (with-namespace-cwd (.getAbsolutePath dir)
              (fn []
                (is (= (.getCanonicalPath sub) (cli/cd "sub")))
                (is (= (.getCanonicalPath sub) (cli/pwd)))
                (is (= (.getCanonicalPath sub)
                       (str/trim (:out (cli/run "pwd" [])))))))
            (is (nil? cli/*cwd*))))))))

(deftest an-unset-namespace-cwd-falls-back-to-the-global-one
  (with-temp-dir
    (fn [dir]
      (with-root-cwd
        (.getAbsolutePath dir)
        (fn []
          (with-namespace-cwd nil
            (fn []
              (is (= (.getAbsolutePath dir) (cli/pwd)))
              (is (= (.getAbsolutePath dir)
                     (str/trim (:out (cli/run "pwd" []))))))))))))
