(ns wchnt-lang.js-view
  "Property/method view over interpreter objects so Target JS can
   write assemblage.step() and ball.x."
  (:require [clojure.string :as str]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.ir :as ir]))

(defn view?
  [x]
  (boolean (and (map? x) (:wchnt/view x))))

(defn unwrap
  [x]
  (if (view? x) (:value x) x))

(defn wrap
  "Wrap an assemblage map. Primitives pass through."
  [ctx obj]
  (if (and (map? obj) (:wchnt/class obj))
    {:wchnt/view true :ctx ctx :value obj}
    obj))

(defn- field-names
  [ctx obj]
  (let [class-name (:wchnt/class obj)
        schema-ir (:schema-ir ctx)
        from-schema (when (and schema-ir class-name)
                      (mapv :component-name
                            (ir/get-assemblage-components schema-ir class-name)))]
    (if (seq from-schema)
      from-schema
      (->> (keys (or (some-> obj :wchnt/cell deref) obj))
           (remove #{:wchnt/class :wchnt/cell :wchnt/subscribers})
           (map name)
           vec))))

(declare describe from-js)

(defn- describe-object
  [ctx obj]
  (let [fields (field-names ctx obj)
        vals (map #(describe (wrap ctx (interpret/get-field (:schema-ir ctx) obj %)))
                  fields)]
    (str "[:" (:wchnt/class obj)
         (when (seq vals) (str " " (str/join " " vals)))
         "]")))

(defn- describe-map
  [m]
  (str "{"
       (str/join " "
                 (map (fn [[k v]]
                        (str (describe k) ":" (describe v)))
                      m))
       "}"))

(defn describe
  "Printable form for Target JS String() / println. Construction-like, not pr-str."
  [x]
  (let [raw (unwrap x)
        ctx (when (view? x) (:ctx x))]
    (cond
      (nil? raw) "null"
      (boolean? raw) (if raw "true" "false")
      (number? raw) (str raw)
      (string? raw) (str "\"" raw "\"")
      (vector? raw) (str "[" (str/join " " (map #(describe (if ctx (wrap ctx %) %)) raw)) "]")
      (and (map? raw) (:wchnt/host raw)) (str "@" (:wchnt/host raw))
      (and (map? raw) (:wchnt/class raw)) (describe-object (or ctx {}) raw)
      (map? raw) (describe-map raw)
      :else (str raw))))

(defn- method-on?
  [ctx obj method-name]
  (let [class-name (:wchnt/class obj)
        methods (:methods-ir ctx)
        has? (fn [c m]
               (boolean (some #(and (= c (:class %))
                                    (= m (:method-name %))
                                    (not (:interface-signature? %)))
                              methods)))]
    (or (has? class-name method-name)
        (some? (ir/find-delegated-method-class
                (:schema-ir ctx) class-name method-name has?)))))

(defn- host-method
  [obj prop]
  (get-in obj [:methods prop]))

(defn- mailbox-inject?
  [ctx obj prop]
  (and (= "inject" prop)
       (:wchnt/class obj)
       (ir/mailbox-class? (:schema-ir ctx) (:wchnt/class obj))))

(defn js-call
  "Call a WCHNT method, inject, or host function. args are view-or-primitive."
  [recv method-name args]
  (let [raw (unwrap recv)
        ctx (:ctx recv)]
    (cond
      (host-method raw method-name)
      (apply (host-method raw method-name) (map unwrap args))

      (and (view? recv) (mailbox-inject? ctx raw method-name))
      (wrap ctx
            (interpret/inject (:schema-ir ctx) (:methods-ir ctx) raw
                              (mapv from-js args)))

      (view? recv)
      (wrap ctx
            (interpret/call (:schema-ir ctx) (:methods-ir ctx) raw
                            method-name (mapv unwrap args)))

      :else
      (throw (ex-info (str "Cannot call '" method-name "' on a non-object")
                      {:method method-name :value recv})))))

(defn js-get
  "JS property read. Methods become zero-or-more-arg functions."
  [recv prop]
  (let [raw (unwrap recv)
        ctx (:ctx recv)]
    (cond
      (and (view? recv) (or (method-on? ctx raw prop)
                            (mailbox-inject? ctx raw prop)))
      (fn [& args] (js-call recv prop (vec args)))

      (host-method raw prop)
      (fn [& args] (js-call recv prop (vec args)))

      (and (map? raw) (contains? raw prop))
      (let [v (get raw prop)]
        (if (fn? v)
          (fn [& args] (apply v args))
          v))

      (view? recv)
      (wrap ctx (interpret/get-field (:schema-ir ctx) raw prop))

      :else
      (throw (ex-info (str "Cannot read '" prop "' of a non-object")
                      {:field prop :value raw})))))

#?(:cljs
   (def ^:private silent-js-props
     #{"then" "toJSON" "inspect" "constructor"}))

#?(:cljs
   (defn- export-call
     [recv v]
     (fn [& args]
       (let [out (apply v args)
             raw (unwrap out)
             ctx (:ctx recv)]
         (cond
           (vector? raw) (vector->js-array ctx raw)
           (and (map? raw) (not (:wchnt/class raw)))
           (as-js {:wchnt/view true :ctx ctx :value raw})
           :else (as-js (if (view? out) out (wrap ctx out))))))))

#?(:cljs
   (declare as-js vector->js-array))

#?(:cljs
   (defn- vector->js-array
     "Expose WCHNT vectors as real JS arrays so Target can for..of and use .length."
     [ctx v]
     (let [a (js/Array. (count v))]
       (doseq [[i item] (map vector (range) v)]
         (aset a i (as-js (if ctx (wrap ctx item) item))))
       a)))

#?(:cljs
   (defonce ^:private proxy->view (js/WeakMap.)))

(defn from-js
  "Recover an interpreter value passed back from Target JS."
  [x]
  #?(:cljs
     (cond
       (or (nil? x) (number? x) (string? x) (boolean? x))
       x

       ;; Target APIs expose WCHNT arrays as native JS arrays. Convert them
       ;; back before storing them in the interpreter heap; otherwise a value
       ;; such as WCHNTInput.keyPresses() is neither a Clojure vector nor a
       ;; WCHNT object, so Methods such as `fold` are looked up as `::fold`.
       (instance? js/Array x)
       (mapv from-js (array-seq x))

       :else
       (if-let [v (.get proxy->view x)]
         (unwrap v)
         (unwrap x)))
     :clj (unwrap x)))

#?(:cljs
   (defn as-js
     "Browser Proxy so real Target JS can do assemblage.step() and ball.x."
     [x]
     (cond
       (nil? x) nil
       (or (number? x) (string? x) (boolean? x)) x
       (fn? x) x
       (vector? x) (vector->js-array nil x)
       (view? x)
       (let [p (js/Proxy. #js {}
                          #js {:get (fn [_obj prop]
                                      (cond
                                        (= prop (.-toPrimitive js/Symbol))
                                        (fn [_] (describe x))

                                        (= prop (.-toStringTag js/Symbol))
                                        "WCHNT"

                                        (not (string? prop))
                                        js/undefined

                                        (= prop "__wchntClass")
                                        (:wchnt/class (unwrap x))

                                        (= prop "toConstruction")
                                        (fn [& _] (describe x))

                                        (or (= prop "toString") (= prop "valueOf"))
                                        (fn [] (describe x))

                                        (contains? silent-js-props prop)
                                        js/undefined

                                        :else
                                        (let [v (js-get x prop)]
                                          (if (fn? v)
                                            (export-call x v)
                                            (let [raw (unwrap v)]
                                              (if (vector? raw)
                                                (vector->js-array (:ctx x) raw)
                                                (as-js v)))))))})]
         (.set proxy->view p x)
         p)
       :else x)))
