(ns omelette.data
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private words
  (with-open [file (-> "words.txt" io/resource io/reader)]
    (->> file
         line-seq
         (filter #(= (str/lower-case %) %))
         doall)))

(defn- compile-re [query options]
  (let [pre (when (:prefix options)
              (str "^" query))
        in (when (:infix options)
             (str "^.+" query ".+$"))
        post (when (:postfix options)
               (str query "$"))]
    (->> [pre in post]
         (filter identity)
         (str/join "|")
         re-pattern)))

(defn search [query options]
  (filter #(re-find (compile-re query options) %) words))

(def about
  (-> "about.md" io/resource slurp))
