(defproject omelette "0.0.0"

  :description "Example of mirrored server/client rendering and routing using React/Om, Sente, and the Nashorn JavaScript engine."

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}

  :main omelette.main

  :global-vars {*warn-on-reflection* true}

  :source-paths ["src" "target/src"]

  :resource-paths ["resources" "target/resources"]

  :dependencies [[ankha "0.1.3"]
                 [com.stuartsierra/component "0.2.1"]
                 [com.taoensso/encore "1.6.0"]
                 [com.taoensso/sente "0.14.1"]
                 [compojure "1.1.8"]
                 [hiccup "1.0.5"]
                 [http-kit "2.1.18"]
                 [markdown-clj "0.9.44"]
                 [om "0.6.4"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2227"]
                 [org.clojure/core.async "0.1.298.0-2a82a1-alpha"]
                 [org.clojure/core.match "0.2.1"]
                 [ring "1.3.0"]
                 [ring/ring-anti-forgery "0.3.2"]
                 [sablono "0.2.17"]]

  :plugins [[com.keminglabs/cljx "0.4.0"]
            [lein-cljsbuild "1.0.3"]
            [lein-pdo "0.1.1"]]

  :hooks [cljx.hooks leiningen.cljsbuild]

  :cljx {:builds [{:source-paths ["src"],  :output-path "target/src",  :rules :clj}
                  {:source-paths ["src"],  :output-path "target/src",  :rules :cljs}]}

  :cljsbuild {:builds [{:source-paths ["src" "target/src"]
                        :compiler {
                                   :preamble ["react/react.min.js"]
                                   :output-to "target/resources/public/assets/scripts/main.js"
                                   :output-dir "target/resources/public/assets/scripts"
                                   :source-map "target/resources/public/assets/scripts/main.js.map"
                                   :optimizations :whitespace}
                        :notify-command ["terminal-notifier" "-message"]}]}

  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.4"]]
                   :source-paths ["dev"]}
             :build {}}

  :aliases {"build-auto" ["with-profile" "build"
                          "do" "clean"
                          ["cljx" "once"]
                          ["pdo"
                           "cljx" "auto,"
                           "cljsbuild" "auto"]]
            "run-dev" ["do" "clean"
                       ["cljx" "once"]
                       ["cljsbuild" "once"]
                       ["pdo"
                        "cljx" "auto,"
                        "cljsbuild" "auto,"
                        "run"]]})
