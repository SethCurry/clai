/**
 * Shape and validate agent-configuration forms for the daemon's API. Keeping
 * this out of the components makes the mode/checkbox logic, the number
 * parsing, and the JSON fields testable without a DOM.
 *
 * A configuration row comes back with camelCase ids and kebab-case setting
 * names (`configId`, `system-prompt`, `max-tool-rounds`), matching what
 * `clai.agent.configs/AgentConfigInput` accepts. `tools` and `plugins` are
 * either absent (the daemon's own defaults), an empty list (none at all), or
 * the names to enable -- the three states the page shows as radio buttons.
 */

/** The form for a configuration that does not exist yet. */
export const EMPTY_FORM = {
  configId: '',
  name: '',
  model: '',
  systemPrompt: '',
  maxToolRounds: '',
  compactAfterTokens: '',
  think: '',
  options: '{}',
  format: '',
  toolsMode: 'default',
  toolIds: [],
  pluginsMode: 'default',
  pluginNames: [],
}

/**
 * The mode a stored list of tools or plugins means: `default` when the row
 * leaves the column NULL, `none` when it is empty, and `custom` otherwise.
 */
export function selectionMode(names) {
  if (names == null) return 'default'
  return names.length === 0 ? 'none' : 'custom'
}

/**
 * Add `name` to or remove it from a list of ids, returning a new list. Used by
 * the checkbox handlers, which only have the form's current ids to work from.
 */
export function toggleName(names, name) {
  return names.includes(name)
    ? names.filter((entry) => entry !== name)
    : [...names, name]
}

/**
 * Merge the ids a configuration stores with the choices the daemon offers.
 *
 * A stored id that the daemon no longer offers still appears, ticked and
 * marked unavailable, so opening and saving a configuration never silently
 * drops a tool or plugin. Entries are `{id, description, available, checked}`,
 * offered items sorted by id and the rest after them.
 */
export function selectionChoices(available, selected) {
  const chosen = new Set(selected ?? [])
  const offered = (available ?? []).map((entry) => ({
    id: entry.id,
    description: entry.description ?? '',
    available: true,
    checked: chosen.has(entry.id),
  }))
  const known = new Set(offered.map((entry) => entry.id))
  const missing = [...chosen]
    .filter((id) => !known.has(id))
    .sort()
    .map((id) => ({
      id,
      description: '',
      available: false,
      checked: true,
    }))
  return [...offered, ...missing]
}

/** The tool choices for `form`, given the tools the daemon reports. */
export function toolChoices(tools, form) {
  return selectionChoices(
    (tools ?? []).map((tool) => ({
      id: tool.id,
      description: tool.description,
    })),
    form?.toolIds,
  )
}

/**
 * The plugin choices for `form`, given the installed plugins. Plugins are
 * selected by name, which is what the `agent_config.plugins` column stores.
 */
export function pluginChoices(plugins, form) {
  return selectionChoices(
    (plugins ?? []).map((plugin) => ({
      id: plugin.name,
      description: '',
    })),
    form?.pluginNames,
  )
}

/**
 * The first sentence of a long description, short enough for a checkbox
 * label: tool descriptions are written for the model and run to paragraphs.
 */
export function firstSentence(text, limit = 140) {
  const line = String(text ?? '')
    .trim()
    .replace(/\s+/g, ' ')
  if (!line) return ''
  const stop = line.search(/\.(\s|$)/)
  const sentence = stop === -1 ? line : line.slice(0, stop + 1)
  return sentence.length > limit ? `${sentence.slice(0, limit - 1)}…` : sentence
}

/** How a configuration's tool list reads in the list beside the editor. */
export function toolsSummary(names) {
  if (names == null) return 'agent default tools'
  if (names.length === 0) return 'no tools'
  return `${names.length} tool${names.length === 1 ? '' : 's'}`
}

/** How a configuration's plugin list reads in the list beside the editor. */
export function pluginsSummary(names) {
  if (names == null) return 'all enabled plugins'
  if (names.length === 0) return 'no plugins'
  return `${names.length} plugin${names.length === 1 ? '' : 's'}`
}

/** The one-line description of a configuration in the page's list. */
export function configSummary(config) {
  return [
    config.model || 'daemon model',
    toolsSummary(config.tools),
    pluginsSummary(config.plugins),
  ].join(' · ')
}

/**
 * Switch one of a form's selection modes. A custom selection starts from
 * `available` the first time it is chosen, so the user ticks off what a session
 * should not have rather than hunting for every id to add.
 */
export function withSelectionMode(form, modeField, idsField, mode, available) {
  return {
    ...form,
    [modeField]: mode,
    [idsField]:
      mode === 'custom' && form[idsField].length === 0
        ? available
        : form[idsField],
  }
}

/** Turn a stored configuration into editable form values. */
export function configToForm(config) {
  return {
    configId: config.configId ?? '',
    name: config.name ?? '',
    model: config.model ?? '',
    systemPrompt: config['system-prompt'] ?? '',
    maxToolRounds:
      config['max-tool-rounds'] == null
        ? ''
        : String(config['max-tool-rounds']),
    compactAfterTokens:
      config['compact-after-tokens'] == null
        ? ''
        : String(config['compact-after-tokens']),
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
    toolsMode: selectionMode(config.tools),
    toolIds: [...(config.tools ?? [])],
    pluginsMode: selectionMode(config.plugins),
    pluginNames: [...(config.plugins ?? [])],
  }
}

/** Parse the model-options field, which has to be a JSON object. */
export function parseOptions(text) {
  const trimmed = String(text ?? '').trim()
  if (!trimmed) return {}
  let value
  try {
    value = JSON.parse(trimmed)
  } catch {
    throw new Error('Model options must be a JSON object.')
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Model options must be a JSON object.')
  }
  return value
}

/**
 * Parse the response-format field: a plain string such as `json`, or a JSON
 * schema, which starts with `{` or `[`.
 */
export function parseFormat(text) {
  const trimmed = String(text ?? '').trim()
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

/** Parse a whole-number field, or nil when it is left blank. */
function parseCount(text, { min, message }) {
  const trimmed = String(text ?? '').trim()
  if (!trimmed) return null
  const value = Number(trimmed)
  if (!Number.isInteger(value) || value < min) {
    throw new Error(message)
  }
  return value
}

/**
 * The tool list a form names: null for the agent's defaults, `[]` for none,
 * and the ticked ids for a custom selection.
 */
function parseTools(form) {
  if (form.toolsMode === 'default') return null
  if (form.toolsMode === 'none') return []
  if (form.toolIds.length === 0) {
    throw new Error('Tick at least one tool, or choose "No tools".')
  }
  return form.toolIds
}

/** The plugin list a form names, in the same three states as the tools. */
function parsePlugins(form) {
  if (form.pluginsMode === 'default') return null
  if (form.pluginsMode === 'none') return []
  if (form.pluginNames.length === 0) {
    throw new Error('Tick at least one plugin, or choose "No plugins".')
  }
  return form.pluginNames
}

/** Validate a form and turn it into the configuration the daemon stores. */
export function formToConfig(form) {
  const name = String(form.name ?? '').trim()
  if (!name) throw new Error('A configuration name is required.')

  return {
    name,
    model: String(form.model ?? '').trim() || null,
    'system-prompt':
      String(form.systemPrompt ?? '').trim() === ''
        ? null
        : form.systemPrompt,
    'max-tool-rounds': parseCount(form.maxToolRounds, {
      min: 1,
      message: 'Max tool rounds must be a positive whole number.',
    }),
    'compact-after-tokens': parseCount(form.compactAfterTokens, {
      min: 0,
      message:
        'Compact after tokens must be a whole number of tokens (0 turns compaction off).',
    }),
    think: form.think === '' ? null : form.think === 'true',
    options: parseOptions(form.options),
    format: parseFormat(form.format),
    tools: parseTools(form),
    plugins: parsePlugins(form),
  }
}
