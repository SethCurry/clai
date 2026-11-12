(ns clai.agent.configs-test
  "Tests for the pure parts of `clai.agent.configs`: configuration inputs are
  normalized before storage and database rows become public configuration maps
  that only contain the overrides they name."
  (:require [clai.agent.configs :as configs]
            [clai.daemon.postgres :as pg]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(deftest config-key-accepts-database-ids
  (is (= 12 (configs/config-key 12)))
  (is (= 12 (configs/config-key "12")))
  (is (nil? (configs/config-key "nope")))
  (is (nil? (configs/config-key "0")))
  (is (nil? (configs/config-key nil))))

(deftest row->config-omits-null-overrides
  (is (= {:configId "7" :name "fast"}
         (configs/row->config {:id 7 :name "fast"}))
      "NULL columns fall back to the agent's own configuration"))

(deftest row->config-converts-every-override
  (let [row {:id                7
             :name              "fast"
             :model             "gemma3"
             :system-prompt     "Be quick."
             :max-tool-rounds   2
             :think             true
             :model-options     "{\"temperature\":0}"
             :response-format   "\"json\""
             :tools             (into-array String ["read_file" "run_command"])
             :created-at        "2025-01-01T00:00:00Z"
             :updated-at        "2025-01-02T00:00:00Z"}]
    (is (= {:configId      "7"
            :name          "fast"
            :model         "gemma3"
            :system-prompt "Be quick."
            :max-tool-rounds 2
            :think         true
            :options       {:temperature 0}
            :format        "json"
            :tools         ["read_file" "run_command"]
            :createdAt     "2025-01-01T00:00:00Z"
            :updatedAt     "2025-01-02T00:00:00Z"}
           (configs/row->config row)))))

(deftest prepare-config-normalizes-input
  (is (= {:name    "fast"
          :model   nil
          :think   false
          :options {}
          :tools   ["read_file" "run_command"]}
         (configs/prepare-config {:name    "  fast  "
                                  :model   "   "
                                  :think   false
                                  :options nil
                                  :tools   [" read_file " "run_command"]})))
  (is (= [] (:tools (configs/prepare-config {:name "no-tools" :tools []})))
      "an empty tool list stays distinct from clearing the override")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"agent configuration"
                        (configs/prepare-config {:name "   "}))))

;; ---------------------------------------------------------------------------
;; Optional integration test
;; ---------------------------------------------------------------------------
;;
;; These tests only run when CLAI_TEST_POSTGRES_URL points at a disposable
;; database. Run them with, for example:
;;
;;   CLAI_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/clai \
;;     lein test clai.agent.configs-test

(deftest configuration-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client (pg/open {:url url})
          name   (str "test-" (random-uuid))]
      (try
        (let [created (configs/create-config client
                                             {:name          name
                                              :model         "tiny"
                                              :system-prompt "Be quick."
                                              :max-tool-rounds 2
                                              :think         true
                                              :options       {:temperature 0}
                                              :format        "json"
                                              :tools         ["read_file"]})]
          (is (string? (:configId created)))
          (is (= name (:name created)))
          (is (= {:temperature 0} (:options created)))
          (is (= ["read_file"] (:tools created)))
          (is (= created (configs/load-config client (:configId created))))
          (is (= created (configs/select-config client name)))

          (let [updated (configs/update-config client (:configId created)
                                               {:name  name
                                                :model "large"
                                                :tools []})]
            (is (= "large" (:model updated)))
            (is (= [] (:tools updated)))
            (is (nil? (:system-prompt updated))
                "a full update clears overrides the new configuration omits"))

          (is (true? (configs/delete-config client (:configId created))))
          (is (nil? (configs/load-config client (:configId created))))
          (is (false? (configs/delete-config client (:configId created)))))
        (finally
          (when-let [stored (configs/select-config client name)]
            (configs/delete-config client (:configId stored))))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
