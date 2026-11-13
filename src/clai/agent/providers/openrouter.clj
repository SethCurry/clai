(ns clai.agent.providers.openrouter
  "A Clojure client for the OpenRouter chat-completions API.

  OpenRouter (https://openrouter.ai) proxies many model providers behind one
  OpenAI-compatible endpoint, `POST /api/v1/chat/completions`. This client
  speaks that protocol and normalizes the replies into the same
  assistant-message shape `clai.agent.providers.ollama` produces -- see
  `clai.agent.providers.messages` -- so `clai.agent.core` can run a session on
  either provider without caring which one answered.

  An API key is required for every request. It comes from the `:api-key`
  option to `make-client`, or from the `OPENROUTER_API_KEY` environment
  variable.

  Requests are non-streaming: the agent loop asks for one reply at a time, so
  `chat` always returns a single response map.

  Example:

      (require '[clai.agent.providers.openrouter :as openrouter])

      (def c (openrouter/make-client {:api-key (System/getenv \"OPENROUTER_API_KEY\")}))
      (openrouter/chat c {:model    \"anthropic/claude-sonnet-4\"
                          :messages [{:role \"user\" :content \"Why is the sky blue?\"}]})
      ;; => {:id \"gen-...\" :choices [{:message {:role \"assistant\" ...}}] ...}"
  (:require [clai.agent.providers.http :as http]
            [clai.agent.providers.messages :as messages]
            [clai.agent.tools.invoke :as invoke]
            [clai.events.schema :as events]
            [clai.log]
            [clai.util :as util]
            [cheshire.core :as json]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  util/non-empty-string)

(def OpenRouterClient
  "The OpenRouter `Client` record."
  [:map
   [:provider [:= :openrouter]]
   [:api-key [:maybe :string]]
   [:base-url non-empty-string]
   [:timeout pos-int?]
   [:referer {:optional true} [:maybe :string]]
   [:title {:optional true} [:maybe :string]]])

(def client-opts
  "Options accepted by [[make-client]]."
  [:map
   [:api-key {:optional true} [:maybe :string]]
   [:base-url {:optional true} non-empty-string]
   [:timeout {:optional true} pos-int?]
   [:referer {:optional true} [:maybe :string]]
   [:title {:optional true} [:maybe :string]]])

(def chat-message
  "One entry of a chat-completions `:messages` vector."
  [:map
   [:role non-empty-string]
   [:content {:optional true} [:maybe [:or :string [:sequential :map]]]]
   [:tool_call_id {:optional true} [:maybe :string]]
   [:tool_calls {:optional true} [:sequential :map]]
   [:name {:optional true} [:maybe :string]]])

(def chat-request
  "Request body for `POST /chat/completions`, as accepted by [[chat]].
  Keys beyond these (`:temperature`, `:max_tokens`, `:reasoning`, ...) are
  forwarded verbatim, matching OpenRouter's pass-through behaviour."
  [:map
   [:model non-empty-string]
   [:messages [:sequential chat-message]]
   [:tools {:optional true} [:sequential [:map]]]
   [:stream {:optional true} boolean?]])

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:private default-base-url "https://openrouter.ai/api/v1")

(def ^:private default-timeout-ms 600000) ; 10 minutes; models can be slow.

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defrecord Client [provider api-key base-url timeout referer title])

(def ^:no-doc
  ^:dynamic *client*
  (map->Client {:provider :openrouter
                :api-key  nil
                :base-url default-base-url
                :timeout  default-timeout-ms}))

(defn make-client
  "Create an OpenRouter client.

  Options:

    :api-key   OpenRouter API key. Defaults to `OPENROUTER_API_KEY`.
    :base-url  API root. Defaults to `https://openrouter.ai/api/v1`.
    :timeout   Request timeout in milliseconds. Defaults to 600000 (10 min).
    :referer   Optional `HTTP-Referer` header (OpenRouter's attribution).
    :title     Optional `X-Title` header. Defaults to `clai`.

  Building a client does not require the key: a request without one fails
  with an explanatory message. Returns a
  `clai.agent.providers.openrouter.Client` record."
  {:malli/schema [:function
                  [:=> [:cat] OpenRouterClient]
                  [:=> [:cat [:maybe client-opts]] OpenRouterClient]]}
  ([] (make-client nil))
  ([{:keys [api-key base-url timeout referer title]
     :or   {api-key  (System/getenv "OPENROUTER_API_KEY")
            base-url (or (System/getenv "OPENROUTER_BASE_URL")
                         default-base-url)
            timeout  default-timeout-ms
            title    "clai"}}]
   (let [client (map->Client {:provider :openrouter
                              :api-key  api-key
                              :base-url base-url
                              :timeout  timeout
                              :referer  referer
                              :title    title})]
     (util/validate! OpenRouterClient client "OpenRouter client")
     (tel/log! {:level :debug
                :id    ::client-created
                :data  {:base-url base-url
                        :api-key? (some? api-key)
                        :timeout  timeout}}
               "Created OpenRouter client")
     client)))

(defmacro with-client
  "Bind `clai.agent.providers.openrouter/*client*` for the body, so calls that
  omit an explicit client use `client`.

      (clai.agent.providers.openrouter/with-client
        (clai.agent.providers.openrouter/make-client {:api-key key})
        (clai.agent.providers.openrouter/chat {:model \"openai/gpt-4o-mini\"
                                               :messages ...}))"
  [client & body]
  `(binding [*client* ~client]
     ~@body))

;; ---------------------------------------------------------------------------
;; Request shaping
;; ---------------------------------------------------------------------------

(def ^:private openai-option-keys
  "The `:options` keys OpenRouter forwards to the underlying provider. Ollama's
  model options (`:num_ctx`, `:repeat_penalty`, ...) have no OpenAI
  equivalent, so they are dropped rather than rejected."
  #{:temperature :top_p :top_k :min_p :max_tokens :max_completion_tokens
    :stop :presence_penalty :frequency_penalty :repetition_penalty :logit_bias
    :logprobs :top_logprobs :seed :n :user :parallel_tool_calls :tool_choice
    :response_format :reasoning :reasoning_effort :transforms :models :route
    :provider :prediction :modalities :web_search_options})

(defn- format->response-format
  "Translate the config's provider-neutral `:format` into an OpenAI
  `response_format`. `\"json\"` asks for a JSON object; a map is treated as a
  JSON schema for structured output. Nil means no constraint."
  [format]
  (cond
    (nil? format)          nil
    (= "json" format)      {:type "json_object"}
    (map? format)          {:type        "json_schema"
                            :json_schema {:name   "response"
                                          :schema format
                                          :strict false}}
    :else                  nil))

(defn chat-options
  "Translate the agent's provider-neutral chat options (`{:options :think
  :format}`, the same map the Ollama client forwards verbatim) into
  OpenRouter request keys.

  `:options` keeps only the OpenAI-compatible keys, `:think true` enables
  OpenRouter's unified reasoning control, and `:format` becomes a
  `response_format`."
  {:malli/schema [:-> [:maybe [:map]]
                  [:map-of :keyword :any]]}
  [chat-opts]
  (let [format  (format->response-format (:format chat-opts))
        options (into {}
                      (filter (comp openai-option-keys key))
                      (:options chat-opts))]
    (cond-> options
      (true? (:think chat-opts))  (assoc :reasoning {:enabled true})
      (some? format)              (assoc :response_format format))))

(defn json-arguments
  "Encode tool-call `arguments` as the JSON string OpenRouter expects. A value
  that is already a string is passed through unchanged."
  {:malli/schema [:-> :any :string]}
  [arguments]
  (cond
    (nil? arguments)    "{}"
    (string? arguments) arguments
    :else               (json/generate-string arguments)))

(defn- tool-call->request
  "Convert a stored tool call into the OpenAI `tool_calls` entry OpenRouter
  expects. Arguments are a JSON *string* on the wire, but session history
  keeps them as a map, so they are re-encoded here."
  [call]
  {:id       (messages/tool-call-id call)
   :type     "function"
   :function {:name      (messages/tool-call-name call)
              :arguments (json-arguments (messages/tool-call-arguments call))}})

(defn- message->request
  "Convert one stored history message into an OpenRouter chat message.

  Assistant turns keep their tool calls (re-encoded) and drop the reasoning
  text, which providers do not accept back. `tool` turns keep the
  `tool_call_id` that ties them to the call they answer."
  [message]
  (let [role (or (some-> (:role message) str) "user")]
    (case role
      "tool"
      (cond-> {:role    "tool"
               :content (events/content->text (:content message))}
        (some? (:tool_call_id message))
        (assoc :tool_call_id (:tool_call_id message)))

      "assistant"
      (cond-> {:role    "assistant"
               :content (events/content->text (:content message))}
        (seq (:tool_calls message))
        (assoc :tool_calls (mapv tool-call->request (:tool_calls message))))

      {:role    role
       :content (events/content->text (:content message))})))

(defn history->messages
  "Convert stored session `history` into OpenRouter `:messages`.

  The stored shape is the normalized one (see
  `clai.agent.providers.messages`), so this drops fields providers reject
  (`:thinking`, `:name`) and rewrites tool calls into the OpenAI form."
  {:malli/schema [:-> [:maybe [:sequential :map]] [:vector chat-message]]}
  [history]
  (mapv message->request history))

(defn request-body
  "Build the `POST /chat/completions` body for one chat turn.

  `:tools` is omitted when empty (OpenRouter rejects an empty tool list for
  some upstream providers) and the agent's `chat-opts` are translated by
  [[chat-options]]."
  {:malli/schema [:-> [:map
                       [:model non-empty-string]
                       [:history [:sequential :map]]
                       [:tools {:optional true} [:sequential :map]]
                       [:chat-opts {:optional true} [:map]]]
                  chat-request]}
  [{:keys [model history tools chat-opts]}]
  (merge (cond-> {:model    model
                  :messages (history->messages history)
                  :stream   false}
           (seq tools) (assoc :tools tools))
         (chat-options chat-opts)))

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- client-headers
  "The `HTTP-Referer` / `X-Title` attribution headers OpenRouter accepts, plus
  the bearer token from [[http/auth-headers]]."
  [client]
  (http/auth-headers
   client
   (cond-> {}
     (not (str/blank? (str (:referer client))))
     (assoc "HTTP-Referer" (:referer client))

     (not (str/blank? (str (:title client))))
     (assoc "X-Title" (:title client)))))

(defn- check-key!
  "Throw when `client` carries no API key, so a misconfigured OpenRouter model
  fails with one clear sentence instead of an opaque 401."
  [client]
  (when (str/blank? (str (:api-key client)))
    (throw (ex-info
            (str "OpenRouter is not configured: set :openrouter {:api-key ...} "
                 "in clai.edn or the OPENROUTER_API_KEY environment variable.")
            {:provider :openrouter}))))

(defn- throw-response-error
  "Throw for an error OpenRouter reported inside a 200 response."
  [client error]
  (let [message (or (:message error) (str error))]
    (tel/log! {:level :error
               :id    ::api-error
               :data  {:status (:code error) :message message}}
              (str "OpenRouter request failed: " message))
    (throw (ex-info (str "OpenRouter request failed: " message)
                    {:provider :openrouter
                     :status   (:code error)
                     :body     error}))))

(defn chat
  "Send one chat-completions request. Calls `POST /chat/completions`.

  Required: `:model` and `:messages` (a vector of `{:role ... :content ...}`
  maps, as produced by [[history->messages]]). Any additional OpenAI request
  key (`:tools`, `:temperature`, `:reasoning`, ...) is forwarded verbatim.

  Always returns a single response map: `:stream` is forced to `false`, since
  the agent loop wants one reply at a time."
  {:malli/schema [:function
                  [:=> [:cat chat-request] [:maybe :map]]
                  [:=> [:cat OpenRouterClient chat-request] [:maybe :map]]]}
  ([req] (chat *client* req))
  ([client req]
   (check-key! client)
   (util/validate! chat-request req "chat request")
   (let [body (http/perform client :post "/chat/completions"
                            (assoc req :stream false)
                            {:headers (client-headers client)})]
     (if-let [error (:error body)]
       (throw-response-error client error)
       body))))

;; ---------------------------------------------------------------------------
;; Response normalization
;; ---------------------------------------------------------------------------

(defn- tool-call->message
  "Normalize one OpenRouter `tool_calls` entry into the shape the agent loop
  works with (see `clai.agent.providers.messages`).

  `arguments` arrive as a JSON string and are parsed into a map, matching the
  shape Ollama's client produces. Unparseable text is reported to the model
  rather than thrown (`clai.agent.tools.invoke/parse-arguments`)."
  [call]
  {:id       (or (:id call) (str (UUID/randomUUID)))
   :type     "function"
   :function {:name      (get-in call [:function :name])
              :arguments (invoke/parse-arguments
                          (get-in call [:function :arguments]))}})

(defn assistant-message
  "Normalize the assistant message of an OpenRouter chat response into the
  provider-neutral shape: `{:role \"assistant\" :content ... :thinking ...
  :tool_calls [...]}`.

  Reasoning text arrives in OpenRouter's `:reasoning` field and becomes
  `:thinking`, so it replays as an `agent_thought` event."
  {:malli/schema [:-> [:maybe :map] messages/AssistantMessage]}
  [resp]
  (let [message (-> resp :choices first :message)]
    (cond-> {:role    "assistant"
             :content (or (:content message) "")}
      (not (str/blank? (str (:reasoning message))))
      (assoc :thinking (str (:reasoning message)))

      (seq (:tool_calls message))
      (assoc :tool_calls (mapv tool-call->message (:tool_calls message))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Send one throwaway prompt to prove the client is configured, printing the
  reply. Used as a smoke test."
  {:malli/schema [:=> [:cat [:* :string]] :nil]}
  [& [model]]
  (let [client (make-client)
        reply  (assistant-message (chat client {:model    (or model "openai/gpt-4o-mini")
                                                :messages [{:role "user"
                                                            :content "ping"}]}))]
    (println (:content reply))))
