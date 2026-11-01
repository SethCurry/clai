(ns user
  (:require [malli.clj-kondo :as mc]
    [malli.instrument :as mi]
    [malli.dev :as dev]
    [clai.core :as core]))

(defn my-fn [] (print "Hello, world!"))

(defn regen-kondo []
    (mi/collect! 'clai.core)
    (mc/emit!))

(defn start []
    (dev/start!))
