# WCHNT mutability and identity

> **Caveat:** This document is purely discursive. It records a design
> discussion and possible directions; it is **not yet a plan for
> implementation** and does not define a committed language change.

WCHNT is influenced by both Clojure and Smalltalk. Clojure suggests values,
functional programming, persistent state, and immutability. Smalltalk suggests
live identity-bearing objects, message sending, an evolving object image, and
the ability to build infrastructure in the language itself. WCHNT should not
have to choose one of those models exclusively.

The useful distinction is between three kinds of thing:

1. **Values** — immutable data whose identity is unimportant.
2. **Identity objects** — stable objects whose fields or behavior may change.
3. **Foreign resources** — opaque values owned by a host platform, such as an
   Android View, socket, graphics context, file, or native buffer.

Ordinary WCHNT classes currently behave primarily as values. Methods calculate
and return replacement objects. The `$` and `>` relationships already identify
cases where stable identity is required, while `@` deliberately says that the
host owns the object and WCHNT cannot see its representation.

## Existing identity signals

The `$` relationship is both an identity and a notification relationship:

```wchnt
Game = $Time
Time = Int/t
```

`Time` must remain the same object because `Game` subscribes to that object.
`Game` is also identity-bearing while it acts as a subscriber: an observable
updates it in place rather than replacing the subscriber with an unrelated
object.

A mailbox is identity-bearing for a different reason:

```wchnt
>Keys = Bool/left Bool/right Bool/up Bool/down
Game = Keys $Time
```

The Target owns the input boundary and injects new fields into the existing
`Keys` object. WCHNT Methods can read the mailbox and process its update, but
they do not call `inject` themselves.

The `@` relationship carries no mutability promise. An `@Graphics`,
`@AndroidView`, or `@Socket` may be mutable, but WCHNT cannot inspect that
fact. Its methods therefore need to be treated as host operations rather than
ordinary value transformations.

## Why reactive identity matters

Consider:

```wchnt
Game::update! = { [:Game [:Time (time.t + 1)]] }
```

Read literally, this creates a new `Time` object. That would be wrong if it
breaks the subscription from the original Time object to Game.

The intended reactive pattern is:

```wchnt
Time::update! = { [:Time (t + 1)] }

Game::update! = { [:Game time] }
```

Something outside Game updates Time. Time preserves its identity, then notifies
Game. Game preserves the Time reference while calculating its own new state.

The current compiler already has special handling for identity slots during
`update`: naming the slot preserves it, constructing the same identity class
patches its fields in place, and constructing a different class is rejected.
That is an important implementation mechanism, but the source-level policy
should eventually make the direction of reactive mutation equally clear. In
particular, a subscriber should not casually update the observable that caused
the current notification, because that can produce re-entrant update loops:

```text
Time.update!
  -> Game.update!
      -> Time.update!
          -> Game.update!
```

## Mutating methods

One possible way to make mutation visible is to reserve `!` at the end of a
method name:

```wchnt
Time::update! = { [:Time (t + 1)] }
Game::reset! = { [:Game initialTime initialBall] }
Queue::push! = { item | ... }
```

The suffix would be an effect marker, not merely a naming convention. A
mutating method would update its receiver in place and return that receiver.
Pure methods would continue to return values without changing their receiver.

The intended call graph would then be effect-aware:

```text
pure method
  may call pure methods only

mutating ! method
  may call pure methods and mutating ! methods
```

Thus a pure method could not hide mutation:

```wchnt
A::foo = { this.bar!(24) }   // rejected
A::foo! = { this.bar!(24) }  // permitted, if A is identity-capable
```

Every `!` method would need to return the receiver's class or opaque handle.
This makes the mutation boundary visible in both the definition and the call.

### Generated host names

Haxe identifiers cannot contain `!`. For the near term it may be valuable for
generated Haxe to preserve the mutation signal for people inspecting or
adopting the generated code. A possible mapping is:

```text
WCHNT source       generated Haxe
update!            update_mutates
reset!             reset_mutates
push!              push_mutates
```

The WCHNT name and its mutating status should remain in the IR; the Haxe name
can be derived or stored as a backend-facing field. The same mapping must be
used for ordinary calls, interface signatures, delegate forwarding methods,
imported methods, and Target code that calls generated methods. A source method
whose name already ends in `_mutates` would need a collision rule, probably a
fail-fast error, rather than silently sharing a generated name.

`update!` would be a particularly important mutating method. In addition to
the general mutation rules, it would complete a reactive update cycle. A
possible distinction is:

- `!` means that the receiver is mutated;
- `update!` means that the receiver is mutated and its reactive contract is
  completed, including notification where appropriate.

This avoids making every small mutating operation automatically notify an
entire dependency graph. Whether all mutating operations on observables should
notify, or whether notification belongs only to `update!`, remains a design
question.

## Class-level versus method-level policy

Method-level `!` markers are useful, but they are not sufficient alone. If a
method is named `Ball::move!`, what establishes that Ball has stable identity?

There are several possible answers.

### Relationship-derived identity

Only classes inferred from identity relationships may define mutating methods:

- mailbox classes (`>`);
- observable classes (the target of `$`);
- subscriber classes (the owner of a `$` slot);
- perhaps classes reached through other explicitly identity-bearing
  relationships.

This is safe and gives `$` and `>` a strong meaning, but it may be too narrow
for a Button, Buffer, Socket, or other ordinary OO object that is mutable but
not reactive and not platform-injected.

### Mutation-derived identity

Defining the first `!` method could promote a class to identity semantics. In
this model:

- ordinary classes with no `!` methods remain value-oriented;
- a class with a `!` method is identity-capable;
- `$` and `>` imply identity even when no `!` method has yet been declared;
- a pure class cannot accidentally mutate merely because a method body happens
  to contain a construction.

This is more flexible and lets WCHNT grow toward general OO infrastructure
without immediately adding another Schema sigil.

### An explicit identity declaration

A future syntax might declare identity directly, for example with a class
marker. That could be clearer for large systems, but it adds another concept
before real examples show that inference from relationships and `!` methods is
insufficient.

The most promising direction is therefore a hybrid: infer identity from `$`
and `>`, and allow `!` methods to establish or require identity for other
classes.

## Mailboxes and foreign objects

Mailboxes are externally mutable rather than internally mutable. Target writes
a complete snapshot, then the mailbox's update behavior runs. This is
different from a general `Queue::push!`, where WCHNT code owns the operation.

Foreign `@` values are opaque capabilities. The compiler cannot know whether a
call such as `view.setText(...)` mutates an Android object. Host method
declarations may eventually need effect information such as pure, mutating,
or unknown. Until then, unknown foreign effects should be confined to Target
Methods or already-mutating contexts.

## A possible long-term policy

The combined model could be:

### Value classes

- ordinary classes with no mutating methods;
- Methods are pure;
- construction creates values;
- methods return replacement values;
- ordinary components may be replaced during an update.

### Identity classes

- classes with `!` methods, plus classes inferred from `$` and `>`;
- instances have stable identity;
- `!` methods may mutate the receiver and return it;
- pure methods may call pure methods only;
- mutating methods may call pure and mutating methods.

### Reactive classes

- classes participating in `$`;
- identity plus notification/subscription semantics;
- `update!` completes a reactive update;
- a subscriber cannot update the observable that caused its current update.

### Mailboxes

- classes marked `>`;
- identity plus platform-owned input mutation;
- Target may inject them;
- Methods may read them but cannot call `inject`.

### Foreign resources

- values behind `@`;
- representation is invisible to WCHNT;
- host APIs determine or conservatively restrict their effects.

The principle is:

> Immutability is the default for values. Identity is inferred from
> relationships and mutating methods. Effects are checked at method boundaries.

This preserves functional programming for application state while leaving room
for Android widgets, native buffers, runtime registries, live tools, and the
Smalltalk-like object infrastructure that WCHNT may eventually implement in
itself.

## Questions still open

- Does `!` itself promote a class to identity, or may it only be used on an
  already identity-declared class?
- Does every mutating method on an observable notify, or only `update!`?
- How should nested mutating calls avoid duplicate or re-entrant notifications?
- What effect information should imported methods and `@` host methods expose?
- Should identity objects have direct field assignment, or should all mutation
  continue to use with-constructions and controlled runtime patching?
- How should transactions, batching, undo, and live-image tooling build on the
  same identity model?
