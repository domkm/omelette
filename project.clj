(defproject omelette "0.0.0"

  :description "Example of mirrored server/client rendering and routing using Om, Sente, Secretary, and the Nashorn JavaScript engine."

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}

  :main omelette.handler

  :global-vars {*warn-on-reflection* true}

  :source-paths ["src" "target/src"]

  :resource-paths ["resources" "target/resources"]

  :dependencies [[ankha "0.1.2"]
                 [com.cemerick/url "0.1.1"]
                 [com.taoensso/encore "1.6.0"]
                 [com.taoensso/sente "0.14.1"]
                 [compojure "1.1.8"]
                 [hiccup "1.0.5"]
                 [http-kit "2.1.18"]
                 [lein-light-nrepl "0.0.18"]
                 [markdown-clj "0.9.44"]
                 [om "0.6.2"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2227"]
                 [org.clojure/core.async "0.1.298.0-2a82a1-alpha"]
                 [org.clojure/core.match "0.2.1"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [ring "1.2.2"]
                 [ring/ring-anti-forgery "0.3.2"]
                 [sablono "0.2.17"]]

  :plugins [[com.keminglabs/cljx "0.3.2"]
            [lein-cljsbuild "1.0.3"]
            [lein-pdo "0.1.1"]]

  :hooks [cljx.hooks]

  :cljx {:builds [{:source-paths ["src"],  :output-path "target/src",  :rules :clj}
                  {:source-paths ["src"],  :output-path "target/src",  :rules :cljs}]}

  :cljsbuild {:builds [{:source-paths ["src" "target/src"]
                        :compiler {
;;                                    :preamble ["react/react.js"]
                                   :output-to "target/resources/public/assets/scripts/main.js"
                                   :output-dir "target/resources/public/assets/scripts"
                                   :source-map "target/resources/public/assets/scripts/main.js.map"
;;                                    :source-map-path "assets/scripts/out"
                                   :optimizations :whitespace}
                        :notify-command ["terminal-notifier" "-message"]}]}

  :aliases {"build-once" ["do" "clean," "cljx" "once," "cljsbuild" "once"]
            "build-auto" ["do" "clean"
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
