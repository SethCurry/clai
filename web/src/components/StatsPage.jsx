import {
  ArcElement,
  BarElement,
  CategoryScale,
  Chart as ChartJS,
  Legend,
  LinearScale,
  Tooltip,
} from 'chart.js'
import { useMemo } from 'react'
import { Bar, Doughnut } from 'react-chartjs-2'
import {
  formatChars,
  formatCount,
  formatRatio,
  seriesEmpty,
  sessionCallSeries,
  sortTools,
  toolCallSeries,
  toolOutputSeries,
  toolShare,
} from '../stats.js'
import { useStats } from '../useStats.js'

// Chart.js draws into a canvas and only ships the pieces a chart asks for.
// These are the ones the stats page uses: bar and doughnut charts with their
// axes, legend, and hover tooltips.
ChartJS.register(
  ArcElement,
  BarElement,
  CategoryScale,
  Legend,
  LinearScale,
  Tooltip,
)

/** Palette shared with the rest of the console. */
const ACCENT = '#7dd3fc'
const ACCENT_FILL = 'rgba(125, 211, 252, 0.55)'
const GRID = 'rgba(141, 151, 171, 0.15)'
const MUTED = '#8d97ab'
const PANEL = '#11151d'
const PALETTE = [
  '#7dd3fc',
  '#818cf8',
  '#34d399',
  '#fbbf24',
  '#f472b6',
  '#38bdf8',
  '#a78bfa',
  '#fb923c',
  '#4ade80',
  '#f87171',
]

/**
 * Options for one bar chart. `horizontal` puts the categories on the y axis,
 * which reads better for tool names; `formatValue` renders the value axis and
 * the tooltip, so a count and a character average read correctly.
 */
function barOptions(horizontal, formatValue) {
  const valueAxis = {
    beginAtZero: true,
    grid: { color: GRID },
    ticks: { color: MUTED, callback: (value) => formatValue(value) },
  }
  const labelAxis = {
    grid: { display: false },
    ticks: { color: MUTED, autoSkip: false },
  }
  return {
    responsive: true,
    maintainAspectRatio: false,
    indexAxis: horizontal ? 'y' : 'x',
    plugins: {
      legend: { display: false },
      tooltip: {
        callbacks: {
          label: (item) => formatValue(item.parsed[horizontal ? 'x' : 'y']),
        },
      },
    },
    scales: horizontal
      ? { x: valueAxis, y: labelAxis }
      : { x: labelAxis, y: valueAxis },
  }
}

/** The dataset for a bar chart: one accent-coloured bar per label. */
function barData(series) {
  return {
    labels: series.labels,
    datasets: [
      {
        data: series.values,
        backgroundColor: ACCENT_FILL,
        borderColor: ACCENT,
        borderRadius: 4,
        borderWidth: 1,
        maxBarThickness: 30,
      },
    ],
  }
}

/**
 * A chart card: a heading, a one-line explanation, and either the chart or a
 * message saying there is nothing to draw yet.
 */
function ChartCard({ title, hint, empty, emptyText, children }) {
  return (
    <section className="stats-card">
      <header className="stats-card__head">
        <h3>{title}</h3>
        {hint && <p>{hint}</p>}
      </header>
      {empty ? (
        <p className="stats-card__empty">{emptyText}</p>
      ) : (
        <div className="stats-chart">{children}</div>
      )}
    </section>
  )
}

/** One large number with its label, for the overview row. */
function Kpi({ label, value, hint }) {
  return (
    <div className="stats-kpi">
      <span className="stats-kpi__label">{label}</span>
      <span className="stats-kpi__value">{value}</span>
      {hint && <span className="stats-kpi__hint">{hint}</span>}
    </div>
  )
}

/** The overview: the totals behind the charts, as a row of KPI cards. */
function Overview({ overview }) {
  const stats = overview ?? {}
  return (
    <section className="stats-kpis" aria-label="Overview">
      <Kpi label="Sessions" value={formatCount(stats.sessionCount)} />
      <Kpi label="Prompts" value={formatCount(stats.promptCount)} />
      <Kpi label="Tool calls" value={formatCount(stats.toolCallCount)} />
      <Kpi
        label="Calls / session"
        value={formatRatio(stats.avgToolCallsPerSession)}
        hint="average tool calls per session"
      />
      <Kpi
        label="Chars / call"
        value={formatChars(stats.avgToolOutputChars)}
        hint="average tool output"
      />
      <Kpi
        label="Tool output"
        value={formatChars(stats.totalToolOutputChars)}
        hint="characters returned in total"
      />
    </section>
  )
}

/** One row of the per-tool table. */
function ToolRow({ tool }) {
  return (
    <tr>
      <td className="stats-table__name">{tool.toolName}</td>
      <td>{formatCount(tool.callCount)}</td>
      <td>{formatCount(tool.sessionCount)}</td>
      <td>{formatChars(tool.avgInputChars)}</td>
      <td>{formatChars(tool.avgOutputChars)}</td>
      <td>{formatChars(tool.maxOutputChars)}</td>
    </tr>
  )
}

/**
 * Every tool with its counts and character averages. The charts show the
 * shape; the table is there to read exact numbers off.
 */
function ToolTable({ tools }) {
  const rows = sortTools(tools)
  if (rows.length === 0) return null
  return (
    <section className="stats-card">
      <header className="stats-card__head">
        <h3>Per-tool detail</h3>
        <p>
          Characters are counted on the JSON stored in the session log: a
          call's arguments and the result it returned.
        </p>
      </header>
      <div className="stats-table-wrap">
        <table className="stats-table">
          <thead>
            <tr>
              <th>Tool</th>
              <th>Calls</th>
              <th>Sessions</th>
              <th>Avg arguments</th>
              <th>Avg output</th>
              <th>Largest output</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((tool) => (
              <ToolRow key={tool.toolName} tool={tool} />
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

/**
 * The stats page: how much the agent works, which tools it reaches for, how
 * much each of them returns, and how busy each session was. The numbers come
 * from `/api/stats/overview`, `/api/stats/tools`, and `/api/stats/sessions`,
 * and are drawn with Chart.js.
 */
export function StatsPage() {
  const stats = useStats()
  const calls = useMemo(() => toolCallSeries(stats.tools), [stats.tools])
  const outputs = useMemo(() => toolOutputSeries(stats.tools), [stats.tools])
  const sessions = useMemo(() => sessionCallSeries(stats.sessions), [stats.sessions])
  const share = useMemo(() => toolShare(stats.tools), [stats.tools])

  return (
    <main className="list-page stats-page">
      <header className="list-page__header">
        <div className="list-page__heading">
          <p className="list-page__eyebrow">meta statistics</p>
          <h2>Stats</h2>
          <p>
            How sessions and the agent use their tools, read from every
            session's event log: call counts, the characters each tool returns,
            and how much work a session takes on average.
          </p>
        </div>
        <div className="list-page__actions">
          <button
            className="button button--quiet"
            onClick={stats.refresh}
            disabled={stats.loading}
          >
            {stats.loading ? 'Loading…' : 'Refresh'}
          </button>
        </div>
      </header>

      {stats.error && (
        <div className="list-page__error" role="alert">
          <span>{stats.error}</span>
          <button
            className="list-page__error-close"
            aria-label="Dismiss statistics error"
            onClick={stats.dismissError}
          >
            ×
          </button>
        </div>
      )}

      <div className="stats-page__body">
        <Overview overview={stats.overview} />

        <div className="stats-grid">
          <ChartCard
            title="Tool calls"
            hint="How often each tool was called."
            empty={seriesEmpty(calls)}
            emptyText="No tool calls recorded yet."
          >
            <Bar
              data={barData(calls)}
              options={barOptions(true, formatCount)}
            />
          </ChartCard>

          <ChartCard
            title="Average output"
            hint="Characters a tool returns per call."
            empty={seriesEmpty(outputs)}
            emptyText="No tool has returned output yet."
          >
            <Bar
              data={barData(outputs)}
              options={barOptions(true, formatChars)}
            />
          </ChartCard>

          <ChartCard
            title="Busiest sessions"
            hint="Tool calls made by each session, top ten."
            empty={seriesEmpty(sessions)}
            emptyText="No sessions have used a tool yet."
          >
            <Bar
              data={barData(sessions)}
              options={barOptions(false, formatCount)}
            />
          </ChartCard>

          <ChartCard
            title="Share of calls"
            hint="Which tools the agent relies on."
            empty={seriesEmpty(share)}
            emptyText="No tool calls recorded yet."
          >
            <Doughnut
              data={{
                labels: share.labels,
                datasets: [
                  {
                    data: share.values,
                    backgroundColor: PALETTE,
                    borderColor: PANEL,
                    borderWidth: 2,
                  },
                ],
              }}
              options={{
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                  legend: {
                    position: 'right',
                    labels: { color: MUTED, boxWidth: 10 },
                  },
                  tooltip: {
                    callbacks: {
                      label: (item) =>
                        `${item.label}: ${formatCount(item.parsed)}`,
                    },
                  },
                },
              }}
            />
          </ChartCard>
        </div>

        <ToolTable tools={stats.tools} />
      </div>
    </main>
  )
}
