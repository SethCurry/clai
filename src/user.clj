(ns user
  (:require [malli.clj-kondo :as mc]
    [malli.instrument :as mi]))

(defn my-fn [] (print "Hello, world!"))

(def clai-ns [
  'clai.core
  ])

(defn regen-kondo []
  (doseq [x clai-ns]
    (-> (mc/collect x)
      (mc/linter-config)
    (mc/save! :clj {}))))
