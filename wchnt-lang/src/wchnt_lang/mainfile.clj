(ns wchnt-lang.mainfile
  (:require [clojure.string :as str]))

(def section-order ["schema" "construction" "reactive" "imperative" "target"])

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

(defn extract-code-blocks [content]
  "Extract code blocks from markdown, enforcing one code block per section, correct order, and proper error handling."
  (let [lines (str/split-lines content)
        code-block-start #"^```(?:\w+)?\s*$"
        code-block-end #"^```\s*$"
        section-pattern #"^##\s*(\w+)\s*$"]
    (loop [lines lines
           current-section nil
           current-section-idx -1
           in-code-block false
           current-code []
           section-map {}
           code-block-seen #{} ; track sections that have had a code block
           seen-sections #{}]
      (if (empty? lines)
        ;; End of file: finalize last section if needed
        (if in-code-block
          (throw (ex-info "Malformed markdown: unclosed code block" {}))
          (let [final-section-map (finalize-current-section current-section current-code section-map)]
            ;; Fill in missing sections with ""
            (mapv #(vector % (get final-section-map % "")) section-order)))
        (let [original-line (first lines)
              trimmed-line (str/trim original-line)
              remaining-lines (rest lines)]
          (cond
            ;; Inside code block: check for code block end first
            in-code-block
            (cond
              (re-matches code-block-end trimmed-line)
              (let [updated-section-map (assoc section-map current-section (str/join "\n" current-code))
                    updated-code-block-seen (conj code-block-seen current-section)]
                (recur remaining-lines current-section current-section-idx false [] updated-section-map updated-code-block-seen seen-sections))
              (re-matches code-block-start trimmed-line)
              (throw (ex-info "Malformed markdown: nested code block" {}))
              :else
              (recur remaining-lines current-section current-section-idx true (conj current-code original-line) section-map code-block-seen seen-sections))

            ;; Section header - always reset code block state
            (re-matches section-pattern trimmed-line)
            (let [section-name (str/lower-case (second (re-matches section-pattern trimmed-line)))
                  new-section-idx (check-section-order section-name current-section-idx)
                  updated-section-map (try
                                        (finalize-current-section current-section current-code section-map)
                                        (catch Exception e
                                          (throw (ex-info (.getMessage e) {:section current-section}))))
                  updated-seen-sections (conj seen-sections section-name)]
              (recur remaining-lines section-name new-section-idx false [] updated-section-map code-block-seen updated-seen-sections))

            ;; Code block start
            (re-matches code-block-start trimmed-line)
            (do
              (when (contains? code-block-seen current-section)
                (throw (ex-info (str "Section '" current-section "' has multiple code blocks") {:section current-section})))
              (recur remaining-lines current-section current-section-idx true [] section-map code-block-seen seen-sections))

            ;; Code block end (should not happen outside code block)
            (re-matches code-block-end trimmed-line)
            (throw (ex-info "Malformed markdown: stray code block end" {}))

            ;; Outside code block, ignore line
            :else
            (recur remaining-lines current-section current-section-idx false current-code section-map code-block-seen seen-sections)))))))

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
        {:success false :error validation-error}
        (let [section-map (into {} sections)]
          {:success true
           :schema (get section-map "schema" "")
           :construction (get section-map "construction" "")
           :reactive (get section-map "reactive" "")
           :imperative (get section-map "imperative" "")
           :target (get section-map "target" "")})))
    (catch clojure.lang.ExceptionInfo e
      {:success false :error (.getMessage e)})
    (catch Exception e
      {:success false :error (str "Malformed markdown: " (.getMessage e))})))

(defn read-mainfile [file-path]
  "Read and parse a WCHNT mainfile from the filesystem"
  (try
    (let [content (slurp file-path)]
      (parse-mainfile content))
    (catch Exception e
      {:success false :error (str "Error reading file: " (.getMessage e))}))) 