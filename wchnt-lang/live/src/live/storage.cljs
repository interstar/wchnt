(ns live.storage
  "localStorage-backed wiki pages for the live editor."
  (:require [clojure.string :as string]
            [live.export :as export]
            [wchnt-lang.mainfile :as mainfile]))

(def pages-key "wchnt.wiki.pages")
(def current-key "wchnt.wiki.current")
(def recents-key "wchnt.wiki.recents")

(defn storage-available?
  "True when localStorage accepts read/write (file:// and private mode may block it)."
  []
  (try
    (let [k "__wchnt_storage_probe__"]
      (.setItem js/localStorage k "1")
      (.removeItem js/localStorage k)
      true)
    (catch :default _ false)))

(defn- read-json
  [key default]
  (try
    (let [parsed (if-let [raw (.getItem js/localStorage key)]
                   (js/JSON.parse raw)
                   default)]
      (if (nil? parsed) default parsed))
    (catch :default e
      (js/console.error "WCHNT wiki: localStorage read failed for" key e)
      default)))

(defn- write-json!
  [key value]
  (try
    (.setItem js/localStorage key (js/JSON.stringify value))
    (catch :default e
      (throw (js/Error. (str "Wiki save failed (localStorage): " (.-message e)))))))

(defn- js-pages-map?
  [value]
  (and value
       (not (array? value))
       (not (string? value))
       (not (number? value))
       (= (type value) js/Object)))

(defn- read-pages
  "Always return a mutable object for page storage."
  []
  (let [parsed (read-json pages-key #js {})]
    (if (js-pages-map? parsed)
      parsed
      #js {})))

(def index-page-name "_index")

(defn list-pages
  "Return sorted page names stored in localStorage."
  []
  (-> (js/Object.keys (read-pages))
      (js->clj)
      sort
      vec))

(defn pages-index-content
  "Markdown page listing all wiki pages as [[links]]."
  []
  (let [pages (remove #(= index-page-name %) (list-pages))]
    (str "# All pages\n\n"
         (if (empty? pages)
           "_No pages yet. Use **New** to create one._\n"
           (string/join "\n" (map #(str "- [[" % "]]") pages)))
         "\n")))

(defn current-page
  []
  (.getItem js/localStorage current-key))

(defn set-current-page!
  [name]
  (.setItem js/localStorage current-key name))

(defn get-page
  [name]
  (when-let [entry (aget (read-pages) name)]
    (.-content entry)))

(defn resolve-page-name
  "Find a stored page key matching name (case-insensitive)."
  [name]
  (let [trimmed (string/trim name)]
    (when (seq trimmed)
      (or (when (get-page trimmed) trimmed)
          (some #(when (= (string/lower-case %)
                          (string/lower-case trimmed))
                   %)
                (list-pages))))))

(defn save-page!
  [name content]
  (when-not (mainfile/valid-page-name? name)
    (throw (js/Error. (str "Invalid page name '" name "'"))))
  (let [pages (read-pages)
        entry #js {:content content
                   :updated (.now js/Date)}]
    (aset pages name entry)
    (write-json! pages-key pages)
    (set-current-page! name)))

(defn delete-page!
  [name]
  (let [pages (read-pages)]
    (js-delete pages name)
    (write-json! pages-key pages)
    (when (= name (current-page))
      (.removeItem js/localStorage current-key))))

(defn blank-page
  [name]
  (str "# " name "\n\nWrite prose here. Link siblings with [[PageName]].\n"))

(defn resolve-page
  "For ## Import: load sibling page content from the wiki store."
  [name]
  (get-page name))

(defn reset-pages!
  "Clear wiki storage. Useful if localStorage was corrupted."
  []
  (.removeItem js/localStorage pages-key)
  (.removeItem js/localStorage current-key)
  (.removeItem js/localStorage recents-key))

(defn all-pages-map
  "Return {page-name content} for every stored page (including _index)."
  []
  (let [pages (read-pages)]
    (into {}
          (map (fn [name]
                 [name (or (get-page name) "")])
               (list-pages)))))

(defn export-all-pages
  "Concatenate all wiki pages into one export file."
  []
  (or (export/encode-pages (all-pages-map)) ""))

(defn import-all-pages!
  "Replace wiki storage with pages parsed from an export file.
   Returns the imported page names in file order."
  [text]
  (let [pages (export/decode-pages text)]
    (when (empty? pages)
      (throw (js/Error. "Import file contains no pages.")))
    (reset-pages!)
    (doseq [{:keys [name content]} pages]
      (save-page! name content))
    (mapv :name pages)))
