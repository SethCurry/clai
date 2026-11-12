import { useCallback, useEffect, useRef, useState } from 'react'
import { responseBody } from '../api.js'
import { docSummary, varSymbol, visibleRows } from '../cljExplorer.js'
import { sessionNamespace } from '../sessionNamespace.js'

/** The daemon URL for the loaded Clojure namespaces. */
const NAMESPACES_URL = '/api/namespaces'

/** The daemon URL for one namespace's Vars. */
function namespaceVarsUrl(namespace) {
  return `${NAMESPACES_URL}/${encodeURIComponent(namespace)}/vars`
}

/** The daemon URL for one Var's docstring, source, and value. */
function varDetailUrl(namespace, name) {
  return (
    `${NAMESPACES_URL}/${encodeURIComponent(namespace)}/var` +
    `?name=${encodeURIComponent(name)}`
  )
}

/**
 * Request `url` and return its JSON body, turning a bad response into an Error
 * carrying the daemon's message.
 */
async function fetchJson(url, signal) {
  const response = await fetch(url, { signal })
  const body = await responseBody(response)
  if (!response.ok) {
    throw new Error(body.error || `Request failed (${response.status}).`)
  }
  return body
}

/** List every namespace loaded in the daemon's JVM. */
async function fetchNamespaces(signal) {
  const body = await fetchJson(NAMESPACES_URL, signal)
  return Array.isArray(body.namespaces) ? body.namespaces : []
}

/** List one namespace's public Vars, keeping the daemon's `missing` flag. */
async function fetchVars(namespace, signal) {
  const body = await fetchJson(namespaceVarsUrl(namespace), signal)
  return {
    status: 'ready',
    entries: Array.isArray(body.vars) ? body.vars : [],
    missing: Boolean(body.missing),
  }
}

/** Read one Var's docstring, argument lists, source, and value. */
function fetchVar(namespace, name, signal) {
  return fetchJson(varDetailUrl(namespace, name), signal)
}

/** Indentation for a row at `depth`, in pixels. */
function indent(depth) {
  return { paddingLeft: `${8 + depth * 12}px` }
}

/**
 * One row of the tree: a namespace to open, a Var to preview, or a note
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

  const namespace = row.kind === 'namespace'
  const active =
    !namespace &&
    selected?.namespace === row.namespace &&
    selected?.name === row.name
  // Namespaces say how much they hold; Vars say what they are for.
  const hint = namespace
    ? `${row.varCount} ${row.varCount === 1 ? 'var' : 'vars'}`
    : docSummary(row.doc)
  const icon = namespace
    ? row.open
      ? '▾'
      : '▸'
    : row.macro
      ? 'm'
      : row.arglists
        ? 'ƒ'
        : '·'
  return (
    <li className="explorer__item">
      <button
        type="button"
        className={`explorer__row${active ? ' explorer__row--active' : ''}`}
        style={indent(row.depth)}
        aria-expanded={namespace ? row.open : undefined}
        aria-current={active ? 'true' : undefined}
        title={namespace ? row.namespace : varSymbol(row)}
        onClick={() => (namespace ? onToggle(row.namespace) : onSelect(row))}
      >
        <span className="explorer__icon" aria-hidden="true">
          {icon}
        </span>
        <span className="explorer__label">{row.name}</span>
        {hint && <span className="explorer__hint">{hint}</span>}
      </button>
    </li>
  )
}

/** One labelled block of the preview pane, hidden when it has no text. */
function Section({ label, text }) {
  if (!text) return null
  return (
    <section className="clj-preview__section">
      <h3 className="clj-preview__label">{label}</h3>
      <pre className="clj-preview__text">{text}</pre>
    </section>
  )
}

/**
 * Where the Var is defined: `file:line`, or the file alone when the Var has no
 * line number.
 */
function definedIn({ file, line }) {
  if (!file) return ''
  return line ? `${file}:${line}` : file
}

/** A button that copies `text` to the clipboard, so it can be pasted in the REPL. */
function CopySymbol({ text }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      type="button"
      className="button button--quiet"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text)
          setCopied(true)
        } catch {
          setCopied(false)
        }
      }}
    >
      {copied ? 'copied' : 'copy'}
    </button>
  )
}

/** The selected Var, or the reason it could not be read. */
function Preview({ preview, onClose }) {
  const detail = preview?.status === 'ready' ? preview.detail : null
  const name = detail ? varSymbol(detail) : preview ? varSymbol(preview) : ''
  return (
    <section className="explorer__preview">
      <header className="explorer__preview-head">
        <span className="explorer__preview-name" title={name}>
          {name}
        </span>
        <div className="explorer__actions">
          {detail && <CopySymbol key={name} text={name} />}
          <button
            className="explorer__close"
            onClick={onClose}
            aria-label="Close preview"
          >
            ×
          </button>
        </div>
      </header>
      {preview?.status === 'loading' && (
        <p className="explorer__note">loading…</p>
      )}
      {preview?.status === 'error' && (
        <p className="explorer__note explorer__note--error">
          {preview.message}
        </p>
      )}
      {detail && (
        <div className="clj-preview">
          {detail.macro && <p className="explorer__note">macro</p>}
          <Section label="Usage" text={detail.arglists} />
          <Section label="Docstring" text={detail.doc} />
          <Section label="Malli schema" text={detail.malliSchema} />
          <Section label="Defined in" text={definedIn(detail)} />
          <Section label="Source" text={detail.source} />
          <Section label="Value" text={detail.value} />
        </div>
      )}
    </section>
  )
}

/**
 * A read-only explorer for the Clojure runtime behind the daemon: the loaded
 * namespaces, the Vars each one holds, and one Var's docstring, argument
 * lists, malli schema, source, and value.
 *
 * Namespaces are fetched when the panel opens and their Vars the first time
 * they are expanded, so the widget only reads the branches the user looks at.
 * The session's own namespace (`sessions.<id>`, or `user` without a session)
 * starts open, because that is where the REPL the panel feeds defines things.
 *
 * Mount this with a `key` of the session id, so switching sessions starts from
 * a fresh tree.
 */
export function ClojureExplorer({ currentSession }) {
  // The namespace the REPL panel evaluates in, opened by default.
  const currentNamespace = sessionNamespace(currentSession)
  const [expanded, setExpanded] = useState(() => new Set([currentNamespace]))
  // `{status, entries | message}` for the namespace list.
  const [namespaces, setNamespaces] = useState({ status: 'loading' })
  // `{namespace -> {status, entries | message}}`, filled in as branches open.
  const [vars, setVars] = useState({})
  const [filter, setFilter] = useState('')
  // The Var being previewed: `{namespace, name}` or null.
  const [selected, setSelected] = useState(null)
  const [preview, setPreview] = useState(null)
  // Bumped by Refresh to re-read the tree and the open preview.
  const [reload, setReload] = useState(0)
  const controllersRef = useRef(new Set())
  const rows = visibleRows({
    namespaces,
    vars,
    expanded,
    filter,
    sessionNamespace: currentNamespace,
  })

  // Abort in-flight reads when the widget goes away.
  useEffect(() => {
    const controllers = controllersRef.current
    return () => {
      for (const controller of controllers) controller.abort()
      controllers.clear()
    }
  }, [])

  // Load the namespace list, and reload it on Refresh.
  useEffect(() => {
    const controller = new AbortController()
    controllersRef.current.add(controller)
    setNamespaces({ status: 'loading' })
    fetchNamespaces(controller.signal)
      .then((entries) => setNamespaces({ status: 'ready', entries }))
      .catch((failure) => {
        if (failure?.name === 'AbortError') return
        setNamespaces({ status: 'error', message: failure.message })
      })
      .finally(() => controllersRef.current.delete(controller))
    return () => controller.abort()
  }, [reload])

  // Fetch every open namespace that has not been read yet. Opening a namespace
  // adds it to `expanded`, which is what makes this fetch it.
  useEffect(() => {
    for (const namespace of expanded) {
      if (vars[namespace]) continue
      const controller = new AbortController()
      controllersRef.current.add(controller)
      setVars((previous) => ({
        ...previous,
        [namespace]: { status: 'loading' },
      }))
      fetchVars(namespace, controller.signal)
        .then((listing) => {
          setVars((previous) => ({ ...previous, [namespace]: listing }))
        })
        .catch((failure) => {
          if (failure?.name === 'AbortError') {
            // Forget the cancelled read so the effect asks again instead of
            // leaving the branch showing `loading…` for good.
            setVars((previous) => {
              const next = { ...previous }
              delete next[namespace]
              return next
            })
            return
          }
          setVars((previous) => ({
            ...previous,
            [namespace]: { status: 'error', message: failure.message },
          }))
        })
        .finally(() => controllersRef.current.delete(controller))
    }
  }, [expanded, vars])

  // Read the selected Var, replacing a preview that is already showing.
  useEffect(() => {
    if (!selected) {
      setPreview(null)
      return undefined
    }
    const controller = new AbortController()
    setPreview({ status: 'loading', ...selected })
    fetchVar(selected.namespace, selected.name, controller.signal)
      .then((detail) => setPreview({ status: 'ready', detail }))
      .catch((failure) => {
        if (failure?.name === 'AbortError') return
        setPreview({ status: 'error', ...selected, message: failure.message })
      })
    return () => controller.abort()
  }, [selected, reload])

  const toggle = useCallback((namespace) => {
    setExpanded((previous) => {
      const next = new Set(previous)
      if (next.has(namespace)) next.delete(namespace)
      else next.add(namespace)
      return next
    })
  }, [])

  /** Preview `row`, or stop previewing the Var that is already selected. */
  const select = useCallback((row) => {
    setSelected((previous) =>
      previous?.namespace === row.namespace && previous?.name === row.name
        ? null
        : { namespace: row.namespace, name: row.name },
    )
  }, [])

  /** Re-read every namespace, every open namespace's Vars, and the preview. */
  const refresh = useCallback(() => {
    setVars({})
    setReload((count) => count + 1)
  }, [])

  return (
    <div className="explorer__panel">
      <header className="explorer__header">
        <div className="explorer__heading">
          <p title={currentNamespace}>{currentNamespace}</p>
        </div>
        <div className="explorer__actions">
          <button className="button button--quiet" onClick={refresh}>
            Refresh
          </button>
        </div>
      </header>

      <div className="explorer__filter">
        <input
          type="search"
          value={filter}
          placeholder="Filter namespaces and vars"
          aria-label="Filter namespaces and vars"
          onChange={(event) => setFilter(event.target.value)}
        />
      </div>

      <div className="explorer__tree">
        <ul className="explorer__list">
          {rows.map((row) => (
            <TreeRow
              key={row.key}
              row={row}
              selected={selected}
              onToggle={toggle}
              onSelect={select}
            />
          ))}
        </ul>
      </div>

      {selected && (
        <Preview preview={preview} onClose={() => setSelected(null)} />
      )}
    </div>
  )
}
