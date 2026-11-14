import { responseBody } from './api.js'

/**
 * Credentials, validation, and the session calls behind the login page.
 *
 * The daemon keeps sessions in a cookie (`clai.auth/cookie-name`), which the
 * browser sends back on every request and WebSocket upgrade, so nothing here
 * carries a token: `logIn` posts the form, `currentSession` asks who the cookie
 * belongs to, and `logOut` revokes it.
 */

/** The values the login form starts from, and returns to after a failed try. */
export const EMPTY_CREDENTIALS = { username: '', password: '' }

/**
 * Trim the form's values into the body the daemon expects. Passwords are not
 * trimmed: they are stored and compared exactly as typed.
 */
export function formToCredentials(form) {
  return {
    username: String(form?.username ?? '').trim(),
    password: String(form?.password ?? ''),
  }
}

/**
 * The message to show when `form` cannot be submitted yet, or null when it can.
 * The daemon rejects either blank field too, but catching it here saves a round
 * trip.
 */
export function credentialError(form) {
  const { username, password } = formToCredentials(form)
  if (!username) return 'Enter your username.'
  if (!password) return 'Enter your password.'
  return null
}

/**
 * Post credentials to `POST /api/auth/login` and return the signed-in user.
 * Throws an `Error` carrying the daemon's message, so the form can show
 * "Invalid username or password" in place.
 */
export async function logIn(credentials) {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(formToCredentials(credentials)),
  })
  const body = await responseBody(response)
  if (!response.ok) {
    throw new Error(body.error || `Could not sign in (${response.status}).`)
  }
  return body.user ?? null
}

/**
 * Ask the daemon who the current cookie belongs to. Answers
 * `{authenticated: false, user: null}` when there is no session, so it never
 * throws for the ordinary "not signed in yet" case.
 */
export async function currentSession() {
  const response = await fetch('/api/auth/me')
  const body = await responseBody(response)
  if (!response.ok) {
    throw new Error(body.error || `Could not read the session (${response.status}).`)
  }
  return { authenticated: body.authenticated === true, user: body.user ?? null }
}

/**
 * Revoke the current session and drop the cookie. Signing out of a session that
 * is already gone is not an error.
 */
export async function logOut() {
  const response = await fetch('/api/auth/logout', { method: 'POST' })
  if (!response.ok) {
    const body = await responseBody(response)
    throw new Error(body.error || `Could not sign out (${response.status}).`)
  }
}
