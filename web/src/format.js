/**
 * Shared date formatting for the web UI's list and detail pages.
 */

/**
 * Parse an ISO timestamp, returning null for a missing or unparsable value so
 * callers can fall back to showing the raw text.
 */
export function parseTimestamp(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * Render an ISO timestamp for a detail panel, falling back to a dash when the
 * database did not supply one.
 */
export function formatTimestamp(value) {
  const date = parseTimestamp(value)
  return date ? date.toLocaleString() : value ? String(value) : '—'
}

/** Relative-time units, largest first, and the age at which a date is shown instead. */
const WEEK = 7 * 24 * 60 * 60
const RELATIVE_UNITS = [
  { seconds: WEEK, suffix: 'w' },
  { seconds: 24 * 60 * 60, suffix: 'd' },
  { seconds: 60 * 60, suffix: 'h' },
  { seconds: 60, suffix: 'm' },
]
const ABSOLUTE_AFTER = 5 * WEEK

/**
 * Render an ISO timestamp as a compact age ("3m ago", "2d ago") for list rows,
 * where a full timestamp would take more room than the row has. Timestamps
 * under a minute old, or in the future, read as "just now"; anything five weeks
 * or more old gets a plain local date.
 */
export function formatRelativeTime(value, now = Date.now()) {
  const date = parseTimestamp(value)
  if (!date) return value ? String(value) : '—'
  const elapsed = Math.max(0, Math.round((now - date.getTime()) / 1000))
  if (elapsed < 60) return 'just now'
  if (elapsed >= ABSOLUTE_AFTER) return date.toLocaleDateString()
  const unit = RELATIVE_UNITS.find((candidate) => elapsed >= candidate.seconds)
  return `${Math.floor(elapsed / unit.seconds)}${unit.suffix} ago`
}
