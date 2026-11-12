import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  docSummary,
  sortNamespaces,
  varSymbol,
  visibleRows,
} from './cljExplorer.js'

const namespaces = {
  status: 'ready',
  entries: [
    { name: 'clojure.core', doc: 'Core library.', varCount: 700 },
    { name: 'clojure.string', doc: 'String utilities.\n\nMore notes.', varCount: 2 },
    { name: 'sessions.42', doc: null, varCount: 1 },
    { name: 'sessions.7', doc: null, varCount: 0 },
  ],
}

const stringVars = {
  status: 'ready',
  missing: false,
  entries: [
    { name: 'join', doc: 'Returns a string.', arglists: '[coll] [separator coll]', macro: false },
    { name: 'upper-case', doc: 'Upper-cases s.', arglists: '[s]', macro: false },
  ],
}

test('sortNamespaces puts the session namespace first', () => {
  assert.deepEqual(
    sortNamespaces(namespaces.entries, 'sessions.42').map((entry) => entry.name),
    ['sessions.42', 'sessions.7', 'clojure.core', 'clojure.string'],
  )
  assert.deepEqual(
    sortNamespaces(namespaces.entries, 'user').map((entry) => entry.name),
    ['sessions.42', 'sessions.7', 'clojure.core', 'clojure.string'],
  )
})

test('visibleRows lists namespaces with their var counts', () => {
  assert.deepEqual(
    visibleRows({ namespaces, sessionNamespace: 'sessions.42' }).map((row) => row.kind),
    ['namespace', 'namespace', 'namespace', 'namespace'],
  )
  const [session, other, core] = visibleRows({
    namespaces,
    sessionNamespace: 'sessions.42',
  })
  assert.equal(session.namespace, 'sessions.42')
  assert.equal(session.name, 'sessions.42')
  assert.equal(session.open, false)
  assert.equal(session.depth, 0)
  assert.equal(other.namespace, 'sessions.7')
  assert.equal(core.name, 'clojure.core')
  assert.equal(core.varCount, 700)
})

test('visibleRows indents the vars of an open namespace', () => {
  const rows = visibleRows({
    namespaces,
    vars: { 'clojure.string': stringVars },
    expanded: new Set(['clojure.string']),
    sessionNamespace: 'sessions.42',
  })
  assert.deepEqual(
    rows.map((row) => [row.kind, row.depth, row.namespace, row.name]),
    [
      ['namespace', 0, 'sessions.42', 'sessions.42'],
      ['namespace', 0, 'sessions.7', 'sessions.7'],
      ['namespace', 0, 'clojure.core', 'clojure.core'],
      ['namespace', 0, 'clojure.string', 'clojure.string'],
      ['var', 1, 'clojure.string', 'join'],
      ['var', 1, 'clojure.string', 'upper-case'],
    ],
  )
  const join = rows.find((row) => row.name === 'join')
  assert.equal(join.arglists, '[coll] [separator coll]')
  assert.equal(join.macro, false)
  assert.equal(varSymbol(join), 'clojure.string/join')
})

test('visibleRows explains branches that are loading, empty, or missing', () => {
  assert.deepEqual(visibleRows({ namespaces: { status: 'loading' } }), [
    { kind: 'note', key: 'note::0:loading…', depth: 0, text: 'loading…' },
  ])
  assert.deepEqual(visibleRows({ namespaces: { status: 'error', message: 'nope' } }), [
    { kind: 'error', key: 'error::0:nope', depth: 0, text: 'nope' },
  ])
  assert.equal(
    visibleRows({ namespaces: { status: 'ready', entries: [] } })[0].text,
    'no namespaces',
  )

  const rows = visibleRows({
    namespaces: { status: 'ready', entries: [{ name: 'sessions.1', varCount: 0 }] },
    vars: {
      'sessions.1': { status: 'ready', missing: true, entries: [] },
      'sessions.2': { status: 'ready', missing: false, entries: [] },
      'sessions.3': { status: 'error', message: 'boom' },
    },
    expanded: new Set(['sessions.1']),
  })
  assert.equal(rows.at(-1).text, 'this namespace is not loaded yet')

  const empty = visibleRows({
    namespaces: { status: 'ready', entries: [{ name: 'sessions.2', varCount: 0 }] },
    vars: { 'sessions.2': { status: 'ready', entries: [] } },
    expanded: new Set(['sessions.2']),
  })
  assert.equal(empty.at(-1).text, 'no vars')

  const broken = visibleRows({
    namespaces: { status: 'ready', entries: [{ name: 'sessions.3', varCount: 0 }] },
    vars: { 'sessions.3': { status: 'error', message: 'boom' } },
    expanded: new Set(['sessions.3']),
  })
  assert.equal(broken.at(-1).kind, 'error')
})

test('visibleRows filters namespaces and open vars by name and doc', () => {
  const base = {
    namespaces,
    vars: { 'clojure.string': stringVars },
    expanded: new Set(['clojure.string']),
    sessionNamespace: 'sessions.42',
  }
  const labels = (filter) =>
    visibleRows({ ...base, filter }).map((row) => row.name ?? row.namespace)

  // A namespace matched by name or docstring keeps all of its Vars.
  assert.deepEqual(labels('STR'), ['clojure.string', 'join', 'upper-case'])
  assert.deepEqual(labels('utilities'), ['clojure.string', 'join', 'upper-case'])
  // Only the Vars that match are kept when the namespace itself does not.
  assert.deepEqual(labels('upper'), ['clojure.string', 'upper-case'])
  assert.deepEqual(labels('join'), ['clojure.string', 'join'])

  assert.equal(
    visibleRows({ ...base, filter: 'nothing' })[0].text,
    'no matching namespaces',
  )
})

test('docSummary takes the first non-blank line', () => {
  assert.equal(docSummary('\n\n  Joins things.  \n more'), 'Joins things.')
  assert.equal(docSummary(null), '')
  assert.equal(docSummary(''), '')
})
