(ns clai.config-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clai.config :as config]
            [clai.test-support :refer [quiet-logs]]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- tmp-file
  "Return a fresh temp file path (not yet created)."
  []
  (let [f (java.io.File/createTempFile "clai-cfg" ".edn")]
    (.delete f)                              ; we want it absent until written
    (.getAbsolutePath f)))

(defn- write-cfg
  "Write `content` to a temp file and return its absolute path."
  [content]
  (let [path (tmp-file)]
    (spit path content)
    path))

(use-fixtures :once quiet-logs)

(use-fixtures :each
  (fn [f]
    ;; `CLAI_CONFIG` would otherwise skew discovery in `load-config` (no-arg),
    ;; so pretend it's unset for each test.
    (with-redefs [config/env-config-path (constantly nil)]
      (f))))

;; ---------------------------------------------------------------------------
;; Explicit-path loading
;; ---------------------------------------------------------------------------

(deftest load-config-explicit-path
  (let [path (write-cfg "{:model \"llama3.2\" :max-tool-rounds 3}")]
    (let [cfg (config/load-config path)]
      (is (= "llama3.2" (:model cfg)))
      (is (= 3 (:max-tool-rounds cfg))))))

(deftest load-config-empty-file
  (let [path (write-cfg "")]
    (is (= {} (config/load-config path)))))

(deftest load-config-missing-file-returns-empty
  (is (= {} (config/load-config (tmp-file)))))

(deftest load-config-directory-returns-empty
  (let [dir (java.io.File/createTempFile "clai-d" "")]
    (.delete dir)
    (.mkdirs dir)
    (try
      (is (= {} (config/load-config (.getAbsolutePath dir))))
      (finally (.delete dir)))))

(deftest load-config-edn-readers
  (let [path (write-cfg "{:ollama {:host \"http://h:1\" :api-key \"k\" :timeout 99}}")]
    (let [cfg (config/load-config path)]
      (is (= {:host "http://h:1" :api-key "k" :timeout 99} (:ollama cfg))))))

(deftest load-config-tools-vector
  (let [path (write-cfg "{:tools [\"read_file\" \"run_command\"]}")]
    (is (= ["read_file" "run_command"] (:tools (config/load-config path))))))

(deftest load-config-malformed-throws
  (let [path (write-cfg "{:model ")]          ; unterminated
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"config file"
         (config/load-config path)))))

(deftest load-config-non-map-coerced-to-empty
  ;; A top-level non-map is unusual but shouldn't crash callers.
  (let [path (write-cfg "[1 2 3]")]
    (is (= {} (config/load-config path)))))

;; ---------------------------------------------------------------------------
;; Discovery (no-arg load-config)
;; ---------------------------------------------------------------------------

(deftest load-config-discovers-via-env
  (let [path (write-cfg "{:model \"env-model\"}")]
    (with-redefs [config/env-config-path (constantly path)]
      (is (= "env-model" (:model (config/load-config)))))))

(deftest config-path-returns-first-existing
  (let [path (write-cfg "{:model \"x\"}")]
    (with-redefs [config/env-config-path (constantly path)]
      (is (= path (config/config-path))))))

(deftest config-path-nil-when-none-exist
  ;; Point every candidate location at an absent file so the result does not
  ;; depend on whether the machine running the tests has a real config file.
  (let [missing (java.io.File. (tmp-file))]
    (with-redefs [config/env-config-path (constantly nil)
                  config/cwd-config-file (constantly missing)
                  config/home-config-file (constantly missing)]
      (is (nil? (config/config-path))))))

(deftest candidates-are-absolute-strings
  (with-redefs [config/env-config-path (constantly "/tmp/clai-candidate.edn")]
    (let [cs (config/candidates)]
      (is (sequential? cs))
      (is (every? string? cs))
      (is (some #(= "/tmp/clai-candidate.edn" %) cs)))))