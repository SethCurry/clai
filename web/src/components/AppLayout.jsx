import { NavLink, Outlet } from 'react-router-dom'
import { useClai } from '../ClaiContext.jsx'
import { ErrorToasts } from './ErrorToasts.jsx'

/** How the daemon connection states read in the topbar. */
const STATUS_LABELS = {
  connected: 'connected',
  connecting: 'connecting',
  disconnected: 'reconnecting',
  error: 'connection error',
}

/**
 * The console's pages, in tab order. `end` keeps the sessions route, which is
 * `/`, from matching every other path as well.
 */
const TABS = [
  { to: '/', label: 'Sessions', end: true },
  { to: '/agent-configs', label: 'Agent configs' },
  { to: '/git-repos', label: 'Git repos' },
  { to: '/notes', label: 'Notes' },
  { to: '/stats', label: 'Stats' },
  { to: '/plugins', label: 'Plugins' },
]

/**
 * One topbar tab. `NavLink` keeps `aria-current` and the active styling in
 * step with the URL, so no page has to know where it sits in the app.
 */
function Tab({ to, label, end }) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        `view-tab${isActive ? ' view-tab--active' : ''}`
      }
    >
      {label}
    </NavLink>
  )
}

/**
 * The signed-in shell around every page: the topbar with the page tabs, the
 * connection and model pills, the signed-in user, and sign-out; then whichever
 * page the URL names; then the daemon's error toasts.
 *
 * The daemon connection comes from the `ClaiProvider` that `RequireAuth`
 * mounts, so pages read it with `useClai` rather than through props.
 */
export function AppLayout({ user, onSignOut }) {
  const clai = useClai()
  const statusLabel = STATUS_LABELS[clai.status] || clai.status

  return (
    <div className="app">
      <header className="topbar">
        <div className="topbar__brand">
          <span className="brand-mark" aria-hidden="true">
            λ
          </span>
          <span className="brand-name">clai</span>
          <span className="brand-tag">agent console</span>
        </div>

        <nav className="view-tabs" aria-label="Main views">
          {TABS.map((tab) => (
            <Tab key={tab.to} {...tab} />
          ))}
        </nav>

        <div className="topbar__meta">
          <span className={`connection connection--${clai.status}`}>
            <span className="connection__dot" />
            {statusLabel}
          </span>
          {clai.sessionInfo.model && (
            <span className="pill">
              {clai.sessionInfo.provider
                ? `${clai.sessionInfo.provider}:${clai.sessionInfo.model}`
                : clai.sessionInfo.model}
            </span>
          )}
          {clai.sessionInfo.nreplPort && (
            <span className="pill">nREPL :{clai.sessionInfo.nreplPort}</span>
          )}
          {user?.username && (
            <span className="pill" title="Signed-in user">
              {user.username}
            </span>
          )}
          <button className="button button--quiet" onClick={onSignOut}>
            Sign out
          </button>
        </div>
      </header>

      <Outlet />

      <ErrorToasts errors={clai.errors} onDismiss={clai.dismissError} />
    </div>
  )
}
