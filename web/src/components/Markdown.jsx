import { useMemo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { remarkNoteLinks } from '../noteLinks.js'

// GFM adds tables, task lists, strikethrough, and autolinks on top of CommonMark.
const REMARK_PLUGINS = [remarkGfm]

// Prefix of the URL a `[[Title]]` reference is rendered with inside the app.
const NOTE_URL_PREFIX = '#note/'

/**
 * The anchor renderer: a `#note/<id>` link is an in-app note reference, while
 * every other link opens in a new tab without leaking the opener reference.
 */
function anchorComponent(onOpenNote) {
  return function Anchor({ node, href, children, ...props }) {
    if (href?.startsWith(NOTE_URL_PREFIX)) {
      const id = href.slice(NOTE_URL_PREFIX.length)
      return (
        <a
          href={href}
          className="note-link"
          onClick={(event) => {
            event.preventDefault()
            onOpenNote?.(id)
          }}
        >
          {children}
        </a>
      )
    }
    return (
      <a {...props} href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    )
  }
}

/**
 * Render CommonMark/GFM text as React elements. Raw HTML is intentionally not
 * enabled, so prompt and agent output cannot inject markup into the page.
 *
 * When `resolveNote` is given, `[[Title]]` references in the text become links:
 * `resolveNote` maps a title to a note id, and clicking a resolved reference
 * calls `onOpenNote` with that id. An unresolved reference stays literal text.
 */
export function Markdown({ text, resolveNote, onOpenNote }) {
  const remarkPlugins = useMemo(
    () =>
      resolveNote
        ? [remarkGfm, [remarkNoteLinks, { resolve: resolveNote }]]
        : REMARK_PLUGINS,
    [resolveNote],
  )

  const components = useMemo(
    () => ({ a: anchorComponent(onOpenNote) }),
    [onOpenNote],
  )

  return (
    <div className="markdown">
      <ReactMarkdown remarkPlugins={remarkPlugins} components={components}>
        {text ?? ''}
      </ReactMarkdown>
    </div>
  )
}
