(ns clai.agent.resources-test
  "Tests for rendering ACP resource content blocks as LLM-readable text."
  (:require [clai.agent.resources :as resources]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]))

(defn- temp-file
  "Create a temp file containing `content` (a string or byte array). The
  caller must delete it."
  ^File [content]
  (let [f (File/createTempFile "clai-resources-test" ".txt")]
    (if (string? content)
      (spit f content)
      (with-open [out (java.io.FileOutputStream. f)]
        (.write out ^bytes content)))
    f))

(deftest resource-link-inlines-local-file
  (let [f (temp-file "hello from a file")]
    (try
      (let [text (resources/resource-link->text
                  {:type "resource_link"
                   :name "hello.txt"
                   :title "Greeting"
                   :uri (str (.toURI f))
                   :mimeType "text/plain"
                   :size 17
                   :description "a greeting"})]
      (is (str/includes? text "<resource-link"))
      (is (str/includes? text "name=\"hello.txt\""))
      (is (str/includes? text "title=\"Greeting\""))
      (is (str/includes? text "mimeType=\"text/plain\""))
      (is (str/includes? text "description=\"a greeting\""))
        (is (str/includes? text "hello from a file"))
        (is (str/includes? text "</resource-link>")))
      (finally (.delete f)))))

(deftest resource-link-truncates-large-files
  (let [f (temp-file "0123456789")]
    (try
      (let [text (resources/resource-link->text
                  {:type "resource_link" :name "digits.txt"
                   :uri (str (.toURI f))}
                  {:max-bytes 4})]
        (is (str/includes? text "0123"))
        (is (not (str/includes? text "456789")))
        (is (str/includes? text "truncated at 4 bytes")))
      (finally (.delete f)))))

(deftest resource-link-describes-binary-files
  (let [f (temp-file (byte-array [1 2 0 4 5]))]
    (try
      (let [text (resources/resource-link->text
                  {:type "resource_link" :name "data.bin"
                   :uri (str (.toURI f))})]
        (is (str/includes? text "data.bin"))
        (is (str/includes? text "binary resource")))
      (finally (.delete f)))))

(deftest resource-link-describes-missing-and-remote-files
  (let [missing (resources/resource-link->text
                 {:type "resource_link"
                  :name "gone.txt"
                  :uri "file:///no/such/file.txt"})
        remote  (resources/resource-link->text
                 {:type "resource_link"
                  :name "page"
                  :uri "https://example.com/page"})]
    (is (str/includes? missing "gone.txt"))
    (is (str/includes? missing "file does not exist"))
    (is (str/includes? remote "https://example.com/page"))
    (is (str/includes? remote "only local file resources"))))

(deftest embedded-text-and-blob-resources
  (let [text (resources/embedded-resource->text
              {:type "resource"
               :resource {:uri "file:///tmp/a.txt"
                          :mimeType "text/plain"
                          :text "embedded body"}})
        blob (resources/embedded-resource->text
              {:type "resource"
               :resource {:uri "file:///tmp/a.bin"
                          :mimeType "application/octet-stream"
                          :blob "AAAA"}})]
    (is (str/includes? text "embedded body"))
    (is (str/includes? text "</resource>"))
    (is (str/includes? blob "binary resource omitted"))
    (is (not (str/includes? blob "AAAA")))))
