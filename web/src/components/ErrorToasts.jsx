/**
 * A small stack of dismissible daemon errors. The list is capped by the hook
 * so a flapping connection cannot cover the whole UI.
 */
export function ErrorToasts({ errors, onDismiss }) {
  if (errors.length === 0) return null
  return (
    <div className="toasts" role="status" aria-live="polite">
      {errors.map((error) => (
        <div className="toast" key={error.id}>
          <span>{error.message}</span>
          <button
            className="toast__close"
            onClick={() => onDismiss(error.id)}
            aria-label="Dismiss error"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  )
}
