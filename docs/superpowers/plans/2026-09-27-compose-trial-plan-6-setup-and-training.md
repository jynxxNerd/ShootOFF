# Compose Trial Plan 6: Setup and Training — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate setup from training in the Compose app (spec §8, Revision 1): a Setup destination (camera → projector → calibrate, with an optional remembered calibration that is checked automatically and a calibration view), a Range screen that is for training only, and the "Nothing blocks" rules, leaving the JavaFX app working exactly as before.

**Architecture:**
- **Tasks 1–2: `core`, no UI.**
  - Task 1: `Settings` learns the remembered calibration (`SavedCalibration`, six additive `shootoff.arena.calibration.*` keys) and carries keys it doesn't know through a save, so the JavaFX app keeps the Compose app's keys when it rewrites `shootoff.properties`.
  - Task 2: `CalibrationCheck<F>`, a pure unit that compares the pattern found in camera frames with the saved bounds (5 px, 3 s), driven by the caller's frames and clock; `PatternDetector` runs auto-calibration's first step on one frame for it.
- **Task 3: calibration only on request.** Opening the arena, or the arena going full screen, no longer starts calibration in Compose. `CalibrationController` gets `start()`, `cancel()` (the arena and camera left as they were) and `applySaved()` (through a new `CalibrationFlow.applySaved`), and reports a success to the app.
- **Task 4: remember calibration and the automatic check** in `AppState`: saving after a calibration while Remember is on, and, when the arena opens, a background check (`CalibrationCheckRun`) that keeps the saved calibration, reports drift, or gives up as "not verified".
- **Task 5: the Setup destination:** three steps with their states, the camera feed with the calibration overlay and the calibrated rectangle, Remember calibration, Show grid (an alignment grid on the arena), and F6 opening Setup to calibrate.
- **Task 6: Range for training only:** the status chip, Clear shots, the drill picker and the drill card's Start/Stop, the not-ready prompt. The Camera | Arena switch, the in-app Arena view as a main view and F2 go.
- **Task 7: Drills becomes a library.**
- **Task 8: nothing starts at launch** (only the camera, in the background) and the UI thread never waits.
- **Task 9: the owner's hardware check**, spec success criteria 1–8, a JavaFX regression check and a 20-minute FPS and memory watch. It replaces Plan 5's Task 15 checklist.

**Tech Stack:**
- Java 21 (`core`, `plugin-api`, `javafx-app`) and Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- Compose Multiplatform 1.12.1 (desktop, Linux x64), Material 3 1.9.0, Material icons core 1.7.3, kotlinx.coroutines (from Compose).
- OpenCV (JavaCV) for the pattern detection, already in `core`.
- JUnit 5 for unit tests; Compose UI tests on JUnit 4 (`v2.createComposeRule`) through the vintage engine.

**Spec:** `docs/superpowers/specs/2026-09-26-compose-trial-design.md`. This plan implements §8 "Revision 1 (2026-09-27): setup separated from training", which supersedes the conflicting parts of §1 (layout and destinations), §2 "Inside compose-app" (screens) and §4 (shortcuts). Where §8 and this plan's brief disagree, §8 wins.

Plan 5 is done, with its review fixes: the gate stands at **624**.

**Prototype.** The whole plan was built in a scratch clone of `compose-ui` at `57a44749`, one commit per task. Every task compiled, and the gate ran green at each task's commit with the counts given below, ending at **679/679**. The code blocks below are the prototype's files and edits, extracted from those commits. The GUI apps were not run; Task 9 is the owner's.

## Plan-author rulings

Where §8 is silent, or the code disagrees with it, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong. Rulings 1, 5 and 6 are also listed for the owner at the end of this section.

1. **At launch only the camera opens, in the background.**
   - §8 rule 1: "The app opens on Range with the camera feed (or the no-camera panel). It never opens the arena, shows a pattern or calibrates by itself." So the start camera still opens, but through `AppState.launch()` (Task 8), which finds and opens it on the I/O dispatcher. Today `Main` opens it on the main thread before the window exists.
   - Nothing else starts: no arena, no calibration, no check (the check only ever runs when the owner opens the arena).

   *Cost if wrong:* if the owner wants no camera at launch either, `launch()` loses its one call and the Range shows the no-camera panel with its picker.
2. **The remembered calibration lives in `shootoff.properties`, through `core`'s `Settings`.**
   - Six keys, all additive and written only while in use: `shootoff.arena.calibration.remember` (only when on), and `.camera`, `.feed`, `.screen`, `.bounds`, `.paper` (only while a calibration is saved; `.paper` only if auto-calibration found the paper). A file that never had them is written exactly as before.
   - `Settings` knows the keys, so the JavaFX app on this branch keeps them when it saves. It also now carries any key it doesn't know through a save instead of dropping it, so a key from a newer build survives an older one. `TestConfigurationKeepsComposeKeys` pins both through the JavaFX `Configuration`.
   - A saved calibration that can't be read is dropped (logged at WARN), never a `ConfigurationException`.

   *Cost:* the JavaFX app built from `master` still drops the keys when it saves; the owner then recalibrates once. An unknown key someone added by hand now survives saves.
3. **What is remembered, and when.**
   - Saved: the camera's name, its feed size, the projector screen's size (the arena placement's screen), the projection in camera pixels, and the paper size if auto-calibration found one.
   - The calibrated feed behavior is not copied: it is already saved as `shootoff.arena.calibrated.behavior`, and applying a remembered calibration uses it.
   - Saved after every calibration that finds the projection while Remember is on; turning Remember on saves the arena's current calibration; turning it off forgets the saved one and quietly stops a check under way.
   - A calibration made without a projector screen (the arena as a window) isn't remembered: it can't be matched to a screen next time.
   - Applied only if the camera name, the feed size and the projector screen size all match; otherwise the Calibrate step says which one differs and asks for a recalibration (§8).

   *Cost:* a projector at the same resolution on a different port still counts as the same projector.
4. **The check.**
   - `CalibrationCheck<F>` (`core`) is pure: the caller offers frames and ticks, and a clock decides the 3 s limit. Kept: a detection with every edge within 5 camera pixels of the saved one. Moved: two detections in a row that agree with each other within 5 px but not with the saved bounds, so one frame caught mid-change isn't reported as drift. Not verified: no answer in 3 s, no camera, or cancelled. The first outcome is final.
   - The detector (`PatternDetector`) runs auto-calibration's first step (grayscale, equalized, the chessboard, the pattern's corners) on its own `AutoCalibrationManager`, so the camera's own calibration state is never touched.
   - Frames come from the Compose feed (`ComposeCameraView.frameTap`, on the camera's thread); only the newest is kept, and it is looked at every 50 ms on the app's background dispatcher. `Camera.getBufferedImage()` isn't used: it reads the device, racing the camera's own thread.
   - The check starts when the arena first reports full screen; the pattern on a window still on its way to the projector would be measured in the wrong place. The 3 s count from the pattern showing. "About a second" is not enforced: a check that keeps the calibration ends at its first good frame, usually well within a second.
   - Shot detection is off while the pattern shows and back 600 ms after (calibration's `DETECTION_RESTART_DELAY`).

   *Cost:* an arena window that never reaches full screen leaves "Checking the saved calibration…" (with Cancel) showing until the owner cancels, calibrates or closes the arena. On a found projector the arena window always goes full screen within about 3 s (Plan 5's ruling 8), so this needs a window manager that refuses it.
5. **A kept calibration is applied the way a manual-box calibration is.**
   - `CalibrationFlow.applySaved` (new, `core`) sets the projection on the arena and the camera, the calibrated feed behavior and the perspective, and stops and restarts nothing.
   - The camera isn't auto-calibrated: frames aren't perspective-warped before shot detection (`CameraManager`'s `cameraAutoCalibrated` stays false) and auto-calibration's exposure step doesn't run.

   *Cost:* on a keystoned projection, shots may land a pixel or two off compared with a fresh auto-calibration, and the camera keeps its default exposure. The alternative is a silent full auto-calibration as the check (it flashes white for the exposure step and takes longer). For the owner to decide after Task 9.
6. **A manual-box calibration is remembered and checked like any other.** Its bounds are where the owner put the box; the check compares them with the pattern the camera finds.

   *Cost:* a box placed a few pixels off the projection is reported as moved every time. The alternative is not remembering manual calibrations. For the owner to decide after Task 9.
7. **Calibration only on request (§8 rule 2; this reverses Plan 5's ruling 9 in Compose only).**
   - Opening the arena makes it calibratable (the controller exists, so Calibrate and F6 work) but doesn't calibrate. The arena going full screen reaches the flow only while it is calibrating.
   - `CalibrationController.start()` starts the flow through `setFullScreen(arena.fullScreen.value)`: the flow reads the arena's placement as it starts, so a start on an arena already full screen looks for the pattern at once and its 12 s timeout still reaches the manual box. (`flow.start()` alone would leave the flow's own full-screen flag false, and the timeout would wait forever.)
   - The JavaFX app is unchanged: its arena still calibrates as it opens.

   *Cost:* none known.
8. **Cancel (§8 rule 5).**
   - The overlay's Stop becomes Cancel; the manual box keeps Done and gets Cancel too. Setup's Calibrate step shows Cancel while calibrating or checking.
   - Cancel is `CalibrationFlow.cancel()` plus putting back what calibration changed: the arena's background, targets, shots and "Needs Calibration" label, and the camera's projection bounds from before it started. The arena and the camera are left exactly as they were.
   - A projector drill calibration stopped stays stopped (`CalibrationFlow.cancel()` never restarts it).

   *Cost:* after a cancelled calibration the owner presses Start again.
9. **Where calibration is seen.**
   - Only on Setup: the overlay and the box are on Setup's camera feed. Range never shows them.
   - Auto-calibration timing out to the manual box brings the owner to Setup (`CalibrationViews.showCalibratingFeed`). A calibration that succeeds while Setup is showing returns to Range (§8).
   - Leaving Setup doesn't stop calibration; Range's chip says "Calibrating…" and leads back.

   *Cost:* none known.
10. **F6 (§8 "Removed").** F6 opens Setup and starts calibrating if there is a camera and an arena. It never stops a calibration (Plan 5's F6 toggled). Without a camera or an arena it only opens Setup, where the missing step is highlighted. F3, F4 and the arena's F11 are unchanged; F2 does nothing.

    *Cost:* none known.
11. **Show grid.**
    - Drawn by `ArenaCanvas` over everything on the arena (black, white lines every tenth of the width and height, orange marks at the corners and the center), not as a background, so nothing has to be saved and restored.
    - Off on leaving Setup, starting a drill, starting calibration or a check; the switch is disabled while calibrating, while checking and while a projector drill runs (§8: "Range and running drills are never affected").

    *Cost:* none known.
12. **The Range screen (§8 "Range").**
    - Top left: the status chip (`calibrationSummary`: what is missing, what is under way, or "✓ Calibrated HH:mm"; it opens Setup) and Clear shots (F4's action). Reset goes; the region command `reset` still works.
    - Top right: the drill picker (the first drill in the catalog until the owner picks one; disabled while a drill runs) and the drill card: Start while idle, then the drill's texts, its own buttons and Stop.
    - "Pause" is the drill's own button, as in Plan 5 (the v2 API has no host-level pause), and F3 still presses it.
    - A projector drill starts only with a camera, the arena open and calibrated, and neither calibration nor the check under way (`AppState.projectorReady`, checked in `startDrill`). Plan 5's guard in `ExerciseRunner` stays.

    *Cost:* a drill without its own pause button has no Pause.
13. **The not-ready prompt.** It shows over the feed while there is a camera and the arena isn't open and calibrated. With no camera, the no-camera panel (which already offers the cameras) is Range's prompt, so two cards never cover each other. Skip hides it until the camera drops or the arena closes, whoever closes it (§8: "It comes back if the camera drops or the arena closes").

    *Cost:* none known.
14. **Removed (§8 "Removed").** `BigView`, `AppState.view`/`showView`, the switch, F2 (`Shortcut.SWITCH_VIEW`) and `UiPrefs.view` go; an old stored `view` preference is ignored. `ArenaView` stays only as Setup's small preview. The Drills screen keeps its rows and always marks projector drills "Needs the projector arena", with no Start or Stop.

    *Cost:* none known.
15. **Existing tests change, because §8 reverses Plan 5 behavior they pin.** Each change is in the task that changes the behavior:
    - Task 3: `TestCalibrationController.closingThenReopeningStartsFreshWithNoStaleState` becomes `…StartsFreshOnlyWhenAskedWithNoStaleState`; `TestCalibrationOverlay.whileLookingForThePatternTheUserCanStop` becomes `…CanCancel`; `TestProblems.openingACameraAfterTheArenaGetsCalibrationToo` becomes `openingACameraAfterTheArenaMakesItCalibratableWithoutCalibrating`; five tests that expected the arena to calibrate as it opened now call `startCalibration()` first (`TestAppState`, `TestScreens` ×2, `TestProblemViews`, `TestProblems`).
    - Task 6: `TestScreens` loses the two switch tests, `TestShortcuts` the F2 test, `TestAppState` `theArenaViewNeedsAnOpenArena`; `TestUiPrefs` stops checking `view`; three `TestAppState` projector-drill tests set the arena up and calibrate first.
    - Task 7: `TestScreens` loses the two Drills start tests; its projector-drill test becomes `drillsIsALibraryMarkingProjectorDrillsWithNoStartOrStop`.

    *Cost:* none; the baseline's Java 8 tests are all unchanged.
16. **Compose tests never read a saved `Settings` back.** The save writes an (empty) webcam list, and reading one back enumerates the machine's webcams through OpenCV. Compose tests read the saved keys as plain `Properties`. The `core` and JavaFX tests read back, as `TestSettings` already does.

    *Cost:* none known.

**Changes to JavaFX behavior:** one. When the JavaFX app saves `shootoff.properties` it now keeps keys it doesn't know (and the six new ones) instead of dropping them. Its UI, its calibration and everything else are unchanged; `CalibrationFlow.applySaved`, `CalibrationCheck` and `PatternDetector` are new code it never calls. No public or protected JavaFX member changes.

**For the owner to decide** (none blocks the plan):
- Ruling 1: the camera opens at launch, as §8 rule 1 says (this plan's brief said no camera opens).
- Ruling 5: a remembered calibration skips auto-calibration's perspective warp and exposure step.
- Ruling 6: manual-box calibrations are remembered and checked against the detected pattern.

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** no `Co-Authored-By` or any other trailer in commit messages. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- **Code style:**
  - Java: tabs; match the surrounding code. A new main-source Java file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file), then a blank line.
  - Kotlin: the official style (4 spaces, trailing commas). A new main-source `.kt` file starts with the same 17 lines, then a blank line.
  - Test files have no header. The code blocks below start at the `package` line.
  - Keep each file's imports sorted as the file already sorts them.
- **Packages:** `com.shootoff.*`. New `core` classes go in `com.shootoff.config`, `com.shootoff.calibration` and `com.shootoff.camera.autocalibration`; new `compose-app` classes in `com.shootoff.compose.app`, `.calibration` and `.arena`.
- **Dependencies:** none added anywhere. `compose-app` still depends on `core`, `plugin-api` and Plan 5's catalog entries only; no OpenJFX in `compose-app` (`TestNoJavaFxInComposeApp` keeps passing).
- **Formats:** `.target`, `.course`, the session formats and `shootoff.xml` descriptors are unchanged. `shootoff.properties` gains only the additive `shootoff.arena.calibration.*` keys of Task 1 (§8), written only while in use.
- **Tests and the owner's settings:**
  - Every `Settings` a test builds is on `ScratchConfig.emptyFile()` (or a `@TempDir` file in `core`). No test reads or writes the working tree's `shootoff.properties`.
  - `compose-app` tests never read a saved `Settings` back (ruling 16); they read the file as `Properties`.
  - `UiPrefs` in tests is `PrefsStore.Memory`.
- **The owner's files.** Never modify, move, delete or stage:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - anything under `exercises/` (the owner's `RandomTargetParDrill.jar` and `RandomTargetParDrill-v2.jar`)
  - anything under `exercise-data/`
  - any file the owner added under `targets/` or `courses/`
  - the owner's Java preferences
  No test runs the drill.
- **The JavaFX app behaves as before**, except that its saves keep keys it doesn't know (ruling 2). `./gradlew run` starts only the JavaFX app; `./gradlew :compose-app:run` starts the Compose app.
- **Publishing:** only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`. No task here publishes.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- Implementers never run the GUI apps; Task 9 is the owner's.
- **Test gate** (unchanged from Plans 1–5; run it in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10` if the shell doesn't already point at a Java 21):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. Use a Bash timeout of 600000 ms.
  - The `N/M passing` count starts at **624** (end of Plan 5). Each task gives the count it ends at. A lower count means tests silently stopped running; Tasks 6 and 7 delete tests on purpose (ruling 15), and their counts allow for it.
  - The Compose UI tests need a display, as the JavaFX tests do. The gate runs them on this machine's `DISPLAY=:0`.
  - The gate leaves `shootoff.properties` unchanged.

## Review Focus

1. **The webcam is unplugged while the check is looking for the pattern.** Expected: the check stops without a word, the frame tap is removed, and the arena the camera calibrated closes (Plan 5's rule), so no pattern is left on the projector and nothing waits for frames that will never come.
   - *Test:* `TestRememberedCalibration.unpluggingTheCameraMidCheckStopsItAndClosesTheArena` (Task 4)
2. **The arena window is closed mid-check or mid-calibration.** Expected: the check stops and looks at no more frames; calibration is cancelled with the camera left not calibrating (Plan 5's `arenaClosing`); nothing is saved.
   - *Tests:* `TestRememberedCalibration.closingTheArenaMidCheckStopsIt` (Task 4); Plan 5's `TestCalibrationController.closingTheArenaWhileCalibratingEndsCalibrationAndForgetsTheProjection` keeps passing
3. **Remember calibration is turned off while a check is running.** Expected: the check stops quietly (no "not verified"), the pattern goes, the arena stays uncalibrated, and the saved keys are removed from the file.
   - *Test:* `TestRememberedCalibration.turningRememberOffMidCheckStopsItQuietly` (Task 4)
4. **The saved calibration was made for another projector resolution** (or another camera, or feed size). Expected: no pattern is shown and nothing is applied; the Calibrate step says what differs, for example "The saved calibration was made for a 1920×1080 projector; this one is 1280×720 — recalibrate".
   - *Tests:* `TestRememberedCalibration.aSavedCalibrationForAnotherProjectorIsNotCheckedAndAsksForRecalibration` and `aSavedCalibrationFitsOnlyItsOwnCameraFeedAndProjector` (Task 4)
5. **F6 is pressed while a projector drill runs.** Expected: Setup opens, the drill stops before the pattern shows, calibration runs, and on success a fresh drill starts and the app is back on Range.
   - *Test:* `TestShortcuts.f6DuringAProjectorDrillStopsItCalibratesAndStartsItAfresh` (Task 5)

The owner's check (Task 9) covers what no unit test reaches: the webcam and the projector, real detection of the pattern, a moved camera, and the drill played through.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/…/config/{SavedCalibration,Settings}.java`; `javafx-app` test `config/TestConfigurationKeepsComposeKeys` | the remembered calibration's keys; unknown keys kept | 1 |
| `core/…/calibration/CalibrationCheck.java`, `core/…/camera/autocalibration/PatternDetector.java` | the check, and the pattern found in one frame | 2 |
| `core/…/calibration/CalibrationFlow.java` (`applySaved`); `…/compose/calibration/{CalibrationController,CalibrationOverlay}.kt`; `…/compose/app/{AppState,RangeScreen,Shortcuts}.kt` | calibration only on request, Cancel, apply a saved calibration | 3 |
| `…/compose/calibration/RememberedCalibration.kt`; `…/compose/feed/ComposeCameraView.kt` (frame tap); `…/compose/app/AppState.kt` | remember calibration and the automatic check | 4 |
| `…/compose/app/{SetupScreen,SetupSteps}.kt`, `…/compose/calibration/ProjectionOutline.kt`; `…/compose/arena/{ArenaModel,ArenaCanvas}.kt` (grid); `…/compose/shell/Rail.kt`; `…/compose/app/{AppState,ShootOffApp,Shortcuts,SettingsScreen}.kt` | the Setup destination, the calibration view, F6 | 5 |
| `…/compose/app/RangeControls.kt`; `…/compose/app/{RangeScreen,AppState,Shortcuts,UiPrefs}.kt`; `…/compose/drill/DrillCard.kt`; `…/compose/arena/{ArenaModel,ArenaCanvas}.kt` (comments) | Range for training; the switch, F2 and the view preference go | 6 |
| `…/compose/app/DrillsScreen.kt` | Drills as a library | 7 |
| `…/compose/app/AppState.kt` (`launch`), `…/compose/Main.kt` | nothing starts at launch | 8 |
| none | the owner's check | 9 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`, with tests under `core/src/test/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | Test methods added (−removed) | Gate |
|---|---|---|
| 1 | `core` `config/TestSavedCalibrationSettings` (5); `javafx-app` `config/TestConfigurationKeepsComposeKeys` (1) | 630 |
| 2 | `core` `calibration/TestCalibrationCheck` (6), `camera/autocalibration/TestPatternDetector` (2) | 638 |
| 3 | `core` `TestCalibrationFlow` (+2); `calibration/TestCalibrationOnRequest` (6), `TestCalibrationOverlay` (+1); `app/TestNothingBlocks` (1) | 648 |
| 4 | `app/TestRememberedCalibration` (12), `TestNothingBlocks` (+1) | 661 |
| 5 | `app/TestSetupSteps` (2), `TestSetupModel` (3), `TestSetupScreen` (5), `TestShortcuts` (+2), `TestNothingBlocksViews` (1); `arena/TestArenaGrid` (1) | 675 |
| 6 | `app/TestRangeScreen` (6), `TestNothingBlocksViews` (+1), `TestAppState` (+1 −1); `TestScreens` (−2), `TestShortcuts` (−1) | 679 |
| 7 | `TestScreens` (−2) | 677 |
| 8 | `TestNothingBlocks` (+2) | 679 |

"Nothing blocks" (§8), one test per rule, all in `app/TestNothingBlocks` and `app/TestNothingBlocksViews`:

| Rule | Test | Task |
|---|---|---|
| 1. Nothing starts on its own at launch | `TestNothingBlocks.rule1AtLaunchOnlyTheCameraOpensAndNothingElseStarts` | 8 |
| 2. Calibration runs only when asked | `TestNothingBlocks.rule2CalibrationRunsOnlyWhenAskedNeverBecauseTheArenaOpened` | 3 |
| 3. The check never blocks; it gives up quietly | `TestNothingBlocks.rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAfterThreeSeconds` | 4 |
| 4. Missing hardware is a state | `TestNothingBlocksViews.rule4WithNoCameraAndNoProjectorEachSaysSoAndTheRestOfTheAppWorks` | 6 |
| 5. Everything that runs can be stopped | `TestNothingBlocksViews.rule5CancelIsOnScreenWhileTheCheckRunsAndWhileCalibrating` | 5 |
| 6. The UI thread never waits | `TestNothingBlocks.rule6TheUiThreadNeverWaitsOnTheCamera` | 8 |

---

### Task 1: Remember-calibration keys in core `Settings`, kept by the JavaFX app's saves

**Files:**
- Create: `core/src/main/java/com/shootoff/config/SavedCalibration.java`
- Modify: `core/src/main/java/com/shootoff/config/Settings.java`
- Test: `core/src/test/java/com/shootoff/config/TestSavedCalibrationSettings.java`, `javafx-app/src/test/java/com/shootoff/config/TestConfigurationKeepsComposeKeys.java`

**Interfaces:**
- Consumes: `core`'s `Settings`, `com.shootoff.geom.Rect` and `Size`; the test fixtures' `ScratchConfig`; the JavaFX `Configuration` (a `Settings` subclass).
- Produces (`com.shootoff.config`):
  - `public record SavedCalibration(String camera, Size feed, Size screen, Rect bounds, Optional<Size> paper)`: all components non-null
  - on `Settings`: `boolean rememberCalibration()`, `void setRememberCalibration(boolean)`, `Optional<SavedCalibration> getSavedCalibration()`, `void setSavedCalibration(SavedCalibration)` (`null` forgets it)
  - keys `shootoff.arena.calibration.remember` (`true`, only when on), `.camera`, `.feed` (`640.0x480.0`), `.screen` (`1280.0x720.0`), `.bounds` (`minX,minY,width,height`), `.paper` (`width,height`, optional)
  - `writeConfigurationFile()` writes back every key the file had that `Settings` doesn't know

**Why both.** The keys are typed in `Settings`, so both apps read and write them. Carrying unknown keys through a save as well means a key a later build adds survives this build's JavaFX saves (ruling 2).

- [ ] **Step 0: Record the owner's files**

```bash
cd /home/bfears/projects/ShootOFF
sha256sum RandomTargetParDrill-bests.properties exercises/RandomTargetParDrill.jar exercises/RandomTargetParDrill-v2.jar shootoff.properties > build/plan6-owner-files.sha256
cat build/plan6-owner-files.sha256
```

Expected: four checksum lines. No gate run of this plan changes any of them. Task 9 checks the first three after the owner has used the apps.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/config/TestSavedCalibrationSettings.java`:

```java
package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

class TestSavedCalibrationSettings {
	private static final SavedCalibration SAVED = new SavedCalibration("UVC Camera (046d:0825) /dev/video0",
			new Size(640, 480), new Size(1280, 720), new Rect(101.5, 80, 400, 300), Optional.of(new Size(11, 8.5)));

	@BeforeAll
	static void setUpHome() {
		// Writing always stores the (empty) webcam list; reading that back enumerates cameras through OpenCV
		Loader.load(opencv_java.class);
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	private static Settings settings(File file) throws Exception {
		return new Settings(file.getPath(), new String[0]);
	}

	@Test
	void aNewFileRemembersNothing() throws Exception {
		final Settings settings = settings(ScratchConfig.emptyFile());

		assertFalse(settings.rememberCalibration());
		assertEquals(Optional.empty(), settings.getSavedCalibration());
	}

	@Test
	void aRememberedCalibrationReadsBackUnchanged() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings written = settings(file);
		written.setRememberCalibration(true);
		written.setSavedCalibration(SAVED);
		assertTrue(written.writeConfigurationFile());

		final Settings read = settings(file);

		assertTrue(read.rememberCalibration());
		assertEquals(Optional.of(SAVED), read.getSavedCalibration());
	}

	@Test
	void withRememberOffAndNothingSavedNoCalibrationKeyIsWritten() throws Exception {
		final File file = ScratchConfig.emptyFile();
		final Settings settings = settings(file);
		settings.setRememberCalibration(true);
		settings.setSavedCalibration(SAVED);
		settings.writeConfigurationFile();

		settings.setRememberCalibration(false);
		settings.setSavedCalibration(null);
		settings.writeConfigurationFile();

		assertFalse(Files.readString(file.toPath(), StandardCharsets.ISO_8859_1).contains("shootoff.arena.calibration."));
	}

	@Test
	void anUnreadableSavedCalibrationIsDroppedNotAnError() throws Exception {
		final File file = ScratchConfig.emptyFile();
		Files.write(file.toPath(), List.of("shootoff.arena.calibration.remember=true",
				"shootoff.arena.calibration.camera=C270", "shootoff.arena.calibration.feed=640x480",
				"shootoff.arena.calibration.screen=wide", "shootoff.arena.calibration.bounds=1,2,3,4"),
				StandardCharsets.ISO_8859_1);

		final Settings settings = settings(file);

		assertTrue(settings.rememberCalibration());
		assertEquals(Optional.empty(), settings.getSavedCalibration());
	}

	@Test
	void aKeyThisVersionDoesntKnowSurvivesASave() throws Exception {
		final File file = ScratchConfig.emptyFile();
		Files.write(file.toPath(), List.of("shootoff.markerradius=6", "shootoff.compose.future=kept as is"),
				StandardCharsets.ISO_8859_1);

		final Settings settings = settings(file);
		settings.setMarkerRadius(7);
		settings.writeConfigurationFile();

		final String saved = Files.readString(file.toPath(), StandardCharsets.ISO_8859_1);
		assertTrue(saved.contains("shootoff.compose.future=kept as is"));
		assertTrue(saved.contains("shootoff.markerradius=7"));
	}
}
```

`javafx-app/src/test/java/com/shootoff/config/TestConfigurationKeepsComposeKeys.java`:

```java
package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/** The JavaFX app rewrites shootoff.properties; what the Compose app keeps there must survive it. */
class TestConfigurationKeepsComposeKeys {
	private static final List<String> COMPOSE_KEYS = List.of("shootoff.arena.calibration.remember=true",
			"shootoff.arena.calibration.camera=C270", "shootoff.arena.calibration.feed=640.0x480.0",
			"shootoff.arena.calibration.screen=1280.0x720.0",
			"shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0",
			"shootoff.compose.unknown=a key no version of the JavaFX app knows");

	@BeforeAll
	static void setUpHome() {
		Loader.load(opencv_java.class);
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	@AfterEach
	void leaveAConfigurationCurrent() throws ConfigurationException {
		// Later tests in this JVM expect Configuration.getConfig() to be non-null
		new Configuration(new String[0]);
	}

	@Test
	void aJavaFxSaveKeepsTheComposeAppsKeys() throws Exception {
		final File file = ScratchConfig.emptyFile();
		Files.write(file.toPath(), COMPOSE_KEYS, StandardCharsets.ISO_8859_1);

		// What the JavaFX preferences window does when the user presses Save
		final Configuration config = new Configuration(file.getPath(), new String[0]);
		config.setMarkerRadius(8);
		assertTrue(config.writeConfigurationFile());

		final List<String> saved = Files.readAllLines(file.toPath(), StandardCharsets.ISO_8859_1);
		assertTrue(saved.containsAll(COMPOSE_KEYS), () -> "saved: " + saved);
		assertTrue(saved.contains("shootoff.markerradius=8"));
		assertEquals(Optional.of(new Rect(100, 80, 400, 300)),
				new Configuration(file.getPath(), new String[0]).getSavedCalibration().map(c -> c.bounds()));
		assertEquals(new Size(1280, 720), config.getSavedCalibration().get().screen());
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.config.TestSavedCalibrationSettings' --console=plain`

Expected: `compileTestJava` FAILS with `cannot find symbol` for `class SavedCalibration`.

- [ ] **Step 3: Write `SavedCalibration` and the keys in `Settings`**

`core/src/main/java/com/shootoff/config/SavedCalibration.java`:

```java
package com.shootoff.config;

import java.util.Objects;
import java.util.Optional;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * A projector calibration the Compose app keeps between sessions ("Remember calibration"): where the
 * projection was on the camera's feed, and the camera and projector screen it was made with. The JavaFX
 * app carries it through its saves and never uses it.
 *
 * @param camera
 *            the calibrating camera's name
 * @param feed
 *            the camera's feed size when it was calibrated
 * @param screen
 *            the projector screen's size, which is the arena's size when full screen
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
		Objects.requireNonNull(bounds, "bounds");
		Objects.requireNonNull(paper, "paper");
	}
}
```

`core/src/main/java/com/shootoff/config/Settings.java`:

Replace:

```java
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
```

with:

```java
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
```

Replace:

```java
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.shootoff.geom.Point;

/**
 * Parses, stores and updates ShootOFF's persisted preferences (shootoff.properties) and
```

with:

```java
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * Parses, stores and updates ShootOFF's persisted preferences (shootoff.properties) and
```

Replace:

```java
	private static final String POI_ADJUSTMENT_X = "shootoff.poiadjust.x";
	private static final String POI_ADJUSTMENT_Y = "shootoff.poiadjust.y";

	protected static final String MARKER_RADIUS_MESSAGE = "MARKER_RADIUS has an invalid value: %d. Acceptable values are "
			+ "between 1 and 20.";
```

with:

```java
	private static final String POI_ADJUSTMENT_X = "shootoff.poiadjust.x";
	private static final String POI_ADJUSTMENT_Y = "shootoff.poiadjust.y";

	// The Compose app's remembered calibration (see SavedCalibration). Written only while in use, so a
	// file that never had them is saved exactly as before.
	private static final String REMEMBER_CALIBRATION_PROP = "shootoff.arena.calibration.remember";
	private static final String SAVED_CALIBRATION_CAMERA_PROP = "shootoff.arena.calibration.camera";
	private static final String SAVED_CALIBRATION_FEED_PROP = "shootoff.arena.calibration.feed";
	private static final String SAVED_CALIBRATION_SCREEN_PROP = "shootoff.arena.calibration.screen";
	private static final String SAVED_CALIBRATION_BOUNDS_PROP = "shootoff.arena.calibration.bounds";
	private static final String SAVED_CALIBRATION_PAPER_PROP = "shootoff.arena.calibration.paper";

	// Every key this class reads; any other key in the file is carried through a save untouched
	private static final Set<String> KNOWN_KEYS = Set.of(FIRST_RUN_PROP, ERROR_REPORTING_PROP, IPCAMS_PROP,
			WEBCAMS_PROP, RECORDING_WEBCAMS_PROP, MARKER_RADIUS_PROP, IGNORE_LASER_COLOR_PROP,
			USE_RED_LASER_SOUND_PROP, RED_LASER_SOUND_PROP, USE_GREEN_LASER_SOUND_PROP, GREEN_LASER_SOUND_PROP,
			USE_VIRTUAL_MAGAZINE_PROP, VIRTUAL_MAGAZINE_CAPACITY_PROP, USE_MALFUNCTIONS_PROP,
			MALFUNCTIONS_PROBABILITY_PROP, ARENA_POSITION_X_PROP, ARENA_POSITION_Y_PROP, MUTED_CHIME_MESSAGES,
			PERSPECTIVE_WEBCAM_DISTANCES, CALIBRATED_FEED_BEHAVIOR_PROP, SHOW_ARENA_SHOT_MARKERS,
			CALIBRATE_AUTO_ADJUST_EXPOSURE, SHOWED_PERSPECTIVE_USAGE_MESSAGE, POI_ADJUSTMENT_X, POI_ADJUSTMENT_Y,
			REMEMBER_CALIBRATION_PROP, SAVED_CALIBRATION_CAMERA_PROP, SAVED_CALIBRATION_FEED_PROP,
			SAVED_CALIBRATION_SCREEN_PROP, SAVED_CALIBRATION_BOUNDS_PROP, SAVED_CALIBRATION_PAPER_PROP);

	protected static final String MARKER_RADIUS_MESSAGE = "MARKER_RADIUS has an invalid value: %d. Acceptable values are "
			+ "between 1 and 20.";
```

Replace:

```java
	private Optional<Double> poiAdjustmentY = Optional.empty();
	private boolean adjustingPOI = false;
	private int poiAdjustmentCount = 0;

	// Runtime state, never written to the configuration file
	private final Set<CameraManager> recordingManagers = new HashSet<>();
```

with:

```java
	private Optional<Double> poiAdjustmentY = Optional.empty();
	private boolean adjustingPOI = false;
	private int poiAdjustmentCount = 0;

	private boolean rememberCalibration = false;
	private Optional<SavedCalibration> savedCalibration = Optional.empty();

	// Keys read from the file that this class doesn't know, e.g. written by a newer ShootOFF
	private final Properties otherProperties = new Properties();

	// Runtime state, never written to the configuration file
	private final Set<CameraManager> recordingManagers = new HashSet<>();
```

Replace:

```java
			logger.info("POI Adjustment loaded from config, x {} y {}", poiAdjustmentX.get(), poiAdjustmentY.get());
		}

		validateConfiguration();
	}

	public boolean writeConfigurationFile() throws ConfigurationException, IOException {
```

with:

```java
			logger.info("POI Adjustment loaded from config, x {} y {}", poiAdjustmentX.get(), poiAdjustmentY.get());
		}

		if (prop.containsKey(REMEMBER_CALIBRATION_PROP)) {
			setRememberCalibration(Boolean.parseBoolean(prop.getProperty(REMEMBER_CALIBRATION_PROP)));
		}

		savedCalibration = readSavedCalibration(prop);

		for (final String key : prop.stringPropertyNames()) {
			if (!KNOWN_KEYS.contains(key)) otherProperties.setProperty(key, prop.getProperty(key));
		}

		validateConfiguration();
	}

	// A saved calibration that can't be read is dropped (and so recalibrated), never an error
	private static Optional<SavedCalibration> readSavedCalibration(Properties prop) {
		final String camera = prop.getProperty(SAVED_CALIBRATION_CAMERA_PROP);
		final String feed = prop.getProperty(SAVED_CALIBRATION_FEED_PROP);
		final String screen = prop.getProperty(SAVED_CALIBRATION_SCREEN_PROP);
		final String bounds = prop.getProperty(SAVED_CALIBRATION_BOUNDS_PROP);
		if (camera == null || feed == null || screen == null || bounds == null) return Optional.empty();

		try {
			final double[] b = numbers(bounds, ",", 4);
			final String paper = prop.getProperty(SAVED_CALIBRATION_PAPER_PROP);
			return Optional.of(new SavedCalibration(camera, size(feed, "x"), size(screen, "x"),
					new Rect(b[0], b[1], b[2], b[3]),
					paper == null ? Optional.empty() : Optional.of(size(paper, ","))));
		} catch (final IllegalArgumentException e) {
			logger.warn("Ignoring the saved calibration, which can't be read: {}", e.getMessage());
			return Optional.empty();
		}
	}

	private static Size size(String value, String separator) {
		final double[] parts = numbers(value, separator, 2);
		return new Size(parts[0], parts[1]);
	}

	private static double[] numbers(String value, String separator, int count) {
		final String[] parts = value.split(Pattern.quote(separator));
		if (parts.length != count) throw new IllegalArgumentException("expected " + count + " numbers in " + value);

		final double[] numbers = new double[count];
		for (int i = 0; i < count; i++)
			numbers[i] = Double.parseDouble(parts[i].trim());
		return numbers;
	}

	public boolean writeConfigurationFile() throws ConfigurationException, IOException {
```

Replace:

```java
		if (isAdjustingPOI() && poiAdjustmentX.isPresent() && poiAdjustmentY.isPresent()) {
			prop.setProperty(POI_ADJUSTMENT_X, String.valueOf(poiAdjustmentX.get()));
			prop.setProperty(POI_ADJUSTMENT_Y, String.valueOf(poiAdjustmentY.get()));
		}

		final OutputStream outputStream = new FileOutputStream(configName);
```

with:

```java
		if (isAdjustingPOI() && poiAdjustmentX.isPresent() && poiAdjustmentY.isPresent()) {
			prop.setProperty(POI_ADJUSTMENT_X, String.valueOf(poiAdjustmentX.get()));
			prop.setProperty(POI_ADJUSTMENT_Y, String.valueOf(poiAdjustmentY.get()));
		}

		if (rememberCalibration) prop.setProperty(REMEMBER_CALIBRATION_PROP, "true");

		if (savedCalibration.isPresent()) {
			final SavedCalibration saved = savedCalibration.get();
			prop.setProperty(SAVED_CALIBRATION_CAMERA_PROP, saved.camera());
			prop.setProperty(SAVED_CALIBRATION_FEED_PROP, format(saved.feed(), "x"));
			prop.setProperty(SAVED_CALIBRATION_SCREEN_PROP, format(saved.screen(), "x"));
			final Rect b = saved.bounds();
			prop.setProperty(SAVED_CALIBRATION_BOUNDS_PROP,
					b.getMinX() + "," + b.getMinY() + "," + b.getWidth() + "," + b.getHeight());
			saved.paper().ifPresent(paper -> prop.setProperty(SAVED_CALIBRATION_PAPER_PROP, format(paper, ",")));
		}

		for (final String key : otherProperties.stringPropertyNames()) {
			prop.setProperty(key, otherProperties.getProperty(key));
		}

		final OutputStream outputStream = new FileOutputStream(configName);
```

Replace:

```java
	}

	/**
	 * Adds a camera whose video is saved with each shot of the session being recorded.
	 */
	public void registerRecordingCameraManager(CameraManager cm) {
```

with:

```java
	}

	/**
	 * @return whether the Compose app keeps the arena's calibration for the next session
	 */
	public boolean rememberCalibration() {
		return rememberCalibration;
	}

	public void setRememberCalibration(boolean rememberCalibration) {
		this.rememberCalibration = rememberCalibration;
	}

	/**
	 * @return the calibration the Compose app kept, if any
	 */
	public Optional<SavedCalibration> getSavedCalibration() {
		return savedCalibration;
	}

	/**
	 * @param calibration
	 *            the calibration to keep, or <tt>null</tt> to forget it
	 */
	public void setSavedCalibration(SavedCalibration calibration) {
		savedCalibration = Optional.ofNullable(calibration);
	}

	/**
	 * Adds a camera whose video is saved with each shot of the session being recorded.
	 */
	public void registerRecordingCameraManager(CameraManager cm) {
```

Replace:

```java
	public Optional<SessionRecorder> getSessionRecorder() {
		return sessionRecorder;
	}

	private final static int POI_NUM_TARGETS = 5;
```

with:

```java
	public Optional<SessionRecorder> getSessionRecorder() {
		return sessionRecorder;
	}

	private static String format(Size size, String separator) {
		return size.getWidth() + separator + size.getHeight();
	}

	private final static int POI_NUM_TARGETS = 5;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.config.*' :javafx-app:test --tests 'com.shootoff.config.*' --console=plain`

Expected: `BUILD SUCCESSFUL`. `TestSavedCalibrationSettings` (5) and `TestConfigurationKeepsComposeKeys` (1) pass, and `TestSettings`, `TestConfiguration` and `TestConfigurationSingleton` still pass.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
```

Expected: `630/630 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add core/src/main/java/com/shootoff/config/SavedCalibration.java core/src/main/java/com/shootoff/config/Settings.java
git add core/src/test/java/com/shootoff/config/TestSavedCalibrationSettings.java javafx-app/src/test/java/com/shootoff/config/TestConfigurationKeepsComposeKeys.java
git commit -m "Keep a remembered calibration in shootoff.properties, and keep keys Settings doesn't know through a save"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 2: The calibration check, a pure unit in `core`, and the pattern detector

**Files:**
- Create: `core/src/main/java/com/shootoff/calibration/CalibrationCheck.java`, `core/src/main/java/com/shootoff/camera/autocalibration/PatternDetector.java`
- Test: `core/src/test/java/com/shootoff/calibration/TestCalibrationCheck.java`, `core/src/test/java/com/shootoff/camera/autocalibration/TestPatternDetector.java` (a new test package)

**Interfaces:**
- Consumes: `AutoCalibrationManager` (`preProcessFrame`, `findChessboard`, `calibrateFrame`, all public), `Camera.bufferedImageToMat`, `CameraCalibrationListener`; core's `pattern.png` resource (Plan 5 Task 2); the test fixtures' `MockCamera` (`setViewSize`).
- Produces:
  - `com.shootoff.calibration.CalibrationCheck<F>`:
    - `DEFAULT_TOLERANCE = 5.0` (camera pixels), `DEFAULT_TIME_LIMIT = 3000` (ms)
    - `@FunctionalInterface interface Detector<F> { Optional<Rect> detect(F frame); }`
    - `sealed interface Outcome permits Kept, Moved, NotVerified`; `record Kept(Rect detected)`, `record Moved(Rect detected, double distance)`, `record NotVerified(Reason reason)`; `enum Reason { PATTERN_NOT_SEEN, NO_CAMERA, CANCELLED }`
    - `CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock)` and `(…, double tolerance, long timeLimit)`; the time limit starts at construction
    - `Optional<Outcome> offer(F frame)`, `Optional<Outcome> tick()`, `Outcome stop(Reason reason)`, `Optional<Outcome> outcome()`, all `synchronized`
    - `static double distance(Rect a, Rect b)`: the largest difference between matching edges
  - `com.shootoff.camera.autocalibration.PatternDetector(Camera camera) implements CalibrationCheck.Detector<BufferedImage>`

**How the check decides (ruling 4).**
- A frame whose pattern has every edge within the tolerance of the saved bounds: **Kept**.
- Otherwise the detection is remembered; the next detection that agrees with it within the tolerance (but not with the saved bounds) gives **Moved**, with how far the furthest edge moved. A frame without the pattern forgets the remembered one.
- Once the time limit has passed, `tick()` and `offer()` give **NotVerified(PATTERN_NOT_SEEN)** and the detector isn't called again. `stop(reason)` ends it with **NotVerified(reason)** unless it already has an outcome. The first outcome is final.
- Tests drive it with fake frames (strings the fake detector maps to bounds) and a fake clock.

`PatternDetector` does what auto-calibration's `StepFindBounds` does with one frame, on an `AutoCalibrationManager` of its own (its listener does nothing), so the camera's own calibration is never touched. `TestPatternDetector` draws `pattern.png` into a 640×480 frame and finds it within 2 px.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/calibration/TestCalibrationCheck.java`:

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
			"pattern moved", Optional.of(new Rect(114, 80, 400, 300)),
			"pattern moved again", Optional.of(new Rect(115, 81, 400, 300)),
			"pattern mid-change", Optional.of(new Rect(160, 120, 300, 200)));

	private long now = 5_000;
	private final List<String> looked = new ArrayList<>();

	private final CalibrationCheck<String> check = new CalibrationCheck<>(SAVED, frame -> {
		looked.add(frame);
		return FRAMES.get(frame);
	}, () -> now);

	@Test
	void theSavedBoundsFoundWithinFivePixelsKeepTheCalibration() {
		assertEquals(Optional.empty(), check.offer("no pattern"));

		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302))), check.offer("pattern in place"));
	}

	@Test
	void twoFramesAgreeingAwayFromTheSavedBoundsReportHowFarItMoved() {
		assertEquals(Optional.empty(), check.offer("pattern moved"));

		final Optional<Outcome> outcome = check.offer("pattern moved again");

		assertEquals(Optional.of(new Moved(new Rect(115, 81, 400, 300), 15.0)), outcome);
	}

	@Test
	void oneFrameCaughtMidChangeIsNotReportedAsAMove() {
		assertEquals(Optional.empty(), check.offer("pattern mid-change"));
		assertEquals(Optional.empty(), check.offer("pattern moved"));
		assertEquals(Optional.empty(), check.offer("no pattern"));

		assertEquals(Optional.of(new Kept(new Rect(104, 77, 398, 302))), check.offer("pattern in place"));
	}

	@Test
	void withNoPatternForThreeSecondsItIsNotVerifiedAndStopsLooking() {
		now += 2_999;
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
		kept.offer("pattern in place");
		assertTrue(kept.stop(Reason.CANCELLED) instanceof Kept);
	}

	@Test
	void theDistanceIsTheFurthestEdge() {
		assertEquals(0.0, CalibrationCheck.distance(SAVED, SAVED));
		// Wider by 14 on the right, 3 higher at the top
		assertEquals(14.0, CalibrationCheck.distance(SAVED, new Rect(100, 77, 414, 303)));
	}
}
```

`core/src/test/java/com/shootoff/camera/autocalibration/TestPatternDetector.java`:

```java
package com.shootoff.camera.autocalibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.shootoff.calibration.CalibrationCheck;
import com.shootoff.camera.MockCamera;
import com.shootoff.geom.Rect;

class TestPatternDetector {
	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	private static PatternDetector detector() {
		final MockCamera camera = new MockCamera();
		camera.setViewSize(new Dimension(640, 480));
		return new PatternDetector(camera);
	}

	// A 640x480 camera frame of a dim wall with the projected pattern at <tt>where</tt>
	private static BufferedImage frame(Rect where) throws IOException {
		final BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = frame.createGraphics();
		g.setColor(new Color(40, 40, 40));
		g.fillRect(0, 0, 640, 480);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(ImageIO.read(TestPatternDetector.class.getResource("/pattern.png")), (int) where.getMinX(),
				(int) where.getMinY(), (int) where.getWidth(), (int) where.getHeight(), null);
		g.dispose();
		return frame;
	}

	@Test
	void theProjectedPatternIsFoundWhereItIs() throws IOException {
		final Rect projected = new Rect(100, 80, 420, 296);

		final Optional<Rect> found = detector().detect(frame(projected));

		assertTrue(found.isPresent());
		assertTrue(CalibrationCheck.distance(projected, found.get()) <= CalibrationCheck.DEFAULT_TOLERANCE,
				() -> "found " + found.get());
	}

	@Test
	void aFrameWithoutThePatternFindsNothing() throws IOException {
		final BufferedImage wall = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);

		assertEquals(Optional.empty(), detector().detect(wall));
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationCheck' --tests 'com.shootoff.camera.autocalibration.TestPatternDetector' --console=plain`

Expected: `compileTestJava` FAILS with `cannot find symbol` for `class CalibrationCheck` and `class PatternDetector`.

- [ ] **Step 3: Write the check and the detector**

`core/src/main/java/com/shootoff/calibration/CalibrationCheck.java`:

```java
package com.shootoff.calibration;

import java.util.Optional;
import java.util.function.LongSupplier;

import com.shootoff.geom.Rect;

/**
 * Checks a remembered calibration against the projection the camera sees now. While the calibration
 * pattern shows on the arena, the caller offers camera frames; each is looked at with a
 * {@link Detector} and the pattern's bounds compared with the saved ones:
 * <ul>
 * <li>every edge within the tolerance of the saved one: the calibration is {@link Kept};</li>
 * <li>two frames in a row agree with each other but not with the saved bounds: the projection has
 * {@link Moved} (one frame alone could be caught mid-change);</li>
 * <li>no answer within the time limit, the camera gone, or the check cancelled: {@link NotVerified}.</li>
 * </ul>
 * The first outcome is final. The check keeps no threads or timers of its own: the caller offers frames
 * and calls {@link #tick()}, from any thread, and the clock decides the time limit.
 *
 * @param <F>
 *            a camera frame
 */
public final class CalibrationCheck<F> {
	/** How far, in camera pixels, an edge may be from the saved one */
	public static final double DEFAULT_TOLERANCE = 5.0;
	/** How long the check waits to see the pattern, in milliseconds */
	public static final long DEFAULT_TIME_LIMIT = 3000;

	/** Finds the calibration pattern in a camera frame */
	@FunctionalInterface
	public interface Detector<F> {
		Optional<Rect> detect(F frame);
	}

	public sealed interface Outcome permits Kept, Moved, NotVerified {}

	/** The saved calibration still fits */
	public record Kept(Rect detected) implements Outcome {}

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
	private final Detector<F> detector;
	private final LongSupplier clock;
	private final double tolerance;
	private final long timeLimit;
	private final long startedAt;

	private Rect lastDetected = null;
	private Outcome outcome = null;

	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock) {
		this(saved, detector, clock, DEFAULT_TOLERANCE, DEFAULT_TIME_LIMIT);
	}

	/**
	 * Starts the time limit now, by <tt>clock</tt>.
	 */
	public CalibrationCheck(Rect saved, Detector<F> detector, LongSupplier clock, double tolerance, long timeLimit) {
		this.saved = saved;
		this.detector = detector;
		this.clock = clock;
		this.tolerance = tolerance;
		this.timeLimit = timeLimit;
		startedAt = clock.getAsLong();
	}

	/**
	 * Looks for the pattern in <tt>frame</tt>, unless the check is over.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> offer(F frame) {
		if (outcome != null || timedOut()) return tick();

		final Optional<Rect> detected = detector.detect(frame);
		if (detected.isEmpty()) {
			lastDetected = null;
			return Optional.empty();
		}

		final Rect found = detected.get();
		if (distance(found, saved) <= tolerance) {
			outcome = new Kept(found);
		} else if (lastDetected != null && distance(found, lastDetected) <= tolerance) {
			outcome = new Moved(found, distance(found, saved));
		} else {
			lastDetected = found;
		}

		return Optional.ofNullable(outcome);
	}

	/**
	 * Ends the check if its time is up.
	 *
	 * @return the outcome, once there is one
	 */
	public synchronized Optional<Outcome> tick() {
		if (outcome == null && timedOut()) outcome = new NotVerified(Reason.PATTERN_NOT_SEEN);
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

	private boolean timedOut() {
		return clock.getAsLong() - startedAt >= timeLimit;
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

`core/src/main/java/com/shootoff/camera/autocalibration/PatternDetector.java`:

```java
package com.shootoff.camera.autocalibration;

import java.awt.image.BufferedImage;
import java.util.Optional;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.imgproc.Imgproc;

import com.shootoff.calibration.CalibrationCheck;
import com.shootoff.camera.CameraCalibrationListener;
import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * Finds the calibration pattern's bounds in one camera frame, the way auto-calibration's first step
 * does (grayscale, equalized, the chessboard, then the pattern's corners). It has its own
 * {@link AutoCalibrationManager}, so it never touches the camera's own calibration.
 */
public final class PatternDetector implements CalibrationCheck.Detector<BufferedImage> {
	// Detection only: this manager never finishes a calibration, so nothing is ever reported
	private static final CameraCalibrationListener NO_LISTENER = new CameraCalibrationListener() {
		@Override
		public void calibrate(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas,
				long frameDelay) {}

		@Override
		public void setArenaBackground(String resourceFilename) {}
	};

	private final AutoCalibrationManager acm;

	/**
	 * @param camera
	 *            the camera the frames come from (for its feed size)
	 */
	public PatternDetector(Camera camera) {
		acm = new AutoCalibrationManager(NO_LISTENER, camera, false);
	}

	@Override
	public synchronized Optional<Rect> detect(BufferedImage frame) {
		final Mat gray = acm.preProcessFrame(Camera.bufferedImageToMat(frame));
		Imgproc.equalizeHist(gray, gray);

		final Optional<MatOfPoint2f> board = acm.findChessboard(gray);
		if (board.isEmpty()) return Optional.empty();

		return acm.calibrateFrame(board.get(), gray);
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationCheck' --tests 'com.shootoff.camera.autocalibration.TestPatternDetector' --console=plain`

Expected: `BUILD SUCCESSFUL`, 8 tests passing.

- [ ] **Step 5: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
```

Expected: `638/638 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add core/src/main/java/com/shootoff/calibration/CalibrationCheck.java core/src/main/java/com/shootoff/camera/autocalibration/PatternDetector.java
git add core/src/test/java/com/shootoff/calibration/TestCalibrationCheck.java core/src/test/java/com/shootoff/camera/autocalibration/TestPatternDetector.java
git commit -m "Add the calibration check: compare the pattern the camera sees with a remembered calibration"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 3: Calibration only on request, with Cancel and a way to apply a saved calibration

**Files:**
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationFlow.java` (`applySaved`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`
- Test: `core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java` (two methods added)
- Test (new): `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`
- Test (changed, ruling 15): `compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`, `TestCalibrationController.kt`, `TestCalibrationOverlay.kt`; `compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`, `TestAppState.kt`, `TestScreens.kt`, `TestProblemViews.kt`, `TestProblems.kt`

**Interfaces:**
- Consumes: Task 1's `SavedCalibration`; Plan 5's `CalibrationFlow` (`start`, `stop`, `cancel`, `calibrated`, `setFullScreen`, `arenaClosing`, `isCalibrating`) and `CalibrationController`.
- Produces:
  - `core`: `CalibrationFlow.applySaved(Rect cameraBounds, Optional<Size> perspectivePaperDims)`: throws `IllegalStateException` while calibrating
  - `com.shootoff.compose.calibration`:
    - `CalibrationViews.calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)` (new method), beside `showCalibratingFeed()` and `restoreSelectedView()`
    - on `CalibrationController`: `start()`, `cancel()`, `applySaved(saved: SavedCalibration)`; `toggle()` now stops (with the box) or calls `start()`; `fullScreenChanged(Boolean)` reaches the flow only while calibrating
    - the overlay's buttons are tagged `calibration-cancel` (always) and `calibration-done` (the box); `calibration-stop` is gone
  - `com.shootoff.compose.app`, on `AppState`:
    - `startCalibration(): Boolean` (false without an arena and a camera), `cancelCalibration()`; `toggleCalibration()` is gone
    - `calibratedAt: StateFlow<LocalTime?>` (set by a success, cleared when the arena closes)
    - constructor parameter `wallClock: () -> LocalTime = LocalTime::now`, after `prefs`
    - `openArena()` and a camera opening after the arena make the arena calibratable without calibrating
  - test fixtures: `AppFixture.oneCamera(camera: TestCamera = TestCamera()): CameraSource` and `AppFixture.appWithCamera(settings, background, screens): AppState` (the fixture's two drills, one test camera, the owner's screens)
  - F6 (`Shortcut.CALIBRATE`) calls `startCalibration()` (Task 5 adds opening Setup)

**What changes (rulings 7 and 8).**
- Opening the arena creates the controller but doesn't start it; so does a camera opening after the arena. The full-screen watch still runs, but the controller passes a change to the flow only while calibrating.
- `start()` records the camera's projection bounds, then starts the flow with `flow.setFullScreen(arena.fullScreen.value)`, so the flow knows the arena's placement as it starts.
- `cancel()` ends through `flow.cancel()` and then puts back the arena's background, targets, shots and label, and the camera's projection bounds. No drill restarts.
- A calibration the user started reports its success (the camera's bounds, and the paper size auto-calibration found) through `CalibrationViews.calibrationSucceeded`. `applySaved` goes through the flow's new `applySaved` and reports nothing.
- `AppState.calibrationSucceeded` records the time; Task 4 adds saving and Task 5 going back to Range.

- [ ] **Step 1: Write the failing tests, and change the ones that expected calibration on open**

Two tests for `applySaved`, and their import:

`core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`:

Replace:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

with:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
```

Replace:

```java
		assertEquals(List.of("camera bounds null"), events);
	}
}
```

with:

```java
		assertEquals(List.of("camera bounds null"), events);
	}

	@Test
	void aSavedCalibrationIsAppliedWithoutCalibrating() {
		final CalibrationFlow flow = flow();
		projectorExerciseRunning = true;

		// The feed's 640 x 480 is shown at 1280 x 960
		flow.applySaved(new Rect(100, 80, 400, 300), Optional.empty());

		assertEquals(List.of("projection on the canvas Rect[minX=200.0, minY=160.0, width=800.0, height=600.0]",
				"camera crops false", "camera limits detection true",
				"camera bounds Rect[minX=100.0, minY=80.0, width=400.0, height=300.0]", "calibrated"), events);
		assertFalse(flow.isCalibrating());
		assertTrue(projectorExerciseRunning);
	}

	@Test
	void aSavedCalibrationCantBeAppliedWhileCalibrating() {
		final CalibrationFlow flow = flow();
		flow.start();

		assertThrows(IllegalStateException.class, () -> flow.applySaved(new Rect(100, 80, 400, 300), Optional.empty()));
		assertTrue(flow.isCalibrating());
	}
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt`:

```kotlin
package com.shootoff.compose.calibration

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.config.SavedCalibration
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestCalibrationOnRequest {
    private val fixture = CalibrationFixture()
    private val controller = fixture.controller
    private val arena = fixture.arena

    @Test
    fun theArenaGoingFullScreenNeverStartsCalibration() {
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)

        assertFalse(controller.state.value.calibrating)
        assertFalse(fixture.events.contains("stop exercise"))
        assertFalse(fixture.events.contains("auto on"))
    }

    @Test
    fun startedOnAnArenaAlreadyFullScreenItLooksAtOnceAndTimesOutToTheBox() {
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)

        controller.start()
        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)

        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)

        assertEquals(Message.MANUAL_REQUEST, controller.state.value.message)
        assertNotNull(controller.state.value.box)
    }

    @Test
    fun cancelLeavesTheArenaAndTheCameraAsTheyWereAndRestartsNothing() {
        val restarts = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { restarts += "restart" })
        val background = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(background)
        arena.setProjection(Rect(100.0, 80.0, 400.0, 300.0))
        arena.setCalibrationLabelVisible(false)
        fixture.camera.bounds = Rect(100.0, 80.0, 400.0, 300.0)
        arena.setFullScreen(true)

        controller.start()
        assertEquals("pattern.png", arena.background.value!!.name)
        assertEquals(null, fixture.camera.bounds)

        controller.cancel()

        assertFalse(controller.state.value.calibrating)
        assertSame(background, arena.background.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertFalse(arena.needsCalibrationLabel.value)
        assertEquals(emptyList<String>(), restarts)
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }

    @Test
    fun cancellingOnAnUncalibratedArenaBringsBackItsLabel() {
        arena.setFullScreen(true)
        controller.start()
        assertFalse(arena.needsCalibrationLabel.value)

        controller.cancel()

        assertTrue(arena.needsCalibrationLabel.value)
    }

    @Test
    fun aCalibrationTheUserStartedReportsItsBoundsAndPaper() {
        arena.setFullScreen(true)
        controller.start()

        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.of(Size(11.0, 8.5)), false, 0)

        assertTrue(fixture.events.contains("succeeded ${Rect(100.0, 80.0, 400.0, 300.0)}"))
    }

    @Test
    fun aRememberedCalibrationIsAppliedWithoutCalibratingOrReportingASuccess() {
        val saved = SavedCalibration("C270", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty())

        controller.applySaved(saved)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertFalse(arena.needsCalibrationLabel.value)
        assertFalse(controller.state.value.calibrating)
        assertFalse(fixture.events.contains("stop exercise"))
        assertFalse(fixture.events.any { it.startsWith("succeeded") })
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`:

```kotlin
package com.shootoff.compose.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec §8 "Nothing blocks": one test per rule. */
class TestNothingBlocks {
    @Test
    fun rule2CalibrationRunsOnlyWhenAskedNeverBecauseTheArenaOpened() {
        val background = TestProblems.QueueDispatcher()
        val app = AppFixture.appWithCamera(background = background)
        try {
            app.openStartCamera()
            app.openArena()
            // The arena window reaching the projector and going full screen
            app.arena.value!!.setFullScreen(true)
            background.drain()

            assertFalse(app.calibration.value!!.state.value.calibrating)
            assertNull(app.arena.value!!.background.value)

            // F6, or Calibrate on Setup
            assertTrue(app.startCalibration())
            assertTrue(app.calibration.value!!.state.value.calibrating)
        } finally {
            app.close()
        }
    }
}
```

The fixture's views hear the new success report:

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`:

Replace:

```kotlin
            events += "restore view"
        }
    }
```

with:

```kotlin
            events += "restore view"
        }

        override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
            events += "succeeded $cameraBounds"
        }
    }
```

Reopening onto a full-screen arena no longer calibrates by itself:

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt`:

Replace:

```kotlin
    @Test
    fun closingThenReopeningStartsFreshWithNoStaleState() {
        val restarts = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { restarts += "restart" })
```

with:

```kotlin
    @Test
    fun closingThenReopeningStartsFreshOnlyWhenAskedWithNoStaleState() {
        val restarts = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { restarts += "restart" })
```

Replace:

```kotlin
        controller.arenaClosing()

        // Reopening straight onto the (still full screen) projector must go through a fresh start(), not
        // a stale "already calibrating" branch left over from the cancelled session
        controller.fullScreenChanged(true)

        assertEquals(2, fixture.events.count { it == "stop exercise" })
```

with:

```kotlin
        controller.arenaClosing()

        // Reopening straight onto the (still full screen) projector doesn't calibrate by itself
        controller.fullScreenChanged(true)
        assertEquals(1, fixture.events.count { it == "stop exercise" })
        assertFalse(controller.state.value.calibrating)

        // Asked to, it goes through a fresh start(), not a stale "already calibrating" branch left over
        // from the cancelled session
        controller.start()

        assertEquals(2, fixture.events.count { it == "stop exercise" })
```

The overlay's Stop is Cancel, and the box has Cancel too:

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt`:

Replace:

```kotlin
    @Test
    fun whileLookingForThePatternTheUserCanStop() {
        startOnTheProjector()
        show()

        compose.onNodeWithText("Looking for the calibration pattern…").assertExists()
        compose.onNodeWithTag("calibration-stop").performClick()

        assertFalse(controller.state.value.calibrating)
    }
```

with:

```kotlin
    @Test
    fun whileLookingForThePatternTheUserCanCancel() {
        startOnTheProjector()
        show()

        compose.onNodeWithText("Looking for the calibration pattern…").assertExists()
        compose.onNodeWithTag("calibration-cancel").performClick()

        assertFalse(controller.state.value.calibrating)
    }

    @Test
    fun theManualBoxCanBeCancelledTooLeavingNoProjection() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        compose.onNodeWithTag("calibration-done").assertExists()
        compose.onNodeWithTag("calibration-cancel").performClick()

        assertFalse(controller.state.value.calibrating)
        assertEquals(null, fixture.arena.projection.value)
        compose.onNodeWithTag("calibration-box").assertDoesNotExist()
    }
```

A camera app for the app-level tests:

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

Replace:

```kotlin
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import java.util.Optional
```

with:

```kotlin
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Optional
```

Replace:

```kotlin
    val feedDrill = V2ExerciseEntry(FeedDrill::class.java, FeedDrill().metadata())

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
```

with:

```kotlin
    val feedDrill = V2ExerciseEntry(FeedDrill::class.java, FeedDrill().metadata())

    /** A camera source with one [TestCamera], which the app opens at start */
    fun oneCamera(camera: TestCamera = TestCamera()) = object : CameraSource {
        override fun cameras() = listOf(camera)

        override fun startCamera(settings: Settings) = camera
    }

    /** The same app with [oneCamera], its settings on [settings] and its watchers on [background] */
    fun appWithCamera(
        settings: Settings = Settings(ScratchConfig.emptyFile().path, arrayOf()),
        background: CoroutineDispatcher = Dispatchers.Default,
        screens: List<Rect> = ownerScreens,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
        return AppState(settings, catalog, oneCamera(), { screens }, ManualClock(), { it.run() }, background)
    }

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
```

Tests that relied on the arena calibrating as it opened now ask for it:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

Replace:

```kotlin
            calibratingApp.openStartCamera()
            calibratingApp.openArena()
            assertTrue(calibratingApp.calibration.value!!.state.value.calibrating)
```

with:

```kotlin
            calibratingApp.openStartCamera()
            calibratingApp.openArena()
            assertTrue(calibratingApp.startCalibration())
            assertTrue(calibratingApp.calibration.value!!.state.value.calibrating)
```

Replace:

```kotlin
            assertNull(calibratingApp.runner.running.value)

            calibratingApp.toggleCalibration()
            assertFalse(calibratingApp.calibration.value!!.state.value.calibrating)
```

with:

```kotlin
            assertNull(calibratingApp.runner.running.value)

            calibratingApp.cancelCalibration()
            assertFalse(calibratingApp.calibration.value!!.state.value.calibrating)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt`:

Replace:

```kotlin
        app = projectorAppWithCamera()
        show { DrillsScreen(app) }

        app.openStartCamera()
        app.openArena()
        compose.waitForIdle()

        compose.onNodeWithTag("start-drill").assertIsNotEnabled()
        compose.onNodeWithText("Calibrating… finish calibration first").assertExists()

        app.toggleCalibration()
        compose.waitForIdle()

        compose.onNodeWithTag("start-drill").assertIsEnabled()
        compose.onNodeWithText("Calibrating… finish calibration first").assertDoesNotExist()
    }
```

with:

```kotlin
        app = projectorAppWithCamera()
        show { DrillsScreen(app) }

        app.openStartCamera()
        app.openArena()
        app.startCalibration()
        compose.waitForIdle()

        compose.onNodeWithTag("start-drill").assertIsNotEnabled()
        compose.onNodeWithText("Calibrating… finish calibration first").assertExists()

        app.cancelCalibration()
        compose.waitForIdle()

        compose.onNodeWithTag("start-drill").assertIsEnabled()
        compose.onNodeWithText("Calibrating… finish calibration first").assertDoesNotExist()
    }
```

Replace:

```kotlin
        app = projectorAppWithCamera()
        show { DrillsScreen(app) }

        app.openStartCamera()
        app.openArena()
        compose.waitForIdle()

        compose.onNodeWithTag("start-drill").assertIsNotEnabled()

        app.calibration.value!!.calibrate(Rect(0.0, 0.0, 100.0, 100.0), Optional.empty(), true, 0L)
```

with:

```kotlin
        app = projectorAppWithCamera()
        show { DrillsScreen(app) }

        app.openStartCamera()
        app.openArena()
        app.startCalibration()
        compose.waitForIdle()

        compose.onNodeWithTag("start-drill").assertIsNotEnabled()

        app.calibration.value!!.calibrate(Rect(0.0, 0.0, 100.0, 100.0), Optional.empty(), true, 0L)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt`:

Replace:

```kotlin
        app.openCamera(AppFixture.TestCamera())
        app.openArena()
        compose.onNodeWithTag("status-strip").assert(hasText("· calibrating", substring = true))

        // Only the calibration controller's own state changes
        app.toggleCalibration()

        compose.waitUntil(5000) { !statusStrip().contains("· calibrating") }
```

with:

```kotlin
        app.openCamera(AppFixture.TestCamera())
        app.openArena()
        app.startCalibration()
        compose.onNodeWithTag("status-strip").assert(hasText("· calibrating", substring = true))

        // Only the calibration controller's own state changes
        app.cancelCalibration()

        compose.waitUntil(5000) { !statusStrip().contains("· calibrating") }
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`:

Replace:

```kotlin
    @Test
    fun openingACameraAfterTheArenaGetsCalibrationToo() {
        app.openArena()
        assertNull(app.calibration.value)
```

with:

```kotlin
    @Test
    fun openingACameraAfterTheArenaMakesItCalibratableWithoutCalibrating() {
        app.openArena()
        assertNull(app.calibration.value)
```

Replace:

```kotlin
        assertNotNull(app.calibration.value)
        assertEquals(Message.FULL_SCREEN_REQUEST, app.calibration.value!!.state.value.message)
    }
```

with:

```kotlin
        assertNotNull(app.calibration.value)
        assertFalse(app.calibration.value!!.state.value.calibrating)
        assertTrue(app.startCalibration())
        assertEquals(Message.FULL_SCREEN_REQUEST, app.calibration.value!!.state.value.message)
    }
```

Replace:

```kotlin
            app.openStartCamera()
            app.openArena()
            val controller = app.calibration.value!!
            assertEquals(Message.FULL_SCREEN_REQUEST, controller.state.value.message)
```

with:

```kotlin
            app.openStartCamera()
            app.openArena()
            app.startCalibration()
            val controller = app.calibration.value!!
            assertEquals(Message.FULL_SCREEN_REQUEST, controller.state.value.message)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationFlow' --console=plain`

Expected: `compileTestJava` FAILS with `cannot find symbol` for `method applySaved`.

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.calibration.*' --console=plain`

Expected: `compileTestKotlin` FAILS, with errors such as `'calibrationSucceeded' overrides nothing` and `Unresolved reference 'startCalibration'`.

- [ ] **Step 3: Add `CalibrationFlow.applySaved`**

`core/src/main/java/com/shootoff/calibration/CalibrationFlow.java`:

Replace:

```java
	/**
	 * Applies how the calibrating feed treats the projection.
	 */
```

with:

```java
	/**
	 * Applies a calibration found in an earlier session (the Compose app's remembered one) without
	 * calibrating: the projection, the calibrated feed behavior and the perspective, as the end of a
	 * calibration would. Nothing is stopped or restarted and the arena's background is left alone.
	 *
	 * @param cameraBounds
	 *            the projection on the camera's feed
	 * @throws IllegalStateException
	 *             while calibrating
	 */
	public void applySaved(Rect cameraBounds, Optional<Size> perspectivePaperDims) {
		if (isCalibrating()) throw new IllegalStateException("A saved calibration can't be applied while calibrating");

		calibrated(cameraBounds, perspectivePaperDims, false);
		view.calibrated(Optional.ofNullable(perspectiveManager()));
	}

	/**
	 * Applies how the calibrating feed treats the projection.
	 */
```

Run: `./gradlew :core:test --tests 'com.shootoff.calibration.TestCalibrationFlow' --console=plain`

Expected: `BUILD SUCCESSFUL`, 11 tests passing (the 9 already there, and the 2 new).

- [ ] **Step 4: Start, cancel and apply in the controller; Cancel on the overlay**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.CalibrationOption
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
```

with:

```kotlin
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.CalibrationOption
import com.shootoff.config.SavedCalibration
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
```

Replace:

```kotlin
}

/** The Range screen's big view, as calibration switches it. */
interface CalibrationViews {
    /** Remembers the view the user is on and shows the calibrating camera's feed. */
    fun showCalibratingFeed()

    fun restoreSelectedView()
}
```

with:

```kotlin
}

/** What calibration asks of the rest of the app. */
interface CalibrationViews {
    /** The manual box is showing: the user must see the calibrating camera's feed (Setup). */
    fun showCalibratingFeed()

    fun restoreSelectedView()

    /**
     * A calibration the user asked for ended with a projection (found by the camera, or the box's).
     *
     * @param cameraBounds the projection on the camera's feed
     * @param paper the perspective paper's size, if auto-calibration found one
     */
    fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>)
}
```

Replace:

```kotlin
    private var generation = 0

    // ---- The user

    /** The Calibrate button: starts calibrating, or ends it (with the box, if it is showing). */
    fun toggle() = if (flow.isCalibrating) flow.stop() else flow.start()

    /** Moves or resizes the box, keeping it inside the canvas (the settings' display size). */
```

with:

```kotlin
    private var generation = 0

    // The camera's projection when calibration started, which Cancel puts back
    @Volatile
    private var boundsBefore: Rect? = null

    // Whether a calibration the user started is under way, so only its end is reported as a success
    @Volatile
    private var session = false

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

    /** Starts calibrating, or ends it (with the box, if it is showing). */
    fun toggle() = if (flow.isCalibrating) flow.stop() else start()

    /** Applies a calibration remembered from an earlier session, without calibrating. */
    fun applySaved(saved: SavedCalibration) = flow.applySaved(saved.bounds, saved.paper)

    /** Moves or resizes the box, keeping it inside the canvas (the settings' display size). */
```

Replace:

```kotlin
    }

    /** The arena window went full screen or left it. */
    fun fullScreenChanged(fullScreen: Boolean) = flow.setFullScreen(fullScreen)

    /**
```

with:

```kotlin
    }

    /**
     * The arena window went full screen or left it. It matters only while calibrating: unlike the JavaFX
     * app, the arena going full screen never starts calibration.
     */
    fun fullScreenChanged(fullScreen: Boolean) {
        if (flow.isCalibrating) flow.setFullScreen(fullScreen)
    }

    /**
```

Replace:

```kotlin
    fun arenaClosing() {
        generation++
        if (flow.isCalibrating) {
            flow.cancel()
            restoreArenaBackground()
            arena.setTargetsVisible(true)
            arena.showShots(settings.showArenaShotMarkers())
        }
        uiState.update { CalibrationUi() }
        flow.arenaClosing()
        arena.setProjection(null)
    }
```

with:

```kotlin
    fun arenaClosing() {
        generation++
        session = false
        if (flow.isCalibrating) {
            flow.cancel()
            putArenaBack()
        }
        uiState.update { CalibrationUi() }
        flow.arenaClosing()
        arena.setProjection(null)
    }

    // The arena's look before calibration: its background, targets, shots, and the label if uncalibrated
    private fun putArenaBack() {
        restoreArenaBackground()
        arena.setTargetsVisible(true)
        arena.showShots(settings.showArenaShotMarkers())
        arena.setCalibrationLabelVisible(arena.projection.value == null)
    }
```

Replace:

```kotlin
    override fun calibrate(arenaBounds: Rect, perspectivePaperDims: Optional<Size>, calibratedFromCanvas: Boolean, frameDelay: Long) {
        val expectedGeneration = generation
        uiThread(
            Runnable {
```

with:

```kotlin
    override fun calibrate(arenaBounds: Rect, perspectivePaperDims: Optional<Size>, calibratedFromCanvas: Boolean, frameDelay: Long) {
        val expectedGeneration = generation
        foundPaper = perspectivePaperDims
        uiThread(
            Runnable {
```

Replace:

```kotlin
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
        }
    }
```

with:

```kotlin
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
        }
        // A calibration the user started, not a remembered one being applied, and one that found the projection
        if (session) {
            session = false
            camera.projectionBounds.ifPresent { views.calibrationSucceeded(it, foundPaper) }
        }
    }
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt`:

Replace:

```kotlin
/**
 * Calibration over the calibrating camera's feed: what the flow asks of the user, with Stop (or Done
 * for the manual box), and the box itself, which the user drags and resizes by its corners.
 */
@Composable
```

with:

```kotlin
/**
 * Calibration over the calibrating camera's feed: what the flow asks of the user, always with Cancel
 * (and Done for the manual box), and the box itself, which the user drags and resizes by its corners.
 */
@Composable
```

Replace:

```kotlin
                    if (message == Message.MANUAL_REQUEST) {
                        Button(onClick = { controller.flow.stop() }, modifier = Modifier.testTag("calibration-done")) { Text("Done") }
                    } else {
                        FilledTonalButton(onClick = { controller.flow.stop() }, modifier = Modifier.testTag("calibration-stop")) { Text("Stop") }
                    }
                }
            }
```

with:

```kotlin
                    if (message == Message.MANUAL_REQUEST) {
                        Button(onClick = { controller.flow.stop() }, modifier = Modifier.testTag("calibration-done")) { Text("Done") }
                    }
                    FilledTonalButton(onClick = controller::cancel, modifier = Modifier.testTag("calibration-cancel")) { Text("Cancel") }
                }
            }
```

- [ ] **Step 5: The app opens the arena without calibrating; F6 and Range's button ask for it**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.util.Optional
import java.util.concurrent.CompletableFuture
```

with:

```kotlin
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.util.Optional
import java.util.concurrent.CompletableFuture
```

Replace:

```kotlin
 * @param background runs the app's watchers
 * @param io opens and lists cameras, which can take seconds, off the UI thread
 */
class AppState(
```

with:

```kotlin
 * @param background runs the app's watchers
 * @param io opens and lists cameras, which can take seconds, off the UI thread
 * @param wallClock the time of day, which the calibration status shows
 */
class AppState(
```

Replace:

```kotlin
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val prefs: UiPrefs = UiPrefs(),
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

with:

```kotlin
    private val io: CoroutineDispatcher = Dispatchers.IO,
    val prefs: UiPrefs = UiPrefs(),
    private val wallClock: () -> LocalTime = LocalTime::now,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

Replace:

```kotlin
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null
```

with:

```kotlin
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null
```

Replace:

```kotlin
    val calibration: StateFlow<CalibrationController?> = calibrationState.asStateFlow()

    /** The open camera, or null */
```

with:

```kotlin
    val calibration: StateFlow<CalibrationController?> = calibrationState.asStateFlow()

    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /** The open camera, or null */
```

Replace:

```kotlin
        problemState.value = null
        cameraState.value = manager
        // The arena was already open with no camera to calibrate with (openArena found none); now one
        // is here, so calibration starts the way it would have if the camera had come first
        arenaState.value?.let { arena -> if (calibrationState.value == null) startCalibrating(arena, manager) }
        return true
    }
```

with:

```kotlin
        problemState.value = null
        cameraState.value = manager
        // The arena was already open with no camera to calibrate with (openArena found none); now one is
        // here, so the arena can be calibrated, as it could have been if the camera had come first
        arenaState.value?.let { arena -> if (calibrationState.value == null) makeCalibratable(arena, manager) }
        return true
    }
```

Replace:

```kotlin
    /**
     * Opens the arena window, on the projector if one is found, and starts calibrating it with the open
     * camera, as the JavaFX app does. Without an open camera there is nothing to calibrate with yet;
     * [startCalibrating] runs later instead, once a camera opens (see [publish]).
     */
    fun openArena() {
```

with:

```kotlin
    /**
     * Opens the arena window, on the projector if one is found, ready to be calibrated with the open camera.
     * Unlike the JavaFX app, it never starts calibrating: only [startCalibration] does. Without an open
     * camera there is nothing to calibrate with yet; [makeCalibratable] runs later instead, once a camera
     * opens (see [publish]).
     */
    fun openArena() {
```

Replace:

```kotlin
        if (prefs.view == BigView.ARENA) viewState.value = BigView.ARENA

        cameraState.value?.let { startCalibrating(arena, it) }
    }

    // Creates the calibration controller for [camera] on [arena] and starts calibrating, the way
    // openArena does when a camera is already open. Also reached when a camera opens (or becomes
    // available) after the arena, which otherwise would leave Calibrate and F6 disabled until the
    // arena is closed and reopened.
    private fun startCalibrating(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(camera, arena, settings, runner, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
```

with:

```kotlin
        if (prefs.view == BigView.ARENA) viewState.value = BigView.ARENA

        cameraState.value?.let { makeCalibratable(arena, it) }
    }

    // Creates the calibration controller for [camera] on [arena], without calibrating. Also reached when a
    // camera opens (or becomes available) after the arena, which otherwise would leave Calibrate and F6
    // disabled until the arena is closed and reopened.
    private fun makeCalibratable(arena: ArenaModel, camera: CameraManager) {
        val controller = CalibrationController(camera, arena, settings, runner, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
```

Replace:

```kotlin
        calibrationState.value = controller

        // Calibration hears the arena going full screen, as the JavaFX arena tells it
        // on the UI thread, as calibration's other inputs are, and only while this arena is still the open one:
        // the flow starts calibrating on a full-screen change, which must never happen after the arena closed
        // Started undispatched, so it is watching before this returns: a flip right after the arena opens is
        // not taken for the value drop(1) skips
```

with:

```kotlin
        calibrationState.value = controller

        // Calibration hears the arena going full screen or leaving it, as the JavaFX arena tells it: on the
        // UI thread, as calibration's other inputs are, and only while this arena is still the open one.
        // Started undispatched, so it is watching before this returns: a flip right after the arena opens is
        // not taken for the value drop(1) skips
```

Replace:

```kotlin
            }
        }

        controller.flow.start()
    }
```

with:

```kotlin
            }
        }
    }
```

Replace:

```kotlin
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
        arenaState.value = null
        runner.stopProjectorExercise()
        placementState.value = null
```

with:

```kotlin
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
        arenaState.value = null
        calibratedAtState.value = null
        runner.stopProjectorExercise()
        placementState.value = null
```

Replace:

```kotlin
    }

    fun toggleCalibration() {
        calibrationState.value?.toggle()
    }
```

with:

```kotlin
    }

    /**
     * Starts calibrating the open arena with the open camera: the only way calibration ever starts
     * (Calibrate on Setup, or F6).
     *
     * @return false if there is no arena, or no camera, to calibrate
     */
    fun startCalibration(): Boolean {
        val controller = calibrationState.value ?: return false
        controller.start()
        return true
    }

    /** Cancel: calibration ends, leaving the arena and the camera as they were before it started. */
    fun cancelCalibration() {
        calibrationState.value?.cancel()
    }

    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        calibratedAtState.value = wallClock()
    }
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`:

Replace:

```kotlin
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
    CALIBRATE(Key.F6, "Start or stop calibrating"),
    ;
```

with:

```kotlin
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
    CALIBRATE(Key.F6, "Start calibrating"),
    ;
```

Replace:

```kotlin
        }
        Shortcut.CLEAR_SHOTS -> clearShots()
        Shortcut.CALIBRATE -> {
            if (calibration.value == null) return false
            toggleCalibration()
        }
    }
    return true
```

with:

```kotlin
        }
        Shortcut.CLEAR_SHOTS -> clearShots()
        Shortcut.CALIBRATE -> return startCalibration()
    }
    return true
```

Range's Calibrate button calls the new pair until Task 6 replaces the button:

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`:

Replace:

```kotlin
        } else {
            FilledTonalButton(onClick = { app.closeArena() }, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
            FilledTonalButton(onClick = app::toggleCalibration, enabled = calibration != null, modifier = Modifier.testTag("calibrate")) {
                Text(if (calibrating) "Stop calibrating" else "Calibrate")
            }
        }
```

with:

```kotlin
        } else {
            FilledTonalButton(onClick = { app.closeArena() }, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
            FilledTonalButton(
                onClick = { if (calibrating) app.cancelCalibration() else app.startCalibration() },
                enabled = calibration != null,
                modifier = Modifier.testTag("calibrate"),
            ) {
                Text(if (calibrating) "Cancel calibrating" else "Calibrate")
            }
        }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --console=plain`

Expected: `BUILD SUCCESSFUL`. New: `TestCalibrationOnRequest` (6), `TestCalibrationOverlay.theManualBoxCanBeCancelledTooLeavingNoProjection`, `TestNothingBlocks.rule2CalibrationRunsOnlyWhenAskedNeverBecauseTheArenaOpened`; every other `compose-app` test passes.

- [ ] **Step 7: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
```

Expected: `648/648 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 8: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add core/src/main/java/com/shootoff/calibration/CalibrationFlow.java core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java
git add compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOnRequest.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt
git commit -m "Calibrate the Compose arena only when asked, with Cancel, and apply a saved calibration"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 4: Remember calibration and the automatic check, in the app

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt` (the frame tap), `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt` (new); `compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`, `TestNothingBlocks.kt` (changed)

**Interfaces:**
- Consumes: Task 1's `SavedCalibration` and `Settings` accessors; Task 2's `CalibrationCheck` and `PatternDetector`; Task 3's `CalibrationController.applySaved`, `CalibrationViews.calibrationSucceeded`, `AppState.startCalibration`, `calibratedAt` and `AppFixture.appWithCamera`; `CalibrationFlow.Scheduler` and `DETECTION_RESTART_DELAY`.
- Produces:
  - `com.shootoff.compose.calibration` (`RememberedCalibration.kt`):
    - `sealed interface CheckState { Idle; Checking; data class Moved(pixels: Int); data class NotVerified(reason: CalibrationCheck.Reason); data class DoesntFit(why: String) }`
    - `fun CheckState.text(): String?`: what Setup and Range say (null for `Idle`)
    - `fun savedCalibrationMismatch(saved: SavedCalibration, camera: String, feed: Size, screen: Rect?): String?`: null when the saved calibration may be checked
    - `class LatestFrame { fun offer(image: BufferedImage); fun take(): BufferedImage? }`
    - `class CalibrationCheckRun(saved, arena, camera: CalibrationCamera, frames: LatestFrame, detector, clock: () -> Long, scope: CoroutineScope, scheduler: CalibrationFlow.Scheduler, uiThread, onDone: (CalibrationCheckRun, CalibrationCheck.Outcome) -> Unit)` with `start()`, `stop(reason)` (both on the UI thread) and `POLL_MILLIS = 50`
  - `ComposeCameraView.frameTap: ((BufferedImage) -> Unit)?`, called with every frame on the camera's thread
  - on `AppState`:
    - constructor parameters `detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) }` and `checkClock: () -> Long = System::currentTimeMillis`, after `wallClock`
    - `rememberCalibration: StateFlow<Boolean>`, `setRememberCalibration(remember: Boolean)`
    - `check: StateFlow<CheckState>`, `cancelCheck()`
  - test fixtures: `AppFixture.appWithCamera(settings, background, screens, detector, checkClock)`

**The flow in the app (rulings 3 and 4).**
- `openArena()`: with Remember on and a saved calibration, `checkRemembered`:
  - no camera → `NotVerified(NO_CAMERA)`
  - a different camera, feed size or projector screen size, or no projector screen → `DoesntFit(why)`; no pattern is shown
  - otherwise `Checking`, and the check starts once the arena reports full screen: the frame tap is set, the grid would be turned off (Task 5), and `CalibrationCheckRun.start()` shows the pattern, turns detection off and looks at the newest frame every 50 ms in the app's scope
- The outcome, on the UI thread, after the background is back: `Kept` applies the saved calibration (`calibratedAt` set, `Idle`); `Moved` and `NotVerified` leave the arena uncalibrated and say so.
- Stopped quietly (`Idle`, the background back, no more frames looked at) by `startCalibration()`, closing the arena (and so a lost camera), and turning Remember off. `cancelCheck()` stops it with `NotVerified(CANCELLED)`.
- `calibrationSucceeded` saves the calibration when Remember is on (camera name, feed size, the placement's screen size, the camera bounds, the paper), and clears any check message.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.Optional
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TestRememberedCalibration {
    private val file: File = ScratchConfig.emptyFile()

    // The owner's projector is the 1280x720 screen; the test camera's feed is 640x480
    private val saved = SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty())

    // What the fake detector finds in any frame, and the check's clock
    private val seen = AtomicReference<Optional<Rect>>(Optional.empty())
    private val looks = AtomicLong()
    private val now = AtomicLong(1_000)
    private val frame = BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR)

    private var app = appOn(Settings(file.path, arrayOf()))

    // What the app saved. Read as plain properties: reading them back into Settings would look for the
    // machine's webcams, since the app's save writes an (empty) webcam list
    private fun savedKeys(): Map<String, String> {
        val properties = Properties()
        file.inputStream().use(properties::load)
        return properties.stringPropertyNames().filter { it.startsWith("shootoff.arena.calibration.") }.associateWith(properties::getProperty)
    }

    private fun appOn(settings: Settings) = AppFixture.appWithCamera(settings, detector = {
        looks.incrementAndGet()
        seen.get()
    }, checkClock = now::get)

    @AfterEach
    fun close() = app.close()

    // A remembered calibration from the last session, as the app saved it
    private fun remembered(screen: String = "1280.0x720.0") {
        app.close()
        file.writeText(
            """
            shootoff.arena.calibration.remember=true
            shootoff.arena.calibration.camera=Test camera
            shootoff.arena.calibration.feed=640.0x480.0
            shootoff.arena.calibration.screen=$screen
            shootoff.arena.calibration.bounds=100.0,80.0,400.0,300.0
            """.trimIndent(),
        )
        app = appOn(Settings(file.path, arrayOf()))
    }

    // The arena opened on the projector, and the window reached it
    private fun openArenaOnTheProjector() {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
    }

    // The camera sends frames until the check has its outcome
    private fun sendFramesUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) {
            app.cameraView.updateBackground(frame, Optional.empty())
            Thread.sleep(10)
        }
        assertTrue(condition())
    }

    private fun calibrateWithTheCamera(bounds: Rect) {
        app.startCalibration()
        app.calibration.value!!.calibrate(bounds, Optional.empty(), false, 0)
    }

    @Test
    fun withRememberOffNothingIsSaved() {
        openArenaOnTheProjector()
        calibrateWithTheCamera(Rect(100.0, 80.0, 400.0, 300.0))

        assertNotNull(app.calibratedAt.value)
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

        openArenaOnTheProjector()
        assertEquals(CheckState.Checking, app.check.value)
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        sendFramesUntil { app.check.value == CheckState.Idle }

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.arena.value!!.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), app.camera.value!!.projectionBounds.get())
        assertNotNull(app.calibratedAt.value)
        assertNull(app.arena.value!!.background.value)
    }

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
    }

    @Test
    fun aSavedCalibrationForAnotherProjectorIsNotCheckedAndAsksForRecalibration() {
        remembered(screen = "1920.0x1080.0")

        openArenaOnTheProjector()

        assertEquals(
            CheckState.DoesntFit("The saved calibration was made for a 1920×1080 projector; this one is 1280×720"),
            app.check.value,
        )
        assertNull(app.arena.value!!.background.value)
        assertNull(app.arena.value!!.projection.value)
    }

    @Test
    fun aSavedCalibrationFitsOnlyItsOwnCameraFeedAndProjector() {
        val projector = Rect(4480.0, 0.0, 1280.0, 720.0)
        val feed = Size(640.0, 480.0)

        assertNull(savedCalibrationMismatch(saved, "Test camera", feed, projector))
        assertEquals("No projector screen found", savedCalibrationMismatch(saved, "Test camera", feed, null))
        assertEquals("The saved calibration was made with another camera (Test camera)", savedCalibrationMismatch(saved, "C920", feed, projector))
        assertEquals(
            "The saved calibration was made at 640×480; the camera is at 1280×720",
            savedCalibrationMismatch(saved, "Test camera", Size(1280.0, 720.0), projector),
        )
    }

    @Test
    fun calibratingDuringTheCheckStopsItFirstSoThePatternIsNeverSavedAsTheBackground() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        calibrateWithTheCamera(Rect(120.0, 90.0, 400.0, 300.0))

        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertEquals(Rect(120.0, 90.0, 400.0, 300.0), app.arena.value!!.projection.value)
    }

    @Test
    fun closingTheArenaMidCheckStopsIt() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.closeArena()
        val looked = looks.get()
        repeat(5) {
            app.cameraView.updateBackground(frame, Optional.empty())
            Thread.sleep(20)
        }

        assertEquals(CheckState.Idle, app.check.value)
        assertEquals(looked, looks.get())
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
    fun turningRememberOffMidCheckStopsItQuietly() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.setRememberCalibration(false)

        assertEquals(CheckState.Idle, app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertNull(app.arena.value!!.projection.value)
        assertEquals(emptyMap<String, String>(), savedKeys())
    }

    @Test
    fun cancellingTheCheckLeavesTheArenaUncalibratedAndNotVerified() {
        remembered()
        openArenaOnTheProjector()
        awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }

        app.cancelCheck()

        assertEquals(CheckState.NotVerified(Reason.CANCELLED), app.check.value)
        assertNull(app.arena.value!!.background.value)
        assertNull(app.arena.value!!.projection.value)
    }

    companion object {
        val SAVED_KEYS = mapOf(
            "shootoff.arena.calibration.remember" to "true",
            "shootoff.arena.calibration.camera" to "Test camera",
            "shootoff.arena.calibration.feed" to "640.0x480.0",
            "shootoff.arena.calibration.screen" to "1280.0x720.0",
            "shootoff.arena.calibration.bounds" to "100.0,80.0,400.0,300.0",
        )
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
```

The camera app takes a fake detector and clock (a detector that never finds the pattern by default):

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.MockCamera
import com.shootoff.camera.Shot
```

with:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.camera.MockCamera
import com.shootoff.camera.Shot
```

Replace:

```kotlin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Optional
```

with:

```kotlin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.util.Optional
```

Replace:

```kotlin
        background: CoroutineDispatcher = Dispatchers.Default,
        screens: List<Rect> = ownerScreens,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
        return AppState(settings, catalog, oneCamera(), { screens }, ManualClock(), { it.run() }, background)
    }
```

with:

```kotlin
        background: CoroutineDispatcher = Dispatchers.Default,
        screens: List<Rect> = ownerScreens,
        detector: CalibrationCheck.Detector<BufferedImage> = CalibrationCheck.Detector { Optional.empty() },
        checkClock: () -> Long = System::currentTimeMillis,
    ): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
        return AppState(
            settings,
            catalog,
            oneCamera(),
            { screens },
            ManualClock(),
            { it.run() },
            background,
            detector = { detector },
            checkClock = checkClock,
        )
    }
```

Rule 3:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spec §8 "Nothing blocks": one test per rule. */
```

with:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.compose.calibration.CheckState
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Spec §8 "Nothing blocks": one test per rule. */
```

Replace:

```kotlin
        }
    }
}
```

with:

```kotlin
        }
    }

    @Test
    fun rule3TheCheckRunsOffTheUiThreadAndGivesUpQuietlyAfterThreeSeconds() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
        val now = AtomicLong(0)
        val lookedOn = AtomicReference<Thread>()
        // The projector is off: the pattern is never seen
        val app = AppFixture.appWithCamera(settings, detector = {
            lookedOn.set(Thread.currentThread())
            Optional.empty()
        }, checkClock = now::get)
        try {
            app.openStartCamera()
            app.openArena()
            app.arena.value!!.setFullScreen(true)
            awaitTrue { app.arena.value!!.background.value?.name == "pattern.png" }
            app.cameraView.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR), Optional.empty())
            awaitTrue { lookedOn.get() != null }

            now.set(CalibrationCheck.DEFAULT_TIME_LIMIT)
            awaitTrue { app.check.value != CheckState.Checking }

            assertEquals(CheckState.NotVerified(CalibrationCheck.Reason.PATTERN_NOT_SEEN), app.check.value)
            assertNotSame(Thread.currentThread(), lookedOn.get())
            assertNull(app.arena.value!!.projection.value)
            assertNull(app.arena.value!!.background.value)
        } finally {
            app.close()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestRememberedCalibration' --console=plain`

Expected: `compileTestKotlin` FAILS, with errors such as `Unresolved reference 'CheckState'`, `Unresolved reference 'setRememberCalibration'` and `No parameter with name 'detector' found`.

- [ ] **Step 3: Write the check's run, its states and the frame tap**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`:

```kotlin
package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.calibration.CalibrationFlow
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

    data class Moved(val pixels: Int) : CheckState

    data class NotVerified(val reason: Reason) : CheckState

    /** The saved calibration was made with another camera or projector, so it wasn't checked */
    data class DoesntFit(val why: String) : CheckState
}

/** What Setup and Range say about a check, or null when there is nothing to say */
fun CheckState.text(): String? = when (this) {
    CheckState.Idle -> null
    CheckState.Checking -> "Checking the saved calibration…"
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

/**
 * One check of a remembered calibration on the open arena (spec §8): the pattern shows on the arena,
 * the camera's frames are looked at in [scope], never on the UI thread, and [onDone] hears the outcome
 * on the UI thread once the arena's background is back. Shot detection is off while the pattern shows,
 * as in calibration, and comes back shortly after.
 */
class CalibrationCheckRun(
    private val saved: SavedCalibration,
    private val arena: ArenaModel,
    private val camera: CalibrationCamera,
    private val frames: LatestFrame,
    private val detector: CalibrationCheck.Detector<BufferedImage>,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val scheduler: CalibrationFlow.Scheduler,
    private val uiThread: (Runnable) -> Unit,
    private val onDone: (CalibrationCheckRun, CalibrationCheck.Outcome) -> Unit,
) {
    companion object {
        // How often a frame is looked at; detection itself takes a few tens of milliseconds
        const val POLL_MILLIS = 50L
    }

    private val finished = AtomicBoolean(false)
    private var background: ArenaBackground? = null

    @Volatile
    private var check: CalibrationCheck<BufferedImage>? = null

    @Volatile
    private var job: Job? = null

    /** Shows the pattern and starts looking; on the UI thread. */
    fun start() {
        background = arena.background.value
        arena.showResource("pattern.png")
        camera.setDetecting(false)
        frames.take()
        val check = CalibrationCheck(saved.bounds, detector, clock)
        this.check = check
        job = scope.launch {
            while (isActive) {
                val frame = frames.take()
                val outcome = (if (frame != null) check.offer(frame) else check.tick()).orElse(null)
                if (outcome != null) {
                    uiThread(Runnable { finish(outcome) })
                    return@launch
                }
                delay(POLL_MILLIS)
            }
        }
    }

    /** Stops the check for [reason], unless it already has an outcome; on the UI thread. */
    fun stop(reason: Reason) {
        val outcome = check?.stop(reason) ?: CalibrationCheck.NotVerified(reason)
        finish(outcome)
    }

    private fun finish(outcome: CalibrationCheck.Outcome) {
        if (!finished.compareAndSet(false, true)) return
        job?.cancel()
        arena.setBackground(background)
        scheduler.schedule({ camera.setDetecting(true) }, CalibrationFlow.DETECTION_RESTART_DELAY)
        onDone(this, outcome)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt`:

Replace:

```kotlin
        private set

    override fun setCameraManager(cameraManager: CameraManager) {
        this.cameraManager = cameraManager
```

with:

```kotlin
        private set

    /** Also hears every frame, on the camera's thread (the calibration check takes them from here) */
    @Volatile
    var frameTap: ((BufferedImage) -> Unit)? = null

    override fun setCameraManager(cameraManager: CameraManager) {
        this.cameraManager = cameraManager
```

Replace:

```kotlin
            return
        }

        val display = feed.displaySize
```

with:

```kotlin
            return
        }

        frameTap?.invoke(frame)

        val display = feed.displaySize
```

- [ ] **Step 4: Remember, save and check in `AppState`**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.DiagnosticMessage
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.shot.ScaledShot
```

with:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.DiagnosticMessage
import com.shootoff.camera.autocalibration.PatternDetector
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.shot.ScaledShot
```

Replace:

```kotlin
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
import com.shootoff.compose.calibration.CalibrationController
import com.shootoff.compose.calibration.CalibrationViews
import com.shootoff.compose.drill.ArenaHostSurface
import com.shootoff.compose.drill.ComposeExerciseHost
```

with:

```kotlin
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
```

Replace:

```kotlin
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
```

with:

```kotlin
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.SavedCalibration
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
```

Replace:

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
```

with:

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
```

Replace:

```kotlin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** The Range screen's big view */
```

with:

```kotlin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** The Range screen's big view */
```

Replace:

```kotlin
 * @param io opens and lists cameras, which can take seconds, off the UI thread
 * @param wallClock the time of day, which the calibration status shows
 */
class AppState(
```

with:

```kotlin
 * @param io opens and lists cameras, which can take seconds, off the UI thread
 * @param wallClock the time of day, which the calibration status shows
 * @param detector finds the calibration pattern in a camera's frames, for the remembered calibration's check
 * @param checkClock the check's time limit runs on it
 */
class AppState(
```

Replace:

```kotlin
    val prefs: UiPrefs = UiPrefs(),
    private val wallClock: () -> LocalTime = LocalTime::now,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

with:

```kotlin
    val prefs: UiPrefs = UiPrefs(),
    private val wallClock: () -> LocalTime = LocalTime::now,
    private val detector: (CameraManager) -> CalibrationCheck.Detector<BufferedImage> = { PatternDetector(it.camera) },
    private val checkClock: () -> Long = System::currentTimeMillis,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

Replace:

```kotlin
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null
```

with:

```kotlin
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
    private val checkFrames = LatestFrame()
    private var checkRun: CalibrationCheckRun? = null
    private var checkWatch: Job? = null

    // The arena's calibration now, as it would be remembered; null while uncalibrated, or when it can't be
    // remembered (no projector screen)
    private var currentCalibration: SavedCalibration? = null
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null
```

Replace:

```kotlin
    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /** The open camera, or null */
```

with:

```kotlin
    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /** Whether calibrations are kept for the next session ("Remember calibration") */
    val rememberCalibration: StateFlow<Boolean> = rememberState.asStateFlow()

    /** Where the check of the remembered calibration stands */
    val check: StateFlow<CheckState> = checkState.asStateFlow()

    /** The open camera, or null */
```

Replace:

```kotlin
        cameraState.value?.let { makeCalibratable(arena, it) }
    }
```

with:

```kotlin
        cameraState.value?.let { makeCalibratable(arena, it) }
        if (settings.rememberCalibration()) checkRemembered(arena)
    }

    // The remembered calibration, checked (never at launch: only here, as the arena opens) if it was made
    // with this camera and a projector screen like this one. The check waits for the arena to reach the
    // projector, since the pattern on a window still on its way there would be measured in the wrong place.
    private fun checkRemembered(arena: ArenaModel) {
        val saved = settings.savedCalibration.orElse(null) ?: return
        val camera = cameraState.value
        if (camera == null || calibrationState.value == null) {
            checkState.value = CheckState.NotVerified(CalibrationCheck.Reason.NO_CAMERA)
            return
        }
        val feed = Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble())
        savedCalibrationMismatch(saved, camera.name, feed, placementState.value?.screen)?.let {
            checkState.value = CheckState.DoesntFit(it)
            return
        }

        checkState.value = CheckState.Checking
        checkWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            arena.fullScreen.first { it }
            uiThread(Runnable { if (arenaState.value === arena && checkState.value == CheckState.Checking && checkRun == null) runCheck(arena, camera, saved) })
        }
    }

    private fun runCheck(arena: ArenaModel, camera: CameraManager, saved: SavedCalibration) {
        val run = CalibrationCheckRun(saved, arena, camera, checkFrames, detector(camera), checkClock, scope, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread) { run, outcome -> checked(run, saved, outcome) }
        checkRun = run
        cameraView.frameTap = checkFrames::offer
        run.start()
    }

    // The check's outcome, on the UI thread; ignored if the check was stopped by something that replaced it
    private fun checked(run: CalibrationCheckRun, saved: SavedCalibration, outcome: CalibrationCheck.Outcome) {
        if (checkRun !== run) return
        checkRun = null
        cameraView.frameTap = null
        when (outcome) {
            is CalibrationCheck.Kept -> {
                calibrationState.value?.applySaved(saved)
                currentCalibration = saved
                calibratedAtState.value = wallClock()
                checkState.value = CheckState.Idle
            }
            is CalibrationCheck.Moved -> checkState.value = CheckState.Moved(outcome.distance().roundToInt())
            is CalibrationCheck.NotVerified -> checkState.value = CheckState.NotVerified(outcome.reason())
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
     * "Remember calibration". On: the arena's calibration, now and after each calibration, is saved for the
     * next session. Off: nothing is saved, the saved one is forgotten, and a check under way stops.
     */
    fun setRememberCalibration(remember: Boolean) {
        rememberState.value = remember
        settings.setRememberCalibration(remember)
        settings.setSavedCalibration(if (remember) currentCalibration else null)
        if (!remember && checkState.value == CheckState.Checking) stopCheckQuietly()
        saveSettings()
    }

    private fun saveSettings() {
        try {
            settings.writeConfigurationFile()
        } catch (e: Exception) {
            logger.error("Couldn't save the settings", e)
        }
    }
```

Replace:

```kotlin
    fun closeArena() {
        val arena = arenaState.value ?: return
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
```

with:

```kotlin
    fun closeArena() {
        val arena = arenaState.value ?: return
        stopCheckQuietly()
        currentCalibration = null
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
```

Replace:

```kotlin
    fun startCalibration(): Boolean {
        val controller = calibrationState.value ?: return false
        controller.start()
        return true
```

with:

```kotlin
    fun startCalibration(): Boolean {
        val controller = calibrationState.value ?: return false
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
        controller.start()
        return true
```

Replace:

```kotlin
    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        calibratedAtState.value = wallClock()
    }
```

with:

```kotlin
    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        calibratedAtState.value = wallClock()
        checkState.value = CheckState.Idle
        val camera = cameraState.value
        val screen = placementState.value?.screen
        // Made without a projector screen, a calibration can't be matched to one next time
        currentCalibration = if (camera != null && screen != null) {
            SavedCalibration(camera.name, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), Size(screen.width, screen.height), cameraBounds, paper)
        } else {
            null
        }
        if (settings.rememberCalibration()) {
            settings.setSavedCalibration(currentCalibration)
            saveSettings()
        }
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --console=plain`

Expected: `BUILD SUCCESSFUL`; `TestRememberedCalibration` (12) and `TestNothingBlocks` (2) pass, with the rest of the package.

- [ ] **Step 6: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
```

Expected: `661/661 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 7: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt
git commit -m "Remember the calibration and check it in the background when the arena opens"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 5: The Setup destination: camera, projector, calibrate, the calibration view, and F6

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/calibration/ProjectionOutline.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt`
- Test (new): `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt`, `TestSetupModel.kt`, `TestSetupScreen.kt`, `TestNothingBlocksViews.kt`; `compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaGrid.kt`
- Test (changed): `compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt` (two tests added)

**Interfaces:**
- Consumes: Tasks 3–4's `AppState` (`startCalibration`, `cancelCalibration`, `calibratedAt`, `rememberCalibration`, `setRememberCalibration`, `check`, `cancelCheck`), `CheckState.text()`, `CalibrationOverlay`; Plan 5's `CameraFeedView`, `NoCameraPanel`, `ArenaView`, `SurfaceTransform`, `Range` colors.
- Produces:
  - `Destination.SETUP("Setup", Icons.Filled.Build, true)`; the rail's order is Range, Setup, Drills, Targets, Sessions, Settings (§8)
  - on `ArenaModel`: `grid: StateFlow<Boolean>`, `showGrid(show: Boolean)`; `@Composable fun AlignmentGrid(size: com.shootoff.geom.Size, transform: SurfaceTransform)`, drawn last by `ArenaCanvas`, tagged `arena-grid`
  - `com.shootoff.compose.app` (`SetupSteps.kt`):
    - `enum class Step(label) { CAMERA, PROJECTOR, CALIBRATE }`, `enum class StepState { DONE, NEXT, WAITING }`
    - `data class SetupSteps(camera, projector, calibrate: StepState)` with `ready: Boolean` and `operator fun get(step: Step)`
    - `fun setupSteps(cameraOpen: Boolean, arenaOpen: Boolean, calibrated: Boolean): SetupSteps`
    - `fun calibrationSummary(cameraOpen, arenaOpen, calibrating, calibrated: Boolean, calibratedAt: LocalTime?, check: CheckState): String`
  - `@Composable fun SetupScreen(app, modifier)`: tags `setup-screen`, `step-CAMERA|PROJECTOR|CALIBRATE` (state description = the `StepState` name), `camera-details`, `camera-missing`, `setup-camera-<name>`, `no-projector`, `open-arena`, `close-arena`, `arena-preview`, `calibration-summary`, `setup-calibrate`, `setup-cancel`, `remember-calibration`, `show-grid`
  - `@Composable fun ProjectionOutline(projection: Rect, transform: SurfaceTransform, modifier)`, tagged `projection-outline`
  - on `AppState`: `showGrid(show: Boolean)`, `gridAllowed(): Boolean`, `pickCamera(camera: Camera)`; `navigate` turns the grid off everywhere but Setup; the manual box navigates to Setup; a success while on Setup navigates to Range; calibration, a check and a drill turn the grid off
  - F6 (`Shortcut.CALIBRATE`, "Open Setup and start calibrating") navigates to Setup, then `startCalibration()`

**The screen.** A column of the three step cards on the left (the next step highlighted, a done one ✓), and the camera feed on the right. Calibration's overlay (the messages with Cancel, the manual box with Done) is on this feed, and the calibrated projection is outlined in orange whenever it isn't calibrating. The Camera step lists the other cameras (opening one saves it, as Settings does, through the new `pickCamera`); the Projector step opens or closes the arena and shows its small live preview; the Calibrate step says where calibration stands and offers Calibrate (or Recalibrate), Cancel while calibrating or checking, Remember calibration and Show grid (rulings 9–11).

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.calibration.CalibrationCheck.Reason
import com.shootoff.compose.calibration.CheckState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

class TestSetupSteps {
    @Test
    fun theFirstStepNotDoneIsNextAndTheOnesAfterItWait() {
        assertEquals(SetupSteps(StepState.NEXT, StepState.WAITING, StepState.WAITING), setupSteps(false, false, false))
        // The projector can be done before the camera
        assertEquals(SetupSteps(StepState.NEXT, StepState.DONE, StepState.WAITING), setupSteps(false, true, false))
        assertEquals(SetupSteps(StepState.DONE, StepState.DONE, StepState.NEXT), setupSteps(true, true, false))
        assertFalse(setupSteps(true, true, false).ready)
        assertTrue(setupSteps(true, true, true).ready)
    }

    @Test
    fun theSummarySaysWhatIsMissingWhatIsUnderWayOrWhenItWasCalibrated() {
        val idle = CheckState.Idle
        assertEquals("No camera", calibrationSummary(false, true, false, false, null, idle))
        assertEquals("No arena", calibrationSummary(true, false, false, false, null, idle))
        assertEquals("Calibrating…", calibrationSummary(true, true, true, true, null, idle))
        assertEquals("Checking the saved calibration…", calibrationSummary(true, true, false, false, null, CheckState.Checking))
        assertEquals("The projection moved about 14 px — recalibrate", calibrationSummary(true, true, false, false, null, CheckState.Moved(14)))
        assertEquals(
            "The pattern wasn't seen: not verified — recalibrate on Setup",
            calibrationSummary(true, true, false, false, null, CheckState.NotVerified(Reason.PATTERN_NOT_SEEN)),
        )
        assertEquals("✓ Calibrated 01:12", calibrationSummary(true, true, false, true, LocalTime.of(1, 12, 40), idle))
        assertEquals("Not calibrated", calibrationSummary(true, true, false, false, null, idle))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupModel.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Rect
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

    private fun setUpOnTheProjector() {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.navigate(Destination.SETUP)
    }

    @Test
    fun theGridShowsOnlyOnSetupAndNeverOverCalibrationOrADrill() {
        setUpOnTheProjector()
        val arena = app.arena.value!!

        app.showGrid(true)
        assertTrue(arena.grid.value)
        app.navigate(Destination.RANGE)
        assertFalse(arena.grid.value)

        app.navigate(Destination.SETUP)
        app.showGrid(true)
        app.startCalibration()
        assertFalse(arena.grid.value)
        app.showGrid(true)
        assertFalse(arena.grid.value)
        app.cancelCalibration()

        app.showGrid(true)
        assertTrue(app.startDrill(AppFixture.feedDrill))
        assertFalse(arena.grid.value)
    }

    @Test
    fun aCalibrationFinishedOnSetupGoesBackToTheRange() {
        setUpOnTheProjector()

        app.startCalibration()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Destination.RANGE, app.destination.value)
    }

    @Test
    fun theManualBoxBringsTheOwnerToSetupWhereTheFeedIs() {
        app.openStartCamera()
        app.openArena()
        app.startCalibration()
        assertEquals(Destination.RANGE, app.destination.value)

        app.showCalibratingFeed()

        assertEquals(Destination.SETUP, app.destination.value)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.RangeDark
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TestSetupScreen {
    @get:Rule
    val compose = createComposeRule()

    private var app = AppFixture.app()

    @After
    fun close() = app.close()

    private fun showApp() = compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

    private fun step(step: Step, state: StepState) =
        compose.onNodeWithTag("step-${step.name}").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state.name))

    @Test
    fun theRailLeadsToSetupWhereWithoutACameraTheCameraStepIsNext() {
        showApp()

        compose.onNodeWithTag("rail-SETUP").performClick()

        compose.onNodeWithTag("setup-screen").assertExists()
        step(Step.CAMERA, StepState.NEXT)
        step(Step.PROJECTOR, StepState.WAITING)
        step(Step.CALIBRATE, StepState.WAITING)
        compose.onNodeWithTag("camera-missing").assertExists()
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun openingTheArenaOnSetupTicksTheStepAndShowsItsPreview() {
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("open-arena").performClick()

        compose.onNodeWithTag("arena-preview").assertExists()
        step(Step.PROJECTOR, StepState.DONE)
        compose.onNodeWithText("No arena").assertDoesNotExist()
    }

    @Test
    fun calibratingOnSetupShowsCancelOnTheStepAndOverTheFeed() {
        app.close()
        app = AppFixture.appWithCamera()
        app.openStartCamera()
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("setup-calibrate").performClick()

        compose.onNodeWithTag("setup-cancel").assertExists()
        compose.onNodeWithTag("calibration-cancel").assertExists()
        compose.onNodeWithText("Calibrating…").assertExists()

        compose.onNodeWithTag("setup-cancel").performClick()
        compose.onNodeWithTag("setup-calibrate").assertExists()
    }

    @Test
    fun rememberAndShowGridAreOnTheCalibrateStep() {
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("remember-calibration").performClick()
        compose.onNodeWithTag("show-grid").performClick()

        assertTrue(app.rememberCalibration.value)
        assertTrue(app.arena.value!!.grid.value)
    }

    @Test
    fun theCalibratedProjectionIsOutlinedOverTheFeed() {
        // A 640x480 canvas shown at 320x240
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) {
                    Box(Modifier.size(320.dp, 240.dp).testTag("feed")) {
                        ProjectionOutline(Rect(100.0, 80.0, 400.0, 300.0), SurfaceTransform.fit(Size(640.0, 480.0), 320f, 240f))
                    }
                }
            }
        }

        val pixels = compose.onNodeWithTag("feed").captureToImage().toPixelMap()
        // The left edge at x = 50, halfway down the outline; inside it, nothing
        assertEquals(RangeDark.accent, pixels[50, 115])
        assertEquals(0f, pixels[120, 115].alpha)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaGrid.kt`:

```kotlin
package com.shootoff.compose.arena

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestArenaGrid {
    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val arena: ArenaModel = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        .also { it.setSize(Size(640.0, 480.0)) }

    private fun pixels(): PixelMap = compose.onNodeWithTag("arena").captureToImage().toPixelMap()

    @Test
    fun theGridCoversTheArenaWithLinesCornersAndCenterUntilItIsTurnedOff() {
        arena.setCalibrationLabelVisible(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                ArenaCanvas(arena, Modifier.size(640.dp, 480.dp).testTag("arena"))
            }
        }

        arena.showGrid(true)
        compose.waitForIdle()
        var pixels = pixels()
        // A tenth of the way across and down: a line; between lines: black
        assertEquals(Color.White, pixels[64, 100])
        assertEquals(Color.White, pixels[100, 48])
        assertEquals(Color.Black, pixels[32, 24])
        // The top left corner's mark and the center's
        assertEquals(Color(0xFFF5A807), pixels[20, 1])
        assertEquals(Color(0xFFF5A807), pixels[320, 240])

        arena.showGrid(false)
        compose.waitForIdle()
        pixels = pixels()
        assertEquals(Color(0xFF333333), pixels[64, 100])
    }
}
```

Rule 5:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocksViews.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.Optional

/** Spec §8 "Nothing blocks", the rules seen on screen. */
class TestNothingBlocksViews {
    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf()).apply {
        setRememberCalibration(true)
        setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
    }
    private val app = AppFixture.appWithCamera(settings)

    @After
    fun close() = app.close()

    @Test
    fun rule5CancelIsOnScreenWhileTheCheckRunsAndWhileCalibrating() {
        app.openStartCamera()
        app.openArena()
        app.navigate(Destination.SETUP)
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }
        assertEquals(CheckState.Checking, app.check.value)

        compose.onNodeWithTag("setup-cancel").performClick()
        compose.waitForIdle()
        assertEquals(CheckState.NotVerified(CalibrationCheck.Reason.CANCELLED), app.check.value)

        compose.onNodeWithTag("setup-calibrate").performClick()
        compose.onNodeWithTag("calibration-cancel").performClick()
        compose.waitForIdle()
        assertFalse(app.calibration.value!!.state.value.calibrating)
    }
}
```

F6, including the Review Focus's F6 during a drill:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`:

Replace:

```kotlin
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.drill.DrillButton
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestShortcuts {
```

with:

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
```

Replace:

```kotlin
        assertFalse(app.handleKey(Key.A, KeyEventType.KeyDown))
    }
}
```

with:

```kotlin
        assertFalse(app.handleKey(Key.A, KeyEventType.KeyDown))
    }

    @Test
    fun f6OpensSetupAndStartsCalibrating() {
        val app = AppFixture.appWithCamera()
        try {
            app.openStartCamera()
            app.openArena()

            assertTrue(app.handleKey(Key.F6, KeyEventType.KeyDown))

            assertEquals(Destination.SETUP, app.destination.value)
            assertTrue(app.calibration.value!!.state.value.calibrating)
        } finally {
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
            assertEquals(Destination.RANGE, app.destination.value)
        } finally {
            app.close()
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --tests 'com.shootoff.compose.arena.*' --console=plain`

Expected: `compileTestKotlin` FAILS, with errors such as `Unresolved reference 'SETUP'`, `Unresolved reference 'setupSteps'`, `Unresolved reference 'showGrid'` and `Unresolved reference 'ProjectionOutline'`.

- [ ] **Step 3: Setup on the rail, and the grid on the arena**

`compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`:

Replace:

```kotlin
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
```

with:

```kotlin
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
```

Replace:

```kotlin
/**
 * The places the rail leads to. Targets and Sessions are in the JavaFX app for now (spec §1).
 */
enum class Destination(val label: String, val icon: ImageVector, val enabled: Boolean) {
    RANGE("Range", Icons.Filled.Home, true),
    TARGETS("Targets", Icons.Filled.Place, false),
    DRILLS("Drills", Icons.Filled.PlayArrow, true),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.List, false),
    SETTINGS("Settings", Icons.Filled.Settings, true),
```

with:

```kotlin
/**
 * The places the rail leads to, in the rail's order (spec §8). Targets and Sessions are in the JavaFX app
 * for now.
 */
enum class Destination(val label: String, val icon: ImageVector, val enabled: Boolean) {
    RANGE("Range", Icons.Filled.Home, true),
    SETUP("Setup", Icons.Filled.Build, true),
    DRILLS("Drills", Icons.Filled.PlayArrow, true),
    TARGETS("Targets", Icons.Filled.Place, false),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.List, false),
    SETTINGS("Settings", Icons.Filled.Settings, true),
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`:

Replace:

```kotlin
    private val fullScreenState = MutableStateFlow(false)
    private val labelState = MutableStateFlow(true)

    /** The arena window's size, in dp: the arena's coordinates */
```

with:

```kotlin
    private val fullScreenState = MutableStateFlow(false)
    private val labelState = MutableStateFlow(true)
    private val gridState = MutableStateFlow(false)

    /** The arena window's size, in dp: the arena's coordinates */
```

Replace:

```kotlin
    /** Whether the arena says "Needs calibration" (until it is first calibrated) */
    val needsCalibrationLabel: StateFlow<Boolean> = labelState.asStateFlow()

    @Volatile
```

with:

```kotlin
    /** Whether the arena says "Needs calibration" (until it is first calibrated) */
    val needsCalibrationLabel: StateFlow<Boolean> = labelState.asStateFlow()

    /** Whether the arena shows Setup's alignment grid in place of everything else */
    val grid: StateFlow<Boolean> = gridState.asStateFlow()

    @Volatile
```

Replace:

```kotlin
    }

    /** Shows or hides every arena target, as calibration does. */
    fun setTargetsVisible(visible: Boolean) {
```

with:

```kotlin
    }

    fun showGrid(show: Boolean) {
        gridState.value = show
    }

    /** Shows or hides every arena target, as calibration does. */
    fun setTargetsVisible(visible: Boolean) {
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`:

Replace:

```kotlin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
```

with:

```kotlin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
```

Replace:

```kotlin
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.TargetLayer
import kotlin.math.roundToInt
```

with:

```kotlin
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.geom.Size as GeomSize
import kotlin.math.roundToInt
```

Replace:

```kotlin
    val background by arena.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val density = LocalDensity.current
```

with:

```kotlin
    val background by arena.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val grid by arena.grid.collectAsState()
    val density = LocalDensity.current
```

Replace:

```kotlin
        }
        overlay(transform)
    }
}
```

with:

```kotlin
        }
        overlay(transform)
        if (grid) AlignmentGrid(size, transform)
    }
}

// How many cells the grid has across and down
private const val GRID_CELLS = 10
// The corner and center marks' arm length and the lines' width, in arena pixels
private const val MARK_LENGTH = 40.0
private const val LINE_WIDTH = 2f

/**
 * Setup's alignment grid, over everything else on the arena: black, with evenly spaced white lines, and
 * the arena's corners and center marked in orange, so its fit to the calibrated rectangle can be judged.
 */
@Composable
fun AlignmentGrid(size: GeomSize, transform: SurfaceTransform) {
    Canvas(Modifier.fillMaxSize().testTag("arena-grid")) {
        val topLeft = transform.toView(0.0, 0.0)
        val bottomRight = transform.toView(size.width, size.height)
        drawRect(Color.Black, topLeft, Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y))
        val stroke = (LINE_WIDTH * transform.scale).coerceAtLeast(1f)
        for (i in 1 until GRID_CELLS) {
            val x = transform.toView(size.width * i / GRID_CELLS, 0.0).x
            val y = transform.toView(0.0, size.height * i / GRID_CELLS).y
            drawLine(Color.White, Offset(x, topLeft.y), Offset(x, bottomRight.y), stroke)
            drawLine(Color.White, Offset(topLeft.x, y), Offset(bottomRight.x, y), stroke)
        }
        val arm = (MARK_LENGTH * transform.scale).toFloat()
        val mark = stroke * 3
        // Each corner's L, pointing into the arena
        for ((corner, direction) in listOf(
            topLeft to Offset(1f, 1f),
            Offset(bottomRight.x, topLeft.y) to Offset(-1f, 1f),
            Offset(topLeft.x, bottomRight.y) to Offset(1f, -1f),
            bottomRight to Offset(-1f, -1f),
        )) {
            drawLine(CALIBRATION_ORANGE, corner, corner + Offset(direction.x * arm, 0f), mark)
            drawLine(CALIBRATION_ORANGE, corner, corner + Offset(0f, direction.y * arm), mark)
        }
        val center = transform.toView(size.width / 2, size.height / 2)
        drawLine(CALIBRATION_ORANGE, center - Offset(arm, 0f), center + Offset(arm, 0f), mark)
        drawLine(CALIBRATION_ORANGE, center - Offset(0f, arm), center + Offset(0f, arm), mark)
    }
}
```

- [ ] **Step 4: The steps, the outline and the screen**

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.text
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Setup's steps, in order */
enum class Step(val label: String) {
    CAMERA("Camera"),
    PROJECTOR("Projector"),
    CALIBRATE("Calibrate"),
}

/** A step's state: done (✓), the next one to do (highlighted), or waiting its turn */
enum class StepState { DONE, NEXT, WAITING }

/**
 * Where setup stands: each step's state, and whether a projector drill can run.
 */
data class SetupSteps(val camera: StepState, val projector: StepState, val calibrate: StepState) {
    val ready: Boolean get() = camera == StepState.DONE && projector == StepState.DONE && calibrate == StepState.DONE

    operator fun get(step: Step): StepState = when (step) {
        Step.CAMERA -> camera
        Step.PROJECTOR -> projector
        Step.CALIBRATE -> calibrate
    }
}

/** The first step not done is the next; the ones after it wait. */
fun setupSteps(cameraOpen: Boolean, arenaOpen: Boolean, calibrated: Boolean): SetupSteps {
    val done = listOf(cameraOpen, arenaOpen, calibrated)
    val next = done.indexOfFirst { !it }
    val states = done.mapIndexed { index, isDone ->
        when {
            isDone -> StepState.DONE
            index == next -> StepState.NEXT
            else -> StepState.WAITING
        }
    }
    return SetupSteps(states[0], states[1], states[2])
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/**
 * One line about the arena's calibration, for Setup's Calibrate step and Range's status chip: what is
 * missing, what is under way, or when it was calibrated.
 */
fun calibrationSummary(
    cameraOpen: Boolean,
    arenaOpen: Boolean,
    calibrating: Boolean,
    calibrated: Boolean,
    calibratedAt: LocalTime?,
    check: CheckState,
): String {
    val checking = check.text()
    return when {
        !cameraOpen -> "No camera"
        !arenaOpen -> "No arena"
        calibrating -> "Calibrating…"
        checking != null -> checking
        calibrated -> "✓ Calibrated" + (calibratedAt?.let { " " + it.format(TIME) } ?: "")
        else -> "Not calibrated"
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/ProjectionOutline.kt`:

```kotlin
package com.shootoff.compose.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Rect

/** The calibrated projection, outlined in orange over the camera feed it was found on. */
@Composable
fun ProjectionOutline(projection: Rect, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val color = Range.colors.accent
    Canvas(modifier.fillMaxSize().testTag("projection-outline")) {
        val topLeft = transform.toView(projection.minX, projection.minY)
        val size = Size((projection.width * transform.scale).toFloat(), (projection.height * transform.scale).toFloat())
        drawRect(color, topLeft, size, style = Stroke(2.dp.toPx()))
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaView
import com.shootoff.compose.calibration.CalibrationOverlay
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.calibration.ProjectionOutline
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay

/**
 * Setup (spec §8): the camera, the projector and calibration, as three steps in order, each with its
 * state, beside the camera feed on which calibration shows its box and the calibrated projection is
 * outlined. Nothing here waits on the hardware: each step says what is missing and the rest still works.
 */
@Composable
fun SetupScreen(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val projection = arena?.projection?.collectAsState()?.value
    val steps = setupSteps(camera != null, arena != null, projection != null)
    val colors = Range.colors

    Row(modifier.fillMaxSize().padding(8.dp).testTag("setup-screen"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Setup", fontSize = 22.sp, color = colors.text)
            StepCard(Step.CAMERA, steps.camera) { CameraStep(app) }
            StepCard(Step.PROJECTOR, steps.projector) { ProjectorStep(app) }
            StepCard(Step.CALIBRATE, steps.calibrate) { CalibrateStep(app) }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (camera == null) {
                NoCameraPanel(app)
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                    if (projection != null && !calibrating) ProjectionOutline(projection, transform)
                    controller?.let { CalibrationOverlay(it, transform) }
                }
            }
        }
    }
}

@Composable
private fun StepCard(step: Step, state: StepState, content: @Composable () -> Unit) {
    val colors = Range.colors
    val number = step.ordinal + 1
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (state == StepState.NEXT) colors.highlightCard else colors.card,
        border = BorderStroke(1.dp, if (state == StepState.NEXT) colors.highlightBorder else colors.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("step-${step.name}").semantics { stateDescription = state.name },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (state == StepState.DONE) "✓ " else "$number  ") + step.label,
                fontSize = 16.sp,
                color = when (state) {
                    StepState.DONE -> colors.good
                    StepState.NEXT -> colors.text
                    StepState.WAITING -> colors.muted
                },
            )
            content()
        }
    }
}

/** The camera: the open one with its frame rate and size, or why there is none, and the cameras to pick from */
@Composable
private fun CameraStep(app: AppState) {
    val camera by app.camera.collectAsState()
    val problem by app.cameraProblem.collectAsState()
    val opening by app.openingCamera.collectAsState()
    val cameras by app.cameraList.collectAsState()
    val colors = Range.colors
    var fps by remember { mutableStateOf(0.0) }
    // Listed off the UI thread: it can take seconds
    LaunchedEffect(Unit) { app.refreshCameras() }
    LaunchedEffect(camera) {
        while (true) {
            fps = camera?.fps ?: 0.0
            delay(1000)
        }
    }

    val open = camera
    if (open != null) {
        Text(
            "${app.settings.getWebcamsUserName(open.camera).orElse(open.name)} · ${"%.0f".format(fps)} FPS · ${open.feedWidth}×${open.feedHeight}",
            color = colors.mutedStrong,
            modifier = Modifier.testTag("camera-details"),
        )
    } else {
        Text(problem ?: "No camera", color = colors.mutedStrong, modifier = Modifier.testTag("camera-missing"))
    }
    opening?.let { Text("Opening camera $it…", color = colors.muted) }
    val found = cameras
    when {
        found == null -> Text("Looking for cameras…", color = colors.muted)
        found.isEmpty() -> Text("No cameras found. Plug one in and come back.", color = colors.muted)
    }
    for (choice in found.orEmpty()) {
        if (open?.camera == choice) continue
        FilledTonalButton(
            onClick = { app.pickCamera(choice) },
            enabled = opening == null,
            modifier = Modifier.testTag("setup-camera-${choice.name}"),
        ) { Text("Use ${choice.name}") }
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
        if (!projectorFound) {
            Text("No projector screen found: the arena opens as a window", color = colors.warning, modifier = Modifier.testTag("no-projector"))
        }
        FilledTonalButton(onClick = app::openArena, modifier = Modifier.testTag("open-arena")) { Text("Open arena") }
    } else {
        ArenaView(open, Modifier.size(240.dp, 135.dp).testTag("arena-preview"))
        FilledTonalButton(onClick = app::closeArena, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
    }
}

/** Calibration: where it stands, Calibrate or Cancel, Remember calibration and Show grid */
@Composable
private fun CalibrateStep(app: AppState) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val remember by app.rememberCalibration.collectAsState()
    val grid = arena?.grid?.collectAsState()?.value == true
    val running by app.runner.running.collectAsState()
    val colors = Range.colors

    Text(
        calibrationSummary(camera != null, arena != null, calibrating, calibrated, calibratedAt, check),
        color = if (calibrated && !calibrating) colors.good else colors.mutedStrong,
        modifier = Modifier.testTag("calibration-summary"),
    )
    // Cancel is always there while something runs (spec §8 rule 5)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            calibrating -> FilledTonalButton(onClick = app::cancelCalibration, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            check == CheckState.Checking -> FilledTonalButton(onClick = app::cancelCheck, modifier = Modifier.testTag("setup-cancel")) { Text("Cancel") }
            else -> Button(onClick = { app.startCalibration() }, enabled = controller != null, modifier = Modifier.testTag("setup-calibrate")) {
                Text(if (calibrated) "Recalibrate" else "Calibrate")
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = remember, onCheckedChange = app::setRememberCalibration, modifier = Modifier.testTag("remember-calibration"))
        Text("Remember calibration", color = colors.text)
    }
    val projectorDrill = running?.host?.isProjector == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = grid,
            onCheckedChange = app::showGrid,
            enabled = arena != null && !calibrating && check != CheckState.Checking && !projectorDrill,
            modifier = Modifier.testTag("show-grid"),
        )
        Text("Show grid", color = colors.text)
    }
    if (projectorDrill) Text("Stop the drill to show the grid", color = colors.muted, fontSize = 12.sp)
}
```

- [ ] **Step 5: Wire Setup into the app, F6 and Settings' camera choice**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    fun navigate(destination: Destination) {
        if (destination.enabled) destinationState.value = destination
    }

    fun showView(view: BigView) {
```

with:

```kotlin
    fun navigate(destination: Destination) {
        if (!destination.enabled) return
        // The grid is only ever shown on Setup
        if (destination != Destination.SETUP) arenaState.value?.showGrid(false)
        destinationState.value = destination
    }

    /**
     * Setup's Show grid: the arena shows the alignment grid in place of its background, unless calibration,
     * the check or a projector drill needs the arena.
     */
    fun showGrid(show: Boolean) {
        val arena = arenaState.value ?: return
        arena.showGrid(show && gridAllowed())
    }

    /** Whether nothing else needs the arena, so the grid may show */
    fun gridAllowed(): Boolean = arenaState.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        checkState.value != CheckState.Checking &&
        runner.running.value?.host?.isProjector != true

    fun showView(view: BigView) {
```

Replace:

```kotlin
    }

    override fun showCalibratingFeed() {
        viewBeforeCalibration = viewState.value
        viewState.value = BigView.CAMERA
    }
```

with:

```kotlin
    }

    // The manual box is showing: it is dragged over Setup's camera feed
    override fun showCalibratingFeed() {
        viewBeforeCalibration = viewState.value
        viewState.value = BigView.CAMERA
        navigate(Destination.SETUP)
    }
```

Replace:

```kotlin
    /** Looks for the cameras plugged in, off the UI thread (it can take seconds), and publishes them in [cameraList]. */
    fun refreshCameras() {
        scope.launch(io) {
```

with:

```kotlin
    /** Looks for the cameras plugged in, off the UI thread (it can take seconds), and publishes them in [cameraList]. */
    /**
     * The user picked [camera] (on Setup or Settings): it opens in the background and, once open, is saved as
     * the camera to start with, as the JavaFX preferences save it.
     */
    fun pickCamera(camera: Camera) {
        openCameraInBackground(camera) { opened ->
            if (opened) {
                settings.setWebcams(listOf(camera.name), listOf(camera))
                saveSettings()
            }
        }
    }

    fun refreshCameras() {
        scope.launch(io) {
```

Replace:

```kotlin
        checkRun = run
        cameraView.frameTap = checkFrames::offer
        run.start()
    }
```

with:

```kotlin
        checkRun = run
        cameraView.frameTap = checkFrames::offer
        arena.showGrid(false)
        run.start()
    }
```

Replace:

```kotlin
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
        controller.start()
        return true
```

with:

```kotlin
        // A check under way stops first, putting the arena's background back before calibration saves it
        stopCheckQuietly()
        arenaState.value?.showGrid(false)
        controller.start()
        return true
```

Replace:

```kotlin
    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        calibratedAtState.value = wallClock()
        checkState.value = CheckState.Idle
        val camera = cameraState.value
```

with:

```kotlin
    override fun calibrationSucceeded(cameraBounds: Rect, paper: Optional<Size>) {
        calibratedAtState.value = wallClock()
        // Calibrated from Setup: back to training
        if (destinationState.value == Destination.SETUP) destinationState.value = Destination.RANGE
        checkState.value = CheckState.Idle
        val camera = cameraState.value
```

Replace:

```kotlin
     */
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        val started = runner.start(entry)
        if (started) destinationState.value = Destination.RANGE
```

with:

```kotlin
     */
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        arenaState.value?.showGrid(false)
        val started = runner.start(entry)
        if (started) destinationState.value = Destination.RANGE
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`:

Replace:

```kotlin
                when (destination) {
                    Destination.RANGE -> RangeScreen(app)
                    Destination.DRILLS -> DrillsScreen(app)
                    Destination.SETTINGS -> SettingsScreen(app)
```

with:

```kotlin
                when (destination) {
                    Destination.RANGE -> RangeScreen(app)
                    Destination.SETUP -> SetupScreen(app)
                    Destination.DRILLS -> DrillsScreen(app)
                    Destination.SETTINGS -> SettingsScreen(app)
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`:

Replace:

```kotlin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/**
```

with:

```kotlin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.compose.shell.Destination

/**
```

Replace:

```kotlin
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
    CALIBRATE(Key.F6, "Start calibrating"),
    ;
```

with:

```kotlin
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
    CALIBRATE(Key.F6, "Open Setup and start calibrating"),
    ;
```

Replace:

```kotlin
        }
        Shortcut.CLEAR_SHOTS -> clearShots()
        Shortcut.CALIBRATE -> return startCalibration()
    }
    return true
```

with:

```kotlin
        }
        Shortcut.CLEAR_SHOTS -> clearShots()
        Shortcut.CALIBRATE -> {
            navigate(Destination.SETUP)
            return startCalibration()
        }
    }
    return true
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt`:

Replace:

```kotlin
                // One camera opens at a time: the choices wait while one is opening
                Choice(camera.name, openCamera?.camera == camera, "camera-${camera.name}", enabled = opening == null) {
                    app.openCameraInBackground(camera) { opened ->
                        if (opened) {
                            app.settings.setWebcams(listOf(camera.name), listOf(camera))
                            save(app)
                        }
                    }
                }
            }
```

with:

```kotlin
                // One camera opens at a time: the choices wait while one is opening
                Choice(camera.name, openCamera?.camera == camera, "camera-${camera.name}", enabled = opening == null) {
                    app.pickCamera(camera)
                }
            }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --console=plain`

Expected: `BUILD SUCCESSFUL`. New: `TestSetupSteps` (2), `TestSetupModel` (3), `TestSetupScreen` (5), `TestArenaGrid` (1), `TestNothingBlocksViews` (1), `TestShortcuts` (+2); `TestAppRail` still passes with the extra item.

- [ ] **Step 7: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
```

Expected: `675/675 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 8: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/ProjectionOutline.kt
git add compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupModel.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocksViews.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaGrid.kt
git commit -m "Add the Setup screen: camera, projector and calibration steps, the calibration view and the grid"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 6: Range for training only; the view switch, the in-app Arena view and F2 go

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt` (whole file), `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/UiPrefs.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillCard.kt`
- Modify (comments only): `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`
- Test (new): `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt`
- Test (changed, ruling 15): `compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`, `TestAppState.kt`, `TestNothingBlocksViews.kt`, `TestScreens.kt`, `TestShortcuts.kt`, `TestUiPrefs.kt`

**Interfaces:**
- Consumes: Task 5's `setupSteps`, `calibrationSummary`, `Destination.SETUP`; Tasks 3–4's `AppState` states; Plan 5's `DrillCard`, `NoCameraPanel`, `ExerciseRunner`, `ExerciseCatalog`.
- Produces:
  - `com.shootoff.compose.app` (`RangeControls.kt`): `const val SET_UP_FIRST = "Set up the projector first"`; `@Composable StatusChip(app, modifier)` (tag `status-chip`, opens Setup); `@Composable DrillControls(app, modifier)` (tags `drill-picker`, `pick-<name>`, `drill-idle-card`, `drill-needs-setup`, `drill-start`, `drill-stop`); `@Composable NotReadyPrompt(app, modifier)` (tags `not-ready`, `prompt-setup`, `prompt-skip`)
  - Range: tag `clear-shots`; the tags `view-camera`, `view-arena`, `arena-hint`, `reset`, `open-arena`, `close-arena` and `calibrate` are gone from it
  - `DrillCard(drill, modifier, actions: @Composable () -> Unit = {})`: `actions` after the drill's buttons
  - on `AppState`: `drillChoice: StateFlow<V2ExerciseEntry?>`, `pickDrill(entry)`, `pickedDrill(entries: List<V2ExerciseEntry>): V2ExerciseEntry?`, `promptSkipped: StateFlow<Boolean>`, `skipPrompt()`, `projectorReady(): Boolean`; `startDrill` refuses a projector drill unless `projectorReady()`
  - removed: `BigView`, `AppState.view`, `AppState.showView`, `UiPrefs.view`, `Shortcut.SWITCH_VIEW`
  - test fixtures: `AppFixture.setUpForProjectorDrills(app)` (camera, arena full screen on the projector, calibrated at `Rect(100, 80, 400, 300)`)

**The screen (rulings 12–14).** The camera feed fills the view (or the no-camera panel). Over it: the status chip and Clear shots at the top left, the picker and the drill card at the top right (Start while idle, disabled with "Set up the projector first" for a projector drill until setup is done; then the drill's texts and buttons and Stop), the banners at the top, the not-ready prompt in the middle while there is a camera but the arena isn't open and calibrated, and the status strip at the bottom left. The tray is unchanged.

- [ ] **Step 1: Write the failing tests, and change the ones that used the switch**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    private val app = AppFixture.appWithCamera()

    @After
    fun close() = app.close()

    private fun showApp() = compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

    @Test
    fun theRangeIsForTrainingTheFeedWithTheChipAndClearShotsAndNoViewSwitch() {
        app.openStartCamera()
        showApp()

        compose.onNodeWithTag("camera-feed").assertExists()
        compose.onNodeWithTag("clear-shots").assertExists()
        compose.onNodeWithTag("view-camera").assertDoesNotExist()
        compose.onNodeWithTag("view-arena").assertDoesNotExist()
        compose.onNodeWithTag("arena-view").assertDoesNotExist()
        compose.onNodeWithTag("reset").assertDoesNotExist()
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
        app.feedMarkers.add(1.0, 1.0, ShotColor.RED, 4)
        showApp()

        compose.onNodeWithTag("clear-shots").performClick()

        assertTrue(app.timer.rows.value.isEmpty())
        assertTrue(app.feedMarkers.markers.value.isEmpty())
    }

    @Test
    fun thePickedDrillStartsAndStopsOnItsCard() {
        app.openStartCamera()
        showApp()
        compose.onNodeWithTag("drill-picker").assert(hasText("Feed drill"))

        compose.onNodeWithTag("drill-start").performClick()
        assertNotNull(app.runner.running.value)
        compose.onNodeWithTag("drill-picker").assertIsNotEnabled()

        compose.onNodeWithTag("drill-stop").performClick()
        assertNull(app.runner.running.value)
        compose.onNodeWithTag("drill-start").assertExists()
    }

    @Test
    fun aProjectorDrillWaitsForSetupAndThenStarts() {
        app.openStartCamera()
        showApp()

        compose.onNodeWithTag("drill-picker").performClick()
        compose.onNodeWithTag("pick-Projector drill").performClick()
        compose.onNodeWithTag("drill-start").assertIsNotEnabled()
        compose.onNodeWithText(SET_UP_FIRST).assertExists()

        AppFixture.setUpForProjectorDrills(app)
        compose.waitForIdle()

        compose.onNodeWithTag("drill-start").assertIsEnabled().performClick()
        assertEquals(AppFixture.projectorDrill, app.runner.running.value!!.entry)
    }

    @Test
    fun theNotReadyPromptOffersSetupAndSkipAndComesBackWhenTheArenaCloses() {
        app.openStartCamera()
        showApp()

        compose.onNodeWithTag("not-ready").assertExists()
        compose.onNodeWithTag("prompt-skip").performClick()
        compose.onNodeWithTag("not-ready").assertDoesNotExist()

        app.openArena()
        app.closeArena()
        compose.waitForIdle()
        compose.onNodeWithTag("not-ready").assertExists()

        compose.onNodeWithTag("prompt-setup").performClick()
        assertEquals(Destination.SETUP, app.destination.value)
    }
}
```

Rule 4:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocksViews.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shootoff.calibration.CalibrationCheck
```

with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.calibration.CalibrationCheck
```

Replace:

```kotlin
        assertFalse(app.calibration.value!!.state.value.calibrating)
    }
}
```

with:

```kotlin
        assertFalse(app.calibration.value!!.state.value.calibrating)
    }

    @Test
    fun rule4WithNoCameraAndNoProjectorEachSaysSoAndTheRestOfTheAppWorks() {
        val bare = AppFixture.app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        try {
            compose.setContent { RangeTheme(dark = true) { ShootOffApp(bare) } }

            // Range: the no-camera panel, and a drill that needs no projector can still be picked
            compose.onNodeWithTag("no-camera").assertExists()
            compose.onNodeWithTag("drill-picker").assertIsEnabled()

            compose.onNodeWithTag("rail-SETUP").performClick()
            compose.onNodeWithTag("camera-missing").assertExists()
            compose.onNodeWithTag("no-projector").assertExists()
            // The arena still opens, as a window
            compose.onNodeWithTag("open-arena").performClick()
            compose.onNodeWithTag("arena-preview").assertExists()

            compose.onNodeWithTag("rail-DRILLS").performClick()
            compose.onNodeWithText("Feed drill").assertExists()
            compose.onNodeWithTag("rail-SETTINGS").performClick()
            compose.onNodeWithText("SHOT MARKER SIZE").assertExists()
        } finally {
            bare.close()
        }
    }
}
```

A fixture that sets the arena up for projector drills:

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

Replace:

```kotlin
    }

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
```

with:

```kotlin
    }

    /**
     * Sets [app] (with a camera) up for projector drills, as the owner does on Setup: the camera, the arena
     * on the projector, and a calibration the camera found.
     */
    fun setUpForProjectorDrills(app: AppState) {
        app.openStartCamera()
        app.openArena()
        app.arena.value!!.setFullScreen(true)
        app.startCalibration()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)
    }

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
```

Projector drills need a calibrated arena now, and there is no view to switch:

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

Replace:

```kotlin
    @Test
    fun aProjectorDrillNeedsTheArenaAndStartingGoesToTheRange() {
        app.navigate(Destination.DRILLS)
        assertFalse(app.startDrill(AppFixture.projectorDrill))

        app.openArena()
        assertTrue(app.startDrill(AppFixture.projectorDrill))

        assertEquals(Destination.RANGE, app.destination.value)
        assertEquals(listOf("Pause"), waitForButtons())
    }

    @Test
    fun theArenaOpensOnTheProjectorAndClosingItStopsAProjectorDrill() {
        app.mainWindowCorner = Point(2000.0, 100.0)
        app.openArena()
        assertEquals(Rect(4480.0, 0.0, 1280.0, 720.0), app.arenaPlacement.value!!.screen)
        app.startDrill(AppFixture.projectorDrill)
        app.showView(BigView.ARENA)

        app.closeArena()

        assertNull(app.arena.value)
        assertNull(app.runner.running.value)
        assertEquals(BigView.CAMERA, app.view.value)
    }

    @Test
    fun theArenaViewNeedsAnOpenArena() {
        app.showView(BigView.ARENA)
        assertEquals(BigView.CAMERA, app.view.value)

        app.openArena()
        app.showView(BigView.ARENA)
        assertEquals(BigView.ARENA, app.view.value)
    }
```

with:

```kotlin
    @Test
    fun aProjectorDrillNeedsTheArenaOpenAndCalibrated() {
        val cameraApp = AppFixture.appWithCamera()
        try {
            assertFalse(cameraApp.startDrill(AppFixture.projectorDrill))
            cameraApp.openStartCamera()
            cameraApp.openArena()
            assertFalse(cameraApp.startDrill(AppFixture.projectorDrill))

            AppFixture.setUpForProjectorDrills(cameraApp)
            assertTrue(cameraApp.startDrill(AppFixture.projectorDrill))

            assertEquals(Destination.RANGE, cameraApp.destination.value)
            assertEquals(listOf("Pause"), waitForButtons(cameraApp))
        } finally {
            cameraApp.close()
        }
    }

    @Test
    fun theArenaOpensOnTheProjectorAndClosingItStopsAProjectorDrill() {
        val cameraApp = AppFixture.appWithCamera()
        try {
            cameraApp.mainWindowCorner = Point(2000.0, 100.0)
            AppFixture.setUpForProjectorDrills(cameraApp)
            assertEquals(Rect(4480.0, 0.0, 1280.0, 720.0), cameraApp.arenaPlacement.value!!.screen)
            assertTrue(cameraApp.startDrill(AppFixture.projectorDrill))

            cameraApp.closeArena()

            assertNull(cameraApp.arena.value)
            assertNull(cameraApp.runner.running.value)
            assertNull(cameraApp.calibratedAt.value)
        } finally {
            cameraApp.close()
        }
    }

    @Test
    fun theDrillPickedIsTheFirstUntilAnotherIsPickedWhileItIsInTheCatalog() {
        val entries = app.catalog.entries.value
        assertEquals(AppFixture.feedDrill, app.pickedDrill(entries))

        app.pickDrill(AppFixture.projectorDrill)
        assertEquals(AppFixture.projectorDrill, app.pickedDrill(entries))

        // Its jar was removed
        assertEquals(AppFixture.feedDrill, app.pickedDrill(listOf(AppFixture.feedDrill)))
    }
```

Replace:

```kotlin
    @Test
    fun closingTheArenaShutsItToNewProjectorDrillsBeforeStoppingTheRunningOne() {
        app.openArena()
        ArenaWatchingDrill.app = app
        assertTrue(app.startDrill(ArenaWatchingDrill.entry))

        app.closeArena()

        // By the time the drill is told to stop, no projector drill can start in its place
        assertEquals(false, ArenaWatchingDrill.arenaOpenAtStop)
        assertNull(app.runner.running.value)
        assertFalse(app.startDrill(AppFixture.projectorDrill))
    }
```

with:

```kotlin
    @Test
    fun closingTheArenaShutsItToNewProjectorDrillsBeforeStoppingTheRunningOne() {
        val cameraApp = AppFixture.appWithCamera()
        try {
            AppFixture.setUpForProjectorDrills(cameraApp)
            ArenaWatchingDrill.app = cameraApp
            assertTrue(cameraApp.startDrill(ArenaWatchingDrill.entry))

            cameraApp.closeArena()

            // By the time the drill is told to stop, no projector drill can start in its place
            assertEquals(false, ArenaWatchingDrill.arenaOpenAtStop)
            assertNull(cameraApp.runner.running.value)
            assertFalse(cameraApp.startDrill(AppFixture.projectorDrill))
        } finally {
            cameraApp.close()
        }
    }
```

Replace:

```kotlin
    }

    private fun waitForButtons(): List<String> {
        val deadline = System.currentTimeMillis() + 5000
        while (app.drill.buttons.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

with:

```kotlin
    }

    private fun waitForButtons(app: AppState): List<String> {
        val deadline = System.currentTimeMillis() + 5000
        while (app.drill.buttons.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt`:

Replace:

```kotlin
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { RangeTheme(dark = true) { content() } }

    @Test
    fun theSwitchOffersTheArenaOnlyOnceItIsOpen() {
        show { ShootOffApp(app) }

        compose.onNodeWithTag("view-camera").assertIsSelected()
        compose.onNodeWithTag("view-arena").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Open the arena first"))

        compose.onNodeWithTag("open-arena").performClick()
        compose.onNodeWithTag("view-arena").assertIsEnabled().performClick()

        compose.onNodeWithTag("view-arena").assertIsSelected()
        compose.onNodeWithTag("arena-view").assertExists()
    }

    @Test
    fun withoutAProjectorScreenTheSwitchSaysSo() {
        app.close()
        app = AppFixture.app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        show { ShootOffApp(app) }

        compose.onNodeWithTag("arena-hint").assertExists()
        compose.onNodeWithText("No projector screen found").assertExists()
    }

    @Test
```

with:

```kotlin
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { RangeTheme(dark = true) { content() } }

    @Test
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`:

Replace:

```kotlin
    private fun press(key: Key) = app.handleKey(key, KeyEventType.KeyDown)

    @Test
    fun f2SwitchesBetweenTheCameraAndAnOpenArena() {
        press(Key.F2)
        assertEquals(BigView.CAMERA, app.view.value)

        app.openArena()
        press(Key.F2)
        assertEquals(BigView.ARENA, app.view.value)
        press(Key.F2)
        assertEquals(BigView.CAMERA, app.view.value)
    }

    @Test
```

with:

```kotlin
    private fun press(key: Key) = app.handleKey(key, KeyEventType.KeyDown)

    @Test
```

Replace:

```kotlin
        assertFalse(app.perform(Shortcut.CALIBRATE))
        // Only a key going down counts, and only a shortcut's
        assertFalse(app.handleKey(Key.F2, KeyEventType.KeyUp))
        assertFalse(app.handleKey(Key.A, KeyEventType.KeyDown))
    }
```

with:

```kotlin
        assertFalse(app.perform(Shortcut.CALIBRATE))
        // Only a key going down counts, and only a shortcut's
        assertFalse(app.handleKey(Key.F3, KeyEventType.KeyUp))
        // F2 switched the view in Plan 5; there is no view to switch now
        assertFalse(app.handleKey(Key.F2, KeyEventType.KeyDown))
        assertFalse(app.handleKey(Key.A, KeyEventType.KeyDown))
    }
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiPrefs.kt`:

Replace:

```kotlin
        assertTrue(prefs.dark)
        assertEquals(BigView.CAMERA, prefs.view)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertFalse(prefs.trayCollapsed)
```

with:

```kotlin
        assertTrue(prefs.dark)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertFalse(prefs.trayCollapsed)
```

Replace:

```kotlin
        UiPrefs(store).apply {
            dark = false
            view = BigView.ARENA
            trayHeight = 300f
            trayCollapsed = true
```

with:

```kotlin
        UiPrefs(store).apply {
            dark = false
            trayHeight = 300f
            trayCollapsed = true
```

Replace:

```kotlin
        val next = UiPrefs(store)
        assertFalse(next.dark)
        assertEquals(BigView.ARENA, next.view)
        assertEquals(300f, next.trayHeight)
        assertTrue(next.trayCollapsed)
```

with:

```kotlin
        val next = UiPrefs(store)
        assertFalse(next.dark)
        assertEquals(300f, next.trayHeight)
        assertTrue(next.trayCollapsed)
```

Replace:

```kotlin
    @Test
    fun unreadableValuesFallBackToTheDefaults() {
        store.put("view", "SIDEWAYS")
        store.put("tray.height", "tall")
        store.put("window", "1,2,3")

        val prefs = UiPrefs(store)
        assertEquals(BigView.CAMERA, prefs.view)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertNull(prefs.window)
```

with:

```kotlin
    @Test
    fun unreadableValuesFallBackToTheDefaults() {
        store.put("tray.height", "tall")
        store.put("window", "1,2,3")

        val prefs = UiPrefs(store)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertNull(prefs.window)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --console=plain`

Expected: `compileTestKotlin` FAILS with `Unresolved reference` errors for `pickedDrill`, `pickDrill` and `SET_UP_FIRST`.

- [ ] **Step 3: Range's controls, and a slot for Stop on the drill card**

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.drill.DrillCard
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

const val SET_UP_FIRST = "Set up the projector first"

/** What the calibration is, or what is missing, on a chip that opens Setup */
@Composable
fun StatusChip(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val colors = Range.colors
    val ready = camera != null && calibrated && !calibrating

    Surface(
        onClick = { app.navigate(Destination.SETUP) },
        shape = RoundedCornerShape(16.dp),
        color = colors.background.copy(alpha = 0.87f),
        border = BorderStroke(1.dp, colors.chipBorder),
        modifier = modifier.testTag("status-chip"),
    ) {
        Text(
            calibrationSummary(camera != null, arena != null, calibrating, calibrated, calibratedAt, check),
            color = if (ready) colors.good else colors.mutedStrong,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * The drill picker and the drill's card: Start for the picked drill (a projector drill only once setup is
 * done), and while it runs its texts, its own buttons (Pause) and Stop.
 */
@Composable
fun DrillControls(app: AppState, modifier: Modifier = Modifier) {
    val entries by app.catalog.entries.collectAsState()
    val choice by app.drillChoice.collectAsState()
    val running by app.runner.running.collectAsState()
    // Whether a projector drill may start follows the camera, the arena, calibration and the check
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val check by app.check.collectAsState()
    val colors = Range.colors
    val picked = remember(entries, choice) { app.pickedDrill(entries) }

    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DrillPicker(entries.map { it.metadata().name }, picked?.metadata()?.name, enabled = running == null) { name ->
            entries.firstOrNull { it.metadata().name == name }?.let(app::pickDrill)
        }
        if (running != null) {
            DrillCard(app.drill) {
                OutlinedButton(onClick = app::stopDrill, modifier = Modifier.testTag("drill-stop")) { Text("Stop") }
            }
        } else if (picked != null) {
            // As AppState.projectorReady decides it
            val ready = !picked.isProjectorOnly || (camera != null && calibrated && !calibrating && check != CheckState.Checking)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.highlightCard.copy(alpha = 0.93f),
                border = BorderStroke(1.dp, colors.highlightBorder),
                modifier = Modifier.widthIn(min = 180.dp, max = 300.dp).testTag("drill-idle-card"),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(picked.metadata().name.uppercase(), color = colors.mutedStrong, fontSize = 10.sp, letterSpacing = 0.6.sp)
                    if (!ready) Text(SET_UP_FIRST, color = colors.warning, fontSize = 12.sp, modifier = Modifier.testTag("drill-needs-setup"))
                    Button(
                        onClick = { app.startDrill(picked) },
                        enabled = ready,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        modifier = Modifier.testTag("drill-start"),
                    ) { Text("Start") }
                }
            }
        }
    }
}

@Composable
private fun DrillPicker(names: List<String>, picked: String?, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = enabled && names.isNotEmpty(), modifier = Modifier.testTag("drill-picker")) {
            Text(picked ?: "No drills", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
            Text("  ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (name in names) {
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onPick(name)
                    },
                    modifier = Modifier.testTag("pick-$name"),
                )
            }
        }
    }
}

/**
 * Range's not-ready prompt (spec §8), over the feed while there is a camera but the arena isn't open and
 * calibrated: the setup steps and their state, Set up (opens Setup) and Skip (hides it for camera-only use
 * until the camera drops or the arena closes). With no camera, the no-camera panel says so instead.
 */
@Composable
fun NotReadyPrompt(app: AppState, modifier: Modifier = Modifier) {
    val camera by app.camera.collectAsState()
    val arena by app.arena.collectAsState()
    val controller by app.calibration.collectAsState()
    val calibrating = controller?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    val calibratedAt by app.calibratedAt.collectAsState()
    val check by app.check.collectAsState()
    val skipped by app.promptSkipped.collectAsState()
    val colors = Range.colors
    val steps = setupSteps(camera != null, arena != null, calibrated)
    if (camera == null || steps.ready || skipped) return

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = modifier.widthIn(max = 420.dp).testTag("not-ready"),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The projector isn't set up", fontSize = 18.sp, color = colors.text)
            for (step in Step.entries) {
                val state = steps[step]
                val detail = when (step) {
                    Step.CAMERA -> ""
                    Step.PROJECTOR -> if (arena == null) " — not open" else ""
                    Step.CALIBRATE -> if (state == StepState.DONE) "" else " — " + calibrationSummary(true, arena != null, calibrating, calibrated, calibratedAt, check)
                }
                Text(
                    (if (state == StepState.DONE) "✓ " else "•  ") + step.label + detail,
                    color = if (state == StepState.DONE) colors.good else colors.mutedStrong,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.navigate(Destination.SETUP) }, modifier = Modifier.testTag("prompt-setup")) { Text("Set up") }
                TextButton(onClick = app::skipPrompt, modifier = Modifier.testTag("prompt-skip")) { Text("Skip") }
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillCard.kt`:

Replace:

```kotlin
/**
 * The running drill's card, floating over the big view: its name, its texts' first lines (the first
 * one big, as the score is in the mockup) and its buttons. Nothing shows while no drill runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrillCard(drill: DrillState, modifier: Modifier = Modifier) {
    val name by drill.name.collectAsState()
    val texts by drill.texts.collectAsState()
```

with:

```kotlin
/**
 * The running drill's card, floating over the big view: its name, its texts' first lines (the first
 * one big, as the score is in the mockup), its buttons, then [actions] (Range's Stop). Nothing shows
 * while no drill runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrillCard(drill: DrillState, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val name by drill.name.collectAsState()
    val texts by drill.texts.collectAsState()
```

Replace:

```kotlin
                }
            }
        }
    }
```

with:

```kotlin
                }
            }
            actions()
        }
    }
```

- [ ] **Step 4: The Range screen**

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`: replace everything from `package` to the end of the file with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.drill.DrillSettings
import com.shootoff.compose.drill.ExerciseOverlay
import com.shootoff.compose.drill.ShotTimerTable
import com.shootoff.compose.feed.Banner
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.feed.BannerView
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.feed.FeedBanners
import com.shootoff.compose.feed.FeedStatus
import com.shootoff.compose.feed.StatusStrip
import com.shootoff.compose.shots.MarkerLayer
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay
import java.awt.Cursor

/** The big view never shrinks under this, however tall the tray is asked to be */
private const val MIN_VIEW_HEIGHT = 160f
private const val TRAY_HANDLE_HEIGHT = 8f

/**
 * The tray's height as drawn: [trayHeight] (what the user set, and what's saved), unless
 * [availableHeight] is too short to give the big view [MIN_VIEW_HEIGHT] dp above it. Layout only —
 * the stored preference is never touched by this.
 */
fun clampedTrayHeight(trayHeight: Float, availableHeight: Float): Float =
    trayHeight.coerceAtMost((availableHeight - TRAY_HANDLE_HEIGHT - MIN_VIEW_HEIGHT).coerceAtLeast(0f))

/**
 * The Range screen, for training only (spec §8): the camera feed with the status chip, Clear shots, the
 * drill picker and card (Start, the drill's own buttons, Stop), the not-ready prompt and the status strip
 * over it, and the tray with the shot timer and the drill's settings. Setup is where the camera, the
 * projector and calibration are set up.
 */
@Composable
fun RangeScreen(app: AppState, modifier: Modifier = Modifier) {
    val trayHeight by app.trayHeight.collectAsState()
    val collapsed by app.trayCollapsed.collectAsState()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val shownTrayHeight = clampedTrayHeight(trayHeight, maxHeight.value)
        Column(Modifier.fillMaxSize().padding(end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            FeedArea(app, Modifier.weight(1f).fillMaxWidth())
            // Drag the tray's edge to size it
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
                    .draggable(
                        orientation = Orientation.Vertical,
                        enabled = !collapsed,
                        state = rememberDraggableState { delta -> app.setTrayHeight(app.trayHeight.value - with(density) { delta.toDp().value }) },
                    )
                    .testTag("tray-handle"),
            )
            Tray(app, collapsed, Modifier.fillMaxWidth().animateContentSize().height(if (collapsed) 36.dp else shownTrayHeight.dp))
        }
    }
}

@Composable
private fun FeedArea(app: AppState, modifier: Modifier) {
    val running by app.runner.running.collectAsState()
    val message by app.drill.message.collectAsState()
    val camera by app.camera.collectAsState()
    val failure by app.runner.failure.collectAsState()
    val colors = Range.colors
    val projectorDrill = running?.host?.isProjector == true

    Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            if (camera == null) {
                NoCameraPanel(app)
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                    TargetLayer(app.feedTargets, transform)
                    MarkerLayer(app.feedMarkers, transform)
                    if (running != null && !projectorDrill) ExerciseOverlay(app.drill, transform)
                }
                NotReadyPrompt(app, Modifier.align(Alignment.Center))
            }

            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(app)
                FilledTonalButton(onClick = app::clearShots, modifier = Modifier.testTag("clear-shots")) { Text("Clear shots") }
            }
            DrillControls(app, Modifier.align(Alignment.TopEnd).padding(10.dp))
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                failure?.let { BannerView(Banner(-1, it, BannerKind.ERROR), onDismiss = app.runner::dismissFailure) }
                message?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
            }
            StatusLine(app, Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

@Composable
private fun StatusLine(app: AppState, modifier: Modifier) {
    val camera by app.camera.collectAsState()
    val shownFps by app.feed.fps.collectAsState()
    // Calibration's status, collected so the strip follows each change
    val arena by app.arena.collectAsState()
    val calibration by app.calibration.collectAsState()
    val calibrating = calibration?.state?.collectAsState()?.value?.calibrating == true
    val calibrated = arena?.projection?.collectAsState()?.value != null
    var cameraFps by remember { mutableStateOf(0.0) }
    // The camera's own frame rate, read once a second
    LaunchedEffect(camera) {
        while (true) {
            cameraFps = camera?.fps ?: 0.0
            delay(1000)
        }
    }
    val manager = camera ?: return
    StatusStrip(
        FeedStatus(
            app.settings.getWebcamsUserName(manager.camera).orElse(manager.name),
            cameraFps,
            shownFps,
            manager.feedWidth,
            manager.feedHeight,
            calibrationStatus(arena != null, calibrating, calibrated),
            app.settings.sessionRecorder.isPresent,
        ),
        modifier,
    )
}

/** The tray: the shot timer, and the running drill's settings. Folded, it keeps its titles. */
@Composable
private fun Tray(app: AppState, collapsed: Boolean, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrayPanel("SHOT TIMER", collapsed, Modifier.weight(1f)) { ShotTimerTable(app.timer, Modifier.fillMaxSize()) }
        TrayPanel("SETTINGS", collapsed, Modifier.width(380.dp), action = {
            TextButton(onClick = { app.setTrayCollapsed(!collapsed) }, modifier = Modifier.height(28.dp).testTag("tray-fold")) {
                Text(if (collapsed) "Show" else "Hide", fontSize = 11.sp)
            }
        }) {
            Column(Modifier.verticalScroll(rememberScrollState())) { DrillSettings(app.drill) }
        }
    }
}

@Composable
private fun TrayPanel(
    title: String,
    collapsed: Boolean,
    modifier: Modifier,
    action: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = Range.colors
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = colors.mutedStrong, fontSize = 10.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
                action()
            }
            if (!collapsed) content()
        }
    }
}
```

- [ ] **Step 5: The drill pick, the prompt and the projector guard in `AppState`; the view, F2 and its preference go**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
import kotlin.math.roundToInt

/** The Range screen's big view */
enum class BigView { CAMERA, ARENA }

const val MIN_TRAY_HEIGHT = 120f
const val MAX_TRAY_HEIGHT = 480f
```

with:

```kotlin
import kotlin.math.roundToInt

const val MIN_TRAY_HEIGHT = 120f
const val MAX_TRAY_HEIGHT = 480f
```

Replace:

```kotlin
    private var openView: OpenView? = null
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
    private val darkState = MutableStateFlow(prefs.dark)
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
```

with:

```kotlin
    private var openView: OpenView? = null
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val darkState = MutableStateFlow(prefs.dark)
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private val calibratedAtState = MutableStateFlow<LocalTime?>(null)
    private val pickedDrillState = MutableStateFlow<V2ExerciseEntry?>(null)
    private val promptSkippedState = MutableStateFlow(false)
    private val rememberState = MutableStateFlow(settings.rememberCalibration())
    private val checkState = MutableStateFlow<CheckState>(CheckState.Idle)
```

Replace:

```kotlin
    // remembered (no projector screen)
    private var currentCalibration: SavedCalibration? = null
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null
```

with:

```kotlin
    // remembered (no projector screen)
    private var currentCalibration: SavedCalibration? = null
    private var fullScreenWatch: Job? = null
```

Replace:

```kotlin
    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /** Whether calibrations are kept for the next session ("Remember calibration") */
```

with:

```kotlin
    /** When the open arena was last calibrated; null while it isn't */
    val calibratedAt: StateFlow<LocalTime?> = calibratedAtState.asStateFlow()

    /** The drill picked on Range, if the user picked one (see [pickedDrill]) */
    val drillChoice: StateFlow<V2ExerciseEntry?> = pickedDrillState.asStateFlow()

    /** Whether Range's not-ready prompt was skipped; it comes back when the camera drops or the arena closes */
    val promptSkipped: StateFlow<Boolean> = promptSkippedState.asStateFlow()

    /** Whether calibrations are kept for the next session ("Remember calibration") */
```

Replace:

```kotlin
    val destination: StateFlow<Destination> = destinationState.asStateFlow()
    val view: StateFlow<BigView> = viewState.asStateFlow()

    /** Range dark, or its light variant */
```

with:

```kotlin
    val destination: StateFlow<Destination> = destinationState.asStateFlow()

    /** Range dark, or its light variant */
```

Replace:

```kotlin
        runner.running.value?.host?.isProjector != true

    fun showView(view: BigView) {
        if (view == BigView.ARENA && arenaState.value == null) return
        viewState.value = view
        prefs.view = view
    }

    fun setDark(dark: Boolean) {
        darkState.value = dark
```

with:

```kotlin
        runner.running.value?.host?.isProjector != true

    fun setDark(dark: Boolean) {
        darkState.value = dark
```

Replace:

```kotlin
    // The manual box is showing: it is dragged over Setup's camera feed
    override fun showCalibratingFeed() {
        viewBeforeCalibration = viewState.value
        viewState.value = BigView.CAMERA
        navigate(Destination.SETUP)
    }

    override fun restoreSelectedView() {
        viewBeforeCalibration?.let { viewState.value = it }
        viewBeforeCalibration = null
    }

    // ---- The camera
```

with:

```kotlin
    // The manual box is showing: it is dragged over Setup's camera feed
    override fun showCalibratingFeed() = navigate(Destination.SETUP)

    // Calibration ending leaves the user where they are; a success from Setup goes to Range (calibrationSucceeded)
    override fun restoreSelectedView() {}

    // ---- The camera
```

Replace:

```kotlin
        if (manager.camera !== camera) return
        closeArena()
        cameraState.value = null
        cameras.clearManager(manager)
```

with:

```kotlin
        if (manager.camera !== camera) return
        closeArena()
        promptSkippedState.value = false
        cameraState.value = null
        cameras.clearManager(manager)
```

Replace:

```kotlin
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena
        // Back to the view the user last left the app on
        if (prefs.view == BigView.ARENA) viewState.value = BigView.ARENA

        cameraState.value?.let { makeCalibratable(arena, it) }
```

with:

```kotlin
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena

        cameraState.value?.let { makeCalibratable(arena, it) }
```

Replace:

```kotlin
    }

    /** The arena window closed: calibration ends, a projector drill stops, and the view goes back to the camera. */
    fun closeArena() {
        val arena = arenaState.value ?: return
```

with:

```kotlin
    }

    /** The arena window closed: calibration or a check ends, a projector drill stops, and Range asks for setup again. */
    fun closeArena() {
        val arena = arenaState.value ?: return
```

Replace:

```kotlin
        runner.stopProjectorExercise()
        placementState.value = null
        viewState.value = BigView.CAMERA
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }
```

with:

```kotlin
        runner.stopProjectorExercise()
        placementState.value = null
        promptSkippedState.value = false
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }
```

Replace:

```kotlin
    // ---- Drills

    /**
     * Starts a fresh instance of [entry]; a projector drill needs the open arena.
     *
     * @return false if it couldn't start
     */
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        arenaState.value?.showGrid(false)
        val started = runner.start(entry)
```

with:

```kotlin
    // ---- Drills

    /** Picks the drill Range's card starts */
    fun pickDrill(entry: V2ExerciseEntry) {
        pickedDrillState.value = entry
    }

    /** The drill Range's card starts: the one picked, while it is still in [entries], else the first */
    fun pickedDrill(entries: List<V2ExerciseEntry>): V2ExerciseEntry? = pickedDrillState.value?.takeIf { it in entries } ?: entries.firstOrNull()

    /** Hides Range's not-ready prompt, for camera-only use, until the camera drops or the arena closes */
    fun skipPrompt() {
        promptSkippedState.value = true
    }

    /**
     * Whether a projector drill can run: a camera, the arena open and calibrated, and neither calibration
     * nor the check under way (spec §8; Plan 5's guard, with calibration added).
     */
    fun projectorReady(): Boolean = cameraState.value != null &&
        arenaState.value?.projection?.value != null &&
        calibrationState.value?.state?.value?.calibrating != true &&
        checkState.value != CheckState.Checking

    /**
     * Starts a fresh instance of [entry]; a projector drill needs [projectorReady].
     *
     * @return false if it couldn't start
     */
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        if (entry.isProjectorOnly && !projectorReady()) return false
        arenaState.value?.showGrid(false)
        val started = runner.start(entry)
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`:

Replace:

```kotlin
 */
enum class Shortcut(val key: Key, val label: String) {
    SWITCH_VIEW(Key.F2, "Switch between the camera and the arena"),
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
```

with:

```kotlin
 */
enum class Shortcut(val key: Key, val label: String) {
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
```

Replace:

```kotlin
fun AppState.perform(shortcut: Shortcut): Boolean {
    when (shortcut) {
        Shortcut.SWITCH_VIEW -> {
            if (arena.value == null) return false
            showView(if (view.value == BigView.CAMERA) BigView.ARENA else BigView.CAMERA)
        }
        Shortcut.PAUSE_DRILL -> {
            val pause = drill.buttons.value.firstOrNull { it.label in PAUSE_LABELS } ?: return false
```

with:

```kotlin
fun AppState.perform(shortcut: Shortcut): Boolean {
    when (shortcut) {
        Shortcut.PAUSE_DRILL -> {
            val pause = drill.buttons.value.firstOrNull { it.label in PAUSE_LABELS } ?: return false
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/UiPrefs.kt`:

Replace:

```kotlin
        set(value) = store.put("theme", if (value) "dark" else "light")

    var view: BigView
        get() = store.get("view")?.let { runCatching { BigView.valueOf(it) }.getOrNull() } ?: BigView.CAMERA
        set(value) = store.put("view", value.name)

    var trayHeight: Float
        get() = store.get("tray.height")?.toFloatOrNull() ?: DEFAULT_TRAY_HEIGHT
```

with:

```kotlin
        set(value) = store.put("theme", if (value) "dark" else "light")

    var trayHeight: Float
        get() = store.get("tray.height")?.toFloatOrNull() ?: DEFAULT_TRAY_HEIGHT
```

The arena's comments no longer mention an in-app Arena view:

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`:

Replace:

```kotlin
/**
 * The projector arena: one model that both the projector window and the in-app Arena view draw, so
 * whatever an exercise does to it (a target hidden, a background set) shows on both.
 */
```

with:

```kotlin
/**
 * The projector arena: one model that both the projector window and Setup's preview draw, so
 * whatever an exercise does to it (a target hidden, a background set) shows on both.
 */
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`:

Replace:

```kotlin
}

/** The in-app Arena view: the arena fitted into the Range screen's big view. */
@Composable
fun ArenaView(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
```

with:

```kotlin
}

/** Setup's small live preview of the arena, fitted into the space it is given. */
@Composable
fun ArenaView(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
```

Check nothing else still names the switch:

```bash
cd /home/bfears/projects/ShootOFF
command grep -rn "BigView\|showView\|SWITCH_VIEW\|view-arena" compose-app/src
```

Expected: nothing.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --console=plain`

Expected: `BUILD SUCCESSFUL`. New: `TestRangeScreen` (6), `TestNothingBlocksViews.rule4…`, `TestAppState.theDrillPickedIsTheFirstUntilAnotherIsPickedWhileItIsInTheCatalog`. Gone: the two switch tests in `TestScreens`, `TestShortcuts.f2SwitchesBetweenTheCameraAndAnOpenArena`, `TestAppState.theArenaViewNeedsAnOpenArena`.

- [ ] **Step 7: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
```

Expected: `679/679 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 8: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/RangeControls.kt compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt compose-app/src/main/kotlin/com/shootoff/compose/app/UiPrefs.kt
git add compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillCard.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocksViews.kt compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiPrefs.kt
git commit -m "Make Range training only: the status chip, Clear shots, the drill picker and card, and the not-ready prompt"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 7: Drills becomes a library

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/DrillsScreen.kt` (whole file)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt` (whole file)

**Interfaces:**
- Consumes: Plan 5's `ExerciseCatalog.entries`, `V2ExerciseEntry.metadata()` and `isProjectorOnly`.
- Produces: `@Composable DrillsScreen(app, modifier)` with rows tagged `drill-<name>` and the projector label tagged `needs-arena`; `const val NEEDS_ARENA` stays; `CALIBRATING_HINT` and the tags `start-drill`, `stop-drill` and `calibrating-hint` are gone.

**The library (§8 "Removed").** Each row has the drill's name, its version and creator, its description, and "Needs the projector arena" for a projector drill whether or not the arena is open (it describes the drill, not the moment). A line under the title says drills run on the Range screen. The drill Range starts is picked there (Task 6).

- [ ] **Step 1: Change the tests**

The projector-drill test becomes the library test, and the two Start tests go (with their helper and the imports only they used):

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt`: replace everything from `package` to the end of the file with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.compose.theme.RangeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestScreens {
    @get:Rule
    val compose = createComposeRule()

    private val app = AppFixture.app()

    @After
    fun close() = app.close()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { RangeTheme(dark = true) { content() } }

    @Test
    fun drillsIsALibraryMarkingProjectorDrillsWithNoStartOrStop() {
        show { DrillsScreen(app) }

        compose.onNodeWithTag("drill-Projector drill").assertExists()
        compose.onNodeWithTag("drill-Feed drill").assertExists()
        compose.onNodeWithText(NEEDS_ARENA).assertExists()
        compose.onNodeWithTag("start-drill").assertDoesNotExist()
        compose.onNodeWithTag("stop-drill").assertDoesNotExist()

        // It says what the drill needs, not whether it is there now
        app.openArena()
        compose.waitForIdle()
        compose.onNodeWithText(NEEDS_ARENA).assertExists()
    }

    @Test
    fun theRailLeadsToDrillsAndSettings() {
        show { ShootOffApp(app) }

        compose.onNodeWithTag("rail-DRILLS").performClick()
        compose.onNodeWithText("Feed drill").assertExists()

        compose.onNodeWithTag("rail-SETTINGS").performClick()
        compose.onNodeWithText("SHOT MARKER SIZE").assertExists()
        compose.onNodeWithTag("screen-4480").performClick()

        assertEquals(4480.0, app.settings.arenaPosition.get().x, 0.0)
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestScreens' --console=plain`

Expected: `drillsIsALibraryMarkingProjectorDrillsWithNoStartOrStop` FAILS: `start-drill` exists (and `NEEDS_ARENA` is gone once the arena opens).

- [ ] **Step 3: Write the library**

`compose-app/src/main/kotlin/com/shootoff/compose/app/DrillsScreen.kt`: replace everything from `package` to the end of the file with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range
import com.shootoff.plugins.engine.V2ExerciseEntry

const val NEEDS_ARENA = "Needs the projector arena"

/**
 * The Drills screen: a library of the v2 exercises the app can run, each with its name, version, creator
 * and description, and whether it needs the projector arena. Drills start from Range's picker (spec §8).
 */
@Composable
fun DrillsScreen(app: AppState, modifier: Modifier = Modifier) {
    val entries by app.catalog.entries.collectAsState()
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Drills", fontSize = 22.sp, color = colors.text)
        Text("Pick a drill to run on the Range screen.", color = colors.muted)
        if (entries.isEmpty()) {
            Text("No drills found in the exercises folder.", color = colors.muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.exerciseClass().name }) { entry -> DrillRow(entry) }
        }
    }
}

@Composable
private fun DrillRow(entry: V2ExerciseEntry) {
    val colors = Range.colors
    val metadata = entry.metadata()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("drill-${metadata.name}"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(metadata.name, color = colors.text, fontSize = 16.sp)
            Text("${metadata.version} · ${metadata.creator}", color = colors.muted, fontSize = 12.sp)
            Text(metadata.description, color = colors.mutedStrong, fontSize = 13.sp)
            if (entry.isProjectorOnly) {
                Text(NEEDS_ARENA, color = colors.warning, fontSize = 12.sp, modifier = Modifier.testTag("needs-arena"))
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --console=plain`

Expected: `BUILD SUCCESSFUL`; `TestScreens` has 2 tests, both passing.

- [ ] **Step 5: Run the gate**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
```

Expected: `677/677 passing; 0 regressions; 0 new failures` (two Start tests removed on purpose), then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/DrillsScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt
git commit -m "Make Drills a library: drills start from the Range picker"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 8: Nothing starts at launch, and the UI thread never waits

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`launch`), `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt` (rules 1 and 6)

**Interfaces:**
- Consumes: Plan 5's `AppState.openCameraInBackground` and `CameraSource.startCamera`; `TestProblems.SlowCamera` (its `open()` blocks until `release`); Task 4's `AppFixture.appWithCamera`.
- Produces: `AppState.launch()`: finds the start camera on the I/O dispatcher (the system source may enumerate cameras) and opens it with `openCameraInBackground`, and does nothing else. `Main` calls it in place of `openStartCamera()`, which stays for tests.

**Rule 1 and rule 6 (ruling 1).** Before this task `Main` opened the camera on the main thread before the window existed, so a slow camera held the window back. After it, the window shows at once, Range shows "Opening camera …" and then the feed; the arena, calibration and the check (even with a remembered calibration) wait for the owner.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt`:

Replace:

```kotlin
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.compose.calibration.CheckState
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
```

with:

```kotlin
import com.shootoff.calibration.CalibrationCheck
import com.shootoff.compose.calibration.CheckState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.SavedCalibration
import com.shootoff.config.ScratchConfig
```

Replace:

```kotlin
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
```

with:

```kotlin
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
```

Replace:

```kotlin
/** Spec §8 "Nothing blocks": one test per rule. */
class TestNothingBlocks {
    @Test
    fun rule2CalibrationRunsOnlyWhenAskedNeverBecauseTheArenaOpened() {
```

with:

```kotlin
/** Spec §8 "Nothing blocks": one test per rule. */
class TestNothingBlocks {
    @Test
    fun rule1AtLaunchOnlyTheCameraOpensAndNothingElseStarts() {
        val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
        settings.setRememberCalibration(true)
        settings.setSavedCalibration(SavedCalibration("Test camera", Size(640.0, 480.0), Size(1280.0, 720.0), Rect(100.0, 80.0, 400.0, 300.0), Optional.empty()))
        val looked = AtomicLong()
        val app = AppFixture.appWithCamera(settings, detector = {
            looked.incrementAndGet()
            Optional.empty()
        })
        try {
            app.launch()
            awaitTrue { app.camera.value != null }

            assertEquals(Destination.RANGE, app.destination.value)
            assertNull(app.arena.value)
            assertNull(app.calibration.value)
            assertEquals(CheckState.Idle, app.check.value)
            assertEquals(0, looked.get())
        } finally {
            app.close()
        }
    }

    @Test
    fun rule2CalibrationRunsOnlyWhenAskedNeverBecauseTheArenaOpened() {
```

Replace:

```kotlin
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
```

with:

```kotlin
    }

    @Test
    fun rule6TheUiThreadNeverWaitsOnTheCamera() {
        val slow = TestProblems.SlowCamera()
        val catalog = ExerciseCatalog()
        val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), catalog, AppFixture.oneCamera(slow), { AppFixture.ownerScreens }, ManualClock(), { it.run() })
        try {
            // All of this on the calling (UI) thread while the camera's open() is stuck
            assertTimeoutPreemptively(Duration.ofSeconds(2)) {
                app.launch()
                awaitTrue { app.openingCamera.value == "Slow camera" }
                app.openArena()
                app.startCalibration()
                app.navigate(Destination.SETUP)
                app.closeArena()
            }
            assertNotSame(Thread.currentThread(), slow.openedOn)

            slow.release.countDown()
            awaitTrue { app.camera.value != null }
        } finally {
            app.close()
        }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestNothingBlocks' --console=plain`

Expected: `compileTestKotlin` FAILS with `Unresolved reference 'launch'`.

- [ ] **Step 3: Launch opens only the camera, in the background**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    // ---- The camera

    /** Opens the camera the app starts with, if there is one */
    fun openStartCamera() {
        cameraSource.startCamera(settings)?.let(::openCamera)
```

with:

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
            } catch (e: Exception) {
                logger.error("Couldn't find the camera to start with", e)
                null
            }
            if (camera != null) uiThread(Runnable { openCameraInBackground(camera) })
        }
    }

    /** Opens the camera the app starts with, if there is one, on the calling thread (for tests: the app uses [launch]) */
    fun openStartCamera() {
        cameraSource.startCamera(settings)?.let(::openCamera)
```

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`:

Replace:

```kotlin
    val app = AppState(settings, catalog, CameraSource.System, prefs = UiPrefs(PrefsStore.User()))
    Settings.setUserNotifier(app.notices)
    app.openStartCamera()

    application {
```

with:

```kotlin
    val app = AppState(settings, catalog, CameraSource.System, prefs = UiPrefs(PrefsStore.User()))
    Settings.setUserNotifier(app.notices)
    // Only the camera opens, in the background: the window shows at once (spec §8 rules 1 and 6)
    app.launch()

    application {
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestNothingBlocks' --tests 'com.shootoff.compose.app.TestNothingBlocksViews' --console=plain`

Expected: `BUILD SUCCESSFUL`, 6 tests passing: one per "Nothing blocks" rule.

- [ ] **Step 5: Run the gate, and check the boundary**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
python3 scripts/test_summary.py summarize compose-app/build/test-results/test | command grep NoJavaFx
```

Expected: `679/679 passing; 0 regressions; 0 new failures`, four `OK` lines, then two `PASS com.shootoff.compose.TestNoJavaFxInComposeApp…` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/Main.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestNothingBlocks.kt
git commit -m "Open only the camera at launch, in the background"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

---

### Task 9: The owner's hardware check, and a JavaFX regression check

Runs in ShootOFF on `compose-ui`. **Files:** none. This replaces Plan 5's Task 15 checklist; what still applies from it is folded in below.
- If a check fails, stop and debug with superpowers:systematic-debugging before changing code.
- The fix belongs in the task that owns the code, as a new commit with a test that reproduces it.
- If the owner's camera shows under about 25 FPS in item 3, apply Plan 5's ruling 17 fallback in `ComposeCameraView` (show at most 15 frames a second).

- [ ] **Step 1: The gate, the boundary and the owner's files**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan6-owner-files.sha256
./gradlew :compose-app:dependencies --configuration runtimeClasspath --console=plain | command grep -c openjfx
```

Expected:
- `679/679 passing; 0 regressions; 0 new failures`
- four `OK` lines
- `0`

- [ ] **Step 2: Launch the Compose app, and start the memory log**

With the webcam and the projector attached (the projector on), run in the background:

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :compose-app:run --console=plain > build/plan6-compose-run.log 2>&1
```

Then, in another shell, once the window is up, log the app's memory once a minute for 25 minutes (it runs in the background while the owner checks):

```bash
cd /home/bfears/projects/ShootOFF
PID=$(pgrep -f 'com.shootoff.compose.MainKt' | head -1); echo "pid $PID"
for i in $(seq 1 25); do echo "$(date +%T) $(ps -o rss= -p "$PID") kB"; sleep 60; done > build/plan6-rss.log &
```

Expected: `pid` followed by a number.

- [ ] **Step 3: The owner checks the Compose app, on the hardware** (spec §1 criteria 1–3 as revised by §8, and §8 criteria 6–8)

1. **Launch: nothing starts but the camera** (§8 rule 1; criterion 1).
   - The window shows at once in Range dark. The rail shows Range, Setup, Drills, Targets, Sessions, Settings; Targets and Sessions are greyed, "in the JavaFX app for now".
   - Range shows the camera feed (after "Opening camera …" if the camera is slow), the status chip reading "No arena", Clear shots, the drill picker with "Random Target PAR Drill with Score" and its card with Start (disabled, "Set up the projector first"), and the not-ready prompt with Set up and Skip.
   - There is no Camera | Arena switch, no Open arena or Calibrate button on Range, and F2 does nothing.
   - No arena window opens; the projector shows nothing from ShootOFF.
2. **Light and dark.** The switch at the rail's foot flips the whole app to the light slate-and-orange variant and back; Settings → Theme does the same.
3. **Frame rate.** The status strip (bottom left) reads like `… · 30 FPS · shown 30 · 640×480 · no arena`. Watch it for a minute: the camera stays near 30 FPS, and "shown" near it.
4. **Setup, top to bottom** (criteria 2 and 6). Press Set up on the prompt.
   - Setup shows three steps: Camera ✓ (the C270's name, its FPS and 640×480), Projector highlighted, Calibrate waiting; the camera feed fills the right side.
   - Projector → Open arena: the arena window opens on the projector and goes full screen; Setup's small preview shows "Needs Calibration", like the projector; Projector ✓, Calibrate highlighted.
   - Opening the arena didn't calibrate: nothing moves until Calibrate is pressed.
   - Calibrate: "Looking for the calibration pattern…" with a spinner and Cancel over the feed; the pattern shows on the projector; auto-calibration finds it; the app goes back to Range by itself, and the chip reads "✓ Calibrated HH:mm" with the time.
5. **The calibration view** (criterion 8). Open Setup from the chip.
   - The calibrated projection is outlined in orange over the camera feed.
   - Show grid: the projector shows a black arena with white grid lines and orange corner and center marks, and the preview shows the same. Judge by eye that the projected grid's edges sit on the orange outline in the feed.
   - Leave Setup (Range): the grid goes and the arena's background is back. Come back, Show grid on, then press Calibrate: the grid goes before the pattern shows.
6. **Cancel and the manual box** (§8 rule 5).
   - Calibrate, then Cancel while it looks for the pattern: calibration ends, the arena looks as before, and the chip still says calibrated (the earlier calibration was kept).
   - Calibrate and cover the camera for 12 s: the orange box appears on Setup's feed with "Drag the box over the projection, then press Done" and Cancel. Drag the box and its corners over the projection, press Done: back on Range, calibrated, and shots land where they hit.
7. **The drill, end to end, from Range** (criteria 3 and 6: without visiting Drills). Start the par drill from its card and play at least five rounds.
   - Placement: a target appears at a random place on the projector, and hides between rounds.
   - Cues and sounds: the make-ready voice, the start beep and the buzzer play.
   - Par timing and points: a hit fills Length and Score; the card shows "Score: …" big and "Round: …" below it.
   - The coral row: a round with no shot shows "Par missed!" and adds a coral-tinted row (Time, Split, "red", Length about the par time, Score 0).
   - Pause and Resume: the card's Pause button and F3 both pause and resume.
   - The summary on the arena shows the hit factor and the personal best; the card shows its first line; the shots are replayed on the summary target; a shot 4 s after the summary restarts the drill.
   - F4 and Clear shots both clear the markers and the shot timer.
   - **F6 while the drill runs** (Review Focus 5): Setup opens, the drill stops before the pattern shows, calibration succeeds, a fresh drill starts, and the app is back on Range.
   - Stop on the card stops the drill and leaves nothing behind (buttons, settings, texts, columns, target).
8. **Remember calibration** (criterion 7).
   - On Setup, tick Remember calibration. Quit the app (close the main window), relaunch with `./gradlew :compose-app:run`: nothing starts but the camera, and the chip says "No arena".
   - Setup → Open arena: the pattern shows on the projector for about a second, then the arena's background; the chip reads "✓ Calibrated HH:mm" without pressing Calibrate. Start the drill: shots land where they hit.
   - Quit, nudge the camera (or the projector) so the projection moves visibly in the feed, relaunch and open the arena: after about a second Setup (and the chip) say "The projection moved about N px — recalibrate", and the arena stays uncalibrated (Start stays disabled). Calibrate: it is saved again.
   - Relaunch with the projector off (or its lens covered), open the arena: within about 3 s it says "The pattern wasn't seen: not verified — recalibrate on Setup"; no dialog, and the rest of the app keeps working meanwhile.
   - During a check (relaunch, open the arena, and act within the first second): Cancel on Setup stops it ("Check cancelled: not verified"); unticking Remember stops it without a message.
   - Untick Remember calibration: `command grep -c "shootoff.arena.calibration" shootoff.properties` prints `0`.
9. **Missing hardware is a state** (§8 rule 4).
   - Unplug the webcam while the arena is open: Range shows "No camera" with the reason and a picker, never a frozen frame; the arena closes; the not-ready prompt comes back once the camera is picked again.
   - Unplug it during a check: the check stops, the arena closes, and nothing waits.
   - Close the arena window during calibration: calibration ends; reopen it and calibrate again normally.
   - Skip on the prompt hides it; closing the arena brings it back.
10. **The tray and remembering.** Drag the tray's top edge; Hide and Show fold it. Quit and relaunch: the window's place and size, the theme and the tray come back; the app always opens on Range.
11. **Twenty minutes.** Keep the drill running (or the app open on Range with the camera) for at least 20 minutes after launch, glancing at the status strip: the camera stays near 30 FPS and "shown" near it throughout. The Setup preview and the projector stay in step.

- [ ] **Step 4: Check the memory log and the Compose app's log**

After at least 20 minutes:

```bash
cd /home/bfears/projects/ShootOFF
cat build/plan6-rss.log
command grep -nE "Exception|Error" build/plan6-compose-run.log
```

Expected:
- 20 or more lines. After the first five minutes the RSS levels off: the last reading is within about 100 MB of the reading at minute 5, with no steady climb minute over minute.
- the log grep prints nothing. (The v1 jar's skip is one INFO line, and the Compose app logs WARN and above.)

- [ ] **Step 5: Check that the moved `TextToSpeech` still links for v1 plugins** (from Plan 5's check)

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :javafx-app:classes --console=plain -q
CP=core/build/classes/java/main:plugin-api/build/classes/java/main:javafx-app/build/classes/java/main
javap -s -p -cp "$CP" com.shootoff.plugins.TextToSpeech | command grep -A1 -E " (say|silence)\("
```

Expected: `public static void say(java.lang.String);` with `descriptor: (Ljava/lang/String;)V`, and `public static void silence(boolean);` with `descriptor: (Z)V`.

- [ ] **Step 6: A JavaFX regression check** (spec §1 criterion 4)

Quit the Compose app. With Remember calibration still ticked from item 8 (tick it again and calibrate if item 8 ended with it off), run `./gradlew run --console=plain > build/plan6-javafx-run.log 2>&1` in the background, with the webcam and the projector attached. The owner checks:
1. Only the JavaFX app starts: `command grep -c "Task :compose-app:run SKIPPED" build/plan6-javafx-run.log` prints `1`.
2. Projector → the arena goes full screen on the projector, and auto-calibration shows the pattern and succeeds, as before (the JavaFX arena still calibrates as it opens).
3. Projector → Background lists the eight bundled backgrounds, and one shows.
4. Add Pepper_Popper to the arena and shoot its plate: it falls. Press Reset: it stands up.
5. Start "Random Target PAR Drill with Score" (v2) for two rounds, then pick "None".
6. File → Preferences → change the marker size and Save. Then `command grep -c "shootoff.arena.calibration" shootoff.properties` prints at least `5`: the JavaFX save kept the Compose app's keys (ruling 2).
7. Quit, then check the log: `command grep -nE "Exception|NoClassDefFoundError|NoSuchMethodError" build/plan6-javafx-run.log` prints nothing.
8. Relaunch the Compose app and open the arena: the remembered calibration is still checked and kept.

- [ ] **Step 7: The owner's files**

```bash
cd /home/bfears/projects/ShootOFF
ls exercises/
command grep -v " shootoff.properties$" build/plan6-owner-files.sha256 | sha256sum -c
git status --short
```

Expected:
- `RandomTargetParDrill-v2.jar` and `RandomTargetParDrill.jar` listed
- three `OK` lines. `shootoff.properties` is left out: both apps save the owner's choices there (the camera, the marker size, the remembered calibration).
- `git status --short` lists only ` M shootoff.properties` (and anything the owner added).

Things this machine can't exercise: a second camera, a HiDPI screen (all three screens here are at scale 1.0), a different projector resolution (Task 4's tests cover it), and a machine with no projector at all beyond turning it off. Note them as unchecked in the report.

- [ ] **Step 8: Report**

Report to the owner: which items passed, any that failed and the fix commits, the FPS seen, the RSS at minutes 5 and 20+, and the three open decisions from the rulings (1, 5 and 6), so the owner can decide on them and on keeping Compose (spec §1 criterion 5).

---

## Spec coverage

| Spec §8 | Where |
|---|---|
| Destinations: Range, Setup, Drills, Targets, Sessions, Settings; Targets and Sessions disabled | Task 5 (`Destination.SETUP`, rail order) |
| Setup: three ordered steps, each with its state (✓ done, the next highlighted) | Task 5 (`setupSteps`, `SetupScreen`, `TestSetupSteps`, `TestSetupScreen`) |
| Camera step: pick the camera; the live feed with FPS and resolution | Task 5 (Camera step, `pickCamera`; the feed beside the steps) |
| Projector step: open or close the arena; a small live preview | Task 5 (Projector step, `ArenaView` preview) |
| Calibrate step: auto-calibration falling back to the manual box, over Setup's feed | Tasks 3, 5 (overlay on Setup's feed; the box brings the owner to Setup) |
| Setup always available on the rail | Task 5 |
| A success from Setup returns to Range | Task 5 (`TestSetupModel.aCalibrationFinishedOnSetupGoesBackToTheRange`) |
| Remember calibration, off by default; off saves nothing; on saves bounds, feed behavior, camera and projector screen | Tasks 1, 4 (rulings 2–3; `TestSavedCalibrationSettings`, `TestRememberedCalibration`) |
| Applied only with the same camera and projector resolution, else the Calibrate step asks for recalibration | Task 4 (`savedCalibrationMismatch`, Review Focus 4) |
| Stored through `core`'s `Settings`, additive keys the JavaFX app preserves; tests on `ScratchConfig` | Task 1 (`TestConfigurationKeepsComposeKeys`); Global Constraints |
| The automatic check: only when the arena opens; about a second of pattern; the existing pattern detection; 5 px; kept / moved "about 14 px" / not verified | Tasks 2, 4 (ruling 4; `TestCalibrationCheck`, `TestPatternDetector`, `TestRememberedCalibration`) |
| Calibration view: the calibrated rectangle over the feed; Show grid with lines, corners and center; only on Setup; off on leaving Setup or starting a drill | Task 5 (`ProjectionOutline`, `AlignmentGrid`, `TestArenaGrid`, `TestSetupModel`) |
| Nothing blocks, rules 1–6 | Tasks 3, 4, 5, 6, 8: one test each (see the table under the file map) |
| Rule 5: Cancel on auto-calibration and the check; the 12 s timeout to the box; Cancel leaves the arena as it was | Tasks 3, 5 (ruling 8; `TestCalibrationOnRequest`, `TestNothingBlocksViews.rule5…`) |
| Range: the feed, the drill card with Start / Pause / Stop, the picker, Clear shots, the tray, the status strip, the banners | Task 6 (ruling 12; `TestRangeScreen`) |
| The status chip opens Setup | Task 6 (`StatusChip`) |
| The not-ready prompt with Set up and Skip; back when the camera drops or the arena closes | Task 6 (ruling 13; `TestRangeScreen.theNotReadyPrompt…`) |
| A projector drill still needs an open, calibrated arena | Task 6 (`projectorReady`, `TestAppState.aProjectorDrillNeedsTheArenaOpenAndCalibrated`) |
| Removed: the switch and the in-app Arena view as a main view; F2; F6 opens Setup and calibrates; F3, F4, F11 stay; Drills as a library | Tasks 5 (F6), 6 (switch, F2, view preference), 7 (Drills) |
| Unchanged: the engine, the host and runner, the drill, the target layer, the shot pipeline, calibration's flow and overlay (plus Cancel), the theme, the JavaFX app | no task changes them beyond `CalibrationFlow.applySaved` (new, unused by JavaFX) and the JavaFX save keeping unknown keys (ruling 2) |
| Success criteria 1–5 (§1, as revised) and 6–8 | Task 9, Step 3 items 1–11, Steps 4–6 |
| Delivery: Plan 6 on `compose-ui`, then the owner's hardware check | this plan; Task 9 |
