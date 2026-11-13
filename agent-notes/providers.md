# LLM providers (Ollama + OpenRouter)

## Where

- `src/clai/agent/providers.clj` — provider registry and the provider-neutral
  chat turn (`model-spec`, `model-client`, `chat-request`, `chat`,
  `assistant-message`, `complete`, `make-clients`). Re-exports the message
  helpers via `util/import-vars`.
- `src/clai/agent/providers/ollama.clj` (+ `ollama/` sub-namespaces) — Ollama
  client, unchanged API. `Client` now carries `:provider :ollama`.
- `src/clai/agent/providers/openrouter.clj` — OpenRouter chat-completions
  client (`Client` record, `make-client`, `chat`, `assistant-message`,
  `history->messages`, `request-body`, `chat-options`).
- `src/clai/agent/providers/http.clj` — the old `ollama.http`, moved up and
  made provider-aware (`provider-label`, `throw-api-error` takes a client,
  error bodies parsed for both Ollama's `{"error": "..."}` and OpenRouter's
  `{"error": {"message": ...}}`).
- `src/clai/agent/providers/messages.clj` — the old `ollama.messages`, moved
  up: the normalized assistant message/tool-call shape and its schemas.
- `src/clai/config.clj` — `:provider`, `:openrouter` sub-map,
  `resolve-provider`, `make-clients` (replaces `make-ollama-client`).
- `src/clai/agent/core.clj` — `make-agent` (renamed from `ollama-agent`) holds
  `:clients`, `:client`, `:provider`, `:default-provider`; `apply-agent-config`
  re-resolves the provider when a session configuration names a model.
- `src/clai/agent/tools/builtin/subagent.clj` — resolves the child's client
  from `:clients` so a sub-agent can run on another provider.
- Tests: `test/clai/agent/providers_test.clj`,
  `test/clai/agent/providers/openrouter_test.clj`.
- User docs: `agent-wiki/providers.md`.

## Decisions

**Model name selects the provider.** `clai.agent.providers/model-spec`:
`<provider>:` prefix wins (`ollama:`, `openrouter:`); otherwise a `/` in the id
means OpenRouter (their ids are always `vendor/model`); otherwise the configured
default (`:provider` / `CLAI_PROVIDER` / `:ollama`). Ollama tags (`gemma3:7b`)
are not mistaken for prefixes because only supported provider names count. An
Ollama model with a `/` is pinnable with `ollama:`.

**One normalized message shape, defined by the event log.** Every provider
converts its reply into the shape Ollama already used
(`{:role :content :thinking :tool_calls [{:id :function {:name :arguments
<map>}}]}`), and every provider converts that shape back into its own wire
format on the way out (`openrouter/history->messages`). Consequences: stored
session history is provider-independent, `clai.agent.tools.invoke` works
unchanged, and `clai.agent.providers.ollama.prompt/history->updates` replays an
OpenRouter conversation correctly. That is why `messages.clj` lives at the
providers level and not under `ollama/`.

**Dispatch on data, not a protocol.** Clients are records with a `:provider`
keyword and `clai.agent.providers` has one `case` per operation. Adding a
provider is a new namespace plus cases. The `case` calls the provider's public
Vars (`ollama/chat`, `openrouter/chat`) rather than capturing function values,
so `with-redefs` in tests still intercepts requests.

**Both clients are always built.** `config/make-clients` builds Ollama and
OpenRouter clients unconditionally, so a session whose stored agent
configuration names another provider's model switches without rebuilding the
agent. Building an OpenRouter client without a key is harmless; `chat` raises
`OpenRouter is not configured: ...` so the failure names the fix.

**Provider-neutral chat options.** `:options`/`:think`/`:format` stay as they
were in `clai.edn`. Ollama gets them verbatim. OpenRouter keeps the known
OpenAI-compatible `:options` keys, maps `:think true` to `{:reasoning {:enabled
true}}`, and maps `:format` to `response_format` (`"json"` → `json_object`, a
map → `json_schema`). Ollama-only option keys are dropped rather than sent and
rejected.

**OpenRouter is non-streaming.** The agent loop asks for one reply at a time
(`:stream false`), so `openrouter/chat` forces `:stream false` and returns one
map; there is no SSE reader. OpenRouter also reports failures inside HTTP 200
bodies (`{:error {:message ...}}`), which `chat` raises like a HTTP error.

**Renames.** `ollama-agent` → `make-agent` (it is no longer Ollama-specific);
`config/make-ollama-client` → `config/make-clients`;
`clai.agent.providers.ollama.http` → `clai.agent.providers.http`;
`clai.agent.providers.ollama.messages` → `clai.agent.providers.messages`.
No compatibility shims: there are no users.
