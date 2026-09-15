(ns wchnt-lang.wiki-search
  "Split wiki search: page-name hits first, then body hits."
  (:require [clojure.string :as str]))

(defn- norm
  [s]
  (str/lower-case (or s "")))

(defn- rank-names
  [ql names]
  (sort-by (fn [p]
             (let [pl (norm p)]
               [(cond (= pl ql) 0
                      (str/starts-with? pl ql) 1
                      :else 2)
                pl]))
           names))

(defn split
  "Case-insensitive substring search over `{page-name content}`.

   `:names` — titles containing `q` (exact, then prefix, then the rest).
   `:bodies` — other pages whose content contains `q`, alphabetical.
   A blank query lists every name and no bodies."
  [q pages-map]
  (let [ql (str/trim (norm q))
        names (vec (sort (keys pages-map)))]
    (if (str/blank? ql)
      {:names names :bodies []}
      (let [name-hits (filterv #(str/includes? (norm %) ql) names)
            name-set (set (map norm name-hits))
            body-hits (->> pages-map
                           (filter (fn [[n c]]
                                     (and (not (contains? name-set (norm n)))
                                          (str/includes? (norm c) ql))))
                           (map first)
                           sort
                           vec)]
        {:names (vec (rank-names ql name-hits))
         :bodies body-hits}))))
