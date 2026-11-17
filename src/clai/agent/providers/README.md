# Providers

`clai` talks to LLM services through one small interface, defined in
[`protocol.clj`](protocol.clj). A *provider* is one such service -- Ollama
(local or hosted) and OpenRouter today -- and everything else in this directory
is either shared plumbing or a provider's implementation of that interface.

| file | what it holds |
| --- | --- |
| [`protocol.clj`](protocol.clj) | the `Provider` protocol, its schemas (`ChatSpec`, `TokenUsage`, `Capabilities`, ...), and the provider-neutral pipeline (`respond`, `complete`) |
| [`messages.clj`](messages.clj) | the normalized assistant-message and tool-call shapes every client returns |
| [`http.clj`](http.clj) | the clj-http/JSON plumbing and Ollama's NDJSON streaming, shared by both clients |
| [`ollama.clj`](ollama.clj), [`ollama/`](ollama) | the Ollama client (local and `ollama.com` cloud) |
| [`openrouter.clj`](openrouter.clj) | the OpenRouter client |
| [`../providers.clj`](../providers.clj) | the entry point: a model name to the provider that serves it, and config to one client per provider |

## One turn, end to end

A chat turn moves through four shapes:

```
{provider-neutral turn}       ChatSpec:  {:model :history :tools :chat-opts}
          |  chat-request                      (provider-specific)
{provider request body}       Ollama JSON, OpenAI chat-completions JSON
          |  chat                              (one round trip)
{provider raw response}       Ollama's event map, OpenRouter's completion map
          |  assistant-message + token-usage   (provider-specific)
{:message :usage :response}   Turn: the normalized reply, the token
                              accounting, and the untouched response
```

```clojure
(require '[clai.agent.providers :as providers]
         '[clai.agent.providers.protocol :as protocol])

(def clients (providers/make-clients {:ollama {:host "http://localhost:11434"}}))
(def client  (:client (providers/model-client clients "gemma3" :ollama)))

(protocol/respond client
                  {:model   "gemma3"
                   :history [{:role "user" :content "Why is the sky blue?"}]})
;; => {:message  {:role "assistant" :content "Sunlight scatters ..."}
;;     :usage    {:input-tokens 12 :output-tokens 40 :total-tokens 52
;;                :cost nil
;;                :raw {:prompt_eval_count 12 :eval_count 40}}
;;     :response {...Ollama's own response map...}}
```

`protocol/complete` is the same turn with the bookkeeping dropped, and is what
the agent loop, session titles, and compaction use:

```clojure
(protocol/complete client {:model   "gemma3"
                           :history [{:role "user" :content "ping"}]})
;; => {:role "assistant" :content "pong"}
```

A `ChatSpec` is the *whole* turn as `clai` stores it: the bare model name, the
session history (as written to the database), the tool definitions from
`clai.agent.tools.registry/specs`, and the agent's `:chat-opts` (`:options`,
`:think`, `:format` from `clai.edn`). Each provider converts that into its own
wire format, including the tool calls in the history: Ollama already speaks the
stored shape, while OpenRouter re-encodes tool arguments as JSON strings and
adds the `tool_call_id` its tool messages require.

## The protocol

```clojure
(defprotocol Provider
  (-provider-id       [client])
  (-capabilities      [client])
  (-chat-request      [client spec])
  (-chat              [client req])
  (-assistant-message [client resp])
  (-tools             [client tool-specs])
  (-token-usage       [client resp]))
```

The `-` methods are the extension points. The functions without the dash are
the public API -- they check that the client really implements the protocol and
carry malli schemas -- and they are what the rest of `clai` calls:

| function | answer |
| --- | --- |
| `provider-id` | which provider is this client? (`:ollama`, `:openrouter`) |
| `capabilities` | what can it do, and what does it report? |
| `chat-request` | the provider's request body for a neutral `ChatSpec` |
| `chat` | send one request, return the provider's raw response |
| `assistant-message` | normalize a raw response into an assistant message |
| `tools` | the provider's dialect for `tool-specs`, or nil |
| `token-usage` | the normalized `TokenUsage` a raw response reports, or nil |
| `respond` | the whole `Turn`: `{:message :usage :response}` |
| `complete` | just the `:message` of a turn |

All of them take the client first, so a turn reads left to right and composes
with `->`.

### Capabilities

```clojure
(protocol/capabilities client)
;; Ollama
{:provider :ollama
 :streaming true
 :tools   {:supported? true :format :ollama :parallel nil}
 :usage   {:tokens? true :cost? false :cached-tokens? false
           :reasoning-tokens? false}
 :reasoning true :images true :structured-output true}

;; OpenRouter
{:provider :openrouter
 :streaming true
 :tools   {:supported? true :format :openai :parallel true}
 :usage   {:tokens? true :cost? true :cached-tokens? true
           :reasoning-tokens? true}
 :reasoning true :images true :structured-output true}
```

`protocol/supports-tools?` and `protocol/supports-usage?` are the two questions
worth asking in a condition.

### Token usage

A `TokenUsage` is one flat map with every field optional. `nil` means "this
provider does not report it", and `:raw` keeps whatever the provider said for
the numbers `clai` does not model:

| field | Ollama | OpenRouter |
| --- | --- | --- |
| `:input-tokens` | `prompt_eval_count` | `usage.prompt_tokens` |
| `:output-tokens` | `eval_count` | `usage.completion_tokens` |
| `:total-tokens` | input + output | `usage.total_tokens` |
| `:cached-input-tokens` | nil | `usage.prompt_tokens_details.cached_tokens` |
| `:reasoning-tokens` | nil | `usage.completion_tokens_details.reasoning_tokens` |
| `:cost` | nil (Ollama reports timings, not money) | `usage.cost` in USD |
| `:raw` | the `*_count`/`*_duration` fields | the `:usage` map |

A streamed Ollama event carries no counts until the final `done: true`
event, so `token-usage` returns nil for every partial event.

## From the REPL

```clojure
(require '[clai.agent.providers :as providers]
         '[clai.agent.providers.ollama :as ollama]
         '[clai.agent.providers.openrouter :as openrouter]
         '[clai.agent.providers.protocol :as protocol]
         '[clai.config :as config])

;; One client per provider, from clai.edn plus the provider environment
;; variables (OLLAMA_HOST, OLLAMA_API_KEY, OPENROUTER_API_KEY, ...).
(def clients (providers/make-clients (config/load-config)))

;; Which provider serves a model name? "gemma3" falls back to the default
;; provider; "openrouter:..." and any name containing "/" are OpenRouter's.
(providers/model-spec "openrouter:anthropic/claude-sonnet-4" :ollama)
;; => {:provider :openrouter :model "anthropic/claude-sonnet-4"}

;; Then chat with whichever client that resolver picked.
(let [{:keys [client model]} (providers/model-client
                              clients "openrouter:anthropic/claude-sonnet-4" :ollama)]
  (providers/respond client
                     {:model   model
                      :history [{:role "user" :content "ping"}]}))

;; Ask a provider what it supports, and translate tool definitions yourself.
(providers/capabilities (:openrouter clients))
(providers/tools (:ollama clients)
                 [{:function {:name "read_file" :parameters {:type "object"}}}])

;; The provider namespaces are also usable on their own, including streaming.
(ollama/chat (ollama/make-client)
             {:model "gemma3" :messages [{:role "user" :content "hi"}]})
;; => lazy sequence of NDJSON event maps; pass :stream false for one map.

(openrouter/token-usage {:usage {:prompt_tokens 5 :completion_tokens 2}})
;; => {:input-tokens 5 :output-tokens 2 :total-tokens 7 ...}
```

`protocol/respond` and `protocol/complete` always ask for a single reply, even
from a provider that can stream (both providers report `:streaming true`);
streaming stays available by calling the provider's own `chat`.

## Features a provider lacks

Nothing in the interface pretends. A feature a provider does not have is
`false`, `nil`, or an absent field, and callers check for it instead of asking
which provider answered:

- Ollama reports no cost, so `[:usage :cost?]` is `false` and every
  `TokenUsage` has `:cost nil` for it.
- OpenRouter has no local model management; that stays in
  `clai.agent.providers.ollama` rather than being faked in the interface.
- A provider without tool calling would return `nil` from `tools` and
  `{:supported? false}` from `[:tools :capabilities]`, and its requests simply
  carry no `:tools` key.

## Adding a provider

1. Write `myprovider.clj` with a `Client` record and a `make-client` that reads
   its options (environment variables and defaults, validated with
   `util/validate!`), the way `ollama.clj` and `openrouter.clj` do.
2. Implement the wire half as plain functions: `request-body` (a `ChatSpec` to
   the provider's request body), `chat` (one request, the raw response map),
   `assistant-message` (the raw response to `messages/AssistantMessage`), and a
   `capabilities` map. Validate requests against a malli schema in a
   `myprovider/schemas.clj`; reuse `clai.agent.providers.http/perform` for the
   HTTP call.
3. Add `token-usage`, mapping whatever the provider reports onto `TokenUsage`
   and leaving what it does not report `nil`. Use
   `messages/tool-definitions` when the provider accepts the standard
   `{:type "function" :function {...}}` shape, and write your own translation
   when it does not.
4. Connect the two halves with `extend-type` at the end of the namespace:

   ```clojure
   (extend-type Client
     protocol/Provider
     (-provider-id       [_] :myprovider)
     (-capabilities      [_] capabilities)
     (-chat-request      [_ spec] (request-body spec))
     (-chat              [client req] (chat client req))
     (-assistant-message [_ resp] (assistant-message resp))
     (-tools             [_ tool-specs] (messages/tool-definitions tool-specs))
     (-token-usage       [_ resp] (token-usage resp)))
   ```

5. Register it in [`../providers.clj`](../providers.clj): the `Provider` enum,
   `provider-names`, and a `make-client` branch that forwards the provider's
   own options. Model-name resolution follows automatically -- `<provider>:`
   prefixes are recognized from `provider-names`, while the `/` in a model name
   keeps meaning OpenRouter (see `model-spec`).
6. Finish the wiring: a `<provider>-config` sub-map in `clai.config/config` if
   it takes options, a label in `clai.agent.providers.http/provider-labels` for
   log lines and errors, and the namespace in the `clai.instrument` require
   list so its `:malli/schema` metadata gets collected.
7. Test it the way the other providers are tested: stub
   `clj-http.client/request` and assert on the request body the client builds
   (see [`test/clai/agent/providers/ollama_test.clj`](../../../../test/clai/agent/providers/ollama_test.clj)),
   or `reify` a `protocol/Provider` for a pure stub client.

Run `lein test` from the project root when you are done.
