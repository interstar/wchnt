## We *Can* Have Nice Things

# Welcome to Project WitchChant

### Huh?

WCHNT is an experimental new programming language.

The name, WCHNT, is an acronym for "We *Can* Have Nice Things". Which is what this is all about.

It's *pronounced* "Witch Chant", because a) that's a hell of a lot more pronounceable than trying to say "WCHNT" (whucn't?), and b) it's a damned cool name for a programming language.

### Why?

This is the language I've been thinking about for over 20 years. Since the early 2000s when I first started wondering why programming couldn't be a lot easier than it was.

Since then I've used various programming languages. I've taught the comparative programming languages course at university. And I've had plenty of frustrations along the way, working in a different languages and environments, which all seemed just too much like hard work.

And so, like all programmers, I've been dreaming of my ideal language for a long time. And I've finally got to that stage in life when I have to do the thing.


### What? 

There are multiple ways of understanding WCHNT, but perhaps the key is something I found myself writing on Quora back in about 2014 :

> It's the mismatch between a program that thinks of itself as a network of interacting objects, and a language that has no concept of "a network of interacting objects", that makes Java and friends so much hard work.

And what I meant by that was that it's obvious that an object oriented program in Java, C++, or even Smalltalk, is a network of interacting objects, but there's actually no way to talk about that network. You define each class in its own isolated little world. And then you have to create and wire the objects together, manually, in imperative code.

That's verbose, error prone work. You might layer a type system on top to add insult to injury by telling you every time you make a mistake. But why can't you just say (and read) in one place, definitively, what all the classes are and how they fit together?

I call that network of classes an "*assemblage*". And I call this paradigm "assemblage programming". It's not an entirely *sui generis* paradigm. It's very much a subclass of "object oriented" programming. And WCHNT is definitely conceived of as an OO language. But it's OO "*turned inside out*". Where everything is organised around the assemblage rather than the individual classes.

Let's get back to the pain of OO. Not only is there nowhere (except scattered around in the type system) any concept of how classes fit together. You have to put the objects together at the right time, in the right order. Otherwise it's another disaster. So then you might come up with design patterns like factories and dependency injection frameworks that tell you *how* to write the code that makes and wires together the right things at the right time.

But if we knew how the objects were meant to fit together, we could do this *declaratively*.

An assemblage, then, is **a group of classes designed to work closely together, whose structure and relations are declared together in a single place, which acts as the single source of truth for the schema of data in your program.**

And an assemblage programming language, such as WCHNT, turns the traditional structure of an OO program inside out to organise everything around this assemblage.

You can think of this as a language with OO semantics but written in a data-driven or schema driven way.


### How does this look in practice?

In WCHNT, we take inspiration from Haskell's `data` keyword and from Backus-Naur grammars, and write the schema of our assemblage like this :


```wchnt
Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
```

This declares four classes, a Game is made of a PlayArea and a Ball. The PlayArea is made from a Rect. The Rect consists of four Ints, to which we give custom names of x,y,width and height. While a Ball has x,y,dx,dy and radius.

Note that where there is only one instance variable of a specific type, we don't need to give an explicit name. The name defaults to a version of the class name with lower-case first letter. So we don't have to say something redundant like `PlayArea/playArea` Inside the Game class, that component will automatically get the name "playArea". 

This schema is the full declaration of the assemblage. It occurs in a specific section at the beginning of the program. And is the only source of truth for all the classes.

### Declarative relationships
In my Quora rant I finished up by saying, "somewhere in the UML is a great idea waiting for a good execution."

Particularly what struck me about UML is the distinctions it makes between the different "has-a" relationships such as "this is a component of that" (and therefore part of the life-cycle of its owner) or "this is an independent object temporarily lent to this object" or "this is some kind of containing context".

But these relationships are not part of the programming language itself. You *can* generate classes from UML, but the round-trip is error prone. And most people just implement these ideas in an ad hoc way.

In assemblage programming, we make some of these relations explicit, which lets the compiler reason about the relationship down the line.

In addition to the normal composition of classes illustrated above, WCHNT defines five other important relationships between classes which we specify with "sigils" (special symbol prefixes in the schema declaration).

| Sigil | Name | Example | Meaning | 
|-------|------|---------|---------|
| *(none)* | normal | Game = Ball | Game has-a Ball | 
| `:` | context-specific | Car = :Engine | Car has an Engine, and an Engine *must* be part of a car. An Engine automatically gets a reference (`theCar`) back to its parent. |
| `+` | delegate | Student = String/id +Person | Student reuses Person by delegation; Person's fields and methods are automatically promoted onto Student | 
| `@` | external | Game = Ball Player @GraphicsContext | GraphicsContext is borrowed from outside and its lifecycle is managed beyond the assemblage | 
| `$` | reactive | Game = Ball Player $Time | Game automatically subscribes to changes in Time |

These will get explained fully later in this document.

----

## The Structure of an Assemblage

A full assemblage is defined in a single file. And is written in a *literate programming* style. 

In 2026 it's clear that *markdown* has become the universal file-format. And that - yes, we gotta mention it - a world with AI assistance means that much "programming" is going to be in natural language. Project WitchChant is getting ahead of the curve by deciding that Markdown is the official format for a WCHNT assemblage. And that we embed the snippets of formal code in a natural language document.

Another of my bugbears over the years is the lack of navigability in my IDE. "Why isn't everything wiki?" I wailed back in about 2002. Why can't I annotate my code with hyperlinks to important related ideas? Well, the typical WCHNT environment *is* wiki. With assemblages defined on pages which can be hyperlinked together. 

A WCHNT assemblage, therefore, is a file or page of markdown with a number of sections (which we also call "phases"). Some sections are required. Others are optional. Each section or phase starts with a header and contains code in a fenced block. It can also contain other free text for discussion and documentation. And hyperlinks to sibling assemblages. 

The sections are :
* Import (optional)
* Schema
* Construction 
* Methods 
* Public (optional)
* Target Methods (optional)
* Target 


## Main Sections

### Schema Section

The schema section represents a number of lines of a "grammar" which shows how the assemblage is organised.

```wchnt
Game = :PlayArea Ball $Time @GraphicsContext 
PlayArea = Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t
```
In additional to normal components. (Eg. the `Ball` as something owned by a `Game`) there are a number of specific relationships defined by sigils. 


#### "Context-dependent" components (:)

A context-dependent component is one which is tied to its owner or parent. 

```wchnt
Car = :Engine String/colour
```
In this example, the colon specifies that the Engine is a context-dependent component. An instance of an Engine is always owned by a car and can only exist inside that car. In practice what this means is that the compiler gives the Engine an instance variable called theCar which points back up to the containing car. This field is automatically filled at the construction of the assemblage. So we can always rely on a Car being available in the methods of the Engine. Going forward this relationship may well govern life-cycle and access rules for the Engine too.

#### "Delegate" components (+)

A delegate is a way to reuse code via the delegation pattern. (As opposed to inheritance)

```wchnt 
Person = String/name String/address
Student = String/student_number +Person
```

In this example, we specify that a Student is a student number *plus a Person*. In practice it keeps a Person object inside the Student object, but automatically promotes the fields and methods of the Person to be visible within a Student object. We will be able to write `s.name` where s is a Student, to access the name which is actually stored in the internal Person object. The same works for method calls.

However the parent class can override or shadow methods of the child class. And in some cases, eg. the `update()` method (see below), it *must* do so, and the compiler will throw an error if it doesn't.

#### "External" components (@)

An external component is simultaneously one which is defined outside the assemblage, and considered to be temporarily lent to it. 

```wchnt
Game = :PlayArea Ball @GraphicsContext
```

In the previous examples, any class referenced on the right hand side of the = sign must be declared in the assemblage. The external class is the exception. WCHNT assumes it's **not** defined within the assemblage at all, and comes from another assemblage, or the platform, framework or wider context within which the program is run. An object of this class will need to exist prior to the Game's construction and be passed as an argument to the construction. We assume that the object will live on after our assemblage has finished and that it is someone else's responsibility to destroy it.


#### "Reactive" components ($)

A reactive relationship is established with the dollar sigil

```wchnt
Game = :PlayArea Ball $Time
Time = Int/t
```

This declares that Game is a dependent of Time. If the Time value changes, the Game will update itself. Behind the scenes we turn the Time object into an observable and the Game object becomes a subscriber to it. When the value `t` in the Time object updates, Game will automatically receive an `update()` message.

Note that WCHNT also takes inspiration from functional programming. I'm a big fan of immutability. And most methods in WCHNT are single expressions which cannot mutate the object. In fact there is only one method that can mutate an object "in place", the `update()` method.


#### "Mailbox" classes (>) 
It's presumed that WCHNT programs are run in a harness or external framework which manages perhaps the top level game loop or REPL loop. 

Mailbox classes are how this external environment passes information into the assemblage.

```wchnt
>Keys = Bool/left Bool/right Bool/up Bool/down
Game = :PlayArea Ball $Time Keys 
```

The > sigil tells us that the values in the `Keys` object are expected to be mutated by the external context. Ideally, the external context should be forbidden from mutating any object which *isn't* a mailbox object. So in this example, Game can look inside its Keys component to find out the latest user key presses passed into the assemblage from the harness.

#### Sum types / Interfaces

We borrow one more trick from Haskell's `data`. The ability to define sum types. Or, in more pedestrian OO terms, *interfaces*.

```wchnt
Shape = Triangle | Square | Circle
```

This declares an interface called `Shape` and the three specific shape classes that implement it.

#### Enums

Enums are defined in the schema declaration with values in quotes.

```wchnt
Action = "Run" | "Jump" | "Duck" | "Shoot"
```
#### Collections

WCHNT supports two standard collections. Arrays (aka lists or vectors), and Maps (aka dictionaries, associative arrays etc). Because it's a typed language, we need to type these collections too.

```
Item = Weapon | Potion | Treasure 
Skill = "Strength" | "Intelligence" | "Dexterity" | "Stamina"
Adventurer = String/name [Item]/items {Skill:Int}/investments

```

Here the `Item` is an interface implemented by the `Weapon`, `Potion` and `Treasure` classes. And `items` is an array of them carried by the `Adventurer`.

`Skill` is an Enum. Which is used as the key in a map of `Skill` to `Int`.

### Construction Phase

The **Construction** phase creates an instance of an entire assemblage. Basically it defines a factory that constructs all the objects and wires them together appropriately.

We use a format based on Clojure's "hiccup" where an object is defined as a list of data in square brackets, with the first element being the name of the class in Clojure's :keyword format (ie. with a colon on the front.) IMHO hiccup is more readable than XML or JSON. And easy to work with in Clojure, the language in which the WCHNT compiler is written.

Here's an example Construction.

Assuming the Schema looks like this :

```wchnt
Game = :PlayArea Ball $Time
PlayArea = Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t
```
Then the Construction could be :
```wchnt
[:Game 
   [:PlayArea 400 400]
   [:Ball 100 100 4 -4 5] 
   [:Time 0]]
```

#### Specific Relations

Wiring up of reactive dependencies (eg. between Game and Time) and references from a context-dependent object to its containing parent (from PlayArea to Game) are done automatically during the construction of the assemblage.

As can be seen in the previous example section, there is nothing special in the Construction about either context-dependent or reactive components. Nor for delegates. We might construct a Student who delegates to a Person like this :

```
[:Student "s482" [:Person "John Smith" "48 Acacia Avenue"]]
```

#### Externals

The exception is externals. These objects are assumed to have been created *before* the assemblage construction is run. 

An external comes either from another imported assemblage, or from the platform / harness code we are running inside.

In the first case, we ask the imported assemblage to give us the object in the assemblage

If `Game = @Map Adventurer` and Maps are made by a different mapMaker assemblage, then in our constructor we might say: 

```
[:Game mapMaker.makeIt() [:Adventurer "Andy" ...]]
```

In the second, we expect to receive the external object from the platform which invokes our assemblage, via a factory call. (The construction is compiled into a native factory function on the target platform)

If `Game = Ball Player/p1 Player/p2 @GraphicsContext` and the GraphicsContext comes from the platform, then


```
[:Game [:Ball 10 10 3 3 4]
       [:Player 40 40]
       [:Player 100 100]
       graphicsContext]
```
The WCHNT compiler will spot that graphicsContext is an unrecognised name in a slot typed for an external component, deduce that this component must come from the platform, and add it as an argument required by the factory. Eg. in the target platform code (in say Haxe or JS or whatever our target platform is) you would have to say something like

```
var game = gameFactory(graphicsContext);
```



#### Multiline Constructions

Sometimes you need to make an object and reuse it in several other places. Or maybe you just want to simplify a complex construction by breaking it into pieces. Constructions (and similarly methods) can be multiline "let bindings". Where each definition is separated by `.` 


```
john = [:Person "John Smith" "42 Acacia Ave"].

[:School
  [:FootballTeam [:Array/Person john ...]]
  [:HockeyTeam [:Array/Person john ... ]]
  ]
``` 
This ensures that it's the same Person object, temporarily bound to the name `john` that is placed in both the football and hockey team lists.


### Methods Phase

The **Methods** phase specifies the behaviour of the assemblage. 

Each method definition binds the classname::methodname to a code block. A code block has the syntax `{ arguments | expression}`.


```wchnt
Ball::bounceDx = { PlayArea/playArea |
  if ((x < 0) or (x > playArea.width))
     { -dx }
  else
     { dx }
}
```

Note that in WCHNT, `if else ` is an expression, not a control structure. If the condition is true, the whole expression evaluates to the result of executing the first block. Otherwise the second. 

Almost all methods are expressions that return a new value which is either a primitive or an object. To construct a new return object we use the same syntax as the Construction phase of the program. In fact Method syntax is a superset of Construction syntax. It can do everything constructions can. Plus some standard arithmetic, logic etc. And accessing the locally bound names within the object.

```wchnt
Foo::bar = { Int/x | [:Pair x (x * x)]}
```
#### Let Bindings

As with Constructions, Methods can be either single expressions or a "let binding" sequence of further definitions, separated by `.` and a final expression.

```wchnt
Foo::baz = {Int/x |
  y = x * x.
  [:Pair x y]
}
```

#### Update()

`update` is a special method. It's the one method of an object which is considered to mutate it "in place".

```wchnt
Game::update = {
  bounced = ball.bounced(playArea)
  [:Game playArea bounced time]
}
```

Although this looks like it's creating a new Game object, because it's the `update()` method of Game, it compiles into something that mutates the existing Game object. In this case, it keeps the same Game object with the same playArea and time reference, but has replaced its old Ball object with the new one created though `ball.bounced()`. Note that the Ball itself has been recreated in this example. We only change in place in the update() method itself. Not the things it calls. 


#### Map, Filter and Fold

Both Array and Map collections have the usual trio of "combinator" methods (map, filter and fold), that apply a code block to them :

```wchnt
bouncedBalls = balls.map({ball | ball.bounced(playArea)}).

lowBalls = balls.filter({ball | ball.y < 100}).

totalScore = players.fold(0, { tot, player | tot + player.score }).

boostedSkills = skills.map({ k, v | v + 1 }).

topSkills = skills.filter({ k, v | v > 0 }).

totalInvestment = skills.fold(0, { acc, k, v | acc + v }).
```

Note that the accumulator is the first argument to the fold and the code-block the second.



### Target Phase

All programs run in some kind of environment. And often it's the interface between your program and that environment that causes the most trouble. The philosophy of WCHNT is to make this environmental dependency more explicit and legible.

The **Target** phase is the place where we put information about how the environment calls into our code. Right now, when WCHNT is still very embryonic, we have two target environments for it. One is compilation to the [Haxe](https://haxe.org/) language. The other is an [interpreter](play/) running in the browser where you can play with WCHNT today.

The Target consists of configuration flags and target specific code to provide the main or game loop. For Haxe compilation target specific code is written in Haxe itself. In the browser it's in Javascript.

Currently we provide a simple graphics API which is the same across the OpenFL and browser canvas targets. 


Here, for example, is the Target for an OpenFL ball bouncing program.

```wchnt
%openfl

%init
var assemblage:Game;

function init():Void {
    assemblage = gameFactory();
}

%step
function step():Void {
    assemblage = assemblage.step();
    var r = assemblage.playArea.rect;
    var b = assemblage.ball;
    wchntGraphics.clear();
    wchntGraphics.beginFill(0x2a2a2a);
    wchntGraphics.drawRect(r.x, r.y, r.width, r.height);
    wchntGraphics.endFill();
    wchntGraphics.beginFill(0xf2f2f2);
    wchntGraphics.drawCircle(b.x, b.y, b.rad);
    wchntGraphics.endFill();
}
```

You can see we start with `%openfl` to tell the compiler which environment we're targeting. Then we define an initialisation phase in platform native code, in this case Haxe. That declares a variable to hold the assemblage, and a standard init() function which calls the `gameFactory()` function. Because `Game` is the top level class in our assemblage, the compiler has emitted a factory function with name gameFactory() to build the assemblage as defined in the Construction phase.

The step() function is the payload for a default "game loop" that the OpenFL harness runs. It's called every tick of the clock and we can see that the first thing it does in this example is call the Game's own step() function. 

You'll notice that this particular program doesn't mutate the assemblage. Game's step() function just creates a new Game object at each step. Which is clean but inefficient. To mutate the Game in position we'd have to put the mutating code in Game's `update()` method and call `assemblage.update();` in this target code.

The rest of this function is about actually drawing the state of the game with the `wchntGraphics` that object comes from the harness. In this example we do the actual drawing in Haxe, leaving the WCHNT code decoupled from the actual rendering.

But it is also possible to define draw() functions *in* WCHNT and pass the wchntGraphics object in as an external component.

```wchnt
Ball::draw = {@WCHNTGraphics/g | g.beginFill(0xf2f2f2).drawCircle(x,y,rad).endFill()}
``` 
Then in the Haxe

```
b.draw(wchntGraphics).
```

The choice is what makes sense in the particular application and target environment.

The target will need to provide a WCHNTGraphics class with those beginFill, drawCircle, and endFill methods.

And note that WCHNT doesn't have imperative programming, so a sequence of graphics calls like this has to be written chained together in "*fluent*" style. 

WCHNT doesn't hide or resolve all your environment or platform integration problems. IMHO it's always hard to interface between the purity of data manipulation inside the program and the messy outside world. But the hope is that by making the choices more visible. And placing them directly in the assemblage definition rather than relegated to obscure external config files, we actually make dealing with it more straightforward.

The structure of an assemblage file still enforces a clean separation in the program between the different layers of your system : the inner data structure, the initialisation from data, the behaviour, and finally this platform integration. (I sometimes borrow the term "shearing layers" from Stewart Brand to talk about these different layers.)

The hunch behind WCHNT and assemblage programming is that, in the small, this separation, and this organisation is easier to read and reason about and manage, easier to navigate, and just generally more comfortable to work with, than the highly splintered way that OO languages normally organise their code.


### Import Phase (and Public Phase)
Assemblages are intended to be small groups of tightly coupled classes. As such we consider that there's no need for data hiding or opacity between them. Classes in the same assemblage are assumed to be able to see into each other and are not trying to hide their structure from each other.

This does not mean that data hiding and abstraction layers are bad things. Another contention of assemblage programming is that different scales require different kinds of membrane. So within an assemblage, "programming in the small", we can assume cooperation and transparency. For programming in the large, the boundary *between* assemblages is still considered opaque and the usual virtues of abstraction and data hiding should be respected.

You could see an assemblage as equivalent to a "package" or namespace.

So what does the boundary BETWEEN assemblages look like?

This is still being experimented with. But the following is an outline of what we currently have. We'll assume two assemblages `flyingA` and `flyingB`. flyingA defines a simple app where shapes fly through the sky. flyingB imports that framework, adds a new shape and inserts it into the same sky.


1) An assemblage with a `Public` section is open for reuse by other assemblages. Assemblages without this Public section can't be reused elsewhere.

2) When an assemblage *is* public, it can be imported into another assemblage in that assemblage's `Import` section. The import will give it a local name. In our example, in `flyingB` we will import `flyingA` under the name `flying`

```wchnt
[[flyingA]] as flying
```


3) The `Public` section of the first assemblage will be a list of specific method calls on specific classes. Eg. in `flyingA`

```wchnt
Game::make
Game::update
Game::addShape
```

4) It can also refer to *interfaces*. We can add Shape to the export list. But note we cannot add a concrete class. The reason is simple. In WCHNT, the construction of a class is based on knowing its inner structure. But between assemblages we are hiding the inner structure of classes. It's OK to export specific method calls with specific argument lists. And to export interfaces which are only known in terms of their method signatures. But the shape of classes must remain hidden between assemblages.


```wchnt
Game::make
Game::update
Game::addShape
Shape
```

5) In `flyingB` , we *can* use the names of classes in the schema, as long as we specify them as externals. Here, in the Schema we define Sky as a Game from `flyingA`. It must be marked as external using @. 

Pentagon is a class we are defining *within* `flyingB` but specifying that it implements the Shape interface that was also published by `flyingA`.

```
Sky = @Game
Pentagon : Shape = Int/x Int/y Int/side Int/dx
```
Then in the construction of `flyingB` we can say:
```
[:Sky flying.make().addShape([:Pentagon 640 90 36 4])]
```

Note the following. Classes are not first class objects in WCHNT. At least, not at the moment. We can't send messages to them. But `flying` the imported assemblage from `flyingA` *is* an object. That accepts the Game::make() message. That function creates a new Game object which, in the construction, is suitable to fill the slot declared by the schema as @Game. 

6) The public API of `flyingA` *also* publishes `Game::addShape()`, a function which adds a new object of a class that implements the `Shape` interface to the Game. Note that `flyingB` still can't create instances of any of the classes from `flyingA`. But it *can* define new classes, such as `Pentagon`, which implement the interface. And can be added to the Game.

Because addShape() returns a new Game with the extra shape included, its return value still fits in the @Game shaped slot we specified in the schema.

`flyingB` therefore is able to use the framework defined in `flyingA` and communicate with it by passing objects through public interfaces. Without knowing anything of the innards of `flyingA`.


----
## Learn and Play

Want to know more?

There's a **[Tutorial](tutorial.html)**, and **[Guide](guide.html)** for the full language.

You can try writing real code in WCHNT in the **[online environment](play/)**.

Full examples (HTML here; same files seed the Play wiki):

- [Pollution](pollution.html) — arcade; [open in Play](play/?page=pollution)
- [Adventure](adventure.html) — ten-room text world; [open in Play](play/?page=adventure)
- [Write paths](writepaths.html) — nested copy; [open in Play](play/?page=writepaths)
- [Square](square.html) — arrow-key mailbox; [open in Play](play/?page=square)
- [Flying A](flyingA.html) — published `Shape` box; [open in Play](play/?page=flyingA)
- [Flying B](flyingB.html) — imports flyingA and adds a Pentagon; [open in Play](play/?page=flyingB)
- [Factory arguments](factory_args.html) — Target builds a `Pen` and passes it into `sketchFactory`; [open in Play](play/?page=factory_args)


