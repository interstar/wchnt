(ns wchnt-lang.debug-pollution-parse
  (:require [clojure.test :refer :all]
            [wchnt-lang.mainfile :as m]))

(deftest user-pollution-document-parses-schema
  (let [content "# Pollution Game

A simple arcade game.

## Schema

This schema defines all the classes used in the game. 

### Notes
* `Keys` is a mailbox object

```
>Keys = Bool/left Bool/right Bool/up Bool/down
Game = PlayArea Player [Pollutant]/pollutants Int/score Int/spawnTier Int/spawnEvery $Time Keys
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Player = Int/x Int/y Int/dx Int/dy Int/rad
Pollutant = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t
```

## Construction

### Notes
* shorthand example

```
[:Game
  [:PlayArea [0 0 800 600]]
  [:Player 400 300 5 0 20]
  [:Array/Pollutant]
  0
  0
  400
  [:Time 0]
  [:Keys false false false false]
]
```

## Methods

Notes :
* method notes

```
Time::update! = { [:Time (t + 1)] }
```

## Target

```
%canvas

%init
function init() { assemblage = GameAssemblage.factory(); }

%step
function step() { assemblage.time.update_mutates(); }
```
"
        result (m/parse-mainfile content)]
    (is (:success result) (first (:errors result)))
    (is (not-empty (:schema (:value result))))))
