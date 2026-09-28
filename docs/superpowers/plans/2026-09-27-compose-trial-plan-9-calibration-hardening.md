# Compose Trial Plan 9: Calibration Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry out spec §8 Revision 4, the fixes from Plan 8's hardware re-check:
- Cancel puts the camera's whole calibration back.
- The exposure step measures only once the white screen reaches the camera.
- The frame rate stays up while the camera looks for the pattern.
- A camera that fails to reopen right after a replug is retried.
- An unattended calibration waits 60 s.

Also: the manual box follows the mouse, the not-found wording is fixed, and the calibration log is accurate. The JavaFX app works as before and gets the `core` fixes.

**Architecture:**
- **Task 1: Cancel puts the camera back** (`core` and `compose-app`).
  - `CalibrationCamera` gains `saveCalibration()` and `restoreCalibration(Saved)`.
  - `CameraManager` saves the projection, the auto-calibration manager (which holds the perspective warp and the paper size) and the exposure. It starts every calibration with a fresh `AutoCalibrationManager`, so the one it saved stays whole.
  - `CalibrationController` saves as a calibration begins and restores in `putBack()` (Cancel, and the unattended not-found).
- **Task 2: the exposure step waits for its white screen** (`core`).
  - `StepAdjustExposure` takes its baseline only once a frame is clearly brighter than the blank arena and has stopped brightening, or after 2 s. It runs on the frames' timestamps.
  - `CalibrationFlow`'s timer gives a pattern the camera has already found 5 s more (`CalibrationCamera.isPatternFound()`).
- **Task 3: the frame rate while looking** (`core`).
  - Each look for the pattern calls `Camera.limitExposureToFramePeriod()`. On V4L2, `SarxosCaptureCamera` switches to manual exposure at one frame period when auto exposure has gone over it.
  - `CameraManager.disableAutoCalibration()` releases that limit.
  - `exposure_dynamic_framerate` is set to 0 again after every exposure-mode switch.
  - The frame rate and the exposure are logged 3 s after each open, and when the frame-rate warning fires.
- **Task 4: a replug is retried, and an unattended calibration waits a minute** (`compose-app` and `core`).
  - `AppState`'s reconnect watch tries a camera that has just appeared up to `RECONNECT_TRIES` (3) times, `RECONNECT_RETRY_MILLIS` (1 s) apart. Only the last failure is reported.
  - `AUTO_CALIBRATION_TIMEOUT_UNATTENDED` becomes 60 s.
- **Task 5: the manual box follows every move of the mouse** (`compose-app`). Each drag places the box from where it was at the press, by the pointer's whole movement since.
- **Task 6: the words and the log** (`compose-app`).
  - The not-found text is reworded, and the not-ready card gets its own wording.
  - The automatic calibration's reason is accurate.
  - The stale "since the pattern first showed" time is gone.
  - Cancel is logged.
- **Task 7: the owner's short hardware re-check.**

**Tech Stack:**
- Java 21 (`core`, `plugin-api`, `javafx-app`) and Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- Compose Multiplatform 1.12.1 (desktop, Linux x64), Material 3 1.9.0, kotlinx.coroutines (from Compose).
- OpenCV 4 through bytedeco's `opencv_java`; V4L2 controls through JNA (`V4l2Controls`).
- JUnit 5 for unit tests (JUnit 4 where a file already uses it); Compose UI tests on JUnit 4 (`v2.createComposeRule`) through the vintage engine.

**Spec:** `docs/superpowers/specs/2026-09-26-compose-trial-design.md`. This plan implements §8 "Revision 4 (2026-09-27): hardening after the Plan 8 hardware check". Where the spec and this plan disagree, the spec wins.

The evidence behind each decision is in two places:
- Plan 8's ledger (`.superpowers/sdd/2026-09-27-compose-trial-plan-8-calibrate-at-startup/progress.md`, gitignored): its "Task 6" lines and the "- " findings after them.
- The run log, `build/plan8-compose-run.log`.

Plan 8 is done, with its review fixes: the gate stands at **767**.

**Prototype.**
- The whole plan was built in a scratch clone of `compose-ui` at the spec commit, one commit per task.
- Every task compiled, and the gate ran green at each task's commit, with the counts given below, ending at **801/801**.
- The code blocks below are the prototype's edits, extracted from those commits by a script. The script checked two things:
  - each "Replace … with …" pair matches exactly once in the file, at the point it is applied;
  - applying the pairs in order gives the task's commit byte for byte.
- Each task's failing-test step was checked against the commit before it.
- The GUI apps were not run. Task 7 is the owner's.

**What only the hardware can confirm.** V4L2 and the C270 can't be unit-tested. The tests pin the logic with fake cameras, and Task 7 checks the rest:
- that the C270 honours manual exposure at one frame period and gives about 30 FPS in the dark (Task 3);
- whether it turns `exposure_dynamic_framerate` back on as its exposure mode changes (Task 3 logs it if it does);
- how long the white screen really takes to reach the camera (Task 2 logs it);
- whether a second try, a second after a failed one, opens the camera (Task 4).

## Plan-author rulings

Where Revision 4 leaves room, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

### Root causes

**Item 1: Cancel left the arena crooked.** What starting calibration throws away, at the base commit `b976e570`:
- `CalibrationFlow.java:246` `camera.setCalibrating(true)`. This leads through `CameraManager.java:340` `setCameraState(CameraState.CALIBRATING)` to `CalculatedFPSCamera.java:51-52` `case CALIBRATING: resetExposure();`. The exposure the last exposure step lowered is lost, and auto exposure comes back.
- `CalibrationFlow.java:247` `camera.setProjectionBounds(null)`: the projection.
- `CalibrationFlow.java:457` `camera.enableAutoCalibration(false)` (in `startAutoCalibration`). This reaches `CameraManager.java:780` `cameraAutoCalibrated = false`, then `:782` `fireAutoCalibration()`, then `:757` `acm.reset()`, then `AutoCalibrationManager.java:127-135`. There `perspMat = null` (`:132`), with `boundingBox`, `boundsRect`, `warpInitialized` and `isCalibrated`. The steps reset as well: the found bounds (`:198`) and the paper size (`:341`).
- Cancel put back only the rectangle: `CalibrationController.kt:163` saved `boundsBefore`, and `:185` restored it.

The warp only applies while `cameraAutoCalibrated` is true (`CameraManager.java:641-645`, `acm.undistortFrame`). After a Cancel, the feed and shot detection therefore used the raw, un-warped frame, and the grid looked cocked against the outline.

The manager is reused across calibrations (`CameraManager.java:778`, `if (acm == null)`). So even a copy of its fields would be overwritten by a look for the pattern still under way on the camera's thread. Each look takes 400–500 ms.

**Item 3: the frame rate in the dark.**
- *When the control is applied.* `exposure_dynamic_framerate` is set once, right after the capture opens (`SarxosCaptureCamera.java:145`). That is the right time: the replugs log "was 1, set to 0" before "Camera capture negotiated".
- *It was already off and didn't help.* At the covered launch (21:46:26) no "was 1" line was logged, so the control was already 0. Yet the camera reported "Initial camera exposure 1002.0" (100.2 ms, `:353`) and "Current webcam FPS is 4.9" two seconds later. With the C270's auto exposure (mode 3, aperture priority), the exposure runs past the frame period whatever that control says, and the frame rate follows.
- *Nothing caps the exposure.* The calibration only ever switches exposure mode for the exposure step (`switchToManualExposure`, `:373`; `resetExposure`, `:435`). Nothing limits the exposure while the pattern is looked for.
- *Whether the toggling turns the control back on* can't be seen in the log: it was never re-read.
- *The replug at 21:40 doesn't fit the exposure alone.* Its exposure read 336 at 21:40:04, and it ran at 3.8 FPS at 21:40:06. That is why this plan logs the frame rate and the exposure at each open and with the warning, and re-asserts the control after each switch.

**Item 6: the handles moved a tenth as far as the mouse.**
- `CalibrationOverlay.kt:104` `val current by rememberUpdatedState(box)`. Each drag event computed the new box from `current`, the box as last composed: `:119-120` for the body, `:139` for a corner.
- A real mouse sends several moves between two frames. Every move but the last before the next frame was computed from the same stale box, so only one move per frame counted.
- Ten moves between two frames gives exactly the tenth the owner saw. Task 5's first test reproduces it: 10 moves with no frame between them move the corner 1 canvas pixel, not 10.
- The old test used one large move per drag, so it passed.

### Decisions

1. **Where the restore lives: `CalibrationController`, not `CalibrationFlow.cancel()`.**
   - `beginSession()` calls `camera.saveCalibration()` before the flow starts, so it runs before anything is reset.
   - `putBack()` calls `camera.restoreCalibration(saved)` after `flow.cancel()` (or the unattended timeout's own cancel) has turned auto-calibration off.
   - The flow's `cancel()` is also the arena-closing path, where the camera's calibration goes anyway, and the JavaFX app has no Cancel. So `core`'s flow is unchanged here.

   *Cost:* none known.
2. **A fresh `AutoCalibrationManager` for every calibration, instead of `reset()`.**
   - The saved calibration holds the old manager by reference, so no OpenCV `Mat` is copied.
   - A look still under way at Cancel writes only into the new, discarded manager.
   - `restoreCalibration` sets the manager before the `cameraAutoCalibrated` flag, and `processFrame` reads the flag before the manager. A frame that sees the flag therefore finds the warp. Both fields become `volatile`.
   - A cancelled manager's late success can't land: `autoCalibrateSuccess` already ignores it once auto-calibration is off.

   *Cost:* a few small allocations per calibration. `enableAutoCalibration(calculateFrameDelay)` now honours its argument every time, not only the first time. Every caller passes `false`.
3. **The exposure counts as part of the camera's calibration.**
   - `Camera.manualExposure()` (default empty) reports the exposure step's lowered exposure, and `Camera.restoreManualExposure(double)` (default no-op) puts it back.
   - Only `SarxosCaptureCamera` implements them, through its existing `switchToManualExposure`.
   - A camera that exposed automatically before calibration gets `resetExposure()`, that is, auto exposure back.

   *Cost:* two default methods on `Camera`. Other cameras behave as before.
4. **When the white screen counts as arrived** (spec decision 2).
   - A frame at least `WHITE_SCREEN_RISE` (20 grey levels) brighter than the blank arena was when the white was asked for.
   - And the frame before it was too, and this frame is at most `WHITE_SCREEN_SETTLED` (2%) brighter than that one.
   - Or `WHITE_SCREEN_TIMEOUT` (2000 ms) has passed. Then it measures anyway, as before.
   - The step's clock moves from `System.currentTimeMillis()` to the frames' own timestamps, which the camera stamps from the same clock and which tests control. The bounds step already worked this way.
   - Both outcomes are logged at INFO: "The white screen reached the camera after N ms …", or "… didn't reach the camera in N ms …".

   *Cost:* a calibration can take up to 2 s longer. If 20 levels is too much in a bright room, the step measures after 2 s, as before; the INFO line shows it.
5. **A found pattern gets `PATTERN_FOUND_GRACE` (5 s), once.** When calibration's timer fires and `camera.isPatternFound()`, the timer is relaunched once for 5 s. This applies to every mode: attended (the box), unattended (the quiet end) and headless.
   - This is Plan 8's deferred final-review minor ("a pattern found in the last ~2 s … is lost to the timer"). Ruling 4 makes the later steps longer, which makes the minor more likely.

   *Cost:* a calibration whose later steps hang reaches the box, or the quiet end, 5 s later.
6. **The exposure held to a frame** (spec decision 3).
   - When: at each look for the pattern (`StepFindBounds`, every 250 ms of frame time).
   - What: `SarxosCaptureCamera.limitExposureToFramePeriod()` reads the exposure and the negotiated FPS. If the exposure is over 1.1 × the frame period (10000 / FPS in V4L2's 100 µs units), it switches to manual exposure at the frame period: 333 at 30 FPS.
   - Where: Linux only, an open camera only, and only while auto exposure is on. It does nothing while manual exposure is already on.
   - The 1.1 slack leaves the C270's normal 336 alone, so in the owner's normal light nothing changes.
   - When it ends: `CameraManager.disableAutoCalibration()`, which every end of calibration calls (success, Cancel, the time limit, the arena closing), calls `releaseExposureLimit()`. That puts auto exposure back, unless the exposure step has lowered the exposure since (`decreaseExposure` takes it over).

   *Cost:* while held, the pattern is looked for on a darker image than auto exposure would give. The search equalizes each frame's histogram first, and in a dark scene a darker image is what gives the frame rate back. Only the hardware can confirm this (Task 7 item 3).
7. **`exposure_dynamic_framerate` is re-asserted** after every switch to manual exposure and every switch back to auto, through the existing `disableDynamicFramerate`. That method logs only when it has to change the control ("was 1, set to 0"), so the next check shows whether the C270 turns it back on.

   *Cost:* one V4L2 ioctl per switch.
8. **Logged at INFO:**
   - 3 s after each open (`CAPTURE_STATE_LOG_DELAY`): "<camera> 3 s after opening: N FPS, exposure E (auto, mode 3), exposure_dynamic_framerate D".
   - Right after the frame-rate warning: "[<camera>] exposure E (…), exposure_dynamic_framerate D".

   *Cost:* none.
9. **The reconnect's retries** (spec decision 4).
   - Each try re-lists the camera and stops if it is gone again.
   - Only the last try reports a failure: the ERROR line and the problem panel. The earlier tries log "… didn't open yet; trying again in 1000 ms" at INFO.
   - Two Plan 7 Task 6 minors are folded in:
     - A reappearance is no longer used up when the reopen is declined because another camera is opening. It is tried at the next listing.
     - The watch checks it is still active after listing (`ensureActive`), so a cancelled watch reopens nothing.

   *Cost:* a camera that really can't open shows its error about 2 s later than before.
10. **One unattended limit, 60 s** (spec decision 5): `AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 60 * 1000`. An attended calibration keeps its 12 s.

    *Cost:* with the projector covered, the pattern shows for 60 s before the quiet end.
11. **The box's drag places it from where it was at the press.** The box is read from the controller's state at the press, not the composed one, and moved or resized by the pointer's whole movement since, divided by the feed's scale. Held at the canvas's edge, it comes back under the pointer. The box stays an axis-aligned rectangle; four corners stay deferred (the owner's call).

    *Cost:* none known.
12. **The not-found wording:**
    - `CheckState.NotFound.text()`, which Setup's Calibrate step and Range's chip share, becomes "The pattern wasn't found: not calibrated". On Setup the Calibrate button is right beside it, and the chip opens Setup.
    - Range's not-ready card has its own button, Set up, so its Calibrate line reads "Calibrate — the pattern wasn't found. Press Set up to calibrate." (`notReadyCalibrateDetail`).

    *Cost:* none. The words are the owner's to change.
13. **The log:**
    - The automatic calibration's reason is "the camera came back" only when the camera that opens is the lost one being watched for (`sameCamera` against `waitingFor`); otherwise it is "the camera opened".
    - `arenaFilledAt` is cleared when the owner starts a calibration, when an automatic one isn't found, and on Cancel.
    - Cancel is logged: "Calibration cancelled, N ms after it started". It goes through a new `CalibrationViews.calibrationCancelled()` (default no-op), so the feed's own Cancel, which calls the controller directly, is logged too.

    *Cost:* none.
14. **The replug that "took 30.2 s"** (for the owner to confirm).
    - Its success line reads "1222 ms since it started, 30172 ms since the pattern first showed". `calibrationStartedAt` is set only by an automatic start, which logs "Calibrating automatically", or by `startCalibration()`. `startCalibration()` logs "The owner is attending the automatic calibration" if one is still running.
    - Neither line appears at 21:40:32. So the automatic calibration had already ended without its "wasn't found" line: most likely by a Cancel, which wasn't logged. A Calibrate pressed at 21:40:32 then found the pattern in 1.2 s.
    - So that automatic calibration most likely searched for about 28 s without finding the pattern, rather than finding it at 30.2 s. Either reading supports rulings 6 and 10, and ruling 13's Cancel line makes the next log unambiguous.
15. **Deferred minors considered** (the Plan 8 and Plan 7 ledgers).
    - Folded in: rulings 5 and 9, above.
    - Left, because this plan doesn't touch their code or has no evidence for them:
      - Plan 8: `arenaClosing()` doesn't reset `unattendedTimeout`; turning the option off doesn't stop a running unattended calibration; `attend()`'s microsecond window; the generation guard against a sub-second Cancel then Calibrate.
      - Plan 7: the WARN every 2 s for a persistent listing failure; "Waiting for <raw name>".
16. **Existing tests that change**, each in the task that changes the behaviour:
    - Task 1: `TestCalibrationFlow`'s and `CalibrationFixture`'s fake cameras implement the two new `CalibrationCamera` methods, and Task 2 adds the third.
    - Task 4: `TestProblems.aCameraThatIsListedButWontOpenIsTriedOnceUntilItIsPluggedInAgain` becomes `…IsTriedThreeTimesUntilItIsPluggedInAgain`.
    - Task 6: `TestSetupSteps` expects the new not-found text.

    *Cost:* none. The baseline's Java 8 tests are all unchanged.

**Changes to JavaFX behavior** (it shares `core`), all deliberate and all to the good:
- Its auto-calibration's exposure step waits for the white screen (ruling 4).
- A found pattern gets 5 s more before the box (ruling 5).
- On Linux the exposure is held to a frame while it looks (ruling 6).
- `exposure_dynamic_framerate` is re-asserted, and the frame rate and exposure are logged (rulings 7 and 8).
- Each calibration uses a fresh `AutoCalibrationManager` (ruling 2).

Its Cancel-less flow, its saved keys and its UI are unchanged.

**For the owner to decide** (none blocks the plan):
- **The wording** (ruling 12): "The pattern wasn't found: not calibrated" on Setup and the chip; "Calibrate — the pattern wasn't found. Press Set up to calibrate." on the card.
- **60 s** (ruling 10): enough by this check's evidence. If Task 7's replugs still end quietly, lengthen it, or look at the new frame-rate and exposure lines first.
- **The 21:40 replug** (ruling 14): did you press Cancel, then Calibrate, around 21:40:31–32?
- **The frame rate outside calibration**: holding the exposure applies only while the pattern is looked for. If the new log shows the C270 dropping below 5 FPS in a dim room while shooting, capping it then too would be a separate decision. It affects the laser's brightness in the image.

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** no `Co-Authored-By` or any other trailer in commit messages. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- **Code style:**
  - Java: tabs; match the surrounding code. A new main-source Java file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file), then a blank line.
  - Kotlin: the official style (4 spaces, trailing commas). A new main-source `.kt` file starts with the same 17 lines, then a blank line.
  - Test files have no header.
  - Keep each file's imports sorted as the file already sorts them.
  - This plan creates no main-source files. It creates three test files: `core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java` (Task 1), and `core/src/test/java/com/shootoff/camera/autocalibration/TestStepAdjustExposure.java` (Task 2) and `…/TestPatternSearchExposure.java` (Task 3).
- **Packages:** `com.shootoff.*`.
- **Dependencies:** none added anywhere. `compose-app` still depends on `core`, `plugin-api` and Plan 5's catalog entries only; no OpenJFX in `compose-app` (`TestNoJavaFxInComposeApp` keeps passing).
- **Formats:**
  - `.target`, `.course`, the session formats, `shootoff.xml` descriptors and `shootoff.properties` are unchanged.
  - Plan 6's `shootoff.arena.calibration.*` keys and Plan 8's `…manual` key are reused as they are. No key is added.
- **The exercise API is unchanged.** `plugin-api` has no edit in this plan.
- **Tests and the owner's settings:**
  - Tests use `ScratchConfig` and never write the owner's `shootoff.properties`: every `Settings` a test builds is on `ScratchConfig.emptyFile()` (or `new Settings(new String[0])` in `core`, as its tests already do). No test reads or writes the working tree's `shootoff.properties`.
  - `compose-app` tests never read a saved `Settings` back (Plan 6's ruling 16); they read the file as `Properties`.
  - `UiPrefs` in tests is `PrefsStore.Memory`.
- **The owner's files.** Never modify, move, delete or stage:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - anything under `exercises/` (the owner's `RandomTargetParDrill.jar` and `RandomTargetParDrill-v2.jar`)
  - anything under `exercise-data/`
  - any file the owner added under `targets/` or `courses/`
  - the owner's Java preferences
  - the drill's own repository (`/home/bfears/projects/RandomTargetParDrill`)

  No test runs the drill. Their checksums are in `build/plan8-owner-files.sha256` (`sha256sum -c build/plan8-owner-files.sha256`; `shootoff.properties`, which both apps save to, may differ).
- **The JavaFX app behaves as before**, apart from the shared `core` fixes listed under "Changes to JavaFX behavior". `./gradlew run` starts only the JavaFX app; `./gradlew :compose-app:run` starts the Compose app.
- **Publishing:** only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`. No task here publishes.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- Implementers never run the GUI apps; Task 7 is the owner's.
- **Test gate** (unchanged from Plans 1–8; run it in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10` if the shell doesn't already point at a Java 21):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. Use a Bash timeout of 600000 ms.
  - The `N/M passing` count starts at **767** (end of Plan 8). Each task gives the count it ends at. A lower count means tests silently stopped running.
  - `core`'s `TestMalfunctionsProcessor.testManyMalfunctions` is probabilistic (a baseline test this plan doesn't touch). If it alone fails, run the gate again.
  - The Compose UI tests need a display, as the JavaFX tests do. The gate runs them on this machine's `DISPLAY=:0`.
  - The gate leaves `shootoff.properties` unchanged.

## Review Focus

1. **Cancel pressed while the camera's thread is in the middle of a look for the pattern.** A look takes 400–500 ms, so during a search the thread is almost always mid-look, and a look can find the pattern after Cancel. Expected: the warp, projection and exposure put back are exactly the ones from before, whatever that last look finds.
   - *Tests:* `TestCameraManagerCalibration.aLookStillUnderWayAtCancelCantChangeTheWarpPutBack` and `TestCalibrationOnRequest.theCameraIsRestoredOnlyAfterItStoppedLookingForThePattern` (Task 1).
2. **The white screen never reaches the camera,** for example the projector covered or switched off just after the pattern is found, or a room so bright the white barely registers. Expected: the exposure step still ends within about 2 s, the calibration succeeds, and the exposure is put back when it can't be lowered.
   - *Tests:* `TestStepAdjustExposure.aWhiteScreenThatNeverReachesTheCameraIsMeasuredAnywayAfterTwoSeconds` and `aRoomBrightEnoughThatTheWhiteBarelyShowsStillGetsItsBaselineOnceTheWhiteSettles` (Task 2).
3. **A camera that isn't V4L2, or isn't open:** an IP camera, the PS3 Eye, the JavaFX app on another OS, a camera unplugged mid-calibration. Expected: holding the exposure, releasing it and restoring it do nothing, and nothing throws.
   - *Tests:* `TestSarxosCaptureCamera.testAnUnopenedCamerasExposureIsNeverHeld` (Task 3); `TestCameraManagerCalibration.restoringACameraThatWasNeverCalibratedLeavesItUncalibrated` (Task 1). `Camera`'s new methods default to doing nothing.
4. **A camera another program holds, listed but refusing to open.** Expected: three tries, one error for the owner, then nothing until it is plugged in again. The owner's own pick meanwhile always wins.
   - *Tests:* `TestProblems.aCameraThatIsListedButWontOpenIsTriedThreeTimesUntilItIsPluggedInAgain` and `aCameraThatAppearsWhileAnotherIsOpeningIsTriedOnceThatOpenFails` (Task 4). Plan 7's `pickingAnotherCameraWhileWaitingEndsTheWatch` keeps passing.
5. **A fast mouse, and a box dragged past the feed's edge and back.** Expected: every move counts, and the box comes back under the pointer.
   - *Tests:* `TestCalibrationOverlay.aCornerFollowsEveryMoveOfTheMouseNotOnlyTheLastBeforeTheNextFrame`, `theBoxFollowsEveryMoveOfTheMouseToo` and `theBoxStaysUnderThePointerAfterBeingHeldAtTheCanvasEdge` (Task 5).

The owner's check (Task 7) covers what no unit test reaches: the C270's exposure and frame rate, the real white-screen delay, a real replug, and the real mouse.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/…/calibration/CalibrationCamera.java`, `core/…/camera/CameraManager.java`, `core/…/camera/cameratypes/{Camera,SarxosCaptureCamera}.java`, `…/compose/calibration/CalibrationController.kt` | Cancel puts the camera's whole calibration back | 1 |
| `core/…/camera/autocalibration/AutoCalibrationManager.java`, `core/…/calibration/{CalibrationCamera,CalibrationFlow}.java`, `core/…/camera/CameraManager.java` | the exposure step waits for the white; a found pattern's grace | 2 |
| `core/…/camera/cameratypes/{Camera,SarxosCaptureCamera}.java`, `core/…/camera/autocalibration/AutoCalibrationManager.java`, `core/…/camera/CameraManager.java` | the exposure held to a frame while looking; the logging | 3 |
| `…/compose/app/AppState.kt`, `core/…/calibration/CalibrationFlow.java` | the reconnect's retries; 60 s | 4 |
| `…/compose/calibration/CalibrationOverlay.kt` | the box follows the mouse | 5 |
| `…/compose/app/{AppState,RangeControls,SetupSteps}.kt`, `…/compose/calibration/{CalibrationController,RememberedCalibration}.kt` | the words and the log | 6 |
| none | the owner's re-check | 7 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`, with tests under `core/src/test/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | Test methods added (−removed) | Gate |
|---|---|---|
| 1 | `core` `camera/TestCameraManagerCalibration` (+5, new); `calibration/TestCalibrationOnRequest` (+3) | 775 |
| 2 | `core` `camera/autocalibration/TestStepAdjustExposure` (+3, new), `calibration/TestCalibrationFlow` (+3), `camera/TestCameraManagerCalibration` (+1) | 782 |
| 3 | `core` `camera/autocalibration/TestPatternSearchExposure` (+1, new), `camera/cameratypes/TestSarxosCaptureCamera` (+5), `camera/TestCameraManagerCalibration` (+1), `camera/TestCameraManagerDiagnostics` (+1) | 790 |
| 4 | `core` `calibration/TestCalibrationFlow` (+1); `app/TestProblems` (+2; one renamed) | 793 |
| 5 | `calibration/TestCalibrationOverlay` (+3) | 796 |
| 6 | `app/TestSetupSteps` (+1), `app/TestCalibrationLogging` (+4) | 801 |

---

### Task 1: Cancel puts the camera's whole calibration back

**Files:**
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationCamera.java` (new `Saved`, `saveCalibration`, `restoreCalibration`)
- Modify: `core/src/main/java/com/shootoff/camera/CameraManager.java` (`acm` and `cameraAutoCalibrated` volatile; `processFrame`, `enableAutoCalibration`, `undistortCoords`; new `SavedCalibration` record, `saveCalibration`, `restoreCalibration`)
- Modify: `core/src/main/java/com/shootoff/camera/cameratypes/Camera.java` (default `manualExposure`, `restoreManualExposure`)
- Modify: `core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java` (`manualExposure`, `restoreManualExposure`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt` (`cameraBefore` replaces `boundsBefore`; `beginSession`, `putBack`, `cancel`'s KDoc)
- Create: `core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`
- Test: `core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java` (its fake camera), `compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt` (its fake camera's `warp` and `Saved`), `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`

**Interfaces:**
- Consumes:
  - Plan 5's `CalibrationFlow` (`start`, `cancel`, `startAutoCalibration` calling `camera.enableAutoCalibration(false)`).
  - Plan 8's `CalibrationController.beginSession()` and `putBack()`, which Cancel and the unattended not-found both end in.
- Produces:
  - `interface CalibrationCamera.Saved {}`, `CalibrationCamera.Saved CalibrationCamera.saveCalibration()` and `void CalibrationCamera.restoreCalibration(CalibrationCamera.Saved saved)`. Both are abstract: `CameraManager` implements them, and so do the two test fakes.
  - `default OptionalDouble Camera.manualExposure()` and `default void Camera.restoreManualExposure(double exposure)`.
  - `CameraManager.enableAutoCalibration` makes a fresh `AutoCalibrationManager` every time. `CameraManager.acm` stays `protected`, now `volatile`.
  - `CalibrationFixture.FakeCamera.warp: String?`, which `enableAutoCalibration` clears, and `data class CalibrationFixture.Saved(val bounds: Rect?, val warp: String?)`.

  Task 2 adds `isPatternFound()` to the same interface and fakes, and Task 3 adds `releaseExposureLimit` beside `restoreManualExposure`.

**The root cause** is set out under "Root causes, item 1" above. The first compose test reproduces the owner's crooked grid: the fake camera's `warp` stands in for the perspective warp that starting calibration throws away.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`:

Replace:

```java
		@Override
		public void setLimitDetectProjection(boolean limitDetection) {
			events.add("camera limits detection " + limitDetection);
		}
	}

```

with:

```java
		@Override
		public void setLimitDetectProjection(boolean limitDetection) {
			events.add("camera limits detection " + limitDetection);
		}

		@Override
		public CalibrationCamera.Saved saveCalibration() {
			return new CalibrationCamera.Saved() {};
		}

		@Override
		public void restoreCalibration(CalibrationCamera.Saved saved) {
			events.add("camera restored");
		}
	}

```

`core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`:

Create it:

```java
package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

import com.shootoff.calibration.CalibrationCamera;
import com.shootoff.camera.autocalibration.AutoCalibrationManager;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * What calibrating changes on a camera, and Cancel puts back (spec §8 Revision 4, decision 1): the projection,
 * the perspective warp auto-calibration found, and the exposure.
 */
class TestCameraManagerCalibration {
	// A camera that remembers what was done to its exposure
	private static final class ExposureCamera extends MockCamera {
		OptionalDouble manual = OptionalDouble.empty();
		final List<String> exposure = new CopyOnWriteArrayList<>();

		@Override
		public OptionalDouble manualExposure() {
			return manual;
		}

		@Override
		public void restoreManualExposure(double value) {
			exposure.add("manual " + value);
			manual = OptionalDouble.of(value);
		}

		@Override
		public void resetExposure() {
			exposure.add("auto");
			manual = OptionalDouble.empty();
		}
	}

	private final ExposureCamera camera = new ExposureCamera();
	private final List<Rect> found = new CopyOnWriteArrayList<>();
	private CameraManager manager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		new Settings(new String[0]);
		camera.setViewSize(new Dimension(640, 480));
		manager = new CameraManager(camera, null, new RecordingCameraView());
		manager.setCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {
				found.add(arenaBounds);
			}

			@Override
			public void setArenaBackground(String resourceFilename) {}
		});
	}

	// A 640x480 camera frame of a dim wall with the projected pattern at <tt>where</tt>
	private static BufferedImage frame(Rect where) throws IOException {
		final BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = frame.createGraphics();
		g.setColor(new Color(40, 40, 40));
		g.fillRect(0, 0, 640, 480);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(ImageIO.read(TestCameraManagerCalibration.class.getResource("/pattern.png")), (int) where.getMinX(),
				(int) where.getMinY(), (int) where.getWidth(), (int) where.getHeight(), null);
		g.dispose();
		return frame;
	}

	// Auto-calibrates as the camera's thread does, frame by frame, until the pattern at <tt>where</tt> is found;
	// then the projection is set, as CalibrationFlow.calibrated does
	private void autoCalibrate(Rect where) throws IOException {
		manager.enableAutoCalibration(false);
		final BufferedImage image = frame(where);
		for (long timestamp = 1000; !manager.cameraAutoCalibrated && timestamp < 10_000; timestamp += 300)
			manager.processFrame(new Frame(image, timestamp), true);
		assertTrue(manager.cameraAutoCalibrated, "the pattern wasn't found");
		manager.setProjectionBounds(found.get(found.size() - 1));
	}

	// What CalibrationFlow.start() does to the camera, then its cancel()
	private void recalibrateAndCancel() {
		manager.setCalibrating(true);
		manager.setProjectionBounds(null);
		manager.enableAutoCalibration(false);
		manager.disableAutoCalibration();
		manager.setCalibrating(false);
	}

	@Test
	void restoringPutsBackTheProjectionAndThePerspectiveWarpThatStartingCalibrationThrewAway() throws IOException {
		autoCalibrate(new Rect(100, 80, 420, 296));
		final Optional<Rect> bounds = manager.getProjectionBounds();
		final Mat warp = manager.acm.getPerspMat();
		assertNotNull(warp);
		final CalibrationCamera.Saved saved = manager.saveCalibration();

		recalibrateAndCancel();
		assertFalse(manager.cameraAutoCalibrated);
		assertNull(manager.acm.getPerspMat());
		assertEquals(Optional.empty(), manager.getProjectionBounds());

		manager.restoreCalibration(saved);

		assertTrue(manager.cameraAutoCalibrated);
		assertSame(warp, manager.acm.getPerspMat());
		assertEquals(bounds, manager.getProjectionBounds());
	}

	@Test
	void restoringPutsBackTheExposureTheExposureStepHadSet() {
		camera.manual = OptionalDouble.of(120);
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		// The next calibration starts from auto exposure (CalculatedFPSCamera resets it for CALIBRATING)
		camera.resetExposure();

		manager.restoreCalibration(saved);

		assertEquals(List.of("auto", "manual 120.0"), camera.exposure);
	}

	@Test
	void restoringACameraThatExposedAutomaticallyPutsAutoExposureBack() {
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		// The exposure step of the cancelled calibration had lowered it by hand
		camera.manual = OptionalDouble.of(90);

		manager.restoreCalibration(saved);

		assertEquals(List.of("auto"), camera.exposure);
		assertEquals(OptionalDouble.empty(), camera.manual);
	}

	@Test
	void restoringACameraThatWasNeverCalibratedLeavesItUncalibrated() {
		final CalibrationCamera.Saved saved = manager.saveCalibration();

		recalibrateAndCancel();
		manager.restoreCalibration(saved);

		assertFalse(manager.cameraAutoCalibrated);
		assertNull(manager.acm);
		assertEquals(Optional.empty(), manager.getProjectionBounds());
	}

	// Cancel pressed while the camera's thread is still looking at a frame (a look takes 400-500 ms): whatever that
	// look finds belongs to the cancelled calibration and can't change the warp put back
	@Test
	void aLookStillUnderWayAtCancelCantChangeTheWarpPutBack() throws IOException {
		autoCalibrate(new Rect(100, 80, 420, 296));
		final Mat warp = manager.acm.getPerspMat();
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		manager.setCalibrating(true);
		manager.setProjectionBounds(null);
		manager.enableAutoCalibration(false);
		// The camera's thread took this one for its frame just before Cancel
		final AutoCalibrationManager looking = manager.acm;

		manager.disableAutoCalibration();
		manager.restoreCalibration(saved);
		// Its look ends after the restore, finding the pattern somewhere else
		looking.processFrame(new Frame(frame(new Rect(60, 40, 420, 296)), 5000));

		assertTrue(manager.cameraAutoCalibrated);
		assertSame(warp, manager.acm.getPerspMat());
	}
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`:

Replace:

```kotlin
    inner class FakeCamera : CalibrationCamera {
        var bounds: Rect? = null

        override fun getName() = "C270"

        override fun getFeedWidth() = 640
```

with:

```kotlin
    inner class FakeCamera : CalibrationCamera {
        var bounds: Rect? = null

        // What auto-calibration found beyond the bounds (in CameraManager: the perspective warp and paper size);
        // starting to look for the pattern throws it away
        var warp: String? = null

        override fun getName() = "C270"

        override fun getFeedWidth() = 640
```

Replace:

```kotlin

        override fun enableAutoCalibration(calculateFrameDelay: Boolean) {
            events += "auto on"
        }

        override fun disableAutoCalibration() {
```

with:

```kotlin

        override fun enableAutoCalibration(calculateFrameDelay: Boolean) {
            events += "auto on"
            warp = null
        }

        override fun disableAutoCalibration() {
```

Replace:

```kotlin
        override fun setCropFeedToProjection(cropFeed: Boolean) {}

        override fun setLimitDetectProjection(limitDetection: Boolean) {}
    }

    val camera = FakeCamera()

```

with:

```kotlin
        override fun setCropFeedToProjection(cropFeed: Boolean) {}

        override fun setLimitDetectProjection(limitDetection: Boolean) {}

        override fun saveCalibration(): CalibrationCamera.Saved = Saved(bounds, warp)

        override fun restoreCalibration(saved: CalibrationCamera.Saved) {
            saved as Saved
            events += "camera restored"
            bounds = saved.bounds
            warp = saved.warp
        }
    }

    data class Saved(val bounds: Rect?, val warp: String?) : CalibrationCamera.Saved

    val camera = FakeCamera()

```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`:

Replace:

```kotlin
        assertFalse(arena.needsCalibrationLabel.value)
        assertEquals(emptyList<String>(), restarts)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    // The owner's white arena (Plan 7's hardware check): auto-calibration's steps set the arena's background
```

with:

```kotlin
        assertFalse(arena.needsCalibrationLabel.value)
        assertEquals(emptyList<String>(), restarts)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    // The owner's crooked grid (Plan 8's hardware check): Cancel over a good auto-calibration put back only the
    // projection's rectangle, not the perspective warp that starting calibration threw away
    @Test
    fun cancellingARecalibrationPutsTheCamerasWholeCalibrationBack() {
        fixture.camera.bounds = Rect(100.0, 80.0, 400.0, 300.0)
        fixture.camera.warp = "the good calibration's warp"
        arena.setFullScreen(true)

        controller.start()
        assertEquals(null, fixture.camera.warp)

        controller.cancel()

        assertEquals("the good calibration's warp", fixture.camera.warp)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
    }

    @Test
    fun anUnattendedCalibrationThatDoesntFindThePatternPutsTheCamerasWholeCalibrationBack() {
        fixture.camera.bounds = Rect(100.0, 80.0, 400.0, 300.0)
        fixture.camera.warp = "the good calibration's warp"
        arena.setFullScreen(true)

        controller.startUnattended {}
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED)

        assertEquals("the good calibration's warp", fixture.camera.warp)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
    }

    // Once auto-calibration is off, so no frame still being processed can change what was put back
    @Test
    fun theCameraIsRestoredOnlyAfterItStoppedLookingForThePattern() {
        arena.setFullScreen(true)
        controller.start()

        controller.cancel()

        assertTrue(fixture.events.indexOf("auto off") < fixture.events.indexOf("camera restored"))
    }

    // The owner's white arena (Plan 7's hardware check): auto-calibration's steps set the arena's background
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.camera.TestCameraManagerCalibration --tests com.shootoff.calibration.TestCalibrationFlow --console=plain`

Expected: FAIL to compile: `method does not override or implement a method from a supertype` (`manualExposure`, `restoreManualExposure`, `saveCalibration`, `restoreCalibration`) and `cannot find symbol` (`CalibrationCamera.Saved`).

- [ ] **Step 3: Implement**

`core/src/main/java/com/shootoff/calibration/CalibrationCamera.java`:

Replace:

```java
	void setCropFeedToProjection(boolean cropFeed);

	void setLimitDetectProjection(boolean limitDetection);
}
```

with:

```java
	void setCropFeedToProjection(boolean cropFeed);

	void setLimitDetectProjection(boolean limitDetection);

	/**
	 * What calibrating changes on a camera, as {@link #saveCalibration()} saved it.
	 */
	interface Saved {}

	/**
	 * Saves what calibrating changes on this camera, for {@link #restoreCalibration} to put back: the
	 * projection, the perspective warp and paper size auto-calibration found, and the exposure. Taken before
	 * calibration starts (spec §8 Revision 4, decision 1).
	 */
	Saved saveCalibration();

	/**
	 * Puts back what {@link #saveCalibration()} saved, after a calibration that ended without calibrating
	 * (Cancel, or the pattern not found). Call it once auto-calibration is off.
	 */
	void restoreCalibration(Saved saved);
}
```

`core/src/main/java/com/shootoff/camera/CameraManager.java`:

Replace:

```java
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
```

with:

```java
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
```

Replace:

```java

	private boolean showedFPSWarning = false;

	protected AutoCalibrationManager acm = null;
	private final AtomicBoolean isAutoCalibrating = new AtomicBoolean(false);
	protected boolean cameraAutoCalibrated = false;

	protected final DeduplicationProcessor deduplicationProcessor = new DeduplicationProcessor(this);

```

with:

```java

	private boolean showedFPSWarning = false;

	// A new one for each calibration (enableAutoCalibration), so the one that found the current warp is kept
	// whole for saveCalibration while the next one looks; read on the camera's thread
	protected volatile AutoCalibrationManager acm = null;
	private final AtomicBoolean isAutoCalibrating = new AtomicBoolean(false);
	protected volatile boolean cameraAutoCalibrated = false;

	protected final DeduplicationProcessor deduplicationProcessor = new DeduplicationProcessor(this);

```

Replace:

```java
			return currentFrame.getOriginalBufferedImage();
		}

		Mat submatFrameBGR = null;

		Rect projectionBounds;
```

with:

```java
			return currentFrame.getOriginalBufferedImage();
		}

		// Each read once, the flag first: Cancel can put an earlier warp back (restoreCalibration, which sets the
		// manager before the flag) while this frame is processed
		final boolean cameraAutoCalibrated = this.cameraAutoCalibrated;
		final AutoCalibrationManager acm = this.acm;

		Mat submatFrameBGR = null;

		Rect projectionBounds;
```

Replace:

```java
	}

	public void enableAutoCalibration(boolean calculateFrameDelay) {
		if (acm == null) acm = new AutoCalibrationManager(this, camera, calculateFrameDelay);
		isAutoCalibrating.set(true);
		cameraAutoCalibrated = false;

```

with:

```java
	}

	public void enableAutoCalibration(boolean calculateFrameDelay) {
		// A fresh one: the last one's warp stays whole in whatever saveCalibration saved, and a frame it is still
		// processing can't change a warp restoreCalibration put back
		acm = new AutoCalibrationManager(this, camera, calculateFrameDelay);
		isAutoCalibrating.set(true);
		cameraAutoCalibrated = false;

```

Replace:

```java

	public void disableAutoCalibration() {
		isAutoCalibrating.set(false);
	}

	@Override
```

with:

```java

	public void disableAutoCalibration() {
		isAutoCalibrating.set(false);
	}

	// What saveCalibration saves: the auto-calibration manager holds the perspective warp and the paper size
	private record SavedCalibration(Optional<Rect> projectionBounds, boolean autoCalibrated, AutoCalibrationManager acm,
			OptionalDouble manualExposure) implements CalibrationCamera.Saved {}

	@Override
	public CalibrationCamera.Saved saveCalibration() {
		return new SavedCalibration(projectionBounds, cameraAutoCalibrated, acm, camera.manualExposure());
	}

	@Override
	public void restoreCalibration(CalibrationCamera.Saved saved) {
		final SavedCalibration calibration = (SavedCalibration) saved;
		// The warp before the flag, so a frame that sees the flag finds the warp
		acm = calibration.acm();
		cameraAutoCalibrated = calibration.autoCalibrated();
		setProjectionBounds(calibration.projectionBounds().orElse(null));

		if (calibration.manualExposure().isPresent()) {
			camera.restoreManualExposure(calibration.manualExposure().getAsDouble());
		} else {
			camera.resetExposure();
		}
	}

	@Override
```

Replace:

```java
	}

	public Point undistortCoords(int x, int y) {
		if (acm == null) return new Point(x, y);
		return acm.undistortCoords(x, y);
	}
```

with:

```java
	}

	public Point undistortCoords(int x, int y) {
		final AutoCalibrationManager acm = this.acm;
		if (acm == null) return new Point(x, y);
		return acm.undistortCoords(x, y);
	}
```

`core/src/main/java/com/shootoff/camera/cameratypes/Camera.java`:

Replace:

```java
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
```

with:

```java
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.OptionalDouble;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
```

Replace:

```java

	void resetExposure();

	static BufferedImage matToBufferedImage(Mat matBGR) {
		final BufferedImage image = new BufferedImage(matBGR.width(), matBGR.height(), BufferedImage.TYPE_3BYTE_BGR);
		final byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
```

with:

```java

	void resetExposure();

	/**
	 * @return the exposure set by hand (the exposure step's), or empty while the camera exposes automatically
	 */
	default OptionalDouble manualExposure() {
		return OptionalDouble.empty();
	}

	/**
	 * Sets the exposure by hand to <tt>exposure</tt>, as {@link #manualExposure()} reported it.
	 */
	default void restoreManualExposure(double exposure) {}

	static BufferedImage matToBufferedImage(Mat matBGR) {
		final BufferedImage image = new BufferedImage(matBGR.width(), matBGR.height(), BufferedImage.TYPE_3BYTE_BGR);
		final byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
```

`core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java`:

Replace:

```java
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
```

with:

```java
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
```

Replace:

```java
	}

	@Override
	public boolean limitsFrames() {
		return false;
	}
```

with:

```java
	}

	@Override
	public synchronized OptionalDouble manualExposure() {
		return manualExposureActive ? OptionalDouble.of(camera.get(Videoio.CAP_PROP_EXPOSURE)) : OptionalDouble.empty();
	}

	@Override
	public synchronized void restoreManualExposure(double exposure) {
		if (switchToManualExposure()) camera.set(Videoio.CAP_PROP_EXPOSURE, exposure);
	}

	@Override
	public boolean limitsFrames() {
		return false;
	}
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
    @Volatile
    private var generation = 0

    // The camera's projection when calibration started, which Cancel puts back
    @Volatile
    private var boundsBefore: Rect? = null

    // Whether a calibration - asked for, or started by itself as the arena opened - is under way, so only
    // its end is reported as a success (not a remembered one merely being applied)
```

with:

```kotlin
    @Volatile
    private var generation = 0

    // The camera's whole calibration when calibration started (its projection, perspective warp, paper size and
    // exposure), which Cancel puts back (spec §8 Revision 4, decision 1)
    @Volatile
    private var cameraBefore: CalibrationCamera.Saved? = null

    // Whether a calibration - asked for, or started by itself as the arena opened - is under way, so only
    // its end is reported as a success (not a remembered one merely being applied)
```

Replace:

```kotlin
    private fun beginSession() {
        // Whatever the camera still sends for an earlier calibration (a background, a success) is stale now
        generation++
        boundsBefore = camera.projectionBounds.orElse(null)
        foundPaper = Optional.empty()
        foundByCamera = false
        session = true
```

with:

```kotlin
    private fun beginSession() {
        // Whatever the camera still sends for an earlier calibration (a background, a success) is stale now
        generation++
        cameraBefore = camera.saveCalibration()
        foundPaper = Optional.empty()
        foundByCamera = false
        session = true
```

Replace:

```kotlin

    /**
     * Cancel: calibration ends and the arena and the camera are left as they were before it started (the
     * background, targets, shots and the projection). A drill it stopped stays stopped.
     */
    fun cancel() {
        if (!flow.isCalibrating) return
```

with:

```kotlin

    /**
     * Cancel: calibration ends and the arena and the camera are left as they were before it started (the
     * background, targets and shots; the camera's projection, perspective warp and exposure). A drill it
     * stopped stays stopped.
     */
    fun cancel() {
        if (!flow.isCalibrating) return
```

Replace:

```kotlin
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

with:

```kotlin
    }

    // Calibration ended without calibrating (Cancel, or an unattended one that didn't find the pattern): the
    // arena and the camera as they were before it started. The flow has already stopped the camera looking
    // for the pattern, so no frame still being processed changes what is put back.
    private fun putBack() {
        generation++
        session = false
        putArenaBack()
        cameraBefore?.let(camera::restoreCalibration)
        cameraBefore = null
        uiState.update { CalibrationUi() }
    }

```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test --tests com.shootoff.camera.TestCameraManagerCalibration --tests com.shootoff.calibration.TestCalibrationFlow :compose-app:test --tests com.shootoff.compose.calibration.TestCalibrationOnRequest --console=plain`

Expected: PASS. `TestCameraManagerCalibration` runs 5 tests and `TestCalibrationOnRequest` 18.

Against Step 3's `core` changes alone, before the `CalibrationController` edit, 3 compose tests fail: the two "…WholeCalibrationBack" tests (`warp` stays `null`) and `theCameraIsRestoredOnlyAfterItStoppedLookingForThePattern` (no "camera restored"). With the old `if (acm == null)` reuse, `restoringPutsBack…Warp` and `aLookStillUnderWay…` fail.

- [ ] **Step 5: The gate**

Run the gate (Global Constraints). Expected: `775/775 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/calibration/CalibrationCamera.java core/src/main/java/com/shootoff/camera/CameraManager.java core/src/main/java/com/shootoff/camera/cameratypes/Camera.java core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt
git commit -m "Cancel puts the camera's whole calibration back: the projection, the perspective warp and the exposure"
git log -1 --format=%B
```

---

### Task 2: The exposure step waits for its white screen, and a found pattern gets time to finish

**Files:**
- Modify: `core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java` (new `WHITE_SCREEN_RISE`, `WHITE_SCREEN_SETTLED`, `WHITE_SCREEN_TIMEOUT`, `patternFound()`; `StepAdjustExposure`'s fields, `reset`, `process`, new `whiteScreenSettled`)
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationCamera.java` (new `isPatternFound`)
- Modify: `core/src/main/java/com/shootoff/camera/CameraManager.java` (new `isPatternFound`)
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationFlow.java` (new `PATTERN_FOUND_GRACE`, `graceGiven`, `launchAutoCalibrationTimer(long)`; `startAutoCalibration`; the class KDoc)
- Create: `core/src/test/java/com/shootoff/camera/autocalibration/TestStepAdjustExposure.java`
- Test: `core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`, `core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`, `compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`

**Interfaces:**
- Consumes: Task 1's `TestCameraManagerCalibration` (its `frame(Rect)` helper and `manager`); `AutoCalibrationManager`'s package-private `AutoCalStep` and its `stepAdjustExposure` field, which the new test reaches from the same package.
- Produces:
  - `static final double AutoCalibrationManager.WHITE_SCREEN_RISE = 20`, `WHITE_SCREEN_SETTLED = 1.02` and `static final long WHITE_SCREEN_TIMEOUT = 2000`, all package-private.
  - `public boolean AutoCalibrationManager.patternFound()`.
  - `boolean CalibrationCamera.isPatternFound()`, abstract, implemented by `CameraManager` and both fakes (`CalibrationFixture`'s returns `false`).
  - `public static final long CalibrationFlow.PATTERN_FOUND_GRACE = 5000`.

**The cause** (spec decision 2):
- `StepAdjustExposure.process` asked for `white.png` and noted the wall-clock time (`AutoCalibrationManager.java:426-428`).
- It took its baseline from the first frame at least `SAMPLE_DELAY` (100 ms, `:396`, `:432`) later (`:435`).
- In the Compose app the white reaches the arena through the UI thread (Plan 8's Task 4), then the projector, then the camera's next exposure. On the owner's hardware that took longer than 100 ms in 11 of the 13 calibrations in the run log. Each of them logged "Failed to adjust exposure" from a baseline of 33–110 ("mean originally 62.0 / 36.8 / 33.4 / 56.5 …"). The two that worked started from 139.9 and 130.9.
- The first test replays the owner's "91.7 … 126.7" case frame by frame.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`:

Replace:

```java

	private final class FakeCamera implements CalibrationCamera {
		Optional<Rect> projectionBounds = Optional.of(new Rect(1, 2, 3, 4));

		@Override
		public String getName() {
```

with:

```java

	private final class FakeCamera implements CalibrationCamera {
		Optional<Rect> projectionBounds = Optional.of(new Rect(1, 2, 3, 4));
		boolean patternFound = false;

		@Override
		public String getName() {
```

Replace:

```java
		@Override
		public void disableAutoCalibration() {
			events.add("camera stops looking");
		}

		@Override
```

with:

```java
		@Override
		public void disableAutoCalibration() {
			events.add("camera stops looking");
		}

		@Override
		public boolean isPatternFound() {
			return patternFound;
		}

		@Override
```

Replace:

```java
				"camera detecting false", "arena shots visible false"), events);
	}

	@Test
	void aHeadlessTimeoutReportsItAndEndsCalibration() {
		headlessTimeout = Optional.of(() -> events.add("headless timeout"));
```

with:

```java
				"camera detecting false", "arena shots visible false"), events);
	}

	// Plan 8's final review: a pattern found just before the time limit was thrown away while the steps after it
	// (the paper, the exposure) still ran; the exposure step now waits for its white screen, so they take longer
	@Test
	void aPatternFoundJustBeforeTheTimeoutGetsTimeToFinishInsteadOfTheBox() {
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		camera.patternFound = true;
		events.clear();

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		assertEquals(List.of("(UI thread)"), events);
		assertTrue(flow.isCalibrating());

		// The steps after it finish in time
		flow.calibrated(new Rect(100, 50, 400, 300), Optional.empty(), false);
		runTimers(CalibrationFlow.PATTERN_FOUND_GRACE);

		assertFalse(flow.isCalibrating());
		assertFalse(events.contains("show box"));
	}

	@Test
	void aPatternFoundWhoseLastStepsNeverFinishStillReachesTheBoxAfterTheGrace() {
		final CalibrationFlow flow = flow();
		flow.setFullScreen(true);
		camera.patternFound = true;

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT);
		runTimers(CalibrationFlow.PATTERN_FOUND_GRACE);

		assertTrue(events.contains("show box"));
	}

	@Test
	void anUnattendedCalibrationWhosePatternWasFoundIsNotCancelledAtItsTimeout() {
		final CalibrationFlow flow = flow();
		flow.startUnattended(() -> events.add("not found"));
		camera.patternFound = true;

		runTimers(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED);
		assertTrue(flow.isCalibrating());

		runTimers(CalibrationFlow.PATTERN_FOUND_GRACE);
		assertFalse(flow.isCalibrating());
		assertTrue(events.contains("not found"));
	}

	@Test
	void aHeadlessTimeoutReportsItAndEndsCalibration() {
		headlessTimeout = Optional.of(() -> events.add("headless timeout"));
```

`core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`:

Replace:

```java
		assertEquals(bounds, manager.getProjectionBounds());
	}

	@Test
	void restoringPutsBackTheExposureTheExposureStepHadSet() {
		camera.manual = OptionalDouble.of(120);
```

with:

```java
		assertEquals(bounds, manager.getProjectionBounds());
	}

	// Calibration's time limit gives a pattern found just before it time to finish (spec §8 Revision 4, decision 2)
	@Test
	void thePatternIsFoundFromTheFrameThatFindsItUntilAutoCalibrationStops() throws IOException {
		manager.enableAutoCalibration(false);
		assertFalse(manager.isPatternFound());

		// One frame finds the pattern; the paper step, which takes the next frames, hasn't run yet
		manager.processFrame(new Frame(frame(new Rect(100, 80, 420, 296)), 1000), true);
		assertTrue(manager.isPatternFound());
		assertFalse(manager.cameraAutoCalibrated);

		manager.disableAutoCalibration();
		assertFalse(manager.isPatternFound());
	}

	@Test
	void restoringPutsBackTheExposureTheExposureStepHadSet() {
		camera.manual = OptionalDouble.of(120);
```

`core/src/test/java/com/shootoff/camera/autocalibration/TestStepAdjustExposure.java`:

Create it:

```java
package com.shootoff.camera.autocalibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import com.shootoff.camera.CameraCalibrationListener;
import com.shootoff.camera.Frame;
import com.shootoff.camera.MockCamera;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * The exposure step's baseline is the white screen's brightness, measured once the white has reached the
 * camera (spec §8 Revision 4, decision 2). On the owner's C270 and projector the white arrived after the step's
 * old 100 ms: "mean originally 91.7 … lowest 126.7".
 */
class TestStepAdjustExposure {
	// A camera whose exposure the step lowers or puts back
	private static final class ExposureCamera extends MockCamera {
		int decreases = 0;
		int resets = 0;

		@Override
		public boolean supportsExposureAdjustment() {
			return true;
		}

		@Override
		public boolean decreaseExposure() {
			decreases++;
			return true;
		}

		@Override
		public void resetExposure() {
			resets++;
		}
	}

	private final ExposureCamera camera = new ExposureCamera();
	private final List<String> backgrounds = new CopyOnWriteArrayList<>();
	private AutoCalibrationManager.AutoCalStep step;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() {
		final AutoCalibrationManager manager = new AutoCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {}

			@Override
			public void setArenaBackground(String resourceFilename) {
				backgrounds.add(String.valueOf(resourceFilename));
			}
		}, camera, false);
		step = manager.stepAdjustExposure;
		step.reset();
	}

	// A grey camera frame of the given brightness, as the step sees it (auto-calibration's frames are grey)
	private void frame(long timestamp, double brightness) {
		step.process(new Frame(new Mat(480, 640, CvType.CV_8UC1, new Scalar(brightness)), timestamp));
	}

	@Test
	void theBaselineIsTakenOnlyOnceTheWhiteScreenHasReachedTheCameraAndStoppedBrightening() {
		// The blank arena, as the paper step left it: the step asks for white
		frame(0, 40);
		assertEquals(List.of("white.png"), backgrounds);

		// Well past the old 100 ms, the white hasn't arrived; then it arrives over a few frames
		frame(33, 40);
		frame(133, 40);
		frame(166, 92);
		frame(200, 130);
		assertEquals(0, camera.decreases, "no baseline while the white is still brightening");

		// It stops brightening: that is the baseline, and the step starts lowering the exposure
		frame(233, 131);
		assertEquals(1, camera.decreases);

		frame(333, 118);
		frame(433, 106);
		frame(533, 95);
		frame(633, 85);
		frame(733, 76);

		assertTrue(step.completed());
		assertEquals(5, camera.decreases);
		assertEquals(0, camera.resets, "lowered, so the lowered exposure is kept");
	}

	@Test
	void aWhiteScreenThatNeverReachesTheCameraIsMeasuredAnywayAfterTwoSeconds() {
		frame(0, 40);
		for (long t = 33; t < AutoCalibrationManager.WHITE_SCREEN_TIMEOUT; t += 33)
			frame(t, 40);
		assertFalse(step.completed());

		frame(AutoCalibrationManager.WHITE_SCREEN_TIMEOUT, 40);

		// Too dark to lower: it gives up and puts the exposure back, as it always has
		assertTrue(step.completed());
		assertEquals(0, camera.decreases);
		assertEquals(1, camera.resets);
	}

	@Test
	void aRoomBrightEnoughThatTheWhiteBarelyShowsStillGetsItsBaselineOnceTheWhiteSettles() {
		frame(0, 110);
		frame(100, 112);
		frame(133, 139);
		frame(166, 140);

		assertEquals(1, camera.decreases);
	}
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`:

Replace:

```kotlin
            events += "auto off"
        }

        override fun setCropFeedToProjection(cropFeed: Boolean) {}

        override fun setLimitDetectProjection(limitDetection: Boolean) {}
```

with:

```kotlin
            events += "auto off"
        }

        override fun isPatternFound() = false

        override fun setCropFeedToProjection(cropFeed: Boolean) {}

        override fun setLimitDetectProjection(limitDetection: Boolean) {}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.camera.autocalibration.TestStepAdjustExposure --tests com.shootoff.calibration.TestCalibrationFlow --tests com.shootoff.camera.TestCameraManagerCalibration --console=plain`

Expected: FAIL to compile: `cannot find symbol` (`WHITE_SCREEN_TIMEOUT`, `PATTERN_FOUND_GRACE`, `isPatternFound`) and `method does not override or implement a method from a supertype` (the fakes' `isPatternFound`).

- [ ] **Step 3: Implement**

`core/src/main/java/com/shootoff/calibration/CalibrationCamera.java`:

Replace:

```java

	void disableAutoCalibration();

	void setCropFeedToProjection(boolean cropFeed);

	void setLimitDetectProjection(boolean limitDetection);
```

with:

```java

	void disableAutoCalibration();

	/**
	 * @return true once auto-calibration has found the pattern and is finishing the steps after it (the paper,
	 *         the exposure), until it reports success or is turned off
	 */
	boolean isPatternFound();

	void setCropFeedToProjection(boolean cropFeed);

	void setLimitDetectProjection(boolean limitDetection);
```

`core/src/main/java/com/shootoff/calibration/CalibrationFlow.java`:

Replace:

```java
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends; unattended: after 30 seconds calibration is cancelled,
 * see {@link #startUnattended}).</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
 * arena's background comes back, detection resumes after a moment, and the projector exercise that
 * was stopped starts again, last.</li>
```

with:

```java
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends; unattended: after 30 seconds calibration is cancelled,
 * see {@link #startUnattended}). A pattern the camera has already found by then gets
 * {@link #PATTERN_FOUND_GRACE} more for the steps after it.</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
 * arena's background comes back, detection resumes after a moment, and the projector exercise that
 * was stopped starts again, last.</li>
```

Replace:

```java
	// the pattern may be washed out: an unattended calibration, with no one to press Calibrate again, waits
	// that out (spec §8 Revision 3)
	public static final long AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 30 * 1000;
	// The pattern going away can look like shots
	public static final long DETECTION_RESTART_DELAY = 600;
	// Before auto-calibrating once the arena is full screen (ShootOFF issue #444)
```

with:

```java
	// the pattern may be washed out: an unattended calibration, with no one to press Calibrate again, waits
	// that out (spec §8 Revision 3)
	public static final long AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 30 * 1000;
	// A pattern the camera found just before the time limit: the steps after it (the paper, the exposure) get this
	// much longer to finish before the limit applies (spec §8 Revision 4, decision 2)
	public static final long PATTERN_FOUND_GRACE = 5 * 1000;
	// The pattern going away can look like shots
	public static final long DETECTION_RESTART_DELAY = 600;
	// Before auto-calibrating once the arena is full screen (ShootOFF issue #444)
```

Replace:

```java
	private volatile Optional<Size> perspectivePaperDims = Optional.empty();
	// Set while an unattended calibration runs (startUnattended): what its timeout runs instead of the box
	private volatile Optional<Runnable> unattendedTimeout = Optional.empty();

	/**
	 * @param headlessTimeout
```

with:

```java
	private volatile Optional<Size> perspectivePaperDims = Optional.empty();
	// Set while an unattended calibration runs (startUnattended): what its timeout runs instead of the box
	private volatile Optional<Runnable> unattendedTimeout = Optional.empty();
	// Whether this search for the pattern has had its PATTERN_FOUND_GRACE already
	private volatile boolean graceGiven = false;

	/**
	 * @param headlessTimeout
```

Replace:

```java

		showMessage(Message.AUTO_CALIBRATING);

		launchAutoCalibrationTimer();
	}

	private void launchAutoCalibrationTimer() {
		cancelAutoCalibrationTimer();

		autoCalibrationTimer = scheduler.schedule(() -> view.runOnUiThread(() -> {
			if (isCalibrating.get() && isFullScreen) {
				final Optional<Runnable> unattended = unattendedTimeout;
				if (headlessTimeout.isPresent()) {
					headlessTimeout.get().run();
```

with:

```java

		showMessage(Message.AUTO_CALIBRATING);

		graceGiven = false;
		launchAutoCalibrationTimer();
	}

	private void launchAutoCalibrationTimer() {
		launchAutoCalibrationTimer(timeout());
	}

	private void launchAutoCalibrationTimer(long delayMillis) {
		cancelAutoCalibrationTimer();

		autoCalibrationTimer = scheduler.schedule(() -> view.runOnUiThread(() -> {
			if (isCalibrating.get() && isFullScreen) {
				// The pattern was found just now: its last steps finish first
				if (!graceGiven && camera.isPatternFound()) {
					graceGiven = true;
					launchAutoCalibrationTimer(PATTERN_FOUND_GRACE);
					return;
				}

				final Optional<Runnable> unattended = unattendedTimeout;
				if (headlessTimeout.isPresent()) {
					headlessTimeout.get().run();
```

Replace:

```java
			}
			// Keep waiting
			else if (!isFullScreen) launchAutoCalibrationTimer();
		}), timeout());
	}

	private long timeout() {
```

with:

```java
			}
			// Keep waiting
			else if (!isFullScreen) launchAutoCalibrationTimer();
		}), delayMillis);
	}

	private long timeout() {
```

`core/src/main/java/com/shootoff/camera/CameraManager.java`:

Replace:

```java
		isAutoCalibrating.set(false);
	}

	// What saveCalibration saves: the auto-calibration manager holds the perspective warp and the paper size
	private record SavedCalibration(Optional<Rect> projectionBounds, boolean autoCalibrated, AutoCalibrationManager acm,
			OptionalDouble manualExposure) implements CalibrationCamera.Saved {}
```

with:

```java
		isAutoCalibrating.set(false);
	}

	@Override
	public boolean isPatternFound() {
		final AutoCalibrationManager acm = this.acm;
		return isAutoCalibrating.get() && acm != null && acm.patternFound();
	}

	// What saveCalibration saves: the auto-calibration manager holds the perspective warp and the paper size
	private record SavedCalibration(Optional<Rect> projectionBounds, boolean autoCalibrated, AutoCalibrationManager acm,
			OptionalDouble manualExposure) implements CalibrationCamera.Saved {}
```

`core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java`:

Replace:

```java

	private final TermCriteria term = new TermCriteria(TermCriteria.EPS | TermCriteria.MAX_ITER, 60, 0.0001);

	/* Paper Pattern */

	public Optional<Size> getPaperDimensions() {
```

with:

```java

	private final TermCriteria term = new TermCriteria(TermCriteria.EPS | TermCriteria.MAX_ITER, 60, 0.0001);

	// The exposure step's white screen has reached the camera once a frame is this many grey levels brighter
	// than the blank arena was when the white was asked for...
	static final double WHITE_SCREEN_RISE = 20;
	// ...and has stopped brightening: a frame at most this much brighter than the one before it
	static final double WHITE_SCREEN_SETTLED = 1.02;
	// How long to wait for that before measuring anyway, in the frames' own time
	static final long WHITE_SCREEN_TIMEOUT = 2000;

	/* Paper Pattern */

	public Optional<Size> getPaperDimensions() {
```

Replace:

```java

	public Mat getPerspMat() {
		return perspMat;
	}

	public Rect getBoundsResult() {
```

with:

```java

	public Mat getPerspMat() {
		return perspMat;
	}

	/**
	 * @return true once the pattern has been found, while the steps after it (the paper, the exposure) finish
	 */
	public boolean patternFound() {
		return stepFindBounds.completed();
	}

	public Rect getBoundsResult() {
```

Replace:

```java
		private boolean patternSet = false;
		private long lastSample = 0;
		private double origMean = 0;

		@Override
		public void reset() {
```

with:

```java
		private boolean patternSet = false;
		private long lastSample = 0;
		private double origMean = 0;
		// When the white screen was asked for, how bright the blank arena was then, and the last frame's brightness
		// while waiting for the white to reach the camera
		private long whiteRequestedAt = 0;
		private double blankMean = 0;
		private double lastMean = 0;

		@Override
		public void reset() {
```

Replace:

```java
			lastSample = 0;
			origMean = 0;
			tries = 0;
		}

		@Override
```

with:

```java
			lastSample = 0;
			origMean = 0;
			tries = 0;
			whiteRequestedAt = 0;
			blankMean = 0;
			lastMean = 0;
		}

		@Override
```

Replace:

```java

		@Override
		public void process(Frame frame) {
			if (!patternSet) {
				calibrationListener.setArenaBackground("white.png");
				patternSet = true;
				lastSample = System.currentTimeMillis();
				return;
			}

			if (completed || (System.currentTimeMillis() - lastSample) < SAMPLE_DELAY) return;

			final Scalar mean = Core.mean(frame.getOriginalMat());
			if (origMean == 0) origMean = mean.val[0];
```

with:

```java

		@Override
		public void process(Frame frame) {
			if (completed) return;

			if (!patternSet) {
				calibrationListener.setArenaBackground("white.png");
				patternSet = true;
				whiteRequestedAt = frame.getTimestamp();
				blankMean = Core.mean(frame.getOriginalMat()).val[0];
				lastMean = blankMean;
				return;
			}

			// The baseline is the white screen's brightness, so it waits for the white to reach the camera: the
			// arena, the projector and the camera can take several frames to show it (spec §8 Revision 4, decision 2)
			if (origMean == 0) {
				final double brightness = Core.mean(frame.getOriginalMat()).val[0];
				if (!whiteScreenSettled(brightness, frame.getTimestamp())) {
					lastMean = brightness;
					return;
				}
				lastSample = frame.getTimestamp() - SAMPLE_DELAY;
			}

			if (frame.getTimestamp() - lastSample < SAMPLE_DELAY) return;

			final Scalar mean = Core.mean(frame.getOriginalMat());
			if (origMean == 0) origMean = mean.val[0];
```

Replace:

```java
				}
			}

			lastSample = System.currentTimeMillis();
		}

	}

	private List<MatOfPoint2f> findPatterns(Mat mat, boolean findMultiple) {
```

with:

```java
				}
			}

			lastSample = frame.getTimestamp();
		}

		// Whether the white screen shows on the camera: a frame clearly brighter than the blank arena, no longer
		// getting brighter; or, once WHITE_SCREEN_TIMEOUT has passed, whatever the camera sees by then
		private boolean whiteScreenSettled(double brightness, long timestamp) {
			final long waited = timestamp - whiteRequestedAt;
			final double white = blankMean + WHITE_SCREEN_RISE;

			if (brightness >= white && lastMean >= white && brightness <= lastMean * WHITE_SCREEN_SETTLED) {
				logger.info("The white screen reached the camera after {} ms: mean {} from {} blank", waited, brightness,
						blankMean);
				return true;
			}

			if (waited >= WHITE_SCREEN_TIMEOUT) {
				logger.info("The white screen didn't reach the camera in {} ms (mean {} from {} blank); measuring anyway",
						waited, brightness, blankMean);
				return true;
			}

			return false;
		}
	}

	private List<MatOfPoint2f> findPatterns(Mat mat, boolean findMultiple) {
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test --tests com.shootoff.camera.autocalibration.TestStepAdjustExposure --tests com.shootoff.calibration.TestCalibrationFlow --tests com.shootoff.camera.TestCameraManagerCalibration :compose-app:test --tests com.shootoff.compose.calibration.TestCalibrationOnRequest --console=plain`

Expected: PASS.

- [ ] **Step 5: The gate**

Expected: `782/782 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java core/src/main/java/com/shootoff/calibration/CalibrationCamera.java core/src/main/java/com/shootoff/calibration/CalibrationFlow.java core/src/main/java/com/shootoff/camera/CameraManager.java core/src/test/java/com/shootoff/camera/autocalibration/TestStepAdjustExposure.java core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt
git commit -m "The exposure step measures once the white screen reaches the camera, and a found pattern gets time to finish"
git log -1 --format=%B
```

---

### Task 3: The exposure held to a frame while looking for the pattern, and the frame rate logged

**Files:**
- Modify: `core/src/main/java/com/shootoff/camera/cameratypes/Camera.java` (default `limitExposureToFramePeriod`, `releaseExposureLimit`, `exposureState`)
- Modify: `core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java`:
  - new `V4L2_EXPOSURE_UNITS_PER_SECOND`, `EXPOSURE_LIMIT_SLACK`, `CAPTURE_STATE_LOG_DELAY` and `exposureLimited`;
  - new `device()`, `logCaptureState()`, `exposureState()`, `describeExposure`, `exposureLimit`, `limitExposureToFramePeriod` and `releaseExposureLimit`;
  - changed `open`, `switchToManualExposure`, `decreaseExposure` and `resetExposure`.
- Modify: `core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java` (`StepFindBounds.process`)
- Modify: `core/src/main/java/com/shootoff/camera/CameraManager.java` (`disableAutoCalibration`, `checkIfMinimumFPS`)
- Create: `core/src/test/java/com/shootoff/camera/autocalibration/TestPatternSearchExposure.java`
- Test: `core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java` (JUnit 4, as the file is), `core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`, `core/src/test/java/com/shootoff/camera/TestCameraManagerDiagnostics.java`

**Interfaces:**
- Consumes:
  - Task 1's `TestCameraManagerCalibration.ExposureCamera` (its `exposure` list).
  - `SarxosCaptureCamera.disableDynamicFramerate(String)` (Plan 7), `switchToManualExposure`, `resetExposure`.
  - `TimerPool.schedule(Runnable, long)`.
- Produces:
  - `default boolean Camera.limitExposureToFramePeriod()`, `default void Camera.releaseExposureLimit()` and `default String Camera.exposureState()`, which returns `""`.
  - `static OptionalDouble SarxosCaptureCamera.exposureLimit(double exposure, double fps)` and `static String SarxosCaptureCamera.describeExposure(double exposure, double autoExposure, OptionalInt dynamicFramerate)`, both package-private.
  - `CameraManager.disableAutoCalibration()` now also calls `camera.releaseExposureLimit()`.

  Nothing downstream consumes these.

**The cause** is set out under "Root causes, item 3" above.

What only the hardware check confirms:
- that the C270 takes manual exposure 333 and runs at about 30 FPS with its lens covered;
- whether it turns `exposure_dynamic_framerate` back on (the log's "was 1, set to 0" after a switch).

The tests pin the decision, the wiring and the log text with fakes.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`:

Replace:

```java
		public void resetExposure() {
			exposure.add("auto");
			manual = OptionalDouble.empty();
		}
	}

```

with:

```java
		public void resetExposure() {
			exposure.add("auto");
			manual = OptionalDouble.empty();
		}

		@Override
		public void releaseExposureLimit() {
			exposure.add("limit released");
		}
	}

```

Replace:

```java
		assertFalse(manager.isPatternFound());
	}

	@Test
	void restoringPutsBackTheExposureTheExposureStepHadSet() {
		camera.manual = OptionalDouble.of(120);
```

with:

```java
		assertFalse(manager.isPatternFound());
	}

	// The exposure held to a frame period while looking for the pattern ends with the looking, whatever ends it
	@Test
	void theExposureLimitEndsWhenAutoCalibrationStops() {
		manager.enableAutoCalibration(false);

		manager.disableAutoCalibration();

		assertEquals(List.of("limit released"), camera.exposure);
	}

	@Test
	void restoringPutsBackTheExposureTheExposureStepHadSet() {
		camera.manual = OptionalDouble.of(120);
```

`core/src/test/java/com/shootoff/camera/TestCameraManagerDiagnostics.java`:

Replace:

```java
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.Settings;
import com.shootoff.config.ConfigurationException;
```

with:

```java
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.shootoff.config.Settings;
import com.shootoff.config.ConfigurationException;
```

Replace:

```java
		assertTrue(view.awaitRemovedWarnings(1, 5, TimeUnit.SECONDS), "warning was never removed");
	}

	@Test
	void motionWarningIsShownThenRemoved() throws InterruptedException {
		cameraManager.showMotionWarning();
```

with:

```java
		assertTrue(view.awaitRemovedWarnings(1, 5, TimeUnit.SECONDS), "warning was never removed");
	}

	// A low frame rate says what the camera's exposure was, so the log tells a dark scene from a slow start
	// (spec §8 Revision 4, decision 3)
	@Test
	void aFrameRateTooLowForShotDetectionLogsTheCamerasExposure() {
		final CameraManager manager = new CameraManager(new MockCamera() {
			@Override
			public String exposureState() {
				return "exposure 1002 (auto, mode 3), exposure_dynamic_framerate 0";
			}
		}, null, new RecordingCameraView());
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		final Logger logger = (Logger) LoggerFactory.getLogger(CameraManager.class);
		logger.addAppender(appender);
		try {
			manager.newFPS(4.9);
		} finally {
			logger.detachAppender(appender);
		}

		assertTrue(appender.list.stream().anyMatch(
				e -> e.getFormattedMessage().equals("[MockCamera] exposure 1002 (auto, mode 3), exposure_dynamic_framerate 0")));
	}

	@Test
	void motionWarningIsShownThenRemoved() throws InterruptedException {
		cameraManager.showMotionWarning();
```

`core/src/test/java/com/shootoff/camera/autocalibration/TestPatternSearchExposure.java`:

Create it:

```java
package com.shootoff.camera.autocalibration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import com.shootoff.camera.CameraCalibrationListener;
import com.shootoff.camera.Frame;
import com.shootoff.camera.MockCamera;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * While auto-calibration looks for the pattern it keeps the camera's frame rate up (spec §8 Revision 4,
 * decision 3): at each look it asks the camera to hold its exposure to a frame period. The C270 dropped to
 * 3.8–4.9 FPS in a dark scene with exposure_dynamic_framerate already 0.
 */
class TestPatternSearchExposure {
	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@Test
	void eachLookForThePatternHoldsTheExposureToAFramePeriod() {
		final AtomicInteger limits = new AtomicInteger();
		final MockCamera camera = new MockCamera() {
			@Override
			public boolean limitExposureToFramePeriod() {
				limits.incrementAndGet();
				return true;
			}
		};
		final AutoCalibrationManager manager = new AutoCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {}

			@Override
			public void setArenaBackground(String resourceFilename) {}
		}, camera, false);
		manager.reset();
		final Mat dark = new Mat(480, 640, CvType.CV_8UC3, new Scalar(10, 10, 10));

		// It looks every 250 ms of the frames' time; the frames between looks are skipped
		manager.processFrame(new Frame(dark, 1000));
		manager.processFrame(new Frame(dark, 1100));
		manager.processFrame(new Frame(dark, 1300));

		assertEquals(2, limits.get());
	}
}
```

`core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java`:

Replace:

```java

import java.awt.Dimension;
import java.util.Optional;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
```

with:

```java

import java.awt.Dimension;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
```

Replace:

```java
	public void testDisablingDynamicFramerateOnMissingDeviceReportsNoChange() {
		assertFalse(SarxosCaptureCamera.disableDynamicFramerate("/nonexistent/video99"));
	}
}
```

with:

```java
	public void testDisablingDynamicFramerateOnMissingDeviceReportsNoChange() {
		assertFalse(SarxosCaptureCamera.disableDynamicFramerate("/nonexistent/video99"));
	}

	// The owner's C270 with its lens covered: auto exposure at 1002 (100 ms) at 30 FPS
	@Test
	public void testAnExposureLongerThanAFramePeriodIsHeldToOne() {
		assertEquals(OptionalDouble.of(333), SarxosCaptureCamera.exposureLimit(1002, 30));
	}

	// The owner's C270 in normal light sits at 336, a hair over the 333 of a frame at 30 FPS: left alone
	@Test
	public void testAnExposureAboutAFramePeriodIsLeftAlone() {
		assertEquals(OptionalDouble.empty(), SarxosCaptureCamera.exposureLimit(336, 30));
		assertEquals(OptionalDouble.empty(), SarxosCaptureCamera.exposureLimit(120, 30));
	}

	@Test
	public void testAnUnknownFrameRateIsTakenAsThirty() {
		assertEquals(OptionalDouble.of(333), SarxosCaptureCamera.exposureLimit(700, 0));
	}

	@Test
	public void testAnUnopenedCamerasExposureIsNeverHeld() {
		final SarxosCaptureCamera camera = new SarxosCaptureCamera("Test Camera", 7);

		assertFalse(camera.limitExposureToFramePeriod());
		camera.releaseExposureLimit();
		assertEquals("", camera.exposureState());
	}

	@Test
	public void testTheExposureStateSaysTheExposureItsModeAndTheDynamicFramerate() {
		assertEquals("exposure 1002 (auto, mode 3), exposure_dynamic_framerate 0",
				SarxosCaptureCamera.describeExposure(1002, 3, OptionalInt.of(0)));
		assertEquals("exposure 333 (manual), exposure_dynamic_framerate unknown",
				SarxosCaptureCamera.describeExposure(333, 1, OptionalInt.empty()));
	}
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.camera.autocalibration.TestPatternSearchExposure --tests com.shootoff.camera.cameratypes.TestSarxosCaptureCamera --tests com.shootoff.camera.TestCameraManagerCalibration --tests com.shootoff.camera.TestCameraManagerDiagnostics --console=plain`

Expected: FAIL to compile: `method does not override or implement a method from a supertype` (`limitExposureToFramePeriod`, `releaseExposureLimit`, `exposureState`) and `cannot find symbol` (`exposureLimit`, `describeExposure`).

- [ ] **Step 3: Implement**

`core/src/main/java/com/shootoff/camera/CameraManager.java`:

Replace:

```java
		if (cameraFPS < MIN_SHOT_DETECTION_FPS && !showedFPSWarning) {
			logger.warn("[{}] Current webcam FPS is {}, which is too low for reliable shot detection", camera.getName(),
					getFPS());
			if (cameraErrorView.isPresent()) cameraErrorView.get().showFPSWarning(camera, getFPS());
			showedFPSWarning = true;
		}
```

with:

```java
		if (cameraFPS < MIN_SHOT_DETECTION_FPS && !showedFPSWarning) {
			logger.warn("[{}] Current webcam FPS is {}, which is too low for reliable shot detection", camera.getName(),
					getFPS());
			// A dark scene (a long exposure) or a slow start? (spec §8 Revision 4, decision 3)
			final String exposure = camera.exposureState();
			if (!exposure.isEmpty()) logger.info("[{}] {}", camera.getName(), exposure);
			if (cameraErrorView.isPresent()) cameraErrorView.get().showFPSWarning(camera, getFPS());
			showedFPSWarning = true;
		}
```

Replace:

```java

	public void disableAutoCalibration() {
		isAutoCalibrating.set(false);
	}

	@Override
```

with:

```java

	public void disableAutoCalibration() {
		isAutoCalibrating.set(false);
		// Whatever ended it (the pattern found, Cancel, the time limit), the search's frame-rate limit ends too
		camera.releaseExposureLimit();
	}

	@Override
```

`core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java`:

Replace:

```java
			if (frame.getTimestamp() - lastFrameCheck < minimumInterval) return;

			lastFrameCheck = frame.getTimestamp();

			Imgproc.equalizeHist(frame.getOriginalMat(), frame.getOriginalMat());

```

with:

```java
			if (frame.getTimestamp() - lastFrameCheck < minimumInterval) return;

			lastFrameCheck = frame.getTimestamp();

			// A dark scene lengthens automatic exposure and drops the frame rate, slowing the search (spec §8
			// Revision 4, decision 3); held to a frame until auto-calibration stops (CameraManager)
			camera.limitExposureToFramePeriod();

			Imgproc.equalizeHist(frame.getOriginalMat(), frame.getOriginalMat());

```

`core/src/main/java/com/shootoff/camera/cameratypes/Camera.java`:

Replace:

```java
	 */
	default void restoreManualExposure(double exposure) {}

	static BufferedImage matToBufferedImage(Mat matBGR) {
		final BufferedImage image = new BufferedImage(matBGR.width(), matBGR.height(), BufferedImage.TYPE_3BYTE_BGR);
		final byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
```

with:

```java
	 */
	default void restoreManualExposure(double exposure) {}

	/**
	 * While auto-calibration looks for the pattern: if automatic exposure has made the exposure longer than a
	 * frame, which lowers the frame rate in a dark scene, holds it to one frame by hand until
	 * {@link #releaseExposureLimit()}.
	 *
	 * @return true if the exposure is held now
	 */
	default boolean limitExposureToFramePeriod() {
		return false;
	}

	/**
	 * Ends {@link #limitExposureToFramePeriod()}: automatic exposure again, unless the exposure step has set an
	 * exposure of its own since.
	 */
	default void releaseExposureLimit() {}

	/**
	 * @return the exposure's settings, for the log; empty if the camera can't say
	 */
	default String exposureState() {
		return "";
	}

	static BufferedImage matToBufferedImage(Mat matBGR) {
		final BufferedImage image = new BufferedImage(matBGR.width(), matBGR.height(), BufferedImage.TYPE_3BYTE_BGR);
		final byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
```

`core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java`:

Replace:

```java
import com.shootoff.camera.shotdetection.NativeShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.util.SystemInfo;

public class SarxosCaptureCamera extends CalculatedFPSCamera {
	private static final Logger logger = LoggerFactory.getLogger(SarxosCaptureCamera.class);

	// V4L2's CAP_PROP_AUTO_EXPOSURE: 1 = manual, 3 = aperture priority (auto).
	private static final double V4L2_MANUAL_EXPOSURE = 1;

	private int cameraIndex = -1;
	private final VideoCapture camera;
```

with:

```java
import com.shootoff.camera.shotdetection.NativeShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.util.SystemInfo;
import com.shootoff.util.TimerPool;

public class SarxosCaptureCamera extends CalculatedFPSCamera {
	private static final Logger logger = LoggerFactory.getLogger(SarxosCaptureCamera.class);

	// V4L2's CAP_PROP_AUTO_EXPOSURE: 1 = manual, 3 = aperture priority (auto).
	private static final double V4L2_MANUAL_EXPOSURE = 1;

	// V4L2's CAP_PROP_EXPOSURE is exposure_time_absolute, in units of 100 µs
	private static final double V4L2_EXPOSURE_UNITS_PER_SECOND = 10_000;
	// An exposure this much longer than a frame still keeps the frame rate: the owner's C270 sits at 336 in
	// normal light, a hair over the 333 of a frame at 30 FPS
	private static final double EXPOSURE_LIMIT_SLACK = 1.1;
	// How long after opening the frame rate and exposure are logged, once the camera has settled into them
	static final long CAPTURE_STATE_LOG_DELAY = 3000;

	private int cameraIndex = -1;
	private final VideoCapture camera;
```

Replace:

```java
			// whatever size was requested now that the capture is actually open.
			if (requestedViewSize.isPresent()) applyViewSize(camera, requestedViewSize.get());

			// OpenCV opens camera index N as /dev/videoN
			if (SystemInfo.isLinux()) disableDynamicFramerate("/dev/video" + cameraIndex);

			// Logged after resolution is applied so this reports what's actually negotiated.
			logCaptureSettings(camera);

			CameraFactory.openCamerasAdd(this);
		}

		return open;
	}

	/**
```

with:

```java
			// whatever size was requested now that the capture is actually open.
			if (requestedViewSize.isPresent()) applyViewSize(camera, requestedViewSize.get());

			if (SystemInfo.isLinux()) disableDynamicFramerate(device());

			// Logged after resolution is applied so this reports what's actually negotiated.
			logCaptureSettings(camera);

			CameraFactory.openCamerasAdd(this);

			// What the frame rate and the exposure settle into (spec §8 Revision 4, decision 3)
			TimerPool.schedule(this::logCaptureState, CAPTURE_STATE_LOG_DELAY);
		}

		return open;
	}

	// OpenCV opens camera index N as /dev/videoN
	private String device() {
		return "/dev/video" + cameraIndex;
	}

	private void logCaptureState() {
		if (!isOpen() || closing.get()) return;

		logger.info("{} {} s after opening: {} FPS, {}", getName(), CAPTURE_STATE_LOG_DELAY / 1000,
				String.format("%.1f", getFPS()), exposureState());
	}

	@Override
	public synchronized String exposureState() {
		if (!isOpen()) return "";

		final OptionalInt dynamicFramerate = SystemInfo.isLinux()
				? V4l2Controls.getControl(device(), V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE)
				: OptionalInt.empty();

		return describeExposure(camera.get(Videoio.CAP_PROP_EXPOSURE), camera.get(Videoio.CAP_PROP_AUTO_EXPOSURE),
				dynamicFramerate);
	}

	static String describeExposure(double exposure, double autoExposure, OptionalInt dynamicFramerate) {
		final String mode = autoExposure == V4L2_MANUAL_EXPOSURE ? "manual"
				: "auto, mode " + Math.round(autoExposure);
		return "exposure " + Math.round(exposure) + " (" + mode + "), exposure_dynamic_framerate "
				+ (dynamicFramerate.isPresent() ? String.valueOf(dynamicFramerate.getAsInt()) : "unknown");
	}

	/**
	 * @return one frame period at <tt>fps</tt> (30 if unknown), in V4L2's exposure units, if <tt>exposure</tt>
	 *         is longer than that (give or take {@link #EXPOSURE_LIMIT_SLACK}); otherwise empty
	 */
	static OptionalDouble exposureLimit(double exposure, double fps) {
		final double framesPerSecond = fps > 0 ? fps : DEFAULT_FPS;
		final double framePeriod = Math.floor(V4L2_EXPOSURE_UNITS_PER_SECOND / framesPerSecond);

		return exposure > framePeriod * EXPOSURE_LIMIT_SLACK ? OptionalDouble.of(framePeriod) : OptionalDouble.empty();
	}

	/**
```

Replace:

```java
	private Optional<Double> origExposure = Optional.empty();
	private Optional<Double> origAutoExposure = Optional.empty();
	private boolean manualExposureActive = false;

	@Override
	public synchronized boolean supportsExposureAdjustment() {
```

with:

```java
	private Optional<Double> origExposure = Optional.empty();
	private Optional<Double> origAutoExposure = Optional.empty();
	private boolean manualExposureActive = false;
	// Whether manual exposure is on only to hold the exposure to a frame (limitExposureToFramePeriod)
	private boolean exposureLimited = false;

	@Override
	public synchronized boolean supportsExposureAdjustment() {
```

Replace:

```java
			logger.info("{} switched to manual exposure (was auto={}) to allow exposure adjustment", getName(),
					autoExposure);

		return true;
	}

	@Override
	public synchronized boolean decreaseExposure() {
		// V4L2 must be in manual exposure mode before CAP_PROP_EXPOSURE writes are honored.
		if (!switchToManualExposure()) return false;

		// Logic:
		// If camera exposure is positive, decrease towards zero
```

with:

```java
			logger.info("{} switched to manual exposure (was auto={}) to allow exposure adjustment", getName(),
					autoExposure);

		// Some cameras may turn dynamic frame rate back on as the exposure mode changes
		disableDynamicFramerate(device());

		return true;
	}

	@Override
	public synchronized boolean limitExposureToFramePeriod() {
		if (!SystemInfo.isLinux() || !isOpen() || manualExposureActive) return false;

		final double exposure = camera.get(Videoio.CAP_PROP_EXPOSURE);
		final OptionalDouble limit = exposureLimit(exposure, camera.get(Videoio.CAP_PROP_FPS));
		if (limit.isEmpty() || !switchToManualExposure()) return false;

		camera.set(Videoio.CAP_PROP_EXPOSURE, limit.getAsDouble());
		exposureLimited = true;

		logger.info("{} auto exposure was {}, longer than a frame: held at {} by hand while looking for the pattern",
				getName(), exposure, limit.getAsDouble());

		return true;
	}

	@Override
	public synchronized void releaseExposureLimit() {
		if (exposureLimited) resetExposure();
	}

	@Override
	public synchronized boolean decreaseExposure() {
		// V4L2 must be in manual exposure mode before CAP_PROP_EXPOSURE writes are honored.
		if (!switchToManualExposure()) return false;

		// The exposure step sets its own exposure from here on: releasing the frame limit leaves it alone
		exposureLimited = false;

		// Logic:
		// If camera exposure is positive, decrease towards zero
```

Replace:

```java
		// Set exposure while still in manual mode -- V4L2 rejects it once auto mode is restored.
		if (origExposure.isPresent()) camera.set(Videoio.CAP_PROP_EXPOSURE, origExposure.get());

		if (manualExposureActive && origAutoExposure.isPresent()) {
			final double autoExposure = origAutoExposure.get();
			camera.set(Videoio.CAP_PROP_AUTO_EXPOSURE, autoExposure);
```

with:

```java
		// Set exposure while still in manual mode -- V4L2 rejects it once auto mode is restored.
		if (origExposure.isPresent()) camera.set(Videoio.CAP_PROP_EXPOSURE, origExposure.get());

		exposureLimited = false;

		if (manualExposureActive && origAutoExposure.isPresent()) {
			final double autoExposure = origAutoExposure.get();
			camera.set(Videoio.CAP_PROP_AUTO_EXPOSURE, autoExposure);
```

Replace:

```java

			if (logger.isInfoEnabled())
				logger.info("{} restored auto exposure mode to {}", getName(), autoExposure);
		}
	}

```

with:

```java

			if (logger.isInfoEnabled())
				logger.info("{} restored auto exposure mode to {}", getName(), autoExposure);

			if (SystemInfo.isLinux()) disableDynamicFramerate(device());
		}
	}

```

- [ ] **Step 4: Run the tests**

Run the Step 2 command. Expected: PASS. `TestSarxosCaptureCamera` runs 16 tests.

- [ ] **Step 5: The gate**

Expected: `790/790 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/camera/cameratypes/Camera.java core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java core/src/main/java/com/shootoff/camera/CameraManager.java core/src/test/java/com/shootoff/camera/autocalibration/TestPatternSearchExposure.java core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java core/src/test/java/com/shootoff/camera/TestCameraManagerDiagnostics.java
git commit -m "Hold the exposure to a frame while looking for the pattern, and log the frame rate and exposure after each open"
git log -1 --format=%B
```

---

### Task 4: A replug is retried, and an unattended calibration waits a minute

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:
  - new `reconnectRetryMillis` parameter, and `RECONNECT_TRIES` and `RECONNECT_RETRY_MILLIS`;
  - `openCameraInBackground` and `publish` gain `reportFailure`;
  - `watchForReturn` changes, with the new `findReturned` and `reopen`;
  - imports.
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationFlow.java` (`AUTO_CALIBRATION_TIMEOUT_UNATTENDED` and the class KDoc)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`, `core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`

**Interfaces:**
- Consumes:
  - Plan 7's `watchForReturn` and `sameCamera`.
  - `openCameraInBackground(camera, then)`, whose `then` hears on the UI thread whether it opened.
  - `publish`, whose success ends the watch.
- Produces:
  - `AppState(…, reconnectRetryMillis: Long = RECONNECT_RETRY_MILLIS, …)`, right after `reconnectMillis`.
  - `const val AppState.RECONNECT_TRIES = 3` and `const val AppState.RECONNECT_RETRY_MILLIS = 1000L`.
  - `fun AppState.openCameraInBackground(camera: Camera, reportFailure: Boolean = true, then: (Boolean) -> Unit = {}): Boolean`. Existing calls with a trailing lambda are unchanged.
  - `CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 60000`.

  Task 6 edits `publish` next to these lines.

**The cause** (spec decision 4):
- `watchForReturn` posted one reopen when the camera first reappeared (`AppState.kt:590`, `if (back != null && !listed)`), then marked it listed (`:599`) whatever happened.
- At 21:42:56 that one try failed ("Cannot open the webcam … /dev/video0", with OpenCV's "can't open camera by index"), and nothing tried again until the owner replugged at 21:43:16.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`:

Replace:

```java
		assertFalse(projectorExerciseRunning, "the drill was stopped, not restarted");
	}

	@Test
	void anUnattendedCalibrationThatFindsThePatternEndsAsAnyOtherAndTheNextStartIsAttended() {
		final CalibrationFlow flow = flow();
```

with:

```java
		assertFalse(projectorExerciseRunning, "the drill was stopped, not restarted");
	}

	// Spec §8 Revision 4, decision 5: a camera just plugged in, or just reopened after a failed try, gets a minute
	@Test
	void anUnattendedCalibrationLooksForAMinute() {
		final CalibrationFlow flow = flow();

		flow.startUnattended(() -> events.add("not found"));

		assertEquals(60_000, CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED);
		assertEquals(List.of(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED),
				timers.stream().map(Timer::delayMillis).toList());
	}

	@Test
	void anUnattendedCalibrationThatFindsThePatternEndsAsAnyOtherAndTheNextStartIsAttended() {
		final CalibrationFlow flow = flow();
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.camera.MockCamera
import com.shootoff.camera.cameratypes.Camera
```

with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.graphics.ImageBitmap
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.camera.MockCamera
import com.shootoff.camera.cameratypes.Camera
```

Replace:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Optional
```

with:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.Optional
```

Replace:

```kotlin
    }

    private fun reconnectingApp(source: CameraSource) =
        AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() }, reconnectMillis = 20)

    @Test
    fun aLostCameraReopensByItselfWhenItIsPluggedBackIn() {
```

with:

```kotlin
    }

    private fun reconnectingApp(source: CameraSource) =
        AppState(
            Settings(ScratchConfig.emptyFile().path, arrayOf()),
            ExerciseCatalog(),
            source,
            { AppFixture.ownerScreens },
            ManualClock(),
            { it.run() },
            reconnectMillis = 20,
            reconnectRetryMillis = 20,
        )

    // The ERROR lines AppState logs while [block] runs
    private fun errorsLoggedDuring(block: () -> Unit): List<String> {
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val logger = LoggerFactory.getLogger(AppState::class.java) as LogbackLogger
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }
    }

    @Test
    fun aLostCameraReopensByItselfWhenItIsPluggedBackIn() {
```

Replace:

```kotlin
    }

    @Test
    fun aCameraThatIsListedButWontOpenIsTriedOnceUntilItIsPluggedInAgain() {
        val source = Pluggable(AppFixture.TestCamera("HD Webcam C270"))
        val app = reconnectingApp(source)
        try {
```

with:

```kotlin
    }

    @Test
    fun aCameraThatIsListedButWontOpenIsTriedThreeTimesUntilItIsPluggedInAgain() {
        val source = Pluggable(AppFixture.TestCamera("HD Webcam C270"))
        val app = reconnectingApp(source)
        try {
```

Replace:

```kotlin
                    return false
                }
            }
            source.plugged.clear()
            source.plugged.add(locked)

            awaitTrue { locked.opens.get() == 1 }
            Thread.sleep(200)
            assertEquals(1, locked.opens.get())
            assertEquals("HD Webcam C270", app.waitingFor.value)
        } finally {
            app.close()
        }
```

with:

```kotlin
                    return false
                }
            }

            val errors = errorsLoggedDuring {
                source.plugged.clear()
                source.plugged.add(locked)

                awaitTrue { locked.opens.get() == AppState.RECONNECT_TRIES }
                Thread.sleep(200)
            }

            assertEquals(AppState.RECONNECT_TRIES, locked.opens.get())
            assertEquals("HD Webcam C270", app.waitingFor.value)
            // Only the last try is reported, so the owner sees one error, not one every try
            assertEquals(listOf("Cannot open the webcam HD Webcam C270"), errors)
        } finally {
            app.close()
        }
    }

    // The owner's replug (Plan 8's hardware check): the device node appeared a moment before the camera could be
    // opened, and the one try failed, leaving the camera closed until the next replug
    @Test
    fun aCameraThatFailsToReopenJustAfterItAppearsIsTriedAgainAndOpens() {
        val source = Pluggable(AppFixture.TestCamera("UVC Camera (046d:0825) /dev/video0"))
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.openArena()
            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
            val notReadyYet = object : AppFixture.TestCamera("UVC Camera (046d:0825) /dev/video0") {
                val opens = AtomicInteger()

                @Volatile
                private var opened = false

                override fun isOpen() = opened

                override fun open(): Boolean {
                    opened = opens.incrementAndGet() > 1
                    return opened
                }
            }

            val errors = errorsLoggedDuring {
                source.plugged.clear()
                source.plugged.add(notReadyYet)

                awaitTrue { app.camera.value != null }
            }

            assertSame(notReadyYet, app.camera.value!!.camera)
            assertEquals(2, notReadyYet.opens.get())
            assertNull(app.waitingFor.value)
            assertNull(app.cameraProblem.value)
            assertEquals(emptyList<String>(), errors)
        } finally {
            app.close()
        }
    }

    // Plan 7's deferred minor: a reappearance was used up even when the reopen was declined because another
    // camera was opening; it must still be tried once that other open is over
    @Test
    fun aCameraThatAppearsWhileAnotherIsOpeningIsTriedOnceThatOpenFails() {
        val lost = AppFixture.TestCamera("HD Webcam C270")
        val source = Pluggable(lost)
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.cameraProblems.showMissingCameraError(lost)
            source.plugged.clear()
            // The owner picks a camera that takes a while to open, and then fails
            val slow = object : AppFixture.TestCamera("Other camera") {
                val release = CountDownLatch(1)

                override fun isOpen() = false

                override fun open(): Boolean {
                    release.await(5, TimeUnit.SECONDS)
                    return false
                }
            }
            app.openCameraInBackground(slow)
            source.plugged.add(AppFixture.TestCamera("HD Webcam C270"))
            Thread.sleep(200)
            assertNull(app.camera.value)

            slow.release.countDown()

            awaitTrue { app.camera.value != null }
            assertEquals("HD Webcam C270", app.camera.value!!.camera.name)
        } finally {
            app.close()
        }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.calibration.TestCalibrationFlow :compose-app:test --tests com.shootoff.compose.app.TestProblems --console=plain --continue`

Expected:
- `core`: FAIL, `anUnattendedCalibrationLooksForAMinute` (`expected: <60000> but was: <30000>`).
- `compose-app`: FAIL to compile, `No parameter with name 'reconnectRetryMillis' found` and `Unresolved reference 'RECONNECT_TRIES'`.

- [ ] **Step 3: Implement**

`core/src/main/java/com/shootoff/calibration/CalibrationFlow.java`:

Replace:

```java
 * <li><b>Success</b>: the camera reports the pattern's bounds, which become the arena's projection,
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends; unattended: after 30 seconds calibration is cancelled,
 * see {@link #startUnattended}). A pattern the camera has already found by then gets
 * {@link #PATTERN_FOUND_GRACE} more for the steps after it.</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
```

with:

```java
 * <li><b>Success</b>: the camera reports the pattern's bounds, which become the arena's projection,
 * and calibration ends.</li>
 * <li><b>Timeout</b>: after 12 seconds the user gets a box to drag over the projection by hand
 * (headless: after 45 seconds calibration ends; unattended: after 60 seconds calibration is cancelled,
 * see {@link #startUnattended}). A pattern the camera has already found by then gets
 * {@link #PATTERN_FOUND_GRACE} more for the steps after it.</li>
 * <li><b>End</b>: stopping with the box calibrates to it. Then the perspective is worked out, the
```

Replace:

```java
	public static final long AUTO_CALIBRATION_TIMEOUT_HEADLESS = 45 * 1000;
	// A camera just plugged in (or just opened) can take 10-15 seconds to settle its exposure, and until then
	// the pattern may be washed out: an unattended calibration, with no one to press Calibrate again, waits
	// that out (spec §8 Revision 3)
	public static final long AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 30 * 1000;
	// A pattern the camera found just before the time limit: the steps after it (the paper, the exposure) get this
	// much longer to finish before the limit applies (spec §8 Revision 4, decision 2)
	public static final long PATTERN_FOUND_GRACE = 5 * 1000;
```

with:

```java
	public static final long AUTO_CALIBRATION_TIMEOUT_HEADLESS = 45 * 1000;
	// A camera just plugged in (or just opened) can take 10-15 seconds to settle its exposure, and until then
	// the pattern may be washed out: an unattended calibration, with no one to press Calibrate again, waits
	// that out with room to spare, since a longer wait costs nothing when no one is there (spec §8 Revision 3,
	// and Revision 4, decision 5)
	public static final long AUTO_CALIBRATION_TIMEOUT_UNATTENDED = 60 * 1000;
	// A pattern the camera found just before the time limit: the steps after it (the paper, the exposure) get this
	// much longer to finish before the limit applies (spec §8 Revision 4, decision 2)
	public static final long PATTERN_FOUND_GRACE = 5 * 1000;
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.shots.RangeReset
import com.shootoff.util.TimerPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
```

with:

```kotlin
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.shots.RangeReset
import com.shootoff.util.TimerPool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
```

Replace:

```kotlin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

with:

```kotlin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
```

Replace:

```kotlin
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check shows
 *   the pattern, or an automatic calibration starts
 * @param calibrationTimers runs calibration's and the check's timers (auto-calibration's timeout among them)
```

with:

```kotlin
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 * @param reconnectRetryMillis how long after a failed reopen of a camera just plugged back in it is tried again
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check shows
 *   the pattern, or an automatic calibration starts
 * @param calibrationTimers runs calibration's and the check's timers (auto-calibration's timeout among them)
```

Replace:

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
```

with:

```kotlin
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
    private val reconnectMillis: Long = 2000,
    private val reconnectRetryMillis: Long = RECONNECT_RETRY_MILLIS,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
    private val calibrationTimers: CalibrationFlow.Scheduler = TIMER_POOL,
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check, or calibration starts */
        const val PATTERN_SETTLE_MILLIS = 500L

        /**
         * How many times a camera just plugged back in is tried before it waits to be plugged in again: its device
         * node can appear a moment before it can be opened (spec §8 Revision 4, decision 4)
         */
        const val RECONNECT_TRIES = 3

        /** How long after a failed try the next one is */
        const val RECONNECT_RETRY_MILLIS = 1000L

        /** Calibration's timers on the app's shared timer pool */
        val TIMER_POOL = CalibrationFlow.Scheduler { task, delay -> TimerPool.schedule(task, delay) ?: CompletableFuture<Void>() }
```

Replace:

```kotlin
     * [openingCamera] names [camera] while it opens on the I/O dispatcher; then [then] hears on the UI thread
     * whether it opened. One camera opens at a time.
     *
     * @return false if the pick was ignored, because another camera is still opening
     */
    fun openCameraInBackground(camera: Camera, then: (Boolean) -> Unit = {}): Boolean {
        if (openingState.value != null) return false
        if (cameraState.value?.camera === camera) {
            then(true)
```

with:

```kotlin
     * [openingCamera] names [camera] while it opens on the I/O dispatcher; then [then] hears on the UI thread
     * whether it opened. One camera opens at a time.
     *
     * @param reportFailure whether a camera that doesn't open is reported to the user; the reconnect's early
     *   tries aren't (see [RECONNECT_TRIES])
     * @return false if the pick was ignored, because another camera is still opening
     */
    fun openCameraInBackground(camera: Camera, reportFailure: Boolean = true, then: (Boolean) -> Unit = {}): Boolean {
        if (openingState.value != null) return false
        if (cameraState.value?.camera === camera) {
            then(true)
```

Replace:

```kotlin
            if (generation != openGeneration.get()) {
                discard(opened)
            } else {
                uiThread(Runnable { then(publish(generation, opened)) })
            }
        }
        return true
```

with:

```kotlin
            if (generation != openGeneration.get()) {
                discard(opened)
            } else {
                uiThread(Runnable { then(publish(generation, opened, reportFailure)) })
            }
        }
        return true
```

Replace:

```kotlin
        return generation to old?.camera
    }

    // An open finished, on the UI thread: shows its camera, or why it couldn't open, unless a newer open (or
    // the app closing) has replaced it
    private fun publish(generation: Int, opened: Opened): Boolean {
        if (generation != openGeneration.get()) {
            if (opened.manager != null) {
                opened.view.live = false
```

with:

```kotlin
        return generation to old?.camera
    }

    // An open finished, on the UI thread: shows its camera, or why it couldn't open (if [reportFailure]), unless a
    // newer open (or the app closing) has replaced it
    private fun publish(generation: Int, opened: Opened, reportFailure: Boolean = true): Boolean {
        if (generation != openGeneration.get()) {
            if (opened.manager != null) {
                opened.view.live = false
```

Replace:

```kotlin
        openingState.value = null
        val manager = opened.manager
        if (manager == null) {
            val error = opened.error
            if (opened.notConnected) {
                cameraProblems.showNotConnected(opened.camera)
```

with:

```kotlin
        openingState.value = null
        val manager = opened.manager
        if (manager == null) {
            if (!reportFailure) return false
            val error = opened.error
            if (opened.notConnected) {
                cameraProblems.showNotConnected(opened.camera)
```

Replace:

```kotlin
    }

    // Looks for the lost camera every [reconnectMillis], in the background, and reopens it once it is plugged
    // back in (spec §8 Revision 2, decision 6). It is tried once each time it appears in the list: a camera
    // listed but refusing to open isn't retried until it is unplugged and plugged in again. The owner's own
    // pick, or any camera opening, ends the watch (publish); so does the app closing.
    //
    // Matched by name: an exact match first, else once a trailing device path (e.g. " /dev/video0") is
    // stripped from both sides, since a re-plugged camera can come back under a different device node
    // (sameCamera; Task 2).
    private fun watchForReturn(name: String) {
        reconnectWatch?.cancel()
        waitingForState.value = name
```

with:

```kotlin
    }

    // Looks for the lost camera every [reconnectMillis], in the background, and reopens it once it is plugged
    // back in (spec §8 Revision 2, decision 6). Each time it appears in the list it is tried up to
    // [RECONNECT_TRIES] times (reopen); a camera listed but still refusing to open then isn't tried again until it
    // is unplugged and plugged in again. The owner's own pick, or any camera opening, ends the watch (publish);
    // so does the app closing.
    private fun watchForReturn(name: String) {
        reconnectWatch?.cancel()
        waitingForState.value = name
```

Replace:

```kotlin
            var listed = false
            while (isActive) {
                delay(reconnectMillis)
                val back = try {
                    cameraSource.cameras().let { found ->
                        found.firstOrNull { it.name == name } ?: found.firstOrNull { sameCamera(it.name, name) }
                    }
                } catch (e: Exception) {
                    logger.warn("Couldn't list the cameras, looking for {}", name, e)
                    null
                }
                if (back != null && !listed) {
                    uiThread(Runnable {
                        // Only while nothing else is open or opening: the owner's pick wins
                        if (waitingForState.value == name && cameraState.value == null && openingState.value == null) {
                            logger.info("{} is plugged in again: reopening it", name)
                            openCameraInBackground(back)
                        }
                    })
                }
                listed = back != null
            }
        }
    }

    private fun stopWatchingForReturn() {
```

with:

```kotlin
            var listed = false
            while (isActive) {
                delay(reconnectMillis)
                val back = findReturned(name)
                // Listing can take seconds: a watch cancelled meanwhile (a pick, the app closing) reopens nothing
                ensureActive()
                listed = when {
                    back == null -> false
                    listed -> true
                    else -> reopen(name, back)
                }
            }
        }
    }

    // The lost camera, if it is listed again. Matched by name: an exact match first, else once a trailing device
    // path (e.g. " /dev/video0") is stripped from both sides, since a re-plugged camera can come back under a
    // different device node (sameCamera)
    private fun findReturned(name: String): Camera? = try {
        cameraSource.cameras().let { found ->
            found.firstOrNull { it.name == name } ?: found.firstOrNull { sameCamera(it.name, name) }
        }
    } catch (e: Exception) {
        logger.warn("Couldn't list the cameras, looking for {}", name, e)
        null
    }

    // Reopens the lost camera, just listed again as [first]: up to RECONNECT_TRIES times, reconnectRetryMillis
    // apart, since its device node can appear a moment before it can be opened (spec §8 Revision 4, decision 4).
    // Only the last failure is reported to the user. It stops once the camera opens, once it is gone from the
    // list, or once something else is open or opening (the owner's pick wins).
    //
    // @return false if it wasn't tried at all (something else was open or opening), so its next listing tries it
    private suspend fun reopen(name: String, first: Camera): Boolean {
        var back = first
        for (attempt in 1..RECONNECT_TRIES) {
            if (attempt > 1) {
                delay(reconnectRetryMillis)
                back = findReturned(name) ?: return true
                currentCoroutineContext().ensureActive()
            }
            val last = attempt == RECONNECT_TRIES
            val camera = back
            val opened = CompletableDeferred<Boolean?>()
            uiThread(Runnable {
                if (waitingForState.value == name && cameraState.value == null && openingState.value == null) {
                    logger.info("{} is plugged in again: reopening it (try {} of {})", name, attempt, RECONNECT_TRIES)
                    if (!openCameraInBackground(camera, reportFailure = last) { opened.complete(it) }) opened.complete(null)
                } else {
                    opened.complete(null)
                }
            })
            when (opened.await()) {
                true -> return true
                null -> return attempt > 1
                false -> if (!last) logger.info("{} didn't open yet; trying again in {} ms", name, reconnectRetryMillis)
            }
        }
        return true
    }

    private fun stopWatchingForReturn() {
```

- [ ] **Step 4: Run the tests**

Run the Step 2 command. Expected: PASS. `TestProblems` runs 25 tests.

- [ ] **Step 5: The gate**

Expected: `793/793 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt core/src/main/java/com/shootoff/calibration/CalibrationFlow.java core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java
git commit -m "Retry a camera that fails to reopen just after it is plugged back in, and give an unattended calibration a minute"
git log -1 --format=%B
```

---

### Task 5: The manual box follows every move of the mouse

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt` (`ManualBox` takes the controller; `dragBox` replaces `dragWithoutSlop`; the `rememberUpdatedState` import goes)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt`

**Interfaces:**
- Consumes: `CalibrationController.state` (its `box`) and `CalibrationController.moveBox(Rect)`, which clamps to the canvas; `SurfaceTransform.scale`.
- Produces: nothing new outside the file (`dragBox` is private).

**The cause** is set out under "Root causes, item 6" above.
- The first two tests turn off the test clock's auto-advance (`compose.mainClock.autoAdvance = false`) and send ten moves with no delay, so nothing recomposes between them, as with a fast mouse.
- Against the old overlay they fail with `expected:<Rect[minX=75.0, minY=75.0, width=160.0, height=155.0]> but was:<Rect[minX=75.0, minY=75.0, width=151.0, height=150.5]>` (the corner) and `expected:<Rect[minX=85.0, minY=80.0, …]> but was:<Rect[minX=76.0, minY=75.5, …]>` (the box).
- The third test fails with `but was:<Rect[minX=100.0, …]>`: the old deltas drift once the box has been held at the edge.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt`:

Replace:

```kotlin
        compose.onNodeWithTag("calibration-done").performClick()
        assertEquals(Rect(80.0, 77.5, 160.0, 155.0), fixture.arena.projection.value)
    }
}
```

with:

```kotlin
        compose.onNodeWithTag("calibration-done").performClick()
        assertEquals(Rect(80.0, 77.5, 160.0, 155.0), fixture.arena.projection.value)
    }

    // The owner's box (Plan 8's hardware check): the handles moved about a tenth as far as the mouse. A real mouse
    // sends many small moves between two frames, and every one of them must count
    @Test
    fun aCornerFollowsEveryMoveOfTheMouseNotOnlyTheLastBeforeTheNextFrame() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        // Nothing is drawn (so nothing recomposes) between the moves, as with a fast mouse
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("calibration-corner-bottom-right").performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(2f, 1f), delayMillis = 0) }
            release()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // 20 x 10 view pixels are 10 x 5 canvas pixels
        assertEquals(Rect(75.0, 75.0, 160.0, 155.0), controller.state.value.box)
    }

    @Test
    fun theBoxFollowsEveryMoveOfTheMouseToo() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("calibration-box").performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(2f, 1f), delayMillis = 0) }
            release()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(Rect(85.0, 80.0, 150.0, 150.0), controller.state.value.box)
    }

    // Held at the canvas's edge while the pointer goes on, the box is back under the pointer when it returns
    @Test
    fun theBoxStaysUnderThePointerAfterBeingHeldAtTheCanvasEdge() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        compose.onNodeWithTag("calibration-box").performMouseInput {
            moveTo(center)
            press()
            // 100 canvas pixels left: the box stops at the edge, 75 canvas pixels along
            moveBy(Offset(-200f, 0f))
            moveBy(Offset(200f, 0f))
            release()
        }
        compose.waitForIdle()

        assertEquals(Rect(75.0, 75.0, 150.0, 150.0), controller.state.value.box)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.calibration.TestCalibrationOverlay --console=plain`

Expected: FAIL, 3 of 6, with the messages above.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt`:

Replace:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
```

with:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
```

Replace:

```kotlin
fun CalibrationOverlay(controller: CalibrationController, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    Box(modifier.fillMaxSize()) {
        state.box?.let { ManualBox(it, transform, controller::moveBox) }

        val message = state.message
        if (state.calibrating && message != null) {
```

with:

```kotlin
fun CalibrationOverlay(controller: CalibrationController, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    Box(modifier.fillMaxSize()) {
        state.box?.let { ManualBox(it, transform, controller) }

        val message = state.message
        if (state.calibrating && message != null) {
```

Replace:

```kotlin
}

@Composable
private fun ManualBox(box: Rect, transform: SurfaceTransform, onMove: (Rect) -> Unit) {
    val colors = Range.colors
    val current by rememberUpdatedState(box)
    val topLeft = transform.toView(box.minX, box.minY)
    val density = LocalDensity.current
    val width = with(density) { (box.width * transform.scale).toFloat().toDp() }
```

with:

```kotlin
}

@Composable
private fun ManualBox(box: Rect, transform: SurfaceTransform, controller: CalibrationController) {
    val colors = Range.colors
    val topLeft = transform.toView(box.minX, box.minY)
    val density = LocalDensity.current
    val width = with(density) { (box.width * transform.scale).toFloat().toDp() }
```

Replace:

```kotlin
            .border(2.dp, colors.accent)
            .testTag("calibration-box")
            .pointerInput(transform) {
                dragWithoutSlop { drag ->
                    val b = current
                    onMove(Rect(b.minX + drag.x / transform.scale, b.minY + drag.y / transform.scale, b.width, b.height))
                }
            },
    ) {
        // Each corner resizes the box from that corner
```

with:

```kotlin
            .border(2.dp, colors.accent)
            .testTag("calibration-box")
            .pointerInput(transform) {
                dragBox(controller, transform) { start, dx, dy -> Rect(start.minX + dx, start.minY + dy, start.width, start.height) }
            },
    ) {
        // Each corner resizes the box from that corner
```

Replace:

```kotlin
                    .background(colors.accent)
                    .testTag("calibration-corner-${corner.name}")
                    .pointerInput(transform) {
                        dragWithoutSlop { drag ->
                            onMove(corner.resize(current, (drag.x / transform.scale).toDouble(), (drag.y / transform.scale).toDouble()))
                        }
                    },
            )
        }
    }
}

// The box follows the pointer from the first pixel: a slop would leave it behind the pointer
private suspend fun PointerInputScope.dragWithoutSlop(onDrag: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        drag(down.id) { change ->
            onDrag(change.positionChange())
            change.consume()
        }
    }
}
```

with:

```kotlin
                    .background(colors.accent)
                    .testTag("calibration-corner-${corner.name}")
                    .pointerInput(transform) {
                        dragBox(controller, transform) { start, dx, dy -> corner.resize(start, dx, dy) }
                    },
            )
        }
    }
}

// Moves or resizes the box with the pointer. Each move places it from where it was as the drag began, by the
// pointer's whole movement since (in canvas pixels): a fast mouse sends several moves between two frames, and
// working from the box as last drawn kept only the last of them, so the box moved a fraction of the mouse's
// movement (the owner's box, Plan 8's hardware check). The box also follows from the first pixel: a slop would
// leave it behind the pointer.
private suspend fun PointerInputScope.dragBox(
    controller: CalibrationController,
    transform: SurfaceTransform,
    place: (start: Rect, dx: Double, dy: Double) -> Rect,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val start = controller.state.value.box ?: return@awaitEachGesture
        var moved = Offset.Zero
        drag(down.id) { change ->
            moved += change.positionChange()
            change.consume()
            controller.moveBox(place(start, (moved.x / transform.scale).toDouble(), (moved.y / transform.scale).toDouble()))
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run the Step 2 command. Expected: PASS, 6 of 6.

- [ ] **Step 5: The gate**

Expected: `796/796 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt
git commit -m "The manual box follows every move of the mouse"
git log -1 --format=%B
```

---

### Task 6: The not-found words, and an accurate calibration log

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt` (`CheckState.NotFound`'s text)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt` (new `notReadyCalibrateDetail`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt` (`NotReadyPrompt` uses it)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt` (new `CalibrationViews.calibrationCancelled`; `cancel` calls it)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`publish`'s reason, `calibrateOnTheProjector`'s not-found, `startCalibration`, new `calibrationCancelled`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationLogging.kt`

**Interfaces:**
- Consumes:
  - Task 4's `publish`, which the reason is computed in, before `stopWatchingForReturn()` clears `waitingFor`.
  - Plan 8's `calibrationStartedAt`, `arenaFilledAt` and `TestCalibrationLogging`'s `messages()`.
- Produces:
  - `fun notReadyCalibrateDetail(arenaOpen: Boolean, calibrating: Boolean, calibrated: Boolean, calibratedAt: LocalTime?, check: CheckState): String`.
  - `fun CalibrationViews.calibrationCancelled() {}`, a default no-op that `AppState` overrides.
  - `CheckState.NotFound.text()` = "The pattern wasn't found: not calibrated".

**The causes:**
- The wording (`RememberedCalibration.kt:73`) was shared by Setup, the chip and the card. On the card it followed "Calibrate — " (`RangeControls.kt:200`), so it read "Calibrate — The pattern wasn't found: not calibrated — calibrate on Setup".
- The reason: `publish` passed "the camera came back" for any camera opening to an open, uncalibrated arena (`AppState.kt:543`), including the launch camera that merely opened after the arena.
- The stale time: `arenaFilledAt` is set whenever the arena reaches the projector (`:759`) and was cleared only on success (`:956`).
- Cancel wasn't logged at all, which is what made the 21:40 replug ambiguous (ruling 14).

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationLogging.kt`:

Replace:

```kotlin
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
```

with:

```kotlin
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
```

Replace:

```kotlin
        assertTrue(messages().contains("The pattern wasn't found in $expectedSeconds s: calibration ended"))
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

with:

```kotlin
        assertTrue(messages().contains("The pattern wasn't found in $expectedSeconds s: calibration ended"))
    }

    // The owner's launch (Plan 8's hardware check): the camera opened a moment after the arena, and the log said
    // "the camera came back"
    @Test
    fun aCameraOpeningAfterTheArenaIsLoggedAsOpeningNotComingBack() {
        app.setRememberCalibration(true)
        app.openArena()
        AppFixture.putOnTheProjector(app)

        app.openStartCamera()

        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        assertTrue(messages().contains("Calibrating automatically: the camera opened"))
    }

    @Test
    fun theLostCameraReopeningIsLoggedAsComingBack() {
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertTrue(app.openCamera(AppFixture.TestCamera()))

        awaitTrue { messages().contains("Calibrating automatically: the camera came back") }
    }

    // Plan 8's final review: after an automatic calibration timed out, a Calibrate the owner pressed logged the
    // automatic one's stale "ms since the pattern first showed"
    @Test
    fun aCalibrationTheOwnerStartsAfterAnAutomaticOneTimedOutLogsNoStalePatternTime() {
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        app.close()
        app = AppFixture.appWithCamera(calibrationTimers = { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
        })
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { timers.any { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED } }
        timers.filter { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED }.forEach { it.second.run() }

        app.startCalibration()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertFalse(success.contains("since the pattern first showed"), success)
    }

    // So the log tells a calibration the owner cancelled from one that ended by itself (Plan 8's replug: a success
    // "1222 ms since it started" after 30 s of searching reads as a Cancel and a fresh Calibrate)
    @Test
    fun cancellingACalibrationIsLoggedWhicheverCancelIsPressed() {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.startCalibration()

        // The feed's own Cancel goes straight to the controller
        app.calibration.value!!.cancel()

        assertTrue(messages().any { it.startsWith("Calibration cancelled") }, messages().toString())
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt`:

Replace:

```kotlin
            calibrationSummary(true, true, false, false, null, CheckState.WaitingToCalibrate),
        )
        assertEquals(
            "The pattern wasn't found: not calibrated — calibrate on Setup",
            calibrationSummary(true, true, false, false, null, CheckState.NotFound),
        )
        assertEquals("The projection moved about 14 px — recalibrate", calibrationSummary(true, true, false, false, null, CheckState.Moved(14)))
```

with:

```kotlin
            calibrationSummary(true, true, false, false, null, CheckState.WaitingToCalibrate),
        )
        assertEquals(
            "The pattern wasn't found: not calibrated",
            calibrationSummary(true, true, false, false, null, CheckState.NotFound),
        )
        assertEquals("The projection moved about 14 px — recalibrate", calibrationSummary(true, true, false, false, null, CheckState.Moved(14)))
```

Replace:

```kotlin
        assertEquals("✓ Calibrated 01:12", calibrationSummary(true, true, false, true, LocalTime.of(1, 12, 40), idle))
        assertEquals("Not calibrated", calibrationSummary(true, true, false, false, null, idle))
    }
}
```

with:

```kotlin
        assertEquals("✓ Calibrated 01:12", calibrationSummary(true, true, false, true, LocalTime.of(1, 12, 40), idle))
        assertEquals("Not calibrated", calibrationSummary(true, true, false, false, null, idle))
    }

    // The owner's wording (Plan 8's hardware check): the card read "Calibrate — The pattern wasn't found: not
    // calibrated — calibrate on Setup". The card's own button is Set up, so it says to press that
    @Test
    fun theNotReadyCardSaysToPressSetUpWhenThePatternWasntFound() {
        assertEquals(
            "the pattern wasn't found. Press Set up to calibrate.",
            notReadyCalibrateDetail(true, false, false, null, CheckState.NotFound),
        )
        // Otherwise it says what Setup and the chip say
        assertEquals("Not calibrated", notReadyCalibrateDetail(true, false, false, null, CheckState.Idle))
        assertEquals("No arena", notReadyCalibrateDetail(false, false, false, null, CheckState.Idle))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestSetupSteps --tests com.shootoff.compose.app.TestCalibrationLogging --console=plain`

Expected: FAIL to compile, `Unresolved reference 'notReadyCalibrateDetail'`.

Without that test, `theSummarySays…` fails on the old text, and three of the four new logging tests fail:
- `aCameraOpeningAfterTheArena…`: the message says "came back".
- `…NoStalePatternTime`: the success line still has "since the pattern first showed".
- `cancellingACalibrationIsLogged…`: there is no "Calibration cancelled" line.

`theLostCameraReopeningIsLoggedAsComingBack` passes before and after: it guards the reconnect's wording.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
            }
            return false
        }
        // A camera is open, whichever: the lost one needn't be watched for any more
        stopWatchingForReturn()
        cameras.addStartedCameraManager(manager)
```

with:

```kotlin
            }
            return false
        }
        // The lost camera itself, reopened (by the reconnect, or picked again), rather than one opening afresh
        val cameBack = waitingForState.value?.let { sameCamera(it, manager.camera.name) } == true
        // A camera is open, whichever: the lost one needn't be watched for any more
        stopWatchingForReturn()
        cameras.addStartedCameraManager(manager)
```

Replace:

```kotlin
            // An uncalibrated arena is calibrated (or its remembered box checked) now that there is a camera
            // (spec §8 Revision 3); without the option, Setup's Calibrate step is next
            if (settings.rememberCalibration() && arena.projection.value == null && !checkState.value.showsPattern) {
                calibrateOrCheck(arena, "the camera came back")
            }
        }
        return true
```

with:

```kotlin
            // An uncalibrated arena is calibrated (or its remembered box checked) now that there is a camera
            // (spec §8 Revision 3); without the option, Setup's Calibrate step is next
            if (settings.rememberCalibration() && arena.projection.value == null && !checkState.value.showsPattern) {
                calibrateOrCheck(arena, if (cameBack) "the camera came back" else "the camera opened")
            }
        }
        return true
```

Replace:

```kotlin
            calibrationState.value?.startUnattended {
                logger.info("The pattern wasn't found in {} s: calibration ended", CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED / 1000)
                checkState.value = CheckState.NotFound
            }
        }
    }
```

with:

```kotlin
            calibrationState.value?.startUnattended {
                logger.info("The pattern wasn't found in {} s: calibration ended", CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED / 1000)
                checkState.value = CheckState.NotFound
                calibrationStartedAt = null
                arenaFilledAt = null
            }
        }
    }
```

Replace:

```kotlin
            logger.info("The owner is attending the automatic calibration")
        } else {
            calibrationStartedAt = checkClock()
        }
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
```

with:

```kotlin
            logger.info("The owner is attending the automatic calibration")
        } else {
            calibrationStartedAt = checkClock()
            // When the arena reached the projector says nothing about a calibration the owner starts later
            arenaFilledAt = null
        }
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
```

Replace:

```kotlin
    /** Cancel: calibration ends, leaving the arena and the camera as they were before it started. */
    fun cancelCalibration() {
        calibrationState.value?.cancel()
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean) {
```

with:

```kotlin
    /** Cancel: calibration ends, leaving the arena and the camera as they were before it started. */
    fun cancelCalibration() {
        calibrationState.value?.cancel()
    }

    override fun calibrationCancelled() {
        logger.info("Calibration cancelled, {} ms after it started", calibrationStartedAt?.let { checkClock() - it })
        calibrationStartedAt = null
        arenaFilledAt = null
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean) {
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt`:

Replace:

```kotlin
                val detail = when (step) {
                    Step.CAMERA -> ""
                    Step.PROJECTOR -> if (arena == null) " — not open" else ""
                    Step.CALIBRATE -> if (state == StepState.DONE) "" else " — " + calibrationSummary(true, arena != null, calibrating, calibrated, calibratedAt, check)
                }
                Text(
                    (if (state == StepState.DONE) "✓ " else "•  ") + step.label + detail,
```

with:

```kotlin
                val detail = when (step) {
                    Step.CAMERA -> ""
                    Step.PROJECTOR -> if (arena == null) " — not open" else ""
                    Step.CALIBRATE -> if (state == StepState.DONE) "" else " — " + notReadyCalibrateDetail(arena != null, calibrating, calibrated, calibratedAt, check)
                }
                Text(
                    (if (state == StepState.DONE) "✓ " else "•  ") + step.label + detail,
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt`:

Replace:

```kotlin
        else -> "Not calibrated"
    }
}
```

with:

```kotlin
        else -> "Not calibrated"
    }
}

/**
 * What Range's not-ready card says after its "Calibrate —" step (it shows only with a camera): the summary, except
 * that a pattern not found says to press the card's own Set up button.
 */
fun notReadyCalibrateDetail(arenaOpen: Boolean, calibrating: Boolean, calibrated: Boolean, calibratedAt: LocalTime?, check: CheckState): String =
    if (arenaOpen && !calibrating && check == CheckState.NotFound) {
        "the pattern wasn't found. Press Set up to calibrate."
    } else {
        calibrationSummary(true, arenaOpen, calibrating, calibrated, calibratedAt, check)
    }
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
     * @param byCamera true if the camera found the pattern; false for the manual box
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean)
}

/**
```

with:

```kotlin
     * @param byCamera true if the camera found the pattern; false for the manual box
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>, byCamera: Boolean)

    /** The owner cancelled a calibration (Setup's Cancel, or the feed's), which left everything as it was. */
    fun calibrationCancelled() {}
}

/**
```

Replace:

```kotlin
        if (!flow.isCalibrating) return
        flow.cancel()
        putBack()
    }

    // Calibration ended without calibrating (Cancel, or an unattended one that didn't find the pattern): the
```

with:

```kotlin
        if (!flow.isCalibrating) return
        flow.cancel()
        putBack()
        views.calibrationCancelled()
    }

    // Calibration ended without calibrating (Cancel, or an unattended one that didn't find the pattern): the
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`:

Replace:

```kotlin
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.WaitingToCalibrate -> "Calibrating once the arena is on the projector…"
    CheckState.NotFound -> "The pattern wasn't found: not calibrated — calibrate on Setup"
    CheckState.Checking -> "Checking the saved calibration…"
    is CheckState.Moved -> "The projection moved about $pixels px — recalibrate"
    is CheckState.NotVerified -> when (reason) {
```

with:

```kotlin
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.WaitingToCalibrate -> "Calibrating once the arena is on the projector…"
    CheckState.NotFound -> "The pattern wasn't found: not calibrated"
    CheckState.Checking -> "Checking the saved calibration…"
    is CheckState.Moved -> "The projection moved about $pixels px — recalibrate"
    is CheckState.NotVerified -> when (reason) {
```

- [ ] **Step 4: Run the tests**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: The gate**

Expected: `801/801 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationLogging.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt
git commit -m "Reword the pattern-not-found texts and make the calibration log accurate"
git log -1 --format=%B
```

---

### Task 7: The owner's short hardware re-check

Runs in ShootOFF on `compose-ui`. **Files:** none. It covers Revision 4's success criteria 15–18, and the manual box and the card's wording.
- If a check fails, stop and debug with superpowers:systematic-debugging before changing code.
- The fix belongs in the task that owns the code, as a new commit with a test that reproduces it.
- The hardware: the Logitech C270 (640×480) and the 1280×720 projector at x=4480. "Calibrate automatically when the arena opens" stays on throughout.

- [ ] **Step 1: The gate and the boundary**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan8-owner-files.sha256
./gradlew :compose-app:dependencies --configuration runtimeClasspath --console=plain | command grep -c openjfx
git log -7 --format=%B | command grep -ci "co-authored-by"
```

Expected:
- `801/801 passing; 0 regressions; 0 new failures`.
- `OK` for the three owner files. `shootoff.properties` may say `FAILED`, since both apps save to it.
- `0`.
- `0`.

- [ ] **Step 2: Launch the Compose app**

With the webcam and the projector attached (the projector on), run in the background:

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :compose-app:run --console=plain > build/plan9-compose-run.log 2>&1
```

Relaunch the same way for each item below, appending with `>> build/plan9-compose-run.log 2>&1`.

- [ ] **Step 3: The owner checks, on the hardware**

1. **Cancel over a good calibration** (criterion 15).
   - Let the arena calibrate itself at launch. On Setup, Show grid: the grid sits on the orange outline.
   - Turn Show grid off, press Recalibrate, and press Cancel at once. Show grid again: still lined up.
   - Repeat with Cancel pressed while the pattern shows, then on the manual box: cover the lens so the box comes after 12 s, then uncover.
   - Each time the grid is still lined up, and the log has a "Calibration cancelled, N ms after it started" line.
2. **The exposure step** (criterion 16). After a few calibrations:

   `command grep -nE "white screen|Exposure lowered|Failed to adjust exposure" build/plan9-compose-run.log`

   - Expected: "The white screen reached the camera after N ms: mean ~130 …", then "Exposure lowered to … mean from ~130".
   - Note N, the white screen's real delay.
   - A "didn't reach the camera in 2000 ms" line, or a "Failed" from a baseline under about 100, is a failure to report.
3. **The frame rate with the lens covered** (criterion 17). Cover the projector's lens and relaunch.
   - The log has "… 3 s after opening: N FPS, exposure …". Once the search starts it has "… auto exposure was E, longer than a frame: held at 333.0 by hand while looking for the pattern".
   - No "Current webcam FPS is … too low" line appears during the search. If one does, the INFO line after it shows the exposure and mode then.
   - After 60 s: "The pattern wasn't found in 60 s: calibration ended", and no box.
   - Uncover and press Calibrate: it calibrates.
4. **A replug** (criterion 18). With the arena calibrated, unplug the camera, wait a few seconds, plug it back in. Do this three or four times.
   - Each time the camera reopens by itself, with "(try 1 of 3)", and "(try 2 of 3)" if the first try failed, and the arena recalibrates.
   - Note each "Calibration succeeded … ms since it started" time. Expected: a few seconds, well under 60.
   - Any "exposure_dynamic_framerate was 1, set to 0" line after a "switched to manual exposure" or "restored auto exposure mode" line means the C270 turns the control back on as its mode changes. Note it for the owner.
5. **The manual box's handles.** Cover the lens, press Calibrate, and wait 12 s for the box.
   - Drag each corner and the box itself, quickly and slowly: the handle stays under the pointer.
   - Drag the box against an edge and back: it comes back under the pointer.
   - Press Done. Uncover and press Calibrate once, to return to a camera calibration.
6. **The card's words.** Cover the lens and relaunch; stay on Range for the minute.
   - Range's card reads "•  Calibrate — the pattern wasn't found. Press Set up to calibrate."
   - The chip and Setup's Calibrate step read "The pattern wasn't found: not calibrated".
   - Uncover.
7. **The JavaFX app** (it shares `core`). `./gradlew run --console=plain > build/plan9-javafx-run.log 2>&1`, then calibrate once on the projector the JavaFX way.
   - It calibrates.
   - `command grep -nE "white screen|Exposure lowered|Failed to adjust" build/plan9-javafx-run.log` shows the step as in item 2.

- [ ] **Step 4: Check the log**

```bash
cd /home/bfears/projects/ShootOFF
command grep -nE "Exception|Error" build/plan9-compose-run.log
command grep -nE "Calibrating automatically|Calibration succeeded|Calibration cancelled|wasn't found|reopening it|didn't open yet|FPS" build/plan9-compose-run.log
```

Expected:
- The first prints nothing, except lines from item 4's deliberate unplugs (the camera's "can no longer communicate" report and OpenCV's `VIDIOC_REQBUFS`).
- The second tells the whole story of items 1, 3 and 4 in order. "Calibrating automatically: the camera opened" appears for a camera that opens after the arena at launch. "…: the camera came back" appears only after an unplug.

- [ ] **Step 5: Report**

Report to the owner:
- which items passed, and any that failed with their fix commits;
- the white screen's delay and the exposure step's outcomes (item 2);
- the frame rate and exposure with the lens covered (item 3);
- the replug times, and whether the C270 turned `exposure_dynamic_framerate` back on (item 4);
- the open decisions from "For the owner to decide".

---

## Spec coverage

| Spec §8 Revision 4 | Where |
|---|---|
| 1. Cancel puts the camera back exactly as it was (the projection, the perspective warp and paper size, the exposure), on Cancel and on an unattended not-found | Task 1 (rulings 1–3; `TestCameraManagerCalibration`, `TestCalibrationOnRequest.…WholeCalibrationBack`); Task 7 item 1 |
| 2. The exposure step waits for its white screen, at most 2 s, in `core`; a found pattern gets 5 s more | Task 2 (rulings 4, 5; `TestStepAdjustExposure`, `TestCalibrationFlow.aPatternFound…`); Task 7 items 2, 7 |
| 3. The frame rate stays up while looking: the exposure held to a frame on V4L2; `exposure_dynamic_framerate` re-asserted; the frame rate and exposure logged after each open and with the warning | Task 3 (rulings 6–8; `TestPatternSearchExposure`, `TestSarxosCaptureCamera`, `TestCameraManagerDiagnostics`); Task 7 items 3, 4 |
| 4. A just-appeared camera is tried three times, a second apart; only the last failure is shown; no error every two seconds | Task 4 (ruling 9; `TestProblems`); Task 7 item 4 |
| 5. An unattended calibration waits 60 s; one limit | Task 4 (ruling 10; `TestCalibrationFlow.anUnattendedCalibrationLooksForAMinute`); Task 7 items 3, 4 |
| Also: the manual box's handles | Task 5 (ruling 11); Task 7 item 5 |
| Also: the not-ready card's wording | Task 6 (ruling 12); Task 7 item 6 |
| Also: the stale pattern time, the automatic calibration's reason | Task 6 (ruling 13); Task 7 Step 4 |
| Success criteria 15–18 | Task 7 items 1–4 |
| Delivery: Plan 9 on `compose-ui`, then the owner's short hardware check of each item | this plan; Task 7 |
