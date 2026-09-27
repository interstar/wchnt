(ns wchnt-lang.targets.platform
  "Host constructors for platform-constructible types (Target CONSTRUCT).

   Declaration in %requires is still the whitelist; this namespace only supplies
   runtime implementations the interpreter knows how to birth. Live cljs uses
   the real JS Date; the JVM stub mirrors getFullYear for tests."
  (:require [wchnt-lang.ir :as ir]))

(defn- date-from-parts
  [y m d h mi s]
  #?(:cljs (js/Date. y m d h mi s)
     :clj {:year y :month m :day d :hour h :min mi :sec s}))

(defn- date-full-year
  [d]
  #?(:cljs (.getFullYear d)
     :clj (:year d)))

(defn date-construct
  "Build a Date from six Ints: year, month (0-based), day, hour, min, sec.
   Matches JS `new Date(y, m, d, h, mi, s)` and Haxe Date's full arity."
  [args]
  (when-not (= 6 (count args))
    (throw (ex-info "Date::CONSTRUCT expected 6 Int arguments (y m d h min sec)"
                    {:got (count args)})))
  (let [[y m d h mi s] args
        host (date-from-parts y m d h mi s)]
    {:wchnt/host "Date"
     :wchnt/platform-constructible? true
     :wchnt/js host
     :methods {"getFullYear" (fn [] (date-full-year host))}}))

(def constructors
  "Type name → (fn [args-vector] host-value)."
  {"Date" date-construct})

(defn attach-constructors
  "Install interpreter constructors for every Schema % type we know how to birth."
  [schema-ir]
  (let [needed (ir/get-platform-constructible-types schema-ir)
        selected (select-keys constructors needed)]
    (if (seq selected)
      (update schema-ir :host-constructors #(merge selected (or % {})))
      schema-ir)))
