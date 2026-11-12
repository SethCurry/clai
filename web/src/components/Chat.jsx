import { useEffect, useRef, useState } from 'react'
import { usePathAutocomplete } from '../usePathAutocomplete.js'
import { EditableTitle } from './EditableTitle.jsx'
import { PathAutocomplete } from './PathAutocomplete.jsx'
import { UpdateView } from './UpdateView.jsx'

function shortId(id) {
  const value = String(id ?? '')
  return value.length > 12 ? value.slice(0, 8) : value
}

/**
 * The conversation timeline and composer. Updates are appended in stream order
 * by `useClaiSocket`; tool-call fragments are merged before they reach here.
 * Typing `@` in the composer offers files and folders from the session's
 * working directory.
 */
export function Chat({ session, updates, promptRunning, onPrompt, onRename }) {
  const [draft, setDraft] = useState('')
  const endRef = useRef(null)
  const textareaRef = useRef(null)
  const autocomplete = usePathAutocomplete({
    sessionId: session.sessionId,
    value: draft,
    onChange: setDraft,
    textareaRef,
  })

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [updates])

  function submit() {
    if (promptRunning || !draft.trim()) return
    if (onPrompt(draft)) {
      setDraft('')
      autocomplete.close()
    }
  }

  function onKeyDown(event) {
    if (autocomplete.onKeyDown(event)) return
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <section className="chat">
      <header className="chat__header">
        <div className="chat__heading">
          <div className="chat__title-row">
            <EditableTitle
              className="chat__title"
              value={session.title}
              placeholder="Untitled session"
              ariaLabel="Session title"
              onSave={(title) => onRename(session.sessionId, title)}
            />
            <span className="chat__id">#{shortId(session.sessionId)}</span>
          </div>
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
        {autocomplete.completion && (
          <PathAutocomplete
            completion={autocomplete.completion}
            onPick={autocomplete.pick}
            onHover={autocomplete.setIndex}
          />
        )}
        <textarea
          ref={textareaRef}
          value={draft}
          onChange={autocomplete.onChange}
          onKeyDown={onKeyDown}
          onBlur={autocomplete.onBlur}
          placeholder="Ask clai to do something..."
          rows={3}
          disabled={promptRunning}
          aria-label="Prompt"
        />
        <div className="composer__footer">
          <span className="composer__hint">
            Enter to send · Shift+Enter for a newline · @ to mention a file or
            folder
          </span>
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
