(ns wchnt-lang.mainfile
  (:require [clojure.string :as str]
            [wchnt-lang.pipeline :as p]))

(def section-order ["schema" "construction" "methods" "imperative" "target"])

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
      (throw (ex-info (str "Section '" section-name "' appears out of order") {:section section-name})))
    idx))

(defn finalize-current-section [current-section current-code section-map]
  "Finalize the current section by adding its code to the map"
  (if (and current-section (seq current-code))
    (if (contains? section-map current-section)
      (throw (ex-info (str "Section '" current-section "' has multiple code blocks") {:section current-section}))
      (assoc section-map current-section (str/join "\n" current-code)))
    section-map))

(defn handle-section-header [line current-section current-code section-map current-section-idx]
  "Handle a section header line"
  (let [trimmed-line (str/trim line)
        section-name (str/lower-case (second (re-matches #"^##\s*(\w+)\s*$" trimmed-line)))
        new-section-idx (check-section-order section-name current-section-idx)
        updated-section-map (finalize-current-section current-section current-code section-map)]
    [section-name new-section-idx updated-section-map]))

(defn handle-code-block-start [line current-section section-map]
  "Handle the start of a code block"
  (let [trimmed-line (str/trim line)]
    (cond
      (not current-section)
      (throw (ex-info "Code block found outside of a section" {}))
      (contains? section-map current-section)
      (throw (ex-info (str "Section '" current-section "' has multiple code blocks") {:section current-section}))
      :else
      true)))

(defn- finalize-extract-state
  [{:keys [in-code-block current-section current-code section-map]}]
  (when in-code-block
    (throw (ex-info "Malformed markdown: unclosed code block" {})))
  (let [final-section-map (finalize-current-section current-section current-code section-map)]
    ;; Fill in missing sections with ""
    (mapv #(vector % (get final-section-map % "")) section-order)))

(defn- handle-line-in-code-block
  [{:keys [current-section current-section-idx current-code section-map code-block-seen seen-sections] :as state}
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
  [{:keys [current-section current-section-idx current-code section-map code-block-seen seen-sections] :as state}
   {:keys [trimmed-line code-block-start code-block-end section-pattern]}]
  (cond
    ;; Section header - always reset code block state
    (re-matches section-pattern trimmed-line)
    (let [section-name (str/lower-case (second (re-matches section-pattern trimmed-line)))
          new-section-idx (check-section-order section-name current-section-idx)
          updated-section-map (try
                                (finalize-current-section current-section current-code section-map)
                                (catch Exception e
                                  (throw (ex-info (.getMessage e) {:section current-section}))))]
      (assoc state
             :current-section section-name
             :current-section-idx new-section-idx
             :current-code []
             :section-map updated-section-map
             :seen-sections (conj seen-sections section-name)))

    ;; Code block start
    (re-matches code-block-start trimmed-line)
    (do
      (when (contains? code-block-seen current-section)
        (throw (ex-info (str "Section '" current-section "' has multiple code blocks") {:section current-section})))
      (assoc state :in-code-block true :current-code []))

    ;; Code block end (should not happen outside code block)
    (re-matches code-block-end trimmed-line)
    (throw (ex-info "Malformed markdown: stray code block end" {}))

    ;; Outside code block, ignore line
    :else
    state))

(defn extract-code-blocks [content]
  "Extract code blocks from markdown, enforcing one code block per section, correct order, and proper error handling."
  (let [lines (str/split-lines content)
        code-block-start #"^```(?:\w+)?\s*$"
        code-block-end #"^```\s*$"
        section-pattern #"^##\s*(\w+)\s*$"]
    (loop [lines lines
           state {:current-section nil
                  :current-section-idx -1
                  :in-code-block false
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

(defn validate-sections [sections]
  "Validate that required sections are present and return error message if not"
  (let [section-map (into {} sections)
        schema-content (get section-map "schema" "")]
    (if (and (contains? section-map "schema") (not-empty (str/trim schema-content)))
      nil
      "Schema section is required but not found")))

(defn parse-mainfile [content]
  "Parse a WCHNT mainfile (markdown with embedded code blocks) and extract sections"
  (try
    (let [sections (extract-code-blocks content)
          validation-error (validate-sections sections)]
      (if validation-error
        (p/fail-cargo validation-error)
        (let [section-map (into {} sections)]
          (p/success-cargo {:schema (get section-map "schema" "")
           :construction (get section-map "construction" "")
           :methods (get section-map "methods" "")
           :imperative (get section-map "imperative" "")
                           :target (get section-map "target" "")}))))
    (catch clojure.lang.ExceptionInfo e
      (p/fail-cargo (.getMessage e)))
    (catch Exception e
      (p/fail-cargo (str "Malformed markdown: " (.getMessage e))))))

(defn read-mainfile [file-path]
  "Read and parse a WCHNT mainfile from the filesystem"
  (try
    (let [content (slurp file-path)]
      (parse-mainfile content))
    (catch Exception e
      (p/fail-cargo (str "Error reading file: " (.getMessage e)))))) 
