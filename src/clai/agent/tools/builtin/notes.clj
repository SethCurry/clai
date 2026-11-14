(ns clai.agent.tools.builtin.notes
  "Built-in tools for the notes stored in PostgreSQL.

  Notes are first-class documents, not session history: a titled note with a
  mandatory title and content, optionally linking to other notes
  directionally (see `clai.daemon.postgres.notes`). The tools let an agent work
  with the same store the web Notes page does:

    notes_list    every note, most recently updated first
    notes_search  BM25 (or substring) search over note content
    notes_read    one note with its content, links, and backlinks
    notes_update  change a note's title, content, or outgoing links
    notes_delete  remove a note and the links pointing at it

  The tools call `clai.daemon.postgres.notes` directly with the session's
  `:postgres` client from the tool context, so there is no HTTP hop and no
  dependency on the daemon running. A context without a client -- a tool run
  in a REPL without storage, say -- reports a clear error instead of a
  NullPointerException.

  `notes_update` is a partial update: storage replaces a whole note, so the
  tool reads the stored note first and merges the given fields over it. A
  model can therefore change a title without resending the content, which
  would otherwise cost a round trip through `notes_read` and risks dropping
  text."
  (:require [clai.agent.tools.builtin.common :as common]
            [clai.agent.tools.schema :as schema]
            [clai.daemon.postgres.notes :as notes]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Limits
;; ---------------------------------------------------------------------------

(def ^:private max-snippet-chars
  "How much of a note's content a list or search result shows, so a page of
  results stays small enough to read at a glance."
  200)

(def ^:private max-content-chars
  "How much of a note's content `notes_read` returns. A note is a document the
  model asked for by id, so it is usually returned whole; the cap only stops
  one unusually long note from flooding the session history."
  20000)

(def ^:private default-search-limit
  "The number of notes `notes_search` returns when `limit` is not given,
  matching `clai.daemon.postgres.notes/search-notes`."
  20)

(def ^:private max-search-limit
  "The most notes `notes_search` will return, however large a `limit` the model
  asks for."
  100)

;; ---------------------------------------------------------------------------
;; Argument normalization
;; ---------------------------------------------------------------------------

(defn- notes-client
  "The PostgreSQL client from tool context `ctx`, throwing when the session has
  no note storage attached."
  {:malli/schema [:-> [:maybe [:map]] :any]}
  [ctx]
  (or (:postgres ctx)
      (throw (ex-info (str "Notes are stored in PostgreSQL, but this session "
                           "has no `:postgres` client.")
                      {:sessionId (:session-id ctx)}))))

(defn- note-id
  "Coerce a tool argument to a note id string, throwing when it is blank. Note
  ids are exposed as strings, but a model may send the digits as a JSON
  number, so both are accepted."
  {:malli/schema [:-> :any #'notes/non-empty-string]}
  [value]
  (let [id (some-> value str str/trim)]
    (when (str/blank? id)
      (throw (ex-info (str "A note `id` is required; take one from notes_list "
                           "or notes_search.")
                      {:value value})))
    id))

(defn- positive-int
  "Coerce `value` to a positive integer, or nil when it is missing or not one.
  JSON numbers arrive as numbers and JSON strings as strings, so both are
  accepted."
  {:malli/schema [:-> :any [:maybe pos-int?]]}
  [value]
  (let [n (cond
            (number? value) (long value)
            (string? value) (parse-long (str/trim value))
            :else nil)]
    (when (and n (pos? n)) n)))

(defn- link-ids
  "Normalize the `links` tool argument to a vector of note ids. A single id is
  wrapped, since a model may send `\"links\": \"7\"` instead of an array, and
  strings are trimmed so a padded id still names its note."
  {:malli/schema [:-> :any [:vector :any]]}
  [links]
  (mapv (fn [link] (if (string? link) (str/trim link) link))
        (cond
          (nil? links)        []
          (sequential? links) links
          :else               [links])))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn- collapse-whitespace
  "Collapse every run of whitespace in `text` to one space, so a content
  preview stays on one line."
  {:malli/schema [:-> :any :string]}
  [text]
  (-> (str text) (str/replace #"\s+" " ") str/trim))

(defn- snippet
  "A one-line preview of note `content`: whitespace collapsed and cut to
  [[max-snippet-chars]] characters."
  {:malli/schema [:-> :any :string]}
  [content]
  (common/truncate (collapse-whitespace content) max-snippet-chars))

(defn- note-summary
  "Render one note as a bullet for `notes_list` and `notes_search`: its id,
  title, update time, and a one-line content preview."
  {:malli/schema [:-> #'notes/Note :string]}
  [{:keys [noteId title content updatedAt]}]
  (str "- " noteId ": " title
       (when-let [at (common/format-timestamp updatedAt)]
         (str " (updated " at ")"))
       (let [preview (snippet content)]
         (when-not (str/blank? preview)
           (str "\n  " preview)))))

(defn- ids-text
  "Render note ids as a comma-separated list, or `(none)` when there are
  none."
  {:malli/schema [:-> [:sequential :string] :string]}
  [ids]
  (if (seq ids) (str/join ", " ids) "(none)"))

(defn- note-detail-text
  "Render a note for `notes_read` and `notes_update`: its title and id, its
  timestamps, its outgoing links and incoming backlinks, and its content."
  {:malli/schema [:-> #'notes/NoteDetail :string]}
  [{:keys [noteId title content links backlinks createdAt updatedAt]}]
  (let [content (str content)]
    (str "Note " noteId ": " title
         "\nupdated: " (or (common/format-timestamp updatedAt) "unknown")
         " | created: " (or (common/format-timestamp createdAt) "unknown")
         "\nlinks: " (ids-text links)
         " | backlinks: " (ids-text backlinks)
         "\n\n"
         (if (> (count content) max-content-chars)
           (str (subs content 0 max-content-chars)
                "\n… [truncated at " max-content-chars " characters]")
           content))))

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn list-notes-fn
  "Tool: list every stored note, most recently updated first.

  Each line has the note's id, title, update time, and a short content
  preview. The ids are what `notes_read`, `notes_update`, and `notes_delete`
  take."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [_args ctx]
  (let [stored (notes/list-notes (notes-client ctx))]
    (if (empty? stored)
      "No notes are stored."
      (str "Found " (count stored) " note(s), most recently updated first:"
           "\n\n"
           (str/join "\n" (map note-summary stored))))))

(defn search-notes-fn
  "Tool: search the stored notes by their text.

  With the `pg_textsearch` extension installed matches are BM25-ranked;
  otherwise they are the notes whose content contains `query`,
  case-insensitively. `limit` defaults to [[default-search-limit]] and is
  capped at [[max-search-limit]]."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [query limit]} ctx]
  (let [q (str/trim (str query))]
    (when (str/blank? q)
      (throw (ex-info "Searching notes requires a `query`." {:query query})))
    (let [n     (min max-search-limit (or (positive-int limit)
                                         default-search-limit))
          found (notes/search-notes (notes-client ctx) {:q q :limit n})]
      (if (empty? found)
        (str "No notes match \"" q "\".")
        (str "Found " (count found) " note(s) matching \"" q "\", most "
             "relevant first:\n\n"
             (str/join "\n" (map note-summary found)))))))

(defn read-note-fn
  "Tool: read one stored note by `id`, returning its title, content, creation
  and update times, the notes it links to, and the notes that link to it."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [id]} ctx]
  (let [id (note-id id)]
    (if-let [note (notes/load-note (notes-client ctx) id)]
      (note-detail-text note)
      (str "No note " id " is stored."))))

(defn update-note-fn
  "Tool: change the title, content, or outgoing links of stored note `id`.

  Fields that are not given keep their stored value: the note is read first
  and the changes merged over it, because storage replaces a whole note.
  `links` replaces the note's outgoing links, and an empty array removes them.
  Returns the updated note."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [id] :as args} ctx]
  (let [id      (note-id id)
        changes (cond-> {}
                  (contains? args :title)   (assoc :title (:title args))
                  (contains? args :content) (assoc :content (:content args))
                  (contains? args :links)   (assoc :links (link-ids (:links args))))]
    (when (empty? changes)
      (throw (ex-info (str "Updating note " id " requires at least one of "
                           "`title`, `content`, or `links`.")
                      {:id id})))
    (let [client (notes-client ctx)]
      (if-let [current (notes/load-note client id)]
        (let [updated (notes/update-note
                       client id
                       (merge (select-keys current [:title :content :links])
                              changes))]
          (str "Updated note " id ".\n\n" (note-detail-text updated)))
        (str "No note " id " is stored.")))))

(defn delete-note-fn
  "Tool: delete stored note `id` and the links that point at it. Reports
  whether a note was removed."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [id]} ctx]
  (let [id (note-id id)]
    (if (notes/delete-note (notes-client ctx) id)
      (str "Deleted note " id ".")
      (str "No note " id " is stored."))))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def ^:private id-property
  "The JSON-schema `id` argument shared by the tools that name one note."
  {:type "string"
   :description (str "Note id from notes_list or notes_search. Ids are "
                     "strings, but the digits as a number work too.")})

(def ^:private notes-list-spec
  {:type "function"
   :function
   {:name "notes_list"
    :description
    (str "List every note stored in PostgreSQL, most recently updated first, "
         "with each note's id, title, update time, and a short content "
         "preview. Use it to find the id that notes_read, notes_update, and "
         "notes_delete need.")
    :parameters {:type "object" :properties {} :required []}}})

(def ^:private notes-search-spec
  {:type "function"
   :function
   {:name "notes_search"
    :description
    (str "Search the notes stored in PostgreSQL by their text. Matching is "
         "BM25-ranked when the server has the pg_textsearch extension and "
         "otherwise the notes whose content contains the query, so pass a few "
         "distinctive words rather than a sentence. Returns each match's id, "
         "title, update time, and a preview; use notes_read for the whole "
         "note.")
    :parameters
    {:type "object"
     :properties
     {"query" {:type "string"
               :description "Text to search the notes for."}
      "limit" {:type "integer"
               :description (str "How many matches to return (default "
                                 default-search-limit ", max "
                                 max-search-limit ").")}}
     :required ["query"]}}})

(def ^:private notes-read-spec
  {:type "function"
   :function
   {:name "notes_read"
    :description
    (str "Read one stored note by id: its title, content, creation and update "
         "times, the notes it links to, and the notes that link to it. Note "
         "content longer than " max-content-chars " characters is truncated.")
    :parameters
    {:type "object"
     :properties {"id" id-property}
     :required ["id"]}}})

(def ^:private notes-update-spec
  {:type "function"
   :function
   {:name "notes_update"
    :description
    (str "Change a stored note. Give the note id and any of title, content, "
         "and links; fields you leave out keep their stored value, so this is "
         "a partial update rather than a replacement. `links` is the array of "
         "note ids the note should link to and replaces the existing links, "
         "so an empty array removes them. Returns the updated note; read it "
         "with notes_read first so you preserve the text you are not "
         "changing.")
    :parameters
    {:type "object"
     :properties
     {"id"      id-property
      "title"   {:type "string"
                 :description "New note title."}
      "content" {:type "string"
                 :description "New note body in Markdown."}
      "links"   {:type "array"
                 :items {:type "string"}
                 :description (str "Ids of the notes this note should link "
                                   "to; replaces the existing outgoing links, "
                                   "and [] removes them.")}}
     :required ["id"]}}})

(def ^:private notes-delete-spec
  {:type "function"
   :function
   {:name "notes_delete"
    :description
    (str "Delete a stored note by id, along with the links that point at it. "
         "This cannot be undone; use notes_read first if you are unsure which "
         "note it is.")
    :parameters
    {:type "object"
     :properties {"id" id-property}
     :required ["id"]}}})

(defn tools
  "Return the built-in notes tool maps."
  {:malli/schema [:-> [:vector #'schema/Tool]]}
  []
  [{:id "notes_list"   :handler list-notes-fn   :spec notes-list-spec}
   {:id "notes_search" :handler search-notes-fn :spec notes-search-spec}
   {:id "notes_read"   :handler read-note-fn     :spec notes-read-spec}
   {:id "notes_update" :handler update-note-fn   :spec notes-update-spec}
   {:id "notes_delete" :handler delete-note-fn   :spec notes-delete-spec}])
