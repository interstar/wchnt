(ns live.export
  "Serialize / deserialize all wiki pages to one text file."
  (:require [clojure.string :as str]
            [wchnt-lang.mainfile :as mainfile]))

(def page-separator "%%%%%%%%%%%%%%%%%%%")

(defn encode-pages
  "pages: map of page-name → content string. Returns one export file body."
  [pages]
  (let [body (str/join
              (for [[name content] (sort pages)]
                (str page-separator "\n" name "\n" content)))]
    (when (seq (str/trim body))
      body)))

(defn- parse-block
  [block]
  (when-not (str/blank? (str/trim block))
    (let [block (str/replace block #"^\s+" "")
          nl-idx (str/index-of block "\n")
          [name content] (if nl-idx
                           [(str/trim (subs block 0 nl-idx))
                            (subs block (inc nl-idx))]
                           [(str/trim block) ""])]
      (when-not (mainfile/valid-page-name? name)
        (throw (ex-info (str "Invalid page name in import: '" name "'")
                        {:name name})))
      {:name name :content content})))

(defn- split-on-separator
  [text]
  #?(:clj  (str/split text (re-pattern (java.util.regex.Pattern/quote page-separator)))
     :cljs (str/split text page-separator)))

(defn decode-pages
  "Parse an export file into [{:name :content} …] in file order."
  [text]
  (when-not (string? text)
    (throw (ex-info "Import text must be a string" {})))
  (->> (split-on-separator text)
       (remove #(str/blank? (str/trim %)))
       (map parse-block)
       (remove nil?)
       vec))
