import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { requestJson } from './api.js'
import { noteId, sortNotes } from './notes.js'

// Wait for a pause in typing before searching, so a query like "bm25 ranking"
// costs one request instead of one per keystroke.
const SEARCH_DEBOUNCE_MS = 250

// How many search matches to ask the daemon for. The endpoint defaults to 20,
// which is fewer than a list column can usefully show.
const SEARCH_LIMIT = 50

function notePath(id) {
  return `/api/notes/${encodeURIComponent(id)}`
}

function jsonOptions(method, body) {
  return {
    method,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  }
}

/**
 * The list-row shape of a saved note: a detail response without its link
 * lists, so the row matches what `/api/notes` returns.
 */
function toRow(detail) {
  const { links, backlinks, ...row } = detail
  return row
}

/**
 * Put `row` into `list` in sorted position, replacing the entry with the same
 * id. A null `list` (no search running) stays null.
 */
function upsert(list, row) {
  if (list == null) return null
  const id = noteId(row)
  return sortNotes([...list.filter((note) => noteId(note) !== id), row])
}

/**
 * Own the notes page's data: the full note list, the search results for the
 * current query, the selected note with its links, and the writes that keep
 * all three in sync.
 *
 * `list` is what the page renders: the search results while a query is
 * active, and the full note list otherwise. Mutation errors are stored in `error`
 * for the page banner and rethrown so an editor can also show them inline.
 */
export function useNotes() {
  const [stored, setStored] = useState([])
  const [query, setQuery] = useState('')
  const [results, setResults] = useState(null)
  const [selectedId, setSelectedId] = useState(null)
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(true)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState('')
  // Selecting two notes in quick succession must land on the later one, so
  // stale detail responses are dropped instead of overwriting the newer one.
  const detailSequence = useRef(0)

  const report = useCallback((failure) => {
    setError(failure?.message || String(failure))
  }, [])

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const body = await requestJson('/api/notes')
      setStored(sortNotes(body.notes ?? []))
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

  // Search on a debounce; an empty query clears the results and shows the
  // full list again. Aborting the previous request keeps a slow response from
  // replacing the results of a newer query.
  useEffect(() => {
    const text = query.trim()
    if (!text) {
      setResults(null)
      setSearching(false)
      return undefined
    }
    const controller = new AbortController()
    setSearching(true)
    const timer = window.setTimeout(async () => {
      try {
        const body = await requestJson(
          `/api/notes/search?q=${encodeURIComponent(text)}&limit=${SEARCH_LIMIT}`,
          { signal: controller.signal },
        )
        setResults(sortNotes(body.notes ?? []))
        setError('')
      } catch (failure) {
        if (!controller.signal.aborted) report(failure)
      } finally {
        if (!controller.signal.aborted) setSearching(false)
      }
    }, SEARCH_DEBOUNCE_MS)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
  }, [query, report])

  /** Load one note with its links and backlinks, and select it. */
  const select = useCallback(
    async (id) => {
      const key = id == null ? null : String(id)
      detailSequence.current += 1
      const sequence = detailSequence.current
      setSelectedId(key)
      if (key == null) {
        setDetail(null)
        return null
      }
      try {
        const loaded = await requestJson(notePath(key))
        if (sequence === detailSequence.current) {
          setDetail(loaded)
          setError('')
        }
        return loaded
      } catch (failure) {
        if (sequence === detailSequence.current) {
          setDetail(null)
          report(failure)
        }
        return null
      }
    },
    [report],
  )

  /**
   * Create (no `id`) or replace (`id`) a note, then select the saved note and
   * update its row in both lists.
   */
  const saveNote = useCallback(
    async (input, id) => {
      try {
        const saved = await requestJson(
          id ? notePath(id) : '/api/notes',
          jsonOptions(id ? 'PUT' : 'POST', input),
        )
        const row = toRow(saved)
        setStored((previous) => upsert(previous, row))
        setResults((previous) => upsert(previous, row))
        detailSequence.current += 1
        setDetail(saved)
        setSelectedId(noteId(saved))
        setError('')
        return saved
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report],
  )

  /** Delete a note, clearing the selection when it was the selected one. */
  const deleteNote = useCallback(
    async (id) => {
      const key = String(id)
      try {
        await requestJson(notePath(key), { method: 'DELETE' })
        setStored((previous) =>
          previous.filter((note) => noteId(note) !== key),
        )
        setResults((previous) =>
          previous == null
            ? null
            : previous.filter((note) => noteId(note) !== key),
        )
        setSelectedId((current) => (current === key ? null : current))
        // Drop the open note when it is the one that was just deleted.
        if (selectedId === key) {
          detailSequence.current += 1
          setDetail(null)
        }
        setError('')
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report, selectedId],
  )

  // `stored` and `results` are both kept sorted, so the list needs no re-sort.
  const list = useMemo(() => results ?? stored, [results, stored])

  return {
    list,
    stored,
    query,
    setQuery,
    searching,
    selectedId,
    detail,
    loading,
    error,
    refresh,
    dismissError: () => setError(''),
    select,
    saveNote,
    deleteNote,
  }
}
