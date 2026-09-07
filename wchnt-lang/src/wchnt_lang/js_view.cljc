(ns wchnt-lang.js-view
  "Property/method view over interpreter objects so Target JS can
   write assemblage.step() and ball.x."
  (:require [wchnt-lang.interpret :as interpret]
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

(defn- method-on?
  [ctx obj method-name]
  (let [class-name (:wchnt/class obj)]
    (boolean (some #(and (= class-name (:class %))
                         (= method-name (:method-name %))
                         (not (:interface-signature? %)))
                   (:methods-ir ctx)))))

(defn- host-method
  [obj prop]
  (when (and (map? obj) (= :graphics (:wchnt/host obj)))
    (get-in obj [:methods prop])))

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
                              (mapv unwrap args)))

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

      (view? recv)
      (wrap ctx (interpret/get-field raw prop))

      :else
      (throw (ex-info (str "Cannot read '" prop "' of a non-object")
                      {:field prop :value raw})))))

#?(:cljs
   (def ^:private silent-js-props
     #{"then" "toJSON" "toString" "valueOf" "inspect" "constructor"}))

#?(:cljs
   (declare as-js))

#?(:cljs
   (defn- vector->js-array
     "Expose WCHNT vectors as real JS arrays so Target can for..of and use .length."
     [ctx v]
     (let [a (js/Array. (count v))]
       (doseq [[i item] (map vector (range) v)]
         (aset a i (as-js (wrap ctx item))))
       a)))

#?(:cljs
   (defn as-js
     "Browser Proxy so real Target JS can do assemblage.step() and ball.x."
     [x]
     (cond
       (nil? x) nil
       (or (number? x) (string? x) (boolean? x)) x
       (fn? x) x
       (vector? x) (let [a (js/Array. (count x))]
                     (doseq [[i item] (map vector (range) x)]
                       (aset a i (as-js item)))
                     a)
       (view? x)
       (js/Proxy. #js {}
                  #js {:get (fn [_obj prop]
                              (if-not (string? prop)
                                js/undefined
                                (if (contains? silent-js-props prop)
                                  js/undefined
                                  (let [v (js-get x prop)]
                                    (if (fn? v)
                                      (fn [& args]
                                        (as-js (apply v args)))
                                      (let [raw (unwrap v)]
                                        (if (vector? raw)
                                          (vector->js-array (:ctx x) raw)
                                          (as-js v))))))))})
       :else x)))
