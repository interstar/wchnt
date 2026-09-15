# wchntGraphics

`wchntGraphics` is the portable drawing surface available to Target code. It is what your
`%step` (or `%init`) uses to draw to the window or canvas, and it exposes the **same API and
the same visual result on both the OpenFL and browser-canvas hosts**. Text hosts
(`%cli` / `%cli-live`) have the sibling object `wchntConsole` (`print` / `println`) instead.

It is deliberately small: rectangles, circles, ellipses, lines, filled/stroked paths, and text.
Schema / Construction / Methods stay platform-independent; only the Target draws.

## The model

`wchntGraphics` follows the Processing / Flash / OpenFL **stateful** model:

- `beginFill(color[, alpha])` turns **fill** on.
- `lineStyle([thickness, color[, alpha]])` turns **stroke** on; `lineStyle()` with no arguments
  (or `noStroke()`) turns it off.
- `endFill()` flushes the current path (fill **and** stroke, whichever are active) and turns
  fill off.
- `drawRect` / `drawCircle` / `drawEllipse` draw a shape that is **filled and/or stroked**
  according to the current state. Fill on = filled; only stroke on = outline; neither = nothing.
- `moveTo` / `lineTo` build a path; `endFill()` closes, fills, and strokes it.
- `drawLine` is stroke-only (a line has no interior).

`clear()` resets the style and paints the whole surface with the background colour;
`background(color[, alpha])` sets that colour first.

Colours are **24-bit integers written in hex**: `0xRRGGBB` (`0xf2f2f2` is light grey,
`0x00ff00` is green). `alpha` is `0..1` and is honoured on **both** hosts.

A translucent `background(color, alpha < 1)` gives a motion-trail fade on both hosts. On the
browser canvas that is a pixel blend; on OpenFL the previous frame's vectors are retained
(fine for short runs, grows unbounded over long ones — a bitmap-backed target would fix that).

## The API

| Method | What it does |
|---|---|
| `background(color[, alpha])` | Set the clear colour (used by the next `clear()`). |
| `clear()` | Reset style and paint the whole surface with the background colour. |
| `beginFill(color[, alpha])` | Turn fill on (opaque by default). |
| `endFill()` | Flush the current path as fill + stroke, then turn fill off. |
| `lineStyle([thickness, color[, alpha]])` | Turn stroke on; no arguments turns it off. |
| `noStroke()` | Turn stroke off. |
| `moveTo(x, y)` | Begin/extend a path. |
| `lineTo(x, y)` | Add a path segment. |
| `drawLine(x1, y1, x2, y2)` | Draw a stroked line. |
| `drawRect(x, y, width, height)` | Draw a rectangle (filled and/or stroked). |
| `drawCircle(x, y, radius)` | Draw a circle (filled and/or stroked). |
| `drawEllipse(x, y, rx, ry)` | Draw an ellipse (filled and/or stroked). |
| `fillText(text, x, y)` | Draw text in the current fill colour. |

## Typical usage

Clear, then fill the background and draw a ball:

```js
wchntGraphics.clear();
wchntGraphics.beginFill(0x2a2a2a);
wchntGraphics.drawRect(0, 0, 800, 600);
wchntGraphics.endFill();
wchntGraphics.beginFill(0xf2f2f2);
wchntGraphics.drawCircle(b.x, b.y, b.rad);
wchntGraphics.endFill();
```

Outline a rectangle instead of filling it (then turn the stroke off again):

```js
wchntGraphics.lineStyle(2, 0x00ff00);
wchntGraphics.drawRect(r.x, r.y, r.width, r.height);
wchntGraphics.noStroke();
```

Fill **and** outline a circle, a line, and a filled polygon:

```js
wchntGraphics.beginFill(0x6464ff);
wchntGraphics.lineStyle(2, 0xffffff);
wchntGraphics.drawCircle(100, 100, 40);
wchntGraphics.endFill();
wchntGraphics.noStroke();

wchntGraphics.lineStyle(4, 0xffffff);
wchntGraphics.drawLine(20, 300, 760, 300);
wchntGraphics.noStroke();

wchntGraphics.beginFill(0xc8c864);
wchntGraphics.moveTo(100, 400);
wchntGraphics.lineTo(200, 340);
wchntGraphics.lineTo(300, 400);
wchntGraphics.lineTo(100, 400);
wchntGraphics.endFill();
```

## Parity example

`examples/graphics_openfl.wcn` and `live-examples/graphics_canvas.wcn` draw the same scene
with identical calls — a smoke test that the two hosts agree.

## Passing wchntGraphics to Target Methods

You can hand `wchntGraphics` into a `@Graphics/g` method and call it there. The calls chain in
source; the compiler unrolls the `Void` chain for you:

```wchnt
Shape::draw = { @Graphics/g | } -> Void

Circle::draw = { @Graphics/g |
  g.beginFill(15316448).drawCircle(x, y, radius).endFill()
} -> Void
```

Then, from Target:

```js
for (var s of assemblage.shapes) {
    s.draw(wchntGraphics);
}
```

> **Caveat:** on the OpenFL host, a `@Graphics/g` parameter types `g` as the raw
> `openfl.display.Graphics` object, which has a smaller surface than `wchntGraphics`. It has
> `beginFill` / `endFill` / `lineStyle` / `moveTo` / `lineTo` / `drawRect` / `drawCircle` /
> `drawEllipse`, but **not** `drawLine`, `noStroke`, `background`, or `fillText` (write
> `g.moveTo(x1,y1).lineTo(x2,y2)` for a line and `g.lineStyle()` for no-stroke there). The
> browser harness hands the same object to both, so those extra methods are available on
> canvas. Prefer the full `wchntGraphics` API in `%init` / `%step` when you need them on both.

The canonical examples show all of this in action: `bounce` (fill + circle), `pollution`
(outline + text), `shapes` (paths via `@Graphics/g`), and `graphics` (the parity scene).
