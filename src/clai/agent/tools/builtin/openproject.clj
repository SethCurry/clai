(ns clai.agent.tools.builtin.openproject
  "Built-in OpenProject tools for managing work packages.

  The tools let an agent list projects and work packages, create new work
  packages, and update existing ones. They talk to the OpenProject API v3 over
  HTTP, authenticating with an API token (`:api-key`, sent as a bearer token)
  or a username/password pair (sent with HTTP basic auth).

  ## Configuration

  Settings come from the tool context's `:openproject` map (the `:openproject`
  sub-map of `clai.edn`), falling back to environment variables:

    :url        OPENPROJECT_URL or OPENPROJECT_BASE_URL
    :api-key    OPENPROJECT_API_KEY or OPENPROJECT_TOKEN
    :username   OPENPROJECT_USERNAME
    :password   OPENPROJECT_PASSWORD
    :project    OPENPROJECT_PROJECT  ; default project for create
    :type       OPENPROJECT_TYPE     ; default type for create
    :timeout    OPENPROJECT_TIMEOUT  ; milliseconds

  The URL may be the instance root (`https://openproject.example.com`) or the
  API root (`https://openproject.example.com/api/v3`).

  ## Work package references

  Tools accept a numeric id, an API href, or a human-readable name for linked
  values (project, type, status, priority, assignee, parent). Numeric ids and
  hrefs are used directly; names are resolved against the corresponding
  OpenProject collection, case-insensitively. This lets a model write
  `\"status\": \"In progress\"` instead of first looking up a status id."
  (:require [cheshire.core :as json]
            [clai.agent.tools.schema :as schema]
            [clai.util :as util]
            [clj-http.client :as http]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def NonEmptyString
  "A string of at least one character."
  util/non-empty-string)

(def Settings
  "OpenProject connection settings. All keys are optional; unresolved settings
  are filled in from environment variables by [[settings]]."
  [:map
   [:url {:optional true} [:maybe NonEmptyString]]
   [:base-url {:optional true} [:maybe NonEmptyString]]
   [:api-key {:optional true} [:maybe :string]]
   [:token {:optional true} [:maybe :string]]
   [:username {:optional true} [:maybe :string]]
   [:password {:optional true} [:maybe :string]]
   [:project {:optional true} [:maybe [:or NonEmptyString :int]]]
   [:type {:optional true} [:maybe [:or NonEmptyString :int]]]
   [:timeout {:optional true} [:maybe pos-int?]]])

(def Client
  "A resolved OpenProject API client. `:api-root` ends in `/api/v3`;
  `:origin` is the instance root used to render clickable work-package URLs."
  [:map
   [:api-root NonEmptyString]
   [:origin NonEmptyString]
   [:auth-header {:optional true} [:maybe NonEmptyString]]
   [:timeout pos-int?]
   [:project {:optional true} [:maybe [:or NonEmptyString :int]]]
   [:type {:optional true} [:maybe [:or NonEmptyString :int]]]])

;; ---------------------------------------------------------------------------
;; Settings and client construction
;; ---------------------------------------------------------------------------

(defn- env
  "Return the value of environment variable `name`, trimmed, or nil when it is
  unset or blank."
  [name]
  (some-> (System/getenv name)
          str/trim
          not-empty))

(defn- env-timeout
  "Return OPENPROJECT_TIMEOUT as a positive integer, or nil when absent or
  invalid."
  []
  (when-let [value (env "OPENPROJECT_TIMEOUT")]
    (let [n (parse-long value)]
      (when (and n (pos? n)) n))))

(defn settings
  "Resolve OpenProject settings for a tool call.

  `ctx`'s `:openproject` map wins over environment variables:
  `OPENPROJECT_URL` / `OPENPROJECT_BASE_URL`, `OPENPROJECT_API_KEY` /
  `OPENPROJECT_TOKEN`, `OPENPROJECT_USERNAME`, `OPENPROJECT_PASSWORD`,
  `OPENPROJECT_PROJECT`, `OPENPROJECT_TYPE`, and `OPENPROJECT_TIMEOUT`."
  {:malli/schema [:-> [:maybe [:map]] #'Settings]}
  [ctx]
  (let [configured (or (:openproject ctx) {})]
    (util/select-present
     {:url      (or (:url configured) (:base-url configured)
                    (env "OPENPROJECT_URL") (env "OPENPROJECT_BASE_URL"))
      :api-key  (or (:api-key configured) (:token configured)
                    (env "OPENPROJECT_API_KEY") (env "OPENPROJECT_TOKEN"))
      :username (or (:username configured) (env "OPENPROJECT_USERNAME"))
      :password (or (:password configured) (env "OPENPROJECT_PASSWORD"))
      :project  (or (:project configured) (env "OPENPROJECT_PROJECT"))
      :type     (or (:type configured) (env "OPENPROJECT_TYPE"))
      :timeout  (or (:timeout configured) (env-timeout))}
     [:url :api-key :username :password :project :type :timeout])))

(defn- normalize-api-root
  "Normalize `url` to an API root ending in `/api/v3`."
  [url]
  (let [root (str/replace (str/trim (str url)) #"/+$" "")]
    (if (str/ends-with? root "/api/v3")
      root
      (str root "/api/v3"))))

(defn- origin-of
  "Return the instance root for an API root by removing a trailing `/api/v3`."
  [api-root]
  (str/replace api-root #"/api/v3$" ""))

(defn- basic-auth-header
  "Build an HTTP basic auth header for `username` and `password`."
  [username password]
  (str "Basic "
       (.encodeToString (Base64/getEncoder)
                        (.getBytes (str username ":" password)
                                   StandardCharsets/UTF_8))))

(defn- auth-header
  "Build the `Authorization` header for `settings`, or nil when no credentials
  are configured. API tokens are sent as bearer tokens, matching OpenProject's
  documented authentication scheme."
  [{:keys [api-key token username password]}]
  (cond
    (not (str/blank? (str (or api-key token))))
    (str "Bearer " (or api-key token))

    (and (not (str/blank? (str username)))
         (some? password))
    (basic-auth-header username password)

    :else nil))

(defn make-client
  "Build an OpenProject client from `settings`.

  Throws an `ex-info` when no `:url`/`:base-url` is configured, since every
  request would fail without one. Credentials are optional: an instance that
  permits anonymous access can be read without them."
  {:malli/schema [:-> #'Settings #'Client]}
  [settings]
  (let [url (or (:url settings) (:base-url settings))]
    (when (str/blank? (str url))
      (throw (ex-info
              (str "OpenProject is not configured. Set :openproject {:url ...} "
                   "in clai.edn or OPENPROJECT_URL.")
              {:settings (dissoc settings :api-key :token :password)})))
    (let [api-root    (normalize-api-root url)
          auth-header (auth-header settings)]
      (cond-> {:api-root api-root
               :origin   (origin-of api-root)
               :timeout  (or (:timeout settings) 30000)}
        (some? auth-header)
        (assoc :auth-header auth-header)

        (some? (:project settings))
        (assoc :project (:project settings))

        (some? (:type settings))
        (assoc :type (:type settings))))))

(defn client-for
  "Build the OpenProject client described by tool context `ctx`."
  {:malli/schema [:-> [:maybe [:map]] #'Client]}
  [ctx]
  (make-client (settings ctx)))

;; ---------------------------------------------------------------------------
;; HTTP requests
;; ---------------------------------------------------------------------------

(defn- api-url
  "Join `client`'s API root with `path`."
  [client path]
  (str (:api-root client)
       (if (str/starts-with? path "/") path (str "/" path))))

(defn- web-url
  "Turn an API href into an absolute web URL on the OpenProject instance.

  API responses link to `/api/v3/work_packages/42`; users want the UI path
  `/work_packages/42`, so strip the API prefix."
  [client href]
  (let [href (str href)]
    (when-not (str/blank? href)
      (cond
        (str/starts-with? href "/api/v3")
        (str (:origin client) (str/replace href #"^/api/v3" ""))

        (str/starts-with? href "http")
        (str/replace href #"/api/v3(?=/|$)" "")

        (str/starts-with? href "/")
        (str (:origin client) href)

        :else
        (str (:origin client) "/" href)))))

(defn- ok?
  "True when `status` is a 2xx HTTP status."
  [status]
  (<= 200 status 299))

(defn- parse-body
  "Parse a JSON response body (string or bytes), returning nil for an empty
  body."
  [body]
  (let [text (cond
               (nil? body) nil
               (bytes? body) (String. ^bytes body StandardCharsets/UTF_8)
               :else (str body))]
    (when-not (str/blank? text)
      (try
        (json/parse-string text true)
        (catch Exception _ text)))))

(defn- error-message
  "Render an OpenProject error body as a concise message."
  [body]
  (let [parsed (parse-body body)]
    (cond
      (map? parsed)
      (let [message (or (:message parsed) (pr-str parsed))
            details (get-in parsed [:_embedded :details])]
        (if (seq details)
          (str message " " (pr-str details))
          message))

      (string? parsed) parsed
      :else "OpenProject API error")))

(defn- throw-api-error
  "Throw an `ex-info` describing a failed OpenProject request."
  [method path status body]
  (let [message (error-message body)]
    (tel/log! {:level :error
               :id    ::api-error
               :data  {:method method
                       :path   path
                       :status status
                       :error  message}}
              (str "OpenProject " (name method) " " path " failed: "
                   status " " message))
    (throw (ex-info (str "OpenProject " (name method) " " path " failed: "
                         status " " message)
                    {:method method :path path :status status
                     :body   (parse-body body)}))))

(defn request
  "Execute an OpenProject API request for `client` and return the parsed JSON
  response body.

  `opts`:

    :query  query parameters (a map)
    :body   request body (a Clojure map, encoded as JSON)

  Throws an `ex-info` on a non-2xx response; the exception carries the method,
  path, status, and parsed error body."
  {:malli/schema [:-> #'Client [:enum :get :post :patch :delete]
                  NonEmptyString [:map] :any]}
  [client method path {:keys [query body] :or {query {}}}]
  (let [start (System/nanoTime)
        resp  (http/request
               (cond-> {:request-method  method
                        :url             (api-url client path)
                        :headers         (cond-> {"Accept" "application/hal+json"}
                                           (some? (:auth-header client))
                                           (assoc "Authorization" (:auth-header client))

                                           (some? body)
                                           (assoc "Content-Type" "application/json"))
                        :query-params    query
                        :throw-exceptions false
                        :decompress-body  true
                        :socket-timeout   (:timeout client)
                        :conn-timeout     (:timeout client)
                        :as               :text}
                 (some? body) (assoc :body (json/encode body))))
        msecs (quot (- (System/nanoTime) start) 1000000)
        {:keys [status]} resp]
    (tel/log! {:level :debug
               :id    ::http-request
               :data  {:method     method
                       :path       path
                       :status     status
                       :elapsed-ms msecs}}
              (str "OpenProject " (name method) " " path))
    (if (ok? status)
      (parse-body (:body resp))
      (throw-api-error method path status (:body resp)))))

;; ---------------------------------------------------------------------------
;; Resource resolution
;; ---------------------------------------------------------------------------

(def ^:private kind-paths
  "API collection path for each resolvable link kind."
  {:project  "/projects"
   :type     "/types"
   :status   "/statuses"
   :priority "/priorities"
   :assignee "/users"
   :parent   "/work_packages"})

(defn- href-id
  "Extract the trailing numeric id from an API href, or nil."
  [href]
  (some-> (re-find #"/(\d+)/?$" (str href)) second parse-long))

(defn- direct-href
  "Return a direct API href for `value` when it is already a numeric id or an
  href. Returns nil when `value` needs name resolution."
  [kind value]
  (let [path (kind-paths kind)]
    (cond
      (map? value)
      (or (:href value)
          (when-some [id (:id value)] (str "/api/v3" path "/" id)))

      (number? value)
      (str "/api/v3" path "/" (long value))

      (string? value)
      (let [s (str/trim value)]
        (cond
          (str/blank? s) nil
          (str/starts-with? s "/api/v3/") s
          (str/starts-with? s "http") s
          (re-matches #"\d+" s) (str "/api/v3" path "/" s)
          :else nil))

      :else nil)))

(defn- collection-query
  "Build a query map that narrows `kind`'s collection to `value` where the
  API supports a name filter. Collections without a name filter are fetched
  wholesale and matched locally."
  [kind value]
  (case kind
    :project
    {"filters" (json/encode [{"name_and_identifier"
                              {"operator" "~" "values" [(str value)]}}])
     "pageSize" 100}

    :assignee
    {"filters" (json/encode [{"name"
                              {"operator" "~" "values" [(str value)]}}])
     "pageSize" 100}

    :parent
    {"filters" (json/encode [{"subject"
                              {"operator" "~" "values" [(str value)]}}])
     "pageSize" 100}

    {"pageSize" 500}))

(defn- collection-elements
  "Fetch the elements of the OpenProject collection at `path`."
  [client path query]
  (or (get-in (request client :get path {:query query})
              [:_embedded :elements])
      []))

(defn- resource-label
  "The user-facing label of a collection element, used when resolving names."
  [kind resource]
  (case kind
    :project (or (:name resource) (:identifier resource))
    :parent  (or (:subject resource) (:displayId resource) (str (:id resource)))
    (or (:name resource) (:login resource) (str (:id resource)))))

(defn- resource-matches?
  "True when `resource` matches `kind`'s label or identifier case-insensitively."
  [kind normalized resource]
  (let [labels (keep #(some-> % str str/trim str/lower-case)
                     [(resource-label kind resource)
                      (:identifier resource)
                      (:displayId resource)])]
    (some #(= normalized %) labels)))

(defn- resolve-by-name
  "Resolve `value` to a collection element for `kind`, or throw when nothing
  matches. Exact label matches win over substring matches."
  [client kind value]
  (let [path     (kind-paths kind)
        elements (collection-elements client path (collection-query kind value))
        lowered  (-> value str str/trim str/lower-case)
        exact    (first (filter #(resource-matches? kind lowered %) elements))
        partial  (first (filter (fn [resource]
                                  (str/includes? (str/lower-case
                                                  (str (resource-label kind resource)))
                                                 lowered))
                                elements))]
    (or exact
        partial
        (throw (ex-info (str "Could not resolve OpenProject " (name kind)
                             " '" value "'. Use its numeric id or API href.")
                        {:kind kind :value value})))))

(defn- resolve-link
  "Resolve `value` to an OpenProject API href for `kind`."
  [client kind value]
  (or (direct-href kind value)
      (some-> (resolve-by-name client kind value)
              (get-in [:_links :self :href]))
      (throw (ex-info (str "Could not resolve OpenProject " (name kind)
                           " '" value "'.")
                      {:kind kind :value value}))))

(defn- filter-value
  "Resolve a filter value to the ids OpenProject's collection filters expect.
  Sequences are resolved element-wise."
  [client kind value]
  (if (sequential? value)
    (mapv #(filter-value client kind %) value)
    (or (href-id (resolve-link client kind value))
        (str value))))

;; ---------------------------------------------------------------------------
;; Filter and sort construction
;; ---------------------------------------------------------------------------

(defn- parse-filters
  "Parse the raw `filters` argument. A string is parsed as JSON; a vector is
  returned as-is; nil/blank returns nil."
  [filters]
  (cond
    (nil? filters) filters
    (string? filters)
    (when-not (str/blank? filters)
      (let [parsed (json/parse-string filters true)]
        (when-not (sequential? parsed)
          (throw (ex-info "OpenProject filters must be a JSON array."
                          {:filters filters})))
        parsed))
    (sequential? filters) filters
    :else (throw (ex-info "OpenProject filters must be a JSON array or vector."
                          {:filters filters}))))

(defn- simple-filter
  "Build one OpenProject filter entry from a convenience argument. OpenProject
  filters always take a JSON array of values."
  [client key kind value]
  {:pre [(some? value)]}
  {key {"operator" (if (= key "subject") "~" "=")
        "values"   (if (= key "subject")
                     [(str value)]
                     (let [resolved (filter-value client kind value)]
                       (if (sequential? resolved) resolved [resolved])))}})

(defn- build-filters
  "Combine raw `filters` with the convenience filters (`project`, `status`,
  `type`, `assignee`, `priority`, `subject`). Returns nil when no filter was
  requested, so OpenProject's own default (open work packages) applies."
  [client {:keys [project status type assignee priority subject filters]}]
  (let [raw    (parse-filters filters)
        simple (cond-> []
                 (some? project)  (conj (simple-filter client "project_id" :project project))
                 (some? status)   (conj (simple-filter client "status_id" :status status))
                 (some? type)     (conj (simple-filter client "type_id" :type type))
                 (some? assignee) (conj (simple-filter client "assigned_to_id" :assignee assignee))
                 (some? priority) (conj (simple-filter client "priority_id" :priority priority))
                 (some? subject)  (conj (simple-filter client "subject" nil subject)))]
    (cond
      (and (nil? raw) (empty? simple)) nil
      :else (into (vec (or raw [])) simple))))

(defn- parse-sort
  "Parse `sort_by` into OpenProject's sort array. Accepts a JSON string, a
  vector, or a simple `\"field direction\"` string."
  [sort-by]
  (cond
    (nil? sort-by) nil
    (sequential? sort-by) sort-by
    (string? sort-by)
    (let [s (str/trim sort-by)]
      (cond
        (str/blank? s) nil
        (str/starts-with? s "[") (json/parse-string s true)
        :else (let [[field direction] (str/split s #"\s+")]
                [[field (or direction "asc")]])))
    :else nil))

;; ---------------------------------------------------------------------------
;; Formatting
;; ---------------------------------------------------------------------------

(defn- truncate
  "Truncate `text` to `max-chars`, appending an ellipsis when shortened."
  [text max-chars]
  (let [text (str text)]
    (if (> (count text) max-chars)
      (str (subs text 0 max-chars) "...")
      text)))

(defn- attribute-line
  "Render the linked attributes of `wp` as one comma-separated line."
  [{:keys [startDate dueDate] :as wp}]
  (let [links (:_links wp)]
    (->> [["project" (get-in links [:project :title])]
          ["type" (get-in links [:type :title])]
          ["status" (get-in links [:status :title])]
          ["priority" (get-in links [:priority :title])]
          ["assignee" (get-in links [:assignee :title])]
          ["start" startDate]
          ["due" dueDate]]
         (keep (fn [[label value]]
                 (when-not (str/blank? (str value))
                   (str label ": " value))))
         (str/join ", "))))

(defn- work-package-summary
  "Render one work package as a compact multi-line summary."
  [client wp]
  (let [href       (get-in wp [:_links :self :href])
        label      (or (:displayId wp) (str (:id wp)))
        attributes (attribute-line wp)]
    (str label ": " (or (:subject wp) "(no subject)")
         (when-not (str/blank? attributes)
           (str "\n  " attributes))
         "\n  id: " (:id wp)
         (when-let [url (web-url client href)]
           (str " | url: " url)))))

(defn- work-package-details
  "Render a work package in more detail, for create/update results."
  [client wp]
  (let [description (get-in wp [:description :raw])]
    (str (work-package-summary client wp)
         (when-not (str/blank? (str description))
           (str "\n  description: "
                (truncate (str/replace (str description) #"\s+" " ") 500))))))

(defn- format-project
  "Render one project as a compact line."
  [client project]
  (let [href (get-in project [:_links :self :href])]
    (str (or (:name project) (:identifier project) (str (:id project)))
         " (id " (:id project)
         (when-not (str/blank? (str (:identifier project)))
           (str ", identifier " (:identifier project)))
         ")"
         (when-let [url (web-url client href)]
           (str " | " url)))))

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn- as-positive-int
  "Coerce `value` to an integer in `[1, max-value]`, falling back to `default`
  when it is missing or invalid. A nil `max-value` means no upper bound."
  [value default max-value]
  (let [parsed (cond
                 (number? value) (long value)
                 (string? value) (parse-long (str/trim value))
                 :else nil)
        n      (max 1 (or parsed default))]
    (if max-value (min n max-value) n)))

(defn list-projects-fn
  "Tool: list OpenProject projects visible to the configured user.

  Arguments: `query` (optional name/identifier substring), `page_size`
  (default 25, max 100), and `offset` (page number, default 1). Returns one
  line per project with its id, identifier, and URL."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [{:keys [query page_size offset]} ctx]
  (let [client  (client-for ctx)
        filters (when-not (str/blank? (str query))
                  (json/encode [{"name_and_identifier"
                                 {"operator" "~" "values" [(str query)]}}]))
        resp    (request client :get "/projects"
                         {:query (cond-> {"pageSize" (as-positive-int page_size 25 100)
                                          "offset"   (as-positive-int offset 1 nil)}
                                   (some? filters) (assoc "filters" filters))})
        projects (get-in resp [:_embedded :elements] [])
        total    (:total resp)]
    (if (empty? projects)
      "No OpenProject projects found."
      (str "Found " (count projects) " project(s)"
           (when (and total (> total (count projects)))
             (str " of " total " total"))
           ":\n\n"
           (str/join "\n" (map #(format-project client %) projects))))))

(defn list-work-packages-fn
  "Tool: list OpenProject work packages.

  Arguments:

    :project    project id, identifier, or name
    :status     status id or name
    :type       type id or name
    :assignee   assignee id or name
    :priority   priority id or name
    :subject    subject substring
    :filters    raw OpenProject filters (JSON string or vector)
    :sort_by    sort field/direction, e.g. \"updated_at desc\"
    :page_size  page size (default 25, max 100)
    :offset     page number (default 1)

  With no filters, OpenProject's default (open work packages) is used. Pass
  `:filters \"[]\"` to list work packages in every status."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [args ctx]
  (let [client  (client-for ctx)
        filters (build-filters client args)
        sort-by (parse-sort (:sort_by args))
        resp    (request client :get "/work_packages"
                         {:query (cond-> {"pageSize" (as-positive-int (:page_size args) 25 100)
                                          "offset"   (as-positive-int (:offset args) 1 nil)}
                                   (some? filters) (assoc "filters" (json/encode filters))
                                   (some? sort-by) (assoc "sortBy" (json/encode sort-by)))})
        wps     (get-in resp [:_embedded :elements] [])
        total   (:total resp)]
    (if (empty? wps)
      "No OpenProject work packages found."
      (str "Found " (count wps) " work package(s)"
           (when (and total (> total (count wps)))
             (str " of " total " total"))
           ":\n\n"
           (str/join "\n\n" (map #(work-package-summary client %) wps))))))

(defn- assoc-link
  "Associate `value` as a `_links` entry named `link` in `payload`, resolving
  it to an API href first. Argument order supports `cond->` threading."
  [payload client link value]
  (assoc-in payload [:_links link] {:href (resolve-link client link value)}))

(defn- add-write-fields
  "Add the writable work-package fields present in `args` to `payload`.

  Presence is checked with `contains?`, so explicitly passing `null` clears a
  date field instead of leaving it unchanged."
  [client args payload]
  (cond-> payload
    (contains? args :subject)
    (assoc :subject (:subject args))

    (contains? args :description)
    (assoc :description {:raw (str (or (:description args) ""))})

    (contains? args :start_date)
    (assoc :startDate (:start_date args))

    (contains? args :due_date)
    (assoc :dueDate (:due_date args))

    (contains? args :estimated_time)
    (assoc :estimatedTime (:estimated_time args))

    (and (contains? args :project) (some? (:project args)))
    (assoc-link client :project (:project args))

    (and (contains? args :status) (some? (:status args)))
    (assoc-link client :status (:status args))

    (and (contains? args :priority) (some? (:priority args)))
    (assoc-link client :priority (:priority args))

    (and (contains? args :assignee) (some? (:assignee args)))
    (assoc-link client :assignee (:assignee args))

    (and (contains? args :type) (some? (:type args)))
    (assoc-link client :type (:type args))

    (and (contains? args :parent) (some? (:parent args)))
    (assoc-link client :parent (:parent args))))

(defn create-work-package-fn
  "Tool: create an OpenProject work package (task).

  Requires `:subject` and a project (from `:project` or the configured
  default). `:type` is optional when the project supplies a default type.
  Also accepts `:description`, `:status`, `:priority`, `:assignee`,
  `:start_date`, `:due_date`, `:estimated_time`, and `:parent`."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [args ctx]
  (let [client  (client-for ctx)
        subject (some-> (:subject args) str str/trim)
        project (or (:project args) (:project client))
        type    (or (:type args) (:type client))]
    (when (str/blank? subject)
      (throw (ex-info "Creating an OpenProject work package requires `subject`."
                      {:args (dissoc args :description)})))
    (when (nil? project)
      (throw (ex-info
              (str "Creating an OpenProject work package requires `project` "
                   "(or an :openproject :project default).")
              {:args (dissoc args :description)})))
    (let [base    (assoc-link {:subject subject} client :project project)
          base    (cond-> base
                    (some? type) (assoc-link client :type type))
          payload (add-write-fields client (dissoc args :subject :project :type) base)
          wp      (request client :post "/work_packages" {:body payload})]
      (str "Created OpenProject work package "
           (or (:displayId wp) (:id wp)) ".\n\n"
           (work-package-details client wp)))))

(defn- work-package-id
  "Extract a numeric work-package id from `value`, which may be a number, a
  numeric string, or an API href."
  [value]
  (let [n (cond
            (number? value) (long value)
            (string? value) (or (parse-long (str/trim value))
                                (href-id value))
            :else nil)]
    (when-not (and n (pos? n))
      (throw (ex-info (str "Invalid OpenProject work package id '" value
                           "'. Use the numeric id from a list result.")
                      {:value value})))
    n))

(defn- current-lock-version
  "Fetch the current `lockVersion` for work package `id`."
  [client id]
  (let [wp (request client :get (str "/work_packages/" id) {})]
    (or (:lockVersion wp)
        (throw (ex-info (str "OpenProject did not return a lockVersion for "
                             "work package " id ".")
                        {:id id})))))

(defn update-work-package-fn
  "Tool: update an OpenProject work package.

  Requires `:id`. Any of `:subject`, `:description`, `:status`, `:type`,
  `:priority`, `:assignee`, `:project`, `:parent`, `:start_date`,
  `:due_date`, and `:estimated_time` may be changed. `:lock_version` may be
  supplied to avoid the extra read; otherwise the current version is fetched
  before the update so OpenProject's optimistic locking succeeds."
  {:malli/schema [:-> #'schema/tool-args #'schema/tool-ctx :string]}
  [args ctx]
  (let [client   (client-for ctx)
        id       (work-package-id (:id args))
        writable (dissoc args :id :lock_version)
        payload  (add-write-fields client writable {})]
    (when (empty? payload)
      (throw (ex-info "No OpenProject work package fields to update were given."
                      {:id id})))
    (let [lock-version (or (:lock_version args)
                           (current-lock-version client id))
          wp           (request client :patch (str "/work_packages/" id)
                                {:body (assoc payload :lockVersion lock-version)})]
      (str "Updated OpenProject work package "
           (or (:displayId wp) (:id wp)) ".\n\n"
           (work-package-details client wp)))))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def ^:private list-projects-spec
  {:type "function"
   :function
   {:name "openproject_list_projects"
    :description
    "List OpenProject projects visible to the configured user. Use it to find
    project ids, identifiers, and names for creating or filtering work
    packages. Requires OpenProject to be configured in clai.edn or through
    OPENPROJECT_URL and credentials."
    :parameters
    {:type "object"
     :properties
     {"query"     {:type "string"
                   :description "Optional name or identifier substring to search for."}
      "page_size" {:type "integer"
                   :description "Results per page (default 25, max 100)."}
      "offset"    {:type "integer"
                   :description "Page number (default 1)."}}
     :required []}}})

(def ^:private list-work-packages-spec
  {:type "function"
   :function
   {:name "openproject_list_work_packages"
    :description
    "List OpenProject work packages (tasks). Filter by project, status, type,
    assignee, priority, or subject. With no filters OpenProject's default
    (open work packages) applies; pass filters \"[]\" to include closed work
    packages too. Results include the numeric id needed for updates."
    :parameters
    {:type "object"
     :properties
     {"project"   {:type "string"
                   :description "Project id, identifier, or name."}
      "status"    {:type "string"
                   :description "Status id or name, e.g. \"In progress\"."}
      "type"      {:type "string"
                   :description "Work package type id or name, e.g. \"Task\"."}
      "assignee"  {:type "string"
                   :description "Assignee id or name."}
      "priority"  {:type "string"
                   :description "Priority id or name, e.g. \"High\"."}
      "subject"   {:type "string"
                   :description "Subject substring to match."}
      "filters"   {:type "string"
                   :description "Raw OpenProject filters as a JSON array, for advanced queries. Combined with the convenience filters."}
      "sort_by"   {:type "string"
                   :description "Sort expression such as \"updated_at desc\" or \"id asc\"."}
      "page_size" {:type "integer"
                   :description "Results per page (default 25, max 100)."}
      "offset"    {:type "integer"
                   :description "Page number (default 1)."}}
     :required []}}})

(def ^:private write-field-properties
  "JSON-schema properties shared by create and update work package tools."
  {"subject"        {:type "string"
                     :description "Work package subject/title."}
   "description"    {:type "string"
                     :description "Work package description in Markdown."}
   "status"         {:type "string"
                     :description "Status id or name, e.g. \"In progress\" or \"Closed\"."}
   "type"           {:type "string"
                     :description "Work package type id or name, e.g. \"Task\" or \"Bug\"."}
   "priority"       {:type "string"
                     :description "Priority id or name, e.g. \"High\"."}
   "assignee"       {:type "string"
                     :description "Assignee id or name."}
   "parent"         {:type "string"
                     :description "Parent work package id or href."}
   "start_date"     {:type "string"
                     :description "Start date in YYYY-MM-DD format."}
   "due_date"       {:type "string"
                     :description "Due date in YYYY-MM-DD format."}
   "estimated_time" {:type "string"
                     :description "Estimated time as an ISO-8601 duration, e.g. \"PT2H\"."}})

(def ^:private create-work-package-spec
  {:type "function"
   :function
   {:name "openproject_create_work_package"
    :description
    "Create an OpenProject work package (task). Requires a subject and either
    a project argument or a configured default project. Type may be supplied
    or left to the project's default. Returns the created work package with
    its id and URL."
    :parameters
    {:type "object"
     :properties (assoc write-field-properties
                        "project" {:type "string"
                                   :description "Project id, identifier, or name. Defaults to the configured project."})
     :required ["subject"]}}})

(def ^:private update-work-package-spec
  {:type "function"
   :function
   {:name "openproject_update_work_package"
    :description
    "Modify an existing OpenProject work package (task). Supply the numeric id
    and any fields to change. Status and other links accept names or ids. The
    current lock version is fetched automatically for OpenProject's optimistic
    locking. Use this to reassign, reprioritize, reschedule, edit, or close
    tasks."
    :parameters
    {:type "object"
     :properties (assoc write-field-properties
                        "id"           {:type "integer"
                                        :description "Numeric work package id from a list or create result."}
                        "project"      {:type "string"
                                        :description "Move the work package to a project id, identifier, or name."}
                        "lock_version" {:type "integer"
                                        :description "Optional current lockVersion. Fetched automatically when omitted."})
     :required ["id"]}}})

(defn tools
  "Return the built-in OpenProject tool maps."
  {:malli/schema [:-> [:vector #'schema/Tool]]}
  []
  [{:id "openproject_list_projects"
    :handler list-projects-fn
    :spec list-projects-spec}
   {:id "openproject_list_work_packages"
    :handler list-work-packages-fn
    :spec list-work-packages-spec}
   {:id "openproject_create_work_package"
    :handler create-work-package-fn
    :spec create-work-package-spec}
   {:id "openproject_update_work_package"
    :handler update-work-package-fn
    :spec update-work-package-spec}])
