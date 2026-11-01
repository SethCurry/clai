(ns clai.providers.ollama.blobs
  "Blob upload/lookup endpoints for the Ollama API.

  These functions take an explicit client; `clai.providers.ollama` wraps them
  with arities that fall back to the default `*client*`."
  (:require [clai.providers.ollama.http :as http]
            [clj-http.client :as http-client]
            [clojure.java.io :as io]
            [taoensso.telemere :as tel])
  (:import [java.io ByteArrayOutputStream InputStream]))

(defn blob-exists?
  "Implementation of `clai.providers.ollama/blob-exists?`."
  [client digest]
  (let [url   (http/api-url client (str "/api/blobs/" digest))
        opts  (-> (http/base-http-opts client)
                  (assoc :request-method :head
                         :url url
                         :headers (http/auth-headers client nil)
                         :as :byte-array))
        resp  (http-client/request opts)]
    (tel/log! {:level :debug
               :id    ::blob-exists
               :data  {:digest digest :status (:status resp)}}
              "Checked Ollama blob")
    (cond
      (= (:status resp) 200) true
      (= (:status resp) 404) false
      :else (http/throw-api-error :head url (:status resp) (:body resp)))))

(defn create-blob
  "Implementation of `clai.providers.ollama/create-blob`."
  [client digest content]
  (let [url   (http/api-url client (str "/api/blobs/" digest))
        bytes (cond
                (bytes? content)        content
                (instance? InputStream content)
                (with-open [in ^InputStream content
                            out (ByteArrayOutputStream.)]
                  (io/copy in out)
                  (.toByteArray out))
                (or (instance? java.io.File content)
                    (string? content))
                (with-open [in (io/input-stream content)
                            out (ByteArrayOutputStream.)]
                  (io/copy in out)
                  (.toByteArray out))
                :else (throw (ex-info "Unsupported blob content"
                                      {:content (type content)})))
        opts  (-> (http/base-http-opts client)
                  (assoc :request-method :post
                         :url url
                         :headers (http/auth-headers client
                                                     {"Content-Type"
                                                      "application/octet-stream"})
                         :body bytes
                         :as :byte-array))
        resp  (http-client/request opts)]
    (tel/log! {:level :debug
               :id    ::create-blob
               :data  {:digest digest
                       :bytes  (alength ^bytes bytes)
                       :status (:status resp)}}
              "Uploaded Ollama blob")
    (when-not (<= 200 (:status resp) 299)
      (http/throw-api-error :post url (:status resp) (:body resp)))
    true))
