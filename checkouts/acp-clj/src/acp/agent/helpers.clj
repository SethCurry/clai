(ns acp.agent.helpers
  (:require [acp.schema.core :as schemas]
            [acp.jsonrpc :as jsonrpc]
            [acp.schema-runtime :as rt]))


(defn send-session-update
  "Send a `session/update` notification carrying one `SessionUpdate` for
  `session-id`. `update` is the full update map including its `:sessionUpdate`
  discriminator (e.g. `{:sessionUpdate \"agent_message_chunk\" :content
  {:type \"text\" :text \"...\"}}`)."
  [conn sid update]
  (rt/validate! schemas/SessionId sid "send-session-update sessionId")
  (rt/validate! schemas/SessionUpdate update "send-session-update update")
  (jsonrpc/send-notification conn "session/update"
                     {:sessionId sid :update update}))

(defn send-message-text
  "Send a plain-text `agent_message_chunk` `session/update` for `sid`.

  `content` is the text to display. `ContentBlock` is a union discriminated by
  `:type`, so the text payload built by `schemas/->TextContent` is tagged as
  `\"text\"` to make it a content block. The chunk is built with
  `schemas/->SessionUpdate` so it is validated before it goes on the wire."
  [conn sid text]
  (send-session-update conn sid
    (schemas/->SessionUpdate
     {:sessionUpdate "agent_message_chunk"
      :content
                      (schemas/->TextContent text)})))

(defn send-thought-text
  [conn sid text]
  (send-session-update conn sid
    (schemas/->SessionUpdate
      {:sessionUpdate "agent_thought_chunk"
        :content (schemas/->TextContent text)})))


(defn send-tool-call-started
  "Emit a `tool_call` SessionUpdate announcing that `call` is starting."
  [conn session-id call-id & {:keys [kind name raw-input title]
                              :or {
                                kind "other"
                                name nil
                                raw-input nil
                                title nil
                                }}]
    (send-session-update conn session-id
      {:_meta        nil
       :sessionUpdate "tool_call"
       :kind          kind
       :name          name
       :rawInput      raw-input
       :title         title
       :toolCallId    call-id
       :status        "in_progress"})
    call-id)


(defn send-tool-call-text
  [conn session-id call-id text & {:keys [status]
                                   :or {
                                     status nil
                                     }}]
  (send-session-update conn session-id
    {:_meta        nil
     :sessionUpdate "tool_call_update"
     :content       (schemas/->TextContent text)
     :toolCallId    call-id
     :status        status})
    )

(defn send-tool-call-done [conn session-id call-id status]
  (send-session-update conn session-id {
      :sessionUpdate "tool_call_update"
      :toolCallId call-id
      :status status
    }))
