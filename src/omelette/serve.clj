(ns omelette.serve
  (:require [com.stuartsierra.component :as component]
            [compojure.core :as compojure]
            [compojure.handler :as handler]
            [org.httpkit.server :as http-kit]
            [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]))

(defrecord Server [port]
  component/Lifecycle
  (start
   [component]
   (if (:stop! component)
     component
     (let [server
           (-> component
               :router
               :ring-routes
               (wrap-anti-forgery {:read-token (comp :csrf-token :params)})
               handler/site
               (http-kit/run-server {:port (or port 0)}))
           port
           (-> server meta :local-port)]
       (println "Web server running on port " port)
       (assoc component :stop! server :port port))))
  (stop
   [component]
   (when-let [stop! (:stop! component)]
     (stop! :timeout 250))
   (dissoc component :stop! :router)))

(defn server
  "Takes a port number.
  Returns an http-kit server component.
  Requires `(get-in this [:router :ring-routes])` to be a routes."
  [port]
  (map->Server {:port port}))
