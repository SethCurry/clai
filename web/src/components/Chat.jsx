import { useEffect, useRef, useState } from 'react'
import { UpdateView } from './UpdateView.jsx'

function shortId(id) {
  const value = String(id ?? '')
  return value.length > 12 ? value.slice(0, 8) : value
}

/**
 * The conversation timeline and composer. Updates are appended in stream order
 * by `useClaiSocket`; tool-call fragments are merged before they reach here.
 */
export function Chat({ session, updates, promptRunning, onPrompt }) {
  const [draft, setDraft] = useState('')
  const endRef = useRef(null)

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [updates])

  function submit() {
    if (promptRunning || !draft.trim()) return
    if (onPrompt(draft)) setDraft('')
  }

  function onKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <section className="chat">
      <header className="chat__header">
        <div>
          <h2>
            Session <span>#{shortId(session.sessionId)}</span>
          </h2>
          <p title={session.cwd}>{session.cwd || 'No working directory'}</p>
        </div>
        <div className="chat__badge">
          {promptRunning ? 'working' : 'idle'}
        </div>
      </header>

      <div className="timeline">
        {updates.length === 0 ? (
          <div className="timeline__empty">
            <p>This session is empty.</p>
            <p>
              Ask clai to inspect the repo, write a patch, or run a command.
              It can use its tools and the live Clojure runtime.
            </p>
          </div>
        ) : (
          updates.map((update, index) => (
            <UpdateView
              key={update.id ? `${update.type}-${update.id}` : `update-${index}`}
              update={update}
            />
          ))
        )}
        {promptRunning && (
          <div className="working" aria-live="polite">
            <span />
            <span />
            <span />
            clai is working
          </div>
        )}
        <div ref={endRef} />
      </div>

      <form
        className="composer"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <textarea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder="Ask clai to do something..."
          rows={3}
          disabled={promptRunning}
          aria-label="Prompt"
        />
        <div className="composer__footer">
          <span className="composer__hint">Enter to send · Shift+Enter for a newline</span>
          <button
            className="button button--primary"
            type="submit"
            disabled={promptRunning || !draft.trim()}
          >
            {promptRunning ? 'Working…' : 'Send'}
          </button>
        </div>
      </form>
    </section>
  )
}
