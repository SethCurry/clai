(ns clai.lsp-test
  "Tests for the parts of the `clojure-lsp` client that do not need a running
  server: project roots, URI conversion, and executable resolution."
  (:require [clai.lsp :as lsp]
            [clai.test-support :refer [quiet-logs]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once quiet-logs)

(defn- temp-dir
  "Create an empty temporary directory and return it as a `java.io.File`."
  []
  (.toFile (Files/createTempDirectory "clai-lsp-test"
                                      (make-array FileAttribute 0))))

(deftest project-root-walks-up-to-a-project-marker
  (let [root    (temp-dir)
        nested  (io/file root "src" "deep" "inside")]
    (try
      (spit (io/file root "deps.edn") "{}")
      (.mkdirs nested)
      (is (= (.getAbsolutePath root) (lsp/project-root nested))
          "the closest ancestor with a deps.edn is the project root")
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest project-root-falls-back-to-the-directory-itself
  (let [root (temp-dir)]
    (try
      (is (= (.getAbsolutePath root) (lsp/project-root root)))
      (finally
        (.delete root)))))

(deftest path-and-uri-round-trip
  (let [root (temp-dir)
        file (io/file root "a file with spaces.clj")]
    (try
      (spit file "(ns x)")
      (let [uri (lsp/path->uri file)]
        (is (str/starts-with? uri "file:///")
            "LSP wants a three-slash file URI")
        (is (= (.getAbsolutePath file) (lsp/uri->path uri))
            "the URI converts back to the same path"))
      (finally
        (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest resolve-executable-finds-a-real-file
  (let [root (temp-dir)
        exe  (io/file root "clojure-lsp")]
    (try
      (spit exe "#!/bin/sh\n")
      (.setExecutable exe true)
      (is (= (.getAbsolutePath exe) (lsp/resolve-executable (.getAbsolutePath exe))))
      (is (= (.getAbsolutePath exe) (lsp/resolve-executable (str root File/separator "clojure-lsp")))
          "a path with a separator is used as given")
      (finally
        (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest resolve-executable-explains-what-to-install
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Install clojure-lsp"
                        (lsp/resolve-executable "clai-definitely-not-installed"))
      "a missing executable names the installation page instead of failing in
      ProcessBuilder"))

(deftest resolve-executable-rejects-a-missing-explicit-path
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"not found at"
                        (lsp/resolve-executable "/definitely/not/here/clojure-lsp"))))
