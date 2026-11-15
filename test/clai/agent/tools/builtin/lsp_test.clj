(ns clai.agent.tools.builtin.lsp-test
  "Tests for the `clojure-lsp` navigation tools.

  Two kinds of test live here. The helpers (resolving a position inside a
  line, rendering locations, diagnostics and outlines) are pure and always run.
  The end-to-end tests need a `clojure-lsp` executable -- found on `PATH` or
  named by `CLOJURE_LSP_BIN` -- and are skipped when there is none, because a
  checkout without clojure-lsp should still be able to run the suite."
  (:require [clai.agent.tools.builtin.lsp :as builtin-lsp]
            [clai.lsp :as lsp]
            [clai.test-support :refer [quiet-logs]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once quiet-logs)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private executable
  "Delay of the `clojure-lsp` executable to test against, or nil when none is
  installed."
  (delay
    (try
      (lsp/resolve-executable (or (System/getenv "CLOJURE_LSP_BIN") "clojure-lsp"))
      (catch Throwable _ nil))))

(def ^:private demo-core
  "Source of `src/demo/core.clj` in the throw-away test project."
  (str "(ns demo.core)\n"
       "\n"
       "(defn greet\n"
       "  \"Say hello to `name`.\"\n"
       "  [name]\n"
       "  (str \"Hello, \" name))\n"
       "\n"
       "(defn run\n"
       "  []\n"
       "  (greet \"world\"))\n"))

(def ^:private demo-other-broken
  "Source of `src/demo/other.clj` while it uses `str/upper-case` without
  requiring `clojure.string`; clojure-lsp reports that as a warning."
  (str "(ns demo.other\n"
       "  (:require [demo.core :as core]))\n"
       "\n"
       "(defn shout\n"
       "  [name]\n"
       "  (str/upper-case (core/greet name)))\n"))

(def ^:private demo-other-fixed
  "Source of `src/demo/other.clj` after adding the missing require."
  (str "(ns demo.other\n"
       "  (:require [clojure.string :as str]\n"
       "            [demo.core :as core]))\n"
       "\n"
       "(defn shout\n"
       "  [name]\n"
       "  (str/upper-case (core/greet name)))\n"))

(def ^:dynamic *ctx*
  "Context handed to the tools under test, or nil when clojure-lsp is not
  installed and the end-to-end tests are skipped."
  nil)

(defn- temp-dir
  "Create an empty temporary directory and return it as a `java.io.File`."
  []
  (.toFile (Files/createTempDirectory "clai-lsp-test"
                                      (make-array FileAttribute 0))))

(defn- write-demo-project!
  "Write a small Clojure project into `root`."
  [root]
  (.mkdirs (io/file root "src" "demo"))
  (spit (io/file root "deps.edn") "{:paths [\"src\"]}\n")
  (spit (io/file root "src" "demo" "core.clj") demo-core)
  (spit (io/file root "src" "demo" "other.clj") demo-other-fixed)
  root)

(defn- delete-tree!
  "Delete `root` and everything below it."
  [root]
  (doseq [file (reverse (file-seq (io/file root)))]
    (.delete file)))

(defn- with-demo-project
  "Create a temporary Clojure project, bind [[*ctx*]] to a context pointing the
  tools at it, and clean both up afterwards."
  [f]
  (let [root (write-demo-project! (temp-dir))
        ctx  (when-let [exe @executable]
               {:cwd (.getAbsolutePath root)
                :lsp {:executable exe}})]
    (when-not ctx
      (println "clojure-lsp is not installed; skipping the end-to-end lsp tool tests"))
    (try
      (binding [*ctx* ctx]
        (f))
      (finally
        (lsp/stop-all!)
        (delete-tree! root)))))

(use-fixtures :once with-demo-project)

(defn- with-other-source
  "Run `f` with `src/demo/other.clj` holding `source`, restoring the fixed file
  afterwards so tests do not depend on each other's edits."
  [source f]
  (let [file (io/file (:cwd *ctx*) "src" "demo" "other.clj")]
    (spit file source)
    (try
      (f)
      (finally
        (spit file demo-other-fixed)))))

(defn- call
  "Invoke the `id` tool from the built-in tool vector with `args` and [[*ctx*]]."
  [id args]
  ((get-in (into {} (map (juxt :id identity)) (builtin-lsp/tools)) [id :handler])
   args
   *ctx*))

;; ---------------------------------------------------------------------------
;; Pure helpers
;; ---------------------------------------------------------------------------

(deftest json-int-accepts-numbers-and-numeric-strings
  (is (= 3 (#'builtin-lsp/json-int 3)))
  (is (= 12 (#'builtin-lsp/json-int " 12 ")))
  (is (nil? (#'builtin-lsp/json-int "twelve")))
  (is (nil? (#'builtin-lsp/json-int nil)))
  (is (nil? (#'builtin-lsp/json-int {:line 3}))))

(deftest split-symbol-separates-a-namespace
  (is (= ["clai.util" "error-message"] (#'builtin-lsp/split-symbol "clai.util/error-message")))
  (is (= [nil "error-message"] (#'builtin-lsp/split-symbol "error-message")))
  (is (= ["clai.util" "error-message"] (#'builtin-lsp/split-symbol " clai.util/error-message "))))

(deftest identifier-index-picks-the-name-on-a-line
  (is (= 8 (#'builtin-lsp/identifier-index "  (defn error-message" "error-message")))
  (is (= 8 (#'builtin-lsp/identifier-index "  (defn error-message" nil))
      "a declaration form is skipped so the var name wins")
  (is (= 16 (#'builtin-lsp/identifier-index "(defn ^:private foo [x] foo)" nil))
      "metadata is skipped too")
  (is (= 3 (#'builtin-lsp/identifier-index "  (foo 1)" nil)))
  (is (zero? (#'builtin-lsp/identifier-index "  " nil)))
  (is (zero? (#'builtin-lsp/identifier-index "  (defn foo" "bar"))
      "a name that is not on the line falls back to the start"))

(deftest relative-path-strips-the-project-root
  (is (= "src/a.clj" (#'builtin-lsp/relative-path "/proj" "/proj/src/a.clj")))
  (is (= "/other/a.clj" (#'builtin-lsp/relative-path "/proj" "/other/a.clj"))))

(deftest excerpt-stops-at-the-next-top-level-form
  (let [file (temp-dir)]
    (try
      (let [source (io/file file "x.clj")]
        (spit source (str ";; A comment\n"
                          "(defn foo\n"
                          "  \"Doc.\"\n"
                          "  [x]\n"
                          "  ;; an inner comment at column 0 is unusual but kept\n"
                          "  x)\n"
                          "\n"
                          ";; Next section\n"
                          "(defn bar [] 1)\n"))
        (is (= "(defn foo\n  \"Doc.\"\n  [x]\n  ;; an inner comment at column 0 is unusual but kept\n  x)"
               (#'builtin-lsp/excerpt (.getAbsolutePath source) 1)))
        (is (= "(defn bar [] 1)"
               (#'builtin-lsp/excerpt (.getAbsolutePath source) 9)))
        (is (str/ends-with? (#'builtin-lsp/excerpt (.getAbsolutePath source) 1) "x)")
            "no trailing blank lines"))
      (finally
        (doseq [f (reverse (file-seq file))] (.delete f))))))

(deftest outline-lines-renders-nested-symbols
  (let [symbols [{:name "demo.core" :kind 3
                  :range {:start {:line 0 :character 0} :end {:line 9 :character 0}}
                  :children [{:name "greet" :kind 12
                              :range {:start {:line 2 :character 0} :end {:line 5 :character 0}}}]}]]
    (is (= ["namespace  demo.core  :1" "  function  greet  :3"]
           (#'builtin-lsp/outline-lines symbols 0)))))

(deftest diagnostic-text-renders-severity-and-code
  (is (= "src/a.clj:3:5  warning  unused binding x  [unused-binding]"
         (#'builtin-lsp/diagnostic-text
          "src/a.clj"
          {:range {:start {:line 2 :character 4} :end {:line 2 :character 5}}
           :severity 2
           :message "unused\n  binding x"
           :code "unused-binding"}))))

(deftest symbol-text-renders-a-symbol-information
  (let [root (temp-dir)]
    (try
      (spit (io/file root "core.clj") "(ns demo.core)\n\n(defn greet [])\n")
      (let [symbol {:name "greet" :kind 12
                    :location {:uri (lsp/path->uri (io/file root "core.clj"))
                               :range {:start {:line 2 :character 0}
                                       :end {:line 2 :character 18}}}}]
        (is (= "core.clj:3:1  function  greet (demo.core)"
               (#'builtin-lsp/symbol-text (.getAbsolutePath root) symbol))))
      (finally
        (doseq [f (reverse (file-seq (io/file root)))] (.delete f))))))

(deftest position-args-are-described-to-the-model
  (doseq [tool (builtin-lsp/tools)]
    (is (map? (:spec tool)))
    (is (= (:id tool) (get-in tool [:spec :function :name])))
    (is (= "object" (get-in tool [:spec :function :parameters :type]))))
  (is (= #{"lsp_definition" "lsp_references" "lsp_hover" "lsp_symbols"
           "lsp_outline" "lsp_diagnostics"}
         (set (map :id (builtin-lsp/tools))))))

;; ---------------------------------------------------------------------------
;; End-to-end, against a live clojure-lsp (skipped when it is not installed)
;; ---------------------------------------------------------------------------

(deftest definition-finds-a-var-by-symbol
  (when *ctx*
    (let [result (call "lsp_definition" {:symbol "demo.core/greet"})]
      (is (str/includes? result "src/demo/core.clj:3:7"))
      (is (str/includes? result "(defn greet")
          "the answer includes the source, so no extra read is needed"))))

(deftest definition-accepts-a-file-and-line
  (when *ctx*
    (let [result (call "lsp_definition" {:file "src/demo/core.clj" :line 10})]
      (is (str/includes? result "src/demo/core.clj:3:7")
          "line 10 calls `greet`, so that is the definition found"))))

(deftest definition-reports-a-symbol-outside-the-project
  (when *ctx*
    (is (str/starts-with? (call "lsp_definition" {:symbol "no-such-var-anywhere"})
                          "No definition found")
        "a missing symbol is explained rather than reported as a tool failure")))

(deftest references-lists-usages
  (when *ctx*
    (let [result (call "lsp_references" {:symbol "demo.core/greet"})]
      (is (str/includes? result "2 references"))
      (is (str/includes? result "src/demo/core.clj:10:4"))
      (is (str/includes? result "src/demo/other.clj:")
          "the usage in the other namespace is found too"))))

(deftest hover-shows-the-docstring
  (when *ctx*
    (let [result (call "lsp_hover" {:symbol "demo.core/greet"})]
      (is (str/includes? result "(demo.core/greet"))
      (is (str/includes? result "Say hello to")))))

(deftest symbols-searches-var-names
  (when *ctx*
    (let [result (call "lsp_symbols" {:query "gree"})]
      (is (str/includes? result "1 symbol matching 'gree'"))
      (is (str/includes? result "function  greet (demo.core)")))))

(deftest outline-lists-the-vars-of-a-file
  (when *ctx*
    (let [result (call "lsp_outline" {:file "src/demo/core.clj"})]
      (is (str/includes? result "namespace  demo.core  :1"))
      (is (str/includes? result "function  greet  :3"))
      (is (str/includes? result "function  run  :8")))))

(deftest diagnostics-reports-findings-for-a-file
  (when *ctx*
    (with-other-source
      demo-other-broken
      (fn []
        (let [result (call "lsp_diagnostics" {:file "src/demo/other.clj"})]
          (is (str/includes? result "unresolved-namespace")
              "the missing require is reported")
          (is (str/includes? result "src/demo/other.clj:6:4")))))))

(deftest diagnostics-sees-edits-made-after-analysis
  (when *ctx*
    (with-other-source
      demo-other-broken
      (fn []
        (is (str/includes? (call "lsp_diagnostics" {:file "src/demo/other.clj"})
                           "unresolved-namespace"))
        (spit (io/file (:cwd *ctx*) "src" "demo" "other.clj") demo-other-fixed)
        (is (not (str/includes? (call "lsp_diagnostics" {:file "src/demo/other.clj"})
                                "unresolved-namespace"))
            "the file is re-analyzed from disk, so a fix is visible immediately")
        (let [result (call "lsp_references" {:symbol "demo.core/greet"})]
          (is (str/includes? result "src/demo/other.clj:7:20")
              "queries after an edit see the new line numbers"))))))

(deftest diagnostics-reports-the-whole-project
  (when *ctx*
    (with-other-source
      demo-other-broken
      (fn []
        (let [result (call "lsp_diagnostics" {})]
          (is (str/includes? result "src/demo/other.clj"))
          (is (str/includes? result "unresolved-namespace")))))))

(deftest tools-explain-a-missing-clojure-lsp
  (let [root (.toFile (Files/createTempDirectory "clai-lsp-missing"
                                                 (make-array FileAttribute 0)))
        ctx  {:cwd (.getAbsolutePath root)
              :lsp {:executable "/definitely/not/here/clojure-lsp"}}]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not found at"
           ((get-in (into {} (map (juxt :id identity)) (builtin-lsp/tools))
                    ["lsp_definition" :handler])
            {:symbol "demo.core/greet"}
            ctx)))
      (finally
        (.delete root)))))
