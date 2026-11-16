import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  barSeries,
  formatChars,
  formatCount,
  formatRatio,
  seriesEmpty,
  sessionCallSeries,
  sessionLabel,
  sortTools,
  toolCallSeries,
  toolOutputSeries,
  toolShare,
} from './stats.js'

const TOOLS = [
  { toolName: 'read_file', callCount: 2, completedCount: 2, avgOutputChars: 1200 },
  { toolName: 'eval_clojure', callCount: 5, completedCount: 4, avgOutputChars: 800 },
  { toolName: 'run_command', callCount: 0, completedCount: 0, avgOutputChars: null },
]

const SESSIONS = [
  { sessionId: '1', title: 'Fix the build', cwd: '/tmp/one', toolCallCount: 3 },
  { sessionId: '2', title: null, cwd: '/home/me/clai', toolCallCount: 9 },
  { sessionId: '3', title: null, cwd: '/', toolCallCount: 0 },
]

test('formatCount leaves a dash for missing data and groups thousands', () => {
  assert.equal(formatCount(null), '—')
  assert.equal(formatCount(undefined), '—')
  assert.equal(formatCount('nope'), '—')
  assert.equal(formatCount(1234), '1,234')
})

test('formatChars shortens character counts', () => {
  assert.equal(formatChars(null), '—')
  assert.equal(formatChars(999), '999')
  assert.equal(formatChars(1000), '1k')
  assert.equal(formatChars(1234), '1.2k')
  assert.equal(formatChars(1_500_000), '1.5M')
})

test('formatRatio keeps one decimal and hides a missing average', () => {
  assert.equal(formatRatio(null), '—')
  assert.equal(formatRatio(2), '2')
  assert.equal(formatRatio(2.25), '2.3')
})

test('sortTools orders by call count, then by name', () => {
  assert.deepEqual(
    sortTools(TOOLS).map((tool) => tool.toolName),
    ['eval_clojure', 'read_file', 'run_command'],
  )
  assert.deepEqual(sortTools(null), [])
  // The input is not mutated.
  assert.equal(TOOLS[0].toolName, 'read_file')
})

test('barSeries reads the label and value out of each row', () => {
  assert.deepEqual(barSeries([{ name: 'a', n: 2 }, { name: 'b', n: null }], (row) => row.name, 'n'), {
    labels: ['a', 'b'],
    values: [2, 0],
  })
  assert.deepEqual(barSeries(null, (row) => row, 'n'), { labels: [], values: [] })
})

test('toolCallSeries and toolShare list tools busiest first', () => {
  assert.deepEqual(toolCallSeries(TOOLS), {
    labels: ['eval_clojure', 'read_file', 'run_command'],
    values: [5, 2, 0],
  })
  assert.deepEqual(toolShare(TOOLS), {
    labels: ['eval_clojure', 'read_file'],
    values: [5, 2],
  })
})

test('toolOutputSeries skips tools that never returned a result', () => {
  assert.deepEqual(toolOutputSeries(TOOLS), {
    labels: ['read_file', 'eval_clojure'],
    values: [1200, 800],
  })
})

test('sessionLabel prefers the title and falls back to the directory and id', () => {
  assert.equal(sessionLabel(SESSIONS[0]), 'Fix the build')
  assert.equal(sessionLabel(SESSIONS[1]), 'clai #2')
  assert.equal(sessionLabel({ sessionId: '4', cwd: '/' }), '#4')
  assert.equal(sessionLabel(null), 'unknown session')
})

test('sessionCallSeries keeps the busiest sessions, most active first', () => {
  assert.deepEqual(sessionCallSeries(SESSIONS, 2), {
    labels: ['clai #2', 'Fix the build'],
    values: [9, 3],
  })
  assert.equal(sessionCallSeries(SESSIONS).values.length, 3)
  assert.deepEqual(sessionCallSeries(null), { labels: [], values: [] })
})

test('seriesEmpty spots an empty chart', () => {
  assert.equal(seriesEmpty({ labels: [], values: [] }), true)
  assert.equal(seriesEmpty({ labels: ['a'], values: [0] }), false)
  assert.equal(seriesEmpty(null), true)
})
