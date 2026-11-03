(ns acp.schema.elicitation
  "Elicitation schemas: the requests, responses and capabilities for
  asking the user for structured input, plus the JSON-Schema subset that
  describes the fields of an elicitation form."
  (:require [acp.schema-runtime :as rt]
            [acp.schema.common :as common]
            [acp.schema.tool :as tool]))


;; Schemas refer to one another, so declare every name up front.
(declare
 BooleanPropertySchema
 CompleteElicitationNotification
 CreateElicitationRequest
 CreateElicitationResponse
 ElicitationAcceptAction
 ElicitationCapabilities
 ElicitationContentValue
 ElicitationFormCapabilities
 ElicitationFormMode
 ElicitationId
 ElicitationPropertySchema
 ElicitationRequestScope
 ElicitationSchema
 ElicitationSchemaType
 ElicitationSessionScope
 ElicitationUrlCapabilities
 ElicitationUrlMode
 EnumOption
 IntegerPropertySchema
 MultiSelectItems
 MultiSelectPropertySchema
 NumberPropertySchema
 StringFormat
 StringMultiSelectItems
 StringPropertySchema
 TitledMultiSelectItems)


;; -------------------------------------------------------------------------
;; CompleteElicitationNotification
;; -------------------------------------------------------------------------

(def CompleteElicitationNotification
  "Notification sent by the agent when a URL-based elicitation is complete."
  [:map
   {:closed false}
   [:elicitationId #'ElicitationId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->CompleteElicitationNotification
  "Create a `CompleteElicitationNotification` value."
  [elicitationId & {:keys [meta]}]
  (let [value {:elicitationId elicitationId
               :_meta         meta}]
    (rt/validate! #'CompleteElicitationNotification
                  value "CompleteElicitationNotification")))


;; -------------------------------------------------------------------------
;; CreateElicitationRequest
;; -------------------------------------------------------------------------

(def CreateElicitationRequest
  "Request from the agent to elicit structured user input. The agent sends this to the client to request information from the user, either via a form or by directing them to a URL. Elicitations are tied to a session (optionally a tool call) or a request."
  [:and
   [:map
    {:closed false}
    [:message :string]
    [:_meta {:optional true} [:maybe [:map {:closed false}]]]]
   [:or
    [:and [:map {:closed false} [:mode [:= "form"]]] #'ElicitationFormMode]
    [:and [:map {:closed false} [:mode [:= "url"]]] #'ElicitationUrlMode]
    [:and
     [:map {:closed false} [:mode :string]]
     [:or #'ElicitationSessionScope #'ElicitationRequestScope]
     [:not
      [:or
       [:map {:closed false} [:mode [:= "form"]]]
       [:map {:closed false} [:mode [:= "url"]]]]]]]])

(defn ->CreateElicitationRequest
  "Create a `CreateElicitationRequest` value.

  `:mode` is the variant discriminator: form requests take
  `:requestedSchema`, url requests take `:elicitationId` and `:url`, and
  both take a `:sessionId`/`:toolCallId` or `:requestId` scope."
  [message mode & {:keys [requestedSchema elicitationId url sessionId toolCallId requestId meta]}]
  (let [value (cond-> {:message message
                       :mode    mode
                       :_meta   meta}
                (some? requestedSchema) (assoc :requestedSchema requestedSchema)
                (some? elicitationId)   (assoc :elicitationId elicitationId)
                (some? url)             (assoc :url url)
                (some? sessionId)       (assoc :sessionId sessionId)
                (some? toolCallId)      (assoc :toolCallId toolCallId)
                (some? requestId)       (assoc :requestId requestId))]
    (rt/validate! #'CreateElicitationRequest
                  value "CreateElicitationRequest")))


;; -------------------------------------------------------------------------
;; CreateElicitationResponse
;; -------------------------------------------------------------------------

(def CreateElicitationResponse
  "Response from the client to an elicitation request."
  [:and
   [:map
    {:closed false}
    [:_meta {:optional true} [:maybe [:map {:closed false}]]]]
   [:or
    [:and
     [:map {:closed false} [:action [:= "accept"]]]
     #'ElicitationAcceptAction]
    [:map {:closed false} [:action [:= "decline"]]]
    [:map {:closed false} [:action [:= "cancel"]]]
    [:and
     [:map {:closed false} [:action :string]]
     [:not
      [:or
       [:map {:closed false} [:action [:= "accept"]]]
       [:map {:closed false} [:action [:= "decline"]]]
       [:map {:closed false} [:action [:= "cancel"]]]]]]]])

(defn ->CreateElicitationResponse
  "Create a `CreateElicitationResponse` value.

  `:action` is the variant discriminator: accepted elicitations may carry
  a `:content` map of the submitted values."
  [action & {:keys [content meta]}]
  (let [value (cond-> {:action action
                       :_meta  meta}
                (some? content) (assoc :content content))]
    (rt/validate! #'CreateElicitationResponse
                  value "CreateElicitationResponse")))


;; -------------------------------------------------------------------------
;; ElicitationAcceptAction
;; -------------------------------------------------------------------------

(def ElicitationAcceptAction
  "The user accepted the elicitation and provided content."
  [:map
   {:closed false}
   [:content
    {:optional true}
    [:maybe [:map-of :string #'ElicitationContentValue]]]])

(defn ->ElicitationAcceptAction
  "Create a `ElicitationAcceptAction` value."
  [& {:keys [content]}]
  (let [value {:content content}]
    (rt/validate! #'ElicitationAcceptAction value "ElicitationAcceptAction")))


;; -------------------------------------------------------------------------
;; ElicitationCapabilities
;; -------------------------------------------------------------------------

(def ElicitationCapabilities
  "Elicitation capabilities supported by the client."
  [:map
   {:closed false}
   [:form {:optional true} [:maybe #'ElicitationFormCapabilities]]
   [:url {:optional true} [:maybe #'ElicitationUrlCapabilities]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ElicitationCapabilities
  "Create a `ElicitationCapabilities` value."
  [& {:keys [form url meta]}]
  (let [value {:form form
               :url  url
               :_meta meta}]
    (rt/validate! #'ElicitationCapabilities value "ElicitationCapabilities")))


;; -------------------------------------------------------------------------
;; ElicitationContentValue
;; -------------------------------------------------------------------------

(def ElicitationContentValue
  "Allowed wire representations for `ElicitationContentValue`."
  [:or :string :int number? :boolean [:vector :string]])

(def ->ElicitationContentValue
  "Create a `ElicitationContentValue` value."
  (rt/make-value-constructor
    #'ElicitationContentValue
    "ElicitationContentValue"))


;; -------------------------------------------------------------------------
;; ElicitationFormCapabilities
;; -------------------------------------------------------------------------

(def ElicitationFormCapabilities
  "Form-based elicitation capabilities. Supplying `{}` means the client supports form-based elicitation."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ElicitationFormCapabilities
  "Create a `ElicitationFormCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'ElicitationFormCapabilities
                  value "ElicitationFormCapabilities")))


;; -------------------------------------------------------------------------
;; ElicitationFormMode
;; -------------------------------------------------------------------------

(def ElicitationFormMode
  "Form-based elicitation mode where the client renders a form from the provided schema."
  [:and
   [:map {:closed false} [:requestedSchema #'ElicitationSchema]]
   [:or #'ElicitationSessionScope #'ElicitationRequestScope]])

(defn ->ElicitationFormMode
  "Create a `ElicitationFormMode` value.

  The scope is either a `:sessionId` (optionally with `:toolCallId`) or a
  `:requestId`."
  [requestedSchema & {:keys [sessionId toolCallId requestId]}]
  (let [value (cond-> {:requestedSchema requestedSchema}
                (some? sessionId)  (assoc :sessionId sessionId)
                (some? toolCallId) (assoc :toolCallId toolCallId)
                (some? requestId)  (assoc :requestId requestId))]
    (rt/validate! #'ElicitationFormMode value "ElicitationFormMode")))


;; -------------------------------------------------------------------------
;; ElicitationId
;; -------------------------------------------------------------------------

(def ElicitationId
  "Unique identifier for an elicitation."
  :string)

(def ->ElicitationId
  "Create a `ElicitationId` value."
  (rt/make-value-constructor #'ElicitationId "ElicitationId"))


;; -------------------------------------------------------------------------
;; ElicitationPropertySchema
;; -------------------------------------------------------------------------

(def ElicitationPropertySchema
  "Property schema for elicitation form fields. Each variant corresponds to a JSON Schema `\"type\"` value. Single-select enums use `type: \"string\"` with `enum` or `oneOf` set. Multi-select enums use `type: \"array\"`."
  [:or
   [:and
    [:map {:closed false} [:type [:= "string"]]]
    #'StringPropertySchema]
   [:and
    [:map {:closed false} [:type [:= "number"]]]
    #'NumberPropertySchema]
   [:and
    [:map {:closed false} [:type [:= "integer"]]]
    #'IntegerPropertySchema]
   [:and
    [:map {:closed false} [:type [:= "boolean"]]]
    #'BooleanPropertySchema]
   [:and
    [:map {:closed false} [:type [:= "array"]]]
    #'MultiSelectPropertySchema]
   [:and
    [:map {:closed false} [:type :string]]
    [:not
     [:or
      [:map {:closed false} [:type [:= "string"]]]
      [:map {:closed false} [:type [:= "number"]]]
      [:map {:closed false} [:type [:= "integer"]]]
      [:map {:closed false} [:type [:= "boolean"]]]
      [:map {:closed false} [:type [:= "array"]]]]]]])

(def ->ElicitationPropertySchema
  "Create a `ElicitationPropertySchema` value."
  (rt/make-value-constructor
    #'ElicitationPropertySchema
    "ElicitationPropertySchema"))


;; -------------------------------------------------------------------------
;; ElicitationRequestScope
;; -------------------------------------------------------------------------

(def ElicitationRequestScope
  "Request-scoped elicitation, tied to a specific JSON-RPC request outside of a session (e.g., during auth/configuration phases before any session is started)."
  [:map {:closed false} [:requestId #'common/RequestId]])

(defn ->ElicitationRequestScope
  "Create a `ElicitationRequestScope` value."
  [requestId]
  (let [value {:requestId requestId}]
    (rt/validate! #'ElicitationRequestScope value "ElicitationRequestScope")))


;; -------------------------------------------------------------------------
;; ElicitationSchema
;; -------------------------------------------------------------------------

(def ElicitationSchema
  "Type-safe elicitation schema for requesting structured user input. This represents a JSON Schema object with primitive-typed properties, as required by the elicitation specification."
  [:map
   {:closed false}
   [:type {:optional true} #'ElicitationSchemaType]
   [:title {:optional true} [:maybe :string]]
   [:properties
    {:optional true}
    [:map-of :string #'ElicitationPropertySchema]]
   [:required {:optional true} [:maybe [:vector :string]]]
   [:description {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ElicitationSchema
  "Create a `ElicitationSchema` value."
  [& {:keys [type title properties required description meta]}]
  (let [value (cond-> {:title       title
                       :required    required
                       :description description
                       :_meta       meta}
                (some? type)       (assoc :type type)
                (some? properties) (assoc :properties properties))]
    (rt/validate! #'ElicitationSchema value "ElicitationSchema")))


;; -------------------------------------------------------------------------
;; ElicitationSchemaType
;; -------------------------------------------------------------------------

(def ElicitationSchemaType
  "Type discriminator for elicitation schemas."
  [:enum "object"])

(def ->ElicitationSchemaType
  "Create a `ElicitationSchemaType` value."
  (rt/make-value-constructor
    #'ElicitationSchemaType
    "ElicitationSchemaType"))


;; -------------------------------------------------------------------------
;; ElicitationSessionScope
;; -------------------------------------------------------------------------

(def ElicitationSessionScope
  "Session-scoped elicitation, optionally tied to a specific tool call. When `toolCallId` is set, the elicitation is tied to a specific tool call. This is useful when an agent receives an elicitation from an MCP server during a tool call and needs to redirect it to the user."
  [:map
   {:closed false}
   [:sessionId #'common/SessionId]
   [:toolCallId {:optional true} [:maybe #'tool/ToolCallId]]])

(defn ->ElicitationSessionScope
  "Create a `ElicitationSessionScope` value."
  [sessionId & {:keys [toolCallId]}]
  (let [value {:sessionId  sessionId
               :toolCallId toolCallId}]
    (rt/validate! #'ElicitationSessionScope value "ElicitationSessionScope")))


;; -------------------------------------------------------------------------
;; ElicitationUrlCapabilities
;; -------------------------------------------------------------------------

(def ElicitationUrlCapabilities
  "URL-based elicitation capabilities. Supplying `{}` means the client supports URL-based elicitation."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->ElicitationUrlCapabilities
  "Create a `ElicitationUrlCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'ElicitationUrlCapabilities
                  value "ElicitationUrlCapabilities")))


;; -------------------------------------------------------------------------
;; ElicitationUrlMode
;; -------------------------------------------------------------------------

(def ElicitationUrlMode
  "URL-based elicitation mode where the client directs the user to a URL."
  [:and
   [:map {:closed false} [:elicitationId #'ElicitationId] [:url :string]]
   [:or #'ElicitationSessionScope #'ElicitationRequestScope]])

(defn ->ElicitationUrlMode
  "Create a `ElicitationUrlMode` value.

  The scope is either a `:sessionId` (optionally with `:toolCallId`) or a
  `:requestId`."
  [elicitationId url & {:keys [sessionId toolCallId requestId]}]
  (let [value (cond-> {:elicitationId elicitationId
                       :url           url}
                (some? sessionId)  (assoc :sessionId sessionId)
                (some? toolCallId) (assoc :toolCallId toolCallId)
                (some? requestId)  (assoc :requestId requestId))]
    (rt/validate! #'ElicitationUrlMode value "ElicitationUrlMode")))


;; -------------------------------------------------------------------------
;; BooleanPropertySchema
;; -------------------------------------------------------------------------

(def BooleanPropertySchema
  "Schema for boolean properties in an elicitation form."
  [:map
   {:closed false}
   [:title {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:default {:optional true} [:maybe :boolean]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->BooleanPropertySchema
  "Create a `BooleanPropertySchema` value."
  [& {:keys [title description default meta]}]
  (let [value {:title       title
               :description description
               :default     default
               :_meta       meta}]
    (rt/validate! #'BooleanPropertySchema value "BooleanPropertySchema")))


;; -------------------------------------------------------------------------
;; EnumOption
;; -------------------------------------------------------------------------

(def EnumOption
  "A titled enum option with a const value, human-readable title, and optional description."
  [:map
   {:closed false}
   [:const :string]
   [:title :string]
   [:description {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->EnumOption
  "Create a `EnumOption` value."
  [const title & {:keys [description meta]}]
  (let [value {:const       const
               :title       title
               :description description
               :_meta       meta}]
    (rt/validate! #'EnumOption value "EnumOption")))


;; -------------------------------------------------------------------------
;; IntegerPropertySchema
;; -------------------------------------------------------------------------

(def IntegerPropertySchema
  "Schema for integer properties in an elicitation form."
  [:map
   {:closed false}
   [:title {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:minimum {:optional true} [:maybe :int]]
   [:maximum {:optional true} [:maybe :int]]
   [:default {:optional true} [:maybe :int]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->IntegerPropertySchema
  "Create a `IntegerPropertySchema` value."
  [& {:keys [title description minimum maximum default meta]}]
  (let [value {:title       title
               :description description
               :minimum     minimum
               :maximum     maximum
               :default     default
               :_meta       meta}]
    (rt/validate! #'IntegerPropertySchema value "IntegerPropertySchema")))


;; -------------------------------------------------------------------------
;; MultiSelectItems
;; -------------------------------------------------------------------------

(def MultiSelectItems
  "Items for a multi-select (array) property schema."
  [:or
   [:and
    [:map {:closed false} [:type [:= "string"]]]
    #'StringMultiSelectItems]
   [:and
    [:map {:closed false} [:type :string]]
    [:not [:map {:closed false} [:type [:= "string"]]]]]
   #'TitledMultiSelectItems])

(def ->MultiSelectItems
  "Create a `MultiSelectItems` value."
  (rt/make-value-constructor #'MultiSelectItems "MultiSelectItems"))


;; -------------------------------------------------------------------------
;; MultiSelectPropertySchema
;; -------------------------------------------------------------------------

(def MultiSelectPropertySchema
  "Schema for multi-select (array) properties in an elicitation form."
  [:map
   {:closed false}
   [:title {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:minItems {:optional true} [:maybe [:int {:min 0}]]]
   [:maxItems {:optional true} [:maybe [:int {:min 0}]]]
   [:items #'MultiSelectItems]
   [:default {:optional true} [:maybe [:vector :string]]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->MultiSelectPropertySchema
  "Create a `MultiSelectPropertySchema` value."
  [items & {:keys [title description minItems maxItems default meta]}]
  (let [value {:title       title
               :description description
               :minItems    minItems
               :maxItems    maxItems
               :items       items
               :default     default
               :_meta       meta}]
    (rt/validate! #'MultiSelectPropertySchema
                  value "MultiSelectPropertySchema")))


;; -------------------------------------------------------------------------
;; NumberPropertySchema
;; -------------------------------------------------------------------------

(def NumberPropertySchema
  "Schema for number (floating-point) properties in an elicitation form."
  [:map
   {:closed false}
   [:title {:optional true} [:maybe :string]]
   [:description {:optional true} [:maybe :string]]
   [:minimum {:optional true} [:maybe number?]]
   [:maximum {:optional true} [:maybe number?]]
   [:default {:optional true} [:maybe number?]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->NumberPropertySchema
  "Create a `NumberPropertySchema` value."
  [& {:keys [title description minimum maximum default meta]}]
  (let [value {:title       title
               :description description
               :minimum     minimum
               :maximum     maximum
               :default     default
               :_meta       meta}]
    (rt/validate! #'NumberPropertySchema value "NumberPropertySchema")))


;; -------------------------------------------------------------------------
;; StringFormat
;; -------------------------------------------------------------------------

(def StringFormat
  "String format types for string properties in elicitation schemas."
  [:enum "email" "uri" "date" "date-time"])

(def ->StringFormat
  "Create a `StringFormat` value."
  (rt/make-value-constructor #'StringFormat "StringFormat"))


;; -------------------------------------------------------------------------
;; StringMultiSelectItems
;; -------------------------------------------------------------------------

(def StringMultiSelectItems
  "String item schema for multi-select enum properties."
  [:map
   {:closed false}
   [:enum [:vector :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->StringMultiSelectItems
  "Create a `StringMultiSelectItems` value."
  [enum & {:keys [meta]}]
  (let [value {:enum  enum
               :_meta meta}]
    (rt/validate! #'StringMultiSelectItems value "StringMultiSelectItems")))


;; -------------------------------------------------------------------------
;; StringPropertySchema
;; -------------------------------------------------------------------------

(def StringPropertySchema
  "Schema for string properties in an elicitation form. When `enum` or `oneOf` is set, this represents a single-select enum with `\"type\": \"string\"`."
  [:map
   {:closed false}
   [:description {:optional true} [:maybe :string]]
   [:enum {:optional true} [:maybe [:vector :string]]]
   [:format {:optional true} [:maybe #'StringFormat]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]
   [:default {:optional true} [:maybe :string]]
   [:minLength {:optional true} [:maybe [:int {:min 0}]]]
   [:title {:optional true} [:maybe :string]]
   [:maxLength {:optional true} [:maybe [:int {:min 0}]]]
   [:oneOf {:optional true} [:maybe [:vector #'EnumOption]]]
   [:pattern {:optional true} [:maybe :string]]])

(defn ->StringPropertySchema
  "Create a `StringPropertySchema` value."
  [& {:keys [description enum format meta default minLength title maxLength oneOf pattern]}]
  (let [value {:description description
               :enum        enum
               :format      format
               :_meta       meta
               :default     default
               :minLength   minLength
               :title       title
               :maxLength   maxLength
               :oneOf       oneOf
               :pattern     pattern}]
    (rt/validate! #'StringPropertySchema value "StringPropertySchema")))


;; -------------------------------------------------------------------------
;; TitledMultiSelectItems
;; -------------------------------------------------------------------------

(def TitledMultiSelectItems
  "Items definition for titled multi-select enum properties."
  [:map
   {:closed false}
   [:anyOf [:vector #'EnumOption]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->TitledMultiSelectItems
  "Create a `TitledMultiSelectItems` value."
  [anyOf & {:keys [meta]}]
  (let [value {:anyOf anyOf
               :_meta meta}]
    (rt/validate! #'TitledMultiSelectItems value "TitledMultiSelectItems")))
