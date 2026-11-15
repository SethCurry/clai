import { useCallback, useEffect, useState } from 'react'
import { requestJson } from './api.js'
import { sortPlugins } from './plugins.js'

function pluginPath(pluginId) {
  return `/api/plugins/${encodeURIComponent(pluginId)}`
}

function jsonOptions(method, body) {
  return {
    method,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  }
}

/**
 * Own the plugins page's data: the installed plugins, and the writes that keep
 * the list in sync.
 *
 * The daemon reloads its plugin registry on every write and answers with the
 * saved plugin plus its new runtime status, so the list is updated from that
 * response instead of being refetched. Mutation errors are stored in `error`
 * for the page banner and rethrown so the form can show them inline too.
 */
export function usePlugins() {
  const [plugins, setPlugins] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const report = useCallback((failure) => {
    setError(failure?.message || String(failure))
  }, [])

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const body = await requestJson('/api/plugins')
      setPlugins(sortPlugins(body.plugins ?? []))
      setError('')
    } catch (failure) {
      report(failure)
    } finally {
      setLoading(false)
    }
  }, [report])

  useEffect(() => {
    refresh()
  }, [refresh])

  /** Create (no `pluginId`) or replace (`pluginId`) a plugin. */
  const savePlugin = useCallback(
    async (input, pluginId) => {
      try {
        const saved = await requestJson(
          pluginId ? pluginPath(pluginId) : '/api/plugins',
          jsonOptions(pluginId ? 'PUT' : 'POST', input),
        )
        setPlugins((previous) =>
          sortPlugins([
            ...previous.filter((plugin) => plugin.pluginId !== saved.pluginId),
            saved,
          ]),
        )
        setError('')
        return saved
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report],
  )

  /** Delete a plugin, dropping it from the list. */
  const deletePlugin = useCallback(
    async (pluginId) => {
      try {
        await requestJson(pluginPath(pluginId), { method: 'DELETE' })
        setPlugins((previous) =>
          previous.filter((plugin) => plugin.pluginId !== String(pluginId)),
        )
        setError('')
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report],
  )

  return {
    plugins,
    loading,
    error,
    refresh,
    dismissError: () => setError(''),
    savePlugin,
    deletePlugin,
  }
}
