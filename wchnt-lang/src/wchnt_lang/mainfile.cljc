(ns wchnt-lang.mainfile
  (:require [clojure.string :as str]
            [wchnt-lang.pipeline :as p]))

(def section-order
  ["import" "schema" "construction" "methods" "target-methods" "target"])

(def compile-sections
  #{"schema" "construction" "methods" "target-methods" "target"})

(defn normalize-section-name
  "Normalize a ## heading to a section key (e.g. \"Target Methods\" → \"target-methods\")."
  [heading]
  (-> heading str/trim str/lower-case (str/replace #"\s+" "-")))

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
        (assoc state
               :current-section nil
               :current-code [])))

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
        section-pattern #"^##\s*(.+?)\s*$"]
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
           (some has? #{"construction" "methods" "target-methods" "target"}))
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

(defn parse-import-names
  "Parse ## Import fence: one sibling page name per line (plain or [[Name]])."
  [import-text]
  (->> (str/split-lines (or import-text ""))
       (map str/trim)
       (remove str/blank?)
       (map strip-brackets)
       vec))

(defn valid-page-name?
  "Page / sibling file stem: letters, digits, hyphen, underscore."
  [name]
  (boolean (re-matches #"[\w-]+" name)))

(defn validate-sections [sections]
  "Validate page kind; return error message if invalid."
  (let [section-map (into {} sections)]
    (:error (classify-page section-map))))

(defn parse-mainfile [content]
  "Parse a WCHNT mainfile (markdown with embedded code blocks) and extract sections"
  (try
    (let [sections (extract-code-blocks content)
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
                            :target-methods (get section-map "target-methods" "")
                            :target (get section-map "target" "")}))))
    (catch #?(:clj Exception :cljs :default) e
      (p/fail-cargo (or (ex-message e) (str e))))))

#?(:clj
   (defn read-mainfile [file-path]
     "Read and parse a WCHNT mainfile from the filesystem"
     (try
       (parse-mainfile (slurp file-path))
       (catch Exception e
         (p/fail-cargo (str "Error reading file: " (or (ex-message e) (str e))))))))
