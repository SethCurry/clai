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
 */
export async function requestJson(path, options = {}) {
  const response = await fetch(path, options)
  const body = await responseBody(response)
  if (!response.ok) {
    throw new Error(body.error || `Request failed (${response.status}).`)
  }
  return body
}
