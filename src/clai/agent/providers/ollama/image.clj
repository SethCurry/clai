(ns clai.agent.providers.ollama.image
  "Image encoding helpers for Ollama requests."
  (:require [clai.agent.providers.ollama.schemas :as schemas]
            [clai.util :as util])
  (:import [java.util Base64]))

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
  (if-let [bytes (util/->bytes src)]
    (.encodeToString (Base64/getEncoder) ^bytes bytes)
    (throw (ex-info "Unsupported image source for encode-image"
                    {:src (type src)}))))
