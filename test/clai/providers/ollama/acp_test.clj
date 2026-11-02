(ns clai.providers.ollama.acp-test
  "Tests for ACP -> Ollama prompt normalization in `clai.providers.ollama.acp`."
  (:require [acp.agent :as acp]
            [clai.providers.ollama.acp :as ollama-acp]
            [clai.test-support :refer [quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]))

(use-fixtures :once quiet-logs)

(def ^:private prompt->messages
  "The ACP prompt normalizer."
  #'ollama-acp/acp-prompt->ollama-messages)

;; ---------------------------------------------------------------------------
;; Prompt normalization
;; ---------------------------------------------------------------------------

(deftest content-block-prompt-becomes-user-message
  ;; ACP sends `:prompt` as a vector of ContentBlocks; their text must survive
  ;; the conversion (regression: it used to be dropped, sending an empty
  ;; user message and prompting the model to report "no request").
  (is (= [{:role "user" :content "hello"}]
         (prompt->messages [{:type "text" :text "hello"}])))
  (is (= [{:role "user" :content "hello\nworld"}]
         (prompt->messages [{:type "text" :text "hello"}
                            {:type "text" :text "world"}])))
  (is (= [{:role "user" :content "look"}]
         (prompt->messages [{:type "text" :text "look"}
                            {:type "image" :data "..."}]))))

(deftest tolerant-prompt-shapes
  (is (= [] (prompt->messages nil)))
  (is (= [{:role "user" :content "hi"}] (prompt->messages "hi")))
  (is (= [{:role "user" :content "hi"}]
         (prompt->messages {:type "text" :text "hi"})))
  ;; role-tagged messages keep their role
  (is (= [{:role "user" :content "hi"}]
         (prompt->messages [{:role "user"
                             :content [{:type "text" :text "hi"}]}])))
  (is (= [{:role "assistant" :content "yo"}]
         (prompt->messages [{:role "assistant" :content "yo"}]))))

;; ---------------------------------------------------------------------------
;; Resource blocks
;; ---------------------------------------------------------------------------

(defn- temp-text-file
  "Create a temporary file containing `content`. Caller must delete it."
  [content]
  (let [f (File/createTempFile "clai-resource" ".txt")]
    (spit f content)
    f))

(deftest resource-link-blocks-are-converted-not-dropped
  (let [f (temp-text-file "the file body")]
    (try
      (let [[msg] (prompt->messages
                   [{:type "text" :text "please read"}
                    {:type "resource_link"
                     :name "notes.txt"
                     :uri (str (.toURI f))
                     :mimeType "text/plain"
                     :description "my notes"}])]
        (is (= "user" (:role msg)))
        (is (str/includes? (:content msg) "please read"))
        (is (str/includes? (:content msg) "notes.txt"))
        (is (str/includes? (:content msg) "the file body"))
        (is (str/includes? (:content msg) "my notes")))
      (finally (.delete f)))))

(deftest resource-link-with-unreadable-target-still-describes-it
  (let [[msg] (prompt->messages
               [{:type "resource_link"
                 :name "missing.txt"
                 :uri "file:///definitely/not/here/missing.txt"}])
        content (:content msg)]
    (is (str/includes? content "missing.txt"))
    (is (str/includes? content "file:///definitely/not/here/missing.txt"))
    (is (str/includes? content "unavailable"))))

(deftest resource-link-uses-cwd-for-relative-paths
  (let [f (temp-text-file "relative body")]
    (try
      (let [name (.getName f)
            [msg] (prompt->messages
                   [{:type "resource_link" :name name :uri name}]
                   {:cwd (.getPath (.getParentFile f))})]
        (is (str/includes? (:content msg) "relative body")))
      (finally (.delete f)))))

(deftest embedded-text-resource-is-inlined
  (let [[msg] (prompt->messages
               [{:type "resource"
                 :resource {:uri "file:///tmp/inline.txt"
                            :mimeType "text/plain"
                            :text "inline contents"}}])]
    (is (str/includes? (:content msg) "inline contents"))))

(deftest resource-block-in-role-tagged-message
  (let [[msg] (prompt->messages
               [{:role "user"
                 :content [{:type "resource"
                            :resource {:uri "file:///tmp/inline.txt"
                                       :text "role tagged"}}]}])]
    (is (= "user" (:role msg)))
    (is (str/includes? (:content msg) "role tagged"))))

(deftest existing-text-and-image-behaviour-is-preserved
  (is (= [{:role "user" :content "look"}]
         (prompt->messages [{:type "text" :text "look"}
                            {:type "image" :data "..."}]))))

;; ---------------------------------------------------------------------------
;; History replay
;; ---------------------------------------------------------------------------

(deftest replay-sends-thought-chunks-before-assistant-text
  (let [sent (atom [])]
    (with-redefs [acp/send-session-update
                  (fn [_conn sid update]
                    (swap! sent conj [sid update]))]
      (is (= 2 (ollama-acp/replay-session-history
                nil "session-1"
                [{:role "assistant"
                  :thinking "step one"
                  :content  "the answer"}])))
      (is (= ["agent_thought_chunk" "agent_message_chunk"]
             (mapv (comp :sessionUpdate second) @sent)))
      (is (= "step one" (get-in (first @sent) [1 :content :text])))
      (is (= "the answer" (get-in (second @sent) [1 :content :text]))))))

(deftest replay-skips-messages-without-thinking
  (let [sent (atom [])]
    (with-redefs [acp/send-session-update
                  (fn [_conn sid update]
                    (swap! sent conj [sid update]))]
      (is (= 2 (ollama-acp/replay-session-history
                nil "session-2"
                [{:role "user" :content "hi"}
                 {:role "assistant" :content "hello"}])))
      (is (= ["user_message_chunk" "agent_message_chunk"]
             (mapv (comp :sessionUpdate second) @sent))))))
