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
    (with-redefs [core/launch-repl (fn [args] (reset! seen (vec args)) 0)]
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
;; nREPL server
;; ---------------------------------------------------------------------------

(deftest nrepl-starts-a-server
  (let [started (atom [])
        out     (StringWriter.)]
    (with-redefs [core/start-nrepl! (fn [port]
                                      (swap! started conj port)
                                      {:port 7888})
                  core/await-shutdown (fn [] nil)]
      (binding [*out* out]
        (is (zero? (core/run ["nrepl"])))
        (is (zero? (core/run ["nrepl" "7888"])))))
    (is (= [nil 7888] @started))
    (is (str/includes? (str out) "7888"))))

(deftest nrepl-rejects-bad-ports
  (with-redefs [core/start-nrepl! (fn [_] (throw (ex-info "should not start" {})))]
    (let [{:keys [out err]}
          (capture #(is (= 1 (core/run ["nrepl" "nope"]))))]
      (is (str/includes? err "Invalid nREPL port"))
      (is (str/blank? out))))
  (let [{:keys [err]} (capture #(is (= 1 (core/run ["nrepl" "1" "2"]))))]
    (is (str/includes? err "Usage: clai nrepl"))))

(deftest nrepl-port-file-round-trips
  (let [file (java.io.File/createTempFile "clai-nrepl" ".port")]
    (try
      (with-redefs [core/port-file (.getPath file)
                    clojure.core/requiring-resolve
                    (fn [sym]
                      (when (= sym 'nrepl.server/start-server)
                        (fn [& _] {:port 7888})))]
        (is (= 7888 (:port (core/start-nrepl! nil))))
        (is (= 7888 (core/running-nrepl-port))))
      (finally
        (.delete file)))))

;; ---------------------------------------------------------------------------
;; REPLY launch
;; ---------------------------------------------------------------------------

(defn- stub-reply
  "Run `f` with REPLY's parser and launcher replaced by `parse` and `launch`,
  and with an nREPL server advertised on port 51234."
  [parse launch f]
  (with-redefs [clojure.core/requiring-resolve
                (fn [sym]
                  (case sym
                    reply.main/parse-args parse
                    reply.main/launch     launch))
                core/running-nrepl-port (constantly 51234)]
    (f)))

(deftest launch-repl-adds-the-clai-bootstrap
  (let [launched (atom nil)]
    (stub-reply (fn [_] [{:color true} [] "REPL-y banner"])
                (fn [options] (reset! launched options))
                #(is (zero? (core/launch-repl ["--color"]))))
    (is (= true (:color @launched)))
    (is (= core/repl-init (:custom-init @launched)))))

(deftest launch-repl-attaches-to-the-running-server
  (let [launched (atom nil)]
    (stub-reply (fn [_] [{} [] ""])
                (fn [options] (reset! launched options))
                #(is (zero? (core/launch-repl []))))
    (is (= "51234" (:attach @launched)))))

(deftest launch-repl-prefers-an-explicit-server
  (doseq [[options args] [[{:attach "9999"} ["--attach" "9999"]]
                          [{:standalone true} ["--standalone"]]]]
    (let [launched (atom nil)]
      (stub-reply (fn [_] [options [] ""])
                  (fn [options] (reset! launched options))
                  #(is (zero? (core/launch-repl args))))
      (is (= options (select-keys @launched [:attach :standalone]))
          (pr-str args)))))

(deftest launch-repl-requires-a-running-server
  (let [launched (atom false)]
    (stub-reply (fn [_] [{} [] ""])
                (fn [_] (reset! launched true))
                (fn []
                  (with-redefs [core/running-nrepl-port (constantly nil)]
                    (let [{:keys [out err]}
                          (capture #(is (= 1 (core/launch-repl []))))]
                      (is (str/includes? err "clai nrepl"))
                      (is (str/blank? out))))))
    (is (false? @launched))))

(deftest launch-repl-pretty-prints-returned-values
  (let [launched (atom nil)]
    (stub-reply (fn [_] [{} [] ""])
                (fn [options] (reset! launched options))
                #(is (zero? (core/launch-repl []))))
    (is (= 'nrepl.util.print/pprint
           (get-in @launched [:nrepl-context :interactive-eval
                              :nrepl.middleware.print/print]))))
  ;; Standalone mode prints locally, so it gets a value->string function.
  (let [launched (atom nil)]
    (stub-reply (fn [_] [{:standalone true} [] ""])
                (fn [options] (reset! launched options))
                #(is (zero? (core/launch-repl ["--standalone"]))))
    (is (fn? (:value-to-string @launched)))
    (is (str/includes? ((:value-to-string @launched) (vec (range 40)))
                       "\n"))))

(deftest launch-repl-keeps-an-explicit-value-printer
  (let [launched (atom nil)
        value-fn '(fn [value] (str value))
        nrepl-fn 'my.ns/print-value]
    (stub-reply (fn [_] [{:value-to-string value-fn
                          :nrepl-context
                          {:interactive-eval
                           {:nrepl.middleware.print/print nrepl-fn}}} [] ""])
                (fn [options] (reset! launched options))
                #(is (zero? (core/launch-repl []))))
    (is (= value-fn (:value-to-string @launched)))
    (is (= nrepl-fn
           (get-in @launched [:nrepl-context :interactive-eval
                              :nrepl.middleware.print/print])))))

(deftest launch-repl-keeps-a-user-init-form
  (let [launched  (atom nil)
        user-init '(println "user init")]
    (stub-reply (fn [_] [{:custom-init user-init} [] ""])
                (fn [options] (reset! launched options))
                #(is (zero? (core/launch-repl ["--init" "init.clj"]))))
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
