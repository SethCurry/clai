(ns clai.agent.providers.ollama.prompt-test
  "Tests for prompt normalization and history replay in
  `clai.agent.providers.ollama.prompt`."
  (:require [clai.agent.providers.ollama.prompt :as ollama-prompt]
            [clai.events.schema :as events]
            [clai.test-support :refer [quiet-logs]]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]))

(use-fixtures :once quiet-logs)

;; ---------------------------------------------------------------------------
;; Prompt normalization
;; ---------------------------------------------------------------------------

(deftest content-block-prompt-becomes-user-message
  ;; Prompts arrive as vectors of event content blocks; their text must survive
  ;; the conversion (regression: it used to be dropped, sending an empty user
  ;; message and prompting the model to report "no request").
  (is (= [{:role "user" :content "hello"}]
         (ollama-prompt/prompt->messages [{:type "text" :content "hello"}])))
  (is (= [{:role "user" :content "hello\nworld"}]
         (ollama-prompt/prompt->messages [{:type "text" :content "hello"}
                                          {:type "text" :content "world"}])))
  (is (= [{:role "user" :content "look"}]
         (ollama-prompt/prompt->messages [{:type "text" :content "look"}
                                          {:type "image" :data "..."}]))))

(deftest tolerant-prompt-shapes
  (is (= [] (ollama-prompt/prompt->messages nil)))
  (is (= [{:role "user" :content "hi"}] (ollama-prompt/prompt->messages "hi")))
  ;; Older callers used `:text`; keep accepting it while `:content` is canonical.
  (is (= [{:role "user" :content "hi"}]
         (ollama-prompt/prompt->messages {:type "text" :text "hi"})))
  ;; role-tagged messages keep their role
  (is (= [{:role "user" :content "hi"}]
         (ollama-prompt/prompt->messages
          [{:role "user" :content [{:type "text" :content "hi"}]}])))
  (is (= [{:role "assistant" :content "yo"}]
         (ollama-prompt/prompt->messages [{:role "assistant" :content "yo"}]))))

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
      (let [[msg] (ollama-prompt/prompt->messages
                   [{:type "text" :content "please read"}
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
  (let [[msg] (ollama-prompt/prompt->messages
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
            [msg] (ollama-prompt/prompt->messages
                   [{:type "resource_link" :name name :uri name}]
                   {:cwd (.getPath (.getParentFile f))})]
        (is (str/includes? (:content msg) "relative body")))
      (finally (.delete f)))))

(deftest embedded-text-resource-is-inlined
  (let [[msg] (ollama-prompt/prompt->messages
               [{:type "resource"
                 :resource {:uri "file:///tmp/inline.txt"
                            :mimeType "text/plain"
                            :text "inline contents"}}])]
    (is (str/includes? (:content msg) "inline contents"))))

(deftest resource-block-in-role-tagged-message
  (let [[msg] (ollama-prompt/prompt->messages
               [{:role "user"
                 :content [{:type "resource"
                            :resource {:uri "file:///tmp/inline.txt"
                                       :text "role tagged"}}]}])]
    (is (= "user" (:role msg)))
    (is (str/includes? (:content msg) "role tagged"))))

(deftest existing-text-and-image-behaviour-is-preserved
  (is (= [{:role "user" :content "look"}]
         (ollama-prompt/prompt->messages
          [{:type "text" :content "look"}
           {:type "image" :data "..."}]))))

;; ---------------------------------------------------------------------------
;; History replay
;; ---------------------------------------------------------------------------

(deftest history-replays-thoughts-before-assistant-text
  (let [updates (ollama-prompt/history->updates
                 [{:role "assistant"
                   :thinking "step one"
                   :content  "the answer"}])]
    (is (= ["agent_thought" "agent_message"] (mapv :type updates)))
    (is (= "step one" (events/content->text (:content (first updates)))))
    (is (= "the answer" (events/content->text (:content (second updates)))))))

(deftest history-skips-messages-without-thinking
  (let [updates (ollama-prompt/history->updates
                 [{:role "user" :content "hi"}
                  {:role "system" :content "internal"}
                  {:role "tool" :content "internal"}
                  {:role "assistant" :content "hello"}])]
    (is (= ["user_prompt" "agent_message"] (mapv :type updates)))))
