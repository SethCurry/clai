import { useCallback, useEffect, useRef, useState } from 'react'
import { PATH_PREFIX, pathAt } from './paths.js'

// Wait for a short pause in typing before asking the daemon, so a path like
// `./src/clai/daemon` produces one request instead of one per keystroke.
const DEBOUNCE_MS = 120

/**
 * Fetch the files and folders that match the typed `path` in `sessionId`'s
 * working directory. Returns only the entries; a failed request is treated as
 * no matches, since a stale or unavailable daemon should not interrupt typing.
 */
async function fetchCompletions(sessionId, path, signal) {
  const url =
    `/api/sessions/${encodeURIComponent(String(sessionId))}/completions` +
    `?path=${encodeURIComponent(path)}`
  const response = await fetch(url, { signal })
  if (!response.ok) return []
  const body = await response.json()
  return Array.isArray(body.entries) ? body.entries : []
}

/**
 * `./`-triggered file completion for a prompt textarea.
 *
 * The hook watches the caret in `value`, asks the daemon's completion endpoint
 * whenever the user is typing a word that starts with `./`, and applies the
 * selected entry back into the text. Picking a directory reopens the list for
 * its contents; picking a file inserts a trailing space and closes the list.
 *
 * Call `onChange` from the textarea's change handler, `onKeyDown` before the
 * form's own Enter handling, and `onBlur` to dismiss the popup.
 */
export function usePathAutocomplete({ sessionId, value, onChange, textareaRef }) {
  const [completion, setCompletion] = useState(null)
  const completionRef = useRef(null)
  const timerRef = useRef(null)
  const controllerRef = useRef(null)
  const sequenceRef = useRef(0)
  // The token the user dismissed with Escape. The popup stays closed until the
  // token changes, so Escape is not undone by the next keystroke.
  const dismissedRef = useRef(null)
  // Caret position to restore after `onChange` replaces the draft.
  const pendingRef = useRef(null)

  const update = useCallback((next) => {
    completionRef.current = next
    setCompletion(next)
  }, [])

  /** Abort any in-flight request and close the popup. */
  const close = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
    controllerRef.current?.abort()
    controllerRef.current = null
    sequenceRef.current += 1
    update(null)
  }, [update])

  /** Ask the daemon for entries matching the path `token`. */
  const load = useCallback(
    (token, immediate) => {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current)
      controllerRef.current?.abort()
      const sequence = ++sequenceRef.current
      const run = () => {
        timerRef.current = null
        const controller = new AbortController()
        controllerRef.current = controller
        fetchCompletions(sessionId, token.path, controller.signal)
          .then((entries) => {
            if (sequence !== sequenceRef.current) return
            update({
              start: token.start,
              end: token.end,
              token: token.text,
              path: token.path,
              entries,
              index: 0,
            })
          })
          .catch((failure) => {
            if (failure?.name === 'AbortError') return
            if (sequence !== sequenceRef.current) return
            update(null)
          })
      }
      if (immediate) run()
      else timerRef.current = window.setTimeout(run, DEBOUNCE_MS)
    },
    [sessionId, update],
  )

  /** Re-read the path token at `caret` and refresh the popup. */
  const refresh = useCallback(
    (text, caret, immediate = false) => {
      const token = pathAt(text, caret)
      if (!token || !sessionId) {
        close()
        return
      }
      if (dismissedRef.current === token.text) {
        close()
        return
      }
      dismissedRef.current = null
      load(token, immediate)
    },
    [close, load, sessionId],
  )

  /** Handle the textarea's change event, then refresh the popup. */
  const handleChange = useCallback(
    (event) => {
      const text = event.target.value
      onChange(text)
      refresh(text, event.target.selectionStart)
    },
    [onChange, refresh],
  )

  /** Insert `entry` into the draft and move the caret past it. */
  const pick = useCallback(
    (entry) => {
      const current = completionRef.current
      if (!current || !entry) return
      const directory = entry.type === 'directory'
      const inserted = `${PATH_PREFIX}${entry.path}${directory ? '' : ' '}`
      const caret = current.start + inserted.length
      const nextValue =
        value.slice(0, current.start) + inserted + value.slice(current.end)
      dismissedRef.current = null
      pendingRef.current = { caret, refresh: directory }
      onChange(nextValue)
      update(null)
    },
    [onChange, update, value],
  )

  /** Highlight entry `index`, used when the pointer hovers a row. */
  const setIndex = useCallback(
    (index) => {
      const current = completionRef.current
      if (!current || index === current.index) return
      update({ ...current, index })
    },
    [update],
  )

  /**
   * Handle ArrowUp/ArrowDown/Enter/Tab/Escape while the popup is open. Returns
   * true when the key was consumed, so the caller skips submitting the prompt.
   */
  const handleKeyDown = useCallback(
    (event) => {
      const current = completionRef.current
      if (!current) return false
      if (event.key === 'Escape') {
        event.preventDefault()
        dismissedRef.current = current.token
        close()
        return true
      }
      if (current.entries.length === 0) return false
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        const delta = event.key === 'ArrowDown' ? 1 : -1
        const count = current.entries.length
        update({ ...current, index: (current.index + delta + count) % count })
        return true
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault()
        pick(current.entries[current.index])
        return true
      }
      return false
    },
    [close, pick, update],
  )

  /** Dismiss the popup when the textarea loses focus. */
  const handleBlur = useCallback(() => close(), [close])

  // Restore the caret after `pick` rewrites the draft, and continue browsing
  // when the pick was a directory.
  useEffect(() => {
    const pending = pendingRef.current
    if (!pending) return
    pendingRef.current = null
    const node = textareaRef?.current
    if (node) {
      node.focus()
      node.setSelectionRange(pending.caret, pending.caret)
    }
    if (pending.refresh) refresh(value, pending.caret, true)
  }, [refresh, textareaRef, value])

  return {
    completion,
    onChange: handleChange,
    onKeyDown: handleKeyDown,
    onBlur: handleBlur,
    pick,
    setIndex,
    close,
  }
}
