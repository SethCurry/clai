import { useCallback, useEffect, useState } from 'react'
import { UNAUTHORIZED_EVENT } from './api.js'
import { currentSession, logIn, logOut } from './auth.js'

/**
 * The message shown when the daemon throws the current session out while the
 * app is open (expired token, revoked by another tab, database reset).
 */
const EXPIRED_MESSAGE = 'Your session has ended. Sign in again.'

/**
 * Own the browser's session state, so `App` can decide between the login page
 * and the console.
 *
 * `status` is `checking` until the daemon answers `/api/auth/me`, then
 * `authenticated` or `anonymous`. `error` holds the message the login form
 * shows, and `busy` is true while a sign-in or sign-out is in flight.
 */
export function useAuth() {
  const [status, setStatus] = useState('checking')
  const [user, setUser] = useState(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  // Re-check the session on load, so a reload keeps the user signed in and a
  // stale cookie takes them to the login page.
  useEffect(() => {
    let cancelled = false

    currentSession()
      .then((session) => {
        if (cancelled) return
        setUser(session.user)
        setStatus(session.authenticated ? 'authenticated' : 'anonymous')
      })
      .catch(() => {
        // The daemon is unreachable or its database is down. Say nothing about
        // credentials; the login attempt will report the real problem.
        if (cancelled) return
        setStatus('anonymous')
      })

    return () => {
      cancelled = true
    }
  }, [])

  // Any authenticated request can discover the session is gone; see
  // `api.js/notifyUnauthorized`.
  useEffect(() => {
    const onUnauthorized = () => {
      setUser(null)
      setStatus('anonymous')
      setError(EXPIRED_MESSAGE)
    }
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  const signIn = useCallback(async (credentials) => {
    setBusy(true)
    setError('')
    try {
      const signedIn = await logIn(credentials)
      setUser(signedIn)
      setStatus('authenticated')
      return true
    } catch (failure) {
      setError(failure.message || String(failure))
      return false
    } finally {
      setBusy(false)
    }
  }, [])

  const signOut = useCallback(async () => {
    setBusy(true)
    try {
      await logOut()
    } catch {
      // Revoking is best effort: the cookie is cleared either way, so treat a
      // failure as already signed out.
    } finally {
      setUser(null)
      setStatus('anonymous')
      setError('')
      setBusy(false)
    }
  }, [])

  const dismissError = useCallback(() => setError(''), [])

  return { status, user, error, busy, signIn, signOut, dismissError }
}
