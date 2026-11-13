(ns clai.agent.providers.ollama.models
  "Model management endpoints for the Ollama API.

  These functions take an explicit client; `clai.agent.providers.ollama` wraps them
  with arities that fall back to the default `*client*`."
  (:require [clai.agent.providers.http :as http]
            [clai.agent.providers.ollama.schemas :as schemas]))

(defn list-models
  "Implementation of `clai.agent.providers.ollama/list-models`."
  [client]
  (http/perform client :get "/api/tags" nil))

(defn list-running-models
  "Implementation of `clai.agent.providers.ollama/list-running-models`."
  [client]
  (http/perform client :get "/api/ps" nil))

(defn show-model
  "Implementation of `clai.agent.providers.ollama/show-model`."
  [client req]
  (schemas/validate! schemas/show-request req "show-model")
  (http/perform client :post "/api/show" req))

(defn create-model
  "Implementation of `clai.agent.providers.ollama/create-model`."
  [client req]
  (schemas/validate! schemas/create-model-request req "create-model")
  (http/post client "/api/create" req))

(defn copy-model
  "Implementation of `clai.agent.providers.ollama/copy-model`."
  [client req]
  (schemas/validate! schemas/copy-model-request req "copy-model")
  (http/perform client :post "/api/copy" req))

(defn pull-model
  "Implementation of `clai.agent.providers.ollama/pull-model`."
  [client req]
  (schemas/validate! schemas/pull-push-request req "pull-model")
  (http/post client "/api/pull" req))

(defn push-model
  "Implementation of `clai.agent.providers.ollama/push-model`."
  [client req]
  (schemas/validate! schemas/pull-push-request req "push-model")
  (http/post client "/api/push" req))

(defn delete-model
  "Implementation of `clai.agent.providers.ollama/delete-model`."
  [client req]
  (schemas/validate! schemas/delete-model-request req "delete-model")
  (http/perform client :delete "/api/delete" req))
