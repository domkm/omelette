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

(defn path->state
  "Converts a path to an app state.
  (path->state \"/search/prefix/omelette\")
  => [:omelette.page/search {:query \"omelette\" :options #{:prefix}}]"
  [path]
  (match
   (filterv (complement str/blank?) (-> path str/lower-case (str/split #"/")))
   [] (search->state "omelette" "prefix-infix-postfix")
   ["search"] (search->state "omelette" "prefix-infix-postfix")
   ["search" options query] (search->state query options)
   ["about"] [:omelette.page/about {}]
   :else [:omelette.page/not-found {}]))

(defn state->path
  "Converts an app state to a path.
  (state->path [:omelette.page/search {:query \"omelette\" :options #{:prefix}}])
  => \"/search/prefix/omelette\""
  [[k data]]
  (let [page (name k)]
    (if (= page "search")
      (str "/search/"
           (-> data :options encode-search-options)
           "/"
           (:query data))
      (str "/" page))))

(defn state->title
  "Converts an app state to a title.
  (state->title [:omelette.page/search {:query \"omelette\" :options #{:prefix}}])
  => \"words that begin with \"omelette\"\""
  [[k data]]
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
     (let [; Create a Sente channel socket (chsk)
           {:keys [send-fn ch-recv connected-uids ajax-post-fn ajax-get-or-ws-handshake-fn]}
           (sente/make-channel-socket! {})
           ; Function to handle events.
           ; Events can be sent from clients or server and will respond with a modified app state either way
           handler
           (fn [{[page data :as event] :event
                 {{uid :uid :as session} :session :as ring-req} :ring-req
                 ?reply-fn :?reply-fn}
                & [?recv]]
             (let [reply
                   ; Sente passes a dummy reply fn if once is not provided on the client-side
                   ; This usage of Sente is probably unusual since it calls the handler fn directly below,
                   ; so use a different reply fn unless one is passed in directly or from the client.
                   (if (-> ?reply-fn meta :dummy-reply-fn?)
                           #(send-fn uid %) ; Reply by sending the new state back to the client.
                           ?reply-fn)]
               (match
                event
                [:omelette.page/search {:query query
                                        :options options}] (->> (data/search query options)
                                                                (assoc data :results)
                                                                (vector page)
                                                                reply)
                [:omelette.page/about _] (reply [page {:markdown (data/about)}])
                [:omelette.page/not-found _] (reply [page {}])
                :else (prn "Unmatched event: " event))))]
       ; Return a started router component that can be used by the server component.
       (assoc component
         :chsk-stop (sente/start-chsk-router-loop! handler ch-recv) ; Function to stop the router loop.
         :send! send-fn ; Function to send messages to connected clients.
         :recv ch-recv ; Channel that receives events send from clients.
         :connected-uids connected-uids ; Atom of connected client UIDs
         :ring-routes ; Ring routes to be used by the server component.
         (let [render (render/renderer)] ; Create a new render function.
           (compojure/routes
            (compojure/POST "/chsk" req (ajax-post-fn req)) ; /chsk routes for Sente.
            (compojure/GET "/chsk" req (ajax-get-or-ws-handshake-fn req))
            (compojure.route/resources "/") ; Serve static resources.
            (compojure/GET
             "*" ; Wildcard route that will render fully-formed HTML.
             {{uid :uid, :as session} :session, uri :uri, :as req}
             ; Call the handler function directly and get the result using `identity` as the reply-fn.
             (let [state (handler {:event (path->state uri) :ring-req req :?reply-fn identity})]
               (assoc req
                 ; Pass the title and the state to the render fn and assoc the returned HTML.
                 :body (render (state->title state) (pr-str state))
                 ; Clients must have a UID in order to send messages to them.
                 :session (assoc session :uid (or uid (java.util.UUID/randomUUID)))
                 ; Use the state to determine the status.
                 :status (if (-> state first name (= "not-found")) 404 200))))))))))
  (stop
   [component]
   (when-let [chsk-stop (:chsk-stop component)]
     (chsk-stop)) ; Stop the chsk loop.
   (dissoc component :chsk-stop :send! :recv :connected-uids :ring-routes)))

#+clj
(defn router
  "Creates a router component.
  Key :ring-routes should be used by a parent component."
  []
  (map->Router {}))

#+cljs
(defn router
  "Creates a router component.
  :page-views key in opts should be a map of page name to page views:
  {:page-views {\"about\" about-view
                \"not-found\" not-found-view}}
  Shared :nav-tokens should be a channel onto which other components should put relative paths when links are clicked.
  Shared :transactions-pub should be publication of transactions with :tag as the topic-fn."
  [data owner opts]
  (reify
    om/IRender
    (render
     [_]
     (om/build ; Build the page view and pass in the page data.
      (get-in opts [:page-views (-> data first name)])
      (last data)))
    ; Initialize things that are incompatible with Nashorn (anything related to `window` or `document`)
    ; or unnecessary (core.async loops).
    om/IDidMount
    (did-mount
     [_]
     ; Initialize history object and add it to local state.
     (om/set-state! owner :history (doto (Html5History.)
                                     (.setUseFragment false)
                                     (.setPathPrefix "")
                                     (.setEnabled true)))
     ; Listen for navigation events that originate from the browser
     ; and update the app state based on the new path.
     (goog.events/listen (om/get-state owner :history)
                         EventType.NAVIGATE
                         #(when (.-isNavigation %)
                            (csp/put! (om/get-shared owner :nav-tokens) (.-token %))))
     ; Update app state with state derived from navigation tokens.
     (->> (om/get-shared owner :nav-tokens)
          (csp/map< path->state )
          (csp/reduce #(om/update! data [] %2 :nav) nil))
     ; Initialize channel socket and add it to local state.
     (doseq [[k v] (rename-keys (sente/make-channel-socket! "/chsk" {})
                                {:send-fn :send!, :state :chsk-state, :ch-recv :recv})]
       (om/set-state! owner k v))
     ; Start channel socket router loop and add stop function to local state.
     (om/set-state! owner
                    :chsk-stop
                    (sente/start-chsk-router-loop!
                     (fn [event _]
                       (match
                        event
                        [:chsk/state {:first-open? true}] (println "Channel socket successfully established!")
                        [:chsk/state chsk-state] (println "Chsk state change: " chsk-state)
                        ; Events sent from the server have an ID of `:chsk/recv`.
                        ; Update app state with the new state.
                        ; This is a potential bug since events are not guaranteed to be sequential.
                        [:chsk/recv state] (when (= (first state)
                                                    (first @data))
                                             (om/update! data state))

                        :else (prn "Unmatched event: " event)))
                     (om/get-state owner :recv)))

     (let [txs (csp/sub (om/get-shared owner :transactions-pub) :nav (csp/chan))
           send! (om/get-state owner :send!)]
       (csp/go-loop
        [timeout nil
         {:keys [new-state old-state]} (csp/<! txs)]
        ; Cancel timeout set below.
        (js/clearTimeout timeout)
        ; Change document title to reflect new app state.
        (set! js/document.title (-> new-state state->title (str " | Omelette")))
        ; Update the token when the state changes.
        (let [history (om/get-state owner :history)
              new-path (state->path new-state)]
          (if-not (= (first old-state)
                     (first new-state))
            (.setToken history new-path) ; Set when page changes;
            (.replaceToken history new-path))) ; replace otherwise.
        (recur (js/setTimeout #(send! new-state) 250)
               (csp/<! txs)))))
    ; Clean up so that router can be safely rendered multiple times.
    om/IWillUnmount
    (will-unmount
     [_]
     (let [history (om/get-state owner :history)]
       (goog.events/removeAll history) ; Remove goog.events listeners from history object.
       (.setEnabled history false)) ; Disable history object.
     ((om/get-state owner :chsk-stop))) ; Stop channel socket loop.

    ))
