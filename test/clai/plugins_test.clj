(ns clai.plugins-test
  "Tests for the plugin registry, hook composition, and jar loading."
  (:require [clai.daemon.postgres :as postgres]
            [clai.events.schema :as events]
            [clai.plugins :as plugins]
            [clai.plugins.store :as store]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File]
           [java.util.jar JarEntry JarOutputStream]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- clear-plugins
  "Start every test with no registered plugins."
  [f]
  (plugins/clear!)
  (try
    (f)
    (finally
      (plugins/clear!))))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)
(use-fixtures :each clear-plugins)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- plugin-row
  "Build a plugin row shaped like one returned by `clai.plugins.store`."
  ([name]
   (plugin-row name {} 100))
  ([name config]
   (plugin-row name config 100))
  ([name config priority]
   {:pluginId       (str "id-" name)
    :name           name
    :jarPath        (str "/tmp/" name ".jar")
    :entryNamespace (str "example." name)
    :config         config
    :enabled        true
    :priority       priority}))

(defn- write-plugin-jar!
  "Write a temporary plugin jar whose entry namespace defines `plugin`.

  The plugin uses `:marker` from its config to make its system-prompt change
  visible, and exercises every message hook."
  [ns-sym]
  (let [file (File/createTempFile "clai-plugin-" ".jar")
        source
        (str "(ns " ns-sym ")\n"
             "(defn plugin [config]\n"
             "  {:description \"test plugin\"\n"
             "   :hooks\n"
             "   {:system-prompt\n"
             "    (fn [ctx next] (str (next ctx) \"|\" (:marker config)))\n"
             "    :user-prompt-pre\n"
             "    (fn [ctx] (conj (:messages ctx)\n"
             "                    {:role \"user\" :content \"pre\"}))\n"
             "    :user-prompt-post\n"
             "    (fn [_ctx] [{:role \"user\" :content \"post\"}])\n"
             "    :agent-message-pre\n"
             "    (fn [ctx] (assoc (:message ctx) :content \"changed\"))\n"
             "    :agent-message-post\n"
             "    (fn [ctx] (assoc-in (:update ctx)\n"
             "                        [:content 0 :content]\n"
             "                        \"post post\"))}})\n")
        entry-name (str (str/replace (name ns-sym) "." "/") ".clj")]
    (.deleteOnExit file)
    (with-open [out (JarOutputStream. (io/output-stream file))]
      (.putNextEntry out (JarEntry. entry-name))
      (io/copy source out)
      (.closeEntry out))
    (.getAbsolutePath file)))

(defn- unique-plugin-ns
  "A fresh namespace symbol, so requiring one test's jar cannot collide with a
  previous test's namespace in the same JVM."
  []
  (symbol (str "example.plugin" (System/nanoTime))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(deftest register-and-replace-plugins
  (let [row (plugin-row "alpha" {:answer 42} 20)]
    (plugins/register! row {})
    (is (= [{:name "alpha"
             :pluginId "id-alpha"
             :priority 20
             :config {:answer 42}
             :hooks []}]
           (plugins/active-plugins)))

    (plugins/register! row {:hooks {:system-prompt (fn [_ctx next] (next {}))}})
    (is (= ["alpha"] (map :name (plugins/active-plugins))))
    (is (= [:system-prompt] (:hooks (first (plugins/active-plugins)))))

    (is (true? (plugins/unregister! "alpha")))
    (is (false? (plugins/unregister! "alpha")))
    (is (empty? (plugins/active-plugins)))))

;; ---------------------------------------------------------------------------
;; Hook composition
;; ---------------------------------------------------------------------------

(deftest namespace-builder-hooks-wrap-the-default-builder
  (let [seen (atom nil)
        base (fn [opts]
               (reset! seen opts)
               (the-ns 'user))]
    (plugins/register! (plugin-row "outer" {} 10)
                       {:hooks {:namespace-builder
                                (fn [ctx next]
                                  (next (update-in ctx [:opts :order] (fnil conj [])
                                                   :outer)))}})
    (plugins/register! (plugin-row "inner" {} 20)
                       {:hooks {:namespace-builder
                                (fn [ctx next]
                                  (next (update-in ctx [:opts :order] (fnil conj [])
                                                   :inner)))}})

    (is (= (the-ns 'user)
           (plugins/build-namespace {:base true} base)))
    (is (= {:base true :order [:outer :inner]} @seen))))

(deftest system-prompt-hooks-compose-in-priority-order
  (plugins/register! (plugin-row "low" {} 10)
                     {:hooks {:system-prompt
                              (fn [ctx next]
                                (str (next ctx) "-low"))}})
  (plugins/register! (plugin-row "high" {} 20)
                     {:hooks {:system-prompt
                              (fn [ctx next]
                                (str (next ctx) "-high"))}})

  (is (= "base-high-low"
         (plugins/assemble-system-prompt {:sys-prompt "base"}
                                         (fn [ctx] (:sys-prompt ctx))))))

(deftest system-prompt-hooks-honor-the-active-plugin-selection
  (plugins/register! (plugin-row "alpha" {} 10)
                     {:hooks {:system-prompt
                              (fn [ctx next]
                                (str (next ctx) "-alpha"))}})
  (plugins/register! (plugin-row "beta" {} 20)
                     {:hooks {:system-prompt
                              (fn [ctx next]
                                (str (next ctx) "-beta"))}})
  (let [prompt (fn [active]
                 (plugins/assemble-system-prompt
                  (cond-> {:sys-prompt "base"}
                    (some? active) (assoc :plugins active))
                  (fn [ctx] (:sys-prompt ctx))))]
    (is (= "base-beta-alpha" (prompt nil))
        "a nil selection runs every registered plugin")
    (is (= "base-alpha" (prompt ["alpha"])))
    (is (= "base-beta" (prompt ["beta"])))
    (is (= "base-beta" (prompt ["id-beta"]))
        "plugins may be selected by database id as well as name")
    (is (= "base" (prompt []))
        "an empty selection disables plugins")
    (is (= "base" (prompt ["missing"])))))

(deftest failing-hooks-are-ignored
  (plugins/register! (plugin-row "failing")
                     {:hooks {:system-prompt
                              (fn [_ctx _next]
                                (throw (ex-info "boom" {})))}})
  (is (= "base"
         (plugins/assemble-system-prompt {:sys-prompt "base"}
                                         (fn [ctx] (:sys-prompt ctx))))))

;; ---------------------------------------------------------------------------
;; Message hooks
;; ---------------------------------------------------------------------------

(deftest user-prompt-hooks-can-rewrite-and-append
  (plugins/register! (plugin-row "messages")
                     {:hooks
                      {:user-prompt-pre
                       (fn [{:keys [messages]}]
                         (conj messages {:role "user" :content "pre"}))
                       :user-prompt-post
                       (fn [_ctx]
                         [{:role "user" :content "post"}])}})

  (is (= [{:role "user" :content "hello"}
          {:role "user" :content "pre"}]
         (plugins/user-prompt-pre {:sessionId "s"}
                                  [{:role "user" :content "hello"}])))

  (is (= [{:role "user" :content "post"}]
         (plugins/user-prompt-post {:sessionId "s"}
                                   [{:role "user" :content "hello"}]))))

(deftest message-hooks-honor-the-session-active-plugin-selection
  (plugins/register! (plugin-row "alpha")
                     {:hooks {:user-prompt-pre
                              (fn [{:keys [messages]}]
                                (conj messages {:role "user"
                                                :content "alpha"}))}})
  (plugins/register! (plugin-row "beta")
                     {:hooks {:user-prompt-pre
                              (fn [{:keys [messages]}]
                                (conj messages {:role "user"
                                                :content "beta"}))}})
  (is (= [{:role "user" :content "hello"}
          {:role "user" :content "alpha"}]
         (plugins/user-prompt-pre {:sessionId "s" :plugins ["alpha"]}
                                  [{:role "user" :content "hello"}]))
      "only the plugins selected by the session run")
  (is (= [{:role "user" :content "hello"}]
         (plugins/user-prompt-pre {:sessionId "s" :plugins []}
                                  [{:role "user" :content "hello"}]))
      "an empty selection disables every plugin"))

(deftest agent-message-hooks-can-rewrite-the-persisted-message-and-event
  (plugins/register! (plugin-row "messages")
                     {:hooks
                      {:agent-message-pre
                       (fn [{:keys [message]}]
                         (assoc message :content "changed"))
                       :agent-message-post
                       (fn [{:keys [update]}]
                         (assoc-in update [:content 0 :content] "post post"))}})

  (is (= {:role "assistant" :content "changed"}
         (plugins/agent-message-pre {:sessionId "s"}
                                    {:role "assistant" :content "original"})))

  (let [update (events/text-event "agent_message" "original")]
    (is (= (assoc-in update [:content 0 :content] "post post")
           (plugins/agent-message-post
            {:sessionId "s"}
            {:role "assistant" :content "original"}
            update)))))

;; ---------------------------------------------------------------------------
;; Jar loading
;; ---------------------------------------------------------------------------

(deftest load-plugin-from-a-jar-and-run-its-hooks
  (let [ns-sym (unique-plugin-ns)
        jar    (write-plugin-jar! ns-sym)
        row    (assoc (plugin-row "jar-plugin" {:marker "marker"})
                      :jarPath jar
                      :entryNamespace (str ns-sym))]
    (try
      (is (= {:name "jar-plugin"
              :jarPath jar
              :namespace (str ns-sym)
              :hooks [:agent-message-post
                      :agent-message-pre
                      :system-prompt
                      :user-prompt-post
                      :user-prompt-pre]}
             (plugins/load-plugin! row)))

      (is (= "base|marker"
             (plugins/assemble-system-prompt {:sys-prompt "base"}
                                             (fn [ctx] (:sys-prompt ctx)))))
      (is (= [{:role "user" :content "hello"}
              {:role "user" :content "pre"}]
             (plugins/user-prompt-pre {:sessionId "s"}
                                      [{:role "user" :content "hello"}])))
      (finally
        (remove-ns ns-sym)))))

(deftest load-configured-loads-good-plugins-and-reports-bad-ones
  (let [ns-sym (unique-plugin-ns)
        jar    (write-plugin-jar! ns-sym)
        good   (assoc (plugin-row "good" {})
                      :jarPath jar
                      :entryNamespace (str ns-sym))
        bad    (assoc (plugin-row "bad" {})
                      :jarPath (str jar ".missing")
                      :entryNamespace "example.missing")]
    (try
      (with-redefs [store/list-enabled (fn [_] [bad good])]
        (is (= {:loaded ["good"] :failed ["bad"]}
               (plugins/load-configured! (postgres/make-client)))))
      (is (= ["good"] (map :name (plugins/active-plugins))))
      (finally
        (remove-ns ns-sym)))))
