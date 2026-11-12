(ns clai.daemon.namespaces-test
  "Tests for the Clojure runtime introspection behind the web UI's namespace
  explorer."
  (:require [clai.daemon.namespaces :as namespaces]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(def fixture-ns
  "The namespace the Var tests create, intern their fixtures in, and remove
  afterwards. It is outside `clai.` so instrumentation never picks it up."
  'fixture.namespaces)

(defn- intern-var
  "Intern `sym` in `n` holding `value`, with `meta` merged into the Var's
  metadata the way `defn` would set it."
  [n sym value meta]
  (doto (intern n sym value)
    (alter-meta! merge meta)))

(defn- with-fixture-namespace
  "Run `f` with [[fixture-ns]] created and populated with a documented
  function, a macro, a private Var, a value, and an unbound Var."
  [f]
  (let [n (create-ns fixture-ns)]
    (try
      (alter-meta! n assoc :doc "Fixture namespace.")
      (intern-var n 'add-one (fn [x] (inc x))
                  {:doc "Adds one to x.\n\nMore detail."
                   :arglists '([x])
                   :malli/schema [:-> :int :int]})
      (intern-var n 'unless* (fn [_form _env & _body] nil)
                  {:doc "Inverted when." :arglists '([test & body]) :macro true})
      (intern-var n 'private-helper identity {:private true})
      (intern-var n 'answer 42 {})
      (intern n 'unbound)
      (f)
      (finally
        (remove-ns fixture-ns)))))

(deftest lists-the-loaded-namespaces
  (let [loaded (vec (:namespaces (namespaces/list-namespaces)))
        names  (mapv :name loaded)]
    (is (= names (sort names)) "namespaces are sorted by name")
    (is (some #{"clojure.string"} names))
    (is (some #{"clai.daemon.namespaces"} names))
    (testing "a namespace summary carries its docstring and Var count"
      (let [string-ns (first (filter #(= "clojure.string" (:name %)) loaded))]
        (is (str/starts-with? (:doc string-ns) "Clojure String utilities"))
        (is (pos? (:varCount string-ns)))))))

(deftest lists-one-namespaces-public-vars
  (with-fixture-namespace
    (fn []
      (let [{:keys [namespace doc missing vars]} (namespaces/namespace-vars
                                                  (str fixture-ns))]
        (is (= (str fixture-ns) namespace))
        (is (= "Fixture namespace." doc))
        (is (false? missing))
        (is (= ["add-one" "answer" "unbound" "unless*"]
               (mapv :name vars))
            "public Vars are sorted by name and private ones are left out")
        (is (= {:name     "add-one"
                :doc      "Adds one to x.\n\nMore detail."
                :arglists "[x]"
                :macro    false}
               (first vars)))
        (is (true? (:macro (first (filter #(= "unless*" (:name %)) vars)))))))))

(deftest an-unloaded-namespace-is-reported-as-missing
  (is (= {:namespace "fixture.absent" :doc nil :missing true :vars []}
         (namespaces/namespace-vars "fixture.absent"))))

(deftest describes-one-var
  (with-fixture-namespace
    (fn []
      (testing "a documented function"
        (let [detail (namespaces/var-detail (str fixture-ns) "add-one")]
          (is (= (str fixture-ns) (:namespace detail)))
          (is (= "add-one" (:name detail)))
          (is (= "Adds one to x.\n\nMore detail." (:doc detail)))
          (is (= "[x]" (:arglists detail)))
          (is (= "[:-> :int :int]" (:malliSchema detail)))
          (is (false? (:macro detail)))
          (is (false? (:private detail)))
          (is (nil? (:source detail)) "a Var without a source file has none")))

      (testing "a macro"
        (is (true? (:macro (namespaces/var-detail (str fixture-ns) "unless*")))))

      (testing "a private Var"
        (is (true? (:private (namespaces/var-detail (str fixture-ns)
                                                    "private-helper")))))

      (testing "a value"
        (is (= "42" (:value (namespaces/var-detail (str fixture-ns) "answer")))))

      (testing "an unbound Var has no value"
        (is (nil? (:value (namespaces/var-detail (str fixture-ns) "unbound")))))

      (testing "an unknown Var has no detail"
        (is (nil? (namespaces/var-detail (str fixture-ns) "nope")))))))

(deftest reads-a-vars-source
  (let [detail (namespaces/var-detail "clojure.string" "join")]
    (is (= "clojure/string.clj" (:file detail)))
    (is (pos? (:line detail)))
    (is (str/starts-with? (:source detail) "(defn ^String join"))
    (is (= "[coll] [separator coll]" (:arglists detail)))))
