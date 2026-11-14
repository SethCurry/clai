(ns clai.daemon.postgres.notes-test
  "Tests for note storage and search.

  The unit tests exercise the pure helpers; the round trip only runs when
  `CLAI_TEST_POSTGRES_URL` points at a disposable database."
  (:require [clai.daemon.postgres :as pg]
            [clai.daemon.postgres.notes :as notes]
            [clai.test-support :refer [instrument quiet-logs]]
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
  (is (= {:title "Title" :content "Body" :links [7 8]}
         (notes/prepare-note {:title "Title"
                              :content "Body"
                              :links ["7" 8 7]})))
  (is (= {:title "Title" :content "Body"}
         (notes/prepare-note {:title "Title" :content "Body" :links nil})))
  (is (= {:title "Title" :content "Body"}
         (notes/prepare-note {:title "Title" :content "Body" :noteId "9"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "   " :content "Body"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "Title" :content ""})))
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "Title"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "Title" :content "Body" :links "7"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (notes/prepare-note {:title "Title"
                                    :content "Body"
                                    :links ["not-a-row"]}))))

(deftest rows-convert-to-public-shapes
  (is (= {:noteId "3" :title "Title" :content "Body"}
         (notes/row->note {:id 3 :title "Title" :content "Body"}))))

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

          (is (= note-id (:noteId (notes/load-note client note-id))))
          (is (nil? (notes/load-note client "not-a-row")))
          (is (some #(= note-id (:noteId %)) (notes/list-notes client)))

          ;; A note's links are directional: the linked note only sees a
          ;; backlink.
          (let [linking (notes/create-note client {:title   (unique-title "linker")
                                                   :content "This one points at the ranking note."
                                                   :links   [note-id]})]
            (try
              (is (= [note-id] (:links linking)))
              (is (= [] (:backlinks linking)))
              (is (= [(:noteId linking)] (:backlinks (notes/load-note client note-id))))

              ;; A link to a note that does not exist is rejected, and the
              ;; whole update is rolled back.
              (is (thrown? clojure.lang.ExceptionInfo
                           (notes/update-note client note-id
                                              {:title   title
                                               :content "replaced"
                                               :links   ["999999999"]})))
              (is (= "Notes are ranked by BM25."
                     (:content (notes/load-note client note-id))))
              (finally
                (notes/delete-note client (:noteId linking))))

            ;; Updating without :links drops the links the note used to have.
            (is (= [] (:backlinks (notes/load-note client note-id))))
            (is (= [note-id] (:links (notes/update-note client note-id
                                                        {:title   title
                                                         :content "Notes are ranked by BM25."
                                                         :links   [note-id]})))))

          (is (some #(= note-id (:noteId %))
                    (notes/search-notes client {:q "BM25"})))
          (is (= [] (notes/search-notes client {:q "aardvarks"})))
          (is (thrown? clojure.lang.ExceptionInfo
                       (notes/search-notes client {:q "   "}))))
        (finally
          (notes/delete-note client (:noteId note)))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
