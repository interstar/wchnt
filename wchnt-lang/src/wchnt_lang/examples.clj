(ns wchnt-lang.examples)

(defn example-game-schema
  "Returns an example WCHNT schema for a simple game."
  []
  "Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Paddle = Int/x Int/y
Rect = Int/x Int/y Int/width Int/height")

(defn example-person-schema
  "Returns an example WCHNT schema with arrays."
  []
  "Person = String/name [Address]/addresses
Address = String/street String/city String/zipCode")

(defn example-shape-schema
  "Returns an example WCHNT schema with interface disjunctions."
  []
  "Shape = Triangle | Circle
Triangle = Int/base Int/height
Circle = Int/radius")

(defn example-complex-schema
  "Returns a more complex example combining multiple features."
  []
  "Game = PlayArea Ball [Team] [Player]
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Football | Rugbyball
Football = Int/x Int/y Int/radius
Rugbyball = Int/x Int/y Int/xrad Int/yrad
Player = Int/x Int/y String/name
Team = [Player]") 