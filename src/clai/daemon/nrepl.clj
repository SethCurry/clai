(ns clai.daemon.nrepl
  "A small nREPL client used by the HTTP daemon to evaluate Clojure forms.

  The web UI can act as a REPL in addition to driving the agent. A browser
  cannot speak nREPL's bencode transport directly, so `clai.daemon.http`
  exposes a WebSocket and forwards `eval` requests through this namespace.

  A connection owns one client transport and one nREPL session. Creating the
  connection does not start an nREPL server; the port is normally the one
  `clai.core/start-nrepl!` publishes in `.nrepl-port`."
  (:require [clai.log]
            [clai.util :as util]
            [clojure.string :as str]
            [nrepl.core :as nrepl]
            [taoensso.telemere :as tel])
  (:import [java.io Closeable]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ConnectionOptions
  "Options accepted by [[connect!]]."
  [:map
   [:port [:int {:min 1 :max 65535}]]
   [:host {:optional true} :string]
   [:timeout {:optional true} pos-int?]])

(def Connection
  "A live nREPL client connection."
  [:map
   [:transport [:fn #(instance? Closeable %)]]
   [:client fn?]
   [:session :string]])

;; ---------------------------------------------------------------------------
;; Responses
;; ---------------------------------------------------------------------------

(defn- normalize-response
  "Strip transport bookkeeping from an nREPL response and make its values
  JSON-friendly.

  Only the keys the web UI renders are kept. `:status` is always a vector of
  strings, and exception values are rendered as strings."
  [response]
  (-> (select-keys response [:ns :value :out :err :status :ex :root-ex])
      (update :status (fn [status]
                        (cond
                          (nil? status)    nil
                          (coll? status)   (mapv str status)
                          :else            [(str status)])))
      (update :ex #(some-> % str))
      (update :root-ex #(some-> % str))))

;; ---------------------------------------------------------------------------
;; Connection lifecycle
;; ---------------------------------------------------------------------------

(defn connect!
  "Connect to the nREPL server at `opts`' `:port` and open a fresh session.

  `:host` defaults to `127.0.0.1`, and `:timeout` (milliseconds) is how long
  the client waits for each response (default 10 minutes). Returns a
  [[Connection]]; call [[close!]] when it is no longer needed."
  {:malli/schema [:-> ConnectionOptions #'Connection]}
  [{:keys [port host timeout]}]
  (let [transport (nrepl/connect :host (or host "127.0.0.1")
                                 :port port)
        client    (nrepl/client transport (or timeout 600000))
        session   (nrepl/new-session client)]
    (tel/log! {:level :debug
               :id    ::connected
               :data  {:host (or host "127.0.0.1") :port port}}
              "Connected web REPL to nREPL")
    {:transport transport
     :client    client
     :session   session}))

(defn eval!
  "Evaluate `code` in `conn`'s nREPL session and return the response maps.

  An optional `ns` names the namespace to evaluate in. Responses are already
  normalized by [[normalize-response]], so they can be sent to the browser as
  JSON. Evaluation errors are nREPL responses (`:ex`/`:err`), not thrown
  exceptions; transport failures are logged and rethrown."
  {:malli/schema [:function
                  [:=> [:cat #'Connection :string] [:vector :map]]
                  [:=> [:cat #'Connection :string [:maybe :string]]
                       [:vector :map]]]}
  ([conn code] (eval! conn code nil))
  ([{:keys [client session]} code ns]
   (try
     (->> (nrepl/message client
                         (cond-> {:op      "eval"
                                  :session session
                                  :code    code}
                           (not (str/blank? (str ns)))
                           (assoc :ns (str ns))))
          (mapv normalize-response))
     (catch Throwable e
       (tel/error! {:id ::eval-failed}
                   e)
       (throw (ex-info (str "nREPL evaluation failed: "
                            (util/error-message e))
                       {:code code :ns ns}
                       e))))))

(defn close!
  "Close `conn`'s nREPL session and transport. Safe to call with nil or twice."
  {:malli/schema [:-> [:maybe #'Connection] :nil]}
  [conn]
  (when conn
    (let [{:keys [client transport session]} conn]
      (try
        (some->> (nrepl/message client {:op "close" :session session})
                 doall)
        (catch Throwable _ nil))
      (try
        (.close ^Closeable transport)
        (catch Throwable _ nil))
      (tel/log! {:level :debug :id ::closed} "Closed web REPL nREPL session")))
  nil)
