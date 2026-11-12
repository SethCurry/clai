import { useEffect, useRef, useState } from 'react'
import { sessionNamespace } from '../sessionNamespace.js'

function Response({ response }) {
  if (!response) return null
  const hasStatusError =
    Array.isArray(response.status) && response.status.includes('eval-error')
  const hasContent =
    response.out ||
    response.err ||
    response.value != null ||
    response.ex ||
    hasStatusError
  // nREPL sends a trailing response that only carries a `done` status.
  if (!hasContent) return null
  return (
    <div className="repl__response">
      {response.out && <pre className="repl__out">{response.out}</pre>}
      {response.err && <pre className="repl__err">{response.err}</pre>}
      {response.value != null && (
        <pre className="repl__value">=&gt; {response.value}</pre>
      )}
      {response.ex && <pre className="repl__err">{response.ex}</pre>}
      {hasStatusError && !response.err && !response.ex && (
        <pre className="repl__err">Evaluation failed.</pre>
      )}
    </div>
  )
}

/**
 * One submitted form and the responses it produced. The form stays visible so
 * the REPL output reads as a history of commands, not just their results.
 */
function Entry({ entry }) {
  return (
    <article className="repl__entry">
      <header className="repl__entry-header">
        {entry.namespace && (
          <span className="repl__entry-ns">{entry.namespace}</span>
        )}
        <pre className="repl__code">{entry.code}</pre>
      </header>
      {(entry.responses ?? []).map((response, index) => (
        <Response key={index} response={response} />
      ))}
    </article>
  )
}

/**
 * A Clojure REPL backed by the daemon's nREPL connection. It shares the
 * WebSocket with the chat, so evaluations run in the same JVM as the agent.
 * The namespace defaults to the one attached to the selected session.
 */
export function ReplPanel({
  nreplPort,
  currentSession,
  entries,
  running,
  onEval,
  onClear,
  onClose,
}) {
  const [code, setCode] = useState('(+ 1 2)')
  const defaultNamespace = sessionNamespace(currentSession)
  const [namespace, setNamespace] = useState(defaultNamespace)
  const outputRef = useRef(null)

  useEffect(() => {
    setNamespace(defaultNamespace)
  }, [defaultNamespace])

  useEffect(() => {
    const node = outputRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [entries])

  function submit() {
    if (running || !code.trim()) return
    onEval(code, namespace)
  }

  function onKeyDown(event) {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <aside className="repl">
      <header className="repl__header">
        <div>
          <h2>Clojure REPL</h2>
          <p>{nreplPort ? `nREPL :${nreplPort}` : 'nREPL offline'}</p>
        </div>
        <div className="repl__actions">
          <button className="button button--quiet" onClick={onClear}>
            Clear
          </button>
          <button
            className="button button--quiet"
            onClick={onClose}
            aria-label="Close REPL"
          >
            ×
          </button>
        </div>
      </header>

      <div className="repl__output" ref={outputRef}>
        {entries.length === 0 ? (
          <p className="repl__empty">
            Evaluate a form in the daemon's JVM. The agent's namespaces are
            already loaded.
          </p>
        ) : (
          entries.map((entry) => <Entry key={entry.id} entry={entry} />)
        )}
        {running && <div className="repl__running">evaluating…</div>}
      </div>

      <form
        className="repl__form"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <div className="repl__ns">
          <label htmlFor="repl-ns">ns</label>
          <input
            id="repl-ns"
            value={namespace}
            onChange={(event) => setNamespace(event.target.value)}
            spellCheck="false"
          />
        </div>
        <textarea
          value={code}
          onChange={(event) => setCode(event.target.value)}
          onKeyDown={onKeyDown}
          spellCheck="false"
          rows={5}
          aria-label="Clojure form"
        />
        <div className="repl__footer">
          <span>Ctrl/Cmd+Enter to evaluate</span>
          <button
            className="button button--primary"
            type="submit"
            disabled={running || !code.trim()}
          >
            {running ? 'Evaluating…' : 'Evaluate'}
          </button>
        </div>
      </form>
    </aside>
  )
}
