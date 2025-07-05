### Construction Phase

The construction phase of a WCHNT program comes directly after the schema definition.

I was thinking it would look something like Clojure's hiccup.

Let's say we have a Game schema like

Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = int/x int/y int/dx int/dy int/rad
Paddle = Rect/geometry
Rect = int/x int/y int/width int/height

A hiccup-like format for the construction phase would let us declare the initial value of the entire game assemblage like this.

[:Game [:PlayArea [:Rect 0 0 500 400] 
       [:Ball 100 100 1 1 5] 
       [:Paddle [:Rect 40 40 5 50]] 
       [:Paddle [:Rect 555 40 5 50]]
]


What you'll notice is that the vectors of data are positional, based on the order things come in the schema. The fact that these are positional and ordered means that the actual tag for the class name can be **optional**.

So, while this would be the most explicit and clearest representation, the same initial state given to the construction of the assemblage above could ALSO be written as

[[[0 0 500 400] 
 [100 100 1 1 5] 
 [[40 40 5 50]] 
 [[555 40 5 50]]
]

Now, in practice I'd expect programmers to use as much of the explicit tagging as they felt they needed.

For example, in practice we might want to write

[[:PlayArea [0 0 500 400] 
 [:Ball 100 100 1 1 5] 
 [:Paddle [40 40 5 50]] 
 [:Paddle [555 40 5 50]]
]

That communicates clearly what paramaters are given to what objects. But eliminates extra noise.

Does this make sense?

Let's think through a couple of further issues.

1) Could we eliminate what look like "unnecessary" extra brackets?

Eg. given that Paddles are only made of one Rect, could we write the constructor as [:Paddle 40 40 4 50] ?

No. I think that would make the representation more prone to error and confusing to read, while not adding much efficiency. So while class-names are optional, bracket structure is sacrosanct.

2) What about sum-types? / Interfaces?

I think these will always need to be explicit.

In other words if your schema is 

Game = Player/p1 Player/p2
Player = Shape
Shape = Triangle | Circle
Triangle = Point2D/p int/base int/height
Circle = Point2D/centre int/radius
Point2D = int/x int/y

Then our canonical construction should be

[:Game
   [:Player [:Triangle [:Point2D 10 10] 5 3]]
   [:Player [:Circle [:Point2D 100 100] 6]]]
   
Now this could be shortened to 

[[[:Triangle [10 10] 5 3]]
 [[:Circle [100 100] 6]]]

But NOT to 

[[[[10 10] 5 3]]
 [[[100 100] 6]]]

In other words, in the construction of the assemblage, whenever there's an interface, the concrete implementation of that needs to be explicit.

3) The goal of this language is always to optimise for both expressivity and readability / clarity.

So we should ALSO allow optional tagging of arguments with the explicit variable name.

Eg. if the schema is 

Ball = int/x int/y int/dx int/dy int/rad

And construction is canonically

[:Ball 0 0 1 1 5]

It should also be possible to write 

[:Ball 0 0 1/dx 1/dy 5/rad]

to label the arguments with the name that they will be bound to in the object. This is purely a convenience for the humans reading and writing the code.

BUT THIS DOES NOT OVERRIDE THE POSITIONAL REQUIREMENT

You can not write this as 

[:Ball 0 0 5/rad 1/dx 1/dy]

hoping that the names of the arguments determine which paramater they'll be bound to . You are still obliged to stick to the order declared in the schema.


4) What about collections like arrays and dictionaries?

We'll need a special notation for them. I'm not 100% sure about this. Still thinking.
  

Game = [Ball]/balls {int:Direction}/keys
Ball = int/x int/y int/dx int/dy int/radius
Direction = "Up" | "Down" | "Left" | "Right"

Then we have to write something like 

[:Game 
  [:Array/balls [:Ball 10 10 1 1 5] [:Ball 50 50 1 -1 5] [:Ball 100 100 -1 -1 5]]
  [:Map/keys {38:Up, 40:Down, 37:Left, 39:Right}]
] 

I'm still not completely committed to this notation. If you can see problems with it, or have good suggestions for alternatives, then I'd like to hear them.

5) Do the object or class relations we represent by sigils in the schema make any difference here? 

No, I don't think so. This is just giving the initial values to the whole assemblage. The type of relationship between the objects doesn't make any difference to that. (Except in the ordering of construction, which we'll come to in the next section.) We don't need to write the sigils on the names of the classes in the construction phase.

6) So what has been presented so far is the construction of an assemblage which is completely made of component classes. What about associations?

There's no special restrictions on constructing associations. EXCEPT when you want to declare objects that exist within a number of parent objects at the same time.

For that we'd need a multi-step construction. A multi-step construction works rather like the "let" form in Clojure and other Lisps.

Consider the schema : 

School = [@Person]/students
Person = Name Address

The construction will look something like

students =
[:Array [:Person "John Smith" "1 The Avenue"]
        [:Person "Jane Jones" "43 Long Street"]]
[:School students]

In other words, we can subassemblages to names, using the "name =" syntax. These subassemblages are precisely for objects which are not components of another object, and need to be reused in multiple places.

Note that the construction phase of our program is simply a sequence of these constructions of sub-assemblages or assemblages. The last assemblage in the sequence is assumed to the final assemblage which is "returned" by the construction phase. And will populate a global variable which is the root class of the construction.

Using the multi-step construction is not due to the object relationship being association. You could equally construct the assemblage like this

[:School [:Array/students [:Person "John Smith" "1 The Avenue"]
                          [:Person "Jane Jones" "43 Long Street"]]] 



However, if you write

Town = School FootballTeam
School = [@Person]/students
FootballTeam = [@Person]/players 
Person = Name Address

and then

[:Town
[:School [:Array/students [:Person "John Smith" "1 The Avenue"]
                          [:Person "Jane Jones" "43 Long Street"]]]
[:FootballTeam [:Array/players [:Person "John Smith" "1 The Avenue"] [:Person "Jane Jones" "43 Long Street"] ]]
]

Then the Person objects in the students list would be completely different from the Person objects in the players lists.

OTOH

people = 
[:Array [:Person "John Smith" "1 The Avenue"]
        [:Person "Jane Jones" "43 Long Street"]]
[:Town [:School people] [:FootballTeam people]]

Then the students and players would be the same people.

Does this make sense? 

7) The circularity in contex-specific components

Context specific components (starting with the colon sigil)

Car = :Engine

are special in the sense that this tells WCHNT that an Engine can only exist as a component of a Car. It can therefore see the Car it is part of by being given a default instance variable or attribute called contextCar. (This name is generated automatically)

This creates the problem of a circularity during construction. We can't create the Car without passing it an Engine. But we also can't create the Engine without passing it the Car. We will solve this with something like a Promise/Future/Thunk like mechanism. The construction will initially construct the Engine, passing it an object that represents something to become available later. By the time the construction is finished, the Car object will be available. 

Getting this exactly right is still going to be complicated. But I think conceptually this will work.

8) Talking of circularity, there's another issue of circularity which we haven't touched on.

What about recursive data structures? Eg. if a class needs to contain another object of the same class. I would like to have avoided this possibility, but it's essential if we want to be able to build trees. And I think any programming language needs to be able to build and manage trees recursively.

So ... how should we represent a tree in a) the schema, and b) in the construction phase? Well, there will need to be the equivalent of Haskell's 

data Tree a = Node a (Tree a) (Tree a) | Leaf

I think we can use a sum type. Something like this. 

Tree = _ | Node  
Node = int/data Tree/left Tree/right

Note I introduced the notation of _ to mean a class that has no components. We will not have Nulls in WCHNT. Null is NOT a "nice thing". This will have to be compiled slightly differently. We will compile this as Tree being an interface, and a class called _Tree having no instance variables, that implements it. 

To build an entire tree during the construction phase this would look something like

[:Node 4 _ [:Node 6 [:Node 3 _ _] _]]

Every occurance of _ in this construction will become an instance of _Tree.

It will, of course, be possible to construct some new objects at run time, for tree-building algorithms etc. But we'll discuss them when we reach the details of the "Behaviour" phase of the program.

9. There is also a type of circularity between two mutually associating classes.

For example, imagine the schema

Class1 = Stuff1 #Class2
Class2 = Stuff2 #Class1

We've given each class an associate reference to a member of the other class. 

But this would raise a serious problem in construction as each would seem to need the other to be constructed.

I'm thinking that most of the time this requirement would arise, the classes would share a common context, and it would be simpler to access each other via that

Eg. 

Parent = :Child1 :Child2
Child1 = Stuff1
Child2 = Stuff2

Now if Child1 needs to access Stuff2, and Chid2 needs to access Stuff1, rather than having an explicit associate reference to each other, they can access via the context.

In methods of Child1 you could write contextParent.Child2 and in methods of Child2 you can write contextParent.Child1

In ordinary OO code this would be a rather ugly and dangerous kludge. But WCHNT is designed so that situations like this are actually principled, robust and legible. The fact that in this case Child1 and Child2 are explicitly designed as context-specific components means they have to exist in the context of a Parent. And have access to it. And because of the principle that objects within an assemblage are transparent to each other, we don't worry too much about Child1 and Child2 knowing the innards of each other or Parent.


-----------

### Implementing the Construction Phase

You might have noticed that I like parsers and grammars. And I'm keen on the isomorphism between the Schema for the object assemblage, and the structure of the Constructions. So I wonder whether, when we build the classes, we shouldn't ALSO be building something like a parser for the construction language .

In other words, we take the schema, and as well as using it to make the classes, we also transform it into another instaparse grammar and use that to make something that parses the construction representation into the tree of objects of the appropriate class.

Does that make sense?

Here's a brief outline of the process and data-structures I'm thinking of.

Phase 1 : Schema Phase

Parse the schema into a list of maps representing classes.

(def class-list [ {:name ClassName  ...} ])

But also 

(def assemblage-grammar (schema->grammar [schema] ... )

Which makes a new Instaparse grammar based on the schema.

Phase 2 : Construction Phase

Let's use the name "factory" for the thing that turns the source of the construction phase of the program into the objects themselves.

So there'll be a (def construction-factory (build-construction-factory assemblage-grammar)) function which will return a function that parses the construction string into some kind of abstract representation of calls to constructors of the actual objects.

Phase 3 : Behaviour Phase

This is a language which will define a number of methods for the classes. We haven't finalised this here. And won't in this document. But the overview is that we'll process the class-list and add the method definitions to each of the maps representing the class.

Phase 4 ... other stuff 

Finally, we'll run through the class-list turning each of these maps into a full Haxe class representation.

And we'll run through the construction-factory, turning all those calls into Haxe statements. The end result of the construction will be a factory function (or object) in Haxe that makes and wires together all the objects.


### Today's Progress (2024-12-19)

#### What Was Achieved

   **Grammar Generation**: The system dynamically generates construction grammars based on schema definitions, including:
   - Class-specific construction rules (e.g., `PersonConstruction`, `SchoolConstruction`)
   - Array construction rules with proper element handling
   - Map construction rules with key-value pairs

#### What is outstanding / current work in progress

3. **Multi-Step Construction Parsing**: 

   The idea of a multi-step construction is that it's like "let" statement in Clojure. It assigns multiple sub-constructions to names. Then the final construction (which isn't assigned to a name) can use these names. This is particularly 
   Parsing constructions that have multiple steps ie. a number of assignments of sub-constructions to variables.
   - Parse final constructions that reference variables like `[:Town [:School people] [:Team people]]`


#### What Works

- ✅ Schema parsing and Haxe class generation
- ✅ Dynamic grammar generation from schema
- ✅ Basic construction parsing for single constructions

#### Outstanding Issues


3. **Variable Reference Resolution**: The system needs to implement proper variable reference resolution, where variables defined in assignments are substituted into the final construction.

4. **Error Handling**: Need better error messages and recovery for construction parsing failures.

5. **Testing**: Need comprehensive tests for multi-step constructions with various formatting styles.

#### Next Steps

1. Fix the dynamic grammar generation to properly include all construction types in the main `Construction` rule
2. Implement proper multi-step construction parsing that treats newlines as whitespace
3. Add variable reference resolution
4. Add comprehensive tests for the construction phase
5. Clean up debug output and add proper error handling



