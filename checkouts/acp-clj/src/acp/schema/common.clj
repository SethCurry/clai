(ns acp.schema.common
  "Schemas shared by more than one protocol area.

  These live below the feature namespaces (`content`, `terminal`, `tool`,
  `mcp`, `session`) so any of them can refer to the shared type without
  creating a dependency cycle."
  (:require [acp.schema-runtime :as rt]))


;; -------------------------------------------------------------------------
;; SessionId
;; -------------------------------------------------------------------------

(def SessionId
  "A unique identifier for a conversation session between a client and agent. Sessions maintain their own context, conversation history, and state, allowing multiple independent interactions with the same agent. See protocol docs: [Session ID](https://agentclientprotocol.com/protocol/v1/session-setup#session-id)"
  :string)

(defn ->SessionId
  "Create a `SessionId` value."
  [id]
  (rt/validate! #'SessionId id "SessionId"))


;; -------------------------------------------------------------------------
;; RequestId
;; -------------------------------------------------------------------------

(def RequestId
  "JSON RPC Request Id An identifier established by the Client that MUST contain a String, Number, or NULL value if included. If it is not included it is assumed to be a notification. The value SHOULD normally not be Null \\[1\\] and Numbers SHOULD NOT contain fractional parts \\[2\\] The Server MUST reply with the same value in the Response object if included. This member is used to correlate the context between the two objects. \\[1\\] The use of Null as a value for the id member in a Request object is discouraged, because this specification uses a value of Null for Responses with an unknown id. Also, because JSON-RPC 1.0 uses an id value of Null for Notifications this could cause confusion in handling. \\[2\\] Fractional parts may be problematic, since many decimal fractions cannot be represented exactly as binary fractions."
  [:maybe [:or :int :string]])

(defn ->RequestId
  "Create a `RequestId` value."
  [id]
  (rt/validate! #'RequestId id "RequestId"))
