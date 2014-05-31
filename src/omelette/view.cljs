(ns omelette.view
  (:require [ankha.core :as ankha]
            [cljs.core.async :as csp]
            [cljs.core.match]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [goog.events :as events]
            [markdown.core :as md]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [omelette.route :as route]
            [sablono.core :as html :refer-macros [html]]
            [taoensso.encore :as encore :refer [logf]]
            [taoensso.sente :as sente])
  (:require-macros [cljs.core.match.macros :refer [match]]
                   [cljs.core.async.macros :as csp]
                   [omelette.view :refer [chsk<!]])
  (:import goog.history.EventType
           goog.history.Html5History
           goog.Uri))

(if (exists? js/console)
  (enable-console-print!)
  (set-print-fn! js/print))

(def app-state (atom nil))

(when (exists? js/document)
  ; change the document title when the app state changes
  (add-watch app-state
             :document-title
             #(set! js/document.title (route/state->title %4)))
  ; initialize state from document
  (reset! app-state
          (-> "omelette-state"
              js/document.getElementById
              .-textContent
              edn/read-string)))

(when (exists? js/window)
  (let [history (doto (Html5History.)
                  (.setUseFragment false)
                  (.setPathPrefix "")
                  (.setEnabled true))]
    ; update the token when the state changes
    ; replace if it's a minor change
    ; set of it's a major change
    (add-watch app-state
               :history-token
               #(.setToken history (route/state->path %4))
;;                #(if (or (not= (first %3)
;;                               (first %4))
;;                         (and (= (-> %4 first name) "search")
;;                              (-> %4 last :results)))
;;                   (.setToken history (route/state->path %4))
;;                   (.replaceToken history (route/state->path %4)))
               )

    ; listen for navigation events that originate from the browser
    ; and update the app state based on the new token
    (events/listen history
                   EventType.NAVIGATE
                   #(when (.-isNavigation %)
                      (->> % .-token route/path->state (reset! app-state))))))

(defn- clean-state [[id data]]
  [id (dissoc data :results :markdown)])


(let [timeout (atom nil)]
  (defn request-new-app-state!
    ([state]
     (route/send! (clean-state state)
                  5000
                  (fn [resp]
                    (if (sente/cb-success? resp)
                      (when (= (clean-state resp) (clean-state @app-state))
                        (reset! app-state resp))
                      (println "chsk callback failure: " {:sent (clean-state state) :response resp})))))
    ([state timeout-ms]
     (js/clearTimeout @timeout)
     (reset! timeout (js/setTimeout #(request-new-app-state! state) timeout-ms)))))

(defn nav-link-to [href content]
  [:a {:href href
       :on-click (fn [e]
                   (.preventDefault e)
                   (reset! app-state (route/path->state href)))}
   content])

(defn loading-view [& args]
  (om/component
   (html
    [:div
     [:h2 "Loading..."]
     (html/image "/assets/images/loading.gif")])))

(defn about-view [markdown]
  (om/component
   (html
     [:div {:dangerouslySetInnerHTML {:__html (md/mdToHtml markdown)}}])))

(defn search-form-view [{:keys [query options] :as data} owner]
  (reify
    om/IRender
    (render
     [_]
     (html [:form {:on-change (fn [_] (request-new-app-state! @app-state 300))}
            [:input {:type "search"
                     :value query
                     :on-change #(om/update! data :query (-> % .-target .-value str/lower-case))}]
            [:div {:on-click #(om/update! options ((if (-> % .-target .-checked)
                                                     conj
                                                     disj)
                                                   options
                                                   (-> % .-target .-name keyword)))}
             (html/check-box "prefix" (-> options :prefix boolean))
             (html/label "prefix" "starts with")
             [:br]
             (html/check-box "infix" (-> options :infix boolean))
             (html/label "infix" "includes")
             [:br]
             (html/check-box "postfix" (-> options :postfix boolean))
             (html/label "postfix" "ends with")
             [:br]]]))))

(defn search-results-view [results]
  (om/component
   (html
    (if (empty? results)
      [:div "no results"]
      (html/unordered-list results)))))

(defn search-view [{:keys [results] :as data} owner]
  (om/component
   (html
    [:div
     (om/build search-form-view (dissoc data :results))
     (if results
       (om/build search-results-view results)
       (do (request-new-app-state! @app-state)
         (om/build loading-view {})))
     ])))

(defn app-view [data owner]
  (om/component
   (html
    [:div
     [:nav
      (nav-link-to "/" "Search")
      (nav-link-to "/about" "About")]
     [:h1 (route/state->title data)]
     (condp = (-> data first name)
       "search" (om/build search-view (last data))
       "about" (if-let [m (-> data last :markdown)]
                 (om/build about-view m)
                 (do (request-new-app-state! @app-state)
                   (om/build loading-view {})))
       "not-found" (html/image "/assets/images/404.gif"))])))

(when (exists? js/window)
  (set! (.-onload js/window)
        (fn []
          (om/root app-view
                   app-state
                   {:target (js/document.getElementById "omelette-app")})
          (om/root ankha/inspector
                   app-state
                   {:target (js/document.getElementById "ankha")})
          (route/start!))))

; LT is chocking if we export this fn
(defn  render-to-string [state-edn]
  (->> state-edn
       edn/read-string
       (om/build app-view)
       dom/render-to-str))
