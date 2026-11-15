import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  EMPTY_FORM,
  configSummary,
  configToForm,
  firstSentence,
  formToConfig,
  parseFormat,
  parseOptions,
  pluginChoices,
  pluginsSummary,
  selectionChoices,
  selectionMode,
  toggleName,
  toolChoices,
  toolsSummary,
  withSelectionMode,
} from './agentConfigs.js'

const stored = {
  configId: '7',
  name: 'fast',
  model: 'gemma3',
  'system-prompt': 'Be brief.',
  'max-tool-rounds': 4,
  'compact-after-tokens': 0,
  think: false,
  options: { temperature: 0.2 },
  format: 'json',
  tools: ['read_file', 'run_command'],
  plugins: [],
}

test('selectionMode reads the three stored states', () => {
  assert.equal(selectionMode(undefined), 'default')
  assert.equal(selectionMode(null), 'default')
  assert.equal(selectionMode([]), 'none')
  assert.equal(selectionMode(['a']), 'custom')
})

test('toggleName adds and removes without mutating', () => {
  const ids = ['a']
  assert.deepEqual(toggleName(ids, 'b'), ['a', 'b'])
  assert.deepEqual(toggleName(ids, 'a'), [])
  assert.deepEqual(ids, ['a'])
})

test('withSelectionMode prefills a custom selection once', () => {
  const form = { toolsMode: 'default', toolIds: [] }
  const custom = withSelectionMode(
    form,
    'toolsMode',
    'toolIds',
    'custom',
    ['read_file', 'run_command'],
  )
  assert.deepEqual(custom, {
    toolsMode: 'custom',
    toolIds: ['read_file', 'run_command'],
  })
  assert.deepEqual(
    withSelectionMode(custom, 'toolsMode', 'toolIds', 'none', ['read_file']),
    { toolsMode: 'none', toolIds: ['read_file', 'run_command'] },
    'leaving a custom selection keeps its ids',
  )
  assert.deepEqual(
    withSelectionMode(custom, 'toolsMode', 'toolIds', 'custom', ['write_file']),
    { toolsMode: 'custom', toolIds: ['read_file', 'run_command'] },
    'returning to a custom selection does not wipe what was ticked',
  )
  assert.deepEqual(form, { toolsMode: 'default', toolIds: [] })
})

test('configToForm turns a stored configuration into form values', () => {
  assert.deepEqual(configToForm(stored), {
    configId: '7',
    name: 'fast',
    model: 'gemma3',
    systemPrompt: 'Be brief.',
    maxToolRounds: '4',
    compactAfterTokens: '0',
    think: 'false',
    options: '{\n  "temperature": 0.2\n}',
    format: 'json',
    toolsMode: 'custom',
    toolIds: ['read_file', 'run_command'],
    pluginsMode: 'none',
    pluginNames: [],
  })
})

test('configToForm fills a blank configuration with the daemon defaults', () => {
  assert.deepEqual(configToForm({ configId: '1', name: 'plain' }), {
    ...EMPTY_FORM,
    configId: '1',
    name: 'plain',
  })
})

test('formToConfig round-trips a stored configuration', () => {
  const input = formToConfig(configToForm(stored))
  assert.deepEqual(input, {
    name: 'fast',
    model: 'gemma3',
    'system-prompt': 'Be brief.',
    'max-tool-rounds': 4,
    'compact-after-tokens': 0,
    think: false,
    options: { temperature: 0.2 },
    format: 'json',
    tools: ['read_file', 'run_command'],
    plugins: [],
  })
})

test('formToConfig sends null for the untouched settings', () => {
  assert.deepEqual(formToConfig({ ...EMPTY_FORM, name: 'plain' }), {
    name: 'plain',
    model: null,
    'system-prompt': null,
    'max-tool-rounds': null,
    'compact-after-tokens': null,
    think: null,
    options: {},
    format: null,
    tools: null,
    plugins: null,
  })
})

test('formToConfig rejects an unnamed configuration', () => {
  assert.throws(() => formToConfig(EMPTY_FORM), /name is required/)
})

test('formToConfig rejects bad numbers, options, and empty selections', () => {
  assert.throws(
    () => formToConfig({ ...EMPTY_FORM, name: 'x', maxToolRounds: '0' }),
    /Max tool rounds/,
  )
  assert.throws(
    () => formToConfig({ ...EMPTY_FORM, name: 'x', compactAfterTokens: '-1' }),
    /Compact after tokens/,
  )
  assert.throws(
    () => formToConfig({ ...EMPTY_FORM, name: 'x', options: '[]' }),
    /Model options must be a JSON object/,
  )
  assert.throws(
    () => formToConfig({ ...EMPTY_FORM, name: 'x', format: '{nope' }),
    /Response format looks like JSON/,
  )
  assert.throws(
    () => formToConfig({ ...EMPTY_FORM, name: 'x', toolsMode: 'custom' }),
    /Tick at least one tool/,
  )
  assert.throws(
    () => formToConfig({ ...EMPTY_FORM, name: 'x', pluginsMode: 'custom' }),
    /Tick at least one plugin/,
  )
})

test('parseOptions and parseFormat accept the blank field', () => {
  assert.deepEqual(parseOptions('  '), {})
  assert.equal(parseFormat(''), null)
  assert.deepEqual(parseFormat('{"type": "object"}'), { type: 'object' })
})

test('selectionChoices keeps ticked ids the daemon does not offer', () => {
  assert.deepEqual(
    selectionChoices(
      [
        { id: 'read_file', description: 'Read a file.' },
        { id: 'run_command', description: 'Run a command.' },
      ],
      ['run_command', 'gone_tool'],
    ),
    [
      {
        id: 'read_file',
        description: 'Read a file.',
        available: true,
        checked: false,
      },
      {
        id: 'run_command',
        description: 'Run a command.',
        available: true,
        checked: true,
      },
      { id: 'gone_tool', description: '', available: false, checked: true },
    ],
  )
})

test('toolChoices and pluginChoices read their ids from each source', () => {
  assert.deepEqual(
    toolChoices([{ id: 'read_file', description: 'Read a file.' }], {
      toolIds: ['read_file'],
    }),
    [
      {
        id: 'read_file',
        description: 'Read a file.',
        available: true,
        checked: true,
      },
    ],
  )
  assert.deepEqual(
    pluginChoices([{ name: 'todays-date' }, { name: 'audit' }], {
      pluginNames: ['audit'],
    }),
    [
      { id: 'todays-date', description: '', available: true, checked: false },
      { id: 'audit', description: '', available: true, checked: true },
    ],
  )
})

test('firstSentence keeps a checkbox label short', () => {
  assert.equal(firstSentence(''), '')
  assert.equal(
    firstSentence('Read a file.\n    Then close it.'),
    'Read a file.',
  )
  assert.equal(firstSentence('No trailing period'), 'No trailing period')
  assert.equal(firstSentence('x'.repeat(200)).length, 140)
  assert.ok(firstSentence('x'.repeat(200)).endsWith('…'))
})

test('the summaries describe what a configuration enables', () => {
  assert.equal(toolsSummary(null), 'agent default tools')
  assert.equal(toolsSummary([]), 'no tools')
  assert.equal(toolsSummary(['a']), '1 tool')
  assert.equal(toolsSummary(['a', 'b']), '2 tools')
  assert.equal(pluginsSummary(null), 'all enabled plugins')
  assert.equal(pluginsSummary([]), 'no plugins')
  assert.equal(pluginsSummary(['a', 'b']), '2 plugins')
  assert.equal(configSummary(stored), 'gemma3 · 2 tools · no plugins')
  assert.equal(
    configSummary({ configId: '1', name: 'plain' }),
    'daemon model · agent default tools · all enabled plugins',
  )
})
