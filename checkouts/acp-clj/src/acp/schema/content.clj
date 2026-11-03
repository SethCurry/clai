(ns acp.schema.content
  (:require [acp.schema-runtime :as rt]))


;; -------------------------------------------------------------------------
;; Role
;; -------------------------------------------------------------------------

(def Role
  "The sender or recipient of messages and data in a conversation."
  [:enum "assistant" "user"])

(def ->Role
  "Create a `Role` value."
  (rt/make-value-constructor #'Role "Role"))


;; -------------------------------------------------------------------------
;; Annotations
;; -------------------------------------------------------------------------

(def Annotations
  "Optional annotations for the client. The client can use annotations to inform how objects are used or displayed"
  [:map
   {:closed false}
   [:audience {:optional true} [:maybe [:vector #'Role]]]
   [:lastModified {:optional true} [:maybe :string]]
   [:priority {:optional true} [:maybe number?]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->Annotations
  "Create a `Annotations` value."
  [& {:keys [audience lastModified priority meta]}]
  (let [value {:audience     audience
               :lastModified lastModified
               :priority     priority
               :_meta        meta}]
    (rt/validate! #'Annotations value "Annotations")))


;; -------------------------------------------------------------------------
;; TextContent
;; -------------------------------------------------------------------------

(def TextContent
  "Text provided to or from an LLM."
  [:map
   {:closed false}
   [:annotations {:optional true} [:maybe #'Annotations]]
   [:text :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->TextContent
  "Create a `TextContent` value."
  [text & {:keys [annotations meta]}]
  (let [value {:text        text
               :annotations annotations
               :type "text"
               :_meta       meta}]
    (rt/validate! #'TextContent value "TextContent")))


;; -------------------------------------------------------------------------
;; ImageContent
;; -------------------------------------------------------------------------

(def ImageContent
  "An image provided to or from an LLM."
  [:map
   {:closed false}
   [:annotations {:optional true} [:maybe #'Annotations]]
   [:data :string]
   [:mimeType :string]
   [:uri {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ImageContent
  "Create a `ImageContent` value."
  [data mimeType & {:keys [annotations uri meta]}]
  (let [value {:annotations annotations
               :data        data
               :mimeType    mimeType
               :uri         uri
               :_meta       meta}]
    (rt/validate! #'ImageContent value "ImageContent")))


;; -------------------------------------------------------------------------
;; AudioContent
;; -------------------------------------------------------------------------

(def AudioContent
  "Audio provided to or from an LLM."
  [:map
   {:closed false}
   [:annotations {:optional true} [:maybe #'Annotations]]
   [:data :string]
   [:mimeType :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AudioContent
  "Create a `AudioContent` value."
  [data mimeType & {:keys [annotations meta]}]
  (let [value {:annotations annotations
               :data        data
               :mimeType    mimeType
               :_meta       meta}]
    (rt/validate! #'AudioContent value "AudioContent")))


;; -------------------------------------------------------------------------
;; ResourceLink
;; -------------------------------------------------------------------------

(def ResourceLink
  "A resource that the server is capable of reading, included in a prompt or tool call result."
  [:map
   {:closed false}
   [:annotations {:optional true} [:maybe #'Annotations]]
   [:description {:optional true} [:maybe :string]]
   [:mimeType {:optional true} [:maybe :string]]
   [:name :string]
   [:size {:optional true} [:maybe :int]]
   [:title {:optional true} [:maybe :string]]
   [:uri :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ResourceLink
  "Create a `ResourceLink` value."
  [name uri & {:keys [annotations description mimeType size title meta]}]
  (let [value {:annotations annotations
               :description description
               :mimeType    mimeType
               :name        name
               :size        size
               :title       title
               :uri         uri
               :_meta       meta}]
    (rt/validate! #'ResourceLink value "ResourceLink")))


;; -------------------------------------------------------------------------
;; TextResourceContents
;; -------------------------------------------------------------------------

(def TextResourceContents
  "Text-based resource contents."
  [:map
   {:closed false}
   [:mimeType {:optional true} [:maybe :string]]
   [:text :string]
   [:uri :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->TextResourceContents
  "Create a `TextResourceContents` value."
  [text uri & {:keys [mimeType meta]}]
  (let [value {:mimeType mimeType
               :text     text
               :uri      uri
               :_meta    meta}]
    (rt/validate! #'TextResourceContents value "TextResourceContents")))


;; -------------------------------------------------------------------------
;; BlobResourceContents
;; -------------------------------------------------------------------------

(def BlobResourceContents
  "Binary resource contents."
  [:map
   {:closed false}
   [:blob :string]
   [:mimeType {:optional true} [:maybe :string]]
   [:uri :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->BlobResourceContents
  "Create a `BlobResourceContents` value."
  [blob uri & {:keys [mimeType meta]}]
  (let [value {:blob     blob
               :mimeType mimeType
               :uri      uri
               :_meta    meta}]
    (rt/validate! #'BlobResourceContents value "BlobResourceContents")))


;; -------------------------------------------------------------------------
;; EmbeddedResourceResource
;; -------------------------------------------------------------------------

(def EmbeddedResourceResource
  "Resource content that can be embedded in a message."
  [:or #'TextResourceContents #'BlobResourceContents])

(def ->EmbeddedResourceResource
  "Create a `EmbeddedResourceResource` value."
  (rt/make-value-constructor
    #'EmbeddedResourceResource
    "EmbeddedResourceResource"))


;; -------------------------------------------------------------------------
;; EmbeddedResource
;; -------------------------------------------------------------------------

(def EmbeddedResource
  "The contents of a resource, embedded into a prompt or tool call result."
  [:map
   {:closed false}
   [:annotations {:optional true} [:maybe #'Annotations]]
   [:resource #'EmbeddedResourceResource]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->EmbeddedResource
  "Create a `EmbeddedResource` value."
  [resource & {:keys [annotations meta]}]
  (let [value {:annotations annotations
               :resource    resource
               :_meta       meta}]
    (rt/validate! #'EmbeddedResource value "EmbeddedResource")))


;; -------------------------------------------------------------------------
;; ContentBlock
;; -------------------------------------------------------------------------

(def ContentBlock
  "Content blocks represent displayable information in the Agent Client Protocol. They provide a structured way to handle various types of user-facing content—whether it's text from language models, images for analysis, or embedded resources for context. Content blocks appear in: - User prompts sent via `session/prompt` - Language model output streamed through `session/update` notifications - Progress updates and results from tool calls This structure is compatible with the Model Context Protocol (MCP), enabling agents to seamlessly forward content from MCP tool outputs without transformation. See protocol docs: [Content](https://agentclientprotocol.com/protocol/v1/content)"
  [:or
   [:and [:map {:closed false} [:type [:= "text"]]] #'TextContent]
   [:and [:map {:closed false} [:type [:= "image"]]] #'ImageContent]
   [:and [:map {:closed false} [:type [:= "audio"]]] #'AudioContent]
   [:and [:map {:closed false} [:type [:= "resource_link"]]] #'ResourceLink]
   [:and [:map {:closed false} [:type [:= "resource"]]] #'EmbeddedResource]])

(def ->ContentBlock
  "Create a `ContentBlock` value."
  (rt/make-value-constructor #'ContentBlock "ContentBlock"))


;; -------------------------------------------------------------------------
;; Content
;; -------------------------------------------------------------------------

(def Content
  "Standard content block (text, images, resources)."
  [:map
   {:closed false}
   [:content #'ContentBlock]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->Content
  "Create a `Content` value."
  [content & {:keys [meta]}]
  (let [value {:content content
               :_meta   meta}]
    (rt/validate! #'Content value "Content")))


;; -------------------------------------------------------------------------
;; Diff
;; -------------------------------------------------------------------------

(def Diff
  "A diff representing file modifications. Shows changes to files in a format suitable for display in the client UI. See protocol docs: [Content](https://agentclientprotocol.com/protocol/v1/tool-calls#content)"
  [:map
   {:closed false}
   [:path :string]
   [:oldText {:optional true} [:maybe :string]]
   [:newText :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->Diff
  "Create a `Diff` value."
  [path newText & {:keys [oldText meta]}]
  (let [value {:newText newText
               :oldText oldText
               :path    path
               :_meta   meta}]
    (rt/validate! #'Diff value "Diff")))
