(ns wchnt-lang.highlight
  "Instaparse spans for the live editor. Same grammars as the compiler.
   Keywords are hidden terminals, so we locate them inside the node span."
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.grammars :as grammars]))

(def ^:private highlight-sections #{"schema" "construction" "methods"})

(def ^:private tag-kind
  {:Definee :class
   :ClassName :class
   :Type :type
   :TypeMarker :type
   :AltName :name
   :Sigil :sigil
   :Inlet :sigil
   :StringLiteral :string
   :EnumValue :string
   :IntLiteral :number
   :FloatLiteral :number
   :BoolLiteral :keyword
   :MethodName :method
   :FieldPath :path})

(def ^:private node-keywords
  {:IfExpr ["if" "else"]
   :OrOp ["or"]
   :AndOp ["and"]
   :NotOp ["not"]})

(defn- word-break?
  [text i]
  (or (neg? i)
      (>= i (count text))
      (not (re-matches #"[A-Za-z0-9_]" (str (nth text i))))))

(defn- find-words
  [text word]
  (let [n (count word)]
    (loop [i 0
           acc []]
      (if-let [j (str/index-of text word i)]
        (if (and (word-break? text (dec j))
                 (word-break? text (+ j n)))
          (recur (+ j n) (conj acc [j (+ j n)]))
          (recur (inc j) acc))
        acc))))

(defn- keywords-in
  [text start end words]
  (let [chunk (subs text start end)]
    (mapcat (fn [w]
              (map (fn [[s e]]
                     {:kind :keyword :start (+ start s) :end (+ start e)})
                   (find-words chunk w)))
            words)))

(defn- node-span
  [node]
  (when-let [[s e] (insta/span node)]
    (when (< s e)
      [s e])))

(defn- trim-span
  [text start end]
  (let [chunk (subs text start end)
        left (count (re-find #"^\s*" chunk))
        right (count (re-find #"\s*$" chunk))
        s (+ start left)
        e (- end right)]
    (when (< s e)
      [s e])))

(defn- walk-node
  [text node]
  (if-not (vector? node)
    []
    (let [tag (first node)
          kind (get tag-kind tag)
          span (node-span node)
          tagged (if-let [[s e] (when (and kind span)
                                  (trim-span text (first span) (second span)))]
                   [{:kind kind :start s :end e}]
                   [])
          kws (if (and span (get node-keywords tag))
                (keywords-in text (first span) (second span)
                             (get node-keywords tag))
                [])]
      (into (into tagged kws)
            (mapcat #(walk-node text %) (rest node))))))

(defn- lines-with-offsets
  [text]
  (let [n (count text)]
    (loop [i 0
           start 0
           out []]
      (cond
        (= i n)
        (conj out {:start start :end i :text (subs text start i)})

        (= (nth text i) \newline)
        (recur (inc i) (inc i)
               (conj out {:start start :end i :text (subs text start i)}))

        :else
        (recur (inc i) start out)))))

(defn- fence?
  [trimmed]
  (re-matches #"^```(?:\w+)?\s*$" trimmed))

(defn- section-name
  "Match the compiler's heading rule: 1-3 hashes, any name, normalized
   (e.g. \"## Target Methods\" -> \"target-methods\")."
  [trimmed]
  (when-let [m (re-matches #"^#{1,3}\s*(.+?)\s*$" trimmed)]
    (-> (second m) str/trim str/lower-case (str/replace #"\s+" "-"))))

(defn- close-block
  [section lines]
  (when (and (contains? highlight-sections section) (seq lines))
    {:section section
     :text (str/join "\n" (map :text lines))
     :start (:start (first lines))
     :end (:end (last lines))}))

(defn- extract-blocks
  [text]
  (loop [lines (lines-with-offsets text)
         section nil
         in-block? false
         acc-lines []
         blocks []]
    (if (empty? lines)
      (if in-block?
        {:error {:message "Malformed markdown: unclosed code block"}}
        {:blocks blocks})
      (let [line (first lines)
            trimmed (str/trim (:text line))
            header (section-name trimmed)]
        (cond
          (and in-block? (fence? trimmed))
          (recur (rest lines) section false []
                 (if-let [b (close-block section acc-lines)]
                   (conj blocks b)
                   blocks))

          in-block?
          (if header
            {:error {:message "Malformed markdown: unclosed code block"}}
            (recur (rest lines) section true (conj acc-lines line) blocks))

          header
          (recur (rest lines) header false [] blocks)

          (fence? trimmed)
          (recur (rest lines) section true [] blocks)

          :else
          (recur (rest lines) section false acc-lines blocks))))))

(defn- parse-block
  [{:keys [section text]}]
  (case section
    "schema" (grammars/parse-schema text)
    "construction" (insta/parse grammars/construction-parser text
                                :start :BlockStatements)
    "methods" (insta/parse grammars/construction-parser text :start :Code)
    nil))

(defn- failure-range
  [text fail]
  (let [i (min (max (or (:index fail) 0) 0) (count text))
        end (min (count text) (max (inc i)
                                   (or (when-let [j (str/index-of text "\n" i)]
                                         j)
                                       (count text))))]
    [i end]))

(defn- shift
  [block item]
  (-> item
      (assoc :section (:section block))
      (update :start + (:start block))
      (update :end + (:start block))))

(defn- highlight-block
  [block]
  (let [ast (parse-block block)]
    (if (or (nil? ast) (insta/failure? ast))
      {:spans []
       :errors [(shift block
                       (let [[s e] (failure-range (:text block) ast)]
                         {:start s :end e
                          :message (str (:section block) ": "
                                        (grammars/failure-in-text->string ast (:text block)))}))]}
      {:spans (mapv #(shift block %) (walk-node (:text block) ast))
       :errors []})))

(defn highlight
  "Spans and parse errors for Schema, Construction, and Methods blocks."
  [markdown]
  (let [{:keys [blocks error]} (extract-blocks markdown)]
    (if error
      {:spans []
       :errors [(assoc error :section nil :start 0 :end (min 1 (count markdown)))]}
      (reduce (fn [acc block]
                (let [{:keys [spans errors]} (highlight-block block)]
                  (-> acc
                      (update :spans into spans)
                      (update :errors into errors))))
              {:spans [] :errors []}
              blocks))))

(defn preserve
  "On section failure, keep the last good spans for that section.
   Document-level extract errors keep every previous span."
  [prev now]
  (if (some (comp nil? :section) (:errors now))
    {:spans (:spans prev) :errors (:errors now)}
    (let [failed (set (keep :section (:errors now)))
          kept (filterv #(contains? failed (:section %)) (:spans prev))
          fresh (filterv #(not (contains? failed (:section %))) (:spans now))]
      {:spans (into kept fresh)
       :errors (:errors now)})))
