FOUR INUITIONS ON ASSEMBLAGE PROGRAMMING

Transcript from a podcast ramble.

Right, four thoughts. What is "assemblage programming", really?

Okay, four ideas. 

1) The first one is obviously from my original insight, from a Quora answer, where I said something like, "it's the mismatch between a program that thinks of itself as a network of interacting objects and a language that has no concept of a network of interacting objects. that makes Java and its friends so much hard work."

And what I meant by that was that it's obvious that an object-oriented program in Java, C++, even Smalltalk, is a network of interacting objects, but there's very little way to talk about creating those "assemblages". The only way is you have to hardwire them together, manually, in imperative code, which is hard, a lot of work for managing that.

And it's prone to a lot of errors, things not being created, or not being available when they need to be available.

It's verbose to do it, imperatively.

You don't know quite when it should happen, and there are issues around when things get created.

And then I kind of finished up by saying, "somewhere in the UML is a great idea waiting for a good execution."

Particularly what strikes me about UML is the distinctions it makes between the different "has-a" relationships such this is a component of (and part of the life-cycle of) its owner, vs. this is an independent object temporarily lent to this object. Vs. this is some kind of containing context.

These distinctions are not very well represented in any object-oriented language I know.

You may have a pointer to something, but no language really has a distinction between a thing that is a component that belongs to a sub-component,
an object that's a sub-component that belongs to me, versus an object that I'm referring to in the outside context, or has been temporarily lent to me.

I mean, maybe that's what things like "move semantics" and Rust's "borrow checker" are starting to get at, but at a much lower level.

But if you're in kind of higher level, object-oriented modelling, these kinds of distinctions are interesting and probably captured, but the languages don't know anything about them. Which I think is lacking, it's missing something.

So, yeah, so that's the first thing : in our current object-oriented languages, because we assemble the networks of objects in imperative code, the architectural view, the view of how all the bits fit together, is not really represented in the code at all.

It's represented in an ad hoc way,i it's represented in a way that's distributed across many different locations in the code, and it's a lot of work to deal with, and very prone to errors.

So, that seems to me a big flaw. There's no way to query, there's no place to look up, how does it all fit together?

Okay, so, the solution to that is "type systems", and yes, to an extent, type systems do give you constraints, but they're not doing the work, why aren't they doing the work for you?

Why not? They can tell you that "this has to have a that", but if you know this has to have a that, why are you having to do it yourself? In imperative code?

That seems to me to be a nonsense.

So that's the first kind of glimpse of "assemblage programming".

An assemblage is our collections of objects, and we want to make a notion of an assemblage, of an architecture, of how these objects fit together.

We want to make that more explicit in our language.

We want to make it something that we can talk to the computer about explicitly, rather than having it implicitly spread across all these different sites and places, in an ad hoc way, as and when we create the objects and wire them together.

So, yeah, type systems help with some constraints in languages that have type systems, but in a lot of other object oriented languages from Smalltalk to Python and JavaScript, you don't even have that, 

2) Second view of assemblage programming then.

Java is the most egregious case, because, every class has to even have its own file.

So you end up with an IDE that manages hundreds of files, all containing tiny class descriptions, but they will have to be in separate files.

They will have to be very demarcated to be protected from each other. So, okay, the idea of, of object oriented, even from Alan Kay's original conception of it being like biological cells, is that everything is protected from everything else.

Everything is in its own little granularity and fine grained, world. Only communicating by messages, black boxes.

And this becomes inconvenient.

It's a lot, again, it becomes fiddly to work with.

It's what we call ravioli code as opposed to spaghetti code. Ravioli code is like too many lots of little things to deal with.

And, they're all, in a sense, kind of separate.

So, the intuition here is that you have a membrane around a module or any sort of thing.

Wheras in real life we deal with things at different scales.

It's a beautiful thing in computing and programming that you have a kind of fractal self-similarity at all scales.

But it's not actually the most practical and convenient thing you want, right?

I mean, object-oriented languages tend to assume that there's only one kind of boundary you want between things.

And the one-size-fits-all boundary between them, the class boundary is the same at all the levels.

That membrane has the same amount of, of data hiding or information hiding. There's the same amount of, I say, the extreme case of Java where you literally have to put everything in a separate file.

Maybe Java's got its way around that. You know, it's got inner classes and things of that nature.

And, obviously, that's, that's how we, we handle this.

We handle things with a certain amount of control of, of privacy, what things are private and public.

But, I mean, the real intuition is that we are trying to, one-size-fits-all for the membrane around things is not convenient.

It's not enough.

It's slightly absurd to try and make the single kind of membrane around everything.

So, assemblage programming introduces the concept of an "assemblage".

And, in a sense, you can think of this as there should be two things :

- at the larger scale, there are things that should be very well demarcated, and that should be very loosely coupled. They have a very definite interface, boundary, are black boxes that only communicate by message passing, are very protective from each other.

- But there's also, another level, an inner level, of things that don't need so much protection from each other. That are tightly coupled, transparent. And, in fact, it'd be quite convenient if you can see across them all.

They should all be defined in one place. So there's a single source of truth and visibility about how they relate to each other.

They should all see inside each other.

They all go together.

So, that's an "assemblage"

An assemblage is like a collection of tightly coupled objects designed to work closely together. Not loosely coupled objects that live independently of each other.

We want not only a language that lets us talk about HOW all those objects fit together, but also we're not so fastidious about them not knowing anything about each other. They could all be more or less visible to each other and open to each other and public.

Um, and, , again, there are kind of ways of achieving this, this idea in Java. There are inner classes, there are, uh, I think it's in C++ you've got friend class or whatever, whatever they're called.

But, again, there's some slightly kind of kludgy ways of doing it.

It would be nice to just admit in an object-oriented language that there are different granularities with different requirements for what the membranes between them.

So, an assemblage is a collection of objects.

You think of the objects in the assemblage as things that don't need so much protecting from each other. They are all declared together. All defined in the same file.

While you think of the assemblages themselves as the big things that are very well decoupled from each other.

So the coupling between the objects, the ordinary objects, can be fairly tight because they're all part of the same model and the kind of module
.
And the coupling between the larger scale things, which I'm calling the assemblages, can be more like our traditional idea of an object-oriented language.

Where they really are data hiding, they really are black boxes, they really are only communicating by message parsing.

So, that's the second intuition.

The first intuition about assemblage programming is that one about wanting to explicitly represent the collection of architectural relationships between objects in our system in the language itself. Not just have it represented in external tools or documentation or through the de facto behaviour of the imperative code.

The second is that it would be nice to have these kind of different kinds of boundaries between things, at least sort of two levels of boundaries with different qualities of membranes.


3) Right, the third one, I've been into Clojure a lot in the last few years.

I think Clojure is a fantastic language. I'm very, very excited by it. I use it for everything I can.

And one of the things that Clojure really pushes you towards, away from object orientation, and I think the more I use it, the more obvious this becomes, but actually you go back and you listen to Rich Hickey talking about it, it's clear he knew this as well, is the ability to talk about DATA.
 
So it's a LISP, and obviously it has much of the goodness that comes from being a LISP.

But it's also willing to do something that's a little bit against LISP orthodoxy by introducing new syntax.

But where did it introduce the new syntax?

It introduced the new syntax for talking about literal data structures, maps, lists, lists nested in maps, maps with extra metadata attached.

You know, the genius of Clojure is that it's built on a great tradition of LISP, which is already fantastic. And it was willing to overthrow LISP orthodoxy when it was a good idea to do so.

And, I mean, I always say the two great ways that Clojure improved on traditional LISPs are immutability, okay, so really kind of going back to the functional programming ideals, but, but immutability, I think, is a really good idea.

Although it's not an idea I'm stressing as part of assemblage programming, for various reasons. (I'm still debating this with myself.)
 
But the other great thing that Hickey brought to Clojure is the emphasis on data, the emphasis on maps, having a great deal of expressivity to describe and talk about data.

It's kind of the opposite of object-oriented. The object-oriented intuition is so obsessed with the concept of the object for doing everything, that all the data gets broken up into little things. And, and data is kind of fragmented, it's so, ... I mean, I've been writing some Java recently where I was just trying to read in a JSON file to populate the data-model in my app.

Now writing a Java to create a lot of objects and ... there's this thing contains that thing, and there's an interface, et cetera, it's all right. You kind of do it and you churn out objects and objects and classes and classes and interfaces and interfaces.

And then you sit down and you say, well, now I'm going to just try and parse all this out of a JSON file. And that's a huge, huge amount of work.

So, this is the big intuition : if you look at any programming language, you look at how people talk about it. You start off by saying, yeah, this is an integer, this is a float, these are the basic types for numbers and characters and strings and booleans or whatever.

But very soon it becomes all about behaviour, it becomes about flow of control, ifs, loops, fors.

Maybe less in functional programming, but it's still kind of focused on the behaviour. And certainly imperative, object-oriented ... they're all about what the program DOES.

That's primary. And then the data is secondary to that.

Or, again, as I was saying, the shape of the data has to emerge out of the behaviour of the system, out of the imperative code.

And what Clojure taught me, I think, is the importance of prioritising data. Declaring data.

And this is not a new idea.

I mean people have known this forever, that, that the shape of the tables and the data structures is more important than, easier to help you understand what the code is doing than, than looking at what the flow of control does.

So.

It seems obvious to me, as I was thinking about assemblages. That we would like to express all this stuff declaratively.

We would like to express data. We'd like a good way of expressing data declaratively.

I mean, that's what you've got in Clojure.

But I'm thinking here of a language that's a more object-oriented language. It's more like Python or Java or Smalltalk or whatever.

But we still want declaration of data.

The assemblage is meant to be a way of letting us declare the data, both the type, the shape, and also the data literals.

Because, in a sense, the hard distinction between being able to declare the types of the data ie this type of thing plugs into that, vs declaring what the actual data is ie. this is the actual student that goes to this school and studies in this class.

Perhaps that distinction is not as important as the OO languages make it. 

Yes, we can declare our types, but we have to imperatively wire the instances of the types, the actual objects, together.

But that's not what happens if you're just using large maps in data literals. In that case there's no distinction between the shape of the data and the data itself.

And it's not that the distinction can't be interesting in some ways, but OO languages make such a big thing of it.

So what I was thinking about, what I've been talking about is, what I'm calling "data first" programming, or the "data first language", or a "schema oriented programming", "schema language", in other words, a way of programming where you're going to start by declaring the shape of the assemblage.

You're going to start with just a schema, and now we've got all sorts of things that are kind of like this, and I'm taking inspiration from these.

So part of this intuition comes out of, I was looking at a video about Haskell, and I mean I've dabbled with Haskell but never been a serious Haskell user and I'm not very excited by the strong emphasis on types.

But one thing I was struck by were the algebraic data types. The way you can describe things very elegantly and simply in algebraic data. I think that's fantastic.

So can we borrow that sort of idea for declaring  our assemblages. And this is also something coming from the way we describe grammars. The Bacchus-Naur format is the other big influence on this.

So, basically, you want to describe this kind of assemblage of objects of classes by saying something like : 

A School is made of professors and students and classes.

A Student is made of a name and address and a date of birth.

A Professor is made of a name and address and a date of birth.

A class is - and I'm obviously talking about a class in a school, this is a confusing example - a "class" is a collection of students and a professor and a discipline.

This is a schema for how our data is, how it's all going to fit together.

Now, again, it's not a new idea, this is an old idea, having ways of describing schemas.

So ... this is kind of going to blend into the fourth intuition.

So the third intuition, the big idea is that we are going to start with defining our schemas. And then we are ONLY going to add the behaviour, explicitly as an afterthought.

We're going to see how much, how far can programming go, if our language starts with just declarative definitions of our structures of data. And we prioritise that, that's what you write first, that's the top level view of your, your thing, that's how you describe all your data, that's the important thing.

And then, later on, we will add some, some behavioural code to it. As and when we really need to. But many standard things for working on data should be implicit in the data.

Now, this blends into the fourth intuition, but I'm going to come back to this third point.

4) The fourth point I'm going to say is, it's, I'm kind of influenced by code generation.

So these are not new ideas.

There have been lots and lots of ways where people have generated code from schemas. Ranging from UML modelling tools that can write the classes for you. Through to something like Ruby on Rails or Django. Where, you've got this way of creating descriptions and that can indeed generate the entities in your system, from updating the database, to giving you simple forms to populate it. Etc.

Those, those have been very useful. You can't knock Rails and Django. They've been a very useful tool for web development for, for 20 years or so now.

But code generation is this complicated thing.

There's an idea in code generation which is "the problem of the round trip". Because, leaving aside Rails for a second, the typical thing is that you have an external tool where you write the schema and then you generate the code from that.

And the wisdom is : the round trip between working, editing your schema and editing the rest of your code is really difficult and really painful.

You know, if you can go and edit your schema for the high level overview, and then you go into the target code that was generated. And you change how those functions work and THEN you go back and edit your schema again.

That's a nightmare. It's very complex for those two to stay in sync.

You know, it's difficult or impossible to keep them in sync automatically, if you try and to keep them in sync through manual discipline, you'll fail.

And so the wisdom is usually, if you're going to do code generation, you either do it once and then throw the original schemas away. Or you never edit the target code and drive everything from the schemas.

So in the first case, that was a kind of one time scaffolding to help you. And now you're working on that target code in the normal way.

In the second, you do everything in top-level modelling language and, and can't really do any further customization to the code-gen output.

So what I think about this is there's a long history of code generation being overhyped and people overselling it. Saying this is miraculous, blah, blah, blah, blah.

And it never comes to anything.

So there's a long history of failure there.

And let's look at that with open eyes and clear thinking.

Let's not pretend there isn't an issue.

At the same time, the whole "future of coding" movement is very interested in ... how should I put this? ... We're always going back and looking at great ideas that failed, or didn't take off the way we thought they should have done.

And saying. "Let's revisit this"

I mean, if you're thinking of Engelbart, you're thinking of Smalltalk and, or Xanadu and Ted Nelson ... we spend all our time looking back at these brilliant ideas that didn't come to anything.

And so the fact that things have, ... I think most of us feel that there are very good ideas that failed for contingent reasons. They accidentally failed in the market; somehow it didn't work out the way it should.

And I mean, again, I'm a clojure programmer.

So a classic example of this, if you go back to about 2005, 2010, it would not be very difficult for someone to say, oh, well, Lisp sounds great in theory, but, it's never, it's never going to really work. We've tried it for 40 years; whatever its virtues, however good it seems when you use it, actually, it just doesn't, doesn't come to anything.

And that would have been a fair enough assessment for a lot of people. Yes, there were people who were passionate about it. And, there was Common Lisp, which was doing, it was okay for certain things. There was Racket, or some of the Schemes like Racket, which were still kind of interesting living stuff.


But it wouldn't, it wouldn't have been that controversial to think that Lisp as an overall idea had sort of failed and perhaps wasn't as good an idea as it seemed.

And yet, now, okay, then we've got Clojure, and I say, I love Clojure, I'm a huge, huge fan. I,love working with Clojure, I spend a lot of my time thinking about Clojure, why it works so well.

And, one of the things, the lessons of Clojure is that great ideas from the past, might still really be great ideas, we just haven't found the right way of doing them yet. Or we haven't found the magic way of doing them that kind of unlocks it and makes it really viable and useful.

And I think Smalltalk is hovering around there. We keep seeing interesting things in Smalltalk, but it never quite seems to take off.

But I think one day someone might , and I say this often, someone might do to Smalltalk what Hickey did with Lisp.

And I say, I think the magic is like, simultaneously, you start with a brilliant thing from the past, and then you are willing to also make some good changes to it,
and throw away some of the old orthodoxy when it works to make it better.

Because I, in Clojure's case, I think that that's very obvious.

Lisp is brilliant.

The two improvements that Hickey made :

- introducing EDN and extra syntax to talk about data literals was brilliant.
- and then, introducing immutability, 

Brilliant improvement on traditional Lisp; throwing away some orthodoxy, but, coming up with something even better.

And someone could do that with Smalltalk. I'll rant about that a lot.

That's not what assemblage programming is.

But I mean, someone could do it with Smalltalk, someone could do it with Prolog, someone, could do it with some sort of concatenative forth-like language.

I don't know.

But yeah, good ideas from the past might really be good ideas from the past, even though they failed.

So, with that in mind, let's take code-generation seriously. And can we solve some of the problems with that? 

Here are the thoughts 

- the round trip is hard if you explicitly think the schema is a different language from the main code base. So have everything in the same language
- thinking of a code generation pipeline, you think of a number of steps of adding "information" about your intended code
  - one we add the schema
  - then the actual bits of behaviour, the methods that do stuff. Of all the classes in the assemblage, in the same file, so it's easy to see the whole code 
  - finally we add hints or other information about the schema in the context of our target. For example, the same list data-structure in our schema code, in one target platform may need to be an array, and on another, a linked list. Perhaps hardwiring THIS into the schema is not the right place. There's another place in the code which should specify how our "Platonic" lists need to be made concrete in THIS CONTEXT (ie. this code targetting this platform.) That layer of our definition should be detached and detachable.


IN SUMMARY : THE FOUR INTUITIONS ON ASSEMBLAGE PROGRAMMING

1) OO languages should provide a way of talking about a network or assemblage of objects and how they fit together. Explicitly. In a single place, as a single source of truth. Rather than this information being diffused across multiple activities of imperative code, or even being policed by the type system
2) OO languages really need more than one kind of membrane between different scales of organization. Assemblages provide for this. Different assemblages are loosely coupled. But objects within an assemblage can be tightly coupled.
3) Even a fairly typical object object language (which I want to use for writing games and music software, but other apps as well) could still benefit from being "data first" ie. prioritizing the visibility and accessibility of literal data.
4) Many ideas for schema-driven code are in the code-generation literature. But we want to do code-generation well. How? By having the code-generation pipeline still within the language itself. The multiple stages of code generation are within the normal compilation that the language does. OR, to turn this around, the program within the language itself is structured more like code-generation pipelines. Starting with schema definitions. Then adding behaviour. Then adding target specific data. And internally, in the compiler itself, we take these steps and reorganize tham back into the structure of our target language. 

Basically I want to make a standard OO language, which at run time, runs very much Python or JS or Smalltalk etc. But whose source code is structured in a completely different ordering. 