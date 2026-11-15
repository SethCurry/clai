import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  DEFAULT_PLUGIN_PRIORITY,
  EMPTY_PLUGIN_FORM,
  configToText,
  formToPluginInput,
  parsePluginConfig,
  pluginStateLabel,
  pluginToForm,
  sortPlugins,
} from './plugins.js'

const stored = {
  pluginId: '3',
  name: 'todays-date',
  jarPath: '/opt/clai/todays-date.jar',
  entryNamespace: 'clai-plugin-todays-date.plugin',
  config: { 'date-format': 'yyyy' },
  enabled: true,
  priority: 20,
  loaded: true,
  hooks: ['system-prompt'],
  createdAt: '2024-01-02T03:04:05Z',
  updatedAt: '2024-01-02T04:04:05Z',
}

test('pluginToForm keeps only the fields the editor shows', () => {
  assert.deepEqual(pluginToForm(stored), {
    name: 'todays-date',
    jarPath: '/opt/clai/todays-date.jar',
    entryNamespace: 'clai-plugin-todays-date.plugin',
    config: '{\n  "date-format": "yyyy"\n}',
    enabled: true,
    priority: '20',
  })
  assert.deepEqual(pluginToForm(null), EMPTY_PLUGIN_FORM)
})

test('configToText renders an empty config as an empty JSON object', () => {
  assert.equal(configToText(undefined), '{}')
  assert.equal(configToText({}), '{}')
  assert.equal(configToText({ mode: 'strict' }), '{\n  "mode": "strict"\n}')
})

test('parsePluginConfig accepts a JSON object and rejects anything else', () => {
  assert.deepEqual(parsePluginConfig(''), {})
  assert.deepEqual(parsePluginConfig('  {"mode": "strict"}  '), {
    mode: 'strict',
  })
  assert.throws(() => parsePluginConfig('[]'), /must be a JSON object/)
  assert.throws(() => parsePluginConfig('"strict"'), /must be a JSON object/)
  assert.throws(() => parsePluginConfig('nope'), /must be a JSON object/)
})

test('formToPluginInput trims the fields and fills in the defaults', () => {
  assert.deepEqual(
    formToPluginInput({
      name: '  todays-date ',
      jarPath: ' /opt/clai/todays-date.jar ',
      entryNamespace: ' clai-plugin-todays-date.plugin ',
      config: '',
      enabled: false,
      priority: ' ',
    }),
    {
      name: 'todays-date',
      jarPath: '/opt/clai/todays-date.jar',
      entryNamespace: 'clai-plugin-todays-date.plugin',
      config: {},
      enabled: false,
      priority: DEFAULT_PLUGIN_PRIORITY,
    },
  )
})

test('formToPluginInput rejects a form without its required fields', () => {
  const form = { ...EMPTY_PLUGIN_FORM, config: '{}' }
  assert.throws(
    () => formToPluginInput({ ...form, name: '  ' }),
    /A plugin name is required/,
  )
  assert.throws(
    () => formToPluginInput({ ...form, name: 'a', jarPath: '' }),
    /path of the plugin jar is required/,
  )
  assert.throws(
    () => formToPluginInput({ ...form, name: 'a', jarPath: '/a.jar' }),
    /entry namespace is required/,
  )
  assert.throws(
    () => formToPluginInput({ ...form, ...stored, priority: 'soon' }),
    /whole number/,
  )
})

test('pluginStateLabel reports the runtime state of a stored plugin', () => {
  assert.equal(pluginStateLabel(stored), '1 hook')
  assert.equal(pluginStateLabel({ ...stored, hooks: [] }), '0 hooks')
  assert.equal(pluginStateLabel({ ...stored, enabled: false }), 'disabled')
  assert.equal(
    pluginStateLabel({ ...stored, loaded: false }),
    'not loaded',
    'an enabled plugin the daemon could not load shows as not loaded',
  )
})

test('sortPlugins orders by priority and then by name', () => {
  const plugins = [
    { name: 'later', priority: 200 },
    { name: 'beta', priority: 10 },
    { name: 'alpha', priority: 10 },
  ]
  assert.deepEqual(
    sortPlugins(plugins).map((plugin) => plugin.name),
    ['alpha', 'beta', 'later'],
  )
  assert.deepEqual(plugins.map((plugin) => plugin.name), ['later', 'beta', 'alpha'])
})
