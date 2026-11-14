/**
 * Shape and validate git repository and worktree forms for the API. Keeping
 * this separate from the components makes the trimming and required-field
 * rules testable without a DOM.
 */

export const EMPTY_REPO_FORM = {
  name: '',
  url: '',
}

export const EMPTY_WORKTREE_FORM = {
  repoId: '',
  branch: '',
  directory: '',
}

/**
 * Turn a stored repository into editable form values.
 */
export function repoToForm(repo) {
  return {
    name: repo?.name ?? '',
    url: repo?.url ?? '',
  }
}

/**
 * Turn a stored worktree into editable form values.
 */
export function worktreeToForm(worktree) {
  return {
    repoId: worktree?.repoId ?? '',
    branch: worktree?.branch ?? '',
    directory: worktree?.directory ?? '',
  }
}

/**
 * Trim and validate a repository form. Both fields are required; the daemon
 * trims again, but rejecting blanks here gives immediate feedback.
 */
export function formToRepoInput(form) {
  const name = String(form.name ?? '').trim()
  const url = String(form.url ?? '').trim()
  if (!name) throw new Error('A repository name is required.')
  if (!url) throw new Error('A git URL or path is required.')
  return { name, url }
}

/**
 * Trim and validate a worktree form. A worktree needs the repository it came
 * from, the branch checked out there, and the directory it lives in.
 */
export function formToWorktreeInput(form) {
  const repoId = String(form.repoId ?? '').trim()
  const branch = String(form.branch ?? '').trim()
  const directory = String(form.directory ?? '').trim()
  if (!repoId) throw new Error('Choose the repository this worktree belongs to.')
  if (!branch) throw new Error('A branch name is required.')
  if (!directory) throw new Error('A worktree directory is required.')
  return { repoId, branch, directory }
}

/**
 * Repositories are shown by name, so keep the list in that order.
 */
export function sortRepos(repos) {
  return [...repos].sort((a, b) =>
    String(a.name ?? '').localeCompare(String(b.name ?? '')),
  )
}

/**
 * Worktrees are shown by the directory they live in, then by branch.
 */
export function sortWorktrees(worktrees) {
  return [...worktrees].sort((a, b) => {
    const byDirectory = String(a.directory ?? '').localeCompare(
      String(b.directory ?? ''),
    )
    return byDirectory || String(a.branch ?? '').localeCompare(String(b.branch ?? ''))
  })
}

/**
 * The worktrees that belong to one repository.
 */
export function worktreesForRepo(worktrees, repoId) {
  return worktrees.filter((worktree) => worktree.repoId === String(repoId))
}
