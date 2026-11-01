(ns clai.providers.ollama.cloud
  "Server and cloud metadata endpoints for the Ollama API.

  These functions take an explicit client; `clai.providers.ollama` wraps them
  with arities that fall back to the default `*client*`."
  (:require [clai.providers.ollama.http :as http]
            [clj-http.client :as http-client]
            [taoensso.telemere :as tel]))

(defn version
  "Implementation of `clai.providers.ollama/version`."
  [client]
  (http/perform client :get "/api/version" nil))

(defn cloud-usage
  "Implementation of `clai.providers.ollama/cloud-usage`."
  [client opts]
  (let [url   (http/api-url client "/api/usage")
        qopts (into {} (filter val opts))
        opts* (-> (http/base-http-opts client)
                  (assoc :request-method :get
                         :url url
                         :query-params qopts
                         :headers (http/auth-headers client nil)
                         :as :byte-array))
        resp  (http-client/request opts*)]
    (tel/log! {:level :debug
               :id    ::cloud-usage
               :data  {:query  qopts
                       :status (:status resp)}}
              "Fetched Ollama cloud usage")
    (when-not (<= 200 (:status resp) 299)
      (http/throw-api-error :get url (:status resp) (:body resp)))
    (http/parse-response resp)))

(defn balance
  "Implementation of `clai.providers.ollama/balance`."
  [client]
  (http/perform client :get "/api/balance" nil))
