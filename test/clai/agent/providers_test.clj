(ns clai.agent.providers-test
  "Tests for provider selection: which provider serves a model name, and that a
  chat turn reaches the right provider client."
  (:require [clai.agent.providers :as providers]
            [clai.agent.providers.ollama :as ollama]
            [clai.agent.providers.openrouter :as openrouter]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

;; ---------------------------------------------------------------------------
;; Model names
;; ---------------------------------------------------------------------------

(deftest plain-model-names-use-the-default-provider
  (is (= {:provider :ollama :model "gemma3"}
         (providers/model-spec "gemma3" :ollama)))
  (is (= {:provider :openrouter :model "gemma3"}
         (providers/model-spec "gemma3" :openrouter)))
  (is (= {:provider :ollama :model "gemma3"}
         (providers/model-spec "gemma3" nil))
      "a nil default provider means Ollama"))

(deftest provider-prefixes-pin-the-provider
  (is (= {:provider :ollama :model "gemma3:7b"}
         (providers/model-spec "ollama:gemma3:7b" :openrouter))
      "the prefix beats the default, and the tag survives")
  (is (= {:provider :openrouter :model "gemma3:7b"}
         (providers/model-spec "openrouter:gemma3:7b" :ollama))
      "an OpenRouter id that happens to look like an Ollama tag")
  (is (= {:provider :ollama :model "hf.co/org/model"}
         (providers/model-spec "ollama:hf.co/org/model" :ollama))
      "an Ollama model whose name contains a slash can still be pinned"))

(deftest namespaced-model-ids-use-openrouter
  (is (= {:provider :openrouter :model "anthropic/claude-sonnet-4"}
         (providers/model-spec "anthropic/claude-sonnet-4" :ollama))))

(deftest ollama-tags-are-not-provider-prefixes
  (is (= {:provider :ollama :model "gemma3:7b"}
         (providers/model-spec "gemma3:7b" :ollama))))

(deftest provider-helpers
  (is (= [:ollama :openrouter] (providers/providers)))
  (is (providers/supported-provider? :openrouter))
  (is (providers/supported-provider? "openrouter"))
  (is (not (providers/supported-provider? :nope)))
  (is (= :openrouter (providers/provider-for-model "a/b" :ollama)))
  (is (= "a/b" (providers/model-name "openrouter:a/b" :ollama)))
  (is (= "gemma3" (providers/model-name "gemma3" :ollama))))

;; ---------------------------------------------------------------------------
;; Clients
;; ---------------------------------------------------------------------------

(def ^:private clients
  {:ollama     (ollama/make-client {:host "http://ollama.test"})
   :openrouter (openrouter/make-client {:api-key "sk-or-test"})})

(deftest make-clients-returns-both-providers
  (let [built (providers/make-clients {:ollama     {:host "http://x:1"}
                                       :openrouter {:api-key "k"}})]
    (is (= #{:ollama :openrouter} (set (keys built))))
    (is (= "http://x:1" (-> built :ollama :host)))
    (is (= "k" (-> built :openrouter :api-key)))))

(deftest make-client-rejects-unknown-providers
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown provider"
                        (providers/make-client :gemini {}))))

(deftest model-client-follows-the-model
  (is (= (get clients :ollama)
         (:client (providers/model-client clients "gemma3" :ollama))))
  (is (= (get clients :openrouter)
         (:client (providers/model-client clients "gemma3" :openrouter))))
  (is (= (get clients :openrouter)
         (:client (providers/model-client clients "openrouter:gemma3" :ollama)))
      "the prefix selects the provider, not the default")
  (is (= "gemma3"
         (:model (providers/model-client clients "openrouter:gemma3" :ollama)))
      "the provider prefix is stripped before the request"))

;; ---------------------------------------------------------------------------
;; Chat dispatch
;; ---------------------------------------------------------------------------

(deftest complete-sends-the-ollama-history-as-is
  (let [requests (atom [])]
    (with-redefs [ollama/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:message {:role "assistant" :content "pong"}})]
      (is (= {:role "assistant" :content "pong"}
             (providers/complete (:ollama clients)
                                 {:model     "gemma3"
                                  :history   [{:role "user" :content "ping"}]
                                  :tools     []
                                  :chat-opts {:options {:temperature 0.5}}})))
      (is (= {:model    "gemma3"
              :messages [{:role "user" :content "ping"}]
              :stream   false
              :tools    []
              :options  {:temperature 0.5}}
             (first @requests))))))

(deftest complete-converts-history-for-openrouter
  (let [requests (atom [])]
    (with-redefs [openrouter/chat
                  (fn [_client req]
                    (swap! requests conj req)
                    {:choices [{:message {:role    "assistant"
                                          :content "pong"}}]})]
      (is (= {:role "assistant" :content "pong"}
             (providers/complete (:openrouter clients)
                                 {:model     "anthropic/claude-sonnet-4"
                                  :history   [{:role    "assistant"
                                               :content ""
                                               :thinking "hmm"
                                               :tool_calls
                                               [{:id       "call-1"
                                                 :function {:name "echo"
                                                            :arguments {:text "hi"}}}]}
                                              {:role         "tool"
                                               :name         "echo"
                                               :tool_call_id "call-1"
                                               :content      "echo:hi"}]
                                  :tools     [{:type "function"
                                               :function {:name "echo"}}]
                                  :chat-opts {:think true}})))
      (let [request (first @requests)]
        (is (= "anthropic/claude-sonnet-4" (:model request)))
        (is (= [{:role       "assistant"
                 :content    ""
                 :tool_calls [{:id       "call-1"
                               :type     "function"
                               :function {:name      "echo"
                                          :arguments "{\"text\":\"hi\"}"}}]}
                {:role         "tool"
                 :tool_call_id "call-1"
                 :content      "echo:hi"}]
               (:messages request))
            "history is converted into the OpenAI wire format")
        (is (= [{:type "function" :function {:name "echo"}}] (:tools request)))
        (is (= {:enabled true} (:reasoning request)))))))

(deftest chat-dispatches-on-the-client
  (with-redefs [ollama/chat     (fn [_ _] {:message {:role "assistant"
                                                     :content "ollama"}})
                openrouter/chat (fn [_ _] {:choices [{:message {:content "openrouter"}}]})]
    (is (= "ollama" (:content (providers/complete (:ollama clients)
                                                  {:model "gemma3" :history []}))))
    (is (= "openrouter" (:content (providers/complete (:openrouter clients)
                                                      {:model "a/b" :history []}))))))

(deftest unknown-clients-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown provider client"
                        (providers/chat {:provider :gemini} {:model "x"
                                                             :messages []}))))

;; ---------------------------------------------------------------------------
;; Message helpers are re-exported
;; ---------------------------------------------------------------------------

(deftest tool-call-helpers-work-on-normalized-messages
  (let [call {:id       "call-1"
              :function {:name "echo" :arguments {:text "hi"}}}]
    (is (providers/has-tool-calls? {:tool_calls [call]}))
    (is (not (providers/has-tool-calls? {:content "hi"})))
    (is (= "call-1" (providers/tool-call-id call)))
    (is (= "echo" (providers/tool-call-name call)))
    (is (= "echo {:text \"hi\"}" (providers/tool-call-title call)))
    (is (= "failed" (providers/tool-result-status "Error: nope")))
    (is (= "completed" (providers/tool-result-status "ok")))))
