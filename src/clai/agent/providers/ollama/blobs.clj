(ns clai.agent.providers.ollama.blobs
  "Blob upload/lookup endpoints for the Ollama API.

  These functions take an explicit client; `clai.agent.providers.ollama` wraps them
  with arities that fall back to the default `*client*`."
  (:require [clai.agent.providers.ollama.http :as http]
            [clai.util :as util]
            [taoensso.telemere :as tel]))

(defn- blob-path
  [digest]
  (str "/api/blobs/" digest))

(defn blob-exists?
  "Implementation of `clai.agent.providers.ollama/blob-exists?`."
  [client digest]
  (let [path (blob-path digest)
        resp (http/request client :head path nil)]
    (tel/log! {:level :debug
               :id    ::blob-exists
               :data  {:digest digest :status (:status resp)}}
              "Checked Ollama blob")
    (cond
      (= 200 (:status resp)) true
      (= 404 (:status resp)) false
      :else (http/throw-api-error :head (http/api-url client path)
                                  (:status resp) (:body resp)))))

(defn create-blob
  "Implementation of `clai.agent.providers.ollama/create-blob`."
  [client digest content]
  (let [path  (blob-path digest)
        bytes (or (util/->bytes content)
                  (throw (ex-info "Unsupported blob content"
                                  {:content (type content)})))
        resp  (http/request client :post path
                            {:body    bytes
                             :headers {"Content-Type" "application/octet-stream"}})]
    (tel/log! {:level :debug
               :id    ::create-blob
               :data  {:digest digest
                       :bytes  (alength ^bytes bytes)
                       :status (:status resp)}}
              "Uploaded Ollama blob")
    (when-not (http/ok? (:status resp))
      (http/throw-api-error :post (http/api-url client path)
                            (:status resp) (:body resp)))
    true))
