(ns omelette.render
  (require [clojure.java.io :as io]
           [omelette.route :as route])
  (:import [javax.script
            Invocable
            ScriptEngineManager]))

(let [js (doto (.getEngineByName (ScriptEngineManager.) "nashorn")
           ; React requires either "window" or "global" to be defined.
           (.eval "var global = this")
           ; Rendering to string errors with some components in 0.9.0.
           ; Om is waiting for 0.11.0, though I don't know why.
           (.eval ^String (slurp "http://cdnjs.cloudflare.com/ajax/libs/react/0.10.0/react.min.js"))
           (.eval (-> "public/assets/scripts/main.js" io/resource io/reader))
           ; Is there a way to eval it without using closure compiler optimizations?
           ; The compile speed is pretty slow on whitespace, about 6-10 seconds
           )
      view (.eval js "omelette.view")]
  (defn edn->html [edn]
    (.invokeMethod ^Invocable js view "render_to_string" (-> edn list object-array))))

(defn response [req]
  (let [state (route/handler {:event (-> req :uri route/path->state), :ring-req req})
        state-edn (pr-str state)]
    {:status (if (-> state first name (= "not-found"))
               404
               200)
     :title (route/state->title state)
     :body (edn->html state-edn)
     :state state-edn}))
