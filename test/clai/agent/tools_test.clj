(ns clai.agent.tools-test
  "Tests for the immutable, per-agent tool registry."
  (:require [clai.agent.tools :as tools]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(defn- tool
  "Build a minimal tool named `id` whose implementation returns `result`."
  [id result]
  {:id      id
   :handler (fn [_args _ctx] result)
   :spec    {:type "function"
             :function {:name id
                        :parameters {:type "object"}}}})

(deftest make-registry-builds-by-id
  (let [registry (tools/make-registry [(tool "a" "A") (tool "b" "B")])]
    (is (= #{"a" "b"} (set (tools/tool-ids registry))))
    (is (= "a" (get-in registry ["a" :id])))
    (is (= "b" (get-in registry ["b" :id])))))

(deftest register-and-unregister-are-immutable
  (let [base     (tools/make-registry [(tool "a" "A")])
        added    (tools/register-tool base (tool "b" "B"))
        removed  (tools/unregister-tool added "a")]
    (is (= #{"a"} (set (tools/tool-ids base)))
        "registering must not mutate the original registry")
    (is (= #{"a" "b"} (set (tools/tool-ids added))))
    (is (= #{"b"} (set (tools/tool-ids removed))))
    (is (= #{"a" "b"} (set (tools/tool-ids added)))
        "unregistering must not mutate the original registry")))

(deftest select-tools-narrows-a-registry
  (let [registry (tools/make-registry [(tool "a" "A")
                                       (tool "b" "B")
                                       (tool "c" "C")])]
    (is (= #{"a" "b" "c"} (set (tools/tool-ids (tools/select-tools registry nil)))))
    (is (= #{"b" "a"} (set (tools/tool-ids (tools/select-tools registry ["b" "a"])))))
    (is (= #{} (set (tools/tool-ids (tools/select-tools registry [])))))
    (is (= #{"b"} (set (tools/tool-ids (tools/select-tools registry ["b" "missing"])))))))

(deftest specs-for-preserves-requested-order
  (let [registry (tools/make-registry [(tool "a" "A")
                                       (tool "b" "B")
                                       (tool "c" "C")])]
    (is (= ["c" "a"]
           (mapv #(get-in % [:function :name])
                 (tools/specs-for registry ["c" "missing" "a"]))))))

(deftest invoke-uses-the-supplied-registry
  (let [registry-a (tools/make-registry [(tool "same" "from-a")])
        registry-b (tools/make-registry [(tool "same" "from-b")])]
    (is (= "from-a" (tools/invoke registry-a "same" {} {})))
    (is (= "from-b" (tools/invoke registry-b "same" {} {})))
    (is (= "Error: unknown tool 'same'"
           (tools/invoke (tools/make-registry []) "same" {} {}))
        "a tool in another agent's registry is not visible")))

(deftest execute-calls-uses-the-supplied-registry
  (let [registry (tools/make-registry [(tool "echo" "ok")])
        calls    [{:id "1"
                   :function {:name "echo"
                              :arguments "{\"text\":\"hi\"}"}}]]
    (is (= [{:role "tool" :content "ok" :name "echo" :tool_call_id "1"}]
           (tools/execute-calls registry calls {})))))
