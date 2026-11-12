(ns clai.daemon.postgres.git-test
  (:require [clai.daemon.postgres :as pg]
            [clai.daemon.postgres.git :as git]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :once quiet-logs)

(defn- unique-name
  "A repository name that will not collide with another test run."
  [prefix]
  (str prefix "-" (UUID/randomUUID)))

(defn- unique-dir
  "A worktree directory that will not collide with another test run."
  []
  (str "/tmp/clai-test-worktrees/" (UUID/randomUUID)))

;; ---------------------------------------------------------------------------
;; Input normalization and row conversion
;; ---------------------------------------------------------------------------

(deftest row-keys-must-name-a-row
  (is (= 12 (git/row-key 12)))
  (is (= 12 (git/row-key "12")))
  (is (nil? (git/row-key "0")))
  (is (nil? (git/row-key "-1")))
  (is (nil? (git/row-key "not-a-row")))
  (is (nil? (git/row-key nil))))

(deftest prepare-repo-trims-and-validates
  (is (= {:name "clai" :url "https://example.com/clai.git"}
         (git/prepare-repo {:name "  clai  "
                            :url  "  https://example.com/clai.git  "})))
  (is (thrown? clojure.lang.ExceptionInfo
               (git/prepare-repo {:name "   " :url "https://example.com"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (git/prepare-repo {:name "clai" :url ""})))
  (is (thrown? clojure.lang.ExceptionInfo
               (git/prepare-repo {:name "clai"}))))

(deftest prepare-worktree-trims-and-resolves-repo-id
  (is (= {:repoId 7 :branch "feature/x" :directory "/tmp/wt"}
         (git/prepare-worktree {:repoId    "7"
                                :branch    "  feature/x  "
                                :directory "  /tmp/wt  "})))
  (is (= 7 (:repoId (git/prepare-worktree {:repoId    7
                                           :branch    "main"
                                           :directory "/tmp/wt"}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (git/prepare-worktree {:repoId    "not-a-row"
                                      :branch    "main"
                                      :directory "/tmp/wt"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (git/prepare-worktree {:repoId    7
                                      :branch    "  "
                                      :directory "/tmp/wt"}))))

(deftest rows-convert-to-public-shapes
  (is (= {:repoId "3" :name "clai" :url "https://example.com/clai.git"}
         (git/row->repo {:id 3
                         :name "clai"
                         :url "https://example.com/clai.git"})))
  (is (= {:worktreeId "9"
          :repoId "3"
          :repoName "clai"
          :repoUrl "https://example.com/clai.git"
          :branch "main"
          :directory "/tmp/wt"}
         (git/row->worktree {:id 9
                             :git-repo-id 3
                             :repo-name "clai"
                             :repo-url "https://example.com/clai.git"
                             :branch "main"
                             :directory "/tmp/wt"}))))

;; ---------------------------------------------------------------------------
;; Optional integration test
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.daemon.postgres.git-test

(deftest repo-and-worktree-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client (pg/open {:url url})
          name   (unique-name "clai-test")
          repo   (git/create-repo client {:name (str "  " name "  ")
                                          :url  "https://example.com/clai.git"})]
      (try
        (let [repo-id (:repoId repo)]
          (is (string? repo-id))
          (is (= name (:name repo)))
          (is (some? (:createdAt repo)))
          (is (git/repo-exists? client repo-id))
          (is (= repo-id (:repoId (git/load-repo client repo-id))))
          (is (= repo-id (:repoId (git/select-repo client name))))
          (is (some #(= repo-id (:repoId %)) (git/list-repos client)))

          ;; The name is unique, so a duplicate insert is rejected.
          (is (thrown? clojure.lang.ExceptionInfo
                       (git/create-repo client {:name name
                                                :url  "https://example.com/other"})))
          (is (nil? (git/load-repo client "not-a-row")))
          (is (nil? (git/select-repo client "not-a-repo")))
          (is (false? (git/repo-exists? client "999999999")))

          (let [directory (unique-dir)
                worktree  (git/create-worktree
                           client
                           {:repoId    repo-id
                            :branch    "  feature/git-repos  "
                            :directory (str "  " directory "  ")})]
            (is (string? (:worktreeId worktree)))
            (is (= repo-id (:repoId worktree)))
            (is (= name (:repoName worktree)))
            (is (= "feature/git-repos" (:branch worktree)))
            (is (= directory (:directory worktree)))

            (is (= (:worktreeId worktree)
                   (:worktreeId (git/load-worktree client (:worktreeId worktree)))))
            (is (= (:worktreeId worktree)
                   (:worktreeId (git/find-worktree client directory))))
            (is (some #(= (:worktreeId worktree) (:worktreeId %))
                      (git/list-worktrees client)))
            (is (some #(= (:worktreeId worktree) (:worktreeId %))
                      (git/list-worktrees client repo-id)))
            (is (= [] (git/list-worktrees client "not-a-row")))

            ;; A branch can only be checked out once per repository, and a
            ;; directory can only hold one worktree.
            (is (thrown? clojure.lang.ExceptionInfo
                         (git/create-worktree
                          client
                          {:repoId    repo-id
                           :branch    "feature/git-repos"
                           :directory (unique-dir)})))
            (is (thrown? clojure.lang.ExceptionInfo
                         (git/create-worktree
                          client
                          {:repoId    repo-id
                           :branch    "other"
                           :directory directory})))

            ;; A worktree of an unknown repository violates the foreign key.
            (is (thrown? clojure.lang.ExceptionInfo
                         (git/create-worktree
                          client
                          {:repoId    999999999
                           :branch    "main"
                           :directory (unique-dir)})))

            (let [moved (unique-dir)]
              (is (= {:branch "release/1.0" :directory moved}
                     (select-keys (git/update-worktree client (:worktreeId worktree)
                                                       {:repoId    repo-id
                                                        :branch    "release/1.0"
                                                        :directory moved})
                                  [:branch :directory]))))
            (is (nil? (git/update-worktree client "999999999"
                                           {:repoId    repo-id
                                            :branch    "main"
                                            :directory (unique-dir)})))

            (is (true? (git/delete-worktree client (:worktreeId worktree))))
            (is (nil? (git/load-worktree client (:worktreeId worktree))))
            (is (false? (git/delete-worktree client (:worktreeId worktree)))))

          ;; Updating replaces the name and URL; the worktree join sees it.
          (let [renamed (str name "-renamed")
                repo    (git/update-repo client repo-id
                                         {:name renamed
                                          :url  "https://example.com/renamed.git"})
                wt      (git/create-worktree client {:repoId    repo-id
                                                     :branch    "main"
                                                     :directory (unique-dir)})]
            (is (= renamed (:name repo)))
            (is (= "https://example.com/renamed.git" (:url repo)))
            (is (= renamed (:repoName wt)))
            (is (= "https://example.com/renamed.git" (:repoUrl wt)))

            ;; Deleting a repository removes its worktrees.
            (is (true? (git/delete-repo client repo-id)))
            (is (false? (git/repo-exists? client repo-id)))
            (is (nil? (git/load-worktree client (:worktreeId wt))))
            (is (not-any? #(= (:worktreeId wt) (:worktreeId %))
                          (git/list-worktrees client))))

          (is (nil? (git/update-repo client "999999999"
                                     {:name name :url "https://example.com"})))
          (is (false? (git/delete-repo client "999999999"))))
        (finally
          (git/delete-repo client (:repoId repo)))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
