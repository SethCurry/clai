(ns clai.agent.providers.ollama
  "A Clojure client for the Ollama HTTP API.

  The Ollama API speaks JSON with snake_case keys; this client preserves
  those keys verbatim (for both requests and responses) so that the shape
  matches the official documentation at
  https://docs.ollama.com/api/introduction exactly.

  Two base URLs are supported:

    * a local Ollama server, by default `http://localhost:11434`
    * Ollama's hosted cloud, `https://ollama.com`

  Cloud requests require an API key, supplied via the `:api-key` option to
  `make-client` (or the `OLLAMA_API_KEY` environment variable).

  Streaming endpoints (`/api/generate`, `/api/chat`, `/api/create`,
  `/api/pull`, `/api/push`) default to streaming. When streaming, the client
  returns a lazy sequence of parsed event maps (one per NDJSON line); consume
  the sequence fully to release the underlying HTTP connection. Pass
  `:stream false` in the request to receive a single response map instead.

  This namespace is the public facade. The implementation is split across
  `clai.agent.providers.ollama.schemas`, `.image`, `.generation`, `.models`,
  `.blobs`, and `.cloud`; the HTTP plumbing lives in
  `clai.agent.providers.http` and the provider-neutral message helpers in
  `clai.agent.providers.messages`, both shared with the other providers.

  `clai.agent.providers` is the entry point that picks a provider from a model
  name and runs one chat turn.

  Example:

      (require '[clai.agent.providers.ollama :as ollama])

      (def c (ollama/make-client))              ;; localhost:11434
      (ollama/chat c {:model \"gemma4\"
                      :messages [{:role \"user\"
                                  :content \"Why is the sky blue?\"}]
                      :stream false})
      ;; => {:model \"gemma4\" :message {:role \"assistant\" :content \"...\"} ...}
  "
  (:require [clai.agent.providers.ollama.blobs :as blobs]
            [clai.agent.providers.ollama.cloud :as cloud]
            [clai.agent.providers.ollama.generation :as generation]
            [clai.agent.providers.ollama.image :as image]
            [clai.agent.providers.messages :as messages]
            [clai.agent.providers.ollama.models :as models]
            [clai.agent.providers.ollama.schemas :as schemas]
            [clai.events.schema :as events]
            [clai.log]
            [taoensso.telemere :as tel]))

;; ---------------------------------------------------------------------------
;; Schemas (re-exported from `clai.agent.providers.ollama.schemas`)
;; ---------------------------------------------------------------------------

(def non-empty-string schemas/non-empty-string)
(def image-source schemas/image-source)
(def TextContent schemas/TextContent)
(def ContentList schemas/ContentList)
(def ToolCallContent schemas/ToolCallContent)
(def UserPromptEvent schemas/UserPromptEvent)
(def AgentThoughtEvent schemas/AgentThoughtEvent)
(def AgentMessageEvent schemas/AgentMessageEvent)
(def ToolCallEvent schemas/ToolCallEvent)
(def ClojureFormEvent schemas/ClojureFormEvent)
(def AgentSessionEvent schemas/AgentSessionEvent)
(def AgentSession schemas/AgentSession)
(def client-opts schemas/client-opts)
(def client-schema schemas/client-schema)
(def generate-request schemas/generate-request)
(def ChatContent schemas/ChatContent)
(def chat-message schemas/chat-message)
(def chat-request schemas/chat-request)
(def embed-request schemas/embed-request)
(def systemone-request schemas/systemone-request)
(def show-request schemas/show-request)
(def create-model-request schemas/create-model-request)
(def copy-model-request schemas/copy-model-request)
(def pull-push-request schemas/pull-push-request)
(def delete-model-request schemas/delete-model-request)
(def model-entry schemas/model-entry)
(def list-models-response schemas/list-models-response)
(def version-response schemas/version-response)
(def balance-response schemas/balance-response)
(def cloud-usage-query schemas/cloud-usage-query)
(def cloud-usage-response schemas/cloud-usage-response)
(def digest schemas/digest)
(def blob-content schemas/blob-content)
(def stream-response schemas/stream-response)
(def chat-response schemas/chat-response)
(def assistant-message-schema schemas/assistant-message-schema)
(def tool-call schemas/tool-call)

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:private default-host "http://localhost:11434")

(def ^:private default-timeout-ms 600000) ; 10 minutes; models can be slow.

;; Keep the record in the public namespace so the class name remains
;; `clai.agent.providers.ollama.Client` for callers and existing tests.
(defrecord Client [provider host api-key timeout])

(def ^:no-doc
  ^:dynamic *client*
  (map->Client {:provider :ollama
                :host     default-host
                :api-key  nil
                :timeout  default-timeout-ms}))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defn make-client
  "Create an Ollama client.

  Options:

    :host      Base URL of the Ollama server.
               Defaults to `http://localhost:11434` (or the `OLLAMA_HOST`
               environment variable when set).
    :api-key   Bearer token for Ollama's cloud API. Defaults to the
               `OLLAMA_API_KEY` environment variable.
    :timeout   Request timeout in milliseconds. Defaults to 600000 (10 min).

  Returns a `clai.agent.providers.ollama.Client` record. With no arguments the
  default local client is returned."
  {:malli/schema [:function
                  [:=> [:cat] #'schemas/client-schema]
                  [:=> [:cat [:maybe #'schemas/client-opts]] #'schemas/client-schema]]}
  ([] (make-client nil))
  ([{:keys [host api-key timeout]
     :or {host     (or (System/getenv "OLLAMA_HOST") default-host)
          api-key  (System/getenv "OLLAMA_API_KEY")
          timeout  default-timeout-ms}}]
   (let [client (map->Client {:provider :ollama
                              :host     host
                              :api-key  api-key
                              :timeout  timeout})]
     (schemas/validate! schemas/client-schema client "make-client")
     (tel/log! {:level :debug
                :id    ::client-created
                :data  {:host     host
                        :api-key? (some? api-key)
                        :timeout  timeout}}
               "Created Ollama client")
     client)))

;; ---------------------------------------------------------------------------
;; Image helpers
;; ---------------------------------------------------------------------------

(def ^{:malli/schema [:-> #'schemas/image-source string?]} encode-image
  "Base64-encode an image for inclusion in an `:images` array.

  Accepts:

    * a `java.io.File` or path string
    * a byte array
    * a `java.io.InputStream`
    * a URL string/`java.net.URL` (the contents are fetched and encoded)

  Returns a base64 string ready to send to Ollama."
  image/encode-image)

;; ---------------------------------------------------------------------------
;; Generation endpoints
;; ---------------------------------------------------------------------------

(defn generate
  "Generate a response for a prompt. Calls `POST /api/generate`.

  Required: `:model`. Common options: `:prompt`, `:system`, `:images`,
  `:format`, `:options`, `:think`, `:keep_alive`, `:raw`, `:suffix`,
  `:logprobs`, `:top_logprobs`.

  Streaming is the default: a lazy sequence of event maps is returned.
  Pass `:stream false` to receive a single response map."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/generate-request]
                   [:or [:map] #'schemas/stream-response]]
                  [:=> [:cat #'schemas/client-schema #'schemas/generate-request]
                   [:or [:map] #'schemas/stream-response]]]}
  ([req] (generate *client* req))
  ([client req] (generation/generate client req)))

(defn chat
  "Generate the next chat message. Calls `POST /api/chat`.

  Required: `:model`, `:messages` (a vector of `{:role :content}` maps).
  Common options: `:tools`, `:format`, `:options`, `:think`, `:keep_alive`,
  `:logprobs`, `:top_logprobs`.

  Streaming is the default: a lazy sequence of event maps is returned.
  Pass `:stream false` to receive a single response map."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/chat-request]
                   [:or [:map] #'schemas/stream-response]]
                  [:=> [:cat #'schemas/client-schema #'schemas/chat-request]
                   [:or [:map] #'schemas/stream-response]]]}
  ([req] (chat *client* req))
  ([client req] (generation/chat client req)))

(defn embed
  "Generate vector embeddings for input text. Calls `POST /api/embed`.

  Required: `:model`, `:input` (a string or vector of strings). Options:
  `:truncate`, `:dimensions`, `:keep_alive`, `:options`.

  Always returns a single response map (this endpoint does not stream)."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/embed-request] [:maybe [:map]]]
                  [:=> [:cat #'schemas/client-schema #'schemas/embed-request]
                   [:maybe [:map]]]]}
  ([req] (embed *client* req))
  ([client req] (generation/embed client req)))

(defn systemone
  "Answer choice, yes/no, and scoring questions with a System One model.
  Calls `POST /v1/systemone`.

  Required: `:model`, `:state`, `:questions`. Option: `:keep_alive`,
  `:images`.

  Always returns a single response map (this endpoint does not stream)."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/systemone-request] [:maybe [:map]]]
                  [:=> [:cat #'schemas/client-schema #'schemas/systemone-request]
                   [:maybe [:map]]]]}
  ([req] (systemone *client* req))
  ([client req] (generation/systemone client req)))

;; ---------------------------------------------------------------------------
;; Model management endpoints
;; ---------------------------------------------------------------------------

(defn list-models
  "Fetch the list of locally available models. Calls `GET /api/tags`.
  Returns a map with a `:models` vector."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe #'schemas/list-models-response]]
                  [:=> [:cat #'schemas/client-schema]
                   [:maybe #'schemas/list-models-response]]]}
  ([ ] (list-models *client*))
  ([client] (models/list-models client)))

(defn list-running-models
  "Fetch the list of models currently loaded into memory. Calls `GET /api/ps`.
  Returns a map with a `:models` vector."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe #'schemas/list-models-response]]
                  [:=> [:cat #'schemas/client-schema]
                   [:maybe #'schemas/list-models-response]]]}
  ([ ] (list-running-models *client*))
  ([client] (models/list-running-models client)))

(defn show-model
  "Show details for a model. Calls `POST /api/show`.

  Required: `:model`. Option: `:verbose`."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/show-request] [:maybe [:map]]]
                  [:=> [:cat #'schemas/client-schema #'schemas/show-request]
                   [:maybe [:map]]]]}
  ([req] (show-model *client* req))
  ([client req] (models/show-model client req)))

(defn create-model
  "Create a model. Calls `POST /api/create`.

  Required: `:model`. Options: `:from`, `:files`, `:template`, `:renderer`,
  `:parser`, `:system`, `:parameters`, `:messages`, `:license`, `:quantize`,
  `:capabilities`, `:requires`.

  Streaming is the default: a lazy sequence of status-event maps is
  returned. Pass `:stream false` to receive the final status map."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/create-model-request]
                   [:or [:map] #'schemas/stream-response]]
                  [:=> [:cat #'schemas/client-schema #'schemas/create-model-request]
                   [:or [:map] #'schemas/stream-response]]]}
  ([req] (create-model *client* req))
  ([client req] (models/create-model client req)))

(defn copy-model
  "Copy a model to a new name. Calls `POST /api/copy`.

  Required: `:source`, `:destination`."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/copy-model-request] [:maybe [:map]]]
                  [:=> [:cat #'schemas/client-schema #'schemas/copy-model-request]
                   [:maybe [:map]]]]}
  ([req] (copy-model *client* req))
  ([client req] (models/copy-model client req)))

(defn pull-model
  "Download a model from a registry. Calls `POST /api/pull`.

  Required: `:model`. Option: `:insecure`.

  Streaming is the default: a lazy sequence of progress-event maps is
  returned. Pass `:stream false` to receive the final status map."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/pull-push-request]
                   [:or [:map] #'schemas/stream-response]]
                  [:=> [:cat #'schemas/client-schema #'schemas/pull-push-request]
                   [:or [:map] #'schemas/stream-response]]]}
  ([req] (pull-model *client* req))
  ([client req] (models/pull-model client req)))

(defn push-model
  "Publish a model to a registry. Calls `POST /api/push`.

  Required: `:model`. Option: `:insecure`.

  Streaming is the default: a lazy sequence of progress-event maps is
  returned. Pass `:stream false` to receive the final status map."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/pull-push-request]
                   [:or [:map] #'schemas/stream-response]]
                  [:=> [:cat #'schemas/client-schema #'schemas/pull-push-request]
                   [:or [:map] #'schemas/stream-response]]]}
  ([req] (push-model *client* req))
  ([client req] (models/push-model client req)))

(defn delete-model
  "Delete a model. Calls `DELETE /api/delete`.

  Required: `:model`."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/delete-model-request] [:maybe [:map]]]
                  [:=> [:cat #'schemas/client-schema #'schemas/delete-model-request]
                   [:maybe [:map]]]]}
  ([req] (delete-model *client* req))
  ([client req] (models/delete-model client req)))

;; ---------------------------------------------------------------------------
;; Blob endpoints
;; ---------------------------------------------------------------------------

(defn blob-exists?
  "Check whether a blob (model weights file) is already present. Calls
  `HEAD /api/blobs/{digest}`. Returns `true` when the blob exists (HTTP 200)
  and `false` otherwise (HTTP 404). Other errors are raised."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/digest] boolean?]
                  [:=> [:cat #'schemas/client-schema #'schemas/digest] boolean?]]}
  ([digest] (blob-exists? *client* digest))
  ([client digest] (blobs/blob-exists? client digest)))

(defn create-blob
  "Upload raw bytes as a blob. Calls `POST /api/blobs/{digest}`.

  `content` may be a byte array, `java.io.File`, path string, or
  `java.io.InputStream`. Returns `true` when the blob was created (HTTP 201)
  and `true` as well when it already existed (HTTP 200)."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/digest #'schemas/blob-content] boolean?]
                  [:=> [:cat #'schemas/client-schema #'schemas/digest
                        #'schemas/blob-content]
                   boolean?]]}
  ([digest content] (create-blob *client* digest content))
  ([client digest content] (blobs/create-blob client digest content)))

;; ---------------------------------------------------------------------------
;; Server / cloud metadata endpoints
;; ---------------------------------------------------------------------------

(defn version
  "Retrieve the Ollama server version. Calls `GET /api/version`.
  Returns a map such as `{:version \"0.12.6\"}`."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe #'schemas/version-response]]
                  [:=> [:cat #'schemas/client-schema]
                   [:maybe #'schemas/version-response]]]}
  ([ ] (version *client*))
  ([client] (cloud/version client)))

(defn cloud-usage
  "Retrieve cloud usage statistics. Calls `GET /api/usage` (cloud only).

  Query options: `:range` (`24h`, `7d`, `30d`), `:scope` (`self`, `team`),
  `:group_by` (`model`). These are sent as query parameters."
  {:malli/schema [:function
                  [:=> [:cat #'schemas/cloud-usage-query]
                   [:maybe #'schemas/cloud-usage-response]]
                  [:=> [:cat #'schemas/client-schema #'schemas/cloud-usage-query]
                   [:maybe #'schemas/cloud-usage-response]]]}
  ([opts] (cloud-usage *client* opts))
  ([client opts] (cloud/cloud-usage client opts)))

(defn balance
  "Check remaining usage credits. Calls `GET /api/balance` (cloud only).
  Returns a map with `:included` and `:purchased` balances."
  {:malli/schema [:function
                  [:=> [:cat] [:maybe #'schemas/balance-response]]
                  [:=> [:cat #'schemas/client-schema]
                   [:maybe #'schemas/balance-response]]]}
  ([ ] (balance *client*))
  ([client] (cloud/balance client)))

;; ---------------------------------------------------------------------------
;; Chat response and tool-call helpers
;; ---------------------------------------------------------------------------

(def ^{:malli/schema [:-> [:maybe #'schemas/chat-response]
                  #'schemas/assistant-message-schema]} assistant-message
  "Return the assistant `:message` map from an Ollama `/api/chat` response,
  normalized to at least a map with role `assistant` and empty content."
  messages/assistant-message)

(def ^{:malli/schema [:-> [:maybe [:map]] boolean?]} has-tool-calls?
  "True when an Ollama assistant message requests one or more tool calls."
  messages/has-tool-calls?)

(def ^{:malli/schema [:-> #'schemas/tool-call schemas/non-empty-string]}
  tool-call-id
  "Return the Ollama tool call's id, generating one when absent."
  messages/tool-call-id)

(def ^{:malli/schema [:-> #'schemas/tool-call [:maybe string?]]} tool-call-name
  "Extract the function name from an Ollama `tool_calls` entry."
  messages/tool-call-name)

(def ^{:malli/schema [:-> #'schemas/tool-call string?]} tool-call-title
  "Render a short human-readable title for an Ollama tool call."
  messages/tool-call-title)

(def ^{:malli/schema [:-> :any #'events/ToolCallStatus]} tool-result-status
  "The terminal status a tool call's recorded `result` implies: `failed` when
  the result is an error message, `completed` otherwise."
  messages/tool-result-status)

;; ---------------------------------------------------------------------------
;; Convenience: bind a default client
;; ---------------------------------------------------------------------------

(defmacro with-client
  "Bind `clai.agent.providers.ollama/*client*` for the body, so calls that omit an
  explicit client use `client`.

      (clai.agent.providers.ollama/with-client
        (clai.agent.providers.ollama/make-client {:host \"https://ollama.com\"
                                            :api-key key})
        (clai.agent.providers.ollama/chat {:model \"gemma4\" :messages ... :stream false}))"
  [client & body]
  `(binding [*client* ~client]
     ~@body))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "Print the Ollama server version as a smoke test of connectivity."
  {:malli/schema [:=> [:cat [:* string?]] :nil]}
  [& _]
  (let [c (make-client)]
    (println "Ollama" (:version (version c)))))
