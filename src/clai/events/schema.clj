(ns clai.events.schema)



(def AgentSession [:map
  [:id :int]
  [:working_directory :str]
  [:created_at :str]])

(def AgentSessionEvent [:map [:id :int]
  [:type :str]
  [:content [:map]]
  [:agent_session_id :int]])
