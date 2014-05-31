(ns omelette.route
  (:require [cemerick.url :as url]
            [clojure.string :as str]
            #+clj [clojure.core.match :refer [match]]
            #+cljs [cljs.core.match]
            #+clj [omelette.data :as data]
            [taoensso.sente :as sente]
            [taoensso.encore :as encore])
  #+cljs
  (:require-macros [cljs.core.match.macros :refer [match]]))

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

(defn- ^:boolean valid-options-str? [options]
  (-> options
      #{"prefix"
        "infix"
        "postfix"
        "prefix-infix"
        "infix-postfix"
        "prefix-postfix"
        "prefix-infix-postfix"}
      boolean))

(defn- search->state [query options]
  (if (valid-options-str? options)
    [:omelette.page/search {:query query
                            :options (decode-search-options options)}]
    [:omelette.page/not-found {}]))

(defn path->state [path]
  (match
   (filterv (complement str/blank?) (-> path
                                        str/lower-case
                                        (str/split #"/")))
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
                       (url/url-encode query)))
      (->> (str/split page #"-")
           (map str/capitalize)
           (str/join " ")
           ))))


#+clj
(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn connected-uids]}
      (sente/make-channel-socket! {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def recv                       ch-recv) ; ChannelSocket's receive channel
  (def send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

#+cljs
(when (exists? js/window)
  (let [{:keys [chsk ch-recv send-fn state]}
        (sente/make-channel-socket! "/chsk" ; Note the same URL as before
                                    {})]
    (def chsk       chsk)
    (def recv    ch-recv) ; ChannelSocket's receive channel
    (def send! send-fn) ; ChannelSocket's send API fn
    (def state state) ; Watchable, read-only atom
    ))

#+clj
(defn handler
  [{[id data :as event] :event
    {{uid :uid :as session} :session :as ring-req} :ring-req
    ?reply-fn :?reply-fn :or {?reply-fn identity}}
   & [?recv]]
  (prn "Event: " event)

  (match
   [id data]

   [:omelette.page/search {:query query
                           :options options}] (->> (data/search query options)
                                                   (assoc data :results)
                                                   (vector id)
                                                   ?reply-fn)

   [:omelette.page/about _] (?reply-fn [id {:markdown data/about}])

   [:omelette.page/not-found _] (?reply-fn [id {}])

   :else
   (do (prn "Unmatched event: " event)
     (when-not (:dummy-reply-fn? (meta ?reply-fn))
       (?reply-fn {:umatched-event-as-echoed-from-server event})))))

#+cljs
(defn handler [[id data :as ev] _]
  (println "Event: " ev)
  (match [id data]
    ;; TODO Match your events here <...>
    [:chsk/state {:first-open? true}]
    (println "Channel socket successfully established!")
    [:chsk/state new-state] (println "Chsk state change: %s" new-state)
    [:chsk/recv  payload]   (println "Push event from server: %s" payload)
    :else (println "Unmatched event: %s" ev)))

(defn start! []
  (sente/start-chsk-router-loop! handler recv))
