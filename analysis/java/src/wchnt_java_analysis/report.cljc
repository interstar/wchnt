(ns wchnt-java-analysis.report
  (:require [clojure.string :as str]
            [wchnt-java-analysis.analyze :as analyze]))

(defn table [headers rows]
  (str "| " (str/join " | " headers) " |\n"
       "| " (str/join " | " (repeat (count headers) "---")) " |\n"
       (str/join "\n" (map #(str "| " (str/join " | " %) " |") rows))))

(defn field-row [class field]
  [(:name class) (:name field) (:type field) (if (:static field) "static" "instance") (:location field)])

(defn method-row [class method]
  [(:name class) (:kind method) (:name method) (:return-type method) (str/join ", " (map #(str (:type %) " " (:name %)) (:parameters method))) (:location method)])

(defn render [analysis]
  (let [known-types (set (map :name (concat (:classes analysis) (:interfaces analysis))))
        inheritance (analyze/inheritance-view (:classes analysis))
        classes (map #(assoc % :schema-line (analyze/schema-line % known-types)) (:classes inheritance))
        analysis (assoc analysis :classes classes :generated-sum-types (:generated-sum-types inheritance))
        schema-lines (concat (map #(str (:name %) " = " (str/join " | " (:values %))) (:generated-sum-types analysis))
                             (keep :schema-line classes))]
    (str "# Java assemblage analysis\n\n"
         "Generated reference material; this is not an automatic Java → WCHNT conversion.\n\n"
         "## Summary\n\n"
         (str (count (:files analysis)) " source files, " (count classes) " classes, " (count (:interfaces analysis)) " interfaces.\n\n"
              "## Source files\n\n"
              (str/join "\n" (map #(str "- `" % "`") (:files analysis)))
              "\n\n"
              (when (seq (:errors analysis))
                (str "### Parse errors\n\n"
                     (str/join "\n" (map #(str "- `" (:file %) "`: " (:message %)) (:errors analysis)))
                     "\n\n"))
              "## Suggested Schema\n\n"
              "The following is deliberately conservative. Fields whose types are not found in this source tree are marked `@`; review every relationship and field name.\n\n"
              "```wchnt\n"
              (str/join "\n" schema-lines)
              "\n```\n\n"
              "## Interfaces\n\n"
              (if (seq (:interfaces analysis))
                (str/join "\n" (map #(str "- `" (analyze/interface-line %) "`\n  Methods: " (or (some->> (:methods %) (map :signature) (str/join "; ")) "none")) (:interfaces analysis)))
                "None discovered.")
              "\n\n## Class relationships\n\n"
              (table ["Class" "Extends" "Implements" "Source"]
                     (map #(vector (str "`" (:name %) "`") (str/join ", " (:extends %)) (str/join ", " (:implements %)) (:location %)) classes))
              "\n\n## Fields\n\n"
              (table ["Class" "Field" "Java type" "Kind" "Source"] (mapcat #(map (partial field-row %) (:fields %)) classes))
              "\n\n## Methods and constructors\n\n"
              (table ["Class" "Kind" "Name" "Returns" "Parameters" "Source"] (mapcat #(map (partial method-row %) (concat (:constructors %) (:methods %))) classes))
              "\n\n## Review notes\n\n"
              (if-let [notes (seq (analyze/review-notes analysis))]
                (str/join "\n" (map #(str "- " %) notes))
                "- No automatic review notes.")
              "\n"))))
