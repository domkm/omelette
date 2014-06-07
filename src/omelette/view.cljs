(ns omelette.view
  (:require [ankha.core :as ankha]
            [cljs.core.async :as csp]
            [cljs.reader :as edn]
            [clojure.string :as str]
            [goog.dom]
            [markdown.core :as md]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [omelette.route :as route]
            [sablono.core :as html :refer-macros [html]])
  (:require-macros [cljs.core.async.macros :as csp]))

(if (exists? js/console)
  (enable-console-print!)
  (set-print-fn! js/print))

(defn not-found-view []
  (om/component
   (html
    (html/image "/assets/images/404.gif"))))

(defn about-view [data]
  (om/component
   (html
    (if-let [markdown (:markdown data)]
      (->> markdown
           md/mdToHtml
           (hash-map :__html)
           (hash-map :dangerouslySetInnerHTML)
           (vector :div))
      [:div
       [:h3 "Loading..."]
       (html/image "/assets/images/loading.gif")]))))

(defn search-view [data owner]
  (reify
    om/IInitState
    (init-state [_] (dissoc (om/value data) :results))
    om/IRenderState
    (render-state
     [_ {:keys [query options]}]
     (html
      [:div
       [:form {:on-change #(om/update! data [] (om/get-state owner) :nav)
               :on-submit #(.preventDefault %)}
        [:input {:type "search"
                 :value query
                 :on-change #(om/set-state! owner :query (-> % .-target .-value (str/replace #"\W|\d|_" "") str/lower-case))}]
        [:br]
        [:div {:on-change #(om/set-state! owner :options ((if (-> % .-target .-checked)
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
         (html/label "postfix" "ends with")]]
       [:div
        (if-let [results (:results data)]
          (if (seq results)
            (html/unordered-list results)
            [:em "no results"])
          [:div
           [:h3 "Loading..."]
           (html/image "/assets/images/loading.gif")])]]))))

(defn app-view [data owner]
  (om/component
   (letfn [(nav-link-to
            [href content]
            [:a {:href href
                 :on-click (fn [e]
                             (.preventDefault e)
                             (csp/put! (om/get-shared owner :nav-tokens) href))}
             content])]
     (html
      [:div
       [:div
        [:nav
         (nav-link-to "/" "Search")
         (nav-link-to "/about" "About")
         (nav-link-to "/not-found" "Not Found")]
        [:h1 (route/state->title data)]
        (om/build route/router
                  data
                  {:opts {:page-views {"about" about-view
                                       "search" search-view
                                       "not-found" not-found-view}}})]
       [:div
        (om/build ankha/inspector data)]]))))

(def app-state (atom nil))

(defn render []
  (let [transactions (csp/chan)
        transactions-pub (csp/pub transactions :tag)]
    (om/root app-view
             app-state
             {:target (goog.dom/getElement "omelette-app")
              :tx-listen #(csp/put! transactions %)
              :shared {:nav-tokens (csp/chan)
                       :transactions transactions
                       :transactions-pub transactions-pub}})))

(defn ^:export render-to-string [state-edn]
  (->> state-edn
       edn/read-string
       (om/build app-view)
       dom/render-to-str))

(defn ^:export init [id]
  (->> id
       goog.dom/getElement
       .-textContent
       edn/read-string
       (reset! app-state))
  (render))
