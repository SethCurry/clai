# Git repositories and worktrees

`clai` keeps a registry of git repositories and the worktrees checked out from
them in PostgreSQL. A repository row holds the git URL and a short,
human-readable name; a worktree row records which repository it came from,
which branch is checked out there, and which directory it lives in.

Both tables are created by the DDL in [`schema/schema.sql`](../schema/schema.sql),
which `clai.daemon.postgres/ensure-schema!` applies (and which `docker compose
up` applies through its one-shot `schema` service).

| Table          | Columns                                                    |
| -------------- | ---------------------------------------------------------- |
| `git_repo`     | `id`, `name` (unique), `url`, `created_at`, `updated_at`    |
| `git_worktree` | `id`, `git_repo_id`, `branch`, `directory`, timestamps      |

`git_worktree.git_repo_id` is a foreign key to `git_repo(id)` with
`ON DELETE CASCADE`, so deleting a repository deletes its worktrees. A branch
can be checked out in only one worktree of a repository, and a directory can
hold only one worktree.

## Registering a repository

Start PostgreSQL (`docker compose up -d`) and use
`clai.daemon.postgres.git` from a REPL:

```clojure
(require '[clai.daemon.postgres :as pg]
         '[clai.daemon.postgres.git :as git])

(def client (pg/make-client))   ; localhost:5432/clai by default

(def repo (git/create-repo client {:name "clai"
                                   :url  "https://github.com/me/clai.git"}))
;; => {:repoId "1" :name "clai" :url "https://github.com/me/clai.git" ...}
```

Names and URLs are trimmed; both are required and the name is unique. Look
repositories up by id or by their friendlier name:

```clojure
(git/list-repos client)
(git/load-repo client "1")
(git/select-repo client "clai")

(git/update-repo client "1" {:name "clai-main"
                             :url  "git@github.com:me/clai.git"})
(git/delete-repo client "1")   ; also deletes the repo's worktrees
```

## Recording a worktree

```clojure
(def wt (git/create-worktree client {:repoId    (:repoId repo)
                                     :branch    "feature/git-repos"
                                     :directory "/home/me/worktrees/clai-git-repos"}))
;; => {:worktreeId "1" :repoId "1" :repoName "clai" :repoUrl "..." ...}

(git/list-worktrees client)              ; every worktree
(git/list-worktrees client (:repoId repo)) ; one repository's worktrees
(git/load-worktree client (:worktreeId wt))
(git/find-worktree client "/home/me/worktrees/clai-git-repos")
```

`git/create-worktree` only stores the row; it does not run `git worktree add`.
Use `clai.shell.git` for the git side, then record the result:

```clojure
(require '[clai.shell.git :as shell-git])

(shell-git/run! ["worktree" "add" "-b" "feature/git-repos"
                 "/home/me/worktrees/clai-git-repos"]
                {:dir "/home/me/src/clai"})
```

Move or re-branch a worktree row with `git/update-worktree`, and remove it with
`git/delete-worktree`. Ids are returned as strings, and lookups that do not
name a row return `nil` rather than throwing.

Worktree rows include `:repoName` and `:repoUrl` so a listing is readable
without a second lookup, which makes them easy to pipe into a picker:

```clojure
(->> (git/list-worktrees client)
     (map (juxt :repoName :branch :directory)))
```

## From the web UI

The web UI does not expose the registry yet; for now manage repositories and
worktrees from the REPL with the functions above.
