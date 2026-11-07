(ns clai.shell.docker
  "REPL-friendly helpers for driving the `docker` CLI.

  The core primitive is [[run]]. It starts `docker` directly (never through a
  shell), captures stdout and stderr separately, and returns a result map
  without throwing when docker exits non-zero:

      (run [\"ps\" \"--all\"] {:dir \"/path/to/project\"})
      ;; => {:command [\"docker\" \"ps\" \"--all\"]
      ;;     :dir \"/path/to/project\"
      ;;     :exit 0
      ;;     :out \"...\"
      ;;     :err \"\"
      ;;     :success? true
      ;;     :timed-out? false}

  [[docker]] is the interactive counterpart: it prints stdout/stderr as docker
  itself would and returns the same map. [[run!]] and [[docker!]] throw an
  `ex-info` when docker fails; the exception's data carries the command, exit
  code, stdout, and stderr.

  The named helpers are silent and return result maps or parsed values. List
  helpers such as [[ps]], [[images]], [[volumes]], [[networks]], [[inspect]],
  and [[compose-ps]] parse docker's JSON output into Clojure maps with
  kebab-case keyword keys:

      (->> (ps {:all? true})
           (filter #(= \"exited\" (:state %)))
           (map :names))

      (-> (inspect \"my-container\")
          first
          :state)

  The working directory is virtual and shared with the other CLI helper
  namespaces. It comes from `:dir`, then the calling namespace's own `*cwd*`
  (each session namespace created by `clai.repl` has one), then the
  process-wide `clai.shell.cli/*cwd*`, then the JVM's working directory, so it
  matters for relative bind mounts, build contexts, and compose files:

      (binding [*cwd* \"/project\"]        ; in a session namespace
        (build \".\" {:tag \"my-app:dev\"}))

  Commands are kept non-interactive by default: CLI hints are disabled,
  BuildKit progress is plain text, and compose ANSI output is disabled. Pass
  `:env` to add or remove process environment variables for a single call.

  Note that `:env` is the *process* environment, not container environment.
  Container and exec environment variables are passed with `:env-vars`:

      (run-container \"alpine\" [\"printenv\" \"FOO\"]
                     {:rm? true :env-vars {\"FOO\" \"bar\"}})

  Every helper also accepts `:extra-args` to append raw arguments when a
  docker flag is not wrapped by a dedicated option, and list helpers accept
  `:filter` as either a string, a sequence of strings, or a map of
  `key value` pairs. Commands use the shared timeout
  (`clai.shell.cli/*timeout-ms*`, 30 seconds by default)."
  (:refer-clojure :exclude [run!])
  (:require [cheshire.core :as json]
            [clai.shell.cli :as cli]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private default-env
  "Environment defaults that keep Docker commands from blocking or filling a
  REPL with interactive terminal output."
  {"DOCKER_CLI_HINTS"  "false"
   "BUILDKIT_PROGRESS" "plain"
   "COMPOSE_ANSI"      "never"})

(def ^:dynamic *docker-executable*
  "Command or path used to invoke the Docker CLI. Defaults to `\"docker\"`;
  bind it to use a different binary such as `podman` or a wrapper."
  "docker")

;; ---------------------------------------------------------------------------
;; Argument construction
;; ---------------------------------------------------------------------------

(defn- command
  "Append the caller's raw `:extra-args` escape hatch to `args`."
  [args opts]
  (into (vec args) (cli/normalize-args (:extra-args opts))))

(defn- repeat-args
  "Append `flag value` for each value in `values`."
  [args flag values]
  (if (nil? values)
    args
    (into args (mapcat (fn [value] [flag (cli/arg->string value)])
                       (cli/normalize-args values)))))

(defn- kv-args
  "Append `flag KEY=value` entries from a map or a sequence of raw strings."
  [args flag values]
  (cond
    (nil? values)
    args

    (map? values)
    (reduce-kv (fn [args k v]
                 (conj args flag (str (cli/arg->string k) "="
                                      (cli/arg->string v))))
               args
               values)

    :else
    (repeat-args args flag values)))

(defn- env-args
  "Append container/exec environment flags."
  [args env-vars]
  (kv-args args "-e" env-vars))

(defn- filter-args
  "Append docker `--filter` flags from a string, sequence, or map."
  [args filters]
  (cond
    (nil? filters)
    args

    (map? filters)
    (reduce-kv (fn [args k v]
                 (conj args "--filter"
                       (str (cli/arg->string k) "=" (cli/arg->string v))))
               args
               filters)

    :else
    (repeat-args args "--filter" filters)))

(defn- compose-files
  "Combine the `:file` and `:files` compose options."
  [opts]
  (concat (cli/normalize-args (:file opts))
          (cli/normalize-args (:files opts))))

(defn- compose-profiles
  "Combine the `:profile` and `:profiles` compose options."
  [opts]
  (concat (cli/normalize-args (:profile opts))
          (cli/normalize-args (:profiles opts))))

(defn- compose-global-args
  "Build the global flags that must precede a docker compose subcommand."
  [opts]
  (let [{:keys [project-name project-directory env-file ansi progress
                compatibility?]} opts
        args (cond-> []
               (some? project-name)
               (conj "--project-name" (cli/arg->string project-name))

               (some? project-directory)
               (conj "--project-directory" (cli/arg->string project-directory))

               (some? env-file)
               (conj "--env-file" (cli/arg->string env-file))

               (some? ansi)
               (conj "--ansi" (cli/arg->string ansi))

               (some? progress)
               (conj "--progress" (cli/arg->string progress))

               compatibility?
               (conj "--compatibility"))]
    (-> args
        (repeat-args "-f" (compose-files opts))
        (repeat-args "--profile" (compose-profiles opts)))))

(defn- compose-args
  "Prefix a docker compose subcommand with the global compose flags."
  [args opts]
  (into (into ["compose"] (compose-global-args opts))
        (cli/normalize-args args)))

;; ---------------------------------------------------------------------------
;; Output parsing
;; ---------------------------------------------------------------------------

(defn- kebab-key
  "Turn a docker JSON field such as `CreatedAt` into `:created-at`."
  [k]
  (-> k
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      str/lower-case
      keyword))

(defn- parse-json
  "Parse docker's JSON output, returning nil for blank output."
  [text]
  (when-not (str/blank? text)
    (json/parse-string text kebab-key)))

(defn- parse-json-lines
  "Parse newline-delimited JSON objects, as produced by `{{json .}}`."
  [text]
  (->> (str/split-lines (or text ""))
       (remove str/blank?)
       (mapv parse-json)))

(defn- parse-json-array
  "Parse a JSON array, accepting either a single object or newline-delimited
  objects as a vector."
  [text]
  (if (str/blank? text)
    []
    (let [text  (str/trim text)
          value (if (str/starts-with? text "[")
                  (parse-json text)
                  (let [rows (parse-json-lines text)]
                    (if (= 1 (count rows)) (first rows) rows)))]
      (cond
        (nil? value)        []
        (sequential? value) (vec value)
        :else               [value]))))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(defn- run-opts
  "Apply docker's non-interactive environment defaults before handing `opts`
  to the shared CLI runner."
  [opts]
  (update opts :env #(merge default-env %)))

(defn run
  "Run `docker` with `args` and capture its output.

  `args` is a string or a sequential collection of strings, keywords, and
  symbols; arguments are passed to docker verbatim, so no shell quoting or
  escaping is involved. `opts` may contain:

    :dir        working directory (defaults to the calling namespace's
                `*cwd*`, then `user.dir`)
    :timeout    milliseconds to wait, or nil to wait forever
    :in         stdin contents (a string, byte array, or InputStream)
    :env        process environment variables; a nil value removes one
    :extra-args additional raw arguments

  Returns a map with `:command`, `:dir`, `:exit`, `:out`, `:err`, `:success?`,
  and `:timed-out?`. A non-zero exit is not an error; use [[run!]] for that."
  ([args] (run args {}))
  ([args opts]
   (cli/run *docker-executable* args (assoc (run-opts opts)
                                             :label "docker"
                                             :data-prefix "docker"))))

(defn check!
  "Return `result` when docker succeeded, or throw an `ex-info` describing the
  failure. The exception's data contains the command, exit, stdout, and
  stderr."
  [result]
  (cli/check! result {:label "docker" :data-prefix "docker"}))

(defn run!
  "Like [[run]], but throw when docker exits non-zero. Returns the result map."
  ([args] (run! args {}))
  ([args opts] (check! (run args opts))))

(defn docker
  "Run docker and print its stdout and stderr, like the `docker` command
  itself.

  Takes variadic arguments plus an optional options map, in any position:

      (docker \"ps\" \"--all\")
      (docker {:dir \"/project\"} \"compose\" \"ps\")
      (docker [\"images\" \"--filter\" \"dangling=true\"])

  Returns [[run]]'s result map. A non-zero exit is not thrown; use [[docker!]]
  for that."
  [& args]
  (let [[opts args] (cli/split-args args)]
    (cli/echo-result! "docker" (run args opts))))

(defn docker!
  "Like [[docker]], but throw when docker exits non-zero after printing its
  output."
  [& args]
  (let [[opts args] (cli/split-args args)]
    (check! (cli/echo-result! "docker" (run args opts)))))

(defn out
  "Run docker and return its trimmed stdout, throwing when it fails."
  ([args] (out args {}))
  ([args opts] (str/trim (:out (run! args opts)))))

;; ---------------------------------------------------------------------------
;; System information
;; ---------------------------------------------------------------------------

(defn version
  "Return the installed docker client version string."
  ([] (version {}))
  ([opts] (out ["version" "--format" "{{.Client.Version}}"] opts)))

(defn version-info
  "Return parsed `docker version` information, with client and server data."
  ([] (version-info {}))
  ([opts]
   (parse-json (:out (run! (command ["version" "--format" "{{json .}}"]
                                    opts)
                          opts)))))

(defn info
  "Return parsed `docker info` as a map."
  ([] (info {}))
  ([opts]
   (parse-json (:out (run! (command ["info" "--format" "{{json .}}"] opts)
                          opts)))))

(defn df
  "Return parsed `docker system df` rows as a vector of maps."
  ([] (df {}))
  ([opts]
   (-> (run! (command ["system" "df" "--format" "{{json .}}"] opts) opts)
       :out
       parse-json-lines)))

;; ---------------------------------------------------------------------------
;; Containers
;; ---------------------------------------------------------------------------

(defn ps
  "Return the containers from `docker ps` as a vector of maps.

  Options include `:all?`, `:size?`, `:last`, and `:filter` (a string,
  sequence, or map). Each map has kebab-case keys such as `:id`, `:names`,
  `:image`, `:command`, `:created-at`, `:status`, `:state`, `:ports`, and
  `:labels`."
  ([] (ps {}))
  ([opts]
   (let [{:keys [all? size? last filter]} opts
         args (cond-> ["ps"]
                all?  (conj "--all")
                size? (conj "--size")

                (some? last)
                (conj "--last" (cli/arg->string last))

                :always
                (conj "--format" "{{json .}}"))]
     (-> (run! (command (filter-args args filter) opts) opts)
         :out
         parse-json-lines))))

(defn containers
  "Return the containers from `docker ps` (an alias for [[ps]])."
  ([] (containers {}))
  ([opts] (ps opts)))

;; ---------------------------------------------------------------------------
;; Images
;; ---------------------------------------------------------------------------

(defn images
  "Return the images from `docker images` as a vector of maps.

  Options include `:all?`, `:digests?`, and `:filter` (a string, sequence, or
  map). Each map has kebab-case keys such as `:repository`, `:tag`, `:id`,
  `:size`, `:created-at`, and `:created-since`."
  ([] (images {}))
  ([opts]
   (let [{:keys [all? digests? filter]} opts
         args (cond-> ["images"]
                all?     (conj "--all")
                digests? (conj "--digests")
                :always  (conj "--format" "{{json .}}"))]
     (-> (run! (command (filter-args args filter) opts) opts)
         :out
         parse-json-lines))))

(defn pull
  "Pull `image`. Options include `:platform`, `:quiet?`, and `:all-tags?`."
  ([image] (pull image {}))
  ([image opts]
   (let [{:keys [platform quiet? all-tags?]} opts
         args (cond-> ["pull"]
                (some? platform) (conj "--platform" (cli/arg->string platform))
                quiet?            (conj "--quiet")
                all-tags?         (conj "--all-tags")
                :always           (conj (cli/arg->string image)))]
     (run! (command args opts) opts))))

(defn push
  "Push `image`. Options include `:quiet?` and `:all-tags?`."
  ([image] (push image {}))
  ([image opts]
   (let [{:keys [quiet? all-tags?]} opts
         args (cond-> ["push"]
                quiet?    (conj "--quiet")
                all-tags? (conj "--all-tags")
                :always   (conj (cli/arg->string image)))]
     (run! (command args opts) opts))))

(defn build
  "Build an image from `context` (default `\".\"`).

  Options include `:tag`/`:tags`, `:file`/`:dockerfile`, `:build-arg`/
  `:build-args`, `:label`/`:labels`, `:target`, `:platform`, `:network`,
  `:progress`, `:no-cache?`, `:pull?`, `:quiet?`, `:rm?`, and `:force-rm?`."
  ([] (build "." {}))
  ([context] (build context {}))
  ([context opts]
   (let [{:keys [tag tags file dockerfile build-arg build-args label labels
                 target platform network progress no-cache? pull? quiet? rm?
                 force-rm?]} opts
         tags   (concat (cli/normalize-args tag) (cli/normalize-args tags))
         labels (or (not-empty labels) label)
         args   (cond-> ["build"]
                  (some? (or file dockerfile))
                  (conj "--file" (cli/arg->string (or file dockerfile)))

                  (some? target)
                  (conj "--target" (cli/arg->string target))

                  (some? platform)
                  (conj "--platform" (cli/arg->string platform))

                  (some? network)
                  (conj "--network" (cli/arg->string network))

                  (some? progress)
                  (conj "--progress" (cli/arg->string progress))

                  no-cache? (conj "--no-cache")
                  pull?     (conj "--pull")
                  quiet?    (conj "--quiet")
                  rm?       (conj "--rm")
                  force-rm? (conj "--force-rm"))]
     (run! (conj (-> args
                     (repeat-args "-t" tags)
                     (kv-args "--build-arg" (or (not-empty build-args) build-arg))
                     (kv-args "--label" labels)
                     (command opts))
                 (cli/arg->string (or context ".")))
           opts))))

(defn tag
  "Tag `source` as `target`."
  ([source target] (tag source target {}))
  ([source target opts]
   (run! (command ["tag" (cli/arg->string source) (cli/arg->string target)]
                  opts)
         opts)))

(defn rmi
  "Remove one or more images. Options include `:force?` and `:no-prune?`."
  ([image-or-images] (rmi image-or-images {}))
  ([image-or-images opts]
   (let [{:keys [force? no-prune?]} opts
         args (cond-> ["rmi"]
                force?    (conj "--force")
                no-prune? (conj "--no-prune"))]
     (run! (command (into args (cli/normalize-args image-or-images)) opts)
           opts))))

(defn history
  "Return `docker history` rows for `image` as a vector of maps."
  ([image] (history image {}))
  ([image opts]
   (let [args (cond-> ["history" (cli/arg->string image)]
                (:no-trunc? opts) (conj "--no-trunc")
                :always           (conj "--format" "{{json .}}"))]
     (-> (run! (command args opts) opts)
         :out
         parse-json-lines))))

(defn inspect
  "Inspect one or more docker objects and return a vector of parsed maps.

  `refs` may be a string or a collection. Options include `:type` (`image`,
  `container`, `volume`, `network`, or `node`) and the usual process options."
  ([refs] (inspect refs {}))
  ([refs opts]
   (let [args (cond-> ["inspect"]
                (some? (:type opts))
                (conj "--type" (cli/arg->string (:type opts)))

                :always
                (into (cli/normalize-args refs)))]
     (parse-json-array (:out (run! (command args opts) opts))))))

(defn inspect-one
  "Inspect a single docker object and return its parsed map, or nil."
  ([ref] (inspect-one ref {}))
  ([ref opts] (first (inspect ref opts))))

;; ---------------------------------------------------------------------------
;; Containers
;; ---------------------------------------------------------------------------

(defn- run-container-args
  "Build the argument vector for `docker run`."
  [image cmd opts]
  (let [{:keys [name detach? rm? interactive? tty? env-vars volumes publish
                workdir user entrypoint network platform restart pull memory
                cpus hostname privileged? init? publish-all? label labels]} opts
        args (cond-> ["run"]
               (some? name)       (conj "--name" (cli/arg->string name))
               detach?            (conj "--detach")
               rm?                (conj "--rm")
               interactive?       (conj "--interactive")
               tty?               (conj "--tty")
               privileged?        (conj "--privileged")
               init?              (conj "--init")
               publish-all?       (conj "--publish-all")

               (some? workdir)
               (conj "--workdir" (cli/arg->string workdir))

               (some? user)
               (conj "--user" (cli/arg->string user))

               (some? entrypoint)
               (conj "--entrypoint" (cli/arg->string entrypoint))

               (some? network)
               (conj "--network" (cli/arg->string network))

               (some? platform)
               (conj "--platform" (cli/arg->string platform))

               (some? restart)
               (conj "--restart" (cli/arg->string restart))

               (some? pull)
               (conj "--pull" (cli/arg->string pull))

               (some? memory)
               (conj "--memory" (cli/arg->string memory))

               (some? cpus)
               (conj "--cpus" (cli/arg->string cpus))

               (some? hostname)
               (conj "--hostname" (cli/arg->string hostname)))]
    (-> args
        (env-args env-vars)
        (repeat-args "-v" volumes)
        (repeat-args "-p" publish)
        (kv-args "--label" (or (not-empty labels) label))
        (command opts)
        (conj (cli/arg->string image))
        (into (cli/normalize-args cmd)))))

(defn run-container
  "Run a container from `image`, optionally with `command` arguments.

  `command` may be omitted or a string/collection of strings. Options include
  `:name`, `:detach?`, `:rm?`, `:interactive?`, `:tty?`, `:env-vars`,
  `:volumes`, `:publish`, `:workdir`, `:user`, `:entrypoint`, `:network`,
  `:platform`, `:restart`, `:pull`, `:memory`, `:cpus`, `:hostname`,
  `:privileged?`, `:init?`, `:publish-all?`, and `:label`/`:labels`.

  Container environment variables use `:env-vars`; the process environment for
  the docker CLI itself uses `:env`.

      (run-container \"alpine\" [\"echo\" \"hi\"] {:rm? true})
      (run-container \"postgres:16\" {:name \"db\" :detach? true
                                      :env-vars {\"POSTGRES_PASSWORD\" \"secret\"}
                                      :publish [\"5432:5432\"]})

  Returns [[run]]'s result map and throws when the container exits non-zero."
  ([image] (run-container image nil {}))
  ([image cmd-or-opts]
   (if (map? cmd-or-opts)
     (run-container image nil cmd-or-opts)
     (run-container image cmd-or-opts {})))
  ([image cmd opts]
   (run! (run-container-args image cmd opts) opts)))

(defn start
  "Start one or more stopped containers."
  ([containers] (start containers {}))
  ([containers opts]
   (run! (command (into ["start"] (cli/normalize-args containers)) opts)
         opts)))

(defn stop
  "Stop one or more running containers. Option `:time` sets the grace period."
  ([containers] (stop containers {}))
  ([containers opts]
   (let [args (cond-> ["stop"]
                (some? (:time opts))
                (conj "--time" (cli/arg->string (:time opts))))]
     (run! (command (into args (cli/normalize-args containers)) opts)
           opts))))

(defn restart
  "Restart one or more containers. Option `:time` sets the grace period."
  ([containers] (restart containers {}))
  ([containers opts]
   (let [args (cond-> ["restart"]
                (some? (:time opts))
                (conj "--time" (cli/arg->string (:time opts))))]
     (run! (command (into args (cli/normalize-args containers)) opts)
           opts))))

(defn kill
  "Kill one or more containers. Option `:signal` names the signal."
  ([containers] (kill containers {}))
  ([containers opts]
   (let [args (cond-> ["kill"]
                (some? (:signal opts))
                (conj "--signal" (cli/arg->string (:signal opts))))]
     (run! (command (into args (cli/normalize-args containers)) opts)
           opts))))

(defn pause
  "Pause one or more containers."
  ([containers] (pause containers {}))
  ([containers opts]
   (run! (command (into ["pause"] (cli/normalize-args containers)) opts)
         opts)))

(defn unpause
  "Unpause one or more containers."
  ([containers] (unpause containers {}))
  ([containers opts]
   (run! (command (into ["unpause"] (cli/normalize-args containers)) opts)
         opts)))

(defn rm
  "Remove one or more containers. Options include `:force?` and `:volumes?`."
  ([containers] (rm containers {}))
  ([containers opts]
   (let [args (cond-> ["rm"]
                (:force? opts)   (conj "--force")
                (:volumes? opts) (conj "--volumes"))]
     (run! (command (into args (cli/normalize-args containers)) opts)
           opts))))

(defn wait
  "Wait for one or more containers to stop and return the result map."
  ([containers] (wait containers {}))
  ([containers opts]
   (run! (command (into ["wait"] (cli/normalize-args containers)) opts)
         opts)))

(defn rename
  "Rename container `container` to `new-name`."
  ([container new-name] (rename container new-name {}))
  ([container new-name opts]
   (run! (command ["rename" (cli/arg->string container)
                   (cli/arg->string new-name)]
                  opts)
         opts)))

(defn exec
  "Run `cmd` inside `container`.

  Options include `:detach?`, `:interactive?`, `:tty?`, `:env-vars`,
  `:workdir`, `:user`, and `:privileged?`. Container environment variables use
  `:env-vars`, as with [[run-container]]."
  ([container cmd] (exec container cmd {}))
  ([container cmd opts]
   (let [{:keys [detach? interactive? tty? env-vars workdir user
                 privileged?]} opts
         args (cond-> ["exec"]
                detach?      (conj "--detach")
                interactive? (conj "--interactive")
                tty?         (conj "--tty")
                privileged?  (conj "--privileged")

                (some? workdir)
                (conj "--workdir" (cli/arg->string workdir))

                (some? user)
                (conj "--user" (cli/arg->string user)))]
     (run! (-> args
               (env-args env-vars)
               (command opts)
               (conj (cli/arg->string container))
               (into (cli/normalize-args cmd)))
           opts))))

(defn logs
  "Return the logs of `container` as a result map.

  Options include `:follow?` (off by default, so the call does not block),
  `:tail`, `:timestamps?`, `:since`, `:until`, and `:details?`."
  ([container] (logs container {}))
  ([container opts]
   (let [{:keys [follow? tail timestamps? since until details?]} opts
         args (cond-> ["logs"]
                follow?     (conj "--follow")
                timestamps? (conj "--timestamps")
                details?    (conj "--details")

                (some? tail)
                (conj "--tail" (cli/arg->string tail))

                (some? since)
                (conj "--since" (cli/arg->string since))

                (some? until)
                (conj "--until" (cli/arg->string until))

                :always
                (conj (cli/arg->string container)))]
     (run! (command args opts) opts))))

(defn top
  "Show the running processes of `container`. Extra `ps` options may be passed
  with `:extra-args`."
  ([container] (top container {}))
  ([container opts]
   (run! (command ["top" (cli/arg->string container)] opts) opts)))

(defn diff
  "Show filesystem changes in `container`."
  ([container] (diff container {}))
  ([container opts]
   (run! (command ["diff" (cli/arg->string container)] opts) opts)))

(defn stats
  "Return container resource statistics.

  Statistics are non-streaming by default. Pass `:no-stream? false` to follow
  live stats (the command is still bounded by the configured timeout). Other
  options include `:all?`, `:no-trunc?`, and `:format`."
  ([containers] (stats containers {}))
  ([containers opts]
   (let [{:keys [all? no-stream? no-trunc? format]} opts
         args (cond-> ["stats"]
                (not= false no-stream?) (conj "--no-stream")
                all?                    (conj "--all")
                no-trunc?               (conj "--no-trunc")

                (some? format)
                (conj "--format" (cli/arg->string format)))]
     (run! (command (into args (cli/normalize-args containers)) opts)
           opts))))

(defn cp
  "Copy files between a container and the local filesystem.

  Options include `:archive?` and `:follow-link?`."
  ([source destination] (cp source destination {}))
  ([source destination opts]
   (let [args (cond-> ["cp"]
                (:archive? opts)     (conj "--archive")
                (:follow-link? opts) (conj "--follow-link"))]
     (run! (command (conj args (cli/arg->string source)
                         (cli/arg->string destination))
                    opts)
           opts))))

;; ---------------------------------------------------------------------------
;; Volumes
;; ---------------------------------------------------------------------------

(defn volumes
  "Return the volumes from `docker volume ls` as a vector of maps.

  Options include `:filter` (a string, sequence, or map)."
  ([] (volumes {}))
  ([opts]
   (let [args ["volume" "ls" "--format" "{{json .}}"]]
     (-> (run! (command (filter-args args (:filter opts)) opts) opts)
         :out
         parse-json-lines))))

(defn volume-create
  "Create a volume named `name`. Options include `:driver` and `:labels`."
  ([name] (volume-create name {}))
  ([name opts]
   (let [{:keys [driver label labels]} opts
         args (cond-> ["volume" "create"]
                (some? driver)
                (conj "--driver" (cli/arg->string driver)))]
     (run! (command (-> args
                        (kv-args "--label" (or (not-empty labels) label))
                        (conj (cli/arg->string name)))
                    opts)
           opts))))

(defn volume-rm
  "Remove one or more volumes. Option `:force?` removes volumes in use."
  ([volumes] (volume-rm volumes {}))
  ([volumes opts]
   (let [args (cond-> ["volume" "rm"]
                (:force? opts) (conj "--force"))]
     (run! (command (into args (cli/normalize-args volumes)) opts)
           opts))))

(defn volume-prune
  "Remove unused volumes. Options include `:force?`, `:all?`, and `:filter`."
  ([] (volume-prune {}))
  ([opts]
   (let [args (cond-> ["volume" "prune"]
                (:force? opts) (conj "--force")
                (:all? opts)   (conj "--all"))]
     (run! (command (filter-args args (:filter opts)) opts) opts))))

;; ---------------------------------------------------------------------------
;; Networks
;; ---------------------------------------------------------------------------

(defn networks
  "Return the networks from `docker network ls` as a vector of maps.

  Options include `:filter` (a string, sequence, or map)."
  ([] (networks {}))
  ([opts]
   (let [args ["network" "ls" "--format" "{{json .}}"]]
     (-> (run! (command (filter-args args (:filter opts)) opts) opts)
         :out
         parse-json-lines))))

(defn network-create
  "Create a network named `name`.

  Options include `:driver`, `:subnet`, `:gateway`, `:ip-range`, `:internal?`,
  `:attachable?`, and `:labels`."
  ([name] (network-create name {}))
  ([name opts]
   (let [{:keys [driver subnet gateway ip-range internal? attachable? label
                 labels]} opts
         args (cond-> ["network" "create"]
                (some? driver)
                (conj "--driver" (cli/arg->string driver))

                (some? subnet)
                (conj "--subnet" (cli/arg->string subnet))

                (some? gateway)
                (conj "--gateway" (cli/arg->string gateway))

                (some? ip-range)
                (conj "--ip-range" (cli/arg->string ip-range))

                internal?   (conj "--internal")
                attachable? (conj "--attachable"))]
     (run! (command (-> args
                        (kv-args "--label" (or (not-empty labels) label))
                        (conj (cli/arg->string name)))
                    opts)
           opts))))

(defn network-rm
  "Remove one or more networks. Option `:force?` removes connected networks."
  ([networks] (network-rm networks {}))
  ([networks opts]
   (let [args (cond-> ["network" "rm"]
                (:force? opts) (conj "--force"))]
     (run! (command (into args (cli/normalize-args networks)) opts)
           opts))))

(defn network-prune
  "Remove unused networks. Options include `:force?` and `:filter`."
  ([] (network-prune {}))
  ([opts]
   (let [args (cond-> ["network" "prune"]
                (:force? opts) (conj "--force"))]
     (run! (command (filter-args args (:filter opts)) opts) opts))))

(defn network-connect
  "Connect `container` to `network`.

  Options include `:alias`/`:aliases`, `:ip`, `:ip6`, and `:link`/`:links`."
  ([network container] (network-connect network container {}))
  ([network container opts]
   (let [{:keys [alias aliases ip ip6 link links]} opts
         args (cond-> ["network" "connect"]
                (some? ip)  (conj "--ip" (cli/arg->string ip))
                (some? ip6) (conj "--ip6" (cli/arg->string ip6)))]
     (run! (command (-> args
                        (repeat-args "--alias" (concat (cli/normalize-args alias)
                                                       (cli/normalize-args aliases)))
                        (repeat-args "--link" (concat (cli/normalize-args link)
                                                      (cli/normalize-args links)))
                        (conj (cli/arg->string network)
                              (cli/arg->string container)))
                    opts)
           opts))))

(defn network-disconnect
  "Disconnect `container` from `network`. Option `:force?` forces the
  disconnection."
  ([network container] (network-disconnect network container {}))
  ([network container opts]
   (let [args (cond-> ["network" "disconnect"]
                (:force? opts) (conj "--force"))]
     (run! (command (conj args (cli/arg->string network)
                         (cli/arg->string container))
                    opts)
           opts))))

;; ---------------------------------------------------------------------------
;; Compose
;; ---------------------------------------------------------------------------

(defn compose-run
  "Run `docker compose` with `args` and return its result map.

  `args` is a string or collection of compose subcommand arguments. Options
  include the global compose flags `:project-name`, `:project-directory`,
  `:file`/`:files`, `:env-file`, `:profile`/`:profiles`, `:ansi`,
  `:progress`, and `:compatibility?`, plus the usual process options."
  ([args] (compose-run args {}))
  ([args opts]
   (run! (command (compose-args args opts) opts) opts)))

(defn compose
  "Run docker compose and print its stdout and stderr, like `docker compose`.

  Takes variadic arguments plus an optional options map, in any position:

      (compose \"ps\")
      (compose {:project-name \"demo\"} \"up\" \"--detach\")
      (compose [\"logs\" \"--tail\" \"20\" \"web\"])

  Returns [[run]]'s result map. A non-zero exit is not thrown; use [[compose!]]
  for that."
  [& args]
  (let [[opts args] (cli/split-args args)
        result      (run (compose-args args opts) opts)]
    (cli/echo-result! "docker compose" result)))

(defn compose!
  "Like [[compose]], but throw when docker compose exits non-zero after
  printing its output."
  [& args]
  (let [[opts args] (cli/split-args args)
        result      (run (compose-args args opts) opts)]
    (check! (cli/echo-result! "docker compose" result))))

(defn compose-out
  "Run docker compose and return its trimmed stdout, throwing when it fails."
  ([args] (compose-out args {}))
  ([args opts] (str/trim (:out (compose-run args opts)))))

(defn compose-up
  "Run `docker compose up`.

  Detached mode is the default so a REPL call does not block; pass
  `:detach? false` to attach. Options include `:build?`, `:force-recreate?`,
  `:remove-orphans?`, `:no-deps?`, `:wait?`, `:pull`, and `:services`."
  ([services-or-opts]
   (if (map? services-or-opts)
     (compose-up (:services services-or-opts) services-or-opts)
     (compose-up services-or-opts {})))
  ([] (compose-up nil {}))
  ([service-names opts]
   (let [{:keys [detach? build? force-recreate? remove-orphans? no-deps? wait?
                 pull services]} opts
         service-names (or service-names services)
         args (cond-> ["up"]
                (not= false detach?) (conj "--detach")
                build?               (conj "--build")
                force-recreate?      (conj "--force-recreate")
                remove-orphans?      (conj "--remove-orphans")
                no-deps?             (conj "--no-deps")
                wait?                (conj "--wait")

                (some? pull)
                (conj "--pull" (cli/arg->string pull)))]
     (compose-run (into args (cli/normalize-args service-names)) opts))))

(defn compose-down
  "Run `docker compose down`.

  Options include `:volumes?`, `:remove-orphans?`, `:rmi` (`\"local\"` or
  `\"all\"`), `:timeout`, and `:services`."
  ([] (compose-down {}))
  ([opts]
   (let [{:keys [volumes? remove-orphans? rmi timeout services]} opts
         args (cond-> ["down"]
                volumes?        (conj "--volumes")
                remove-orphans? (conj "--remove-orphans")

                (some? rmi)
                (conj "--rmi" (cli/arg->string rmi))

                (some? timeout)
                (conj "--timeout" (cli/arg->string timeout)))]
     (compose-run (into args (cli/normalize-args services)) opts))))

(defn compose-ps
  "Return `docker compose ps` rows as a vector of maps.

  Options include `:all?` and `:services`. Each map has kebab-case keys such as
  `:name`, `:service`, `:state`, `:status`, `:health`, and `:publishers`."
  ([] (compose-ps {}))
  ([opts]
   (let [{:keys [all? services]} opts
         args (cond-> ["ps" "--format" "json"]
                all? (conj "--all"))]
     (-> (compose-run (into args (cli/normalize-args services)) opts)
         :out
         parse-json-array))))

(defn compose-logs
  "Return `docker compose logs` output as a result map.

  Options include `:follow?` (off by default), `:tail`, `:timestamps?`,
  `:since`, `:until`, and `:services`."
  ([services-or-opts]
   (if (map? services-or-opts)
     (compose-logs (:services services-or-opts) services-or-opts)
     (compose-logs services-or-opts {})))
  ([] (compose-logs nil {}))
  ([service-names opts]
   (let [{:keys [follow? tail timestamps? since until services]} opts
         service-names (or service-names services)
         args (cond-> ["logs"]
                follow?     (conj "--follow")
                timestamps? (conj "--timestamps")

                (some? tail)
                (conj "--tail" (cli/arg->string tail))

                (some? since)
                (conj "--since" (cli/arg->string since))

                (some? until)
                (conj "--until" (cli/arg->string until)))]
     (compose-run (into args (cli/normalize-args service-names)) opts))))

(defn compose-build
  "Run `docker compose build`. Options include `:services`, `:no-cache?`,
  `:pull?`, and `:quiet?`."
  ([] (compose-build {}))
  ([opts]
   (let [{:keys [no-cache? pull? quiet? services]} opts
         args (cond-> ["build"]
                no-cache? (conj "--no-cache")
                pull?     (conj "--pull")
                quiet?    (conj "--quiet"))]
     (compose-run (into args (cli/normalize-args services)) opts))))

(defn compose-pull
  "Run `docker compose pull`. Options include `:quiet?` and `:services`."
  ([] (compose-pull {}))
  ([opts]
   (let [{:keys [quiet? services]} opts
         args (cond-> ["pull"]
                quiet? (conj "--quiet"))]
     (compose-run (into args (cli/normalize-args services)) opts))))

(defn compose-restart
  "Restart compose services. Options include `:timeout` and `:services`."
  ([] (compose-restart {}))
  ([opts]
   (let [{:keys [timeout services]} opts
         args (cond-> ["restart"]
                (some? timeout)
                (conj "--timeout" (cli/arg->string timeout)))]
     (compose-run (into args (cli/normalize-args services)) opts))))

(defn compose-stop
  "Stop compose services. Options include `:timeout` and `:services`."
  ([] (compose-stop {}))
  ([opts]
   (let [{:keys [timeout services]} opts
         args (cond-> ["stop"]
                (some? timeout)
                (conj "--timeout" (cli/arg->string timeout)))]
     (compose-run (into args (cli/normalize-args services)) opts))))

(defn compose-start
  "Start compose services. Options include `:services`."
  ([] (compose-start {}))
  ([opts]
   (compose-run (into ["start"] (cli/normalize-args (:services opts))) opts)))

(defn compose-config
  "Run `docker compose config`.

  Options include `:services?`, `:volumes?`, `:images?`, `:hash?`,
  `:profiles?`, `:quiet?`, `:no-normalize?`, `:no-interpolate?`,
  `:no-path-resolution?`, and `:resolve-image-digests?`."
  ([] (compose-config {}))
  ([opts]
   (let [{:keys [services? volumes? images? hash? profiles? quiet?
                 no-normalize? no-interpolate? no-path-resolution?
                 resolve-image-digests?]} opts
         args (cond-> ["config"]
                services?              (conj "--services")
                volumes?               (conj "--volumes")
                images?                (conj "--images")
                hash?                  (conj "--hash")
                profiles?              (conj "--profiles")
                quiet?                 (conj "--quiet")
                no-normalize?          (conj "--no-normalize")
                no-interpolate?        (conj "--no-interpolate")
                no-path-resolution?    (conj "--no-path-resolution")
                resolve-image-digests? (conj "--resolve-image-digests"))]
     (compose-run args opts))))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(defn login
  "Log in to a registry.

  Options include `:username`, `:password`, and `:password-stdin?` (combine
  with `:in` to feed the password on stdin)."
  ([server] (login server {}))
  ([server opts]
   (let [{:keys [username password password-stdin?]} opts
         args (cond-> ["login"]
                (some? username)
                (conj "--username" (cli/arg->string username))

                (some? password)
                (conj "--password" (cli/arg->string password))

                password-stdin?
                (conj "--password-stdin")

                (some? server)
                (conj (cli/arg->string server)))]
     (run! (command args opts) opts))))

(defn logout
  "Log out of a registry."
  ([server] (logout server {}))
  ([server opts]
   (run! (command (cond-> ["logout"]
                    (some? server) (conj (cli/arg->string server)))
                  opts)
         opts)))

(defn search
  "Search Docker Hub and return the results as a vector of maps.

  Options include `:limit`, `:no-trunc?`, and `:filter` (a string, sequence,
  or map)."
  ([term] (search term {}))
  ([term opts]
   (let [{:keys [limit no-trunc? filter]} opts
         args (cond-> ["search"]
                (some? limit) (conj "--limit" (cli/arg->string limit))
                no-trunc?     (conj "--no-trunc")
                :always       (conj "--format" "{{json .}}"))
         args (-> args
                  (filter-args filter)
                  (conj (cli/arg->string term)))]
     (-> (run! (command args opts) opts)
         :out
         parse-json-lines))))
