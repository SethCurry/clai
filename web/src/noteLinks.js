/**
 * The wiki-link syntax notes use to point at each other: another note's title
 * in double square brackets, as in `[[BM25 ranking]]`. The storage layer parses
 * the same syntax out of a note's Markdown (`clai.daemon.postgres.notes`), so
 * this module is the browser's half of that rule.
 */

/** Matches a `[[Title]]` reference; a title may not contain brackets or a line break. */
export const NOTE_LINK_PATTERN = /\[\[([^[\]\r\n]+)\]\]/g

/** The trimmed title of one regex match, or an empty string when it is blank. */
function matchTitle(match) {
  return String(match?.[1] ?? '').trim()
}

/**
 * The note titles `content` links to, in the order they first appear and
 * without duplicates (compared case-insensitively, like the daemon resolves
 * them). A title with no matching note is still returned: it may be written
 * later.
 */
export function parseNoteLinkTitles(content) {
  const seen = new Set()
  const titles = []
  for (const match of String(content ?? '').matchAll(NOTE_LINK_PATTERN)) {
    const title = matchTitle(match)
    const key = title.toLowerCase()
    if (!title || seen.has(key)) continue
    seen.add(key)
    titles.push(title)
  }
  return titles
}

/**
 * Split `text` into plain and wiki-link pieces, so a link can be rendered
 * without touching the rest of the text. A blank reference stays plain text.
 */
export function splitNoteLinks(text) {
  const value = String(text ?? '')
  const pieces = []
  let last = 0
  for (const match of value.matchAll(NOTE_LINK_PATTERN)) {
    const title = matchTitle(match)
    if (match.index > last) {
      pieces.push({ type: 'text', value: value.slice(last, match.index) })
    }
    pieces.push(
      title ? { type: 'note', title } : { type: 'text', value: match[0] },
    )
    last = match.index + match[0].length
  }
  if (last < value.length) {
    pieces.push({ type: 'text', value: value.slice(last) })
  }
  return pieces
}

/** Node types the remark walk does not turn into links, because their text is code or a link already. */
const SKIPPED_NODE_TYPES = new Set([
  'link',
  'linkReference',
  'code',
  'inlineCode',
  'html',
])

/** Replace `[[Title]]` references in `node`'s text children, recursing through the rest. */
function walkChildren(node, resolve) {
  if (!Array.isArray(node?.children)) return
  node.children = node.children.flatMap((child) => {
    if (child.type === 'text') return textNodes(child, resolve)
    if (!SKIPPED_NODE_TYPES.has(child.type)) walkChildren(child, resolve)
    return [child]
  })
}

/** Append `value` as text, merging it into a preceding text node. */
function pushText(nodes, value) {
  if (!value) return
  const last = nodes[nodes.length - 1]
  if (last?.type === 'text') last.value += value
  else nodes.push({ type: 'text', value })
}

/** Turn one Markdown text node into text and note-link nodes. */
function textNodes(node, resolve) {
  const nodes = []
  for (const piece of splitNoteLinks(node.value)) {
    if (piece.type === 'text') {
      pushText(nodes, piece.value)
      continue
    }
    const id = resolve?.(piece.title)
    if (id == null || id === '') {
      pushText(nodes, `[[${piece.title}]]`)
    } else {
      nodes.push({
        type: 'link',
        url: `#note/${id}`,
        children: [{ type: 'text', value: piece.title }],
      })
    }
  }
  return nodes
}

/**
 * A remark plugin that renders `[[Title]]` references as links.
 *
 * `resolve` maps a title to the id of the note it names, or to null when no
 * stored note has that title; an unresolved reference stays literal text. The
 * link points at a `#note/<id>` fragment, which the renderer's anchor
 * component turns into in-app navigation.
 */
export function remarkNoteLinks({ resolve } = {}) {
  return (tree) => {
    walkChildren(tree, resolve)
  }
}
