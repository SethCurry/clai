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
                 [ring/ring-core "1.15.4"]
                 [ring/ring-jetty-adapter "1.15.4"]
                 [hiccup/hiccup "2.0.0"]
                 [com.datomic/local "1.0.301"]]
  ;; `schema/` holds the canonical storage DDL, which `clai.daemon.postgres`
  ;; loads from the classpath at runtime (and the uberjar must carry).
  :resource-paths ["resources" "schema"]
  :target-path "target/%s"
  :repl-options {:init-ns user}
  ;; Build the example today's-date plugin on its own with `lein plugin-jar`.
  ;; The plugin source lives under `src/` so the main project can also load and
  ;; test it directly; the profile packages only that namespace.
  :aliases {"plugin-jar" ["with-profile" "plugin" "jar"]}
  :profiles
  {;; `:main` lives in the dev and uberjar profiles so plugin-only builds do
   ;; not implicitly AOT-compile and package `clai.core`.
   :dev
   {:main clai.core}

   :plugin
   {:target-path "target/plugin"
    :resource-paths ^:replace []
    :aot [clai-plugin-todays-date.plugin]
    :jar-name "clai-plugin-todays-date.jar"
    ;; The source path is the project's `src/`, but only the plugin namespace
    ;; belongs in the plugin jar.
    :jar-exclusions [#"^(?!clai_plugin_todays_date/).*$"]}

   :uberjar
   {:main clai.core
    ;; AOT'd `clai` classes refer to classes that dependency namespaces
    ;; generate with `defrecord`/`deftype`/`defprotocol` -- for example
    ;; `taoensso.telemere.impl.WrappedSignal`. Those classes are only written
    ;; into the jar when the namespace that defines them is AOT compiled as
    ;; well, and `:aot :all` only scans this project's own `:source-paths`, so
    ;; dependency namespaces are listed out.
    ;;
    ;; A dependency namespace used only through its functions does not need to
    ;; be listed; add one here if building the uberjar starts failing with a
    ;; `NoClassDefFoundError` for a class it generates. Because dependency
    ;; sources have no timestamp to compare against, `lein clean` is needed
    ;; after upgrading one of these dependencies.
    :aot [#"^clai\..*" #"^user$"

          ;; com.taoensso/telemere, plus the namespaces it loads at runtime
          taoensso.encore
          taoensso.encore.signals
          taoensso.telemere
          taoensso.telemere.impl
          taoensso.telemere.utils
          taoensso.telemere.consoles
          taoensso.telemere.streams
          taoensso.telemere.files]}})
