(ns wchnt-lang.targets.interpreter-std
  "Runtime support for standard-library host objects. Signatures and fluent
   behaviour come from stdlib-signatures.cljc; implementations remain here
   only for the host objects that the interpreter actually executes."
  (:require [wchnt-lang.targets.stdlib-signatures :as stdlib]))

(defn- one-method-spec
  [spec]
  (assoc spec
         :arity (count (:args spec))
         :arg-types (:args spec)))

(defn- method-spec
  [[method-name {:keys [overloads] :as spec}]]
  [method-name
   (if overloads
     (assoc spec :overloads (mapv one-method-spec overloads))
     (one-method-spec spec))])

(defn- arity-spec
  [spec arity]
  (if-let [overloads (:overloads spec)]
    (some #(when (= arity (:arity %)) %) overloads)
    (when (= arity (:arity spec)) spec)))

(def host-api
  (into {}
        (map (fn [[class-name methods]]
               [class-name (into {} (map method-spec methods))])
             stdlib/signatures)))

(defn known-host?
  [class-name]
  (contains? host-api class-name))

(defn lookup
  "Return {:arity :return :arg-types} or throw if this host type is known
   but the method or arity is wrong. Nil when class-name is not a host table type."
  [class-name method arity]
  (when-let [methods (get host-api class-name)]
    (if-let [declared (get methods method)]
      (if-let [spec (arity-spec declared arity)]
        spec
        (throw (ex-info (str class-name "::" method " expected "
                             (if (:overloads declared)
                               (sort (map :arity (:overloads declared)))
                               (:arity declared))
                             " argument(s), got " arity)
                        {:class-name class-name :method method
                         :expected (if (:overloads declared)
                                     (sort (map :arity (:overloads declared)))
                                     (:arity declared))
                         :got arity})))
      (throw (ex-info (str "Unknown method '" method "' on " class-name)
                      {:class-name class-name :method method})))))

(defn query?
  [class-name method]
  (when-let [spec (get-in host-api [class-name method])]
    (not (:fluent spec))))

(defn query-spec
  "Return the declared specification for a value-returning host query."
  [class-name method arity]
  (when-let [declared (get-in host-api [class-name method])]
    (if-let [spec (arity-spec declared arity)]
      spec
      (throw (ex-info (str class-name "::" method " expected "
                           (if (:overloads declared)
                             (sort (map :arity (:overloads declared)))
                             (:arity declared))
                           " argument(s), got " arity)
                      {:class-name class-name :method method
                       :expected (if (:overloads declared)
                                   (sort (map :arity (:overloads declared)))
                                   (:arity declared))
                       :got arity})))))

(defn- d
  [x]
  (double x))

(defn- rand-int-n
  [n]
  (let [k (long n)]
    (when-not (pos? k)
      (throw (ex-info "WCHNTMaths.randInt requires n > 0" {:n n})))
    #?(:clj (int (* (Math/random) k))
       :cljs (js/Math.floor (* (js/Math.random) k)))))

(defn- clamp255
  [x]
  (let [n (int (Math/floor (* (d x) 255.0)))]
    (cond
      (< n 0) 0
      (> n 255) 255
      :else n)))

(defn- hsv-pack
  "Pack HSV into 0xRRGGBB. v may be > 1; channels clamp."
  [h s v]
  (let [hh (let [x (mod (d h) 1.0)]
             (if (neg? x) (+ x 1.0) x))
        i (int (Math/floor (* hh 6.0)))
        f (- (* hh 6.0) i)
        vv (d v)
        ss (d s)
        p (* vv (- 1.0 ss))
        q (* vv (- 1.0 (* f ss)))
        t (* vv (- 1.0 (* (- 1.0 f) ss)))
        [r g b] (case (mod i 6)
                  0 [vv t p]
                  1 [q vv p]
                  2 [p vv t]
                  3 [p q vv]
                  4 [t p vv]
                  [vv p q])]
    (bit-or (bit-shift-left (clamp255 r) 16)
            (bit-shift-left (clamp255 g) 8)
            (clamp255 b))))

(defn- default-maths-methods
  []
  {"rand" (fn [] #?(:clj (Math/random) :cljs (js/Math.random)))
   "pi" (fn [] #?(:clj Math/PI :cljs js/Math.PI))
   "randInt" rand-int-n
   "hsv" hsv-pack
   "sin" (fn [x] #?(:clj (Math/sin (d x)) :cljs (js/Math.sin x)))
   "cos" (fn [x] #?(:clj (Math/cos (d x)) :cljs (js/Math.cos x)))
   "tan" (fn [x] #?(:clj (Math/tan (d x)) :cljs (js/Math.tan x)))
   "asin" (fn [x] #?(:clj (Math/asin (d x)) :cljs (js/Math.asin x)))
   "acos" (fn [x] #?(:clj (Math/acos (d x)) :cljs (js/Math.acos x)))
   "atan" (fn [x] #?(:clj (Math/atan (d x)) :cljs (js/Math.atan x)))
   "abs" (fn [x] #?(:clj (Math/abs (d x)) :cljs (js/Math.abs x)))
   "floor" (fn [x] #?(:clj (int (Math/floor (d x))) :cljs (js/Math.floor x)))
   "ceil" (fn [x] #?(:clj (int (Math/ceil (d x))) :cljs (js/Math.ceil x)))
   "round" (fn [x] #?(:clj (int (Math/round (d x))) :cljs (js/Math.round x)))
   "sqrt" (fn [x] #?(:clj (Math/sqrt (d x)) :cljs (js/Math.sqrt x)))
   "log" (fn [x] #?(:clj (Math/log (d x)) :cljs (js/Math.log x)))
   "exp" (fn [x] #?(:clj (Math/exp (d x)) :cljs (js/Math.exp x)))
   "pow" (fn [x y] #?(:clj (Math/pow (d x) (d y)) :cljs (js/Math.pow x y)))
   "min" (fn [x y] #?(:clj (Math/min (d x) (d y)) :cljs (js/Math.min x y)))
   "max" (fn [x y] #?(:clj (Math/max (d x) (d y)) :cljs (js/Math.max x y)))
   "atan2" (fn [y x] #?(:clj (Math/atan2 (d y) (d x)) :cljs (js/Math.atan2 y x)))})

(defn make-maths
  "Interpreter host object. overrides is an optional map of method name → fn."
  ([]
   (make-maths nil))
  ([overrides]
   {:wchnt/host "WCHNTMaths"
    :methods (merge (default-maths-methods) overrides)}))

(defn invoke
  "Call a method on a :maths host object."
  [maths method args]
  (let [f (get-in maths [:methods method])]
    (when-not f
      (throw (ex-info (str "Unknown method '" method "' on WCHNTMaths")
                      {:method method})))
    (apply f args)))
