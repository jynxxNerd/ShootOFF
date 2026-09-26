# Session Recording and Viewer Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Arena targets are recorded once, with valid indexes, and the session viewer replays any session (including old broken ones) without throwing.

**Architecture:**
- **Recording.** A `recordsSessionEvents` flag on `CanvasManager` silences the arena-tab mirror. Target adds are recorded where every target enters a canvas, `CanvasManager.addTarget(Target)`. `TargetView` records only for registered targets on recording canvases, and `SessionRecorder` ignores index -1.
- **Viewer.** `SessionCanvasManager` validates indexes, skips events it can't apply (symmetrically for undo), and never adds a node twice.

**Tech Stack:** Java 21, JavaFX 21, JUnit 5 (Jupiter) + JUnit 4 (vintage), Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-09-26-session-recording-fix-design.md`

## Global Constraints

- Branch `fix-session-recording` in `/home/bfears/projects/ShootOFF`. Never commit to `master`.
- No `Co-Authored-By` trailer in commit messages. Verify with `git log -1 --format=%B`.
- Never stage `shootoff.properties` (it has the owner's local edits). Stage files by path; never `git add -A` / `git add .`.
- No changes to the session file formats (XML/JSON) or the event model classes (`com.shootoff.session.*Event`).
- Camera-tab (non-mirrored) canvases keep recording (the flag defaults to `true`).
- Test gate: `./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt` must show `0 regressions; 0 new failures`. A full run takes ~2-3 min; use a Bash timeout of at least 600000 ms.
- The mirrored-pair test from spec §4 is done with the spec's stated fallback (the pieces on a plain `CanvasManager`). `MirroredCanvasManager` needs a live `ProjectorArenaPane`, which can't be built in a unit test. The owner's hardware check covers the real pair.

## Review Focus

1. **Scrubbing the viewer back and forth.** do → undo → do on the same event must not add a marker or target twice, and an event skipped once must be re-evaluated when redone. *Test:* `TestSessionCanvasManagerReplay.redoingAnEventAfterUndoDoesNotDuplicateNodes` (Task 2).
2. **A shot that hit a target the viewer never loaded** (target index present but out of range). The marker must still show; only the animation is skipped. *Test:* `TestSessionCanvasManagerReplay.shotOnUnknownTargetStillShowsMarker` (Task 2).
3. **A session whose target file no longer exists** (the "target added" name can't be loaded). The rest must replay. *Test:* `TestSessionCanvasManagerReplay.missingTargetFileIsSkipped` (Task 2).
4. **Removing a target on a non-recording canvas** must not record a remove, even though the recording copy is removed at the same moment. *Test:* `TestCanvasManagerSessionRecording.nonRecordingCanvasRecordsNothing` (Task 1).
5. **A target positioned before it's registered** (exactly what `MirroredCanvasManager` does) must record nothing until it's added, and then add + position + size. *Test:* `TestCanvasManagerSessionRecording.unregisteredTargetRecordsNothing` and `addingTargetRecordsAddPositionAndSize` (Task 1).

---

### Task 1: Record each target once, with valid indexes

**Files:**
- Modify: `src/main/java/com/shootoff/gui/CanvasManager.java` (flag; `addTarget(File, boolean)` ~811-827; `addTarget(Target)` ~847-870; `removeTarget` ~872-895)
- Modify: `src/main/java/com/shootoff/gui/targets/TargetView.java` (12 record sites; new helper)
- Modify: `src/main/java/com/shootoff/session/SessionRecorder.java` (`recordTargetRemoved`, `recordTargetResized`, `recordTargetMoved`)
- Modify: `src/main/java/com/shootoff/gui/pane/ProjectorSlide.java` (~line 184, after `tabCanvasManager.setMirroredManager(projectorCanvasManager);`)
- Create: `src/test/java/com/shootoff/gui/TestCanvasManagerSessionRecording.java`
- Modify: `src/test/java/com/shootoff/session/TestSessionRecorder.java` (one new test)

**Interfaces:**
- Produces: `CanvasManager.setRecordsSessionEvents(boolean)`, `CanvasManager.recordsSessionEvents() → boolean` (default `true`).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/shootoff/gui/TestCanvasManagerSessionRecording.java`:

```java
package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.gui.controller.ShootOFFController;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.session.Event;
import com.shootoff.session.EventType;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.TargetMovedEvent;
import com.shootoff.session.TargetRemovedEvent;
import com.shootoff.session.TargetResizedEvent;

import javafx.collections.FXCollections;
import javafx.scene.Group;

class TestCanvasManagerSessionRecording {
	private static final String CAMERA = "Default";

	private Configuration config;
	private SessionRecorder recorder;
	private CanvasManager canvas;

	@BeforeEach
	void setUp() throws ConfigurationException {
		config = new Configuration(new String[0]);
		recorder = new SessionRecorder();
		config.setSessionRecorder(recorder);
		canvas = new CanvasManager(new Group(), new ShootOFFController(), CAMERA,
				FXCollections.observableArrayList());
	}

	@AfterEach
	void tearDown() {
		config.setSessionRecorder(null);
	}

	private TargetView newTarget() {
		return new TargetView(new File("targets/shoot.target"), new Group(), new HashMap<String, String>(), canvas,
				false);
	}

	private List<EventType> eventTypes() {
		return recorder.getCameraEvents(CAMERA).stream().map(Event::getType).collect(Collectors.toList());
	}

	@Test
	void canvasesRecordByDefault() {
		assertTrue(canvas.recordsSessionEvents());
	}

	@Test
	void addingTargetRecordsAddPositionAndSize() {
		canvas.addTarget(newTarget());

		assertEquals(List.of(EventType.TARGET_ADDED, EventType.TARGET_MOVED, EventType.TARGET_RESIZED), eventTypes());
		final List<Event> events = recorder.getCameraEvents(CAMERA);
		assertEquals(0, ((TargetMovedEvent) events.get(1)).getTargetIndex());
		assertEquals(0, ((TargetResizedEvent) events.get(2)).getTargetIndex());
	}

	@Test
	void removingTargetRecordsOneRemoveWithValidIndex() {
		final TargetView target = newTarget();
		canvas.addTarget(target);

		canvas.removeTarget(target);

		final List<Event> events = recorder.getCameraEvents(CAMERA);
		assertEquals(1, eventTypes().stream().filter(t -> t == EventType.TARGET_REMOVED).count());
		assertEquals(0, ((TargetRemovedEvent) events.get(events.size() - 1)).getTargetIndex());
	}

	@Test
	void nonRecordingCanvasRecordsNothing() {
		canvas.setRecordsSessionEvents(false);
		final TargetView target = newTarget();

		canvas.addTarget(target);
		target.setPosition(10, 20);
		target.setDimensions(30, 40);
		canvas.removeTarget(target);

		assertEquals(List.of(), eventTypes());
	}

	@Test
	void unregisteredTargetRecordsNothing() {
		final TargetView target = newTarget(); // parent is canvas, but never added to it

		target.setPosition(10, 20);
		target.setDimensions(30, 40);

		assertEquals(List.of(), eventTypes());
	}
}
```

In `src/test/java/com/shootoff/session/TestSessionRecorder.java`, add this test method inside the class (JUnit 4; the class's existing imports and `setUp` fields are used):

```java
	@Test
	public void testUnregisteredTargetEventsAreIgnored() throws ConfigurationException {
		final MockCanvasManager otherCanvas = new MockCanvasManager(new Configuration(new String[0]));
		final TargetView unregistered = new TargetView(new File("unregistered.target"), new Group(),
				new HashMap<String, String>(), otherCanvas, false);

		sessionRecorder.recordTargetMoved(cameraName, unregistered, 1, 2);
		sessionRecorder.recordTargetResized(cameraName, unregistered, 3, 4);
		sessionRecorder.recordTargetRemoved(cameraName, unregistered);

		assertTrue(sessionRecorder.getCameraEvents(cameraName).isEmpty());
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.shootoff.gui.TestCanvasManagerSessionRecording' --tests 'com.shootoff.session.TestSessionRecorder' --console=plain`
Expected: compilation FAILS (`setRecordsSessionEvents`/`recordsSessionEvents` don't exist). Temporarily comment out the two tests that use the flag, `canvasesRecordByDefault` and `nonRecordingCanvasRecordsNothing`, and rerun to see real RED on the rest:
- `addingTargetRecordsAddPositionAndSize` FAILS: nothing is recorded, because `addTarget(Target)` doesn't record today.
- `unregisteredTargetRecordsNothing` FAILS: `checkTarget` synthesizes an add and -1 events.
- `testUnregisteredTargetEventsAreIgnored` FAILS for the same reason.

Record the RED output in the report, then uncomment the two tests.

- [ ] **Step 3: Implement**

`CanvasManager.java`: add a field next to the other boolean fields (~line 114):

```java
	// False for a canvas that mirrors another canvas that already records session events,
	// so each target is recorded once
	private boolean recordsSessionEvents = true;
```

and these methods (near `getTargets()`):

```java
	public void setRecordsSessionEvents(boolean recordsSessionEvents) {
		this.recordsSessionEvents = recordsSessionEvents;
	}

	public boolean recordsSessionEvents() {
		return recordsSessionEvents;
	}
```

In `addTarget(File targetFile, boolean playAnimations)`, delete the block

```java
			if (config.getSessionRecorder().isPresent() && target.isPresent()) {
				config.getSessionRecorder().get().recordTargetAdded(cameraName, target.get());
			}
```

In `addTarget(Target newTarget)`, directly after `targets.add(newTarget);`, add:

```java
		if (recordsSessionEvents && config.getSessionRecorder().isPresent()) {
			final SessionRecorder recorder = config.getSessionRecorder().get();
			recorder.recordTargetAdded(cameraName, newTarget);
			final Point2D position = newTarget.getPosition();
			recorder.recordTargetMoved(cameraName, newTarget, (int) position.getX(), (int) position.getY());
			final Dimension2D dimension = newTarget.getDimension();
			recorder.recordTargetResized(cameraName, newTarget, dimension.getWidth(), dimension.getHeight());
		}
```

Add imports for `com.shootoff.session.SessionRecorder`, `javafx.geometry.Point2D` and `javafx.geometry.Dimension2D` if they aren't already imported.

In `removeTarget(Target target)`, change `if (config.getSessionRecorder().isPresent()) {` (the block containing `recordTargetRemoved`) to:

```java
		if (recordsSessionEvents && config.getSessionRecorder().isPresent()) {
```

`TargetView.java`: add this method (near `getTargetIndex()`):

```java
	// Only the canvas that records session events records this target, and only once the
	// target is registered on it (index -1 means it isn't yet)
	private boolean shouldRecordSessionEvents() {
		return config.isPresent() && config.get().getSessionRecorder().isPresent() && parent.isPresent()
				&& parent.get().recordsSessionEvents() && getTargetIndex() >= 0;
	}
```

then replace all 12 occurrences of the condition text (each one guards a `recordTargetMoved`/`recordTargetResized` call):

```bash
sed -i 's/config.isPresent() && config.get().getSessionRecorder().isPresent()/shouldRecordSessionEvents()/' src/main/java/com/shootoff/gui/targets/TargetView.java
grep -c "shouldRecordSessionEvents()" src/main/java/com/shootoff/gui/targets/TargetView.java
```

Expected count: 13 (12 call sites plus the method's declaration line). The method body itself uses `config.isPresent() && config.get().getSessionRecorder().isPresent()` and must NOT be rewritten by the sed. Add the method AFTER running the sed, or check the method body afterwards and restore it.

`SessionRecorder.java`: make the first line of each of `recordTargetRemoved`, `recordTargetResized` and `recordTargetMoved`:

```java
		// A target that isn't registered on a canvas has no index to replay against
		if (target.getTargetIndex() < 0) return;
```

`ProjectorSlide.java`: directly after `tabCanvasManager.setMirroredManager(projectorCanvasManager);` add:

```java
		// The arena tab mirrors the projector canvas; only the projector canvas records session
		// events, otherwise every arena target is recorded twice
		tabCanvasManager.setRecordsSessionEvents(false);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.shootoff.gui.TestCanvasManagerSessionRecording' --tests 'com.shootoff.session.TestSessionRecorder' --tests 'com.shootoff.session.io.TestSessionIO' --console=plain`
Expected: all PASS.

- [ ] **Step 5: Full gate and commit**

Run the gate from Global Constraints. Expected: `0 regressions; 0 new failures`.

```bash
git add src/main/java/com/shootoff/gui/CanvasManager.java src/main/java/com/shootoff/gui/targets/TargetView.java src/main/java/com/shootoff/session/SessionRecorder.java src/main/java/com/shootoff/gui/pane/ProjectorSlide.java src/test/java/com/shootoff/gui/TestCanvasManagerSessionRecording.java src/test/java/com/shootoff/session/TestSessionRecorder.java
git commit -m "Record each arena target once, with valid indexes"
git log -1 --format=%B
```

---

### Task 2: Session viewer skips events it can't apply

**Files:**
- Create: `src/test/resources/sessions/arena_duplicate_targets.xml` (copied from the owner's recorded session)
- Create: `src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java`
- Modify: `src/main/java/com/shootoff/gui/SessionCanvasManager.java`

**Interfaces:**
- Consumes: `SessionIO.loadSession(File) → Optional<SessionRecorder>`, `SessionRecorder.getCameraEvents(String) → List<Event>`, `new SessionCanvasManager(Group, Configuration)`, `doEvent(Event)`, `undoEvent(Event)`.
- Produces: no public API change.

- [ ] **Step 1: Add the fixture**

```bash
mkdir -p src/test/resources/sessions
cp "sessions/2026-09-25 21.04.07.xml" src/test/resources/sessions/arena_duplicate_targets.xml
grep -c 'index="-1"' src/test/resources/sessions/arena_duplicate_targets.xml
```

Expected: 132.

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java`:

```java
package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.session.Event;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.ShotEvent;
import com.shootoff.session.TargetAddedEvent;
import com.shootoff.session.io.SessionIO;

import javafx.scene.Group;
import javafx.scene.Node;

class TestSessionCanvasManagerReplay {
	private Configuration config;
	private Group canvas;
	private SessionCanvasManager viewer;

	@BeforeEach
	void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		config = new Configuration(new String[0]);
		canvas = new Group();
		viewer = new SessionCanvasManager(canvas, config);
	}

	@Test
	void replaysRecordedArenaSessionWithDuplicateTargetsAndBadIndexes() {
		final Optional<SessionRecorder> session = SessionIO
				.loadSession(new File("src/test/resources/sessions/arena_duplicate_targets.xml"));
		assertTrue(session.isPresent());
		final List<Event> events = session.get().getCameraEvents("arena");
		assertTrue(events.size() > 100);

		assertDoesNotThrow(() -> events.forEach(viewer::doEvent));

		final List<Event> reversed = new ArrayList<>(events);
		Collections.reverse(reversed);
		assertDoesNotThrow(() -> reversed.forEach(viewer::undoEvent));
	}

	@Test
	void shotOnUnknownTargetStillShowsMarker() {
		final DisplayShot shot = new DisplayShot(new Shot(ShotColor.RED, 5, 5, 0), 2);
		final ShotEvent event = new ShotEvent("arena", 0, shot, false, false, Optional.of(7), Optional.of(0),
				Optional.empty());

		assertDoesNotThrow(() -> viewer.doEvent(event));
		assertTrue(canvas.getChildren().contains(shot.getMarker()));

		assertDoesNotThrow(() -> viewer.undoEvent(event));
		assertTrue(!canvas.getChildren().contains(shot.getMarker()));
	}

	@Test
	void redoingAnEventAfterUndoDoesNotDuplicateNodes() {
		final DisplayShot shot = new DisplayShot(new Shot(ShotColor.RED, 5, 5, 0), 2);
		final ShotEvent event = new ShotEvent("arena", 0, shot, false, false, Optional.empty(), Optional.empty(),
				Optional.empty());

		viewer.doEvent(event);
		assertDoesNotThrow(() -> viewer.doEvent(event)); // re-applied without an undo
		final long markers = canvas.getChildren().stream().filter((Node n) -> n == shot.getMarker()).count();
		assertEquals(1, markers);
	}

	@Test
	void missingTargetFileIsSkipped() {
		final TargetAddedEvent added = new TargetAddedEvent("arena", 0, "no_such_dir/no_such.target");
		final int childrenBefore = canvas.getChildren().size();

		assertDoesNotThrow(() -> viewer.doEvent(added));
		assertDoesNotThrow(() -> viewer.undoEvent(added));
		assertEquals(childrenBefore, canvas.getChildren().size());
	}
}
```

Before relying on these constructor signatures, check them against the source:
- `Shot(ShotColor, double, double, long)` and `DisplayShot(Shot, int)`: as used in `TestSessionIO`.
- `ShotEvent(String, long, DisplayShot, boolean, boolean, Optional<Integer>, Optional<Integer>, Optional<String>)`
- `TargetAddedEvent(String, long, String)`: see `src/main/java/com/shootoff/session/`.

If any differs, adapt the test call (not the production class) and note it in the report.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.shootoff.gui.TestSessionCanvasManagerReplay' --console=plain`
Expected:
- `replaysRecordedArenaSessionWithDuplicateTargetsAndBadIndexes` FAILS with `IndexOutOfBoundsException: Index -1`.
- `shotOnUnknownTargetStillShowsMarker` FAILS with `IndexOutOfBoundsException`.
- `redoingAnEventAfterUndoDoesNotDuplicateNodes` FAILS with `IllegalArgumentException: duplicate children added`.
- `missingTargetFileIsSkipped` FAILS with a `NullPointerException` on undo, or passes (record which in the report).

- [ ] **Step 4: Implement**

In `SessionCanvasManager.java`, add imports `java.util.HashSet`, `java.util.Set`, `org.slf4j.Logger` and `org.slf4j.LoggerFactory`, plus these fields:

```java
	private static final Logger logger = LoggerFactory.getLogger(SessionCanvasManager.class);

	// Events that could not be applied (e.g. recorded with target index -1 by older versions);
	// undoing them is skipped too so do and undo stay in step
	private final Set<Event> skippedEvents = new HashSet<>();
	private final Set<Event> skippedAnimations = new HashSet<>();
	private boolean warnedAboutSkippedEvents = false;
```

and these helpers:

```java
	private boolean isValidTargetIndex(int index) {
		return index >= 0 && index < targetViews.size();
	}

	private void skip(Event e, String reason) {
		skippedEvents.add(e);
		logSkipped(e, reason);
	}

	private void logSkipped(Event e, String reason) {
		if (!warnedAboutSkippedEvents) {
			warnedAboutSkippedEvents = true;
			logger.warn("Some session events could not be applied and were skipped");
		}

		logger.debug("Skipped {} event at {} ms: {}", e.getType(), e.getTimestamp(), reason);
	}

	private void addToCanvas(Node node) {
		if (!canvas.getChildren().contains(node)) canvas.getChildren().add(node);
	}
```

(import `javafx.scene.Node`).

At the very top of `doEvent`, before the `switch`, add:

```java
		// Re-evaluate from scratch if this event is being redone
		skippedEvents.remove(e);
		skippedAnimations.remove(e);
```

At the very top of `undoEvent`, before the `switch`, add:

```java
		if (skippedEvents.remove(e)) return;
```

Then change these cases.

**`doEvent` SHOT:**
- Replace `canvas.getChildren().add(se.getShot().getMarker());` with `addToCanvas(se.getShot().getMarker());`.
- Replace the animation block

  ```java
  			if (se.getTargetIndex().isPresent() && se.getHitRegionIndex().isPresent()) {
  				animateTarget(se, false);
  			}
  ```

  with

  ```java
  			if (se.getTargetIndex().isPresent() && se.getHitRegionIndex().isPresent()) {
  				if (canAnimate(se)) {
  					animateTarget(se, false);
  				} else {
  					skippedAnimations.add(e);
  					logSkipped(e, "shot animation refers to a target that is not shown");
  				}
  			}
  ```

  and add

  ```java
  	private boolean canAnimate(ShotEvent se) {
  		if (!isValidTargetIndex(se.getTargetIndex().get())) return false;

  		final int regionIndex = se.getHitRegionIndex().get();
  		return regionIndex >= 0
  				&& regionIndex < targetViews.get(se.getTargetIndex().get()).getTargetGroup().getChildren().size();
  	}
  ```

**`doEvent` TARGET_ADDED:** replace `addTarget((TargetAddedEvent) e);` with

```java
			if (!addTarget((TargetAddedEvent) e)) skip(e, "target file could not be loaded");
```

and change `addTarget` to return `boolean`: `true` at the end of the `if (targetComponents.isPresent())` block, `false` otherwise. Inside it, replace `canvas.getChildren().add(tc.getTargetGroup());` with `addToCanvas(tc.getTargetGroup());`.

**`doEvent` TARGET_REMOVED, TARGET_RESIZED, TARGET_MOVED:** right after each `final Target…Event xxx = (…) e;` line, add

```java
			if (!isValidTargetIndex(xxx.getTargetIndex())) {
				skip(e, "target index " + xxx.getTargetIndex() + " is not shown");
				break;
			}
```

using `tre`, `trre` and `tme` respectively.

**`undoEvent` SHOT:** replace the animation block with

```java
			if (se.getTargetIndex().isPresent() && se.getHitRegionIndex().isPresent()
					&& !skippedAnimations.remove(e) && canAnimate(se)) {
				animateTarget(se, true);
			}
```

**`undoEvent` TARGET_ADDED:** replace the three lines with

```java
			final TargetView added = eventToContainer.get(e);
			if (added == null) break;
			canvas.getChildren().remove(added.getTargetGroup());
			targetViews.remove(added);
			targets.remove(added);
```

**`undoEvent` TARGET_REMOVED:** replace the body after `final TargetRemovedEvent tre = …` with

```java
			final TargetView oldTarget = eventToContainer.get(e);
			if (oldTarget == null) break;
			addToCanvas(oldTarget.getTargetGroup());
			final int restoreIndex = Math.min(tre.getTargetIndex(), targetViews.size());
			targetViews.add(restoreIndex, oldTarget);
			targets.add(Math.min(tre.getTargetIndex(), targets.size()), oldTarget);
```

**`undoEvent` TARGET_RESIZED / TARGET_MOVED:** after reading `oldDimension`/`oldPosition`, add

```java
			if (oldDimension == null || !isValidTargetIndex(trre.getTargetIndex())) break;
```

and

```java
			if (oldPosition == null || !isValidTargetIndex(tme.getTargetIndex())) break;
```

respectively, before the `setDimensions`/`setPosition` call.

**`undoEvent` EXERCISE_FEED_MESSAGE:** unchanged.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.shootoff.gui.TestSessionCanvasManagerReplay' --console=plain`
Expected: all 4 PASS.

- [ ] **Step 6: Full gate and commit**

Run the gate from Global Constraints. Expected: `0 regressions; 0 new failures`.

```bash
git add src/main/java/com/shootoff/gui/SessionCanvasManager.java src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java src/test/resources/sessions/arena_duplicate_targets.xml
git commit -m "Session viewer skips events it cannot apply instead of throwing"
git log -1 --format=%B
```

---

### Task 3: Hardware check with the owner

**Files:** none. If a check fails, stop and debug before changing code.

- [ ] **Step 1:** Launch with `./gradlew run --args="-d" --console=plain > build/session-fix-run.log 2>&1` (in the background). The owner then:
  1. opens the Projector Arena and calibrates
  2. clicks **Record Session**
  3. runs Shoot Don't Shoot for a round or two
  4. clicks **Stop Recording**
  5. opens **View Sessions** and replays the new session, scrubbing back and forth

- [ ] **Step 2:** Check:
  - `grep -c "Exception" build/session-fix-run.log` is 0
  - the newest file in `sessions/` has `index="-1"` count 0
  - each target has one `targetAdded`, i.e. the `targetAdded` count equals the number of targets the exercise showed (roughly half of what the old file had per round)

  The owner confirms targets appear, move and disappear correctly on replay.

- [ ] **Step 3:** Replay the old session `2026-09-25 21.04.07` too. It should play with no exceptions; the log shows one WARN "Some session events could not be applied and were skipped".
