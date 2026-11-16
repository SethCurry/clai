import { useCallback, useEffect, useState } from 'react'
import { requestJson } from './api.js'

/** What the page renders before the first load finishes. */
const EMPTY_STATS = { overview: null, tools: [], sessions: [] }

/**
 * Own the stats page's data: the overview totals, the per-tool rows, and the
 * per-session rows.
 *
 * The daemon exposes each section separately, so they are fetched together and
 * stored as one snapshot. `refresh` reloads all three; a failure is kept in
 * `error` for the page banner.
 */
export function useStats() {
  const [stats, setStats] = useState(EMPTY_STATS)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [overview, tools, sessions] = await Promise.all([
        requestJson('/api/stats/overview'),
        requestJson('/api/stats/tools'),
        requestJson('/api/stats/sessions'),
      ])
      setStats({
        overview,
        tools: tools.tools ?? [],
        sessions: sessions.sessions ?? [],
      })
      setError('')
    } catch (failure) {
      setError(failure?.message || String(failure))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return {
    ...stats,
    loading,
    error,
    refresh,
    dismissError: () => setError(''),
  }
}
