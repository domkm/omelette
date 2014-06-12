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
            #+cljs [goog.events]
            #+cljs [om.core :as om :include-macros true])
  #+cljs
  (:require-macros [cljs.core.async.macros :as csp]
                   [cljs.core.match.macros :refer [match]])
  #+cljs
  (:import goog.history.EventType
           goog.history.Html5History))

(defn- encode-search-options
  "Takes an options set.
  Returns a path-segment string."
  [options]
  (->> [:prefix :infix :postfix]
       (map options)
       (remove nil?)
       (map name)
       (str/join "-")))

(defn- decode-search-options
  "Takes a path-segment string.
  Returns an options set."
  [string]
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

(defn- search-path->state
  "Takes query and options path-segments.
  Returns corresponding app state."
  [query options]
  (if (valid-options-str? options)
    [:omelette.page/search {:query query
                            :options (decode-search-options options)}]
    [:omelette.page/not-found {}]))

(defn path->state
  "Converts a path to an app state.
  (path->state \"/search/prefix/omelette\")
  => [:omelette.page/search {:query \"omelette\" :options #{:prefix}}]"
  [path]
  (let [default-search (search-path->state "omelette" "prefix-infix-postfix")
        path-segments (->> (-> path str/lower-case (str/split #"/"))
                           (remove str/blank?)
                           vec)]
    (match
     path-segments
     [] default-search
     ["search"] default-search
     ["search" options query] (search-path->state query options)
     ["about"] [:omelette.page/about {}]
     :else [:omelette.page/not-found {}])))

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

(defn- search-state->title
  "Takes search page data map.
  Returns a string describing the search."
  [{:keys [query options]}]
  (let [opt-pairs {:prefix "starts with"
                   :infix "includes"
                   :postfix "ends with"}
        [a b c :as opts] (->> (keys opt-pairs)
                              (map (comp opt-pairs options))
                              (remove nil?))
        opts-str (condp = (count opts)
                   1 a
                   2 (encore/format "%s or %s" a b)
                   3 (encore/format "%s, %s, or %s" a b c))]
    (encore/format "words that %s \"%s\""
                   opts-str
                   query)))

(defn state->title
  "Converts an app state to a title.
  (state->title [:omelette.page/search {:query \"omelette\" :options #{:prefix}}])
  => \"words that begin with \"omelette\"\""
  [[k data]]
  (let [page (name k)]
    (if (= page "search")
      (search-state->title data)
      (->> (str/split page #"-")
           (map str/capitalize)
           (str/join " ")))))

#+clj
(defn- handler-fn
  "Takes a Sente channel socket map.
  Returns an event handler function."
  [{:keys [send-fn]}]
  (fn handler
    [{{{uid :uid, :as session} :session, :as ring-req} :ring-req,
      [page data :as event] :event,
      ?reply-fn :?reply-fn}
     & [?recv]]
    (let [; Sente passes a dummy reply function if one is not provided.
          ; This usage of Sente is probably unusual since the handler is
          ; directly invoked below. Check if it's a dummy reply function and,
          ; if it is, reply by sending the new state back to the client.
          reply (if (-> ?reply-fn meta :dummy-reply-fn?)
                  #(send-fn uid %)
                  ?reply-fn)]
      (condp = (name page)
        "search" (->> data
                      ((juxt :query :options))
                      (apply data/search)
                      (assoc data :results)
                      (vector page)
                      reply)
        "about" (->> (data/about)
                     (hash-map :markdown)
                     (vector page)
                     reply)
        "not-found" (reply event)
        (prn "Unmatched event: " event)))))

#+clj
(defn- wildcard-ring-route
  "Takes an event handler function.
  Returns a wildcard Ring route for server-side rendering."
  [handler]
  (let [render (render/render-fn)]
    (compojure/GET
     "*"
     {{uid :uid, :as session} :session, uri :uri, :as req}
     (let [state (handler {:?reply-fn identity ; `identity` returns the new state.
                           :event (path->state uri)
                           :ring-req req})]
       (assoc req
         ; Render HTML with title and state EDN.
         :body (render (state->title state) (pr-str state))
         ; Clients must have a UID in order to receive messages.
         :session (assoc session :uid (or uid (java.util.UUID/randomUUID)))
         ; Use the state to determine the status.
         :status (if (-> state first name (= "not-found"))
                   404
                   200))))))

#+clj
(defn- ring-routes
  "Takes an event handler function and Sente channel socket map.
  Returns Ring routes for Sente, static resources, and GET requests."
  [handler {:keys [ajax-post-fn ajax-get-or-ws-handshake-fn]}]
  (compojure/routes
   (compojure/POST "/chsk" req (ajax-post-fn req)) ; /chsk routes for Sente.
   (compojure/GET "/chsk" req (ajax-get-or-ws-handshake-fn req))
   (compojure.route/resources "/") ; Serve static resources.
   (wildcard-ring-route handler)))

#+clj
(defrecord Router []
  component/Lifecycle
  (start
   [component]
   (if (:stop! component)
     component
     (let [chsk (sente/make-channel-socket! {})
           handler (handler-fn chsk)
           routes (ring-routes handler chsk)
           stop! (sente/start-chsk-router-loop! handler (:ch-recv chsk))]
       (assoc component
         :stop! stop!
         :ring-routes routes))))
  (stop
   [component]
   (when-let [stop! (:stop! component)]
     (stop!))
   (dissoc component :stop! :ring-routes)))

#+clj
(defn router
  "Creates a router component.
  Key :ring-routes should be used by an http-kit server."
  []
  (map->Router {}))

#+cljs
(defn- start-history!
  "Takes an Om component.
  Initializes an Html5History object and adds it to the component local state."
  [owner]
  (let [history (doto (Html5History.)
                  (.setUseFragment false)
                  (.setPathPrefix "")
                  (.setEnabled true))]
    ; Listen for navigation events that originate from the browser
    ; and update the app state based on the new path.
    (goog.events/listen
     history
     EventType.NAVIGATE
     (fn [event]
       (when (.-isNavigation event)
         (csp/put! (om/get-shared owner :nav-tokens) (.-token event)))))
    ; Add history to local state.
    (om/set-state! owner :history history)))

#+cljs
(defn- stop-history!
  "Takes an Om component with a history object.
  Disables the history object."
  [owner]
  (let [history (om/get-state owner :history)]
    ; Remove goog.events listeners from history object.
    (goog.events/removeAll history)
    ; Disable history object.
    (.setEnabled history false)))

#+cljs
(defn- start-nav-loop!
  "Takes a cursor and an Om component.
  Listens to shared :nav-tokens channel and updates cursor."
  [data owner]
  ; Update app state with state derived from navigation tokens.
  (->> (om/get-shared owner :nav-tokens)
       (csp/map< path->state )
       (csp/reduce #(om/update! data [] %2 :nav) nil)))

#+cljs
(defn- handler-fn
  "Takes a cursor.
  Returns an event handler function that will update the cursor."
  [data]
  (fn handler [event _]
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
     :else (prn "Unmatched event: " event))))

#+cljs
(defn- start-router-loop!
  "Takes a cursor, an Om component, and a Sente channel socket map.
  Starts the channel socket router loop and adds `:stop!`,
  a function to stop the loop, to the component local state."
  [data owner {:keys [ch-recv]}]
  (->> ch-recv
       (sente/start-chsk-router-loop! (handler-fn data))
       (om/set-state! owner :stop!)))

#+cljs
(defn- stop-router-loop!
  "Takes an Om component with a running router loop.
  Stops the router loop."
  [owner]
  ((om/get-state owner :stop!)))

#+cljs
(defn- update-history!
  "Takes an Om component with an Html5History object and a transaction.
  Updates history with the new state."
  [owner {:keys [new-state old-state]}]
  (let [history (om/get-state owner :history)
        new-path (state->path new-state)]
    (if-not (= (first old-state)
               (first new-state))
      (.setToken history new-path) ; Set when page changes;
      (.replaceToken history new-path)))) ; replace otherwise.

#+cljs
(defn- update-title!
  "Takes a transaction.
  Updates `document` title with new state."
  [{:keys [new-state]}]
  (set! js/document.title
        (-> new-state state->title (str " | Omelette"))))

#+cljs
(defn- send-state!
  "Takes a timeout ID, a Sente channel socket map, and a transaction.
  Cancels the timeout and schedules a new app state request.
  Returns a new timeout ID."
  [timeout {:keys [send-fn]} {:keys [new-state]}]
  (js/clearTimeout timeout)
  (js/setTimeout #(send-fn new-state) 250))

#+cljs
(defn- start-tx-loop!
  "Takes a cursor, Om component, and a Sente channel socket map.
  Starts a loop that uses transactions tagged `:nav` to:
    * update `document.title`
    * update `window.history`
    * schedule a request for a new app state"
  [data owner chsk]
  (let [txs (csp/sub (om/get-shared owner :transactions-pub) :nav (csp/chan))]
    (csp/go-loop
     [timeout nil
      tx (csp/<! txs)]
     (csp/go (update-title! tx)
             (update-history! owner tx))
     (recur (send-state! timeout chsk tx)
            (csp/<! txs)))))

#+cljs
(defn- start-router!
  "Takes a cursor and an Om component.
  Uses cursor and component to start router.
  Requires global `window` and `document` objects
  so should not be called when running in Nashorn."
  [data owner]
  (start-history! owner)
  (start-nav-loop! data owner)
  (let [chsk (sente/make-channel-socket! "/chsk" {})]
    (start-router-loop! data owner chsk)
    (start-tx-loop! data owner chsk)))

#+cljs
(defn- stop-router!
  "Takes an Om component with an enabled history object and running router loop.
  Disables the history object and stops the router loop."
  [owner]
  (stop-history! owner)
  (stop-router-loop! owner))

#+cljs
(defn- build-page
  "Takes a cursor and opts with a `:page-views` key.
  Builds the page view associated with the active page."
  [[page data] {views :page-views}]
  (om/build (-> page name views) data))

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
    (render [_] (build-page data opts))
    om/IDidMount
    (did-mount [_] (start-router! data owner))
    om/IWillUnmount
    (will-unmount [_] (stop-router! owner))))
