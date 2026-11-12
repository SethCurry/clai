import assert from 'node:assert/strict'
import { test } from 'node:test'
import { LIST_LIMIT, fileName, visibleRows } from './fileExplorer.js'

const root = {
  status: 'ready',
  entries: [
    { name: 'src', path: 'src/', type: 'directory' },
    { name: 'README.md', path: 'README.md', type: 'file' },
  ],
}

const nested = {
  status: 'ready',
  entries: [{ name: 'app.clj', path: 'src/app.clj', type: 'file' }],
}

test('visibleRows indents open directories and their children', () => {
  const listings = { '': root, 'src/': nested }
  assert.deepEqual(visibleRows(listings, new Set([''])), [
    { kind: 'directory', path: 'src/', name: 'src', depth: 0, open: false },
    { kind: 'file', path: 'README.md', name: 'README.md', depth: 0, open: false },
  ])
  assert.deepEqual(visibleRows(listings, new Set(['', 'src/'])), [
    { kind: 'directory', path: 'src/', name: 'src', depth: 0, open: true },
    { kind: 'file', path: 'src/app.clj', name: 'app.clj', depth: 1, open: false },
    { kind: 'file', path: 'README.md', name: 'README.md', depth: 0, open: false },
  ])
})

test('visibleRows explains branches that are loading, empty, or broken', () => {
  const loading = { '': { status: 'loading' }, 'src/': { status: 'ready', entries: [] } }
  assert.deepEqual(visibleRows(loading, new Set(['', 'src/'])), [
    { kind: 'note', path: '', depth: 0, text: 'loading…' },
  ])

  const empty = { '': { status: 'ready', entries: [] } }
  assert.deepEqual(visibleRows(empty, new Set([''])), [
    { kind: 'note', path: '', depth: 0, text: 'empty' },
  ])

  const failed = { '': { status: 'error', message: 'nope' } }
  assert.deepEqual(visibleRows(failed, new Set([''])), [
    { kind: 'error', path: '', depth: 0, text: 'nope' },
  ])
})

test('visibleRows notes a truncated directory', () => {
  const entries = Array.from({ length: LIST_LIMIT }, (_, index) => ({
    name: `f${index}.clj`,
    path: `f${index}.clj`,
    type: 'file',
  }))
  const rows = visibleRows({ '': { status: 'ready', entries } }, new Set(['']))
  assert.equal(rows.length, LIST_LIMIT + 1)
  assert.equal(rows.at(-1).kind, 'note')
  assert.match(rows.at(-1).text, new RegExp(String(LIST_LIMIT)))
})

test('fileName strips directories and trailing slashes', () => {
  assert.equal(fileName('src/clai/core.clj'), 'core.clj')
  assert.equal(fileName('README.md'), 'README.md')
  assert.equal(fileName('src/'), 'src')
  assert.equal(fileName(''), '')
  assert.equal(fileName(null), '')
})
