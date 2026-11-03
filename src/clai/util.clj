(ns clai.util
  "Small shared helpers used across `clai` namespaces.

  This namespace deliberately depends only on `clai.log` (for stderr logging)
  and the standard library, so any other `clai` namespace can use it without
  pulling in the agent, provider, or tool layers."
  (:require [clai.log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayOutputStream File InputStream PushbackReader]
           [java.net URL]
           [java.nio.charset StandardCharsets]
           [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def non-empty-string
  "A string of at least one character."
  [:re #".+"])

;; ---------------------------------------------------------------------------
;; Var re-export
;; ---------------------------------------------------------------------------

(defmacro import-vars
  "Define public vars in the current namespace that alias the implementation
  vars named by `syms`, copying their docs, arglists, and malli schemas."
  [& syms]
  `(do
     ~@(for [sym syms]
         (let [local (symbol (name sym))]
           `(do
              (def ~local ~sym)
              (alter-meta! (var ~local)
                           merge
                           (select-keys (meta (var ~sym))
                                        [:doc :arglists :malli/schema])))))))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(defn error-message
  "Return the message of `e`, falling back to its class name when the
  throwable carries no message."
  [e]
  (or (ex-message e) (str (class e))))

;; ---------------------------------------------------------------------------
;; Bytes
;; ---------------------------------------------------------------------------

(defn ->bytes
  "Coerce `x` to a byte array.

  Accepts a byte array, `InputStream`, `File`, `URL`, or path string. Returns
  nil for nil or any other type, leaving the caller to decide whether that is
  an error."
  [x]
  (cond
    (nil? x)   nil
    (bytes? x) x

    (or (instance? InputStream x)
        (instance? File x)
        (instance? URL x)
        (string? x))
    (with-open [in  (io/input-stream x)
                out (ByteArrayOutputStream.)]
      (io/copy in out)
      (.toByteArray out))

    :else nil))

(defn read-at-most
  "Read up to `n` bytes from `in`, returning a byte array (possibly shorter).
  Stops at EOF."
  [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (>= off n)
        buf
        (let [read-n (.read in buf off (- n off))]
          (if (neg? read-n)
            (Arrays/copyOf buf off)
            (recur (+ off read-n))))))))

(defn read-text
  "Read at most `max-bytes` from `in` as UTF-8 text.

  Returns `{:text ... :truncated? ...}`; text past the cap is left unread."
  [^InputStream in max-bytes]
  (let [data       (read-at-most in (inc max-bytes))
        truncated? (> (alength ^bytes data) max-bytes)
        ^bytes kept (if truncated? (Arrays/copyOf ^bytes data (int max-bytes)) data)]
    {:text       (String. kept StandardCharsets/UTF_8)
     :truncated? truncated?}))

;; ---------------------------------------------------------------------------
;; Maps
;; ---------------------------------------------------------------------------

(defn select-present
  "Return the entries of `m` whose keys are in `ks` and whose values are
  non-nil, in the order of `ks`."
  [m ks]
  (into {}
        (keep (fn [k] (when-some [v (get m k)] [k v])))
        ks))

;; ---------------------------------------------------------------------------
;; EDN files
;; ---------------------------------------------------------------------------

(defn read-edn-file
  "Read `file` as EDN, returning its value (nil for an empty file).

  Read failures are logged under `opts`' `:error-id` and rethrown as an
  `ex-info` naming the file, so a malformed file is easy to spot. `opts`:

    :error-id  the telemere signal id for the failure
    :label     what to call the file in the error message (default \"EDN\")
    :data      extra data to include in the error signal
    :readers   a `clojure.edn/read` tag-reader map, when custom tagged
               literals are expected"
  ([file] (read-edn-file file nil))
  ([^File file {:keys [error-id label data readers]
                :or   {label "EDN"}}]
   (try
     (with-open [r (PushbackReader. (io/reader file))]
       (edn/read (cond-> {:eof nil}
                   readers (assoc :readers readers))
                 r))
     (catch Throwable e
       (tel/error! {:id   error-id
                    :data (merge {:file (.getAbsolutePath file)} data)}
                   e)
       (throw (ex-info (str "Failed to read " label " "
                            (.getAbsolutePath file) ": "
                            (error-message e))
                       {:file file :cause e}))))))
