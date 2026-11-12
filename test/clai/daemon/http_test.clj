(ns clai.daemon.http-test
  "Tests for the HTTP/WebSocket daemon's routing and message handling.

  The agent is replaced with an atom-backed stub so these tests do not need
  PostgreSQL or Ollama."
  (:require [cheshire.core :as json]
            [clai.agent.core :as agent]
            [clai.daemon.http :as http]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [ring.websocket :as ws])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]
           [java.util UUID]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(defn- new-session
  "Create a stub session in `store` with a random id."
  ([store cwd] (new-session store cwd nil))
  ([store cwd config-id]
   (let [id      (str (UUID/randomUUID))
         session (cond-> {:sessionId id
                          :cwd       (or cwd (str (.getCanonicalFile (io/file "."))))
                          :history   []}
                   config-id (assoc :configId config-id))]
     (swap! store assoc id session)
     session)))

(defn- with-fake-agent
  "Run `f` with the agent operations replaced by an atom-backed store."
  [store f]
  (with-redefs [agent/new-session!
                (fn [_ {:keys [cwd configId]}]
                  (let [session (new-session store cwd configId)]
                    (cond-> {:sessionId (:sessionId session)}
                      configId (assoc :configId configId))))

                agent/load-session!
                (fn [_ {:keys [sessionId]}]
                  (get @store (str sessionId)))

                agent/list-sessions!
                (fn [_ _]
                  {:sessions (mapv #(select-keys % [:sessionId :cwd :configId])
                                   (vals @store))})

                agent/session-cwd!
                (fn [_ {:keys [sessionId]}]
                  (get-in @store [(str sessionId) :cwd]))

                agent/list-plugins!
                (fn [_ _] {:plugins []})

                agent/delete-session!
                (fn [_ {:keys [sessionId]}]
                  (swap! store dissoc (str sessionId))
                  {})

                agent/rename-session!
                (fn [_ {:keys [sessionId title]}]
                  (when-let [session (get @store (str sessionId))]
                    (let [title   (when-not (str/blank? (str title))
                                    (str/trim (str title)))
                          updated (assoc session :title title)]
                      (swap! store assoc (str sessionId) updated)
                      (select-keys updated [:sessionId :title :cwd :configId]))))

                agent/run-prompt!
                (fn [_ {:keys [prompt on-update]}]
                  (when on-update
                    (on-update {:type    "agent_message"
                                :content [{:type    "text"
                                           :content (str "echo " prompt)}]}))
                  {:stopReason "end_turn"})]
    (f)))

(defn- state
  "Build daemon state around `store`, optionally with a static `root`."
  ([store] (state store (io/file ".")))
  ([store root]
   {:agent       {:model "test-model"}
    :root        root
    :nrepl-port  nil
    :connections (atom #{})
    :prompts     (atom #{})}))

(defn- request
  "Build a minimal Ring request. `body`, when present, is encoded as JSON.
  A `?query` suffix in `uri` is moved to `:query-string`."
  ([method uri] (request method uri nil))
  ([method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     {:request-method method
      :uri            path
      :path-info      path
      :query-string   query
      :headers        {"content-type" "application/json"}
      :body           (when (some? body)
                        (java.io.StringReader. (json/generate-string body)))})))

(defn- body-text
  "Read a response body, whether it is a string or a `File` from
  `ring.util.response/file-response`."
  [response]
  (let [body (:body response)]
    (if (instance? File body)
      (slurp body)
      body)))

(defn- response-body
  "Parse a JSON response body."
  [response]
  (json/parse-string (body-text response) true))

(defn- wait-until
  "Wait up to two seconds for `pred` to become truthy."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest health-reports-the-daemon-status
  (let [store (atom {})]
    (with-fake-agent store
      (fn []
        (let [handler (http/handler (state store))
              response (handler (request :get "/api/health"))
              body (response-body response)]
          (is (= 200 (:status response)))
          (is (= true (:ok body)))
          (is (= "test-model" (:model body)))
          (is (= 0 (:sessions body)))
          (is (nil? (:nreplPort body))))))))

(deftest health-reports-database-errors
  (let [store (atom {})]
    (with-fake-agent store
      (fn []
        (with-redefs [agent/list-sessions!
                      (fn [_ _] (throw (ex-info "database down" {})))]
          (let [handler (http/handler (state store))
                response (handler (request :get "/api/health"))
                body (response-body response)]
            (is (= 503 (:status response)))
            (is (= false (:ok body)))
            (is (= "error" (:database body)))
            (is (re-find #"database down" (:error body)))))))))

(deftest sessions-can-be-created-listed-loaded-and-deleted
  (let [store (atom {})]
    (with-fake-agent store
      (fn []
        (let [handler (http/handler (state store))
              created (handler (request :post "/api/sessions" {:cwd "/tmp/project"}))
              session (response-body created)
              id      (:sessionId session)]

          (is (= 201 (:status created)))
          (is (string? id))
          (is (= "/tmp/project" (:cwd session)))
          (is (= [] (:updates session)))

          (let [listed (response-body (handler (request :get "/api/sessions")))]
            (is (= [id] (mapv :sessionId (:sessions listed)))))

          (let [loaded (handler (request :get (str "/api/sessions/" id)))]
            (is (= 200 (:status loaded)))
            (is (= id (:sessionId (response-body loaded)))))

          (let [deleted (handler (request :delete (str "/api/sessions/" id)))]
            (is (= 200 (:status deleted)))
            (is (= id (:deleted (response-body deleted)))))
          (is (= 404 (:status (handler (request :get (str "/api/sessions/" id)))))))))))

(deftest session-titles-can-be-renamed-and-cleared
  (let [store (atom {})
        sent  (atom [])
        conn  {:send           #(swap! sent conj %)
               :nrepl          (atom nil)
               :prompt-running (atom false)}]
    (with-fake-agent store
      (fn []
        (let [daemon-state (state store)
              session      (new-session store "/tmp/project")
              id           (:sessionId session)]
          (http/handle-message! daemon-state conn
                                (json/generate-string
                                 {:type      "rename_session"
                                  :requestId "r1"
                                  :sessionId id
                                  :title     "  Fix the build  "}))
          (is (= "session_renamed" (:type (first @sent))))
          (is (= "Fix the build" (get-in (first @sent) [:session :title])))
          (is (= "Fix the build" (:title (get @store id))))

          (reset! sent [])
          (http/handle-message! daemon-state conn
                                (json/generate-string
                                 {:type      "rename_session"
                                  :requestId "r2"
                                  :sessionId id
                                  :title     ""}))
          (is (nil? (get-in (first @sent) [:session :title])))

          (reset! sent [])
          (http/handle-message! daemon-state conn
                                (json/generate-string
                                 {:type      "rename_session"
                                  :requestId "r3"
                                  :sessionId "999999999"
                                  :title     "Nope"}))
          (is (= "error" (:type (first @sent))))
          (is (= "r3" (:requestId (first @sent)))))))))


(deftest agent-configurations-can-be-listed-and-loaded
  (let [configs [{:configId "1" :name "fast" :model "gemma3"}
                 {:configId "2" :name "careful"}]]
    (with-fake-agent (atom {})
      (fn []
        (with-redefs [agent/list-agent-configs!
                      (fn [_ _] {:configs configs})
                      agent/load-agent-config!
                      (fn [_ {:keys [configId name]}]
                        (cond
                          configId (first (filter #(= configId (:configId %))
                                                  configs))
                          name     (first (filter #(= name (:name %)) configs))
                          :else    nil))]
          (let [handler (http/handler (state (atom {})))]
            (is (= {:configs configs}
                   (response-body (handler (request :get "/api/agent-configs")))))
            (is (= (first configs)
                   (response-body (handler (request :get "/api/agent-configs/1")))))
            (is (= 404 (:status (handler (request :get "/api/agent-configs/999")))))))))))

(deftest installed-plugins-can-be-listed
  (let [store   (atom {})
        sent    (atom [])
        conn    {:send           #(swap! sent conj %)
                 :nrepl          (atom nil)
                 :prompt-running (atom false)}
        plugins [{:pluginId "1" :name "todays-date" :enabled true}
                 {:pluginId "2" :name "audit" :enabled false}]]
    (with-fake-agent store
      (fn []
        (with-redefs [agent/list-plugins! (fn [_ _] {:plugins plugins})]
          (let [handler (http/handler (state store))]
            (is (= {:plugins plugins}
                   (response-body (handler (request :get "/api/plugins"))))))
          (http/handle-message! (state store) conn
                                (json/generate-string
                                 {:type "list_plugins" :requestId "r-plugins"}))
          (is (= {:type "plugins"
                  :requestId "r-plugins"
                  :plugins plugins}
                 (first @sent))))))))

(deftest agent-configurations-can-be-created-updated-and-deleted
  (with-fake-agent (atom {})
    (fn []
      (with-redefs [agent/create-agent-config!
                    (fn [_ config]
                      (assoc config :configId "10"))

                    agent/update-agent-config!
                    (fn [_ {:keys [configId] :as config}]
                      (when (= "10" configId)
                        (assoc config :configId configId)))

                    agent/delete-agent-config!
                    (fn [_ {:keys [configId]}]
                      (= "10" configId))]
        (let [handler (http/handler (state (atom {})))
              created (handler (request :post "/api/agent-configs"
                                        {:name "fast" :model "gemma3"}))]
          (is (= 201 (:status created)))
          (is (= {:configId "10" :name "fast" :model "gemma3"}
                 (response-body created)))

          (let [updated (handler (request :put "/api/agent-configs/10"
                                          {:name "fast" :model "tiny"}))]
            (is (= 200 (:status updated)))
            (is (= {:configId "10" :name "fast" :model "tiny"}
                   (response-body updated))))

          (is (= 404 (:status (handler (request :put "/api/agent-configs/99"
                                                {:name "missing"})))))

          (let [deleted (handler (request :delete "/api/agent-configs/10"))]
            (is (= 200 (:status deleted)))
            (is (= "10" (:deleted (response-body deleted)))))

          (is (= 404 (:status (handler (request :delete
                                                "/api/agent-configs/99"))))))))))

(deftest sessions-remember-their-agent-configuration
  (let [store (atom {})]
    (with-fake-agent store
      (fn []
        (let [handler (http/handler (state store))
              created (handler (request :post "/api/sessions"
                                        {:cwd "/tmp/project" :configId "3"}))
              session (response-body created)]
          (is (= 201 (:status created)))
          (is (= "3" (:configId session)))
          (is (= [{:sessionId (:sessionId session)
                   :cwd       "/tmp/project"
                   :configId  "3"}]
                 (:sessions (response-body
                             (handler (request :get "/api/sessions")))))))))))

(deftest websocket-upgrades-only-from-loopback-browsers
  (let [store (atom {})]
    (with-fake-agent store
      (fn []
        (let [handler (http/handler (state store))
              upgrade (fn [origin]
                        (cond-> (-> (request :get "/ws")
                                    (assoc-in [:headers "upgrade"] "websocket")
                                    (assoc-in [:headers "connection"] "Upgrade"))
                          origin (assoc-in [:headers "origin"] origin)))]
          (is (ws/websocket-response? (handler (upgrade nil))))
          (is (ws/websocket-response? (handler (upgrade "http://127.0.0.1:5173"))))
          (is (ws/websocket-response? (handler (upgrade "http://localhost:5173"))))
          (is (= 403 (:status (handler (upgrade "https://evil.example"))))))))))

(deftest unknown-api-routes-are-404s
  (with-fake-agent (atom {})
    (fn []
      (let [handler (http/handler (state (atom {})))]
        (is (= 404 (:status (handler (request :get "/api/nope")))))))))

(deftest serves-the-react-build-with-a-client-side-fallback
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "clai-web-" (UUID/randomUUID)))]
    (try
      (.mkdirs (io/file root "assets"))
      (spit (io/file root "index.html") "<html>clai</html>")
      (spit (io/file root "assets" "app.js") "console.log('clai')")
      (with-fake-agent (atom {})
        (fn []
          (let [handler (http/handler (state (atom {}) root))]
            (is (= "<html>clai</html>"
                   (body-text (handler (request :get "/")))))
            (is (= "console.log('clai')"
                   (body-text (handler (request :get "/assets/app.js")))))
            (is (= "<html>clai</html>"
                   (body-text (handler (request :get "/sessions/1"))))))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(deftest websocket-messages-drive-the-agent
  (let [store (atom {})
        sent  (atom [])
        conn  {:send           #(swap! sent conj %)
               :nrepl          (atom nil)
               :prompt-running (atom false)}]
    (with-fake-agent store
      (fn []
        (let [daemon-state (state store)]
          (http/handle-message! daemon-state conn
                                "{\"type\":\"list_sessions\",\"requestId\":\"r1\"}")
          (is (= {:type "sessions" :requestId "r1" :sessions []}
                 (first @sent)))

          (reset! sent [])
          (with-redefs [agent/list-agent-configs!
                        (fn [_ _] {:configs [{:configId "3" :name "fast"}]})]
            (http/handle-message! daemon-state conn
                                  "{\"type\":\"list_agent_configs\",\"requestId\":\"r-config\"}")
            (is (= {:type "agent_configs"
                    :requestId "r-config"
                    :configs [{:configId "3" :name "fast"}]}
                   (first @sent))))
          (reset! sent [])
          (http/handle-message! daemon-state conn
                                "{\"type\":\"new_session\",\"requestId\":\"r2\",\"cwd\":\"/tmp\"}")
          (let [id (get-in (first @sent) [:session :sessionId])]
            (is (string? id))
            (reset! sent [])
            (http/handle-message! daemon-state conn
                                  (json/generate-string
                                   {:type      "prompt"
                                    :requestId "r3"
                                    :sessionId id
                                    :prompt    "hi"}))
            (is (wait-until #(some (fn [message]
                                     (= "prompt_done" (:type message)))
                                   @sent)))
            (is (some #(= "user_prompt" (get-in % [:update :type])) @sent))
            (is (some #(= "agent_message" (get-in % [:update :type])) @sent))
            (is (some #(= "echo hi" (get-in % [:update :content 0 :content]))
                      @sent))))))))

(deftest prompts-are-serialized-per-session-across-connections
  (let [store   (atom {})
        started (promise)
        release (promise)
        sent-a  (atom [])
        sent-b  (atom [])
        conn-a  {:send           #(swap! sent-a conj %)
                 :nrepl          (atom nil)
                 :prompt-running (atom false)}
        conn-b  {:send           #(swap! sent-b conj %)
                 :nrepl          (atom nil)
                 :prompt-running (atom false)}]
    (with-fake-agent store
      (fn []
        (with-redefs [agent/run-prompt!
                      (fn [_ {:keys [on-update]}]
                        (deliver started true)
                        @release
                        (on-update {:type    "agent_message"
                                    :content [{:type    "text"
                                               :content "done"}]})
                        {:stopReason "end_turn"})]
          (let [daemon-state (state store)
                id           (:sessionId (new-session store "/tmp"))]
            (#'http/run-prompt! daemon-state conn-a "r1" id "first")
            (is (true? (deref started 2000 false)))

            (#'http/run-prompt! daemon-state conn-b "r2" id "second")
            (is (= "error" (:type (first @sent-b))))
            (is (re-find #"already running for this session"
                         (:message (first @sent-b))))
            (is (false? @(:prompt-running conn-b)))

            (deliver release true)
            (is (wait-until #(some (fn [message]
                                     (= "prompt_done" (:type message)))
                                   @sent-a)))
            (is (wait-until #(empty? @(:prompts daemon-state))))
            (is (false? @(:prompt-running conn-a)))))))))

(deftest json-encoding-handles-instants
  (is (= {"start_time" "1970-01-01T00:00:00Z"}
         (#'http/json-safe {:start_time (Instant/ofEpochMilli 0)}))))

(deftest session-completions-list-the-working-directory
  (let [store (atom {})
        root  (.toFile (Files/createTempDirectory
                        "clai-completions-" (make-array FileAttribute 0)))]
    (try
      (spit (io/file root "README.md") "")
      (.mkdirs (io/file root "src"))
      (with-fake-agent store
        (fn []
          (let [handler (http/handler (state store))
                session (new-session store (str root))
                id      (:sessionId session)
                response (handler (request :get
                                           (str "/api/sessions/" id
                                                "/completions?path=s")))
                body     (response-body response)]
            (is (= 200 (:status response)))
            (is (= {:directory ""
                    :prefix    "s"
                    :entries   [{:name "src" :path "src/" :type "directory"}]}
                   body))

            (is (= 404 (:status (handler (request :get
                                                  "/api/sessions/nope/completions"))))))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (io/delete-file file true))))))
