(ns wchnt-lang.targets.core
  "Parse the Target section: host, lifecycle Haxe, and the %trace binding."
  (:require [clojure.string :as str]
            [wchnt-lang.targets.requires :as requires]))

(def known-hosts
  #{"terminal" "cli" "cli-live" "openfl" "canvas" "form"})

(def host-help
  "%terminal, %cli, %cli-live, %openfl, %canvas, or %form")

(def frame-hosts
  "Hosts that use %init / %step instead of %main (frame loop or line loop)."
  #{"openfl" "canvas" "form" "cli" "cli-live"})

(def lifecycle-names
  #{"main" "init" "step"})

(defn- header-name
  [line]
  (second (re-matches #"^[ \t]*%([A-Za-z_][A-Za-z0-9_-]*)[ \t]*$" line)))

(defn- collect-blocks
  [lines]
  (let [start (drop-while #(str/blank? %) lines)]
    (when (and (seq start) (not (header-name (first start))))
      (throw (ex-info "Target must start with %name (e.g. %main or %trace)"
                      {:line (first start)})))
    (reduce (fn [blocks line]
              (if-let [name (header-name line)]
                (conj blocks {:name name :lines []})
                (if (empty? blocks)
                  blocks
                  (update-in blocks [(dec (count blocks)) :lines] conj line))))
            []
            start)))

(defn- haxe-fn-name
  [haxe]
  (when-let [match (re-find #"function\s+(\w+)" haxe)]
    (second match)))

(defn- haxe-has-fn?
  [haxe name]
  (boolean (re-find (re-pattern (str "function\\s+" name "\\b")) haxe)))

(defn- assert-unique-names
  [blocks]
  (let [names (mapv :name blocks)]
    (when (not= (count names) (count (set names)))
      (throw (ex-info "Duplicate %name in Target"
                      {:names names})))))

(defn- host-block?
  [block]
  (contains? known-hosts (:name block)))

(defn- extract-host
  [blocks]
  (let [hosts (filterv host-block? blocks)]
    (when (> (count hosts) 1)
      (throw (ex-info (str "Target may name only one host (" host-help ")")
                      {:names (mapv :name hosts)})))
    (if-let [host (first hosts)]
      (do
        (when-not (str/blank? (:haxe host))
          (throw (ex-info (str "%" (:name host) " names the host and must be empty")
                          {:name (:name host)})))
        (:name host))
      (throw (ex-info (str "Target must name a host (" host-help ")")
                      {:hint "Add an empty host line before lifecycle blocks, e.g. %terminal then %main"})))))

(defn- names-of
  [blocks]
  (set (map :name blocks)))

(defn- assert-terminal-lifecycle
  [names]
  (when (or (contains? names "init") (contains? names "step"))
    (throw (ex-info (str "%init and %step are for %openfl, %canvas, %cli and %cli-live, not %terminal")
                    {:names names})))
  (when-not (contains? names "main")
    (throw (ex-info "Target must define %main"
                    {:names names}))))

(defn- assert-frame-lifecycle
  [host names]
  (when-not (contains? names "init")
    (throw (ex-info (str "%" host " requires %init")
                    {:host host :names names})))
  (when-not (contains? names "step")
    (throw (ex-info (str "%" host " requires %step")
                    {:host host :names names})))
  (when (contains? names "main")
    (throw (ex-info (str "%" host " uses %init and %step, not %main")
                    {:host host :names names}))))

(defn- assert-lifecycle
  [host blocks]
  (let [names (names-of blocks)]
    (cond
      (= host "terminal") (assert-terminal-lifecycle names)
      (contains? frame-hosts host) (assert-frame-lifecycle host names))))

(defn- block-named
  [blocks name]
  (first (filter #(= name (:name %)) blocks)))

(defn- assert-function-named
  [block expected]
  (when block
    (when-not (haxe-has-fn? (:haxe block) expected)
      (throw (ex-info (str "%" expected " must contain a function " expected)
                      {:expected expected
                       :found (haxe-fn-name (:haxe block))})))))

(defn- binding-from-block
  [{:keys [name haxe]}]
  (let [fn-name (if (haxe-has-fn? haxe name)
                  name
                  (haxe-fn-name haxe))]
    (when-not fn-name
      (throw (ex-info (str "%" name " must contain a function")
                      {:name name :haxe haxe})))
    [name {:haxe haxe :fn-name fn-name}]))

(defn- assert-supported-bindings
  [blocks]
  (let [unsupported (remove #(= "trace" (:name %)) blocks)]
    (when (seq unsupported)
      (throw (ex-info (str "Only %trace may be defined as a Target helper; found %"
                           (:name (first unsupported)))
                      {:bindings (sort (map :name unsupported))})))))

(defn- haxe-block
  [block]
  (when block
    {:haxe (:haxe block)}))

(defn- requires-block
  [blocks]
  (some #(when (= "requires" (:name %)) %) blocks))

(defn parse-target
  "Turn Target section text into a host, lifecycle bodies, and % bindings.
   Blank input is empty. Non-empty input must start with %name.
   Terminal requires %main. OpenFL, canvas, form, cli and cli-live require %init and %step, not %main.
   Host % names take no body. A host line is required for every non-empty Target."
  [text]
  (if (str/blank? (or text ""))
    {:bindings {} :main nil :host nil :init nil :step nil}
    (let [blocks (->> (str/split-lines text)
                      collect-blocks
                      (mapv (fn [b]
                              {:name (:name b)
                               :haxe (str/trim (str/join "\n" (:lines b)))})))]
      (when (empty? blocks)
        (throw (ex-info "Target must start with %name (e.g. %main or %trace)"
                        {:text text})))
      (assert-unique-names blocks)
      (let [host (extract-host blocks)
            requires (requires-block blocks)
            other (remove #(or (contains? lifecycle-names (:name %))
                               (host-block? %)
                               (= "requires" (:name %)))
                          blocks)]
        (assert-lifecycle host blocks)
        (assert-function-named (block-named blocks "init") "init")
        (assert-function-named (block-named blocks "step") "step")
        (assert-supported-bindings other)
        (let [requires-ir (requires/parse (or (:haxe requires) ""))]
          (requires/assert-no-duplicate-method-signatures! requires-ir)
          {:host host
           :requires requires-ir
           :external-types (requires/provided-types requires-ir)
           :requires-text (some :haxe [requires])
         :bindings (into {} (map binding-from-block other))
         :main (haxe-block (block-named blocks "main"))
         :init (haxe-block (block-named blocks "init"))
           :step (haxe-block (block-named blocks "step"))})))))
