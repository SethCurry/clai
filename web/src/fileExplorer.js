/**
 * Pure helpers for the web UI's file explorer widget.
 *
 * The daemon lists directories and reads files (see `clai.daemon.files`); the
 * explorer keeps those listings in a `{path -> listing}` cache and only reads
 * the branches the user opens. [[visibleRows]] flattens that lazy tree into
 * the rows the panel renders, so the component never recurses.
 */

/**
 * The number of entries requested per directory. Directories with more
 * entries than this are truncated and say so, which keeps one huge folder
 * from flooding the panel.
 */
export const LIST_LIMIT = 500

/**
 * Flatten one directory's children into rows for `expanded`, appending the
 * rows of every open subdirectory after its own row. `depth` is the
 * indentation level of the returned rows.
 */
function rowsFor(listings, expanded, path, depth) {
  const listing = listings[path]
  if (!listing || listing.status === 'loading') {
    return [{ kind: 'note', path, depth, text: 'loading…' }]
  }
  if (listing.status === 'error') {
    return [{ kind: 'error', path, depth, text: listing.message }]
  }
  const entries = listing.entries ?? []
  if (entries.length === 0) {
    return [{ kind: 'note', path, depth, text: 'empty' }]
  }
  const rows = entries.flatMap((entry) => {
    const directory = entry.type === 'directory'
    const row = {
      kind: directory ? 'directory' : 'file',
      path: entry.path,
      name: entry.name,
      depth,
      open: false,
    }
    if (!directory || !expanded.has(entry.path)) return [row]
    return [
      { ...row, open: true },
      ...rowsFor(listings, expanded, entry.path, depth + 1),
    ]
  })
  // A full page of entries is probably a truncated one.
  return entries.length >= LIST_LIMIT
    ? [
        ...rows,
        {
          kind: 'note',
          path,
          depth,
          text: `only the first ${LIST_LIMIT} entries are shown`,
        },
      ]
    : rows
}

/**
 * The rows the explorer renders for the session's working directory, in tree
 * order: `{kind, path, depth}` for directories and files, plus `note` and
 * `error` rows that stand in for a branch that is loading, empty, or
 * unreadable.
 */
export function visibleRows(listings, expanded) {
  return rowsFor(listings, expanded, '', 0)
}

/** The name of the file `path` points at, ignoring any trailing slash. */
export function fileName(path) {
  const trimmed = String(path ?? '').replace(/\/+$/, '')
  const slash = trimmed.lastIndexOf('/')
  return slash === -1 ? trimmed : trimmed.slice(slash + 1)
}
