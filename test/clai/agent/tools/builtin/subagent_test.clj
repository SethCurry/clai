(ns clai.agent.tools.builtin.subagent-test
  "Tests for the built-in `spawn_agent` tool."
  (:require [clai.agent.providers.ollama :as ollama]
            [clai.agent.tools.builtin :as builtin]
            [clai.agent.tools.builtin.subagent :as subagent]
            [clai.agent.tools.registry :as registry]
            [clai.test-support :refer [quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(defn- tool
  "Build a minimal tool named `id` whose implementation calls `f`."
  [id f]
  {:id      id
   :handler f
   :spec    {:type "function"
             :function {:name id
                        :parameters {:type "object"}}}})

(defn- echo-tool
  []
  (tool "echo" (fn [{:keys [text]} _ctx] (str "echo:" text))))

(defn- spawn-tool
  []
  (tool "spawn_agent" (fn [_args _ctx] "should not run")))

(defn- ctx
  ([registry] (ctx registry 0))
  ([registry depth]
   {:cwd          "/tmp"
    :session-id   "parent-session"
    :client       :fake-client
    :model        "parent-model"
    :tool-registry registry
    :chat-opts    {}
    :max-rounds   4
    :agent-depth  depth}))

(defn- tool-names
  [request]
  (mapv #(get-in % [:function :name]) (:tools request)))

(deftest spawn-agent-is-a-built-in-tool
  (is (contains? (set (registry/tool-ids (builtin/built-in-registry)))
                 "spawn_agent")))

(deftest spawn-agent-runs-with-its-own-system-prompt-and-tools
  (let [requests (atom [])
        calls    (atom 0)
        registry (registry/make-registry [(echo-tool) (spawn-tool)])]
    (with-redefs [ollama/chat
                  (fn [_client request]
                    (swap! requests conj request)
                    (if (= 1 (swap! calls inc))
                      {:message {:role    "assistant"
                                 :content ""
                                 :tool_calls
                                 [{:id "call-1"
                                   :function
                                   {:name "echo"
                                    :arguments "{\"text\":\"hi\"}"}}]}}
                      {:message {:role "assistant" :content "final reply"}}))]
      (let [result (subagent/spawn-agent-fn
                    {:prompt        "do the task"
                     :system_prompt "be terse"
                     :tools         ["echo"]
                     :model         "child-model"
                     :max_rounds    3}
                    (ctx registry))]
        (is (= "final reply" result))
        (is (= 2 (count @requests)))
        (is (= "child-model" (:model (first @requests))))
        (is (= [{:role "system" :content "be terse"}
                {:role "user" :content "do the task"}]
               (:messages (first @requests)))
            "the child gets its own system prompt and only the task")
        (is (= ["echo"] (tool-names (first @requests)))
            "only the requested child tools are offered")
        (is (= "echo:hi"
               (->> (:messages (second @requests))
                    (filter #(= "tool" (:role %)))
                    first
                    :content))
            "the child executes its tools against its own registry")))))

(deftest spawn-agent-withholds-itself-by-default
  (let [requests (atom [])
        registry (registry/make-registry [(echo-tool) (spawn-tool)])]
    (with-redefs [ollama/chat
                  (fn [_client request]
                    (swap! requests conj request)
                    {:message {:role "assistant" :content "ok"}})]
      (is (= "ok" (subagent/spawn-agent-fn {:prompt "x"} (ctx registry))))
      (is (= ["echo"] (tool-names (first @requests)))
          "spawn_agent must not be offered to the child unless requested"))))

(deftest spawn-agent-can-be-explicitly-nested
  (let [requests (atom [])
        registry (registry/make-registry [(echo-tool) (spawn-tool)])]
    (with-redefs [ollama/chat
                  (fn [_client request]
                    (swap! requests conj request)
                    {:message {:role "assistant" :content "ok"}})]
      (subagent/spawn-agent-fn
       {:prompt "x" :tools ["spawn_agent"]}
       (ctx registry))
      (is (= ["spawn_agent"] (tool-names (first @requests)))))))

(deftest spawn-agent-enforces-depth-limit
  (let [registry (registry/make-registry [(echo-tool)])]
    (is (str/starts-with?
         (subagent/spawn-agent-fn
          {:prompt "too deep"}
          (ctx registry subagent/default-max-depth))
         "Error:"))))

(deftest spawn-agent-validates-required-inputs
  (let [registry (registry/make-registry [(echo-tool)])]
    (is (str/starts-with?
         (subagent/spawn-agent-fn {:prompt "   "} (ctx registry))
         "Error:"))
    (is (str/starts-with?
         (subagent/spawn-agent-fn {:prompt "x"}
                                  (assoc (ctx registry) :client nil))
         "Error:"))))

(deftest spawn-agent-clamps-max-rounds
  (let [requests (atom [])
        registry (registry/make-registry [(echo-tool)])]
    (with-redefs [ollama/chat
                  (fn [_client request]
                    (swap! requests conj request)
                    {:message {:role    "assistant"
                               :content ""
                               :tool_calls
                               [{:id "call-1"
                                 :function
                                 {:name "echo"
                                  :arguments "{\"text\":\"hi\"}"}}]}})]
      (let [result (subagent/spawn-agent-fn
                    {:prompt "loop" :tools ["echo"] :max_rounds 1000}
                    (ctx registry))]
        (is (str/starts-with? result "Error:"))
        (is (= subagent/max-rounds-limit (count @requests)))))))
