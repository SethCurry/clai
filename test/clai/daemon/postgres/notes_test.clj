(ns clai.daemon.postgres.notes-test
  "Tests for note storage and search.

  The unit tests exercise the pure helpers; the round trip only runs when
  `CLAI_TEST_POSTGRES_URL` points at a disposable database."
  (:require [clai.daemon.postgres :as pg]
            [clai.daemon.postgres.notes :as notes]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.util UUID]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(defn- unique-title
  "A note title that will not collide with another test run."
  [prefix]
  (str prefix "-" (UUID/randomUUID)))

;; ---------------------------------------------------------------------------
;; Input normalization and row conversion
;; ---------------------------------------------------------------------------

(deftest row-keys-must-name-a-row
  (is (= 12 (notes/row-key 12)))
  (is (= 12 (notes/row-key "12")))
  (is (nil? (notes/row-key "0")))
  (is (nil? (notes/row-key "-1")))
  (is (nil? (notes/row-key "not-a-row")))
  (is (nil? (notes/row-key nil))))

(deftest prepare-note-trims-and-validates
  (is (= {:title "Title" :content "Body"}
         (notes/prepare-note {:title "  Title  " :content "  Body  "})))
  (is (= {:title "Title" :content "Body"}
         (notes/prepare-note {:title "Title" :content "Body" :noteId "9"})))
  (is (= {:title "Title" :content "Body"}
         (notes/prepare-note {:title "Title" :content "Body" :links ["7"]}))
      "a leftover links field is ignored, not stored")
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "   " :content "Body"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "Title" :content ""})))
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "Title"}))))

(deftest rows-convert-to-public-shapes
  (is (= {:noteId "3" :title "Title" :content "Body"}
         (notes/row->note {:id 3 :title "Title" :content "Body"}))))

;; ---------------------------------------------------------------------------
;; Links
;; ---------------------------------------------------------------------------

(deftest link-titles-parses-the-bracket-syntax
  (is (= ["BM25 ranking" "Links"]
         (notes/link-titles
          "See [[BM25 ranking]], then [[ Links ]] and [[BM25 ranking]] again.")))
  (is (= [] (notes/link-titles "No links here, just [array] and [text](url).")))
  (is (= [] (notes/link-titles "A stray [[bracket.")))
  (is (= [] (notes/link-titles "A blank [[]] reference.")))
  (is (= [] (notes/link-titles "A reference across
lines [[is not
a link]].")))
  (is (= [] (notes/link-titles nil))))

(deftest note-detail-resolves-both-link-directions
  (let [rows [{:id 1 :title "BM25" :content "Notes are ranked by BM25."}
              {:id 2 :title "Links"
               :content "This note points at [[bm25]] and [[Missing]]."}
              {:id 3 :title "Self" :content "This note links to [[Self]]."}]]
    (is (= {:links [] :backlinks ["2"]}
           (select-keys (notes/note-detail rows (first rows))
                        [:links :backlinks])))
    (is (= {:links ["1"] :backlinks []}
           (select-keys (notes/note-detail rows (second rows))
                        [:links :backlinks]))
        "a title resolves ignoring case, and an unknown title is dropped")
    (is (= {:links [] :backlinks []}
           (select-keys (notes/note-detail rows (nth rows 2))
                        [:links :backlinks]))
        "a note's reference to itself is not a link")))

;; ---------------------------------------------------------------------------
;; Optional integration test
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.daemon.postgres.notes-test

(deftest note-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client (pg/open {:url url})
          title  (unique-title "clai-test")
          note   (notes/create-note client {:title   (str "  " title "  ")
                                            :content "Notes are ranked by BM25."})]
      (try
        (let [note-id (:noteId note)]
          (is (string? note-id))
          (is (= title (:title note)))
          (is (= "Notes are ranked by BM25." (:content note)))
          (is (= [] (:links note)))
          (is (= [] (:backlinks note)))
          (is (some? (:createdAt note)))

          (is (= [note-id (:createdAt note)]
                 ((juxt :noteId :createdAt) (notes/load-note client note-id)))
              "loading a note keeps its timestamps")
          (is (nil? (notes/load-note client "not-a-row")))
          (is (some #(= note-id (:noteId %)) (notes/list-notes client)))

          ;; A title is how notes address each other, so it cannot be reused,
          ;; in any case.
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"already exists"
               (notes/create-note client {:title   (str/upper-case title)
                                          :content "A duplicate title."})))

          ;; A link is written in the content and read back out of it.
          (let [linker (notes/create-note
                        client {:title   (unique-title "linker")
                                :content (str "This one points at [[" title "]].")})]
            (try
              (is (= [note-id] (:links linker)))
              (is (= [] (:backlinks linker)))
              (is (= [(:noteId linker)]
                     (:backlinks (notes/load-note client note-id))))

              ;; Renaming onto a stored title is rejected too.
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo #"already exists"
                   (notes/update-note client (:noteId linker)
                                      {:title   title
                                       :content "Renamed onto another note."})))

              ;; Removing the reference removes the link.
              (is (= [] (:links (notes/update-note
                                 client (:noteId linker)
                                 {:title   (:title linker)
                                  :content "No links now."}))))
              (is (= [] (:backlinks (notes/load-note client note-id))))
              (finally
                (notes/delete-note client (:noteId linker)))))

          (is (some #(= note-id (:noteId %))
                    (notes/search-notes client {:q "BM25"})))
          (is (= [] (notes/search-notes client {:q "aardvarks"})))
          (is (thrown? clojure.lang.ExceptionInfo
                       (notes/search-notes client {:q "   "}))))
        (finally
          (notes/delete-note client (:noteId note)))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
