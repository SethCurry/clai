(ns clai.agent.tools-test
  "Tests for the immutable, per-agent tool registry."
  (:require [clai.agent.tools.invoke :as invoke]
            [clai.agent.tools.registry :as registry]
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
  (let [registry (registry/make-registry [(tool "a" "A") (tool "b" "B")])]
    (is (= #{"a" "b"} (set (registry/tool-ids registry))))
    (is (= "a" (get-in registry ["a" :id])))
    (is (= "b" (get-in registry ["b" :id])))))

(deftest register-and-unregister-are-immutable
  (let [base     (registry/make-registry [(tool "a" "A")])
        added    (registry/register-tool base (tool "b" "B"))
        removed  (registry/unregister-tool added "a")]
    (is (= #{"a"} (set (registry/tool-ids base)))
        "registering must not mutate the original registry")
    (is (= #{"a" "b"} (set (registry/tool-ids added))))
    (is (= #{"b"} (set (registry/tool-ids removed))))
    (is (= #{"a" "b"} (set (registry/tool-ids added)))
        "unregistering must not mutate the original registry")))

(deftest select-tools-narrows-a-registry
  (let [registry (registry/make-registry [(tool "a" "A")
                                           (tool "b" "B")
                                           (tool "c" "C")])]
    (is (= #{"a" "b" "c"} (set (registry/tool-ids (registry/select-tools registry nil)))))
    (is (= #{"b" "a"} (set (registry/tool-ids (registry/select-tools registry ["b" "a"])))))
    (is (= #{} (set (registry/tool-ids (registry/select-tools registry [])))))
    (is (= #{"b"} (set (registry/tool-ids (registry/select-tools registry ["b" "missing"])))))))

(deftest specs-for-preserves-requested-order
  (let [registry (registry/make-registry [(tool "a" "A")
                                           (tool "b" "B")
                                           (tool "c" "C")])]
    (is (= ["c" "a"]
           (mapv #(get-in % [:function :name])
                 (registry/specs-for registry ["c" "missing" "a"]))))))

(deftest invoke-uses-the-supplied-registry
  (let [registry-a (registry/make-registry [(tool "same" "from-a")])
        registry-b (registry/make-registry [(tool "same" "from-b")])]
    (is (= "from-a" (invoke/invoke registry-a "same" {} {})))
    (is (= "from-b" (invoke/invoke registry-b "same" {} {})))
    (is (= "Error: unknown tool 'same'"
           (invoke/invoke (registry/make-registry []) "same" {} {}))
        "a tool in another agent's registry is not visible")))

(deftest execute-calls-uses-the-supplied-registry
  (let [registry (registry/make-registry [(tool "echo" "ok")])
        calls    [{:id "1"
                   :function {:name "echo"
                              :arguments "{\"text\":\"hi\"}"}}]]
    (is (= [{:role "tool" :content "ok" :name "echo" :tool_call_id "1"}]
           (invoke/execute-calls registry calls {})))))
