(ns live.embed
  "Compile-time example sources for Load example.")

(defmacro example-source
  [name]
  (slurp (str "live-examples/" name ".wcn")))

(defmacro blank-source
  []
  "# Welcome\n\nCreate pages with **New**. Open existing pages with **Go**. Link them with [[PageName]].\n")
