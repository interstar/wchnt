(ns wchnt-java-analysis.model)

(defn source-location [{:keys [file line column]}]
  (str file (when line (str ":" line (when column (str ":" column))))))

(defn empty-analysis [] {:files [] :classes [] :interfaces [] :errors []})
