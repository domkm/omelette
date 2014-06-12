(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [omelette.main :as main]))

(def system nil)

(defn init []
  (alter-var-root #'system (constantly (main/system 3000))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system #(when % (component/stop %))))

(defn go []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'user/go))

(comment
  (pr system)
  (init)
  (start)
  (stop)
  (go)
  (reset)
  (main/browse system))
