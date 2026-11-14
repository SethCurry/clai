/**
 * Shape, validate, and sort notes for the web UI's notes page. Keeping this
 * separate from the components makes the trimming and link rules testable
 * without a DOM, and keeps them in step with
 * `clai.daemon.postgres.notes/prepare-note`.
 */

/** The values a blank note editor starts from. */
export const EMPTY_NOTE_FORM = { title: '', content: '', links: [] }

/** Longest preview a note card shows before it is cut with an ellipsis. */
export const PREVIEW_LENGTH = 140

/**
 * The database id of `note` as a string, or null when it has none. Ids arrive
 * as strings but a hand-written fixture may use a number.
 */
export function noteId(note) {
  const id = note?.noteId
  return id == null || id === '' ? null : String(id)
}

/**
 * The title a note is listed under, so a note without one is still addressable.
 * The daemon rejects blank titles, so this only matters for optimistic rows.
 */
export function noteTitle(note) {
  return String(note?.title ?? '').trim() || 'Untitled note'
}

/**
 * One line of note content for a list card: whitespace collapsed and cut at
 * `maxLength` with an ellipsis.
 */
export function notePreview(content, maxLength = PREVIEW_LENGTH) {
  const text = String(content ?? '')
    .replace(/\s+/g, ' ')
    .trim()
  if (text.length <= maxLength) return text
  return `${text.slice(0, Math.max(1, maxLength - 1)).trimEnd()}…`
}

/**
 * Trim and de-duplicate a link list, keeping the order the ids were given in.
 * Ids are compared as strings because the API exposes note ids as strings
 * whether or not the JSON body sent them as numbers.
 */
export function normalizeLinks(links) {
  const seen = new Set()
  const result = []
  for (const link of Array.isArray(links) ? links : []) {
    const id = String(link ?? '').trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    result.push(id)
  }
  return result
}

/**
 * Turn a loaded note into editable form values. Only the fields the form shows
 * are kept; the daemon fills in timestamps and backlinks.
 */
export function noteToForm(note) {
  return {
    title: note?.title ?? '',
    content: note?.content ?? '',
    links: normalizeLinks(note?.links),
  }
}

/**
 * Trim and validate a note form. The title and content are both mandatory, and
 * the daemon re-checks the same rules, but rejecting blanks here gives
 * immediate feedback. `links` always comes back as an array, because a note
 * saved without links must replace its outgoing links with nothing.
 */
export function formToNoteInput(form) {
  const title = String(form.title ?? '').trim()
  const content = String(form.content ?? '').trim()
  if (!title) throw new Error('A note title is required.')
  if (!content) throw new Error('Note content is required.')
  return { title, content, links: normalizeLinks(form.links) }
}

/**
 * Add `id` to `links` unless it is already there.
 */
export function addLink(links, id) {
  const link = String(id ?? '').trim()
  const current = normalizeLinks(links)
  if (!link || current.includes(link)) return current
  return [...current, link]
}

/**
 * Drop `id` from `links`, leaving the rest in order.
 */
export function removeLink(links, id) {
  const link = String(id ?? '').trim()
  return normalizeLinks(links).filter((current) => current !== link)
}

/** A note's update time in milliseconds, with a missing one sorting oldest. */
function updatedAtValue(note) {
  const parsed = Date.parse(note?.updatedAt ?? '')
  return Number.isNaN(parsed) ? 0 : parsed
}

/** A note's database id as a number, for tie-breaking two equally old notes. */
function idValue(note) {
  const value = Number(noteId(note))
  return Number.isFinite(value) ? value : 0
}

/**
 * Sort notes the way the list shows them: most recently updated first, then
 * highest id. The daemon returns that order too, so this mainly re-places a
 * note after it is saved.
 */
export function sortNotes(notes) {
  return [...notes].sort(
    (a, b) => updatedAtValue(b) - updatedAtValue(a) || idValue(b) - idValue(a),
  )
}

/**
 * A `noteId` -> title map, for rendering links and backlinks as titles instead
 * of ids. The list endpoint returns ids without link lists, so this is the
 * only place those titles come from.
 */
export function titleLookup(notes) {
  const titles = new Map()
  for (const note of notes) {
    const id = noteId(note)
    if (id) titles.set(id, noteTitle(note))
  }
  return titles
}

/**
 * The label for a linked note: its title when the note is in `titles`, and a
 * readable `Note #id` fallback when the list has not loaded it.
 */
export function linkLabel(titles, id) {
  const key = String(id)
  return titles?.get(key) ?? `Note #${key}`
}

/**
 * The notes a note's link editor may add: everything except the note being
 * edited, its existing links, and notes without an id, sorted by title.
 */
export function linkCandidates(notes, links, currentNoteId) {
  const linked = new Set(normalizeLinks(links))
  const self = currentNoteId == null ? null : String(currentNoteId)
  return notes
    .filter((note) => {
      const id = noteId(note)
      return id !== null && id !== self && !linked.has(id)
    })
    .sort((a, b) => noteTitle(a).localeCompare(noteTitle(b)))
}
