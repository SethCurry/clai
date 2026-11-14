(ns clai.daemon.files
  "Filesystem browsing for the web UI: `./` path completion and the file
  explorer.

  When the user types `./` in the prompt composer, the UI asks the daemon for
  the entries of the session's working directory and narrows them as the user
  keeps typing. The file explorer asks the same question without a prefix and
  then asks [[read-file]] for the text of whatever the user clicks. The daemon
  does the reading so the browser never needs direct access to the filesystem,
  and so the paths it offers are exactly the ones the agent can read.

  [[list-entries]] treats the typed text as a path relative to the session's
  working directory: everything before the last `/` names the directory to
  list, and the final segment is a case-insensitive name prefix. Entries are
  directories first, then files, each ordered by name.

  ## Example

      (list-entries {:root \"/home/me/clai\" :path \"src/clai/d\"})
      ;; => {:directory \"src/clai\"
      ;;     :prefix    \"d\"
      ;;     :entries   [{:name \"daemon\" :path \"src/clai/daemon/\"
      ;;                  :type \"directory\"}]}"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util Arrays]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def DefaultLimit
  "The number of completion entries returned when a request does not set
  `:limit`. Big enough to browse a flat directory, small enough to keep the
  popup usable."
  100)

(def CompletionRequest
  "Options for [[list-entries]]. `:root` is the directory the user may browse
  (normally the session's `cwd`); `:path` is the text typed after `./`, or the
  directory to list when it ends in `/`; `:limit` caps the number of returned
  entries; and `:include-hidden?` lists dotfiles unconditionally, which the
  file explorer does."
  [:map
   [:root [:maybe :string]]
   [:path {:optional true} [:maybe :string]]
   [:limit {:optional true} [:maybe [:int {:min 1 :max 1000}]]]
   [:include-hidden? {:optional true} [:maybe :boolean]]])

(def Entry
  "One completion candidate. `:path` is relative to the request's `:root` and
  ends in `/` for directories, so a client can keep descending into them."
  [:map
   [:name :string]
   [:path :string]
   [:type [:enum "directory" "file"]]])

(def CompletionResult
  "The reply to a completion request. `:directory` is the relative directory
  that was listed (empty at the root) and `:prefix` is the name fragment the
  entries were filtered by."
  [:map
   [:directory :string]
   [:prefix :string]
   [:entries [:vector Entry]]])

(def ReadRequest
  "Options for [[read-file]]. `:root` is the directory the caller may read from
  (normally the session's `cwd`) and `:path` is the file path relative to it."
  [:map
   [:root [:maybe :string]]
   [:path {:optional true} [:maybe :string]]])

(def FileContent
  "One file, as the file explorer previews it. `:content` holds at most
  [[MaxPreviewBytes]] of text and is empty when `:binary` is true, because
  those bytes were not text worth showing."
  [:map
   [:path :string]
   [:name :string]
   [:content :string]
   [:truncated :boolean]
   [:binary :boolean]])

;; ---------------------------------------------------------------------------
;; Path handling
;; ---------------------------------------------------------------------------

(defn- canonical-dir
  "The canonical `File` for `f` when it is a readable directory, else nil."
  [^File f]
  (when (and f (.isDirectory f) (.canRead f))
    (.getCanonicalFile f)))

(defn- within-root?
  "True when canonical `file` is `root` or lives below it."
  [^File root ^File file]
  (.startsWith (.toPath file) (.toPath root)))

(defn- resolve-in-root
  "Resolve relative `path` against `root`, returning a canonical `File`.

  Returns nil when the result escapes `root` (through `..` or a symlink), so
  neither listing nor reading can ever reach outside the session's working
  directory."
  [^File root path]
  (let [resolved (.getCanonicalFile (io/file root ^String path))]
    (when (within-root? root resolved)
      resolved)))

(defn- normalize-path
  "Normalize the typed `path`: trim it, use `/` separators, and drop the
  leading `./` and `/` that would otherwise look like a different path."
  [path]
  (-> (str (or path ""))
      str/trim
      (str/replace "\\" "/")
      (str/replace #"^\./" "")
      (str/replace #"^/+" "")))

(defn- split-path
  "Split the typed `path` into `{:directory :prefix}`.

  A trailing `/` lists the whole directory (`\"src/\"` becomes directory
  `\"src\"` with an empty prefix) and the final segment of anything else is
  the prefix to match."
  [path]
  (let [path     (normalize-path path)
        segments (str/split path #"/" -1)]
    {:directory (str/join "/" (butlast segments))
     :prefix    (if (str/ends-with? path "/") "" (last segments))}))

;; ---------------------------------------------------------------------------
;; Entry building
;; ---------------------------------------------------------------------------

(defn- relative-path
  "`file`'s path relative to canonical `root`, always using `/` separators."
  [^File root ^File file]
  (-> (.relativize (.toPath root) (.toPath file))
      str
      (str/replace File/separator "/")))

(defn- entry-for
  "Build the completion [[Entry]] for `file`, a direct child of `root`."
  [^File root ^File file]
  (let [directory? (.isDirectory file)]
    {:name (.getName file)
     :path (cond-> (relative-path root file)
             directory? (str "/"))
     :type (if directory? "directory" "file")}))

(defn- hidden?
  "True when `file` is a dotfile or dot-directory."
  [^File file]
  (str/starts-with? (.getName file) "."))

(defn- offered?
  "True when `file` belongs in a listing: dotfiles are left out unless the
  request asks for them or the user has already typed a leading `.`."
  [prefix include-hidden? ^File file]
  (or include-hidden?
      (not (hidden? file))
      (str/starts-with? prefix ".")))

(defn- matches-prefix?
  "True when `entry`'s name starts with `prefix`, ignoring case."
  [prefix {:keys [name]}]
  (str/starts-with? (str/lower-case name) (str/lower-case prefix)))

(defn- sort-key
  "Sort entries by kind (directories first) and then by name, ignoring case."
  [{:keys [name type]}]
  [(if (= type "directory") 0 1) (str/lower-case name) name])

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(defn list-entries
  "List completion entries under `:root` for the text the user typed after
  `./`, or for the directory named by a `:path` that ends in `/`.

  Returns a [[CompletionResult]]; a missing root, a directory that does not
  exist, or a path escaping the root all yield an empty `:entries` vector
  rather than an error, so the UI can keep typing."
  {:malli/schema [:-> CompletionRequest CompletionResult]}
  [{:keys [root path limit include-hidden?]}]
  (let [{:keys [directory prefix]} (split-path path)
        empty-result {:directory directory :prefix prefix :entries []}
        root-file    (when-not (str/blank? root)
                       (canonical-dir (io/file root)))
        dir          (when root-file
                       (canonical-dir
                        (if (str/blank? directory)
                          root-file
                          (resolve-in-root root-file directory))))]
    (if (nil? dir)
      empty-result
      (assoc empty-result
             :entries (->> (.listFiles dir)
                           (remove nil?)
                           (filter #(offered? prefix include-hidden? %))
                           (map #(entry-for root-file %))
                           (filter #(matches-prefix? prefix %))
                           (sort-by sort-key)
                           (take (or limit DefaultLimit))
                           vec)))))

;; ---------------------------------------------------------------------------
;; File preview
;; ---------------------------------------------------------------------------

(def MaxPreviewBytes
  "The largest file, in bytes, that [[read-file]] decodes. Anything longer is
  truncated, so a log or an archive opened by mistake cannot stall the
  browser."
  (* 128 1024))

(defn- binary-bytes?
  "True when `bytes` contain a NUL byte, which is the cheapest sign that a
  file is not text."
  [^bytes bytes]
  (loop [index 0]
    (cond
      (= index (alength bytes))  false
      (zero? (aget bytes index)) true
      :else                      (recur (inc index)))))

(defn- read-preview
  "Read at most [[MaxPreviewBytes]] of `file`, returning the bytes and whether
  the file continues past them."
  [^File file]
  (with-open [in (io/input-stream file)]
    (let [buffer (byte-array MaxPreviewBytes)
          count  (.readNBytes in buffer 0 MaxPreviewBytes)]
      {:bytes     (if (= count MaxPreviewBytes)
                    buffer
                    (Arrays/copyOf buffer (int count)))
       :truncated (pos? (.read in))})))

(defn read-file
  "Read one file under `:root` for the file explorer.

  Returns a [[FileContent]] for a readable regular file, and nil when the path
  is blank, does not name a file, or escapes `:root`."
  {:malli/schema [:-> ReadRequest [:maybe FileContent]]}
  [{:keys [root path]}]
  (let [path      (str/replace (normalize-path path) #"/+$" "")
        root-file (when-not (str/blank? root)
                    (canonical-dir (io/file root)))
        file      (when (and root-file (not (str/blank? path)))
                    (resolve-in-root root-file path))]
    (when (and file (.isFile file) (.canRead file))
      (let [{:keys [bytes truncated]} (read-preview file)
            binary                    (binary-bytes? bytes)]
        {:path      path
         :name      (.getName file)
         :content   (if binary "" (String. ^bytes bytes "UTF-8"))
         :truncated truncated
         :binary    binary}))))
