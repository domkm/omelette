(ns omelette.handler
  (:gen-class)
  (:require [clojure.tools.nrepl.server :as nrepl]
            [compojure.core :refer [GET POST ANY defroutes routes]]
            [compojure.handler :as handler]
            [compojure.route ]
            [hiccup.page :refer [html5]]
            [lighttable.nrepl.handler :as lt]
            [omelette.route :as route]
            [omelette.render :as render]
            [org.httpkit.server :refer [run-server]]
            [ring.middleware.anti-forgery :as ring-anti-forgery]
            [ns-tracker.core :refer [ns-tracker]]))

(let [stop-router! (atom nil)
      reset-router! (fn []
                      (@stop-router!)
                      (reset! stop-router! (route/start!)))]
  (defn wrap-reload
    "Reload namespaces of modified files before the request is passed to the
    supplied handler.

    Takes the following options:
    :dirs - A list of directories that contain the source files.
    Defaults to [\"src\"]."
    [handler & [options]]

    (reset! stop-router! (route/start!))

    (let [source-dirs (:dirs options ["src" "target/src"])
          modified-namespaces (ns-tracker source-dirs)]
      (fn [request]
        (doseq [ns-sym (modified-namespaces)]
          (require ns-sym :reload))
;;         (require '[omelette.route :as route] :reload)
        (reset-router!)
        (handler request)))))

(defn page-handler [req]
  (let [{:keys [status title body state]} (render/response req)]
    {:status status
     :session (assoc (:session req) :uid (or (-> req :session :uid)
                                             (java.util.UUID/randomUUID)))
     :body (html5
            [:head
              [:meta {:charset "utf-8"}]
              [:meta {:http-equiv "X-UA-Compatible" :content "IE=edge,chrome=1"}]
              [:title title]
              [:meta {:name "viewport" :content "width=device-width"}]]
            [:body
             [:noscript "If you're seeing this then you're probably a search engine."]
             [:div#omelette-app body]
             [:script#omelette-state {:type "application/edn"} state]
             [:div#ankha {:style "position:absolute;top:100px;right:100px;"}]
             [:script {:type "text/javascript" :src "//cdnjs.cloudflare.com/ajax/libs/react/0.10.0/react.js"}]
             [:script {:type "text/javascript" :src "/assets/scripts/main.js"}]])}))

(defroutes app-handler
  (->
      (routes
       (GET  "/chsk" req (route/ring-ajax-get-or-ws-handshake req))
       (POST "/chsk" req (route/ring-ajax-post req))
       (GET "/" req (page-handler req))
       (GET "/about" req (page-handler req))
       (GET "/search" req (page-handler req))
       (GET "/search/*/*" req (page-handler req))
       (compojure.route/resources "/")
       (compojure.route/not-found "Not found")
;;        This catches everything. How do we just catch routes that aren't resources or /chsk?
;;        (GET "*" req (page-handler req))
;;        (route/not-found "Not Found")
)
      (ring-anti-forgery/wrap-anti-forgery
       {:read-token (fn [req] (-> req :params :csrf-token))})))

(defn -main [& args]
  (let [handler (-> #'app-handler handler/site (wrap-reload  {:dirs ["src" "target/src"]}))
        server (run-server handler {:port 0})
        uri (format "http://localhost:%s/" (-> server meta :local-port))
        nrepl-server (nrepl/start-server :handler (nrepl/default-handler #'lt/lighttable-ops))
        nrepl-uri (format "http://localhost:%s/" (:port nrepl-server))]
    (println "Server running at " uri)
    (println "nrepl server running " nrepl-uri)
    (.browse (java.awt.Desktop/getDesktop)
             (java.net.URI. uri))))
