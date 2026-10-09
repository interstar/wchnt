# WCHNT examples for Pharo 14

These examples target Pharo 14. **Bounce** is the first graphical slice:
WCHNT Schema, Construction, and Methods compile to Smalltalk, while a native
Morphic `WCHNTGraphics` adapter opens a window and drives the animation. I-Spy
is retained as an earlier console experiment; its popup-based `WCHNTConsole`
adapter is not a supported Smalltalk console API.

## Compile and run Bounce

From the `wchnt-lang` project directory, compile the WCHNT source:

```sh
lein run smalltalk-examples/bounce.wcn | tail -n +2 > smalltalk-examples/bounce.st
```

The first output line is a host banner, so `tail` removes it. The generated
`bounce.st` file is a Pharo file-out containing the WCHNT classes, methods, and
the Morphic graphics adapter.

In Pharo 14:

1. Open a Playground (**Browse → Playground**).
2. Evaluate the file-in expression below with **Do it** (Ctrl+D on Linux or
   Windows; Cmd+D on macOS). Replace the path with the full path to `bounce.st`.

   ```smalltalk
   CodeImporter evaluateFileNamed: '/full/path/to/wchnt-lang/smalltalk-examples/bounce.st'.
   ```

3. In the Playground, evaluate this with **Do it**:

   ```smalltalk
   GameAssemblage new main.
   ```

   A window should open with a green play area and a moving white ball. Close
   the window to stop the animation.

The Morphic adapter uses `drawOn:` for rendering and Morphic's `step` / `stepTime`
callbacks for animation. The generated entry point runs native Smalltalk `%init`
once and installs native Smalltalk `%step` code as the Morph's callback. WCHNT
hex colour literals are emitted in Pharo's `16r...` radix syntax; packed and
explicit alpha values are converted into Morphic alpha-blended drawing.

## Compile and run Butterfly

Butterfly is the first input experiment. It exercises the Morphic input adapter:
mouse position/button state and the characters pressed since the previous frame
are copied into the WCHNT `Inbox` each step. Click and drag to paint mirrored
circles; press **1–9** to change colour.

From the `wchnt-lang` project directory:

```sh
lein run smalltalk-examples/butterfly.wcn | tail -n +2 > smalltalk-examples/butterfly.st
```

In a Pharo 14 Playground, file in the generated `.st` file and run its entry
point, just as for Bounce:

```smalltalk
CodeImporter evaluateFileNamed: '/full/path/to/wchnt-lang/smalltalk-examples/butterfly.st'.
```

Then evaluate:

```smalltalk
PaintAssemblage new main.
```

The generated main opens a Morphic window. Close that window to stop its stepping
loop. This experiment is specifically using Morphic for window/event lifecycle;
the WCHNT drawing and input operations remain behind `WCHNTGraphics` and
`WCHNTInput`.

## Compile I-Spy

From the `wchnt-lang` project directory:

```sh
lein run smalltalk-examples/ispy.wcn | tail -n +2 > smalltalk-examples/ispy.st
```

The first output line from the compiler is a host banner; `tail` removes it so
the result is a Smalltalk chunk-format file-out.

## Load and run in Pharo

1. Open your Pharo 14 image.
2. **Left-click** an empty part of the Pharo desktop to open the World menu.
   (Right-click opens **World contents**, the smaller menu in your screenshot.)
   Hover over **Browse**, then choose **Playground** from its submenu.
3. In the Playground, paste the expression below, replacing the example path
   with the full path to this project's `smalltalk-examples/ispy.st` file.
   Then put the cursor on that line and press **Ctrl+D** (or choose **Do it**
   from the right-click menu). Evaluate the file using
   `CodeImporter evaluateFileNamed:` (this is the path verified by the Pharo
   14 import probes below). The file defines the classes and installs the
   methods into the currently open image. Loading the file does not start the
   game.
4. In the Playground, replace the file-in expression with the following and
   press **Ctrl+D** (or choose **Do it**). This explicitly calls the example's
   entry point:

   ```smalltalk
   GameAssemblage new main.
   ```

   The introductory message should appear in the Transcript. Each guess opens
   a Pharo input dialog; the reply and next clue appear in the Transcript.
   Cancel the dialog to end the game.

The expression to evaluate is:

```smalltalk
CodeImporter evaluateFileNamed: '/full/path/to/wchnt-lang/smalltalk-examples/ispy.st'.
```

After the file has been evaluated, run `GameAssemblage new main.` in the
Playground as described above to start the game.

The generated code and runtime adapters are intentionally specific to Pharo
14 for this experiment. Adventure is planned as the next example after the
backend adds the map, enum, switch, and write-path support it needs.
