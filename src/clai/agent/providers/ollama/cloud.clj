(ns clai.agent.providers.ollama.cloud
  "Server and cloud metadata endpoints for the Ollama API.

  These functions take an explicit client; `clai.agent.providers.ollama` wraps them
  with arities that fall back to the default `*client*`."
  (:require [clai.agent.providers.ollama.http :as http]
            [clai.util :as util]
            [taoensso.telemere :as tel]))

(defn version
  "Implementation of `clai.agent.providers.ollama/version`."
  [client]
  (http/perform client :get "/api/version" nil))

(defn cloud-usage
  "Implementation of `clai.agent.providers.ollama/cloud-usage`."
  [client opts]
  (let [query (util/select-present opts [:range :scope :group_by])
        resp  (http/perform client :get "/api/usage" nil {:query-params query})]
    (tel/log! {:level :debug
               :id    ::cloud-usage
               :data  {:query query}}
              "Fetched Ollama cloud usage")
    resp))

(defn balance
  "Implementation of `clai.agent.providers.ollama/balance`."
  [client]
  (http/perform client :get "/api/balance" nil))
