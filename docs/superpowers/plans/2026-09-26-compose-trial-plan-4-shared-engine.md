# Compose Trial Plan 4: The Shared Engine Moves into core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the four UI-neutral pieces the Compose app would otherwise copy (arena geometry, the shot pipeline, the calibration flow and exercise host support) out of `javafx-app` into `core` and `plugin-api`, switch the JavaFX app onto each in the same task, and pin `JavaFxExerciseHost` with a shared `ExerciseHost` contract suite, with the JavaFX app behaving exactly as before.

**Architecture:**
- **Task 1: arena geometry.** `core`'s `com.shootoff.geom.ArenaGeometry` holds the camera ↔ canvas ↔ arena arithmetic that was in `CanvasManager`, operation for operation. `CanvasManager`'s translate helpers and `scaleShotToArenaBounds` call it.
- **Task 2: one shot queue.** `ShotDetector.submitShot` hands every detected shot to `core`'s `ShotQueue`: one thread, detection order. It replaces a new "Shot Notifier" thread per shot, which let two shots from one camera frame change the JavaFX shot timer at once.
- **Task 3: the shot pipeline.** `core`'s `ShotPipeline` does everything between a detected shot and the exercise: processors, laser sound, the timer row, the marker, passing shots inside the projection to the arena in arena coordinates, hit-testing on the right `TargetSet`, session recording, region commands and delivery. A user interface supplies a `ShotPipeline.Surface`; `CanvasManager` is the JavaFX one. The session recorder and recording cameras move from `Configuration` to `Settings`. The shot timer's text moves to `TimerRow`, and `poi_adjust`'s arithmetic to `PoiAdjustment`.
- **Task 4: the calibration flow.** `core`'s `CalibrationFlow` is the state machine: stop the projector exercise → full screen → pattern and auto-detect → success, or timeout to the manual box → save bounds → restore, then restart the exercise. `CalibrationManager` keeps only the JavaFX drawing and input, behind `CalibrationFlow.View`. `CameraManager` implements `CalibrationCamera`.
- **Task 5: exercise host support.** `plugin-api`'s `ExerciseHostSupport` (package `com.shootoff.exercise.host`) holds the UI-neutral half of `JavaFxExerciseHost`: the exercise thread and callbacks, exactly-once stop with the targets to remove, name resolution (jar first), timer rows, and the par/delay listeners. `SavedBackground` holds background save/restore. `JavaFxExerciseHost` becomes a JavaFX layer over them.
- **Task 6: the contract suite.** `plugin-api`'s test fixtures gain `ExerciseHostContract`, an abstract JUnit 5 suite a host's test subclasses with a `Harness`. The JavaFX app runs it twice: on a camera feed, and on the mirrored projector arena. Plan 5 adds the Compose subclass.
- **Task 7: owner check.** A v1 binary check, then the owner's JavaFX regression check on the hardware.

**Tech Stack:** Java 21 toolchain (records, sealed interfaces, pattern-matching `switch`), Gradle 8.14 wrapper (Kotlin DSL) with `java-library`, `java-test-fixtures` and `maven-publish`, OpenJFX 21 (javafx-app only), `java.util.concurrent`, JUnit 5 + JUnit 4 (vintage). No new dependencies: Kotlin and Compose arrive in Plan 5.

**Spec:** `docs/superpowers/specs/2026-09-26-compose-trial-design.md`. This plan is §2 "Moved into `core` / `plugin-api` first" (items 1–4), the Plan 4 parts of §5 (the new `core` and `plugin-api` unit tests, and the shared `ExerciseHost` contract suite run against `JavaFxExerciseHost`), and the §6 Plan 4 bullet. Plan 5 (`compose-app`) is not in scope. Plans 1–3 (sub-project 1a) are done; the gate stands at **371**.

## Plan-author rulings

Where the spec is silent or disagrees with the code, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

1. **Packages.** `core` gains `com.shootoff.geom.ArenaGeometry` (next to `Rect`, `Size` and `Point`), `com.shootoff.shots` (`ShotQueue`, `ShotPipeline`, `ShotTimer`, `TimerRow`, `PoiAdjustment`) and `com.shootoff.calibration` (`CalibrationFlow`, `CalibrationCamera`). `plugin-api` gains `com.shootoff.exercise.host` (`ExerciseHostSupport`, `SavedBackground`). That package is for hosts, not exercise authors, so it stays out of `com.shootoff.exercise`. *Cost if wrong:* renames before Plan 5 uses them.
2. **The session recorder and the recording cameras move from `Configuration` to `Settings`**, with the same six methods (`register…`, `unregister…`, `unregisterAll…`, `getRecordingManagers`, `setSessionRecorder`, `getSessionRecorder`). The pipeline in `core` records shots and needs them. Code compiled against `Configuration` still links: the JVM finds the methods in the superclass. *Cost:* none known; Task 7 Step 1 checks the descriptors.
3. **One shot queue for every camera.** Detected shots are handled on one daemon thread, `Shot Notifier`, in detection order (`ShotQueue.shared()`). This is how the spec's "rows are appended on one thread in order" is met, for the JavaFX app too. Behavior change: a v1 exercise that blocks inside `shotListener` now delays the next shot's marker and row, though not its time. For example, Shoot Don't Shoot's "Bad shoot!" speech, or its `NewRound` run in place. Before, each shot had its own thread. *Cost if wrong:* a visible lag after a don't-shoot hit. Task 7 looks for it.
4. **Rows stay on the shot thread.** The JavaFX row is still appended on the thread that handles the shot, now the queue's, before the exercise hears the shot. So a v1 exercise's `setShotTimerColumnText` from `shotListener` fills that shot's row, as before. Moving appends to the JavaFX thread would reorder them against v1 code. *Cost:* the `TableView` still reads a list changed off the JavaFX thread, as it always has.
5. **The ignored laser color stays in `ShotDetector`.** It is already in `core` (`checkIgnoreColor`, covered by `TestShotDetector.ignoredLaserColorIsDropped`), and it runs before a shot reaches the pipeline. *Cost:* none.
6. **The pipeline keeps the JavaFX canvases' rules through its `Surface` port:**
   - A *mirrored* shot is a copy on the arena tab: no processors, sound or recording, and its region commands know it is a copy.
   - Only a camera feed's surface has an `arena()`.
   - Each surface decides in `deliver` whether it feeds the exercise. For the JavaFX canvases that is today's rule: not a mirrored canvas that has a camera manager, except for shots passed on to the arena.
   - A surface without a shot timer adds no row, and keeps the malfunction and reload flags for later.

   Compose passes `mirrored = false` everywhere. *Cost:* the pipeline's API carries a JavaFX-shaped `mirrored` flag.
7. **Region commands:**
   - The pipeline decides when commands run: on a hit whose model region has a `command` tag. The user interface carries them out, as `RegionCommand`'s Javadoc already says, because `animate`, `reverse` and `play_sound` act on the UI's own drawing.
   - `poi_adjust`'s arithmetic and its settings and sounds move to `PoiAdjustment`.
   - JavaFX still measures the hit region's center from the node (`getBoundsInParent()` plus the target's position), so the offsets are exactly as before.

   *Cost:* Compose measures the center from the model in Plan 5.
8. **Target session events stay with the target views.** Added, moved, resized and removed are the target's lifecycle, not the shot pipeline's, so they stay in `CanvasManager.addTarget/removeTarget` and `TargetView` with the `recordsSessionEvents` guard. The spec's "session recording" in the pipeline is shot recording. *Cost:* Compose records target events itself. Its Sessions destination is disabled in 1b.
9. **The shot timer:**
   - `TimerRow` is the row's text: time, split and laser, formatted exactly as `ShotEntry` did.
   - `ShotTimer` is the port the pipeline appends through.
   - `ShotEntry` keeps its API and builds its text from `TimerRow`.
   - A host's `addTimerRow` row comes from `ExerciseHostSupport.noShot`/`timerRow`: red, at (−10, −10).
10. **The calibration flow runs on the caller's thread.**
    - As before, the camera reports success on its own thread, and the flow's timers come back on the UI thread (`View.runOnUiThread`).
    - Messages are an enum. The texts, colors and chime delays stay in JavaFX.
    - Two old quirks are kept: the exercise to restart isn't cleared after a stop, and the full-screen flag is only what `setFullScreen` last said.

    *Cost:* Compose must call `setFullScreen` when its arena window changes.
11. **The JavaFX classes keep their public API:**
    - `CalibrationManager` keeps its constructor and every public method (`ProjectorSlide`, `ShootOFFController` and `TestAutoCalibration` use them).
    - `CanvasManager` keeps `addShot`, `addArenaShot`, `checkHit`, `scaleShotToArenaBounds` and the translate helpers (tests call them). They now delegate.
    - `JavaFxExerciseHost` keeps its API.

    No existing test changes. *Cost:* thin delegating methods stay in the JavaFX classes.
12. **Host support is generic in the UI's target type** (`ExerciseHostSupport<T>`). It tracks targets. The UI host still tracks its own nodes (labels, buttons, panes, columns, markers) and removes them when `stop()` returns a `Stopped`. `stop()` on the JavaFX thread still tears down synchronously (the fix in `521e9bcb`), and a jar target on the arena is still added by file on the projector canvas and hidden on both copies (`bc444821`). *Cost:* none known.
13. **The contract suite lives in `plugin-api`'s test fixtures** (`com.shootoff.exercise.ExerciseHostContract`):
    - JUnit is `testFixturesCompileOnly`, so the published fixtures still depend on nothing new. The drill fetches them with `isTransitive = false` and never loads the class.
    - `javafx-app` tests add `testImplementation(testFixtures(project(":plugin-api")))`.
    - The suite drives shots with the host's own entry point (`JavaFxExerciseHost.deliverShot`), not the pipeline, which has its own tests.
    - It doesn't run against `FakeExerciseHost`, which by design runs callbacks on the caller's thread.

    *Cost:* Plan 5's Compose test module needs the same `testFixtures` dependency.
14. **Log text.** Moved code logs the same messages, with one exception. The debug line for a hit now names the model region's class ("RectangleRegion") where it named the v1 `RegionType` ("RECTANGLE"). *Cost:* none; debug only.
15. **New tests never write `shootoff.properties`.** `TestPoiAdjustment` overrides `Settings.writeConfigurationFile`. Existing tests (`TestTargetCommands`, `TestCanvasManager.testPOIAdjust`) write it as they always have. So Task 1 saves the owner's copy, and Task 7 puts it back before the hardware check.
16. **Order.** Tasks 1–5 follow the spec's order. The race fix (Task 2) comes before the rest of the pipeline (Task 3) because a reviewer can judge it on its own, and its reproduction test must fail first against today's code. The contract suite (Task 6) comes last, once the host it pins is on the shared support.

**v1 API breaks: none.** Every change to a type a v1 plugin can see is internal or additive (see "The v1 surface"). **Nothing in the spec's Plan 4 is infeasible.** Ruling 3 is the one visible behavior change, and it is the fix the spec asks for.

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never commit to `master`, never push.
- No `Co-Authored-By` trailer in commit messages. Verify after every commit with `git log -1 --format=%B`.
- Never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- Use `git mv` for every move or rename so history follows. Never delete and re-create a moved file. (This plan extracts code into new files and moves no file; the rule stands if a task needs a move.)
- Code style: tabs; match surrounding conventions.
  - Every **new main-source** Java file starts with the GPL header, copied verbatim from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file), then a blank line. The code blocks below start at the `package` line.
  - New test files follow the repo's test style, which has no header.
- Package names stay `com.shootoff.*`.
- `core` and `plugin-api` must not depend on OpenJFX. `TestNoJavaFxInCore` and `TestNoJavaFxInPluginApi` enforce it for `core`, `plugin-api` and `plugin-api`'s test fixtures, so `ExerciseHostContract` must not reference `javafx.*` either.
- Unchanged formats: `.target`, `.course`, the session formats (XML/JSON), `shootoff.properties`, and `shootoff.xml` descriptors.
- Existing test classes and methods keep their package, class, method names and bodies: the baseline compares tests by `class.method`, and this plan changes no existing test (ruling 11).
- No new third-party dependencies, and the version catalog stays as it is. Kotlin and Compose come in Plan 5. Task 6 only adds existing catalog entries to the `testFixturesCompileOnly` configuration (ruling 13).
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- `javafx-app/src/test/resources/targets/hit-parity.txt` is never hand-edited.
- **The owner's files.** Never modify, move or delete:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - `exercises/RandomTargetParDrill.jar` and `exercises/RandomTargetParDrill-v2.jar` (Task 7 Step 3 moves the v2 jar aside and back, as Plan 3's check did)
  - anything under `exercise-data/`
  - any file the owner added under `targets/` or `courses/`
- New tests must not write `shootoff.properties` (ruling 15).
- **Behavior stays identical** for the owner's settings (`shootoff.arena.show.markers=false`, calibrated behavior `ONLY_IN_BOUNDS`) and for every other value: every moved method keeps its arithmetic operation for operation, and its calls in the same order.
- **Publishing:** only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`.
- **Test gate** (unchanged from Plans 1–3; run it in ShootOFF):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  It must print `0 regressions; 0 new failures`. A full run takes several minutes, so use a Bash timeout of 600000 ms. The `N/M passing` count starts at **371** (end of Plan 3). Each task states "passing = previous + N", where N is the number of test methods the task adds. A lower count means tests silently stopped running.
- **v1 plugins keep loading and running.** The v1 surface of Plan 3 stays source- and binary-compatible (next section). The installed `exercises/RandomTargetParDrill.jar` (v1.1) and `exercises/RandomTargetParDrill-v2.jar` must keep working. Task 7 Step 1 checks the drill's members with `javap`.

## The v1 surface

Plan 3's list stands unchanged: `javap -c -p` of the installed `exercises/RandomTargetParDrill.jar` references `targets.Target`, `TargetRegion`, `Hit`; `gui.CanvasManager.getCanvasGroup` and `addShot(DisplayShot, boolean)`; `ProjectorArenaPane.getCanvasManager`; `LocatedImage.<init>(InputStream, String)`; `ProjectorTrainingExerciseBase`, `TrainingExerciseBase`, `TrainingExercise`, `ExerciseMetadata`; `camera.Shot`, `ShotColor`, `DisplayShot`, `ArenaShot`, `Configuration.getMarkerRadius`, `NamedThreadFactory`.

**What this plan does to types v1 plugins can see.** All of it is internal or additive:
- `CanvasManager`: every public and protected member stays, with the same signature. `addShot(DisplayShot, boolean)`, `addArenaShot`, `checkHit`, `scaleShotToArenaBounds`, `translateCameraToCanvas`, `translateCanvasToCamera` and `translateCanvasToCameraPoint` now delegate to `core`. Private helpers and the private malfunction and reload flags go.
- `Configuration`: the six session-recorder and recording-camera methods are now inherited from `Settings`, with identical descriptors (ruling 2).
- `CameraManager` (in `core` since Plan 1) also implements `com.shootoff.calibration.CalibrationCamera`. Its methods are unchanged.
- `ShotEntry`, `TargetCommands`, `CalibrationManager`, `JavaFxExerciseHost`: public API unchanged.

**Sanctioned breaks: none.**

## Review Focus

1. **Two lasers in one camera frame** (or two shots within a few milliseconds). The expected result: two shot timer rows, in detection order, no exception, and no shot lost. Before this plan, the JavaFX shot timer threw "Called endChange before beginChange" and dropped a row.

   *Tests:*
   - `TestShotTimerRowOrder.twoShotsInOneFrameMakeTwoRowsInOrderOnOneThread`: the real JavaFX path through `CameraManager.injectShot`. It fails first against today's code (Task 2).
   - `TestShotQueue.shotsAreHandledOneAtATimeInTheOrderTheyArrive` (Task 2)
   - `TestShotPipeline.twoShotsInOneFrameMakeTwoRowsInOrderOnOneThread` (Task 3)
2. **Calibrating while the v2 drill runs.** Expected: the drill stops first and restores the arena's background synchronously, then calibration saves that background and shows the pattern. After calibration the background comes back, and only then does a fresh drill start. If the order slips, the drill's teardown overwrites the pattern and auto-calibration fails, as in Plan 3's hardware check.

   *Tests:*
   - `TestCalibrationFlow.startingOnAFullScreenArenaStopsTheDrillBeforeSavingTheBackgroundAndShowingThePattern` and `autoCalibrationSavesTheBoundsThenRestoresTheBackgroundBeforeRestartingTheDrill` (Task 4)
   - `TestJavaFxExerciseHost.stopFinishesRestoringTheBackgroundBeforeReturningOnTheFxThread`, which stays unchanged and green (Task 5)
3. **A v2 projector exercise hides or removes its target on the mirrored arena.** Expected: the target goes dark, or goes away, on the projector window *and* on the arena tab, whether it came from the exercise's jar or from ShootOFF's `targets/`. After `stop()`, no copy is left on either canvas. This is the class of bug fixed in `bc444821`.

   *Tests:* `TestJavaFxExerciseHostContractOnArena`: `hidingATargetHidesItOnEveryViewOfTheSurface`, `removingATargetRemovesItFromEveryViewOfTheSurface` and `stopRemovesEverythingTheExerciseAddedAndLaterCallsChangeNothing` (Task 6). Task 6 Step 6 shows the suite failing when `bc444821` is undone.
4. **A shot on the edge of the calibrated projection, or after the arena window changes size.** Expected: edges count as inside, so the shot goes to the arena. The arena's *current* size scales it: the pipeline reads it per shot, as `scaleShotToArenaBounds` read `arenaPane.getWidth()`.

   *Tests:*
   - `TestShotPipeline.aShotOnTheProjectionsEdgeGoesToTheArenaAtTheArenasCurrentSize` (Task 3)
   - `TestArenaGeometry.projectionCornersLandOnArenaCorners` (Task 1)
5. **An exercise's shot callback throws, or a v1 exercise blocks in it.** Expected: the shot queue logs the exception and handles later shots. A blocking callback delays later shots but loses none (ruling 3).

   *Tests:*
   - `TestShotQueue.aShotThatFailsDoesNotStopLaterOnes` (Task 2)
   - `TestShotQueue.shotsAreHandledOneAtATimeInTheOrderTheyArrive`: a slow first shot (Task 2)
   - owner check, Task 7 Step 3, items 5 and 8

The owner's check (Task 7) covers what no unit test reaches: the webcam and projector, real calibration, the real menus, sounds, and a session recorded and replayed.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/.../geom/ArenaGeometry.java`; `javafx-app/.../gui/CanvasManager.java` (translate helpers, `scaleShotToArenaBounds`) | camera ↔ canvas ↔ arena arithmetic | 1 |
| `core/.../shots/ShotQueue.java`; `core/.../camera/shotdetection/ShotDetector.java` (`submitShot`) | one thread for shots | 2 |
| `core/.../shots/{ShotPipeline,ShotTimer,TimerRow,PoiAdjustment}.java`; `core/.../config/Settings.java`; `javafx-app/.../config/Configuration.java`, `gui/CanvasManager.java`, `gui/ShotEntry.java`, `gui/targets/TargetCommands.java` | the shot pipeline | 3 |
| `core/.../calibration/{CalibrationFlow,CalibrationCamera}.java`; `core/.../camera/CameraManager.java` (implements); `javafx-app/.../gui/CalibrationManager.java` | the calibration flow | 4 |
| `plugin-api/.../exercise/host/{ExerciseHostSupport,SavedBackground}.java`; `javafx-app/.../gui/exercise/JavaFxExerciseHost.java` | host support | 5 |
| `plugin-api/src/testFixtures/.../exercise/ExerciseHostContract.java`; `plugin-api/build.gradle.kts`, `javafx-app/build.gradle.kts`; `javafx-app/src/test/.../gui/exercise/{JavaFxHostHarness,TestJavaFxExerciseHostContract,TestJavaFxExerciseHostContractOnArena}.java` | the contract suite | 6 |
| none | owner check | 7 |

New tests: `core`: `geom/TestArenaGeometry` (5), `shots/TestShotQueue` (2), `shots/TestShotPipeline` (10), `shots/TestTimerRow` (1), `shots/TestPoiAdjustment` (2), `calibration/TestCalibrationFlow` (7). `plugin-api`: `exercise/host/TestExerciseHostSupport` (11). `javafx-app`: `gui/TestShotTimerRowOrder` (1), `gui/exercise/TestJavaFxExerciseHostContract` (8), `gui/exercise/TestJavaFxExerciseHostContractOnArena` (8). The gate ends at **426**.

---
### Task 1: Arena geometry in `core`

**Files:**
- Create: `core/src/main/java/com/shootoff/geom/ArenaGeometry.java`
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` (imports; `translateCameraToCanvas`, `translateCanvasToCamera`, `translateCanvasToCameraPoint`, `scaleShotToArenaBounds`; two private helpers)
- Test: `core/src/test/java/com/shootoff/geom/TestArenaGeometry.java` (new)

**Interfaces:**
- Consumes: `com.shootoff.geom.Rect`, `Size`, `Point` (core).
- Produces: `public final class com.shootoff.geom.ArenaGeometry`, static methods only:
  - `Rect cameraToCanvas(Rect bounds, Size feed, Size display)`: returns `bounds` itself when feed and display are the same size
  - `Rect canvasToCamera(Rect bounds, Size feed, Size display)`: returns `bounds` itself when feed and display are the same size
  - `Point canvasToArena(double x, double y, Rect projection, Size arena)`: `projection` on the canvas
  - `Point arenaToCamera(double x, double y, Rect projection, Size arena)`: `projection` on the camera feed

  Task 3's pipeline and Task 4's calibration flow use them.

- [ ] **Step 0: Save the owner's settings file and checksums**

The gate's existing POI tests rewrite `shootoff.properties` (ruling 15). Save the owner's copy once, before the first gate run of this plan:

```bash
cd /home/bfears/projects/ShootOFF
test -f build/plan4-shootoff.properties || cp shootoff.properties build/plan4-shootoff.properties
sha256sum RandomTargetParDrill-bests.properties exercises/RandomTargetParDrill.jar exercises/RandomTargetParDrill-v2.jar > build/plan4-owner-files.sha256
cat build/plan4-owner-files.sha256
```

Expected: three checksum lines.

- [ ] **Step 1: Write the failing test**

`core/src/test/java/com/shootoff/geom/TestArenaGeometry.java` compares `ArenaGeometry` with the old `CanvasManager` bodies, kept in the test as the legacy oracle. It compares exactly (`assertEquals` on `Rect`/`Point`, no tolerance), across five feeds, five displays, five projections, five arena sizes and eight points:

```java
package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ArenaGeometry against the math CanvasManager used before it moved to core. Each legacy method below
 * is the old body with the JavaFX types and the logging taken out.
 */
class TestArenaGeometry {
	private static final List<Size> FEEDS = List.of(new Size(640, 480), new Size(1280, 720), new Size(1920, 1080),
			new Size(320, 240), new Size(800, 600));
	private static final List<Size> DISPLAYS = List.of(new Size(640, 480), new Size(800, 600), new Size(1280, 960),
			new Size(320, 240), new Size(1366, 768));
	private static final List<Rect> PROJECTIONS = List.of(new Rect(100, 100, 540, 260), new Rect(113, 32, 422, 316),
			new Rect(0, 0, 640, 480), new Rect(12.5, 7.25, 301.75, 199.5), new Rect(250, 180, 90, 60));
	private static final List<Size> ARENAS = List.of(new Size(640, 360), new Size(1280, 720), new Size(1920, 1080),
			new Size(1024, 768), new Size(1366.5, 767.25));
	private static final double[][] POINTS = { { 0, 0 }, { 100, 100 }, { 381, 235 }, { 271.37, 125.61 },
			{ 639.99, 479.99 }, { 640, 360 }, { -10, -10 }, { 1919, 1079 } };

	// CanvasManager.translateCameraToCanvas
	private static Rect legacyCameraToCanvas(Rect bounds, int feedWidth, int feedHeight, int displayWidth,
			int displayHeight) {
		if (displayWidth == feedWidth && displayHeight == feedHeight) return bounds;

		final double scaleX = (double) displayWidth / (double) feedWidth;
		final double scaleY = (double) displayHeight / (double) feedHeight;

		final double minX = (bounds.getMinX() * scaleX);
		final double minY = (bounds.getMinY() * scaleY);
		final double width = (bounds.getWidth() * scaleX);
		final double height = (bounds.getHeight() * scaleY);

		return new Rect(minX, minY, width, height);
	}

	// CanvasManager.translateCanvasToCamera
	private static Rect legacyCanvasToCamera(Rect bounds, int feedWidth, int feedHeight, int displayWidth,
			int displayHeight) {
		if (displayWidth == feedWidth && displayHeight == feedHeight) return bounds;

		final double scaleX = (double) feedWidth / (double) displayWidth;
		final double scaleY = (double) feedHeight / (double) displayHeight;

		final double minX = (bounds.getMinX() * scaleX);
		final double minY = (bounds.getMinY() * scaleY);
		final double width = (bounds.getWidth() * scaleX);
		final double height = (bounds.getHeight() * scaleY);

		return new Rect(minX, minY, width, height);
	}

	// CanvasManager.scaleShotToArenaBounds, with the arena pane's width and height
	private static Point legacyCanvasToArena(double shotX, double shotY, Rect projectionBounds, double arenaWidth,
			double arenaHeight) {
		final double x_scale = arenaWidth / projectionBounds.getWidth();
		final double y_scale = arenaHeight / projectionBounds.getHeight();

		return new Point((shotX - projectionBounds.getMinX()) * x_scale, (shotY - projectionBounds.getMinY()) * y_scale);
	}

	// CanvasManager.translateCanvasToCameraPoint, with the camera's projection bounds
	private static Point legacyArenaToCamera(double x, double y, Rect b, double arenaWidth, double arenaHeight) {
		final double x_scale = b.getWidth() / arenaWidth;
		final double y_scale = b.getHeight() / arenaHeight;

		return new Point(b.getMinX() + (x * x_scale), b.getMinY() + (y * y_scale));
	}

	@Test
	void cameraAndCanvasBoundsMatchTheLegacyMathForEveryFeedAndDisplay() {
		int compared = 0;
		for (final Size feed : FEEDS) {
			for (final Size display : DISPLAYS) {
				for (final Rect bounds : PROJECTIONS) {
					final int fw = (int) feed.getWidth(), fh = (int) feed.getHeight();
					final int dw = (int) display.getWidth(), dh = (int) display.getHeight();

					assertEquals(legacyCameraToCanvas(bounds, fw, fh, dw, dh),
							ArenaGeometry.cameraToCanvas(bounds, feed, display), feed + " to " + display + ": " + bounds);
					assertEquals(legacyCanvasToCamera(bounds, fw, fh, dw, dh),
							ArenaGeometry.canvasToCamera(bounds, feed, display), display + " to " + feed + ": " + bounds);
					compared++;
				}
			}
		}
		assertEquals(FEEDS.size() * DISPLAYS.size() * PROJECTIONS.size(), compared);
	}

	@Test
	void aFeedTheSizeOfTheDisplayLeavesBoundsAsTheyAre() {
		final Rect bounds = new Rect(113, 32, 422, 316);

		assertSame(bounds, ArenaGeometry.cameraToCanvas(bounds, new Size(640, 480), new Size(640, 480)));
		assertSame(bounds, ArenaGeometry.canvasToCamera(bounds, new Size(640, 480), new Size(640, 480)));
	}

	@Test
	void canvasPointsMatchTheLegacyArenaMathForEveryPlacementAndArenaSize() {
		for (final Rect projection : PROJECTIONS) {
			for (final Size arena : ARENAS) {
				for (final double[] point : POINTS) {
					assertEquals(legacyCanvasToArena(point[0], point[1], projection, arena.getWidth(), arena.getHeight()),
							ArenaGeometry.canvasToArena(point[0], point[1], projection, arena),
							"(" + point[0] + ", " + point[1] + ") in " + projection + " on " + arena);
				}
			}
		}
	}

	@Test
	void arenaPointsMatchTheLegacyCameraMathForEveryPlacementAndArenaSize() {
		for (final Rect projection : PROJECTIONS) {
			for (final Size arena : ARENAS) {
				for (final double[] point : POINTS) {
					assertEquals(legacyArenaToCamera(point[0], point[1], projection, arena.getWidth(), arena.getHeight()),
							ArenaGeometry.arenaToCamera(point[0], point[1], projection, arena),
							"(" + point[0] + ", " + point[1] + ") on " + arena + " into " + projection);
				}
			}
		}
	}

	@Test
	void projectionCornersLandOnArenaCorners() {
		final Rect projection = new Rect(100, 100, 540, 260);
		final Size arena = new Size(640, 360);

		assertEquals(new Point(0, 0), ArenaGeometry.canvasToArena(100, 100, projection, arena));
		assertEquals(new Point(640, 360), ArenaGeometry.canvasToArena(640, 360, projection, arena));
		assertEquals(new Point(100, 100), ArenaGeometry.arenaToCamera(0, 0, projection, arena));
		assertEquals(new Point(640, 360), ArenaGeometry.arenaToCamera(640, 360, projection, arena));
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:test --tests 'com.shootoff.geom.TestArenaGeometry' --console=plain`
Expected: FAIL at compile time: `cannot find symbol` … `ArenaGeometry`.

- [ ] **Step 3: Write `ArenaGeometry`**

`core/src/main/java/com/shootoff/geom/ArenaGeometry.java` (GPL header, then):

```java
package com.shootoff.geom;

/**
 * How the camera feed, the canvas that shows it and the projector arena relate, once the arena is
 * calibrated.
 * <ul>
 * <li>The <b>camera feed</b> is in the camera's pixels (for example 640 x 480).</li>
 * <li>The <b>canvas</b> shows the feed at the display resolution (<tt>shootoff.properties</tt>),
 * so detected shots and calibrated bounds are scaled from the feed to it.</li>
 * <li>The <b>arena</b> is the projector window. Its calibrated projection is the rectangle the
 * camera sees it in, on the canvas or on the feed.</li>
 * </ul>
 * The arithmetic is exactly the JavaFX app's (formerly in <tt>CanvasManager</tt>), operation for
 * operation, so results match to the last bit.
 */
public final class ArenaGeometry {
	private ArenaGeometry() {}

	/**
	 * @return <tt>bounds</tt> on the camera feed, scaled to the canvas; <tt>bounds</tt> itself when
	 *         the feed and the display are the same size
	 */
	public static Rect cameraToCanvas(Rect bounds, Size feed, Size display) {
		if (display.getWidth() == feed.getWidth() && display.getHeight() == feed.getHeight()) return bounds;

		final double scaleX = display.getWidth() / feed.getWidth();
		final double scaleY = display.getHeight() / feed.getHeight();

		return new Rect(bounds.getMinX() * scaleX, bounds.getMinY() * scaleY, bounds.getWidth() * scaleX,
				bounds.getHeight() * scaleY);
	}

	/**
	 * @return <tt>bounds</tt> on the canvas, scaled to the camera feed; <tt>bounds</tt> itself when
	 *         the feed and the display are the same size
	 */
	public static Rect canvasToCamera(Rect bounds, Size feed, Size display) {
		if (display.getWidth() == feed.getWidth() && display.getHeight() == feed.getHeight()) return bounds;

		final double scaleX = feed.getWidth() / display.getWidth();
		final double scaleY = feed.getHeight() / display.getHeight();

		return new Rect(bounds.getMinX() * scaleX, bounds.getMinY() * scaleY, bounds.getWidth() * scaleX,
				bounds.getHeight() * scaleY);
	}

	/**
	 * @param projection
	 *            the calibrated projection on the canvas
	 * @param arena
	 *            the arena's current size
	 * @return a canvas point (normally one inside <tt>projection</tt>) on the arena
	 */
	public static Point canvasToArena(double x, double y, Rect projection, Size arena) {
		final double scaleX = arena.getWidth() / projection.getWidth();
		final double scaleY = arena.getHeight() / projection.getHeight();

		return new Point((x - projection.getMinX()) * scaleX, (y - projection.getMinY()) * scaleY);
	}

	/**
	 * @param projection
	 *            the calibrated projection on the camera feed
	 * @param arena
	 *            the arena's current size
	 * @return an arena point on the camera feed
	 */
	public static Point arenaToCamera(double x, double y, Rect projection, Size arena) {
		final double scaleX = projection.getWidth() / arena.getWidth();
		final double scaleY = projection.getHeight() / arena.getHeight();

		return new Point(projection.getMinX() + (x * scaleX), projection.getMinY() + (y * scaleY));
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests 'com.shootoff.geom.TestArenaGeometry' --console=plain && command grep -ho 'tests="[0-9]*" skipped="0" failures="0" errors="0"' core/build/test-results/test/TEST-com.shootoff.geom.TestArenaGeometry.xml`
Expected: `BUILD SUCCESSFUL`, then `tests="5" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Switch `CanvasManager` onto it**

In `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java`:

(a) Replace the import line `import com.shootoff.geom.Rect;` (it sits among the `javafx.*` imports) with:

```java
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
```

(b) Replace the whole of `translateCameraToCanvas(Rect)` and `translateCanvasToCamera(Rect)` (from `public Rect translateCameraToCanvas(Rect bounds) {` to the closing brace of `translateCanvasToCamera`) with:

```java
	public Rect translateCameraToCanvas(Rect bounds) {
		final Rect translated = ArenaGeometry.cameraToCanvas(bounds, feedSize(), displaySize());

		logger.trace("translateCameraToCanvas {} {} {} {} - {} {} {} {}", bounds.getMinX(), bounds.getMinY(),
				bounds.getWidth(), bounds.getHeight(), translated.getMinX(), translated.getMinY(), translated.getWidth(),
				translated.getHeight());

		return translated;
	}

	public Rect translateCanvasToCamera(Rect bounds) {
		final Rect translated = ArenaGeometry.canvasToCamera(bounds, feedSize(), displaySize());

		logger.trace("translateCanvasToCamera {} {} {} {} - {} {} {} {}", bounds.getMinX(), bounds.getMinY(),
				bounds.getWidth(), bounds.getHeight(), translated.getMinX(), translated.getMinY(), translated.getWidth(),
				translated.getHeight());

		return translated;
	}

	private Size feedSize() {
		return new Size(cameraManager.getFeedWidth(), cameraManager.getFeedHeight());
	}

	private Size displaySize() {
		return new Size(config.getDisplayWidth(), config.getDisplayHeight());
	}
```

(c) In `translateCanvasToCameraPoint(double, double)`, keep the two guards (no camera manager; no projection bounds or arena pane). Replace everything after them, from `final Rect b = cameraManager.getProjectionBounds().get();` to the method's `return`, with:

```java
		final Point camera = ArenaGeometry.arenaToCamera(x, y, cameraManager.getProjectionBounds().get(),
				new Size(arenaPane.get().getWidth(), arenaPane.get().getHeight()));

		return new Pair<Double, Double>(camera.getX(), camera.getY());
```

(d) In `scaleShotToArenaBounds(ArenaShot)`, keep the guard for missing projection bounds. Replace everything after it (the `x_scale`/`y_scale` lines, the two trace lines and `shot.setArenaCoords(...)`) with:

```java
		logger.trace("scaleShotToArenaBounds pre x {} y {}", shot.getX(), shot.getY());

		final Point arena = ArenaGeometry.canvasToArena(shot.getX(), shot.getY(), projectionBounds.get(),
				new Size(arenaPane.get().getWidth(), arenaPane.get().getHeight()));
		shot.setArenaCoords(arena.getX(), arena.getY());

		logger.trace("scaleShotToArenaBounds post x {} y {}", shot.getX(), shot.getY());
```

The old `translateCanvasToCameraPoint scale x {} y {}` trace line goes; nothing else changes.

- [ ] **Step 6: Run the tests that use the helpers**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.targets.TestTargetCommands' --tests 'com.shootoff.gui.TestCanvasManager' --tests 'com.shootoff.camera.TestAutoCalibration' --console=plain`
Expected: `BUILD SUCCESSFUL`. `TestTargetCommands.testPOIAdjust` goes through `scaleShotToArenaBounds` and `translateCanvasToCameraPoint` unchanged.

- [ ] **Step 7: Run the gate**

Run the gate (Global Constraints).
Expected: `376/376 passing; 0 regressions; 0 new failures` (passing = 371 + 5).

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/shootoff/geom/ArenaGeometry.java core/src/test/java/com/shootoff/geom/TestArenaGeometry.java javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java
git commit -m "Move camera, canvas and arena coordinate math into core"
git log -1 --format=%B
```

Expected: the message alone, with no trailer.

---
### Task 2: One shot queue (two lasers in one frame)

**Files:**
- Create: `core/src/main/java/com/shootoff/shots/ShotQueue.java`
- Modify: `core/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java` (`submitShot`, one import)
- Test: `javafx-app/src/test/java/com/shootoff/gui/TestShotTimerRowOrder.java` (new), `core/src/test/java/com/shootoff/shots/TestShotQueue.java` (new)

**Interfaces:**
- Consumes: nothing new.
- Produces: `public final class com.shootoff.shots.ShotQueue`:
  - `ShotQueue(String threadName)`: one daemon thread with that name
  - `static ShotQueue shared()`: the queue every camera's shots go through; its thread is `Shot Notifier`
  - `void submit(Runnable work)`: runs after everything submitted before; a `RuntimeException` is logged and later work still runs
  - `boolean isQueueThread()`

  Task 3's pipeline runs on it.

- [ ] **Step 1: Write the test that reproduces the race**

`javafx-app/src/test/java/com/shootoff/gui/TestShotTimerRowOrder.java` makes two shots through the real path, `CameraManager.injectShot` → `ShotDetector.addShot` → `submitShot` → `CanvasManager.addShot`. The shot timer's listener gives a second thread time to change the list while the first change is still being made:

```java
package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.gui.controller.ShootOFFController;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Group;

class TestShotTimerRowOrder {
	// Two lasers seen in the same camera frame: the camera's thread submits both shots at once
	@Test
	void twoShotsInOneFrameMakeTwoRowsInOrderOnOneThread() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);
		final Configuration config = new Configuration(new String[0]);

		final ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();
		final AtomicInteger changing = new AtomicInteger();
		final AtomicBoolean overlapped = new AtomicBoolean();
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		final CountDownLatch secondArrived = new CountDownLatch(1);
		shotEntries.addListener((ListChangeListener<ShotEntry>) change -> {
			threads.add(Thread.currentThread());
			if (changing.incrementAndGet() > 1) {
				overlapped.set(true);
				secondArrived.countDown();
			} else {
				// Give another thread time to change the list while this change is still being made
				try {
					secondArrived.await(500, TimeUnit.MILLISECONDS);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			changing.decrementAndGet();
		});

		final CanvasManager canvas = new CanvasManager(new Group(), new ShootOFFController(), "test", shotEntries);
		final CamerasSupervisor cameras = new CamerasSupervisor(config);
		final CameraManager camera = cameras.addCameraManager(new MockCamera(), null, canvas).get();
		cameras.setDetectingAll(false);
		canvas.setCameraManager(camera);

		camera.injectShot(ShotColor.RED, 100, 100, false);
		camera.injectShot(ShotColor.GREEN, 400, 300, false);

		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (shotEntries.size() < 2) {
			if (System.nanoTime() > deadline) fail("rows: " + shotEntries.size());
			Thread.sleep(10);
		}

		assertFalse(overlapped.get(), "two threads changed the shot timer at once");
		assertEquals(1, Set.copyOf(threads).size());
		assertEquals(List.of(100.0, 400.0), shotEntries.stream().map(row -> row.getShot().getX()).toList());
		assertEquals(List.of("red", "green"), shotEntries.stream().map(ShotEntry::getColor).toList());
		assertEquals(config.getMarkerRadius(), (int) shotEntries.get(0).getShot().getMarker().getRadiusX());
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.TestShotTimerRowOrder' --console=plain`
Expected: FAIL. The message varies with timing, but it is one of:
- `rows: 1`: a notifier thread died on "Called endChange before beginChange", and its row was lost
- `two threads changed the shot timer at once`
- `expected: <[100.0, 400.0]> but was: <[400.0, 100.0]>`

Any of these is the bug.

- [ ] **Step 3: Write the queue's own test**

`core/src/test/java/com/shootoff/shots/TestShotQueue.java`:

```java
package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class TestShotQueue {
	private final ShotQueue queue = new ShotQueue("Test shots");

	@Test
	void shotsAreHandledOneAtATimeInTheOrderTheyArrive() throws Exception {
		final List<String> events = new CopyOnWriteArrayList<>();
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		final CountDownLatch done = new CountDownLatch(2);

		// Two cameras' threads submit at once; the first shot's handling is slow
		final Thread first = new Thread(() -> queue.submit(() -> {
			threads.add(Thread.currentThread());
			events.add("first starts");
			sleep(100);
			events.add("first ends");
			done.countDown();
		}));
		first.start();
		first.join();
		final Thread second = new Thread(() -> queue.submit(() -> {
			threads.add(Thread.currentThread());
			events.add("second");
			done.countDown();
		}));
		second.start();

		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(List.of("first starts", "first ends", "second"), events);
		assertEquals(1, Set.copyOf(threads).size());
		assertEquals("Test shots", threads.get(0).getName());
	}

	@Test
	void aShotThatFailsDoesNotStopLaterOnes() throws Exception {
		final CountDownLatch handled = new CountDownLatch(1);
		final List<Boolean> onQueue = new CopyOnWriteArrayList<>();

		queue.submit(() -> {
			throw new IllegalStateException("Called endChange before beginChange");
		});
		queue.submit(() -> {
			onQueue.add(queue.isQueueThread());
			handled.countDown();
		});

		assertTrue(handled.await(5, TimeUnit.SECONDS));
		assertEquals(List.of(true), onQueue);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
```

Run: `./gradlew :core:test --tests 'com.shootoff.shots.TestShotQueue' --console=plain`
Expected: FAIL at compile time: `cannot find symbol` … `ShotQueue`.

- [ ] **Step 4: Write `ShotQueue`**

`core/src/main/java/com/shootoff/shots/ShotQueue.java` (GPL header, then):

```java
package com.shootoff.shots;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one thread detected shots are handled on, in the order they were detected. Handling a shot
 * (shot processors, its shot timer row, hit tests, region commands, the exercise's callback) runs here
 * instead of on the camera's thread, so slow handling never delays detection. It runs one shot at a
 * time, so two shots from the same camera frame can't change the shot timer at once.
 */
public final class ShotQueue {
	private static final Logger logger = LoggerFactory.getLogger(ShotQueue.class);
	private static final ShotQueue SHARED = new ShotQueue("Shot Notifier");

	private final ExecutorService executor;
	private volatile Thread thread;

	public ShotQueue(String threadName) {
		executor = Executors.newSingleThreadExecutor(runnable -> {
			final Thread t = new Thread(runnable, threadName);
			t.setDaemon(true);
			thread = t;
			return t;
		});
	}

	/**
	 * @return the queue every camera's shots go through
	 */
	public static ShotQueue shared() {
		return SHARED;
	}

	/**
	 * Handles <tt>work</tt> after everything submitted before it. If it throws, the exception is
	 * logged and later work still runs.
	 */
	public void submit(Runnable work) {
		executor.execute(() -> {
			try {
				work.run();
			} catch (final RuntimeException e) {
				logger.error("Handling a shot failed", e);
			}
		});
	}

	/**
	 * @return <tt>true</tt> when called while handling a shot
	 */
	public boolean isQueueThread() {
		return Thread.currentThread() == thread;
	}
}
```

- [ ] **Step 5: Hand detected shots to the queue**

In `core/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java`, in `submitShot`, replace:

```java
		// Notify of new shot on a non-shot detection thread because most
		// training exercises do shot processing on whatever thread submits
		// the shot
		new Thread(() -> cameraView.addShot(shot), "Shot Notifier").start();
```

with:

```java
		// Handle the shot off the detection thread, because exercises handle shots on the thread that
		// delivers them, and one shot at a time, in the order shots were detected
		ShotQueue.shared().submit(() -> cameraView.addShot(shot));
```

and add `import com.shootoff.shots.ShotQueue;` after `import com.shootoff.config.Settings;`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.shots.TestShotQueue' --tests 'com.shootoff.camera.shotdetection.TestShotDetector' :javafx-app:test --tests 'com.shootoff.gui.TestShotTimerRowOrder' --tests 'com.shootoff.gui.TestCanvasManager' --console=plain`
Expected: `BUILD SUCCESSFUL`. `TestShotDetector` and `TestCanvasManager`'s `injectShot` tests still see their shots, now from the queue.

- [ ] **Step 7: Run the gate**

Expected: `379/379 passing; 0 regressions; 0 new failures` (passing = 376 + 3).

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/shootoff/shots/ShotQueue.java core/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java core/src/test/java/com/shootoff/shots/TestShotQueue.java javafx-app/src/test/java/com/shootoff/gui/TestShotTimerRowOrder.java
git commit -m "Handle detected shots one at a time on one thread, fixing two shots in one frame"
git log -1 --format=%B
```

Expected: the message alone, with no trailer.

---
### Task 3: The shot pipeline in `core`

**Files:**
- Create: `core/src/main/java/com/shootoff/shots/ShotPipeline.java`, `ShotTimer.java`, `TimerRow.java`, `PoiAdjustment.java`
- Modify: `core/src/main/java/com/shootoff/config/Settings.java` (two fields, six methods, two imports)
- Modify: `javafx-app/src/main/java/com/shootoff/config/Configuration.java` (the same fields and methods removed, the class Javadoc, two imports)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` (imports; the malfunction and reload fields become the pipeline; the constructor; everything from `notifyShot` to just before `loadTarget`)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/ShotEntry.java` (the public constructor, one import)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/targets/TargetCommands.java` (`poi_adjust`, two imports)
- Test: `core/src/test/java/com/shootoff/shots/TestShotPipeline.java`, `TestTimerRow.java`, `TestPoiAdjustment.java` (new)

**Interfaces:**
- Consumes:
  - `ArenaGeometry.canvasToArena` (Task 1)
  - `ShotQueue` (Task 2): the pipeline runs on whatever thread calls it, which for detected shots is the queue's
  - core's `Settings`, `SessionRecorder`, `TargetRef`, `HitTester`, `Hit`, `Region`, `RegionCommand`, `SoundPlayer`, and the shot processors
- Produces:
  - `Settings`: `registerRecordingCameraManager(CameraManager)`, `unregisterRecordingCameraManager(CameraManager)`, `unregisterAllRecordingCameraManagers()`, `Set<CameraManager> getRecordingManagers()`, `setSessionRecorder(SessionRecorder)` (`null` for none), `Optional<SessionRecorder> getSessionRecorder()`
  - `record com.shootoff.shots.TimerRow(Shot shot, String time, String split, String laser, boolean hadMalfunction, boolean hadReload)` and `static TimerRow of(Shot shot, Optional<Shot> previous, boolean hadMalfunction, boolean hadReload)`
  - `@FunctionalInterface interface ShotTimer<S extends Shot>`: `void appendShotRow(S shot, boolean hadMalfunction, boolean hadReload)`
  - `final class ShotPipeline<S extends Shot>`:
    - `ShotPipeline(Surface<S> surface, Settings settings)`
    - `void addShot(S shot, boolean mirrored)`
    - `boolean addArenaShot(S shot, Optional<String> videoString, boolean mirrored)`
    - `Optional<Hit> hitTest(S shot, Optional<String> videoString, boolean mirrored)`
  - `interface ShotPipeline.Surface<S>`:
    - `String name()`, `TargetSet targets()`, `Optional<Hit> hitTest(double x, double y)`
    - `Optional<ShotTimer<S>> shotTimer()`, `void show(S shot)`, `int markerRadius(S shot)`
    - `void runRegionCommands(S shot, Hit hit, boolean mirrored)`
    - `boolean deliver(S shot, Optional<Hit> hit, boolean arenaShot)`
    - `Optional<Arena<S>> arena()`
  - `interface ShotPipeline.Arena<S>`: `Optional<Rect> projection()`, `Size size()`, `S toArenaShot(S shot, Point arenaPoint)`, `boolean addArenaShot(S shot, Optional<String> videoString, boolean mirrored)`
  - `final class PoiAdjustment`: `static Point offset(Point regionCenter, Point shot, double targetScaleX, double targetScaleY)`, `static void apply(Settings settings, Point offset)`

  `Hit` here is `com.shootoff.targets.model.Hit`. Task 5 uses `TimerRow`; Plan 5's Compose surfaces implement `Surface` and `Arena`.

**What moves, and where.** `CanvasManager.addShot(DisplayShot, boolean)` becomes `ShotPipeline.addShot`, step for step:
1. processors (unless mirrored); a rejected shot is recorded as a malfunction or a reload and goes no further
2. `notifyShot` and the laser sound
3. the row, carrying and then clearing the malfunction and reload flags
4. the marker
5. the video string
6. inside the projection: an `ArenaShot` in arena coordinates, handed to the arena canvas (`addArenaShot`), and done
7. otherwise: hit test and record, region commands, and delivery

`addArenaShot` and `checkHit` move the same way. The JavaFX specifics become `CanvasManager`'s private `PipelineSurface` and `ArenaLink`: `DisplayShot` markers, `ShotEntry` rows, v1 `Hit`s for `TargetCommands` and v1 exercises, the mirrored-canvas delivery rule and `ArenaShot`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/shots/TestShotPipeline.java`. A fake surface writes down what the pipeline asks of it, in order. A real `SessionRecorder` checks what is recorded:

```java
package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.processors.MalfunctionsProcessor;
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.session.Event;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.ShotEvent;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.RectangleRegion;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetSet;

class TestShotPipeline {
	private static final Rect PROJECTION = new Rect(100, 100, 540, 260);
	private static final Size ARENA = new Size(640, 360);

	private Settings settings;
	private SessionRecorder recorder;
	private final List<String> events = new CopyOnWriteArrayList<>();
	private FakeSurface camera;
	private FakeSurface arena;
	private ShotPipeline<Shot> cameraPipeline;
	private ShotPipeline<Shot> arenaPipeline;

	// A surface that writes down what the pipeline asks of it
	private class FakeSurface implements ShotPipeline.Surface<Shot> {
		final String name;
		final TargetSet targets = new TargetSet();
		final List<Shot> rows = new CopyOnWriteArrayList<>();
		Optional<ShotPipeline.Arena<Shot>> arena = Optional.empty();
		boolean hasTimer = true;
		boolean exerciseRunning = true;

		FakeSurface(String name) {
			this.name = name;
		}

		PlacedTarget addBox(double x, double y, Map<String, String> tags) {
			final PlacedTarget box = targets.add(new TargetDefinition(Optional.empty(), Map.of(),
					List.of(new RectangleRegion(0, 0, 0, 50, 50, "red", tags))));
			targets.move(box.getId(), x, y);
			return box;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public TargetSet targets() {
			return targets;
		}

		@Override
		public Optional<Hit> hitTest(double x, double y) {
			events.add(name + " hit test");
			return HitTester.hit(targets, x, y);
		}

		@Override
		public Optional<ShotTimer<Shot>> shotTimer() {
			if (!hasTimer) return Optional.empty();

			return Optional.of((shot, hadMalfunction, hadReload) -> {
				events.add(name + " row" + (hadMalfunction ? " after a malfunction" : "")
						+ (hadReload ? " after a reload" : ""));
				rows.add(shot);
			});
		}

		@Override
		public void show(Shot shot) {
			events.add(name + " shows (" + (int) shot.getX() + ", " + (int) shot.getY() + ")");
		}

		@Override
		public int markerRadius(Shot shot) {
			return 3;
		}

		@Override
		public void runRegionCommands(Shot shot, Hit hit, boolean mirrored) {
			events.add(name + " runs " + hit.region().commands() + (mirrored ? " mirrored" : ""));
		}

		@Override
		public boolean deliver(Shot shot, Optional<Hit> hit, boolean arenaShot) {
			events.add(name + " delivers " + (hit.isPresent() ? "a hit" : "a miss") + (arenaShot ? " on the arena" : ""));
			return exerciseRunning;
		}

		@Override
		public Optional<ShotPipeline.Arena<Shot>> arena() {
			return arena;
		}
	}

	// The arena as the camera feed passes shots to it
	private final class FakeArena implements ShotPipeline.Arena<Shot> {
		Size size = ARENA;

		@Override
		public Optional<Rect> projection() {
			return Optional.of(PROJECTION);
		}

		@Override
		public Size size() {
			return size;
		}

		@Override
		public Shot toArenaShot(Shot shot, Point arenaPoint) {
			return new Shot(shot.getColor(), arenaPoint.getX(), arenaPoint.getY(), shot.getTimestamp(), shot.getFrame());
		}

		@Override
		public boolean addArenaShot(Shot shot, Optional<String> videoString, boolean mirrored) {
			return arenaPipeline.addArenaShot(shot, videoString, mirrored);
		}
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		settings = new Settings(new String[0]);
		recorder = new SessionRecorder();
		settings.setSessionRecorder(recorder);
		MalfunctionsProcessor.setUseTTS(false);

		camera = new FakeSurface("Camera 1");
		arena = new FakeSurface("arena");
		arena.hasTimer = false;
		cameraPipeline = new ShotPipeline<>(camera, settings);
		arenaPipeline = new ShotPipeline<>(arena, settings);
	}

	@AfterEach
	void tearDown() {
		settings.setSessionRecorder(null);
	}

	private static Shot shot(double x, double y) {
		return new Shot(ShotColor.RED, x, y, 1000);
	}

	private List<ShotEvent> recorded(String surface) {
		final List<ShotEvent> shots = new java.util.ArrayList<>();
		for (final Event event : recorder.getCameraEvents(surface)) {
			if (event instanceof ShotEvent shotEvent) shots.add(shotEvent);
		}
		return shots;
	}

	@Test
	void aCameraShotIsRowedShownHitTestedRecordedAndDelivered() {
		camera.addBox(10, 10, Map.of());
		camera.addBox(200, 200, Map.of());

		cameraPipeline.addShot(shot(220, 220), false);

		assertEquals(List.of("Camera 1 row", "Camera 1 shows (220, 220)", "Camera 1 hit test", "Camera 1 delivers a hit"),
				events);
		final ShotEvent recordedShot = recorded("Camera 1").get(0);
		assertEquals(Optional.of(1), recordedShot.getTargetIndex());
		assertEquals(Optional.of(0), recordedShot.getHitRegionIndex());
		assertEquals(3, recordedShot.getMarkerRadius());
		assertFalse(recordedShot.isMalfunction());
	}

	@Test
	void aMissIsRecordedWithoutATarget() {
		camera.addBox(10, 10, Map.of());

		cameraPipeline.addShot(shot(300, 300), false);

		assertTrue(events.contains("Camera 1 delivers a miss"));
		assertEquals(Optional.empty(), recorded("Camera 1").get(0).getTargetIndex());
		assertEquals(Optional.empty(), recorded("Camera 1").get(0).getHitRegionIndex());
	}

	@Test
	void aMalfunctionRejectsTheShotAndItsNextRowSaysSo() {
		settings.setMalfunctions(true);
		settings.setMalfunctionsProbability(100);

		cameraPipeline.addShot(shot(20, 20), false);

		// Recorded as a malfunction, and nothing else
		assertEquals(List.of(), events);
		assertTrue(recorded("Camera 1").get(0).isMalfunction());

		settings.setMalfunctions(false);
		cameraPipeline.addShot(shot(30, 30), false);
		cameraPipeline.addShot(shot(40, 40), false);

		assertEquals(List.of("Camera 1 row after a malfunction", "Camera 1 row"),
				events.stream().filter(event -> event.contains("row")).toList());
	}

	@Test
	void anEmptyVirtualMagazineRejectsTheShotAndItsNextRowIsAReload() {
		settings.setUseVirtualMagazine(true);
		settings.setVirtualMagazineCapacity(1);
		for (final ShotProcessor processor : settings.getShotProcessors()) {
			if (processor instanceof VirtualMagazineProcessor magazine) magazine.setUseTTS(false);
		}

		cameraPipeline.addShot(shot(20, 20), false);
		cameraPipeline.addShot(shot(30, 30), false);
		cameraPipeline.addShot(shot(40, 40), false);

		assertEquals(List.of("Camera 1 row", "Camera 1 row after a reload"),
				events.stream().filter(event -> event.contains("row")).toList());
		assertEquals(List.of(false, true, false), recorded("Camera 1").stream().map(ShotEvent::isReload).toList());
	}

	@Test
	void aShotInsideTheProjectionGoesToTheArenaInArenaCoordinates() {
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(360, 220, Map.of());
		final Point onArena = ArenaGeometry.canvasToArena(381, 235, PROJECTION, ARENA);
		arena.addBox(onArena.getX() - 5, onArena.getY() - 5, Map.of());

		cameraPipeline.addShot(shot(381, 235), false);

		// The camera feed shows the shot and rows it; the arena hit-tests, records and delivers it
		assertEquals(List.of("Camera 1 row", "Camera 1 shows (381, 235)",
				"arena shows (" + (int) onArena.getX() + ", " + (int) onArena.getY() + ")", "arena hit test",
				"arena delivers a hit on the arena"), events);
		assertEquals(List.of(), recorded("Camera 1"));
		assertEquals(Optional.of(0), recorded("arena").get(0).getTargetIndex());
		assertEquals(onArena.getX(), recorded("arena").get(0).getShot().getX(), 0);
	}

	@Test
	void aShotOnTheProjectionsEdgeGoesToTheArenaAtTheArenasCurrentSize() {
		final FakeArena fakeArena = new FakeArena();
		camera.arena = Optional.of(fakeArena);

		// The projection's far corner, edges inclusive
		cameraPipeline.addShot(shot(640, 360), false);
		// The arena window was resized after calibration
		fakeArena.size = new Size(1280, 720);
		cameraPipeline.addShot(shot(640, 360), false);

		assertEquals(List.of("arena shows (640, 360)", "arena shows (1280, 720)"),
				events.stream().filter(event -> event.startsWith("arena shows")).toList());
		assertFalse(events.contains("Camera 1 hit test"));
	}

	@Test
	void aShotOutsideTheProjectionIsHitTestedOnTheCameraFeed() {
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(40, 40, Map.of());

		cameraPipeline.addShot(shot(50, 50), false);

		assertEquals(List.of("Camera 1 row", "Camera 1 shows (50, 50)", "Camera 1 hit test", "Camera 1 delivers a hit"),
				events);
		assertEquals(List.of(), recorded("arena"));
	}

	@Test
	void mirroredShotsAreNotProcessedRecordedOrDeliveredFromTheArena() {
		settings.setMalfunctions(true);
		settings.setMalfunctionsProbability(100);
		arena.addBox(0, 0, Map.of("command", "reset"));

		cameraPipeline.addShot(shot(20, 20), true);
		final boolean taken = arenaPipeline.addArenaShot(shot(20, 20), Optional.empty(), true);

		// Not rejected by the malfunction, and not recorded
		assertEquals(List.of("Camera 1 row", "Camera 1 shows (20, 20)", "Camera 1 hit test", "Camera 1 delivers a miss",
				"arena shows (20, 20)", "arena hit test", "arena runs [RegionCommand[name=reset, args=[]]] mirrored"),
				events);
		assertFalse(taken);
		assertEquals(List.of(), recorded("Camera 1"));
		assertEquals(List.of(), recorded("arena"));
	}

	@Test
	void regionCommandsRunOnlyForRegionsThatHaveThem() {
		camera.addBox(0, 0, Map.of("command", "animate(pepper_popper);play_sound(sounds/metal_clang.wav)"));
		camera.addBox(100, 100, Map.of("name", "plain"));

		cameraPipeline.addShot(shot(10, 10), false);
		cameraPipeline.addShot(shot(110, 110), false);

		assertEquals(List.of("Camera 1 runs [RegionCommand[name=animate, args=[pepper_popper]], "
				+ "RegionCommand[name=play_sound, args=[sounds/metal_clang.wav]]]"),
				events.stream().filter(event -> event.contains("runs")).toList());
	}

	@Test
	void twoShotsInOneFrameMakeTwoRowsInOrderOnOneThread() throws Exception {
		final ShotQueue queue = new ShotQueue("Test shots");
		final AtomicInteger appending = new AtomicInteger();
		final AtomicBoolean overlapped = new AtomicBoolean();
		final CountDownLatch secondArrived = new CountDownLatch(1);
		final CountDownLatch bothRowed = new CountDownLatch(2);
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		final List<Shot> rowed = new CopyOnWriteArrayList<>();
		final ShotPipeline<Shot> pipeline = new ShotPipeline<>(new FakeSurface("Camera 1") {
			@Override
			public Optional<ShotTimer<Shot>> shotTimer() {
				return Optional.of((shot, hadMalfunction, hadReload) -> {
					threads.add(Thread.currentThread());
					if (appending.incrementAndGet() > 1) {
						overlapped.set(true);
						secondArrived.countDown();
					} else {
						try {
							secondArrived.await(300, TimeUnit.MILLISECONDS);
						} catch (final InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					rowed.add(shot);
					appending.decrementAndGet();
					bothRowed.countDown();
				});
			}
		}, settings);

		// One camera frame, two lasers: both shots are submitted at once
		final Shot first = shot(100, 100);
		final Shot second = shot(400, 300);
		queue.submit(() -> pipeline.addShot(first, false));
		final Thread other = new Thread(() -> queue.submit(() -> pipeline.addShot(second, false)));
		other.start();
		other.join();

		assertTrue(bothRowed.await(5, TimeUnit.SECONDS));
		assertFalse(overlapped.get(), "two threads appended rows at once");
		assertEquals(List.of(first, second), rowed);
		assertEquals(1, Set.copyOf(threads).size());
	}
}
```

`core/src/test/java/com/shootoff/shots/TestTimerRow.java`:

```java
package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;

class TestTimerRow {
	@Test
	void readsTheShotsTimeSplitAndLaser() {
		final Shot previous = new Shot(ShotColor.RED, 0, 0, 1000);

		final TimerRow first = TimerRow.of(previous, Optional.empty(), false, false);
		final TimerRow green = TimerRow.of(new Shot(ShotColor.GREEN, 5, 5, 2500), Optional.of(previous), true, false);
		final TimerRow infrared = TimerRow.of(new Shot(ShotColor.INFRARED, 5, 5, 3125), Optional.of(previous), false,
				true);

		assertEquals(String.format("%.2f", 1.0f), first.time());
		assertEquals("-", first.split());
		assertEquals("red", first.laser());
		assertEquals(String.format("%.2f", 2.5f), green.time());
		assertEquals(String.format("%.2f", 1.5f), green.split());
		assertEquals("green", green.laser());
		assertTrue(green.hadMalfunction());
		assertEquals("infrared", infrared.laser());
		assertEquals(String.format("%.2f", 2.125f), infrared.split());
		assertTrue(infrared.hadReload());
	}
}
```

`core/src/test/java/com/shootoff/shots/TestPoiAdjustment.java` (it never writes `shootoff.properties`, ruling 15):

```java
package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Point;
import com.shootoff.sound.SoundPlayer;

class TestPoiAdjustment {
	private Settings settings;
	private boolean wasSilenced;

	@BeforeEach
	void setUp() throws ConfigurationException {
		// Never write the working directory's shootoff.properties
		settings = new Settings(new String[0]) {
			@Override
			public boolean writeConfigurationFile() {
				return true;
			}
		};
		wasSilenced = SoundPlayer.isSilenced();
		SoundPlayer.silence(true);
	}

	@AfterEach
	void tearDown() {
		SoundPlayer.silence(wasSilenced);
	}

	@Test
	void theOffsetIsFromTheRegionsCenterInTheTargetsScale() {
		assertEquals(new Point(-7, 14), PoiAdjustment.offset(new Point(200, 100), new Point(193, 114), 1, 1));
		assertEquals(new Point(-3.5, 7), PoiAdjustment.offset(new Point(200, 100), new Point(193, 114), 2, 2));
	}

	@Test
	void fiveHitsTurnTheAdjustmentOnAndASixthTurnsItOff() {
		for (int hit = 0; hit < 4; hit++) {
			PoiAdjustment.apply(settings, new Point(10, -4));
			assertFalse(settings.isAdjustingPOI());
		}

		PoiAdjustment.apply(settings, new Point(10, -4));

		assertTrue(settings.isAdjustingPOI());
		assertEquals(Optional.of(-10.0), settings.getPOIAdjustmentX());
		assertEquals(Optional.of(4.0), settings.getPOIAdjustmentY());

		PoiAdjustment.apply(settings, new Point(1, 1));

		assertFalse(settings.isAdjustingPOI());
		assertEquals(Optional.empty(), settings.getPOIAdjustmentX());
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.shots.*' --console=plain`
Expected: FAIL at compile time: `cannot find symbol` for `ShotPipeline`, `ShotTimer`, `TimerRow`, `PoiAdjustment` and `setSessionRecorder`.

- [ ] **Step 3: Move the session recorder and the recording cameras into `Settings`**

In `core/src/main/java/com/shootoff/config/Settings.java`:
- add `import com.shootoff.camera.CameraManager;` after `import com.shootoff.camera.CameraFactory;`
- add `import com.shootoff.session.SessionRecorder;` after `import com.shootoff.camera.shot.ShotColor;`
- just before `	private static Settings settings = null;` insert:

```java
	// Runtime state, never written to the configuration file
	private final Set<CameraManager> recordingManagers = new HashSet<>();
	private Optional<SessionRecorder> sessionRecorder = Optional.empty();

```

- just before `	private final static int POI_NUM_TARGETS = 5;` insert:

```java
	/**
	 * Adds a camera whose video is saved with each shot of the session being recorded.
	 */
	public void registerRecordingCameraManager(CameraManager cm) {
		recordingManagers.add(cm);
	}

	public void unregisterRecordingCameraManager(CameraManager cm) {
		recordingManagers.remove(cm);
	}

	public void unregisterAllRecordingCameraManagers() {
		recordingManagers.clear();
	}

	public Set<CameraManager> getRecordingManagers() {
		return recordingManagers;
	}

	/**
	 * Sets the session being recorded, or <tt>null</tt> when none is.
	 */
	public void setSessionRecorder(SessionRecorder sessionRecorder) {
		this.sessionRecorder = Optional.ofNullable(sessionRecorder);
	}

	public Optional<SessionRecorder> getSessionRecorder() {
		return sessionRecorder;
	}

```

In `javafx-app/src/main/java/com/shootoff/config/Configuration.java`:
- delete the fields `private final Set<CameraManager> recordingManagers = new HashSet<>();` and `private Optional<SessionRecorder> sessionRecorder = Optional.empty();`
- delete the six methods `registerRecordingCameraManager`, `unregisterRecordingCameraManager`, `unregisterAllRecordingCameraManagers`, `getRecordingManagers`, `setSessionRecorder` and `getSessionRecorder`. Every caller now reaches the identical methods in `Settings`.
- delete the imports `com.shootoff.camera.CameraManager` and `com.shootoff.session.SessionRecorder`. Keep `HashSet` and `Set`: `videoPlayers` uses them.
- replace the class Javadoc's first paragraph with:

```java
 * The JavaFX app's configuration: the persisted {@link Settings} plus the JavaFX app's own runtime
 * state (the current exercise and plugin, open video players and the shot timer row color). The session
 * recorder and the recording cameras are in {@link Settings}, which the shot pipeline in core reads.
```

- [ ] **Step 4: Write the pipeline's classes**

`core/src/main/java/com/shootoff/shots/TimerRow.java` (GPL header, then):

```java
package com.shootoff.shots;

import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;

/**
 * What a shot timer row shows, as text: the shot's time and split in seconds and its laser.
 *
 * @param time
 *            the shot's time, e.g. "2.50"
 * @param split
 *            the time since the previous row's shot, e.g. "1.50", or "-" for the first row
 * @param laser
 *            "red", "green" or "infrared"
 * @param hadMalfunction
 *            <tt>true</tt> if a malfunction rejected the shot before this one
 * @param hadReload
 *            <tt>true</tt> if the virtual magazine rejected the shot before this one (a reload)
 */
public record TimerRow(Shot shot, String time, String split, String laser, boolean hadMalfunction,
		boolean hadReload) {
	/**
	 * @param previous
	 *            the shot of the row before this one, if any
	 */
	public static TimerRow of(Shot shot, Optional<Shot> previous, boolean hadMalfunction, boolean hadReload) {
		final String laser;
		if (ShotColor.RED.equals(shot.getColor())) {
			laser = "red";
		} else if (ShotColor.GREEN.equals(shot.getColor())) {
			laser = "green";
		} else {
			laser = "infrared";
		}

		final float timestampS = ((float) shot.getTimestamp()) / 1000f;
		final String time = String.format("%.2f", timestampS);

		final String split;
		if (previous.isPresent()) {
			split = String.format("%.2f", timestampS - ((float) previous.get().getTimestamp() / 1000f));
		} else {
			split = "-";
		}

		return new TimerRow(shot, time, split, laser, hadMalfunction, hadReload);
	}
}
```

`core/src/main/java/com/shootoff/shots/ShotTimer.java` (GPL header, then):

```java
package com.shootoff.shots;

import com.shootoff.camera.Shot;

/**
 * A user interface's shot timer, as the {@link ShotPipeline} fills it.
 */
@FunctionalInterface
public interface ShotTimer<S extends Shot> {
	/**
	 * Appends the row for <tt>shot</tt> after the latest row (see {@link TimerRow#of}).
	 *
	 * @param hadMalfunction
	 *            <tt>true</tt> if a malfunction rejected the shot before this one
	 * @param hadReload
	 *            <tt>true</tt> if the virtual magazine rejected the shot before this one
	 */
	void appendShotRow(S shot, boolean hadMalfunction, boolean hadReload);
}
```

`core/src/main/java/com/shootoff/shots/PoiAdjustment.java` (GPL header, then). `apply` is the tail of `TargetCommands`' `poi_adjust`, with `TrainingExerciseBase.playSound(String)` replaced by what it called, `SoundPlayer.play(String)`:

```java
package com.shootoff.shots;

import com.shootoff.config.Settings;
import com.shootoff.geom.Point;
import com.shootoff.sound.SoundPlayer;

/**
 * The <tt>poi_adjust</tt> region command: five hits on the POI adjustment target's regions set the
 * point-of-impact offset every later shot is moved by, and a sixth turns it off. Each UI measures the
 * hit region's center on the camera feed; this does the rest.
 */
public final class PoiAdjustment {
	private PoiAdjustment() {}

	/**
	 * @param regionCenter
	 *            the center of the hit region, on the camera feed
	 * @param shot
	 *            where the shot was, on the camera feed (its bounds position)
	 * @param targetScaleX
	 *            the target's horizontal scale
	 * @return how far the shot landed from the region's center, in the target's own scale
	 */
	public static Point offset(Point regionCenter, Point shot, double targetScaleX, double targetScaleY) {
		return new Point((shot.getX() - regionCenter.getX()) / targetScaleX,
				(shot.getY() - regionCenter.getY()) / targetScaleY);
	}

	/**
	 * Counts one hit towards the adjustment and plays its sounds: <tt>beep2.wav</tt> twice when this
	 * hit turns the adjustment off, <tt>beep.wav</tt> once the adjustment is on, and <tt>beep2.wav</tt>
	 * while it is still being measured.
	 */
	public static void apply(Settings settings, Point offset) {
		if (settings.updatePOIAdjustment(offset.getX(), offset.getY())) {
			SoundPlayer.play("sounds/beep2.wav");
			try {
				Thread.sleep(200);
			} catch (final InterruptedException e) {}
			SoundPlayer.play("sounds/beep2.wav");
		} else if (settings.isAdjustingPOI())
			SoundPlayer.play("sounds/beep.wav");
		else
			SoundPlayer.play("sounds/beep2.wav");
	}
}
```

`core/src/main/java/com/shootoff/shots/ShotPipeline.java` (GPL header, then). `processShot`, `recordRejectedShot`, `notifyShot` and `createVideoString` are `CanvasManager`'s, word for word, reading `settings` instead of `config`:

```java
package com.shootoff.shots;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.Shot;
import com.shootoff.camera.processors.MalfunctionsProcessor;
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.recorders.ShotRecorder;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.TargetRef;
import com.shootoff.sound.SoundPlayer;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetSet;

/**
 * Everything between a detected shot and the running exercise, for one surface (a camera feed, the
 * arena, or a copy of the arena that mirrors it):
 * <ol>
 * <li>the shot processors (malfunctions, the virtual magazine); a rejected shot is recorded as such and
 * goes no further</li>
 * <li>the laser sound</li>
 * <li>the shot timer row</li>
 * <li>the marker</li>
 * <li>a shot inside the arena's calibrated projection goes on to the arena, in arena coordinates;
 * any other shot is hit-tested against this surface's targets</li>
 * <li>session recording, the hit region's commands, and delivery to the running exercise</li>
 * </ol>
 * A <i>mirrored</i> shot is a copy of a shot another surface handles: it skips the processors, the
 * sound and session recording, and its region commands know it is a copy.
 * <p>
 * The user interface supplies a {@link Surface}, and hands it detected shots on the {@link ShotQueue}.
 *
 * @param <S>
 *            the user interface's shot type (e.g. one that carries its marker)
 */
public final class ShotPipeline<S extends Shot> {
	private static final Logger logger = LoggerFactory.getLogger(ShotPipeline.class);

	/**
	 * What a user interface does with shots on one of its surfaces.
	 */
	public interface Surface<S extends Shot> {
		/**
		 * @return the name this surface's session events are recorded under: the camera's name, or
		 *         "arena"
		 */
		String name();

		/**
		 * @return the surface's targets, which session events refer to
		 */
		TargetSet targets();

		/**
		 * @return the hit on the topmost visible target region at (<tt>x</tt>, <tt>y</tt>) that the
		 *         surface shows
		 */
		Optional<Hit> hitTest(double x, double y);

		/**
		 * @return the shot timer this surface adds rows to, if it has one
		 */
		Optional<ShotTimer<S>> shotTimer();

		/**
		 * Keeps the shot and draws its marker.
		 */
		void show(S shot);

		/**
		 * @return the marker radius session files store for the shot
		 */
		int markerRadius(S shot);

		/**
		 * Carries out the commands of the hit region, which has a command tag.
		 *
		 * @param mirrored
		 *            <tt>true</tt> for a copy of a shot another surface handles
		 */
		void runRegionCommands(S shot, Hit hit, boolean mirrored);

		/**
		 * Hands the shot to the running exercise, if there is one and this surface feeds it.
		 *
		 * @param arenaShot
		 *            <tt>true</tt> for a shot in arena coordinates
		 * @return <tt>true</tt> if an exercise took the shot
		 */
		boolean deliver(S shot, Optional<Hit> hit, boolean arenaShot);

		/**
		 * @return the arena that shots inside its calibrated projection go on to; empty for the arena
		 *         itself, its copies, and a camera feed while there is no arena
		 */
		Optional<Arena<S>> arena();
	}

	/**
	 * The arena, as a camera feed's surface sees it.
	 */
	public interface Arena<S extends Shot> {
		/**
		 * @return the arena's calibrated projection, in the camera feed surface's coordinates; empty
		 *         until the arena is calibrated
		 */
		Optional<Rect> projection();

		/**
		 * @return the arena's current size
		 */
		Size size();

		/**
		 * @return a copy of <tt>shot</tt> at <tt>arenaPoint</tt>, in arena coordinates
		 */
		S toArenaShot(S shot, Point arenaPoint);

		/**
		 * Handles a shot in arena coordinates, normally with {@link ShotPipeline#addArenaShot} on the
		 * arena's surfaces.
		 *
		 * @return <tt>true</tt> if an exercise took the shot
		 */
		boolean addArenaShot(S shot, Optional<String> videoString, boolean mirrored);
	}

	private final Surface<S> surface;
	private final Settings settings;

	// Guarded by this: why the shot before the next row was rejected, shown on that row
	private boolean hadMalfunction = false;
	private boolean hadReload = false;

	public ShotPipeline(Surface<S> surface, Settings settings) {
		this.surface = surface;
		this.settings = settings;
	}

	/**
	 * Handles a shot on this surface, in its coordinates.
	 */
	public void addShot(S shot, boolean mirrored) {
		if (!mirrored) {
			final Optional<ShotProcessor> rejectingProcessor = processShot(shot);
			if (rejectingProcessor.isPresent()) {
				recordRejectedShot(shot, rejectingProcessor.get());
				return;
			} else {
				notifyShot(shot);
			}

			if (settings.useRedLaserSound()
					&& (ShotColor.RED.equals(shot.getColor()) || ShotColor.INFRARED.equals(shot.getColor()))) {
				SoundPlayer.play(settings.getRedLaserSound());
			} else if (settings.useGreenLaserSound() && ShotColor.GREEN.equals(shot.getColor())) {
				SoundPlayer.play(settings.getGreenLaserSound());
			}
		}

		final Optional<ShotTimer<S>> shotTimer = surface.shotTimer();
		if (shotTimer.isPresent()) {
			final boolean malfunction;
			final boolean reload;
			synchronized (this) {
				malfunction = hadMalfunction;
				reload = hadReload;
				hadMalfunction = false;
				hadReload = false;
			}
			shotTimer.get().appendShotRow(shot, malfunction, reload);
		}

		surface.show(shot);

		final Optional<String> videoString = createVideoString(shot);

		final Optional<Arena<S>> arena = surface.arena();
		if (arena.isPresent()) {
			final Optional<Rect> projection = arena.get().projection();

			if (projection.isPresent() && projection.get().contains(shot.getX(), shot.getY())) {
				final Point arenaPoint = ArenaGeometry.canvasToArena(shot.getX(), shot.getY(), projection.get(),
						arena.get().size());
				arena.get().addArenaShot(arena.get().toArenaShot(shot, arenaPoint), videoString, mirrored);

				// The arena handled the shot
				return;
			}
		}

		final Optional<Hit> hit = hitTest(shot, videoString, mirrored);
		runRegionCommands(shot, hit, mirrored);
		surface.deliver(shot, hit, false);
	}

	/**
	 * Handles a shot another surface passed on, in this surface's (the arena's) coordinates.
	 *
	 * @return <tt>true</tt> if an exercise took the shot
	 */
	public boolean addArenaShot(S shot, Optional<String> videoString, boolean mirrored) {
		surface.show(shot);

		final Optional<Hit> hit = hitTest(shot, videoString, mirrored);
		runRegionCommands(shot, hit, mirrored);

		if (!mirrored) return surface.deliver(shot, hit, true);

		return false;
	}

	/**
	 * Hit-tests a shot against this surface's targets and records it in the session being recorded,
	 * unless it is mirrored.
	 */
	public Optional<Hit> hitTest(S shot, Optional<String> videoString, boolean mirrored) {
		final Optional<Hit> hit = surface.hitTest(shot.getX(), shot.getY());

		if (hit.isPresent()) {
			if (settings.inDebugMode()) {
				final Region region = hit.get().region();
				logger.debug("Processing Shot: Found Hit Region For Shot ({}, {}), Type ({}), Tags ({})", shot.getX(),
						shot.getY(), region.getClass().getSimpleName(), region.tags());
			}
		} else {
			logger.debug("Processing Shot: Did Not Find Hit For Shot ({}, {})", shot.getX(), shot.getY());
		}

		final Optional<SessionRecorder> recorder = settings.getSessionRecorder();
		if (!mirrored && recorder.isPresent()) {
			recorder.get().recordShot(surface.name(), shot, surface.markerRadius(shot), false, false,
					hit.map(h -> new TargetRef(surface.targets(), h.targetId())), hit.map(h -> h.region().index()),
					videoString);
		}

		return hit;
	}

	private void runRegionCommands(S shot, Optional<Hit> hit, boolean mirrored) {
		if (hit.isPresent() && hit.get().region().tags().containsKey(Region.TAG_COMMAND)) {
			surface.runRegionCommands(shot, hit.get(), mirrored);
		}
	}

	private Optional<ShotProcessor> processShot(Shot shot) {
		for (final ShotProcessor processor : settings.getShotProcessors()) {
			if (!processor.processShot(shot)) {
				synchronized (this) {
					if (processor instanceof MalfunctionsProcessor) {
						hadMalfunction = true;
					} else if (processor instanceof VirtualMagazineProcessor) {
						hadReload = true;
					}
				}

				logger.debug("Processing Shot: Shot Rejected By {}", processor.getClass().getName());
				return Optional.of(processor);
			}
		}

		return Optional.empty();
	}

	private void recordRejectedShot(S shot, ShotProcessor rejectingProcessor) {
		final Optional<SessionRecorder> recorder = settings.getSessionRecorder();
		if (recorder.isEmpty()) return;

		notifyShot(shot);

		final Optional<String> videoString = createVideoString(shot);

		if (rejectingProcessor instanceof MalfunctionsProcessor) {
			recorder.get().recordShot(surface.name(), shot, surface.markerRadius(shot), true, false, Optional.empty(),
					Optional.empty(), videoString);
		} else if (rejectingProcessor instanceof VirtualMagazineProcessor) {
			recorder.get().recordShot(surface.name(), shot, surface.markerRadius(shot), false, true, Optional.empty(),
					Optional.empty(), videoString);
		}
	}

	// The cameras that save video with each recorded shot
	private void notifyShot(Shot shot) {
		if (settings.getSessionRecorder().isPresent()) {
			for (final CameraManager cm : settings.getRecordingManagers())
				cm.notifyShot(shot);
		}
	}

	// Which videos show the shot, as session files store it
	private Optional<String> createVideoString(Shot shot) {
		if (settings.getSessionRecorder().isPresent() && !settings.getRecordingManagers().isEmpty()) {
			final StringBuilder sb = new StringBuilder();

			for (final CameraManager cm : settings.getRecordingManagers()) {
				final ShotRecorder r = cm.getRevelantRecorder(shot);

				// No recorder when forking the shot video failed
				if (r == null) continue;

				if (sb.length() > 0) {
					sb.append(",");
				}

				sb.append(r.getCameraName().replaceAll(":", "-"));
				sb.append(":");
				sb.append(r.getRelativeVideoFile().getPath());
			}

			return sb.length() == 0 ? Optional.empty() : Optional.of(sb.toString());
		}

		return Optional.empty();
	}
}
```

- [ ] **Step 5: Run the core tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.shots.*' --console=plain && command grep -ho 'tests="[0-9]*" skipped="0" failures="0" errors="0"' core/build/test-results/test/TEST-com.shootoff.shots.*.xml`
Expected: `BUILD SUCCESSFUL`, then four lines, for `TestPoiAdjustment`, `TestShotPipeline`, `TestShotQueue` and `TestTimerRow` in that order: `tests="2"`, `tests="10"`, `tests="2"`, `tests="1"`.

- [ ] **Step 6: Put `CanvasManager` on the pipeline**

In `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java`:

(a) Imports:
- delete `java.util.Iterator`, `java.util.Map.Entry`, `com.shootoff.camera.processors.MalfunctionsProcessor`, `com.shootoff.camera.processors.ShotProcessor`, `com.shootoff.camera.processors.VirtualMagazineProcessor` and `com.shootoff.camera.recorders.ShotRecorder`
- after `import com.shootoff.session.TargetRef;` add:

```java
import com.shootoff.shots.ShotPipeline;
import com.shootoff.shots.ShotTimer;
```

(b) Replace the two fields

```java
	private boolean hadMalfunction = false;
	private boolean hadReload = false;
```

with:

```java
	private final ShotPipeline<DisplayShot> shotPipeline;
```

(c) In the constructor, right after `this.shotEntries = shotEntries;`, add:

```java
		shotPipeline = new ShotPipeline<>(new PipelineSurface(), config);
```

(d) Replace everything from `	private void notifyShot(Shot shot) {` up to, not including, `	protected Optional<TargetComponents> loadTarget(File targetFile, boolean playAnimations) {` with the block below.
- `getShots`, `addShot(ScaledShot)`, `scaleShotToArenaBounds` (as Task 1 left it), `drawShot`, `targetFor` and `executeRegionCommands` are unchanged; they are repeated so the block is complete.
- The row code is the old `addShot`'s, now `appendShotEntry`.
- `deliver` is the old exercise rule. `ArenaLink` is the old arena hand-off.

```java
	// A shot's row in the shot timer, after the latest row
	private void appendShotEntry(DisplayShot shot, boolean hadMalfunction, boolean hadReload) {
		final Optional<Shot> lastShot;

		if (shotEntries.isEmpty()) {
			lastShot = Optional.empty();
		} else {
			lastShot = Optional.of(shotEntries.get(shotEntries.size() - 1).getShot());
		}

		final ShotEntry shotEntry = new ShotEntry(shot, lastShot, config.getShotTimerRowColor(), hadMalfunction,
				hadReload);

		try {
			shotEntries.add(shotEntry);
		} catch (final NullPointerException npe) {
			logger.error("JDK 8094135 exception", npe);
			jdk8094135Warning();
		}
	}

	// For testing
	protected List<DisplayShot> getShots() {
		return shots;
	}

	@Override
	public void addShot(ScaledShot shot) {
		addShot(new DisplayShot(shot, config.getMarkerRadius()), false);
	}

	/**
	 * Handles a shot on this canvas, in its coordinates: see {@link ShotPipeline#addShot}.
	 */
	public void addShot(DisplayShot shot, boolean isMirroredShot) {
		shotPipeline.addShot(shot, isMirroredShot);
	}

	public void scaleShotToArenaBounds(ArenaShot shot) {
		if (!projectionBounds.isPresent()) {
			logger.error("scaleShotToArenaBounds called when projectionBounds not present");
			return;
		}
		
		logger.trace("scaleShotToArenaBounds pre x {} y {}", shot.getX(), shot.getY());

		final Point arena = ArenaGeometry.canvasToArena(shot.getX(), shot.getY(), projectionBounds.get(),
				new Size(arenaPane.get().getWidth(), arenaPane.get().getHeight()));
		shot.setArenaCoords(arena.getX(), arena.getY());

		logger.trace("scaleShotToArenaBounds post x {} y {}", shot.getX(), shot.getY());
	}

	/**
	 * Handles a shot a camera feed passed on to this (arena) canvas: see
	 * {@link ShotPipeline#addArenaShot}.
	 */
	public boolean addArenaShot(ArenaShot shot, Optional<String> videoString, boolean isMirroredShot) {
		return shotPipeline.addArenaShot(shot, videoString, isMirroredShot);
	}

	private void drawShot(DisplayShot shot) {
		final Runnable drawShotAction = () -> {
			canvasGroup.getChildren().add(shot.getMarker());
			shot.getMarker().setVisible(showShots);
		};

		if (Platform.isFxApplicationThread()) {
			drawShotAction.run();
		} else {
			Platform.runLater(drawShotAction);
		}
	}

	protected Optional<Hit> checkHit(DisplayShot shot, Optional<String> videoString, boolean isMirroredShot) {
		return shotPipeline.hitTest(shot, videoString, isMirroredShot).flatMap(hit -> toHit(shot, hit));
	}

	// The v1 hit for a hit on this canvas's targets; empty if another thread removed the target meanwhile
	private Optional<Hit> toHit(DisplayShot shot, com.shootoff.targets.model.Hit modelHit) {
		return targetFor(modelHit.targetId()).map(target -> {
			final Hit hit = target.toHit(modelHit, shot.getX(), shot.getY());
			hit.setShot(shot);
			return hit;
		});
	}

	// The view of a target in this canvas's set; empty if another thread removed it meanwhile
	private Optional<TargetView> targetFor(TargetId id) {
		for (final Target target : new ArrayList<>(targets)) {
			final TargetView view = (TargetView) target;
			if (view.getPlacedTarget().getId().equals(id)) return Optional.of(view);
		}

		return Optional.empty();
	}

	private void executeRegionCommands(Hit hit, boolean isMirroredShot) {
		if (arenaPane.isPresent())
			TargetView.parseCommandTag(hit.getHitRegion(), new TargetCommands(arenaPane.get().getCanvasManager(), targets, resetter, hit, isMirroredShot));
		else
			TargetView.parseCommandTag(hit.getHitRegion(), new TargetCommands(this, targets, resetter, hit, isMirroredShot));

	}

	// This canvas, as the shot pipeline sees it
	private final class PipelineSurface implements ShotPipeline.Surface<DisplayShot> {
		@Override
		public String name() {
			return cameraName;
		}

		@Override
		public TargetSet targets() {
			return targetSet;
		}

		@Override
		public Optional<com.shootoff.targets.model.Hit> hitTest(double x, double y) {
			// The model checks visible targets topmost (last added) first, so shots register for the
			// top target when targets overlap
			return HitTester.hit(targetSet, x, y).filter(hit -> targetFor(hit.targetId()).isPresent());
		}

		@Override
		public Optional<ShotTimer<DisplayShot>> shotTimer() {
			if (shotEntries == null) return Optional.empty();

			return Optional.of(CanvasManager.this::appendShotEntry);
		}

		@Override
		public void show(DisplayShot shot) {
			shots.add(shot);
			drawShot(shot);
		}

		@Override
		public int markerRadius(DisplayShot shot) {
			return (int) shot.getMarker().getRadiusX();
		}

		@Override
		public void runRegionCommands(DisplayShot shot, com.shootoff.targets.model.Hit hit, boolean mirrored) {
			toHit(shot, hit).ifPresent(v1Hit -> executeRegionCommands(v1Hit, mirrored));
		}

		@Override
		public boolean deliver(DisplayShot shot, Optional<com.shootoff.targets.model.Hit> hit, boolean arenaShot) {
			final Optional<TrainingExercise> currentExercise = config.getExercise();
			if (currentExercise.isEmpty()) return false;

			// If the canvas is mirrored, use the one without the camera manager for exercises because
			// that is the one for the arena window. If we use the arena tab canvas manager the targets
			// will be copies and will not be the versions of the targets added by exercises.
			if (!arenaShot && CanvasManager.this instanceof MirroredCanvasManager && cameraManager != null) return false;

			currentExercise.get().shotListener(shot, hit.flatMap(h -> toHit(shot, h)));
			return true;
		}

		@Override
		public Optional<ShotPipeline.Arena<DisplayShot>> arena() {
			if (arenaPane.isEmpty() || CanvasManager.this instanceof MirroredCanvasManager) return Optional.empty();

			return Optional.of(new ArenaLink());
		}
	}

	// The projector arena, as this camera feed's canvas passes shots on to it
	private final class ArenaLink implements ShotPipeline.Arena<DisplayShot> {
		@Override
		public Optional<Rect> projection() {
			return projectionBounds;
		}

		@Override
		public Size size() {
			return new Size(arenaPane.get().getWidth(), arenaPane.get().getHeight());
		}

		@Override
		public DisplayShot toArenaShot(DisplayShot shot, Point arenaPoint) {
			final ArenaShot arenaShot = new ArenaShot(shot);
			arenaShot.setArenaCoords(arenaPoint.getX(), arenaPoint.getY());
			return arenaShot;
		}

		@Override
		public boolean addArenaShot(DisplayShot shot, Optional<String> videoString, boolean mirrored) {
			return arenaPane.get().getCanvasManager().addArenaShot((ArenaShot) shot, videoString, mirrored);
		}
	}
```

- [ ] **Step 7: `ShotEntry` and `poi_adjust` use the moved code**

In `javafx-app/src/main/java/com/shootoff/gui/ShotEntry.java`, replace `import com.shootoff.camera.shot.ShotColor;` with `import com.shootoff.shots.TimerRow;`. In the public constructor, replace everything after `this.shot = shot;` (the laser `if`/`else`, the time, the split and `this.split = …`) with:

```java

		final TimerRow row = TimerRow.of(shot, lastShot, hadMalfunction, hadReload);
		color = row.laser();
		this.rowColor = rowColor;
		timestamp = row.time();
		split = new SplitData(row.split(), rowColor, hadMalfunction, hadReload);
```

In `javafx-app/src/main/java/com/shootoff/gui/targets/TargetCommands.java`:
- add `import com.shootoff.geom.Point;` after `import com.shootoff.gui.CanvasManager;`
- add `import com.shootoff.shots.PoiAdjustment;` after `import com.shootoff.gui.Resetter;`
- in `case "poi_adjust":`, replace everything from the line `Pair<Double, Double> translated = canvasManager.translateCanvasToCameraPoint(…);` to the end of the case (the last `TrainingExerciseBase.playSound("sounds/beep2.wav");`) with:

```java
			final Pair<Double, Double> translated = canvasManager.translateCanvasToCameraPoint(nodeBounds.getX() + reg.getBoundsInParent().getMinX() + regcenterx, nodeBounds.getY() + reg.getBoundsInParent().getMinY() + regcentery);
			final BoundsShot shot = (BoundsShot) hit.getShot();
			final Point offset = PoiAdjustment.offset(new Point(translated.getKey(), translated.getValue()),
					new Point(shot.getBoundsX(), shot.getBoundsY()), hit.getTarget().getScaleX(),
					hit.getTarget().getScaleY());

			if (logger.isTraceEnabled()) {
				logger.trace("Adjusting POI regcenterx {} regcentery {}", translated.getKey(), translated.getValue());
				logger.trace("Adjusting POI scalex {} scaley {}", hit.getTarget().getScaleX(), hit.getTarget().getScaleY());
				logger.trace("Adjusting POI offsetx {} offsety {}", offset.getX(), offset.getY());
			}

			PoiAdjustment.apply(config, offset);
```

The region's center is still measured from the node as before (ruling 7), and `(shot − center) / scale` is the same operation.

- [ ] **Step 8: Run the JavaFX shot, hit, session and POI tests**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.*' --tests 'com.shootoff.plugins.*' --tests 'com.shootoff.camera.TestAutoCalibration' --console=plain`
Expected: `BUILD SUCCESSFUL`. `TestCanvasManager`, `TestCanvasManagerHits`, `TestCanvasManagerSessionRecording`, `TestTargetCommands`, `TestShotEntry`, `TestShotTimerRowOrder`, `TestJavaFxExerciseHost*` and the built-in exercise tests pass unchanged.

- [ ] **Step 9: Run the gate**

Expected: `392/392 passing; 0 regressions; 0 new failures` (passing = 379 + 13).

- [ ] **Step 10: Commit**

```bash
git add core/src/main/java/com/shootoff/shots/ShotPipeline.java core/src/main/java/com/shootoff/shots/ShotTimer.java core/src/main/java/com/shootoff/shots/TimerRow.java core/src/main/java/com/shootoff/shots/PoiAdjustment.java core/src/main/java/com/shootoff/config/Settings.java core/src/test/java/com/shootoff/shots/TestShotPipeline.java core/src/test/java/com/shootoff/shots/TestTimerRow.java core/src/test/java/com/shootoff/shots/TestPoiAdjustment.java javafx-app/src/main/java/com/shootoff/config/Configuration.java javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java javafx-app/src/main/java/com/shootoff/gui/ShotEntry.java javafx-app/src/main/java/com/shootoff/gui/targets/TargetCommands.java
git commit -m "Move the shot pipeline into core and put the JavaFX canvases on it"
git log -1 --format=%B
```

Expected: the message alone, with no trailer.

---
### Task 4: The calibration flow in `core`

**Files:**
- Create: `core/src/main/java/com/shootoff/calibration/CalibrationCamera.java`, `CalibrationFlow.java`
- Modify: `core/src/main/java/com/shootoff/camera/CameraManager.java` (implements `CalibrationCamera`; one import)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java` (rewritten around the flow, public API unchanged)
- Test: `core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java` (new)

**Interfaces:**
- Consumes:
  - `ArenaGeometry.cameraToCanvas` and `canvasToCamera` (Task 1)
  - core's `Settings` (`getDisplayWidth/Height`, `showArenaShotMarkers`), `CalibrationOption`, `PerspectiveManager`, `Rect`, `Size`
- Produces:
  - `interface com.shootoff.calibration.CalibrationCamera`: `String getName()`, `int getFeedWidth()`, `int getFeedHeight()`, `Optional<Rect> getProjectionBounds()`, `void setProjectionBounds(Rect)` (`null` clears), `void setCalibrating(boolean)`, `void setDetecting(boolean)`, `void enableAutoCalibration(boolean calculateFrameDelay)`, `void disableAutoCalibration()`, `void setCropFeedToProjection(boolean)`, `void setLimitDetectProjection(boolean)`. `CameraManager` implements it with its existing methods.
  - `final class CalibrationFlow`:
    - constants `AUTO_CALIBRATION_TIMEOUT` (12000), `AUTO_CALIBRATION_TIMEOUT_HEADLESS` (45000), `DETECTION_RESTART_DELAY` (600), `FULL_SCREEN_SETTLE_DELAY` (100), all milliseconds
    - `enum Message { FULL_SCREEN_REQUEST, AUTO_CALIBRATING, MANUAL_REQUEST }`
    - `interface View`:
      - `boolean isArenaFullScreen()`
      - `void setArenaShotsVisible(boolean)`
      - `void setCalibrating(boolean)`
      - `void calibrationStarted()`
      - `void saveArenaBackground()`
      - `void restoreArenaBackground()`
      - `void showPattern()`
      - `void showMessage(Message)`
      - `void hideMessage(Message)`
      - `void showCalibratingFeed()`
      - `void restoreSelectedView()`
      - `void showManualBox()`
      - `Optional<Rect> manualBox()`
      - `void removeManualBox()`
      - `void projectionCalibrated(Rect canvasBounds)`
      - `CalibrationOption calibratedFeedBehavior()`
      - `Size arenaResolution()`
      - `void calibrated(Optional<PerspectiveManager>)`
      - `void runOnUiThread(Runnable)`
    - `@FunctionalInterface interface Exercises { Optional<Runnable> stopProjectorExercise(); }`: returns what restarts it
    - `@FunctionalInterface interface Scheduler { Future<?> schedule(Runnable task, long delayMillis); }`: `TimerPool::schedule` fits
    - `CalibrationFlow(CalibrationCamera, View, Exercises, Settings, Scheduler, Optional<Runnable> headlessTimeout)`
    - `boolean isCalibrating()`, `void start()`, `void stop()`
    - `void calibrated(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas)`
    - `void configureArenaCamera(CalibrationOption)`, `void arenaClosing()`, `void setFullScreen(boolean)`

  Plan 5's Compose arena implements `View`.

**What moves.** Every step of `CalibrationManager.enableCalibration`, `stopCalibration`, `calibrate`, `setFullScreenStatus`, `enableAutoCalibration`, the auto-calibration timer and `enableManualCalibration` moves, in the same order. Only the drawing stays in JavaFX:
- the pattern image, the three diagnostic labels and their colors and chimes
- the purple manual box
- the camera view selection
- the arena pane and the calibration listeners

The order Plan 3's final review fixed is kept:
1. `start()` stops the projector exercise first. `JavaFxExerciseHost.stop()` restores the background synchronously on the JavaFX thread.
2. Then it saves the background and shows the pattern.
3. `stop()` restores the background.
4. The exercise restarts last.

- [ ] **Step 1: Write the failing test**

`core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java` uses a fake camera, a fake view that writes down each call, and a manual scheduler. The display is 1280 × 960 over a 640 × 480 feed, so canvas bounds are twice the feed's:

```java
package com.shootoff.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.perspective.PerspectiveManager;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

class TestCalibrationFlow {
	// What the camera, the user interface and the exercise were asked to do, in order
	private final List<String> events = new ArrayList<>();
	private final List<Timer> timers = new ArrayList<>();
	private Settings settings;
	private FakeCamera camera;
	private FakeView view;
	private boolean projectorExerciseRunning;
	private Optional<Runnable> headlessTimeout = Optional.empty();

	private record Timer(Runnable task, long delayMillis, CompletableFuture<Void> future) {}

	private final class FakeCamera implements CalibrationCamera {
		Optional<Rect> projectionBounds = Optional.of(new Rect(1, 2, 3, 4));

		@Override
		public String getName() {
			return "Fake camera";
		}

		@Override
		public int getFeedWidth() {
			return 640;
		}

		@Override
		public int getFeedHeight() {
			return 480;
		}

		@Override
		public Optional<Rect> getProjectionBounds() {
			return projectionBounds;
		}

		@Override
		public void setProjectionBounds(Rect bounds) {
			projectionBounds = Optional.ofNullable(bounds);
			events.add("camera bounds " + bounds);
		}

		@Override
		public void setCalibrating(boolean isCalibrating) {
			events.add("camera calibrating " + isCalibrating);
		}

		@Override
		public void setDetecting(boolean isDetecting) {
			events.add("camera detecting " + isDetecting);
		}

		@Override
		public void enableAutoCalibration(boolean calculateFrameDelay) {
			events.add("camera looks for the pattern");
		}

		@Override
		public void disableAutoCalibration() {
			events.add("camera stops looking");
		}

		@Override
		public void setCropFeedToProjection(boolean cropFeed) {
			events.add("camera crops " + cropFeed);
		}

		@Override
		public void setLimitDetectProjection(boolean limitDetection) {
			events.add("camera limits detection " + limitDetection);
		}
	}

	private final class FakeView implements CalibrationFlow.View {
		boolean fullScreen = true;
		Optional<Rect> box = Optional.empty();
		CalibrationOption behavior = CalibrationOption.ONLY_IN_BOUNDS;

		@Override
		public boolean isArenaFullScreen() {
			return fullScreen;
		}

		@Override
		public void setArenaShotsVisible(boolean visible) {
			events.add("arena shots visible " + visible);
		}

		@Override
		public void setCalibrating(boolean calibrating) {
			events.add("calibrate button calibrating " + calibrating);
		}

		@Override
		public void calibrationStarted() {
			events.add("arena hides its targets");
		}

		@Override
		public void saveArenaBackground() {
			events.add("save background");
		}

		@Override
		public void restoreArenaBackground() {
			events.add("restore background");
		}

		@Override
		public void showPattern() {
			events.add("show pattern");
		}

		@Override
		public void showMessage(CalibrationFlow.Message message) {
			events.add("show " + message);
		}

		@Override
		public void hideMessage(CalibrationFlow.Message message) {
			events.add("hide " + message);
		}

		@Override
		public void showCalibratingFeed() {
			events.add("show calibrating feed");
		}

		@Override
		public void restoreSelectedView() {
			events.add("restore selected view");
		}

		@Override
		public void showManualBox() {
			box = Optional.of(new Rect(75, 75, 150, 150));
			events.add("show box");
		}

		@Override
		public Optional<Rect> manualBox() {
			return box;
		}

		@Override
		public void removeManualBox() {
			if (box.isPresent()) events.add("remove box");
			box = Optional.empty();
		}

		@Override
		public void projectionCalibrated(Rect canvasBounds) {
			events.add("projection on the canvas " + canvasBounds);
		}

		@Override
		public CalibrationOption calibratedFeedBehavior() {
			return behavior;
		}

		@Override
		public Size arenaResolution() {
			return new Size(1280, 720);
		}

		@Override
		public void calibrated(Optional<PerspectiveManager> perspectiveManager) {
			events.add("calibrated" + (perspectiveManager.isPresent() ? " with perspective" : ""));
		}

		@Override
		public void runOnUiThread(Runnable action) {
			events.add("(UI thread)");
			action.run();
		}
	}

	private CalibrationFlow flow() {
		return new CalibrationFlow(camera, view, () -> {
			if (!projectorExerciseRunning) return Optional.empty();

			events.add("stop exercise");
			projectorExerciseRunning = false;
			return Optional.of(() -> {
				events.add("restart exercise");
				projectorExerciseRunning = true;
			});
		}, settings, (task, delayMillis) -> {
			final CompletableFuture<Void> future = new CompletableFuture<>();
			timers.add(new Timer(task, delayMillis, future));
			return future;
		}, headlessTimeout);
	}

	// Runs the timers that are due after delayMillis and haven't been cancelled
	private void runTimers(long delayMillis) {
		for (final Timer timer : List.copyOf(timers)) {
			if (timer.delayMillis() == delayMillis && !timer.future().isCancelled() && !timer.future().isDone()) {
				timer.future().complete(null);
				timer.task().run();
			}
		}
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		// Feed 640 x 480 shown at 1280 x 960: the canvas is twice the feed
		settings = new Settings(new String[0]);
		settings.setDisplayResolution(1280, 960);
		settings.setShowArenaShotMarkers(false);
		camera = new FakeCamera();
		view = new FakeView();
	}

	@Test
	void startingOnAFullScreenArenaStopsTheDrillBeforeSavingTheBackgroundAndShowingThePattern() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();

		flow.start();

		assertTrue(flow.isCalibrating());
		assertEquals(List.of("stop exercise", "arena shots visible false", "calibrate button calibrating true",
				"camera calibrating true", "camera bounds null", "arena hides its targets", "save background",
				"show pattern", "camera looks for the pattern", "show AUTO_CALIBRATING"), events);
		assertEquals(List.of(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT),
				timers.stream().map(Timer::delayMillis).toList());
	}

	@Test
	void autoCalibrationSavesTheBoundsThenRestoresTheBackgroundBeforeRestartingTheDrill() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
		flow.start();
		events.clear();

		// The camera found the pattern, in feed coordinates
		flow.calibrated(new Rect(100, 50, 400, 300), Optional.empty(), false);

		assertFalse(flow.isCalibrating());
		assertEquals(List.of("projection on the canvas " + new Rect(200, 100, 800, 600), "camera crops false",
				"camera limits detection true", "camera bounds " + new Rect(100, 50, 400, 300), "camera stops looking",
				"calibrate button calibrating false", "hide AUTO_CALIBRATING", "restore selected view", "calibrated",
				"restore background", "camera calibrating false", "camera detecting false", "arena shots visible false",
				"restart exercise"), events);
		assertTrue(timers.get(0).future().isCancelled(), "the auto-calibration timeout");

		events.clear();
		runTimers(CalibrationFlow.DETECTION_RESTART_DELAY);
		assertEquals(List.of("camera detecting true"), events);
	}

	@Test
	void aWindowedArenaIsAskedToGoFullScreenAndThenAutoCalibrates() {
		view.fullScreen = false;
		final CalibrationFlow flow = flow();

		flow.start();
		assertEquals(List.of("arena shots visible false", "calibrate button calibrating true", "camera calibrating true",
				"camera bounds null", "show FULL_SCREEN_REQUEST"), events);

		events.clear();
		view.fullScreen = true;
		flow.setFullScreen(true);
		assertEquals(List.of("hide FULL_SCREEN_REQUEST"), events);

		events.clear();
		runTimers(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY);
		assertEquals(List.of("(UI thread)", "arena hides its targets", "save background", "show pattern",
				"camera looks for the pattern", "show AUTO_CALIBRATING"), events);
	}

	@Test
	void leavingFullScreenPausesAutoCalibrationAndReturningKeepsTheSavedBackground() {
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		events.clear();

		flow.setFullScreen(false);
		assertEquals(List.of("camera stops looking", "hide AUTO_CALIBRATING", "show FULL_SCREEN_REQUEST"), events);

		events.clear();
		flow.setFullScreen(true);
		runTimers(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY);
		// The pattern is still the background: saving now would save the pattern
		assertFalse(events.contains("save background"));
		assertTrue(events.contains("show pattern"));
	}

	@Test
	void aTimeoutOpensTheManualBoxAndStoppingCalibratesToIt() {
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		events.clear();

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertEquals(List.of("(UI thread)", "camera stops looking", "hide AUTO_CALIBRATING", "show calibrating feed",
				"show MANUAL_REQUEST", "show box"), events);
		assertTrue(flow.isCalibrating());

		events.clear();
		flow.stop();

		// The box is on the canvas; the camera gets it in feed coordinates
		assertEquals(List.of("remove box", "projection on the canvas " + new Rect(75, 75, 150, 150),
				"camera crops false", "camera limits detection true", "camera bounds " + new Rect(37.5, 37.5, 75, 75),
				"camera stops looking", "calibrate button calibrating false", "hide MANUAL_REQUEST",
				"restore selected view", "calibrated", "restore background", "camera calibrating false",
				"camera detecting false", "arena shots visible false"), events);
	}

	@Test
	void aHeadlessTimeoutReportsItAndEndsCalibration() {
		headlessTimeout = Optional.of(() -> events.add("headless timeout"));
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		events.clear();

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertEquals(List.of(), events);

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_HEADLESS);
		assertEquals(List.of("(UI thread)", "headless timeout"), events.subList(0, 2));
		assertFalse(flow.isCalibrating());
		assertFalse(events.contains("show box"));
	}

	@Test
	void theCalibratedFeedBehaviorDecidesCroppingAndLimiting() {
		final CalibrationFlow flow = flow();

		view.behavior = CalibrationOption.CROP;
		flow.calibrated(new Rect(0, 0, 10, 10), Optional.empty(), true);
		assertTrue(events.containsAll(List.of("camera crops true", "camera limits detection false")));

		events.clear();
		flow.configureArenaCamera(CalibrationOption.EVERYWHERE);
		assertEquals(List.of("camera crops false", "camera limits detection false"), events);

		events.clear();
		flow.arenaClosing();
		assertEquals(List.of("camera bounds null"), events);
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationFlow' --console=plain`
Expected: FAIL at compile time: `cannot find symbol` … `CalibrationCamera`, `CalibrationFlow`.

- [ ] **Step 3: Write `CalibrationCamera` and `CalibrationFlow`**

`core/src/main/java/com/shootoff/calibration/CalibrationCamera.java` (GPL header, then):

```java
package com.shootoff.calibration;

import java.util.Optional;

import com.shootoff.geom.Rect;

/**
 * The camera pointed at the projector arena, as calibration uses it. {@link
 * com.shootoff.camera.CameraManager} is one.
 */
public interface CalibrationCamera {
	String getName();

	int getFeedWidth();

	int getFeedHeight();

	/**
	 * @return the arena's calibrated projection on the camera feed, if it is calibrated
	 */
	Optional<Rect> getProjectionBounds();

	/**
	 * @param projectionBounds
	 *            the arena's projection on the camera feed, or <tt>null</tt> for none
	 */
	void setProjectionBounds(Rect projectionBounds);

	/**
	 * While calibrating, the camera doesn't detect shots.
	 */
	void setCalibrating(boolean isCalibrating);

	void setDetecting(boolean isDetecting);

	/**
	 * Starts looking for the calibration pattern in the camera's frames. When it finds it, the
	 * camera calls {@link CalibrationFlow#calibrated} with the pattern's bounds on the feed.
	 */
	void enableAutoCalibration(boolean calculateFrameDelay);

	void disableAutoCalibration();

	void setCropFeedToProjection(boolean cropFeed);

	void setLimitDetectProjection(boolean limitDetection);
}
```

`core/src/main/java/com/shootoff/calibration/CalibrationFlow.java` (GPL header, then):

```java
package com.shootoff.calibration;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.perspective.PerspectiveManager;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * Calibrating the projector arena, whatever draws it:
 * <ol>
 * <li><b>Start</b>: a running projector exercise is stopped (it could change the arena under the
 * camera), the arena hides its shots, and the camera stops detecting shots and forgets the old
 * bounds.</li>
 * <li><b>Full screen</b>: auto-calibration needs the arena full screen on the projector; until it is,
 * the user is asked to make it so.</li>
 * <li><b>Auto-detect</b>: the arena's background is saved and the calibration pattern shown; the
 * camera looks for it.</li>
 * <li><b>Success</b>: the camera reports the pattern's bounds, which become the arena's projection,
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends).</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
 * arena's background comes back, detection resumes after a moment, and the projector exercise that
 * was stopped starts again, last.</li>
 * </ol>
 * The flow runs on whatever thread calls it, as the JavaFX app always has (the camera reports success
 * on its own thread); its timers come back on the UI thread through {@link View#runOnUiThread}.
 */
public final class CalibrationFlow {
	private static final Logger logger = LoggerFactory.getLogger(CalibrationFlow.class);

	public static final long AUTO_CALIBRATION_TIMEOUT = 12 * 1000;
	public static final long AUTO_CALIBRATION_TIMEOUT_HEADLESS = 45 * 1000;
	// The pattern going away can look like shots
	public static final long DETECTION_RESTART_DELAY = 600;
	// Before auto-calibrating once the arena is full screen (ShootOFF issue #444)
	public static final long FULL_SCREEN_SETTLE_DELAY = 100;

	/**
	 * The messages calibration shows the user on the calibrating camera's feed.
	 */
	public enum Message {
		/** Move the arena to the projector and make it full screen */
		FULL_SCREEN_REQUEST,
		/** Auto-calibration is looking for the pattern */
		AUTO_CALIBRATING,
		/** Drag the box over the projection */
		MANUAL_REQUEST
	}

	/**
	 * What calibration shows and asks of the user interface.
	 */
	public interface View {
		boolean isArenaFullScreen();

		void setArenaShotsVisible(boolean visible);

		/**
		 * @param calibrating
		 *            <tt>true</tt> while calibrating, e.g. to relabel the calibrate button
		 */
		void setCalibrating(boolean calibrating);

		/**
		 * The pattern is about to show: the arena hides its targets and its "needs calibration" label.
		 */
		void calibrationStarted();

		void saveArenaBackground();

		/**
		 * Shows the background saved by {@link #saveArenaBackground()} again, or none.
		 */
		void restoreArenaBackground();

		void showPattern();

		void showMessage(Message message);

		void hideMessage(Message message);

		/**
		 * Remembers which view the user was looking at and shows the calibrating camera's feed.
		 */
		void showCalibratingFeed();

		/**
		 * Shows the view remembered by {@link #showCalibratingFeed()}, if any.
		 */
		void restoreSelectedView();

		/**
		 * Shows a box the user drags and resizes over the projection on the calibrating camera's feed.
		 */
		void showManualBox();

		/**
		 * @return the box's bounds on the feed's canvas, if it is showing
		 */
		Optional<Rect> manualBox();

		void removeManualBox();

		/**
		 * The arena's projection on the calibrating camera feed's canvas changed; shots inside it now go
		 * to the arena.
		 */
		void projectionCalibrated(Rect canvasBounds);

		/**
		 * @return how the calibrating feed treats the projection: crop to it, only detect in it, or
		 *         neither
		 */
		CalibrationOption calibratedFeedBehavior();

		/**
		 * @return the arena window's size
		 */
		Size arenaResolution();

		/**
		 * Calibration ended.
		 */
		void calibrated(Optional<PerspectiveManager> perspectiveManager);

		void runOnUiThread(Runnable action);
	}

	/**
	 * The running exercise, as calibration pauses it.
	 */
	@FunctionalInterface
	public interface Exercises {
		/**
		 * Stops the running exercise if it runs on the projector arena.
		 *
		 * @return what starts it again, or empty if none was running
		 */
		Optional<Runnable> stopProjectorExercise();
	}

	@FunctionalInterface
	public interface Scheduler {
		/**
		 * Runs <tt>task</tt> on a background thread after <tt>delayMillis</tt>.
		 *
		 * @return the task, to cancel it; <tt>null</tt> if it can't be scheduled
		 */
		Future<?> schedule(Runnable task, long delayMillis);
	}

	private final CalibrationCamera camera;
	private final View view;
	private final Exercises exercises;
	private final Settings settings;
	private final Scheduler scheduler;
	private final Optional<Runnable> headlessTimeout;

	private final AtomicBoolean isCalibrating = new AtomicBoolean(false);
	private final AtomicBoolean isShowingPattern = new AtomicBoolean(false);
	private volatile boolean isFullScreen = false;
	private final Set<Message> shownMessages = EnumSet.noneOf(Message.class);
	private volatile Future<?> autoCalibrationTimer = null;
	private volatile Optional<Runnable> restartExercise = Optional.empty();
	private volatile Optional<Size> perspectivePaperDims = Optional.empty();

	/**
	 * @param headlessTimeout
	 *            for headless use: runs when auto-calibration times out after 45 seconds, and calibration
	 *            then ends; without it, auto-calibration times out after 12 seconds to the manual box
	 */
	public CalibrationFlow(CalibrationCamera camera, View view, Exercises exercises, Settings settings,
			Scheduler scheduler, Optional<Runnable> headlessTimeout) {
		this.camera = camera;
		this.view = view;
		this.exercises = exercises;
		this.settings = settings;
		this.scheduler = scheduler;
		this.headlessTimeout = headlessTimeout;
	}

	public boolean isCalibrating() {
		return isCalibrating.get();
	}

	/**
	 * Starts calibrating.
	 */
	public void start() {
		// Projector exercises change what is on the arena, which gets in the way of calibration. The
		// exercise is stopped first, so it has put the arena back before the pattern's background is
		// saved over it.
		restartExercise = exercises.stopProjectorExercise();

		view.setArenaShotsVisible(false);

		isCalibrating.set(true);

		view.setCalibrating(true);

		// Calibrating, and so not detecting
		camera.setCalibrating(true);
		camera.setProjectionBounds(null);

		if (view.isArenaFullScreen()) {
			startAutoCalibration();
		} else {
			showMessage(Message.FULL_SCREEN_REQUEST);
		}
	}

	/**
	 * Ends calibrating: with the manual box's bounds if it is showing, otherwise with the bounds found
	 * so far (possibly none).
	 */
	public void stop() {
		isCalibrating.set(false);

		final Optional<Rect> manualBox = view.manualBox();
		if (manualBox.isPresent()) calibrated(manualBox.get(), Optional.empty(), true);

		camera.disableAutoCalibration();

		cancelAutoCalibrationTimer();

		view.setCalibrating(false);

		hideMessage(Message.FULL_SCREEN_REQUEST);
		hideMessage(Message.AUTO_CALIBRATING);
		hideMessage(Message.MANUAL_REQUEST);
		view.removeManualBox();

		view.restoreSelectedView();

		view.calibrated(Optional.ofNullable(perspectiveManager()));

		view.restoreArenaBackground();

		camera.setCalibrating(false);

		isShowingPattern.set(false);

		// Shot detection stays off briefly because the pattern going away can cause false shots. This
		// applies to every camera feed rather than just the arena's.
		camera.setDetecting(false);
		scheduler.schedule(() -> camera.setDetecting(true), DETECTION_RESTART_DELAY);

		view.setArenaShotsVisible(settings.showArenaShotMarkers());

		restartExercise.ifPresent(Runnable::run);
	}

	/**
	 * The arena's projection was found: by the camera (<tt>calibratedFromCanvas</tt> false, bounds on
	 * the camera feed) or with the manual box (true, bounds on the feed's canvas). Ends calibrating if it
	 * is still going.
	 */
	public void calibrated(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas) {
		view.removeManualBox();

		final Rect canvasBounds = calibratedFromCanvas ? arenaBounds
				: ArenaGeometry.cameraToCanvas(arenaBounds, feedSize(), displaySize());

		final Rect cameraBounds = ArenaGeometry.canvasToCamera(canvasBounds, feedSize(), displaySize());
		view.projectionCalibrated(canvasBounds);
		configureArenaCamera(view.calibratedFeedBehavior());
		camera.setProjectionBounds(cameraBounds);

		logger.debug("calibrate {} {} {}", canvasBounds, perspectivePaperDims, calibratedFromCanvas);

		this.perspectivePaperDims = perspectivePaperDims;

		if (isCalibrating()) stop();
	}

	/**
	 * Applies how the calibrating feed treats the projection.
	 */
	public void configureArenaCamera(CalibrationOption option) {
		camera.setCropFeedToProjection(CalibrationOption.CROP.equals(option));
		camera.setLimitDetectProjection(CalibrationOption.ONLY_IN_BOUNDS.equals(option));
	}

	/**
	 * The arena window is closing: its projection is gone.
	 */
	public void arenaClosing() {
		camera.setProjectionBounds(null);
	}

	/**
	 * The arena went full screen, or left it. Starts calibrating if it isn't already. Otherwise leaving
	 * full screen pauses auto-calibration and asks for full screen again, and going full screen resumes
	 * it after a moment.
	 */
	public void setFullScreen(boolean fullScreen) {
		isFullScreen = fullScreen;

		logger.trace("setFullScreenStatus - {} {}", fullScreen, isCalibrating);

		if (!isCalibrating.get()) {
			start();
		} else if (!fullScreen) {
			camera.disableAutoCalibration();

			view.removeManualBox();
			hideMessage(Message.AUTO_CALIBRATING);
			hideMessage(Message.MANUAL_REQUEST);

			showMessage(Message.FULL_SCREEN_REQUEST);
		} else {
			hideMessage(Message.FULL_SCREEN_REQUEST);
			// Delay slightly to prevent #444 bug
			scheduler.schedule(() -> view.runOnUiThread(() -> {
				if (isCalibrating.get()) startAutoCalibration();
			}), FULL_SCREEN_SETTLE_DELAY);
		}
	}

	private void startAutoCalibration() {
		logger.trace("enableAutoCalibration");

		view.calibrationStarted();
		// We may already be calibrating if the user decided to move the arena to another screen while
		// calibrating. If we save the background in that case we are saving the calibration pattern as
		// the background.
		if (!isShowingPattern.get()) view.saveArenaBackground();
		view.showPattern();
		isShowingPattern.set(true);

		camera.enableAutoCalibration(false);

		showMessage(Message.AUTO_CALIBRATING);

		launchAutoCalibrationTimer();
	}

	private void launchAutoCalibrationTimer() {
		cancelAutoCalibrationTimer();

		autoCalibrationTimer = scheduler.schedule(() -> view.runOnUiThread(() -> {
			if (isCalibrating.get() && isFullScreen) {
				if (headlessTimeout.isPresent()) {
					headlessTimeout.get().run();
					stop();
				} else {
					camera.disableAutoCalibration();
					startManualCalibration();
				}
			}
			// Keep waiting
			else if (!isFullScreen) launchAutoCalibrationTimer();
		}), headlessTimeout.isPresent() ? AUTO_CALIBRATION_TIMEOUT_HEADLESS : AUTO_CALIBRATION_TIMEOUT);
	}

	private void cancelAutoCalibrationTimer() {
		final Future<?> timer = autoCalibrationTimer;
		if (timer != null) timer.cancel(false);
	}

	private void startManualCalibration() {
		logger.trace("enableManualCalibration");

		hideMessage(Message.AUTO_CALIBRATING);

		view.showCalibratingFeed();

		showMessage(Message.MANUAL_REQUEST);

		view.showManualBox();
	}

	private void showMessage(Message message) {
		synchronized (shownMessages) {
			if (!shownMessages.add(message)) return;
		}

		view.showMessage(message);
	}

	private void hideMessage(Message message) {
		synchronized (shownMessages) {
			if (!shownMessages.remove(message)) return;
		}

		view.hideMessage(message);
	}

	private PerspectiveManager perspectiveManager() {
		final Optional<Rect> bounds = camera.getProjectionBounds();
		if (bounds.isEmpty()) return null;

		final Size feedDim = feedSize();
		final Optional<Size> paper = perspectivePaperDims;

		if (PerspectiveManager.isCameraSupported(camera.getName(), feedDim)) {
			if (paper.isPresent()) {
				return new PerspectiveManager(camera.getName(), bounds.get(), feedDim, paper.get(),
						view.arenaResolution());
			} else {
				return new PerspectiveManager(camera.getName(), bounds.get(), feedDim, view.arenaResolution());
			}
		} else if (paper.isPresent()) {
			return new PerspectiveManager(bounds.get(), feedDim, paper.get(), view.arenaResolution());
		}

		logger.debug("Too many perspective parameters are unknown to create a perspective manager.");
		return null;
	}

	private Size feedSize() {
		return new Size(camera.getFeedWidth(), camera.getFeedHeight());
	}

	private Size displaySize() {
		return new Size(settings.getDisplayWidth(), settings.getDisplayHeight());
	}
}
```

In `core/src/main/java/com/shootoff/camera/CameraManager.java`, replace the class declaration line

```java
public class CameraManager implements ObservableCloseable, CameraEventListener, CameraCalibrationListener {
```

with

```java
public class CameraManager
		implements ObservableCloseable, CameraEventListener, CameraCalibrationListener, CalibrationCamera {
```

and add `import com.shootoff.calibration.CalibrationCamera;` before `import com.shootoff.camera.autocalibration.AutoCalibrationManager;`. `CameraManager` already has every method with these signatures.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationFlow' --console=plain && command grep -ho 'tests="[0-9]*" skipped="0" failures="0" errors="0"' core/build/test-results/test/TEST-com.shootoff.calibration.TestCalibrationFlow.xml`
Expected: `BUILD SUCCESSFUL`, then `tests="7" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Rewrite `CalibrationManager` around the flow**

Replace `javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java` below its GPL header (lines 1–17 stay) with the code below.
- The constructor and every public method keep their signatures. `CameraCalibrationListener.calibrate` ignores `delay`, as before.
- The message texts, colors and chime delays are the old ones. So are the purple box at (75, 75) sized 150 × 150 (`createCalibrationTarget(DEFAULT_BOX_DIM, DEFAULT_BOX_DIM, DEFAULT_BOX_POS, DEFAULT_BOX_POS)`, with the old argument order), the view restore and the listeners.

```java
package com.shootoff.gui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.shootoff.calibration.CalibrationFlow;
import com.shootoff.calibration.CalibrationFlow.Message;
import com.shootoff.camera.CameraCalibrationListener;
import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.perspective.PerspectiveManager;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.Configuration;
import com.shootoff.gui.exercise.HostedExercise;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.targets.CameraViews;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.io.TargetIO.TargetComponents;
import com.shootoff.targets.model.ResourceResolver;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.util.TimerPool;

import javafx.application.Platform;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.stage.WindowEvent;

/**
 * Calibrates the projector arena in the JavaFX app: the calibration flow itself is core's {@link
 * CalibrationFlow}; this draws it (the pattern, the messages on the calibrating camera's feed and the
 * manual box) and passes the user's actions to it.
 */
public class CalibrationManager implements CameraCalibrationListener {
	private static final int DEFAULT_BOX_DIM = 75;
	private static final int DEFAULT_BOX_POS = 150;

	private final CalibrationConfigurator calibrationConfigurator;
	private final CanvasManager calibratingCanvasManager;
	private final CameraViews cameraViews;
	private final Configuration config;
	private final ExerciseListener exerciseListener;
	private final List<CalibrationListener> calibrationListeners = new ArrayList<>();
	private final ProjectorArenaPane arenaPane;
	private final CalibrationFlow flow;

	private Optional<TargetView> calibrationTarget = Optional.empty();
	private Optional<CameraView> originalView = Optional.empty();
	private final Map<Message, Label> messages = new EnumMap<>(Message.class);

	public CalibrationManager(CalibrationConfigurator calibrationConfigurator, CameraManager calibratingCameraManager,
			ProjectorArenaPane arenaPane, CameraViews cameraViews, AutocalibrationListener autocalibrationListener,
			ExerciseListener exerciseListener) {
		this.calibrationConfigurator = calibrationConfigurator;
		calibratingCanvasManager = (CanvasManager) calibratingCameraManager.getCameraView();
		calibrationListeners.add(arenaPane);
		this.arenaPane = arenaPane;
		this.cameraViews = cameraViews;
		config = Configuration.getConfig();
		this.exerciseListener = exerciseListener;

		flow = new CalibrationFlow(calibratingCameraManager, new FxView(), this::stopProjectorExercise, config,
				TimerPool::schedule, Optional.ofNullable(autocalibrationListener)
						.map(listener -> (Runnable) listener::autocalibrationTimedOut));

		arenaPane.setFeedCanvasManager(calibratingCanvasManager);
		calibratingCameraManager.setCalibrationManager(this);
		calibratingCameraManager.setOnCloseListener(() -> Platform
				.runLater(() -> arenaPane.fireEvent(new WindowEvent(null, WindowEvent.WINDOW_CLOSE_REQUEST))));
	}

	public void addCalibrationListener(CalibrationListener calibrationListener) {
		calibrationListeners.add(calibrationListener);
	}

	public void enableCalibration() {
		flow.start();
	}

	public void stopCalibration() {
		flow.stop();
	}

	@Override
	public void calibrate(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas,
			long delay) {
		flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas);
	}

	public void configureArenaCamera(CalibrationOption option) {
		flow.configureArenaCamera(option);
	}

	public void arenaClosing() {
		flow.arenaClosing();
	}

	public void setFullScreenStatus(boolean fullScreen) {
		flow.setFullScreen(fullScreen);
	}

	public boolean isCalibrating() {
		return flow.isCalibrating();
	}

	@Override
	public void setArenaBackground(String resourceFilename) {
		if (resourceFilename != null) {
			final InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourceFilename);
			final LocatedImage img = new LocatedImage(is, resourceFilename);
			arenaPane.setArenaBackground(img);
		} else {
			arenaPane.setArenaBackground(null);
		}
	}

	// Projector exercises can alter what is on the arena, thereby interfering with calibration
	private Optional<Runnable> stopProjectorExercise() {
		final Optional<TrainingExercise> exercise = config.getExercise();
		if (exercise.isEmpty() || !HostedExercise.isProjectorExercise(exercise.get())) return Optional.empty();

		exerciseListener.setExercise(null);
		return Optional.of(() -> exerciseListener.setProjectorExercise(exercise.get()));
	}

	private void createCalibrationTarget(double x, double y, double width, double height) {
		// Purple (Color.PURPLE) at the default target opacity, as before
		final TargetDefinition definition = new TargetDefinition(Optional.empty(), Map.of(), List.of(
				new com.shootoff.targets.model.RectangleRegion(0, x, y, width, height, "#800080", Map.of())));

		final TargetComponents components;
		try {
			components = TargetIO.buildTarget(definition, ResourceResolver.files(), false);
		} catch (final IOException e) {
			// A rectangle reads no files
			throw new UncheckedIOException(e);
		}

		calibrationTarget = Optional.of((TargetView) calibratingCanvasManager.addTarget(components, false));
		calibrationTarget.get().setKeepInBounds(true);
	}

	// The flow, drawn in the JavaFX app
	private final class FxView implements CalibrationFlow.View {
		@Override
		public boolean isArenaFullScreen() {
			return arenaPane.isFullScreen();
		}

		@Override
		public void setArenaShotsVisible(boolean visible) {
			arenaPane.getCanvasManager().setShowShots(visible);
		}

		@Override
		public void setCalibrating(boolean calibrating) {
			calibrationConfigurator.toggleCalibrating(calibrating);
		}

		@Override
		public void calibrationStarted() {
			for (final CalibrationListener c : calibrationListeners)
				c.startCalibration();
			arenaPane.setCalibrationMessageVisible(false);
		}

		@Override
		public void saveArenaBackground() {
			arenaPane.saveCurrentBackground();
		}

		@Override
		public void restoreArenaBackground() {
			arenaPane.restoreCurrentBackground();
		}

		@Override
		public void showPattern() {
			setArenaBackground("pattern.png");
		}

		@Override
		public void showMessage(Message message) {
			final Label label = switch (message) {
			case FULL_SCREEN_REQUEST -> calibratingCanvasManager
					.addDiagnosticMessage("Please move the arena to your projector and hit F11", Color.YELLOW);
			case AUTO_CALIBRATING -> calibratingCanvasManager.addDiagnosticMessage("Attempting autocalibration", 11000,
					Color.CYAN);
			case MANUAL_REQUEST -> calibratingCanvasManager
					.addDiagnosticMessage("Please manually calibrate the projection region", 20000, Color.ORANGE);
			};

			synchronized (messages) {
				messages.put(message, label);
			}
		}

		@Override
		public void hideMessage(Message message) {
			final Label label;
			synchronized (messages) {
				label = messages.remove(message);
			}

			if (label != null) calibratingCanvasManager.removeDiagnosticMessage(label);
		}

		@Override
		public void showCalibratingFeed() {
			originalView = Optional.of(cameraViews.getSelectedCameraView());
			cameraViews.selectCameraView(calibratingCanvasManager);
		}

		@Override
		public void restoreSelectedView() {
			if (originalView.isPresent()) {
				cameraViews.selectCameraView(originalView.get());
			}
		}

		@Override
		public void showManualBox() {
			if (!calibrationTarget.isPresent()) {
				createCalibrationTarget(DEFAULT_BOX_DIM, DEFAULT_BOX_DIM, DEFAULT_BOX_POS, DEFAULT_BOX_POS);
			} else {
				calibratingCanvasManager.addTarget(calibrationTarget.get());
			}
		}

		@Override
		public Optional<Rect> manualBox() {
			return calibrationTarget.map(target -> FxGeometry.toRect(target.getTargetGroup().getBoundsInParent()));
		}

		@Override
		public void removeManualBox() {
			if (calibrationTarget.isPresent()) {
				calibratingCanvasManager.removeTarget(calibrationTarget.get());
				calibrationTarget = Optional.empty();
			}
		}

		@Override
		public void projectionCalibrated(Rect canvasBounds) {
			calibratingCanvasManager.setProjectorArena(arenaPane, canvasBounds);
		}

		@Override
		public CalibrationOption calibratedFeedBehavior() {
			return calibrationConfigurator.getCalibratedFeedBehavior();
		}

		@Override
		public Size arenaResolution() {
			return FxGeometry.toSize(arenaPane.getArenaStageResolution());
		}

		@Override
		public void calibrated(Optional<PerspectiveManager> perspectiveManager) {
			for (final CalibrationListener c : calibrationListeners)
				c.calibrated(perspectiveManager);
		}

		@Override
		public void runOnUiThread(Runnable action) {
			Platform.runLater(action);
		}
	}
}
```

- [ ] **Step 6: Run the JavaFX calibration and exercise tests**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.camera.TestAutoCalibration' --tests 'com.shootoff.gui.exercise.*' --tests 'com.shootoff.gui.pane.*' --console=plain`
Expected: `BUILD SUCCESSFUL`. `TestAutoCalibration` builds `CalibrationManager` with a null `CameraViews` and a null autocalibration listener, as before.

- [ ] **Step 7: Run the gate**

Expected: `399/399 passing; 0 regressions; 0 new failures` (passing = 392 + 7).

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/shootoff/calibration/CalibrationCamera.java core/src/main/java/com/shootoff/calibration/CalibrationFlow.java core/src/main/java/com/shootoff/camera/CameraManager.java core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java
git commit -m "Move the calibration flow into core; CalibrationManager keeps the JavaFX drawing"
git log -1 --format=%B
```

Expected: the message alone, with no trailer.

---
### Task 5: Exercise host support in `plugin-api`

**Files:**
- Create: `plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java`, `SavedBackground.java`
- Modify: `javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java` (rewritten over the support, API unchanged)
- Test: `plugin-api/src/test/java/com/shootoff/exercise/host/TestExerciseHostSupport.java` (new)

**Interfaces:**
- Consumes:
  - `ExerciseExecutor`, `ExercisePaths`, `Exercise`, `ExerciseHost`, `TargetHandle`, `DelayRange`, `Cancellable` (plugin-api)
  - `TimerRow` (Task 3)
- Produces:
  - `final class com.shootoff.exercise.host.ExerciseHostSupport<T>`:
    - constants `STOP_TIMEOUT` (2 s), `DEFAULT_PAR_TIME` (2.0), `DEFAULT_DELAYED_START` (4–8)
    - `sealed interface TargetSource permits JarTarget, FileTarget, MissingTarget`, with `record JarTarget(String name, URL url)`, `record FileTarget(File file)` and `record MissingTarget(String requested)`
    - `record Stopped<T>(List<T> addedTargets, boolean restartDetection)`
    - `@FunctionalInterface interface SoundSink { void play(String name, InputStream sound, Runnable whenDone); }`
    - `ExerciseHostSupport(Exercise exercise, ClassLoader resources)`, `String exerciseName()`
    - the exercise's thread: `void start(ExerciseHost)`, `void deliverShot(Shot, Optional<Hit>)`, `void targetsChanged(Supplier<List<TargetHandle>>)`, `void reset()`, `void run(Runnable)`, `Cancellable schedule(Runnable, Duration)`, `Cancellable scheduleRepeating(Runnable, Duration, Duration)`
    - stopping: `Optional<Stopped<T>> stop()`, `boolean isStopped()`
    - targets: `boolean track(T)`, `void untrack(T)`
    - detection: `void pauseShotDetection(boolean paused, Consumer<Boolean> detecting)`
    - names: `TargetSource findTarget(String)`, `Optional<InputStream> openSound(String)`, `void playSound(String, SoundSink)`, `void playSounds(List<String>, SoundSink)`, `Optional<InputStream> openImage(String name) throws IOException`, `Optional<InputStream> resource(String)`, `Optional<URL> findResource(String)`, `static InputStream open(URL) throws IOException`, `Path dataDirectory()`
    - timer rows: `static Shot noShot(long timeMillis)`, `static TimerRow timerRow(long timeMillis, Optional<Shot> previous)`
    - shared settings: `double parTime()`, `void setParTime(double)`, `void onParTimeChanged(DoubleConsumer)`, `void userChangedParTime(double)`, `DelayRange delayedStart()`, `void setDelayedStart(DelayRange)`, `void onDelayedStartChanged(Consumer<DelayRange>)`, `void userChangedDelayedStart(int min, int max)`
  - `final class SavedBackground<B>`: `void beforeChange(Supplier<Optional<B>> current)`, `void restore(Consumer<Optional<B>> setBackground)`

  Task 6's contract checks the result through `JavaFxExerciseHost`. Plan 5's `ComposeExerciseHost` builds on the same support.

**What moves.** From `JavaFxExerciseHost`:
- the executor and every callback submission
- `stop()`'s first half: `ExerciseExecutor.shutdown(exercise::stop, 2 s)`, then snapshotting and clearing the added targets and the paused flag
- `addedTargets`, `stopped` and `detectionPaused` with their lock
- `findResource`, `open`, `openImage`, `openSound`, `playInOrder`, `resource`, `dataDirectory` and the target lookup in `loadTarget`
- the no-shot row's shot
- `parTime`, `delayedStart`, the listener lists, and the logic of the timing controls' listener
- `changedBackground`/`previousBackground`

What stays JavaFX: the scene, the nodes it tracks for the teardown, `fx(...)`, the synchronous teardown on the JavaFX thread, and the arena's add-by-file for jar targets with the tab copy hidden alongside.

- [ ] **Step 1: Write the failing test**

`plugin-api/src/test/java/com/shootoff/exercise/host/TestExerciseHostSupport.java`. It uses a temporary ShootOFF home and a temporary "jar" folder. The jar has its own `sounds/beep.wav` (byte 9) and a `targets/IPSC.target` that shadows the home's:

```java
package com.shootoff.exercise.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.host.ExerciseHostSupport.FileTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.JarTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.MissingTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.Stopped;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.shots.TimerRow;
import com.shootoff.targets.model.Hit;

class TestExerciseHostSupport {
	@TempDir Path home;
	@TempDir Path jar;
	private String previousHome;
	private URLClassLoader resources;
	private final List<String> events = new CopyOnWriteArrayList<>();
	private final List<String> threads = new CopyOnWriteArrayList<>();
	private final AtomicInteger stops = new AtomicInteger();
	private ExerciseHostSupport<String> support;

	private final class Drill implements Exercise {
		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Support drill", "1.0", "ShootOFF tests", "Records its callbacks");
		}

		@Override
		public void start(ExerciseHost host) {
			record("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			record("shot");
		}

		@Override
		public void onReset() {
			record("reset");
		}

		@Override
		public void stop() {
			stops.incrementAndGet();
			record("stop");
		}
	}

	private void record(String event) {
		threads.add(Thread.currentThread().getName());
		events.add(event);
	}

	private static void write(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static byte[] read(Optional<InputStream> stream) throws IOException {
		try (InputStream in = stream.orElseThrow(() -> new AssertionError("nothing opened"))) {
			return in.readAllBytes();
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", home.toString());

		// ShootOFF's folder
		write(home.resolve("targets/IPSC.target"), "<target/>".getBytes());
		write(home.resolve("sounds/beep.wav"), new byte[] { 1 });
		write(home.resolve("sounds/chime.wav"), new byte[] { 2 });

		// The exercise's jar: its own beep, a target of its own, and one that shadows ShootOFF's
		write(jar.resolve("sounds/beep.wav"), new byte[] { 9 });
		write(jar.resolve("targets/box.target"), "<target/>".getBytes());
		write(jar.resolve("targets/IPSC.target"), "<target/>".getBytes());
		write(jar.resolve("backgrounds/black.png"), new byte[] { 7 });
		resources = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null);

		support = new ExerciseHostSupport<>(new Drill(), resources);
	}

	@AfterEach
	void tearDown() throws IOException {
		support.stop();
		resources.close();
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	// Waits for everything queued on the exercise's thread so far
	private void sync() throws InterruptedException {
		final CountDownLatch done = new CountDownLatch(1);
		support.run(done::countDown);
		assertTrue(done.await(5, TimeUnit.SECONDS));
	}

	@Test
	void targetsComeFromTheExercisesJarFirstThenShootoffsFolder() throws IOException {
		assertEquals(new JarTarget("targets/box.target", jar.resolve("targets/box.target").toUri().toURL()),
				support.findTarget("@targets/box.target"));
		// The jar's copy shadows ShootOFF's
		assertEquals("targets/IPSC.target", ((JarTarget) support.findTarget("/targets/IPSC.target")).name());
		// A bare name is looked for in the targets folder
		assertEquals(new FileTarget(home.resolve("targets/IPSC.target").toFile()), support.findTarget("IPSC.target"));
		assertEquals(new MissingTarget("targets/no_such.target"), support.findTarget("targets/no_such.target"));
		// "@" names a jar resource, never a file
		assertEquals(new MissingTarget("@IPSC.target"), support.findTarget("@IPSC.target"));
	}

	@Test
	void soundsComeFromTheExercisesJarFirstThenShootoffsSoundsFolder() throws IOException {
		assertArrayEquals(new byte[] { 9 }, read(support.openSound("sounds/beep.wav")));
		assertArrayEquals(new byte[] { 2 }, read(support.openSound("chime.wav")));
		assertEquals(Optional.empty(), support.openSound("sounds/no_such.wav"));

		final List<String> played = new ArrayList<>();
		support.playSounds(List.of("sounds/beep.wav", "no_such.wav", "chime.wav"), (name, sound, whenDone) -> {
			played.add(name);
			whenDone.run();
		});
		assertEquals(List.of("sounds/beep.wav", "chime.wav"), played);
	}

	@Test
	void resourcesComeOnlyFromTheExercisesJarAndImagesAlsoFromShootoff() throws IOException {
		assertArrayEquals(new byte[] { 9 }, read(support.resource("/sounds/beep.wav")));
		assertEquals(Optional.empty(), support.resource("sounds/chime.wav"));
		assertArrayEquals(new byte[] { 7 }, read(support.openImage("backgrounds/black.png")));
		// A resource on ShootOFF's own class path, as the bundled arena backgrounds are
		assertTrue(support.openImage("com/shootoff/exercise/Exercise.class").isPresent());
		assertEquals(Optional.empty(), support.openImage("backgrounds/no_such.png"));
	}

	@Test
	void stopRunsTheExercisesStopOnceAndHandsBackWhatItLeft() throws Exception {
		support.start(null);
		support.track("IPSC");
		support.track("box");
		support.untrack("box");
		support.pauseShotDetection(true, detecting -> events.add("detecting " + detecting));

		final List<Optional<Stopped<String>>> results = new CopyOnWriteArrayList<>();
		final Thread other = new Thread(() -> results.add(support.stop()));
		other.start();
		results.add(support.stop());
		other.join();

		assertEquals(1, stops.get());
		assertEquals(1, results.stream().filter(Optional::isPresent).count());
		assertEquals(new Stopped<>(List.of("IPSC"), true),
				results.stream().flatMap(Optional::stream).findFirst().get());
		assertEquals(List.of("start", "detecting false", "stop"), events);
		assertTrue(support.isStopped());

		// Later calls change nothing
		assertFalse(support.track("late"));
		support.pauseShotDetection(false, detecting -> events.add("detecting " + detecting));
		support.schedule(() -> events.add("late task"), Duration.ZERO);
		support.run(() -> events.add("late callback"));
		assertEquals(Optional.empty(), support.openSound("chime.wav"));
		Thread.sleep(100);
		assertEquals(List.of("start", "detecting false", "stop"), events);
	}

	@Test
	void anExerciseThatNeverPausedDetectionLeavesItAlone() {
		support.track("IPSC");

		assertEquals(Optional.of(new Stopped<>(List.of("IPSC"), false)), support.stop());
	}

	@Test
	void callbacksRunOnTheExercisesThreadInOrder() throws Exception {
		support.start(null);
		support.deliverShot(new Shot(ShotColor.RED, 1, 2, 3), Optional.empty());
		support.targetsChanged(List::of);
		support.reset();
		support.schedule(() -> record("scheduled"), Duration.ofMillis(10));
		support.run(() -> record("button"));
		sync();
		Thread.sleep(50);

		assertEquals(List.of("start", "shot", "reset", "button", "scheduled"), events);
		assertEquals(List.of("Exercise: Support drill"), threads.stream().distinct().toList());
	}

	@Test
	void aTimerRowReadsLikeARedShotsRow() {
		final Shot previous = new Shot(ShotColor.GREEN, 5, 5, 1000);

		final TimerRow row = ExerciseHostSupport.timerRow(2500, Optional.of(previous));

		assertEquals(String.format("%.2f", 2.5f), row.time());
		assertEquals(String.format("%.2f", 1.5f), row.split());
		assertEquals("red", row.laser());
		assertEquals(-10, row.shot().getX(), 0);
		assertEquals(-10, row.shot().getY(), 0);
		assertEquals("-", ExerciseHostSupport.timerRow(2500, Optional.empty()).split());
	}

	@Test
	void listenersHearOnlyTheUsersChangesOnTheExercisesThread() throws Exception {
		final List<String> heard = new CopyOnWriteArrayList<>();
		support.onParTimeChanged(seconds -> {
			record("par");
			heard.add("par " + seconds);
		});
		support.onDelayedStartChanged(range -> {
			record("delay");
			heard.add("delay " + range.minSeconds() + "-" + range.maxSeconds());
		});

		// The exercise's own values
		support.setParTime(4.0);
		support.setDelayedStart(new DelayRange(5, 8));
		assertEquals(4.0, support.parTime(), 0);
		assertEquals(new DelayRange(5, 8), support.delayedStart());

		// The user's: unchanged values and a minimum above the maximum are ignored
		support.userChangedParTime(4.0);
		support.userChangedParTime(3.5);
		support.userChangedDelayedStart(5, 8);
		support.userChangedDelayedStart(9, 6);
		support.userChangedDelayedStart(2, 6);
		sync();

		assertEquals(List.of("par 3.5", "delay 2-6"), heard);
		assertEquals(3.5, support.parTime(), 0);
		assertEquals(new DelayRange(2, 6), support.delayedStart());
		assertEquals(List.of("Exercise: Support drill"), threads.stream().distinct().toList());
	}

	@Test
	void theDefaultsAreTheSharedControlsDefaults() {
		assertEquals(2.0, support.parTime(), 0);
		assertEquals(new DelayRange(4, 8), support.delayedStart());
	}

	@Test
	void aBackgroundIsRestoredToTheOneFromBeforeTheFirstChange() {
		final SavedBackground<String> saved = new SavedBackground<>();
		final List<Optional<String>> restored = new ArrayList<>();

		saved.restore(restored::add);
		assertEquals(List.of(), restored);

		saved.beforeChange(() -> Optional.of("indoor range"));
		saved.beforeChange(() -> Optional.of("the exercise's first background"));
		saved.restore(restored::add);
		assertEquals(List.of(Optional.of("indoor range")), restored);
	}

	@Test
	void theDataDirectoryIsPerExerciseInTheShootoffHome() {
		final Path directory = support.dataDirectory();

		assertEquals(home.resolve("exercise-data").resolve(Drill.class.getName()), directory);
		assertTrue(Files.isDirectory(directory));
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plugin-api:test --tests 'com.shootoff.exercise.host.TestExerciseHostSupport' --console=plain`
Expected: FAIL at compile time: `ExerciseHostSupport` and `SavedBackground` are missing (`cannot find symbol` or `does not exist`).

- [ ] **Step 3: Write the support classes**

`plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java` (GPL header, then):

```java
package com.shootoff.exercise.host;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseExecutor;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.ExercisePaths;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.shots.TimerRow;
import com.shootoff.targets.model.Hit;

/**
 * The part of an {@link ExerciseHost} that is the same in every user interface:
 * <ul>
 * <li>the exercise's thread, and every callback on it</li>
 * <li>stopping: the exercise's <tt>stop()</tt> exactly once, then handing back the targets it added and
 * whether it left shot detection paused</li>
 * <li>names: a target, sound, image or resource is looked up in the exercise's own jar first, then in
 * ShootOFF's folder</li>
 * <li>the shot timer row an exercise adds without a shot</li>
 * <li>the shared par time and start delay, and the exercise's listeners for the user's changes</li>
 * </ul>
 * The user interface keeps its own drawing and controls, and removes them when {@link #stop()} says so.
 *
 * @param <T>
 *            the user interface's target type
 */
public final class ExerciseHostSupport<T> {
	private static final Logger logger = LoggerFactory.getLogger(ExerciseHostSupport.class);

	public static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);
	// What the shared timing controls show until an exercise sets its own values, as for v1 exercises
	public static final double DEFAULT_PAR_TIME = 2.0;
	public static final DelayRange DEFAULT_DELAYED_START = new DelayRange(4, 8);

	/**
	 * Where a target an exercise names comes from.
	 */
	public sealed interface TargetSource permits JarTarget, FileTarget, MissingTarget {}

	/**
	 * A target in the exercise's jar.
	 *
	 * @param name
	 *            its resource name in the jar, without a leading <tt>@</tt> or <tt>/</tt>
	 */
	public record JarTarget(String name, URL url) implements TargetSource {}

	/**
	 * A target file in ShootOFF's folder.
	 */
	public record FileTarget(File file) implements TargetSource {}

	/**
	 * A target that is in neither place.
	 */
	public record MissingTarget(String requested) implements TargetSource {}

	/**
	 * What {@link #stop()} hands back for the user interface to undo.
	 *
	 * @param addedTargets
	 *            the targets the exercise added and hasn't removed, in the order it added them
	 * @param restartDetection
	 *            <tt>true</tt> if the exercise left shot detection paused
	 */
	public record Stopped<T>(List<T> addedTargets, boolean restartDetection) {}

	/**
	 * Plays a sound and closes it, then runs <tt>whenDone</tt>.
	 */
	@FunctionalInterface
	public interface SoundSink {
		void play(String name, InputStream sound, Runnable whenDone);
	}

	private final Exercise exercise;
	private final ClassLoader resources;
	private final ExerciseExecutor executor;

	// Guarded by this
	private final List<T> addedTargets = new ArrayList<>();
	private boolean stopped = false;
	private boolean detectionPaused = false;

	private volatile double parTime = DEFAULT_PAR_TIME;
	private volatile DelayRange delayedStart = DEFAULT_DELAYED_START;
	private final List<DoubleConsumer> parListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<DelayRange>> delayListeners = new CopyOnWriteArrayList<>();

	/**
	 * @param resources
	 *            the exercise's class loader; for a plugin, its jar's
	 */
	public ExerciseHostSupport(Exercise exercise, ClassLoader resources) {
		this.exercise = exercise;
		this.resources = resources;
		executor = new ExerciseExecutor(exercise.metadata().getName());
	}

	public String exerciseName() {
		return exercise.metadata().getName();
	}

	// ---- The exercise's thread

	public void start(ExerciseHost host) {
		executor.execute(() -> exercise.start(host));
	}

	public void deliverShot(Shot shot, Optional<Hit> hit) {
		executor.execute(() -> exercise.onShot(shot, hit));
	}

	/**
	 * @param targets
	 *            the surface's targets, read on the exercise's thread
	 */
	public void targetsChanged(Supplier<List<TargetHandle>> targets) {
		executor.execute(() -> exercise.onTargetsChanged(targets.get()));
	}

	public void reset() {
		executor.execute(exercise::onReset);
	}

	/**
	 * Runs a callback of the exercise's (a button, a setting) on its thread.
	 */
	public void run(Runnable callback) {
		executor.execute(callback);
	}

	public Cancellable schedule(Runnable task, Duration delay) {
		return executor.schedule(task, delay);
	}

	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return executor.scheduleRepeating(task, initialDelay, period);
	}

	// ---- Stopping

	/**
	 * Stops the exercise: the callbacks already queued run, then its <tt>stop()</tt>, and every
	 * scheduled task is cancelled. Waits up to two seconds for the exercise's thread, which never waits
	 * for a user interface's thread, so a user interface may call this on its own thread. Only the first
	 * call does anything.
	 *
	 * @return what the user interface must undo; empty if the exercise had already stopped
	 */
	public Optional<Stopped<T>> stop() {
		synchronized (this) {
			if (stopped) return Optional.empty();
			stopped = true;
		}

		executor.shutdown(exercise::stop, STOP_TIMEOUT);

		synchronized (this) {
			final Stopped<T> undo = new Stopped<>(List.copyOf(addedTargets), detectionPaused);
			addedTargets.clear();
			return Optional.of(undo);
		}
	}

	public synchronized boolean isStopped() {
		return stopped;
	}

	// ---- Targets

	/**
	 * Remembers a target the exercise added, to remove it when the exercise stops.
	 *
	 * @return <tt>false</tt> if the exercise has stopped meanwhile: the user interface removes the
	 *         target again
	 */
	public synchronized boolean track(T target) {
		if (stopped) return false;

		addedTargets.add(target);
		return true;
	}

	/**
	 * Forgets a target the exercise removed.
	 */
	public synchronized void untrack(T target) {
		addedTargets.remove(target);
	}

	// ---- Shot detection

	/**
	 * Pauses or resumes shot detection for the exercise, unless it has stopped.
	 *
	 * @param detecting
	 *            turns every camera's detection on or off
	 */
	public synchronized void pauseShotDetection(boolean paused, Consumer<Boolean> detecting) {
		if (stopped) return;

		detectionPaused = paused;
		detecting.accept(!paused);
	}

	// ---- Names

	/**
	 * @return where <tt>targetFile</tt> is: in the exercise's jar, else in ShootOFF's folder (or its
	 *         <tt>targets/</tt> folder)
	 */
	public TargetSource findTarget(String targetFile) {
		final String name = ExercisePaths.resourceName(targetFile);
		final Optional<URL> resource = findResource(name);
		if (resource.isPresent()) return new JarTarget(name, resource.get());

		final Optional<File> file = ExercisePaths.shootoffFile(targetFile, "targets");
		if (file.isPresent()) return new FileTarget(file.get());

		return new MissingTarget(targetFile);
	}

	/**
	 * Opens a sound: from the exercise's jar, else from ShootOFF's folder or its <tt>sounds/</tt> folder.
	 *
	 * @return empty if it can't be found or read, or the exercise has stopped
	 */
	public Optional<InputStream> openSound(String resourceOrFile) {
		if (isStopped()) return Optional.empty();

		try {
			final Optional<URL> resource = findResource(ExercisePaths.resourceName(resourceOrFile));
			if (resource.isPresent()) return Optional.of(open(resource.get()));

			final Optional<File> file = ExercisePaths.shootoffFile(resourceOrFile, "sounds");
			if (file.isPresent()) return Optional.of(new BufferedInputStream(Files.newInputStream(file.get().toPath())));
		} catch (final IOException e) {
			logger.error("Can't open sound {}", resourceOrFile, e);
			return Optional.empty();
		}

		logger.error("Can't find sound {}", resourceOrFile);
		return Optional.empty();
	}

	public void playSound(String resourceOrFile, SoundSink sink) {
		openSound(resourceOrFile).ifPresent(sound -> sink.play(resourceOrFile, sound, () -> {}));
	}

	/**
	 * Plays the sounds one after another, skipping any that can't be found, until the exercise stops.
	 */
	public void playSounds(List<String> resourcesOrFiles, SoundSink sink) {
		playInOrder(List.copyOf(resourcesOrFiles), 0, sink);
	}

	private void playInOrder(List<String> sounds, int index, SoundSink sink) {
		if (index >= sounds.size() || isStopped()) return;

		final Optional<InputStream> sound = openSound(sounds.get(index));
		if (sound.isPresent()) {
			sink.play(sounds.get(index), sound.get(), () -> playInOrder(sounds, index + 1, sink));
		} else {
			playInOrder(sounds, index + 1, sink);
		}
	}

	/**
	 * Opens an image, for example a background: from the exercise's jar, else from ShootOFF's own
	 * resources (e.g. <tt>arena/backgrounds/...</tt>).
	 *
	 * @param name
	 *            a resource name (see {@link ExercisePaths#resourceName})
	 * @return empty if it is in neither place
	 */
	public Optional<InputStream> openImage(String name) throws IOException {
		final Optional<URL> resource = findResource(name);
		if (resource.isPresent()) return Optional.of(open(resource.get()));

		return Optional.ofNullable(ExerciseHostSupport.class.getResourceAsStream("/" + name));
	}

	/**
	 * @return a resource from the exercise's own jar
	 */
	public Optional<InputStream> resource(String path) {
		final Optional<URL> resource = findResource(ExercisePaths.resourceName(path));
		if (resource.isEmpty()) return Optional.empty();

		try {
			return Optional.of(open(resource.get()));
		} catch (final IOException e) {
			logger.error("Can't open resource {}", path, e);
			return Optional.empty();
		}
	}

	/**
	 * @return a resource in the exercise's own jar only: its class loader's <tt>getResource</tt> would
	 *         search ShootOFF's classpath first
	 */
	public Optional<URL> findResource(String name) {
		if (resources == null || name.isEmpty()) return Optional.empty();

		return Optional
				.ofNullable(resources instanceof URLClassLoader jar ? jar.findResource(name) : resources.getResource(name));
	}

	/**
	 * Opens a resource uncached, so the exercise's jar isn't held open and can be replaced while ShootOFF
	 * runs.
	 */
	public static InputStream open(URL url) throws IOException {
		final URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		return new BufferedInputStream(connection.getInputStream());
	}

	/**
	 * @return <tt>&lt;shootoff.home&gt;/exercise-data/&lt;exercise class&gt;/</tt>, created if needed
	 */
	public Path dataDirectory() {
		final Path directory = Paths.get(System.getProperty("shootoff.home", System.getProperty("user.dir")),
				"exercise-data", exercise.getClass().getName());

		try {
			return Files.createDirectories(directory);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---- Timer rows

	/**
	 * @return the shot a row that no shot made stands for: v1's drill injected a red shot at (-10, -10)
	 *         at the row's time, so the row reads Laser "red"
	 */
	public static Shot noShot(long timeMillis) {
		return new Shot(ShotColor.RED, -10, -10, timeMillis);
	}

	/**
	 * @param previous
	 *            the shot of the timer's latest row, if any
	 * @return the row {@link ExerciseHost#addTimerRow} adds: it reads like a red shot's row at
	 *         <tt>timeMillis</tt>
	 */
	public static TimerRow timerRow(long timeMillis, Optional<Shot> previous) {
		return TimerRow.of(noShot(timeMillis), previous, false, false);
	}

	// ---- The shared par time and start delay

	public double parTime() {
		return parTime;
	}

	/**
	 * Sets the par time, as the exercise does: its own listeners don't hear it.
	 */
	public void setParTime(double seconds) {
		parTime = seconds;
	}

	public void onParTimeChanged(DoubleConsumer listener) {
		parListeners.add(listener);
	}

	/**
	 * The user set the par time: the exercise's listeners hear it on its thread, unless it didn't change.
	 */
	public void userChangedParTime(double seconds) {
		if (Double.compare(seconds, parTime) == 0) return;

		parTime = seconds;
		for (final DoubleConsumer listener : parListeners) {
			executor.execute(() -> listener.accept(seconds));
		}
	}

	public DelayRange delayedStart() {
		return delayedStart;
	}

	/**
	 * Sets the start delay, as the exercise does: its own listeners don't hear it.
	 */
	public void setDelayedStart(DelayRange range) {
		delayedStart = range;
	}

	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		delayListeners.add(listener);
	}

	/**
	 * The user set the start delay: the exercise's listeners hear it on its thread, unless it didn't
	 * change or the minimum is above the maximum.
	 */
	public void userChangedDelayedStart(int minSeconds, int maxSeconds) {
		if (minSeconds > maxSeconds) return;

		final DelayRange range = new DelayRange(minSeconds, maxSeconds);
		if (range.equals(delayedStart)) return;

		delayedStart = range;
		for (final Consumer<DelayRange> listener : delayListeners) {
			executor.execute(() -> listener.accept(range));
		}
	}
}
```

`plugin-api/src/main/java/com/shootoff/exercise/host/SavedBackground.java` (GPL header, then):

```java
package com.shootoff.exercise.host;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The arena background from before an exercise first changed it, to put back when the exercise stops.
 * Use it on the user interface's thread.
 *
 * @param <B>
 *            the user interface's background type
 */
public final class SavedBackground<B> {
	private boolean changed = false;
	private Optional<B> previous = Optional.empty();

	/**
	 * Call before each change: the first time, remembers the current background.
	 */
	public void beforeChange(Supplier<Optional<B>> current) {
		if (changed) return;

		previous = current.get();
		changed = true;
	}

	/**
	 * Puts back the background from before the first change (empty for none), if there was a change.
	 */
	public void restore(Consumer<Optional<B>> setBackground) {
		if (changed) setBackground.accept(previous);
	}
}
```

- [ ] **Step 4: Run the plugin-api tests to verify they pass**

Run: `./gradlew :plugin-api:test --console=plain && command grep -ho 'tests="[0-9]*" skipped="0" failures="0" errors="0"' plugin-api/build/test-results/test/TEST-com.shootoff.exercise.host.TestExerciseHostSupport.xml`
Expected: `BUILD SUCCESSFUL` (with `TestNoJavaFxInPluginApi` still green), then `tests="11" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Rewrite `JavaFxExerciseHost` over the support**

Replace `javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java` below its GPL header (lines 1–17 stay) with the code below.
- The JavaFX half is the old code, line for line: text, messages, buttons, settings, columns, row styles, markers, `clearShots`, the teardown, and the timing controls.
- `loadTarget` keeps the `bc444821` rule: on a `MirroredCanvasManager`, a jar target is added by file, and `setVisible` hides the tab's copy too.
- `stop()` keeps the `521e9bcb` rule: the teardown runs at once when called on the JavaFX thread.

```java
package com.shootoff.gui.exercise;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseExecutor;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.ExercisePaths;
import com.shootoff.exercise.RowStyle;
import com.shootoff.exercise.ShotMarkerHandle;
import com.shootoff.exercise.ShotStyle;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.exercise.TextHandle;
import com.shootoff.exercise.TextStyle;
import com.shootoff.exercise.host.ExerciseHostSupport;
import com.shootoff.exercise.host.ExerciseHostSupport.FileTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.JarTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.MissingTarget;
import com.shootoff.exercise.host.ExerciseHostSupport.Stopped;
import com.shootoff.exercise.host.SavedBackground;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.MirroredCanvasManager;
import com.shootoff.gui.ParListener;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.MirroredTarget;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Target;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetId;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.text.Font;

/**
 * Runs one v2 {@link Exercise} in the JavaFX app, on the projector arena or on a camera feed. What
 * every user interface's host shares (the exercise's thread, stopping, names, timer rows, the shared
 * par time and start delay) is plugin-api's {@link ExerciseHostSupport}; this draws the exercise on the
 * JavaFX scene.
 * <ul>
 * <li>Every exercise callback (start, shots, target changes, resets, stop, scheduled tasks, buttons,
 * settings and listeners) runs on the exercise's own thread, an {@link ExerciseExecutor}.</li>
 * <li>Host methods may be called from any thread. Changes to the scene are queued onto the JavaFX
 * thread in call order. No host method waits for the JavaFX thread, so {@link #stop()} may wait for
 * the exercise thread while running on the JavaFX thread.</li>
 * <li>{@link #stop()} stops the exercise, cancels its scheduled tasks and removes everything it added.
 * Calls the exercise makes afterwards change nothing.</li>
 * </ul>
 */
public final class JavaFxExerciseHost implements ExerciseHost {
	private static final Logger logger = LoggerFactory.getLogger(JavaFxExerciseHost.class);

	private static final int COLUMN_WIDTH = 60;

	private final ExerciseHostContext context;
	private final ExerciseHostSupport<TargetView> support;

	// JavaFX thread only
	private final List<Node> canvasNodes = new ArrayList<>();
	private final List<Node> markers = new ArrayList<>();
	private final Map<CanvasManager, Label> feedLabels = new LinkedHashMap<>();
	private final List<Node> panes = new ArrayList<>();
	private final List<Button> buttons = new ArrayList<>();
	private final List<TableColumn<ShotEntry, String>> columns = new ArrayList<>();
	private TimingControlsPane timingControls = null;
	private final SavedBackground<LocatedImage> savedBackground = new SavedBackground<>();
	private boolean tornDown = false;

	// The shared timing controls' listener; it runs on the JavaFX thread
	private final ParListener timingListener = new ParListener() {
		@Override
		public void updatedDelayedStartInterval(int min, int max) {
			support.userChangedDelayedStart(min, max);
		}

		@Override
		public void updatedParInterval(double seconds) {
			support.userChangedParTime(seconds);
		}
	};

	public JavaFxExerciseHost(Exercise exercise, ExerciseHostContext context) {
		this.context = context;
		support = new ExerciseHostSupport<>(exercise, context.resources());
	}

	// ---- Lifecycle, driven by HostedExercise

	public void start() {
		support.start(this);
	}

	/**
	 * Hands a shot to the exercise: arena shots to a projector exercise, camera shots to a camera
	 * exercise.
	 */
	public void deliverShot(Shot shot, Optional<Hit> hit) {
		if (isProjector() != (shot instanceof ArenaShot)) return;

		support.deliverShot(shot, hit);
	}

	public void targetsChanged() {
		support.targetsChanged(this::targets);
	}

	public void reset() {
		support.reset();
	}

	/**
	 * Stops the exercise and removes everything it added. Waits up to two seconds for the exercise
	 * thread, which never waits for the JavaFX thread, so this may run on the JavaFX thread.
	 */
	public void stop() {
		final Optional<Stopped<TargetView>> stopped = support.stop();
		if (stopped.isEmpty()) return;

		for (final TargetView target : stopped.get().addedTargets()) {
			context.canvas().removeTarget(target);
		}

		if (stopped.get().restartDetection()) context.cameras().setDetectingAll(true);

		// Queued after every scene change the exercise made, so all of them are undone. When
		// already on the FX thread, run it now instead: a caller that stops an exercise and then
		// touches the scene itself in the same FX call (for example enabling calibration) must see
		// the teardown, including the background restore, as already done.
		if (Platform.isFxApplicationThread()) {
			tearDown();
		} else {
			Platform.runLater(this::tearDown);
		}
	}

	private void tearDown() {
		tornDown = true;

		context.canvas().getCanvasGroup().getChildren().removeAll(canvasNodes);
		canvasNodes.clear();
		markers.clear();

		for (final Map.Entry<CanvasManager, Label> feed : feedLabels.entrySet()) {
			feed.getKey().removeChild(feed.getValue());
		}
		feedLabels.clear();

		context.view().getTrainingExerciseContainer().getChildren().removeAll(panes);
		panes.clear();
		timingControls = null;

		context.view().getButtonsPane().getChildren().removeAll(buttons);
		buttons.clear();

		context.view().getShotEntryTable().getColumns().removeAll(columns);
		columns.clear();

		savedBackground.restore(previous -> context.arena().get().setArenaBackground(previous.orElse(null)));
	}

	// Queues a scene change. Changes queued after the tear down are dropped.
	private void fx(Runnable change) {
		Platform.runLater(() -> {
			if (!tornDown) change.run();
		});
	}

	private ObservableList<Node> canvasChildren() {
		return context.canvas().getCanvasGroup().getChildren();
	}

	// ---- Surface

	@Override
	public Size surfaceSize() {
		if (context.arena().isPresent()) {
			final ProjectorArenaPane arena = context.arena().get();
			return new Size(arena.getWidth(), arena.getHeight());
		}

		return new Size(context.config().getDisplayWidth(), context.config().getDisplayHeight());
	}

	@Override
	public boolean isProjector() {
		return context.arena().isPresent();
	}

	@Override
	public void setBackground(String imageResource) {
		if (context.arena().isEmpty()) {
			logger.warn("{} set a background, but only the projector arena has one", support.exerciseName());
			return;
		}

		final String name = ExercisePaths.resourceName(imageResource);
		final LocatedImage background;
		try {
			final Optional<InputStream> image = support.openImage(name);
			if (image.isEmpty()) {
				logger.error("Can't find background {}", imageResource);
				return;
			}
			try (InputStream in = image.get()) {
				background = new LocatedImage(in, "/" + name);
			}
		} catch (final IOException e) {
			logger.error("Can't read background {}", imageResource, e);
			return;
		}

		fx(() -> {
			final ProjectorArenaPane arena = context.arena().get();
			savedBackground.beforeChange(arena::getArenaBackground);
			arena.setArenaBackground(background);
		});
	}

	// ---- Targets

	@Override
	public Optional<TargetHandle> addTarget(String targetFile, double x, double y) {
		if (support.isStopped()) return Optional.empty();

		final Optional<Target> added = loadTarget(targetFile);
		if (added.isEmpty()) return Optional.empty();

		final TargetView target = (TargetView) added.get();
		target.setPosition(x, y);

		final Optional<ProjectorArenaPane> arena = context.arena();
		if (arena.isPresent() && arena.get().getPerspectiveManager().isPresent()
				&& arena.get().getPerspectiveManager().get().isInitialized()) {
			arena.get().resizeTargetToDefaultPerspective(target);
		}

		if (support.track(target)) return Optional.of(new FxTargetHandle(target));

		// Stopped while loading
		context.canvas().removeTarget(target);
		return Optional.empty();
	}

	private Optional<Target> loadTarget(String targetFile) {
		return switch (support.findTarget(targetFile)) {
		case JarTarget jar -> loadJarTarget(jar);
		case FileTarget file -> context.canvas().addTarget(file.file(), false);
		case MissingTarget missing -> {
			logger.error("Can't find target {}", missing.requested());
			yield Optional.empty();
		}
		};
	}

	private Optional<Target> loadJarTarget(JarTarget jar) {
		// The projector arena's canvas mirrors each target on the arena tab's. Add it as v1's
		// ProjectorTrainingExerciseBase does, by file: that returns this canvas's copy, the one the
		// projector shows and removeTarget expects. Adding the components here instead returns the
		// tab's copy, which the exercise could neither hide on the projector nor remove.
		if (context.canvas() instanceof MirroredCanvasManager) {
			return context.canvas().addTarget(new File("@" + jar.name()), false);
		}

		try (InputStream in = ExerciseHostSupport.open(jar.url())) {
			return TargetIO.loadTarget(in, false, context.resources()).map(
					components -> context.canvas().addTarget(components.withTargetFile(new File("@" + jar.name())), true));
		} catch (final IOException e) {
			logger.error("Can't read target {} from the exercise", jar.name(), e);
			return Optional.empty();
		}
	}

	@Override
	public List<TargetHandle> targets() {
		final List<TargetHandle> handles = new ArrayList<>();
		for (final Target target : new ArrayList<>(context.canvas().getTargets())) {
			handles.add(new FxTargetHandle((TargetView) target));
		}
		return handles;
	}

	private final class FxTargetHandle implements TargetHandle {
		private final TargetView view;

		FxTargetHandle(TargetView view) {
			this.view = view;
		}

		@Override
		public TargetId id() {
			return view.getPlacedTarget().getId();
		}

		@Override
		public void move(double x, double y) {
			view.setPosition(x, y);
		}

		@Override
		public void resize(double width, double height) {
			view.setDimensions(width, height);
		}

		@Override
		public void setVisible(boolean visible) {
			view.setVisible(visible);
			// MirroredTarget mirrors placement but not visibility: hide the arena tab's copy too
			if (view instanceof MirroredTarget mirrored && mirrored.getMirroredTarget() != null) {
				mirrored.getMirroredTarget().setVisible(visible);
			}
		}

		@Override
		public void remove() {
			support.untrack(view);
			context.canvas().removeTarget(view);
		}

		@Override
		public Point position() {
			return view.getPlacedTarget().getPosition();
		}

		@Override
		public Size size() {
			return view.getPlacedTarget().getSize();
		}

		@Override
		public TargetDefinition definition() {
			return view.getComponents().getDefinition();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof FxTargetHandle other && other.view == view;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(view);
		}
	}

	// ---- Text

	@Override
	public TextHandle showText(String text, double x, double y, TextStyle style) {
		final Label label = new Label(text);
		label.setFont(Font.font(style.fontSize()));
		label.setTextFill(Color.web(style.textColor()));
		label.setBackground(
				new Background(new BackgroundFill(Color.web(style.backgroundColor()), CornerRadii.EMPTY, Insets.EMPTY)));

		fx(() -> {
			label.setLayoutX(x);
			label.setLayoutY(y);
			canvasChildren().add(label);
			canvasNodes.add(label);
		});

		return new TextHandle() {
			@Override
			public void setText(String newText) {
				fx(() -> label.setText(newText));
			}

			@Override
			public void move(double newX, double newY) {
				fx(() -> {
					label.setLayoutX(newX);
					label.setLayoutY(newY);
				});
			}

			@Override
			public void remove() {
				fx(() -> {
					canvasChildren().remove(label);
					canvasNodes.remove(label);
				});
			}
		};
	}

	@Override
	public void showMessage(String message) {
		if (support.isStopped()) return;

		if (context.config().inDebugMode()) System.out.println(message);
		context.config().getSessionRecorder().ifPresent(recorder -> recorder.recordExerciseFeedMessage(message));

		fx(() -> {
			for (final CanvasManager feed : context.feeds()) {
				feedLabels.computeIfAbsent(feed, canvas -> {
					final Label label = new Label();
					label.setTextFill(Color.WHITE);
					canvas.addChild(label);
					return label;
				}).setText(message);
			}
		});
	}

	// ---- Controls

	@Override
	public ButtonHandle addButton(String label, Runnable onClick) {
		final Button button = new Button(label);
		button.setOnAction(event -> support.run(onClick));

		fx(() -> {
			final List<Node> pane = context.view().getButtonsPane().getChildren();
			// Sized like ShootOFF's Reset button, the pane's first
			if (!pane.isEmpty() && pane.get(0) instanceof Button reset) {
				button.setPrefSize(reset.getPrefWidth(), reset.getPrefHeight());
			}
			pane.add(button);
			buttons.add(button);
		});

		return new ButtonHandle() {
			@Override
			public void setLabel(String newLabel) {
				fx(() -> button.setText(newLabel));
			}

			@Override
			public void remove() {
				fx(() -> {
					context.view().getButtonsPane().getChildren().remove(button);
					buttons.remove(button);
				});
			}
		};
	}

	@Override
	public void addNumberSetting(String label, double initial, double min, double max, double step,
			DoubleConsumer onChange) {
		fx(() -> {
			final Spinner<Double> spinner = new Spinner<>(min, max, initial, step);
			spinner.setEditable(true);
			spinner.valueProperty().addListener((observable, oldValue, newValue) -> {
				if (newValue != null) support.run(() -> onChange.accept(newValue));
			});

			final HBox pane = new HBox(10, new Label(label), spinner);
			pane.setAlignment(Pos.CENTER_LEFT);
			context.view().getTrainingExerciseContainer().getChildren().add(pane);
			panes.add(pane);
		});
	}

	// ---- Shot timer

	@Override
	public void addColumn(String name) {
		fx(() -> {
			final TableColumn<ShotEntry, String> column = new TableColumn<>(name);
			column.setPrefWidth(COLUMN_WIDTH);
			column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getExerciseValue(name)));
			context.view().getShotEntryTable().getColumns().add(column);
			columns.add(column);
		});
	}

	@Override
	public void setColumnValue(String name, String value) {
		fx(() -> {
			final TableView<ShotEntry> table = context.view().getShotEntryTable();
			if (table.getItems().isEmpty()) {
				logger.warn("{} set {} on an empty shot timer", support.exerciseName(), name);
				return;
			}

			table.getItems().get(table.getItems().size() - 1).setExerciseValue(name, value);
			table.refresh();
		});
	}

	@Override
	public void styleLastRow(RowStyle style) {
		final Color color = Color.web(style.highlightColor());

		fx(() -> {
			final ObservableList<ShotEntry> rows = context.view().getShotEntryTable().getItems();
			if (rows.isEmpty()) return;

			final int last = rows.size() - 1;
			rows.set(last, rows.get(last).withRowColor(Optional.of(color)));
		});
	}

	// The row v1's drill got from a fake red shot at (-10, -10): the shot timer computes Time, Split and
	// Laser from the shot as usual, but nothing draws, hit-tests or records it
	@Override
	public void addTimerRow(long timeMillis, RowStyle style) {
		final Color color = Color.web(style.highlightColor());
		final DisplayShot noShot = new DisplayShot(ExerciseHostSupport.noShot(timeMillis),
				context.config().getMarkerRadius());

		fx(() -> {
			final ObservableList<ShotEntry> rows = context.view().getShotEntryTable().getItems();
			final Optional<Shot> previous = rows.isEmpty() ? Optional.empty()
					: Optional.of(rows.get(rows.size() - 1).getShot());
			rows.add(new ShotEntry(noShot, previous, Optional.of(color), false, false));
		});
	}

	// ---- Shots

	@Override
	public ShotMarkerHandle showShotMarker(double x, double y, ShotStyle style) {
		final int radius = context.config().getMarkerRadius();
		final Ellipse marker = new Ellipse(x, y, radius, radius);
		marker.setFill(DisplayShot.toPaint(style.color()));

		fx(() -> {
			canvasChildren().add(marker);
			canvasNodes.add(marker);
			markers.add(marker);
		});

		return () -> fx(() -> {
			canvasChildren().remove(marker);
			canvasNodes.remove(marker);
			markers.remove(marker);
		});
	}

	@Override
	public void clearShots() {
		if (support.isStopped()) return;

		context.cameras().clearShots();
		fx(() -> {
			canvasChildren().removeAll(markers);
			canvasNodes.removeAll(markers);
			markers.clear();
		});
	}

	@Override
	public void pauseShotDetection(boolean paused) {
		support.pauseShotDetection(paused, context.cameras()::setDetectingAll);
	}

	// ---- Sound

	@Override
	public void playSound(String resourceOrFile) {
		support.playSound(resourceOrFile, context.sounds()::play);
	}

	@Override
	public void playSounds(List<String> resourcesOrFiles) {
		support.playSounds(resourcesOrFiles, context.sounds()::play);
	}

	@Override
	public void say(String text) {
		if (!support.isStopped()) context.sounds().say(text);
	}

	// ---- Time

	@Override
	public long currentTimeMillis() {
		return System.currentTimeMillis();
	}

	@Override
	public Cancellable schedule(Runnable task, Duration delay) {
		return support.schedule(task, delay);
	}

	@Override
	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return support.scheduleRepeating(task, initialDelay, period);
	}

	// ---- Resources

	@Override
	public Optional<InputStream> resource(String path) {
		return support.resource(path);
	}

	@Override
	public Path dataDirectory() {
		return support.dataDirectory();
	}

	// ---- Shared settings

	@Override
	public double parTime() {
		return support.parTime();
	}

	@Override
	public void setParTime(double seconds) {
		support.setParTime(seconds);
		fx(() -> {
			if (timingControls != null) timingControls.setParTime(seconds);
		});
	}

	@Override
	public void onParTimeChanged(DoubleConsumer listener) {
		support.onParTimeChanged(listener);
		fx(() -> showTimingControls(true));
	}

	@Override
	public DelayRange delayedStart() {
		return support.delayedStart();
	}

	@Override
	public void setDelayedStart(DelayRange range) {
		support.setDelayedStart(range);
		fx(() -> {
			if (timingControls != null) timingControls.setDelayRange(range.minSeconds(), range.maxSeconds());
		});
	}

	@Override
	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		support.onDelayedStartChanged(listener);
		fx(() -> showTimingControls(false));
	}

	// The fields show the current values; setting them notifies timingListener, which ignores values it
	// already has
	private void showTimingControls(boolean withParTime) {
		if (timingControls == null) {
			timingControls = new TimingControlsPane(timingListener);
			timingControls.setDelayRange(support.delayedStart().minSeconds(), support.delayedStart().maxSeconds());
			context.view().getTrainingExerciseContainer().getChildren().add(timingControls);
			panes.add(timingControls);
		}

		if (withParTime && !timingControls.hasParTime()) {
			timingControls.addParTime(timingListener);
			timingControls.setParTime(support.parTime());
		}
	}
}
```

- [ ] **Step 6: Run the JavaFX host tests**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.exercise.*' --tests 'com.shootoff.plugins.engine.*' --console=plain`
Expected: `BUILD SUCCESSFUL`. All of `TestJavaFxExerciseHost` (13), `TestJavaFxExerciseHostOnArena` (2) and `TestHostedExercise` (1) pass unchanged. This includes `stopFinishesRestoringTheBackgroundBeforeReturningOnTheFxThread` and `jarTargetIsTheProjectorCopyAndHidesOnTheProjector`.

- [ ] **Step 7: Run the gate**

Expected: `410/410 passing; 0 regressions; 0 new failures` (passing = 399 + 11).

- [ ] **Step 8: Commit**

```bash
git add plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java plugin-api/src/main/java/com/shootoff/exercise/host/SavedBackground.java plugin-api/src/test/java/com/shootoff/exercise/host/TestExerciseHostSupport.java javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java
git commit -m "Move exercise host support into plugin-api; JavaFxExerciseHost draws over it"
git log -1 --format=%B
```

Expected: the message alone, with no trailer.

---
### Task 6: The `ExerciseHost` contract suite

**Files:**
- Create: `plugin-api/src/testFixtures/java/com/shootoff/exercise/ExerciseHostContract.java`
- Modify: `plugin-api/build.gradle.kts` (JUnit, compile-only, for the fixtures)
- Modify: `javafx-app/build.gradle.kts` (the tests use `plugin-api`'s fixtures)
- Test: `javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java` (new, the JavaFX harness), `TestJavaFxExerciseHostContract.java` and `TestJavaFxExerciseHostContractOnArena.java` (new)

**Interfaces:**
- Consumes:
  - `JavaFxExerciseHost`, `ExerciseHostContext`, `SoundOutput` (javafx-app)
  - `MockCanvasManager`, `ProjectorArenaPane`, `MirroredCanvasManager`, `MirroredTarget`, `TimingControlsPane`, `ShotEntry` (javafx-app)
  - `PluginJars` (core fixtures), `Plugin`, `ExerciseLoaders`
- Produces: `public abstract class com.shootoff.exercise.ExerciseHostContract` (plugin-api test fixtures, JUnit 5):
  - `@TempDir protected Path temp`
  - `protected abstract Harness newHarness(Exercise exercise, Map<String, byte[]> jarEntries) throws Exception`
  - `public interface Harness extends AutoCloseable`:
    - driving the host: `ExerciseHost host()`, `void start()`, `void shoot(double x, double y)`, `void reset()`, `void stop()`, `void awaitUi()`
    - playing the user: `void click(String label)`, `void changeSetting(String label, double value)`, `void userSetsParTime(double)`, `void userSetsDelayedStart(int min, int max)`
    - looking at what is shown: `Object shownState()`, `void addShotRow(long timeMillis)`, `List<TimerRowView> timerRows(String column)`, `List<Boolean> visibilityOnEachView(TargetHandle)`, `int viewCount()`, `List<String> playedSounds()`
    - `void close()`
  - `public record TimerRowView(String time, String split, String laser, String columnValue, boolean highlighted)`
  - eight tests:
    - `everyCallbackRunsOnTheExercisesOneThread`
    - `stopRunsTheExercisesStopExactlyOnceFromAnyThread`
    - `stopRemovesEverythingTheExerciseAddedAndLaterCallsChangeNothing`
    - `aTimerRowBecomesTheLatestRow`
    - `hidingATargetHidesItOnEveryViewOfTheSurface`
    - `removingATargetRemovesItFromEveryViewOfTheSurface`
    - `parAndDelayListenersHearOnlyTheUsersChanges`
    - `namesAreLookedUpInTheExercisesJarFirst`

  Plan 5's `ComposeExerciseHost` test subclasses it, with a Compose `Harness`.

The spec's contract items and the tests that check them:

| Spec §5 contract item | Contract test |
|---|---|
| one exercise thread | `everyCallbackRunsOnTheExercisesOneThread` |
| stop cleanup, exactly-once stop | `stopRunsTheExercisesStopExactlyOnceFromAnyThread`, `stopRemovesEverythingTheExerciseAddedAndLaterCallsChangeNothing` |
| timer rows (`addTimerRow` becomes the latest row) | `aTimerRowBecomesTheLatestRow` |
| hiding and removing targets on the surface the exercise sees, mirrored arena included | `hidingATargetHidesItOnEveryViewOfTheSurface`, `removingATargetRemovesItFromEveryViewOfTheSurface`, run on the arena by `TestJavaFxExerciseHostContractOnArena` |
| par/delay listeners | `parAndDelayListenersHearOnlyTheUsersChanges` |
| resource resolution order | `namesAreLookedUpInTheExercisesJarFirst` |

- [ ] **Step 1: Write the JavaFX subclasses**

`javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHostContract.java`:

```java
package com.shootoff.gui.exercise;

import java.util.Map;

import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHostContract;

/**
 * JavaFxExerciseHost on a camera feed keeps the host contract.
 */
class TestJavaFxExerciseHostContract extends ExerciseHostContract {
	@Override
	protected Harness newHarness(Exercise exercise, Map<String, byte[]> jarEntries) throws Exception {
		return JavaFxHostHarness.onCameraFeed(exercise, jarEntries, temp);
	}
}
```

`javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHostContractOnArena.java`:

```java
package com.shootoff.gui.exercise;

import java.util.Map;

import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHostContract;

/**
 * JavaFxExerciseHost on the projector arena keeps the host contract, on the arena window and on the
 * arena tab that mirrors it.
 */
class TestJavaFxExerciseHostContractOnArena extends ExerciseHostContract {
	@Override
	protected Harness newHarness(Exercise exercise, Map<String, byte[]> jarEntries) throws Exception {
		return JavaFxHostHarness.onArena(exercise, jarEntries, temp);
	}
}
```

`javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java`: the host on a `MockCanvasManager` camera canvas, or on the projector canvas mirrored by the arena tab's, wired as `ProjectorSlide` and `TestJavaFxExerciseHostOnArena` wire them. On the arena the exercise's jar is a real plugin jar, because the arena canvases load `@` targets from the current plugin:

```java
package com.shootoff.gui.exercise;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.ExerciseHostContract;
import com.shootoff.exercise.ExerciseHostContract.TimerRowView;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.MirroredCanvasManager;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.MirroredTarget;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.TrainingExerciseBase;
import com.shootoff.plugins.TrainingExerciseView;
import com.shootoff.plugins.engine.ExerciseLoaders;
import com.shootoff.plugins.engine.Plugin;
import com.shootoff.plugins.engine.PluginJars;
import com.shootoff.targets.Target;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * A {@link JavaFxExerciseHost} on a camera feed's canvas, or on the projector arena's canvas mirrored by
 * the arena tab's (wired as ProjectorSlide wires them), for {@link ExerciseHostContract}.
 */
final class JavaFxHostHarness implements ExerciseHostContract.Harness {
	// The plugin a projector exercise's jar needs to be: the arena's canvases load "@" targets from the
	// current plugin's jar
	private static final String DRILL_CLASS = "com.example.v2.ContractDrill";
	private static final String DRILL_SOURCE = String.join("\n",
			"package com.example.v2;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.exercise.Exercise;",
			"import com.shootoff.exercise.ExerciseHost;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.targets.model.Hit;",
			"public class ContractDrill implements Exercise {",
			"  @Override public ExerciseMetadata metadata() {",
			"    return new ExerciseMetadata(\"Contract drill\", \"1.0\", \"ShootOFF tests\", \"Its jar\", true);",
			"  }",
			"  @Override public void start(ExerciseHost host) {}",
			"  @Override public void onShot(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void onReset() {}",
			"  @Override public void stop() {}",
			"}");

	private final Configuration config;
	private final JavaFxExerciseHost host;
	private final CanvasManager canvas;
	private final Optional<MirroredCanvasManager> mirror;
	private final List<CanvasManager> feeds;
	private final Pane container;
	private final VBox buttons;
	private final TableView<ShotEntry> table;
	private final URLClassLoader resources;
	private final List<String> played = new CopyOnWriteArrayList<>();

	private JavaFxHostHarness(Configuration config, Exercise exercise, CanvasManager canvas,
			Optional<MirroredCanvasManager> mirror, Optional<ProjectorArenaPane> arena, Pane container,
			URLClassLoader resources) throws Exception {
		this.config = config;
		this.canvas = canvas;
		this.mirror = mirror;
		feeds = List.of(mirror.isPresent() ? mirror.get() : canvas);
		this.container = container;
		this.resources = resources;
		buttons = onFx(() -> new VBox(new Button("Reset")));
		table = onFx(() -> new TableView<>(FXCollections.observableArrayList()));

		host = new JavaFxExerciseHost(exercise, new ExerciseHostContext(config, new CamerasSupervisor(config), new View(),
				canvas, arena, feeds, resources, new SoundOutput() {
					@Override
					public void play(String name, InputStream sound, Runnable whenDone) {
						try (InputStream in = sound) {
							played.add(name);
						} catch (final IOException e) {
							throw new AssertionError(e);
						}
						whenDone.run();
					}

					@Override
					public void say(String text) {}
				}));
	}

	/**
	 * A host on a camera feed's canvas, with the exercise's jar as a folder.
	 */
	static JavaFxHostHarness onCameraFeed(Exercise exercise, Map<String, byte[]> jarEntries, Path temp)
			throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final Configuration config = new Configuration(new String[0]);

		final Path jar = Files.createDirectories(temp.resolve("jar"));
		write(jar, jarEntries);
		final URLClassLoader resources = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null);

		final MockCanvasManager canvas = onFx(() -> new MockCanvasManager(config));
		return new JavaFxHostHarness(config, exercise, canvas, Optional.empty(), Optional.empty(),
				onFx(VBox::new), resources);
	}

	/**
	 * A host on the projector arena: the arena window's canvas, mirrored by the arena tab's, with the
	 * exercise's jar installed as the current plugin.
	 */
	static JavaFxHostHarness onArena(Exercise exercise, Map<String, byte[]> jarEntries, Path temp) throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final Configuration config = new Configuration(new String[0]);

		// PluginJars jars everything in its classes folder
		write(Files.createDirectories(temp.resolve("drill.jar.build").resolve("classes")), jarEntries);
		final Path jar = PluginJars.build(temp, "drill.jar", Optional.of(PluginJars.descriptor(2, DRILL_CLASS)),
				Map.of(DRILL_CLASS, DRILL_SOURCE), classpath());
		final Plugin plugin = new Plugin(jar, ExerciseLoaders.all());
		config.setPlugin(plugin);

		final Pane container = onFx(VBox::new);
		final ProjectorArenaPane[] panes = onFx(() -> {
			final ObservableList<ShotEntry> shotTimer = FXCollections.observableArrayList();
			final Stage arenaStage = new Stage();
			final ProjectorArenaPane projectorPane = new ProjectorArenaPane(arenaStage, null, container, null,
					shotTimer);
			final ProjectorArenaPane tabPane = new ProjectorArenaPane(arenaStage, null, container, null, shotTimer);
			projectorPane.setArenaPaneMirror(tabPane);
			final MirroredCanvasManager projector = (MirroredCanvasManager) projectorPane.getCanvasManager();
			final MirroredCanvasManager tab = (MirroredCanvasManager) tabPane.getCanvasManager();
			projector.setMirroredManager(tab);
			tab.setMirroredManager(projector);
			tab.setRecordsSessionEvents(false);
			return new ProjectorArenaPane[] { projectorPane, tabPane };
		});

		return new JavaFxHostHarness(config, exercise, panes[0].getCanvasManager(),
				Optional.of((MirroredCanvasManager) panes[1].getCanvasManager()), Optional.of(panes[0]), container,
				plugin.getLoader());
	}

	private static void write(Path folder, Map<String, byte[]> entries) throws IOException {
		for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
			final Path file = folder.resolve(entry.getKey());
			Files.createDirectories(file.getParent());
			Files.write(file, entry.getValue());
		}
	}

	// ShootOFF's classes, as a plugin author compiles against them
	private static String classpath() {
		return String.join(File.pathSeparator, System.getProperty("java.class.path"), location(TrainingExerciseBase.class),
				location(Exercise.class), location(Shot.class));
	}

	private static String location(Class<?> type) {
		return new File(type.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath();
	}

	static <T> T onFx(Callable<T> action) throws Exception {
		final CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.call());
			} catch (final Throwable t) {
				result.completeExceptionally(t);
			}
		});
		return result.get(5, TimeUnit.SECONDS);
	}

	private final class View implements TrainingExerciseView {
		@Override
		public Pane getTrainingExerciseContainer() {
			return container;
		}

		@Override
		public TableView<ShotEntry> getShotEntryTable() {
			return table;
		}

		@Override
		public VBox getButtonsPane() {
			return buttons;
		}

		@Override
		public Optional<CameraView> getArenaView() {
			return Optional.empty();
		}

		@Override
		public List<Target> getTargets() {
			return canvas.getTargets();
		}
	}

	@Override
	public ExerciseHost host() {
		return host;
	}

	@Override
	public void start() {
		host.start();
	}

	@Override
	public void shoot(double x, double y) {
		final DisplayShot shot = new DisplayShot(ShotColor.RED, x, y, System.currentTimeMillis(), 2);
		// A projector exercise takes shots in arena coordinates
		host.deliverShot(mirror.isPresent() ? new ArenaShot(shot) : shot, Optional.empty());
	}

	@Override
	public void reset() {
		host.reset();
	}

	@Override
	public void stop() {
		host.stop();
	}

	@Override
	public void awaitUi() throws Exception {
		onFx(() -> null);
	}

	@Override
	public void click(String label) throws Exception {
		onFx(() -> {
			for (final Node node : buttons.getChildren()) {
				if (node instanceof Button button && label.equals(button.getText())) {
					button.fire();
					return null;
				}
			}
			throw new AssertionError("No button " + label);
		});
	}

	@Override
	@SuppressWarnings("unchecked")
	public void changeSetting(String label, double value) throws Exception {
		onFx(() -> {
			for (final Node pane : container.getChildren()) {
				if (pane instanceof HBox box && box.getChildren().get(0) instanceof Label name
						&& label.equals(name.getText())) {
					((Spinner<Double>) box.getChildren().get(1)).getValueFactory().setValue(value);
					return null;
				}
			}
			throw new AssertionError("No setting " + label);
		});
	}

	private TimingControlsPane timingControls() {
		return container.getChildren().stream().filter(TimingControlsPane.class::isInstance)
				.map(TimingControlsPane.class::cast).findFirst()
				.orElseThrow(() -> new AssertionError("No timing controls"));
	}

	@Override
	public void userSetsParTime(double seconds) throws Exception {
		onFx(() -> {
			timingControls().setParTime(seconds);
			return null;
		});
	}

	@Override
	public void userSetsDelayedStart(int minSeconds, int maxSeconds) throws Exception {
		onFx(() -> {
			timingControls().setDelayRange(minSeconds, maxSeconds);
			return null;
		});
	}

	@Override
	public Object shownState() throws Exception {
		return onFx(() -> {
			final List<Object> state = new ArrayList<>();
			state.add(List.copyOf(canvas.getCanvasGroup().getChildren()));
			state.add(List.copyOf(canvas.getTargets()));
			if (mirror.isPresent()) {
				state.add(List.copyOf(mirror.get().getCanvasGroup().getChildren()));
				state.add(List.copyOf(mirror.get().getTargets()));
			}
			for (final CanvasManager feed : feeds) {
				state.add(List.copyOf(feed.getCanvasGroup().getChildren()));
			}
			state.add(List.copyOf(buttons.getChildren()));
			state.add(List.copyOf(container.getChildren()));
			state.add(List.copyOf(table.getColumns()));
			return state;
		});
	}

	@Override
	public void addShotRow(long timeMillis) throws Exception {
		onFx(() -> {
			final List<ShotEntry> rows = table.getItems();
			final Optional<Shot> previous = rows.isEmpty() ? Optional.empty()
					: Optional.of(rows.get(rows.size() - 1).getShot());
			rows.add(new ShotEntry(new DisplayShot(new Shot(ShotColor.RED, 1, 2, timeMillis), 2), previous,
					Optional.empty(), false, false));
			return null;
		});
	}

	@Override
	public List<TimerRowView> timerRows(String column) throws Exception {
		return onFx(() -> table.getItems().stream()
				.map(row -> new TimerRowView(row.getTimestamp(), row.getSplit().getSplit(), row.getColor(),
						row.getExerciseValue(column), row.getRowColor().isPresent()))
				.toList());
	}

	@Override
	public List<Boolean> visibilityOnEachView(TargetHandle target) throws Exception {
		final Optional<TargetView> view = find(canvas, target);

		if (view.isEmpty()) {
			// A copy left on the arena tab without its projector copy still shows
			if (mirror.isPresent() && mirror.get().getTargets().size() > canvas.getTargets().size()) {
				return List.of(true);
			}
			return List.of();
		}

		final List<Boolean> visibility = new ArrayList<>();
		visibility.add(shown(view.get()));
		if (mirror.isPresent()) {
			final TargetView tabCopy = ((MirroredTarget) view.get()).getMirroredTarget();
			if (!mirror.get().getTargets().contains(tabCopy)) throw new AssertionError("The arena tab has no copy");
			visibility.add(shown(tabCopy));
		}
		return visibility;
	}

	private static Optional<TargetView> find(CanvasManager canvas, TargetHandle target) {
		for (final Target t : new ArrayList<>(canvas.getTargets())) {
			final TargetView view = (TargetView) t;
			if (view.getPlacedTarget().getId().equals(target.id())) return Optional.of(view);
		}
		return Optional.empty();
	}

	// The model and the scene must agree
	private static boolean shown(TargetView view) throws Exception {
		final boolean model = view.isVisible();
		final boolean node = onFx(() -> view.getTargetGroup().isVisible());
		if (model != node) throw new AssertionError("The model says visible=" + model + ", the scene " + node);
		return model;
	}

	@Override
	public int viewCount() {
		return mirror.isPresent() ? 2 : 1;
	}

	@Override
	public List<String> playedSounds() {
		return List.copyOf(played);
	}

	@Override
	public void close() throws Exception {
		host.stop();
		config.setPlugin(null);
		resources.close();
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.exercise.TestJavaFxExerciseHostContract*' --console=plain`
Expected: FAIL at compile time: `package com.shootoff.exercise` has no `ExerciseHostContract` (`cannot find symbol`).

- [ ] **Step 3: Give the fixtures JUnit, compile-only, and the JavaFX tests the fixtures**

In `plugin-api/build.gradle.kts`, in `dependencies`, after `testImplementation(testFixtures(project(":core")))`, add:

```kotlin
    // ExerciseHostContract is a JUnit test class; the hosts' tests that extend it bring JUnit
    // themselves, so it isn't published as a dependency of the fixtures
    testFixturesCompileOnly(platform(libs.junit.bom))
    testFixturesCompileOnly(libs.junit.jupiter)
```

In `javafx-app/build.gradle.kts`, in `dependencies`, after `testImplementation(testFixtures(project(":core")))`, add:

```kotlin
    // ExerciseHostContract, which JavaFxExerciseHost must pass
    testImplementation(testFixtures(project(":plugin-api")))
```

- [ ] **Step 4: Write the contract**

`plugin-api/src/testFixtures/java/com/shootoff/exercise/ExerciseHostContract.java`. It is a test fixture, so it has no GPL header, and it must not reference `javafx.*`:

```java
package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;

/**
 * What every {@link ExerciseHost} must do, whatever user interface draws it. A user interface's host
 * test extends this and supplies a {@link Harness}: the host on a real surface of that user interface,
 * and ways to play the user and look at what is shown. Run it once per kind of surface (a camera feed,
 * the projector arena).
 */
public abstract class ExerciseHostContract {
	protected static final String EXERCISE_NAME = "Contract drill";
	private static final String EXERCISE_THREAD = "Exercise: " + EXERCISE_NAME;
	private static final TextStyle STYLE = new TextStyle(20, "white", "transparent");

	// A small box of the exercise's own, and one that shadows ShootOFF's targets/IPSC.target
	private static final byte[] BOX = "<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"red\" /></target>"
			.getBytes();
	private static final byte[] SHADOW = "<target><rectangle x=\"0\" y=\"0\" width=\"30\" height=\"40\" fill=\"blue\" /></target>"
			.getBytes();
	private static final byte[] CUE = { 1, 2, 3 };

	@TempDir protected Path temp;

	private Harness harness;
	private final Drill drill = new Drill();

	/**
	 * A host on one of the user interface's surfaces, running the contract's exercise.
	 */
	public interface Harness extends AutoCloseable {
		ExerciseHost host();

		/** Starts the exercise, as the user interface does when the user picks it. */
		void start();

		/** Hands the exercise a shot at (x, y) on its surface, as the shot pipeline does. */
		void shoot(double x, double y);

		/** The user presses Reset. */
		void reset();

		/** Stops the exercise, as the user interface does when the user picks another. */
		void stop();

		/** Waits until the user interface has made every change queued so far. */
		void awaitUi() throws Exception;

		/** The user clicks the exercise's button with this label. */
		void click(String label) throws Exception;

		/** The user sets the exercise's number setting with this label. */
		void changeSetting(String label, double value) throws Exception;

		/** The user types a par time into the shared control. */
		void userSetsParTime(double seconds) throws Exception;

		/** The user types a start delay into the shared controls. */
		void userSetsDelayedStart(int minSeconds, int maxSeconds) throws Exception;

		/**
		 * @return everything the surface, the controls and the shot timer show, comparable with
		 *         <tt>equals</tt>
		 */
		Object shownState() throws Exception;

		/** A shot's row appears in the shot timer, as the shot pipeline adds it. */
		void addShotRow(long timeMillis) throws Exception;

		/**
		 * @return the shot timer's rows, with each row's value in <tt>column</tt>
		 */
		List<TimerRowView> timerRows(String column) throws Exception;

		/**
		 * @return whether each view of the surface shows the target (one per view: the arena window and
		 *         the arena tab that mirrors it); empty if no view has it
		 */
		List<Boolean> visibilityOnEachView(TargetHandle target) throws Exception;

		/**
		 * @return how many views the surface has
		 */
		int viewCount();

		/**
		 * @return the names of the sounds played, in order
		 */
		List<String> playedSounds();

		@Override
		void close() throws Exception;
	}

	/**
	 * A shot timer row as the user sees it.
	 */
	public record TimerRowView(String time, String split, String laser, String columnValue, boolean highlighted) {}

	/**
	 * @param jarEntries
	 *            the exercise's jar: resource names and their bytes
	 */
	protected abstract Harness newHarness(Exercise exercise, Map<String, byte[]> jarEntries) throws Exception;

	// The exercise under test: it writes down its callbacks and their threads, and does what the test
	// asks when it starts
	private static final class Drill implements Exercise {
		final List<String> events = new CopyOnWriteArrayList<>();
		final Map<String, String> threads = new ConcurrentHashMap<>();
		final AtomicInteger stops = new AtomicInteger();
		volatile Consumer<ExerciseHost> onStart = host -> {};

		void record(String event) {
			threads.put(event, Thread.currentThread().getName());
			events.add(event);
		}

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata(EXERCISE_NAME, "1.0", "ShootOFF tests", "Checks the host contract");
		}

		@Override
		public void start(ExerciseHost host) {
			onStart.accept(host);
			// Written down once start() has done everything it does
			record("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			record("shot");
		}

		@Override
		public void onTargetsChanged(List<TargetHandle> targets) {
			record("targets");
		}

		@Override
		public void onReset() {
			record("reset");
		}

		@Override
		public void stop() {
			stops.incrementAndGet();
			record("stop");
		}
	}

	private Harness harness() throws Exception {
		if (harness == null) {
			final Map<String, byte[]> jar = new LinkedHashMap<>();
			jar.put("targets/box.target", BOX);
			jar.put("targets/IPSC.target", SHADOW);
			jar.put("sounds/cue.wav", CUE);
			harness = newHarness(drill, jar);
		}
		return harness;
	}

	@AfterEach
	void closeHarness() throws Exception {
		if (harness != null) harness.close();
	}

	private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail(failure);
			Thread.sleep(5);
		}
	}

	private void awaitEvent(String event) throws InterruptedException {
		waitFor(() -> drill.events.contains(event), "never saw " + event + " in " + drill.events);
	}

	private static byte[] read(Optional<InputStream> stream) throws IOException {
		try (InputStream in = stream.orElseThrow(() -> new AssertionError("nothing opened"))) {
			return in.readAllBytes();
		}
	}

	@Test
	void everyCallbackRunsOnTheExercisesOneThread() throws Exception {
		drill.onStart = host -> {
			host.addButton("Go", () -> drill.record("click"));
			host.addNumberSetting("Rounds", 1, 1, 10, 1, value -> drill.record("setting"));
			host.onParTimeChanged(seconds -> drill.record("par"));
			host.onDelayedStartChanged(range -> drill.record("delay"));
			host.schedule(() -> drill.record("scheduled"), Duration.ofMillis(10));
			host.scheduleRepeating(() -> drill.record("repeating"), Duration.ofMillis(10), Duration.ofMillis(50));
		};
		final Harness h = harness();

		h.start();
		awaitEvent("start");
		h.shoot(5, 5);
		h.reset();
		h.awaitUi();
		h.click("Go");
		h.changeSetting("Rounds", 2);
		h.userSetsParTime(3.0);
		h.userSetsDelayedStart(2, 6);

		for (final String event : List.of("start", "shot", "reset", "click", "setting", "par", "delay", "scheduled",
				"repeating")) {
			awaitEvent(event);
		}
		h.stop();

		assertTrue(drill.events.contains("stop"));
		assertEquals(Set.of(EXERCISE_THREAD), Set.copyOf(drill.threads.values()));
	}

	@Test
	void stopRunsTheExercisesStopExactlyOnceFromAnyThread() throws Exception {
		final Harness h = harness();
		h.start();
		awaitEvent("start");

		final Thread other = new Thread(h::stop);
		other.start();
		h.stop();
		other.join();
		h.stop();

		assertEquals(1, drill.stops.get());
		assertEquals(List.of("start", "stop"), drill.events);
	}

	@Test
	void stopRemovesEverythingTheExerciseAddedAndLaterCallsChangeNothing() throws Exception {
		final AtomicBoolean ran = new AtomicBoolean();
		final List<TargetHandle> added = new CopyOnWriteArrayList<>();
		drill.onStart = host -> {
			host.addTarget("targets/IPSC.target", 5, 5).ifPresent(added::add);
			host.addTarget("IPSC.target", 50, 50).ifPresent(added::add);
			host.showText("Score: 0", 10, 10, STYLE);
			host.showMessage("Make ready");
			host.addButton("Pause", () -> {});
			host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> {});
			host.addColumn("Score");
			host.showShotMarker(20, 20, new ShotStyle(ShotColor.RED));
			host.onParTimeChanged(seconds -> {});
			host.onDelayedStartChanged(range -> {});
			host.schedule(() -> ran.set(true), Duration.ofMillis(300));
		};
		final Harness h = harness();
		h.awaitUi();
		final Object before = h.shownState();

		h.start();
		awaitEvent("start");
		h.awaitUi();
		assertEquals(2, added.size());
		assertNotEquals(before, h.shownState());

		h.stop();
		h.awaitUi();

		assertEquals(before, h.shownState());
		for (final TargetHandle target : added) {
			assertEquals(List.of(), h.visibilityOnEachView(target), "a target the exercise added is still shown");
		}
		Thread.sleep(500);
		assertFalse(ran.get(), "a scheduled task ran after stop");

		final ExerciseHost host = h.host();
		host.showText("late", 0, 0, STYLE);
		host.addButton("late", () -> {});
		host.showMessage("late");
		assertEquals(Optional.empty(), host.addTarget("targets/IPSC.target", 0, 0));
		h.awaitUi();
		assertEquals(before, h.shownState());
	}

	@Test
	void aTimerRowBecomesTheLatestRow() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();
		host.addColumn("Score");
		h.addShotRow(1000);

		host.addTimerRow(2500, new RowStyle("coral"));
		host.setColumnValue("Score", "0");
		h.awaitUi();
		h.addShotRow(3000);

		assertEquals(List.of(new TimerRowView(String.format("%.2f", 1.0f), "-", "red", "", false),
				new TimerRowView(String.format("%.2f", 2.5f), String.format("%.2f", 1.5f), "red", "0", true),
				new TimerRowView(String.format("%.2f", 3.0f), String.format("%.2f", 0.5f), "red", "", false)),
				h.timerRows("Score"));
	}

	@Test
	void hidingATargetHidesItOnEveryViewOfTheSurface() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();
		final TargetHandle fromJar = host.addTarget("@targets/box.target", 10, 10).get();
		final TargetHandle fromShootoff = host.addTarget("IPSC.target", 100, 100).get();
		h.awaitUi();

		final List<Boolean> allShown = Collections.nCopies(h.viewCount(), true);
		final List<Boolean> allHidden = Collections.nCopies(h.viewCount(), false);
		assertEquals(allShown, h.visibilityOnEachView(fromJar));
		assertEquals(allShown, h.visibilityOnEachView(fromShootoff));

		fromJar.setVisible(false);
		fromShootoff.setVisible(false);
		h.awaitUi();
		assertEquals(allHidden, h.visibilityOnEachView(fromJar));
		assertEquals(allHidden, h.visibilityOnEachView(fromShootoff));

		fromJar.setVisible(true);
		h.awaitUi();
		assertEquals(allShown, h.visibilityOnEachView(fromJar));
		assertEquals(allHidden, h.visibilityOnEachView(fromShootoff));
	}

	@Test
	void removingATargetRemovesItFromEveryViewOfTheSurface() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();
		final TargetHandle box = host.addTarget("@targets/box.target", 10, 10).get();
		final TargetHandle ipsc = host.addTarget("IPSC.target", 100, 100).get();
		h.awaitUi();

		// The handle the exercise got is the target the surface shows
		assertTrue(host.targets().containsAll(List.of(box, ipsc)));
		box.move(30, 40);
		assertEquals(30, box.position().getX(), 0);

		box.remove();
		h.awaitUi();

		assertEquals(List.of(), h.visibilityOnEachView(box));
		assertFalse(host.targets().contains(box));
		assertEquals(Collections.nCopies(h.viewCount(), true), h.visibilityOnEachView(ipsc));
	}

	@Test
	void parAndDelayListenersHearOnlyTheUsersChanges() throws Exception {
		final List<String> heard = new CopyOnWriteArrayList<>();
		drill.onStart = host -> {
			host.setParTime(4.0);
			host.setDelayedStart(new DelayRange(5, 8));
			host.onParTimeChanged(seconds -> heard.add("par " + seconds));
			host.onDelayedStartChanged(range -> heard.add("delay " + range.minSeconds() + "-" + range.maxSeconds()));
		};
		final Harness h = harness();
		h.start();
		awaitEvent("start");
		h.awaitUi();
		assertEquals(List.of(), heard);

		h.userSetsParTime(3.5);
		h.userSetsParTime(3.5);
		h.userSetsDelayedStart(9, 6);
		h.userSetsDelayedStart(2, 6);
		waitFor(() -> heard.size() >= 2, "heard only " + heard);
		Thread.sleep(100);

		assertEquals(List.of("par 3.5", "delay 2-6"), heard);
		assertEquals(3.5, h.host().parTime(), 0);
		assertEquals(new DelayRange(2, 6), h.host().delayedStart());
	}

	@Test
	void namesAreLookedUpInTheExercisesJarFirst() throws Exception {
		final Harness h = harness();
		final ExerciseHost host = h.host();

		// The jar's IPSC.target shadows ShootOFF's; a bare name finds ShootOFF's in its targets folder
		assertEquals(1, host.addTarget("targets/IPSC.target", 0, 0).get().definition().regions().size());
		assertTrue(host.addTarget("IPSC.target", 0, 0).get().definition().regions().size() > 1);
		assertEquals(10, host.addTarget("@targets/box.target", 0, 0).get().size().getWidth(), 0);
		assertEquals(Optional.empty(), host.addTarget("targets/no_such.target", 0, 0));

		// Resources are the jar's only; sounds fall back to ShootOFF's sounds folder
		assertArrayEquals(CUE, read(host.resource("/sounds/cue.wav")));
		assertEquals(Optional.empty(), host.resource("sounds/beep.wav"));
		host.playSounds(List.of("sounds/cue.wav", "no_such.wav", "beep.wav"));
		waitFor(() -> h.playedSounds().size() >= 2, "played only " + h.playedSounds());
		assertEquals(List.of("sounds/cue.wav", "beep.wav"), h.playedSounds());
	}
}
```

- [ ] **Step 5: Run the contract on both surfaces**

Run: `./gradlew :plugin-api:test --tests 'com.shootoff.exercise.TestNoJavaFxInPluginApi' :javafx-app:test --tests 'com.shootoff.gui.exercise.TestJavaFxExerciseHostContract*' --console=plain && command grep -ho 'tests="[0-9]*" skipped="0" failures="0" errors="0"' javafx-app/build/test-results/test/TEST-com.shootoff.gui.exercise.TestJavaFxExerciseHostContract*.xml`
Expected: `BUILD SUCCESSFUL`, then two lines of `tests="8" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Check that the contract catches the mirrored-arena bug**

Undo `bc444821`'s fix in the working tree only. In `JavaFxExerciseHost.loadJarTarget`, delete the `if (context.canvas() instanceof MirroredCanvasManager) { … }` block. In `FxTargetHandle.setVisible`, delete the `if (view instanceof MirroredTarget mirrored …) { … }` block. Then run:

```bash
./gradlew :javafx-app:cleanTest :javafx-app:test --tests 'com.shootoff.gui.exercise.TestJavaFxExerciseHostContractOnArena' --console=plain | command grep -E "FAILED"
git checkout javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java
git diff --stat javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java
```

Expected:
- among the `FAILED` lines (`> Task :javafx-app:test FAILED` is one of them): `TestJavaFxExerciseHostContractOnArena > hidingATargetHidesItOnEveryViewOfTheSurface() FAILED`, `… removingATargetRemovesItFromEveryViewOfTheSurface() FAILED` and `… stopRemovesEverythingTheExerciseAddedAndLaterCallsChangeNothing() FAILED`
- after the checkout, `git diff --stat` prints nothing: the file is Task 5's again

- [ ] **Step 7: Check the published fixtures**

```bash
./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain -q
python3 -c "import json; m=json.load(open('build/m2/com/shootoff/plugin-api/5.0.0-SNAPSHOT/plugin-api-5.0.0-SNAPSHOT.module')); print({v['name']: [d['module'] for d in v.get('dependencies', [])] for v in m['variants']})"
```

Expected: `{'apiElements': ['core'], 'runtimeElements': ['core'], 'testFixturesApiElements': ['plugin-api'], 'testFixturesRuntimeElements': ['plugin-api']}`. The fixtures gain no JUnit dependency, so the drill's `isTransitive = false` fixtures still resolve.

- [ ] **Step 8: Run the gate**

Expected: `426/426 passing; 0 regressions; 0 new failures` (passing = 410 + 16).

- [ ] **Step 9: Commit**

```bash
git add plugin-api/src/testFixtures/java/com/shootoff/exercise/ExerciseHostContract.java plugin-api/build.gradle.kts javafx-app/build.gradle.kts javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHostContract.java javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHostContractOnArena.java
git commit -m "Add the ExerciseHost contract suite and run it against JavaFxExerciseHost"
git log -1 --format=%B
```

Expected: the message alone, with no trailer.

---
### Task 7: JavaFX regression check with the owner

Runs in ShootOFF on `compose-ui`. **Files:** none. If a check fails, stop and debug with superpowers:systematic-debugging before changing code. The fix belongs in the task that owns the code, as a new commit.

- [ ] **Step 1: Check the v1 members, and the settings methods that moved**

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :javafx-app:classes --console=plain -q
CP=core/build/classes/java/main:plugin-api/build/classes/java/main:javafx-app/build/classes/java/main
missing=0
check() { # class member descriptor; a constructor's member is the class's full name
	if ! javap -s -p -cp "$CP" "$1" | command grep -A1 -F " $2(" | command grep -qF "descriptor: $3"; then
		echo "MISSING $1 $2 $3"; missing=1
	fi
}
check com.shootoff.targets.Target getDimension "()Ljavafx/geometry/Dimension2D;"
check com.shootoff.targets.Target getPosition "()Ljavafx/geometry/Point2D;"
check com.shootoff.targets.Target setPosition "(DD)V"
check com.shootoff.targets.Target setVisible "(Z)V"
check com.shootoff.targets.TargetRegion getTag "(Ljava/lang/String;)Ljava/lang/String;"
check com.shootoff.targets.TargetRegion tagExists "(Ljava/lang/String;)Z"
check com.shootoff.targets.Hit getHitRegion "()Lcom/shootoff/targets/TargetRegion;"
check com.shootoff.targets.Hit getImpactX "()I"
check com.shootoff.targets.Hit getImpactY "()I"
check com.shootoff.targets.Hit getShot "()Lcom/shootoff/camera/Shot;"
check com.shootoff.gui.CanvasManager getCanvasGroup "()Ljavafx/scene/Group;"
check com.shootoff.gui.CanvasManager addShot "(Lcom/shootoff/camera/shot/DisplayShot;Z)V"
check com.shootoff.gui.CanvasManager addArenaShot "(Lcom/shootoff/camera/shot/ArenaShot;Ljava/util/Optional;Z)Z"
check com.shootoff.gui.CanvasManager scaleShotToArenaBounds "(Lcom/shootoff/camera/shot/ArenaShot;)V"
check com.shootoff.gui.CanvasManager translateCameraToCanvas "(Lcom/shootoff/geom/Rect;)Lcom/shootoff/geom/Rect;"
check com.shootoff.gui.CanvasManager translateCanvasToCamera "(Lcom/shootoff/geom/Rect;)Lcom/shootoff/geom/Rect;"
check com.shootoff.gui.pane.ProjectorArenaPane getCanvasManager "()Lcom/shootoff/gui/CanvasManager;"
check com.shootoff.gui.LocatedImage com.shootoff.gui.LocatedImage "(Ljava/io/InputStream;Ljava/lang/String;)V"
check com.shootoff.plugins.ExerciseMetadata com.shootoff.plugins.ExerciseMetadata "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase com.shootoff.plugins.ProjectorTrainingExerciseBase "()V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase com.shootoff.plugins.ProjectorTrainingExerciseBase "(Ljava/util/List;)V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase addTarget "(Ljava/io/File;DD)Ljava/util/Optional;"
check com.shootoff.plugins.ProjectorTrainingExerciseBase destroy "()V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase getArenaWidth "()D"
check com.shootoff.plugins.ProjectorTrainingExerciseBase getArenaHeight "()D"
check com.shootoff.plugins.TrainingExerciseBase clearShots "()V"
check com.shootoff.plugins.TrainingExerciseBase pauseShotDetection "(Z)V"
check com.shootoff.plugins.TrainingExerciseBase playSound "(Ljava/io/File;)V"
check com.shootoff.plugins.TrainingExerciseBase playSound "(Ljava/io/InputStream;)V"
check com.shootoff.plugins.TrainingExerciseBase setShotTimerColumnText "(Ljava/lang/String;Ljava/lang/String;)V"
check com.shootoff.plugins.TrainingExerciseBase setShotTimerRowColor "(Ljavafx/scene/paint/Color;)V"
check com.shootoff.plugins.TrainingExercise shotListener "(Lcom/shootoff/camera/Shot;Ljava/util/Optional;)V"
check com.shootoff.config.Settings getSessionRecorder "()Ljava/util/Optional;"
check com.shootoff.config.Settings setSessionRecorder "(Lcom/shootoff/session/SessionRecorder;)V"
check com.shootoff.config.Settings getRecordingManagers "()Ljava/util/Set;"
check com.shootoff.config.Settings registerRecordingCameraManager "(Lcom/shootoff/camera/CameraManager;)V"
check com.shootoff.config.Settings unregisterRecordingCameraManager "(Lcom/shootoff/camera/CameraManager;)V"
check com.shootoff.config.Settings unregisterAllRecordingCameraManagers "()V"
check com.shootoff.config.Settings getMarkerRadius "()I"
echo "missing=$missing"
```

Expected: no `MISSING` line, and `missing=0`. `javap` lists a class's own members only, so the moved methods (and `getMarkerRadius`, which the drill calls on `Configuration`) are checked on `Settings`, where the JVM finds them.

- [ ] **Step 2: Put back the owner's settings and launch**

```bash
cd /home/bfears/projects/ShootOFF
cp build/plan4-shootoff.properties shootoff.properties
command grep -E "^shootoff.arena.(show.markers|calibrated.behavior)=" shootoff.properties
```

Expected: `shootoff.arena.calibrated.behavior=ONLY_IN_BOUNDS` and `shootoff.arena.show.markers=false`.

Run `./gradlew run --args="-d" --console=plain > build/plan4-run.log 2>&1` in the background, with the webcam and projector attached.

- [ ] **Step 3: The owner checks, on the hardware**

1. **Camera shots and hits.** On the camera tab, with IPSC added from the Targets menu, fire hits and misses:
   - markers appear where the laser hit
   - the shot timer rows show Time, Split and Laser as before
   - an IPSC hit counts on the hit region
2. **Arena calibration, auto.** Open the projector arena and press Calibrate:
   - the pattern shows
   - auto-calibration finds it
   - the arena's background comes back
   - shots inside the projection land on the arena, and shots outside it land on the camera tab's targets
3. **Auto-calibration while the v2 drill runs.** Start "Random Target PAR Drill with Score" (v2), then press Calibrate:
   - the drill stops and the pattern shows (not the drill's black background)
   - auto-calibration succeeds
   - the arena's background comes back
   - a fresh drill starts
4. **The v2 drill.** Play at least three rounds:
   - the target is hidden between rounds, on the projector *and* on the arena tab
   - a hit fills Length and Score
   - a round without a shot shows "Par missed!" and adds the **coral row** (Time, Split, "red", Length about the par time, Score 0)
   - Pause/Resume works; the summary shows the hit factor and personal best; a shot 4 s after the summary restarts the drill
   - pick "None": the drill leaves nothing behind (buttons, spinner, par/delay controls, columns, texts, target, markers) and the background is back
5. **Shoot Don't Shoot.** On the arena, hit "shoot" and "don't shoot" targets. It plays as before. After a don't-shoot hit ("Bad shoot!"), note whether the next shot's marker appears late (ruling 3). A marker may arrive after the speech; no shot may go missing.
6. **POI adjust.** Add `POI_Offset_Adjustment` to the arena, shoot its regions until the double beep turns the adjustment on, then shoot a target: the shots move by the adjustment. A sixth shot on the POI target turns it off (the double beep again).
7. **Session record and replay.** Press Record Session, add IPSC to the arena, move it, fire hits and misses, then press Stop Recording. In View Sessions the target and the shot markers replay as recorded, including the hits' regions.
8. **Two lasers in one frame** (if the owner can do it, for example two lasers held side by side): two rows, in order, and no "Called endChange before beginChange" in the log.
9. **The v1 drill.** Quit ShootOFF, then `mv exercises/RandomTargetParDrill-v2.jar build/`. Relaunch and play two rounds of the v1 drill: the target, the par miss's coral row, the scores. Quit, then `mv build/RandomTargetParDrill-v2.jar exercises/`.

- [ ] **Step 4: Check the log**

```bash
command grep -nE "Exception|NoClassDefFoundError|NoSuchMethodError|AbstractMethodError|endChange|Handling a shot failed" build/plan4-run.log
```

Expected: nothing. A `NoSuchMethodError` or `AbstractMethodError` naming a v1 member means the v1 surface broke: report it; don't paper over it.

- [ ] **Step 5: The owner's files**

```bash
cd /home/bfears/projects/ShootOFF
ls exercises/
sha256sum -c build/plan4-owner-files.sha256
git status --short
```

Expected:
- `RandomTargetParDrill-v2.jar` and `RandomTargetParDrill.jar` listed
- three `OK` lines
- `git status --short` lists only `shootoff.properties` (the owner's edits, as before this plan) and `.superpowers/`, plus anything the owner added. It lists no file this plan changed.

Things this machine can't exercise: the PS3 Eye camera, and a second camera feed. Note them as unchecked in the report.

---

## Spec coverage

| Spec | Where |
|---|---|
| §2 Plan 4 item 1: arena geometry (camera ↔ arena with the calibrated projection; `scaleShotToArenaBounds` and the translate helpers) | Task 1 |
| §2 item 2: the shot pipeline (processors, ignored laser color, hit-testing the right `TargetSet`, region commands and `poi_adjust`, session recording, timer rows, delivery) | Task 3; the ignored color stays in `ShotDetector` (ruling 5) |
| §2 item 2: rows on one thread in order, fixing two shots in one frame | Task 2 (rulings 3–4), reproduced by `TestShotTimerRowOrder` |
| §2 item 3: the calibration flow (pattern → auto-detect → success or timeout → manual → save; pause and restart the exercise); `CalibrationManager` keeps the JavaFX drawing and input | Task 4 (rulings 10–11) |
| §2 item 4: host support in `plugin-api` (resolution order, background save/restore, tracking and removal on stop, timer rows, par/delay listeners); `JavaFxExerciseHost` a thin JavaFX layer | Task 5 (ruling 12) |
| §2: each move its own step; the JavaFX app switches over in the same step; the suite keeps passing | one task per move, each with the gate |
| §5: geometry against the old `CanvasManager` math across placements and scales | Task 1, `TestArenaGeometry` |
| §5: the pipeline (processors, camera and arena hits, recording, row order, two shots in one frame) | Task 3, `TestShotPipeline`, `TestTimerRow`, `TestPoiAdjustment`; Task 2, `TestShotQueue`, `TestShotTimerRowOrder` |
| §5: the calibration flow's transitions with a fake camera and arena | Task 4, `TestCalibrationFlow` |
| §5: host support (resolution order, stop cleanup, timer rows) | Task 5, `TestExerciseHostSupport` |
| §5: the shared contract suite against `JavaFxExerciseHost` (one exercise thread; stop cleanup; timer rows; hiding and removing targets on the surface the exercise sees) | Task 6, on a camera feed and on the mirrored arena (ruling 13) |
| §5: existing JavaFX tests unchanged and passing | every task (ruling 11) |
| §5 / §6: a short JavaFX regression check | Task 7 |
| §6 Plan 4: shippable on its own | the gate after every task; Task 7 |
