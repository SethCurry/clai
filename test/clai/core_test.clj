(ns clai.core-test
  "Tests for the ACP <-> Ollama glue in `clai.core`, in particular that a
  `session/prompt` actually reaches the model and that the reply validates as
  an ACP `session/update`."
  (:require [acp.agent :as acp]
            [acp.schema.core :as schemas]
            [clai.core :as core]
            [clai.providers.ollama :as ollama]
            [clai.test-support :refer [quiet-logs]]
            [clai.tools :as tools]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(def ^:private prompt->messages
  "The (private) ACP prompt normalizer."
  #'core/acp-prompt->ollama-messages)

(def ^:private test-client
  (ollama/make-client {:host "http://localhost:11434" :timeout 1000}))

;; ---------------------------------------------------------------------------
;; Prompt normalization
;; ---------------------------------------------------------------------------

(deftest content-block-prompt-becomes-user-message
  ;; ACP sends `:prompt` as a vector of ContentBlocks; their text must survive
  ;; the conversion (regression: it used to be dropped, sending an empty
  ;; user message and prompting the model to report "no request").
  (is (= [{:role "user" :content "hello"}]
         (prompt->messages [{:type "text" :text "hello"}])))
  (is (= [{:role "user" :content "hello\nworld"}]
         (prompt->messages [{:type "text" :text "hello"}
                            {:type "text" :text "world"}])))
  (is (= [{:role "user" :content "look"}]
         (prompt->messages [{:type "text" :text "look"}
                            {:type "image" :data "..."}]))))

(deftest tolerant-prompt-shapes
  (is (= [] (prompt->messages nil)))
  (is (= [{:role "user" :content "hi"}] (prompt->messages "hi")))
  (is (= [{:role "user" :content "hi"}]
         (prompt->messages {:type "text" :text "hi"})))
  ;; role-tagged messages keep their role
  (is (= [{:role "user" :content "hi"}]
         (prompt->messages [{:role "user"
                             :content [{:type "text" :text "hi"}]}])))
  (is (= [{:role "assistant" :content "yo"}]
         (prompt->messages [{:role "assistant" :content "yo"}]))))

;; ---------------------------------------------------------------------------
;; session/prompt end to end (Ollama stubbed)
;; ---------------------------------------------------------------------------

(defn- run-with-stubbed-ollama
  "Run `f` with `/api/chat` and the ACP senders stubbed. `f` receives the
  agent plus atoms collecting the chat request bodies (`requests`) and the
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
      (let [agent (core/ollama-agent {:cfg    {}
                                      :client test-client
                                      :model  "stub-model"})]
        (f agent requests updates)))))

(deftest session-prompt-sends-user-text-to-ollama
  (run-with-stubbed-ollama
   "pong"
   (fn [agent requests updates]
     (let [{:keys [sessionId]} (acp/handle-session-new agent nil {:cwd "/tmp"})
           resp                (acp/handle-session-prompt
                                agent nil
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

(deftest tool-call-output-is-sent-as-tool-call-content
  ;; Regression: the result of a tool call used to be sent only in
  ;; `:rawOutput`, which ACP clients do not render, so the client showed the
  ;; `tool_call` announcement but never its output. The result must also be
  ;; present as `ToolCallContent` (a text content block) on both the finished
  ;; `tool_call` and the `tool_call_update`.
  (let [updates (atom [])
        replies (atom [{:message {:role       "assistant"
                                  :content    ""
                                  :tool_calls [{:id       "call-1"
                                                :function {:name      "read_file"
                                                           :arguments "{\"path\":\"x\"}"}}]}}
                       {:message {:role "assistant" :content "done"}}])]
    (with-redefs [ollama/chat
                  (fn [_client _req] (let [r (first @replies)]
                                       (swap! replies rest)
                                       r))
                  tools/invoke
                  (fn [_id _args _ctx] "hello tool output")
                  acp/send-session-update
                  (fn [_conn sid update]
                    (schemas/->SessionUpdate update)
                    (swap! updates conj [sid update]))]
      (let [agent (core/ollama-agent {:cfg    {}
                                      :client test-client
                                      :model  "stub-model"})
            {:keys [sessionId]} (acp/handle-session-new agent nil {:cwd "/tmp"})]
        (acp/handle-session-prompt agent nil
                                   {:sessionId sessionId
                                    :prompt    [{:type "text" :text "go"}]})
        (let [tool-updates (filter #(str/starts-with?
                                     (get-in % [1 :sessionUpdate] "")
                                     "tool_call")
                                   @updates)
              [started finished update] (map second tool-updates)]
          (is (= ["tool_call" "tool_call" "tool_call_update"]
                 (map :sessionUpdate [started finished update])))
          (is (= "in_progress" (:status started)))
          (is (= "completed" (:status finished))
              "the finished tool call is sent as a `tool_call` with a terminal status")
          (is (= (:toolCallId started) (:toolCallId finished)))
          (is (= "read_file" (:name finished)))
          (is (= "completed" (:status update)))
          (is (= [{:type "content"
                   :content {:type "text" :text "hello tool output"}}]
                 (:content finished))
              "tool output must be visible to ACP clients via :content")
          (is (= (:content finished) (:content update)))
          (is (= "hello tool output"
                 (get-in (first (:content finished)) [:content :text]))))))))

(deftest session-prompt-accumulates-history
  (run-with-stubbed-ollama
   "pong"
   (fn [agent requests _updates]
     (let [{:keys [sessionId]} (acp/handle-session-new agent nil {:cwd "/tmp"})]
       (acp/handle-session-prompt agent nil
                                  {:sessionId sessionId
                                   :prompt    [{:type "text" :text "first"}]})
       (acp/handle-session-prompt agent nil
                                  {:sessionId sessionId
                                   :prompt    [{:type "text" :text "second"}]})
       (is (= [{:role "user" :content "first"}
               {:role "assistant" :content "pong"}
               {:role "user" :content "second"}]
              (:messages (second @requests))))))))

(deftest session-cancel-does-not-throw
  (run-with-stubbed-ollama
   "pong"
   (fn [agent _requests _updates]
     (let [{:keys [sessionId]} (acp/handle-session-new agent nil {:cwd "/tmp"})]
       (is (nil? (acp/on-session-cancel agent nil {:sessionId sessionId})))))))

(deftest prompt-for-unknown-session-relays-message
  (run-with-stubbed-ollama
   "pong"
   (fn [agent requests updates]
     (let [resp (acp/handle-session-prompt
                 agent nil
                 {:sessionId "missing"
                  :prompt    [{:type "text" :text "ping"}]})]
       (is (= {:stopReason "end_turn"} resp))
       (is (empty? @requests))
       (let [[sid update] (first @updates)]
         (is (= ["missing"] [sid]))
         (is (= "agent_message_chunk" (:sessionUpdate update)))
         (is (= "No session missing" (get-in update [:content :text]))))))))
