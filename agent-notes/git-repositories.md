# Git repository and worktree storage

## Decision

Users need to name the repositories clai works with and keep track of the
worktrees checked out from them, so the daemon owns two tables rather than
relying on `git worktree list` output:

- `git_repo`: `id`, `name` (unique), `url`, `created_at`, `updated_at`.
  `name` is the short, human-readable label users and the UI address a
  repository by; `url` is the git URL or local path.
- `git_worktree`: `id`, `git_repo_id` (FK), `branch`, `directory`,
  `created_at`, `updated_at`.

`git_worktree.git_repo_id` references `git_repo(id)` with `ON DELETE CASCADE`,
so deleting a repository does not leave orphaned worktrees. Two uniqueness
rules mirror git's own constraints: a branch is checked out in at most one
worktree per repository (`UNIQUE (git_repo_id, branch)`) and a directory holds
at most one worktree (`UNIQUE (directory)`). The `(git_repo_id, branch)` unique
index also serves lookups by repository, so no extra index is needed.

## Implementation

- Storage: `src/clai/daemon/postgres/git.clj` (`clai.daemon.postgres.git`).
  Follows `clai.daemon.postgres.sessions`: a client is the first argument, the
  schema is ensured once per client by a private `ready`, and ids are exposed
  as strings.
- DDL: `schema/schema.sql`. Both `CREATE TABLE IF NOT EXISTS` statements are
  idempotent, so existing databases pick them up on the next
  `ensure-schema!`/`docker compose up`.
- Public API: `list-repos`, `load-repo`, `select-repo` (by name),
  `repo-exists?`, `create-repo`, `update-repo`, `delete-repo`,
  `list-worktrees` (all or by repository), `load-worktree`, `find-worktree`
  (by directory), `create-worktree`, `update-worktree`, `delete-worktree`.
- Worktree reads join `git_repo` and expose `:repoName`/`:repoUrl` so a
  listing is readable without a second query.
- Malli schema types are uppercase (`GitRepo`, `GitRepoInput`, `GitWorktree`,
  `GitWorktreeInput`, `RepoRef`), and `clai.instrument` requires the namespace
  so the validators run.
- Tests: `test/clai/daemon/postgres/git_test.clj`. Pure normalization/conversion
  tests always run; the round-trip test runs only when `CLAI_TEST_POSTGRES_URL`
  is set.

## Deliberately not done

- No `git worktree add`/`remove` calls: the storage namespace only records
  rows. Shelling out stays in `clai.shell.git`.
- No web UI or HTTP API for the registry yet; the wiki documents the REPL
  workflow (`agent-wiki/git-repositories.md`).
- The `directory` is stored as given (trimmed), not canonicalized, because the
  daemon may not share a filesystem with the worktree.
- Branch is `NOT NULL`; detached-HEAD worktrees are not modeled.

## Testing against a local database

The local `docker compose` database has a password, and the integration tests
build a client from `CLAI_TEST_POSTGRES_URL` (which carries no credentials), so
pass the user/password through the environment:

```sh
CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
CLAI_POSTGRES_USER=clai CLAI_POSTGRES_PASSWORD=secret \
  lein test clai.daemon.postgres.git-test
```

Do not run the whole `clai.daemon.postgres-test` namespace with those variables
set: `make-client-url-wins` asserts that a URL supplies its own credentials, so
it only holds when no `PG*`/`CLAI_POSTGRES_*` credentials are in the
environment.
