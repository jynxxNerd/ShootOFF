# Compose Trial Plan 8: Calibrate at Startup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry out spec §8 Revision 3. With the option on, the arena calibrates itself when it opens on the projector (at launch, and when a camera comes back), in place of reapplying a saved rectangle, which lost the perspective warp. It ends quietly if the pattern isn't found. A manual box is still remembered and checked. The post-calibration measurement goes. Also fixed from Plan 7's hardware check: Cancel never leaves the arena white, and Range's chip hides once the range is ready. The JavaFX app works as before.

**Architecture:**
- **Task 1: `core`.** `CalibrationFlow.startUnattended(Runnable)` starts a calibration whose timeout (30 s) cancels it and runs the callback, instead of showing the manual box. `SavedCalibration` gains `manual`, stored as the new additive key `shootoff.arena.calibration.manual=true`.
- **Task 2: the measurement goes.** Plan 7's post-calibration measurement (`CheckState.Measuring`) is removed, and with it the drill restart held back for it. `PatternMeasurement` stays in `core`, because the manual box's check uses it.
- **Task 3: the arena calibrates itself.**
  - `CalibrationController.startUnattended`.
  - `AppState.calibrateOrCheck`: a remembered manual box is checked; anything else is calibrated once the arena is on the projector (`CheckState.WaitingToCalibrate`). A pattern not found ends as `CheckState.NotFound`, with no box.
  - Only manual boxes are saved as bounds.
  - The checkbox is renamed "Calibrate automatically when the arena opens".
  - Auto-calibration is logged at INFO.
- **Task 4: Cancel never leaves the arena white.** A background the camera's thread asks for (the exposure step's white) reaches the arena on the UI thread, and only while that calibration still runs.
- **Task 5: Range's status chip is hidden once the range is ready.**
- **Task 6: the owner's short hardware re-check,** plus what is left of Plan 7's Task 10.

**Tech Stack:**
- Java 21 (`core`, `plugin-api`, `javafx-app`) and Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- Compose Multiplatform 1.12.1 (desktop, Linux x64), Material 3 1.9.0, kotlinx.coroutines (from Compose).
- JUnit 5 for unit tests; Compose UI tests on JUnit 4 (`v2.createComposeRule`) through the vintage engine.

**Spec:** `docs/superpowers/specs/2026-09-26-compose-trial-design.md`. This plan implements §8 "Revision 3 (2026-09-27): calibrate at startup instead of checking", which supersedes the conflicting parts of Revisions 1 and 2 above it. Where the spec and this plan disagree, the spec wins. Plan 7's ledger (`.superpowers/sdd/2026-09-27-compose-trial-plan-7-hardware-feedback/progress.md`, gitignored) has the hardware check's findings behind each decision: its "Task 10 (owner re-check …)" section.

Plan 7 is done, with its review fixes: the gate stands at **752**.

**Prototype.** The whole plan was built in a scratch clone of `compose-ui` at the spec commit, one commit per task. Every task compiled, and the gate ran green at each task's commit with the counts given below, ending at **759/759**. The code blocks below are the prototype's edits, extracted from those commits by a script that checked each "Replace … with …" pair matches exactly once in the file before the task, and that applying them in order gives the task's commit byte for byte. Each task's failing-test step was checked against the commit before it. The GUI apps were not run; Task 6 is the owner's.

## Plan-author rulings

Where Revision 3 leaves room, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong. The owner's own rulings (a quiet end when the pattern isn't found; manual boxes still remembered and checked) are the spec's decisions 3 and 5, not repeated here.

1. **The unattended calibration lives in `core`'s flow.**
   - `CalibrationFlow.startUnattended(onTimeout)` starts calibrating as `start()` does. When auto-calibration times out, it calls `cancel()` and then `onTimeout`, where an attended calibration would show the manual box.
   - The timeout is `AUTO_CALIBRATION_TIMEOUT_UNATTENDED`, 30 s; an attended calibration keeps its 12 s. The reason is spec decision 3's freshly plugged camera. `stop()` and `cancel()` clear the unattended mode, so the next `start()` is attended again.
   - The JavaFX app never calls it, and its `start()` path is unchanged.

   *Cost:* with the projector off or covered at launch, the pattern shows for 30 s before the quiet end. Cancel ends it at once.
2. **When the automatic calibration starts** (`AppState.calibrateOrCheck`):
   - It starts from `openArena()`, whether at launch or by hand on Setup, and from `publish()` when a camera opens to an open, uncalibrated arena: the reconnect, a pick, or a switch.
   - It needs the option on and no saved *manual* box; a saved box is checked instead.
   - It waits for Plan 7's `watchArenaForPattern`: the arena full screen, filling the projector screen, and settled for 500 ms. Meanwhile the state is `CheckState.WaitingToCalibrate`.
   - With no projector screen, or no camera yet, nothing starts. `publish()` comes back to it once a camera opens.
   - `WaitingToCalibrate` counts as `showsPattern`, so the grid, Start, F3 and the drill's Resume stay locked, and Setup offers Cancel (`cancelCheck`, which then ends the wait quietly).

   *Cost:* opening the arena by hand on Setup with the option on also calibrates by itself. That is what the checkbox says.
3. **A calibration that didn't find the pattern** ends as `CheckState.NotFound`: "The pattern wasn't found: not calibrated — calibrate on Setup", on Setup and on Range's chip.
   - The arena is exactly as before, uncalibrated with its "Needs Calibration" label.
   - The owner stays where they are; the box never shows.
   - Calibrate (or F6) clears the state and works as ever.

   *Cost:* none known.
4. **The confirmation.** An automatic calibration that succeeds reports through the same `calibrationSucceeded`, so Setup's "Calibration complete ✓ HH:mm" shows for it too. Plan 7's ruling 1 kept the confirmation for calibrations the owner asked for, but an automatic one is a real calibration, and the time tells the owner it happened.

   *Cost:* Setup shows a "Calibration complete" the owner didn't press for. Listed for the owner.
5. **What is saved:**
   - `SavedCalibration` gains a `boolean manual` record component. Every caller passes it explicitly; there is no 5-argument constructor left to default it.
   - `Settings` writes `shootoff.arena.calibration.manual=true` only for a box, and reads a missing key as `false`.
   - `calibrationSucceeded(cameraBounds, paper, byCamera)`: the controller tells a camera-found calibration from the box. Only a box becomes `currentCalibration` and is saved; after a camera-found calibration the saved calibration is cleared and only `…remember=true` stays.
   - A Plan 7 save (no `manual` key) is therefore not a box: the arena calibrates afresh and forgets it (`TestRememberedCalibration.aCalibrationSavedWithoutTheManualMarkIsCalibratedAfreshAndForgotten`).

   *Cost:* none known. The owner's current `shootoff.properties` holds a Plan 7 save, and it is dropped on the first successful calibration.
6. **What is removed** (Task 2 and Task 3):
   - `CheckState.Measuring` and its text.
   - `AppState.calibrationFinishedByCamera`, `uncroppedDetector`, `aroundCameraCalibration`, `holdingDrillRestart`, `deferredDrillRestart`, `runDeferredDrillRestart` and `dropDeferredDrillRestart`, with their calls in `detachCamera`, `closeArena`, `startDrill`, `close`, `cancelCheck` and `setRememberCalibration`.
   - `CalibrationViews.calibrationFinishedByCamera` and `aroundCameraCalibration`.
   - The `PatternMeasurement.work()` extension.
   - `drillForCalibration` shrinks back to `CalibrationFlow.Exercises { pauseOrStopProjectorDrill() }`.

   What stays: `PatternMeasurement` (`CalibrationCheck` measures with it), `CalibrationFlow.applySaved`, `CalibrationController.applySaved`, `PatternRun`, `checkRemembered` and `checked`, all for the manual box's check.

   *Cost:* none. The removed code only ever served the measurement.
7. **F6 during an automatic calibration.** `CalibrationController.start()` does nothing while calibrating, so F6 opens Setup and leaves the automatic calibration running, unattended. If it doesn't find the pattern it ends quietly, and Calibrate then offers the box.

   *Cost:* the owner presses Calibrate once more in that case. Not worth a "become attended" path.
8. **The white arena** (spec decision 8). `CalibrationController.setArenaBackground`, which the camera's thread calls, now posts to the UI thread. It shows the background only if the calibration that asked (its `generation`) still runs. `generation` is now also bumped as each calibration begins, so a stale request from an earlier one is dropped too.
   - The pattern shown by the flow itself still goes straight to the arena on the UI thread.

   *Cost:* the exposure step's white appears one UI-thread hop later (a few milliseconds); the step waits 100 ms before sampling.
9. **The exposure question** (spec decision 3):
   - `AutoCalibrationManager` runs its steps in order: `StepFindBounds`, then `StepFindDelay` (off unless a frame delay is asked for), `StepFindPaperPattern` and `StepAdjustExposure`. The exposure step runs only after the bounds are found, so it can't help find a washed-out pattern. The 30 s unattended timeout is the remedy: the camera's own auto exposure settles meanwhile, and `StepFindBounds` looks every 250 ms throughout.
   - `logback.xml` gains INFO for `com.shootoff.camera.autocalibration`, so the exposure step's "Exposure lowered to …" or "Failed to adjust exposure …" line shows in the hardware check.
   - The Plan 7 logs suggest the step has been putting the C270's auto exposure back after each calibration ("switched to manual exposure … restored auto exposure mode", about a second apart). That is harmless, but it means the exposure step may do nothing on the C270. Listed for the owner.

   *Cost:* none known.
10. **Range's chip** (spec decision 9). `StatusChip` draws nothing when there is a camera, the arena is calibrated, nothing is calibrating, and the check state has no text. Otherwise it is as before.

    *Cost:* none known.
11. **The round label** (spec decision 10) is left to the owner; see "For the owner" below. No host change: `ComposeExerciseHost`'s `TextHandle.setText` and `remove` both update the arena's text, and the drill never calls either for the round text at the summary.
12. **The checkbox** reads "Calibrate automatically when the arena opens", with a muted line under it: "A box placed by hand is reused and checked instead". The test tag (`remember-calibration`), `AppState.rememberCalibration`, `setRememberCalibration` and the `…remember` key keep their names.

    *Cost:* none. The wording is the owner's to change.
13. **Test timing.** In tests, `uiThread` runs inline on the watcher's thread, so an automatic calibration starts there, not on the test's thread. Tests that answer it wait until it is looking (`awaitAutomaticCalibrationLooking()`, message `AUTO_CALIBRATING`) before handing it a result. In the app it runs on the EDT, like the rest of calibration.
14. **Existing tests change where Revision 3 reverses behavior they pin.** Each change is in the task that changes the behavior:
    - Task 1: every `SavedCalibration(…)` in `compose-app`'s tests gains `, true`. These are remembered boxes, the only kind the app saves as bounds from Task 3 on.
    - Task 2 deletes 9 tests, listed in its Files block, and adds 1. `TestRememberedCalibration.calibratingDuringTheCheck…` and `TestRangeScreen.theResumeButtonIsDisabledWhileAPatternShows` no longer go through a measurement.
    - Task 3: `TestRememberedCalibration.withRememberOnACalibrationIsSavedWithItsCameraAndProjector` becomes `withTheOptionOnACalibrationTheCameraFoundSavesOnlyThatTheOptionIsOn`, and `turningRememberOnSavesTheCalibrationThereIsAndOffForgetsIt` becomes `turningTheOptionOnSavesTheManualBoxThereIsAndOffForgetsIt`. `aManualBoxCalibrationIsRememberedAsTheBoxWithoutMeasuring` becomes `…AsTheBoxAndMarkedAsOne`. The `remembered()` file and `SAVED_KEYS` gain `…manual=true`.
    - Task 5: `TestRangeScreen.theChipSaysWhatIsMissingOrWhenItWasCalibratedAndOpensSetup` becomes `theChipSaysWhatIsMissingAndOpensSetup`.

    *Cost:* none. The baseline's Java 8 tests are all unchanged.

**Changes to JavaFX behavior:** none.
- `CalibrationFlow` gains `startUnattended`, which the JavaFX app never calls. Its timer's timeout is picked by a new private `timeout()` that returns exactly what the JavaFX app got before.
- `SavedCalibration` gains a component; the JavaFX app never constructs one.
- `Settings` carries the new `…manual` key through a JavaFX save (`TestConfigurationKeepsComposeKeys`).

**For the owner to decide** (none blocks the plan):
- **The checkbox wording** (ruling 12): "Calibrate automatically when the arena opens", plus "A box placed by hand is reused and checked instead".
- **"Calibration complete ✓ HH:mm" after an automatic calibration** (ruling 4): keep it, or reserve it for Calibrate and F6.
- **The drill's round label** (spec decision 10), a change in `/home/bfears/projects/RandomTargetParDrill` that this plan doesn't touch:
  - In `src/main/java/com/shootoff/plugins/RandomTargetParDrill.java`, `displayResults()` ends with `showOnFeeds(message)`, which puts the summary in `scoreText` (top left). The round text, `roundText` (created in `initUI()` with `host.showText(roundLabel(), surface.getWidth() / 2, 10, LABEL_STYLE)`), is left as it was.
  - Add `roundText.setText("");` next to that `showOnFeeds(message)` call. `resetValues()` already restores it with `roundText.setText(roundLabel())` on reset and on the next run.
  - A test in `TestRandomTargetParDrill` could assert `roundText()` is empty after `displayResults` (its `fullDrillScoresTenRoundsAndReplaysTheShotsOnTheSummaryTarget` reaches the summary).
  - This is plugin v1.2, which you rebuild and copy to `exercises/` yourself.
- **The C270's exposure step** (ruling 9): after Task 3 the log says whether it lowered the exposure. If it always reports "Failed to adjust exposure", the step does nothing on this camera, which is worth knowing but needs no change.
- **30 s for an unattended calibration** (ruling 1): enough by Plan 7's evidence (found at 15 s after a plug-in, not by 8–10 s). If the re-check still sees a quiet end right after a replug, lengthen `AUTO_CALIBRATION_TIMEOUT_UNATTENDED`.

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** no `Co-Authored-By` or any other trailer in commit messages. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- **Code style:**
  - Java: tabs; match the surrounding code. A new main-source Java file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file), then a blank line.
  - Kotlin: the official style (4 spaces, trailing commas). A new main-source `.kt` file starts with the same 17 lines, then a blank line.
  - Test files have no header.
  - Keep each file's imports sorted as the file already sorts them.
  - This plan creates no new files.
- **Packages:** `com.shootoff.*`.
- **Dependencies:** none added anywhere. `compose-app` still depends on `core`, `plugin-api` and Plan 5's catalog entries only; no OpenJFX in `compose-app` (`TestNoJavaFxInComposeApp` keeps passing).
- **Formats:**
  - `.target`, `.course`, the session formats, `shootoff.xml` descriptors and `shootoff.properties` are unchanged.
  - Plan 6's `shootoff.arena.calibration.*` keys are reused as they are, plus one new additive key, `shootoff.arena.calibration.manual`, written only for a manual box.
- **The exercise API is unchanged.** `plugin-api` has no edit in this plan.
- **Tests and the owner's settings:**
  - Tests use `ScratchConfig` and never write the owner's `shootoff.properties`: every `Settings` a test builds is on `ScratchConfig.emptyFile()` (or a `@TempDir` file in `core`). No test reads or writes the working tree's `shootoff.properties`.
  - `compose-app` tests never read a saved `Settings` back (Plan 6's ruling 16); they read the file as `Properties`.
  - `UiPrefs` in tests is `PrefsStore.Memory`.
- **The owner's files.** Never modify, move, delete or stage:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - anything under `exercises/` (the owner's `RandomTargetParDrill.jar` and `RandomTargetParDrill-v2.jar`)
  - anything under `exercise-data/`
  - any file the owner added under `targets/` or `courses/`
  - the owner's Java preferences
  - the drill's own repository (`/home/bfears/projects/RandomTargetParDrill`)
  No test runs the drill.
- **The JavaFX app behaves as before.** `./gradlew run` starts only the JavaFX app; `./gradlew :compose-app:run` starts the Compose app.
- **Publishing:** only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`. No task here publishes.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- Implementers never run the GUI apps; Task 6 is the owner's.
- **Test gate** (unchanged from Plans 1–7; run it in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10` if the shell doesn't already point at a Java 21):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. Use a Bash timeout of 600000 ms.
  - The `N/M passing` count starts at **752** (end of Plan 7). Each task gives the count it ends at. A lower count means tests silently stopped running. Task 2 deletes tests on purpose, and its count allows for it.
  - `core`'s `TestMalfunctionsProcessor.testManyMalfunctions` is probabilistic (a baseline test this plan doesn't touch). If it alone fails, run the gate again.
  - The Compose UI tests need a display, as the JavaFX tests do. The gate runs them on this machine's `DISPLAY=:0`.
  - The gate leaves `shootoff.properties` unchanged.

## Review Focus

1. **A camera just plugged in (at launch, or the reconnect) whose exposure hasn't settled.** Expected: the automatic calibration keeps looking well past the 8–12 s a washed-out C270 needs, and doesn't give up at the attended 12 s.
   - *Tests:* `TestCalibrationFlow.anUnattendedCalibrationThatTimesOutIsCancelledWithoutTheManualBox` asserts it is still calibrating after 12 s and ends only at 30 s (Task 1). `TestRememberedCalibration.withTheOptionOnTheCameraComingBackCalibratesTheArenaAgain` covers the reconnect (Task 3).
2. **The projector is off or covered at launch.** Expected: no manual box pops up, the owner isn't moved to Setup, the arena is left as it was ("Needs Calibration"), Setup and the chip say "The pattern wasn't found…", and Calibrate works afterwards.
   - *Tests:* `TestRememberedCalibration.anAutomaticCalibrationThatDoesntFindThePatternEndsQuietlyWithoutTheBox` and `TestCalibrationOnRequest.anUnattendedCalibrationThatDoesntFindThePatternPutsTheArenaBackWithoutTheBox` (Task 3).
3. **Cancel pressed at any moment of a calibration, including while the exposure step's white shows, or as the camera is mid-frame.** Expected: the arena is exactly as before, never white.
   - *Tests:* `TestCalibrationOnRequest.aWhiteScreenTheCameraAsksForAsCancelLandsNeverShows`, `cancelDuringTheExposureStepPutsTheBackgroundBackNotWhite`, `cancelOnTheManualBoxAfterTheExposureStepStartedPutsTheBackgroundBack` and `aBackgroundTheCameraAsksForAfterASuccessNeverShows` (Task 4).
4. **The arena isn't on the projector yet when the calibration would start** (the window manager is still resizing it, or no projector screen). Expected: nothing shows and nothing calibrates until it fills the projector; Setup says so, with Cancel; the owner stays on Range.
   - *Tests:* `TestNothingBlocks.rule1WithTheOptionOnTheLaunchArenaCalibratesOnlyOnceItIsOnTheProjector`, `TestRememberedCalibration.withTheOptionOnOpeningTheArenaCalibratesItOnceItIsOnTheProjector` and `TestSetupScreen.anAutomaticCalibrationWaitingForTheProjectorSaysSoWithCancel` (Task 3).
5. **The owner's existing `shootoff.properties`, saved by Plan 7** (bounds, no `manual` key). Expected: it isn't reapplied (it would lose the perspective again); the arena calibrates afresh and the stale bounds are forgotten.
   - *Test:* `TestRememberedCalibration.aCalibrationSavedWithoutTheManualMarkIsCalibratedAfreshAndForgotten` (Task 3).

The owner's check (Task 6) covers what no unit test reaches: the C270's real exposure after a plug-in, the real pattern search, the perspective warp that makes the grid line up, and the window manager's full screen.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/…/calibration/CalibrationFlow.java`, `core/…/config/{SavedCalibration,Settings}.java` | the unattended calibration; the manual box's mark | 1 |
| `…/compose/app/AppState.kt`, `…/compose/calibration/{CalibrationController,RememberedCalibration}.kt` | the measurement and the held restart go | 2 |
| `…/compose/app/{AppState,SetupScreen}.kt`, `…/compose/calibration/{CalibrationController,RememberedCalibration}.kt`, `compose-app/src/main/resources/logback.xml` | the arena calibrates itself; the checkbox | 3 |
| `…/compose/calibration/CalibrationController.kt` | Cancel never leaves the arena white | 4 |
| `…/compose/app/RangeControls.kt` | the chip only when there is something to say | 5 |
| none | the owner's re-check | 6 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`, with tests under `core/src/test/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | Test methods added (−removed) | Gate |
|---|---|---|
| 1 | `core` `calibration/TestCalibrationFlow` (+2), `config/TestSavedCalibrationSettings` (+1) | 755 |
| 2 | `app/TestRememberedCalibration` (−3), `app/TestCalibrationPausesTheDrill` (−5, +1), `app/TestSetupScreen` (−1) | 747 |
| 3 | `app/TestRememberedCalibration` (+4), `calibration/TestCalibrationOnRequest` (+1), `app/TestSetupScreen` (+1), `app/TestNothingBlocks` (+1) | 754 |
| 4 | `calibration/TestCalibrationOnRequest` (+4) | 758 |
| 5 | `app/TestRangeScreen` (+1) | 759 |

---

### Task 1: `core`: an unattended calibration, and the manual box's mark

**Files:**
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationFlow.java` (new `AUTO_CALIBRATION_TIMEOUT_UNATTENDED`, `unattendedTimeout`, `startUnattended`, `timeout()`; `stop`, `cancel`, `launchAutoCalibrationTimer`)
- Modify: `core/src/main/java/com/shootoff/config/SavedCalibration.java` (component `manual`)
- Modify: `core/src/main/java/com/shootoff/config/Settings.java` (`SAVED_CALIBRATION_MANUAL_PROP`, read and write)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (the three `SavedCalibration(…)` calls pass `manual`)
- Test: `core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`, `core/src/test/java/com/shootoff/config/TestSavedCalibrationSettings.java`, `javafx-app/src/test/java/com/shootoff/config/TestConfigurationKeepsComposeKeys.java`, and the `SavedCalibration(…)` calls in `compose-app/src/test/kotlin/com/shootoff/compose/app/{TestRememberedCalibration,TestNothingBlocks,TestNothingBlocksViews}.kt` and `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`

**Interfaces:**
- Consumes: Plan 5's `CalibrationFlow` (`start`, `stop`, `cancel`, `setFullScreen`, the auto-calibration timer); Plan 6's `SavedCalibration` and `Settings.getSavedCalibration`/`setSavedCalibration`.
- Produces:
  - `public void CalibrationFlow.startUnattended(Runnable onTimeout)`: a no-op while calibrating. Its timeout calls `cancel()`, then `onTimeout.run()`, on the UI thread.
  - `public static final long CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 30000`.
  - `record SavedCalibration(String camera, Size feed, Size screen, Rect bounds, Optional<Size> paper, boolean manual)`.
  - The key `shootoff.arena.calibration.manual=true`, written only when `manual`.

  Task 3 uses all of these.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`:

Replace:

```java
		assertFalse(flow.isCalibrating());
		assertFalse(events.contains("show box"));
	}

	@Test
	void cancelEndsCalibrationWithNoBackgroundRestoreOrRestartAndReEnablesDetection() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
```

with:

```java
		assertFalse(flow.isCalibrating());
		assertFalse(events.contains("show box"));
	}

	@Test
	void anUnattendedCalibrationThatTimesOutIsCancelledWithoutTheManualBox() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
		flow.startUnattended(() -> events.add("not found"));
		assertTrue(flow.isCalibrating());
		assertTrue(events.contains("show pattern"));
		events.clear();

		// Attended calibration's 12 seconds go by: a camera just plugged in may still be settling
		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertTrue(flow.isCalibrating());
		assertEquals(List.of(), events);

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED);

		assertFalse(flow.isCalibrating());
		// Cancelled, as Cancel does it, and only then told; never the box, never the calibrating feed
		assertEquals(List.of("(UI thread)", "camera stops looking", "hide AUTO_CALIBRATING",
				"calibrate button calibrating false", "restore selected view", "camera calibrating false",
				"camera detecting false", "not found"), events);
		assertFalse(projectorExerciseRunning, "the drill was stopped, not restarted");
	}

	@Test
	void anUnattendedCalibrationThatFindsThePatternEndsAsAnyOtherAndTheNextStartIsAttended() {
		final CalibrationFlow flow = flow();
		flow.startUnattended(() -> events.add("not found"));

		flow.calibrated(new Rect(100, 50, 400, 300), Optional.empty(), false);
		assertFalse(flow.isCalibrating());
		assertTrue(events.contains("calibrated"));

		// Calibrate, pressed afterwards: its timeout opens the box as ever
		flow.start();
		events.clear();
		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertTrue(flow.isCalibrating());
		assertTrue(events.contains("show box"));
		assertFalse(events.contains("not found"));
	}

	@Test
	void cancelEndsCalibrationWithNoBackgroundRestoreOrRestartAndReEnablesDetection() {
		projectorExerciseRunning = true;
		final CalibrationFlow flow = flow();
```

`core/src/test/java/com/shootoff/config/TestSavedCalibrationSettings.java`:

Replace:

```java
import com.shootoff.geom.Size;

class TestSavedCalibrationSettings {
	private static final SavedCalibration SAVED = new SavedCalibration("UVC Camera (046d:0825) /dev/video0",
			new Size(640, 480), new Size(1280, 720), new Rect(101.5, 80, 400, 300), Optional.of(new Size(11, 8.5)));

	@BeforeAll
	static void setUpHome() {
		// Writing always stores the (empty) webcam list; reading that back enumerates cameras through OpenCV
```

with:

```java
import com.shootoff.geom.Size;

class TestSavedCalibrationSettings {
	private static final SavedCalibration SAVED = new SavedCalibration("UVC Camera (046d:0825) /dev/video0",
			new Size(640, 480), new Size(1280, 720), new Rect(101.5, 80, 400, 300), Optional.of(new Size(11, 8.5)), false);

	@BeforeAll
	static void setUpHome() {
		// Writing always stores the (empty) webcam list; reading that back enumerates cameras through OpenCV
```

Replace:

```java
		assertTrue(read.rememberCalibration());
		assertEquals(Optional.of(SAVED), read.getSavedCalibration());
	}

	@Test
	void withRememberOffAndNothingSavedNoCalibrationKeyIsWritten() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings settings = settings(file);
```

with:

```java
		assertTrue(read.rememberCalibration());
		assertEquals(Optional.of(SAVED), read.getSavedCalibration());
	}

	@Test
	void aManualBoxIsMarkedAsOneAndAnythingElseIsNot() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings written = settings(file);
		written.setRememberCalibration(true);
		written.setSavedCalibration(SAVED);
		written.writeConfigurationFile();
		assertFalse(Files.readString(file.toPath(), StandardCharsets.ISO_8859_1).contains("shootoff.arena.calibration.manual"));

		final SavedCalibration box = new SavedCalibration(SAVED.camera(), SAVED.feed(), SAVED.screen(), SAVED.bounds(),
				Optional.empty(), true);
		written.setSavedCalibration(box);
		written.writeConfigurationFile();

		assertTrue(Files.readAllLines(file.toPath(), StandardCharsets.ISO_8859_1).contains("shootoff.arena.calibration.manual=true"));
		assertEquals(Optional.of(box), settings(file).getSavedCalibration());
	}

	@Test
	void withRememberOffAndNothingSavedNoCalibrationKeyIsWritten() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings settings = settings(file);
```

`javafx-app/src/test/java/com/shootoff/config/TestConfigurationKeepsComposeKeys.java`:

Replace:

```java
class TestConfigurationKeepsComposeKeys {
	private static final List<String> COMPOSE_KEYS = List.of("shootoff.arena.calibration.remember=true",
			"shootoff.arena.calibration.camera=C270", "shootoff.arena.calibration.feed=640.0x480.0",
			"shootoff.arena.calibration.screen=1280.0x720.0",
			"shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0",
			"shootoff.compose.unknown=a key no version of the JavaFX app knows");

	@BeforeAll
	static void setUpHome() {
```

with:

```java
class TestConfigurationKeepsComposeKeys {
	private static final List<String> COMPOSE_KEYS = List.of("shootoff.arena.calibration.remember=true",
			"shootoff.arena.calibration.camera=C270", "shootoff.arena.calibration.feed=640.0x480.0",
			"shootoff.arena.calibration.screen=1280.0x720.0",
			"shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0", "shootoff.arena.calibration.manual=true",
			"shootoff.compose.unknown=a key no version of the JavaFX app knows");

	@BeforeAll
	static void setUpHome() {
```

Replace:

```java
		assertTrue(saved.contains("shootoff.markerradius=8"));
		assertEquals(Optional.of(new Rect(100, 80, 400, 300)),
				new Configuration(file.getPath(), new String[0]).getSavedCalibration().map(c -> c.bounds()));
		assertEquals(new Size(1280, 720), config.getSavedCalibration().get().screen());
	}
}
```

with:

```java
		assertTrue(saved.contains("shootoff.markerradius=8"));
		assertEquals(Optional.of(new Rect(100, 80, 400, 300)),
				new Configuration(file.getPath(), new String[0]).getSavedCalibration().map(c -> c.bounds()));
		assertEquals(new Size(1280, 720), config.getSavedCalibration().get().screen());
		assertTrue(config.getSavedCalibration().get().manual());
	}
}
```

The `compose-app` tests build `SavedCalibration` with five arguments; each gains `, true` (they stand for remembered manual boxes):

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

Replace:

```kotlin
class TestRememberedCalibration {
    private val file: File = ScratchConfig.emptyFile()

    // The owner's projector is the 1280x720 screen; the test camera's feed is 640x480
    private val saved = SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty())

    // What the fake detector finds in any frame, and the check's clock
    private val seen = AtomicReference<Optional<Rect>>(Optional.empty())
    private val looks = AtomicLong()
```

with:

```kotlin
class TestRememberedCalibration {
    private val file: File = ScratchConfig.emptyFile()

    // The owner's projector is the 1280x720 screen; the test camera's feed is 640x480
    private val saved = SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true)

    // What the fake detector finds in any frame, and the check's clock
    private val seen = AtomicReference<Optional<Rect>>(Optional.empty())
    private val looks = AtomicLong()
```

Replace:

```kotlin
    fun savedCalibrationMismatchMatchesACameraRenumberedToAnotherDeviceNode() {
        val projector = Rect(4480.0, 0.0, 1280.0, 720.0)
        val feed = Size(640.0, 480.0)
        val savedOnVideo0 = SavedCalibration(
            "UVC Camera (046d:0825) /dev/video0", feed, Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(),
        )

        assertNull(savedCalibrationMismatch(savedOnVideo0, "UVC Camera (046d:0825) /dev/video2", feed, projector))
    }
```

with:

```kotlin
    fun savedCalibrationMismatchMatchesACameraRenumberedToAnotherDeviceNode() {
        val projector = Rect(4480.0, 0.0, 1280.0, 720.0)
        val feed = Size(640.0, 480.0)
        val savedOnVideo0 = SavedCalibration(
            "UVC Camera (046d:0825) /dev/video0", feed, Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true,
        )

        assertNull(savedCalibrationMismatch(savedOnVideo0, "UVC Camera (046d:0825) /dev/video2", feed, projector))
    }
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`:

Replace:

```kotlin
    @Test
    fun rule1AtLaunchTheCameraAndTheArenaOpenAndNothingCalibrates() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
        val looked = AtomicLong()
        val app = AppFixture.appWithCamera(settings, detector = {
            looked.incrementAndGet()
            Optional.empty()
```

with:

```kotlin
    @Test
    fun rule1AtLaunchTheCameraAndTheArenaOpenAndNothingCalibrates() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true))
        val looked = AtomicLong()
        val app = AppFixture.appWithCamera(settings, detector = {
            looked.incrementAndGet()
            Optional.empty()
```

Replace:

```kotlin
    @Test
    fun rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAtItsTimeLimit() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
        val now = AtomicLong(0)
        val lookedOn = AtomicReference<Thread>()
        // The projector is off: the pattern is never seen
        val app = AppFixture.appWithCamera(settings, detector = {
```

with:

```kotlin
    @Test
    fun rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAtItsTimeLimit() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true))
        val now = AtomicLong(0)
        val lookedOn = AtomicReference<Thread>()
        // The projector is off: the pattern is never seen
        val app = AppFixture.appWithCamera(settings, detector = {
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocksViews.kt`:

Replace:

```kotlin
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf()).apply {
        setRememberCalibration(true)
        setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
    }
    private val app = AppFixture.appWithCamera(settings)

    @After
```

with:

```kotlin
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf()).apply {
        setRememberCalibration(true)
        setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true))
    }
    private val app = AppFixture.appWithCamera(settings)

    @After
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`:

Replace:

```kotlin
    }

    @Test
    fun aRememberedCalibrationIsAppliedWithoutCalibratingOrReportingASuccess() {
        val saved = SavedCalibration("C270", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty())

        controller.applySaved(saved)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
```

with:

```kotlin
    }

    @Test
    fun aRememberedCalibrationIsAppliedWithoutCalibratingOrReportingASuccess() {
        val saved = SavedCalibration("C270", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), true)

        controller.applySaved(saved)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationFlow' --tests 'com.shootoff.config.TestSavedCalibrationSettings' --console=plain`
Expected: `:core:compileTestJava` FAILS with "cannot find symbol … startUnattended" and "constructor SavedCalibration in record SavedCalibration cannot be applied to given types".

- [ ] **Step 3: Write the implementation**

`core/src/main/java/com/shootoff/calibration/CalibrationFlow.java`:

Replace:

```java
 * camera looks for it.</li>
 * <li><b>Success</b>: the camera reports the pattern's bounds, which become the arena's projection,
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends).</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
 * arena's background comes back, detection resumes after a moment, and the projector exercise that
 * was stopped starts again, last.</li>
 * </ol>
```

with:

```java
 * camera looks for it.</li>
 * <li><b>Success</b>: the camera reports the pattern's bounds, which become the arena's projection,
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends; unattended: after 30 seconds calibration is cancelled,
 * see {@link #startUnattended}).</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
 * arena's background comes back, detection resumes after a moment, and the projector exercise that
 * was stopped starts again, last.</li>
 * </ol>
```

Replace:

```java
	private static final Logger logger = LoggerFactory.getLogger(CalibrationFlow.class);

	public static final long AUTO_CALIBRATION_TIMEOUT = 12 * 1000;
	public static final long AUTO_CALIBRATION_TIMEOUT_HEADLESS = 45 * 1000;
	// The pattern going away can look like shots
	public static final long DETECTION_RESTART_DELAY = 600;
	// Before auto-calibrating once the arena is full screen (ShootOFF issue #444)
	public static final long FULL_SCREEN_SETTLE_DELAY = 100;
```

with:

```java
	private static final Logger logger = LoggerFactory.getLogger(CalibrationFlow.class);

	public static final long AUTO_CALIBRATION_TIMEOUT = 12 * 1000;
	public static final long AUTO_CALIBRATION_TIMEOUT_HEADLESS = 45 * 1000;
	// A camera just plugged in (or just opened) can take 10-15 seconds to settle its exposure, and until then
	// the pattern may be washed out: an unattended calibration, with no one to press Calibrate again, waits
	// that out (spec §8 Revision 3)
	public static final long AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 30 * 1000;
	// The pattern going away can look like shots
	public static final long DETECTION_RESTART_DELAY = 600;
	// Before auto-calibrating once the arena is full screen (ShootOFF issue #444)
	public static final long FULL_SCREEN_SETTLE_DELAY = 100;
```

Replace:

```java
	private final Set<Message> shownMessages = EnumSet.noneOf(Message.class);
	private volatile Future<?> autoCalibrationTimer = null;
	private volatile Optional<Runnable> restartExercise = Optional.empty();
	private volatile Optional<Size> perspectivePaperDims = Optional.empty();

	/**
	 * @param headlessTimeout
	 *            for headless use: runs when auto-calibration times out after 45 seconds, and calibration
```

with:

```java
	private final Set<Message> shownMessages = EnumSet.noneOf(Message.class);
	private volatile Future<?> autoCalibrationTimer = null;
	private volatile Optional<Runnable> restartExercise = Optional.empty();
	private volatile Optional<Size> perspectivePaperDims = Optional.empty();
	// Set while an unattended calibration runs (startUnattended): what its timeout runs instead of the box
	private volatile Optional<Runnable> unattendedTimeout = Optional.empty();

	/**
	 * @param headlessTimeout
	 *            for headless use: runs when auto-calibration times out after 45 seconds, and calibration
```

Replace:

```java
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

```

with:

```java
			showMessage(Message.FULL_SCREEN_REQUEST);
		}
	}

	/**
	 * Starts calibrating with no one there to drag the manual box (the Compose app calibrating by itself as
	 * the arena opens). It runs as {@link #start()} does, except when auto-calibration times out, after
	 * {@link #AUTO_CALIBRATION_TIMEOUT_UNATTENDED}: then calibration is cancelled, as {@link #cancel()} does,
	 * and <tt>onTimeout</tt> runs, instead of the box being shown. Stopping or cancelling ends it; the next
	 * {@link #start()} is attended again.
	 *
	 * @param onTimeout
	 *            runs, after the cancel, if the pattern isn't found in time
	 */
	public void startUnattended(Runnable onTimeout) {
		if (isCalibrating()) return;

		unattendedTimeout = Optional.of(onTimeout);
		setFullScreen(view.isArenaFullScreen());
	}

	/**
	 * Ends calibrating: with the manual box's bounds if it is showing, otherwise with the bounds found
	 * so far (possibly none).
	 */
	public void stop() {
		isCalibrating.set(false);
		unattendedTimeout = Optional.empty();

		final Optional<Rect> manualBox = view.manualBox();
		if (manualBox.isPresent()) calibrated(manualBox.get(), Optional.empty(), true);

```

Replace:

```java
	 * still comes back after the usual delay, so it isn't left off everywhere else.
	 */
	public void cancel() {
		isCalibrating.set(false);

		cancelAutoCalibrationTimer();

		camera.disableAutoCalibration();
```

with:

```java
	 * still comes back after the usual delay, so it isn't left off everywhere else.
	 */
	public void cancel() {
		isCalibrating.set(false);
		unattendedTimeout = Optional.empty();

		cancelAutoCalibrationTimer();

		camera.disableAutoCalibration();
```

Replace:

```java
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
```

with:

```java
		cancelAutoCalibrationTimer();

		autoCalibrationTimer = scheduler.schedule(() -> view.runOnUiThread(() -> {
			if (isCalibrating.get() && isFullScreen) {
				final Optional<Runnable> unattended = unattendedTimeout;
				if (headlessTimeout.isPresent()) {
					headlessTimeout.get().run();
					stop();
				} else if (unattended.isPresent()) {
					cancel();
					unattended.get().run();
				} else {
					camera.disableAutoCalibration();
					startManualCalibration();
				}
			}
			// Keep waiting
			else if (!isFullScreen) launchAutoCalibrationTimer();
		}), timeout());
	}

	private long timeout() {
		if (headlessTimeout.isPresent()) return AUTO_CALIBRATION_TIMEOUT_HEADLESS;
		if (unattendedTimeout.isPresent()) return AUTO_CALIBRATION_TIMEOUT_UNATTENDED;
		return AUTO_CALIBRATION_TIMEOUT;
	}

	private void cancelAutoCalibrationTimer() {
		final Future<?> timer = autoCalibrationTimer;
```

`core/src/main/java/com/shootoff/config/SavedCalibration.java`:

Replace:

```java
 * @param bounds
 *            the projection on the camera's feed, in feed pixels
 * @param paper
 *            the perspective paper's size, if auto-calibration found one
 */
public record SavedCalibration(String camera, Size feed, Size screen, Rect bounds, Optional<Size> paper) {
	public SavedCalibration {
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(feed, "feed");
		Objects.requireNonNull(screen, "screen");
```

with:

```java
 * @param bounds
 *            the projection on the camera's feed, in feed pixels
 * @param paper
 *            the perspective paper's size, if auto-calibration found one
 * @param manual
 *            whether it is a box the user placed by hand; the Compose app remembers only those as bounds, and
 *            calibrates anew for any other (spec §8 Revision 3)
 */
public record SavedCalibration(String camera, Size feed, Size screen, Rect bounds, Optional<Size> paper, boolean manual) {
	public SavedCalibration {
		Objects.requireNonNull(camera, "camera");
		Objects.requireNonNull(feed, "feed");
		Objects.requireNonNull(screen, "screen");
```

`core/src/main/java/com/shootoff/config/Settings.java`:

Replace:

```java
	private static final String SAVED_CALIBRATION_FEED_PROP = "shootoff.arena.calibration.feed";
	private static final String SAVED_CALIBRATION_SCREEN_PROP = "shootoff.arena.calibration.screen";
	private static final String SAVED_CALIBRATION_BOUNDS_PROP = "shootoff.arena.calibration.bounds";
	private static final String SAVED_CALIBRATION_PAPER_PROP = "shootoff.arena.calibration.paper";

	// Every key this class reads; any other key in the file is carried through a save untouched
	private static final Set<String> KNOWN_KEYS = Set.of(FIRST_RUN_PROP, ERROR_REPORTING_PROP, IPCAMS_PROP,
			WEBCAMS_PROP, RECORDING_WEBCAMS_PROP, MARKER_RADIUS_PROP, IGNORE_LASER_COLOR_PROP,
```

with:

```java
	private static final String SAVED_CALIBRATION_FEED_PROP = "shootoff.arena.calibration.feed";
	private static final String SAVED_CALIBRATION_SCREEN_PROP = "shootoff.arena.calibration.screen";
	private static final String SAVED_CALIBRATION_BOUNDS_PROP = "shootoff.arena.calibration.bounds";
	private static final String SAVED_CALIBRATION_PAPER_PROP = "shootoff.arena.calibration.paper";
	// Written only for a manual box; a saved calibration without it (as Plan 7 saved them) isn't one
	private static final String SAVED_CALIBRATION_MANUAL_PROP = "shootoff.arena.calibration.manual";

	// Every key this class reads; any other key in the file is carried through a save untouched
	private static final Set<String> KNOWN_KEYS = Set.of(FIRST_RUN_PROP, ERROR_REPORTING_PROP, IPCAMS_PROP,
			WEBCAMS_PROP, RECORDING_WEBCAMS_PROP, MARKER_RADIUS_PROP, IGNORE_LASER_COLOR_PROP,
```

Replace:

```java
			MALFUNCTIONS_PROBABILITY_PROP, ARENA_POSITION_X_PROP, ARENA_POSITION_Y_PROP, MUTED_CHIME_MESSAGES,
			PERSPECTIVE_WEBCAM_DISTANCES, CALIBRATED_FEED_BEHAVIOR_PROP, SHOW_ARENA_SHOT_MARKERS,
			CALIBRATE_AUTO_ADJUST_EXPOSURE, SHOWED_PERSPECTIVE_USAGE_MESSAGE, POI_ADJUSTMENT_X, POI_ADJUSTMENT_Y,
			REMEMBER_CALIBRATION_PROP, SAVED_CALIBRATION_CAMERA_PROP, SAVED_CALIBRATION_FEED_PROP,
			SAVED_CALIBRATION_SCREEN_PROP, SAVED_CALIBRATION_BOUNDS_PROP, SAVED_CALIBRATION_PAPER_PROP);

	protected static final String MARKER_RADIUS_MESSAGE = "MARKER_RADIUS has an invalid value: %d. Acceptable values are "
			+ "between 1 and 20.";
	protected static final String LASER_COLOR_MESSAGE = "LASER_COLOR has an invalid value: %s. Acceptable values are "
```

with:

```java
			MALFUNCTIONS_PROBABILITY_PROP, ARENA_POSITION_X_PROP, ARENA_POSITION_Y_PROP, MUTED_CHIME_MESSAGES,
			PERSPECTIVE_WEBCAM_DISTANCES, CALIBRATED_FEED_BEHAVIOR_PROP, SHOW_ARENA_SHOT_MARKERS,
			CALIBRATE_AUTO_ADJUST_EXPOSURE, SHOWED_PERSPECTIVE_USAGE_MESSAGE, POI_ADJUSTMENT_X, POI_ADJUSTMENT_Y,
			REMEMBER_CALIBRATION_PROP, SAVED_CALIBRATION_CAMERA_PROP, SAVED_CALIBRATION_FEED_PROP,
			SAVED_CALIBRATION_SCREEN_PROP, SAVED_CALIBRATION_BOUNDS_PROP, SAVED_CALIBRATION_PAPER_PROP,
			SAVED_CALIBRATION_MANUAL_PROP);

	protected static final String MARKER_RADIUS_MESSAGE = "MARKER_RADIUS has an invalid value: %d. Acceptable values are "
			+ "between 1 and 20.";
	protected static final String LASER_COLOR_MESSAGE = "LASER_COLOR has an invalid value: %s. Acceptable values are "
```

Replace:

```java
			final double[] b = numbers(bounds, ",", 4);
			final String paper = prop.getProperty(SAVED_CALIBRATION_PAPER_PROP);
			return Optional.of(new SavedCalibration(camera, size(feed, "x"), size(screen, "x"),
					new Rect(b[0], b[1], b[2], b[3]),
					paper == null ? Optional.empty() : Optional.of(size(paper, ","))));
		} catch (final IllegalArgumentException e) {
			logger.warn("Ignoring the saved calibration, which can't be read: {}", e.getMessage());
			return Optional.empty();
		}
```

with:

```java
			final double[] b = numbers(bounds, ",", 4);
			final String paper = prop.getProperty(SAVED_CALIBRATION_PAPER_PROP);
			return Optional.of(new SavedCalibration(camera, size(feed, "x"), size(screen, "x"),
					new Rect(b[0], b[1], b[2], b[3]),
					paper == null ? Optional.empty() : Optional.of(size(paper, ",")),
					Boolean.parseBoolean(prop.getProperty(SAVED_CALIBRATION_MANUAL_PROP))));
		} catch (final IllegalArgumentException e) {
			logger.warn("Ignoring the saved calibration, which can't be read: {}", e.getMessage());
			return Optional.empty();
		}
```

Replace:

```java
			final Rect b = saved.bounds();
			prop.setProperty(SAVED_CALIBRATION_BOUNDS_PROP,
					b.getMinX() + "," + b.getMinY() + "," + b.getWidth() + "," + b.getHeight());
			saved.paper().ifPresent(paper -> prop.setProperty(SAVED_CALIBRATION_PAPER_PROP, format(paper, ",")));
		}

		for (final String key : otherProperties.stringPropertyNames()) {
			prop.setProperty(key, otherProperties.getProperty(key));
```

with:

```java
			final Rect b = saved.bounds();
			prop.setProperty(SAVED_CALIBRATION_BOUNDS_PROP,
					b.getMinX() + "," + b.getMinY() + "," + b.getWidth() + "," + b.getHeight());
			saved.paper().ifPresent(paper -> prop.setProperty(SAVED_CALIBRATION_PAPER_PROP, format(paper, ",")));
			if (saved.manual()) prop.setProperty(SAVED_CALIBRATION_MANUAL_PROP, "true");
		}

		for (final String key : otherProperties.stringPropertyNames()) {
			prop.setProperty(key, otherProperties.getProperty(key));
```

`AppState` keeps compiling: a check's kept result, and the measurement's (which Task 2 removes), carry the saved calibration's mark; a new calibration isn't marked yet (Task 3 decides):

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
        when (outcome) {
            is CalibrationCheck.Kept -> {
                // Kept as saved, or, for a drift within twice the tolerance, at the fresh measurement, which is
                // then what is remembered
                val kept = SavedCalibration(saved.camera, saved.feed, saved.screen, outcome.bounds(), saved.paper)
                calibrationState.value?.applySaved(kept)
                currentCalibration = kept
                if (kept.bounds != saved.bounds && settings.rememberCalibration()) {
                    settings.setSavedCalibration(kept)
```

with:

```kotlin
        when (outcome) {
            is CalibrationCheck.Kept -> {
                // Kept as saved, or, for a drift within twice the tolerance, at the fresh measurement, which is
                // then what is remembered
                val kept = SavedCalibration(saved.camera, saved.feed, saved.screen, outcome.bounds(), saved.paper, saved.manual)
                calibrationState.value?.applySaved(kept)
                currentCalibration = kept
                if (kept.bounds != saved.bounds && settings.rememberCalibration()) {
                    settings.setSavedCalibration(kept)
```

Replace:

```kotlin
            val measurement = { PatternMeasurement("Calibration measurement", uncroppedDetector(camera, calibration), checkClock, Optional.of(calibration.bounds)).work() }
            startPatternRun(arena, camera, measurement) { result ->
                checkState.value = CheckState.Idle
                if (result is PatternMeasurement.Measured && currentCalibration === calibration && settings.rememberCalibration()) {
                    val measured = SavedCalibration(calibration.camera, calibration.feed, calibration.screen, result.median(), calibration.paper)
                    currentCalibration = measured
                    settings.setSavedCalibration(measured)
                    saveSettings()
                }
```

with:

```kotlin
            val measurement = { PatternMeasurement("Calibration measurement", uncroppedDetector(camera, calibration), checkClock, Optional.of(calibration.bounds)).work() }
            startPatternRun(arena, camera, measurement) { result ->
                checkState.value = CheckState.Idle
                if (result is PatternMeasurement.Measured && currentCalibration === calibration && settings.rememberCalibration()) {
                    val measured = SavedCalibration(calibration.camera, calibration.feed, calibration.screen, result.median(), calibration.paper, calibration.manual)
                    currentCalibration = measured
                    settings.setSavedCalibration(measured)
                    saveSettings()
                }
```

Replace:

```kotlin
        val camera = cameraState.value
        val screen = placementState.value?.screen
        // Made without a projector screen, a calibration can't be matched to one next time
        currentCalibration = if (camera != null && screen != null) {
            SavedCalibration(camera.name, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), Size(screen.width, screen.height), cameraBounds, paper)
        } else {
            null
        }
        if (settings.rememberCalibration()) {
```

with:

```kotlin
        val camera = cameraState.value
        val screen = placementState.value?.screen
        // Made without a projector screen, a calibration can't be matched to one next time
        currentCalibration = if (camera != null && screen != null) {
            SavedCalibration(camera.name, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), Size(screen.width, screen.height), cameraBounds, paper, false)
        } else {
            null
        }
        if (settings.rememberCalibration()) {
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationFlow' --tests 'com.shootoff.config.TestSavedCalibrationSettings' :javafx-app:test --tests 'com.shootoff.config.TestConfigurationKeepsComposeKeys' --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints). Expected: `755/755 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/calibration/CalibrationFlow.java core/src/main/java/com/shootoff/config/SavedCalibration.java core/src/main/java/com/shootoff/config/Settings.java compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java core/src/test/java/com/shootoff/config/TestSavedCalibrationSettings.java javafx-app/src/test/java/com/shootoff/config/TestConfigurationKeepsComposeKeys.java compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocksViews.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt
git commit -m "Core: an unattended calibration that ends quietly, and a marker for a remembered manual box"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

---

### Task 2: The measurement after a calibration goes, and the drill restart held back for it

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (remove `calibrationFinishedByCamera`, `uncroppedDetector`, `aroundCameraCalibration`, `holdingDrillRestart`, `deferredDrillRestart`, `runDeferredDrillRestart`, `dropDeferredDrillRestart`; simplify `drillForCalibration`, `cancelCheck`, `setRememberCalibration`, `detachCamera`, `closeArena`, `startDrill`, `close`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt` (remove `CalibrationViews.calibrationFinishedByCamera` and `aroundCameraCalibration`; simplify `calibrate`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt` (remove `CheckState.Measuring` and `PatternMeasurement.work()`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/{TestRememberedCalibration,TestCalibrationPausesTheDrill,TestSetupScreen,TestRangeScreen}.kt`
- Tests deleted (9):
  - `TestRememberedCalibration`: `withCropOnTheMeasuredMedianIsInFullFrameCoordinates`, `afterAnAutoCalibrationWithRememberOnThePatternIsMeasuredAndItsMedianRemembered`, `cancellingTheMeasurementLeavesTheCalibrationsOwnBoundsRemembered`.
  - `TestCalibrationPausesTheDrill`: `anUnpausableProjectorDrillRestartsOnlyAfterTheMeasurementEnds`, `f3DuringMeasuringDoesNothingAndTheDrillStaysPaused`, `cameraLostWhileMeasuringLeavesTheUnpausableDrillStopped`, `closingTheArenaWhileMeasuringNeverStartsTheDrill`, `aCameraDrillStartedDuringMeasuringIsStillRunningAfterItEnds`, together with its `CountingDrill` class.
  - `TestSetupScreen`: `whileTheCalibrationIsMeasuredForNextTimeSetupSaysSoWithCancel`.

**Interfaces:**
- Consumes: Task 1's `SavedCalibration.manual`.
- Produces:
  - `CheckState` is `Idle | Checking | Moved | NotVerified | DoesntFit`, and `CheckState.showsPattern` is `this == CheckState.Checking`; Task 3 adds two states to both.
  - `CalibrationViews` has `showCalibratingFeed`, `restoreSelectedView` and `calibrationSucceeded(cameraBounds, paper)`; Task 3 adds `byCamera` to the last.
  - `AppState.cancelCheck()` handles `Checking` only.

**Why (spec Revision 3, "Removed").** The measurement only existed to save a camera-found calibration's bounds the way the check measures them. From Task 3 on those bounds are never saved, so the measurement, and the held restart that kept a Pause-less drill from running under it, have nothing left to do. The flow restarts such a drill the moment calibration ends, as it did before Plan 7.

- [ ] **Step 1: Change the tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

Replace:

```kotlin
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.config.CalibrationOption
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
```

with:

```kotlin
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
```

Replace:

```kotlin
        app.setRememberCalibration(false)
        assertEquals(emptyMap<String, String>(), savedKeys())
    }

    // Final review (Plan 7): with CalibrationOption.CROP, CameraManager crops each frame to the projection
    // before ComposeCameraView.frameTap hands it to the measurement, so the detector sees the pattern near
    // the crop's own origin, not full-frame coordinates. The saved median must be shifted back by the
    // crop's own origin, so it lines up with the relaunch check's full-frame detections.
    @Test
    fun withCropOnTheMeasuredMedianIsInFullFrameCoordinates() {
        app.setRememberCalibration(true)
        app.settings.setCalibratedFeedBehavior(CalibrationOption.CROP)
        openArenaOnTheProjector()
        // As a cropped frame's detector would report it: near the crop's own origin, not the camera's
        seen.set(Optional.of(Rect(2.0, -1.0, 400.0, 300.0)))

        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        assertTrue(app.camera.value!!.isCroppingFeedToProjection)
        assertEquals(CheckState.Measuring, app.check.value)
        sendFramesUntil { app.check.value == CheckState.Idle }

        // The crop's origin (100, 80) added back to the detection (2, -1): full-frame coordinates
        assertEquals("102.0,79.0,400.0,300.0", savedKeys()["shootoff.arena.calibration.bounds"])
    }

    @Test
    fun openingTheArenaChecksTheSavedCalibrationAndKeepsItWhenThePatternIsInPlace() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
```

with:

```kotlin
        app.setRememberCalibration(false)
        assertEquals(emptyMap<String, String>(), savedKeys())
    }

    @Test
    fun openingTheArenaChecksTheSavedCalibrationAndKeepsItWhenThePatternIsInPlace() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
```

Replace:

```kotlin
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        calibrateWithTheCamera(Rect(120.0, 90.0, 400.0, 300.0))

        // With Remember on, the new calibration is measured next; once that ends, the arena's own
        // background is back, not the check's pattern
        assertEquals(CheckState.Measuring, app.check.value)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        app.cancelCheck()
        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertEquals(Rect(120.0, 90.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }
```

with:

```kotlin
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        calibrateWithTheCamera(Rect(120.0, 90.0, 400.0, 300.0))

        // Once calibration ends, the arena's own background is back, not the check's pattern
        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertEquals(Rect(120.0, 90.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }
```

Replace:

```kotlin
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertNotNull(app.calibratedAt.value)
    }

    @Test
    fun afterAnAutoCalibrationWithRememberOnThePatternIsMeasuredAndItsMedianRemembered() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
        seen.set(Optional.of(Rect(102.0, 80.0, 400.0, 300.0)))

        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        // Saved at once as calibrated, then measured as the check will measure it next time
        assertEquals(SAVED_KEYS, savedKeys())
        assertEquals(CheckState.Measuring, app.check.value)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        sendFramesUntil { app.check.value == CheckState.Idle }

        assertEquals("102.0,80.0,400.0,300.0", savedKeys()["shootoff.arena.calibration.bounds"])
        // This session keeps the calibration it made
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertNull(app.arena.value!!.background.value)
    }

    @Test
    fun aManualBoxCalibrationIsRememberedAsTheBoxWithoutMeasuring() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
```

with:

```kotlin
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertNotNull(app.calibratedAt.value)
    }

    @Test
    fun aManualBoxCalibrationIsRememberedAsTheBoxWithoutMeasuring() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
```

Replace:

```kotlin
        assertEquals(CheckState.Idle, app.check.value)
        assertEquals("90.0,70.0,420.0,310.0", savedKeys()["shootoff.arena.calibration.bounds"])
    }

    @Test
    fun cancellingTheMeasurementLeavesTheCalibrationsOwnBoundsRemembered() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.cancelCheck()

        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertEquals(SAVED_KEYS, savedKeys())
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    @Test
    fun aSmallDriftIsKeptAtTheFreshMeasurementWhichIsRememberedFromThenOn() {
        remembered()
        // 10 px: past the 8 px tolerance, within twice it
```

with:

```kotlin
        assertEquals(CheckState.Idle, app.check.value)
        assertEquals("90.0,70.0,420.0,310.0", savedKeys()["shootoff.arena.calibration.bounds"])
    }

    @Test
    fun aSmallDriftIsKeptAtTheFreshMeasurementWhichIsRememberedFromThenOn() {
        remembered()
        // 10 px: past the 8 px tolerance, within twice it
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationPausesTheDrill.kt`:

Replace:

```kotlin

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.Shot
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
```

with:

```kotlin

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
```

Replace:

```kotlin
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

/** Spec §8 Revision 2, decision 2: calibration pauses the drill, and never resets it. */
class TestCalibrationPausesTheDrill {
    private val app = AppFixture.appWithCamera()

    // A projector drill with no Pause button, like AppFixture.unpausableDrill, that counts its own starts:
    // proves a drill was never started, not just that it isn't running any more (Task 8 review fix round 2)
    // Not private: entry.newInstance() reflects into it from another package, which a private constructor blocks
    class CountingDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Counting drill", "2.0", "ShootOFF tests", "Counts its starts", true)

        override fun start(host: ExerciseHost) {
            starts.incrementAndGet()
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}

        companion object {
            val starts = AtomicInteger()
        }
    }

    @AfterEach
    fun close() = app.close()

    private fun pauseLabel(): String? = app.drill.buttons.value.firstOrNull { it.label == "Pause" || it.label == "Resume" }?.label
```

with:

```kotlin
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/** Spec §8 Revision 2, decision 2: calibration pauses the drill, and never resets it. */
class TestCalibrationPausesTheDrill {
    private val app = AppFixture.appWithCamera()

    @AfterEach
    fun close() = app.close()

    private fun pauseLabel(): String? = app.drill.buttons.value.firstOrNull { it.label == "Pause" || it.label == "Resume" }?.label
```

Replace:

```kotlin
        assertNotSame(first, app.runner.running.value!!.host)
        assertEquals("Unpausable drill", app.runner.running.value!!.host.name)
    }

    // Review fix (Task 8 round 1): with Remember on, the pattern is measured again after this calibration;
    // restarting an unpausable drill before that measurement ends would run it, deaf, under the cover.
    @Test
    fun anUnpausableProjectorDrillRestartsOnlyAfterTheMeasurementEnds() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        val first = app.runner.running.value!!.host
        app.setRememberCalibration(true)
```

with:

```kotlin
        assertNotSame(first, app.runner.running.value!!.host)
        assertEquals("Unpausable drill", app.runner.running.value!!.host.name)
    }

    // With Remember on nothing follows a calibration any more (spec §8 Revision 3): a drill with no Pause
    // button starts afresh as soon as the calibration ends, as it did before Plan 7
    @Test
    fun withRememberOnAnUnpausableProjectorDrillRestartsAsSoonAsCalibrationEnds() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        val first = app.runner.running.value!!.host
        app.setRememberCalibration(true)
```

Replace:

```kotlin
        assertNull(app.runner.running.value)

        calibrateWithTheCamera()

        // Measuring the fresh calibration for next time: the drill must not run, deaf, under its cover
        assertEquals(CheckState.Measuring, app.check.value)
        assertNull(app.runner.running.value)

        app.cancelCheck()

        assertEquals(CheckState.Idle, app.check.value)
        assertNotSame(first, app.runner.running.value!!.host)
        assertEquals("Unpausable drill", app.runner.running.value!!.host.name)
    }

    // Review fix (Task 8 round 1): F3 must not resume shot detection, or the drill's rounds, under the
    // pattern a measurement shows for the remembered calibration.
    @Test
    fun f3DuringMeasuringDoesNothingAndTheDrillStaysPaused() {
        startThePausingDrill()
        app.setRememberCalibration(true)

        app.handleKey(Key.F6, KeyEventType.KeyDown)
        awaitTrue { pauseLabel() == "Resume" }
        calibrateWithTheCamera()

        assertEquals(CheckState.Measuring, app.check.value)
        assertFalse(app.perform(Shortcut.PAUSE_DRILL))
        assertEquals("Resume", pauseLabel())
    }

    // Review fix (Task 8 round 1): F3 must not resume shot detection, or the drill's rounds, while
    // calibration itself is under way (before any pattern the check or measurement shows).
    @Test
    fun f3DuringCalibrationDoesNothing() {
```

with:

```kotlin
        assertNull(app.runner.running.value)

        calibrateWithTheCamera()

        assertEquals(CheckState.Idle, app.check.value)
        assertNotSame(first, app.runner.running.value!!.host)
        assertEquals("Unpausable drill", app.runner.running.value!!.host.name)
    }

    // Review fix (Task 8 round 1): F3 must not resume shot detection, or the drill's rounds, while
    // calibration itself is under way (before any pattern the check or measurement shows).
    @Test
    fun f3DuringCalibrationDoesNothing() {
```

Replace:

```kotlin
        assertFalse(app.perform(Shortcut.PAUSE_DRILL))
        assertEquals("Resume", pauseLabel())
    }

    // Review fix (Task 8 round 2): detachCamera must drop a restart a measurement was holding back, not
    // release it — pauseOrStopProjectorDrill finds nothing to stop (the drill is already stopped, held), so
    // releasing it here would run an unpausable drill on an uncalibrated arena with no camera (spec §8
    // Revision 2, decision 7).
    @Test
    fun cameraLostWhileMeasuringLeavesTheUnpausableDrillStopped() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        app.setRememberCalibration(true)

        app.startCalibration()
        calibrateWithTheCamera()
        assertEquals(CheckState.Measuring, app.check.value)

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertNull(app.runner.running.value)
    }

    // Review fix (Task 8 round 2): closeArena must drop a restart a measurement was holding back, not
    // release it — releasing it would start the drill (running its exercise's start(), which can add targets
    // or play sounds) only to stop it again a moment later, for an arena that is going away anyway.
    @Test
    fun closingTheArenaWhileMeasuringNeverStartsTheDrill() {
        AppFixture.setUpForProjectorDrills(app)
        val entry = V2ExerciseEntry(CountingDrill::class.java, CountingDrill().metadata())
        CountingDrill.starts.set(0)
        assertTrue(app.startDrill(entry))
        // The exercise's own start() runs on its own thread (ExerciseHostSupport.run); wait for the first one
        awaitTrue { CountingDrill.starts.get() == 1 }
        app.setRememberCalibration(true)

        app.startCalibration()
        calibrateWithTheCamera()
        assertEquals(CheckState.Measuring, app.check.value)

        app.closeArena()

        assertNull(app.runner.running.value)
        // Long enough for a restart's start(), if one were wrongly made, to have run on its own thread
        Thread.sleep(200)
        assertEquals(1, CountingDrill.starts.get())
    }

    // Review fix (Task 8 round 2): a camera drill the owner starts during Measuring is their own choice; the
    // restart a measurement is holding back must not stop it and replace it once the measurement ends.
    @Test
    fun aCameraDrillStartedDuringMeasuringIsStillRunningAfterItEnds() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        app.setRememberCalibration(true)

        app.startCalibration()
        calibrateWithTheCamera()
        assertEquals(CheckState.Measuring, app.check.value)

        assertTrue(app.startDrill(AppFixture.feedDrill))
        val host = app.runner.running.value!!.host

        app.cancelCheck()

        assertEquals(CheckState.Idle, app.check.value)
        assertSame(host, app.runner.running.value!!.host)
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
```

with:

```kotlin
        assertFalse(app.perform(Shortcut.PAUSE_DRILL))
        assertEquals("Resume", pauseLabel())
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`:

Replace:

```kotlin
        compose.onNodeWithTag("calibration-complete").assertTextEquals("Calibration complete ✓ 14:05")
        step(Step.CALIBRATE, StepState.DONE)
    }

    @Test
    fun whileTheCalibrationIsMeasuredForNextTimeSetupSaysSoWithCancel() {
        app.close()
        app = AppFixture.appWithCamera()
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("setup-calibrate").performClick()
        compose.runOnIdle { app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0) }

        compose.onNodeWithText("Measuring the calibration for next time…").assertExists()
        compose.onNodeWithTag("setup-cancel").performClick()
        compose.onNodeWithTag("setup-calibrate").assertExists()
        compose.onNodeWithTag("calibration-complete").assertExists()
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
        app.navigate(Destination.SETUP)
```

with:

```kotlin
        compose.onNodeWithTag("calibration-complete").assertTextEquals("Calibration complete ✓ 14:05")
        step(Step.CALIBRATE, StepState.DONE)
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
        app.navigate(Destination.SETUP)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt`:

Replace:

```kotlin
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class TestRangeScreen {
    @get:Rule
    val compose = createComposeRule()
```

with:

```kotlin
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.RangeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TestRangeScreen {
    @get:Rule
    val compose = createComposeRule()
```

Replace:

```kotlin
    fun theResumeButtonIsDisabledWhileAPatternShows() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.pausingDrill))
        assertTrue(app.perform(Shortcut.PAUSE_DRILL))
        app.setRememberCalibration(true)
        showApp()
        compose.onNodeWithTag("drill-button-Resume").assertIsEnabled()

        // Not F6/perform: that also navigates to Setup, and this drill's card only shows on Range
        assertTrue(app.startCalibration())
        app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        compose.waitForIdle()

        assertEquals(CheckState.Measuring, app.check.value)
        compose.onNodeWithTag("drill-button-Resume").assertIsNotEnabled()
    }

    @Test
```

with:

```kotlin
    fun theResumeButtonIsDisabledWhileAPatternShows() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.pausingDrill))
        assertTrue(app.perform(Shortcut.PAUSE_DRILL))
        showApp()
        compose.onNodeWithTag("drill-button-Resume").assertIsEnabled()

        // Not F6/perform: that also navigates to Setup, and this drill's card only shows on Range
        assertTrue(app.startCalibration())
        compose.waitForIdle()

        assertTrue(app.calibration.value!!.state.value.calibrating)
        compose.onNodeWithTag("drill-button-Resume").assertIsNotEnabled()
    }

    @Test
```

- [ ] **Step 2: Run the tests to verify the new one fails**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestCalibrationPausesTheDrill' --console=plain`
Expected: FAIL: `withRememberOnAnUnpausableProjectorDrillRestartsAsSoonAsCalibrationEnds` with "expected: <Idle> but was: <Measuring>".

- [ ] **Step 3: Remove the measurement and the held restart**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.PatternMeasurement
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.DiagnosticMessage
```

with:

```kotlin

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.DiagnosticMessage
```

Replace:

```kotlin
 * @param wallClock the time of day, which the calibration status shows
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check or a
 *   measurement shows the pattern
 */
class AppState(
    val settings: Settings,
    val catalog: ExerciseCatalog = ExerciseCatalog(),
```

with:

```kotlin
 * @param wallClock the time of day, which the calibration status shows
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check shows
 *   the pattern
 */
class AppState(
    val settings: Settings,
    val catalog: ExerciseCatalog = ExerciseCatalog(),
```

Replace:

```kotlin
    private val reconnectMillis: Long = 2000,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check or measurement */
        const val PATTERN_SETTLE_MILLIS = 500L
    }

    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

with:

```kotlin
    private val reconnectMillis: Long = 2000,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check */
        const val PATTERN_SETTLE_MILLIS = 500L
    }

    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

Replace:

```kotlin
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    private val checkFrames = LatestFrame()

    // The check's or the measurement's run, while its pattern shows; read by CalibratingCamera on timer threads
    @Volatile
    private var patternRun: PatternRun<*>? = null
    private var patternWatch: Job? = null
    private val waitingForState = MutableStateFlow<String?>(null)
```

with:

```kotlin
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    private val checkFrames = LatestFrame()

    // The check's run, while its pattern shows; read by CalibratingCamera on timer threads
    @Volatile
    private var patternRun: PatternRun<*>? = null
    private var patternWatch: Job? = null
    private val waitingForState = MutableStateFlow<String?>(null)
```

Replace:

```kotlin
            pauseOrStopProjectorDrill()
        } else if (running != null) {
            pauseDrill()
        }
        // Review fix (Task 8 round 2): a drill a measurement was holding back is dropped, not restarted —
        // there's no camera left to calibrate it with, or to measure the pattern against (spec §8 Revision 2,
        // decision 7)
        dropDeferredDrillRestart()
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        currentCalibration = null
        // For calibration the camera going is the arena going: calibration ends, and both projections go
```

with:

```kotlin
            pauseOrStopProjectorDrill()
        } else if (running != null) {
            pauseDrill()
        }
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        currentCalibration = null
        // For calibration the camera going is the arena going: calibration ends, and both projections go
```

Replace:

```kotlin

    // Starts [start] once the arena is on the projector (spec §8 Revision 2, decision 3): full screen, as big
    // as the projector's screen, and settled there for [patternSettleMillis], so the pattern is never measured
    // in a window on its way (or, after F11, off) the projector. Leaving the projector stops the run quietly
    // (the state stays, so its return starts a fresh run). Runs until the check or measurement ends.
    private fun watchArenaForPattern(arena: ArenaModel, start: () -> Unit) {
        patternWatch?.cancel()
        val screen = placementState.value?.screen
        patternWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
```

with:

```kotlin

    // Starts [start] once the arena is on the projector (spec §8 Revision 2, decision 3): full screen, as big
    // as the projector's screen, and settled there for [patternSettleMillis], so the pattern is never measured
    // in a window on its way (or, after F11, off) the projector. Leaving the projector stops the run quietly
    // (the state stays, so its return starts a fresh run). Runs until the check ends.
    private fun watchArenaForPattern(arena: ArenaModel, start: () -> Unit) {
        patternWatch?.cancel()
        val screen = placementState.value?.screen
        patternWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
```

Replace:

```kotlin
    // Whether an arena of [size] fills [screen] (to the pixel, give or take rounding)
    private fun fills(size: Size, screen: Rect?): Boolean =
        screen == null || (abs(size.width - screen.width) <= 1 && abs(size.height - screen.height) <= 1)

    // The arena left the projector mid-run. The run stops without a word, but the state stays (Checking or
    // Measuring) and the watch keeps going, so a fresh run starts once the arena is back.
    private fun stopRunQuietlyOffTheProjector() {
        val run = patternRun ?: return
        patternRun = null
        cameraView.frameTap = null
```

with:

```kotlin
    // Whether an arena of [size] fills [screen] (to the pixel, give or take rounding)
    private fun fills(size: Size, screen: Rect?): Boolean =
        screen == null || (abs(size.width - screen.width) <= 1 && abs(size.height - screen.height) <= 1)

    // The arena left the projector mid-run. The run stops without a word, but the state stays (Checking) and
    // the watch keeps going, so a fresh run starts once the arena is back.
    private fun stopRunQuietlyOffTheProjector() {
        val run = patternRun ?: return
        patternRun = null
        cameraView.frameTap = null
```

Replace:

```kotlin
        }, uiThread) { run, result ->
            if (patternRun === run && result != null) {
                patternRun = null
                cameraView.frameTap = null
                // A result ends the check or measurement: the watch has nothing left to restart
                patternWatch?.cancel()
                patternWatch = null
                done(result)
            }
```

with:

```kotlin
        }, uiThread) { run, result ->
            if (patternRun === run && result != null) {
                patternRun = null
                cameraView.frameTap = null
                // A result ends the check: the watch has nothing left to restart
                patternWatch?.cancel()
                patternWatch = null
                done(result)
            }
```

Replace:

```kotlin
            is CalibrationCheck.NotVerified -> checkState.value = CheckState.NotVerified(outcome.reason())
        }
    }

    // After a calibration the camera found, with Remember on, the pattern is measured once more, the way the
    // check measures it (the median of five detections), and that is what is remembered (spec §8 Revision 2,
    // decision 3). The calibration's own bounds stay saved until then, and if it can't be measured.
    override fun calibrationFinishedByCamera() {
        val arena = arenaState.value ?: return
        val camera = cameraState.value ?: return
        val calibration = currentCalibration ?: return
        if (!settings.rememberCalibration()) return
        checkState.value = CheckState.Measuring
        watchArenaForPattern(arena) {
            val measurement = { PatternMeasurement("Calibration measurement", uncroppedDetector(camera, calibration), checkClock, Optional.of(calibration.bounds)).work() }
            startPatternRun(arena, camera, measurement) { result ->
                checkState.value = CheckState.Idle
                if (result is PatternMeasurement.Measured && currentCalibration === calibration && settings.rememberCalibration()) {
                    val measured = SavedCalibration(calibration.camera, calibration.feed, calibration.screen, result.median(), calibration.paper, calibration.manual)
                    currentCalibration = measured
                    settings.setSavedCalibration(measured)
                    saveSettings()
                }
                // Measured or not, the measurement is over: a drill it held back from restarting runs now
                // (Task 8 review fix)
                runDeferredDrillRestart()
            }
        }
    }

    // With CalibrationOption.CROP, CameraManager crops every frame to the projection before frameTap hands
    // it to the measurement (CameraManager, around updateFrame's crop and updateBackground calls), so the
    // detector's rects are relative to the crop's own top-left corner, not the camera's full frame. The
    // saved median must be in full-frame coordinates, to match the relaunch check's own (uncropped)
    // detections (spec §8 Revision 2, decision 3's "both sides measure the same way"), so [calibration]'s
    // own origin — the crop's own rectangle — is added back to every detection while cropping is on.
    //
    // Final review (Plan 7): turning cropping off for the measurement's duration, and back on afterwards,
    // was the other option; it was rejected because restoring it needs a call on every one of the
    // measurement's several end paths (done, cancel, stopCheckQuietly, camera loss, arena close) — a single
    // missed one would leave the Setup feed uncropped, or a later measurement uncorrected. Adding the
    // offset back here is a pure, stateless correction with nothing left to restore.
    private fun uncroppedDetector(camera: CameraManager, calibration: SavedCalibration): CalibrationCheck.Detector<BufferedImage> {
        val base = detector(camera)
        return CalibrationCheck.Detector { frame ->
            base.detect(frame).map { detected ->
                if (camera.isCroppingFeedToProjection) {
                    Rect(detected.minX + calibration.bounds.minX, detected.minY + calibration.bounds.minY, detected.width, detected.height)
                } else {
                    detected
                }
            }
        }
    }

    // Ends a check or a measurement without a word (something replaced it); the arena's look comes back.
    // Review fix (Task 8 round 2): never touches deferredDrillRestart itself — every caller decides, right
    // after, whether to release it (runDeferredDrillRestart) or drop it (dropDeferredDrillRestart).
    private fun stopCheckQuietly() {
        patternWatch?.cancel()
        patternWatch = null
        val run = patternRun
```

with:

```kotlin
            is CalibrationCheck.NotVerified -> checkState.value = CheckState.NotVerified(outcome.reason())
        }
    }

    // Ends a check without a word (something replaced it); the arena's look comes back
    private fun stopCheckQuietly() {
        patternWatch?.cancel()
        patternWatch = null
        val run = patternRun
```

Replace:

```kotlin
        run?.stop()
        checkState.value = CheckState.Idle
    }

    /**
     * Cancel on Setup while the pattern shows: a check ends with the arena uncalibrated and "not verified"; a
     * measurement ends leaving the calibration's own bounds remembered, and a drill it held back restarts.
     */
    fun cancelCheck() {
        when (checkState.value) {
            CheckState.Checking -> {
                stopCheckQuietly()
                checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED)
            }
            CheckState.Measuring -> {
                stopCheckQuietly()
                runDeferredDrillRestart()
            }
            else -> {}
        }
    }

    /**
     * "Remember calibration". On: the arena's calibration, now and after each calibration, is saved for the
     * next session. Off: nothing is saved, the saved one is forgotten, a check under way stops, and a drill a
     * measurement held back restarts (there's no measurement left to remember it for).
     */
    fun setRememberCalibration(remember: Boolean) {
        // A no-op when nothing changed: turning it on again with nothing calibrated this session (so
        // currentCalibration is still null) would otherwise erase an already-saved calibration
        if (remember == rememberState.value) return
        rememberState.value = remember
        settings.setRememberCalibration(remember)
        settings.setSavedCalibration(if (remember) currentCalibration else null)
        if (!remember && checkState.value.showsPattern) {
            stopCheckQuietly()
            runDeferredDrillRestart()
        }
        saveSettings()
    }

    private fun saveSettings() {
```

with:

```kotlin
        run?.stop()
        checkState.value = CheckState.Idle
    }

    /** Cancel on Setup while the check's pattern shows: the arena stays uncalibrated, "not verified". */
    fun cancelCheck() {
        if (checkState.value != CheckState.Checking) return
        stopCheckQuietly()
        checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED)
    }

    /**
     * "Remember calibration". On: the arena's calibration, now and after each calibration, is saved for the
     * next session. Off: nothing is saved, the saved one is forgotten, and a check under way stops.
     */
    fun setRememberCalibration(remember: Boolean) {
        // A no-op when nothing changed: turning it on again with nothing calibrated this session (so
        // currentCalibration is still null) would otherwise erase an already-saved calibration
        if (remember == rememberState.value) return
        rememberState.value = remember
        settings.setRememberCalibration(remember)
        settings.setSavedCalibration(if (remember) currentCalibration else null)
        if (!remember && checkState.value.showsPattern) stopCheckQuietly()
        saveSettings()
    }

    private fun saveSettings() {
```

Replace:

```kotlin
    /** The arena window closed: calibration or a check ends, a projector drill stops, and Range asks for setup again. */
    fun closeArena() {
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        // Review fix (Task 8 round 2): dropped, not restarted — starting it now only to stop it again below
        // (runner.stopProjectorExercise()) could add targets or play sounds for a drill about to go anyway
        dropDeferredDrillRestart()
        currentCalibration = null
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
```

with:

```kotlin
    /** The arena window closed: calibration or a check ends, a projector drill stops, and Range asks for setup again. */
    fun closeArena() {
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        currentCalibration = null
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
```

Replace:

```kotlin
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        if (entry.isProjectorOnly && !projectorReady()) return false
        arenaState.value?.showGrid(false)
        val started = runner.start(entry)
        if (started) {
            destinationState.value = Destination.RANGE
            // Review fix (Task 8 round 2): the owner's own choice wins over a drill a measurement was holding
            // back — that restart, once released, must not stop this one and replace it
            dropDeferredDrillRestart()
        }
        return started
    }

    fun stopDrill() = runner.stop()
```

with:

```kotlin
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        if (entry.isProjectorOnly && !projectorReady()) return false
        arenaState.value?.showGrid(false)
        val started = runner.start(entry)
        if (started) destinationState.value = Destination.RANGE
        return started
    }

    fun stopDrill() = runner.stop()
```

Replace:

```kotlin
    }

    // What calibration does to the running drill (spec §8 Revision 2, decision 2): a projector drill is paused,
    // and stays paused afterwards, never restarted; one with no Pause button is stopped and started afresh
    // after a success, as before. A camera drill doesn't use the arena and is left alone.
    //
    // Review fix (Task 8 round 1): the flow runs this restart itself, synchronously, as a camera-found
    // calibration ends — before calibrationFinishedByCamera decides whether a measurement follows. While
    // aroundCameraCalibration is holding restarts (a measurement may follow, and the drill mustn't run,
    // deaf, under its cover), the restart is stashed in deferredDrillRestart instead of running at once.
    //
    // Review fix (Task 8 round 2): a restart still held when a fresh calibration starts (F6 mid-measurement)
    // is carried over as that calibration's own restartExercise, rather than asking pauseOrStopProjectorDrill
    // again — the drill it would look for has already been stopped, so a fresh ask would find nothing and
    // the held restart would never run.
    private val drillForCalibration = CalibrationFlow.Exercises {
        val held = deferredDrillRestart
        if (held != null) {
            deferredDrillRestart = null
            Optional.of(Runnable { if (holdingDrillRestart) deferredDrillRestart = held else held.run() })
        } else {
            pauseOrStopProjectorDrill().map { restart -> Runnable { if (holdingDrillRestart) deferredDrillRestart = restart else restart.run() } }
        }
    }

    // Set only around a camera-found calibration's own end (CalibrationController.calibrate), so
    // drillForCalibration's wrapper above knows to hold a restart back instead of running it there
    @Volatile
    private var holdingDrillRestart = false

    // A drill's restart, held back because a measurement might follow the calibration that stopped it.
    // Review fix (Task 8 round 2): releasing (running) it and dropping (forgetting) it are different, explicit
    // choices made at each call site — never a side effect of stopCheckQuietly, which merely ends the pattern
    // run. Released when the measurement ends (measured, not seen, or cancelled) or Remember goes off
    // mid-measurement; dropped when there's no longer a place, or reason, to restart the drill into: the
    // camera going (detachCamera), the arena or the app closing, or the owner starting a drill of their own.
    private var deferredDrillRestart: Runnable? = null

    private fun runDeferredDrillRestart() {
        val restart = deferredDrillRestart ?: return
        deferredDrillRestart = null
        restart.run()
    }

    private fun dropDeferredDrillRestart() {
        deferredDrillRestart = null
    }

    /**
     * Runs [action] — a camera-found calibration ending — holding back any drill restart it triggers until
     * [action] decides whether a measurement follows: if it does (checkState is Measuring once [action]
     * returns), the measurement's own end releases it later; otherwise it releases at once, here.
     */
    override fun aroundCameraCalibration(action: () -> Unit) {
        holdingDrillRestart = true
        try {
            action()
        } finally {
            holdingDrillRestart = false
            if (checkState.value != CheckState.Measuring) runDeferredDrillRestart()
        }
    }

    // The camera as calibration, the check and the measurement see it: when they turn shot detection back on
    // as they end, it stays off while a pattern still shows (a check or measurement started meanwhile: Plan 6's
    // N1) and while the running drill has it paused (a paused drill turned it off itself)
    private inner class CalibratingCamera(private val camera: CameraManager) : CalibrationCamera by camera {
        override fun setDetecting(isDetecting: Boolean) {
            camera.setDetecting(isDetecting && patternRun == null && runner.running.value?.host?.shotDetectionPaused != true)
        }
```

with:

```kotlin
    }

    // What calibration does to the running drill (spec §8 Revision 2, decision 2): a projector drill is paused,
    // and stays paused afterwards, never restarted; one with no Pause button is stopped and started afresh
    // as calibration ends, as before. A camera drill doesn't use the arena and is left alone.
    private val drillForCalibration = CalibrationFlow.Exercises { pauseOrStopProjectorDrill() }

    // The camera as calibration and the check see it: when they turn shot detection back on as they end, it
    // stays off while a pattern still shows (a check started meanwhile: Plan 6's N1) and while the running
    // drill has it paused (a paused drill turned it off itself)
    private inner class CalibratingCamera(private val camera: CameraManager) : CalibrationCamera by camera {
        override fun setDetecting(isDetecting: Boolean) {
            camera.setDetecting(isDetecting && patternRun == null && runner.running.value?.host?.shotDetectionPaused != true)
        }
```

Replace:

```kotlin
        openGeneration.incrementAndGet()
        stopWatchingForReturn()
        runner.stop()
        closeArena()
        // Belt and suspenders alongside closeArena's own drop (Task 8 round 2): nothing starts on the way out
        dropDeferredDrillRestart()
        cameras.closeAll()
        scope.cancel()
    }
}
```

with:

```kotlin
        openGeneration.incrementAndGet()
        stopWatchingForReturn()
        runner.stop()
        closeArena()
        cameras.closeAll()
        scope.cancel()
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
     * @param cameraBounds the projection on the camera's feed
     * @param paper the perspective paper's size, if auto-calibration found one
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)

    /**
     * A calibration the camera found (not the manual box) has finished and the arena looks as it did
     * before: the pattern may show again, to measure it for the remembered calibration.
     */
    fun calibrationFinishedByCamera() {}

    /**
     * Runs [action] — a calibration the camera found ending — around whatever it does with a projector
     * drill calibration paused or stopped (spec §8 Revision 2, decision 2): a drill with no Pause button,
     * about to restart as [action] ends it, may need to wait instead for a measurement [action] starts
     * (Task 8 review fix round 1). The default just runs it, restarting at once as before.
     */
    fun aroundCameraCalibration(action: () -> Unit) = action()
}

/**
 * Calibrates the projector arena in the Compose app: core's [CalibrationFlow] decides what happens; this
```

with:

```kotlin
     * @param cameraBounds the projection on the camera's feed
     * @param paper the perspective paper's size, if auto-calibration found one
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)
}

/**
 * Calibrates the projector arena in the Compose app: core's [CalibrationFlow] decides what happens; this
```

Replace:

```kotlin
        uiThread(
            Runnable {
                if (generation == expectedGeneration && flow.isCalibrating) {
                    foundPaper = perspectivePaperDims
                    val asked = session
                    views.aroundCameraCalibration {
                        flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas)
                        // The flow has finished (it reported the success, and put the arena's look back)
                        if (asked && !session) views.calibrationFinishedByCamera()
                    }
                }
            },
        )
    }
```

with:

```kotlin
        uiThread(
            Runnable {
                if (generation == expectedGeneration && flow.isCalibrating) {
                    foundPaper = perspectivePaperDims
                    flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas)
                }
            },
        )
    }
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`:

Replace:

```kotlin
import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.PatternMeasurement
import com.shootoff.compose.app.sameCamera
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.SavedCalibration
```

with:

```kotlin
import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.app.sameCamera
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.SavedCalibration
```

Replace:

```kotlin

    /** Waiting for the arena to reach the projector, or looking for the pattern */
    data object Checking : CheckState

    /**
     * After a calibration, measuring the pattern as the check will measure it next time, so that is what is
     * remembered (spec §8 Revision 2, decision 3)
     */
    data object Measuring : CheckState

    data class Moved(val pixels: Int) : CheckState

    data class NotVerified(val reason: Reason) : CheckState

    /** The saved calibration was made with another camera or projector, so it wasn't checked */
    data class DoesntFit(val why: String) : CheckState
}

/** Whether the pattern is on the arena for a check or a measurement, or waiting to go on */
val CheckState.showsPattern: Boolean get() = this == CheckState.Checking || this == CheckState.Measuring

/** What Setup and Range say about a check, or null when there is nothing to say */
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.Checking -> "Checking the saved calibration…"
    CheckState.Measuring -> "Measuring the calibration for next time…"
    is CheckState.Moved -> "The projection moved about $pixels px — recalibrate"
    is CheckState.NotVerified -> when (reason) {
        Reason.PATTERN_NOT_SEEN -> "The pattern wasn't seen: not verified — recalibrate on Setup"
        Reason.NO_CAMERA -> "No camera to check with: not verified — recalibrate on Setup"
```

with:

```kotlin

    /** Waiting for the arena to reach the projector, or looking for the pattern */
    data object Checking : CheckState

    data class Moved(val pixels: Int) : CheckState

    data class NotVerified(val reason: Reason) : CheckState

    /** The saved calibration was made with another camera or projector, so it wasn't checked */
    data class DoesntFit(val why: String) : CheckState
}

/** Whether the pattern is on the arena for a check, or waiting to go on */
val CheckState.showsPattern: Boolean get() = this == CheckState.Checking

/** What Setup and Range say about a check, or null when there is nothing to say */
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.Checking -> "Checking the saved calibration…"
    is CheckState.Moved -> "The projection moved about $pixels px — recalibrate"
    is CheckState.NotVerified -> when (reason) {
        Reason.PATTERN_NOT_SEEN -> "The pattern wasn't seen: not verified — recalibrate on Setup"
        Reason.NO_CAMERA -> "No camera to check with: not verified — recalibrate on Setup"
```

Replace:

```kotlin

    fun take(): BufferedImage? = frame.getAndSet(null)
}

/** What a [PatternRun] does with the camera's frames: check a remembered calibration, or measure the pattern */
interface PatternWork<T : Any> {
    /** Looks at [frame]; the result, once there is one */
    fun offer(frame: BufferedImage): T?

```

with:

```kotlin

    fun take(): BufferedImage? = frame.getAndSet(null)
}

/** What a [PatternRun] does with the camera's frames: check a remembered calibration */
interface PatternWork<T : Any> {
    /** Looks at [frame]; the result, once there is one */
    fun offer(frame: BufferedImage): T?

```

Replace:

```kotlin

    override fun tick() = this@work.tick().orElse(null)
}

/** A [PatternMeasurement], as a [PatternRun] does it */
fun PatternMeasurement<BufferedImage>.work(): PatternWork<PatternMeasurement.Result> = object : PatternWork<PatternMeasurement.Result> {
    override fun offer(frame: BufferedImage) = this@work.offer(frame).orElse(null)

    override fun tick() = this@work.tick().orElse(null)
}

/**
 * The calibration pattern shown on the open arena, alone, while the camera's frames are looked at (spec §8):
 * to check a remembered calibration, or to measure the pattern after a calibration. The frames are looked at
 * in [scope], never on the UI thread, and [onDone] hears the result on the UI thread once the arena's look is
 * back, or null if the run was stopped. Shot detection is off while the pattern shows, as in calibration,
 * and asked back shortly after.
 *
 * @param work made as the pattern shows, so its time limit starts then
 */
class PatternRun<T : Any>(
```

with:

```kotlin

    override fun tick() = this@work.tick().orElse(null)
}

/**
 * The calibration pattern shown on the open arena, alone, while the camera's frames are looked at to check a
 * remembered calibration (spec §8). The frames are looked at in [scope], never on the UI thread, and [onDone]
 * hears the result on the UI thread once the arena's look is back, or null if the run was stopped. Shot
 * detection is off while the pattern shows, as in calibration, and asked back shortly after.
 *
 * @param work made as the pattern shows, so its time limit starts then
 */
class PatternRun<T : Any>(
```

Replace:

```kotlin
        }
    }

    /**
     * Stops the run; on the UI thread. Never touches the work itself: a check's or a measurement's `offer`
     * is `synchronized` and can hold its lock for as long as a detection takes (hundreds of milliseconds when
     * the pattern isn't found), which would freeze the UI thread here (spec §8 rule 6). [finish]'s
     * compare-and-set makes the first end final, so a result arriving after this is ignored.
     */
    fun stop() = finish(null)
```

with:

```kotlin
        }
    }

    /**
     * Stops the run; on the UI thread. Never touches the work itself: a check's `offer` is `synchronized` and can hold its lock for as long as a detection takes (hundreds of milliseconds when
     * the pattern isn't found), which would freeze the UI thread here (spec §8 rule 6). [finish]'s
     * compare-and-set makes the first end final, so a result arriving after this is ignored.
     */
    fun stop() = finish(null)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --tests 'com.shootoff.compose.calibration.*' --console=plain`
Expected: BUILD SUCCESSFUL.

Then check nothing refers to the removed names:

```bash
command grep -rnE "Measuring|calibrationFinishedByCamera|aroundCameraCalibration|DeferredDrillRestart|holdingDrillRestart|uncroppedDetector" compose-app/src
```

Expected: no output.

- [ ] **Step 5: Run the gate**

Expected: `747/747 passing; 0 regressions; 0 new failures` (752 + 3 − 9 + 1: Task 1's three, then this task's nine deleted and one added).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationPausesTheDrill.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt
git commit -m "Drop the measurement after a calibration, and the drill restart held back for it"
git log -1 --format=%B
```

---

### Task 3: The arena calibrates itself when it opens

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt` (`startUnattended`, `beginSession`, `putBack`, `foundByCamera`; `calibrationSucceeded` gains `byCamera`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt` (`CheckState.WaitingToCalibrate`, `CheckState.NotFound`, their texts, `showsPattern`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (constructor parameter `calibrationTimers`, `TIMER_POOL`; `calibrateOrCheck`, `calibrateOnTheProjector`; `openArena`, `publish`, `cancelCheck`, `calibrationSucceeded`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt` (the checkbox's wording)
- Modify: `compose-app/src/main/resources/logback.xml` (INFO for `com.shootoff.camera.autocalibration`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/{AppFixture,TestRememberedCalibration,TestNothingBlocks,TestSetupScreen,TestSetupSteps}.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/calibration/{CalibrationFixture,TestCalibrationOnRequest}.kt`

**Interfaces:**
- Consumes:
  - Task 1's `CalibrationFlow.startUnattended(Runnable)`, `AUTO_CALIBRATION_TIMEOUT_UNATTENDED` and `SavedCalibration.manual`.
  - Task 2's `CheckState`, `showsPattern`, `cancelCheck` and `CalibrationViews`.
  - Plan 7's `watchArenaForPattern(arena, start)`, `checkRemembered(arena)`, `stopCheckQuietly()` and `AppFixture.putOnTheProjector`.
- Produces:
  - `fun CalibrationController.startUnattended(onNotFound: () -> Unit)`.
  - `CalibrationViews.calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean)`.
  - `CheckState.WaitingToCalibrate` and `CheckState.NotFound`.
  - `AppState(…, calibrationTimers: CalibrationFlow.Scheduler = AppState.TIMER_POOL)`, the last constructor parameter.
  - `AppFixture.appWithCamera(…, calibrationTimers = …)`.

  Task 4 relies on `CalibrationController.generation` being bumped in `putBack()`; Task 5 relies on the texts of the two new states.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`:

Replace:

```kotlin
        override fun restoreSelectedView() {
            events += "restore view"
        }

        override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
            events += "succeeded $cameraBounds $paper"
        }
    }

```

with:

```kotlin
        override fun restoreSelectedView() {
            events += "restore view"
        }

        override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean) {
            events += "succeeded $cameraBounds $paper"
        }
    }

```

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.camera.MockCamera
import com.shootoff.camera.Shot
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.targets.ManualClock
```

with:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.MockCamera
import com.shootoff.camera.Shot
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.targets.ManualClock
```

Replace:

```kotlin
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
        wallClock: () -> LocalTime = LocalTime::now,
        patternSettleMillis: Long = 0,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
```

with:

```kotlin
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
        wallClock: () -> LocalTime = LocalTime::now,
        patternSettleMillis: Long = 0,
        calibrationTimers: CalibrationFlow.Scheduler = AppState.TIMER_POOL,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
```

Replace:

```kotlin
            wallClock = wallClock,
            detector = { detector },
            checkClock = checkClock,
            patternSettleMillis = patternSettleMillis,
        )
    }

    /**
```

with:

```kotlin
            wallClock = wallClock,
            detector = { detector },
            checkClock = checkClock,
            patternSettleMillis = patternSettleMillis,
            calibrationTimers = calibrationTimers,
        )
    }

    /**
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`:

Replace:

```kotlin
        assertEquals(Message.MANUAL_REQUEST, controller.state.value.message)
        assertNotNull(controller.state.value.box)
    }

    @Test
    fun cancelLeavesTheArenaAndTheCameraAsTheyWereAndRestartsNothing() {
        val restarts = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { restarts += "restart" })
```

with:

```kotlin
        assertEquals(Message.MANUAL_REQUEST, controller.state.value.message)
        assertNotNull(controller.state.value.box)
    }

    @Test
    fun anUnattendedCalibrationThatDoesntFindThePatternPutsTheArenaBackWithoutTheBox() {
        var notFound = 0
        arena.setFullScreen(true)

        controller.startUnattended { notFound++ }
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(arena.covered.value)

        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED)

        assertEquals(1, notFound)
        assertFalse(controller.state.value.calibrating)
        assertEquals(null, controller.state.value.box)
        assertEquals(null, controller.state.value.message)
        assertFalse(fixture.events.contains("show feed"))
        assertEquals(null, arena.background.value)
        assertFalse(arena.covered.value)
        assertTrue(arena.needsCalibrationLabel.value)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    @Test
    fun cancelLeavesTheArenaAndTheCameraAsTheyWereAndRestartsNothing() {
        val restarts = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { restarts += "restart" })
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

Replace:

```kotlin

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
```

with:

```kotlin

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
```

Replace:

```kotlin
import java.awt.image.BufferedImage
import java.io.File
import java.util.Optional
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
```

with:

```kotlin
import java.awt.image.BufferedImage
import java.io.File
import java.util.Optional
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
```

Replace:

```kotlin
            shootoff.arena.calibration.camera=Test camera
            shootoff.arena.calibration.feed=640.0x480.0
            shootoff.arena.calibration.screen=$screen
            shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0
            """.trimIndent(),
        )
        app = if (detector == null) appOn(Settings(file.path, arrayOf())) else appOn(Settings(file.path, arrayOf()), detector)
    }
```

with:

```kotlin
            shootoff.arena.calibration.camera=Test camera
            shootoff.arena.calibration.feed=640.0x480.0
            shootoff.arena.calibration.screen=$screen
            shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0
            shootoff.arena.calibration.manual=true
            """.trimIndent(),
        )
        app = if (detector == null) appOn(Settings(file.path, arrayOf())) else appOn(Settings(file.path, arrayOf()), detector)
    }
```

Replace:

```kotlin
        app.startCalibration()
        app.calibration.value!!.calibrate(bounds, Optional.empty(), false, 0)
    }

    @Test
    fun withRememberOffNothingIsSaved() {
        openArenaOnTheProjector()
        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))
```

with:

```kotlin
        app.startCalibration()
        app.calibration.value!!.calibrate(bounds, Optional.empty(), false, 0)
    }

    // Calibrate, then Done on the manual box where the owner left it (the canvas is the feed's size here)
    private fun calibrateWithTheBox(bounds: Rect) {
        app.startCalibration()
        app.calibration.value!!.flow.calibrated(bounds, Optional.empty(), true)
    }

    private fun calibrating() = app.calibration.value?.state?.value?.calibrating == true

    // An automatic calibration has shown the pattern and is looking for it (in the app the flow starts on the UI
    // thread; here it runs on the watcher's, so the test waits for it to get there)
    private fun awaitAutomaticCalibrationLooking() = awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }

    @Test
    fun withRememberOffNothingIsSaved() {
        openArenaOnTheProjector()
        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))
```

Replace:

```kotlin
        assertFalse(file.readText().contains("shootoff.arena.calibration."))
    }

    @Test
    fun withRememberOnACalibrationIsSavedWithItsCameraAndProjector() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()

        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        assertEquals(SAVED_KEYS, savedKeys())
    }

    @Test
    fun turningRememberOnSavesTheCalibrationThereIsAndOffForgetsIt() {
        openArenaOnTheProjector()
        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        app.setRememberCalibration(true)
        assertEquals(SAVED_KEYS, savedKeys())

        app.setRememberCalibration(false)
        assertEquals(emptyMap<String, String>(), savedKeys())
    }

    @Test
    fun openingTheArenaChecksTheSavedCalibrationAndKeepsItWhenThePatternIsInPlace() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
```

with:

```kotlin
        assertFalse(file.readText().contains("shootoff.arena.calibration."))
    }

    @Test
    fun withTheOptionOnACalibrationTheCameraFoundSavesOnlyThatTheOptionIsOn() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()

        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        assertEquals(mapOf("shootoff.arena.calibration.remember" to "true"), savedKeys())
    }

    @Test
    fun turningTheOptionOnSavesTheManualBoxThereIsAndOffForgetsIt() {
        openArenaOnTheProjector()
        calibrateWithTheBox(Rect(100.0, 80.0, 400.0, 300.0))

        app.setRememberCalibration(true)
        assertEquals(SAVED_KEYS, savedKeys())

        app.setRememberCalibration(false)
        assertEquals(emptyMap<String, String>(), savedKeys())
    }

    @Test
    fun withTheOptionOnOpeningTheArenaCalibratesItOnceItIsOnTheProjector() {
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()

        // Not yet on the projector: waiting, not calibrating, and nothing shows
        assertEquals(CheckState.WaitingToCalibrate, app.check.value)
        assertFalse(calibrating())
        assertNull(app.arena.value!!.background.value)

        AppFixture.putOnTheProjector(app)
        awaitAutomaticCalibrationLooking()
        // A full auto-calibration, as Calibrate does: the pattern, alone, and the owner left where they are
        assertEquals(CheckState.Idle, app.check.value)
        assertEquals("pattern.png", app.arena.value!!.background.value?.name)
        assertTrue(app.arena.value!!.covered.value)
        assertEquals(Destination.RANGE, app.destination.value)

        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertNotNull(app.calibratedAt.value)
        assertEquals(Destination.RANGE, app.destination.value)
        assertEquals(mapOf("shootoff.arena.calibration.remember" to "true"), savedKeys())
    }

    // Plan 7 saved the camera's calibrations as bounds, without the manual mark: they're not reapplied (that
    // lost the perspective, spec §8 Revision 3) but calibrated afresh, and forgotten once that succeeds
    @Test
    fun aCalibrationSavedWithoutTheManualMarkIsCalibratedAfreshAndForgotten() {
        remembered()
        file.writeText(file.readText().replace("shootoff.arena.calibration.manual=true", ""))
        app.close()
        app = appOn(Settings(file.path, arrayOf()))

        openArenaOnTheProjector()
        awaitAutomaticCalibrationLooking()
        assertEquals(0, looks.get())

        app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(102.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals(mapOf("shootoff.arena.calibration.remember" to "true"), savedKeys())
    }

    @Test
    fun anAutomaticCalibrationThatDoesntFindThePatternEndsQuietlyWithoutTheBox() {
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        app.close()
        app = AppFixture.appWithCamera(Settings(file.path, arrayOf()), calibrationTimers = { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
        })
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
        awaitTrue { timers.any { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED } }

        // The projector is covered: the pattern is never found
        timers.filter { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED }.forEach { it.second.run() }

        assertFalse(calibrating())
        assertEquals(CheckState.NotFound, app.check.value)
        assertNull(app.calibration.value!!.state.value.box)
        assertEquals(Destination.RANGE, app.destination.value)
        assertNull(app.arena.value!!.projection.value)
        assertNull(app.arena.value!!.background.value)
        assertTrue(app.arena.value!!.needsCalibrationLabel.value)

        // Calibrate on Setup works as ever afterwards
        assertTrue(app.startCalibration())
        assertEquals(CheckState.Idle, app.check.value)
        assertTrue(calibrating())
    }

    @Test
    fun withTheOptionOnTheCameraComingBackCalibratesTheArenaAgain() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()
        awaitAutomaticCalibrationLooking()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        assertNotNull(app.arena.value!!.projection.value)

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
        assertNull(app.arena.value!!.projection.value)

        // Plugged back in: the arena is still on the projector, so calibration starts once it has settled
        assertTrue(app.openCamera(AppFixture.TestCamera()))
        awaitAutomaticCalibrationLooking()
        app.calibration.value!!.calibrate(Rect(104.0, 82.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(104.0, 82.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    @Test
    fun openingTheArenaChecksTheSavedCalibrationAndKeepsItWhenThePatternIsInPlace() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
```

Replace:

```kotlin
        assertNotNull(app.calibratedAt.value)
    }

    @Test
    fun aManualBoxCalibrationIsRememberedAsTheBoxWithoutMeasuring() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()

        app.startCalibration()
```

with:

```kotlin
        assertNotNull(app.calibratedAt.value)
    }

    @Test
    fun aManualBoxCalibrationIsRememberedAsTheBoxAndMarkedAsOne() {
        app.setRememberCalibration(true)
        openArenaOnTheProjector()

        app.startCalibration()
```

Replace:

```kotlin
        app.calibration.value!!.flow.calibrated(Rect(90.0, 70.0, 420.0, 310.0), Optional.empty(), true)

        assertEquals(CheckState.Idle, app.check.value)
        assertEquals("90.0,70.0,420.0,310.0", savedKeys()["shootoff.arena.calibration.bounds"])
    }

    @Test
    fun aSmallDriftIsKeptAtTheFreshMeasurementWhichIsRememberedFromThenOn() {
```

with:

```kotlin
        app.calibration.value!!.flow.calibrated(Rect(90.0, 70.0, 420.0, 310.0), Optional.empty(), true)

        assertEquals(CheckState.Idle, app.check.value)
        assertEquals("90.0,70.0,420.0,310.0", savedKeys()["shootoff.arena.calibration.bounds"])
        assertEquals("true", savedKeys()["shootoff.arena.calibration.manual"])
    }

    @Test
    fun aSmallDriftIsKeptAtTheFreshMeasurementWhichIsRememberedFromThenOn() {
```

Replace:

```kotlin
            "shootoff.arena.calibration.camera" to "Test camera",
            "shootoff.arena.calibration.feed" to "640.0x480.0",
            "shootoff.arena.calibration.screen" to "1280.0x720.0",
            "shootoff.arena.calibration.bounds" to "100.0,80.0,400.0,300.0",
        )
    }

    private fun awaitTrue(condition: () -> Boolean) {
```

with:

```kotlin
            "shootoff.arena.calibration.camera" to "Test camera",
            "shootoff.arena.calibration.feed" to "640.0x480.0",
            "shootoff.arena.calibration.screen" to "1280.0x720.0",
            "shootoff.arena.calibration.bounds" to "100.0,80.0,400.0,300.0",
            "shootoff.arena.calibration.manual" to "true",
        )
    }

    private fun awaitTrue(condition: () -> Boolean) {
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`:

Replace:

```kotlin
            app.close()
        }
    }

    @Test
    fun rule1WithNoProjectorScreenOnlyTheCameraOpensAtLaunch() {
        val catalog = ExerciseCatalog()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), catalog, AppFixture.oneCamera(), { listOf(Rect(0.0, 0.0, 1920.0, 1080.0)) }, ManualClock(), { it.run() })
```

with:

```kotlin
            app.close()
        }
    }

    // Spec §8 Revision 3: with the option on, the launch arena calibrates itself, but only once it is on the
    // projector, and without taking the owner off Range
    @Test
    fun rule1WithTheOptionOnTheLaunchArenaCalibratesOnlyOnceItIsOnTheProjector() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        val app = AppFixture.appWithCamera(settings)
        try {
            app.launch()
            awaitTrue { app.camera.value != null }
            app.mainWindowPlaced(Point(50.0, 50.0))

            assertEquals(CheckState.WaitingToCalibrate, app.check.value)
            assertFalse(app.calibration.value!!.state.value.calibrating)
            assertNull(app.arena.value!!.background.value)

            AppFixture.putOnTheProjector(app)
            awaitTrue { app.calibration.value!!.state.value.calibrating }
            assertEquals(Destination.RANGE, app.destination.value)
        } finally {
            app.close()
        }
    }

    @Test
    fun rule1WithNoProjectorScreenOnlyTheCameraOpensAtLaunch() {
        val catalog = ExerciseCatalog()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), catalog, AppFixture.oneCamera(), { listOf(Rect(0.0, 0.0, 1920.0, 1080.0)) }, ManualClock(), { it.run() })
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`:

Replace:

```kotlin
        compose.onNodeWithTag("calibration-complete").assertTextEquals("Calibration complete ✓ 14:05")
        step(Step.CALIBRATE, StepState.DONE)
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("remember-calibration").performClick()
        compose.onNodeWithTag("show-grid").performClick()

        assertTrue(app.rememberCalibration.value)
```

with:

```kotlin
        compose.onNodeWithTag("calibration-complete").assertTextEquals("Calibration complete ✓ 14:05")
        step(Step.CALIBRATE, StepState.DONE)
    }

    @Test
    fun anAutomaticCalibrationWaitingForTheProjectorSaysSoWithCancel() {
        app.close()
        app = AppFixture.appWithCamera()
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("calibration-summary").assertTextEquals("Calibrating once the arena is on the projector…")
        compose.onNodeWithTag("setup-cancel").performClick()

        compose.onNodeWithTag("setup-calibrate").assertExists()
        compose.onNodeWithTag("calibration-summary").assertTextEquals("Not calibrated")
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithText("Calibrate automatically when the arena opens").assertExists()
        compose.onNodeWithTag("remember-calibration").performClick()
        compose.onNodeWithTag("show-grid").performClick()

        assertTrue(app.rememberCalibration.value)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt`:

Replace:

```kotlin
        assertEquals("No camera", calibrationSummary(false, true, false, false, null, idle))
        assertEquals("No arena", calibrationSummary(true, false, false, false, null, idle))
        assertEquals("Calibrating…", calibrationSummary(true, true, true, true, null, idle))
        assertEquals("Checking the saved calibration…", calibrationSummary(true, true, false, false, null, CheckState.Checking))
        assertEquals("The projection moved about 14 px — recalibrate", calibrationSummary(true, true, false, false, null, CheckState.Moved(14)))
        assertEquals(
            "The pattern wasn't seen: not verified — recalibrate on Setup",
            calibrationSummary(true, true, false, false, null, CheckState.NotVerified(Reason.PATTERN_NOT_SEEN)),
```

with:

```kotlin
        assertEquals("No camera", calibrationSummary(false, true, false, false, null, idle))
        assertEquals("No arena", calibrationSummary(true, false, false, false, null, idle))
        assertEquals("Calibrating…", calibrationSummary(true, true, true, true, null, idle))
        assertEquals("Checking the saved calibration…", calibrationSummary(true, true, false, false, null, CheckState.Checking))
        assertEquals(
            "Calibrating once the arena is on the projector…",
            calibrationSummary(true, true, false, false, null, CheckState.WaitingToCalibrate),
        )
        assertEquals(
            "The pattern wasn't found: not calibrated — calibrate on Setup",
            calibrationSummary(true, true, false, false, null, CheckState.NotFound),
        )
        assertEquals("The projection moved about 14 px — recalibrate", calibrationSummary(true, true, false, false, null, CheckState.Moved(14)))
        assertEquals(
            "The pattern wasn't seen: not verified — recalibrate on Setup",
            calibrationSummary(true, true, false, false, null, CheckState.NotVerified(Reason.PATTERN_NOT_SEEN)),
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestRememberedCalibration' --console=plain`
Expected: `:compose-app:compileTestKotlin` FAILS: unresolved references `startUnattended`, `WaitingToCalibrate`, `NotFound`, `TIMER_POOL` and `calibrationTimers`, and `CalibrationFixture`'s `calibrationSucceeded` "overrides nothing".

- [ ] **Step 3: The controller's unattended start**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin

    fun restoreSelectedView()

    /**
     * A calibration the user asked for ended with a projection (found by the camera, or the box's).
     *
     * @param cameraBounds the projection on the camera's feed
     * @param paper the perspective paper's size, if auto-calibration found one
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)
}

/**
 * Calibrates the projector arena in the Compose app: core's [CalibrationFlow] decides what happens; this
```

with:

```kotlin

    fun restoreSelectedView()

    /**
     * A calibration (asked for, or started by itself as the arena opened) ended with a projection.
     *
     * @param cameraBounds the projection on the camera's feed
     * @param paper the perspective paper's size, if auto-calibration found one
     * @param byCamera true if the camera found the pattern; false for the manual box
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean)
}

/**
 * Calibrates the projector arena in the Compose app: core's [CalibrationFlow] decides what happens; this
```

Replace:

```kotlin
    // The paper size auto-calibration found this session, if any
    @Volatile
    private var foundPaper: Optional<Size> = Optional.empty()

    // ---- The user

    /**
     * Starts calibrating; only ever because the user asked (Calibrate on Setup, or F6). The flow is told
     * whether the arena is full screen as it starts, so a start on an arena that is already full screen
     * looks for the pattern at once and its timeout reaches the manual box.
     */
    fun start() {
        if (flow.isCalibrating) return
        boundsBefore = camera.projectionBounds.orElse(null)
        foundPaper = Optional.empty()
        session = true
        flow.setFullScreen(arena.fullScreen.value)
    }

    /**
     * Cancel: calibration ends and the arena and the camera are left as they were before it started (the
     * background, targets, shots and the projection). A drill it stopped stays stopped.
     */
    fun cancel() {
        if (!flow.isCalibrating) return
        generation++
        session = false
        flow.cancel()
        putArenaBack()
        camera.setProjectionBounds(boundsBefore)
        uiState.update { CalibrationUi() }
    }
```

with:

```kotlin
    // The paper size auto-calibration found this session, if any
    @Volatile
    private var foundPaper: Optional<Size> = Optional.empty()

    // Whether the camera found this session's projection, rather than the manual box
    @Volatile
    private var foundByCamera = false

    // ---- The user

    /**
     * Starts calibrating because the user asked (Calibrate on Setup, or F6). The flow is told whether the
     * arena is full screen as it starts, so a start on an arena that is already full screen looks for the
     * pattern at once and its timeout reaches the manual box.
     */
    fun start() {
        if (flow.isCalibrating) return
        beginSession()
        flow.setFullScreen(arena.fullScreen.value)
    }

    /**
     * Starts calibrating by itself, with no one there to drag the manual box (spec §8 Revision 3): as [start],
     * except that if the pattern isn't found in time, calibration ends as [cancel] ends it and [onNotFound]
     * hears it, on the UI thread, instead of the box showing.
     */
    fun startUnattended(onNotFound: () -> Unit) {
        if (flow.isCalibrating) return
        beginSession()
        flow.startUnattended {
            putBack()
            onNotFound()
        }
    }

    private fun beginSession() {
        boundsBefore = camera.projectionBounds.orElse(null)
        foundPaper = Optional.empty()
        foundByCamera = false
        session = true
    }

    /**
     * Cancel: calibration ends and the arena and the camera are left as they were before it started (the
     * background, targets, shots and the projection). A drill it stopped stays stopped.
     */
    fun cancel() {
        if (!flow.isCalibrating) return
        flow.cancel()
        putBack()
    }

    // Calibration ended without calibrating (Cancel, or an unattended one that didn't find the pattern): the
    // arena and the camera as they were before it started
    private fun putBack() {
        generation++
        session = false
        putArenaBack()
        camera.setProjectionBounds(boundsBefore)
        uiState.update { CalibrationUi() }
    }
```

Replace:

```kotlin
        uiThread(
            Runnable {
                if (generation == expectedGeneration && flow.isCalibrating) {
                    foundPaper = perspectivePaperDims
                    flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas)
                }
            },
        )
```

with:

```kotlin
        uiThread(
            Runnable {
                if (generation == expectedGeneration && flow.isCalibrating) {
                    foundPaper = perspectivePaperDims
                    foundByCamera = true
                    flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas)
                }
            },
        )
```

Replace:

```kotlin
        // Targets take their real-world sizes once the perspective is known
        if (perspectiveManager.isPresent) {
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
        }
        // A calibration the user started, not a remembered one being applied, and one that found the projection
        if (session) {
            session = false
            camera.projectionBounds.ifPresent { views.calibrationSucceeded(it, foundPaper) }
        }
    }

    override fun runOnUiThread(action: Runnable) = uiThread(action)
```

with:

```kotlin
        // Targets take their real-world sizes once the perspective is known
        if (perspectiveManager.isPresent) {
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
        }
        // A calibration that was started, not a remembered one being applied, and one that found the projection
        if (session) {
            session = false
            camera.projectionBounds.ifPresent { views.calibrationSucceeded(it, foundPaper, foundByCamera) }
        }
    }

    override fun runOnUiThread(action: Runnable) = uiThread(action)
```

- [ ] **Step 4: The two new states**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`:

Replace:

```kotlin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Where the check of a remembered calibration stands */
sealed interface CheckState {
    /** No check, or the last one kept the calibration */
    data object Idle : CheckState

    /** Waiting for the arena to reach the projector, or looking for the pattern */
    data object Checking : CheckState

    data class Moved(val pixels: Int) : CheckState
```

with:

```kotlin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Where the arena's own calibration stands as it opens (spec §8 Revision 3): an automatic calibration waiting
 * for the projector, or one that didn't find the pattern; or the check of a remembered manual box
 */
sealed interface CheckState {
    /** Nothing under way, or the last check kept the calibration */
    data object Idle : CheckState

    /** An automatic calibration, waiting for the arena to reach the projector */
    data object WaitingToCalibrate : CheckState

    /** An automatic calibration didn't find the pattern in time, and ended without calibrating */
    data object NotFound : CheckState

    /** Waiting for the arena to reach the projector, or looking for the pattern */
    data object Checking : CheckState

    data class Moved(val pixels: Int) : CheckState
```

Replace:

```kotlin
    /** The saved calibration was made with another camera or projector, so it wasn't checked */
    data class DoesntFit(val why: String) : CheckState
}

/** Whether the pattern is on the arena for a check, or waiting to go on */
val CheckState.showsPattern: Boolean get() = this == CheckState.Checking

/** What Setup and Range say about a check, or null when there is nothing to say */
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.Checking -> "Checking the saved calibration…"
    is CheckState.Moved -> "The projection moved about $pixels px — recalibrate"
    is CheckState.NotVerified -> when (reason) {
        Reason.PATTERN_NOT_SEEN -> "The pattern wasn't seen: not verified — recalibrate on Setup"
```

with:

```kotlin
    /** The saved calibration was made with another camera or projector, so it wasn't checked */
    data class DoesntFit(val why: String) : CheckState
}

/** Whether the pattern is on the arena for a check, or waiting to go on for a check or a calibration */
val CheckState.showsPattern: Boolean get() = this == CheckState.Checking || this == CheckState.WaitingToCalibrate

/** What Setup and Range say about a check, or null when there is nothing to say */
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.WaitingToCalibrate -> "Calibrating once the arena is on the projector…"
    CheckState.NotFound -> "The pattern wasn't found: not calibrated — calibrate on Setup"
    CheckState.Checking -> "Checking the saved calibration…"
    is CheckState.Moved -> "The projection moved about $pixels px — recalibrate"
    is CheckState.NotVerified -> when (reason) {
        Reason.PATTERN_NOT_SEEN -> "The pattern wasn't seen: not verified — recalibrate on Setup"
```

- [ ] **Step 5: The app calibrates, or checks a box, as the arena opens**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check shows
 *   the pattern
 */
class AppState(
    val settings: Settings,
    val catalog: ExerciseCatalog = ExerciseCatalog(),
```

with:

```kotlin
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check shows
 *   the pattern, or an automatic calibration starts
 * @param calibrationTimers runs calibration's and the check's timers (auto-calibration's timeout among them)
 */
class AppState(
    val settings: Settings,
    val catalog: ExerciseCatalog = ExerciseCatalog(),
```

Replace:

```kotlin
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
    private val reconnectMillis: Long = 2000,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check */
        const val PATTERN_SETTLE_MILLIS = 500L
    }

    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + background)
```

with:

```kotlin
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
    private val reconnectMillis: Long = 2000,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
    private val calibrationTimers: CalibrationFlow.Scheduler = TIMER_POOL,
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check, or calibration starts */
        const val PATTERN_SETTLE_MILLIS = 500L

        /** Calibration's timers on the app's shared timer pool */
        val TIMER_POOL = CalibrationFlow.Scheduler { task, delay -> TimerPool.schedule(task, delay) ?: CompletableFuture<Void>() }
    }

    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + background)
```

Replace:

```kotlin
        // The arena was already open with no camera to calibrate with (opened before the camera, or kept open
        // when the last camera went); now one is here, so the arena can be calibrated
        arenaState.value?.let { arena ->
            if (calibrationState.value == null) makeCalibratable(arena, manager)
            // An uncalibrated arena is checked against the remembered calibration now that there is a camera
            // to check with (spec §8 Revision 2, decision 7); without Remember, Setup's Calibrate step is next
            if (settings.rememberCalibration() && settings.savedCalibration.isPresent &&
                arena.projection.value == null && !checkState.value.showsPattern
            ) {
                checkRemembered(arena)
            }
        }
        return true
    }
```

with:

```kotlin
        // The arena was already open with no camera to calibrate with (opened before the camera, or kept open
        // when the last camera went); now one is here, so the arena can be calibrated
        arenaState.value?.let { arena ->
            if (calibrationState.value == null) makeCalibratable(arena, manager)
            // An uncalibrated arena is calibrated (or its remembered box checked) now that there is a camera
            // (spec §8 Revision 3); without the option, Setup's Calibrate step is next
            if (settings.rememberCalibration() && arena.projection.value == null && !checkState.value.showsPattern) {
                calibrateOrCheck(arena)
            }
        }
        return true
    }
```

Replace:

```kotlin
    fun projectorScreenFound(): Boolean = arenaPlacementNow().screen != null

    /**
     * Opens the arena window, on the projector if one is found, ready to be calibrated with the open camera.
     * Unlike the JavaFX app, it never starts calibrating: only [startCalibration] does. Without an open
     * camera there is nothing to calibrate with yet; [makeCalibratable] runs later instead, once a camera
     * opens (see [publish]).
     */
    fun openArena() {
        if (arenaState.value != null) return

```

with:

```kotlin
    fun projectorScreenFound(): Boolean = arenaPlacementNow().screen != null

    /**
     * Opens the arena window, on the projector if one is found, ready to be calibrated with the open camera.
     * With "Calibrate automatically when the arena opens" on, it calibrates once it is on the projector (or a
     * remembered box is checked); otherwise only [startCalibration] calibrates. Without an open camera there
     * is nothing to calibrate with yet; [makeCalibratable] runs later instead, once a camera opens (see
     * [publish]).
     */
    fun openArena() {
        if (arenaState.value != null) return

```

Replace:

```kotlin
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena

        cameraState.value?.let { makeCalibratable(arena, it) }
        if (settings.rememberCalibration()) checkRemembered(arena)
    }

    // The remembered calibration, checked as the arena opens, or once a camera becomes available for an arena
    // already open (see publish), if it was made with this camera and a projector screen like this one. The
    // pattern shows only while the arena is on the projector (watchArenaForPattern).
    private fun checkRemembered(arena: ArenaModel) {
        val saved = settings.savedCalibration.orElse(null) ?: return
```

with:

```kotlin
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena

        cameraState.value?.let { makeCalibratable(arena, it) }
        if (settings.rememberCalibration()) calibrateOrCheck(arena)
    }

    // With the option on, what an uncalibrated arena does (spec §8 Revision 3): a remembered manual box is
    // checked, since only the owner can place one; otherwise the arena is calibrated afresh, as Calibrate does
    private fun calibrateOrCheck(arena: ArenaModel) {
        if (settings.savedCalibration.map { it.manual }.orElse(false)) checkRemembered(arena) else calibrateOnTheProjector(arena)
    }

    // Starts an unattended calibration once the arena is on the projector (watchArenaForPattern). Without a
    // camera, publish() comes back here once one opens; without a projector screen the arena is a window,
    // which auto-calibration can't use, so nothing starts. If the pattern isn't found, it ends quietly: no
    // manual box, and Setup and the chip say so.
    private fun calibrateOnTheProjector(arena: ArenaModel) {
        if (calibrationState.value == null || placementState.value?.screen == null) return
        checkState.value = CheckState.WaitingToCalibrate
        watchArenaForPattern(arena) {
            patternWatch?.cancel()
            patternWatch = null
            checkState.value = CheckState.Idle
            arena.showGrid(false)
            calibrationCompleteState.value = null
            calibrationState.value?.startUnattended { checkState.value = CheckState.NotFound }
        }
    }

    // The remembered manual box, checked as the arena opens, or once a camera becomes available for an arena
    // already open (see publish), if it was made with this camera and a projector screen like this one. The
    // pattern shows only while the arena is on the projector (watchArenaForPattern).
    private fun checkRemembered(arena: ArenaModel) {
        val saved = settings.savedCalibration.orElse(null) ?: return
```

Replace:

```kotlin

    // Starts [start] once the arena is on the projector (spec §8 Revision 2, decision 3): full screen, as big
    // as the projector's screen, and settled there for [patternSettleMillis], so the pattern is never measured
    // in a window on its way (or, after F11, off) the projector. Leaving the projector stops the run quietly
    // (the state stays, so its return starts a fresh run). Runs until the check ends.
    private fun watchArenaForPattern(arena: ArenaModel, start: () -> Unit) {
        patternWatch?.cancel()
        val screen = placementState.value?.screen
        patternWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
```

with:

```kotlin

    // Starts [start] once the arena is on the projector (spec §8 Revision 2, decision 3): full screen, as big
    // as the projector's screen, and settled there for [patternSettleMillis], so the pattern is never measured
    // in a window on its way (or, after F11, off) the projector. Leaving the projector stops the run quietly
    // (the state stays, so its return starts a fresh run). Runs until the check ends, or the automatic
    // calibration starts.
    private fun watchArenaForPattern(arena: ArenaModel, start: () -> Unit) {
        patternWatch?.cancel()
        val screen = placementState.value?.screen
        patternWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
```

Replace:

```kotlin

    // Shows the pattern and hands the camera's frames to [work]; [done] hears its result, unless the run was
    // stopped or replaced first
    private fun <T : Any> startPatternRun(arena: ArenaModel, camera: CameraManager, work: () -> PatternWork<T>, done: (T) -> Unit) {
        val run = PatternRun(arena, CalibratingCamera(camera), checkFrames, work, scope, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread) { run, result ->
            if (patternRun === run && result != null) {
                patternRun = null
                cameraView.frameTap = null
                // A result ends the check: the watch has nothing left to restart
```

with:

```kotlin

    // Shows the pattern and hands the camera's frames to [work]; [done] hears its result, unless the run was
    // stopped or replaced first
    private fun <T : Any> startPatternRun(arena: ArenaModel, camera: CameraManager, work: () -> PatternWork<T>, done: (T) -> Unit) {
        val run = PatternRun(arena, CalibratingCamera(camera), checkFrames, work, scope, calibrationTimers, uiThread) { run, result ->
            if (patternRun === run && result != null) {
                patternRun = null
                cameraView.frameTap = null
                // A result ends the check: the watch has nothing left to restart
```

Replace:

```kotlin
        run?.stop()
        checkState.value = CheckState.Idle
    }

    /** Cancel on Setup while the check's pattern shows: the arena stays uncalibrated, "not verified". */
    fun cancelCheck() {
        if (checkState.value != CheckState.Checking) return
        stopCheckQuietly()
        checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED)
    }

    /**
     * "Remember calibration". On: the arena's calibration, now and after each calibration, is saved for the
     * next session. Off: nothing is saved, the saved one is forgotten, and a check under way stops.
     */
    fun setRememberCalibration(remember: Boolean) {
        // A no-op when nothing changed: turning it on again with nothing calibrated this session (so
        // currentCalibration is still null) would otherwise erase an already-saved calibration
```

with:

```kotlin
        run?.stop()
        checkState.value = CheckState.Idle
    }

    /**
     * Cancel on Setup while a check's pattern shows (the arena stays uncalibrated, "not verified"), or while
     * an automatic calibration waits for the projector (it doesn't start).
     */
    fun cancelCheck() {
        when (checkState.value) {
            CheckState.Checking -> {
                stopCheckQuietly()
                checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED)
            }
            CheckState.WaitingToCalibrate -> stopCheckQuietly()
            else -> {}
        }
    }

    /**
     * "Calibrate automatically when the arena opens" (spec §8 Revision 3). On: the arena calibrates itself as it
     * opens, and a manual box, now and after each calibration, is saved for the next session to check. Off:
     * nothing is saved, the saved box is forgotten, and a check (or a wait to calibrate) under way stops.
     */
    fun setRememberCalibration(remember: Boolean) {
        // A no-op when nothing changed: turning it on again with nothing calibrated this session (so
        // currentCalibration is still null) would otherwise erase an already-saved calibration
```

Replace:

```kotlin
    // Creates the calibration controller for [camera] on [arena], without calibrating. Also reached when a
    // camera opens (or becomes available) after the arena, which otherwise would leave Calibrate and F6
    // disabled until the arena is closed and reopened.
    private fun makeCalibratable(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(CalibratingCamera(camera), arena, settings, drillForCalibration, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread)
        camera.setCalibrationManager(controller)
        calibrationState.value = controller

        // Calibration hears the arena going full screen or leaving it, as the JavaFX arena tells it: on the
```

with:

```kotlin
    // Creates the calibration controller for [camera] on [arena], without calibrating. Also reached when a
    // camera opens (or becomes available) after the arena, which otherwise would leave Calibrate and F6
    // disabled until the arena is closed and reopened.
    private fun makeCalibratable(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(CalibratingCamera(camera), arena, settings, drillForCalibration, this, calibrationTimers, uiThread)
        camera.setCalibrationManager(controller)
        calibrationState.value = controller

        // Calibration hears the arena going full screen or leaving it, as the JavaFX arena tells it: on the
```

Replace:

```kotlin
    fun cancelCalibration() {
        calibrationState.value?.cancel()
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        val now = wallClock()
        calibratedAtState.value = now
        // The user stays where they are (Setup, usually) and is told it worked; they go back to Range when
        // they choose (spec §8 Revision 2, decision 1)
        calibrationCompleteState.value = now
        checkState.value = CheckState.Idle
        val camera = cameraState.value
        val screen = placementState.value?.screen
        // Made without a projector screen, a calibration can't be matched to one next time
        currentCalibration = if (camera != null && screen != null) {
            SavedCalibration(camera.name, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), Size(screen.width, screen.height), cameraBounds, paper, false)
        } else {
            null
        }
        if (settings.rememberCalibration()) {
```

with:

```kotlin
    fun cancelCalibration() {
        calibrationState.value?.cancel()
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean) {
        val now = wallClock()
        calibratedAtState.value = now
        // The user stays where they are (Setup, usually) and is told it worked; they go back to Range when
        // they choose (spec §8 Revision 2, decision 1)
        calibrationCompleteState.value = now
        checkState.value = CheckState.Idle
        val camera = cameraState.value
        val screen = placementState.value?.screen
        // Only a manual box is remembered as bounds: the camera's calibration is redone next time (spec §8
        // Revision 3). Made without a projector screen, a box can't be matched to one next time
        currentCalibration = if (!byCamera && camera != null && screen != null) {
            SavedCalibration(camera.name, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), Size(screen.width, screen.height), cameraBounds, paper, true)
        } else {
            null
        }
        if (settings.rememberCalibration()) {
```

- [ ] **Step 6: The checkbox, and the log**

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`:

Replace:

```kotlin
        FilledTonalButton(onClick = app::closeArena, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
    }
}

/** Calibration: where it stands, Calibrate or Cancel, Remember calibration and Show grid */
@Composable
private fun CalibrateStep(app: AppState) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
```

with:

```kotlin
        FilledTonalButton(onClick = app::closeArena, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
    }
}

/** Calibration: where it stands, Calibrate or Cancel, calibrating when the arena opens, and Show grid */
@Composable
private fun CalibrateStep(app: AppState) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
```

Replace:

```kotlin
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = app::setRememberCalibration, modifier = Modifier.testTag("remember-calibration"))
        Text("Remember calibration", color = colors.text)
    }
    val projectorDrill = running?.host?.isProjector == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
```

with:

```kotlin
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = app::setRememberCalibration, modifier = Modifier.testTag("remember-calibration"))
        Column {
            Text("Calibrate automatically when the arena opens", color = colors.text)
            Text("A box placed by hand is reused and checked instead", color = colors.muted, fontSize = 12.sp)
        }
    }
    val projectorDrill = running?.host?.isProjector == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
```

`compose-app/src/main/resources/logback.xml`:

Replace:

```xml
    </encoder>
  </appender>

  <logger name="com.shootoff.camera.cameratypes.SarxosCaptureCamera" level="INFO"/>
  <!-- The remembered calibration's measurements and outcomes, and the camera's reconnect -->
  <logger name="com.shootoff.calibration" level="INFO"/>
  <logger name="com.shootoff.compose.app.AppState" level="INFO"/>

  <root level="warn">
    <appender-ref ref="STDOUT"/>
```

with:

```xml
    </encoder>
  </appender>

  <logger name="com.shootoff.camera.cameratypes.SarxosCaptureCamera" level="INFO"/>
  <!-- The remembered box's check, its measurements and outcomes, and the camera's reconnect -->
  <logger name="com.shootoff.calibration" level="INFO"/>
  <!-- Auto-calibration's exposure step: whether it lowered the exposure or put it back -->
  <logger name="com.shootoff.camera.autocalibration" level="INFO"/>
  <logger name="com.shootoff.compose.app.AppState" level="INFO"/>

  <root level="warn">
    <appender-ref ref="STDOUT"/>
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Run the gate**

Expected: `754/754 passing; 0 regressions; 0 new failures`.

- [ ] **Step 9: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt compose-app/src/main/resources/logback.xml compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt
git commit -m "Calibrate the arena by itself when it opens, instead of checking a saved calibration"
git log -1 --format=%B
```

---

### Task 4: Cancel never leaves the arena white

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt` (`setArenaBackground`, `beginSession`, the `generation` comment)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`

**Interfaces:**
- Consumes:
  - Task 3's `beginSession()` and `putBack()` (which bumps `generation`).
  - `CalibrationFixture`, whose `uiThread` runs tasks at once.
  - `CameraCalibrationListener.setArenaBackground(String?)`, which `AutoCalibrationManager`'s steps call on the camera's thread.
- Produces: `CalibrationController.setArenaBackground` shows the background on the UI thread only while the calibration that asked still runs. Nothing downstream changes.

**The root cause** (spec decision 8):
- `AutoCalibrationManager.processFrame` runs on the camera's thread. `CameraManager` checks `isAutoCalibrating` once per frame, before handing the frame over.
- The steps after `StepFindBounds` call `setArenaBackground(null)`, and `StepAdjustExposure`'s first frame calls `setArenaBackground("white.png")`. Both went straight to `arena.showResource`, which decodes the PNG before setting it.
- A Cancel on the UI thread in the middle of such a frame ran `putArenaBack()` first, and the white landed after it. Nothing restores the background after Cancel, so the projector stayed white until the arena was closed.
- The same can happen after a success, or across calibrations: a straggler from the last one saved as the next one's "before".
- The first test below reproduces the owner's sequence; the second and third pin the cancels that were already right (during the exposure step, and on the manual box after it); the fourth is the success path.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`:

Replace:

```kotlin
        assertEquals(emptyList<String>(), restarts)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    @Test
    fun cancellingOnAnUncalibratedArenaBringsBackItsLabel() {
        arena.setFullScreen(true)
        controller.start()
```

with:

```kotlin
        assertEquals(emptyList<String>(), restarts)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    // The owner's white arena (Plan 7's hardware check): auto-calibration's steps set the arena's background
    // from the camera's thread (the exposure step shows white.png). One that lands after Cancel has put the
    // arena back must not show; the arena must be exactly as it was before calibration started.
    @Test
    fun aWhiteScreenTheCameraAsksForAsCancelLandsNeverShows() {
        val background = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(background)
        arena.setFullScreen(true)
        controller.start()

        // The camera's thread was already in the exposure step's first frame when Cancel was pressed
        controller.cancel()
        controller.setArenaBackground("white.png")

        assertSame(background, arena.background.value)
    }

    @Test
    fun cancelDuringTheExposureStepPutsTheBackgroundBackNotWhite() {
        val background = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(background)
        arena.setFullScreen(true)
        controller.start()
        // The pattern was found; the paper step blanks the arena, then the exposure step shows white
        controller.setArenaBackground(null)
        controller.setArenaBackground("white.png")
        assertEquals("white.png", arena.background.value!!.name)

        controller.cancel()

        assertSame(background, arena.background.value)
        assertFalse(arena.covered.value)
    }

    @Test
    fun cancelOnTheManualBoxAfterTheExposureStepStartedPutsTheBackgroundBack() {
        arena.setFullScreen(true)
        controller.start()
        controller.setArenaBackground("white.png")
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        assertNotNull(controller.state.value.box)

        controller.cancel()

        assertEquals(null, arena.background.value)
        assertTrue(arena.needsCalibrationLabel.value)
    }

    @Test
    fun aBackgroundTheCameraAsksForAfterASuccessNeverShows() {
        arena.setFullScreen(true)
        controller.start()
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        assertEquals(null, arena.background.value)

        controller.setArenaBackground("white.png")

        assertEquals(null, arena.background.value)
    }

    @Test
    fun cancellingOnAnUncalibratedArenaBringsBackItsLabel() {
        arena.setFullScreen(true)
        controller.start()
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.calibration.TestCalibrationOnRequest' --console=plain`
Expected: 2 FAIL: `aWhiteScreenTheCameraAsksForAsCancelLandsNeverShows` (the background is `white.png`) and `aBackgroundTheCameraAsksForAfterASuccessNeverShows`. The other two pass.

- [ ] **Step 3: Write the fix**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
    // the pattern (still asking for full screen), when there is nothing to restore
    @Volatile
    private var backgroundSaved = false

    // Bumped whenever the arena closes, so a calibrated() completion queued on the UI thread beforehand
    // (the camera found the pattern just as the window went away) finds out it is stale and does nothing
    @Volatile
    private var generation = 0

    // The camera's projection when calibration started, which Cancel puts back
```

with:

```kotlin
    // the pattern (still asking for full screen), when there is nothing to restore
    @Volatile
    private var backgroundSaved = false

    // Bumped whenever a calibration begins or ends without calibrating (Cancel, the arena closing), so
    // whatever the camera sent for an earlier one and is still queued on the UI thread (a completion, as the
    // camera found the pattern just as the window went away; a background) finds out it is stale and does
    // nothing
    @Volatile
    private var generation = 0

    // The camera's projection when calibration started, which Cancel puts back
```

Replace:

```kotlin
        }
    }

    private fun beginSession() {
        boundsBefore = camera.projectionBounds.orElse(null)
        foundPaper = Optional.empty()
        foundByCamera = false
        session = true
```

with:

```kotlin
        }
    }

    private fun beginSession() {
        // Whatever the camera still sends for an earlier calibration (a background, a success) is stale now
        generation++
        boundsBefore = camera.projectionBounds.orElse(null)
        foundPaper = Optional.empty()
        foundByCamera = false
        session = true
```

Replace:

```kotlin
            },
        )
    }

    /** Auto-calibration's exposure step shows a white screen, then none */
    override fun setArenaBackground(resourceFilename: String?) = arena.showResource(resourceFilename)

    // ---- The flow (CalibrationFlow.View)

    override fun isArenaFullScreen(): Boolean = arena.fullScreen.value
```

with:

```kotlin
            },
        )
    }

    /**
     * Auto-calibration's steps set the arena's background from the camera's thread (the exposure step shows a
     * white screen; the steps before it blank the arena). The camera can be in the middle of a frame when
     * calibration ends, so this reaches the arena on the UI thread and only while that same calibration still
     * runs: a request landing after Cancel, the arena closing or a success has put the arena back is dropped,
     * and never leaves the projector white (the owner's white arena, Plan 7's hardware check).
     */
    override fun setArenaBackground(resourceFilename: String?) {
        val expectedGeneration = generation
        uiThread(
            Runnable {
                if (generation == expectedGeneration && flow.isCalibrating) arena.showResource(resourceFilename)
            },
        )
    }

    // ---- The flow (CalibrationFlow.View)

    override fun isArenaFullScreen(): Boolean = arena.fullScreen.value
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.calibration.*' --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the gate**

Expected: `758/758 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt
git commit -m "Cancel never leaves the arena white: the camera's background requests reach it only while its calibration runs"
git log -1 --format=%B
```

---

### Task 5: Range's status chip only when there is something to say

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt` (`StatusChip`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt`

**Interfaces:**
- Consumes: `CheckState.text()` (Task 3's two new texts included), `AppFixture.setUpForProjectorDrills`.
- Produces: `StatusChip` draws nothing when the range is ready and there is nothing to say. `RangeScreen` places it as before.

- [ ] **Step 1: Write the failing test**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt`:

Replace:

```kotlin
        compose.onNodeWithTag("open-arena").assertDoesNotExist()
    }

    @Test
    fun theChipSaysWhatIsMissingOrWhenItWasCalibratedAndOpensSetup() {
        app.openStartCamera()
        showApp()
        compose.onNodeWithTag("status-chip").assert(hasText("No arena"))

        AppFixture.setUpForProjectorDrills(app)
        compose.waitForIdle()
        compose.onNodeWithTag("status-chip").assert(hasText("✓ Calibrated", substring = true))

        compose.onNodeWithTag("status-chip").performClick()
        assertEquals(Destination.SETUP, app.destination.value)
    }

    @Test
    fun clearShotsEmptiesTheTimerAndTheMarkers() {
        app.openStartCamera()
        app.timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 1.0, 1000), false, false)
```

with:

```kotlin
        compose.onNodeWithTag("open-arena").assertDoesNotExist()
    }

    @Test
    fun theChipSaysWhatIsMissingAndOpensSetup() {
        app.openStartCamera()
        showApp()
        compose.onNodeWithTag("status-chip").assert(hasText("No arena"))

        compose.onNodeWithTag("status-chip").performClick()
        assertEquals(Destination.SETUP, app.destination.value)
    }

    // The owner's ask (Plan 7's hardware check): once all is set up the status strip at the bottom already says
    // it, so the chip goes; it comes back as soon as something needs attention or is under way
    @Test
    fun theChipIsHiddenOnceTheRangeIsReadyAndBackWhileCalibrating() {
        AppFixture.setUpForProjectorDrills(app)
        showApp()
        compose.onNodeWithTag("status-chip").assertDoesNotExist()

        assertTrue(app.startCalibration())
        compose.waitForIdle()
        compose.onNodeWithTag("status-chip").assert(hasText("Calibrating…"))
    }

    @Test
    fun clearShotsEmptiesTheTimerAndTheMarkers() {
        app.openStartCamera()
        app.timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 1.0, 1000), false, false)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestRangeScreen' --console=plain`
Expected: FAIL: `theChipIsHiddenOnceTheRangeIsReadyAndBackWhileCalibrating` (the chip exists, reading "✓ Calibrated HH:mm").

- [ ] **Step 3: Hide the chip once ready**

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt`:

Replace:

```kotlin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.drill.DrillCard
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

const val SET_UP_FIRST = "Set up the projector first"

/** What the calibration is, or what is missing, on a chip that opens Setup */
@Composable
fun StatusChip(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
```

with:

```kotlin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.calibration.text
import com.shootoff.compose.drill.DrillCard
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

const val SET_UP_FIRST = "Set up the projector first"

/**
 * What is missing, or under way, on a chip that opens Setup. Once the range is ready (a camera, the arena
 * calibrated, nothing under way and nothing to say) the chip goes: the status strip says it already.
 */
@Composable
fun StatusChip(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
```

Replace:

```kotlin
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val colors = Range.colors
    val ready = camera != null && calibrated && !calibrating

    Surface(
        onClick = { app.navigate(Destination.SETUP) },
        shape = RoundedCornerShape(16.dp),
```

with:

```kotlin
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val colors = Range.colors
    val ready = camera != null && calibrated && !calibrating
    if (ready && check.text() == null) return

    Surface(
        onClick = { app.navigate(Destination.SETUP) },
        shape = RoundedCornerShape(16.dp),
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestRangeScreen' --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the gate**

Expected: `759/759 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt
git commit -m "Hide Range's status chip once the range is ready"
git log -1 --format=%B
```

---

### Task 6: The owner's short hardware re-check

Runs in ShootOFF on `compose-ui`. **Files:** none. It covers Revision 3's success criteria (7 as revised, 10 as revised, 12, 13 and 14), and what is left of Plan 7's Task 10.
- If a check fails, stop and debug with superpowers:systematic-debugging before changing code.
- The fix belongs in the task that owns the code, as a new commit with a test that reproduces it.
- The hardware: the Logitech C270 (640×480) and the 1280×720 projector at x=4480.

- [ ] **Step 1: The gate and the boundary**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
./gradlew :compose-app:dependencies --configuration runtimeClasspath --console=plain | command grep -c openjfx
```

Expected:
- `759/759 passing; 0 regressions; 0 new failures`
- the `OK` lines as in Plan 7's Task 10, except `shootoff.properties`, which both apps save to
- `0`

- [ ] **Step 2: Launch the Compose app**

With the webcam and the projector attached (the projector on), run in the background:

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :compose-app:run --console=plain > build/plan8-compose-run.log 2>&1
```

Relaunch the same way for each item below, appending with `>> build/plan8-compose-run.log 2>&1`.

- [ ] **Step 3: The owner checks, on the hardware**

1. **Startup with the option on** (criterion 7).
   - On Setup the checkbox reads "Calibrate automatically when the arena opens", with the line under it. Tick it, then quit and relaunch.
   - The arena opens on the projector, and the pattern shows by itself within a second or two of full screen. The app stays on Range.
   - Calibration ends with the chip gone from Range (criterion 14) and the bottom strip saying calibrated.
   - On Setup, "Calibration complete ✓ HH:mm" shows. Show grid: the grid's edges sit on the orange outline, as after pressing Calibrate: **not cocked** (criterion 12).
   - `command grep -c "shootoff.arena.calibration" shootoff.properties` prints `1` (only `…remember=true`; the Plan 7 bounds are gone).
2. **The camera bumped, then a relaunch.** Quit, nudge the camera so the projection moves in the feed, relaunch. The arena calibrates again by itself, with no "moved" message, and Show grid lines up again.
3. **The projector covered at startup.** Cover the projector's lens and relaunch.
   - The pattern shows (on the covered projector) for about 30 s, then goes. No manual box appears, and the app doesn't switch to Setup.
   - Range's chip and Setup say "The pattern wasn't found: not calibrated — calibrate on Setup", and the arena shows "Needs Calibration".
   - Uncover it and press Calibrate on Setup: it calibrates, and after 12 s without the pattern it would offer the box, as ever.
   - Also: relaunch covered, and press Cancel on Setup while the pattern shows. It ends at once, with the arena as it was.
4. **Unplug and replug the camera** (criterion 10). With the arena calibrated and the drill paused or stopped:
   - Unplug the camera. The arena stays, showing "Needs Calibration", and the chip says "No camera".
   - Plug it back in. Within a few seconds the camera reopens by itself, then the pattern shows and the arena calibrates again, even though the camera's controls were just reset. The log's `exposure_dynamic_framerate was 1, set to 0` line comes right before.
   - Show grid lines up.
   - Then `command grep -nE "Exposure lowered|Failed to adjust exposure" build/plan8-compose-run.log` shows what the exposure step did (ruling 9; for the owner's note).
5. **A manual box remembered.**
   - With the option on, cover the projector and press Calibrate. After 12 s drag the box over the projection and press Done.
   - `command grep -c "shootoff.arena.calibration.manual=true" shootoff.properties` prints `1`.
   - Uncover and relaunch. The saved box is *checked* ("Checking the saved calibration…", about two seconds of pattern) and kept.
   - Then press Calibrate once, uncovered, to go back to a camera calibration. `…manual` is gone from `shootoff.properties`.
6. **Cancel is never white** (criterion 13). Press Calibrate and Cancel at several moments: at once; as soon as the pattern is found (the arena briefly goes blank or white for the exposure step); and on the manual box. Each time the arena comes back exactly as it was, never white.
7. **What is left of Plan 7's Task 10.** Run Plan 7's Task 10 Step 3 items **8** (the drill, end to end, including the recalibration and F6 mid-round), **10** (missing hardware) and **11** (the tray and remembering), then its Step 6 (the JavaFX regression check). Then keep the app running for Plan 7's item **12** (twenty minutes) with the memory log of Plan 7's Step 2 (`build/plan8-rss.log`), and check it as in Plan 7's Step 4. Where those items mention the remembered calibration's check or "Measuring", read them as Revision 3 describes: an automatic calibration.
   - In JavaFX Step 6 point 7, `command grep -c "shootoff.arena.calibration" shootoff.properties` prints at least `1`, not `5`.
   - Point 9 (relaunching the Compose app) now shows an automatic calibration, not a check.

- [ ] **Step 4: Check the log**

```bash
cd /home/bfears/projects/ShootOFF
command grep -nE "Exception|Error" build/plan8-compose-run.log
command grep -cE "Calibration (check|measurement)" build/plan8-compose-run.log
```

Expected:
- The first prints nothing, except lines from item 4's deliberate unplug (the camera's own "can no longer communicate" report).
- The second prints a small number, from item 5's check only (no "Calibration measurement" lines at all).

- [ ] **Step 5: Report**

Report to the owner: which items passed, any that failed and the fix commits, what the exposure step logged, the FPS and RSS from item 7, and the open decisions from "For the owner to decide", including the drill's round label, which is theirs to change in the drill's repository.

---

## Spec coverage

| Spec §8 Revision 3 | Where |
|---|---|
| 1. With the option on, a full auto-calibration as the arena opens, once it is on the projector (full screen, filling, settled); at launch, by hand, and when a camera comes back; nothing without a projector screen | Task 3 (ruling 2; `TestRememberedCalibration.withTheOptionOnOpeningTheArena…`, `…TheCameraComingBack…`, `TestNothingBlocks.rule1WithTheOptionOn…`); Task 6 items 1, 4 |
| 2. No "moved" for auto-calibrations | Task 3 (`calibrateOrCheck` checks only a manual box); Task 6 item 2 |
| 3. Not found: a quiet end, no box, no move to Setup, the message; 30 s for a camera still settling | Task 1 (ruling 1; `TestCalibrationFlow.anUnattended…`); Task 3 (ruling 3; `…EndsQuietlyWithoutTheBox`, `TestCalibrationOnRequest.anUnattended…`); Task 6 item 3 |
| 4. Everything from Revisions 1 and 2 still holds: the drill pauses, the cover, F3 and Resume locked, never blocking, Cancel, never moved to Setup, the confirmation, camera loss | Task 3 (rulings 2, 4; `showsPattern` includes `WaitingToCalibrate`; Plan 7's `TestCalibrationPausesTheDrill`, `TestNothingBlocks` and `TestProblems` keep passing); Task 6 item 7 |
| 5. A manual box remembered and checked | Task 3 (`aManualBoxCalibrationIsRememberedAsTheBoxAndMarkedAsOne`; Plan 7's check tests keep passing on marked boxes); Task 6 item 5 |
| 6. What is saved: `…remember`; nothing more after an auto-calibration; the box with `…manual=true`; a Plan 7 save calibrated afresh and forgotten; additive, carried by the JavaFX app | Task 1 (ruling 5; `TestSavedCalibrationSettings`, `TestConfigurationKeepsComposeKeys`); Task 3 (`withTheOptionOnACalibrationTheCameraFound…`, `aCalibrationSavedWithoutTheManualMark…`) |
| 7. The checkbox's wording | Task 3 (ruling 12; `TestSetupScreen.rememberAndShowGridAreOnTheCalibrateStep`) |
| 8. Cancel never leaves the arena white | Task 4 (ruling 8); Task 6 item 6 |
| 9. The chip only when there is something to say | Task 5 (ruling 10); Task 6 item 1 |
| 10. The drill's round label is the drill's own | "For the owner to decide" (ruling 11) |
| 11. Auto-calibration logged at INFO | Task 3 (`logback.xml`); Task 6 item 4 |
| Removed: the measurement, the held restart; `PatternMeasurement` and `applySaved` stay | Task 2 (ruling 6) |
| This reverses: Revisions 1 and 2's check for auto-calibrations, decisions 3, 4 and 7, Nothing blocks rules 1–3, Plan 6's ruling 5 | Tasks 2, 3 |
| Success criteria 7 and 10 (revised), 12, 13, 14 | Task 6 |
| Delivery: Plan 8 on `compose-ui`, then the owner's re-check with what is left of Plan 7's Task 10 | this plan; Task 6 |
