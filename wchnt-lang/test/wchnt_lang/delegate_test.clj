(ns wchnt-lang.delegate-test
  "Delegation (+): schema relationship, field/method promotion, must-override."
  (:require [clojure.test :refer :all]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.grammars :as grammars]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.reaction :as reaction]
            [wchnt-lang.schema :as schema]))

(defn- schema-ir
  [schema-text]
  (let [cargo (parser/schema-wchnt->schema-ast schema-text)]
    (is (:success cargo) (str (first (:errors cargo))))
    (ast-to-ir/schema-ast-to-ir (:value cargo))))

(defn- methods-ir
  [schema-text reaction-text]
  (reaction/reaction-ast-to-ir (grammars/parse-reaction reaction-text)
                               (schema-ir schema-text)))

(def person-schema
  "Person = BasePerson | Student
BasePerson = String/name Int/age
Student = String/id +BasePerson")

(def person-schema-no-sum
  "BasePerson = String/name Int/age
Student = String/id +BasePerson")

(defn- person-page
  [methods]
  (str "# delegate\n## Schema\n```\n" person-schema-no-sum
       "\n```\n## Construction\n```\n"
       "[:Student \"s17\" [:BasePerson \"Ada\" 36]]\n"
       "```\n## Methods\n```\n" methods "\n```\n"))

(deftest schema-records-delegate-component
  (testing "+BasePerson is an owned delegate slot named basePerson"
    (let [ir (schema-ir person-schema-no-sum)
          student (ir/find-assemblage ir "Student")
          slot (first (filter #(= :delegate (:relationship %))
                              (:components student)))]
      (is (schema/valid-schema-ir? ir))
      (is (= "id" (:component-name (first (:components student)))))
      (is (= "basePerson" (:component-name slot)))
      (is (= "BasePerson" (:type-name slot))))))

(deftest schema-rejects-delegate-primitive
  (testing "+Int is not a schema class"
    (is (thrown-with-msg? Exception #"schema class"
                          (schema-ir "Student = +Int/n\n")))))

(deftest schema-rejects-delegate-interface
  (testing "cannot delegate to a sum type"
    (is (thrown-with-msg? Exception #"interface"
                          (schema-ir (str "Person = BasePerson | Student\n"
                                          "BasePerson = String/name\n"
                                          "Student = +Person String/id\n"))))))

(deftest schema-rejects-field-shadow
  (testing "Student cannot declare a field that a delegate already has"
    (is (thrown-with-msg? Exception #"shadows"
                          (schema-ir (str "BasePerson = String/name Int/age\n"
                                          "Student = +BasePerson String/name\n"))))))

(deftest schema-rejects-two-delegate-field-clash
  (testing "two delegates cannot share a field name"
    (is (thrown-with-msg? Exception #"same field"
                          (schema-ir (str "A = String/name\n"
                                          "B = String/name Int/n\n"
                                          "C = +A +B\n"))))))

(deftest schema-rejects-cyclic-delegate
  (testing "A class cannot delegate to itself or through a cycle"
    (is (thrown-with-msg? Exception #"(?i)cyclic"
                          (schema-ir "Student = +Student String/id\n")))
    (is (thrown-with-msg? Exception #"(?i)cyclic"
                          (schema-ir "A = +B\nB = +A\n")))))

(deftest promoted-field-in-method-ir
  (testing "bare name in Student methods is the path through the delegate"
    (let [methods (methods-ir person-schema-no-sum
                              (str "BasePerson::greet = { name }\n"
                                   "Student::label = { id + \" \" + name }\n"))
          label (first (filter #(= "label" (:method-name %)) methods))
          path? (fn [node]
                  (and (map? node)
                       (= :path (:expr node))
                       (= ["basePerson" "name"] (:fields node))))]
      (is (some path? (tree-seq coll? seq (:body label)))))))

(deftest write-path-promotes-delegate-fields
  (testing "[:Student | name = n] patches the inner BasePerson"
    (let [rename (first (filter #(and (= "Student" (:class %))
                                      (= "rename" (:method-name %)))
                                (methods-ir person-schema-no-sum
                                            (str "BasePerson::rename = { String/n | [:BasePerson n age] }\n"
                                                 "Student::rename = { String/n | [:Student | name = n] }\n"))))]
      (is (= :construct (get-in rename [:body :expr])))
      (is (= "Student" (get-in rename [:body :class-name])))
      (is (= 2 (count (get-in rename [:body :args])))))))

(deftest must-override-method-that-returns-delegate
  (testing "Student must define rename if BasePerson::rename returns BasePerson"
    (is (thrown-with-msg? Exception #"must define 'rename'"
                          (methods-ir person-schema-no-sum
                                      "BasePerson::rename = { String/n | [:BasePerson n age] }\n")))))

(deftest must-override-is-immediate
  (testing "GradStudent must override Student::rename, not BasePerson::greet"
    (let [schema (str "BasePerson = String/name Int/age\n"
                      "Student = +BasePerson String/id\n"
                      "GradStudent = +Student String/year\n")
          methods (str "BasePerson::greet = { name }\n"
                       "BasePerson::rename = { String/n | [:BasePerson n age] }\n"
                       "Student::rename = { String/n | [:Student | name = n] }\n")]
      (is (thrown-with-msg? Exception #"must define 'rename'"
                            (methods-ir schema methods)))
      (is (seq (methods-ir schema
                           (str methods
                                "GradStudent::rename = { String/n | [:GradStudent | name = n] }\n")))))))

(deftest collections-of-delegate-stay-promoted
  (testing "a method returning [BasePerson] does not force an override"
    (let [methods (methods-ir (str "BasePerson = String/name Int/age\n"
                                   "Student = +BasePerson String/id\n")
                              (str "BasePerson::kids = { [:Array/BasePerson [:BasePerson name age]] }\n"
                                   "Student::label = { name }\n"))]
      (is (some #(= "kids" (:method-name %)) methods)))))

(deftest method-may-override-delegate-method
  (testing "Student::greet may hide BasePerson::greet"
    (let [methods (methods-ir person-schema-no-sum
                              (str "BasePerson::greet = { name }\n"
                                   "Student::greet = { id }\n"))]
      (is (= "id" (get-in (first (filter #(and (= "Student" (:class %))
                                               (= "greet" (:method-name %)))
                                         methods))
                         [:body :name]))))))

(deftest method-cannot-shadow-promoted-field
  (testing "Student::name is a method colliding with BasePerson.name"
    (is (thrown-with-msg? Exception #"shadows field"
                          (methods-ir person-schema-no-sum
                                      "Student::name = { id }\n")))))

(deftest two-delegates-cannot-share-a-method
  (testing "two delegates with the same method name fail even if fields differ"
    (is (thrown-with-msg? Exception #"same method"
                          (methods-ir (str "A = String/x\nB = String/y\nC = +A +B\n")
                                      (str "A::greet = { x }\n"
                                           "B::greet = { y }\n"))))))

(deftest interface-return-does-not-force-override
  (testing "a method whose result is the Person sum does not force Student to override"
    (let [methods (methods-ir person-schema
                              (str "BasePerson::other = { Person/p | p }\n"
                                   "Student::asRole = { [:Student id basePerson] }\n"
                                   "Student::label = { name }\n"))]
      (is (some #(= "label" (:method-name %)) methods)))))

(deftest student-is-not-a-baseperson-slot
  (testing "a BasePerson-typed value cannot be a Student"
    (is (thrown-with-msg? Exception #"expected BasePerson"
                          (methods-ir (str "School = [BasePerson]/people\n"
                                           person-schema-no-sum)
                                      (str "School::bad = { Student/s | people.cons(s) }\n"))))))

(deftest interpret-promotes-fields-and-methods
  (testing "Student.label reads name; rename write-path keeps id"
    (let [page (person-page
                (str "BasePerson::greet = { name }\n"
                     "BasePerson::rename = { String/n | [:BasePerson n age] }\n"
                     "Student::rename = { String/n | [:Student | name = n] }\n"
                     "Student::label = { name }\n"))
          loaded (interpret/load-program page)
          root (:root loaded)
          greet (interpret/call (:schema-ir loaded) (:methods-ir loaded) root "greet" [])
          renamed (interpret/call (:schema-ir loaded) (:methods-ir loaded) root "rename" ["Grace"])]
      (is (= "Ada" (interpret/get-field (:schema-ir loaded) root "name")))
      (is (= "Ada" (interpret/call (:schema-ir loaded) (:methods-ir loaded) root "label" [])))
      (is (= "Ada" greet))
      (is (= "s17" (interpret/get-field renamed "id")))
      (is (= "Grace" (interpret/get-field (:schema-ir loaded) renamed "name"))))))
