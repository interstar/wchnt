(ns live.embed
  "Compile-time example sources for Load example.")

(defmacro example-source
  [name]
  (slurp (str "live-examples/" name ".wcn")))

(defmacro blank-source
  []
  "# Welcome\n\nSearch or create pages from the top box. Browse them under **Pages**. Link them with [[PageName]].\n")
