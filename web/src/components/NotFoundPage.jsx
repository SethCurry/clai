import { Link, useLocation } from 'react-router-dom'

/**
 * What a signed-in browser sees at a path the app has no page for. It stays
 * inside the console shell, so the tabs and the sign-out button keep working
 * and the way back to the sessions is one click away.
 */
export function NotFoundPage() {
  const { pathname } = useLocation()

  return (
    <main className="empty empty--page">
      <div className="empty__card">
        <p className="empty__eyebrow">clai / web</p>
        <h2>Page not found</h2>
        <p>
          This console has no page at <code>{pathname}</code>.
        </p>
        <Link className="button button--primary" to="/">
          Back to sessions
        </Link>
      </div>
    </main>
  )
}
