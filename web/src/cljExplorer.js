/**
 * Pure helpers for the web UI's Clojure namespace explorer.
 *
 * The daemon introspects its own JVM (see `clai.daemon.namespaces`) and the
 * explorer keeps one listing per opened namespace in a `{namespace -> vars}`
 * cache, so the panel only asks about the branches the user looks at.
 * [[visibleRows]] flattens that lazy tree into the rows the panel renders, and
 * [[sortNamespaces]] decides where the session's own namespace goes.
 */

/**
 * The prefix of the namespaces clai creates for sessions (`sessions.<id>`),
 * matching `clai.repl/session-ns`. They are kept above library namespaces
 * because they are where the user's own definitions land.
 */
export const SESSION_PREFIX = 'sessions.'

/**
 * The first non-blank line of `doc`, trimmed, or `""` when there is no doc.
 * Docstrings start with a summary line by convention, which is what fits in a
 * tree row.
 */
export function docSummary(doc) {
  const line = String(doc ?? '')
    .split('\n')
    .map((text) => text.trim())
    .find((text) => text !== '')
  return line ?? ''
}

/**
 * Order namespaces for the explorer: the session's own namespace first, then
 * every other session namespace, then everything else, each group in name
 * order.
 */
export function sortNamespaces(namespaces, sessionNamespace) {
  const rank = (name) =>
    name === sessionNamespace ? 0 : name.startsWith(SESSION_PREFIX) ? 1 : 2
  return [...namespaces].sort(
    (a, b) => rank(a.name) - rank(b.name) || a.name.localeCompare(b.name),
  )
}

/** True when `needle` is empty or appears in one of `texts`, ignoring case. */
function matches(needle, ...texts) {
  if (!needle) return true
  return texts.some((text) =>
    String(text ?? '')
      .toLowerCase()
      .includes(needle),
  )
}

/**
 * True when `listing` -- an open namespace's Vars -- holds a Var that matches
 * `needle`. It keeps a namespace in the tree when only its Vars match the
 * filter.
 */
function anyVarMatches(listing, needle) {
  if (!needle) return false
  return (listing?.entries ?? []).some((entry) =>
    matches(needle, entry.name, entry.doc),
  )
}

/**
 * A stand-in row for a branch that is loading, empty, or unreadable. `scope`
 * keeps the row's key unique among its siblings: two open namespaces can both
 * be loading at once.
 */
function noteRow(kind, depth, text, scope = '') {
  return { kind, key: `${kind}:${scope}:${depth}:${text}`, depth, text }
}

/**
 * The rows under an open namespace: its Vars, narrowed by `needle`, or a note
 * explaining why there are none.
 */
function varRows(namespace, listing, needle) {
  if (!listing || listing.status === 'loading') {
    return [noteRow('note', 1, 'loading…', namespace)]
  }
  if (listing.status === 'error') {
    return [noteRow('error', 1, listing.message, namespace)]
  }
  if (listing.missing) {
    return [noteRow('note', 1, 'this namespace is not loaded yet', namespace)]
  }
  const vars = (listing.entries ?? []).filter((entry) =>
    matches(needle, entry.name, entry.doc),
  )
  if (vars.length === 0) {
    return [
      noteRow('note', 1, needle ? 'no matching vars' : 'no vars', namespace),
    ]
  }
  return vars.map((entry) => ({
    kind: 'var',
    key: `var:${namespace}/${entry.name}`,
    namespace,
    name: entry.name,
    doc: entry.doc ?? null,
    arglists: entry.arglists ?? null,
    macro: Boolean(entry.macro),
    depth: 1,
  }))
}

/**
 * The rows the explorer renders, in tree order: `namespace` rows at depth 0
 * with the Vars of every open namespace (`var` rows at depth 1) beneath them,
 * plus `note` and `error` rows for branches that are loading, empty, missing,
 * or unreadable.
 *
 * `namespaces` is the root listing (`{status, entries, message}`), `vars` maps
 * a namespace name to its listing (`{status, entries, missing, message}`),
 * `expanded` is the set of open namespace names, and `filter` narrows both
 * levels by name or docstring. A namespace that matches by name or docstring
 * keeps all of its Vars; one that only holds a matching Var narrows to the
 * matching Vars. Only opened namespaces can be matched by their Vars, because
 * only their Vars have been fetched.
 */
export function visibleRows({
  namespaces,
  vars = {},
  expanded = new Set(),
  filter = '',
  sessionNamespace = null,
}) {
  if (namespaces.status === 'loading') {
    return [noteRow('note', 0, 'loading…')]
  }
  if (namespaces.status === 'error') {
    return [noteRow('error', 0, namespaces.message)]
  }
  const needle = String(filter ?? '')
    .trim()
    .toLowerCase()
  const entries = sortNamespaces(
    namespaces.entries ?? [],
    sessionNamespace,
  ).filter(
    (entry) =>
      matches(needle, entry.name, entry.doc) ||
      (expanded.has(entry.name) && anyVarMatches(vars[entry.name], needle)),
  )
  const rows = entries.flatMap((entry) => {
    const open = expanded.has(entry.name)
    const row = {
      kind: 'namespace',
      key: `namespace:${entry.name}`,
      // A namespace row is its own label and its own namespace.
      name: entry.name,
      namespace: entry.name,
      doc: entry.doc ?? null,
      varCount: entry.varCount ?? 0,
      depth: 0,
      open,
    }
    if (!open) return [row]
    // A namespace that matched by name or docstring keeps all of its Vars: the
    // filter already answered for that branch.
    const varNeedle = matches(needle, entry.name, entry.doc) ? '' : needle
    return [row, ...varRows(entry.name, vars[entry.name], varNeedle)]
  })
  if (rows.length === 0) {
    return [
      noteRow('note', 0, needle ? 'no matching namespaces' : 'no namespaces'),
    ]
  }
  return rows
}

/** The fully qualified symbol of a `var` row, as the REPL would expect it. */
export function varSymbol(row) {
  return `${row.namespace}/${row.name}`
}
