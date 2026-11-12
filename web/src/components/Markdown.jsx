import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// GFM adds tables, task lists, strikethrough, and autolinks on top of CommonMark.
const REMARK_PLUGINS = [remarkGfm]

// External links should open in a new tab without leaking the opener reference.
const COMPONENTS = {
  a({ node, ...props }) {
    return <a {...props} target="_blank" rel="noopener noreferrer" />
  },
}

/**
 * Render CommonMark/GFM text as React elements. Raw HTML is intentionally not
 * enabled, so prompt and agent output cannot inject markup into the page.
 */
export function Markdown({ text }) {
  return (
    <div className="markdown">
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={COMPONENTS}>
        {text ?? ''}
      </ReactMarkdown>
    </div>
  )
}
