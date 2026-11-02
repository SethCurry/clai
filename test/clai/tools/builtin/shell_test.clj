(ns clai.tools.builtin.shell-test
  "Tests for the built-in `run_command` tool, in particular that output is
  streamed to the caller as it is produced rather than returned in one piece."
  (:require [clai.tools.builtin.shell :as shell]
            [clai.test-support :refer [quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(def ^:private run-command
  "The `run_command` tool handler."
  (-> (shell/tools) first :handler))

(defn- snapshotting-ctx
  "Context whose `:on-output` callback records each snapshot it is handed.
  The atom holds the snapshots, oldest first."
  []
  (let [snapshots (atom [])]
    [{:cwd "/tmp" :on-output (fn [output] (swap! snapshots conj output))}
     snapshots]))

(deftest run-command-returns-combined-output
  (is (= "one\ntwo\n" (run-command {:command "echo one; echo two"} {:cwd "/tmp"}))))

(deftest run-command-reports-exit-codes
  (is (= "boom\n\n[exit code 3]"
         (run-command {:command "echo boom; exit 3"} {:cwd "/tmp"}))
      "a non-zero exit is appended to the raw output"))

(deftest run-command-streams-output-as-it-arrives
  (let [[ctx snapshots] (snapshotting-ctx)
        result (run-command {:command "echo one; sleep 0.3; echo two"} ctx)]
    (is (= "one\ntwo\n" result)
        "the return value is still the complete output")
    (is (= ["one\n"] (take 1 @snapshots))
        "the first line is reported before the command finishes")
    (is (= "one\ntwo\n" (last @snapshots))
        "the last snapshot holds everything captured so far")
    (is (every? #(str/starts-with? "one\ntwo\n" %) @snapshots)
        "snapshots are prefixes of the final output, so a consumer can
        replace what it rendered instead of appending to it")))

(deftest run-command-emits-nothing-when-no-consumer-is-listening
  (is (= "quiet\n" (run-command {:command "echo quiet"} {:cwd "/tmp"}))
      "a context without :on-output must work -- sub-agent tools have none"))

(deftest run-command-survives-a-failing-output-consumer
  (let [ctx {:cwd "/tmp"
             :on-output (fn [_output] (throw (ex-info "consumer blew up" {})))}]
    (is (= "ok\n" (run-command {:command "echo ok"} ctx))
        "a broken stream consumer must not fail the command")))

(deftest run-command-caps-output
  (let [output (run-command {:command "yes x | head -c 200000"} {:cwd "/tmp"})]
    (is (<= (count output) 65536)
        "output is capped so history stays small")))
