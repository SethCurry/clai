(ns clai.daemon.http.cookies-test
  "Tests for reading a session token off a request and writing the cookie that
  carries it back."
  (:require [clai.daemon.auth :as auth]
            [clai.daemon.http.cookies :as cookies]
            [clai.test-support :refer [instrument quiet-logs]]
            [clojure.test :refer :all]))

(use-fixtures :once quiet-logs)
(use-fixtures :once instrument)

(deftest cookie-headers-parse-into-maps
  (is (= {} (cookies/parse-cookies nil)))
  (is (= {} (cookies/parse-cookies "")))
  (is (= {"a" "1"} (cookies/parse-cookies "a=1")))
  (is (= {"a" "1" "clai_session" "tok"}
         (cookies/parse-cookies "a=1; clai_session=tok")))
  (is (= {"a" "x=y"} (cookies/parse-cookies "a=x=y")))
  (is (= {"a" ""} (cookies/parse-cookies "a=")))
  (is (= {} (cookies/parse-cookies "junk")))
  (is (= {"a" "1"} (cookies/parse-cookies " a = 1 "))))

(deftest request-tokens-come-from-the-cookie-or-a-bearer-header
  (is (= "tok"
         (cookies/request-token {:headers {"cookie" "clai_session=tok"}})))
  (is (= "tok"
         (cookies/request-token {:headers {"authorization" "Bearer tok"}})))
  (is (= "tok"
         (cookies/request-token {:headers {"authorization" "bearer  tok"}})))
  (is (nil? (cookies/request-token {:headers {}})))
  (is (nil? (cookies/request-token {:headers {"cookie" "other=1"}})))
  (is (nil? (cookies/request-token {:headers {"cookie" "clai_session="}})))
  (is (nil? (cookies/request-token {:headers {"authorization" "Basic abc"}})))
  (is (= "other"
         (cookies/request-token {:headers {"cookie" "clai_session=tok"
                                           "authorization" "Bearer other"}}))
      "an explicit authorization header wins over a cookie"))

(deftest session-cookies-are-http-only-and-expire
  (is (= (str "clai_session=tok; Path=/; HttpOnly; SameSite=Lax; Max-Age="
              auth/token-ttl-seconds)
         (cookies/session-cookie "tok")))
  (is (= "clai_session=tok; Path=/; HttpOnly; SameSite=Lax"
         (cookies/session-cookie "tok" nil)))
  (is (= "clai_session=tok; Path=/; HttpOnly; SameSite=Lax; Max-Age=60"
         (cookies/session-cookie "tok" 60)))
  (is (= "clai_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
         (cookies/cleared-session-cookie))))
