/**
 * The event the UI listens for when the daemon reports that the session is
 * gone: `useAuth` turns it into the login page.
 */
export const UNAUTHORIZED_EVENT = 'clai:unauthorized'

/**
 * Tell the app that the daemon stopped accepting its session. Safe to call
 * outside a browser (node tests import this module) and more than once.
 */
export function notifyUnauthorized() {
  if (typeof window !== 'undefined' && window.dispatchEvent) {
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
  }
}

/**
 * Read a `fetch` response body as JSON, returning `{}` for an empty or
 * unparsable body so callers can report the HTTP status instead of a parse
 * error.
 */
export async function responseBody(response) {
  const text = await response.text()
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return {}
  }
}

/**
 * Fetch `path` and parse its JSON response. A non-2xx response is turned into
 * an `Error` carrying the daemon's message, so callers can show it directly
 * instead of decoding the body twice.
 *
 * A 401 also announces [[UNAUTHORIZED_EVENT]]: every caller here runs inside an
 * authenticated view, so a 401 means the session expired and the app should
 * return to the login page. The login form itself uses `auth.js`, which does
 * not announce it.
 */
export async function requestJson(path, options = {}) {
  const response = await fetch(path, options)
  const body = await responseBody(response)
  if (response.status === 401) notifyUnauthorized()
  if (!response.ok) {
    throw new Error(body.error || `Request failed (${response.status}).`)
  }
  return body
}
