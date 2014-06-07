(ns omelette.serve
  (:require [com.stuartsierra.component :as component]
            [compojure.core :as compojure]
            [compojure.handler :as handler]
            [org.httpkit.server :as http-kit]
            [ring.middleware.anti-forgery :as ring-anti-forgery]))

(defrecord Server [port]
  component/Lifecycle
  (start
   [component]
   (if (:server component)
     component
     (let [server
           (-> component
               :router
               :ring-routes
               (ring-anti-forgery/wrap-anti-forgery {:read-token #(-> % :params :csrf-token)})
               handler/site
               (http-kit/run-server {:port (or port 0)}))
           port
           (-> server meta :local-port)]
       (println "Web server running on port " port)
       (assoc component :server server :port port))))
  (stop
   [component]
   (when-let [server (:server component)]
     (server :timeout 250))
   (dissoc component :server :router)))

(defn server []
  (map->Server {}))
