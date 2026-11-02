(ns clai.resources
  "Render ACP resource content blocks as text an LLM can read.

  ACP prompts may contain `resource_link` blocks that point at files (usually
  with `file://` URIs) and `resource` blocks that embed resource contents.
  Ollama's chat API only accepts text, so without conversion those blocks are
  silently dropped and the model never learns which files the user referenced.

  A `resource_link` describes a resource rather than carrying its bytes, so
  [[resource-link->text]] renders a small header with the link's metadata and,
  for local files, inlines the file's text contents (up to
  [[max-resource-bytes]]). Links to non-file URIs, missing files, and binary
  resources still produce a readable description so the model knows they were
  referenced.

  Embedded `resource` blocks carry their contents directly; text resources are
  inlined and binary blobs are described rather than dumped."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net URI]
           [java.nio.charset StandardCharsets]
           [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Limits & helpers
;; ---------------------------------------------------------------------------

(def max-resource-bytes
  "Maximum number of bytes to inline from a linked file, to keep prompt
  history bounded."
  (* 64 1024))

(def ^:private binary-sniff-bytes
  "How many leading bytes to inspect when deciding whether a file is binary."
  8192)

(defn- escape-attr
  "Escape `value` for use inside a double-quoted header attribute."
  [value]
  (-> (str value)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace #"\r?\n" " ")))

(defn- attr
  "Render ` key=\"value\"` when `value` is present and non-blank, else `\"\"`."
  [k value]
  (if (str/blank? (str value))
    ""
    (str " " (name k) "=\"" (escape-attr value) "\"")))

(defn- header
  "Render an opening resource tag with the given `attrs` map."
  [tag attrs]
  (str "<" (name tag)
       (apply str (map (fn [[k v]] (attr k v)) attrs))
       ">"))

(defn- closing-tag
  [tag]
  (str "</" (name tag) ">"))

;; ---------------------------------------------------------------------------
;; URI / file handling
;; ---------------------------------------------------------------------------

(defn- parse-uri
  "Parse `uri` as a `java.net.URI`, returning nil when it is not a valid URI."
  [uri]
  (when-not (str/blank? (str uri))
    (try
      (URI. (str uri))
      (catch Exception _ nil))))

(defn- uri->file
  "Resolve `uri` to a local `java.io.File`, or nil when it is not local.

  `file:` URIs are converted through `java.net.URI` so percent-escapes and
  spaces are handled correctly. URIs with no scheme are treated as paths,
  resolved against `cwd` when relative."
  [uri cwd]
  (let [u (parse-uri uri)]
    (cond
      (nil? u) nil
      (= "file" (.getScheme u))
      (try
        (File. u)
        (catch Exception _ nil))

      (nil? (.getScheme u))
      (let [f (io/file (str uri))]
        (if (.isAbsolute f) f (io/file (or cwd ".") (str uri))))

      :else nil)))

(defn- binary-bytes?
  "Heuristically decide whether `bytes` look like binary data rather than
  UTF-8 text. A NUL byte in the sniffed prefix is treated as binary."
  [^bytes bytes]
  (let [n (min (alength bytes) binary-sniff-bytes)]
    (loop [i 0]
      (cond
        (>= i n) false
        (zero? (aget bytes i)) true
        :else (recur (inc i))))))

(defn- read-at-most
  "Read up to `n` bytes from `in`, returning a byte array (possibly shorter)."
  [^java.io.InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (>= off n)
        buf
        (let [read-n (.read in buf off (- n off))]
          (if (neg? read-n)
            (Arrays/copyOf buf off)
            (recur (+ off read-n))))))))

(defn- read-file-contents
  "Read a local file as text for inlining.

  Returns a map with either `:text` (the decoded contents) and
  `:truncated?`, or `:error` describing why the contents could not be
  rendered."
  [^File f max-bytes]
  (cond
    (not (.exists f))        {:error "file does not exist"}
    (.isDirectory f)         {:error "path is a directory"}
    (not (.canRead f))       {:error "file is not readable"}
    :else
    (try
      (with-open [in (io/input-stream f)]
        (let [bytes      (read-at-most in (inc max-bytes))
              truncated? (> (alength bytes) max-bytes)
              bytes      (if truncated? (Arrays/copyOf bytes max-bytes) bytes)]
          (if (binary-bytes? bytes)
            {:error (str "binary resource (" (alength bytes) " bytes)")}
            {:text       (String. bytes StandardCharsets/UTF_8)
             :truncated? truncated?})))
      (catch Throwable e
        {:error (or (ex-message e) (str (class e)))}))))

(defn- rendered-file-contents
  "Render the body of a linked file: its text, a truncation marker, or an
  explanatory note when the contents are unavailable."
  [f max-bytes]
  (let [{:keys [text truncated? error]} (read-file-contents f max-bytes)]
    (cond
      (some? text) (str text
                        (when truncated?
                          (str "\n... [truncated at " max-bytes " bytes]")))
      (some? error) (str "[resource contents unavailable: " error "]")
      :else         "[resource contents unavailable]")))

;; ---------------------------------------------------------------------------
;; Content conversion
;; ---------------------------------------------------------------------------

(defn resource-link->text
  "Render an ACP `ResourceLink` content block as readable text.

  The result starts with a `<resource-link>` header carrying the link's
  `name`, `uri`, and other metadata. When the URI points at a local file the
  file's text contents are inlined (capped at `max-bytes`); otherwise a short
  note explains why the contents were not inlined. Either way the model sees
  the reference instead of the block being dropped.

  `opts` may contain:

    :cwd        directory used to resolve scheme-less relative paths
    :max-bytes  cap on inlined bytes (defaults to [[max-resource-bytes]])"
  ([link] (resource-link->text link nil))
  ([{:keys [name title uri mimeType size description] :as _link}
    {:keys [cwd max-bytes]
     :or   {max-bytes max-resource-bytes}}]
   (let [tag  :resource-link
         head (header tag {:name        name
                           :title       title
                           :uri         uri
                           :mimeType    mimeType
                           :size        size
                           :description description})
         f    (uri->file uri cwd)
         body (if f
                (rendered-file-contents f max-bytes)
                "[resource contents unavailable: only local file resources can be read]")]
     (str head "\n" body "\n" (closing-tag tag)))))

(defn embedded-resource->text
  "Render an ACP `EmbeddedResource` content block (`{:type \"resource\"
  :resource ...}`) as readable text.

  Text resources are inlined verbatim. Binary blobs are described by size and
  MIME type instead of being dumped into the prompt."
  ([block] (embedded-resource->text block nil))
  ([{:keys [resource]} _opts]
   (let [tag  :resource
         {:keys [uri mimeType text blob]} resource
         head (header tag {:uri uri :mimeType mimeType})
         body (cond
                (some? text) text
                (some? blob) (str "[binary resource omitted"
                                  (when (pos? (count (str blob)))
                                    (str ", " (count (str blob)) " base64 chars"))
                                  "]")
                :else "[resource contents unavailable]")]
     (str head "\n" body "\n" (closing-tag tag)))))
