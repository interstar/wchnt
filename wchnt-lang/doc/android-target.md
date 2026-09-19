# Android Target (plan)

**Status:** not started — design notes for a future slice of work.

This document records how WCHNT might target Android for demonstration programs (Pong, Pollution, and similar canvas-style games). It covers the backend choice, harness template, and build/sideload pipeline.

See also: `target.md` (Target as shearing layer), `plan.md` (compiler architecture), `live.md` (canvas host + interpreter).

## Motivation

WCHNT already separates platform from assemblage logic:

- **Schema, Construction, Methods** — the game (or app) itself
- **Target** — outer environment: input snapshot, frame loop, drawing surface

Today we have `%terminal`, `%openfl`, and `%canvas`. Android would be another host. The same `.wcn` game logic should compile with only the Target section (and, where platform APIs differ, selected Methods) differing — exactly as `pollution_openfl.wcn` and `pollution_canvas.wcn` share Schema/Construction today.

Pollution in WCHNT was adapted from the original Android app at `~/Documents/PRODUCTION/games/newpollution`. That project is a useful reference for the harness shape: `SurfaceView`, game thread, `Canvas` drawing, swipe input via `GestureDetector`.

Initial goal: run small demos (Pong, Pollution) on a phone via sideloading — not a full production Android toolchain or Play Store pipeline.

## What stays the same

Regardless of backend path:

| Layer | On Android |
|-------|------------|
| Schema | Unchanged |
| Construction | Unchanged |
| Methods | Unchanged |
| Target | **Host-specific** — `%init`, `%step`, input inject, draw calls |

Target follows the established inject-then-tick pattern (see `target.md`):

1. Read input (keyboard, touch, swipe) and `inject` into `>` mailboxes.
2. Call `assemblage.time.update_mutates()` once per frame.
3. Draw via a portable graphics handle (`wchntGraphics`).

Methods must not embed Android APIs directly; platform handles pass through Target or `@Type/name` parameters, with their signatures declared in `%requires`.

## Current infrastructure (relevant pieces)

| Piece | Location | Role |
|-------|----------|------|
| Target parser | `src/wchnt_lang/target.cljc` | `%terminal`, `%openfl`, `%canvas` hosts |
| Haxe emitter | `src/wchnt_lang/compiler.cljc` | `emit-main-class`, OpenFL lifecycle |
| `WCHNTGraphics` | `src/wchnt_lang/haxe_helpers.cljc` | Portable draw API for OpenFL Target |
| Canvas harness | `live/public/harness.js` | Browser equivalent of `WCHNTGraphics` + `input.keys` |
| Build script | `go.sh` | WCHNT → Haxe; OpenFL branch writes `project.xml`, runs `lime test neko` |
| Examples | `examples/pollution_openfl.wcn`, `examples/pong_openfl.wcn` | OpenFL demos |
| Live examples | `live-examples/pollution_canvas.wcn`, `live-examples/pong_canvas.wcn` | Same logic, `%canvas` Target |

There is **no Android implementation** in the compiler today. `target.md` notes that Android swipe input would replace the keyboard inject body only.

## Backend choice: two paths

### Path A — WCHNT → Haxe → OpenFL/Lime (Android)

Reuse the existing Haxe backend and OpenFL host. Extend `go.sh` (or a sibling script) to run `lime test android` instead of `lime test neko`.

**Important nuance:** OpenFL on Android is **not** “Haxe compiles to Java source.” Lime typically compiles Haxe to **native (C++/NDK)** and wraps it in a standard Android Activity shell. The comparison is OpenFL-on-Android vs a native Java harness — not Haxe-to-Java vs hand-written Java.

| Pros | Cons |
|------|------|
| High reuse: `%openfl`, `WCHNTGraphics`, examples, `compiler.cljc` | Lime Android toolchain (SDK, NDK, versions) can be brittle |
| Fastest path to a demo on device | Generated code harder to debug than plain Java in Android Studio |
| Same draw API already implemented | OpenFL model (Sprite/stage) differs from original `newpollution` SurfaceView |
| Minimal compiler changes | Less natural fit if WCHNT grows toward native Android UI later |

**Compiler work:** small — add `%android` host (or reuse `%openfl` with a build flag), extend `go.sh` / `project.xml` for Android meta (package, permissions, orientation).

**Target work:** adapt input. Pong can use on-screen keys or external keyboard. Pollution originally used **swipes** (`GestureDetector` in `newpollution`); WCHNT Pollution today injects keyboard arrows. Android Target would inject swipe-derived booleans into `keys` — Methods unchanged.

### Path B — Java/Kotlin backend + Android template

Add an explicit codegen backend (parallel to `ir-to-haxe`) that emits Java classes. Slot them into a fixed Android project template modeled on `newpollution`.

| Pros | Cons |
|------|------|
| Matches familiar Android architecture (Activity, SurfaceView, game thread) | Large upfront work: new emitter + runtime semantics in Java |
| Plain Java/Kotlin — easy to debug in Android Studio | Two backends to maintain unless Haxe-for-mobile is dropped later |
| Natural home for swipe/touch in harness | Must implement construction factory, `$` notify, `>` inject, `update` in Java |
| Better long-term if WCHNT targets “real” Android apps | Narrow subset first; full language parity takes time |

**Compiler work:** large — `ir-to-java` (or Kotlin), or a staged approach emitting only factory + game classes initially.

**Harness work:** moderate — template project with stable contract (see below).

### Recommended phasing

1. **Spike Path A** — try `lime test android` with today’s `pollution_openfl.wcn` output. Validates toolchain pain on the developer machine with minimal WCHNT changes.
2. **If Path A is acceptable for demos** — ship Pong/Pollution via OpenFL Android; add `pollution_android.wcn` (or `%android` Target) with swipe inject.
3. **If Path A is too painful or native harness is preferred** — build Path B template + minimal Java emitter for demo games only.

Paths are not mutually exclusive forever, but maintaining two full backends long-term is costly. Pick one as primary after the spike.

## Android template (Path B, and conceptually similar for Path A)

The template is **harness code** — everything Target and the platform own. Generated WCHNT output slots in alongside it.

Proposed layout:

```
android-template/
  app/src/main/java/org/wchnt/
    MainActivity.java          # launches game view
    WCHNTGameView.java         # SurfaceView + SurfaceHolder.Callback
    WCHNTGameThread.java       # lock canvas → update → draw loop
    WCHNTGraphics.java         # Canvas/Paint wrapper
    WCHNTInput.java            # keyboard / swipe → snapshot for inject
  app/src/main/AndroidManifest.xml
  build.gradle, settings.gradle, ...
  generated/                   # populated at compile time (not in template repo)
    GameAssemblage.java
    Game.java, Player.java, ...
    MainBridge.java            # wires factory + Target %init/%step bodies
```

### Stable contract (harness ↔ generated code)

Keep the seam small — mirror OpenFL/canvas:

| Contract | Responsibility |
|----------|----------------|
| `GameAssemblage.factory()` | Construction IR → root assemblage instance |
| `%init` | One-time setup (listeners, dimensions) |
| `%step` | Each frame: inject input → `time.update_mutates()` → draw |
| `wchntGraphics` | `clear`, `beginFill`, `endFill`, `lineStyle`, `drawRect`, `drawCircle`, `moveTo`, `lineTo`, `fillText` |
| `%name` helpers | Optional Haxe/Java fragments callable from Methods |

`WCHNTGraphics` on Android wraps `android.graphics.Canvas` and `Paint`, matching the API already documented in `target.md` for OpenFL and canvas hosts.

### Reference: original Pollution harness

`newpollution` (`~/Documents/PRODUCTION/games/newpollution`) provides a working pattern:

- `GameView` — `SurfaceView`, `GestureDetector` for fling → direction
- `MainThread` — `lockCanvas` / `update` / `draw` / `unlockCanvasAndPost`
- `GameWorld` — game state and drawing (in WCHNT this becomes Methods + Construction)

WCHNT Target `%step` replaces the imperative glue between input, update, and draw; the template owns the thread and surface lifecycle.

## Build pipeline (sideload)

End-to-end flow for Path B:

```bash
# 1. Compile WCHNT → generated Java (future backend)
lein run examples/pollution_android.wcn --target java -o generated/

# 2. Assemble project from template
cp -r android-template/ build/pollution-android/
cp -r generated/* build/pollution-android/app/src/main/java/org/wchnt/generated/

# 3. Build debug APK
cd build/pollution-android && ./gradlew assembleDebug

# 4. Sideload to connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Path A (OpenFL/Lime) analogue:

```bash
lein run examples/pollution_openfl.wcn > generated/Main.hx
# go-android.sh writes project.xml with Android meta, runs:
lime test android -debug
adb install -r Export/android/bin/app-debug.apk
```

A single entry script (e.g. `go-android.sh`) should mirror `go.sh`: WCHNT file in → APK out, with clear failure messages and artifacts left on disk when the build fails.

**Prerequisites:** Android SDK, `ANDROID_HOME`, device USB debugging or emulator; for Path A also Lime/OpenFL Android target setup.

## Example programs

| Program | Notes for Android |
|---------|-------------------|
| **Pong** | Keyboard inject works; optional on-screen buttons later |
| **Pollution** | Prefer swipe inject (like `newpollution`); Schema/Construction/Methods same as OpenFL/canvas versions |

Example file pairs (future):

- `examples/pollution_android.wcn` — `%android` or `%openfl` + Android-specific Target (swipe inject)
- `examples/pong_android.wcn` — frame loop + draw; keyboard or touch

Shared sections should remain identical to `pollution_openfl.wcn` / `pong_openfl.wcn`; only Target (and host line) differ.

## Compiler and repo changes (checklist for when we start)

### Path A (OpenFL/Lime)

- [ ] `target.cljc` — register `%android` if distinct from `%openfl`, or document `%openfl` + Android build flag
- [ ] `go-android.sh` — Android `project.xml`, `lime test android`, optional `adb install`
- [ ] Example `pollution_android.wcn` with swipe-based inject in `%init`/`%step`
- [ ] `target_test.clj`, `examples_test.clj` — as needed
- [ ] Document SDK/NDK setup in this file or a short `doc/android-setup.md`

### Path B (Java template)

- [ ] `android-template/` in repo (minimal Gradle project)
- [ ] `WCHNTGraphics.java`, game loop, input adapter
- [ ] `ir-to-java.cljc` (or narrow emitter for demo subset)
- [ ] `compiler.cljc` — branch on host / `--target java`
- [ ] `go-android.sh` — template copy + generate + gradle + adb
- [ ] Tests for emitted Java (compile-only or Robolectric later)

## Open questions

- **Host name:** `%android` vs reusing `%openfl` with platform-specific build script?
- **Language:** Java vs Kotlin for template and emitted code?
- **Input model for Pollution:** map swipes to the existing four-boolean `Keys` mailbox, or extend schema for direction/velocity?
- **Interpreter on device:** ship JVM/Clojure interpreter in APK? Probably too heavy for demos; codegen preferred.
- **Haxe `-java` target:** emit Haxe, compile to `.java` with `-java`, drop into template — possible for a language subset without OpenFL, but untested here.

## Summary

Adding Android as a WCHNT target fits the existing architecture: Target is the shearing layer; demo games need a harness (graphics, loop, input) and a build path to APK. **Path A (OpenFL/Lime)** is the lowest-effort proof of concept; **Path B (Java template + emitter)** aligns with the original Pollution app and is better long-term for native Android. Start with a Lime Android spike; decide backend based on toolchain pain and how “native” the harness needs to feel.

No work is scheduled until a future session explicitly picks up this slice.
