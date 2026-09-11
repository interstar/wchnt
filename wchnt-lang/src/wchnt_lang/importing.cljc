(ns wchnt-lang.importing
  "Public lists, import aliases, and the opaque-handle membrane."
  (:require [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.reaction :as reaction]))

(defn- method-key
  [{:keys [class method]}]
  [class method])

(defn- method-entry?
  [entry]
  (and (:class entry) (:method entry)))

(defn- find-method
  [methods-ir class method]
  (first (filter #(and (= class (:class %))
                       (= method (:method-name %))
                       (not (:interface-signature? %)))
                 methods-ir)))

(defn- interface-names
  [schema-ir]
  (set (map :name (:interfaces schema-ir))))

(defn- public-entry-key
  [entry]
  (if (method-entry? entry)
    [:method (method-key entry)]
    [:type (:type entry)]))

(defn- assert-public-entries!
  [schema-ir methods-ir entries]
  (doseq [entry entries]
    (if (method-entry? entry)
      (when-not (find-method methods-ir (:class entry) (:method entry))
        (throw (ex-info (str "Public names unknown method "
                             (:class entry) "::" (:method entry))
                        {:class-name (:class entry)
                         :method-name (:method entry)})))
      (when-not (contains? (interface-names schema-ir) (:type entry))
        (throw (ex-info (str "Public name '" (:type entry)
                             "' is not an interface")
                        {:type-name (:type entry)}))))))

(defn- interface-signatures
  [methods-ir interface-names]
  (filterv #(and (:interface-signature? %)
                 (contains? interface-names (:class %)))
           methods-ir))

(defn apply-public
  "Validate ## Public against methods IR. Assoc :public-methods,
   :static-public-methods, and :public-interfaces on schema-ir."
  [schema-ir methods-ir public-text]
  (let [entries (mainfile/parse-public-names public-text)
        keys (mapv public-entry-key entries)]
    (when (not= (count keys) (count (set keys)))
      (throw (ex-info "Duplicate Public entry" {:methods keys})))
    (assert-public-entries! schema-ir methods-ir entries)
    (let [method-entries (filterv method-entry? entries)
          public-set (set (map method-key method-entries))
          public-ifaces (set (keep :type entries))
          introducers (filterv (fn [{:keys [class method]}]
                                 (let [m (find-method methods-ir class method)]
                                   (not (reaction/expr-uses-instance? m))))
                               method-entries)]
      (when (and (seq entries) (empty? introducers))
        (throw (ex-info (str "Public has no introducer (a method that does not use this). "
                             "Add a factory such as Game::make")
                        {:public keys})))
      (assoc schema-ir
             :public-methods (into (or (:public-methods schema-ir) #{}) public-set)
             :public-interfaces (into (or (:public-interfaces schema-ir) #{}) public-ifaces)
             :static-public-methods (into (or (:static-public-methods schema-ir) #{})
                                          (set (map method-key introducers)))))))

(defn- cargo-public-set
  [cargo]
  (or (get-in cargo [:stash :schema-ir :public-methods] #{})))

(defn- cargo-public-interfaces
  [cargo]
  (or (get-in cargo [:stash :schema-ir :public-interfaces] #{})))

(defn assert-importable-public!
  [page-name cargo]
  (when (and (empty? (cargo-public-set cargo))
             (empty? (cargo-public-interfaces cargo)))
    (throw (ex-info (str "Cannot import '" page-name
                         "': assemblage has no ## Public section")
                    {:page page-name}))))

(defn- published-methods-from
  [schema-ir methods-ir]
  (let [public (or (:public-methods schema-ir) #{})
        ifaces (or (:public-interfaces schema-ir) #{})
        from-list (keep (fn [m]
                          (let [k [(:class m) (:method-name m)]]
                            (when (contains? public k)
                              [k m])))
                        methods-ir)
        from-ifaces (map (fn [m] [[(:class m) (:method-name m)] m])
                         (interface-signatures methods-ir ifaces))]
    (into {} (concat from-list from-ifaces))))

(defn env-from-import-cargos
  "Build the importer's view of compiled siblings."
  [specs cargos-by-page]
  (let [aliases (into {}
                      (map (fn [{:keys [page alias]}]
                             (let [cargo (get cargos-by-page page)
                                   schema-ir (get-in cargo [:stash :schema-ir])
                                   methods-ir (or (get-in cargo [:stash :methods-ir]) [])]
                               [alias {:page page
                                       :public-methods (or (:public-methods schema-ir) #{})
                                       :public-interfaces (or (:public-interfaces schema-ir) #{})
                                       :methods-ir methods-ir}])))
                      specs)
        iface-names (into #{} (mapcat :public-interfaces (vals aliases)))
        handles (into #{} (comp (mapcat :public-methods)
                                (map first)
                                (remove iface-names))
                      (vals aliases))
        imported-methods (into {} (mapcat (fn [{:keys [methods-ir] :as info}]
                                            (let [schema {:public-methods (:public-methods info)
                                                          :public-interfaces (:public-interfaces info)}]
                                              (published-methods-from schema methods-ir)))
                                          (vals aliases)))
        public-methods (into #{} (mapcat :public-methods (vals aliases)))
        static-public (into #{} (mapcat #(get-in cargos-by-page
                                                 [(:page %) :stash :schema-ir
                                                  :static-public-methods]
                                                 #{})
                                        specs))]
    {:import-aliases aliases
     :imported-handles handles
     :imported-interfaces iface-names
     :imported-methods imported-methods
     :public-methods public-methods
     :public-interfaces iface-names
     :static-public-methods static-public}))

(defn- register-local-implements
  [schema-ir imported-ifaces]
  (reduce (fn [ir assemblage]
            (if-let [iface (:implements assemblage)]
              (do
                (when-not (contains? imported-ifaces iface)
                  (throw (ex-info (str (:name assemblage) " : " iface
                                       " — '" iface "' is not a published imported interface")
                                  {:class-name (:name assemblage)
                                   :interface iface})))
                (update-in ir [:interface-implementers iface]
                           (fnil conj #{}) (:name assemblage)))
              ir))
          schema-ir
          (:assemblages schema-ir)))

(defn attach-import-env
  "Mark handle types on the importer's schema. Fail if a handle is used without @."
  [schema-ir env]
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
