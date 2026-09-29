# Compose Plan 13: The Six Standard Exercises on the v2 API — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Both apps list and run the same six standard built-in exercises (Shoot for Score, Random Shoot, Timed Holster Drill, PAR Drill with Score, PAR Drill with a random Subtarget, ISSF 25M Standard Pistol) from one new module on the v2 exercise API, each scoring and timing the shooter's targets on the camera feed and on the projector arena alike, with Pause/Resume where they have rounds; their JavaFX classes are gone.

**Architecture:**
- **Tasks 1–2: the API additions** (`plugin-api`), each implemented at once by `FakeExerciseHost`, the Compose host and the JavaFX host, with `ExerciseHostContract` tests run against both apps' hosts on both surfaces. G1: yes/no and choice settings. G2: `hasSound`. G4: `resetTargets` (stand the targets up and clear the shots without the exercise's `onReset`). G5: `reloadVirtualMagazine`.
- **Tasks 3–4: everywhere mode.** A camera (non-projector) exercise sees the camera feed's targets and then the arena's, hears about changes on either, and takes camera shots and arena shots. The shot pipeline already hands a camera shot that lands in the projection to the arena only, so each shot arrives once. Task 3 does it in the Compose app (the host, the runner, the arena overlay, calibration); Task 4 in the JavaFX app's v2 host.
- **Tasks 5–8: the ports.** Task 5 adds the `builtin-exercises` module (Java 21, depending only on `plugin-api`) with `BuiltInRegistry`, lists it in both apps, and ports Shoot for Score. Tasks 6–8 port Random Shoot, the Timed Holster family (Timed Holster, Par for Score and Par Random Shot together, since they subclass each other), and ISSF. Each task removes the JavaFX classes it replaces and moves their tests to `FakeExerciseHost`.
- **Task 9: the owner's hardware check.**

**Tech Stack:**
- Java 21 (`core`, `plugin-api`, `builtin-exercises`, `javafx-app`); Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- JUnit 5 for new tests; JUnit 4 for the ported JavaFX exercise tests (see "The gate and the moved tests"); Compose UI tests with `createComposeRule`; JavaFX tests with `JavaFXThreadingRule` / `Platform.runLater`.
- `FakeExerciseHost` from `plugin-api`'s test fixtures.

**Spec:** `docs/superpowers/specs/2026-09-29-exercise-port-design.md` (commit `a6c034de`). This plan is its §7 "Plan 13". Read the whole spec. Where the spec and this plan disagree, the spec wins. Plan 14 (the four projector drills, the overlap banner, removing `TrainingExerciseBase`, `ProjectorTrainingExerciseBase` and `BuiltInExercises`' legacy entries) is not in this plan: those classes stay while the projector drills use them.

**Prototype.**
- The whole plan was built in a scratch clone of `compose-ui` at `a6c034de`, one commit per task (Tasks 1–8).
- The gate ran green at the start (**951/951**) and after every task: **957, 972, 979, 980, 984, 986, 1002, 1005**.
- The code blocks below are the prototype's edits, extracted from those commits by a script. It checked that each "Replace … with …" pair matches exactly once in the file at the point it is applied (pairs in order, top to bottom), and that applying a task's pairs gives its commit byte for byte. New files are given whole.
- Tests on new API (Tasks 1–4) don't compile before their task's main changes; that is their failing run. The ported exercises' tests don't compile before the exercise exists. Two tests pin a behaviour a plausible wrong implementation would miss: `TestAppState.aDrillStandsFallenTargetsBackUpOnTheFeedAndTheArena` (`HostContext`'s default `resetTargets` only clears the shots, so it fails without `AppState`'s wiring), and `TestParForScore.theParTimeScoresFromTheBeepToTheChime` (a literal port of the JavaFX class doesn't score the first round; ruling 22).
- The GUI apps were not run. Task 9 is the owner's.

**What only the hardware can confirm** (Task 9): that each exercise sounds, speaks and times as it did in the JavaFX app; that a camera drill scores a projected target on the wall once per shot; that its text shows on the wall; that calibrating mid-drill pauses a drill with rounds.

## Plan-author rulings

Where the spec leaves room, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

### The API additions (Tasks 1–2)

1. **G1 is two methods:** `addYesNoSetting(String label, boolean initial, Consumer<Boolean> onChange)` (a check box) and `addChoiceSetting(String label, List<String> choices, String initial, Consumer<String> onChange)` (a drop-down: Compose `DropdownMenu`, JavaFX `ComboBox`). An `initial` that isn't one of `choices` throws `IllegalArgumentException` in the caller's thread, in every host. Like number settings, their values aren't persisted (spec §6: "values persist as the host's settings already do", and number settings don't). `FakeExerciseHost` gets `changeSetting(String, boolean)`, `chooseSetting`, `yesNoSettingValue`, `choiceSettingValue` and `settingChoices`. *Cost:* none of the six uses G1; Plan 14's Bouncing Targets is its first user.
2. **G2 is `boolean hasSound(String resourceOrFile)`**, looking where `playSound` looks: the exercise's jar, then ShootOFF's folder, then its `sounds/` folder (`ExerciseHostSupport.hasSound`). *Cost:* none.
3. **G4 is `void resetTargets()`:** every target's animations go back to their first frames, on the camera feed and the arena, and the shots are cleared as `clearShots` clears them (ShootOFF's markers and shot timer, and the exercise's own markers). It is not the Reset button: it doesn't call the exercise's `onReset`, doesn't reset the cameras (which restarts their shot timestamps and detectors), doesn't reset the shot processors, and doesn't pause detection for a second. Compose: `HostContext.resetTargets`, which `AppState` wires to `standTargetsUp()` plus `feedSurface.clear()`; it defaults to `clearShots` for harnesses. JavaFX: `CanvasManager.reset()` on the exercise's canvas, every camera's canvas and (Task 4) the canvases a camera exercise sees, on the JavaFX thread. *Cost:* Dueling Tree (Plan 14) did get the processor reset from v1's `camerasSupervisor.reset()`; if that matters there, Plan 14 adds `reloadVirtualMagazine` beside it.
4. **G5 is `void reloadVirtualMagazine()`:** it resets the `VirtualMagazineProcessor` in `Settings.getShotProcessors()` while `useVirtualMagazine()` is on, as v1's `TrainingExerciseBase.reloadVirtualMagazine` did (`ExerciseHostSupport.reloadVirtualMagazine(Settings)`), at once on the caller's thread. The contract gives the harness `Settings settings()` to check it. *Cost:* none.
5. **The fake plays the contract's part in its own test**, not the contract: the contract checks that callbacks run on the exercise's own thread, which the single-threaded fake by design doesn't have. `TestFakeExerciseHost` covers G1, G2, G4 and G5 (`targetResets()`, `magazineReloads()`).

### Everywhere mode (Tasks 3–4)

6. **Every camera (non-projector) v2 exercise runs everywhere**, plugins included, as spec §5 says "for non-projector exercises". *Cost:* a camera-feed plugin now also gets arena shots and sees arena targets; the owner's only plugin is a projector drill.
7. **The arena's targets are the arena layout's** (`AppState.arenaLayout.targets`, Plan 11): the shooter's projector targets, which exist whether or not the arena window is open. That set is one object for the app's life, so the host listens to it from start to stop. With the window closed no shot can reach them. *Cost:* with the arena closed and no subtarget target on the feed, Random Shoot or Par Random Shot could call out a target on the closed arena. The owner's arena opens at launch.
8. **Order:** `targets()` lists the camera feed's targets, then the arena's, as v1's `knownTargets` did. Exercises that take "the first target with subtargets" prefer the feed.
9. **Each shot once.** `ShotPipeline.addShot` hands a camera shot inside the projection to the arena and returns, so it is delivered once, as an arena shot in arena coordinates (checked in `ShotPipeline`, and pinned end to end by `TestEverywhereMode.aCameraShotAndAnArenaShotEachReachItOnce`). `ExerciseRunner.deliver` takes an arena shot when the host `takesArenaShots` (a projector exercise, or a camera exercise that runs everywhere) and a camera shot when the host isn't a projector host. *Cost:* none.
10. **Texts and markers.** `showText` draws on the camera feed and on the arena at the same (x, y), each in its own surface's coordinates. `showShotMarker`'s markers stay on the camera feed, whose coordinates they are in. None of the six uses either. *Cost:* a plugin placing text by the feed's size may land differently on a larger arena.
11. **`showMessage`** shows the feed's banner as before and, for a camera exercise that runs everywhere, the same text at the arena's top left, white, 24 arena units high, as v1's arena label (`ArenaExerciseOverlay`). A projector drill's banner stays off the arena (spec: projector mode is unchanged). An empty message shows neither banner nor arena text (ISSF's Reset clears its message with `""`, as v1 did; Task 8).
12. **Surface size, background, own targets.** `surfaceSize()` and `isProjector()` stay the camera feed's; `addTarget` adds to the camera feed (spec §5); `setBackground` is ignored, as on any camera feed.
13. **Calibration pauses a running camera drill that has a Pause button**, since it now uses the arena's targets, and leaves one without a Pause button running (Shoot for Score, Random Shoot). Projector drills are unchanged. *Cost:* a camera drill with rounds needs Resume after calibrating, as a projector drill does.
14. **The JavaFX app needed it too.** The brief's premise that "the JavaFX host already gives standard exercises all canvases" held for v1 exercises only: `JavaFxExerciseHost` listed only the first camera's canvas and dropped arena shots for a camera exercise. `ExerciseHostContext` gains `Optional<Supplier<List<CanvasManager>>> everywhere` (a second constructor keeps the old one); `ShootOFFController` gives a camera exercise every camera's canvas and, while the arena is open, the arena window's canvas (not the arena tab's copy). `onTargetsChanged` already fired for every canvas (`HostedExercise.targetUpdate`), and `showMessage` already reached every camera feed and the arena. `showText` stays on the first camera's canvas. *Cost:* JavaFX-only; none of the six uses `showText`.

### The module and the ports (Tasks 5–8)

15. **The package is `com.shootoff.plugins`**, where the JavaFX classes were and where `core`'s `ExerciseMetadata` is, so the ported classes keep their fully qualified names (the Training menu's `shootoff.xml` test fixture, `TestPluginLoading`, still names `com.shootoff.plugins.ShootForScore`). No new package. Each JavaFX class is removed in the same task that adds its port, since two classes of one name can't share a classpath.
16. **`BuiltInRegistry.entries()`** returns `List<V2ExerciseEntry>` in v1's Training menu order (ISSF, Random Shoot, Shoot for Score, Timed Holster, Par for Score, Par Random Shot), each entry's metadata read from a throwaway instance. The JavaFX `BuiltInExercises.entries()` is the registry's entries followed by the legacy ones still to port, so the menu order shifts between Tasks 5 and 8 and is v1's again after Task 8. The Compose Drills screen sorts by name, as it always has. The Compose app builds its engine through `pluginEngine(catalog)` in `ExerciseCatalog.kt`, so a test can check it.
17. **Faithful means:** metadata (name, version, creator, description) verbatim — so the names are "PAR Drill with Score", "PAR Drill with a random Subtarget" and "ISSF 25M Standard Pistol", as in the JavaFX menu; the spec's table uses short labels. Column names, sounds, spoken sentences and texts verbatim; `showTextOnFeed` becomes `showMessage`; `%n` becomes `\n`; timings (10 s start, 5 s resume, random delay in whole seconds from `nextInt(max − min + 1) + min`, 150/20/10 s series). The lengths are formatted with `String.format` in the default locale, as before.
18. **Timers:** `host.schedule` replaces executors and `Thread.sleep`; each drill keeps at most one pending step (`scheduleNext` cancels the one before), plus ISSF's series end. `stop()` only sets a flag: the host cancels the tasks.
19. **Random numbers** come from a `Random` the drill is given through a package-private constructor; the public no-argument constructor uses `new Random()`. With seed 15, Random Shoot calls out exactly what the JavaFX test expected, so its assertions are unchanged.
20. **The shared controls start at the JavaFX defaults**: the Holster family and ISSF set the shared delay to 4–8 s at start, and the PAR drills the par time to 2.0 s, as each v1 exercise's own new pane showed and as the owner's RandomTargetParDrill does.
21. **Row shading:** v1's `setShotTimerRowColor(LIGHTGRAY)` coloured every later row until changed; the port calls `styleLastRow(new RowStyle("lightgray"))` on each shot of a shaded round.

### What the JavaFX code does that the spec doesn't say

22. **Par for Score's first round didn't score.** v1 set `countScore` only after a round ended (`setupRound`), so the first round after start counted no points, nor the first after a Reset made during a par time (the interrupted sleep cleared it). The port counts from each beep to its chime. *For the owner to confirm* (Task 9 step 4): this is the one scoring difference from the JavaFX app.
23. **Resume could start a second chain of rounds** (Timed Holster family): v1's Pause left the next round scheduled, so Resume within that time ran both. The port's Pause cancels the pending step.
24. **Pausing inside a par time** in v1 still chimed and paused detection (the sleeping thread finished). The port's Pause ends the par time without the chime.
25. **Par Random Shot without a subtarget target** warned and ran no rounds at start, but after Reset ran silent rounds (chimes, no call-out), and showed neither the timing controls nor "score: 0". The port warns and runs no rounds, on start, Reset and Resume alike, until Reset finds such a target; its timing controls and "score: 0" show as for Par for Score.
26. **Kept as they were:** Par Random Shot's call-out replaces the beep, its rows are never shaded (it never sets `hadShot`) and every shot gets a Length; Timed Holster leaves detection on between rounds while the PAR drills pause it after the chime; a round's shading survives Reset in the Holster family; Random Shoot may pick a subtarget twice in one sequence (`rng.ints`) and keeps v1's `subtargetIndex > subtargets.size()` check; Shoot for Score shows nothing on a miss, the score on any hit, and no message at start.
27. **Random Shoot's target changes.** v1 heard ADDED/REMOVED of one target; v2 gets the whole list. While no target is called out, a list with a subtarget target starts the call-outs (only then: a plain target arriving plays no warning); the called-out target leaving starts them on another or warns; other changes do nothing.
28. **Par Random Shot's targets.** v1 read cameras and arena at start but only the cameras on Reset; the port reads `host.targets()` (both) each time.
29. **ISSF had no Pause.** The port's Pause abandons the series under way: its points come off the totals and Resume shoots it again (5 s, "make ready", the random delay, the beep), with the same shading. Paused between series, Resume makes ready for the next one. After the event is over, Resume only turns detection back on. v1 threw on a shot before the first series (`endRound` null); the port can't.

### Pause, per exercise

30. **Shoot for Score and Random Shoot have no Pause**: nothing runs between shots, so there is nothing to hold (spec §5: "Every exercise with rounds or timers"). Calibration leaves them running (ruling 13).
31. **Timed Holster, Par for Score, Par Random Shot:** Pause (label "Resume") turns detection off and cancels the pending step (and the par time, rulings 23–24). Resume (label "Pause") makes ready 5 s later, then rounds as usual. Reset while paused un-pauses and starts afresh 10 s later.
32. **ISSF:** ruling 29; Reset while paused un-pauses and starts the event afresh 10 s later.

### The gate and the moved tests

33. **The nine baseline tests keep their names.** `scripts/test_summary.py compare` keys tests by `classname.method` from every `*/build/test-results/test`, so a test that moves module under the same class and method name still matches the Java 8 baseline; one that is renamed, or run by JUnit 5 (whose names end in `()`), reads as `REGRESSION …: MISSING`. So the ported `TestShootForScore`, `TestRandomShoot` and `TestISSFStandardPistol` stay in package `com.shootoff.plugins`, keep their method names, and stay JUnit 4 (`org.junit.Test`; the vintage engine is on every module's test runtime). The baseline file and the compare script are unchanged. The ledger, checked in each port task:

| Baseline test | Moves in |
|---|---|
| `TestShootForScore.testReset`, `testJustRed`, `testJustGreen`, `testRedAndGreen` | Task 5 |
| `TestRandomShoot.testNoTarget`, `testFiveSmallTarget`, `testNoSoundFilesForSubtargetNames` | Task 6 |
| `TestISSFStandardPistol.testFullRound`, `testFull150sThenReset` | Task 8 |

New tests (Tasks 7–8 new classes) are JUnit 5, like the owner's `TestRandomTargetParDrill`.

34. **The JavaFX tests that named a v1 standard exercise** now name others: `TestExerciseSlide.v2ExercisesAreListedWithV1Ones` lists the v1 Steel Challenge with the v2 Shoot for Score and its V2 drill; `TestHostedExercise` uses a test-local v1 camera drill. `javafx-app`'s test resource `shootoff.xml` still names `com.shootoff.plugins.ShootForScore`, now the v2 class; its test only needs the file to exist.

**For the owner to decide** (none blocks the plan): ruling 22 (Par for Score's first round), ruling 7 (arena targets while the window is closed), ruling 13 (calibration pauses camera drills).

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** plain sentences in the repo's style (e.g. "Hold the exposure at one frame for the whole pattern search"), with **no `Co-Authored-By` or any other trailer**. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage or modify `shootoff.properties` (it has the owner's local edits). Stage files by path (`git add <path>…`, `git rm <path>`, `git mv <from> <to>`). Never use `git add -A` or `git add .`. Never `git stash`, `git restore` or `git reset`.
- **The owner's files.** Never modify, move, delete or stage:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - anything under `exercises/` or `exercise-data/`
  - `arena-layout.course` in the ShootOFF folder, and any file under `courses/` or `targets/` (the owner's untracked `arena-layout.course` and `courses/mine.course` exist: never stage them)
  - the owner's Java preferences
  Checksums are in `build/plan8-owner-files.sha256`: `sha256sum -c build/plan8-owner-files.sha256` must print OK for every line.
- **Tests and the owner's settings:** tests use scratch directories (`@TempDir`, JUnit 4's `TemporaryFolder`, `ArenaFiles.scratch()`) and `ScratchConfig` (or `new Settings(new String[0])`, which reads no file), never the owner's `shootoff.properties`, `shootoff.home`, `courses/` or `arena-layout.course`. Tests read the bundled `targets/` and `sounds/` only, and no test runs a built-in exercise on a real host (so nothing is written to `exercise-data/`). A test that sets `shootoff.home` or the default locale puts it back.
- **Code style:**
  - Kotlin: the official style (4 spaces, trailing commas).
  - Java: tabs; match the surrounding code.
  - A new main-source file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java`. Test files have no header.
  - Keep imports sorted as each file sorts them.
- **Packages:** `com.shootoff.*`; no new packages (the new module's classes are in `com.shootoff.plugins`).
- **Modules and dependencies:** one new module, `builtin-exercises` (`java-library`), depending only on `:plugin-api` (tests: `plugin-api`'s and `core`'s test fixtures). `javafx-app` and `compose-app` depend on it (`implementation`). No external dependency is added anywhere. No OpenJFX in `compose-app` or `builtin-exercises` (`TestNoJavaFxInComposeApp` and `TestBuiltInRegistry.theBuiltInExercisesDoNotReferenceJavaFx` pass).
- **Formats:** `.target`, `.course`, the session formats and `shootoff.properties` keys are unchanged. No settings key is added.
- **The exercise API** (`plugin-api`) changes only by G1, G2, G4 and G5 (Tasks 1–2). Kept for Plan 14: `TrainingExerciseBase`, `ProjectorTrainingExerciseBase`, `TrainingExercise`, `BuiltInExercises` and the four projector drills.
- **Publishing:** none. Never publish to `~/.m2`.
- Implementers never run the GUI apps (`./gradlew run`, `:compose-app:run`, `:javafx-app:run`); Task 9 is the owner's.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper.
- **Test gate** (run in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10`; Bash timeout 600000 ms):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. The count starts at **951** (end of Plan 12); each task gives the count it ends at. A lower count means tests silently stopped running. A `REGRESSION … MISSING` line for a moved test means it lost its name (ruling 33).
  - `core`'s `TestMalfunctionsProcessor.testManyMalfunctions` is probabilistic. If it alone fails, run the gate again.
  - The Compose and JavaFX UI tests need a display; the gate runs them on this machine's `DISPLAY=:0`.
  - After the gate, `git status --short` shows only ` M shootoff.properties` plus the task's own files (and the owner's untracked `arena-layout.course` and `courses/mine.course`, never staged), and `sha256sum -c build/plan8-owner-files.sha256` prints all OK.

## Review Focus

1. **Pause pressed mid-round, then Resume** (in a par time, in an ISSF series, while "make ready" is pending). Expected: one chain of rounds afterwards, no stray chime, the abandoned ISSF series shot again without its points.
   - *Tests:* `TestTimedHolsterDrill.pauseStopsTheRoundsAndResumeMakesReadyFiveSecondsLater` (one pending task after Resume), `TestParForScore.pausingInTheParTimeEndsItWithoutTheChime` (Task 7); `TestISSFStandardPistol.pauseAbandonsTheSeriesUnderWayAndResumeShootsItAgain` (Task 8).
2. **A camera shot inside the projection during a camera drill.** Expected: the drill scores it once, on the arena target, not also as a camera miss.
   - *Test:* `TestEverywhereMode.aCameraShotAndAnArenaShotEachReachItOnce` (Task 3).
3. **Calibrating (F6) while a camera drill runs.** Expected: a drill with rounds pauses and stays the same drill; one without keeps running.
   - *Test:* `TestCalibrationPausesTheDrill.f6PausesACameraDrillWithAPauseButtonAndLeavesOneWithoutRunning` (Task 3).
4. **The shooter moves targets mid-drill** (removes the target Random Shoot calls out, or starts Par Random Shot before placing one). Expected: call-outs move to another subtarget target, or a warning; no call-outs of a target that isn't there, no silent rounds.
   - *Tests:* `TestRandomShoot.theCalledOutTargetLeavingMovesOnOrWarns`, `aTargetWithSubtargetsArrivingStartsTheCallOuts` (Task 6); `TestParRandomShot.withoutATargetWithSubtargetsItWarnsAndRunsNoRoundsUntilResetFindsOne` (Task 7).
5. **Reset mid-drill, including while paused.** Expected: the drill un-pauses and starts afresh after its 10 s, scores zeroed, and ISSF's cleared message leaves no empty banner.
   - *Tests:* `TestTimedHolsterDrill.resetStartsAfreshTenSecondsLater`, `TestParForScore.resetZeroesTheScores` (Task 7); `TestISSFStandardPistol.testFull150sThenReset`, `TestRangeScreen.anEmptyDrillMessageShowsNoBanner` (Task 8).

Task 9 covers what no unit test reaches: the sounds, voices and timings on the owner's range, a projected target on the wall, and the arena text.

## Module and file map

| Where | What | Task |
|---|---|---|
| `plugin-api/…/exercise/ExerciseHost.java`, `…/exercise/host/ExerciseHostSupport.java` | G1, G2, G4, G5 | 1, 2 |
| `plugin-api/src/testFixtures/…/exercise/{FakeExerciseHost, ExerciseHostContract}.java` | the fake's side and the contract | 1, 2 |
| `compose-app/…/drill/{ComposeExerciseHost, DrillState, DrillSettings}.kt` | G1 controls; G2/G4/G5 | 1, 2 |
| `compose-app/…/drill/{HostSurface, ExerciseRunner, ExerciseTexts}.kt`, `…/app/AppState.kt`, `…/Main.kt` | everywhere mode; G4's wiring; calibration | 2, 3 |
| `javafx-app/…/gui/exercise/{JavaFxExerciseHost, ExerciseHostContext}.java`, `…/gui/controller/ShootOFFController.java` | G1–G5; everywhere mode | 1, 2, 4 |
| `builtin-exercises/` (new): `BuiltInRegistry` and the six exercises | the ports | 5–8 |
| `settings.gradle.kts`, `{javafx-app, compose-app}/build.gradle.kts`, `…/compose/app/ExerciseCatalog.kt`, `javafx-app/…/plugins/BuiltInExercises.java` | the module in both apps | 5–8 |
| `compose-app/…/app/RangeScreen.kt` | no empty banner | 8 |
| none | the owner's hardware check | 9 |

`compose-app/…/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; its tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `plugin-api/…/` is `plugin-api/src/main/java/com/shootoff/`. `javafx-app/…/` is `javafx-app/src/main/java/com/shootoff/`; its tests are under `javafx-app/src/test/java/com/shootoff/`. `builtin-exercises/` has `src/main/java/com/shootoff/plugins/` and `src/test/java/com/shootoff/plugins/`.

**New tests and the gate after each task:**

| Task | Test methods added | Gate |
|---|---|---|
| 1 | `plugin-api` `TestFakeExerciseHost` (+1); `ExerciseHostContract` (+1, run by 4 host tests: +4); `compose` `drill/TestDrillPanel` (+1) | 957 |
| 2 | `TestFakeExerciseHost` (+2); `ExerciseHostContract` (+3, ×4: +12); `compose` `app/TestAppState` (+1) | 972 |
| 3 | `compose` `drill/TestEverywhereMode` (+4, new), `drill/TestDrillPanel` (+1), `app/TestCalibrationPausesTheDrill` (+1), `app/TestAppState` (+1) | 979 |
| 4 | `javafx` `gui/exercise/TestJavaFxExerciseHost` (+1) | 980 |
| 5 | `builtin` `TestShootForScore` (4 moved from `javafx`), `TestBuiltInRegistry` (+3, new); `compose` `app/TestAppState` (+1) | 984 |
| 6 | `builtin` `TestRandomShoot` (3 moved, +2) | 986 |
| 7 | `builtin` `TestTimedHolsterDrill` (+7), `TestParForScore` (+5), `TestParRandomShot` (+4), all new | 1002 |
| 8 | `builtin` `TestISSFStandardPistol` (2 moved, +2); `compose` `app/TestRangeScreen` (+1) | 1005 |

**Suggested models** (implementer / reviewer): Task 1 sonnet / sonnet; Task 2 sonnet / opus (host threading: resets and clears from the exercise's thread); Task 3 sonnet / opus (everywhere mode: routing, listeners, the arena overlay, calibration); Task 4 sonnet / opus (the JavaFX host's canvases and threads); Tasks 5–8 sonnet / sonnet; Task 9 the owner.

---

### Task 1: Yes/no and choice settings (G1)

**Files:**
- Modify: `plugin-api/src/main/java/com/shootoff/exercise/ExerciseHost.java` (two methods)
- Modify: `plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java`, `…/ExerciseHostContract.java`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/drill/{DrillState, DrillSettings, ComposeExerciseHost}.kt`
- Modify: `javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java`
- Test: `plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java`, `compose-app/src/test/kotlin/com/shootoff/compose/drill/{ComposeHostHarness, TestDrillPanel}.kt`, `javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java`

**Interfaces:**
- Consumes: `ExerciseHostSupport.run(Runnable)` (runs a callback on the exercise's thread); `DrillState`'s settings list.
- Produces:
  - `ExerciseHost.addYesNoSetting(String label, boolean initial, Consumer<Boolean> onChange)`; `ExerciseHost.addChoiceSetting(String label, List<String> choices, String initial, Consumer<String> onChange)` (throws `IllegalArgumentException` if `initial ∉ choices`).
  - `FakeExerciseHost.changeSetting(String, boolean)`, `chooseSetting(String, String)`, `yesNoSettingValue(String)`, `choiceSettingValue(String)`, `settingChoices(String)`.
  - `ExerciseHostContract.Harness.changeYesNoSetting(String, boolean)`, `chooseSetting(String, String)`.
  - Compose: `sealed interface DrillSetting { id; label }` implemented by `NumberSetting`, `YesNoSetting(id, label, value: Boolean, onChange)`, `ChoiceSetting(id, label, choices, value: String, onChange)`; `DrillState.settings: StateFlow<List<DrillSetting>>`; `DrillState.setSettingValue(Long, Boolean)`, `setSettingChoice(Long, String)`. Test tags `setting-<label>` (the check box / the drop-down's button) and `setting-<label>-<choice>` (a menu item).

**Why.** Spec §5 G1 (Bouncing Targets, Plan 14, has a check box and number combos). Ruling 1.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/drill/ComposeHostHarness.kt`:

Replace:

```kotlin
    }

    override fun changeSetting(label: String, value: Double) {
        val setting = drill.settings.value.firstOrNull { it.label == label } ?: throw AssertionError("No setting $label")
        setting.onChange(value)
    }

    override fun userSetsParTime(seconds: Double) {
```

with:

```kotlin
    }

    override fun changeSetting(label: String, value: Double) = setting<NumberSetting>(label).onChange(value)

    override fun changeYesNoSetting(label: String, value: Boolean) = setting<YesNoSetting>(label).onChange(value)

    override fun chooseSetting(label: String, choice: String) = setting<ChoiceSetting>(label).onChange(choice)

    private inline fun <reified S : DrillSetting> setting(label: String): S =
        drill.settings.value.filterIsInstance<S>().firstOrNull { it.label == label } ?: throw AssertionError("No setting $label")

    override fun userSetsParTime(seconds: Double) {
```

Replace:

```kotlin
        drill.name.value,
        drill.buttons.value.map { it.label },
        drill.settings.value.map { it.label to it.value },
        drill.texts.value,
        drill.timing.value?.let { Triple(it.showsParTime, it.parTime, it.delay) },
```

with:

```kotlin
        drill.name.value,
        drill.buttons.value.map { it.label },
        drill.settings.value.map {
            it.label to when (it) {
                is NumberSetting -> it.value
                is YesNoSetting -> it.value
                is ChoiceSetting -> it.value
            }
        },
        drill.texts.value,
        drill.timing.value?.let { Triple(it.showsParTime, it.parTime, it.delay) },
```

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestDrillPanel.kt`:

Replace:

```kotlin
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextEquals
```

with:

```kotlin
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTextEquals
```

Replace:

```kotlin

    @Test
    fun parTimeAndStartDelayReachTheExercise() {
        drill.setTiming(TimingControls(true, 2.0, DelayRange(4, 8), { heard += "par $it" }, { min, max -> heard += "delay $min-$max" }))
```

with:

```kotlin

    @Test
    fun yesNoAndChoiceSettingsReachTheExercise() {
        drill.addSetting(YesNoSetting(1, "Remove hit targets", false) { value ->
            heard += "remove $value"
            drill.setSettingValue(1, value)
        })
        drill.addSetting(ChoiceSetting(2, "Speed", listOf("1", "5", "10"), "5") { choice ->
            heard += "speed $choice"
            drill.setSettingChoice(2, choice)
        })
        show { DrillSettings(drill) }

        compose.onNodeWithTag("setting-Remove hit targets").performClick()
        compose.onNodeWithTag("setting-Speed").performClick()
        compose.onNodeWithTag("setting-Speed-10").performClick()

        assertEquals(listOf("remove true", "speed 10"), heard)
        compose.onNodeWithTag("setting-Remove hit targets").assertIsOn()
        compose.onNodeWithTag("setting-Speed").assertTextEquals("10")
    }

    @Test
    fun parTimeAndStartDelayReachTheExercise() {
        drill.setTiming(TimingControls(true, 2.0, DelayRange(4, 8), { heard += "par $it" }, { min, max -> heard += "delay $min-$max" }))
```

`javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java`:

Replace:

```java
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
```

with:

```java
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
```

Replace:

```java
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

```

with:

```java
	public void changeSetting(String label, double value) throws Exception {
		onFx(() -> {
			((Spinner<Double>) settingControl(label)).getValueFactory().setValue(value);
			return null;
		});
	}

	@Override
	public void changeYesNoSetting(String label, boolean value) throws Exception {
		onFx(() -> {
			((CheckBox) settingControl(label)).setSelected(value);
			return null;
		});
	}

	@Override
	@SuppressWarnings("unchecked")
	public void chooseSetting(String label, String choice) throws Exception {
		onFx(() -> {
			((ComboBox<String>) settingControl(label)).setValue(choice);
			return null;
		});
	}

	// The control beside the setting's label; on the JavaFX thread
	private Node settingControl(String label) {
		for (final Node pane : container.getChildren()) {
			if (pane instanceof HBox box && box.getChildren().get(0) instanceof Label name
					&& label.equals(name.getText())) {
				return box.getChildren().get(1);
			}
		}
		throw new AssertionError("No setting " + label);
	}

```

`plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java`:

Replace:

```java

	@Test
	void parAndDelayListenersHearOnlyUserChanges() {
		final List<String> heard = new ArrayList<>();
```

with:

```java

	@Test
	void yesNoAndChoiceSettingsAreRecordedAndCallBack() {
		final List<String> calls = new ArrayList<>();
		host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> calls.add("rounds " + value));
		host.addYesNoSetting("Remove hit targets", false, value -> calls.add("remove " + value));
		host.addChoiceSetting("Speed", List.of("1", "2", "3"), "2", choice -> calls.add("speed " + choice));

		host.changeSetting("Remove hit targets", true);
		host.chooseSetting("Speed", "3");

		assertEquals(List.of("remove true", "speed 3"), calls);
		assertEquals(List.of("Rounds", "Remove hit targets", "Speed"), host.settingLabels());
		assertTrue(host.yesNoSettingValue("Remove hit targets"));
		assertEquals("3", host.choiceSettingValue("Speed"));
		assertEquals(List.of("1", "2", "3"), host.settingChoices("Speed"));
		assertThrows(IllegalArgumentException.class, () -> host.chooseSetting("Speed", "4"));
		assertThrows(IllegalStateException.class, () -> host.changeSetting("Speed", true));
		assertThrows(IllegalStateException.class, () -> host.yesNoSettingValue("Rounds"));
		assertThrows(IllegalArgumentException.class,
				() -> host.addChoiceSetting("Count", List.of("1", "2"), "5", choice -> {}));
	}

	@Test
	void parAndDelayListenersHearOnlyUserChanges() {
		final List<String> heard = new ArrayList<>();
```

`plugin-api/src/testFixtures/java/com/shootoff/exercise/ExerciseHostContract.java`:

Replace:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
```

with:

```java
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
```

Replace:

```java
		/** The user sets the exercise's number setting with this label. */
		void changeSetting(String label, double value) throws Exception;

		/** The user types a par time into the shared control. */
```

with:

```java
		/** The user sets the exercise's number setting with this label. */
		void changeSetting(String label, double value) throws Exception;

		/** The user ticks or clears the exercise's yes/no setting with this label. */
		void changeYesNoSetting(String label, boolean value) throws Exception;

		/** The user picks a choice of the exercise's choice setting with this label. */
		void chooseSetting(String label, String choice) throws Exception;

		/** The user types a par time into the shared control. */
```

Replace:

```java

	@Test
	void stopRunsTheExercisesStopExactlyOnceFromAnyThread() throws Exception {
		final Harness h = harness();
```

with:

```java

	@Test
	void yesNoAndChoiceSettingsHearTheUsersChangesOnTheExercisesThread() throws Exception {
		final List<String> heard = new CopyOnWriteArrayList<>();
		drill.onStart = host -> {
			host.addYesNoSetting("Remove hit targets", false, value -> {
				heard.add("remove " + value);
				drill.record("yes/no");
			});
			host.addChoiceSetting("Speed", List.of("1", "5", "10"), "5", choice -> {
				heard.add("speed " + choice);
				drill.record("choice");
			});
		};
		final Harness h = harness();
		h.start();
		awaitEvent("start");
		h.awaitUi();

		h.changeYesNoSetting("Remove hit targets", true);
		h.chooseSetting("Speed", "10");
		awaitEvent("yes/no");
		awaitEvent("choice");

		assertEquals(List.of("remove true", "speed 10"), heard);
		assertEquals(Set.of(EXERCISE_THREAD), Set.copyOf(drill.threads.values()));
		assertThrows(IllegalArgumentException.class,
				() -> h.host().addChoiceSetting("Count", List.of("1", "2"), "5", choice -> {}));
	}

	@Test
	void stopRunsTheExercisesStopExactlyOnceFromAnyThread() throws Exception {
		final Harness h = harness();
```

Replace:

```java
			host.addButton("Pause", () -> {});
			host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> {});
			host.addColumn("Score");
			host.showShotMarker(20, 20, new ShotStyle(ShotColor.RED));
```

with:

```java
			host.addButton("Pause", () -> {});
			host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> {});
			host.addYesNoSetting("Remove hit targets", true, value -> {});
			host.addChoiceSetting("Speed", List.of("1", "2"), "1", choice -> {});
			host.addColumn("Score");
			host.showShotMarker(20, 20, new ShotStyle(ShotColor.RED));
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :plugin-api:compileTestJava :compose-app:compileTestKotlin :javafx-app:compileTestJava --console=plain`
Expected: FAIL to compile: `addYesNoSetting`, `addChoiceSetting`, `YesNoSetting`, `ChoiceSetting` and the fake's new methods don't exist.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`:

Replace:

```kotlin
    }

    // ---- Shot timer

```

with:

```kotlin
    }

    override fun addYesNoSetting(label: String, initial: Boolean, onChange: Consumer<Boolean>) {
        val id = nextId.incrementAndGet()
        ui {
            drill.addSetting(
                YesNoSetting(id, label, initial) { value ->
                    drill.setSettingValue(id, value)
                    support.run(guarded { onChange.accept(value) })
                },
            )
            settings += id
        }
    }

    override fun addChoiceSetting(label: String, choices: List<String>, initial: String, onChange: Consumer<String>) {
        require(initial in choices) { "$label: $initial isn't one of $choices" }
        val id = nextId.incrementAndGet()
        ui {
            drill.addSetting(
                ChoiceSetting(id, label, choices.toList(), initial) { choice ->
                    drill.setSettingChoice(id, choice)
                    support.run(guarded { onChange.accept(choice) })
                },
            )
            settings += id
        }
    }

    // ---- Shot timer

```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillSettings.kt`:

Replace:

```kotlin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
```

with:

```kotlin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
```

Replace:

```kotlin
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
```

with:

```kotlin
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
```

Replace:

```kotlin

/**
 * The running drill's settings: its number settings, and the shared par time and start delay while it
 * listens to them. A typed value counts once the user presses Enter or leaves the field; − and + step it.
 */
@Composable
```

with:

```kotlin

/**
 * The running drill's settings: its number, yes/no and choice settings, and the shared par time and start
 * delay while it listens to them. A typed value counts once the user presses Enter or leaves the field; −
 * and + step it.
 */
@Composable
```

Replace:

```kotlin
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (setting in settings) {
            NumberField(setting.label, setting.value, setting.min, setting.max, setting.step, "setting-${setting.label}") {
                setting.onChange(it)
            }
        }
```

with:

```kotlin
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (setting in settings) {
            when (setting) {
                is NumberSetting -> NumberField(setting.label, setting.value, setting.min, setting.max, setting.step, "setting-${setting.label}") {
                    setting.onChange(it)
                }
                is YesNoSetting -> YesNoField(setting)
                is ChoiceSetting -> ChoiceField(setting)
            }
        }
```

Replace:

```kotlin
}

// A step's sum without floating point dust (2.0 - 0.1 is 1.9)
private fun stepped(value: Double): Double = Math.round(value * 1_000_000) / 1_000_000.0
```

with:

```kotlin
}

@Composable
private fun YesNoField(setting: YesNoSetting) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(setting.label, color = Range.colors.mutedStrong, modifier = Modifier.width(120.dp))
        Checkbox(
            checked = setting.value,
            onCheckedChange = { setting.onChange(it) },
            modifier = Modifier.testTag("setting-${setting.label}"),
        )
    }
}

@Composable
private fun ChoiceField(setting: ChoiceSetting) {
    var open by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(setting.label, color = Range.colors.mutedStrong, modifier = Modifier.width(120.dp))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("setting-${setting.label}")) {
                Text(setting.value, style = NumberStyle)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (choice in setting.choices) {
                    DropdownMenuItem(
                        text = { Text(choice) },
                        onClick = {
                            open = false
                            if (choice != setting.value) setting.onChange(choice)
                        },
                        modifier = Modifier.testTag("setting-${setting.label}-$choice"),
                    )
                }
            }
        }
    }
}

// A step's sum without floating point dust (2.0 - 0.1 is 1.9)
private fun stepped(value: Double): Double = Math.round(value * 1_000_000) / 1_000_000.0
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillState.kt`:

Replace:

```kotlin
const val RESUME_LABEL = "Resume"

data class NumberSetting(
    val id: Long,
    val label: String,
    val value: Double,
    val min: Double,
```

with:

```kotlin
const val RESUME_LABEL = "Resume"

/** One of the running exercise's settings, in the drill panel */
sealed interface DrillSetting {
    val id: Long
    val label: String
}

data class NumberSetting(
    override val id: Long,
    override val label: String,
    val value: Double,
    val min: Double,
```

Replace:

```kotlin
    val step: Double,
    val onChange: (Double) -> Unit,
)

/** A text the exercise shows on its surface, in the surface's coordinates */
```

with:

```kotlin
    val step: Double,
    val onChange: (Double) -> Unit,
) : DrillSetting

/** A check box */
data class YesNoSetting(
    override val id: Long,
    override val label: String,
    val value: Boolean,
    val onChange: (Boolean) -> Unit,
) : DrillSetting

/** A drop-down of [choices] */
data class ChoiceSetting(
    override val id: Long,
    override val label: String,
    val choices: List<String>,
    val value: String,
    val onChange: (String) -> Unit,
) : DrillSetting

/** A text the exercise shows on its surface, in the surface's coordinates */
```

Replace:

```kotlin
    private val nameState = MutableStateFlow<String?>(null)
    private val buttonState = MutableStateFlow<List<DrillButton>>(emptyList())
    private val settingState = MutableStateFlow<List<NumberSetting>>(emptyList())
    private val textState = MutableStateFlow<List<DrillText>>(emptyList())
    private val timingState = MutableStateFlow<TimingControls?>(null)
```

with:

```kotlin
    private val nameState = MutableStateFlow<String?>(null)
    private val buttonState = MutableStateFlow<List<DrillButton>>(emptyList())
    private val settingState = MutableStateFlow<List<DrillSetting>>(emptyList())
    private val textState = MutableStateFlow<List<DrillText>>(emptyList())
    private val timingState = MutableStateFlow<TimingControls?>(null)
```

Replace:

```kotlin
    val name: StateFlow<String?> = nameState.asStateFlow()
    val buttons: StateFlow<List<DrillButton>> = buttonState.asStateFlow()
    val settings: StateFlow<List<NumberSetting>> = settingState.asStateFlow()
    val texts: StateFlow<List<DrillText>> = textState.asStateFlow()
    val timing: StateFlow<TimingControls?> = timingState.asStateFlow()
```

with:

```kotlin
    val name: StateFlow<String?> = nameState.asStateFlow()
    val buttons: StateFlow<List<DrillButton>> = buttonState.asStateFlow()
    val settings: StateFlow<List<DrillSetting>> = settingState.asStateFlow()
    val texts: StateFlow<List<DrillText>> = textState.asStateFlow()
    val timing: StateFlow<TimingControls?> = timingState.asStateFlow()
```

Replace:

```kotlin
    fun removeButton(id: Long) = buttonState.update { buttons -> buttons.filterNot { it.id == id } }

    fun addSetting(setting: NumberSetting) = settingState.update { it + setting }

    fun removeSetting(id: Long) = settingState.update { settings -> settings.filterNot { it.id == id } }

    /** The user set a setting's value */
    fun setSettingValue(id: Long, value: Double) =
        settingState.update { settings -> settings.map { if (it.id == id) it.copy(value = value) else it } }

    fun addText(text: DrillText) = textState.update { it + text }
```

with:

```kotlin
    fun removeButton(id: Long) = buttonState.update { buttons -> buttons.filterNot { it.id == id } }

    fun addSetting(setting: DrillSetting) = settingState.update { it + setting }

    fun removeSetting(id: Long) = settingState.update { settings -> settings.filterNot { it.id == id } }

    /** The user set a number setting's value */
    fun setSettingValue(id: Long, value: Double) = updateSetting(id) { if (it is NumberSetting) it.copy(value = value) else it }

    /** The user ticked or cleared a yes/no setting */
    fun setSettingValue(id: Long, value: Boolean) = updateSetting(id) { if (it is YesNoSetting) it.copy(value = value) else it }

    /** The user picked one of a choice setting's choices */
    fun setSettingChoice(id: Long, choice: String) = updateSetting(id) { if (it is ChoiceSetting) it.copy(value = choice) else it }

    private fun updateSetting(id: Long, change: (DrillSetting) -> DrillSetting) =
        settingState.update { settings -> settings.map { if (it.id == id) change(it) else it } }

    fun addText(text: DrillText) = textState.update { it + text }
```

`javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java`:

Replace:

```java
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
```

with:

```java
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
```

Replace:

```java
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
```

with:

```java
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
```

Replace:

```java
			});

			final HBox pane = new HBox(10, new Label(label), spinner);
			pane.setAlignment(Pos.CENTER_LEFT);
			context.view().getTrainingExerciseContainer().getChildren().add(pane);
			panes.add(pane);
		});
	}

```

with:

```java
			});

			addSettingPane(label, spinner);
		});
	}

	@Override
	public void addYesNoSetting(String label, boolean initial, Consumer<Boolean> onChange) {
		fx(() -> {
			final CheckBox checkBox = new CheckBox();
			checkBox.setSelected(initial);
			checkBox.selectedProperty().addListener((observable, oldValue, newValue) -> {
				support.run(() -> onChange.accept(newValue));
			});

			addSettingPane(label, checkBox);
		});
	}

	@Override
	public void addChoiceSetting(String label, List<String> choices, String initial, Consumer<String> onChange) {
		if (!choices.contains(initial)) {
			throw new IllegalArgumentException(label + ": " + initial + " isn't one of " + choices);
		}
		final List<String> items = List.copyOf(choices);

		fx(() -> {
			final ComboBox<String> comboBox = new ComboBox<>(FXCollections.observableArrayList(items));
			comboBox.setValue(initial);
			comboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
				if (newValue != null) support.run(() -> onChange.accept(newValue));
			});

			addSettingPane(label, comboBox);
		});
	}

	// A setting's label and control, in the exercise pane
	private void addSettingPane(String label, Node control) {
		final HBox pane = new HBox(10, new Label(label), control);
		pane.setAlignment(Pos.CENTER_LEFT);
		context.view().getTrainingExerciseContainer().getChildren().add(pane);
		panes.add(pane);
	}

```

`plugin-api/src/main/java/com/shootoff/exercise/ExerciseHost.java`:

Replace:

```java
	 */
	void addNumberSetting(String label, double initial, double min, double max, double step, DoubleConsumer onChange);

	/**
```

with:

```java
	 */
	void addNumberSetting(String label, double initial, double min, double max, double step, DoubleConsumer onChange);

	/**
	 * Adds a yes/no setting (a check box) to the exercise pane. <tt>onChange</tt> hears every value the
	 * user sets.
	 */
	void addYesNoSetting(String label, boolean initial, Consumer<Boolean> onChange);

	/**
	 * Adds a setting with a fixed list of choices (a drop-down) to the exercise pane. <tt>onChange</tt>
	 * hears every choice the user makes.
	 *
	 * @param initial
	 *            one of <tt>choices</tt>
	 * @throws IllegalArgumentException
	 *             if <tt>initial</tt> isn't one of <tt>choices</tt>
	 */
	void addChoiceSetting(String label, List<String> choices, String initial, Consumer<String> onChange);

	/**
```

`plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java`:

Replace:

```java
 * visible targets;</li>
 * <li>{@link #advance} moves the manual clock and runs the tasks that fall due;</li>
 * <li>{@link #click}, {@link #changeSetting}, {@link #changeParTime} and {@link #changeDelayedStart}
 * use the controls.</li>
 * </ul>
 * Everything runs on the calling thread, which plays the exercise thread.
```

with:

```java
 * visible targets;</li>
 * <li>{@link #advance} moves the manual clock and runs the tasks that fall due;</li>
 * <li>{@link #click}, {@link #changeSetting(String, double)}, {@link #changeSetting(String, boolean)},
 * {@link #chooseSetting}, {@link #changeParTime} and {@link #changeDelayedStart} use the controls.</li>
 * </ul>
 * Everything runs on the calling thread, which plays the exercise thread.
```

Replace:

```java
	private final List<FakeText> texts = new ArrayList<>();
	private final List<FakeButton> buttons = new ArrayList<>();
	private final Map<String, FakeSetting> settings = new LinkedHashMap<>();
	private final List<String> columns = new ArrayList<>();
	private final List<FakeRow> rows = new ArrayList<>();
```

with:

```java
	private final List<FakeText> texts = new ArrayList<>();
	private final List<FakeButton> buttons = new ArrayList<>();
	// A FakeSetting, FakeYesNoSetting or FakeChoiceSetting by its label
	private final Map<String, Object> settings = new LinkedHashMap<>();
	private final List<String> columns = new ArrayList<>();
	private final List<FakeRow> rows = new ArrayList<>();
```

Replace:

```java

	public void changeSetting(String label, double value) {
		final FakeSetting setting = settings.get(label);
		if (setting == null) throw new IllegalStateException("No setting labeled " + label);
		if (value < setting.min || value > setting.max) {
			throw new IllegalArgumentException(String.format("%s must be between %s and %s, not %s", label,
```

with:

```java

	public void changeSetting(String label, double value) {
		final FakeSetting setting = setting(label, FakeSetting.class);
		if (value < setting.min || value > setting.max) {
			throw new IllegalArgumentException(String.format("%s must be between %s and %s, not %s", label,
```

Replace:

```java
		setting.value = value;
		setting.onChange.accept(value);
	}

```

with:

```java
		setting.value = value;
		setting.onChange.accept(value);
	}

	/**
	 * The user ticks or clears a yes/no setting.
	 */
	public void changeSetting(String label, boolean value) {
		final FakeYesNoSetting setting = setting(label, FakeYesNoSetting.class);
		setting.value = value;
		setting.onChange.accept(value);
	}

	/**
	 * The user picks one of a choice setting's choices.
	 */
	public void chooseSetting(String label, String choice) {
		final FakeChoiceSetting setting = setting(label, FakeChoiceSetting.class);
		if (!setting.choices.contains(choice)) {
			throw new IllegalArgumentException(label + " has no choice " + choice + "; its choices are " + setting.choices);
		}

		setting.value = choice;
		setting.onChange.accept(choice);
	}

	private <S> S setting(String label, Class<S> kind) {
		final Object setting = settings.get(label);
		if (setting == null) throw new IllegalStateException("No setting labeled " + label);
		if (!kind.isInstance(setting)) {
			throw new IllegalStateException(label + " is a " + setting.getClass().getSimpleName() + ", not a "
					+ kind.getSimpleName());
		}
		return kind.cast(setting);
	}

```

Replace:

```java

	public double settingValue(String label) {
		final FakeSetting setting = settings.get(label);
		if (setting == null) throw new IllegalStateException("No setting labeled " + label);
		return setting.value;
	}

```

with:

```java

	public double settingValue(String label) {
		return setting(label, FakeSetting.class).value;
	}

	public boolean yesNoSettingValue(String label) {
		return setting(label, FakeYesNoSetting.class).value;
	}

	public String choiceSettingValue(String label) {
		return setting(label, FakeChoiceSetting.class).value;
	}

	public List<String> settingChoices(String label) {
		return setting(label, FakeChoiceSetting.class).choices;
	}

```

Replace:

```java
			DoubleConsumer onChange) {
		if (!stopped) settings.put(label, new FakeSetting(initial, min, max, onChange));
	}

```

with:

```java
			DoubleConsumer onChange) {
		if (!stopped) settings.put(label, new FakeSetting(initial, min, max, onChange));
	}

	@Override
	public void addYesNoSetting(String label, boolean initial, Consumer<Boolean> onChange) {
		if (!stopped) settings.put(label, new FakeYesNoSetting(initial, onChange));
	}

	@Override
	public void addChoiceSetting(String label, List<String> choices, String initial, Consumer<String> onChange) {
		if (!choices.contains(initial)) {
			throw new IllegalArgumentException(label + ": " + initial + " isn't one of " + choices);
		}
		if (!stopped) settings.put(label, new FakeChoiceSetting(List.copyOf(choices), initial, onChange));
	}

```

Replace:

```java
	}

	private static final class FakeRow {
		final Optional<Shot> shot;
```

with:

```java
	}

	private static final class FakeYesNoSetting {
		boolean value;
		final Consumer<Boolean> onChange;

		FakeYesNoSetting(boolean value, Consumer<Boolean> onChange) {
			this.value = value;
			this.onChange = onChange;
		}
	}

	private static final class FakeChoiceSetting {
		final List<String> choices;
		String value;
		final Consumer<String> onChange;

		FakeChoiceSetting(List<String> choices, String value, Consumer<String> onChange) {
			this.choices = choices;
			this.value = value;
			this.onChange = onChange;
		}
	}

	private static final class FakeRow {
		final Optional<Shot> shot;
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :plugin-api:test :compose-app:test --tests '*Contract*' --tests '*DrillPanel*' :javafx-app:test --tests '*ExerciseHost*' --console=plain`
Expected: PASS, including `yesNoAndChoiceSettingsHearTheUsersChangesOnTheExercisesThread` on all four contract runs.

- [ ] **Step 5: The gate**

Run the gate. Expected: `957/957 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add plugin-api/src/main/java/com/shootoff/exercise/ExerciseHost.java plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java plugin-api/src/testFixtures/java/com/shootoff/exercise/ExerciseHostContract.java plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillState.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/DrillSettings.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/ComposeHostHarness.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestDrillPanel.kt javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java
git commit -m "Let an exercise add yes/no and choice settings"
git log -1 --format=%B
```

---

### Task 2: Sounds, standing targets up, and the virtual magazine (G2, G4, G5)

**Files:**
- Modify: `plugin-api/src/main/java/com/shootoff/exercise/ExerciseHost.java` (three methods), `plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java` (`hasSound`, `reloadVirtualMagazine(Settings)`)
- Modify: `plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java`, `…/ExerciseHostContract.java`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt` (`HostContext.resetTargets`), `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`standTargetsUp`, `resetTargetsForDrill`)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java`
- Test: `plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java`, `compose-app/src/test/kotlin/com/shootoff/compose/{app/TestAppState, drill/ComposeHostHarness}.kt`, `javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java`

**Interfaces:**
- Consumes: Task 1's `ExerciseHost`; `AppState.arenaLayout.targets`, `feedTargets`, `feedSurface.clear()`; `CanvasManager.reset()`; `Settings.useVirtualMagazine()`, `getShotProcessors()`.
- Produces:
  - `ExerciseHost.hasSound(String resourceOrFile): boolean`, `resetTargets()`, `reloadVirtualMagazine()`.
  - `ExerciseHostSupport.hasSound(String)`, `static reloadVirtualMagazine(Settings)`.
  - `FakeExerciseHost.targetResets()`, `magazineReloads()` (ints); its `resetTargets()` clears its rows and markers.
  - `ExerciseHostContract.Harness.settings(): Settings`.
  - Compose: `HostContext(…, resetTargets: () -> Unit = clearShots)` (last parameter).

**Why.** Spec §5 G2 (Random Shoot, Par Random Shot: a voice file, else text to speech), G4 (Dueling Tree, Plan 14), G5 (Steel Challenge, Plan 14). Rulings 2–5.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

Replace:

```kotlin

            app.reset()

            assertTrue(app.arenaLayout.targets.animations.isOnFirstFrame(onArena))
```

with:

```kotlin

            app.reset()

            assertTrue(app.arenaLayout.targets.animations.isOnFirstFrame(onArena))
            assertTrue(app.feedTargets.animations.isOnFirstFrame(onFeed))
        } finally {
            app.close()
        }
    }

    // Exercise port spec §5 G4: a drill stands the shooter's targets back up, on the feed and the arena, as Reset does
    @Test
    fun aDrillStandsFallenTargetsBackUpOnTheFeedAndTheArena() {
        val clock = ManualClock()
        val app = AppFixture.app(clock = clock)
        try {
            val onArena = AppFixture.fallenPopper(app.arenaLayout.targets, clock)
            val onFeed = AppFixture.fallenPopper(app.feedTargets, clock)
            assertTrue(app.startDrill(AppFixture.feedDrill))

            app.runner.running.value!!.host.resetTargets()

            assertTrue(app.arenaLayout.targets.animations.isOnFirstFrame(onArena))
```

`compose-app/src/test/kotlin/com/shootoff/compose/drill/ComposeHostHarness.kt`:

Replace:

```kotlin
    override fun playedSounds(): List<String> = played.toList()

    override fun close() = host.stop()
}
```

with:

```kotlin
    override fun playedSounds(): List<String> = played.toList()

    override fun settings(): Settings = settings

    override fun close() = host.stop()
}
```

`javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java`:

Replace:

```java
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
```

with:

```java
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.Settings;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
```

Replace:

```java

	@Override
	public int viewCount() {
		return mirror.isPresent() ? 2 : 1;
```

with:

```java

	@Override
	public Settings settings() {
		return config;
	}

	@Override
	public int viewCount() {
		return mirror.isPresent() ? 2 : 1;
```

`plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java`:

Replace:

```java
		assertTrue(Files.isDirectory(host.dataDirectory()));
	}
}
```

with:

```java
		assertTrue(Files.isDirectory(host.dataDirectory()));
	}

	@Test
	void aSoundExistsInTheExercisesJarOrShootoffsFolder() throws IOException {
		final Path jar = Files.createDirectories(temp.resolve("jar/sounds"));
		Files.write(jar.resolve("cue.wav"), new byte[] { 1 });
		host.withResources(new URLClassLoader(new URL[] { temp.resolve("jar").toUri().toURL() }, null));

		assertTrue(host.hasSound("sounds/cue.wav"));
		assertTrue(host.hasSound("@sounds/cue.wav"));
		assertTrue(host.hasSound("sounds/voice/shootoff-makeready.wav"));
		assertTrue(host.hasSound("beep.wav"));
		assertFalse(host.hasSound("sounds/voice/shootoff-undefined_region_name_5.wav"));
		assertFalse(host.hasSound(""));
	}

	@Test
	void resettingTargetsClearsTheShotsWithoutResettingTheExercise() {
		final Recorder recorder = new Recorder();
		host.start(recorder);
		host.shoot(ShotColor.RED, 5, 5);
		host.showShotMarker(5, 5, new ShotStyle(ShotColor.RED));

		host.resetTargets();
		host.reloadVirtualMagazine();

		assertEquals(List.of(), host.rows());
		assertEquals(List.of(), host.shotMarkers());
		assertEquals(1, host.targetResets());
		assertEquals(1, host.magazineReloads());
		assertEquals(List.of("start", "shot -1"), recorder.events);
	}
}
```

`plugin-api/src/testFixtures/java/com/shootoff/exercise/ExerciseHostContract.java`:

Replace:

```java

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;
```

with:

```java

import com.shootoff.camera.Shot;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Settings;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;
```

Replace:

```java
		 */
		List<String> playedSounds();

		@Override
```

with:

```java
		 */
		List<String> playedSounds();

		/**
		 * @return the settings the host was given
		 */
		Settings settings();

		@Override
```

Replace:

```java

	@Test
	void namesAreLookedUpInTheExercisesJarFirst() throws Exception {
		final Harness h = harness();
```

with:

```java

	@Test
	void aSoundExistsInTheExercisesJarOrShootoffsFolder() throws Exception {
		final ExerciseHost host = harness().host();

		assertTrue(host.hasSound("sounds/cue.wav"));
		assertTrue(host.hasSound("sounds/beep.wav"));
		assertTrue(host.hasSound("beep.wav"));
		assertFalse(host.hasSound("sounds/voice/shootoff-undefined_region_name_5.wav"));
	}

	@Test
	void resettingTargetsClearsTheExercisesMarkersWithoutCallingOnReset() throws Exception {
		final Harness h = harness();
		h.start();
		awaitEvent("start");
		h.awaitUi();
		final Object before = h.shownState();

		h.host().showShotMarker(20, 20, new ShotStyle(ShotColor.RED));
		h.awaitUi();
		assertNotEquals(before, h.shownState());

		h.host().resetTargets();
		h.awaitUi();

		assertEquals(before, h.shownState());
		assertEquals(List.of("start"), drill.events);
	}

	@Test
	void aReloadFillsTheVirtualMagazineOnlyWhileItIsOn() throws Exception {
		final Harness h = harness();
		final Settings settings = h.settings();
		settings.setUseVirtualMagazine(true);
		settings.setVirtualMagazineCapacity(3);
		final VirtualMagazineProcessor magazine = settings.getShotProcessors().stream()
				.filter(VirtualMagazineProcessor.class::isInstance).map(VirtualMagazineProcessor.class::cast)
				.findFirst().orElseThrow();
		magazine.setUseTTS(false);
		final Shot shot = new Shot(ShotColor.RED, 0, 0, 0);
		magazine.processShot(shot);
		magazine.processShot(shot);
		assertEquals(1, magazine.getRountCount());

		h.host().reloadVirtualMagazine();
		assertEquals(3, magazine.getRountCount());

		magazine.processShot(shot);
		settings.setUseVirtualMagazine(false);
		h.host().reloadVirtualMagazine();
		assertEquals(2, magazine.getRountCount());
	}

	@Test
	void namesAreLookedUpInTheExercisesJarFirst() throws Exception {
		final Harness h = harness();
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :plugin-api:compileTestJava :compose-app:compileTestKotlin :javafx-app:compileTestJava --console=plain`
Expected: FAIL to compile: `hasSound`, `resetTargets`, `reloadVirtualMagazine`, `targetResets`, `magazineReloads` and `Harness.settings()` don't exist.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    /** Reset: the cameras, the arena's animations and the shots, then the drill, then a short pause in detection */
    fun reset() {
        // The cameras reset their feeds' and the open arena's targets; these stand up without a camera or arena too
        arenaLayout.targets.animations.resetAll()
        feedTargets.animations.resetAll()
        rangeReset.reset { runner.reset() }
    }

```

with:

```kotlin
    /** Reset: the cameras, the arena's animations and the shots, then the drill, then a short pause in detection */
    fun reset() {
        standTargetsUp()
        rangeReset.reset { runner.reset() }
    }

    // The cameras reset their feeds' and the open arena's targets; these stand up without a camera or arena too
    private fun standTargetsUp() {
        arenaLayout.targets.animations.resetAll()
        feedTargets.animations.resetAll()
    }

    // What a drill's resetTargets does: Reset's targets and shots, without the cameras' reset (which restarts
    // their shot timers), the drill's own reset, or the pause in detection
    private fun resetTargetsForDrill() {
        standTargetsUp()
        feedSurface.clear()
    }

```

Replace:

```kotlin
                setDetecting = cameras::setDetectingAll,
                onFailure = runner::failed,
            ),
        )
```

with:

```kotlin
                setDetecting = cameras::setDetectingAll,
                onFailure = runner::failed,
                resetTargets = ::resetTargetsForDrill,
            ),
        )
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`:

Replace:

```kotlin
 * @param setDetecting turns every camera's shot detection on or off
 * @param onFailure hears that one of the exercise's callbacks threw
 */
class HostContext(
```

with:

```kotlin
 * @param setDetecting turns every camera's shot detection on or off
 * @param onFailure hears that one of the exercise's callbacks threw
 * @param resetTargets stands every target on the camera feed and the arena back up, and clears the shots
 */
class HostContext(
```

Replace:

```kotlin
    val setDetecting: (Boolean) -> Unit,
    val onFailure: (ComposeExerciseHost, Throwable) -> Unit = { _, _ -> },
)

```

with:

```kotlin
    val setDetecting: (Boolean) -> Unit,
    val onFailure: (ComposeExerciseHost, Throwable) -> Unit = { _, _ -> },
    val resetTargets: () -> Unit = clearShots,
)

```

Replace:

```kotlin
    }

    override fun pauseShotDetection(paused: Boolean) = support.pauseShotDetection(paused) { context.setDetecting(it) }

```

with:

```kotlin
    }

    override fun resetTargets() {
        if (support.isStopped) return

        context.resetTargets()
        ui {
            drill.markers.removeAll(markers)
            markers.clear()
        }
    }

    override fun reloadVirtualMagazine() {
        if (!support.isStopped) ExerciseHostSupport.reloadVirtualMagazine(context.settings)
    }

    override fun pauseShotDetection(paused: Boolean) = support.pauseShotDetection(paused) { context.setDetecting(it) }

```

Replace:

```kotlin
        context.sounds.play(name, sound) { whenDone.run() }
    }

    override fun say(text: String) {
```

with:

```kotlin
        context.sounds.play(name, sound) { whenDone.run() }
    }

    override fun hasSound(resourceOrFile: String): Boolean = support.hasSound(resourceOrFile)

    override fun say(text: String) {
```

`javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java`:

Replace:

```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
```

with:

```java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
```

Replace:

```java
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
```

with:

```java
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraView;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
```

Replace:

```java

	@Override
	public void pauseShotDetection(boolean paused) {
		support.pauseShotDetection(paused, context.cameras()::setDetectingAll);
```

with:

```java

	@Override
	public void resetTargets() {
		if (support.isStopped()) return;

		fx(() -> {
			// Each camera's canvas stands its targets and the arena's up and clears their shots, as Reset does
			// without restarting the cameras' shot timers
			final Set<CanvasManager> canvases = new LinkedHashSet<>();
			canvases.add(context.canvas());
			for (final CameraView view : context.cameras().getCameraViews()) {
				if (view instanceof CanvasManager canvas) canvases.add(canvas);
			}
			canvases.forEach(CanvasManager::reset);

			canvasChildren().removeAll(markers);
			canvasNodes.removeAll(markers);
			markers.clear();
		});
	}

	@Override
	public void reloadVirtualMagazine() {
		if (!support.isStopped()) ExerciseHostSupport.reloadVirtualMagazine(context.config());
	}

	@Override
	public void pauseShotDetection(boolean paused) {
		support.pauseShotDetection(paused, context.cameras()::setDetectingAll);
```

Replace:

```java
	public void playSounds(List<String> resourcesOrFiles) {
		support.playSounds(resourcesOrFiles, context.sounds()::play);
	}

```

with:

```java
	public void playSounds(List<String> resourcesOrFiles) {
		support.playSounds(resourcesOrFiles, context.sounds()::play);
	}

	@Override
	public boolean hasSound(String resourceOrFile) {
		return support.hasSound(resourceOrFile);
	}

```

`plugin-api/src/main/java/com/shootoff/exercise/ExerciseHost.java`:

Replace:

```java
	void clearShots();

	void pauseShotDetection(boolean paused);

```

with:

```java
	void clearShots();

	/**
	 * Stands every target back up, on the camera feeds and the arena: their animations go back to their
	 * first frames, as ShootOFF's Reset button does, and the shots are cleared as {@link #clearShots}
	 * does. Unlike the Reset button, it doesn't call the exercise's {@link Exercise#onReset}.
	 */
	void resetTargets();

	/**
	 * Fills the virtual magazine back to its capacity, as a reload does. Does nothing while the virtual
	 * magazine is off.
	 */
	void reloadVirtualMagazine();

	void pauseShotDetection(boolean paused);

```

Replace:

```java
	 */
	void playSounds(List<String> resourcesOrFiles);

	/**
```

with:

```java
	 */
	void playSounds(List<String> resourcesOrFiles);

	/**
	 * @return whether {@link #playSound} would find the sound: in the exercise's jar, in ShootOFF's folder,
	 *         or in its <tt>sounds/</tt> folder
	 */
	boolean hasSound(String resourceOrFile);

	/**
```

`plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java`:

Replace:

```java

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
```

with:

```java

import com.shootoff.camera.Shot;
import com.shootoff.camera.processors.ShotProcessor;
import com.shootoff.camera.processors.VirtualMagazineProcessor;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Settings;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
```

Replace:

```java
	}

	public void playSound(String resourceOrFile, SoundSink sink) {
		openSound(resourceOrFile).ifPresent(sound -> sink.play(resourceOrFile, sound, () -> {}));
```

with:

```java
	}

	/**
	 * @return whether {@link #openSound} would find the sound
	 */
	public boolean hasSound(String resourceOrFile) {
		return findResource(ExercisePaths.resourceName(resourceOrFile)).isPresent()
				|| ExercisePaths.shootoffFile(resourceOrFile, "sounds").isPresent();
	}

	/**
	 * Fills the virtual magazine back to its capacity, if it is on.
	 */
	public static void reloadVirtualMagazine(Settings settings) {
		if (!settings.useVirtualMagazine()) return;

		for (final ShotProcessor processor : settings.getShotProcessors()) {
			if (processor instanceof VirtualMagazineProcessor) processor.reset();
		}
	}

	public void playSound(String resourceOrFile, SoundSink sink) {
		openSound(resourceOrFile).ifPresent(sound -> sink.play(resourceOrFile, sound, () -> {}));
```

`plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java`:

Replace:

```java
	private long now = START_TIME;
	private long nextSequence = 0;

	/**
```

with:

```java
	private long now = START_TIME;
	private long nextSequence = 0;
	private int targetResets = 0;
	private int magazineReloads = 0;

	/**
```

Replace:

```java
	}

	public boolean isShotDetectionPaused() {
		return detectionPaused;
```

with:

```java
	}

	/**
	 * @return how many times the exercise stood the targets back up ({@link #resetTargets})
	 */
	public int targetResets() {
		return targetResets;
	}

	/**
	 * @return how many times the exercise reloaded the virtual magazine
	 */
	public int magazineReloads() {
		return magazineReloads;
	}

	public boolean isShotDetectionPaused() {
		return detectionPaused;
```

Replace:

```java

	@Override
	public void pauseShotDetection(boolean paused) {
		if (!stopped) detectionPaused = paused;
```

with:

```java

	@Override
	public void resetTargets() {
		if (stopped) return;
		targetResets++;
		rows.clear();
		markers.clear();
	}

	@Override
	public void reloadVirtualMagazine() {
		if (!stopped) magazineReloads++;
	}

	@Override
	public void pauseShotDetection(boolean paused) {
		if (!stopped) detectionPaused = paused;
```

Replace:

```java
	public void playSounds(List<String> resourcesOrFiles) {
		if (!stopped) sounds.addAll(resourcesOrFiles);
	}

```

with:

```java
	public void playSounds(List<String> resourcesOrFiles) {
		if (!stopped) sounds.addAll(resourcesOrFiles);
	}

	/**
	 * Looks where {@link #playSound} would: the exercise's jar ({@link #withResources}), then ShootOFF's
	 * folder and its <tt>sounds/</tt> folder.
	 */
	@Override
	public boolean hasSound(String resourceOrFile) {
		final String name = ExercisePaths.resourceName(resourceOrFile);
		return (!name.isEmpty() && resources.getResource(name) != null)
				|| ExercisePaths.shootoffFile(resourceOrFile, "sounds").isPresent();
	}

```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :plugin-api:test :compose-app:test --tests '*Contract*' --tests '*TestAppState*' :javafx-app:test --tests '*ExerciseHost*' --console=plain`
Expected: PASS.

- [ ] **Step 5: The gate**

Run the gate. Expected: `972/972 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add plugin-api/src/main/java/com/shootoff/exercise/ExerciseHost.java plugin-api/src/main/java/com/shootoff/exercise/host/ExerciseHostSupport.java plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java plugin-api/src/testFixtures/java/com/shootoff/exercise/ExerciseHostContract.java plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/ComposeHostHarness.kt javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java javafx-app/src/test/java/com/shootoff/gui/exercise/JavaFxHostHarness.java
git commit -m "Let an exercise ask for a sound, stand the targets back up and reload the virtual magazine"
git log -1 --format=%B
```

---

### Task 3: Camera drills run everywhere in the Compose app

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/drill/HostSurface.kt` (`seenTargets`, `takesArenaShots`; `FeedHostSurface`'s `arenaTargets`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt` (listeners on every seen surface, `targets()`, `Handle`'s surface, `takesArenaShots`, `runsEverywhere`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt` (`deliver`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseTexts.kt` (`showMarkers`, `ArenaExerciseOverlay`, the arena message)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt` (the arena window's overlay)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`newHost`, calibration)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestEverywhereMode.kt` (new), `drill/TestDrillPanel.kt`, `app/TestCalibrationPausesTheDrill.kt`, `app/TestAppState.kt`, `shots/SurfaceFixture.kt`

**Interfaces:**
- Consumes: `ShotPipeline.addShot`'s routing (Plan 12: a camera shot in the projection goes to the arena only); `AppState.arenaLayout.targets` (Plan 11); `SurfaceTargets.set` listeners; `AppState.pauseDrill()`.
- Produces:
  - `HostSurface.seenTargets: List<SurfaceTargets>` (default `listOf(targets)`), `HostSurface.takesArenaShots: Boolean` (default `isProjector`); `FeedHostSurface(targets, displaySize, arenaTargets: SurfaceTargets? = null)`.
  - `ComposeExerciseHost.takesArenaShots`, `ComposeExerciseHost.runsEverywhere` (Booleans).
  - `ArenaExerciseOverlay(drill: DrillState, projector: Boolean, everywhere: Boolean, transform: SurfaceTransform)`; `ExerciseOverlay(…, showMarkers: Boolean = true)`; test tag `arena-exercise-message`.
  - `SurfaceFixture(exercise: ShotReceiver? = null)` (test fixture).

**Why.** Spec §5 "Everywhere mode" and §2 (the owner: "from everywhere, as in the old app"). Rulings 6–13.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

Replace:

```kotlin
        } finally {
            app.close()
        }
    }

    // Spec §6: editing works with the arena closed, and the next window shows the layout
```

with:

```kotlin
        } finally {
            app.close()
        }
    }

    // Exercise port spec §5: a camera drill sees the shooter's arena targets beside the camera feed's
    @Test
    fun aCameraDrillRunsEverywhere() {
        val box = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf())))
        val onFeed = app.feedTargets.add(box, ResourceResolver.files())
        val onArena = app.arenaLayout.targets.add(box, ResourceResolver.files())
        assertTrue(app.startDrill(AppFixture.feedDrill))

        val host = app.runner.running.value!!.host

        assertTrue(host.runsEverywhere)
        assertEquals(listOf(onFeed.id, onArena.id), host.targets().map { it.id() })
    }

    // Spec §6: editing works with the arena closed, and the next window shows the layout
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationPausesTheDrill.kt`:

Replace:

```kotlin
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
```

with:

```kotlin
    }

    // Exercise port spec §5: a camera drill runs on the arena's targets too, so calibration pauses it if it can
    @Test
    fun f6PausesACameraDrillWithAPauseButtonAndLeavesOneWithoutRunning() {
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.pausingFeedDrill))
        awaitTrue { pauseLabel() == "Pause" }
        val pausable = app.runner.running.value!!.host

        app.handleKey(Key.F6, KeyEventType.KeyDown)

        awaitTrue { pauseLabel() == "Resume" }
        assertSame(pausable, app.runner.running.value!!.host)
        calibrateWithTheCamera()
        assertEquals("Resume", pauseLabel())

        assertTrue(app.startDrill(AppFixture.feedDrill))
        val unpausable = app.runner.running.value!!.host
        app.handleKey(Key.F6, KeyEventType.KeyDown)
        calibrateWithTheCamera()

        assertSame(unpausable, app.runner.running.value!!.host)
        assertFalse(unpausable.isStopped)
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
```

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestDrillPanel.kt`:

Replace:

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
```

with:

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
```

Replace:

```kotlin
    }

    @Test
    fun anExercisesTextSitsAtItsPlaceOnTheSurface() {
```

with:

```kotlin
    }

    // Exercise port spec §5: a camera drill's texts and banner show on the arena too; a projector drill's banner doesn't
    @Test
    fun theArenaShowsAnEverywhereDrillsTextsAndBanner() {
        drill.addText(DrillText(7, "Score: 0", 100.0, 50.0, TextStyle(40.0, "white", "transparent")))
        drill.setMessage("red score: 10")
        var projector by mutableStateOf(false)
        var everywhere by mutableStateOf(true)
        show {
            Box(Modifier.size(640.dp, 360.dp)) {
                ArenaExerciseOverlay(drill, projector, everywhere, SurfaceTransform.fit(Size(1280.0, 720.0), 640f, 360f))
            }
        }

        compose.onNodeWithTag("exercise-text-7").assertLeftPositionInRootIsEqualTo(50.dp).assertTopPositionInRootIsEqualTo(25.dp)
        compose.onNodeWithTag("arena-exercise-message").assertTextEquals("red score: 10")

        projector = true
        everywhere = false
        compose.onNodeWithTag("exercise-text-7").assertExists()
        compose.onNodeWithTag("arena-exercise-message").assertDoesNotExist()

        projector = false
        compose.onNodeWithTag("exercise-text-7").assertDoesNotExist()
    }

    @Test
    fun anExercisesTextSitsAtItsPlaceOnTheSurface() {
```

Create `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestEverywhereMode.kt`:

```kotlin
package com.shootoff.compose.drill

import com.shootoff.camera.Shot
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.camera.shot.ShotColor
import com.shootoff.compose.shots.ArenaPointShot
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.shots.SurfaceFixture
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.exercise.TargetHandle
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Exercise port spec §5: a camera exercise runs everywhere, on the camera feed and on the arena's targets. */
class TestEverywhereMode {
    companion object {
        val heard = CopyOnWriteArrayList<String>()

        @Volatile
        var host: ExerciseHost? = null
    }

    /** A camera drill that writes down its shots and the targets it hears about */
    class CameraDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Camera drill", "1.0", "ShootOFF tests", "On the camera feed")

        override fun start(host: ExerciseHost) {
            TestEverywhereMode.host = host
            heard += "start"
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {
            heard += "shot ${shot.x},${shot.y} on ${hit.map { it.targetId() }.orElse(null)}"
        }

        override fun onTargetsChanged(targets: List<TargetHandle>) {
            heard += "targets ${targets.size}"
        }

        override fun onReset() {}

        override fun stop() {}
    }

    private val entry = V2ExerciseEntry(CameraDrill::class.java, CameraDrill().metadata())
    private val fixture = SurfaceFixture { shot, hit, arenaShot -> runner.deliver(shot, hit, arenaShot) }
    private val drill = DrillState()
    private var everywhere = true

    private val silent = object : SoundOutput {
        override fun play(name: String, sound: InputStream, whenDone: () -> Unit) = whenDone()

        override fun say(text: String) {}
    }

    private val runner: ExerciseRunner = ExerciseRunner { _, exercise, runner ->
        val surface = FeedHostSurface(fixture.feed.targets, Size(640.0, 480.0), if (everywhere) fixture.arena.targets else null)
        ComposeExerciseHost(exercise, HostContext(fixture.settings, surface, ShotTimerModel(), drill, null, silent, {}, {}, runner::failed))
    }

    init {
        heard.clear()
        host = null
    }

    @AfterEach
    fun stop() = runner.stop()

    private fun box() = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 40.0, 40.0, "red", mapOf())))

    private fun awaitHeard(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (heard.size < count) {
            if (System.nanoTime() > deadline) throw AssertionError("heard only $heard")
            Thread.sleep(5)
        }
        // Nothing more is on its way
        Thread.sleep(100)
    }

    private fun start() {
        assertTrue(runner.start(entry))
        awaitHeard(1)
    }

    @Test
    fun itSeesTheFeedsTargetsThenTheArenasAndHearsAboutChangesOnEither() {
        val onFeed = fixture.feed.targets.add(box(), ResourceResolver.files(), Placement(50.0, 50.0, 1.0, 1.0, true))
        val onArena = fixture.arena.targets.add(box(), ResourceResolver.files(), Placement(620.0, 340.0, 1.0, 1.0, true))
        start()

        assertEquals(listOf(onFeed.id, onArena.id), host!!.targets().map { it.id() })

        // The exercise reads the targets when it hears of the change, on its own thread
        fixture.arena.targets.add(box(), ResourceResolver.files(), Placement(10.0, 10.0, 1.0, 1.0, true))
        awaitHeard(2)
        fixture.feed.targets.remove(onFeed.id)
        awaitHeard(3)

        assertEquals(listOf("start", "targets 3", "targets 2"), heard)
    }

    @Test
    fun aCameraShotAndAnArenaShotEachReachItOnce() {
        fixture.projection = Rect(100.0, 100.0, 320.0, 180.0)
        val onFeed = fixture.feed.targets.add(box(), ResourceResolver.files(), Placement(50.0, 50.0, 1.0, 1.0, true))
        val onArena = fixture.arena.targets.add(box(), ResourceResolver.files(), Placement(620.0, 340.0, 1.0, 1.0, true))
        start()

        // Beside the projection on the camera target, then inside it, where the pipeline passes it on to the arena
        fixture.feed.add(ScaledShot(ShotColor.RED, 60.0, 60.0, 1000))
        fixture.feed.add(ScaledShot(ShotColor.RED, 260.0, 190.0, 2000))
        awaitHeard(3)

        assertEquals(listOf("start", "shot 60.0,60.0 on ${onFeed.id}", "shot 640.0,360.0 on ${onArena.id}"), heard)
    }

    @Test
    fun itsOwnTargetsGoOnTheCameraFeed() {
        start()

        val added = host!!.addTarget("IPSC.target", 5.0, 5.0).get()

        assertEquals(listOf(added.id()), fixture.feed.targets.set.targets.map { it.id })
        assertEquals(TargetOwner.EXERCISE, fixture.feed.targets.owner(added.id()))
        assertEquals(emptyList<Any>(), fixture.arena.targets.set.targets)
    }

    @Test
    fun withoutTheArenaACameraDrillTakesCameraShotsOnly() {
        everywhere = false
        start()

        runner.deliver(ArenaPointShot(ScaledShot(ShotColor.RED, 1.0, 1.0, 0), 2.0, 2.0), null, true)
        runner.deliver(ScaledShot(ShotColor.RED, 3.0, 3.0, 0), null, false)
        awaitHeard(2)

        assertEquals(listOf("start", "shot 3.0,3.0 on null"), heard)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/shots/SurfaceFixture.kt`:

Replace:

```kotlin
import java.util.concurrent.CopyOnWriteArrayList

/** A camera feed and the arena wired as the Compose app wires them, with a recording exercise. */
class SurfaceFixture {
    data class Delivered(val shot: Shot, val hit: Hit?, val arenaShot: Boolean)

```

with:

```kotlin
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A camera feed and the arena wired as the Compose app wires them, with a recording exercise.
 *
 * @param exercise also hears every shot delivered, as the app's exercise runner does
 */
class SurfaceFixture(private val exercise: ShotReceiver? = null) {
    data class Delivered(val shot: Shot, val hit: Hit?, val arenaShot: Boolean)

```

Replace:

```kotlin
    private val receiver: ShotReceiver = ShotReceiver { shot, hit, arenaShot ->
        delivered += Delivered(shot, hit, arenaShot)
        true
    }

```

with:

```kotlin
    private val receiver: ShotReceiver = ShotReceiver { shot, hit, arenaShot ->
        delivered += Delivered(shot, hit, arenaShot)
        exercise?.deliver(shot, hit, arenaShot) ?: true
    }

```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:compileTestKotlin --console=plain`
Expected: FAIL to compile: `FeedHostSurface` takes two arguments, and `ArenaExerciseOverlay` and `runsEverywhere` don't exist.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`:

Replace:

```kotlin
import com.shootoff.compose.app.WindowRole
import com.shootoff.compose.arena.ArenaWindow
import com.shootoff.compose.drill.ExerciseOverlay
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.Settings
```

with:

```kotlin
import com.shootoff.compose.app.WindowRole
import com.shootoff.compose.arena.ArenaWindow
import com.shootoff.compose.drill.ArenaExerciseOverlay
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.config.Settings
```

Replace:

```kotlin
                key(arenaGeneration) {
                    ArenaWindow(shownArena, shownPlacement, onCloseRequest = app::closeArena, onKey = { app.handleKey(it.key, it.type) }) { transform ->
                        if (running?.host?.isProjector == true) ExerciseOverlay(app.drill, transform)
                    }
                }
```

with:

```kotlin
                key(arenaGeneration) {
                    ArenaWindow(shownArena, shownPlacement, onCloseRequest = app::closeArena, onKey = { app.handleKey(it.key, it.type) }) { transform ->
                        val host = running?.host
                        if (host != null) ArenaExerciseOverlay(app.drill, host.isProjector, host.runsEverywhere, transform)
                    }
                }
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    }

    // For a running projector drill only (a camera drill is left to detachCamera's own pauseDrill() call):
    // pauses it if it has a Pause button, or stops it instead, since nothing else can hold it off the arena
    // while the arena isn't available to it (calibrating, or, per spec §8 Revision 2 decision 7, the camera
    // gone). Returns what starts it again, for calibration to use afterwards; empty when nothing was stopped.
```

with:

```kotlin
    }

    // For a running projector drill (a camera drill is left to detachCamera's own pauseDrill() call, and to a
    // pause here when it runs everywhere): pauses it if it has a Pause button, or stops it instead, since nothing
    // else can hold it off the arena
    // while the arena isn't available to it (calibrating, or, per spec §8 Revision 2 decision 7, the camera
    // gone). Returns what starts it again, for calibration to use afterwards; empty when nothing was stopped.
```

Replace:

```kotlin
        val running = runner.running.value
        return when {
            running == null || !running.host.isProjector -> Optional.empty()
            pauseDrill() -> Optional.empty()
            else -> runner.stopProjectorExercise()
```

with:

```kotlin
        val running = runner.running.value
        return when {
            running == null -> Optional.empty()
            // A camera drill that runs everywhere uses the arena's targets too: it pauses if it can, and runs on if not
            !running.host.isProjector -> {
                if (running.host.runsEverywhere) pauseDrill()
                Optional.empty()
            }
            pauseDrill() -> Optional.empty()
            else -> runner.stopProjectorExercise()
```

Replace:

```kotlin
    // What calibration does to the running drill (spec §8 Revision 2, decision 2): a projector drill is paused,
    // and stays paused afterwards, never restarted; one with no Pause button is stopped and started afresh
    // as calibration ends, as before. A camera drill doesn't use the arena and is left alone.
    private val drillForCalibration = CalibrationFlow.Exercises { pauseOrStopProjectorDrill() }

```

with:

```kotlin
    // What calibration does to the running drill (spec §8 Revision 2, decision 2): a projector drill is paused,
    // and stays paused afterwards, never restarted; one with no Pause button is stopped and started afresh
    // as calibration ends, as before. A camera drill runs everywhere (exercise port spec §5), on the arena's
    // targets too: it is paused if it has a Pause button, and otherwise left alone.
    private val drillForCalibration = CalibrationFlow.Exercises { pauseOrStopProjectorDrill() }

```

Replace:

```kotlin
            ArenaHostSurface(arenaState.value ?: return null)
        } else {
            FeedHostSurface(feedTargets, displaySize)
        }
        return ComposeExerciseHost(
```

with:

```kotlin
            ArenaHostSurface(arenaState.value ?: return null)
        } else {
            // Exercise port spec §5: a camera exercise runs everywhere, on the shooter's arena targets too
            FeedHostSurface(feedTargets, displaySize, arenaLayout.targets)
        }
        return ComposeExerciseHost(
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`:

Replace:

```kotlin
import com.shootoff.compose.shots.Marker
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.Settings
```

with:

```kotlin
import com.shootoff.compose.shots.Marker
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.Settings
```

Replace:

```kotlin
    private val drill = context.drill
    private val targets = context.surface.targets
    private val nextId = AtomicLong()

```

with:

```kotlin
    private val drill = context.drill
    private val targets = context.surface.targets
    private val seenTargets = context.surface.seenTargets
    private val nextId = AtomicLong()

```

Replace:

```kotlin
    private val savedBackground = SavedBackground<ArenaBackground>()

    // The exercise hears about targets joining and leaving its surface
    private val targetListener = object : TargetSetListener {
        override fun targetAdded(target: PlacedTarget) = targetsChanged()
```

with:

```kotlin
    private val savedBackground = SavedBackground<ArenaBackground>()

    // The exercise hears about targets joining and leaving the surfaces it sees
    private val targetListener = object : TargetSetListener {
        override fun targetAdded(target: PlacedTarget) = targetsChanged()
```

Replace:

```kotlin
    val shotDetectionPaused: Boolean get() = support.isShotDetectionPaused

    // ---- Lifecycle, driven by the ExerciseRunner

    fun start() {
        drill.setName(name)
        targets.set.addListener(targetListener)
        support.run(guarded { exercise.start(this) })
    }
```

with:

```kotlin
    val shotDetectionPaused: Boolean get() = support.isShotDetectionPaused

    /** Whether the exercise takes arena shots: a projector exercise, or a camera exercise that runs everywhere */
    val takesArenaShots: Boolean get() = context.surface.takesArenaShots

    /** A camera exercise that runs everywhere: on the camera feed and on the arena's targets */
    val runsEverywhere: Boolean get() = takesArenaShots && !isProjector

    // ---- Lifecycle, driven by the ExerciseRunner

    fun start() {
        drill.setName(name)
        seenTargets.forEach { it.set.addListener(targetListener) }
        support.run(guarded { exercise.start(this) })
    }
```

Replace:

```kotlin
    fun stop() {
        val stopped = support.stop().orElse(null) ?: return
        targets.set.removeListener(targetListener)

        for (target in stopped.addedTargets()) targets.remove(target)
```

with:

```kotlin
    fun stop() {
        val stopped = support.stop().orElse(null) ?: return
        seenTargets.forEach { it.set.removeListener(targetListener) }

        for (target in stopped.addedTargets()) targets.remove(target)
```

Replace:

```kotlin
        context.surface.placeNewTarget(target)

        if (support.track(target.id)) return Optional.of(Handle(target))

        // Stopped while loading
```

with:

```kotlin
        context.surface.placeNewTarget(target)

        if (support.track(target.id)) return Optional.of(Handle(target, targets))

        // Stopped while loading
```

Replace:

```kotlin
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

```

with:

```kotlin
    }

    /** The camera feed's targets, then (for an exercise that runs everywhere) the arena's */
    override fun targets(): List<TargetHandle> = seenTargets.flatMap { surface -> surface.set.targets.map { Handle(it, surface) } }

    // A target on one of the surfaces the exercise sees
    private inner class Handle(private val target: PlacedTarget, private val surface: SurfaceTargets) : TargetHandle {
        override fun id(): TargetId = target.id

        override fun move(x: Double, y: Double) = surface.set.move(target.id, x, y)

        override fun resize(width: Double, height: Double) = surface.set.resize(target.id, width, height)

        override fun setVisible(visible: Boolean) = surface.set.setVisible(target.id, visible)

        override fun remove() {
            support.untrack(target.id)
            surface.remove(target.id)
        }

```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt`:

Replace:

```kotlin
/**
 * The Compose app's current exercise: at most one runs. Starting one stops the one before. Shots reach
 * it by surface: arena shots only a projector exercise, camera shots only a camera exercise. Calibration
 * pauses a projector exercise (see AppState.pauseDrill); one with no Pause button is stopped through
 * [stopProjectorExercise] and started afresh afterwards.
```

with:

```kotlin
/**
 * The Compose app's current exercise: at most one runs. Starting one stops the one before. Shots reach
 * it by surface: a projector exercise takes arena shots only; a camera exercise takes camera shots, and
 * arena shots too when it runs everywhere (exercise port spec §5). A camera shot that the pipeline passes
 * on to the arena arrives once, as an arena shot. Calibration
 * pauses a projector exercise (see AppState.pauseDrill); one with no Pause button is stopped through
 * [stopProjectorExercise] and started afresh afterwards.
```

Replace:

```kotlin
    override fun deliver(shot: Shot, hit: Hit?, arenaShot: Boolean): Boolean {
        val host = runningState.value?.host ?: return false
        if (host.isProjector != arenaShot) return true
        host.deliverShot(shot, hit)
        return true
```

with:

```kotlin
    override fun deliver(shot: Shot, hit: Hit?, arenaShot: Boolean): Boolean {
        val host = runningState.value?.host ?: return false
        val takes = if (arenaShot) host.takesArenaShots else !host.isProjector
        if (!takes) return true
        host.deliverShot(shot, hit)
        return true
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseTexts.kt`:

Replace:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
```

with:

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
```

Replace:

```kotlin
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
```

with:

```kotlin
 * The running exercise's texts and markers on its surface, at the surface's scale: a text's top left
 * is at its (x, y), in its font size and colors.
 *
 * @param showMarkers false to draw the texts only
 */
@Composable
fun ExerciseOverlay(drill: DrillState, transform: SurfaceTransform, modifier: Modifier = Modifier, showMarkers: Boolean = true) {
    val texts by drill.texts.collectAsState()
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        if (showMarkers) MarkerLayer(drill.markers, transform)
        for (text in texts) {
            val topLeft = transform.toView(text.x, text.y)
```

Replace:

```kotlin
    }
}
```

with:

```kotlin
    }
}

/**
 * What the running exercise draws on the arena. A projector exercise: its texts and markers. A camera
 * exercise that runs everywhere (exercise port spec §5): its texts, at the same (x, y) as on the camera
 * feed, and its banner message at the top left, white, as the JavaFX app showed it; its markers stay on the
 * camera feed, whose coordinates they are in.
 */
@Composable
fun ArenaExerciseOverlay(drill: DrillState, projector: Boolean, everywhere: Boolean, transform: SurfaceTransform) {
    when {
        projector -> ExerciseOverlay(drill, transform)
        everywhere -> {
            ExerciseOverlay(drill, transform, showMarkers = false)
            ArenaMessage(drill, transform)
        }
    }
}

@Composable
private fun ArenaMessage(drill: DrillState, transform: SurfaceTransform) {
    val message by drill.message.collectAsState()
    val density = LocalDensity.current
    val shown = message?.takeIf { it.isNotEmpty() } ?: return
    val topLeft = transform.toView(MESSAGE_INSET, MESSAGE_INSET)
    Text(
        shown,
        color = Color.White,
        fontSize = with(density) { (MESSAGE_FONT_SIZE * transform.scale).toFloat().toSp() },
        lineHeight = with(density) { (MESSAGE_FONT_SIZE * transform.scale * 1.2).toFloat().toSp() },
        modifier = Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .testTag("arena-exercise-message"),
    )
}

// In arena coordinates
private const val MESSAGE_INSET = 10.0
private const val MESSAGE_FONT_SIZE = 24.0
```

`compose-app/src/main/kotlin/com/shootoff/compose/drill/HostSurface.kt`:

Replace:

```kotlin
 */
interface HostSurface {
    val targets: SurfaceTargets

    val isProjector: Boolean

    fun size(): Size
```

with:

```kotlin
 */
interface HostSurface {
    /** Where the exercise's own targets go */
    val targets: SurfaceTargets

    val isProjector: Boolean

    /**
     * The targets the exercise sees and hears about: its surface's, and for a camera exercise that runs
     * everywhere, the arena's too
     */
    val seenTargets: List<SurfaceTargets> get() = listOf(targets)

    /** Whether the exercise takes shots on the arena (in arena coordinates) */
    val takesArenaShots: Boolean get() = isProjector

    fun size(): Size
```

Replace:

```kotlin
}

/** A camera feed: its targets on the feed's canvas, which is the display size */
class FeedHostSurface(override val targets: SurfaceTargets, private val displaySize: Size) : HostSurface {
    override val isProjector: Boolean get() = false

    override fun size(): Size = displaySize
```

with:

```kotlin
}

/**
 * A camera feed: its targets on the feed's canvas, which is the display size.
 *
 * @param arenaTargets for an exercise that runs everywhere (exercise port spec §5): the shooter's arena
 *   targets, which the exercise sees beside the feed's, and whose shots it takes too
 */
class FeedHostSurface(
    override val targets: SurfaceTargets,
    private val displaySize: Size,
    private val arenaTargets: SurfaceTargets? = null,
) : HostSurface {
    override val isProjector: Boolean get() = false

    override val seenTargets: List<SurfaceTargets> get() = listOfNotNull(targets, arenaTargets)

    override val takesArenaShots: Boolean get() = arenaTargets != null

    override fun size(): Size = displaySize
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :compose-app:test --console=plain`
Expected: PASS (all of `compose-app`, since the runner and the host are everyone's).

- [ ] **Step 5: The gate**

Run the gate. Expected: `979/979 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/Main.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseRunner.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/ExerciseTexts.kt compose-app/src/main/kotlin/com/shootoff/compose/drill/HostSurface.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationPausesTheDrill.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestDrillPanel.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestEverywhereMode.kt compose-app/src/test/kotlin/com/shootoff/compose/shots/SurfaceFixture.kt
git commit -m "Run camera drills in the Compose app on the arena's targets and shots too"
git log -1 --format=%B
```

---

### Task 4: Camera drills run everywhere in the JavaFX app

**Files:**
- Modify: `javafx-app/src/main/java/com/shootoff/gui/exercise/ExerciseHostContext.java` (the `everywhere` component and the old constructor)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java` (`deliverShot`, `takesArenaShots`, `targets()`, `resetTargets`)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java` (`everywhereCanvases`, `startHostedExercise`)
- Test: `javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHost.java`

**Interfaces:**
- Consumes: Task 2's `resetTargets`; `CamerasSupervisor.getCameraViews()`; `ProjectorSlide.getArenaPane().getCanvasManager()` (the arena window's canvas).
- Produces: `ExerciseHostContext(…, SoundOutput sounds, Optional<Supplier<List<CanvasManager>>> everywhere)` (canonical) and the old eight-argument constructor (`everywhere` empty); `JavaFxExerciseHost.takesArenaShots(): boolean`.

**Why.** Spec §5: "The JavaFX host already behaves this way for its standard exercises; it keeps doing so". Its v1 exercises did; its v2 host didn't (ruling 14), and from Task 5 the standard exercises are v2.

- [ ] **Step 1: Write the failing test**

`javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHost.java`:

Replace:

```java
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
```

with:

```java
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
```

Replace:

```java
import com.shootoff.exercise.TextStyle;
import com.shootoff.geom.Point;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.MockCanvasManager;
```

with:

```java
import com.shootoff.exercise.TextStyle;
import com.shootoff.geom.Point;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.MockCanvasManager;
```

Replace:

```java
	}

	private static LocatedImage image(String url) throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
```

with:

```java
	}

	// Exercise port spec §5: a camera exercise that runs everywhere sees every canvas's targets and takes
	// arena shots too; one that doesn't takes camera shots only
	@Test
	void cameraHostThatRunsEverywhereSeesEveryCanvasAndTakesArenaShots() throws Exception {
		final MockCanvasManager arenaCanvas = onFx(() -> new MockCanvasManager(config));
		onFx(() -> {
			canvas.addTarget(new File("targets/IPSC.target"), false);
			arenaCanvas.addTarget(new File("targets/SimpleBullseye_score.target"), false);
			return null;
		});
		final List<CanvasManager> seen = List.of(canvas, arenaCanvas);
		final JavaFxExerciseHost everywhereHost = new JavaFxExerciseHost(exercise,
				new ExerciseHostContext(config, cameras, new View(), canvas, Optional.empty(), List.of(canvas), resources,
						sounds, Optional.of(() -> seen)));

		assertEquals(List.of(canvas.getTargetSet().getTargets().get(0).getId(),
				arenaCanvas.getTargetSet().getTargets().get(0).getId()),
				everywhereHost.targets().stream().map(TargetHandle::id).toList());
		assertEquals(1, host.targets().size());

		everywhereHost.start();
		final DisplayShot cameraShot = new DisplayShot(ShotColor.RED, 1, 2, 3, 2);
		final ArenaShot arenaShot = new ArenaShot(new DisplayShot(ShotColor.RED, 4, 5, 6, 2));
		everywhereHost.deliverShot(cameraShot, Optional.empty());
		everywhereHost.deliverShot(arenaShot, Optional.empty());
		waitFor(() -> exercise.shots.size() == 2, "shots " + exercise.shots);
		everywhereHost.stop();
		assertEquals(List.of(cameraShot, arenaShot), exercise.shots);

		exercise.shots.clear();
		host.start();
		host.deliverShot(arenaShot, Optional.empty());
		host.deliverShot(cameraShot, Optional.empty());
		waitFor(() -> exercise.shots.size() == 1, "shots " + exercise.shots);
		host.stop();
		assertEquals(List.of(cameraShot), exercise.shots);
	}

	private static LocatedImage image(String url) throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew :javafx-app:compileTestJava --console=plain`
Expected: FAIL to compile: `ExerciseHostContext` has no nine-argument constructor.

- [ ] **Step 3: Implement**

`javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java`:

Replace:

```java
import java.util.Map.Entry;
import java.util.Optional;

import org.slf4j.Logger;
```

with:

```java
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
```

Replace:

```java
	}

	// Starts a fresh instance of a v2 exercise on the arena, or on the first camera's feed
	private void startHostedExercise(V2ExerciseEntry entry) {
```

with:

```java
	}

	// Every camera's canvas, then the arena window's while the arena is open (not the arena tab's copy)
	private List<CanvasManager> everywhereCanvases() {
		final List<CanvasManager> canvases = new ArrayList<>();
		for (final CameraView view : camerasSupervisor.getCameraViews()) {
			canvases.add((CanvasManager) view);
		}
		if (projectorSlide.getArenaPane() != null) canvases.add(projectorSlide.getArenaPane().getCanvasManager());
		return canvases;
	}

	// Starts a fresh instance of a v2 exercise on the arena, or on the first camera's feed
	private void startHostedExercise(V2ExerciseEntry entry) {
```

Replace:

```java
		config.setPlugin(pluginEngine.getPlugin(entry.metadata()).orElse(null));

		final HostedExercise running = new HostedExercise(entry,
				new JavaFxExerciseHost(exercise, new ExerciseHostContext(config, camerasSupervisor, this, canvas, arena,
						feeds, entry.exerciseClass().getClassLoader(), SoundOutput.speakers())));
		config.setExercise(running);
		running.init();
```

with:

```java
		config.setPlugin(pluginEngine.getPlugin(entry.metadata()).orElse(null));

		// Exercise port spec §5: a camera exercise runs everywhere, on every camera's targets and the arena's,
		// as the v1 exercises did
		final Optional<Supplier<List<CanvasManager>>> everywhere = projector ? Optional.empty()
				: Optional.of(this::everywhereCanvases);

		final HostedExercise running = new HostedExercise(entry,
				new JavaFxExerciseHost(exercise, new ExerciseHostContext(config, camerasSupervisor, this, canvas, arena,
						feeds, entry.exerciseClass().getClassLoader(), SoundOutput.speakers(), everywhere)));
		config.setExercise(running);
		running.init();
```

`javafx-app/src/main/java/com/shootoff/gui/exercise/ExerciseHostContext.java`:

Replace:

```java
import java.util.List;
import java.util.Optional;

import com.shootoff.camera.CamerasSupervisor;
```

with:

```java
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.shootoff.camera.CamerasSupervisor;
```

Replace:

```java
 * @param resources
 *            the exercise's class loader; for a plugin, its jar's
 */
public record ExerciseHostContext(Configuration config, CamerasSupervisor cameras, TrainingExerciseView view,
		CanvasManager canvas, Optional<ProjectorArenaPane> arena, List<CanvasManager> feeds, ClassLoader resources,
		SoundOutput sounds) {
	public ExerciseHostContext {
		feeds = List.copyOf(feeds);
	}
}
```

with:

```java
 * @param resources
 *            the exercise's class loader; for a plugin, its jar's
 * @param everywhere
 *            for a camera exercise that runs everywhere (exercise port spec §5): the canvases whose targets
 *            it sees, as they are when it asks (every camera's, and the arena window's while the arena is
 *            open). It takes arena shots too.
 */
public record ExerciseHostContext(Configuration config, CamerasSupervisor cameras, TrainingExerciseView view,
		CanvasManager canvas, Optional<ProjectorArenaPane> arena, List<CanvasManager> feeds, ClassLoader resources,
		SoundOutput sounds, Optional<Supplier<List<CanvasManager>>> everywhere) {
	public ExerciseHostContext {
		feeds = List.copyOf(feeds);
	}

	/**
	 * A context for an exercise that sees only its own canvas's targets and shots.
	 */
	public ExerciseHostContext(Configuration config, CamerasSupervisor cameras, TrainingExerciseView view,
			CanvasManager canvas, Optional<ProjectorArenaPane> arena, List<CanvasManager> feeds, ClassLoader resources,
			SoundOutput sounds) {
		this(config, cameras, view, canvas, arena, feeds, resources, sounds, Optional.empty());
	}
}
```

`javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java`:

Replace:

```java
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
```

with:

```java
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
```

Replace:

```java

	/**
	 * Hands a shot to the exercise: arena shots to a projector exercise, camera shots to a camera
	 * exercise.
	 */
	public void deliverShot(Shot shot, Optional<Hit> hit) {
		if (isProjector() != (shot instanceof ArenaShot)) return;

		support.deliverShot(shot, hit);
```

with:

```java

	/**
	 * Hands a shot to the exercise: arena shots to a projector exercise; camera shots to a camera
	 * exercise, and arena shots too when it runs everywhere (exercise port spec §5). A camera shot the
	 * pipeline passes on to the arena arrives once, as an arena shot.
	 */
	public void deliverShot(Shot shot, Optional<Hit> hit) {
		final boolean takes = shot instanceof ArenaShot ? takesArenaShots() : !isProjector();
		if (!takes) return;

		support.deliverShot(shot, hit);
```

Replace:

```java
	}

	@Override
	public List<TargetHandle> targets() {
		final List<TargetHandle> handles = new ArrayList<>();
		for (final Target target : new ArrayList<>(context.canvas().getTargets())) {
			handles.add(new FxTargetHandle((TargetView) target));
		}
		return handles;
```

with:

```java
	}

	/**
	 * @return whether the exercise takes arena shots: a projector exercise, or a camera exercise that runs
	 *         everywhere
	 */
	public boolean takesArenaShots() {
		return isProjector() || context.everywhere().isPresent();
	}

	/**
	 * @return the targets on the exercise's canvas; for a camera exercise that runs everywhere, every
	 *         camera's and then the arena's
	 */
	@Override
	public List<TargetHandle> targets() {
		final List<CanvasManager> canvases = context.everywhere().map(Supplier::get).orElse(List.of(context.canvas()));
		final List<TargetHandle> handles = new ArrayList<>();
		for (final CanvasManager canvas : canvases) {
			for (final Target target : new ArrayList<>(canvas.getTargets())) {
				handles.add(new FxTargetHandle((TargetView) target));
			}
		}
		return handles;
```

Replace:

```java
				if (view instanceof CanvasManager canvas) canvases.add(canvas);
			}
			canvases.forEach(CanvasManager::reset);

```

with:

```java
				if (view instanceof CanvasManager canvas) canvases.add(canvas);
			}
			context.everywhere().ifPresent(everywhere -> canvases.addAll(everywhere.get()));
			canvases.forEach(CanvasManager::reset);

```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :javafx-app:test --tests '*ExerciseHost*' --tests '*HostedExercise*' --console=plain`
Expected: PASS.

- [ ] **Step 5: The gate**

Run the gate. Expected: `980/980 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java javafx-app/src/main/java/com/shootoff/gui/exercise/ExerciseHostContext.java javafx-app/src/main/java/com/shootoff/gui/exercise/JavaFxExerciseHost.java javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHost.java
git commit -m "Run camera drills in the JavaFX app on every canvas's targets and shots"
git log -1 --format=%B
```

---

### Task 5: The `builtin-exercises` module in both apps, and Shoot for Score

**Files:**
- Create: `builtin-exercises/build.gradle.kts`
- Create: `builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`, `…/ShootForScore.java`
- Modify: `settings.gradle.kts`, `javafx-app/build.gradle.kts`, `compose-app/build.gradle.kts`
- Modify: `javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/ExerciseCatalog.kt` (`pluginEngine`), `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`
- Delete: `javafx-app/src/main/java/com/shootoff/plugins/ShootForScore.java`, `javafx-app/src/test/java/com/shootoff/plugins/TestShootForScore.java`
- Test: `builtin-exercises/src/test/java/com/shootoff/plugins/TestShootForScore.java` (moved, JUnit 4), `…/TestBuiltInRegistry.java` (new); `javafx-app/src/test/java/com/shootoff/{plugins/TestBuiltInExercises, gui/pane/TestExerciseSlide, gui/exercise/TestHostedExercise}.java`; `compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`

**Interfaces:**
- Consumes: `V2ExerciseEntry(Class<? extends Exercise>, ExerciseMetadata)`; `PluginEngine(PluginListener, List<ExerciseLoader>, List<ExerciseEntry>)`; `Hit.region().tag(String)`; `ExerciseHost.addColumn`, `setColumnValue`, `showMessage`.
- Produces:
  - `com.shootoff.plugins.BuiltInRegistry.entries(): List<V2ExerciseEntry>` (Tasks 6–8 add to its private `EXERCISES` list).
  - `com.shootoff.plugins.ShootForScore` (v2), with `static String scoreMessage(int redScore, int greenScore)` (Task 7's Par for Score uses it) and package-private `getRedScore()`, `getGreenScore()`.
  - Compose: `fun pluginEngine(catalog: ExerciseCatalog): PluginEngine` in `ExerciseCatalog.kt`.
  - `BuiltInExercises.entries()` = the registry's entries, then the legacy ones (Tasks 6–8 remove those they port).

**Why.** Spec §5 "Module builtin-exercises" and "Removal"; §4 row 1. Rulings 15–17, 33–34.

- [ ] **Step 1: Remove the JavaFX class and its test**

```bash
git rm javafx-app/src/main/java/com/shootoff/plugins/ShootForScore.java javafx-app/src/test/java/com/shootoff/plugins/TestShootForScore.java
```

- [ ] **Step 2: Write the failing tests**

Create `builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java`:

```java
package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shootoff.JavaFxReferenceScanner;
import com.shootoff.plugins.engine.V2ExerciseEntry;

class TestBuiltInRegistry {
	@Test
	void theStandardExercisesAreListedInTheTrainingMenusOrder() {
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("Shoot for Score"), entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false), entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}

	@Test
	void eachRunIsAFreshInstanceThatDescribesItselfAsListed() throws Exception {
		for (final V2ExerciseEntry entry : BuiltInRegistry.entries()) {
			assertNotSame(entry.newInstance(), entry.newInstance());
			assertEquals(entry.metadata(), entry.newInstance().metadata());
			assertFalse(entry.metadata().getDescription().isEmpty());
		}
	}

	@Test
	void theBuiltInExercisesDoNotReferenceJavaFx() throws Exception {
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(BuiltInRegistry.class));
	}
}
```

Create `builtin-exercises/src/test/java/com/shootoff/plugins/TestShootForScore.java`:

```java
package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;

// JUnit 4, as in the JavaFX app: the test names stay those of the Java 8 baseline
public class TestShootForScore {
	@Rule public TemporaryFolder temp = new TemporaryFolder();

	private FakeExerciseHost host;
	private ShootForScore sfs;
	private Hit tenRegionHit;
	private Hit fiveRegionHit;

	@Before
	public void setUp() throws IOException, TargetFormatException {
		final TargetDefinition bullseye = TargetDefinitions.load(Paths.get("targets", "SimpleBullseye_score.target"));
		for (final Region region : bullseye.regions()) {
			if (region.tag("points").equals(Optional.of("10"))) {
				tenRegionHit = new Hit(new TargetId(1), region, new Point(0, 0));
			} else if (region.tag("points").equals(Optional.of("5"))) {
				fiveRegionHit = new Hit(new TargetId(1), region, new Point(0, 0));
			}
		}

		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.newFolder("data").toPath());
		sfs = new ShootForScore();
		host.start(sfs);
	}

	private void shoot(ShotColor color, Optional<Hit> hit) {
		host.shoot(new Shot(color, 0, 0, 0, 2), hit);
	}

	// The messages shown since the last call
	private int seen = 0;

	private List<String> newMessages() {
		final List<String> messages = host.messages();
		final List<String> shown = messages.subList(seen, messages.size());
		seen = messages.size();
		return List.copyOf(shown);
	}

	@Test
	public void testReset() {
		host.reset();
		assertEquals(List.of("score: 0"), newMessages());
	}

	@Test
	public void testJustRed() {
		assertEquals(List.of("Score"), host.columns());

		// Miss
		shoot(ShotColor.RED, Optional.empty());
		assertEquals(List.of(), newMessages());

		// Hit ten
		shoot(ShotColor.RED, Optional.of(tenRegionHit));
		assertEquals(List.of("red score: 10"), newMessages());
		assertEquals(Map.of("Score", "10"), host.rows().get(1).values());

		// Hit five
		shoot(ShotColor.RED, Optional.of(fiveRegionHit));
		assertEquals(List.of("red score: 15"), newMessages());

		assertEquals(15, sfs.getRedScore());
		assertEquals(0, sfs.getGreenScore());

		host.reset();
		assertEquals(List.of("score: 0"), newMessages());

		assertEquals(0, sfs.getRedScore());
		assertEquals(0, sfs.getGreenScore());
	}

	@Test
	public void testJustGreen() {
		// Miss
		shoot(ShotColor.GREEN, Optional.empty());
		assertEquals(List.of(), newMessages());

		// Hit ten
		shoot(ShotColor.GREEN, Optional.of(tenRegionHit));
		assertEquals(List.of("green score: 10"), newMessages());

		// Hit five
		shoot(ShotColor.GREEN, Optional.of(fiveRegionHit));
		assertEquals(List.of("green score: 15"), newMessages());

		assertEquals(0, sfs.getRedScore());
		assertEquals(15, sfs.getGreenScore());

		host.reset();
		assertEquals(List.of("score: 0"), newMessages());

		assertEquals(0, sfs.getRedScore());
		assertEquals(0, sfs.getGreenScore());
	}

	@Test
	public void testRedAndGreen() {
		// Red hit ten
		shoot(ShotColor.RED, Optional.of(tenRegionHit));
		assertEquals(List.of("red score: 10"), newMessages());

		// Green hit five
		shoot(ShotColor.GREEN, Optional.of(fiveRegionHit));
		assertEquals(List.of("red score: 10\ngreen score: 5"), newMessages());

		assertEquals(10, sfs.getRedScore());
		assertEquals(5, sfs.getGreenScore());
	}
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

Replace:

```kotlin
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
```

with:

```kotlin
import com.shootoff.exercise.Exercise
import com.shootoff.exercise.ExerciseHost
import com.shootoff.plugins.BuiltInRegistry
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
```

Replace:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

```

with:

```kotlin
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Optional

```

Replace:

```kotlin
    fun theCatalogListsTheV2DrillsByName() {
        assertEquals(listOf("Feed drill", "Projector drill"), app.catalog.entries.value.map { it.metadata().name })
    }

```

with:

```kotlin
    fun theCatalogListsTheV2DrillsByName() {
        assertEquals(listOf("Feed drill", "Projector drill"), app.catalog.entries.value.map { it.metadata().name })
    }

    // Exercise port spec §5: the Drills screen lists the exercises that ship with ShootOFF beside the plugins
    @Test
    fun theDrillsScreenListsTheBuiltInExercises(@TempDir plugins: Path) {
        val previous = System.getProperty("shootoff.plugins")
        System.setProperty("shootoff.plugins", plugins.toString())
        try {
            val catalog = ExerciseCatalog()
            pluginEngine(catalog)

            assertEquals(BuiltInRegistry.entries().map { it.metadata().name }.sorted(), catalog.entries.value.map { it.metadata().name })
        } finally {
            if (previous == null) System.clearProperty("shootoff.plugins") else System.setProperty("shootoff.plugins", previous)
        }
    }

```

`javafx-app/src/test/java/com/shootoff/gui/exercise/TestHostedExercise.java`:

Replace:

```java
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.ShootForScore;
import com.shootoff.plugins.SteelChallenge;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.targets.model.Hit;

class TestHostedExercise {
	public static final class Drill implements Exercise {
		private final boolean projectorOnly;
```

with:

```java
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.SteelChallenge;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.targets.Target;
import com.shootoff.targets.model.Hit;

class TestHostedExercise {
	// A v1 exercise for the camera feed
	private static final class V1FeedDrill implements TrainingExercise {
		@Override
		public void init() {}

		@Override
		public void targetUpdate(Target target, TargetChange change) {}

		@Override
		public ExerciseMetadata getInfo() {
			return new ExerciseMetadata("V1 drill", "1.0", "ShootOFF tests", "A v1 drill");
		}

		@Override
		public void shotListener(Shot shot, Optional<com.shootoff.targets.Hit> hit) {}

		@Override
		public void reset(List<Target> targets) {}

		@Override
		public void destroy() {}
	}

	public static final class Drill implements Exercise {
		private final boolean projectorOnly;
```

Replace:

```java
		assertFalse(HostedExercise.isProjectorExercise(feedItem));
		assertTrue(HostedExercise.isProjectorExercise(new SteelChallenge()));
		assertFalse(HostedExercise.isProjectorExercise(new ShootForScore()));

		// A menu item has no host: its callbacks do nothing
```

with:

```java
		assertFalse(HostedExercise.isProjectorExercise(feedItem));
		assertTrue(HostedExercise.isProjectorExercise(new SteelChallenge()));
		assertFalse(HostedExercise.isProjectorExercise(new V1FeedDrill()));

		// A menu item has no host: its callbacks do nothing
```

`javafx-app/src/test/java/com/shootoff/gui/pane/TestExerciseSlide.java`:

Replace:

```java
	@Test
	public void v2ExercisesAreListedWithV1Ones() {
		slide.registerExercise(new LegacyExerciseEntry(new ShootForScore(), false));
		slide.registerProjectorExercise(new V2ExerciseEntry(V2Drill.class, new V2Drill().metadata()));

		assertTrue(menuNames().containsAll(List.of("Shoot for Score", "V2 Drill")));
	}
}
```

with:

```java
	@Test
	public void v2ExercisesAreListedWithV1Ones() {
		slide.registerProjectorExercise(new LegacyExerciseEntry(new SteelChallenge(), true));
		slide.registerExercise(new V2ExerciseEntry(ShootForScore.class, new ShootForScore().metadata()));
		slide.registerProjectorExercise(new V2ExerciseEntry(V2Drill.class, new V2Drill().metadata()));

		assertTrue(menuNames().containsAll(List.of("Steel Challenge", "Shoot for Score", "V2 Drill")));
	}
}
```

`javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`:

Replace:

```java

import static org.junit.Assert.assertEquals;

import java.util.List;
```

with:

```java

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
```

Replace:

```java
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.plugins.engine.ExerciseEntry;

public class TestBuiltInExercises {
```

with:

```java
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.plugins.engine.ExerciseEntry;
import com.shootoff.plugins.engine.LegacyExerciseEntry;

public class TestBuiltInExercises {
```

Replace:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("ISSFStandardPistol", "RandomShoot", "ShootForScore", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
	}
}
```

with:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("ShootForScore", "ISSFStandardPistol", "RandomShoot", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 1));
		assertTrue(entries.subList(1, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :builtin-exercises:compileTestJava --console=plain`
Expected: FAIL: there is no `builtin-exercises` project yet. (With only Step 4's `settings.gradle.kts` and `builtin-exercises/build.gradle.kts` in place, it fails to compile instead: no `ShootForScore`, no `BuiltInRegistry`.)

- [ ] **Step 4: Implement**

Create `builtin-exercises/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

// The exercises that ship with ShootOFF, on the v2 exercise API. Both apps list them (exercise port spec §5).
dependencies {
    api(project(":plugin-api"))
    // FakeExerciseHost, which the exercises' tests run them on
    testImplementation(testFixtures(project(":plugin-api")))
    // JavaFxReferenceScanner for the boundary test
    testImplementation(testFixtures(project(":core")))
}
```

Create `builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`:

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

package com.shootoff.plugins;

import java.util.List;

import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.engine.V2ExerciseEntry;

/**
 * The exercises that ship with ShootOFF, in the Training menu's order. Both apps list them.
 */
public final class BuiltInRegistry {
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(ShootForScore.class);

	public static List<V2ExerciseEntry> entries() {
		return EXERCISES.stream().map(BuiltInRegistry::entry).toList();
	}

	// A throwaway instance tells the exercise's name and whether it runs only on the projector
	private static V2ExerciseEntry entry(Class<? extends Exercise> exerciseClass) {
		try {
			return new V2ExerciseEntry(exerciseClass, exerciseClass.getDeclaredConstructor().newInstance().metadata());
		} catch (final ReflectiveOperationException e) {
			throw new IllegalStateException("Can't make a " + exerciseClass.getSimpleName(), e);
		}
	}
}
```

Create `builtin-exercises/src/main/java/com/shootoff/plugins/ShootForScore.java`:

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

package com.shootoff.plugins;

import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.targets.model.Hit;

/**
 * Adds up the points of the regions each laser color hits. It runs everywhere: on the camera feed's
 * targets and the arena's.
 */
public class ShootForScore implements Exercise {
	private static final String POINTS_COL_NAME = "Score";

	private ExerciseHost host;
	private int redScore = 0;
	private int greenScore = 0;

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("Shoot for Score", "1.0", "phrack",
				"This exercise works with targets that have score tags "
						+ "assigned to regions. Any time a target region is hit, "
						+ "the number of points assigned to that region are added " + "to your total score.");
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		host.addColumn(POINTS_COL_NAME);
	}

	/**
	 * @return red's score, for tests
	 */
	int getRedScore() {
		return redScore;
	}

	/**
	 * @return green's score, for tests
	 */
	int getGreenScore() {
		return greenScore;
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		if (hit.isEmpty()) return;

		final Optional<String> points = hit.get().region().tag("points");
		if (points.isPresent()) {
			host.setColumnValue(POINTS_COL_NAME, points.get());

			if (shot.getColor().equals(ShotColor.RED) || shot.getColor().equals(ShotColor.INFRARED)) {
				redScore += Integer.parseInt(points.get());
			} else if (shot.getColor().equals(ShotColor.GREEN)) {
				greenScore += Integer.parseInt(points.get());
			}
		}

		host.showMessage(scoreMessage(redScore, greenScore));
	}

	/**
	 * @return the scores as the JavaFX app showed them: each color that has scored, else "score: 0"
	 */
	static String scoreMessage(int redScore, int greenScore) {
		if (redScore > 0 && greenScore > 0) {
			return String.format("red score: %d\ngreen score: %d", redScore, greenScore);
		} else if (redScore > 0) {
			return String.format("red score: %d", redScore);
		} else if (greenScore > 0) {
			return String.format("green score: %d", greenScore);
		}

		return "score: 0";
	}

	@Override
	public void onReset() {
		redScore = 0;
		greenScore = 0;
		host.showMessage("score: 0");
	}

	@Override
	public void stop() {}
}
```

`compose-app/build.gradle.kts`:

Replace:

```kotlin
    implementation(project(":core"))
    implementation(project(":plugin-api"))

    // Linux x64 only, like core's native libraries
```

with:

```kotlin
    implementation(project(":core"))
    implementation(project(":plugin-api"))
    // The exercises that ship with ShootOFF, which the JavaFX app lists too
    implementation(project(":builtin-exercises"))

    // Linux x64 only, like core's native libraries
```

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`:

Replace:

```kotlin
import com.shootoff.compose.app.WindowBounds
import com.shootoff.compose.app.handleKey
import com.shootoff.compose.app.ShootOffApp
import com.shootoff.compose.app.UiErrors
```

with:

```kotlin
import com.shootoff.compose.app.WindowBounds
import com.shootoff.compose.app.handleKey
import com.shootoff.compose.app.pluginEngine
import com.shootoff.compose.app.ShootOffApp
import com.shootoff.compose.app.UiErrors
```

Replace:

```kotlin
import com.shootoff.geom.Point
import com.shootoff.plugins.TextToSpeech
import com.shootoff.plugins.engine.PluginEngine
import com.shootoff.plugins.engine.V2ExerciseLoader
import com.shootoff.util.TimerPool
import org.bytedeco.javacpp.Loader
```

with:

```kotlin
import com.shootoff.geom.Point
import com.shootoff.plugins.TextToSpeech
import com.shootoff.util.TimerPool
import org.bytedeco.javacpp.Loader
```

Replace:

```kotlin

    val catalog = ExerciseCatalog()
    val plugins = PluginEngine(catalog, listOf(V2ExerciseLoader()), emptyList())
    plugins.startWatching()

```

with:

```kotlin

    val catalog = ExerciseCatalog()
    val plugins = pluginEngine(catalog)
    plugins.startWatching()

```

`compose-app/src/main/kotlin/com/shootoff/compose/app/ExerciseCatalog.kt`:

Replace:

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
```

with:

```kotlin
package com.shootoff.compose.app

import com.shootoff.plugins.BuiltInRegistry
import com.shootoff.plugins.engine.ExerciseEntry
import com.shootoff.plugins.engine.PluginEngine
import com.shootoff.plugins.engine.PluginListener
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.plugins.engine.V2ExerciseLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The plugin engine that fills [catalog]: the exercises that ship with ShootOFF (exercise port spec §5), and
 * the v2 plugin jars in the shootoff.plugins folder.
 */
fun pluginEngine(catalog: ExerciseCatalog): PluginEngine {
    val builtIns: List<ExerciseEntry> = BuiltInRegistry.entries()
    return PluginEngine(catalog, listOf(V2ExerciseLoader()), builtIns)
}

/**
```

`javafx-app/build.gradle.kts`:

Replace:

```kotlin
    api(project(":core"))
    api(project(":plugin-api"))
    testImplementation(testFixtures(project(":core")))
    // ExerciseHostContract, which JavaFxExerciseHost must pass
```

with:

```kotlin
    api(project(":core"))
    api(project(":plugin-api"))
    // The exercises that ship with ShootOFF, which the Compose app lists too
    implementation(project(":builtin-exercises"))
    testImplementation(testFixtures(project(":core")))
    // ExerciseHostContract, which JavaFxExerciseHost must pass
```

`javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`:

Replace:

```java
package com.shootoff.plugins;

import java.util.List;

```

with:

```java
package com.shootoff.plugins;

import java.util.ArrayList;
import java.util.List;

```

Replace:

```java

/**
 * The exercises that ship with the JavaFX app, in Training menu order.
 */
public final class BuiltInExercises {
```

with:

```java

/**
 * The exercises that ship with the JavaFX app: the ones ported to the v2 exercise API, which the Compose
 * app lists too ({@link BuiltInRegistry}), then the JavaFX ones still to port, in Training menu order.
 */
public final class BuiltInExercises {
```

Replace:

```java

	public static List<ExerciseEntry> entries() {
		return List.of(standard(new ISSFStandardPistol()), standard(new RandomShoot()), standard(new ShootForScore()),
				standard(new TimedHolsterDrill()), standard(new ParForScore()), standard(new ParRandomShot()),
				projector(new BouncingTargets()), projector(new DuelingTree()), projector(new ShootDontShoot()),
				projector(new SteelChallenge()));
	}

```

with:

```java

	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(standard(new ISSFStandardPistol()), standard(new RandomShoot()),
				standard(new TimedHolsterDrill()), standard(new ParForScore()), standard(new ParRandomShot()),
				projector(new BouncingTargets()), projector(new DuelingTree()), projector(new ShootDontShoot()),
				projector(new SteelChallenge())));
		return entries;
	}

```

`settings.gradle.kts`:

Replace:

```kotlin
rootProject.name = "shootoff"

include("core", "plugin-api", "javafx-app", "compose-app")
```

with:

```kotlin
rootProject.name = "shootoff"

include("core", "plugin-api", "builtin-exercises", "javafx-app", "compose-app")
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :builtin-exercises:test :javafx-app:test --tests '*BuiltInExercises*' --tests '*ExerciseSlide*' --tests '*HostedExercise*' :compose-app:test --tests '*TestAppState*' --console=plain`
Expected: PASS.

- [ ] **Step 6: The gate, and the moved tests**

Run the gate. Expected: `984/984 passing; 0 regressions; 0 new failures`. Then:

Run: `python3 scripts/test_summary.py summarize builtin-exercises/build/test-results/test | command grep TestShootForScore`
Expected: exactly these four lines, the baseline's names (ruling 33):

```
PASS com.shootoff.plugins.TestShootForScore.testJustGreen
PASS com.shootoff.plugins.TestShootForScore.testJustRed
PASS com.shootoff.plugins.TestShootForScore.testRedAndGreen
PASS com.shootoff.plugins.TestShootForScore.testReset
```

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts builtin-exercises/build.gradle.kts builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java builtin-exercises/src/main/java/com/shootoff/plugins/ShootForScore.java builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java builtin-exercises/src/test/java/com/shootoff/plugins/TestShootForScore.java javafx-app/build.gradle.kts compose-app/build.gradle.kts javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java javafx-app/src/test/java/com/shootoff/gui/pane/TestExerciseSlide.java javafx-app/src/test/java/com/shootoff/gui/exercise/TestHostedExercise.java compose-app/src/main/kotlin/com/shootoff/compose/Main.kt compose-app/src/main/kotlin/com/shootoff/compose/app/ExerciseCatalog.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt
git commit -m "Port Shoot for Score to the v2 exercise API in a module both apps list"
git log -1 --format=%B
```

---

### Task 6: Random Shoot

**Files:**
- Create: `builtin-exercises/src/main/java/com/shootoff/plugins/RandomShoot.java`
- Modify: `builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`, `javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`
- Move: `javafx-app/src/test/resources/test_missing_sound_files.target` → `builtin-exercises/src/test/resources/`
- Delete: `javafx-app/src/main/java/com/shootoff/plugins/RandomShoot.java`, `javafx-app/src/test/java/com/shootoff/plugins/TestRandomShoot.java`
- Test: `builtin-exercises/src/test/java/com/shootoff/plugins/TestRandomShoot.java` (moved, JUnit 4, +2), `…/TestBuiltInRegistry.java`; `javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`

**Interfaces:**
- Consumes: Task 2's `hasSound`; `ExerciseHost.targets()`, `playSound`, `playSounds`, `say`; `Exercise.onTargetsChanged(List<TargetHandle>)`; `TargetHandle.definition().regions()`.
- Produces: `com.shootoff.plugins.RandomShoot` (v2) with package-private `RandomShoot(Random)`, `getSubtargets()`, `getCurrentSubtargets()`, and constants `WARNING_SOUND`, `SHOOT_SOUND`, `AND_SOUND` (Task 7's Par Random Shot uses `WARNING_SOUND`).

**Why.** Spec §4 row 2, G2. Rulings 17–19, 26–27, 30, 33.

- [ ] **Step 1: Remove the JavaFX class and its test, and move the test target**

```bash
git rm javafx-app/src/main/java/com/shootoff/plugins/RandomShoot.java javafx-app/src/test/java/com/shootoff/plugins/TestRandomShoot.java
mkdir -p builtin-exercises/src/test/resources
git mv javafx-app/src/test/resources/test_missing_sound_files.target builtin-exercises/src/test/resources/test_missing_sound_files.target
```

- [ ] **Step 2: Write the failing tests**

`builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java`:

Replace:

```java
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("Shoot for Score"), entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false), entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}

```

with:

```java
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("Random Shoot", "Shoot for Score"),
				entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false, false), entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}

```

Create `builtin-exercises/src/test/java/com/shootoff/plugins/TestRandomShoot.java`:

```java
package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;

// JUnit 4, as in the JavaFX app: the test names stay those of the Java 8 baseline
public class TestRandomShoot {
	@Rule public TemporaryFolder temp = new TemporaryFolder();

	private String previousHome;
	private FakeExerciseHost host;
	private Random rng;
	private int soundsSeen = 0;
	private int spokenSeen = 0;

	@Before
	public void setUp() throws IOException {
		// The voice files are ShootOFF's own, in its sounds/voice folder
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.newFolder("data").toPath());
		rng = new Random(15); // Changing this seed will cause tests to fail
	}

	@After
	public void tearDown() {
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	// The sounds played since the last call
	private List<String> newSounds() {
		final List<String> sounds = host.sounds();
		final List<String> played = List.copyOf(sounds.subList(soundsSeen, sounds.size()));
		soundsSeen = sounds.size();
		return played;
	}

	// The sentences spoken since the last call
	private List<String> newSpoken() {
		final List<String> spoken = host.spoken();
		final List<String> said = List.copyOf(spoken.subList(spokenSeen, spoken.size()));
		spokenSeen = spoken.size();
		return said;
	}

	private void shoot(Optional<Hit> hit) {
		host.shoot(new Shot(ShotColor.GREEN, 0, 0, 0, 2), hit);
	}

	private static Hit hit(TargetHandle target, String subtarget) {
		for (final Region region : target.definition().regions()) {
			if (region.tag("subtarget").equals(Optional.of(subtarget))) {
				return new Hit(target.id(), region, new Point(0, 0));
			}
		}
		throw new AssertionError("No subtarget " + subtarget);
	}

	@Test
	public void testNoTarget() {
		host.start(new RandomShoot(rng));

		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());

		host.reset();

		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());
	}

	@Test
	public void testFiveSmallTarget() {
		final TargetHandle bullseyeFiveTarget = host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);

		// Make sure initial state makes sense

		assertEquals(5, rs.getSubtargets().size());

		assertTrue(rs.getSubtargets().contains("1"));
		assertTrue(rs.getSubtargets().contains("2"));
		assertTrue(rs.getSubtargets().contains("3"));
		assertTrue(rs.getSubtargets().contains("4"));
		assertTrue(rs.getSubtargets().contains("5"));

		final String firstSubtarget = rs.getSubtargets().get(rs.getCurrentSubtargets().peek());

		assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));

		// Simulate missing a shot

		shoot(Optional.empty());

		assertEquals(List.of(RandomShoot.SHOOT_SOUND, String.format("sounds/voice/shootoff-%s.wav", firstSubtarget)),
				newSounds());

		// Simulate a hit

		final int oldSize = rs.getCurrentSubtargets().size();

		shoot(Optional.of(hit(bullseyeFiveTarget, firstSubtarget)));

		if (oldSize > 1) {
			assertEquals(oldSize - 1, rs.getCurrentSubtargets().size());
		} else {
			// The round is over and the next one is called out
			assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));
		}
	}

	@Test
	public void testNoSoundFilesForSubtargetNames() throws URISyntaxException {
		final String missingSounds = Paths
				.get(TestRandomShoot.class.getResource("/test_missing_sound_files.target").toURI()).toString();
		host.addTarget(missingSounds, 0, 0).get();
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);

		// Make sure initial state makes sense

		assertEquals(5, rs.getSubtargets().size());

		final String firstSubtarget = rs.getSubtargets().get(rs.getCurrentSubtargets().peek());

		assertEquals(List.of("shoot subtarget undefined_region_name_5 then undefined_region_name_3"), newSpoken());

		// Simulate missing a shot

		shoot(Optional.empty());

		assertEquals(List.of("shoot " + firstSubtarget), newSpoken());
	}

	// A target with subtargets arriving while none is called out starts the call-outs
	@Test
	public void aTargetWithSubtargetsArrivingStartsTheCallOuts() {
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);
		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());

		final TargetHandle plain = host.addTarget("targets/SimpleBullseye_score.target", 0, 0).get();
		rs.onTargetsChanged(host.targets());
		assertEquals(List.of(), newSounds());

		host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		rs.onTargetsChanged(host.targets());

		assertEquals(5, rs.getSubtargets().size());
		assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));

		// Another target leaving changes nothing
		plain.remove();
		rs.onTargetsChanged(host.targets());
		assertEquals(List.of(), newSounds());
	}

	// The called-out target leaving starts the call-outs on another, or warns that there is none
	@Test
	public void theCalledOutTargetLeavingMovesOnOrWarns() {
		final TargetHandle first = host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		final TargetHandle second = host.addTarget("targets/SimpleBullseye_five_small.target", 300, 0).get();
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);
		newSounds();

		first.remove();
		rs.onTargetsChanged(host.targets());
		assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));
		assertEquals(5, rs.getSubtargets().size());

		second.remove();
		rs.onTargetsChanged(host.targets());
		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());
		assertTrue(rs.getCurrentSubtargets().isEmpty());
	}
}
```

`javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`:

Replace:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("ShootForScore", "ISSFStandardPistol", "RandomShoot", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
```

with:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("RandomShoot", "ShootForScore", "ISSFStandardPistol", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
```

Replace:

```java
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 1));
		assertTrue(entries.subList(1, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
```

with:

```java
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 2));
		assertTrue(entries.subList(2, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :builtin-exercises:compileTestJava --console=plain`
Expected: FAIL to compile: `RandomShoot` doesn't exist.

- [ ] **Step 4: Implement**

`builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`:

Replace:

```java
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(ShootForScore.class);

	public static List<V2ExerciseEntry> entries() {
```

with:

```java
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(RandomShoot.class, ShootForScore.class);

	public static List<V2ExerciseEntry> entries() {
```

Create `builtin-exercises/src/main/java/com/shootoff/plugins/RandomShoot.java`:

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

package com.shootoff.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Stack;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetId;

/**
 * Calls out a random sequence of a target's subtargets for the shooter to hit in order; a miss or a wrong
 * subtarget repeats the one due. It runs everywhere: on the camera feed's targets and the arena's.
 */
public class RandomShoot implements Exercise {
	static final String WARNING_SOUND = "sounds/voice/shootoff-subtargets-warning.wav";
	static final String SHOOT_SOUND = "sounds/voice/shootoff-shoot.wav";
	static final String AND_SOUND = "sounds/voice/shootoff-and.wav";

	private final Random rng;
	private ExerciseHost host;
	// The target whose subtargets are called out
	private Optional<TargetId> selectedTarget = Optional.empty();
	private final List<String> subtargets = new ArrayList<>();
	private final Stack<Integer> currentSubtargets = new Stack<>();

	public RandomShoot() {
		this(new Random());
	}

	/**
	 * @param rng
	 *            for tests, one with a known seed
	 */
	RandomShoot(Random rng) {
		this.rng = rng;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("Random Shoot", "1.0", "phrack",
				"This exercise works with targets that have subtarget tags "
						+ "assigned to some regions. Subtargets are selected at random "
						+ "and the shooter is asked to shoot those subtargets in order. "
						+ "If a subtarget is shot out of order or the shooter misses, the "
						+ "name of the subtarget that should have been shot is repeated.");
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		if (fetchSubtargets(host.targets())) startRound();
	}

	/**
	 * As the JavaFX app did: while no target is called out, a target with subtargets arriving starts the
	 * call-outs; the called-out target leaving starts them afresh on another, or warns that there is none.
	 */
	@Override
	public void onTargetsChanged(List<TargetHandle> targets) {
		if (selectedTarget.isPresent()) {
			final TargetId selected = selectedTarget.get();
			if (targets.stream().noneMatch(target -> target.id().equals(selected))) {
				selectedTarget = Optional.empty();
				if (fetchSubtargets(targets)) startRound();
			}
		} else if (targets.stream().anyMatch(RandomShoot::hasSubtargets)) {
			if (fetchSubtargets(targets)) startRound();
		}
	}

	private static boolean hasSubtargets(TargetHandle target) {
		return target.definition().regions().stream().anyMatch(region -> region.tags().containsKey("subtarget"));
	}

	private void startRound() {
		pickSubtargets();
		saySubtargets();
	}

	/**
	 * @return every subtarget of the called-out target, for tests
	 */
	List<String> getSubtargets() {
		return subtargets;
	}

	/**
	 * @return the subtargets still to hit, the next on top, for tests
	 */
	Stack<Integer> getCurrentSubtargets() {
		return currentSubtargets;
	}

	/**
	 * Finds the first target with subtargets and gets its regions. If there is none, warns the shooter.
	 *
	 * @return <tt>true</tt> if there are subtargets to call out
	 */
	private boolean fetchSubtargets(List<TargetHandle> targets) {
		subtargets.clear();
		currentSubtargets.clear();

		boolean foundTarget = false;
		for (final TargetHandle target : targets) {
			for (final Region region : target.definition().regions()) {
				if (region.tags().containsKey("subtarget")) {
					subtargets.add(region.tags().get("subtarget"));
					foundTarget = true;
				}
			}

			if (foundTarget) {
				selectedTarget = Optional.of(target.id());
				break;
			}
		}

		if (foundTarget && subtargets.size() > 0) {
			return true;
		} else {
			host.playSound(WARNING_SOUND);
			return false;
		}
	}

	private void pickSubtargets() {
		currentSubtargets.clear();

		final int count = rng.nextInt((subtargets.size() - 1) + 1) + 1;
		for (final int i : rng.ints(count, 0, subtargets.size()).toArray()) {
			currentSubtargets.push(Integer.valueOf(i));
		}
	}

	private static String voice(String subtarget) {
		return String.format("sounds/voice/shootoff-%s.wav", subtarget);
	}

	private void saySubtargets() {
		final List<String> sounds = new ArrayList<>();
		sounds.add(SHOOT_SOUND);

		final Stack<Integer> temp = new Stack<>();
		temp.addAll(currentSubtargets);
		Collections.reverse(temp);
		final Iterator<Integer> it = temp.iterator();

		while (it.hasNext()) {
			final Integer index = it.next();

			if (!it.hasNext() && currentSubtargets.size() > 1) sounds.add(AND_SOUND);

			final String targetNameSound = voice(subtargets.get(index));

			if (host.hasSound(targetNameSound)) {
				sounds.add(targetNameSound);
			} else {
				// No voice actor's recording for this subtarget: text to speech instead
				saySubtargetsTTS();
				return;
			}
		}

		host.playSounds(sounds);
	}

	private void saySubtargetsTTS() {
		final StringBuilder sentence = new StringBuilder("shoot subtarget ");

		sentence.append(subtargets.get(currentSubtargets.get(currentSubtargets.size() - 1)));

		for (int i = currentSubtargets.size() - 2; i >= 0; i--) {
			sentence.append(" then ");
			sentence.append(subtargets.get(currentSubtargets.get(i)));
		}

		host.say(sentence.toString());
	}

	private void sayCurrentSubtarget() {
		final int subtargetIndex = currentSubtargets.peek();

		if (subtargets.size() == 0 || subtargetIndex > subtargets.size()) {
			// There are no subtargets left, or the index is of one that doesn't exist: start again
			startRound();
			return;
		}

		final String targetNameSound = voice(subtargets.get(subtargetIndex));

		if (host.hasSound(targetNameSound)) {
			host.playSounds(List.of(SHOOT_SOUND, targetNameSound));
		} else {
			host.say("shoot " + subtargets.get(currentSubtargets.peek()));
		}
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		if (currentSubtargets.isEmpty()) return;

		if (hit.isPresent()) {
			final Optional<String> subtargetValue = hit.get().region().tag("subtarget");
			if (subtargetValue.isPresent() && subtargetValue.get().equals(subtargets.get(currentSubtargets.peek()))) {
				currentSubtargets.pop();
			} else {
				sayCurrentSubtarget();
			}

			if (currentSubtargets.isEmpty()) startRound();
		} else {
			sayCurrentSubtarget();
		}
	}

	@Override
	public void onReset() {
		if (fetchSubtargets(host.targets())) startRound();
	}

	@Override
	public void stop() {}
}
```

`javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`:

Replace:

```java
	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(standard(new ISSFStandardPistol()), standard(new RandomShoot()),
				standard(new TimedHolsterDrill()), standard(new ParForScore()), standard(new ParRandomShot()),
				projector(new BouncingTargets()), projector(new DuelingTree()), projector(new ShootDontShoot()),
				projector(new SteelChallenge())));
		return entries;
	}
```

with:

```java
	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(standard(new ISSFStandardPistol()), standard(new TimedHolsterDrill()),
				standard(new ParForScore()), standard(new ParRandomShot()), projector(new BouncingTargets()),
				projector(new DuelingTree()), projector(new ShootDontShoot()), projector(new SteelChallenge())));
		return entries;
	}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :builtin-exercises:test :javafx-app:test --tests '*BuiltInExercises*' --console=plain`
Expected: PASS. The seed-15 call-outs match the JavaFX test's (ruling 19).

- [ ] **Step 6: The gate, and the moved tests**

Run the gate. Expected: `986/986 passing; 0 regressions; 0 new failures`. Then:

Run: `python3 scripts/test_summary.py summarize builtin-exercises/build/test-results/test | command grep -E 'TestRandomShoot\.test'`
Expected: exactly these three lines:

```
PASS com.shootoff.plugins.TestRandomShoot.testFiveSmallTarget
PASS com.shootoff.plugins.TestRandomShoot.testNoSoundFilesForSubtargetNames
PASS com.shootoff.plugins.TestRandomShoot.testNoTarget
```

- [ ] **Step 7: Commit**

```bash
git add builtin-exercises/src/main/java/com/shootoff/plugins/RandomShoot.java builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java builtin-exercises/src/test/java/com/shootoff/plugins/TestRandomShoot.java builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java
git commit -m "Port Random Shoot to the v2 exercise API"
git log -1 --format=%B
```

---

### Task 7: Timed Holster Drill, PAR Drill with Score, PAR Drill with a random Subtarget

The three go together: in the JavaFX app `ParRandomShot extends ParForScore extends TimedHolsterDrill`, so none of the three can be removed while the others remain, and the ports keep the same names.

**Files:**
- Create: `builtin-exercises/src/main/java/com/shootoff/plugins/TimedHolsterDrill.java`, `…/ParForScore.java`, `…/ParRandomShot.java`
- Modify: `builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`, `javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`
- Delete: `javafx-app/src/main/java/com/shootoff/plugins/{TimedHolsterDrill, ParForScore, ParRandomShot}.java` (they had no tests)
- Test: `builtin-exercises/src/test/java/com/shootoff/plugins/TestTimedHolsterDrill.java`, `…/TestParForScore.java`, `…/TestParRandomShot.java` (all new, JUnit 5), `…/TestBuiltInRegistry.java`; `javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`

**Interfaces:**
- Consumes: Task 2's `hasSound`; Task 5's `ShootForScore.scoreMessage`; Task 6's `RandomShoot.WARNING_SOUND`; `ExerciseHost.schedule` / `Cancellable`, `addButton` / `ButtonHandle.setLabel`, `setDelayedStart`, `onDelayedStartChanged`, `setParTime`, `onParTimeChanged`, `styleLastRow`, `pauseShotDetection`, `currentTimeMillis`, `clearShots`.
- Produces:
  - `TimedHolsterDrill` (v2), with package-private `TimedHolsterDrill(Random)`; constants `LENGTH_COL_NAME`, `PAUSE`, `RESUME`, `CLEAR_SHOTS`, `START_DELAY` (10 s), `RESUME_DELAY` (5 s), `DEFAULT_DELAY` (4–8), `ROUND_SHADING` (`lightgray`), `MAKE_READY_SOUND`, `BEEP_SOUND`; protected hooks `doRound()`, `nextRound()`, `scheduleNext(Runnable, Duration)`, `canRun()`, `paused()`, `resetValues()`, `setupRound()`, `randomDelay()`, `startRoundTimer()`, `setLength()`.
  - `ParForScore extends TimedHolsterDrill`, with `ParForScore(Random)`; `POINTS_COL_NAME`, `DEFAULT_PAR_TIME` (2.0), `CHIME_SOUND`; protected `startParTime()`, `setPoints(ShotColor, String)`, field `countScore`, `parTime`.
  - `ParRandomShot extends ParForScore`, with `ParRandomShot(Random)`, `getSubtargets()`, `getCurrentSubtarget()`.

**Why.** Spec §4 rows 3–5, §5 Pause. Rulings 17–18, 20–26, 28, 31.

- [ ] **Step 1: Remove the JavaFX classes**

```bash
git rm javafx-app/src/main/java/com/shootoff/plugins/TimedHolsterDrill.java javafx-app/src/main/java/com/shootoff/plugins/ParForScore.java javafx-app/src/main/java/com/shootoff/plugins/ParRandomShot.java
```

- [ ] **Step 2: Write the failing tests**

`builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java`:

Replace:

```java
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("Random Shoot", "Shoot for Score"),
				entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false, false), entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}

```

with:

```java
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("Random Shoot", "Shoot for Score", "Timed Holster Drill", "PAR Drill with Score",
				"PAR Drill with a random Subtarget"), entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false, false, false, false, false),
				entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}

```

Create `builtin-exercises/src/test/java/com/shootoff/plugins/TestParForScore.java`:

```java
package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;

class TestParForScore {
	private static final String MAKE_READY = TimedHolsterDrill.MAKE_READY_SOUND;
	private static final String BEEP = TimedHolsterDrill.BEEP_SOUND;
	private static final String CHIME = ParForScore.CHIME_SOUND;

	@TempDir Path temp;
	private Locale previousLocale;
	private FakeExerciseHost host;
	private Hit tenRegionHit;

	@BeforeEach
	void setUp() throws IOException, TargetFormatException {
		previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.US);
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.resolve("data"));

		for (final Region region : TargetDefinitions.load(Paths.get("targets", "SimpleBullseye_score.target")).regions()) {
			if (region.tag("points").equals(Optional.of("10"))) tenRegionHit = new Hit(new TargetId(1), region, new Point(0, 0));
		}
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
	}

	// Starts the drill with a 1 s delay and a 2 s par time, and runs it to its first beep
	private void startDrillToTheBeep() {
		host.start(new ParForScore(new Random(7)));
		host.changeDelayedStart(new DelayRange(1, 1));
		host.changeParTime(2.0);
		advanceSeconds(11);
	}

	private void advanceSeconds(double seconds) {
		host.advance(Duration.ofMillis(Math.round(seconds * 1000)));
	}

	private boolean shootTheTen(ShotColor color) {
		return host.shoot(new Shot(color, 0, 0, host.currentTimeMillis()), Optional.of(tenRegionHit));
	}

	private Map<String, String> lastRow() {
		final List<FakeExerciseHost.Row> rows = host.rows();
		return rows.get(rows.size() - 1).values();
	}

	@Test
	void itShowsBothColumnsAndTheSharedParTimeAtTheJavaFxDefault() {
		host.start(new ParForScore());

		assertEquals(List.of("Length", "Score"), host.columns());
		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		assertEquals(2.0, host.parTime(), 0);
		assertTrue(host.isListeningForParTime());
		assertEquals(List.of("score: 0"), host.messages());
	}

	@Test
	void theParTimeScoresFromTheBeepToTheChime() {
		startDrillToTheBeep();
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());

		// The first round scores too
		advanceSeconds(0.5);
		assertTrue(shootTheTen(ShotColor.RED));
		assertEquals(Map.of("Length", "0.50", "Score", "10"), lastRow());
		assertTrue(shootTheTen(ShotColor.GREEN));
		assertEquals(List.of("score: 0", "red score: 10", "red score: 10\ngreen score: 10"), host.messages());

		// The chime ends the par time and shot detection until the next beep
		advanceSeconds(1.5);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME), host.sounds());
		assertTrue(host.isShotDetectionPaused());
		assertFalse(shootTheTen(ShotColor.RED));

		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME, BEEP), host.sounds());
		assertFalse(host.isShotDetectionPaused());
	}

	@Test
	void theUsersParTimeTakesEffectFromTheNextRound() {
		startDrillToTheBeep();

		host.changeParTime(3.0);
		advanceSeconds(2);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME), host.sounds());
		advanceSeconds(1);
		advanceSeconds(2.9);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME, BEEP), host.sounds());
		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME, BEEP, CHIME), host.sounds());
	}

	@Test
	void pausingInTheParTimeEndsItWithoutTheChime() {
		startDrillToTheBeep();

		host.click("Pause");
		advanceSeconds(30);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		assertTrue(host.isShotDetectionPaused());

		host.click("Resume");
		advanceSeconds(6);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY, BEEP), host.sounds());
		assertTrue(shootTheTen(ShotColor.RED));
		assertEquals("10", lastRow().get("Score"));
	}

	@Test
	void resetZeroesTheScores() {
		startDrillToTheBeep();
		shootTheTen(ShotColor.RED);

		host.reset();

		assertEquals(List.of("score: 0", "red score: 10", "score: 0"), host.messages());
		advanceSeconds(11);
		assertTrue(shootTheTen(ShotColor.RED));
		assertEquals("red score: 10", host.messages().get(3));
	}
}
```

Create `builtin-exercises/src/test/java/com/shootoff/plugins/TestParRandomShot.java`:

```java
package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;

class TestParRandomShot {
	private static final String MAKE_READY = TimedHolsterDrill.MAKE_READY_SOUND;
	private static final String CHIME = ParForScore.CHIME_SOUND;

	@TempDir Path temp;
	private Locale previousLocale;
	private String previousHome;
	private FakeExerciseHost host;

	@BeforeEach
	void setUp() {
		previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.US);
		// The voice files are ShootOFF's own, in its sounds/voice folder
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	// Starts the drill with a 1 s delay and a 2 s par time, and runs it to its first call-out
	private ParRandomShot startDrillToTheCallOut() {
		final ParRandomShot drill = new ParRandomShot(new Random(3));
		host.start(drill);
		host.changeDelayedStart(new DelayRange(1, 1));
		host.changeParTime(2.0);
		advanceSeconds(11);
		return drill;
	}

	private void advanceSeconds(double seconds) {
		host.advance(Duration.ofMillis(Math.round(seconds * 1000)));
	}

	private static Hit hit(TargetHandle target, String subtarget) {
		for (final Region region : target.definition().regions()) {
			if (region.tag("subtarget").equals(Optional.of(subtarget))) {
				return new Hit(target.id(), region, new Point(0, 0));
			}
		}
		throw new AssertionError("No subtarget " + subtarget);
	}

	private boolean shoot(Hit hit) {
		return host.shoot(new Shot(ShotColor.RED, 0, 0, host.currentTimeMillis()), Optional.of(hit));
	}

	private Map<String, String> lastRow() {
		final List<FakeExerciseHost.Row> rows = host.rows();
		return rows.get(rows.size() - 1).values();
	}

	@Test
	void withoutATargetWithSubtargetsItWarnsAndRunsNoRoundsUntilResetFindsOne() {
		host.start(new ParRandomShot(new Random(3)));
		host.changeDelayedStart(new DelayRange(1, 1));

		assertEquals(List.of(RandomShoot.WARNING_SOUND), host.sounds());
		advanceSeconds(60);
		host.click("Pause");
		host.click("Resume");
		advanceSeconds(60);
		assertEquals(List.of(RandomShoot.WARNING_SOUND), host.sounds());
		assertEquals(0, host.pendingTasks());

		host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0);
		host.reset();
		advanceSeconds(10);
		assertEquals(List.of(RandomShoot.WARNING_SOUND, MAKE_READY), host.sounds());
	}

	@Test
	void eachRoundCallsOutARandomSubtargetInsteadOfTheBeep() {
		host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0);
		final ParRandomShot drill = startDrillToTheCallOut();

		assertEquals(5, drill.getSubtargets().size());
		assertEquals(List.of(MAKE_READY, "sounds/voice/shootoff-" + drill.getCurrentSubtarget() + ".wav"),
				host.sounds());

		advanceSeconds(2);
		assertEquals(CHIME, host.sounds().get(2));
		assertTrue(host.isShotDetectionPaused());
	}

	@Test
	void onlyHitsOnTheCalledSubtargetScoreItsPointsOrOne() {
		final TargetHandle target = host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		final ParRandomShot drill = startDrillToTheCallOut();
		final String called = drill.getCurrentSubtarget();
		final String other = drill.getSubtargets().stream().filter(name -> !name.equals(called)).findFirst().get();

		advanceSeconds(0.5);
		assertTrue(shoot(hit(target, other)));
		assertEquals(Map.of("Length", "0.50"), lastRow());
		assertEquals(List.of("score: 0"), host.messages());

		assertTrue(shoot(hit(target, called)));
		assertEquals(Map.of("Length", "0.50", "Score", "1"), lastRow());
		assertEquals(List.of("score: 0", "red score: 1"), host.messages());
	}

	@Test
	void aSubtargetWithoutAVoiceFileIsSpoken() throws URISyntaxException {
		host.addTarget(Paths.get(TestParRandomShot.class.getResource("/test_missing_sound_files.target").toURI())
				.toString(), 0, 0);
		final ParRandomShot drill = startDrillToTheCallOut();

		assertEquals(List.of(drill.getCurrentSubtarget()), host.spoken());
		assertEquals(List.of(MAKE_READY), host.sounds());
	}
}
```

Create `builtin-exercises/src/test/java/com/shootoff/plugins/TestTimedHolsterDrill.java`:

```java
package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.exercise.RowStyle;

class TestTimedHolsterDrill {
	private static final String MAKE_READY = TimedHolsterDrill.MAKE_READY_SOUND;
	private static final String BEEP = TimedHolsterDrill.BEEP_SOUND;

	@TempDir Path temp;
	private Locale previousLocale;
	private FakeExerciseHost host;

	@BeforeEach
	void setUp() {
		previousLocale = Locale.getDefault();
		// The drill formats lengths in the default locale, as it always has
		Locale.setDefault(Locale.US);
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
	}

	// Starts the drill with a 1 s delay between rounds
	private void startDrill() {
		host.start(new TimedHolsterDrill(new Random(7)));
		host.changeDelayedStart(new DelayRange(1, 1));
	}

	private void advanceSeconds(double seconds) {
		host.advance(Duration.ofMillis(Math.round(seconds * 1000)));
	}

	private void shoot() {
		assertTrue(host.shoot(ShotColor.RED, 5, 5));
	}

	private String lastLength() {
		final List<FakeExerciseHost.Row> rows = host.rows();
		return rows.get(rows.size() - 1).values().get(TimedHolsterDrill.LENGTH_COL_NAME);
	}

	@Test
	void itShowsItsControlsAndTheSharedDelayAtTheJavaFxDefaults() {
		host.start(new TimedHolsterDrill());

		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		assertEquals(List.of("Length"), host.columns());
		assertEquals(new DelayRange(4, 8), host.delayedStart());
		assertTrue(host.isListeningForDelayedStart());
		assertFalse(host.isListeningForParTime());
		assertTrue(host.isShotDetectionPaused());
	}

	@Test
	void tenSecondsToGetReadyThenMakeReadyAndABeepAfterTheRandomDelay() {
		startDrill();

		advanceSeconds(9.9);
		assertEquals(List.of(), host.sounds());

		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY), host.sounds());
		assertTrue(host.isShotDetectionPaused());

		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		assertFalse(host.isShotDetectionPaused());

		// Round after round, with no new "make ready" and detection left on
		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP, BEEP), host.sounds());
		assertFalse(host.isShotDetectionPaused());
	}

	@Test
	void aShotsLengthIsItsTimeSinceTheBeep() {
		startDrill();
		advanceSeconds(11);

		advanceSeconds(0.25);
		shoot();
		assertEquals("0.25", lastLength());

		advanceSeconds(0.5);
		shoot();
		assertEquals("0.75", lastLength());
	}

	@Test
	void roundsAlternateTheirShadingOnlyAfterARoundWithAShot() {
		startDrill();
		advanceSeconds(11);

		shoot();
		// The next round is shaded; a round without a shot keeps the shading for the one after
		advanceSeconds(1);
		shoot();
		advanceSeconds(1);
		advanceSeconds(1);
		shoot();

		assertEquals(List.of(Optional.empty(), Optional.of(TimedHolsterDrill.ROUND_SHADING),
				Optional.<RowStyle> empty()), host.rows().stream().map(FakeExerciseHost.Row::style).toList());
	}

	@Test
	void pauseStopsTheRoundsAndResumeMakesReadyFiveSecondsLater() {
		startDrill();
		advanceSeconds(11);

		host.click("Pause");
		assertEquals(List.of("Resume", "Clear Shots"), host.buttonLabels());
		assertTrue(host.isShotDetectionPaused());
		advanceSeconds(60);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());

		host.click("Resume");
		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		advanceSeconds(4.9);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY), host.sounds());
		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY, BEEP), host.sounds());
		// One chain of rounds, not two
		assertEquals(1, host.pendingTasks());
	}

	@Test
	void resetStartsAfreshTenSecondsLater() {
		startDrill();
		advanceSeconds(11);
		host.click("Pause");

		host.reset();

		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		assertTrue(host.isShotDetectionPaused());
		advanceSeconds(9.9);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY), host.sounds());
	}

	@Test
	void clearShotsClearsTheShotTimer() {
		startDrill();
		advanceSeconds(11);
		shoot();

		host.click("Clear Shots");

		assertEquals(List.of(), host.rows());
	}
}
```

`javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`:

Replace:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("RandomShoot", "ShootForScore", "ISSFStandardPistol", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 2));
		assertTrue(entries.subList(2, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
```

with:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("RandomShoot", "ShootForScore", "TimedHolsterDrill", "ParForScore", "ParRandomShot",
				"ISSFStandardPistol", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 5));
		assertTrue(entries.subList(5, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :builtin-exercises:compileTestJava --console=plain`
Expected: FAIL to compile: `TimedHolsterDrill`, `ParForScore` and `ParRandomShot` don't exist in `builtin-exercises`.

- [ ] **Step 4: Implement**

`builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`:

Replace:

```java
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(RandomShoot.class, ShootForScore.class);

	public static List<V2ExerciseEntry> entries() {
```

with:

```java
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(RandomShoot.class, ShootForScore.class,
			TimedHolsterDrill.class, ParForScore.class, ParRandomShot.class);

	public static List<V2ExerciseEntry> entries() {
```

Create `builtin-exercises/src/main/java/com/shootoff/plugins/ParForScore.java`:

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

package com.shootoff.plugins;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.targets.model.Hit;

/**
 * Merge of TimedHolsterDrill and ShootForScore, with the addition of a PAR interval during which scores are
 * counted: the beep, then a chime at the end of the par time, when shot detection stops until the next
 * round.
 *
 * @author Edward Kort
 */
public class ParForScore extends TimedHolsterDrill {
	static final String POINTS_COL_NAME = "Score";
	/** The shared par time when the drill starts, as the JavaFX app's own control had it */
	static final double DEFAULT_PAR_TIME = 2.0;
	static final String CHIME_SOUND = "sounds/chime.wav";

	protected double parTime = DEFAULT_PAR_TIME;

	private int redScore = 0;
	private int greenScore = 0;

	// While the par time runs
	protected boolean countScore = false;

	public ParForScore() {
		super();
	}

	ParForScore(Random random) {
		super(random);
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("PAR Drill with Score", "1.0", "Edward Kort",
				"This exercise does not require a target, but one may be used "
						+ "to give the shooter something to shoot at. If a target with "
						+ "score areas is used, the scores are displayed and tracked. "
						+ "When the exercise is started you are asked to enter a range "
						+ "for randomly delayed starts, and for the interval (PAR time) "
						+ "in which those scores will be counted. You are then given 10 "
						+ "seconds to position yourself. After a random wait (within "
						+ "the entered range) a beep tells you to draw the pistol from "
						+ "its holster and fire at your target; a chime signals the end "
						+ "of the Par time, to finally re-holster. This process is "
						+ "repeated as long as this exercise is on.");
	}

	@Override
	protected void initUI() {
		super.initUI();
		host.addColumn(POINTS_COL_NAME);

		host.setParTime(DEFAULT_PAR_TIME);
		host.onParTimeChanged(seconds -> parTime = seconds);
	}

	@Override
	protected void doRound() {
		host.playSound(BEEP_SOUND);
		host.pauseShotDetection(false);
		startRoundTimer();
		startParTime();
	}

	/**
	 * Counts the scores until the chime at the end of the par time.
	 */
	protected void startParTime() {
		countScore = true;
		scheduleNext(this::endParTime, Duration.ofMillis(Math.round(parTime * 1000)));
	}

	private void endParTime() {
		host.playSound(CHIME_SOUND);
		host.pauseShotDetection(true);
		countScore = false;
		nextRound();
	}

	@Override
	protected void paused() {
		countScore = false;
	}

	/*
	 * This method merges shotListener for TimedHolsterDrill and ShootForScore.
	 */
	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		super.onShot(shot, hit);

		if (hit.isEmpty() || !countScore) return;

		final Optional<String> points = hit.get().region().tag("points");
		if (points.isPresent()) setPoints(shot.getColor(), points.get());
	}

	protected void setPoints(ShotColor shotColor, String points) {
		host.setColumnValue(POINTS_COL_NAME, points);

		if (shotColor.equals(ShotColor.RED) || shotColor.equals(ShotColor.INFRARED)) {
			redScore += Integer.parseInt(points);
		} else if (shotColor.equals(ShotColor.GREEN)) {
			greenScore += Integer.parseInt(points);
		}

		host.showMessage(ShootForScore.scoreMessage(redScore, greenScore));
	}

	@Override
	protected void resetValues() {
		redScore = 0;
		greenScore = 0;
		host.showMessage("score: 0");
	}
}
```

Create `builtin-exercises/src/main/java/com/shootoff/plugins/ParRandomShot.java`:

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

package com.shootoff.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;

/**
 * Merge of ParForScore and RandomShoot: each round calls out a random subtarget of the first target that
 * has subtargets, instead of the beep, and only hits on it score. Without such a target it warns and runs
 * no rounds until Reset finds one.
 *
 * @author Edward Kort
 */
public class ParRandomShot extends ParForScore {
	private final List<String> subtargets = new ArrayList<>();
	private final Random rng;
	private boolean foundTarget;
	private int currentSubtarget;

	public ParRandomShot() {
		this(new Random());
	}

	/**
	 * @param random
	 *            for tests, one with a known seed; it picks both the delays and the subtargets
	 */
	ParRandomShot(Random random) {
		super(random);
		rng = random;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("PAR Drill with a random Subtarget", "1.0", "Edward Kort",
				"This exercise works with targets that have subtarget tags "
						+ "assigned to some regions. When the exercise is started you "
						+ "are asked to enter a range for randomly delayed starts, and "
						+ "for the interval (PAR time) in which those scores will be "
						+ "counted. You are then given 10 seconds to position yourself. "
						+ "After a random wait (within the entered range), a randomly "
						+ "selected subtarget is called out, telling you to draw the "
						+ "pistol from its holster and fire at your target; a chime "
						+ "signals the end of the Par time, to finally re-holster. The "
						+ "score for each shot, performed during the PAR time and hitting "
						+ "the subtarget, is points assigned to that subtarget (or 1 if "
						+ "there is no assignment). This process is repeated as long as " + "this exercise is on.");
	}

	@Override
	protected boolean canRun() {
		return foundTarget;
	}

	// The call-out replaces the beep
	@Override
	protected void doRound() {
		pickSubtarget();
		saySubtarget();
		host.pauseShotDetection(false);
		startRoundTimer();
		startParTime();
	}

	// Rows keep their plain shading here, as in the JavaFX app
	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		setLength();

		if (!foundTarget || hit.isEmpty() || !countScore) return;

		final String subtarget = subtargets.get(currentSubtarget);
		final Region region = hit.get().region();
		if (region.tag("subtarget").equals(Optional.of(subtarget))) {
			setPoints(shot.getColor(), region.tag("points").orElse("1"));
		}
	}

	@Override
	protected void resetValues() {
		super.resetValues();
		fetchSubtargets(host.targets());
	}

	/**
	 * @return every subtarget of the called-out target, for tests
	 */
	List<String> getSubtargets() {
		return subtargets;
	}

	/**
	 * @return the subtarget called out last, for tests
	 */
	String getCurrentSubtarget() {
		return subtargets.get(currentSubtarget);
	}

	/**
	 * @see RandomShoot
	 */
	private void fetchSubtargets(List<TargetHandle> targets) {
		subtargets.clear();

		foundTarget = false;
		for (final TargetHandle target : targets) {
			for (final Region region : target.definition().regions()) {
				if (region.tags().containsKey("subtarget")) {
					subtargets.add(region.tags().get("subtarget"));
					foundTarget = true;
				}
			}

			if (foundTarget) break;
		}

		if (!foundTarget) host.playSound(RandomShoot.WARNING_SOUND);
	}

	private void pickSubtarget() {
		if (foundTarget) currentSubtarget = rng.nextInt(subtargets.size());
	}

	private void saySubtarget() {
		if (!foundTarget) return;

		final String subValue = subtargets.get(currentSubtarget);
		final String targetNameSound = String.format("sounds/voice/shootoff-%s.wav", subValue);

		if (host.hasSound(targetNameSound)) {
			host.playSound(targetNameSound);
		} else {
			// No voice actor's recording for this subtarget: text to speech instead
			host.say(subValue);
		}
	}
}
```

Create `builtin-exercises/src/main/java/com/shootoff/plugins/TimedHolsterDrill.java`:

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

package com.shootoff.plugins;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.RowStyle;
import com.shootoff.targets.model.Hit;

/**
 * Ten seconds to get ready, then round after round: "make ready" once, and after a random delay a beep to
 * draw and fire; each shot's Length is its time since the beep. Rounds alternate their shot timer rows'
 * shading. Pause stops the rounds; Resume makes ready again five seconds later. It runs everywhere: on the
 * camera feed's targets and the arena's.
 * <p>
 * Par for Score and Par Random Shot build on it: {@link #doRound} is a round's start, and
 * {@link #nextRound} schedules the next one.
 */
public class TimedHolsterDrill implements Exercise {
	static final String LENGTH_COL_NAME = "Length";
	static final String PAUSE = "Pause";
	static final String RESUME = "Resume";
	static final String CLEAR_SHOTS = "Clear Shots";
	static final Duration START_DELAY = Duration.ofSeconds(10);
	static final Duration RESUME_DELAY = Duration.ofSeconds(5);
	/** The shared delay controls' values when the drill starts, as the JavaFX app's own controls had them */
	static final DelayRange DEFAULT_DELAY = new DelayRange(4, 8);
	static final RowStyle ROUND_SHADING = new RowStyle("lightgray");
	static final String MAKE_READY_SOUND = "sounds/voice/shootoff-makeready.wav";
	static final String BEEP_SOUND = "sounds/beep.wav";

	protected ExerciseHost host;
	private final Random random;
	private int delayMin = DEFAULT_DELAY.minSeconds();
	private int delayMax = DEFAULT_DELAY.maxSeconds();
	// false while paused
	private boolean repeatExercise = true;
	private long beepTime = 0;
	private boolean hadShot = false;
	private boolean coloredRows = false;
	private ButtonHandle pauseResumeButton;
	// The drill's next step (make ready, a round, the end of a par time): at most one is pending
	private Optional<Cancellable> nextStep = Optional.empty();

	public TimedHolsterDrill() {
		this(new Random());
	}

	/**
	 * @param random
	 *            for tests, one with a known seed
	 */
	TimedHolsterDrill(Random random) {
		this.random = random;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("Timed Holster Drill", "1.0", "phrack",
				"This exercise does not require a target, but one may be used "
						+ "to give the shooter something to shoot at. When the exercise "
						+ "is started you are asked to enter a range for randomly "
						+ "delayed starts. You are then given 10 seconds to position "
						+ "yourself. After a random wait (within the entered range) a "
						+ "beep tells you to draw their pistol from it's holster, "
						+ "fire at your target, and finally re-holster. This process is "
						+ "repeated as long as this exercise is on.");
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		initUI();
		initService();
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		// The JavaFX app shaded every row added while a round's shading was on
		if (coloredRows) host.styleLastRow(ROUND_SHADING);

		if (repeatExercise) {
			hadShot = true;
			setLength();
		}
	}

	protected void setLength() {
		final float drawShotLength = (float) (host.currentTimeMillis() - beepTime) / (float) 1000; // s
		host.setColumnValue(LENGTH_COL_NAME, String.format("%.2f", drawShotLength));
	}

	@Override
	public void onReset() {
		host.pauseShotDetection(true);
		cancelNextStep();
		repeatExercise = true;
		pauseResumeButton.setLabel(PAUSE);
		resetValues();
		if (canRun()) scheduleNext(this::setupWait, START_DELAY);
	}

	@Override
	public void stop() {
		// The host cancels the pending step and removes what the drill added
		repeatExercise = false;
	}

	/**
	 * @return whether the drill has what its rounds need (Par Random Shot: a target with subtargets)
	 */
	protected boolean canRun() {
		return true;
	}

	protected void initUI() {
		pauseResumeButton = host.addButton(PAUSE, this::pauseOrResume);
		host.addButton(CLEAR_SHOTS, host::clearShots);
		host.addColumn(LENGTH_COL_NAME);

		host.setDelayedStart(DEFAULT_DELAY);
		host.onDelayedStartChanged(range -> {
			delayMin = range.minSeconds();
			delayMax = range.maxSeconds();
		});
	}

	protected void initService() {
		host.pauseShotDetection(true);
		resetValues();
		if (canRun()) scheduleNext(this::setupWait, START_DELAY);
	}

	// Pause stops the rounds where they are; Resume makes ready again after RESUME_DELAY
	private void pauseOrResume() {
		if (repeatExercise) {
			pauseResumeButton.setLabel(RESUME);
			repeatExercise = false;
			host.pauseShotDetection(true);
			cancelNextStep();
			paused();
		} else {
			pauseResumeButton.setLabel(PAUSE);
			repeatExercise = true;
			if (canRun()) scheduleNext(this::setupWait, RESUME_DELAY);
		}
	}

	/**
	 * The drill was paused: what a round in progress must forget
	 */
	protected void paused() {}

	private void setupWait() {
		if (!repeatExercise) return;

		host.pauseShotDetection(true);
		host.playSound(MAKE_READY_SOUND);
		scheduleNext(this::round, Duration.ofSeconds(randomDelay()));
	}

	private void round() {
		if (!repeatExercise) return;

		doRound();
	}

	/**
	 * A round's start: the beep, shot detection on and the round's clock started, then the next round.
	 */
	protected void doRound() {
		host.playSound(BEEP_SOUND);
		host.pauseShotDetection(false);
		startRoundTimer();
		nextRound();
	}

	/**
	 * Schedules the next round after a random delay.
	 */
	protected void nextRound() {
		scheduleNext(this::round, Duration.ofSeconds(setupRound()));
	}

	protected void scheduleNext(Runnable step, Duration delay) {
		cancelNextStep();
		nextStep = Optional.of(host.schedule(step, delay));
	}

	private void cancelNextStep() {
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.empty();
	}

	/**
	 * @return the delay, in seconds, before the next round
	 */
	protected int setupRound() {
		// Only toggle the color if there was a shot in the last round, otherwise the colors get out of sync
		// if the user misses a round (thus you can have a string of shots that is all gray or white even
		// though they were different rounds)
		if (hadShot) {
			coloredRows = !coloredRows;
			hadShot = false;
		}

		return randomDelay();
	}

	protected int randomDelay() {
		return random.nextInt((delayMax - delayMin) + 1) + delayMin;
	}

	protected void startRoundTimer() {
		beepTime = host.currentTimeMillis();
	}

	/**
	 * What the drill sets up at its start and on Reset
	 */
	protected void resetValues() {}
}
```

`javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`:

Replace:

```java
	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(standard(new ISSFStandardPistol()), standard(new TimedHolsterDrill()),
				standard(new ParForScore()), standard(new ParRandomShot()), projector(new BouncingTargets()),
				projector(new DuelingTree()), projector(new ShootDontShoot()), projector(new SteelChallenge())));
		return entries;
```

with:

```java
	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(standard(new ISSFStandardPistol()), projector(new BouncingTargets()),
				projector(new DuelingTree()), projector(new ShootDontShoot()), projector(new SteelChallenge())));
		return entries;
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :builtin-exercises:test :javafx-app:test --tests '*BuiltInExercises*' --console=plain`
Expected: PASS. `TestParForScore.theParTimeScoresFromTheBeepToTheChime` pins ruling 22.

- [ ] **Step 6: The gate**

Run the gate. Expected: `1002/1002 passing; 0 regressions; 0 new failures`.

- [ ] **Step 7: Commit**

```bash
git add builtin-exercises/src/main/java/com/shootoff/plugins/TimedHolsterDrill.java builtin-exercises/src/main/java/com/shootoff/plugins/ParForScore.java builtin-exercises/src/main/java/com/shootoff/plugins/ParRandomShot.java builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java builtin-exercises/src/test/java/com/shootoff/plugins/TestTimedHolsterDrill.java builtin-exercises/src/test/java/com/shootoff/plugins/TestParForScore.java builtin-exercises/src/test/java/com/shootoff/plugins/TestParRandomShot.java builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java
git commit -m "Port the Timed Holster Drill and the two PAR drills to the v2 exercise API"
git log -1 --format=%B
```

---

### Task 8: ISSF 25M Standard Pistol

**Files:**
- Create: `builtin-exercises/src/main/java/com/shootoff/plugins/ISSFStandardPistol.java`
- Modify: `builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`, `javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java` (only the projector drills are left there)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt` (no empty banner)
- Delete: `javafx-app/src/main/java/com/shootoff/plugins/ISSFStandardPistol.java`, `javafx-app/src/test/java/com/shootoff/plugins/TestISSFStandardPistol.java`
- Test: `builtin-exercises/src/test/java/com/shootoff/plugins/TestISSFStandardPistol.java` (moved, JUnit 4, +2), `…/TestBuiltInRegistry.java`; `javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`; `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt`

**Interfaces:**
- Consumes: as Task 7, plus `Cancellable.cancel()` for the series' end; the banner tag `banner-0` (the drill's message in `RangeScreen`).
- Produces: `ISSFStandardPistol` (v2), with package-private `ISSFStandardPistol(Random)`; constants `SCORE_COL_NAME`, `ROUND_COL_NAME`, `START_DELAY`, `RESUME_DELAY`, `DEFAULT_DELAY`, `SERIES_SHADING`, `MAKE_READY_SOUND`, `BEEP_SOUND`, `ROUND_OVER_SOUND`. After this task `BuiltInRegistry.entries()` lists the six in v1's menu order and `BuiltInExercises.entries()` is those six (v2) then the four projector drills (legacy).

**Why.** Spec §4 row 6, §5 Pause. Rulings 11, 17–18, 20–21, 29, 32–33.

- [ ] **Step 1: Remove the JavaFX class and its test**

```bash
git rm javafx-app/src/main/java/com/shootoff/plugins/ISSFStandardPistol.java javafx-app/src/test/java/com/shootoff/plugins/TestISSFStandardPistol.java
```

- [ ] **Step 2: Write the failing tests**

`builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java`:

Replace:

```java
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("Random Shoot", "Shoot for Score", "Timed Holster Drill", "PAR Drill with Score",
				"PAR Drill with a random Subtarget"), entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false, false, false, false, false),
				entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}
```

with:

```java
		final List<V2ExerciseEntry> entries = BuiltInRegistry.entries();

		assertEquals(List.of("ISSF 25M Standard Pistol", "Random Shoot", "Shoot for Score", "Timed Holster Drill",
				"PAR Drill with Score", "PAR Drill with a random Subtarget"),
				entries.stream().map(entry -> entry.metadata().getName()).toList());
		assertEquals(List.of(false, false, false, false, false, false),
				entries.stream().map(V2ExerciseEntry::isProjectorOnly).toList());
	}
```

Create `builtin-exercises/src/test/java/com/shootoff/plugins/TestISSFStandardPistol.java`:

```java
package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;

// JUnit 4, as in the JavaFX app: the test names stay those of the Java 8 baseline
public class TestISSFStandardPistol {
	private static final String MAKE_READY = ISSFStandardPistol.MAKE_READY_SOUND;
	private static final String BEEP = ISSFStandardPistol.BEEP_SOUND;
	private static final String ROUND_OVER = ISSFStandardPistol.ROUND_OVER_SOUND;

	@Rule public TemporaryFolder temp = new TemporaryFolder();

	private FakeExerciseHost host;
	private Hit scoredRegionHit;
	private int regionScore;
	private int messagesSeen = 0;
	private int soundsSeen = 0;
	private int spokenSeen = 0;

	@Before
	public void setUp() throws IOException, TargetFormatException {
		final Region scored = TargetDefinitions.load(Paths.get("targets", "ISSF.target")).regions().get(0);
		scoredRegionHit = new Hit(new TargetId(1), scored, new Point(0, 0));
		regionScore = Integer.parseInt(scored.tag("points").get());

		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.newFolder("data").toPath());
		host.start(new ISSFStandardPistol(new Random(1)));
		// No random delays, as the JavaFX test's init(0, 0) had it
		host.changeDelayedStart(new DelayRange(0, 0));
		host.advance(ISSFStandardPistol.START_DELAY);
	}

	private List<String> newMessages() {
		final List<String> all = host.messages();
		final List<String> shown = List.copyOf(all.subList(messagesSeen, all.size()));
		messagesSeen = all.size();
		return shown;
	}

	private List<String> newSounds() {
		final List<String> all = host.sounds();
		final List<String> played = List.copyOf(all.subList(soundsSeen, all.size()));
		soundsSeen = all.size();
		return played;
	}

	private List<String> newSpoken() {
		final List<String> all = host.spoken();
		final List<String> said = List.copyOf(all.subList(spokenSeen, all.size()));
		spokenSeen = all.size();
		return said;
	}

	// A shot on the scored region; the next series, if this one ended, starts at once (no delay)
	private void shootScored() {
		assertTrue(host.shoot(new Shot(ShotColor.RED, 0, 0, 0, 2), Optional.of(scoredRegionHit)));
		host.advance(Duration.ZERO);
	}

	private static String getScoreString(int roundOne, int roundTwo, int roundThree) {
		return String.format("150s score: %d\n20s score: %d\n10s score: %d\ntotal score: %d", roundOne, roundTwo,
				roundThree, roundOne + roundTwo + roundThree);
	}

	private void assertShown(int roundOne, int roundTwo, int roundThree, boolean roundOver, boolean gameOver) {
		assertEquals(List.of(getScoreString(roundOne, roundTwo, roundThree)), newMessages());

		if (gameOver) {
			assertEquals(List.of(ROUND_OVER), newSounds());
			assertEquals(List.of("Event over... Your score is 60"), newSpoken());
		} else if (roundOver) {
			assertEquals(List.of(ROUND_OVER, BEEP), newSounds());
		} else {
			assertEquals(List.of(), newSounds());
		}
	}

	private void shootFullSeries150s(int i) {
		// (regionScore * 5 * i) = last round's score
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 2, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 3, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 4, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 5, 0, 0, true, false);
	}

	@Test
	public void testFullRound() {
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());

		// 150s round 1-4
		for (int i = 0; i < 4; i++) {
			shootFullSeries150s(i);
		}

		// 20s round 1-4
		for (int i = 0; i < 4; i++) {
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 2, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 3, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 4, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 5, 0, true, false);
		}

		// 10s round 1-4
		for (int i = 0; i < 4; i++) {
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore, false, false);
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 2, false, false);
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 3, false, false);
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 4, false, false);

			final boolean gameOver = i == 3;
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 5, true, gameOver);
		}

		// The event is over: shot detection stays on, and no series starts
		assertFalse(host.isShotDetectionPaused());
		assertEquals(0, host.pendingTasks());
	}

	@Test
	public void testFull150sThenReset() {
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());

		// 150s round 1-4
		for (int i = 0; i < 4; i++) {
			shootFullSeries150s(i);
		}

		host.reset();
		assertEquals(List.of(""), newMessages());
		assertTrue(host.isShotDetectionPaused());

		host.advance(ISSFStandardPistol.START_DELAY);
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());
		shootScored();
		assertEquals(List.of(getScoreString(regionScore, 0, 0)), newMessages());
	}

	@Test
	public void aSeriesEndsAtItsTimeAndEachShotsRowSaysItsScoreAndSeries() {
		newSounds();
		shootScored();
		assertEquals(Map.of("Score", String.valueOf(regionScore), "Round", "R1 (150s)"),
				host.rows().get(0).values());

		host.advance(Duration.ofSeconds(149));
		assertEquals(List.of(), newSounds());
		host.advance(Duration.ofSeconds(1));
		assertEquals(List.of(ROUND_OVER, BEEP), newSounds());

		shootScored();
		assertEquals("R2 (150s)", host.rows().get(1).values().get("Round"));
		assertEquals(Optional.of(ISSFStandardPistol.SERIES_SHADING), host.rows().get(1).style());
	}

	@Test
	public void pauseAbandonsTheSeriesUnderWayAndResumeShootsItAgain() {
		newSounds();
		shootScored();
		shootScored();
		newMessages();

		host.click("Pause");
		assertEquals(List.of("Resume"), host.buttonLabels());
		assertTrue(host.isShotDetectionPaused());
		host.advance(Duration.ofSeconds(200));
		assertEquals(List.of(), newSounds());

		host.click("Resume");
		host.advance(ISSFStandardPistol.RESUME_DELAY);
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());

		// The same series from its start, without the points shot before the pause
		shootScored();
		assertEquals(List.of(getScoreString(regionScore, 0, 0)), newMessages());
		assertEquals("R1 (150s)", host.rows().get(host.rows().size() - 1).values().get("Round"));
	}
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt`:

Replace:

```kotlin

        assertTrue(app.feedTargets.animations.isOnFirstFrame(key))
    }

```

with:

```kotlin

        assertTrue(app.feedTargets.animations.isOnFirstFrame(key))
    }

    // A drill clears its message with an empty one (ISSF's Reset does): no empty banner
    @Test
    fun anEmptyDrillMessageShowsNoBanner() {
        app.openStartCamera()
        showApp()

        app.drill.setMessage("score: 0")
        compose.onNodeWithTag("banner-0").assertExists()
        app.drill.setMessage("")
        compose.onNodeWithTag("banner-0").assertDoesNotExist()
    }

```

`javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`:

Replace:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("RandomShoot", "ShootForScore", "TimedHolsterDrill", "ParForScore", "ParRandomShot",
				"ISSFStandardPistol", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 5));
		assertTrue(entries.subList(5, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
```

with:

```java
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("ISSFStandardPistol", "RandomShoot", "ShootForScore", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
		// The ported ones are the v2 exercises both apps list
		assertEquals(BuiltInRegistry.entries(), entries.subList(0, 6));
		assertTrue(entries.subList(6, entries.size()).stream().allMatch(LegacyExerciseEntry.class::isInstance));
	}
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `./gradlew :builtin-exercises:compileTestJava :compose-app:test --tests '*TestRangeScreen*' --console=plain`
Expected: `builtin-exercises` fails to compile (`ISSFStandardPistol` doesn't exist); `anEmptyDrillMessageShowsNoBanner` fails (an empty banner shows).

- [ ] **Step 4: Implement**

`builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java`:

Replace:

```java
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(RandomShoot.class, ShootForScore.class,
			TimedHolsterDrill.class, ParForScore.class, ParRandomShot.class);

	public static List<V2ExerciseEntry> entries() {
```

with:

```java
	private BuiltInRegistry() {}

	private static final List<Class<? extends Exercise>> EXERCISES = List.of(ISSFStandardPistol.class,
			RandomShoot.class, ShootForScore.class, TimedHolsterDrill.class, ParForScore.class, ParRandomShot.class);

	public static List<V2ExerciseEntry> entries() {
```

Create `builtin-exercises/src/main/java/com/shootoff/plugins/ISSFStandardPistol.java`:

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

package com.shootoff.plugins;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.RowStyle;
import com.shootoff.targets.model.Hit;

/**
 * The ISSF 25 m standard pistol event: four series of five shots in 150 s, then four in 20 s, then four in
 * 10 s. Each series starts with a beep and ends at its time or its fifth shot. Pause abandons the series
 * under way (its points come off) and Resume shoots it again after "make ready". It runs everywhere: on
 * the camera feed's targets and the arena's.
 */
public class ISSFStandardPistol implements Exercise {
	static final String SCORE_COL_NAME = "Score";
	static final String ROUND_COL_NAME = "Round";
	static final String PAUSE = "Pause";
	static final String RESUME = "Resume";
	static final Duration START_DELAY = Duration.ofSeconds(10);
	static final Duration RESUME_DELAY = Duration.ofSeconds(5);
	/** The shared delay controls' values when the event starts, as the JavaFX app's own controls had them */
	static final DelayRange DEFAULT_DELAY = new DelayRange(4, 8);
	static final RowStyle SERIES_SHADING = new RowStyle("lightgray");
	static final String MAKE_READY_SOUND = "sounds/voice/shootoff-makeready.wav";
	static final String BEEP_SOUND = "sounds/beep.wav";
	static final String ROUND_OVER_SOUND = "sounds/voice/shootoff-roundover.wav";
	private static final int[] ROUND_TIMES = { 150, 20, 10 };

	private final Random random;
	private ExerciseHost host;
	private ButtonHandle pauseResumeButton;
	private int roundTimeIndex = 0;
	private int round = 1;
	private int shotCount = 0;
	private int runningScore = 0;
	// The points of the series under way, which a pause takes off
	private int seriesScore = 0;
	private final Map<Integer, Integer> sessionScores = new HashMap<>();
	private int delayMin = DEFAULT_DELAY.minSeconds();
	private int delayMax = DEFAULT_DELAY.maxSeconds();
	// false while paused
	private boolean repeatExercise = true;
	private boolean coloredRows = false;
	private boolean seriesShaded = false;
	private boolean eventOver = false;
	// Make ready or the next series' start, and the end of the series under way
	private Optional<Cancellable> nextStep = Optional.empty();
	private Optional<Cancellable> endRound = Optional.empty();

	public ISSFStandardPistol() {
		this(new Random());
	}

	/**
	 * @param random
	 *            for tests, one with a known seed
	 */
	ISSFStandardPistol(Random random) {
		this.random = random;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("ISSF 25M Standard Pistol", "1.0", "phrack",
				"This exercise implements the ISSF event describe at: "
						+ "http://www.pistol.org.au/events/disciplines/issf. You "
						+ "can use any scored target with this exercise, but use "
						+ "the ISSF target for the most authentic experience.");
	}

	private void setInitialValues() {
		roundTimeIndex = 0;
		round = 1;
		shotCount = 0;
		runningScore = 0;
		seriesScore = 0;
		eventOver = false;

		for (final int time : ROUND_TIMES) {
			sessionScores.put(time, 0);
		}
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		setInitialValues();

		host.pauseShotDetection(true);
		host.setDelayedStart(DEFAULT_DELAY);
		host.onDelayedStartChanged(range -> {
			delayMin = range.minSeconds();
			delayMax = range.maxSeconds();
		});
		pauseResumeButton = host.addButton(PAUSE, this::pauseOrResume);
		host.addColumn(SCORE_COL_NAME);
		host.addColumn(ROUND_COL_NAME);

		scheduleNext(this::setupWait, START_DELAY);
	}

	private void setupWait() {
		if (!repeatExercise) return;

		host.playSound(MAKE_READY_SOUND);
		scheduleNext(this::startRound, Duration.ofSeconds(randomDelay()));
	}

	private void startRound() {
		shotCount = 0;
		seriesScore = 0;

		if (!repeatExercise) return;

		seriesShaded = coloredRows;
		coloredRows = !coloredRows;

		host.playSound(BEEP_SOUND);
		host.pauseShotDetection(false);
		endRound = Optional.of(host.schedule(this::endRound, Duration.ofSeconds(ROUND_TIMES[roundTimeIndex])));
	}

	private void endRound() {
		endRound = Optional.empty();
		if (!repeatExercise) return;

		host.pauseShotDetection(true);
		host.playSound(ROUND_OVER_SOUND);

		final int randomDelay = randomDelay();

		if (round < 4) {
			// Go to next round
			round++;
			scheduleNext(this::startRound, Duration.ofSeconds(randomDelay));
		} else if (roundTimeIndex < ROUND_TIMES.length - 1) {
			// Go to round 1 for next time
			round = 1;
			roundTimeIndex++;
			scheduleNext(this::startRound, Duration.ofSeconds(randomDelay));
		} else {
			host.say("Event over... Your score is " + runningScore);
			host.pauseShotDetection(false);
			// The event is over: Reset starts it again
			eventOver = true;
		}
	}

	private int randomDelay() {
		return random.nextInt((delayMax - delayMin) + 1) + delayMin;
	}

	private void scheduleNext(Runnable step, Duration delay) {
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.of(host.schedule(step, delay));
	}

	private void cancelPending() {
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.empty();
		endRound.ifPresent(Cancellable::cancel);
		endRound = Optional.empty();
	}

	private void pauseOrResume() {
		if (repeatExercise) {
			pauseResumeButton.setLabel(RESUME);
			repeatExercise = false;
			host.pauseShotDetection(true);
			if (endRound.isPresent()) abandonSeries();
			cancelPending();
		} else {
			pauseResumeButton.setLabel(PAUSE);
			repeatExercise = true;
			if (eventOver) {
				host.pauseShotDetection(false);
			} else {
				scheduleNext(this::setupWait, RESUME_DELAY);
			}
		}
	}

	// The series under way is shot again after the pause: its points come off
	private void abandonSeries() {
		final int time = ROUND_TIMES[roundTimeIndex];
		sessionScores.put(time, sessionScores.get(time) - seriesScore);
		runningScore -= seriesScore;
		seriesScore = 0;
		shotCount = 0;
		// It keeps its shading when shot again
		coloredRows = seriesShaded;
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		if (seriesShaded) host.styleLastRow(SERIES_SHADING);

		shotCount++;

		int hitScore = 0;

		if (hit.isPresent()) {
			final Optional<String> points = hit.get().region().tag("points");

			if (points.isPresent()) {
				hitScore = Integer.parseInt(points.get());
				sessionScores.put(ROUND_TIMES[roundTimeIndex], sessionScores.get(ROUND_TIMES[roundTimeIndex]) + hitScore);
				runningScore += hitScore;
				seriesScore += hitScore;
			}

			final StringBuilder message = new StringBuilder();

			for (final Integer time : ROUND_TIMES) {
				message.append(String.format("%ss score: %d\n", time, sessionScores.get(time)));
			}

			host.showMessage(message.toString() + "total score: " + runningScore);
		}

		final String currentRound = String.format("R%d (%ds)", round, ROUND_TIMES[roundTimeIndex]);
		host.setColumnValue(SCORE_COL_NAME, String.valueOf(hitScore));
		host.setColumnValue(ROUND_COL_NAME, currentRound);

		// The fifth shot ends the series early
		if (shotCount == 5 && endRound.isPresent()) {
			host.pauseShotDetection(true);
			endRound.get().cancel();
			endRound();
		}
	}

	@Override
	public void onReset() {
		host.pauseShotDetection(true);
		cancelPending();

		setInitialValues();
		seriesShaded = false;
		host.showMessage("");

		repeatExercise = true;
		pauseResumeButton.setLabel(PAUSE);
		scheduleNext(this::setupWait, START_DELAY);
	}

	@Override
	public void stop() {
		// The host cancels the pending steps and removes what the exercise added
		repeatExercise = false;
	}
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt`:

Replace:

```kotlin
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                failure?.let { BannerView(Banner(-1, it, BannerKind.ERROR), onDismiss = app.runner::dismissFailure) }
                message?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
            }
```

with:

```kotlin
            Column(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                failure?.let { BannerView(Banner(-1, it, BannerKind.ERROR), onDismiss = app.runner::dismissFailure) }
                // A drill clears its message with an empty one (ISSF's Reset does)
                message?.takeIf { it.isNotEmpty() }?.let { BannerView(Banner(0, it, BannerKind.INFO), onDismiss = { app.drill.setMessage(null) }) }
                FeedBanners(app.feed)
            }
```

`javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`:

Replace:

```java

/**
 * The exercises that ship with the JavaFX app: the ones ported to the v2 exercise API, which the Compose
 * app lists too ({@link BuiltInRegistry}), then the JavaFX ones still to port, in Training menu order.
 */
public final class BuiltInExercises {
```

with:

```java

/**
 * The exercises that ship with the JavaFX app, in Training menu order: the standard ones, on the v2 exercise
 * API, which the Compose app lists too ({@link BuiltInRegistry}), then the projector ones still to port.
 */
public final class BuiltInExercises {
```

Replace:

```java
	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(standard(new ISSFStandardPistol()), projector(new BouncingTargets()),
				projector(new DuelingTree()), projector(new ShootDontShoot()), projector(new SteelChallenge())));
		return entries;
	}

	private static ExerciseEntry standard(TrainingExercise exercise) {
		return new LegacyExerciseEntry(exercise, false);
	}

```

with:

```java
	public static List<ExerciseEntry> entries() {
		final List<ExerciseEntry> entries = new ArrayList<>(BuiltInRegistry.entries());
		entries.addAll(List.of(projector(new BouncingTargets()), projector(new DuelingTree()),
				projector(new ShootDontShoot()), projector(new SteelChallenge())));
		return entries;
	}

```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :builtin-exercises:test :javafx-app:test --tests '*BuiltInExercises*' :compose-app:test --tests '*TestRangeScreen*' --console=plain`
Expected: PASS.

- [ ] **Step 6: The gate, and the moved tests**

Run the gate. Expected: `1005/1005 passing; 0 regressions; 0 new failures`. Then:

Run: `python3 scripts/test_summary.py summarize builtin-exercises/build/test-results/test | command grep -E 'TestISSFStandardPistol\.test'`
Expected: exactly these two lines:

```
PASS com.shootoff.plugins.TestISSFStandardPistol.testFull150sThenReset
PASS com.shootoff.plugins.TestISSFStandardPistol.testFullRound
```

And no JavaFX standard exercise is left:

Run: `ls javafx-app/src/main/java/com/shootoff/plugins/`
Expected: `BouncingTargets.java BuiltInExercises.java DuelingTree.java ProjectorTrainingExerciseBase.java ShootDontShoot.java SteelChallenge.java TrainingExercise.java TrainingExerciseBase.java TrainingExerciseView.java engine`.

- [ ] **Step 7: Commit**

```bash
git add builtin-exercises/src/main/java/com/shootoff/plugins/ISSFStandardPistol.java builtin-exercises/src/main/java/com/shootoff/plugins/BuiltInRegistry.java builtin-exercises/src/test/java/com/shootoff/plugins/TestISSFStandardPistol.java builtin-exercises/src/test/java/com/shootoff/plugins/TestBuiltInRegistry.java compose-app/src/main/kotlin/com/shootoff/compose/app/RangeScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRangeScreen.kt javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java
git commit -m "Port the ISSF 25 m standard pistol event to the v2 exercise API"
git log -1 --format=%B
```

---

### Task 9: The owner's hardware check

Not for a subagent. The owner runs `./gradlew :compose-app:run` with the projector and the C270, calibrated, a camera target on the feed and a projected target on the wall (a scored one, e.g. `SimpleBullseye_score.target`, and one with subtargets, e.g. `SimpleBullseye_five_small.target`), and reports back.

- [ ] **1. The list.** Drills screen: the six are listed by name (ISSF 25M Standard Pistol, PAR Drill with Score, PAR Drill with a random Subtarget, Random Shoot, Shoot for Score, Timed Holster Drill) beside the owner's plugin.
- [ ] **2. Shoot for Score, everywhere.** Shoot the projected scored target: one row with its points, and "red score: N" in the feed's banner and at the wall's top left. Shoot the camera target: it scores too. Reset: "score: 0".
- [ ] **3. Random Shoot.** With the subtarget target on the wall (none on the feed): "shoot … and …" in the voice files; a miss repeats the one due; the right hits in order call a new sequence. Remove the target on the Targets screen: the warning plays.
- [ ] **4. Timed Holster, then PAR Drill with Score.** 10 s, "make ready", the beep after the delay; each shot's Length is its draw time; rounds alternate grey rows. Pause: nothing more until Resume; Resume: "make ready" 5 s later. PAR drill: the chime at the par time; **the first round scores** (ruling 22 — the JavaFX app didn't count it: say if that should stay as it was).
- [ ] **5. PAR Drill with a random Subtarget.** The call-out replaces the beep; only hits on the called subtarget score (1 point each on the five-small target).
- [ ] **6. ISSF.** "make ready", the beep, a series ends at its fifth shot ("round over", then the next beep after the delay); the banner lists 150s/20s/10s and the total. Pause mid-series and Resume: the series is shot again. Reset: the banner goes, and it starts afresh.
- [ ] **7. Calibrating mid-drill** (F6) during Timed Holster: the drill pauses (its button reads Resume) and stays the same drill after calibrating.
- [ ] **8. The JavaFX app** (optional): `./gradlew :javafx-app:run`, Training menu: the six are there, first, and Shoot for Score scores a projected target and a camera target.
- [ ] **9. The files.** `sha256sum -c build/plan8-owner-files.sha256` still prints OK for every line.
