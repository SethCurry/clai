import { useEffect, useMemo, useState } from 'react'
import { formatTimestamp } from '../format.js'
import {
  configToText,
  formToPluginInput,
  pluginStateLabel,
  pluginToForm,
} from '../plugins.js'
import { usePlugins } from '../usePlugins.js'

/**
 * Install or edit one plugin. The caller owns the API call, so the form only
 * has to validate its own fields and surface the failure inline.
 */
function PluginForm({ plugin, onSave, onCancel }) {
  const [form, setForm] = useState(() => pluginToForm(plugin))
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const update = (field, value) => {
    setForm((previous) => ({ ...previous, [field]: value }))
  }

  const submit = async (event) => {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      await onSave(formToPluginInput(form))
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="list-panel">
      <header className="list-panel__head">
        <div>
          <p className="list-page__eyebrow">
            {plugin ? 'edit plugin' : 'new plugin'}
          </p>
          <h3>{plugin ? plugin.name : 'Install a plugin'}</h3>
          <p>
            The jar that holds the plugin, the namespace in it that defines the{' '}
            <code>plugin</code> Var, and the configuration that namespace
            receives.
          </p>
        </div>
      </header>

      <form className="config-form" onSubmit={submit}>
        <label className="config-form__field">
          <span>Name</span>
          <input
            value={form.name}
            onChange={(event) => update('name', event.target.value)}
            placeholder="todays-date"
            autoFocus
            required
          />
        </label>

        <label className="config-form__field">
          <span>Jar path</span>
          <input
            className="config-form__code"
            value={form.jarPath}
            onChange={(event) => update('jarPath', event.target.value)}
            placeholder="/opt/clai/plugins/todays-date.jar"
            spellCheck={false}
            required
          />
        </label>

        <label className="config-form__field">
          <span>Entry namespace</span>
          <input
            className="config-form__code"
            value={form.entryNamespace}
            onChange={(event) => update('entryNamespace', event.target.value)}
            placeholder="clai-plugin-todays-date.plugin"
            spellCheck={false}
            required
          />
        </label>

        <div className="config-form__row">
          <label className="config-form__field">
            <span>Priority</span>
            <input
              type="number"
              step="1"
              value={form.priority}
              onChange={(event) => update('priority', event.target.value)}
              title="Lower priorities run first; plugins with the same priority run in name order."
            />
          </label>

          <label className="config-form__field">
            <span>State</span>
            <select
              value={form.enabled ? 'enabled' : 'disabled'}
              onChange={(event) =>
                update('enabled', event.target.value === 'enabled')
              }
            >
              <option value="enabled">Enabled</option>
              <option value="disabled">Disabled</option>
            </select>
          </label>
        </div>

        <label className="config-form__field">
          <span>Config (JSON)</span>
          <textarea
            className="config-form__code"
            rows={5}
            value={form.config}
            onChange={(event) => update('config', event.target.value)}
            spellCheck={false}
          />
        </label>

        {error && <p className="config-form__error">{error}</p>}

        <div className="config-form__actions">
          <span className="config-form__spacer" />
          <button
            type="button"
            className="button button--quiet"
            disabled={busy}
            onClick={onCancel}
          >
            Cancel
          </button>
          <button type="submit" className="button button--primary" disabled={busy}>
            {busy ? 'Saving…' : plugin ? 'Save plugin' : 'Install plugin'}
          </button>
        </div>
      </form>
    </div>
  )
}

/** The plugins column: every installed plugin in the order its hooks run. */
function PluginList({ plugins, selectedId, onSelect, onNew }) {
  return (
    <aside className="list-page__list" aria-label="Installed plugins">
      <div className="list-page__list-head">
        <h3>Installed</h3>
        <span className="pill">{plugins.length}</span>
      </div>
      <button className="button button--primary" onClick={onNew}>
        + New plugin
      </button>

      {plugins.length === 0 ? (
        <p className="list-page__empty">
          No plugins installed yet. A plugin is a Clojure jar whose entry
          namespace defines a <code>plugin</code> Var.
        </p>
      ) : (
        <div className="plugin-list">
          {plugins.map((plugin) => {
            const active = plugin.pluginId === selectedId
            return (
              <button
                key={plugin.pluginId}
                className={`plugin-card${active ? ' plugin-card--active' : ''}`}
                onClick={() => onSelect(plugin.pluginId)}
              >
                <span className="plugin-card__name">{plugin.name}</span>
                <span className="plugin-card__namespace">
                  {plugin.entryNamespace}
                </span>
                <span className="plugin-card__meta">
                  {pluginStateLabel(plugin)} · priority {plugin.priority}
                </span>
              </button>
            )
          })}
        </div>
      )}
    </aside>
  )
}

/** One plugin: what it stores, what it contributes, and how to change it. */
function PluginDetail({ plugin, onEdit, onDelete }) {
  const hooks = plugin.hooks ?? []

  return (
    <div className="list-panel">
      <header className="list-panel__head plugin__head">
        <div className="plugin__identity">
          <p className="list-page__eyebrow">plugin #{plugin.pluginId}</p>
          <h3>{plugin.name}</h3>
          <p className="plugin__path">{plugin.jarPath}</p>
        </div>
        <div className="plugin__actions">
          <button className="button button--quiet" onClick={() => onEdit(plugin)}>
            Edit
          </button>
          <button
            className="button button--danger"
            onClick={() => onDelete(plugin)}
          >
            Delete
          </button>
        </div>
      </header>

      <dl className="plugin__meta">
        <div>
          <dt>Runtime</dt>
          <dd>{pluginStateLabel(plugin)}</dd>
        </div>
        <div>
          <dt>Priority</dt>
          <dd>{plugin.priority}</dd>
        </div>
        <div>
          <dt>Entry namespace</dt>
          <dd className="plugin__namespace">{plugin.entryNamespace}</dd>
        </div>
      </dl>

      <section className="plugin-section">
        <h4>Hooks</h4>
        {hooks.length > 0 ? (
          <ul className="plugin-hooks">
            {hooks.map((hook) => (
              <li key={hook} className="plugin-hook">
                {hook}
              </li>
            ))}
          </ul>
        ) : (
          <p className="list-page__empty">
            {plugin.enabled
              ? 'The daemon registered no hooks for this plugin. Check the jar path and entry namespace in its log.'
              : 'This plugin is disabled, so the daemon does not load it.'}
          </p>
        )}
      </section>

      <section className="plugin-section">
        <h4>Config</h4>
        <pre className="plugin-config">{configToText(plugin.config)}</pre>
        <p className="list-page__empty">
          Passed to the plugin&apos;s constructor when the daemon loads it.
        </p>
        {plugin.updatedAt && (
          <p className="list-page__empty">
            Last changed {formatTimestamp(plugin.updatedAt)}.
          </p>
        )}
      </section>
    </div>
  )
}

/**
 * The plugins page: list the installed plugins, install new ones, edit or
 * delete them, and see which hooks each one contributes. Served by
 * `/api/plugins`, and reached from the console's "Plugins" tab.
 */
export function PluginsPage() {
  const installed = usePlugins()
  const [selectedId, setSelectedId] = useState(null)
  const [editor, setEditor] = useState(null)

  // Keep a valid selection as the list loads and after a plugin is deleted,
  // without overriding the user's choice.
  useEffect(() => {
    if (installed.plugins.length === 0) {
      if (selectedId !== null) setSelectedId(null)
      return
    }
    if (!installed.plugins.some((plugin) => plugin.pluginId === selectedId)) {
      setSelectedId(installed.plugins[0].pluginId)
    }
  }, [installed.plugins, selectedId])

  const selectedPlugin = useMemo(
    () => installed.plugins.find((plugin) => plugin.pluginId === selectedId) ?? null,
    [installed.plugins, selectedId],
  )

  const savePlugin = async (input) => {
    const saved = await installed.savePlugin(
      input,
      editor?.plugin?.pluginId ?? null,
    )
    setSelectedId(saved.pluginId)
    setEditor(null)
  }

  const removePlugin = async (plugin) => {
    if (!window.confirm(`Delete plugin "${plugin.name}"?`)) return
    try {
      await installed.deletePlugin(plugin.pluginId)
      setEditor(null)
    } catch {
      // The hook already stored the error for the page banner.
    }
  }

  return (
    <main className="list-page">
      <header className="list-page__header">
        <div className="list-page__heading">
          <p className="list-page__eyebrow">runtime</p>
          <h2>Plugins</h2>
          <p>
            Install the jars that extend the agent with their own hooks. Saving
            a plugin reloads the daemon&apos;s registry, so the change takes
            effect on the next prompt.
          </p>
        </div>
        <div className="list-page__actions">
          <button
            className="button button--quiet"
            onClick={installed.refresh}
            disabled={installed.loading}
          >
            {installed.loading ? 'Loading…' : 'Refresh'}
          </button>
        </div>
      </header>

      {installed.error && (
        <div className="list-page__error" role="alert">
          <span>{installed.error}</span>
          <button
            className="list-page__error-close"
            aria-label="Dismiss plugin error"
            onClick={installed.dismissError}
          >
            ×
          </button>
        </div>
      )}

      <div className="list-page__body">
        <PluginList
          plugins={installed.plugins}
          selectedId={editor ? null : selectedId}
          onSelect={(pluginId) => {
            setSelectedId(pluginId)
            setEditor(null)
          }}
          onNew={() => setEditor({ plugin: null })}
        />

        <section className="list-page__detail">
          {editor ? (
            <PluginForm
              key={editor.plugin?.pluginId ?? 'new'}
              plugin={editor.plugin}
              onSave={savePlugin}
              onCancel={() => setEditor(null)}
            />
          ) : selectedPlugin ? (
            <PluginDetail
              plugin={selectedPlugin}
              onEdit={(plugin) => setEditor({ plugin })}
              onDelete={removePlugin}
            />
          ) : (
            <div className="list-page__placeholder">
              <p>Select a plugin to view it, or install a new one.</p>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}
