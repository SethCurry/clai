import { useMemo, useState } from 'react'
import { formatRelativeTime, formatTimestamp } from '../format.js'
import {
  EMPTY_NOTE_FORM,
  addLink,
  formToNoteInput,
  linkCandidates,
  linkLabel,
  notePreview,
  noteTitle,
  noteToForm,
  removeLink,
  titleLookup,
} from '../notes.js'
import { useNotes } from '../useNotes.js'
import { Markdown } from './Markdown.jsx'

/** One row of the note list: title, a one-line excerpt, and its age. */
function NoteCard({ note, active, onSelect }) {
  return (
    <button
      className={`note-card${active ? ' note-card--active' : ''}`}
      onClick={() => onSelect(note.noteId)}
    >
      <span className="note-card__title">{noteTitle(note)}</span>
      <span className="note-card__preview">{notePreview(note.content)}</span>
      <span className="note-card__meta">
        {formatRelativeTime(note.updatedAt)}
      </span>
    </button>
  )
}

/** What the list says when it has no notes to show. */
function emptyListMessage(searching, hasQuery) {
  if (searching) return 'Searching…'
  if (hasQuery) return 'No notes match that search.'
  return 'No notes yet. Write one to get started.'
}

/**
 * The left column: the note list, the search field above it, and the button
 * that starts a new note. While a search runs it lists matches instead, and
 * says how many it found.
 */
function NoteList({
  notes,
  query,
  searching,
  selectedId,
  onQuery,
  onSelect,
  onNew,
}) {
  const hasQuery = query.trim() !== ''
  return (
    <aside className="list-page__list" aria-label="Notes">
      <div className="list-page__list-head">
        <h3>Notes</h3>
        <span className="pill">{notes.length}</span>
      </div>

      <button className="button button--primary" onClick={onNew}>
        + New note
      </button>

      <div className="notes-search">
        <input
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          placeholder="Search notes…"
          aria-label="Search notes"
          spellCheck={false}
        />
        {hasQuery && (
          <button
            className="notes-search__clear"
            aria-label="Clear note search"
            onClick={() => onQuery('')}
          >
            ×
          </button>
        )}
      </div>

      {!searching && hasQuery && (
        <p className="notes-list__meta">
          {notes.length === 1 ? '1 match' : `${notes.length} matches`} for
          “{query.trim()}”, most relevant first.
        </p>
      )}

      {notes.length > 0 ? (
        <div className="note-list">
          {notes.map((note) => (
            <NoteCard
              key={note.noteId}
              note={note}
              active={note.noteId === selectedId}
              onSelect={onSelect}
            />
          ))}
        </div>
      ) : (
        <p className="list-page__empty">
          {emptyListMessage(searching, hasQuery)}
        </p>
      )}
    </aside>
  )
}

/**
 * One row of linked notes, rendered as chips that jump to the note. A link to
 * a note the list has not loaded still shows, labelled by its id.
 */
function LinkChips({ heading, hint, ids, titles, onSelect }) {
  if (ids.length === 0) return null
  return (
    <section className="note-links">
      <h4>
        {heading} <span className="pill">{ids.length}</span>
      </h4>
      {hint && <p className="note-links__hint">{hint}</p>}
      <div className="note-links__chips">
        {ids.map((id) => (
          <button
            key={id}
            className="note-link-chip"
            onClick={() => onSelect(id)}
          >
            {linkLabel(titles, id)}
          </button>
        ))}
      </div>
    </section>
  )
}

/** The read view: one note's metadata, rendered Markdown, links, and backlinks. */
function NoteDetail({ note, titles, onEdit, onDelete, onSelect }) {
  return (
    <div className="list-panel note-detail">
      <header className="list-panel__head note-detail__head">
        <div className="note-detail__identity">
          <p className="list-page__eyebrow">note #{note.noteId}</p>
          <h3>{noteTitle(note)}</h3>
        </div>
        <div className="note-detail__actions">
          <button className="button button--quiet" onClick={() => onEdit(note)}>
            Edit
          </button>
          <button
            className="button button--danger"
            onClick={() => onDelete(note)}
          >
            Delete
          </button>
        </div>
      </header>

      <dl className="note-detail__meta">
        <div>
          <dt>Created</dt>
          <dd>{formatTimestamp(note.createdAt)}</dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>{formatTimestamp(note.updatedAt)}</dd>
        </div>
      </dl>

      <article className="note-detail__content">
        <Markdown text={note.content} />
      </article>

      <LinkChips
        heading="Links"
        hint="Notes this one points at."
        ids={note.links ?? []}
        titles={titles}
        onSelect={onSelect}
      />
      <LinkChips
        heading="Backlinks"
        hint="Notes that point at this one."
        ids={note.backlinks ?? []}
        titles={titles}
        onSelect={onSelect}
      />
    </div>
  )
}

/**
 * The write view for a new or existing note: title, Markdown content, and the
 * outgoing links. `allNotes` fills the link picker, which offers only notes
 * that are not already linked and are not the note being edited. The caller
 * owns the API call, so the form only validates its own fields.
 */
function NoteEditor({ note, allNotes, onSave, onCancel }) {
  const [form, setForm] = useState(() => noteToForm(note ?? EMPTY_NOTE_FORM))
  const [pick, setPick] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const titles = useMemo(() => titleLookup(allNotes), [allNotes])
  const candidates = useMemo(
    () => linkCandidates(allNotes, form.links, note?.noteId),
    [allNotes, form.links, note?.noteId],
  )

  const update = (field, value) => {
    setForm((previous) => ({ ...previous, [field]: value }))
  }

  const addPickedLink = () => {
    if (!pick) return
    update('links', addLink(form.links, pick))
    setPick('')
  }

  const submit = async (event) => {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      await onSave(formToNoteInput(form))
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  // Ctrl/Cmd+Enter saves without reaching for the button; Enter alone stays a
  // newline, since note content is multi-line Markdown.
  const onKeyDown = (event) => {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      submit(event)
    }
  }

  return (
    <div className="list-panel">
      <header className="list-panel__head">
        <p className="list-page__eyebrow">
          {note ? `edit note #${note.noteId}` : 'new note'}
        </p>
        <h3>{note ? noteTitle(note) : 'Write a note'}</h3>
        <p>
          Notes are Markdown and stored in PostgreSQL. Linking a note records a
          direction, so the other note shows it as a backlink.
        </p>
      </header>

      <form className="config-form" onSubmit={submit} onKeyDown={onKeyDown}>
        <label className="config-form__field">
          <span>Title</span>
          <input
            value={form.title}
            onChange={(event) => update('title', event.target.value)}
            placeholder="BM25 ranking"
            autoFocus
            required
          />
        </label>

        <label className="config-form__field">
          <span>Content</span>
          <textarea
            className="notes-editor__content"
            value={form.content}
            onChange={(event) => update('content', event.target.value)}
            placeholder="What this note should remember. Markdown works."
            rows={14}
            required
          />
        </label>

        <div className="config-form__field">
          <span>Links</span>
          {form.links.length === 0 ? (
            <p className="notes-editor__hint">No outgoing links.</p>
          ) : (
            <div className="note-links__chips">
              {form.links.map((id) => (
                <span key={id} className="note-link-chip note-link-chip--editable">
                  {linkLabel(titles, id)}
                  <button
                    type="button"
                    className="note-link-chip__remove"
                    aria-label={`Remove link to ${linkLabel(titles, id)}`}
                    onClick={() => update('links', removeLink(form.links, id))}
                  >
                    ×
                  </button>
                </span>
              ))}
            </div>
          )}
          <div className="notes-editor__add-link">
            <select
              value={pick}
              onChange={(event) => setPick(event.target.value)}
              disabled={candidates.length === 0}
              aria-label="Note to link to"
            >
              <option value="">
                {candidates.length === 0
                  ? 'No other notes to link to'
                  : 'Choose a note to link to…'}
              </option>
              {candidates.map((candidate) => (
                <option key={candidate.noteId} value={candidate.noteId}>
                  {noteTitle(candidate)}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="button button--quiet"
              onClick={addPickedLink}
              disabled={!pick}
            >
              Add link
            </button>
          </div>
        </div>

        {error && <p className="config-form__error">{error}</p>}

        <div className="config-form__actions">
          <span className="config-form__spacer" />
          <button
            type="button"
            className="button button--quiet"
            disabled={busy}
            onClick={onCancel}
          >
            Cancel
          </button>
          <button
            type="submit"
            className="button button--primary"
            disabled={busy}
          >
            {busy ? 'Saving…' : note ? 'Save note' : 'Create note'}
          </button>
        </div>
      </form>
    </div>
  )
}

/**
 * The notes page: list and search the stored notes, read one with its links
 * and backlinks, and create, edit, or delete notes. Served by `/api/notes` and
 * `/api/notes/search`.
 */
export function NotesPage({ onClose }) {
  const notes = useNotes()
  const [editor, setEditor] = useState(null)
  const titles = useMemo(() => titleLookup(notes.stored), [notes.stored])

  const startNew = () => {
    notes.setQuery('')
    setEditor({ note: null })
  }

  const openNote = async (id) => {
    setEditor(null)
    await notes.select(id)
  }

  const saveNote = async (input) => {
    const saved = await notes.saveNote(input, editor?.note?.noteId ?? null)
    if (!editor?.note) notes.setQuery('')
    setEditor(null)
    return saved
  }

  const deleteNote = async (note) => {
    if (!window.confirm(`Delete note "${noteTitle(note)}"?`)) return
    try {
      await notes.deleteNote(note.noteId)
      setEditor(null)
    } catch {
      // The hook already stored the error for the page banner.
    }
  }

  return (
    <main className="list-page">
      <header className="list-page__header">
        <div className="list-page__heading">
          <p className="list-page__eyebrow">knowledge base</p>
          <h2>Notes</h2>
          <p>
            Notes are Markdown documents stored in PostgreSQL. Search them by
            content, link them to each other, and read either direction of a
            link here.
          </p>
        </div>
        <div className="list-page__actions">
          <button
            className="button button--quiet"
            onClick={notes.refresh}
            disabled={notes.loading}
          >
            {notes.loading ? 'Loading…' : 'Refresh'}
          </button>
          <button className="button button--quiet" onClick={onClose}>
            Back to sessions
          </button>
        </div>
      </header>

      {notes.error && (
        <div className="list-page__error" role="alert">
          <span>{notes.error}</span>
          <button
            className="list-page__error-close"
            aria-label="Dismiss note error"
            onClick={notes.dismissError}
          >
            ×
          </button>
        </div>
      )}

      <div className="list-page__body">
        <NoteList
          notes={notes.list}
          query={notes.query}
          searching={notes.searching}
          selectedId={editor ? null : notes.selectedId}
          onQuery={notes.setQuery}
          onSelect={openNote}
          onNew={startNew}
        />

        <section className="list-page__detail">
          {editor ? (
            <NoteEditor
              key={editor.note?.noteId ?? 'new'}
              note={editor.note}
              allNotes={notes.stored}
              onSave={saveNote}
              onCancel={() => setEditor(null)}
            />
          ) : notes.detail ? (
            <NoteDetail
              note={notes.detail}
              titles={titles}
              onEdit={(note) => setEditor({ note })}
              onDelete={deleteNote}
              onSelect={openNote}
            />
          ) : (
            <div className="list-page__placeholder">
              <p>Select a note to read it, or write a new one.</p>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}
