(ns clai.repl.docker-test
  "Tests for the Docker CLI helpers in `clai.repl.docker`."
  (:require [clai.repl.cli :as cli]
            [clai.repl.docker :as docker]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import [java.io File StringWriter]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; Fake docker executable
;; ---------------------------------------------------------------------------

(def ^:dynamic *docker-log* nil)

(def ^:private fake-docker-script
  "#!/bin/sh
if [ -n \"$DOCKER_TEST_LOG\" ]; then
  printf '%s\\n' \"$@\" > \"$DOCKER_TEST_LOG\"
fi
if [ -n \"$DOCKER_TEST_OUT\" ]; then
  printf '%s' \"$DOCKER_TEST_OUT\"
fi
if [ -n \"$DOCKER_TEST_ERR\" ]; then
  printf '%s' \"$DOCKER_TEST_ERR\" >&2
fi
exit \"${DOCKER_TEST_EXIT:-0}\"
")

(defn- delete-tree!
  "Recursively delete `file` and its contents."
  [^File file]
  (when (.exists file)
    (when (.isDirectory file)
      (doseq [child (.listFiles file)]
        (delete-tree! child)))
    (.delete file)))

(defn- with-fake-docker
  "Run `f` with a fake `docker` executable first on PATH.

  The fake logs its arguments to [[*docker-log*]] and echoes
  `DOCKER_TEST_OUT`/`DOCKER_TEST_ERR` before exiting with `DOCKER_TEST_EXIT`."
  [f]
  (let [dir    (.toFile (Files/createTempDirectory
                         "clai-docker-test"
                         (make-array FileAttribute 0)))
        script (io/file dir "docker")
        log    (io/file dir "docker.log")]
    (spit script fake-docker-script)
    (.setExecutable script true)
    (try
      (binding [docker/*docker-executable* (.getAbsolutePath script)
                *docker-log* (.getAbsolutePath log)]
        (f))
      (finally
        (delete-tree! dir)))))

(defn- fake-opts
  "Add the fake docker log file to `opts`' process environment."
  [opts]
  (update (or opts {}) :env #(merge {"DOCKER_TEST_LOG" *docker-log*} %)))

(defn- last-args
  "Return the arguments logged by the most recent fake docker invocation."
  []
  (->> (slurp *docker-log*)
       str/split-lines
       (remove str/blank?)
       vec))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(deftest run-captures-output-and-exit
  (with-fake-docker
    (fn []
      (let [result (docker/run ["ps"] (fake-opts
                                       {:env {"DOCKER_TEST_OUT" "out\n"
                                              "DOCKER_TEST_ERR" "err\n"}}))]
        (is (= [docker/*docker-executable* "ps"] (:command result)))
        (is (= 0 (:exit result)))
        (is (= "out\n" (:out result)))
        (is (= "err\n" (:err result)))
        (is (true? (:success? result)))
        (is (false? (:timed-out? result)))
        (is (= ["ps"] (last-args)))))))

(deftest run-does-not-throw-on-a-non-zero-exit
  (with-fake-docker
    (fn []
      (let [result (docker/run ["ps"]
                               (fake-opts
                                {:env {"DOCKER_TEST_EXIT" "7"
                                       "DOCKER_TEST_ERR" "bad\n"}}))]
        (is (= 7 (:exit result)))
        (is (false? (:success? result)))
        (is (= "bad\n" (:err result)))))))

(deftest run!-throws-with-namespaced-error-data
  (with-fake-docker
    (fn []
      (let [ex (try
                 (docker/run! ["ps"]
                              (fake-opts
                               {:env {"DOCKER_TEST_EXIT" "7"
                                      "DOCKER_TEST_ERR" "bad\n"}}))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= 7 (:docker/exit (ex-data ex))))
        (is (= "bad\n" (:docker/err (ex-data ex))))
        (is (str/includes? (ex-message ex) "docker command failed"))))))

(deftest docker-prints-output
  (with-fake-docker
    (fn []
      (let [out    (StringWriter.)
            err    (StringWriter.)
            result (binding [*out* out *err* err]
                     (docker/docker (fake-opts
                                     {:env {"DOCKER_TEST_OUT" "hello\n"}})
                                    "ps"))]
        (is (:success? result))
        (is (str/includes? (str out) "hello"))
        (is (= "" (str err)))
        (is (= ["ps"] (last-args)))))))

(deftest docker!-prints-then-throws
  (with-fake-docker
    (fn []
      (let [err (StringWriter.)
            ex  (try
                  (binding [*out* (StringWriter.) *err* err]
                    (docker/docker! (fake-opts
                                     {:env {"DOCKER_TEST_EXIT" "3"
                                            "DOCKER_TEST_ERR" "nope\n"}})
                                    "ps"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (str/includes? (str err) "nope"))
        (is (= 3 (:docker/exit (ex-data ex))))))))

(deftest out-returns-trimmed-stdout
  (with-fake-docker
    (fn []
      (is (= "hello"
             (docker/out ["version"]
                         (fake-opts
                          {:env {"DOCKER_TEST_OUT" "  hello \n"}})))))))

;; ---------------------------------------------------------------------------
;; Listing and parsing
;; ---------------------------------------------------------------------------

(deftest ps-parses-json-lines-and-builds-args
  (with-fake-docker
    (fn []
      (let [out        (str "{\"ID\":\"abc\",\"Names\":\"web\","
                            "\"State\":\"running\",\"Labels\":\"app=web\"}\n"
                            "{\"ID\":\"def\",\"Names\":\"db\","
                            "\"State\":\"exited\"}\n")
            containers (docker/ps
                        (fake-opts
                         {:all? true :size? true :last 2
                          :filter {:status "running"}
                          :env {"DOCKER_TEST_OUT" out}}))]
        (is (= ["abc" "def"] (mapv :id containers)))
        (is (= "web" (:names (first containers))))
        (is (= "app=web" (:labels (first containers))))
        (is (= ["ps" "--all" "--size" "--last" "2" "--format" "{{json .}}"
                "--filter" "status=running"]
               (last-args)))))))

(deftest images-parses-json-lines
  (with-fake-docker
    (fn []
      (let [images (docker/images
                    (fake-opts
                     {:all? true :digests? true :filter ["dangling=true"]
                      :env {"DOCKER_TEST_OUT"
                            "{\"Repository\":\"alpine\",\"Tag\":\"3.20\",\"ID\":\"sha256:abc\"}\n"}}))]
        (is (= "alpine" (:repository (first images))))
        (is (= "3.20" (:tag (first images))))
        (is (= ["images" "--all" "--digests" "--format" "{{json .}}"
                "--filter" "dangling=true"]
               (last-args)))))))

(deftest inspect-parses-json-arrays
  (with-fake-docker
    (fn []
      (let [result (docker/inspect
                    ["abc" "def"]
                    (fake-opts
                     {:type "container"
                      :env {"DOCKER_TEST_OUT"
                            "[{\"Id\":\"abc\",\"Name\":\"/one\"},{\"Id\":\"def\",\"Name\":\"/two\"}]"}}))]
        (is (= ["abc" "def"] (mapv :id result)))
        (is (= "/one" (:name (first result))))
        (is (= ["inspect" "--type" "container" "abc" "def"] (last-args)))
        (is (= "/two" (:name (docker/inspect-one
                              "def"
                              (fake-opts
                               {:env {"DOCKER_TEST_OUT"
                                      "[{\"Id\":\"def\",\"Name\":\"/two\"}]"}})))))))))

(deftest build-builds-the-expected-command
  (with-fake-docker
    (fn []
      (let [result (docker/build
                    "."
                    (fake-opts
                     {:tag "app:dev"
                      :file "Dockerfile"
                      :build-arg {"FOO" "bar"}
                      :no-cache? true}))]
        (is (:success? result))
        (is (= ["build" "--file" "Dockerfile" "--no-cache"
                "-t" "app:dev" "--build-arg" "FOO=bar" "."]
               (last-args)))))))

;; ---------------------------------------------------------------------------
;; Containers
;; ---------------------------------------------------------------------------

(deftest run-container-builds-flags-and-command
  (with-fake-docker
    (fn []
      (let [result (docker/run-container
                    "alpine"
                    ["echo" "hi"]
                    (fake-opts
                     {:name "demo"
                      :detach? true
                      :rm? true
                      :workdir "/app"
                      :env-vars {"FOO" "bar"}
                      :volumes ["a:b"]
                      :publish ["8080:80"]
                      :label {"app" "demo"}}))]
        (is (:success? result))
        (is (= ["run" "--name" "demo" "--detach" "--rm" "--workdir" "/app"
                "-e" "FOO=bar" "-v" "a:b" "-p" "8080:80" "--label" "app=demo"
                "alpine" "echo" "hi"]
               (last-args)))))))

(deftest exec-builds-flags-and-command
  (with-fake-docker
    (fn []
      (docker/exec "demo"
                   ["sh" "-lc" "echo hi"]
                   (fake-opts
                    {:interactive? true
                     :tty? true
                     :workdir "/app"
                     :env-vars {"FOO" "bar"}
                     :extra-args ["--env" "BAZ=qux"]}))
      (is (= ["exec" "--interactive" "--tty" "--workdir" "/app"
              "-e" "FOO=bar" "--env" "BAZ=qux"
              "demo" "sh" "-lc" "echo hi"]
             (last-args))))))

(deftest container-lifecycle-helpers-build-expected-commands
  (with-fake-docker
    (fn []
      (docker/stop ["a" "b"] (fake-opts {:time 5}))
      (is (= ["stop" "--time" "5" "a" "b"] (last-args)))

      (docker/rm "a" (fake-opts {:force? true :volumes? true}))
      (is (= ["rm" "--force" "--volumes" "a"] (last-args)))

      (docker/logs "a" (fake-opts {:tail 20 :timestamps? true}))
      (is (= ["logs" "--timestamps" "--tail" "20" "a"] (last-args)))

      (docker/stats nil (fake-opts {:all? true}))
      (is (= ["stats" "--no-stream" "--all"] (last-args))))))

;; ---------------------------------------------------------------------------
;; Volumes and networks
;; ---------------------------------------------------------------------------

(deftest volume-and-network-helpers-build-expected-commands
  (with-fake-docker
    (fn []
      (docker/volumes (fake-opts {:filter {:dangling "true"}}))
      (is (= ["volume" "ls" "--format" "{{json .}}"
              "--filter" "dangling=true"]
             (last-args)))

      (docker/volume-create "data"
                            (fake-opts {:driver "local"
                                        :label {"app" "demo"}}))
      (is (= ["volume" "create" "--driver" "local" "--label" "app=demo"
              "data"]
             (last-args)))

      (docker/networks (fake-opts {:filter ["name=bridge"]}))
      (is (= ["network" "ls" "--format" "{{json .}}" "--filter" "name=bridge"]
             (last-args)))

      (docker/network-create "frontend"
                             (fake-opts
                              {:driver "bridge" :subnet "172.20.0.0/16"
                               :internal? true}))
      (is (= ["network" "create" "--driver" "bridge"
              "--subnet" "172.20.0.0/16" "--internal" "frontend"]
             (last-args))))))

;; ---------------------------------------------------------------------------
;; Compose
;; ---------------------------------------------------------------------------

(deftest compose-runs-a-subcommand-and-prints
  (with-fake-docker
    (fn []
      (let [out    (StringWriter.)
            result (binding [*out* out]
                     (docker/compose
                      (fake-opts
                       {:project-name "demo"
                        :file ["a.yml" "b.yml"]
                        :env {"DOCKER_TEST_OUT" "composed\n"}})
                      "ps"))]
        (is (:success? result))
        (is (str/includes? (str out) "composed"))
        (is (= ["compose" "--project-name" "demo" "-f" "a.yml" "-f" "b.yml"
                "ps"]
               (last-args)))))))

(deftest compose!-throws-after-printing
  (with-fake-docker
    (fn []
      (let [err (StringWriter.)
            ex  (try
                  (binding [*out* (StringWriter.) *err* err]
                    (docker/compose!
                     (fake-opts
                      {:env {"DOCKER_TEST_EXIT" "1"
                             "DOCKER_TEST_ERR" "compose failed\n"}})
                     "ps"))
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (str/includes? (str err) "compose failed"))
        (is (= 1 (:docker/exit (ex-data ex))))))))

(deftest compose-up-defaults-to-detached
  (with-fake-docker
    (fn []
      (docker/compose-up (fake-opts {:services ["web" "db"] :build? true}))
      (is (= ["compose" "up" "--detach" "--build" "web" "db"] (last-args)))

      (docker/compose-up "web" (fake-opts {:detach? false}))
      (is (= ["compose" "up" "web"] (last-args)))

      (docker/compose-up nil (fake-opts {:services ["api"]}))
      (is (= ["compose" "up" "--detach" "api"] (last-args))))))

(deftest compose-down-and-ps-build-expected-commands
  (with-fake-docker
    (fn []
      (docker/compose-down (fake-opts {:volumes? true :remove-orphans? true
                                       :rmi "local" :timeout 10}))
      (is (= ["compose" "down" "--volumes" "--remove-orphans"
              "--rmi" "local" "--timeout" "10"]
             (last-args)))

      (let [rows (docker/compose-ps
                  (fake-opts
                   {:all? true :services ["web"]
                    :env {"DOCKER_TEST_OUT"
                          "[{\"Name\":\"demo-web-1\",\"Service\":\"web\",\"State\":\"running\"}]"}}))]
        (is (= "demo-web-1" (:name (first rows))))
        (is (= "web" (:service (first rows))))
        (is (= ["compose" "ps" "--format" "json" "--all" "web"]
               (last-args)))))))

;; ---------------------------------------------------------------------------
;; Real docker smoke tests (skipped when no daemon is available)
;; ---------------------------------------------------------------------------

(defn- docker-daemon-available?
  "True when the real docker client and daemon both answer."
  []
  (try
    (:success? (cli/run "docker" ["info"] {:timeout 10000}))
    (catch Throwable _
      false)))

(deftest real-docker-queries-when-available
  (when (docker-daemon-available?)
    (is (not (str/blank? (docker/version))))
    (is (map? (docker/version-info)))
    (is (map? (docker/info)))
    (is (vector? (docker/ps)))
    (is (vector? (docker/images)))))
