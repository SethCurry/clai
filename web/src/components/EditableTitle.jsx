import { useEffect, useRef, useState } from 'react'

/**
 * Inline editable text. Renders `value` (or `placeholder`) as a button that
 * turns into a text input when clicked. Enter or blur saves, Escape cancels,
 * and the trimmed value is sent to `onSave` only when it actually changed.
 */
export function EditableTitle({
  value,
  placeholder,
  ariaLabel,
  className = '',
  onSave,
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value ?? '')
  const inputRef = useRef(null)
  const editingRef = useRef(false)

  useEffect(() => {
    if (!editing) return
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [editing])

  function startEditing() {
    editingRef.current = true
    setDraft(value ?? '')
    setEditing(true)
  }

  function commit() {
    if (!editingRef.current) return
    editingRef.current = false
    setEditing(false)
    const next = draft.trim()
    if (next !== (value ?? '').trim()) onSave(next)
  }

  function cancel() {
    editingRef.current = false
    setEditing(false)
  }

  if (editing) {
    return (
      <input
        ref={inputRef}
        className={`editable-title editable-title--input ${className}`}
        value={draft}
        aria-label={ariaLabel}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            commit()
          } else if (event.key === 'Escape') {
            event.preventDefault()
            cancel()
          }
        }}
      />
    )
  }

  return (
    <button
      type="button"
      className={`editable-title${value ? '' : ' editable-title--placeholder'} ${className}`}
      title="Click to rename"
      aria-label={ariaLabel}
      onClick={startEditing}
    >
      {value || placeholder}
    </button>
  )
}
