(ns wchnt-lang.importing
  "Public static methods, imported assemblage handles, and the import membrane."
  (:require [wchnt-lang.grammars :as grammars]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.reaction :as reaction]))

(defn- method-key [{:keys [class method]}] [class method])

(defn- method-entry? [entry] (and (:method entry) (not (:type entry))))

(defn- interface-names [schema-ir]
  (set (map :name (:interfaces schema-ir))))

(defn- find-method [methods-ir class method]
  (first (filter #(and (= class (:class %))
                       (= method (:method-name %))
                       (not (:interface-signature? %)))
                 methods-ir)))

(defn- public-ast-entries [public-text]
  (let [parsed (grammars/parse-public-with-failure-handling public-text)]
    (when-not (:success parsed)
      (throw (ex-info (str "In Public, " (:error parsed)) {})))
    (reduce (fn [acc node]
              (let [entry (if (= :PublicEntry (first node))
                            (second node)
                            node)]
                (case (first entry)
                  :PublicMethodDefinition
                  (update acc :methods conj {:method (second (second entry))
                                             :ast entry})
                  :PublicInterface
                  (update acc :interfaces conj (second entry))
                  acc)))
            {:methods [] :interfaces []}
            (filter vector? (rest (:ast parsed))))))

(defn public-method-ast
  "Turn an unqualified Public method into the ordinary method AST owned by root-class."
  [root-class {:keys [ast]}]
  (let [[_ method-name & body] ast]
    (into [:MethodDefinition [:ClassName root-class] method-name] body)))

(defn public-entries [public-text]
  (public-ast-entries public-text))

(defn public-methods-ast [root-class public-text]
  (let [{:keys [methods]} (public-ast-entries public-text)]
    (into [:Code]
          (map #(public-method-ast root-class %) methods))))

(defn- public-entry-key [entry]
  (if (method-entry? entry)
    [:method (method-key entry)]
    [:type (:type entry)]))

(defn apply-public
  "Validate Public and record its explicit static methods and interfaces."
  [schema-ir methods-ir public-text]
  (let [{:keys [methods interfaces]} (public-ast-entries public-text)
        root (or (:root-class schema-ir)
                 (when (= 1 (count (:assemblages schema-ir)))
                   (:name (first (:assemblages schema-ir)))))
        method-entries (mapv #(assoc % :class root) methods)
        entries (into method-entries (map #(hash-map :type %) interfaces))
        keys (mapv public-entry-key entries)]
    (when (not= (count keys) (count (set keys)))
      (throw (ex-info "Duplicate Public entry" {:entries keys})))
    (doseq [{:keys [class method]} method-entries]
      (when-not (find-method methods-ir class method)
        (throw (ex-info (str "Public method unknown: " method)
                        {:class-name class :method-name method}))))
    (doseq [type interfaces]
      (when-not (contains? (interface-names schema-ir) type)
        (throw (ex-info (str "Public name '" type "' is not an interface")
                        {:type-name type}))))
    (assoc schema-ir
           :public-methods (set (map method-key method-entries))
           :static-public-methods (set (map method-key method-entries))
           :public-interfaces (set interfaces))))

(defn assert-importable-public! [page-name cargo]
  (let [schema-ir (get-in cargo [:stash :schema-ir])]
    (when (and (empty? (or (:public-methods schema-ir) #{}))
               (empty? (or (:public-interfaces schema-ir) #{})))
      (throw (ex-info (str "Cannot import '" page-name
                           "': assemblage has no ## Public section")
                      {:page page-name})))
    (when-not (get-in cargo [:stash :construction-ir])
      (throw (ex-info (str "Cannot import '" page-name
                           "': imported pages must have Construction")
                      {:page page-name})))))

(defn- all-handle-methods [cargo root-class]
  (into {}
        (keep (fn [m]
                (when (and (= root-class (:class m))
                           (not (:interface-signature? m)))
                  [[root-class (:method-name m)] m])))
        (or (get-in cargo [:stash :methods-ir]) [])))

(defn env-from-import-cargos [specs cargos-by-page]
  (let [aliases
        (into {}
              (map (fn [{:keys [page alias]}]
                     (let [cargo (get cargos-by-page page)
                           schema-ir (get-in cargo [:stash :schema-ir])
                           root-class (get-in cargo [:stash :construction-ir :root-class])]
                       [alias {:page page
                               :root-class root-class
                               :assemblage-class (str root-class "Assemblage")
                               :public-methods (or (:public-methods schema-ir) #{})
                               :static-public-methods (or (:static-public-methods schema-ir) #{})
                               :public-interfaces (or (:public-interfaces schema-ir) #{})
                               :methods-ir (get-in cargo [:stash :methods-ir])
                               :schema-ir (get-in cargo [:stash :schema-ir])
                               :construction-ir (get-in cargo [:stash :construction-ir])
                               :factory-params (get-in cargo [:stash :construction-ir :factory-params])
                               :handle-methods (all-handle-methods cargo root-class)}])))
              specs)
        iface-names (into #{} (mapcat :public-interfaces (vals aliases)))
        handles (into #{} (keep :root-class (vals aliases)))
        imported-assemblages (vec (mapcat (fn [{:keys [page]}]
                                            (get-in (get cargos-by-page page)
                                                    [:stash :schema-ir :assemblages]))
                                          specs))
        imported-methods (into {}
                              (mapcat (fn [{:keys [methods-ir]}]
                                        (keep (fn [m]
                                                (when-not (:interface-signature? m)
                                                  [[(:class m) (:method-name m)] m]))
                                              (or methods-ir [])))
                                      (vals aliases)))
        static-public (into #{} (mapcat :static-public-methods (vals aliases)))]
    {:import-aliases aliases
     :imported-handles handles
     :imported-assemblages imported-assemblages
     :imported-interfaces iface-names
     :imported-methods imported-methods
     :public-methods (into #{} (mapcat :public-methods (vals aliases)))
     :public-interfaces iface-names
     :static-public-methods static-public}))

(defn- register-local-implements [schema-ir imported-ifaces]
  (reduce (fn [ir assemblage]
            (if-let [iface (:implements assemblage)]
              (do
                (when-not (contains? imported-ifaces iface)
                  (throw (ex-info (str (:name assemblage) " : " iface
                                       " — '" iface "' is not a published imported interface")
                                  {:class-name (:name assemblage) :interface iface})))
                (update-in ir [:interface-implementers iface]
                           (fnil conj #{}) (:name assemblage)))
              ir))
          schema-ir
          (:assemblages schema-ir)))

(defn attach-import-env [schema-ir env]
  (let [handles (:imported-handles env)
        imported-ifaces (or (:imported-interfaces env) #{})
        local (set (map :name (:assemblages schema-ir)))]
    (doseq [assemblage (:assemblages schema-ir)
            component (:components assemblage)
            :let [type-name (:type-name component)
                  rel (:relationship component)]]
      (when (contains? handles type-name)
        (when (contains? local type-name)
          (throw (ex-info (str "Class '" type-name
                               "' is both local and an imported handle")
                          {:type-name type-name})))
        (when (not= :external rel)
          (throw (ex-info (str "'" type-name "' is an imported handle; declare it as @"
                               type-name)
                          {:type-name type-name
                           :class-name (:name assemblage)})))))
    (-> schema-ir
        (assoc :import-aliases (:import-aliases env)
               :imported-handles handles
               :imported-assemblages (or (:imported-assemblages env) [])
               :imported-interfaces imported-ifaces
               :imported-methods (:imported-methods env)
               :public-methods (into (or (:public-methods schema-ir) #{})
                                     (:public-methods env))
               :public-interfaces (into (or (:public-interfaces schema-ir) #{})
                                        (or (:public-interfaces env) #{}))
               :static-public-methods (into (or (:static-public-methods schema-ir) #{})
                                            (:static-public-methods env)))
        (register-local-implements imported-ifaces)
        (update :external-types
                (fn [ext]
                  (into #{} (remove handles) (or ext #{})))))))
