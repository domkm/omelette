(ns omelette.route
  (:require [clojure.string :as str]
            [taoensso.encore :as encore]
            [taoensso.sente :as sente]
            #+clj [clojure.core.match :refer [match]]
            #+clj [com.stuartsierra.component :as component]
            #+clj [compojure.core :as compojure]
            #+clj [compojure.route]
            #+clj [omelette.data :as data]
            #+clj [omelette.render :as render]
            #+cljs [cljs.core.async :as csp]
            #+cljs [cljs.core.match]
            #+cljs [clojure.set :refer [rename-keys]]
            #+cljs [goog.events]
            #+cljs [om.core :as om :include-macros true])
  #+cljs
  (:require-macros [cljs.core.async.macros :as csp]
                   [cljs.core.match.macros :refer [match]])
  #+cljs
  (:import goog.history.EventType
           goog.history.Html5History))

(defn- encode-search-options [opts]
  (->> [:prefix :infix :postfix]
       (map opts)
       (filter identity)
       (map name)
       (str/join "-")))

(defn- decode-search-options [string]
  (->> (str/split string #"-")
       (map keyword)
       set))

(def ^:private valid-options-str?
  #{"prefix"
    "infix"
    "postfix"
    "prefix-infix"
    "infix-postfix"
    "prefix-postfix"
    "prefix-infix-postfix"})

(defn- search->state [query options]
  (if (valid-options-str? options)
    [:omelette.page/search {:query query
                            :options (decode-search-options options)}]
    [:omelette.page/not-found {}]))

(defn path->state [path]
  (match
   (filterv (complement str/blank?) (-> path str/lower-case (str/split #"/")))
   [] (search->state "omelette" "prefix-infix-postfix")
   ["search"] (search->state "omelette" "prefix-infix-postfix")
   ["search" options query] (search->state query options)
   ["about"] [:omelette.page/about {}]
   :else [:omelette.page/not-found {}]))

(defn state->path [[k data]]
  (let [page (name k)]
    (if (= page "search")
      (str "/search/"
           (-> data :options encode-search-options)
           "/"
           (:query data))
      (str "/" page))))

(defn state->title [[k data]]
  (let [page (name k)]
    (if (= page "search")
      (let [{:keys [query options]} data
            [a b c :as opts] (filter identity [(when (:prefix options) "start with")
                                               (when (:infix options) "include")
                                               (when (:postfix options) "end with")])
            opts-str (condp = (count opts)
                       1 a
                       2 (encore/format "%s or %s" a b)
                       3 (encore/format "%s, %s, or %s" a b c))]
        (encore/format "words that %s \"%s\""
                       opts-str
                       query))
      (->> (str/split page #"-")
           (map str/capitalize)
           (str/join " ")))))

#+clj
(defrecord Router []
  component/Lifecycle
  (start
   [component]
   (if (:chsk-stop component)
     component
     (let [{:keys [send-fn ch-recv connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
           (sente/make-channel-socket! {})

           handler
           (fn [{[id data :as event] :event
                 {{uid :uid :as session} :session :as ring-req} :ring-req
                 ?reply-fn :?reply-fn}
                & [?recv]]
             (let [reply (if (-> ?reply-fn meta :dummy-reply-fn?)
                           #(send-fn uid %)
                           ?reply-fn)]
               (match
                [id data]
                [:omelette.page/search {:query query
                                        :options options}] (->> (data/search query options)
                                                                (assoc data :results)
                                                                (vector id)
                                                                reply)
                [:omelette.page/about _] (reply [id {:markdown data/about}])
                [:omelette.page/not-found _] (reply [id {}])
                :else (prn "Unmatched event: " event))))]

       (assoc component
         :chsk-stop (sente/start-chsk-router-loop! handler ch-recv)
         :send! send-fn
         :recv ch-recv
         :connected-uids connected-uids
         :ring-routes
         (let [render (render/renderer)]
           (compojure/routes
            (compojure/POST "/chsk" req (ajax-post-fn req))
            (compojure/GET "/chsk" req (ajax-get-or-ws-handshake-fn req))
            (compojure.route/resources "/")
            (compojure/GET
             "*"
             {{uid :uid, :as session} :session, uri :uri, :as req}
             (let [state (handler {:event (path->state uri) :ring-req req :?reply-fn identity})]
               (assoc req
                 :status (if (-> state first name (= "not-found")) 404 200)
                 :session (assoc session :uid (or uid (java.util.UUID/randomUUID)))
                 :body (render (state->title state) (pr-str state)))))))))))
  (stop
   [component]
   (when-let [chsk-stop (:chsk-stop component)]
     (chsk-stop))
   (dissoc component :chsk-stop :send! :recv :connected-uids :ring-routes)))

#+clj
(defn router []
  (map->Router {}))

#+cljs
(defn router [data owner opts]
  (reify

    om/IDidMount
    (did-mount
     [_]
     ; initialize history object and add it to state
     (om/set-state! owner :history (doto (Html5History.)
                                     (.setUseFragment false)
                                     (.setPathPrefix "")
                                     (.setEnabled true)))
     ; listen for navigation events that originate from the browser
     ; and update the app state based on the new token
     (goog.events/listen (om/get-state owner :history)
                         EventType.NAVIGATE
                         #(when (.-isNavigation %)
                            (csp/put! (om/get-shared owner :nav-tokens) (.-token %))))
     ; update app state with new nav state
     (->> (om/get-shared owner :nav-tokens)
          (csp/map< path->state )
          (csp/reduce #(om/update! data [] %2 :nav) nil))
     ; initialize the channel socket and add it to state
     (doseq [[k v] (rename-keys (sente/make-channel-socket! "/chsk" {})
                                {:send-fn :send!, :state :chsk-state, :ch-recv :recv})]
       (om/set-state! owner k v))
     ; start chsk router
     (om/set-state! owner
                    :chsk-stop
                    (sente/start-chsk-router-loop!
                     (fn [event _]
                       (match
                        event
                        [:chsk/state {:first-open? true}] (println "Channel socket successfully established!")
                        [:chsk/state chsk-state] (println "Chsk state change: " chsk-state)
                        [:chsk/recv state] (om/update! data state)
                        :else (println "Unmatched event: " event)))
                     (om/get-state owner :recv)))
     ; subscribe to transactions tagged :nav
     (let [txs (csp/sub (om/get-shared owner :transactions-pub) :nav (csp/chan))]
       (csp/go-loop
        [timeout nil
         {:keys [new-state old-state]} (csp/<! txs)]
        (js/clearTimeout timeout)
        ; change document title to reflect app state
        (set! js/document.title (-> new-state state->title (str " | Omelette")))
        ; update the token when the state changes. replace if it's a minor change; set if it's a page change
        (let [history (om/get-state owner :history)
              new-path (state->path new-state)]
          (if (= (first old-state)
                 (first new-state))
            (.replaceToken history new-path)
            (.setToken history new-path)))
        (recur (js/setTimeout #((om/get-state owner :send!) new-state) 250)
               (csp/<! txs)))))

    om/IWillUnmount
    (will-unmount
     [_]
     (let [history (om/get-state owner :history)]
       (goog.events/removeAll history)
       (.setEnabled history false))
     ((om/get-state owner :chsk-stop)))

    om/IRender
    (render
     [_]
     (om/build
      (get-in opts [:page-views (-> data first name)])
      (last data)))))
