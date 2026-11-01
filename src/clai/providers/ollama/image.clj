(ns clai.providers.ollama.image
  "Image encoding helpers for Ollama requests."
  (:require [clai.providers.ollama.schemas :as schemas]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net URL]
           [java.util Base64]))

(defn encode-image
  "Base64-encode an image for inclusion in an `:images` array.

  Accepts:

    * a `java.io.File` or path string
    * a byte array
    * a `java.io.InputStream`
    * a URL string/`java.net.URL` (the contents are fetched and encoded)

  Returns a base64 string ready to send to Ollama."
  {:malli/schema [:-> #'schemas/image-source string?]}
  [src]
  (cond
    (bytes? src)
    (.encodeToString (Base64/getEncoder) ^bytes src)

    (or (instance? InputStream src)
        (instance? java.io.File src)
        (instance? URL src)
        (string? src))
    (let [^bytes ba (with-open [in (io/input-stream src)
                                out (ByteArrayOutputStream.)]
                      (io/copy in out)
                      (.toByteArray out))]
      (.encodeToString (Base64/getEncoder) ba))

    :else
    (throw (ex-info "Unsupported image source for encode-image"
                    {:src (type src)}))))
