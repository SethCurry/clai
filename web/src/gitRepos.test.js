import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  EMPTY_REPO_FORM,
  EMPTY_WORKTREE_FORM,
  formToRepoInput,
  formToWorktreeInput,
  repoToForm,
  sortRepos,
  sortWorktrees,
  worktreeToForm,
  worktreesForRepo,
} from './gitRepos.js'

test('repoToForm turns a stored repo into editable values', () => {
  assert.deepEqual(
    repoToForm({
      repoId: '1',
      name: 'clai',
      url: 'https://example.com/clai.git',
    }),
    { name: 'clai', url: 'https://example.com/clai.git' },
  )
  assert.deepEqual(repoToForm(null), EMPTY_REPO_FORM)
})

test('formToRepoInput trims and validates the name and url', () => {
  assert.deepEqual(
    formToRepoInput({ name: '  clai  ', url: ' git@example.com:me/clai.git ' }),
    { name: 'clai', url: 'git@example.com:me/clai.git' },
  )
  assert.throws(() => formToRepoInput({ name: '  ', url: 'git://x' }), {
    message: 'A repository name is required.',
  })
  assert.throws(() => formToRepoInput({ name: 'clai', url: ' ' }), {
    message: 'A git URL or path is required.',
  })
})

test('worktreeToForm and formToWorktreeInput round-trip a worktree', () => {
  const stored = {
    worktreeId: '4',
    repoId: '1',
    repoName: 'clai',
    branch: 'feature/git-ui',
    directory: '/tmp/clai-git-ui',
  }
  assert.deepEqual(worktreeToForm(stored), {
    repoId: '1',
    branch: 'feature/git-ui',
    directory: '/tmp/clai-git-ui',
  })
  assert.deepEqual(formToWorktreeInput(worktreeToForm(stored)), {
    repoId: '1',
    branch: 'feature/git-ui',
    directory: '/tmp/clai-git-ui',
  })
  assert.deepEqual(worktreeToForm(null), EMPTY_WORKTREE_FORM)
})

test('formToWorktreeInput requires a repository, branch, and directory', () => {
  assert.throws(
    () =>
      formToWorktreeInput({ repoId: '', branch: 'main', directory: '/tmp/x' }),
    { message: 'Choose the repository this worktree belongs to.' },
  )
  assert.throws(
    () => formToWorktreeInput({ repoId: '1', branch: '', directory: '/tmp/x' }),
    { message: 'A branch name is required.' },
  )
  assert.throws(
    () => formToWorktreeInput({ repoId: '1', branch: 'main', directory: ' ' }),
    { message: 'A worktree directory is required.' },
  )
})

test('sortRepos sorts by name without mutating the input', () => {
  const repos = [
    { repoId: '2', name: 'zeta' },
    { repoId: '1', name: 'alpha' },
  ]
  assert.deepEqual(
    sortRepos(repos).map((repo) => repo.name),
    ['alpha', 'zeta'],
  )
  assert.deepEqual(
    repos.map((repo) => repo.name),
    ['zeta', 'alpha'],
  )
})

test('sortWorktrees sorts by directory and then branch', () => {
  const worktrees = [
    { directory: '/tmp/b', branch: 'main' },
    { directory: '/tmp/a', branch: 'trunk' },
    { directory: '/tmp/a', branch: 'feature' },
  ]
  assert.deepEqual(
    sortWorktrees(worktrees).map((worktree) => worktree.branch),
    ['feature', 'trunk', 'main'],
  )
})

test('worktreesForRepo keeps only the matching repository', () => {
  const worktrees = [
    { worktreeId: '1', repoId: '1' },
    { worktreeId: '2', repoId: '2' },
  ]
  assert.deepEqual(
    worktreesForRepo(worktrees, '1').map((worktree) => worktree.worktreeId),
    ['1'],
  )
  assert.deepEqual(worktreesForRepo(worktrees, 1), [
    { worktreeId: '1', repoId: '1' },
  ])
})
