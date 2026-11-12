(ns clai.daemon.files
  "Filesystem path completion for the web UI's `@` mentions.

  When the user types `@` in the prompt composer, the UI asks the daemon for
  the entries of the session's working directory and narrows them as the user
  keeps typing. The daemon does the listing so the browser never needs direct
  access to the filesystem, and so the paths it offers are exactly the ones
  the agent can read.

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
  (:import [java.io File]))

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
  (normally the session's `cwd`); `:path` is the text typed after `@`; and
  `:limit` caps the number of returned entries."
  [:map
   [:root [:maybe :string]]
   [:path {:optional true} [:maybe :string]]
   [:limit {:optional true} [:maybe [:int {:min 1 :max 500}]]]])

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
  "Resolve relative `directory` against `root`, returning a canonical `File`.

  Returns nil when the result escapes `root` (through `..` or a symlink), so
  the endpoint can never list anything outside the session's working
  directory."
  [^File root directory]
  (let [resolved (.getCanonicalFile (io/file root ^String directory))]
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
  "True when `file` is a dotfile or dot-directory, which completions offer only
  when the user has already typed a leading `.`."
  [^File file]
  (str/starts-with? (.getName file) "."))

(defn- matches-prefix?
  "True when `entry`'s name starts with `prefix`, ignoring case."
  [prefix {:keys [name]}]
  (str/starts-with? (str/lower-case name) (str/lower-case prefix)))

(defn- sort-key
  "Sort entries by kind (directories first) and then by name, ignoring case."
  [{:keys [name type]}]
  [(if (= type "directory") 0 1) (str/lower-case name) name])

;; ---------------------------------------------------------------------------
;; Completion
;; ---------------------------------------------------------------------------

(defn list-entries
  "List completion entries under `:root` for the text the user typed after
  `@`.

  Returns a [[CompletionResult]]; a missing root, a directory that does not
  exist, or a path escaping the root all yield an empty `:entries` vector
  rather than an error, so the UI can keep typing."
  {:malli/schema [:-> CompletionRequest CompletionResult]}
  [{:keys [root path limit]}]
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
                           (remove #(and (hidden? %) (not (str/starts-with? prefix "."))))
                           (map #(entry-for root-file %))
                           (filter #(matches-prefix? prefix %))
                           (sort-by sort-key)
                           (take (or limit DefaultLimit))
                           vec)))))
