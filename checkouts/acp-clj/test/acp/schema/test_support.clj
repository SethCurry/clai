(ns acp.schema.test-support
  "Assertion helpers shared by the `acp.schema.*` tests."
  (:require [clojure.test :as test]
            [malli.core :as m]))


(defn returned
  "Return the map produced by calling `f`, or the `ExceptionInfo` it threw."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      e)))


(defn accepts
  "Assert that `schema` accepts every value in `values`."
  [schema values]
  (doseq [value values]
    (test/is (m/validate schema value)
             (str "expected " (pr-str value) " to be accepted"))))


(defn rejects
  "Assert that `schema` rejects every value in `values`."
  [schema values]
  (doseq [value values]
    (test/is (not (m/validate schema value))
             (str "expected " (pr-str value) " to be rejected"))))


(defn builds
  "Assert that `ctor` called with `args` returns `expected` and that the
  result satisfies `schema`."
  [schema ctor args expected]
  (let [value (apply ctor args)]
    (test/is (= expected value)
             (str "unexpected value: " (pr-str value)))
    (test/is (m/validate schema value)
             (str "expected " (pr-str value) " to satisfy the schema"))))


(defn ctor-rejects
  "Assert that `ctor` rejects every argument list, either by arity or by
  throwing an `ExceptionInfo` for an invalid value."
  [ctor arglists]
  (doseq [args arglists]
    (test/is (thrown? Exception (apply ctor args))
             (str "expected " (pr-str args) " to be rejected"))))


(defn ex-data-of
  "Ex-data of the `ExceptionInfo` thrown by `f`."
  [f]
  (let [e (returned f)]
    (test/is (instance? clojure.lang.ExceptionInfo e))
    (ex-data e)))
