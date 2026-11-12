import { useEffect, useRef } from 'react'

/**
 * The directory part of a completion entry's relative path, shown dimmed
 * beside the name. `src/Alpha.clj` displays as `src/` and a top-level entry
 * displays nothing.
 */
function directoryOf(path) {
  const slash = path.lastIndexOf('/')
  return slash === -1 ? '' : path.slice(0, slash + 1)
}

/**
 * The `@`-mention popup above the prompt composer. Entries and the active row
 * come from `usePathAutocomplete`; picking a row keeps focus in the textarea
 * because rows act on `mousedown` instead of `click`.
 */
export function PathAutocomplete({ completion, onPick, onHover }) {
  const listRef = useRef(null)
  const { entries, index } = completion

  useEffect(() => {
    listRef.current
      ?.querySelector('[aria-selected="true"]')
      ?.scrollIntoView({ block: 'nearest' })
  }, [entries, index])

  return (
    <div
      className="autocomplete"
      role="listbox"
      aria-label="Files and folders"
      ref={listRef}
    >
      {entries.length === 0 ? (
        <p className="autocomplete__empty">No matching files or folders</p>
      ) : (
        entries.map((entry, position) => {
          const directory = entry.type === 'directory'
          return (
            <button
              key={entry.path}
              type="button"
              role="option"
              aria-selected={position === index}
              className={`autocomplete__item${
                position === index ? ' autocomplete__item--active' : ''
              }`}
              onMouseDown={(event) => {
                event.preventDefault()
                onPick(entry)
              }}
              onMouseMove={() => onHover(position)}
            >
              <span className="autocomplete__icon" aria-hidden="true">
                {directory ? '▸' : '·'}
              </span>
              <span className="autocomplete__name">
                {entry.name}
                {directory ? '/' : ''}
              </span>
              <span className="autocomplete__dir">{directoryOf(entry.path)}</span>
            </button>
          )
        })
      )}
    </div>
  )
}
