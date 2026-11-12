(ns clai.shell.help-test
  "Tests for the REPL help command in `clai.shell.help`."
  (:require [clai.shell.cli :as cli]
            [clai.shell.git :as git]
            [clai.shell.help :as help :refer [help help!]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- in-test-ns
  "Call `f` with [[*ns*]] bound to this test namespace, so runtime symbol
  lookups (as `help*` does for quoted symbols and strings) see its aliases."
  [f]
  (binding [*ns* (the-ns 'clai.shell.help-test)]
    (f)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- documented
  "A documented, multi-arity function with an malli schema, used to check the
  rendered output."
  ([a] (documented a {}))
  ([a opts] [a opts]))

(alter-meta! #'documented
             merge
             {:doc     "Do the thing with `a`.\n\nOptions include `:quiet?`."
              :arglists '([a] [a opts])
              :malli/schema [:=> [:cat :any [:? :map]] :any]})

(declare unbound-helper)

(defn- private-helper
  "A private helper; `help` can still describe it."
  [x]
  x)

;; ---------------------------------------------------------------------------
;; help-text
;; ---------------------------------------------------------------------------

(deftest help-text-renders-name-kind-signatures-and-docstring
  (let [text (help/help-text #'documented)]
    (is (str/starts-with? text "clai.shell.help-test/documented (function)"))
    (is (str/includes? text "\n  (documented a)\n"))
    (is (str/includes? text "\n  (documented a opts)\n"))
    (is (str/includes? text "\n  Schema: [:=> [:cat :any [:? :map]] :any]\n"))
    (is (str/includes? text "\n  Do the thing with `a`."))
    (is (str/includes? text "\n\n  Options include `:quiet?`."))))

(deftest help-text-indents-every-docstring-line
  (let [lines (str/split-lines (help/help-text #'documented))]
    (is (some #(= "  Do the thing with `a`." %) lines))
    (is (some #(= "" %) lines))
    (is (some #(= "  Options include `:quiet?`." %) lines))))

(deftest help-text-reports-a-dynamic-var
  (is (str/includes? (help/help-text #'cli/*cwd*) "(dynamic var)")))

(deftest help-text-omits-an-absent-docstring
  (is (= "clai.shell.help-test/unbound-helper (var)"
         (help/help-text #'unbound-helper))))

(deftest help-text-resolves-symbols-and-strings-in-the-current-namespace
  (in-test-ns
    (fn []
      (is (= (help/help-text #'git/commit)
             (help/help-text 'git/commit)))
      (is (= (help/help-text #'git/commit)
             (help/help-text "git/commit"))))))

(deftest help-text-describes-a-namespace
  (let [text (help/help-text 'clai.shell.git)]
    (is (str/starts-with? text "clai.shell.git (namespace)"))
    (is (str/includes? text "REPL-friendly helpers for driving the `git` CLI."))
    (is (str/includes? text "\n\nPublic vars:\n"))
    (is (some #(= "  commit" %) (str/split-lines text)))))

(deftest help-text-lists-public-vars-without-their-docstrings
  (let [text (help/help-text 'clai.shell.git)]
    (is (str/includes? text "\n  commit\n"))
    (is (not (str/includes? text "Commit staged changes")))))

(deftest help-text-describes-a-namespace-object
  (is (= (help/help-text 'clai.shell.git)
         (help/help-text (the-ns 'clai.shell.git)))))

(deftest help-text-reports-an-unknown-target
  (in-test-ns
    (fn []
      (is (= "No Var or namespace found for no-such-var."
             (help/help-text 'no-such-var)))
      (is (= "No Var or namespace found for clai.shell.no-such-ns."
             (help/help-text 'clai.shell.no-such-ns))))))

(deftest help-text-uses-the-fully-qualified-name-behind-an-alias
  (is (str/starts-with? (help/help-text #'git/commit)
                        "clai.shell.git/commit (function)")))

;; ---------------------------------------------------------------------------
;; The help macro
;; ---------------------------------------------------------------------------

(deftest help-does-not-need-a-quoted-symbol
  (let [out (with-out-str (help git/commit))]
    (is (str/starts-with? out "clai.shell.git/commit (function)\n"))
    (is (str/includes? out "  (commit message)\n"))
    (is (str/includes? out "Commit staged changes with `message`."))))

(deftest help-does-not-evaluate-its-argument
  ;; `unbound-helper` has no value; reading it as a value would throw.
  (let [out (with-out-str (help unbound-helper))]
    (is (str/starts-with? out "clai.shell.help-test/unbound-helper (var)"))))

(deftest help-accepts-quoted-symbols-strings-and-vars
  (in-test-ns
    (fn []
      (let [expected (with-out-str (help git/commit))]
        (is (= expected (with-out-str (help 'git/commit))))
        (is (= expected (with-out-str (help "git/commit"))))
        (is (= expected (with-out-str (help #'git/commit))))))))

(deftest help-prints-a-message-for-an-unknown-symbol
  (is (= "No Var or namespace found for definitely-not-a-var.\n"
         (with-out-str (help definitely-not-a-var)))))

(deftest help-prints-namespace-documentation
  (let [out (with-out-str (help clai.shell.git))]
    (is (str/starts-with? out "clai.shell.git (namespace)\n"))
    (is (str/includes? out "REPL-friendly helpers for driving the `git` CLI."))))

(deftest help-with-no-argument-describes-the-current-namespace
  (in-test-ns
    (fn []
      (let [out (with-out-str (help!))]
        (is (str/starts-with? out "clai.shell.help-test (namespace)\n"))
        (is (str/includes? out "Public vars:\n"))
        (is (str/includes? out "\n  unbound-helper\n"))))))

(deftest help-with-no-argument-describes-itself
  (let [out (with-out-str (help))]
    (is (str/starts-with? out "clai.shell.help/help (macro)\n"))
    (is (str/includes? out "  (help)\n"))
    (is (str/includes? out "  (help sym)\n"))
    (is (str/includes? out "Print the documentation and call signatures"))))

(deftest help-describes-a-private-var
  (is (str/starts-with? (with-out-str (help private-helper))
                        "clai.shell.help-test/private-helper (function)")))

(deftest help-returns-nil
  (let [sink (java.io.StringWriter.)]
    (is (nil? (binding [*out* sink] (help git/commit))))
    (is (nil? (binding [*out* sink] (help/help* #'git/commit))))))
