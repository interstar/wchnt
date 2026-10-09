#!/usr/bin/env bb

(ns project-lint.backend-coverage
  "Check that every backend handles the whole method-body IR vocabulary.

   ir_vocabulary.edn lists the expression kinds and built-in methods. This
   script reads source as data (no compiler load) and checks:
     1. the front end produces exactly that vocabulary, so the file stays true;
     2. each backend's dispatch handles every entry in it."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [edamame.core :as edamame]))

(def project-root
  (-> *file* java.io.File. .getParentFile .getParentFile .getCanonicalPath))

(def vocabulary
  (edamame/parse-string (slurp (str project-root "/project_lint/ir_vocabulary.edn"))))

(def producer
  "Where the front end builds method-body IR, and the built-in call entry."
  {:file "src/wchnt_lang/reaction.cljc"
   :builtin-root 'builtin-call})

(def consumers
  "Each backend's expression dispatcher; its call helpers are found from it."
  [{:name "Haxe" :file "src/wchnt_lang/targets/haxe_backend.cljc"
    :root 'expr-ir-to-haxe}
   {:name "Smalltalk" :file "src/wchnt_lang/targets/smalltalk.cljc"
    :root 'expression}
   {:name "Interpreter (live)" :file "src/wchnt_lang/interpret.cljc"
    :root 'eval-expr}])

;; --- reading source ----------------------------------------------------------

(defn- read-forms
  [file]
  (edamame/parse-string-all (slurp (str project-root "/" file))
                            {:all true :read-cond :allow :features #{:clj}
                             :auto-resolve name}))

(defn- defns
  "Top-level defn / defn- forms by name."
  [forms]
  (into {}
        (keep (fn [form]
                (when (and (seq? form) (#{'defn 'defn-} (first form)))
                  [(second form) form])))
        forms))

(defn- subforms
  [form]
  (tree-seq coll? seq form))

(defn- dispatcher?
  "A function that switches on a node's kind: (case (:expr x) ...) in a
   backend, or ast-utils/node-type? in the front end's AST converter."
  [form]
  (some #(or (and (seq? %) (= 'case (first %))
                  (seq? (second %)) (= :expr (first (second %))))
             (= 'ast-utils/node-type? %))
        (subforms form)))

(defn- reachable
  "Names of functions in the same file reachable from root. Other dispatchers
   are not entered, so the walk stays inside the root's own handling instead
   of recursing back over every expression."
  [fns root]
  (loop [seen #{} todo [root]]
    (if-let [name (first todo)]
      (if (or (seen name) (not (fns name)))
        (recur seen (rest todo))
        (let [form (fns name)
              enter? (or (= name root) (not (dispatcher? form)))
              callees (when enter? (filter #(and (symbol? %) (fns %)) (subforms form)))]
          (recur (if enter? (conj seen name) seen) (concat (rest todo) callees))))
      seen)))

;; --- facts about a form ------------------------------------------------------

(defn- ref-to?
  "x is the symbol k, or (k something), e.g. method / (:method expr)."
  [k x]
  (or (= x (symbol (name k)))
      (and (seq? x) (= k (first x)))))

(defn- compared
  "The literal compared with a k-reference in (= a b) or (not= a b), or nil."
  [k form]
  (when (and (seq? form) (#{'= 'not=} (first form)) (= 3 (count form)))
    (let [[_ a b] form]
      (cond (ref-to? k a) b
            (ref-to? k b) a))))

(defn- case-tests
  "Test constants of (case <k-ref> ...), or nil."
  [k form]
  (when (and (seq? form) (= 'case (first form)) (ref-to? k (second form)))
    (let [clauses (drop 2 form)
          paired (if (odd? (count clauses)) (butlast clauses) clauses)]
      (mapcat #(if (seq? %) % [%]) (take-nth 2 paired)))))

(defn- contains-tests
  "Members of (contains? #{...} <k-ref>), or nil."
  [k form]
  (when (and (seq? form) (= 'contains? (first form))
             (set? (second form)) (ref-to? k (nth form 2 nil)))
    (second form)))

(defn- literals-for
  [k form]
  (concat (case-tests k form)
          (contains-tests k form)
          (when-let [v (compared k form)] [v])))

(defn- on-guard
  "[method on] for (and ... (= on \"Y\") ... (= method \"x\") ...), or nil."
  [form]
  (when (and (seq? form) (= 'and (first form)))
    (let [on (some #(compared :on %) (rest form))
          method (some #(compared :method %) (rest form))]
      (when (and (string? on) (string? method))
        [method on]))))

(defn- facts
  "Expression kinds, method names, and :on-guarded methods a form handles.
   A guarded (and ...) is not searched further: its method only counts when
   the guard can match."
  [form]
  (if-let [guard (on-guard form)]
    [{:guard guard}]
    (concat (when (seq? form)
              (concat (map (fn [k] {:kind k}) (filter keyword? (literals-for :expr form)))
                      (map (fn [m] {:method m}) (filter string? (literals-for :method form)))))
            (when (coll? form) (mapcat facts form)))))

(defn- handled
  [{:keys [file root]}]
  (let [fns (defns (read-forms file))]
    (when-not (fns root)
      (throw (ex-info (str "Dispatcher " root " not found in " file) {:file file})))
    (let [all (mapcat (comp facts fns) (reachable fns root))]
      {:kinds (set (keep :kind all))
       :methods (set (keep :method all))
       :guards (set (keep :guard all))})))

;; --- vocabulary --------------------------------------------------------------

(def vocab-kinds (set (keys (:expressions vocabulary))))
(def vocab-methods (set (map :method (:builtins vocabulary))))
(def vocab-on-tags (set (keep (fn [{:keys [method on]}] (when on [method on]))
                              (:builtins vocabulary))))
(def vocab-signatures (set (map (juxt :receiver :method :arity) (:builtins vocabulary))))

;; --- producer checks ---------------------------------------------------------

(defn- expr-literals
  "Map literals {:expr <kw> ...} anywhere in the producer."
  [forms]
  (filter #(and (map? %) (keyword? (:expr %))) (mapcat subforms forms)))

(defn- expect-arity-literals
  "[receiver method arity] from (expect-arity \"R\" \"m\" n args) calls."
  [forms]
  (keep (fn [f]
          (when (and (seq? f) (= 'expect-arity (first f)))
            (let [[_ r m n] f]
              (when (and (string? r) (string? m) (int? n)) [r m n]))))
        (mapcat subforms forms)))

(defn- producer-facts
  [{:keys [file builtin-root]}]
  (let [forms (read-forms file)
        fns (defns forms)
        builtin-forms (map fns (reachable fns builtin-root))
        nodes (expr-literals forms)]
    {:kinds (set (map :expr nodes))
     :methods (set (filter string? (mapcat #(mapcat (partial literals-for :method) (subforms %))
                                           builtin-forms)))
     :on-tags (set (keep (fn [{:keys [expr method on]}]
                           (when (and (= :call expr) (string? method) (string? on))
                             [method on]))
                         nodes))
     :signatures (set (expect-arity-literals builtin-forms))}))

(defn- names
  [xs]
  (str/join ", " (sort (map #(if (vector? %) (str/join "/" %) (str %)) xs))))

(defn- difference-error
  [label xs]
  (when (seq xs) (str label ": " (names xs))))

(defn- check-producer
  []
  (let [{:keys [kinds methods on-tags signatures]} (producer-facts producer)
        where (:file producer)]
    (remove nil?
            [(difference-error (str "expression kinds produced in " where " but not in ir_vocabulary.edn")
                               (set/difference kinds vocab-kinds))
             (difference-error (str "vocabulary expression kinds never produced in " where)
                               (set/difference vocab-kinds kinds))
             (difference-error (str "built-in methods in " where " but not in ir_vocabulary.edn")
                               (set/difference methods vocab-methods))
             (difference-error (str "vocabulary built-in methods never produced in " where)
                               (set/difference vocab-methods methods))
             (difference-error "built-in :on tags differ from ir_vocabulary.edn (method/on)"
                               (set/union (set/difference on-tags vocab-on-tags)
                                          (set/difference vocab-on-tags on-tags)))
             (difference-error "expect-arity receiver/method/arity missing from ir_vocabulary.edn"
                               (set/difference signatures vocab-signatures))])))

;; --- backend checks ----------------------------------------------------------

(defn- check-consumer
  [consumer]
  (let [{:keys [kinds methods guards]} (handled consumer)
        dead (remove vocab-on-tags guards)
        live-methods (into methods (map first (filter vocab-on-tags guards)))
        missing-methods (set/difference vocab-methods live-methods (set (map first dead)))
        label (:name consumer)]
    {:name label
     :errors (remove nil?
                     [(difference-error (str label " does not handle expression kinds")
                                        (set/difference vocab-kinds kinds))
                      (difference-error (str label " has no case for built-in methods (generic call instead)")
                                        missing-methods)
                      (difference-error (str label " branches test an :on tag the IR never sets, so never match (method/on)")
                                        dead)])}))

;; --- report ------------------------------------------------------------------

(defn- report!
  [heading errors]
  (if (seq errors)
    (doseq [e errors] (println (str "ERROR " e)))
    (println (str "OK " heading))))

(let [producer-errors (check-producer)
      results (map check-consumer consumers)]
  (report! (str "IR vocabulary matches front end (" (count vocab-kinds) " expression kinds, "
                (count vocab-methods) " built-in methods)")
           producer-errors)
  (doseq [{:keys [name errors]} results]
    (report! (str "backend coverage: " name) errors))
  (when (or (seq producer-errors) (some (comp seq :errors) results))
    (System/exit 1)))
