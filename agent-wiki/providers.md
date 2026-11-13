# Models and providers (Ollama + OpenRouter)

`clai` can talk to more than one LLM provider. Today it ships two:

| Provider     | Default endpoint                     | Client |
| ------------ | ------------------------------------ | ------ |
| `ollama`     | `http://localhost:11434` (or Ollama Cloud, `https://ollama.com`) | `clai.agent.providers.ollama` |
| `openrouter` | `https://openrouter.ai/api/v1`       | `clai.agent.providers.openrouter` |

Both can be configured at the same time, and **the model name picks the
provider** — for every session, and even per session through a stored agent
configuration.

## Configure both providers

In `clai.edn` (see [config.example.edn](../config.example.edn)):

```clojure
{:provider   :ollama          ; provider for model names that do not name one
 :model      "gemma3"         ; the model a new agent starts with

 :ollama     {:host    "http://localhost:11434"
              :timeout 600000}          ; ms

 :openrouter {:api-key "sk-or-v1-..."   ; or OPENROUTER_API_KEY
              :base-url "https://openrouter.ai/api/v1"
              :timeout 600000
              ;; Optional attribution headers OpenRouter shows on its
              ;; activity page:
              :referer "https://example.com"
              :title   "clai"}}
```

Both sub-maps are optional. The equivalent environment variables are
`OLLAMA_HOST`, `OLLAMA_API_KEY`, `OPENROUTER_API_KEY`, `OPENROUTER_BASE_URL`,
`CLAI_MODEL`, and `CLAI_PROVIDER`. Values in the config file win over the
environment; anything unset falls back to the provider's defaults.

Ollama's hosted cloud is just another Ollama endpoint:

```clojure
{:model  "gpt-oss:120b"
 :ollama {:host "https://ollama.com" :api-key "..."}}
```

## Choose a provider by model name

| Model name                          | Provider     | Model sent to the API  |
| ----------------------------------- | ------------ | ---------------------- |
| `"gemma3"`                          | `:provider` (`:ollama`) | `gemma3`    |
| `"gemma3"` with `:provider :openrouter` | `:openrouter` | `gemma3`         |
| `"ollama:gemma3:7b"`                | `:ollama`    | `gemma3:7b`            |
| `"openrouter:gemma3:7b"`            | `:openrouter`| `gemma3:7b`            |
| `"anthropic/claude-sonnet-4"`       | `:openrouter`| `anthropic/claude-sonnet-4` |
| `"ollama:hf.co/org/model"`          | `:ollama`    | `hf.co/org/model`      |

The rules, in order:

1. A `<provider>:` prefix always wins. Only the two provider names count, so an
   Ollama tag such as `gemma3:7b` is never mistaken for a prefix.
2. A model id with a `/` in it is an OpenRouter id.
3. Everything else uses `:provider` (or `CLAI_PROVIDER`), defaulting to
   `:ollama`.

Point 2 is the convenience case: OpenRouter namespaces every model id with the
upstream provider (`openai/gpt-4o-mini`, `anthropic/claude-sonnet-4`,
`meta-llama/llama-3.3-70b-instruct`, ...), so pasting one in "just works". If
you have an *Ollama* model whose name contains a slash, prefix it with
`ollama:`.

## Switch a running agent

- **Config file / environment:** `:model "openrouter:anthropic/claude-sonnet-4"`
  makes every new session start there.
- **Per session:** store an agent configuration (CLI: the session picker in the
  web UI's *Agent configurations* dialog) whose `model` is
  `openrouter:anthropic/claude-sonnet-4`, and create sessions with it. Sessions
  using that configuration run on OpenRouter; sessions using another
  configuration keep using their own model. `clai` builds both provider clients
  at startup, so switching needs no restart.
- **Sub-agents:** `spawn_agent` takes a `model` argument, so a parent running on
  Ollama can delegate to an OpenRouter model (and the other way around):

  ```
  spawn_agent(prompt: "summarize README.md",
              model: "openrouter:openai/gpt-4o-mini",
              tools: ["read_file"])
  ```

The model chip in the web UI's top bar (and the daemon's `/api/health`) shows
`provider:model` for the agent the daemon started with.

## What is shared between providers

Everything above the wire format: sessions, history, tools, plugins, the
`eval_clojure` REPL, and the web UI.

- **Tool calls** are normalized into one shape. OpenRouter sends arguments as a
  JSON string, Ollama as an object; both become maps before the agent sees
  them, so tools are written once.
- **Reasoning** text is exposed the same way: Ollama's `:thinking` and
  OpenRouter's `:reasoning` both arrive as `agent_thought` events.
- **Chat options** stay provider-neutral in `clai.edn`:

  ```clojure
  {:options {:temperature 0.2}   ; forwarded to both
   :think   true                 ; Ollama :think / OpenRouter reasoning
   :format  "json"}              ; Ollama :format / OpenRouter response_format
  ```

  Ollama gets `:options`/`:think`/`:format` verbatim. OpenRouter keeps the
  OpenAI-compatible keys (`:temperature`, `:top_p`, `:max_tokens`, `:seed`,
  `:stop`, ...) and drops Ollama-only ones (`:num_ctx`, `:repeat_penalty`, ...)
  instead of failing the request.

## Troubleshooting

- **`OpenRouter is not configured: set :openrouter {:api-key ...}`** — the
  model you asked for routes to OpenRouter but no key was found. Add it to
  `clai.edn` or export `OPENROUTER_API_KEY`.
- **`OpenRouter request failed: ...`** — OpenRouter reported an error. Its
  message is passed through (`Insufficient credits`, `No endpoints found`, a
  moderation refusal, ...). OpenRouter sometimes returns these inside an HTTP
  200; `clai` treats them as failures either way.
- **An Ollama model with a `/` went to OpenRouter** — prefix it with `ollama:`.
- **A model you expect on OpenRouter went to Ollama** — give it a full
  OpenRouter id (`vendor/model`) or the `openrouter:` prefix; a bare name with
  no slash is routed to the default provider.

## Adding another provider

`clai.agent.providers` dispatches on the `:provider` key of a client record, so
a new provider is a new namespace with a client record, a `chat` that posts the
provider's request and returns its raw response, an `assistant-message` that
normalizes the reply into `clai.agent.providers.messages`' shape, and a case in
`clai.agent.providers`. The agent loop, tools, and UI need no changes.
