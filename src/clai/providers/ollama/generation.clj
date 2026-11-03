(ns clai.providers.ollama.generation
  "Generation endpoints for the Ollama API.

  These functions take an explicit client; `clai.providers.ollama` wraps them
  with arities that fall back to the default `*client*`."
  (:require [clai.providers.ollama.http :as http]
            [clai.providers.ollama.schemas :as schemas]))

(defn generate
  "Implementation of `clai.providers.ollama/generate`."
  [client req]
  (schemas/validate! schemas/generate-request req "generate")
  (http/post client "/api/generate" req))

(defn chat
  "Implementation of `clai.providers.ollama/chat`."
  [client req]
  (schemas/validate! schemas/chat-request req "chat")
  (http/post client "/api/chat" req))

(defn embed
  "Implementation of `clai.providers.ollama/embed`."
  [client req]
  (schemas/validate! schemas/embed-request req "embed")
  (http/perform client :post "/api/embed" req))

(defn systemone
  "Implementation of `clai.providers.ollama/systemone`."
  [client req]
  (schemas/validate! schemas/systemone-request req "systemone")
  (http/perform client :post "/v1/systemone" req))
