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
| Shot timer | `addColumn(name)`, `setColumnValue(name, value)` (latest row), `styleLastRow(RowStyle)`, `addTimerRow(timeMillis, RowStyle)` (a row no shot made, e.g. a par miss) |
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
