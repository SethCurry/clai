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
