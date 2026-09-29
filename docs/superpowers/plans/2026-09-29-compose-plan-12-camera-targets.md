# Compose Plan 12: Camera Targets Alongside the Projector — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Once the projector arena is calibrated, a shot on a camera-feed target registers on that target (inside the projected area or beside it), a miss beside the projection is dropped with "Only detect shots in projector bounds", the camera looks at its whole frame while the feed has targets, Shift+Up makes a target taller, and the Compose log shows the layout memory's info lines. Both apps get the same behaviour, since the routing and detection live in `core`.

**Architecture:**
- **Task 1: where a shot goes** (`core`'s `ShotPipeline`). While the feed's arena is calibrated, the feed is hit-tested first. A hit on a feed target keeps the shot on the feed. Otherwise a shot inside the projection goes to the arena, as today. Otherwise, with `ONLY_IN_BOUNDS`, the shot is dropped before the processors, the sound, the row and the marker. Without a calibrated arena nothing changes, not even the order of the steps.
- **Task 2: where the camera looks** (`core`'s `CameraManager`, `CameraView`, `ShotDetector`, plus both apps' camera views). `CameraView` gains `hasTargets()`, which the camera asks on its own thread for every frame. With `ONLY_IN_BOUNDS` the camera searches only the projection while its view has no targets, and the whole frame while it has some. Each frame's area is fixed for that frame, so a shot is offset by the area it was found in, and the detector's per-pixel averages start afresh when the area changes. The Compose app answers from `FeedSurface`'s `TargetSet`, the JavaFX app from `CanvasManager`'s.
- **Task 3: Shift+Up/Down and the log.** `TargetEditor.nudge` swaps Up and Down for resizing. `logback.xml` sets `com.shootoff.compose.courses` to INFO.
- **Task 4: the owner's hardware check.**

**Tech Stack:**
- Java 21 (`core`, `javafx-app`); Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- OpenCV through `org.bytedeco` `opencv_java` (already in `core`), for the `Mat` frames in Task 2's test.
- JUnit 5 in `core` and `compose-app`; JUnit 4 with `JavaFXThreadingRule` in `javafx-app`'s `TestCanvasManager`.

**Spec:** `docs/superpowers/specs/2026-09-28-compose-target-placement-design.md`, §9 "Revision 1" (commit `08e39c9f`). Read the whole spec for context; this plan implements only §9. Where the spec and this plan disagree, the spec wins.

**Prototype.**
- The whole plan was built in a scratch clone of `compose-ui` at `08e39c9f`, one commit per task.
- The gate ran green at the start (**926/926**), after Task 2 (**941/941**) and after Task 3 (**942/942**). Task 1 was checked with `core`'s `TestShotPipeline` and the full `compose-app` and `javafx-app` suites; its gate figure, 934, is the start plus its 8 new tests.
- The code blocks below are the prototype's edits, extracted from those commits by a script. The script checked that each "Replace … with …" pair matches exactly once in the file at the point it is applied, and that applying the pairs in order gives the task's commit byte for byte.
- Every new test was seen to fail first: against the old `ShotPipeline` (8 of `TestShotPipeline`'s 17), and against the old `logback.xml` (`theLayoutMemorysInfoLinesShowInTheAppsLog`). Task 2's tests don't compile without the new methods.
- The GUI apps were not run. Task 4 is the owner's.

**What only the hardware can confirm** (Task 4): that a real laser shot on a camera target beside the screen, and on one over the projected area, registers (with its sound); that the frame rate stays usable while the camera searches the whole frame; that adding or removing a camera target mid-session raises no phantom shot.

## Plan-author rulings

Where the spec leaves room, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

### Routing (Task 1)

1. **Where the decision is made.** `ShotPipeline.addShot` decides the route first, before the processors, the laser sound, the shot timer row, the marker and the video notice (`notifyShot`). Today the row and marker come before the arena check, so rule 3's "dropped entirely" needs the decision earlier. *Cost:* none.
2. **The feed is hit-tested first only while the arena is calibrated**: `surface.arena()` is present and its `projection()` is present. Without that, the order of the steps is today's (row, marker, hit test), so every test and behaviour without a calibrated arena is untouched (spec: "Without a calibrated arena nothing changes"). With it, the order is: hit test, processors, sound, row, marker, then the arena or the feed's recording, region commands and delivery. The feed's hit is tested once and reused for recording, commands and delivery. *Cost:* in the JavaFX app, `PipelineSurface.hitTest` records its v1 hit in a thread-local (`lastHit`) that `toHit` reads later; nothing else hit-tests that feed on the shot queue's thread in between, so the reuse holds.
3. **A dropped shot leaves no trace at all.** It gets no marker, no timer row and no session event. It isn't passed to the malfunction or virtual magazine processors, so it uses no round and can't malfunction. It plays no laser sound, doesn't reach the exercise, and isn't noted to the recording cameras. The row flags that carry "after a malfunction" / "after a reload" are left for the next shot that is kept. A debug log line records the drop. *Cost:* none; the spec says "exactly as if it had not been detected".
4. **Rule 3 reads `Settings.getCalibratedFeedBehavior()`**, not the camera's own flag. It is the setting the camera's detection follows in both apps: `CalibrationFlow.configureArenaCamera` applies it at calibration, and the JavaFX app applies it again whenever Preferences change it (`ProjectorSlide.calibratedFeedBehaviorsChanged`). The pipeline already holds `Settings`, and the surfaces need no new method. *Cost:* in the JavaFX app's debug click-to-shoot, a click beside the projection on no feed target is dropped under `ONLY_IN_BOUNDS`; the camera would not have seen such a shot either.
5. **"Crop feed to projector" is unchanged.** Its detection stays on the projection's sub-image, whatever targets the feed has. Rule 1 applies to camera targets on a cropped feed, which can only be inside the projection. A shot outside it (it can't normally happen) is a feed shot, as today.
6. **A mirrored shot is routed the same way.** In practice no mirrored shot has an arena link: the JavaFX `MirroredCanvasManager` returns no arena, and the Compose app mirrors nothing. The pipeline doesn't special-case it. *Cost:* none.
7. **Three existing `TestShotPipeline` tests change.**
   - `aShotInsideTheProjectionGoesToTheArenaInArenaCoordinates` shot through a camera box it had placed over the point. Under rule 1 that box now takes the shot, so the box moves to (10, 10); the overlap case becomes a new test.
   - `aShotOnTheProjectionsEdgeGoesToTheArenaAtTheArenasCurrentSize` asserted that the camera feed wasn't hit-tested. It now asserts that the camera feed delivered nothing.
   - `aShotOutsideTheProjectionIsHitTestedOnTheCameraFeed` now expects the hit test first.

### Detection (Task 2)

8. **How `CameraManager` learns about feed targets: `CameraView.hasTargets()`.** `CameraView` is `core`'s UI-neutral interface for "the camera's view of its feed". Both apps already implement it and hand it to the `CameraManager` constructor, which calls `setCameraManager(this)`. So no registration step is needed, and no view can be wired to the wrong camera. It is abstract, not a `default`, so no view can forget it; the two test fakes in `core` (`RecordingCameraView`, `TestRangeReset.RecordingView`) implement it.
   - Compose: `ComposeCameraView.hasTargets()` asks its `FeedShots`. `FeedShots` gains `hasTargets()`; `FeedShots.None` says false, and `FeedSurface` answers `targets.set.size() > 0`. `AppState`'s `OpenView` delegates `by view`, so it needs no edit.
   - JavaFX: `CanvasManager.hasTargets()` answers `targetSet.size() > 0`. The camera asks only its own view, the feed's `CanvasManager`, never the arena's `MirroredCanvasManager` (which `ProjectorSlide` also gives the calibrating camera).
   - *Thread-safety:* `TargetSet.size()` is `synchronized`, so the camera's thread can read it while the UI or an exercise changes the set.
   - *Cost:* one more method for any future `CameraView`.
9. **Any target counts**: the shooter's or an exercise's, shown or hidden. Targets an exercise adds to the feed are camera targets for both detection and routing, since they are in the feed's `TargetSet` and hit-testing already covers them. A hidden target keeps the camera looking at the whole frame but can't be hit (hit-testing skips it), so a shot on it beside the projection is dropped. Counting hidden targets keeps the detection area steady while a drill flashes targets on and off, and each change of area blinds the detector for a frame (ruling 11). *Cost:* while a drill hides every feed target, the camera still searches the whole frame.
10. **Each frame is searched in one fixed area, and its shots are offset by that area.** `processFrame` decides the area once per frame and holds it in a `ThreadLocal` while the detector runs. `ShotDetector.addShot` asks `CameraManager.getDetectionArea()`, which returns that frame's area on the camera's thread and today's live answer anywhere else (`injectShot`, tests). This way a target added or removed mid-frame can't give a shot the wrong offset. This is the crop-offset class of bug Plan 7's review fixed for the calibration measurement (`AppState.uncroppedDetector`, commit `a91d67b1`). The same code also fixes an old slip: when taking the projection's sub-image fails and detection falls back to the full frame, its shots are no longer offset. *Cost:* none.
11. **The detector starts afresh when the area changes.** `JavaShotDetector` keeps per-pixel moving averages indexed by the coordinates of the image it is given. After a switch between the sub-image and the full frame, each average belongs to a different pixel, and a bright pixel could look like a shot. So on a change `CameraManager` calls `setFrameSize(feedWidth, feedHeight)`, which resets the averages. The detector then sees nothing for one frame while they refill. This happens only on a change, not every frame (`theDetectorStartsAfreshOnlyWhenTheAreaChanges`), and the first calibrated frame gets it too. *Cost:* a one-frame blind spot each time the feed's first target is added or its last removed.
12. **`cropFeedToProjection` and `limitDetectProjection` become `volatile`.** They are set on the UI thread and read on the camera's for every frame. *Cost:* none.
13. **Detectors that don't search sub-images** (`NativeShotDetector`, `OptiTrackShotDetector`, `handlesBounds()` false) still drop shots outside the projection while the area is the projection. While the feed has targets the area is empty, so they pass every shot on, and the pipeline drops the misses (rule 3).
14. **Deduplication** still runs in the camera, before the pipeline. A dropped shot therefore counts as a recent shot for the de-duplicator, so a second shot at the same spot straight after it is also discarded. Both would be dropped anyway unless they straddle the projection's edge. *Cost:* negligible.

### Also in this revision (Task 3)

15. **Shift+Up grows the height and Shift+Down shrinks it**, about the centre, with the same 10-unit floor (spec §9). Shift+Right and Shift+Left are unchanged. `TestTargetEditor.arrowsMoveTheSelectedTargetByOneAndShiftArrowsResizeItAboutItsCenter` is updated to match.
16. **Logging:** `<logger name="com.shootoff.compose.courses" level="INFO"/>` in `compose-app/src/main/resources/logback.xml`. The "left out" lines are already WARN (the root level) and show today; this adds the INFO lines: "Restored the arena layout from …", "No remembered arena layout …", and "The arena layout changed before it could be restored …". A test pins the effective level, since `compose-app`'s tests run under this same file.

**Changes to JavaFX behaviour** (it shares `core`), once calibrated:
- A shot on a camera-feed target registers on it even inside the projection.
- With "Only detect shots in projector bounds", misses beside the projection are dropped, and the camera searches the whole frame while the feed has targets.

**For the owner to decide** (none blocks the plan): whether hidden feed targets should count (ruling 9).

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** plain sentences in the repo's style (e.g. "Hold the exposure at one frame for the whole pattern search"), with **no `Co-Authored-By` or any other trailer**. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage or modify `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`. Never `git stash`, `git restore` or `git reset`.
- **The owner's files.** Never modify, move, delete or stage:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - anything under `exercises/` or `exercise-data/`
  - `arena-layout.course` in the ShootOFF folder, and any file under `courses/` or `targets/`
  - the owner's Java preferences
  Checksums are in `build/plan8-owner-files.sha256`: `sha256sum -c build/plan8-owner-files.sha256` must print OK for every line.
- **Tests and the owner's settings:** tests use scratch directories (`@TempDir`, `ArenaFiles.scratch()`) and `ScratchConfig` (or `new Settings(new String[0])`, which reads no file), never the owner's `shootoff.properties`, `shootoff.home`, `courses/` or `arena-layout.course`. Tests read the bundled `targets/` only.
- **Code style:**
  - Kotlin: the official style (4 spaces, trailing commas).
  - Java: tabs; match the surrounding code.
  - A new main-source file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (this plan adds none). Test files have no header.
  - Keep imports sorted as each file sorts them.
- **Packages:** `com.shootoff.*`; no new packages.
- **Dependencies:** none added anywhere. No OpenJFX in `compose-app` (`TestNoJavaFxInComposeApp` keeps passing).
- **Formats:** `.target`, `.course`, the session formats and `shootoff.properties` keys are unchanged. No settings key is added.
- **The exercise API is unchanged.** `plugin-api` has no edit.
- **Publishing:** none. Never publish to `~/.m2`.
- Implementers never run the GUI apps (`./gradlew run`, `:compose-app:run`, `:javafx-app:run`); Task 4 is the owner's.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper.
- **Test gate** (run in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10`; Bash timeout 600000 ms):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. The count starts at **926** (end of Plan 11); each task gives the count it ends at. A lower count means tests silently stopped running.
  - `core`'s `TestMalfunctionsProcessor.testManyMalfunctions` is probabilistic. If it alone fails, run the gate again.
  - The Compose and JavaFX UI tests need a display; the gate runs them on this machine's `DISPLAY=:0`.
  - After the gate, `git status --short` shows only ` M shootoff.properties` plus the task's own files (and the owner's untracked `arena-layout.course` and `courses/mine.course`, never staged), and `sha256sum -c build/plan8-owner-files.sha256` prints all OK.

## Review Focus

1. **A camera target added or removed while the laser is firing.** The detection area flips between the projection and the whole frame mid-session. Expected: every shot lands where it was fired (no shot offset by the wrong area), and the switch itself raises no phantom shot.
   - *Tests:* `TestCameraManagerDetectionArea.aShotIsOffsetByTheAreaItWasFoundInEvenIfTheTargetsChangeMeanwhile`, `theDetectorStartsAfreshOnlyWhenTheAreaChanges` (Task 2).
2. **Malfunctions or the virtual magazine on, and a miss beside the screen.** Expected: the miss uses no round, can't malfunction, and leaves no row, marker or session event; the next real shot's row doesn't say "after a malfunction".
   - *Tests:* `TestShotPipeline.aMissOutsideTheProjectionIsDroppedWithoutATraceWhenDetectingOnlyInBounds`, `aDroppedShotUsesNoRoundOfTheVirtualMagazine` (Task 1).
3. **A drill that puts its own targets on the feed** (or hides the shooter's). Expected: the drill's feed targets count as camera targets (the camera searches the whole frame and hits on them register), and removing the last target narrows the search again.
   - *Test:* `TestSurfaces.theFeedHasTargetsWhileAnyTargetIsOnIt` (Task 2).
4. **A camera target drawn over the projected area, on top of an arena target.** Expected: the camera target takes the shot, with its sound and row; the arena target underneath gets nothing.
   - *Tests:* `TestShotPipeline.aShotOnACameraTargetInsideTheProjectionIsTheCameraTargetsEvenOverAnArenaTarget` (Task 1), `TestSurfaces.aShotOnAFeedTargetInsideTheProjectionIsTheFeedTargetsNotTheArenas` (Task 1).
5. **The owner switches to "Detect shots everywhere" or "Crop feed to projector".** Expected: a miss beside the projection is a feed miss (a row and a marker) as before, and a hit on a camera target registers with any of the three options.
   - *Tests:* `TestShotPipeline.aMissOutsideTheProjectionIsTheCameraFeedsWhenNotDetectingOnlyInBounds`, `aShotOnACameraTargetOutsideTheProjectionIsTheCameraTargetsWithEveryCalibrationOption` (Task 1); `TestCameraManagerDetectionArea.everywhereLooksAtTheWholeFrameAndCropAtTheProjectionWhateverTheTargets` (Task 2).

Task 4 covers what no unit test reaches: real laser shots on camera targets beside and over the screen, their sounds, and the frame rate while the camera searches the whole frame.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/…/shots/ShotPipeline.java` | the route: feed target first, then the arena, then drop or feed miss | 1 |
| `core/…/camera/{CameraView, CameraManager}.java`, `core/…/camera/shotdetection/ShotDetector.java` | `hasTargets()`; the detection area per frame; the offset and the detector's restart | 2 |
| `…/compose/feed/ComposeCameraView.kt`, `…/compose/shots/Surfaces.kt` | the Compose feed answers `hasTargets()` | 2 |
| `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` | the JavaFX feed answers `hasTargets()` | 2 |
| `…/compose/targets/TargetEditor.kt`, `compose-app/src/main/resources/logback.xml` | Shift+Up taller; the layout memory's INFO lines | 3 |
| none | the owner's hardware check | 4 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; its tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`; its tests are under `core/src/test/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | Test methods added | Gate |
|---|---|---|
| 1 | `core` `shots/TestShotPipeline` (+6, 3 changed), `compose` `shots/TestSurfaces` (+2) | 934 |
| 2 | `core` `camera/TestCameraManagerDetectionArea` (+4, new), `javafx` `gui/TestCanvasManager` (+1), `compose` `feed/TestComposeCameraView` (+1), `compose` `shots/TestSurfaces` (+1) | 941 |
| 3 | `compose` `courses/TestLayoutMemory` (+1); `targets/TestTargetEditor` one test changed | 942 |

**Suggested models** (implementer / reviewer): Task 1 sonnet / opus (the core routing everything shares); Task 2 sonnet / opus (the camera thread, offsets and both apps); Task 3 haiku / sonnet.

---

### Task 1: Where a shot on the camera feed goes

**Files:**
- Modify: `core/src/main/java/com/shootoff/shots/ShotPipeline.java` (the class KDoc, `addShot`, a new `Route` record and `route`, `hitTest` split into `hitTest` and `record`)
- Test: `core/src/test/java/com/shootoff/shots/TestShotPipeline.java`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt`

**Interfaces:**
- Consumes: `ShotPipeline.Surface.hitTest(double, double)`, `Surface.arena()`, `Arena.projection()` (unchanged); `Settings.getCalibratedFeedBehavior()`, `CalibrationOption.ONLY_IN_BOUNDS`.
- Produces: no new public API. `ShotPipeline.addShot(S, boolean)` follows spec §9's three rules once `surface.arena()` has a `projection()`; `public Optional<Hit> hitTest(S, Optional<String>, boolean)` is unchanged (`CanvasManager.checkHit` uses it).

**Why.** Spec §9: a shot on a camera target is that target's wherever it lands; otherwise inside the projection it is the arena's; otherwise, with "Only detect shots in projector bounds", it is dropped before it leaves any trace. Rulings 1–7.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/shots/TestShotPipeline.java`:

Replace:

```java
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
```

with:

```java
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
```

Replace:

```java
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
```

with:

```java
	@Test
	void aShotInsideTheProjectionGoesToTheArenaInArenaCoordinates() {
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(10, 10, Map.of());
		final Point onArena = ArenaGeometry.canvasToArena(381, 235, PROJECTION, ARENA);
		arena.addBox(onArena.getX() - 5, onArena.getY() - 5, Map.of());

		cameraPipeline.addShot(shot(381, 235), false);

		// The camera feed looks for a target of its own first, then shows the shot and rows it; the arena
		// hit-tests, records and delivers it
		assertEquals(List.of("Camera 1 hit test", "Camera 1 row", "Camera 1 shows (381, 235)",
				"arena shows (" + (int) onArena.getX() + ", " + (int) onArena.getY() + ")", "arena hit test",
				"arena delivers a hit on the arena"), events);
		assertEquals(List.of(), recorded("Camera 1"));
```

Replace:

```java

		assertEquals(List.of("arena shows (640, 360)", "arena shows (1280, 720)"),
				events.stream().filter(event -> event.startsWith("arena shows")).toList());
		assertFalse(events.contains("Camera 1 hit test"));
	}

	@Test
```

with:

```java

		assertEquals(List.of("arena shows (640, 360)", "arena shows (1280, 720)"),
				events.stream().filter(event -> event.startsWith("arena shows")).toList());
		assertFalse(events.stream().anyMatch(event -> event.startsWith("Camera 1 delivers")));
	}

	@Test
```

Replace:

```java

		cameraPipeline.addShot(shot(50, 50), false);

		assertEquals(List.of("Camera 1 row", "Camera 1 shows (50, 50)", "Camera 1 hit test", "Camera 1 delivers a hit"),
				events);
		assertEquals(List.of(), recorded("arena"));
	}

	@Test
```

with:

```java

		cameraPipeline.addShot(shot(50, 50), false);

		// Once the arena is calibrated, the camera feed's own targets are hit-tested first, once
		assertEquals(List.of("Camera 1 hit test", "Camera 1 row", "Camera 1 shows (50, 50)", "Camera 1 delivers a hit"),
				events);
		assertEquals(List.of(), recorded("arena"));
	}

	// Spec §9 rule 1: a camera target over the projected area takes the shot, even over an arena target
	@Test
	void aShotOnACameraTargetInsideTheProjectionIsTheCameraTargetsEvenOverAnArenaTarget() {
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(360, 220, Map.of("command", "play_sound(sounds/metal_clang.wav)"));
		final Point onArena = ArenaGeometry.canvasToArena(381, 235, PROJECTION, ARENA);
		arena.addBox(onArena.getX() - 5, onArena.getY() - 5, Map.of());

		cameraPipeline.addShot(shot(381, 235), false);

		assertEquals(List.of("Camera 1 hit test", "Camera 1 row", "Camera 1 shows (381, 235)",
				"Camera 1 runs [RegionCommand[name=play_sound, args=[sounds/metal_clang.wav]]]", "Camera 1 delivers a hit"),
				events);
		assertEquals(Optional.of(0), recorded("Camera 1").get(0).getTargetIndex());
		assertEquals(List.of(), recorded("arena"));
	}

	// Spec §9 rule 1: beside the projection, a camera target takes the shot whatever the calibration option
	@Test
	void aShotOnACameraTargetOutsideTheProjectionIsTheCameraTargetsWithEveryCalibrationOption() {
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(40, 40, Map.of());

		for (final CalibrationOption option : CalibrationOption.values()) {
			settings.setCalibratedFeedBehavior(option);
			events.clear();

			cameraPipeline.addShot(shot(50, 50), false);

			assertEquals(List.of("Camera 1 hit test", "Camera 1 row", "Camera 1 shows (50, 50)", "Camera 1 delivers a hit"),
					events, option.name());
		}
		assertEquals(3, recorded("Camera 1").size());
	}

	// Spec §9 rule 3: with "Only detect shots in projector bounds", a shot beside the projection on no camera
	// target is dropped: no row, no marker, no miss, no session event, and no malfunction
	@Test
	void aMissOutsideTheProjectionIsDroppedWithoutATraceWhenDetectingOnlyInBounds() {
		settings.setCalibratedFeedBehavior(CalibrationOption.ONLY_IN_BOUNDS);
		settings.setMalfunctions(true);
		settings.setMalfunctionsProbability(100);
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(40, 40, Map.of());

		cameraPipeline.addShot(shot(20, 300), false);

		assertEquals(List.of("Camera 1 hit test"), events);
		assertEquals(List.of(), recorded("Camera 1"));
		assertEquals(List.of(), recorded("arena"));

		// Nor does the next row say a malfunction came before it
		settings.setMalfunctions(false);
		cameraPipeline.addShot(shot(50, 50), false);
		assertEquals(List.of("Camera 1 row"), events.stream().filter(event -> event.contains("row")).toList());
	}

	// Spec §9 rule 3: a dropped shot uses no round of the virtual magazine
	@Test
	void aDroppedShotUsesNoRoundOfTheVirtualMagazine() {
		settings.setCalibratedFeedBehavior(CalibrationOption.ONLY_IN_BOUNDS);
		settings.setUseVirtualMagazine(true);
		settings.setVirtualMagazineCapacity(1);
		for (final ShotProcessor processor : settings.getShotProcessors()) {
			if (processor instanceof VirtualMagazineProcessor magazine) magazine.setUseTTS(false);
		}
		camera.arena = Optional.of(new FakeArena());
		camera.addBox(40, 40, Map.of());

		cameraPipeline.addShot(shot(20, 300), false);
		cameraPipeline.addShot(shot(50, 50), false);

		assertEquals(List.of("Camera 1 row"), events.stream().filter(event -> event.contains("row")).toList());
		assertEquals(List.of(false), recorded("Camera 1").stream().map(ShotEvent::isReload).toList());
	}

	// Spec §9 rule 3: "Detect shots everywhere" (and "Crop feed to projector", unchanged) keep a miss beside the
	// projection on the camera feed
	@Test
	void aMissOutsideTheProjectionIsTheCameraFeedsWhenNotDetectingOnlyInBounds() {
		camera.arena = Optional.of(new FakeArena());

		for (final CalibrationOption option : List.of(CalibrationOption.EVERYWHERE, CalibrationOption.CROP)) {
			settings.setCalibratedFeedBehavior(option);
			events.clear();

			cameraPipeline.addShot(shot(20, 300), false);

			assertEquals(List.of("Camera 1 hit test", "Camera 1 row", "Camera 1 shows (20, 300)", "Camera 1 delivers a miss"),
					events, option.name());
		}
		assertEquals(2, recorded("Camera 1").size());
	}

	// A copy of a shot is routed the same way: a mirrored miss beside the projection is dropped too
	@Test
	void aMirroredMissOutsideTheProjectionIsDroppedWhenDetectingOnlyInBounds() {
		settings.setCalibratedFeedBehavior(CalibrationOption.ONLY_IN_BOUNDS);
		camera.arena = Optional.of(new FakeArena());

		cameraPipeline.addShot(shot(20, 300), true);

		assertEquals(List.of("Camera 1 hit test"), events);
	}

	@Test
```

`compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt`:

Replace:

```kotlin
        assertEquals(listOf(640.0 to 360.0), arena.markers.markers.value.map { it.x to it.y })
    }

    @Test
    fun withoutACalibrationOrAnArenaShotsStayOnTheFeed() {
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
```

with:

```kotlin
        assertEquals(listOf(640.0 to 360.0), arena.markers.markers.value.map { it.x to it.y })
    }

    // Spec §9 rule 1: a feed target over the projected area takes the shot, not the arena target under it
    @Test
    fun aShotOnAFeedTargetInsideTheProjectionIsTheFeedTargetsNotTheArenas() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        val (arenaBox, arenaPlacement) = box(620.0, 340.0)
        arena.targets.add(arenaBox, ResourceResolver.files(), arenaPlacement)
        val (feedBox, feedPlacement) = box(240.0, 170.0)
        val feedTarget = feed.targets.add(feedBox, ResourceResolver.files(), feedPlacement)

        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))

        val delivered = fixture.delivered.single()
        assertFalse(delivered.arenaShot)
        assertEquals(feedTarget.id, delivered.hit!!.targetId())
        assertEquals(1, feed.timer.rows.value.size)
        assertEquals(listOf(260.0 to 190.0), feed.markers.markers.value.map { it.x to it.y })
        assertEquals(emptyList<Marker>(), arena.markers.markers.value)
    }

    // Spec §9 rule 3: "Only detect shots in projector bounds" is the default; a miss beside the projection
    // leaves no row, no marker and no miss for the exercise
    @Test
    fun aMissBesideTheProjectionIsDroppedWhenDetectingOnlyInBounds() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        val (definition, placement) = box(10.0, 10.0)
        feed.targets.add(definition, ResourceResolver.files(), placement)

        feed.add(ScaledShot(ShotColor.RED, 60.0, 400.0, 1000))

        assertEquals(emptyList<RowView>(), feed.timer.rows.value)
        assertEquals(emptyList<Marker>(), feed.markers.markers.value)
        assertEquals(emptyList<SurfaceFixture.Delivered>(), fixture.delivered.toList())
    }

    @Test
    fun withoutACalibrationOrAnArenaShotsStayOnTheFeed() {
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.shots.TestShotPipeline --console=plain`

Expected: FAIL, 8 of 17: the six new tests and the three changed ones except `aShotOnTheProjectionsEdgeGoesToTheArenaAtTheArenasCurrentSize` (e.g. `aShotInsideTheProjectionGoesToTheArenaInArenaCoordinates` expected `[Camera 1 hit test, Camera 1 row, …]` but was `[Camera 1 row, …]`).

- [ ] **Step 3: Route the shot first**

`core/src/main/java/com/shootoff/shots/ShotPipeline.java`:

Replace:

```java
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.recorders.ShotRecorder;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
```

with:

```java
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.recorders.ShotRecorder;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.CalibrationOption;
import com.shootoff.config.Settings;
import com.shootoff.geom.ArenaGeometry;
import com.shootoff.geom.Point;
```

Replace:

```java
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
```

with:

```java
 * Everything between a detected shot and the running exercise, for one surface (a camera feed, the
 * arena, or a copy of the arena that mirrors it):
 * <ol>
 * <li>where the shot goes, while this camera feed's arena is calibrated: a shot on one of the feed's own
 * targets is the feed's, wherever it lands; otherwise a shot inside the arena's projection goes on to the
 * arena; otherwise, with "Only detect shots in projector bounds", the shot is dropped here, before
 * anything below, as if it had not been detected</li>
 * <li>the shot processors (malfunctions, the virtual magazine); a rejected shot is recorded as such and
 * goes no further</li>
 * <li>the laser sound</li>
 * <li>the shot timer row</li>
 * <li>the marker</li>
 * <li>a shot for the arena goes on to it, in arena coordinates; any other shot is hit-tested against this
 * surface's targets</li>
 * <li>session recording, the hit region's commands, and delivery to the running exercise</li>
 * </ol>
 * A <i>mirrored</i> shot is a copy of a shot another surface handles: it skips the processors, the
```

Replace:

```java
	 * Handles a shot on this surface, in its coordinates.
	 */
	public void addShot(S shot, boolean mirrored) {
		if (!mirrored) {
			final Optional<ShotProcessor> rejectingProcessor = processShot(shot);
			if (rejectingProcessor.isPresent()) {
```

with:

```java
	 * Handles a shot on this surface, in its coordinates.
	 */
	public void addShot(S shot, boolean mirrored) {
		final Route<S> route = route(shot);
		if (route.dropped()) {
			logger.debug("Processing Shot: Dropped ({}, {}): outside the projection and on no camera target",
					shot.getX(), shot.getY());
			return;
		}

		if (!mirrored) {
			final Optional<ShotProcessor> rejectingProcessor = processShot(shot);
			if (rejectingProcessor.isPresent()) {
```

Replace:

```java

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
```

with:

```java

		final Optional<String> videoString = createVideoString(shot);

		if (route.arena() != null) {
			final Arena<S> arena = route.arena();
			final Point arenaPoint = ArenaGeometry.canvasToArena(shot.getX(), shot.getY(), route.projection(),
					arena.size());
			arena.addArenaShot(arena.toArenaShot(shot, arenaPoint), videoString, mirrored);

			// The arena handled the shot
			return;
		}

		final Optional<Hit> hit = route.feedHit() != null ? record(shot, route.feedHit(), videoString, mirrored)
				: hitTest(shot, videoString, mirrored);
		runRegionCommands(shot, hit, mirrored);
		surface.deliver(shot, hit, false);
	}

	/**
	 * Where a shot goes: dropped; on to <tt>arena</tt>, inside its <tt>projection</tt>; or this surface's,
	 * with <tt>feedHit</tt> the hit test already made (a hit or a miss), or null when it is still to be made.
	 */
	private record Route<T extends Shot>(boolean dropped, Optional<Hit> feedHit, Arena<T> arena, Rect projection) {
		static <T extends Shot> Route<T> drop() {
			return new Route<>(true, null, null, null);
		}

		static <T extends Shot> Route<T> toSurface(Optional<Hit> feedHit) {
			return new Route<>(false, feedHit, null, null);
		}

		static <T extends Shot> Route<T> toArena(Arena<T> arena, Rect projection) {
			return new Route<>(false, null, arena, projection);
		}
	}

	/*
	 * Without a calibrated arena every shot is this surface's, hit-tested after its row and marker as always.
	 * With one (spec §9, Revision 1), this surface is hit-tested first: a hit on one of its own targets keeps
	 * the shot here wherever it is; otherwise a shot inside the projection goes on to the arena; otherwise it
	 * is a miss here, or, when the camera looks only inside the projection, dropped: it was seen only because
	 * the feed has targets (CameraManager.getDetectionArea), and none of them was hit.
	 */
	private Route<S> route(S shot) {
		final Optional<Arena<S>> arena = surface.arena();
		final Optional<Rect> projection = arena.flatMap(Arena::projection);
		if (projection.isEmpty()) return Route.toSurface(null);

		final Optional<Hit> feedHit = surface.hitTest(shot.getX(), shot.getY());
		if (feedHit.isPresent()) return Route.toSurface(feedHit);

		if (projection.get().contains(shot.getX(), shot.getY())) return Route.toArena(arena.get(), projection.get());

		if (CalibrationOption.ONLY_IN_BOUNDS.equals(settings.getCalibratedFeedBehavior())) return Route.drop();

		return Route.toSurface(feedHit);
	}

	/**
```

Replace:

```java
	 * unless it is mirrored.
	 */
	public Optional<Hit> hitTest(S shot, Optional<String> videoString, boolean mirrored) {
		final Optional<Hit> hit = surface.hitTest(shot.getX(), shot.getY());

		if (hit.isPresent()) {
			if (settings.inDebugMode()) {
				final Region region = hit.get().region();
```

with:

```java
	 * unless it is mirrored.
	 */
	public Optional<Hit> hitTest(S shot, Optional<String> videoString, boolean mirrored) {
		return record(shot, surface.hitTest(shot.getX(), shot.getY()), videoString, mirrored);
	}

	// Logs the hit test's outcome and records the shot in the session being recorded, unless it is mirrored
	private Optional<Hit> record(S shot, Optional<Hit> hit, Optional<String> videoString, boolean mirrored) {
		if (hit.isPresent()) {
			if (settings.inDebugMode()) {
				final Region region = hit.get().region();
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :core:test --tests com.shootoff.shots.TestShotPipeline :compose-app:test --tests com.shootoff.compose.shots.TestSurfaces --console=plain`

Expected: PASS, 17 tests in `TestShotPipeline` and 8 in `TestSurfaces`.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `934/934 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties`, the owner's untracked files, and this task's files).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/shots/ShotPipeline.java core/src/test/java/com/shootoff/shots/TestShotPipeline.java compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt
git commit -m "Give a camera target the shots that land on it once the arena is calibrated, and drop misses the camera wasn't looking for"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 2: Where the camera looks while the feed has targets

**Files:**
- Modify: `core/src/main/java/com/shootoff/camera/CameraView.java` (`hasTargets()`)
- Modify: `core/src/main/java/com/shootoff/camera/CameraManager.java` (the two flags `volatile`; `frameDetectionArea`, `lastDetectionArea`; `getDetectionArea()`, `detectionArea(Rect)`; `processFrame`'s detection block)
- Modify: `core/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java` (`addShot`'s offset)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` (`hasTargets()`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt` (`FeedShots.hasTargets()`, `ComposeCameraView.hasTargets()`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/shots/Surfaces.kt` (`FeedSurface.hasTargets()`)
- Create: `core/src/test/java/com/shootoff/camera/TestCameraManagerDetectionArea.java`
- Test: `core/src/test/java/com/shootoff/camera/RecordingCameraView.java`, `core/src/test/java/com/shootoff/shots/TestRangeReset.java` (the fakes implement `hasTargets()`)
- Test: `javafx-app/src/test/java/com/shootoff/gui/TestCanvasManager.java`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/feed/TestComposeCameraView.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt`

**Interfaces:**
- Consumes: Task 1's routing (a miss the whole-frame search finds beside the projection is dropped there); `TargetSet.size()` (synchronized); `SurfaceTargets.add(…, owner: TargetOwner)` (Plan 11).
- Produces:
  - `CameraView.hasTargets(): boolean`, abstract, called on the camera's thread for every frame.
  - `CameraManager.getDetectionArea(): Optional<Rect>`: the projection (on the camera's feed) while cropping, or while limiting with no targets on the view; empty for the whole frame; on the camera's thread during `processFrame`, that frame's area.
  - `FeedShots.hasTargets(): Boolean` (Kotlin interface; `FeedShots.None` false); `FeedSurface.hasTargets()`; `ComposeCameraView.hasTargets()`; `CanvasManager.hasTargets()`.
  - `RecordingCameraView.setHasTargets(boolean)` (test fixture).

**Why.** Spec §9 "Detection": with "Only detect shots in projector bounds" the camera must look beside the projection while the feed has targets, and shot coordinates stay in full-frame coordinates either way. Rulings 8–14.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/camera/RecordingCameraView.java`:

Replace:

```java
	private final List<String> diagnosticWarnings = new CopyOnWriteArrayList<>();
	private final Semaphore removedWarnings = new Semaphore(0);
	private final BlockingQueue<ScaledShot> shots = new LinkedBlockingQueue<>();

	@Override
	public void addShot(ScaledShot shot) {
```

with:

```java
	private final List<String> diagnosticWarnings = new CopyOnWriteArrayList<>();
	private final Semaphore removedWarnings = new Semaphore(0);
	private final BlockingQueue<ScaledShot> shots = new LinkedBlockingQueue<>();
	private volatile boolean hasTargets = false;

	@Override
	public void addShot(ScaledShot shot) {
```

Replace:

```java
	@Override
	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}

	public List<String> diagnosticWarnings() {
		return List.copyOf(diagnosticWarnings);
	}
```

with:

```java
	@Override
	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}

	@Override
	public boolean hasTargets() {
		return hasTargets;
	}

	public void setHasTargets(boolean hasTargets) {
		this.hasTargets = hasTargets;
	}

	public List<String> diagnosticWarnings() {
		return List.copyOf(diagnosticWarnings);
	}
```

`core/src/test/java/com/shootoff/shots/TestRangeReset.java`:

Replace:

```java

		@Override
		public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}
	}
}
```

with:

```java

		@Override
		public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}

		@Override
		public boolean hasTargets() {
			return false;
		}
	}
}
```

`core/src/test/java/com/shootoff/camera/TestCameraManagerDetectionArea.java` (new file):

```java
package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.camera.shotdetection.FrameProcessingShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;

/**
 * Where the camera looks for shots once the arena is calibrated (spec §9, Revision 1): with "Only detect shots in
 * projector bounds", the projection while the camera's view has no targets, the whole frame while it has some.
 */
class TestCameraManagerDetectionArea {
	private static final Rect PROJECTION = new Rect(100, 40, 200, 200);

	// A detector that writes down the size of each frame it is given and each time it starts afresh, and can
	// find a shot at a spot in the frame, running something first (a change landing mid-frame)
	private static final class AreaDetector extends FrameProcessingShotDetector {
		final List<String> frames = new CopyOnWriteArrayList<>();
		final AtomicInteger restarts = new AtomicInteger();
		volatile double[] shotAt = null;
		volatile Runnable beforeShot = () -> {};

		AreaDetector(CameraManager cameraManager, CameraView cameraView) {
			super(cameraManager, cameraView);
		}

		@Override
		public void processFrame(Frame frame, boolean isDetecting) {
			frames.add(frame.getOriginalMat().cols() + "x" + frame.getOriginalMat().rows());
			final double[] at = shotAt;
			if (at != null) {
				beforeShot.run();
				addShot(ShotColor.RED, at[0], at[1], frame.getTimestamp(), true);
			}
		}

		@Override
		public void setFrameSize(int width, int height) {
			restarts.incrementAndGet();
		}

		@Override
		protected boolean handlesBounds() {
			return true;
		}
	}

	private static final class AreaCamera extends MockCamera {
		AreaDetector detector;

		@Override
		public ShotDetector getPreferredShotDetector(CameraManager cameraManager, CameraView cameraView) {
			detector = new AreaDetector(cameraManager, cameraView);
			return detector;
		}
	}

	private final AreaCamera camera = new AreaCamera();
	private final RecordingCameraView view = new RecordingCameraView();
	private CameraManager manager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		new Settings(new String[0]);
		manager = new CameraManager(camera, null, view);
		manager.setProjectionBounds(PROJECTION);
	}

	private void frame() {
		manager.newFrame(new Frame(new Mat(480, 640, CvType.CV_8UC3), System.currentTimeMillis()), false);
	}

	private ScaledShot nextShot() throws InterruptedException {
		return view.awaitShot(5, TimeUnit.SECONDS).orElseThrow(() -> new AssertionError("no shot reached the view"));
	}

	@Test
	void onlyInBoundsLooksAtTheProjectionWhileTheViewHasNoTargetsAndTheWholeFrameWhileItHasSome() {
		manager.setLimitDetectProjection(true);

		frame();
		view.setHasTargets(true);
		frame();
		view.setHasTargets(false);
		frame();

		assertEquals(List.of("200x200", "640x480", "200x200"), camera.detector.frames);
		assertEquals(Optional.of(PROJECTION), manager.getDetectionArea());
		view.setHasTargets(true);
		assertEquals(Optional.empty(), manager.getDetectionArea());
	}

	@Test
	void everywhereLooksAtTheWholeFrameAndCropAtTheProjectionWhateverTheTargets() {
		view.setHasTargets(true);
		frame();
		view.setHasTargets(false);
		frame();

		manager.setCropFeedToProjection(true);
		frame();
		view.setHasTargets(true);
		frame();

		assertEquals(List.of("640x480", "640x480", "200x200", "200x200"), camera.detector.frames);
	}

	@Test
	void theDetectorStartsAfreshOnlyWhenTheAreaChanges() {
		manager.setLimitDetectProjection(true);
		final int before = camera.detector.restarts.get();

		frame();
		frame();
		assertEquals(before + 1, camera.detector.restarts.get());

		view.setHasTargets(true);
		frame();
		frame();
		assertEquals(before + 2, camera.detector.restarts.get());
	}

	@Test
	void aShotIsOffsetByTheAreaItWasFoundInEvenIfTheTargetsChangeMeanwhile() throws InterruptedException {
		manager.setLimitDetectProjection(true);

		// Found in the projection's sub-image: offset onto the feed, though a target arrives before it is added
		camera.detector.shotAt = new double[] { 10, 10 };
		camera.detector.beforeShot = () -> view.setHasTargets(true);
		frame();
		final ScaledShot inProjection = nextShot();
		assertEquals(110, inProjection.getX(), 0.001);
		assertEquals(50, inProjection.getY(), 0.001);

		// Found in the whole frame: where it is, though the last target goes before it is added
		camera.detector.shotAt = new double[] { 500, 400 };
		camera.detector.beforeShot = () -> view.setHasTargets(false);
		frame();
		final ScaledShot inFrame = nextShot();
		assertEquals(500, inFrame.getX(), 0.001);
		assertEquals(400, inFrame.getY(), 0.001);
	}
}
```

`javafx-app/src/test/java/com/shootoff/gui/TestCanvasManager.java`:

Replace:

```java
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.ScratchConfig;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Hit;
```

with:

```java
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.ScratchConfig;
import com.shootoff.geom.Rect;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Hit;
```

Replace:

```java
		cm.removeTarget(ipscTarget);

		assertEquals(0, cm.getTargets().size());
	}

	@Test
```

with:

```java
		cm.removeTarget(ipscTarget);

		assertEquals(0, cm.getTargets().size());
	}

	// Spec §9, Revision 1: while the feed has a target, its camera looks at the whole frame
	@Test
	public void testTheCameraLooksAtTheWholeFrameOnlyWhileTheFeedHasTargets() {
		final CameraManager cameraManager = cm.getCameraManager();
		cameraManager.setLimitDetectProjection(true);
		cameraManager.setProjectionBounds(new Rect(100, 40, 200, 200));

		assertTrue(cm.hasTargets());
		assertEquals(Optional.empty(), cameraManager.getDetectionArea());

		cm.removeTarget(ipscTarget);

		assertFalse(cm.hasTargets());
		assertEquals(Optional.of(new Rect(100, 40, 200, 200)), cameraManager.getDetectionArea());
	}

	@Test
```

`compose-app/src/test/kotlin/com/shootoff/compose/feed/TestComposeCameraView.kt`:

Replace:

```kotlin
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
```

with:

```kotlin
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
```

Replace:

```kotlin
class TestComposeCameraView {
    private val feed = FeedState(Size(640.0, 480.0))
    private val events = mutableListOf<String>()
    private lateinit var view: ComposeCameraView

    @BeforeEach
```

with:

```kotlin
class TestComposeCameraView {
    private val feed = FeedState(Size(640.0, 480.0))
    private val events = mutableListOf<String>()
    private var feedHasTargets = false
    private lateinit var view: ComposeCameraView

    @BeforeEach
```

Replace:

```kotlin
            override fun reset() {
                events += "reset"
            }
        })
        CameraManager(MockCamera(), null, view)
    }

    @Test
```

with:

```kotlin
            override fun reset() {
                events += "reset"
            }

            override fun hasTargets() = feedHasTargets
        })
        CameraManager(MockCamera(), null, view)
    }

    // Spec §9, Revision 1: the camera asks its view whether the feed has targets
    @Test
    fun theViewHasTargetsWhileItsFeedDoes() {
        assertFalse(view.hasTargets())
        feedHasTargets = true
        assertTrue(view.hasTargets())
        assertFalse(ComposeCameraView("C270", feed).hasTargets())
    }

    @Test
```

`compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt`:

Replace:

```kotlin
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.targets.RegionKey
import com.shootoff.geom.Rect
import com.shootoff.shots.ShotQueue
import com.shootoff.targets.model.Placement
```

with:

```kotlin
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.targets.RegionKey
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.geom.Rect
import com.shootoff.shots.ShotQueue
import com.shootoff.targets.model.Placement
```

Replace:

```kotlin
        assertEquals(emptyList<SurfaceFixture.Delivered>(), fixture.delivered.toList())
    }

    @Test
    fun withoutACalibrationOrAnArenaShotsStayOnTheFeed() {
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
```

with:

```kotlin
        assertEquals(emptyList<SurfaceFixture.Delivered>(), fixture.delivered.toList())
    }

    // Spec §9, Revision 1: the camera looks at its whole frame while the feed has a target, an exercise's too
    @Test
    fun theFeedHasTargetsWhileAnyTargetIsOnIt() {
        assertFalse(feed.hasTargets())
        val (definition, placement) = box(10.0, 10.0)
        val shooters = feed.targets.add(definition, ResourceResolver.files(), placement)
        assertTrue(feed.hasTargets())
        val exercises = feed.targets.add(definition, ResourceResolver.files(), placement, TargetOwner.EXERCISE)
        feed.targets.set.remove(shooters.id)
        assertTrue(feed.hasTargets())
        feed.targets.set.remove(exercises.id)
        assertFalse(feed.hasTargets())
    }

    @Test
    fun withoutACalibrationOrAnArenaShotsStayOnTheFeed() {
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.camera.TestCameraManagerDetectionArea --console=plain`

Expected: FAIL to compile: `RecordingCameraView.hasTargets` "does not override or implement a method from a supertype", and `getDetectionArea()` "cannot find symbol".

- [ ] **Step 3: Ask the view, fix each frame's area, and answer in both apps**

`core/src/main/java/com/shootoff/camera/CameraView.java`:

Replace:

```java
	public void setCameraManager(CameraManager cameraManager);

	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds);
}
```

with:

```java
	public void setCameraManager(CameraManager cameraManager);

	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds);

	/**
	 * @return whether the view has targets of its own, an exercise's included, shown or hidden. While it does,
	 *         a camera that limits detection to the arena's projection looks at the whole frame, so that shots
	 *         on those targets beside the projection are seen (see {@link CameraManager#getDetectionArea}).
	 *         Called on the camera's thread for every frame: it must be quick and safe to call from any
	 *         thread.
	 */
	public boolean hasTargets();
}
```

`core/src/main/java/com/shootoff/camera/CameraManager.java`:

Replace:

```java
	private final AtomicBoolean isDetecting = new AtomicBoolean(true);
	private final AtomicBoolean isCalibrating = new AtomicBoolean(false);
	private boolean shownBrightnessWarning = false;
	private boolean cropFeedToProjection = false;
	private boolean limitDetectProjection = false;

	protected Optional<Integer> minimumShotDimension = Optional.empty();

```

with:

```java
	private final AtomicBoolean isDetecting = new AtomicBoolean(true);
	private final AtomicBoolean isCalibrating = new AtomicBoolean(false);
	private boolean shownBrightnessWarning = false;
	// Set on the UI thread, read on the camera's for every frame
	private volatile boolean cropFeedToProjection = false;
	private volatile boolean limitDetectProjection = false;

	// Where the frame this thread is processing is searched for shots (processFrame), so that a shot found in it
	// is offset by that area, even if the view's targets change meanwhile; null outside processFrame
	private final ThreadLocal<Optional<Rect>> frameDetectionArea = new ThreadLocal<>();
	// The camera's thread only: the area the last frame was searched in
	private Optional<Rect> lastDetectionArea = Optional.empty();

	protected Optional<Integer> minimumShotDimension = Optional.empty();

```

Replace:

```java

	public Optional<Rect> getProjectionBounds() {
		return projectionBounds;
	}

	public boolean startRecordingStream(File videoFile) {
```

with:

```java

	public Optional<Rect> getProjectionBounds() {
		return projectionBounds;
	}

	/**
	 * Where the camera looks for shots, on its feed: the projection while the feed is cropped to it, or while
	 * detection is limited to it and the camera's view has no targets of its own (spec §9, Revision 1: with
	 * targets on the feed the camera looks at the whole frame, so shots on them beside the projection are seen,
	 * and the shot pipeline drops the rest). Empty for the whole frame.
	 * <p>
	 * On the camera's thread while it processes a frame, it is the area that frame is searched in.
	 */
	public Optional<Rect> getDetectionArea() {
		final Optional<Rect> frameArea = frameDetectionArea.get();
		if (frameArea != null) return frameArea;

		final Rect bounds;
		synchronized (projectionBoundsLock) {
			bounds = projectionBounds.orElse(null);
		}
		return detectionArea(bounds);
	}

	private Optional<Rect> detectionArea(Rect projectionBounds) {
		if (projectionBounds == null) return Optional.empty();
		if (cropFeedToProjection) return Optional.of(projectionBounds);
		if (limitDetectProjection && (cameraView == null || !cameraView.hasTargets())) return Optional.of(projectionBounds);
		return Optional.empty();
	}

	public boolean startRecordingStream(File videoFile) {
```

Replace:

```java
			}
		}

		if ((isLimitingDetectionToProjection() || isCroppingFeedToProjection()) && projectionBounds != null) {
			if (submatFrameBGR == null) {
				try {
					submatFrameBGR = currentFrame.getOriginalMat().submat((int) projectionBounds.getMinY(),
							(int) projectionBounds.getMaxY(), (int) projectionBounds.getMinX(),
							(int) projectionBounds.getMaxX());
				} catch (CvException e) {
					logger.error("Failed to get submat for frame to limit detection bounds, projectionBounds = "
							+ projectionBounds.toString() + ", frameSize = "
							+ currentFrame.getOriginalMat().size().toString(), e);
				}
			}

			if (shotDetector instanceof FrameProcessingShotDetector) {
				if (submatFrameBGR != null) {
					((FrameProcessingShotDetector) shotDetector)
							.processFrame(new Frame(submatFrameBGR, currentFrame.getTimestamp()), isDetecting.get());
				} else {
					logger.warn("Due to errors fetching frame submat, falling back to using full frame");
					((FrameProcessingShotDetector) shotDetector).processFrame(currentFrame, isDetecting.get());
				}
			}
		} else {
			if (shotDetector instanceof FrameProcessingShotDetector)
				((FrameProcessingShotDetector) shotDetector).processFrame(currentFrame, isDetecting.get());
		}

		// currentFrame is showing the colored pixels for brightness and motion,
```

with:

```java
			}
		}

		final Optional<Rect> detectionArea = detectionArea(projectionBounds);
		if (!detectionArea.equals(lastDetectionArea)) {
			lastDetectionArea = detectionArea;
			// The detector's per-pixel averages are of the other area's pixels: they start afresh, so that the
			// change doesn't look like a shot
			if (shotDetector instanceof FrameProcessingShotDetector) shotDetector.setFrameSize(getFeedWidth(), getFeedHeight());
		}

		frameDetectionArea.set(detectionArea);
		try {
			if (detectionArea.isPresent()) {
				if (submatFrameBGR == null) {
					try {
						submatFrameBGR = currentFrame.getOriginalMat().submat((int) projectionBounds.getMinY(),
								(int) projectionBounds.getMaxY(), (int) projectionBounds.getMinX(),
								(int) projectionBounds.getMaxX());
					} catch (CvException e) {
						logger.error("Failed to get submat for frame to limit detection bounds, projectionBounds = "
								+ projectionBounds.toString() + ", frameSize = "
								+ currentFrame.getOriginalMat().size().toString(), e);
					}
				}

				if (shotDetector instanceof FrameProcessingShotDetector) {
					if (submatFrameBGR != null) {
						((FrameProcessingShotDetector) shotDetector)
								.processFrame(new Frame(submatFrameBGR, currentFrame.getTimestamp()), isDetecting.get());
					} else {
						logger.warn("Due to errors fetching frame submat, falling back to using full frame");
						// Shots found in the full frame need no offset
						frameDetectionArea.set(Optional.empty());
						((FrameProcessingShotDetector) shotDetector).processFrame(currentFrame, isDetecting.get());
					}
				}
			} else {
				if (shotDetector instanceof FrameProcessingShotDetector)
					((FrameProcessingShotDetector) shotDetector).processFrame(currentFrame, isDetecting.get());
			}
		} finally {
			frameDetectionArea.remove();
		}

		// currentFrame is showing the colored pixels for brightness and motion,
```

`core/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java`:

Replace:

```java
package com.shootoff.camera.shotdetection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

with:

```java
package com.shootoff.camera.shotdetection;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Replace:

```java

		}

		if (scaleShot && (cameraManager.isLimitingDetectionToProjection() || cameraManager.isCroppingFeedToProjection())
				&& cameraManager.getProjectionBounds().isPresent()) {
			final Rect b = cameraManager.getProjectionBounds().get();

			if (handlesBounds()) {
				shot.adjustBounds(b.getMinX(), b.getMinY());
```

with:

```java

		}

		// The area the camera searched: a shot found in the projection's sub-image is offset back onto the feed
		final Optional<Rect> area = scaleShot ? cameraManager.getDetectionArea() : Optional.empty();
		if (area.isPresent()) {
			final Rect b = area.get();

			if (handlesBounds()) {
				shot.adjustBounds(b.getMinX(), b.getMinY());
```

`javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java`:

Replace:

```java
		}

		Platform.runLater(() -> background.setImage(img));
	}

	public void updateBackground(Image img) {
```

with:

```java
		}

		Platform.runLater(() -> background.setImage(img));
	}

	/**
	 * Whether this canvas has targets, an exercise's included: its camera then looks at the whole frame, even
	 * when it looks only inside the projection otherwise. Called on the camera's thread; the target set is safe
	 * to read from any thread.
	 */
	@Override
	public boolean hasTargets() {
		return targetSet.size() > 0;
	}

	public void updateBackground(Image img) {
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt`:

Replace:

```kotlin

    fun reset()

    object None : FeedShots {
        override fun add(shot: ScaledShot) {}

        override fun clear() {}

        override fun reset() {}
    }
}

```

with:

```kotlin

    fun reset()

    /** Whether the feed has targets of its own; asked on the camera's thread for every frame */
    fun hasTargets(): Boolean

    object None : FeedShots {
        override fun add(shot: ScaledShot) {}

        override fun clear() {}

        override fun reset() {}

        override fun hasTargets() = false
    }
}

```

Replace:

```kotlin

    override fun clearShots() = shots.clear()

    override fun reset() = shots.reset()

    override fun close() = feed.clearFrame()
```

with:

```kotlin

    override fun clearShots() = shots.clear()

    override fun hasTargets() = shots.hasTargets()

    override fun reset() = shots.reset()

    override fun close() = feed.clearFrame()
```

`compose-app/src/main/kotlin/com/shootoff/compose/shots/Surfaces.kt`:

Replace:

```kotlin
        clear()
    }

    // ---- The pipeline's view of the feed

    override fun name(): String = name
```

with:

```kotlin
        clear()
    }

    /** The shooter's targets and an exercise's alike: while there are any, the camera looks at its whole frame */
    override fun hasTargets(): Boolean = targets.set.size() > 0

    // ---- The pipeline's view of the feed

    override fun name(): String = name
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :core:test --tests com.shootoff.camera.TestCameraManagerDetectionArea --tests com.shootoff.camera.shotdetection.TestShotDetector --tests com.shootoff.shots.TestRangeReset :compose-app:test --tests com.shootoff.compose.shots.TestSurfaces --tests com.shootoff.compose.feed.TestComposeCameraView :javafx-app:test --tests com.shootoff.gui.TestCanvasManager --console=plain`

Expected: PASS: 4 tests in `TestCameraManagerDetectionArea`, 4 in `TestShotDetector` (its `shotInsideProjectionIsOffsetByTheProjectionOrigin` now goes through `getDetectionArea()`), 9 in `TestSurfaces`, 6 in `TestComposeCameraView`, 17 in `TestCanvasManager`.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `941/941 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties`, the owner's untracked files, and this task's files).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/camera/CameraManager.java core/src/main/java/com/shootoff/camera/CameraView.java core/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java core/src/test/java/com/shootoff/camera/RecordingCameraView.java core/src/test/java/com/shootoff/camera/TestCameraManagerDetectionArea.java core/src/test/java/com/shootoff/shots/TestRangeReset.java javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java javafx-app/src/test/java/com/shootoff/gui/TestCanvasManager.java compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt compose-app/src/main/kotlin/com/shootoff/compose/shots/Surfaces.kt compose-app/src/test/kotlin/com/shootoff/compose/feed/TestComposeCameraView.kt compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt
git commit -m "Look for shots on the whole camera frame while the feed has targets, even when detecting only in the projector's bounds"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 3: Shift+Up makes a target taller, and the layout memory's lines in the log

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetEditor.kt` (`nudge` and its KDoc)
- Modify: `compose-app/src/main/resources/logback.xml`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetEditor.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt`

**Interfaces:**
- Consumes: `TargetEditor.nudge(arrow: Arrow, resize: Boolean)`, `TargetEditor.STEP`, `MIN_SIZE` (Plan 11); `LayoutMemory`'s and `CourseLoader`'s SLF4J loggers.
- Produces: no new API. `nudge(Arrow.UP, resize = true)` grows the height by `STEP`; `nudge(Arrow.DOWN, resize = true)` shrinks it.

**Why.** Spec §9 "Also in this revision". Rulings 15–16.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetEditor.kt`:

Replace:

```kotlin
        editor.nudge(Arrow.UP, resize = false)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)

        // Right and Down grow, Left and Up shrink
        editor.nudge(Arrow.RIGHT, resize = true)
        assertBounds(Rect(99.5, 100.0, 101.0, 50.0), target)
        editor.nudge(Arrow.DOWN, resize = true)
        assertBounds(Rect(99.5, 99.5, 101.0, 51.0), target)
        editor.nudge(Arrow.LEFT, resize = true)
        editor.nudge(Arrow.UP, resize = true)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)
    }

```

with:

```kotlin
        editor.nudge(Arrow.UP, resize = false)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)

        // Right and Up grow, Left and Down shrink (spec §9, Revision 1)
        editor.nudge(Arrow.RIGHT, resize = true)
        assertBounds(Rect(99.5, 100.0, 101.0, 50.0), target)
        editor.nudge(Arrow.UP, resize = true)
        assertBounds(Rect(99.5, 99.5, 101.0, 51.0), target)
        editor.nudge(Arrow.LEFT, resize = true)
        editor.nudge(Arrow.DOWN, resize = true)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)
    }

```

`compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt`:

Replace:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Future
```

with:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Future
```

Replace:

```kotlin
        Placement(10.0, 20.0, 1.0, 1.0, true),
        owner,
    )

    @Test
    fun theLayoutIsSavedOnceTheChangesStopNotOncePerChange() {
```

with:

```kotlin
        Placement(10.0, 20.0, 1.0, 1.0, true),
        owner,
    )

    // Spec §9, Revision 1: the app's log (logback.xml) shows the layout memory's info lines
    @Test
    fun theLayoutMemorysInfoLinesShowInTheAppsLog() {
        assertTrue(LoggerFactory.getLogger(LayoutMemory::class.java).isInfoEnabled)
        assertTrue(LoggerFactory.getLogger(CourseLoader::class.java).isInfoEnabled)
    }

    @Test
    fun theLayoutIsSavedOnceTheChangesStopNotOncePerChange() {
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.targets.TestTargetEditor --tests com.shootoff.compose.courses.TestLayoutMemory --console=plain`

Expected: FAIL: `arrowsMoveTheSelectedTargetByOneAndShiftArrowsResizeItAboutItsCenter` (the bounds after Shift+Up) and `theLayoutMemorysInfoLinesShowInTheAppsLog`.

- [ ] **Step 3: Swap Up and Down, and set the level**

`compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetEditor.kt`:

Replace:

```kotlin

    /**
     * An arrow key: moves the selected target by [STEP], or with [resize] (Shift held) makes it [STEP]
     * wider (Right) or narrower (Left), taller (Down) or shorter (Up), about its center, as the JavaFX app did.
     */
    fun nudge(arrow: Arrow, resize: Boolean) {
        val target = selectedTarget() ?: return
```

with:

```kotlin

    /**
     * An arrow key: moves the selected target by [STEP], or with [resize] (Shift held) makes it [STEP]
     * wider (Right) or narrower (Left), taller (Up) or shorter (Down), about its center (spec §9, Revision 1).
     */
    fun nudge(arrow: Arrow, resize: Boolean) {
        val target = selectedTarget() ?: return
```

Replace:

```kotlin
        when (arrow) {
            Arrow.LEFT -> width = max(width - STEP, min(MIN_SIZE, width))
            Arrow.RIGHT -> width += STEP
            Arrow.UP -> height = max(height - STEP, min(MIN_SIZE, height))
            Arrow.DOWN -> height += STEP
        }
        val center = Point(bounds.minX + bounds.width / 2, bounds.minY + bounds.height / 2)
        place(target, fitted(target, p, Rect(center.x - width / 2, center.y - height / 2, width, height)))
```

with:

```kotlin
        when (arrow) {
            Arrow.LEFT -> width = max(width - STEP, min(MIN_SIZE, width))
            Arrow.RIGHT -> width += STEP
            Arrow.UP -> height += STEP
            Arrow.DOWN -> height = max(height - STEP, min(MIN_SIZE, height))
        }
        val center = Point(bounds.minX + bounds.width / 2, bounds.minY + bounds.height / 2)
        place(target, fitted(target, p, Rect(center.x - width / 2, center.y - height / 2, width, height)))
```

`compose-app/src/main/resources/logback.xml`:

Replace:

```xml
  <!-- Auto-calibration's exposure step: whether it lowered the exposure or put it back -->
  <logger name="com.shootoff.camera.autocalibration" level="INFO"/>
  <logger name="com.shootoff.compose.app.AppState" level="INFO"/>

  <root level="warn">
    <appender-ref ref="STDOUT"/>
```

with:

```xml
  <!-- Auto-calibration's exposure step: whether it lowered the exposure or put it back -->
  <logger name="com.shootoff.camera.autocalibration" level="INFO"/>
  <logger name="com.shootoff.compose.app.AppState" level="INFO"/>
  <!-- The remembered arena layout: restored, and why a target or background was left out -->
  <logger name="com.shootoff.compose.courses" level="INFO"/>

  <root level="warn">
    <appender-ref ref="STDOUT"/>
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.targets.TestTargetEditor --tests com.shootoff.compose.courses.TestLayoutMemory --tests com.shootoff.compose.app.TestTargetsScreen --console=plain`

Expected: PASS, 20 tests in `TestLayoutMemory`; `TestTargetsScreen` (Shift+Right only) unchanged.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `942/942 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties`, the owner's untracked files, and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetEditor.kt compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetEditor.kt compose-app/src/main/resources/logback.xml compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt
git commit -m "Make Shift+Up grow a target's height, and show the layout memory's info lines in the log"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 4: The owner's hardware check

Not for a subagent. The owner runs `./gradlew :compose-app:run` with the projector and the C270, calibrated, with "Only detect shots in projector bounds" (the default), and reports back.

- [ ] **1. Beside the screen.** Targets → Camera. Add a target over a real paper target the camera sees beside the projected area. Shoot it with the laser: the hit registers (a shot timer row, a marker, and the target's sound or hit animation if it has one).
- [ ] **2. Over the projected area.** Add a camera target over part of the projection, with an arena target under it on the wall. Shoot the camera target: it takes the hit, and the arena target doesn't. Shoot the arena target where no camera target covers it: the arena takes it, as before.
- [ ] **3. A miss beside the screen.** Shoot the wall beside the projection, away from any camera target: nothing happens (no row, no marker). Turn on malfunctions or the virtual magazine if handy: such a miss uses no round.
- [ ] **4. No camera targets.** Targets → Camera → Clear. Shoot beside the projection: nothing, as before this plan. Shoot inside: the arena takes it.
- [ ] **5. Adding and removing mid-session.** With the laser off, add and remove a camera target a few times: no shot appears. The feed's frame rate (the camera's FPS, and no "too low for reliable shot detection" warning in the log) stays usable while a camera target is on.
- [ ] **6. Shift+Up / Shift+Down.** On the Targets screen, select a target: Shift+Up makes it taller, Shift+Down shorter.
- [ ] **7. The log.** Restart: the console shows "Restored the arena layout from …/arena-layout.course" (or "No remembered arena layout …").
- [ ] **8. The JavaFX app** (optional): `./gradlew :javafx-app:run`, calibrate, put a target on the camera feed beside the projection and shoot it: it registers.
- [ ] **9. The files.** `sha256sum -c build/plan8-owner-files.sha256` still prints OK for every line.
