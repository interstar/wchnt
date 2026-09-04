## The Reaction phase in the WCHNT program

The reaction phase is a set of methods or functions whose job is to transform and construct data. 

In this phase all data is immutable.

And it consists of a set of method definitions which are themselves, constructors for new objects

They take the form

ReactionPhase = (MethodDef)*
MethodDef = ClassName <"::"> MethodName <"("> (Arg <WSP>+)*  <")"> <WSP>* <"="> Construction


So

Game::update() =
   
  [:Game [:Ball (x + dx) (y + dy) dx dy rad] ...]


Update is a special method which ALWAYS creates a new copy of the object it belongs to.

=====

Questions and doubts ...

Lots of overlap with the construction phase. Maybe that really needs to be generalised to a single language

What this needs that we didn't think of before is things like arithmentic and boolean expressions

But no reason we can't have those in the construction.

In other words, construction might not be a separate phase at all. Just a narrowed version of a reaction

Also ties in with reactive variables.

Game = Rect Ball $time 

This makes update automatically take a time param  in haxe

public function update(t:Time) {

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

Rect::area() = 
  width * height

Ball::move() =
  [:Ball (x+dx) (y+dy) dx dy radius]
  
Ball::update() = move()

The Rect::area function returns the area of the Rect object. The Ball::move returns a new Ball with an updated position.

But really, there is a huge overlap between what we want to say in a construction and a reaction. We might as well eliminate redundancy and have a generic but common grammar / parser for them both. 

Does all this make sense?

  
