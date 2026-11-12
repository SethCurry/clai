import { useState } from 'react'

const EMPTY_FORM = {
  configId: '',
  name: '',
  model: '',
  systemPrompt: '',
  maxToolRounds: '',
  think: '',
  options: '{}',
  format: '',
  toolsMode: 'default',
  toolIds: '',
}

function configToForm(config) {
  const tools = config.tools
  const toolsMode =
    tools == null ? 'default' : tools.length === 0 ? 'none' : 'custom'
  return {
    configId: config.configId,
    name: config.name ?? '',
    model: config.model ?? '',
    systemPrompt: config['system-prompt'] ?? '',
    maxToolRounds:
      config['max-tool-rounds'] == null
        ? ''
        : String(config['max-tool-rounds']),
    think: config.think == null ? '' : String(config.think),
    options:
      config.options && Object.keys(config.options).length > 0
        ? JSON.stringify(config.options, null, 2)
        : '{}',
    format:
      config.format == null
        ? ''
        : typeof config.format === 'string'
          ? config.format
          : JSON.stringify(config.format, null, 2),
    toolsMode,
    toolIds: toolsMode === 'custom' ? tools.join(', ') : '',
  }
}

function toolsLabel(config) {
  if (config.tools == null) return 'agent default tools'
  if (config.tools.length === 0) return 'no tools'
  return `${config.tools.length} tool${config.tools.length === 1 ? '' : 's'}`
}

function parseOptions(text) {
  const trimmed = text.trim()
  if (!trimmed) return {}
  const value = JSON.parse(trimmed)
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Model options must be a JSON object.')
  }
  return value
}

function parseFormat(text) {
  const trimmed = text.trim()
  if (!trimmed) return null
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return JSON.parse(trimmed)
    } catch {
      throw new Error('Response format looks like JSON but could not be parsed.')
    }
  }
  return trimmed
}

function parseTools(form) {
  if (form.toolsMode === 'default') return null
  if (form.toolsMode === 'none') return []
  const tools = form.toolIds
    .split(/[\s,]+/)
    .map((id) => id.trim())
    .filter(Boolean)
  if (tools.length === 0) {
    throw new Error('Add at least one tool id, or choose "Agent default".')
  }
  return tools
}

function formToConfig(form) {
  const name = form.name.trim()
  if (!name) throw new Error('A configuration name is required.')

  let maxToolRounds = null
  if (form.maxToolRounds.trim()) {
    const value = Number(form.maxToolRounds)
    if (!Number.isInteger(value) || value < 1) {
      throw new Error('Max tool rounds must be a positive whole number.')
    }
    maxToolRounds = value
  }

  return {
    name,
    model: form.model.trim() || null,
    'system-prompt':
      form.systemPrompt.trim() === '' ? null : form.systemPrompt,
    'max-tool-rounds': maxToolRounds,
    think: form.think === '' ? null : form.think === 'true',
    options: parseOptions(form.options),
    format: parseFormat(form.format),
    tools: parseTools(form),
  }
}

/**
 * Create, edit, and delete stored agent configurations. The daemon serves the
 * same shapes as `/api/agent-configs`; this dialog keeps the sidebar's config
 * picker in sync through the hook that owns it.
 */
export function AgentConfigManager({ configs, onSave, onDelete, onClose }) {
  const [form, setForm] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const updateField = (field, value) => {
    setForm((previous) => ({ ...previous, [field]: value }))
  }

  const startCreate = () => {
    setError('')
    setForm({ ...EMPTY_FORM })
  }

  const startEdit = (config) => {
    setError('')
    setForm(configToForm(config))
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      await onSave(formToConfig(form), form.configId || null)
      setForm(null)
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  const remove = async (config) => {
    if (
      !window.confirm(`Delete agent configuration "${config.name}"?`)
    ) {
      return
    }
    setError('')
    setBusy(true)
    try {
      await onDelete(config.configId)
      if (form?.configId === config.configId) setForm(null)
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section
        className="modal config-manager"
        role="dialog"
        aria-modal="true"
        aria-labelledby="config-manager-title"
      >
        <header className="modal__header">
          <div>
            <h2 id="config-manager-title">Agent configurations</h2>
            <p>Reusable model, prompt, and tool settings for new sessions.</p>
          </div>
          <button
            type="button"
            className="modal__close"
            aria-label="Close agent configuration manager"
            onClick={onClose}
          >
            ×
          </button>
        </header>

        <div className="config-manager__body">
          <div className="config-manager__list">
            <button
              type="button"
              className="button button--primary"
              onClick={startCreate}
              disabled={busy}
            >
              + New configuration
            </button>

            {configs.length === 0 ? (
              <p className="config-manager__empty">
                No stored configurations yet. Create one to reuse a model,
                prompt, and tool set across sessions.
              </p>
            ) : (
              configs.map((config) => {
                const active = form?.configId === config.configId
                return (
                  <div
                    className={`config-row${active ? ' config-row--active' : ''}`}
                    key={config.configId}
                  >
                    <button
                      type="button"
                      className="config-row__select"
                      onClick={() => startEdit(config)}
                    >
                      <span className="config-row__name">{config.name}</span>
                      <span className="config-row__meta">
                        {config.model || 'daemon model'} · {toolsLabel(config)}
                      </span>
                    </button>
                    <button
                      type="button"
                      className="config-row__delete"
                      aria-label={`Delete agent configuration ${config.name}`}
                      title="Delete configuration"
                      disabled={busy}
                      onClick={() => remove(config)}
                    >
                      ×
                    </button>
                  </div>
                )
              })
            )}
          </div>

          <div className="config-manager__editor">
            {form ? (
              <form className="config-form" onSubmit={handleSubmit}>
                <div className="config-form__title">
                  {form.configId
                    ? `Edit #${form.configId}`
                    : 'New configuration'}
                </div>

                <label className="config-form__field">
                  <span>Name</span>
                  <input
                    value={form.name}
                    onChange={(event) =>
                      updateField('name', event.target.value)
                    }
                    placeholder="fast"
                    autoFocus
                    required
                  />
                </label>

                <label className="config-form__field">
                  <span>Model</span>
                  <input
                    value={form.model}
                    onChange={(event) =>
                      updateField('model', event.target.value)
                    }
                    placeholder="Daemon default"
                  />
                </label>

                <label className="config-form__field">
                  <span>System prompt</span>
                  <textarea
                    rows={4}
                    value={form.systemPrompt}
                    onChange={(event) =>
                      updateField('systemPrompt', event.target.value)
                    }
                    placeholder="Daemon default"
                  />
                </label>

                <div className="config-form__row">
                  <label className="config-form__field">
                    <span>Max tool rounds</span>
                    <input
                      type="number"
                      min="1"
                      step="1"
                      value={form.maxToolRounds}
                      onChange={(event) =>
                        updateField('maxToolRounds', event.target.value)
                      }
                      placeholder="Daemon default"
                    />
                  </label>

                  <label className="config-form__field">
                    <span>Thinking</span>
                    <select
                      value={form.think}
                      onChange={(event) =>
                        updateField('think', event.target.value)
                      }
                    >
                      <option value="">Daemon default</option>
                      <option value="true">Enabled</option>
                      <option value="false">Disabled</option>
                    </select>
                  </label>
                </div>

                <label className="config-form__field">
                  <span>Model options (JSON)</span>
                  <textarea
                    className="config-form__code"
                    rows={3}
                    value={form.options}
                    onChange={(event) =>
                      updateField('options', event.target.value)
                    }
                    spellCheck={false}
                  />
                </label>

                <label className="config-form__field">
                  <span>Response format</span>
                  <input
                    value={form.format}
                    onChange={(event) =>
                      updateField('format', event.target.value)
                    }
                    placeholder={'Daemon default, "json", or a JSON schema'}
                  />
                </label>

                <div className="config-form__field">
                  <span>Tools</span>
                  <select
                    value={form.toolsMode}
                    onChange={(event) =>
                      updateField('toolsMode', event.target.value)
                    }
                  >
                    <option value="default">Agent default tools</option>
                    <option value="none">No tools</option>
                    <option value="custom">Selected tools</option>
                  </select>
                  {form.toolsMode === 'custom' && (
                    <input
                      className="config-form__code"
                      value={form.toolIds}
                      onChange={(event) =>
                        updateField('toolIds', event.target.value)
                      }
                      placeholder="read_file, run_command, write_file"
                    />
                  )}
                </div>

                {error && <p className="config-form__error">{error}</p>}

                <div className="config-form__actions">
                  {form.configId && (
                    <button
                      type="button"
                      className="button button--danger"
                      disabled={busy}
                      onClick={() =>
                        remove(
                          configs.find(
                            (config) => config.configId === form.configId,
                          ) || { configId: form.configId, name: form.name },
                        )
                      }
                    >
                      Delete
                    </button>
                  )}
                  <span className="config-form__spacer" />
                  <button
                    type="button"
                    className="button button--quiet"
                    disabled={busy}
                    onClick={() => setForm(null)}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className="button button--primary"
                    disabled={busy}
                  >
                    {busy ? 'Saving…' : 'Save configuration'}
                  </button>
                </div>
              </form>
            ) : (
              <div className="config-manager__placeholder">
                Select a configuration to edit, or create a new one.
              </div>
            )}
          </div>
        </div>
      </section>
    </div>
  )
}
