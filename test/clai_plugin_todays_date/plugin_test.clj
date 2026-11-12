(ns clai-plugin-todays-date.plugin-test
  "Tests for the today's-date system-prompt plugin."
  (:require [clai-plugin-todays-date.plugin :as todays-date]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.time LocalDate]))

(deftest format-date-uses-the-java-time-pattern
  (is (= "2024-02-03"
         (todays-date/format-date (LocalDate/of 2024 2 3) "yyyy-MM-dd")))
  (is (= "Saturday, February 3, 2024"
         (todays-date/format-date (LocalDate/of 2024 2 3)
                                  "EEEE, MMMM d, yyyy"))))

(deftest date-prefix-announces-the-date
  (is (= "Today's date is 2024-02-03."
         (todays-date/date-prefix (LocalDate/of 2024 2 3) "yyyy-MM-dd"))))

(deftest prepend-date-keeps-the-existing-prompt
  (is (str/ends-with? (todays-date/prepend-date "You are helpful." "yyyy-MM-dd")
                      "\n\nYou are helpful."))
  (is (str/starts-with? (todays-date/prepend-date "You are helpful."
                                                  "yyyy-MM-dd")
                        "Today's date is ")))

(deftest prepend-date-returns-only-the-date-without-a-prompt
  (is (re-matches #"Today's date is \d{4}-\d{2}-\d{2}\."
                  (todays-date/prepend-date nil "yyyy-MM-dd")))
  (is (= (todays-date/prepend-date nil "yyyy-MM-dd")
         (todays-date/prepend-date "   " "yyyy-MM-dd"))))

(deftest plugin-hook-prepends-the-date-to-the-default-prompt
  (let [descriptor  (todays-date/plugin {})
        system-hook (get-in descriptor [:hooks :system-prompt])
        prompt      (system-hook {:plugin {:name "today"}}
                                 (fn [_ctx] "Base prompt."))]
    (is (= "Prepends today's date to the system prompt."
           (:description descriptor)))
    (is (str/starts-with? prompt "Today's date is "))
    (is (str/ends-with? prompt "\n\nBase prompt."))))

(deftest plugin-configuration-selects-the-date-format
  (let [system-hook (get-in (todays-date/plugin {:date-format "yyyyMMdd"})
                            [:hooks :system-prompt])
        prompt      (system-hook {} (fn [_ctx] nil))]
    (is (re-matches #"Today's date is \d{8}\." prompt))))
