const SESSION_NAMESPACE_PREFIX = 'sessions'

/**
 * Read the id out of a session map, or accept a bare id. Missing ids return
 * null so the caller can fall back to the default namespace.
 */
function sessionId(session) {
  if (session == null) return null
  if (typeof session === 'object') return session.sessionId ?? null
  return session
}

/**
 * The Clojure namespace clai attaches to a session, matching
 * `clai.repl/session-ns`: `sessions.<id>` with everything but letters,
 * digits, dots, underscores, and hyphens replaced by underscores.
 *
 * Without a selected session the REPL evaluates in `user` instead.
 */
export function sessionNamespace(session) {
  const id = sessionId(session)
  if (id == null || String(id).trim() === '') return 'user'
  const segment = String(id).replace(/[^A-Za-z0-9_.-]/g, '_')
  return `${SESSION_NAMESPACE_PREFIX}.${segment}`
}
