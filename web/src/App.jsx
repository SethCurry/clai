import { Navigate, Route, Routes } from 'react-router-dom'
import { ClaiProvider } from './ClaiContext.jsx'
import { AppLayout } from './components/AppLayout.jsx'
import { GitReposPage } from './components/GitReposPage.jsx'
import { LoginPage } from './components/LoginPage.jsx'
import { NotFoundPage } from './components/NotFoundPage.jsx'
import { NotesPage } from './components/NotesPage.jsx'
import { PluginsPage } from './components/PluginsPage.jsx'
import { SessionsPage } from './components/SessionsPage.jsx'
import { useAuth } from './useAuth.js'

/**
 * The sign-in form, wired to the session state `useAuth` owns. Signing in
 * flips that state, which is what makes `/login` redirect to the console.
 */
function LoginRoute({ auth }) {
  return (
    <div className="app">
      <LoginPage
        status={auth.status}
        error={auth.error}
        busy={auth.busy}
        onSignIn={auth.signIn}
        onDismissError={auth.dismissError}
      />
    </div>
  )
}

/**
 * The guard in front of every signed-in page. While `useAuth` asks the daemon
 * whether the cookie is still good it renders the login page's "checking"
 * state; without a session it sends the browser to `/login`; with one it
 * mounts the console shell and, through `ClaiProvider`, the single WebSocket
 * every page shares.
 */
function RequireAuth({ auth }) {
  if (auth.status === 'checking') return <LoginRoute auth={auth} />
  if (auth.status !== 'authenticated') return <Navigate to="/login" replace />
  return (
    <ClaiProvider>
      <AppLayout user={auth.user} onSignOut={auth.signOut} />
    </ClaiProvider>
  )
}

/**
 * The web UI's routes. `/login` is the only page reachable without a session;
 * everything else renders inside the console shell, with the sessions page as
 * the index route.
 */
export default function App() {
  const auth = useAuth()

  return (
    <Routes>
      <Route
        path="/login"
        element={
          auth.status === 'authenticated' ? (
            <Navigate to="/" replace />
          ) : (
            <LoginRoute auth={auth} />
          )
        }
      />
      <Route element={<RequireAuth auth={auth} />}>
        <Route index element={<SessionsPage />} />
        <Route path="git-repos" element={<GitReposPage />} />
        <Route path="notes" element={<NotesPage />} />
        <Route path="plugins" element={<PluginsPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
