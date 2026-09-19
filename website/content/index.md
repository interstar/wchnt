## We *Can* Have Nice Things

# Welcome to Project WitchChant

### Huh?

WCHNT is an experimental new programming language.

The name, WCHNT, is an acronym for "We *Can* Have Nice Things". Which is what this is all about.

It's *pronounced* "Witch Chant", because a) that's a hell of a lot more pronounceable than trying to say "WCHNT" (whucn't?), and b) it's a cool name for a programming language.

### Why?

This is the language I've been thinking about for over 20 years. Since the early 2000s when I first started wondering why programming couldn't be a lot easier than it was.

Since then I've used a lot of programming languages. I've taught the comparative programming languages course at university. And I've had plenty of frustrations along the way with different languages and environments that were just too much like hard work.

And so, like many programmers, I've been dreaming of my ideal language for a long time. And I've finally got to that stage in life when I have to do the thing.

### What? 

There are multiple ways of understanding WCHNT, but perhaps the key is something I found myself writing on Quora back in about 2014 :

> It's the mismatch between a program that thinks of itself as a network of interacting objects, and a language that has no concept of "a network of interacting objects", that makes Java and friends so much hard work.

And what I meant by that was that it's obvious that an object oriented program in Java, C++, or even Smalltalk, is a network of interacting objects, but there's actually no way to talk about that network. You define each class in its own isolated little world. And then you have to create and wire the objects together, manually, in imperative code.

That's verbose, error prone work. You might layer a type system on top to add insult to injury by telling you every time you make a mistake. But why can't you just say (and read) in one place, definitively, what all the classes are and how they fit together?

I call that network of classes an "*assemblage*". And I call this paradigm "assemblage programming". It's not an entirely *sui generis* paradigm. It's very much a subclass of "object oriented" programming. And WCHNT is definitely conceived of as an OO language. But it's OO "**turned inside out**". Where everything is organised around the assemblage rather than the individual classes.

Let's get back to the pain of OO. Not only is there nowhere (except scattered around in the type system) any concept of how classes fit together. You have to put the objects together at the right time, in the right order. Or there'll be trouble. So then you might come up with design patterns like factories and dependency injection frameworks that tell you *how* to write the imperative code that makes and wires together the right things at the right time.

But if we knew how the objects were meant to fit together, we could do this *declaratively*.

An assemblage, then, is **a group of classes designed to work closely together, whose structure and relations are declared together in a single place, which acts as the single source of truth for the schema of data in your program.**

And an assemblage programming language, such as WCHNT, turns the traditional structure of an OO program inside out to organise everything around this assemblage.

You can think of this as a language with OO semantics but written in a data-driven or schema driven way.


### How does this look in practice?

In WCHNT, we take inspiration from Haskell's `data` keyword and from Backus-Naur grammars, and write the schema of our assemblage like this :


```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Paddle = Int/x Int/y Int/height
```

This declares four classes : a Game is made of a PlayArea, a Ball and two Paddles. The PlayArea has dimensions, which are Ints with custom names width and height. While a Ball has x,y,dx,dy and radius and a Paddle has x and y and a height. 


At this point, depending on the kind of learner you are, you can try :

* The more detailed **[philosophy](philosophy.html)** and **[full anatomy](anatomy.html)** of a WCHNT program
* The **[basic tutorial](tutorial.html)** (A simple "bouncing ball" game)
* The full **[Language Guide](guide.html)** 
* The **[in-browser live interpreter and environment](play/)**
* "[Pollution](pollution.html)" a simple arcade game (And [open in interpreter](play/?page=pollution)
* Explore the source on [GitHub](https://github.com/interstar/wchnt) or [GitLab](https://gitlab.com/interstar/wchnt)

There are further examples (HTML here; same files seed the Play wiki):

- [Adventure](adventure.html) — ten-room text world; [open in Play](play/?page=adventure)
- [Write paths](writepaths.html) — nested copy; [open in Play](play/?page=writepaths)
- [Square](square.html) — arrow-key mailbox; [open in Play](play/?page=square)
- [Flying A](flyingA.html) — published `Shape` box; [open in Play](play/?page=flyingA)
- [Flying B](flyingB.html) — imports flyingA and adds a Pentagon; [open in Play](play/?page=flyingB)
- [Factory arguments](factory_args.html) — Target builds a `Pen` and passes it into `sketchFactory`; [open in Play](play/?page=factory_args)


