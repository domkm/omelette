(ns omelette.render
  (:require [clojure.java.io :as io]
            [hiccup.page :refer [html5]])
  (:import [javax.script
            Invocable
            ScriptEngineManager]))

(defn renderer []
  (let [js (doto (.getEngineByName (ScriptEngineManager.) "nashorn")
             (.eval "var global = this") ; React requires either "window" or "global" to be defined.
             (.eval (-> "public/assets/scripts/main.js" io/resource io/reader)))
        view (.eval js "omelette.view")]
    (fn render
      [title state-edn]
      (html5
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
        [:meta {:name "viewport" :content "width=device-width"}]
        [:title (str title " | Omelette")]]
       [:body
        [:noscript "If you're seeing this then you're probably a search engine."]
        [:div#omelette-app (.invokeMethod ^Invocable js view "render_to_string" (-> state-edn list object-array))]
        [:script {:type "text/javascript" :src "/assets/scripts/main.js"}]
        [:script#omelette-state {:type "application/edn"} state-edn]
        [:script {:type "text/javascript"} "omelette.view.init('omelette-state')"]
        ]))))

;; ((renderer) "foo" (pr-str [:omelette.page/not-found {}]))
