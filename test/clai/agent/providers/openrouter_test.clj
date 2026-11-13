(ns clai.agent.providers.openrouter-test
  "Tests for the OpenRouter client: client construction, request shaping,
  history conversion, and response normalization."
  (:require [clai.agent.providers.openrouter :as openrouter]
            [clai.test-support :refer [quiet-logs]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer :all]))

;; ---------------------------------------------------------------------------
;; Fixtures and helpers
;; ---------------------------------------------------------------------------

(def ^:dynamic *recorded-requests* (atom nil))
(def ^:dynamic *stub-response* (atom nil))

(defn- with-stubbed-http
  "Replace clj-http's `request` with a stub that records the options it was
  called with and returns the response stored in `*stub-response*`."
  [f]
  (binding [*recorded-requests* (atom [])
            *stub-response*     (atom {:status 200 :body nil})]
    (with-redefs [http/request (fn [opts]
                                 (swap! *recorded-requests* conj opts)
                                 @*stub-response*)]
      (f))))

(use-fixtures :once quiet-logs)
(use-fixtures :each with-stubbed-http)

(defn- last-request
  "The most recent request the client issued."
  []
  (peek @*recorded-requests*))

(defn- json-response
  "A 200 response carrying `body` as JSON."
  [body]
  {:status 200 :body (.getBytes (json/encode body) "UTF-8")})

(def sample-client
  (openrouter/make-client {:api-key  "sk-or-test"
                           :base-url "https://openrouter.test/api/v1"
                           :timeout  12345
                           :referer  "https://example.com"
                           :title    "clai-test"}))

(def ^:private completion
  "A minimal successful chat-completions response."
  {:id      "gen-1"
   :model   "anthropic/claude-sonnet-4"
   :choices [{:message       {:role "assistant" :content "pong"}
              :finish_reason "stop"}]})

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(deftest make-client-defaults
  (let [c (openrouter/make-client)]
    (is (= :openrouter (:provider c)))
    (is (= "https://openrouter.ai/api/v1" (:base-url c)))
    (is (= "clai" (:title c)))
    (is (pos? (:timeout c)))))

(deftest make-client-options
  (is (= "sk-or-test" (:api-key sample-client)))
  (is (= "https://openrouter.test/api/v1" (:base-url sample-client)))
  (is (= 12345 (:timeout sample-client)))
  (is (= "https://example.com" (:referer sample-client)))
  (is (= "clai-test" (:title sample-client))))

(deftest make-client-is-a-record
  (is (instance? clai.agent.providers.openrouter.Client
                 (openrouter/make-client))))

;; ---------------------------------------------------------------------------
;; Chat
;; ---------------------------------------------------------------------------

(deftest chat-posts-to-chat-completions
  (reset! *stub-response* (json-response completion))
  (let [result (openrouter/chat sample-client
                                {:model    "anthropic/claude-sonnet-4"
                                 :messages [{:role "user" :content "ping"}]})
        req    (last-request)]
    (is (= :post (:request-method req)))
    (is (= "https://openrouter.test/api/v1/chat/completions" (:url req)))
    (is (= "Bearer sk-or-test" (get (:headers req) "Authorization")))
    (is (= "https://example.com" (get (:headers req) "HTTP-Referer")))
    (is (= "clai-test" (get (:headers req) "X-Title")))
    (is (= {:model    "anthropic/claude-sonnet-4"
            :messages [{:role "user" :content "ping"}]
            :stream   false}
           (json/parse-string (String. ^bytes (:body req) "UTF-8") true)))
    (is (= "gen-1" (:id result)))))

(deftest chat-requires-an-api-key
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"OpenRouter is not configured"
       (openrouter/chat (openrouter/make-client {:api-key nil})
                        {:model    "openai/gpt-4o-mini"
                         :messages [{:role "user" :content "ping"}]}))))

(deftest chat-reports-http-errors
  (reset! *stub-response*
          {:status 402
           :body   (.getBytes (json/encode
                               {:error {:message "Insufficient credits"
                                        :code    402}})
                              "UTF-8")})
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Insufficient credits"
       (openrouter/chat sample-client
                        {:model    "openai/gpt-4o-mini"
                         :messages [{:role "user" :content "ping"}]}))))

(deftest chat-reports-errors-inside-a-200-response
  (reset! *stub-response*
          (json-response {:error {:message "No endpoints found"
                                  :code    404}}))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"No endpoints found"
       (openrouter/chat sample-client
                        {:model    "openai/gpt-4o-mini"
                         :messages [{:role "user" :content "ping"}]}))))

;; ---------------------------------------------------------------------------
;; History conversion
;; ---------------------------------------------------------------------------

(deftest history-converts-tool-calls-and-results
  (let [history [{:role "system" :content "be brief"}
                 {:role "user" :content "list files"}
                 {:role    "assistant"
                  :content ""
                  :thinking "I should look"
                  :tool_calls [{:id       "call-1"
                                :function {:name      "list_directory"
                                           :arguments {:path "/tmp"}}}]}
                 {:role         "tool"
                  :name         "list_directory"
                  :tool_call_id "call-1"
                  :content      "a.txt"}]
        messages (openrouter/history->messages history)]
    (is (= [{:role "system" :content "be brief"}
            {:role "user" :content "list files"}
            {:role         "assistant"
             :content      ""
             :tool_calls   [{:id       "call-1"
                             :type     "function"
                             :function {:name      "list_directory"
                                        :arguments "{\"path\":\"/tmp\"}"}}]}
            {:role         "tool"
             :tool_call_id "call-1"
             :content      "a.txt"}]
           messages))
    (is (not (contains? (nth messages 2) :thinking))
        "reasoning is not sent back to the provider")
    (is (not (contains? (nth messages 3) :name))
        "tool messages carry only what OpenAI-compatible APIs accept")))

(deftest history-flattens-content-lists
  (is (= [{:role "user" :content "one\ntwo"}]
         (openrouter/history->messages
          [{:role "user" :content [{:type "text" :content "one"}
                                   {:type "text" :content "two"}]}]))))

;; ---------------------------------------------------------------------------
;; Chat options
;; ---------------------------------------------------------------------------

(deftest chat-options-keep-openai-parameters
  (is (= {:temperature 0.2 :max_tokens 64}
         (openrouter/chat-options {:options {:temperature 0.2
                                             :max_tokens  64
                                             :num_ctx     8192}}))
      "Ollama-only model options are dropped"))

(deftest chat-options-translate-think-and-format
  (is (= {:reasoning {:enabled true}}
         (openrouter/chat-options {:think true})))
  (is (= {:response_format {:type "json_object"}}
         (openrouter/chat-options {:format "json"})))
  (is (= {:response_format {:type        "json_schema"
                            :json_schema {:name   "response"
                                          :schema {:type "object"}
                                          :strict false}}}
         (openrouter/chat-options {:format {:type "object"}}))))

(deftest chat-options-empty-by-default
  (is (= {} (openrouter/chat-options nil)))
  (is (= {} (openrouter/chat-options {}))))

;; ---------------------------------------------------------------------------
;; Request body
;; ---------------------------------------------------------------------------

(deftest request-body-carries-history-and-options
  (let [body (openrouter/request-body
              {:model     "openai/gpt-4o-mini"
               :history   [{:role "user" :content "ping"}]
               :tools     [{:type "function" :function {:name "echo"}}]
               :chat-opts {:options {:temperature 0.1}}})]
    (is (= "openai/gpt-4o-mini" (:model body)))
    (is (= [{:role "user" :content "ping"}] (:messages body)))
    (is (= false (:stream body)))
    (is (= 0.1 (:temperature body)))
    (is (= [{:type "function" :function {:name "echo"}}] (:tools body)))))

(deftest request-body-omits-empty-tools
  (is (not (contains? (openrouter/request-body {:model   "openai/gpt-4o-mini"
                                                :history [{:role "user"
                                                           :content "ping"}]
                                                :tools   []})
                      :tools))))

;; ---------------------------------------------------------------------------
;; Response normalization
;; ---------------------------------------------------------------------------

(deftest assistant-message-normalizes-content
  (is (= {:role "assistant" :content "pong"}
         (openrouter/assistant-message completion))))

(deftest assistant-message-maps-reasoning-to-thinking
  (is (= {:role     "assistant"
          :content  "pong"
          :thinking "because"}
         (openrouter/assistant-message
          {:choices [{:message {:role      "assistant"
                                :content   "pong"
                                :reasoning "because"}}]}))))

(deftest assistant-message-normalizes-tool-calls
  (let [msg (openrouter/assistant-message
             {:choices [{:message
                         {:role    "assistant"
                          :content nil
                          :tool_calls
                          [{:id       "call-1"
                            :type     "function"
                            :function {:name      "list_directory"
                                       :arguments "{\"path\":\"/tmp\"}"}}]}}]})]
    (is (= "" (:content msg)))
    (is (= [{:id       "call-1"
             :type     "function"
             :function {:name      "list_directory"
                        :arguments {:path "/tmp"}}}]
           (:tool_calls msg)))
    (is (= "list_directory"
           (-> msg :tool_calls first (get-in [:function :name]))))
    (is (map? (-> msg :tool_calls first (get-in [:function :arguments])))
        "arguments are parsed into a map, like Ollama's")))

(deftest assistant-message-tolerates-missing-choices
  (is (= {:role "assistant" :content ""}
         (openrouter/assistant-message {}))))
