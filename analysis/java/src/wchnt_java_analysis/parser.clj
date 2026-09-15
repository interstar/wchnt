(ns wchnt-java-analysis.parser
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wchnt-java-analysis.model :as model])
  (:import [com.github.javaparser StaticJavaParser]
           [com.github.javaparser.ast.body ClassOrInterfaceDeclaration FieldDeclaration MethodDeclaration ConstructorDeclaration Parameter]
           [com.github.javaparser.ast.type Type]
           [java.nio.file Files Path]))

(defn location [file node line-offset]
  (let [p (.getBegin node)]
    {:file (.getPath file) :line (when (.isPresent p) (+ line-offset (.line (.get p)))) :column (when (.isPresent p) (.column (.get p)))}))

(defn type-text [^Type type] (.asString type))
(defn visibility [node] (cond (.isPublic node) "public" (.isProtected node) "protected" (.isPrivate node) "private" :else "package"))
(defn parameter [^Parameter p] {:name (.getNameAsString p) :type (type-text (.getType p))})
(defn method-data [file node kind line-offset]
  {:name (.getNameAsString node)
   :kind kind
   :return-type (if (= kind "constructor") "" (type-text (.getType node)))
   :parameters (mapv parameter (.getParameters node))
   :visibility (visibility node)
   :signature (str (.getNameAsString node) "(" (str/join ", " (map #(str (:type %) " " (:name %)) (map parameter (.getParameters node)))) ")")
   :location (model/source-location (location file node line-offset))})
(defn field-data [file ^FieldDeclaration node line-offset]
  (let [v (.getElementType node)]
    (mapv (fn [declarator]
            {:name (.getNameAsString declarator) :type (type-text v) :static (.isStatic node) :final (.isFinal node)
             :visibility (visibility node) :location (model/source-location (location file node line-offset))})
          (.getVariables node))))
(defn declaration [file ^ClassOrInterfaceDeclaration node line-offset]
  {:name (.getNameAsString node)
   :interface? (.isInterface node)
   :abstract (.isAbstract node)
   :extends (mapv #(.getNameAsString %) (.getExtendedTypes node))
   :implements (mapv #(.getNameAsString %) (.getImplementedTypes node))
   :fields (vec (mapcat #(field-data file % line-offset) (.getFields node)))
   :static-fields (vec (filter :static (mapcat #(field-data file % line-offset) (.getFields node))))
   :constructors (mapv #(method-data file % "constructor" line-offset) (.getConstructors node))
   :methods (mapv #(method-data file % "method" line-offset) (.getMethods node))
   :location (model/source-location (location file node line-offset))})
(defn parse-file [file]
  (let [processing? (str/ends-with? (.getName file) ".pde")
        source (when processing? (slurp file))
        [imports body] (if processing?
                        (let [lines (str/split-lines source)
                              [is rest-lines] (split-with #(re-matches #"\s*import\s+.+;\s*" %) lines)]
                          [(str/join "\n" is) (str/join "\n" rest-lines)])
                        [nil nil])
        synthetic-name (-> (.getName file) (str/replace #"\.(java|pde)$" "") (str/replace #"[^A-Za-z0-9_]" "_"))
        cu (if processing?
             (StaticJavaParser/parse (str imports "\nclass " synthetic-name " {\n" body "\n}"))
             (StaticJavaParser/parse file))
        line-offset (if processing? -1 0)]
    (mapv #(declaration file % line-offset) (.findAll cu ClassOrInterfaceDeclaration))))
(defn java-files [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile %))
       (filter #(re-matches #"(?i).+\.(java|pde)" (.getName %)))
       sort))
(defn parse-tree [root]
  (reduce (fn [analysis file]
            (try
              (let [decls (parse-file file)]
                (-> analysis (update :files conj (.getPath file)) (update :classes into (remove :interface? decls)) (update :interfaces into (filter :interface? decls))))
              (catch Exception e
                (-> analysis (update :files conj (.getPath file)) (update :errors conj {:file (.getPath file) :message (.getMessage e)})))))
          (model/empty-analysis)
          (java-files root)))
