(ns clai-plugin-todays-date.plugin
  "A `clai` plugin that prepends today's date to the system prompt.

  Load it as the entry namespace `clai-plugin-todays-date.plugin`. The optional
  `:date-format` plugin configuration accepts a `java.time.format` pattern and
  defaults to `yyyy-MM-dd`, for example:

      {:date-format \"EEEE, MMMM d, yyyy\"}"
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))

(def default-date-format
  "The `java.time.format` pattern used when the plugin configuration does not
  provide one."
  "yyyy-MM-dd")

(def DateFormat
  "A non-blank `java.time.format` date pattern."
  [:string {:min 1}])

(defn format-date
  "Format `date` with the `java.time.format` `pattern`."
  {:malli/schema [:-> [:fn #(instance? LocalDate %)] #'DateFormat :string]}
  [date pattern]
  (.format date (DateTimeFormatter/ofPattern pattern)))

(defn today
  "Return today's local date formatted with `pattern`."
  {:malli/schema [:-> #'DateFormat :string]}
  [pattern]
  (format-date (LocalDate/now) pattern))

(defn date-prefix
  "Return the system-prompt prefix that announces `date`."
  {:malli/schema [:-> [:fn #(instance? LocalDate %)] #'DateFormat :string]}
  [date pattern]
  (str "Today's date is " (format-date date pattern) "."))

(defn prepend-date
  "Prepend today's date to `prompt`.

  A blank or missing prompt becomes just the date prefix, so a session with
  no configured system prompt still learns today's date."
  {:malli/schema [:-> [:maybe :string] #'DateFormat :string]}
  [prompt pattern]
  (let [prefix (date-prefix (LocalDate/now) pattern)]
    (if (str/blank? prompt)
      prefix
      (str prefix "\n\n" prompt))))

(defn plugin
  "Build the plugin descriptor from the plugin's JSON `config`.

  `:date-format` selects the date pattern; every other configuration key is
  ignored. The returned descriptor installs one `:system-prompt` hook that
  prepends the date to the prompt produced by the rest of the hook chain."
  {:malli/schema [:-> [:maybe :map] :map]}
  [config]
  {:description "Prepends today's date to the system prompt."
   :version     "1.0.0"
   :hooks
   {:system-prompt
    (fn [ctx next]
      (prepend-date (next ctx)
                    (or (:date-format config) default-date-format)))}})
