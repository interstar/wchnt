(ns wchnt-lang.target
  "Parse the Target section: host, lifecycle Haxe, and %name bindings."
  (:require [clojure.string :as str]))

(def known-hosts
  #{"terminal" "openfl"})

(def lifecycle-names
  #{"main" "init" "step"})

(defn- header-name
  [line]
  (second (re-matches #"^[ \t]*%([A-Za-z_][A-Za-z0-9_]*)[ \t]*$" line)))

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
      (throw (ex-info "Target may name only one host (%terminal or %openfl)"
                      {:names (mapv :name hosts)})))
    (if-let [host (first hosts)]
      (do
        (when-not (str/blank? (:haxe host))
          (throw (ex-info (str "%" (:name host) " names the host and must be empty")
                          {:name (:name host)})))
        (:name host))
      "terminal")))

(defn- names-of
  [blocks]
  (set (map :name blocks)))

(defn- assert-terminal-lifecycle
  [names]
  (when (or (contains? names "init") (contains? names "step"))
    (throw (ex-info "%init and %step are for %openfl, not %terminal"
                    {:names names})))
  (when-not (contains? names "main")
    (throw (ex-info "Target must define %main"
                    {:names names}))))

(defn- assert-openfl-lifecycle
  [names]
  (when-not (contains? names "init")
    (throw (ex-info "%openfl requires %init"
                    {:names names})))
  (when-not (contains? names "step")
    (throw (ex-info "%openfl requires %step"
                    {:names names})))
  (when (contains? names "main")
    (throw (ex-info "%openfl uses %init and %step, not %main"
                    {:names names}))))

(defn- assert-lifecycle
  [host blocks]
  (let [names (names-of blocks)]
    (case host
      "terminal" (assert-terminal-lifecycle names)
      "openfl" (assert-openfl-lifecycle names))))

(defn- block-named
  [blocks name]
  (first (filter #(= name (:name %)) blocks)))

(defn- assert-function-named
  [block expected]
  (when block
    (let [found (haxe-fn-name (:haxe block))]
      (when (not= found expected)
        (throw (ex-info (str "%" expected " must contain a Haxe function " expected)
                        {:expected expected :found found}))))))

(defn- binding-from-block
  [{:keys [name haxe]}]
  (let [fn-name (haxe-fn-name haxe)]
    (when-not fn-name
      (throw (ex-info (str "%" name " must contain a Haxe function")
                      {:name name :haxe haxe})))
    [name {:haxe haxe :fn-name fn-name}]))

(defn- haxe-block
  [block]
  (when block
    {:haxe (:haxe block)}))

(defn parse-target
  "Turn Target section text into a host, lifecycle Haxe, and % bindings.
   Blank input is empty. Non-empty input must start with %name.
   Terminal requires %main. OpenFL requires %init and %step, not %main.
   %terminal and %openfl name the host and take no Haxe body. Omitted host is terminal."
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
            other (remove #(or (contains? lifecycle-names (:name %))
                               (host-block? %))
                          blocks)]
        (assert-lifecycle host blocks)
        (assert-function-named (block-named blocks "init") "init")
        (assert-function-named (block-named blocks "step") "step")
        {:host host
         :bindings (into {} (map binding-from-block other))
         :main (haxe-block (block-named blocks "main"))
         :init (haxe-block (block-named blocks "init"))
         :step (haxe-block (block-named blocks "step"))}))))
