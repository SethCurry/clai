import { useEffect, useMemo, useState } from 'react'
import { formatTimestamp } from '../format.js'
import {
  EMPTY_REPO_FORM,
  EMPTY_WORKTREE_FORM,
  formToRepoInput,
  formToWorktreeInput,
  repoToForm,
  worktreeToForm,
  worktreesForRepo,
} from '../gitRepos.js'
import { useGitRepos } from '../useGitRepos.js'

function worktreeCountLabel(count) {
  return `${count} worktree${count === 1 ? '' : 's'}`
}

/**
 * Create or rename a repository. The caller owns the API call, so the form
 * only has to validate its own fields and surface the failure inline.
 */
function RepoForm({ repo, onSave, onCancel }) {
  const [form, setForm] = useState(() => repoToForm(repo ?? EMPTY_REPO_FORM))
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const update = (field, value) => {
    setForm((previous) => ({ ...previous, [field]: value }))
  }

  const submit = async (event) => {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      await onSave(formToRepoInput(form))
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="list-panel">
      <header className="list-panel__head">
        <div>
          <p className="list-page__eyebrow">
            {repo ? 'edit repository' : 'new repository'}
          </p>
          <h3>{repo ? repo.name : 'Register a repository'}</h3>
          <p>
            A name the UI can address the repository by, and the git URL or
            path it is fetched from.
          </p>
        </div>
      </header>

      <form className="config-form" onSubmit={submit}>
        <label className="config-form__field">
          <span>Name</span>
          <input
            value={form.name}
            onChange={(event) => update('name', event.target.value)}
            placeholder="clai"
            autoFocus
            required
          />
        </label>

        <label className="config-form__field">
          <span>Git URL or path</span>
          <input
            className="config-form__code"
            value={form.url}
            onChange={(event) => update('url', event.target.value)}
            placeholder="git@github.com:me/clai.git"
            spellCheck={false}
            required
          />
        </label>

        {error && <p className="config-form__error">{error}</p>}

        <div className="config-form__actions">
          <span className="config-form__spacer" />
          <button
            type="button"
            className="button button--quiet"
            disabled={busy}
            onClick={onCancel}
          >
            Cancel
          </button>
          <button type="submit" className="button button--primary" disabled={busy}>
            {busy ? 'Saving…' : repo ? 'Save repository' : 'Add repository'}
          </button>
        </div>
      </form>
    </div>
  )
}

/**
 * Record or move a worktree. The repository picker lets an existing worktree
 * be reassigned, which mirrors `git/update-worktree`'s replace semantics.
 */
function WorktreeForm({ worktree, repos, defaultRepoId, onSave, onCancel }) {
  const [form, setForm] = useState(() =>
    worktreeToForm(
      worktree ?? { ...EMPTY_WORKTREE_FORM, repoId: defaultRepoId ?? '' },
    ),
  )
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const update = (field, value) => {
    setForm((previous) => ({ ...previous, [field]: value }))
  }

  const submit = async (event) => {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      await onSave(formToWorktreeInput(form))
    } catch (failure) {
      setError(failure.message || String(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="config-form git-worktree-form" onSubmit={submit}>
      <div className="config-form__title">
        {worktree ? `Edit worktree #${worktree.worktreeId}` : 'New worktree'}
      </div>

      <label className="config-form__field">
        <span>Repository</span>
        <select
          value={form.repoId}
          onChange={(event) => update('repoId', event.target.value)}
          required
        >
          <option value="">Choose a repository</option>
          {repos.map((repo) => (
            <option key={repo.repoId} value={repo.repoId}>
              {repo.name}
            </option>
          ))}
        </select>
      </label>

      <div className="config-form__row">
        <label className="config-form__field">
          <span>Branch</span>
          <input
            value={form.branch}
            onChange={(event) => update('branch', event.target.value)}
            placeholder="feature/git-ui"
            autoFocus
            required
          />
        </label>

        <label className="config-form__field">
          <span>Directory</span>
          <input
            className="config-form__code"
            value={form.directory}
            onChange={(event) => update('directory', event.target.value)}
            placeholder="/home/me/worktrees/clai-git-ui"
            spellCheck={false}
            required
          />
        </label>
      </div>

      {error && <p className="config-form__error">{error}</p>}

      <div className="config-form__actions">
        <span className="config-form__spacer" />
        <button
          type="button"
          className="button button--quiet"
          disabled={busy}
          onClick={onCancel}
        >
          Cancel
        </button>
        <button type="submit" className="button button--primary" disabled={busy}>
          {busy ? 'Saving…' : worktree ? 'Save worktree' : 'Add worktree'}
        </button>
      </div>
    </form>
  )
}

function RepoList({ repos, worktrees, selectedId, onSelect, onNew }) {
  return (
    <aside className="list-page__list" aria-label="Git repositories">
      <div className="list-page__list-head">
        <h3>Repositories</h3>
        <span className="pill">{repos.length}</span>
      </div>
      <button className="button button--primary" onClick={onNew}>
        + New repository
      </button>

      {repos.length === 0 ? (
        <p className="list-page__empty">
          No repositories yet. Register one to name the code clai works with.
        </p>
      ) : (
        <div className="git-repo-list">
          {repos.map((repo) => {
            const count = worktreesForRepo(worktrees, repo.repoId).length
            const active = repo.repoId === selectedId
            return (
              <button
                key={repo.repoId}
                className={`git-repo-card${active ? ' git-repo-card--active' : ''}`}
                onClick={() => onSelect(repo.repoId)}
              >
                <span className="git-repo-card__name">{repo.name}</span>
                <span className="git-repo-card__url">{repo.url}</span>
                <span className="git-repo-card__meta">
                  {worktreeCountLabel(count)}
                </span>
              </button>
            )
          })}
        </div>
      )}
    </aside>
  )
}

function RepoDetail({
  repo,
  worktrees,
  onEdit,
  onDelete,
  onAddWorktree,
  onEditWorktree,
  onDeleteWorktree,
  worktreeEditor,
  repos,
  onSaveWorktree,
  onCancelWorktree,
}) {
  return (
    <div className="list-panel">
      <header className="list-panel__head git-repo__head">
        <div className="git-repo__identity">
          <p className="list-page__eyebrow">repository #{repo.repoId}</p>
          <h3>{repo.name}</h3>
          <p className="git-repo__url">{repo.url}</p>
        </div>
        <div className="git-repo__actions">
          <button className="button button--quiet" onClick={() => onEdit(repo)}>
            Edit
          </button>
          <button
            className="button button--danger"
            onClick={() => onDelete(repo)}
          >
            Delete
          </button>
        </div>
      </header>

      <dl className="git-repo__meta">
        <div>
          <dt>Created</dt>
          <dd>{formatTimestamp(repo.createdAt)}</dd>
        </div>
        <div>
          <dt>Updated</dt>
          <dd>{formatTimestamp(repo.updatedAt)}</dd>
        </div>
        <div>
          <dt>Worktrees</dt>
          <dd>{worktrees.length}</dd>
        </div>
      </dl>

      <section className="git-worktrees">
        <div className="git-worktrees__head">
          <div>
            <h4>Worktrees</h4>
            <p>
              Checkouts of this repository. Recording one stores its branch and
              directory; it does not run <code>git worktree add</code>.
            </p>
          </div>
          {!worktreeEditor && (
            <button className="button button--primary" onClick={onAddWorktree}>
              + Add worktree
            </button>
          )}
        </div>

        {worktreeEditor && (
          <WorktreeForm
            key={worktreeEditor.worktree?.worktreeId ?? 'new'}
            worktree={worktreeEditor.worktree}
            repos={repos}
            defaultRepoId={repo.repoId}
            onSave={onSaveWorktree}
            onCancel={onCancelWorktree}
          />
        )}

        {worktrees.length === 0 ? (
          <p className="list-page__empty">
            No worktrees recorded for this repository yet.
          </p>
        ) : (
          <ul className="git-worktree-list">
            {worktrees.map((worktree) => (
              <li className="git-worktree" key={worktree.worktreeId}>
                <div className="git-worktree__main">
                  <span className="git-worktree__branch">
                    {worktree.branch}
                  </span>
                  <span className="git-worktree__directory">
                    {worktree.directory}
                  </span>
                </div>
                <div className="git-worktree__actions">
                  <button
                    className="button button--quiet"
                    onClick={() => onEditWorktree(worktree)}
                  >
                    Edit
                  </button>
                  <button
                    className="button button--quiet"
                    onClick={() => onDeleteWorktree(worktree)}
                  >
                    Delete
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

/**
 * The git repository registry page: list repositories, register new ones,
 * edit or delete them, and manage the worktrees recorded for the selected
 * repository. Served by `/api/git-repos` and `/api/git-worktrees`.
 */
export function GitReposPage({ onClose }) {
  const git = useGitRepos()
  const [selectedId, setSelectedId] = useState(null)
  const [repoEditor, setRepoEditor] = useState(null)
  const [worktreeEditor, setWorktreeEditor] = useState(null)

  // Keep a valid selection as the list loads and after a repository is
  // deleted, without overriding the user's choice.
  useEffect(() => {
    if (git.repos.length === 0) {
      if (selectedId !== null) setSelectedId(null)
      return
    }
    if (!git.repos.some((repo) => repo.repoId === selectedId)) {
      setSelectedId(git.repos[0].repoId)
    }
  }, [git.repos, selectedId])

  const selectedRepo = useMemo(
    () => git.repos.find((repo) => repo.repoId === selectedId) ?? null,
    [git.repos, selectedId],
  )
  const selectedWorktrees = useMemo(
    () => worktreesForRepo(git.worktrees, selectedId),
    [git.worktrees, selectedId],
  )

  const selectRepo = (repoId) => {
    setSelectedId(repoId)
    setRepoEditor(null)
    setWorktreeEditor(null)
  }

  const saveRepo = async (input) => {
    const saved = await git.saveRepo(input, repoEditor?.repo?.repoId ?? null)
    setSelectedId(saved.repoId)
    setRepoEditor(null)
  }

  const removeRepo = async (repo) => {
    const count = worktreesForRepo(git.worktrees, repo.repoId).length
    const suffix = count === 0 ? '' : ` and its ${worktreeCountLabel(count)}`
    if (!window.confirm(`Delete repository "${repo.name}"${suffix}?`)) return
    try {
      await git.deleteRepo(repo.repoId)
      setRepoEditor(null)
      setWorktreeEditor(null)
    } catch {
      // The hook already stored the error for the page banner.
    }
  }

  const saveWorktree = async (input) => {
    const saved = await git.saveWorktree(
      input,
      worktreeEditor?.worktree?.worktreeId ?? null,
    )
    setSelectedId(saved.repoId)
    setWorktreeEditor(null)
  }

  const removeWorktree = async (worktree) => {
    if (
      !window.confirm(
        `Delete worktree "${worktree.branch}" at ${worktree.directory}?`,
      )
    ) {
      return
    }
    try {
      await git.deleteWorktree(worktree.worktreeId)
      setWorktreeEditor(null)
    } catch {
      // The hook already stored the error for the page banner.
    }
  }

  return (
    <main className="list-page">
      <header className="list-page__header">
        <div className="list-page__heading">
          <p className="list-page__eyebrow">registry</p>
          <h2>Git repositories</h2>
          <p>
            Register the repositories clai works with and record the worktrees
            checked out from them.
          </p>
        </div>
        <div className="list-page__actions">
          <button
            className="button button--quiet"
            onClick={git.refresh}
            disabled={git.loading}
          >
            {git.loading ? 'Loading…' : 'Refresh'}
          </button>
          <button className="button button--quiet" onClick={onClose}>
            Back to sessions
          </button>
        </div>
      </header>

      {git.error && (
        <div className="list-page__error" role="alert">
          <span>{git.error}</span>
          <button
            className="list-page__error-close"
            aria-label="Dismiss repository error"
            onClick={git.dismissError}
          >
            ×
          </button>
        </div>
      )}

      <div className="list-page__body">
        <RepoList
          repos={git.repos}
          worktrees={git.worktrees}
          selectedId={repoEditor ? null : selectedId}
          onSelect={selectRepo}
          onNew={() => {
            setRepoEditor({ repo: null })
            setWorktreeEditor(null)
          }}
        />

        <section className="list-page__detail">
          {repoEditor ? (
            <RepoForm
              key={repoEditor.repo?.repoId ?? 'new'}
              repo={repoEditor.repo}
              onSave={saveRepo}
              onCancel={() => setRepoEditor(null)}
            />
          ) : selectedRepo ? (
            <RepoDetail
              repo={selectedRepo}
              worktrees={selectedWorktrees}
              repos={git.repos}
              worktreeEditor={worktreeEditor}
              onEdit={(repo) => {
                setRepoEditor({ repo })
                setWorktreeEditor(null)
              }}
              onDelete={removeRepo}
              onAddWorktree={() => setWorktreeEditor({ worktree: null })}
              onEditWorktree={(worktree) => setWorktreeEditor({ worktree })}
              onDeleteWorktree={removeWorktree}
              onSaveWorktree={saveWorktree}
              onCancelWorktree={() => setWorktreeEditor(null)}
            />
          ) : (
            <div className="list-page__placeholder">
              <p>Select a repository to view it, or register a new one.</p>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}
