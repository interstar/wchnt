(ns wchnt-lang.targets.testharness-live-expr
  "Evaluate %assert host expressions against live fixtures.
   Supports names, numbers, true/false, new Class(...), .field, .method(...), == / !=."
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.interpret :as interpret]))

(def ^:private expr-parser
  (insta/parser
   "Expr = Equality
    Equality = Call (EqOp Call)?
    EqOp = '==' | '!='
    Call = Primary Suffix*
    <Suffix> = Member | CallArgs
    Member = <'.'> Name
    CallArgs = <'('> ArgList? <')'>
    <ArgList> = Expr (<','> Expr)*
    <Primary> = New | Name | Number | Bool | <'('> Expr <')'>
    New = <'new'> Name CallArgs
    Bool = 'true' | 'false'
    Name = #'[A-Za-z_][A-Za-z0-9_]*'
    Number = #'-?[0-9]+'"
   :auto-whitespace :standard))

(defn- parse-number
  [s]
  #?(:clj (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(defn- transform
  [ast]
  (insta/transform
   {:Name str
    :Number parse-number
    :Bool (fn [s] (= s "true"))
    :EqOp identity
    :Member (fn [name] {:member name})
    :CallArgs (fn [& args] {:call (vec args)})
    :New (fn [class-name call-args]
           {:new class-name :args (:call call-args)})
    :Primary identity
    :Call (fn [head & suffixes]
            {:chain head :suffixes (vec suffixes)})
    :Equality (fn ([left] left)
                  ([left op right] {:eq op :left left :right right}))
    :Expr identity}
   ast))

(defn parse-expr
  [text]
  (let [ast (expr-parser text)]
    (when (insta/failure? ast)
      (throw (ex-info (str "%assert expression parse error: " text)
                      {:text text :failure (pr-str ast)})))
    (transform ast)))

(declare eval-expr)

(defn- env-get
  [env name]
  (if (contains? env name)
    (get env name)
    (throw (ex-info (str "Unknown name '" name "' in %assert")
                    {:name name}))))

(defn- eval-primary
  [head ctx env]
  (cond
    (string? head) (env-get env head)
    (number? head) head
    (boolean? head) head
    (and (map? head) (contains? head :new))
    (interpret/construct-object
     (:schema-ir ctx) (:new head)
     (mapv #(eval-expr % ctx env) (:args head)))
    (and (map? head) (or (contains? head :chain) (contains? head :eq)))
    (eval-expr head ctx env)
    :else (throw (ex-info "Bad %assert primary" {:head head}))))

(defn- apply-suffixes
  "Fold .name and .name(...) suffixes. A Member followed by CallArgs is a method."
  [head suffixes ctx env]
  (loop [acc (eval-primary head ctx env)
         remaining suffixes]
    (if (empty? remaining)
      acc
      (let [s (first remaining)
            nxt (second remaining)]
        (cond
          (and (:member s) nxt (contains? nxt :call))
          (recur (interpret/call (:schema-ir ctx) (:methods-ir ctx) acc
                                 (:member s)
                                 (mapv #(eval-expr % ctx env) (:call nxt)))
                 (nnext remaining))

          (:member s)
          (recur (interpret/get-field (:schema-ir ctx) acc (:member s))
                 (next remaining))

          :else
          (throw (ex-info "Bad %assert suffix sequence" {:suffix s})))))))

(defn eval-expr
  [expr ctx env]
  (cond
    (and (map? expr) (contains? expr :eq))
    (let [left (eval-expr (:left expr) ctx env)
          right (eval-expr (:right expr) ctx env)
          ok? (= left right)]
      (if (= "==" (:eq expr)) ok? (not ok?)))

    (and (map? expr) (contains? expr :chain))
    (apply-suffixes (:chain expr) (:suffixes expr) ctx env)

    :else
    (eval-primary expr ctx env)))

(defn eval-assert-expr
  "Return boolean result of an assert expression text."
  [text ctx env]
  (let [result (eval-expr (parse-expr (str/trim text)) ctx env)]
    (if (boolean? result)
      result
      (throw (ex-info (str "%assert expression must be boolean, got: " (pr-str result))
                      {:text text :result result})))))
