(ns clai.agent.tools.builtin.openproject-test
  "Tests for the OpenProject work-package tools. Every test replaces
  `clj-http.client/request`, so no network access or live OpenProject instance
  is needed."
  (:require [cheshire.core :as json]
            [clai.agent.tools.builtin.openproject :as openproject]
            [clai.test-support :refer [quiet-logs]]
            [clj-http.client]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)

(def ^:private ctx
  "Tool context pointing at a fake OpenProject instance."
  {:openproject {:url "https://op.example.com" :api-key "test-token"}})

(def ^:private tools-by-id
  "OpenProject tools keyed by id."
  (into {} (map (juxt :id identity)) (openproject/tools)))

(defn- handler
  "Return the handler for OpenProject tool `id`."
  [id]
  (:handler (get tools-by-id id)))

(defn- json-response
  "Build a fake HTTP response with status `status` and JSON `body`."
  [status body]
  {:status status :body (json/encode body)})

(defn- collection
  "Build an OpenProject collection response from `elements`."
  [elements]
  {:_type "Collection"
   :total (count elements)
   :_embedded {:elements elements}})

(defn- project-element
  "A project element as returned by `/api/v3/projects`."
  [id name identifier]
  {:id id
   :name name
   :identifier identifier
   :_links {:self {:href (str "/api/v3/projects/" id)}}})

(defn- status-element
  "A status element as returned by `/api/v3/statuses`."
  [id name]
  {:id id
   :name name
   :_links {:self {:href (str "/api/v3/statuses/" id)}}})

(defn- type-element
  "A work-package type element as returned by `/api/v3/types`."
  [id name]
  {:id id
   :name name
   :_links {:self {:href (str "/api/v3/types/" id)}}})

(defn- work-package
  "A work-package element as returned by `/api/v3/work_packages`."
  [id display-id subject status]
  {:id id
   :displayId display-id
   :subject subject
   :_links {:self {:href (str "/api/v3/work_packages/" id)}
            :project {:title "Demo"}
            :type {:title "Task"}
            :status {:title status}}})

(defn- comment-activity
  "A comment activity as returned by
  `/api/v3/work_packages/{id}/activities`."
  [id user-id text created-at]
  {:_type "Activity::Comment"
   :id id
   :comment {:format "markdown" :raw text :html (str "<p>" text "</p>")}
   :details []
   :createdAt created-at
   :updatedAt created-at
   :_links {:self {:href (str "/api/v3/activities/" id)}
            :user {:href (str "/api/v3/users/" user-id)}
            :workPackage {:href "/api/v3/work_packages/42" :title "Ship it"}}})

(defn- change-activity
  "A journal entry that records field changes without comment text."
  [id user-id created-at changes]
  {:_type "Activity"
   :id id
   :comment {:format "markdown" :raw "" :html ""}
   :details (mapv (fn [change] {:format "custom" :raw change}) changes)
   :createdAt created-at
   :_links {:self {:href (str "/api/v3/activities/" id)}
            :user {:href (str "/api/v3/users/" user-id)}
            :workPackage {:href "/api/v3/work_packages/42" :title "Ship it"}}})

(defn- activities-request
  "A fake `clj-http.client/request` serving work package 42's activities and
  the users their comments link to. `activities` and `users` are thunks so a
  test can raise a failure only when a lookup happens."
  [requests activities users]
  (fn [req]
    (swap! requests conj req)
    (cond
      (str/ends-with? (:url req) "/api/v3/work_packages/42/activities")
      (json-response 200 (collection (activities)))

      (str/ends-with? (:url req) "/api/v3/users/5")
      (users)

      :else
      (throw (ex-info "unexpected request" {:url (:url req)})))))

(defn- user-response
  "The response for `GET /api/v3/users/5`."
  []
  (json-response 200 {:id 5 :name "Seth Curry"}))

(defn- request-filters
  "Parse the `filters` query parameter of a captured request."
  [req]
  (some-> (get-in req [:query-params "filters"])
          (json/parse-string)))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(deftest make-client-normalizes-url-and-token-auth
  (let [client (openproject/make-client {:url "https://op.example.com/api/v3/"
                                         :api-key "tok"
                                         :project 7
                                         :type "Task"
                                         :timeout 1234})]
    (is (= "https://op.example.com/api/v3" (:api-root client)))
    (is (= "https://op.example.com" (:origin client)))
    (is (= "Bearer tok" (:auth-header client)))
    (is (= 7 (:project client)))
    (is (= "Task" (:type client)))
    (is (= 1234 (:timeout client)))))

(deftest make-client-supports-basic-auth
  (let [client (openproject/make-client {:url "https://op.example.com"
                                         :username "me"
                                         :password "secret"})]
    (is (= "Basic bWU6c2VjcmV0" (:auth-header client))
        "OpenProject accepts HTTP basic auth with a username/password pair")))

(deftest make-client-requires-a-url
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not configured"
       (openproject/make-client {}))
      "without a URL every request would fail, so fail early with a clear
      message instead"))

(deftest settings-prefers-context-values
  (let [settings (openproject/settings
                  {:openproject {:url     "https://ctx.example.com"
                                 :api-key "ctx-key"
                                 :project "demo"}})]
    (is (= "https://ctx.example.com" (:url settings)))
    (is (= "ctx-key" (:api-key settings)))
    (is (= "demo" (:project settings)))))

;; ---------------------------------------------------------------------------
;; Listing projects
;; ---------------------------------------------------------------------------

(deftest list-projects-searches-and-renders-projects
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (json-response 200
                                   (collection [(project-element 7 "Demo" "demo")])))]
      (let [result ((handler "openproject_list_projects")
                    {:query "Dem" :page_size 5 :offset 2} ctx)
            req    (first @requests)]
        (is (str/includes? result "Demo (id 7, identifier demo)"))
        (is (str/includes? result "https://op.example.com/projects/7"))
        (is (= "Bearer test-token" (get-in req [:headers "Authorization"])))
        (is (= "https://op.example.com/api/v3/projects" (:url req)))
        (is (= 5 (get-in req [:query-params "pageSize"])))
        (is (= 2 (get-in req [:query-params "offset"])))
        (is (= [{"name_and_identifier"
                 {"operator" "~" "values" ["Dem"]}}]
               (request-filters req)))))))

;; ---------------------------------------------------------------------------
;; Listing work packages
;; ---------------------------------------------------------------------------

(deftest list-work-packages-resolves-names-and-sends-filters
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (cond
                      (str/ends-with? (:url req) "/api/v3/projects")
                      (json-response 200
                                     (collection [(project-element 7 "Demo" "demo")]))

                      (str/ends-with? (:url req) "/api/v3/statuses")
                      (json-response 200
                                     (collection [(status-element 9 "In progress")]))

                      (str/ends-with? (:url req) "/api/v3/work_packages")
                      (json-response 200
                                     (collection [(work-package 11 "DEMO-1" "Fix login"
                                                                "In progress")]))

                      :else
                      (throw (ex-info "unexpected request" {:url (:url req)}))))]
      (let [result ((handler "openproject_list_work_packages")
                    {:project    "Demo"
                     :status     "in progress"
                     :sort_by    "updated_at desc"
                     :page_size  2
                     :offset     3} ctx)
            wp-req (last @requests)]
        (is (str/includes? result "DEMO-1: Fix login"))
        (is (str/includes? result "status: In progress"))
        (is (str/includes? result "id: 11"))
        (is (= [{"project_id" {"operator" "=" "values" [7]}}
                {"status_id"  {"operator" "=" "values" [9]}}]
               (request-filters wp-req)))
        (is (= [["updated_at" "desc"]]
               (json/parse-string (get-in wp-req [:query-params "sortBy"]))))
        (is (= 2 (get-in wp-req [:query-params "pageSize"])))
        (is (= 3 (get-in wp-req [:query-params "offset"])))))))

(deftest list-work-packages-uses-openproject-defaults-without-filters
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (json-response 200 (collection [])))]
      (is (= "No OpenProject work packages found."
             ((handler "openproject_list_work_packages") {} ctx)))
      (is (= 1 (count @requests))
          "with no filters there are no name-resolution requests")
      (is (nil? (get-in (first @requests) [:query-params "filters"]))
          "omitting filters keeps OpenProject's default open-work-package
          filter"))))

(deftest list-work-packages-raw-empty-filters-include-all-statuses
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (json-response 200 (collection [])))]
      ((handler "openproject_list_work_packages") {:filters "[]"} ctx)
      (is (= [] (request-filters (first @requests)))
          "an explicit empty filter array disables OpenProject's default"))))

(deftest list-work-packages-reports-api-errors
  (with-redefs [clj-http.client/request
                (fn [_req]
                  (json-response 404 {:_type "Error"
                                      :errorIdentifier "urn:openproject-org:api:v3:errors:NotFound"
                                      :message "Work package not found"}))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"OpenProject get /work_packages failed: 404 Work package not found"
         ((handler "openproject_list_work_packages") {} ctx)))))

(deftest list-work-packages-without-configuration-fails-clearly
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"OpenProject is not configured"
       ((handler "openproject_list_work_packages") {} {}))))

;; ---------------------------------------------------------------------------
;; Creating work packages
;; ---------------------------------------------------------------------------

(deftest create-work-package-builds-links-and-write-fields
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (cond
                      (= :post (:request-method req))
                      (json-response 200
                                     {:id 12
                                      :displayId "DEMO-2"
                                      :subject "New task"
                                      :lockVersion 0
                                      :description {:raw "**Body**"}
                                      :_links {:self {:href "/api/v3/work_packages/12"}
                                               :status {:title "In progress"}}})

                      (str/ends-with? (:url req) "/api/v3/types")
                      (json-response 200
                                     (collection [(type-element 8 "Task")]))

                      (str/ends-with? (:url req) "/api/v3/statuses")
                      (json-response 200
                                     (collection [(status-element 9 "In progress")]))

                      :else
                      (throw (ex-info "unexpected request" {:url (:url req)}))))]
      (let [result ((handler "openproject_create_work_package")
                    {:subject        "New task"
                     :project        "7"
                     :type           "Task"
                     :status         "In progress"
                     :description    "**Body**"
                     :due_date       "2026-01-01"
                     :estimated_time "PT2H"} ctx)
            post   (first (filter #(= :post (:request-method %)) @requests))
            body   (json/parse-string (:body post))]
        (is (str/includes? result "Created OpenProject work package DEMO-2."))
        (is (str/includes? result "description: **Body**"))
        (is (= "New task" (get body "subject")))
        (is (= "/api/v3/projects/7" (get-in body ["_links" "project" "href"])))
        (is (= "/api/v3/types/8" (get-in body ["_links" "type" "href"])))
        (is (= "/api/v3/statuses/9" (get-in body ["_links" "status" "href"])))
        (is (= "**Body**" (get-in body ["description" "raw"])))
        (is (= "2026-01-01" (get body "dueDate")))
        (is (= "PT2H" (get body "estimatedTime")))))))

(deftest create-work-package-requires-subject-and-project
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires `subject`"
       ((handler "openproject_create_work_package") {:project "7"} ctx)))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires `project`"
       ((handler "openproject_create_work_package") {:subject "No project"} ctx))))

(deftest create-work-package-uses-configured-defaults
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (json-response 200 {:id 13 :displayId "DEMO-3" :subject "Default"}
                                   ))]
      (let [ctx {:openproject {:url     "https://op.example.com"
                               :api-key "test-token"
                               :project 7
                               :type    8}}]
        ((handler "openproject_create_work_package") {:subject "Default"} ctx)
        (let [body (json/parse-string (:body (first @requests)))]
          (is (= "/api/v3/projects/7" (get-in body ["_links" "project" "href"])))
          (is (= "/api/v3/types/8" (get-in body ["_links" "type" "href"]))))))))

;; ---------------------------------------------------------------------------
;; Updating work packages
;; ---------------------------------------------------------------------------

(deftest update-work-package-fetches-lock-version-and-resolves-status
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (cond
                      (= :patch (:request-method req))
                      (json-response 200
                                     {:id 42
                                      :displayId "DEMO-42"
                                      :subject "Updated"
                                      :lockVersion 6
                                      :_links {:self {:href "/api/v3/work_packages/42"}
                                               :status {:title "Closed"}}})

                      (str/ends-with? (:url req) "/api/v3/statuses")
                      (json-response 200
                                     (collection [(status-element 10 "Closed")]))

                      (str/ends-with? (:url req) "/api/v3/work_packages/42")
                      (json-response 200 {:id 42 :lockVersion 5})

                      :else
                      (throw (ex-info "unexpected request" {:url (:url req)}))))]
      (let [result ((handler "openproject_update_work_package")
                    {:id 42 :subject "Updated" :status "Closed"} ctx)
            patch  (first (filter #(= :patch (:request-method %)) @requests))
            body   (json/parse-string (:body patch))]
        (is (str/includes? result "Updated OpenProject work package DEMO-42."))
        (is (= 5 (get body "lockVersion"))
            "the current lockVersion is fetched before the PATCH")
        (is (= "Updated" (get body "subject")))
        (is (= "/api/v3/statuses/10" (get-in body ["_links" "status" "href"])))
        (is (some #(and (= :get (:request-method %))
                        (str/ends-with? (:url %) "/api/v3/work_packages/42"))
                  @requests)
            "without an explicit lock_version, the current work package is read
            first")))))

(deftest update-work-package-accepts-explicit-lock-version
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (json-response 200 {:id 42 :displayId "DEMO-42"
                                        :subject "Updated" :lockVersion 6}))]
      ((handler "openproject_update_work_package")
       {:id 42 :subject "Updated" :lock_version 5} ctx)
      (is (= [:patch] (mapv :request-method @requests))
          "an explicit lock_version avoids the extra GET")
      (is (= 5 (get (json/parse-string (:body (first @requests))) "lockVersion"))))))

(deftest update-work-package-requires-a-writable-field
  (with-redefs [clj-http.client/request
                (fn [_req] (throw (ex-info "should not request" {})))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"No OpenProject work package fields"
         ((handler "openproject_update_work_package") {:id 42} ctx)))))

(deftest update-work-package-rejects-non-numeric-ids
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid OpenProject work package id"
       ((handler "openproject_update_work_package")
        {:id "DEMO-42" :subject "Updated"} ctx))))
\n
;; ---------------------------------------------------------------------------
;; Reading comments
;; ---------------------------------------------------------------------------

(deftest list-comments-renders-comments-with-authors
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (activities-request
                   requests
                   (fn [] [(comment-activity 107 5 "First line\nSecond line"
                                             "2026-11-12T00:22:34.954Z")
                           (change-activity 108 5 "2026-11-12T01:00:00.000Z"
                                            ["Status set to Closed"])])
                   user-response)]
      (let [result ((handler "openproject_list_comments") {:id 42} ctx)]
        (is (str/includes?
             result
             "Found 1 comment(s) on OpenProject work package 42 \"Ship it\":"))
        (is (str/includes? result "comment 107 by Seth Curry on 2026-11-12 00:22"))
        (is (str/includes? result "  First line\n  Second line")
            "multi-line comment bodies are indented")
        (is (not (str/includes? result "comment 108"))
            "entries without comment text are skipped by default")
        (is (not (str/includes? result "changes:")))
        (is (= 1 (count (filter #(str/includes? (:url %) "/api/v3/users/")
                                @requests)))
            "each author is resolved once")))))

(deftest list-comments-includes-changes-when-asked
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (activities-request
                   requests
                   (fn [] [(comment-activity 107 5 "First" "2026-11-12T00:22:34.954Z")
                           (change-activity 108 5 "2026-11-12T01:00:00.000Z"
                                            ["Status set to Closed"])])
                   user-response)]
      (let [result ((handler "openproject_list_comments")
                    {:id 42 :include_changes true} ctx)]
        (is (str/includes? result "Found 2 activity(s)"))
        (is (str/includes? result "comment 107 by Seth Curry"))
        (is (str/includes? result "change 108 by Seth Curry on 2026-11-12 01:00"))
        (is (str/includes? result "  changes:\n    - Status set to Closed"))))))

(deftest list-comments-limit-keeps-the-most-recent-entries
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (activities-request
                   requests
                   (fn [] [(comment-activity 107 5 "First" "2026-11-12T00:22:34.954Z")
                           (comment-activity 108 5 "Second" "2026-11-12T01:00:00.000Z")])
                   user-response)]
      (let [result ((handler "openproject_list_comments") {:id 42 :limit 1} ctx)]
        (is (str/includes? result "comment 108"))
        (is (not (str/includes? result "comment 107")))))))

(deftest list-comments-without-comments-says-so
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (activities-request
                   requests
                   (fn [] [(change-activity 108 5 "2026-11-12T01:00:00.000Z"
                                            ["Status set to Closed"])])
                   user-response)]
      (is (= "No comments on OpenProject work package 42 \"Ship it\"."
             ((handler "openproject_list_comments") {:id 42} ctx))))))

(deftest list-comments-falls-back-when-the-author-cannot-be-read
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (activities-request
                   requests
                   (fn [] [(comment-activity 107 5 "First" "2026-11-12T00:22:34.954Z")])
                   (fn [] (json-response 403 {:_type "Error"
                                              :message "Forbidden"})))]
      (let [result ((handler "openproject_list_comments") {:id 42} ctx)]
        (is (str/includes? result "comment 107 by user 5")
            "an unreadable user must not fail the listing")))))

(deftest list-comments-rejects-non-numeric-work-package-ids
  (with-redefs [clj-http.client/request
                (fn [_req] (throw (ex-info "should not request" {})))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invalid OpenProject work package id"
         ((handler "openproject_list_comments") {:id "DEMO-42"} ctx)))))

;; ---------------------------------------------------------------------------
;; Writing comments
;; ---------------------------------------------------------------------------

(deftest add-comment-posts-markdown-body
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (cond
                      (= :post (:request-method req))
                      (json-response 201 (comment-activity 109 5 "Look, **merged**"
                                                           "2026-11-13T09:00:00.000Z"))

                      (str/ends-with? (:url req) "/api/v3/users/5")
                      (user-response)

                      :else
                      (throw (ex-info "unexpected request" {:url (:url req)}))))]
      (let [result ((handler "openproject_add_comment")
                    {:id 42 :comment "  Look, **merged**\n"} ctx)
            post   (first (filter #(= :post (:request-method %)) @requests))
            body   (json/parse-string (:body post))]
        (is (str/ends-with? (:url post) "/api/v3/work_packages/42/activities"))
        (is (= "Look, **merged**" (get-in body ["comment" "raw"]))
            "new comments are wrapped in {:comment {:raw ...}} and trimmed")
        (is (str/includes? result "Added comment 109 to OpenProject work package 42."))
        (is (str/includes? result "comment 109 by Seth Curry"))))))

(deftest add-comment-requires-text
  (with-redefs [clj-http.client/request
                (fn [_req] (throw (ex-info "should not request" {})))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"requires `comment` text"
         ((handler "openproject_add_comment") {:id 42 :comment "  "} ctx)))))

(deftest update-comment-patches-with-a-bare-string
  (let [requests (atom [])]
    (with-redefs [clj-http.client/request
                  (fn [req]
                    (swap! requests conj req)
                    (cond
                      (= :patch (:request-method req))
                      (json-response 200 (comment-activity 107 5 "Edited"
                                                           "2026-11-12T00:22:34.954Z"))

                      (str/ends-with? (:url req) "/api/v3/users/5")
                      (user-response)

                      :else
                      (throw (ex-info "unexpected request" {:url (:url req)}))))]
      (let [result ((handler "openproject_update_comment")
                    {:id 107 :comment "Edited"} ctx)
            patch  (first (filter #(= :patch (:request-method %)) @requests))]
        (is (str/ends-with? (:url patch) "/api/v3/activities/107"))
        (is (= "Edited" (get (json/parse-string (:body patch)) "comment"))
            "OpenProject takes the edited comment as a plain string")
        (is (str/includes? result "Updated OpenProject comment 107."))
        (is (str/includes? result "comment 107 by Seth Curry"))))))

(deftest update-comment-requires-text
  (with-redefs [clj-http.client/request
                (fn [_req] (throw (ex-info "should not request" {})))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"requires `comment` text"
         ((handler "openproject_update_comment") {:id 107} ctx)))))

(deftest update-comment-rejects-non-numeric-ids
  (with-redefs [clj-http.client/request
                (fn [_req] (throw (ex-info "should not request" {})))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Invalid OpenProject comment id"
         ((handler "openproject_update_comment")
          {:id "DEMO-42" :comment "Edited"} ctx)))))
