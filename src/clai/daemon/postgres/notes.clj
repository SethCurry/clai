(ns clai.daemon.postgres.notes
  "Note storage on PostgreSQL for `clai`'s daemon.

  The table is declared in `schema/schema.sql` and created by
  `clai.daemon.postgres/ensure-schema!`:

      note  id, title, content, created_at, updated_at

  A note is a titled Markdown document; `title` and `content` are both
  mandatory and a blank one is rejected.

  ## Links

  A note links to another note by writing its title in double square brackets,
  as in `[[BM25 ranking]]`. The links are not stored separately: they are read
  back out of the content whenever a note is loaded, so a link is always in
  step with the text and a reference to a note that does not exist yet simply
  resolves once that note is written.

  A title is therefore how notes address each other, and it is unique across
  the store, compared case-insensitively and ignoring surrounding space, so
  `[[bm25 ranking]]` and `[[BM25 ranking]]` name the same note. Creating or
  renaming a note to a title another note already uses is rejected with an
  `ex-info`.

  ## Search

  [[search-notes]] ranks notes by BM25 relevance through the
  `note_content_bm25_idx` index that `schema/schema.sql` creates when the
  `pg_textsearch` extension is installed. On a server without that extension
  the same function falls back to a case-insensitive substring match over the
  content, so callers do not have to know which server they are talking to.
  Both modes return the most relevant notes first, limited by `:limit`.

  Every function takes a `clai.daemon.postgres/Client` as its first argument
  and ensures the schema on first use, like
  `clai.daemon.postgres.sessions`. Note ids are exposed as strings, and lookups
  that do not name a row return nil rather than throwing.

  ## Example

      (require '[clai.daemon.postgres :as pg]
               '[clai.daemon.postgres.notes :as notes])

      (def client (pg/open {:host \"localhost\"}))

      (def ranking (notes/create-note client {:title   \"BM25\"
                                              :content \"Notes are ranked by BM25.\"}))

      (def links (notes/create-note client {:title   \"Links\"
                                            :content \"This note points at [[BM25]].\"}))

      (notes/load-note client (:noteId links))   ; => links [ranking id]
      (notes/load-note client (:noteId ranking)) ; => backlinks [links id]
      (notes/search-notes client {:q \"ranked\"})"
  (:require [clai.daemon.postgres :as postgres]
            [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def NoteInput
  "A note accepted by [[create-note]] and [[update-note]]: a mandatory `:title`
  and `:content`. A note links to others by writing their titles as
  `[[Title]]` in its content; there is no separate link field."
  [:map
   [:title non-empty-string]
   [:content non-empty-string]])

(def Note
  "A `note` row, as returned by [[list-notes]] and [[search-notes]]."
  [:map
   [:noteId non-empty-string]
   [:title non-empty-string]
   [:content non-empty-string]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

(def NoteDetail
  "A `note` row with its links, as returned by [[load-note]], [[create-note]],
  and [[update-note]]. `:links` are the ids of the notes its content references
  with `[[Title]]`, `:backlinks` the ids of the notes that reference it."
  [:map
   [:noteId non-empty-string]
   [:title non-empty-string]
   [:content non-empty-string]
   [:links [:vector non-empty-string]]
   [:backlinks [:vector non-empty-string]]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

(def SearchInput
  "Options for [[search-notes]]: the query `:q`, and how many matches to
  return as `:limit` (default [[default-search-limit]])."
  [:map
   [:q non-empty-string]
   [:limit {:optional true} pos-int?]])

(def ^:private default-search-limit
  "The number of notes [[search-notes]] returns when `:limit` is not given."
  20)

;; ---------------------------------------------------------------------------
;; Input normalization and row conversion
;; ---------------------------------------------------------------------------

(defn- blank->nil
  "`value` trimmed, or nil when it is nil or blank."
  {:malli/schema [:-> :any [:maybe non-empty-string]]}
  [value]
  (let [text (some-> value str str/trim)]
    (when-not (str/blank? text) text)))

(defn ^:no-doc row-key
  "`id` as the database key it names, or nil when it does not name a row.

  Note ids are exposed as strings, and `INSERT` returns the numeric key, so
  both are accepted. Anything non-numeric (for example an externally minted
  UUID) returns nil instead of throwing."
  {:malli/schema [:-> [:maybe [:or :string :int]] [:maybe pos-int?]]}
  [id]
  (when (some? id)
    (when-let [n (if (number? id) id (parse-long (str id)))]
      (when (pos? n) n))))

(defn ^:no-doc prepare-note
  "Validate and normalize a note input for storage: the title and content are
  trimmed and a blank one is rejected."
  {:malli/schema [:-> :map #'NoteInput]}
  [note]
  ;; Only the note's own fields are considered, so a JSON body may carry extra
  ;; keys (such as the id from a PUT path) without failing validation.
  (let [note (-> (select-keys note [:title :content])
                 (update :title blank->nil)
                 (update :content blank->nil))]
    (postgres/validate! NoteInput note "note")))

(defn ^:no-doc row->note
  "Convert a `note` row to the public note shape."
  {:malli/schema [:-> :map #'Note]}
  [row]
  (cond-> {:noteId  (str (:id row))
           :title   (:title row)
           :content (:content row)}
    (some? (:created-at row))
    (assoc :createdAt (postgres/timestamp->iso (:created-at row)))

    (some? (:updated-at row))
    (assoc :updatedAt (postgres/timestamp->iso (:updated-at row)))))

;; ---------------------------------------------------------------------------
;; Links
;; ---------------------------------------------------------------------------
;;
;; A link is written in a note's Markdown as `[[Title]]`. Nothing about the
;; link is stored: reading a note parses the references out of every note's
;; content and resolves them against the stored titles, which keeps both
;; directions in step with the text and with the note titles.

(def ^:private note-link-re
  "Matches the `[[Title]]` wiki-link syntax. The title may not contain brackets
  or a line break, so a stray `[[` cannot swallow the rest of a note."
  #"\[\[([^\[\]\r\n]+)\]\]")

(defn ^:no-doc link-titles
  "The titles `content` links to with the `[[Title]]` syntax, trimmed, in
  first-appearance order and without duplicates."
  {:malli/schema [:-> [:maybe :string] [:vector non-empty-string]]}
  [content]
  (->> (re-seq note-link-re (str content))
       (map (comp str/trim second))
       (remove str/blank?)
       distinct
       vec))

(defn- title-key
  "The key two titles are compared by: trimmed and lower-cased, so `[[BM25]]`
  names a note titled \"bm25\"."
  {:malli/schema [:-> :any [:maybe non-empty-string]]}
  [title]
  (some-> title str str/trim str/lower-case not-empty))

(defn- resolved-link-ids
  "Resolve `titles` to note ids through `ids-by-title`, dropping titles that
  name no note and the note `self-id` itself, keeping the order the titles
  appear in."
  [ids-by-title self-id titles]
  (->> titles
       (keep (fn [title] (get ids-by-title (title-key title))))
       (remove #{self-id})
       distinct
       vec))

(defn- note-links
  "The outgoing `:links` and incoming `:backlinks` of `row` among `rows`, both
  as note-id strings. `rows` are every stored note."
  [rows row]
  (let [ids-by-title (into {} (map (juxt (comp title-key :title) :id)) rows)
        ids-of (fn [note]
                 (resolved-link-ids ids-by-title (:id note)
                                    (link-titles (:content note))))
        id (:id row)]
    {:links     (mapv str (ids-of row))
     :backlinks (->> rows
                     (remove #(= id (:id %)))
                     (filter #(some #{id} (ids-of %)))
                     (mapv (comp str :id)))}))

(defn ^:no-doc note-detail
  "The `NoteDetail` shape for `row`: its public fields plus the links its
  content references and the notes that reference it, both resolved through
  `rows`, every stored note."
  {:malli/schema [:-> [:sequential :map] :map #'NoteDetail]}
  [rows row]
  (merge (row->note row) (note-links rows row)))

;; ---------------------------------------------------------------------------
;; Note SQL
;; ---------------------------------------------------------------------------

(def ^:private note-columns
  "The column list shared by every note query."
  "id, title, content, created_at, updated_at")

(def ^:private select-note-columns
  "A SELECT of the note columns, before any WHERE clause."
  (str "SELECT " note-columns " FROM note"))

(def ^:private list-notes-sql
  "Every note, most recently updated first."
  (str select-note-columns " ORDER BY updated_at DESC, id DESC"))

(def ^:private link-scan-sql
  "Every note, ordered by id so link resolution and the backlinks built from it
  are deterministic. The scan carries the timestamps as well, so [[load-note]]
  can read the whole note from it."
  (str select-note-columns " ORDER BY id"))

(def ^:private title-taken-sql
  "The id of another note that already has the title, compared the way links
  resolve titles."
  "SELECT id FROM note WHERE lower(trim(title)) = lower(trim(?)) AND id <> ?")

(def ^:private insert-note-sql
  "Insert a note and return its row."
  (str "INSERT INTO note (title, content)
        VALUES (?, ?)
        RETURNING " note-columns))

(def ^:private update-note-sql
  "Replace a note's title and content and return its row."
  (str "UPDATE note
           SET title = ?, content = ?, updated_at = now()
         WHERE id = ?
        RETURNING " note-columns))

(def ^:private delete-note-sql
  "Delete one note."
  "DELETE FROM note WHERE id = ?")

;; ---------------------------------------------------------------------------
;; Search SQL
;; ---------------------------------------------------------------------------

(def ^:private bm25-search-sql
  "BM25-ranked matches for a query, best match first."
  ;; `<@>` scores against the BM25 index and returns negative scores, so
  ;; ascending order puts the best match first; matches score below zero and
  ;; the rest are filtered out. `to_bm25query` takes the index by name because
  ;; the query text is a bound parameter rather than a literal.
  "SELECT id, title, content, created_at, updated_at
     FROM note
    WHERE content <@> to_bm25query(?, 'note_content_bm25_idx') < 0
    ORDER BY content <@> to_bm25query(?, 'note_content_bm25_idx')
    LIMIT ?")

(def ^:private substring-search-sql
  "The no-extension fallback: notes whose content contains the query."
  ;; The fallback when pg_textsearch is not installed: a case-insensitive
  ;; containment test, with no relevance ranking.
  "SELECT id, title, content, created_at, updated_at
     FROM note
    WHERE position(lower(?) in lower(content)) > 0
    ORDER BY updated_at DESC, id DESC
    LIMIT ?")

;; ---------------------------------------------------------------------------
;; Search mode
;; ---------------------------------------------------------------------------

(defonce ^:private bm25-datasources
  ;; Datasources whose database has the pg_textsearch extension, so searching
  ;; does not probe the catalogs on every call. Only positive answers are
  ;; cached: a server that gains the extension later is picked up on the next
  ;; search.
  (atom #{}))

(defn- bm25?
  "True when `datasource`'s database has the pg_textsearch extension, so
  [[bm25-search-sql]] can be used instead of [[substring-search-sql]]."
  {:malli/schema [:-> [:fn #(instance? javax.sql.DataSource %)] boolean?]}
  [datasource]
  (or (contains? @bm25-datasources datasource)
      (boolean
       (when (postgres/exec-one! datasource ::bm25-extension
                                 "SELECT 1 AS one FROM pg_extension WHERE extname = 'pg_textsearch'"
                                 [])
         (swap! bm25-datasources conj datasource)
         true))))

;; ---------------------------------------------------------------------------
;; Note rows
;; ---------------------------------------------------------------------------

(defn- all-note-rows
  "Every stored note, the rows link resolution works from."
  {:malli/schema [:-> [:fn #(instance? javax.sql.DataSource %)] [:vector :map]]}
  [datasource]
  (postgres/exec! datasource ::link-scan link-scan-sql []))

(defn- ensure-title-free!
  "Throw when another note already has `title`, compared the way links resolve
  titles. `note-id` is the note being updated, or nil when creating one."
  {:malli/schema [:-> [:fn #(instance? javax.sql.DataSource %)]
                  non-empty-string [:maybe pos-int?] :nil]}
  [datasource title note-id]
  (when-let [row (postgres/exec-one! datasource ::title-taken title-taken-sql
                                     [title (or note-id 0)])]
    (throw (ex-info (str "A note titled " (pr-str title) " already exists")
                    {:title title :noteId (str (:id row))})))
  nil)

(defn- saved-detail
  "The `NoteDetail` of the freshly written `row`, resolved against the stored
  notes."
  {:malli/schema [:-> [:fn #(instance? javax.sql.DataSource %)] :map
                  #'NoteDetail]}
  [datasource row]
  (note-detail (all-note-rows datasource) row))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn list-notes
  "Return every stored note, most recently updated first."
  {:malli/schema [:-> #'postgres/client-schema [:vector #'Note]]}
  [client]
  (mapv row->note
        (postgres/exec! (:datasource (postgres/ready client)) ::list-notes
                        list-notes-sql [])))

(defn load-note
  "Return the note named by `note-id` with its links and backlinks, or nil when
  no such note is stored."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  [:maybe #'NoteDetail]]}
  [client note-id]
  (when-let [id (row-key note-id)]
    (let [rows (all-note-rows (:datasource (postgres/ready client)))]
      (when-let [row (first (filter #(= id (:id %)) rows))]
        (note-detail rows row)))))

(defn create-note
  "Store a new note and return it with its links and backlinks.

  `note` is validated with [[NoteInput]]. Its title must not match another
  note's title, because `[[Title]]` references in other notes address a note by
  its title."
  {:malli/schema [:-> #'postgres/client-schema #'NoteInput #'NoteDetail]}
  [client note]
  (let [note       (prepare-note note)
        datasource (:datasource (postgres/ready client))]
    (ensure-title-free! datasource (:title note) nil)
    (let [row   (postgres/exec-one! datasource ::create-note insert-note-sql
                                    [(:title note) (:content note)])
          saved (saved-detail datasource row)]
      (tel/log! {:level :debug
                 :id    ::note-created
                 :data  {:noteId (:noteId saved)
                         :title  (:title saved)
                         :links  (count (:links saved))}}
                "Created PostgreSQL note")
      saved)))

(defn update-note
  "Replace the title and content of `note-id`, returning the updated note, or
  nil when no such note is stored.

  `note` is validated with [[NoteInput]]. The note's links are whatever its new
  content references with `[[Title]]`, and its title must not match another
  note's title."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  #'NoteInput [:maybe #'NoteDetail]]}
  [client note-id note]
  (let [note (prepare-note note)]
    (when-let [id (row-key note-id)]
      (let [datasource (:datasource (postgres/ready client))]
        (ensure-title-free! datasource (:title note) id)
        (when-let [row (postgres/exec-one! datasource ::update-note
                                           update-note-sql
                                           [(:title note) (:content note) id])]
          (let [updated (saved-detail datasource row)]
            (tel/log! {:level :debug
                       :id    ::note-updated
                       :data  {:noteId (:noteId updated)
                               :links  (count (:links updated))}}
                      "Updated PostgreSQL note")
            updated))))))

(defn delete-note
  "Delete `note-id`. Returns true when a note was removed."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string boolean?]}
  [client note-id]
  (let [deleted? (boolean
                  (when-let [id (row-key note-id)]
                    (pos? (postgres/updated-count
                           (postgres/exec-one! (:datasource (postgres/ready client))
                                               ::delete-note
                                               delete-note-sql [id])))))]
    (tel/log! {:level :debug
               :id    ::note-deleted
               :data  {:noteId note-id :deleted? deleted?}}
              "Deleted PostgreSQL note")
    deleted?))

(defn search-notes
  "Search the stored notes and return up to `:limit` matches, most relevant
  first.

  `:q` is the query text. With the `pg_textsearch` extension installed the
  matches are BM25-ranked (via the `note_content_bm25_idx` index over `content`);
  otherwise they are the notes whose content contains `:q`, case-insensitively,
  most recently updated first. A blank query is rejected."
  {:malli/schema [:-> #'postgres/client-schema #'SearchInput [:vector #'Note]]}
  [client {:keys [q limit]}]
  (let [datasource (:datasource (postgres/ready client))
        q          (str/trim (str q))
        limit      (or limit default-search-limit)
        _          (postgres/validate! SearchInput {:q q :limit limit}
                                       "note search")
        rows       (if (bm25? datasource)
                     (postgres/exec! datasource ::search-bm25 bm25-search-sql
                                     [q q limit])
                     (postgres/exec! datasource ::search-substring
                                     substring-search-sql [q limit]))]
    (mapv row->note rows)))
