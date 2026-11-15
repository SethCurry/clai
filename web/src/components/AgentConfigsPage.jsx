import { useMemo, useState } from 'react'
import { useClai } from '../ClaiContext.jsx'
import {
  EMPTY_FORM,
  configSummary,
  configToForm,
  firstSentence,
  formToConfig,
  pluginChoices,
  toggleName,
  toolChoices,
  withSelectionMode,
} from '../agentConfigs.js'
import { pluginStateLabel } from '../plugins.js'

/** One radio button of a mode group. */
function ModeOption({ name, value, label, hint, checked, onChange }) {
  return (
    <label
      className={`mode-option${checked ? ' mode-option--active' : ''}`}
    >
      <input
        type="radio"
        name={name}
        value={value}
        checked={checked}
        onChange={() => onChange(value)}
      />
      <span className="mode-option__body">
        <span className="mode-option__label">{label}</span>
        <span className="mode-option__hint">{hint}</span>
      </span>
    </label>
  )
}

/**
 * A radio group choosing how a configuration names one of its lists: the
 * daemon's default, nothing at all, or the items ticked below it.
 */
function ModeGroup({ name, legend, value, options, onChange }) {
  return (
    <fieldset className="config-form__group">
      <legend>{legend}</legend>
      <div className="mode-options">
        {options.map((option) => (
          <ModeOption
            key={option.value}
            name={name}
            value={option.value}
            label={option.label}
            hint={option.hint}
            checked={value === option.value}
            onChange={onChange}
          />
        ))}
      </div>
    </fieldset>
  )
}

/**
 * The ticked boxes behind a custom selection: everything the daemon offers,
 * plus any stored id it no longer knows, which stays ticked so saving the
 * form cannot drop it by accident.
 */
function ChoiceGrid({ legend, choices, describe, emptyHint, onToggle }) {
  if (choices.length === 0) {
    return <p className="config-form__hint">{emptyHint}</p>
  }
  return (
    <fieldset className="config-form__group">
      <legend>{legend}</legend>
      <div className="config-choices">
        {choices.map((choice) => (
          <label
            key={choice.id}
            className={[
              'config-choice',
              choice.checked && 'config-choice--checked',
              !choice.available && 'config-choice--missing',
            ]
              .filter(Boolean)
              .join(' ')}
            title={
              choice.available
                ? undefined
                : `${choice.id} is not available in this daemon; it stays selected until you untick it.`
            }
          >
            <input
              type="checkbox"
              checked={choice.checked}
              onChange={() => onToggle(choice.id)}
            />
            <span className="config-choice__body">
              <span className="config-choice__name">{choice.id}</span>
              <span className="config-choice__meta">{describe(choice)}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

/** The list column: every stored configuration, with its settings summary. */
function ConfigList({ configs, selectedId, onSelect, onNew }) {
  return (
    <aside className="list-page__list" aria-label="Agent configurations">
      <div className="list-page__list-head">
        <h3>Configurations</h3>
        <span className="pill">{configs.length}</span>
      </div>

      <button className="button button--primary" onClick={onNew}>
        + New configuration
      </button>

      {configs.length === 0 ? (
        <p className="list-page__empty">
          No stored configurations yet. Create one to reuse a model, prompt,
          tool set, and plugin set across sessions.
        </p>
      ) : (
        <div className="config-list">
          {configs.map((config) => (
            <div
              key={config.configId}
              className={`config-row${
                config.configId === selectedId ? ' config-row--active' : ''
              }`}
            >
              <button
                className="config-row__select"
                onClick={() => onSelect(config)}
              >
                <span className="config-row__name">{config.name}</span>
                <span className="config-row__meta">
                  {configSummary(config)}
                </span>
              </button>
            </div>
          ))}
        </div>
      )}
    </aside>
  )
}

/**
 * The editor for one configuration: the model and prompt settings, then the
 * tools and plugins it enables. The caller owns the API calls, so the form
 * only validates its own fields and surfaces a failure inline.
 */
function ConfigForm({
  draft,
  tools,
  plugins,
  busy,
  error,
  onChange,
  onToolsMode,
  onPluginsMode,
  onToggleTool,
  onTogglePlugin,
  onSubmit,
  onDelete,
  onCancel,
}) {
  const toolOptions = useMemo(() => toolChoices(tools, draft), [tools, draft])
  const pluginOptions = useMemo(
    () => pluginChoices(plugins, draft),
    [plugins, draft],
  )
  const pluginsByName = useMemo(
    () => new Map(plugins.map((plugin) => [plugin.name, plugin])),
    [plugins],
  )

  const describeTool = (choice) =>
    choice.available
      ? firstSentence(choice.description) || 'no description'
      : 'not available in this daemon'

  const describePlugin = (choice) => {
    const plugin = pluginsByName.get(choice.id)
    return plugin
      ? `${pluginStateLabel(plugin)} · priority ${plugin.priority}`
      : 'not installed in this daemon'
  }

  return (
    <div className="list-panel">
      <header className="list-panel__head">
        <div>
          <p className="list-page__eyebrow">
            {draft.configId
              ? `agent configuration #${draft.configId}`
              : 'new agent configuration'}
          </p>
          <h3>{draft.name || 'Untitled configuration'}</h3>
          <p>
            Sessions that select this configuration use these settings instead
            of the daemon&apos;s own model, prompt, tools, and plugins.
          </p>
        </div>
        <div className="list-page__actions">
          {draft.configId && (
            <button
              className="button button--danger"
              disabled={busy}
              onClick={onDelete}
            >
              Delete
            </button>
          )}
        </div>
      </header>

      <form className="config-form" onSubmit={onSubmit}>
        <label className="config-form__field">
          <span>Name</span>
          <input
            value={draft.name}
            onChange={(event) => onChange('name', event.target.value)}
            placeholder="fast"
            required
          />
        </label>

        <label className="config-form__field">
          <span>Model</span>
          <input
            value={draft.model}
            onChange={(event) => onChange('model', event.target.value)}
            placeholder="Daemon default"
            title="The model also picks the provider: openrouter:vendor/model (or any vendor/model id) runs on OpenRouter, ollama:name on Ollama, and a plain name uses the daemon's default provider."
          />
        </label>

        <label className="config-form__field">
          <span>System prompt</span>
          <textarea
            rows={4}
            value={draft.systemPrompt}
            onChange={(event) => onChange('systemPrompt', event.target.value)}
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
              value={draft.maxToolRounds}
              onChange={(event) => onChange('maxToolRounds', event.target.value)}
              placeholder="Daemon default"
            />
          </label>

          <label className="config-form__field">
            <span>Compact after tokens</span>
            <input
              type="number"
              min="0"
              step="1"
              value={draft.compactAfterTokens}
              onChange={(event) =>
                onChange('compactAfterTokens', event.target.value)
              }
              placeholder="Daemon default"
              title="Once the session history is estimated to be this many tokens, the daemon summarizes the older messages so the conversation fits the model's context window. 0 turns compaction off."
            />
          </label>
        </div>

        <ModeGroup
          name="think"
          legend="Thinking"
          value={draft.think}
          options={[
            {
              value: '',
              label: 'Daemon default',
              hint: 'Let the daemon decide how the model reasons.',
            },
            {
              value: 'true',
              label: 'Enabled',
              hint: 'Ask the model to reason before it answers.',
            },
            {
              value: 'false',
              label: 'Disabled',
              hint: 'Answer straight away; faster on reasoning models.',
            },
          ]}
          onChange={(value) => onChange('think', value)}
        />

        <label className="config-form__field">
          <span>Model options (JSON)</span>
          <textarea
            className="config-form__code"
            rows={3}
            value={draft.options}
            onChange={(event) => onChange('options', event.target.value)}
            spellCheck={false}
          />
        </label>

        <label className="config-form__field">
          <span>Response format</span>
          <input
            value={draft.format}
            onChange={(event) => onChange('format', event.target.value)}
            placeholder={'Daemon default, "json", or a JSON schema'}
          />
        </label>

        <ModeGroup
          name="toolsMode"
          legend="Tools"
          value={draft.toolsMode}
          options={[
            {
              value: 'default',
              label: 'Agent default tools',
              hint: 'Every tool the daemon was started with.',
            },
            {
              value: 'none',
              label: 'No tools',
              hint: 'The model answers without calling anything.',
            },
            {
              value: 'custom',
              label: 'Selected tools',
              hint: 'Only the tools ticked below.',
            },
          ]}
          onChange={onToolsMode}
        />
        {draft.toolsMode === 'custom' && (
          <ChoiceGrid
            legend={`Tools — ${draft.toolIds.length} selected`}
            choices={toolOptions}
            describe={describeTool}
            emptyHint="The daemon has not reported any tools yet."
            onToggle={onToggleTool}
          />
        )}

        <ModeGroup
          name="pluginsMode"
          legend="Plugins"
          value={draft.pluginsMode}
          options={[
            {
              value: 'default',
              label: 'All enabled plugins',
              hint: 'Every plugin the daemon loaded, in priority order.',
            },
            {
              value: 'none',
              label: 'No plugins',
              hint: 'Run the session without any plugin hooks.',
            },
            {
              value: 'custom',
              label: 'Selected plugins',
              hint: 'Only the plugins ticked below.',
            },
          ]}
          onChange={onPluginsMode}
        />
        {draft.pluginsMode === 'custom' && (
          <ChoiceGrid
            legend={`Plugins — ${draft.pluginNames.length} selected`}
            choices={pluginOptions}
            describe={describePlugin}
            emptyHint="No plugins are installed. Install one on the Plugins page to pick it here."
            onToggle={onTogglePlugin}
          />
        )}

        {error && <p className="config-form__error">{error}</p>}

        <div className="config-form__actions">
          <span className="config-form__spacer" />
          <button
            type="button"
            className="button button--quiet"
            disabled={busy}
            onClick={onCancel}
          >
            Close
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
    </div>
  )
}

/**
 * The agent-configurations page: the reusable model, prompt, tool, and plugin
 * settings a session can select. The list on the left is the stored set; the
 * form on the right ticks which tools and plugins the configuration enables.
 *
 * Configurations and plugins arrive over the shared daemon socket; the tools
 * the daemon can run come from `list_tools`, so the checkboxes always list
 * what this daemon actually offers.
 */
export function AgentConfigsPage() {
  const clai = useClai()
  const [draft, setDraft] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const tools = clai.tools
  const plugins = clai.plugins
  const configs = clai.agentConfigs

  const allToolIds = useMemo(() => tools.map((tool) => tool.id), [tools])
  const allPluginNames = useMemo(
    () => plugins.map((plugin) => plugin.name),
    [plugins],
  )

  const updateField = (field, value) => {
    setDraft((previous) => ({ ...previous, [field]: value }))
  }

  const updateToolsMode = (mode) => {
    setDraft((previous) =>
      withSelectionMode(previous, 'toolsMode', 'toolIds', mode, allToolIds),
    )
  }

  const updatePluginsMode = (mode) => {
    setDraft((previous) =>
      withSelectionMode(
        previous,
        'pluginsMode',
        'pluginNames',
        mode,
        allPluginNames,
      ),
    )
  }

  const openConfig = (config) => {
    setError('')
    setDraft(configToForm(config))
  }

  const startCreate = () => {
    setError('')
    setDraft({ ...EMPTY_FORM })
  }

  const closeEditor = () => {
    setError('')
    setDraft(null)
  }

  const submit = async (event) => {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      const saved = await clai.saveAgentConfig(
        formToConfig(draft),
        draft.configId || null,
      )
      setDraft(configToForm(saved))
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    const config = configs.find((entry) => entry.configId === draft.configId)
    if (
      !window.confirm(
        `Delete agent configuration "${config?.name ?? draft.name}"?`,
      )
    ) {
      return
    }
    setError('')
    setBusy(true)
    try {
      await clai.deleteAgentConfig(draft.configId)
      setDraft(null)
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="list-page">
      <header className="list-page__header">
        <div className="list-page__heading">
          <p className="list-page__eyebrow">agent</p>
          <h2>Agent configurations</h2>
          <p>
            A configuration is a reusable set of model, prompt, tool, and
            plugin settings. Pick one in the sessions sidebar before starting
            a session; sessions without one use the daemon&apos;s own defaults.
          </p>
        </div>
        <div className="list-page__actions">
          <button
            className="button button--quiet"
            onClick={clai.refreshAgentSetup}
          >
            Refresh
          </button>
        </div>
      </header>

      <div className="list-page__body">
        <ConfigList
          configs={configs}
          selectedId={draft?.configId}
          onSelect={openConfig}
          onNew={startCreate}
        />

        <section className="list-page__detail">
          {draft ? (
            <ConfigForm
              key={draft.configId || 'new'}
              draft={draft}
              tools={tools}
              plugins={plugins}
              busy={busy}
              error={error}
              onChange={updateField}
              onToolsMode={updateToolsMode}
              onPluginsMode={updatePluginsMode}
              onToggleTool={(id) =>
                updateField('toolIds', toggleName(draft.toolIds, id))
              }
              onTogglePlugin={(name) =>
                updateField('pluginNames', toggleName(draft.pluginNames, name))
              }
              onSubmit={submit}
              onDelete={remove}
              onCancel={closeEditor}
            />
          ) : (
            <div className="list-page__placeholder">
              <p>Select a configuration to edit it, or create a new one.</p>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}
