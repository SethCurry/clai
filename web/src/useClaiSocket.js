import { useCallback, useEffect, useRef, useState } from 'react'
import { sessionNamespace } from './sessionNamespace.js'

// Reconnect quickly at first, then back off so a daemon restart is picked up
// without hammering it.
const RECONNECT_DELAYS = [500, 1000, 2000, 5000, 10000]

/**
 * Merge one streamed agent update into the timeline. Tool calls arrive in
 * pieces: a `running` event starts the card, output events carry only the id
 * and incremental text, and a terminal event marks it completed or failed.
 */
function applyUpdate(updates, update) {
  if (update.type === 'tool_call' && update.id) {
    const index = updates.findIndex(
      (item) => item.type === 'tool_call' && item.id === update.id,
    )
    if (index === -1) return [...updates, update]
    const next = updates.slice()
    next[index] = { ...next[index], ...update }
    return next
  }
  return [...updates, update]
}

function errorId() {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/**
 * Append one nREPL response to the REPL entry for `requestId`, preserving the
 * form the user submitted. An eval produces several responses (a value and a
 * trailing `done` status, for example), so entries collect all of them.
 */
function appendEvalResponse(entries, requestId, response) {
  const index = entries.findIndex((entry) => entry.requestId === requestId)
  if (index === -1) {
    return [
      ...entries,
      { id: errorId(), requestId, code: '', responses: [response] },
    ]
  }
  const next = entries.slice()
  next[index] = {
    ...next[index],
    responses: [...(next[index].responses ?? []), response],
  }
  return next
}

async function responseBody(response) {
  const text = await response.text()
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return {}
  }
}

function sortConfigs(configs) {
  return [...configs].sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * Own the browser's connection to the clai daemon. All agent and REPL traffic
 * goes over the same WebSocket; this hook turns the daemon's messages into
 * state the components can render.
 */
export function useClaiSocket() {
  const [status, setStatus] = useState('connecting')
  const [sessionInfo, setSessionInfo] = useState({
    model: null,
    nreplPort: null,
  })
  const [sessions, setSessions] = useState([])
  const [agentConfigs, setAgentConfigs] = useState([])
  const [plugins, setPlugins] = useState([])
  const [selectedConfigId, setSelectedConfigId] = useState('')
  const [currentSession, setCurrentSession] = useState(null)
  const [updates, setUpdates] = useState([])
  const [promptRunning, setPromptRunning] = useState(false)
  const [replEntries, setReplEntries] = useState([])
  const [evalRunning, setEvalRunning] = useState(false)
  const [errors, setErrors] = useState([])

  const socketRef = useRef(null)
  const reconnectTimer = useRef(null)
  const reconnectAttempt = useRef(0)
  const requestCounter = useRef(0)
  const activeSession = useRef(null)

  const pushError = useCallback((message) => {
    setErrors((previous) => [
      ...previous.slice(-4),
      { id: errorId(), message: String(message) },
    ])
  }, [])

  const dismissError = useCallback((id) => {
    setErrors((previous) => previous.filter((error) => error.id !== id))
  }, [])

  const send = useCallback(
    (message) => {
      const socket = socketRef.current
      if (!socket || socket.readyState !== WebSocket.OPEN) {
        pushError('The daemon is not connected yet.')
        return null
      }
      const requestId = message.requestId ?? String(++requestCounter.current)
      socket.send(JSON.stringify({ ...message, requestId }))
      return requestId
    },
    [pushError],
  )

  useEffect(() => {
    let closedByUs = false

    function connect() {
      setStatus('connecting')
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const socket = new WebSocket(`${protocol}//${window.location.host}/ws`)
      socketRef.current = socket

      socket.onopen = () => {
        reconnectAttempt.current = 0
        setStatus('connected')
        setPromptRunning(false)
        setEvalRunning(false)
        send({ type: 'list_sessions' })
        send({ type: 'list_agent_configs' })
        send({ type: 'list_plugins' })
        // After a reconnect, replay the active conversation. The prompt may
        // have finished while the socket was down.
        if (activeSession.current) {
          send({ type: 'load_session', sessionId: activeSession.current })
        }
      }

      socket.onmessage = (event) => {
        let message
        try {
          message = JSON.parse(event.data)
        } catch {
          pushError('The daemon sent a message that was not valid JSON.')
          return
        }

        switch (message.type) {
          case 'hello':
            setSessionInfo({
              model: message.model ?? null,
              nreplPort: message.nreplPort ?? null,
            })
            break

          case 'sessions':
            setSessions(message.sessions ?? [])
            break

          case 'agent_configs':
            setAgentConfigs(message.configs ?? [])
            break

          case 'plugins':
            setPlugins(message.plugins ?? [])
            break

          case 'agent_config':
            if (message.config?.configId) {
              setAgentConfigs((previous) => {
                const next = previous.filter(
                  (config) => config.configId !== message.config.configId,
                )
                return [...next, message.config].sort((a, b) =>
                  a.name.localeCompare(b.name),
                )
              })
              setSelectedConfigId(message.config.configId)
            }
            break

          case 'session': {
            const session = message.session
            if (!session?.sessionId) break
            activeSession.current = session.sessionId
            setCurrentSession({
              sessionId: session.sessionId,
              title: session.title ?? null,
              cwd: session.cwd,
              configId: session.configId,
              createdAt: session.createdAt,
            })
            setUpdates(session.updates ?? [])
            setPromptRunning(false)
            // A new session is not in the sidebar until the list is refreshed.
            send({ type: 'list_sessions' })
            break
          }

          case 'update':
            if (message.sessionId === activeSession.current) {
              setUpdates((previous) => applyUpdate(previous, message.update))
            }
            break

          case 'prompt_done':
            setPromptRunning(false)
            break

          case 'deleted':
            setSessions((previous) =>
              previous.filter(
                (session) => session.sessionId !== message.sessionId,
              ),
            )
            if (message.sessionId === activeSession.current) {
              activeSession.current = null
              setCurrentSession(null)
              setUpdates([])
            }
            break

          case 'session_renamed': {
            const session = message.session
            if (!session?.sessionId) break
            const withTitle = (previous) =>
              previous.sessionId === session.sessionId
                ? { ...previous, title: session.title ?? null }
                : previous
            setCurrentSession((previous) =>
              previous ? withTitle(previous) : previous,
            )
            setSessions((previous) => previous.map(withTitle))
            break
          }

          case 'eval':
            setReplEntries((previous) =>
              appendEvalResponse(previous, message.requestId, message.response),
            )
            break

          case 'eval_done':
            setEvalRunning(false)
            break

          case 'error':
            pushError(message.message || 'The daemon reported an error.')
            setPromptRunning(false)
            setEvalRunning(false)
            break

          default:
            pushError(`Unknown daemon message type: ${message.type}`)
        }
      }

      socket.onclose = () => {
        if (closedByUs) return
        if (socketRef.current !== socket) return
        setStatus('disconnected')
        setPromptRunning(false)
        setEvalRunning(false)
        const delay =
          RECONNECT_DELAYS[
            Math.min(reconnectAttempt.current, RECONNECT_DELAYS.length - 1)
          ]
        reconnectAttempt.current += 1
        reconnectTimer.current = window.setTimeout(connect, delay)
      }

      socket.onerror = () => {
        setStatus('error')
      }
    }

    connect()

    return () => {
      closedByUs = true
      if (reconnectTimer.current) window.clearTimeout(reconnectTimer.current)
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [pushError, send])

  const newSession = useCallback(
    (configId = selectedConfigId) =>
      send({
        type: 'new_session',
        ...(configId ? { configId: String(configId) } : {}),
      }),
    [selectedConfigId, send],
  )

  const selectAgentConfig = useCallback(
    (configId) => {
      setSelectedConfigId(configId)
      if (configId) send({ type: 'load_agent_config', configId })
    },
    [send],
  )

  const refreshSessions = useCallback(
    () => send({ type: 'list_sessions' }),
    [send],
  )

  const loadSession = useCallback(
    (sessionId) => send({ type: 'load_session', sessionId }),
    [send],
  )

  const deleteSession = useCallback(
    (sessionId) => send({ type: 'delete_session', sessionId }),
    [send],
  )

  const renameSession = useCallback(
    (sessionId, title) => send({ type: 'rename_session', sessionId, title }),
    [send],
  )

  const saveAgentConfig = useCallback(
    async (config, configId) => {
      const path = configId
        ? `/api/agent-configs/${encodeURIComponent(configId)}`
        : '/api/agent-configs'
      try {
        const response = await fetch(path, {
          method: configId ? 'PUT' : 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(config),
        })
        const body = await responseBody(response)
        if (!response.ok) {
          throw new Error(
            body.error ||
              `Could not save agent configuration (${response.status}).`,
          )
        }
        setAgentConfigs((previous) =>
          sortConfigs([
            ...previous.filter(
              (saved) => saved.configId !== body.configId,
            ),
            body,
          ]),
        )
        if (!configId) setSelectedConfigId(body.configId)
        return body
      } catch (failure) {
        pushError(failure.message || String(failure))
        throw failure
      }
    },
    [pushError],
  )

  const deleteAgentConfig = useCallback(
    async (configId) => {
      try {
        const response = await fetch(
          `/api/agent-configs/${encodeURIComponent(configId)}`,
          { method: 'DELETE' },
        )
        const body = await responseBody(response)
        if (!response.ok) {
          throw new Error(
            body.error ||
              `Could not delete agent configuration (${response.status}).`,
          )
        }
        setAgentConfigs((previous) =>
          previous.filter((config) => config.configId !== String(configId)),
        )
        setSelectedConfigId((previous) =>
          previous === String(configId) ? '' : previous,
        )
        // Deleting a configuration clears it from sessions that selected it.
        send({ type: 'list_sessions' })
        return body
      } catch (failure) {
        pushError(failure.message || String(failure))
        throw failure
      }
    },
    [pushError, send],
  )

  const runPrompt = useCallback(
    (prompt) => {
      if (!currentSession || !prompt.trim()) return false
      setPromptRunning(true)
      const requestId = send({
        type: 'prompt',
        sessionId: currentSession.sessionId,
        prompt,
      })
      if (!requestId) {
        setPromptRunning(false)
        return false
      }
      return true
    },
    [currentSession, send],
  )

  const evalForm = useCallback(
    (code, namespace) => {
      if (!code.trim()) return false
      setEvalRunning(true)
      const evalNamespace = namespace?.trim() || sessionNamespace(currentSession)
      const requestId = send({
        type: 'eval',
        code,
        ns: evalNamespace,
      })
      if (!requestId) {
        setEvalRunning(false)
        return false
      }
      setReplEntries((previous) => [
        ...previous,
        {
          id: errorId(),
          requestId,
          code,
          namespace: evalNamespace,
          responses: [],
        },
      ])
      return true
    },
    [currentSession, send],
  )

  const clearRepl = useCallback(() => setReplEntries([]), [])

  return {
    status,
    sessionInfo,
    sessions,
    agentConfigs,
    plugins,
    selectedConfigId,
    currentSession,
    updates,
    promptRunning,
    replEntries,
    evalRunning,
    errors,
    newSession,
    selectAgentConfig,
    refreshSessions,
    loadSession,
    deleteSession,
    renameSession,
    saveAgentConfig,
    deleteAgentConfig,
    runPrompt,
    evalForm,
    clearRepl,
    dismissError,
  }
}
