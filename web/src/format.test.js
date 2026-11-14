import assert from 'node:assert/strict'
import { test } from 'node:test'
import { formatRelativeTime, formatTimestamp, parseTimestamp } from './format.js'

test('parseTimestamp rejects missing and unparsable values', () => {
  assert.equal(parseTimestamp(null), null)
  assert.equal(parseTimestamp(''), null)
  assert.equal(parseTimestamp('not-a-date'), null)
  assert.ok(parseTimestamp('2024-01-02T03:04:05Z') instanceof Date)
})

test('formatTimestamp handles missing and invalid values', () => {
  assert.equal(formatTimestamp(null), '—')
  assert.equal(formatTimestamp('not-a-date'), 'not-a-date')
  assert.notEqual(formatTimestamp('2024-01-02T03:04:05Z'), '—')
})

test('formatRelativeTime renders minutes, hours, days, and weeks', () => {
  const now = Date.parse('2024-01-10T12:00:00Z')
  const ago = (seconds) => new Date(now - seconds * 1000).toISOString()
  assert.equal(formatRelativeTime(ago(20), now), 'just now')
  assert.equal(formatRelativeTime(ago(5 * 60), now), '5m ago')
  assert.equal(formatRelativeTime(ago(3 * 60 * 60), now), '3h ago')
  assert.equal(formatRelativeTime(ago(2 * 24 * 60 * 60), now), '2d ago')
  assert.equal(formatRelativeTime(ago(9 * 24 * 60 * 60), now), '1w ago')
})

test('formatRelativeTime falls back to a date and copes with bad input', () => {
  const now = Date.parse('2024-06-01T12:00:00Z')
  const old = formatRelativeTime('2024-01-02T03:04:05Z', now)
  assert.match(old, /^[0-9]/)
  assert.equal(formatRelativeTime(null, now), '—')
  assert.equal(formatRelativeTime('nope', now), 'nope')
  // A timestamp from the future reads as "just now" rather than a negative age.
  assert.equal(
    formatRelativeTime(new Date(now + 60 * 1000).toISOString(), now),
    'just now',
  )
})
