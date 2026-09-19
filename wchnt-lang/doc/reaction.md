# Methods

**This document moved to [`method.md`](method.md).**

`reaction.md` is kept as a redirect for old links. The canonical Methods spec —
including pure methods, mutating `!` methods, calling restrictions, `update!`,
identity slots, and in-place mutation for `$` / `>` objects — is in
**`method.md`**.

In brief: methods are pure unless their names end in `!`. A mutating method
reconstructs its own class, patches the receiver in place, and returns it.
Pure methods may not call mutating methods. The root class of a program is also
mutable; other classes become mutable when they are `$` observables, `$`
subscribers, or `>` mailbox classes.

`reaction_phase.md` is an archived early sketch; see the note at the bottom of `method.md`.
