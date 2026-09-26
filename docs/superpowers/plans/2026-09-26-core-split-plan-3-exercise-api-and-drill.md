# Core Split Plan 3: Exercise API, Plugin Engine and the Drill Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give ShootOFF a UI-neutral exercise API (`plugin-api`), move the plugin engine into `core` with v1 and v2 loaders, host v2 exercises in the JavaFX app, publish the modules, and port RandomTargetParDrill to the new API in its own repository, while v1 plugins and the ten built-in exercises keep working.

**Architecture:**
- **Task 1: test hygiene.** The three tests that scan `targets/` and `courses/` check an explicit list of bundled files, so the owner's own files no longer break the gate.
- **Tasks 2–3: `plugin-api`** (package `com.shootoff.exercise`):
  - `Exercise`, `ExerciseHost` and its handles and value types.
  - `ExerciseExecutor`: the one thread per running exercise that every callback runs on.
  - `FakeExerciseHost` in the module's test fixtures: a manual clock, recorded output, and injected shots that are hit-tested against the exercise's own targets.
  - `ExerciseMetadata` moves to `core` (same package and name, so v1 jars still bind to it) and gains a projector-only flag.
- **Task 4: the plugin engine in `core`.**
  - `core` finds jars, reads `shootoff.xml` (now with `apiVersion`), de-duplicates by name and creator, and watches the folder.
  - It hands each class to an `ExerciseLoader` for the descriptor's API version. The v2 loader is in `plugin-api`, the v1 (legacy) loader in `javafx-app`.
  - The app passes the ten built-in exercises in.
- **Tasks 5–6: hosting in the JavaFX app.**
  - Groundwork (Task 5): the shared par/delay controls become a reusable pane, `TargetView` applies off-thread moves on the JavaFX thread, v1 `Hit`s carry the model hit, and shot-timer rows can be recolored.
  - `JavaFxExerciseHost` (Task 6) implements `ExerciseHost` for the arena or a camera feed. `HostedExercise` adapts it to the app's existing `TrainingExercise` dispatch, so shots, target changes, resets and exercise switching reach v2 exercises through the same code paths as v1.
- **Task 7: publishing.**
  - `core`, `plugin-api` (with its test-fixtures variant) and the JavaFX app are published to `build/m2`.
  - The app's POM now exposes `core`.
  - A plugin-author guide records the API and every v1 type change.
- **Tasks 8–9: the drill port**, on a new `plugin-api-v2` branch of `/home/bfears/projects/RandomTargetParDrill`:
  - `RandomTargetParDrill` implements `Exercise`, tested with `FakeExerciseHost`.
  - The first v2 run copies the owner's personal bests into the exercise's data directory.
  - The v2 jar is installed next to the untouched v1 jar.
- **Task 10: owner check.** A v1 binary-compatibility check, then the owner's hardware check: calibration, the full v2 drill, a v1 exercise, the v1 drill, and a session recorded and replayed.

**Tech Stack:** Java 21 toolchain (records, sealed interfaces, pattern matching), Gradle 8.14 wrapper (Kotlin DSL) with `java-library`, `java-test-fixtures` and `maven-publish`, OpenJFX 21 (javafx-app only), `java.util.concurrent` (`ScheduledThreadPoolExecutor`), `javax.tools` (test plugin jars), JUnit 5 + JUnit 4 (vintage).

**Spec:** `docs/superpowers/specs/2026-09-26-core-split-and-exercise-api-design.md`. This plan is §7 "Plan 3", steps 1–6, with the exercise API of §4, the hosting of §5, the drill port of §6 and the tests of §8. Plans 1 and 2 (`docs/superpowers/plans/2026-09-26-core-split-plan-1-modules-and-camera.md`, `…-plan-2-targets-sessions-courses.md`) are done.

## Plan-author rulings

Where the spec is silent or disagrees with the code, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

1. **The `Hit` in `Exercise.onShot(Shot, Optional<Hit>)` is the model's `com.shootoff.targets.model.Hit`** (`TargetId`, `Region`, impact in target coordinates). It is already UI-neutral and exactly what §4 describes. A plugin-api copy would duplicate it. *Cost if wrong:* the model record becomes published API, so changing its components later breaks v2 plugins.
2. **The v2 API lives in package `com.shootoff.exercise`.** `ExerciseMetadata` stays `com.shootoff.plugins.ExerciseMetadata`, because the v1 drill jar binds to that name. It moves (`git mv`) from javafx-app to `core`, because the engine in `core` needs it for de-duplication. It gains `ExerciseMetadata(name, version, creator, description, boolean projectorOnly)` and `isProjectorOnly()`: the Training menu must know where to list a v2 exercise before running it. v1 exercises keep getting their placement from their superclass. *Cost:* an extra field in a class v1 authors see (additive only).
3. **Where the loaders live.** §7 says "v2 loading moves to `core`", but `core` can't see `Exercise`: `plugin-api` depends on `core`, not the other way. So:
   - `core` has the engine: jar discovery, the descriptor (`PluginDescriptor`), de-duplication, the watcher, and an `ExerciseLoader` interface keyed by `apiVersion`.
   - The v2 loader (`V2ExerciseLoader`) is in `plugin-api`, and the v1 loader (`LegacyExerciseLoader`) in `javafx-app`.
   - The app passes both loaders and its built-in exercises to the engine.

   *Cost:* 1b's Compose host must pass the v2 loader itself (one line).
4. **The Training menu keeps its order and its de-duplication.**
   - Order: standard built-ins, then plugin jars, then projector built-ins.
   - De-duplication: two exercises with the same name and creator are the same exercise, and the newer version wins, across API versions too.
   - Unregistering now removes a projector exercise's button from the projector pane. Before, it only searched the universal pane, so a superseded projector plugin stayed listed.

   *Cost:* none known; the rule is today's.
5. **The v2 drill is installed next to the v1 jar**, as `exercises/RandomTargetParDrill-v2.jar`, and `exercises/RandomTargetParDrill.jar` is never modified.
   - The v2 drill keeps the name "Random Target PAR Drill with Score" and the creator, with version `2.0`. By ruling 4, the menu then lists the drill once, as v2.
   - The owner check (Task 10) plays the v1 jar by starting ShootOFF with the v2 jar moved aside.

   *Cost:* while both jars are installed, the v1 drill can't be picked from the menu.
6. **`addTarget` returns `Optional<TargetHandle>`** instead of the spec's `TargetHandle`. A missing or malformed target file is empty, as v1's `addTarget` was. *Cost:* none; an exercise that must have its target calls `orElseThrow`.
7. **Four additions to the spec's `ExerciseHost` table:**
   - **`setParTime(double)` and `setDelayedStart(DelayRange)`.** Without them the shared controls start at the app's defaults (par 2.0 s, delay 4–8 s). The drill's defaults are par 4.0 s and delay 5–8 s, and the owner's personal bests are keyed by the 4.00 s par. v1's drill displayed 2.0 while using 4.0 until the field was edited. An exercise's own `set…` calls don't call its own listeners; only the user's edits do.
   - **`long currentTimeMillis()`.** The host's clock: `System.currentTimeMillis()` in the app, and the manual clock in `FakeExerciseHost`. The drill measures shot times with it, so tests can check them exactly.
   - **`showShotMarker` returns a `ShotMarkerHandle`** with `remove()`. The drill hides each round's markers between rounds while the shot timer keeps its rows. `clearShots()` would also clear the rows.

   *Cost:* a larger API surface to keep stable.
8. **Threading (§4).**
   - `ExerciseExecutor` (plugin-api) is one daemon thread per running exercise, named `Exercise: <name>`. Callbacks run in submission order. A callback that throws is logged, and the thread and any repeating task carry on.
   - `JavaFxExerciseHost.stop()`:
     1. Runs the callbacks already queued, then `Exercise.stop()`.
     2. Cancels every scheduled task.
     3. Waits up to 2 s for the exercise thread.
     4. Queues the removal of everything the exercise added.
   - After `stop()`, host calls change nothing: the executor rejects tasks, and scene changes queued after the removal are dropped.
   - No host method waits for the JavaFX thread, so the JavaFX thread may wait for the exercise thread.

   *Cost:* an exercise whose `stop()` blocks for more than 2 s is interrupted.
9. **Which shots reach a v2 exercise.**
   - A projector host delivers only `ArenaShot`s, in arena coordinates. A camera host delivers only camera shots. v1 projector exercises also received camera shots outside the projection, in camera coordinates, and the v1 drill cast them to `ArenaShot`.
   - A camera host draws on the first camera's feed.
   - `showMessage` shows on every camera feed and on the arena tab, as v1's `showTextOnFeed` did. It is not shown in the arena window; `showText` is for that.
   - A hit may be on a target the exercise didn't add.

   *Cost:* a v2 camera exercise can't place targets on a second camera's feed.
10. **`TargetView` applies model changes made off the JavaFX thread on the JavaFX thread** when its group is in a scene. The model itself still changes synchronously, so `position()` reads back at once. Groups outside a scene are still updated in place: the session viewer's replay and several tests rely on that. v1 exercises that move targets from their own threads benefit too. *Cost:* for a moment the drawn target lags its model.
11. **Resolving names.**
    - A target, sound or resource name is first looked up in the exercise's own jar (`URLClassLoader.findResource`, never the app's classpath), with any leading `@` or `/` dropped.
    - Otherwise it is a file: absolute, or relative to the ShootOFF home, or relative to the home's `targets/` (targets) or `sounds/` (sounds).
    - `setBackground` looks in the exercise's jar, then in ShootOFF's bundled resources (e.g. `arena/backgrounds/…`).
    - `stop()` restores the arena's previous background. v1 exercises left theirs behind.

    *Cost:* a jar resource shadows a same-named ShootOFF file.
12. **`dataDirectory()` is `<shootoff.home>/exercise-data/<exercise class name>/`**, created on first use and ignored by git. The drill's first v2 run **copies** `<shootoff.home>/RandomTargetParDrill-bests.properties` there, and only when the copy doesn't exist yet. It never moves, deletes or overwrites either file, so the v1 jar keeps its own bests. *Cost:* bests set with v2 don't show up in v1 and the other way round.
13. **A par miss no longer adds a coral shot-timer row.**
    - v1 made the row by injecting a fake red shot at (−10, −10) into the arena canvas. That shot was hit-tested, sent back to the drill's own shot listener, and written to session files as a real shot.
    - The v2 drill records the par miss itself, shows "Par missed!", and counts it in the summary.
    - `styleLastRow` still exists for exercises that color real rows.

    *Cost:* the owner loses the coral row. A host method `addTimerRow(RowStyle)` would restore it.
14. **Drill pause/resume.** Pause cancels the pending round start, and resume replaces it. v1 only flagged the pending round. A pause and resume within the start delay then ran two round chains at once. *Cost:* none known.
15. **Published coordinates.**
    - The JavaFX app stays `com.shootoff:shootoff`, not the spec's `com.shootoff:javafx-app`, so the v1 drill's `master` build keeps resolving.
    - The app applies `java-library` with `api(project(":core"))` and `api(project(":plugin-api"))`. This is Plan 1's deferred fix: the POM lists `core` at compile scope.
    - `plugin-api`'s fixtures publish as capability `com.shootoff:plugin-api-test-fixtures`. The drill requests them by capability with `isTransitive = false`, instead of the spec's `testFixtures(...)`, because `core`'s transitive MaryTTS dependencies aren't on Maven Central.

    *Cost:* the spec's coordinate name for the app isn't used.
16. **The bundled-file tests read an explicit list** (`com.shootoff.BundledFiles` in `core`'s test fixtures) instead of walking `targets/` and `courses/`. *Cost:* adding a bundled target means adding it to the list. The count assertion (26 targets, 8 courses) points there.
17. **Timing controls.** A minimum above the maximum is ignored for v2 exercises (v1 passed it on, and the drill's `Random.nextInt` threw). v1's own text-field parsing is moved unchanged (`TimingControlsPane`).
18. **Not fixed here:** a target command `play_sound(@…)` on a target of a v2 exercise finds nothing. `TargetCommands` resolves `@` sounds against the running exercise's class, which for v2 is the app's adapter. The drill's target has no commands.
19. **Order within the spec's steps.** The boundary test for `plugin-api` (spec step 3) lands in Task 2, with the module's first classes, so it guards them from the start. The rest follows §7's order.

Nothing in the spec's Plan 3 steps is infeasible. Rulings 3, 6, 7 and 15 are the places where the plan departs from the spec's text.

## Global Constraints

- **Two repositories:**
  - **ShootOFF**: `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Tasks 1–7 and 10 run here. Never switch branches and never commit to `master`.
  - **RandomTargetParDrill**: `/home/bfears/projects/RandomTargetParDrill`. Tasks 8–9 run here, on branch `plugin-api-v2`, which Task 8 creates from `master`. Never commit to its `master`.
  - Never push either repository.
- No `Co-Authored-By` trailer in commit messages. Verify after every commit with `git log -1 --format=%B`.
- Never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- Use `git mv` for every move or rename so history follows. Never delete and re-create a moved file.
- Code style: tabs; match surrounding conventions.
  - Every **new main-source** Java file in ShootOFF starts with the GPL header, copied verbatim from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file).
  - New test files follow the repo's test style, which has no header.
  - The drill's files have no header; new drill files follow that.
- Package names stay `com.shootoff.*`. A package may be split across modules: the app runs on the classpath, not as JPMS modules.
- `core` and `plugin-api` must not depend on OpenJFX. `TestNoJavaFxInCore` and `TestNoJavaFxInPluginApi` (Task 2) enforce it: no compiled class in `core`, `plugin-api` or `plugin-api`'s test fixtures may reference `javafx.*`.
- Unchanged formats: `.target`, `.course`, the session formats (XML/JSON), `shootoff.properties`, and v1 `shootoff.xml` descriptors. A descriptor without `apiVersion` is v1.
- Existing test classes and methods keep their package, class and method names, because the baseline compares tests by `class.method`. A test whose body must change for a new signature keeps its name.
- No new third-party dependencies. The version catalog stays as it is. The drill's build drops OpenJFX and adds only `com.shootoff` artifacts.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- `javafx-app/src/test/resources/targets/hit-parity.txt` is never hand-edited.
- **The owner's files.** Never modify, move or delete:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - `exercises/RandomTargetParDrill.jar`
  - any file the owner added under `targets/` or `courses/` (for example `targets/TEST_TARGET.target`, `courses/TEST_COURSE.course`)
- **Test gate** (unchanged from Plans 1 and 2; run it in ShootOFF):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  It must print `0 regressions; 0 new failures`. A full run takes several minutes, so use a Bash timeout of 600000 ms. The `N/M passing` count starts at **305** (end of Plan 2). Each ShootOFF task states "passing = previous + N", where N is the number of test methods the task adds. A lower count means tests silently stopped running.
- **v1 plugins keep loading and running.** These stay source- and binary-compatible:
  - the public API of `com.shootoff.targets.Target`, `TargetRegion` and its four region classes, and `Hit`
  - `TrainingExercise`, `TrainingExerciseBase`, `ProjectorTrainingExerciseBase`, `ExerciseMetadata`
  - every member in "The v1 surface" below

  The installed `exercises/RandomTargetParDrill.jar` and the ten built-in exercises must keep working. Task 10 Step 1 checks the drill's members with `javap`.
- **Publishing and the drill's builds.**
  - Publish only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`.
  - Every drill build passes the same `-Dmaven.repo.local=…`, after `./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2` in ShootOFF.

## The v1 surface

Plan 2's list stands. `javap -c -p` of the installed `exercises/RandomTargetParDrill.jar` shows these references:
- `targets.Target`: `getDimension`, `getPosition`, `setPosition(DD)V`, `setVisible(Z)V`
- `targets.TargetRegion`: `getTag`, `tagExists`. The drill casts the region to `javafx.scene.Node`.
- `targets.Hit`: `getHitRegion`, `getImpactX`, `getImpactY`, `getShot`
- `gui.CanvasManager`: `getCanvasGroup`, `addShot(DisplayShot, boolean)`
- `gui.pane.ProjectorArenaPane.getCanvasManager`, `gui.LocatedImage.<init>(InputStream, String)`
- implements `gui.ParListener` and `gui.DelayedStartListener`
- `ProjectorTrainingExerciseBase`: `<init>()`, `<init>(List)`, `addTarget(File, double, double)`, `destroy`, `getArenaWidth`, `getArenaHeight`
- `TrainingExerciseBase`: `clearShots`, `pauseShotDetection`, `playSound(File)`, `playSound(InputStream)`
- `plugins.TrainingExercise` (the callbacks it implements), `plugins.ExerciseMetadata.<init>(4 × String)`
- `camera.Shot`, `ShotColor`, `DisplayShot`, `ArenaShot`, `Configuration.getMarkerRadius`, `NamedThreadFactory`

The ten built-ins use the inherited base-class methods listed in Plan 2, and `SteelChallenge.init(List<Target>)`.

**This plan touches the v1 surface only additively:**
- `ExerciseMetadata` moves to `core`. Its binary name, constructor and getters are unchanged, and it gains a constructor and `isProjectorOnly()`.
- `Hit` gains `Hit(Target, TargetRegion, int, int, com.shootoff.targets.model.Hit)` and `getModelHit()`.
- `ShotEntry` gains `withRowColor`.
- `TrainingExerciseBase`'s private `DelayPane` becomes the public `TimingControlsPane`, with identical controls and behavior.

**Sanctioned breaks.** Neither the drill nor any built-in uses these. The built-ins and the app are adapted in-tree.
1. `com.shootoff.plugins.engine` (Task 4) moves to `core`:
   - `PluginListener` takes `ExerciseEntry` instead of `TrainingExercise`.
   - `Plugin(Path)` becomes `Plugin(Path, List<ExerciseLoader>)`, and `getExercise()` becomes `getEntry()`. `getLoader()`, `getJarPath()` and `getType()` stay.
   - `PluginEngine(PluginListener)` becomes `PluginEngine(PluginListener, List<ExerciseLoader>, List<ExerciseEntry>)`, and `getPlugin(TrainingExercise)` becomes `getPlugin(ExerciseMetadata)`.
2. `ExerciseSlide`'s `PluginListener` methods take `ExerciseEntry` (Task 4).

Plans 1 and 2 changed further v1 types that plugins can see, beyond their sanctioned lists. Task 7 records all of them for plugin authors in `docs/plugin-api.md`:
- `Settings.getIgnoreLaserColor()` returns `Optional<ShotColor>`
- `CameraManager.getCurrentFrame()` returns `BufferedImage`
- `CameraManager.startRecordingShots(Optional<String>)`
- projection bounds, perspective and calibration take `Rect`/`Size`/`Point`
- `Settings.getArenaPosition()` returns `Optional<Point>`
- `CalibrationOption` moved from `com.shootoff.gui` to `com.shootoff.config`, and `Settings.getCalibratedFeedBehavior()` returns it

## Review Focus

1. **The user switches exercises while a v2 exercise is mid-callback** (for example picking "None" while the drill ends a round, or starting calibration). The expected result:
   - No label, button, spinner, timing pane, column, marker, target or background of the old exercise remains.
   - No callback runs after `stop()`, and later host calls change nothing.

   *Tests:*
   - `TestExerciseExecutor.shutdownRunsQueuedCallbacksThenTheLastOneAndCancelsScheduledTasks`, `callsAfterShutdownAreIgnored` (Task 2)
   - `TestFakeExerciseHost.stopRemovesEverythingAndCancelsTasks` (Task 3)
   - `TestJavaFxExerciseHost.stopRemovesEverythingAndCancelsScheduledTasks` (Task 6)
2. **Calibration while the v2 drill runs.** Calibration must stop the drill, as it stops v1 projector exercises, and restart a fresh drill afterwards. That requires the app to recognize a hosted projector exercise. *Tests:* `TestHostedExercise.hostedExercisesAreProjectorExercisesWhenTheirMetadataSaysSo` (Task 6); the owner check (Task 10 Step 2, item 7).
3. **v1 and v2 of the same exercise installed together.** The menu must list it once (the newer), and must not keep a stale button for the superseded projector exercise. *Tests:* `TestPluginEngineRegistration.newerVersionOfTheSameExerciseWins`, `TestExerciseSlide.unregisteringAProjectorExerciseRemovesItsButton` (Task 4); the owner check (Task 10 Step 2, item 2).
4. **The owner's personal bests.** The legacy file must never be lost or overwritten, a v2 bests file must never be replaced, and the first v2 run must compare against the old best. *Tests:* `TestBestsFile` (four tests) and `TestRandomTargetParDrill.theFirstV2RunCountsTheV1PersonalBest` (Task 9); checksums in Task 9 Step 1 and Task 10 Step 5.
5. **A broken v2 plugin or exercise:**
   - a class that isn't an `Exercise`
   - an unsupported `apiVersion`
   - a jar without its own descriptor while ShootOFF's classpath has one
   - an exercise callback that throws

   The expected result: the jar is skipped with a log line, and the rest of the menu still loads. A throwing callback doesn't stop later callbacks or repeating tasks. *Tests:*
   - `TestPluginLoading.v2ClassThatIsNotAnExerciseIsRejected`, `descriptorOnTheClasspathIsIgnored`
   - `TestPluginEngineRegistration.jarWithUnsupportedApiVersionIsSkipped`
   - `TestPluginDescriptor.descriptorOnTheParentClassPathIsIgnored`
   - `TestExerciseExecutor.aThrowingCallbackDoesNotStopLaterOnesOrItsRepeats`

The owner's check (Task 10) covers what no unit test reaches: projector and webcam hardware, the real menus and panes, sounds, calibration, and a session recorded and replayed.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/src/testFixtures/java/com/shootoff/BundledFiles.java`; `TestTargetDefinitions`, `TestCourseIO`, `TestHitParity` | bundled-file lists | 1 |
| `core/.../plugins/ExerciseMetadata.java` (moved from javafx-app) | metadata with the projector flag | 2 |
| `plugin-api/src/main/java/com/shootoff/exercise/{Exercise,ExerciseHost,TargetHandle,TextHandle,TextStyle,ButtonHandle,RowStyle,ShotStyle,ShotMarkerHandle,Cancellable,DelayRange,ExerciseExecutor,ExercisePaths}.java` | the v2 API and its thread | 2 |
| `core/src/testFixtures/java/com/shootoff/JavaFxReferenceScanner.java` (moved from core tests) | boundary scanner shared with plugin-api | 2 |
| `plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java` | the test host | 3 |
| `core/.../plugins/engine/{PluginEngine,Plugin,PluginType,PluginListener}.java` (moved), `{PluginDescriptor,ExerciseEntry,ExerciseLoader}.java` | the engine in core | 4 |
| `plugin-api/.../plugins/engine/{V2ExerciseLoader,V2ExerciseEntry}.java` | v2 loading | 4 |
| `javafx-app/.../plugins/engine/{LegacyExerciseLoader,LegacyExerciseEntry,ExerciseLoaders}.java`, `plugins/BuiltInExercises.java` | v1 loading, built-ins | 4 |
| `core/src/testFixtures/java/com/shootoff/plugins/engine/PluginJars.java` | builds test plugin jars | 4 |
| `javafx-app/.../gui/TimingControlsPane.java`; `TargetView`, `targets/Hit`, `gui/ShotEntry`, `TrainingExerciseBase` | hosting groundwork | 5 |
| `javafx-app/.../gui/exercise/{JavaFxExerciseHost,ExerciseHostContext,HostedExercise,SoundOutput}.java`; `ExerciseSlide`, `ShootOFFController`, `CalibrationManager`, `ProjectorSlide`, `.gitignore` | v2 hosting | 6 |
| `javafx-app/build.gradle.kts`, `docs/plugin-api.md` | publishing, plugin-author guide | 7 |
| drill repo: `build.gradle.kts`, `src/main/resources/shootoff.xml`, `src/main/java/com/shootoff/plugins/RandomTargetParDrill.java`, `src/test/java/com/shootoff/plugins/TestRandomTargetParDrill.java`; `RoundLimitListener.java` deleted | the port | 8 |
| drill repo: `src/main/java/com/shootoff/plugins/BestsFile.java`, `src/test/java/com/shootoff/plugins/TestBestsFile.java` | bests migration | 9 |

---
### Task 1: Bundled-file tests check a fixed list

Runs in ShootOFF on `compose-ui`.

**Files:**
- Create: `core/src/testFixtures/java/com/shootoff/BundledFiles.java`
- Modify: `core/src/test/java/com/shootoff/targets/model/TestTargetDefinitions.java` (`everyBundledTargetParses`, imports)
- Modify: `core/src/test/java/com/shootoff/courses/io/TestCourseIO.java` (`testEveryBundledCourseParses`, imports)
- Modify: `javafx-app/src/test/java/com/shootoff/targets/TestHitParity.java` (`bundledTargets()`, imports)

**Interfaces:**
- Consumes: nothing new.
- Produces: `com.shootoff.BundledFiles` (core test fixtures, visible to core and javafx-app tests):
  - `static final List<String> TARGETS` (26 paths), `COURSES` (8 paths)
  - `static List<Path> targets()`, `static List<Path> courses()`: the listed files, in list order. They throw `IllegalStateException` naming a listed file that is missing.

- [ ] **Step 1: Reproduce the failure with a stray user file**

```bash
cd /home/bfears/projects/ShootOFF
printf '<target>\n' > targets/zz_plan3_stray.target
printf '<course>\n' > courses/zz_plan3_stray.course
./gradlew :core:test --tests 'com.shootoff.targets.model.TestTargetDefinitions' --tests 'com.shootoff.courses.io.TestCourseIO' :javafx-app:test --tests 'com.shootoff.targets.TestHitParity.modelMatchesRecordedExpectations' --continue --console=plain 2>&1 | command grep -E "FAILED|tests completed"
```

Expected: `everyBundledTargetParses FAILED`, `testEveryBundledCourseParses FAILED` and `modelMatchesRecordedExpectations FAILED`. Keep the two stray files until Step 4.

- [ ] **Step 2: Add the list**

`core/src/testFixtures/java/com/shootoff/BundledFiles.java` (test fixtures take no GPL header):

```java
package com.shootoff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The target and course files that ship with ShootOFF. Tests that check every bundled file use these
 * lists instead of walking targets/ and courses/, where users keep files of their own. Add a file here
 * when it is added to the repository.
 */
public final class BundledFiles {
	public static final List<String> TARGETS = List.of(
			"targets/AQT_Silhouette.target",
			"targets/Chicken_Silhouette.target",
			"targets/Duel_Tree.target",
			"targets/IPSC.target",
			"targets/IPSC_Classic_Falling_Popper.target",
			"targets/IPSC_Classic_Popper.target",
			"targets/ISSF.target",
			"targets/Musical_Target.target",
			"targets/POI_Offset_Adjustment.target",
			"targets/Pepper_Popper.target",
			"targets/Pig_Silhouette.target",
			"targets/Plate_Rack.target",
			"targets/Plate_Rack_Silhouette.target",
			"targets/Ram_Silhouette.target",
			"targets/Reset.target",
			"targets/SimpleBullseye_five_small.target",
			"targets/SimpleBullseye_score.target",
			"targets/Steel_Challenge_Circle.target",
			"targets/Steel_Challenge_Rectangle.target",
			"targets/Steel_Challenge_Stop_Circle.target",
			"targets/Steel_Challenge_Stop_Rectangle.target",
			"targets/Swedish_Soldier.target",
			"targets/Turkey_Silhouette.target",
			"targets/USPSA.target",
			"targets/shoot_dont_shoot/dont_shoot.target",
			"targets/shoot_dont_shoot/shoot.target");

	public static final List<String> COURSES = List.of(
			"courses/steel_challenge/accelerator.course",
			"courses/steel_challenge/five_to_go.course",
			"courses/steel_challenge/outerlimits.course",
			"courses/steel_challenge/pendulum.course",
			"courses/steel_challenge/roundabout.course",
			"courses/steel_challenge/showdown.course",
			"courses/steel_challenge/smoke_and_hope.course",
			"courses/steel_challenge/speed_option.course");

	private BundledFiles() {}

	public static List<Path> targets() {
		return existing(TARGETS);
	}

	public static List<Path> courses() {
		return existing(COURSES);
	}

	private static List<Path> existing(List<String> names) {
		final List<Path> paths = new ArrayList<>();

		for (final String name : names) {
			final Path path = Paths.get(name);
			if (!Files.isRegularFile(path)) {
				throw new IllegalStateException(name + " is listed in BundledFiles but doesn't exist");
			}
			paths.add(path);
		}

		return paths;
	}
}
```

The list is `git ls-files 'targets/*.target' 'courses/*.course'` at `e14089e5`, in that order.

- [ ] **Step 3: Use it in the three tests**

In `TestTargetDefinitions`, replace the first lines of `everyBundledTargetParses`:

```java
	@Test
	void everyBundledTargetParses() throws IOException, TargetFormatException {
		final List<Path> files = BundledFiles.targets();

		assertEquals(26, files.size());
```

The rest of the method is unchanged. Add `import com.shootoff.BundledFiles;` and delete `import java.util.stream.Collectors;` and `import java.util.stream.Stream;`, which are now unused.

In `TestCourseIO`, replace the first lines of `testEveryBundledCourseParses`:

```java
	@Test
	public void testEveryBundledCourseParses() throws IOException {
		final List<Path> files = BundledFiles.courses();

		assertEquals(8, files.size());
```

The rest of the method is unchanged. Add `import com.shootoff.BundledFiles;` and delete the now-unused imports `java.nio.file.Files`, `java.nio.file.Paths`, `java.util.stream.Collectors` and `java.util.stream.Stream`.

In `TestHitParity`, replace `bundledTargets()`:

```java
	private static List<Path> bundledTargets() throws IOException {
		return BundledFiles.targets();
	}
```

Add `import com.shootoff.BundledFiles;` and delete `import java.util.stream.Stream;`. `Collectors`, `Files` and `Paths` are still used there.

- [ ] **Step 4: Run the tests with the stray files, then remove them**

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :core:test --tests 'com.shootoff.targets.model.TestTargetDefinitions' --tests 'com.shootoff.courses.io.TestCourseIO' :javafx-app:test --tests 'com.shootoff.targets.TestHitParity.modelMatchesRecordedExpectations' --continue --console=plain 2>&1 | command grep -E "FAILED|BUILD"
rm targets/zz_plan3_stray.target courses/zz_plan3_stray.course
```

Expected: no `FAILED`, and `BUILD SUCCESSFUL`. Only the two `zz_plan3_stray` files are removed; any other file of the owner's stays.

- [ ] **Step 5: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 0 (**305**).

- [ ] **Step 6: Commit**

```bash
git add core/src/testFixtures/java/com/shootoff/BundledFiles.java core/src/test/java/com/shootoff/targets/model/TestTargetDefinitions.java \
  core/src/test/java/com/shootoff/courses/io/TestCourseIO.java javafx-app/src/test/java/com/shootoff/targets/TestHitParity.java
git status --short
git commit -m "Check bundled targets and courses against a fixed list so user files don't break the tests"
git log -1 --format=%B
```

`git status --short` must show only ` M shootoff.properties` (and `??` lines for owner files, if any) besides the four staged files.

---
### Task 2: The exercise API and its thread in `plugin-api`

Runs in ShootOFF on `compose-ui`.

**Files:**
- Move: `git mv javafx-app/src/main/java/com/shootoff/plugins/ExerciseMetadata.java core/src/main/java/com/shootoff/plugins/ExerciseMetadata.java`, then modify it.
- Move: `git mv core/src/test/java/com/shootoff/JavaFxReferenceScanner.java core/src/testFixtures/java/com/shootoff/JavaFxReferenceScanner.java`, then modify it.
- Create in `plugin-api/src/main/java/com/shootoff/exercise/`: `Exercise.java`, `ExerciseHost.java`, `TargetHandle.java`, `TextHandle.java`, `TextStyle.java`, `ButtonHandle.java`, `RowStyle.java`, `ShotStyle.java`, `ShotMarkerHandle.java`, `Cancellable.java`, `DelayRange.java`, `ExerciseExecutor.java`, `ExercisePaths.java`
- Modify: `plugin-api/build.gradle.kts`
- Test: `core/src/test/java/com/shootoff/plugins/TestExerciseMetadata.java`
- Test in `plugin-api/src/test/java/com/shootoff/exercise/`: `TestExerciseExecutor.java`, `TestExercisePaths.java`, `TestExerciseValues.java`, `TestNoJavaFxInPluginApi.java`

**Interfaces:**
- Consumes: `com.shootoff.camera.Shot`, `com.shootoff.camera.shot.ShotColor`, `com.shootoff.geom.{Point,Size}`, `com.shootoff.targets.model.{Hit,TargetDefinition,TargetId}` (core).
- Produces:
  - `com.shootoff.plugins.ExerciseMetadata` (core), with a new constructor `(String name, String version, String creator, String description, boolean projectorOnly)` and `boolean isProjectorOnly()`. The 4-argument constructor means not projector-only.
  - `interface Exercise`: `ExerciseMetadata metadata()`, `void start(ExerciseHost)`, `void onShot(Shot, Optional<Hit>)`, `default void onTargetsChanged(List<TargetHandle>)`, `void onReset()`, `void stop()`
  - `interface ExerciseHost`:
    - Surface: `Size surfaceSize()`, `boolean isProjector()`, `void setBackground(String)`
    - Targets: `Optional<TargetHandle> addTarget(String, double, double)`, `List<TargetHandle> targets()`
    - Text: `TextHandle showText(String, double, double, TextStyle)`, `void showMessage(String)`
    - Controls: `ButtonHandle addButton(String, Runnable)`, `void addNumberSetting(String, double initial, double min, double max, double step, DoubleConsumer)`
    - Shot timer: `void addColumn(String)`, `void setColumnValue(String, String)`, `void styleLastRow(RowStyle)`
    - Shots: `ShotMarkerHandle showShotMarker(double, double, ShotStyle)`, `void clearShots()`, `void pauseShotDetection(boolean)`
    - Sound: `void playSound(String)`, `void playSounds(List<String>)`, `void say(String)`
    - Time: `long currentTimeMillis()`, `Cancellable schedule(Runnable, Duration)`, `Cancellable scheduleRepeating(Runnable, Duration, Duration)`
    - Resources: `Optional<InputStream> resource(String)`, `Path dataDirectory()`
    - Shared settings: `double parTime()`, `void setParTime(double)`, `void onParTimeChanged(DoubleConsumer)`, `DelayRange delayedStart()`, `void setDelayedStart(DelayRange)`, `void onDelayedStartChanged(Consumer<DelayRange>)`
  - `interface TargetHandle`: `TargetId id()`, `void move(double, double)`, `void resize(double, double)`, `void setVisible(boolean)`, `void remove()`, `Point position()`, `Size size()`, `TargetDefinition definition()`
  - `interface TextHandle`: `setText(String)`, `move(double, double)`, `remove()`; `interface ButtonHandle`: `setLabel(String)`, `remove()`; `interface ShotMarkerHandle`: `remove()`
  - `@FunctionalInterface interface Cancellable`: `void cancel()`
  - `record TextStyle(double fontSize, String textColor, String backgroundColor)`, `record RowStyle(String highlightColor)`, `record ShotStyle(ShotColor color)`, `record DelayRange(int minSeconds, int maxSeconds)` (throws `IllegalArgumentException` if `minSeconds < 0` or `maxSeconds < minSeconds`). Colors are `#RRGGBB` or CSS color names, as in `.target` files.
  - `final class ExerciseExecutor`:
    - `ExerciseExecutor(String exerciseName)` (thread name `Exercise: <exerciseName>`)
    - `void execute(Runnable)`, `Cancellable schedule(Runnable, Duration)`, `Cancellable scheduleRepeating(Runnable, Duration, Duration)`
    - `boolean isExerciseThread()`, `boolean isShutdown()`, `boolean shutdown(Runnable last, Duration timeout)`
  - `final class ExercisePaths`: `static String resourceName(String)`, `static Optional<File> shootoffFile(String path, String folder)`
  - `com.shootoff.JavaFxReferenceScanner` (core test fixtures, now `public`): `findJavaFxReferences(Path)`, `countClasses(Path)`, and new `static List<String> findJavaFxReferencesNextTo(Class<?> anchor) throws Exception`

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/plugins/TestExerciseMetadata.java`:

```java
package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestExerciseMetadata {
	@Test
	void fourArgumentMetadataIsNotProjectorOnly() {
		assertFalse(new ExerciseMetadata("Drill", "1.0", "me", "A drill").isProjectorOnly());
		assertTrue(new ExerciseMetadata("Drill", "2.0", "me", "A drill", true).isProjectorOnly());
	}

	@Test
	void projectorOnlyTakesPartInEquality() {
		final ExerciseMetadata v1 = new ExerciseMetadata("Drill", "1.0", "me", "A drill");

		assertEquals(v1, new ExerciseMetadata("Drill", "1.0", "me", "A drill", false));
		assertEquals(v1.hashCode(), new ExerciseMetadata("Drill", "1.0", "me", "A drill", false).hashCode());
		assertNotEquals(v1, new ExerciseMetadata("Drill", "1.0", "me", "A drill", true));
	}
}
```

`plugin-api/src/test/java/com/shootoff/exercise/TestExerciseExecutor.java`:

```java
package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestExerciseExecutor {
	private final ExerciseExecutor executor = new ExerciseExecutor("Test drill");

	@AfterEach
	void tearDown() {
		executor.shutdown(() -> {}, Duration.ofSeconds(1));
	}

	// Runs on the exercise thread after everything submitted so far
	private void sync() throws InterruptedException {
		final CountDownLatch done = new CountDownLatch(1);
		executor.execute(done::countDown);
		assertTrue(done.await(5, TimeUnit.SECONDS));
	}

	@Test
	void callbacksRunInOrderOnOneNamedThread() throws Exception {
		final List<Integer> order = new CopyOnWriteArrayList<>();
		final List<String> threads = new CopyOnWriteArrayList<>();

		for (int i = 0; i < 20; i++) {
			final int n = i;
			// Each from a new thread: the order of submission is kept, and all run on one thread
			final Thread submitter = new Thread(() -> executor.execute(() -> {
				order.add(n);
				threads.add(Thread.currentThread().getName());
			}));
			submitter.start();
			submitter.join();
		}
		sync();

		assertEquals(IntStream.range(0, 20).boxed().toList(), order);
		assertEquals(Set.of("Exercise: Test drill"), Set.copyOf(threads));
	}

	@Test
	void scheduledTaskRunsAfterItsDelayOnTheExerciseThread() throws Exception {
		final long start = System.nanoTime();
		final CompletableFuture<Boolean> onExerciseThread = new CompletableFuture<>();

		executor.schedule(() -> onExerciseThread.complete(executor.isExerciseThread()), Duration.ofMillis(100));

		assertTrue(onExerciseThread.get(5, TimeUnit.SECONDS));
		assertTrue(System.nanoTime() - start >= Duration.ofMillis(100).toNanos());
		assertFalse(executor.isExerciseThread());
	}

	@Test
	void cancelledTaskNeverRuns() throws Exception {
		final AtomicBoolean ran = new AtomicBoolean();

		executor.schedule(() -> ran.set(true), Duration.ofMillis(100)).cancel();
		Thread.sleep(300);

		assertFalse(ran.get());
	}

	@Test
	void repeatingTaskRepeatsUntilCancelled() throws Exception {
		final AtomicInteger runs = new AtomicInteger();
		final CountDownLatch three = new CountDownLatch(3);

		final Cancellable repeating = executor.scheduleRepeating(() -> {
			runs.incrementAndGet();
			three.countDown();
		}, Duration.ZERO, Duration.ofMillis(20));
		assertTrue(three.await(5, TimeUnit.SECONDS));

		repeating.cancel();
		sync();
		final int afterCancel = runs.get();
		Thread.sleep(100);

		assertEquals(afterCancel, runs.get());
	}

	@Test
	void aThrowingCallbackDoesNotStopLaterOnesOrItsRepeats() throws Exception {
		executor.execute(() -> {
			throw new IllegalStateException("A bug in an exercise");
		});

		final CountDownLatch repeats = new CountDownLatch(3);
		executor.scheduleRepeating(() -> {
			repeats.countDown();
			throw new IllegalStateException("A bug in a repeating task");
		}, Duration.ZERO, Duration.ofMillis(10));

		assertTrue(repeats.await(5, TimeUnit.SECONDS));
		sync();
	}

	@Test
	void shutdownRunsQueuedCallbacksThenTheLastOneAndCancelsScheduledTasks() throws Exception {
		final List<String> ran = new CopyOnWriteArrayList<>();
		final CountDownLatch release = new CountDownLatch(1);

		// Keep the exercise thread busy so the next callback queues behind it
		executor.execute(() -> {
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		executor.execute(() -> ran.add("queued"));
		executor.schedule(() -> ran.add("scheduled"), Duration.ofMillis(200));
		new Thread(() -> {
			try {
				Thread.sleep(50);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			release.countDown();
		}).start();

		assertTrue(executor.shutdown(() -> ran.add("last"), Duration.ofSeconds(5)));
		Thread.sleep(300);

		assertEquals(List.of("queued", "last"), ran);
		assertTrue(executor.isShutdown());
	}

	@Test
	void callsAfterShutdownAreIgnored() throws Exception {
		assertTrue(executor.shutdown(() -> {}, Duration.ofSeconds(1)));
		final AtomicBoolean ran = new AtomicBoolean();

		executor.execute(() -> ran.set(true));
		executor.schedule(() -> ran.set(true), Duration.ZERO);
		executor.scheduleRepeating(() -> ran.set(true), Duration.ZERO, Duration.ofMillis(10)).cancel();
		Thread.sleep(100);

		assertFalse(ran.get());
	}

	@Test
	void shutdownFromTheExerciseThreadDoesNotWait() throws Exception {
		final CompletableFuture<Boolean> result = new CompletableFuture<>();
		final CountDownLatch last = new CountDownLatch(1);

		executor.execute(() -> result.complete(executor.shutdown(last::countDown, Duration.ofSeconds(30))));

		// It returned at once instead of waiting for itself, and the last callback still ran after it
		assertFalse(result.get(5, TimeUnit.SECONDS));
		assertTrue(last.await(5, TimeUnit.SECONDS));
	}
}
```

`plugin-api/src/test/java/com/shootoff/exercise/TestExercisePaths.java`:

```java
package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestExercisePaths {
	@TempDir Path home;
	private String previousHome;

	@BeforeEach
	void setUp() throws IOException {
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", home.toString());
		Files.createDirectories(home.resolve("targets"));
		Files.writeString(home.resolve("targets/A.target"), "<target/>");
	}

	@AfterEach
	void tearDown() {
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	@Test
	void resourceNameDropsTheAtAndLeadingSlashes() {
		assertEquals("targets/ISSF.target", ExercisePaths.resourceName("@targets\\ISSF.target"));
		assertEquals("sounds/buzzer.wav", ExercisePaths.resourceName("/sounds/buzzer.wav"));
		assertEquals("backgrounds/black.png", ExercisePaths.resourceName("backgrounds/black.png"));
	}

	@Test
	void shootoffFileLooksInTheHomeThenInTheFolder() {
		final File a = home.resolve("targets/A.target").toFile();

		assertEquals(Optional.of(a), ExercisePaths.shootoffFile("targets/A.target", "targets"));
		assertEquals(Optional.of(a), ExercisePaths.shootoffFile("A.target", "targets"));
		assertEquals(Optional.empty(), ExercisePaths.shootoffFile("B.target", "targets"));
		assertEquals(Optional.empty(), ExercisePaths.shootoffFile("@targets/A.target", "targets"));
	}

	@Test
	void absolutePathIsUsedAsIs() {
		final File a = home.resolve("targets/A.target").toFile();

		assertEquals(Optional.of(a), ExercisePaths.shootoffFile(a.getAbsolutePath(), "sounds"));
		assertEquals(Optional.empty(), ExercisePaths.shootoffFile(home.resolve("missing.wav").toString(), "sounds"));
	}
}
```

`plugin-api/src/test/java/com/shootoff/exercise/TestExerciseValues.java`:

```java
package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TestExerciseValues {
	@Test
	void delayRangeRejectsAMinimumAboveTheMaximum() {
		assertEquals(5, new DelayRange(5, 5).maxSeconds());
		assertThrows(IllegalArgumentException.class, () -> new DelayRange(5, 4));
		assertThrows(IllegalArgumentException.class, () -> new DelayRange(-1, 4));
	}
}
```

`plugin-api/src/test/java/com/shootoff/exercise/TestNoJavaFxInPluginApi.java`:

```java
package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.shootoff.JavaFxReferenceScanner;

class TestNoJavaFxInPluginApi {
	@Test
	void pluginApiClassesDoNotReferenceJavaFx() throws Exception {
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(Exercise.class));
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.plugins.TestExerciseMetadata' :plugin-api:test --console=plain`
Expected: FAIL at compile time: `cannot find symbol` for `ExerciseMetadata` in core, and for `ExerciseExecutor`, `ExercisePaths`, `DelayRange`, `Exercise` and `JavaFxReferenceScanner` in plugin-api.

- [ ] **Step 3: Move `ExerciseMetadata` into `core` and add the projector flag**

```bash
git mv javafx-app/src/main/java/com/shootoff/plugins/ExerciseMetadata.java core/src/main/java/com/shootoff/plugins/ExerciseMetadata.java
```

Keep its license header. Replace everything after the header with:

```java
package com.shootoff.plugins;

import java.io.Serializable;
import java.util.Objects;

/**
 * Data about what an exercise is and who wrote it. A v2 exercise also says whether it only runs on
 * the projector arena; v1 exercises say that with their base class instead.
 *
 * @author phrack
 */
public class ExerciseMetadata implements Serializable {
	private static final long serialVersionUID = 1L;

	private final String name;
	private final String version;
	private final String creator;
	private final String description;
	private final boolean projectorOnly;

	public ExerciseMetadata(final String name, final String version, final String creator, final String description) {
		this(name, version, creator, description, false);
	}

	/**
	 * @param projectorOnly
	 *            <tt>true</tt> if the exercise only runs on the projector arena (v2 exercises)
	 */
	public ExerciseMetadata(final String name, final String version, final String creator, final String description,
			final boolean projectorOnly) {
		this.name = name;
		this.version = version;
		this.creator = creator;
		this.description = description;
		this.projectorOnly = projectorOnly;
	}

	public String getName() {
		return name;
	}

	public String getVersion() {
		return version;
	}

	public String getCreator() {
		return creator;
	}

	public String getDescription() {
		return description;
	}

	public boolean isProjectorOnly() {
		return projectorOnly;
	}

	@Override
	public int hashCode() {
		return Objects.hash(creator, description, name, version, projectorOnly);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null || getClass() != obj.getClass()) return false;
		final ExerciseMetadata other = (ExerciseMetadata) obj;
		return Objects.equals(creator, other.creator) && Objects.equals(description, other.description)
				&& Objects.equals(name, other.name) && Objects.equals(version, other.version)
				&& projectorOnly == other.projectorOnly;
	}

	@Override
	public String toString() {
		return name + " " + version + " " + creator;
	}
}
```

- [ ] **Step 4: Share the JavaFX scanner through `core`'s test fixtures**

```bash
git mv core/src/test/java/com/shootoff/JavaFxReferenceScanner.java core/src/testFixtures/java/com/shootoff/JavaFxReferenceScanner.java
```

In the moved file:
- Make the class and its two methods `public`.
- Add the imports `java.nio.file.FileSystem`, `java.nio.file.FileSystems` and `java.nio.file.Paths`.
- Add this method after `countClasses`:

```java
	/**
	 * Scans the classes folder or jar that <tt>anchor</tt> was loaded from. Under java-test-fixtures,
	 * a module's tests see its jar rather than its classes folder.
	 *
	 * @return the classes there that mention JavaFX
	 */
	public static List<String> findJavaFxReferencesNextTo(Class<?> anchor) throws Exception {
		final Path location = Paths.get(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
		final String anchorFile = anchor.getName().replace('.', '/') + ".class";

		if (Files.isDirectory(location)) return scanRoot(location, anchorFile);

		try (FileSystem jar = FileSystems.newFileSystem(location)) {
			return scanRoot(jar.getPath("/"), anchorFile);
		}
	}

	private static List<String> scanRoot(Path root, String anchorFile) throws IOException {
		if (!Files.exists(root.resolve(anchorFile))) {
			throw new IllegalStateException("Scanning the wrong place: " + root + " has no " + anchorFile);
		}
		return findJavaFxReferences(root);
	}
```

`TestNoJavaFxInCore` stays as it is: it is in the same package.

- [ ] **Step 5: Give `plugin-api` the scanner**

Replace `plugin-api/build.gradle.kts` with:

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":core"))
    // JavaFxReferenceScanner for the boundary test
    testImplementation(testFixtures(project(":core")))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
```

- [ ] **Step 6: Write the API types**

Create each file below in `plugin-api/src/main/java/com/shootoff/exercise/`, with the GPL header followed by the code.

`Exercise.java`:

```java
package com.shootoff.exercise;

import java.util.List;
import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;

/**
 * A training exercise that runs on any ShootOFF user interface. It only talks to its
 * {@link ExerciseHost}. The host calls every method here on the exercise's own thread, one call at a
 * time, so an exercise needs no locking.
 * <p>
 * A plugin jar declares its exercise in <tt>shootoff.xml</tt>:
 * <tt>&lt;shootoffExercise apiVersion="2" exerciseClass="..." /&gt;</tt>. The class needs a public
 * no-argument constructor that does no work: ShootOFF creates one instance to read its metadata, and
 * a fresh instance every time the exercise starts.
 */
public interface Exercise {
	ExerciseMetadata metadata();

	/**
	 * Called once when the exercise starts. Keep <tt>host</tt>: it is the exercise's only way to
	 * show things, play sounds and schedule work.
	 */
	void start(ExerciseHost host);

	/**
	 * Called for every detected shot.
	 *
	 * @param shot
	 *            the shot, in the surface's coordinates (arena coordinates on the projector)
	 * @param hit
	 *            the topmost visible region of a visible target under the shot, if any
	 */
	void onShot(Shot shot, Optional<Hit> hit);

	/**
	 * Called when a target is added to or removed from the surface, by the exercise or the user.
	 */
	default void onTargetsChanged(List<TargetHandle> targets) {}

	/**
	 * Called when the user presses Reset or shoots a reset target.
	 */
	void onReset();

	/**
	 * Called once when the exercise ends. The host then cancels its scheduled tasks and removes
	 * everything it added, so an exercise only has to stop its own threads, if it made any.
	 */
	void stop();
}
```

`ExerciseHost.java`:

```java
package com.shootoff.exercise;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import com.shootoff.geom.Size;

/**
 * What a user interface offers a running {@link Exercise}. Methods may be called from any thread;
 * each user interface moves the work onto its own thread. Every callback the host makes (scheduled
 * tasks, buttons, settings, listeners) runs on the exercise's thread. After the exercise stops, calls
 * change nothing.
 * <p>
 * Names of targets, sounds and resources are looked up in the exercise's own jar first (a leading
 * <tt>@</tt> or <tt>/</tt> is dropped). Otherwise they are files: absolute, relative to the ShootOFF
 * folder, or relative to its <tt>targets/</tt> or <tt>sounds/</tt> folder.
 */
public interface ExerciseHost {
	/**
	 * @return the size of the surface the exercise places things on: the arena, or the camera feed
	 */
	Size surfaceSize();

	/**
	 * @return <tt>true</tt> if the surface is the projector arena
	 */
	boolean isProjector();

	/**
	 * Sets the arena's background to an image from the exercise's jar or ShootOFF's bundled
	 * backgrounds (e.g. <tt>arena/backgrounds/indoor_range.gif</tt>). The previous background comes
	 * back when the exercise stops. Ignored on a camera feed.
	 */
	void setBackground(String imageResource);

	/**
	 * Adds a target with its top-left corner at (<tt>x</tt>, <tt>y</tt>).
	 *
	 * @return the target, or empty if the file can't be found or read
	 */
	Optional<TargetHandle> addTarget(String targetFile, double x, double y);

	/**
	 * @return every target on the surface, bottom to top, including the user's
	 */
	List<TargetHandle> targets();

	TextHandle showText(String text, double x, double y, TextStyle style);

	/**
	 * Shows a message in the standard banner on every camera feed, and records it in the session
	 * being recorded, if any.
	 */
	void showMessage(String message);

	/**
	 * Adds a button next to ShootOFF's Reset button.
	 */
	ButtonHandle addButton(String label, Runnable onClick);

	/**
	 * Adds a numeric setting to the exercise pane. <tt>onChange</tt> hears every value the user sets.
	 */
	void addNumberSetting(String label, double initial, double min, double max, double step, DoubleConsumer onChange);

	/**
	 * Adds a column to the shot timer.
	 */
	void addColumn(String name);

	/**
	 * Sets a column's value in the shot timer's latest row, which is the row of the latest shot.
	 */
	void setColumnValue(String name, String value);

	/**
	 * Highlights the shot timer's latest row.
	 */
	void styleLastRow(RowStyle style);

	/**
	 * Draws a shot marker, for example to replay shots on a summary target.
	 */
	ShotMarkerHandle showShotMarker(double x, double y, ShotStyle style);

	/**
	 * Clears ShootOFF's shot markers and shot timer, and the markers the exercise drew.
	 */
	void clearShots();

	void pauseShotDetection(boolean paused);

	void playSound(String resourceOrFile);

	/**
	 * Plays the sounds one after another.
	 */
	void playSounds(List<String> resourcesOrFiles);

	/**
	 * Speaks <tt>text</tt> (text to speech).
	 */
	void say(String text);

	/**
	 * @return the host's clock in milliseconds. Use it to time shots, so tests can control time.
	 */
	long currentTimeMillis();

	/**
	 * Runs <tt>task</tt> on the exercise's thread after <tt>delay</tt>.
	 */
	Cancellable schedule(Runnable task, Duration delay);

	Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period);

	/**
	 * @return a resource from the exercise's own jar
	 */
	Optional<InputStream> resource(String path);

	/**
	 * @return a writable folder for this exercise's own files (for example personal bests); it exists
	 */
	Path dataDirectory();

	/**
	 * @return the par time in seconds shown in ShootOFF's shared par-time control
	 */
	double parTime();

	/**
	 * Sets the shared par-time control, for example to the exercise's default. The exercise's own
	 * listeners aren't called.
	 */
	void setParTime(double seconds);

	/**
	 * Shows the shared par-time control while the exercise runs; <tt>listener</tt> hears the values
	 * the user sets.
	 */
	void onParTimeChanged(DoubleConsumer listener);

	/**
	 * @return the random start delay shown in ShootOFF's shared delay controls
	 */
	DelayRange delayedStart();

	/**
	 * Sets the shared delay controls. The exercise's own listeners aren't called.
	 */
	void setDelayedStart(DelayRange range);

	/**
	 * Shows the shared delay controls while the exercise runs; <tt>listener</tt> hears the ranges the
	 * user sets.
	 */
	void onDelayedStartChanged(Consumer<DelayRange> listener);
}
```

`TargetHandle.java`:

```java
package com.shootoff.exercise;

import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetId;

/**
 * A target on the exercise's surface. Hits name it by {@link #id()}.
 */
public interface TargetHandle {
	TargetId id();

	/**
	 * Moves the target's top-left corner to (<tt>x</tt>, <tt>y</tt>).
	 */
	void move(double x, double y);

	void resize(double width, double height);

	/**
	 * Shows or hides the target. Hidden targets take no hits.
	 */
	void setVisible(boolean visible);

	void remove();

	Point position();

	Size size();

	TargetDefinition definition();
}
```

`TextHandle.java`:

```java
package com.shootoff.exercise;

public interface TextHandle {
	void setText(String text);

	void move(double x, double y);

	void remove();
}
```

`ButtonHandle.java`:

```java
package com.shootoff.exercise;

public interface ButtonHandle {
	void setLabel(String label);

	void remove();
}
```

`ShotMarkerHandle.java`:

```java
package com.shootoff.exercise;

public interface ShotMarkerHandle {
	void remove();
}
```

`Cancellable.java`:

```java
package com.shootoff.exercise;

/**
 * A scheduled task. Cancelling a task that already ran, or was already cancelled, does nothing.
 */
@FunctionalInterface
public interface Cancellable {
	void cancel();
}
```

`TextStyle.java`:

```java
package com.shootoff.exercise;

/**
 * How {@link ExerciseHost#showText} draws text. Colors are <tt>#RRGGBB</tt> or CSS color names (for
 * example <tt>white</tt>, <tt>transparent</tt>), as in target files.
 */
public record TextStyle(double fontSize, String textColor, String backgroundColor) {}
```

`RowStyle.java`:

```java
package com.shootoff.exercise;

/**
 * How {@link ExerciseHost#styleLastRow} highlights a shot timer row: a <tt>#RRGGBB</tt> or CSS
 * color name.
 */
public record RowStyle(String highlightColor) {}
```

`ShotStyle.java`:

```java
package com.shootoff.exercise;

import com.shootoff.camera.shot.ShotColor;

/**
 * How {@link ExerciseHost#showShotMarker} draws a marker: in the color ShootOFF uses for shots of
 * that laser color, at the configured marker size.
 */
public record ShotStyle(ShotColor color) {}
```

`DelayRange.java`:

```java
package com.shootoff.exercise;

/**
 * A random start delay, in whole seconds.
 */
public record DelayRange(int minSeconds, int maxSeconds) {
	public DelayRange {
		if (minSeconds < 0 || maxSeconds < minSeconds) {
			throw new IllegalArgumentException(String.format("Not a delay range: %d-%d s", minSeconds, maxSeconds));
		}
	}
}
```

`ExercisePaths.java`:

```java
package com.shootoff.exercise;

import java.io.File;
import java.util.Optional;

/**
 * How hosts turn the names exercises use into jar resources and files.
 */
public final class ExercisePaths {
	private ExercisePaths() {}

	/**
	 * @return <tt>path</tt> as a resource name in an exercise's jar: without a leading <tt>@</tt> or
	 *         <tt>/</tt>, with <tt>/</tt> separators
	 */
	public static String resourceName(String path) {
		String name = path.replace('\\', '/');
		if (name.startsWith("@")) name = name.substring(1);
		while (name.startsWith("/")) name = name.substring(1);
		return name;
	}

	/**
	 * Finds a file in ShootOFF's folder (the <tt>shootoff.home</tt> system property, or the working
	 * directory): <tt>path</tt> itself if absolute, otherwise <tt>path</tt> in the home folder,
	 * otherwise <tt>path</tt> in its <tt>folder</tt> subfolder (so "IPSC.target" finds
	 * "targets/IPSC.target"). A path starting with <tt>@</tt> names a jar resource, never a file.
	 */
	public static Optional<File> shootoffFile(String path, String folder) {
		if (path.isEmpty() || path.startsWith("@")) return Optional.empty();

		final File file = new File(path);
		if (file.isAbsolute()) return file.isFile() ? Optional.of(file) : Optional.empty();

		final File home = new File(System.getProperty("shootoff.home", System.getProperty("user.dir")));
		final File inHome = new File(home, path);
		if (inHome.isFile()) return Optional.of(inHome);

		final File inFolder = new File(new File(home, folder), path);
		return inFolder.isFile() ? Optional.of(inFolder) : Optional.empty();
	}
}
```

`ExerciseExecutor.java`:

```java
package com.shootoff.exercise;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thread a running exercise lives on. Hosts run every exercise callback here, one at a time and
 * in the order they were submitted, so exercises need no locking. A callback that throws is logged,
 * and later callbacks and repeating tasks carry on.
 */
public final class ExerciseExecutor {
	private static final Logger logger = LoggerFactory.getLogger(ExerciseExecutor.class);

	private final String exerciseName;
	private final ScheduledThreadPoolExecutor executor;
	private volatile Thread thread;

	public ExerciseExecutor(String exerciseName) {
		this.exerciseName = exerciseName;
		executor = new ScheduledThreadPoolExecutor(1, runnable -> {
			final Thread t = new Thread(runnable, "Exercise: " + exerciseName);
			t.setDaemon(true);
			thread = t;
			return t;
		});
		executor.setRemoveOnCancelPolicy(true);
		// Shutting down cancels every delayed and repeating task; callbacks already due still run
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
	}

	/**
	 * Runs <tt>callback</tt> on the exercise thread after everything submitted before it. Ignored once
	 * the executor has shut down.
	 */
	public void execute(Runnable callback) {
		schedule(callback, Duration.ZERO);
	}

	public Cancellable schedule(Runnable task, Duration delay) {
		try {
			final ScheduledFuture<?> future = executor.schedule(guarded(task), delay.toNanos(), TimeUnit.NANOSECONDS);
			return () -> future.cancel(false);
		} catch (final RejectedExecutionException e) {
			logger.debug("Ignoring a task for {}, which has stopped", exerciseName);
			return () -> {};
		}
	}

	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		try {
			final ScheduledFuture<?> future = executor.scheduleAtFixedRate(guarded(task), initialDelay.toNanos(),
					period.toNanos(), TimeUnit.NANOSECONDS);
			return () -> future.cancel(false);
		} catch (final RejectedExecutionException e) {
			logger.debug("Ignoring a repeating task for {}, which has stopped", exerciseName);
			return () -> {};
		}
	}

	public boolean isExerciseThread() {
		return Thread.currentThread() == thread;
	}

	public boolean isShutdown() {
		return executor.isShutdown();
	}

	/**
	 * Stops the exercise thread: callbacks already submitted run, then <tt>last</tt> (for example
	 * the exercise's <tt>stop()</tt>), and every scheduled task is cancelled. Waits up to
	 * <tt>timeout</tt> for that, and interrupts the thread if it takes longer. Called on the exercise
	 * thread itself, it can't wait: <tt>last</tt> then runs when the current callback returns.
	 *
	 * @return <tt>true</tt> if the thread finished within <tt>timeout</tt>
	 */
	public boolean shutdown(Runnable last, Duration timeout) {
		if (executor.isShutdown()) return executor.isTerminated();

		execute(last);
		executor.shutdown();

		if (isExerciseThread()) return false;

		try {
			if (executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) return true;
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		logger.warn("{} didn't stop within {} ms; interrupting it", exerciseName, timeout.toMillis());
		executor.shutdownNow();
		return false;
	}

	private Runnable guarded(Runnable task) {
		return () -> {
			try {
				task.run();
			} catch (final RuntimeException | Error e) {
				logger.error("{} threw an exception", exerciseName, e);
			}
		};
	}
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.plugins.TestExerciseMetadata' --tests 'com.shootoff.TestNoJavaFxInCore' :plugin-api:test :javafx-app:compileTestJava --console=plain`
Expected: `BUILD SUCCESSFUL`, with these passing:
- `TestExerciseMetadata` (2)
- `TestNoJavaFxInCore` (3)
- `TestExerciseExecutor` (8), `TestExercisePaths` (3), `TestExerciseValues` (1), `TestNoJavaFxInPluginApi` (1)

javafx-app still compiles: `ExerciseMetadata` kept its package.

- [ ] **Step 8: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 15 (**320**). Also:

```bash
ls plugin-api/build/test-results/test | command grep -c "^TEST-com.shootoff.exercise"
```

Expected: `4`.

- [ ] **Step 9: Commit**

```bash
git add core/src/main/java/com/shootoff/plugins/ExerciseMetadata.java core/src/test/java/com/shootoff/plugins/TestExerciseMetadata.java \
  core/src/testFixtures/java/com/shootoff/JavaFxReferenceScanner.java plugin-api/build.gradle.kts \
  plugin-api/src/main/java/com/shootoff/exercise plugin-api/src/test/java/com/shootoff/exercise
git status --short | command grep -v '^R  '
git commit -m "Add the UI-neutral exercise API and its exercise thread to plugin-api"
git log -1 --format=%B
```

The filtered status must show only ` M shootoff.properties` (plus the owner's `??` files, if any).

---
### Task 3: `FakeExerciseHost` in `plugin-api`'s test fixtures

Runs in ShootOFF on `compose-ui`.

**Files:**
- Modify: `plugin-api/build.gradle.kts` (add `java-test-fixtures`)
- Create: `plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java`
- Modify: `plugin-api/src/test/java/com/shootoff/exercise/TestNoJavaFxInPluginApi.java` (one test added)
- Test: `plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java`

**Interfaces:**
- Consumes: everything Task 2 produced; `com.shootoff.targets.model.{TargetSet,PlacedTarget,Placement,HitTester,TargetDefinitions,ResourceResolver,TargetFormatException}` (core, Plan 2).
- Produces: `public class FakeExerciseHost implements ExerciseHost` (package `com.shootoff.exercise`, published as `plugin-api`'s test fixtures):
  - Constants: `DEFAULT_SURFACE = new Size(1280, 720)`, `START_TIME = 1_000_000` (ms), `DEFAULT_PAR_TIME = 2.0`, `DEFAULT_DELAYED_START = new DelayRange(4, 8)`
  - Constructors: `FakeExerciseHost()` (default surface, projector, a new temporary data directory), and `FakeExerciseHost(Size surfaceSize, boolean projector, Path dataDirectory)`, which creates `dataDirectory`
  - `FakeExerciseHost withResources(ClassLoader)`: where "the exercise's jar" is. The default is the thread's context class loader, so a plugin's `src/main/resources` are found in its tests.
  - Lifecycle: `void start(Exercise)`, `void reset()` (clears rows, then `onReset()`), `void stop()` (`stop()`, then removes everything and cancels tasks)
  - Shots: `boolean shoot(ShotColor, double, double)` (hit-tested against the exercise's visible targets), `boolean shoot(Shot, Optional<Hit>)`. Both return `false` and deliver nothing while detection is paused, before `start` or after `stop`. A delivered shot adds a shot-timer row before `onShot`.
  - Time: `void advance(Duration)`: runs due tasks in time order, ties in scheduling order, including tasks they schedule within the window. `int pendingTasks()`.
  - User actions: `void click(String label)` (throws `IllegalStateException` if no button has that label), `void changeSetting(String label, double value)` (throws `IllegalArgumentException` outside min..max), `void changeParTime(double)`, `void changeDelayedStart(DelayRange)`
  - Queries:
    - `List<ShownText> shownTexts()`, `Optional<String> textAt(double x, double y)`, `List<String> messages()`
    - `List<String> buttonLabels()`, `List<String> settingLabels()`, `double settingValue(String)`
    - `List<String> columns()`, `List<Row> rows()`, `List<ShownMarker> shotMarkers()`
    - `List<String> sounds()`, `List<String> spoken()`, `Optional<String> background()`
    - `boolean isShotDetectionPaused()`, `boolean isStopped()`, `boolean isVisible(TargetHandle)`, `boolean isListeningForParTime()`, `boolean isListeningForDelayedStart()`
  - Records: `ShownText(String text, double x, double y, TextStyle style)`, `ShownMarker(double x, double y, ShotStyle style)`, `Row(Shot shot, Map<String,String> values, Optional<RowStyle> style)`
  - An exercise's own `setParTime`/`setDelayedStart` don't call its listeners; `changeParTime`/`changeDelayedStart` (the user) do.

- [ ] **Step 1: Write the failing tests**

`plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java`:

```java
package com.shootoff.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetSet;

class TestFakeExerciseHost {
	private static final TextStyle STYLE = new TextStyle(20, "white", "transparent");

	@TempDir Path temp;
	private String previousHome;
	private FakeExerciseHost host;

	@BeforeEach
	void setUp() {
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		host = new FakeExerciseHost(new Size(800, 600), true, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	private static final class Recorder implements Exercise {
		final List<String> events = new ArrayList<>();

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Recorder", "1.0", "ShootOFF tests", "Records its callbacks", true);
		}

		@Override
		public void start(ExerciseHost host) {
			events.add("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			events.add("shot " + hit.map(h -> h.region().index()).orElse(-1));
		}

		@Override
		public void onReset() {
			events.add("reset");
		}

		@Override
		public void stop() {
			events.add("stop");
		}
	}

	@Test
	void advanceRunsDueTasksInTimeOrder() {
		final List<String> ran = new ArrayList<>();
		final long start = host.currentTimeMillis();

		host.schedule(() -> ran.add("b"), Duration.ofSeconds(2));
		host.schedule(() -> ran.add("a"), Duration.ofSeconds(1));
		host.schedule(() -> ran.add("c"), Duration.ofSeconds(2));
		host.schedule(() -> host.schedule(() -> ran.add("d"), Duration.ofMillis(500)), Duration.ofSeconds(1));

		host.advance(Duration.ofMillis(1500));
		assertEquals(List.of("a", "d"), ran);
		assertEquals(start + 1500, host.currentTimeMillis());

		host.advance(Duration.ofMillis(500));
		assertEquals(List.of("a", "d", "b", "c"), ran);
		assertEquals(FakeExerciseHost.START_TIME + 2000, host.currentTimeMillis());
	}

	@Test
	void repeatingTaskRunsEveryPeriodUntilCancelled() {
		final List<Long> times = new ArrayList<>();
		final long start = host.currentTimeMillis();

		final Cancellable repeating = host.scheduleRepeating(() -> times.add(host.currentTimeMillis() - start),
				Duration.ofMillis(100), Duration.ofMillis(250));
		host.advance(Duration.ofMillis(700));
		assertEquals(List.of(100L, 350L, 600L), times);

		repeating.cancel();
		host.advance(Duration.ofSeconds(1));
		assertEquals(3, times.size());
		assertEquals(0, host.pendingTasks());
	}

	@Test
	void cancelledTaskDoesNotRun() {
		final List<String> ran = new ArrayList<>();

		host.schedule(() -> ran.add("cancelled"), Duration.ofSeconds(1)).cancel();
		host.schedule(() -> ran.add("kept"), Duration.ofSeconds(1));
		host.advance(Duration.ofSeconds(1));

		assertEquals(List.of("kept"), ran);
	}

	@Test
	void addTargetLoadsTheDefinitionFromFilesOrTheExercisesJar() throws IOException, TargetFormatException {
		final TargetHandle ipsc = host.addTarget("targets/IPSC.target", 10, 20).get();
		assertEquals(new Point(10, 20), ipsc.position());
		assertEquals(TargetDefinitions.load(Paths.get("targets/IPSC.target")).regions(), ipsc.definition().regions());

		// A bare name is looked up in the targets folder
		assertTrue(host.addTarget("ISSF.target", 0, 0).isPresent());

		// "@" names come from the exercise's jar
		Files.createDirectories(temp.resolve("jar/targets"));
		Files.writeString(temp.resolve("jar/targets/box.target"),
				"<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"red\" /></target>");
		try (URLClassLoader jar = new URLClassLoader(new URL[] { temp.resolve("jar").toUri().toURL() }, null)) {
			host.withResources(jar);
			assertEquals(new Size(10, 20), host.addTarget("@targets/box.target", 0, 0).get().size());
		}

		assertEquals(Optional.empty(), host.addTarget("targets/no_such.target", 0, 0));
		assertEquals(3, host.targets().size());
		assertEquals(ipsc, host.targets().get(0));
	}

	@Test
	void shootDeliversTheModelHitUnlessDetectionIsPaused() throws TargetFormatException {
		final Recorder exercise = new Recorder();
		host.start(exercise);
		final TargetHandle ipsc = host.addTarget("targets/IPSC.target", 0, 0).get();

		// The same target placed the same way, to know which region the middle of it is
		final TargetSet reference = new TargetSet();
		final Rect bounds = reference.add(TargetDefinitions.load(Paths.get("targets/IPSC.target")), Placement.ORIGIN)
				.getBounds();
		final double x = bounds.getMinX() + bounds.getWidth() / 2;
		final double y = bounds.getMinY() + bounds.getHeight() / 2;
		final int region = HitTester.hit(reference, x, y).get().region().index();

		assertTrue(host.shoot(ShotColor.RED, x, y));
		ipsc.setVisible(false);
		assertTrue(host.shoot(ShotColor.RED, x, y));
		host.pauseShotDetection(true);
		assertFalse(host.shoot(ShotColor.RED, x, y));

		// Hidden targets take no hits; paused detection delivers nothing
		assertEquals(List.of("start", "shot " + region, "shot -1"), exercise.events);
		assertFalse(host.isVisible(ipsc));
		assertEquals(2, host.rows().size());
	}

	@Test
	void columnValuesAndStylesGoToTheLatestRow() {
		host.start(new Recorder());
		host.addColumn("Score");

		host.setColumnValue("Score", "ignored: there is no row yet");
		host.shoot(ShotColor.RED, 1, 1);
		host.setColumnValue("Score", "5");
		host.shoot(ShotColor.GREEN, 2, 2);
		host.setColumnValue("Score", "7");
		host.styleLastRow(new RowStyle("coral"));

		assertEquals(List.of("Score"), host.columns());
		assertEquals(List.of(Map.of("Score", "5"), Map.of("Score", "7")),
				host.rows().stream().map(FakeExerciseHost.Row::values).toList());
		assertEquals(Optional.empty(), host.rows().get(0).style());
		assertEquals(Optional.of(new RowStyle("coral")), host.rows().get(1).style());
		assertEquals(ShotColor.GREEN, host.rows().get(1).shot().getColor());
	}

	@Test
	void buttonsTextsAndSettingsAreRecordedAndCallBack() {
		final List<String> calls = new ArrayList<>();
		final ButtonHandle pause = host.addButton("Pause", () -> calls.add("pause"));
		host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> calls.add("rounds " + value));
		final TextHandle text = host.showText("Score: 0", 10, 20, STYLE);

		host.click("Pause");
		pause.setLabel("Resume");
		host.click("Resume");
		host.changeSetting("Rounds", 5);
		text.setText("Score: 5");

		assertEquals(List.of("pause", "pause", "rounds 5.0"), calls);
		assertEquals(List.of("Resume"), host.buttonLabels());
		assertEquals(List.of("Rounds"), host.settingLabels());
		assertEquals(5, host.settingValue("Rounds"), 0);
		assertEquals(Optional.of("Score: 5"), host.textAt(10, 20));
		assertThrows(IllegalArgumentException.class, () -> host.changeSetting("Rounds", 101));
		assertThrows(IllegalStateException.class, () -> host.click("Pause"));

		text.move(30, 40);
		assertEquals(List.of(new FakeExerciseHost.ShownText("Score: 5", 30, 40, STYLE)), host.shownTexts());
		text.remove();
		pause.remove();
		assertEquals(List.of(), host.shownTexts());
		assertEquals(List.of(), host.buttonLabels());
	}

	@Test
	void parAndDelayListenersHearOnlyUserChanges() {
		final List<String> heard = new ArrayList<>();
		assertEquals(FakeExerciseHost.DEFAULT_PAR_TIME, host.parTime(), 0);
		assertFalse(host.isListeningForParTime());

		host.onParTimeChanged(par -> heard.add("par " + par));
		host.onDelayedStartChanged(range -> heard.add("delay " + range.minSeconds() + "-" + range.maxSeconds()));
		host.setParTime(4.0);
		host.setDelayedStart(new DelayRange(5, 8));

		assertEquals(List.of(), heard);
		assertEquals(4.0, host.parTime(), 0);
		assertEquals(new DelayRange(5, 8), host.delayedStart());

		host.changeParTime(2.5);
		host.changeDelayedStart(new DelayRange(1, 2));

		assertEquals(List.of("par 2.5", "delay 1-2"), heard);
		assertEquals(2.5, host.parTime(), 0);
		assertTrue(host.isListeningForParTime());
		assertTrue(host.isListeningForDelayedStart());
	}

	@Test
	void stopRemovesEverythingAndCancelsTasks() {
		final Recorder exercise = new Recorder();
		host.start(exercise);
		host.addTarget("targets/IPSC.target", 0, 0);
		host.showText("Round: 1", 0, 0, STYLE);
		host.addButton("Pause", () -> {});
		host.addNumberSetting("Rounds", 1, 0, 2, 1, value -> {});
		host.addColumn("Score");
		host.showShotMarker(1, 1, new ShotStyle(ShotColor.RED));
		host.setBackground("backgrounds/black.png");
		host.onParTimeChanged(par -> {});
		host.pauseShotDetection(true);
		final List<String> ran = new ArrayList<>();
		host.schedule(() -> ran.add("late"), Duration.ofSeconds(1));

		host.stop();
		host.advance(Duration.ofSeconds(2));

		assertEquals(List.of("start", "stop"), exercise.events);
		assertEquals(List.of(), ran);
		assertTrue(host.isStopped());
		assertEquals(List.of(), host.targets());
		assertEquals(List.of(), host.shownTexts());
		assertEquals(List.of(), host.buttonLabels());
		assertEquals(List.of(), host.settingLabels());
		assertEquals(List.of(), host.columns());
		assertEquals(List.of(), host.shotMarkers());
		assertEquals(Optional.empty(), host.background());
		assertFalse(host.isListeningForParTime());
		assertFalse(host.isShotDetectionPaused());
		assertEquals(0, host.pendingTasks());

		// Calls after stop change nothing
		host.showText("late", 0, 0, STYLE);
		host.schedule(() -> ran.add("after stop"), Duration.ZERO);
		host.advance(Duration.ofSeconds(1));
		assertEquals(List.of(), host.shownTexts());
		assertEquals(List.of(), ran);
		assertFalse(host.shoot(ShotColor.RED, 1, 1));
	}

	@Test
	void soundsMessagesAndResourcesAreRecorded() throws IOException {
		host.playSound("sounds/beep.wav");
		host.playSounds(List.of("a.wav", "b.wav"));
		host.say("Make ready");
		host.showMessage("Score: 1");

		assertEquals(List.of("sounds/beep.wav", "a.wav", "b.wav"), host.sounds());
		assertEquals(List.of("Make ready"), host.spoken());
		assertEquals(List.of("Score: 1"), host.messages());

		// resource() reads the exercise's jar: the test classpath by default
		try (InputStream in = host.resource("/com/shootoff/exercise/FakeExerciseHost.class").get()) {
			assertTrue(in.read() >= 0);
		}
		assertEquals(Optional.empty(), host.resource("no/such/resource"));
		assertEquals(temp.resolve("data"), host.dataDirectory());
		assertTrue(Files.isDirectory(host.dataDirectory()));
	}
}
```

Add this test to `TestNoJavaFxInPluginApi`:

```java
	@Test
	void testFixturesDoNotReferenceJavaFx() throws Exception {
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferencesNextTo(FakeExerciseHost.class));
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :plugin-api:test --console=plain`
Expected: FAIL at compile time: `cannot find symbol: class FakeExerciseHost`.

- [ ] **Step 3: Add the test-fixtures source set**

In `plugin-api/build.gradle.kts`, change the `plugins` block to:

```kotlin
plugins {
    `java-library`
    // FakeExerciseHost, published as plugin-api's test-fixtures variant for exercise tests
    `java-test-fixtures`
    `maven-publish`
}
```

- [ ] **Step 4: Write `FakeExerciseHost`**

`plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java` (test fixtures take no GPL header):

```java
package com.shootoff.exercise;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.ResourceResolver;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;
import com.shootoff.targets.model.TargetSet;

/**
 * An in-memory {@link ExerciseHost} for exercise tests. It records what the exercise shows, and the
 * test plays the user and the clock:
 * <ul>
 * <li>{@link #start}, {@link #reset} and {@link #stop} drive the exercise's lifecycle;</li>
 * <li>{@link #shoot(ShotColor, double, double)} fires a shot, hit-tested against the exercise's
 * visible targets;</li>
 * <li>{@link #advance} moves the manual clock and runs the tasks that fall due;</li>
 * <li>{@link #click}, {@link #changeSetting}, {@link #changeParTime} and {@link #changeDelayedStart}
 * use the controls.</li>
 * </ul>
 * Everything runs on the calling thread, which plays the exercise thread.
 */
public class FakeExerciseHost implements ExerciseHost {
	public static final Size DEFAULT_SURFACE = new Size(1280, 720);
	/** The clock's time, in milliseconds, when the host is created */
	public static final long START_TIME = 1_000_000;
	/** What ShootOFF's shared controls show until an exercise sets its own values */
	public static final double DEFAULT_PAR_TIME = 2.0;
	public static final DelayRange DEFAULT_DELAYED_START = new DelayRange(4, 8);

	public record ShownText(String text, double x, double y, TextStyle style) {}

	public record ShownMarker(double x, double y, ShotStyle style) {}

	/**
	 * A shot timer row: the shot, the exercise's column values, and its highlight
	 */
	public record Row(Shot shot, Map<String, String> values, Optional<RowStyle> style) {}

	private final Size surfaceSize;
	private final boolean projector;
	private final Path dataDirectory;
	private ClassLoader resources = Thread.currentThread().getContextClassLoader();

	private final TargetSet targetSet = new TargetSet();
	private final List<FakeText> texts = new ArrayList<>();
	private final List<FakeButton> buttons = new ArrayList<>();
	private final Map<String, FakeSetting> settings = new LinkedHashMap<>();
	private final List<String> columns = new ArrayList<>();
	private final List<FakeRow> rows = new ArrayList<>();
	private final List<FakeMarker> markers = new ArrayList<>();
	private final List<String> sounds = new ArrayList<>();
	private final List<String> spoken = new ArrayList<>();
	private final List<String> messages = new ArrayList<>();
	private final List<DoubleConsumer> parListeners = new ArrayList<>();
	private final List<Consumer<DelayRange>> delayListeners = new ArrayList<>();
	private final PriorityQueue<Task> tasks = new PriorityQueue<>(
			Comparator.comparingLong((Task task) -> task.due).thenComparingLong(task -> task.sequence));

	private Optional<Exercise> exercise = Optional.empty();
	private Optional<String> background = Optional.empty();
	private boolean detectionPaused = false;
	private boolean stopped = false;
	private double parTime = DEFAULT_PAR_TIME;
	private DelayRange delayedStart = DEFAULT_DELAYED_START;
	private long now = START_TIME;
	private long nextSequence = 0;

	/**
	 * A projector host with the default surface and a new temporary data directory.
	 */
	public FakeExerciseHost() {
		this(DEFAULT_SURFACE, true, newTemporaryDirectory());
	}

	public FakeExerciseHost(Size surfaceSize, boolean projector, Path dataDirectory) {
		this.surfaceSize = surfaceSize;
		this.projector = projector;
		this.dataDirectory = dataDirectory;

		try {
			Files.createDirectories(dataDirectory);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path newTemporaryDirectory() {
		try {
			return Files.createTempDirectory("exercise-data");
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Sets where resources of "the exercise's jar" come from.
	 */
	public FakeExerciseHost withResources(ClassLoader resources) {
		this.resources = resources;
		return this;
	}

	// ---- The test's side: lifecycle, shots, the clock and the user

	public void start(Exercise exercise) {
		this.exercise = Optional.of(exercise);
		exercise.start(this);
	}

	/**
	 * Presses Reset: clears the shot timer, then calls the exercise's <tt>onReset</tt>.
	 */
	public void reset() {
		if (stopped) return;
		rows.clear();
		exercise.ifPresent(Exercise::onReset);
	}

	/**
	 * Stops the exercise, then removes everything it added and cancels its tasks, as ShootOFF does.
	 */
	public void stop() {
		if (stopped) return;
		exercise.ifPresent(Exercise::stop);
		stopped = true;

		for (final PlacedTarget target : targetSet.getTargets()) {
			targetSet.remove(target.getId());
		}
		texts.clear();
		buttons.clear();
		settings.clear();
		columns.clear();
		markers.clear();
		parListeners.clear();
		delayListeners.clear();
		tasks.clear();
		background = Optional.empty();
		detectionPaused = false;
	}

	/**
	 * Fires a shot at (<tt>x</tt>, <tt>y</tt>), hit-tested against the exercise's visible targets.
	 *
	 * @return <tt>false</tt> if the shot wasn't delivered: detection is paused, or the exercise isn't
	 *         running
	 */
	public boolean shoot(ShotColor color, double x, double y) {
		return shoot(new Shot(color, x, y, now), HitTester.hit(targetSet, x, y));
	}

	/**
	 * Delivers a shot with a hit of the test's choosing.
	 */
	public boolean shoot(Shot shot, Optional<Hit> hit) {
		if (detectionPaused || stopped || exercise.isEmpty()) return false;

		rows.add(new FakeRow(shot));
		exercise.get().onShot(shot, hit);
		return true;
	}

	/**
	 * Moves the clock forward, running every task that falls due on the way, in time order (tasks
	 * due at the same time run in the order they were scheduled).
	 */
	public void advance(Duration duration) {
		final long end = now + duration.toMillis();

		while (!tasks.isEmpty() && tasks.peek().due <= end) {
			final Task task = tasks.poll();
			now = task.due;

			if (task.period > 0) {
				// Rescheduled before it runs, so it can cancel itself
				task.due += task.period;
				task.sequence = nextSequence++;
				tasks.add(task);
			}

			task.action.run();
		}

		now = end;
	}

	public int pendingTasks() {
		return tasks.size();
	}

	public void click(String label) {
		final FakeButton button = buttons.stream().filter(b -> b.label.equals(label)).findFirst()
				.orElseThrow(() -> new IllegalStateException("No button labeled " + label + "; the buttons are "
						+ buttonLabels()));
		button.onClick.run();
	}

	public void changeSetting(String label, double value) {
		final FakeSetting setting = settings.get(label);
		if (setting == null) throw new IllegalStateException("No setting labeled " + label);
		if (value < setting.min || value > setting.max) {
			throw new IllegalArgumentException(String.format("%s must be between %s and %s, not %s", label,
					setting.min, setting.max, value));
		}

		setting.value = value;
		setting.onChange.accept(value);
	}

	/**
	 * The user sets the shared par time: the exercise's listeners hear it.
	 */
	public void changeParTime(double seconds) {
		parTime = seconds;
		for (final DoubleConsumer listener : List.copyOf(parListeners)) {
			listener.accept(seconds);
		}
	}

	/**
	 * The user sets the shared delay: the exercise's listeners hear it.
	 */
	public void changeDelayedStart(DelayRange range) {
		delayedStart = range;
		for (final Consumer<DelayRange> listener : List.copyOf(delayListeners)) {
			listener.accept(range);
		}
	}

	// ---- The test's side: what the exercise shows

	public List<ShownText> shownTexts() {
		return texts.stream().map(t -> new ShownText(t.text, t.x, t.y, t.style)).toList();
	}

	/**
	 * @return the latest text shown with its top-left corner at (<tt>x</tt>, <tt>y</tt>)
	 */
	public Optional<String> textAt(double x, double y) {
		Optional<String> found = Optional.empty();
		for (final FakeText text : texts) {
			if (text.x == x && text.y == y) found = Optional.of(text.text);
		}
		return found;
	}

	public List<String> messages() {
		return List.copyOf(messages);
	}

	public List<String> buttonLabels() {
		return buttons.stream().map(b -> b.label).toList();
	}

	public List<String> settingLabels() {
		return List.copyOf(settings.keySet());
	}

	public double settingValue(String label) {
		final FakeSetting setting = settings.get(label);
		if (setting == null) throw new IllegalStateException("No setting labeled " + label);
		return setting.value;
	}

	public List<String> columns() {
		return List.copyOf(columns);
	}

	public List<Row> rows() {
		return rows.stream().map(r -> new Row(r.shot, Map.copyOf(r.values), r.style)).toList();
	}

	public List<ShownMarker> shotMarkers() {
		return markers.stream().map(m -> new ShownMarker(m.x, m.y, m.style)).toList();
	}

	/**
	 * @return every sound played, in order, as the exercise named it
	 */
	public List<String> sounds() {
		return List.copyOf(sounds);
	}

	public List<String> spoken() {
		return List.copyOf(spoken);
	}

	public Optional<String> background() {
		return background;
	}

	public boolean isShotDetectionPaused() {
		return detectionPaused;
	}

	public boolean isStopped() {
		return stopped;
	}

	public boolean isVisible(TargetHandle target) {
		return targetSet.get(target.id()).map(PlacedTarget::isVisible).orElse(false);
	}

	public boolean isListeningForParTime() {
		return !parListeners.isEmpty();
	}

	public boolean isListeningForDelayedStart() {
		return !delayListeners.isEmpty();
	}

	// ---- ExerciseHost

	@Override
	public Size surfaceSize() {
		return surfaceSize;
	}

	@Override
	public boolean isProjector() {
		return projector;
	}

	@Override
	public void setBackground(String imageResource) {
		if (!stopped && projector) background = Optional.of(imageResource);
	}

	@Override
	public Optional<TargetHandle> addTarget(String targetFile, double x, double y) {
		if (stopped) return Optional.empty();

		final Optional<TargetDefinition> definition = loadDefinition(targetFile);
		if (definition.isEmpty()) return Optional.empty();

		final PlacedTarget placed = targetSet.add(definition.get(), Placement.ORIGIN.withPosition(x, y));
		return Optional.of(new FakeTarget(placed.getId(), placed.getDefinition()));
	}

	private Optional<TargetDefinition> loadDefinition(String targetFile) {
		final String name = ExercisePaths.resourceName(targetFile);

		try {
			if (!name.isEmpty() && resources.getResource(name) != null) {
				try (InputStream in = resources.getResourceAsStream(name)) {
					return Optional.of(TargetDefinitions.load(in, ResourceResolver.classLoader(resources)));
				}
			}

			final Optional<File> file = ExercisePaths.shootoffFile(targetFile, "targets");
			if (file.isEmpty()) return Optional.empty();
			return Optional.of(TargetDefinitions.load(file.get().toPath()));
		} catch (final IOException | TargetFormatException e) {
			return Optional.empty();
		}
	}

	@Override
	public List<TargetHandle> targets() {
		final List<TargetHandle> handles = new ArrayList<>();
		for (final PlacedTarget target : targetSet.getTargets()) {
			handles.add(new FakeTarget(target.getId(), target.getDefinition()));
		}
		return handles;
	}

	@Override
	public TextHandle showText(String text, double x, double y, TextStyle style) {
		final FakeText shown = new FakeText(text, x, y, style);
		if (!stopped) texts.add(shown);

		return new TextHandle() {
			@Override
			public void setText(String newText) {
				shown.text = newText;
			}

			@Override
			public void move(double newX, double newY) {
				shown.x = newX;
				shown.y = newY;
			}

			@Override
			public void remove() {
				texts.remove(shown);
			}
		};
	}

	@Override
	public void showMessage(String message) {
		if (!stopped) messages.add(message);
	}

	@Override
	public ButtonHandle addButton(String label, Runnable onClick) {
		final FakeButton button = new FakeButton(label, onClick);
		if (!stopped) buttons.add(button);

		return new ButtonHandle() {
			@Override
			public void setLabel(String newLabel) {
				button.label = newLabel;
			}

			@Override
			public void remove() {
				buttons.remove(button);
			}
		};
	}

	@Override
	public void addNumberSetting(String label, double initial, double min, double max, double step,
			DoubleConsumer onChange) {
		if (!stopped) settings.put(label, new FakeSetting(initial, min, max, onChange));
	}

	@Override
	public void addColumn(String name) {
		if (!stopped && !columns.contains(name)) columns.add(name);
	}

	@Override
	public void setColumnValue(String name, String value) {
		if (!stopped && !rows.isEmpty()) rows.get(rows.size() - 1).values.put(name, value);
	}

	@Override
	public void styleLastRow(RowStyle style) {
		if (!stopped && !rows.isEmpty()) rows.get(rows.size() - 1).style = Optional.of(style);
	}

	@Override
	public ShotMarkerHandle showShotMarker(double x, double y, ShotStyle style) {
		final FakeMarker marker = new FakeMarker(x, y, style);
		if (!stopped) markers.add(marker);
		return () -> markers.remove(marker);
	}

	@Override
	public void clearShots() {
		if (stopped) return;
		rows.clear();
		markers.clear();
	}

	@Override
	public void pauseShotDetection(boolean paused) {
		if (!stopped) detectionPaused = paused;
	}

	@Override
	public void playSound(String resourceOrFile) {
		if (!stopped) sounds.add(resourceOrFile);
	}

	@Override
	public void playSounds(List<String> resourcesOrFiles) {
		if (!stopped) sounds.addAll(resourcesOrFiles);
	}

	@Override
	public void say(String text) {
		if (!stopped) spoken.add(text);
	}

	@Override
	public long currentTimeMillis() {
		return now;
	}

	@Override
	public Cancellable schedule(Runnable task, Duration delay) {
		return schedule(task, delay.toMillis(), 0);
	}

	@Override
	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return schedule(task, initialDelay.toMillis(), Math.max(1, period.toMillis()));
	}

	private Cancellable schedule(Runnable action, long delayMillis, long periodMillis) {
		if (stopped) return () -> {};

		final Task task = new Task(now + delayMillis, periodMillis, nextSequence++, action);
		tasks.add(task);
		return () -> tasks.remove(task);
	}

	@Override
	public Optional<InputStream> resource(String path) {
		return Optional.ofNullable(resources.getResourceAsStream(ExercisePaths.resourceName(path)));
	}

	@Override
	public Path dataDirectory() {
		return dataDirectory;
	}

	@Override
	public double parTime() {
		return parTime;
	}

	@Override
	public void setParTime(double seconds) {
		if (!stopped) parTime = seconds;
	}

	@Override
	public void onParTimeChanged(DoubleConsumer listener) {
		if (!stopped) parListeners.add(listener);
	}

	@Override
	public DelayRange delayedStart() {
		return delayedStart;
	}

	@Override
	public void setDelayedStart(DelayRange range) {
		if (!stopped) delayedStart = range;
	}

	@Override
	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		if (!stopped) delayListeners.add(listener);
	}

	// ---- What the host keeps

	private final class FakeTarget implements TargetHandle {
		private final TargetId id;
		private final TargetDefinition definition;

		FakeTarget(TargetId id, TargetDefinition definition) {
			this.id = id;
			this.definition = definition;
		}

		private PlacedTarget placed() {
			return targetSet.get(id).orElseThrow(() -> new IllegalStateException("The target was removed"));
		}

		@Override
		public TargetId id() {
			return id;
		}

		@Override
		public void move(double x, double y) {
			targetSet.move(id, x, y);
		}

		@Override
		public void resize(double width, double height) {
			targetSet.resize(id, width, height);
		}

		@Override
		public void setVisible(boolean visible) {
			targetSet.setVisible(id, visible);
		}

		@Override
		public void remove() {
			targetSet.remove(id);
		}

		@Override
		public Point position() {
			return placed().getPosition();
		}

		@Override
		public Size size() {
			return placed().getSize();
		}

		@Override
		public TargetDefinition definition() {
			return definition;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof FakeTarget other && other.id.equals(id);
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}
	}

	private static final class FakeText {
		String text;
		double x;
		double y;
		final TextStyle style;

		FakeText(String text, double x, double y, TextStyle style) {
			this.text = text;
			this.x = x;
			this.y = y;
			this.style = style;
		}
	}

	private static final class FakeButton {
		String label;
		final Runnable onClick;

		FakeButton(String label, Runnable onClick) {
			this.label = label;
			this.onClick = onClick;
		}
	}

	private static final class FakeSetting {
		double value;
		final double min;
		final double max;
		final DoubleConsumer onChange;

		FakeSetting(double value, double min, double max, DoubleConsumer onChange) {
			this.value = value;
			this.min = min;
			this.max = max;
			this.onChange = onChange;
		}
	}

	private static final class FakeRow {
		final Shot shot;
		final Map<String, String> values = new LinkedHashMap<>();
		Optional<RowStyle> style = Optional.empty();

		FakeRow(Shot shot) {
			this.shot = shot;
		}
	}

	private record FakeMarker(double x, double y, ShotStyle style) {}

	private static final class Task {
		long due;
		final long period;
		long sequence;
		final Runnable action;

		Task(long due, long period, long sequence, Runnable action) {
			this.due = due;
			this.period = period;
			this.sequence = sequence;
			this.action = action;
		}
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :plugin-api:test --console=plain`
Expected: `BUILD SUCCESSFUL`. `TestFakeExerciseHost` (10) passes, and `TestNoJavaFxInPluginApi` (2) passes, now scanning the plugin-api and test-fixtures jars.

- [ ] **Step 6: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 11 (**331**).

- [ ] **Step 7: Commit**

```bash
git add plugin-api/build.gradle.kts plugin-api/src/testFixtures/java/com/shootoff/exercise/FakeExerciseHost.java \
  plugin-api/src/test/java/com/shootoff/exercise/TestFakeExerciseHost.java plugin-api/src/test/java/com/shootoff/exercise/TestNoJavaFxInPluginApi.java
git status --short
git commit -m "Add FakeExerciseHost to plugin-api's test fixtures"
git log -1 --format=%B
```

`git status --short` must show only ` M shootoff.properties` (plus the owner's `??` files, if any) besides the staged files.

---
### Task 4: The plugin engine in `core`, with v1 and v2 loaders

Runs in ShootOFF on `compose-ui`.

**Files:**
- Move (`git mv`) from `javafx-app/src/main/java/com/shootoff/plugins/engine/` to `core/src/main/java/com/shootoff/plugins/engine/`: `PluginEngine.java`, `Plugin.java`, `PluginType.java`, `PluginListener.java`. Then rewrite `PluginEngine`, `Plugin` and `PluginListener`. `PluginType` is unchanged.
- Create in `core/src/main/java/com/shootoff/plugins/engine/`: `PluginDescriptor.java`, `ExerciseEntry.java`, `ExerciseLoader.java`
- Create in `plugin-api/src/main/java/com/shootoff/plugins/engine/`: `V2ExerciseLoader.java`, `V2ExerciseEntry.java`
- Create in `javafx-app/src/main/java/com/shootoff/plugins/engine/`: `LegacyExerciseLoader.java`, `LegacyExerciseEntry.java`, `ExerciseLoaders.java`
- Create: `javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java`
- Modify: `javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java`, `gui/controller/ShootOFFController.java`, `gui/controller/PluginManagerController.java`
- Create: `core/src/testFixtures/java/com/shootoff/plugins/engine/PluginJars.java`
- Test (new): `core/src/test/java/com/shootoff/plugins/engine/TestPluginDescriptor.java`, `TestPluginEngineRegistration.java`; `javafx-app/src/test/java/com/shootoff/plugins/engine/TestPluginLoading.java`; `javafx-app/src/test/java/com/shootoff/gui/pane/TestExerciseSlide.java`; `javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`
- Test (bodies adapted, names kept): `javafx-app/src/test/java/com/shootoff/plugins/engine/{TestPlugin,TestPluginEngine,TestPluginDescriptorIsolation}.java`

**Interfaces:**
- Consumes: `ExerciseMetadata` (core, Task 2), `Exercise` (plugin-api, Task 2), `com.shootoff.util.VersionChecker` (core).
- Produces:
  - `core`, package `com.shootoff.plugins.engine`:
    - `record PluginDescriptor(int apiVersion, String exerciseClass)`. `static PluginDescriptor read(URLClassLoader loader, Path jarPath) throws IOException` reads only the loader's own jar (`findResource`). A missing `apiVersion` means 1. It throws `IllegalArgumentException` for a missing descriptor, malformed XML, a non-numeric `apiVersion` or a missing `exerciseClass`.
    - `interface ExerciseEntry`: `ExerciseMetadata metadata()`, `boolean isProjectorOnly()`, `Class<?> exerciseClass()`. One Training menu item.
    - `interface ExerciseLoader`: `int apiVersion()`, `ExerciseEntry load(Class<?>)`. `load` throws `IllegalArgumentException` for a class it can't run.
    - `interface PluginListener`: `registerExercise(ExerciseEntry)`, `registerProjectorExercise(ExerciseEntry)`, `unregisterExercise(ExerciseEntry)`
    - `class Plugin`: `Plugin(Path jarPath, List<ExerciseLoader> loaders) throws IOException` (throws `IllegalArgumentException` for a jar no loader can load), `getLoader()`, `getEntry()`, `getJarPath()`, `getApiVersion()`, `getType()`
    - `class PluginEngine`: `PluginEngine(PluginListener, List<ExerciseLoader>, List<ExerciseEntry> builtIns) throws IOException`, `getPlugins()`, `Optional<Plugin> getPlugin(ExerciseMetadata)`, `startWatching()`, `stopWatching()`
  - `plugin-api`, package `com.shootoff.plugins.engine`:
    - `final class V2ExerciseLoader implements ExerciseLoader` (API version 2)
    - `record V2ExerciseEntry(Class<? extends Exercise> exerciseClass, ExerciseMetadata metadata) implements ExerciseEntry` with `Exercise newInstance() throws ReflectiveOperationException`
  - `javafx-app`:
    - `LegacyExerciseLoader` (API version 1)
    - `record LegacyExerciseEntry(TrainingExercise prototype, boolean isProjectorOnly) implements ExerciseEntry`
    - `ExerciseLoaders.all()` (`List<ExerciseLoader>`: legacy, then v2)
    - `com.shootoff.plugins.BuiltInExercises.entries()` (`List<ExerciseEntry>`: the ten built-ins in menu order)
  - `core` test fixtures: `PluginJars.build(Path dir, String jarName, Optional<String> descriptor, Map<String,String> sources, String classpath) → Path` (compiles the sources and jars them, with `descriptor` as `shootoff.xml`), and `PluginJars.descriptor(int apiVersion, String exerciseClass) → String`

- [ ] **Step 1: Write the failing tests**

`core/src/testFixtures/java/com/shootoff/plugins/engine/PluginJars.java` (test fixtures take no GPL header):

```java
package com.shootoff.plugins.engine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

/**
 * Builds plugin jars for tests, the way a plugin author's build would.
 */
public final class PluginJars {
	private PluginJars() {}

	/**
	 * @return a <tt>shootoff.xml</tt> naming <tt>exerciseClass</tt>; an <tt>apiVersion</tt> of 1 is
	 *         left out, as v1 plugins do
	 */
	public static String descriptor(int apiVersion, String exerciseClass) {
		final String version = apiVersion == 1 ? "" : " apiVersion=\"" + apiVersion + "\"";
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<shootoffExercise" + version + " exerciseClass=\""
				+ exerciseClass + "\" />\n";
	}

	/**
	 * Compiles <tt>sources</tt> (class name to source code) against <tt>classpath</tt> and jars the
	 * classes as <tt>dir/jarName</tt>, with <tt>descriptor</tt> as its <tt>shootoff.xml</tt> if present.
	 * The build files go in <tt>dir/jarName.build</tt>.
	 */
	public static Path build(Path dir, String jarName, Optional<String> descriptor, Map<String, String> sources,
			String classpath) throws IOException {
		final Path work = Files.createDirectories(dir.resolve(jarName + ".build"));
		final Path classes = Files.createDirectories(work.resolve("classes"));

		if (!sources.isEmpty()) {
			final List<String> args = new ArrayList<>(List.of("-classpath", classpath, "-d", classes.toString()));
			for (final Map.Entry<String, String> source : sources.entrySet()) {
				final Path file = work.resolve("src").resolve(source.getKey().replace('.', '/') + ".java");
				Files.createDirectories(file.getParent());
				Files.writeString(file, source.getValue());
				args.add(file.toString());
			}

			if (ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])) != 0) {
				throw new IllegalStateException("The test plugin's sources didn't compile");
			}
		}

		final Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

		final Path jar = dir.resolve(jarName);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest);
				Stream<Path> files = Files.walk(classes)) {
			if (descriptor.isPresent()) add(out, "shootoff.xml", descriptor.get().getBytes(StandardCharsets.UTF_8));

			for (final Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				add(out, classes.relativize(file).toString().replace(File.separatorChar, '/'), Files.readAllBytes(file));
			}
		}

		return jar;
	}

	private static void add(JarOutputStream out, String name, byte[] contents) throws IOException {
		out.putNextEntry(new JarEntry(name));
		out.write(contents);
		out.closeEntry();
	}
}
```

`core/src/test/java/com/shootoff/plugins/engine/TestPluginDescriptor.java`:

```java
package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestPluginDescriptor {
	@TempDir Path temp;

	private PluginDescriptor read(String name, Optional<String> descriptor, ClassLoader parent) throws IOException {
		final Path jar = PluginJars.build(temp, name, descriptor, Map.of(), "");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() }, parent)) {
			return PluginDescriptor.read(loader, jar);
		}
	}

	@Test
	void apiVersionDefaultsToOne() throws IOException {
		assertEquals(new PluginDescriptor(1, "a.Drill"),
				read("v1.jar", Optional.of(PluginJars.descriptor(1, "a.Drill")), null));
	}

	@Test
	void apiVersionTwoIsRead() throws IOException {
		assertEquals(new PluginDescriptor(2, "a.Drill"),
				read("v2.jar", Optional.of(PluginJars.descriptor(2, "a.Drill")), null));
	}

	@Test
	void nonNumericApiVersionIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> read("bad.jar",
				Optional.of("<shootoffExercise apiVersion=\"two\" exerciseClass=\"a.Drill\" />"), null));
	}

	@Test
	void missingExerciseClassIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> read("noclass.jar", Optional.of("<shootoffExercise apiVersion=\"2\" />"), null));
	}

	@Test
	void descriptorOnTheParentClassPathIsIgnored() throws IOException {
		final Path classpath = Files.createDirectories(temp.resolve("classpath"));
		Files.writeString(classpath.resolve("shootoff.xml"), PluginJars.descriptor(1, "com.shootoff.plugins.ShootForScore"));

		try (URLClassLoader parent = new URLClassLoader(new URL[] { classpath.toUri().toURL() }, null)) {
			// getResource would find the parent's descriptor; the plugin's own jar has none
			assertNotNull(parent.getResource("shootoff.xml"));
			assertThrows(IllegalArgumentException.class, () -> read("bare.jar", Optional.empty(), parent));
		}
	}
}
```

`core/src/test/java/com/shootoff/plugins/engine/TestPluginEngineRegistration.java`:

```java
package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.plugins.ExerciseMetadata;

class TestPluginEngineRegistration {
	@TempDir Path plugins;
	private String previousPlugins;
	private final List<String> events = new ArrayList<>();

	private record TestEntry(ExerciseMetadata metadata, boolean isProjectorOnly, Class<?> exerciseClass)
			implements ExerciseEntry {}

	// Loads classes that implement Supplier<String>, returning "name|version|projectorOnly"
	private static final ExerciseLoader TEST_LOADER = new ExerciseLoader() {
		@Override
		public int apiVersion() {
			return 2;
		}

		@Override
		public ExerciseEntry load(Class<?> exerciseClass) {
			try {
				@SuppressWarnings("unchecked")
				final String[] info = ((Supplier<String>) exerciseClass.getDeclaredConstructor().newInstance()).get()
						.split("\\|");
				final boolean projectorOnly = Boolean.parseBoolean(info[2]);
				return new TestEntry(new ExerciseMetadata(info[0], info[1], "tester", "A test exercise", projectorOnly),
						projectorOnly, exerciseClass);
			} catch (final ReflectiveOperationException e) {
				throw new IllegalArgumentException(e);
			}
		}
	};

	private final PluginListener listener = new PluginListener() {
		@Override
		public void registerExercise(ExerciseEntry exercise) {
			events.add("+" + label(exercise));
		}

		@Override
		public void registerProjectorExercise(ExerciseEntry exercise) {
			events.add("+projector " + label(exercise));
		}

		@Override
		public void unregisterExercise(ExerciseEntry exercise) {
			events.add("-" + label(exercise));
		}
	};

	private static String label(ExerciseEntry exercise) {
		return exercise.metadata().getName() + " " + exercise.metadata().getVersion();
	}

	private static ExerciseEntry builtIn(String name, boolean projectorOnly) {
		return new TestEntry(new ExerciseMetadata(name, "1.0", "phrack", "A built-in", projectorOnly), projectorOnly,
				Object.class);
	}

	private void jar(String jarName, String className, String info, int apiVersion) throws IOException {
		final String simpleName = className.substring(className.lastIndexOf('.') + 1);
		final String source = "package test; public class " + simpleName
				+ " implements java.util.function.Supplier<String> { public String get() { return \"" + info + "\"; } }";
		PluginJars.build(plugins, jarName, Optional.of(PluginJars.descriptor(apiVersion, className)),
				Map.of(className, source), System.getProperty("java.class.path"));
	}

	// The names left in the menu after all the events
	private List<String> listed() {
		final List<String> listed = new ArrayList<>();
		for (final String event : events) {
			if (event.startsWith("-")) listed.remove(event.substring(1));
			else if (event.startsWith("+projector ")) listed.add(event.substring("+projector ".length()));
			else listed.add(event.substring(1));
		}
		return listed;
	}

	@BeforeEach
	void setUp() {
		previousPlugins = System.getProperty("shootoff.plugins");
		System.setProperty("shootoff.plugins", plugins.toString());
	}

	@AfterEach
	void tearDown() {
		if (previousPlugins == null) System.clearProperty("shootoff.plugins");
		else System.setProperty("shootoff.plugins", previousPlugins);
	}

	@Test
	void builtInsFramePluginsInMenuOrder() throws IOException {
		jar("drill.jar", "test.Drill", "Drill|1.0|false", 2);

		new PluginEngine(listener, List.of(TEST_LOADER),
				List.of(builtIn("Standard A", false), builtIn("Projector B", true), builtIn("Standard C", false)));

		assertEquals(List.of("+Standard A 1.0", "+Standard C 1.0", "+Drill 1.0", "+projector Projector B 1.0"), events);
	}

	@Test
	void newerVersionOfTheSameExerciseWins() throws IOException {
		jar("drill-v1.jar", "test.DrillOne", "Drill|1.1|true", 2);
		jar("drill-v2.jar", "test.DrillTwo", "Drill|2.0|true", 2);

		final PluginEngine engine = new PluginEngine(listener, List.of(TEST_LOADER), List.of());

		assertEquals(1, engine.getPlugins().size());
		assertEquals("2.0", engine.getPlugins().iterator().next().getEntry().metadata().getVersion());
		// Whichever jar was found first, only 2.0 is left in the menu
		assertEquals(List.of("Drill 2.0"), listed());
	}

	@Test
	void jarWithUnsupportedApiVersionIsSkipped() throws IOException {
		jar("future.jar", "test.Future", "Future|1.0|false", 3);
		jar("drill.jar", "test.Drill", "Drill|1.0|false", 2);

		final PluginEngine engine = new PluginEngine(listener, List.of(TEST_LOADER), List.of());

		assertEquals(1, engine.getPlugins().size());
		assertEquals(List.of("+Drill 1.0"), events);
	}
}
```

`javafx-app/src/test/java/com/shootoff/plugins/engine/TestPluginLoading.java`:

```java
package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExerciseBase;

class TestPluginLoading {
	private static final String V2_CLASS = "com.example.v2.V2Drill";
	private static final String V2_SOURCE = String.join("\n",
			"package com.example.v2;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.exercise.Exercise;",
			"import com.shootoff.exercise.ExerciseHost;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.targets.model.Hit;",
			"public class V2Drill implements Exercise {",
			"  @Override public ExerciseMetadata metadata() {",
			"    return new ExerciseMetadata(\"V2 Drill\", \"1.0\", \"ShootOFF tests\", \"Loaded from a v2 jar\", true);",
			"  }",
			"  @Override public void start(ExerciseHost host) {}",
			"  @Override public void onShot(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void onReset() {}",
			"  @Override public void stop() {}",
			"}");

	private static final String V1_CLASS = "com.example.v1.V1Drill";
	private static final String V1_SOURCE = String.join("\n",
			"package com.example.v1;",
			"import java.util.List;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.plugins.ProjectorTrainingExerciseBase;",
			"import com.shootoff.plugins.TrainingExercise;",
			"import com.shootoff.targets.Hit;",
			"import com.shootoff.targets.Target;",
			"public class V1Drill extends ProjectorTrainingExerciseBase implements TrainingExercise {",
			"  public V1Drill() {}",
			"  public V1Drill(List<Target> targets) { super(targets); }",
			"  @Override public void init() {}",
			"  @Override public void targetUpdate(Target target, TargetChange change) {}",
			"  @Override public ExerciseMetadata getInfo() {",
			"    return new ExerciseMetadata(\"V1 Drill\", \"1.1\", \"ShootOFF tests\", \"Loaded from a v1 jar\");",
			"  }",
			"  @Override public void shotListener(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void reset(List<Target> targets) {}",
			"}");

	@TempDir Path temp;

	// ShootOFF's classes, as a plugin author compiles against them
	private static String classpath() {
		return String.join(File.pathSeparator, System.getProperty("java.class.path"), location(TrainingExerciseBase.class),
				location(Exercise.class), location(Shot.class));
	}

	private static String location(Class<?> type) {
		return new File(type.getProtectionDomain().getCodeSource().getLocation().getPath()).getPath();
	}

	@Test
	void v2JarLoadsAsAnExercise() throws Exception {
		final Path jar = PluginJars.build(temp, "v2.jar", Optional.of(PluginJars.descriptor(2, V2_CLASS)),
				Map.of(V2_CLASS, V2_SOURCE), classpath());

		final Plugin plugin = new Plugin(jar, ExerciseLoaders.all());

		assertEquals(2, plugin.getApiVersion());
		assertEquals(PluginType.PROJECTOR_ONLY, plugin.getType());
		final V2ExerciseEntry entry = assertInstanceOf(V2ExerciseEntry.class, plugin.getEntry());
		assertEquals("V2 Drill", entry.metadata().getName());
		final Exercise exercise = entry.newInstance();
		assertEquals(V2_CLASS, exercise.getClass().getName());
		assertSame(plugin.getLoader(), exercise.getClass().getClassLoader());
	}

	@Test
	void v1JarLoadsThroughTheLegacyPath() throws Exception {
		// Like the installed RandomTargetParDrill.jar: a descriptor without apiVersion
		final Path jar = PluginJars.build(temp, "v1.jar", Optional.of(PluginJars.descriptor(1, V1_CLASS)),
				Map.of(V1_CLASS, V1_SOURCE), classpath());

		final Plugin plugin = new Plugin(jar, ExerciseLoaders.all());

		assertEquals(1, plugin.getApiVersion());
		assertEquals(PluginType.PROJECTOR_ONLY, plugin.getType());
		final LegacyExerciseEntry entry = assertInstanceOf(LegacyExerciseEntry.class, plugin.getEntry());
		assertInstanceOf(ProjectorTrainingExerciseBase.class, entry.prototype());
		assertEquals("V1 Drill", entry.metadata().getName());
		assertEquals(V1_CLASS, entry.exerciseClass().getName());
	}

	@Test
	void descriptorOnTheClasspathIsIgnored() throws Exception {
		// ShootOFF's test resources put a shootoff.xml naming ShootForScore on the classpath
		assertNotNull(TestPluginLoading.class.getClassLoader().getResource("shootoff.xml"));
		final Path bare = PluginJars.build(temp, "bare.jar", Optional.empty(), Map.of(V2_CLASS, V2_SOURCE),
				classpath());

		assertThrows(IllegalArgumentException.class, () -> new Plugin(bare, ExerciseLoaders.all()));
	}

	@Test
	void v2ClassThatIsNotAnExerciseIsRejected() throws Exception {
		final Path jar = PluginJars.build(temp, "notexercise.jar",
				Optional.of(PluginJars.descriptor(2, "com.example.v2.NotAnExercise")),
				Map.of("com.example.v2.NotAnExercise", "package com.example.v2; public class NotAnExercise {}"),
				classpath());

		assertThrows(IllegalArgumentException.class, () -> new Plugin(jar, ExerciseLoaders.all()));
	}
}
```

`javafx-app/src/test/java/com/shootoff/gui/pane/TestExerciseSlide.java`:

```java
package com.shootoff.gui.pane;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.gui.ExerciseListener;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.plugins.SteelChallenge;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.engine.LegacyExerciseEntry;
import com.shootoff.plugins.engine.PluginEngine;

import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

public class TestExerciseSlide {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private final VBox body = new VBox();
	private ExerciseSlide slide;

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		new Configuration(new String[0]);

		slide = new ExerciseSlide(new HBox(), body, new ExerciseListener() {
			@Override
			public void setProjectorExercise(TrainingExercise exercise) {}

			@Override
			public void setExercise(TrainingExercise exercise) {}

			@Override
			public PluginEngine getPluginEngine() {
				return null;
			}
		});
		slide.showBody();
	}

	// The names on the Training menu's buttons, both panes
	private List<String> menuNames() {
		final List<String> names = new ArrayList<>();
		for (final Node node : body.getChildren()) {
			if (!(node instanceof VBox panes)) continue;

			for (final Node pane : panes.getChildren()) {
				final ItemSelectionPane<?> items = (ItemSelectionPane<?>) ((TitledPane) pane).getContent();
				for (final Node button : ((Pane) items.getContent()).getChildren()) {
					names.add(((ButtonBase) button).getText());
				}
			}
		}
		return names;
	}

	@Test
	public void unregisteringAProjectorExerciseRemovesItsButton() {
		final LegacyExerciseEntry steel = new LegacyExerciseEntry(new SteelChallenge(), true);

		slide.registerProjectorExercise(steel);
		assertTrue(menuNames().contains("Steel Challenge"));

		slide.unregisterExercise(steel);
		assertFalse(menuNames().contains("Steel Challenge"));
	}
}
```

`javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java`:

```java
package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Rule;
import org.junit.Test;

import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.plugins.engine.ExerciseEntry;

public class TestBuiltInExercises {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	@Test
	public void tenBuiltInsInMenuOrder() {
		final List<ExerciseEntry> entries = BuiltInExercises.entries();

		assertEquals(List.of("ISSFStandardPistol", "RandomShoot", "ShootForScore", "TimedHolsterDrill", "ParForScore",
				"ParRandomShot", "BouncingTargets", "DuelingTree", "ShootDontShoot", "SteelChallenge"),
				entries.stream().map(entry -> entry.exerciseClass().getSimpleName()).toList());
		assertEquals(List.of(false, false, false, false, false, false, true, true, true, true),
				entries.stream().map(ExerciseEntry::isProjectorOnly).toList());
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.plugins.engine.*' --console=plain`
Expected: FAIL at compile time: `cannot find symbol` for `PluginDescriptor`, `ExerciseEntry`, `ExerciseLoader` and `PluginEngine`.

- [ ] **Step 3: Move the engine into `core`**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p core/src/main/java/com/shootoff/plugins/engine
for f in PluginEngine Plugin PluginType PluginListener; do
	git mv javafx-app/src/main/java/com/shootoff/plugins/engine/$f.java core/src/main/java/com/shootoff/plugins/engine/$f.java
done
```

Create `ExerciseEntry.java` in `core/src/main/java/com/shootoff/plugins/engine/` (GPL header, then):

```java
package com.shootoff.plugins.engine;

import com.shootoff.plugins.ExerciseMetadata;

/**
 * One exercise in the Training menu: a built-in exercise or one loaded from a plugin jar, of either
 * API version. Each user interface knows how to run the kinds it supports.
 */
public interface ExerciseEntry {
	ExerciseMetadata metadata();

	/**
	 * @return <tt>true</tt> if the exercise only runs on the projector arena
	 */
	boolean isProjectorOnly();

	Class<?> exerciseClass();
}
```

`ExerciseLoader.java` (GPL header, then):

```java
package com.shootoff.plugins.engine;

/**
 * Turns a plugin's exercise class into a Training menu entry, for one plugin API version (the
 * <tt>apiVersion</tt> in <tt>shootoff.xml</tt>).
 */
public interface ExerciseLoader {
	int apiVersion();

	/**
	 * @throws IllegalArgumentException
	 *             if <tt>exerciseClass</tt> isn't an exercise of this API version
	 */
	ExerciseEntry load(Class<?> exerciseClass);
}
```

`PluginDescriptor.java` (GPL header, then):

```java
package com.shootoff.plugins.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A plugin jar's <tt>shootoff.xml</tt>:
 * <tt>&lt;shootoffExercise apiVersion="2" exerciseClass="..." /&gt;</tt>. A descriptor without
 * <tt>apiVersion</tt> is a v1 plugin.
 */
public record PluginDescriptor(int apiVersion, String exerciseClass) {
	public static final String FILE_NAME = "shootoff.xml";

	private static final Logger logger = LoggerFactory.getLogger(PluginDescriptor.class);

	/**
	 * Reads the descriptor in <tt>loader</tt>'s own jar. The classpath is never searched: it may hold
	 * ShootOFF's or another plugin's descriptor.
	 *
	 * @throws IllegalArgumentException
	 *             if the jar has no descriptor, or it is malformed
	 */
	public static PluginDescriptor read(URLClassLoader loader, Path jarPath) throws IOException {
		final URL url = loader.findResource(FILE_NAME);
		if (url == null) {
			throw new IllegalArgumentException(
					String.format("The jarPath %s does not represent a valid ShootOFF plugin", jarPath));
		}

		final DescriptorHandler handler = new DescriptorHandler();

		// Don't cache the jar connection, otherwise the jar stays open and can't be replaced or deleted
		// while ShootOFF runs
		final URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		try (InputStream in = connection.getInputStream()) {
			SAXParserFactory.newInstance().newSAXParser().parse(in, handler);
		} catch (final ParserConfigurationException | SAXException e) {
			throw new IllegalArgumentException(
					String.format("%s's %s is malformed: %s", jarPath, FILE_NAME, e.getMessage()), e);
		}

		if (handler.exerciseClass == null) {
			throw new IllegalArgumentException(
					String.format("%s's %s doesn't name an exerciseClass", jarPath, FILE_NAME));
		}

		return new PluginDescriptor(handler.apiVersion, handler.exerciseClass);
	}

	private static final class DescriptorHandler extends DefaultHandler {
		private int apiVersion = 1;
		private String exerciseClass = null;

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			if (!"shootoffExercise".equals(qName)) {
				logger.warn("Unrecognized exercise settings tag ignored: {}", qName);
				return;
			}

			exerciseClass = attributes.getValue("exerciseClass");

			final String version = attributes.getValue("apiVersion");
			if (version != null) {
				try {
					apiVersion = Integer.parseInt(version.trim());
				} catch (final NumberFormatException e) {
					throw new SAXException("apiVersion must be a whole number, not " + version);
				}
			}
		}
	}
}
```

Replace the contents of `PluginListener.java` (it has no license header, and still has none):

```java
package com.shootoff.plugins.engine;

/**
 * Hears which exercises the Training menu should list.
 */
public interface PluginListener {
	void registerExercise(ExerciseEntry exercise);

	void registerProjectorExercise(ExerciseEntry exercise);

	void unregisterExercise(ExerciseEntry exercise);
}
```

In `Plugin.java`, keep the license header and replace everything after it:

```java
package com.shootoff.plugins.engine;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * An exercise loaded from a plugin jar, by the loader for its descriptor's API version.
 */
public class Plugin {
	private final Path jarPath;
	private final URLClassLoader loader;
	private final PluginDescriptor descriptor;
	private final ExerciseEntry entry;

	/**
	 * @throws IllegalArgumentException
	 *             if the jar isn't a plugin, or none of <tt>loaders</tt> can load its exercise
	 */
	public Plugin(final Path jarPath, final List<ExerciseLoader> loaders) throws IOException {
		this.jarPath = jarPath;
		loader = new URLClassLoader(new URL[] { jarPath.toUri().toURL() },
				Thread.currentThread().getContextClassLoader());

		try {
			descriptor = PluginDescriptor.read(loader, jarPath);

			final ExerciseLoader exerciseLoader = loaders.stream()
					.filter(candidate -> candidate.apiVersion() == descriptor.apiVersion()).findFirst()
					.orElseThrow(() -> new IllegalArgumentException(String.format(
							"%s needs plugin API version %d, which this ShootOFF doesn't support", jarPath,
							descriptor.apiVersion())));

			final Class<?> exerciseClass;
			try {
				exerciseClass = loader.loadClass(descriptor.exerciseClass());
			} catch (final ClassNotFoundException e) {
				throw new IllegalArgumentException(String.format("Configured exerciseClass (%s) was not found in %s",
						descriptor.exerciseClass(), jarPath), e);
			}

			entry = exerciseLoader.load(exerciseClass);
		} catch (final RuntimeException | IOException e) {
			loader.close();
			throw e;
		}
	}

	public URLClassLoader getLoader() {
		return loader;
	}

	public ExerciseEntry getEntry() {
		return entry;
	}

	public Path getJarPath() {
		return jarPath;
	}

	public int getApiVersion() {
		return descriptor.apiVersion();
	}

	public PluginType getType() {
		return entry.isProjectorOnly() ? PluginType.PROJECTOR_ONLY : PluginType.STANDARD;
	}

	@Override
	public int hashCode() {
		return jarPath.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof Plugin other && jarPath.equals(other.jarPath);
	}
}
```

In `PluginEngine.java`, keep the license header and the watcher (`startWatching`, `stopWatching`, `run`). Change the rest:

1. Imports: delete the ten built-in exercise imports and `com.shootoff.plugins.TrainingExercise`. Add `java.util.List` and `java.util.stream.Stream`.
2. Fields and constructor. Replace the fields from `pluginDir` to `plugins`, the constructor, `registerDefaultStandardTrainingExercises` and `registerDefaultProjectorExercises` with:

```java
	private final Path pluginDir;
	private final PluginListener pluginListener;
	private final List<ExerciseLoader> loaders;
	private final PathMatcher jarMatcher = FileSystems.getDefault().getPathMatcher("glob:*.jar");
	private final WatchService watcher = FileSystems.getDefault().newWatchService();
	private final Set<Plugin> plugins = new HashSet<>();

	private final AtomicBoolean watching = new AtomicBoolean(false);

	/**
	 * Lists the built-in exercises and the plugins in the <tt>shootoff.plugins</tt> folder, in the
	 * Training menu's order: standard built-ins, then plugins, then projector built-ins.
	 *
	 * @param loaders
	 *            one per plugin API version the app can run
	 * @param builtIns
	 *            the exercises that ship with the app
	 */
	public PluginEngine(final PluginListener pluginListener, final List<ExerciseLoader> loaders,
			final List<ExerciseEntry> builtIns) throws IOException {
		if (pluginListener == null) {
			throw new IllegalArgumentException("pluginListener cannot be null");
		}

		pluginDir = Paths.get(System.getProperty("shootoff.plugins"));
		this.pluginListener = pluginListener;
		this.loaders = List.copyOf(loaders);

		if (!Files.exists(pluginDir) && !pluginDir.toFile().mkdirs()) {
			logger.error("The path specified by shootoff.plugins doesn't exist and we couldn't create it.");
			return;
		}

		if (!Files.isDirectory(pluginDir)) {
			logger.error("Can't enumerate existing plugins or watch for new plugins because the "
					+ "shootoff.plugins property is not set to a directory");
		}

		for (final ExerciseEntry builtIn : builtIns) {
			if (!builtIn.isProjectorOnly()) pluginListener.registerExercise(builtIn);
		}

		enumerateExistingPlugins();

		for (final ExerciseEntry builtIn : builtIns) {
			if (builtIn.isProjectorOnly()) pluginListener.registerProjectorExercise(builtIn);
		}

		pluginDir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
	}
```

3. Replace `registerPlugin`, `unregisterPlugin`, `enumerateExistingPlugins`, `findPlugin` and `getPlugin` (keep `getPlugins`) with:

```java
	private boolean registerPlugin(final Path jarPath) {
		final Plugin registeringPlugin;

		try {
			registeringPlugin = new Plugin(jarPath, loaders);
		} catch (final Exception e) {
			logger.error("Error creating new plugin", e);
			return false;
		}

		// If the plugin already exists and the new plugin is newer, unregister the old plugin before
		// registering the new one. If the new plugin is actually older, don't load it
		final Optional<Plugin> existingPlugin = findPlugin(registeringPlugin);

		if (existingPlugin.isPresent()) {
			final Plugin existing = existingPlugin.get();
			final ExerciseMetadata existingMetadata = existing.getEntry().metadata();
			final ExerciseMetadata registeringMetadata = registeringPlugin.getEntry().metadata();

			if (VersionChecker.compareVersions(existingMetadata.getVersion(), registeringMetadata.getVersion()) == -1) {
				logger.debug("Registering plugin ({}, {}, {}, {}) is a newer duplicate of an "
						+ "already registered plugin ({}, {}, {}, {})", registeringMetadata.getName(),
						registeringMetadata.getVersion(), registeringMetadata.getCreator(),
						registeringPlugin.getJarPath(), existingMetadata.getName(), existingMetadata.getVersion(),
						existingMetadata.getCreator(), existing.getJarPath());
				unregisterPlugin(existing);
			} else {
				logger.debug("Registering plugin ({}, {}, {}, {}) is an older or same version duplicate of an "
						+ "already registered plugin ({}, {}, {}, {})", registeringMetadata.getName(),
						registeringMetadata.getVersion(), registeringMetadata.getCreator(),
						registeringPlugin.getJarPath(), existingMetadata.getName(), existingMetadata.getVersion(),
						existingMetadata.getCreator(), existing.getJarPath());
				return false;
			}
		}

		if (plugins.add(registeringPlugin)) {
			if (registeringPlugin.getEntry().isProjectorOnly()) {
				pluginListener.registerProjectorExercise(registeringPlugin.getEntry());
			} else {
				pluginListener.registerExercise(registeringPlugin.getEntry());
			}
		}

		return true;
	}

	private void unregisterPlugin(Plugin plugin) {
		pluginListener.unregisterExercise(plugin.getEntry());
		plugins.remove(plugin);
	}

	private void enumerateExistingPlugins() {
		try (Stream<Path> files = Files.walk(pluginDir)) {
			files.forEach(filePath -> {
				if (Files.isRegularFile(filePath) && jarMatcher.matches(filePath.getFileName())) {
					registerPlugin(filePath);
				}
			});
		} catch (final IOException e) {
			logger.error("Error enumerating existing external plugins", e);
		}
	}

	// Plugins are the same exercise if they have the same name and creator
	private Optional<Plugin> findPlugin(Plugin plugin) {
		final ExerciseMetadata newMetadata = plugin.getEntry().metadata();

		for (final Plugin p : plugins) {
			final ExerciseMetadata existingMetadata = p.getEntry().metadata();

			if (existingMetadata.getName().equals(newMetadata.getName())
					&& existingMetadata.getCreator().equals(newMetadata.getCreator())) {
				return Optional.of(p);
			}
		}

		return Optional.empty();
	}

	public Set<Plugin> getPlugins() {
		return plugins;
	}

	/**
	 * @return the plugin whose exercise has <tt>metadata</tt>; empty for a built-in exercise
	 */
	public Optional<Plugin> getPlugin(ExerciseMetadata metadata) {
		for (final Plugin p : plugins) {
			if (p.getEntry().metadata().equals(metadata)) return Optional.of(p);
		}

		return Optional.empty();
	}
```

`PluginEngine` keeps its imports of `ExerciseMetadata` and `VersionChecker`, which are both in `core` now.

- [ ] **Step 4: The v2 loader in `plugin-api`**

Create in `plugin-api/src/main/java/com/shootoff/plugins/engine/`, each with the GPL header.

`V2ExerciseEntry.java`:

```java
package com.shootoff.plugins.engine;

import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.ExerciseMetadata;

/**
 * A v2 exercise in the Training menu. Each run starts from a fresh instance.
 */
public record V2ExerciseEntry(Class<? extends Exercise> exerciseClass, ExerciseMetadata metadata)
		implements ExerciseEntry {
	@Override
	public boolean isProjectorOnly() {
		return metadata.isProjectorOnly();
	}

	public Exercise newInstance() throws ReflectiveOperationException {
		return exerciseClass.getDeclaredConstructor().newInstance();
	}
}
```

`V2ExerciseLoader.java`:

```java
package com.shootoff.plugins.engine;

import com.shootoff.exercise.Exercise;
import com.shootoff.plugins.ExerciseMetadata;

/**
 * Loads exercises of plugin API version 2: classes that implement {@link Exercise}.
 */
public final class V2ExerciseLoader implements ExerciseLoader {
	@Override
	public int apiVersion() {
		return 2;
	}

	@Override
	public ExerciseEntry load(Class<?> exerciseClass) {
		if (!Exercise.class.isAssignableFrom(exerciseClass)) {
			throw new IllegalArgumentException(String.format("Configured exerciseClass (%s) does not implement %s",
					exerciseClass.getName(), Exercise.class.getName()));
		}

		final Class<? extends Exercise> type = exerciseClass.asSubclass(Exercise.class);
		final ExerciseMetadata metadata;
		try {
			metadata = type.getDeclaredConstructor().newInstance().metadata();
		} catch (final ReflectiveOperationException e) {
			throw new IllegalArgumentException("Error instantiating configured exerciseClass " + type.getName(), e);
		}

		if (metadata == null) {
			throw new IllegalArgumentException(type.getName() + " returned no metadata");
		}

		return new V2ExerciseEntry(type, metadata);
	}
}
```

- [ ] **Step 5: The v1 loader and the built-ins in `javafx-app`**

Create in `javafx-app/src/main/java/com/shootoff/plugins/engine/`, each with the GPL header.

`LegacyExerciseEntry.java`:

```java
package com.shootoff.plugins.engine;

import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExercise;

/**
 * A v1 exercise in the Training menu. The app starts it by calling the prototype class's
 * <tt>(List&lt;Target&gt;)</tt> constructor, as it always has.
 */
public record LegacyExerciseEntry(TrainingExercise prototype, boolean isProjectorOnly) implements ExerciseEntry {
	@Override
	public ExerciseMetadata metadata() {
		return prototype.getInfo();
	}

	@Override
	public Class<?> exerciseClass() {
		return prototype.getClass();
	}
}
```

`LegacyExerciseLoader.java`:

```java
package com.shootoff.plugins.engine;

import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.TrainingExerciseBase;

/**
 * Loads exercises of plugin API version 1 (descriptors without <tt>apiVersion</tt>): direct
 * subclasses of {@link TrainingExerciseBase} or {@link ProjectorTrainingExerciseBase}.
 */
public final class LegacyExerciseLoader implements ExerciseLoader {
	@Override
	public int apiVersion() {
		return 1;
	}

	@Override
	public ExerciseEntry load(Class<?> exerciseClass) {
		final Class<?> superclass = exerciseClass.getSuperclass();
		final String superclassName = superclass == null ? "null" : superclass.getName();
		final boolean isStandard = TrainingExerciseBase.class.getName().equals(superclassName);
		final boolean isProjector = ProjectorTrainingExerciseBase.class.getName().equals(superclassName);

		if (!isStandard && !isProjector) {
			throw new IllegalArgumentException(String.format(
					"Configured exerciseClass (%s) does not have a known training exercise superclass, type is %s",
					exerciseClass.getName(), superclassName));
		}

		try {
			return new LegacyExerciseEntry((TrainingExercise) exerciseClass.getDeclaredConstructor().newInstance(),
					isProjector);
		} catch (final ReflectiveOperationException | ClassCastException e) {
			throw new IllegalArgumentException("Error instantiating configured exerciseClass " + exerciseClass.getName(),
					e);
		}
	}
}
```

`ExerciseLoaders.java`:

```java
package com.shootoff.plugins.engine;

import java.util.List;

/**
 * The plugin API versions the JavaFX app runs.
 */
public final class ExerciseLoaders {
	private ExerciseLoaders() {}

	public static List<ExerciseLoader> all() {
		return List.of(new LegacyExerciseLoader(), new V2ExerciseLoader());
	}
}
```

`javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java` (GPL header, then):

```java
package com.shootoff.plugins;

import java.util.List;

import com.shootoff.plugins.engine.ExerciseEntry;
import com.shootoff.plugins.engine.LegacyExerciseEntry;

/**
 * The exercises that ship with the JavaFX app, in Training menu order.
 */
public final class BuiltInExercises {
	private BuiltInExercises() {}

	public static List<ExerciseEntry> entries() {
		return List.of(standard(new ISSFStandardPistol()), standard(new RandomShoot()), standard(new ShootForScore()),
				standard(new TimedHolsterDrill()), standard(new ParForScore()), standard(new ParRandomShot()),
				projector(new BouncingTargets()), projector(new DuelingTree()), projector(new ShootDontShoot()),
				projector(new SteelChallenge()));
	}

	private static ExerciseEntry standard(TrainingExercise exercise) {
		return new LegacyExerciseEntry(exercise, false);
	}

	private static ExerciseEntry projector(TrainingExercise exercise) {
		return new LegacyExerciseEntry(exercise, true);
	}
}
```

- [ ] **Step 6: The Training menu and the controllers**

In `ExerciseSlide.java`:
- Add the imports `java.util.Map`, `java.util.concurrent.ConcurrentHashMap`, `com.shootoff.plugins.engine.ExerciseEntry` and `com.shootoff.plugins.engine.LegacyExerciseEntry`.
- Add a field after `projectorExerciseItemPane`:

```java
	// What each registered exercise's button hands to the ExerciseListener
	private final Map<ExerciseEntry, TrainingExercise> menuItems = new ConcurrentHashMap<>();
```

- Replace `registerExercise`, `registerProjectorExercise` and `unregisterExercise` with:

```java
	@Override
	public void registerExercise(ExerciseEntry exercise) {
		addMenuItem(exerciseItemPane, exercise);
	}

	@Override
	public void registerProjectorExercise(ExerciseEntry exercise) {
		addMenuItem(projectorExerciseItemPane, exercise);
	}

	@Override
	public void unregisterExercise(ExerciseEntry exercise) {
		final TrainingExercise item = menuItems.remove(exercise);
		if (item == null) return;

		// A projector exercise's button is in the projector pane
		(exercise.isProjectorOnly() ? projectorExerciseItemPane : exerciseItemPane).removeButton(item);
	}

	private void addMenuItem(ItemSelectionPane<TrainingExercise> pane, ExerciseEntry exercise) {
		final Optional<TrainingExercise> item = menuItem(exercise);
		if (item.isEmpty()) return;

		menuItems.put(exercise, item.get());

		final Tooltip t = new Tooltip(exercise.metadata().getDescription());
		t.setPrefWidth(500);
		t.setWrapText(true);
		pane.addButton(item.get(), exercise.metadata().getName(), Optional.empty(), Optional.of(t));
	}

	private static Optional<TrainingExercise> menuItem(ExerciseEntry exercise) {
		if (exercise instanceof LegacyExerciseEntry legacy) return Optional.of(legacy.prototype());

		logger.warn("{} is a v2 exercise; the JavaFX app can't run those yet", exercise.metadata());
		return Optional.empty();
	}
```

Task 6 replaces `menuItem`'s last two lines.

In `ShootOFFController.java`:
- Add the imports `com.shootoff.plugins.BuiltInExercises` and `com.shootoff.plugins.engine.ExerciseLoaders`.
- In `init`, replace `pluginEngine = new PluginEngine(exerciseSlide);` with:

```java
		pluginEngine = new PluginEngine(exerciseSlide, ExerciseLoaders.all(), BuiltInExercises.entries());
```

- In both `setExercise` and `setProjectorExercise`, replace `pluginEngine.getPlugin(newExercise)` with `pluginEngine.getPlugin(newExercise.getInfo())`.

In `PluginManagerController.java`, replace `p.getExercise().getInfo()` (in `findInstalledPlugin`) with `p.getEntry().metadata()`, and `installedPlugin.get().getExercise().getInfo().getVersion()` with `installedPlugin.get().getEntry().metadata().getVersion()`.

- [ ] **Step 7: Adapt the existing engine tests (names kept)**

In `TestPluginEngine.setUp`, replace the engine's construction with:

```java
		pe = new PluginEngine(new PluginListener() {
			@Override
			public void registerExercise(ExerciseEntry exercise) {}

			@Override
			public void registerProjectorExercise(ExerciseEntry exercise) {}

			@Override
			public void unregisterExercise(ExerciseEntry exercise) {}
		}, ExerciseLoaders.all(), List.of());
```

Replace `import com.shootoff.plugins.TrainingExercise;` with `import java.util.List;`. `ExerciseEntry` and `ExerciseLoaders` are in the test's own package.

In `TestPlugin`, change each `new Plugin(pluginDir.resolve(Paths.get("….jar")))` to `new Plugin(pluginDir.resolve(Paths.get("….jar")), ExerciseLoaders.all())`. Change `p.getExercise().getClass().getName()` to `p.getEntry().exerciseClass().getName()`, and `p.getExercise().getInfo().getName()` to `p.getEntry().metadata().getName()`.

In `TestPluginDescriptorIsolation`:
- Change both `new Plugin(…)` calls to pass `ExerciseLoaders.all()` as the second argument.
- Change `plugin.getExercise().getClass().getName()` to `plugin.getEntry().exerciseClass().getName()`.
- Change `plugin.getExercise().getInfo().getName()` to `plugin.getEntry().metadata().getName()`.

```bash
command grep -rn "getExercise()" javafx-app/src/test/java/com/shootoff/plugins/engine javafx-app/src/main/java/com/shootoff/gui/controller/PluginManagerController.java
```

Expected: nothing.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.plugins.*' --tests 'com.shootoff.TestNoJavaFxInCore' :plugin-api:test :javafx-app:test --tests 'com.shootoff.plugins.*' --tests 'com.shootoff.gui.pane.*' --console=plain`
Expected: `BUILD SUCCESSFUL`, with these passing:
- `TestPluginDescriptor` (5), `TestPluginEngineRegistration` (3), `TestNoJavaFxInCore` (3)
- `TestNoJavaFxInPluginApi` (2), now also scanning the v2 loader
- `TestPluginLoading` (4), `TestExerciseSlide` (1), `TestBuiltInExercises` (1)
- `TestPlugin` (3), `TestPluginEngine` (1), `TestPluginDescriptorIsolation` (2)
- the ten built-in exercise tests

- [ ] **Step 9: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 14 (**345**).

- [ ] **Step 10: Check the installed v1 drill still loads through the engine**

Load a copy of the owner's real jar with a throwaway test, which is deleted afterwards:

```bash
cd /home/bfears/projects/ShootOFF
rm -rf build/engine-check && mkdir -p build/engine-check && cp exercises/RandomTargetParDrill.jar build/engine-check/
mkdir -p javafx-app/src/test/java/scratch
cat > javafx-app/src/test/java/scratch/InstalledDrillCheck.java <<'JAVA'
package scratch;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.shootoff.plugins.engine.ExerciseLoaders;
import com.shootoff.plugins.engine.Plugin;

class InstalledDrillCheck {
	@Test
	void installedDrillLoads() throws Exception {
		final Plugin plugin = new Plugin(Paths.get("build/engine-check/RandomTargetParDrill.jar"), ExerciseLoaders.all());
		System.out.println("CHECK " + plugin.getApiVersion() + " " + plugin.getType() + " " + plugin.getEntry().metadata());
	}
}
JAVA
./gradlew :javafx-app:test --tests 'scratch.InstalledDrillCheck' --console=plain -q
command grep -ho "CHECK [^<]*" javafx-app/build/test-results/test/TEST-scratch.InstalledDrillCheck.xml
rm -rf javafx-app/src/test/java/scratch build/engine-check javafx-app/build/test-results/test/TEST-scratch.InstalledDrillCheck.xml
git status --short
```

Expected:
- `CHECK 1 PROJECTOR_ONLY Random Target PAR Drill with Score 1.1 Benjamin Fears`
- `git status --short` lists no `scratch` path

- [ ] **Step 11: Commit**

```bash
git add core/src/main/java/com/shootoff/plugins/engine core/src/testFixtures/java/com/shootoff/plugins/engine/PluginJars.java \
  core/src/test/java/com/shootoff/plugins/engine plugin-api/src/main/java/com/shootoff/plugins/engine \
  javafx-app/src/main/java/com/shootoff/plugins/engine javafx-app/src/main/java/com/shootoff/plugins/BuiltInExercises.java \
  javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java \
  javafx-app/src/main/java/com/shootoff/gui/controller/PluginManagerController.java \
  javafx-app/src/test/java/com/shootoff/plugins/engine javafx-app/src/test/java/com/shootoff/gui/pane/TestExerciseSlide.java \
  javafx-app/src/test/java/com/shootoff/plugins/TestBuiltInExercises.java
git status --short | command grep -v '^R  '
git commit -m "Move the plugin engine into core with a loader per plugin API version"
git log -1 --format=%B
```

The filtered status must show only ` M shootoff.properties` (plus the owner's `??` files, if any).

---
### Task 5: Hosting groundwork in the JavaFX app

Runs in ShootOFF on `compose-ui`.

**Files:**
- Create: `javafx-app/src/main/java/com/shootoff/gui/TimingControlsPane.java`
- Modify: `javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java` (the private `DelayPane` class, `getDelayedStartInterval`, `getParInterval`, imports)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java` (`placementListener`, `toHit`, one import)
- Modify: `javafx-app/src/main/java/com/shootoff/targets/Hit.java` (a field, a constructor, a getter)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/ShotEntry.java` (a copy constructor, `withRowColor`)
- Test: `javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetViewThreading.java` (new), `javafx-app/src/test/java/com/shootoff/gui/TestShotEntry.java` (new), `javafx-app/src/test/java/com/shootoff/gui/TestCanvasManagerHits.java` (one test added)

**Interfaces:**
- Consumes: `DelayedStartListener`, `ParListener` (javafx-app); `com.shootoff.targets.model.Hit` (core).
- Produces:
  - `public class com.shootoff.gui.TimingControlsPane extends GridPane`:
    - `TimingControlsPane(DelayedStartListener)`: the min/max rows, showing 4 and 8
    - `void addParTime(ParListener)`: the par row, showing 2.0; a second call does nothing
    - `boolean hasParTime()`, `void setDelayRange(int, int)`, `void setParTime(double)`
    - `String getMinText()`, `String getMaxText()`, `Optional<String> getParTimeText()`

    Setting a field's text notifies the listener, exactly as typing does.
  - `com.shootoff.targets.Hit`: `Hit(Target, TargetRegion, int, int, com.shootoff.targets.model.Hit modelHit)` and `Optional<com.shootoff.targets.model.Hit> getModelHit()`. The 4-argument constructor gives an empty model hit. `TargetView.toHit` fills it in, so every hit `CanvasManager` reports carries the model hit.
  - `ShotEntry withRowColor(Optional<Color>)`: a copy with the same shot, timestamp, color, split and exercise values
  - `TargetView`: a placement change made off the JavaFX thread, to a target whose group is in a scene, reaches the group on the JavaFX thread (ruling 10). The model changes at once on the calling thread.

- [ ] **Step 1: Write the failing tests**

`javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetViewThreading.java`:

```java
package com.shootoff.gui.targets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Point;
import com.shootoff.targets.io.TargetIO;

import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;

class TestTargetViewThreading {
	private static <T> T onFx(Callable<T> action) throws Exception {
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

	@Test
	void offThreadMovesOfAShownTargetAreAppliedOnTheFxThread() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final List<Boolean> changedOnFxThread = new CopyOnWriteArrayList<>();

		final TargetView target = onFx(() -> {
			final TargetView view = new TargetView(TargetIO.loadTarget(new File("targets/IPSC.target"), false).get(),
					new ArrayList<>());
			new Scene(new Group(view.getTargetGroup()));
			view.getTargetGroup().layoutXProperty()
					.addListener((observable, oldX, newX) -> changedOnFxThread.add(Platform.isFxApplicationThread()));
			return view;
		});

		// From the test thread, as an exercise moves its targets from its own thread
		target.setPosition(40, 50);

		assertEquals(new Point(40, 50), target.getPlacedTarget().getPosition());
		assertEquals(40, onFx(() -> target.getTargetGroup().getLayoutX()), 0);
		assertEquals(List.of(true), changedOnFxThread);
	}
}
```

`javafx-app/src/test/java/com/shootoff/gui/TestShotEntry.java`:

```java
package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;

import javafx.scene.paint.Color;

class TestShotEntry {
	@Test
	void withRowColorKeepsTheRowsValues() {
		final ShotEntry entry = new ShotEntry(new DisplayShot(new Shot(ShotColor.RED, 1, 2, 1500), 2),
				Optional.of(new Shot(ShotColor.RED, 0, 0, 1000)), Optional.empty(), false, false);
		entry.setExerciseValue("Score", "10");

		final ShotEntry colored = entry.withRowColor(Optional.of(Color.CORAL));

		assertEquals(Optional.of(Color.CORAL), colored.getRowColor());
		assertEquals(Optional.of(Color.CORAL), colored.getSplit().getRowColor());
		assertEquals(entry.getSplit().getSplit(), colored.getSplit().getSplit());
		assertEquals("10", colored.getExerciseValue("Score"));
		assertEquals(entry.getTimestamp(), colored.getTimestamp());
		assertEquals(entry.getColor(), colored.getColor());
		assertSame(entry.getShot(), colored.getShot());
		assertEquals(Optional.empty(), entry.getRowColor());
	}
}
```

Add to `TestCanvasManagerHits`:

```java
	@Test
	public void v1HitsCarryTheModelHit() {
		final TargetView ipsc = add("targets/IPSC.target");
		ipsc.setPosition(40, 30);

		final Hit hit = shoot(190.37, 180.61).get();

		assertEquals(HitTester.hit(canvas.getTargetSet(), 190.37, 180.61), hit.getModelHit());
		// A v1 hit made without the model (for example by a test) has none
		assertEquals(Optional.empty(), new Hit(ipsc, ipsc.getRegions().get(0), 0, 0).getModelHit());
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.targets.TestTargetViewThreading' --tests 'com.shootoff.gui.TestShotEntry' --tests 'com.shootoff.gui.TestCanvasManagerHits' --console=plain`
Expected: FAIL at compile time: `cannot find symbol` for `withRowColor` and `getModelHit`. The threading test fails once those compile: before Step 5 it records `false`, because the move ran on the test thread.

- [ ] **Step 3: Extract the timing controls**

`javafx-app/src/main/java/com/shootoff/gui/TimingControlsPane.java` (GPL header, then):

```java
package com.shootoff.gui;

import java.util.Optional;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;

/**
 * The controls for an exercise's random start delay and, optionally, its par time. v1 exercises get
 * them through TrainingExerciseBase.getDelayedStartInterval/getParInterval, v2 exercises through
 * ExerciseHost.onDelayedStartChanged/onParTimeChanged. Setting a field's text notifies the listener,
 * exactly as typing does.
 */
public class TimingControlsPane extends GridPane {
	private final TextField minTextField = new TextField("4");
	private final TextField maxTextField = new TextField("8");
	private Optional<TextField> parTextField = Optional.empty();

	public TimingControlsPane(DelayedStartListener listener) {
		getColumnConstraints().add(new ColumnConstraints(100));
		setVgap(5);

		final Label instructionsLabel = new Label(
				"Set interval within which a beep will sound to signal the start of a round.\n");
		instructionsLabel.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);

		this.add(instructionsLabel, 0, 0, 2, 3);
		addRow(3, new Label("Min (s)"));
		addRow(4, new Label("Max (s)"));

		this.add(minTextField, 1, 3);
		this.add(maxTextField, 1, 4);

		minTextField.textProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue.matches("\\d*")) {
				minTextField.setText(oldValue);
				minTextField.positionCaret(minTextField.getLength());
			} else {
				listener.updatedDelayedStartInterval(Integer.parseInt(minTextField.getText()),
						Integer.parseInt(maxTextField.getText()));
			}
		});

		maxTextField.textProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue.matches("\\d*")) {
				maxTextField.setText(oldValue);
				maxTextField.positionCaret(maxTextField.getLength());
			} else {
				listener.updatedDelayedStartInterval(Integer.parseInt(minTextField.getText()),
						Integer.parseInt(maxTextField.getText()));
			}
		});
	}

	/**
	 * Adds the par time row. A second call does nothing.
	 */
	public void addParTime(ParListener listener) {
		if (parTextField.isPresent()) return;

		final TextField field = new TextField("2.0");
		addRow(5, new Label("PAR Time (s)"));
		this.add(field, 1, 5);

		field.textProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue.matches("^\\d*\\.?\\d*$")) {
				field.setText(oldValue);
				field.positionCaret(field.getLength());
			} else {
				listener.updatedParInterval(Double.parseDouble(field.getText()));
			}
		});

		parTextField = Optional.of(field);
	}

	public boolean hasParTime() {
		return parTextField.isPresent();
	}

	public void setDelayRange(int minSeconds, int maxSeconds) {
		minTextField.setText(Integer.toString(minSeconds));
		maxTextField.setText(Integer.toString(maxSeconds));
	}

	public void setParTime(double seconds) {
		parTextField.ifPresent(field -> field.setText(Double.toString(seconds)));
	}

	public String getMinText() {
		return minTextField.getText();
	}

	public String getMaxText() {
		return maxTextField.getText();
	}

	public Optional<String> getParTimeText() {
		return parTextField.map(TextField::getText);
	}
}
```

The listeners are `DelayPane`'s and `getParInterval`'s code, moved unchanged.

In `TrainingExerciseBase.java`:
- Delete the private static class `DelayPane`.
- Replace `getDelayedStartInterval` and `getParInterval` (keep their Javadoc) with the bodies below.
- Add `import com.shootoff.gui.TimingControlsPane;`. Delete `import javafx.beans.value.ChangeListener;` and `import javafx.scene.control.TextField;`, now unused. `ObservableValue` is still used by `addShotTimerColumn`.

```java
	public void getDelayedStartInterval(final DelayedStartListener listener) {
		if (listener == null) throw new IllegalArgumentException("Delayed start listener must be non-null");

		if (haveDelayControls) return;

		final TimingControlsPane delayPane = new TimingControlsPane(listener);

		trainingExerciseContainer.getChildren().add(delayPane);
		exercisePanes.add(delayPane);
		haveDelayControls = true;
	}
```

```java
	public void getParInterval(final ParListener listener) {
		if (listener == null) throw new IllegalArgumentException("Par listener must be non-null");

		if (haveParControls) return;

		final TimingControlsPane parPane = new TimingControlsPane(listener);
		parPane.addParTime(listener);

		trainingExerciseContainer.getChildren().add(parPane);
		exercisePanes.add(parPane);
		haveParControls = true;
	}
```

```bash
command grep -n "DelayPane\|ChangeListener\|TextField" javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java
```

Expected: nothing.

- [ ] **Step 4: Carry the model hit in v1 hits, and recolor shot-timer rows**

In `javafx-app/src/main/java/com/shootoff/targets/Hit.java`:
- Add `import java.util.Optional;`.
- Add the field `private final Optional<com.shootoff.targets.model.Hit> modelHit;` after `impactX, impactY`.
- Change the existing constructor's body to one line, keeping its Javadoc: `this(target, hitRegion, impactX, impactY, null);`
- Add after it:

```java
	/**
	 * Create a new Hit from a hit of the core target model, which v2 exercises receive.
	 *
	 * @param modelHit
	 *            the model's hit, or <tt>null</tt> if there is none
	 */
	public Hit(final Target target, final TargetRegion hitRegion, final int impactX, final int impactY,
			final com.shootoff.targets.model.Hit modelHit) {
		this.target = target;
		this.hitRegion = hitRegion;
		this.impactX = impactX;
		this.impactY = impactY;
		this.modelHit = Optional.ofNullable(modelHit);
	}

	/**
	 * @return the core target model's hit this hit was made from; empty for hits made without the model
	 */
	public Optional<com.shootoff.targets.model.Hit> getModelHit() {
		return modelHit;
	}
```

In `TargetView.toHit`, pass the model hit:

```java
		return new Hit(this, (TargetRegion) regionNodes.get(hit.region().index()),
				(int) (x - regionBounds.getMinX()), (int) (y - regionBounds.getMinY()), hit);
```

In `ShotEntry.java`, add after the public constructor:

```java
	private ShotEntry(ShotEntry original, Optional<Color> rowColor) {
		shot = original.shot;
		timestamp = original.timestamp;
		color = original.color;
		this.rowColor = rowColor;
		split = new SplitData(original.split.getSplit(), rowColor, original.split.hadMalfunction(),
				original.split.hadReload());
		exerciseData.putAll(original.exerciseData);
	}

	/**
	 * @return a copy of this row with another highlight color, keeping the exercise's column values
	 */
	public ShotEntry withRowColor(Optional<Color> rowColor) {
		return new ShotEntry(this, rowColor);
	}
```

- [ ] **Step 5: Apply off-thread placement changes on the JavaFX thread**

In `TargetView.java`, add `import javafx.application.Platform;` and replace the `placementListener` field with:

```java
	private final TargetSetListener placementListener = new TargetSetListener() {
		@Override
		public void targetChanged(PlacedTarget target) {
			if (!target.getId().equals(membership.placed().getId())) return;

			// A shown node may only change on the JavaFX thread, but exercises move their targets from
			// their own threads. The model has already changed; the drawing catches up with its latest
			// placement. Nodes not yet in a scene (session replay, tests) change at once.
			if (Platform.isFxApplicationThread() || targetGroup.getScene() == null) {
				applyPlacement(target);
			} else {
				Platform.runLater(() -> applyPlacement(membership.placed()));
			}
		}
	};
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.*' --tests 'com.shootoff.plugins.*' --tests 'com.shootoff.targets.*' --console=plain`
Expected: `BUILD SUCCESSFUL`, with these passing:
- `TestTargetViewThreading` (1), `TestShotEntry` (1), `TestCanvasManagerHits` (one more than before)
- `TestTargetViewPlacement`, `TestSessionCanvasManagerReplay`, `TestTargetCommands`, `TestHitParity` (unchanged)
- the ten built-in exercise tests, whose timing panes now come from `TimingControlsPane`

- [ ] **Step 7: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 3 (**348**).

- [ ] **Step 8: Commit**

```bash
git add javafx-app/src/main/java/com/shootoff/gui/TimingControlsPane.java javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java \
  javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java javafx-app/src/main/java/com/shootoff/targets/Hit.java \
  javafx-app/src/main/java/com/shootoff/gui/ShotEntry.java javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetViewThreading.java \
  javafx-app/src/test/java/com/shootoff/gui/TestShotEntry.java javafx-app/src/test/java/com/shootoff/gui/TestCanvasManagerHits.java
git status --short
git commit -m "Prepare the JavaFX app to host v2 exercises: shared timing controls, off-thread target moves, model hits"
git log -1 --format=%B
```

`git status --short` must show only ` M shootoff.properties` (plus the owner's `??` files, if any) besides the staged files.

---
### Task 6: `JavaFxExerciseHost`, and v2 exercises in the Training menu

Runs in ShootOFF on `compose-ui`.

**Files:**
- Create in `javafx-app/src/main/java/com/shootoff/gui/exercise/`: `SoundOutput.java`, `ExerciseHostContext.java`, `JavaFxExerciseHost.java`, `HostedExercise.java`
- Modify: `javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java` (`menuItem`, `onItemClicked`)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java` (`setExercise`, `setProjectorExercise`, a new `startHostedExercise`)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java`, `gui/pane/ProjectorSlide.java` (one projector-exercise check each)
- Modify: `.gitignore` (add `/exercise-data/`)
- Test: `javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHost.java`, `TestHostedExercise.java` (new); `javafx-app/src/test/java/com/shootoff/gui/pane/TestExerciseSlide.java` (one test added)

**Interfaces:**
- Consumes:
  - Tasks 2 and 4: `Exercise`, `ExerciseHost` and the handles and values, `ExerciseExecutor`, `ExercisePaths`, `V2ExerciseEntry`, `LegacyExerciseEntry`
  - Task 5: `TimingControlsPane`, `ShotEntry.withRowColor`, `Hit.getModelHit`
  - `CanvasManager`, `ProjectorArenaPane`, `CamerasSupervisor`, `TrainingExerciseView`, `TargetIO.loadTarget(InputStream, boolean, ClassLoader)`, `SoundPlayer`, `TextToSpeech`
- Produces:
  - `interface SoundOutput`: `void play(String name, InputStream sound, Runnable whenDone)`, `void say(String text)`, `static SoundOutput speakers()`
  - `record ExerciseHostContext(Configuration config, CamerasSupervisor cameras, TrainingExerciseView view, CanvasManager canvas, Optional<ProjectorArenaPane> arena, List<CanvasManager> feeds, ClassLoader resources, SoundOutput sounds)`. `canvas` is the surface. `feeds` get `showMessage` banners. `resources` is the exercise's class loader.
  - `final class JavaFxExerciseHost implements ExerciseHost`:
    - `JavaFxExerciseHost(Exercise, ExerciseHostContext)`
    - lifecycle, each safe from any thread: `void start()`, `void deliverShot(Shot, Optional<com.shootoff.targets.model.Hit>)`, `void targetsChanged()`, `void reset()`, `void stop()`
  - `final class HostedExercise implements TrainingExercise`:
    - `HostedExercise(V2ExerciseEntry)`: a menu item with no host, whose callbacks do nothing
    - `HostedExercise(V2ExerciseEntry, JavaFxExerciseHost)`: a running exercise
    - `V2ExerciseEntry getEntry()`, `boolean isProjector()`, `static boolean isProjectorExercise(TrainingExercise)`
  - `ShootOFFController`: picking a `HostedExercise` from the menu, or re-selecting one after calibration, starts a fresh instance of its class on a new host.

- [ ] **Step 1: Write the failing tests**

`javafx-app/src/test/java/com/shootoff/gui/exercise/TestJavaFxExerciseHost.java`:

```java
package com.shootoff.gui.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.CameraView;
import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ArenaShot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.RowStyle;
import com.shootoff.exercise.ShotStyle;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.exercise.TextHandle;
import com.shootoff.exercise.TextStyle;
import com.shootoff.geom.Point;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExerciseView;
import com.shootoff.targets.Target;
import com.shootoff.targets.model.Hit;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.text.Font;

class TestJavaFxExerciseHost {
	private static final TextStyle STYLE = new TextStyle(40, "white", "transparent");

	@TempDir Path temp;
	private Configuration config;
	private CamerasSupervisor cameras;
	private MockCanvasManager canvas;
	private Pane container;
	private VBox buttons;
	private TableView<ShotEntry> table;
	private URLClassLoader resources;
	private final RecordingSounds sounds = new RecordingSounds();
	private final RecordingExercise exercise = new RecordingExercise();
	private List<Node> canvasBefore;
	private JavaFxExerciseHost host;

	private static final class RecordingSounds implements SoundOutput {
		final List<String> played = new CopyOnWriteArrayList<>();
		final List<String> spoken = new CopyOnWriteArrayList<>();

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
		public void say(String text) {
			spoken.add(text);
		}
	}

	private static final class RecordingExercise implements Exercise {
		final List<String> events = new CopyOnWriteArrayList<>();
		final Map<String, String> threads = new ConcurrentHashMap<>();
		final List<Shot> shots = new CopyOnWriteArrayList<>();

		void record(String event) {
			threads.put(event, Thread.currentThread().getName() + (Platform.isFxApplicationThread() ? " (FX)" : ""));
			events.add(event);
		}

		void await(String event) throws InterruptedException {
			waitFor(() -> events.contains(event), "never saw " + event + " in " + events);
		}

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Recording exercise", "1.0", "ShootOFF tests", "Records its callbacks");
		}

		@Override
		public void start(ExerciseHost host) {
			record("start");
		}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {
			shots.add(shot);
			record("shot");
		}

		@Override
		public void onTargetsChanged(List<TargetHandle> targets) {
			record("targets " + targets.size());
		}

		@Override
		public void onReset() {
			record("reset");
		}

		@Override
		public void stop() {
			record("stop");
		}
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

	private static <T> T onFx(Callable<T> action) throws Exception {
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

	// Waits for the scene changes queued so far
	private static void fxSync() throws Exception {
		onFx(() -> null);
	}

	private static void waitFor(BooleanSupplier condition, String failure) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) fail(failure);
			Thread.sleep(5);
		}
	}

	private ExerciseHostContext context(MockCanvasManager surface, Optional<ProjectorArenaPane> arena) {
		return new ExerciseHostContext(config, cameras, new View(), surface, arena, List.of(canvas), resources,
				sounds);
	}

	@BeforeEach
	void setUp() throws Exception {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		config = new Configuration(new String[0]);
		cameras = new CamerasSupervisor(config);

		// "The exercise's jar": a sound, a target and a background
		final Path jar = Files.createDirectories(temp.resolve("jar"));
		Files.createDirectories(jar.resolve("sounds"));
		Files.write(jar.resolve("sounds/cue.wav"), new byte[] { 1, 2, 3 });
		Files.createDirectories(jar.resolve("targets"));
		Files.writeString(jar.resolve("targets/box.target"),
				"<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"20\" fill=\"red\" /></target>");
		Files.createDirectories(jar.resolve("backgrounds"));
		ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", jar.resolve("backgrounds/black.png").toFile());
		resources = new URLClassLoader(new URL[] { jar.toUri().toURL() }, null);

		onFx(() -> {
			canvas = new MockCanvasManager(config);
			container = new VBox();
			buttons = new VBox(new Button("Reset"));
			table = new TableView<>(FXCollections.observableArrayList());
			canvasBefore = List.copyOf(canvas.getCanvasGroup().getChildren());
			return null;
		});

		host = new JavaFxExerciseHost(exercise, context(canvas, Optional.empty()));
	}

	@AfterEach
	void tearDown() throws IOException {
		host.stop();
		resources.close();
	}

	private Label labelWithText(String text) {
		for (final Node node : canvas.getCanvasGroup().getChildren()) {
			if (node instanceof Label label && text.equals(label.getText())) return label;
		}
		throw new AssertionError("No label " + text + " on the canvas");
	}

	private Button buttonLabeled(String text) {
		for (final Node node : buttons.getChildren()) {
			if (node instanceof Button button && text.equals(button.getText())) return button;
		}
		throw new AssertionError("No button " + text);
	}

	@SuppressWarnings("unchecked")
	private Spinner<Double> spinner() {
		for (final Node pane : container.getChildren()) {
			if (!(pane instanceof HBox box)) continue;
			for (final Node node : box.getChildren()) {
				if (node instanceof Spinner<?> spinner) return (Spinner<Double>) spinner;
			}
		}
		throw new AssertionError("No spinner in the exercise pane");
	}

	private List<TimingControlsPane> timingPanes() {
		return container.getChildren().stream().filter(TimingControlsPane.class::isInstance)
				.map(TimingControlsPane.class::cast).toList();
	}

	@Test
	void targetsReachTheCanvasAndItsTargetSet() throws Exception {
		final TargetHandle ipsc = host.addTarget("targets/IPSC.target", 10, 20).get();

		assertEquals(1, canvas.getTargets().size());
		assertEquals(canvas.getTargetSet().getTargets().get(0).getId(), ipsc.id());
		assertEquals(new Point(10, 20), ipsc.position());
		assertEquals(List.of(ipsc), host.targets());

		ipsc.move(30, 40);
		ipsc.setVisible(false);
		assertEquals(new Point(30, 40), ipsc.position());
		fxSync();
		assertEquals(30, onFx(() -> ((TargetView) canvas.getTargets().get(0)).getTargetGroup().getLayoutX()), 0);
		assertFalse(canvas.getTargetSet().getTargets().get(0).isVisible());

		// From the exercise's jar
		final TargetHandle box = host.addTarget("@targets/box.target", 0, 0).get();
		assertEquals("@targets/box.target", canvas.getTargets().get(1).getTargetFile().getPath());
		assertEquals(10, box.size().getWidth(), 0);

		ipsc.remove();
		box.remove();
		fxSync();
		assertEquals(List.of(), canvas.getTargets());
		assertEquals(Optional.empty(), host.addTarget("targets/no_such.target", 0, 0));
	}

	@Test
	void textsAndMessagesReachTheCanvases() throws Exception {
		final TextHandle round = host.showText("Round: 1/10", 100, 10, STYLE);
		fxSync();
		final Label label = onFx(() -> labelWithText("Round: 1/10"));
		assertEquals(100, label.getLayoutX(), 0);
		assertEquals(Font.font(40).getSize(), label.getFont().getSize(), 0);
		assertEquals(Color.WHITE, label.getTextFill());

		round.setText("Round: 2/10");
		round.move(120, 12);
		fxSync();
		assertEquals("Round: 2/10", label.getText());
		assertEquals(120, label.getLayoutX(), 0);

		round.remove();
		host.showMessage("Score: 3");
		fxSync();
		assertFalse(canvas.getCanvasGroup().getChildren().contains(label));
		assertNotNull(onFx(() -> labelWithText("Score: 3")));
	}

	@Test
	void buttonsReachTheButtonsPane() throws Exception {
		host.start();
		final ButtonHandle pause = host.addButton("Pause", () -> exercise.record("pause"));
		fxSync();

		onFx(() -> {
			buttonLabeled("Pause").fire();
			return null;
		});
		exercise.await("pause");

		pause.setLabel("Resume");
		fxSync();
		assertNotNull(onFx(() -> buttonLabeled("Resume")));

		pause.remove();
		fxSync();
		assertEquals(1, buttons.getChildren().size());
	}

	@Test
	void numberSettingReachesTheExercisePane() throws Exception {
		host.start();
		host.addNumberSetting("Shots per round", 10, 1, 100, 1, value -> exercise.record("rounds " + value));
		fxSync();

		final Spinner<Double> spinner = onFx(this::spinner);
		assertEquals(10, spinner.getValue(), 0);

		onFx(() -> {
			spinner.getValueFactory().setValue(7.0);
			return null;
		});
		exercise.await("rounds 7.0");
	}

	@Test
	void columnsAndRowStylesReachTheShotTimer() throws Exception {
		host.addColumn("Score");
		fxSync();
		assertEquals(List.of("Score"), onFx(() -> table.getColumns().stream().map(TableColumn::getText).toList()));

		onFx(() -> table.getItems().add(new ShotEntry(new DisplayShot(new Shot(ShotColor.RED, 1, 2, 1000), 2),
				Optional.empty(), Optional.empty(), false, false)));
		host.setColumnValue("Score", "10");
		host.styleLastRow(new RowStyle("coral"));
		fxSync();

		final ShotEntry last = onFx(() -> table.getItems().get(table.getItems().size() - 1));
		assertEquals("10", last.getExerciseValue("Score"));
		assertEquals(Optional.of(Color.CORAL), last.getRowColor());
	}

	@Test
	void soundsReachTheSoundOutput() {
		host.playSound("sounds/beep.wav");
		host.playSound("/sounds/cue.wav");
		host.playSounds(List.of("sounds/beep.wav", "chime.wav"));
		host.playSound("sounds/no_such.wav");
		host.say("Make ready");

		// ShootOFF's sounds folder, the exercise's jar, and a bare name in the sounds folder
		assertEquals(List.of("sounds/beep.wav", "/sounds/cue.wav", "sounds/beep.wav", "chime.wav"), sounds.played);
		assertEquals(List.of("Make ready"), sounds.spoken);
	}

	@Test
	void callbacksArriveOnOneExerciseThread() throws Exception {
		host.start();
		host.addButton("Go", () -> exercise.record("click"));
		host.addNumberSetting("Rounds", 1, 1, 10, 1, value -> exercise.record("setting"));
		host.onParTimeChanged(value -> exercise.record("par"));
		host.schedule(() -> exercise.record("scheduled"), Duration.ofMillis(10));
		host.deliverShot(new DisplayShot(ShotColor.RED, 1, 2, 3, 2), Optional.empty());
		host.targetsChanged();
		host.reset();
		fxSync();
		onFx(() -> {
			buttonLabeled("Go").fire();
			spinner().getValueFactory().setValue(2.0);
			timingPanes().get(0).setParTime(3.0);
			return null;
		});

		for (final String event : List.of("start", "shot", "targets 0", "reset", "click", "setting", "par", "scheduled")) {
			exercise.await(event);
		}
		host.stop();

		assertTrue(exercise.events.contains("stop"));
		assertEquals(Set.of("Exercise: Recording exercise"), Set.copyOf(exercise.threads.values()));
	}

	@Test
	void stopRemovesEverythingAndCancelsScheduledTasks() throws Exception {
		host.start();
		host.addTarget("targets/IPSC.target", 5, 5);
		host.showText("Score: 0", 10, 10, STYLE);
		host.showMessage("Make ready");
		host.addButton("Pause", () -> {});
		host.addNumberSetting("Rounds", 10, 1, 100, 1, value -> {});
		host.addColumn("Score");
		host.showShotMarker(20, 20, new ShotStyle(ShotColor.RED));
		host.onParTimeChanged(value -> {});
		final AtomicBoolean ran = new AtomicBoolean();
		host.schedule(() -> ran.set(true), Duration.ofMillis(300));
		fxSync();
		assertEquals(2, container.getChildren().size());
		assertTrue(onFx(() -> canvas.getCanvasGroup().getChildren().stream().anyMatch(Ellipse.class::isInstance)));

		host.stop();
		fxSync();

		assertEquals(List.of(), canvas.getTargets());
		assertEquals(canvasBefore, onFx(() -> List.copyOf(canvas.getCanvasGroup().getChildren())));
		assertEquals(1, buttons.getChildren().size());
		assertEquals(List.of(), container.getChildren());
		assertEquals(List.of(), table.getColumns());
		Thread.sleep(500);
		assertFalse(ran.get());
		assertEquals(List.of("start", "stop"), exercise.events);

		// Calls after stop change nothing
		host.showText("late", 0, 0, STYLE);
		host.addButton("late", () -> {});
		assertEquals(Optional.empty(), host.addTarget("targets/IPSC.target", 0, 0));
		fxSync();
		assertEquals(canvasBefore, onFx(() -> List.copyOf(canvas.getCanvasGroup().getChildren())));
		assertEquals(1, buttons.getChildren().size());
	}

	@Test
	void timingControlsShowOnlyWhileAnExerciseListens() throws Exception {
		host.start();
		host.setParTime(4.0);
		host.setDelayedStart(new DelayRange(5, 8));
		fxSync();
		assertEquals(List.of(), timingPanes());

		final List<Double> heard = new CopyOnWriteArrayList<>();
		host.onParTimeChanged(heard::add);
		fxSync();
		final TimingControlsPane controls = onFx(() -> timingPanes().get(0));

		// The shared controls show the exercise's values, and the exercise hears only the user's
		assertEquals(Optional.of("4.0"), onFx(controls::getParTimeText));
		assertEquals("5-8", onFx(() -> controls.getMinText() + "-" + controls.getMaxText()));
		assertEquals(List.of(), heard);

		onFx(() -> {
			controls.setParTime(3.5);
			return null;
		});
		waitFor(() -> heard.contains(3.5), "the exercise never heard 3.5 s");
		assertEquals(3.5, host.parTime(), 0);

		host.stop();
		fxSync();
		assertEquals(List.of(), timingPanes());
	}

	@Test
	void projectorHostSetsAndRestoresTheBackgroundAndTakesOnlyArenaShots() throws Exception {
		final MockCanvasManager arenaCanvas = onFx(() -> new MockCanvasManager(config));
		final ProjectorArenaPane arena = onFx(() -> new ProjectorArenaPane(config, arenaCanvas));
		final JavaFxExerciseHost projectorHost = new JavaFxExerciseHost(exercise, context(arenaCanvas, Optional.of(arena)));
		assertTrue(projectorHost.isProjector());
		assertFalse(host.isProjector());

		projectorHost.start();
		projectorHost.setBackground("backgrounds/black.png");
		fxSync();
		assertEquals("/backgrounds/black.png", arena.getArenaBackground().get().getURL());

		final ArenaShot arenaShot = new ArenaShot(new DisplayShot(ShotColor.RED, 4, 5, 6, 2));
		projectorHost.deliverShot(new DisplayShot(ShotColor.RED, 1, 2, 3, 2), Optional.empty());
		projectorHost.deliverShot(arenaShot, Optional.empty());
		exercise.await("shot");

		projectorHost.stop();
		fxSync();
		assertEquals(List.of(arenaShot), exercise.shots);
		assertEquals(Optional.empty(), arena.getArenaBackground());
	}

	@Test
	void dataDirectoryIsPerExerciseInTheShootoffHome() throws IOException {
		final String previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", temp.toString());
		try {
			assertEquals(temp.resolve("exercise-data").resolve(RecordingExercise.class.getName()), host.dataDirectory());
			assertTrue(Files.isDirectory(host.dataDirectory()));
		} finally {
			System.setProperty("shootoff.home", previousHome);
		}
	}
}
```

`javafx-app/src/test/java/com/shootoff/gui/exercise/TestHostedExercise.java`:

```java
package com.shootoff.gui.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.ShootForScore;
import com.shootoff.plugins.SteelChallenge;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.targets.model.Hit;

class TestHostedExercise {
	public static final class Drill implements Exercise {
		private final boolean projectorOnly;

		public Drill() {
			this(true);
		}

		Drill(boolean projectorOnly) {
			this.projectorOnly = projectorOnly;
		}

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Drill", "2.0", "ShootOFF tests", "A v2 drill", projectorOnly);
		}

		@Override
		public void start(ExerciseHost host) {}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {}

		@Override
		public void onReset() {}

		@Override
		public void stop() {}
	}

	@Test
	void hostedExercisesAreProjectorExercisesWhenTheirMetadataSaysSo() {
		final HostedExercise projectorItem = new HostedExercise(new V2ExerciseEntry(Drill.class, new Drill(true).metadata()));
		final HostedExercise feedItem = new HostedExercise(new V2ExerciseEntry(Drill.class, new Drill(false).metadata()));

		assertTrue(HostedExercise.isProjectorExercise(projectorItem));
		assertFalse(HostedExercise.isProjectorExercise(feedItem));
		assertTrue(HostedExercise.isProjectorExercise(new SteelChallenge()));
		assertFalse(HostedExercise.isProjectorExercise(new ShootForScore()));

		// A menu item has no host: its callbacks do nothing
		projectorItem.init();
		projectorItem.shotListener(new Shot(null, 0, 0, 0), Optional.empty());
		projectorItem.reset(List.of());
		projectorItem.destroy();
		assertEquals("Drill", projectorItem.getInfo().getName());
	}
}
```

Add a v2 exercise of its own and a test to `TestExerciseSlide`:

```java
	public static final class V2Drill implements Exercise {
		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("V2 Drill", "2.0", "ShootOFF tests", "A v2 drill", true);
		}

		@Override
		public void start(ExerciseHost host) {}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {}

		@Override
		public void onReset() {}

		@Override
		public void stop() {}
	}

	@Test
	public void v2ExercisesAreListedWithV1Ones() {
		slide.registerExercise(new LegacyExerciseEntry(new ShootForScore(), false));
		slide.registerProjectorExercise(new V2ExerciseEntry(V2Drill.class, new V2Drill().metadata()));

		assertTrue(menuNames().containsAll(List.of("Shoot for Score", "V2 Drill")));
	}
```

The new imports for `TestExerciseSlide` are `java.util.Optional`, `com.shootoff.camera.Shot`, `com.shootoff.exercise.Exercise`, `com.shootoff.exercise.ExerciseHost`, `com.shootoff.plugins.ExerciseMetadata`, `com.shootoff.plugins.ShootForScore`, `com.shootoff.plugins.engine.V2ExerciseEntry` and `com.shootoff.targets.model.Hit`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.exercise.*' --tests 'com.shootoff.gui.pane.TestExerciseSlide' --console=plain`
Expected: FAIL at compile time: `package com.shootoff.gui.exercise` has no `JavaFxExerciseHost`, `ExerciseHostContext`, `SoundOutput` or `HostedExercise`.

- [ ] **Step 3: Write the sound output and the context**

Each file in `javafx-app/src/main/java/com/shootoff/gui/exercise/` starts with the GPL header.

`SoundOutput.java`:

```java
package com.shootoff.gui.exercise;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Optional;

import javax.sound.sampled.LineEvent;

import com.shootoff.plugins.TextToSpeech;
import com.shootoff.sound.SoundPlayer;

/**
 * Where a hosted exercise's sounds go: the speakers, or a recorder in tests.
 */
public interface SoundOutput {
	/**
	 * Plays <tt>sound</tt> and closes it, then runs <tt>whenDone</tt>.
	 *
	 * @param name
	 *            the name the exercise used, for logs and tests
	 */
	void play(String name, InputStream sound, Runnable whenDone);

	void say(String text);

	static SoundOutput speakers() {
		return new SoundOutput() {
			@Override
			public void play(String name, InputStream sound, Runnable whenDone) {
				if (SoundPlayer.isSilenced()) {
					System.out.println(name);
					whenDone.run();
					return;
				}

				SoundPlayer.play(new BufferedInputStream(sound), Optional.of(event -> {
					if (LineEvent.Type.STOP.equals(event.getType())) {
						event.getLine().close();
						whenDone.run();
					}
				}));
			}

			@Override
			public void say(String text) {
				// Synthesis takes a while; keep it off the exercise thread
				new Thread(() -> TextToSpeech.say(text), "Exercise speech").start();
			}
		};
	}
}
```

`ExerciseHostContext.java`:

```java
package com.shootoff.gui.exercise;

import java.util.List;
import java.util.Optional;

import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.config.Configuration;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.plugins.TrainingExerciseView;

/**
 * What a {@link JavaFxExerciseHost} works with.
 *
 * @param canvas
 *            the surface: the arena's canvas, or a camera feed's
 * @param arena
 *            the projector arena, for a projector exercise
 * @param feeds
 *            the canvases that show {@link JavaFxExerciseHost#showMessage} banners
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

- [ ] **Step 4: Write `JavaFxExerciseHost`**

`JavaFxExerciseHost.java`:

```java
package com.shootoff.gui.exercise;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.ParListener;
import com.shootoff.gui.ShotEntry;
import com.shootoff.gui.TimingControlsPane;
import com.shootoff.gui.pane.ProjectorArenaPane;
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
 * Runs one v2 {@link Exercise} in the JavaFX app, on the projector arena or on a camera feed.
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

	private static final Duration STOP_TIMEOUT = Duration.ofSeconds(2);
	// What the shared timing controls show until an exercise sets its own values, as for v1 exercises
	private static final double DEFAULT_PAR_TIME = 2.0;
	private static final DelayRange DEFAULT_DELAYED_START = new DelayRange(4, 8);
	private static final int COLUMN_WIDTH = 60;

	private final Exercise exercise;
	private final ExerciseHostContext context;
	private final ExerciseExecutor executor;

	// Guarded by this
	private final List<TargetView> addedTargets = new ArrayList<>();
	private boolean stopped = false;
	private boolean detectionPaused = false;

	private volatile double parTime = DEFAULT_PAR_TIME;
	private volatile DelayRange delayedStart = DEFAULT_DELAYED_START;
	private final List<DoubleConsumer> parListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<DelayRange>> delayListeners = new CopyOnWriteArrayList<>();

	// JavaFX thread only
	private final List<Node> canvasNodes = new ArrayList<>();
	private final List<Node> markers = new ArrayList<>();
	private final Map<CanvasManager, Label> feedLabels = new LinkedHashMap<>();
	private final List<Node> panes = new ArrayList<>();
	private final List<Button> buttons = new ArrayList<>();
	private final List<TableColumn<ShotEntry, String>> columns = new ArrayList<>();
	private TimingControlsPane timingControls = null;
	private boolean changedBackground = false;
	private Optional<LocatedImage> previousBackground = Optional.empty();
	private boolean tornDown = false;

	// The shared timing controls' listener; it runs on the JavaFX thread
	private final ParListener timingListener = new ParListener() {
		@Override
		public void updatedDelayedStartInterval(int min, int max) {
			if (min > max) return;

			final DelayRange range = new DelayRange(min, max);
			if (range.equals(delayedStart)) return;

			delayedStart = range;
			for (final Consumer<DelayRange> listener : delayListeners) {
				executor.execute(() -> listener.accept(range));
			}
		}

		@Override
		public void updatedParInterval(double seconds) {
			if (Double.compare(seconds, parTime) == 0) return;

			parTime = seconds;
			for (final DoubleConsumer listener : parListeners) {
				executor.execute(() -> listener.accept(seconds));
			}
		}
	};

	public JavaFxExerciseHost(Exercise exercise, ExerciseHostContext context) {
		this.exercise = exercise;
		this.context = context;
		executor = new ExerciseExecutor(exercise.metadata().getName());
	}

	// ---- Lifecycle, driven by HostedExercise

	public void start() {
		executor.execute(() -> exercise.start(this));
	}

	/**
	 * Hands a shot to the exercise: arena shots to a projector exercise, camera shots to a camera
	 * exercise.
	 */
	public void deliverShot(Shot shot, Optional<Hit> hit) {
		if (isProjector() != (shot instanceof ArenaShot)) return;

		executor.execute(() -> exercise.onShot(shot, hit));
	}

	public void targetsChanged() {
		executor.execute(() -> exercise.onTargetsChanged(targets()));
	}

	public void reset() {
		executor.execute(exercise::onReset);
	}

	/**
	 * Stops the exercise and removes everything it added. Waits up to two seconds for the exercise
	 * thread, which never waits for the JavaFX thread, so this may run on the JavaFX thread.
	 */
	public void stop() {
		synchronized (this) {
			if (stopped) return;
			stopped = true;
		}

		executor.shutdown(exercise::stop, STOP_TIMEOUT);

		final List<TargetView> targets;
		final boolean restartDetection;
		synchronized (this) {
			targets = List.copyOf(addedTargets);
			addedTargets.clear();
			restartDetection = detectionPaused;
		}

		for (final TargetView target : targets) {
			context.canvas().removeTarget(target);
		}

		if (restartDetection) context.cameras().setDetectingAll(true);

		// Queued after every scene change the exercise made, so all of them are undone
		Platform.runLater(this::tearDown);
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

		if (changedBackground) context.arena().get().setArenaBackground(previousBackground.orElse(null));
	}

	// Queues a scene change. Changes queued after the tear down are dropped.
	private void fx(Runnable change) {
		Platform.runLater(() -> {
			if (!tornDown) change.run();
		});
	}

	private synchronized boolean isStopped() {
		return stopped;
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
			logger.warn("{} set a background, but only the projector arena has one", exercise.metadata().getName());
			return;
		}

		final String name = ExercisePaths.resourceName(imageResource);
		final LocatedImage background;
		try (InputStream image = openImage(name)) {
			if (image == null) {
				logger.error("Can't find background {}", imageResource);
				return;
			}
			background = new LocatedImage(image, "/" + name);
		} catch (final IOException e) {
			logger.error("Can't read background {}", imageResource, e);
			return;
		}

		fx(() -> {
			final ProjectorArenaPane arena = context.arena().get();
			if (!changedBackground) {
				previousBackground = arena.getArenaBackground();
				changedBackground = true;
			}
			arena.setArenaBackground(background);
		});
	}

	// The exercise's jar first, then ShootOFF's own resources (for example arena/backgrounds/...)
	private InputStream openImage(String name) throws IOException {
		final Optional<URL> resource = findResource(name);
		if (resource.isPresent()) return open(resource.get());

		return JavaFxExerciseHost.class.getResourceAsStream("/" + name);
	}

	// ---- Targets

	@Override
	public Optional<TargetHandle> addTarget(String targetFile, double x, double y) {
		if (isStopped()) return Optional.empty();

		final Optional<Target> added = loadTarget(targetFile);
		if (added.isEmpty()) return Optional.empty();

		final TargetView target = (TargetView) added.get();
		target.setPosition(x, y);

		final Optional<ProjectorArenaPane> arena = context.arena();
		if (arena.isPresent() && arena.get().getPerspectiveManager().isPresent()
				&& arena.get().getPerspectiveManager().get().isInitialized()) {
			arena.get().resizeTargetToDefaultPerspective(target);
		}

		synchronized (this) {
			if (!stopped) {
				addedTargets.add(target);
				return Optional.of(new FxTargetHandle(target));
			}
		}

		// Stopped while loading
		context.canvas().removeTarget(target);
		return Optional.empty();
	}

	private Optional<Target> loadTarget(String targetFile) {
		final String name = ExercisePaths.resourceName(targetFile);
		final Optional<URL> resource = findResource(name);

		if (resource.isPresent()) {
			try (InputStream in = open(resource.get())) {
				return TargetIO.loadTarget(in, false, context.resources()).map(
						components -> context.canvas().addTarget(components.withTargetFile(new File("@" + name)), true));
			} catch (final IOException e) {
				logger.error("Can't read target {} from the exercise", name, e);
				return Optional.empty();
			}
		}

		final Optional<File> file = ExercisePaths.shootoffFile(targetFile, "targets");
		if (file.isEmpty()) {
			logger.error("Can't find target {}", targetFile);
			return Optional.empty();
		}

		return context.canvas().addTarget(file.get(), false);
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
		}

		@Override
		public void remove() {
			synchronized (JavaFxExerciseHost.this) {
				addedTargets.remove(view);
			}
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
		if (isStopped()) return;

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
		button.setOnAction(event -> executor.execute(onClick));

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
				if (newValue != null) executor.execute(() -> onChange.accept(newValue));
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
				logger.warn("{} set {} on an empty shot timer", exercise.metadata().getName(), name);
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
		if (isStopped()) return;

		context.cameras().clearShots();
		fx(() -> {
			canvasChildren().removeAll(markers);
			canvasNodes.removeAll(markers);
			markers.clear();
		});
	}

	@Override
	public synchronized void pauseShotDetection(boolean paused) {
		if (stopped) return;

		detectionPaused = paused;
		context.cameras().setDetectingAll(!paused);
	}

	// ---- Sound

	@Override
	public void playSound(String resourceOrFile) {
		openSound(resourceOrFile).ifPresent(sound -> context.sounds().play(resourceOrFile, sound, () -> {}));
	}

	@Override
	public void playSounds(List<String> resourcesOrFiles) {
		playInOrder(List.copyOf(resourcesOrFiles), 0);
	}

	private void playInOrder(List<String> sounds, int index) {
		if (index >= sounds.size() || isStopped()) return;

		final Optional<InputStream> sound = openSound(sounds.get(index));
		if (sound.isPresent()) {
			context.sounds().play(sounds.get(index), sound.get(), () -> playInOrder(sounds, index + 1));
		} else {
			playInOrder(sounds, index + 1);
		}
	}

	// The exercise's jar first, then ShootOFF's folder and its sounds/ folder
	private Optional<InputStream> openSound(String resourceOrFile) {
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

	@Override
	public void say(String text) {
		if (!isStopped()) context.sounds().say(text);
	}

	// ---- Time

	@Override
	public long currentTimeMillis() {
		return System.currentTimeMillis();
	}

	@Override
	public Cancellable schedule(Runnable task, Duration delay) {
		return executor.schedule(task, delay);
	}

	@Override
	public Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period) {
		return executor.scheduleRepeating(task, initialDelay, period);
	}

	// ---- Resources

	@Override
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

	// A plugin's own jar only: its class loader's getResource would search ShootOFF's classpath first
	private Optional<URL> findResource(String name) {
		final ClassLoader loader = context.resources();
		if (loader == null || name.isEmpty()) return Optional.empty();

		return Optional.ofNullable(loader instanceof URLClassLoader jar ? jar.findResource(name) : loader.getResource(name));
	}

	// Uncached, so the exercise's jar isn't held open and can be replaced while ShootOFF runs
	private static InputStream open(URL url) throws IOException {
		final URLConnection connection = url.openConnection();
		connection.setUseCaches(false);
		return new BufferedInputStream(connection.getInputStream());
	}

	@Override
	public Path dataDirectory() {
		final Path directory = Paths.get(System.getProperty("shootoff.home", System.getProperty("user.dir")),
				"exercise-data", exercise.getClass().getName());

		try {
			return Files.createDirectories(directory);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---- Shared settings

	@Override
	public double parTime() {
		return parTime;
	}

	@Override
	public void setParTime(double seconds) {
		parTime = seconds;
		fx(() -> {
			if (timingControls != null) timingControls.setParTime(seconds);
		});
	}

	@Override
	public void onParTimeChanged(DoubleConsumer listener) {
		parListeners.add(listener);
		fx(() -> showTimingControls(true));
	}

	@Override
	public DelayRange delayedStart() {
		return delayedStart;
	}

	@Override
	public void setDelayedStart(DelayRange range) {
		delayedStart = range;
		fx(() -> {
			if (timingControls != null) timingControls.setDelayRange(range.minSeconds(), range.maxSeconds());
		});
	}

	@Override
	public void onDelayedStartChanged(Consumer<DelayRange> listener) {
		delayListeners.add(listener);
		fx(() -> showTimingControls(false));
	}

	// The fields show the current values; setting them notifies timingListener, which ignores values it
	// already has
	private void showTimingControls(boolean withParTime) {
		if (timingControls == null) {
			timingControls = new TimingControlsPane(timingListener);
			timingControls.setDelayRange(delayedStart.minSeconds(), delayedStart.maxSeconds());
			context.view().getTrainingExerciseContainer().getChildren().add(timingControls);
			panes.add(timingControls);
		}

		if (withParTime && !timingControls.hasParTime()) {
			timingControls.addParTime(timingListener);
			timingControls.setParTime(parTime);
		}
	}
}
```

- [ ] **Step 5: Write `HostedExercise`**

`HostedExercise.java`:

```java
package com.shootoff.gui.exercise;

import java.util.List;
import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.targets.Hit;
import com.shootoff.targets.Target;

/**
 * A v2 exercise, seen by the JavaFX app as a {@link TrainingExercise}, so shots, target changes,
 * resets and exercise switching reach it through the same code as v1 exercises. Without a host it is
 * a Training menu item: picking it starts a fresh instance of its class on a new host.
 */
public final class HostedExercise implements TrainingExercise {
	private final V2ExerciseEntry entry;
	private final Optional<JavaFxExerciseHost> host;

	/**
	 * A Training menu item.
	 */
	public HostedExercise(V2ExerciseEntry entry) {
		this.entry = entry;
		host = Optional.empty();
	}

	/**
	 * A running exercise.
	 */
	public HostedExercise(V2ExerciseEntry entry, JavaFxExerciseHost host) {
		this.entry = entry;
		this.host = Optional.of(host);
	}

	/**
	 * @return <tt>true</tt> for exercises that only run on the projector arena, of either API version
	 */
	public static boolean isProjectorExercise(TrainingExercise exercise) {
		return exercise instanceof ProjectorTrainingExerciseBase
				|| (exercise instanceof HostedExercise hosted && hosted.isProjector());
	}

	public V2ExerciseEntry getEntry() {
		return entry;
	}

	public boolean isProjector() {
		return entry.isProjectorOnly();
	}

	@Override
	public void init() {
		host.ifPresent(JavaFxExerciseHost::start);
	}

	@Override
	public void targetUpdate(Target target, TargetChange change) {
		host.ifPresent(JavaFxExerciseHost::targetsChanged);
	}

	@Override
	public ExerciseMetadata getInfo() {
		return entry.metadata();
	}

	@Override
	public void shotListener(Shot shot, Optional<Hit> hit) {
		host.ifPresent(h -> h.deliverShot(shot, hit.flatMap(Hit::getModelHit)));
	}

	@Override
	public void reset(List<Target> targets) {
		host.ifPresent(JavaFxExerciseHost::reset);
	}

	@Override
	public void destroy() {
		host.ifPresent(JavaFxExerciseHost::stop);
	}
}
```

- [ ] **Step 6: List and start v2 exercises**

In `ExerciseSlide.java`:
- Add the imports `com.shootoff.gui.exercise.HostedExercise` and `com.shootoff.plugins.engine.V2ExerciseEntry`.
- Replace `menuItem` with:

```java
	private static Optional<TrainingExercise> menuItem(ExerciseEntry exercise) {
		if (exercise instanceof LegacyExerciseEntry legacy) return Optional.of(legacy.prototype());
		if (exercise instanceof V2ExerciseEntry v2) return Optional.of(new HostedExercise(v2));

		logger.warn("{} is an exercise of a kind the JavaFX app can't run", exercise.metadata());
		return Optional.empty();
	}
```

- In `onItemClicked`, replace `} else if (selectedExercise instanceof ProjectorTrainingExerciseBase) {` with `} else if (HostedExercise.isProjectorExercise(selectedExercise)) {`.
- If `ProjectorTrainingExerciseBase` is now unused in the file, delete its import:

```bash
command grep -c "ProjectorTrainingExerciseBase" javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java
```

A count of `1` means only the import is left: delete it.

In `ShootOFFController.java`:
- Add the imports `com.shootoff.exercise.Exercise`, `com.shootoff.gui.exercise.ExerciseHostContext`, `com.shootoff.gui.exercise.HostedExercise`, `com.shootoff.gui.exercise.JavaFxExerciseHost`, `com.shootoff.gui.exercise.SoundOutput`, `com.shootoff.gui.pane.ProjectorArenaPane` and `com.shootoff.plugins.engine.V2ExerciseEntry`. Skip any the file already has.
- In `setExercise`, after `if (exercise == null) return;`, insert:

```java
			if (exercise instanceof HostedExercise hosted) {
				startHostedExercise(hosted.getEntry());
				return;
			}
```

- In `setProjectorExercise`, after `config.setExercise(null);`, insert the same four lines.
- Add this method after `setProjectorExercise`:

```java
	// Starts a fresh instance of a v2 exercise on the arena, or on the first camera's feed
	private void startHostedExercise(V2ExerciseEntry entry) {
		final boolean projector = entry.isProjectorOnly();

		if (projector && projectorSlide.getArenaPane() == null) {
			logger.error("{} needs the projector arena", entry.metadata().getName());
			return;
		}

		if (!projector && camerasSupervisor.getCameraViews().isEmpty()) {
			logger.error("{} needs a camera feed", entry.metadata().getName());
			return;
		}

		final Exercise exercise;
		try {
			exercise = entry.newInstance();
		} catch (final ReflectiveOperationException e) {
			logger.error("Failed to start exercise " + entry.metadata().getName() + " " + entry.metadata().getVersion(),
					e);
			return;
		}

		final List<CanvasManager> feeds = new ArrayList<>();
		for (final CameraView view : camerasSupervisor.getCameraViews()) {
			feeds.add((CanvasManager) view);
		}
		getArenaView().ifPresent(view -> feeds.add((CanvasManager) view));

		final Optional<ProjectorArenaPane> arena = projector ? Optional.of(projectorSlide.getArenaPane())
				: Optional.empty();
		final CanvasManager canvas = arena.map(ProjectorArenaPane::getCanvasManager)
				.orElseGet(() -> (CanvasManager) camerasSupervisor.getCameraView(0));

		config.setPlugin(pluginEngine.getPlugin(entry.metadata()).orElse(null));

		final HostedExercise running = new HostedExercise(entry,
				new JavaFxExerciseHost(exercise, new ExerciseHostContext(config, camerasSupervisor, this, canvas, arena,
						feeds, entry.exerciseClass().getClassLoader(), SoundOutput.speakers())));
		config.setExercise(running);
		running.init();
	}
```

In `CalibrationManager.enableCalibration`, replace `config.getExercise().get() instanceof ProjectorTrainingExerciseBase` with `HostedExercise.isProjectorExercise(config.getExercise().get())`.

In `ProjectorSlide`'s arena close handler, replace `config.getExercise().get() instanceof ProjectorTrainingExerciseBase` with `HostedExercise.isProjectorExercise(config.getExercise().get())`.

In both files, add `import com.shootoff.gui.exercise.HostedExercise;`. Delete the `ProjectorTrainingExerciseBase` import if it is now unused:

```bash
for f in javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorSlide.java; do
	echo "$f $(command grep -c ProjectorTrainingExerciseBase $f)"
done
```

A count of `1` means only the import is left: delete it.

Add to `.gitignore`, under `# Ignore ShootOFF Folders`:

```
/exercise-data/
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.*' --tests 'com.shootoff.plugins.*' --console=plain`
Expected: `BUILD SUCCESSFUL`, with these passing:
- `TestJavaFxExerciseHost` (11), `TestHostedExercise` (1), `TestExerciseSlide` (2)
- the ten built-in exercise tests, and the calibration and canvas tests

- [ ] **Step 8: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 13 (**361**).

- [ ] **Step 9: Commit**

```bash
git add javafx-app/src/main/java/com/shootoff/gui/exercise javafx-app/src/test/java/com/shootoff/gui/exercise \
  javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java \
  javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorSlide.java \
  javafx-app/src/test/java/com/shootoff/gui/pane/TestExerciseSlide.java .gitignore
git status --short
git commit -m "Host v2 exercises in the JavaFX app and list them in the Training menu"
git log -1 --format=%B
```

`git status --short` must show only ` M shootoff.properties` (plus the owner's `??` files, if any) besides the staged files.

---
### Task 7: Publishing, and the plugin-author guide

Runs in ShootOFF on `compose-ui`.

**Files:**
- Modify: `javafx-app/build.gradle.kts` (`java-library`, `api` dependencies, the publication comment)
- Create: `docs/plugin-api.md`

**Interfaces:**
- Consumes: the publications of `core`, `plugin-api` and `javafx-app` (Plans 1–2; `plugin-api`'s test fixtures from Task 3).
- Produces, in `build/m2`, version `5.0.0-SNAPSHOT`:
  - `com.shootoff:core`
  - `com.shootoff:plugin-api`, with the test-fixtures variant (capability `com.shootoff:plugin-api-test-fixtures`, jar `plugin-api-5.0.0-SNAPSHOT-test-fixtures.jar`)
  - `com.shootoff:shootoff` (the JavaFX app), whose POM lists `core` and `plugin-api` at compile scope

  Tasks 8–9 build the drill against these.

- [ ] **Step 1: Check what the app's POM exposes today**

```bash
cd /home/bfears/projects/ShootOFF
./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain -q
cat > build/pom-scopes.py <<'EOF'
import xml.etree.ElementTree as ET
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
root = ET.parse('build/m2/com/shootoff/shootoff/5.0.0-SNAPSHOT/shootoff-5.0.0-SNAPSHOT.pom').getroot()
for d in root.findall('m:dependencies/m:dependency', ns):
    if d.find('m:groupId', ns).text == 'com.shootoff':
        print(d.find('m:artifactId', ns).text, d.find('m:scope', ns).text)
EOF
python3 build/pom-scopes.py
```

Expected (the Plan 1 defect this task fixes): `core runtime` and `plugin-api runtime`.

- [ ] **Step 2: Expose `core` and `plugin-api` to plugins built against the app**

In `javafx-app/build.gradle.kts`, change the `plugins` block and the two project dependencies:

```kotlin
plugins {
    application
    `java-library`
    `maven-publish`
    alias(libs.plugins.javafx)
}
```

```kotlin
    // api: plugins compiled against the app (com.shootoff:shootoff) see core's and plugin-api's types,
    // such as Settings and Shot, without declaring them
    api(project(":core"))
    api(project(":plugin-api"))
```

Replace the publication's comment with:

```kotlin
            // Old-style plugins (e.g. RandomTargetParDrill 1.x) compile against this coordinate, so it
            // stays com.shootoff:shootoff (Plan 3, ruling 15)
```

- [ ] **Step 3: Write the plugin-author guide**

`docs/plugin-api.md`:

````markdown
# Writing ShootOFF exercises

ShootOFF 5 runs two kinds of exercise plugins:

- **v2 exercises** (`apiVersion="2"`) use the UI-neutral API in `com.shootoff.exercise` (module `plugin-api`). They only talk to an `ExerciseHost`, so the same jar runs in the JavaFX app and, later, in the Compose app. New exercises should use it.
- **v1 exercises** extend `TrainingExerciseBase` or `ProjectorTrainingExerciseBase` and use JavaFX directly. They keep working in the JavaFX app. The section "Changes for v1 plugins" lists what changed under them.

Both kinds are jars in ShootOFF's `exercises/` folder. ShootOFF picks up a jar added while it runs. When two jars hold an exercise with the same name and creator, the newer version is the one listed.

## Building a v2 exercise

Publish ShootOFF's modules to a Maven repository first. In the ShootOFF project, run `./gradlew publishToMavenLocal`, adding `-Dmaven.repo.local=<folder>` for a repository other than `~/.m2`. Pass the same `-Dmaven.repo.local` to the plugin's builds.

`build.gradle.kts`:

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

val shootoffVersion = "5.0.0-SNAPSHOT"

dependencies {
    // ShootOFF provides these at runtime. Not transitive: core's own libraries (OpenCV, MaryTTS, ...)
    // aren't needed to compile or test an exercise, and some aren't on Maven Central.
    compileOnly("com.shootoff:plugin-api:$shootoffVersion") { isTransitive = false }
    compileOnly("com.shootoff:core:$shootoffVersion") { isTransitive = false }
    compileOnly("org.slf4j:slf4j-api:2.0.20")

    testImplementation("com.shootoff:plugin-api:$shootoffVersion") { isTransitive = false }
    testImplementation("com.shootoff:core:$shootoffVersion") { isTransitive = false }
    // FakeExerciseHost: plugin-api's test fixtures
    testImplementation("com.shootoff:plugin-api:$shootoffVersion") {
        isTransitive = false
        capabilities { requireCapability("com.shootoff:plugin-api-test-fixtures") }
    }
    testImplementation("org.slf4j:slf4j-api:2.0.20")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

`src/main/resources/shootoff.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<shootoffExercise apiVersion="2" exerciseClass="com.example.MyDrill" />
```

The exercise class implements `com.shootoff.exercise.Exercise` and has a public no-argument constructor that does no work. ShootOFF creates one instance to read `metadata()` for the Training menu, and a fresh one every time the exercise starts. A projector-only exercise says so in its metadata: `new ExerciseMetadata(name, version, creator, description, true)`.

## The exercise's thread

Every call the host makes on the exercise runs on one thread per running exercise. That covers `start`, `onShot`, `onTargetsChanged`, `onReset`, `stop`, scheduled tasks, button and setting callbacks, and par/delay listeners. So an exercise needs no locking.

- Use `host.schedule` / `scheduleRepeating` for timers, never your own threads or `Thread.sleep`. A sleeping exercise thread delays every shot behind it.
- Use `host.currentTimeMillis()` to time things, so tests can control the clock.
- Host methods may be called from any thread.
- When the exercise stops, the host runs the callbacks already queued, calls `stop()`, cancels every scheduled task, and removes everything the exercise added: targets, texts, buttons, settings, columns, markers, the par/delay controls, and the arena background it set. Host calls after that change nothing.
- A callback that throws is logged, and the exercise carries on.

## `ExerciseHost` at a glance

| Area | Methods |
|---|---|
| Surface | `surfaceSize()`, `isProjector()`, `setBackground(image)` (arena only; restored when the exercise stops) |
| Targets | `addTarget(file, x, y)` → `Optional<TargetHandle>`; `targets()`. `TargetHandle`: `id`, `move`, `resize`, `setVisible` (hidden targets take no hits), `remove`, `position`, `size`, `definition` |
| Text | `showText(text, x, y, TextStyle)` → `TextHandle` (`setText`, `move`, `remove`); `showMessage(text)`, the banner on every camera feed, also recorded in sessions |
| Controls | `addButton(label, onClick)` → `ButtonHandle` (`setLabel`, `remove`); `addNumberSetting(label, initial, min, max, step, onChange)` |
| Shot timer | `addColumn(name)`, `setColumnValue(name, value)` (latest row), `styleLastRow(RowStyle)` |
| Shots | `showShotMarker(x, y, ShotStyle)` → `ShotMarkerHandle`; `clearShots()`; `pauseShotDetection(paused)` |
| Sound | `playSound(name)`, `playSounds(names)` (one after another), `say(text)` |
| Time | `currentTimeMillis()`, `schedule(task, delay)`, `scheduleRepeating(task, initialDelay, period)` → `Cancellable` |
| Resources | `resource(path)` (the exercise's jar), `dataDirectory()` (`<ShootOFF>/exercise-data/<exercise class>/`) |
| Shared settings | `parTime()`, `setParTime(s)`, `onParTimeChanged(listener)`, `delayedStart()`, `setDelayedStart(range)`, `onDelayedStartChanged(listener)`. The shared controls show while a listener is registered; the listeners hear the user's changes, not the exercise's own `set…` calls |

Shots arrive in the surface's coordinates: arena coordinates for a projector exercise. A hit is a `com.shootoff.targets.model.Hit`: the target's `TargetId`, the `Region` (with its tags, for example `points`), and the impact in target coordinates. Colors in `TextStyle` and `RowStyle` are `#RRGGBB` or CSS color names, as in `.target` files.

### Names of targets, sounds and images

A name is looked up in the exercise's own jar first, with a leading `@` or `/` dropped. Otherwise it is a file:
- absolute
- relative to the ShootOFF folder
- relative to its `targets/` folder (targets) or `sounds/` folder (sounds)

So `"@targets/My.target"`, `"/sounds/buzzer.wav"`, `"sounds/beep.wav"` and `"IPSC.target"` all work.

## Testing with `FakeExerciseHost`

`com.shootoff.exercise.FakeExerciseHost` (plugin-api's test fixtures) is an in-memory host. It has a manual clock, records everything the exercise shows, and lets the test play the user.

```java
final FakeExerciseHost host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, true, tempDir);
host.start(new MyDrill());
host.changeDelayedStart(new DelayRange(1, 1));   // the user edits the shared controls
host.advance(Duration.ofSeconds(11));            // runs every task that falls due
final Point p = host.targets().get(0).position();
host.shoot(ShotColor.RED, p.getX() + 200, p.getY() + 200);   // hit-tested against the drill's targets
assertEquals(Optional.of("Round: 1/10"), host.textAt(640, 10));
host.click("Pause");
```

`FakeExerciseHost` reads "the exercise's jar" from the test's class loader, so the plugin's `src/main/resources` are found.

## Changes for v1 plugins

v1 plugins compile against `com.shootoff:shootoff:5.0.0-SNAPSHOT`. Its POM lists `com.shootoff:core` and `com.shootoff:plugin-api` at compile scope. A build that declares it with `isTransitive = false` must also declare `com.shootoff:core` the same way.

The classes, methods and fields the installed v1 plugins use are unchanged, source and binary. Beyond them, the move of ShootOFF's engine into `core` (sub-project 1a) changed these types a v1 plugin can see:

- **Configuration.** Persisted settings are in `com.shootoff.config.Settings` (module `core`), which `Configuration` extends. `Settings.getSettings()` returns the current settings.
  - `getIgnoreLaserColor()` returns `Optional<ShotColor>` instead of an optional JavaFX `Color`.
  - `getArenaPosition()` returns `Optional<com.shootoff.geom.Point>`.
  - `CalibrationOption` moved from `com.shootoff.gui` to `com.shootoff.config`, and `getCalibratedFeedBehavior()` returns it.
- **Cameras.**
  - `CameraManager.getCurrentFrame()` returns a `java.awt.image.BufferedImage` instead of a JavaFX `Image`.
  - `startRecordingShots` takes an `Optional<String>` session name.
  - Projection bounds are a `com.shootoff.geom.Rect` (`setProjectionBounds(Rect)`, `getProjectionBounds()` → `Optional<Rect>`).
- **Geometry.** Calibration and perspective APIs take and return `com.shootoff.geom.Point`, `Size` and `Rect` instead of JavaFX's `Point2D`, `Dimension2D` and `Bounds`. For example, `PerspectiveManager.calculateObjectSize` returns `Optional<Size>`.
- **Targets.** A target hidden with `setVisible(false)` no longer takes hits. `Hit.getModelHit()` returns the core target model's hit.
- **Plugin engine.** `com.shootoff.plugins.engine` (`PluginEngine`, `Plugin`, `PluginListener`) moved to `core` and now works with `ExerciseEntry` and one `ExerciseLoader` per API version. `ExerciseMetadata` moved to `core`, in the same package, and gained a projector-only flag.
- **Threading.** A target's position, size or visibility changed from an exercise's own thread now reaches the screen on the JavaFX thread, a moment later. The target's model, which `getPosition()` and `getDimension()` read, changes at once.
````

- [ ] **Step 4: Publish and check the artifacts**

```bash
cd /home/bfears/projects/ShootOFF
./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain -q
M2=build/m2/com/shootoff
python3 build/pom-scopes.py
ls $M2/plugin-api/5.0.0-SNAPSHOT | command grep -c "^plugin-api-5.0.0-SNAPSHOT-test-fixtures.jar$"
command grep -c '"name": "plugin-api-test-fixtures"' $M2/plugin-api/5.0.0-SNAPSHOT/plugin-api-5.0.0-SNAPSHOT.module
unzip -l $M2/plugin-api/5.0.0-SNAPSHOT/plugin-api-5.0.0-SNAPSHOT-test-fixtures.jar | command grep -c "com/shootoff/exercise/FakeExerciseHost.class"
unzip -l $M2/plugin-api/5.0.0-SNAPSHOT/plugin-api-5.0.0-SNAPSHOT.jar | command grep -cE "com/shootoff/exercise/ExerciseHost.class|plugins/engine/V2ExerciseLoader.class"
unzip -l $M2/core/5.0.0-SNAPSHOT/core-5.0.0-SNAPSHOT.jar | command grep -cE "plugins/engine/PluginEngine.class|plugins/ExerciseMetadata.class"
unzip -l $M2/shootoff/5.0.0-SNAPSHOT/shootoff-5.0.0-SNAPSHOT.jar | command grep -cE "gui/exercise/JavaFxExerciseHost.class|plugins/engine/LegacyExerciseLoader.class|plugins/TrainingExerciseBase.class"
rm build/pom-scopes.py
```

Expected, in order:
- `core compile` and `plugin-api compile`
- `1`
- `2` (the API and runtime variants of the fixtures)
- `1`, `2`, `2`, `3`

- [ ] **Step 5: The v1 drill's `master` still compiles against the published app**

This is the same scratch check as Plan 2 Task 8 Step 7. The drill's own repository is not touched.

```bash
cd /home/bfears/projects/ShootOFF
rm -rf build/drill-check && mkdir -p build/drill-check
git -C /home/bfears/projects/RandomTargetParDrill archive master | tar -x -C build/drill-check
command grep -q 'com.shootoff:core' build/drill-check/build.gradle.kts || perl -0pi -e 's/(    compileOnly\("com\.shootoff:shootoff:5\.0\.0-SNAPSHOT"\) \{ isTransitive = false \}\n)/$1    compileOnly("com.shootoff:core:5.0.0-SNAPSHOT") { isTransitive = false }\n/' build/drill-check/build.gradle.kts
(cd build/drill-check && ./gradlew compileJava -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain) 2>&1 | tail -3
rm -rf build/drill-check
```

Expected: `BUILD SUCCESSFUL`. `git archive master` exports the drill's `master` commit, so the check tests `master` whichever branch the drill's working tree is on.

- [ ] **Step 6: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 0 (**361**).

- [ ] **Step 7: Commit**

```bash
git add javafx-app/build.gradle.kts docs/plugin-api.md
git status --short
git commit -m "Publish plugin-api with its test fixtures, expose core to v1 plugin builds, and document the plugin API"
git log -1 --format=%B
```

`git status --short` must show only ` M shootoff.properties` (plus the owner's `??` files, if any) besides the staged files.

---
### Task 8: RandomTargetParDrill on the new API

**Runs in `/home/bfears/projects/RandomTargetParDrill`, on a new branch `plugin-api-v2`.** Never commit to its `master`, and never push. The drill's build finds `plugin-api` and `core` in ShootOFF's `build/m2` (Task 7), through `mavenLocal()` and `-Dmaven.repo.local`.

**Files (all in the drill repository):**
- Modify: `build.gradle.kts` (no OpenJFX; `plugin-api`, `core` and the test fixtures; the jar is named `RandomTargetParDrill-v2.jar`)
- Modify: `src/main/resources/shootoff.xml` (`apiVersion="2"`)
- Rewrite: `src/main/java/com/shootoff/plugins/RandomTargetParDrill.java`
- Delete: `src/main/java/com/shootoff/gui/RoundLimitListener.java` (`git rm`)
- Unchanged: `HitFactor.java`, `HitFactorSummary.java`, `PersonalBests.java` and their tests
- Test: `src/test/java/com/shootoff/plugins/TestRandomTargetParDrill.java`

**Interfaces:**
- Consumes: `Exercise`, `ExerciseHost` and its types, `FakeExerciseHost` (Tasks 2–3); `ExerciseMetadata` (5-argument constructor), `Shot`, `ShotColor`, `Point`, `Size`, `com.shootoff.targets.model.Hit` (core).
- Produces: `public class RandomTargetParDrill implements Exercise`:
  - a public no-argument constructor, and `RandomTargetParDrill(Random)` for tests
  - metadata: "Random Target PAR Drill with Score", `2.0`, "Benjamin Fears", projector-only
  - package-visible constants the tests use: `BESTS_FILE`, `BEEP_WAV`, `BUZZER_WAV`, `MAKE_READY_WAV`, `PAUSE`, `RESUME`, `CLEAR_SHOTS`, `ROUNDS_SETTING`, `LENGTH_COL_NAME`, `POINTS_COL_NAME`

  Its texts: the score and summary at (10, 10), the round at (surface width / 2, 10), and the last shot's time at (10, surface height − 80).

**What the port keeps and changes.** The v1 class is the reference. Each v1 behavior maps to v2 as follows:

| v1 | v2 |
|---|---|
| `init`: add `@targets/ISSF.target` hidden, black background, Pause/Clear Shots buttons, Length/Score columns, score/round/time labels, round-limit pane, par/delay pane; start in 10 s | the same through `addTarget`, `setBackground`, `addButton`, `addColumn`, `showText`, `addNumberSetting("Shots per round", 10, 1, 100, 1, …)`, `setParTime(4.0)`/`setDelayedStart(5–8)` with listeners |
| `SetupWait`: pause detection, "make ready", round after the random delay | same, through `schedule` |
| `doRound`: beep, random placement, show, round label, detection on, `Thread.sleep(par)`, par-miss check, buzzer, detection off, completion check | `startRound` (up to detection on and the round timer), then `endRound` scheduled after the par time. The exercise thread never sleeps, so shots during the par time are delivered and timed at once. |
| shot: ignore green hits, time it (Length column), shoot-to-reset, show the marker, track it, score points (Score column, score label), "Missed!" | same. Times come from `host.currentTimeMillis()`, and markers are `showShotMarker` handles. |
| par miss: coral row from a fake shot at (−10, −10), "Par missed!" | "Par missed!", and the miss is tracked; no timer row (ruling 13) |
| between rounds: hide target and markers | same; the round's marker handles are removed |
| summary: target in the middle, markers moved to target-relative positions, hit factor with personal best, statistics on the score label and camera feeds | same text. Markers are drawn at `summaryTargetPosition − targetPositionAtShot + shotPosition`. The text goes to the score text and `showMessage`. |
| pause/resume: flags; resume after 5 s | same, and the pending round start is cancelled or replaced (ruling 14) |
| reset: cancel everything, restart in 5 s | `onReset`: same |
| personal bests in `<shootoff.home>/RandomTargetParDrill-bests.properties` | in `host.dataDirectory()`. Task 9 adds the copy from the ShootOFF home. |

- [ ] **Step 1: Create the branch**

```bash
cd /home/bfears/projects/RandomTargetParDrill
git status --short
git switch -c plugin-api-v2 master
git branch --show-current
```

Expected: an empty status before the switch, then `plugin-api-v2`. If the status isn't empty, stop and ask the owner; don't stash or discard their changes.

- [ ] **Step 2: Publish ShootOFF's modules**

```bash
cd /home/bfears/projects/ShootOFF
./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain -q
ls build/m2/com/shootoff/plugin-api/5.0.0-SNAPSHOT | command grep -c "test-fixtures.jar$"
```

Expected: `1`.

- [ ] **Step 3: Switch the build to the plugin API**

In the drill repository, replace `build.gradle.kts` with:

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // ShootOFF's plugin API: run `./gradlew publishToMavenLocal` in the ShootOFF project first
    mavenLocal()
    mavenCentral()
}

val shootoffVersion = "5.0.0-SNAPSHOT"

dependencies {
    // Provided by ShootOFF at runtime, so none of it goes into the plugin jar. Not transitive: core's
    // own libraries (OpenCV, MaryTTS, ...) aren't needed to compile an exercise, and some aren't on
    // Maven Central
    compileOnly("com.shootoff:plugin-api:$shootoffVersion") { isTransitive = false }
    compileOnly("com.shootoff:core:$shootoffVersion") { isTransitive = false }
    compileOnly("org.slf4j:slf4j-api:2.0.20")

    testImplementation("com.shootoff:plugin-api:$shootoffVersion") { isTransitive = false }
    testImplementation("com.shootoff:core:$shootoffVersion") { isTransitive = false }
    // FakeExerciseHost, from plugin-api's test fixtures
    testImplementation("com.shootoff:plugin-api:$shootoffVersion") {
        isTransitive = false
        capabilities { requireCapability("com.shootoff:plugin-api-test-fixtures") }
    }
    testImplementation("org.slf4j:slf4j-api:2.0.20")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    // Installed next to the v1 drill's RandomTargetParDrill.jar rather than over it
    archiveFileName = "RandomTargetParDrill-v2.jar"
}

// Directory of the ShootOFF install to copy the plugin into; override with -PshootoffHome=...
val shootoffHome = providers.gradleProperty("shootoffHome").orElse("../ShootOFF")

tasks.register<Copy>("installPlugin") {
    description = "Copies the plugin jar into ShootOFF's exercises folder"
    group = "distribution"
    from(tasks.jar)
    into(shootoffHome.map { "$it/exercises" })
}
```

Replace `src/main/resources/shootoff.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<shootoffExercise apiVersion="2" exerciseClass="com.shootoff.plugins.RandomTargetParDrill" />
```

```bash
git rm src/main/java/com/shootoff/gui/RoundLimitListener.java
```

- [ ] **Step 4: Write the failing tests**

`src/test/java/com/shootoff/plugins/TestRandomTargetParDrill.java`:

```java
package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
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
import com.shootoff.exercise.ShotStyle;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.geom.Point;

class TestRandomTargetParDrill {
	@TempDir Path temp;
	private Locale previousLocale;
	private FakeExerciseHost host;

	@BeforeEach
	void setUp() {
		previousLocale = Locale.getDefault();
		// The drill formats times in the default locale, as it always has
		Locale.setDefault(Locale.US);
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, true, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
	}

	// Starts a drill of the given rounds with a 1 s start delay and a 2 s par time, and runs it to its
	// first round: the target shows 11 s after the start (the 10 s start delay, then 1 s)
	private void startDrill(int rounds) {
		host.start(new RandomTargetParDrill(new Random(42)));
		host.changeDelayedStart(new DelayRange(1, 1));
		host.changeParTime(2.0);
		host.changeSetting(RandomTargetParDrill.ROUNDS_SETTING, rounds);
		host.advance(Duration.ofSeconds(11));
	}

	private TargetHandle target() {
		return host.targets().get(0);
	}

	// The ISSF target is 400 x 400; its 10-point center is 200, 200 into it
	private void shootCenter() {
		final Point position = target().position();
		assertTrue(host.shoot(ShotColor.RED, position.getX() + 200, position.getY() + 200));
	}

	private String scoreText() {
		return host.textAt(10, 10).orElseThrow();
	}

	private String timeText() {
		return host.textAt(10, 640).orElseThrow();
	}

	private Optional<String> roundText() {
		return host.textAt(640, 10);
	}

	private static long count(List<String> sounds, String sound) {
		return sounds.stream().filter(sound::equals).count();
	}

	@Test
	void fullDrillScoresTenRoundsAndReplaysTheShotsOnTheSummaryTarget() {
		startDrill(10);

		for (int round = 1; round <= 10; round++) {
			assertTrue(host.isVisible(target()), "round " + round);
			assertEquals(Optional.of("Round: " + round + "/10"), roundText());

			host.advance(Duration.ofMillis(500));
			shootCenter();
			assertEquals("10 points   -  0.500 seconds", timeText());

			// The par time ends 2 s after the beep; the next round starts 1 s later
			host.advance(Duration.ofMillis(2500));
		}

		assertEquals(10, count(host.sounds(), RandomTargetParDrill.BEEP_WAV));
		assertEquals(10, count(host.sounds(), RandomTargetParDrill.BUZZER_WAV));
		assertEquals(10, host.rows().size());
		for (final FakeExerciseHost.Row row : host.rows()) {
			assertEquals("0.50", row.values().get(RandomTargetParDrill.LENGTH_COL_NAME));
			assertEquals("10", row.values().get(RandomTargetParDrill.POINTS_COL_NAME));
		}
		assertTrue(host.messages().contains("Score: 100"));

		final String summary = "Hit Factor: 20.00   (10 rounds, 2.00 s par)\nNew personal best!\n\n"
				+ "Total Shots: 10\nTotal Points: 100\nTotal Time: 5.00\nAverage Points: 10.000\n"
				+ "Average Time: 0.500\nPoints min/max: 10.00/10.00\nTimes min/max: 0.500/0.500\n"
				+ "Missed Shots: 0\nMissed Par: 0";
		assertEquals(summary, scoreText());
		assertEquals(summary, host.messages().get(host.messages().size() - 1));

		// The summary target sits up and left of the middle of the arena, and every shot is replayed
		// where it hit the target: its center
		assertEquals(new Point(390, 110), target().position());
		assertTrue(host.isVisible(target()));
		assertEquals(10, host.shotMarkers().size());
		for (final FakeExerciseHost.ShownMarker marker : host.shotMarkers()) {
			assertEquals(590, marker.x(), 1e-9);
			assertEquals(310, marker.y(), 1e-9);
		}
	}

	@Test
	void parMissIsCountedAndPenalized() {
		startDrill(1);

		host.advance(Duration.ofSeconds(2));
		assertEquals("Par missed!", timeText());
		assertTrue(host.rows().isEmpty());

		host.advance(Duration.ofSeconds(1));
		assertEquals("Hit Factor: 0.00   (1 rounds, 2.00 s par)\nNew personal best!\n\n"
				+ "Total Shots: 1\nTotal Points: 0\nTotal Time: 2.00\nAverage Points: 0.000\n"
				+ "Average Time: 2.000\nPoints min/max: 0.00/0.00\nTimes min/max: 2.000/2.000\n"
				+ "Missed Shots: 0\nMissed Par: 1", scoreText());
		assertEquals(List.of(), host.shotMarkers());
	}

	@Test
	void missIsCountedAndPenalized() {
		startDrill(1);

		host.advance(Duration.ofMillis(500));
		final Point position = target().position();
		// Past the target's bottom-right corner, still on the arena
		assertTrue(host.shoot(ShotColor.RED, position.getX() + 410, position.getY() + 410));
		assertEquals("Missed!", timeText());

		host.advance(Duration.ofMillis(2500));
		assertEquals("Hit Factor: 0.00   (1 rounds, 2.00 s par)\nNew personal best!\n\n"
				+ "Total Shots: 1\nTotal Points: 0\nTotal Time: 0.50\nAverage Points: 0.000\n"
				+ "Average Time: 0.500\nPoints min/max: 0.00/0.00\nTimes min/max: 0.500/0.500\n"
				+ "Missed Shots: 1\nMissed Par: 0", scoreText());
		assertEquals(List.of(new FakeExerciseHost.ShownMarker(800, 520, new ShotStyle(ShotColor.RED))),
				host.shotMarkers());
	}

	@Test
	void pauseAndResumeRestartTheCountdown() {
		host.start(new RandomTargetParDrill(new Random(42)));
		host.changeDelayedStart(new DelayRange(1, 1));
		host.advance(Duration.ofSeconds(10));
		assertEquals(List.of(RandomTargetParDrill.MAKE_READY_WAV), host.sounds());

		host.advance(Duration.ofMillis(500));
		host.click(RandomTargetParDrill.PAUSE);
		assertEquals(List.of(RandomTargetParDrill.RESUME, RandomTargetParDrill.CLEAR_SHOTS), host.buttonLabels());
		assertTrue(host.isShotDetectionPaused());

		// Resuming before the paused round would have started must not start two rounds
		host.advance(Duration.ofMillis(100));
		host.click(RandomTargetParDrill.RESUME);
		assertEquals(List.of(RandomTargetParDrill.PAUSE, RandomTargetParDrill.CLEAR_SHOTS), host.buttonLabels());
		host.advance(Duration.ofMillis(4900));
		assertEquals(List.of(RandomTargetParDrill.MAKE_READY_WAV), host.sounds());
		assertFalse(host.isVisible(target()));

		// 5 s after resuming: "make ready", then the round 1 s later
		host.advance(Duration.ofMillis(100));
		assertEquals(List.of(RandomTargetParDrill.MAKE_READY_WAV, RandomTargetParDrill.MAKE_READY_WAV), host.sounds());
		host.advance(Duration.ofSeconds(1));
		assertEquals(List.of(RandomTargetParDrill.MAKE_READY_WAV, RandomTargetParDrill.MAKE_READY_WAV,
				RandomTargetParDrill.BEEP_WAV), host.sounds());
		assertTrue(host.isVisible(target()));
		assertFalse(host.isShotDetectionPaused());
		assertEquals(Optional.of("Round: 1/10"), roundText());
	}

	@Test
	void summaryComparesTheHitFactorWithThePersonalBest() throws IOException {
		final Path bests = temp.resolve("data").resolve(RandomTargetParDrill.BESTS_FILE);
		Files.writeString(bests, "1rounds-2.00spar=40.0\n");

		startDrill(1);
		host.advance(Duration.ofMillis(500));
		shootCenter();
		host.advance(Duration.ofMillis(2500));

		assertTrue(scoreText().startsWith("Hit Factor: 20.00   (1 rounds, 2.00 s par)\n"
				+ "50% of personal best (40.00)\n\nTotal Shots: 1\nTotal Points: 10\n"), scoreText());
		// A worse run keeps the best
		assertEquals(Optional.of(40.0), new PersonalBests(bests).best("1rounds-2.00spar"));
	}
}
```

- [ ] **Step 5: Run them to verify they fail**

```bash
cd /home/bfears/projects/RandomTargetParDrill
./gradlew test -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain 2>&1 | command grep -E "error:|FAILED|BUILD" | head -5
```

Expected: `BUILD FAILED`, with compile errors in `RandomTargetParDrill.java`: `package javafx.application does not exist`. The v1 class no longer compiles against the plugin API.

- [ ] **Step 6: Rewrite the drill**

Replace `src/main/java/com/shootoff/plugins/RandomTargetParDrill.java` with:

```java
package com.shootoff.plugins;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.ShotMarkerHandle;
import com.shootoff.exercise.ShotStyle;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.exercise.TextHandle;
import com.shootoff.exercise.TextStyle;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.targets.model.Hit;

/**
 * Shows the ISSF target at a random place on the projector arena after a random delay: shoot it
 * before the par time runs out. After the last round it shows the hit factor against the personal
 * best for the same settings, and replays every shot on the target.
 */
public class RandomTargetParDrill implements Exercise {
	private static final Logger logger = LoggerFactory.getLogger(RandomTargetParDrill.class);

	static final String BESTS_FILE = "RandomTargetParDrill-bests.properties";
	static final String TARGET_FILE = "@targets/ISSF.target";
	static final String BACKGROUND = "/backgrounds/blackBG.png";
	static final String BUZZER_WAV = "/sounds/buzzer.wav";
	static final String MAKE_READY_WAV = "sounds/voice/shootoff-makeready.wav";
	static final String BEEP_WAV = "sounds/beep.wav";
	static final String PAUSE = "Pause";
	static final String RESUME = "Resume";
	static final String CLEAR_SHOTS = "Clear Shots";
	static final String ROUNDS_SETTING = "Shots per round";
	static final String LENGTH_COL_NAME = "Length";
	static final String POINTS_COL_NAME = "Score";

	static final double DEFAULT_PAR_TIME = 4.0;
	static final DelayRange DEFAULT_DELAY = new DelayRange(5, 8);
	static final int DEFAULT_MAX_ROUNDS = 10;

	private static final Duration START_DELAY = Duration.ofSeconds(10);
	private static final Duration RESUME_DELAY = Duration.ofSeconds(5);
	private static final Duration SHOOT_TO_RESET_DELAY = Duration.ofSeconds(4);
	private static final Duration RESULTS_DELAY = Duration.ofSeconds(1);
	private static final Duration FINAL_HIDE_DELAY = Duration.ofMillis(500);
	// Targets stay this far from the arena's right and bottom edges; the summary target sits this far
	// up and left of the middle
	private static final int MARGIN = 50;

	private static final TextStyle LABEL_STYLE = new TextStyle(40, "white", "transparent");
	private static final TextStyle TIME_STYLE = new TextStyle(60, "white", "transparent");

	private final Random random;
	private ExerciseHost host;
	private PersonalBests personalBests;
	private TargetHandle target;
	private ButtonHandle pauseResumeButton;
	private TextHandle scoreText;
	private TextHandle roundText;
	private TextHandle timeText;

	// Every scheduled task, all cancelled by a reset; the pending round step, replaced by a resume
	private final List<Cancellable> pending = new ArrayList<>();
	private Optional<Cancellable> nextStep = Optional.empty();
	private final List<ShotMarkerHandle> roundMarkers = new ArrayList<>();
	private final List<ShotMarkerHandle> replayMarkers = new ArrayList<>();
	private final List<TrackedShot> trackedShots = new ArrayList<>();

	private double parTime = DEFAULT_PAR_TIME;
	private int delayMin = DEFAULT_DELAY.minSeconds();
	private int delayMax = DEFAULT_DELAY.maxSeconds();
	private int roundLimit = DEFAULT_MAX_ROUNDS;
	private boolean paused = false;
	private boolean repeatExercise = true;
	private boolean countScore = false;
	private boolean shootToReset = false;
	private boolean hadShot = false;
	private boolean isDrillComplete = false;
	private long beepTime = 0;
	private long roundStartTime = 0;
	private float shotTime;
	private int score = 0;
	private int round = 0;

	/**
	 * A shot or a par miss, with where the target was at the time
	 */
	private record TrackedShot(Optional<Point> position, Point targetPosition, ShotColor color, boolean isHit,
			int points, float shotTime, boolean missedPar) {}

	public RandomTargetParDrill() {
		this(new Random());
	}

	RandomTargetParDrill(Random random) {
		this.random = random;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("Random Target PAR Drill with Score", "2.0", "Benjamin Fears",
				"Shoot a randomly placed target as fast as you can.", true);
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		personalBests = new PersonalBests(host.dataDirectory().resolve(BESTS_FILE));

		target = host.addTarget(TARGET_FILE, 0, 0)
				.orElseThrow(() -> new IllegalStateException("Can't load " + TARGET_FILE));
		target.setVisible(false);

		initUI();
		initService();
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		if (hit.isPresent() && shot.getColor() == ShotColor.GREEN) return;

		if (repeatExercise) {
			hadShot = true;
			setLength();
		}

		if (shootToReset) {
			shootToReset = false;
			host.clearShots();
			onReset();
			return;
		}

		roundMarkers.add(host.showShotMarker(shot.getX(), shot.getY(), new ShotStyle(shot.getColor())));
		recordShot(shot, hit);

		if (hit.isEmpty() || !countScore) {
			if (!countScore) {
				logger.debug("count score is false!");
			} else {
				logger.debug("hit is not present!");
				setLastTime("Missed!");
			}
			return;
		}

		String roundScore = "";
		final Optional<String> points = hit.get().region().tag("points");
		if (points.isPresent()) {
			setPoints(shot.getColor(), points.get());
			roundScore += String.format("%d points   -  ", Integer.parseInt(points.get()));
		}

		roundScore += String.format("%.3f seconds", shotTime);
		setLastTime(roundScore);
	}

	@Override
	public void onReset() {
		host.pauseShotDetection(true);
		cancelPending();
		paused = false;
		pauseResumeButton.setLabel(PAUSE);

		hideTargetAndShots();
		removeMarkers(replayMarkers);

		resetValues();
		scheduleNextStep(this::setupWait, RESUME_DELAY);
	}

	@Override
	public void stop() {
		// The host cancels the tasks and removes what the drill added
		repeatExercise = false;
	}

	private void initUI() {
		host.setBackground(BACKGROUND);
		pauseResumeButton = host.addButton(PAUSE, this::pauseOrResume);
		host.addButton(CLEAR_SHOTS, host::clearShots);
		host.addColumn(LENGTH_COL_NAME);
		host.addColumn(POINTS_COL_NAME);

		final Size surface = host.surfaceSize();
		scoreText = host.showText("Score: 0", 10, 10, LABEL_STYLE);
		roundText = host.showText(roundLabel(), surface.getWidth() / 2, 10, LABEL_STYLE);
		timeText = host.showText("", 10, surface.getHeight() - 80, TIME_STYLE);

		host.addNumberSetting(ROUNDS_SETTING, DEFAULT_MAX_ROUNDS, 1, 100, 1, value -> roundLimit = (int) value);
		host.setParTime(DEFAULT_PAR_TIME);
		host.setDelayedStart(DEFAULT_DELAY);
		host.onParTimeChanged(value -> parTime = value);
		host.onDelayedStartChanged(range -> {
			delayMin = range.minSeconds();
			delayMax = range.maxSeconds();
		});
	}

	private void initService() {
		host.pauseShotDetection(true);
		resetValues();
		scheduleNextStep(this::setupWait, START_DELAY);
	}

	private void schedule(Runnable task, Duration delay) {
		pending.add(host.schedule(task, delay));
	}

	// The drill's next step (make ready, or a round): at most one is pending
	private void scheduleNextStep(Runnable step, Duration delay) {
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.of(host.schedule(step, delay));
	}

	private void cancelPending() {
		pending.forEach(Cancellable::cancel);
		pending.clear();
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.empty();
	}

	private void pauseOrResume() {
		if (round >= roundLimit) {
			soundBuzzer();
			return;
		}

		if (!paused) {
			paused = true;
			pauseResumeButton.setLabel(RESUME);
			repeatExercise = false;
			host.pauseShotDetection(true);
			nextStep.ifPresent(Cancellable::cancel);
			nextStep = Optional.empty();
		} else {
			paused = false;
			pauseResumeButton.setLabel(PAUSE);
			repeatExercise = true;
			scheduleNextStep(this::setupWait, RESUME_DELAY);
		}
	}

	private void setupWait() {
		if (!repeatExercise) return;

		host.pauseShotDetection(true);
		host.playSound(MAKE_READY_WAV);
		scheduleNextStep(this::startRound, Duration.ofSeconds(randomDelay()));
	}

	private void startRound() {
		if (!repeatExercise) return;

		countScore = true;
		round++;
		host.playSound(BEEP_WAV);

		randomizeTarget();
		target.setVisible(true);

		roundText.setText(roundLabel());
		hideLastTime();

		host.pauseShotDetection(false);
		startRoundTimer();
		schedule(this::endRound, Duration.ofMillis(Math.round(parTime * 1000)));
	}

	private void endRound() {
		if (!hadShot) {
			logger.info("Round ended without a shot");
			parMissed();
		}

		soundBuzzer();

		host.pauseShotDetection(true);
		countScore = false;
		checkDrillComplete();

		final int nextDelay = setupRound();
		scheduleNextStep(this::startRound, Duration.ofSeconds(nextDelay));

		if (isDrillComplete) schedule(this::displayResults, RESULTS_DELAY);
	}

	// Hides the target and shots before the next round; returns the delay before it, in seconds
	private int setupRound() {
		hadShot = false;

		final int randomDelay = randomDelay();
		final int randomDelay2 = random.nextInt((Integer.max(delayMax / 2, delayMin) - delayMin) + 1) + delayMin;

		if (isDrillComplete) {
			schedule(this::hideTargetAndShots, FINAL_HIDE_DELAY);
			return 0;
		}

		schedule(this::hideTargetAndShots, Duration.ofSeconds(Integer.min(randomDelay, randomDelay2)));
		return randomDelay;
	}

	private int randomDelay() {
		return random.nextInt((delayMax - delayMin) + 1) + delayMin;
	}

	private void soundBuzzer() {
		host.playSound(BUZZER_WAV);
	}

	private void setLength() {
		final float drawShotLength = (float) (host.currentTimeMillis() - beepTime) / 1000f; // s
		host.setColumnValue(LENGTH_COL_NAME, String.format("%.2f", drawShotLength));
		shotTime = drawShotLength;
	}

	private void recordShot(Shot shot, Optional<Hit> hit) {
		if (shot.getColor() == ShotColor.GREEN) {
			logger.info("Ignored GREEN shot!!!");
			return;
		}

		trackedShots.add(new TrackedShot(Optional.of(new Point(shot.getX(), shot.getY())), target.position(),
				shot.getColor(), hit.isPresent(), points(hit), shotTime, false));
	}

	private static int points(Optional<Hit> hit) {
		return hit.flatMap(h -> h.region().tag("points")).map(Integer::parseInt).orElse(0);
	}

	private void parMissed() {
		shotTime = (float) (host.currentTimeMillis() - beepTime) / 1000f;
		trackedShots.add(new TrackedShot(Optional.empty(), target.position(), ShotColor.RED, false, 0, shotTime, true));
		setLastTime("Par missed!");
	}

	private void checkDrillComplete() {
		if (round >= roundLimit) {
			isDrillComplete = true;
			repeatExercise = false;

			schedule(() -> {
				// Wait 4 seconds to reactivate shot detection: the next shot restarts the drill
				host.pauseShotDetection(false);
				shootToReset = true;
			}, SHOOT_TO_RESET_DELAY);
		}
	}

	private void displayResults() {
		final Size surface = host.surfaceSize();
		final Size size = target.size();
		final double targetX = (surface.getWidth() / 2) - (size.getWidth() / 2) - MARGIN;
		final double targetY = (surface.getHeight() / 2) - (size.getHeight() / 2) - MARGIN;
		target.move(targetX, targetY);
		target.setVisible(true);

		int numShots = 0;
		float timeTotal = 0;
		int numMisses = 0;
		int numParMisses = 0;
		int pointsTotal = 0;
		float maxTime = 0;
		float minTime = 1000;
		float maxScore = 0;
		float minScore = 1000;

		for (final TrackedShot tracked : trackedShots) {
			logger.info(String.format("Shot %d: %.2f - par time = %.2f", numShots, tracked.shotTime(), parTime));
			numShots++;
			if (!tracked.isHit() && !tracked.missedPar()) numMisses++;
			if (tracked.missedPar()) numParMisses++;

			timeTotal += tracked.shotTime();
			minTime = Math.min(tracked.shotTime(), minTime);
			maxTime = Math.max(tracked.shotTime(), maxTime);

			pointsTotal += tracked.points();
			minScore = Math.min(tracked.points(), minScore);
			maxScore = Math.max(tracked.points(), maxScore);

			// Replay the shot where it hit the target, now that the target has moved
			tracked.position().ifPresent(position -> replayMarkers.add(host.showShotMarker(
					targetX - tracked.targetPosition().getX() + position.getX(),
					targetY - tracked.targetPosition().getY() + position.getY(), new ShotStyle(tracked.color()))));
		}

		final float avgTime = timeTotal / numShots;
		final float avgPoints = (float) pointsTotal / numShots;
		final double hitFactor = HitFactor.compute(pointsTotal, numMisses, numParMisses, timeTotal);

		logger.info(String.format(
				"Total Points: %d, Total Time: %.2f; Average Points: %.3f; Average Time: %.3f; Missed Shots: %d; Missed Par: %d; Hit Factor: %.2f",
				pointsTotal, timeTotal, avgPoints, avgTime, numMisses, numParMisses, hitFactor));

		final String message = hitFactorSummary(hitFactor) + "\n\n" + String.format(
				"Total Shots: %d\nTotal Points: %d\nTotal Time: %.2f\nAverage Points: %.3f\nAverage Time: %.3f\nPoints min/max: %.2f/%.2f\nTimes min/max: %.3f/%.3f\nMissed Shots: %d\nMissed Par: %d",
				numShots, pointsTotal, timeTotal, avgPoints, avgTime, minScore, maxScore, minTime, maxTime, numMisses,
				numParMisses);
		showOnFeeds(message);

		schedule(this::hideLastTime, RESULTS_DELAY);
	}

	private String hitFactorSummary(double hitFactor) {
		final String settingsKey = PersonalBests.settingsKey(roundLimit, parTime);

		try {
			final Optional<Double> previousBest = personalBests.best(settingsKey);
			personalBests.recordIfBest(settingsKey, hitFactor);
			return HitFactorSummary.format(hitFactor, roundLimit, parTime, previousBest);
		} catch (final IOException e) {
			logger.error("Could not read or save personal best hit factors", e);
			return HitFactorSummary.format(hitFactor, roundLimit, parTime);
		}
	}

	private void randomizeTarget() {
		final Size surface = host.surfaceSize();
		final Size size = target.size();
		logger.info(String.format("Target dimensions: w: %.1f, h: %.1f", size.getWidth(), size.getHeight()));

		final int maxX = (int) (surface.getWidth() - size.getWidth() - MARGIN);
		final int x = random.nextInt(maxX);
		final int maxY = (int) (surface.getHeight() - size.getHeight() - MARGIN);
		final int y = random.nextInt(maxY);

		logger.info(String.format("Placing target at x: %d, y: %d", x, y));
		target.move(x, y);
	}

	private void hideTargetAndShots() {
		target.setVisible(false);
		removeMarkers(roundMarkers);
	}

	private static void removeMarkers(List<ShotMarkerHandle> markers) {
		markers.forEach(ShotMarkerHandle::remove);
		markers.clear();
	}

	private String roundLabel() {
		return String.format("Round: %d/%d", round, roundLimit);
	}

	private void hideLastTime() {
		timeText.setText("");
	}

	private void setLastTime(String time) {
		timeText.setText(time);
	}

	// v1's showTextOnFeed on the projector: the arena's score text and every camera feed
	private void showOnFeeds(String message) {
		scoreText.setText(message);
		host.showMessage(message);
	}

	private void startRoundTimer() {
		beepTime = host.currentTimeMillis();
		if (roundStartTime == 0) roundStartTime = beepTime;
	}

	private void resetValues() {
		shootToReset = false;
		isDrillComplete = false;
		repeatExercise = true;
		roundStartTime = 0;
		score = 0;
		round = 0;
		trackedShots.clear();

		showOnFeeds("Score: 0");
		roundText.setText(roundLabel());
		hideLastTime();
	}

	private void setPoints(ShotColor shotColor, String points) {
		host.setColumnValue(POINTS_COL_NAME, points);

		if (shotColor == ShotColor.RED || shotColor == ShotColor.INFRARED) {
			score += Integer.parseInt(points);
		}

		showOnFeeds(String.format("Score: %d", score));
	}
}
```

- [ ] **Step 7: Run the tests to verify they pass**

```bash
cd /home/bfears/projects/RandomTargetParDrill
./gradlew test -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain 2>&1 | tail -3
python3 /home/bfears/projects/ShootOFF/scripts/test_summary.py summarize build/test-results/test | command grep -c '^PASS'
python3 /home/bfears/projects/ShootOFF/scripts/test_summary.py summarize build/test-results/test | command grep -v '^PASS'
```

Expected:
- `BUILD SUCCESSFUL`
- `19`: 14 unchanged tests (`TestHitFactor` 4, `TestHitFactorSummary` 4, `TestPersonalBests` 6) plus `TestRandomTargetParDrill` 5
- nothing printed by the last command

- [ ] **Step 8: Check the jar**

```bash
./gradlew jar -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain -q
unzip -p build/libs/RandomTargetParDrill-v2.jar shootoff.xml
rm -rf build/jar-check && mkdir -p build/jar-check && (cd build/jar-check && unzip -q ../libs/RandomTargetParDrill-v2.jar)
command grep -rlE "javafx[./]" build/jar-check | wc -l
command grep -rl "RoundLimitListener" build/jar-check | wc -l
ls build/jar-check/targets build/jar-check/sounds build/jar-check/backgrounds
rm -rf build/jar-check
```

Expected:
- the descriptor with `apiVersion="2"`
- `0` and `0`
- `ISSF.target`, `buzzer.wav` and `blackBG.png`

- [ ] **Step 9: Commit on `plugin-api-v2`**

```bash
cd /home/bfears/projects/RandomTargetParDrill
git branch --show-current
git add build.gradle.kts src/main/resources/shootoff.xml src/main/java/com/shootoff/plugins/RandomTargetParDrill.java \
  src/test/java/com/shootoff/plugins/TestRandomTargetParDrill.java
git status --short
git commit -m "Port the drill to ShootOFF's UI-neutral exercise API"
git log -1 --format=%B
```

Expected:
- `plugin-api-v2` from the first command
- a status listing only the staged files and the staged deletion of `RoundLimitListener.java`
- a message with no trailer

---
### Task 9: Personal-bests migration and installing the v2 jar

**Runs in `/home/bfears/projects/RandomTargetParDrill` on `plugin-api-v2`**, except the steps that check files in ShootOFF, which say so.

**Files (drill repository):**
- Create: `src/main/java/com/shootoff/plugins/BestsFile.java`
- Modify: `src/main/java/com/shootoff/plugins/RandomTargetParDrill.java` (`start`, one import)
- Test: `src/test/java/com/shootoff/plugins/TestBestsFile.java` (new); `TestRandomTargetParDrill.java` (`setUp`, `tearDown`, one test added)
- Installed (not committed anywhere): `/home/bfears/projects/ShootOFF/exercises/RandomTargetParDrill-v2.jar`

**Interfaces:**
- Consumes: `RandomTargetParDrill.BESTS_FILE`, `PersonalBests` (drill); `ExerciseHost.dataDirectory()`.
- Produces: `final class BestsFile` (package-private), `static Path locate(Path dataDirectory, Path shootoffHome)`. It returns `dataDirectory/BESTS_FILE`, first copying `shootoffHome/BESTS_FILE` there if the copy doesn't exist and the original does. It never overwrites, moves or deletes either file. A failed copy is logged, and the drill starts with empty bests.

- [ ] **Step 1: Record the owner's files (in ShootOFF)**

```bash
cd /home/bfears/projects/ShootOFF
sha256sum RandomTargetParDrill-bests.properties exercises/RandomTargetParDrill.jar > build/owner-files.sha256
cat build/owner-files.sha256
```

Task 10 checks these again. The bests file holds the owner's `10rounds-4.00spar` best.

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/shootoff/plugins/TestBestsFile.java`:

```java
package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestBestsFile {
	private static final String KEY = PersonalBests.settingsKey(10, 4.0);

	@TempDir Path temp;
	private Path data;
	private Path home;

	@BeforeEach
	void setUp() throws IOException {
		data = Files.createDirectories(temp.resolve("data"));
		home = Files.createDirectories(temp.resolve("home"));
	}

	@Test
	void copiesTheV1BestsOnTheFirstRun() throws IOException {
		Files.writeString(home.resolve(RandomTargetParDrill.BESTS_FILE), KEY + "=6.588104617997978\n");

		final Path bests = BestsFile.locate(data, home);

		assertEquals(data.resolve(RandomTargetParDrill.BESTS_FILE), bests);
		assertEquals(Optional.of(6.588104617997978), new PersonalBests(bests).best(KEY));
	}

	@Test
	void leavesTheV1FileForTheV1Drill() throws IOException {
		final Path legacy = home.resolve(RandomTargetParDrill.BESTS_FILE);
		Files.writeString(legacy, KEY + "=6.5\n");

		new PersonalBests(BestsFile.locate(data, home)).recordIfBest(KEY, 7.0);

		assertEquals(KEY + "=6.5\n", Files.readString(legacy));
	}

	@Test
	void neverReplacesBestsAlreadyInTheDataDirectory() throws IOException {
		Files.writeString(home.resolve(RandomTargetParDrill.BESTS_FILE), KEY + "=6.5\n");
		Files.writeString(data.resolve(RandomTargetParDrill.BESTS_FILE), KEY + "=7.0\n");

		BestsFile.locate(data, home);

		assertEquals(KEY + "=7.0\n", Files.readString(data.resolve(RandomTargetParDrill.BESTS_FILE)));
	}

	@Test
	void withoutV1BestsTheDataDirectoryStartsEmpty() throws IOException {
		final Path bests = BestsFile.locate(data, home);

		assertFalse(Files.exists(bests));
		assertEquals(Optional.empty(), new PersonalBests(bests).best(KEY));
	}
}
```

In `TestRandomTargetParDrill`, point the ShootOFF home at an empty folder for every test. Add a field `private String previousHome;`, and make `setUp` and `tearDown`:

```java
	@BeforeEach
	void setUp() throws IOException {
		previousLocale = Locale.getDefault();
		// The drill formats times in the default locale, as it always has
		Locale.setDefault(Locale.US);
		// Where the v1 drill kept its bests; empty unless a test writes there
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", Files.createDirectories(temp.resolve("home")).toString());
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, true, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}
```

Add the test:

```java
	@Test
	void theFirstV2RunCountsTheV1PersonalBest() throws IOException {
		final Path legacy = temp.resolve("home").resolve(RandomTargetParDrill.BESTS_FILE);
		Files.writeString(legacy, "1rounds-2.00spar=40.0\n");

		startDrill(1);
		host.advance(Duration.ofMillis(500));
		shootCenter();
		host.advance(Duration.ofMillis(2500));

		assertTrue(scoreText().startsWith("Hit Factor: 20.00   (1 rounds, 2.00 s par)\n"
				+ "50% of personal best (40.00)\n"), scoreText());
		// The v1 drill's file is untouched
		assertEquals("1rounds-2.00spar=40.0\n", Files.readString(legacy));
	}
```

- [ ] **Step 3: Run them to verify they fail**

```bash
cd /home/bfears/projects/RandomTargetParDrill
./gradlew test -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain 2>&1 | command grep -E "error:|FAILED|BUILD" | head -5
```

Expected: `BUILD FAILED`, `cannot find symbol: variable BestsFile`.

- [ ] **Step 4: Write `BestsFile` and use it**

`src/main/java/com/shootoff/plugins/BestsFile.java`:

```java
package com.shootoff.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the drill keeps its personal bests: the exercise's data directory. The v1 drill kept them in
 * the ShootOFF folder. The first v2 run copies that file and leaves it there for the v1 drill.
 */
final class BestsFile {
	private static final Logger logger = LoggerFactory.getLogger(BestsFile.class);

	private BestsFile() {}

	/**
	 * @return the bests file in <tt>dataDirectory</tt>, first copying the v1 drill's file from
	 *         <tt>shootoffHome</tt> if there is one and the copy doesn't exist yet. Neither file is ever
	 *         overwritten, moved or deleted.
	 */
	static Path locate(Path dataDirectory, Path shootoffHome) {
		final Path bests = dataDirectory.resolve(RandomTargetParDrill.BESTS_FILE);
		final Path legacy = shootoffHome.resolve(RandomTargetParDrill.BESTS_FILE);

		if (Files.exists(bests) || !Files.isRegularFile(legacy)) return bests;

		try {
			Files.copy(legacy, bests);
			logger.info("Copied the personal bests in {} to {}", legacy, bests);
		} catch (final IOException e) {
			logger.error("Could not copy the personal bests in {} to {}", legacy, bests, e);
		}

		return bests;
	}
}
```

In `RandomTargetParDrill.start`, replace the `personalBests` line with:

```java
		personalBests = new PersonalBests(BestsFile.locate(host.dataDirectory(),
				Paths.get(System.getProperty("shootoff.home", System.getProperty("user.dir")))));
```

Then add `import java.nio.file.Paths;`.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew test -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain 2>&1 | tail -3
python3 /home/bfears/projects/ShootOFF/scripts/test_summary.py summarize build/test-results/test | command grep -c '^PASS'
```

Expected: `BUILD SUCCESSFUL`, and `24` (19 plus `TestBestsFile` 4 and `theFirstV2RunCountsTheV1PersonalBest`).

- [ ] **Step 6: Install the v2 jar next to the v1 jar**

```bash
cd /home/bfears/projects/RandomTargetParDrill
./gradlew installPlugin -PshootoffHome=/home/bfears/projects/ShootOFF -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain -q
ls /home/bfears/projects/ShootOFF/exercises
(cd /home/bfears/projects/ShootOFF && sha256sum -c build/owner-files.sha256)
```

Expected:
- `RandomTargetParDrill-v2.jar` and `RandomTargetParDrill.jar`
- `RandomTargetParDrill-bests.properties: OK` and `exercises/RandomTargetParDrill.jar: OK`

`exercises/` is git-ignored in ShootOFF, so nothing there is committed.

- [ ] **Step 7: Commit on `plugin-api-v2`**

```bash
cd /home/bfears/projects/RandomTargetParDrill
git branch --show-current
git add src/main/java/com/shootoff/plugins/BestsFile.java src/main/java/com/shootoff/plugins/RandomTargetParDrill.java \
  src/test/java/com/shootoff/plugins/TestBestsFile.java src/test/java/com/shootoff/plugins/TestRandomTargetParDrill.java
git status --short
git commit -m "Keep personal bests in the exercise's data folder, copying the v1 drill's on the first run"
git log -1 --format=%B
git log --oneline master..plugin-api-v2
```

Expected: `plugin-api-v2`; only the four files staged; no trailer; two commits ahead of `master`.

---
### Task 10: v1 compatibility check and hardware check with the owner

Runs in ShootOFF on `compose-ui`. **Files:** none. If a check fails, stop and debug with superpowers:systematic-debugging before changing code. The fix belongs in the task that owns the code, as a new commit in that task's repository and branch.

- [ ] **Step 1: Check the v1 members the installed drill uses**

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
check com.shootoff.gui.pane.ProjectorArenaPane getCanvasManager "()Lcom/shootoff/gui/CanvasManager;"
check com.shootoff.gui.LocatedImage com.shootoff.gui.LocatedImage "(Ljava/io/InputStream;Ljava/lang/String;)V"
check com.shootoff.plugins.ExerciseMetadata com.shootoff.plugins.ExerciseMetadata "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase com.shootoff.plugins.ProjectorTrainingExerciseBase "()V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase com.shootoff.plugins.ProjectorTrainingExerciseBase "(Ljava/util/List;)V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase addTarget "(Ljava/io/File;DD)Ljava/util/Optional;"
check com.shootoff.plugins.ProjectorTrainingExerciseBase destroy "()V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase getArenaWidth "()D"
check com.shootoff.plugins.ProjectorTrainingExerciseBase getArenaHeight "()D"
check com.shootoff.plugins.ProjectorTrainingExerciseBase setCourse "(Ljava/io/File;)Ljava/util/List;"
check com.shootoff.plugins.TrainingExerciseBase clearShots "()V"
check com.shootoff.plugins.TrainingExerciseBase pauseShotDetection "(Z)V"
check com.shootoff.plugins.TrainingExerciseBase playSound "(Ljava/io/File;)V"
check com.shootoff.plugins.TrainingExerciseBase playSound "(Ljava/io/InputStream;)V"
check com.shootoff.plugins.TrainingExerciseBase getParInterval "(Lcom/shootoff/gui/ParListener;)V"
check com.shootoff.plugins.TrainingExerciseBase getDelayedStartInterval "(Lcom/shootoff/gui/DelayedStartListener;)V"
check com.shootoff.plugins.TrainingExerciseBase addExercisePane "(Ljavafx/scene/layout/Pane;)V"
check com.shootoff.plugins.TrainingExerciseBase addShootOFFButton "(Ljava/lang/String;Ljavafx/event/EventHandler;)Ljavafx/scene/control/Button;"
check com.shootoff.plugins.TrainingExerciseBase addShotTimerColumn "(Ljava/lang/String;I)V"
check com.shootoff.plugins.TrainingExerciseBase setShotTimerColumnText "(Ljava/lang/String;Ljava/lang/String;)V"
check com.shootoff.plugins.TrainingExerciseBase setShotTimerRowColor "(Ljavafx/scene/paint/Color;)V"
check com.shootoff.plugins.TrainingExerciseBase showTextOnFeed "(Ljava/lang/String;)V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase showTextOnFeed "(Ljava/lang/String;IILjavafx/scene/paint/Color;Ljavafx/scene/paint/Color;Ljavafx/scene/text/Font;)V"
check com.shootoff.plugins.ProjectorTrainingExerciseBase setArenaBackground "(Lcom/shootoff/gui/LocatedImage;)V"
check com.shootoff.plugins.TrainingExercise shotListener "(Lcom/shootoff/camera/Shot;Ljava/util/Optional;)V"
check com.shootoff.plugins.TrainingExercise targetUpdate "(Lcom/shootoff/targets/Target;Lcom/shootoff/plugins/TrainingExercise\$TargetChange;)V"
check com.shootoff.plugins.TrainingExercise reset "(Ljava/util/List;)V"
echo "missing=$missing"
```

Expected: no `MISSING` line, and `missing=0`.

- [ ] **Step 2: Launch**

Run `./gradlew run --args="-d" --console=plain > build/plan3-run.log 2>&1` in the background. With the webcam and projector attached, the owner checks:

1. **Calibration.** Open the projector arena and calibrate (auto-calibration, then a manual adjustment if needed). The arena is calibrated as before.
2. **The Training menu.**
   - Projector Exercises lists "Random Target PAR Drill with Score" **once**. It is the v2 jar: when it starts, the PAR field shows 4.0 (the v1 drill's showed 2.0), and "Shots per round" is a spinner.
   - The ten built-ins are listed in the same order as before.
3. **The v2 drill.** Start it and play at least three rounds:
   - black background, and the target hidden until a round starts
   - "make ready" voice, then a beep, and the target somewhere new each round
   - the par/delay controls show PAR 4.0 and Min 5 / Max 8, and "Shots per round" shows 10
   - a hit fills Length and Score in the shot timer and raises "Score: N" on the arena and the camera feed; the time appears bottom left
   - a round without a shot shows "Par missed!" and sounds the buzzer at the par time
   - Pause shows "Resume" and stops the rounds; Resume says "make ready" 5 s later and carries on; Clear Shots clears the markers and the shot timer
4. **The summary.** Set "Shots per round" to 3 and restart the drill (pick None, then the drill), or play a full 10 rounds at the default 4.0 s par:
   - the text shows the hit factor
   - at 10 rounds and 4.00 s par, the text shows "NN% of personal best (6.59)" or "New personal best!": the migrated v1 best (ruling 12)
   - the statistics follow
   - the target stands in the middle of the arena, with every shot replayed where it hit
5. **Shoot to reset.** Four seconds after the summary, a shot restarts the drill.
6. **Stopping.** Pick "None" in the Training menu. Every trace of the drill is gone: the buttons, the spinner, the par/delay controls, the Length/Score columns, the texts, the target and the markers. The arena background is back to what it was before.
7. **Calibration while the drill runs.** Start the drill, then press Calibrate. The drill stops during calibration, and a fresh drill starts after it.
8. **The v1 path:**
   - **Shoot Don't Shoot** on the arena plays as before.
   - **The v1 drill:** quit ShootOFF, then `mv exercises/RandomTargetParDrill-v2.jar build/`. Relaunch and play two rounds of the v1 drill. Quit, then `mv build/RandomTargetParDrill-v2.jar exercises/`.
   - **An animated target:** Duel Tree or Bouncing Targets plays as before.
9. **Session record and replay.** This wasn't checked in Plan 2.
   - Press **Record Session**. Add IPSC from the Targets menu to the arena, move and resize it, and fire hits and misses.
   - Start the v2 drill and play two rounds, then press **Stop Recording**.
   - Open **View Sessions** and replay the new session:
     - the IPSC target is added, moved and resized as it was
     - shot markers appear in their colors
     - the drill's "Score: N" messages appear as exercise feed messages
   - The drill's own target comes from its jar, which the session viewer can't load yet (Plan 2, ruling 16), so only its shots show.
   - Also open one older session file from `sessions/` (recorded before Plan 2): it loads and replays.

- [ ] **Step 3: Check the log**

```bash
command grep -nE "Exception|NoClassDefFoundError|NoSuchMethodError|AbstractMethodError|Can't (load|find|read)" build/plan3-run.log
command grep -n "older or same version duplicate" build/plan3-run.log
```

Expected:
- The first command prints nothing. A `NoSuchMethodError` or `AbstractMethodError` naming a v1 member means the v1 surface broke: report it; don't paper over it.
- The second command is informational: it names the v1 jar if debug logging reached the log file, and prints nothing otherwise.

- [ ] **Step 4: The owner's files**

```bash
cd /home/bfears/projects/ShootOFF
sha256sum -c build/owner-files.sha256
cat exercise-data/com.shootoff.plugins.RandomTargetParDrill/RandomTargetParDrill-bests.properties
git status --short
```

Expected:
- `RandomTargetParDrill-bests.properties: OK` and `exercises/RandomTargetParDrill.jar: OK`
- the v2 bests file, holding the migrated `10rounds-4.00spar` line plus any new keys
- `git status --short` doesn't list `exercise-data/` (it is ignored)

- [ ] **Step 5: Installed distribution**

```bash
./gradlew installDist --console=plain
(cd /tmp && /home/bfears/projects/ShootOFF/javafx-app/build/install/shootoff/bin/shootoff)
```

The window opens and the Training menu lists the built-ins. The install folder has no `exercises/` jars, so no plugin is listed. The owner closes it.

Things this Linux machine can't exercise: the PS3 Eye camera, and Windows paths in `@` resources. Note them as unchecked in the report.

---

## Spec coverage

| Spec | Where |
|---|---|
| §1 criterion 1: `core` and `plugin-api` without JavaFX, enforced by a test | Task 2 (`TestNoJavaFxInPluginApi`, and the scanner shared with `TestNoJavaFxInCore`); Task 3 (test fixtures) |
| §1 criterion 3: all tests pass, plus the §8 tests | The gate in every ShootOFF task; the drill's tests in Tasks 8–9 |
| §1 criterion 4: the drill on `plugin-api` loads and plays with its current behavior | Tasks 8–9; owner check Task 10 Step 2 (items 3–5) |
| §1 criterion 5: session files load and replay | Task 10 Step 2, item 9 (the session fixtures stay covered by Plan 2's tests in the gate) |
| §1 criterion 6: v1 plugins still load and run | Task 4 (`LegacyExerciseLoader`, `TestPluginLoading.v1JarLoadsThroughTheLegacyPath`, Step 10 with the installed jar); Task 10 Steps 1–2 |
| §2 publishing: `core`, `plugin-api` and its test-fixtures variant, and the app for old plugins | Task 7 (coordinates per ruling 15) |
| §4 `Exercise` | Task 2 (`Hit` per ruling 1) |
| §4 `ExerciseHost` table | Task 2, with the additions of rulings 6–7 |
| §4 threading: one exercise thread, host methods from any thread, `stop()` cancels and removes | Task 2 (`ExerciseExecutor`); Task 6 (`JavaFxExerciseHost`, tests `callbacksArriveOnOneExerciseThread` and `stopRemovesEverythingAndCancelsScheduledTasks`) |
| §4 loading: `apiVersion="2"`, v2 in the engine, v1 to the legacy loader, descriptor from the jar only | Task 4 (ruling 3) |
| §4 `FakeExerciseHost`: records targets, texts, buttons, columns, sounds and tasks; manual clock; injected shots and hits | Task 3 |
| §5 exercise hosting: `JavaFxExerciseHost` for a camera canvas and the arena; buttons, settings and columns in the existing panes; v1 and v2 together in the Training menu | Tasks 5–6 |
| §5 the legacy exercise API stays in javafx-app | Task 4 (the legacy loader and the built-ins as entries); Task 5 (`TimingControlsPane` shared, unchanged behavior) |
| §6 the drill: `Exercise` only, scoring reused, bests in `dataDirectory()` migrated from the ShootOFF home, number setting, buttons, texts, replay with `showShotMarker`, `host.schedule`, `apiVersion="2"`, `plugin-api` dependency | Tasks 8–9 |
| §6 the drill's `FakeExerciseHost` tests: 10 rounds with hits, a par miss, a miss, pause and resume, the summary with hit factor and personal-best line | Task 8 (`TestRandomTargetParDrill`, 5 tests); Task 9 (the first v2 run against the v1 best) |
| §7 Plan 3 step 1: `plugin-api` | Tasks 2–3 |
| §7 Plan 3 step 2: engine to `core`, built-ins from the app, v2 loading, v1 loading in javafx-app | Task 4 |
| §7 Plan 3 step 3: `JavaFxExerciseHost` and the boundary test | Tasks 5–6; the boundary test in Task 2 (ruling 19) |
| §7 Plan 3 step 4: publishing | Task 7 |
| §7 Plan 3 step 5: the drill port on `plugin-api-v2` | Tasks 8–9 |
| §7 Plan 3 step 6: owner hardware check | Task 10 |
| §8 `JavaFxExerciseHost` tests: targets, texts, buttons, columns, sounds; one exercise thread; `stop()` | Task 6, `TestJavaFxExerciseHost` (11 tests) |
| §8 plugin loading: v2 jar built in the test, v1 jar through the legacy path, classpath descriptor ignored | Task 4, `TestPluginLoading`, `TestPluginDescriptor` |
| §8 owner check list, plus a session recorded and replayed | Task 10 Step 2 |
| Plan 1 deferred: the app's POM hides `core` | Task 7 Steps 1–2, 4 |
| Owner files break the bundled-file tests | Task 1 |
