(ns clai.agent.tools.builtin.eval-test
  "Tests for the built-in `eval_clojure` tool and the agent's REPL namespace
  it evaluates in."
  (:require [clai.agent.tools.builtin.eval :as eval]
            [clai.agent.tools.registry :as registry]
            [clai.agent.tools.schema :as schema]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.home.core :as home]
            [clai.test-support :refer [instrument memory-session-store
                                       quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [malli.core :as m]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)
(use-fixtures :each memory-session-store)

(def ^:private eval-clojure
  "The `eval_clojure` tool handler."
  (-> (eval/tools) first :handler))

(def ^:private one-tool-registry
  "A registry holding a single echo tool, for checking what evaluated code can
  see of its own call."
  (registry/make-registry
   [{:id "echo"
     :handler (fn [args _ctx] (str (:text args)))
     :spec {:type "function"
            :function {:name "echo"
                       :description "Echo `text` back."
                       :parameters {:type "object"
                                    :properties {"text" {:type "string"}}
                                    :required ["text"]}}}}]))

(deftest evaluates-in-the-agents-home-namespace
  (is (= "=> \"clai.home.core\"" (eval-clojure {:code "(str *ns*)"} {}))
      "code without a namespace runs in the agent's REPL"))

(deftest returns-printed-output-before-the-value
  (is (= "hi\n=> 3" (eval-clojure {:code "(do (println \"hi\") (+ 1 2))"} {})))
  (is (= "err\n=> nil" (eval-clojure {:code "(binding [*out* *err*] (println \"err\"))"} {}))
      "stderr is captured with stdout"))

(deftest evaluates-in-a-chosen-namespace
  (is (= "=> \"clojure.string\""
         (eval-clojure {:code "(str *ns*)" :namespace "clojure.string"} {}))))

(deftest reports-unknown-namespaces-instead-of-failing
  (let [result (eval-clojure {:code "(+ 1 2)" :namespace "no.such.namespace"} {})]
    (is (str/includes? result "No such namespace"))
    (is (str/includes? result "namespace"))))

(deftest reports-the-session-it-runs-in
  (let [client  ::client
        session (sessions/create-session client {:cwd "/tmp"})
        id      (:sessionId session)
        ctx     {:cwd "/tmp" :session-id id :postgres client}]
    (sessions/append-history client id {:role "user" :content "hello"})
    (is (= (str "=> \"" id "\"")
           (eval-clojure {:code "(:sessionId (session))"} ctx)))
    (is (= "=> \"/tmp\""
           (eval-clojure {:code "(:cwd (session))"} ctx)))
    (is (= "=> [\"user\"]"
           (eval-clojure {:code "(mapv :role (history))"} ctx)))))

(deftest reports-errors-as-text
  (let [result (eval-clojure {:code "(/ 1 0)"} {})]
    (is (str/starts-with? result "=> Error: Divide by zero"))
    (is (str/includes? result "clai.agent.tools.builtin.eval")
        "the stack trace points at the evaluation")))

(deftest prints-eagerly-infinite-values-finitely
  (let [result (eval-clojure {:code "(range)"} {})]
    (is (str/includes? result "...")
        "*print-length* stops an infinite sequence from hanging the tool")))

(deftest truncates-huge-results
  (let [result (eval-clojure {:code "(apply str (repeat 100000 \"x\"))"} {})]
    (is (str/includes? result "[truncated at"))
    (is (<= (count result) (+ eval/max-result-bytes 100)))))

(deftest binds-the-session-context
  (is (= "=> \"/tmp\"" (eval-clojure {:code "(:cwd (context))"} {:cwd "/tmp"})))
  (is (= "=> {}" (eval-clojure {:code "(context)"} {}))
      "an empty context leaves the helpers empty instead of throwing")
  (is (nil? eval/*context*)
      "the context binding does not leak out of the call"))

(deftest exposes-the-tools-the-agent-can-call
  (is (= "=> [\"echo\"]"
         (eval-clojure {:code "(mapv :id (tools))"}
                       {:tool-registry one-tool-registry})))
  (is (= "=> \"Echo `text` back.\""
         (eval-clojure {:code "(:description (first (tools)))"}
                       {:tool-registry one-tool-registry})))
  (is (= "=> \"echo\""
         (eval-clojure {:code "(-> (tool \"echo\") :function :name)"}
                       {:tool-registry one-tool-registry})))
  (is (= "=> nil" (eval-clojure {:code "(tool \"nope\")"} {}))))

(deftest home-helpers-work-without-a-context
  (is (= [] (home/tools)))
  (is (nil? (home/context)))
  (is (nil? (home/session)))
  (is (nil? (home/history)))
  (is (str/includes? (with-out-str (home/help)) "eval_clojure")))

(deftest home-var-utils-describe-the-running-code
  (let [vars (home/vars 'clai.agent.tools.builtin.eval)]
    (is (some #{:name :doc :arglists :macro} (keys (first vars))))
    (is (some #(= "eval-clojure-fn" (:name %)) vars)))
  (is (= "error-message" (:name (home/var-detail "clai.util" "error-message"))))
  (is (= [] (home/vars 'no.such.namespace))))

(deftest the-tool-spec-is-valid
  (let [spec (:spec (first (eval/tools)))]
    (is (m/validate schema/tool-spec spec))
    (is (str/includes? (-> spec :function :description) "(tools)")
        "the description tells the model how to discover its tools")
    (is (str/includes? (-> spec :function :description) "(def ")
        "the description tells the model to save values with def, which the
        user's REPL in the same namespace can then reach")))
