(ns clai.providers.ollama.schemas
  "Malli schemas and request validation for the Ollama client."
  (:require [clai.log]
            [clai.util :as util]
            [malli.core :as m]
            [malli.error :as me]
            [taoensso.telemere :as tel])
  (:import [java.io InputStream]
           [java.net URL]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  util/non-empty-string)

(def image-source
  "Anything `encode-image` accepts: bytes, InputStream, File, URL, or path string."
  [:or
   [:maybe bytes?]
   [:maybe [:fn #(instance? InputStream %)]]
   [:maybe [:fn #(instance? java.io.File %)]]
   [:maybe [:fn #(instance? URL %)]]
   [:maybe string?]])

(def client-opts
  "Options accepted by `make-client`."
  [:map
   [:host {:optional true} string?]
   [:api-key {:optional true} [:maybe string?]]
   [:timeout {:optional true} pos-int?]])

(def client-schema
  "The `Client` record."
  [:map
   [:host string?]
   [:api-key [:maybe string?]]
   [:timeout pos-int?]])

(def generate-request
  "Request body for `POST /api/generate`."
  [:map
   [:model non-empty-string]
   [:prompt {:optional true} string?]
   [:system {:optional true} string?]
   [:images {:optional true} [:sequential string?]]
   [:format {:optional true} [:or string? [:map]]]
   [:options {:optional true} [:map]]
   [:think {:optional true} boolean?]
   [:keep_alive {:optional true} [:or string? number?]]
   [:raw {:optional true} boolean?]
   [:suffix {:optional true} string?]
   [:logprobs {:optional true} boolean?]
   [:top_logprobs {:optional true} pos-int?]
   [:stream {:optional true} boolean?]])

(def chat-message
  "A single chat message in `:messages`."
  [:map
   [:role non-empty-string]
   [:content {:optional true} [:or string? [:sequential [:map]]]]
   [:thinking {:optional true} [:maybe string?]]
   [:images {:optional true} [:sequential string?]]
   [:tool_calls {:optional true} [:sequential [:map]]]])

(def chat-request
  "Request body for `POST /api/chat`."
  [:map
   [:model non-empty-string]
   [:messages [:sequential chat-message]]
   [:tools {:optional true} [:sequential [:map]]]
   [:format {:optional true} [:or string? [:map]]]
   [:options {:optional true} [:map]]
   [:think {:optional true} boolean?]
   [:keep_alive {:optional true} [:or string? number?]]
   [:logprobs {:optional true} boolean?]
   [:top_logprobs {:optional true} pos-int?]
   [:stream {:optional true} boolean?]])

(def embed-request
  "Request body for `POST /api/embed`."
  [:map
   [:model non-empty-string]
   [:input [:or string? [:sequential string?]]]
   [:truncate {:optional true} boolean?]
   [:dimensions {:optional true} pos-int?]
   [:keep_alive {:optional true} [:or string? number?]]
   [:options {:optional true} [:map]]])

(def systemone-request
  "Request body for `POST /v1/systemone`."
  [:map
   [:model non-empty-string]
   [:state string?]
   [:questions [:sequential [:map]]]
   [:keep_alive {:optional true} [:or string? number?]]
   [:images {:optional true} [:sequential string?]]])

(def show-request
  "Request body for `POST /api/show`."
  [:map
   [:model non-empty-string]
   [:verbose {:optional true} boolean?]])

(def create-model-request
  "Request body for `POST /api/create`."
  [:map
   [:model non-empty-string]
   [:from {:optional true} string?]
   [:files {:optional true} [:sequential string?]]
   [:template {:optional true} string?]
   [:renderer {:optional true} string?]
   [:parser {:optional true} string?]
   [:system {:optional true} string?]
   [:parameters {:optional true} [:map]]
   [:messages {:optional true} [:sequential [:map]]]
   [:license {:optional true} [:or string? [:sequential string?]]]
   [:quantize {:optional true} string?]
   [:capabilities {:optional true} [:sequential string?]]
   [:requires {:optional true} [:sequential string?]]
   [:stream {:optional true} boolean?]])

(def copy-model-request
  "Request body for `POST /api/copy`."
  [:map
   [:source non-empty-string]
   [:destination non-empty-string]])

(def pull-push-request
  "Request body for `POST /api/pull` and `POST /api/push`."
  [:map
   [:model non-empty-string]
   [:insecure {:optional true} boolean?]
   [:stream {:optional true} boolean?]])

(def delete-model-request
  "Request body for `DELETE /api/delete`."
  [:map
   [:model non-empty-string]])

(def model-entry
  "An entry in the `:models` vector returned by `list-models`/`list-running-models`."
  [:map
   [:name {:optional true} string?]
   [:model {:optional true} string?]
   [:size {:optional true} number?]
   [:digest {:optional true} string?]
   [:modified_at {:optional true} string?]])

(def list-models-response
  "Response of `GET /api/tags` and `GET /api/ps`."
  [:map
   [:models {:optional true} [:sequential model-entry]]])

(def version-response
  "Response of `GET /api/version`."
  [:map [:version string?]])

(def balance-response
  "Response of `GET /api/balance`."
  [:map
   [:included {:optional true} [:map]]
   [:purchased {:optional true} [:map]]])

(def cloud-usage-query
  "Query parameters accepted by `cloud-usage`."
  [:map
   [:range {:optional true} [:enum "24h" "7d" "30d"]]
   [:scope {:optional true} [:enum "self" "team"]]
   [:group_by {:optional true} [:enum "model"]]])

(def cloud-usage-response
  "Response of `GET /api/usage` (cloud only)."
  [:map])

(def digest
  "A blob digest, e.g. `sha256:abc123...`."
  [:re #"sha256:[0-9a-fA-F]+"])

(def blob-content
  "Anything `create-blob` accepts: bytes, InputStream, File, or path string."
  [:or
   [:fn bytes?]
   [:fn #(instance? InputStream %)]
   [:fn #(instance? java.io.File %)]
   string?])

(def stream-response
  "A lazy sequence of parsed NDJSON event maps. Validated structurally (as a
  sequence) so that validation does not consume the stream."
  [:fn sequential?])

(def chat-response
  "The parts of an Ollama `/api/chat` response this namespace inspects."
  [:map
   [:message {:optional true} [:map]]])

(def assistant-message-schema
  "The normalized assistant message returned by `assistant-message`."
  [:map
   [:role string?]
   [:content {:optional true} [:or string? [:sequential [:map]]]]
   [:thinking {:optional true} [:maybe string?]]
   [:images {:optional true} [:sequential string?]]
   [:tool_calls {:optional true} [:sequential [:map]]]])

(def tool-call
  "One entry of an Ollama `tool_calls` vector. Both the nested
  `{:function {:name ... :arguments ...}}` and flattened `{:name ...}` shapes
  are accepted, so this schema is intentionally open."
  [:map])

(defn validate!
  "Validate `value` against `schema`, returning `value` when valid or throwing
  an `ex-info` with a human-readable malli error explanation otherwise."
  [schema value fn-name]
  (if-let [errors (and schema (m/explain schema value))]
    (let [explained (me/humanize errors)]
      (tel/log! {:level :error
                 :id    ::invalid-request
                 :data  {:fn fn-name :errors explained}}
                (str "Invalid " fn-name " request"))
      (throw (ex-info (str "Invalid " fn-name " request: "
                           (pr-str explained))
                      {:fn fn-name :schema schema :value value :errors errors})))
    value))
