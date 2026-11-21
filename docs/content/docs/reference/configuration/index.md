---
title: "Configuration"
description: "Reference for clai's EDN configuration keys, model and provider resolution, PostgreSQL and OpenProject settings, and every supported environment variable."
summary: "Every key, every environment variable, and what wins."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 1
toc: true
---

## File discovery

The first file that exists wins:

1. `$CLAI_CONFIG`
2. `./clai.edn`
3. `~/.config/clai/config.edn` (under `$XDG_CONFIG_HOME` when set)

A missing, unreadable, or non-map file falls back to the built-in defaults
rather than failing startup. `clai.config/candidates` and
`clai.config/config-path` report what would be loaded.

## Keys

Every key is optional. Unknown keys are allowed and ignored.

| Key                    | Type                       | Default                 | Purpose                                                             |
| ---------------------- | -------------------------- | ----------------------- | ------------------------------------------------------------------- |
| `:model`               | non-empty string           | `gemma3`                | Model name, optionally prefixed with a provider.                    |
| `:provider`            | `:ollama` / `:openrouter`  | `:ollama`               | Provider for a model name that does not name one.                   |
| `:system-prompt`       | string                     | built-in prompt         | Prepended to every session's history.                               |
| `:max-tool-rounds`     | positive integer           | `8`                     | Consecutive tool-call rounds before the agent gives up.             |
| `:compact-after-tokens`| non-negative integer       | compaction default      | History budget; `0` disables compaction.                            |
| `:options`             | map                        | `{}`                    | Provider-neutral chat options.                                      |
| `:think`               | boolean                    | provider default        | Ollama `:think` / OpenRouter reasoning.                             |
| `:format`              | string or map              | none                    | Ollama `:format` / OpenRouter `response_format`.                    |
| `:ollama`              | map                        | client defaults         | Ollama connection settings.                                         |
| `:openrouter`          | map                        | client defaults         | OpenRouter connection settings.                                     |
| `:postgres`            | map                        | development database    | Session, note, plugin, and auth storage.                            |
| `:openproject`         | map                        | disabled                | Enables the OpenProject tools.                                      |
| `:tools`               | vector of strings          | every built-in tool     | Tool ids to expose; `[]` disables tools.                            |
| `:plugins`             | vector of strings          | every enabled plugin    | Plugin names or database ids; `[]` disables plugins.                |

## Model and provider resolution

`clai.config/resolve-model` checks `:model`, then `$CLAI_MODEL`, then
`gemma3`. `clai.config/resolve-provider` checks `:provider`, then
`$CLAI_PROVIDER`, then `:ollama`; an unknown provider is logged and replaced
by the default.

A model name can always pick its own provider:

| Name                        | Provider   | Model                       |
| --------------------------- | ---------- | --------------------------- |
| `gemma3`                    | `:provider`| `gemma3`                    |
| `ollama:gemma3:7b`          | Ollama     | `gemma3:7b`                 |
| `openrouter:gemma3:7b`      | OpenRouter | `gemma3:7b`                 |
| `anthropic/claude-sonnet-4` | OpenRouter | `anthropic/claude-sonnet-4` |

An id containing `/` is treated as an OpenRouter id.

### `:ollama`

| Key       | Environment        | Default                 |
| --------- | ------------------ | ----------------------- |
| `:host`   | `OLLAMA_HOST`      | `http://localhost:11434`|
| `:api-key`| `OLLAMA_API_KEY`   | none                    |
| `:timeout`| none               | client default          |

### `:openrouter`

| Key         | Environment            | Default                           |
| ----------- | ---------------------- | --------------------------------- |
| `:api-key`  | `OPENROUTER_API_KEY`   | none                              |
| `:base-url` | `OPENROUTER_BASE_URL`  | `https://openrouter.ai/api/v1`    |
| `:timeout`  | none                   | client default                    |
| `:referer`  | none                   | none                              |
| `:title`    | none                   | none                              |

`:referer` and `:title` are optional attribution headers OpenRouter shows on
its activity page.

## `:postgres`

The connection is opened lazily and `schema/schema.sql` is applied on the first
session operation. Omitted keys fall back to `CLAI_POSTGRES_*`, then the
standard `PG*` variables, then the development defaults.

| Key                  | Environment                                            | Default      |
| -------------------- | ------------------------------------------------------ | ------------ |
| `:url`               | `CLAI_POSTGRES_URL`, `DATABASE_URL`                    | none         |
| `:host`              | `CLAI_POSTGRES_HOST`, `PGHOST`                         | `localhost`  |
| `:port`              | `CLAI_POSTGRES_PORT`, `PGPORT`                         | `5432`       |
| `:database`          | `CLAI_POSTGRES_DATABASE`, `CLAI_POSTGRES_DB`, `PGDATABASE` | `clai`   |
| `:user`              | `CLAI_POSTGRES_USER`, `PGUSER`                         | `clai`       |
| `:password`          | `CLAI_POSTGRES_PASSWORD`, `PGPASSWORD`                 | `secret`     |
| `:ssl-mode`          | `CLAI_POSTGRES_SSL_MODE`, `CLAI_POSTGRES_SSLMODE`, `PGSSLMODE` | client default |
| `:connect-timeout`   | `CLAI_POSTGRES_CONNECT_TIMEOUT`                        | client default |
| `:socket-timeout`    | `CLAI_POSTGRES_SOCKET_TIMEOUT`                         | client default |
| `:application-name`  | `CLAI_POSTGRES_APPLICATION_NAME`                       | `clai`       |

`:url` is a full JDBC URL and replaces the host/port/database form. Its
credentials replace the user/password defaults.

## `:openproject`

The tools are only enabled when this map (or the environment) provides a URL
and credentials. Omitted keys fall back to `OPENPROJECT_*` variables.

| Key         | Environment                                |
| ----------- | ------------------------------------------ |
| `:url`      | `OPENPROJECT_URL`, `OPENPROJECT_BASE_URL`  |
| `:base-url` | `OPENPROJECT_URL`, `OPENPROJECT_BASE_URL`  |
| `:api-key`  | `OPENPROJECT_API_KEY`, `OPENPROJECT_TOKEN` |
| `:token`    | `OPENPROJECT_API_KEY`, `OPENPROJECT_TOKEN` |
| `:username` | `OPENPROJECT_USERNAME`                     |
| `:password` | `OPENPROJECT_PASSWORD`                     |
| `:project`  | `OPENPROJECT_PROJECT`                      |
| `:type`     | `OPENPROJECT_TYPE`                         |
| `:timeout`  | `OPENPROJECT_TIMEOUT`                      |

`:url` may be the instance root or its `/api/v3` API root. `:project` is the
default project for new work packages, and `:type` the default type.

## Other environment variables

| Variable                    | Purpose                                              |
| --------------------------- | ---------------------------------------------------- |
| `CLAI_CONFIG`               | Highest-priority config file path                    |
| `CLAI_MODEL`                | Default model when `:model` is absent                |
| `CLAI_PROVIDER`             | Default provider when `:provider` is absent          |
| `CLAI_MAX_TOOL_ROUNDS`      | Tool-round cap                                       |
| `CLAI_COMPACT_AFTER_TOKENS` | History token budget (`0` disables compaction)       |
| `CLAI_WEB_ROOT`             | Directory the daemon serves the React build from     |
| `CLAI_DAEMON_URL`           | Daemon URL used by the Vite web dev proxy            |

## Related runtime settings

- `clai.config/chat-options` selects `:options`, `:think`, and `:format` for a
  provider.
- `clai.config/tool-specs` resolves the model-facing tool definitions from
  `:tools` and an agent's registry.
- `clai.config/make-clients` builds one provider client per configured
  provider.
- `clai.config/make-postgres-client` builds the storage client.

Stored agent configurations in PostgreSQL override these file defaults for the
sessions that select them.
