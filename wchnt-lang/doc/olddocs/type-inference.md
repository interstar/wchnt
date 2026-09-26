# Type inference: numeric widening, `if`, and negation

WCHNT has a small numeric type lattice and uses it consistently when checking
expressions, inferring parameter types, and checking method arguments.

## Numeric types form a small widening lattice

WCHNT uses this numeric lattice:

```
Int  <:  Float
```

The common type, or join, of two numeric values is therefore:

```
join(Int, Int)       = Int
join(Int, Float)     = Float
join(Float, Int)     = Float
join(Float, Float)   = Float
```

This is the same basic idea as a numeric tower, with two levels. Widening from
`Int` to `Float` is automatic; narrowing is never implicit.

## `if` is an expression, so both branches share one type

`if` / `else` returns a value, so both branches must unify to a single type.
Non-numeric branches must still match exactly. Numeric branches use the lattice
above, so an `Int` branch and a `Float` branch produce a `Float` result. WCHNT
fails fast for unrelated types rather than silently coercing them. For example,
an `Int` branch and a `Bool` branch are rejected:

```
if branches must have the same type, got Int and Bool
```

The one-way widening rule also applies to arguments and assignments: a `Float`
context accepts an `Int`, but an `Int` context never accepts a `Float`.
For example, this expression is well-typed and has type `Float`:

```wchnt
if (dx < 0.0) { -dx } else { dx }
```

## Negation preserves its operand's type

`-x` has the same numeric type as `x`: negating an `Int` gives `Int`, negating
a `Float` gives `Float`. The compiler infers the `:neg` node from its operand
and keeps any surrounding numeric context during parameter inference:

```
Int   -> Int
Float -> Float
```

Arithmetic and comparisons use the same context. Thus a Float literal in an
expression causes otherwise-unannotated numeric parameters in that expression
to be inferred as `Float`. A negation or comparison therefore cannot
accidentally force a Float parameter to `Int`.

For example, the following method has a Float parameter and a Float result:

```wchnt
Game::absolute = { Float/x | if (x < 0.0) { x } else { -x } }
```

The explicit annotation is not changed by the inference pass. Without an
annotation, a Float literal in the same numeric expression supplies the Float
context for an otherwise-untyped parameter.

## Explicit narrowing

Narrowing is explicit because it can lose information. Float values provide
language-level methods that do not require a `WCHNTMaths` object:

```wchnt
x.toInt()   // truncate toward zero
x.floor()   // round down
x.ceil()    // round up
x.round()   // round to the nearest integer
```

All four return `Int`. Their meanings are distinct:

- `toInt()` truncates toward zero.
- `floor()` rounds toward negative infinity.
- `ceil()` rounds toward positive infinity.
- `round()` rounds to the nearest integer according to the target runtime.

The compiler recognizes these as built-in methods on `Float`. The interpreter
implements them directly, the Haxe backend emits `Std.int`, `Math.floor`,
`Math.ceil`, and `Math.round`, and the live ClojureScript backend evaluates
the corresponding JavaScript numeric operations. `WCHNTMaths` remains
appropriate for host maths such as random numbers, trigonometry, and colour
conversion; it is not needed merely to convert a Float.

## Why this class of bug hides

Most of WCHNT's typing is inference-based and looked up on demand
(`value-type`, `expr-return-type`, and `arith-result-type`). The numeric join
is applied when an `if` expression is assembled, while assignability applies
the one-way `Int`-to-`Float` widening rule for arguments and assignments.
Parameter inference uses the same numeric context before the final method
types are fixed, so the separate inference and checking passes agree.

Arithmetic nodes retain their inferred type when rendered. This is important
for division: an integer-only expression can use integer division, while a
Float expression keeps Haxe's Float division instead of being emitted as an
unintended `Std.int(dx / steps)` conversion.
