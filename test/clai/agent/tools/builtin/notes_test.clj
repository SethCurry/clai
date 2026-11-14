(ns clai.agent.tools.builtin.notes-test
  "Tests for the built-in notes tools.

  The storage calls are replaced with `with-redefs`, so no PostgreSQL server is
  needed: these tests cover argument handling and rendering, while
  `clai.daemon.postgres.notes-test` covers the storage itself."
  (:require [clai.agent.tools.builtin.notes :as builtin-notes]
            [clai.agent.tools.schema :as schema]
            [clai.daemon.postgres.notes :as notes]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [malli.core :as malli]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(def ^:private ctx
  "Tool context with a storage client the redefined storage functions ignore."
  {:postgres ::client})

(def ^:private tools-by-id
  "The notes tools keyed by id."
  (into {} (map (juxt :id identity)) (builtin-notes/tools)))

(defn- handler
  "Return the handler for notes tool `id`, or call it with `args` and `ctx`."
  ([id] (:handler (get tools-by-id id)))
  ([id args ctx] ((handler id) args ctx)))

(def ^:private ranking-note
  "A note as `list-notes` returns it."
  {:noteId    "12"
   :title     "BM25"
   :content   "Notes are ranked by BM25."
   :createdAt "2026-01-02T03:04:05.123456Z"
   :updatedAt "2026-01-02T04:05:06.654321Z"})

(def ^:private linking-note
  "A second note, so a listing has more than one entry."
  {:noteId "9" :title "Links" :content "This note points at BM25."})

(def ^:private ranking-detail
  "`ranking-note` as `load-note` returns it, with its links and backlinks."
  (assoc ranking-note :links ["9"] :backlinks ["7"]))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(deftest tools-are-well-formed
  (is (= ["notes_delete" "notes_list" "notes_read" "notes_search"
          "notes_update"]
         (sort (keys tools-by-id))))
  (doseq [tool (vals tools-by-id)]
    (is (malli/validate schema/tool-spec (:spec tool))
        (str (:id tool) " spec should be a valid tool definition"))))

;; ---------------------------------------------------------------------------
;; Listing and searching
;; ---------------------------------------------------------------------------

(deftest list-notes-renders-a-summary-per-note
  (with-redefs [notes/list-notes (fn [_client] [ranking-note linking-note])]
    (let [text (handler "notes_list" {} ctx)]
      (is (str/starts-with? text "Found 2 note(s)"))
      (is (str/includes? text "- 12: BM25 (updated 2026-01-02 04:05 UTC)"))
      (is (str/includes? text "  Notes are ranked by BM25."))
      (is (str/includes? text "- 9: Links")))))

(deftest list-notes-reports-an-empty-store
  (with-redefs [notes/list-notes (fn [_client] [])]
    (is (= "No notes are stored." (handler "notes_list" {} ctx)))))

(deftest search-notes-sends-the-trimmed-query-and-a-bounded-limit
  (let [seen (atom nil)]
    (with-redefs [notes/search-notes
                  (fn [_client opts]
                    (reset! seen opts)
                    [ranking-note])]
      (let [text (handler "notes_search" {:query "  ranked " :limit "500"} ctx)]
        (is (= {:q "ranked" :limit 100} @seen)
            "a query is trimmed and an oversized limit is capped")
        (is (str/includes? text "Found 1 note(s) matching \"ranked\""))
        (is (str/includes? text "- 12: BM25"))))
    (with-redefs [notes/search-notes
                  (fn [_client opts] (reset! seen opts) [])]
      (is (= "No notes match \"aardvarks\"."
             (handler "notes_search" {:query "aardvarks"} ctx)))
      (is (= {:q "aardvarks" :limit 20} @seen)
          "the storage default limit is sent explicitly"))))

(deftest search-notes-requires-a-query
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires a `query`"
                        (handler "notes_search" {:query "   "} ctx))))

;; ---------------------------------------------------------------------------
;; Reading
;; ---------------------------------------------------------------------------

(deftest read-notes-renders-the-note-with-its-links
  (with-redefs [notes/load-note
                (fn [_client id]
                  (when (= id "12") ranking-detail))]
    (let [text (handler "notes_read" {:id 12} ctx)]
      (is (str/includes? text "Note 12: BM25"))
      (is (str/includes? text "updated: 2026-01-02 04:05 UTC"))
      (is (str/includes? text "links: 9 | backlinks: 7"))
      (is (str/includes? text "Notes are ranked by BM25.")))
    (is (= "No note 99 is stored." (handler "notes_read" {:id "99"} ctx)))))

(deftest read-notes-truncates-a-very-long-note
  (with-redefs [notes/load-note
                (fn [_client _id]
                  (assoc ranking-detail :content (apply str (repeat 20001 "x"))))]
    (is (str/includes? (handler "notes_read" {:id "12"} ctx)
                       "[truncated at 20000 characters]"))))

(deftest notes-tools-require-an-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"`id` is required"
                        (handler "notes_read" {} ctx))))

;; ---------------------------------------------------------------------------
;; Updating and deleting
;; ---------------------------------------------------------------------------

(deftest update-notes-merges-the-change-over-the-stored-note
  (let [seen (atom nil)]
    (with-redefs [notes/load-note
                  (fn [_client _id] ranking-detail)
                  notes/update-note
                  (fn [_client id note]
                    (reset! seen [id note])
                    (assoc ranking-detail :title (:title note)))]
      (let [text (handler "notes_update" {:id "12" :title "BM25 ranking"} ctx)]
        (is (= ["12" {:title   "BM25 ranking"
                      :content "Notes are ranked by BM25."
                      :links   ["9"]}]
               @seen)
            "the fields that were not given keep their stored value")
        (is (str/starts-with? text "Updated note 12."))))))

(deftest update-notes-normalizes-links
  (let [seen (atom nil)]
    (with-redefs [notes/load-note
                  (fn [_client _id] ranking-detail)
                  notes/update-note
                  (fn [_client _id note]
                    (reset! seen note)
                    ranking-detail)]
      (handler "notes_update" {:id "12" :links "9"} ctx)
      (is (= ["9"] (:links @seen)) "a bare id is wrapped in a vector")
      (handler "notes_update" {:id "12" :links []} ctx)
      (is (= [] (:links @seen)) "an empty array removes the links"))))

(deftest update-notes-requires-a-field-to-change
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires at least one of"
                        (handler "notes_update" {:id "12"} ctx))))

(deftest update-notes-reports-a-missing-note
  (with-redefs [notes/load-note (fn [_client _id] nil)]
    (is (= "No note 99 is stored."
           (handler "notes_update" {:id "99" :content "new"} ctx)))))

(deftest delete-notes-reports-whether-a-note-was-removed
  (let [seen (atom nil)]
    (with-redefs [notes/delete-note
                  (fn [_client id]
                    (reset! seen id)
                    (= id "12"))]
      (is (= "Deleted note 12." (handler "notes_delete" {:id 12} ctx)))
      (is (= "12" @seen) "a numeric id is sent to storage as a string")
      (is (= "No note 99 is stored."
             (handler "notes_delete" {:id "99"} ctx))))))

;; ---------------------------------------------------------------------------
;; Missing storage
;; ---------------------------------------------------------------------------

(deftest notes-tools-explain-a-missing-postgres-client
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"no `:postgres` client"
                        (handler "notes_list" {} {}))))
