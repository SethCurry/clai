(defproject clai "0.1.0-SNAPSHOT"
  :description "A Clojure client for the Ollama API"
  :url "https://docs.ollama.com/api/introduction"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.2"]
                 [clj-http "3.13.0"]
                 [cheshire "5.13.0"]
                 [metosin/malli "0.17.0"]]
  :main ^:skip-aot clai.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})