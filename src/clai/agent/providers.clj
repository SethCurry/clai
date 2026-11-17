(ns clai.agent.providers
  "The entry point over the LLM providers `clai` can talk to.

  Each provider is named by a keyword (`:ollama`, `:openrouter`) and is
  implemented by its own namespace (`clai.agent.providers.ollama`,
  `clai.agent.providers.openrouter`). A provider's client record implements
  `clai.agent.providers.protocol/Provider`, so chatting, tool translation, and
  token accounting dispatch on the client itself; this namespace adds what the
  protocol deliberately leaves out: resolving a model name to the provider that
  serves it, and building one client per provider from configuration.

  ## Choosing a provider by model name

  A configured model name may name its provider explicitly with a
  `<provider>:` prefix. Without one, the model decides:

      \"gemma3\"                        -> the default provider (Ollama)
      \"ollama:gemma3:7b\"              -> Ollama, model `gemma3:7b`
      \"openrouter:gemma3:7b\"          -> OpenRouter, model `gemma3:7b`
      \"anthropic/claude-sonnet-4\"     -> OpenRouter (OpenRouter model ids are
                                           namespaced with `/`)

  Anything else -- such as an Ollama model whose name happens to contain `/`
  -- can still be pinned with the `ollama:` prefix. `:provider` in `clai.edn`
  (or `CLAI_PROVIDER`) chooses the default provider; it is `:ollama`.

  ## Chatting

  [[complete]] is the whole conversation turn in one call: it converts the
  stored session history into the provider's wire format, sends the request,
  and normalizes the reply into the assistant-message shape described in
  `clai.agent.providers.messages`, so the agent loop stays provider-agnostic.
  [[respond]] does the same and also returns the provider's raw response and
  its normalized token usage. [[capabilities]], [[tools]], and [[token-usage]]
  answer what a given provider can do and reports.

  Example:

      (require '[clai.agent.providers :as providers]
               '[clai.config :as config])

      (def clients (providers/make-clients (config/load-config)))

      ;; Resolve the model to its provider's client, then run one turn.
      (let [{:keys [client model]}
            (providers/model-client clients \"openrouter:openai/gpt-4o-mini\" :ollama)]
        (providers/respond client
                           {:model   model
                            :history [{:role \"user\" :content \"ping\"}]}))
      ;; => {:message  {:role \"assistant\" :content \"pong\"}
      ;;     :usage    {:input-tokens 8 :output-tokens 2 :total-tokens 10
      ;;                :cost 0.000012, ...}
      ;;     :response {...OpenRouter's own response map...}}"
  (:require [clai.agent.providers.messages :as messages]
            [clai.agent.providers.ollama :as ollama]
            [clai.agent.providers.openrouter :as openrouter]
            [clai.agent.providers.protocol :as protocol]
            [clai.util :as util]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Providers and model names
;; ---------------------------------------------------------------------------

(def Provider
  "A provider keyword: one of the providers `clai` supports."
  [:enum :ollama :openrouter])

(def ModelSpec
  "A model name resolved to the provider that serves it."
  [:map
   [:provider Provider]
   [:model :string]])

(def ResolvedModel
  "A model name resolved to its provider and that provider's client."
  [:map
   [:provider Provider]
   [:model :string]
   [:client :map]])

(def Clients
  "One client per provider, as returned by [[make-clients]]."
  [:map-of Provider :map])

(def provider-names
  "Every provider `clai` supports, in the order they are documented."
  [:ollama :openrouter])

(defn providers
  "Return the provider keywords `clai` supports, for the REPL (`(providers)`)
  and for configuration validation."
  {:malli/schema [:-> [:vector Provider]]}
  []
  provider-names)

(defn supported-provider?
  "True when `provider` (a keyword or string) names a provider `clai` supports."
  {:malli/schema [:-> :any boolean?]}
  [provider]
  (contains? (set provider-names)
             (if (string? provider) (keyword provider) provider)))

(defn- provider-prefix
  "Split `<provider>:<model>` into `[provider model]` when `model` starts with
  a supported provider name, else nil.

  Only the provider names `clai` knows count, so Ollama tags such as
  `gemma3:7b` are not mistaken for prefixes."
  [model]
  (when-let [idx (and (string? model) (.indexOf ^String model ":"))]
    (when (pos? idx)
      (let [provider (keyword (subs model 0 idx))
            name     (subs model (inc idx))]
        (when (and (supported-provider? provider) (not= "" name))
          [provider name])))))

(defn model-spec
  "Resolve `model` to `{:provider ... :model ...}`.

  An explicit `<provider>:` prefix wins; a name containing `/` is an
  OpenRouter model id; anything else uses `default-provider` (falling back to
  `:ollama` when that is nil or not a supported provider)."
  {:malli/schema [:-> [:maybe :string] [:maybe Provider] ModelSpec]}
  [model default-provider]
  (let [model   (str/trim (if (string? model) model (str model)))
        default (if (supported-provider? default-provider)
                  default-provider
                  :ollama)]
    (if-let [[provider model] (provider-prefix model)]
      {:provider provider :model model}
      {:provider (if (str/includes? model "/") :openrouter default)
       :model    model})))

(defn provider-for-model
  "The provider keyword that serves `model`."
  {:malli/schema [:-> [:maybe :string] [:maybe Provider] Provider]}
  [model default-provider]
  (:provider (model-spec model default-provider)))

(defn model-name
  "The bare model name `model` names, with any provider prefix removed."
  {:malli/schema [:-> [:maybe :string] [:maybe Provider] :string]}
  [model default-provider]
  (:model (model-spec model default-provider)))

;; ---------------------------------------------------------------------------
;; Clients
;; ---------------------------------------------------------------------------

(defn make-client
  "Build the client for `provider` from its `opts` sub-map.

  `:ollama` opts are `clai.agent.providers.ollama/make-client`'s
  (`{:host :api-key :timeout}`); `:openrouter` opts are
  `clai.agent.providers.openrouter/make-client`'s (`{:api-key :base-url
  :timeout :referer :title}`). Omitted keys fall back to the provider's
  environment variables and defaults."
  {:malli/schema [:-> Provider [:maybe [:map]] protocol/Client]}
  [provider opts]
  (case provider
    :ollama     (ollama/make-client
                 (util/select-present opts [:host :api-key :timeout]))
    :openrouter (openrouter/make-client
                 (util/select-present opts
                                      [:api-key :base-url :timeout
                                       :referer :title]))
    (throw (ex-info (str "Unknown provider " (pr-str provider))
                    {:provider provider
                     :supported provider-names}))))

(defn make-clients
  "Build one client per provider from `cfg`, a `clai.config/load-config` map.

  Reading the `:ollama` and `:openrouter` sub-maps here (and not in
  `clai.config`) keeps the provider namespaces free of a dependency on the
  config layer."
  {:malli/schema [:-> [:map] Clients]}
  [cfg]
  (into {}
        (map (fn [provider]
               [provider (make-client provider (get cfg provider))]))
        provider-names))

(defn client-for
  "The client in `clients` that serves `provider`, throwing when it is
  missing. `clients` normally comes from [[make-clients]] and so holds every
  provider."
  {:malli/schema [:-> Clients Provider protocol/Client]}
  [clients provider]
  (or (get clients provider)
      (throw (ex-info (str "No client configured for the " (name provider)
                           " provider")
                      {:provider provider
                       :clientProviders (vec (keys clients))}))))

(defn model-client
  "Resolve `model` to `{:provider ... :model ... :client ...}`.

  `clients` is a `{provider client}` map ([[make-clients]]) and
  `default-provider` chooses the provider for a model name that does not name
  one. This is how the agent picks a provider per model -- and per session's
  stored agent configuration."
  {:malli/schema [:-> Clients [:maybe :string] [:maybe Provider] ResolvedModel]}
  [clients model default-provider]
  (let [{:keys [provider] :as spec} (model-spec model default-provider)]
    (assoc spec :client (client-for clients provider))))

;; ---------------------------------------------------------------------------
;; Chatting through the client's provider
;; ---------------------------------------------------------------------------
;;
;; These are the protocol's functions, imported so a caller has one namespace
;; to require: `chat-request` shapes a turn, `chat` sends it, `respond` and
;; `complete` run the whole turn, and `capabilities`, `tools`, and `token-usage`
;; say what a provider can do and reports.

(util/import-vars protocol/provider-id
                  protocol/capabilities
                  protocol/supports-tools?
                  protocol/supports-usage?
                  protocol/chat-request
                  protocol/chat
                  protocol/assistant-message
                  protocol/tools
                  protocol/token-usage
                  protocol/respond
                  protocol/complete)

;; ---------------------------------------------------------------------------
;; Message helpers (provider-neutral)
;; ---------------------------------------------------------------------------

(util/import-vars messages/has-tool-calls?
                  messages/tool-call-id
                  messages/tool-call-name
                  messages/tool-call-title
                  messages/tool-result-status)
