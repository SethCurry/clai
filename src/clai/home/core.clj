(ns clai.home.core
  "The agent's REPL: the namespace `eval_clojure` code runs in by default.

  It is an ordinary Clojure namespace with a few conveniences preloaded, so
  the first thing an agent evaluates has somewhere useful to stand:

    * [[help]] prints a cheat sheet of everything listed here;
    * [[tools]] and [[tool]] report the tools the calling agent can use, read
      from the live registry rather than from the system prompt;
    * [[context]], [[session]], and [[history]] report the session the call is
      running in;
    * [[active-plugins]] reports the plugins loaded in this process;
    * [[vars]] and [[var-detail]] (plus `doc`, `source`, `apropos`, `dir`, and
      `find-doc` from `clojure.repl`) read the running code;
    * the `eval`, `registry`, `sessions`, `clj`, `plugins`, `json`, `shell`,
      `io`, and `str` aliases cover the namespaces an agent most often wants.

  Everything defined here, and everything evaluated here, persists for the
  life of the process: a Var defined by one `eval_clojure` call is still there
  in the next one. The user's REPL shares this namespace, so a Var defined
  here is theirs to inspect too -- which is why the agent is encouraged to
  `def` values worth keeping rather than leaving them in a `let`.

  ## Example

      (help)                                  ; what this namespace offers
      (map :id (tools))                       ; => (\"eval_clojure\" \"read_file\" ...)
      (:cwd (context))                        ; => \"/home/me/project\"
      (count (history))                       ; => 12 stored messages
      (map :name (vars 'clai.util))           ; => (\"->bytes\" \"error-message\" ...)
      (:value (var-detail \"clai.util\" \"non-empty-string\"))"
  (:require [clai.agent.tools.builtin.eval :as eval]
            [clai.agent.tools.registry :as registry]
            [clai.daemon.namespaces :as clj]
            [clai.daemon.postgres.sessions :as sessions]
            [clai.plugins :as plugins]
            [clai.util :as util]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :refer [pprint]]
            [clojure.repl :refer [apropos dir doc find-doc source]]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ToolSummary
  "One row of [[tools]]: a tool's id and the description the model is shown."
  [:map
   [:id :string]
   [:description [:maybe :string]]])

(def ToolList
  "The reply to [[tools]]: every tool the calling agent can use, sorted by id."
  [:vector ToolSummary])

(def SessionInfo
  "The reply to [[session]]: a stored session without its (possibly large)
  conversation, which [[history]] returns separately."
  [:map
   [:sessionId :string]
   [:cwd :string]
   [:title {:optional true} [:maybe :string]]
   [:configId {:optional true} [:maybe :any]]
   [:createdAt {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------------------------
;; Session inspection
;; ---------------------------------------------------------------------------

(defn context
  "The live session context of the `eval_clojure` call being evaluated: `:cwd`,
  `:session-id`, `:provider`, `:model`, `:client`, `:clients`,
  `:tool-registry`, `:postgres`, and the agent's limits (see
  `clai.agent.tools.schema/tool-ctx`).

  Returns nil when the code was not run by the `eval_clojure` tool, for
  example from a plain REPL."
  {:malli/schema [:-> [:maybe :map]]}
  []
  eval/*context*)

(defn- tool-id-text
  "Read tool id `id` as a string, accepting a keyword as well."
  [id]
  (if (keyword? id) (name id) (str id)))

(defn tools
  "Every tool the calling agent can use right now, as a [[ToolList]] sorted by
  id.

  This reads the live registry of the call being evaluated, so it is the
  authoritative list -- including tools a configuration, a plugin, or a
  sub-agent setup added -- rather than what the system prompt remembers."
  {:malli/schema [:-> ToolList]}
  []
  (->> (:tool-registry (context))
       (vals)
       (map (fn [{:keys [id spec]}]
              {:id          id
               :description (get-in spec [:function :description])}))
       (sort-by :id)
       vec))

(defn tool
  "The full tool definition (`{:type \"function\" :function {...}}`) of tool
  `id`, or nil when the calling agent cannot use it.

  Use it to look up a tool's exact argument names and what they mean before
  calling it."
  {:malli/schema [:-> [:or :string :keyword] [:maybe :map]]}
  [id]
  (some-> (get (:tool-registry (context)) (tool-id-text id)) :spec))

(defn- postgres+session
  "The `[postgres-client session-id]` pair of the call being evaluated, or nil
  when it has neither (code run outside a session, for example)."
  []
  (let [{:keys [postgres session-id]} (context)]
    (when (and postgres session-id)
      [postgres (str session-id)])))

(defn session
  "The stored session `eval_clojure` is running in, as a [[SessionInfo]], or
  nil when the call has no session or no database client.

  Its `:cwd` is the directory relative paths and shell commands belong in."
  {:malli/schema [:-> [:maybe SessionInfo]]}
  []
  (when-let [[postgres id] (postgres+session)]
    (some-> (sessions/load-session postgres id)
            (dissoc :history))))

(defn history
  "This session's stored conversation, oldest first, as the provider-neutral
  message maps `clai.daemon.postgres.sessions` keeps: the user prompts, the
  assistant's replies and tool calls, and every tool result.

  Returns nil when the call has no session or no database client."
  {:malli/schema [:-> [:maybe [:vector :map]]]}
  []
  (when-let [[postgres id] (postgres+session)]
    (sessions/session-history postgres id)))

(defn active-plugins
  "The plugins loaded in this process, in load order, as
  `clai.plugins/active-plugins` describes them: `{:name :pluginId :priority
  :config :hooks}`."
  {:malli/schema [:-> [:vector :map]]}
  []
  (plugins/active-plugins))

;; ---------------------------------------------------------------------------
;; Runtime inspection
;; ---------------------------------------------------------------------------

(defn vars
  "The public Vars of namespace `ns-name`, sorted by name, as `{:name :doc
  :arglists :macro}` maps. Answers `[]` for a namespace that is not loaded."
  {:malli/schema [:-> [:or :string :symbol] [:vector :map]]}
  [ns-name]
  (-> (clj/namespace-vars (str ns-name))
      :vars))

(util/import-vars clj/list-namespaces clj/var-detail)

;; ---------------------------------------------------------------------------
;; Help
;; ---------------------------------------------------------------------------

(def help-form-column
  "How wide the form column of [[cheat-sheet]] is. A longer form pushes its
  description right instead of being cut."
  40)

(def help-sections
  "The body of [[cheat-sheet]]: `[title rows]` pairs, where a row is either a
  `[form description]` pair or a form too long to share its line."
  [["The live agent"
    [["(help)" "this text"]
     ["(tools)" "every tool you can call: {:id :description}"]
     ["(tool \"run_command\")" "one tool's full schema and its arguments"]
     ["(context)" "{:cwd :session-id :provider :model :tool-registry ...}"]
     ["(session)" "this session's id, title, and working directory"]
     ["(history)" "this session's messages, oldest first"]
     ["(active-plugins)" "plugins loaded in this process"]
     ["(def failing (filter :failed? runs))"
      "save a value: the user's REPL sees it"]]]

   ["The running code"
    [["(vars 'clai.agent.core)" "a namespace's public Vars and docstrings"]
     ["(var-detail \"clai.util\" \"error-message\")" "doc, arglists, source, value"]
     ["(source clai.util/error-message)" "the form that defines a Var"]
     ["(doc clai.agent.core/run-prompt!)" "one docstring"]
     ["(apropos \"session\")" "Vars whose name matches a pattern"]
     ["(dir clai.util)" "just the names in a namespace"]
     ["(list-namespaces)" "every loaded namespace and its docstring"]
     ["(all-ns) (ns-publics 'clai.util)" "plain Clojure introspection"]]]

   ["Reading and running the project"
    [["(slurp \"src/clai/agent/core.clj\")" "read a file"]
     ["(spit \"/tmp/scratch.edn\" {:a 1})" "write one"]
     ["(->> (file-seq (io/file \".\")) (map str) (filter #(.endsWith ^String % \".clj\")))"]
     ["(shell/sh \"git\" \"status\")" "run a command; read :out, :err, :exit"]
     ["(shell/sh \"lein\" \"test\" :dir \"/project\")" "run a command elsewhere"]
     ["(json/parse-string \"{\\\"a\\\": 1}\" true)" "parse JSON into keywords"]
     ["(require 'my.ns :reload)" "reload code you just changed"]
     ["(pprint (tools))" "pretty-print a structured value"]]]])

(def help-footer
  "The last lines of [[cheat-sheet]]: the aliases and refers this namespace
  adds to plain Clojure."
  [""
   "Aliases: eval registry sessions clj plugins json shell io str"
   "Refers:  pprint doc source dir apropos find-doc"])

(defn- help-row
  "Render one [[help-sections]] row: `form` alone when `description` is nil,
  otherwise `form` in the [[help-form-column]] and `description` beside it."
  [form description]
  (if description
    (format (str "  %-" help-form-column "s %s") form description)
    (str "  " form)))

(defn- section-text
  "Render one [[help-sections]] entry as lines: its title, then its rows."
  [[title rows]]
  (cons title (map (fn [[form description]]
                     (help-row form description))
                   rows)))

(def help-header
  "The opening lines of [[cheat-sheet]]."
  ["clai.home.core -- the agent's REPL. Everything here persists for the life"
   "of the process, and the user has a REPL in this same namespace, so a Var"
   "you def is theirs too. One form is read per eval_clojure call, so wrap"
   "several in (do ...)."])

(def cheat-sheet
  "The text [[help]] prints: what this namespace offers an agent, with the
  one-line form to try for each entry. Sections are separated by a blank
  line."
  (str/join
   "\n"
   (concat help-header
           [""]
           (->> help-sections
                (map section-text)
                (interpose [""])
                (apply concat))
           help-footer)))

(defn help
  "Print [[cheat-sheet]]: what this namespace offers and the form to try for
  each entry. Returns nil."
  {:malli/schema [:-> nil?]}
  []
  (println cheat-sheet))
