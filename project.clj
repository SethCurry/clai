(defproject clai "0.1.0-SNAPSHOT"
  :description "A Clojure client for the Ollama API"
  :url "https://docs.ollama.com/api/introduction"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [clj-http "3.13.0"]
                 [cheshire "5.13.0"]
                 [com.taoensso/telemere "1.2.1"]
                 [metosin/malli "0.17.0"]
                 [com.github.seancorfield/next.jdbc "1.3.1118"]
                 [org.postgresql/postgresql "42.7.8"]
                 [reply/reply "0.5.1"]
                 [nrepl/nrepl "1.0.0"]
                 [hiccup/hiccup "2.0.0"]
                 [com.datomic/local "1.0.301"]
                 [io.scurry/acp "0.1.0-SNAPSHOT"]]
  :main clai.core
  ;; `schema/` holds the canonical storage DDL, which `clai.daemon.postgres`
  ;; loads from the classpath at runtime (and the uberjar must carry).
  :resource-paths ["resources" "schema"]
  :target-path "target/%s"
  :repl-options {:init-ns user}
  :profiles
  {:uberjar
   {;; AOT'd `clai` classes refer to classes that dependency namespaces
    ;; generate with `defrecord`/`deftype`/`defprotocol` -- for example
    ;; `acp.agent.AgentHandler` and `taoensso.telemere.impl.WrappedSignal`.
    ;; Those classes are only written into the jar when the namespace that
    ;; defines them is AOT compiled as well, and `:aot :all` only scans this
    ;; project's own `:source-paths`, so dependency namespaces are listed out.
    ;;
    ;; A dependency namespace used only through its functions does not need to
    ;; be listed; add one here if building the uberjar starts failing with a
    ;; `NoClassDefFoundError` for a class it generates. Because dependency
    ;; sources have no timestamp to compare against, `lein clean` is needed
    ;; after upgrading one of these dependencies.
    :aot [#"^clai\..*" #"^user$"

          ;; io.scurry/acp transport. Schema types come from clai.events.schema,
          ;; so none of the acp.schema.* namespaces need AOT compilation.
          acp.agent
          acp.agent.helpers
          acp.errors
          acp.jsonrpc

          ;; com.taoensso/telemere, plus the namespaces it loads at runtime
          taoensso.encore
          taoensso.encore.signals
          taoensso.telemere
          taoensso.telemere.impl
          taoensso.telemere.utils
          taoensso.telemere.consoles
          taoensso.telemere.streams
          taoensso.telemere.files]}})
