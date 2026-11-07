(ns clai.ui.html.core
  (:require [hiccup2.core :as h]))

(defn render [items & {:keys [file]
                       :or {
                         file nil
                         }}]
  (let [rendered (h/html items)]
    (when (not (nil? file))
      (spit file rendered))
    rendered))
