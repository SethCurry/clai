import { useCallback, useEffect, useState } from 'react'
import { requestJson } from './api.js'
import { sortRepos, sortWorktrees } from './gitRepos.js'

function repoPath(repoId) {
  return `/api/git-repos/${encodeURIComponent(repoId)}`
}

function worktreePath(worktreeId) {
  return `/api/git-worktrees/${encodeURIComponent(worktreeId)}`
}

function jsonOptions(method, body) {
  return {
    method,
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  }
}

/**
 * Own the git repository registry: load repositories and worktrees, and keep
 * both lists in sync after creates, updates, and deletes. Mutation errors are
 * stored in `error` and rethrown so the form that triggered them can also show
 * them inline.
 */
export function useGitRepos() {
  const [repos, setRepos] = useState([])
  const [worktrees, setWorktrees] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const report = useCallback((failure) => {
    setError(failure?.message || String(failure))
  }, [])

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [repoBody, worktreeBody] = await Promise.all([
        requestJson('/api/git-repos'),
        requestJson('/api/git-worktrees'),
      ])
      setRepos(sortRepos(repoBody.repos ?? []))
      setWorktrees(sortWorktrees(worktreeBody.worktrees ?? []))
      setError('')
    } catch (failure) {
      report(failure)
    } finally {
      setLoading(false)
    }
  }, [report])

  useEffect(() => {
    refresh()
  }, [refresh])

  const saveRepo = useCallback(
    async (repo, repoId) => {
      try {
        const saved = await requestJson(
          repoId ? repoPath(repoId) : '/api/git-repos',
          jsonOptions(repoId ? 'PUT' : 'POST', repo),
        )
        setRepos((previous) =>
          sortRepos([
            ...previous.filter((item) => item.repoId !== saved.repoId),
            saved,
          ]),
        )
        setError('')
        return saved
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report],
  )

  const deleteRepo = useCallback(
    async (repoId) => {
      try {
        await requestJson(repoPath(repoId), { method: 'DELETE' })
        setRepos((previous) =>
          previous.filter((item) => item.repoId !== String(repoId)),
        )
        setWorktrees((previous) =>
          previous.filter((item) => item.repoId !== String(repoId)),
        )
        setError('')
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report],
  )

  const saveWorktree = useCallback(
    async (worktree, worktreeId) => {
      try {
        const saved = await requestJson(
          worktreeId ? worktreePath(worktreeId) : '/api/git-worktrees',
          jsonOptions(worktreeId ? 'PUT' : 'POST', worktree),
        )
        setWorktrees((previous) =>
          sortWorktrees([
            ...previous.filter((item) => item.worktreeId !== saved.worktreeId),
            saved,
          ]),
        )
        setError('')
        return saved
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report],
  )

  const deleteWorktree = useCallback(
    async (worktreeId) => {
      try {
        await requestJson(worktreePath(worktreeId), { method: 'DELETE' })
        setWorktrees((previous) =>
          previous.filter((item) => item.worktreeId !== String(worktreeId)),
        )
        setError('')
      } catch (failure) {
        report(failure)
        throw failure
      }
    },
    [report],
  )

  return {
    repos,
    worktrees,
    loading,
    error,
    refresh,
    dismissError: () => setError(''),
    saveRepo,
    deleteRepo,
    saveWorktree,
    deleteWorktree,
  }
}
