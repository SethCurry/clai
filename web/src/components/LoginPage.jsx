import { useState } from 'react'
import { credentialError, EMPTY_CREDENTIALS } from '../auth.js'

/**
 * The sign-in card. It holds the form's own state and reports submission
 * through `onSubmit`, which resolves true when the daemon accepted the
 * credentials; the page keeps rendering the form while it does not, so the
 * error stays in place for a retry.
 */
function LoginForm({ error, busy, onSubmit, onDismissError }) {
  const [form, setForm] = useState(EMPTY_CREDENTIALS)
  const [localError, setLocalError] = useState('')

  const update = (field, value) => {
    setForm((previous) => ({ ...previous, [field]: value }))
    if (localError) setLocalError('')
    if (error) onDismissError()
  }

  const submit = async (event) => {
    event.preventDefault()
    const problem = credentialError(form)
    if (problem) {
      setLocalError(problem)
      return
    }
    setLocalError('')
    const signedIn = await onSubmit(form)
    if (!signedIn) setForm((previous) => ({ ...previous, password: '' }))
  }

  const message = localError || error

  return (
    <form className="login__form" onSubmit={submit}>
      <label className="login__field">
        <span>Username</span>
        <input
          name="username"
          value={form.username}
          onChange={(event) => update('username', event.target.value)}
          autoComplete="username"
          autoFocus
          spellCheck={false}
          required
        />
      </label>

      <label className="login__field">
        <span>Password</span>
        <input
          name="password"
          type="password"
          value={form.password}
          onChange={(event) => update('password', event.target.value)}
          autoComplete="current-password"
          required
        />
      </label>

      {message && (
        <p className="login__error" role="alert">
          {message}
        </p>
      )}

      <button
        type="submit"
        className="button button--primary login__submit"
        disabled={busy}
      >
        {busy ? 'Signing in…' : 'Sign in'}
      </button>
    </form>
  )
}

/**
 * The login page: the only view the web UI offers until the daemon accepts a
 * session. `status` is `checking` while the app asks whether the current cookie
 * is still good, which is why the same page can say "Checking session…".
 *
 * The `admin`/`admin` account the storage DDL seeds is named in the hint, since
 * a fresh local checkout has no other way to get in.
 */
export function LoginPage({ status, error, busy, onSignIn, onDismissError }) {
  return (
    <main className="login">
      <section className="login__card">
        <div className="login__brand">
          <span className="brand-mark" aria-hidden="true">
            λ
          </span>
          <div>
            <p className="login__eyebrow">clai / web</p>
            <h1>Sign in</h1>
          </div>
        </div>

        {status === 'checking' ? (
          <p className="login__checking">Checking session…</p>
        ) : (
          <>
            <p className="login__lead">
              The agent console, git repositories, and notes are private to the
              accounts stored in the daemon's database.
            </p>

            <LoginForm
              error={error}
              busy={busy}
              onSubmit={onSignIn}
              onDismissError={onDismissError}
            />

            <p className="login__hint">
              A fresh local database is seeded with the account{' '}
              <code>admin</code> / <code>admin</code>. Change its password in{' '}
              <code>app_user</code> before exposing this daemon beyond localhost.
            </p>
          </>
        )}
      </section>
    </main>
  )
}
