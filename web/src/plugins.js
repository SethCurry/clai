/**
 * Shape and validate plugin forms for the daemon's API. Keeping this separate
 * from the components makes the trimming, JSON config parsing, and ordering
 * rules testable without a DOM.
 *
 * A plugin row comes back with `jarPath`, `entryNamespace`, `config`,
 * `enabled`, `priority`, and the runtime status `loaded`/`hooks`;
 * `clai.plugins.store/PluginInput` accepts the same names, so a form is a
 * stored plugin without its ids, timestamps, and runtime status.
 */

/** The hook priority a plugin gets when the form leaves it blank. */
export const DEFAULT_PLUGIN_PRIORITY = 100

export const EMPTY_PLUGIN_FORM = {
  name: '',
  jarPath: '',
  entryNamespace: '',
  config: '{}',
  enabled: true,
  priority: String(DEFAULT_PLUGIN_PRIORITY),
}

/**
 * A plugin's config map as the JSON text the editor shows.
 */
export function configToText(config) {
  if (!config || Object.keys(config).length === 0) return '{}'
  return JSON.stringify(config, null, 2)
}

/**
 * Turn a stored plugin into editable form values.
 */
export function pluginToForm(plugin) {
  return {
    name: plugin?.name ?? '',
    jarPath: plugin?.jarPath ?? '',
    entryNamespace: plugin?.entryNamespace ?? '',
    config: configToText(plugin?.config),
    enabled: plugin?.enabled !== false,
    priority: String(plugin?.priority ?? DEFAULT_PLUGIN_PRIORITY),
  }
}

/**
 * Parse the config field, which has to be a JSON object: the daemon passes it
 * to the plugin's constructor as a map.
 */
export function parsePluginConfig(text) {
  const trimmed = String(text ?? '').trim()
  if (!trimmed) return {}
  let value
  try {
    value = JSON.parse(trimmed)
  } catch {
    throw new Error('The plugin config must be a JSON object.')
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('The plugin config must be a JSON object.')
  }
  return value
}

/**
 * Trim and validate a plugin form. The name, jar path, and entry namespace are
 * required; a blank priority falls back to the daemon's default.
 */
export function formToPluginInput(form) {
  const name = String(form.name ?? '').trim()
  const jarPath = String(form.jarPath ?? '').trim()
  const entryNamespace = String(form.entryNamespace ?? '').trim()
  if (!name) throw new Error('A plugin name is required.')
  if (!jarPath) throw new Error('The path of the plugin jar is required.')
  if (!entryNamespace) throw new Error('An entry namespace is required.')

  const priorityText = String(form.priority ?? '').trim()
  const priority =
    priorityText === '' ? DEFAULT_PLUGIN_PRIORITY : Number(priorityText)
  if (!Number.isInteger(priority)) {
    throw new Error('Priority must be a whole number; lower priorities run first.')
  }

  return {
    name,
    jarPath,
    entryNamespace,
    config: parsePluginConfig(form.config),
    enabled: form.enabled !== false,
    priority,
  }
}

/**
 * A one-line description of a plugin's runtime state: the daemon only loads
 * enabled plugins, so a disabled or unloadable row contributes no hooks.
 */
export function pluginStateLabel(plugin) {
  if (!plugin?.enabled) return 'disabled'
  if (!plugin.loaded) return 'not loaded'
  const hooks = (plugin.hooks ?? []).length
  return `${hooks} hook${hooks === 1 ? '' : 's'}`
}

/**
 * Plugins run in priority order, then by name, so the list shows the order
 * their hooks fire in.
 */
export function sortPlugins(plugins) {
  return [...plugins].sort((a, b) => {
    const byPriority =
      (a.priority ?? DEFAULT_PLUGIN_PRIORITY) -
      (b.priority ?? DEFAULT_PLUGIN_PRIORITY)
    return (
      byPriority ||
      String(a.name ?? '').localeCompare(String(b.name ?? ''))
    )
  })
}
