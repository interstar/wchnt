## The Reaction phase in the WCHNT program

> **Archived sketch (superseded).** The working Methods spec is **`method.md`**.
> This file is kept for archaeology only; the rules below are not the current
> grammar or semantics.

The old reaction sketch described methods or functions whose job was to
transform and construct data. The current Methods phase keeps that pure style
for ordinary methods, while method names ending in `!` explicitly mark
in-place mutation.

And it consists of a set of method definitions which are themselves, constructors for new objects

They take the form

ReactionPhase = (MethodDef)*
MethodDef = ClassName <"::"> MethodName <"("> (Arg <WSP>+)*  <")"> <WSP>* <"="> Construction


So

Game::update! =
   
  [:Game [:Ball (x + dx) (y + dy) dx dy rad] ...]


In the current language, `update!` is a special mutating method. It reconstructs
the same class, patches the receiver in place, notifies subscribers when the
receiver is observable, and returns the receiver. It does not create a new
identity object.

=====

Questions and doubts ...

Lots of overlap with the construction phase. Maybe that really needs to be generalised to a single language

What this needs that we didn't think of before is things like arithmentic and boolean expressions

But no reason we can't have those in the construction.

In other words, construction might not be a separate phase at all. Just a narrowed version of a reaction

Also ties in with reactive variables.

Game = Rect Ball $Time

The `$` relationship makes `Time` observable and `Game` a subscriber. The
compiler requires both to define `update!`; generated Haxe calls it as
`update_mutates()`.

public function update_mutates():Game {

}

-----

WCHNT is still an unusual OO programming language in that it prioritises "the assemblage" over individual classes. So we organise the program differently. The schema at the top. Then the construction. Then the reactive methods (ie. methods without assignment or imperative programming)

Hence a program might look something like

## Schema

Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/radius

## Construction

[:Game [:PlayArea [0 0 500 400]] [:Ball 200 200 1 1 5]]

## Reaction 

Rect::area =
  width * height

Ball::move =
  [:Ball (x+dx) (y+dy) dx dy radius]
  
Ball::update! = { [:Ball (x + dx) (y + dy) radius] }

The Rect::area function returns the area of the Rect object. The Ball::move
method returns a new Ball with an updated position. A `Ball::update!` method,
if the class is mutable, would instead update that Ball in place.

But really, there is a huge overlap between what we want to say in a construction and a reaction. We might as well eliminate redundancy and have a generic but common grammar / parser for them both. 

Does all this make sense?

  
