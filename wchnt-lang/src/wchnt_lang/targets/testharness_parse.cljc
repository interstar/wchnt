(ns wchnt-lang.targets.testharness-parse
  "Parse %testharness / %testharness-live Target bodies:
   repeated %with constructions and %assert cases."
  (:require [clojure.string :as str]))

(def host-names
  #{"testharness" "testharness-live"})

(defn- header-name
  [line]
  (second (re-matches #"^[ \t]*%([A-Za-z_][A-Za-z0-9_-]*)[ \t]*$" line)))

(defn- collect-blocks
  "Like core/collect-blocks but allows duplicate %with / %assert names."
  [lines]
  (let [start (drop-while #(str/blank? %) lines)]
    (when (and (seq start) (not (header-name (first start))))
      (throw (ex-info "Target must start with %testharness or %testharness-live"
                      {:line (first start)})))
    (reduce (fn [blocks line]
              (if-let [name (header-name line)]
                (conj blocks {:name name :lines []})
                (if (empty? blocks)
                  blocks
                  (update-in blocks [(dec (count blocks)) :lines] conj line))))
            []
            start)))

(defn- block-body
  [block]
  (str/trim (str/join "\n" (:lines block))))

(defn- root-class-from-construction-text
  [text]
  (when-let [[_ class-name] (re-find #"\[\s*:([A-Za-z_][A-Za-z0-9_]*)" text)]
    class-name))

(defn- binding-name
  [class-name]
  (str (str/lower-case (subs class-name 0 1))
       (subs class-name 1)))

(defn- parse-with-block
  [block]
  (let [text (block-body block)]
    (when (str/blank? text)
      (throw (ex-info "%with requires a WCHNT construction" {})))
    (let [root (root-class-from-construction-text text)]
      (when-not root
        (throw (ex-info "%with body must be a construction starting with [:ClassName …]"
                        {:text text})))
      {:op :with
       :construction-text text
       :root-class root
       :binding (binding-name root)})))

(defn- strip-label
  [line]
  (when-let [[_ label] (re-matches #"^[ \t]*\"((?:\\.|[^\"])*)\"[ \t]*$" line)]
    (-> label
        (str/replace #"\\n" "\n")
        (str/replace #"\\\"" "\"")
        (str/replace #"\\\\" "\\"))))

(defn- parse-assert-block
  "Assert body: alternating quoted labels and host expressions."
  [block]
  (let [lines (->> (:lines block)
                   (map str/trim)
                   (remove str/blank?))]
    (when (empty? lines)
      (throw (ex-info "%assert requires at least one label and expression" {})))
    (loop [remaining lines
           cases []]
      (if (empty? remaining)
        {:op :assert :cases cases}
        (let [label (strip-label (first remaining))]
          (when-not label
            (throw (ex-info "%assert expected a quoted label"
                            {:line (first remaining)})))
          (when-not (next remaining)
            (throw (ex-info (str "%assert label \"" label "\" needs an expression")
                            {:label label})))
          (recur (nnext remaining)
                 (conj cases {:label label
                              :expr (second remaining)})))))))

(defn- parse-suite-block
  [block]
  (case (:name block)
    "with" (parse-with-block block)
    "assert" (parse-assert-block block)
    (throw (ex-info (str "Test harness only allows %with and %assert after the host line, not %"
                         (:name block))
                    {:name (:name block)}))))

(defn parse-target
  "Parse a %testharness or %testharness-live Target section into suite IR.
   No %main/%init/%step. Multiple %with and %assert blocks are allowed."
  ([text]
   (parse-target text nil))
  ([text expected-host]
   (when (str/blank? (or text ""))
     (throw (ex-info "Test harness Target cannot be blank" {})))
   (let [blocks (collect-blocks (str/split-lines text))]
     (when (empty? blocks)
       (throw (ex-info "Target must start with %testharness or %testharness-live"
                       {:text text})))
     (let [host-block (first blocks)
           rest-blocks (rest blocks)
           host (:name host-block)]
       (when-not (contains? host-names host)
         (throw (ex-info "First Target directive must be %testharness or %testharness-live"
                         {:found host})))
       (when (and expected-host (not= host expected-host))
         (throw (ex-info (str "Expected %" expected-host ", found %" host)
                         {:expected expected-host :found host})))
       (when-not (str/blank? (block-body host-block))
         (throw (ex-info (str "%" host " names the host and must be empty") {})))
       (when (empty? rest-blocks)
         (throw (ex-info (str "%" host " requires at least one %with or %assert") {})))
       (let [suite (mapv parse-suite-block rest-blocks)]
         {:host host
          :bindings {}
          :main nil
          :init nil
          :step nil
          :requires nil
          :external-types #{}
          :suite suite})))))
