(ns clai.daemon.postgres.notes
  "Note storage on PostgreSQL for `clai`'s daemon.

  The tables are declared in `schema/schema.sql` and created by
  `clai.daemon.postgres/ensure-schema!`:

      note       id, title, content, created_at, updated_at
      note_link  from_note_id, to_note_id, created_at

  A note is a titled document; `title` and `content` are both mandatory and a
  blank one is rejected. Notes link to each other directionally: a `note_link`
  row points from `from_note_id` to `to_note_id`, so [[load-note]] returns both
  the note's outgoing `:links` and its incoming `:backlinks`. Deleting a note
  deletes the links that point at it (`ON DELETE CASCADE`).

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

      (notes/create-note client {:title   \"Links\"
                                 :content \"This note points at BM25.\"
                                 :links   [(:noteId ranking)]})

      (notes/search-notes client {:q \"ranked\"})
      (notes/load-note client (:noteId ranking))"
  (:require [clai.daemon.postgres :as postgres]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  postgres/non-empty-string)

(def NoteRef
  "A reference to a `note` row: its database id as a number or as a string of
  digits."
  [:or pos-int? [:re #"^[1-9][0-9]*$"]])

(def NoteInput
  "A note accepted by [[create-note]] and [[update-note]]. `:title` and
  `:content` are mandatory, and the optional `:links` vector names the notes
  this one links to (they replace any existing outgoing links)."
  [:map
   [:title non-empty-string]
   [:content non-empty-string]
   [:links {:optional true} [:vector NoteRef]]])

(def Note
  "A `note` row, as returned by [[list-notes]] and [[search-notes]]."
  [:map
   [:noteId non-empty-string]
   [:title non-empty-string]
   [:content non-empty-string]
   [:createdAt {:optional true} [:maybe :string]]
   [:updatedAt {:optional true} [:maybe :string]]])

(def NoteDetail
  "A `note` row with its directional links, as returned by [[load-note]],
  [[create-note]], and [[update-note]]. `:links` are the ids this note links
  to, `:backlinks` the ids of the notes that link to it."
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

(defn- link-keys
  "The distinct `note.id` keys named by `links`, rejecting ids that name no
  row."
  {:malli/schema [:-> [:maybe [:sequential NoteRef]] [:vector pos-int?]]}
  [links]
  (->> (or links [])
       (mapv (fn [link]
               (or (row-key link)
                   (throw (ex-info (str "Invalid note link: " (pr-str link))
                                   {:value link})))))
       distinct
       vec))

(defn ^:no-doc prepare-note
  "Validate and normalize a note input for storage: the title and content are
  trimmed, a blank one is rejected, and `:links` becomes a vector of distinct
  numeric `note.id` keys."
  {:malli/schema [:-> :map #'NoteInput]}
  [note]
  ;; Only the note's own fields are considered, so a JSON body may carry extra
  ;; keys (such as the id from a PUT path) without failing validation.
  (let [note (cond-> (-> (select-keys note [:title :content :links])
                         (update :title blank->nil)
                         (update :content blank->nil))
               (nil? (:links note)) (dissoc :links))
        note (postgres/validate! NoteInput note "note")]
    (cond-> (dissoc note :links)
      (contains? note :links) (assoc :links (link-keys (:links note))))))

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
;; Note SQL
;; ---------------------------------------------------------------------------

(def ^:private note-columns
  "The column list shared by every note query."
  "id, title, content, created_at, updated_at")

(def ^:private select-note-columns
  "A SELECT of the note columns, before any WHERE clause."
  (str "SELECT " note-columns " FROM note"))

(def ^:private list-notes-sql
  (str select-note-columns " ORDER BY updated_at DESC, id DESC"))

(def ^:private load-note-sql
  (str select-note-columns " WHERE id = ?"))

(def ^:private insert-note-sql
  (str "INSERT INTO note (title, content)
        VALUES (?, ?)
        RETURNING " note-columns))

(def ^:private update-note-sql
  (str "UPDATE note
           SET title = ?, content = ?, updated_at = now()
         WHERE id = ?
        RETURNING " note-columns))

(def ^:private delete-note-sql
  "DELETE FROM note WHERE id = ?")

;; ---------------------------------------------------------------------------
;; Link SQL
;; ---------------------------------------------------------------------------

(def ^:private links-sql
  "SELECT to_note_id FROM note_link WHERE from_note_id = ? ORDER BY to_note_id")

(def ^:private backlinks-sql
  "SELECT from_note_id FROM note_link WHERE to_note_id = ? ORDER BY from_note_id")

(def ^:private delete-links-sql
  "DELETE FROM note_link WHERE from_note_id = ?")

(def ^:private insert-link-sql
  ;; Selecting from `note` rather than inserting the id directly makes an
  ;; unknown target return no row instead of raising a foreign key error.
  "INSERT INTO note_link (from_note_id, to_note_id)
   SELECT ?, id FROM note WHERE id = ?
   RETURNING to_note_id")

;; ---------------------------------------------------------------------------
;; Search SQL
;; ---------------------------------------------------------------------------

(def ^:private bm25-search-sql
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
  ;; The fallback when pg_textsearch is not installed: a case-insensitive
  ;; containment test, with no relevance ranking.
  "SELECT id, title, content, created_at, updated_at
     FROM note
    WHERE position(lower(?) in lower(content)) > 0
    ORDER BY updated_at DESC, id DESC
    LIMIT ?")

;; ---------------------------------------------------------------------------
;; Link helpers
;; ---------------------------------------------------------------------------

(defn- link-ids
  "The note ids in `rows` under `column`, as strings."
  [column rows]
  (mapv (comp str column) rows))

(defn- replace-links!
  "Make `note-id`'s outgoing links exactly `to-ids`: the existing links are
  deleted and each target is inserted. A target that names no note throws an
  `ex-info`, so a note cannot link to a missing note."
  {:malli/schema [:-> :any pos-int? [:maybe [:sequential pos-int?]] :nil]}
  [connectable note-id to-ids]
  (postgres/exec! connectable ::delete-links delete-links-sql [note-id])
  (doseq [to-id to-ids]
    (when-not (postgres/exec-one! connectable ::insert-link insert-link-sql
                                  [note-id to-id])
      (throw (ex-info (str "No note " to-id " to link to")
                      {:noteId (str note-id) :link (str to-id)}))))
  nil)

(defn- note-detail
  "The `NoteDetail` shape for `row`, reading its links and backlinks through
  `connectable`."
  {:malli/schema [:-> :any :map #'NoteDetail]}
  [connectable row]
  (assoc (row->note row)
         :links (link-ids :to-note-id
                          (postgres/exec! connectable ::links links-sql
                                          [(:id row)]))
         :backlinks (link-ids :from-note-id
                              (postgres/exec! connectable ::backlinks
                                              backlinks-sql [(:id row)]))))

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
  (let [datasource (:datasource (postgres/ready client))]
    (when-let [id (row-key note-id)]
      (when-let [row (postgres/exec-one! datasource ::load-note
                                         load-note-sql [id])]
        (note-detail datasource row)))))

(defn create-note
  "Store a new note and return it with its links.

  `note` is validated with [[NoteInput]]. The optional `:links` names the notes
  it links to; each must be a stored note."
  {:malli/schema [:-> #'postgres/client-schema #'NoteInput #'NoteDetail]}
  [client note]
  (let [note  (prepare-note note)
        saved (jdbc/with-transaction [tx (:datasource (postgres/ready client))]
                (let [row (postgres/exec-one! tx ::create-note insert-note-sql
                                              [(:title note) (:content note)])]
                  (replace-links! tx (:id row) (:links note))
                  (note-detail tx row)))]
    (tel/log! {:level :debug
               :id    ::note-created
               :data  {:noteId (:noteId saved)
                       :title  (:title saved)
                       :links  (count (:links saved))}}
              "Created PostgreSQL note")
    saved))

(defn update-note
  "Replace the title, content, and links of `note-id`, returning the updated
  note, or nil when no such note is stored.

  `note` is validated with [[NoteInput]]; because the whole note is replaced, a
  note without `:links` ends up with no outgoing links."
  {:malli/schema [:-> #'postgres/client-schema #'non-empty-string
                  #'NoteInput [:maybe #'NoteDetail]]}
  [client note-id note]
  (let [note (prepare-note note)]
    (when-let [id (row-key note-id)]
      (let [updated (jdbc/with-transaction [tx (:datasource (postgres/ready client))]
                      (when-let [row (postgres/exec-one! tx ::update-note
                                                         update-note-sql
                                                         [(:title note)
                                                          (:content note)
                                                          id])]
                        (replace-links! tx id (:links note))
                        (note-detail tx row)))]
        (when updated
          (tel/log! {:level :debug
                     :id    ::note-updated
                     :data  {:noteId (:noteId updated)
                             :links  (count (:links updated))}}
                    "Updated PostgreSQL note"))
        updated))))

(defn delete-note
  "Delete `note-id` and the links that point at it. Returns true when a note
  was removed."
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
