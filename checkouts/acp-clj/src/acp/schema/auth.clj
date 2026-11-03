(ns acp.schema.auth
  "Authentication method and capability schemas, plus the `authenticate`
  and `logout` method requests and responses."
  (:require [acp.schema-runtime :as rt]))


;; Schemas refer to one another, so declare every name up front.
(declare
 AgentAuthCapabilities
 AuthCapabilities
 AuthMethod
 AuthMethodAgent
 AuthMethodId
 AuthMethodTerminal
 AuthenticateRequest
 AuthenticateResponse
 LogoutCapabilities
 LogoutRequest
 LogoutResponse)


;; -------------------------------------------------------------------------
;; AuthCapabilities
;; -------------------------------------------------------------------------

(def AuthCapabilities
  "Authentication capabilities supported by the client. Advertised during initialization to inform the agent which authentication method types the client can handle. This governs opt-in types that require additional client-side support."
  [:map
   {:closed false}
   [:terminal {:optional true} :boolean]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AuthCapabilities
  "Create a `AuthCapabilities` value."
  [& {:keys [terminal meta]}]
  (let [value (cond-> {:_meta meta}
                (some? terminal) (assoc :terminal terminal))]
    (rt/validate! #'AuthCapabilities value "AuthCapabilities")))


;; -------------------------------------------------------------------------
;; AgentAuthCapabilities
;; -------------------------------------------------------------------------

(def AgentAuthCapabilities
  "Authentication-related capabilities supported by the agent."
  [:map
   {:closed false}
   [:logout {:optional true} [:maybe #'LogoutCapabilities]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AgentAuthCapabilities
  "Create a `AgentAuthCapabilities` value."
  [& {:keys [logout meta]}]
  (let [value {:logout logout
               :_meta  meta}]
    (rt/validate! #'AgentAuthCapabilities value "AgentAuthCapabilities")))


;; -------------------------------------------------------------------------
;; AuthMethod
;; -------------------------------------------------------------------------

(def AuthMethod
  "Describes an available authentication method. The `type` field acts as the discriminator in the serialized JSON form. When no `type` is present, the method is treated as `agent`."
  [:or
   [:and
    [:map {:closed false} [:type [:= "terminal"]]]
    #'AuthMethodTerminal]
   #'AuthMethodAgent])

(defn ->AuthMethod
  "Create a `AuthMethod` value."
  [method]
  (rt/validate! #'AuthMethod method "AuthMethod"))


;; -------------------------------------------------------------------------
;; AuthMethodAgent
;; -------------------------------------------------------------------------

(def AuthMethodAgent
  "Agent handles authentication itself through `authenticate`. This is the default authentication method type."
  [:map
   {:closed false}
   [:id #'AuthMethodId]
   [:name :string]
   [:description {:optional true} [:maybe :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AuthMethodAgent
  "Create a `AuthMethodAgent` value."
  [id name & {:keys [description meta]}]
  (let [value {:id          id
               :name        name
               :description description
               :_meta       meta}]
    (rt/validate! #'AuthMethodAgent value "AuthMethodAgent")))


;; -------------------------------------------------------------------------
;; AuthMethodId
;; -------------------------------------------------------------------------

(def AuthMethodId
  "Typed identifier used for auth method values on the wire."
  :string)

(defn ->AuthMethodId
  "Create a `AuthMethodId` value."
  [id]
  (rt/validate! #'AuthMethodId id "AuthMethodId"))


;; -------------------------------------------------------------------------
;; AuthMethodTerminal
;; -------------------------------------------------------------------------

(def AuthMethodTerminal
  "Terminal-based authentication method. The client runs the configured agent program as a separate interactive process for the user to authenticate via a TUI. Agents MUST advertise this method only when the client enabled its terminal authentication capability. A zero exit status signals success; any other termination signals failure. The client MUST NOT pass this method to `authenticate`."
  [:map
   {:closed false}
   [:id #'AuthMethodId]
   [:name :string]
   [:description {:optional true} [:maybe :string]]
   [:args {:optional true} [:vector :string]]
   [:env {:optional true} [:map-of :string :string]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AuthMethodTerminal
  "Create a `AuthMethodTerminal` value."
  [id name & {:keys [description args env meta]}]
  (let [value (cond-> {:id          id
                       :name        name
                       :description description
                       :_meta       meta}
                (some? args) (assoc :args args)
                (some? env)  (assoc :env env))]
    (rt/validate! #'AuthMethodTerminal value "AuthMethodTerminal")))


;; -------------------------------------------------------------------------
;; AuthenticateRequest
;; -------------------------------------------------------------------------

(def AuthenticateRequest
  "Request parameters for the authenticate method. Specifies which authentication method to use."
  [:map
   {:closed false}
   [:methodId #'AuthMethodId]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AuthenticateRequest
  "Create a `AuthenticateRequest` value."
  [methodId & {:keys [meta]}]
  (let [value {:methodId methodId
               :_meta     meta}]
    (rt/validate! #'AuthenticateRequest value "AuthenticateRequest")))


;; -------------------------------------------------------------------------
;; AuthenticateResponse
;; -------------------------------------------------------------------------

(def AuthenticateResponse
  "Response to the `authenticate` method."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->AuthenticateResponse
  "Create a `AuthenticateResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'AuthenticateResponse value "AuthenticateResponse")))


;; -------------------------------------------------------------------------
;; LogoutCapabilities
;; -------------------------------------------------------------------------

(def LogoutCapabilities
  "Logout capabilities supported by the agent. Supplying `{}` means the agent supports the logout method."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->LogoutCapabilities
  "Create a `LogoutCapabilities` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'LogoutCapabilities value "LogoutCapabilities")))


;; -------------------------------------------------------------------------
;; LogoutRequest
;; -------------------------------------------------------------------------

(def LogoutRequest
  "Request parameters for the logout method. Terminates the current authenticated session."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->LogoutRequest
  "Create a `LogoutRequest` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'LogoutRequest value "LogoutRequest")))


;; -------------------------------------------------------------------------
;; LogoutResponse
;; -------------------------------------------------------------------------

(def LogoutResponse
  "Response to the `logout` method."
  [:map
   {:closed false}
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->LogoutResponse
  "Create a `LogoutResponse` value."
  [& {:keys [meta]}]
  (let [value {:_meta meta}]
    (rt/validate! #'LogoutResponse value "LogoutResponse")))
