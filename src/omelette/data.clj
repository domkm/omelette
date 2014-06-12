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

(defn search
  "Takes a string and set.
  Set can `:prefix`, `:infix`, and `:postfix`.
  Returns words that have string in any of the positions specified in options."
  [query options]
  (->> words
       (filter #(re-find (compile-re query options) %))
       (take 500)))

(defn about
  "Returns raw markdown for the \"About\" page."
  []
  (-> "about.md" io/resource slurp))
