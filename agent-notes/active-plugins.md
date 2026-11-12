# Active plugins

## Decision

`agent_config.plugins` is a `TEXT[]` of plugin names (database ids are also
accepted). It follows the same NULL/empty-array convention as `tools`:

- `NULL` means every enabled plugin registered at startup.
- `[]` means no plugins for sessions using the configuration.
- A list means only the named plugins run.

The config file's `:plugins` key supplies the agent-wide default. A session's
selected agent configuration overrides it. The agent map carries the resolved
list as `:plugins`.

## Implementation

- Schema: `schema/schema.sql` declares `agent_config.plugins` and includes an
  `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` for existing databases.
- Storage: `clai.agent.configs` normalizes, stores, and reads the list with
  the same double-JSON-parameter trick used for `tools`.
- Runtime: `clai.plugins/resolve-hooks` filters registry entries by name or
  id. `nil` disables filtering, `[]` disables plugins. The selection reaches
  hooks through:
  - `:plugins` on the system-prompt context (`assemble-system-prompt`),
  - `:plugins` on the session map (`user-prompt-*`, `agent-message-*`),
  - `:plugins` in namespace-builder options.
- Core: `clai.agent.core` resolves the list in `build-agent`,
  `apply-agent-config`, and `new-session!`, and attaches it to the session
  used by `run-prompt!`.
- HTTP/UI: `GET /api/plugins`, the `list_plugins` WebSocket message, and the
  Plugins section of the agent-configuration dialog in `web/` expose and edit
  the setting.

## Why per session instead of per process

The plugin registry (and its jar class loaders) stays process-wide because
Clojure cannot unload namespaces. Filtering at hook resolution time keeps one
registry while letting agent configurations choose different active sets, just
like `tools` narrows the registry per session.
