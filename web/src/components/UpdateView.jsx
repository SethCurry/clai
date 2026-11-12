import { Markdown } from './Markdown.jsx'

/**
 * Flatten an event's content, which may be a string, a content block, or a
 * list of blocks, into display text.
 */
function contentToText(content) {
  if (content == null) return ''
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content.map(contentToText).filter(Boolean).join('\n')
  }
  if (typeof content === 'object') {
    if (typeof content.content === 'string') return content.content
    if (Array.isArray(content.content)) return contentToText(content.content)
    if (typeof content.text === 'string') return content.text
    try {
      return JSON.stringify(content, null, 2)
    } catch {
      return String(content)
    }
  }
  return String(content)
}

function pretty(value) {
  if (value == null) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function ToolCall({ update }) {
  const status = update.status || 'running'
  const title = update.title || update.name || update.id || 'tool call'
  return (
    <article className={`tool-call tool-call--${status}`}>
      <header className="tool-call__header">
        <span className="tool-call__dot" aria-hidden="true" />
        <span className="tool-call__title">{title}</span>
        <span className="tool-call__status">{status}</span>
      </header>
      {update.input && (
        <details className="tool-call__section">
          <summary>Input</summary>
          <pre>{pretty(update.input)}</pre>
        </details>
      )}
      {update.output && (
        <pre className="tool-call__output">{update.output}</pre>
      )}
    </article>
  )
}

/**
 * Render one streamed agent event: a chat bubble, a thought, or a tool call.
 */
export function UpdateView({ update }) {
  if (!update) return null

  if (update.type === 'tool_call') {
    return <ToolCall update={update} />
  }

  const text = contentToText(update.content)

  if (update.type === 'user_prompt') {
    return (
      <div className="message message--user">
        <div className="message__label">You</div>
        <div className="message__body">
          <Markdown text={text} />
        </div>
      </div>
    )
  }

  if (update.type === 'agent_message') {
    return (
      <div className="message message--agent">
        <div className="message__label">clai</div>
        <div className="message__body">
          <Markdown text={text} />
        </div>
      </div>
    )
  }

  if (update.type === 'agent_thought') {
    return (
      <details className="thought">
        <summary>Thinking</summary>
        <div>{text}</div>
      </details>
    )
  }

  return (
    <div className="message message--unknown">
      <div className="message__label">{update.type || 'update'}</div>
      <pre>{pretty(update)}</pre>
    </div>
  )
}
