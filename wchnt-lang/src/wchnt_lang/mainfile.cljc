(ns wchnt-lang.mainfile
  (:require [clojure.string :as str]
            [wchnt-lang.pipeline :as p]))

(def section-order
  ["import" "schema" "construction" "methods" "public" "target"])

(def compile-sections
  #{"schema" "construction" "methods" "public" "target"})

(defn normalize-section-name
  "Normalize a Markdown section heading to its key."
  [heading]
  (-> heading str/trim str/lower-case (str/replace #"\s+" "-")))

(defn- markdown-heading
  [line]
  (when-let [[_ hashes title] (re-matches #"^(#{1,3})\s+(.+?)\s*$" (str/trim line))]
    {:level (count hashes)
     :title (str/trim title)}))

(defn- transclusion-heading
  [heading]
  (when-let [[_ title page] (re-matches #"(?s)(.+?)\s+\[\[([^\]]+)\]\]$"
                                        (:title heading))]
    {:level (:level heading)
     :title (str/trim title)
     :page (str/trim page)}))

(defn- markdown-section-headings
  "Find Markdown headings outside fenced code blocks."
  [lines]
  (loop [remaining lines
         index 0
         in-fence? false
         headings []]
    (if (empty? remaining)
      headings
      (let [line (first remaining)
            trimmed (str/trim line)
            fence-start? (boolean (re-matches #"^```(?:\w+)?\s*$" trimmed))
            fence-end? (boolean (re-matches #"^```\s*$" trimmed))]
        (cond
          (and in-fence? fence-end?)
          (recur (rest remaining) (inc index) false headings)

          in-fence?
          (recur (rest remaining) (inc index) true headings)

          fence-start?
          (recur (rest remaining) (inc index) true headings)

          :else
          (recur (rest remaining)
                 (inc index)
                 false
                 (if-let [heading (markdown-heading line)]
                   (conj headings (assoc heading :index index))
                   headings)))))))

(defn- markdown-sections
  "Return sections with their raw bodies, using normal Markdown heading nesting."
  [content]
  (let [lines (vec (str/split-lines (or content "")))
        headings (markdown-section-headings lines)]
    (mapv (fn [heading-index heading]
            (let [next-heading (some #(when (<= (:level %) (:level heading)) %)
                                     (subvec headings (inc heading-index)))
                  end-index (or (:index next-heading) (count lines))]
              (assoc heading
                     :body (str/join "\n"
                                     (subvec lines (inc (:index heading)) end-index))
                     :transclusion (transclusion-heading heading))))
          (range (count headings))
          headings)))

(defn- transclusion-in-content?
  [content]
  (some :transclusion (markdown-sections content)))

(defn- source-section
  [page-name source-content section-title]
  (let [matches (filter #(= (normalize-section-name section-title)
                            (normalize-section-name (or (get-in % [:transclusion :title])
                                                        (:title %))))
                        (markdown-sections source-content))]
    (cond
      (empty? matches)
      (throw (ex-info (str "Transclusion section '" (normalize-section-name section-title)
                           "' not found in page '" page-name "'")
                      {:page page-name :section section-title}))

      (> (count matches) 1)
      (throw (ex-info (str "Transclusion section '" (normalize-section-name section-title)
                           "' occurs more than once in page '" page-name "'")
                      {:page page-name :section section-title}))

      :else
      (let [section (first matches)]
        (when (or (:transclusion section)
                  (transclusion-in-content? (:body section)))
          (throw (ex-info (str "Nested transclusion of section '"
                               (normalize-section-name section-title)
                               "' in page '" page-name "' is not supported")
                          {:page page-name :section section-title})))
        section))))

(declare valid-page-name?)

(defn expand-transclusions
  "Replace `## Section [[page]]` sections with the matching raw section body.

   Expansion is deliberately one level deep. The resolver returns raw Markdown;
   it is never called recursively while expanding a source section."
  ([content]
   (expand-transclusions content nil))
  ([content resolve-page]
   (let [lines (vec (str/split-lines (or content "")))
         sections (markdown-sections content)
         transcluded (filter :transclusion sections)]
     (if (empty? transcluded)
       content
       (do
         (when-not resolve-page
           (throw (ex-info "Transclusion requires a page resolver" {})))
         (let [replacements
               (into {}
                     (map (fn [section]
                            (let [{:keys [page title level]} (:transclusion section)]
                              (when-not (valid-page-name? page)
                                (throw (ex-info (str "Invalid transclusion page name '" page "'")
                                                {:page page})))
                              (let [source-content (resolve-page page)]
                                (when-not source-content
                                  (throw (ex-info (str "Transclusion page not found: '" page "'")
                                                  {:page page})))
                                (let [source (source-section page source-content title)]
                                  [(:index section)
                                   (vec (concat
                                         [(str (apply str (repeat level "#")) " " title)]
                                         (when (seq (:body source))
                                           (str/split-lines (:body source)))))]))))
                          transcluded))]
           (loop [index 0
                  output []]
             (if (>= index (count lines))
               (str/join "\n" output)
               (if-let [section (some #(when (= index (:index %)) %) transcluded)]
                 (let [end-index (or (:index (some #(when (and (> (:index %) index)
                                                                  (<= (:level %) (:level section)))
                                                           %)
                                                     sections))
                                     (count lines))
                       [replacement-start & replacement-lines]
                       (get replacements index)]
                   (recur end-index (into output (cons replacement-start replacement-lines))))
                 (recur (inc index) (conj output (nth lines index))))))))))))

(defn reserved-section?
  [section-name]
  (contains? (set section-order) section-name))

(defn valid-section? [section-name]
  "Check if section name is valid and return its index"
  (let [idx (get (zipmap section-order (range)) section-name -1)]
    (when (= idx -1)
      (throw (ex-info (str "Unknown section: '" section-name "'") {:section section-name})))
    idx))

(defn check-section-order [section-name current-section-idx]
  "Check if section appears in correct order"
  (let [idx (valid-section? section-name)]
    (when (< idx current-section-idx)
      (throw (ex-info (str "Section '" section-name "' appears out of order")
                      {:section section-name})))
    idx))

(defn finalize-current-section [current-section current-code section-map]
  "Finalize the current section by adding its code to the map"
  (if (and current-section (seq current-code))
    (if (contains? section-map current-section)
      (throw (ex-info (str "Section '" current-section "' has multiple code blocks")
                      {:section current-section}))
      (assoc section-map current-section (str/join "\n" current-code)))
    section-map))

(defn- section-heading-name
  [trimmed-line section-pattern]
  (when-let [[_ raw] (re-matches section-pattern trimmed-line)]
    (normalize-section-name raw)))

(defn- finalize-extract-state
  [{:keys [in-code-block skipping-prose-fence current-section current-code section-map]}]
  (when (or in-code-block skipping-prose-fence)
    (throw (ex-info "Malformed markdown: unclosed code block" {})))
  (let [final-section-map (finalize-current-section current-section current-code section-map)]
    (mapv #(vector % (get final-section-map % "")) section-order)))

(defn- handle-line-in-code-block
  [{:keys [current-section current-code section-map code-block-seen] :as state}
   {:keys [original-line trimmed-line code-block-start code-block-end section-pattern]}]
  (cond
    (re-matches code-block-end trimmed-line)
    (let [updated-section-map (assoc section-map current-section (str/join "\n" current-code))
          updated-code-block-seen (conj code-block-seen current-section)]
      (assoc state
             :in-code-block false
             :current-code []
             :section-map updated-section-map
             :code-block-seen updated-code-block-seen))

    (re-matches section-pattern trimmed-line)
    (throw (ex-info "Malformed markdown: unclosed code block" {}))

    (re-matches code-block-start trimmed-line)
    (throw (ex-info "Malformed markdown: nested code block" {}))

    :else
    (assoc state :current-code (conj current-code original-line))))

(defn- handle-line-outside-code-block
  [{:keys [current-section current-section-idx current-code section-map code-block-seen seen-sections skipping-prose-fence] :as state}
   {:keys [trimmed-line code-block-start code-block-end section-pattern]}]
  (cond
    skipping-prose-fence
    (if (re-matches code-block-end trimmed-line)
      (assoc state :skipping-prose-fence false)
      state)

    (re-matches section-pattern trimmed-line)
    (let [section-name (section-heading-name trimmed-line section-pattern)]
      (if (reserved-section? section-name)
        (let [new-section-idx (check-section-order section-name current-section-idx)
              updated-section-map (finalize-current-section current-section current-code section-map)]
          (assoc state
                 :current-section section-name
                 :current-section-idx new-section-idx
                 :current-code []
                 :section-map updated-section-map
                 :seen-sections (conj seen-sections section-name)))
        (if (and current-section (contains? code-block-seen current-section))
          (assoc state :current-section nil :current-code [])
          state)))

    (re-matches code-block-start trimmed-line)
    (cond
      (not (reserved-section? current-section))
      (assoc state :skipping-prose-fence true)

      (contains? code-block-seen current-section)
      (throw (ex-info (str "Section '" current-section "' has multiple code blocks")
                      {:section current-section}))

      :else
      (assoc state :in-code-block true :current-code []))

    (re-matches code-block-end trimmed-line)
    (throw (ex-info "Malformed markdown: stray code block end" {}))

    :else
    state))

(defn extract-code-blocks [content]
  "Extract code blocks from markdown, enforcing one code block per section, correct order, and proper error handling."
  (let [lines (str/split-lines content)
        code-block-start #"^```(?:\w+)?\s*$"
        code-block-end #"^```\s*$"
        section-pattern #"^#{1,3}\s*(.+?)\s*$"]
    (loop [lines lines
           state {:current-section nil
                  :current-section-idx -1
                  :in-code-block false
                  :skipping-prose-fence false
                  :current-code []
                  :section-map {}
                  :code-block-seen #{}
                  :seen-sections #{}}]
      (if (empty? lines)
        (finalize-extract-state state)
        (let [original-line (first lines)
              trimmed-line (str/trim original-line)
              remaining-lines (rest lines)
              ctx {:original-line original-line
                   :trimmed-line trimmed-line
                   :code-block-start code-block-start
                   :code-block-end code-block-end
                   :section-pattern section-pattern}]
          (recur remaining-lines
                 (if (:in-code-block state)
                   (handle-line-in-code-block state ctx)
                   (handle-line-outside-code-block state ctx))))))))

(defn- section-present?
  [section-map section-key]
  (not-empty (str/trim (get section-map section-key ""))))

(defn classify-page
  "Return {:page-kind kw} or {:error string}."
  [section-map]
  (let [has? #(section-present? section-map %)
        compile-present (filter has? compile-sections)]
    (cond
      (and (has? "import") (empty? compile-present))
      {:error "Import with nothing to compile"}

      (and (not (has? "schema"))
           (some has? #{"construction" "methods" "public" "target"}))
      {:error "Compile sections require a Schema section"}

      (empty? compile-present)
      {:page-kind :documentation}

      (and (has? "schema") (not (has? "construction")))
      {:page-kind :library}

      :else
      {:page-kind :program})))

(defn- strip-brackets
  [name]
  (if-let [[_ inner] (re-matches #"\[\[(.+?)\]\]" (str/trim name))]
    (str/trim inner)
    (str/trim name)))

(defn- valid-alias?
  [name]
  (boolean (re-matches #"[A-Za-z][A-Za-z0-9_]*" (or name ""))))

(defn- parse-import-line
  "One Import line: page, [[page]], page as alias, [[page]] as alias."
  [line]
  (let [line (str/trim line)]
    (if-let [[_ page alias] (re-matches #"(?:\[\[)?([\w-]+)(?:\]\])?\s+as\s+([A-Za-z][A-Za-z0-9_]*)" line)]
      {:page page :alias alias}
      (let [page (strip-brackets line)]
        (when-not (re-matches #"[\w-]+" page)
          (throw (ex-info (str "Invalid import line '" line "'") {:line line})))
        (if (valid-alias? page)
          {:page page :alias page}
          (throw (ex-info (str "Import '" page "' needs an alias (e.g. " page " as lib)")
                          {:page page})))))))

(defn parse-import-names
  "Page names from ## Import (plain or [[Name]]), in file order."
  [import-text]
  (->> (str/split-lines (or import-text ""))
       (map str/trim)
       (remove str/blank?)
       (map strip-brackets)
       vec))

(defn parse-import-specs
  "Parse ## Import fence into {:page :alias} in file order.
   Identifier page names default to themselves as alias; hyphenated names need `as`."
  [import-text]
  (let [specs (->> (str/split-lines (or import-text ""))
                   (map str/trim)
                   (remove str/blank?)
                   (mapv parse-import-line))
        aliases (map :alias specs)]
    (when (not= (count aliases) (count (set aliases)))
      (throw (ex-info (str "Duplicate import alias '"
                           (ffirst (filter (fn [[_ n]] (> n 1))
                                           (frequencies aliases)))
                           "'")
                      {:specs specs})))
    specs))

(defn parse-public-names
  "Parse ## Public fence: an unqualified method definition or published type name."
  [public-text]
  (->> (str/split-lines (or public-text ""))
       (map str/trim)
       (remove str/blank?)
       (mapv (fn [line]
               (cond
                 (re-matches #"([A-Za-z_][A-Za-z0-9_]*)\s*=.*" line)
                 {:method (first (str/split line #"\s*=\s*" 2))}

                 (re-matches #"([A-Za-z][A-Za-z0-9_]*)" line)
                 {:type line}

                 :else
                 (throw (ex-info (str "Invalid Public line '" line
                                      "' (expected name = {...} or a type name)")
                                 {:line line})))))))

(defn valid-page-name?
  "Page / sibling file stem: letters, digits, hyphen, underscore."
  [name]
  (boolean (re-matches #"[\w-]+" name)))

(defn validate-sections [sections]
  "Validate page kind; return error message if invalid."
  (let [section-map (into {} sections)]
    (:error (classify-page section-map))))

(defn parse-mainfile
  "Parse a WCHNT mainfile, expanding section transclusions before extraction."
  ([content]
   (parse-mainfile content {}))
  ([content {:keys [resolve-page]}]
   (try
    (let [expanded-content (expand-transclusions content resolve-page)
          sections (extract-code-blocks expanded-content)
          validation-error (validate-sections sections)]
      (if validation-error
        (p/fail-cargo validation-error)
        (let [section-map (into {} sections)
              {:keys [page-kind]} (classify-page section-map)]
          (p/success-cargo {:page-kind page-kind
                            :import (get section-map "import" "")
                            :schema (get section-map "schema" "")
                            :construction (get section-map "construction" "")
                            :methods (get section-map "methods" "")
                            :public (get section-map "public" "")
                            :target (get section-map "target" "")}))))
    (catch #?(:clj Exception :cljs :default) e
      (p/fail-cargo (or (ex-message e) (str e)))))))

#?(:clj
   (defn read-mainfile [file-path]
     "Read and parse a WCHNT mainfile from the filesystem"
     (try
       (parse-mainfile (slurp file-path))
       (catch Exception e
         (p/fail-cargo (str "Error reading file: " (or (ex-message e) (str e))))))))
