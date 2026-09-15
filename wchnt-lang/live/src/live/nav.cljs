(ns live.nav
  "Wiki navigation helpers: recents, backlinks, and page search.

   Navigation is the wiki's real job on mobile (see doc/mobile-design.md).
   These are pure-ish query helpers over `live.storage`; the DOM wiring lives
   in `live.core`."
  (:require [clojure.string :as string]
            [live.storage :as storage]
            [wchnt-lang.wiki-search :as wiki-search]))

(def recents-key storage/recents-key)
(def ^:private recents-cap 12)

(defn recents
  "Recently visited page names, most-recent first (excludes the index page)."
  []
  (try
    (let [raw (.getItem js/localStorage recents-key)]
      (->> (if raw (js->clj (js/JSON.parse raw)) [])
           (remove #(= storage/index-page-name %))
           vec))
    (catch :default _ [])))

(defn record-visit!
  "Push `name` to the front of the recents list, de-duplicated and capped."
  [name]
  (when (and (seq name) (not= name storage/index-page-name))
    (let [updated (->> (recents)
                       (remove #(= % name))
                       (cons name)
                       (take recents-cap)
                       vec)]
      (try
        (.setItem js/localStorage recents-key (js/JSON.stringify (clj->js updated)))
        (catch :default _ nil)))))

(defn- browsable-pages-map
  []
  (dissoc (storage/all-pages-map) storage/index-page-name))

(defn search-pages
  "Name hits and body hits for `q`. See `wchnt-lang.wiki-search/split`."
  [q]
  (wiki-search/split q (browsable-pages-map)))

(defn search
  "Page names matching `q` (case-insensitive substring). Exact matches sort
   first, then prefix matches, then the rest — each group alphabetical."
  [q]
  (:names (search-pages q)))

(defn exact-page?
  "True when `name` resolves to an existing page (case-insensitive)."
  [name]
  (boolean (storage/resolve-page-name name)))

(def ^:private link-re #"\[\[([^\]]+)\]\]")

(defn- links-in
  [content]
  (->> (re-seq link-re (or content ""))
       (map (comp string/lower-case string/trim second))
       set))

(defn backlinks
  "Page names whose content links to `target` via [[target]] (case-insensitive)."
  [target]
  (let [t (string/lower-case (string/trim (or target "")))]
    (if (string/blank? t)
      []
      (->> (storage/all-pages-map)
           (filter (fn [[name content]]
                     (and (not= (string/lower-case name) t)
                          (contains? (links-in content) t))))
           (map first)
           sort
           vec))))
