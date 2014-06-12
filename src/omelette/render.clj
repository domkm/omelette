(ns omelette.render
  (:require [clojure.java.io :as io]
            [hiccup.page :refer [html5 include-css include-js]])
  (:import [javax.script
            Invocable
            ScriptEngineManager]))

(defn render-fn
  "Returns a function to render fully-formed HTML.
  (fn render [title app-state-edn])"
  []
  (let [js (doto (.getEngineByName (ScriptEngineManager.) "nashorn")
             ; React requires either "window" or "global" to be defined.
             (.eval "var global = this")
             (.eval (-> "public/assets/scripts/main.js"
                        io/resource
                        io/reader)))
        view (.eval js "omelette.view")
        render-to-string (fn [edn]
                           (.invokeMethod
                            ^Invocable js
                            view
                            "render_to_string"
                            (-> edn
                                list
                                object-array)))]
    (fn render [title state-edn]
      (html5
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
        [:meta {:name "viewport" :content "width=device-width"}]
        [:title (str title " | Omelette")]]
       [:body
        [:noscript "If you're seeing this then you're probably a search engine."]
        (include-css "//cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.1.1/css/bootstrap.css")
        (include-js "/assets/scripts/main.js")
        ; Render view to HTML string and insert it where React will mount.
        [:div#omelette-app (render-to-string state-edn)]
        ; Serialize app state so client can initialize without making an additional request.
        [:script#omelette-state {:type "application/edn"} state-edn]
        ; Initialize client and pass in IDs of the app HTML and app EDN elements.
        [:script {:type "text/javascript"} "omelette.view.init('omelette-app', 'omelette-state')"]]))))
