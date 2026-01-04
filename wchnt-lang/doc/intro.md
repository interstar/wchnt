# Introduction to WCHNT Language

WCHNT (We CAN Have Nice Things) is a new object-oriented language for making coding easy and fun. It's designed to express "assemblages" of different object classes in a more intuitive way than traditional OO languages.

## Philosophy

WCHNT is based on the idea that we can express an entire assemblage of multiple interconnected classes at once in a single data-schema, using a Backus-Naur Format inspired grammar. Instead of defining classes one by one, we define the entire structure of our system in a declarative way. This gives us declarative rather than imperative data descriptions and construction. A single point where we can read and edit the shape of the assemblage of objects. 

In fact "assemblage programming" turns the whole shape and ordering of an OO program inside-out. A WCHNT program is a literate markdown file with 5 sections. Each of which defines one codeblock demarcated with backtick fences.

The 5 sections are :
- Schema
- Construction
- Reactive
- Imperative
- Target

### Class relationships in the Schema

The schema is a declaration of classes and how they interrelate. 

A simple example might be

```
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = Int/x Int/y Int/dx Int/dy Int/radius
Rect = Int/x Int/y Int/width Int/height
Paddle = Rect
```

This defines the structure of a simple Pong game in 5 classes

#### Ordinary Components

We add further information about the relationships between objects by using sigils to represent specific relationships.

Class names defined without a sigil represent "ordinary components".

An ordinary component is an object of a general purpose class. Ie. a class which may be used in various parts of the program. The object is presumed to belong to the parent object. It's constructed along with it. And its lifecycle is connected to it.

The instance variable representing the component gets, by default, the name of its class with lower-cased first letter. In other words inside the Game object, the PlayArea object is called playArea.

However, it's possible to give an alternative name to a variable using the /altName This can be used anywhere, but MUST be used for disambiguation when there are two components of the same type. Eg. in our example, the Game has two GamePaddle objects, so they each need to be given unique names. The same is true throughout the schema for Ints.

#### Context Specific Component

The first alternative relationship we introduce is the "context specific component"

```
Car = :Engine
```

The sigil used here is the colon : and it means that an object of this class MUST BELONG to an object of the parent class. In practice as a component, the Engine is constructed at the same time as the Car in the same construction block. But also the Engine gets an implicit instance variable called theCar, which is the reference to the context or parent which contains it.

WCHNT should not allow the creation of Engines in any other context. Or that theCar ever changes or doesn't exist.

Therefore methods of Engine can safely access properties and methods of theCar which forms their context. The philosophy of assemblage programming which is explored here is that assemblages, ie. small, tightly coupled collections of objects designed to work together, do not need the usual black-box / abstraction layer that hides all details of one from another. There is a role for that degree of data-hiding in the relation between one assemblage and another. But not between members of the same assemblage.

Example

Car = :Engine
Engine = Int/cc

Should become (in pseudo haxe)

class Car {
  var engine:Engine;
}

class Engine {
   var cc:Int;
   var theEngine:Engine;
   ...
   public function setContext(c: Car) {
      this.theCar = c;
   }
}



#### External 

The second relationship is the "external". This uses the "@" sigil. 

School = Address @Person/headteacher

In this case, we expect the Address is an ordinary component of the School. But the headteacher is an object of class Person which is lent to the school. There is no assumption that the lifecycle of this Person is tied to the lifecycle of the school. And we might expect that a reference to an existing Person was passed in the construction of the School.


#### Reactive Dependencies

The final relationship is reactive dependencies using an observable/subscriber pattern. The sigil here is $

Take 

Game = PlayArea Ball $Time

In this context, while PlayArea and Ball are components of the Game, the Time is taken to be an external and changable value. It is visible anywhere within the Game object using the name time. 

When a class is marked with the $ sigil, it becomes an "observable" class that maintains a list of subscribers. Like other classes in WCHNT, it only changes its value through its `update()` method. When the observable class updates itself, it automatically sends messages to all subscribers to call their own `update()` methods.

This creates a reactive chain where changes in one object automatically propagate to dependent objects. The exact implementation details are still being worked out, but the goal is to provide a clean way to express reactive dependencies without manual event handling.

See more about methods, particularly the update method, below.

#### Sum Type or Interfaces

We can define a "sum type" or "interface" in the schema by writing 

Shape = Circle | Triangle
Circle = Int/cx Int/cy Int/radius
Triangle = Int/base Int/height

In the target code, this will compile to an interface called "Shape", and two classes Circle and Triangle that implement it.

#### Enums

Options = "Blue" | "Green" | "Red" | "Yellow"

In the target code, this compiles to an Enum with those values

#### Collections

There are two standard collections, arrays (aka vectors, sequences, lists) and maps (aka dictionaries)

In the schema these can be represented as

Discipline = String/name @Person/teacher
School = [Student]/students {String:Discipline}/disciplines

When compiled, the variable students will be an array of objects of class Student. While disciplines will be a map of strings to objects of class Discipline


### Constructions

The next phase or section of a WCHNT program is the "contruction". Or we could say, the global construction.

A construction is a way to declare the initial values of an assemblage in one specific place. To make it visible and easy read and change. It's inspired by languages like Clojure which typically feature very plain, easy to read data-structure literals.

The full construction for the Game will look something like this

[:Game 
  [:PlayArea [:Rect 0 0 500 400] ] 
  [:Ball 200 200 1 1 5] 
  [:Paddle [:Rect 50 50 20 80]]
  [:Paddle [:Rect 430 50 20 80]] ]

Square brackets delimit objects. The first element is a label that indicates the class or type. The rest, the data values for the components, by position.

In order to maximize readability in constructions, a) newlines are meaningless whitespace. b) labels which can be meaningfully infered from the context are optional.

In other words, the Game construction could be as minimal as

[:Game 
  [[0 0 500 400] ] 
  [200 200 1 1 5] 
  [[50 50 20 80]]
  [[430 50 20 80]] ]

In practice we expect the programmer to choose a convenient balance of readability and intelligibility while eliminating too much visual noise.

For example

[:Game 
  [:PlayArea [0 0 500 400] ] 
  [:Ball 200 200 1 1 5] 
  [:Paddle [50 50 20 80]]
  [:Paddle [430 50 20 80]] ]


In the target language, the construction section of the wchnt program compiles down to a big "factory" function that builds the entire assemblage.

#### Collections

Say we have 

Person = String/name
Discipline = String/name @Person/teacher
School = [Student]/students {String:Discipline}/disciplines

A construction would look like

[:School 
  [:Array/Student
    [:Person "John Smith"]
    [:Person "Mary Doe"]] 
  [:Map/{String:Discipline} 
     "M1":[:Discipline "Maths 1":[:Person "Steve" ]],
     "E3":[:Discipline "English 3" [:Person "Mike"] ]
     ]  
    ]

As with the outermost class. The type labels of Arrays and Maps are NOT optional. The School construction could be reduced to 

[:School 
  [:Array/Student
    ["John Smith"]
    ["Mary Doe"]] 
  [:Map/{String:Discipline} 
     "M1":["Maths 1":["Steve" ]],
     "E3":["English 3":["Mike"]]
     ]
    ]

But no more. The other labels here are necessary.

#### Sum Types

Sum types are also necessary in construction. Eg.

Game = [Player]/players
Player = Name Shape
Shape = Circle | Triangle
Circle = Int/radius
Triangle = Int/base Int/height

Construction looks like

[:Game 
  [:Array/Player
    ["John" [:Circle 5]
    ["Alice" [:Triangle 4 8]]]]]

Note that class labales are not optional when the class can be one of several that instantiates the interface or sum-type.

#### Multi-Statement Constructions

WCHNT is an OO rather than functional programming language. But it tilts towards the ideal of immutability from FP. Mutation is intended to be constrained to specific parts of the code.

But sometimes we need to define some intermediate values during a construction and bind them to a name. Much like a let binding in Clojure or Lisp.

Any code block can consist of a single expression or be a multi-statement block with multiple assignment statements followed by a final expression. The full stop (period) is the statement separator and is the only way to bind intermediate names on the way to calculating a result.

```
players = [:Array/Person ["John"...] ...].
[:Team "Crystal Palace" players]
```

The name "players" is bound to an array of people once. It can not be updated. But can be referenced later in the construction.

A single expression can get quite complex - it can include sub-expressions which are constructions, arithmetic and logic expressions, other method calls, and control structures like ifs and loops. These are all expressions themselves. Complex expressions that do a lot of work without the statement separator (full stop) are just single complex expressions rather than sequences of bindings.



### Behaviour (Reaction and Imperative)

WCHNT is an OO language so behaviour is in the form of methods of classes which are invoked by sending messages to objects of those classes in a traditional way.

We want to restrict mutability though, so the first of our behaviour phases or sections of the wchnt program is the "Reaction".

In this section there is (almost) no mutation of objects. Methods are (almost) pure functions which return new data

The simplest example we can think of. 

In the Schema

Rect = Int/width Int/height

In the Reaction

Rect::area = { (width * height)}
 
The area method of the Rect takes no arguments, but has access to the instance variables of the Rect object.

Curly brackets delimit the code block in which we can put typical mathematical and logical expressions. And calls to other objects. But also constructions.

Rect::doubleWidth = {
   [:Rect x y (width * 2) height] 
}

This returns a new Rect object, double the width of the original. In fact, the construction section of the wchnt is nothing but a special global case of a code-block that delivers a construction. Constructions in methods follow the same rules as the construction section. Can include multi-statements and let bindings etc. Expressions are, in fact, available to use in the main construction.

In a multi-statement code-block, the value of the last statement is the return value. 

If the last statement happens to still be an assignment it still returns the assigned value eg. 

{
x = blah.
y = 43
}

will evaluate to 43

#### Blocks with arguments / lambdas

A code block demarcated by { } is like a block in Smalltalk. It's a first class citizen of the language. And can take arguments, becoming a lambda expression. Eg.

{Int/x | x * 2}

This block takes an argument and returns it multiplied by 2.

Methods are just code-blocks attached to objects.


Booster = Int/x

Booster::boost = {Int/y | (x * y)}

The boost method takes the argument y and multiplies it by the Booster's x field.

#### Control structures

Like Smalltalk, WCHNT uses code blocks to handle typical control structures like looping and conditions. Rather than building explicit control flow into the language like for loops and if statements, we achieve the same thing by passing code blocks to methods. These methods act as "combinators" for control flow.

The Boolean class will have methods like `booleanVal.true?(exp,exp)` and `booleanVal.false?(exp,exp)`

`true?` is a conditional operator: if the boolean is true, then return the first value, otherwise the second. `false?` is the opposite: if the boolean is false return the first, otherwise the second.

For example:
```
bool.true?({3+4}, {5*2})
```

Using blocks allows us to defer evaluation until we decide which branch we want. Without blocks, `bool.true?(3+4, 5*2)` would evaluate both expressions before passing them to the method.

Ints will have a `times(codeblock)` method for iteration.

And collections will have typical map, filter, reduce (or fold) type methods.

The exact implementation of blocks in the target language (Haxe) is still being worked out - whether to use Haxe's Lambda library or create wrapper classes to represent code blocks as objects. 

#### The update method.

The method update() is special in WCHNT. While WCHNT is generally immutable, the job of the update function is to mutate the object itself.

Therefore the update method of a class must return a construction for that class itself.

For example

Ball::update = {
  newdx = ((x < 0) or (x > theGame.playArea.width)).true?(-dx, dx)
  newdy = ((y < 0) or (y > theGame.playArea.width)).true?(-dy, dy)
  [:Ball (x + newdx) (y + newdy) newdx newdy radius]
}

The idea here is that this should be tied to the reactive dependencies we discussed in the Schema section earlier.

For example 

Game = PlayArea Ball $Time

will make the Time object observable and the Game object subscribed to it.

When the Time updates itself, the update method of the Game should be called automatically.

Game can obviously trigger update() in its components. I'm still open minded on the question as to whether, if Game has its update triggered automatically then context specific classes eg. Game = :Ball  would also have their update called automatically.

We still have to think how this will be implemented. It could be that in target code we have a special optimisation whereby the update() method doesn't create a new object of the class, but mutates the existing one in place. This will be an optimisation though. 

#### Target Commands

The final section or phase of a WCHNT program is related to the target output. It's here that special instructions for the particular target platform are made. 

The philosophy of WCHNT is inspired by Stewart Brand's notion of "shearing layers" - different parts of the program evolve at different speeds or have different ephemerality. WCHNT also brings things that are typically outside the program (and therefore awkward to manage) into the program itself.

You can think of both construction and target sections as alternatives to configuration files, but each represents a different shearing layer:
- The construction section contains initialization data intrinsic to the code in general
- The target section contains custom decisions for this program aiming for this target language on this target platform

Hypothetically, in future, we may compile to a low level language like C. And the Target may include special instructions that Arrays should be implemented as native C arrays rather than a higher level linked-list. 

A more near term use of the Target section would be something like this. We may want to trace what's happening in our program. But in different situations or platforms, this might involve printing to stdout. Or logging to a particular logging infrastructure etc.

We include the idea of Target Commands which are embedded in the construction and reaction language, but whose real meaning is confirmed in the target. These start with a percent sign.

So in a method we might write

%trace(x)

The Target section IS the configuration - it's where you specify that this trace should be expanded into `print` to the command line or `console.log` or a call to a special logging framework you have installed.
 
