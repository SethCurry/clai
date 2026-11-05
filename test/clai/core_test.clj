(ns clai.core-test
  "Tests for the `clai` command-line dispatch in `clai.core`.

  The agent server and the REPLY launcher are stubbed, so nothing here starts
  the stdio agent, JLine, or an nREPL server."
  (:require [clai.core :as core]
            [clai.test-support :refer [quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io StringWriter]))

(use-fixtures :once quiet-logs)

(defn- capture
  "Call `f` with `*out*` and `*err*` captured, returning both as strings."
  [f]
  (let [out (StringWriter.)
        err (StringWriter.)]
    (binding [*out* out *err* err]
      (f))
    {:out (str out) :err (str err)}))

(deftest agent-is-the-default-command
  (let [served (atom 0)
        repl   (fn [_] (throw (ex-info "the REPL should not launch" {})))]
    (with-redefs [core/serve-agent (fn [] (swap! served inc))
                  core/launch-repl repl]
      (is (zero? (core/run [])))
      (is (zero? (core/run ["agent"])))
      (is (= 2 @served)))))

(deftest repl-forwards-its-arguments
  (let [seen (atom ::none)]
    (with-redefs [core/launch-repl (fn [args] (reset! seen (vec args)))]
      (is (zero? (core/run ["shell"])))
      (is (= [] @seen))
      (is (zero? (core/run ["shell" "--standalone" "--color"])))
      (is (= ["--standalone" "--color"] @seen)))))

(deftest help-prints-usage
  (doseq [args [["help"] ["--help"] ["-h"]]]
    (let [{:keys [out err]} (capture #(is (zero? (core/run args))))]
      (is (str/includes? out "Usage: clai [command]") (pr-str args))
      (is (str/includes? out "shell") (pr-str args))
      (is (str/blank? err) (pr-str args)))))

(deftest unknown-command-fails
  (let [{:keys [out err]}
        (capture #(is (= 1 (core/run ["frobnicate"]))))]
    (is (str/includes? err "Unknown command: frobnicate"))
    (is (str/includes? err "Usage: clai [command]"))
    (is (str/blank? out))))

;; ---------------------------------------------------------------------------
;; REPLY launch
;; ---------------------------------------------------------------------------

(defn- stub-reply
  "Run `f` with REPLY's parser and launcher replaced by `parse` and `launch`."
  [parse launch f]
  (with-redefs [clojure.core/requiring-resolve
                (fn [sym]
                  (case sym
                    reply.main/parse-args parse
                    reply.main/launch     launch))]
    (f)))

(deftest launch-repl-adds-the-clai-bootstrap
  (let [launched (atom nil)]
    (stub-reply (fn [_] [{:color true} [] "REPL-y banner"])
                (fn [options] (reset! launched options))
                #(is (nil? (core/launch-repl ["--color"]))))
    (is (= true (:color @launched)))
    (is (= core/repl-init (:custom-init @launched)))))

(deftest launch-repl-keeps-a-user-init-form
  (let [launched  (atom nil)
        user-init '(println "user init")]
    (stub-reply (fn [_] [{:custom-init user-init} [] ""])
                (fn [options] (reset! launched options))
                #(core/launch-repl ["--init" "init.clj"]))
    (is (= `(do ~core/repl-init ~user-init) (:custom-init @launched)))))

(deftest launch-repl-prints-reply-help-without-launching
  (let [launched (atom false)]
    (stub-reply (fn [_] [{:help true} [] "REPL-y banner"])
                (fn [_] (reset! launched true))
                (fn []
                  (let [{:keys [out]} (capture #(core/launch-repl ["--help"]))]
                    (is (str/includes? out "REPL-y banner")))))
    (is (false? @launched))))

(deftest launch-repl-reports-bad-options
  (let [launched (atom false)
        parse    (fn [args]
                   (if (= ["--help"] (vec args))
                     [{:help true} [] "REPL-y banner"]
                     (throw (ex-info "Unknown option" {}))))]
    (stub-reply parse
                (fn [_] (reset! launched true))
                (fn []
                  (let [{:keys [out]} (capture #(core/launch-repl ["--nope"]))]
                    (is (str/includes? out "Unknown option"))
                    (is (str/includes? out "REPL-y banner")))))
    (is (false? @launched))))
