(ns acp.schema.mcp-test

  (:require [clojure.test :as test]
            [malli.core :as m]
            [acp.schema.mcp :as mcp]))


;; -------------------------------------------------------------------------
;; Helpers
;; -------------------------------------------------------------------------

(defn- returned
  "Return the map produced by calling `f`, or the `ExceptionInfo` it threw."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      e)))


(def ^:private header {:name "Authorization" :value "Bearer token" :_meta nil})

(def ^:private env-var {:name "TOKEN" :value "secret" :_meta nil})

(def ^:private http-server
  {:type "http"
   :name "http"
   :url "https://example.test/mcp"
   :headers [header]
   :_meta nil})

(def ^:private sse-server
  {:type "sse"
   :name "sse"
   :url "https://example.test/mcp"
   :headers []
   :_meta nil})

(def ^:private stdio-server
  {:type "stdio"
   :name "stdio"
   :command "npx"
   :args ["-y" "server"]
   :env [env-var]
   :_meta nil})


;; -------------------------------------------------------------------------
;; HttpHeader
;; -------------------------------------------------------------------------

(test/deftest HttpHeader-schema
  (test/testing "requires a name and a value"
    (test/is (m/validate mcp/HttpHeader {:name "Authorization" :value "token"}))
    (test/is (not (m/validate mcp/HttpHeader {:name "Authorization"})))
    (test/is (not (m/validate mcp/HttpHeader {:value "token"}))))

  (test/testing "rejects malformed values"
    (test/is (not (m/validate mcp/HttpHeader {:name 1 :value "token"})))
    (test/is (not (m/validate mcp/HttpHeader {:name "a" :value nil})))

    (doseq [value [nil "Authorization" [:header] true 42]]
      (test/is (not (m/validate mcp/HttpHeader value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->HttpHeader
  (test/testing "fills the metadata slot with nil"
    (test/is (= {:name "Authorization" :value "token" :_meta nil}
                (mcp/->HttpHeader "Authorization" "token")))
    (test/is (= header
                (mcp/->HttpHeader "Authorization"
                                  "Bearer token"
                                  :meta nil))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate mcp/HttpHeader
                        (mcp/->HttpHeader "Authorization" "token"))))

  (test/testing "rejects invalid arguments"
    (test/is (thrown? clojure.lang.ExceptionInfo (mcp/->HttpHeader nil "token")))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->HttpHeader "Authorization" 1)))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->HttpHeader "Authorization" "token" :meta "meta")))))


;; -------------------------------------------------------------------------
;; EnvVariable
;; -------------------------------------------------------------------------

(test/deftest EnvVariable-schema
  (test/testing "requires a name and a value"
    (test/is (m/validate mcp/EnvVariable {:name "TOKEN" :value "secret"}))
    (test/is (not (m/validate mcp/EnvVariable {:name "TOKEN"})))
    (test/is (not (m/validate mcp/EnvVariable {:value "secret"}))))

  (test/testing "rejects malformed values"
    (test/is (not (m/validate mcp/EnvVariable {:name 1 :value "secret"})))
    (test/is (not (m/validate mcp/EnvVariable {:name "TOKEN" :value nil})))

    (doseq [value [nil "TOKEN" [:env] true 42]]
      (test/is (not (m/validate mcp/EnvVariable value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->EnvVariable
  (test/testing "fills the metadata slot with nil"
    (test/is (= {:name "TOKEN" :value "secret" :_meta nil}
                (mcp/->EnvVariable "TOKEN" "secret"))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate mcp/EnvVariable (mcp/->EnvVariable "TOKEN" "secret"))))

  (test/testing "rejects invalid arguments"
    (test/is (thrown? clojure.lang.ExceptionInfo (mcp/->EnvVariable nil "secret")))
    (test/is (thrown? clojure.lang.ExceptionInfo (mcp/->EnvVariable "TOKEN" nil)))))


;; -------------------------------------------------------------------------
;; Remote transports
;; -------------------------------------------------------------------------

(test/deftest McpServerHttp-and-Sse-schemas
  (test/testing "accept servers with headers"
    (test/is (m/validate mcp/McpServerHttp http-server))
    (test/is (m/validate mcp/McpServerSse sse-server))
    (test/is (m/validate mcp/McpServerHttp
                        {:name "http" :url "https://example.test" :headers []})))

  (test/testing "require a name, url and header vector"
    (doseq [schema [mcp/McpServerHttp mcp/McpServerSse]]
      (test/is (not (m/validate schema {:url "https://example.test"
                                        :headers []})))
      (test/is (not (m/validate schema {:name "x" :headers []})))
      (test/is (not (m/validate schema {:name "x" :url "https://e.test"})))
      (test/is (not (m/validate schema {:name "x"
                                        :url "https://e.test"
                                        :headers {}})))
      (test/is (not (m/validate schema {:name "x"
                                        :url "https://e.test"
                                        :headers [{:name "a"}]}))))))

(test/deftest ->McpServerHttp-and-Sse
  (test/testing "keep their argument order"
    (test/is (= {:name "http" :url "https://e.test" :headers [] :_meta nil}
                (mcp/->McpServerHttp "http" "https://e.test" [])))
    (test/is (= {:name "sse" :url "https://e.test" :headers [header] :_meta nil}
                (mcp/->McpServerSse "sse" "https://e.test" [header]))))

  (test/testing "reject invalid arguments"
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerHttp nil "https://e.test" [])))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerHttp "http" 1 [])))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerHttp "http" "https://e.test" nil)))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerSse "sse" "https://e.test" [{:name "a"}])))))


;; -------------------------------------------------------------------------
;; Stdio transport
;; -------------------------------------------------------------------------

(test/deftest McpServerStdio-schema
  (test/testing "accepts a command, args and env"
    (test/is (m/validate mcp/McpServerStdio stdio-server))
    (test/is (m/validate mcp/McpServerStdio
                        {:name "stdio"
                         :command "npx"
                         :args []
                         :env []})))

  (test/testing "requires every field"
    (test/is (not (m/validate mcp/McpServerStdio {})))
    (test/is (not (m/validate mcp/McpServerStdio {:name "stdio"})))
    (test/is (not (m/validate mcp/McpServerStdio {:name "stdio"
                                                  :command "npx"}))))

  (test/testing "rejects malformed fields"
    (test/is (not (m/validate mcp/McpServerStdio {:name "stdio"
                                                  :command "npx"
                                                  :args ["a" 1]
                                                  :env []})))
    (test/is (not (m/validate mcp/McpServerStdio {:name "stdio"
                                                  :command "npx"
                                                  :args []
                                                  :env [{:name "a"}]})))))


(test/deftest ->McpServerStdio
  (test/testing "keeps its argument order"
    (test/is (= (dissoc stdio-server :type)
                (mcp/->McpServerStdio "stdio"
                                      "npx"
                                      ["-y" "server"]
                                      [env-var])))
    (test/is (= {:name "stdio"
                 :command "npx"
                 :args []
                 :env []
                 :_meta {:traceId "t-1"}}
                (mcp/->McpServerStdio "stdio" "npx" [] []
                                      :meta {:traceId "t-1"}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate mcp/McpServerStdio
                        (mcp/->McpServerStdio "stdio" "npx" [] [])))
    ;; the transport payload itself carries no discriminator; `McpServer`
    ;; adds it
    (test/is (m/validate mcp/McpServer
                        (assoc (mcp/->McpServerStdio "stdio" "npx" [] [])
                               :type "stdio"))))

  (test/testing "rejects invalid arguments"
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerStdio nil "npx" [] [])))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerStdio "stdio" nil [] [])))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerStdio "stdio" "npx" nil [])))
    (test/is (thrown? clojure.lang.ExceptionInfo
                      (mcp/->McpServerStdio "stdio" "npx" [] [{}])))))


;; -------------------------------------------------------------------------
;; McpServer
;; -------------------------------------------------------------------------

(test/deftest McpServer-schema
  (test/testing "accepts every transport variant"
    (test/is (m/validate mcp/McpServer http-server))
    (test/is (m/validate mcp/McpServer sse-server))
    (test/is (m/validate mcp/McpServer stdio-server)))

  (test/testing "rejects unknown or missing discriminators"
    (test/is (not (m/validate mcp/McpServer {})))
    (test/is (not (m/validate mcp/McpServer
                              {:type "websocket"
                               :name "ws"
                               :url "wss://example.test"
                               :headers []})))
    (test/is (not (m/validate mcp/McpServer
                              {:name "http"
                               :url "https://example.test"
                               :headers []}))))

  (test/testing "rejects payloads that do not match their variant"
    (test/is (not (m/validate mcp/McpServer
                              {:type "http" :name "http" :url 1 :headers []})))
    (test/is (not (m/validate mcp/McpServer
                              {:type "stdio"
                               :name "stdio"
                               :command "npx"
                               :args "server"
                               :env []}))))

  (test/testing "rejects non-map values"
    (doseq [value [nil "http" [:http] true 42]]
      (test/is (not (m/validate mcp/McpServer value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->McpServer
  (test/testing "round-trips every transport variant"
    (doseq [server [http-server sse-server stdio-server]]
      (test/is (= server (mcp/->McpServer server)))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate mcp/McpServer (mcp/->McpServer stdio-server))))

  (test/testing "preserves extra keys"
    (test/is (= (assoc stdio-server :notInSpec true)
                (mcp/->McpServer (assoc stdio-server :notInSpec true)))))

  (test/testing "rejects invalid servers"
    (doseq [server [{}
                    {:type "websocket"}
                    {:type "http" :name "http"}
                    {:type "stdio" :name "stdio" :command "npx"}
                    nil
                    "http"
                    [:http]]]
      (test/is (thrown? clojure.lang.ExceptionInfo (mcp/->McpServer server))
               (str "expected " (pr-str server) " to be rejected"))))


  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(mcp/->McpServer {:type "websocket"}))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (= {:type "websocket"} (:value (ex-data e))))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid McpServer" (ex-message e))))))


;; -------------------------------------------------------------------------
;; McpCapabilities
;; -------------------------------------------------------------------------

(test/deftest McpCapabilities-schema
  (test/testing "accepts an empty map and transport flags"
    (test/is (m/validate mcp/McpCapabilities {}))
    (test/is (m/validate mcp/McpCapabilities {:http true}))
    (test/is (m/validate mcp/McpCapabilities {:sse false}))
    (test/is (m/validate mcp/McpCapabilities {:http true :sse true}))
    (test/is (m/validate mcp/McpCapabilities {:_meta {:traceId "t-1"}}))
    (test/is (m/validate mcp/McpCapabilities {:http true :notInSpec true})))

  (test/testing "rejects malformed capability values"
    (test/is (not (m/validate mcp/McpCapabilities {:http nil})))
    (test/is (not (m/validate mcp/McpCapabilities {:http "true"})))
    (test/is (not (m/validate mcp/McpCapabilities {:sse 1})))
    (test/is (not (m/validate mcp/McpCapabilities {:_meta "meta"})))
    (doseq [value [nil "capabilities" [:http] true 42]]
      (test/is (not (m/validate mcp/McpCapabilities value))
               (str "expected " (pr-str value) " to be rejected")))))


(test/deftest ->McpCapabilities
  (test/testing "fills the metadata slot with nil"
    (test/is (= {:_meta nil} (mcp/->McpCapabilities))))

  (test/testing "accepts transport flags and metadata"
    (test/is (= {:http true :_meta nil}
                (mcp/->McpCapabilities {:http true})))
    (test/is (= {:http true :sse true :_meta {:traceId "t-1"}}
                (mcp/->McpCapabilities {:http true
                                        :sse true
                                        :_meta {:traceId "t-1"}}))))

  (test/testing "returns a value that satisfies the schema"
    (test/is (m/validate mcp/McpCapabilities (mcp/->McpCapabilities)))
    (test/is (m/validate mcp/McpCapabilities
                        (mcp/->McpCapabilities {:sse true}))))

  (test/testing "rejects invalid arguments"
    (doseq [args [[{:http "yes"}]
                  [{:sse nil}]
                  [{:_meta "meta"}]]]
      (test/is (thrown? clojure.lang.ExceptionInfo
                        (apply mcp/->McpCapabilities args))
               (str "expected " (pr-str args) " to be rejected"))))

  (test/testing "throws an ex-info with schema, value and errors"
    (let [e (returned #(mcp/->McpCapabilities {:http "yes"}))]
      (test/is (instance? clojure.lang.ExceptionInfo e))
      (test/is (false? (m/validate (:schema (ex-data e))
                                   (:value (ex-data e)))))
      (test/is (some? (:errors (ex-data e))))
      (test/is (re-find #"Invalid McpCapabilities" (ex-message e))))))
