(ns wchnt-lang.targets.stdlib-signatures
  "The WCHNT standard-library API contract.

   This file contains declarations only. Target implementations live in the
   Haxe and live standard libraries; project lint checks those implementations
   against this contract.")

(def signatures
  {"WCHNTMaths"
   {"rand" {:args [] :return "Float"}
    "pi" {:args [] :return "Float"}
    "randInt" {:args ["Int"] :return "Int"}
    "sin" {:args ["Float"] :return "Float"}
    "cos" {:args ["Float"] :return "Float"}
    "tan" {:args ["Float"] :return "Float"}
    "asin" {:args ["Float"] :return "Float"}
    "acos" {:args ["Float"] :return "Float"}
    "atan" {:args ["Float"] :return "Float"}
    "atan2" {:args ["Float" "Float"] :return "Float"}
    "abs" {:args ["Float"] :return "Float"}
    "floor" {:args ["Float"] :return "Int"}
    "ceil" {:args ["Float"] :return "Int"}
    "round" {:args ["Float"] :return "Int"}
    "sqrt" {:args ["Float"] :return "Float"}
    "log" {:args ["Float"] :return "Float"}
    "exp" {:args ["Float"] :return "Float"}
    "pow" {:args ["Float" "Float"] :return "Float"}
    "min" {:args ["Float" "Float"] :return "Float"}
    "max" {:args ["Float" "Float"] :return "Float"}
    "hsv" {:args ["Float" "Float" "Float"] :return "Int"}}

   "WCHNTGraphics"
   {"color" {:overloads [{:args ["Int"] :return "Int"}
                          {:args ["Int" "Int" "Int"] :return "Int"}
                          {:args ["Int" "Int" "Int" "Int"] :return "Int"}]}
    "red" {:args ["Int"] :return "Int"}
    "green" {:args ["Int"] :return "Int"}
    "blue" {:args ["Int"] :return "Int"}
    "alpha" {:args ["Int"] :return "Int"}
    "background" {:overloads [{:args ["Int"]}
                               {:args ["Int" "Float"]}]
                  :return "WCHNTGraphics" :fluent true}
    "clear" {:args [] :return "WCHNTGraphics" :fluent true}
    "beginFill" {:overloads [{:args ["Int"]}
                              {:args ["Int" "Float"]}]
                 :return "WCHNTGraphics" :fluent true}
    "endFill" {:args [] :return "WCHNTGraphics" :fluent true}
    "lineStyle" {:overloads [{:args []}
                              {:args ["Float" "Int"]}
                              {:args ["Float" "Int" "Float"]}]
                 :return "WCHNTGraphics" :fluent true}
    "noStroke" {:args [] :return "WCHNTGraphics" :fluent true}
    "moveTo" {:args ["Float" "Float"] :return "WCHNTGraphics" :fluent true}
    "lineTo" {:args ["Float" "Float"] :return "WCHNTGraphics" :fluent true}
    "drawLine" {:args ["Float" "Float" "Float" "Float"]
                :return "WCHNTGraphics" :fluent true}
    "drawRect" {:args ["Float" "Float" "Float" "Float"]
                :return "WCHNTGraphics" :fluent true}
    "drawCircle" {:args ["Float" "Float" "Float"]
                  :return "WCHNTGraphics" :fluent true}
    "drawEllipse" {:args ["Float" "Float" "Float" "Float"]
                   :return "WCHNTGraphics" :fluent true}
    "fillText" {:args ["String" "Float" "Float"]
                :return "WCHNTGraphics" :fluent true}}

   "WCHNTConsole"
   {"format" {:args ["Any"] :return "String"}
    "print" {:args ["Any"] :return "WCHNTConsole" :fluent true}
    "println" {:args ["Any"] :return "WCHNTConsole" :fluent true}
    "clear" {:args [] :return "WCHNTConsole" :fluent true}}

   "WCHNTInput"
   {"mouseX" {:args [] :return "Int"}
    "mouseY" {:args [] :return "Int"}
    "mouseNX" {:args [] :return "Float"}
    "mouseNY" {:args [] :return "Float"}
    "mouseDown" {:args [] :return "Bool"}
    "keyDown" {:args ["String"] :return "Bool"}
    "keyPresses" {:args [] :return "Array<String>"}
    "attach" {:args [] :return "WCHNTInput" :fluent true}
    "detach" {:args [] :return "WCHNTInput" :fluent true}
    "focus" {:args [] :return "WCHNTInput" :fluent true}}

   "WCHNTForm"
   {"mount" {:args ["Any"] :return "WCHNTForm" :fluent true}
    "value" {:args ["String"] :return "String"}
    "number" {:args ["String"] :return "Float"}
    "pollEvents" {:args [] :return "Array<FormEvent>"}
    "graphics" {:args ["String"] :return "WCHNTGraphics"}
    "clear" {:args [] :return "WCHNTForm" :fluent true}}})
