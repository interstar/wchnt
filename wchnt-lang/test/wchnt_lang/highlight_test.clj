(ns wchnt-lang.highlight-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.highlight :as highlight]))

(defn- bounce []
  (slurp "live-examples/bounce_canvas.wcn"))

(defn- texts
  [src spans kind]
  (->> spans
       (filter #(= kind (:kind %)))
       (map #(subs src (:start %) (:end %)))))

(deftest bounce-schema-class-names
  (testing "Schema definees and types are class/type spans in the buffer"
    (let [src (bounce)
          {:keys [spans errors]} (highlight/highlight src)]
      (is (empty? errors))
      (is (some #{"Game"} (texts src spans :class)))
      (is (some #{"PlayArea"} (texts src spans :class)))
      (is (some #{"Int"} (texts src spans :type)))
      (let [game (first (filter #(and (= :class (:kind %))
                                      (= "Game" (subs src (:start %) (:end %))))
                                spans))]
        (is (str/includes? (subs src 0 (:start game)) "## Schema"))
        (is (not (str/includes? (subs src 0 (:start game)) "## Construction")))))))

(deftest bounce-construction-and-methods
  (testing "Construction classes/numbers and Methods names/keywords"
    (let [src (bounce)
          {:keys [spans]} (highlight/highlight src)]
      (is (some #{"Game"} (texts src spans :class)))
      (is (some #{"800"} (texts src spans :number)))
      (is (some #{"bounceDx"} (texts src spans :method)))
      (is (some #{"if"} (texts src spans :keyword)))
      (is (some #{"or"} (texts src spans :keyword)))
      (is (some #{"else"} (texts src spans :keyword)))
      (is (not-any? #{"function"} (texts src spans :keyword))))))

(deftest methods-with-external-parameters-are-highlighted
  (testing "Methods with external parameters use WCHNT syntax highlighting"
    (let [src (str "# t\n## Methods\n\n```\n"
                   "Circle::draw = { @WCHNTGraphics/g | "
                   "if (radius > 0) { g } else { g } }\n```\n")
          {:keys [spans errors]} (highlight/highlight src)]
      (is (empty? errors) (pr-str errors))
      (is (some #{"draw"} (texts src spans :method)))
      (is (some #{"if"} (texts src spans :keyword)))
      (is (some #{"WCHNTGraphics"} (texts src spans :type))))))

(deftest broken-schema-keeps-last-good
  (testing "Parse failure keeps previous spans and marks the error"
    (let [src (bounce)
          bad (str/replace src #"Game = PlayArea Ball" "Game = !!!")
          ok (highlight/highlight src)
          now (highlight/highlight bad)
          merged (highlight/preserve ok now)
          err-msg (:message (first (:errors now)))]
      (is (seq (:errors now)))
      (is (= "schema" (:section (first (:errors now)))))
      (is (re-find #"schema:" err-msg))
      (is (re-find #"Expected" err-msg))
      (is (some #{"Game"} (texts src (:spans merged) :class)))
      (is (some #{"PlayArea"} (texts src (:spans merged) :class)))
      (is (empty? (filter #(= "schema" (:section %)) (:spans now)))))))

(deftest malformed-markdown-preserves-all
  (testing "Unclosed fence does not drop last good highlights"
    (let [src (bounce)
          ok (highlight/highlight src)
          now (highlight/highlight (str src "\n```\n"))
          merged (highlight/preserve ok now)]
      (is (seq (:errors now)))
      (is (= (:spans ok) (:spans merged))))))

(defn- schema-page
  [schema]
  (str "# t\n## Schema\n\n```\n" schema "\n```\n"))

(defn- kind-of
  [src spans token]
  (:kind (first (filter #(= token (subs src (:start %) (:end %))) spans))))

(deftest schema-relationship-colours
  (testing "sigil and type share a relationship kind; mailbox colours the definee"
    (let [src (schema-page
               (str ">Keys = Bool/left\n"
                    "Car = :Motor @Store $Clock +Person PlayArea\n"
                    "Motor = Int/n\n"
                    "Person = String/name\n"))
          {:keys [spans errors]} (highlight/highlight src)]
      (is (empty? errors) (pr-str errors))
      (is (= :rel-mailbox (kind-of src spans ">")))
      (is (= :rel-mailbox (kind-of src spans "Keys")))
      (is (= :rel-context (kind-of src spans ":")))
      (is (= :rel-context (kind-of src spans "Motor")))
      (is (= :rel-external (kind-of src spans "@")))
      (is (= :rel-external (kind-of src spans "Store")))
      (is (= :rel-reactive (kind-of src spans "$")))
      (is (= :rel-reactive (kind-of src spans "Clock")))
      (is (= :rel-delegate (kind-of src spans "+")))
      (is (= :rel-delegate (kind-of src spans "Person")))
      (is (= :type (kind-of src spans "PlayArea")))
      (is (= :class (kind-of src spans "Car"))))))
