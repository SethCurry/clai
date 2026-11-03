(ns acp.schema.fs
  "File system schemas for the `fs/read_text_file` and
  `fs/write_text_file` client methods."
  (:require [acp.schema-runtime :as rt]
            [acp.schema.common :as common]))


;; Schemas refer to one another, so declare every name up front.
(declare
 FileSystemCapabilities
 ReadTextFileRequest
 ReadTextFileResponse
 WriteTextFileRequest
 WriteTextFileResponse)


;; -------------------------------------------------------------------------
;; FileSystemCapabilities
;; -------------------------------------------------------------------------

(def FileSystemCapabilities
  "File system capabilities that a client may support. See protocol docs: [FileSystem](https://agentclientprotocol.com/protocol/v1/initialization#filesystem)"
  [:map
   {:closed false}
   [:readTextFile {:optional true} :boolean]
   [:writeTextFile {:optional true} :boolean]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->FileSystemCapabilities
  "Create a `FileSystemCapabilities` value."
  [& {:keys [readTextFile writeTextFile meta]}]
  (let [value (cond-> {:_meta meta}
                (some? readTextFile)  (assoc :readTextFile readTextFile)
                (some? writeTextFile) (assoc :writeTextFile writeTextFile))]
    (rt/validate! #'FileSystemCapabilities value "FileSystemCapabilities")))


;; -------------------------------------------------------------------------
;; ReadTextFileRequest
;; -------------------------------------------------------------------------

(def ReadTextFileRequest
  "Request to read content from a text file. Only available if the client supports the `fs.readTextFile` capability."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:path :string]
   [:line {:optional true} [:maybe [:int {:min 0}]]]
   [:limit {:optional true} [:maybe [:int {:min 0}]]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ReadTextFileRequest
  "Create a `ReadTextFileRequest` value."
  [sessionId path & {:keys [line limit meta]}]
  (let [value {:sessionId sessionId
               :path      path
               :line      line
               :limit     limit
               :_meta     meta}]
    (rt/validate! #'ReadTextFileRequest value "ReadTextFileRequest")))


;; -------------------------------------------------------------------------
;; ReadTextFileResponse
;; -------------------------------------------------------------------------

(def ReadTextFileResponse
  "Response containing the contents of a text file."
  [:map
   {:closed false}
   [:content :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ReadTextFileResponse
  "Create a `ReadTextFileResponse` value."
  [content & {:keys [meta]}]
  (let [value {:content content
               :_meta   meta}]
    (rt/validate! #'ReadTextFileResponse value "ReadTextFileResponse")))


;; -------------------------------------------------------------------------
;; WriteTextFileRequest
;; -------------------------------------------------------------------------

(def WriteTextFileRequest
  "Request to write content to a text file. Only available if the client supports the `fs.writeTextFile` capability."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:path :string]
   [:content :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->WriteTextFileRequest
  "Create a `WriteTextFileRequest` value."
  [sessionId path content & {:keys [meta]}]
  (let [value {:sessionId sessionId
               :path      path
               :content   content
               :_meta     meta}]
    (rt/validate! #'WriteTextFileRequest value "WriteTextFileRequest")))


;; -------------------------------------------------------------------------
;; WriteTextFileResponse
;; -------------------------------------------------------------------------

(def WriteTextFileResponse
  "Response to `fs/write_text_file`"
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->WriteTextFileResponse
  "Create a `WriteTextFileResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'WriteTextFileResponse value "WriteTextFileResponse")))
