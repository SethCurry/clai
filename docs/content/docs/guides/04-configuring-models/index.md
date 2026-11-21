---
title: "Configuring Models and Providers"
description: "Resolve clai's configuration file, choose Ollama or OpenRouter models, tune chat options and compaction, and add per-project instructions with AGENTS.md."
summary: "One EDN file, two providers, and a documented precedence chain."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 4
toc: true
---

`config.example.edn` is the reference, and it is commented key by key. Copy it
somewhere clai will find it:

```sh
cp config.example.edn ./clai.edn
```

## Where configuration comes from

The first file that exists wins:

1. `$CLAI_CONFIG`
2. `./clai.edn`
3. `~/.config/clai/config.edn` (respecting `XDG_CONFIG_HOME`)

Anything a file omits falls back to an environment variable and then to the
built-in default. Settings are read individually, so a config file can set the
model while the database password comes from the environment.

```clojure
{:model    "gemma3"     ; or "openrouter:anthropic/claude-sonnet-4"
 :provider :ollama      ; default provider for a model name that names none
 :system-prompt "You are a concise, helpful coding agent."
 :max-tool-rounds 8
 :compact-after-tokens 250000
 :tools    ["read_file" "write_file" "list_directory" "run_command"
            "eval_clojure" "spawn_agent" "notes_list" "notes_search"]
 :options  {:temperature 0.7}}
```

## Choosing a provider

A model name picks its own provider when it can:

| Model name                       | Provider     | Model sent to it         |
| -------------------------------- | ------------ | ------------------------ |
| `gemma3`                         | `:provider`  | `gemma3`                 |
| `ollama:gemma3:7b`               | Ollama       | `gemma3:7b`              |
| `openrouter:gemma3:7b`           | OpenRouter   | `gemma3:7b`              |
| `anthropic/claude-sonnet-4`      | OpenRouter   | `anthropic/claude-sonnet-4` |

Anything else uses `:provider` (default `:ollama`). An id containing `/` is
treated as an OpenRouter id. Both providers can be configured at once, and a
stored agent configuration can name a model from either.

### Ollama

```clojure
:ollama {:host     "http://localhost:11434"
         :api-key  "sk-..."          ; only needed for https://ollama.com
         :timeout  600000}           ; milliseconds
```

Omitted keys fall back to `OLLAMA_HOST`, `OLLAMA_API_KEY`, and the client
defaults.

### OpenRouter

```clojure
:openrouter {:api-key  "sk-or-v1-..."
             :base-url "https://openrouter.ai/api/v1"
             :timeout  600000
             :referer  "https://example.com"
             :title    "clai"}
```

`:api-key` falls back to `$OPENROUTER_API_KEY` and `:base-url` to
`$OPENROUTER_BASE_URL`. `:referer` and `:title` are optional attribution
headers OpenRouter shows on its activity page.

## Chat options

`:options`, `:think`, and `:format` are provider-neutral. Each provider
translates them into its own wire format: Ollama forwards them to `/api/chat`
verbatim, while OpenRouter keeps the OpenAI-compatible ones (`:temperature`,
`:top_p`, `:max_tokens`, ...) and drops Ollama-only ones (`:num_ctx`, ...).

```clojure
:options {:temperature 0.7}
:think   false       ; Ollama :think / OpenRouter reasoning
;; :format "json"    ; Ollama :format / OpenRouter response_format
```

## Compaction and tool rounds

`:max-tool-rounds` caps consecutive tool-call rounds before the agent gives up
(default 8). `:compact-after-tokens` is the history budget: once a session is
estimated to be over it, the agent summarizes the conversation in one request
and appends a `compaction` event.

Compaction keeps the system prompt (with the summary appended), the newest
messages, and the turn in progress. It never interrupts work mid-turn, and the
event log remains a complete audit trail. A budget of `0` disables compaction.

## Tools and plugins

`:tools` selects the built-in tools the model may call. Omit the key to enable
every built-in tool; set it to `[]` to disable tools entirely. Unknown ids are
skipped, and the order is the order the model sees.

`:plugins` selects which registered plugins run. Omit it (or use `null`) for
every enabled plugin, `[]` for none, or a list of plugin names or database ids.
A session's stored agent configuration overrides both defaults. See
[Writing plugins](/docs/guides/writing-plugins/).

## Per-project instructions

An `AGENTS.md` in a new session's working directory is appended to the system
prompt, so per-project instructions travel with the project. The file is read
when the session starts; it is not re-read on every prompt.

## Environment variables

| Variable                    | Used for                                              |
| --------------------------- | ----------------------------------------------------- |
| `CLAI_CONFIG`               | Config file path (highest-priority candidate)         |
| `CLAI_MODEL`                | Default model when the file does not set `:model`     |
| `CLAI_PROVIDER`             | Default provider for unprefixed model names           |
| `CLAI_MAX_TOOL_ROUNDS`      | Tool-round cap                                        |
| `CLAI_COMPACT_AFTER_TOKENS` | History token budget (`0` disables compaction)        |
| `OLLAMA_HOST`               | Ollama server URL                                     |
| `OLLAMA_API_KEY`            | Ollama cloud API key                                  |
| `OPENROUTER_API_KEY`        | OpenRouter API key                                    |
| `OPENROUTER_BASE_URL`       | OpenRouter API base URL                               |
| `CLAI_POSTGRES_*` / `PG*`   | PostgreSQL connection settings                        |
| `OPENPROJECT_*`             | OpenProject tools                                     |
| `CLAI_WEB_ROOT`             | Directory the daemon serves the React build from      |
| `CLAI_DAEMON_URL`           | Daemon URL used by the Vite dev proxy                 |

The file wins over the environment. The full key and environment-variable
reference is in [Configuration](/docs/reference/configuration/).
