(ns clai.task
  (:import [java.util.concurrent LinkedTransferQueue]))

(defn new-queue []
  (new LinkedTransferQueue))
