(ns clai.repl.git-test
  "Tests for the git CLI helpers in `clai.repl.git`."
  (:require [clai.repl.git :as git]
            [clojure.java.io :as io]
            [clojure.test :refer :all])
  (:import [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Temporary repositories
;; ---------------------------------------------------------------------------

(defn- delete-tree!
  "Recursively delete `file` and its contents."
  [^File file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (.listFiles file)]
        (delete-tree! child)))
    (.delete file)))

(defn- make-repo
  "Create an initialized temporary git repository and return its directory."
  []
  (let [path (Files/createTempDirectory "clai-git-test"
                                        (make-array FileAttribute 0))
        dir  (.toFile path)]
    (git/run! ["init" "-q"] {:dir dir})
    (git/run! ["config" "user.email" "test@example.com"] {:dir dir})
    (git/run! ["config" "user.name" "Test User"] {:dir dir})
    (git/run! ["config" "commit.gpgsign" "false"] {:dir dir})
    dir))

(defn- with-repo
  "Run `f` with a fresh temporary repository, cleaning it up afterwards."
  [f]
  (let [dir (make-repo)]
    (try
      (f dir)
      (finally
        (delete-tree! dir)))))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(deftest run-captures-output-and-exit
  (with-repo
    (fn [dir]
      (let [result (git/run ["rev-parse" "--is-inside-work-tree"]
                            {:dir dir})]
        (is (= ["git" "rev-parse" "--is-inside-work-tree"]
               (:command result)))
        (is (= 0 (:exit result)))
        (is (= "true\n" (:out result)))
        (is (= "" (:err result)))
        (is (true? (:success? result)))
        (is (false? (:timed-out? result)))))))

(deftest run-does-not-throw-on-a-non-zero-exit
  (with-repo
    (fn [dir]
      (let [result (git/run ["rev-parse" "no-such-revision"] {:dir dir})]
        (is (not (:success? result)))
        (is (pos? (:exit result)))
        (is (clojure.string/includes? (:err result) "no-such-revision"))))))

(deftest run!-throws-on-a-non-zero-exit
  (with-repo
    (fn [dir]
      (let [ex (try
                 (git/run! ["rev-parse" "no-such-revision"] {:dir dir})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (pos? (:git/exit (ex-data ex))))
        (is (clojure.string/includes? (:git/err (ex-data ex))
                                      "no-such-revision"))))))

(deftest run-feeds-stdin-to-git
  (with-repo
    (fn [dir]
      (let [result (git/run ["hash-object" "--stdin"]
                            {:dir dir :in "hello\n"})]
        (is (:success? result))
        (is (re-matches #"[0-9a-f]{40}\n" (:out result)))))))

(deftest git-prints-output
  (with-repo
    (fn [dir]
      (let [out (StringWriter.)
            err (StringWriter.)
            result (binding [*out* out *err* err]
                     (git/git {:dir dir} "--version"))]
        (is (:success? result))
        (is (clojure.string/includes? (str out) "git version"))
        (is (= "" (str err)))))))

(deftest git!-prints-then-throws
  (with-repo
    (fn [dir]
      (let [err (StringWriter.)
            ex  (try
                  (binding [*out* (StringWriter.) *err* err]
                    (git/git! {:dir dir} "rev-parse" "no-such-revision"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (clojure.string/includes? (str err) "no-such-revision"))))))

(deftest out-returns-trimmed-stdout
  (with-repo
    (fn [dir]
      (spit (io/file dir "a.txt") "one\n")
      (git/add "a.txt" {:dir dir})
      (git/commit "initial" {:dir dir})
      (is (= (git/head {:dir dir})
             (git/out ["rev-parse" "HEAD"] {:dir dir}))))))

;; ---------------------------------------------------------------------------
;; Status
;; ---------------------------------------------------------------------------

(deftest status-reports-an-initial-empty-repository
  (with-repo
    (fn [dir]
      (let [status (git/status {:dir dir})]
        (is (string? (:branch status)))
        (is (true? (:initial? status)))
        (is (empty? (:entries status)))
        (is (true? (:clean? status)))))))

(deftest status-parses-untracked-staged-and-clean-worktrees
  (with-repo
    (fn [dir]
      (spit (io/file dir "notes.txt") "hello\n")
      (let [status (git/status {:dir dir})]
        (is (true? (:dirty? status)))
        (is (false? (:clean? status)))
        (is (= ["notes.txt"] (mapv :path (:untracked status))))
        (is (= "??" (:status (first (:untracked status))))))

      (git/add "notes.txt" {:dir dir})
      (let [status (git/status {:dir dir})]
        (is (empty? (:untracked status)))
        (is (= ["notes.txt"] (mapv :path (:staged status))))
        (is (= "A" (:status (first (:staged status))))))

      (git/commit "Initial commit" {:dir dir})
      (let [status (git/status {:dir dir})]
        (is (true? (:clean? status)))
        (is (false? (:dirty? status)))
        (is (string? (:branch status)))
        (is (= (:branch status) (git/current-branch {:dir dir})))
        (is (re-matches #"[0-9a-f]{40}\n"
                        (:out (git/run ["rev-parse" "HEAD"] {:dir dir}))))))))

(deftest status-parses-ahead-and-behind-information
  (with-repo
    (fn [dir]
      (let [remote (io/file dir "remote.git")]
        (git/init {:dir dir :path remote :bare? true})
        (spit (io/file dir "a.txt") "one\n")
        (git/add "a.txt" {:dir dir})
        (git/commit "one" {:dir dir})
        (let [branch (git/current-branch {:dir dir})]
          (git/run! ["remote" "add" "origin" (.getPath remote)] {:dir dir})
          (git/run! ["push" "-q" "--set-upstream" "origin" branch]
                    {:dir dir})
          (let [status (git/status {:dir dir})]
            (is (= (str "origin/" branch) (:upstream status)))
            (is (= 0 (:ahead status)))
            (is (= 0 (:behind status))))
          (spit (io/file dir "b.txt") "two\n")
          (git/add "b.txt" {:dir dir})
          (git/commit "two" {:dir dir})
          (let [status (git/status {:dir dir})]
            (is (= 1 (:ahead status)))
            (is (= 0 (:behind status)))))))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(deftest repository-queries-work
  (with-repo
    (fn [dir]
      (spit (io/file dir "a.txt") "one\n")
      (git/add "a.txt" {:dir dir})
      (git/commit "initial" {:dir dir})
      (let [branch (git/current-branch {:dir dir})]
        (is (some? branch))
        (is (contains? (set (git/branches {:dir dir})) branch))
        (is (= (.getCanonicalPath dir) (git/root {:dir dir})))
        (is (= (git/rev-parse "HEAD" {:dir dir}) (git/head {:dir dir})))
        (is (some #(= "a.txt" %) (git/ls-files {:dir dir})))
        (is (clojure.string/includes?
             (:out (git/log {:dir dir :limit 1 :oneline? true}))
             "initial"))
        (is (clojure.string/includes?
             (:out (git/show "HEAD" {:dir dir}))
             "a.txt"))))))

(deftest diffs-report-both-worktree-and-index-changes
  (with-repo
    (fn [dir]
      (spit (io/file dir "a.txt") "one\n")
      (git/add "a.txt" {:dir dir})
      (git/commit "initial" {:dir dir})

      (spit (io/file dir "a.txt") "two\n")
      (is (clojure.string/includes? (:out (git/diff {:dir dir})) "-one"))

      (git/add "a.txt" {:dir dir})
      (is (clojure.string/includes?
           (:out (git/diff {:dir dir :staged? true}))
           "+two")))))

(deftest branches-can-be-created-switched-and-deleted
  (with-repo
    (fn [dir]
      (spit (io/file dir "a.txt") "one\n")
      (git/add "a.txt" {:dir dir})
      (git/commit "initial" {:dir dir})
      (let [original (git/current-branch {:dir dir})]
        (git/create-branch "feature" {:dir dir})
        (is (contains? (set (git/branches {:dir dir})) "feature"))
        (git/switch "feature" {:dir dir})
        (is (= "feature" (git/current-branch {:dir dir})))
        (git/switch original {:dir dir})
        (git/delete-branch "feature" {:dir dir})
        (is (not (contains? (set (git/branches {:dir dir})) "feature")))))))

(deftest stashes-can-be-created-listed-applied-and-dropped
  (with-repo
    (fn [dir]
      (spit (io/file dir "a.txt") "one\n")
      (git/add "a.txt" {:dir dir})
      (git/commit "initial" {:dir dir})
      (spit (io/file dir "a.txt") "two\n")
      (git/stash {:dir dir :message "work in progress"})
      (is (true? (git/clean? {:dir dir})))
      (is (clojure.string/includes?
           (:out (git/stash-list {:dir dir}))
           "work in progress"))
      (git/stash-pop {:dir dir})
      (is (false? (git/clean? {:dir dir})))
      (is (clojure.string/includes?
           (:out (git/diff {:dir dir}))
           "+two")))))

(deftest config-reads-repository-values
  (with-repo
    (fn [dir]
      (is (= "Test User" (git/config "user.name" {:dir dir})))
      (is (clojure.string/includes?
           (git/version {:dir dir})
           "git version")))))
