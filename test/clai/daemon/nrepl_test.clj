(ns clai.daemon.nrepl-test
  "Tests for the nREPL client used by the web REPL."
  (:require [clai.daemon.nrepl :as bridge]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.test :refer :all]
            [nrepl.server :as nrepl-server]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(defn- with-server
  "Run `f` with an ephemeral nREPL server and close both the server and any
  connection `f` opens."
  [f]
  (let [server (nrepl-server/start-server :port 0)]
    (try
      (f (:port server))
      (finally
        (nrepl-server/stop-server server)))))

(defn- response-with-value
  "The response in `responses` carrying `value`, if any."
  [responses value]
  (some #(when (= value (:value %)) %) responses))

(deftest eval-round-trips
  (with-server
    (fn [port]
      (let [conn (bridge/connect! {:port port})]
        (try
          (let [responses (bridge/eval! conn "(+ 1 2)")]
            (is (some? (response-with-value responses "3")))
            (is (some #(some #{"done"} (:status %)) responses)))
          (finally
            (bridge/close! conn)))))))

(deftest eval-can-select-a-namespace
  (with-server
    (fn [port]
      (let [conn (bridge/connect! {:port port})]
        (try
          (let [responses (bridge/eval! conn "(str *ns*)" "clojure.string")]
            (is (some? (response-with-value responses "\"clojure.string\""))))
          (finally
            (bridge/close! conn)))))))

(deftest eval-returns-errors-as-responses
  (with-server
    (fn [port]
      (let [conn (bridge/connect! {:port port})]
        (try
          (let [responses (bridge/eval! conn "(/ 1 0)")]
            (is (some #(re-find #"Divide by zero" (str (:err %) (:ex %))) responses)))
          (finally
            (bridge/close! conn)))))))

(deftest close-is-safe
  (is (nil? (bridge/close! nil))))
