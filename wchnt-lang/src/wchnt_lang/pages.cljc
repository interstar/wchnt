(ns wchnt-lang.pages
  "Sibling page resolution and IR merge for ## Import."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pipeline :as p]))

(defn- schema-class-names
  [schema-ir]
  (into #{} (map :name) (:assemblages schema-ir)))

(defn- method-keys
  [methods-ir]
  (set (map (juxt :class :method-name) methods-ir)))

(defn- merge-map-keys
  [label base overlay]
  (let [clash (set/intersection (set (keys base)) (set (keys overlay)))]
    (when (seq clash)
      (throw (ex-info (str "Import clash: duplicate " label " " (first clash))
                      {:label label :key (first clash)})))
    (merge base overlay)))

(defn merge-schema-irs
  "Merge overlay schema IR into base. Fail on duplicate class names."
  [base overlay]
  (let [clash (set/intersection (schema-class-names base) (schema-class-names overlay))]
    (when (seq clash)
      (throw (ex-info (str "Import clash: duplicate class " (first clash))
                      {:class (first clash)})))
    (-> base
        (update :assemblages into (:assemblages overlay))
        (update :interfaces into (:interfaces overlay))
        (update :enums into (:enums overlay))
        (update :observable-classes into (:observable-classes overlay))
        (update :subscriber-classes into (:subscriber-classes overlay))
        (update :mutable-classes into (:mutable-classes overlay))
        (update :public-methods #(into (or % #{}) (or (:public-methods overlay) #{})))
        (update :static-public-methods #(into (or % #{}) (or (:static-public-methods overlay) #{})))
        (update :mailbox-classes into (:mailbox-classes overlay))
        (update :debug-methods into (:debug-methods overlay))
        (update :external-types #(into (or % #{}) (:external-types overlay)))
        (update :context-relationships #(merge-map-keys "context" % (:context-relationships overlay)))
        (update :interface-implementers #(merge-with (fn [a b]
                                                       (into (set (if (coll? a) a [a]))
                                                             (set (if (coll? b) b [b]))))
                                                     (or % {})
                                                     (or (:interface-implementers overlay) {})))
        (update :imported-interfaces #(into (or % #{}) (or (:imported-interfaces overlay) #{})))
        (update :public-interfaces #(into (or % #{}) (or (:public-interfaces overlay) #{})))
        (assoc :imported-methods (merge (:imported-methods base)
                                        (:imported-methods overlay))
               :imported-handles (into (or (:imported-handles base) #{})
                                       (or (:imported-handles overlay) #{}))
               :import-aliases (merge (:import-aliases base)
                                      (:import-aliases overlay))))))

(defn merge-methods-irs
  "Append imported methods. Fail on duplicate [class method]."
  [base imported]
  (let [clash (seq (set/intersection (method-keys base) (method-keys imported)))]
    (when clash
      (throw (ex-info (str "Import clash: duplicate method "
                           (str/join "::" (first clash)))
                      {:method (first clash)})))
    (into (vec base) imported)))

(defn merge-cargo-stashes
  "Merge import cargo stash (base) with page cargo stash (overlay)."
  [import-cargo page-cargo]
  (let [page-stash (:stash page-cargo)
        import-stash (:stash import-cargo)
        merged-schema (merge-schema-irs (:schema-ir import-stash)
                                        (:schema-ir page-stash))
        merged-methods (merge-methods-irs (or (:methods-ir import-stash) [])
                                          (or (:methods-ir page-stash) []))]
    (assoc page-cargo
           :stash (assoc page-stash
                         :schema-ir merged-schema
                         :methods-ir merged-methods))))

(defn- assert-importable-page!
  [page-name codeblocks]
  (let [kind (:page-kind codeblocks)]
    (when (= kind :documentation)
      (throw (ex-info (str "Cannot import documentation page '" page-name "'")
                      {:page page-name})))
    (when (seq (str/trim (:import codeblocks)))
      (throw (ex-info (str "Nested Import in '" page-name "' is not supported")
                      {:page page-name})))))

(defn- load-import-page!
  [name resolve-page]
  (when-not (mainfile/valid-page-name? name)
    (throw (ex-info (str "Invalid page name '" name "'") {:page name})))
  (when-not (resolve-page name)
    (throw (ex-info (str "Import page not found: '" name "'") {:page name})))
  (let [parsed (mainfile/parse-mainfile (resolve-page name)
                                       {:resolve-page resolve-page})]
    (when (p/failed? parsed)
      (throw (ex-info (str "Import page '" name "' parse error: "
                           (first (:errors parsed)))
                      {:page name})))
    (let [codeblocks (:value parsed)]
      (assert-importable-page! name codeblocks)
      {:name name
       :imports (mainfile/parse-import-names (:import codeblocks))})))

(defn resolve-import-order
  "Return ordered list of import page names (transitive, depth-first)."
  [import-names resolve-page]
  (letfn [(visit [name visited stack acc]
            (cond
              (contains? stack name)
              (throw (ex-info (str "Import cycle involving '" name "'") {:page name}))

              (contains? visited name)
              acc

              :else
              (let [{:keys [imports]} (load-import-page! name resolve-page)
                    visited' (conj visited name)
                    acc' (reduce (fn [a imp]
                                   (visit imp visited' (conj stack name) a))
                                 acc
                                 imports)]
                (if (some #{name} acc')
                  acc'
                  (conj acc' name)))))]
    (reduce (fn [acc name] (visit name #{} #{} acc)) [] import-names)))

(defn sibling-resolve
  "Build a :resolve-page fn for sibling .wcn files in dir-path (JVM)."
  [dir-path]
  #?(:clj
     (fn [page-name]
       (let [file (str dir-path "/" page-name ".wcn")]
         (when (.exists (java.io.File. file))
           (slurp file))))
     :cljs
     (fn [_] nil)))
