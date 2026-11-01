(ns clai.providers.ollama.models
  "Model management endpoints for the Ollama API.

  These functions take an explicit client; `clai.providers.ollama` wraps them
  with arities that fall back to the default `*client*`."
  (:require [clai.providers.ollama.http :as http]
            [clai.providers.ollama.schemas :as schemas]))

(defn list-models
  "Implementation of `clai.providers.ollama/list-models`."
  [client]
  (http/perform client :get "/api/tags" nil))

(defn list-running-models
  "Implementation of `clai.providers.ollama/list-running-models`."
  [client]
  (http/perform client :get "/api/ps" nil))

(defn show-model
  "Implementation of `clai.providers.ollama/show-model`."
  [client req]
  (schemas/validate! schemas/show-request req "show-model")
  (http/perform client :post "/api/show" req))

(defn create-model
  "Implementation of `clai.providers.ollama/create-model`."
  [client req]
  (schemas/validate! schemas/create-model-request req "create-model")
  (if (http/streaming? req)
    (http/perform-stream client :post "/api/create" req)
    (http/perform client :post "/api/create" req)))

(defn copy-model
  "Implementation of `clai.providers.ollama/copy-model`."
  [client req]
  (schemas/validate! schemas/copy-model-request req "copy-model")
  (http/perform client :post "/api/copy" req))

(defn pull-model
  "Implementation of `clai.providers.ollama/pull-model`."
  [client req]
  (schemas/validate! schemas/pull-push-request req "pull-model")
  (if (http/streaming? req)
    (http/perform-stream client :post "/api/pull" req)
    (http/perform client :post "/api/pull" req)))

(defn push-model
  "Implementation of `clai.providers.ollama/push-model`."
  [client req]
  (schemas/validate! schemas/pull-push-request req "push-model")
  (if (http/streaming? req)
    (http/perform-stream client :post "/api/push" req)
    (http/perform client :post "/api/push" req)))

(defn delete-model
  "Implementation of `clai.providers.ollama/delete-model`."
  [client req]
  (schemas/validate! schemas/delete-model-request req "delete-model")
  (http/perform client :delete "/api/delete" req))
