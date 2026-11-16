/**
 * Pure helpers behind the stats page: number formatting, chart series, and
 * session labels. Keeping them here lets the page stay about layout, and lets
 * the node tests cover the arithmetic without a browser.
 */

/** Render a count for a KPI card; a missing value reads as a dash. */
export function formatCount(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return Number(value).toLocaleString()
}

/**
 * Render a character count compactly -- `950`, `12.3k`, `4.5M` -- which is what
 * fits in a KPI card and a chart axis. A missing value reads as a dash.
 */
export function formatChars(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const number = Number(value)
  if (Math.abs(number) < 1000) return `${Math.round(number)}`
  if (Math.abs(number) < 1_000_000) return `${trimZero(number / 1000)}k`
  return `${trimZero(number / 1_000_000)}M`
}

/** Render an average with at most one decimal; a missing value reads as a dash. */
export function formatRatio(value) {
  if (value == null || Number.isNaN(Number(value))) return '—'
  return Number(value).toLocaleString(undefined, { maximumFractionDigits: 1 })
}

/** Drop the trailing `.0` a one-decimal rendering leaves behind. */
function trimZero(number) {
  return number.toFixed(1).replace(/\.0$/, '')
}

/**
 * The per-tool rows, most-called first. The daemon already sorts them; sorting
 * again keeps the charts right if a row is added or edited by hand.
 */
export function sortTools(tools) {
  return [...(tools ?? [])].sort(
    (left, right) =>
      (right.callCount ?? 0) - (left.callCount ?? 0) ||
      String(left.toolName).localeCompare(String(right.toolName)),
  )
}

/**
 * A Chart.js bar series drawn from `rows`: the label each bar gets and the
 * number it is worth. Missing values become zero so the bar is drawn at the
 * axis rather than skipped.
 */
export function barSeries(rows, label, value) {
  const list = rows ?? []
  return {
    labels: list.map(label),
    values: list.map((row) => Number(row[value]) || 0),
  }
}

/** Calls per tool, most-called first. */
export function toolCallSeries(tools) {
  return barSeries(sortTools(tools), (tool) => tool.toolName, 'callCount')
}

/**
 * Average characters returned per call, for the tools that returned any,
 * most verbose first.
 */
export function toolOutputSeries(tools) {
  const rows = [...(tools ?? [])]
    .filter((tool) => (tool.completedCount ?? 0) > 0)
    .sort(
      (left, right) =>
        (right.avgOutputChars ?? 0) - (left.avgOutputChars ?? 0) ||
        String(left.toolName).localeCompare(String(right.toolName)),
    )
  return barSeries(rows, (tool) => tool.toolName, 'avgOutputChars')
}

/**
 * The `limit` sessions that used the most tool calls, busiest first, as a bar
 * series labelled by [[sessionLabel]].
 */
export function sessionCallSeries(sessions, limit = 10) {
  const rows = [...(sessions ?? [])]
    .sort(
      (left, right) =>
        (right.toolCallCount ?? 0) - (left.toolCallCount ?? 0) ||
        String(left.sessionId).localeCompare(String(right.sessionId)),
    )
    .slice(0, limit)
  return barSeries(rows, sessionLabel, 'toolCallCount')
}

/**
 * A short name for a session in a chart label: its title, or the last segment
 * of its working directory plus its id when it is still untitled.
 */
export function sessionLabel(session) {
  if (!session) return 'unknown session'
  if (session.title) return session.title
  const cwd = String(session.cwd ?? '').replace(/\/+$/, '')
  const directory = cwd.slice(cwd.lastIndexOf('/') + 1)
  const suffix = session.sessionId == null ? '?' : `#${session.sessionId}`
  return directory ? `${directory} ${suffix}` : suffix
}

/** The label/value pairs for the tool-share doughnut, busiest tool first. */
export function toolShare(tools) {
  const rows = sortTools(tools).filter((tool) => (tool.callCount ?? 0) > 0)
  return barSeries(rows, (tool) => tool.toolName, 'callCount')
}

/** True when a series holds nothing worth drawing. */
export function seriesEmpty(series) {
  return !series || series.values.length === 0
}
