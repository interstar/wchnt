
# Philosophy of WCHNT

This page describes the principles that wchnt is built on.

Wchnt is not an exotic language. It's basically an OO language. But it's OO which is *turned inside out* to make **the assemblage** - a cluster of tightly coupled, interdependent classes - the main organising principle, rather than the individual class, as in most OO languages.

The goal is to make the assemblage as readable, intelligible and as convenient to work with, as possible.

### Schema First

> Show me your flowcharts and conceal your tables, and I shall continue to be mystified. Show me your tables, and I won’t usually need your flowcharts; they’ll be obvious. 

-- [Fred Brooks](https://en.wikiquote.org/wiki/Fred_Brooks#The_Mythical_Man-Month:_Essays_on_Software_Engineering_(1975,_1995))

We do this by first bringing the definition of the entire *schema* of the assemblage into a single location, in a declarative style. Having this schema in a single place gives the programmer an overview of the data-structure / object graph that they are working with. And a convenient way to make changes to it.

It allows us to make explicit the relationships and intentions that are normally diffused across the type-system, the imperative code of the constructors, and even external documentation. In my Quora rant I finished up by saying, "somewhere in the UML is a great idea waiting for a good execution."

What struck me particularly about the UML was the distinction it makes between the different "has-a" relationships such as "this is a component of that" (and therefore part of the life-cycle of its owner) or "this is an independent object temporarily lent to this object" or "this is some kind of containing context".

These relationships are typically not part of a programming language itself. And while you can generate OO code from UML, the round-trip is error prone. Most people just implement these ideas in an ad hoc way and hope the code matches the UML model.

In assemblage programming, we make some of these relations explicit, which lets the compiler reason about the relationship down the line. Both to generate extra code, and to check for consistency. 

In addition to the normal composition of classes, wchnt defines five other important relationships. These are *context-dependent*, *delegate*, *external*, *reactive* and *mailbox* classes. Which will be described in detail in the [anatomy.](anatomy.html)

```wchnt
Game = PlayArea :Ball :Paddle/paddle1 :Paddle/paddle2 $Timer Buttons
PlayArea = +Rect
Rect = Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/radius
Paddle = Int/x Int/y Int/height
>Buttons = Bool/up Bool/down Bool/left Bool/right

```

### Managing State

I've been writing Clojure for over 10 years now. And it's certainly my preferred language when I can use it. A lot of its FP virtue is down to default *immutability*, which makes returning to and debugging existing code much easier than in stateful languages.

I had been wondering what an "immutable Smalltalk" would look like for a while. Could you build a purely OO, live Smalltalk-like system with immutable objects? The answer is, of course, no. There has to be mutability *somewhere* in the system. The question is how to manage it.

Wchnt is partly an exploration of that question. We manage mutability in two ways : 

* Firstly the relationships between certain classes, as made explicit in the schema, imply when a class is mutable or not.
* Secondly, even when a class *is* mutable, we require a syntactic distinction between methods that mutate their object in place, and methods which return new shallow copies of the receiver. Furthermore, we place a restriction that non-mutating methods cannot call mutating methods. When you write or call a non-mutating method you know that there can't be mutation further down the call chain.

The exception to this is for classes which have been marked as *external*. External classes come from elsewhere - perhaps the operating system or a library written in another language. We have no knowledge of statefulness within an external. But this itself creates a kind of *centrifugal force* that spins mutable data out to the "edges" of the program. Ideally to a "Redux"-like top-level global state holding object, or even out into external files and databases.

*Note that I'm still very much experimenting with state-management in wchnt and some of these design decisions might be changed tomorrow.*

### Two Granularities

The assemblage plays a second role in our thinking about our program. OO languages are usually focused on *the class* as the one-size-fits-all unit of granularity. In assemblage programming we explicitly identify the need for two levels of granularity : the class and the assemblage. These have very different purposes and require different membranes.

*Within the assemblage* we assume that the individual classes are written at the same time, by the same programmer(s) and intended to collaborate together to do the same job. As such, they have no need to be protected from each other by abstraction or data hiding. Assemblages themselves are the coarser granularity components of a larger system, and here we still want the traditional virtues of data hiding and programming to abstract interfaces.

You could argue that assemblages are somewhat like packages or modules. But unlike Java *et al* which give you a lot of fine grained control over public and private access at the class level, and packages which have no real further implications for data-hiding and modularity, wchnt gives you two explicitly different granularities with fixed rules for data visibility in each. It's less flexible than the fine-grained control that Java gives, but I hope is simpler to reason about and work with.
s
### Navigable, literate codebase and reuse

Another of my bugbears over the years has been the lack of navigability in my IDE. "Why isn't everything wiki?" I wailed back in about 2002. Why can't I annotate my code with ad hoc hyperlinks to important related ideas? So I can quickly jump around between tightly coupled elements I'm currently working on?

The typical wchnt environment *is* wiki. With assemblages defined on pages which can be hyperlinked together. 

In 2026 it's also clear that *markdown* has become the universal file-format. And that - yes, we gotta mention it - a world with AI assistance means that much "programming" is going to be in natural language. Project WitchChant is getting ahead of the curve by deciding that Markdown is the official format for a wchnt assemblage. And that we embed the snippets of formal code in fenced blocks, within in a *literate* natural language document.

Because of this markdown base, wchnt assemblages are nicely renderable in the browser which again to speaks to our emphasis on readability.

### Explicit Target Platform and Context

No program is an island. Unless you are writing bare-metal bootloaders, you expect your program to live within a context provided by the platform or operating system. And likely to be invoked by callbacks from that platform. And to act on and through resources that that platform provides.

This platform dependency is crucial to your program running correctly, and mismatches often cause subtle problems. But much of the information about it is usually obscure : hidden away in configuration files, and rarely discussed or seen in tutorials or documentation for the language. Wchnt takes the opposite view. Assemblages have a section dedicated to the target platform, right there in the page, given almost equal billing to the other parts of and ideas in the code. Just as we want to make the schema of the network of object explicit and visible in our code, we also strive to make all the messiness of the connection to the host or target platform visible too.

Of course, the real world is messy, and there is always something outside what can be captured here. But the aspiration is for this section to be as flexible and maximalist as possible. It specifies not simply what platform the assemblage is targetting, but what recourses it requires. It even allows target specific code to help in wiring our assemblage into the platform. The wchnt compiler itself can be extended with custom compilers for the target section. 

For example, right now, when wchnt is still very embryonic, we target two platforms : the first is our live interpreter which is written in ClojureScript and runs anywhere that Javascript runs. The second is compilation of whcnt to [Haxe](https://haxe.org/) source-code and the Haxe ecosystem. (From which it can be further transpiled to Java, Python or C++ etc)

Within those broader categories we have more specific targets such as Haxe's [OpenFL](https://www.openfl.org/).




### Types of Reuse
The notion of wchnt as a "wiki" or collection of hyperlinked pages or documents goes beyond just convenience of reading, though. We integrate this assumption into our strategy for reuse. 

Wchnt has run-time reuse where an assemblage imports another assemblage as a reusable component. This is for the usual purposes of creating reusable libraries, or assembling components into a larger systems etc. It ALSO expects compile-time reuse via *transclusion*. Any assemblage can simply declare that it transcludes a section from another page. This is a crude operation on the source 


### A Final Word On AI

Many people seem to think that the rise of AI assistance and vibe coding will lead to the end of "programming languages". If the machine writes all the code, why do we need to look at it? Why don't we generate raw machine code for efficiency?

I believe the opposite. Firstly that a language which is good for humans to read and think about is *also* good for a language model to think about. However big the context window, the AI will benefit from shorter, clearer and more elegant textual representations of the desired computation. Secondly, it turns out that a language which is really focused on making a program easy for humans to read and understand is a great way for AIs and humans to communicate with each other. I admit that the more I use AI coding assistance, the less patient and tolerant I get for reading code. (And my tolerance and patience was never that high in the first place.) But I *can* read and modify vibe coded wchnt fairly easily. Of course, assemblages are smallish. But that's kinda the point.

### Explicit Inspirations

* Smalltalk (a live OO environment)
* Clojure (data-driven FP, and immutability)
* UML (explicit classification of has-a relationships)
* COBOL (puts the shape of data at the beginning)
* Haskell (the first time I saw the `data` statement and declaration of ADTs my mind was blown by how
* Java (the *anti-inspiration* ... often my philosophy is "what would Java do? Do the opposite")
* Processing (In one sense I might call this a proto "assemblage" language. It reconfigures Java so that multiple classes live in a single tab and encourages thinking of that as a kind of granularity. Also it makes the its platform immediately accessible and useful which turns out to be the secret of making programming fun)
* Wiki (shout out to the wisdom of Ward Cunningham)


### The Goals



