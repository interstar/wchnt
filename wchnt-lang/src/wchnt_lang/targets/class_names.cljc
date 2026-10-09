(ns wchnt-lang.targets.class-names
  "Apply the optional Target prefix to generated source identifiers."
  (:require [clojure.string :as str]))

(def ^:private source-token-pattern
  #"(?s)(?:\"(?:\"\"|\\.|[^\"\\])*\"|'(?:''|\\.|[^'\\])*'|//[^\n]*|/\*.*?\*/|[A-Za-z_][A-Za-z0-9_]*|.)")

(def ^:private identifier-pattern
  #"[A-Za-z_][A-Za-z0-9_]*")

(defn- generated-class-names
  [cargo]
  (let [schema-ir (get-in cargo [:stash :schema-ir])
        construction-ir (get-in cargo [:stash :construction-ir])
        root-class (or (:root-class construction-ir) (:root-class schema-ir))
        imported-cargos (vals (get-in cargo [:stash :imported-cargos] {}))]
    (concat (map :name (:assemblages schema-ir))
            (map :name (:interfaces schema-ir))
            (map :name (:enums schema-ir))
            (when root-class [(str root-class "Assemblage")])
            (mapcat generated-class-names imported-cargos))))

(defn- class-name-map
  [cargo prefix]
  (let [names (vec (generated-class-names cargo))
        duplicates (->> names frequencies (keep (fn [[name n]]
                                                   (when (> n 1) name))) sort vec)]
    (when (seq duplicates)
      (throw (ex-info "Generated WCHNT class names collide before prefixing"
                      {:class-names duplicates})))
    (into {} (map (fn [name] [name (str prefix name)])) names)))

(defn- reserved-target-classes
  [target-ir]
  (let [shared #{"WCHNTConsole" "WCHNTMaths" "WCHNTRuntime"
                 "IWCHNTObject" "IWCHNTHelper" "WCHNTHelper"}
        host (:host target-ir)]
    (into shared
          (case host
            "smalltalk" #{"WCHNTGraphics" "WCHNTInput"}
            "openfl" #{"WCHNTGraphics" "WCHNTInput"}
            "testharness" #{"WCHNTUnitTests"}
            #{}))))

(defn- assert-no-name-collisions!
  [name-map cargo]
  (let [target-ir (get-in cargo [:stash :target-ir])
        reserved (into (reserved-target-classes target-ir)
                       (:external-types target-ir))
        collisions (sort (filter reserved (vals name-map)))]
    (when (seq collisions)
      (throw (ex-info "The Target class prefix collides with reserved or external classes"
                      {:class-names collisions
                       :prefix (:class-prefix target-ir)
                       :target (:host target-ir)})))))

(defn- haxe-type-argument?
  [tokens index]
  (let [prefix (->> (subvec tokens 0 index)
                    (remove str/blank?)
                    vec)
        [depth type-argument?]
        (let [[depth stack _]
              (reduce (fn [[depth stack previous] token]
                  (cond
                    (= token "<")
                    [(inc depth)
                     (conj stack (boolean
                                  (re-matches #"[A-Z][A-Za-z0-9_]*"
                                              (or previous ""))))
                     token]
                    (= token ">") [(max 0 (dec depth))
                                   (if (seq stack) (pop stack) stack)
                                   token]
                    :else [depth stack token]))
                           [0 [] nil]
                           prefix)]
          [depth stack])]
    (and (pos? depth)
         (peek type-argument?)
         (contains? #{"<" ","} (peek prefix)))))

(defn- significant-token
  [tokens index direction]
  (loop [index (+ index direction)]
    (when (<= 0 index (dec (count tokens)))
      (if (str/blank? (get tokens index))
        (recur (+ index direction))
        (get tokens index)))))

(defn- class-reference?
  [tokens index target]
  (let [previous (significant-token tokens index -1)
        next-token (significant-token tokens index 1)]
    (case (:host target)
      "smalltalk"
      (or (= previous "#")
          (= previous "!")
          (= next-token "class")
          (= next-token "new"))

      ;; Haxe targets share the same source emitter. Type positions and class
      ;; side references are distinct from ordinary field/local identifiers.
      (or (contains? #{"class" "interface" "enum" "extends" "implements"
                       "new" ":" "->"} previous)
          (and (= next-token ".") (str/ends-with? (get tokens index) "Assemblage"))
          (haxe-type-argument? tokens index)))))

(defn- replace-identifiers
  [source name-map target]
  (let [tokens (vec (re-seq source-token-pattern source))]
    (->> tokens
         (map-indexed (fn [index token]
                        (if (and (re-matches identifier-pattern token)
                                 (contains? name-map token)
                                 (class-reference? tokens index target))
                          (get name-map token)
                          token)))
         (apply str))))

(defn- replace-source-strings
  [value name-map target]
  (cond
    (string? value) (replace-identifiers value name-map target)
    (map? value) (into (empty value)
                       (map (fn [[key child]]
                              [key (replace-source-strings child name-map target)]))
                       value)
    (vector? value) (mapv #(replace-source-strings % name-map target) value)
    (list? value) (apply list (map #(replace-source-strings % name-map target) value))
    :else value))

(defn apply-prefix
  "Prefix generated WCHNT class identifiers in a backend source artifact.

   String literals and comments are left untouched. The interpreter IR is not
   changed; this runs only after a target backend has emitted its artifact."
  [artifact cargo prefix]
  (if (str/blank? prefix)
    artifact
    (let [name-map (class-name-map cargo prefix)
          target (get-in cargo [:stash :target-ir])]
      (assert-no-name-collisions! name-map cargo)
      (-> artifact
          (update :outputs replace-source-strings name-map target)
          (update :payload replace-source-strings name-map target)
          (update-in [:metadata :class-names]
                     (fn [names]
                       (when names
                         (mapv #(get name-map % %) names))))))))
