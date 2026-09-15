(ns live.render
  "Render a .wcn page (markdown) to read-view HTML.

   Prose is a small markdown subset (headings, lists, emphasis, inline code,
   links, and [[wiki links]]). WCHNT code fences are coloured by the shared
   `wchnt-lang.highlight` — the same grammar the compiler and editor use — so
   the read view stays a single source of truth with the editor."
  (:require [clojure.string :as string]
            [wchnt-lang.highlight :as hl]))

;; Same token classes the editor paints (see live/highlight.cljs + live.css),
;; so read-view code colouring matches the editor under both themes.
(def ^:private kind->class
  {:class "cm-wchnt-class"
   :type "cm-wchnt-type"
   :name "cm-wchnt-name"
   :sigil "cm-wchnt-sigil"
   :rel-context "cm-wchnt-rel-context"
   :rel-delegate "cm-wchnt-rel-delegate"
   :rel-reactive "cm-wchnt-rel-reactive"
   :rel-external "cm-wchnt-rel-external"
   :rel-mailbox "cm-wchnt-rel-mailbox"
   :keyword "cm-wchnt-keyword"
   :string "cm-wchnt-string"
   :number "cm-wchnt-number"
   :method "cm-wchnt-method"
   :path "cm-wchnt-path"
   :error "cm-wchnt-error"})

(defn- escape-html
  [s]
  (-> s
      (string/replace "&" "&amp;")
      (string/replace "<" "&lt;")
      (string/replace ">" "&gt;")
      (string/replace "\"" "&quot;")))

(defn- safe-spans
  "Grammar spans for the whole page; empty when there's no code or a parse throws."
  [text]
  (if (string/includes? text "```")
    (try (:spans (hl/highlight text)) (catch :default _ []))
    []))

(defn- line-records
  "Vector of {:text :start :end} with absolute char offsets (newline = 1 char),
   matching the offset convention of wchnt-lang.highlight."
  [text]
  (loop [lines (string/split text #"\n" -1)
         start 0
         out []]
    (if (empty? lines)
      out
      (let [l (first lines)]
        (recur (rest lines)
               (+ start (count l) 1)
               (conj out {:text l :start start :end (+ start (count l))}))))))

;; --- inline prose ----------------------------------------------------------

(defn- wiki-anchor
  [name]
  (let [n (string/trim name)]
    (str "<a class=\"cm-wiki-link\" href=\"#\" data-wiki-page=\"" n "\">" n "</a>")))

(defn- ext-anchor
  [txt url]
  (str "<a class=\"read-ext-link\" href=\"" url
       "\" target=\"_blank\" rel=\"noopener\">" txt "</a>"))

(defn- render-inline
  "Escape, then apply a small inline-markdown subset. Groups are already escaped,
   so replacements never re-escape."
  [raw]
  (-> raw
      escape-html
      (string/replace #"`([^`]+)`" "<code class=\"read-code-inline\">$1</code>")
      (string/replace #"\[\[([^\]]+)\]\]" (fn [[_ name]] (wiki-anchor name)))
      (string/replace #"\[([^\]]+)\]\(([^)]+)\)" (fn [[_ txt url]] (ext-anchor txt url)))
      (string/replace #"\*\*([^*]+)\*\*" "<strong>$1</strong>")
      (string/replace #"\*([^*]+)\*" "<em>$1</em>")))

;; --- code fences -----------------------------------------------------------

(defn- wrap-kind
  [kind s]
  (if-let [cls (kind->class kind)]
    (str "<span class=\"" cls "\">" (escape-html s) "</span>")
    (escape-html s)))

(defn- colorize-code-line
  "HTML for one code line, colouring any grammar spans that fall on it."
  [text {ls :start le :end} spans]
  (let [local (->> spans
                   (filter #(and (< (:start %) le) (> (:end %) ls)))
                   (sort-by :start))]
    (loop [pos ls
           ss local
           out []]
      (if (empty? ss)
        (apply str (conj out (escape-html (subs text pos le))))
        (let [sp (first ss)
              s (max (:start sp) pos)
              e (min (:end sp) le)]
          (if (<= e s)
            (recur pos (rest ss) out)
            (recur e (rest ss)
                   (conj out
                         (escape-html (subs text pos s))
                         (wrap-kind (:kind sp) (subs text s e))))))))))

(defn- render-code-block
  [text lines spans]
  (str "<pre class=\"read-code\"><code>"
       (string/join "\n" (map #(colorize-code-line text % spans) lines))
       "</code></pre>"))

;; --- block grouping --------------------------------------------------------

(defn- fence-open [t] (re-matches #"^```(\w+)?\s*$" t))
(defn- fence-close [t] (re-matches #"^```\s*$" t))
(defn- heading-line? [t] (re-matches #"^#{1,6}\s+.+" t))
(defn- list-line? [t] (re-matches #"^[-*]\s+.+" t))

(defn- flush-cur [acc cur] (if cur (conj acc cur) acc))

(defn- group-blocks
  "Group line records into :code / :heading / :list / :para blocks."
  [lines]
  (loop [ls lines
         acc []
         cur nil]
    (if (empty? ls)
      (flush-cur acc cur)
      (let [ln (first ls)
            tt (string/trim (:text ln))]
        (cond
          (and cur (= (:kind cur) :code))
          (if (fence-close tt)
            (recur (rest ls) (conj acc cur) nil)
            (recur (rest ls) acc (update cur :lines conj ln)))

          (fence-open tt)
          (recur (rest ls) (flush-cur acc cur) {:kind :code :lines []})

          (heading-line? tt)
          (recur (rest ls) (conj (flush-cur acc cur) {:kind :heading :text tt}) nil)

          (string/blank? tt)
          (recur (rest ls) (flush-cur acc cur) nil)

          (list-line? tt)
          (let [item (string/replace tt #"^[-*]\s+" "")]
            (if (and cur (= (:kind cur) :list))
              (recur (rest ls) acc (update cur :items conj item))
              (recur (rest ls) (flush-cur acc cur) {:kind :list :items [item]})))

          :else
          (if (and cur (= (:kind cur) :para))
            (recur (rest ls) acc (update cur :lines conj tt))
            (recur (rest ls) (flush-cur acc cur) {:kind :para :lines [tt]})))))))

(defn- render-heading
  [t]
  (let [[_ hashes body] (re-matches #"^(#{1,6})\s+(.+?)\s*$" t)
        n (count hashes)]
    (str "<h" n " class=\"read-h" n "\">" (render-inline body) "</h" n ">")))

(defn- render-list
  [items]
  (str "<ul class=\"read-ul\">"
       (apply str (map #(str "<li>" (render-inline %) "</li>") items))
       "</ul>"))

(defn- render-block
  [text spans blk]
  (case (:kind blk)
    :code (render-code-block text (:lines blk) spans)
    :heading (render-heading (:text blk))
    :list (render-list (:items blk))
    :para (str "<p class=\"read-p\">"
               (string/join " " (map render-inline (:lines blk)))
               "</p>")
    ""))

(defn render-html
  "Full read-view HTML for a .wcn page."
  [text]
  (let [spans (safe-spans text)
        blocks (group-blocks (line-records text))]
    (apply str (map #(render-block text spans %) blocks))))
