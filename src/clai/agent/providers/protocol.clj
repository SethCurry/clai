(ns clai.agent.providers.protocol
  "The provider interface: everything `clai` needs from an LLM provider.

  A provider is an LLM service `clai` can chat with -- `:ollama` for a local
  or hosted Ollama server, `:openrouter` for OpenRouter's multi-model proxy.
  Each provider namespace builds its own client record and extends [[Provider]]
  to it (`clai.agent.providers.ollama`, `clai.agent.providers.openrouter`), so
  every client answers the same questions:

    [[provider-id]]         which provider is this client?
    [[capabilities]]        which features does it support, and what does it
                            report?
    [[chat-request]]        how is a provider-neutral turn put on the wire?
    [[chat]]                send it, returning the provider's raw response
    [[assistant-message]]   normalize the reply into an assistant message
    [[tools]]               translate tool definitions into its dialect
    [[token-usage]]         normalize the token accounting it reports

  The methods on [[Provider]] are the extension points and carry a `-` prefix;
  the functions without one are the public API -- they check the client and are
  what the rest of `clai` calls. [[respond]] and [[complete]] are the
  provider-neutral pipeline built on top of them, and `clai.agent.providers`
  adds model-name resolution and client construction.

  ## Features a provider lacks

  Providers differ in what they can do and what they report, so every optional
  part of the interface says so instead of pretending: a [[capabilities]] flag
  is `false` or `nil`, [[tools]] returns `nil` for a provider without tool
  calling, and a [[TokenUsage]] field is `nil` when the provider does not
  report it. Callers therefore never need to know which provider answered.

  Example:

      (require '[clai.agent.providers.protocol :as protocol]
               '[clai.agent.providers.ollama :as ollama])

      (let [client (ollama/make-client)
            turn   (protocol/respond client
                                     {:model   \"gemma3\"
                                      :history [{:role \"user\" :content \"ping\"}]})]
        [(:content (:message turn)) (:usage turn)])"
  (:require [clai.agent.providers.messages :as messages]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ProviderId
  "The keyword naming a provider, e.g. `:ollama`."
  [:keyword])

(def TokenUsage
  "The token accounting of one chat turn, normalized across providers.

  Providers report what they can: Ollama counts prompt and reply tokens,
  OpenRouter also knows the request's cost and its cached and reasoning-token
  splits. A `nil` (or absent) field is a number the provider does not report,
  so a caller can use whatever is there without special-casing a provider."
  [:map
   [:input-tokens {:optional true} [:maybe :int]]
   [:output-tokens {:optional true} [:maybe :int]]
   [:total-tokens {:optional true} [:maybe :int]]
   [:reasoning-tokens {:optional true} [:maybe :int]]
   [:cached-input-tokens {:optional true} [:maybe :int]]
   [:cost {:optional true} [:maybe [:or :int :double]]]
   [:raw {:optional true} [:maybe :map]]])

(def ToolSupport
  "Whether a provider can call tools, and in which dialect.

  `:format` names the wire shape of a tool call: `:openai` for
  `{:id ... :type \"function\" :function {:arguments <json string>}}` and
  `:ollama` for `{:function {:arguments <map>}}` without a call id. `:parallel`
  is nil when the provider does not say whether it runs several calls at once."
  [:map
   [:supported? :boolean]
   [:format {:optional true} [:maybe [:enum :openai :ollama]]]
   [:parallel {:optional true} [:maybe :boolean]]])

(def UsageSupport
  "Which parts of a [[TokenUsage]] a provider actually reports. A `false` or
  `nil` flag marks a field that stays `nil` for every response, so callers can
  skip looking for it."
  [:map
   [:tokens? :boolean]
   [:cost? {:optional true} [:maybe :boolean]]
   [:cached-tokens? {:optional true} [:maybe :boolean]]
   [:reasoning-tokens? {:optional true} [:maybe :boolean]]])

(def Capabilities
  "What a provider client supports, as returned by [[capabilities]]."
  [:map
   [:provider ProviderId]
   [:streaming {:optional true} [:maybe :boolean]]
   [:tools {:optional true} [:maybe ToolSupport]]
   [:usage {:optional true} [:maybe UsageSupport]]
   [:reasoning {:optional true} [:maybe :boolean]]
   [:images {:optional true} [:maybe :boolean]]
   [:structured-output {:optional true} [:maybe :boolean]]])

(def ChatSpec
  "A provider-neutral description of one chat turn, as [[chat-request]] takes
  it: `:model` (the bare model name), `:history` (the stored session history),
  `:tools` (neutral tool definitions, e.g. from
  `clai.agent.tools.registry/specs`), and `:chat-opts` (the agent's `:options`,
  `:think`, and `:format`)."
  [:map
   [:model :string]
   [:history [:sequential :map]]
   [:tools {:optional true} [:maybe [:sequential :map]]]
   [:chat-opts {:optional true} [:maybe [:map]]]])

(def Turn
  "What [[respond]] returns: the normalized assistant `:message`, the
  provider's raw `:response`, and the normalized `:usage` (nil when the
  provider reports no token counts)."
  [:map
   [:message #'messages/AssistantMessage]
   [:response [:maybe :map]]
   [:usage [:maybe TokenUsage]]])

;; ---------------------------------------------------------------------------
;; The interface
;; ---------------------------------------------------------------------------

(defprotocol Provider
  "The interface a provider client implements.

  Extend it to your own client record and `clai` can chat through it; the
  namespace docstring lists which method answers which question. Every method
  takes the client first, so a turn reads left to right; [[respond]]
  puts the pieces together:

      (let [wire (chat-request client spec)  ; neutral turn -> this dialect
            resp (chat client wire)]         ; one round trip
        {:message (assistant-message client resp)
         :usage   (token-usage client resp)})"

  (-provider-id
    [client]
    "The [[ProviderId]] keyword naming this provider, e.g. `:ollama`.")

  (-capabilities
    [client]
    "The [[Capabilities]] map: the features this provider supports and the
    usage fields it reports.")

  (-chat-request
    [client spec]
    "Shape the provider-neutral [[ChatSpec]] into this provider's request body,
    ready for [[-chat]].")

  (-chat
    [client req]
    "Send one chat request -- already in this provider's wire shape -- and
    return its raw response map.")

  (-assistant-message
    [client resp]
    "Normalize the raw `resp` of [[-chat]] into the provider-neutral
    `clai.agent.providers.messages/AssistantMessage`.")

  (-tools
    [client tool-specs]
    "Translate provider-neutral tool definitions into this provider's dialect,
    or nil when the provider cannot call tools or was given none.")

  (-token-usage
    [client resp]
    "The [[TokenUsage]] the raw `resp` of [[-chat]] reports, or nil when it
    reports none."))

(def Client
  "A provider client: any value implementing [[Provider]]."
  [:fn #(satisfies? Provider %)])

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn ensure-client
  "Return `client` when it implements [[Provider]], else throw an `ex-info`
  naming what was passed, so a caller sees one clear sentence instead of a
  \"no implementation of method\" from inside a protocol dispatch.

  The return schema is `:any` on purpose: reporting a client that does not
  implement the protocol is this function's whole job, and constraining its
  return would replace that message with a schema error."
  {:malli/schema [:-> :any :any]}
  [client]
  (if (satisfies? Provider client)
    client
    (throw (ex-info (str "Not a provider client: " (pr-str client))
                    {:client client}))))

(defn provider-id
  "The keyword naming `client`'s provider, e.g. `:ollama`."
  {:malli/schema [:-> Client ProviderId]}
  [client]
  (-provider-id (ensure-client client)))

(defn capabilities
  "What `client`'s provider supports, as a [[Capabilities]] map.

  Use it when a caller can work with a feature or without it -- for example
  offering tools only to a provider whose `:tools` are supported, or showing
  costs only where `[:usage :cost?]` is true."
  {:malli/schema [:-> Client Capabilities]}
  [client]
  (-capabilities (ensure-client client)))

(defn supports-tools?
  "True when `client`'s provider can call tools at all."
  {:malli/schema [:-> Client :boolean]}
  [client]
  (boolean (get-in (capabilities client) [:tools :supported?])))

(defn supports-usage?
  "True when `client`'s provider reports token counts."
  {:malli/schema [:-> Client :boolean]}
  [client]
  (boolean (get-in (capabilities client) [:usage :tokens?])))

(defn chat-request
  "Build the request body for one chat turn from the provider-neutral `spec`
  (a [[ChatSpec]]: `:model`, `:history`, `:tools`, `:chat-opts`).

  The body is in `client`'s wire shape and goes straight to [[chat]]."
  {:malli/schema [:-> Client ChatSpec [:map]]}
  [client spec]
  (-chat-request (ensure-client client) spec))

(defn chat
  "Send one chat `req` -- a body from [[chat-request]] -- to `client` and
  return the provider's raw response map."
  {:malli/schema [:-> Client :map [:maybe :map]]}
  [client req]
  (-chat (ensure-client client) req))

(defn assistant-message
  "Normalize `resp`, the raw response of [[chat]], into the provider-neutral
  assistant message described in `clai.agent.providers.messages`."
  {:malli/schema [:-> Client [:maybe :map] #'messages/AssistantMessage]}
  [client resp]
  (-assistant-message (ensure-client client) resp))

(defn tools
  "Translate the provider-neutral `tool-specs` into the definitions `client`'s
  provider accepts, ready to put in a chat request.

  Returns nil when the provider cannot call tools or `tool-specs` is empty, so
  callers can simply drop the resulting `:tools` key from a request."
  {:malli/schema [:-> Client [:maybe [:sequential :map]]
                  [:maybe [:sequential :map]]]}
  [client tool-specs]
  (-tools (ensure-client client) tool-specs))

(defn token-usage
  "The [[TokenUsage]] `resp`, a raw response of [[chat]], reports -- or nil
  when the provider reports no counts."
  {:malli/schema [:-> Client [:maybe :map] [:maybe TokenUsage]]}
  [client resp]
  (-token-usage (ensure-client client) resp))

;; ---------------------------------------------------------------------------
;; The provider-neutral pipeline
;; ---------------------------------------------------------------------------

(defn respond
  "Run one chat turn on `client` and return the whole [[Turn]]: the normalized
  assistant `:message`, the provider's raw `:response`, and the normalized
  `:usage` (nil when the provider reports none).

  This is [[complete]] with the bookkeeping kept, for callers that want the
  token counts or the untouched response as well as the reply."
  {:malli/schema [:-> Client ChatSpec Turn]}
  [client spec]
  (let [response (chat client (chat-request client spec))]
    {:message  (assistant-message client response)
     :usage    (token-usage client response)
     :response response}))

(defn complete
  "Run one chat turn on `client` and return just the normalized assistant
  message -- the reply the agent loop works with."
  {:malli/schema [:-> Client ChatSpec #'messages/AssistantMessage]}
  [client spec]
  (:message (respond client spec)))
