# Compose Trial Plan 7: Hardware Check Feedback — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry out the owner's decisions from the Plan 6 hardware check (spec §8, Revision 2): stay on Setup after calibrating, pause the drill for calibration instead of restarting it, a remembered calibration that never reports a false "moved", the arena at launch, only real cameras listed, a lost camera that reconnects and keeps the arena open, and no camera pick that can crash the app, leaving the JavaFX app working as before.

**Architecture:**
- **Task 1: `core`, the camera list.** `CameraFactory` leaves out V4L2 nodes that can't capture video (the C270's metadata node), and `SarxosCaptureCamera` keeps its name from when it was found instead of looking it up by index.
- **Task 2: a stale pick and the safety net.** Picking a camera that is no longer plugged in says it is not connected (`CameraSource.current` finds it by name), and `UiErrors` keeps an exception in a window's event handling or composition from closing the app.
- **Task 3: stay on Setup** after calibrating, with "Calibration complete ✓ HH:mm" on the Calibrate step.
- **Task 4: calibration pauses the drill** through its own Pause button; the arena is *covered* (not its targets hidden) while a pattern shows, and a paused drill keeps shot detection off afterwards. No change to the exercise API.
- **Task 5: losing (or switching) the camera keeps the arena open**, uncalibrated, and pauses the drill; a camera coming back is checked against the remembered calibration.
- **Task 6: a lost camera reopens by itself** when it is plugged back in.
- **Task 7: `core`, the measurement.** `PatternMeasurement` takes the median of five detections; `CalibrationCheck` compares it with the saved bounds against a tolerance that scales with the pattern (kept, kept at the fresh measurement, or moved).
- **Task 8: the remembered calibration in the app:** the pattern measured again after an auto-calibration and that median saved; the check and the measurement start only once the arena fills the projector and has settled; `ArenaWindow` reports full screen only once it fills its screen; detection stays off while any pattern shows; INFO logging.
- **Task 9: the arena opens at launch** when a projector screen is found; Setup looks for a projector again while the arena is closed.
- **Task 10: the owner's hardware re-check**, which also carries Plan 6's Task 9 items that were not reached, a JavaFX regression check and a 20-minute FPS and memory watch.

**Tech Stack:**
- Java 21 (`core`, `plugin-api`, `javafx-app`) and Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- Compose Multiplatform 1.12.1 (desktop, Linux x64), Material 3 1.9.0, kotlinx.coroutines (from Compose).
- OpenCV (JavaCV) for pattern detection and JNA for V4L2 ioctls, both already in `core`.
- JUnit 5 for unit tests; Compose UI tests on JUnit 4 (`v2.createComposeRule`) through the vintage engine.

**Spec:** `docs/superpowers/specs/2026-09-26-compose-trial-design.md`. This plan implements §8 "Revision 2 (2026-09-27): after the hardware check", which supersedes the conflicting parts of Revision 1 (§8 above it) and of §4. Where the spec and this plan disagree, the spec wins. Plan 6's ledger (`.superpowers/sdd/2026-09-27-compose-trial-plan-6-setup-and-training/progress.md`, gitignored) has the hardware check's findings behind each decision.

Plan 6 is done, with its review fixes: the gate stands at **685**.

**Prototype.** The whole plan was built in a scratch clone of `compose-ui` at `c6d306c4` (the spec commit), one commit per task. Every task compiled, and the gate ran green at each task's commit with the counts given below, ending at **723/723**. The code blocks below are the prototype's files and edits, extracted from those commits. Each task's failing-test step was checked against the commit before it. The GUI apps were not run; Task 10 is the owner's.

## Plan-author rulings

Where Revision 2 leaves room, or the code disagrees with it, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong. Rulings 2, 7 and 8 are also listed for the owner at the end of this section.

1. **The confirmation is for calibrations the owner asked for.**
   - `AppState.calibrationComplete` holds the time a calibration started on Setup or with F6 completed; Setup's Calibrate step shows "Calibration complete ✓ HH:mm" while it is set. It is cleared when the next calibration starts, the arena closes, or the camera goes.
   - A remembered calibration the check kept doesn't set it: the status line already says "✓ Calibrated HH:mm", and nothing was calibrated.

   *Cost if wrong:* none known.
2. **Pausing is pressing the drill's own Pause button** (decision 2: "find the smallest correct way").
   - `AppState.pauseDrill()` presses the button labeled "Pause", as F3 does, unless one labeled "Resume" shows (the drill is already paused). Calibration's `CalibrationFlow.Exercises` in the Compose app is AppState's `drillForCalibration`: a projector drill is paused and nothing is restarted afterwards (it returns `Optional.empty()`).
   - A projector drill with no Pause button is stopped and started afresh after a success, as before (`ExerciseRunner.stopProjectorExercise`). A camera (feed) drill is left alone, as before: the flow only ever stopped projector exercises.
   - **No exercise API change.** The owner's RandomTargetParDrill v2 jar works as it is. The only `plugin-api` edit is a getter on the host side, `ExerciseHostSupport.isShotDetectionPaused()` (ruling 4), which plugins never see.
   - The press runs on the drill's own thread, a moment after calibration starts; the arena is covered by then (ruling 3), so whatever the drill does while pausing (the par drill hides its round's target) happens under the cover.

   *Cost:* the par drill's Pause, once all its rounds are done (the summary), sounds the buzzer instead of pausing; Calibrate or F6 at the summary buzzes once. A drill whose pause button has another label isn't paused.
3. **The arena is covered, not emptied, while a pattern shows.**
   - `ArenaModel.cover(Boolean)`: while covered, `ArenaCanvas` draws only the background (the pattern): no targets, shot markers, "Needs Calibration" label or exercise overlay. Nothing's own state changes, so a target the paused drill hid meanwhile stays hidden afterwards.
   - Calibration covers the arena in place of `setTargetsVisible(false)` and uncovers it in place of `setTargetsVisible(true)`, which would have shown a target the paused drill had hidden. The check and the measurement (`PatternRun`) cover it too, which folds in Plan 6's deferred "pattern under the Needs Calibration label".

   *Cost:* none known. `ArenaModel.setTargetsVisible` stays (its test uses it) but the app no longer calls it.
4. **Shot detection after a pattern.** Calibration, the check and the measurement turn detection back on 600 ms after their pattern goes. Through `AppState.CalibratingCamera` that is skipped while the running drill has detection paused (`ComposeExerciseHost.shotDetectionPaused`, from `ExerciseHostSupport.isShotDetectionPaused()`) or while another pattern run is showing (Plan 6's deferred N1).

   *Cost:* none known.
5. **Measuring** (decision 3).
   - `PatternMeasurement` (`core`): five detections, the median of each edge on its own; at the time limit three are enough, fewer are "not seen". The time limit is 8 s from when the pattern shows. Every detection and every result is logged at INFO.
   - `CalibrationCheck` now measures that way and judges with `CalibrationCheck.judge`: within the tolerance (`max(8 px, 2%)` of the saved pattern's larger side), `Kept` as saved; up to twice the tolerance, `Kept` at the fresh median (`Kept.bounds()`), which the app applies and saves in place of the old one; beyond that, `Moved`.
   - The owner's two calibrations of an unmoved setup (95,59,450,262 and 93,56,442,262) are 10 px apart on the right edge: past the 9 px tolerance, within twice it, so kept (`TestCalibrationCheck.theOwnersTwoCalibrationsOfAnUnmovedSetupAreNotAMove`).

   *Cost:* a real move of between one and two tolerances (9 to 18 px on the owner's setup) is taken as a small drift and followed rather than reported.
6. **When the pattern shows for a check or a measurement.** Only once the arena is full screen, its size equals the projector screen's (within 1 px), and 500 ms have passed (`AppState.PATTERN_SETTLE_MILLIS`). Leaving the projector stops the run quietly and a return starts a fresh one, as Plan 6's full-screen watch did.
   - Separately, `ArenaWindow` now reports full screen only once the window fills the screen it is on (`fillsItsScreen`), not as soon as full screen is asked for. That also stops auto-calibration looking for the pattern in the 640×480 window before the window manager has resized it.

   *Cost:* an arena put full screen on another monitor (dragged there, then F11) isn't checked (it isn't the projector); Setup shows "Checking…" with Cancel until it is back.
7. **What is saved after a calibration** (decision 3: "saving the median at calibration time").
   - After an auto-calibration with Remember on, the calibration's own bounds are saved at once (as before); then the pattern shows again, on Setup ("Measuring the calibration for next time…", with Cancel), and the median replaces them. Cancelled or not seen: the calibration's own bounds stay.
   - This session keeps the projection auto-calibration found; only what is remembered for next time is the median. Next session applies the median.
   - A manual-box calibration is saved as the box, without measuring (Plan 6's ruling 6 stands). Turning Remember on after calibrating saves that calibration's own bounds without measuring; the next check keeps it or follows a small drift (ruling 5).

   *Cost:* the pattern shows a second time, for about two or three seconds, after every auto-calibration with Remember on.
8. **Losing or switching the camera** (decision 7).
   - `AppState.detachCamera()`: the drill pauses (ruling 2), a check or measurement stops quietly, calibration ends (`CalibrationController.arenaClosing()`, which clears both projections), and the arena shows "Needs Calibration". The arena stays open.
   - Switching cameras (a pick while one is open) does the same: one rule for the camera going, whatever the reason. Plan 5's `switchingCamerasClosesTheArena…` test becomes `switchingCamerasKeepsTheArenaOpen…`.
   - When a camera opens with the arena open and uncalibrated, and Remember is on with a saved calibration, it is checked (any camera: another one is reported as "made with another camera"). Plan 6 re-checked only after "no camera to check with".

   *Cost:* switching from a working camera to another drops the calibration, as it always did; the arena now stays.
9. **Reconnecting** (decision 6). While a lost camera is awaited (`AppState.waitingFor`), the cameras are listed every 2 s on the I/O dispatcher and the first with the lost one's name is opened. It is tried once each time it appears in the list: a camera that is listed but won't open (another program has it) isn't retried until it has been unplugged and plugged in again. Any camera opening, the owner's pick included, ends the watch; so does closing the app. The no-camera panel says "Waiting for … to be plugged back in…" above the picker.

   *Cost:* a camera that stopped answering without leaving the list is retried once, then waits for the owner's pick.
10. **A stale pick** (decision 8). `CameraSource.current(camera)` finds the camera plugged in now by name (the system source lists the cameras; the default returns the camera itself). None: "HD Webcam C270 is not connected. Plug it in, or pick another camera.", and nothing is opened. Found: that one is opened, so a camera plugged back in under another device number opens at its new index.

    *Cost:* two cameras with the same name can't be told apart (as the saved settings can't already).
11. **The safety net** (decision 8). `UiErrors` is Compose's `WindowExceptionHandlerFactory`, provided to both windows in `Main`. It logs the exception at ERROR and adds a notice ("Something went wrong: IndexOutOfBoundsException", "…. ShootOFF kept running; the details are in the log."), then returns, where Compose's default shows a dialog and closes the window (for the main window, the app).

    *Cost:* after an exception during composition, that window's content may not redraw properly until it is closed and reopened; the app, the camera and the arena keep running.
12. **The camera list** (decision 5). `V4l2Controls.capturesVideo` asks the node with `VIDIOC_QUERYCAP` and reads `device_caps` (or `capabilities` on old drivers); `CameraFactory.getWebcams()` leaves out a Linux node that answers "no video capture", keeping the index count so the others still open as `/dev/videoN`. A node that can't be asked (missing, not V4L2, no libc) is still listed. The JavaFX app shares `CameraFactory`, so its camera list gains the fix too.

    *Cost:* none known.
13. **The arena at launch** (decision 4). `AppState.launch()` opens the arena on the UI thread, before the camera finishes opening, if `projectorScreenFound()`; the check waits for the camera (Plan 6's retry on `publish`) and for the arena to fill the projector (ruling 6). Setup's Projector step looks for a projector every 2 s while the arena is closed (`PROJECTOR_LOOK_MILLIS`), which folds in Plan 6's deferred stale "No projector screen found".

    *Cost:* none known.
14. **Logging.** `compose-app`'s `logback.xml` gains INFO for `com.shootoff.calibration` (each detection and outcome) and for `com.shootoff.compose.app.AppState` (the reconnect). Everything else stays at WARN.

    *Cost:* a check logs up to about twenty INFO lines.
15. **Existing tests change where Revision 2 reverses behavior they pin.** Each change is in the task that changes the behavior:
    - Task 3: `TestSetupModel.aCalibrationFinishedOnSetupGoesBackToTheRange` becomes `aCalibrationFinishedOnSetupStaysOnSetupAndSaysItIsComplete`; `TestShortcuts.f6DuringAProjectorDrill…` expects Setup, not Range.
    - Task 4: `TestShortcuts.f6DuringAProjectorDrillStopsItCalibratesAndStartsItAfresh` goes, replaced by `TestCalibrationPausesTheDrill.f6PausesTheDrillAndItStaysPausedWhereItWasAfterCalibrating`; two `TestCalibrationController` tests check the cover instead of the targets' visibility.
    - Task 5: `TestProblems.losingTheCameraClosesTheArenaItCalibrated` and `switchingCamerasClosesTheArenaTheOldOneCalibrated` are rewritten for the arena staying; `aCameraOpensOffTheCallingThreadAndSaysSoMeanwhile` expects the arena still open; `TestRememberedCalibration.unpluggingTheCameraMidCheckStopsItAndClosesTheArena` becomes `…AndKeepsTheArenaOpen`.
    - Task 7: `TestCalibrationCheck` is rewritten (6 tests become 8); `TestPatternDetector` compares with 5 px itself; `TestRememberedCalibration.aMovedProjectionIsReported…` moves the pattern 20 px, not 14.
    - Task 8: `TestRememberedCalibration.calibratingDuringTheCheck…` cancels the measurement that now follows; the check's tests put the arena on the projector with `AppFixture.putOnTheProjector`; `TestNothingBlocks.rule3…AfterThreeSeconds` becomes `…AtItsTimeLimit`.
    - Task 9: `TestNothingBlocks.rule1AtLaunchOnlyTheCameraOpensAndNothingElseStarts` becomes `rule1AtLaunchTheCameraAndTheArenaOpenAndNothingCalibrates`.

    *Cost:* none; the baseline's Java 8 tests are all unchanged.

**Changes to JavaFX behavior:** one. Its camera list (Preferences) no longer shows V4L2 nodes that can't capture video. `SarxosCaptureCamera.getName()` returns the same names, now kept rather than looked up. Its UI, its calibration and everything else are unchanged; `PatternMeasurement` and the reworked `CalibrationCheck` are code it never calls, and `ExerciseHostSupport.isShotDetectionPaused()` is a new getter it doesn't use. No public or protected member it uses changes.

**For the owner to decide** (none blocks the plan):
- **No `plugin-api` change affects your drill.** Your RandomTargetParDrill v2 jar needs no rebuild: calibration pauses it by pressing its own Pause button. If you later want drills without a Pause button to pause too, or the par drill not to buzz when calibrated at its summary, a host-level pause could be added to the exercise API as default methods (for example `default boolean pause() { return false; }` and `resume()` on `Exercise`), which older jars would ignore; it isn't needed for this plan.
- Ruling 7: the pattern shows a second time, briefly, after each auto-calibration with Remember on, to measure what is remembered.
- Ruling 8: switching cameras also keeps the arena open.
- Still open from Plan 6: ruling 5 (a remembered calibration skips auto-calibration's perspective warp and exposure step) and ruling 6 (manual-box calibrations are remembered and checked).

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** no `Co-Authored-By` or any other trailer in commit messages. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- **Code style:**
  - Java: tabs; match the surrounding code. A new main-source Java file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file), then a blank line.
  - Kotlin: the official style (4 spaces, trailing commas). A new main-source `.kt` file starts with the same 17 lines, then a blank line.
  - Test files have no header. The code blocks below start at the `package` line.
  - Keep each file's imports sorted as the file already sorts them.
- **Packages:** `com.shootoff.*`. New `core` classes go in `com.shootoff.calibration`; new `compose-app` classes in `com.shootoff.compose.app`.
- **Dependencies:** none added anywhere. `compose-app` still depends on `core`, `plugin-api` and Plan 5's catalog entries only; no OpenJFX in `compose-app` (`TestNoJavaFxInComposeApp` keeps passing).
- **Formats:** `.target`, `.course`, the session formats, `shootoff.xml` descriptors and `shootoff.properties` are unchanged (Plan 6's `shootoff.arena.calibration.*` keys are reused as they are).
- **The exercise API is unchanged.** `plugin-api`'s `Exercise` and `ExerciseHost` (what plugin jars compile against) don't change. The one `plugin-api` edit is the host-side getter `ExerciseHostSupport.isShotDetectionPaused()`.
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
- **The JavaFX app behaves as before**, except its camera list (ruling 12). `./gradlew run` starts only the JavaFX app; `./gradlew :compose-app:run` starts the Compose app.
- **Publishing:** only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`. No task here publishes.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- Implementers never run the GUI apps; Task 10 is the owner's.
- **Test gate** (unchanged from Plans 1–6; run it in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10` if the shell doesn't already point at a Java 21):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. Use a Bash timeout of 600000 ms.
  - The `N/M passing` count starts at **685** (end of Plan 6). Each task gives the count it ends at. A lower count means tests silently stopped running; Task 4 deletes one test on purpose (ruling 15), and its count allows for it.
  - The Compose UI tests need a display, as the JavaFX tests do. The gate runs them on this machine's `DISPLAY=:0`.
  - The gate leaves `shootoff.properties` unchanged.
  - `build/gate.log` has one Kotlin warning from Plan 5 (`TestExerciseRunner.kt:71`, an unnecessary `lateinit`) when the compose tests recompile; no task adds another.

## Review Focus

1. **The webcam is unplugged in the middle of a drill, with the arena on the projector, and plugged back in.** Expected: the app doesn't close; the arena stays open showing "Needs Calibration"; the drill pauses where it was; Setup and the chip say "No camera"; the camera reopens by itself when plugged back in, calibratable at once, and with Remember on the saved calibration is checked.
   - *Tests:* `TestProblems.losingTheCameraKeepsTheArenaOpenUncalibratedAndPausesTheDrill` (Task 5), `TestRememberedCalibration.whenTheCameraComesBackTheSavedCalibrationIsCheckedAgain` (Task 5), `TestProblems.aLostCameraReopensByItselfWhenItIsPluggedBackIn` (Task 6)
2. **A camera is picked from a list made before it was unplugged** (the owner's crash). Expected: no exception reaches the window; the panel says the camera is not connected and offers the others; any exception that does escape a click handler is a notice, not an exit.
   - *Tests:* `TestSarxosCaptureCamera.testTheNameIsKeptFromWhenTheCameraWasFound` (Task 1), `TestProblems.pickingACameraThatIsNoLongerPluggedInSaysItIsNotConnected` and `TestUiErrors.anExceptionInTheUiIsShownAsANoticeAndNeverClosesTheWindow` (Task 2)
3. **The arena window is slow to reach full screen, or flaps (F11 twice quickly) during a check.** Expected: no pattern is measured until the window fills the projector and has settled; a flap never turns shot detection on under the next pattern.
   - *Tests:* `TestRememberedCalibration.theCheckWaitsForTheArenaToFillTheProjectorAndSettle` and `aFastFullScreenFlapNeverTurnsDetectionOnUnderTheNextPattern`, `TestArenaWindow.theArenaIsFullScreenOnlyOnceItFillsItsScreen` (Task 8)
4. **Calibration is started, then cancelled, while the drill is already paused.** Expected: calibration doesn't press Resume; the drill stays paused, the arena comes back as it was, and detection stays off until the owner resumes.
   - *Tests:* `TestCalibrationPausesTheDrill.aDrillAlreadyPausedStaysPausedThroughACancelledCalibration` and `aPausedDrillKeepsShotDetectionOffAfterCalibrationUntilItResumes` (Task 4)
5. **The lost camera comes back in the list but won't open** (another program took it, or the device isn't ready). Expected: it is tried once, the panel keeps saying why and offering the cameras, and the app doesn't retry (and log an error) every two seconds.
   - *Test:* `TestProblems.aCameraThatIsListedButWontOpenIsTriedOnceUntilItIsPluggedInAgain` (Task 6)

The owner's check (Task 10) covers what no unit test reaches: the C270's real nodes, real pattern detection and its jitter, the window manager's full screen, unplugging and replugging, and the drill played through a recalibration.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/…/camera/cameratypes/{V4l2Controls,SarxosCaptureCamera}.java`, `core/…/camera/CameraFactory.java` | only capture nodes listed; the camera's name kept | 1 |
| `…/compose/app/{UiErrors,CameraSource,CameraProblems,AppState}.kt`, `…/compose/Main.kt` | a stale pick says "not connected"; the safety net | 2 |
| `…/compose/app/{AppState,SetupScreen,SetupSteps}.kt` | stay on Setup; "Calibration complete ✓" | 3 |
| `plugin-api/…/exercise/host/ExerciseHostSupport.java`; `…/compose/drill/{ComposeExerciseHost,ExerciseRunner}.kt`; `…/compose/arena/{ArenaModel,ArenaCanvas}.kt`; `…/compose/calibration/{CalibrationController,RememberedCalibration}.kt`; `…/compose/app/{AppState,Shortcuts}.kt` | calibration pauses the drill; the arena's cover | 4 |
| `…/compose/app/AppState.kt` | the camera going keeps the arena | 5 |
| `…/compose/app/{AppState,NoCameraPanel}.kt` | the reconnect | 6 |
| `core/…/calibration/{PatternMeasurement,CalibrationCheck}.java` | the median, the tolerance, the outcomes | 7 |
| `…/compose/calibration/{RememberedCalibration,CalibrationController}.kt`; `…/compose/arena/ArenaWindow.kt`; `…/compose/app/{AppState,SetupScreen,RangeControls}.kt`; `compose-app/src/main/resources/logback.xml` | measuring after calibration, the arena on the projector, logging | 8 |
| `…/compose/app/{AppState,SetupScreen}.kt`, `…/compose/Main.kt` | the arena at launch; the projector looked for again | 9 |
| none | the owner's re-check | 10 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`, with tests under `core/src/test/java/com/shootoff/`. `plugin-api/…/` is `plugin-api/src/main/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | Test methods added (−removed) | Gate |
|---|---|---|
| 1 | `core` `camera/cameratypes/TestV4l2Controls` (+4), `TestSarxosCaptureCamera` (+1) | 690 |
| 2 | `app/TestProblems` (+2), `app/TestUiErrors` (2) | 694 |
| 3 | `app/TestSetupScreen` (+1); `TestSetupModel` (1 renamed) | 695 |
| 4 | `plugin-api` `exercise/host/TestExerciseHostSupport` (+1); `calibration/TestCalibrationController` (+1), `arena/TestArenaViews` (+1), `app/TestCalibrationPausesTheDrill` (4), `app/TestShortcuts` (−1) | 701 |
| 5 | `app/TestRememberedCalibration` (+1); `TestProblems` (2 rewritten) | 702 |
| 6 | `app/TestProblems` (+3), `app/TestProblemViews` (+1) | 706 |
| 7 | `core` `calibration/TestPatternMeasurement` (5), `calibration/TestCalibrationCheck` (+2, rewritten) | 713 |
| 8 | `app/TestRememberedCalibration` (+6), `arena/TestArenaWindow` (1), `app/TestSetupScreen` (+1) | 721 |
| 9 | `app/TestNothingBlocks` (+1), `app/TestSetupScreen` (+1) | 723 |

Revision 2's decisions, one row each:

| Decision | Tests | Task |
|---|---|---|
| 1. Stay on Setup after calibrating | `TestSetupModel.aCalibrationFinishedOnSetupStaysOnSetupAndSaysItIsComplete`, `TestSetupScreen.aFinishedCalibrationStaysOnSetupAndSaysCalibrationComplete` | 3 |
| 2. Calibration pauses the drill | `TestCalibrationPausesTheDrill` (4), `TestCalibrationController.aTargetThePausedDrillHidWhileCalibratingStaysHiddenAfterwards`, `TestArenaViews.whileCoveredOnlyTheBackgroundShowsAndEverythingComesBackAsItWas` | 4 |
| 3. No false "moved" | `TestPatternMeasurement` (5), `TestCalibrationCheck` (8), `TestRememberedCalibration` (Task 8's six), `TestArenaWindow` | 7, 8 |
| 4. The arena at launch | `TestNothingBlocks.rule1AtLaunchTheCameraAndTheArenaOpenAndNothingCalibrates`, `rule1WithNoProjectorScreenOnlyTheCameraOpensAtLaunch`, `TestSetupScreen.theProjectorStepNoticesAProjectorPluggedInWhileItShows` | 9 |
| 5. Only real cameras | `TestV4l2Controls` (Task 1's four) | 1 |
| 6. Reconnect | `TestProblems` (Task 6's three), `TestProblemViews.whileTheLostCameraIsAwaitedThePanelSaysSoAndStillOffersTheCameras` | 6 |
| 7. Camera loss keeps the arena | `TestProblems.losingTheCameraKeepsTheArenaOpenUncalibratedAndPausesTheDrill`, `switchingCamerasKeepsTheArenaOpenUncalibratedAndCalibratableWithTheNewOne`, `TestRememberedCalibration.whenTheCameraComesBackTheSavedCalibrationIsCheckedAgain` | 5 |
| 8. No crash on a stale pick | `TestSarxosCaptureCamera.testTheNameIsKeptFromWhenTheCameraWasFound`, `TestProblems.pickingACameraThatIsNoLongerPluggedInSaysItIsNotConnected`, `aCameraPluggedBackInIsFoundByNameAndThatOneOpens`, `TestUiErrors` (2) | 1, 2 |

---

### Task 1: Only cameras that capture video are listed, and a camera keeps its name

**Files:**
- Modify: `core/src/main/java/com/shootoff/camera/cameratypes/V4l2Controls.java`, `core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java`, `core/src/main/java/com/shootoff/camera/CameraFactory.java`
- Test: `core/src/test/java/com/shootoff/camera/cameratypes/TestV4l2Controls.java`, `core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java`

**Interfaces:**
- Consumes: `V4l2Controls`' existing JNA `ioctl` (now taking any `Memory` argument); `SystemInfo.isLinux()`.
- Produces (`com.shootoff.camera.cameratypes`):
  - `public static Optional<Boolean> V4l2Controls.capturesVideo(String device)`: empty when the node can't be asked
  - `public static boolean V4l2Controls.isCaptureNode(String device)`: `capturesVideo(device).orElse(true)`
  - `static boolean V4l2Controls.capturesVideo(int capabilities, int deviceCaps)` (package-private, for the test)
  - `SarxosCaptureCamera.getName()` returns the name it was made with; it no longer reads `Webcam.getWebcams()`
  - `CameraFactory.getWebcams()` leaves out Linux nodes that answer "no video capture"; each other camera keeps its `/dev/videoN` index

**Why (decisions 5 and 8).** The C270 is two V4L2 nodes under one name: `/dev/video0` captures, `/dev/video1` carries metadata and can't be opened as a camera. Listing both offered a camera that fails with "Cannot open the webcam". And `SarxosCaptureCamera.getName()` looked its name up in the live webcam list by index, so once the camera was unplugged the list was shorter than the index and `getName()` threw `IndexOutOfBoundsException` from a click handler: the owner's crash.

- [ ] **Step 0: Record the owner's files**

```bash
cd /home/bfears/projects/ShootOFF
sha256sum RandomTargetParDrill-bests.properties exercises/RandomTargetParDrill.jar exercises/RandomTargetParDrill-v2.jar shootoff.properties > build/plan7-owner-files.sha256
cat build/plan7-owner-files.sha256
```

Expected: four checksum lines. No gate run of this plan changes any of them. Task 10 checks the first three after the owner has used the apps.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/camera/cameratypes/TestV4l2Controls.java`:

Replace:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
```

with:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
```

Replace:

```java
		// the id v4l2-ctl reports for exposure_dynamic_framerate
		assertEquals(0x009a0903, V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE);
	}
}
```

with:

```java
		// the id v4l2-ctl reports for exposure_dynamic_framerate
		assertEquals(0x009a0903, V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE);
	}

	@Test
	void aCaptureNodeCapturesVideo() {
		// The Logitech C270's /dev/video0: capabilities 0x84a00001, device caps 0x04200001
		assertTrue(V4l2Controls.capturesVideo(0x84a00001, 0x04200001));
	}

	@Test
	void aMetadataNodeDoesNotCaptureVideo() {
		// The C270's /dev/video1, under the same name: same capabilities, device caps 0x04a00000 (metadata)
		assertFalse(V4l2Controls.capturesVideo(0x84a00001, 0x04a00000));
	}

	@Test
	void aDriverWithoutDeviceCapsIsJudgedByItsCapabilities() {
		assertTrue(V4l2Controls.capturesVideo(0x05000001, 0));
		assertFalse(V4l2Controls.capturesVideo(0x00800000, 0x00000001));
	}

	@Test
	void aNodeWhoseCapabilitiesCantBeReadIsStillListed() throws IOException {
		final Path notADevice = Files.createFile(tempDir.resolve("not-a-camera"));

		assertEquals(Optional.empty(), V4l2Controls.capturesVideo(tempDir.resolve("video99").toString()));
		assertEquals(Optional.empty(), V4l2Controls.capturesVideo(notADevice.toString()));
		assertTrue(V4l2Controls.isCaptureNode(tempDir.resolve("video99").toString()));
	}
}
```

`core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java`:

Replace:

```java
		assertEquals(0.0, viewSize.getHeight(), 0.0);
	}

	@Test
	public void testDisablingDynamicFramerateOnMissingDeviceReportsNoChange() {
		assertFalse(SarxosCaptureCamera.disableDynamicFramerate("/nonexistent/video99"));
```

with:

```java
		assertEquals(0.0, viewSize.getHeight(), 0.0);
	}

	@Test
	public void testTheNameIsKeptFromWhenTheCameraWasFound() {
		// Index 7 is past every webcam on the test machine, as a camera unplugged after it was listed is:
		// its name must not be looked up by index again
		final SarxosCaptureCamera camera = new SarxosCaptureCamera("Test Camera", 7);

		assertEquals("Test Camera", camera.getName());
	}

	@Test
	public void testDisablingDynamicFramerateOnMissingDeviceReportsNoChange() {
		assertFalse(SarxosCaptureCamera.disableDynamicFramerate("/nonexistent/video99"));
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.camera.cameratypes.*' --console=plain`

Expected: `compileTestJava` FAILS with `cannot find symbol` for `capturesVideo` and `isCaptureNode`. (With only Step 3's `V4l2Controls` part in place, `testTheNameIsKeptFromWhenTheCameraWasFound` fails with `IndexOutOfBoundsException: Index 7 out of bounds for length 0`: the owner's crash.)

- [ ] **Step 3: Ask a node whether it captures video, keep the name, and list only capture nodes**

`core/src/main/java/com/shootoff/camera/cameratypes/V4l2Controls.java`:

Replace:

```java
package com.shootoff.camera.cameratypes;

import java.util.OptionalInt;

import org.slf4j.Logger;
```

with:

```java
package com.shootoff.camera.cameratypes;

import java.util.Optional;
import java.util.OptionalInt;

import org.slf4j.Logger;
```

Replace:

```java
	private static final long VIDIOC_G_CTRL = 0xC008561BL;
	private static final long VIDIOC_S_CTRL = 0xC008561CL;

	private static final int O_RDWR = 2;

	// struct v4l2_control { __u32 id; __s32 value; }
	private static final int CONTROL_SIZE = 8;

	private interface CLibrary extends Library {
		int open(String path, int flags);
```

with:

```java
	private static final long VIDIOC_G_CTRL = 0xC008561BL;
	private static final long VIDIOC_S_CTRL = 0xC008561CL;

	// _IOR('V', 0, struct v4l2_capability)
	private static final long VIDIOC_QUERYCAP = 0x80685600L;

	private static final int O_RDWR = 2;

	// struct v4l2_control { __u32 id; __s32 value; }
	private static final int CONTROL_SIZE = 8;

	// struct v4l2_capability { __u8 driver[16]; __u8 card[32]; __u8 bus_info[32]; __u32 version;
	// __u32 capabilities; __u32 device_caps; __u32 reserved[3]; }
	private static final int CAPABILITY_SIZE = 104;
	private static final int CAPABILITIES_OFFSET = 84;
	private static final int DEVICE_CAPS_OFFSET = 88;

	private static final int CAP_VIDEO_CAPTURE = 0x00000001;
	private static final int CAP_VIDEO_CAPTURE_MPLANE = 0x00001000;
	// The device_caps field is filled in (every driver since Linux 3.3)
	private static final int CAP_DEVICE_CAPS = 0x80000000;

	private interface CLibrary extends Library {
		int open(String path, int flags);
```

Replace:

```java
		return ioctl(device, VIDIOC_S_CTRL, newControl(controlId, value));
	}

	private static Memory newControl(int controlId, int value) {
		final Memory control = new Memory(CONTROL_SIZE);
		control.setInt(0, controlId);
```

with:

```java
		return ioctl(device, VIDIOC_S_CTRL, newControl(controlId, value));
	}

	/**
	 * Whether <tt>device</tt> is a node that captures video. A UVC webcam also has a metadata node (the
	 * Logitech C270 is /dev/video0 for capture and /dev/video1 for metadata, under the same name), which
	 * can't be opened as a camera.
	 *
	 * @return empty if the device's capabilities can't be read (missing, not a V4L2 device, no libc)
	 */
	public static Optional<Boolean> capturesVideo(String device) {
		final Memory capability = new Memory(CAPABILITY_SIZE);
		capability.clear();

		if (!ioctl(device, VIDIOC_QUERYCAP, capability)) return Optional.empty();

		return Optional.of(capturesVideo(capability.getInt(CAPABILITIES_OFFSET), capability.getInt(DEVICE_CAPS_OFFSET)));
	}

	/**
	 * @return true unless <tt>device</tt> says it can't capture video: a node whose capabilities can't be
	 *         read is still listed, as it was before this check
	 */
	public static boolean isCaptureNode(String device) {
		return capturesVideo(device).orElse(true);
	}

	/**
	 * @param capabilities
	 *            the whole physical device's capabilities
	 * @param deviceCaps
	 *            this node's own, when <tt>capabilities</tt> has V4L2_CAP_DEVICE_CAPS
	 */
	static boolean capturesVideo(int capabilities, int deviceCaps) {
		final int node = (capabilities & CAP_DEVICE_CAPS) != 0 ? deviceCaps : capabilities;
		return (node & (CAP_VIDEO_CAPTURE | CAP_VIDEO_CAPTURE_MPLANE)) != 0;
	}

	private static Memory newControl(int controlId, int value) {
		final Memory control = new Memory(CONTROL_SIZE);
		control.setInt(0, controlId);
```

Replace:

```java
		return control;
	}

	private static boolean ioctl(String device, long request, Memory control) {
		try {
			final CLibrary libc = LibC.INSTANCE;
			final int fd = libc.open(device, O_RDWR);
```

with:

```java
		return control;
	}

	private static boolean ioctl(String device, long request, Memory argument) {
		try {
			final CLibrary libc = LibC.INSTANCE;
			final int fd = libc.open(device, O_RDWR);
```

Replace:

```java
			}

			try {
				final int result = libc.ioctl(fd, new NativeLong(request), control);

				if (result < 0) logger.debug("V4L2 ioctl {} on {} failed", Long.toHexString(request), device);
```

with:

```java
			}

			try {
				final int result = libc.ioctl(fd, new NativeLong(request), argument);

				if (result < 0) logger.debug("V4L2 ioctl {} on {} failed", Long.toHexString(request), device);
```

`core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java`:

Replace:

```java
	private int cameraIndex = -1;
	private final VideoCapture camera;

	private final AtomicBoolean closing = new AtomicBoolean(false);
```

with:

```java
	private int cameraIndex = -1;
	private final VideoCapture camera;
	// Kept from when the camera was found: looking it up in the live webcam list by index again fails
	// once the camera is unplugged (the list shrinks under the index)
	private final String name;

	private final AtomicBoolean closing = new AtomicBoolean(false);
```

Replace:

```java
	// For testing
	protected SarxosCaptureCamera() {
		camera = null;
	}

	public SarxosCaptureCamera(final String cameraName) {
```

with:

```java
	// For testing
	protected SarxosCaptureCamera() {
		camera = null;
		name = null;
	}

	public SarxosCaptureCamera(final String cameraName) {
```

Replace:

```java
		camera = new VideoCapture();
		this.cameraIndex = cameraIndex;

	}

	public SarxosCaptureCamera(final String cameraName, int cameraIndex) {
```

with:

```java
		camera = new VideoCapture();
		this.cameraIndex = cameraIndex;
		name = cameraName;
	}

	public SarxosCaptureCamera(final String cameraName, int cameraIndex) {
```

Replace:

```java
		camera = new VideoCapture();
		this.cameraIndex = cameraIndex;

	}

	@Override
```

with:

```java
		camera = new VideoCapture();
		this.cameraIndex = cameraIndex;
		name = cameraName;
	}

	@Override
```

Replace:

```java
	@Override
	public String getName() {
		return Webcam.getWebcams().get(cameraIndex).getName();
	}

	@Override
```

with:

```java
	@Override
	public String getName() {
		return name;
	}

	@Override
```

The `import com.github.sarxos.webcam.Webcam;` stays: the name-only constructor still uses it.

`core/src/main/java/com/shootoff/camera/CameraFactory.java`:

Replace:

```java
import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.camera.cameratypes.IpCamera;
import com.shootoff.camera.cameratypes.SarxosCaptureCamera;
import com.shootoff.util.SystemInfo;

public final class CameraFactory {
```

with:

```java
import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.camera.cameratypes.IpCamera;
import com.shootoff.camera.cameratypes.SarxosCaptureCamera;
import com.shootoff.camera.cameratypes.V4l2Controls;
import com.shootoff.util.SystemInfo;

public final class CameraFactory {
```

Replace:

```java
		int cameraIndex = 0;
		for (final Webcam w : Webcam.getWebcams()) {
			final Camera c;
			if (w.getDevice() instanceof IpCamDevice)
				c = new IpCamera(w);
			else
				c = new SarxosCaptureCamera(w.getName(), cameraIndex);
```

with:

```java
		int cameraIndex = 0;
		for (final Webcam w : Webcam.getWebcams()) {
			final boolean ipCamera = w.getDevice() instanceof IpCamDevice;

			// OpenCV opens camera index N as /dev/videoN. A node that can't capture video (a UVC webcam's
			// metadata node, listed under the same name as its camera) is left out; its index still counts.
			if (!ipCamera && SystemInfo.isLinux() && !V4l2Controls.isCaptureNode("/dev/video" + cameraIndex)) {
				logger.debug("{} at /dev/video{} doesn't capture video: not listed", w.getName(), cameraIndex);
				cameraIndex++;
				continue;
			}

			final Camera c;
			if (ipCamera)
				c = new IpCamera(w);
			else
				c = new SarxosCaptureCamera(w.getName(), cameraIndex);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.camera.cameratypes.*' --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `690/690 passing; 0 regressions; 0 new failures`, and four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add core/src/main/java/com/shootoff/camera/cameratypes/V4l2Controls.java core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java core/src/main/java/com/shootoff/camera/CameraFactory.java core/src/test/java/com/shootoff/camera/cameratypes/TestV4l2Controls.java core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java
git commit -m "List only cameras that capture video, and keep each camera's name"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 2: A picked camera that is gone says so, and a UI error never closes the app

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/UiErrors.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/CameraSource.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/CameraProblems.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`openCameraInBackground`, `Opened`, `publish`), `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiErrors.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`

**Interfaces:**
- Consumes: Task 1's cached `SarxosCaptureCamera.getName()`; Plan 5's `Notices`, `CameraProblems`, `AppState.openCameraInBackground`.
- Produces:
  - `CameraSource.current(camera: Camera): Camera?`: a default method returning `camera`; `CameraSource.System` returns the listed camera with the same name, or null
  - `CameraProblems.showNotConnected(webcam: Camera)`: "<name> is not connected. Plug it in, or pick another camera."
  - `class UiErrors(notices: Notices) : WindowExceptionHandlerFactory` with `fun report(error: Throwable)`; `Main` provides it to both windows through `LocalWindowExceptionHandlerFactory`

**Why (decision 8).** Task 1 fixes the exception's cause. A pick made from a list shown before the unplug must still not try to open a device that isn't there: `openCameraInBackground` now asks the source for the camera as it is plugged in now, on the I/O dispatcher, and opens that one (it may be back under another device number) or says it is not connected. `UiErrors` is the safety net for anything else that escapes a window's event handling or composition.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiErrors.kt` (new):

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.ExperimentalComposeUiApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.EventQueue
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalComposeUiApi::class)
class TestUiErrors {
    @Test
    fun anExceptionInTheUiIsShownAsANoticeAndNeverClosesTheWindow() {
        val notices = Notices()
        val window = Frame()
        val closings = AtomicInteger()
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                closings.incrementAndGet()
            }
        })
        try {
            val handler = UiErrors(notices).exceptionHandler(window)

            // Returns, where Compose's default handler rethrows and asks the window to close (for the
            // main window, that exits the app)
            handler.onException(IndexOutOfBoundsException("Index 1 out of bounds for length 0"))
            EventQueue.invokeAndWait {}

            val notice = notices.notices.value.single()
            assertEquals("Something went wrong: IndexOutOfBoundsException", notice.title)
            assertEquals("Index 1 out of bounds for length 0. ShootOFF kept running; the details are in the log.", notice.message)
            assertEquals(0, closings.get())
        } finally {
            window.dispose()
        }
    }

    @Test
    fun anExceptionWithNoMessageStillSaysWhatHappened() {
        val notices = Notices()

        UiErrors(notices).report(IllegalStateException())

        assertEquals("ShootOFF kept running; the details are in the log.", notices.notices.value.single().message)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`:

Replace:

```kotlin
import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.camera.MockCamera
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.feed.FeedFrame
```

with:

```kotlin
import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.camera.MockCamera
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.feed.FeedFrame
```

Replace:

```kotlin
        assertEquals("Newer camera", app.camera.value!!.camera.name)
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

with:

```kotlin
        assertEquals("Newer camera", app.camera.value!!.camera.name)
    }

    @Test
    fun pickingACameraThatIsNoLongerPluggedInSaysItIsNotConnected() {
        // Listed before it was unplugged: the system no longer has a camera by its name
        val gone = SlowCamera("HD Webcam C270")
        val unplugged = object : CameraSource {
            override fun cameras() = emptyList<AppFixture.TestCamera>()

            override fun startCamera(settings: Settings) = null

            override fun current(camera: Camera) = null
        }
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), unplugged, { AppFixture.ownerScreens }, ManualClock(), { it.run() })
        try {
            val opened = AtomicReference<Boolean?>()

            app.openCameraInBackground(gone) { opened.set(it) }

            awaitTrue { opened.get() != null }
            assertEquals(false, opened.get())
            assertNull(app.camera.value)
            assertNull(app.openingCamera.value)
            assertEquals("HD Webcam C270 is not connected. Plug it in, or pick another camera.", app.cameraProblem.value)
            assertEquals(0, gone.opens.get())
        } finally {
            app.close()
        }
    }

    @Test
    fun aCameraPluggedBackInIsFoundByNameAndThatOneOpens() {
        val stale = SlowCamera("HD Webcam C270")
        val replugged = AppFixture.TestCamera("HD Webcam C270")
        val source = object : CameraSource {
            override fun cameras() = listOf(replugged)

            override fun startCamera(settings: Settings) = null

            override fun current(camera: Camera) = cameras().firstOrNull { it.name == camera.name }
        }
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() })
        try {
            app.openCameraInBackground(stale)

            awaitTrue { app.camera.value != null }
            assertSame(replugged, app.camera.value!!.camera)
            assertEquals(0, stale.opens.get())
        } finally {
            app.close()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestUiErrors' --tests 'com.shootoff.compose.app.TestProblems' --console=plain`

Expected: `compileTestKotlin` FAILS with `'current' overrides nothing` and `Unresolved reference 'UiErrors'`.

- [ ] **Step 3: Find a picked camera by name before opening it**

`compose-app/src/main/kotlin/com/shootoff/compose/app/CameraSource.kt`:

Replace:

```kotlin
    /** The camera to open at start: the configured one, else the system default */
    fun startCamera(settings: Settings): Camera?

    object System : CameraSource {
        override fun cameras(): List<Camera> = CameraFactory.getWebcams()

        override fun startCamera(settings: Settings): Camera? =
            settings.webcams.values.firstOrNull() ?: CameraFactory.getDefault().orElse(null)
    }

    object None : CameraSource {
```

with:

```kotlin
    /** The camera to open at start: the configured one, else the system default */
    fun startCamera(settings: Settings): Camera?

    /**
     * [camera] as it is plugged in now, found by its name (it may be back under another device number), or
     * null if it is no longer plugged in. Called off the UI thread: it may list the cameras.
     */
    fun current(camera: Camera): Camera? = camera

    object System : CameraSource {
        override fun cameras(): List<Camera> = CameraFactory.getWebcams()

        override fun startCamera(settings: Settings): Camera? =
            settings.webcams.values.firstOrNull() ?: CameraFactory.getDefault().orElse(null)

        override fun current(camera: Camera): Camera? = cameras().firstOrNull { it.name == camera.name }
    }

    object None : CameraSource {
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/CameraProblems.kt`:

Replace:

```kotlin
    // clears the feed and shows [missingMessage] only if so
    override fun showMissingCameraError(webcam: Camera) = lost(webcam)

    /** What the feed says when [webcam] stops answering */
    fun missingMessage(webcam: Camera): String = String.format(CameraErrorView.MISSING_ERROR, name(webcam))
```

with:

```kotlin
    // clears the feed and shows [missingMessage] only if so
    override fun showMissingCameraError(webcam: Camera) = lost(webcam)

    /** [webcam] was picked from a list made before it was unplugged: it isn't opened */
    fun showNotConnected(webcam: Camera) = problem("${name(webcam)} is not connected. Plug it in, or pick another camera.")

    /** What the feed says when [webcam] stops answering */
    fun missingMessage(webcam: Camera): String = String.format(CameraErrorView.MISSING_ERROR, name(webcam))
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
        scope.launch(io) {
            // The old device closes before the new one opens, in case they are the same hardware
            old?.let(::closeDevice)
            val opened = startCamera(camera)
            // Dropped already (a newer open, or the app closing): cleaned up here, off the UI thread
            if (generation != openGeneration.get()) {
                discard(opened)
```

with:

```kotlin
        scope.launch(io) {
            // The old device closes before the new one opens, in case they are the same hardware
            old?.let(::closeDevice)
            // Picked from a list made before it was unplugged, it may be gone, or back under another device
            val found = try {
                cameraSource.current(camera)
            } catch (e: Exception) {
                logger.warn("Couldn't look for the webcam {}", camera.name, e)
                camera
            }
            val opened = if (found != null) startCamera(found) else Opened(camera, OpenView(cameraView), null, notConnected = true)
            // Dropped already (a newer open, or the app closing): cleaned up here, off the UI thread
            if (generation != openGeneration.get()) {
                discard(opened)
```

Replace:

```kotlin
    }

    // A camera open's outcome: its manager if it started, else why not
    private class Opened(val camera: Camera, val view: OpenView, val manager: CameraManager?, val error: Throwable? = null)

    // Starts a manager for [camera], blocking on the hardware. It isn't registered with [cameras] (only
    // [publish] does that, on the UI thread), and a camera that fails to start is closed again.
```

with:

```kotlin
    }

    // A camera open's outcome: its manager if it started, else why not
    private class Opened(
        val camera: Camera,
        val view: OpenView,
        val manager: CameraManager?,
        val error: Throwable? = null,
        val notConnected: Boolean = false,
    )

    // Starts a manager for [camera], blocking on the hardware. It isn't registered with [cameras] (only
    // [publish] does that, on the UI thread), and a camera that fails to start is closed again.
```

Replace:

```kotlin
        val manager = opened.manager
        if (manager == null) {
            val error = opened.error
            if (error != null) {
                cameraProblems.showOpenError(opened.camera, error)
            } else {
                logger.error("Cannot open the webcam {}", opened.camera.name)
```

with:

```kotlin
        val manager = opened.manager
        if (manager == null) {
            val error = opened.error
            if (opened.notConnected) {
                cameraProblems.showNotConnected(opened.camera)
            } else if (error != null) {
                cameraProblems.showOpenError(opened.camera, error)
            } else {
                logger.error("Cannot open the webcam {}", opened.camera.name)
```

- [ ] **Step 4: The safety net**

`compose-app/src/main/kotlin/com/shootoff/compose/app/UiErrors.kt` (new):

```kotlin
/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import org.slf4j.LoggerFactory
import java.awt.Window

/**
 * The safety net under the Compose app's windows (spec §8 Revision 2, decision 8): an exception that
 * escapes event handling or composition is logged and shown as a notice, and never closes a window or
 * exits the app. Compose Desktop's own handler shows a dialog and closes the window the exception came
 * from, which for the main window exits the app. Provided to every window through
 * `LocalWindowExceptionHandlerFactory` (see Main).
 */
@OptIn(ExperimentalComposeUiApi::class)
class UiErrors(private val notices: Notices) : WindowExceptionHandlerFactory {
    private val logger = LoggerFactory.getLogger(UiErrors::class.java)

    override fun exceptionHandler(window: Window): WindowExceptionHandler = WindowExceptionHandler(::report)

    /** Logs [error] and tells the user, then returns: the window and the app carry on */
    fun report(error: Throwable) {
        logger.error("Unexpected error in the user interface; ShootOFF kept running", error)
        val detail = error.message?.let { "$it. " } ?: ""
        notices.showError("Something went wrong", error.javaClass.simpleName, "${detail}ShootOFF kept running; the details are in the log.")
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`: both windows go inside a `CompositionLocalProvider`. The whole file after the change:

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt` (the whole file):

```kotlin
/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.shootoff.compose.app.AppState
import com.shootoff.compose.app.CameraSource
import com.shootoff.compose.app.ExerciseCatalog
import com.shootoff.compose.app.PrefsStore
import com.shootoff.compose.app.UiPrefs
import com.shootoff.compose.app.WindowBounds
import com.shootoff.compose.app.handleKey
import com.shootoff.compose.app.ShootOffApp
import com.shootoff.compose.app.UiErrors
import com.shootoff.compose.arena.ArenaWindow
import com.shootoff.compose.drill.ExerciseOverlay
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.plugins.TextToSpeech
import com.shootoff.plugins.engine.PluginEngine
import com.shootoff.plugins.engine.V2ExerciseLoader
import com.shootoff.util.TimerPool
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import java.io.File

/**
 * The Compose app. It shares the JavaFX app's folder: shootoff.properties, targets/, sounds/,
 * exercises/ and exercise-data/ in the working directory.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val home = System.getProperty("shootoff.home") ?: System.getProperty("user.dir").also { System.setProperty("shootoff.home", it) }
    System.setProperty("shootoff.sessions", home + File.separator + "sessions")
    System.setProperty("shootoff.courses", home + File.separator + "courses")
    System.setProperty("shootoff.plugins", home + File.separator + "exercises")

    Loader.load(opencv_java::class.java)

    val settings = Settings(home + File.separator + "shootoff.properties", arrayOf())
    // Speech synthesis starts slowly: warm it up in the background
    Thread({ TextToSpeech.say("") }, "Speech warm-up").apply { isDaemon = true }.start()

    val catalog = ExerciseCatalog()
    val plugins = PluginEngine(catalog, listOf(V2ExerciseLoader()), emptyList())
    plugins.startWatching()

    val app = AppState(settings, catalog, CameraSource.System, prefs = UiPrefs(PrefsStore.User()))
    Settings.setUserNotifier(app.notices)
    // Only the camera opens, in the background: the window shows at once (spec §8 rules 1 and 6)
    app.launch()

    // An exception in a window's event handling or composition is logged and shown, and never exits the app
    val uiErrors = UiErrors(app.notices)

    application {
        val arena by app.arena.collectAsState()
        val placement by app.arenaPlacement.collectAsState()
        val running by app.runner.running.collectAsState()
        val dark by app.dark.collectAsState()
        // A saved place that no longer reaches a current screen (a monitor unplugged) is discarded
        val remembered = app.prefs.window?.let { placeMainWindow(it, app.screensNow()) }
        val state = rememberWindowState(
            position = remembered?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = remembered?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 860.dp),
        )

        fun exit() {
            plugins.stopWatching()
            app.close()
            TimerPool.close()
            exitApplication()
        }

        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides uiErrors) {
            Window(
                onCloseRequest = ::exit,
                state = state,
                title = "ShootOFF",
                onPreviewKeyEvent = { app.handleKey(it.key, it.type) },
            ) {
                // Where the window is tells which screen ShootOFF is on; its place and size are remembered
                LaunchedEffect(state) {
                    snapshotFlow { state.position to state.size }.collect { (position, size) ->
                        app.mainWindowCorner = Point(window.x.toDouble(), window.y.toDouble())
                        if (position.isSpecified) {
                            app.prefs.window = WindowBounds(position.x.value, position.y.value, size.width.value, size.height.value)
                        }
                    }
                }
                RangeTheme(dark = dark) { ShootOffApp(app) }
            }

            val shownArena = arena
            val shownPlacement = placement
            if (shownArena != null && shownPlacement != null) {
                ArenaWindow(shownArena, shownPlacement, onCloseRequest = app::closeArena, onKey = { app.handleKey(it.key, it.type) }) { transform ->
                    if (running?.host?.isProjector == true) ExerciseOverlay(app.drill, transform)
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestUiErrors' --tests 'com.shootoff.compose.app.TestProblems' --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `694/694 passing; 0 regressions; 0 new failures`, and four `OK` lines.

- [ ] **Step 7: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/UiErrors.kt compose-app/src/main/kotlin/com/shootoff/compose/app/CameraSource.kt compose-app/src/main/kotlin/com/shootoff/compose/app/CameraProblems.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/Main.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiErrors.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt
git commit -m "Say a picked camera that is gone is not connected, and keep the app running through UI errors"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 3: Stay on Setup after calibrating, with a confirmation

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`calibrationComplete`, `calibrationSucceeded`, `startCalibration`, `closeArena`), `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt` (`CalibrateStep`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupModel.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`

**Interfaces:**
- Consumes: Plan 6's `CalibrationViews.calibrationSucceeded`, `AppState.wallClock`.
- Produces:
  - `AppState.calibrationComplete: StateFlow<LocalTime?>` (ruling 1)
  - `fun calibrationCompleteText(at: LocalTime): String` in `SetupSteps.kt`: `"Calibration complete ✓ 14:05"`
  - Setup's tag `calibration-complete`
  - `AppFixture.appWithCamera(…, wallClock: () -> LocalTime = LocalTime::now)` (additive)

**Why (decision 1).** The owner looks at the result on Setup (the outline, Show grid) before training; being sent to Range hid it. This reverses Revision 1's "When calibration succeeds from Setup, the app returns to Range" and Plan 6's ruling 9, and resolves Plan 6's deferred "calibrationSucceeded sets the destination directly": it no longer sets it at all.

- [ ] **Step 1: Write the failing tests, and change the ones that expected Range**

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

Replace:

```kotlin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.util.Optional

/** An app with no camera, the owner's three screens, and two drills in its catalog. */
```

with:

```kotlin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.util.Optional

/** An app with no camera, the owner's three screens, and two drills in its catalog. */
```

Replace:

```kotlin
        screens: List<Rect> = ownerScreens,
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
```

with:

```kotlin
        screens: List<Rect> = ownerScreens,
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
        wallClock: () -> LocalTime = LocalTime::now,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
```

Replace:

```kotlin
            ManualClock(),
            { it.run() },
            background,
            detector = { detector },
            checkClock = checkClock,
        )
```

with:

```kotlin
            ManualClock(),
            { it.run() },
            background,
            wallClock = wallClock,
            detector = { detector },
            checkClock = checkClock,
        )
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupModel.kt`:

Replace:

```kotlin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestSetupModel {
    private val app = AppFixture.appWithCamera()

    @AfterEach
    fun close() = app.close()
```

with:

```kotlin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.Optional

class TestSetupModel {
    private val app = AppFixture.appWithCamera(wallClock = { LocalTime.of(14, 5) })

    @AfterEach
    fun close() = app.close()
```

Replace:

```kotlin
    }

    @Test
    fun aCalibrationFinishedOnSetupGoesBackToTheRange() {
        setUpOnTheProjector()

        app.startCalibration()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Destination.RANGE, app.destination.value)
    }

    @Test
```

with:

```kotlin
    }

    @Test
    fun aCalibrationFinishedOnSetupStaysOnSetupAndSaysItIsComplete() {
        setUpOnTheProjector()

        app.startCalibration()
        assertNull(app.calibrationComplete.value)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Destination.SETUP, app.destination.value)
        assertEquals(LocalTime.of(14, 5), app.calibrationComplete.value)

        // Calibrating again takes the confirmation away until that calibration completes; so does closing the arena
        app.startCalibration()
        assertNull(app.calibrationComplete.value)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
        assertEquals(LocalTime.of(14, 5), app.calibrationComplete.value)
        app.closeArena()
        assertNull(app.calibrationComplete.value)
    }

    @Test
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`:

Replace:

```kotlin
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
```

with:

```kotlin
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
```

Replace:

```kotlin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TestSetupScreen {
    @get:Rule
```

with:

```kotlin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalTime
import java.util.Optional

class TestSetupScreen {
    @get:Rule
```

Replace:

```kotlin
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
```

with:

```kotlin
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun aFinishedCalibrationStaysOnSetupAndSaysCalibrationComplete() {
        app.close()
        app = AppFixture.appWithCamera(wallClock = { LocalTime.of(14, 5) })
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("setup-calibrate").performClick()
        compose.runOnIdle { app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0) }

        compose.onNodeWithTag("setup-screen").assertExists()
        compose.onNodeWithTag("calibration-complete").assertTextEquals("Calibration complete ✓ 14:05")
        step(Step.CALIBRATE, StepState.DONE)
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`:

Replace:

```kotlin
            app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

            assertNotSame(first, app.runner.running.value!!.host)
            assertEquals(Destination.RANGE, app.destination.value)
        } finally {
            app.close()
        }
```

with:

```kotlin
            app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

            assertNotSame(first, app.runner.running.value!!.host)
            // F6 opened Setup, and a finished calibration stays there
            assertEquals(Destination.SETUP, app.destination.value)
        } finally {
            app.close()
        }
```

(Task 4 replaces that F6 test with one where the drill is paused; here only its destination changes.)

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestSetupModel' --tests 'com.shootoff.compose.app.TestSetupScreen' --tests 'com.shootoff.compose.app.TestShortcuts' --console=plain`

Expected: `compileTestKotlin` FAILS with `Unresolved reference 'calibrationComplete'`.

- [ ] **Step 3: Keep the owner on Setup and say it worked**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private val pickedDrillState = MutableStateFlow<V2ExerciseEntry?>(null)
    private val promptSkippedState = MutableStateFlow(false)
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
```

with:

```kotlin
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private val calibrationCompleteState = MutableStateFlow<LocalTime?>(null)
    private val pickedDrillState = MutableStateFlow<V2ExerciseEntry?>(null)
    private val promptSkippedState = MutableStateFlow(false)
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
```

Replace:

```kotlin
    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /** The drill picked on Range, if the user picked one (see [pickedDrill]) */
    val drillChoice: StateFlow<V2ExerciseEntry?> = pickedDrillState.asStateFlow()
```

with:

```kotlin
    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /**
     * When the last calibration the user asked for completed, for Setup's confirmation; null from the moment
     * another calibration starts, and once the arena closes
     */
    val calibrationComplete: StateFlow<LocalTime?> = calibrationCompleteState.asStateFlow()

    /** The drill picked on Range, if the user picked one (see [pickedDrill]) */
    val drillChoice: StateFlow<V2ExerciseEntry?> = pickedDrillState.asStateFlow()
```

Replace:

```kotlin
    // The manual box is showing: it is dragged over Setup's camera feed
    override fun showCalibratingFeed() = navigate(Destination.SETUP)

    // Calibration ending leaves the user where they are; a success from Setup goes to Range (calibrationSucceeded)
    override fun restoreSelectedView() {}

    // ---- The camera
```

with:

```kotlin
    // The manual box is showing: it is dragged over Setup's camera feed
    override fun showCalibratingFeed() = navigate(Destination.SETUP)

    // Calibration ending leaves the user where they are, a success included (spec §8 Revision 2, decision 1)
    override fun restoreSelectedView() {}

    // ---- The camera
```

Replace:

```kotlin
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
        arenaState.value = null
        calibratedAtState.value = null
        runner.stopProjectorExercise()
        placementState.value = null
        promptSkippedState.value = false
```

with:

```kotlin
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
        arenaState.value = null
        calibratedAtState.value = null
        calibrationCompleteState.value = null
        runner.stopProjectorExercise()
        placementState.value = null
        promptSkippedState.value = false
```

Replace:

```kotlin
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
        arenaState.value?.showGrid(false)
        controller.start()
        return true
    }
```

with:

```kotlin
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
        arenaState.value?.showGrid(false)
        calibrationCompleteState.value = null
        controller.start()
        return true
    }
```

Replace:

```kotlin
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        calibratedAtState.value = wallClock()
        // Calibrated from Setup: back to training
        if (destinationState.value == Destination.SETUP) destinationState.value = Destination.RANGE
        checkState.value = CheckState.Idle
        val camera = cameraState.value
        val screen = placementState.value?.screen
```

with:

```kotlin
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
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt`:

Replace:

```kotlin
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One line about the arena's calibration, for Setup's Calibrate step and Range's status chip: what is
 * missing, what is under way, or when it was calibrated.
```

with:

```kotlin
private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Setup's confirmation that the calibration the user asked for worked */
fun calibrationCompleteText(at: LocalTime): String = "Calibration complete ✓ " + at.format(TIME)

/**
 * One line about the arena's calibration, for Setup's Calibrate step and Range's status chip: what is
 * missing, what is under way, or when it was calibrated.
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`:

Replace:

```kotlin
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val remember by app.rememberCalibration.collectAsState()
    val grid = arena?.grid?.collectAsState()?.value == true
```

with:

```kotlin
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val complete by app.calibrationComplete.collectAsState()
    val check by app.check.collectAsState()
    val remember by app.rememberCalibration.collectAsState()
    val grid = arena?.grid?.collectAsState()?.value == true
```

Replace:

```kotlin
        color = if (calibrated && !calibrating) colors.good else colors.mutedStrong,
        modifier = Modifier.testTag("calibration-summary"),
    )
    // Cancel is always there while something runs (spec §8 rule 5)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
```

with:

```kotlin
        color = if (calibrated && !calibrating) colors.good else colors.mutedStrong,
        modifier = Modifier.testTag("calibration-summary"),
    )
    // The confirmation stays until the next calibration starts: the user goes back to Range when they choose
    complete?.let {
        Text(calibrationCompleteText(it), color = colors.good, fontSize = 16.sp, modifier = Modifier.testTag("calibration-complete"))
    }
    // Cancel is always there while something runs (spec §8 rule 5)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestSetupModel' --tests 'com.shootoff.compose.app.TestSetupScreen' --tests 'com.shootoff.compose.app.TestShortcuts' --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `695/695 passing; 0 regressions; 0 new failures`, and four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupModel.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt
git commit -m "Stay on Setup after calibrating, with a confirmation"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 4: Calibration pauses the drill instead of restarting it

**Files:**
- Modify: `plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt` (its KDoc), `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt` (`CalibrationCheckRun`), `compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`
- Create (test): `compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationPausesTheDrill.kt`
- Test: `plugin-api/src/test/java/com/shootoff/exercise/host/TestExerciseHostSupport.java`, `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`

**Interfaces:**
- Consumes: Plan 6's `CalibrationController` (built by `AppState.makeCalibratable`), `CalibrationCheckRun`, `Shortcut.PAUSE_DRILL`; `CalibrationFlow.Exercises` (`core`, unchanged).
- Produces:
  - `public synchronized boolean ExerciseHostSupport.isShotDetectionPaused()` (`plugin-api`, host side; ruling 4)
  - `ComposeExerciseHost.shotDetectionPaused: Boolean`
  - `ArenaModel.covered: StateFlow<Boolean>` and `ArenaModel.cover(covered: Boolean)` (ruling 3)
  - `const val PAUSE_LABEL = "Pause"`, `const val RESUME_LABEL = "Resume"` in `Shortcuts.kt`
  - `AppState.pauseDrill(): Boolean` (ruling 2); the private `drillForCalibration` and `inner class CalibratingCamera` that Tasks 5 and 8 build on
  - `AppFixture.PausingDrill` / `AppFixture.pausingDrill` (Pause ↔ Resume, and `pauseShotDetection`), `AppFixture.UnpausableDrill` / `AppFixture.unpausableDrill`

**Why (decision 2).** A recalibration mid-session is a correction: the owner lost their rounds, score and personal-best progress each time the flow stopped the drill and started a fresh one. Now the drill is paused through its own Pause button (the v2 API has no host-level pause), kept through calibration, and left paused. Hiding every target during calibration and showing every target afterwards would reveal the round target the paused drill had hidden, so the arena is covered instead.

- [ ] **Step 1: Write the failing tests, and change the ones that expected a fresh drill**

`plugin-api/src/test/java/com/shootoff/exercise/host/TestExerciseHostSupport.java`:

Replace:

```java
		assertEquals(Optional.of(new Stopped<>(List.of("IPSC"), false)), support.stop());
	}

	@Test
	void callbacksRunOnTheExercisesThreadInOrder() throws Exception {
		support.start(null);
```

with:

```java
		assertEquals(Optional.of(new Stopped<>(List.of("IPSC"), false)), support.stop());
	}

	@Test
	void whetherTheExerciseHasDetectionPausedIsKnownUntilItStops() {
		assertFalse(support.isShotDetectionPaused());

		support.pauseShotDetection(true, detecting -> {});
		assertTrue(support.isShotDetectionPaused());

		support.pauseShotDetection(false, detecting -> {});
		assertFalse(support.isShotDetectionPaused());

		support.pauseShotDetection(true, detecting -> {});
		support.stop();
		assertFalse(support.isShotDetectionPaused());
	}

	@Test
	void callbacksRunOnTheExercisesThreadInOrder() throws Exception {
		support.start(null);
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

Replace:

```kotlin
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
```

with:

```kotlin
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.ButtonHandle
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
```

Replace:

```kotlin
        override fun stop() {}
    }

    class FeedDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Feed drill", "1.0", "ShootOFF tests", "On the camera feed")
```

with:

```kotlin
        override fun stop() {}
    }

    /**
     * A projector drill that pauses as the par drill does: its button reads "Pause" while it runs and
     * "Resume" while paused, and pausing turns shot detection off.
     */
    class PausingDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Pausing drill", "2.0", "ShootOFF tests", "Pauses like the par drill", true)

        override fun start(host: ExerciseHost) {
            var paused = false
            lateinit var button: ButtonHandle
            button = host.addButton("Pause") {
                paused = !paused
                button.setLabel(if (paused) "Resume" else "Pause")
                host.pauseShotDetection(paused)
            }
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    /** A projector drill with no Pause button */
    class UnpausableDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Unpausable drill", "2.0", "ShootOFF tests", "No pause", true)

        override fun start(host: ExerciseHost) {}

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    class FeedDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Feed drill", "1.0", "ShootOFF tests", "On the camera feed")
```

Replace:

```kotlin
    val projectorDrill = V2ExerciseEntry(ProjectorDrill::class.java, ProjectorDrill().metadata())
    val feedDrill = V2ExerciseEntry(FeedDrill::class.java, FeedDrill().metadata())

    /** A camera source with one [TestCamera], which the app opens at start */
    fun oneCamera(camera: TestCamera = TestCamera()) = object : CameraSource {
```

with:

```kotlin
    val projectorDrill = V2ExerciseEntry(ProjectorDrill::class.java, ProjectorDrill().metadata())
    val feedDrill = V2ExerciseEntry(FeedDrill::class.java, FeedDrill().metadata())
    val pausingDrill = V2ExerciseEntry(PausingDrill::class.java, PausingDrill().metadata())
    val unpausableDrill = V2ExerciseEntry(UnpausableDrill::class.java, UnpausableDrill().metadata())

    /** A camera source with one [TestCamera], which the app opens at start */
    fun oneCamera(camera: TestCamera = TestCamera()) = object : CameraSource {
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationPausesTheDrill.kt` (new):

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
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

    private fun startThePausingDrill() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.pausingDrill))
        awaitTrue { pauseLabel() == "Pause" }
    }

    private fun calibrateWithTheCamera() = app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

    @Test
    fun f6PausesTheDrillAndItStaysPausedWhereItWasAfterCalibrating() {
        startThePausingDrill()
        val host = app.runner.running.value!!.host

        app.handleKey(Key.F6, KeyEventType.KeyDown)

        awaitTrue { pauseLabel() == "Resume" }
        assertSame(host, app.runner.running.value!!.host)
        assertTrue(app.calibration.value!!.state.value.calibrating)
        assertTrue(app.arena.value!!.covered.value)

        calibrateWithTheCamera()

        // The same drill, still paused, with the arena as it was: never stopped, never restarted
        assertSame(host, app.runner.running.value!!.host)
        assertEquals("Resume", pauseLabel())
        assertFalse(app.arena.value!!.covered.value)
        assertEquals(Destination.SETUP, app.destination.value)

        assertTrue(app.perform(Shortcut.PAUSE_DRILL))
        awaitTrue { pauseLabel() == "Pause" }
    }

    @Test
    fun aDrillAlreadyPausedStaysPausedThroughACancelledCalibration() {
        startThePausingDrill()
        app.perform(Shortcut.PAUSE_DRILL)
        awaitTrue { pauseLabel() == "Resume" }

        assertTrue(app.startCalibration())
        app.cancelCalibration()

        // Calibration didn't press Resume
        Thread.sleep(100)
        assertEquals("Resume", pauseLabel())
        assertFalse(app.arena.value!!.covered.value)
        assertTrue(app.runner.running.value!!.host.isProjector)
    }

    @Test
    fun aPausedDrillKeepsShotDetectionOffAfterCalibrationUntilItResumes() {
        startThePausingDrill()
        val camera = app.camera.value!!

        app.startCalibration()
        awaitTrue { pauseLabel() == "Resume" }
        calibrateWithTheCamera()

        // Calibration turns detection back on once the pattern is well gone; not under a paused drill
        Thread.sleep(CalibrationFlow.DETECTION_RESTART_DELAY + 400)
        assertFalse(camera.isDetecting)

        app.perform(Shortcut.PAUSE_DRILL)
        awaitTrue { camera.isDetecting }
    }

    @Test
    fun aProjectorDrillWithNoPauseButtonIsStoppedAndStartedAfreshAsBefore() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.unpausableDrill))
        val first = app.runner.running.value!!.host

        app.startCalibration()
        assertNull(app.runner.running.value)

        calibrateWithTheCamera()
        assertNotSame(first, app.runner.running.value!!.host)
        assertEquals("Unpausable drill", app.runner.running.value!!.host.name)
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt`:

Replace:

```kotlin
            ResourceResolver.files(),
        )
        startOnTheProjector()
        assertFalse(arena.targets.set.get(target.id).get().isVisible)

        // The camera reports the pattern on its 640x480 feed, shown 1:1 on the canvas
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
```

with:

```kotlin
            ResourceResolver.files(),
        )
        startOnTheProjector()
        // Covered, not hidden: the target keeps its own visibility under the pattern
        assertTrue(arena.covered.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)

        // The camera reports the pattern on its 640x480 feed, shown 1:1 on the canvas
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
```

Replace:

```kotlin
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertSame(drillBackground, arena.background.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)
        assertFalse(controller.state.value.calibrating)
        assertNull(controller.state.value.message)
```

with:

```kotlin
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertSame(drillBackground, arena.background.value)
        assertFalse(arena.covered.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)
        assertFalse(controller.state.value.calibrating)
        assertNull(controller.state.value.message)
```

Replace:

```kotlin
        )
        startOnTheProjector()
        assertEquals("pattern.png", arena.background.value!!.name)
        assertFalse(arena.targets.set.get(target.id).get().isVisible)
        assertFalse(arena.markers.visible.value)

        controller.arenaClosing()

        // The model is left exactly as it was before calibration started
        assertSame(originalBackground, arena.background.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)
        assertTrue(arena.markers.visible.value)
    }

    @Test
    fun reopeningThenCalibratingSuccessfullyRestoresTheOriginalBackgroundNotThePattern() {
        val originalBackground = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
```

with:

```kotlin
        )
        startOnTheProjector()
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(arena.covered.value)
        assertFalse(arena.markers.visible.value)

        controller.arenaClosing()

        // The model is left exactly as it was before calibration started
        assertSame(originalBackground, arena.background.value)
        assertFalse(arena.covered.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)
        assertTrue(arena.markers.visible.value)
    }

    @Test
    fun aTargetThePausedDrillHidWhileCalibratingStaysHiddenAfterwards() {
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf()))),
            ResourceResolver.files(),
        )
        startOnTheProjector()

        // The drill pauses on its own thread once calibration has started, and hides its round's target
        arena.targets.set.setVisible(target.id, false)
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertFalse(arena.covered.value)
        assertFalse(arena.targets.set.get(target.id).get().isVisible)
    }

    @Test
    fun reopeningThenCalibratingSuccessfullyRestoresTheOriginalBackgroundNotThePattern() {
        val originalBackground = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
```

`compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt`:

Replace:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
```

with:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
```

Replace:

```kotlin
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
```

with:

```kotlin
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
```

Replace:

```kotlin
        compose.waitForIdle()
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(0)
    }
}
```

with:

```kotlin
        compose.waitForIdle()
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(0)
    }

    @Test
    fun whileCoveredOnlyTheBackgroundShowsAndEverythingComesBackAsItWas() {
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 80.0, 80.0, "red", mapOf("opacity" to "1")))),
            ResourceResolver.files(),
            Placement(600.0, 300.0, 1.0, 1.0, true),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ArenaCanvas(arena, Modifier.size(640.dp, 360.dp).testTag("projector")) {
                    Text("Score: 3", Modifier.testTag("drill-text"))
                }
            }
        }
        compose.onNodeWithTag("drill-text").assertExists()

        arena.cover(true)
        compose.waitForIdle()

        // The pattern (the background) alone: no target, no label, none of the drill's texts
        assertEquals(Color(0xFF333333), pixels("projector")[320, 170])
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(0)
        compose.onAllNodesWithTag("drill-text").assertCountEquals(0)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)

        arena.cover(false)
        compose.waitForIdle()

        assertEquals(Color.Red, pixels("projector")[320, 170])
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(1)
        compose.onNodeWithTag("drill-text").assertExists()
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

Replace:

```kotlin
        openArenaOnTheProjector()
        assertEquals(CheckState.Checking, app.check.value)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        sendFramesUntil { app.check.value == CheckState.Idle }

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.camera.value!!.projectionBounds.get())
        assertNotNull(app.calibratedAt.value)
```

with:

```kotlin
        openArenaOnTheProjector()
        assertEquals(CheckState.Checking, app.check.value)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        // Only the pattern shows: no "Needs Calibration" label, targets or drill texts over it
        assertTrue(app.arena.value!!.covered.value)

        sendFramesUntil { app.check.value == CheckState.Idle }

        assertFalse(app.arena.value!!.covered.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.camera.value!!.projectionBounds.get())
        assertNotNull(app.calibratedAt.value)
```

`TestShortcuts.kt` loses `f6DuringAProjectorDrillStopsItCalibratesAndStartsItAfresh` (`TestCalibrationPausesTheDrill` replaces it) and the imports only it used:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`:

Replace:

```kotlin
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.drill.DrillButton
import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestShortcuts {
    private val app = AppFixture.app()
```

with:

```kotlin
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.drill.DrillButton
import com.shootoff.compose.shell.Destination
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestShortcuts {
    private val app = AppFixture.app()
```

Replace:

```kotlin
            app.close()
        }
    }

    @Test
    fun f6DuringAProjectorDrillStopsItCalibratesAndStartsItAfresh() {
        val app = AppFixture.appWithCamera()
        try {
            app.openStartCamera()
            app.openArena()
            app.arena.value!!.setFullScreen(true)
            app.startCalibration()
            app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
            assertTrue(app.startDrill(AppFixture.projectorDrill))
            val first = app.runner.running.value!!.host

            app.handleKey(Key.F6, KeyEventType.KeyDown)
            assertNull(app.runner.running.value)
            assertTrue(app.calibration.value!!.state.value.calibrating)

            app.calibration.value!!.calibrate(Rect(102.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

            assertNotSame(first, app.runner.running.value!!.host)
            // F6 opened Setup, and a finished calibration stays there
            assertEquals(Destination.SETUP, app.destination.value)
        } finally {
            app.close()
        }
    }
}
```

with:

```kotlin
            app.close()
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plugin-api:test --tests '*TestExerciseHostSupport' :compose-app:test --tests 'com.shootoff.compose.app.TestCalibrationPausesTheDrill' --tests 'com.shootoff.compose.calibration.*' --tests 'com.shootoff.compose.arena.*' --continue --console=plain`

Expected: `plugin-api`'s `compileTestJava` FAILS with `cannot find symbol` for `isShotDetectionPaused()`, and `compose-app`'s `compileTestKotlin` with `Unresolved reference 'covered'` and `Unresolved reference 'cover'`.

- [ ] **Step 3: Whether the drill has detection paused**

`plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java`:

Replace:

```java
		detecting.accept(!paused);
	}

	// ---- Names

	/**
```

with:

```java
		detecting.accept(!paused);
	}

	/**
	 * @return whether the exercise has shot detection paused and is still running, so that something
	 *         turning detection back on for its own reasons (the end of a calibration) can leave it off
	 */
	public synchronized boolean isShotDetectionPaused() {
		return detectionPaused && !stopped;
	}

	// ---- Names

	/**
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`:

Replace:

```kotlin
    val name: String get() = support.exerciseName()

    // ---- Lifecycle, driven by the ExerciseRunner

    fun start() {
```

with:

```kotlin
    val name: String get() = support.exerciseName()

    /** Whether the exercise has paused shot detection (a paused drill does) */
    val shotDetectionPaused: Boolean get() = support.isShotDetectionPaused

    // ---- Lifecycle, driven by the ExerciseRunner

    fun start() {
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt`:

Replace:

```kotlin
/**
 * The Compose app's current exercise: at most one runs. Starting one stops the one before. Shots reach
 * it by surface: arena shots only a projector exercise, camera shots only a camera exercise. Calibration
 * stops a projector exercise and starts it afresh afterwards.
 *
 * @param isCalibrating whether the arena is currently calibrating; a projector drill refuses to start
 *   while it is, so no path (the Drills screen or otherwise) can start one under calibration's feet
```

with:

```kotlin
/**
 * The Compose app's current exercise: at most one runs. Starting one stops the one before. Shots reach
 * it by surface: arena shots only a projector exercise, camera shots only a camera exercise. Calibration
 * pauses a projector exercise (see AppState.pauseDrill); one with no Pause button is stopped through
 * [stopProjectorExercise] and started afresh afterwards.
 *
 * @param isCalibrating whether the arena is currently calibrating; a projector drill refuses to start
 *   while it is, so no path (the Drills screen or otherwise) can start one under calibration's feet
```

- [ ] **Step 4: The arena's cover, used by calibration and the check**

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`:

Replace:

```kotlin
    private val fullScreenState = MutableStateFlow(false)
    private val labelState = MutableStateFlow(true)
    private val gridState = MutableStateFlow(false)

    /** The arena window's size, in dp: the arena's coordinates */
    val size: StateFlow<Size> = sizeState.asStateFlow()
```

with:

```kotlin
    private val fullScreenState = MutableStateFlow(false)
    private val labelState = MutableStateFlow(true)
    private val gridState = MutableStateFlow(false)
    private val coveredState = MutableStateFlow(false)

    /** The arena window's size, in dp: the arena's coordinates */
    val size: StateFlow<Size> = sizeState.asStateFlow()
```

Replace:

```kotlin
    /** Whether the arena shows Setup's alignment grid in place of everything else */
    val grid: StateFlow<Boolean> = gridState.asStateFlow()

    @Volatile
    var perspective: PerspectiveManager? = null
```

with:

```kotlin
    /** Whether the arena shows Setup's alignment grid in place of everything else */
    val grid: StateFlow<Boolean> = gridState.asStateFlow()

    /**
     * Whether only the background shows, while a calibration pattern needs the arena: the targets, shot
     * markers, the "Needs calibration" label and the exercise's texts are left out, each keeping its own
     * state, so what a paused drill hid meanwhile stays hidden once the cover comes off.
     */
    val covered: StateFlow<Boolean> = coveredState.asStateFlow()

    @Volatile
    var perspective: PerspectiveManager? = null
```

Replace:

```kotlin
        gridState.value = show
    }

    /** Shows or hides every arena target, as calibration does. */
    fun setTargetsVisible(visible: Boolean) {
        for (target in targets.set.targets) targets.set.setVisible(target.id, visible)
```

with:

```kotlin
        gridState.value = show
    }

    fun cover(covered: Boolean) {
        coveredState.value = covered
    }

    /** Shows or hides every arena target, as calibration does. */
    fun setTargetsVisible(visible: Boolean) {
        for (target in targets.set.targets) targets.set.setVisible(target.id, visible)
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`:

Replace:

```kotlin
/**
 * The arena as both of its views draw it, fitted to the space it is given: the background stretched over
 * the arena, its targets and shot markers, the "Needs calibration" label, then [overlay] (the exercise's
 * texts and markers) in arena coordinates.
 */
@Composable
fun ArenaCanvas(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
```

with:

```kotlin
/**
 * The arena as both of its views draw it, fitted to the space it is given: the background stretched over
 * the arena, its targets and shot markers, the "Needs calibration" label, then [overlay] (the exercise's
 * texts and markers) in arena coordinates. While the arena is covered (a calibration pattern showing), only
 * the background is drawn.
 */
@Composable
fun ArenaCanvas(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
```

Replace:

```kotlin
    val background by arena.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val grid by arena.grid.collectAsState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Color.Black)) {
```

with:

```kotlin
    val background by arena.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val grid by arena.grid.collectAsState()
    val covered by arena.covered.collectAsState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Color.Black)) {
```

Replace:

```kotlin
                dstSize = IntSize(areaSize.width.roundToInt(), areaSize.height.roundToInt()),
            )
        }
        TargetLayer(arena.targets, transform)
        MarkerLayer(arena.markers, transform)
        if (label) {
            // The JavaFX arena's label: 48 px orange, centered in a 628x90 box at (6, 6)
            val topLeft = transform.toView(6.0, 6.0)
            with(density) {
```

with:

```kotlin
                dstSize = IntSize(areaSize.width.roundToInt(), areaSize.height.roundToInt()),
            )
        }
        if (!covered) {
            TargetLayer(arena.targets, transform)
            MarkerLayer(arena.markers, transform)
        }
        if (label && !covered) {
            // The JavaFX arena's label: 48 px orange, centered in a 628x90 box at (6, 6)
            val topLeft = transform.toView(6.0, 6.0)
            with(density) {
```

Replace:

```kotlin
                }
            }
        }
        overlay(transform)
        if (grid) AlignmentGrid(size, transform)
    }
}
```

with:

```kotlin
                }
            }
        }
        if (!covered) overlay(transform)
        if (grid) AlignmentGrid(size, transform)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
    // The arena's look before calibration: its background, targets, shots, and the label if uncalibrated
    private fun putArenaBack() {
        restoreArenaBackground()
        arena.setTargetsVisible(true)
        arena.showShots(settings.showArenaShotMarkers())
        arena.setCalibrationLabelVisible(arena.projection.value == null)
    }
```

with:

```kotlin
    // The arena's look before calibration: its background, targets, shots, and the label if uncalibrated
    private fun putArenaBack() {
        restoreArenaBackground()
        arena.cover(false)
        arena.showShots(settings.showArenaShotMarkers())
        arena.setCalibrationLabelVisible(arena.projection.value == null)
    }
```

Replace:

```kotlin
    override fun setCalibrating(calibrating: Boolean) = uiState.update { it.copy(calibrating = calibrating) }

    override fun calibrationStarted() {
        arena.setTargetsVisible(false)
        arena.setCalibrationLabelVisible(false)
    }
```

with:

```kotlin
    override fun setCalibrating(calibrating: Boolean) = uiState.update { it.copy(calibrating = calibrating) }

    // Covered rather than hidden: a paused drill keeps its targets as it left them (spec §8 Revision 2, decision 2)
    override fun calibrationStarted() {
        arena.cover(true)
        arena.setCalibrationLabelVisible(false)
    }
```

Replace:

```kotlin
    override fun calibrated(perspectiveManager: Optional<PerspectiveManager>) {
        arena.perspective = perspectiveManager.orElse(null)
        arena.setCalibrationLabelVisible(false)
        arena.setTargetsVisible(true)
        // Targets take their real-world sizes once the perspective is known
        if (perspectiveManager.isPresent) {
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
```

with:

```kotlin
    override fun calibrated(perspectiveManager: Optional<PerspectiveManager>) {
        arena.perspective = perspectiveManager.orElse(null)
        arena.setCalibrationLabelVisible(false)
        arena.cover(false)
        // Targets take their real-world sizes once the perspective is known
        if (perspectiveManager.isPresent) {
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`:

Replace:

```kotlin
    @Volatile
    private var job: Job? = null

    /** Shows the pattern and starts looking; on the UI thread. */
    fun start() {
        background = arena.background.value
        arena.showResource("pattern.png")
        camera.setDetecting(false)
        frames.take()
```

with:

```kotlin
    @Volatile
    private var job: Job? = null

    /** Shows the pattern, alone, and starts looking; on the UI thread. */
    fun start() {
        background = arena.background.value
        arena.cover(true)
        arena.showResource("pattern.png")
        camera.setDetecting(false)
        frames.take()
```

Replace:

```kotlin
        if (!finished.compareAndSet(false, true)) return
        job?.cancel()
        arena.setBackground(background)
        scheduler.schedule({ camera.setDetecting(true) }, CalibrationFlow.DETECTION_RESTART_DELAY)
        onDone(this, outcome)
    }
```

with:

```kotlin
        if (!finished.compareAndSet(false, true)) return
        job?.cancel()
        arena.setBackground(background)
        arena.cover(false)
        scheduler.schedule({ camera.setDetecting(true) }, CalibrationFlow.DETECTION_RESTART_DELAY)
        onDone(this, outcome)
    }
```

- [ ] **Step 5: Calibration pauses the drill in `AppState`**

`compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`:

Replace:

```kotlin
    }
}

/** Labels a drill's pause button may have: the par drill's are "Pause" and "Resume" */
private val PAUSE_LABELS = setOf("Pause", "Resume")

/**
 * Does what a shortcut does.
```

with:

```kotlin
    }
}

/** The par drill's pause button reads this while the drill runs */
const val PAUSE_LABEL = "Pause"

/** … and this while it is paused */
const val RESUME_LABEL = "Resume"

/** Labels a drill's pause button may have */
private val PAUSE_LABELS = setOf(PAUSE_LABEL, RESUME_LABEL)

/**
 * Does what a shortcut does.
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
```

with:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
```

Replace:

```kotlin
    }

    private fun runCheck(arena: ArenaModel, camera: CameraManager, saved: SavedCalibration) {
        val run = CalibrationCheckRun(saved, arena, camera, checkFrames, detector(camera), checkClock, scope, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread) { run, outcome -> checked(run, saved, outcome) }
        checkRun = run
```

with:

```kotlin
    }

    private fun runCheck(arena: ArenaModel, camera: CameraManager, saved: SavedCalibration) {
        val run = CalibrationCheckRun(saved, arena, CalibratingCamera(camera), checkFrames, detector(camera), checkClock, scope, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread) { run, outcome -> checked(run, saved, outcome) }
        checkRun = run
```

Replace:

```kotlin
    // camera opens (or becomes available) after the arena, which otherwise would leave Calibrate and F6
    // disabled until the arena is closed and reopened.
    private fun makeCalibratable(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(camera, arena, settings, runner, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread)
        camera.setCalibrationManager(controller)
```

with:

```kotlin
    // camera opens (or becomes available) after the arena, which otherwise would leave Calibrate and F6
    // disabled until the arena is closed and reopened.
    private fun makeCalibratable(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(CalibratingCamera(camera), arena, settings, drillForCalibration, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread)
        camera.setCalibrationManager(controller)
```

Replace:

```kotlin
    fun stopDrill() = runner.stop()

    /** Reset: the cameras, the arena's animations and the shots, then the drill, then a short pause in detection */
    fun reset() = rangeReset.reset { runner.reset() }
```

with:

```kotlin
    fun stopDrill() = runner.stop()

    /**
     * Pauses the running drill through its own Pause button, as F3 does, unless it is paused already (its
     * button reads Resume). The v2 exercise API has no pause of its own: the drill's button is the pause.
     *
     * @return false if no drill with a Pause button runs
     */
    fun pauseDrill(): Boolean {
        val buttons = drill.buttons.value
        if (buttons.any { it.label == RESUME_LABEL }) return true
        val pause = buttons.firstOrNull { it.label == PAUSE_LABEL } ?: return false
        pause.onClick()
        return true
    }

    // What calibration does to the running drill (spec §8 Revision 2, decision 2): a projector drill is paused,
    // and stays paused afterwards, never restarted; one with no Pause button is stopped and started afresh
    // after a success, as before. A camera drill doesn't use the arena and is left alone.
    private val drillForCalibration = CalibrationFlow.Exercises {
        val running = runner.running.value
        when {
            running == null || !running.host.isProjector -> Optional.empty()
            pauseDrill() -> Optional.empty()
            else -> runner.stopProjectorExercise()
        }
    }

    // The camera as calibration and the check see it: when they turn shot detection back on as they end,
    // it stays off while the running drill has it paused (a paused drill turned it off itself)
    private inner class CalibratingCamera(private val camera: CameraManager) : CalibrationCamera by camera {
        override fun setDetecting(isDetecting: Boolean) {
            camera.setDetecting(isDetecting && runner.running.value?.host?.shotDetectionPaused != true)
        }
    }

    /** Reset: the cameras, the arena's animations and the shots, then the drill, then a short pause in detection */
    fun reset() = rangeReset.reset { runner.reset() }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :plugin-api:test --tests '*TestExerciseHostSupport' :compose-app:test --tests 'com.shootoff.compose.app.TestCalibrationPausesTheDrill' --tests 'com.shootoff.compose.calibration.*' --tests 'com.shootoff.compose.arena.*' --tests 'com.shootoff.compose.app.TestRememberedCalibration' --tests 'com.shootoff.compose.app.TestShortcuts' --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `701/701 passing; 0 regressions; 0 new failures` (one `TestShortcuts` test deleted on purpose), and four `OK` lines.

- [ ] **Step 8: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java plugin-api/src/test/java/com/shootoff/exercise/host/TestExerciseHostSupport.java compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationPausesTheDrill.kt compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt
git commit -m "Pause the drill for calibration instead of restarting it"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 5: Losing or switching the camera keeps the arena open and pauses the drill

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`releaseCamera`, `publish`, `cameraLost`, new `detachCamera`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`

**Interfaces:**
- Consumes: Task 4's `AppState.pauseDrill()` and `AppFixture.pausingDrill`; Task 3's `calibrationComplete`; Plan 6's `CalibrationController.arenaClosing()`, `setupSteps`, `calibrationSummary`.
- Produces: the private `AppState.detachCamera()`, which Task 6's reconnect relies on (the arena is still open when the camera comes back). When a camera opens with the arena open and uncalibrated, and Remember is on with a saved calibration, the check runs (ruling 8).

**Why (decision 7).** This reverses Plan 5's rule that the arena closes with the camera that calibrated it. With the arena opening at launch and the drill pausing instead of resetting, closing the arena threw away exactly what the owner wanted kept.

- [ ] **Step 1: Change the tests that expected the arena to close, and add the camera's return**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`:

Replace:

```kotlin
    }

    @Test
    fun losingTheCameraClosesTheArenaItCalibrated() {
        app.openStartCamera()
        app.openArena()
        assertNotNull(app.calibration.value)

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertNull(app.arena.value)
        assertNull(app.calibration.value)
    }

    @Test
```

with:

```kotlin
    }

    @Test
    fun losingTheCameraKeepsTheArenaOpenUncalibratedAndPausesTheDrill() {
        val app = AppFixture.appWithCamera()
        try {
            AppFixture.setUpForProjectorDrills(app)
            assertTrue(app.startDrill(AppFixture.pausingDrill))
            awaitTrue { app.drill.buttons.value.any { it.label == "Pause" } }
            val host = app.runner.running.value!!.host

            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

            val arena = app.arena.value!!
            assertNull(arena.projection.value)
            assertTrue(arena.needsCalibrationLabel.value)
            assertNull(app.calibration.value)
            assertNull(app.calibratedAt.value)
            awaitTrue { app.drill.buttons.value.any { it.label == "Resume" } }
            assertSame(host, app.runner.running.value!!.host)
            assertEquals("No camera", calibrationSummary(app.camera.value != null, true, false, false, null, app.check.value))

            // Without Remember calibration, the camera coming back leaves Calibrate as the next step
            assertTrue(app.openCamera(AppFixture.TestCamera()))
            assertNotNull(app.calibration.value)
            assertEquals(StepState.NEXT, setupSteps(true, true, arena.projection.value != null).calibrate)
        } finally {
            app.close()
        }
    }

    @Test
```

Replace:

```kotlin
    }

    @Test
    fun switchingCamerasClosesTheArenaTheOldOneCalibrated() {
        app.openStartCamera()
        app.openArena()
        assertNotNull(app.calibration.value)

        assertTrue(app.openCamera(AppFixture.TestCamera("Other camera")))

        assertNull(app.arena.value)
        assertNull(app.calibration.value)
        assertEquals("Other camera", app.camera.value!!.camera.name)
    }
```

with:

```kotlin
    }

    @Test
    fun switchingCamerasKeepsTheArenaOpenUncalibratedAndCalibratableWithTheNewOne() {
        app.openStartCamera()
        app.openArena()
        val old = app.calibration.value
        assertNotNull(old)

        assertTrue(app.openCamera(AppFixture.TestCamera("Other camera")))

        assertNotNull(app.arena.value)
        assertNull(app.arena.value!!.projection.value)
        assertNotNull(app.calibration.value)
        assertNotSame(old, app.calibration.value)
        assertEquals("Other camera", app.camera.value!!.camera.name)
    }
```

Replace:

```kotlin
        // Returns while the camera's open() is still blocked
        app.openCameraInBackground(slow) { opened.set(it) }

        assertNull(app.arena.value)
        assertNull(app.camera.value)
        assertEquals("Slow camera", app.openingCamera.value)
```

with:

```kotlin
        // Returns while the camera's open() is still blocked
        app.openCameraInBackground(slow) { opened.set(it) }

        assertNotNull(app.arena.value)
        assertNull(app.calibration.value)
        assertNull(app.camera.value)
        assertEquals("Slow camera", app.openingCamera.value)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

Replace:

```kotlin
    }

    @Test
    fun unpluggingTheCameraMidCheckStopsItAndClosesTheArena() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertNull(app.arena.value)
        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.cameraView.frameTap)
    }

    @Test
```

with:

```kotlin
    }

    @Test
    fun unpluggingTheCameraMidCheckStopsItAndKeepsTheArenaOpen() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

        assertNotNull(app.arena.value)
        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.cameraView.frameTap)
        assertNull(app.arena.value!!.background.value)
        assertFalse(app.arena.value!!.covered.value)
    }

    @Test
    fun whenTheCameraComesBackTheSavedCalibrationIsCheckedAgain() {
        remembered()
        seen.set(Optional.of(Rect(102.0, 79.0, 399.0, 302.0)))
        openArenaOnTheProjector()
        sendFramesUntil { app.arena.value!!.projection.value != null }

        app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
        assertNull(app.arena.value!!.projection.value)
        assertTrue(app.arena.value!!.needsCalibrationLabel.value)

        // Plugged back in (the reconnect, or the owner's pick): the same camera, as a new device
        assertTrue(app.openCamera(AppFixture.TestCamera()))

        assertEquals(CheckState.Checking, app.check.value)
        sendFramesUntil { app.check.value == CheckState.Idle }
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    @Test
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestProblems' --tests 'com.shootoff.compose.app.TestRememberedCalibration' --console=plain`

Expected: 5 tests FAIL (the arena is closed, so `app.arena.value!!` throws or `assertNotNull` fails): `TestProblems.aCameraOpensOffTheCallingThreadAndSaysSoMeanwhile`, `losingTheCameraKeepsTheArenaOpenUncalibratedAndPausesTheDrill`, `switchingCamerasKeepsTheArenaOpenUncalibratedAndCalibratableWithTheNewOne`; `TestRememberedCalibration.unpluggingTheCameraMidCheckStopsItAndKeepsTheArenaOpen`, `whenTheCameraComesBackTheSavedCalibrationIsCheckedAgain`.

- [ ] **Step 3: The camera going detaches it from the arena instead of closing the arena**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    private fun closeDeviceLater(camera: Camera) = io.asExecutor().execute { closeDevice(camera) }

    // Closes the open camera's manager, and the arena it calibrated, as the JavaFX app's arena closes with its
    // camera (ruling 13). Returns the new open's generation and the camera whose device is still to close.
    private fun releaseCamera(): Pair<Int, Camera?> {
        val generation = openGeneration.incrementAndGet()
        openingState.value = null
        val old = cameraState.value
        if (old != null) closeArena()
        old?.let(cameras::clearManager)
        openView?.live = false
        openView = null
```

with:

```kotlin
    private fun closeDeviceLater(camera: Camera) = io.asExecutor().execute { closeDevice(camera) }

    // Closes the open camera's manager; the arena stays open, without the calibration that camera made (spec §8
    // Revision 2, decision 7). Returns the new open's generation and the camera whose device is still to close.
    private fun releaseCamera(): Pair<Int, Camera?> {
        val generation = openGeneration.incrementAndGet()
        openingState.value = null
        val old = cameraState.value
        if (old != null) detachCamera()
        old?.let(cameras::clearManager)
        openView?.live = false
        openView = null
```

Replace:

```kotlin
        openView = opened.view
        problemState.value = null
        cameraState.value = manager
        // The arena was already open with no camera to calibrate with (openArena found none); now one is
        // here, so the arena can be calibrated, as it could have been if the camera had come first
        arenaState.value?.let { arena ->
            if (calibrationState.value == null) makeCalibratable(arena, manager)
            // The remembered check was waiting on a camera too (spec §8): with one open now, and
            // makeCalibratable just above having made the arena calibratable, retry it rather than leaving
            // Setup stuck saying there was no camera to check with
            if (settings.rememberCalibration() && settings.savedCalibration.isPresent &&
                checkState.value == CheckState.NotVerified(CalibrationCheck.Reason.NO_CAMERA)
            ) {
                checkRemembered(arena)
            }
```

with:

```kotlin
        openView = opened.view
        problemState.value = null
        cameraState.value = manager
        // The arena was already open with no camera to calibrate with (opened before the camera, or kept open
        // when the last camera went); now one is here, so the arena can be calibrated
        arenaState.value?.let { arena ->
            if (calibrationState.value == null) makeCalibratable(arena, manager)
            // An uncalibrated arena is checked against the remembered calibration now that there is a camera
            // to check with (spec §8 Revision 2, decision 7); without Remember, Setup's Calibrate step is next
            if (settings.rememberCalibration() && settings.savedCalibration.isPresent &&
                arena.projection.value == null && checkState.value != CheckState.Checking
            ) {
                checkRemembered(arena)
            }
```

Replace:

```kotlin
    }

    // The camera stopped answering (reported on its thread, run here on the UI thread): if it is still the
    // open one, close it and the arena it calibrated, and show the picker, not its last frame
    private fun cameraLost(camera: Camera) {
        val manager = cameraState.value ?: return
        if (manager.camera !== camera) return
        closeArena()
        promptSkippedState.value = false
        cameraState.value = null
        cameras.clearManager(manager)
```

with:

```kotlin
    }

    // The camera stopped answering (reported on its thread, run here on the UI thread): if it is still the
    // open one, close it, and show the picker, not its last frame. The arena stays open (spec §8 Revision 2,
    // decision 7)
    private fun cameraLost(camera: Camera) {
        val manager = cameraState.value ?: return
        if (manager.camera !== camera) return
        detachCamera()
        promptSkippedState.value = false
        cameraState.value = null
        cameras.clearManager(manager)
```

Replace:

```kotlin
        closeDeviceLater(camera)
    }

    // ---- The arena

    /** The main window's top left corner, which tells the screen ShootOFF is on */
```

with:

```kotlin
        closeDeviceLater(camera)
    }

    // The open camera is going (lost, or replaced). The arena stays open (spec §8 Revision 2, decision 7), but
    // its calibration was made with that camera, so it goes: calibration or a check under way ends, the arena
    // says "Needs Calibration" again, and the running drill pauses, as it does for calibration.
    private fun detachCamera() {
        pauseDrill()
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        currentCalibration = null
        // For calibration the camera going is the arena going: calibration ends, and both projections go
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
        calibratedAtState.value = null
        calibrationCompleteState.value = null
        arena.setCalibrationLabelVisible(true)
    }

    // ---- The arena

    /** The main window's top left corner, which tells the screen ShootOFF is on */
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --console=plain`

Expected: `BUILD SUCCESSFUL`. (`TestRangeScreen.theNotReadyPromptOffersSetupAndSkipAndComesBackWhenTheArenaCloses` still passes: the prompt comes back when the camera drops, as before.)

- [ ] **Step 5: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `702/702 passing; 0 regressions; 0 new failures`, and four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt
git commit -m "Keep the arena open when the camera goes, and pause the drill"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 6: A lost camera reopens by itself when it is plugged back in

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`reconnectMillis`, `waitingFor`, `cameraLost`, `publish`, `close`), `compose-app/src/main/kotlin/com/shootoff/compose/app/NoCameraPanel.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt`

**Interfaces:**
- Consumes: Task 5's `detachCamera` (the arena stays open for the camera's return); Plan 5's `openCameraInBackground`, `CameraSource.cameras()`.
- Produces:
  - the constructor parameter `AppState(…, reconnectMillis: Long = 2000)` (additive; Task 8 adds `patternSettleMillis` after it)
  - `AppState.waitingFor: StateFlow<String?>`: the lost camera's name while it is awaited
  - the no-camera panel's tag `waiting-for-camera`: "Waiting for <name> to be plugged back in…"

**Why (decision 6).** Plan 5 left reconnecting out of scope; the owner wants the unplugged camera to come back without a click, with the picker still there for choosing another. Ruling 9 has the details.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`:

Replace:

```kotlin
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
```

with:

```kotlin
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
```

Replace:

```kotlin
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

with:

```kotlin
        }
    }

    // What is plugged in, for the reconnect: cameras come and go from it
    private class Pluggable(vararg cameras: Camera) : CameraSource {
        val plugged = CopyOnWriteArrayList(cameras.toList())

        override fun cameras(): List<Camera> = plugged.toList()

        override fun startCamera(settings: Settings) = plugged.firstOrNull()
    }

    private fun reconnectingApp(source: CameraSource) =
        AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() }, reconnectMillis = 20)

    @Test
    fun aLostCameraReopensByItselfWhenItIsPluggedBackIn() {
        val source = Pluggable(AppFixture.TestCamera("HD Webcam C270"))
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.openArena()

            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
            source.plugged.clear()
            assertEquals("HD Webcam C270", app.waitingFor.value)
            Thread.sleep(100)
            assertNull(app.camera.value)

            val back = AppFixture.TestCamera("HD Webcam C270")
            source.plugged.add(back)

            awaitTrue { app.camera.value != null }
            assertSame(back, app.camera.value!!.camera)
            assertNull(app.waitingFor.value)
            // Back on the arena it left, ready to calibrate
            assertNotNull(app.calibration.value)
        } finally {
            app.close()
        }
    }

    @Test
    fun pickingAnotherCameraWhileWaitingEndsTheWatch() {
        val lost = AppFixture.TestCamera("HD Webcam C270")
        val source = Pluggable(lost)
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.cameraProblems.showMissingCameraError(lost)
            source.plugged.clear()

            assertTrue(app.openCamera(AppFixture.TestCamera("Other camera")))
            assertNull(app.waitingFor.value)
            source.plugged.add(AppFixture.TestCamera("HD Webcam C270"))

            Thread.sleep(200)
            assertEquals("Other camera", app.camera.value!!.camera.name)
        } finally {
            app.close()
        }
    }

    @Test
    fun aCameraThatIsListedButWontOpenIsTriedOnceUntilItIsPluggedInAgain() {
        val source = Pluggable(AppFixture.TestCamera("HD Webcam C270"))
        val app = reconnectingApp(source)
        try {
            app.openStartCamera()
            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)
            // Still listed, but another program has it now
            val locked = object : AppFixture.TestCamera("HD Webcam C270") {
                val opens = AtomicInteger()

                override fun isOpen() = false

                override fun open(): Boolean {
                    opens.incrementAndGet()
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
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt`:

Replace:

```kotlin
        assertNull(app.runner.running.value)
    }

    @Test
    fun whileACameraOpensThePanelSaysSo() {
        show()
```

with:

```kotlin
        assertNull(app.runner.running.value)
    }

    @Test
    fun whileTheLostCameraIsAwaitedThePanelSaysSoAndStillOffersTheCameras() {
        // A long poll: this test looks at the panel while the app waits, before any reconnect
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() }, reconnectMillis = 60_000)
        try {
            app.openCamera(AppFixture.TestCamera())
            compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

            app.cameraProblems.showMissingCameraError(app.camera.value!!.camera)

            compose.onNodeWithTag("waiting-for-camera").assertExists()
            compose.onNodeWithText("Waiting for Test camera to be plugged back in…").assertExists()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("pick-Test camera").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            app.close()
        }
    }

    @Test
    fun whileACameraOpensThePanelSaysSo() {
        show()
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestProblems' --tests 'com.shootoff.compose.app.TestProblemViews' --console=plain`

Expected: `compileTestKotlin` FAILS with `No parameter with name 'reconnectMillis' found` and `Unresolved reference 'waitingFor'`.

- [ ] **Step 3: Watch for the lost camera, and say so on the panel**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.EventQueue
```

with:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.EventQueue
```

Replace:

```kotlin
 * @param wallClock the time of day, which the calibration status shows
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 */
class AppState(
    val settings: Settings,
```

with:

```kotlin
 * @param wallClock the time of day, which the calibration status shows
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 */
class AppState(
    val settings: Settings,
```

Replace:

```kotlin
    private val wallClock: () -> LocalTime = LocalTime::now,
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + background)
```

with:

```kotlin
    private val wallClock: () -> LocalTime = LocalTime::now,
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
    private val reconnectMillis: Long = 2000,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + background)
```

Replace:

```kotlin
    private val checkFrames = LatestFrame()
    private var checkRun: CalibrationCheckRun? = null
    private var checkWatch: Job? = null

    // The arena's calibration now, as it would be remembered; null while uncalibrated, or when it can't be
    // remembered (no projector screen)
```

with:

```kotlin
    private val checkFrames = LatestFrame()
    private var checkRun: CalibrationCheckRun? = null
    private var checkWatch: Job? = null
    private val waitingForState = MutableStateFlow<String?>(null)
    private var reconnectWatch: Job? = null

    // The arena's calibration now, as it would be remembered; null while uncalibrated, or when it can't be
    // remembered (no projector screen)
```

Replace:

```kotlin
    /** Why there is no camera, if something went wrong */
    val cameraProblem: StateFlow<String?> = problemState.asStateFlow()

    /** The name of the camera being opened in the background, or null */
    val openingCamera: StateFlow<String?> = openingState.asStateFlow()
```

with:

```kotlin
    /** Why there is no camera, if something went wrong */
    val cameraProblem: StateFlow<String?> = problemState.asStateFlow()

    /** The name of the camera that was lost and is watched for, to be reopened when plugged back in; or null */
    val waitingFor: StateFlow<String?> = waitingForState.asStateFlow()

    /** The name of the camera being opened in the background, or null */
    val openingCamera: StateFlow<String?> = openingState.asStateFlow()
```

Replace:

```kotlin
            }
            return false
        }
        cameras.addStartedCameraManager(manager)
        cameraView.setCameraManager(manager)
        openView = opened.view
```

with:

```kotlin
            }
            return false
        }
        // A camera is open, whichever: the lost one needn't be watched for any more
        stopWatchingForReturn()
        cameras.addStartedCameraManager(manager)
        cameraView.setCameraManager(manager)
        openView = opened.view
```

Replace:

```kotlin
        feed.clearFrame()
        problemState.value = cameraProblems.missingMessage(camera)
        closeDeviceLater(camera)
    }

    // The open camera is going (lost, or replaced). The arena stays open (spec §8 Revision 2, decision 7), but
```

with:

```kotlin
        feed.clearFrame()
        problemState.value = cameraProblems.missingMessage(camera)
        closeDeviceLater(camera)
        watchForReturn(camera.name)
    }

    // Looks for the lost camera every [reconnectMillis], in the background, and reopens it once it is plugged
    // back in (spec §8 Revision 2, decision 6). It is tried once each time it appears in the list: a camera
    // listed but refusing to open isn't retried until it is unplugged and plugged in again. The owner's own
    // pick, or any camera opening, ends the watch (publish); so does the app closing.
    private fun watchForReturn(name: String) {
        reconnectWatch?.cancel()
        waitingForState.value = name
        reconnectWatch = scope.launch(io) {
            var listed = false
            while (isActive) {
                delay(reconnectMillis)
                val back = try {
                    cameraSource.cameras().firstOrNull { it.name == name }
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
        reconnectWatch?.cancel()
        reconnectWatch = null
        waitingForState.value = null
    }

    // The open camera is going (lost, or replaced). The arena stays open (spec §8 Revision 2, decision 7), but
```

Replace:

```kotlin
    fun close() {
        // A camera still opening is closed when it finishes
        openGeneration.incrementAndGet()
        runner.stop()
        closeArena()
        cameras.closeAll()
```

with:

```kotlin
    fun close() {
        // A camera still opening is closed when it finishes
        openGeneration.incrementAndGet()
        stopWatchingForReturn()
        runner.stop()
        closeArena()
        cameras.closeAll()
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/NoCameraPanel.kt`:

Replace:

```kotlin
    val problem by app.cameraProblem.collectAsState()
    val opening by app.openingCamera.collectAsState()
    val cameras by app.cameraList.collectAsState()
    // Looked for again after each problem: an unplugged camera may be back
    LaunchedEffect(problem) { app.refreshCameras() }
    val colors = Range.colors
```

with:

```kotlin
    val problem by app.cameraProblem.collectAsState()
    val opening by app.openingCamera.collectAsState()
    val cameras by app.cameraList.collectAsState()
    val waitingFor by app.waitingFor.collectAsState()
    // Looked for again after each problem: an unplugged camera may be back
    LaunchedEffect(problem) { app.refreshCameras() }
    val colors = Range.colors
```

Replace:

```kotlin
                    Text("Opening camera $shownOpening…", color = colors.mutedStrong, modifier = Modifier.testTag("opening-camera"))
                } else {
                    Text(problem ?: "Pick the camera pointed at your target.", color = colors.mutedStrong)
                    val found = cameras
                    when {
                        found == null -> Text("Looking for cameras…", color = colors.muted)
```

with:

```kotlin
                    Text("Opening camera $shownOpening…", color = colors.mutedStrong, modifier = Modifier.testTag("opening-camera"))
                } else {
                    Text(problem ?: "Pick the camera pointed at your target.", color = colors.mutedStrong)
                    // The lost camera reopens by itself when it is plugged back in; another can be picked meanwhile
                    waitingFor?.let {
                        Text("Waiting for $it to be plugged back in…", color = colors.muted, modifier = Modifier.testTag("waiting-for-camera"))
                    }
                    val found = cameras
                    when {
                        found == null -> Text("Looking for cameras…", color = colors.muted)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestProblems' --tests 'com.shootoff.compose.app.TestProblemViews' --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `706/706 passing; 0 regressions; 0 new failures`, and four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/NoCameraPanel.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt
git commit -m "Reopen a lost camera by itself when it is plugged back in"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 7: The pattern measured as the median of five detections, against a tolerance that scales

**Files:**
- Create: `core/src/main/java/com/shootoff/calibration/PatternMeasurement.java`
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationCheck.java` (rewritten)
- Create (test): `core/src/test/java/com/shootoff/calibration/TestPatternMeasurement.java`
- Test: `core/src/test/java/com/shootoff/calibration/TestCalibrationCheck.java` (rewritten), `core/src/test/java/com/shootoff/camera/autocalibration/TestPatternDetector.java`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`

**Interfaces:**
- Consumes: Plan 6's `CalibrationCheck.Detector<F>` (unchanged, still nested in `CalibrationCheck`; `PatternDetector` implements it) and `CalibrationCheck.distance`.
- Produces (`com.shootoff.calibration`):
  - `public final class PatternMeasurement<F>`: `PatternMeasurement(String purpose, CalibrationCheck.Detector<F> detector, LongSupplier clock, Optional<Rect> reference)` (and a form with `int detections, long timeLimit`); `synchronized Optional<Result> offer(F)`, `tick()`, `result()`; `sealed interface Result permits Measured, NotSeen`; `record Measured(Rect median, int detections)`; `record NotSeen(int detections)`; `static Rect median(List<Rect>)`; `DEFAULT_DETECTIONS = 5`, `MIN_DETECTIONS = 3`, `DEFAULT_TIME_LIMIT = 8000`
  - `CalibrationCheck<F>`: the same `offer`/`tick`/`stop`/`outcome`; `record Kept(Rect detected, double distance, Rect bounds)` (was `Kept(Rect detected)`); `Moved` and `NotVerified` unchanged; `static Outcome judge(Rect saved, Rect measured)`; `static double tolerance(Rect saved)`; `MIN_TOLERANCE = 8.0`, `TOLERANCE_FRACTION = 0.02`; `DEFAULT_TIME_LIMIT` is now `PatternMeasurement.DEFAULT_TIME_LIMIT`; the constructor's `(…, double tolerance, long timeLimit)` form becomes `(…, int detections, long timeLimit)`, and `DEFAULT_TOLERANCE` goes

**Why (decision 3, its cause).** The saved bounds and the check both came from `AutoCalibrationManager.calibrateFrame` on a single frame, extrapolated from the inner chessboard corners and rounded, so each measurement jitters by 5–10 px; the owner's two calibrations of an unmoved setup were 10 px apart, past the 5 px tolerance. A median of five detections on both sides, and a tolerance of `max(8 px, 2%)` with "moved" only beyond twice it, fixes that (ruling 5). The app keeps working on this task's `Kept` unchanged: it still applies the saved bounds; Task 8 applies `Kept.bounds()`.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/calibration/TestPatternMeasurement.java` (new):

```java
package com.shootoff.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.calibration.PatternMeasurement.Measured;
import com.shootoff.calibration.PatternMeasurement.NotSeen;
import com.shootoff.geom.Rect;

class TestPatternMeasurement {
	// The owner's C270 finding the pattern of one unmoved projection five times: each detection is a few
	// pixels off the others (the estimator extrapolates from the inner corners and rounds)
	private static final Map<String, Optional<Rect>> FRAMES = Map.of(
			"no pattern", Optional.empty(),
			"first", Optional.of(new Rect(95, 59, 450, 262)),
			"second", Optional.of(new Rect(93, 56, 442, 262)),
			"third", Optional.of(new Rect(94, 58, 446, 262)),
			"fourth", Optional.of(new Rect(96, 57, 448, 262)),
			"fifth", Optional.of(new Rect(93, 59, 444, 262)));

	private long now = 5_000;
	private final List<String> looked = new ArrayList<>();

	private final PatternMeasurement<String> measurement = new PatternMeasurement<>("Test", frame -> {
		looked.add(frame);
		return FRAMES.get(frame);
	}, () -> now, Optional.empty());

	@Test
	void theMeasurementIsTheMedianOfFiveDetectionsEdgeByEdge() {
		assertEquals(Optional.empty(), measurement.offer("first"));
		assertEquals(Optional.empty(), measurement.offer("second"));
		assertEquals(Optional.empty(), measurement.offer("third"));
		assertEquals(Optional.empty(), measurement.offer("fourth"));

		final Optional<PatternMeasurement.Result> result = measurement.offer("fifth");

		// Left 93,93,94,95,96; top 56,57,58,59,59; right 535,537,540,544,545; bottom 318,319,320,321,321
		assertEquals(Optional.of(new Measured(new Rect(94, 58, 446, 262), 5)), result);
	}

	@Test
	void framesWithoutThePatternDontCount() {
		for (final String frame : List.of("first", "no pattern", "second", "third", "no pattern", "fourth")) {
			assertEquals(Optional.empty(), measurement.offer(frame));
		}

		assertEquals(Optional.of(new Measured(new Rect(94, 58, 446, 262), 5)), measurement.offer("fifth"));
	}

	@Test
	void atTheTimeLimitThreeDetectionsAreEnoughAndFewerAreNotSeen() {
		measurement.offer("first");
		measurement.offer("second");
		measurement.offer("third");
		now += PatternMeasurement.DEFAULT_TIME_LIMIT;

		assertEquals(Optional.of(new Measured(new Rect(94, 58, 446, 262), 3)), measurement.tick());

		final PatternMeasurement<String> dim = new PatternMeasurement<>("Test", FRAMES::get, () -> now, Optional.empty());
		dim.offer("first");
		dim.offer("no pattern");
		dim.offer("second");
		now += PatternMeasurement.DEFAULT_TIME_LIMIT - 1;
		assertEquals(Optional.empty(), dim.tick());
		now += 1;
		assertEquals(Optional.of(new NotSeen(2)), dim.tick());
	}

	@Test
	void onceMeasuredFramesArentLookedAt() {
		for (final String frame : List.of("first", "second", "third", "fourth", "fifth")) measurement.offer(frame);

		measurement.offer("first");

		assertEquals(List.of("first", "second", "third", "fourth", "fifth"), looked);
	}

	@Test
	void anEvenNumberOfDetectionsTakesTheMiddleTwosMean() {
		assertEquals(new Rect(94.5, 58.5, 447.5, 263),
				PatternMeasurement.median(List.of(new Rect(95, 59, 450, 262), new Rect(93, 56, 442, 262),
						new Rect(94, 58, 446, 264), new Rect(96, 60, 448, 262))));
	}
}
```

`core/src/test/java/com/shootoff/calibration/TestCalibrationCheck.java` (the whole file):

```java
package com.shootoff.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.calibration.CalibrationCheck.Kept;
import com.shootoff.calibration.CalibrationCheck.Moved;
import com.shootoff.calibration.CalibrationCheck.NotVerified;
import com.shootoff.calibration.CalibrationCheck.Outcome;
import com.shootoff.calibration.CalibrationCheck.Reason;
import com.shootoff.geom.Rect;

class TestCalibrationCheck {
	private static final Rect SAVED = new Rect(100, 80, 400, 300);

	// Fake frames: each names what the detector finds in it
	private static final Map<String, Optional<Rect>> FRAMES = Map.of(
			"no pattern", Optional.empty(),
			"pattern in place", Optional.of(new Rect(104, 77, 398, 302)),
			"pattern moved", Optional.of(new Rect(120, 80, 400, 300)),
			"pattern mid-change", Optional.of(new Rect(160, 120, 300, 200)));

	private long now = 5_000;
	private final List<String> looked = new ArrayList<>();

	private final CalibrationCheck<String> check = new CalibrationCheck<>(SAVED, frame -> {
		looked.add(frame);
		return FRAMES.get(frame);
	}, () -> now);

	// Offers <tt>frame</tt> until there is an outcome, or five times
	private Optional<Outcome> offerFiveTimes(CalibrationCheck<String> check, String frame) {
		Optional<Outcome> outcome = Optional.empty();
		for (int i = 0; i < PatternMeasurement.DEFAULT_DETECTIONS && outcome.isEmpty(); i++) outcome = check.offer(frame);
		return outcome;
	}

	@Test
	void withinTheToleranceTheSavedCalibrationIsKeptAsSaved() {
		assertEquals(Optional.empty(), check.offer("no pattern"));

		final Optional<Outcome> outcome = offerFiveTimes(check, "pattern in place");

		// 4 px from the saved bounds, inside the 8 px tolerance
		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302), 4.0, SAVED)), outcome);
	}

	@Test
	void theOwnersTwoCalibrationsOfAnUnmovedSetupAreNotAMove() {
		final Rect saved = new Rect(95, 59, 450, 262);
		final Rect measured = new Rect(93, 56, 442, 262);
		final CalibrationCheck<String> owners = new CalibrationCheck<>(saved, frame -> Optional.of(measured), () -> now);

		// 10 px apart (the right edge), past the 9 px tolerance (2% of 450) but not twice it: kept, at the
		// fresh measurement
		assertEquals(Optional.of(new Kept(measured, 10.0, measured)), offerFiveTimes(owners, "any"));
	}

	@Test
	void clearlyBeyondTheToleranceItMoved() {
		final Optional<Outcome> outcome = offerFiveTimes(check, "pattern moved");

		assertEquals(Optional.of(new Moved(new Rect(120, 80, 400, 300), 20.0)), outcome);
	}

	@Test
	void aFrameCaughtMidChangeDoesntSwayTheMeasurement() {
		assertEquals(Optional.empty(), check.offer("pattern mid-change"));

		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302), 4.0, SAVED)),
				offerFiveTimes(check, "pattern in place"));
	}

	@Test
	void withNoPatternInTimeItIsNotVerifiedAndStopsLooking() {
		now += CalibrationCheck.DEFAULT_TIME_LIMIT - 1;
		assertEquals(Optional.empty(), check.tick());
		assertEquals(Optional.empty(), check.offer("no pattern"));

		now += 1;
		assertEquals(Optional.of(new NotVerified(Reason.PATTERN_NOT_SEEN)), check.tick());

		// A frame arriving afterwards isn't even looked at
		assertEquals(Optional.of(new NotVerified(Reason.PATTERN_NOT_SEEN)), check.offer("pattern in place"));
		assertEquals(List.of("no pattern"), looked);
	}

	@Test
	void stoppingGivesItsReasonUnlessThereIsAlreadyAnOutcome() {
		assertEquals(new NotVerified(Reason.NO_CAMERA), check.stop(Reason.NO_CAMERA));
		assertEquals(new NotVerified(Reason.NO_CAMERA), check.stop(Reason.CANCELLED));

		final CalibrationCheck<String> kept = new CalibrationCheck<>(SAVED, FRAMES::get, () -> now);
		offerFiveTimes(kept, "pattern in place");
		assertTrue(kept.stop(Reason.CANCELLED) instanceof Kept);
	}

	@Test
	void theDistanceIsTheFurthestEdge() {
		assertEquals(0.0, CalibrationCheck.distance(SAVED, SAVED));
		// Wider by 14 on the right, 3 higher at the top
		assertEquals(14.0, CalibrationCheck.distance(SAVED, new Rect(100, 77, 414, 303)));
	}

	@Test
	void theToleranceScalesWithThePattern() {
		assertEquals(8.0, CalibrationCheck.tolerance(SAVED));
		assertEquals(9.0, CalibrationCheck.tolerance(new Rect(95, 59, 450, 262)));
		assertEquals(20.0, CalibrationCheck.tolerance(new Rect(0, 0, 1000, 600)));
		assertEquals(8.0, CalibrationCheck.tolerance(new Rect(0, 0, 100, 80)));
	}
}
```

`core/src/test/java/com/shootoff/camera/autocalibration/TestPatternDetector.java`:

Replace:

```java
		final Optional<Rect> found = detector().detect(frame(projected));

		assertTrue(found.isPresent());
		assertTrue(CalibrationCheck.distance(projected, found.get()) <= CalibrationCheck.DEFAULT_TOLERANCE,
				() -> "found " + found.get());
	}

	@Test
```

with:

```java
		final Optional<Rect> found = detector().detect(frame(projected));

		assertTrue(found.isPresent());
		// On a clean, synthetic frame one detection lands within 5 px; a real camera's jitter is why the check
		// takes a median and allows more (CalibrationCheck.tolerance)
		assertTrue(CalibrationCheck.distance(projected, found.get()) <= 5.0, () -> "found " + found.get());
	}

	@Test
```

The app's moved-projection test moves the pattern clearly beyond twice the tolerance:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

Replace:

```kotlin
    @Test
    fun aMovedProjectionIsReportedAndTheArenaStaysUncalibrated() {
        remembered()
        seen.set(Optional.of(Rect(114.0, 80.0, 400.0, 300.0)))

        openArenaOnTheProjector()
        sendFramesUntil { app.check.value is CheckState.Moved }

        assertEquals(CheckState.Moved(14), app.check.value)
        assertNull(app.arena.value!!.projection.value)
        assertNull(app.calibratedAt.value)
        assertNull(app.arena.value!!.background.value)
```

with:

```kotlin
    @Test
    fun aMovedProjectionIsReportedAndTheArenaStaysUncalibrated() {
        remembered()
        // 20 px: more than twice the 8 px tolerance for this 400 px pattern
        seen.set(Optional.of(Rect(120.0, 80.0, 400.0, 300.0)))

        openArenaOnTheProjector()
        sendFramesUntil { app.check.value is CheckState.Moved }

        assertEquals(CheckState.Moved(20), app.check.value)
        assertNull(app.arena.value!!.projection.value)
        assertNull(app.calibratedAt.value)
        assertNull(app.arena.value!!.background.value)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.*' --tests 'com.shootoff.camera.autocalibration.TestPatternDetector' --console=plain`

Expected: `compileTestJava` FAILS with `package com.shootoff.calibration.PatternMeasurement does not exist`.

- [ ] **Step 3: Write the measurement**

`core/src/main/java/com/shootoff/calibration/PatternMeasurement.java` (new):

```java
/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.geom.Rect;

/**
 * Measures where the calibration pattern is on the camera's feed: the median, edge by edge, of several
 * detections. One detection jitters by 5–10 px from the next (the pattern's outer edges are extrapolated
 * from its inner corners and rounded), so a single frame can't tell a moved projection from noise; the
 * median of five can. Both sides of the remembered calibration's check are measured this way: the bounds
 * saved after a calibration, and the pattern found when they are checked.
 * <p>
 * Like {@link CalibrationCheck}, it keeps no threads or timers: the caller offers frames and calls
 * {@link #tick()}, from any thread, and the clock decides the time limit. The first result is final. Each
 * detection, and the result, is logged at INFO.
 *
 * @param <F>
 *            a camera frame
 */
public final class PatternMeasurement<F> {
	private static final Logger logger = LoggerFactory.getLogger(PatternMeasurement.class);

	/** How many detections make a measurement */
	public static final int DEFAULT_DETECTIONS = 5;
	/** How many are enough once the time is up */
	public static final int MIN_DETECTIONS = 3;
	/**
	 * How long the pattern is looked for, in milliseconds: a detection takes about 400–500 ms on the owner's
	 * machine, so five take two to three seconds, with room for frames that miss it
	 */
	public static final long DEFAULT_TIME_LIMIT = 8000;

	public sealed interface Result permits Measured, NotSeen {}

	/**
	 * @param median
	 *            the median of the detections, edge by edge
	 * @param detections
	 *            how many it was taken from
	 */
	public record Measured(Rect median, int detections) implements Result {}

	/** Too few detections in time (projector off, camera pointed elsewhere, poor light) */
	public record NotSeen(int detections) implements Result {}

	private final String purpose;
	private final CalibrationCheck.Detector<F> detector;
	private final LongSupplier clock;
	private final Optional<Rect> reference;
	private final int detections;
	private final long timeLimit;
	private final long startedAt;

	private final List<Rect> found = new ArrayList<>();
	private Result result = null;

	/**
	 * Starts the time limit now, by <tt>clock</tt>.
	 *
	 * @param purpose
	 *            what the measurement is for, in its log lines
	 * @param reference
	 *            bounds each detection's distance from is logged (the saved ones), if any
	 */
	public PatternMeasurement(String purpose, CalibrationCheck.Detector<F> detector, LongSupplier clock,
			Optional<Rect> reference) {
		this(purpose, detector, clock, reference, DEFAULT_DETECTIONS, DEFAULT_TIME_LIMIT);
	}

	public PatternMeasurement(String purpose, CalibrationCheck.Detector<F> detector, LongSupplier clock,
			Optional<Rect> reference, int detections, long timeLimit) {
		this.purpose = purpose;
		this.detector = detector;
		this.clock = clock;
		this.reference = reference;
		this.detections = detections;
		this.timeLimit = timeLimit;
		startedAt = clock.getAsLong();
	}

	/**
	 * Looks for the pattern in <tt>frame</tt>, unless the measurement is over.
	 *
	 * @return the result, once there is one
	 */
	public synchronized Optional<Result> offer(F frame) {
		if (result != null || timedOut()) return tick();

		final long before = System.nanoTime();
		final Optional<Rect> detected = detector.detect(frame);
		final long took = (System.nanoTime() - before) / 1_000_000;

		if (detected.isEmpty()) {
			logger.info("{}: no pattern in this frame ({} ms)", purpose, took);
			return Optional.empty();
		}

		final Rect bounds = detected.get();
		found.add(bounds);
		if (reference.isPresent()) {
			logger.info("{}: detection {}/{} at {}, {} px from the saved bounds ({} ms)", purpose, found.size(),
					detections, describe(bounds), round(CalibrationCheck.distance(bounds, reference.get())), took);
		} else {
			logger.info("{}: detection {}/{} at {} ({} ms)", purpose, found.size(), detections, describe(bounds), took);
		}

		if (found.size() >= detections) finish(new Measured(median(found), found.size()));

		return Optional.ofNullable(result);
	}

	/**
	 * Ends the measurement if its time is up: with the median of what was found, if that is at least
	 * {@link #MIN_DETECTIONS}.
	 *
	 * @return the result, once there is one
	 */
	public synchronized Optional<Result> tick() {
		if (result == null && timedOut()) {
			finish(found.size() >= Math.min(MIN_DETECTIONS, detections) ? new Measured(median(found), found.size())
					: new NotSeen(found.size()));
		}
		return Optional.ofNullable(result);
	}

	public synchronized Optional<Result> result() {
		return Optional.ofNullable(result);
	}

	private void finish(Result result) {
		this.result = result;
		if (result instanceof Measured measured) {
			logger.info("{}: measured {} from {} detections", purpose, describe(measured.median()), measured.detections());
		} else {
			logger.info("{}: the pattern wasn't seen ({} detections in {} ms)", purpose, found.size(), timeLimit);
		}
	}

	private boolean timedOut() {
		return clock.getAsLong() - startedAt >= timeLimit;
	}

	/**
	 * @return the median of <tt>rects</tt>' left, top, right and bottom edges, each on its own (the mean of
	 *         the middle two for an even number)
	 */
	public static Rect median(List<Rect> rects) {
		final double left = median(rects, Rect::getMinX);
		final double top = median(rects, Rect::getMinY);
		final double right = median(rects, Rect::getMaxX);
		final double bottom = median(rects, Rect::getMaxY);
		return new Rect(left, top, right - left, bottom - top);
	}

	private static double median(List<Rect> rects, ToDoubleFunction<Rect> edge) {
		final double[] values = rects.stream().mapToDouble(edge).sorted().toArray();
		final int middle = values.length / 2;
		return values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
	}

	/** A rectangle as the log shows it: left,top width×height */
	static String describe(Rect rect) {
		return round(rect.getMinX()) + "," + round(rect.getMinY()) + " " + round(rect.getWidth()) + "×"
				+ round(rect.getHeight());
	}

	private static String round(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : String.format("%.1f", value);
	}
}
```

- [ ] **Step 4: The check measures, then judges**

`core/src/main/java/com/shootoff/calibration/CalibrationCheck.java` (the whole file):

```java
/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.calibration;

import java.util.Optional;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.geom.Rect;

/**
 * Checks a remembered calibration against the projection the camera sees now. While the calibration
 * pattern shows on the arena, the caller offers camera frames; they are measured with a
 * {@link PatternMeasurement} (the median of five detections, as the saved bounds were) and the measurement
 * compared with the saved bounds, against a tolerance that scales with the pattern ({@link #tolerance}):
 * <ul>
 * <li>within the tolerance: the calibration is {@link Kept}, as saved;</li>
 * <li>beyond it, but not twice it: measurement noise, or a nudge too small to matter; {@link Kept}, at the
 * fresh measurement;</li>
 * <li>more than twice the tolerance: the projection has {@link Moved};</li>
 * <li>too few detections within the time limit, the camera gone, or the check cancelled:
 * {@link NotVerified}.</li>
 * </ul>
 * The first outcome is final, and logged at INFO. The check keeps no threads or timers of its own: the
 * caller offers frames and calls {@link #tick()}, from any thread, and the clock decides the time limit.
 *
 * @param <F>
 *            a camera frame
 */
public final class CalibrationCheck<F> {
	private static final Logger logger = LoggerFactory.getLogger(CalibrationCheck.class);

	/** The smallest tolerance, in camera pixels */
	public static final double MIN_TOLERANCE = 8.0;
	/** The tolerance's share of the saved pattern's larger side */
	public static final double TOLERANCE_FRACTION = 0.02;
	/** How long the check waits to measure the pattern, in milliseconds */
	public static final long DEFAULT_TIME_LIMIT = PatternMeasurement.DEFAULT_TIME_LIMIT;

	/** Finds the calibration pattern in a camera frame */
	@FunctionalInterface
	public interface Detector<F> {
		Optional<Rect> detect(F frame);
	}

	public sealed interface Outcome permits Kept, Moved, NotVerified {}

	/**
	 * The saved calibration still fits.
	 *
	 * @param detected
	 *            the pattern as measured now
	 * @param distance
	 *            how far its furthest edge is from the saved one, in camera pixels
	 * @param bounds
	 *            the calibration to use: the saved bounds when within the tolerance, else the fresh measurement
	 */
	public record Kept(Rect detected, double distance, Rect bounds) implements Outcome {}

	/**
	 * The projection is somewhere else now.
	 *
	 * @param distance
	 *            how far the furthest edge moved, in camera pixels
	 */
	public record Moved(Rect detected, double distance) implements Outcome {}

	public record NotVerified(Reason reason) implements Outcome {}

	public enum Reason {
		/** The pattern wasn't seen in time (projector off, camera pointed elsewhere, poor light) */
		PATTERN_NOT_SEEN,
		/** No camera to look with, or it went away */
		NO_CAMERA,
		/** The user, or something that replaced the check, stopped it */
		CANCELLED
	}

	private final Rect saved;
	private final PatternMeasurement<F> measurement;

	private Outcome outcome = null;

	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock) {
		this(saved, detector, clock, PatternMeasurement.DEFAULT_DETECTIONS, DEFAULT_TIME_LIMIT);
	}

	/**
	 * Starts the time limit now, by <tt>clock</tt>.
	 */
	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock, int detections, long timeLimit) {
		this.saved = saved;
		measurement = new PatternMeasurement<>("Calibration check", detector, clock, Optional.of(saved), detections,
				timeLimit);
	}

	/**
	 * Looks for the pattern in <tt>frame</tt>, unless the check is over.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> offer(F frame) {
		if (outcome != null) return Optional.of(outcome);
		measurement.offer(frame).ifPresent(this::judge);
		return Optional.ofNullable(outcome);
	}

	/**
	 * Ends the check if its time is up.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> tick() {
		if (outcome == null) measurement.tick().ifPresent(this::judge);
		return Optional.ofNullable(outcome);
	}

	/**
	 * Stops the check, unless it is already over.
	 *
	 * @return the outcome: <tt>reason</tt>, or the one it already had
	 */
	public synchronized Outcome stop(Reason reason) {
		if (outcome == null) outcome = new NotVerified(reason);
		return outcome;
	}

	public synchronized Optional<Outcome> outcome() {
		return Optional.ofNullable(outcome);
	}

	private void judge(PatternMeasurement.Result result) {
		if (result instanceof PatternMeasurement.Measured measured) {
			outcome = judge(saved, measured.median());
		} else {
			outcome = new NotVerified(Reason.PATTERN_NOT_SEEN);
			logger.info("Calibration check: not verified, the pattern wasn't seen");
		}
	}

	/**
	 * @return what <tt>measured</tt> says about <tt>saved</tt>: kept as saved within the tolerance, kept at
	 *         <tt>measured</tt> up to twice it, moved beyond that
	 */
	public static Outcome judge(Rect saved, Rect measured) {
		final double distance = distance(measured, saved);
		final double tolerance = tolerance(saved);
		final Outcome outcome;
		if (distance <= tolerance) {
			outcome = new Kept(measured, distance, saved);
		} else if (distance <= 2 * tolerance) {
			outcome = new Kept(measured, distance, measured);
		} else {
			outcome = new Moved(measured, distance);
		}
		logger.info("Calibration check: measured {}, {} px from the saved {} (tolerance {} px): {}",
				PatternMeasurement.describe(measured), String.format("%.1f", distance),
				PatternMeasurement.describe(saved), String.format("%.1f", tolerance),
				outcome instanceof Moved ? "moved" : distance <= tolerance ? "kept" : "kept at the new measurement");
		return outcome;
	}

	/**
	 * @return how far an edge may be from the saved one, in camera pixels: the larger of
	 *         {@link #MIN_TOLERANCE} and {@link #TOLERANCE_FRACTION} of the saved pattern's larger side
	 */
	public static double tolerance(Rect saved) {
		return Math.max(MIN_TOLERANCE, TOLERANCE_FRACTION * Math.max(saved.getWidth(), saved.getHeight()));
	}

	/**
	 * @return how far apart the furthest pair of matching edges are
	 */
	public static double distance(Rect a, Rect b) {
		return Math.max(Math.max(Math.abs(a.getMinX() - b.getMinX()), Math.abs(a.getMaxX() - b.getMaxX())),
				Math.max(Math.abs(a.getMinY() - b.getMinY()), Math.abs(a.getMaxY() - b.getMaxY())));
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.*' --tests 'com.shootoff.camera.autocalibration.TestPatternDetector' :compose-app:test --tests 'com.shootoff.compose.app.TestRememberedCalibration' --tests 'com.shootoff.compose.app.TestNothingBlocks' --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `713/713 passing; 0 regressions; 0 new failures`, and four `OK` lines.

- [ ] **Step 7: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add core/src/main/java/com/shootoff/calibration/PatternMeasurement.java core/src/main/java/com/shootoff/calibration/CalibrationCheck.java core/src/test/java/com/shootoff/calibration/TestPatternMeasurement.java core/src/test/java/com/shootoff/calibration/TestCalibrationCheck.java core/src/test/java/com/shootoff/camera/autocalibration/TestPatternDetector.java compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt
git commit -m "Measure the pattern as the median of five detections, with a tolerance that scales"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 8: The remembered calibration measured the check's way, and only on the projector

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt` (`CalibrationCheckRun` becomes `PatternRun`; `CheckState.Measuring`), `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt`, `compose-app/src/main/resources/logback.xml`
- Create (test): `compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaWindow.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`

**Interfaces:**
- Consumes: Task 7's `PatternMeasurement`, `CalibrationCheck.Kept.bounds()`; Task 4's `ArenaModel.cover` and `AppState.CalibratingCamera`; Plan 6's `LatestFrame`, `ComposeCameraView.frameTap`, `savedCalibrationMismatch`.
- Produces:
  - `interface PatternWork<T : Any>` (`offer(frame: BufferedImage): T?`, `tick(): T?`), `CalibrationCheck<BufferedImage>.work()`, `PatternMeasurement<BufferedImage>.work()`
  - `class PatternRun<T : Any>(arena, camera: CalibrationCamera, frames: LatestFrame, work: () -> PatternWork<T>, scope, scheduler, uiThread, onDone: (PatternRun<T>, T?) -> Unit)` with `start()` and `stop()`; it replaces `CalibrationCheckRun`
  - `CheckState.Measuring` ("Measuring the calibration for next time…") and `val CheckState.showsPattern: Boolean` (Checking or Measuring)
  - `CalibrationViews.calibrationFinishedByCamera()` (a default no-op), called by `CalibrationController` once an auto-calibration has finished
  - `fun fillsItsScreen(requested: Boolean, content: Size, screen: Size): Boolean` in `ArenaWindow.kt`
  - `AppState(…, patternSettleMillis: Long = PATTERN_SETTLE_MILLIS)` and `AppState.PATTERN_SETTLE_MILLIS = 500`
  - `AppFixture.appWithCamera(…, patternSettleMillis: Long = 0)` and `AppFixture.putOnTheProjector(app)` (the arena 1280×720 and full screen)

**Why (decision 3, the rest).** Both sides of the check must be measured the same way, so after an auto-calibration with Remember on the pattern is measured once more and the median saved (ruling 7). The check applies and saves a small drift (ruling 5). And the pattern is measured only once the arena really fills the projector and has settled (ruling 6): `ArenaWindow` used to report full screen as soon as it was asked for, so the check (and auto-calibration) could measure a pattern still drawn in the 640×480 window. Detection stays off while any pattern shows, which folds in Plan 6's deferred N1.

- [ ] **Step 1: Write the failing tests, and put the check's arena on the projector**

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

Replace:

```kotlin
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
```

with:

```kotlin
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
```

Replace:

```kotlin
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
        wallClock: () -> LocalTime = LocalTime::now,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
```

with:

```kotlin
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
        wallClock: () -> LocalTime = LocalTime::now,
        patternSettleMillis: Long = 0,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
```

Replace:

```kotlin
            wallClock = wallClock,
            detector = { detector },
            checkClock = checkClock,
        )
    }
```

with:

```kotlin
            wallClock = wallClock,
            detector = { detector },
            checkClock = checkClock,
            patternSettleMillis = patternSettleMillis,
        )
    }
```

Replace:

```kotlin
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
    }

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
```

with:

```kotlin
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
    }

    /**
     * The arena window on the owner's projector, as the window manager leaves it: full screen, and filling
     * the 1280x720 screen (the remembered calibration's check and measurement wait for both)
     */
    fun putOnTheProjector(app: AppState) {
        val arena = app.arena.value!!
        arena.setSize(Size(1280.0, 720.0))
        arena.setFullScreen(true)
    }

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

Replace:

```kotlin
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.config.SavedCalibration
```

with:

```kotlin
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.config.SavedCalibration
```

Replace:

```kotlin
            looks.incrementAndGet()
            seen.get()
        },
    ) = AppFixture.appWithCamera(settings, detector = detector, checkClock = now::get)

    @AfterEach
    fun close() = app.close()
```

with:

```kotlin
            looks.incrementAndGet()
            seen.get()
        },
        patternSettleMillis: Long = 0,
    ) = AppFixture.appWithCamera(settings, detector = detector, checkClock = now::get, patternSettleMillis = patternSettleMillis)

    @AfterEach
    fun close() = app.close()
```

Replace:

```kotlin
    private fun openArenaOnTheProjector() {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
    }

    // The camera sends frames until the check has its outcome
```

with:

```kotlin
    private fun openArenaOnTheProjector() {
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
    }

    // The camera sends frames until the check has its outcome
```

Replace:

```kotlin
        calibrateWithTheCamera(Rect(120.0, 90.0, 400.0, 300.0))

        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertEquals(Rect(120.0, 90.0, 400.0, 300.0), app.arena.value!!.projection.value)
```

with:

```kotlin
        calibrateWithTheCamera(Rect(120.0, 90.0, 400.0, 300.0))

        // With Remember on, the new calibration is measured next; once that ends, the arena's own
        // background is back, not the check's pattern
        assertEquals(CheckState.Measuring, app.check.value)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        app.cancelCheck()
        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertEquals(Rect(120.0, 90.0, 400.0, 300.0), app.arena.value!!.projection.value)
```

Replace:

```kotlin
        app.openStartCamera()
        assertEquals(CheckState.Checking, app.check.value)

        app.arena.value!!.setFullScreen(true)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        sendFramesUntil { app.check.value == CheckState.Idle }
```

with:

```kotlin
        app.openStartCamera()
        assertEquals(CheckState.Checking, app.check.value)

        AppFixture.putOnTheProjector(app)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
        sendFramesUntil { app.check.value == CheckState.Idle }
```

Replace:

```kotlin
        assertNotNull(app.calibratedAt.value)
    }

    @Test
    fun turningRememberOnWhenItsAlreadyOnIsANoOpAndKeepsTheSavedCalibration() {
        remembered()
```

with:

```kotlin
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

        app.startCalibration()
        // The box, where the owner left it, as Done hands it to the flow
        app.calibration.value!!.flow.calibrated(Rect(90.0, 70.0, 420.0, 310.0), Optional.empty(), true)

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
        seen.set(Optional.of(Rect(110.0, 80.0, 400.0, 300.0)))

        openArenaOnTheProjector()
        sendFramesUntil { app.check.value == CheckState.Idle }

        assertEquals(Rect(110.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals("110.0,80.0,400.0,300.0", savedKeys()["shootoff.arena.calibration.bounds"])
    }

    @Test
    fun theCheckWaitsForTheArenaToFillTheProjectorAndSettle() {
        remembered()
        app.close()
        app = appOn(Settings(file.path, arrayOf()), patternSettleMillis = 300)
        app.openStartCamera()
        app.openArena()
        val arena = app.arena.value!!

        // Full screen asked for, but the window manager hasn't resized the 640x480 window yet
        arena.setFullScreen(true)
        Thread.sleep(500)
        assertNull(arena.background.value)

        arena.setSize(Size(1280.0, 720.0))
        Thread.sleep(100)
        assertNull(arena.background.value)
        awaitTrue { arena.background.value?.name == "pattern.png" }
        assertEquals(CheckState.Checking, app.check.value)
    }

    @Test
    fun aFastFullScreenFlapNeverTurnsDetectionOnUnderTheNextPattern() {
        remembered()
        openArenaOnTheProjector()
        val camera = app.camera.value!!
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        // F11 twice, well within the 600 ms the stopped run waits to turn detection back on
        app.arena.value!!.setFullScreen(false)
        awaitTrue { app.arena.value!!.background.value == null }
        app.arena.value!!.setFullScreen(true)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        Thread.sleep(CalibrationFlow.DETECTION_RESTART_DELAY + 300)
        assertEquals(CheckState.Checking, app.check.value)
        assertFalse(camera.isDetecting)
    }

    @Test
    fun turningRememberOnWhenItsAlreadyOnIsANoOpAndKeepsTheSavedCalibration() {
        remembered()
```

`compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaWindow.kt` (new):

```kotlin
package com.shootoff.compose.arena

import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestArenaWindow {
    private val projector = Size(1280.0, 720.0)

    @Test
    fun theArenaIsFullScreenOnlyOnceItFillsItsScreen() {
        // Asked for, but the window manager hasn't resized the window yet
        assertFalse(fillsItsScreen(true, Size(640.0, 480.0), projector))
        assertTrue(fillsItsScreen(true, Size(1280.0, 720.0), projector))
        // Rounding in the window's size in dp
        assertTrue(fillsItsScreen(true, Size(1279.5, 720.0), projector))
        // A window as big as the screen, but not asked to be full screen (a maximized one)
        assertFalse(fillsItsScreen(false, Size(1280.0, 720.0), projector))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`:

Replace:

```kotlin
        step(Step.CALIBRATE, StepState.DONE)
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
```

with:

```kotlin
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
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`:

Replace:

```kotlin
    }

    @Test
    fun rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAfterThreeSeconds() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
```

with:

```kotlin
    }

    @Test
    fun rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAtItsTimeLimit() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
```

Replace:

```kotlin
        try {
            app.openStartCamera()
            app.openArena()
            app.arena.value!!.setFullScreen(true)
            awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
            app.cameraView.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR), Optional.empty())
            awaitTrue { lookedOn.get() != null }
```

with:

```kotlin
        try {
            app.openStartCamera()
            app.openArena()
            AppFixture.putOnTheProjector(app)
            awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
            app.cameraView.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR), Optional.empty())
            awaitTrue { lookedOn.get() != null }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestRememberedCalibration' --tests 'com.shootoff.compose.arena.TestArenaWindow' --tests 'com.shootoff.compose.app.TestSetupScreen' --tests 'com.shootoff.compose.app.TestNothingBlocks' --console=plain`

Expected: `compileTestKotlin` FAILS with `No parameter with name 'patternSettleMillis' found`, `Unresolved reference 'Measuring'` and `Unresolved reference 'fillsItsScreen'`.

- [ ] **Step 3: One pattern run for the check and the measurement**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`, the whole file after the change (`CalibrationCheckRun` is replaced by `PatternWork` and `PatternRun`; `CheckState` gains `Measuring` and `showsPattern`):

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt` (the whole file):

```kotlin
/*
 * ShootOFF - Software for Laser Dry Fire Training
 * Copyright (C) 2016 phrack
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.PatternMeasurement
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.SavedCalibration
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Where the check of a remembered calibration stands */
sealed interface CheckState {
    /** No check, or the last one kept the calibration */
    data object Idle : CheckState

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
        Reason.CANCELLED -> "Check cancelled: not verified — recalibrate on Setup"
    }
    is CheckState.DoesntFit -> "$why — recalibrate"
}

/**
 * Why a saved calibration can't be used with this camera and projector screen, or null when it can
 * (and so is worth checking).
 *
 * @param screen the projector screen the arena is on; null when none was found
 */
fun savedCalibrationMismatch(saved: SavedCalibration, camera: String, feed: Size, screen: Rect?): String? = when {
    screen == null -> "No projector screen found"
    saved.camera != camera -> "The saved calibration was made with another camera (${saved.camera})"
    saved.feed != feed -> "The saved calibration was made at ${describe(saved.feed)}; the camera is at ${describe(feed)}"
    saved.screen != Size(screen.width, screen.height) ->
        "The saved calibration was made for a ${describe(saved.screen)} projector; this one is ${describe(Size(screen.width, screen.height))}"
    else -> null
}

private fun describe(size: Size) = "${size.width.roundToInt()}×${size.height.roundToInt()}"

/** The newest camera frame, kept for the check to take; older ones are dropped. */
class LatestFrame {
    private val frame = AtomicReference<BufferedImage?>()

    fun offer(image: BufferedImage) = frame.set(image)

    fun take(): BufferedImage? = frame.getAndSet(null)
}

/** What a [PatternRun] does with the camera's frames: check a remembered calibration, or measure the pattern */
interface PatternWork<T : Any> {
    /** Looks at [frame]; the result, once there is one */
    fun offer(frame: BufferedImage): T?

    /** The result once the time is up, or null */
    fun tick(): T?
}

/** A check of [CalibrationCheck], as a [PatternRun] does it */
fun CalibrationCheck<BufferedImage>.work(): PatternWork<CalibrationCheck.Outcome> = object : PatternWork<CalibrationCheck.Outcome> {
    override fun offer(frame: BufferedImage) = this@work.offer(frame).orElse(null)

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
    private val arena: ArenaModel,
    private val camera: CalibrationCamera,
    private val frames: LatestFrame,
    private val work: () -> PatternWork<T>,
    private val scope: CoroutineScope,
    private val scheduler: CalibrationFlow.Scheduler,
    private val uiThread: (Runnable) -> Unit,
    private val onDone: (PatternRun<T>, T?) -> Unit,
) {
    companion object {
        // How often a frame is looked at, at most; a detection itself takes up to about half a second
        const val POLL_MILLIS = 50L
    }

    private val finished = AtomicBoolean(false)
    private var background: ArenaBackground? = null

    @Volatile
    private var job: Job? = null

    /** Shows the pattern, alone, and starts looking; on the UI thread. */
    fun start() {
        background = arena.background.value
        arena.cover(true)
        arena.showResource("pattern.png")
        camera.setDetecting(false)
        frames.take()
        val work = work()
        job = scope.launch {
            while (isActive) {
                val frame = frames.take()
                val result = if (frame != null) work.offer(frame) else work.tick()
                if (result != null) {
                    uiThread(Runnable { finish(result) })
                    return@launch
                }
                delay(POLL_MILLIS)
            }
        }
    }

    /**
     * Stops the run; on the UI thread. Never touches the work itself: a check's or a measurement's `offer`
     * is `synchronized` and can hold its lock for as long as a detection takes (hundreds of milliseconds when
     * the pattern isn't found), which would freeze the UI thread here (spec §8 rule 6). [finish]'s
     * compare-and-set makes the first end final, so a result arriving after this is ignored.
     */
    fun stop() = finish(null)

    private fun finish(result: T?) {
        if (!finished.compareAndSet(false, true)) return
        job?.cancel()
        arena.setBackground(background)
        arena.cover(false)
        scheduler.schedule({ camera.setDetecting(true) }, CalibrationFlow.DETECTION_RESTART_DELAY)
        onDone(this, result)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
     * @param paper the perspective paper's size, if auto-calibration found one
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)
}

/**
```

with:

```kotlin
     * @param paper the perspective paper's size, if auto-calibration found one
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)

    /**
     * A calibration the camera found (not the manual box) has finished and the arena looks as it did
     * before: the pattern may show again, to measure it for the remembered calibration.
     */
    fun calibrationFinishedByCamera() {}
}

/**
```

Replace:

```kotlin
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
            Runnable {
                if (generation == expectedGeneration && flow.isCalibrating) {
                    foundPaper = perspectivePaperDims
                    val asked = session
                    flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas)
                    // The flow has finished (it reported the success, and put the arena's look back)
                    if (asked && !session) views.calibrationFinishedByCamera()
                }
            },
        )
```

- [ ] **Step 4: The arena is full screen once it fills its screen**

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt`:

Replace:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
```

with:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
```

Replace:

```kotlin
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.geom.Size
import kotlinx.coroutines.delay

/** How long the arena window waits to reach its screen before going full screen there */
private const val PLACEMENT_WAIT_MILLIS = 3000L

/**
 * The projector arena's own window. It opens at [placement] and, once the window manager has put it on
 * that screen, goes full screen there (going full screen first would fill the screen it opened on).
 * F11 toggles full screen, as in the JavaFX app. Its size is the arena's size.
 */
@Composable
fun ArenaWindow(
```

with:

```kotlin
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.geom.Size
import kotlinx.coroutines.delay
import kotlin.math.abs

/** How long the arena window waits to reach its screen before going full screen there */
private const val PLACEMENT_WAIT_MILLIS = 3000L

/**
 * Whether the arena is really full screen: asked to be, and its content as big as the screen it is on. The
 * window manager resizes the window some time after full screen is asked for; until then the pattern would
 * be drawn, and measured, at the window's old size (spec §8 Revision 2, decision 3).
 */
fun fillsItsScreen(requested: Boolean, content: Size, screen: Size): Boolean =
    requested && abs(content.width - screen.width) <= 1 && abs(content.height - screen.height) <= 1

/**
 * The projector arena's own window. It opens at [placement] and, once the window manager has put it on
 * that screen, goes full screen there (going full screen first would fill the screen it opened on).
 * F11 toggles full screen, as in the JavaFX app. Its size is the arena's size, and the arena is full screen
 * once the window fills its screen ([fillsItsScreen]), not as soon as that is asked for.
 */
@Composable
fun ArenaWindow(
```

Replace:

```kotlin
            }
            state.placement = WindowPlacement.Fullscreen
        }
        LaunchedEffect(state) {
            snapshotFlow { state.placement == WindowPlacement.Fullscreen }.collect { arena.setFullScreen(it) }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            LaunchedEffect(maxWidth, maxHeight) { arena.setSize(Size(maxWidth.value.toDouble(), maxHeight.value.toDouble())) }
            ArenaCanvas(arena, Modifier.fillMaxSize(), overlay)
        }
    }
```

with:

```kotlin
            }
            state.placement = WindowPlacement.Fullscreen
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val requested = state.placement == WindowPlacement.Fullscreen
            LaunchedEffect(maxWidth, maxHeight, requested) {
                val size = Size(maxWidth.value.toDouble(), maxHeight.value.toDouble())
                arena.setSize(size)
                val on = window.graphicsConfiguration.bounds
                arena.setFullScreen(fillsItsScreen(requested, size, Size(on.width.toDouble(), on.height.toDouble())))
            }
            ArenaCanvas(arena, Modifier.fillMaxSize(), overlay)
        }
    }
```

- [ ] **Step 5: Check, measure and save in `AppState`**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
```

with:

```kotlin
import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.PatternMeasurement
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
```

Replace:

```kotlin
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
import com.shootoff.compose.calibration.CalibrationCheckRun
import com.shootoff.compose.calibration.CalibrationController
import com.shootoff.compose.calibration.CalibrationViews
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.LatestFrame
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.compose.drill.ArenaHostSurface
import com.shootoff.compose.drill.ComposeExerciseHost
import com.shootoff.compose.drill.DrillState
```

with:

```kotlin
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
import com.shootoff.compose.calibration.CalibrationController
import com.shootoff.compose.calibration.CalibrationViews
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.LatestFrame
import com.shootoff.compose.calibration.PatternRun
import com.shootoff.compose.calibration.PatternWork
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.calibration.work
import com.shootoff.compose.drill.ArenaHostSurface
import com.shootoff.compose.drill.ComposeExerciseHost
import com.shootoff.compose.drill.DrillState
```

Replace:

```kotlin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
```

with:

```kotlin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
```

Replace:

```kotlin
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

const val MIN_TRAY_HEIGHT = 120f
```

with:

```kotlin
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

const val MIN_TRAY_HEIGHT = 120f
```

Replace:

```kotlin
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 */
class AppState(
    val settings: Settings,
```

with:

```kotlin
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 * @param reconnectMillis how often a lost camera is looked for, to reopen it when it is plugged back in
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check or a
 *   measurement shows the pattern
 */
class AppState(
    val settings: Settings,
```

Replace:

```kotlin
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
    private val reconnectMillis: Long = 2000,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + background)
```

with:

```kotlin
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
    private val reconnectMillis: Long = 2000,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check or measurement */
        const val PATTERN_SETTLE_MILLIS = 500L
    }

    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + background)
```

Replace:

```kotlin
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    private val checkFrames = LatestFrame()
    private var checkRun: CalibrationCheckRun? = null
    private var checkWatch: Job? = null
    private val waitingForState = MutableStateFlow<String?>(null)
    private var reconnectWatch: Job? = null
```

with:

```kotlin
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    private val checkFrames = LatestFrame()

    // The check's or the measurement's run, while its pattern shows; read by CalibratingCamera on timer threads
    @Volatile
    private var patternRun: PatternRun<*>? = null
    private var patternWatch: Job? = null
    private val waitingForState = MutableStateFlow<String?>(null)
    private var reconnectWatch: Job? = null
```

Replace:

```kotlin
    /** Whether nothing else needs the arena, so the grid may show */
    fun gridAllowed(): Boolean = arenaState.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        checkState.value != CheckState.Checking &&
        runner.running.value?.host?.isProjector != true

    fun setDark(dark: Boolean) {
```

with:

```kotlin
    /** Whether nothing else needs the arena, so the grid may show */
    fun gridAllowed(): Boolean = arenaState.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        !checkState.value.showsPattern &&
        runner.running.value?.host?.isProjector != true

    fun setDark(dark: Boolean) {
```

Replace:

```kotlin
            // An uncalibrated arena is checked against the remembered calibration now that there is a camera
            // to check with (spec §8 Revision 2, decision 7); without Remember, Setup's Calibrate step is next
            if (settings.rememberCalibration() && settings.savedCalibration.isPresent &&
                arena.projection.value == null && checkState.value != CheckState.Checking
            ) {
                checkRemembered(arena)
            }
```

with:

```kotlin
            // An uncalibrated arena is checked against the remembered calibration now that there is a camera
            // to check with (spec §8 Revision 2, decision 7); without Remember, Setup's Calibrate step is next
            if (settings.rememberCalibration() && settings.savedCalibration.isPresent &&
                arena.projection.value == null && !checkState.value.showsPattern
            ) {
                checkRemembered(arena)
            }
```

Replace:

```kotlin
        if (settings.rememberCalibration()) checkRemembered(arena)
    }

    // The remembered calibration, checked (never at launch: only here, as the arena opens, or once a camera
    // becomes available for an arena already open — see publish) if it was made with this camera and a
    // projector screen like this one. The check tracks the arena's full screen state throughout, through
    // watchFullScreenForCheck: it starts, or restarts, only while full screen, since the pattern on a window
    // still on its way there, or one the owner has pulled off the projector (F11) mid-check, would be
    // measured in the wrong place.
    private fun checkRemembered(arena: ArenaModel) {
        val saved = settings.savedCalibration.orElse(null) ?: return
        val camera = cameraState.value
```

with:

```kotlin
        if (settings.rememberCalibration()) checkRemembered(arena)
    }

    // The remembered calibration, checked as the arena opens, or once a camera becomes available for an arena
    // already open (see publish), if it was made with this camera and a projector screen like this one. The
    // pattern shows only while the arena is on the projector (watchArenaForPattern).
    private fun checkRemembered(arena: ArenaModel) {
        val saved = settings.savedCalibration.orElse(null) ?: return
        val camera = cameraState.value
```

Replace:

```kotlin
        }

        checkState.value = CheckState.Checking
        watchFullScreenForCheck(arena, camera, saved)
    }

    // Starts, and restarts, the check as the arena's full screen state comes and goes (spec §8, Finding 2):
    // reaching full screen starts a run; losing it (F11 mid-check) stops the run quietly, the same way
    // stopCheckQuietly's run?.stop(...) does (the pattern comes off, the background comes back), without
    // reporting a Moved outcome measured in a window — checkState stays Checking so a return to full screen
    // tries again. Runs until checkState leaves Checking: checked cancels it on a real outcome, and
    // stopCheckQuietly cancels it on every other end (cancel, closing the arena, calibrating, remember off).
    private fun watchFullScreenForCheck(arena: ArenaModel, camera: CameraManager, saved: SavedCalibration) {
        checkWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            arena.fullScreen.collect { fullScreen ->
                uiThread(Runnable {
                    if (arenaState.value !== arena || checkState.value != CheckState.Checking) return@Runnable
                    if (fullScreen) {
                        if (checkRun == null) runCheck(arena, camera, saved)
                    } else {
                        stopRunQuietlyForFullScreenLoss()
                    }
                })
            }
        }
    }

    // Finding 2: the arena left full screen mid-check. The run stops without a word, as stopCheckQuietly's
    // run?.stop(...) does, but checkState stays Checking and the full screen watch keeps running, so
    // watchFullScreenForCheck starts a fresh run once the arena is full screen again.
    private fun stopRunQuietlyForFullScreenLoss() {
        val run = checkRun ?: return
        checkRun = null
        cameraView.frameTap = null
        run.stop(CalibrationCheck.Reason.CANCELLED)
    }

    private fun runCheck(arena: ArenaModel, camera: CameraManager, saved: SavedCalibration) {
        val run = CalibrationCheckRun(saved, arena, CalibratingCamera(camera), checkFrames, detector(camera), checkClock, scope, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread) { run, outcome -> checked(run, saved, outcome) }
        checkRun = run
        cameraView.frameTap = checkFrames::offer
        arena.showGrid(false)
        run.start()
    }

    // The check's outcome, on the UI thread; ignored if the check was stopped by something that replaced it
    private fun checked(run: CalibrationCheckRun, saved: SavedCalibration, outcome: CalibrationCheck.Outcome) {
        if (checkRun !== run) return
        checkRun = null
        cameraView.frameTap = null
        // A real outcome ends the check: the full screen watch (Finding 2) has nothing left to restart
        checkWatch?.cancel()
        checkWatch = null
        when (outcome) {
            is CalibrationCheck.Kept -> {
                calibrationState.value?.applySaved(saved)
                currentCalibration = saved
                calibratedAtState.value = wallClock()
                checkState.value = CheckState.Idle
            }
```

with:

```kotlin
        }

        checkState.value = CheckState.Checking
        watchArenaForPattern(arena) {
            startPatternRun(arena, camera, { CalibrationCheck(saved.bounds, detector(camera), checkClock).work() }) { outcome ->
                checked(saved, outcome)
            }
        }
    }

    // Starts [start] once the arena is on the projector (spec §8 Revision 2, decision 3): full screen, as big
    // as the projector's screen, and settled there for [patternSettleMillis], so the pattern is never measured
    // in a window on its way (or, after F11, off) the projector. Leaving the projector stops the run quietly
    // (the state stays, so its return starts a fresh run). Runs until the check or measurement ends.
    private fun watchArenaForPattern(arena: ArenaModel, start: () -> Unit) {
        patternWatch?.cancel()
        val screen = placementState.value?.screen
        patternWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(arena.fullScreen, arena.size) { fullScreen, size -> fullScreen && fills(size, screen) }
                .distinctUntilChanged()
                .collectLatest { onTheProjector ->
                    if (onTheProjector) delay(patternSettleMillis)
                    uiThread(Runnable {
                        if (arenaState.value !== arena || !checkState.value.showsPattern) return@Runnable
                        if (!onTheProjector) {
                            stopRunQuietlyOffTheProjector()
                        } else if (patternRun == null) {
                            start()
                        }
                    })
                }
        }
    }

    // Whether an arena of [size] fills [screen] (to the pixel, give or take rounding)
    private fun fills(size: Size, screen: Rect?): Boolean =
        screen == null || (abs(size.width - screen.width) <= 1 && abs(size.height - screen.height) <= 1)

    // The arena left the projector mid-run. The run stops without a word, but the state stays (Checking or
    // Measuring) and the watch keeps going, so a fresh run starts once the arena is back.
    private fun stopRunQuietlyOffTheProjector() {
        val run = patternRun ?: return
        patternRun = null
        cameraView.frameTap = null
        run.stop()
    }

    // Shows the pattern and hands the camera's frames to [work]; [done] hears its result, unless the run was
    // stopped or replaced first
    private fun <T : Any> startPatternRun(arena: ArenaModel, camera: CameraManager, work: () -> PatternWork<T>, done: (T) -> Unit) {
        val run = PatternRun(arena, CalibratingCamera(camera), checkFrames, work, scope, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread) { run, result ->
            if (patternRun === run && result != null) {
                patternRun = null
                cameraView.frameTap = null
                // A result ends the check or measurement: the watch has nothing left to restart
                patternWatch?.cancel()
                patternWatch = null
                done(result)
            }
        }
        patternRun = run
        cameraView.frameTap = checkFrames::offer
        arena.showGrid(false)
        run.start()
    }

    // The check's outcome, on the UI thread
    private fun checked(saved: SavedCalibration, outcome: CalibrationCheck.Outcome) {
        when (outcome) {
            is CalibrationCheck.Kept -> {
                // Kept as saved, or, for a drift within twice the tolerance, at the fresh measurement, which is
                // then what is remembered
                val kept = SavedCalibration(saved.camera, saved.feed, saved.screen, outcome.bounds(), saved.paper)
                calibrationState.value?.applySaved(kept)
                currentCalibration = kept
                if (kept.bounds != saved.bounds && settings.rememberCalibration()) {
                    settings.setSavedCalibration(kept)
                    saveSettings()
                }
                calibratedAtState.value = wallClock()
                checkState.value = CheckState.Idle
            }
```

Replace:

```kotlin
        }
    }

    // Ends a check without a word (something replaced it); the arena's background comes back
    private fun stopCheckQuietly() {
        checkWatch?.cancel()
        checkWatch = null
        val run = checkRun
        checkRun = null
        cameraView.frameTap = null
        run?.stop(CalibrationCheck.Reason.CANCELLED)
        checkState.value = CheckState.Idle
    }

    /** Cancel on the check: it ends, the arena stays uncalibrated, and Setup says it wasn't verified. */
    fun cancelCheck() {
        if (checkState.value != CheckState.Checking) return
        stopCheckQuietly()
        checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED)
    }

    /**
```

with:

```kotlin
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
            val measurement = { PatternMeasurement("Calibration measurement", detector(camera), checkClock, Optional.of(calibration.bounds)).work() }
            startPatternRun(arena, camera, measurement) { result ->
                checkState.value = CheckState.Idle
                if (result is PatternMeasurement.Measured && currentCalibration === calibration && settings.rememberCalibration()) {
                    val measured = SavedCalibration(calibration.camera, calibration.feed, calibration.screen, result.median(), calibration.paper)
                    currentCalibration = measured
                    settings.setSavedCalibration(measured)
                    saveSettings()
                }
            }
        }
    }

    // Ends a check or a measurement without a word (something replaced it); the arena's look comes back
    private fun stopCheckQuietly() {
        patternWatch?.cancel()
        patternWatch = null
        val run = patternRun
        patternRun = null
        cameraView.frameTap = null
        run?.stop()
        checkState.value = CheckState.Idle
    }

    /**
     * Cancel on Setup while the pattern shows: a check ends with the arena uncalibrated and "not verified"; a
     * measurement ends leaving the calibration's own bounds remembered.
     */
    fun cancelCheck() {
        when (checkState.value) {
            CheckState.Checking -> {
                stopCheckQuietly()
                checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED)
            }
            CheckState.Measuring -> stopCheckQuietly()
            else -> {}
        }
    }

    /**
```

Replace:

```kotlin
        rememberState.value = remember
        settings.setRememberCalibration(remember)
        settings.setSavedCalibration(if (remember) currentCalibration else null)
        if (!remember && checkState.value == CheckState.Checking) stopCheckQuietly()
        saveSettings()
    }
```

with:

```kotlin
        rememberState.value = remember
        settings.setRememberCalibration(remember)
        settings.setSavedCalibration(if (remember) currentCalibration else null)
        if (!remember && checkState.value.showsPattern) stopCheckQuietly()
        saveSettings()
    }
```

Replace:

```kotlin
    fun projectorReady(): Boolean = cameraState.value != null &&
        arenaState.value?.projection?.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        checkState.value != CheckState.Checking

    /**
     * Starts a fresh instance of [entry]; a projector drill needs [projectorReady].
```

with:

```kotlin
    fun projectorReady(): Boolean = cameraState.value != null &&
        arenaState.value?.projection?.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        !checkState.value.showsPattern

    /**
     * Starts a fresh instance of [entry]; a projector drill needs [projectorReady].
```

Replace:

```kotlin
        }
    }

    // The camera as calibration and the check see it: when they turn shot detection back on as they end,
    // it stays off while the running drill has it paused (a paused drill turned it off itself)
    private inner class CalibratingCamera(private val camera: CameraManager) : CalibrationCamera by camera {
        override fun setDetecting(isDetecting: Boolean) {
            camera.setDetecting(isDetecting && runner.running.value?.host?.shotDetectionPaused != true)
        }
    }
```

with:

```kotlin
        }
    }

    // The camera as calibration, the check and the measurement see it: when they turn shot detection back on
    // as they end, it stays off while a pattern still shows (a check or measurement started meanwhile: Plan 6's
    // N1) and while the running drill has it paused (a paused drill turned it off itself)
    private inner class CalibratingCamera(private val camera: CameraManager) : CalibrationCamera by camera {
        override fun setDetecting(isDetecting: Boolean) {
            camera.setDetecting(isDetecting && patternRun == null && runner.running.value?.host?.shotDetectionPaused != true)
        }
    }
```

- [ ] **Step 6: Setup and Range treat measuring like checking; log the check at INFO**

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`:

Replace:

```kotlin
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaView
import com.shootoff.compose.calibration.CalibrationOverlay
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay
```

with:

```kotlin
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaView
import com.shootoff.compose.calibration.CalibrationOverlay
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay
```

Replace:

```kotlin
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            calibrating -> FilledTonalButton(onClick = app::cancelCalibration, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            check == CheckState.Checking -> FilledTonalButton(onClick = app::cancelCheck, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            else -> Button(onClick = { app.startCalibration() }, enabled = controller != null, modifier = Modifier.testTag("setup-calibrate")) {
                Text(if (calibrated) "Recalibrate" else "Calibrate")
            }
```

with:

```kotlin
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            calibrating -> FilledTonalButton(onClick = app::cancelCalibration, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            check.showsPattern -> FilledTonalButton(onClick = app::cancelCheck, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            else -> Button(onClick = { app.startCalibration() }, enabled = controller != null, modifier = Modifier.testTag("setup-calibrate")) {
                Text(if (calibrated) "Recalibrate" else "Calibrate")
            }
```

Replace:

```kotlin
        Switch(
            checked = grid,
            onCheckedChange = app::showGrid,
            enabled = arena != null && !calibrating && check != CheckState.Checking && !projectorDrill,
            modifier = Modifier.testTag("show-grid"),
        )
        Text("Show grid", color = colors.text)
```

with:

```kotlin
        Switch(
            checked = grid,
            onCheckedChange = app::showGrid,
            enabled = arena != null && !calibrating && !check.showsPattern && !projectorDrill,
            modifier = Modifier.testTag("show-grid"),
        )
        Text("Show grid", color = colors.text)
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt`:

Replace:

```kotlin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.drill.DrillCard
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range
```

with:

```kotlin
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.drill.DrillCard
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range
```

Replace:

```kotlin
            }
        } else if (picked != null) {
            // As AppState.projectorReady decides it
            val ready = !picked.isProjectorOnly || (camera != null && calibrated && !calibrating && check != CheckState.Checking)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.highlightCard.copy(alpha = 0.93f),
```

with:

```kotlin
            }
        } else if (picked != null) {
            // As AppState.projectorReady decides it
            val ready = !picked.isProjectorOnly || (camera != null && calibrated && !calibrating && !check.showsPattern)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.highlightCard.copy(alpha = 0.93f),
```

`compose-app/src/main/resources/logback.xml`:

Replace:

```xml
  </appender>

  <logger name="com.shootoff.camera.cameratypes.SarxosCaptureCamera" level="INFO"/>

  <root level="warn">
    <appender-ref ref="STDOUT"/>
```

with:

```xml
  </appender>

  <logger name="com.shootoff.camera.cameratypes.SarxosCaptureCamera" level="INFO"/>
  <!-- The remembered calibration's measurements and outcomes, and the camera's reconnect -->
  <logger name="com.shootoff.calibration" level="INFO"/>
  <logger name="com.shootoff.compose.app.AppState" level="INFO"/>

  <root level="warn">
    <appender-ref ref="STDOUT"/>
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --console=plain`

Expected: `BUILD SUCCESSFUL`. Run it twice more with `:compose-app:cleanTest` first: the new timing tests (`theCheckWaitsForTheArenaToFillTheProjectorAndSettle`, `aFastFullScreenFlapNeverTurnsDetectionOnUnderTheNextPattern`) must pass every time.

- [ ] **Step 8: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
```

Expected: `721/721 passing; 0 regressions; 0 new failures`, and four `OK` lines.

- [ ] **Step 9: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt compose-app/src/main/resources/logback.xml compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaWindow.kt
git commit -m "Measure the remembered calibration the way the check does, only once the arena fills the projector"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 9: The arena opens at launch when a projector screen is found

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`launch`), `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt` (`ProjectorStep`), `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt` (a comment)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`

**Interfaces:**
- Consumes: Plan 6's `AppState.launch()`, `projectorScreenFound()`, `openArena()` and the check's retry once the camera opens (`publish`); Task 8's `watchArenaForPattern`.
- Produces: `launch()` also opens the arena when `projectorScreenFound()`; `const val PROJECTOR_LOOK_MILLIS = 2000L` in `SetupScreen.kt`.

**Why (decision 4).** A session always starts with the arena on the projector, and with a remembered calibration the check needs it there. This reverses rule 1's "It never opens the arena"; it still never calibrates, and nothing waits (ruling 13). With no projector nothing opens, and Setup's hint is looked for again every two seconds, so it no longer goes stale when a projector is plugged in while Setup shows (Plan 6's deferred minor).

- [ ] **Step 1: Write the failing tests, and change rule 1's**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`:

Replace:

```kotlin
/** Spec §8 "Nothing blocks": one test per rule. */
class TestNothingBlocks {
    @Test
    fun rule1AtLaunchOnlyTheCameraOpensAndNothingElseStarts() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
```

with:

```kotlin
/** Spec §8 "Nothing blocks": one test per rule. */
class TestNothingBlocks {
    @Test
    fun rule1AtLaunchTheCameraAndTheArenaOpenAndNothingCalibrates() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
```

Replace:

```kotlin
            awaitTrue { app.camera.value != null }

            assertEquals(Destination.RANGE, app.destination.value)
            assertNull(app.arena.value)
            assertNull(app.calibration.value)
            assertEquals(CheckState.Idle, app.check.value)
            assertEquals(0, looked.get())
        } finally {
            app.close()
        }
```

with:

```kotlin
            awaitTrue { app.camera.value != null }

            assertEquals(Destination.RANGE, app.destination.value)
            // The owner's projector was found: the arena opens on it (spec §8 Revision 2, decision 4)
            assertEquals(Rect(4480.0, 0.0, 1280.0, 720.0), app.arenaPlacement.value!!.screen)
            assertFalse(app.calibration.value!!.state.value.calibrating)
            // The saved calibration is checked once the window fills the projector, which it hasn't yet
            assertEquals(CheckState.Checking, app.check.value)
            assertEquals(0, looked.get())
            assertNull(app.arena.value!!.background.value)
        } finally {
            app.close()
        }
    }

    @Test
    fun rule1WithNoProjectorScreenOnlyTheCameraOpensAtLaunch() {
        val catalog = ExerciseCatalog()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), catalog, AppFixture.oneCamera(), { listOf(Rect(0.0, 0.0, 1920.0, 1080.0)) }, ManualClock(), { it.run() })
        try {
            app.launch()
            awaitTrue { app.camera.value != null }

            assertNull(app.arena.value)
            assertFalse(app.projectorScreenFound())
        } finally {
            app.close()
        }
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`:

Replace:

```kotlin
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.RangeDark
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.After
```

with:

```kotlin
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.theme.RangeDark
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.After
```

Replace:

```kotlin
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun openingTheArenaOnSetupTicksTheStepAndShowsItsPreview() {
        app.navigate(Destination.SETUP)
```

with:

```kotlin
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun theProjectorStepNoticesAProjectorPluggedInWhileItShows() {
        app.close()
        var screens = listOf(Rect(0.0, 0.0, 1920.0, 1080.0))
        app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), CameraSource.None, { screens }, ManualClock(), { it.run() })
        app.navigate(Destination.SETUP)
        showApp()
        compose.onNodeWithTag("no-projector").assertExists()

        screens = AppFixture.ownerScreens
        compose.mainClock.advanceTimeBy(PROJECTOR_LOOK_MILLIS + 100)

        compose.onNodeWithTag("no-projector").assertDoesNotExist()
    }

    @Test
    fun openingTheArenaOnSetupTicksTheStepAndShowsItsPreview() {
        app.navigate(Destination.SETUP)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestNothingBlocks' --tests 'com.shootoff.compose.app.TestSetupScreen' --console=plain`

Expected: `compileTestKotlin` FAILS with `Unresolved reference 'PROJECTOR_LOOK_MILLIS'`. (With only Step 3's `SetupScreen` part in place, `rule1AtLaunchTheCameraAndTheArenaOpenAndNothingCalibrates` FAILS: no arena opened at launch.)

- [ ] **Step 3: Open the arena at launch; look for a projector while it is closed**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    // ---- The camera

    /**
     * What the app does as it starts (spec §8 rule 1): the camera it starts with is found and opened in the
     * background, and nothing else happens. The arena, calibration and the check wait for the user.
     */
    fun launch() {
        scope.launch(io) {
            val camera = try {
                cameraSource.startCamera(settings)
```

with:

```kotlin
    // ---- The camera

    /**
     * What the app does as it starts (spec §8 rule 1, as revised): the camera it starts with is found and
     * opened in the background, and the arena opens on the projector if a projector screen is found, where
     * the remembered calibration, if any, is checked (spec §8 Revision 2, decision 4). Nothing calibrates.
     */
    fun launch() {
        // Looking for screens asks AWT: on the UI thread, where the arena opens
        uiThread(Runnable { if (arenaState.value == null && projectorScreenFound()) openArena() })
        scope.launch(io) {
            val camera = try {
                cameraSource.startCamera(settings)
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`:

Replace:

```kotlin
    }
}

/** The projector: open or close the arena, with a small live preview of it */
@Composable
private fun ProjectorStep(app: AppState) {
    val arena by app.arena.collectAsState()
    val colors = Range.colors
    // Looking for the projector asks AWT about every screen: once per arena change, not on every recomposition
    val projectorFound = remember(arena) { arena == null && app.projectorScreenFound() }

    val open = arena
    if (open == null) {
```

with:

```kotlin
    }
}

/** How often the Projector step looks for a projector screen while the arena is closed */
const val PROJECTOR_LOOK_MILLIS = 2000L

/** The projector: open or close the arena, with a small live preview of it */
@Composable
private fun ProjectorStep(app: AppState) {
    val arena by app.arena.collectAsState()
    val colors = Range.colors
    // Looking for the projector asks AWT about every screen: every couple of seconds while the arena is
    // closed, so a projector plugged in meanwhile is noticed, and never on a recomposition
    var projectorFound by remember { mutableStateOf(true) }
    LaunchedEffect(arena) {
        while (arena == null) {
            projectorFound = app.projectorScreenFound()
            delay(PROJECTOR_LOOK_MILLIS)
        }
    }

    val open = arena
    if (open == null) {
```

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`:

Replace:

```kotlin
    val app = AppState(settings, catalog, CameraSource.System, prefs = UiPrefs(PrefsStore.User()))
    Settings.setUserNotifier(app.notices)
    // Only the camera opens, in the background: the window shows at once (spec §8 rules 1 and 6)
    app.launch()

    // An exception in a window's event handling or composition is logged and shown, and never exits the app
```

with:

```kotlin
    val app = AppState(settings, catalog, CameraSource.System, prefs = UiPrefs(PrefsStore.User()))
    Settings.setUserNotifier(app.notices)
    // The camera opens in the background, and the arena on the projector if there is one; the window shows at
    // once and nothing calibrates (spec §8 rules 1 and 6, and Revision 2, decision 4)
    app.launch()

    // An exception in a window's event handling or composition is logged and shown, and never exits the app
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the gate, and check the boundary**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
python3 scripts/test_summary.py summarize compose-app/build/test-results/test | command grep NoJavaFx
```

Expected: `723/723 passing; 0 regressions; 0 new failures`, four `OK` lines, then two `PASS com.shootoff.compose.TestNoJavaFxInComposeApp…` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/Main.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt
git commit -m "Open the arena at launch when a projector screen is found"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 10: The owner's hardware re-check, and a JavaFX regression check

Runs in ShootOFF on `compose-ui`. **Files:** none. It covers every Revision 2 decision, Plan 6's success criteria 1–8 (criterion 7 included: a relaunch with nothing moved keeps the remembered calibration), the parts of Plan 6's Task 9 that were not reached (the rest of its item 9, its item 10 and its JavaFX check), and a 20-minute FPS and memory watch. It replaces Plan 6's Task 9.
- If a check fails, stop and debug with superpowers:systematic-debugging before changing code.
- The fix belongs in the task that owns the code, as a new commit with a test that reproduces it.
- The hardware: the Logitech C270 (640×480) and the 1280×720 projector at x=4480. Settings → Arena display should still point at the projector (the owner re-picked it in Plan 6's check).

- [ ] **Step 1: The gate, the boundary and the owner's files**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan7-owner-files.sha256
./gradlew :compose-app:dependencies --configuration runtimeClasspath --console=plain | command grep -c openjfx
```

Expected:
- `723/723 passing; 0 regressions; 0 new failures`
- four `OK` lines
- `0`

- [ ] **Step 2: Launch the Compose app, and start the memory log**

With the webcam and the projector attached (the projector on), run in the background:

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :compose-app:run --console=plain > build/plan7-compose-run.log 2>&1
```

Then, in another shell, once the window is up, log the app's memory once a minute for 25 minutes (it runs in the background while the owner checks):

```bash
cd /home/bfears/projects/ShootOFF
PID=$(pgrep -f 'com.shootoff.compose.MainKt' | head -1); echo "pid $PID"
for i in $(seq 1 25); do echo "$(date +%T) $(ps -o rss= -p "$PID") kB"; sleep 60; done > build/plan7-rss.log &
```

Expected: `pid` followed by a number.

- [ ] **Step 3: The owner checks the Compose app, on the hardware**

1. **Launch** (§1 criterion 1; rule 1 as revised; decision 4).
   - The window shows at once in Range dark. The rail shows Range, Setup, Drills, Targets, Sessions, Settings; Targets and Sessions are greyed, "in the JavaFX app for now".
   - The arena opens by itself on the projector and goes full screen there. Nothing calibrates: the projector shows "Needs Calibration" (or, with Remember calibration on from an earlier session, the pattern for two or three seconds: see item 9).
   - Range shows the camera feed (after "Opening camera …" if the camera is slow), the status chip, Clear shots, the drill picker and its card. There is no Camera | Arena switch, and F2 does nothing.
2. **Light and dark.** The switch at the rail's foot flips the whole app to the light slate-and-orange variant and back; Settings → Theme does the same.
3. **Frame rate.** The status strip reads like `… · 30 FPS · shown 30 · 640×480 · …`. Watch it for a minute: the camera stays near 30 FPS, and "shown" near it.
4. **Only real cameras** (decision 5). Setup's Camera step and Settings → Camera list the C270 once (no second entry for `/dev/video1`). `command grep -c "Cannot open the webcam" build/plan7-compose-run.log` prints `0`.
5. **Setup, top to bottom** (criteria 2 and 6; decision 1). Open Setup from the rail or the chip.
   - Camera ✓ (the C270's name, its FPS and 640×480) and Projector ✓ (the arena is already open, with its small preview); Calibrate highlighted.
   - Calibrate: "Looking for the calibration pattern…" with a spinner and Cancel over the feed; the pattern shows on the projector; auto-calibration finds it.
   - The app **stays on Setup**. The Calibrate step shows "Calibration complete ✓ HH:mm" with the time; the chip on Range will read "✓ Calibrated HH:mm". Go back to Range from the rail when ready.
6. **The calibration view** (criterion 8). On Setup:
   - The calibrated projection is outlined in orange over the camera feed.
   - Show grid: the projector shows a black arena with white grid lines and orange corner and center marks, and the preview shows the same. Judge by eye that the projected grid's edges sit on the orange outline in the feed.
   - Leave Setup: the grid goes and the arena's background is back.
7. **Cancel and the manual box** (§8 rule 5).
   - Calibrate, then Cancel while it looks for the pattern: calibration ends and the arena looks as before (the earlier calibration kept).
   - Calibrate and cover the camera for 12 s: the orange box appears on Setup's feed with "Drag the box over the projection, then press Done" and Cancel. Drag it over the projection, press Done: still on Setup, "Calibration complete ✓", and shots land where they hit.
8. **The drill, end to end, from Range** (criteria 3 and 6; decision 2). Start the par drill from its card and play at least five rounds.
   - Placement, cues and sounds (make-ready voice, start beep, buzzer), par timing and points ("Score: …" big, "Round: …" below), the coral "Par missed!" row, the summary with the hit factor and personal best, the shot replay, and shoot-to-reset, as in Plan 6.
   - Pause and Resume: the card's Pause button and F3 both pause and resume. F4 and Clear shots both clear the markers and the shot timer.
   - **Recalibrate mid-round** from Setup: the drill pauses (the card shows Resume), and while the pattern shows the projector shows nothing else (no target, score or round text, no "Needs Calibration"). After "Calibration complete ✓", the arena looks as it did, the drill is still paused, and shooting at the projector adds no shots. Back on Range, Resume: "make ready", and the drill carries on with its round count and score, not from round 1.
   - **F6 during a round:** Setup opens, the drill pauses, calibration succeeds, the app stays on Setup, and the drill is still paused. Resume carries on.
   - **Already paused, then Calibrate and Cancel:** the drill stays paused (calibration doesn't resume it).
   - Stop on the card stops the drill and leaves nothing behind.
9. **Remember calibration** (criterion 7; decision 3).
   - On Setup, tick Remember calibration and calibrate: after "Calibration complete ✓", the pattern shows again for two or three seconds ("Measuring the calibration for next time…", with Cancel), then the background.
   - Quit (close the main window) and relaunch with `./gradlew :compose-app:run` (appending to the log: `>> build/plan7-compose-run.log 2>&1`). The arena opens on the projector by itself, the pattern shows for two or three seconds, and the chip reads "✓ Calibrated HH:mm" without pressing Calibrate. Start the drill: shots land where they hit.
   - **Nothing moved, never "moved":** relaunch twice more without touching the camera or the projector. Each time the calibration is kept. `command grep -E "Calibration (check|measurement)" build/plan7-compose-run.log` shows five detections per check (each with its rectangle, its distance from the saved bounds and how long it took) and a "kept" (or "kept at the new measurement") line; none says "moved".
   - Quit, nudge the camera so the projection moves visibly in the feed, relaunch: Setup (and the chip) say "The projection moved about N px — recalibrate", and the arena stays uncalibrated (Start stays disabled); or, for a nudge of only a few pixels, the calibration is kept at the new measurement. Calibrate: it is measured and saved again.
   - Relaunch with the projector's lens covered: within about 8 s Setup says "The pattern wasn't seen: not verified — recalibrate on Setup"; no dialog, and the rest of the app keeps working meanwhile.
   - During a check (relaunch, and act while the pattern shows): press F11 on the arena twice quickly: the check starts again once the arena is back full screen, with no false "moved". Then Cancel on Setup: "Check cancelled: not verified"; unticking Remember instead stops it without a message.
   - Untick Remember calibration: `command grep -c "shootoff.arena.calibration" shootoff.properties` prints `0`. Tick it again and calibrate (item 11 and Step 6 need it on).
10. **Missing hardware** (§8 rule 4; decisions 6, 7 and 8; the rest of Plan 6's item 9).
    - **Unplug the webcam during a round**, with the arena calibrated: the app stays up; Range shows "No camera" with the reason, "Waiting for … to be plugged back in…" and the picker; the arena stays open on the projector showing "Needs Calibration"; the drill is paused; Setup's Camera step and the chip say "No camera".
    - **Click the unplugged camera in the picker before plugging it back:** the panel says "… is not connected. Plug it in, or pick another camera."; the app does not crash (the crash from Plan 6's check).
    - **Plug it back in:** within a few seconds it reopens by itself, without a click. With Remember on, the pattern shows and the saved calibration is kept; with Remember off, Setup's Calibrate step is highlighted. Resume the drill.
    - Unplug it during a check (relaunch, unplug while the pattern shows): the check stops, the pattern goes, the arena stays; plug it back: the check runs again.
    - Close the arena window during calibration: calibration ends; reopen it on Setup and calibrate again normally.
    - Skip on Range's not-ready prompt hides it; closing the arena brings it back.
    - If the projector can be unplugged (or its output turned off in the display settings) without disturbing the other screens: relaunch without it: no arena opens and Setup's Projector step says no projector screen was found. Plug it back in with Setup showing: within about two seconds the hint goes. Otherwise note this as unchecked.
11. **The tray and remembering** (Plan 6's item 10). Drag the tray's top edge; Hide and Show fold it. Quit and relaunch: the window's place and size, the theme and the tray come back; the app opens on Range (with the arena on the projector).
12. **Twenty minutes.** Keep the drill running (or the app open on Range with the camera) for at least 20 minutes after launch, glancing at the status strip: the camera stays near 30 FPS and "shown" near it throughout. The Setup preview and the projector stay in step.

- [ ] **Step 4: Check the memory log and the Compose app's log**

After at least 20 minutes:

```bash
cd /home/bfears/projects/ShootOFF
cat build/plan7-rss.log
command grep -nE "Exception|Error" build/plan7-compose-run.log
command grep -cE "Calibration (check|measurement)" build/plan7-compose-run.log
```

Expected:
- 20 or more lines. After the first five minutes the RSS levels off: the last reading is within about 100 MB of the reading at minute 5, with no steady climb minute over minute (Plan 6's run: about 1.0–1.1 GB, one 1.2 GB spike at calibration).
- The second command prints nothing, except lines from item 10's deliberate unplugs (the camera's own "can no longer communicate" report). An "Unexpected error in the user interface" line means the safety net caught something: report it with its stack trace.
- The third prints a number above zero: the check's and the measurement's INFO lines.

- [ ] **Step 5: Check that the moved `TextToSpeech` still links for v1 plugins** (from Plan 5's check)

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :javafx-app:classes --console=plain -q
CP=core/build/classes/java/main:plugin-api/build/classes/java/main:javafx-app/build/classes/java/main
javap -s -p -cp "$CP" com.shootoff.plugins.TextToSpeech | command grep -A1 -E " (say|silence)\("
```

Expected: `public static void say(java.lang.String);` with `descriptor: (Ljava/lang/String;)V`, and `public static void silence(boolean);` with `descriptor: (Z)V`.

- [ ] **Step 6: A JavaFX regression check** (spec §1 criterion 4)

Quit the Compose app. With Remember calibration ticked and a calibration saved (item 9), run `./gradlew run --console=plain > build/plan7-javafx-run.log 2>&1` in the background, with the webcam and the projector attached. The owner checks:
1. Only the JavaFX app starts: `command grep -c "Task :compose-app:run SKIPPED" build/plan7-javafx-run.log` prints `1`.
2. File → Preferences lists the C270 once (decision 5 reaches the JavaFX app too), and the feed shows.
3. Projector → the arena goes full screen on the projector, and auto-calibration shows the pattern and succeeds, as before (the JavaFX arena still calibrates as it opens).
4. Projector → Background lists the eight bundled backgrounds, and one shows.
5. Add Pepper_Popper to the arena and shoot its plate: it falls. Press Reset: it stands up.
6. Start "Random Target PAR Drill with Score" (v2) for two rounds, then pick "None".
7. File → Preferences → change the marker size and Save. Then `command grep -c "shootoff.arena.calibration" shootoff.properties` prints at least `5`: the JavaFX save kept the Compose app's keys.
8. Quit, then check the log: `command grep -nE "Exception|NoClassDefFoundError|NoSuchMethodError" build/plan7-javafx-run.log` prints nothing.
9. Relaunch the Compose app: the arena opens on the projector and the remembered calibration is checked and kept.

- [ ] **Step 7: The owner's files**

```bash
cd /home/bfears/projects/ShootOFF
ls exercises/
command grep -v " shootoff.properties$" build/plan7-owner-files.sha256 | sha256sum -c
git status --short
```

Expected:
- `RandomTargetParDrill-v2.jar` and `RandomTargetParDrill.jar` listed
- three `OK` lines. `shootoff.properties` is left out: both apps save the owner's choices there (the camera, the marker size, the remembered calibration).
- `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

Things this machine can't exercise: a second camera of another name (the picker with two real cameras), a HiDPI screen (all three screens here are at scale 1.0, so `fillsItsScreen`'s dp-to-pixel match is only exercised at 1.0), a different projector resolution (Plan 6's tests cover it), and, unless item 10's last check was possible, no projector at all. Note them as unchecked in the report.

- [ ] **Step 8: Report**

Report to the owner: which items passed, any that failed and the fix commits, the FPS seen, the RSS at minutes 5 and 20+, the distances the log shows for the unmoved relaunches (item 9), and the open decisions (this plan's rulings 2, 7 and 8, and Plan 6's rulings 5 and 6), so the owner can decide on them and on keeping Compose (spec §1 criterion 5).

---

## Spec coverage

| Spec §8 Revision 2 | Where |
|---|---|
| 1. Stay on Setup after calibrating, "Calibration complete ✓ HH:mm"; the manual box and F6 bring the owner to Setup and stay | Task 3 (ruling 1; `TestSetupModel`, `TestSetupScreen`); Task 4 (`f6PausesTheDrill…` asserts Setup) |
| 2. Calibration pauses the drill (F6 included); it stays paused after success or Cancel, never reset; no exercise API change; the arena covered; detection stays off under a paused drill; drills without Pause stopped and restarted as before, camera drills left alone | Task 4 (rulings 2–4; `TestCalibrationPausesTheDrill`, `TestCalibrationController`, `TestArenaViews`, `TestExerciseHostSupport`) |
| 3. No false "moved": the median of five on both sides (the median saved after an auto-calibration, the box saved as the box); the tolerance `max(8 px, 2%)`; kept / kept at the fresh median / moved beyond twice; only once the arena fills the projector, after a settle; the 8 s limit; INFO logging; detection off while any pattern shows | Task 7 (ruling 5; `TestPatternMeasurement`, `TestCalibrationCheck`); Task 8 (rulings 6, 7, 14; `TestRememberedCalibration`, `TestArenaWindow`, `TestSetupScreen`) |
| 4. The arena opens at launch when a projector screen is found; with none, nothing opens and Setup says so, looking again every couple of seconds; the check then; never blocks or calibrates | Task 9 (ruling 13; `TestNothingBlocks`, `TestSetupScreen`) |
| 5. Only real cameras, once each; a node that can't be asked is still listed; the JavaFX list too | Task 1 (ruling 12; `TestV4l2Controls`) |
| 6. A lost camera reconnects by itself, in the background; the picker stays, saying what it waits for | Task 6 (ruling 9; `TestProblems`, `TestProblemViews`) |
| 7. Losing the camera keeps the arena open, uncalibrated, and pauses the drill; "No camera"; on return the check if Remember is on, else Calibrate highlighted; switching cameras the same | Task 5 (ruling 8; `TestProblems`, `TestRememberedCalibration`) |
| 8. A stale pick never crashes: the name kept; "not connected"; found by name when back; the safety net logs and shows, never exits; the JavaFX app keeps working | Tasks 1, 2 (rulings 10, 11; `TestSarxosCaptureCamera`, `TestProblems`, `TestUiErrors`); Task 10 Step 6 |
| Nothing blocks, rules 1 and 3 revised; rules 2, 4, 5, 6 stand | Tasks 8, 9 (`TestNothingBlocks.rule1…`, `rule3…AtItsTimeLimit`); Plan 6's rule tests keep passing |
| Deferred minors folded in: the stale projector hint; the pattern under the label; the fast flap (N1); `calibrationSucceeded` setting the destination | Tasks 9, 4 and 8, 8, 3 |
| Success criteria 6 (revised), 7 (with no false "moved"), 9, 10, 11; §1 criteria 1–5; Plan 6's criteria 1–8 | Task 10, Step 3 items 1–12, Steps 4–6 |
| Delivery: Plan 7 on `compose-ui`, then the owner's re-check including Plan 6's unreached items | this plan; Task 10 |
