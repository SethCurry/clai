(ns clai.agent.tools.builtin.files-test
  "Tests for the built-in filesystem tools, in particular that `read_file` can
  be limited to a range of lines."
  (:require [clai.agent.tools.builtin.common :as common]
            [clai.agent.tools.builtin.files :as builtin-files]
            [clai.agent.tools.schema :as schema]
            [clai.test-support :refer [quiet-logs]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [malli.core :as malli])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once quiet-logs)

(def ^:private tools-by-id
  "The filesystem tools keyed by id."
  (into {} (map (juxt :id identity)) (builtin-files/tools)))

(defn- handler
  "Return the handler for filesystem tool `id`, or call it with `args` and `ctx`."
  ([id] (:handler (get tools-by-id id)))
  ([id args ctx] ((handler id) args ctx)))

(defn- temp-dir
  "Create an empty temporary directory and return it as a `java.io.File`."
  []
  (.toFile (Files/createTempDirectory "clai-files-test"
                                      (make-array FileAttribute 0))))

(def ^:private numbered-lines
  "Ten numbered lines, so a read can be told apart from its neighbours."
  (str/join "\n" (map #(str "line " %) (range 1 11))))

(defn- with-file
  "Write `content` to `name` in a temporary directory, call `f` with a tool
  context whose `:cwd` is that directory, and clean up afterwards."
  [name content f]
  (let [root (temp-dir)]
    (try
      (spit (io/file root name) content)
      (f {:cwd (.getAbsolutePath root)})
      (finally
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file true))))))

;; ---------------------------------------------------------------------------
;; Tool specs
;; ---------------------------------------------------------------------------

(deftest the-tool-specs-are-valid
  (doseq [tool (builtin-files/tools)]
    (is (malli/validate schema/tool-spec (:spec tool)) (:id tool))))

(deftest read-file-spec-documents-the-optional-line-range
  (let [{:keys [description parameters]}
        (get-in tools-by-id ["read_file" :spec :function])]
    (is (= #{"path" "start_line" "end_line"} (set (keys (:properties parameters)))))
    (is (= ["path"] (:required parameters))
        "the line range is optional, so only `path` is required")
    (is (str/includes? description "start_line"))
    (is (str/includes? description "end_line"))))

;; ---------------------------------------------------------------------------
;; Line ranges
;; ---------------------------------------------------------------------------

(deftest read-file-returns-the-whole-file-by-default
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= numbered-lines (handler "read_file" {:path "lines.txt"} ctx))))))

(deftest read-file-returns-everything-after-the-start-line
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= "line 3\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10"
             (handler "read_file" {:path "lines.txt" :start_line 3} ctx)))
      (is (= numbered-lines
             (handler "read_file" {:path "lines.txt" :start_line 1} ctx))
          "starting at the first line reads everything"))))

(deftest read-file-returns-an-inclusive-line-range
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= "line 2\nline 3\nline 4"
             (handler "read_file" {:path "lines.txt" :start_line 2 :end_line 4} ctx)))
      (is (= "line 7"
             (handler "read_file" {:path "lines.txt" :start_line 7 :end_line 7} ctx))
          "a single-line range returns just that line")
      (is (= "line 10"
             (handler "read_file" {:path "lines.txt"
                                     :start_line 10
                                     :end_line 99} ctx))
          "a range that runs past the end of the file returns what is there"))))

(deftest read-file-accepts-line-numbers-as-strings
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= "line 2\nline 3"
             (handler "read_file" {:path "lines.txt"
                                   :start_line "2"
                                   :end_line " 3 "} ctx))
          "a model may send the numbers as JSON strings"))))

(deftest read-file-reads-past-the-end-of-the-file
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= "line 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\nline 9\nline 10"
             (handler "read_file" {:path "lines.txt"
                                   :start_line 2
                                   :end_line 99} ctx))
          "an `end_line` past the end reads to the end"))))

(deftest read-file-explains-a-line-past-the-end-of-the-file
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= "Error: lines.txt has 10 lines, so there is no line 99."
             (handler "read_file" {:path "lines.txt" :start_line 99} ctx))))))

(deftest read-file-rejects-unusable-line-numbers
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (doseq [args [{:start_line "abc"}
                    {:start_line 0}
                    {:start_line -3}
                    {:end_line "nope"}
                    {:end_line 0}]]
        (is (str/starts-with? (handler "read_file" (assoc args :path "lines.txt") ctx)
                              "Error: ")
            (pr-str args)))
      (is (= "Error: `end_line` 2 is before `start_line` 5."
             (handler "read_file"
                      {:path "lines.txt" :start_line 5 :end_line 2}
                      ctx))))))

(deftest read-file-treats-a-blank-line-number-as-absent
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= numbered-lines
             (handler "read_file" {:path "lines.txt"
                                   :start_line ""
                                   :end_line nil} ctx))
          "a blank optional field means \"not given\""))))

(deftest read-file-caps-what-one-call-returns
  (with-file "big.txt" (str/join "\n" (repeat 20000 (apply str (repeat 20 "x"))))
    (fn [ctx]
      (let [result (handler "read_file" {:path "big.txt"} ctx)]
        (is (str/includes? result "truncated at")
            "a read that hits the cap says so")
        (is (< (count result) (+ common/max-output-bytes 200))
            "the cap keeps one read from flooding the session history")
        (is (str/includes? result "`start_line` and `end_line`")
            "the truncation note points at the line range arguments")))))

;; ---------------------------------------------------------------------------
;; Other filesystem tools
;; ---------------------------------------------------------------------------

(deftest read-file-reports-missing-files-and-directories
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= "Error: no such file: nope.txt"
             (handler "read_file" {:path "nope.txt"} ctx)))
      (is (= "Error: not a file (is a directory): ."
             (handler "read_file" {:path "."} ctx))))))

(deftest write-file-creates-the-file-and-its-parents
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (str/starts-with? (handler "write_file" {:path "sub/dir/new.txt"
                                                   :content "hello"}
                                          ctx)
                            "Wrote 5 bytes"))
      (is (= "hello" (slurp (io/file (:cwd ctx) "sub/dir/new.txt")))))))

(deftest list-directory-lists-entries
  (with-file "lines.txt" numbered-lines
    (fn [ctx]
      (is (= "- lines.txt" (handler "list_directory" {} ctx))))))
