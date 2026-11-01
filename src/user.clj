(ns user
  (:require [malli.clj-kondo :as mc]
    [malli.instrument :as mi]
    [malli.dev :as dev]
    [clai.core :as core]))

(def ^:private instrument-namespaces
  "Namespaces whose `:malli/schema` metadata should be collected for
  instrumentation and clj-kondo annotations."
  '[clai.agent
    clai.agent.loop
    clai.agent.messages
    clai.config
    clai.state
    clai.tools])

(defn my-fn [] (print "Hello, world!"))

(defn regen-kondo []
    (mi/collect! {:ns instrument-namespaces})
    (mc/emit!))

(defn start []
    (dev/start!))
