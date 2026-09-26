# Core Split and UI-Neutral Exercise API (Sub-project 1a) — Design

- **Date:** 2026-09-26
- **Status:** Draft, awaiting review
- **Branch:** `compose-ui` (long-lived; merged only if the owner decides to keep the Compose direction)

## 1. Context and intent

The owner wants to replace ShootOFF's JavaFX UI with **Compose Multiplatform (Desktop)**, and eventually run **all exercises** on it. The work stays on a branch until the owner decides whether they like it.

To decide, the owner wants to see **one exercise running end to end in Compose**: their RandomTargetParDrill, a projector exercise. The owner chose:

- a **UI-neutral exercise API**, so exercises never touch a UI toolkit
- a **full multi-module restructure**

JavaFX is currently woven into ShootOFF's core:

- Targets are built as JavaFX shapes, and hit-testing uses them.
- Shot markers are JavaFX `Ellipse`s (`DisplayShot`).
- Exercises draw with JavaFX.
- Camera management, calibration and sessions reference JavaFX types.

### Overall roadmap

| # | Sub-project | Outcome |
|---|---|---|
| **1a** | **Core split and exercise API (this document)** | Modules `core`, `plugin-api`, `javafx-app`; UI-neutral target model and exercise API; the JavaFX app works exactly as before; RandomTargetParDrill ported to the new API |
| 1b | Compose trial | `compose-app`: range screen, projector arena, calibration, exercise host; the drill runs end to end in Compose; the owner decides |
| 2 | Port the other exercises | The ten built-in exercises on the new API |
| 3 | Remaining screens in Compose | Targets, preferences, session viewer, courses, and so on |
| 4 | Retire JavaFX | Remove `javafx-app` and the legacy exercise API |

### Success criteria for 1a

1. The build has modules `core`, `plugin-api` and `javafx-app`. `core` and `plugin-api` have no JavaFX dependency, and a test enforces it.
2. `./gradlew run` launches the JavaFX app, and it behaves as today.
3. The full test suite passes: all existing tests, relocated into their modules, plus the new tests in §8.
4. RandomTargetParDrill, rewritten against `plugin-api` in its own repository, loads and plays in the JavaFX app with its current behavior: random placement, cues and sounds, par timing, the hit-factor summary with personal bests, and the shot replay on the summary target.
5. Existing session files (XML and JSON) still load and replay. Session file formats are unchanged.
6. Old-style plugins built on `TrainingExerciseBase` / `ProjectorTrainingExerciseBase` still load and run in the JavaFX app.

## 2. Modules and build

| Module | Language | Contents | Depends on |
|---|---|---|---|
| `core` | Java | Camera and OpenCV, shot detection, recorders, sessions and session I/O, configuration, courses, plugin loading, calibration and perspective math, **target model and hit-testing**, core geometry types | — (no UI) |
| `plugin-api` | Java | Exercise API: `Exercise`, `ExerciseHost` and handles, `ExerciseMetadata`, `FakeExerciseHost` (in the Gradle `java-test-fixtures` source set, published as `plugin-api`'s test-fixtures variant so plugin builds can use it with `testImplementation(testFixtures("com.shootoff:plugin-api:…"))`) | `core` |
| `javafx-app` | Java | Everything under today's `gui/**`, FXML, `Main`/`Launcher`, `TargetView` and `DisplayShot` markers, the **legacy** exercise base classes and the ten built-in exercises, `JavaFxExerciseHost` | `core`, `plugin-api` |
| `compose-app` | Kotlin | Sub-project 1b; not created in 1a | `core`, `plugin-api` |

**Build layout.**
- One `settings.gradle.kts` includes the modules. Each module has its own `build.gradle.kts`, and the shared `gradle/libs.versions.toml` stays.
- The root keeps the conveniences:
  - `./gradlew run` delegates to `:javafx-app:run`.
  - `./gradlew test` runs every module's tests.
  - `installDist` builds the JavaFX distribution.
- Runtime resource directories (`targets/`, `sounds/`, `courses/`, `exercises/`) stay at the repository root. `javafx-app`'s `run` uses the repository root as its working directory, as today.

**Dependencies by module.**
- `core`: OpenCV/JavaCV/FFmpeg, webcam-capture, MaryTTS, Gson, OSHI, logback/slf4j, commons-cli.
- `plugin-api`: slf4j-api only, beyond `core`.
- `javafx-app`: OpenJFX.

**Publishing.** `maven-publish` publishes `com.shootoff:core` and `com.shootoff:plugin-api` (version `5.0.0-SNAPSHOT`) to `mavenLocal` for plugin builds. Old-style plugins compile against `com.shootoff:javafx-app`, which is also published so they keep building.

**Boundary.** Separate modules make JavaFX unavailable at compile time for `core` and `plugin-api`. In addition, a test asserts that no class in either module references `javafx.*`, checked by scanning the compiled class files.

## 3. Target model and hit-testing (`core`)

The model is parsed from the existing `.target` XML format, which is unchanged:

- `<target defaultPerceivedWidth defaultPerceivedHeight defaultDistance>`
- `<ellipse centerX centerY radiusX radiusY fill>`
- `<rectangle x y width height fill>`
- `<polygon fill>` with `<point x y>` children
- `<image x y file>`
- nested `<tag name value/>` elements
- the `fillCanvas` attribute where used

Types:

- **`TargetDefinition`** (immutable): the source file, default perceived size and distance, and an ordered list of `Region`s. Order is paint order; later regions are on top.
- **`Region`**: sealed hierarchy `EllipseRegion`, `RectangleRegion`, `PolygonRegion`, `ImageRegion`, each with its geometry in target coordinates, fill color (`#RRGGBB`/named color string as in the file), tags (`Map<String,String>`), and index within the target. `ImageRegion` carries its image path and, for animated images, frame data loaded by the UI side; the core keeps only the path.
- **`TargetDefinitions.load(Path)`** / **`load(InputStream, ResourceResolver)`**: the parser. Parse errors return a descriptive failure instead of an empty target.
- **`PlacedTarget`**: a definition plus a stable `TargetId`, position, size (scale derived from the definition's natural bounds), visibility, and per-region visibility (for the `visible` tag and animation state). It is mutated only through its owner, a **`TargetSet`** per canvas: an ordered collection with add/remove/move/resize/visibility, and listeners the UI subscribes to.
- **`HitTester.hit(TargetSet, double x, double y) → Optional<Hit>`**:
  - Checks visible targets, topmost first.
  - Within a target, checks visible regions topmost first.
  - Geometry is exact for ellipse, rectangle and polygon. Images use the axis-aligned bounds of the image in target coordinates, matching today's JavaFX behavior for `ImageView`.
  - `Hit` = `TargetId`, `Region`, and the impact point in target coordinates.
- **Animation commands** (`command` tag with `animate`/`reverse`) remain data on `Region`. `core` exposes them, and each UI plays them.
- **Core geometry types:** `Point`, `Size`, `Rect` (replacing JavaFX `Point2D`, `Dimension2D`, `Bounds` in core APIs).

## 4. Exercise API (`plugin-api`)

```java
public interface Exercise {
	ExerciseMetadata metadata();
	void start(ExerciseHost host);
	void onShot(Shot shot, Optional<Hit> hit);
	default void onTargetsChanged(List<TargetHandle> targets) {}
	void onReset();
	void stop();
}
```

`ExerciseHost`:

| Area | Methods |
|---|---|
| Placement surface | `Size surfaceSize()`; `boolean isProjector()`; `setBackground(String imageResource)` (projector only) |
| Targets | `TargetHandle addTarget(String targetFile, double x, double y)` (resolved against the exercise jar first, then the ShootOFF `targets/` folder); `List<TargetHandle> targets()`. `TargetHandle`: `id()`, `move(x,y)`, `resize(w,h)`, `setVisible(boolean)`, `remove()`, `position()`, `size()`, `definition()` |
| Text | `TextHandle showText(String text, double x, double y, TextStyle style)` (`TextStyle`: font size, text color, background color); `TextHandle.setText`, `move`, `remove`; `showMessage(String)` for the standard feed banner |
| Controls | `ButtonHandle addButton(String label, Runnable onClick)` (`setLabel`, `remove`); `addNumberSetting(String label, double initial, double min, double max, double step, DoubleConsumer onChange)` |
| Shot timer | `addColumn(String name)`; `setColumnValue(String name, String value)` for the latest row; `styleLastRow(RowStyle)` (`RowStyle`: highlight color) |
| Shots | `showShotMarker(double x, double y, ShotStyle style)` (for summaries/replays); `clearShots()`; `pauseShotDetection(boolean)` |
| Sound | `playSound(String resourceOrFile)`; `playSounds(List<String>)`; `say(String text)` (TTS) |
| Time | `Cancellable schedule(Runnable task, Duration delay)`; `Cancellable scheduleRepeating(Runnable task, Duration initialDelay, Duration period)` |
| Resources | `Optional<InputStream> resource(String path)` (from the exercise's jar); `Path dataDirectory()` (a writable folder per exercise, e.g. for personal bests) |
| Settings shared with the app | Par time and delayed start come from the app's existing controls: `double parTime()`, `onParTimeChanged(DoubleConsumer)`, `DelayRange delayedStart()` (min/max seconds), `onDelayedStartChanged(Consumer<DelayRange>)`. The controls are shown only while an exercise has registered a listener for them |

**Threading.**
- Host methods may be called from any thread; each UI marshals onto its own UI thread.
- The host delivers every exercise callback (`start`, `onShot`, `onReset`, `stop`, scheduled tasks, button and setting callbacks) on **one exercise thread per running exercise**, so exercises need no locking.
- `stop()` cancels all scheduled tasks and removes everything the exercise added.

**Loading.**
- `shootoff.xml` gains `apiVersion="2"` and `exerciseClass`.
- The plugin loader in `core` instantiates v2 exercises (which must implement `Exercise`).
- Jars without `apiVersion` are v1. They are handed to `javafx-app`'s legacy loader, which runs them via `TrainingExerciseBase` / `ProjectorTrainingExerciseBase` as today.
- The loader keeps reading the descriptor from the jar only (`findResource`).

**`FakeExerciseHost`.** An in-memory implementation for tests. It records targets, texts, buttons, column values, sounds and scheduled tasks, and offers a manual clock (`advance(Duration)`) and a way to inject shots and hits.

## 5. JavaFX app on the new core (`javafx-app`)

- **Target rendering.**
  - `TargetView` is built from a `PlacedTarget` + `TargetDefinition`: one JavaFX shape or `ImageView` per region.
  - It keeps today's interactions (select, drag, resize, keyboard, animations), and changes to position, size and visibility go through the `TargetSet`.
  - The XML-to-JavaFX building in today's `TargetIO`/`XMLTargetReader` is replaced by rendering from the model.
- **Hits** come from `HitTester`, not JavaFX `contains`.
- **Shots.** `core` emits `Shot` data. `DisplayShot` markers stay a `javafx-app` concern.
- **`CameraManager` → UI.** Replaced by a `CameraListener` interface in `core` (frame available, shot detected, feed resolution, errors, brightness and FPS warnings). `CanvasManager`/`ShootOFFController` implement it and marshal with `Platform.runLater`.
- **Configuration.** Anything JavaFX in `config` moves to `javafx-app`: e.g. stage positions, and `ObservableList` usages replaced with plain collections plus listeners.
- **Calibration and perspective.** The math in `AutoCalibrationManager` / `PerspectiveManager` uses core geometry types. The JavaFX calibration overlay (`CalibrationManager`) stays in `javafx-app`.
- **Sessions.**
  - Events store core `Shot` data and target references.
  - `SessionRecorder` writes the same XML/JSON as today, converting `TargetId`s to the current index at record time.
  - `SessionCanvasManager` stays in `javafx-app`.
- **Exercise hosting.**
  - `JavaFxExerciseHost` implements `ExerciseHost` for a camera canvas (`isProjector() == false`) and for the projector arena (`true`).
  - Buttons, settings and columns go into the existing exercise pane and shot-timer table.
  - The Training menu lists v1 and v2 exercises together.
- **Legacy exercise API.** `TrainingExerciseBase` and `ProjectorTrainingExerciseBase` stay in `javafx-app`, adapted to the new target model where needed, so the ten built-in exercises and third-party v1 plugins keep working until sub-project 2.

## 6. RandomTargetParDrill on the new API (separate repository)

- Implements `Exercise`, using only `ExerciseHost`.
- Scoring (`HitFactor`, `HitFactorSummary`) and `PersonalBests` are reused unchanged. The bests file path comes from `host.dataDirectory()`, and on first run it migrates an existing `RandomTargetParDrill-bests.properties` from the ShootOFF home.
- The round-limit field becomes `addNumberSetting`. Pause/resume and "Clear Shots" become `addButton`. The score, round and time labels become `showText`. The summary replay uses `showShotMarker` in target-relative positions. Executors become `host.schedule`.
- `shootoff.xml` declares `apiVersion="2"`. The build depends on `com.shootoff:plugin-api`.
- **New unit tests with `FakeExerciseHost`:**
  - a full 10-round drill with injected hits
  - a par miss
  - a miss
  - pause and resume
  - the summary content, including the hit factor and the personal-best line

## 7. Migration order

Revised 2026-09-26 after a full inventory of JavaFX coupling. What changed:

- `Configuration` holds runtime state that depends on JavaFX (the current exercise, the session recorder, open video-player windows, `Alert` dialogs).
- Sessions, courses and the plugin engine depend on the JavaFX `Target`.
- Shot detection creates JavaFX shot markers (`DisplayShot` holds an `Ellipse`).

Work happens in three plans. Each plan leaves the build and the full test suite green after every step, and each step is committed separately.

**Plan 1: modules, and the camera pipeline into `core`.**

1. Module skeletons: `settings.gradle.kts`, per-module builds, the dependency split, and root `run`/`test`/`installDist` wiring. All code starts in `javafx-app`, which is effectively today's project moved. `core` and `plugin-api` start empty.
2. Neutral types in `core`:
   - geometry: `Point`, `Size`, `Rect`
   - `ShotColor`-based colors, replacing `javafx.scene.paint.Color` in shot and config logic
   - `UserNotifier`, replacing `Alert` in non-UI code
   - a sound player, replacing the `TrainingExerciseBase.playSound` calls from shot processors
3. Split `Configuration`:
   - A core **`Settings`** holds the persisted preferences and command-line options. It has no JavaFX and no references to exercises, sessions or UI. `Main.forceClose` becomes `System.exit`, and `CalibrationOption` moves out of `gui`.
   - The runtime state stays in `javafx-app`: the current exercise, session recorder, recording managers, video players and dialogs.
4. Decouple the camera pipeline:
   - Shot detection emits plain `Shot` data; `DisplayShot`/`ArenaShot` markers are created in `javafx-app`.
   - `CameraView` loses its JavaFX types (`Node`, `Label`, `Color`), and diagnostic messages return a handle interface.
   - `CameraManager` returns `BufferedImage`, not a JavaFX `Image`.
   - The PS3 Eye settings window moves to `javafx-app`.
   - Calibration and perspective use core geometry.
5. Move into `core`: `util`, `Closeable`/`ObservableCloseable`, the camera packages (`camera`, `cameratypes`, `shotdetection`, `autocalibration`, `perspective`, `recorders`, `processors`, `video`, and the data-only shot classes) and `Settings`, along with the tests that don't need GUI test fixtures.

**Plan 2: target model, then sessions and courses into `core`.**

1. Target model, parser and `HitTester` in `core`, plus the parity test (§8).
2. Switch `javafx-app` rendering and hit-testing to the model, and delete the JavaFX target-building code.
3. Sessions (`session`, `session.io`) are expressed in the model (target ids, `Shot` data) and move to `core`.
4. Courses get a data model (targets and a background reference instead of a live `ProjectorArenaPane`), and `courses`/`courses.io` move to `core`. Applying a course to the arena stays in `javafx-app`.

**Plan 3: plugin API, engine, and the drill.**

1. `plugin-api`: `Exercise`, `ExerciseHost`, `FakeExerciseHost`.
2. The plugin engine moves to `core`. Built-in exercises are supplied by the app, not hard-coded. v2 loading moves to `core`, and v1 loading moves to `javafx-app`.
3. `JavaFxExerciseHost` and the boundary test.
4. Publishing: `core`, `plugin-api` and `javafx-app` to `mavenLocal`.
5. The RandomTargetParDrill port (§6) in its repository, on a `plugin-api-v2` branch.
6. Owner hardware check (§8).

**Tests during the migration.** Tests that need GUI test fixtures (`MockCanvasManager`, `TargetView`, `JavaFXThreadingRule`, the shot-detection video tests built on them, calibration tests) stay in `javafx-app`'s test tree even when the code they test has moved to `core`. They move once their fixtures have core equivalents. This isn't a regression: the test still runs against the same code.

## 8. Testing

- **Existing tests.** All of them keep passing (currently 203), moved to the module whose code they test, or kept in `javafx-app` while they depend on GUI test fixtures (§7). The baseline comparison (`scripts/test_summary.py`) is updated to read every module's test results.
- **Boundary test.** No compiled class in `core` or `plugin-api` references `javafx.*`.
- **Hit-testing parity.**
  - For every bundled `.target` file, over a grid of points across each target's bounds (and at several placements and scales), `HitTester` returns the same hit region as today's JavaFX implementation.
  - Written in `javafx-app` tests before the JavaFX hit path is removed, and kept as a regression test against a recorded expectation file afterwards.
- **Parser tests.** Every bundled `.target` file parses. Malformed input reports an error.
- **`JavaFxExerciseHost` tests.** Targets, texts, buttons, columns and sounds reach the JavaFX side. Callbacks arrive on one exercise thread. `stop()` removes everything and cancels scheduled tasks.
- **Plugin loading.** A v2 jar (built in the test) loads as an `Exercise`. A v1 jar still loads through the legacy path. A descriptor on the classpath is ignored.
- **Session compatibility.** The existing session fixtures (`legacy_session.json`, `arena_duplicate_targets.xml`) load and replay.
- **RandomTargetParDrill.** The `FakeExerciseHost` tests in §6.
- **Owner hardware check.** In the JavaFX app, with the projector and webcam, confirm:
  - calibration
  - the full drill: placement, cues and sounds, par timing, scoring
  - the summary with hit factor and personal best
  - the shot replay on the summary target
  - one built-in exercise (v1 path), for example Shoot Don't Shoot
  - recording and replaying a session

## 9. Risks

| Risk | Mitigation |
|---|---|
| Size of the refactor | Small green steps (§7), each committed; the JavaFX app works throughout |
| Hit-testing differences between the model and JavaFX | Parity test before the switch |
| Targets with animated images behave differently | Parity test covers image bounds; animations stay data played by the JavaFX `TargetView` as today; owner check includes an animated target exercise (e.g. Bouncing Targets or Duel Tree) |
| Third-party v1 plugins break | Legacy loader and base classes kept in `javafx-app`; v1 loading test |
| Plugins can't find `plugin-api` | Published to `mavenLocal`; the drill's build documents the dependency |
| Long-lived branch drifts from `master` | `master` changes are merged into `compose-ui` regularly; the branch is merged back only if Compose is kept |

## 10. Out of scope

- Any Compose code (sub-project 1b).
- Porting the ten built-in exercises to the new API (sub-project 2).
- Visual or UX changes to the JavaFX app.
- Changes to the `.target` or session file formats.
