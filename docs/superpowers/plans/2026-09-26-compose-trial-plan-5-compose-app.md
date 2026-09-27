# Compose Trial Plan 5: The Compose App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `compose-app`, a Compose Multiplatform (Desktop) ShootOFF in the owner's Range look that picks the camera, opens and calibrates the projector arena and runs the v2 RandomTargetParDrill end to end, with shipping polish; first move the last UI-neutral pieces it would otherwise copy into `core`, and leave the JavaFX app working exactly as before.

**Architecture:**
- **Task 1: the module.** Kotlin, the Compose Gradle plugins and the Compose desktop libraries join the version catalog. `compose-app` depends on `core` and `plugin-api` only. It has the Range theme (dark, and its light variant) and the rail. `./gradlew run` still starts only the JavaFX app; `./gradlew :compose-app:run` starts the Compose app.
- **Tasks 2–3: the last moves into `core`** (the Plan 4 final review's gaps 1 and 2). `git mv` puts the calibration pattern, the exposure step's white screen, the arena backgrounds and text-to-speech into `core`. `RangeReset` (what Reset does), `GifFrames` (reading an animated GIF into whole frames) and `ProjectorScreens` (which screen the arena goes on) are extracted into `core`. The JavaFX app switches onto them in the same task.
- **Tasks 4–8: the surfaces.**
  - Task 4, the camera feed (`ComposeCameraView` is core's `CameraView`; only the newest frame is kept), its banners and the status strip.
  - Task 5, the target layer, which draws a `TargetSet` from the model with image frames and animations.
  - Task 6, the shot pipeline's Compose surfaces, with one ordered shot timer (gap 3).
  - Task 7, the one arena model that the projector window and the in-app Arena view both draw.
  - Task 8, calibration on core's `CalibrationFlow`, with Compose's own texts and the manual box (gap 4).
- **Task 9: the host.** `ComposeExerciseHost` is built on `ExerciseHostSupport` and joins the `ExerciseHostContract` suite on a camera feed and on the arena. `ExerciseRunner` is the Compose app's current-exercise registry and shot routing (gap 5).
- **Tasks 10–13: the app.** Task 10, the drill panel. Task 11, the screens (Range with the Camera | Arena switch, Drills, Settings; Targets and Sessions disabled). Task 12, errors and edge cases. Task 13, polish (light/dark, animations, the sizable and foldable tray, remembered layout, HiDPI, keyboard shortcuts).
- **Task 14: the boundary.** No `compose-app` class references JavaFX, and JavaFX isn't on its class path.
- **Task 15: the owner's hardware check.** Spec §1 success criteria 1–4 in the Compose app, then a short JavaFX regression check.

**Tech Stack:**
- Java 21 (`core`, `plugin-api`, `javafx-app`) and Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- Compose Multiplatform 1.12.1 (desktop, Linux x64), with Material 3 1.9.0 and Material icons core 1.7.3.
- kotlinx.coroutines (`StateFlow`, from Compose).
- JUnit 5 for unit tests. Compose UI tests use JUnit 4 (the `ui-test-junit4` desktop kit, `v2.createComposeRule`) and run on the gate's vintage engine.
- OpenJFX 21 stays in `javafx-app` only.

**Spec:** `docs/superpowers/specs/2026-09-26-compose-trial-design.md`. This plan covers:
- §1: the owner decisions and success criteria 1–4 (criterion 5 is the owner's decision after Task 15)
- §2 "Inside `compose-app`"
- §3 and §4
- the Plan 5 parts of §5: the Compose host in the contract suite, the Compose UI tests, the boundary test and the owner hardware check
- the §6 Plan 5 bullet
- the five gaps the Plan 4 final review left for Plan 5

Plan 4 is done; the gate stands at **431**.

**Prototype.** The whole plan was built and run in a scratch clone of `compose-ui` at `4ded24d1`. Every task compiled with its tests green, in order. The full gate finished at **579/579** with the working tree's `shootoff.properties` unchanged. `./gradlew :compose-app:run` opened the Compose app on this machine: the C270 negotiated 640x480 YUYV at 30 FPS, and "Open arena" put the arena window full screen on the projector (1280x720 at x=4480). The code blocks below are the prototype's files.

## Plan-author rulings

Where the spec is silent, or the code disagrees with it, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

1. **Pinned versions.** Kotlin **2.3.21** (the Kotlin and Compose-compiler Gradle plugins), Compose Multiplatform **1.12.1** (the `org.jetbrains.compose` plugin, `desktop-jvm-linux-x64` and `ui-test-junit4`), Material 3 **1.9.0** (what Compose 1.12.1's own `compose.material3` accessor resolves) and Material icons core **1.7.3**. All were downloaded and built here with Gradle 8.14 and Java 21.
   - *Why:* Kotlin 2.4.20 also builds, but it warns on every build that Gradle 8.14 is deprecated, and Kotlin 2.5 will need 8.14.4. 2.3.21 builds without the warning.
   - The desktop artifact is the Linux x64 one, like `core`'s native libraries.
   - The plugins are declared in the root build with `apply false`, so every module shares one Kotlin plugin class loader.

   *Cost if wrong:* a version bump later; nothing else depends on these.
2. **Kotlin style and headers.**
   - The official Kotlin style: 4-space indents and trailing commas.
   - Every new main-source `.kt` file starts with the GPL header, lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` copied verbatim (it is already a block comment), then a blank line. The code blocks below start at the `package` line.
   - Test files have no header, as in the rest of the repo.
   - Java stays on tabs.

   *Cost:* none known.
3. **`./gradlew run` still starts only the JavaFX app.**
   - Gradle runs an unqualified task name in every module that has it. So `compose-app`'s `run` task has an `onlyIf`: it runs only when asked for by path (`:compose-app:run`).
   - Checked in the prototype: `./gradlew run` prints `> Task :compose-app:run SKIPPED`.

   *Cost:* `./gradlew run` never starts the Compose app, which is the spec's intent.
4. **State is `StateFlow`s of immutable snapshots, written from any thread.**
   - The host, the pipeline, the camera and calibration change state at once, under the owner's lock, in call order. Compose redraws from the snapshots.
   - There is no UI-thread queue, so a host call's change is visible when the call returns. The contract suite's `awaitUi()` is a no-op for Compose. Teardown can't race a queued change, because there is none.

   *Cost:* a whole-list copy per change; at a shot's rate that is nothing.
5. **One ordered shot-timer sink (gap 3).**
   - `ShotTimerModel` is the only row list. The pipeline's row per shot (on the shot queue's thread) and the host's `addTimerRow`, `setColumnValue` and `styleLastRow` (on the exercise's thread) all change it under one lock.
   - A value lands on the row that was latest when the exercise set it. The JavaFX host queues the same call to the FX thread, where a newer shot row can get in first.

   *Cost:* none known; `TestShotTimerModel` pins it with both writers at once.
6. **The resources and text-to-speech move with `git mv` (gap 1).** These move into `core/src/main/resources` (JavaFX finds them at the same class-path names):
   - `pattern.png`
   - `white.png`: auto-calibration's exposure step shows it, and the owner's `shootoff.arena.calibrated.exposure=true`
   - `arena/backgrounds/`

   `TextToSpeech` moves to `core` in the same package, so v1 plugins that call it still link (Task 15 checks its descriptors). It needs only MaryTTS, which `core` already has.

   *Cost:* `javafx-app`'s jar no longer contains them; the app, the distribution and `com.shootoff:shootoff`'s dependencies all bring `core`.
7. **Region commands and Reset (gap 2).**
   - *Moved to `core`, JavaFX switched:*
     - `RangeReset`: cameras and shot processors reset, then the exercise, then `disableShotDetection(1000)` with the "already off" and "calibrating" rules, operation for operation
     - `GifFrames`: the GIF decoding with disposal methods that `GifAnimation.readGif` did
   - *Compose's own:* `RegionCommandRunner` carries out `reset`, `animate`, `reverse`, `play_sound` and `poi_adjust` on the model, with `RegionAnimations` for frames. JavaFX's `TargetCommands` and sprite animations act on v1 nodes, which v1 plugins can see, so they stay.
   - Compose follows JavaFX's rules:
     - `animate()` and `animate(true)` animate the hit region; `animate(name)` animates the named region of the hit target, and only from its first frame
     - `reverse` waits for a running play to finish
     - `play_sound(file, name)` stays quiet once that image has fallen
     - a play takes one cycle, which is the first frame's delay or 100 ms
   - Two differences, both in cases the JavaFX app gets wrong:
     - An "@" sound comes from the running exercise's jar by resource name. JavaFX looks it up next to the running v1 exercise's class, so a v2 drill's "@" sounds never play there.
     - `poi_adjust` maps the region's center through the calibrated projection only on the arena. On a camera feed the canvas point is used as is; JavaFX would put a feed hit through the arena's mapping.

   *Cost:* two command implementations until JavaFX retires; `TestRegionCommandRunner` pins Compose's.
8. **The projector screen choice moves to `core` (`ProjectorScreens`), JavaFX switched.**
   - The rule stays the same:
     1. the saved arena position if it is on another screen
     2. else, with two screens, the one ShootOFF isn't on
     3. else, with more than two, the first smallest
   - The owner's three screens pick HDMI-1, 1280x720 at x=4480.
   - Only debug logging changes: the "Stored arena coordinates are on current home screen" and "Saved screen coordinates … no longer exists" lines are gone, and the two- and three-screen lines log before the saved-position check.
   - *Compose's window:* it opens 10 px into that screen, waits (up to 3 s) until AWT reports it there, then goes full screen. In the prototype, going full screen at once filled the main screen instead.

   *Cost:* none known.
9. **Calibration in Compose (gap 4).**
   - Compose writes its own message texts. It plays no reminder chimes, because the overlay is on screen.
   - It calls `setFullScreen` whenever the arena window's placement changes (the app watches `ArenaModel.fullScreen`), as JavaFX's F11 handler does.
   - The manual box starts at JavaFX's default: 150 px square, 75 px in. It is dragged by its body and resized by its corners. It follows the pointer with no touch slop.
   - Opening the arena starts calibrating, as in JavaFX.

   *Cost:* no chime reminders on a long calibration.
10. **The current exercise and shot routing are `ExerciseRunner`'s (gap 5).**
    - One exercise at a time. Starting one stops the running one first.
    - Arena shots reach only a projector exercise; camera shots reach only a camera exercise.
    - Calibration stops a projector exercise and starts a fresh instance afterwards.
    - Closing the arena stops a projector exercise, because its surface is gone. JavaFX leaves it running headless.

    *Cost:* a drill can't outlive its arena window; nothing in the trial needs it to.
11. **An exercise that throws is stopped.**
    - The host wraps every callback it runs for the exercise (start, shots, reset, target changes, buttons, settings, scheduled tasks, listeners).
    - A throw is reported to the runner, which stops the drill off the exercise's thread and shows "‹name› stopped: ‹Exception›: ‹message›" until dismissed. The executor still logs the exception.
    - `ExerciseHostSupport` and `ExerciseExecutor` are unchanged, and the exercise is never wrapped. A wrapper would change `dataDirectory()`, which is named after the exercise's class: the owner's personal bests live there.

    *Cost:* none known.
12. **Only v2 exercises.**
    - The Compose app's plugin engine gets only `V2ExerciseLoader`.
    - The owner's v1 `RandomTargetParDrill.jar` is skipped with core's logged "Error creating new plugin … needs plugin API version 1"; the v2 jar beside it registers. This matches 1a's "broken or mismatched jars are skipped".

    *Cost:* one ERROR line in the log at start.
13. **One camera at a time; Settings writes `shootoff.properties`.**
    - The owner has one camera, so the Camera | Arena switch has one camera segment. Choosing a camera writes `shootoff.webcams` (as the JavaFX preferences do).
    - Marker size writes `shootoff.markerradius`. The arena display is saved as the arena position (`shootoff.arena.x/y`), the way JavaFX saves an arena the user placed by hand.
    - Switching or losing the camera closes the arena it calibrated, as the JavaFX arena closes with its calibrating camera.
    - Look and layout go in the user's Java preferences (`com/shootoff/compose`), never in `shootoff.properties`, whose writer drops keys it doesn't know. These are the theme, the last view, the tray's height and fold, and the window's place and size. Tests use an in-memory store.

    *Cost:* no multi-camera view in the trial.
14. **The drill card is a summary.**
    - The exercise's texts are drawn on its surface, as in JavaFX. The card also shows the first line of each text, the first one big, as the mockup's score is, plus the drill's name and buttons (the first filled, the rest tonal).
    - Number settings and the shared par time and start delay are in the tray's Settings panel. A typed value counts on Enter or when the field loses focus; − and + step it.

    *Cost:* a long multi-line summary shows its first line on the card, and the whole summary on the arena.
15. **The Arena segment is enabled only while the arena window is open.**
    - Its hint says "Open the arena first", or "No projector screen found" when no screen looks like one.
    - "Open arena" works without a projector: the arena opens as a window on ShootOFF's screen, as in JavaFX.
    - Projector drills show "Needs the projector arena" and can't start until the arena is open.

    *Cost:* none known.
16. **Keyboard shortcuts are function keys**, so they never clash with typing in a settings field:
    - F2 switches the view
    - F3 presses the drill's Pause or Resume button
    - F4 clears the shots
    - F6 starts or stops calibrating
    - in the arena window, F11 toggles full screen (as in JavaFX), and the other keys reach the app

    *Cost:* F3 only works for a drill whose pause button is labeled Pause or Resume, as the owner's is.
17. **Camera frames are converted on the camera's thread, with no rate cap.**
    - Only the newest frame is kept.
    - The status strip shows the camera's FPS and the frames shown in the last second.
    - A 640x480 conversion measured 5.7 ms here, about 17% of a 30 FPS frame.

    *Cost:* if the owner's camera FPS drops below about 25 in Task 15, cap the shown frames at 15 FPS as JavaFX does (`MINIMUM_FRAME_DELTA`) in `ComposeCameraView.updateBackground`.
18. **HiDPI.** Arena coordinates are the arena window's size in dp; the feed's canvas is the display size. Views draw through `SurfaceTransform` in pixels, so every density shows the same share of the view. `TestPolish` checks density 2.

    *Cost:* none known.
19. **Tests never touch the owner's settings or preferences.** Every `Settings` a `compose-app` test builds is on `ScratchConfig.emptyFile()`. `TestRegionCommandRunner` (the only test that writes a POI adjustment) also checks the working tree's fingerprint afterwards. `UiPrefs` in tests is `PrefsStore.Memory`.

    *Cost:* none known.

**Changes to v1 or JavaFX behavior: none.**
- Changes inside `javafx-app`:
  - `GifAnimation.readGif` builds its frames from `GifFrames`: the same algorithm, the same frames
  - `ShootOFFController.reset()` and `disableShotDetection(int)` delegate to `RangeReset`: the same order and rules; the "did not re-enable" info line now logs under `RangeReset`
  - `ProjectorArenaPane.autoPlaceArena()` asks `ProjectorScreens`: the same choice and placement; debug lines differ as in ruling 8
  - four images, the backgrounds folder and `TextToSpeech` moved to `core`: the same class-path names and class name
- No public or protected JavaFX member changes. No existing test changes.

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never commit to `master`, never push.
- No `Co-Authored-By` trailer in commit messages. Verify after every commit with `git log -1 --format=%B`.
- Never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- Use `git mv` for every move so history follows (Task 2). Never delete and re-create a moved file.
- **Code style:**
  - Java: tabs; match the surrounding code. A new main-source Java file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file), then a blank line.
  - Kotlin: the official style (4 spaces, trailing commas). A new main-source `.kt` file starts with the same 17 lines, then a blank line (ruling 2).
  - Test files have no header. The code blocks below start at the `package` line.
- **Packages:** `com.shootoff.*`. `compose-app` is `com.shootoff.compose` and its subpackages `theme`, `shell`, `surface`, `feed`, `targets`, `shots`, `arena`, `calibration`, `drill` and `app`.
- **Dependencies:**
  - `core`, `plugin-api` and `javafx-app` gain no dependencies.
  - `compose-app` depends on `core`, `plugin-api` and exactly the catalog entries of Task 1: Kotlin 2.3.21, Compose Multiplatform 1.12.1, Material 3 1.9.0 and Material icons core 1.7.3. Its tests also use `ui-test-junit4` 1.12.1 and the `core` and `plugin-api` test fixtures.
  - No OpenJFX in `compose-app` (Task 14's `TestNoJavaFxInComposeApp`). `TestNoJavaFxInCore` and `TestNoJavaFxInPluginApi` keep passing.
- **Unchanged formats:** `.target`, `.course`, the session formats (XML/JSON), `shootoff.properties`, and `shootoff.xml` descriptors.
- Existing test classes and methods keep their package, class, method names and bodies. This plan changes no existing test.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- `javafx-app/src/test/resources/targets/hit-parity.txt` is never hand-edited.
- **The owner's files.** Never modify, move or delete:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - `exercises/RandomTargetParDrill.jar` and `exercises/RandomTargetParDrill-v2.jar`
  - anything under `exercise-data/`
  - any file the owner added under `targets/` or `courses/`
  - the owner's Java preferences
  No test runs the drill.
- **No test reads or writes the working tree's `shootoff.properties`** (ruling 19). Every `Settings` a test builds is on `ScratchConfig.emptyFile()`.
- **The JavaFX app behaves exactly as before** (see "Changes to v1 or JavaFX behavior"). `./gradlew run` starts only the JavaFX app; `./gradlew :compose-app:run` starts the Compose app. Both use the repository root as the working directory.
- **Publishing:** only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`.
- **Test gate** (unchanged from Plans 1–4; run it in ShootOFF):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. Use a Bash timeout of 600000 ms.
  - The `N/M passing` count starts at **431** (end of Plan 4). Each task gives the count it ends at, the previous count plus the test methods it adds. A lower count means tests silently stopped running.
  - The Compose UI tests need a display, as the JavaFX tests do. The gate runs them on this machine's `DISPLAY=:0`.
  - The gate leaves `shootoff.properties` unchanged.
- **v1 plugins keep loading and running in the JavaFX app.** Plan 3's v1 surface is unchanged; Task 15 Step 2 checks `TextToSpeech`'s descriptors in `core`.

## Review Focus

1. **Calibrating while the drill runs.** Expected: the drill stops before the pattern shows, the pattern is saved over nothing of the drill's, and after calibration the background comes back and a fresh drill starts, in that order. If the order slips, the drill's teardown overwrites the pattern and auto-calibration fails.
   - *Tests:* `TestCalibrationController.aRunningProjectorDrillStopsFirstAndStartsAgainAfter` (Task 8)
   - *Tests:* `TestExerciseRunner.calibrationStopsAProjectorDrillAndStartsItAfresh` (Task 9)
2. **The drill sets a column the moment the next shot's row arrives** (a hit's Length and Score while another shot is detected). Expected: the value lands on the row that was latest when the drill set it; no row is lost or mis-split.
   - *Tests:* `TestShotTimerModel.theShotQueueAndTheExerciseWritingAtOnceKeepOneOrder`, 500 rows from each thread at once (Task 6)
3. **The arena window changes size after calibration** (it leaves full screen, or the owner drags it). Expected: a shot inside the projection lands at its point in the arena's current size; the pipeline reads the size per shot.
   - *Tests:* `TestArenaModel.aShotAfterTheArenaChangedSizeIsScaledToItsNewSize` (Task 7)
4. **Start pressed twice, or another drill started while one runs.** Expected: one drill runs; the first is stopped once and leaves no buttons, settings, texts, columns or targets behind.
   - *Tests:* `TestExerciseRunner.startingTheDrillAgainStopsTheRunningOneFirst` (Task 9)
5. **The webcam is unplugged mid-session.** Expected: the feed never freezes on its last frame. It shows "No camera" with the reason and a picker, and the arena the camera calibrated closes.
   - *Tests:* `TestProblems.aCameraThatStopsAnsweringIsClosedAndItsLastFrameGoes` and `losingTheCameraClosesTheArenaItCalibrated` (Task 12)

The owner's check (Task 15) covers what no unit test reaches: the webcam and the projector, real calibration, sounds and speech, and the drill played through.

## Module and file map

| Where | What | Task |
|---|---|---|
| `gradle/libs.versions.toml`, `settings.gradle.kts`, `build.gradle.kts`; `compose-app/build.gradle.kts`, `compose-app/src/main/resources/logback.xml`; `…/compose/Main.kt`, `theme/RangeTheme.kt`, `shell/Rail.kt` | the module, the theme, the rail | 1 |
| `core/src/main/resources/{pattern.png,white.png,arena/backgrounds/}` and `core/…/plugins/TextToSpeech.java` (all `git mv` from `javafx-app`) | shared images and speech | 2 |
| `core/…/shots/RangeReset.java`, `geom/ProjectorScreens.java`, `targets/model/GifFrames.java`; `javafx-app/…/gui/controller/ShootOFFController.java`, `gui/pane/ProjectorArenaPane.java`, `targets/animation/GifAnimation.java` | Reset, screen choice and GIF frames in `core` | 3 |
| `…/compose/surface/SurfaceTransform.kt`; `feed/{FeedState,ComposeCameraView,CameraFeedView,FeedBanners,StatusStrip}.kt` | the camera feed | 4 |
| `…/compose/targets/{RegionLook,RegionImages,RegionAnimations,SurfaceTargets,TargetLayer}.kt` | the target layer | 5 |
| `…/compose/shots/{ShotTimerModel,ShotMarkers,ArenaPointShot,ShotReceiver,RegionCommandRunner,Surfaces,MarkerLayer}.kt` | pipeline surfaces, the one timer | 6 |
| `…/compose/arena/{ArenaModel,ArenaScreens,ArenaCanvas,ArenaWindow}.kt` | one arena, two views | 7 |
| `…/compose/calibration/{CalibrationController,CalibrationOverlay}.kt` | calibration | 8 |
| `…/compose/drill/{DrillState,HostSurface,SoundOutput,ComposeExerciseHost,ExerciseRunner}.kt` | the host and the runner | 9 |
| `…/compose/drill/{WebColors,DrillCard,ExerciseTexts,ShotTimerTable,DrillSettings}.kt` | the drill panel | 10 |
| `…/compose/app/{ExerciseCatalog,CameraSource,AppState,RangeScreen,DrillsScreen,SettingsScreen,ShootOffApp}.kt`; `Main.kt` | screens and wiring | 11 |
| `…/compose/app/{CameraProblems,NoCameraPanel,Notices}.kt`; `AppState.kt`, `RangeScreen.kt`, `ShootOffApp.kt`, `Main.kt` | errors and edge cases | 12 |
| `…/compose/app/{UiPrefs,Shortcuts}.kt`; `AppState.kt`, `RangeScreen.kt`, `ShootOffApp.kt`, `SettingsScreen.kt`, `Main.kt`, `shell/Rail.kt`, `feed/FeedBanners.kt`, `arena/ArenaWindow.kt` | polish | 13 |
| `compose-app/src/test/…/compose/TestNoJavaFxInComposeApp.kt` | the boundary | 14 |
| none | owner check | 15 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`, and `javafx-app/…/` is `javafx-app/src/main/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | New test methods | Gate |
|---|---|---|
| 1 | `theme/TestRangeTheme` (3), `shell/TestAppRail` (2) | 436 |
| 2 | `core` `TestBundledImages` (2) | 438 |
| 3 | `core` `shots/TestRangeReset` (4), `geom/TestProjectorScreens` (8), `targets/model/TestGifFrames` (2) | 452 |
| 4 | `surface/TestSurfaceTransform` (2), `feed/TestFeedState` (4), `feed/TestComposeCameraView` (5), `feed/TestFeedViews` (3) | 466 |
| 5 | `targets/TestRegionLook` (3), `TestRegionAnimations` (5), `TestSurfaceTargets` (3), `TestTargetLayer` (5) | 482 |
| 6 | `shots/TestShotTimerModel` (5), `TestSurfaces` (6), `TestRegionCommandRunner` (5) | 498 |
| 7 | `arena/TestArenaModel` (5), `TestArenaScreens` (4), `TestArenaViews` (3) | 510 |
| 8 | `calibration/TestCalibrationController` (6), `TestCalibrationOverlay` (2) | 518 |
| 9 | `drill/TestComposeExerciseHostContract` (8), `TestComposeExerciseHostContractOnArena` (8), `TestExerciseRunner` (6) | 540 |
| 10 | `drill/TestWebColors` (1), `TestDrillPanel` (6) | 547 |
| 11 | `app/TestAppState` (7), `TestScreens` (4) | 558 |
| 12 | `app/TestProblems` (5), `TestProblemViews` (3) | 566 |
| 13 | `app/TestUiPrefs` (3), `TestShortcuts` (4), `TestPolish` (4) | 577 |
| 14 | `TestNoJavaFxInComposeApp` (2) | 579 |

---
### Task 1: The `compose-app` module, the Range theme and the rail

**Files:**
- Modify: `gradle/libs.versions.toml` (the Kotlin and Compose versions, libraries and plugins)
- Modify: `settings.gradle.kts` (include `compose-app`)
- Modify: `build.gradle.kts` (declare the three plugins `apply false`)
- Create: `compose-app/build.gradle.kts`, `compose-app/src/main/resources/logback.xml`
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/theme/RangeTheme.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/theme/TestRangeTheme.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/shell/TestAppRail.kt`

**Interfaces:**
- Consumes: nothing new; `compose-app` depends on `project(":core")` and `project(":plugin-api")`.
- Produces:
  - `com.shootoff.compose.theme`:
    - `data class RangeColors(background, rail, accent, onAccent, accentSoft, onAccentSoft, card, cardBorder, highlightCard, highlightBorder, bigNumber, text, muted, mutedStrong, chipBorder, good, warning, error, feedCenter, feedEdge: Color, isDark: Boolean)`
    - `val RangeDark`, `val RangeLight`, `val LocalRangeColors`, `val NumberStyle: TextStyle`
    - `fun RangeColors.colorScheme(): ColorScheme`
    - `@Composable fun RangeTheme(dark: Boolean, content: @Composable () -> Unit)`
    - `object Range { val colors: RangeColors (composable getter); val bigNumber: TextStyle }`
  - `com.shootoff.compose.shell`:
    - `enum class Destination(label, icon, enabled) { RANGE, TARGETS, DRILLS, SESSIONS, SETTINGS }`, with TARGETS and SESSIONS disabled
    - `const val DISABLED_HINT = "In the JavaFX app for now"`
    - `@Composable fun AppRail(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier)`. Its items are test-tagged `rail-<NAME>`.

The theme's dark colors are the owner's pick, taken exactly from the mockup's `.b` CSS (`.superpowers/brainstorm/156968-1790456357/content/theme.html`, option B). The light variant keeps the slate and orange. Its accent is darkened to `#B85518`, so white on it passes 4.5:1. `TestRangeTheme` checks the contrast of both variants.

- [ ] **Step 0: Record the owner's files**

```bash
cd /home/bfears/projects/ShootOFF
sha256sum RandomTargetParDrill-bests.properties exercises/RandomTargetParDrill.jar exercises/RandomTargetParDrill-v2.jar shootoff.properties > build/plan5-owner-files.sha256
cat build/plan5-owner-files.sha256
```

Expected: four checksum lines. No gate run of this plan changes any of them. Task 15 checks the first three after the owner has used the apps.

- [ ] **Step 1: Add the versions, the module and the root plugins**

`gradle/libs.versions.toml`:

Replace:

```toml
slf4j = "2.0.20"
junit = "5.14.4"

[libraries]
```

with:

```toml
slf4j = "2.0.20"
junit = "5.14.4"
# compose-app only (Plan 5): Kotlin, Compose Multiplatform desktop and its Material 3
kotlin = "2.3.21"
compose-multiplatform = "1.12.1"
compose-material3 = "1.9.0"
compose-material-icons = "1.7.3"

[libraries]
```

Replace:

```toml
junit4 = "junit:junit:4.13.2"
hamcrest-core = "org.hamcrest:hamcrest-core:1.3"

[bundles]
```

with:

```toml
junit4 = "junit:junit:4.13.2"
hamcrest-core = "org.hamcrest:hamcrest-core:1.3"
compose-desktop-linux-x64 = { module = "org.jetbrains.compose.desktop:desktop-jvm-linux-x64", version.ref = "compose-multiplatform" }
compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "compose-material3" }
compose-material-icons-core = { module = "org.jetbrains.compose.material:material-icons-core", version.ref = "compose-material-icons" }
compose-ui-test-junit4 = { module = "org.jetbrains.compose.ui:ui-test-junit4", version.ref = "compose-multiplatform" }

[bundles]
```

Replace:

```toml
[plugins]
javafx = { id = "org.openjfx.javafxplugin", version = "0.1.0" }
```

with:

```toml
[plugins]
javafx = { id = "org.openjfx.javafxplugin", version = "0.1.0" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
```

`settings.gradle.kts`:

Replace:

```kotlin
rootProject.name = "shootoff"

include("core", "plugin-api", "javafx-app")
```

with:

```kotlin
rootProject.name = "shootoff"

include("core", "plugin-api", "javafx-app", "compose-app")
```

`build.gradle.kts`:

Replace:

```kotlin
plugins {
    alias(libs.plugins.javafx) apply false
}
```

with:

```kotlin
plugins {
    alias(libs.plugins.javafx) apply false
    // compose-app only; declared here so every module shares one Kotlin plugin class loader
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
```

Replace:

```kotlin
// There is no code in the root project. `./gradlew run`, `installDist` and `test` still work from
// here: Gradle runs a task name given on the command line in every module that has it, and only
// :javafx-app has run/installDist.
subprojects {
    apply(plugin = "java")
```

with:

```kotlin
// There is no code in the root project. `./gradlew run`, `installDist` and `test` still work from
// here: Gradle runs a task name given on the command line in every module that has it, and only
// :javafx-app has run/installDist. compose-app's run only runs when asked for by path
// (./gradlew :compose-app:run), so ./gradlew run still starts just the JavaFX app.
subprojects {
    apply(plugin = "java")
```

- [ ] **Step 2: Write the module's build and its logging**

`compose-app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

repositories {
    // Compose's AndroidX dependencies (collection, lifecycle, annotation) are published by Google
    google()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))

    // Linux x64 only, like core's native libraries
    implementation(libs.compose.desktop.linux.x64)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.compose.ui.test.junit4)
    // ExerciseHostContract, which ComposeExerciseHost must pass
    testImplementation(testFixtures(project(":plugin-api")))
    // JavaFxReferenceScanner, MockCamera and ScratchConfig
    testImplementation(testFixtures(project(":core")))
}

compose.desktop {
    application {
        mainClass = "com.shootoff.compose.MainKt"
    }
}

// The Compose plugin registers run once the project is evaluated
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    // The Compose app shares targets/, sounds/, exercises/, exercise-data/ and shootoff.properties with
    // the JavaFX app, resolved against the working directory
    workingDir = rootDir

    // `./gradlew run` runs every module's run task; only run this one when asked for by path
    val requested = gradle.startParameter.taskNames
    onlyIf("./gradlew run starts the JavaFX app; use ./gradlew :compose-app:run") {
        requested.any { it == ":compose-app:run" || it == "compose-app:run" }
    }
}
```


`compose-app/src/main/resources/logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <logger name="com.shootoff.camera.cameratypes.SarxosCaptureCamera" level="INFO"/>

  <root level="warn">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```


Run: `./gradlew :compose-app:dependencies --configuration runtimeClasspath --console=plain | command grep -E "^[+\\]---"`

Expected, among others: `org.jetbrains.compose.desktop:desktop-jvm-linux-x64:1.12.1`, `org.jetbrains.compose.material3:material3:1.9.0` and `org.jetbrains.compose.material:material-icons-core:1.7.3` (the first download takes a minute). No `org.openjfx` line anywhere in the output.

- [ ] **Step 3: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/theme/TestRangeTheme.kt`:

```kotlin
package com.shootoff.compose.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestRangeTheme {
    @Test
    fun darkIsTheOwnersPickFromTheMockup() {
        assertEquals(Color(0xFF12161A), RangeDark.background)
        assertEquals(Color(0xFF171C21), RangeDark.rail)
        assertEquals(Color(0xFFF08A4B), RangeDark.accent)
        assertEquals(Color(0xFF3A261B), RangeDark.accentSoft)
        assertEquals(Color(0xFFF5B38B), RangeDark.onAccentSoft)
        assertEquals(Color(0xFF1A2026), RangeDark.card)
        assertEquals(Color(0xFF242C33), RangeDark.cardBorder)
        assertEquals(Color(0xFF241A14), RangeDark.highlightCard)
        assertEquals(Color(0xFF5A3520), RangeDark.highlightBorder)
        assertEquals(Color(0xFFF5A26B), RangeDark.bigNumber)
        assertEquals(Color(0xFF8D99A3), RangeDark.muted)
        assertEquals(Color(0xFFAAB5BD), RangeDark.mutedStrong)
        assertEquals(Color(0xFF6CC592), RangeDark.good)
    }

    @Test
    fun bothVariantsKeepTheirTextReadable() {
        for (colors in listOf(RangeDark, RangeLight)) {
            assertTrue(contrast(colors.text, colors.background) >= 7.0, "text on ${colors.isDark}")
            assertTrue(contrast(colors.onAccent, colors.accent) >= 4.5, "button text on ${colors.isDark}")
            assertTrue(contrast(colors.onAccentSoft, colors.accentSoft) >= 4.5, "selection on ${colors.isDark}")
            assertTrue(contrast(colors.bigNumber, colors.highlightCard) >= 4.5, "numbers on ${colors.isDark}")
            assertTrue(contrast(colors.muted, colors.card) >= 4.5, "muted text on ${colors.isDark}")
        }
    }

    @Test
    fun theColorSchemeCarriesTheAccent() {
        assertEquals(RangeDark.accent, RangeDark.colorScheme().primary)
        assertEquals(RangeLight.accent, RangeLight.colorScheme().primary)
        assertEquals(RangeLight.background, RangeLight.colorScheme().background)
    }

    // WCAG contrast ratio
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}
```


`compose-app/src/test/kotlin/com/shootoff/compose/shell/TestAppRail.kt`:

```kotlin
package com.shootoff.compose.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.shootoff.compose.theme.RangeTheme
import org.junit.Rule
import org.junit.Test

class TestAppRail {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun targetsAndSessionsAreShownButDisabled() {
        compose.setContent { RangeTheme(dark = true) { AppRail(Destination.RANGE, {}) } }

        compose.onNodeWithTag("rail-RANGE").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("rail-DRILLS").assertIsEnabled()
        compose.onNodeWithTag("rail-SETTINGS").assertIsEnabled()
        compose.onNodeWithTag("rail-TARGETS").assertIsNotEnabled()
        compose.onNodeWithTag("rail-SESSIONS").assertIsNotEnabled()
    }

    @Test
    fun choosingADestinationSelectsIt() {
        compose.setContent {
            var selected by remember { mutableStateOf(Destination.RANGE) }
            RangeTheme(dark = false) { AppRail(selected, { selected = it }) }
        }

        compose.onNodeWithTag("rail-DRILLS").performClick()
        compose.onNodeWithTag("rail-DRILLS").assertIsSelected()
    }
}
```


- [ ] **Step 4: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.theme.*' --tests 'com.shootoff.compose.shell.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'RangeDark'` (and `'AppRail'`), then `BUILD FAILED`.

- [ ] **Step 5: Write the theme, the rail and a first window**

Each file starts with the GPL header (ruling 2).

`compose-app/src/main/kotlin/com/shootoff/compose/theme/RangeTheme.kt`:

```kotlin
package com.shootoff.compose.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * The Range look: slate with an orange accent and high-contrast numbers. [RangeDark] is the owner's
 * pick (the design session's theme.html option B); [RangeLight] is its light variant.
 */
@Immutable
data class RangeColors(
    val background: Color,
    val rail: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val card: Color,
    val cardBorder: Color,
    val highlightCard: Color,
    val highlightBorder: Color,
    val bigNumber: Color,
    val text: Color,
    val muted: Color,
    val mutedStrong: Color,
    val chipBorder: Color,
    val good: Color,
    val warning: Color,
    val error: Color,
    val feedCenter: Color,
    val feedEdge: Color,
    val isDark: Boolean,
)

val RangeDark = RangeColors(
    background = Color(0xFF12161A),
    rail = Color(0xFF171C21),
    accent = Color(0xFFF08A4B),
    onAccent = Color(0xFF1A0D05),
    accentSoft = Color(0xFF3A261B),
    onAccentSoft = Color(0xFFF5B38B),
    card = Color(0xFF1A2026),
    cardBorder = Color(0xFF242C33),
    highlightCard = Color(0xFF241A14),
    highlightBorder = Color(0xFF5A3520),
    bigNumber = Color(0xFFF5A26B),
    text = Color(0xFFE8ECEF),
    muted = Color(0xFF8D99A3),
    mutedStrong = Color(0xFFAAB5BD),
    chipBorder = Color(0xFF2C343B),
    good = Color(0xFF6CC592),
    warning = Color(0xFFE5C07B),
    error = Color(0xFFEF6F6C),
    feedCenter = Color(0xFF2A3036),
    feedEdge = Color(0xFF0B0D0F),
    isDark = true,
)

// The same slate and orange on a light ground: the accent is darkened so text on it and next to it
// keeps its contrast on white
val RangeLight = RangeColors(
    background = Color(0xFFF3F5F7),
    rail = Color(0xFFE6EBEF),
    accent = Color(0xFFB85518),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFFBE2D2),
    onAccentSoft = Color(0xFF7A3510),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFD4DBE1),
    highlightCard = Color(0xFFFFF1E6),
    highlightBorder = Color(0xFFF0B58E),
    bigNumber = Color(0xFFB4501A),
    text = Color(0xFF1B2126),
    muted = Color(0xFF5C6873),
    mutedStrong = Color(0xFF45515B),
    chipBorder = Color(0xFFC7CFD6),
    good = Color(0xFF2E8B57),
    warning = Color(0xFF9A6B00),
    error = Color(0xFFC0392B),
    feedCenter = Color(0xFF2A3036),
    feedEdge = Color(0xFF0B0D0F),
    isDark = false,
)

val LocalRangeColors = staticCompositionLocalOf { RangeDark }

/** Numbers (times, scores, FPS) are set in a monospaced face, as in the mockups. */
val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace)

fun RangeColors.colorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = onAccentSoft,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = card,
        onSurfaceVariant = mutedStrong,
        surfaceContainerLowest = background,
        surfaceContainerLow = rail,
        surfaceContainer = card,
        surfaceContainerHigh = card,
        surfaceContainerHighest = cardBorder,
        inverseSurface = text,
        inverseOnSurface = background,
        outline = chipBorder,
        outlineVariant = cardBorder,
        error = error,
    )
}

@Composable
fun RangeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) RangeDark else RangeLight
    CompositionLocalProvider(LocalRangeColors provides colors) {
        MaterialTheme(
            colorScheme = colors.colorScheme(),
            typography = Typography(),
            content = content,
        )
    }
}

object Range {
    val colors: RangeColors
        @Composable get() = LocalRangeColors.current

    val bigNumber = NumberStyle.copy(fontSize = 22.sp)
}
```


`compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`:

```kotlin
package com.shootoff.compose.shell

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range

/**
 * The places the rail leads to. Targets and Sessions are in the JavaFX app for now (spec §1).
 */
enum class Destination(val label: String, val icon: ImageVector, val enabled: Boolean) {
    RANGE("Range", Icons.Filled.Home, true),
    TARGETS("Targets", Icons.Filled.Place, false),
    DRILLS("Drills", Icons.Filled.PlayArrow, true),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.List, false),
    SETTINGS("Settings", Icons.Filled.Settings, true),
}

const val DISABLED_HINT = "In the JavaFX app for now"

@Composable
fun AppRail(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val colors = Range.colors
    NavigationRail(modifier = modifier, containerColor = colors.rail) {
        Spacer(Modifier.height(12.dp))
        for (destination in Destination.entries) {
            NavigationRailItem(
                selected = destination == selected,
                enabled = destination.enabled,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.onAccentSoft,
                    selectedTextColor = colors.onAccentSoft,
                    indicatorColor = colors.accentSoft,
                    unselectedIconColor = colors.muted,
                    unselectedTextColor = colors.muted,
                    disabledIconColor = colors.muted.copy(alpha = 0.4f),
                    disabledTextColor = colors.muted.copy(alpha = 0.4f),
                ),
                modifier = Modifier.testTag("rail-${destination.name}").semantics {
                    if (!destination.enabled) stateDescription = DISABLED_HINT
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        // Targets and Sessions are greyed out; say why
        Text(
            "Targets and Sessions: ${DISABLED_HINT.replaceFirstChar { it.lowercase() }}",
            color = colors.muted,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp).padding(horizontal = 4.dp).testTag("rail-hint"),
        )
    }
}
```


`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt` (Task 11 replaces it):

```kotlin
package com.shootoff.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range
import com.shootoff.compose.theme.RangeTheme

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ShootOFF") {
        RangeTheme(dark = true) {
            var destination by remember { mutableStateOf(Destination.RANGE) }
            Row(Modifier.fillMaxSize().background(Range.colors.background)) {
                AppRail(destination, { destination = it })
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(destination.label, color = Range.colors.muted)
                }
            }
        }
    }
}
```


- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.theme.*' --tests 'com.shootoff.compose.shell.*' --console=plain`

Expected: `BUILD SUCCESSFUL`; `python3 scripts/test_summary.py summarize compose-app/build/test-results/test` prints 5 `PASS` lines.

- [ ] **Step 7: Check that `./gradlew run` still starts only the JavaFX app**

```bash
cd /home/bfears/projects/ShootOFF
env -u DISPLAY -u WAYLAND_DISPLAY ./gradlew run --continue --console=plain 2>&1 | command grep -E "Task :(javafx-app|compose-app):run"
```

Expected: `> Task :compose-app:run SKIPPED`, then `> Task :javafx-app:run FAILED`. With no display, the JavaFX app stops at once, which here only proves it was the one started. Nothing opens.

- [ ] **Step 8: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `436/436 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 9: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add gradle/libs.versions.toml settings.gradle.kts build.gradle.kts
git add compose-app/build.gradle.kts compose-app/src/main/resources/logback.xml
git add compose-app/src/main/kotlin/com/shootoff/compose/theme/RangeTheme.kt compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt compose-app/src/main/kotlin/com/shootoff/compose/Main.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/theme/TestRangeTheme.kt compose-app/src/test/kotlin/com/shootoff/compose/shell/TestAppRail.kt
git commit -m "Add the compose-app module: Kotlin, Compose desktop, the Range theme and the rail"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 2: The calibration pattern, the white screen, the arena backgrounds and text-to-speech move into `core`

**Files:**
- Move (`git mv`), `javafx-app/src/main/resources/` → `core/src/main/resources/`:
  - `pattern.png`
  - `white.png`
  - `arena/` (its `backgrounds/` folder: eight GIFs and `image_licenses.txt`)
- Move (`git mv`): `javafx-app/src/main/java/com/shootoff/plugins/TextToSpeech.java` → `core/src/main/java/com/shootoff/plugins/TextToSpeech.java`
- Test: `core/src/test/java/com/shootoff/TestBundledImages.java` (new)

**Interfaces:**
- Consumes: nothing new.
- Produces: `core`'s class path has `/pattern.png`, `/white.png` and `/arena/backgrounds/*.gif`. `com.shootoff.plugins.TextToSpeech` (`say(String)`, `silence(boolean)`) is in `core`, unchanged.
  - The JavaFX app loads the images by the same names from the same class loader: `CalibrationManager.setArenaBackground`, `ArenaBackgroundsSlide`, `ProjectorArenaPane.toLocatedImage` and `ExerciseHostSupport.openImage`. So it doesn't change.
  - Task 7's `ArenaModel.showResource` loads the pattern and the white screen; `ExerciseHostSupport.openImage` finds the backgrounds for the Compose host.

**Why these.** They are the Plan 4 final review's gap 1.
- Auto-calibration's exposure step shows `white.png` (`AutoCalibrationManager` line 426), and the owner's `shootoff.arena.calibrated.exposure=true`.
- `ExerciseHost.say` needs `TextToSpeech`, which uses only MaryTTS, and MaryTTS is already `core`'s.
- The package stays `com.shootoff.plugins` (a split package across two jars, as `ExerciseMetadata` already is), so v1 plugins that call `TextToSpeech` still link.

- [ ] **Step 1: Write the failing test**

`core/src/test/java/com/shootoff/TestBundledImages.java`:

```java
package com.shootoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.shootoff.calibration.CalibrationFlow;
import com.shootoff.plugins.TextToSpeech;

class TestBundledImages {
	// Every image a user interface shows from ShootOFF's own resources: the calibration pattern, the
	// exposure step's white screen and the arena backgrounds
	private static final List<String> IMAGES = List.of("/pattern.png", "/white.png",
			"/arena/backgrounds/hickok45_autumn.gif", "/arena/backgrounds/hickok45_summer.gif",
			"/arena/backgrounds/indoor_range.gif", "/arena/backgrounds/kiang_west_savanna.gif",
			"/arena/backgrounds/oradour-sur-glane.gif", "/arena/backgrounds/outdoor_range.gif",
			"/arena/backgrounds/steel_range_bay.gif", "/arena/backgrounds/subterranean_parking_lot.gif");

	@Test
	void theCalibrationPatternAndBackgroundsAreCoreResources() throws Exception {
		for (final String name : IMAGES) {
			final URL url = CalibrationFlow.class.getResource(name);
			assertNotNull(url, name + " is not on core's class path");
			assertTrue(url.toString().contains("/core/"), name + " comes from " + url);

			try (InputStream in = url.openStream()) {
				final BufferedImage image = ImageIO.read(in);
				assertNotNull(image, name + " is not an image");
				assertTrue(image.getWidth() > 0, name);
			}
		}
	}

	@Test
	void textToSpeechIsInCore() throws Exception {
		final URL location = TextToSpeech.class.getProtectionDomain().getCodeSource().getLocation();
		assertTrue(location.toString().contains("/core/"), "TextToSpeech comes from " + location);
		assertEquals("com.shootoff.plugins.TextToSpeech", TextToSpeech.class.getName());
	}
}
```


- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:test --tests 'com.shootoff.TestBundledImages' --console=plain`

Expected: FAIL at compile time: `error: cannot find symbol` … `TextToSpeech` (`core` doesn't have it yet).

- [ ] **Step 3: Move the files**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p core/src/main/resources core/src/main/java/com/shootoff/plugins
git mv javafx-app/src/main/resources/pattern.png core/src/main/resources/pattern.png
git mv javafx-app/src/main/resources/white.png core/src/main/resources/white.png
git mv javafx-app/src/main/resources/arena core/src/main/resources/arena
git mv javafx-app/src/main/java/com/shootoff/plugins/TextToSpeech.java core/src/main/java/com/shootoff/plugins/TextToSpeech.java
git status --short
```

Expected: 12 `R` lines (the eight backgrounds, `image_licenses.txt`, the two images and `TextToSpeech.java`), plus `?? core/src/test/java/com/shootoff/TestBundledImages.java`. No file content changes.

- [ ] **Step 4: Run the test and the JavaFX tests that load these**

Run: `./gradlew :core:test --tests 'com.shootoff.TestBundledImages' :javafx-app:test --tests 'com.shootoff.courses.TestArenaCourse' --tests 'com.shootoff.gui.exercise.TestJavaFxExerciseHost' --tests 'com.shootoff.plugins.TestTextToSpeechEngine' --console=plain`

Expected: `BUILD SUCCESSFUL`: `TestBundledImages` (2), and the JavaFX tests that read `/arena/backgrounds/indoor_range.gif`, `/pattern.png` and `TextToSpeech`, all still passing.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `438/438 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add core/src/test/java/com/shootoff/TestBundledImages.java
git commit -m "Move the calibration pattern, the arena backgrounds and text-to-speech into core"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

The `git mv` renames are already staged.

---
### Task 3: Reset, GIF frames and the projector screen choice move into `core`

**Files:**
- Create: `core/src/main/java/com/shootoff/shots/RangeReset.java`, `core/src/main/java/com/shootoff/geom/ProjectorScreens.java`, `core/src/main/java/com/shootoff/targets/model/GifFrames.java`
- Modify: `javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java` (`reset()`, `disableShotDetection(int)`, init)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java` (`autoPlaceArena()`; `findSmallestScreen()` goes)
- Modify: `javafx-app/src/main/java/com/shootoff/targets/animation/GifAnimation.java` (`readGif`)
- Test: `core/src/test/java/com/shootoff/shots/TestRangeReset.java`, `core/src/test/java/com/shootoff/geom/TestProjectorScreens.java`, `core/src/test/java/com/shootoff/targets/model/TestGifFrames.java` (new)

**Interfaces:**
- Consumes:
  - `CamerasSupervisor` (`reset()`, `areDetecting()`, `setDetectingAll(boolean)`, `getCameraManagers()`)
  - `CameraManager.isDetecting()` and `setDetecting(boolean)`
  - `TimerPool.schedule(Runnable, long)`
  - `Rect`, `Point`
- Produces:
  - `public final class com.shootoff.shots.RangeReset`:
    - `RangeReset(CamerasSupervisor, BooleanSupplier calibrating, RangeReset.Scheduler)`, with `interface Scheduler { void schedule(Runnable task, long delayMillis); }`
    - `void reset(Runnable resetExercise)`
    - `void disableShotDetection(long millis)`
    - `static final long DETECTION_PAUSE_MILLIS = 1000`
  - `public final class com.shootoff.geom.ProjectorScreens`:
    - `enum Reason { SAVED_POSITION, OTHER_OF_TWO, SMALLEST }`, `record Choice(int screen, Reason reason)`
    - `static Optional<Choice> choose(List<Rect> screens, int arenaScreen, OptionalInt appScreen, Optional<Point> savedArenaPosition)`
  - `public final class com.shootoff.targets.model.GifFrames`:
    - `record GifFrame(BufferedImage image, int delayMillis, String disposal)`
    - `static List<GifFrame> read(InputStream) throws IOException`

  The Compose app uses all three: Task 5 (`GifFrames`), Task 7 (`ProjectorScreens`) and Task 11 (`RangeReset`).

**What moves.** These are the UI-neutral parts that the Compose app would otherwise copy (gap 2, and ruling 8's screen choice). Each is the JavaFX code operation for operation:
- `RangeReset`: `ShootOFFController.reset()` and `disableShotDetection(int)`. The exercise's reset is a `Runnable`, and "is it calibrating" a `BooleanSupplier`.
- `GifFrames`: `GifAnimation.readGif`'s decoding. `GifAnimation` keeps its static `frames` field and turns each frame into an `ImageFrame`, as before.
- `ProjectorScreens`: `ProjectorArenaPane.autoPlaceArena`'s choice.
  - It keeps JavaFX's hit rule for the saved position: a 1-pixel square at the point that overlaps a screen, as `Screen.getScreensForRectangle(x, y, 1, 1)` decides.
  - It keeps "the first of equally small screens".
  - It gives no choice with two screens when ShootOFF's own screen is unknown.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/shots/TestRangeReset.java`:

```java
package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.DiagnosticMessage;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;

class TestRangeReset {
	private final List<String> events = new ArrayList<>();
	private final List<Runnable> scheduled = new ArrayList<>();
	private final List<Long> delays = new ArrayList<>();
	private final AtomicBoolean calibrating = new AtomicBoolean(false);
	private CamerasSupervisor cameras;
	private CameraManager first;
	private CameraManager second;
	private RangeReset reset;

	@BeforeEach
	void setUp() throws Exception {
		final Settings settings = new Settings(new String[0]);
		cameras = new CamerasSupervisor(settings);
		first = new CameraManager(new MockCamera(), null, new RecordingView("first"));
		second = new CameraManager(new MockCamera(), null, new RecordingView("second"));
		cameras.getCameraManagers().add(first);
		cameras.getCameraManagers().add(second);
		cameras.setDetectingAll(true);
		reset = new RangeReset(cameras, calibrating::get, (task, delayMillis) -> {
			scheduled.add(task);
			delays.add(delayMillis);
		});
	}

	@Test
	void resetClearsTheCamerasThenResetsTheExerciseThenPausesDetectionForASecond() {
		reset.reset(() -> events.add("exercise"));

		assertEquals(List.of("first reset", "second reset", "exercise"), events);
		assertFalse(first.isDetecting());
		assertFalse(second.isDetecting());
		assertEquals(List.of(RangeReset.DETECTION_PAUSE_MILLIS), delays);

		scheduled.get(0).run();

		assertTrue(first.isDetecting());
		assertTrue(second.isDetecting());
	}

	@Test
	void aCameraThatWasAlreadyOffStaysOff() {
		second.setDetecting(false);

		reset.disableShotDetection(250);
		scheduled.get(0).run();

		assertTrue(first.isDetecting());
		assertFalse(second.isDetecting());
		assertEquals(List.of(250L), delays);
	}

	@Test
	void detectionStaysOffWhileCalibrating() {
		reset.disableShotDetection(1000);
		calibrating.set(true);
		scheduled.get(0).run();

		assertFalse(first.isDetecting());
		assertFalse(second.isDetecting());
	}

	@Test
	void nothingHappensWhileAnExerciseHasDetectionPaused() {
		cameras.setDetectingAll(false);

		reset.disableShotDetection(1000);

		assertTrue(scheduled.isEmpty());
		assertFalse(first.isDetecting());
	}

	// A camera view that writes down resets
	private final class RecordingView implements CameraView {
		private final String name;

		RecordingView(String name) {
			this.name = name;
		}

		@Override
		public void reset() {
			events.add(name + " reset");
		}

		@Override
		public void addShot(ScaledShot shot) {}

		@Override
		public DiagnosticMessage addDiagnosticWarning(String message) {
			return () -> {};
		}

		@Override
		public void clearShots() {}

		@Override
		public void close() {}

		@Override
		public void setCameraManager(CameraManager cameraManager) {}

		@Override
		public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}
	}
}
```


`core/src/test/java/com/shootoff/geom/TestProjectorScreens.java`:

```java
package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.ProjectorScreens.Choice;
import com.shootoff.geom.ProjectorScreens.Reason;

class TestProjectorScreens {
	// The owner's desk: DP-2 on the left, DP-1 in the middle, the projector on HDMI-1
	private static final List<Rect> OWNER = List.of(new Rect(0, 0, 1920, 1080), new Rect(1920, 0, 2560, 1440),
			new Rect(4480, 0, 1280, 720));
	private static final List<Rect> TWO = List.of(new Rect(0, 0, 1920, 1080), new Rect(1920, 0, 1280, 720));

	@Test
	void withMoreThanTwoScreensTheSmallestIsTheProjector() {
		assertEquals(Optional.of(new Choice(2, Reason.SMALLEST)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.empty()));
	}

	@Test
	void theFirstOfEquallySmallScreensWins() {
		final List<Rect> screens = List.of(new Rect(0, 0, 1920, 1080), new Rect(1920, 0, 800, 600),
				new Rect(2720, 0, 600, 800));

		assertEquals(Optional.of(new Choice(1, Reason.SMALLEST)),
				ProjectorScreens.choose(screens, 0, OptionalInt.empty(), Optional.empty()));
	}

	@Test
	void withTwoScreensItIsTheOneShootoffIsNotOn() {
		assertEquals(Optional.of(new Choice(1, Reason.OTHER_OF_TWO)),
				ProjectorScreens.choose(TWO, 0, OptionalInt.of(0), Optional.empty()));
		assertEquals(Optional.of(new Choice(0, Reason.OTHER_OF_TWO)),
				ProjectorScreens.choose(TWO, 0, OptionalInt.of(1), Optional.empty()));
	}

	@Test
	void withTwoScreensAndNoKnownMainWindowScreenThereIsNoChoice() {
		assertEquals(Optional.empty(), ProjectorScreens.choose(TWO, 0, OptionalInt.empty(), Optional.empty()));
	}

	@Test
	void oneScreenIsNeverTheProjector() {
		assertEquals(Optional.empty(),
				ProjectorScreens.choose(List.of(OWNER.get(0)), 0, OptionalInt.of(0), Optional.empty()));
	}

	@Test
	void aSavedPositionOnAnotherScreenWins() {
		assertEquals(Optional.of(new Choice(0, Reason.SAVED_POSITION)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.of(new Point(100, 100))));
	}

	@Test
	void aSavedPositionOnTheArenasOwnScreenOrOffEveryScreenIsIgnored() {
		assertEquals(Optional.of(new Choice(2, Reason.SMALLEST)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.of(new Point(2000, 100))));
		assertEquals(Optional.of(new Choice(2, Reason.SMALLEST)),
				ProjectorScreens.choose(OWNER, 1, OptionalInt.of(1), Optional.of(new Point(9000, 100))));
	}

	@Test
	void aSavedPositionOnAScreensLeftEdgeIsOnThatScreen() {
		assertEquals(Optional.of(new Choice(2, Reason.SAVED_POSITION)),
				ProjectorScreens.choose(OWNER, 0, OptionalInt.of(0), Optional.of(new Point(4480, 0))));
		// One pixel left of it is the middle screen's last column
		assertEquals(Optional.of(new Choice(1, Reason.SAVED_POSITION)),
				ProjectorScreens.choose(OWNER, 0, OptionalInt.of(0), Optional.of(new Point(4479, 0))));
	}
}
```


`core/src/test/java/com/shootoff/targets/model/TestGifFrames.java`:

```java
package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;

import com.shootoff.targets.model.GifFrames.GifFrame;

class TestGifFrames {
	private static final String POPPER = "targets/pepper_popper.gif";

	@Test
	void everyFrameIsWholeAtTheLogicalScreenSize() throws Exception {
		final List<GifFrame> frames;
		try (InputStream in = new FileInputStream(POPPER)) {
			frames = GifFrames.read(in);
		}

		final int[] logical;
		try (InputStream in = new FileInputStream(POPPER)) {
			logical = ImageSizes.read(in, true);
		}

		assertEquals(frameCount(), frames.size());
		for (final GifFrame frame : frames) {
			assertEquals(logical[0], frame.image().getWidth());
			assertEquals(logical[1], frame.image().getHeight());
			assertTrue(frame.delayMillis() >= 0);
		}
	}

	@Test
	void framesAreComposedOverTheOnesBefore() throws Exception {
		final List<GifFrame> frames;
		try (InputStream in = new FileInputStream(POPPER)) {
			frames = GifFrames.read(in);
		}

		// The popper falls: the first and last frames differ, and each is a separate copy
		final GifFrame first = frames.get(0);
		final GifFrame last = frames.get(frames.size() - 1);
		assertNotEquals(opaquePixels(first), opaquePixels(last));
		assertTrue(first.image() != last.image());
	}

	private static int frameCount() throws Exception {
		try (ImageInputStream in = ImageIO.createImageInputStream(new FileInputStream(POPPER))) {
			final ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
			reader.setInput(in);
			final int count = reader.getNumImages(true);
			reader.dispose();
			return count;
		}
	}

	private static int opaquePixels(GifFrame frame) {
		int count = 0;
		for (int x = 0; x < frame.image().getWidth(); x++) {
			for (int y = 0; y < frame.image().getHeight(); y++) {
				if ((frame.image().getRGB(x, y) >>> 24) != 0) count++;
			}
		}
		return count;
	}
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.shots.TestRangeReset' --tests 'com.shootoff.geom.TestProjectorScreens' --tests 'com.shootoff.targets.model.TestGifFrames' --console=plain`

Expected: FAIL at compile time: `cannot find symbol` … `RangeReset`, and `package com.shootoff.geom.ProjectorScreens does not exist` / `com.shootoff.targets.model.GifFrames does not exist`.

- [ ] **Step 3: Write the three classes**

Each starts with the GPL header.

`core/src/main/java/com/shootoff/shots/RangeReset.java`:

```java
package com.shootoff.shots;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CamerasSupervisor;

/**
 * What Reset does, whatever user interface asks for it (the Reset button, or a target region's
 * <tt>reset</tt> command): reset every camera (its shots, its view's targets' animations) and the shot
 * processors, then the running exercise, then pause shot detection for a second so the reset itself
 * isn't seen as shots.
 */
public final class RangeReset {
	private static final Logger logger = LoggerFactory.getLogger(RangeReset.class);

	public static final long DETECTION_PAUSE_MILLIS = 1000;

	@FunctionalInterface
	public interface Scheduler {
		/**
		 * Runs <tt>task</tt> on a background thread after <tt>delayMillis</tt>.
		 */
		void schedule(Runnable task, long delayMillis);
	}

	private final CamerasSupervisor cameras;
	private final BooleanSupplier calibrating;
	private final Scheduler scheduler;

	/**
	 * @param calibrating
	 *            <tt>true</tt> while the arena is calibrating: detection then stays off
	 */
	public RangeReset(CamerasSupervisor cameras, BooleanSupplier calibrating, Scheduler scheduler) {
		this.cameras = cameras;
		this.calibrating = calibrating;
		this.scheduler = scheduler;
	}

	/**
	 * @param resetExercise
	 *            resets the running exercise, if there is one
	 */
	public void reset(Runnable resetExercise) {
		cameras.reset();

		resetExercise.run();

		disableShotDetection(DETECTION_PAUSE_MILLIS);
	}

	/**
	 * Turns shot detection off for <tt>millis</tt>, unless it is already off everywhere (e.g. an exercise
	 * paused it). Cameras that were already off stay off, and none comes back on while calibrating.
	 * Technically a shorter pause could be asked for while a longer one runs; pauses are short, so that
	 * isn't handled.
	 */
	public void disableShotDetection(long millis) {
		if (!cameras.areDetecting()) return;

		// Keep track of cameras that already had shot detection off so that
		// we can ensure they stay off when we re-enable shot detection
		final Set<CameraManager> alreadyOff = new HashSet<>();

		for (final CameraManager cm : cameras.getCameraManagers()) {
			if (!cm.isDetecting()) alreadyOff.add(cm);
		}

		cameras.setDetectingAll(false);

		final Runnable restartDetection = () -> {
			if (!calibrating.getAsBoolean()) {
				if (alreadyOff.isEmpty()) {
					cameras.setDetectingAll(true);
				} else {
					for (final CameraManager cm : cameras.getCameraManagers()) {
						if (!alreadyOff.contains(cm)) cm.setDetecting(true);
					}
				}
			} else {
				logger.info("disableShotDetectionTimer did not re-enable shot detection, isCalibrating is true");
			}
		};

		scheduler.schedule(restartDetection, millis);
	}
}
```


`core/src/main/java/com/shootoff/geom/ProjectorScreens.java`:

```java
package com.shootoff.geom;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Which screen the projector arena goes on, whatever user interface draws it:
 * <ol>
 * <li>where the user last put the arena by hand (the saved arena position), if that is on a screen
 * other than the one the arena window opened on</li>
 * <li>otherwise, with exactly two screens, the one ShootOFF's main window isn't on</li>
 * <li>otherwise, with more than two screens, the smallest (the first of equals)</li>
 * </ol>
 * Screens are in the user interface's screen order and coordinates.
 */
public final class ProjectorScreens {
	private ProjectorScreens() {}

	public enum Reason {
		SAVED_POSITION, OTHER_OF_TWO, SMALLEST
	}

	/**
	 * @param screen
	 *            the index of the chosen screen
	 */
	public record Choice(int screen, Reason reason) {}

	/**
	 * @param arenaScreen
	 *            the screen the arena window is on now
	 * @param appScreen
	 *            the screen ShootOFF's main window is on, if known (only needed with two screens)
	 * @param savedArenaPosition
	 *            where the user last put the arena by hand, if anywhere
	 * @return the screen for the arena, or empty if none looks like a projector
	 */
	public static Optional<Choice> choose(List<Rect> screens, int arenaScreen, OptionalInt appScreen,
			Optional<Point> savedArenaPosition) {
		if (savedArenaPosition.isPresent()) {
			final Point saved = savedArenaPosition.get();

			int first = -1;
			boolean onArenaScreen = false;
			for (int i = 0; i < screens.size(); i++) {
				if (!touches(screens.get(i), saved)) continue;
				if (first < 0) first = i;
				if (i == arenaScreen) onArenaScreen = true;
			}

			if (first >= 0 && !onArenaScreen) return Optional.of(new Choice(first, Reason.SAVED_POSITION));
		}

		if (screens.size() == 2) {
			if (appScreen.isEmpty()) return Optional.empty();

			return Optional.of(new Choice(appScreen.getAsInt() == 0 ? 1 : 0, Reason.OTHER_OF_TWO));
		}

		if (screens.size() > 2) {
			int smallest = 0;
			for (int i = 1; i < screens.size(); i++) {
				if (area(screens.get(i)) < area(screens.get(smallest))) smallest = i;
			}
			return Optional.of(new Choice(smallest, Reason.SMALLEST));
		}

		return Optional.empty();
	}

	// Whether a one-pixel square at the point overlaps the screen, as JavaFX's
	// Screen.getScreensForRectangle(x, y, 1, 1) decides it
	private static boolean touches(Rect screen, Point point) {
		return point.getX() + 1 > screen.getMinX() && point.getY() + 1 > screen.getMinY()
				&& point.getX() < screen.getMaxX() && point.getY() < screen.getMaxY();
	}

	private static double area(Rect screen) {
		return screen.getHeight() * screen.getWidth();
	}
}
```


`core/src/main/java/com/shootoff/targets/model/GifFrames.java`:

```java
package com.shootoff.targets.model;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads an animated GIF into whole frames, each drawn over the frames before it as the GIF's disposal
 * methods say, at the GIF's logical screen size. The pixels are the user interface's to draw; the hit
 * tester sees the current frame as an {@link AlphaMask}.
 */
public final class GifFrames {
	private GifFrames() {}

	/**
	 * One whole frame.
	 *
	 * @param delayMillis
	 *            how long the GIF shows it
	 * @param disposal
	 *            the frame's disposal method, e.g. "none" or "restoreToPrevious"
	 */
	public record GifFrame(BufferedImage image, int delayMillis, String disposal) {}

	// This method is from http://stackoverflow.com/a/17269591
	public static List<GifFrame> read(InputStream stream) throws IOException {
		final ArrayList<GifFrame> frames = new ArrayList<>(2);

		int width = -1;
		int height = -1;

		final ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
		reader.setInput(ImageIO.createImageInputStream(stream));
		final IIOMetadata metadata = reader.getStreamMetadata();
		if (metadata != null) {
			final IIOMetadataNode globalRoot = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());

			final NodeList globalScreenDescriptor = globalRoot.getElementsByTagName("LogicalScreenDescriptor");

			if (globalScreenDescriptor.getLength() > 0) {
				final IIOMetadataNode screenDescriptor = (IIOMetadataNode) globalScreenDescriptor.item(0);

				if (screenDescriptor != null) {
					width = Integer.parseInt(screenDescriptor.getAttribute("logicalScreenWidth"));
					height = Integer.parseInt(screenDescriptor.getAttribute("logicalScreenHeight"));
				}
			}
		}

		BufferedImage master = null;
		Graphics2D masterGraphics = null;

		for (int frameIndex = 0;; frameIndex++) {
			BufferedImage image;
			try {
				image = reader.read(frameIndex);
			} catch (final IndexOutOfBoundsException io) {
				break;
			}

			if (width == -1 || height == -1) {
				width = image.getWidth();
				height = image.getHeight();
			}

			final IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(frameIndex)
					.getAsTree("javax_imageio_gif_image_1.0");
			final IIOMetadataNode gce = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
			final int delay = Integer.parseInt(gce.getAttribute("delayTime")) * 10;
			final String disposal = gce.getAttribute("disposalMethod");

			int x = 0;
			int y = 0;

			if (master == null) {
				master = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
				masterGraphics = master.createGraphics();
				masterGraphics.setBackground(new Color(0, 0, 0, 0));
			} else {
				final NodeList children = root.getChildNodes();
				for (int nodeIndex = 0; nodeIndex < children.getLength(); nodeIndex++) {
					final Node nodeItem = children.item(nodeIndex);
					if (nodeItem.getNodeName().equals("ImageDescriptor")) {
						final NamedNodeMap map = nodeItem.getAttributes();
						x = Integer.parseInt(map.getNamedItem("imageLeftPosition").getNodeValue());
						y = Integer.parseInt(map.getNamedItem("imageTopPosition").getNodeValue());
					}
				}
			}
			masterGraphics.drawImage(image, x, y, null);

			final BufferedImage copy = new BufferedImage(master.getColorModel(), master.copyData(null),
					master.isAlphaPremultiplied(), null);
			frames.add(new GifFrame(copy, delay, disposal));

			if (disposal.equals("restoreToPrevious")) {
				BufferedImage from = null;
				for (int i = frameIndex - 1; i >= 0; i--) {
					if (!frames.get(i).disposal().equals("restoreToPrevious") || frameIndex == 0) {
						from = frames.get(i).image();
						break;
					}
				}

				master = new BufferedImage(from.getColorModel(), from.copyData(null), from.isAlphaPremultiplied(),
						null);
				masterGraphics = master.createGraphics();
				masterGraphics.setBackground(new Color(0, 0, 0, 0));
			} else if (disposal.equals("restoreToBackgroundColor")) {
				masterGraphics.clearRect(x, y, image.getWidth(), image.getHeight());
			}
		}
		reader.dispose();

		return frames;
	}
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run the Step 2 command again.

Expected: `BUILD SUCCESSFUL`, 14 tests passing.

- [ ] **Step 5: Switch the JavaFX app onto them**

`javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java`:

Replace:

```java
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
```

with:

```java
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
```

Replace:

```java
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
```

with:

```java
import java.util.Map.Entry;
import java.util.Optional;

import org.slf4j.Logger;
```

Replace:

```java
import com.shootoff.plugins.engine.PluginEngine;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.targets.CameraViews;
import com.shootoff.targets.Target;
```

with:

```java
import com.shootoff.plugins.engine.PluginEngine;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.shots.RangeReset;
import com.shootoff.targets.CameraViews;
import com.shootoff.targets.Target;
```

Replace:

```java
	private Configuration config;
	private PluginEngine pluginEngine;
	private static final Logger logger = LoggerFactory.getLogger(ShootOFFController.class);
	private final ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();
```

with:

```java
	private Configuration config;
	private PluginEngine pluginEngine;
	private RangeReset rangeReset;
	private static final Logger logger = LoggerFactory.getLogger(ShootOFFController.class);
	private final ObservableList<ShotEntry> shotEntries = FXCollections.observableArrayList();
```

Replace:

```java
		projectorSlide = new ProjectorSlide(controlsContainer, bodyContainer, this, shootOFFStage,
				trainingExerciseContainer, this, exerciseSlide);

		pluginEngine = new PluginEngine(exerciseSlide, ExerciseLoaders.all(), BuiltInExercises.entries());
```

with:

```java
		projectorSlide = new ProjectorSlide(controlsContainer, bodyContainer, this, shootOFFStage,
				trainingExerciseContainer, this, exerciseSlide);

		rangeReset = new RangeReset(camerasSupervisor,
				() -> projectorSlide.getCalibrationManager().map(CalibrationManager::isCalibrating).orElse(false),
				TimerPool::schedule);

		pluginEngine = new PluginEngine(exerciseSlide, ExerciseLoaders.all(), BuiltInExercises.entries());
```

Replace:

```java
	@Override
	public void reset() {
		camerasSupervisor.reset();

		if (config.getExercise().isPresent()) {
			final List<Target> knownTargets = new ArrayList<>();
			knownTargets.addAll(getTargets());

			if (projectorSlide.getArenaPane() != null) {
				knownTargets.addAll(projectorSlide.getArenaPane().getCanvasManager().getTargets());
			}

			config.getExercise().get().reset(knownTargets);
		}

		disableShotDetection(1000);
	}

	// Technically the period could be shorter than the previous call
	// and we don't handle that right now. I'm not too worried about that
	// because I don't think the periods are going to be vastly different
	// This is only intended for very short disablement periods
	public void disableShotDetection(int msDuration) {
		// Don't disable the cameras if they are already disabled (e.g. because
		// a training protocol paused shot detection)
		if (!camerasSupervisor.areDetecting()) return;

		// Keep track of cameras that already had shot detection off so that
		// we can ensure they stay off when we re-enable shot detection
		final Set<CameraManager> alreadyOff = new HashSet<>();

		for (final CameraManager cm : camerasSupervisor.getCameraManagers()) {
			if (!cm.isDetecting()) alreadyOff.add(cm);
		}

		camerasSupervisor.setDetectingAll(false);

		final Runnable restartDetection = () -> {
			final Optional<CalibrationManager> calibrationManager = projectorSlide.getCalibrationManager();

			if (!calibrationManager.isPresent()
					|| (calibrationManager.isPresent() && !calibrationManager.get().isCalibrating())) {
				if (alreadyOff.isEmpty()) {
					camerasSupervisor.setDetectingAll(true);
				} else {
					for (final CameraManager cm : camerasSupervisor.getCameraManagers()) {
						if (!alreadyOff.contains(cm)) cm.setDetecting(true);
					}
				}
			} else {
				logger.info("disableShotDetectionTimer did not re-enable shot detection, isCalibrating is true");
			}
		};

		TimerPool.schedule(restartDetection, msDuration);
	}
```

with:

```java
	@Override
	public void reset() {
		rangeReset.reset(() -> {
			if (config.getExercise().isPresent()) {
				final List<Target> knownTargets = new ArrayList<>();
				knownTargets.addAll(getTargets());

				if (projectorSlide.getArenaPane() != null) {
					knownTargets.addAll(projectorSlide.getArenaPane().getCanvasManager().getTargets());
				}

				config.getExercise().get().reset(knownTargets);
			}
		});
	}

	/**
	 * Pauses shot detection briefly: see {@link RangeReset#disableShotDetection}.
	 */
	public void disableShotDetection(int msDuration) {
		rangeReset.disableShotDetection(msDuration);
	}
```

`javafx-app/src/main/java/com/shootoff/targets/animation/GifAnimation.java`: replace everything from `package` to the end of the file with:

```java
package com.shootoff.targets.animation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import com.shootoff.targets.model.GifFrames;
import com.shootoff.targets.model.GifFrames.GifFrame;

import javafx.scene.image.ImageView;
import javafx.util.Duration;

public class GifAnimation extends SpriteAnimation {
	private static ImageFrame[] frames;

	public GifAnimation(ImageView imageView, InputStream gifStream) throws IOException {
		super(imageView, readGif(gifStream));

		int delay = frames[0].getDelay();
		if (delay < 1) delay = SpriteAnimation.DEFAULT_DELAY;

		setCycleDuration(Duration.millis(delay));
	}

	public GifAnimation(ImageView imageView, File gifFile) throws FileNotFoundException, IOException {
		super(imageView, readGif(new FileInputStream(gifFile)));

		int delay = frames[0].getDelay();
		if (delay < 1) delay = SpriteAnimation.DEFAULT_DELAY;

		setCycleDuration(Duration.millis(delay));
	}

	// The frames are core's GifFrames: whole frames, composed as the GIF's disposal methods say
	private static ImageFrame[] readGif(InputStream stream) throws IOException {
		final ArrayList<ImageFrame> frames = new ArrayList<>(2);

		for (final GifFrame frame : GifFrames.read(stream)) {
			frames.add(new ImageFrame(frame.image(), frame.delayMillis(), frame.disposal()));
		}

		GifAnimation.frames = frames.toArray(new ImageFrame[frames.size()]);
		return GifAnimation.frames;
	}
}
```

`javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java`:

Replace:

```java
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
```

with:

```java
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ScheduledFuture;
```

Replace:

```java
import com.shootoff.util.TimerPool;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
```

with:

```java
import com.shootoff.util.TimerPool;
import com.shootoff.geom.Point;
import com.shootoff.geom.ProjectorScreens;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
```

Replace:

```java

	public void autoPlaceArena() {
		Optional<Screen> homeScreen = getStageHomeScreen(arenaStage);

		if (homeScreen.isPresent()) {
```

with:

```java

	public void autoPlaceArena() {
		final Optional<Screen> homeScreen = getStageHomeScreen(arenaStage);

		if (homeScreen.isPresent()) {
```

Replace:

```java
		}

		// Place the arena on what we hope is the projector with the following
		// precidence:
		// 1. If the user has place the arena on a screen before, place it on
		// that screen again
		// 2. If the user has never placed the arena before and there are only
		// two screens,
		// put it on the screen the ShootOFF window isn't on
		// 3. If the arena has never been placed and there are more than two
		// screens, place
		// the arena on the smallest screen
		if (config.getArenaPosition().isPresent()) {
			logger.debug("Projector has been manually placed previously");

			final Point arenaPosition = config.getArenaPosition().get();

			final ObservableList<Screen> screens = Screen.getScreensForRectangle(arenaPosition.getX(),
					arenaPosition.getY(), 1, 1);

			if (!screens.isEmpty()) {
				boolean matchedOriginal = false;
				for (final Screen screen : screens) {
					if (originalArenaHomeScreen.equals(screen)) {
						logger.debug("Stored arena coordinates are on current home screen");
						matchedOriginal = true;
					}
				}

				if (!matchedOriginal) {
					arenaStage.setX(arenaPosition.getX());
					arenaStage.setY(arenaPosition.getY());

					Platform.runLater(() -> toggleFullScreen());

					arenaHome = screens.get(0);

					setArenaScreenOrigin(arenaHome);

					return;
				}

			} else {
				logger.debug("Saved screen coordinates ({}, {}) no longer exists, attempting fallback approaches...",
						arenaPosition.getX(), arenaPosition.getY());
			}
		}

		Optional<Screen> projector = Optional.empty();

		if (Screen.getScreens().size() == 2) {
			logger.debug("Two screens present");

			homeScreen = getStageHomeScreen(shootOffStage);

			if (!homeScreen.isPresent()) return;

			final Screen shootOFFScreen = homeScreen.get();

			for (final Screen screen : Screen.getScreens()) {
				if (!screen.equals(shootOFFScreen)) {
					projector = Optional.of(screen);
					break;
				}
			}
		} else if (Screen.getScreens().size() > 2) {
			logger.debug("More than two screens present");

			projector = findSmallestScreen();
		}

		if (projector.isPresent()) {
			final double dpiScaleFactor = ShootOFFController.getDpiScaleFactorForScreen();

			arenaHome = projector.get();

			final double newX = arenaHome.getBounds().getMinX() * dpiScaleFactor;
			final double newY = arenaHome.getBounds().getMinY() * dpiScaleFactor;

			logger.debug("Found likely projector screen: resolution = {}x{}, newX = {}, newY = {}",
					arenaHome.getBounds().getWidth(), arenaHome.getBounds().getHeight(), newX, newY);

			arenaStage.setX(newX + 10);
			arenaStage.setY(newY + 10);

			detectedProjectorScreen = projector;

			setArenaScreenOrigin(arenaHome);

			Platform.runLater(() -> toggleFullScreen());

		} else {
			logger.debug("Did not find screen that is a likely projector");
		}
	}

	private Optional<Screen> findSmallestScreen() {
		Screen smallest = null;

		// Find screen with the smallest area
		for (final Screen screen : Screen.getScreens()) {
			if (smallest == null) {
				smallest = screen;
			} else {
				if (screen.getBounds().getHeight() * screen.getBounds().getWidth() < smallest.getBounds().getHeight()
						* smallest.getBounds().getWidth()) {
					smallest = screen;
				}
			}
		}

		return Optional.ofNullable(smallest);
	}
```

with:

```java
		}

		// Place the arena on what we hope is the projector: core's ProjectorScreens picks the screen
		// (where the user last put the arena, else the other of two screens, else the smallest)
		final List<Screen> screens = new ArrayList<>(Screen.getScreens());
		final List<Rect> screenBounds = new ArrayList<>();
		for (final Screen screen : screens) {
			final Rectangle2D b = screen.getBounds();
			screenBounds.add(new Rect(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()));
		}

		final Optional<Point> arenaPosition = config.getArenaPosition();
		if (arenaPosition.isPresent()) logger.debug("Projector has been manually placed previously");

		// ShootOFF's own screen only matters, and is only looked up, with exactly two screens
		OptionalInt shootOffScreen = OptionalInt.empty();
		if (screens.size() == 2) {
			logger.debug("Two screens present");

			final Optional<Screen> shootOffHome = getStageHomeScreen(shootOffStage);
			if (shootOffHome.isPresent()) shootOffScreen = OptionalInt.of(screens.indexOf(shootOffHome.get()));
		} else if (screens.size() > 2) {
			logger.debug("More than two screens present");
		}

		final Optional<ProjectorScreens.Choice> choice = ProjectorScreens.choose(screenBounds,
				screens.indexOf(originalArenaHomeScreen), shootOffScreen, arenaPosition);

		if (choice.isEmpty()) {
			logger.debug("Did not find screen that is a likely projector");
			return;
		}

		if (choice.get().reason() == ProjectorScreens.Reason.SAVED_POSITION) {
			arenaStage.setX(arenaPosition.get().getX());
			arenaStage.setY(arenaPosition.get().getY());

			Platform.runLater(() -> toggleFullScreen());

			arenaHome = screens.get(choice.get().screen());

			setArenaScreenOrigin(arenaHome);

			return;
		}

		final double dpiScaleFactor = ShootOFFController.getDpiScaleFactorForScreen();

		arenaHome = screens.get(choice.get().screen());

		final double newX = arenaHome.getBounds().getMinX() * dpiScaleFactor;
		final double newY = arenaHome.getBounds().getMinY() * dpiScaleFactor;

		logger.debug("Found likely projector screen: resolution = {}x{}, newX = {}, newY = {}",
				arenaHome.getBounds().getWidth(), arenaHome.getBounds().getHeight(), newX, newY);

		arenaStage.setX(newX + 10);
		arenaStage.setY(newY + 10);

		detectedProjectorScreen = Optional.of(arenaHome);

		setArenaScreenOrigin(arenaHome);

		Platform.runLater(() -> toggleFullScreen());
	}
```

- [ ] **Step 6: Run the JavaFX tests that cover these paths**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.targets.io.TestTargetIO' --tests 'com.shootoff.gui.TestCanvasManager' --tests 'com.shootoff.gui.TestCanvasManagerHits' --tests 'com.shootoff.targets.TestHitParity' --console=plain`

Expected: `BUILD SUCCESSFUL` with no failures. `TestTargetIO` builds GIF targets through `GifAnimation`. The screen choice and Reset have no JavaFX tests; Task 15's regression check covers them on the hardware.

- [ ] **Step 7: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `452/452 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 8: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add core/src/main/java/com/shootoff/shots/RangeReset.java core/src/main/java/com/shootoff/geom/ProjectorScreens.java core/src/main/java/com/shootoff/targets/model/GifFrames.java
git add core/src/test/java/com/shootoff/shots/TestRangeReset.java core/src/test/java/com/shootoff/geom/TestProjectorScreens.java core/src/test/java/com/shootoff/targets/model/TestGifFrames.java
git add javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java javafx-app/src/main/java/com/shootoff/targets/animation/GifAnimation.java
git commit -m "Move Reset, GIF frames and the projector screen choice into core"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 4: The camera feed, its banners and the status strip

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/surface/SurfaceTransform.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/feed/CameraFeedView.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedBanners.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/feed/StatusStrip.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/surface/TestSurfaceTransform.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/feed/TestFeedState.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/feed/TestComposeCameraView.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/feed/TestFeedViews.kt`

**Interfaces:**
- Consumes:
  - `core`'s `CameraView` (`addShot(ScaledShot)`, `addDiagnosticWarning(String): DiagnosticMessage`, `clearShots()`, `close()`, `reset()`, `setCameraManager(CameraManager)`, `updateBackground(BufferedImage, Optional<Rect>)`)
  - `CameraManager.getFeedWidth/getFeedHeight/getFPS/getName`
  - `ArenaGeometry.cameraToCanvas(Rect, Size, Size)`
  - Task 1's `Range`, `NumberStyle`
- Produces:
  - `com.shootoff.compose.surface.SurfaceTransform(scale: Float, offsetX: Float, offsetY: Float)`:
    - `toView(x, y): Offset`, `toSurface(Offset): Point`
    - `companion fun fit(surface: Size, viewWidth: Float, viewHeight: Float)`: the surface scaled to fit, keeping its aspect, centered
    - used by every view from here on
  - `com.shootoff.compose.feed`:
    - `class FeedFrame(image: ImageBitmap, bounds: Rect)`
    - `enum BannerKind { WARNING, INFO, CALIBRATION, ERROR }`, `data class Banner(id: Long, text: String, kind: BannerKind)`
    - `class FeedState(displaySize: Size, clock: () -> Long = System::currentTimeMillis)`:
      - `frame: StateFlow<FeedFrame?>`, `banners: StateFlow<List<Banner>>`, `fps: StateFlow<Double>`
      - `showFrame(FeedFrame)`, `clearFrame()`, `addBanner(text, kind): Banner`, `removeBanner(Banner)`
    - `interface FeedShots { add(ScaledShot); clear(); reset() }` and `FeedShots.None`
    - `class ComposeCameraView(name: String, feed: FeedState, var shots: FeedShots = FeedShots.None) : CameraView`, with `cameraManager: CameraManager?`
    - `@Composable CameraFeedView(feed, modifier, overlay: @Composable (SurfaceTransform) -> Unit)`
    - `@Composable FeedBanners(feed, modifier)`, `@Composable BannerView(banner, onDismiss)`
    - `enum CalibrationStatus(label) { NO_ARENA, NEEDS_CALIBRATION, CALIBRATING, CALIBRATED }`
    - `data class FeedStatus(camera, cameraFps, shownFps, width, height, calibration, recording)` with `text()`
    - `@Composable StatusStrip(status, modifier)`

**How frames flow (spec §3).**
- `CameraManager` calls `updateBackground` on its own thread. `ComposeCameraView` converts the frame there, never on the UI thread, and puts it in a `StateFlow`, which keeps only the newest (ruling 17).
- A frame cropped to the projection goes where the projection is on the feed's canvas, the display size (640x480).
- Warnings become banners until the camera removes them.
- `FeedState.fps` counts the frames shown in the last second. The status strip shows it next to the camera's own FPS; that is the spec's frame-rate measurement.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/surface/TestSurfaceTransform.kt`:

```kotlin
package com.shootoff.compose.surface

import androidx.compose.ui.geometry.Offset
import com.shootoff.geom.Point
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestSurfaceTransform {
    @Test
    fun aWideViewLetterboxesTheSurfaceLeftAndRight() {
        val transform = SurfaceTransform.fit(Size(640.0, 480.0), 1000f, 480f)

        assertEquals(SurfaceTransform(1f, 180f, 0f), transform)
        assertEquals(Offset(180f, 0f), transform.toView(0.0, 0.0))
        assertEquals(Offset(820f, 480f), transform.toView(640.0, 480.0))
    }

    @Test
    fun viewPointsMapBackToTheSurface() {
        val transform = SurfaceTransform.fit(Size(1280.0, 720.0), 640f, 480f)

        assertEquals(0.5f, transform.scale)
        assertEquals(Point(640.0, 360.0), transform.toSurface(transform.toView(640.0, 360.0)))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/feed/TestFeedState.kt`:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TestFeedState {
    private var now = 0L
    private val feed = FeedState(Size(640.0, 480.0)) { now }

    private fun frame() = FeedFrame(ImageBitmap(4, 3), Rect(0.0, 0.0, 640.0, 480.0))

    @Test
    fun onlyTheNewestFrameIsKept() {
        val first = frame()
        val second = frame()

        feed.showFrame(first)
        feed.showFrame(second)

        assertSame(second, feed.frame.value)
    }

    @Test
    fun fpsCountsTheFramesShownInTheLastSecond() {
        for (i in 0 until 30) {
            now = i * 33L
            feed.showFrame(frame())
        }
        assertEquals(30.0, feed.fps.value)

        // At 1.5 s only the frames after 0.5 s count: 14 of the first 30, and this one
        now = 1500
        feed.showFrame(frame())
        assertEquals(15.0, feed.fps.value)
    }

    @Test
    fun clearingTheFrameShowsNothingAndZeroFps() {
        feed.showFrame(frame())
        feed.clearFrame()

        assertNull(feed.frame.value)
        assertEquals(0.0, feed.fps.value)
    }

    @Test
    fun bannersComeAndGoInOrder() {
        val bright = feed.addBanner("Warning: Excessive brightness", BannerKind.WARNING)
        val calibrating = feed.addBanner("Looking for the calibration pattern", BannerKind.CALIBRATION)

        assertEquals(listOf(bright, calibrating), feed.banners.value)

        feed.removeBanner(bright)
        assertEquals(listOf(calibrating), feed.banners.value)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/feed/TestComposeCameraView.kt`:

```kotlin
package com.shootoff.compose.feed

import com.shootoff.camera.CameraManager
import com.shootoff.camera.MockCamera
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.util.Optional

class TestComposeCameraView {
    private val feed = FeedState(Size(640.0, 480.0))
    private val events = mutableListOf<String>()
    private lateinit var view: ComposeCameraView

    @BeforeEach
    fun setUp() {
        Settings(arrayOf())
        view = ComposeCameraView("C270", feed, object : FeedShots {
            override fun add(shot: ScaledShot) {
                events += "shot ${shot.x},${shot.y}"
            }

            override fun clear() {
                events += "clear"
            }

            override fun reset() {
                events += "reset"
            }
        })
        CameraManager(MockCamera(), null, view)
    }

    @Test
    fun aFrameFillsTheCanvas() {
        view.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), Optional.empty())

        val frame = feed.frame.value!!
        assertEquals(Rect(0.0, 0.0, 640.0, 480.0), frame.bounds)
        assertEquals(640, frame.image.width)
    }

    @Test
    fun aFrameCroppedToTheProjectionGoesWhereTheProjectionIsOnTheCanvas() {
        // A 1280x960 camera shown on the 640x480 canvas: the projection is halved
        view.cameraManager!!.setFeedResolution(1280, 960)

        view.updateBackground(BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB), Optional.of(Rect(200.0, 100.0, 400.0, 300.0)))

        assertEquals(Rect(100.0, 50.0, 200.0, 150.0), feed.frame.value!!.bounds)
    }

    @Test
    fun noFrameShowsNothing() {
        view.updateBackground(BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB), Optional.empty())
        view.updateBackground(null, Optional.empty())

        assertNull(feed.frame.value)
    }

    @Test
    fun warningsAreBannersUntilTheCameraRemovesThem() {
        val warning = view.addDiagnosticWarning("Warning: Excessive brightness")
        assertEquals(listOf("Warning: Excessive brightness"), feed.banners.value.map { it.text })

        warning.remove()
        assertEquals(emptyList<Banner>(), feed.banners.value)
    }

    @Test
    fun shotsClearsAndResetsGoToTheFeedsShots() {
        view.addShot(ScaledShot(ShotColor.RED, 10.0, 20.0, 5))
        view.clearShots()
        view.reset()

        assertEquals(listOf("shot 10.0,20.0", "clear", "reset"), events)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/feed/TestFeedViews.kt`:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage

class TestFeedViews {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theNewestFrameIsDrawnFittedToTheView() {
        val feed = FeedState(Size(640.0, 480.0))
        val red = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0 until 64) for (y in 0 until 48) setRGB(x, y, 0xFF0000)
        }
        feed.showFrame(FeedFrame(red.toComposeImageBitmap(), Rect(0.0, 0.0, 640.0, 480.0)))

        compose.setContent { RangeTheme(dark = true) { CameraFeedView(feed, Modifier.size(320.dp, 240.dp)) } }

        val pixels = compose.onNodeWithTag("camera-feed").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun theStatusStripSaysCameraFpsResolutionCalibrationAndRecording() {
        val status = FeedStatus("C270", 30.0, 29.6, 640, 480, CalibrationStatus.CALIBRATED, recording = true)

        compose.setContent { RangeTheme(dark = true) { StatusStrip(status) } }

        compose.onNodeWithTag("status-strip").assertTextContains("C270 · 30 FPS · shown 30 · 640×480 · calibrated · ● REC")
    }

    @Test
    fun aBannerCanBeDismissed() {
        val feed = FeedState(Size(640.0, 480.0))
        feed.addBanner("The FPS from C270 has dropped", BannerKind.WARNING)

        compose.setContent { RangeTheme(dark = true) { FeedBanners(feed) } }
        compose.onNodeWithText("The FPS from C270 has dropped").assertExists()

        compose.onNodeWithTag("dismiss-${feed.banners.value.single().id}").performClick()
        compose.waitForIdle()

        assertEquals(0, feed.banners.value.size)
        compose.onNodeWithText("The FPS from C270 has dropped").assertDoesNotExist()
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.surface.*' --tests 'com.shootoff.compose.feed.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'SurfaceTransform'` (and `'FeedState'`, `'ComposeCameraView'`, …), then `BUILD FAILED`.

- [ ] **Step 3: Write the feed**

`compose-app/src/main/kotlin/com/shootoff/compose/surface/SurfaceTransform.kt`:

```kotlin
package com.shootoff.compose.surface

import androidx.compose.ui.geometry.Offset
import com.shootoff.geom.Point
import com.shootoff.geom.Size

/**
 * How a surface (a camera feed's canvas, the arena) is drawn in a view: scaled to fit, keeping its
 * aspect ratio, and centered. Surfaces are in their own units (the feed canvas's display size, the arena
 * window's size in dp); views are in pixels.
 */
data class SurfaceTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toView(x: Double, y: Double): Offset = Offset(offsetX + x.toFloat() * scale, offsetY + y.toFloat() * scale)

    fun toSurface(view: Offset): Point = Point(((view.x - offsetX) / scale).toDouble(), ((view.y - offsetY) / scale).toDouble())

    companion object {
        fun fit(surface: Size, viewWidth: Float, viewHeight: Float): SurfaceTransform {
            if (surface.width <= 0 || surface.height <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return SurfaceTransform(1f, 0f, 0f)
            }
            val scale = minOf(viewWidth / surface.width.toFloat(), viewHeight / surface.height.toFloat())
            return SurfaceTransform(
                scale,
                (viewWidth - surface.width.toFloat() * scale) / 2f,
                (viewHeight - surface.height.toFloat() * scale) / 2f,
            )
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedState.kt`:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * A frame to draw, and where it goes on the feed's canvas (the whole canvas, or the arena's projection
 * when the feed is cropped to it).
 */
class FeedFrame(val image: ImageBitmap, val bounds: Rect)

enum class BannerKind { WARNING, INFO, CALIBRATION, ERROR }

data class Banner(val id: Long, val text: String, val kind: BannerKind)

/**
 * What one camera feed shows, for Compose to draw. Written from the camera's thread and others; only the
 * newest frame is kept, so frames never queue behind a slow draw.
 */
class FeedState(val displaySize: Size, private val clock: () -> Long = System::currentTimeMillis) {
    private val frameState = MutableStateFlow<FeedFrame?>(null)
    private val bannerState = MutableStateFlow<List<Banner>>(emptyList())
    private val fpsState = MutableStateFlow(0.0)
    private val frameTimes = ArrayDeque<Long>()
    private val nextBanner = AtomicLong()

    val frame: StateFlow<FeedFrame?> = frameState.asStateFlow()
    val banners: StateFlow<List<Banner>> = bannerState.asStateFlow()

    /** Frames shown in the last second */
    val fps: StateFlow<Double> = fpsState.asStateFlow()

    fun showFrame(frame: FeedFrame) {
        frameState.value = frame
        val now = clock()
        synchronized(frameTimes) {
            frameTimes.addLast(now)
            while (frameTimes.isNotEmpty() && now - frameTimes.first() >= 1000) frameTimes.removeFirst()
            fpsState.value = frameTimes.size.toDouble()
        }
    }

    fun clearFrame() {
        frameState.value = null
        synchronized(frameTimes) {
            frameTimes.clear()
            fpsState.value = 0.0
        }
    }

    fun addBanner(text: String, kind: BannerKind): Banner {
        val banner = Banner(nextBanner.incrementAndGet(), text, kind)
        bannerState.update { it + banner }
        return banner
    }

    fun removeBanner(banner: Banner) {
        bannerState.update { banners -> banners.filterNot { it.id == banner.id } }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt`:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shootoff.camera.CameraManager
import com.shootoff.camera.CameraView
import com.shootoff.camera.DiagnosticMessage
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.geom.ArenaGeometry
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import java.awt.image.BufferedImage
import java.util.Optional

/**
 * Where a camera feed's shots go: the feed surface's shot pipeline (see FeedSurface).
 */
interface FeedShots {
    fun add(shot: ScaledShot)

    fun clear()

    fun reset()

    object None : FeedShots {
        override fun add(shot: ScaledShot) {}

        override fun clear() {}

        override fun reset() {}
    }
}

/**
 * One camera feed in the Compose app, as its [CameraManager] sees it. Frames are converted for Compose on
 * the camera's thread, never the UI thread, and only the newest is kept ([FeedState]).
 */
class ComposeCameraView(
    val name: String,
    val feed: FeedState,
    @Volatile var shots: FeedShots = FeedShots.None,
) : CameraView {
    @Volatile
    var cameraManager: CameraManager? = null
        private set

    override fun setCameraManager(cameraManager: CameraManager) {
        this.cameraManager = cameraManager
    }

    override fun updateBackground(frame: BufferedImage?, projectionBounds: Optional<Rect>) {
        if (frame == null) {
            feed.clearFrame()
            return
        }

        val display = feed.displaySize
        val bounds = projectionBounds.map { toCanvas(it) }.orElse(Rect(0.0, 0.0, display.width, display.height))
        feed.showFrame(FeedFrame(frame.toComposeImageBitmap(), bounds))
    }

    // The projection, which the camera reports on its feed, on the feed's canvas
    private fun toCanvas(cameraBounds: Rect): Rect {
        val camera = cameraManager ?: return cameraBounds
        return ArenaGeometry.cameraToCanvas(cameraBounds, Size(camera.feedWidth.toDouble(), camera.feedHeight.toDouble()), feed.displaySize)
    }

    override fun addShot(shot: ScaledShot) = shots.add(shot)

    override fun addDiagnosticWarning(message: String): DiagnosticMessage {
        val banner = feed.addBanner(message, BannerKind.WARNING)
        return DiagnosticMessage { feed.removeBanner(banner) }
    }

    override fun clearShots() = shots.clear()

    override fun reset() = shots.reset()

    override fun close() = feed.clearFrame()
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/CameraFeedView.kt`:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import kotlin.math.roundToInt

/**
 * A camera feed, fitted to the space it has, with [overlay] drawn over it in the same coordinates
 * (targets, shot markers, the calibration box).
 */
@Composable
fun CameraFeedView(
    feed: FeedState,
    modifier: Modifier = Modifier,
    overlay: @Composable (SurfaceTransform) -> Unit = {},
) {
    val frame by feed.frame.collectAsState()
    val colors = Range.colors
    BoxWithConstraints(
        modifier.background(Brush.radialGradient(listOf(colors.feedCenter, colors.feedEdge))).testTag("camera-feed"),
    ) {
        val transform = SurfaceTransform.fit(feed.displaySize, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize()) {
            val shown = frame ?: return@Canvas
            val topLeft = transform.toView(shown.bounds.minX, shown.bounds.minY)
            drawImage(
                shown.image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(
                    (shown.bounds.width * transform.scale).roundToInt(),
                    (shown.bounds.height * transform.scale).roundToInt(),
                ),
            )
        }
        overlay(transform)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedBanners.kt`:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shootoff.compose.theme.Range

/**
 * The feed's banners (camera warnings, calibration messages, the drill's messages), newest last. Each
 * can be dismissed.
 */
@Composable
fun FeedBanners(feed: FeedState, modifier: Modifier = Modifier) {
    val banners by feed.banners.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (banner in banners) {
            BannerView(banner, onDismiss = { feed.removeBanner(banner) })
        }
    }
}

@Composable
fun BannerView(banner: Banner, onDismiss: () -> Unit) {
    val colors = Range.colors
    val accent = when (banner.kind) {
        BannerKind.WARNING -> colors.warning
        BannerKind.ERROR -> colors.error
        BannerKind.CALIBRATION -> colors.accent
        BannerKind.INFO -> colors.good
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.card.copy(alpha = 0.92f),
        contentColor = colors.text,
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.testTag("banner-${banner.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(banner.text, color = colors.text)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp).testTag("dismiss-${banner.id}")) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = colors.muted)
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/StatusStrip.kt`:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range

enum class CalibrationStatus(val label: String) {
    NO_ARENA("no arena"),
    NEEDS_CALIBRATION("needs calibration"),
    CALIBRATING("calibrating"),
    CALIBRATED("calibrated"),
}

/**
 * What the status strip says about a feed.
 *
 * @param cameraFps the camera's own frame rate
 * @param shownFps how many frames the Compose app drew in the last second
 */
data class FeedStatus(
    val camera: String,
    val cameraFps: Double,
    val shownFps: Double,
    val width: Int,
    val height: Int,
    val calibration: CalibrationStatus,
    val recording: Boolean,
) {
    fun text(): String {
        val parts = mutableListOf(
            camera,
            "%.0f FPS".format(cameraFps),
            "shown %.0f".format(shownFps),
            "${width}×$height",
            calibration.label,
        )
        if (recording) parts += "● REC"
        return parts.joinToString(" · ")
    }
}

@Composable
fun StatusStrip(status: FeedStatus, modifier: Modifier = Modifier) {
    val colors = Range.colors
    val text = buildAnnotatedString {
        append(status.camera)
        append(" · ")
        withStyle(SpanStyle(color = if (status.cameraFps >= 20) colors.good else colors.warning)) {
            append("%.0f FPS".format(status.cameraFps))
        }
        append(" · shown %.0f · ${status.width}×${status.height} · ".format(status.shownFps))
        withStyle(SpanStyle(color = if (status.calibration == CalibrationStatus.CALIBRATED) colors.good else colors.muted)) {
            append(status.calibration.label)
        }
        if (status.recording) {
            append(" · ")
            withStyle(SpanStyle(color = colors.error)) { append("● REC") }
        }
    }
    Text(
        text,
        style = NumberStyle.copy(fontSize = 11.sp),
        color = colors.muted,
        modifier = modifier
            .background(colors.background.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("status-strip"),
    )
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.surface.*' --tests 'com.shootoff.compose.feed.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 14 tests passing. `TestFeedViews.theNewestFrameIsDrawnFittedToTheView` captures the drawn view and finds the red frame's pixel.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `466/466 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/surface/SurfaceTransform.kt compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedState.kt compose-app/src/main/kotlin/com/shootoff/compose/feed/ComposeCameraView.kt compose-app/src/main/kotlin/com/shootoff/compose/feed/CameraFeedView.kt compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedBanners.kt compose-app/src/main/kotlin/com/shootoff/compose/feed/StatusStrip.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/surface/TestSurfaceTransform.kt compose-app/src/test/kotlin/com/shootoff/compose/feed/TestFeedState.kt compose-app/src/test/kotlin/com/shootoff/compose/feed/TestComposeCameraView.kt compose-app/src/test/kotlin/com/shootoff/compose/feed/TestFeedViews.kt
git commit -m "Show the camera feed, its banners and the status strip in Compose"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 5: The target layer: a `TargetSet` drawn from the model

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionLook.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionImages.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionAnimations.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/targets/SurfaceTargets.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetLayer.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/targets/ManualClock.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestRegionLook.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestRegionAnimations.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestSurfaceTargets.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetLayer.kt` (`ManualClock` is a test helper, not a test)

**Interfaces:**
- Consumes:
  - `core`'s `TargetSet` (`add`, `remove`, `getTargets`, `get`, `move`, `setVisible`, `setRegionVisible`, `setImageMask`, `addListener`)
  - `PlacedTarget` (`getId`, `getDefinition`, `getPlacement`, `localToParent`, `isRegionVisible`, `regionBounds`, `getPosition`, `getScaleX/Y`, `getSize`)
  - `Region` records and `Region.isResizable()`
  - `ResourceResolver`, `AlphaMask.of`
  - Task 3's `GifFrames.read`; Task 4's `SurfaceTransform`
- Produces (`com.shootoff.compose.targets`):
  - `regionFill(name: String): Color`, `regionOpacity(Region): Float`, `regionCenter(Region): Point`, `const val DEFAULT_OPACITY = 0.5f`
  - `class RegionImage(frames: List<ImageBitmap>, masks: List<AlphaMask>, cycleMillis: Long)`, with `animated`; `object RegionImages { load(TargetDefinition, ResourceResolver): Map<Int, RegionImage> }`
  - `data class RegionKey(target: TargetId, region: Int)`
  - `fun interface AnimationClock { schedule(delayMillis: Long, step: () -> Unit) }` and `AnimationClock.background`
  - `class RegionAnimations(set: TargetSet, clock: AnimationClock)`:
    - `frames: StateFlow<Map<RegionKey, Int>>`
    - `register`, `unregister`, `isAnimated`, `isOnFirstFrame`, `frameOf`, `play(key, resetAfter = false)`, `reverse`, `reset`, `resetAll`
  - `data class DrawnTarget(id, definition, placement, origin: Point, regionVisible: List<Boolean>)`
  - `class SurfaceTargets(set: TargetSet = TargetSet(), clock: AnimationClock = AnimationClock.background)`:
    - `animations`, `drawn: StateFlow<List<DrawnTarget>>`
    - `add(TargetDefinition, ResourceResolver, Placement = Placement.ORIGIN): PlacedTarget`, `remove(TargetId)`, `image(TargetId, region): RegionImage?`
  - `@Composable TargetLayer(targets: SurfaceTargets, transform: SurfaceTransform, modifier)`

**Drawing as the JavaFX app draws.**
- Shapes are filled in the target editor's eight named colors (or "#rrggbb", else cornsilk, as `TargetEditorController.createColor`) at their `opacity` tag, or 0.5. They have no stroke. Images are opaque.
- A target sits at its placement's translation, scaled about its visible regions' center (`PlacedTarget.localToParent(0, 0)` is its origin). An unresizable region is counter-scaled about its own center.
- Hidden targets and regions are not drawn; hit-testing is `core`'s, unchanged.
- An animated GIF plays like JavaFX's `SpriteAnimation`:
  - one frame per `cycle / frames` step, over the first frame's delay (100 ms without one)
  - a reversed animation plays back to the first frame
  - `reverse` during a play takes effect when the play ends
  - "on its first frame" is the last frame once reversed
- Each frame change hands the frame's `AlphaMask` to the `TargetSet`, which the hit tester reads.
- There is one `SurfaceTargets` per surface, so every view of a surface draws the same frames.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/targets/ManualClock.kt`:

```kotlin
package com.shootoff.compose.targets

/** An [AnimationClock] that runs steps only when told, in time order, so tests see every frame. */
class ManualClock : AnimationClock {
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    var now = 0L
        private set

    @Synchronized
    override fun schedule(delayMillis: Long, step: () -> Unit) {
        pending += (now + delayMillis) to step
    }

    /** Runs the next step; false if there is none. */
    fun runNext(): Boolean {
        val next = synchronized(this) {
            val first = pending.minByOrNull { it.first } ?: return false
            pending.remove(first)
            first
        }
        now = next.first
        next.second()
        return true
    }

    fun runAll() {
        while (runNext()) Unit
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/targets/TestRegionLook.kt`:

```kotlin
package com.shootoff.compose.targets

import androidx.compose.ui.graphics.Color
import com.shootoff.geom.Point
import com.shootoff.targets.model.EllipseRegion
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PolygonRegion
import com.shootoff.targets.model.RectangleRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestRegionLook {
    @Test
    fun fillsAreTheJavaFxAppsColors() {
        assertEquals(Color(0xFF000000), regionFill("black"))
        assertEquals(Color(0xFF8B4513), regionFill("brown"))
        assertEquals(Color(0xFF505050), regionFill("gray"))
        assertEquals(Color(0xFFFFA500), regionFill("orange"))
        assertEquals(Color(0xFF12AB34), regionFill("#12ab34"))
        // Anything else is cornsilk, as in the JavaFX app
        assertEquals(Color(0xFFFFF8DC), regionFill("purple"))
        assertEquals(Color(0xFFFFF8DC), regionFill("#zzzzzz"))
    }

    @Test
    fun shapesAreHalfTransparentUnlessTaggedAndImagesAreOpaque() {
        assertEquals(0.5f, regionOpacity(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf())))
        assertEquals(1f, regionOpacity(EllipseRegion(0, 5.0, 5.0, 5.0, 5.0, "white", mapOf("opacity" to "1"))))
        assertEquals(1f, regionOpacity(ImageRegion(0, 0.0, 0.0, "targets/IPSC.png", 10, 10, mapOf("opacity" to "0.2"))))
    }

    @Test
    fun centersAreTheMiddleOfEachRegionsBounds() {
        assertEquals(Point(15.0, 30.0), regionCenter(RectangleRegion(0, 10.0, 20.0, 10.0, 20.0, "red", mapOf())))
        assertEquals(Point(5.0, 6.0), regionCenter(EllipseRegion(0, 5.0, 6.0, 3.0, 4.0, "red", mapOf())))
        assertEquals(Point(2.0, 3.0), regionCenter(PolygonRegion(0, listOf(Point(0.0, 0.0), Point(4.0, 1.0), Point(1.0, 6.0)), "red", mapOf())))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/targets/TestRegionAnimations.kt`:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class TestRegionAnimations {
    private val clock = ManualClock()
    private val targets = SurfaceTargets(clock = clock)
    private val animations = targets.animations
    private lateinit var popper: RegionKey
    private lateinit var image: RegionImage

    @BeforeEach
    fun addThePepperPopper() {
        val target = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        popper = RegionKey(target.id, 0)
        image = targets.image(target.id, 0)!!
        assertTrue(image.frames.size > 2)
    }

    private fun mask() = targets.set.get(popper.target).get().getImageMask(0).get()

    private val last get() = image.frames.size - 1

    @Test
    fun playingStepsThroughEveryFrameOnceOverOneCycle() {
        assertTrue(animations.isOnFirstFrame(popper))
        assertSame(image.masks[0], mask())

        animations.play(popper)
        clock.runNext()
        assertEquals(1, animations.frameOf(popper))
        assertSame(image.masks[1], mask())

        clock.runAll()

        assertEquals(last, animations.frameOf(popper))
        assertSame(image.masks[last], mask())
        assertFalse(animations.isOnFirstFrame(popper))
        assertEquals(image.cycleMillis / image.frames.size * image.frames.size, clock.now)
    }

    @Test
    fun aReversedAnimationPlaysBackToTheFirstFrame() {
        animations.play(popper)
        clock.runAll()

        animations.reverse(popper)
        // Reversed, the frame it starts from is the last
        assertTrue(animations.isOnFirstFrame(popper))

        animations.play(popper)
        clock.runAll()

        assertEquals(0, animations.frameOf(popper))
        assertSame(image.masks[0], mask())
    }

    @Test
    fun reversingWhilePlayingWaitsForThePlayToFinish() {
        animations.play(popper)
        clock.runNext()

        animations.reverse(popper)
        assertFalse(animations.isOnFirstFrame(popper))
        clock.runAll()

        assertEquals(last, animations.frameOf(popper))
        assertTrue(animations.isOnFirstFrame(popper))
    }

    @Test
    fun resetAfterGoesBackToTheFirstFrameOnceDone() {
        animations.play(popper, resetAfter = true)
        clock.runAll()

        assertEquals(0, animations.frameOf(popper))
        assertTrue(animations.isOnFirstFrame(popper))
    }

    @Test
    fun resetStopsAPlayAndShowsTheFirstFrame() {
        animations.play(popper)
        clock.runNext()
        clock.runNext()

        animations.resetAll()
        clock.runAll()

        assertEquals(0, animations.frameOf(popper))
        assertSame(image.masks[0], mask())
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/targets/TestSurfaceTargets.kt`:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.Optional

class TestSurfaceTargets {
    private val targets = SurfaceTargets(clock = ManualClock())

    private fun box() = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 20.0, "red", mapOf())))

    @Test
    fun anImageTargetIsReadyToHitTestAsSoonAsItIsAdded() {
        val popper = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())

        assertTrue(targets.image(popper.id, 0)!!.animated)
        assertTrue(targets.set.get(popper.id).get().getImageMask(0).isPresent)
        assertEquals(listOf(true, false, false), targets.drawn.value.single().regionVisible)
    }

    @Test
    fun theSnapshotFollowsEveryChangeToTheSet() {
        val target = targets.add(box(), ResourceResolver.files(), Placement(5.0, 6.0, 1.0, 1.0, true))
        assertEquals(Point(5.0, 6.0), targets.drawn.value.single().origin)

        targets.set.move(target.id, 50.0, 60.0)
        assertEquals(Point(50.0, 60.0), targets.drawn.value.single().origin)

        targets.set.setVisible(target.id, false)
        assertFalse(targets.drawn.value.single().placement.visible())

        targets.set.setRegionVisible(target.id, 0, false)
        assertEquals(listOf(false), targets.drawn.value.single().regionVisible)

        targets.remove(target.id)
        assertEquals(emptyList<DrawnTarget>(), targets.drawn.value)
    }

    @Test
    fun anImageThatCantBeReadIsLeftOutButTheTargetStays() {
        val broken = TargetDefinition(Optional.empty(), mapOf(), listOf(ImageRegion(0, 0.0, 0.0, "targets/no_such.png", 10, 10, mapOf())))

        val target = targets.add(broken, ResourceResolver.files())

        assertNull(targets.image(target.id, 0))
        assertEquals(1, targets.drawn.value.size)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetLayer.kt`:

```kotlin
package com.shootoff.compose.targets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.nio.file.Paths
import java.util.Optional

class TestTargetLayer {
    @get:Rule
    val compose = createComposeRule()

    private val clock = ManualClock()
    private val targets = SurfaceTargets(clock = clock)

    private fun square(fill: String, tags: Map<String, String> = mapOf("opacity" to "1")) =
        TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, fill, tags)))

    // The surface drawn 1:1 on a black 640x480 view
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(640.dp, 480.dp).background(Color.Black).testTag("surface")) {
                    TargetLayer(targets, SurfaceTransform.fit(Size(640.0, 480.0), 640f, 480f))
                }
            }
        }
    }

    private fun pixels(): PixelMap = compose.onNodeWithTag("surface").captureToImage().toPixelMap()

    @Test
    fun aShapeIsDrawnWhereItsTargetIsInItsFill() {
        targets.add(square("red"), ResourceResolver.files(), Placement(100.0, 100.0, 1.0, 1.0, true))
        show()

        val pixels = pixels()
        assertEquals(Color.Red, pixels[120, 120])
        assertEquals(Color.Black, pixels[95, 95])
    }

    @Test
    fun shapesWithoutAnOpacityTagAreHalfTransparent() {
        targets.add(square("white", mapOf()), ResourceResolver.files(), Placement(100.0, 100.0, 1.0, 1.0, true))
        show()

        val gray = pixels()[120, 120]
        assertEquals(0.5f, gray.red, 0.02f)
    }

    @Test
    fun hiddenTargetsAndHiddenRegionsAreNotDrawn() {
        val hidden = targets.add(square("red"), ResourceResolver.files(), Placement(100.0, 100.0, 1.0, 1.0, false))
        val regionHidden = targets.add(square("blue"), ResourceResolver.files(), Placement(300.0, 100.0, 1.0, 1.0, true))
        targets.set.setRegionVisible(regionHidden.id, 0, false)
        show()

        val pixels = pixels()
        assertEquals(Color.Black, pixels[120, 120])
        assertEquals(Color.Black, pixels[320, 120])

        targets.set.setVisible(hidden.id, true)
        compose.waitForIdle()
        assertEquals(Color.Red, pixels()[120, 120])
    }

    @Test
    fun aScaledTargetGrowsAboutItsCenter() {
        targets.add(square("red"), ResourceResolver.files(), Placement(100.0, 100.0, 2.0, 2.0, true))
        show()

        // 100..140 doubled about 120 is 80..160
        val pixels = pixels()
        assertEquals(Color.Red, pixels[85, 85])
        assertEquals(Color.Red, pixels[155, 155])
        assertEquals(Color.Black, pixels[75, 75])
    }

    @Test
    fun anAnimatedImageShowsItsCurrentFrame() {
        val popper = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        show()
        val first = pixels()

        targets.animations.play(RegionKey(popper.id, 0))
        clock.runAll()
        compose.waitForIdle()

        // The popper has fallen: the view isn't what it was
        assertFalse(first.buffer.contentEquals(pixels().buffer))
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.targets.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'AnimationClock'` (and `'SurfaceTargets'`, `'regionFill'`, …), then `BUILD FAILED`.

- [ ] **Step 3: Write the target layer**

`compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionLook.kt`:

```kotlin
package com.shootoff.compose.targets

import androidx.compose.ui.graphics.Color
import com.shootoff.geom.Point
import com.shootoff.targets.model.EllipseRegion
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PolygonRegion
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.Region

/** Shapes without an opacity tag are drawn half transparent, as in the JavaFX app. */
const val DEFAULT_OPACITY = 0.5f

private val NAMED_FILLS = mapOf(
    "black" to Color(0xFF000000),
    "blue" to Color(0xFF0000FF),
    // JavaFX's SADDLEBROWN
    "brown" to Color(0xFF8B4513),
    "gray" to Color(0xFF505050),
    "green" to Color(0xFF008000),
    "orange" to Color(0xFFFFA500),
    "red" to Color(0xFFFF0000),
    "white" to Color(0xFFFFFFFF),
)

// JavaFX's CORNSILK, for fills the JavaFX app doesn't know either
private val UNKNOWN_FILL = Color(0xFFFFF8DC)

/**
 * A shape's fill as the JavaFX app draws it: the eight names of the target editor, or a "#rrggbb" code.
 */
fun regionFill(name: String): Color {
    NAMED_FILLS[name]?.let { return it }
    if (name.startsWith("#") && name.length == 7) {
        name.substring(1).toLongOrNull(16)?.let { return Color(0xFF000000 or it) }
    }
    return UNKNOWN_FILL
}

/** A region's opacity: its opacity tag, else [DEFAULT_OPACITY]; images are opaque. */
fun regionOpacity(region: Region): Float {
    if (region is ImageRegion) return 1f
    return region.tag(Region.TAG_OPACITY).map { it.toFloatOrNull() ?: DEFAULT_OPACITY }.orElse(DEFAULT_OPACITY)
}

/** The center of a region's bounds in target coordinates, which an unresizable region keeps its size about. */
fun regionCenter(region: Region): Point = when (region) {
    is EllipseRegion -> Point(region.centerX(), region.centerY())
    is RectangleRegion -> Point(region.x() + region.width() / 2, region.y() + region.height() / 2)
    is ImageRegion -> Point(region.x() + region.imageWidth() / 2.0, region.y() + region.imageHeight() / 2.0)
    is PolygonRegion -> {
        val xs = region.points().map { it.x }
        val ys = region.points().map { it.y }
        Point((xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionImages.kt`:

```kotlin
package com.shootoff.compose.targets

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shootoff.targets.model.AlphaMask
import com.shootoff.targets.model.GifFrames
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.slf4j.LoggerFactory
import java.io.File
import javax.imageio.ImageIO

/**
 * An image region's pixels: one frame, or an animation's frames. [cycleMillis] is how long the JavaFX app
 * takes to play the whole animation once: the first frame's delay, or 100 ms without one.
 */
class RegionImage(val frames: List<ImageBitmap>, val masks: List<AlphaMask>, val cycleMillis: Long) {
    val animated: Boolean get() = frames.size > 1
}

object RegionImages {
    private val logger = LoggerFactory.getLogger(RegionImages::class.java)
    const val DEFAULT_CYCLE_MILLIS = 100L

    /**
     * Reads every image region of a target, by region index. A region whose image can't be read is left
     * out and logged; the target is still drawn.
     */
    fun load(definition: TargetDefinition, resolver: ResourceResolver): Map<Int, RegionImage> {
        val images = mutableMapOf<Int, RegionImage>()
        for (region in definition.regions()) {
            if (region !is ImageRegion) continue
            try {
                read(region, resolver)?.let { images[region.index()] = it }
            } catch (e: Exception) {
                logger.error("Can't read image {} of a target", region.imagePath(), e)
            }
        }
        return images
    }

    private fun read(region: ImageRegion, resolver: ResourceResolver): RegionImage? {
        val path = region.imagePath()
        val stream = resolver.open(path).orElse(null) ?: run {
            logger.error("Can't find image {} of a target", path)
            return null
        }

        stream.use { input ->
            if (isGif(path)) {
                val frames = GifFrames.read(input)
                if (frames.isEmpty()) return null
                val delay = frames[0].delayMillis().toLong().let { if (it < 1) DEFAULT_CYCLE_MILLIS else it }
                return RegionImage(
                    frames.map { it.image().toComposeImageBitmap() },
                    frames.map { AlphaMask.of(it.image()) },
                    delay,
                )
            }

            val image = ImageIO.read(input) ?: return null
            return RegionImage(listOf(image.toComposeImageBitmap()), listOf(AlphaMask.of(image)), DEFAULT_CYCLE_MILLIS)
        }
    }

    // The JavaFX app animates an image when the part of its file name after the first "." ends with "gif"
    private fun isGif(path: String): Boolean {
        val name = File(path).name
        return name.substring(name.indexOf('.') + 1).endsWith("gif")
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionAnimations.kt`:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One image region of one target. */
data class RegionKey(val target: TargetId, val region: Int)

/** Runs animation steps later, on a background thread. */
fun interface AnimationClock {
    fun schedule(delayMillis: Long, step: () -> Unit)

    companion object {
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "Region animations").apply { isDaemon = true }
        }

        val background = AnimationClock { delayMillis, step -> executor.schedule(step, delayMillis, TimeUnit.MILLISECONDS) }
    }
}

/**
 * The animated image regions of one surface's targets: which frame each shows, as the JavaFX app's sprite
 * animations do. Playing steps through the frames once over the image's cycle, toward the last frame, or
 * toward the first once reversed. The current frame is what every view draws and what the hit tester sees
 * (its alpha mask goes to the [TargetSet]).
 */
class RegionAnimations(private val set: TargetSet, private val clock: AnimationClock = AnimationClock.background) {
    private class State(val image: RegionImage) {
        var frame = 0
        var reversed = false
        var running = false
        var generation = 0
        var reverseWhenDone = false
    }

    private val states = HashMap<RegionKey, State>()
    private val frameState = MutableStateFlow<Map<RegionKey, Int>>(emptyMap())

    /** The frame each animated region shows now */
    val frames: StateFlow<Map<RegionKey, Int>> = frameState.asStateFlow()

    /** A target joined the surface: its images start on their first frames. */
    fun register(target: TargetId, images: Map<Int, RegionImage>) {
        synchronized(this) {
            for ((region, image) in images) states[RegionKey(target, region)] = State(image)
        }
        for ((region, image) in images) set.setImageMask(target, region, image.masks[0])
        publish()
    }

    fun unregister(target: TargetId) {
        synchronized(this) { states.keys.removeAll { it.target == target } }
        publish()
    }

    fun isAnimated(key: RegionKey): Boolean = synchronized(this) { states[key]?.image?.animated ?: false }

    /** Whether the region shows the frame an animation starts from: the first, or the last once reversed. */
    fun isOnFirstFrame(key: RegionKey): Boolean = synchronized(this) {
        val state = states[key] ?: return true
        if (!state.image.animated) return true
        state.frame == if (state.reversed) state.image.frames.size - 1 else 0
    }

    fun frameOf(key: RegionKey): Int = synchronized(this) { states[key]?.frame ?: 0 }

    /**
     * Plays the region's animation once. With [resetAfter] it goes back to its first frame when done.
     */
    fun play(key: RegionKey, resetAfter: Boolean = false) {
        val (state, generation) = synchronized(this) {
            val state = states[key] ?: return
            if (!state.image.animated || state.running) return
            state.running = true
            state.generation++
            state to state.generation
        }
        val stepMillis = maxOf(1L, state.image.cycleMillis / state.image.frames.size)
        step(key, state, generation, stepMillis, resetAfter)
    }

    // One frame per step; the step after the last frame ends the play, a whole cycle after it began
    private fun step(key: RegionKey, state: State, generation: Int, stepMillis: Long, resetAfter: Boolean) {
        clock.schedule(stepMillis) {
            val finished: Boolean
            synchronized(this) {
                if (state.generation != generation) return@schedule
                val end = if (state.reversed) 0 else state.image.frames.size - 1
                finished = state.frame == end
                if (finished) {
                    state.running = false
                    if (state.reverseWhenDone) {
                        state.reverseWhenDone = false
                        state.reversed = !state.reversed
                    }
                } else {
                    state.frame += if (state.reversed) -1 else 1
                }
            }
            if (!finished) {
                showFrame(key, state)
                step(key, state, generation, stepMillis, resetAfter)
            } else if (resetAfter) {
                reset(key)
            }
        }
    }

    /**
     * Reverses the region's animation, once the current play finishes if it is playing.
     */
    fun reverse(key: RegionKey) {
        synchronized(this) {
            val state = states[key] ?: return
            if (state.running) state.reverseWhenDone = true else state.reversed = !state.reversed
        }
    }

    /** Stops the region's animation and shows its first frame, as Reset does. */
    fun reset(key: RegionKey) {
        val state = synchronized(this) {
            val state = states[key] ?: return
            state.generation++
            state.running = false
            state.reversed = false
            state.reverseWhenDone = false
            state.frame = 0
            state
        }
        showFrame(key, state)
    }

    fun resetAll() {
        val keys = synchronized(this) { states.keys.toList() }
        keys.forEach(::reset)
    }

    private fun showFrame(key: RegionKey, state: State) {
        val frame = synchronized(this) { state.frame }
        set.setImageMask(key.target, key.region, state.image.masks[frame])
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(this) { states.mapValues { it.value.frame } }
        frameState.update { snapshot }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/targets/SurfaceTargets.kt`:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSet
import com.shootoff.targets.model.TargetSetListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * A target as the views draw it: a snapshot of its placed target.
 *
 * @param origin where the target's (0, 0) is on the surface
 */
data class DrawnTarget(
    val id: TargetId,
    val definition: TargetDefinition,
    val placement: Placement,
    val origin: Point,
    val regionVisible: List<Boolean>,
)

/**
 * The targets on one surface (a camera feed or the arena): the [TargetSet] the shot pipeline hit-tests,
 * their images and animations, and the snapshot every view of the surface draws. There is one per
 * surface however many views show it, so every view shows the same targets.
 */
class SurfaceTargets(val set: TargetSet = TargetSet(), clock: AnimationClock = AnimationClock.background) {
    val animations = RegionAnimations(set, clock)
    private val images = ConcurrentHashMap<TargetId, Map<Int, RegionImage>>()
    private val drawnState = MutableStateFlow<List<DrawnTarget>>(emptyList())

    /** The targets, bottom to top, as the views draw them */
    val drawn: StateFlow<List<DrawnTarget>> = drawnState.asStateFlow()

    init {
        set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) = publish()

            override fun targetRemoved(target: PlacedTarget) {
                images.remove(target.id)
                animations.unregister(target.id)
                publish()
            }

            override fun targetChanged(target: PlacedTarget) = publish()
        })
    }

    /**
     * Adds a target on top, with its images read through [resolver] (the exercise's jar for "@" paths).
     */
    fun add(definition: TargetDefinition, resolver: ResourceResolver, placement: Placement = Placement.ORIGIN): PlacedTarget {
        val loaded = RegionImages.load(definition, resolver)
        val target = set.add(definition, placement)
        images[target.id] = loaded
        animations.register(target.id, loaded)
        publish()
        return target
    }

    fun remove(id: TargetId) = set.remove(id)

    fun image(id: TargetId, region: Int): RegionImage? = images[id]?.get(region)

    private fun publish() {
        drawnState.value = set.targets.map { target ->
            DrawnTarget(
                target.id,
                target.definition,
                target.placement,
                target.localToParent(0.0, 0.0),
                target.definition.regions().indices.map { target.isRegionVisible(it) },
            )
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetLayer.kt`:

```kotlin
package com.shootoff.compose.targets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntSize
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.targets.model.EllipseRegion
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PolygonRegion
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.Region

/**
 * Draws a surface's targets from the model: shapes in their fills and opacity, images on their current
 * animation frame, hidden targets and regions left out, each target placed and scaled as the JavaFX app
 * places it (an unresizable region keeps its size).
 */
@Composable
fun TargetLayer(targets: SurfaceTargets, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val drawn by targets.drawn.collectAsState()
    val frames by targets.animations.frames.collectAsState()
    Canvas(modifier.fillMaxSize()) {
        translate(transform.offsetX, transform.offsetY) {
            scale(transform.scale, transform.scale, pivot = Offset.Zero) {
                for (target in drawn) {
                    if (!target.placement.visible()) continue
                    drawTarget(target, targets, frames)
                }
            }
        }
    }
}

private fun DrawScope.drawTarget(target: DrawnTarget, targets: SurfaceTargets, frames: Map<RegionKey, Int>) {
    val sx = target.placement.scaleX().toFloat()
    val sy = target.placement.scaleY().toFloat()
    translate(target.origin.x.toFloat(), target.origin.y.toFloat()) {
        scale(sx, sy, pivot = Offset.Zero) {
            for (region in target.definition.regions()) {
                if (!target.regionVisible[region.index()]) continue
                if (!region.isResizable && (sx != 1f || sy != 1f)) {
                    val center = regionCenter(region)
                    scale(1 / sx, 1 / sy, pivot = Offset(center.x.toFloat(), center.y.toFloat())) {
                        drawRegion(region, target, targets, frames)
                    }
                } else {
                    drawRegion(region, target, targets, frames)
                }
            }
        }
    }
}

private fun DrawScope.drawRegion(region: Region, target: DrawnTarget, targets: SurfaceTargets, frames: Map<RegionKey, Int>) {
    val alpha = regionOpacity(region)
    when (region) {
        is EllipseRegion -> drawOval(
            regionFill(region.fill()),
            topLeft = Offset((region.centerX() - region.radiusX()).toFloat(), (region.centerY() - region.radiusY()).toFloat()),
            size = Size((2 * region.radiusX()).toFloat(), (2 * region.radiusY()).toFloat()),
            alpha = alpha,
        )
        is RectangleRegion -> drawRect(
            regionFill(region.fill()),
            topLeft = Offset(region.x().toFloat(), region.y().toFloat()),
            size = Size(region.width().toFloat(), region.height().toFloat()),
            alpha = alpha,
        )
        is PolygonRegion -> {
            val path = Path()
            region.points().forEachIndexed { i, point ->
                if (i == 0) path.moveTo(point.x.toFloat(), point.y.toFloat()) else path.lineTo(point.x.toFloat(), point.y.toFloat())
            }
            path.close()
            drawPath(path, regionFill(region.fill()), alpha = alpha)
        }
        is ImageRegion -> {
            val image = targets.image(target.id, region.index()) ?: return
            val frame = frames[RegionKey(target.id, region.index())] ?: 0
            translate(region.x().toFloat(), region.y().toFloat()) {
                drawImage(
                    image.frames[frame.coerceIn(0, image.frames.size - 1)],
                    dstSize = IntSize(region.imageWidth(), region.imageHeight()),
                )
            }
        }
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.targets.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 16 tests passing. The Pepper Popper tests read `targets/` in the repository root (the tests' working directory).

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `482/482 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionLook.kt compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionImages.kt compose-app/src/main/kotlin/com/shootoff/compose/targets/RegionAnimations.kt compose-app/src/main/kotlin/com/shootoff/compose/targets/SurfaceTargets.kt compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetLayer.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/targets/ManualClock.kt compose-app/src/test/kotlin/com/shootoff/compose/targets/TestRegionLook.kt compose-app/src/test/kotlin/com/shootoff/compose/targets/TestRegionAnimations.kt compose-app/src/test/kotlin/com/shootoff/compose/targets/TestSurfaceTargets.kt compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetLayer.kt
git commit -m "Draw a surface's targets in Compose from the model, with image frames and animations"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 6: The shot pipeline's Compose surfaces, one ordered shot timer, markers and region commands

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotTimerModel.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotMarkers.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shots/ArenaPointShot.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotReceiver.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shots/RegionCommandRunner.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shots/Surfaces.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shots/MarkerLayer.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/shots/SurfaceFixture.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/shots/TestShotTimerModel.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/shots/TestRegionCommandRunner.kt` (`SurfaceFixture` is a helper)

**Interfaces:**
- Consumes:
  - `core`'s `ShotPipeline<S>`:
    - `Surface<S>`: `name`, `targets`, `hitTest`, `shotTimer`, `show`, `markerRadius`, `runRegionCommands`, `deliver`, `arena`
    - `Arena<S>`: `projection`, `size`, `toArenaShot`, `addArenaShot`
    - `addShot(S, boolean)`, `addArenaShot(S, Optional<String>, boolean)`
  - `ShotTimer<S>`, `TimerRow.of`, `ExerciseHostSupport.timerRow`, `HitTester.hit`, `PoiAdjustment.offset/apply`, `ExercisePaths.resourceName`, `SoundPlayer.play`, `ShotQueue.shared()`
  - Task 4's `FeedShots`; Task 5's `SurfaceTargets`, `RegionKey`
- Produces (`com.shootoff.compose.shots`):
  - `data class RowView(row: TimerRow, values: Map<String, String> = emptyMap(), highlight: String? = null)`, with `shot`
  - `class ShotTimerModel : ShotTimer<ScaledShot>`:
    - `rows: StateFlow<List<RowView>>`, `columns: StateFlow<List<String>>`
    - `appendShotRow`, `addTimerRow(timeMillis, highlight)`, `setColumnValue(name, value): Boolean`, `styleLastRow(highlight)`, `addColumn`, `removeColumns`, `clear()`
  - `data class Marker(id, x, y, color: ShotColor, radius)`; `class ShotMarkers`:
    - `markers`, `visible` (StateFlows)
    - `add(x, y, color, radius): Marker`, `remove`, `removeAll`, `clear`, `setVisible`
  - `class ArenaPointShot(shot: ScaledShot, arenaX: Double, arenaY: Double) : ScaledShot`
  - `fun interface ShotReceiver { deliver(shot: Shot, hit: Hit?, arenaShot: Boolean): Boolean }` and `ShotReceiver.None`
  - `class RegionCommandRunner(targets, settings, reset: () -> Unit, exerciseResources: () -> ClassLoader?, toCamera: (Point) -> Point = { it }, sounds: Sounds = Sounds.Speakers)`, with `run(shot, hit, mirrored)`
  - `class ArenaSurface(settings, targets: SurfaceTargets, markers: ShotMarkers, size: () -> Size, receiver: () -> ShotReceiver, commands: () -> RegionCommandRunner) : ShotPipeline.Surface<ScaledShot>`, with `pipeline` and `arenaSize()`
  - `class ArenaLink(arena: ArenaSurface, projection: () -> Rect?) : ShotPipeline.Arena<ScaledShot>`
  - `class FeedSurface(name, settings, targets, timer: ShotTimerModel, markers, receiver: () -> ShotReceiver, commands: () -> RegionCommandRunner, openArena: () -> ArenaSurface?, projection: () -> Rect?) : ShotPipeline.Surface<ScaledShot>, FeedShots`
  - `fun markerColor(ShotColor): Color`; `@Composable MarkerLayer(markers, transform, modifier)`

**What the pipeline gets from Compose.** Everything between a detected shot and the exercise is `core`'s `ShotPipeline` (Plan 4). Compose supplies two surfaces:
- **The camera feed** (`FeedSurface`). It has a shot timer, its markers and targets, and the arena its shots go on to inside the calibrated projection (on the feed's canvas).
- **The arena** (`ArenaSurface`). It has no timer (the feed made the row), its own markers and targets, and its size read per shot.

There is one arena model, so no shot is ever a mirrored copy (ruling 6 of Plan 4); Compose passes `mirrored = false`. The shot's type is core's `ScaledShot`, and `ArenaPointShot` carries its arena coordinates.

**The one ordered timer (gap 3, ruling 5).** Shot rows and the exercise's own rows, values and highlights all go through `ShotTimerModel`, under one lock.

**Region commands (ruling 7).** `RegionCommandRunner` follows JavaFX's `TargetCommands` on the model. Its sounds go through a `Sounds` port, so tests hear them.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/shots/SurfaceFixture.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.Shot
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Hit
import java.util.concurrent.CopyOnWriteArrayList

/** A camera feed and the arena wired as the Compose app wires them, with a recording exercise. */
class SurfaceFixture {
    data class Delivered(val shot: Shot, val hit: Hit?, val arenaShot: Boolean)

    val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    val clock = ManualClock()
    val delivered = CopyOnWriteArrayList<Delivered>()
    val resets = CopyOnWriteArrayList<String>()
    var arenaOpen = true
    var projection: Rect? = null

    val arena: ArenaSurface = ArenaSurface(
        settings,
        SurfaceTargets(clock = clock),
        ShotMarkers(),
        { Size(1280.0, 720.0) },
        { receiver },
        { arenaCommands },
    )

    val feed: FeedSurface = FeedSurface(
        "C270",
        settings,
        SurfaceTargets(clock = clock),
        ShotTimerModel(),
        ShotMarkers(),
        { receiver },
        { feedCommands },
        { if (arenaOpen) arena else null },
        { projection },
    )

    private val receiver: ShotReceiver = ShotReceiver { shot, hit, arenaShot ->
        delivered += Delivered(shot, hit, arenaShot)
        true
    }

    private val feedCommands: RegionCommandRunner = RegionCommandRunner(feed.targets, settings, { resets += "reset" }, { null })
    private val arenaCommands: RegionCommandRunner = RegionCommandRunner(arena.targets, settings, { resets += "reset" }, { null })
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/shots/TestShotTimerModel.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class TestShotTimerModel {
    private val timer = ShotTimerModel()

    private fun shot(millis: Long, color: ShotColor = ShotColor.RED) = ScaledShot(color, 1.0, 2.0, millis)

    @Test
    fun rowsReadLikeTheJavaFxShotTimer() {
        timer.appendShotRow(shot(1000), false, false)
        timer.appendShotRow(shot(2500, ShotColor.GREEN), true, false)

        val rows = timer.rows.value
        assertEquals(listOf("%.2f".format(1.0f), "%.2f".format(2.5f)), rows.map { it.row.time() })
        assertEquals(listOf("-", "%.2f".format(1.5f)), rows.map { it.row.split() })
        assertEquals(listOf("red", "green"), rows.map { it.row.laser() })
        assertTrue(rows[1].row.hadMalfunction())
    }

    @Test
    fun anExercisesRowBecomesTheLatestAndTakesItsValues() {
        timer.addColumn("Score")
        timer.appendShotRow(shot(1000), false, false)

        timer.addTimerRow(2500, "coral")
        assertTrue(timer.setColumnValue("Score", "0"))
        timer.appendShotRow(shot(3000), false, false)

        val rows = timer.rows.value
        assertEquals(listOf(null, "coral", null), rows.map { it.highlight })
        assertEquals(listOf(null, "0", null), rows.map { it.values["Score"] })
        assertEquals("red", rows[1].row.laser())
        assertEquals("%.2f".format(0.5f), rows[2].row.split())
    }

    @Test
    fun anEmptyTimerHasNoRowForAValueOrAHighlight() {
        assertFalse(timer.setColumnValue("Score", "10"))
        timer.styleLastRow("coral")

        assertEquals(emptyList<RowView>(), timer.rows.value)
    }

    @Test
    fun columnsComeAndGoAndClearingKeepsThem() {
        timer.addColumn("Length")
        timer.addColumn("Score")
        timer.appendShotRow(shot(1000), false, false)

        timer.clear()
        assertEquals(emptyList<RowView>(), timer.rows.value)
        assertEquals(listOf("Length", "Score"), timer.columns.value)

        timer.removeColumns(listOf("Length", "Score"))
        assertEquals(emptyList<String>(), timer.columns.value)
    }

    @Test
    fun theShotQueueAndTheExerciseWritingAtOnceKeepOneOrder() {
        val start = CountDownLatch(1)
        val shots = thread {
            start.await()
            for (i in 0 until 500) timer.appendShotRow(shot(i * 10L), false, false)
        }
        val exercise = thread {
            start.await()
            for (i in 0 until 500) timer.addTimerRow(i * 10L + 5, "coral")
        }
        start.countDown()
        shots.join()
        exercise.join()

        val rows = timer.rows.value
        assertEquals(1000, rows.size)
        // Every row's split is from the row right before it
        for (i in 1 until rows.size) {
            val split = rows[i].shot.timestamp / 1000f - rows[i - 1].shot.timestamp / 1000f
            assertEquals("%.2f".format(split), rows[i].row.split(), "row $i")
        }
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.targets.RegionKey
import com.shootoff.geom.Rect
import com.shootoff.shots.ShotQueue
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TestSurfaces {
    private val fixture = SurfaceFixture()
    private val feed = fixture.feed
    private val arena = fixture.arena

    private fun box(x: Double, y: Double) = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, "red", mapOf())))
        .let { it to Placement(x, y, 1.0, 1.0, true) }

    @Test
    fun aShotGetsARowAndAMarkerThenReachesTheExercise() {
        feed.add(ScaledShot(ShotColor.RED, 100.0, 120.0, 1000))

        assertEquals(1, feed.timer.rows.value.size)
        assertEquals(listOf(100.0 to 120.0), feed.markers.markers.value.map { it.x to it.y })
        val delivered = fixture.delivered.single()
        assertNull(delivered.hit)
        assertFalse(delivered.arenaShot)
    }

    @Test
    fun aShotOnAFeedTargetHitsIt() {
        val (definition, placement) = box(50.0, 50.0)
        val target = feed.targets.add(definition, ResourceResolver.files(), placement)

        feed.add(ScaledShot(ShotColor.RED, 60.0, 60.0, 1000))

        assertEquals(target.id, fixture.delivered.single().hit!!.targetId())
    }

    @Test
    fun aShotInsideTheProjectionGoesToTheArenaInArenaCoordinates() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        val (definition, placement) = box(620.0, 340.0)
        val target = arena.targets.add(definition, ResourceResolver.files(), placement)

        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))

        val delivered = fixture.delivered.single()
        assertTrue(delivered.arenaShot)
        assertEquals(640.0, delivered.shot.x)
        assertEquals(360.0, delivered.shot.y)
        assertEquals(target.id, delivered.hit!!.targetId())
        // The feed made the row and drew its marker; the arena drew its own
        assertEquals(1, feed.timer.rows.value.size)
        assertEquals(1, feed.markers.markers.value.size)
        assertEquals(listOf(640.0 to 360.0), arena.markers.markers.value.map { it.x to it.y })
    }

    @Test
    fun withoutACalibrationOrAnArenaShotsStayOnTheFeed() {
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
        fixture.arenaOpen = false
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 2000))

        assertEquals(listOf(false, false), fixture.delivered.map { it.arenaShot })
        assertEquals(emptyList<Marker>(), arena.markers.markers.value)
    }

    @Test
    fun twoShotsFromOneFrameMakeTwoRowsInDetectionOrder() {
        val done = CountDownLatch(2)
        for (millis in listOf(1000L, 1004L)) {
            ShotQueue.shared().submit {
                feed.add(ScaledShot(ShotColor.RED, 10.0, 10.0, millis))
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        assertEquals(listOf(1000L, 1004L), feed.timer.rows.value.map { it.shot.timestamp })
    }

    @Test
    fun clearingClearsTheTimerAndBothSurfacesMarkersAndResetStandsTargetsBackUp() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))
        val popper = arena.targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        arena.targets.animations.play(RegionKey(popper.id, 0))
        fixture.clock.runAll()

        feed.reset()

        assertEquals(emptyList<RowView>(), feed.timer.rows.value)
        assertEquals(emptyList<Marker>(), feed.markers.markers.value)
        assertEquals(emptyList<Marker>(), arena.markers.markers.value)
        assertEquals(0, arena.targets.animations.frameOf(RegionKey(popper.id, 0)))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/shots/TestRegionCommandRunner.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.RegionKey
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.sound.SoundPlayer
import com.shootoff.targets.model.AlphaMask
import com.shootoff.targets.model.HitTester
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional

class TestRegionCommandRunner {
    private val clock = ManualClock()
    private val targets = SurfaceTargets(clock = clock)
    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val played = mutableListOf<String>()
    private var resets = 0
    private var loader: ClassLoader? = null
    private val before = ScratchConfig.workingTreeFingerprint()

    private val sounds = object : RegionCommandRunner.Sounds {
        override fun play(file: String) {
            played += file
        }

        override fun play(stream: InputStream) {
            played += "stream:" + stream.use { String(it.readAllBytes()) }
        }
    }

    private val runner = RegionCommandRunner(targets, settings, { resets++ }, { loader }, sounds = sounds)

    @AfterEach
    fun theOwnersSettingsAreUntouched() {
        assertEquals(before, ScratchConfig.workingTreeFingerprint())
    }

    private fun shoot(point: Point) = shoot(point.x, point.y)

    // A point on the region where its first frame isn't transparent, so the shot hits it
    private fun opaquePoint(bounds: Rect, mask: AlphaMask): Point {
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask.isOpaque(x, y)) return Point(bounds.minX + x + 0.5, bounds.minY + y + 0.5)
        }
        throw AssertionError("The region is transparent")
    }

    private fun shoot(x: Double, y: Double) {
        val hit = HitTester.hit(targets.set, x, y).get()
        runner.run(ScaledShot(ShotColor.RED, x, y, 1000), hit, false)
    }

    @Test
    fun aPepperPopperFallsOnceAndRingsOnlyWhileStanding() {
        val popper = targets.add(TargetDefinitions.load(Paths.get("targets/Pepper_Popper.target")), ResourceResolver.files())
        val image = RegionKey(popper.id, 0)

        // Its hidden plate region names the popper image: animate(pepper_popper) and the steel sound
        shoot(266.0, 60.0)
        assertEquals(listOf("sounds/steel_sound_1.wav"), played)
        clock.runAll()
        assertFalse(targets.animations.isOnFirstFrame(image))

        // Fallen: the next hit neither animates it again nor rings
        played.clear()
        shoot(266.0, 60.0)
        assertEquals(emptyList<String>(), played)
    }

    @Test
    fun aDuelingTreePaddleSwingsAndIsReversedForItsNextHit() {
        val tree = targets.add(TargetDefinitions.load(Paths.get("targets/Duel_Tree.target")), ResourceResolver.files())
        val paddle = RegionKey(tree.id, 1)
        shoot(opaquePoint(tree.regionBounds(tree.definition.regions()[1]), targets.image(tree.id, 1)!!.masks[0]))
        clock.runAll()

        // animate, then reverse once the swing is done: it starts from its last frame next time
        assertTrue(targets.animations.isOnFirstFrame(paddle))
        assertTrue(targets.animations.frameOf(paddle) > 0)
        assertEquals(listOf("sounds/steel_sound_1.wav"), played)
    }

    @Test
    fun resetDoesWhatResetDoes() {
        targets.add(command("reset"), ResourceResolver.files())

        shoot(5.0, 5.0)

        assertEquals(1, resets)
    }

    @Test
    fun anAtSoundComesFromTheRunningExercisesJar(@TempDir jar: Path) {
        Files.createDirectories(jar.resolve("sounds"))
        Files.write(jar.resolve("sounds/cue.wav"), "cue".toByteArray())
        loader = URLClassLoader(arrayOf(jar.toUri().toURL()), null)
        targets.add(command("play_sound(@sounds/cue.wav)"), ResourceResolver.files())

        shoot(5.0, 5.0)

        assertEquals(listOf("stream:cue"), played)
    }

    @Test
    fun fivePoiHitsSetTheAdjustmentAndASixthTurnsItOff() {
        val wasSilenced = SoundPlayer.isSilenced()
        SoundPlayer.silence(true)
        try {
            targets.add(command("poi_adjust", x = 100.0, y = 100.0), ResourceResolver.files())

            // 5 right and 2 down of the region's center (110, 110)
            repeat(5) { shoot(115.0, 112.0) }
            assertTrue(settings.isAdjustingPOI)
            assertEquals(Optional.of(-5.0), settings.poiAdjustmentX)
            assertEquals(Optional.of(-2.0), settings.poiAdjustmentY)

            shoot(115.0, 112.0)
            assertFalse(settings.isAdjustingPOI)
        } finally {
            SoundPlayer.silence(wasSilenced)
        }
    }

    private fun command(command: String, x: Double = 0.0, y: Double = 0.0) = TargetDefinition(
        Optional.empty(),
        mapOf(),
        listOf(RectangleRegion(0, x, y, 20.0, 20.0, "red", mapOf("command" to command))),
    )
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.shots.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'ArenaSurface'` (and `'FeedSurface'`, `'ShotTimerModel'`, …), then `BUILD FAILED`.

- [ ] **Step 3: Write the surfaces**

`compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotTimerModel.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.Shot
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.exercise.host.ExerciseHostSupport
import com.shootoff.shots.ShotTimer
import com.shootoff.shots.TimerRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Optional

/**
 * One shot timer row as the table shows it.
 *
 * @param values the running exercise's columns' values
 * @param highlight the row's highlight color as the exercise named it (e.g. "coral"), if any
 */
data class RowView(val row: TimerRow, val values: Map<String, String> = emptyMap(), val highlight: String? = null) {
    val shot: Shot get() = row.shot()
}

/**
 * The shot timer: the one ordered list of rows that the shot pipeline (a row per shot, on the shot
 * queue's thread) and the running exercise (its own rows, values and highlights, on its thread) both
 * write. Every change happens under one lock, in the order it was asked for, and publishes the whole new
 * list, so a value always lands on the row that was latest when the exercise set it.
 */
class ShotTimerModel : ShotTimer<ScaledShot> {
    private val lock = Any()
    private val rowState = MutableStateFlow<List<RowView>>(emptyList())
    private val columnState = MutableStateFlow<List<String>>(emptyList())

    val rows: StateFlow<List<RowView>> = rowState.asStateFlow()

    /** The running exercise's columns, after Time, Split and Laser */
    val columns: StateFlow<List<String>> = columnState.asStateFlow()

    override fun appendShotRow(shot: ScaledShot, hadMalfunction: Boolean, hadReload: Boolean) {
        synchronized(lock) {
            val rows = rowState.value
            rowState.value = rows + RowView(TimerRow.of(shot, previous(rows), hadMalfunction, hadReload))
        }
    }

    /** Adds a row no shot made (see ExerciseHost.addTimerRow) and makes it the latest. */
    fun addTimerRow(timeMillis: Long, highlight: String) {
        synchronized(lock) {
            val rows = rowState.value
            rowState.value = rows + RowView(ExerciseHostSupport.timerRow(timeMillis, previous(rows)), highlight = highlight)
        }
    }

    /** @return false if there is no row to set it on */
    fun setColumnValue(name: String, value: String): Boolean = synchronized(lock) {
        val rows = rowState.value
        if (rows.isEmpty()) return false
        val last = rows.last()
        rowState.value = rows.dropLast(1) + last.copy(values = last.values + (name to value))
        true
    }

    fun styleLastRow(highlight: String) {
        synchronized(lock) {
            val rows = rowState.value
            if (rows.isEmpty()) return
            rowState.value = rows.dropLast(1) + rows.last().copy(highlight = highlight)
        }
    }

    fun addColumn(name: String) {
        synchronized(lock) { columnState.value = columnState.value + name }
    }

    fun removeColumns(names: Collection<String>) {
        synchronized(lock) {
            val remaining = columnState.value.toMutableList()
            for (name in names) remaining.remove(name)
            columnState.value = remaining
        }
    }

    fun clear() {
        synchronized(lock) { rowState.value = emptyList() }
    }

    private fun previous(rows: List<RowView>): Optional<Shot> = Optional.ofNullable(rows.lastOrNull()?.shot)
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotMarkers.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.shot.ShotColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class Marker(val id: Long, val x: Double, val y: Double, val color: ShotColor, val radius: Int)

/** The shot markers on one surface, in the surface's coordinates. */
class ShotMarkers {
    private val next = AtomicLong()
    private val markerState = MutableStateFlow<List<Marker>>(emptyList())
    private val visibleState = MutableStateFlow(true)

    val markers: StateFlow<List<Marker>> = markerState.asStateFlow()

    /** The arena hides its markers unless the user wants them (shootoff.arena.show.markers) */
    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    fun add(x: Double, y: Double, color: ShotColor, radius: Int): Marker {
        val marker = Marker(next.incrementAndGet(), x, y, color, radius)
        markerState.update { it + marker }
        return marker
    }

    fun remove(marker: Marker) = markerState.update { markers -> markers.filterNot { it.id == marker.id } }

    fun removeAll(gone: Collection<Marker>) {
        val ids = gone.map { it.id }.toSet()
        markerState.update { markers -> markers.filterNot { it.id in ids } }
    }

    fun clear() = markerState.update { emptyList() }

    fun setVisible(visible: Boolean) {
        visibleState.value = visible
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/shots/ArenaPointShot.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot

/** A camera feed's shot passed on to the arena, at its point in arena coordinates. */
class ArenaPointShot(shot: ScaledShot, private val arenaX: Double, private val arenaY: Double) : ScaledShot(shot) {
    override fun getX(): Double = arenaX

    override fun getY(): Double = arenaY
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotReceiver.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.Shot
import com.shootoff.targets.model.Hit

/**
 * Whoever runs the current exercise: shots end there (see ExerciseRunner).
 */
fun interface ShotReceiver {
    /**
     * @param arenaShot true for a shot on the arena, in arena coordinates
     * @return true if an exercise took the shot
     */
    fun deliver(shot: Shot, hit: Hit?, arenaShot: Boolean): Boolean

    companion object {
        val None = ShotReceiver { _, _, _ -> false }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/shots/RegionCommandRunner.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.compose.targets.RegionKey
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.exercise.ExercisePaths
import com.shootoff.geom.Point
import com.shootoff.shots.PoiAdjustment
import com.shootoff.sound.SoundPlayer
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.Region
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Carries out a hit region's commands on the Compose app's model, as the JavaFX app's TargetCommands does
 * on its nodes: <tt>reset</tt>, <tt>animate</tt>, <tt>reverse</tt>, <tt>play_sound</tt> and
 * <tt>poi_adjust</tt>.
 *
 * @param reset what Reset does (see RangeReset)
 * @param exerciseResources the running exercise's class loader, for "@" sounds from its jar
 * @param toCamera maps a point on this surface to the camera feed, for <tt>poi_adjust</tt>
 */
class RegionCommandRunner(
    private val targets: SurfaceTargets,
    private val settings: Settings,
    private val reset: () -> Unit,
    private val exerciseResources: () -> ClassLoader?,
    private val toCamera: (Point) -> Point = { it },
    private val sounds: Sounds = Sounds.Speakers,
) {
    interface Sounds {
        fun play(file: String)

        fun play(stream: InputStream)

        object Speakers : Sounds {
            override fun play(file: String) = SoundPlayer.play(file)

            override fun play(stream: InputStream) = SoundPlayer.play(stream)
        }
    }

    private val logger = LoggerFactory.getLogger(RegionCommandRunner::class.java)

    /**
     * @param mirrored true for a copy of a shot another surface handles (never in the Compose app)
     */
    fun run(shot: ScaledShot, hit: Hit, mirrored: Boolean) {
        for (command in hit.region().commands()) {
            when (command.name()) {
                "reset" -> reset()
                "animate" -> animate(hit, command.args())
                "reverse" -> reverse(hit)
                "play_sound" -> playSound(hit, command.args())
                "poi_adjust" -> if (!mirrored) poiAdjust(shot, hit)
            }
        }
    }

    private fun animate(hit: Hit, args: List<String>) {
        val resetAfter = args.firstOrNull() == "true"
        val region = if (args.isEmpty() || resetAfter) hit.region() else named(hit, args[0])
        if (region == null) {
            logger.error("Request to animate region named {}, but it doesn't exist.", args[0])
            return
        }

        val key = RegionKey(hit.targetId(), region.index())
        // Don't repeat animations for fallen targets
        if (!targets.animations.isOnFirstFrame(key)) return

        if (targets.animations.isAnimated(key)) {
            targets.animations.play(key, resetAfter)
        } else {
            logger.error("Request to animate region, but region does not contain an animation.")
        }
    }

    private fun reverse(hit: Hit) {
        if (hit.region() !is ImageRegion) {
            logger.error("A reversal was requested on a non-image region.")
            return
        }

        val key = RegionKey(hit.targetId(), hit.region().index())
        if (targets.animations.isAnimated(key)) {
            targets.animations.reverse(key)
        } else {
            logger.error("A reversal was requested on an image region that isn't animated.")
        }
    }

    private fun playSound(hit: Hit, args: List<String>) {
        // With a second argument, stay quiet if that image region has already fallen
        if (args.size == 2) {
            val named = named(hit, args[1])
            if (named is ImageRegion && !targets.animations.isOnFirstFrame(RegionKey(hit.targetId(), named.index()))) return
        }

        val sound = args.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return
        if (sound[0] != '@') {
            sounds.play(sound)
            return
        }

        // A sound in the running exercise's jar
        val loader = exerciseResources()
        val stream = loader?.getResourceAsStream(ExercisePaths.resourceName(sound))
        if (stream == null) {
            logger.error("Can't play {} because it is a resource in an exercise but no exercise is loaded.", sound)
            return
        }
        sounds.play(BufferedInputStream(stream))
    }

    private fun poiAdjust(shot: ScaledShot, hit: Hit) {
        val region = hit.region() as? RectangleRegion ?: return
        val target = targets.set.get(hit.targetId()).orElse(null) ?: return

        // The region's center on this surface, then on the camera feed, as the JavaFX app measures it:
        // the target's position plus the region's own center, before the target's scale
        val position = target.position
        val center = toCamera(Point(position.x + region.x() + region.width() / 2.0, position.y + region.y() + region.height() / 2.0))

        val offset = PoiAdjustment.offset(center, Point(shot.boundsX, shot.boundsY), target.scaleX, target.scaleY)
        PoiAdjustment.apply(settings, offset)
    }

    // A region of the hit target with this name tag
    private fun named(hit: Hit, name: String): Region? {
        val target = targets.set.get(hit.targetId()).orElse(null) ?: return null
        return target.definition.regions().firstOrNull { it.tag("name").orElse(null) == name }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/shots/Surfaces.kt`:

```kotlin
package com.shootoff.compose.shots

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.compose.feed.FeedShots
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.shots.ShotPipeline
import com.shootoff.shots.ShotTimer
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.HitTester
import com.shootoff.targets.model.TargetSet
import java.util.Optional

/**
 * The projector arena as the shot pipeline sees it: its targets, its markers and its size. Shots reach it
 * from the calibrated camera feed, already in arena coordinates, and have no timer row of their own (the
 * feed added it). The Compose app has one arena model, so no shot here is ever a mirrored copy.
 */
class ArenaSurface(
    private val settings: Settings,
    val targets: SurfaceTargets,
    val markers: ShotMarkers,
    private val size: () -> Size,
    private val receiver: () -> ShotReceiver,
    private val commands: () -> RegionCommandRunner,
) : ShotPipeline.Surface<ScaledShot> {
    val pipeline = ShotPipeline(this, settings)

    fun arenaSize(): Size = size()

    override fun name(): String = "arena"

    override fun targets(): TargetSet = targets.set

    override fun hitTest(x: Double, y: Double): Optional<Hit> = HitTester.hit(targets.set, x, y)

    override fun shotTimer(): Optional<ShotTimer<ScaledShot>> = Optional.empty()

    override fun show(shot: ScaledShot) {
        markers.add(shot.x, shot.y, shot.color, settings.markerRadius)
    }

    override fun markerRadius(shot: ScaledShot): Int = settings.markerRadius

    override fun runRegionCommands(shot: ScaledShot, hit: Hit, mirrored: Boolean) = commands().run(shot, hit, mirrored)

    override fun deliver(shot: ScaledShot, hit: Optional<Hit>, arenaShot: Boolean): Boolean =
        receiver().deliver(shot, hit.orElse(null), arenaShot)

    override fun arena(): Optional<ShotPipeline.Arena<ScaledShot>> = Optional.empty()
}

/**
 * The arena as a camera feed passes shots on to it: shots inside the calibrated projection (on the feed's
 * canvas) go to the arena in arena coordinates.
 */
class ArenaLink(private val arena: ArenaSurface, private val projection: () -> Rect?) : ShotPipeline.Arena<ScaledShot> {
    override fun projection(): Optional<Rect> = Optional.ofNullable(projection.invoke())

    override fun size(): Size = arena.arenaSize()

    override fun toArenaShot(shot: ScaledShot, arenaPoint: Point): ScaledShot = ArenaPointShot(shot, arenaPoint.x, arenaPoint.y)

    override fun addArenaShot(shot: ScaledShot, videoString: Optional<String>, mirrored: Boolean): Boolean =
        arena.pipeline.addArenaShot(shot, videoString, mirrored)
}

/**
 * A camera feed as the shot pipeline sees it: its targets, the shot timer, its markers, and the arena its
 * shots may go on to. Its camera hands it detected shots on the shot queue's thread ([FeedShots]).
 */
class FeedSurface(
    private val name: String,
    private val settings: Settings,
    val targets: SurfaceTargets,
    val timer: ShotTimerModel,
    val markers: ShotMarkers,
    private val receiver: () -> ShotReceiver,
    private val commands: () -> RegionCommandRunner,
    private val openArena: () -> ArenaSurface?,
    private val projection: () -> Rect?,
) : ShotPipeline.Surface<ScaledShot>, FeedShots {
    val pipeline = ShotPipeline(this, settings)

    // ---- From the camera

    override fun add(shot: ScaledShot) = pipeline.addShot(shot, false)

    /** Clears the feed's markers and the shot timer, and the arena's markers, as the JavaFX app does. */
    override fun clear() {
        markers.clear()
        timer.clear()
        openArena()?.markers?.clear()
    }

    /** Puts the feed's targets' animations back to their first frames, and the arena's, then clears. */
    override fun reset() {
        targets.animations.resetAll()
        openArena()?.targets?.animations?.resetAll()
        clear()
    }

    // ---- The pipeline's view of the feed

    override fun name(): String = name

    override fun targets(): TargetSet = targets.set

    override fun hitTest(x: Double, y: Double): Optional<Hit> = HitTester.hit(targets.set, x, y)

    override fun shotTimer(): Optional<ShotTimer<ScaledShot>> = Optional.of(timer)

    override fun show(shot: ScaledShot) {
        markers.add(shot.x, shot.y, shot.color, settings.markerRadius)
    }

    override fun markerRadius(shot: ScaledShot): Int = settings.markerRadius

    override fun runRegionCommands(shot: ScaledShot, hit: Hit, mirrored: Boolean) = commands().run(shot, hit, mirrored)

    override fun deliver(shot: ScaledShot, hit: Optional<Hit>, arenaShot: Boolean): Boolean =
        receiver().deliver(shot, hit.orElse(null), arenaShot)

    /** While the arena is open, shots inside its calibrated projection go on to it */
    override fun arena(): Optional<ShotPipeline.Arena<ScaledShot>> =
        Optional.ofNullable(openArena()?.let { ArenaLink(it, projection) })
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/shots/MarkerLayer.kt`:

```kotlin
package com.shootoff.compose.shots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.surface.SurfaceTransform

/** A marker's fill, as the JavaFX app paints it */
fun markerColor(color: ShotColor): Color = when (color) {
    ShotColor.RED -> Color(0xFFFF0000)
    ShotColor.GREEN -> Color(0xFF008000)
    ShotColor.INFRARED -> Color(0xFFFFA500)
}

@Composable
fun MarkerLayer(markers: ShotMarkers, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val shown by markers.markers.collectAsState()
    val visible by markers.visible.collectAsState()
    if (!visible) return
    Canvas(modifier.fillMaxSize()) {
        for (marker in shown) {
            drawCircle(markerColor(marker.color), marker.radius * transform.scale, transform.toView(marker.x, marker.y))
        }
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.shots.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 16 tests passing. The POI test prints the silenced beeps to its output.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `498/498 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotTimerModel.kt compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotMarkers.kt compose-app/src/main/kotlin/com/shootoff/compose/shots/ArenaPointShot.kt compose-app/src/main/kotlin/com/shootoff/compose/shots/ShotReceiver.kt compose-app/src/main/kotlin/com/shootoff/compose/shots/RegionCommandRunner.kt compose-app/src/main/kotlin/com/shootoff/compose/shots/Surfaces.kt compose-app/src/main/kotlin/com/shootoff/compose/shots/MarkerLayer.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/shots/SurfaceFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/shots/TestShotTimerModel.kt compose-app/src/test/kotlin/com/shootoff/compose/shots/TestSurfaces.kt compose-app/src/test/kotlin/com/shootoff/compose/shots/TestRegionCommandRunner.kt
git commit -m "Give the Compose app shot pipeline surfaces, one ordered shot timer, markers and region commands"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 7: The arena: one model, the projector window and the in-app view

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaScreens.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaModel.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaScreens.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt`

**Interfaces:**
- Consumes:
  - Task 3's `ProjectorScreens.choose`
  - Task 5's `SurfaceTargets`, `TargetLayer`
  - Task 6's `ArenaSurface`, `ShotMarkers`, `MarkerLayer`, `ShotReceiver`, `RegionCommandRunner`
  - `PerspectiveManager.isInitialized()` and `calculateObjectSize(w, h, distance)`, `TargetDefinition.defaultPerception()` and `fillsCanvas()`
  - `Settings.showArenaShotMarkers()`
- Produces (`com.shootoff.compose.arena`):
  - `class ArenaBackground(image: ImageBitmap, name: String)` with `companion read(InputStream, name): ArenaBackground?`
  - `class ArenaModel(settings, receiver: () -> ShotReceiver, commands: () -> RegionCommandRunner, clock: AnimationClock = AnimationClock.background)`:
    - its parts: `targets`, `markers`, `surface: ArenaSurface`, `var perspective: PerspectiveManager?`
    - its state: `size`, `background`, `projection: StateFlow<Rect?>`, `fullScreen`, `needsCalibrationLabel`
    - `setSize`, `setFullScreen`, `setBackground`, `showResource(name: String?)`, `setProjection`, `setCalibrationLabelVisible`, `setTargetsVisible`, `showShots`, `placeNewTarget(PlacedTarget)`
  - `data class ArenaPlacement(screen: Rect?, position: Point)`
  - `object ArenaScreens { screens(): List<Rect>; place(screens, appScreen: Int, savedPosition: Point?): ArenaPlacement; screenAt(screens, point): Int }`
  - `@Composable ArenaCanvas(arena, modifier, overlay)` and `@Composable ArenaView(arena, modifier, overlay)`
  - `@Composable ArenaWindow(arena, placement, onCloseRequest, overlay)`; Task 13 adds an `onKey` parameter

**One model, two views (spec §3).**
- The projector window and the in-app Arena view both draw the one `ArenaModel`, so there is no mirrored copy to get out of step.
- Each fits the arena, whose size is the window's content size in dp, into its space:
  - a gray (#333333) arena
  - its background stretched over it, as JavaFX's fitted `ImageView`
  - its targets and markers
  - "Needs Calibration" in orange until calibrated, as JavaFX's label
  - then the exercise's overlay
- The arena's markers follow `shootoff.arena.show.markers` (the owner's is `false`).
- `placeNewTarget` fits a new target as JavaFX's `ProjectorArenaPane.targetAdded` does: real-world size once the perspective is known, and "fill the canvas".

**The window (ruling 8).** It opens at the chosen screen's corner plus 10 px, or at the saved arena position. It waits until AWT reports it there, then goes full screen. F11 toggles full screen. Its content size is the arena's size.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaModel.kt`:

```kotlin
package com.shootoff.compose.arena

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shots.FeedSurface
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestArenaModel {
    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private lateinit var arena: ArenaModel

    private fun arena(): ArenaModel {
        arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        return arena
    }

    private fun box(tags: Map<String, String> = mapOf()) =
        TargetDefinition(Optional.empty(), tags, listOf(RectangleRegion(0, 0.0, 0.0, 100.0, 50.0, "red", mapOf())))

    @Test
    fun theCalibrationPatternAndTheWhiteScreenAreShootoffsOwnImages() {
        val arena = arena()

        arena.showResource("pattern.png")
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(arena.background.value!!.image.width > 0)

        arena.showResource("white.png")
        assertEquals("white.png", arena.background.value!!.name)

        // An image ShootOFF doesn't have leaves the background as it was
        arena.showResource("no_such.png")
        assertEquals("white.png", arena.background.value!!.name)

        arena.showResource(null)
        assertNull(arena.background.value)
    }

    @Test
    fun calibrationHidesAndShowsEveryTarget() {
        val arena = arena()
        val first = arena.targets.add(box(), ResourceResolver.files())
        val second = arena.targets.add(box(), ResourceResolver.files(), Placement(200.0, 0.0, 1.0, 1.0, false))

        arena.setTargetsVisible(false)
        assertFalse(arena.targets.set.get(first.id).get().isVisible)

        arena.setTargetsVisible(true)
        assertTrue(arena.targets.set.get(first.id).get().isVisible)
        assertTrue(arena.targets.set.get(second.id).get().isVisible)
    }

    @Test
    fun aTargetThatFillsTheCanvasIsStretchedOverTheArena() {
        val arena = arena()
        arena.setSize(Size(1280.0, 720.0))
        val poi = arena.targets.add(box(mapOf("fillCanvas" to "true")), ResourceResolver.files())

        arena.placeNewTarget(poi)

        val placed = arena.targets.set.get(poi.id).get()
        assertEquals(1280.0, placed.size.width, 0.01)
        assertEquals(720.0, placed.size.height, 0.01)
    }

    @Test
    fun aShotAfterTheArenaChangedSizeIsScaledToItsNewSize() {
        val arena = arena()
        val feed = FeedSurface(
            "Default", settings, SurfaceTargets(clock = ManualClock()), ShotTimerModel(), ShotMarkers(),
            { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) },
            { arena.surface }, { Rect(100.0, 100.0, 320.0, 180.0) },
        )
        arena.setSize(Size(1280.0, 720.0))

        // The arena leaves full screen: 640x360 now
        arena.setSize(Size(640.0, 360.0))
        feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 1000))

        assertEquals(listOf(320.0 to 180.0), arena.markers.markers.value.map { it.x to it.y })
    }

    @Test
    fun arenaMarkersFollowTheShowMarkersSetting() {
        settings.setShowArenaShotMarkers(false)

        assertFalse(arena().markers.visible.value)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaScreens.kt`:

```kotlin
package com.shootoff.compose.arena

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestArenaScreens {
    // AWT's order on the owner's desk: DP-1 (ShootOFF), DP-2, then the projector on HDMI-1
    private val owner = listOf(Rect(1920.0, 0.0, 2560.0, 1440.0), Rect(0.0, 0.0, 1920.0, 1080.0), Rect(4480.0, 0.0, 1280.0, 720.0))

    @Test
    fun theOwnersProjectorGetsTheArenaTenPixelsIn() {
        assertEquals(ArenaPlacement(owner[2], Point(4490.0, 10.0)), ArenaScreens.place(owner, 0, null))
    }

    @Test
    fun aSavedPositionOnAnotherScreenIsUsedAsIs() {
        assertEquals(ArenaPlacement(owner[1], Point(100.0, 200.0)), ArenaScreens.place(owner, 0, Point(100.0, 200.0)))
    }

    @Test
    fun withOneScreenTheArenaOpensInAWindowOnIt() {
        assertEquals(ArenaPlacement(null, Point(10.0, 10.0)), ArenaScreens.place(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)), 0, null))
    }

    @Test
    fun theMainWindowsScreenIsTheOneItsCornerIsOn() {
        assertEquals(0, ArenaScreens.screenAt(owner, Point(2000.0, 50.0)))
        assertEquals(2, ArenaScreens.screenAt(owner, Point(4500.0, 50.0)))
        assertEquals(0, ArenaScreens.screenAt(owner, Point(-500.0, 50.0)))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt`:

```kotlin
package com.shootoff.compose.arena

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.util.Optional

class TestArenaViews {
    @get:Rule
    val compose = createComposeRule()

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private lateinit var arena: ArenaModel

    @Before
    fun setUp() {
        arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        arena.setSize(Size(1280.0, 720.0))
    }

    // The projector window's view (640x360) and the in-app view (320x180) of the one arena
    private fun showBothViews() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column {
                    ArenaCanvas(arena, Modifier.size(640.dp, 360.dp).testTag("projector"))
                    ArenaView(arena, Modifier.size(320.dp, 180.dp).testTag("in-app"))
                }
            }
        }
    }

    private fun pixels(tag: String): PixelMap = compose.onNodeWithTag(tag).captureToImage().toPixelMap()

    @Test
    fun bothViewsDrawTheOneArenasTargets() {
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 80.0, 80.0, "red", mapOf("opacity" to "1")))),
            ResourceResolver.files(),
            Placement(600.0, 300.0, 1.0, 1.0, true),
        )
        arena.setCalibrationLabelVisible(false)
        showBothViews()

        // The target's center (640, 340) at each view's scale
        assertEquals(Color.Red, pixels("projector")[320, 170])
        assertEquals(Color.Red, pixels("in-app")[160, 85])

        arena.targets.set.setVisible(target.id, false)
        compose.waitForIdle()

        assertEquals(Color(0xFF333333), pixels("projector")[320, 170])
        assertEquals(Color(0xFF333333), pixels("in-app")[160, 85])
    }

    @Test
    fun theBackgroundIsStretchedOverTheWholeArena() {
        val blue = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0 until 10) for (y in 0 until 10) setRGB(x, y, 0x0000FF)
        }
        arena.setBackground(ArenaBackground(blue.toComposeImageBitmap(), "blue"))
        arena.setCalibrationLabelVisible(false)
        showBothViews()

        val pixels = pixels("projector")
        assertEquals(Color.Blue, pixels[2, 2])
        assertEquals(Color.Blue, pixels[637, 357])
    }

    @Test
    fun theArenaSaysItNeedsCalibrationUntilItIsCalibrated() {
        showBothViews()
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(2)

        arena.setCalibrationLabelVisible(false)
        compose.waitForIdle()
        compose.onAllNodesWithTag("needs-calibration").assertCountEquals(0)
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.arena.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'ArenaModel'` (and `'ArenaScreens'`, `'ArenaCanvas'`, …), then `BUILD FAILED`.

- [ ] **Step 3: Write the arena**

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`:

```kotlin
package com.shootoff.compose.arena

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shootoff.camera.perspective.PerspectiveManager
import com.shootoff.compose.shots.ArenaSurface
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * An arena background.
 *
 * @param name where it came from (a resource name), for logs
 */
class ArenaBackground(val image: ImageBitmap, val name: String) {
    companion object {
        fun read(stream: InputStream, name: String): ArenaBackground? =
            stream.use { ImageIO.read(it) }?.let { ArenaBackground(it.toComposeImageBitmap(), name) }
    }
}

/**
 * The projector arena: one model that both the projector window and the in-app Arena view draw, so
 * whatever an exercise does to it (a target hidden, a background set) shows on both.
 */
class ArenaModel(
    private val settings: Settings,
    receiver: () -> ShotReceiver,
    commands: () -> RegionCommandRunner,
    clock: AnimationClock = AnimationClock.background,
) {
    private val logger = LoggerFactory.getLogger(ArenaModel::class.java)

    val targets = SurfaceTargets(clock = clock)
    val markers = ShotMarkers().also { it.setVisible(settings.showArenaShotMarkers()) }

    private val sizeState = MutableStateFlow(Size(640.0, 480.0))
    private val backgroundState = MutableStateFlow<ArenaBackground?>(null)
    private val projectionState = MutableStateFlow<Rect?>(null)
    private val fullScreenState = MutableStateFlow(false)
    private val labelState = MutableStateFlow(true)

    /** The arena window's size, in dp: the arena's coordinates */
    val size: StateFlow<Size> = sizeState.asStateFlow()

    val background: StateFlow<ArenaBackground?> = backgroundState.asStateFlow()

    /** The arena's projection on the calibrating camera feed's canvas; null until calibrated */
    val projection: StateFlow<Rect?> = projectionState.asStateFlow()

    val fullScreen: StateFlow<Boolean> = fullScreenState.asStateFlow()

    /** Whether the arena says "Needs calibration" (until it is first calibrated) */
    val needsCalibrationLabel: StateFlow<Boolean> = labelState.asStateFlow()

    @Volatile
    var perspective: PerspectiveManager? = null

    val surface = ArenaSurface(settings, targets, markers, { sizeState.value }, receiver, commands)

    fun setSize(size: Size) {
        sizeState.value = size
    }

    fun setFullScreen(fullScreen: Boolean) {
        fullScreenState.value = fullScreen
    }

    fun setBackground(background: ArenaBackground?) {
        backgroundState.value = background
    }

    /**
     * Shows one of ShootOFF's own images as the background (the calibration pattern, the exposure step's
     * white screen), or none for null.
     */
    fun showResource(name: String?) {
        if (name == null) {
            setBackground(null)
            return
        }
        val stream = ArenaModel::class.java.getResourceAsStream("/" + name.removePrefix("/"))
        if (stream == null) {
            logger.error("ShootOFF has no image {}", name)
            return
        }
        setBackground(ArenaBackground.read(stream, name))
    }

    fun setProjection(projection: Rect?) {
        projectionState.value = projection
    }

    fun setCalibrationLabelVisible(visible: Boolean) {
        labelState.value = visible
    }

    /** Shows or hides every arena target, as calibration does. */
    fun setTargetsVisible(visible: Boolean) {
        for (target in targets.set.targets) targets.set.setVisible(target.id, visible)
    }

    fun showShots(visible: Boolean) = markers.setVisible(visible)

    /**
     * Fits a target that just joined the arena, as the JavaFX arena does: to its real-world size once the
     * perspective is known, and over the whole arena if it asks to fill it.
     */
    fun placeNewTarget(target: PlacedTarget) {
        val perspective = perspective
        val perception = target.definition.defaultPerception()
        if (perspective != null && perspective.isInitialized && perception.isPresent) {
            val real = perception.get()
            perspective.calculateObjectSize(real.width().toDouble(), real.height().toDouble(), real.distance().toDouble())
                .ifPresent { targets.set.resize(target.id, it.width, it.height) }
        }

        if (target.definition.fillsCanvas()) {
            val arena = sizeState.value
            targets.set.resize(target.id, arena.width, arena.height)
            val origin = targets.set.get(target.id).map { it.localToParent(0.0, 0.0) }.orElse(null) ?: return
            targets.set.move(target.id, -origin.x, -origin.y)
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaScreens.kt`:

```kotlin
package com.shootoff.compose.arena

import com.shootoff.geom.Point
import com.shootoff.geom.ProjectorScreens
import com.shootoff.geom.Rect
import java.awt.GraphicsEnvironment
import java.util.Optional
import java.util.OptionalInt

/**
 * Where the arena window opens.
 *
 * @param screen the screen to go full screen on; null when no screen looks like a projector
 * @param position where the window opens, before it goes full screen
 */
data class ArenaPlacement(val screen: Rect?, val position: Point)

object ArenaScreens {
    /** The screens, in AWT's order and coordinates */
    fun screens(): List<Rect> {
        if (GraphicsEnvironment.isHeadless()) return emptyList()
        return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
            val b = it.defaultConfiguration.bounds
            Rect(b.x.toDouble(), b.y.toDouble(), b.width.toDouble(), b.height.toDouble())
        }
    }

    /**
     * Picks the arena's screen with core's rule (see ProjectorScreens). The window opens where ShootOFF is,
     * then moves: to the saved position, or 10 px into the chosen screen, as the JavaFX app places it.
     *
     * @param appScreen the screen ShootOFF's main window is on
     */
    fun place(screens: List<Rect>, appScreen: Int, savedPosition: Point?): ArenaPlacement {
        val choice = ProjectorScreens.choose(screens, appScreen, OptionalInt.of(appScreen), Optional.ofNullable(savedPosition))
        if (choice.isEmpty) {
            val home = screens.getOrNull(appScreen)
            return ArenaPlacement(null, Point((home?.minX ?: 0.0) + 10, (home?.minY ?: 0.0) + 10))
        }

        val screen = screens[choice.get().screen()]
        val position = if (choice.get().reason() == ProjectorScreens.Reason.SAVED_POSITION) {
            savedPosition!!
        } else {
            Point(screen.minX + 10, screen.minY + 10)
        }
        return ArenaPlacement(screen, position)
    }

    /** The screen containing a point, e.g. the main window's top left corner */
    fun screenAt(screens: List<Rect>, point: Point): Int = screens.indexOfFirst { it.contains(point.x, point.y) }.coerceAtLeast(0)
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`:

```kotlin
package com.shootoff.compose.arena

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shootoff.compose.shots.MarkerLayer
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.TargetLayer
import kotlin.math.roundToInt

private val ARENA_GRAY = Color(0xFF333333)
private val CALIBRATION_ORANGE = Color(0xFFF5A807)

/**
 * The arena as both of its views draw it, fitted to the space it is given: the background stretched over
 * the arena, its targets and shot markers, the "Needs calibration" label, then [overlay] (the exercise's
 * texts and markers) in arena coordinates.
 */
@Composable
fun ArenaCanvas(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    val size by arena.size.collectAsState()
    val background by arena.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val density = LocalDensity.current

    BoxWithConstraints(modifier.background(Color.Black)) {
        val transform = SurfaceTransform.fit(size, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        // The arena's own area: gray until it has a background
        Canvas(Modifier.fillMaxSize().testTag("arena-canvas")) {
            val topLeft = transform.toView(0.0, 0.0)
            val areaSize = Size((size.width * transform.scale).toFloat(), (size.height * transform.scale).toFloat())
            drawRect(ARENA_GRAY, topLeft, areaSize)
            val image = background?.image ?: return@Canvas
            drawImage(
                image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(areaSize.width.roundToInt(), areaSize.height.roundToInt()),
            )
        }
        TargetLayer(arena.targets, transform)
        MarkerLayer(arena.markers, transform)
        if (label) {
            // The JavaFX arena's label: 48 px orange, centered in a 628x90 box at (6, 6)
            val topLeft = transform.toView(6.0, 6.0)
            with(density) {
                Box(
                    Modifier.offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                        .size((628 * transform.scale).toDp(), (90 * transform.scale).toDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Needs Calibration",
                        color = CALIBRATION_ORANGE,
                        style = TextStyle(fontSize = (48 * transform.scale).toSp()),
                        modifier = Modifier.testTag("needs-calibration"),
                    )
                }
            }
        }
        overlay(transform)
    }
}

/** The in-app Arena view: the arena fitted into the Range screen's big view. */
@Composable
fun ArenaView(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    ArenaCanvas(arena, modifier.testTag("arena-view"), overlay)
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt`:

```kotlin
package com.shootoff.compose.arena

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
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
    arena: ArenaModel,
    placement: ArenaPlacement,
    onCloseRequest: () -> Unit,
    overlay: @Composable (SurfaceTransform) -> Unit = {},
) {
    val state = rememberWindowState(
        position = WindowPosition(placement.position.x.dp, placement.position.y.dp),
        size = DpSize(640.dp, 480.dp),
    )
    Window(
        onCloseRequest = onCloseRequest,
        state = state,
        title = "Projector Arena",
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.F11) {
                state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                true
            } else {
                false
            }
        },
    ) {
        LaunchedEffect(placement) {
            val screen = placement.screen ?: return@LaunchedEffect
            val waited = System.currentTimeMillis()
            while (System.currentTimeMillis() - waited < PLACEMENT_WAIT_MILLIS) {
                val on = window.graphicsConfiguration.bounds
                if (on.x.toDouble() == screen.minX && on.y.toDouble() == screen.minY) break
                delay(50)
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
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.arena.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 12 tests passing.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `510/510 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaScreens.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaModel.kt compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaScreens.kt compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt
git commit -m "Draw the one arena model in its projector window and in the app"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 8: Calibration on core's flow, with the overlay and the manual box

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt` (`CalibrationFixture` is a helper)

**Interfaces:**
- Consumes:
  - `core`'s `CalibrationFlow`:
    - `View`, `Exercises`, `Scheduler`, `Message`
    - `start()`, `stop()`, `calibrated(Rect, Optional<Size>, boolean)`, `setFullScreen(boolean)`, `arenaClosing()`, `isCalibrating()`
    - `FULL_SCREEN_SETTLE_DELAY`, `AUTO_CALIBRATION_TIMEOUT`
  - `CalibrationCamera` (`CameraManager` is one); `CameraCalibrationListener`
  - Task 7's `ArenaModel`, `ArenaBackground`
- Produces (`com.shootoff.compose.calibration`):
  - `data class CalibrationUi(calibrating: Boolean = false, message: Message? = null, box: Rect? = null)`
  - `fun Message.text(): String`
  - `interface CalibrationViews { showCalibratingFeed(); restoreSelectedView() }`
  - `class CalibrationController(camera: CalibrationCamera, arena: ArenaModel, settings, exercises: CalibrationFlow.Exercises, views: CalibrationViews, scheduler: CalibrationFlow.Scheduler, uiThread: (Runnable) -> Unit) : CalibrationFlow.View, CameraCalibrationListener`:
    - `flow`, `state: StateFlow<CalibrationUi>`, `DEFAULT_BOX`
    - `toggle()`, `moveBox(Rect)`, `fullScreenChanged(Boolean)`, `arenaClosing()`
  - `@Composable CalibrationOverlay(controller, transform, modifier)`

**How Compose draws the flow (gap 4, ruling 9).**
- The flow (Plan 4) decides every step. The controller shows it:
  - the pattern and the white screen come from `core`'s resources (Task 2)
  - the arena's background is saved before the pattern and put back afterwards
  - targets hide while calibrating; the "Needs Calibration" label goes
  - the calibrated projection lands in `ArenaModel.projection`, and shots use it from then on
  - the manual box is a `Rect` on the feed's canvas
- Messages are Compose's own text, on a card over the feed with Stop, or Done while the box shows.
- The app calls `fullScreenChanged` whenever the arena window's placement changes (Task 11).
- Calibration stops a projector exercise first and restarts it last, through the flow's `Exercises` port (Task 9's `ExerciseRunner`).

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt`:

```kotlin
package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** A controller on a fake camera, with the flow's timers and the UI thread under the test's control. */
class CalibrationFixture {
    val events = CopyOnWriteArrayList<String>()
    val timers = mutableListOf<Pair<Long, Runnable>>()
    val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    var restartExercise: Optional<Runnable> = Optional.empty()

    inner class FakeCamera : CalibrationCamera {
        var bounds: Rect? = null

        override fun getName() = "C270"

        override fun getFeedWidth() = 640

        override fun getFeedHeight() = 480

        override fun getProjectionBounds(): Optional<Rect> = Optional.ofNullable(bounds)

        override fun setProjectionBounds(projectionBounds: Rect?) {
            bounds = projectionBounds
        }

        override fun setCalibrating(isCalibrating: Boolean) {
            events += "camera calibrating $isCalibrating"
        }

        override fun setDetecting(isDetecting: Boolean) {
            events += "camera detecting $isDetecting"
        }

        override fun enableAutoCalibration(calculateFrameDelay: Boolean) {
            events += "auto on"
        }

        override fun disableAutoCalibration() {
            events += "auto off"
        }

        override fun setCropFeedToProjection(cropFeed: Boolean) {}

        override fun setLimitDetectProjection(limitDetection: Boolean) {}
    }

    val camera = FakeCamera()

    lateinit var arena: ArenaModel

    init {
        arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        arena.setSize(Size(1280.0, 720.0))
    }

    val views = object : CalibrationViews {
        override fun showCalibratingFeed() {
            events += "show feed"
        }

        override fun restoreSelectedView() {
            events += "restore view"
        }
    }

    val controller = CalibrationController(
        camera,
        arena,
        settings,
        {
            events += "stop exercise"
            restartExercise
        },
        views,
        CalibrationFlow.Scheduler { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
        },
        { it.run() },
    )

    /** Runs the pending timer that was set for [delay] */
    fun fire(delay: Long) {
        val timer = timers.first { it.first == delay }
        timers.remove(timer)
        timer.second.run()
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt`:

```kotlin
package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.geom.Rect
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestCalibrationController {
    private val fixture = CalibrationFixture()
    private val controller = fixture.controller
    private val arena = fixture.arena

    // The arena window opens windowed, then reaches the projector and goes full screen
    private fun startOnTheProjector() {
        controller.toggle()
        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
    }

    @Test
    fun calibrationAsksForFullScreenThenShowsThePatternAndLooks() {
        controller.toggle()
        assertEquals(Message.FULL_SCREEN_REQUEST, controller.state.value.message)
        assertTrue(controller.state.value.calibrating)

        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)

        assertEquals(Message.AUTO_CALIBRATING, controller.state.value.message)
        assertEquals("pattern.png", arena.background.value!!.name)
        assertTrue(fixture.events.contains("auto on"))
        assertFalse(arena.needsCalibrationLabel.value)
    }

    @Test
    fun whenTheCameraFindsThePatternTheProjectionIsSetAndTheBackgroundComesBack() {
        val drillBackground = ArenaBackground(ImageBitmap(4, 4), "backgrounds/blackBG.png")
        arena.setBackground(drillBackground)
        val target = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf()))),
            ResourceResolver.files(),
        )
        startOnTheProjector()
        assertFalse(arena.targets.set.get(target.id).get().isVisible)

        // The camera reports the pattern on its 640x480 feed, shown 1:1 on the canvas
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), arena.projection.value)
        assertEquals(Rect(100.0, 80.0, 400.0, 300.0), fixture.camera.bounds)
        assertSame(drillBackground, arena.background.value)
        assertTrue(arena.targets.set.get(target.id).get().isVisible)
        assertFalse(controller.state.value.calibrating)
        assertNull(controller.state.value.message)
    }

    @Test
    fun whenAutoCalibrationTimesOutTheBoxShowsOnTheFeed() {
        startOnTheProjector()

        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)

        assertEquals(Message.MANUAL_REQUEST, controller.state.value.message)
        assertEquals(CalibrationController.DEFAULT_BOX, controller.state.value.box)
        assertTrue(fixture.events.contains("show feed"))
    }

    @Test
    fun doneCalibratesToTheBoxWhereTheUserLeftIt() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)

        controller.moveBox(Rect(120.0, 90.0, 380.0, 280.0))
        controller.flow.stop()

        assertEquals(Rect(120.0, 90.0, 380.0, 280.0), arena.projection.value)
        assertNull(controller.state.value.box)
        assertTrue(fixture.events.contains("restore view"))
        assertNull(arena.background.value)
    }

    @Test
    fun aRunningProjectorDrillStopsFirstAndStartsAgainAfter() {
        val order = mutableListOf<String>()
        fixture.restartExercise = Optional.of(Runnable { order += "restart" })
        controller.toggle()
        assertEquals("stop exercise", fixture.events.first())

        arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
        controller.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        assertEquals(listOf("restart"), order)
    }

    @Test
    fun closingTheArenaWhileCalibratingEndsCalibrationAndForgetsTheProjection() {
        startOnTheProjector()

        controller.arenaClosing()

        assertFalse(controller.state.value.calibrating)
        assertNull(arena.projection.value)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt`:

```kotlin
package com.shootoff.compose.calibration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class TestCalibrationOverlay {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = CalibrationFixture()
    private val controller = fixture.controller

    // A 320x240 canvas drawn at twice its size, so view pixels and canvas pixels differ
    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) {
                    Box(Modifier.size(640.dp, 480.dp)) {
                        CalibrationOverlay(controller, SurfaceTransform.fit(Size(320.0, 240.0), 640f, 480f))
                    }
                }
            }
        }
    }

    private fun startOnTheProjector() {
        controller.toggle()
        fixture.arena.setFullScreen(true)
        controller.fullScreenChanged(true)
        fixture.fire(CalibrationFlow.FULL_SCREEN_SETTLE_DELAY)
    }

    @Test
    fun whileLookingForThePatternTheUserCanStop() {
        startOnTheProjector()
        show()

        compose.onNodeWithText("Looking for the calibration pattern…").assertExists()
        compose.onNodeWithTag("calibration-stop").performClick()

        assertFalse(controller.state.value.calibrating)
    }

    @Test
    fun theBoxMovesAndResizesWithTheMouseAtTheFeedsScale() {
        startOnTheProjector()
        fixture.fire(CalibrationFlow.AUTO_CALIBRATION_TIMEOUT)
        show()

        // Dragging 10 view pixels moves the box 5 canvas pixels
        compose.onNodeWithTag("calibration-box").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(10f, 5f))
            release()
        }
        compose.waitForIdle()
        assertEquals(Rect(80.0, 77.5, 150.0, 150.0), controller.state.value.box)

        compose.onNodeWithTag("calibration-corner-bottom-right").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(20f, 10f))
            release()
        }
        compose.waitForIdle()
        assertEquals(Rect(80.0, 77.5, 160.0, 155.0), controller.state.value.box)

        compose.onNodeWithTag("calibration-done").performClick()
        assertEquals(Rect(80.0, 77.5, 160.0, 155.0), fixture.arena.projection.value)
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.calibration.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'CalibrationController'` (and `'CalibrationViews'`, `'CalibrationOverlay'`), then `BUILD FAILED`.

- [ ] **Step 3: Write the controller and the overlay**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

```kotlin
package com.shootoff.compose.calibration

import com.shootoff.calibration.CalibrationCamera
import com.shootoff.calibration.CalibrationFlow
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.camera.CameraCalibrationListener
import com.shootoff.camera.perspective.PerspectiveManager
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.config.CalibrationOption
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Optional

/**
 * What the calibration overlay shows.
 *
 * @param message the flow's current request of the user, if any
 * @param box the manual calibration box on the calibrating feed's canvas, while it is showing
 */
data class CalibrationUi(val calibrating: Boolean = false, val message: Message? = null, val box: Rect? = null)

/** The Compose app's words for the flow's messages (the JavaFX app keeps its own). */
fun Message.text(): String = when (this) {
    Message.FULL_SCREEN_REQUEST -> "Move the arena to the projector and press F11"
    Message.AUTO_CALIBRATING -> "Looking for the calibration pattern…"
    Message.MANUAL_REQUEST -> "Drag the box over the projection, then press Done"
}

/** The Range screen's big view, as calibration switches it. */
interface CalibrationViews {
    /** Remembers the view the user is on and shows the calibrating camera's feed. */
    fun showCalibratingFeed()

    fun restoreSelectedView()
}

/**
 * Calibrates the projector arena in the Compose app: core's [CalibrationFlow] decides what happens; this
 * shows it (the pattern on the arena, the overlay on the feed) and hands the user's actions and the
 * camera's findings back. The camera reports success on its own thread; the flow's timers come back on
 * [uiThread].
 */
class CalibrationController(
    private val camera: CalibrationCamera,
    private val arena: ArenaModel,
    private val settings: Settings,
    exercises: CalibrationFlow.Exercises,
    private val views: CalibrationViews,
    scheduler: CalibrationFlow.Scheduler,
    private val uiThread: (Runnable) -> Unit,
) : CalibrationFlow.View, CameraCalibrationListener {
    companion object {
        // The JavaFX app's default box: 150 px square, 75 px in from the feed's corner
        val DEFAULT_BOX = Rect(75.0, 75.0, 150.0, 150.0)
    }

    val flow = CalibrationFlow(camera, this, exercises, settings, scheduler, Optional.empty())

    private val uiState = MutableStateFlow(CalibrationUi())
    val state: StateFlow<CalibrationUi> = uiState.asStateFlow()

    @Volatile
    private var savedBackground: ArenaBackground? = null

    // ---- The user

    /** The Calibrate button: starts calibrating, or ends it (with the box, if it is showing). */
    fun toggle() = if (flow.isCalibrating) flow.stop() else flow.start()

    fun moveBox(box: Rect) = uiState.update { if (it.box != null) it.copy(box = box) else it }

    /** The arena window went full screen or left it. */
    fun fullScreenChanged(fullScreen: Boolean) = flow.setFullScreen(fullScreen)

    /** The arena window is closing: calibration ends, and its projection with it. */
    fun arenaClosing() {
        if (flow.isCalibrating) flow.stop() else flow.arenaClosing()
        arena.setProjection(null)
    }

    // ---- The camera (CameraCalibrationListener)

    override fun calibrate(arenaBounds: Rect, perspectivePaperDims: Optional<Size>, calibratedFromCanvas: Boolean, frameDelay: Long) =
        flow.calibrated(arenaBounds, perspectivePaperDims, calibratedFromCanvas)

    /** Auto-calibration's exposure step shows a white screen, then none */
    override fun setArenaBackground(resourceFilename: String?) = arena.showResource(resourceFilename)

    // ---- The flow (CalibrationFlow.View)

    override fun isArenaFullScreen(): Boolean = arena.fullScreen.value

    override fun setArenaShotsVisible(visible: Boolean) = arena.showShots(visible)

    override fun setCalibrating(calibrating: Boolean) = uiState.update { it.copy(calibrating = calibrating) }

    override fun calibrationStarted() {
        arena.setTargetsVisible(false)
        arena.setCalibrationLabelVisible(false)
    }

    override fun saveArenaBackground() {
        savedBackground = arena.background.value
    }

    override fun restoreArenaBackground() {
        arena.setBackground(savedBackground)
        savedBackground = null
    }

    override fun showPattern() = arena.showResource("pattern.png")

    override fun showMessage(message: Message) = uiState.update { it.copy(message = message) }

    override fun hideMessage(message: Message) = uiState.update { if (it.message == message) it.copy(message = null) else it }

    override fun showCalibratingFeed() = views.showCalibratingFeed()

    override fun restoreSelectedView() = views.restoreSelectedView()

    override fun showManualBox() = uiState.update { it.copy(box = DEFAULT_BOX) }

    override fun manualBox(): Optional<Rect> = Optional.ofNullable(uiState.value.box)

    override fun removeManualBox() = uiState.update { it.copy(box = null) }

    override fun projectionCalibrated(canvasBounds: Rect) = arena.setProjection(canvasBounds)

    override fun calibratedFeedBehavior(): CalibrationOption = settings.calibratedFeedBehavior

    override fun arenaResolution(): Size = arena.size.value

    override fun calibrated(perspectiveManager: Optional<PerspectiveManager>) {
        arena.perspective = perspectiveManager.orElse(null)
        arena.setCalibrationLabelVisible(false)
        arena.setTargetsVisible(true)
        // Targets take their real-world sizes once the perspective is known
        if (perspectiveManager.isPresent) {
            for (target in arena.targets.set.targets) arena.placeNewTarget(target)
        }
    }

    override fun runOnUiThread(action: Runnable) = uiThread(action)
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt`:

```kotlin
package com.shootoff.compose.calibration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shootoff.calibration.CalibrationFlow.Message
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Rect
import kotlin.math.roundToInt

private const val MIN_BOX = 20.0

/**
 * Calibration over the calibrating camera's feed: what the flow asks of the user, with Stop (or Done
 * for the manual box), and the box itself, which the user drags and resizes by its corners.
 */
@Composable
fun CalibrationOverlay(controller: CalibrationController, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    Box(modifier.fillMaxSize()) {
        state.box?.let { ManualBox(it, transform, controller::moveBox) }

        val message = state.message
        if (state.calibrating && message != null) {
            val colors = Range.colors
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.highlightCard,
                border = BorderStroke(1.dp, colors.highlightBorder),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp).testTag("calibration-message"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (message == Message.AUTO_CALIBRATING) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                    }
                    Text(message.text(), color = colors.text)
                    if (message == Message.MANUAL_REQUEST) {
                        Button(onClick = { controller.flow.stop() }, modifier = Modifier.testTag("calibration-done")) { Text("Done") }
                    } else {
                        FilledTonalButton(onClick = { controller.flow.stop() }, modifier = Modifier.testTag("calibration-stop")) { Text("Stop") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualBox(box: Rect, transform: SurfaceTransform, onMove: (Rect) -> Unit) {
    val colors = Range.colors
    val current by rememberUpdatedState(box)
    val topLeft = transform.toView(box.minX, box.minY)
    val density = LocalDensity.current
    val width = with(density) { (box.width * transform.scale).toFloat().toDp() }
    val height = with(density) { (box.height * transform.scale).toFloat().toDp() }

    Box(
        Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(width, height)
            .background(colors.accent.copy(alpha = 0.18f))
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
        for ((alignment, corner) in listOf(
            Alignment.TopStart to Corner(left = true, top = true),
            Alignment.TopEnd to Corner(left = false, top = true),
            Alignment.BottomStart to Corner(left = true, top = false),
            Alignment.BottomEnd to Corner(left = false, top = false),
        )) {
            Box(
                Modifier
                    .align(alignment)
                    .size(14.dp)
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

private data class Corner(val left: Boolean, val top: Boolean) {
    val name: String get() = (if (top) "top" else "bottom") + "-" + (if (left) "left" else "right")

    fun resize(box: Rect, dx: Double, dy: Double): Rect {
        var minX = box.minX
        var minY = box.minY
        var maxX = box.maxX
        var maxY = box.maxY
        if (left) minX = minOf(minX + dx, maxX - MIN_BOX) else maxX = maxOf(maxX + dx, minX + MIN_BOX)
        if (top) minY = minOf(minY + dy, maxY - MIN_BOX) else maxY = maxOf(maxY + dy, minY + MIN_BOX)
        return Rect(minX, minY, maxX - minX, maxY - minY)
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.calibration.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 8 tests passing.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `518/518 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationOverlay.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/calibration/CalibrationFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationController.kt compose-app/src/test/kotlin/com/shootoff/compose/calibration/TestCalibrationOverlay.kt
git commit -m "Calibrate the arena from Compose on core's calibration flow, with the manual box"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 9: `ComposeExerciseHost` and the exercise runner, in the `ExerciseHost` contract suite

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/HostSurface.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/SoundOutput.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/drill/ComposeHostHarness.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestComposeExerciseHostContract.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestComposeExerciseHostContractOnArena.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestExerciseRunner.kt` (`ComposeHostHarness` is the suite's harness)

**Interfaces:**
- Consumes:
  - `plugin-api`'s `ExerciseHost`, `Exercise`, the handles and styles
  - `ExerciseHostSupport<T>`: `run`, `stop(): Optional<Stopped<T>>`, `track`/`untrack`, `findTarget`, `openImage`, `open(URL)`, `openSound`/`playSound`/`playSounds`, `resource`, `dataDirectory`, `pauseShotDetection`, and the par-time and start-delay methods
  - `SavedBackground<B>`, `V2ExerciseEntry`
  - the test fixtures' `ExerciseHostContract` and its `Harness`
  - `TargetDefinitions.load`, `ResourceResolver.classLoader`
  - Task 5's `SurfaceTargets`; Task 6's `ShotTimerModel`, `ShotMarkers`, `Marker`, `ShotReceiver`, `ArenaPointShot`; Task 7's `ArenaModel`, `ArenaBackground`
- Produces (`com.shootoff.compose.drill`):
  - `data class DrillButton(id, label, onClick: () -> Unit)`, `data class NumberSetting(id, label, value, min, max, step, onChange: (Double) -> Unit)`, `data class DrillText(id, text, x, y, style: TextStyle)`
  - `data class TimingControls(showsParTime, parTime, delay: DelayRange, onParTime: (Double) -> Unit, onDelay: (Int, Int) -> Unit)`
  - `class DrillState`:
    - `name`, `buttons`, `settings`, `texts`, `timing`, `message` (StateFlows); `markers: ShotMarkers`
    - `addButton`/`relabelButton`/`removeButton`, `addSetting`/`removeSetting`/`setSettingValue`, `addText`/`updateText`/`removeText`, `setTiming`/`updateTiming`, `setMessage`, `setName`, `clear`
  - `interface HostSurface { targets; isProjector; size(); background(); setBackground(); placeNewTarget() }`, with `ArenaHostSurface(arena)` and `FeedHostSurface(targets, displaySize)`
  - `interface SoundOutput { play(name, sound: InputStream, whenDone: () -> Unit); say(text) }` and `SoundOutput.speakers`
  - `class HostContext(settings, surface, timer, drill, resources: ClassLoader?, sounds, clearShots: () -> Unit, setDetecting: (Boolean) -> Unit, onFailure: (ComposeExerciseHost, Throwable) -> Unit = { _, _ -> })`
  - `class ComposeExerciseHost(exercise, context) : ExerciseHost`, with `name`, `start()`, `deliverShot(Shot, Hit?)`, `targetsChanged()`, `reset()`, `stop()`, `isStopped`
  - `class Running(entry: V2ExerciseEntry, host: ComposeExerciseHost)`
  - `class ExerciseRunner(newHost: (V2ExerciseEntry, Exercise, ExerciseRunner) -> ComposeExerciseHost?) : ShotReceiver, CalibrationFlow.Exercises`:
    - `running`, `failure` (StateFlows)
    - `start(entry): Boolean`, `stop()`, `reset()`, `failed(host, error)`, `dismissFailure()`

**The host (spec §3).**
- It keeps the 1a contract through `ExerciseHostSupport`: one exercise thread, host methods callable from any thread, `stop()` exactly once, and nothing after stop.
- Each host call changes `DrillState`, the surface's targets or `ShotTimerModel` at once, under its lock (ruling 4).
- `stop()` removes what the exercise added: its targets, texts, markers, buttons, settings, columns, timing controls and message. It puts the background back.
- Callbacks are guarded (ruling 11). The host calls the exercise through `support.run`, so the exercise object is never wrapped and `dataDirectory()` keeps its class's name.
- Targets from the exercise's jar keep their "@name" file, as in JavaFX. Their images come through the exercise's class loader.

**The runner (gap 5, ruling 10).** `ExerciseRunner` is the Compose app's current-exercise registry. It starts a fresh instance per run and routes shots by surface. It is the calibration flow's `Exercises` port.

**The contract.** `ComposeHostHarness` runs the suite on a camera feed and on the arena. The arena has two views, the projector window and the in-app Arena view, and both draw the one model. The suite's eight tests pass on both.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/drill/ComposeHostHarness.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.shots.ArenaPointShot
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.exercise.ExerciseHostContract
import com.shootoff.exercise.ExerciseHostContract.TimerRowView
import com.shootoff.exercise.TargetHandle
import com.shootoff.geom.Size
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [ComposeExerciseHost] on a camera feed, or on the projector arena (the one arena model that the
 * projector window and the in-app Arena view both draw), for [ExerciseHostContract].
 */
class ComposeHostHarness private constructor(
    exercise: Exercise,
    resources: ClassLoader,
    private val projector: Boolean,
) : ExerciseHostContract.Harness {
    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val played = CopyOnWriteArrayList<String>()
    val timer = ShotTimerModel()
    val drill = DrillState()
    private lateinit var arena: ArenaModel
    private val surface: HostSurface
    private val host: ComposeExerciseHost

    init {
        System.setProperty("shootoff.home", System.getProperty("user.dir"))
        surface = if (projector) {
            arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
            arena.setSize(Size(1280.0, 720.0))
            ArenaHostSurface(arena)
        } else {
            FeedHostSurface(SurfaceTargets(clock = ManualClock()), Size(640.0, 480.0))
        }

        host = ComposeExerciseHost(
            exercise,
            HostContext(
                settings,
                surface,
                timer,
                drill,
                resources,
                object : SoundOutput {
                    override fun play(name: String, sound: InputStream, whenDone: () -> Unit) {
                        sound.close()
                        played += name
                        whenDone()
                    }

                    override fun say(text: String) {}
                },
                clearShots = { timer.clear() },
                setDetecting = {},
            ),
        )
    }

    companion object {
        fun onCameraFeed(exercise: Exercise, jarEntries: Map<String, ByteArray>, temp: Path) =
            ComposeHostHarness(exercise, jar(jarEntries, temp), projector = false)

        fun onArena(exercise: Exercise, jarEntries: Map<String, ByteArray>, temp: Path) =
            ComposeHostHarness(exercise, jar(jarEntries, temp), projector = true)

        // The exercise's jar, as a folder
        private fun jar(entries: Map<String, ByteArray>, temp: Path): ClassLoader {
            val jar = Files.createDirectories(temp.resolve("jar"))
            for ((name, bytes) in entries) {
                val file = jar.resolve(name)
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
            return URLClassLoader(arrayOf(jar.toUri().toURL()), null)
        }
    }

    override fun host(): ExerciseHost = host

    override fun start() = host.start()

    override fun shoot(x: Double, y: Double) {
        val shot = ScaledShot(ShotColor.RED, x, y, System.currentTimeMillis())
        // A projector exercise takes shots in arena coordinates
        host.deliverShot(if (projector) ArenaPointShot(shot, x, y) else shot, null)
    }

    override fun reset() = host.reset()

    override fun stop() = host.stop()

    // Host calls change the state at once; nothing is queued for the UI
    override fun awaitUi() {}

    override fun click(label: String) {
        val button = drill.buttons.value.firstOrNull { it.label == label } ?: throw AssertionError("No button $label")
        button.onClick()
    }

    override fun changeSetting(label: String, value: Double) {
        val setting = drill.settings.value.firstOrNull { it.label == label } ?: throw AssertionError("No setting $label")
        setting.onChange(value)
    }

    override fun userSetsParTime(seconds: Double) {
        val timing = drill.timing.value ?: throw AssertionError("No timing controls")
        if (!timing.showsParTime) throw AssertionError("No par time control")
        timing.onParTime(seconds)
    }

    override fun userSetsDelayedStart(minSeconds: Int, maxSeconds: Int) {
        val timing = drill.timing.value ?: throw AssertionError("No delay controls")
        timing.onDelay(minSeconds, maxSeconds)
    }

    override fun shownState(): Any = listOf(
        surface.targets.drawn.value.map { it.id to it.placement },
        drill.name.value,
        drill.buttons.value.map { it.label },
        drill.settings.value.map { it.label to it.value },
        drill.texts.value,
        drill.timing.value?.let { Triple(it.showsParTime, it.parTime, it.delay) },
        drill.message.value,
        drill.markers.markers.value,
        timer.columns.value,
        surface.background()?.name,
    )

    override fun addShotRow(timeMillis: Long) = timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 2.0, timeMillis), false, false)

    override fun timerRows(column: String): List<TimerRowView> = timer.rows.value.map {
        TimerRowView(it.row.time(), it.row.split(), it.row.laser(), it.values[column] ?: "", it.highlight != null)
    }

    // Every view of the surface draws its one model: on the arena, the projector window and the Arena view
    override fun visibilityOnEachView(target: TargetHandle): List<Boolean> {
        val drawn = surface.targets.drawn.value.firstOrNull { it.id == target.id() } ?: return emptyList()
        return List(viewCount()) { drawn.placement.visible() }
    }

    override fun viewCount(): Int = if (projector) 2 else 1

    override fun playedSounds(): List<String> = played.toList()

    override fun close() = host.stop()
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestComposeExerciseHostContract.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHostContract

/** ComposeExerciseHost on a camera feed keeps the host contract. */
class TestComposeExerciseHostContract : ExerciseHostContract() {
    override fun newHarness(exercise: Exercise, jarEntries: Map<String, ByteArray>): Harness =
        ComposeHostHarness.onCameraFeed(exercise, jarEntries, temp)
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestComposeExerciseHostContractOnArena.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHostContract

/** ComposeExerciseHost on the projector arena keeps the host contract. */
class TestComposeExerciseHostContractOnArena : ExerciseHostContract() {
    override fun newHarness(exercise: Exercise, jarEntries: Map<String, ByteArray>): Harness =
        ComposeHostHarness.onArena(exercise, jarEntries, temp)
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestExerciseRunner.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.camera.Shot
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.shots.ArenaPointShot
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Size
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class TestExerciseRunner {
    companion object {
        val heard = CopyOnWriteArrayList<String>()
        var resources: ClassLoader? = null
    }

    /** A projector drill that writes down its shots and throws on "boom" */
    class ArenaDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Arena drill", "1.0", "ShootOFF tests", "On the arena", true)

        override fun start(host: ExerciseHost) {
            heard += "start"
            host.addButton("Pause") {}
            host.setBackground("@backgrounds/black.png")
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {
            if (shot.x == 13.0) throw IllegalStateException("boom")
            heard += "shot ${shot.x}"
        }

        override fun onReset() {}

        override fun stop() {
            heard += "stop"
        }
    }

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val drill = DrillState()
    private val timer = ShotTimerModel()
    private lateinit var arena: ArenaModel
    private val entry = V2ExerciseEntry(ArenaDrill::class.java, ArenaDrill().metadata())
    private var arenaOpen = true

    private val runner = ExerciseRunner { _, exercise, runner ->
        if (!arenaOpen) {
            null
        } else {
            ComposeExerciseHost(
                exercise,
                HostContext(settings, ArenaHostSurface(arena), timer, drill, resources, silent, {}, {}, runner::failed),
            )
        }
    }

    private val silent = object : SoundOutput {
        override fun play(name: String, sound: InputStream, whenDone: () -> Unit) = whenDone()

        override fun say(text: String) {}
    }

    init {
        heard.clear()
        arena = ArenaModel(settings, { ShotReceiver.None }, { RegionCommandRunner(arena.targets, settings, {}, { null }) }, ManualClock())
        arena.setSize(Size(1280.0, 720.0))
    }

    @AfterEach
    fun stop() = runner.stop()

    private fun jar(temp: Path) {
        Files.createDirectories(temp.resolve("backgrounds"))
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", temp.resolve("backgrounds/black.png").toFile())
        resources = URLClassLoader(arrayOf(temp.toUri().toURL()), null)
    }

    private fun awaitHeard(event: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (event !in heard) {
            if (System.nanoTime() > deadline) throw AssertionError("never heard $event in $heard")
            Thread.sleep(5)
        }
    }

    @Test
    fun onlyArenaShotsReachAProjectorDrill(@TempDir temp: Path) {
        jar(temp)
        assertTrue(runner.start(entry))
        awaitHeard("start")

        runner.deliver(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), null, false)
        runner.deliver(ArenaPointShot(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), 2.0, 2.0), null, true)
        awaitHeard("shot 2.0")

        assertTrue("shot 1.0" !in heard)
    }

    @Test
    fun aDrillThatThrowsIsStoppedAndTheUserToldWhy(@TempDir temp: Path) {
        jar(temp)
        runner.start(entry)
        awaitHeard("start")

        runner.deliver(ArenaPointShot(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), 13.0, 13.0), null, true)
        awaitHeard("stop")

        assertEquals("Arena drill stopped: IllegalStateException: boom", runner.failure.value)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (runner.running.value != null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNull(runner.running.value)
        assertEquals(emptyList<DrillButton>(), drill.buttons.value)
    }

    @Test
    fun calibrationStopsAProjectorDrillAndStartsItAfresh(@TempDir temp: Path) {
        jar(temp)
        runner.start(entry)
        val first = runner.running.value!!.host

        val restart = runner.stopProjectorExercise()
        assertNull(runner.running.value)
        restart.get().run()

        assertNotSame(first, runner.running.value!!.host)
        assertSame(entry, runner.running.value!!.entry)
    }

    @Test
    fun startingTheDrillAgainStopsTheRunningOneFirst(@TempDir temp: Path) {
        jar(temp)
        runner.start(entry)
        val first = runner.running.value!!.host
        awaitHeard("start")

        runner.start(entry)

        assertTrue(first.isStopped)
        assertEquals(1, heard.count { it == "stop" })
        // One drill's worth of buttons: the first drill's are gone
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (drill.buttons.value.size != 1 && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(listOf("Pause"), drill.buttons.value.map { it.label })
    }

    @Test
    fun withoutTheArenaAProjectorDrillDoesntStart() {
        arenaOpen = false

        assertEquals(false, runner.start(entry))
        assertNull(runner.running.value)
    }

    @Test
    fun theArenasBackgroundComesBackWhenTheDrillStops(@TempDir temp: Path) {
        jar(temp)
        val before = ArenaBackground(ImageBitmap(2, 2), "indoor_range.gif")
        arena.setBackground(before)
        runner.start(entry)
        awaitHeard("start")
        assertEquals("/backgrounds/black.png", arena.background.value!!.name)

        runner.stop()

        assertSame(before, arena.background.value)
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.drill.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'ComposeExerciseHost'` (and `'DrillState'`, `'ExerciseRunner'`, …), then `BUILD FAILED`.

- [ ] **Step 3: Write the host and the runner**

`compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillState.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.exercise.DelayRange
import com.shootoff.exercise.TextStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DrillButton(val id: Long, val label: String, val onClick: () -> Unit)

data class NumberSetting(
    val id: Long,
    val label: String,
    val value: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val onChange: (Double) -> Unit,
)

/** A text the exercise shows on its surface, in the surface's coordinates */
data class DrillText(val id: Long, val text: String, val x: Double, val y: Double, val style: TextStyle)

/**
 * The shared par time and start delay controls, while the running exercise listens to them.
 *
 * @param showsParTime false when the exercise listens only to the start delay
 * @param onParTime the user set the par time
 * @param onDelay the user set the start delay's minimum and maximum
 */
data class TimingControls(
    val showsParTime: Boolean,
    val parTime: Double,
    val delay: DelayRange,
    val onParTime: (Double) -> Unit,
    val onDelay: (Int, Int) -> Unit,
)

/**
 * What the running exercise shows outside its surface's targets: the drill panel's buttons, settings
 * and timing controls, its texts and markers, and its banner message. Its host writes it from any
 * thread; Compose draws it.
 */
class DrillState {
    private val nameState = MutableStateFlow<String?>(null)
    private val buttonState = MutableStateFlow<List<DrillButton>>(emptyList())
    private val settingState = MutableStateFlow<List<NumberSetting>>(emptyList())
    private val textState = MutableStateFlow<List<DrillText>>(emptyList())
    private val timingState = MutableStateFlow<TimingControls?>(null)
    private val messageState = MutableStateFlow<String?>(null)

    /** The running exercise's name, while one runs */
    val name: StateFlow<String?> = nameState.asStateFlow()
    val buttons: StateFlow<List<DrillButton>> = buttonState.asStateFlow()
    val settings: StateFlow<List<NumberSetting>> = settingState.asStateFlow()
    val texts: StateFlow<List<DrillText>> = textState.asStateFlow()
    val timing: StateFlow<TimingControls?> = timingState.asStateFlow()

    /** The exercise's banner, shown on every camera feed */
    val message: StateFlow<String?> = messageState.asStateFlow()

    /** The markers the exercise drew itself, on its surface */
    val markers = ShotMarkers()

    fun setName(name: String?) {
        nameState.value = name
    }

    fun addButton(button: DrillButton) = buttonState.update { it + button }

    fun relabelButton(id: Long, label: String) = buttonState.update { buttons -> buttons.map { if (it.id == id) it.copy(label = label) else it } }

    fun removeButton(id: Long) = buttonState.update { buttons -> buttons.filterNot { it.id == id } }

    fun addSetting(setting: NumberSetting) = settingState.update { it + setting }

    fun removeSetting(id: Long) = settingState.update { settings -> settings.filterNot { it.id == id } }

    /** The user set a setting's value */
    fun setSettingValue(id: Long, value: Double) =
        settingState.update { settings -> settings.map { if (it.id == id) it.copy(value = value) else it } }

    fun addText(text: DrillText) = textState.update { it + text }

    fun updateText(id: Long, change: (DrillText) -> DrillText) = textState.update { texts -> texts.map { if (it.id == id) change(it) else it } }

    fun removeText(id: Long) = textState.update { texts -> texts.filterNot { it.id == id } }

    fun setTiming(timing: TimingControls?) {
        timingState.value = timing
    }

    fun updateTiming(change: (TimingControls) -> TimingControls) = timingState.update { it?.let(change) }

    fun setMessage(message: String?) {
        messageState.value = message
    }

    /** Everything goes, as when the exercise stops */
    fun clear() {
        nameState.value = null
        buttonState.value = emptyList()
        settingState.value = emptyList()
        textState.value = emptyList()
        timingState.value = null
        messageState.value = null
        markers.clear()
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/HostSurface.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget

/**
 * The surface an exercise runs on, as its host uses it: the projector arena, or a camera feed.
 */
interface HostSurface {
    val targets: SurfaceTargets

    val isProjector: Boolean

    fun size(): Size

    /** The arena's background (always none on a camera feed) */
    fun background(): ArenaBackground? = null

    fun setBackground(background: ArenaBackground?) {}

    /** Fits a target the exercise just added, as the surface fits new targets */
    fun placeNewTarget(target: PlacedTarget) {}
}

class ArenaHostSurface(private val arena: ArenaModel) : HostSurface {
    override val targets: SurfaceTargets get() = arena.targets

    override val isProjector: Boolean get() = true

    override fun size(): Size = arena.size.value

    override fun background(): ArenaBackground? = arena.background.value

    override fun setBackground(background: ArenaBackground?) = arena.setBackground(background)

    override fun placeNewTarget(target: PlacedTarget) = arena.placeNewTarget(target)
}

/** A camera feed: its targets on the feed's canvas, which is the display size */
class FeedHostSurface(override val targets: SurfaceTargets, private val displaySize: Size) : HostSurface {
    override val isProjector: Boolean get() = false

    override fun size(): Size = displaySize
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/SoundOutput.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.plugins.TextToSpeech
import com.shootoff.sound.SoundPlayer
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Optional
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener

/** Where a running exercise's sounds go: the speakers, or a recorder in tests. */
interface SoundOutput {
    /**
     * Plays [sound] and closes it, then runs [whenDone].
     *
     * @param name the name the exercise used, for logs and tests
     */
    fun play(name: String, sound: InputStream, whenDone: () -> Unit)

    fun say(text: String)

    companion object {
        val speakers = object : SoundOutput {
            override fun play(name: String, sound: InputStream, whenDone: () -> Unit) {
                if (SoundPlayer.isSilenced()) {
                    println(name)
                    sound.close()
                    whenDone()
                    return
                }

                val listener = LineListener { event ->
                    if (LineEvent.Type.STOP == event.type) {
                        event.line.close()
                        whenDone()
                    }
                }
                SoundPlayer.play(BufferedInputStream(sound), Optional.of(listener))
            }

            // Synthesis takes a while; keep it off the exercise thread
            override fun say(text: String) {
                Thread({ TextToSpeech.say(text) }, "Exercise speech").start()
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.camera.Shot
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.shots.Marker
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.config.Settings
import com.shootoff.exercise.ButtonHandle
import com.shootoff.exercise.Cancellable
import com.shootoff.exercise.DelayRange
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.exercise.ExercisePaths
import com.shootoff.exercise.RowStyle
import com.shootoff.exercise.ShotMarkerHandle
import com.shootoff.exercise.ShotStyle
import com.shootoff.exercise.TargetHandle
import com.shootoff.exercise.TextHandle
import com.shootoff.exercise.TextStyle
import com.shootoff.exercise.host.ExerciseHostSupport
import com.shootoff.exercise.host.ExerciseHostSupport.FileTarget
import com.shootoff.exercise.host.ExerciseHostSupport.JarTarget
import com.shootoff.exercise.host.ExerciseHostSupport.MissingTarget
import com.shootoff.exercise.host.SavedBackground
import com.shootoff.geom.Point
import com.shootoff.geom.Size
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetFormatException
import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSetListener
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.function.DoubleConsumer

/**
 * What a [ComposeExerciseHost] works with.
 *
 * @param resources the exercise's class loader; for a plugin, its jar's
 * @param clearShots clears every camera's shots and the shot timer
 * @param setDetecting turns every camera's shot detection on or off
 * @param onFailure hears that one of the exercise's callbacks threw
 */
class HostContext(
    val settings: Settings,
    val surface: HostSurface,
    val timer: ShotTimerModel,
    val drill: DrillState,
    val resources: ClassLoader?,
    val sounds: SoundOutput,
    val clearShots: () -> Unit,
    val setDetecting: (Boolean) -> Unit,
    val onFailure: (ComposeExerciseHost, Throwable) -> Unit = { _, _ -> },
)

/**
 * Runs one v2 [Exercise] in the Compose app, on the projector arena or on a camera feed. What every
 * user interface's host shares (the exercise's thread, stopping, names, timer rows, the shared par time
 * and start delay) is plugin-api's [ExerciseHostSupport]; this puts the exercise's things into the
 * Compose app's state.
 * - Every exercise callback runs on the exercise's own thread. One that throws is reported to
 *   [HostContext.onFailure] (the app then stops the exercise and says why).
 * - Host methods may be called from any thread. They change the state at once, under one lock, in call
 *   order; Compose redraws from it. No host method waits for the UI thread.
 * - [stop] stops the exercise, cancels its scheduled tasks and removes everything it added. Calls the
 *   exercise makes afterwards change nothing.
 */
class ComposeExerciseHost(private val exercise: Exercise, private val context: HostContext) : ExerciseHost {
    private val logger = LoggerFactory.getLogger(ComposeExerciseHost::class.java)
    private val support = ExerciseHostSupport<TargetId>(exercise, context.resources)
    private val drill = context.drill
    private val targets = context.surface.targets
    private val nextId = AtomicLong()

    // Guarded by lock: what this host added to the shared state, removed on stop
    private val lock = Any()
    private var tornDown = false
    private val buttons = mutableListOf<Long>()
    private val settings = mutableListOf<Long>()
    private val texts = mutableListOf<Long>()
    private val markers = mutableListOf<Marker>()
    private val columns = mutableListOf<String>()
    private var showsTiming = false
    private var showsMessage = false
    private val savedBackground = SavedBackground<ArenaBackground>()

    // The exercise hears about targets joining and leaving its surface
    private val targetListener = object : TargetSetListener {
        override fun targetAdded(target: PlacedTarget) = targetsChanged()

        override fun targetRemoved(target: PlacedTarget) = targetsChanged()
    }

    val name: String get() = support.exerciseName()

    // ---- Lifecycle, driven by the ExerciseRunner

    fun start() {
        drill.setName(name)
        targets.set.addListener(targetListener)
        support.run(guarded { exercise.start(this) })
    }

    /** Hands a shot to the exercise, in its surface's coordinates. */
    fun deliverShot(shot: Shot, hit: Hit?) = support.run(guarded { exercise.onShot(shot, Optional.ofNullable(hit)) })

    fun targetsChanged() = support.run(guarded { exercise.onTargetsChanged(targets()) })

    fun reset() = support.run(guarded { exercise.onReset() })

    /**
     * Stops the exercise and removes everything it added. Waits up to two seconds for the exercise's
     * thread, which never waits for the UI thread, so this may run on the UI thread.
     */
    fun stop() {
        val stopped = support.stop().orElse(null) ?: return
        targets.set.removeListener(targetListener)

        for (target in stopped.addedTargets()) targets.remove(target)
        if (stopped.restartDetection()) context.setDetecting(true)

        synchronized(lock) {
            tornDown = true
            buttons.forEach(drill::removeButton)
            settings.forEach(drill::removeSetting)
            texts.forEach(drill::removeText)
            drill.markers.removeAll(markers)
            context.timer.removeColumns(columns)
            if (showsTiming) drill.setTiming(null)
            if (showsMessage) drill.setMessage(null)
            drill.setName(null)
            savedBackground.restore { context.surface.setBackground(it.orElse(null)) }
        }
    }

    val isStopped: Boolean get() = support.isStopped

    // A callback of the exercise's, reporting a throw before the executor logs it
    private fun guarded(callback: () -> Unit) = Runnable {
        try {
            callback()
        } catch (e: RuntimeException) {
            context.onFailure(this, e)
            throw e
        } catch (e: Error) {
            context.onFailure(this, e)
            throw e
        }
    }

    // Changes the shared state unless the exercise has been torn down
    private inline fun ui(change: () -> Unit) {
        synchronized(lock) {
            if (!tornDown) change()
        }
    }

    // ---- The user, from the drill panel's timing controls

    private fun userChangedParTime(seconds: Double) {
        support.userChangedParTime(seconds)
        ui { drill.updateTiming { it.copy(parTime = seconds) } }
    }

    private fun userChangedDelayedStart(minSeconds: Int, maxSeconds: Int) {
        support.userChangedDelayedStart(minSeconds, maxSeconds)
        ui { drill.updateTiming { it.copy(delay = support.delayedStart()) } }
    }

    // ---- Surface

    override fun surfaceSize(): Size = context.surface.size()

    override fun isProjector(): Boolean = context.surface.isProjector

    override fun setBackground(imageResource: String) {
        if (!isProjector) {
            logger.warn("{} set a background, but only the projector arena has one", name)
            return
        }

        val resource = ExercisePaths.resourceName(imageResource)
        val background = try {
            val image = support.openImage(resource).orElse(null)
            if (image == null) {
                logger.error("Can't find background {}", imageResource)
                return
            }
            ArenaBackground.read(image, "/$resource")
        } catch (e: IOException) {
            logger.error("Can't read background {}", imageResource, e)
            return
        } ?: return

        ui {
            savedBackground.beforeChange { Optional.ofNullable(context.surface.background()) }
            context.surface.setBackground(background)
        }
    }

    // ---- Targets

    override fun addTarget(targetFile: String, x: Double, y: Double): Optional<TargetHandle> {
        if (support.isStopped) return Optional.empty()

        val (definition, resolver) = loadTarget(targetFile) ?: return Optional.empty()
        val target = targets.add(definition, resolver, Placement(x, y, 1.0, 1.0, true))
        context.surface.placeNewTarget(target)

        if (support.track(target.id)) return Optional.of(Handle(target))

        // Stopped while loading
        targets.remove(target.id)
        return Optional.empty()
    }

    private fun loadTarget(targetFile: String): Pair<TargetDefinition, ResourceResolver>? {
        val jarResolver = ResourceResolver.classLoader(context.resources)
        return try {
            when (val source = support.findTarget(targetFile)) {
                is JarTarget -> ExerciseHostSupport.open(source.url()).use { stream: InputStream ->
                    TargetDefinitions.load(stream, jarResolver).withFile(File("@" + source.name())) to jarResolver
                }
                is FileTarget -> TargetDefinitions.load(source.file().toPath()) to ResourceResolver.files()
                is MissingTarget -> {
                    logger.error("Can't find target {}", source.requested())
                    null
                }
            }
        } catch (e: TargetFormatException) {
            logger.error("Can't read target {}: {}", targetFile, e.message)
            null
        } catch (e: IOException) {
            logger.error("Can't read target {}", targetFile, e)
            null
        }
    }

    override fun targets(): List<TargetHandle> = targets.set.targets.map { Handle(it) }

    private inner class Handle(private val target: PlacedTarget) : TargetHandle {
        override fun id(): TargetId = target.id

        override fun move(x: Double, y: Double) = targets.set.move(target.id, x, y)

        override fun resize(width: Double, height: Double) = targets.set.resize(target.id, width, height)

        override fun setVisible(visible: Boolean) = targets.set.setVisible(target.id, visible)

        override fun remove() {
            support.untrack(target.id)
            targets.remove(target.id)
        }

        override fun position(): Point = target.position

        override fun size(): Size = target.size

        override fun definition(): TargetDefinition = target.definition

        override fun equals(other: Any?): Boolean = other is Handle && other.target.id == target.id

        override fun hashCode(): Int = target.id.hashCode()
    }

    // ---- Text

    override fun showText(text: String, x: Double, y: Double, style: TextStyle): TextHandle {
        val id = nextId.incrementAndGet()
        ui {
            drill.addText(DrillText(id, text, x, y, style))
            texts += id
        }

        return object : TextHandle {
            override fun setText(newText: String) = ui { drill.updateText(id) { it.copy(text = newText) } }

            override fun move(newX: Double, newY: Double) = ui { drill.updateText(id) { it.copy(x = newX, y = newY) } }

            override fun remove() = ui {
                drill.removeText(id)
                texts -= id
            }
        }
    }

    override fun showMessage(message: String) {
        if (support.isStopped) return

        if (context.settings.inDebugMode()) println(message)
        context.settings.sessionRecorder.ifPresent { it.recordExerciseFeedMessage(message) }

        ui {
            drill.setMessage(message)
            showsMessage = true
        }
    }

    // ---- Controls

    override fun addButton(label: String, onClick: Runnable): ButtonHandle {
        val id = nextId.incrementAndGet()
        ui {
            drill.addButton(DrillButton(id, label) { support.run(guarded { onClick.run() }) })
            buttons += id
        }

        return object : ButtonHandle {
            override fun setLabel(newLabel: String) = ui { drill.relabelButton(id, newLabel) }

            override fun remove() = ui {
                drill.removeButton(id)
                buttons -= id
            }
        }
    }

    override fun addNumberSetting(label: String, initial: Double, min: Double, max: Double, step: Double, onChange: DoubleConsumer) {
        val id = nextId.incrementAndGet()
        ui {
            drill.addSetting(
                NumberSetting(id, label, initial, min, max, step) { value ->
                    drill.setSettingValue(id, value)
                    support.run(guarded { onChange.accept(value) })
                },
            )
            settings += id
        }
    }

    // ---- Shot timer

    override fun addColumn(name: String) = ui {
        context.timer.addColumn(name)
        columns += name
    }

    override fun setColumnValue(name: String, value: String) = ui {
        if (!context.timer.setColumnValue(name, value)) logger.warn("{} set {} on an empty shot timer", this.name, name)
    }

    override fun styleLastRow(style: RowStyle) = ui { context.timer.styleLastRow(style.highlightColor()) }

    override fun addTimerRow(timeMillis: Long, style: RowStyle) = ui { context.timer.addTimerRow(timeMillis, style.highlightColor()) }

    // ---- Shots

    override fun showShotMarker(x: Double, y: Double, style: ShotStyle): ShotMarkerHandle {
        var marker: Marker? = null
        ui {
            marker = drill.markers.add(x, y, style.color(), context.settings.markerRadius).also { markers += it }
        }
        return ShotMarkerHandle {
            ui {
                marker?.let {
                    drill.markers.remove(it)
                    markers -= it
                }
            }
        }
    }

    override fun clearShots() {
        if (support.isStopped) return

        context.clearShots()
        ui {
            drill.markers.removeAll(markers)
            markers.clear()
        }
    }

    override fun pauseShotDetection(paused: Boolean) = support.pauseShotDetection(paused) { context.setDetecting(it) }

    // ---- Sound

    override fun playSound(resourceOrFile: String) = support.playSound(resourceOrFile) { name, sound, whenDone ->
        context.sounds.play(name, sound) { whenDone.run() }
    }

    override fun playSounds(resourcesOrFiles: List<String>) = support.playSounds(resourcesOrFiles) { name, sound, whenDone ->
        context.sounds.play(name, sound) { whenDone.run() }
    }

    override fun say(text: String) {
        if (!support.isStopped) context.sounds.say(text)
    }

    // ---- Time

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun schedule(task: Runnable, delay: Duration): Cancellable = support.schedule(guarded { task.run() }, delay)

    override fun scheduleRepeating(task: Runnable, initialDelay: Duration, period: Duration): Cancellable =
        support.scheduleRepeating(guarded { task.run() }, initialDelay, period)

    // ---- Resources

    override fun resource(path: String): Optional<InputStream> = support.resource(path)

    override fun dataDirectory(): Path = support.dataDirectory()

    // ---- Shared settings

    override fun parTime(): Double = support.parTime()

    override fun setParTime(seconds: Double) {
        support.setParTime(seconds)
        ui { drill.updateTiming { it.copy(parTime = seconds) } }
    }

    override fun onParTimeChanged(listener: DoubleConsumer) {
        support.onParTimeChanged { seconds -> guarded { listener.accept(seconds) }.run() }
        showTimingControls(withParTime = true)
    }

    override fun delayedStart(): DelayRange = support.delayedStart()

    override fun setDelayedStart(range: DelayRange) {
        support.setDelayedStart(range)
        ui { drill.updateTiming { it.copy(delay = range) } }
    }

    override fun onDelayedStartChanged(listener: Consumer<DelayRange>) {
        support.onDelayedStartChanged { range -> guarded { listener.accept(range) }.run() }
        showTimingControls(withParTime = false)
    }

    // The controls show the current values
    private fun showTimingControls(withParTime: Boolean) = ui {
        val current = drill.timing.value
        drill.setTiming(
            TimingControls(
                showsParTime = withParTime || (current?.showsParTime ?: false),
                parTime = support.parTime(),
                delay = support.delayedStart(),
                onParTime = ::userChangedParTime,
                onDelay = ::userChangedDelayedStart,
            ),
        )
        showsTiming = true
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.camera.Shot
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.exercise.Exercise
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.Optional

/** The exercise the Compose app is running, and its host. */
class Running(val entry: V2ExerciseEntry, val host: ComposeExerciseHost)

/**
 * The Compose app's current exercise: at most one runs. Starting one stops the one before. Shots reach
 * it by surface: arena shots only a projector exercise, camera shots only a camera exercise. Calibration
 * stops a projector exercise and starts it afresh afterwards.
 *
 * @param newHost makes a host for a fresh instance of an entry's exercise on the surface it runs on;
 *   null when that surface isn't there (no arena for a projector exercise)
 */
class ExerciseRunner(private val newHost: (V2ExerciseEntry, Exercise, ExerciseRunner) -> ComposeExerciseHost?) :
    ShotReceiver, CalibrationFlow.Exercises {
    private val logger = LoggerFactory.getLogger(ExerciseRunner::class.java)
    private val runningState = MutableStateFlow<Running?>(null)
    private val failureState = MutableStateFlow<String?>(null)

    val running: StateFlow<Running?> = runningState.asStateFlow()

    /** Why the last exercise stopped on its own, until dismissed */
    val failure: StateFlow<String?> = failureState.asStateFlow()

    /**
     * Stops the running exercise and starts a fresh instance of [entry].
     *
     * @return false if it couldn't start (logged)
     */
    @Synchronized
    fun start(entry: V2ExerciseEntry): Boolean {
        stop()
        failureState.value = null

        val exercise = try {
            entry.newInstance()
        } catch (e: ReflectiveOperationException) {
            logger.error("Failed to start exercise {} {}", entry.metadata().name, entry.metadata().version, e)
            failureState.value = "${entry.metadata().name} couldn't start: ${e.javaClass.simpleName}"
            return false
        }

        val host = newHost(entry, exercise, this) ?: run {
            logger.error("{} needs the projector arena", entry.metadata().name)
            return false
        }
        runningState.value = Running(entry, host)
        host.start()
        return true
    }

    @Synchronized
    fun stop() {
        val running = runningState.value ?: return
        runningState.value = null
        running.host.stop()
    }

    fun reset() {
        runningState.value?.host?.reset()
    }

    fun dismissFailure() {
        failureState.value = null
    }

    /**
     * An exercise's callback threw: the exercise is stopped, off its own thread, and the user told why.
     */
    fun failed(host: ComposeExerciseHost, error: Throwable) {
        if (runningState.value?.host !== host) return
        failureState.value = "${host.name} stopped: ${error.javaClass.simpleName}" + (error.message?.let { ": $it" } ?: "")
        Thread({
            synchronized(this) {
                if (runningState.value?.host === host) stop()
            }
        }, "Stopping ${host.name}").start()
    }

    override fun deliver(shot: Shot, hit: Hit?, arenaShot: Boolean): Boolean {
        val host = runningState.value?.host ?: return false
        if (host.isProjector != arenaShot) return true
        host.deliverShot(shot, hit)
        return true
    }

    /** Calibration stops a projector exercise first; this starts it again afterwards. */
    override fun stopProjectorExercise(): Optional<Runnable> {
        val running = runningState.value
        if (running == null || !running.host.isProjector) return Optional.empty()

        stop()
        return Optional.of(Runnable { start(running.entry) })
    }
}
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.drill.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 22 tests passing: 8 contract tests on the feed, 8 on the arena, and 6 runner tests.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `540/540 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillState.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/HostSurface.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/SoundOutput.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/drill/ComposeHostHarness.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestComposeExerciseHostContract.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestComposeExerciseHostContractOnArena.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestExerciseRunner.kt
git commit -m "Add ComposeExerciseHost and the exercise runner, and run the ExerciseHost contract against them"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 10: The drill panel: the card, the settings, the texts and the shot timer

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/drill/WebColors.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillCard.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseTexts.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/ShotTimerTable.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillSettings.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestWebColors.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestDrillPanel.kt`

**Interfaces:**
- Consumes: Task 9's `DrillState`, `DrillButton`, `NumberSetting`, `DrillText`, `TimingControls`; Task 6's `ShotTimerModel`, `RowView`, `MarkerLayer`; Task 4's `SurfaceTransform`; Task 1's `Range`, `NumberStyle`.
- Produces (`com.shootoff.compose.drill`):
  - `fun webColor(name: String): Color`
  - `@Composable DrillCard(drill, modifier)`, tags `drill-card`, `drill-text-<n>` and `drill-button-<label>`
  - `@Composable ExerciseOverlay(drill, transform, modifier)`, tag `exercise-text-<id>`
  - `@Composable ShotTimerTable(timer, modifier)`: tag `shot-timer`; each row is tagged `timer-row-<n>`, with its highlight name, or "plain", as its state description
  - `@Composable DrillSettings(drill, modifier)`: tags `setting-<label>`, `par-time`, `delay-min` and `delay-max`, each with `-down` and `-up` buttons

**What it shows (spec §2 "Drill panel", ruling 14).**
- The card floats over the big view (Task 11). It shows the drill's name, the first line of each of its texts (the first big, in the bright orange) and its buttons.
- The exercise's texts and markers are drawn on its surface at their coordinates and font size.
- The shot timer has #, Time, Split and Laser, then the drill's columns.
  - A highlighted row is tinted in its color: the par drill's coral miss row.
  - The split after a malfunction is orange, and after a reload light blue, as in JavaFX.
  - New rows scroll into view and animate in.
- `webColor` reads the CSS names the exercises use, and "#rgb"/"#rrggbb"/"#rrggbbaa".

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestWebColors.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestWebColors {
    @Test
    fun namesAndCodesReadAsJavaFxReadsThem() {
        assertEquals(Color(0xFFFF7F50), webColor("coral"))
        assertEquals(Color(0xFFFF7F50), webColor(" Coral "))
        assertEquals(Color.Transparent, webColor("transparent"))
        assertEquals(Color(0xFF12AB34), webColor("#12ab34"))
        assertEquals(Color(0xFFFFAA00), webColor("#fa0"))
        assertEquals(Color(0x8012AB34), webColor("#12ab3480"))
        assertEquals(Color(0xFF808080), webColor("no such color"))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestDrillPanel.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.exercise.DelayRange
import com.shootoff.exercise.TextStyle
import com.shootoff.geom.Size
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestDrillPanel {
    @get:Rule
    val compose = createComposeRule()

    private val drill = DrillState()
    private val heard = mutableListOf<String>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) { content() }
            }
        }
    }

    @Test
    fun theCardShowsTheDrillsNameTextsAndButtons() {
        drill.setName("Random Target PAR Drill with Score")
        drill.addText(DrillText(1, "Score: 35", 10.0, 10.0, TextStyle(40.0, "white", "transparent")))
        drill.addText(DrillText(2, "Round: 4/10\nPar 4.00", 300.0, 10.0, TextStyle(40.0, "white", "transparent")))
        drill.addButton(DrillButton(3, "Pause") { heard += "pause" })
        drill.addButton(DrillButton(4, "Clear Shots") { heard += "clear" })
        show { DrillCard(drill) }

        compose.onNodeWithText("RANDOM TARGET PAR DRILL WITH SCORE").assertExists()
        compose.onNodeWithTag("drill-text-0").assertTextEquals("Score: 35")
        compose.onNodeWithTag("drill-text-1").assertTextEquals("Round: 4/10")
        compose.onNodeWithTag("drill-button-Pause").performClick()
        compose.onNodeWithTag("drill-button-Clear Shots").performClick()

        assertEquals(listOf("pause", "clear"), heard)
    }

    @Test
    fun noDrillNoCard() {
        show { DrillCard(drill) }

        compose.onNodeWithTag("drill-card").assertDoesNotExist()
    }

    @Test
    fun theShotTimerShowsTheExercisesColumnsAndItsCoralRow() {
        val timer = ShotTimerModel()
        timer.addColumn("Length")
        timer.addColumn("Score")
        timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 2.0, 1500), false, false)
        timer.setColumnValue("Score", "10")
        timer.addTimerRow(5600, "coral")
        timer.setColumnValue("Length", "4.20")
        timer.setColumnValue("Score", "0")
        show { Box(Modifier.size(600.dp, 300.dp)) { ShotTimerTable(timer) } }

        compose.onNodeWithText("Length").assertExists()
        compose.onNodeWithText("4.20").assertExists()
        compose.onNodeWithTag("timer-row-0").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "plain"))
        compose.onNodeWithTag("timer-row-1").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "coral"))
    }

    @Test
    fun numberSettingsStepAndTakeTypedValuesOnEnter() {
        drill.addSetting(NumberSetting(1, "Rounds", 10.0, 1.0, 20.0, 1.0) { value ->
            heard += "rounds $value"
            drill.setSettingValue(1, value)
        })
        show { DrillSettings(drill) }

        compose.onNodeWithTag("setting-Rounds-up").performClick()
        compose.onNodeWithTag("setting-Rounds").performTextReplacement("15")
        compose.onNodeWithTag("setting-Rounds").performKeyInput { pressKey(Key.Enter) }
        // Out of range: ignored, and the field shows the value again
        compose.onNodeWithTag("setting-Rounds").performTextReplacement("99")
        compose.onNodeWithTag("setting-Rounds").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("rounds 11.0", "rounds 15.0"), heard)
        compose.onNodeWithTag("setting-Rounds").assertTextEquals("15")
    }

    @Test
    fun parTimeAndStartDelayReachTheExercise() {
        drill.setTiming(TimingControls(true, 2.0, DelayRange(4, 8), { heard += "par $it" }, { min, max -> heard += "delay $min-$max" }))
        show { DrillSettings(drill) }

        compose.onNodeWithTag("par-time-down").performClick()
        compose.onNodeWithTag("delay-max").performTextReplacement("6")
        compose.onNodeWithTag("delay-max").performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("par 1.9", "delay 4-6"), heard)
    }

    @Test
    fun anExercisesTextSitsAtItsPlaceOnTheSurface() {
        drill.addText(DrillText(7, "Score: 0", 100.0, 50.0, TextStyle(40.0, "white", "transparent")))
        // A 1280x720 arena shown at half size
        show { Box(Modifier.size(640.dp, 360.dp)) { ExerciseOverlay(drill, SurfaceTransform.fit(Size(1280.0, 720.0), 640f, 360f)) } }

        compose.onNodeWithTag("exercise-text-7").assertLeftPositionInRootIsEqualTo(50.dp).assertTopPositionInRootIsEqualTo(25.dp)
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.drill.TestWebColors' --tests 'com.shootoff.compose.drill.TestDrillPanel' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'webColor'` (and `'DrillCard'`, `'ShotTimerTable'`, `'DrillSettings'`, `'ExerciseOverlay'`), then `BUILD FAILED`.

- [ ] **Step 3: Write the panel**

`compose-app/src/main/kotlin/com/shootoff/compose/drill/WebColors.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.ui.graphics.Color
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.shootoff.compose.drill.WebColors")

// The CSS names exercises use for text, backgrounds and row highlights
private val NAMED = mapOf(
    "transparent" to Color(0x00000000),
    "black" to Color(0xFF000000),
    "white" to Color(0xFFFFFFFF),
    "red" to Color(0xFFFF0000),
    "green" to Color(0xFF008000),
    "lime" to Color(0xFF00FF00),
    "blue" to Color(0xFF0000FF),
    "yellow" to Color(0xFFFFFF00),
    "orange" to Color(0xFFFFA500),
    "coral" to Color(0xFFFF7F50),
    "tomato" to Color(0xFFFF6347),
    "gold" to Color(0xFFFFD700),
    "gray" to Color(0xFF808080),
    "grey" to Color(0xFF808080),
    "lightgray" to Color(0xFFD3D3D3),
    "darkgray" to Color(0xFFA9A9A9),
    "silver" to Color(0xFFC0C0C0),
    "lightskyblue" to Color(0xFF87CEFA),
    "lightblue" to Color(0xFFADD8E6),
    "lightgreen" to Color(0xFF90EE90),
    "limegreen" to Color(0xFF32CD32),
    "cyan" to Color(0xFF00FFFF),
    "magenta" to Color(0xFFFF00FF),
    "pink" to Color(0xFFFFC0CB),
    "purple" to Color(0xFF800080),
    "brown" to Color(0xFFA52A2A),
    "navy" to Color(0xFF000080),
)

/**
 * A color an exercise names, as JavaFX's Color.web reads the common ones: a CSS name, or "#rgb",
 * "#rrggbb" or "#rrggbbaa". Anything else is gray, and logged.
 */
fun webColor(name: String): Color {
    val key = name.trim().lowercase()
    NAMED[key]?.let { return it }

    if (key.startsWith("#")) {
        val hex = key.substring(1)
        val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        val value = expanded.toLongOrNull(16)
        when {
            value != null && expanded.length == 6 -> return Color(0xFF000000 or value)
            value != null && expanded.length == 8 -> return Color(((value and 0xFF) shl 24) or (value shr 8))
        }
    }

    logger.warn("Unknown color {}; using gray", name)
    return Color(0xFF808080)
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillCard.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range

/**
 * The running drill's card, floating over the big view: its name, its texts' first lines (the first
 * one big, as the score is in the mockup) and its buttons. Nothing shows while no drill runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrillCard(drill: DrillState, modifier: Modifier = Modifier) {
    val name by drill.name.collectAsState()
    val texts by drill.texts.collectAsState()
    val buttons by drill.buttons.collectAsState()
    val colors = Range.colors
    val shown = name ?: return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.highlightCard.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, colors.highlightBorder),
        modifier = modifier.widthIn(min = 180.dp, max = 300.dp).animateContentSize().testTag("drill-card"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                shown.uppercase(),
                color = colors.mutedStrong,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            texts.forEachIndexed { index, text ->
                val line = text.text.lineSequence().firstOrNull().orEmpty()
                AnimatedContent(line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "drill text") { value ->
                    if (index == 0) {
                        Text(value, style = Range.bigNumber, color = colors.bigNumber, modifier = Modifier.testTag("drill-text-$index"))
                    } else {
                        Text(value, style = NumberStyle.copy(fontSize = 12.sp), color = colors.mutedStrong, modifier = Modifier.testTag("drill-text-$index"))
                    }
                }
            }
            if (buttons.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    buttons.forEachIndexed { index, button ->
                        if (index == 0) {
                            Button(
                                onClick = button.onClick,
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                                modifier = Modifier.testTag("drill-button-${button.label}"),
                            ) { Text(button.label) }
                        } else {
                            FilledTonalButton(onClick = button.onClick, modifier = Modifier.testTag("drill-button-${button.label}")) {
                                Text(button.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseTexts.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import com.shootoff.compose.shots.MarkerLayer
import com.shootoff.compose.surface.SurfaceTransform
import kotlin.math.roundToInt

/**
 * The running exercise's texts and markers on its surface, at the surface's scale: a text's top left
 * is at its (x, y), in its font size and colors.
 */
@Composable
fun ExerciseOverlay(drill: DrillState, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val texts by drill.texts.collectAsState()
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        MarkerLayer(drill.markers, transform)
        for (text in texts) {
            val topLeft = transform.toView(text.x, text.y)
            Text(
                text.text,
                color = webColor(text.style.textColor()),
                fontSize = with(density) { (text.style.fontSize() * transform.scale).toFloat().toSp() },
                lineHeight = with(density) { (text.style.fontSize() * transform.scale * 1.2).toFloat().toSp() },
                modifier = Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .background(webColor(text.style.backgroundColor()))
                    .testTag("exercise-text-${text.id}"),
            )
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ShotTimerTable.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.shots.RowView
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range

// The JavaFX shot timer's split colors for a shot after a malfunction or a reload
private val MALFUNCTION = Color(0xFFFFA500)
private val RELOAD = Color(0xFF87CEFA)

/**
 * The shot timer: #, Time, Split and Laser, then the running exercise's columns. A row the exercise
 * highlights (e.g. the par drill's coral par-miss row) is tinted in its color. New rows scroll into view.
 */
@Composable
fun ShotTimerTable(timer: ShotTimerModel, modifier: Modifier = Modifier) {
    val rows by timer.rows.collectAsState()
    val columns by timer.columns.collectAsState()
    val colors = Range.colors
    val list = rememberLazyListState()

    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) list.animateScrollToItem(rows.size - 1)
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Header("#", 28)
            Header("Time", 64)
            Header("Split", 64)
            Header("Laser", 64)
            for (column in columns) Header(column, 72)
        }
        HorizontalDivider(color = colors.cardBorder)
        LazyColumn(state = list, modifier = Modifier.testTag("shot-timer")) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
                TimerRow(index, row, columns, Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun RowScope.Header(name: String, width: Int) {
    Text(name, color = Range.colors.muted, fontSize = 11.sp, modifier = Modifier.width(width.dp))
}

@Composable
private fun TimerRow(index: Int, row: RowView, columns: List<String>, modifier: Modifier) {
    val colors = Range.colors
    val tint = row.highlight?.let { webColor(it).copy(alpha = 0.55f) } ?: Color.Transparent
    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.Start,
            modifier = Modifier
                .fillMaxWidth()
                .background(tint, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .testTag("timer-row-$index")
                .semantics { stateDescription = row.highlight ?: "plain" },
        ) {
            Cell("${index + 1}", 28)
            Cell(row.row.time(), 64)
            val split = when {
                row.row.hadMalfunction() -> MALFUNCTION
                row.row.hadReload() -> RELOAD
                else -> null
            }
            Cell(row.row.split(), 64, background = split)
            Cell(row.row.laser(), 64)
            for (column in columns) Cell(row.values[column].orEmpty(), 72)
        }
        HorizontalDivider(color = colors.cardBorder)
    }
}

@Composable
private fun RowScope.Cell(text: String, width: Int, background: Color? = null) {
    Text(
        text,
        style = NumberStyle.copy(fontSize = 12.sp),
        color = Range.colors.text,
        modifier = Modifier.width(width.dp).let { if (background != null) it.background(background.copy(alpha = 0.6f)) else it },
    )
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillSettings.kt`:

```kotlin
package com.shootoff.compose.drill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shootoff.compose.theme.NumberStyle
import com.shootoff.compose.theme.Range
import java.util.Locale

/**
 * The running drill's settings: its number settings, and the shared par time and start delay while it
 * listens to them. A typed value counts once the user presses Enter or leaves the field; − and + step it.
 */
@Composable
fun DrillSettings(drill: DrillState, modifier: Modifier = Modifier) {
    val settings by drill.settings.collectAsState()
    val timing by drill.timing.collectAsState()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (setting in settings) {
            NumberField(setting.label, setting.value, setting.min, setting.max, setting.step, "setting-${setting.label}") {
                setting.onChange(it)
            }
        }
        timing?.let { controls ->
            if (controls.showsParTime) {
                NumberField("Par time (s)", controls.parTime, 0.0, 3600.0, 0.1, "par-time") { controls.onParTime(it) }
            }
            NumberField("Delay from (s)", controls.delay.minSeconds().toDouble(), 0.0, 3600.0, 1.0, "delay-min") {
                controls.onDelay(it.toInt(), controls.delay.maxSeconds())
            }
            NumberField("Delay to (s)", controls.delay.maxSeconds().toDouble(), 0.0, 3600.0, 1.0, "delay-max") {
                controls.onDelay(controls.delay.minSeconds(), it.toInt())
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    step: Double,
    tag: String,
    onCommit: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(format(value, step)) }
    val commit = {
        val typed = text.toDoubleOrNull()
        if (typed != null && typed in min..max) {
            if (typed != value) onCommit(typed)
        } else {
            text = format(value, step)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Range.colors.mutedStrong, modifier = Modifier.width(120.dp))
        TextButton(onClick = { stepped(value - step).takeIf { it >= min }?.let(onCommit) }, modifier = Modifier.testTag("$tag-down")) { Text("−") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = NumberStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(96.dp)
                .testTag(tag)
                .onFocusChanged { if (!it.isFocused) commit() }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        commit()
                        true
                    } else {
                        false
                    }
                },
        )
        TextButton(onClick = { stepped(value + step).takeIf { it <= max }?.let(onCommit) }, modifier = Modifier.testTag("$tag-up")) { Text("+") }
    }
}

// A step's sum without floating point dust (2.0 - 0.1 is 1.9)
private fun stepped(value: Double): Double = Math.round(value * 1_000_000) / 1_000_000.0

// Whole numbers without a decimal point, others to two places at most
private fun format(value: Double, step: Double): String =
    if (step >= 1 && value == Math.rint(value)) {
        value.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }
```


- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.drill.TestWebColors' --tests 'com.shootoff.compose.drill.TestDrillPanel' --console=plain`

Expected: `BUILD SUCCESSFUL`, 7 tests passing.

- [ ] **Step 5: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `547/547 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 6: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/drill/WebColors.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillCard.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseTexts.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ShotTimerTable.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillSettings.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/drill/TestWebColors.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestDrillPanel.kt
git commit -m "Render the drill panel: the drill card, its settings, its texts and the shot timer"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 11: The screens and the wiring: Range, Drills and Settings over the app state

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/ExerciseCatalog.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/CameraSource.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/DrillsScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt` (replaces Task 1's)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt` (`AppFixture` is a helper)

**Interfaces:**
- Consumes everything above, and from `core` and `plugin-api`:
  - `PluginEngine(PluginListener, List<ExerciseLoader>, List<ExerciseEntry>)`, `startWatching`/`stopWatching`, `V2ExerciseLoader`, `V2ExerciseEntry`
  - `CamerasSupervisor.addCameraManager(Camera, CameraErrorView, CameraView)`, `clearManager`, `closeAll`, `setDetectingAll`
  - `CameraFactory.getWebcams()/getDefault()`, `CameraManager.setCalibrationManager(CameraCalibrationListener)`
  - `Settings` (`getDisplayWidth/Height`, `getWebcams`, `setWebcams`, `getMarkerRadius`/`setMarkerRadius`, `getArenaPosition`/`setArenaPosition`, `writeConfigurationFile`, `getWebcamsUserName`, `getSessionRecorder`)
  - `ArenaGeometry.arenaToCamera`, `TimerPool.schedule/close`, `TextToSpeech.say`, `Loader.load(opencv_java)`
- Produces (`com.shootoff.compose.app`):
  - `class ExerciseCatalog : PluginListener`, with `entries: StateFlow<List<V2ExerciseEntry>>`
  - `interface CameraSource { cameras(); startCamera(settings) }` with `System` and `None`
  - `enum BigView { CAMERA, ARENA }`
  - `class AppState(settings, catalog = ExerciseCatalog(), cameraSource = CameraSource.None, screens: () -> List<Rect> = ArenaScreens::screens, clock = AnimationClock.background, uiThread: (Runnable) -> Unit = EventQueue::invokeLater) : CalibrationViews`:
    - its parts: `timer`, `drill`, `feed`, `feedTargets`, `feedMarkers`, `feedSurface`, `cameraView`, `cameras`, `rangeReset`, `runner`
    - its state (StateFlows): `arena`, `arenaPlacement`, `calibration`, `camera`, `destination`, `view`
    - `var mainWindowCorner: Point`
    - `navigate`, `showView`, `openStartCamera`, `availableCameras`, `openCamera(Camera): Boolean`, `screensNow`, `arenaPlacementNow`, `projectorScreenFound`, `openArena`, `closeArena`, `toggleCalibration`, `calibrationStatus`, `startDrill(entry): Boolean`, `stopDrill`, `reset`, `clearShots`, `close`
  - Composables: `RangeScreen(app)`, `ViewSwitch(app)` (tags `view-camera`, `view-arena`, `arena-hint`), `DrillsScreen(app)` (`NEEDS_ARENA`, tags `drill-<name>`, `start-drill`, `stop-drill`), `SettingsScreen(app)` (tags `camera-<name>`, `marker-size`, `screen-<x>`), `ShootOffApp(app)`. Range's actions are tagged `open-arena`, `close-arena`, `calibrate` and `reset`.

**The app state.** `AppState` wires the pieces as the JavaFX controller does, but for one camera (ruling 13):
- The feed's `FeedSurface` passes shots to the open arena's `ArenaSurface` through the calibrated projection.
- Region commands' `reset` is `RangeReset` with the runner's reset. The arena's `poi_adjust` maps through the camera's projection (ruling 7).
- New hosts get the arena for projector drills and the feed otherwise (ruling 10).
- Opening the arena creates its `CalibrationController` on the open camera and starts calibrating. The app then tells it each full-screen change (ruling 9).

**The screens (spec §1 and §2).**
- **Range:** the big view (the camera feed with its targets, markers, the exercise's overlay and calibration; or the arena view), with over it:
  - the Camera | Arena switch
  - Open/Close arena, Calibrate and Reset
  - the drill card, top right
  - the banners, top center
  - the status strip, bottom left

  Below the big view is the tray: the shot timer and the drill's settings.
- **Drills:** the registered v2 drills. A projector drill needs the open arena (ruling 15). Starting one goes to Range.
- **Settings:** camera, marker size and arena display (ruling 13).
- The rail's Targets and Sessions stay disabled (Task 1).

**Main.** It sets the ShootOFF system properties as JavaFX's `Main` does, loads OpenCV, reads `shootoff.properties`, warms up speech off the UI thread, and starts the plugin engine with the v2 loader only (ruling 12). Then it opens the start camera, the main window and, while the arena is open, the arena window.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.Shot
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.geom.Rect
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import java.util.Optional

/** An app with no camera, the owner's three screens, and two drills in its catalog. */
object AppFixture {
    val ownerScreens = listOf(Rect(1920.0, 0.0, 2560.0, 1440.0), Rect(0.0, 0.0, 1920.0, 1080.0), Rect(4480.0, 0.0, 1280.0, 720.0))

    class ProjectorDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Projector drill", "2.0", "ShootOFF tests", "On the arena", true)

        override fun start(host: ExerciseHost) {
            host.addButton("Pause") {}
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    class FeedDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Feed drill", "1.0", "ShootOFF tests", "On the camera feed")

        override fun start(host: ExerciseHost) {}

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    val projectorDrill = V2ExerciseEntry(ProjectorDrill::class.java, ProjectorDrill().metadata())
    val feedDrill = V2ExerciseEntry(FeedDrill::class.java, FeedDrill().metadata())

    fun app(screens: List<Rect> = ownerScreens): AppState {
        val catalog = ExerciseCatalog()
        catalog.registerProjectorExercise(projectorDrill)
        catalog.registerExercise(feedDrill)
        return AppState(
            Settings(ScratchConfig.emptyFile().path, arrayOf()),
            catalog,
            CameraSource.None,
            { screens },
            ManualClock(),
            { it.run() },
        )
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.compose.feed.CalibrationStatus
import com.shootoff.compose.shell.Destination
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestAppState {
    private val app = AppFixture.app()

    @AfterEach
    fun close() = app.close()

    @Test
    fun theCatalogListsTheV2DrillsByName() {
        assertEquals(listOf("Feed drill", "Projector drill"), app.catalog.entries.value.map { it.metadata().name })
    }

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

    @Test
    fun withoutACameraTheArenaOpensButCantCalibrate() {
        app.openArena()

        assertNotNull(app.arena.value)
        assertNull(app.calibration.value)
        assertEquals(CalibrationStatus.NEEDS_CALIBRATION, app.calibrationStatus())
    }

    @Test
    fun onOneScreenNoProjectorIsFound() {
        val single = AppFixture.app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        try {
            assertFalse(single.projectorScreenFound())
            assertTrue(app.projectorScreenFound())
        } finally {
            single.close()
        }
    }

    @Test
    fun targetsAndSessionsCantBeNavigatedTo() {
        app.navigate(Destination.TARGETS)
        app.navigate(Destination.SESSIONS)

        assertEquals(Destination.RANGE, app.destination.value)
    }

    private fun waitForButtons(): List<String> {
        val deadline = System.currentTimeMillis() + 5000
        while (app.drill.buttons.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return app.drill.buttons.value.map { it.label }
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TestScreens {
    @get:Rule
    val compose = createComposeRule()

    private var app = AppFixture.app()

    @After
    fun close() = app.close()

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
    fun drillsMarkProjectorDrillsUntilTheArenaIsOpen() {
        show { DrillsScreen(app) }

        compose.onNodeWithTag("drill-Projector drill").assertExists()
        compose.onNodeWithText(NEEDS_ARENA).assertExists()

        app.openArena()
        compose.waitForIdle()
        compose.onNodeWithText(NEEDS_ARENA).assertDoesNotExist()
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

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'AppState'` (and `'ExerciseCatalog'`, `'CameraSource'`, `'ShootOffApp'`, …), then `BUILD FAILED`.

- [ ] **Step 3: Write the app state and the screens**

`compose-app/src/main/kotlin/com/shootoff/compose/app/ExerciseCatalog.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.plugins.engine.ExerciseEntry
import com.shootoff.plugins.engine.PluginListener
import com.shootoff.plugins.engine.V2ExerciseEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The exercises the Drills screen lists: the v2 exercises the plugin engine registers (the Compose app
 * runs no v1 exercise), by name.
 */
class ExerciseCatalog : PluginListener {
    private val entryState = MutableStateFlow<List<V2ExerciseEntry>>(emptyList())
    val entries: StateFlow<List<V2ExerciseEntry>> = entryState.asStateFlow()

    override fun registerExercise(exercise: ExerciseEntry) = add(exercise)

    override fun registerProjectorExercise(exercise: ExerciseEntry) = add(exercise)

    override fun unregisterExercise(exercise: ExerciseEntry) = entryState.update { entries -> entries.filterNot { it == exercise } }

    private fun add(exercise: ExerciseEntry) {
        if (exercise !is V2ExerciseEntry) return
        entryState.update { entries -> (entries + exercise).sortedBy { it.metadata().name } }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/CameraSource.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.CameraFactory
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.config.Settings

/** The cameras the app can open. */
interface CameraSource {
    /** Every camera plugged in */
    fun cameras(): List<Camera>

    /** The camera to open at start: the configured one, else the system default */
    fun startCamera(settings: Settings): Camera?

    object System : CameraSource {
        override fun cameras(): List<Camera> = CameraFactory.getWebcams()

        override fun startCamera(settings: Settings): Camera? =
            settings.webcams.values.firstOrNull() ?: CameraFactory.getDefault().orElse(null)
    }

    object None : CameraSource {
        override fun cameras(): List<Camera> = emptyList()

        override fun startCamera(settings: Settings): Camera? = null
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.CameraManager
import com.shootoff.camera.CamerasSupervisor
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
import com.shootoff.compose.calibration.CalibrationController
import com.shootoff.compose.calibration.CalibrationViews
import com.shootoff.compose.drill.ArenaHostSurface
import com.shootoff.compose.drill.ComposeExerciseHost
import com.shootoff.compose.drill.DrillState
import com.shootoff.compose.drill.ExerciseRunner
import com.shootoff.compose.drill.FeedHostSurface
import com.shootoff.compose.drill.HostContext
import com.shootoff.compose.drill.SoundOutput
import com.shootoff.compose.feed.CalibrationStatus
import com.shootoff.compose.feed.ComposeCameraView
import com.shootoff.compose.feed.FeedState
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.shots.FeedSurface
import com.shootoff.compose.shots.RegionCommandRunner
import com.shootoff.compose.shots.ShotMarkers
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.geom.ArenaGeometry
import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.shots.RangeReset
import com.shootoff.util.TimerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.EventQueue
import java.util.concurrent.CompletableFuture

/** The Range screen's big view */
enum class BigView { CAMERA, ARENA }

/**
 * The Compose app's state: the camera and its feed, the arena, calibration, the running drill, the shot
 * timer, and where the user is. Composables read it; user actions call it.
 *
 * @param screens the screens the arena can go on, in AWT's coordinates
 * @param uiThread runs calibration's timers on the UI thread
 */
class AppState(
    val settings: Settings,
    val catalog: ExerciseCatalog = ExerciseCatalog(),
    private val cameraSource: CameraSource = CameraSource.None,
    private val screens: () -> List<Rect> = ArenaScreens::screens,
    private val clock: AnimationClock = AnimationClock.background,
    private val uiThread: (Runnable) -> Unit = EventQueue::invokeLater,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val displaySize = Size(settings.displayWidth.toDouble(), settings.displayHeight.toDouble())
    val cameras = CamerasSupervisor(settings)
    val timer = ShotTimerModel()
    val drill = DrillState()
    val feed = FeedState(displaySize)
    val feedTargets = SurfaceTargets(clock = clock)
    val feedMarkers = ShotMarkers()

    val rangeReset = RangeReset(cameras, { calibration.value?.flow?.isCalibrating ?: false }, { task, delay -> TimerPool.schedule(task, delay) })
    val runner: ExerciseRunner = ExerciseRunner(::newHost)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
    private val cameraState = MutableStateFlow<CameraManager?>(null)
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null

    /** The open arena, or null */
    val arena: StateFlow<ArenaModel?> = arenaState.asStateFlow()

    /** Where the arena window opens, while it is open */
    val arenaPlacement: StateFlow<ArenaPlacement?> = placementState.asStateFlow()

    val calibration: StateFlow<CalibrationController?> = calibrationState.asStateFlow()

    /** The open camera, or null */
    val camera: StateFlow<CameraManager?> = cameraState.asStateFlow()

    val destination: StateFlow<Destination> = destinationState.asStateFlow()
    val view: StateFlow<BigView> = viewState.asStateFlow()

    val feedSurface = FeedSurface(
        "Default",
        settings,
        feedTargets,
        timer,
        feedMarkers,
        { runner },
        { feedCommands },
        { arenaState.value?.surface },
        { arenaState.value?.projection?.value },
    )

    val cameraView = ComposeCameraView("Default", feed, feedSurface)

    private val feedCommands = RegionCommandRunner(feedTargets, settings, ::reset, ::exerciseResources)

    // ---- Where the user is

    fun navigate(destination: Destination) {
        if (destination.enabled) destinationState.value = destination
    }

    fun showView(view: BigView) {
        if (view == BigView.ARENA && arenaState.value == null) return
        viewState.value = view
    }

    override fun showCalibratingFeed() {
        viewBeforeCalibration = viewState.value
        viewState.value = BigView.CAMERA
    }

    override fun restoreSelectedView() {
        viewBeforeCalibration?.let { viewState.value = it }
        viewBeforeCalibration = null
    }

    // ---- The camera

    /** Opens the camera the app starts with, if there is one */
    fun openStartCamera() {
        cameraSource.startCamera(settings)?.let(::openCamera)
    }

    fun availableCameras(): List<Camera> = cameraSource.cameras()

    /**
     * Opens [camera] in place of the open one.
     *
     * @return false if it can't be opened
     */
    fun openCamera(camera: Camera): Boolean {
        cameraState.value?.let(cameras::clearManager)
        cameraState.value = null

        val manager = cameras.addCameraManager(camera, null, cameraView).orElse(null)
        if (manager == null) {
            logger.error("Cannot open the webcam {}", camera.name)
            return false
        }
        cameraState.value = manager
        return true
    }

    // ---- The arena

    /** The main window's top left corner, which tells the screen ShootOFF is on */
    @Volatile
    var mainWindowCorner = Point(0.0, 0.0)

    /** Where the arena would open now */
    fun arenaPlacementNow(): ArenaPlacement {
        val all = screens()
        return ArenaScreens.place(all, ArenaScreens.screenAt(all, mainWindowCorner), settings.arenaPosition.orElse(null))
    }

    fun screensNow(): List<Rect> = screens()

    /** Whether a screen looks like the projector */
    fun projectorScreenFound(): Boolean = arenaPlacementNow().screen != null

    /**
     * Opens the arena window, on the projector if one is found, and starts calibrating it with the open
     * camera, as the JavaFX app does.
     */
    fun openArena() {
        if (arenaState.value != null) return

        placementState.value = arenaPlacementNow()

        lateinit var arena: ArenaModel
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena

        val camera = cameraState.value ?: return
        val controller = CalibrationController(camera, arena, settings, runner, this, { task, delay ->
            TimerPool.schedule(task, delay) ?: CompletableFuture<Void>()
        }, uiThread)
        camera.setCalibrationManager(controller)
        calibrationState.value = controller

        // Calibration hears the arena going full screen, as the JavaFX arena tells it
        fullScreenWatch = scope.launch { arena.fullScreen.drop(1).collect { controller.fullScreenChanged(it) } }

        controller.flow.start()
    }

    /** The arena window closed: calibration ends, a projector drill stops, and the view goes back to the camera. */
    fun closeArena() {
        val arena = arenaState.value ?: return
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
        if (runner.running.value?.host?.isProjector == true) runner.stop()
        arenaState.value = null
        placementState.value = null
        viewState.value = BigView.CAMERA
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }

    fun toggleCalibration() {
        calibrationState.value?.toggle()
    }

    fun calibrationStatus(): CalibrationStatus {
        val arena = arenaState.value ?: return CalibrationStatus.NO_ARENA
        return when {
            calibrationState.value?.state?.value?.calibrating == true -> CalibrationStatus.CALIBRATING
            arena.projection.value != null -> CalibrationStatus.CALIBRATED
            else -> CalibrationStatus.NEEDS_CALIBRATION
        }
    }

    private fun arenaCommands(arena: ArenaModel) = RegionCommandRunner(arena.targets, settings, ::reset, ::exerciseResources, toCamera = { point ->
        // poi_adjust measures the region's center on the camera feed, through the calibrated projection
        val bounds = cameraState.value?.projectionBounds?.orElse(null)
        if (bounds == null) point else ArenaGeometry.arenaToCamera(point.x, point.y, bounds, arena.size.value)
    })

    // ---- Drills

    /**
     * Starts a fresh instance of [entry]; a projector drill needs the open arena.
     *
     * @return false if it couldn't start
     */
    fun startDrill(entry: V2ExerciseEntry): Boolean {
        val started = runner.start(entry)
        if (started) destinationState.value = Destination.RANGE
        return started
    }

    fun stopDrill() = runner.stop()

    /** Reset: the cameras, the arena's animations and the shots, then the drill, then a short pause in detection */
    fun reset() = rangeReset.reset { runner.reset() }

    /** Clears the shot markers and the shot timer */
    fun clearShots() = feedSurface.clear()

    private fun exerciseResources(): ClassLoader? = runner.running.value?.entry?.exerciseClass()?.classLoader

    private fun newHost(entry: V2ExerciseEntry, exercise: Exercise, runner: ExerciseRunner): ComposeExerciseHost? {
        val surface = if (entry.isProjectorOnly) {
            ArenaHostSurface(arenaState.value ?: return null)
        } else {
            FeedHostSurface(feedTargets, displaySize)
        }
        return ComposeExerciseHost(
            exercise,
            HostContext(
                settings,
                surface,
                timer,
                drill,
                entry.exerciseClass().classLoader,
                SoundOutput.speakers,
                clearShots = ::clearShots,
                setDetecting = cameras::setDetectingAll,
                onFailure = runner::failed,
            ),
        )
    }

    /** Stops everything, for the app's exit */
    fun close() {
        runner.stop()
        closeArena()
        cameras.closeAll()
        scope.cancel()
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaView
import com.shootoff.compose.calibration.CalibrationOverlay
import com.shootoff.compose.drill.DrillCard
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

/**
 * The Range screen: the big view (the camera feed or the arena) with the Camera | Arena switch, the
 * drill card and the status strip over it, and the tray with the shot timer and the drill's settings.
 */
@Composable
fun RangeScreen(app: AppState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigViewArea(app, Modifier.weight(1f).fillMaxWidth())
        Tray(app, Modifier.fillMaxWidth().height(220.dp))
    }
}

@Composable
private fun BigViewArea(app: AppState, modifier: Modifier) {
    val view by app.view.collectAsState()
    val arena by app.arena.collectAsState()
    val running by app.runner.running.collectAsState()
    val message by app.drill.message.collectAsState()
    val calibration by app.calibration.collectAsState()
    val colors = Range.colors
    val projectorDrill = running?.host?.isProjector == true

    Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = modifier) {
        Box(Modifier.fillMaxSize()) {
            val shownArena = arena
            if (view == BigView.ARENA && shownArena != null) {
                ArenaView(shownArena, Modifier.fillMaxSize()) { transform ->
                    if (projectorDrill) ExerciseOverlay(app.drill, transform)
                }
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                    TargetLayer(app.feedTargets, transform)
                    MarkerLayer(app.feedMarkers, transform)
                    if (running != null && !projectorDrill) ExerciseOverlay(app.drill, transform)
                    calibration?.let { CalibrationOverlay(it, transform) }
                }
            }

            Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ViewSwitch(app)
                RangeActions(app)
            }
            DrillCard(app.drill, Modifier.align(Alignment.TopEnd).padding(10.dp))
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
            }
            StatusLine(app, Modifier.align(Alignment.BottomStart).padding(10.dp))
        }
    }
}

/** The Camera | Arena segmented switch. Arena is disabled, with a hint, until the arena is open. */
@Composable
fun ViewSwitch(app: AppState) {
    val view by app.view.collectAsState()
    val arena by app.arena.collectAsState()
    val colors = Range.colors
    val arenaHint = when {
        arena != null -> null
        app.projectorScreenFound() -> "Open the arena first"
        else -> "No projector screen found"
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.background.copy(alpha = 0.87f),
            border = BorderStroke(1.dp, colors.chipBorder),
        ) {
            Row(Modifier.padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Segment("Camera", view == BigView.CAMERA, enabled = true, tag = "view-camera") { app.showView(BigView.CAMERA) }
                Segment("Arena", view == BigView.ARENA, enabled = arena != null, tag = "view-arena", hint = arenaHint) {
                    app.showView(BigView.ARENA)
                }
            }
        }
        arenaHint?.let { Text(it, color = colors.muted, fontSize = 11.sp, modifier = Modifier.testTag("arena-hint")) }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, enabled: Boolean, tag: String, hint: String? = null, onClick: () -> Unit) {
    val colors = Range.colors
    val text = when {
        selected -> colors.onAccentSoft
        enabled -> colors.mutedStrong
        else -> colors.muted.copy(alpha = 0.45f)
    }
    Text(
        label,
        color = text,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
                if (hint != null) stateDescription = hint
            }
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .testTag(tag),
    )
}

/** Open or close the arena, calibrate, reset and clear the shots */
@Composable
private fun RangeActions(app: AppState) {
    val arena by app.arena.collectAsState()
    val calibration by app.calibration.collectAsState()
    val calibrating = calibration?.state?.collectAsState()?.value?.calibrating == true

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (arena == null) {
            FilledTonalButton(onClick = { app.openArena() }, modifier = Modifier.testTag("open-arena")) { Text("Open arena") }
        } else {
            FilledTonalButton(onClick = { app.closeArena() }, modifier = Modifier.testTag("close-arena")) { Text("Close arena") }
            FilledTonalButton(onClick = app::toggleCalibration, enabled = calibration != null, modifier = Modifier.testTag("calibrate")) {
                Text(if (calibrating) "Stop calibrating" else "Calibrate")
            }
        }
        FilledTonalButton(onClick = app::reset, modifier = Modifier.testTag("reset")) { Text("Reset") }
    }
}

@Composable
private fun StatusLine(app: AppState, modifier: Modifier) {
    val camera by app.camera.collectAsState()
    val shownFps by app.feed.fps.collectAsState()
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
            app.calibrationStatus(),
            app.settings.sessionRecorder.isPresent,
        ),
        modifier,
    )
}

/** The tray: the shot timer, and the running drill's settings */
@Composable
private fun Tray(app: AppState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrayPanel("SHOT TIMER", Modifier.weight(1f)) { ShotTimerTable(app.timer, Modifier.fillMaxSize()) }
        TrayPanel("SETTINGS", Modifier.width(380.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) { DrillSettings(app.drill) }
        }
    }
}

@Composable
private fun TrayPanel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    val colors = Range.colors
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(title, color = colors.mutedStrong, fontSize = 10.sp, letterSpacing = 0.6.sp)
            content()
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/DrillsScreen.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range
import com.shootoff.plugins.engine.V2ExerciseEntry

const val NEEDS_ARENA = "Needs the projector arena"

/**
 * The Drills screen: the v2 exercises the app can run. A projector drill can start only while the arena
 * is open; until then it says so. Starting one stops the running one and goes to the Range screen.
 */
@Composable
fun DrillsScreen(app: AppState, modifier: Modifier = Modifier) {
    val entries by app.catalog.entries.collectAsState()
    val arena by app.arena.collectAsState()
    val running by app.runner.running.collectAsState()
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Drills", fontSize = 22.sp, color = colors.text)
        if (entries.isEmpty()) {
            Text("No drills found in the exercises folder.", color = colors.muted)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.exerciseClass().name }) { entry ->
                DrillRow(
                    entry,
                    isRunning = running?.entry == entry,
                    canStart = !entry.isProjectorOnly || arena != null,
                    onStart = { app.startDrill(entry) },
                    onStop = app::stopDrill,
                )
            }
        }
    }
}

@Composable
private fun DrillRow(entry: V2ExerciseEntry, isRunning: Boolean, canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val colors = Range.colors
    val metadata = entry.metadata()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isRunning) colors.highlightCard else colors.card,
        border = BorderStroke(1.dp, if (isRunning) colors.highlightBorder else colors.cardBorder),
        modifier = Modifier.fillMaxWidth().testTag("drill-${metadata.name}"),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(metadata.name, color = colors.text, fontSize = 16.sp)
                Text("${metadata.version} · ${metadata.creator}" + if (entry.isProjectorOnly) " · Projector" else "", color = colors.muted, fontSize = 12.sp)
                Text(metadata.description, color = colors.mutedStrong, fontSize = 13.sp)
                if (!canStart) Text(NEEDS_ARENA, color = colors.warning, fontSize = 12.sp, modifier = Modifier.testTag("needs-arena"))
            }
            if (isRunning) {
                FilledTonalButton(onClick = onStop, modifier = Modifier.testTag("stop-drill")) { Text("Stop") }
            } else {
                Button(onClick = onStart, enabled = canStart, modifier = Modifier.testTag("start-drill")) { Text("Start") }
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Rect
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

private val logger = LoggerFactory.getLogger("com.shootoff.compose.app.SettingsScreen")

/**
 * The slim Settings screen: which camera, how big shot markers are, and which screen the arena goes on.
 * Changes are saved to shootoff.properties, which the JavaFX app shares.
 */
@Composable
fun SettingsScreen(app: AppState, modifier: Modifier = Modifier) {
    val colors = Range.colors
    val openCamera by app.camera.collectAsState()
    val cameras = remember { app.availableCameras() }
    var markerRadius by remember { mutableIntStateOf(app.settings.markerRadius) }
    var arenaScreen by remember { mutableStateOf(app.arenaPlacementNow().screen) }
    val screens = remember { app.screensNow() }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 22.sp, color = colors.text)

        Section("Camera") {
            if (cameras.isEmpty()) Text("No cameras found.", color = colors.muted)
            for (camera in cameras) {
                Choice(camera.name, openCamera?.camera == camera, "camera-${camera.name}") {
                    if (app.openCamera(camera)) {
                        app.settings.setWebcams(listOf(camera.name), listOf(camera))
                        save(app)
                    }
                }
            }
        }

        Section("Shot marker size") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = markerRadius.toFloat(),
                    onValueChange = { markerRadius = it.roundToInt() },
                    onValueChangeFinished = {
                        app.settings.setMarkerRadius(markerRadius)
                        save(app)
                    },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.width(280.dp).testTag("marker-size"),
                )
                Text("$markerRadius px", color = colors.text, modifier = Modifier.padding(start = 12.dp))
            }
        }

        Section("Arena display") {
            for (screen in screens) {
                Choice(describe(screen), screen == arenaScreen, "screen-${screen.minX.toInt()}") {
                    // Saved as the JavaFX app saves an arena the user placed by hand
                    app.settings.setArenaPosition(screen.minX, screen.minY)
                    save(app)
                    arenaScreen = screen
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = Range.colors.mutedStrong, fontSize = 11.sp, letterSpacing = 0.6.sp)
        content()
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = Range.colors.text, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun describe(screen: Rect) = "${screen.width.toInt()}×${screen.height.toInt()} at ${screen.minX.toInt()}, ${screen.minY.toInt()}"

private fun save(app: AppState) {
    try {
        app.settings.writeConfigurationFile()
    } catch (e: Exception) {
        logger.error("Couldn't save the settings", e)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

/** The main window's content: the rail, and the destination it leads to. */
@Composable
fun ShootOffApp(app: AppState) {
    val destination by app.destination.collectAsState()
    Row(Modifier.fillMaxSize().background(Range.colors.background)) {
        AppRail(destination, app::navigate)
        Box(Modifier.fillMaxSize()) {
            when (destination) {
                Destination.RANGE -> RangeScreen(app)
                Destination.DRILLS -> DrillsScreen(app)
                Destination.SETTINGS -> SettingsScreen(app)
                // Disabled on the rail
                Destination.TARGETS, Destination.SESSIONS -> RangeScreen(app)
            }
        }
    }
}
```


- [ ] **Step 4: Replace `Main`**

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt` (replaces Task 1's; the header stays):

```kotlin
package com.shootoff.compose

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.shootoff.compose.app.AppState
import com.shootoff.compose.app.CameraSource
import com.shootoff.compose.app.ExerciseCatalog
import com.shootoff.compose.app.ShootOffApp
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

    val app = AppState(settings, catalog, CameraSource.System)
    app.openStartCamera()

    application {
        val arena by app.arena.collectAsState()
        val placement by app.arenaPlacement.collectAsState()
        val running by app.runner.running.collectAsState()
        val state = rememberWindowState(size = DpSize(1280.dp, 860.dp))

        fun exit() {
            plugins.stopWatching()
            app.close()
            TimerPool.close()
            exitApplication()
        }

        Window(onCloseRequest = ::exit, state = state, title = "ShootOFF") {
            LaunchedEffect(state.position) {
                app.mainWindowCorner = Point(window.x.toDouble(), window.y.toDouble())
            }
            RangeTheme(dark = true) { ShootOffApp(app) }
        }

        val shownArena = arena
        val shownPlacement = placement
        if (shownArena != null && shownPlacement != null) {
            ArenaWindow(shownArena, shownPlacement, onCloseRequest = app::closeArena) { transform ->
                if (running?.host?.isProjector == true) ExerciseOverlay(app.drill, transform)
            }
        }
    }
}
```


- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 11 tests passing.

- [ ] **Step 6: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `558/558 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 7: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/ExerciseCatalog.kt compose-app/src/main/kotlin/com/shootoff/compose/app/CameraSource.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/DrillsScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt compose-app/src/main/kotlin/com/shootoff/compose/Main.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/app/AppFixture.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestScreens.kt
git commit -m "Wire the Compose app: Range, Drills and Settings screens over the app state"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 12: Errors and edge cases

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/CameraProblems.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/NoCameraPanel.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/Notices.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt`

**Interfaces:**
- Consumes:
  - `core`'s `CameraErrorView` (`MISSING_ERROR`, `FPS_WARNING`, `BRIGHTNESS_WARNING`, and its four methods), `UserNotifier`, `Settings.setUserNotifier`
  - the test fixtures' `MockCamera` and `PluginJars` (`build`, `descriptor`)
  - Task 9's `ExerciseRunner.failure/failed/dismissFailure`
- Produces (`com.shootoff.compose.app`):
  - `class CameraProblems(settings, feed, problem: (String) -> Unit, lost: (Camera) -> Unit) : CameraErrorView`
  - `@Composable NoCameraPanel(app)`, tags `no-camera` and `pick-<camera>`
  - `data class Notice(id, title, message)`; `class Notices : UserNotifier`, with `notices: StateFlow<List<Notice>>` and `dismiss(Notice)`
  - `@Composable NoticeSnackbars(notices, modifier)`, tags `notice-<id>` and `dismiss-notice-<id>`
  - on `AppState`: `cameraProblem: StateFlow<String?>`, `notices: Notices`, `cameraProblems: CameraProblems`

**The cases (spec §4).**
- **No camera, or it drops out.**
  - The feed shows "No camera" with the reason and a picker, never a frozen frame. A lost camera is closed, and its frame cleared.
  - Switching or losing the camera closes the arena it calibrated (ruling 13).
  - Automatic reconnect stays out of scope.
- **Low FPS and a very bright picture** are banners on the feed, in core's words.
- **No projector screen** was Task 11: the Arena segment's hint and "Needs the projector arena".
- **An auto-calibration timeout** goes straight to the manual box. That is the flow's (Task 8's `whenAutoCalibrationTimesOutTheBoxShowsOnTheFeed`).
- **An exercise throws.** The runner stops it, and a red banner says "‹name› stopped: ‹Exception›: ‹message›" until dismissed (ruling 11).
- **A broken or mismatched plugin jar** is skipped and the rest load (ruling 12).
- **Missing target files and other `UserNotifier` messages** are snackbars, each until dismissed.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.MockCamera
import com.shootoff.camera.cameratypes.CameraEventListener
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
import com.shootoff.plugins.engine.PluginEngine
import com.shootoff.plugins.engine.PluginJars
import com.shootoff.plugins.engine.V2ExerciseLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Optional

class TestProblems {
    /** A camera another program holds */
    class LockedCamera : MockCamera() {
        override fun setCameraEventListener(cameraEventListener: CameraEventListener?) {}

        override fun isOpen() = false

        override fun open() = false

        override fun isLocked() = true

        override fun getName() = "Locked camera"
    }

    private val cameras = listOf(AppFixture.TestCamera())
    private val source = object : CameraSource {
        override fun cameras() = cameras

        override fun startCamera(settings: Settings) = cameras.firstOrNull()
    }
    private val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() })

    @AfterEach
    fun close() = app.close()

    @Test
    fun aCameraThatCantBeOpenedLeavesTheNoCameraPanelWithTheReason() {
        assertFalse(app.openCamera(LockedCamera()))

        assertNull(app.camera.value)
        assertTrue(app.cameraProblem.value!!.startsWith("Cannot open the webcam Locked camera."))
    }

    @Test
    fun aCameraThatStopsAnsweringIsClosedAndItsLastFrameGoes() {
        app.openStartCamera()
        val manager = app.camera.value
        assertNotNull(manager)

        app.cameraProblems.showMissingCameraError(manager!!.camera)

        assertNull(app.camera.value)
        assertNull(app.feed.frame.value)
        assertEquals("ShootOFF can no longer communicate with the webcam Test camera. Was it unplugged?", app.cameraProblem.value)
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
    fun lowFpsAndBrightnessAreBannersOnTheFeed() {
        app.openStartCamera()
        val manager = app.camera.value!!

        app.cameraProblems.showFPSWarning(manager.camera, 4.0)
        app.cameraProblems.showBrightnessWarning(manager.camera)

        assertEquals(listOf(BannerKind.WARNING, BannerKind.WARNING), app.feed.banners.value.map { it.kind })
    }

    @Test
    fun aV1PluginIsSkippedAndTheV2BesideItStillLoads(@TempDir exercises: Path) {
        val previous = System.getProperty("shootoff.plugins")
        System.setProperty("shootoff.plugins", exercises.toString())
        try {
            PluginJars.build(exercises, "Old.jar", Optional.of(PluginJars.descriptor(1, "com.example.v1.OldDrill")),
                mapOf("com.example.v1.OldDrill" to "package com.example.v1; public class OldDrill {}"), classpath())
            PluginJars.build(exercises, "New.jar", Optional.of(PluginJars.descriptor(2, "com.example.v2.NewDrill")),
                mapOf("com.example.v2.NewDrill" to NEW_DRILL), classpath())
            val catalog = ExerciseCatalog()

            PluginEngine(catalog, listOf(V2ExerciseLoader()), emptyList())

            assertEquals(listOf("New drill"), catalog.entries.value.map { it.metadata().name })
        } finally {
            if (previous == null) System.clearProperty("shootoff.plugins") else System.setProperty("shootoff.plugins", previous)
        }
    }

    private val NEW_DRILL = """
        package com.example.v2;
        import java.util.Optional;
        import com.shootoff.camera.Shot;
        import com.shootoff.exercise.Exercise;
        import com.shootoff.exercise.ExerciseHost;
        import com.shootoff.plugins.ExerciseMetadata;
        import com.shootoff.targets.model.Hit;
        public class NewDrill implements Exercise {
          @Override public ExerciseMetadata metadata() { return new ExerciseMetadata("New drill", "1.0", "ShootOFF tests", "Its jar"); }
          @Override public void start(ExerciseHost host) {}
          @Override public void onShot(Shot shot, Optional<Hit> hit) {}
          @Override public void onReset() {}
          @Override public void stop() {}
        }
    """.trimIndent()

    // ShootOFF's classes, as a plugin author compiles against them
    private fun classpath(): String = listOf(System.getProperty("java.class.path"), location(Exercise::class.java), location(com.shootoff.camera.Shot::class.java))
        .joinToString(File.pathSeparator)

    private fun location(type: Class<*>) = File(type.protectionDomain.codeSource.location.path).path
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TestProblemViews {
    @get:Rule
    val compose = createComposeRule()

    private val source = object : CameraSource {
        override fun cameras() = listOf(AppFixture.TestCamera())

        override fun startCamera(settings: Settings) = null
    }
    private val app = AppState(Settings(ScratchConfig.emptyFile().path, arrayOf()), ExerciseCatalog(), source, { AppFixture.ownerScreens }, ManualClock(), { it.run() })

    @After
    fun close() = app.close()

    private fun show() = compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

    @Test
    fun withNoCameraTheFeedOffersTheCamerasToPick() {
        show()
        compose.onNodeWithTag("no-camera").assertExists()

        compose.onNodeWithTag("pick-Test camera").performClick()

        assertNotNull(app.camera.value)
        compose.onNodeWithTag("no-camera").assertDoesNotExist()
    }

    @Test
    fun aNoticeIsASnackbarUntilDismissed() {
        show()
        app.notices.showError("Missing Target", "Missing Required Target File", "targets/IPSC.target is missing")
        compose.onNodeWithText("targets/IPSC.target is missing").assertExists()

        compose.onNodeWithTag("dismiss-notice-${app.notices.notices.value.single().id}").performClick()

        compose.onNodeWithText("targets/IPSC.target is missing").assertDoesNotExist()
    }

    @Test
    fun aDrillThatThrowsLeavesABannerSayingWhy() {
        show()
        app.startDrill(AppFixture.feedDrill)
        val host = app.runner.running.value!!.host

        app.runner.failed(host, IllegalStateException("boom"))
        compose.waitUntil(5000) { app.runner.running.value == null }

        compose.onNodeWithText("Feed drill stopped: IllegalStateException: boom").assertExists()
        assertNull(app.runner.running.value)
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestProblems' --tests 'com.shootoff.compose.app.TestProblemViews' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'cameraProblems'` (and `'cameraProblem'`, `'notices'`), then `BUILD FAILED`.

- [ ] **Step 3: Write the problem pieces**

`compose-app/src/main/kotlin/com/shootoff/compose/app/CameraProblems.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.CameraErrorView
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.feed.FeedState
import com.shootoff.config.Settings

/**
 * The camera's troubles, as the Compose app shows them: a camera that can't be opened or stops
 * answering leaves the feed on its "No camera" panel (with a camera picker), never a frozen frame; a low
 * frame rate or a very bright picture is a banner on the feed.
 *
 * @param lost the camera stopped answering: the app closes it
 */
class CameraProblems(
    private val settings: Settings,
    private val feed: FeedState,
    private val problem: (String) -> Unit,
    private val lost: (Camera) -> Unit,
) : CameraErrorView {
    private fun name(camera: Camera): String = settings.getWebcamsUserName(camera).orElse(camera.name)

    override fun showCameraLockError(webcam: Camera, allCamerasFailed: Boolean) =
        problem("Cannot open the webcam ${name(webcam)}. It is being used by another program or it is an IPCam with the wrong credentials.")

    override fun showMissingCameraError(webcam: Camera) {
        feed.clearFrame()
        problem(String.format(CameraErrorView.MISSING_ERROR, name(webcam)))
        lost(webcam)
    }

    override fun showFPSWarning(webcam: Camera, fps: Double) {
        feed.addBanner(String.format(CameraErrorView.FPS_WARNING, name(webcam), fps), BannerKind.WARNING)
    }

    override fun showBrightnessWarning(webcam: Camera) {
        feed.addBanner(String.format(CameraErrorView.BRIGHTNESS_WARNING, name(webcam)), BannerKind.WARNING)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/NoCameraPanel.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.theme.Range

/** The feed with no camera: why, and the cameras to pick from. */
@Composable
fun NoCameraPanel(app: AppState, modifier: Modifier = Modifier) {
    val problem by app.cameraProblem.collectAsState()
    val cameras = remember(problem) { app.availableCameras() }
    val colors = Range.colors

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.card,
            border = BorderStroke(1.dp, colors.cardBorder),
            modifier = Modifier.widthIn(max = 460.dp).testTag("no-camera"),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No camera", fontSize = 20.sp, color = colors.text)
                Text(problem ?: "Pick the camera pointed at your target.", color = colors.mutedStrong)
                if (cameras.isEmpty()) Text("No cameras found. Plug one in and come back.", color = colors.muted)
                for (camera in cameras) {
                    FilledTonalButton(onClick = { app.openCamera(camera) }, modifier = Modifier.testTag("pick-${camera.name}")) {
                        Text(camera.name)
                    }
                }
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/Notices.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.util.UserNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class Notice(val id: Long, val title: String, val message: String)

/**
 * Problems found by code without a user interface of its own (a missing target file, an unwritable
 * shootoff.properties), shown as snackbars. Install with Settings.setUserNotifier.
 */
class Notices : UserNotifier {
    private val next = AtomicLong()
    private val noticeState = MutableStateFlow<List<Notice>>(emptyList())
    val notices: StateFlow<List<Notice>> = noticeState.asStateFlow()

    override fun showError(title: String, header: String, message: String) {
        noticeState.update { it + Notice(next.incrementAndGet(), "$title: $header", message) }
    }

    fun dismiss(notice: Notice) = noticeState.update { notices -> notices.filterNot { it.id == notice.id } }
}
```


- [ ] **Step 4: Wire them in**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
    private val cameraState = MutableStateFlow<CameraManager?>(null)
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
```

with:

```kotlin
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
    private val cameraState = MutableStateFlow<CameraManager?>(null)
    private val problemState = MutableStateFlow<String?>(null)
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
```

Replace:

```kotlin
    /** The open camera, or null */
    val camera: StateFlow<CameraManager?> = cameraState.asStateFlow()

    val destination: StateFlow<Destination> = destinationState.asStateFlow()
```

with:

```kotlin
    /** The open camera, or null */
    val camera: StateFlow<CameraManager?> = cameraState.asStateFlow()

    /** Why there is no camera, if something went wrong */
    val cameraProblem: StateFlow<String?> = problemState.asStateFlow()

    /** Problems for the user from code without a user interface (see Settings.setUserNotifier) */
    val notices = Notices()

    /** What the cameras report their troubles to */
    val cameraProblems = CameraProblems(settings, feed, { problemState.value = it }, ::cameraLost)

    val destination: StateFlow<Destination> = destinationState.asStateFlow()
```

Replace:

```kotlin
     */
    fun openCamera(camera: Camera): Boolean {
        cameraState.value?.let(cameras::clearManager)
        cameraState.value = null

        val manager = cameras.addCameraManager(camera, null, cameraView).orElse(null)
        if (manager == null) {
            logger.error("Cannot open the webcam {}", camera.name)
            return false
        }
        cameraState.value = manager
        return true
    }
```

with:

```kotlin
     */
    fun openCamera(camera: Camera): Boolean {
        // The arena was calibrated with the camera that is going away, as the JavaFX app's arena closes with it
        if (cameraState.value != null) closeArena()
        cameraState.value?.let(cameras::clearManager)
        cameraState.value = null
        feed.clearFrame()

        val manager = cameras.addCameraManager(camera, cameraProblems, cameraView).orElse(null)
        if (manager == null) {
            logger.error("Cannot open the webcam {}", camera.name)
            cameraProblems.showCameraLockError(camera, false)
            return false
        }
        problemState.value = null
        cameraState.value = manager
        return true
    }

    // The camera stopped answering: close it, so the feed shows the picker and not its last frame, and close
    // the arena it calibrated
    private fun cameraLost(camera: Camera) {
        val manager = cameraState.value ?: return
        if (manager.camera !== camera) return
        closeArena()
        cameraState.value = null
        cameras.clearManager(manager)
    }
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`:

Replace:

```kotlin
    val message by app.drill.message.collectAsState()
    val calibration by app.calibration.collectAsState()
    val colors = Range.colors
    val projectorDrill = running?.host?.isProjector == true
```

with:

```kotlin
    val message by app.drill.message.collectAsState()
    val calibration by app.calibration.collectAsState()
    val camera by app.camera.collectAsState()
    val failure by app.runner.failure.collectAsState()
    val colors = Range.colors
    val projectorDrill = running?.host?.isProjector == true
```

Replace:

```kotlin
                    if (projectorDrill) ExerciseOverlay(app.drill, transform)
                }
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
```

with:

```kotlin
                    if (projectorDrill) ExerciseOverlay(app.drill, transform)
                }
            } else if (camera == null) {
                NoCameraPanel(app)
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
```

Replace:

```kotlin
            DrillCard(app.drill, Modifier.align(Alignment.TopEnd).padding(10.dp))
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
```

with:

```kotlin
            DrillCard(app.drill, Modifier.align(Alignment.TopEnd).padding(10.dp))
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                failure?.let { BannerView(Banner(-1, it, BannerKind.ERROR), onDismiss = app.runner::dismissFailure) }
                message?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`: replace everything from `package` to the end of the file with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.theme.Range

/** The main window's content: the rail, the destination it leads to, and snackbars for notices. */
@Composable
fun ShootOffApp(app: AppState) {
    val destination by app.destination.collectAsState()
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Range.colors.background)) {
            AppRail(destination, app::navigate)
            Box(Modifier.fillMaxSize()) {
                when (destination) {
                    Destination.RANGE -> RangeScreen(app)
                    Destination.DRILLS -> DrillsScreen(app)
                    Destination.SETTINGS -> SettingsScreen(app)
                    // Disabled on the rail
                    Destination.TARGETS, Destination.SESSIONS -> RangeScreen(app)
                }
            }
        }
        NoticeSnackbars(app.notices, Modifier.align(Alignment.BottomCenter).padding(24.dp))
    }
}

/** Each notice as a snackbar until dismissed, oldest at the top */
@Composable
fun NoticeSnackbars(notices: Notices, modifier: Modifier = Modifier) {
    val shown by notices.notices.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (notice in shown) {
            Snackbar(
                action = { TextButton(onClick = { notices.dismiss(notice) }, modifier = Modifier.testTag("dismiss-notice-${notice.id}")) { Text("Dismiss") } },
                modifier = Modifier.widthIn(max = 640.dp).testTag("notice-${notice.id}"),
            ) {
                Column {
                    Text(notice.title)
                    Text(notice.message)
                }
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`:

Replace:

```kotlin

    val app = AppState(settings, catalog, CameraSource.System)
    app.openStartCamera()
```

with:

```kotlin

    val app = AppState(settings, catalog, CameraSource.System)
    Settings.setUserNotifier(app.notices)
    app.openStartCamera()
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.*' --console=plain`

Expected: `BUILD SUCCESSFUL`, 19 tests passing (8 new). The v1-jar test logs core's "Error creating new plugin … needs plugin API version 1" with a stack trace; that is the skip working.

- [ ] **Step 6: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `566/566 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 7: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/CameraProblems.kt compose-app/src/main/kotlin/com/shootoff/compose/app/NoCameraPanel.kt compose-app/src/main/kotlin/com/shootoff/compose/app/Notices.kt
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt compose-app/src/main/kotlin/com/shootoff/compose/Main.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblems.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestProblemViews.kt
git commit -m "Handle a missing camera, a failing drill, skipped v1 plugins and notices in the Compose app"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 13: Polish: light and dark, animations, a sizable tray, remembered layout, HiDPI and shortcuts

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/UiPrefs.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedBanners.kt`, `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiPrefs.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestPolish.kt`

**Interfaces:**
- Consumes: everything above; `java.util.prefs.Preferences`.
- Produces (`com.shootoff.compose.app`):
  - `interface PrefsStore { get(key); put(key, value) }` with `PrefsStore.User` (the user's Java preferences, `com/shootoff/compose`) and `PrefsStore.Memory`
  - `data class WindowBounds(x, y, width, height)`
  - `class UiPrefs(store: PrefsStore = PrefsStore.Memory())`, with `dark`, `view`, `trayHeight`, `trayCollapsed`, `window` and `DEFAULT_TRAY_HEIGHT = 220f`
  - `enum Shortcut(key, label) { SWITCH_VIEW (F2), PAUSE_DRILL (F3), CLEAR_SHOTS (F4), CALIBRATE (F6) }`
  - `fun AppState.perform(Shortcut): Boolean`, `fun AppState.handleKey(Key, KeyEventType): Boolean`
  - `const val MIN_TRAY_HEIGHT = 120f`, `MAX_TRAY_HEIGHT = 480f`
  - on `AppState`:
    - the constructor's last parameter `prefs: UiPrefs = UiPrefs()`
    - `dark`, `trayHeight`, `trayCollapsed` (StateFlows)
    - `setDark`, `setTrayHeight`, `setTrayCollapsed`
    - `showView` remembers the view; `openArena` returns to the Arena view if the user left the app there
  - `AppRail` gains `trailing: @Composable () -> Unit = {}`; `ThemeSwitch(app)` (tag `theme-switch`) sits at the rail's foot
  - `ArenaWindow` gains `onKey: (KeyEvent) -> Boolean = { false }`
  - Range's tray is tagged `tray-handle` and `tray-fold`; Settings' theme choices `theme-dark` and `theme-light`

**The polish (spec §4).**
- **Light and dark.** Range dark is the default. The light variant is one switch away at the rail's foot, and in Settings; both are remembered.
- **Animations:**
  - the big view cross-fades between Camera and Arena
  - the drill card resizes smoothly and its texts fade between values (Task 10)
  - new shot-timer rows animate in (Task 10)
  - banners fade and slide in
- **The tray** is resized by dragging its top edge (120–480 dp) and folded to its titles with Hide/Show. Both are remembered.
- **Remembered layout:** the window's place and size, the last view, the theme and the tray (ruling 13's `UiPrefs`, never in `shootoff.properties`).
- **HiDPI:** surfaces are drawn through `SurfaceTransform` in pixels (ruling 18).
- **Keyboard shortcuts** (ruling 16) in both windows. F11 stays the arena window's.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiPrefs.kt`:

```kotlin
package com.shootoff.compose.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestUiPrefs {
    private val store = PrefsStore.Memory()

    @Test
    fun aFirstRunIsRangeDarkOnTheCameraWithTheTrayOpen() {
        val prefs = UiPrefs(store)

        assertTrue(prefs.dark)
        assertEquals(BigView.CAMERA, prefs.view)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertFalse(prefs.trayCollapsed)
        assertNull(prefs.window)
    }

    @Test
    fun whatTheUserLeftIsThereNextTime() {
        UiPrefs(store).apply {
            dark = false
            view = BigView.ARENA
            trayHeight = 300f
            trayCollapsed = true
            window = WindowBounds(2600f, 400f, 1330f, 910f)
        }

        val next = UiPrefs(store)
        assertFalse(next.dark)
        assertEquals(BigView.ARENA, next.view)
        assertEquals(300f, next.trayHeight)
        assertTrue(next.trayCollapsed)
        assertEquals(WindowBounds(2600f, 400f, 1330f, 910f), next.window)
    }

    @Test
    fun unreadableValuesFallBackToTheDefaults() {
        store.put("view", "SIDEWAYS")
        store.put("tray.height", "tall")
        store.put("window", "1,2,3")

        val prefs = UiPrefs(store)
        assertEquals(BigView.CAMERA, prefs.view)
        assertEquals(UiPrefs.DEFAULT_TRAY_HEIGHT, prefs.trayHeight)
        assertNull(prefs.window)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.drill.DrillButton
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestShortcuts {
    private val app = AppFixture.app()

    @AfterEach
    fun close() = app.close()

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
    fun f3PressesTheDrillsPauseOrResumeButton() {
        val pressed = mutableListOf<String>()
        app.drill.addButton(DrillButton(1, "Clear Shots") { pressed += "clear" })
        app.drill.addButton(DrillButton(2, "Resume") { pressed += "resume" })

        assertTrue(app.perform(Shortcut.PAUSE_DRILL))

        assertEquals(listOf("resume"), pressed)
    }

    @Test
    fun f4ClearsTheShots() {
        app.timer.appendShotRow(ScaledShot(ShotColor.RED, 1.0, 1.0, 1000), false, false)
        app.feedMarkers.add(1.0, 1.0, ShotColor.RED, 4)

        press(Key.F4)

        assertTrue(app.timer.rows.value.isEmpty())
        assertTrue(app.feedMarkers.markers.value.isEmpty())
    }

    @Test
    fun withNothingToDoAShortcutDoesNothing() {
        // No drill button to press, no camera to calibrate with
        assertFalse(app.perform(Shortcut.PAUSE_DRILL))
        assertFalse(app.perform(Shortcut.CALIBRATE))
        // Only a key going down counts, and only a shortcut's
        assertFalse(app.handleKey(Key.F2, KeyEventType.KeyUp))
        assertFalse(app.handleKey(Key.A, KeyEventType.KeyDown))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestPolish.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.compose.theme.RangeLight
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class TestPolish {
    @get:Rule
    val compose = createComposeRule()

    private val store = PrefsStore.Memory()
    private val app = AppState(
        Settings(ScratchConfig.emptyFile().path, arrayOf()),
        ExerciseCatalog(),
        CameraSource.None,
        { AppFixture.ownerScreens },
        ManualClock(),
        { it.run() },
        UiPrefs(store),
    )

    @After
    fun close() = app.close()

    private fun showApp() = compose.setContent {
        val dark by app.dark.collectAsState()
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            RangeTheme(dark = dark) { Box(Modifier.size(1000.dp, 700.dp).testTag("window")) { ShootOffApp(app) } }
        }
    }

    @Test
    fun theThemeSwitchGoesLightAndIsRemembered() {
        showApp()

        compose.onNodeWithTag("theme-switch").performClick()
        compose.waitForIdle()

        assertFalse(app.dark.value)
        assertFalse(UiPrefs(store).dark)
        // The rail's foot, below everything on it, is the light variant's rail color
        val pixels = compose.onNodeWithTag("window").captureToImage().toPixelMap()
        assertEquals(RangeLight.rail, pixels[4, 695])
    }

    @Test
    fun theTrayFoldsAndUnfolds() {
        showApp()
        compose.onNodeWithTag("shot-timer").assertExists()

        compose.onNodeWithTag("tray-fold").performClick()
        compose.onNodeWithTag("shot-timer").assertDoesNotExist()
        assertTrue(UiPrefs(store).trayCollapsed)

        compose.onNodeWithTag("tray-fold").performClick()
        compose.onNodeWithTag("shot-timer").assertExists()
    }

    @Test
    fun draggingTheTraysEdgeUpMakesItTallerWithinLimits() {
        showApp()

        compose.onNodeWithTag("tray-handle").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, -100f))
            release()
        }
        compose.waitForIdle()
        assertTrue(app.trayHeight.value > UiPrefs.DEFAULT_TRAY_HEIGHT + 50)

        app.setTrayHeight(5000f)
        assertEquals(MAX_TRAY_HEIGHT, app.trayHeight.value)
        assertEquals(MAX_TRAY_HEIGHT, UiPrefs(store).trayHeight)
    }

    @Test
    fun onAHiDpiScreenTheSurfaceFillsTheSameShareOfTheView() {
        val targets = SurfaceTargets(clock = ManualClock())
        targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, "red", mapOf("opacity" to "1")))),
            ResourceResolver.files(),
            Placement(100.0, 100.0, 1.0, 1.0, true),
        )
        // 320x240 dp at twice the density is 640x480 pixels
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.size(320.dp, 240.dp).background(Color.Black).testTag("hidpi")) {
                    TargetLayer(targets, SurfaceTransform.fit(Size(640.0, 480.0), 640f, 480f))
                }
            }
        }

        val pixels = compose.onNodeWithTag("hidpi").captureToImage().toPixelMap()
        assertEquals(640, pixels.width)
        assertEquals(Color.Red, pixels[120, 120])
    }
}
```


- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.app.TestUiPrefs' --tests 'com.shootoff.compose.app.TestShortcuts' --tests 'com.shootoff.compose.app.TestPolish' --console=plain`

Expected: FAIL at compile time: `e: … Unresolved reference 'PrefsStore'` (and `'UiPrefs'`, `'handleKey'`, `'Shortcut'`, `'MAX_TRAY_HEIGHT'`), then `BUILD FAILED`.

- [ ] **Step 3: Write the preferences and the shortcuts**

`compose-app/src/main/kotlin/com/shootoff/compose/app/UiPrefs.kt`:

```kotlin
package com.shootoff.compose.app

import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences

/** Where the Compose app keeps its own preferences: not in shootoff.properties, which the JavaFX app rewrites. */
interface PrefsStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    /** The user's Java preferences, under com/shootoff/compose */
    class User(private val node: Preferences = Preferences.userRoot().node("com/shootoff/compose")) : PrefsStore {
        override fun get(key: String): String? = node.get(key, null)

        override fun put(key: String, value: String) = node.put(key, value)
    }

    /** For tests */
    class Memory : PrefsStore {
        private val values = ConcurrentHashMap<String, String>()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String) {
            values[key] = value
        }
    }
}

/** The main window's place and size, in dp */
data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** The look and layout the user left the Compose app in. */
class UiPrefs(private val store: PrefsStore = PrefsStore.Memory()) {
    var dark: Boolean
        get() = store.get("theme") != "light"
        set(value) = store.put("theme", if (value) "dark" else "light")

    var view: BigView
        get() = store.get("view")?.let { runCatching { BigView.valueOf(it) }.getOrNull() } ?: BigView.CAMERA
        set(value) = store.put("view", value.name)

    var trayHeight: Float
        get() = store.get("tray.height")?.toFloatOrNull() ?: DEFAULT_TRAY_HEIGHT
        set(value) = store.put("tray.height", value.toString())

    var trayCollapsed: Boolean
        get() = store.get("tray.collapsed") == "true"
        set(value) = store.put("tray.collapsed", value.toString())

    var window: WindowBounds?
        get() {
            val parts = store.get("window")?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return null
            return if (parts.size == 4) WindowBounds(parts[0], parts[1], parts[2], parts[3]) else null
        }
        set(value) {
            if (value != null) store.put("window", "${value.x},${value.y},${value.width},${value.height}")
        }

    companion object {
        const val DEFAULT_TRAY_HEIGHT = 220f
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType

/**
 * The Compose app's keyboard shortcuts. Function keys, so they never clash with typing in a field.
 */
enum class Shortcut(val key: Key, val label: String) {
    SWITCH_VIEW(Key.F2, "Switch between the camera and the arena"),
    PAUSE_DRILL(Key.F3, "Pause or resume the drill"),
    CLEAR_SHOTS(Key.F4, "Clear the shots"),
    CALIBRATE(Key.F6, "Start or stop calibrating"),
    ;

    companion object {
        fun forKey(key: Key): Shortcut? = entries.firstOrNull { it.key == key }
    }
}

/** Labels a drill's pause button may have: the par drill's are "Pause" and "Resume" */
private val PAUSE_LABELS = setOf("Pause", "Resume")

/**
 * Does what a shortcut does.
 *
 * @return false if it did nothing (e.g. no drill with a pause button runs)
 */
fun AppState.perform(shortcut: Shortcut): Boolean {
    when (shortcut) {
        Shortcut.SWITCH_VIEW -> {
            if (arena.value == null) return false
            showView(if (view.value == BigView.CAMERA) BigView.ARENA else BigView.CAMERA)
        }
        Shortcut.PAUSE_DRILL -> {
            val pause = drill.buttons.value.firstOrNull { it.label in PAUSE_LABELS } ?: return false
            pause.onClick()
        }
        Shortcut.CLEAR_SHOTS -> clearShots()
        Shortcut.CALIBRATE -> {
            if (calibration.value == null) return false
            toggleCalibration()
        }
    }
    return true
}

/** The main window's key handler: a shortcut's key going down does its action. */
fun AppState.handleKey(key: Key, type: KeyEventType): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val shortcut = Shortcut.forKey(key) ?: return false
    perform(shortcut)
    return true
}
```


- [ ] **Step 4: Put them and the animations into the app**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
enum class BigView { CAMERA, ARENA }

/**
 * The Compose app's state: the camera and its feed, the arena, calibration, the running drill, the shot
```

with:

```kotlin
enum class BigView { CAMERA, ARENA }

const val MIN_TRAY_HEIGHT = 120f
const val MAX_TRAY_HEIGHT = 480f

/**
 * The Compose app's state: the camera and its feed, the arena, calibration, the running drill, the shot
```

Replace:

```kotlin
    private val clock: AnimationClock = AnimationClock.background,
    private val uiThread: (Runnable) -> Unit = EventQueue::invokeLater,
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

with:

```kotlin
    private val clock: AnimationClock = AnimationClock.background,
    private val uiThread: (Runnable) -> Unit = EventQueue::invokeLater,
    val prefs: UiPrefs = UiPrefs(),
) : CalibrationViews {
    private val logger = LoggerFactory.getLogger(AppState::class.java)
```

Replace:

```kotlin
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null
```

with:

```kotlin
    private val destinationState = MutableStateFlow(Destination.RANGE)
    private val viewState = MutableStateFlow(BigView.CAMERA)
    private val darkState = MutableStateFlow(prefs.dark)
    private val trayHeightState = MutableStateFlow(prefs.trayHeight)
    private val trayCollapsedState = MutableStateFlow(prefs.trayCollapsed)
    private var viewBeforeCalibration: BigView? = null
    private var fullScreenWatch: Job? = null
```

Replace:

```kotlin
    val view: StateFlow<BigView> = viewState.asStateFlow()

    val feedSurface = FeedSurface(
        "Default",
```

with:

```kotlin
    val view: StateFlow<BigView> = viewState.asStateFlow()

    /** Range dark, or its light variant */
    val dark: StateFlow<Boolean> = darkState.asStateFlow()

    /** The tray's height in dp, and whether it is folded down to its title bar */
    val trayHeight: StateFlow<Float> = trayHeightState.asStateFlow()
    val trayCollapsed: StateFlow<Boolean> = trayCollapsedState.asStateFlow()

    val feedSurface = FeedSurface(
        "Default",
```

Replace:

```kotlin
        if (view == BigView.ARENA && arenaState.value == null) return
        viewState.value = view
    }
```

with:

```kotlin
        if (view == BigView.ARENA && arenaState.value == null) return
        viewState.value = view
        prefs.view = view
    }

    fun setDark(dark: Boolean) {
        darkState.value = dark
        prefs.dark = dark
    }

    /** The user dragged the tray's edge */
    fun setTrayHeight(height: Float) {
        val clamped = height.coerceIn(MIN_TRAY_HEIGHT, MAX_TRAY_HEIGHT)
        trayHeightState.value = clamped
        prefs.trayHeight = clamped
    }

    fun setTrayCollapsed(collapsed: Boolean) {
        trayCollapsedState.value = collapsed
        prefs.trayCollapsed = collapsed
    }
```

Replace:

```kotlin
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena

        val camera = cameraState.value ?: return
```

with:

```kotlin
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena
        // Back to the view the user last left the app on
        if (prefs.view == BigView.ARENA) viewState.value = BigView.ARENA

        val camera = cameraState.value ?: return
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
```

with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
```

Replace:

```kotlin
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
```

with:

```kotlin
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
```

Replace:

```kotlin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
```

with:

```kotlin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
```

Replace:

```kotlin
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay

/**
```

with:

```kotlin
import com.shootoff.compose.theme.Range
import kotlinx.coroutines.delay
import java.awt.Cursor

/**
```

Replace:

```kotlin
@Composable
fun RangeScreen(app: AppState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigViewArea(app, Modifier.weight(1f).fillMaxWidth())
        Tray(app, Modifier.fillMaxWidth().height(220.dp))
    }
}
```

with:

```kotlin
@Composable
fun RangeScreen(app: AppState, modifier: Modifier = Modifier) {
    val trayHeight by app.trayHeight.collectAsState()
    val collapsed by app.trayCollapsed.collectAsState()
    val density = LocalDensity.current
    Column(modifier.fillMaxSize().padding(end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        BigViewArea(app, Modifier.weight(1f).fillMaxWidth())
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
        Tray(app, collapsed, Modifier.fillMaxWidth().animateContentSize().height(if (collapsed) 36.dp else trayHeight.dp))
    }
}
```

Replace:

```kotlin
        Box(Modifier.fillMaxSize()) {
            val shownArena = arena
            if (view == BigView.ARENA && shownArena != null) {
                ArenaView(shownArena, Modifier.fillMaxSize()) { transform ->
                    if (projectorDrill) ExerciseOverlay(app.drill, transform)
                }
            } else if (camera == null) {
                NoCameraPanel(app)
            } else {
                CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                    TargetLayer(app.feedTargets, transform)
                    MarkerLayer(app.feedMarkers, transform)
                    if (running != null && !projectorDrill) ExerciseOverlay(app.drill, transform)
                    calibration?.let { CalibrationOverlay(it, transform) }
                }
            }
```

with:

```kotlin
        Box(Modifier.fillMaxSize()) {
            val shownArena = arena
            val shown = if (view == BigView.ARENA && shownArena != null) BigView.ARENA else BigView.CAMERA
            Crossfade(shown, label = "big view") { current ->
                if (current == BigView.ARENA && shownArena != null) {
                    ArenaView(shownArena, Modifier.fillMaxSize()) { transform ->
                        if (projectorDrill) ExerciseOverlay(app.drill, transform)
                    }
                } else if (camera == null) {
                    NoCameraPanel(app)
                } else {
                    CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                        TargetLayer(app.feedTargets, transform)
                        MarkerLayer(app.feedMarkers, transform)
                        if (running != null && !projectorDrill) ExerciseOverlay(app.drill, transform)
                        calibration?.let { CalibrationOverlay(it, transform) }
                    }
                }
            }
```

Replace:

```kotlin
}

/** The tray: the shot timer, and the running drill's settings */
@Composable
private fun Tray(app: AppState, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrayPanel("SHOT TIMER", Modifier.weight(1f)) { ShotTimerTable(app.timer, Modifier.fillMaxSize()) }
        TrayPanel("SETTINGS", Modifier.width(380.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) { DrillSettings(app.drill) }
        }
```

with:

```kotlin
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
```

Replace:

```kotlin

@Composable
private fun TrayPanel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    val colors = Range.colors
    Surface(
```

with:

```kotlin

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
```

Replace:

```kotlin
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(title, color = colors.mutedStrong, fontSize = 10.sp, letterSpacing = 0.6.sp)
            content()
        }
    }
```

with:

```kotlin
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
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`:

Replace:

```kotlin
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
```

with:

```kotlin
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
```

Replace:

```kotlin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
```

with:

```kotlin
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.shell.AppRail
import com.shootoff.compose.shell.Destination
```

Replace:

```kotlin
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Range.colors.background)) {
            AppRail(destination, app::navigate)
            Box(Modifier.fillMaxSize()) {
                when (destination) {
```

with:

```kotlin
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Range.colors.background)) {
            AppRail(destination, app::navigate, trailing = { ThemeSwitch(app) })
            Box(Modifier.fillMaxSize()) {
                when (destination) {
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
}

/** Range dark or light, at the foot of the rail */
@Composable
fun ThemeSwitch(app: AppState) {
    val dark by app.dark.collectAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Switch(checked = dark, onCheckedChange = app::setDark, modifier = Modifier.testTag("theme-switch"))
        Text(if (dark) "Dark" else "Light", color = Range.colors.muted, fontSize = 10.sp)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt`:

Replace:

```kotlin
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 22.sp, color = colors.text)

        Section("Camera") {
```

with:

```kotlin
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", fontSize = 22.sp, color = colors.text)

        Section("Theme") {
            val dark by app.dark.collectAsState()
            Choice("Range dark", dark, "theme-dark") { app.setDark(true) }
            Choice("Range light", !dark, "theme-light") { app.setDark(false) }
        }

        Section("Camera") {
```

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`:

Replace:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
```

with:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
```

Replace:

```kotlin
import com.shootoff.compose.app.CameraSource
import com.shootoff.compose.app.ExerciseCatalog
import com.shootoff.compose.app.ShootOffApp
import com.shootoff.compose.arena.ArenaWindow
```

with:

```kotlin
import com.shootoff.compose.app.CameraSource
import com.shootoff.compose.app.ExerciseCatalog
import com.shootoff.compose.app.PrefsStore
import com.shootoff.compose.app.UiPrefs
import com.shootoff.compose.app.WindowBounds
import com.shootoff.compose.app.handleKey
import com.shootoff.compose.app.ShootOffApp
import com.shootoff.compose.arena.ArenaWindow
```

Replace:

```kotlin
    plugins.startWatching()

    val app = AppState(settings, catalog, CameraSource.System)
    Settings.setUserNotifier(app.notices)
    app.openStartCamera()
```

with:

```kotlin
    plugins.startWatching()

    val app = AppState(settings, catalog, CameraSource.System, prefs = UiPrefs(PrefsStore.User()))
    Settings.setUserNotifier(app.notices)
    app.openStartCamera()
```

Replace:

```kotlin
        val placement by app.arenaPlacement.collectAsState()
        val running by app.runner.running.collectAsState()
        val state = rememberWindowState(size = DpSize(1280.dp, 860.dp))

        fun exit() {
```

with:

```kotlin
        val placement by app.arenaPlacement.collectAsState()
        val running by app.runner.running.collectAsState()
        val dark by app.dark.collectAsState()
        val remembered = app.prefs.window
        val state = rememberWindowState(
            position = remembered?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
            size = remembered?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 860.dp),
        )

        fun exit() {
```

Replace:

```kotlin
        }

        Window(onCloseRequest = ::exit, state = state, title = "ShootOFF") {
            LaunchedEffect(state.position) {
                app.mainWindowCorner = Point(window.x.toDouble(), window.y.toDouble())
            }
            RangeTheme(dark = true) { ShootOffApp(app) }
        }
```

with:

```kotlin
        }

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
```

Replace:

```kotlin
        val shownPlacement = placement
        if (shownArena != null && shownPlacement != null) {
            ArenaWindow(shownArena, shownPlacement, onCloseRequest = app::closeArena) { transform ->
                if (running?.host?.isProjector == true) ExerciseOverlay(app.drill, transform)
            }
```

with:

```kotlin
        val shownPlacement = placement
        if (shownArena != null && shownPlacement != null) {
            ArenaWindow(shownArena, shownPlacement, onCloseRequest = app::closeArena, onKey = { app.handleKey(it.key, it.type) }) { transform ->
                if (running?.host?.isProjector == true) ExerciseOverlay(app.drill, transform)
            }
```

`compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`:

Replace:

```kotlin

@Composable
fun AppRail(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val colors = Range.colors
    NavigationRail(modifier = modifier, containerColor = colors.rail) {
```

with:

```kotlin

@Composable
fun AppRail(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val colors = Range.colors
    NavigationRail(modifier = modifier, containerColor = colors.rail) {
```

Replace:

```kotlin
            modifier = Modifier.width(72.dp).padding(horizontal = 4.dp).testTag("rail-hint"),
        )
    }
}
```

with:

```kotlin
            modifier = Modifier.width(72.dp).padding(horizontal = 4.dp).testTag("rail-hint"),
        )
        Spacer(Modifier.weight(1f))
        trailing()
        Spacer(Modifier.height(12.dp))
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedBanners.kt`:

Replace:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
```

with:

```kotlin
package com.shootoff.compose.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
```

Replace:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
```

with:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
```

Replace:

```kotlin
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (banner in banners) {
            BannerView(banner, onDismiss = { feed.removeBanner(banner) })
        }
    }
```

with:

```kotlin
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (banner in banners) {
            key(banner.id) {
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { -it / 2 }) {
                    BannerView(banner, onDismiss = { feed.removeBanner(banner) })
                }
            }
        }
    }
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt`:

Replace:

```kotlin
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
```

with:

```kotlin
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
```

Replace:

```kotlin
    placement: ArenaPlacement,
    onCloseRequest: () -> Unit,
    overlay: @Composable (SurfaceTransform) -> Unit = {},
) {
```

with:

```kotlin
    placement: ArenaPlacement,
    onCloseRequest: () -> Unit,
    onKey: (KeyEvent) -> Boolean = { false },
    overlay: @Composable (SurfaceTransform) -> Unit = {},
) {
```

Replace:

```kotlin
                true
            } else {
                false
            }
        },
```

with:

```kotlin
                true
            } else {
                onKey(event)
            }
        },
```


- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :compose-app:test --console=plain`

Expected: `BUILD SUCCESSFUL`; `python3 scripts/test_summary.py summarize compose-app/build/test-results/test | command grep -c PASS` prints 130.

- [ ] **Step 6: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `577/577 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 7: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/main/kotlin/com/shootoff/compose/app/UiPrefs.kt compose-app/src/main/kotlin/com/shootoff/compose/app/Shortcuts.kt
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SettingsScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/Main.kt compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt compose-app/src/main/kotlin/com/shootoff/compose/feed/FeedBanners.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaWindow.kt
git add compose-app/src/test/kotlin/com/shootoff/compose/app/TestUiPrefs.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestShortcuts.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestPolish.kt
git commit -m "Polish the Compose app: light and dark, animations, a sizable tray, remembered layout, HiDPI and shortcuts"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 14: The boundary: no JavaFX in `compose-app`

**Files:**
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/TestNoJavaFxInComposeApp.kt` (new)

**Interfaces:**
- Consumes: `core`'s test fixture `com.shootoff.JavaFxReferenceScanner.findJavaFxReferencesNextTo(Class<?>)`; `compose-app`'s entry point `com.shootoff.compose.MainKt`.
- Produces: nothing.

This is spec §5's boundary test. `compose-app`'s classes don't mention JavaFX, and no OpenJFX jar is on its class path, so nothing reaches JavaFX even through `core` or `plugin-api`.

- [ ] **Step 1: Write the test**

`compose-app/src/test/kotlin/com/shootoff/compose/TestNoJavaFxInComposeApp.kt`:

```kotlin
package com.shootoff.compose

import com.shootoff.JavaFxReferenceScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TestNoJavaFxInComposeApp {
    @Test
    fun composeAppClassesDoNotReferenceJavaFx() {
        // MainKt is compose-app's entry point, so this scans compose-app's own classes
        assertEquals(emptyList<String>(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(Class.forName("com.shootoff.compose.MainKt")))
    }

    @Test
    fun javaFxIsNotOnComposeAppsClassPath() {
        val javaFx = System.getProperty("java.class.path").split(File.pathSeparator).filter { "openjfx" in it || "javafx" in File(it).name }

        assertEquals(emptyList<String>(), javaFx)
        assertTrue(runCatching { Class.forName("javafx.application.Platform") }.isFailure)
    }
}
```


- [ ] **Step 2: See it catch a JavaFX reference**

The module has no JavaFX, so the test passes as soon as it exists. First prove it can fail: add a class that names JavaFX, run the test, then delete the class.

```bash
cd /home/bfears/projects/ShootOFF
printf 'package com.shootoff.compose\n\nobject BoundaryProbe {\n    val name = "javafx.scene.Node"\n}\n' > compose-app/src/main/kotlin/com/shootoff/compose/BoundaryProbe.kt
./gradlew :compose-app:test --tests 'com.shootoff.compose.TestNoJavaFxInComposeApp' --console=plain
rm compose-app/src/main/kotlin/com/shootoff/compose/BoundaryProbe.kt
```

Expected: FAIL, `composeAppClassesDoNotReferenceJavaFx()` reports `expected: <[]> but was: <[com/shootoff/compose/BoundaryProbe.class]>`. Then the probe is deleted: `git status --short` must not list it.

- [ ] **Step 3: Run the test to verify it passes**

Run: `./gradlew :compose-app:test --tests 'com.shootoff.compose.TestNoJavaFxInComposeApp' --console=plain`

Expected: `BUILD SUCCESSFUL`, 2 tests passing.

- [ ] **Step 4: Run the gate**

Run the gate (Global Constraints), then check the owner's files:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
```

Expected: `579/579 passing; 0 regressions; 0 new failures`, then four `OK` lines.

- [ ] **Step 5: Commit**

```bash
cd /home/bfears/projects/ShootOFF
git add compose-app/src/test/kotlin/com/shootoff/compose/TestNoJavaFxInComposeApp.kt
git commit -m "Check that compose-app never references JavaFX"
git log -1 --format=%B
git status --short
```

Expected: the message alone, with no trailer. `git status --short` lists only `shootoff.properties` and `.superpowers/` (and anything the owner added).

---
### Task 15: The owner's hardware check, and a short JavaFX regression check

Runs in ShootOFF on `compose-ui`. **Files:** none.
- If a check fails, stop and debug with superpowers:systematic-debugging before changing code.
- The fix belongs in the task that owns the code, as a new commit with a test that reproduces it.
- If the owner's camera shows under about 25 FPS in item 2, apply ruling 17's fallback in `ComposeCameraView`.

- [ ] **Step 1: The gate, the boundary and the owner's files**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan5-owner-files.sha256
./gradlew :compose-app:dependencies --configuration runtimeClasspath --console=plain | command grep -c openjfx
```

Expected:
- `579/579 passing; 0 regressions; 0 new failures`
- four `OK` lines
- `0`

- [ ] **Step 2: Check that the moved `TextToSpeech` still links for v1 plugins**

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :javafx-app:classes --console=plain -q
CP=core/build/classes/java/main:plugin-api/build/classes/java/main:javafx-app/build/classes/java/main
javap -s -p -cp "$CP" com.shootoff.plugins.TextToSpeech | command grep -A1 -E " (say|silence)\("
ls javafx-app/build/classes/java/main/com/shootoff/plugins/TextToSpeech.class 2>&1 | command grep -c "No such file"
```

Expected: `public static void say(java.lang.String);` with `descriptor: (Ljava/lang/String;)V`, and `public static void silence(boolean);` with `descriptor: (Z)V`. Then `1`: the class is now `core`'s only.

- [ ] **Step 3: Launch the Compose app**

With the webcam and the projector attached, run in the background:

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :compose-app:run --console=plain > build/plan5-compose-run.log 2>&1
```

- [ ] **Step 4: The owner checks the Compose app, on the hardware** (spec §1 success criteria 1–3)

1. **Launch and look.**
   - The Compose app opens in Range dark: the slate background, the rail with Range, Targets, Drills, Sessions and Settings, and the orange accent.
   - Targets and Sessions are greyed out, and the rail says they are in the JavaFX app for now.
   - The big view has the Camera | Arena switch (Arena greyed, "Open the arena first").
   - The status strip at the bottom left reads like `… · 30 FPS · shown 30 · 640×480 · no arena`.
   - The tray along the bottom has the shot timer and the settings.
2. **Frame rate.** Watch the status strip for a minute: the camera stays near 30 FPS, and "shown" near it.
3. **Light and dark.** Flip the switch at the rail's foot: the whole app turns to the light slate-and-orange variant, and back. Settings → Theme does the same.
4. **Camera pick.** Settings → Camera lists the C270. Choosing it reopens the feed, and the status strip names it.
5. **Arena on the projector.** On Range, press Open arena:
   - the arena window opens on the projector and goes full screen
   - "Move the arena…" gives way to "Looking for the calibration pattern…" over the feed
   - the Arena segment is enabled, and switching to it shows the same arena as the projector
6. **Auto-calibration.**
   - The pattern shows on the projector and auto-calibration finds it.
   - "Needs Calibration" goes; the arena's background comes back; the status strip says `calibrated`.
   - Shots inside the projection now reach the arena.
7. **The manual box.** Press Calibrate again, and cover the camera for 12 s (or wait for the timeout):
   - the view switches to the camera, and an orange box appears on the feed with "Drag the box over the projection, then press Done"
   - drag the box and its corners over the projection, then press Done
   - the status strip says `calibrated`, and shots land where they hit
8. **The drill, end to end.** Drills lists "Random Target PAR Drill with Score". Press Start; the app goes to Range. Play at least five rounds.
   - Placement: a target appears at a random place on the projector and in the Arena view, and hides between rounds.
   - Cues and sounds: the make-ready voice, the start beep and the buzzer play.
   - Par timing and points: a hit fills Length and Score; the card shows "Score: …" big and "Round: …" below it.
   - The coral row: a round with no shot shows "Par missed!" and adds a coral-tinted row (Time, Split, "red", Length about the par time, Score 0).
   - Pause and Resume: the card's Pause button and F3 both pause and resume.
   - The summary: the arena shows the summary with the hit factor and the personal best, and the card shows its first line.
   - The replay: the shots are replayed on the summary target.
   - Shoot-to-reset: a shot 4 s after the summary restarts the drill.
   - Calibrate while it runs: the drill stops, calibrates, and starts afresh.
   - Close the arena: the drill stops and leaves nothing behind (buttons, settings, texts, columns, target).
9. **Keyboard shortcuts.**
   - F2 flips Camera and Arena; F3 pauses and resumes; F4 clears the shots and the timer; F6 starts and stops calibrating.
   - In the arena window, F11 leaves and re-enters full screen, and calibration follows.
10. **The tray and remembering.**
    - Drag the tray's top edge; Hide and Show fold it.
    - Quit and relaunch: the window, the theme, the tray and the last view come back (the Arena view once the arena is opened again).

- [ ] **Step 5: Check the Compose app's log**

```bash
cd /home/bfears/projects/ShootOFF
command grep -nE "Exception|Error" build/plan5-compose-run.log | command grep -v "needs plugin API version 1" | command grep -vE "^[0-9]+:\s+at "
```

Expected: nothing, apart from the v1 jar's expected "Error creating new plugin" line (ruling 12). Its stack trace is filtered out above.

- [ ] **Step 6: A short JavaFX regression check** (spec §1 success criterion 4)

Quit the Compose app. Run `./gradlew run --console=plain > build/plan5-javafx-run.log 2>&1` in the background, with the webcam and the projector attached. The owner checks:
1. Only the JavaFX app starts: `command grep -c "Task :compose-app:run SKIPPED" build/plan5-javafx-run.log` prints `1`.
2. Projector → the arena goes full screen on the projector (`ProjectorScreens`), and auto-calibration shows the pattern (now from `core`) and succeeds.
3. Projector → Background lists the eight bundled backgrounds (now from `core`), and one shows.
4. Add Pepper_Popper to the arena and shoot its plate: it falls (`GifFrames`). Press Reset: it stands up, and shots pause for a moment (`RangeReset`).
5. Start "Random Target PAR Drill with Score" (v2) for two rounds, then pick "None".
6. Quit, then check the log: `command grep -nE "Exception|NoClassDefFoundError|NoSuchMethodError" build/plan5-javafx-run.log` prints nothing.

- [ ] **Step 7: The owner's files**

```bash
cd /home/bfears/projects/ShootOFF
ls exercises/
command grep -v " shootoff.properties$" build/plan5-owner-files.sha256 | sha256sum -c
git status --short
```

Expected:
- `RandomTargetParDrill-v2.jar` and `RandomTargetParDrill.jar` listed
- three `OK` lines. `shootoff.properties` is left out: both apps save the owner's choices there, such as the camera, the marker size and the arena position.
- `git status --short` lists only `shootoff.properties` and `.superpowers/`, plus anything the owner added.

Things this machine can't exercise: a second camera, a HiDPI screen (all three screens here are at scale 1.0) and a machine with no projector. Note them as unchecked in the report.

---

## Spec coverage

| Spec | Where |
|---|---|
| §1 owner decisions: a polished working slice; Range dark with a light variant; big feed with a bottom tray; the Camera \| Arena switch; the rail with Targets and Sessions disabled; extract first | Tasks 1–13; rulings 13–15 |
| §1 criterion 1: `./gradlew :compose-app:run` opens in Range dark with the status strip, the switch and a working light/dark switch | Tasks 1, 4, 11, 13; Task 15 item 1–3 |
| §1 criterion 2: camera pick, arena on the projector, auto-calibration and the manual box | Tasks 7, 8, 11; Task 15 items 4–7 |
| §1 criterion 3: the drill end to end | Tasks 5, 6, 9, 10; Task 15 item 8 |
| §1 criterion 4: `./gradlew run` still starts the JavaFX app, which works as before, and the suite passes | Task 1 Step 7 (ruling 3), Tasks 2–3, every gate, Task 15 Step 6 |
| §2 `compose-app`: Kotlin 2.x, Compose Desktop, Material 3; depends on `core` and `plugin-api` only; no OpenJFX; the catalog; the other modules gain nothing | Task 1 (ruling 1), Task 14 |
| §2 app state | Task 11 (`AppState`), Task 13 (`UiPrefs`) |
| §2 camera feed: `CameraView`, `BufferedImage` frames for Compose, markers and banners | Task 4, Task 6 (`MarkerLayer`) |
| §2 target layer: ellipses, rectangles, polygons, images, GIF frames, visibility and region visibility | Task 5 |
| §2 arena window on the detected projector screen | Tasks 3 (`ProjectorScreens`), 7 |
| §2 calibration overlay: auto-detect progress, then a draggable, resizable box | Task 8 |
| §2 drill panel: buttons, number settings, texts, timer columns and rows | Task 10 |
| §2 screens: Range with the switch, Drills, Settings; Targets and Sessions disabled | Task 11, Task 1 |
| §2 `ComposeExerciseHost` on the shared host support | Task 9 |
| §3 frames conflated, converted off the UI thread; diagnostic warnings as banners | Task 4 (ruling 17) |
| §3 shots: the shared pipeline, the right `TargetSet`, rows and markers as observable lists | Task 6 (rulings 4–5) |
| §3 one arena model, two views | Task 7 |
| §3 one exercise thread; host calls update observable state; no host call waits on the UI thread | Task 9 (ruling 4), the contract suite |
| §3 calibration drives the arena, the camera and the overlay; bounds saved through `Settings` | Task 8 |
| §3 one `shootoff.properties` through `Settings` | Tasks 11–13 (ruling 13) |
| §4 errors: no camera; no projector; calibration timeout; an exercise throws; broken jars; `UserNotifier` | Task 12, Task 11 (projector), Task 8 (timeout) |
| §4 polish: status strip; light/dark; animations; resizable, collapsible tray; remembered window and view; HiDPI; shortcuts | Tasks 4, 13 |
| §4 out of scope (the built-in exercises, Targets/Sessions/Courses, the phone remote, the calibration drift, format changes) | not built; ruling 12 |
| §5 the contract suite against `ComposeExerciseHost` | Task 9 (feed and arena) |
| §5 Compose UI tests: drill panel, the switch, the calibration overlay's states, Targets and Sessions disabled, error banners | Tasks 10, 11, 8, 1, 12 |
| §5 boundary test | Task 14 |
| §5 owner hardware check and a short JavaFX regression check | Task 15 |
| §6 Plan 5 | this plan |
| §7 risks: frame rate (measured, ruling 17); rendering differences (the model drives drawing; owner check); toolchain isolated to `compose-app` (ruling 1) | Tasks 4, 5, 1, 15 |
| Plan 4 final review gaps 1–5 | Task 2 (1); Tasks 3, 6 (2); Task 6 (3); Task 8 (4); Task 9 (5) |
