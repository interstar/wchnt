(ns wchnt-lang.examples)

(defn example-game-schema
  "Returns an example WCHNT schema for a simple game."
  []
  "Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = int/x int/y int/dx int/dy int/rad
Paddle = int/x int/y
Rect = int/x int/y int/width int/height")

(defn example-person-schema
  "Returns an example WCHNT schema with arrays."
  []
  "Person = String/name [Address]/addresses
Address = String/street String/city String/zipCode")

(defn example-shape-schema
  "Returns an example WCHNT schema with interface disjunctions."
  []
  "Shape = Triangle | Circle
Triangle = int/base int/height
Circle = int/radius")

(defn example-complex-schema
  "Returns a more complex example combining multiple features."
  []
  "Game = PlayArea Ball [Team] [Player]
PlayArea = Rect
Rect = int/x int/y int/width int/height
Ball = Football | Rugbyball
Football = int/x int/y int/radius
Rugbyball = int/x int/y int/xrad int/yrad
Player = int/x int/y String/name
Team = [Player]") 