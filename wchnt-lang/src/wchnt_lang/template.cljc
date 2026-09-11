(ns wchnt-lang.template
  "Named {hole} substitution for String::tpl. Shared by Methods checking and the interpreter."
  (:require [clojure.string :as str]))

(defn- hole-name?
  [name]
  (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*" name)))

(defn- fail
  [msg]
  (throw (ex-info (str "String::tpl: " msg) {:msg msg})))

(defn- close-index
  [s start]
  (or (str/index-of s "}" start)
      (fail "unmatched '{'")))

(defn- read-hole
  [s i]
  (let [j (close-index s (inc i))
        name (subs s (inc i) j)]
    (when-not (hole-name? name)
      (fail (str "bad hole '{" name "}'")))
    {:name name :next (inc j)}))

(defn holes
  "Hole names in order. Fail-fast on unmatched or illegal braces."
  [s]
  (loop [i 0 acc []]
    (if (>= i (count s))
      acc
      (let [c (subs s i (inc i))]
        (cond
          (= c "{")
          (let [{:keys [name next]} (read-hole s i)]
            (recur next (conj acc name)))

          (= c "}")
          (fail "unmatched '}'")

          :else
          (recur (inc i) acc))))))

(defn expand
  "Replace {name} holes from a string-keyed map. Missing names fail. Extra keys ignored."
  [s vars]
  (loop [i 0 out ""]
    (if (>= i (count s))
      out
      (let [c (subs s i (inc i))]
        (cond
          (= c "{")
          (let [{:keys [name next]} (read-hole s i)]
            (when-not (contains? vars name)
              (fail (str "missing '" name "'")))
            (recur next (str out (get vars name))))

          (= c "}")
          (fail "unmatched '}'")

          :else
          (recur (inc i) (str out c)))))))
