(ns clai.daemon.files-test
  "Tests for the path-completion filesystem listing used by the web UI."
  (:require [clai.daemon.files :as files]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.java.io :as io]
            [clojure.test :refer :all])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- temp-dir
  "Create an empty temporary directory and return its canonical `File`."
  []
  (.toFile (Files/createTempDirectory "clai-files-"
                                      (make-array FileAttribute 0))))

(defn- touch!
  "Create an empty file named `path` below `root`, making parent directories as
  needed. Returns the file."
  [^File root path]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file "")
    file))

(defn- with-tree
  "Run `f` with a temporary working directory containing a small tree:

      README.md
      .hidden
      src/app.clj
      src/Alpha.clj
      src/nested/deep.clj
      docs/guide.md"
  [f]
  (let [root (temp-dir)]
    (try
      (doseq [path ["README.md"
                    ".hidden"
                    "src/app.clj"
                    "src/Alpha.clj"
                    "src/nested/deep.clj"
                    "docs/guide.md"]]
        (touch! root path))
      (f (.getCanonicalFile root))
      (finally
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file true))))))

(defn- names
  "The `:name` values of a completion result's entries."
  [result]
  (mapv :name (:entries result)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest root-listing-puts-directories-first-and-hides-dotfiles
  (with-tree
    (fn [root]
      (let [result (files/list-entries {:root (str root) :path ""})]
        (is (= "" (:directory result)))
        (is (= "" (:prefix result)))
        (is (= ["docs" "src" "README.md"] (names result)))
        (is (= [{:name "docs" :path "docs/" :type "directory"}
                {:name "src" :path "src/" :type "directory"}
                {:name "README.md" :path "README.md" :type "file"}]
               (:entries result)))))))

(deftest directory-paths-end-with-a-slash-so-the-client-can-descend
  (with-tree
    (fn [root]
      (let [result (files/list-entries {:root (str root) :path "src/"})]
        (is (= "src" (:directory result)))
        (is (= "" (:prefix result)))
        (is (= ["nested" "Alpha.clj" "app.clj"] (names result)))
        (is (= "src/nested/" (:path (first (:entries result)))))))))

(deftest prefixes-filter-case-insensitively
  (with-tree
    (fn [root]
      (is (= ["nested"] (names (files/list-entries {:root (str root)
                                                    :path "src/n"}))))
      (is (= ["Alpha.clj" "app.clj"]
             (names (files/list-entries {:root (str root) :path "src/a"}))))
      (is (= ["Alpha.clj"] (names (files/list-entries {:root (str root)
                                                       :path "src/Al"}))))
      (is (= ["src"] (names (files/list-entries {:root (str root) :path "S"})))))))

(deftest a-leading-dot-opts-into-hidden-entries
  (with-tree
    (fn [root]
      (is (= [".hidden"] (names (files/list-entries {:root (str root)
                                                     :path "."})))))))

(deftest the-files-endpoint-opts-into-hidden-entries-entirely
  (with-tree
    (fn [root]
      (is (= ["docs" "src" ".hidden" "README.md"]
             (names (files/list-entries {:root            (str root)
                                         :path            ""
                                         :include-hidden? true})))))))

(deftest nested-directories-are-listed-with-relative-paths
  (with-tree
    (fn [root]
      (is (= ["deep.clj"] (names (files/list-entries {:root (str root)
                                                      :path "src/nested/d"}))))
      (is (= "src/nested/deep.clj"
             (:path (first (:entries (files/list-entries
                                      {:root (str root)
                                       :path "src/nested/deep.clj"})))))))))

(deftest paths-outside-the-root-are-refused
  (with-tree
    (fn [root]
      (doseq [path ["../" "../../etc/passwd" "/etc/passwd" "src/../../"]]
        (is (= [] (:entries (files/list-entries {:root (str root) :path path})))
            path)))))

(deftest missing-and-non-directory-roots-yield-no-entries
  (with-tree
    (fn [root]
      (is (= [] (:entries (files/list-entries {:root (str root) :path "nope/"}))))
      (is (= [] (:entries (files/list-entries {:root (str root)
                                              :path "README.md/"}))))
      (is (= [] (:entries (files/list-entries {:root (str root)
                                              :path "src/app.clj/"}))))
      (is (= [] (:entries (files/list-entries {:root nil :path ""}))))
      (is (= [] (:entries (files/list-entries {:root "" :path ""})))))))

(deftest limits-cap-the-number-of-entries
  (with-tree
    (fn [root]
      (is (= ["docs"] (names (files/list-entries {:root (str root)
                                                   :path ""
                                                   :limit 1}))))
      (is (= ["docs" "src" "README.md"]
             (names (files/list-entries {:root (str root)
                                         :path ""
                                         :limit files/DefaultLimit})))))))

;; ---------------------------------------------------------------------------
;; File preview
;; ---------------------------------------------------------------------------

(deftest read-file-returns-the-text-of-a-file-below-the-root
  (with-tree
    (fn [root]
      (spit (io/file root "src/app.clj") "(ns app)")
      (is (= {:path       "src/app.clj"
              :name       "app.clj"
              :content    "(ns app)"
              :truncated false
              :binary    false}
             (files/read-file {:root (str root) :path "src/app.clj"}))))))

(deftest read-file-truncates-large-files
  (with-tree
    (fn [root]
      (spit (io/file root "big.txt")
            (apply str (repeat (+ files/MaxPreviewBytes 10) "a")))
      (let [result (files/read-file {:root (str root) :path "big.txt"})]
        (is (= files/MaxPreviewBytes (count (:content result))))
        (is (true? (:truncated result)))))))

(deftest read-file-does-not-preview-binary-files
  (with-tree
    (fn [root]
      (with-open [out (io/output-stream (io/file root "blob.bin"))]
        (.write out (byte-array [1 2 0 3])))
      (is (= {:path       "blob.bin"
              :name       "blob.bin"
              :content    ""
              :truncated false
              :binary    true}
             (files/read-file {:root (str root) :path "blob.bin"}))))))

(deftest read-file-refuses-paths-outside-the-root-and-non-files
  (with-tree
    (fn [root]
      (doseq [path ["../etc/passwd" "/etc/passwd" "src" "src/" "nope.clj" "" nil]]
        (is (nil? (files/read-file {:root (str root) :path path})) (pr-str path)))
      (is (nil? (files/read-file {:root nil :path "README.md"})))
      (is (nil? (files/read-file {:root "" :path "README.md"}))))))
