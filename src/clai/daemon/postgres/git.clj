(ns clai.daemon.postgres.git
  "Git repository and worktree storage on PostgreSQL for `clai`'s daemon.

  The tables are declared in `schema/schema.sql` and created by
  `clai.daemon.postgres/ensure-schema!`:

      git_repo      id, name, url, created_at, updated_at
      git_worktree  id, git_repo_id, branch, directory, created_at, updated_at

  A repository row is a repo clai knows about: a human-readable `name` (unique,
  so it can be used to address the repo) and the git `url` it is fetched from.
  A worktree row records one checkout of that repository: the `branch` checked
  out there and the `directory` it lives in. A worktree belongs to exactly one
  repository through `git_repo_id`, and deleting the repository deletes its
  worktrees (`ON DELETE CASCADE`).

  Every function takes a `clai.daemon.postgres/Client` as its first argument
  and ensures the schema on first use, like
  `clai.daemon.postgres.sessions`. Repository and worktree ids are exposed as
  strings, and lookups that do not name a row return nil rather than throwing.

  ## Example

      (require '[clai.daemon.postgres :as pg]
               '[clai.daemon.postgres.git :as git])

      (def client (pg/open {:host \"localhost\"}))

      (def repo (git/create-repo client {:name \"clai\"
                                         :url  \"https://github.com/me/clai\"}))

      (git/create-worktree client {:repoId    (:repoId repo)
                                   :branch    \"feature/git-repos\"
                                   :directory \"/home/me/worktrees/clai-git-repos\"})

      (git/list-worktrees client (:repoId repo))"
  (:require [clai.daemon.postgres :as postgres]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def RepoRef
  "A reference to a `git_repo` row: its database id as a number or as a
  string of digits."
  [:or pos-int? [:re #"^[1-9][0-9]*$"]])

(def GitRepoInput
  "A repository accepted by [[create-repo]] and [[update-repo]]. `:name` is
  the short, human-readable label (unique across repositories) and `:url` the
  git URL or local path the repository lives at."
  [:map
   [:name non-empty-string]
   [:url non-empty-string]])

(def GitRepo
  "A `git_repo` row, as returned by [[create-repo]], [[load-repo]],
  [[select-repo]], and [[list-repos]]."
  [:map
   [:repoId non-empty-string]
   [:name non-empty-string]
   [:url non-empty-string]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

(def GitWorktreeInput
  "A worktree accepted by [[create-worktree]] and [[update-worktree]].
  `:repoId` names the `git_repo` row the worktree belongs to, `:branch` the
  branch checked out there, and `:directory` the worktree's path."
  [:map
   [:repoId RepoRef]
   [:branch non-empty-string]
   [:directory non-empty-string]])

(def GitWorktree
  "A `git_worktree` row joined to its repository, as returned by
  [[create-worktree]], [[load-worktree]], [[find-worktree]], and
  [[list-worktrees]]. The repository's name and URL are included so a worktree
  is readable without a second lookup."
  [:map
   [:worktreeId non-empty-string]
   [:repoId non-empty-string]
   [:repoName non-empty-string]
   [:repoUrl non-empty-string]
   [:branch non-empty-string]
   [:directory non-empty-string]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Schema readiness
;; ---------------------------------------------------------------------------

(defonce ^:private schema-ready
  ;; Clients whose storage tables have already been ensured. Keeping this here
  ;; lets callers build a client (and even an agent) without touching the
  ;; database; the schema is created on the first repository operation.
  (atom #{}))

(defn- ready
  "Ensure the storage tables exist for `client` (once per client) and return
  it."
  [client]
  (when-not (contains? @schema-ready client)
    (locking schema-ready
      (when-not (contains? @schema-ready client)
        (postgres/ensure-schema! client)
        (swap! schema-ready conj client))))
  client)

;; ---------------------------------------------------------------------------
;; Input normalization and row conversion
;; ---------------------------------------------------------------------------

(defn- blank->nil
  "`value` trimmed, or nil when it is nil or blank."
  [value]
  (let [text (some-> value str str/trim)]
    (when-not (str/blank? text) text)))

(defn ^:no-doc row-key
  "`id` as the database key it names, or nil when it does not name a row.

  Repository and worktree ids are exposed as strings, and `INSERT` returns the
  numeric key, so both are accepted. Anything non-numeric (for example an
  externally minted UUID) returns nil instead of throwing."
  {:malli/schema [:-> [:maybe [:or :string :int]] [:maybe pos-int?]]}
  [id]
  (when (some? id)
    (when-let [n (if (number? id) id (parse-long (str id)))]
      (when (pos? n) n))))

(defn ^:no-doc prepare-repo
  "Validate and normalize a repository input for storage: the name and URL are
  trimmed, and a blank one is rejected."
  {:malli/schema [:-> :map #'GitRepoInput]}
  [repo]
  (postgres/validate! GitRepoInput
                      (-> repo
                          (update :name blank->nil)
                          (update :url blank->nil))
                      "git repository"))

(defn ^:no-doc prepare-worktree
  "Validate and normalize a worktree input for storage: the branch and
  directory are trimmed, and `:repoId` is converted to the numeric
  `git_repo.id` it names."
  {:malli/schema [:-> :map #'GitWorktreeInput]}
  [worktree]
  (let [worktree (-> worktree
                     (update :branch blank->nil)
                     (update :directory blank->nil))]
    (-> (postgres/validate! GitWorktreeInput worktree "git worktree")
        (update :repoId row-key))))

(defn ^:no-doc row->repo
  "Convert a `git_repo` row to the public repository shape."
  {:malli/schema [:-> :map #'GitRepo]}
  [row]
  (cond-> {:repoId (str (:id row))
           :name   (:name row)
           :url    (:url row)}
    (some? (:created-at row))
    (assoc :createdAt (postgres/timestamp->iso (:created-at row)))

    (some? (:updated-at row))
    (assoc :updatedAt (postgres/timestamp->iso (:updated-at row)))))

(defn ^:no-doc row->worktree
  "Convert a `git_worktree` row joined to its `git_repo` row to the public
  worktree shape."
  {:malli/schema [:-> :map #'GitWorktree]}
  [row]
  (cond-> {:worktreeId (str (:id row))
           :repoId     (str (:git-repo-id row))
           :repoName   (:repo-name row)
           :repoUrl    (:repo-url row)
           :branch     (:branch row)
           :directory  (:directory row)}
    (some? (:created-at row))
    (assoc :createdAt (postgres/timestamp->iso (:created-at row)))

    (some? (:updated-at row))
    (assoc :updatedAt (postgres/timestamp->iso (:updated-at row)))))

;; ---------------------------------------------------------------------------
;; Repository SQL
;; ---------------------------------------------------------------------------

(def ^:private repo-columns
  "The column list shared by every repository query."
  "id, name, url, created_at, updated_at")

(def ^:private select-repo-columns
  "A SELECT of the repository columns, before any WHERE clause."
  (str "SELECT " repo-columns " FROM git_repo"))

(def ^:private list-repos-sql
  (str select-repo-columns " ORDER BY name, id"))

(def ^:private load-repo-sql
  (str select-repo-columns " WHERE id = ?"))

(def ^:private select-repo-sql
  (str select-repo-columns " WHERE name = ?"))

(def ^:private insert-repo-sql
  (str "INSERT INTO git_repo (name, url)
        VALUES (?, ?)
        RETURNING " repo-columns))

(def ^:private update-repo-sql
  (str "UPDATE git_repo
           SET name = ?, url = ?, updated_at = now()
         WHERE id = ?
        RETURNING " repo-columns))

(def ^:private delete-repo-sql
  "DELETE FROM git_repo WHERE id = ?")

;; ---------------------------------------------------------------------------
;; Worktree SQL
;; ---------------------------------------------------------------------------

(def ^:private worktree-columns
  "The joined column list shared by every worktree query."
  "w.id,
   w.git_repo_id,
   r.name AS repo_name,
   r.url AS repo_url,
   w.branch,
   w.directory,
   w.created_at,
   w.updated_at")

(def ^:private select-worktree-columns
  "A SELECT of the worktree columns joined to their repository, before any
  WHERE clause."
  (str "SELECT " worktree-columns "
          FROM git_worktree w
          JOIN git_repo r ON r.id = w.git_repo_id"))

(def ^:private list-worktrees-sql
  (str select-worktree-columns " ORDER BY r.name, w.directory, w.id"))

(def ^:private list-repo-worktrees-sql
  (str select-worktree-columns " WHERE w.git_repo_id = ? ORDER BY w.directory, w.id"))

(def ^:private load-worktree-sql
  (str select-worktree-columns " WHERE w.id = ?"))

(def ^:private find-worktree-sql
  (str select-worktree-columns " WHERE w.directory = ?"))

(def ^:private insert-worktree-sql
  "INSERT INTO git_worktree (git_repo_id, branch, directory)
   VALUES (?, ?, ?)
   RETURNING id")

(def ^:private update-worktree-sql
  "UPDATE git_worktree
      SET git_repo_id = ?, branch = ?, directory = ?, updated_at = now()
    WHERE id = ?
   RETURNING id")

(def ^:private delete-worktree-sql
  "DELETE FROM git_worktree WHERE id = ?")

(defn- worktree-by-id
  "Read the joined worktree row with database key `id` from `connectable`."
  [connectable id]
  (some-> (postgres/exec-one! connectable ::load-worktree
                              load-worktree-sql [id])
          row->worktree))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn list-repos
  "Return every registered repository, sorted by name."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'GitRepo]]}
  [client]
  (mapv row->repo
        (postgres/exec! (:datasource (ready client)) ::list-repos
                        list-repos-sql [])))

(defn load-repo
  "Return the repository named by `repo-id`, or nil when no such repository is
  stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'GitRepo]]}
  [client repo-id]
  (let [client (ready client)]
    (when-let [id (row-key repo-id)]
      (some-> (postgres/exec-one! (:datasource client) ::load-repo
                                  load-repo-sql [id])
              row->repo))))

(defn select-repo
  "Return the repository whose human-readable name is `name`, or nil when none
  is stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'GitRepo]]}
  [client name]
  (some-> (postgres/exec-one! (:datasource (ready client)) ::select-repo
                              select-repo-sql [(str/trim name)])
          row->repo))

(defn create-repo
  "Register a repository and return it.

  `repo` is validated with [[GitRepoInput]]; the name is unique, so a
  duplicate name makes the database reject the insert."
  {:malli/schema [:-> #'postgres/client-schema #'GitRepoInput #'GitRepo]}
  [client repo]
  (let [repo  (prepare-repo repo)
        saved (row->repo
               (postgres/exec-one! (:datasource (ready client)) ::create-repo
                                   insert-repo-sql
                                   [(:name repo) (:url repo)]))]
    (tel/log! {:level :debug
               :id    ::repo-created
               :data  {:repoId (:repoId saved)
                       :name   (:name saved)
                       :url    (:url saved)}}
              "Registered PostgreSQL git repository")
    saved))

(defn update-repo
  "Replace the name and URL of `repo-id` and return the updated repository, or
  nil when no such repository is stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  #'GitRepoInput [:maybe #'GitRepo]]}
  [client repo-id repo]
  (let [client (ready client)
        repo   (prepare-repo repo)]
    (when-let [id (row-key repo-id)]
      (some-> (postgres/exec-one! (:datasource client) ::update-repo
                                  update-repo-sql
                                  [(:name repo) (:url repo) id])
              row->repo))))

(defn delete-repo
  "Delete `repo-id` and its worktrees (`git_worktree.git_repo_id` is
  `ON DELETE CASCADE`). Returns true when a repository was removed."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client repo-id]
  (let [client   (ready client)
        id       (row-key repo-id)
        deleted? (boolean
                  (when id
                    (pos? (postgres/updated-count
                           (postgres/exec-one! (:datasource client) ::delete-repo
                                               delete-repo-sql [id])))))]
    (tel/log! {:level :debug
               :id    ::repo-deleted
               :data  {:repoId repo-id :deleted? deleted?}}
              "Deleted PostgreSQL git repository")
    deleted?))

(defn repo-exists?
  "Return true when `repo-id` names a stored repository."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client repo-id]
  (let [client (ready client)]
    (boolean
     (when-let [id (row-key repo-id)]
       (postgres/exec-one! (:datasource client) ::repo-exists
                           "SELECT 1 AS one FROM git_repo WHERE id = ?"
                           [id])))))

(defn list-worktrees
  "Return every stored worktree, sorted by repository name and directory. With
  `repo-id`, return only that repository's worktrees."
  {:malli/schema [:function
                  [:=> [:cat #'postgres/client-schema]
                   [:vector #'GitWorktree]]
                  [:=> [:cat #'postgres/client-schema #'non-empty-string]
                   [:vector #'GitWorktree]]]}
  ([client]
   (mapv row->worktree
         (postgres/exec! (:datasource (ready client)) ::list-worktrees
                         list-worktrees-sql [])))
  ([client repo-id]
   (let [client (ready client)]
     (if-let [id (row-key repo-id)]
       (mapv row->worktree
             (postgres/exec! (:datasource client) ::list-repo-worktrees
                             list-repo-worktrees-sql [id]))
       []))))

(defn load-worktree
  "Return the worktree named by `worktree-id`, or nil when no such worktree is
  stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'GitWorktree]]}
  [client worktree-id]
  (let [client (ready client)]
    (when-let [id (row-key worktree-id)]
      (worktree-by-id (:datasource client) id))))

(defn find-worktree
  "Return the worktree stored for `directory`, or nil when no worktree lives
  there."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'GitWorktree]]}
  [client directory]
  (some-> (postgres/exec-one! (:datasource (ready client)) ::find-worktree
                              find-worktree-sql [(str/trim directory)])
          row->worktree))

(defn create-worktree
  "Record a worktree checked out from a registered repository and return it.

  `worktree` is validated with [[GitWorktreeInput]]. `:repoId` must name a
  `git_repo` row (the foreign key enforces it), a branch can only be checked
  out in one worktree of a repository, and a directory can only hold one
  worktree, so duplicates make the database reject the insert."
  {:malli/schema [:-> #'postgres/client-schema #'GitWorktreeInput
                  #'GitWorktree]}
  [client worktree]
  (let [client   (ready client)
        worktree (prepare-worktree worktree)
        result   (postgres/exec-one! (:datasource client) ::create-worktree
                                     insert-worktree-sql
                                     [(:repoId worktree)
                                      (:branch worktree)
                                      (:directory worktree)])
        saved    (worktree-by-id (:datasource client) (:id result))]
    (tel/log! {:level :debug
               :id    ::worktree-created
               :data  {:worktreeId (:worktreeId saved)
                       :repoId     (:repoId saved)
                       :branch     (:branch saved)
                       :directory  (:directory saved)}}
              "Recorded PostgreSQL git worktree")
    saved))

(defn update-worktree
  "Replace the repository, branch, and directory of `worktree-id` and return
  the updated worktree, or nil when no such worktree is stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  #'GitWorktreeInput [:maybe #'GitWorktree]]}
  [client worktree-id worktree]
  (let [client   (ready client)
        worktree (prepare-worktree worktree)]
    (when-let [id (row-key worktree-id)]
      (when-let [result (postgres/exec-one!
                         (:datasource client) ::update-worktree
                         update-worktree-sql
                         [(:repoId worktree)
                          (:branch worktree)
                          (:directory worktree)
                          id])]
        (worktree-by-id (:datasource client) (:id result))))))

(defn delete-worktree
  "Delete `worktree-id`. Returns true when a worktree was removed."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client worktree-id]
  (let [client   (ready client)
        id       (row-key worktree-id)
        deleted? (boolean
                  (when id
                    (pos? (postgres/updated-count
                           (postgres/exec-one! (:datasource client)
                                               ::delete-worktree
                                               delete-worktree-sql [id])))))]
    (tel/log! {:level :debug
               :id    ::worktree-deleted
               :data  {:worktreeId worktree-id :deleted? deleted?}}
              "Deleted PostgreSQL git worktree")
    deleted?))
