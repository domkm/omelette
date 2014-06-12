(ns omelette.main
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [omelette.route :as route]
            [omelette.serve :as serve]))

(defn system
  ([] (system nil))
  ([port] (component/system-map
           :router (route/router)
           :server (component/using
                    (serve/server port)
                    [:router]))))

(defn browse [system]
  (->> (get-in system [:server :port])
       (str "http://localhost:")
       java.net.URI.
       (.browse (java.awt.Desktop/getDesktop))))

(defn -main [& _]
  (-> (system)
      component/start
      browse))
