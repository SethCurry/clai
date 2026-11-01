(ns clai.agent-test
  "Tests for the ACP agent handler in `clai.agent`, in particular that a
  `session/prompt` actually reaches the model and that the reply validates as
  an ACP `session/update`."
  (:require [acp.agent :as acp]
            [acp.schema.core :as schemas]
            [clai.agent :as agent]
            [clai.providers.ollama :as ollama]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(def ^:private test-client
  (ollama/make-client {:host "http://localhost:11434" :timeout 1000}))

;; ---------------------------------------------------------------------------
;; session/prompt end to end (Ollama stubbed)
;; ---------------------------------------------------------------------------

(defn- run-with-stubbed-ollama
  "Run `f` with `/api/chat` and the ACP senders stubbed. `f` receives the
  handler plus atoms collecting the chat request bodies (`requests`) and the
  `[sessionId update]` pairs sent to the client (`updates`)."
  [reply f]
  (let [requests (atom [])
        updates  (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:message {:role "assistant" :content reply}})
                  acp/send-session-update
                  (fn [_conn sid update]
                    ;; Validate exactly as the real sender would, so a
                    ;; malformed update fails the test.
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))]
      (let [handler (agent/ollama-agent {:cfg    {}
                                         :client test-client
                                         :model  "stub-model"})]
        (f handler requests updates)))))

(deftest session-prompt-sends-user-text-to-ollama
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests updates]
     (let [{:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})
           resp                (acp/handle-session-prompt
                                handler nil
                                {:sessionId sessionId
                                 :prompt    [{:type "text" :text "ping"}]})]
       (is (= {:stopReason "end_turn"} resp))
       (is (= 1 (count @requests)))
       (is (= [{:role "user" :content "ping"}]
              (:messages (first @requests)))
           "the prompt text must be in the request sent to Ollama")
       (is (= "stub-model" (:model (first @requests))))
       (is (= 1 (count @updates)))
       (let [[sid update] (first @updates)]
         (is (= sessionId sid))
         (is (= "agent_message_chunk" (:sessionUpdate update)))
         (is (= "pong" (get-in update [:content :text]))))))))

(deftest session-prompt-accumulates-history
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests _updates]
     (let [{:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})]
       (acp/handle-session-prompt handler nil
                                  {:sessionId sessionId
                                   :prompt    [{:type "text" :text "first"}]})
       (acp/handle-session-prompt handler nil
                                  {:sessionId sessionId
                                   :prompt    [{:type "text" :text "second"}]})
       (is (= [{:role "user" :content "first"}
               {:role "assistant" :content "pong"}
               {:role "user" :content "second"}]
              (:messages (second @requests))))))))

(deftest session-prompt-uses-the-agents-own-tool-registry
  (let [requests (atom [])
        updates  (atom [])
        calls    (atom 0)
        echo     {:id   "echo"
                  :fn   (fn [{:keys [text]} _ctx] (str "echo:" text))
                  :spec {:type "function"
                         :function {:name "echo"
                                    :parameters {:type "object"}}}}
        other    {:id   "other"
                  :fn   (fn [_args _ctx] "other")
                  :spec {:type "function"
                         :function {:name "other"
                                    :parameters {:type "object"}}}}]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    (if (= 1 (swap! calls inc))
                      {:message {:role "assistant"
                                 :content ""
                                 :tool_calls
                                 [{:id "call-1"
                                   :function {:name "echo"
                                              :arguments "{\"text\":\"hi\"}"}}]}}
                      {:message {:role "assistant" :content "done"}}))
                  acp/send-session-update
                  (fn [_conn sid update]
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))]
      (let [handler (agent/ollama-agent {:cfg           {:tools ["echo"]}
                                         :client        test-client
                                         :model         "stub-model"
                                         :tool-registry [echo other]})
            {:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})
            resp (acp/handle-session-prompt
                  handler nil
                  {:sessionId sessionId
                   :prompt    [{:type "text" :text "ping"}]})]
        (is (= {:stopReason "end_turn"} resp))
        (is (= 2 (count @requests)))
        (is (= ["echo"]
               (mapv #(get-in % [:function :name])
                     (:tools (first @requests))))
            "config :tools must narrow the agent's own registry")
        (is (= "echo:hi"
               (->> (:messages (second @requests))
                    (filter #(= "tool" (:role %)))
                    first
                    :content))
            "the tool call must execute against the same per-agent registry")
        (is (= "done" (get-in (last @updates) [1 :content :text])))))))

(deftest session-cancel-does-not-throw
  (run-with-stubbed-ollama
   "pong"
   (fn [handler _requests _updates]
     (let [{:keys [sessionId]} (acp/handle-session-new handler nil {:cwd "/tmp"})]
       (is (nil? (acp/on-session-cancel handler nil {:sessionId sessionId})))))))

(deftest prompt-for-unknown-session-relays-message
  (run-with-stubbed-ollama
   "pong"
   (fn [handler requests updates]
     (let [resp (acp/handle-session-prompt
                 handler nil
                 {:sessionId "missing"
                  :prompt    [{:type "text" :text "ping"}]})]
       (is (= {:stopReason "end_turn"} resp))
       (is (empty? @requests))
       (let [[sid update] (first @updates)]
         (is (= ["missing"] [sid]))
         (is (= "agent_message_chunk" (:sessionUpdate update)))
         (is (= "No session missing" (get-in update [:content :text]))))))))
