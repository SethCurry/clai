(ns acp.schema.mcp
  "Schemas for the MCP servers an agent can connect to."
  (:require [acp.schema-runtime :as rt]))


;; -------------------------------------------------------------------------
;; HttpHeader
;; -------------------------------------------------------------------------

(def HttpHeader
  "An HTTP header to set when making requests to the MCP server."
  [:map
   {:closed false}
   [:name :string]
   [:value :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->HttpHeader
  "Create a `HttpHeader` value."
  [name value & {:keys [meta]}]
  (let [value {:name  name
               :value value
               :_meta meta}]
    (rt/validate! #'HttpHeader value "HttpHeader")))


;; -------------------------------------------------------------------------
;; EnvVariable
;; -------------------------------------------------------------------------

(def EnvVariable
  "An environment variable to set when launching an MCP server."
  [:map
   {:closed false}
   [:name :string]
   [:value :string]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->EnvVariable
  "Create a `EnvVariable` value."
  [name value & {:keys [meta]}]
  (let [value {:name  name
               :value value
               :_meta meta}]
    (rt/validate! #'EnvVariable value "EnvVariable")))


;; -------------------------------------------------------------------------
;; McpServerHttp
;; -------------------------------------------------------------------------

(def McpServerHttp
  "HTTP transport configuration for MCP."
  [:map
   {:closed false}
   [:name :string]
   [:url :string]
   [:headers [:vector #'HttpHeader]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->McpServerHttp
  "Create a `McpServerHttp` value."
  [name url headers & {:keys [meta]}]
  (let [value {:name    name
               :url     url
               :headers headers
               :_meta   meta}]
    (rt/validate! #'McpServerHttp value "McpServerHttp")))


;; -------------------------------------------------------------------------
;; McpServerSse
;; -------------------------------------------------------------------------

(def McpServerSse
  "SSE transport configuration for MCP."
  [:map
   {:closed false}
   [:name :string]
   [:url :string]
   [:headers [:vector #'HttpHeader]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->McpServerSse
  "Create a `McpServerSse` value."
  [name url headers & {:keys [meta]}]
  (let [value {:name    name
               :url     url
               :headers headers
               :_meta   meta}]
    (rt/validate! #'McpServerSse value "McpServerSse")))


;; -------------------------------------------------------------------------
;; McpServerStdio
;; -------------------------------------------------------------------------

(def McpServerStdio
  "Stdio transport configuration for MCP."
  [:map
   {:closed false}
   [:name :string]
   [:command :string]
   [:args [:vector :string]]
   [:env [:vector #'EnvVariable]]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(defn ->McpServerStdio
  "Create a `McpServerStdio` value."
  [name command args env & {:keys [meta]}]
  (let [value {:name    name
               :command command
               :args    args
               :env     env
               :_meta   meta}]
    (rt/validate! #'McpServerStdio value "McpServerStdio")))


;; -------------------------------------------------------------------------
;; McpServer
;; -------------------------------------------------------------------------

(def McpServer
  "Configuration for connecting to an MCP (Model Context Protocol) server. MCP servers provide tools and context that the agent can use when processing prompts. See protocol docs: [MCP Servers](https://agentclientprotocol.com/protocol/v1/session-setup#mcp-servers)"
  [:or
   [:and [:map {:closed false} [:type [:= "http"]]] #'McpServerHttp]
   [:and [:map {:closed false} [:type [:= "sse"]]] #'McpServerSse]
   #'McpServerStdio])

(defn ->McpServer
  "Create a `McpServer` value."
  [server]
  (rt/validate! #'McpServer server "McpServer"))


;; -------------------------------------------------------------------------
;; McpCapabilities
;; -------------------------------------------------------------------------

(def McpCapabilities
  "MCP capabilities supported by the agent"
  [:map
   {:closed false}
   [:http {:optional true} :boolean]
   [:sse {:optional true} :boolean]
   [:_meta {:optional true} [:maybe [:map {:closed false}]]]])

(def ->McpCapabilities
  "Create a `McpCapabilities` value."
  (rt/make-object-constructor
    #'McpCapabilities
    "McpCapabilities"
    [{:key :http, :required? false, :nullable? false}
     {:key :sse, :required? false, :nullable? false}
     {:key :_meta, :required? false, :nullable? true}]
    false))
