(ns clai.shell.task-test
  "Tests for the task-binary helpers in `clai.shell.task`."
  (:require [clai.shell.cli :as cli]
            [clai.shell.task :as task]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temporary projects
;; ---------------------------------------------------------------------------

(defn- delete-tree!
  "Recursively delete `file` and its contents."
  [^File file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (.listFiles file)]
        (delete-tree! child)))
    (.delete file)))

(def ^:private taskfile
  "version: \"3\"

tasks:
  default:
    desc: Default task
    cmds:
      - echo default

  greet:
    desc: Say hello
    aliases: [hi]
    cmds:
      - echo hello

  greet-name:
    desc: Greet a named person
    cmds:
      - echo \"hello {{.NAME}}\"

  fail:
    cmds:
      - exit 3

  write:
    desc: Write a marker file
    status:
      - test -f marker.txt
    cmds:
      - touch marker.txt

  hidden:
    cmds:
      - echo hidden

  with-dep:
    desc: Has a dependency
    deps: [greet]
    cmds:
      - echo dependent
")

(defn- make-project
  "Create a temporary directory containing a Taskfile."
  []
  (let [path (Files/createTempDirectory "clai-task-test"
                                        (make-array FileAttribute 0))
        dir  (.toFile path)]
    (spit (io/file dir "Taskfile.yml") taskfile)
    dir))

(defn- with-project
  "Run `f` with a fresh temporary project, cleaning it up afterwards."
  [f]
  (let [dir (make-project)]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(deftest run-captures-output-and-exit
  (with-project
    (fn [dir]
      (let [result (task/run ["--version"] {:dir dir})]
        (is (= ["task" "--version"] (:command result)))
        (is (= 0 (:exit result)))
        (is (str/includes? (:out result) "Task version"))
        (is (= "" (:err result)))
        (is (true? (:success? result)))
        (is (false? (:timed-out? result)))))))

(deftest run-does-not-throw-on-a-non-zero-exit
  (with-project
    (fn [dir]
      (let [result (task/run ["fail"] {:dir dir})]
        (is (not (:success? result)))
        (is (pos? (:exit result)))
        (is (str/includes? (:err result) "fail"))))))

(deftest run!-throws-on-a-non-zero-exit
  (with-project
    (fn [dir]
      (let [ex (try
                 (task/run! ["fail"] {:dir dir})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (pos? (:task/exit (ex-data ex))))
        (is (str/includes? (:task/err (ex-data ex)) "fail"))))))

(deftest task-prints-output
  (with-project
    (fn [dir]
      (let [out (StringWriter.)
            err (StringWriter.)
            result (binding [*out* out *err* err]
                     (task/task {:dir dir} "greet"))]
        (is (:success? result))
        (is (str/includes? (str out) "hello"))
        (is (str/includes? (str err) "task: [greet]"))))))

(deftest task!-prints-then-throws
  (with-project
    (fn [dir]
      (let [err (StringWriter.)
            ex  (try
                  (binding [*out* (StringWriter.) *err* err]
                    (task/task! {:dir dir} "fail"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (str/includes? (str err) "fail"))))))

(deftest out-returns-trimmed-stdout
  (with-project
    (fn [dir]
      (is (= "hello" (task/out ["greet"] {:dir dir :silent? true})))
      (is (= "hello Ada"
             (task/out ["greet-name"]
                       {:dir dir :silent? true :vars {"NAME" "Ada"}}))))))

(deftest run-uses-the-shared-virtual-cwd
  (with-project
    (fn [dir]
      (binding [cli/*cwd* (.getAbsolutePath dir)]
        (let [result (task/run ["greet"] {:silent? true})]
          (is (:success? result))
          (is (= "hello\n" (:out result)))
          (is (= (.getAbsolutePath dir) (:dir result))))))))

;; ---------------------------------------------------------------------------
;; Listing, summaries, and status
;; ---------------------------------------------------------------------------

(deftest list-parses-descriptions-and-aliases
  (with-project
    (fn [dir]
      (let [{:keys [tasks raw]} (task/list {:dir dir})
            by-name             (into {} (map (juxt :name identity)) tasks)]
        (is (str/includes? raw "Available tasks"))
        (is (= "Say hello" (:description (by-name "greet"))))
        (is (= ["hi"] (:aliases (by-name "greet"))))
        (is (contains? by-name "hidden"))
        (is (nil? (:description (by-name "hidden"))))))))

(deftest list-can-hide-undescribed-tasks
  (with-project
    (fn [dir]
      (let [names (set (map :name (task/tasks {:dir dir :all? false})))]
        (is (contains? names "greet"))
        (is (not (contains? names "hidden")))))))

(deftest summary-parses-sections
  (with-project
    (fn [dir]
      (let [summary (task/summary "with-dep" {:dir dir})]
        (is (= "with-dep" (:name summary)))
        (is (= "Has a dependency" (:description summary)))
        (is (= ["greet"] (:dependencies summary)))
        (is (= ["echo dependent"] (:commands summary)))))))

(deftest status-and-up-to-date?-reflect-marker-files
  (with-project
    (fn [dir]
      (is (false? (task/up-to-date? "write" {:dir dir})))
      (is (not (:success? (task/status "write" {:dir dir}))))
      (task/run! ["write"] {:dir dir})
      (is (true? (task/up-to-date? "write" {:dir dir}))))))

(deftest dry-run-does-not-execute-commands
  (with-project
    (fn [dir]
      (let [result (task/dry-run ["write"] {:dir dir})]
        (is (:success? result))
        (is (not (.exists (io/file dir "marker.txt"))))))))

(deftest taskfile-names-the-file-to-use
  (with-project
    (fn [dir]
      (spit (io/file dir "Other.yml")
            "version: \"3\"\ntasks:\n  other:\n    desc: Other task\n")
      (let [names (set (map :name (task/tasks {:dir dir
                                               :taskfile "Other.yml"})))]
        (is (= #{"other"} names))))))

(deftest version-reports-the-installed-task
  (with-project
    (fn [dir]
      (is (str/includes? (task/version {:dir dir}) "Task version")))))
