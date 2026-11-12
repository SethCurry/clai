(ns clai.agent.configs-test
  "Tests for the pure parts of `clai.agent.configs`: database rows become
  public configuration maps that only contain the overrides they name."
  (:require [clai.agent.configs :as configs]
            [clojure.test :refer :all]))

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
