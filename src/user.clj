(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [omelette.main :as main]))

(defonce system
  (atom main/system))

(defn reset []
  (reset! system main/system))

(defn start []
  (swap! system component/start))

(defn stop []
  (swap! system component/stop))

(defn restart []
  (stop)
  (refresh :after 'user/start))

(comment
  (reset)
  (start)
  (stop)
  (restart)
  (main/browse @system)
  (prn @system))
