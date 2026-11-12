(ns clai.plugins.store-test
  "Tests for plugin configuration in PostgreSQL.

  The storage functions only need a live database when `CLAI_TEST_POSTGRES_URL`
  is set; otherwise each integration test reports a skip."
  (:require [clai.daemon.postgres :as pg]
            [clai.plugins.store :as store]
            [clai.test-support :refer [quiet-logs]]
            [clojure.test :refer :all])
  (:import [java.util UUID]))

(use-fixtures :once quiet-logs)

(defn- unique-name
  "A plugin name that cannot collide with another test run."
  []
  (str "test-plugin-" (UUID/randomUUID)))

(deftest plugin-round-trip
  (if-let [url (System/getenv "CLAI_TEST_POSTGRES_URL")]
    (let [client (pg/open {:url url})
          name   (unique-name)
          plugin (store/create-plugin
                  client
                  {:name            name
                   :jar-path        "/tmp/first.jar"
                   :entry-namespace "example.first"
                   :config          {:mode "one"}
                   :priority        30})
          id     (:pluginId plugin)]
      (try
        (is (string? id))
        (is (= name (:name plugin)))
        (is (= "/tmp/first.jar" (:jarPath plugin)))
        (is (= "example.first" (:entryNamespace plugin)))
        (is (= {:mode "one"} (:config plugin)))
        (is (true? (:enabled plugin)))
        (is (= 30 (:priority plugin)))

        (is (= plugin (store/load-plugin client id)))
        (is (= plugin (store/select-plugin client name)))
        (is (some #(= id (:pluginId %)) (store/list-plugins client)))
        (is (some #(= id (:pluginId %)) (store/list-enabled client)))

        (let [updated (store/update-plugin
                       client
                       id
                       {:name            name
                        :jar-path        "/tmp/second.jar"
                        :entry-namespace "example.second"
                        :config          {:mode "two"}
                        :enabled         false
                        :priority        5})]
          (is (= "/tmp/second.jar" (:jarPath updated)))
          (is (= "example.second" (:entryNamespace updated)))
          (is (= {:mode "two"} (:config updated)))
          (is (false? (:enabled updated)))
          (is (= 5 (:priority updated)))
          (is (not-any? #(= id (:pluginId %)) (store/list-enabled client))))

        (is (true? (store/delete-plugin client id)))
        (is (false? (store/delete-plugin client id)))
        (is (nil? (store/load-plugin client id)))

        (finally
          (store/delete-plugin client id)
          (pg/close! client))))
    (is true "CLAI_TEST_POSTGRES_URL is not set; skipping integration test")))
