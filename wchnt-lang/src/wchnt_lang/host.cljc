(ns wchnt-lang.host
  "Compiler-owned host types (WCHNTMaths). Query methods have return
   types; types not in the table stay fluent (return the receiver).")

(def ^:private maths-specs
  {"rand" {:arity 0 :return "Float"}
   "pi" {:arity 0 :return "Float"}
   "randInt" {:arity 1 :return "Int" :arg-types ["Int"]}
   "sin" {:arity 1 :return "Float" :arg-types ["Float"]}
   "cos" {:arity 1 :return "Float" :arg-types ["Float"]}
   "tan" {:arity 1 :return "Float" :arg-types ["Float"]}
   "asin" {:arity 1 :return "Float" :arg-types ["Float"]}
   "acos" {:arity 1 :return "Float" :arg-types ["Float"]}
   "atan" {:arity 1 :return "Float" :arg-types ["Float"]}
   "abs" {:arity 1 :return "Float" :arg-types ["Float"]}
   "floor" {:arity 1 :return "Int" :arg-types ["Float"]}
   "ceil" {:arity 1 :return "Int" :arg-types ["Float"]}
   "round" {:arity 1 :return "Int" :arg-types ["Float"]}
   "sqrt" {:arity 1 :return "Float" :arg-types ["Float"]}
   "log" {:arity 1 :return "Float" :arg-types ["Float"]}
   "exp" {:arity 1 :return "Float" :arg-types ["Float"]}
   "pow" {:arity 2 :return "Float" :arg-types ["Float" "Float"]}
   "min" {:arity 2 :return "Float" :arg-types ["Float" "Float"]}
   "max" {:arity 2 :return "Float" :arg-types ["Float" "Float"]}
   "atan2" {:arity 2 :return "Float" :arg-types ["Float" "Float"]}
   "hsv" {:arity 3 :return "Int" :arg-types ["Float" "Float" "Float"]}})

(def host-api
  {"WCHNTMaths" maths-specs})

(defn known-host?
  [class-name]
  (contains? host-api class-name))

(defn lookup
  "Return {:arity :return :arg-types} or throw if this host type is known
   but the method or arity is wrong. Nil when class-name is not a host table type."
  [class-name method arity]
  (when-let [methods (get host-api class-name)]
    (if-let [spec (get methods method)]
      (if (= arity (:arity spec))
        spec
        (throw (ex-info (str class-name "::" method " expected "
                             (:arity spec) " argument(s), got " arity)
                        {:class-name class-name :method method
                         :expected (:arity spec) :got arity})))
      (throw (ex-info (str "Unknown method '" method "' on " class-name)
                      {:class-name class-name :method method})))))

(defn query?
  [class-name method]
  (when-let [spec (get-in host-api [class-name method])]
    (not= "Void" (:return spec))))

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
   {:wchnt/host :maths
    :methods (merge (default-maths-methods) overrides)}))

(defn invoke
  "Call a method on a :maths host object."
  [maths method args]
  (let [f (get-in maths [:methods method])]
    (when-not f
      (throw (ex-info (str "Unknown method '" method "' on WCHNTMaths")
                      {:method method})))
    (apply f args)))
