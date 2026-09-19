(ns wchnt-lang.targets.live-js
  "Tiny JavaScript subset for %canvas %init/%step. Not a JS engine.
   Enough for bounce_canvas.wcn; unknown syntax fails fast."
  (:require [instaparse.core :as insta]
            [wchnt-lang.js-view :as js-view]))

(def ^:private js-parser
  (insta/parser
   "Program = Top*
    <Top> = VarDecl | FnDecl
    FnDecl = <'function'> Name <'('> <')'> Block
    Block = <'{'> Stmt* <'}'>
    <Stmt> = VarInit | Assign | CallStmt
    VarDecl = <'var'> Name <';'>
    VarInit = <'var'> Name <'='> Expr <';'>
    Assign = LValue <'='> Expr <';'>
    CallStmt = Expr <';'>
    LValue = Name Member*
    Expr = Primary Suffix*
    <Suffix> = Member | CallArgs
    Member = <'.'> Name
    CallArgs = <'('> ArgList? <')'>
    <ArgList> = Expr (<','> Expr)*
    <Primary> = Name | Number
    Name = #'[A-Za-z_][A-Za-z0-9_]*'
    Number = Hex | Dec
    Hex = #'0x[0-9a-fA-F]+'
    Dec = #'[0-9]+'"
   :auto-whitespace :standard))

(defn- parse-dec
  [s]
  #?(:clj (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(defn- parse-hex
  [s]
  #?(:clj (Long/parseLong (subs s 2) 16)
     :cljs (js/parseInt (subs s 2) 16)))

(defn- transform
  [ast]
  (insta/transform
   {:Name str
    :Hex (fn [s] (parse-hex s))
    :Dec (fn [s] (parse-dec s))
    :Number identity
    :Member (fn [name] {:member name})
    :CallArgs (fn [& args] {:call (vec args)})
    :Expr (fn [head & suffixes] {:chain head :suffixes (vec suffixes)})
    :LValue (fn [name & members] (into [name] (map :member members)))
    :Assign (fn [lval expr] {:assign lval :expr expr})
    :VarDecl (fn [name] {:var name})
    :VarInit (fn [name expr] {:var-init name :expr expr})
    :CallStmt (fn [expr] {:do expr})
    :FnDecl (fn [name block] {:fn name :body block})
    :Block (fn [& stmts] (vec stmts))
    :Program (fn [& items] (vec items))}
   ast))

(defn parse-js
  [text]
  (let [ast (js-parser text)]
    (when (insta/failure? ast)
      (throw (ex-info (str "Target JS parse error: " (pr-str ast))
                      {:text text :failure ast})))
    (vec (transform ast))))

(defn- env-get
  [env name]
  (if (contains? @env name)
    (get @env name)
    (throw (ex-info (str "Unknown name '" name "' in Target JS")
                    {:name name}))))

(defn- invoke
  [callee args]
  (if (fn? callee)
    (apply callee args)
    (throw (ex-info "Target JS tried to call a non-function"
                    {:callee callee}))))

(defn- eval-primary
  [head env]
  (cond
    (string? head) (env-get env head)
    (number? head) head
    :else (throw (ex-info "Bad JS primary" {:head head}))))

(defn- eval-expr
  [expr env]
  (if (and (map? expr) (:chain expr))
    (reduce (fn [acc suffix]
              (cond
                (:member suffix) (js-view/js-get acc (:member suffix))
                (:call suffix) (invoke acc (mapv #(eval-expr % env)
                                                 (:call suffix)))
                :else (throw (ex-info "Bad JS suffix" {:suffix suffix}))))
            (eval-primary (:chain expr) env)
            (:suffixes expr))
    (eval-primary expr env)))

(defn- eval-stmt
  [stmt env]
  (cond
    (:var stmt)
    (swap! env assoc (:var stmt) nil)

    (:var-init stmt)
    (swap! env assoc (:var-init stmt) (eval-expr (:expr stmt) env))

    (:assign stmt)
    (let [lval (:assign stmt)]
      (when-not (= 1 (count lval))
        (throw (ex-info "Target JS can only assign to a bare name"
                        {:lval lval})))
      (swap! env assoc (first lval) (eval-expr (:expr stmt) env)))

    (:do stmt)
    (eval-expr (:do stmt) env)

    :else
    (throw (ex-info "Unsupported Target JS statement" {:stmt stmt}))))

(defn- eval-top
  [item env]
  (cond
    (:fn item)
    (swap! env assoc (:fn item) (:body item))

    (:var item)
    (swap! env assoc (:var item) nil)

    :else
    (eval-stmt item env)))

(defn eval-script
  "Load top-level var/function declarations into env."
  [text env]
  (doseq [item (parse-js text)]
    (eval-top item env))
  env)

(defn call-js-fn
  "Run a function previously declared in env (body is a statement vector)."
  [env name]
  (let [body (env-get env name)]
    (when-not (vector? body)
      (throw (ex-info (str "'" name "' is not a Target function")
                      {:name name :value body})))
    (doseq [stmt body]
      (eval-stmt stmt env))))
