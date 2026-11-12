import { useCallback, useEffect, useRef, useState } from 'react'
import { responseBody } from '../api.js'
import { LIST_LIMIT, fileName, visibleRows } from '../fileExplorer.js'

/**
 * The daemon URL for one session-scoped filesystem read. `resource` is
 * `files` for a directory listing or `file` for one file's text.
 */
function sessionFileUrl(sessionId, resource, path) {
  return (
    `/api/sessions/${encodeURIComponent(String(sessionId))}/${resource}` +
    `?path=${encodeURIComponent(path)}`
  )
}

/**
 * List one directory of `sessionId`'s working directory. A `path` that ends
 * in `/` lists the whole directory; `""` lists the working directory itself.
 */
async function fetchEntries(sessionId, path, signal) {
  const response = await fetch(
    `${sessionFileUrl(sessionId, 'files', path)}&limit=${LIST_LIMIT}`,
    { signal },
  )
  const body = await responseBody(response)
  if (!response.ok) {
    throw new Error(
      body.error || `Could not list this folder (${response.status}).`,
    )
  }
  return Array.isArray(body.entries) ? body.entries : []
}

/** Read one file of `sessionId`'s working directory for the preview pane. */
async function fetchFile(sessionId, path, signal) {
  const response = await fetch(sessionFileUrl(sessionId, 'file', path), {
    signal,
  })
  const body = await responseBody(response)
  if (!response.ok) {
    throw new Error(
      body.error || `Could not read this file (${response.status}).`,
    )
  }
  return body
}

/** Indentation for a row at `depth`, in pixels. */
function indent(depth) {
  return { paddingLeft: `${8 + depth * 12}px` }
}

/**
 * One row of the tree: a directory to open, a file to preview, or a note
 * standing in for a branch that is loading, empty, or unreadable.
 */
function TreeRow({ row, selected, onToggle, onSelect }) {
  if (row.kind === 'note' || row.kind === 'error') {
    return (
      <li className="explorer__item">
        <p
          className={`explorer__note${
            row.kind === 'error' ? ' explorer__note--error' : ''
          }`}
          style={indent(row.depth)}
        >
          {row.text}
        </p>
      </li>
    )
  }

  const directory = row.kind === 'directory'
  const active = !directory && row.path === selected
  return (
    <li className="explorer__item">
      <button
        type="button"
        className={`explorer__row${active ? ' explorer__row--active' : ''}`}
        style={indent(row.depth)}
        aria-expanded={directory ? row.open : undefined}
        aria-current={active ? 'true' : undefined}
        title={row.path}
        onClick={() => (directory ? onToggle(row.path) : onSelect(row.path))}
      >
        <span className="explorer__icon" aria-hidden="true">
          {directory ? (row.open ? '▾' : '▸') : '·'}
        </span>
        <span className="explorer__label">
          {row.name}
          {directory ? '/' : ''}
        </span>
      </button>
    </li>
  )
}

/** The text of the selected file, or the reason it could not be read. */
function Preview({ preview }) {
  if (!preview) return null
  if (preview.status === 'loading') {
    return <p className="explorer__note">loading…</p>
  }
  if (preview.status === 'error') {
    return (
      <p className="explorer__note explorer__note--error">{preview.message}</p>
    )
  }
  if (preview.binary) {
    return (
      <p className="explorer__note">
        This file is not text, so it is not shown.
      </p>
    )
  }
  return (
    <>
      <pre className="explorer__content">{preview.content}</pre>
      {preview.truncated && (
        <p className="explorer__note">Showing the start of a large file.</p>
      )}
    </>
  )
}

/**
 * A read-only file explorer for the selected session's working directory.
 *
 * Directories are fetched the first time they are opened, so the widget only
 * reads the branches the user looks at. Clicking a file previews its text,
 * which the daemon truncates rather than streaming a whole large file.
 *
 * This renders the body of the explorer panel; `ExplorerPanel` draws the tabs
 * and the close button around it. Mount it with a `key` of the session id, so
 * switching sessions starts from a fresh tree.
 */
export function FileExplorer({ currentSession }) {
  const sessionId = currentSession?.sessionId ?? null
  // Open directories. The working directory (`""`) starts open.
  const [expanded, setExpanded] = useState(() => new Set(['']))
  // `{path -> {status, entries | message}}`, filled in as branches open.
  const [listings, setListings] = useState({})
  // The relative path of the file being previewed, if any.
  const [selected, setSelected] = useState(null)
  const [preview, setPreview] = useState(null)
  // Bumped by Refresh to re-read the tree and the open preview.
  const [reload, setReload] = useState(0)
  const controllersRef = useRef(new Set())
  const rows = visibleRows(listings, expanded)

  // Abort in-flight reads when the widget goes away.
  useEffect(() => {
    const controllers = controllersRef.current
    return () => {
      for (const controller of controllers) controller.abort()
      controllers.clear()
    }
  }, [])

  const loadDirectory = useCallback(
    async (path) => {
      const controller = new AbortController()
      controllersRef.current.add(controller)
      setListings((previous) => ({
        ...previous,
        [path]: { status: 'loading' },
      }))
      try {
        const entries = await fetchEntries(sessionId, path, controller.signal)
        setListings((previous) => ({
          ...previous,
          [path]: { status: 'ready', entries },
        }))
      } catch (failure) {
        if (failure?.name === 'AbortError') {
          // Forget the cancelled load so the effect below asks again. Without
          // this a cancelled read -- StrictMode re-running effects, say --
          // would leave the branch showing `loading…` for good.
          setListings((previous) => {
            const next = { ...previous }
            delete next[path]
            return next
          })
          return
        }
        setListings((previous) => ({
          ...previous,
          [path]: { status: 'error', message: failure.message },
        }))
      } finally {
        controllersRef.current.delete(controller)
      }
    },
    [sessionId],
  )

  // Fetch every open directory that has no listing yet. Opening a directory
  // adds it to `expanded`, which is what makes this fetch it.
  useEffect(() => {
    if (!sessionId) return
    for (const path of expanded) {
      if (!listings[path]) loadDirectory(path)
    }
  }, [expanded, listings, loadDirectory, sessionId])

  // Read the selected file, replacing a preview that is already showing.
  useEffect(() => {
    if (!sessionId || !selected) {
      setPreview(null)
      return undefined
    }
    const controller = new AbortController()
    setPreview({ status: 'loading', path: selected })
    fetchFile(sessionId, selected, controller.signal)
      .then((file) => setPreview({ status: 'ready', ...file }))
      .catch((failure) => {
        if (failure?.name === 'AbortError') return
        setPreview({ status: 'error', path: selected, message: failure.message })
      })
    return () => controller.abort()
  }, [sessionId, selected, reload])

  const toggle = useCallback((path) => {
    setExpanded((previous) => {
      const next = new Set(previous)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }, [])

  /** Preview `path`, or stop previewing it when it is already selected. */
  const select = useCallback((path) => {
    setSelected((previous) => (previous === path ? null : path))
  }, [])

  /** Re-read every open directory and the open preview. */
  const refresh = useCallback(() => {
    setListings({})
    setReload((count) => count + 1)
  }, [])

  const previewPath = preview?.path ?? selected

  return (
    <div className="explorer__panel">
      <header className="explorer__header">
        <div className="explorer__heading">
          <p title={currentSession?.cwd}>
            {currentSession?.cwd || 'no session selected'}
          </p>
        </div>
        <div className="explorer__actions">
          <button
            className="button button--quiet"
            onClick={refresh}
            disabled={!sessionId}
          >
            Refresh
          </button>
        </div>
      </header>

      {sessionId ? (
        <div className="explorer__tree">
          <ul className="explorer__list">
            {rows.map((row) => (
              <TreeRow
                key={`${row.kind}-${row.path}`}
                row={row}
                selected={selected}
                onToggle={toggle}
                onSelect={select}
              />
            ))}
          </ul>
        </div>
      ) : (
        <p className="explorer__note">
          Pick a session to browse the files it works in.
        </p>
      )}

      {previewPath && (
        <section className="explorer__preview">
          <header className="explorer__preview-head">
            <span className="explorer__preview-name" title={previewPath}>
              {preview?.name ?? fileName(previewPath)}
            </span>
            <button
              className="explorer__close"
              onClick={() => setSelected(null)}
              aria-label="Close preview"
            >
              ×
            </button>
          </header>
          <Preview preview={preview} />
        </section>
      )}
    </div>
  )
}
