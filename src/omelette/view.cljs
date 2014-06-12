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

; Print using Nashorn's `print` function if `console` is undefined.
(if (exists? js/console)
  (enable-console-print!)
  (set-print-fn! js/print))

(defn- search-view-query [owner query]
  [:div
   {:on-change #(->> (-> %
                         .-target
                         .-value
                         (str/replace #"\W|\d|_" "")
                         str/lower-case)
                     (om/set-state! owner :query))}
   (html/search-field "query" query)])

(defn- search-view-options [owner options]
  [:div
   (for [[option label] {:prefix "starts with"
                         :infix "includes"
                         :postfix "ends with"}
         :let [handler (fn [event]
                         (let [new-opts ((if (-> event
                                                 .-target
                                                 .-checked)
                                           conj
                                           disj)
                                         options
                                         option)]
                           (when (seq new-opts)
                             (om/set-state! owner :options new-opts))))]]
     [:div
      {:on-change handler}
      (html/check-box (name option) (-> options option boolean))
      (html/label (name option) label)])])

(defn search-view [data owner]
  (reify
    om/IInitState
    (init-state
     [_]
     (dissoc (om/value data) :results))
    om/IWillUpdate
    (will-update
     [_ _ new-state]
     ; Update data if the current and new states are different.
     (when-not (= (om/get-render-state owner)
                  new-state)
       ; Tag it with `:nav` so the router receives it.
       (om/update! data [] new-state :nav)))
    om/IRenderState
    (render-state
     [_ {:keys [query options]}]
     (html
      [:div
       [:form {:on-submit #(.preventDefault %)}
        (search-view-query owner query)
        (search-view-options owner options)]
       [:div
        (if-let [results (:results data)]
          (if (seq results)
            (html/unordered-list results)
            [:h3 "No Results"])
          [:h3 "Loading..."])]]))))

(defn about-view [data]
  (om/component
   (html
    [:div
     (when-let [markdown (:markdown data)]
       (->> markdown
            md/mdToHtml
            (hash-map :__html)
            (hash-map :dangerouslySetInnerHTML)
            (vector :div)))])))

(defn not-found-view []
  (om/component
   (html
    (html/image "/assets/images/404.gif"))))

(defn- app-view-nav [data owner]
  [:nav.navbar.navbar-default
   [:ul.nav.navbar-nav
    (for [[href content] {"/" "Search"
                          "/about" "About"
                          "/not-found" "Not Found"}
          :let [active? (= (-> content
                               str/lower-case
                               (str/replace #" " "-"))
                           (-> data
                               first
                               name))
                handler (fn [event]
                          (.preventDefault event)
                          (csp/put! (om/get-shared owner :nav-tokens) href))]]
      [:li (when active? {:class "active"})
       [:a {:href href
            :on-click handler}
        content]])]])

(defn app-view [data owner]
  (om/component
   (html
    [:div.container-fluid
     (app-view-nav data owner)
     [:div.row
      [:div.col-xs-6
       [:h1 (route/state->title data)]
       (om/build route/router
                 data
                 {:opts {:page-views {"about" about-view
                                      "search" search-view
                                      "not-found" not-found-view}}})]
      [:div.col-xs-6
       [:h2 "App State"]
       (om/build ankha/inspector data)]]])))

(declare app-container
         app-state)

(defn render
  "Renders the app to the DOM.
  Can safely be called repeatedly to rerender the app."[]
  (let [transactions (csp/chan)
        transactions-pub (csp/pub transactions :tag)]
    (om/root app-view
             app-state
             {:target app-container
              :tx-listen #(csp/put! transactions %)
              :shared {:nav-tokens (csp/chan)
                       :transactions transactions
                       :transactions-pub transactions-pub}})))

(defn ^:export render-to-string
  "Takes an app state as EDN and returns the HTML for that state.
  It can be invoked from JS as `omelette.view.render_to_string(edn)`."
  [state-edn]
  (->> state-edn
       edn/read-string
       (om/build app-view)
       dom/render-to-str))

(defn ^:export init
  "Initializes the app.
  Should only be called once on page load.
  It can be invoked from JS as `omelette.view.init(appElementId, stateElementId)`."
  [app-id state-id]
  (->> state-id
       goog.dom/getElement
       .-textContent
       edn/read-string
       atom
       (set! app-state))
  (->> app-id
       goog.dom/getElement
       (set! app-container))
  (render))
