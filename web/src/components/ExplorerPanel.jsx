import { ClojureExplorer } from './ClojureExplorer.jsx'
import { FileExplorer } from './FileExplorer.jsx'

/** The panels the explorer can show, in tab order. */
const TABS = [
  { id: 'files', label: 'Files' },
  { id: 'namespaces', label: 'Namespaces' },
]

/**
 * The right-hand explorer panel: one tab for the session's working directory
 * and one for the Clojure namespaces the REPL can use. Both panels are lazy
 * trees, and only the selected one is mounted, so switching tabs costs one
 * request rather than two.
 *
 * Each explorer is keyed by the session id, so selecting another session
 * starts both trees from scratch.
 */
export function ExplorerPanel({ tab, onTab, currentSession, onClose }) {
  const sessionKey = currentSession?.sessionId ?? 'no-session'
  return (
    <aside className="explorer">
      <header className="explorer__header">
        <div className="explorer__tabs" role="tablist" aria-label="Explorer">
          {TABS.map(({ id, label }) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={id === tab}
              className={`explorer__tab${
                id === tab ? ' explorer__tab--active' : ''
              }`}
              onClick={() => onTab(id)}
            >
              {label}
            </button>
          ))}
        </div>
        <button
          className="explorer__close"
          onClick={onClose}
          aria-label="Close explorer"
        >
          ×
        </button>
      </header>

      {tab === 'namespaces' ? (
        <ClojureExplorer key={sessionKey} currentSession={currentSession} />
      ) : (
        <FileExplorer key={sessionKey} currentSession={currentSession} />
      )}
    </aside>
  )
}
