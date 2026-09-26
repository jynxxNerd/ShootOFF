# Core Split Plan 1: Modules and the Camera Pipeline into Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the build into `core`, `plugin-api` and `javafx-app` modules, and move the camera pipeline (cameras, shot detection, calibration and perspective math, recorders, processors, video, `util`) plus the persisted settings into a `core` module that has no JavaFX. The JavaFX app must behave exactly as before.

**Architecture:**
- **Task 1: modules.** Today's project moves into `javafx-app` with `git mv src javafx-app/src`. `core` and `plugin-api` start empty. The root keeps `./gradlew run`, `test` and `installDist` working through Gradle's task-name selection: only `:javafx-app` has `run`/`installDist`, and every module has `test`. Runtime resources (`targets/`, `sounds/`, `courses/`, `exercises/`) stay at the repository root. `run` and every module's tests use the repository root as their working directory.
- **Tasks 2–9: decouple in place.** The coupled code stays in `javafx-app` while it's made neutral:
  - geometry, a sound player and a user notifier are added to `core`
  - shot colors become `ShotColor`
  - `Configuration` is split into a neutral `Settings` plus a `Configuration` subclass holding runtime state
  - JavaFX `Bounds`/`Dimension2D`/`Point2D` become `Rect`/`Size`/`Point`
  - `CameraView` loses its JavaFX types
  - shot detection emits `ScaledShot` data, and `DisplayShot` markers are made by the view
  - the PS3 Eye settings window moves out of the camera class
- **Task 10: move.** The now-neutral packages move into `core` with `git mv`, along with the tests that don't need GUI fixtures. A boundary test scans `core`'s compiled classes for `javafx`.
- **Task 11: owner check.** The owner checks the app by hand.

**Tech Stack:** Java 21 toolchain, Gradle 8.14 wrapper (Kotlin DSL, `gradle/libs.versions.toml`), OpenJFX 21 via `org.openjfx.javafxplugin` 0.1.0 (in `javafx-app` only), JavaCV/OpenCV/FFmpeg (linux-x86_64 classifiers), JUnit 5 + JUnit 4 (vintage).

**Spec:** `docs/superpowers/specs/2026-09-26-core-split-and-exercise-api-design.md` (this plan is §7 "Plan 1", steps 1–5; Plans 2 and 3 are out of scope).

## Global Constraints

- Work on branch `compose-ui` in `/home/bfears/projects/ShootOFF`. Never switch branches and never commit to `master`.
- No `Co-Authored-By` trailer in commit messages. Verify after every commit with `git log -1 --format=%B`.
- Never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- Use `git mv` for every move or rename so history follows. Never delete and re-create a moved file.
- Code style: tabs; match surrounding conventions. Every **new main-source** Java file starts with the GPL header, copied verbatim from lines 1–17 of `javafx-app/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file; it is at `src/...` before Task 1 and `core/...` after Task 10). New test files follow the repo's test style, which has no header.
- Package names stay `com.shootoff.*`. A package may be split across modules: the app runs on the classpath, not as JPMS modules.
- `core` and `plugin-api` must not depend on OpenJFX, and no compiled class in `core` may reference `javafx.*` (enforced by `TestNoJavaFxInCore` from Task 2).
- Unchanged formats: the `.target` format, the session formats (XML/JSON), and the `shootoff.properties` keys and value formats.
- Existing test classes and methods keep their package, class and method names, because the baseline compares tests by `class.method`. A test that moves modules keeps its name.
- No new third-party dependencies. The version catalog stays as it is.
- **Test gate (Task 1 onward):**

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  It must print `0 regressions; 0 new failures`. A full run takes several minutes, so use a Bash timeout of 600000 ms. The `N/M passing` count must equal the previous task's count plus the new test methods that task adds (stated in each task). A lower count means tests silently stopped running.
- Old-style (v1) plugins must keep loading and running. The installed `exercises/RandomTargetParDrill.jar` was compiled against the pre-split jar. Keep these source- and binary-compatible:
  - the public API of `TrainingExerciseBase` and `ProjectorTrainingExerciseBase`
  - the constructors and getters of `Shot`, `DisplayShot` and `ArenaShot`
  - `Configuration.getConfig()` and every `Configuration` getter/setter, which are inherited from `Settings`
  - `NamedThreadFactory`

  Two deliberate breaks, both unused by the drill and the built-in exercises:
  - `CameraView` loses `addChild`/`removeChild`/`addTarget`/`addDiagnosticMessage(String, Color)`/`removeDiagnosticMessage`.
  - `Shot.colorMap`/`Shot.getPaintColor()` move to `DisplayShot`.
- Publishing: `com.shootoff:core`, `com.shootoff:plugin-api` and `javafx-app` **as `com.shootoff:shootoff`** (the coordinate old-style plugins compile against), all `5.0.0-SNAPSHOT`. Verification publishes only to `build/m2` (`-Dmaven.repo.local`), never to the owner's `~/.m2`.

## Review Focus

1. **Display resolution different from the camera resolution.** Shot scaling moves from `DisplayShot` into the new core `ScaledShot`, and the marker is now made by the view. The marker must still land at the scaled position, with the configured radius. *Tests:* `TestShotDetector.detectedShotIsScaledToTheDisplay`, `TestCanvasManager.testDetectedShotBecomesMarkerAtItsDisplayPosition` and `TestShot.testDisplayShotFromScaledShotKeepsDisplayPosition` (Task 8).
2. **A shot on the edge of the calibrated projection area.** `Rect.contains` replaces JavaFX `Bounds.contains` for "only detect in projector bounds" and arena hand-off, so it must agree exactly on the edges and for zero-size or negative-size rectangles. Shots in bounds must be offset by the projection origin. *Tests:* `TestFxGeometry.rectContainsMatchesJavaFxBoundingBox` (Task 6) and `TestShotDetector.shotInsideProjectionIsOffsetByTheProjectionOrigin` (Task 8).
3. **An ignored laser color.** The color comparison changes from JavaFX `Color` to `ShotColor`. Shots of the ignored color must be dropped, and other colors must still reach the view. *Test:* `TestShotDetector.ignoredLaserColorIsDropped` (Task 8).
4. **An unwritable `shootoff.properties` or a malformed IP-camera URL.** Previously this showed a JavaFX `Alert`. Without a UI the problem must be reported through the notifier (logged by default), and the JavaFX app must still show an alert. *Tests:* `TestConfiguration.testUnwritableConfigNotifiesUser` and `testMalformedIpCamUrlNotifiesUser` (Task 3).
5. **An existing `shootoff.properties` written by today's app.** It covers every non-camera key, including arena position, calibrated-feed behavior and POI adjustment, and it must load into `Settings` with every value intact and round-trip unchanged. *Tests:* `TestSettings.readsEveryKeyOfAnExistingPropertiesFile` and `writtenSettingsReadBackUnchanged` (Task 5).

The owner's check (Task 11) covers the remaining risk no unit test reaches: the installed v1 `RandomTargetParDrill.jar` still loads and plays.

## Module and file map

| Where | What | Task |
|---|---|---|
| `settings.gradle.kts`, `build.gradle.kts` (root) | include the three modules; shared Java/repositories/test config in `subprojects {}` | 1 |
| `core/build.gradle.kts` | `java-library`, all non-UI libraries as `api`; `java-test-fixtures` from Task 10 | 1, 10 |
| `plugin-api/build.gradle.kts` | `java-library`, `api(project(":core"))`, empty until Plan 3 | 1 |
| `javafx-app/build.gradle.kts` | `application`, OpenJFX, `run`/`installDist`/start-script patch, publishes as `com.shootoff:shootoff` | 1 |
| `scripts/test_summary.py` | reads several result directories | 1 |
| `core/.../geom/{Point,Size,Rect}.java` | neutral geometry with JavaFX-style getters | 2 |
| `core/src/test/.../TestNoJavaFxInCore.java`, `JavaFxReferenceScanner.java` | boundary test | 2 |
| `core/.../sound/SoundPlayer.java` | sound playback moved out of `TrainingExerciseBase` | 3 |
| `core/.../util/UserNotifier.java`, `javafx-app/.../gui/AlertUserNotifier.java` | replaces `Alert` in configuration code | 3 |
| `javafx-app/.../config/Settings.java` (renamed from `Configuration.java`), new `Configuration.java` | persisted settings vs runtime state | 5 (Settings → `core` in 10) |
| `javafx-app/.../gui/FxGeometry.java` | JavaFX ↔ core geometry at the UI boundary | 6 |
| `core/.../camera/DiagnosticMessage.java` | handle for a shown diagnostic message | 7 |
| `javafx-app/.../camera/shot/ScaledShot.java` | shot data emitted by detection | 8 (→ `core` in 10) |
| `javafx-app/.../gui/PS3EyeSettingsWindow.java` | PS3 Eye settings UI | 9 |
| `core/src/testFixtures/.../camera/{MockCamera,MockCameraManager,VideoFinishedListener}.java` | shared camera test fixtures | 10 |

After Task 10:
- **`javafx-app` keeps** `camera/shot/DisplayShot.java`, `camera/shot/ArenaShot.java`, `config/Configuration.java`, and everything under `gui`, `plugins`, `session`, `courses` and `targets`, plus `Main` and `Launcher`.
- **`core` holds** `Closeable`, `ObservableCloseable`, `camera/**` (except the two marker classes), `config/{Settings,ConfigurationException,CalibrationOption}`, `util/**`, `geom/**` and `sound/**`.

---

### Task 1: Module skeletons; all code moves into `javafx-app`

**Files:**
- Move: `src/` → `javafx-app/src/` (`git mv`)
- Modify: `settings.gradle.kts`, `build.gradle.kts` (both rewritten)
- Create: `core/build.gradle.kts`, `plugin-api/build.gradle.kts`, `javafx-app/build.gradle.kts`
- Modify: `scripts/test_summary.py`
- Modify (test paths that are relative to the module, not the repo root):
  - `javafx-app/src/test/java/com/shootoff/session/io/TestSessionIO.java:40`
  - `javafx-app/src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java:48`
  - `javafx-app/src/test/java/com/shootoff/plugins/engine/TestPluginEngine.java:19`
  - `javafx-app/src/test/java/com/shootoff/plugins/engine/TestPlugin.java:26`

**Interfaces:**
- Produces:
  - Gradle projects `:core` (`java-library`, `maven-publish`), `:plugin-api` (`java-library`, `maven-publish`, `api(project(":core"))`) and `:javafx-app` (`application`, OpenJFX, `implementation(project(":core"))`, `implementation(project(":plugin-api"))`).
  - Every module's tests run with the repository root as their working directory.
  - `scripts/test_summary.py compare RESULTS_DIR... BASELINE` and `summarize RESULTS_DIR...` accept several directories.
  - The gate command in Global Constraints.
  - `./gradlew installDist` output moves from `build/install/shootoff/` to `javafx-app/build/install/shootoff/`.

- [ ] **Step 1: Record the pre-split test count**

Run the old gate on the untouched tree (timeout 600000 ms):

```bash
cd /home/bfears/projects/ShootOFF
./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt | tail -1 | tee build/gate-before.txt
```

Expected: `N/M passing; 0 regressions; 0 new failures`, with 203 passing today. Every later gate in this task must show the same `N/M`.

- [ ] **Step 2: Let `test_summary.py` read several result directories**

In `scripts/test_summary.py`, replace the docstring's three usage lines:

```
  summarize RESULTS_DIR            print "PASS|FAIL|SKIP class.method" lines
  combine FILE...                  merge summaries; PASS only if PASS in every file
  compare RESULTS_DIR BASELINE     exit 1 if any baseline PASS test no longer passes
```

with

```
  summarize RESULTS_DIR...          print "PASS|FAIL|SKIP class.method" lines
  combine FILE...                   merge summaries; PASS only if PASS in every file
  compare RESULTS_DIR... BASELINE   exit 1 if any baseline PASS test no longer passes

RESULTS_DIR may be given several times (one per Gradle module, e.g. */build/test-results/test).
Directories that don't exist are skipped; a test reported FAIL in any directory counts as FAIL.
```

Replace the whole `collect` function with:

```python
def collect(results_dirs):
    outcomes = {}
    for results_dir in results_dirs:
        for path in glob.glob(os.path.join(results_dir, "**", "TEST-*.xml"), recursive=True):
            for case in ET.parse(path).getroot().iter("testcase"):
                name = f"{case.get('classname')}.{case.get('name')}"
                if case.find("skipped") is not None:
                    outcome = "SKIP"
                elif case.find("failure") is not None or case.find("error") is not None:
                    outcome = "FAIL"
                else:
                    outcome = "PASS"
                if outcomes.get(name) != "FAIL":
                    outcomes[name] = outcome
    if not outcomes:
        sys.exit(f"No test results found in {', '.join(results_dirs)}")
    return outcomes
```

In `main`, replace

```python
    if len(argv) >= 2 and argv[0] == "summarize":
        print_summary(collect(argv[1]))
```

with

```python
    if len(argv) >= 2 and argv[0] == "summarize":
        print_summary(collect(argv[1:]))
```

and replace

```python
    elif len(argv) == 3 and argv[0] == "compare":
        current = collect(argv[1])
        baseline = read_summary(argv[2])
```

with

```python
    elif len(argv) >= 3 and argv[0] == "compare":
        current = collect(argv[1:-1])
        baseline = read_summary(argv[-1])
```

Check it still works on the results from Step 1, which are still at the old location:

```bash
python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt | tail -1
python3 scripts/test_summary.py compare build/no-such-dir build/test-results/test docs/superpowers/baseline/java8-tests.txt | tail -1
```

Expected: both print the same line as `build/gate-before.txt`.

- [ ] **Step 3: Move the sources into `javafx-app`**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p javafx-app core plugin-api
git mv src javafx-app/src
rm -rf build/test-results build/reports
```

The last line removes the root's stale test results so nobody mistakes them for current ones. The gate's `*/build/test-results/test` glob never matches the root's `build/` anyway.

- [ ] **Step 4: Write the Gradle build files**

Replace `settings.gradle.kts` with:

```kotlin
rootProject.name = "shootoff"

include("core", "plugin-api", "javafx-app")
```

Replace `build.gradle.kts` with:

```kotlin
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.javafx) apply false
}

// Keep in sync with javafx-app/src/main/resources/version.properties
val shootoffVersion = "5.0.0-SNAPSHOT"
val catalog = libs

// There is no code in the root project. `./gradlew run`, `installDist` and `test` still work from
// here: Gradle runs a task name given on the command line in every module that has it, and only
// :javafx-app has run/installDist.
subprojects {
    apply(plugin = "java")

    group = "com.shootoff"
    version = shootoffVersion

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    repositories {
        mavenCentral()
        maven("https://raw.githubusercontent.com/DFKI-MLT/Maven-Repository/main/") {
            // de.dfki.lt.jtok:jtok-core (a MaryTTS dependency) is only published by DFKI
            content { includeGroupByRegex("de\\.dfki\\..*") }
        }
        maven("https://nexus.terrestris.de/repository/public/") {
            // gov.nist.math:Jampack (a MaryTTS dependency) is not on Maven Central
            content { includeModule("gov.nist.math", "Jampack") }
        }
        maven("https://nrgxnat.jfrog.io/artifactory/libs-release/") {
            // com.twmacinta:fast-md5 (a MaryTTS dependency)
            content { includeModule("com.twmacinta", "fast-md5") }
        }
    }

    configurations.all {
        // webcam-capture and MaryTTS pull in slf4j bindings that conflict with logback
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
    }

    dependencies {
        "compileOnly"(catalog.spotbugs.annotations)

        "testImplementation"(platform(catalog.junit.bom))
        "testImplementation"(catalog.junit.jupiter)
        "testImplementation"(catalog.junit4)
        "testImplementation"(catalog.hamcrest.core)
        "testRuntimeOnly"(catalog.junit.vintage.engine)
        "testRuntimeOnly"(catalog.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Tests resolve targets/, sounds/, courses/ and shootoff.properties against the working
        // directory, as the app does at runtime
        workingDir = rootDir
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
```

Create `core/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

// Native libraries are only bundled for this platform for now
val javacppPlatform = "linux-x86_64"

dependencies {
    // Everything is `api` because javafx-app still uses these libraries directly; later plans
    // narrow this once the code using them lives in core
    api(libs.slf4j.api)
    api(libs.logback.classic)

    // webcam-capture fetches bridj 0.6.2, which does not play nicely with
    // stackguard in newer JVMs
    api(libs.bridj)
    api(libs.webcam.capture)
    api(libs.webcam.capture.driver.ipcam)
    api(libs.jna)

    api(libs.commons.cli)
    api(libs.bundles.marytts)
    api(libs.oshi.core)
    api(libs.gson)

    api(libs.javacv) {
        // Only the OpenCV and FFmpeg presets are used
        listOf(
            "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense", "librealsense2",
            "videoinput", "artoolkitplus", "leptonica", "tesseract",
        ).forEach { exclude(group = "org.bytedeco", module = it) }
    }
    for (preset in listOf(libs.javacpp, libs.opencv, libs.ffmpeg, libs.openblas)) {
        api(preset)
        api(variantOf(preset) { classifier(javacppPlatform) })
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
```

Create `plugin-api/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

// The UI-neutral exercise API arrives in Plan 3; until then this module is empty
dependencies {
    api(project(":core"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
```

Create `javafx-app/build.gradle.kts`:

```kotlin
plugins {
    application
    `maven-publish`
    alias(libs.plugins.javafx)
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.fxml", "javafx.swing")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))

    // Also needed at compile time: JavaFXToolkitInitializer implements
    // TestExecutionListener to work around a GTK2/GTK3 native library
    // conflict between OpenCV and OpenJFX (see that class's Javadoc).
    testCompileOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "com.shootoff.Launcher"
    // Keeps the start script and install folder named "shootoff"
    applicationName = "shootoff"
}

tasks.named<JavaExec>("run") {
    // ShootOFF and its plugins resolve targets/, sounds/, courses/ and
    // exercises/ against the working directory
    workingDir = rootDir
}

distributions {
    main {
        contents {
            from(rootDir) {
                include("targets/**", "sounds/**", "courses/**", "shootoff.properties", "LICENSE",
                    "eyeCam32.dll", "eyeCam64.dll")
            }
        }
    }
}

tasks.startScripts {
    // Run from the install folder so relative resource paths resolve there
    // no matter where the script is launched from
    doLast {
        val unixExec = "exec \"\$JAVACMD\" \"\$@\""
        val unix = unixScript.readText()
        check(unixExec in unix) { "Unexpected Unix start script layout" }
        unixScript.writeText(unix.replace(unixExec, "cd \"\$APP_HOME\" || exit\n$unixExec"))

        val windowsExec = "@rem Execute "
        val windows = windowsScript.readText()
        check(windowsExec in windows) { "Unexpected Windows start script layout" }
        windowsScript.writeText(windows.replace(windowsExec, "cd /d \"%APP_HOME%\"\r\n\r\n$windowsExec"))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // Old-style plugins (e.g. RandomTargetParDrill) compile against this coordinate.
            // Plan 3 renames it to com.shootoff:javafx-app.
            artifactId = "shootoff"
            from(components["java"])
        }
    }
}
```

- [ ] **Step 5: Fix the four test paths that name the module's source folder**

The tests now run from the repository root, but these files live under `javafx-app/`.

In `javafx-app/src/test/java/com/shootoff/session/io/TestSessionIO.java`, replace

```java
	private static final File LEGACY_SESSION = new File("src/test/resources/sessions/legacy_session.json");
```

with

```java
	private static final File LEGACY_SESSION = new File("javafx-app/src/test/resources/sessions/legacy_session.json");
```

In `javafx-app/src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java`, replace `new File("src/test/resources/sessions/arena_duplicate_targets.xml")` with `new File("javafx-app/src/test/resources/sessions/arena_duplicate_targets.xml")`.

In `javafx-app/src/test/java/com/shootoff/plugins/engine/TestPluginEngine.java`, replace

```java
		pluginsPath = System.getProperty("user.dir") + File.separator + "src" + File.separator + "test" + File.separator
				+ "exercises";
```

with

```java
		pluginsPath = System.getProperty("user.dir") + File.separator + "javafx-app" + File.separator + "src"
				+ File.separator + "test" + File.separator + "exercises";
```

In `javafx-app/src/test/java/com/shootoff/plugins/engine/TestPlugin.java`, replace

```java
		pluginDir = Paths.get(System.getProperty("user.dir") + File.separator + "src" + File.separator + "test"
				+ File.separator + "exercises");
```

with

```java
		pluginDir = Paths.get(System.getProperty("user.dir") + File.separator + "javafx-app" + File.separator + "src"
				+ File.separator + "test" + File.separator + "exercises");
```

Check nothing else names `src/` relative to the working directory:

```bash
grep -rn '"src/\|"src"' javafx-app/src/test/java
```

Expected: only `TestPluginDescriptorIsolation.java:52`, which resolves against a temporary folder and is correct as is.

- [ ] **Step 6: Check the module wiring**

```bash
./gradlew projects --console=plain | grep -E "core|plugin-api|javafx-app"
./gradlew test run installDist --dry-run --console=plain | grep -E ":(core|plugin-api|javafx-app):(test|run|installDist) "
./gradlew :core:dependencies --configuration runtimeClasspath --console=plain | grep -ci openjfx
./gradlew :plugin-api:dependencies --configuration runtimeClasspath --console=plain | grep -ci openjfx
```

Expected:
- the three projects are listed
- the dry run lists `:core:test`, `:plugin-api:test`, `:javafx-app:test`, `:javafx-app:run` and `:javafx-app:installDist`, and no root `:run`
- both OpenJFX counts are `0`

- [ ] **Step 7: Run the gate**

Run the gate from Global Constraints. Expected: `0 regressions; 0 new failures` and the same `N/M passing` as `build/gate-before.txt`. Check that the results came from the module:

```bash
ls javafx-app/build/test-results/test | head -3
```

- [ ] **Step 8: Check `installDist` and a real launch**

```bash
./gradlew installDist --console=plain
ls javafx-app/build/install/shootoff/bin/shootoff javafx-app/build/install/shootoff/targets | head -5
grep -c 'cd "$APP_HOME" || exit' javafx-app/build/install/shootoff/bin/shootoff
timeout 60 ./gradlew run --console=plain > build/run-smoke.log 2>&1; grep -nE "Exception|Could not find or load main class" build/run-smoke.log
```

Expected:
- the start script and `targets/` exist
- the `cd` patch count is `1`
- the ShootOFF window opens and is killed after 60 s, and the last `grep` prints nothing

If there is no display, skip the `run` line and say so in the report.

- [ ] **Step 9: Check that the owner's plugin still compiles against the published coordinate**

This publishes to a scratch Maven repository under `build/`, not to `~/.m2`.

```bash
cd /home/bfears/projects/ShootOFF
./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain
ls build/m2/com/shootoff
rm -rf build/drill-check && rsync -a --exclude build --exclude .gradle /home/bfears/projects/RandomTargetParDrill/ build/drill-check/
(cd build/drill-check && ./gradlew compileJava -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain)
```

Expected:
- `build/m2/com/shootoff` lists `core`, `plugin-api` and `shootoff`
- the drill copy prints `BUILD SUCCESSFUL`. All classes are still in the `shootoff` jar, so the drill needs no change yet.

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts core/build.gradle.kts plugin-api/build.gradle.kts javafx-app/build.gradle.kts scripts/test_summary.py \
  javafx-app/src/test/java/com/shootoff/session/io/TestSessionIO.java \
  javafx-app/src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java \
  javafx-app/src/test/java/com/shootoff/plugins/engine/TestPluginEngine.java \
  javafx-app/src/test/java/com/shootoff/plugins/engine/TestPlugin.java
git status --short | grep -v '^R ' | head -20
git commit -m "Split the build into core, plugin-api and javafx-app modules"
git log -1 --format=%B
```

The `git mv` from Step 3 is already staged. The `git status` check must show ` M shootoff.properties` (unstaged, leading space) and nothing else unexpected.

---

### Task 2: Core geometry types and the no-JavaFX boundary test

**Files:**
- Create: `core/src/main/java/com/shootoff/geom/Point.java`, `Size.java`, `Rect.java`
- Create: `core/src/test/java/com/shootoff/geom/TestPoint.java`, `TestSize.java`, `TestRect.java`
- Create: `core/src/test/java/com/shootoff/JavaFxReferenceScanner.java`, `core/src/test/java/com/shootoff/TestNoJavaFxInCore.java`

**Interfaces:**
- Produces (package `com.shootoff.geom`, all immutable `final` classes with value `equals`/`hashCode`):
  - `Point(double x, double y)`: `double getX()`, `double getY()`. Replaces `javafx.geometry.Point2D`.
  - `Size(double width, double height)`: `double getWidth()`, `double getHeight()`. Replaces `javafx.geometry.Dimension2D`.
  - `Rect(double minX, double minY, double width, double height)`: `getMinX()`, `getMinY()`, `getWidth()`, `getHeight()`, `getMaxX()` (= minX + width), `getMaxY()`, `boolean isEmpty()` (width < 0 or height < 0), and `boolean contains(double x, double y)`. `contains` is inclusive on all edges and false when empty, exactly like JavaFX `BoundingBox.contains`. Replaces `javafx.geometry.Bounds`/`BoundingBox`.
  - JavaFX-style getter names are deliberate: later tasks swap the types without touching call sites.
- Produces (core tests): `JavaFxReferenceScanner.findJavaFxReferences(Path classesDir) → List<String>` (relative `.class` paths whose bytes contain `javafx/` or `javafx.`), and `TestNoJavaFxInCore`, which scans `core`'s compiled main classes.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/com/shootoff/geom/TestPoint.java`:

```java
package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TestPoint {
	@Test
	void exposesCoordinates() {
		final Point point = new Point(1.5, -2.0);

		assertEquals(1.5, point.getX());
		assertEquals(-2.0, point.getY());
	}

	@Test
	void pointsWithTheSameCoordinatesAreEqual() {
		assertEquals(new Point(3, 4), new Point(3, 4));
		assertEquals(new Point(3, 4).hashCode(), new Point(3, 4).hashCode());
		assertNotEquals(new Point(3, 4), new Point(4, 3));
	}
}
```

Create `core/src/test/java/com/shootoff/geom/TestSize.java`:

```java
package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TestSize {
	@Test
	void exposesWidthAndHeight() {
		final Size size = new Size(1280, 720);

		assertEquals(1280, size.getWidth());
		assertEquals(720, size.getHeight());
	}

	@Test
	void sizesWithTheSameDimensionsAreEqual() {
		assertEquals(new Size(640, 480), new Size(640, 480));
		assertEquals(new Size(640, 480).hashCode(), new Size(640, 480).hashCode());
		assertNotEquals(new Size(640, 480), new Size(480, 640));
	}
}
```

Create `core/src/test/java/com/shootoff/geom/TestRect.java`:

```java
package com.shootoff.geom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestRect {
	private final Rect rect = new Rect(10, 20, 30, 40);

	@Test
	void exposesJavaFxStyleBounds() {
		assertEquals(10, rect.getMinX());
		assertEquals(20, rect.getMinY());
		assertEquals(30, rect.getWidth());
		assertEquals(40, rect.getHeight());
		assertEquals(40, rect.getMaxX());
		assertEquals(60, rect.getMaxY());
	}

	@Test
	void containsInteriorPoint() {
		assertTrue(rect.contains(25, 40));
	}

	@Test
	void edgesAndCornersAreInside() {
		assertTrue(rect.contains(10, 20));
		assertTrue(rect.contains(40, 60));
		assertTrue(rect.contains(10, 60));
		assertTrue(rect.contains(40, 20));
	}

	@Test
	void pointsJustOutsideAreOutside() {
		assertFalse(rect.contains(9.999, 30));
		assertFalse(rect.contains(40.001, 30));
		assertFalse(rect.contains(20, 19.999));
		assertFalse(rect.contains(20, 60.001));
	}

	@Test
	void negativeSizeIsEmptyAndContainsNothing() {
		final Rect empty = new Rect(0, 0, -1, 5);

		assertTrue(empty.isEmpty());
		assertFalse(empty.contains(0, 0));
	}

	@Test
	void zeroSizeIsNotEmptyAndContainsItsCorner() {
		final Rect point = new Rect(5, 5, 0, 0);

		assertFalse(point.isEmpty());
		assertTrue(point.contains(5, 5));
	}

	@Test
	void rectsWithTheSameBoundsAreEqual() {
		assertEquals(new Rect(1, 2, 3, 4), new Rect(1, 2, 3, 4));
		assertEquals(new Rect(1, 2, 3, 4).hashCode(), new Rect(1, 2, 3, 4).hashCode());
		assertNotEquals(new Rect(1, 2, 3, 4), new Rect(1, 2, 4, 3));
	}
}
```

Create `core/src/test/java/com/shootoff/JavaFxReferenceScanner.java`:

```java
package com.shootoff;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds compiled classes that mention JavaFX. Class files store referenced type names as text
 * in their constant pool (e.g. "Ljavafx/scene/Node;"), so a byte search is enough.
 */
final class JavaFxReferenceScanner {
	private JavaFxReferenceScanner() {}

	static List<String> findJavaFxReferences(Path classesDir) throws IOException {
		try (Stream<Path> files = Files.walk(classesDir)) {
			return files.filter(p -> p.toString().endsWith(".class")).filter(JavaFxReferenceScanner::referencesJavaFx)
					.map(p -> classesDir.relativize(p).toString().replace(File.separatorChar, '/')).sorted()
					.collect(Collectors.toList());
		}
	}

	static long countClasses(Path classesDir) throws IOException {
		try (Stream<Path> files = Files.walk(classesDir)) {
			return files.filter(p -> p.toString().endsWith(".class")).count();
		}
	}

	private static boolean referencesJavaFx(Path classFile) {
		try {
			final String contents = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
			return contents.contains("javafx/") || contents.contains("javafx.");
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
```

Create `core/src/test/java/com/shootoff/TestNoJavaFxInCore.java`:

```java
package com.shootoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.geom.Rect;

class TestNoJavaFxInCore {
	@Test
	void coreClassesDoNotReferenceJavaFx() throws Exception {
		final Path classesDir = Paths.get(Rect.class.getProtectionDomain().getCodeSource().getLocation().toURI());

		assertTrue(Files.isDirectory(classesDir), "Expected core's compiled classes folder, got " + classesDir);
		assertTrue(Files.exists(classesDir.resolve("com/shootoff/geom/Rect.class")),
				"Scanning the wrong folder: " + classesDir);
		assertTrue(JavaFxReferenceScanner.countClasses(classesDir) > 0);
		assertEquals(List.of(), JavaFxReferenceScanner.findJavaFxReferences(classesDir));
	}

	@Test
	void scannerFindsJavaFxTypeReference(@TempDir Path dir) throws Exception {
		Files.createDirectories(dir.resolve("a"));
		Files.write(dir.resolve("a/B.class"), "Êþº¾ Ljavafx/scene/Node; java/lang/Object"
				.getBytes(StandardCharsets.ISO_8859_1));
		Files.write(dir.resolve("a/C.class"), "Êþº¾ java/lang/Object".getBytes(StandardCharsets.ISO_8859_1));

		assertEquals(List.of("a/B.class"), JavaFxReferenceScanner.findJavaFxReferences(dir));
	}

	@Test
	void scannerFindsJavaFxNameInStringConstant(@TempDir Path dir) throws Exception {
		Files.write(dir.resolve("D.class"), "Class.forName javafx.application.Platform".getBytes(StandardCharsets.ISO_8859_1));

		assertEquals(List.of("D.class"), JavaFxReferenceScanner.findJavaFxReferences(dir));
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --console=plain`
Expected: compilation FAILS with `cannot find symbol` for `Point`, `Size` and `Rect` (package `com.shootoff.geom` does not exist).

- [ ] **Step 3: Implement the geometry types**

Create each file with the GPL header (Global Constraints) followed by the code below.

`core/src/main/java/com/shootoff/geom/Point.java`:

```java
package com.shootoff.geom;

import java.util.Objects;

/**
 * An immutable point. Replaces JavaFX's {@code Point2D} in UI-neutral code; the getter names
 * match it.
 */
public final class Point {
	private final double x;
	private final double y;

	public Point(double x, double y) {
		this.x = x;
		this.y = y;
	}

	public double getX() {
		return x;
	}

	public double getY() {
		return y;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Point)) return false;

		final Point other = (Point) o;
		return Double.compare(x, other.x) == 0 && Double.compare(y, other.y) == 0;
	}

	@Override
	public int hashCode() {
		return Objects.hash(x, y);
	}

	@Override
	public String toString() {
		return "Point[x=" + x + ", y=" + y + "]";
	}
}
```

`core/src/main/java/com/shootoff/geom/Size.java`:

```java
package com.shootoff.geom;

import java.util.Objects;

/**
 * An immutable width and height. Replaces JavaFX's {@code Dimension2D} in UI-neutral code; the
 * getter names match it.
 */
public final class Size {
	private final double width;
	private final double height;

	public Size(double width, double height) {
		this.width = width;
		this.height = height;
	}

	public double getWidth() {
		return width;
	}

	public double getHeight() {
		return height;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Size)) return false;

		final Size other = (Size) o;
		return Double.compare(width, other.width) == 0 && Double.compare(height, other.height) == 0;
	}

	@Override
	public int hashCode() {
		return Objects.hash(width, height);
	}

	@Override
	public String toString() {
		return "Size[width=" + width + ", height=" + height + "]";
	}
}
```

`core/src/main/java/com/shootoff/geom/Rect.java`:

```java
package com.shootoff.geom;

import java.util.Objects;

/**
 * An immutable axis-aligned rectangle. Replaces JavaFX's {@code Bounds}/{@code BoundingBox} in
 * UI-neutral code: the getter names match, and {@link #contains(double, double)} behaves exactly
 * like {@code BoundingBox.contains} (edges inclusive, nothing inside an empty rectangle).
 */
public final class Rect {
	private final double minX;
	private final double minY;
	private final double width;
	private final double height;

	public Rect(double minX, double minY, double width, double height) {
		this.minX = minX;
		this.minY = minY;
		this.width = width;
		this.height = height;
	}

	public double getMinX() {
		return minX;
	}

	public double getMinY() {
		return minY;
	}

	public double getWidth() {
		return width;
	}

	public double getHeight() {
		return height;
	}

	public double getMaxX() {
		return minX + width;
	}

	public double getMaxY() {
		return minY + height;
	}

	/**
	 * @return <code>true</code> if the width or height is negative
	 */
	public boolean isEmpty() {
		return width < 0 || height < 0;
	}

	public boolean contains(double x, double y) {
		if (isEmpty()) return false;

		return x >= minX && x <= getMaxX() && y >= minY && y <= getMaxY();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof Rect)) return false;

		final Rect other = (Rect) o;
		return Double.compare(minX, other.minX) == 0 && Double.compare(minY, other.minY) == 0
				&& Double.compare(width, other.width) == 0 && Double.compare(height, other.height) == 0;
	}

	@Override
	public int hashCode() {
		return Objects.hash(minX, minY, width, height);
	}

	@Override
	public String toString() {
		return "Rect[minX=" + minX + ", minY=" + minY + ", width=" + width + ", height=" + height + "]";
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:test --console=plain`
Expected: all 14 tests pass: `TestPoint` 2, `TestSize` 2, `TestRect` 7 and `TestNoJavaFxInCore` 3.

- [ ] **Step 5: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 14.

```bash
git add core/src/main/java/com/shootoff/geom core/src/test/java/com/shootoff
git commit -m "Add core geometry types and a test that core has no JavaFX"
git log -1 --format=%B
```

---

### Task 3: Core sound player and user notifier

**Files:**
- Create: `core/src/main/java/com/shootoff/sound/SoundPlayer.java`, `core/src/test/java/com/shootoff/sound/TestSoundPlayer.java`
- Create: `core/src/main/java/com/shootoff/util/UserNotifier.java`, `javafx-app/src/main/java/com/shootoff/gui/AlertUserNotifier.java`
- Modify: `javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java` (sound methods ~lines 83, 149–151, 459–594)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/processors/MalfunctionsProcessor.java`, `VirtualMagazineProcessor.java`
- Modify: `javafx-app/src/main/java/com/shootoff/config/Configuration.java` (Alert blocks at ~413–426 and ~656–682)
- Modify: `javafx-app/src/main/java/com/shootoff/Main.java` (`runShootOFF`, ~line 537)
- Test: `javafx-app/src/test/java/com/shootoff/config/TestConfiguration.java` (two new JUnit 4 tests)

**Interfaces:**
- Produces: `com.shootoff.sound.SoundPlayer`, all static:
  - `silence(boolean)` and `isSilenced() → boolean`
  - `resolve(File) → File` (relative files resolve against the `shootoff.home` system property)
  - `play(String)`, `play(File)`, `play(InputStream)`, `play(InputStream, Optional<LineListener>)`, `playAll(List<File>)`

  When silenced it prints to stdout instead of playing, exactly as `TrainingExerciseBase` did:
  - a file prints its unresolved path
  - a stream prints `Playing audio for modular exercise.`
  - `playAll` prints each file
- Produces: `com.shootoff.util.UserNotifier` (functional interface `void showError(String title, String header, String message)`; constant `UserNotifier.LOGGING` logs at ERROR). `com.shootoff.gui.AlertUserNotifier implements UserNotifier` shows a JavaFX error `Alert`, and is safe to call from any thread.
- Produces: `Configuration.setUserNotifier(UserNotifier)` (static; `null` restores `LOGGING`). `Main` installs `AlertUserNotifier` before creating the configuration. Task 5 moves this static into `Settings`, and `Configuration.setUserNotifier(...)` keeps compiling as an inherited static.
- Keeps: every `TrainingExerciseBase` static sound method (`silence`, `playSound(String|File|InputStream|InputStream, Optional<LineListener>)`, `playSounds(List<File>)`), now delegating to `SoundPlayer`.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/com/shootoff/sound/TestSoundPlayer.java`:

```java
package com.shootoff.sound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestSoundPlayer {
	private final PrintStream originalOut = System.out;
	private final ByteArrayOutputStream out = new ByteArrayOutputStream();
	private String originalHome;

	@BeforeEach
	void captureStdout() {
		originalHome = System.getProperty("shootoff.home");
		System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
	}

	@AfterEach
	void restore() {
		System.setOut(originalOut);
		SoundPlayer.silence(false);
		if (originalHome == null) {
			System.clearProperty("shootoff.home");
		} else {
			System.setProperty("shootoff.home", originalHome);
		}
	}

	private String printed() {
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	void absoluteFileIsUnchanged() {
		final File absolute = new File("sounds/beep.wav").getAbsoluteFile();

		assertEquals(absolute, SoundPlayer.resolve(absolute));
	}

	@Test
	void relativeFileResolvesAgainstShootoffHome() {
		System.setProperty("shootoff.home", "/opt/shootoff");

		assertEquals(new File("/opt/shootoff" + File.separator + "sounds/beep.wav"),
				SoundPlayer.resolve(new File("sounds/beep.wav")));
	}

	@Test
	void silencedFilePrintsItsUnresolvedPath() {
		SoundPlayer.silence(true);

		SoundPlayer.play("sounds/beep.wav");

		assertEquals("sounds/beep.wav" + System.lineSeparator(), printed());
	}

	@Test
	void silencedStreamPrintsPlaceholder() {
		SoundPlayer.silence(true);

		SoundPlayer.play(new ByteArrayInputStream(new byte[0]));

		assertEquals("Playing audio for modular exercise." + System.lineSeparator(), printed());
	}

	@Test
	void silencedPlayAllPrintsEachPath() {
		SoundPlayer.silence(true);

		SoundPlayer.playAll(List.of(new File("a.wav"), new File("b.wav")));

		assertEquals("a.wav" + System.lineSeparator() + "b.wav" + System.lineSeparator(), printed());
	}

	@Test
	void missingFileIsLoggedNotThrown() {
		assertDoesNotThrow(() -> SoundPlayer.play(new File("/no/such/dir/missing.wav")));
	}
}
```

In `javafx-app/src/test/java/com/shootoff/config/TestConfiguration.java`, add these imports:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.Assume;
```

and add these two methods inside the class:

```java
	@Test
	public void testUnwritableConfigNotifiesUser() throws IOException, ConfigurationException {
		Assume.assumeFalse("root can write read-only files", "root".equals(System.getProperty("user.name")));

		final File props = File.createTempFile("unwritable", ".properties");
		final List<String> titles = new ArrayList<>();
		Configuration.setUserNotifier((title, header, message) -> titles.add(title));
		try {
			final Configuration config = new Configuration(props.getPath(), new String[0]);
			assertTrue(props.setWritable(false));

			assertFalse(config.writeConfigurationFile());
			assertEquals(List.of("Cannot Persist Preferences"), titles);
		} finally {
			Configuration.setUserNotifier(null);
			props.setWritable(true);
			props.delete();
		}
	}

	@Test
	public void testMalformedIpCamUrlNotifiesUser() {
		final List<String> titles = new ArrayList<>();
		Configuration.setUserNotifier((title, header, message) -> titles.add(title));
		try {
			assertFalse(defaultConfig.registerIpCam("bad", "not a url", Optional.empty(), Optional.empty()).isPresent());
			assertEquals(List.of("Malformed URL"), titles);
		} finally {
			Configuration.setUserNotifier(null);
		}
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test :javafx-app:compileTestJava --continue --console=plain`
Expected: both compilations FAIL: `SoundPlayer` (package `com.shootoff.sound` does not exist) and `Configuration.setUserNotifier` (cannot find symbol).

- [ ] **Step 3: Implement `SoundPlayer` and `UserNotifier` in core**

Create `core/src/main/java/com/shootoff/sound/SoundPlayer.java` (GPL header, then). The body is `TrainingExerciseBase`'s sound code moved unchanged, except that the two private overloads get distinct names:

```java
package com.shootoff.sound;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays sound files and streams asynchronously. Used by shot processors and exercises.
 */
public final class SoundPlayer {
	private static final Logger logger = LoggerFactory.getLogger(SoundPlayer.class);

	private static volatile boolean isSilenced = false;

	private SoundPlayer() {}

	/**
	 * Allows sounds to be silenced or on. If silenced, instead of playing a sound the file name
	 * is printed to stdout. This exists so that components can be easily tested even if they
	 * rely on sounds.
	 * 
	 * @param isSilenced
	 *            set to <tt>true</tt> if sound file names should instead be printed to stdout,
	 *            <tt>false</tt> for normal operation.
	 */
	public static void silence(boolean isSilenced) {
		SoundPlayer.isSilenced = isSilenced;
	}

	public static boolean isSilenced() {
		return isSilenced;
	}

	/**
	 * @return <tt>soundFile</tt> if it is absolute, otherwise <tt>soundFile</tt> resolved
	 *         against the <tt>shootoff.home</tt> folder
	 */
	public static File resolve(File soundFile) {
		if (soundFile.isAbsolute()) return soundFile;

		return new File(System.getProperty("shootoff.home") + File.separator + soundFile.getPath());
	}

	/**
	 * Plays an audio file asynchronously.
	 * 
	 * @param soundFilePath
	 *            the audio file to play (e.g. "sounds/metal_clang.wav")
	 */
	public static void play(String soundFilePath) {
		play(new File(soundFilePath));
	}

	public static void play(File soundFile) {
		playFile(soundFile, Optional.empty());
	}

	public static void play(InputStream is) {
		play(is, Optional.empty());
	}

	public static void play(InputStream is, Optional<LineListener> listener) {
		if (isSilenced) {
			System.out.println("Playing audio for modular exercise.");
			return;
		}

		try {
			final AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(is);
			playStream(audioInputStream, listener);
		} catch (UnsupportedAudioFileException | IOException e) {
			logger.error("Error reading sound stream to play", e);
		}
	}

	/**
	 * Plays the files one after another.
	 */
	public static void playAll(List<File> soundFiles) {
		if (isSilenced) {
			soundFiles.forEach(System.out::println);
		} else {
			final SoundQueue sq = new SoundQueue(soundFiles);
			sq.play();
		}
	}

	private static void playFile(File soundFile, Optional<LineListener> listener) {
		if (isSilenced) {
			System.out.println(soundFile.getPath());
			return;
		}

		final File resolvedFile = resolve(soundFile);

		try {
			final AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(resolvedFile);
			playStream(audioInputStream, listener);
		} catch (UnsupportedAudioFileException | IOException e) {
			logger.error(String.format("Error reading sound file to play: soundFile = %s", resolvedFile), e);
		}
	}

	private static void playStream(AudioInputStream audioInputStream, Optional<LineListener> listener) {
		final AudioFormat format = audioInputStream.getFormat();
		final DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

		SourceDataLine line = null;

		try {
			line = (SourceDataLine) AudioSystem.getLine(info);

			line.open(format);
			line.start();

			if (listener.isPresent()) {
				line.addLineListener(listener.get());
			} else {
				line.addLineListener((e) -> {
					if (LineEvent.Type.STOP.equals(e.getType())) {
						e.getLine().close();
						try {
							audioInputStream.close();
						} catch (final Exception e1) {
							logger.error("Error closing audio input stream", e1);
						}
					}
				});
			}

			final SourceDataLine sourceLine = line;
			new Thread(() -> {
				int nBytesRead = 0;
				final byte[] abData = new byte[1024];
				while (nBytesRead != -1) {
					try {
						nBytesRead = audioInputStream.read(abData, 0, abData.length);
					} catch (final IOException e) {
						logger.error("Error playing sound clip", e);
					}
					if (nBytesRead >= 0) {
						sourceLine.write(abData, 0, nBytesRead);
					}
				}

				sourceLine.drain();
				sourceLine.close();
			}).start();
		} catch (final LineUnavailableException e) {
			if (line != null) line.close();
			logger.error("Error playing sound clip", e);
		}
	}

	private static class SoundQueue implements LineListener {
		private final List<File> soundFiles;
		private int queueIndex = 0;

		public SoundQueue(List<File> soundFiles) {
			this.soundFiles = soundFiles;
		}

		public void play() {
			playFile(soundFiles.get(queueIndex), Optional.of(this));
		}

		@Override
		public void update(final LineEvent event) {
			if (LineEvent.Type.STOP.equals(event.getType())) {
				event.getLine().close();

				queueIndex++;

				if (queueIndex < soundFiles.size()) {
					playFile(soundFiles.get(queueIndex), Optional.of(this));
				}
			}
		}
	}
}
```

Before relying on this copy, diff it against the original. `sed -n 459,594p javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java` must match the code above except for the method names and the `resolvedFile` local. If the original differs anywhere else, copy the original's behavior.

Create `core/src/main/java/com/shootoff/util/UserNotifier.java` (GPL header, then):

```java
package com.shootoff.util;

import org.slf4j.LoggerFactory;

/**
 * Tells the user about a problem found by code that has no UI of its own (e.g. an unwritable
 * preferences file). A UI installs its own implementation; without one, problems are only
 * logged.
 */
@FunctionalInterface
public interface UserNotifier {
	void showError(String title, String header, String message);

	UserNotifier LOGGING = (title, header, message) -> LoggerFactory.getLogger(UserNotifier.class)
			.error("{} - {}: {}", title, header, message);
}
```

- [ ] **Step 4: Make `TrainingExerciseBase` and the processors use `SoundPlayer`**

In `TrainingExerciseBase.java`:
1. Delete the field `	private static boolean isSilenced = false;`.
2. Replace the body of `silence(boolean isSilenced)`, `		TrainingExerciseBase.isSilenced = isSilenced;`, with `		SoundPlayer.silence(isSilenced);`. Keep its Javadoc.
3. Replace everything from the `	/**` line directly above ` * Plays an audio file asynchronously.` through the closing `	}` of `private static class SoundQueue` (the line just before the Javadoc ` * Removes all objects the training exercise has added to the GUI.`) with:

```java
	/**
	 * Plays an audio file asynchronously.
	 * 
	 * @param soundFilePath
	 *            the audio file to play (e.g. "sounds/metal_clang.wav")
	 * 
	 * @since 1.1
	 */
	public static void playSound(final String soundFilePath) {
		SoundPlayer.play(soundFilePath);
	}

	public static void playSound(final File soundFile) {
		SoundPlayer.play(soundFile);
	}

	public static void playSound(final InputStream is) {
		SoundPlayer.play(is);
	}

	public static void playSound(final InputStream is, Optional<LineListener> listener) {
		SoundPlayer.play(is, listener);
	}

	public static void playSounds(final List<File> soundFiles) {
		SoundPlayer.playAll(soundFiles);
	}
```

4. Imports:
   - Remove `java.io.IOException` and the `javax.sound.sampled` imports `AudioFormat`, `AudioInputStream`, `AudioSystem`, `DataLine`, `LineEvent`, `LineUnavailableException`, `SourceDataLine` and `UnsupportedAudioFileException`.
   - Keep `javax.sound.sampled.LineListener`.
   - Add `import com.shootoff.sound.SoundPlayer;` after `import com.shootoff.gui.ShotEntry;`.
   - Then check: `grep -n "IOException\|AudioSystem\|isSilenced" javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java` prints only the Javadoc line ` * @param isSilenced` and the `silence(boolean isSilenced)` method.

In both processors, switch to `SoundPlayer`:

```bash
cd /home/bfears/projects/ShootOFF
perl -pi -e 's/^import com\.shootoff\.plugins\.TrainingExerciseBase;/import com.shootoff.sound.SoundPlayer;/; s/TrainingExerciseBase\.playSound\(/SoundPlayer.play(/g' \
  javafx-app/src/main/java/com/shootoff/camera/processors/MalfunctionsProcessor.java \
  javafx-app/src/main/java/com/shootoff/camera/processors/VirtualMagazineProcessor.java
grep -rn "TrainingExerciseBase\|com.shootoff.plugins" javafx-app/src/main/java/com/shootoff/camera
```

Expected: the last `grep` prints nothing.

- [ ] **Step 5: Replace the `Alert`s in `Configuration` with the notifier**

In `javafx-app/src/main/java/com/shootoff/config/Configuration.java`:
- Remove `import javafx.scene.control.Alert;` and `import javafx.scene.control.Alert.AlertType;`.
- Add `import com.shootoff.util.UserNotifier;` after `import com.shootoff.session.SessionRecorder;`.

Add, directly after `	private static Configuration config = null;`:

```java

	private static volatile UserNotifier userNotifier = UserNotifier.LOGGING;

	/**
	 * Sets who tells the user about configuration problems (an unwritable preferences file, a bad
	 * IP camera URL). Without a UI they are only logged.
	 * 
	 * @param notifier
	 *            the notifier to use, or <tt>null</tt> to only log
	 */
	public static void setUserNotifier(UserNotifier notifier) {
		userNotifier = notifier != null ? notifier : UserNotifier.LOGGING;
	}
```

In `writeConfigurationFile()`, replace

```java
			final Alert writeAlert = new Alert(AlertType.ERROR);
			writeAlert.setTitle("Cannot Persist Preferences");
			writeAlert.setHeaderText("Configuration File Unwritable!");
			writeAlert.setResizable(true);
			writeAlert.setContentText("The file " + configName + " is not writable, thus your preferences"
					+ " cannot be saved. This is likely the case because you placed ShootOFF in a location"
					+ " that only the administrator can write to, but ShootOFF is not running as an"
					+ " administrator. Please either move ShootOFF to a different location or grant write"
					+ " privileges to the file.");
			writeAlert.showAndWait();
```

with

```java
			userNotifier.showError("Cannot Persist Preferences", "Configuration File Unwritable!",
					"The file " + configName + " is not writable, thus your preferences"
							+ " cannot be saved. This is likely the case because you placed ShootOFF in a location"
							+ " that only the administrator can write to, but ShootOFF is not running as an"
							+ " administrator. Please either move ShootOFF to a different location or grant write"
							+ " privileges to the file.");
```

In `registerIpCam(...)`, replace the three `catch` blocks (from `		} catch (MalformedURLException | URISyntaxException ue) {` through the `ipcamTimeoutAlert.showAndWait();` line and its closing `		}`) with:

```java
		} catch (MalformedURLException | URISyntaxException ue) {
			userNotifier.showError("Malformed URL", "IPCam URL is Malformed!",
					"IPCam URL is not valid: \n\n" + ue.getMessage());
		} catch (final UnknownHostException uhe) {
			userNotifier.showError("Unknown Host", "IPCam URL Unknown!",
					"The IPCam at " + cameraURL + " cannot be resolved. Ensure the URL is correct "
							+ "and that you are either connected to the internet or on the same network as the camera.");
		} catch (final TimeoutException te) {
			userNotifier.showError("IPCam Timeout", "Connection to IPCam Reached Timeout!",
					"Could not communicate with the IP at " + cameraURL + ". Please check the following:\n\n"
							+ "-The IPCam URL is correct\n"
							+ "-You are connected to the Internet (for external cameras)\n"
							+ "-You are connected to the same network as the camera (for local cameras)");
		}
```

Check: `grep -n "Alert" javafx-app/src/main/java/com/shootoff/config/Configuration.java` prints nothing.

- [ ] **Step 6: Show alerts in the JavaFX app**

Create `javafx-app/src/main/java/com/shootoff/gui/AlertUserNotifier.java` (GPL header, then):

```java
package com.shootoff.gui;

import com.shootoff.util.UserNotifier;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;

/**
 * Shows problems reported by non-UI code as JavaFX error dialogs.
 */
public class AlertUserNotifier implements UserNotifier {
	@Override
	public void showError(String title, String header, String message) {
		if (Platform.isFxApplicationThread()) {
			show(title, header, message);
		} else {
			Platform.runLater(() -> show(title, header, message));
		}
	}

	private static void show(String title, String header, String message) {
		final Alert alert = new Alert(AlertType.ERROR);
		alert.setTitle(title);
		alert.setHeaderText(header);
		alert.setResizable(true);
		alert.setContentText(message);
		alert.showAndWait();
	}
}
```

In `javafx-app/src/main/java/com/shootoff/Main.java`, add `import com.shootoff.gui.AlertUserNotifier;` to the `com.shootoff` imports. In `runShootOFF()`, replace

```java
		Configuration config;
		try {
			config = new Configuration(System.getProperty("shootoff.home") + File.separator + "shootoff.properties",
```

with

```java
		Configuration.setUserNotifier(new AlertUserNotifier());

		Configuration config;
		try {
			config = new Configuration(System.getProperty("shootoff.home") + File.separator + "shootoff.properties",
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core:test :javafx-app:test --tests 'com.shootoff.config.TestConfiguration' --tests 'com.shootoff.camera.TestMalfunctionsProcessor' --tests 'com.shootoff.camera.TestVirtualMagazineProcessor' --tests 'com.shootoff.plugins.TestShootDontShoot' --console=plain`

Expected:
- all `TestSoundPlayer` tests pass (6)
- all `TestConfiguration` tests pass, including the 2 new ones
- the processor tests and `TestShootDontShoot` pass (it silences sounds and checks what reaches stdout)

- [ ] **Step 8: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 8.

```bash
git add core/src/main/java/com/shootoff/sound core/src/test/java/com/shootoff/sound core/src/main/java/com/shootoff/util/UserNotifier.java \
  javafx-app/src/main/java/com/shootoff/gui/AlertUserNotifier.java \
  javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java \
  javafx-app/src/main/java/com/shootoff/camera/processors/MalfunctionsProcessor.java \
  javafx-app/src/main/java/com/shootoff/camera/processors/VirtualMagazineProcessor.java \
  javafx-app/src/main/java/com/shootoff/config/Configuration.java \
  javafx-app/src/main/java/com/shootoff/Main.java \
  javafx-app/src/test/java/com/shootoff/config/TestConfiguration.java
git commit -m "Move sound playback and configuration error reporting into UI-neutral core types"
git log -1 --format=%B
```

---

### Task 4: Shot and configuration logic use `ShotColor`, not JavaFX `Color`

**Files:**
- Modify: `javafx-app/src/main/java/com/shootoff/camera/Shot.java` (remove `colorMap`, `getPaintColor`)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/shot/DisplayShot.java`, `ArenaShot.java` (paint lookup)
- Modify: `javafx-app/src/main/java/com/shootoff/config/Configuration.java` (`getIgnoreLaserColor`, ~line 919)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java` (`checkIgnoreColor`, ~line 142)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/shotdetection/JavaShotDetector.java:434`
- Test: `javafx-app/src/test/java/com/shootoff/config/TestConfiguration.java` (lines 8, 91, 94, 293), `javafx-app/src/test/java/com/shootoff/gui/TestCanvasManager.java` (lines 94, 99)

**Interfaces:**
- Produces:
  - `Configuration.getIgnoreLaserColor() → Optional<ShotColor>`: `RED` for `"red"`, `GREEN` for `"green"`, otherwise empty.
  - `DisplayShot.toPaint(ShotColor) → javafx.scene.paint.Color` (static; `RED→Color.RED`, `GREEN→Color.GREEN`, `INFRARED→Color.ORANGE`, the exact old `Shot.colorMap`), and `DisplayShot.getPaintColor() → Color`.
- Removes: `Shot.colorMap` and `Shot.getPaintColor()`. Every existing caller of `getPaintColor()` holds a `DisplayShot`: `XMLSessionWriter`, `JSONSessionWriter` and `ShootOFFController`. Session files keep writing `Color.toString()` values, so the file format is unchanged.

- [ ] **Step 1: Change the tests to expect `ShotColor`**

In `TestConfiguration.java`:
- Replace `import javafx.scene.paint.Color;` with `import com.shootoff.camera.shot.ShotColor;`.
- Replace `assertEquals(Color.RED, defaultConfig.getIgnoreLaserColor().get());` with `assertEquals(ShotColor.RED, defaultConfig.getIgnoreLaserColor().get());`.
- Replace `assertEquals(Color.GREEN, defaultConfig.getIgnoreLaserColor().get());` with `assertEquals(ShotColor.GREEN, defaultConfig.getIgnoreLaserColor().get());`.
- Replace `assertEquals(Color.GREEN, writtenConfig.getIgnoreLaserColor().get());` with `assertEquals(ShotColor.GREEN, writtenConfig.getIgnoreLaserColor().get());`.

In `TestCanvasManager.java`, replace `CanvasManager.colorToWebCode(Shot.colorMap.get(ShotColor.RED))` with `CanvasManager.colorToWebCode(DisplayShot.toPaint(ShotColor.RED))`, and `CanvasManager.colorToWebCode(Shot.colorMap.get(ShotColor.GREEN))` with `CanvasManager.colorToWebCode(DisplayShot.toPaint(ShotColor.GREEN))`. `Shot` is still imported and used on line 134.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.config.TestConfiguration' --console=plain`
Expected: compilation FAILS on `DisplayShot.toPaint` (cannot find symbol). To see the real RED on the configuration tests, temporarily revert the two `TestCanvasManager` lines and rerun. `testIgnoreLaserColorValid` and `testWriteConfigFile` then FAIL with `expected:<RED> but was:<0xff0000ff>` (and `GREEN`/`0x008000ff`). Restore the two lines afterwards.

- [ ] **Step 3: Implement**

`Configuration.java`: add `import com.shootoff.camera.shot.ShotColor;` after `import com.shootoff.camera.processors.VirtualMagazineProcessor;`, and replace

```java
	public Optional<Color> getIgnoreLaserColor() {
		if (ignoreLaserColorName.equals("red")) {
			return Optional.of(Color.RED);
		} else if (ignoreLaserColorName.equals("green")) {
			return Optional.of(Color.GREEN);
		}

		return Optional.empty();
	}
```

with

```java
	public Optional<ShotColor> getIgnoreLaserColor() {
		if (ignoreLaserColorName.equals("red")) {
			return Optional.of(ShotColor.RED);
		} else if (ignoreLaserColorName.equals("green")) {
			return Optional.of(ShotColor.GREEN);
		}

		return Optional.empty();
	}
```

Keep `import javafx.scene.paint.Color;`: `shotRowColor` still uses it until Task 5.

`ShotDetector.java`: in `checkIgnoreColor`, replace

```java
				&& Shot.colorMap.get(color).equals(config.getIgnoreLaserColor().get())) {
```

with

```java
				&& color.equals(config.getIgnoreLaserColor().get())) {
```

(`Shot` is still used in `addShot`, so keep its import.)

`JavaShotDetector.java:434`, a debug-image path, is dead while `isDebugShotsRecordToFiles()` is `false`. It compared a JavaFX `Color` with a `ShotColor`, so it never matched. Replace

```java
				if (javafx.scene.paint.Color.GREEN.equals(color.get())) {
```

with

```java
				if (ShotColor.GREEN.equals(color.get())) {
```

`Shot.java`:
- Delete the imports `java.util.HashMap`, `java.util.Map` and `javafx.scene.paint.Color`.
- Delete the block

  ```java
  	static public final Map<ShotColor, Color> colorMap = new HashMap<ShotColor, Color>();
  	static {
  		colorMap.put(ShotColor.RED, Color.RED);
  		colorMap.put(ShotColor.GREEN, Color.GREEN);
  		colorMap.put(ShotColor.INFRARED, Color.ORANGE);
  	}
  ```

  and the method

  ```java
  	public Color getPaintColor() {
  		return colorMap.get(color);
  	}
  ```

`DisplayShot.java`:
- Add `import javafx.scene.paint.Color;` above `import javafx.scene.shape.Ellipse;`.
- Replace every `colorMap.get(color)` (4 places) with `toPaint(color)`.
- Add these methods after the last constructor:

```java
	/**
	 * @return the JavaFX paint used for markers of shots of <tt>color</tt>
	 */
	public static Color toPaint(ShotColor color) {
		return switch (color) {
		case RED -> Color.RED;
		case GREEN -> Color.GREEN;
		case INFRARED -> Color.ORANGE;
		};
	}

	public Color getPaintColor() {
		return toPaint(color);
	}
```

`ArenaShot.java`: replace both `colorMap.get(color)` with `toPaint(color)`.

Check:

```bash
grep -rn "colorMap" javafx-app/src
grep -rn "javafx" javafx-app/src/main/java/com/shootoff/camera/Shot.java javafx-app/src/main/java/com/shootoff/camera/shotdetection
```

Expected: both print nothing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.config.TestConfiguration' --tests 'com.shootoff.gui.TestCanvasManager' --tests 'com.shootoff.camera.shot.TestShot' --tests 'com.shootoff.session.io.TestSessionIO' --console=plain`
Expected: all PASS. `TestSessionIO` shows the session color strings didn't change.

- [ ] **Step 5: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 0.

```bash
git add javafx-app/src/main/java/com/shootoff/camera/Shot.java \
  javafx-app/src/main/java/com/shootoff/camera/shot/DisplayShot.java \
  javafx-app/src/main/java/com/shootoff/camera/shot/ArenaShot.java \
  javafx-app/src/main/java/com/shootoff/config/Configuration.java \
  javafx-app/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java \
  javafx-app/src/main/java/com/shootoff/camera/shotdetection/JavaShotDetector.java \
  javafx-app/src/test/java/com/shootoff/config/TestConfiguration.java \
  javafx-app/src/test/java/com/shootoff/gui/TestCanvasManager.java
git commit -m "Use ShotColor instead of JavaFX Color in shot and configuration logic"
git log -1 --format=%B
```

---

### Task 5: Split `Configuration` into neutral `Settings` and JavaFX-app runtime state

`Settings` is created in `javafx-app` next to `Configuration`, because it still depends on camera classes that are there. It moves to `core` in Task 10.

**Files:**
- Rename: `javafx-app/src/main/java/com/shootoff/config/Configuration.java` → `Settings.java` (`git mv`, then edit)
- Create: `javafx-app/src/main/java/com/shootoff/config/Configuration.java` (new, runtime state only)
- Rename: `javafx-app/src/main/java/com/shootoff/gui/CalibrationOption.java` → `javafx-app/src/main/java/com/shootoff/config/CalibrationOption.java`
- Modify (`CalibrationOption` import):
  - `gui/CalibrationConfigurator.java`
  - `gui/CalibrationManager.java`
  - `gui/controller/PreferencesController.java`
  - `gui/pane/ProjectorSlide.java`
  - test `camera/TestAutoCalibration.java`
- Modify (`Configuration` → `Settings` in camera code), all under `javafx-app/src/main/java/com/shootoff/camera/`:
  - `CameraManager.java`
  - `CamerasSupervisor.java`
  - `shotdetection/ShotDetector.java`
  - `shotdetection/JavaShotDetector.java`
  - `autocalibration/AutoCalibrationManager.java`
  - `processors/MalfunctionsProcessor.java`
  - `processors/VirtualMagazineProcessor.java`
- Modify: `CameraManager.startRecordingShots` (~line 410), `javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java:227`, `javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java:213`
- Create: `javafx-app/src/test/java/com/shootoff/config/TestSettings.java` (moves to `core` in Task 10), `javafx-app/src/test/java/com/shootoff/config/TestConfigurationSingleton.java` (stays)

**Interfaces:**
- Produces `com.shootoff.config.Settings`: every persisted preference and command-line option, with the same public getters/setters `Configuration` had, and:
  - constructors `protected Settings(InputStream, String)`, `protected Settings(String)`, `protected Settings(InputStream, String, String[])`, `public Settings(String name, String[] args)` and `public Settings(String[] args)`. Each constructor makes the new instance current.
  - `public static Settings getSettings()`: the most recently constructed `Settings` (in the app, the `Configuration`)
  - `public static void setUserNotifier(UserNotifier)` (moved from `Configuration`)
  - `Optional<Point> getArenaPosition()`, `setArenaPosition(double, double)`
  - `Optional<ShotColor> getIgnoreLaserColor()`
  - `CalibrationOption getCalibratedFeedBehavior()`
  - a bad command line ends the process with `System.exit(-1)` (was `Main.forceClose(-1)`, which is the same call)
- Produces `com.shootoff.config.Configuration extends Settings` (javafx-app):
  - the same five constructors, delegating to `super`
  - `public static Configuration getConfig()` returns `Settings.getSettings()` if it is a `Configuration`, otherwise `null`
  - the runtime state, moved verbatim: `registerVideoPlayer`/`unregisterVideoPlayer`/`getVideoPlayers`, `setSessionRecorder`/`getSessionRecorder`, `setExercise`/`getExercise`, `setPlugin`/`getPlugin`, `setShotTimerRowColor(Color)`/`getShotTimerRowColor()`, `registerRecordingCameraManager`/`unregisterRecordingCameraManager`/`unregisterAllRecordingCameraManagers`/`getRecordingManagers`

  So `config.getX()` call sites in `gui`/`plugins`/`session` compile unchanged.
- Produces `com.shootoff.config.CalibrationOption` (moved from `com.shootoff.gui`, otherwise unchanged).
- Changes `CameraManager.startRecordingShots()` → `startRecordingShots(Optional<String> sessionName)`. The camera no longer asks the configuration for the session recorder.
- Camera code uses only `Settings`: `Settings.getSettings()`, `new VirtualMagazineProcessor(Settings)`, `new MalfunctionsProcessor(Settings)`, `new CamerasSupervisor(Settings)`. Passing a `Configuration` still works.

- [ ] **Step 1: Write the failing tests**

Create `javafx-app/src/test/java/com/shootoff/config/TestSettings.java`:

```java
package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.shot.ShotColor;
import com.shootoff.geom.Point;

class TestSettings {
	// A shootoff.properties as today's app writes it, minus the webcam keys (reading those
	// enumerates real cameras)
	private static final List<String> EXISTING_FILE = List.of(
			"shootoff.firstrun=false",
			"shootoff.errorreporting=false",
			"shootoff.markerradius=6",
			"shootoff.ignorelasercolor=red",
			"shootoff.redlasersound.use=false",
			"shootoff.redlasersound=sounds/walther_ppq.wav",
			"shootoff.greenlasersound.use=false",
			"shootoff.greenlasersound=sounds/walther_ppq.wav",
			"shootoff.virtualmagazine.use=true",
			"shootoff.virtualmagazine.capacity=15",
			"shootoff.malfunctions.use=true",
			"shootoff.malfunctions.probability=5.5",
			"shootoff.arena.x=1920.0",
			"shootoff.arena.y=0.0",
			"shootoff.diagnosticmessages.chime.muted=Warning: Excessive brightness",
			"shootoff.webcams.distances=Cam A|300",
			"shootoff.arena.calibrated.behavior=CROP",
			"shootoff.arena.show.markers=true",
			"shootoff.arena.calibrated.exposure=false",
			"shootoff.arena.notified.perspective=true",
			"shootoff.poiadjust.x=1.5",
			"shootoff.poiadjust.y=-2.25");

	@TempDir
	Path tempDir;

	@BeforeAll
	static void setUpHome() {
		// Writing always stores the (empty) webcam list; reading that back enumerates cameras
		// through OpenCV
		Loader.load(opencv_java.class);
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	private File existingFile() throws IOException {
		final Path file = tempDir.resolve("shootoff.properties");
		Files.write(file, EXISTING_FILE, StandardCharsets.ISO_8859_1);
		return file.toFile();
	}

	@Test
	void readsEveryKeyOfAnExistingPropertiesFile() throws Exception {
		final Settings settings = new Settings(existingFile().getPath(), new String[0]);

		assertFalse(settings.isFirstRun());
		assertFalse(settings.useErrorReporting());
		assertEquals(6, settings.getMarkerRadius());
		assertTrue(settings.ignoreLaserColor());
		assertEquals(Optional.of(ShotColor.RED), settings.getIgnoreLaserColor());
		assertFalse(settings.useRedLaserSound());
		assertTrue(settings.useVirtualMagazine());
		assertEquals(15, settings.getVirtualMagazineCapacity());
		assertTrue(settings.useMalfunctions());
		assertEquals(5.5f, settings.getMalfunctionsProbability(), 0.001f);
		assertEquals(Optional.of(new Point(1920.0, 0.0)), settings.getArenaPosition());
		assertTrue(settings.isChimeMuted("Warning: Excessive brightness"));
		assertEquals(Optional.of(300), settings.getCameraDistance("Cam A"));
		assertEquals(CalibrationOption.CROP, settings.getCalibratedFeedBehavior());
		assertTrue(settings.showArenaShotMarkers());
		assertFalse(settings.autoAdjustExposure());
		assertTrue(settings.showedPerspectiveMessage());
		assertTrue(settings.isAdjustingPOI());
		assertEquals(Optional.of(1.5), settings.getPOIAdjustmentX());
		assertEquals(Optional.of(-2.25), settings.getPOIAdjustmentY());
	}

	@Test
	void writtenSettingsReadBackUnchanged() throws Exception {
		final File file = existingFile();
		final Settings written = new Settings(file.getPath(), new String[0]);
		written.setMarkerRadius(9);
		written.setArenaPosition(12.5, 340.0);
		written.setCalibratedFeedBehavior(CalibrationOption.EVERYWHERE);
		written.muteMessageChime("Another message");
		assertTrue(written.writeConfigurationFile());

		final Settings read = new Settings(file.getPath(), new String[0]);

		// Changed values
		assertEquals(9, read.getMarkerRadius());
		assertEquals(Optional.of(new Point(12.5, 340.0)), read.getArenaPosition());
		assertEquals(CalibrationOption.EVERYWHERE, read.getCalibratedFeedBehavior());
		assertTrue(read.isChimeMuted("Another message"));
		// Values carried through from the original file
		assertFalse(read.useErrorReporting());
		assertEquals(Optional.of(ShotColor.RED), read.getIgnoreLaserColor());
		assertTrue(read.useVirtualMagazine());
		assertEquals(15, read.getVirtualMagazineCapacity());
		assertTrue(read.useMalfunctions());
		assertEquals(5.5f, read.getMalfunctionsProbability(), 0.001f);
		assertTrue(read.isChimeMuted("Warning: Excessive brightness"));
		assertEquals(Optional.of(300), read.getCameraDistance("Cam A"));
		assertTrue(read.showArenaShotMarkers());
		assertFalse(read.autoAdjustExposure());
		assertTrue(read.showedPerspectiveMessage());
		assertTrue(read.isAdjustingPOI());
		assertEquals(Optional.of(1.5), read.getPOIAdjustmentX());
		assertEquals(Optional.of(-2.25), read.getPOIAdjustmentY());
	}
}
```

Create `javafx-app/src/test/java/com/shootoff/config/TestConfigurationSingleton.java`:

```java
package com.shootoff.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestConfigurationSingleton {
	@AfterEach
	void leaveAConfigurationCurrent() throws ConfigurationException {
		// Later tests in this JVM expect Configuration.getConfig() to be non-null
		new Configuration(new String[0]);
	}

	@Test
	void newConfigurationIsTheCurrentSettings() throws ConfigurationException {
		final Configuration config = new Configuration(new String[0]);

		assertSame(config, Settings.getSettings());
		assertSame(config, Configuration.getConfig());
	}

	@Test
	void plainSettingsIsNotAConfiguration() throws ConfigurationException {
		final Settings settings = new Settings(new String[0]);

		assertSame(settings, Settings.getSettings());
		assertNull(Configuration.getConfig());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :javafx-app:compileTestJava --console=plain`
Expected: compilation FAILS with `cannot find symbol: class Settings` and `cannot find symbol: variable CalibrationOption` (it is still in `com.shootoff.gui`).

- [ ] **Step 3: Rename `Configuration.java` to `Settings.java` and strip the runtime state**

```bash
cd /home/bfears/projects/ShootOFF
git mv javafx-app/src/main/java/com/shootoff/config/Configuration.java javafx-app/src/main/java/com/shootoff/config/Settings.java
mkdir -p build
cat > build/split_settings.py <<'EOF'
import pathlib, re, sys

path = pathlib.Path("javafx-app/src/main/java/com/shootoff/config/Settings.java")
src = path.read_text()

def replace(pattern, replacement, expected, flags=re.M):
    global src
    src, n = re.subn(pattern, replacement, src, flags=flags)
    if n != expected:
        sys.exit(f"{pattern!r}: expected {expected} match(es), found {n}; Settings.java left unchanged")

# Imports of runtime-state and JavaFX types
for imp in ["com.shootoff.Main", "com.shootoff.camera.CameraManager", "com.shootoff.gui.CalibrationOption",
            "com.shootoff.gui.controller.VideoPlayerController", "com.shootoff.plugins.TrainingExercise",
            "com.shootoff.plugins.engine.Plugin", "com.shootoff.session.SessionRecorder",
            "javafx.scene.paint.Color"]:
    replace(r"^import " + re.escape(imp) + r";\n", "", 1)
replace(r"^import javafx\.geometry\.Point2D;$", "import com.shootoff.geom.Point;", 1)

# Class Javadoc, name, logger, constructors
replace(r" \* Used to parse, store, and update configuration data from a file and in memory\n"
        r" \* at run time\. All of ShootOFF's global settings are managed by this class\.\n",
        " * Parses, stores and updates ShootOFF's persisted preferences (shootoff.properties) and\n"
        " * command-line options. Holds no UI, exercise or session state: the JavaFX app adds that in\n"
        " * its Configuration subclass.\n", 1)
replace(r"^public class Configuration \{", "public class Settings {", 1)
replace(r"getLogger\(Configuration\.class\)", "getLogger(Settings.class)", 1)
replace(r"^\t(protected|public) Configuration\(", r"\t\1 Settings(", 5)

# Singleton
replace(r"\tprivate static Configuration config = null;\n",
        "\tprivate static Settings settings = null;\n", 1)
replace(r"\tpublic static Configuration getConfig\(\) \{\n\t\treturn config;\n\t\}\n\n"
        r"\tprivate static void setConfig\(Configuration config\) \{\n\t\tConfiguration\.config = config;\n\t\}\n",
        "\t/**\n\t * @return the most recently constructed settings (in the JavaFX app, its Configuration)\n\t */\n"
        "\tpublic static Settings getSettings() {\n\t\treturn settings;\n\t}\n\n"
        "\tprivate static void setSettings(Settings settings) {\n\t\tSettings.settings = settings;\n\t}\n", 1)
replace(r"\bsetConfig\(this\);", "setSettings(this);", 5)

# No dependency on Main (forceClose is System.exit) or JavaFX geometry
replace(r"Main\.forceClose\(-1\);", "System.exit(-1);", 1)
replace(r"\bPoint2D\b", "Point", 4)

# Runtime-state fields move to Configuration
for field in [r"\tprivate final Set<CameraManager> recordingManagers = new HashSet<>\(\);\n",
              r"\tprivate final Set<VideoPlayerController> videoPlayers = new HashSet<>\(\);\n",
              r"\tprivate Optional<SessionRecorder> sessionRecorder = Optional\.empty\(\);\n",
              r"\tprivate TrainingExercise currentExercise = null;\n",
              r"\tprivate Plugin currentPlugin = null;\n",
              r"\tprivate Optional<Color> shotRowColor = Optional\.empty\(\);\n"]:
    replace(field, "", 1)

# Runtime-state methods move to Configuration (each is removed with the blank line before it)
for signature in ["public void registerVideoPlayer(VideoPlayerController videoPlayer)",
                  "public void unregisterVideoPlayer(VideoPlayerController videoPlayer)",
                  "public Set<VideoPlayerController> getVideoPlayers()",
                  "public void setShotTimerRowColor(Color c)",
                  "public void registerRecordingCameraManager(CameraManager cm)",
                  "public void unregisterRecordingCameraManager(CameraManager cm)",
                  "public void unregisterAllRecordingCameraManagers()",
                  "public void setSessionRecorder(SessionRecorder sessionRecorder)",
                  "public void setExercise(TrainingExercise exercise)",
                  "public void setPlugin(Plugin plugin)",
                  "public Optional<SessionRecorder> getSessionRecorder()",
                  "public Set<CameraManager> getRecordingManagers()",
                  "public Optional<TrainingExercise> getExercise()",
                  "public Optional<Plugin> getPlugin()",
                  "public Optional<Color> getShotTimerRowColor()"]:
    replace(r"\n\t" + re.escape(signature) + r" \{\n.*?\n\t\}\n", "", 1, flags=re.S)

path.write_text(src)
print("Settings.java updated")
EOF
python3 build/split_settings.py
```

Expected: `Settings.java updated`. If the script exits with a "expected N match(es)" message, it has not written anything. Compare that pattern with the file, which must be the Task 4 version, and fix the pattern, not the file.

Check:

```bash
grep -nE "Point2D|javafx|VideoPlayer|SessionRecorder|TrainingExercise|\bPlugin\b|recordingManagers|shotRowColor|Main\.|Configuration\b" javafx-app/src/main/java/com/shootoff/config/Settings.java
```

Expected: only lines with `ConfigurationException`, the notifier header text `"Configuration File Unwritable!"`, and the two Javadoc comments that mention the JavaFX app's `Configuration` subclass.

- [ ] **Step 4: Move `CalibrationOption` into the `config` package**

```bash
cd /home/bfears/projects/ShootOFF
git mv javafx-app/src/main/java/com/shootoff/gui/CalibrationOption.java javafx-app/src/main/java/com/shootoff/config/CalibrationOption.java
perl -pi -e 's/^package com\.shootoff\.gui;/package com.shootoff.config;/' javafx-app/src/main/java/com/shootoff/config/CalibrationOption.java
grep -rl "com.shootoff.gui.CalibrationOption" javafx-app/src | xargs perl -pi -e 's/com\.shootoff\.gui\.CalibrationOption/com.shootoff.config.CalibrationOption/g'
```

Two files in package `com.shootoff.gui` used it without an import:
- In `javafx-app/src/main/java/com/shootoff/gui/CalibrationConfigurator.java`, replace `package com.shootoff.gui;\n\npublic interface CalibrationConfigurator {` with `package com.shootoff.gui;\n\nimport com.shootoff.config.CalibrationOption;\n\npublic interface CalibrationConfigurator {`.
- In `javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java`, add `import com.shootoff.config.CalibrationOption;` directly above `import com.shootoff.config.Configuration;`.

Check that every user outside `com.shootoff.config` imports it:

```bash
grep -rlw CalibrationOption javafx-app/src | xargs grep -L "import com.shootoff.config.CalibrationOption;"
```

Expected: only `.../config/CalibrationOption.java` and `.../config/Settings.java`, which share its package.

- [ ] **Step 5: Create the runtime-state `Configuration`**

Create `javafx-app/src/main/java/com/shootoff/config/Configuration.java` (GPL header, then):

```java
package com.shootoff.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.shootoff.camera.CameraManager;
import com.shootoff.gui.controller.VideoPlayerController;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.engine.Plugin;
import com.shootoff.session.SessionRecorder;

import javafx.scene.paint.Color;

/**
 * The JavaFX app's configuration: the persisted {@link Settings} plus the app's runtime state
 * (the current exercise and plugin, the session recorder, recording cameras, open video players
 * and the shot timer row color).
 * 
 * @author phrack
 */
public class Configuration extends Settings {
	private final Set<CameraManager> recordingManagers = new HashSet<>();
	private final Set<VideoPlayerController> videoPlayers = new HashSet<>();
	private Optional<SessionRecorder> sessionRecorder = Optional.empty();
	private TrainingExercise currentExercise = null;
	private Plugin currentPlugin = null;
	private Optional<Color> shotRowColor = Optional.empty();

	/**
	 * @return the current configuration, or <tt>null</tt> if the most recently constructed
	 *         {@link Settings} is not a <tt>Configuration</tt> (only happens in core tests)
	 */
	public static Configuration getConfig() {
		final Settings settings = Settings.getSettings();
		return settings instanceof Configuration ? (Configuration) settings : null;
	}

	protected Configuration(InputStream configInputStream, String name) throws IOException, ConfigurationException {
		super(configInputStream, name);
	}

	protected Configuration(String name) throws IOException, ConfigurationException {
		super(name);
	}

	protected Configuration(InputStream configInputStream, String name, String[] args)
			throws IOException, ConfigurationException {
		super(configInputStream, name, args);
	}

	/**
	 * Loads the configuration from a file named <tt>name</tt> and then updates the
	 * configuration using the programs arguments stored in <tt>args</tt>.
	 * 
	 * @param name
	 *            the configuration file to load properties from
	 * @param args
	 *            the command line arguments for this program
	 * @throws IOException
	 *             <tt>name</tt> doesn't exist on the file system
	 * @throws ConfigurationException
	 *             a specific property value is out of spec
	 */
	public Configuration(String name, String[] args) throws IOException, ConfigurationException {
		super(name, args);
	}

	public Configuration(String[] args) throws ConfigurationException {
		super(args);
	}

	public void registerVideoPlayer(VideoPlayerController videoPlayer) {
		videoPlayers.add(videoPlayer);
	}

	public void unregisterVideoPlayer(VideoPlayerController videoPlayer) {
		videoPlayers.remove(videoPlayer);
	}

	public Set<VideoPlayerController> getVideoPlayers() {
		return videoPlayers;
	}

	public void setShotTimerRowColor(Color c) {
		shotRowColor = Optional.ofNullable(c);
	}

	public Optional<Color> getShotTimerRowColor() {
		return shotRowColor;
	}

	public void registerRecordingCameraManager(CameraManager cm) {
		recordingManagers.add(cm);
	}

	public void unregisterRecordingCameraManager(CameraManager cm) {
		recordingManagers.remove(cm);
	}

	public void unregisterAllRecordingCameraManagers() {
		recordingManagers.clear();
	}

	public Set<CameraManager> getRecordingManagers() {
		return recordingManagers;
	}

	public void setSessionRecorder(SessionRecorder sessionRecorder) {
		this.sessionRecorder = Optional.ofNullable(sessionRecorder);
	}

	public Optional<SessionRecorder> getSessionRecorder() {
		return sessionRecorder;
	}

	public void setExercise(TrainingExercise exercise) {
		if (currentExercise != null) currentExercise.destroy();

		currentExercise = exercise;
	}

	public Optional<TrainingExercise> getExercise() {
		return Optional.ofNullable(currentExercise);
	}

	public void setPlugin(Plugin plugin) {
		currentPlugin = plugin;
	}

	public Optional<Plugin> getPlugin() {
		return Optional.ofNullable(currentPlugin);
	}
}
```

The `Settings` constructors never call these methods, so it's safe that the fields initialize after `super(...)` returns.

- [ ] **Step 6: Make camera code depend on `Settings` only**

```bash
cd /home/bfears/projects/ShootOFF
M=javafx-app/src/main/java/com/shootoff
perl -pi -e 's/\bConfiguration\.getConfig\(\)/Settings.getSettings()/g; s/^import com\.shootoff\.config\.Configuration;/import com.shootoff.config.Settings;/; s/\bConfiguration\b/Settings/g' \
  $M/camera/CameraManager.java $M/camera/CamerasSupervisor.java \
  $M/camera/shotdetection/ShotDetector.java $M/camera/shotdetection/JavaShotDetector.java \
  $M/camera/autocalibration/AutoCalibrationManager.java \
  $M/camera/processors/MalfunctionsProcessor.java $M/camera/processors/VirtualMagazineProcessor.java
```

`CameraManager.startRecordingShots()` asked the configuration for the session recorder, which is runtime state. The caller now passes the session name. In `CameraManager.java`, replace

```java
	public void startRecordingShots() {
		String sessionName = null;
		if (config.getSessionRecorder().isPresent()) {
			sessionName = config.getSessionRecorder().get().getSessionName();

			final File sessionVideoFolder = new File(System.getProperty("shootoff.home") + File.separator + "sessions"
					+ File.separator + config.getSessionRecorder().get().getSessionName());
```

with

```java
	/**
	 * Starts keeping video around each detected shot.
	 * 
	 * @param sessionName
	 *            the name of the session being recorded, if any; shot videos then go in a
	 *            folder of that name under <tt>sessions/</tt>
	 */
	public void startRecordingShots(Optional<String> sessionName) {
		String sessionFolderName = null;
		if (sessionName.isPresent()) {
			sessionFolderName = sessionName.get();

			final File sessionVideoFolder = new File(System.getProperty("shootoff.home") + File.separator + "sessions"
					+ File.separator + sessionName.get());
```

and, further down in the same method, replace

```java
			rollingRecorder = new RollingRecorder(".mp4", sessionName, cameraName, getFeedWidth(), getFeedHeight());
```

with

```java
			rollingRecorder = new RollingRecorder(".mp4", sessionFolderName, cameraName, getFeedWidth(), getFeedHeight());
```

In `javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java`, replace

```java
			cm.startRecordingShots();
```

with

```java
			cm.startRecordingShots(config.getSessionRecorder().map(SessionRecorder::getSessionName));
```

`SessionRecorder` is already imported there.

In `javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java`, replace `			final Point2D arenaPosition = config.getArenaPosition().get();` with `			final Point arenaPosition = config.getArenaPosition().get();`, and add `import com.shootoff.geom.Point;` after the last `import com.shootoff.` line. Its other `Point2D` uses (screen origin) stay JavaFX.

Check:

```bash
grep -rlw "Configuration" javafx-app/src/main/java/com/shootoff/camera javafx-app/src/main/java/com/shootoff/util
grep -rn "getSessionRecorder\|startRecordingShots()" javafx-app/src/main/java/com/shootoff/camera
```

Expected: the first prints only `.../cameratypes/PS3EyeCamera.java`, whose window title string `"PS3EYE Configuration"` moves out in Task 9. The second prints nothing.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.config.*' --tests 'com.shootoff.camera.TestMalfunctionsProcessor' --tests 'com.shootoff.camera.TestVirtualMagazineProcessor' --tests 'com.shootoff.gui.TestCanvasManagerSessionRecording' --console=plain`
Expected:
- `TestSettings` (2), `TestConfigurationSingleton` (2) and all of `TestConfiguration` pass
- the processor tests and the session-recording test pass

- [ ] **Step 8: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 4.

```bash
git add javafx-app/src/main/java/com/shootoff/config \
  javafx-app/src/main/java/com/shootoff/gui/CalibrationConfigurator.java \
  javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java \
  javafx-app/src/main/java/com/shootoff/gui/controller/PreferencesController.java \
  javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorSlide.java \
  javafx-app/src/main/java/com/shootoff/gui/pane/ExerciseSlide.java \
  javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java \
  javafx-app/src/main/java/com/shootoff/camera \
  javafx-app/src/test/java/com/shootoff/camera/TestAutoCalibration.java \
  javafx-app/src/test/java/com/shootoff/config/TestSettings.java \
  javafx-app/src/test/java/com/shootoff/config/TestConfigurationSingleton.java
git status --short   # the CalibrationOption and Configuration→Settings renames are already staged by git mv
git commit -m "Split Configuration into UI-neutral Settings and the JavaFX app's runtime state"
git log -1 --format=%B
```

`git status` must show `Configuration.java` as renamed to `Settings.java` plus a new `Configuration.java`, or as modified with `Settings.java` added. Either is fine: `git log --follow` on `Settings.java` still finds the history. `shootoff.properties` must stay unstaged.

---

### Task 6: Calibration, perspective and projection bounds use core geometry

**Files:**
- Create: `javafx-app/src/main/java/com/shootoff/gui/FxGeometry.java`, `javafx-app/src/test/java/com/shootoff/gui/TestFxGeometry.java`
- Modify (all JavaFX geometry → `Rect`/`Size`), under `javafx-app/src/main/java/com/shootoff/camera/`:
  - `CameraCalibrationListener.java`
  - `CameraManager.java`
  - `CameraView.java`
  - `shotdetection/ShotDetector.java`
  - `perspective/PerspectiveManager.java`
  - `autocalibration/AutoCalibrationManager.java`
- Modify (UI side of the same values), under `javafx-app/src/main/java/com/shootoff/`:
  - `gui/CanvasManager.java` (`Bounds` only)
  - `gui/MirroredCanvasManager.java` (`Bounds` only)
  - `gui/CalibrationManager.java`
  - `gui/pane/TargetDistancePane.java`
  - `gui/pane/ProjectorArenaPane.java:680,683`
  - `plugins/ProjectorTrainingExerciseBase.java:359,363`
- Test (type swaps), under `javafx-app/src/test/java/com/shootoff/camera/`:
  - `MockCameraManager.java`
  - `ShotDetectionTestor.java`
  - `TestCameraManagerDark.java`
  - `TestAutoCalibration.java`
  - `perspective/TestPerspectiveManager.java`

  and `javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetCommands.java`

**Interfaces:**
- Consumes: `com.shootoff.geom.Rect`, `Size` (Task 2).
- Produces:
  - `CameraCalibrationListener.calibrate(Rect arenaBounds, Optional<Size> perspectivePaperDims, boolean calibratedFromCanvas, long frameDelay)`
  - `CameraManager.setProjectionBounds(Rect)`, `CameraManager.getProjectionBounds() → Optional<Rect>`
  - `CameraView.updateBackground(BufferedImage, Optional<Rect>)`
  - `PerspectiveManager`: every constructor and `isCameraSupported`/`setProjectorResolution` take `Rect`/`Size`; `calculateObjectSize(...) → Optional<Size>`
  - `AutoCalibrationManager.getBoundsResult() → Rect`, `calibrateFrame(...) → Optional<Rect>`, `findPaperPattern(...) → Optional<Size>`, `getPaperDimensions() → Optional<Size>`
  - `CanvasManager.translateCameraToCanvas(Rect) → Rect`, `translateCanvasToCamera(Rect) → Rect`, `setProjectorArena(ProjectorArenaPane, Rect)`
  - `com.shootoff.gui.FxGeometry.toRect(javafx.geometry.Bounds) → Rect` and `toSize(javafx.geometry.Dimension2D) → Size`, used only where a JavaFX node or stage supplies the value
- Unchanged: `ProjectorArenaPane.getArenaStageResolution()` still returns JavaFX `Dimension2D`. Callers convert it.

- [ ] **Step 1: Write the failing test**

Create `javafx-app/src/test/java/com/shootoff/gui/TestFxGeometry.java`:

```java
package com.shootoff.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

import javafx.geometry.BoundingBox;
import javafx.geometry.Dimension2D;

class TestFxGeometry {
	@Test
	void toRectKeepsPositionAndSize() {
		assertEquals(new Rect(1.5, 2.5, 30, 40), FxGeometry.toRect(new BoundingBox(1.5, 2.5, 30, 40)));
	}

	@Test
	void toSizeKeepsWidthAndHeight() {
		assertEquals(new Size(1280, 720), FxGeometry.toSize(new Dimension2D(1280, 720)));
	}

	// Projection bounds decide which shots are detected and which go to the arena, so the core
	// type must agree with JavaFX everywhere, including edges and degenerate boxes
	@Test
	void rectContainsMatchesJavaFxBoundingBox() {
		final double[][] boxes = { { 109, 104, 379, 297 }, { 0, 0, 64, 48 }, { 5, 5, 0, 0 }, { 10, 10, -1, 20 },
				{ 10.25, 3.75, 7.5, 0.5 } };

		for (final double[] b : boxes) {
			final BoundingBox fx = new BoundingBox(b[0], b[1], b[2], b[3]);
			final Rect rect = FxGeometry.toRect(fx);

			for (double x = b[0] - 2; x <= b[0] + Math.abs(b[2]) + 2; x += 0.25) {
				for (double y = b[1] - 2; y <= b[1] + Math.abs(b[3]) + 2; y += 0.25) {
					if (fx.contains(x, y) != rect.contains(x, y)) {
						throw new AssertionError("Rect and BoundingBox disagree at (" + x + ", " + y + ") for " + fx);
					}
				}
			}
		}
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.TestFxGeometry' --console=plain`
Expected: compilation FAILS with `cannot find symbol: variable FxGeometry`.

- [ ] **Step 3: Create `FxGeometry`**

Create `javafx-app/src/main/java/com/shootoff/gui/FxGeometry.java` (GPL header, then):

```java
package com.shootoff.gui;

import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;

/**
 * Converts JavaFX geometry to the UI-neutral core types where a JavaFX node or stage hands a
 * value to core code.
 */
public final class FxGeometry {
	private FxGeometry() {}

	public static Rect toRect(Bounds bounds) {
		return new Rect(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
	}

	public static Size toSize(Dimension2D dimension) {
		return new Size(dimension.getWidth(), dimension.getHeight());
	}
}
```

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.TestFxGeometry' --console=plain`
Expected: 3 PASS.

- [ ] **Step 4: Swap the types**

`AutoCalibrationManager` imports OpenCV's `Size`, so qualify that one first:

```bash
cd /home/bfears/projects/ShootOFF
M=javafx-app/src/main/java/com/shootoff
T=javafx-app/src/test/java/com/shootoff
perl -0pi -e 's/^import org\.opencv\.core\.Size;\n//m; s/\bnew Size\(/new org.opencv.core.Size(/g; s/static final Size boardSize/static final org.opencv.core.Size boardSize/' \
  $M/camera/autocalibration/AutoCalibrationManager.java
grep -n "\bSize\b" $M/camera/autocalibration/AutoCalibrationManager.java
```

Expected: every remaining `Size` is written `org.opencv.core.Size` (4 constructor calls and the `boardSize` field's type).

Define the two substitutions and apply them:

```bash
cd /home/bfears/projects/ShootOFF
M=javafx-app/src/main/java/com/shootoff
T=javafx-app/src/test/java/com/shootoff
TO_RECT='s/^import javafx\.geometry\.BoundingBox;\n//m if /^import javafx\.geometry\.Bounds;$/m; s/^import javafx\.geometry\.(?:Bounds|BoundingBox);$/import com.shootoff.geom.Rect;/m; s/\bBoundingBox\b/Rect/g; s/\bBounds\b/Rect/g;'
TO_SIZE='s/^import javafx\.geometry\.Dimension2D;$/import com.shootoff.geom.Size;/m; s/\bDimension2D\b/Size/g;'

# Camera code and the tests that feed it: every Bounds/BoundingBox/Dimension2D is projection or perspective geometry
perl -0pi -e "$TO_RECT $TO_SIZE" \
  $M/camera/CameraCalibrationListener.java $M/camera/CameraManager.java $M/camera/CameraView.java \
  $M/camera/shotdetection/ShotDetector.java $M/camera/perspective/PerspectiveManager.java \
  $M/camera/autocalibration/AutoCalibrationManager.java $M/gui/CalibrationManager.java \
  $T/camera/MockCameraManager.java $T/camera/ShotDetectionTestor.java $T/camera/TestCameraManagerDark.java \
  $T/camera/TestAutoCalibration.java $T/camera/perspective/TestPerspectiveManager.java \
  $T/gui/targets/TestTargetCommands.java

# Canvas managers: Bounds are projection bounds, but Dimension2D/Point2D are target geometry and stay JavaFX
perl -0pi -e "$TO_RECT" $M/gui/CanvasManager.java $M/gui/MirroredCanvasManager.java

# Only perspective results
perl -0pi -e "$TO_SIZE" $M/gui/pane/TargetDistancePane.java $M/plugins/ProjectorTrainingExerciseBase.java
```

In `ProjectorArenaPane.java`, only the perspective result changes, because `getArenaStageResolution()` stays JavaFX. Replace

```java
				final Optional<Dimension2D> targetDimensions = pm.calculateObjectSize(width, height, distance);
```

with

```java
				final Optional<Size> targetDimensions = pm.calculateObjectSize(width, height, distance);
```

and `					final Dimension2D d = targetDimensions.get();` with `					final Size d = targetDimensions.get();`. Add `import com.shootoff.geom.Size;` next to `import com.shootoff.geom.Point;` (added in Task 5).

In `CalibrationManager.java`, convert the two places where JavaFX supplies the value:

```bash
cd /home/bfears/projects/ShootOFF
M=javafx-app/src/main/java/com/shootoff
perl -pi -e 's/calibrate\(calibrationTarget\.get\(\)\.getTargetGroup\(\)\.getBoundsInParent\(\),/calibrate(FxGeometry.toRect(calibrationTarget.get().getTargetGroup().getBoundsInParent()),/; s/arenaPane\.getArenaStageResolution\(\)/FxGeometry.toSize(arenaPane.getArenaStageResolution())/g' $M/gui/CalibrationManager.java
grep -c "FxGeometry" $M/gui/CalibrationManager.java
```

Expected count: `4` (one `toRect` and three `toSize`).

- [ ] **Step 5: Compile and check no JavaFX geometry is left in camera code**

```bash
./gradlew :javafx-app:compileJava :javafx-app:compileTestJava --console=plain
grep -rn "javafx.geometry" javafx-app/src/main/java/com/shootoff/camera
grep -rn "import com.shootoff.geom" javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java javafx-app/src/main/java/com/shootoff/gui/MirroredCanvasManager.java
```

Expected:
- `BUILD SUCCESSFUL`
- the first `grep` prints nothing
- the second shows one `Rect` import in each canvas manager, while `Dimension2D`/`Point2D` imports remain for target geometry

If `CanvasManager` fails to compile because `Dimension2D`/`Point2D` imports were removed, the perl touched the wrong file set. Restore it with `git checkout -- <file>` and rerun only `TO_RECT` on it.

- [ ] **Step 6: Run the affected tests**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.camera.*' --tests 'com.shootoff.gui.*' --console=plain`
Expected: all PASS. This includes `TestPerspectiveManager`, `TestAutoCalibration`, `TestCameraManagerDark` (projection-bounds videos), `TestTargetCommands` and `TestFxGeometry`.

- [ ] **Step 7: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 3.

```bash
git add javafx-app/src/main/java/com/shootoff/gui/FxGeometry.java javafx-app/src/test/java/com/shootoff/gui/TestFxGeometry.java \
  javafx-app/src/main/java/com/shootoff/camera \
  javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java javafx-app/src/main/java/com/shootoff/gui/MirroredCanvasManager.java \
  javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java \
  javafx-app/src/main/java/com/shootoff/gui/pane/TargetDistancePane.java javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java \
  javafx-app/src/main/java/com/shootoff/plugins/ProjectorTrainingExerciseBase.java \
  javafx-app/src/test/java/com/shootoff/camera javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetCommands.java
git commit -m "Use core geometry for calibration, perspective and projection bounds"
git log -1 --format=%B
```

---

### Task 7: `CameraView` and `CameraManager` lose their JavaFX types

**Files:**
- Create: `core/src/main/java/com/shootoff/camera/DiagnosticMessage.java`
- Modify: `javafx-app/src/main/java/com/shootoff/camera/CameraView.java` (rewritten)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/CameraManager.java` (imports; `getCurrentFrame` ~461; diagnostic warnings ~710–750)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` (six `@Override`s; new `addDiagnosticWarning`)
- Modify: `javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java` (~118, ~135, ~612)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/pane/TargetSlide.java` (~84, ~162, ~169)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java` (~272)
- Create: `javafx-app/src/test/java/com/shootoff/camera/RecordingCameraView.java`, `javafx-app/src/test/java/com/shootoff/camera/TestCameraManagerDiagnostics.java` (both move to `core` in Task 10)

**Interfaces:**
- Consumes: `Rect` (Task 2); `CameraView.updateBackground(BufferedImage, Optional<Rect>)` (Task 6).
- Produces:
  - `com.shootoff.camera.DiagnosticMessage` (functional interface; `void remove()`), created in `core`.
  - `CameraView` (still `extends com.shootoff.Closeable`) is exactly:
    - `addShot(DisplayShot, boolean)` (changes in Task 8)
    - `DiagnosticMessage addDiagnosticWarning(String message)`
    - `clearShots()`, `close()`, `reset()`
    - `setCameraManager(CameraManager)`
    - `updateBackground(BufferedImage, Optional<Rect>)`
  - Removed from `CameraView`: `addChild(Node)`, `removeChild(Node)`, `addTarget(File)`, `addTarget(Target)`, `addDiagnosticMessage(String, Color)`, `removeDiagnosticMessage(Label)`. `CanvasManager` keeps all of them as its own public methods. JavaFX callers that held a `CameraView` cast to `CanvasManager`, the only implementation, as `CalibrationManager` and `ShootOFFController` already do.
  - `CameraManager.getCurrentFrame() → BufferedImage`. `TargetSlide` converts it with `SwingFXUtils`.
  - Test helper `RecordingCameraView implements CameraView`:
    - `diagnosticWarnings() → List<String>`
    - `awaitRemovedWarnings(int count, long timeout, TimeUnit unit) → boolean`
    - `awaitShot(long timeout, TimeUnit unit) → Optional<DisplayShot>` (becomes `Optional<ScaledShot>` in Task 8)

- [ ] **Step 1: Write the failing tests**

Create `javafx-app/src/test/java/com/shootoff/camera/RecordingCameraView.java`:

```java
package com.shootoff.camera;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.geom.Rect;

/**
 * A camera view with no UI that records what the camera pipeline sends it.
 */
public class RecordingCameraView implements CameraView {
	private final List<String> diagnosticWarnings = new CopyOnWriteArrayList<>();
	private final Semaphore removedWarnings = new Semaphore(0);
	private final BlockingQueue<DisplayShot> shots = new LinkedBlockingQueue<>();

	@Override
	public void addShot(DisplayShot shot, boolean isMirroredShot) {
		shots.add(shot);
	}

	@Override
	public DiagnosticMessage addDiagnosticWarning(String message) {
		diagnosticWarnings.add(message);
		return removedWarnings::release;
	}

	@Override
	public void clearShots() {}

	@Override
	public void close() {}

	@Override
	public void reset() {}

	@Override
	public void setCameraManager(CameraManager cameraManager) {}

	@Override
	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds) {}

	public List<String> diagnosticWarnings() {
		return List.copyOf(diagnosticWarnings);
	}

	public boolean awaitRemovedWarnings(int count, long timeout, TimeUnit unit) throws InterruptedException {
		return removedWarnings.tryAcquire(count, timeout, unit);
	}

	public Optional<DisplayShot> awaitShot(long timeout, TimeUnit unit) throws InterruptedException {
		return Optional.ofNullable(shots.poll(timeout, unit));
	}
}
```

Create `javafx-app/src/test/java/com/shootoff/camera/TestCameraManagerDiagnostics.java`:

```java
package com.shootoff.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;

class TestCameraManagerDiagnostics {
	private RecordingCameraView view;
	private CameraManager cameraManager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		new Configuration(new String[0]);
		view = new RecordingCameraView();
		cameraManager = new CameraManager(new MockCamera(), null, view);
	}

	@Test
	void brightnessWarningIsShownThenRemoved() throws InterruptedException {
		cameraManager.showBrightnessWarning();

		assertEquals(List.of("Warning: Excessive brightness"), view.diagnosticWarnings());
		assertTrue(view.awaitRemovedWarnings(1, 5, TimeUnit.SECONDS), "warning was never removed");
	}

	@Test
	void motionWarningIsShownThenRemoved() throws InterruptedException {
		cameraManager.showMotionWarning();

		assertEquals(List.of("Warning: Excessive motion -- Try reducing the camera exposure setting"),
				view.diagnosticWarnings());
		assertTrue(view.awaitRemovedWarnings(1, 5, TimeUnit.SECONDS), "warning was never removed");
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :javafx-app:compileTestJava --console=plain`
Expected: compilation FAILS: `DiagnosticMessage` cannot be found, and `RecordingCameraView is not abstract and does not override abstract method addChild(Node)`.

- [ ] **Step 3: Add `DiagnosticMessage` to core and rewrite `CameraView`**

Create `core/src/main/java/com/shootoff/camera/DiagnosticMessage.java` (GPL header, then):

```java
package com.shootoff.camera;

/**
 * A diagnostic message a camera view is showing, e.g. "Warning: Excessive brightness".
 */
@FunctionalInterface
public interface DiagnosticMessage {
	/**
	 * Stops showing the message.
	 */
	void remove();
}
```

Replace the whole content of `javafx-app/src/main/java/com/shootoff/camera/CameraView.java` with:

```java
package com.shootoff.camera;

import java.awt.image.BufferedImage;
import java.util.Optional;

import com.shootoff.Closeable;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.geom.Rect;

/**
 * Shows one camera's frames, shots and diagnostic messages. The JavaFX implementation is
 * com.shootoff.gui.CanvasManager. No UI toolkit types appear here, so camera code can live in
 * core.
 * 
 * @author phrack
 */
public interface CameraView extends Closeable {
	public void addShot(DisplayShot shot, boolean isMirroredShot);

	/**
	 * Shows a warning until the returned message is removed.
	 */
	public DiagnosticMessage addDiagnosticWarning(String message);

	public void clearShots();

	@Override
	public void close();

	public void reset();

	public void setCameraManager(CameraManager cameraManager);

	public void updateBackground(BufferedImage frame, Optional<Rect> projectionBounds);
}
```

- [ ] **Step 4: Update `CameraManager`**

- Remove the imports `javafx.embed.swing.SwingFXUtils`, `javafx.scene.control.Label`, `javafx.scene.image.Image` and `javafx.scene.paint.Color`.
- Replace

  ```java
  	public Image getCurrentFrame() {
  		return SwingFXUtils.toFXImage(camera.getBufferedImage(), null);
  	}
  ```

  with

  ```java
  	public BufferedImage getCurrentFrame() {
  		return camera.getBufferedImage();
  	}
  ```

- Replace `	private Label brightnessDiagnosticWarning = null;` with `	private DiagnosticMessage brightnessDiagnosticWarning = null;`, and `	private Label motionDiagnosticWarning = null;` with `	private DiagnosticMessage motionDiagnosticWarning = null;`.
- Replace `			brightnessDiagnosticWarning = cameraView.addDiagnosticMessage("Warning: Excessive brightness", Color.RED);` with `			brightnessDiagnosticWarning = cameraView.addDiagnosticWarning("Warning: Excessive brightness");`.
- Replace

  ```java
  			motionDiagnosticWarning = cameraView.addDiagnosticMessage(
  					"Warning: Excessive motion -- Try reducing the camera exposure setting", Color.RED);
  ```

  with

  ```java
  			motionDiagnosticWarning = cameraView
  					.addDiagnosticWarning("Warning: Excessive motion -- Try reducing the camera exposure setting");
  ```

- Replace `				cameraView.removeDiagnosticMessage(brightnessDiagnosticWarning);` with `				brightnessDiagnosticWarning.remove();`, and `				cameraView.removeDiagnosticMessage(motionDiagnosticWarning);` with `				motionDiagnosticWarning.remove();`.

Check: `grep -n "javafx" javafx-app/src/main/java/com/shootoff/camera/CameraManager.java javafx-app/src/main/java/com/shootoff/camera/CameraView.java` prints nothing.

- [ ] **Step 5: Update the JavaFX side**

In `CanvasManager.java`:
- Delete the `@Override` line directly above each of these six methods (they stay, now as `CanvasManager`'s own methods): `public boolean addChild(Node c)`, `public boolean removeChild(Node c)`, `public Label addDiagnosticMessage(String message, Color backgroundColor)`, `public void removeDiagnosticMessage(Label diagnosticLabel)`, `public Optional<Target> addTarget(File targetFile)` and `public Target addTarget(Target newTarget)`.
- Add `import com.shootoff.camera.DiagnosticMessage;` after `import com.shootoff.camera.CameraView;`.
- Add this method directly after `removeDiagnosticMessage(Label)`:

```java
	@Override
	public DiagnosticMessage addDiagnosticWarning(String message) {
		final Label diagnosticLabel = addDiagnosticMessage(message, Color.RED);
		return () -> removeDiagnosticMessage(diagnosticLabel);
	}
```

`MirroredCanvasManager`'s and `MockCanvasManager`'s `@Override`s on `addTarget` still override `CanvasManager`, so they stay.

In `TrainingExerciseBase.java`, add `import com.shootoff.gui.CanvasManager;` after `import com.shootoff.config.Configuration;`, then:
- replace `			arenaView.addChild(exerciseLabel);` with `			((CanvasManager) arenaView).addChild(exerciseLabel);`
- replace `			cv.addChild(exerciseLabel);` with `			((CanvasManager) cv).addChild(exerciseLabel);`
- replace `			entry.getKey().removeChild(entry.getValue());` with `			((CanvasManager) entry.getKey()).removeChild(entry.getValue());`

In `TargetSlide.java`:
- Add `import com.shootoff.gui.CanvasManager;` after `import com.shootoff.camera.CameraManager;`, and `import javafx.embed.swing.SwingFXUtils;` before `import javafx.fxml.FXMLLoader;`.
- Replace `					currentFrame = currentCamera.getCurrentFrame();` with `					currentFrame = SwingFXUtils.toFXImage(currentCamera.getCurrentFrame(), null);`.
- Replace `				final Image currentFrame = currentCamera.getCurrentFrame();` with `				final Image currentFrame = SwingFXUtils.toFXImage(currentCamera.getCurrentFrame(), null);`.
- Replace `			cameraViews.getSelectedCameraView().addTarget(ref);` with `			((CanvasManager) cameraViews.getSelectedCameraView()).addTarget(ref);`.

In `CalibrationManager.java`, replace `			calibratingCameraManager.getCameraView().addTarget(calibrationTarget.get());` with `			calibratingCanvasManager.addTarget(calibrationTarget.get());`. The field `calibratingCanvasManager` is that same view, cast in the constructor.

Check that nothing else used the removed `CameraView` methods:

```bash
./gradlew :javafx-app:compileJava :javafx-app:compileTestJava --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.camera.TestCameraManagerDiagnostics' --tests 'com.shootoff.gui.*' --tests 'com.shootoff.plugins.*' --console=plain`
Expected: `TestCameraManagerDiagnostics` 2 PASS, and all `gui`/`plugins` tests PASS (exercise labels still reach the canvas).

- [ ] **Step 7: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 2.

```bash
git add core/src/main/java/com/shootoff/camera/DiagnosticMessage.java \
  javafx-app/src/main/java/com/shootoff/camera/CameraView.java javafx-app/src/main/java/com/shootoff/camera/CameraManager.java \
  javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java \
  javafx-app/src/main/java/com/shootoff/gui/pane/TargetSlide.java javafx-app/src/main/java/com/shootoff/plugins/TrainingExerciseBase.java \
  javafx-app/src/test/java/com/shootoff/camera/RecordingCameraView.java \
  javafx-app/src/test/java/com/shootoff/camera/TestCameraManagerDiagnostics.java
git commit -m "Remove JavaFX types from CameraView and CameraManager"
git log -1 --format=%B
```

---

### Task 8: Shot detection emits `ScaledShot` data; the view makes the `DisplayShot` marker

**Files:**
- Create: `javafx-app/src/main/java/com/shootoff/camera/shot/ScaledShot.java` (moves to `core` in Task 10)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/shot/DisplayShot.java` (rewritten on top of `ScaledShot`)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java` (rewritten)
- Modify: `javafx-app/src/main/java/com/shootoff/camera/CameraView.java` (`addShot` signature)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` (new `addShot(ScaledShot)`; `@Override` removed from `addShot(DisplayShot, boolean)` ~line 579)
- Modify: `javafx-app/src/test/java/com/shootoff/camera/RecordingCameraView.java`
- Create: `javafx-app/src/test/java/com/shootoff/camera/shot/TestScaledShot.java`, `javafx-app/src/test/java/com/shootoff/camera/shotdetection/TestShotDetector.java` (both move to `core` in Task 10)
- Test: `javafx-app/src/test/java/com/shootoff/camera/shot/TestShot.java`, `javafx-app/src/test/java/com/shootoff/gui/TestCanvasManager.java` (one new test each)

**Interfaces:**
- Consumes: `Settings.getSettings()` (Task 5); `CameraManager.getProjectionBounds() → Optional<Rect>` (Task 6); `RecordingCameraView` (Task 7).
- Produces `com.shootoff.camera.shot.ScaledShot extends BoundsShot`: the display-scaling half of the old `DisplayShot`, with no marker. It has:
  - constructors `(ShotColor, double x, double y, long timestamp, int frame)`, `(ShotColor, double x, double y, long timestamp)` and `(Shot)`. The last copies bounds and display values from a `BoundsShot`/`ScaledShot`.
  - `setDisplayVals(int displayWidth, int displayHeight, int feedWidth, int feedHeight)`
  - `getX()`/`getY()`, which return display coordinates once scaled, otherwise the `BoundsShot` values
  - `getDisplayX()`, `getDisplayY()`
- Produces `CameraView.addShot(ScaledShot shot)`, which replaces `addShot(DisplayShot, boolean)`. It is called on the "Shot Notifier" thread, and the view builds its own marker. `CanvasManager.addShot(ScaledShot)` does `addShot(new DisplayShot(shot, config.getMarkerRadius()), false)`.
- Produces `DisplayShot extends ScaledShot`. Its constructors, `getMarker()`, `getDisplayMarker()`, `setDisplayVals(...)` (re-creates the marker), `toPaint`/`getPaintColor` and every getter keep their signatures and behavior. `ArenaShot` is unchanged.
- `ShotDetector` no longer references `DisplayShot`. `submitShot(ScaledShot)` is its protected hook.
- `RecordingCameraView.awaitShot(...) → Optional<ScaledShot>`.

- [ ] **Step 1: Write the failing tests**

In `RecordingCameraView.java`:
- Replace `import com.shootoff.camera.shot.DisplayShot;` with `import com.shootoff.camera.shot.ScaledShot;`.
- Replace `BlockingQueue<DisplayShot>` with `BlockingQueue<ScaledShot>` and `Optional<DisplayShot> awaitShot` with `Optional<ScaledShot> awaitShot`.
- Replace

  ```java
  	@Override
  	public void addShot(DisplayShot shot, boolean isMirroredShot) {
  		shots.add(shot);
  	}
  ```

  with

  ```java
  	@Override
  	public void addShot(ScaledShot shot) {
  		shots.add(shot);
  	}
  ```

Create `javafx-app/src/test/java/com/shootoff/camera/shot/TestScaledShot.java`:

```java
package com.shootoff.camera.shot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestScaledShot {
	@Test
	void scalesTheBoundsAdjustedPositionToTheDisplay() {
		final ScaledShot shot = new ScaledShot(ShotColor.GREEN, 100, 100, 50, 3);
		shot.adjustBounds(10, 10);

		shot.setDisplayVals(100, 100, 200, 200);

		assertEquals(55, shot.getX(), 0.001);
		assertEquals(55, shot.getY(), 0.001);
		assertEquals(55, shot.getDisplayX(), 0.001);
		assertEquals(110, shot.getBoundsX(), 0.001);
		assertEquals(100, shot.getOrigX(), 0.001);
		assertEquals(50, shot.getTimestamp());
		assertEquals(3, shot.getFrame());
		assertEquals(ShotColor.GREEN, shot.getColor());
	}

	@Test
	void unscaledShotReportsItsBoundsPosition() {
		final ScaledShot shot = new ScaledShot(ShotColor.RED, 5, 6, 0);

		assertEquals(5, shot.getX(), 0.001);
		assertEquals(6, shot.getDisplayY(), 0.001);
	}

	@Test
	void copyKeepsBoundsAndDisplayValues() {
		final ScaledShot shot = new ScaledShot(ShotColor.RED, 100, 100, 0);
		shot.adjustBounds(10, 10);
		shot.setDisplayVals(100, 100, 200, 200);

		final ScaledShot copy = new ScaledShot(shot);

		assertEquals(55, copy.getX(), 0.001);
		assertEquals(110, copy.getBoundsX(), 0.001);
	}
}
```

Create `javafx-app/src/test/java/com/shootoff/camera/shotdetection/TestShotDetector.java`:

```java
package com.shootoff.camera.shotdetection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.MockCamera;
import com.shootoff.camera.RecordingCameraView;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.geom.Rect;

class TestShotDetector {
	private Configuration config;
	private RecordingCameraView view;
	private CameraManager cameraManager;

	@BeforeAll
	static void loadOpenCv() {
		Loader.load(opencv_java.class);
	}

	@BeforeEach
	void setUp() throws ConfigurationException {
		config = new Configuration(new String[0]);
		view = new RecordingCameraView();
		// MockCamera is never opened, so the feed stays at the default 640x480
		cameraManager = new CameraManager(new MockCamera(), null, view);
	}

	private ScaledShot nextShot() throws InterruptedException {
		return view.awaitShot(5, TimeUnit.SECONDS).orElseThrow(() -> new AssertionError("no shot reached the view"));
	}

	@Test
	void detectedShotIsScaledToTheDisplay() throws InterruptedException {
		config.setDisplayResolution(1280, 960);

		cameraManager.injectShot(ShotColor.GREEN, 100, 50, true);

		final ScaledShot shot = nextShot();
		// Plain data: markers are the view's business
		assertEquals(ScaledShot.class, shot.getClass());
		assertEquals(ShotColor.GREEN, shot.getColor());
		assertEquals(200, shot.getX(), 0.001);
		assertEquals(100, shot.getY(), 0.001);
		assertEquals(100, shot.getBoundsX(), 0.001);
	}

	@Test
	void clickToShootShotIsNotScaled() throws InterruptedException {
		config.setDisplayResolution(1280, 960);

		cameraManager.injectShot(ShotColor.RED, 100, 50, false);

		final ScaledShot shot = nextShot();
		assertEquals(100, shot.getX(), 0.001);
		assertEquals(50, shot.getY(), 0.001);
	}

	@Test
	void ignoredLaserColorIsDropped() throws InterruptedException {
		config.setIgnoreLaserColor(true);
		config.setIgnoreLaserColorName("green");

		cameraManager.injectShot(ShotColor.GREEN, 100, 50, true);
		assertEquals(Optional.empty(), view.awaitShot(500, TimeUnit.MILLISECONDS));

		cameraManager.injectShot(ShotColor.RED, 300, 300, true);
		assertEquals(ShotColor.RED, nextShot().getColor());
	}

	@Test
	void shotInsideProjectionIsOffsetByTheProjectionOrigin() throws InterruptedException {
		cameraManager.setLimitDetectProjection(true);
		cameraManager.setProjectionBounds(new Rect(100, 40, 200, 200));

		// JavaShotDetector sees only the projection sub-image, so its coordinates are relative to it
		cameraManager.injectShot(ShotColor.GREEN, 10, 10, true);

		final ScaledShot shot = nextShot();
		assertEquals(110, shot.getX(), 0.001);
		assertEquals(50, shot.getY(), 0.001);
		assertEquals(10, shot.getOrigX(), 0.001);
	}
}
```

In `TestShot.java` (JUnit 4), add this method inside the class:

```java
	@Test
	public void testDisplayShotFromScaledShotKeepsDisplayPosition() {
		ScaledShot scaled = new ScaledShot(ShotColor.RED, 100, 100, 50, 0);
		scaled.adjustBounds(10, 10);
		scaled.setDisplayVals(100, 100, 200, 200);

		DisplayShot dshot = new DisplayShot(scaled, 5);

		assertEquals(55.0, dshot.getX(), .1);
		assertEquals(55.0, dshot.getMarker().getCenterX(), .1);
		assertEquals(55.0, dshot.getMarker().getCenterY(), .1);
		assertEquals(5.0, dshot.getMarker().getRadiusX(), .1);
		assertEquals(110.0, dshot.getBoundsX(), .1);
	}
```

In `TestCanvasManager.java` (JUnit 4, runs on the JavaFX thread), add `import com.shootoff.camera.shot.ScaledShot;` and this method:

```java
	@Test
	public void testDetectedShotBecomesMarkerAtItsDisplayPosition() {
		final ScaledShot detected = new ScaledShot(ShotColor.GREEN, 100, 50, 0);
		detected.setDisplayVals(1280, 960, 640, 480);

		cm.addShot(detected);

		final DisplayShot shown = cm.getShots().get(cm.getShots().size() - 1);
		assertEquals(200, shown.getMarker().getCenterX(), 0.1);
		assertEquals(100, shown.getMarker().getCenterY(), 0.1);
		assertEquals(config.getMarkerRadius(), shown.getMarker().getRadiusX(), 0.1);
		assertEquals(ShotColor.GREEN, shown.getColor());
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :javafx-app:compileTestJava --console=plain`
Expected: compilation FAILS with `cannot find symbol: class ScaledShot`.

- [ ] **Step 3: Create `ScaledShot`**

Create `javafx-app/src/main/java/com/shootoff/camera/shot/ScaledShot.java` (GPL header, then):

```java
package com.shootoff.camera.shot;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.Shot;

/**
 * A {@link BoundsShot} that can be scaled from camera-feed to display coordinates. This is the
 * shot data shot detection hands to a camera view; each view creates its own marker for it.
 * 
 * @author cbdmaul
 */
public class ScaledShot extends BoundsShot {
	private static final Logger logger = LoggerFactory.getLogger(ScaledShot.class);

	private Optional<Double> displayX = Optional.empty();
	private Optional<Double> displayY = Optional.empty();

	public ScaledShot(ShotColor color, double x, double y, long timestamp, int frame) {
		super(color, x, y, timestamp, frame);
	}

	public ScaledShot(ShotColor color, double x, double y, long timestamp) {
		super(color, x, y, timestamp);
	}

	public ScaledShot(Shot shot) {
		super(shot);

		if (shot instanceof ScaledShot) {
			displayX = ((ScaledShot) shot).displayX;
			displayY = ((ScaledShot) shot).displayY;
		}
	}

	public void setDisplayVals(int displayWidth, int displayHeight, int feedWidth, int feedHeight) {
		final double scaleX = (double) displayWidth / (double) feedWidth;
		final double scaleY = (double) displayHeight / (double) feedHeight;

		double scaledX, scaledY;
		if (displayX.isPresent()) {
			scaledX = displayX.get() * scaleX;
			scaledY = displayY.get() * scaleY;
		} else {
			scaledX = super.getX() * scaleX;
			scaledY = super.getY() * scaleY;
		}

		if (logger.isTraceEnabled()) {
			logger.trace("setTranslation {} {} - {} {} to {} {}", scaleX, scaleY, super.getX(), super.getY(), scaledX,
					scaledY);
		}

		displayX = Optional.of(scaledX);
		displayY = Optional.of(scaledY);
	}

	@Override
	public double getX() {
		if (!displayX.isPresent()) return super.getX();
		return displayX.get();
	}

	@Override
	public double getY() {
		if (!displayY.isPresent()) return super.getY();
		return displayY.get();
	}

	public double getDisplayX() {
		if (!displayX.isPresent()) return super.getX();
		return displayX.get();
	}

	public double getDisplayY() {
		if (!displayY.isPresent()) return super.getY();
		return displayY.get();
	}
}
```

- [ ] **Step 4: Rebuild `DisplayShot` on `ScaledShot`**

Replace the whole content of `javafx-app/src/main/java/com/shootoff/camera/shot/DisplayShot.java` with the code below. It keeps every public constructor and method from Task 4. The display fields and scaling now come from `ScaledShot`.

```java
package com.shootoff.camera.shot;

import com.shootoff.camera.Shot;

import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;

/**
 * A {@link ScaledShot} with the JavaFX marker that shows it on a canvas.
 * 
 * @author cbdmaul
 */
public class DisplayShot extends ScaledShot {
	protected Ellipse marker;

	public DisplayShot(ShotColor color, double x, double y, long timestamp, int frame, int markerRadius) {
		super(color, x, y, timestamp, frame);
		marker = new Ellipse(x, y, markerRadius, markerRadius);
		marker.setFill(toPaint(color));
	}

	public DisplayShot(ShotColor color, double x, double y, long timestamp, int markerRadius) {
		super(color, x, y, timestamp);
		marker = new Ellipse(x, y, markerRadius, markerRadius);
		marker.setFill(toPaint(color));
	}

	/**
	 * Creates the marker for <tt>shot</tt> at its display position (keeping any bounds and
	 * display values it already has).
	 */
	public DisplayShot(Shot shot, int markerRadius) {
		super(shot);
		marker = new Ellipse(getX(), getY(), markerRadius, markerRadius);
		marker.setFill(toPaint(color));
	}

	public DisplayShot(Shot shot, Ellipse marker) {
		super(shot);
		this.marker = marker;
	}

	/**
	 * @return the JavaFX paint used for markers of shots of <tt>color</tt>
	 */
	public static Color toPaint(ShotColor color) {
		return switch (color) {
		case RED -> Color.RED;
		case GREEN -> Color.GREEN;
		case INFRARED -> Color.ORANGE;
		};
	}

	public Color getPaintColor() {
		return toPaint(color);
	}

	public Ellipse getMarker() {
		return marker;
	}

	@Override
	public void setDisplayVals(int displayWidth, int displayHeight, int feedWidth, int feedHeight) {
		super.setDisplayVals(displayWidth, displayHeight, feedWidth, feedHeight);

		marker = new Ellipse(getDisplayX(), getDisplayY(), marker.radiusXProperty().get(),
				marker.radiusYProperty().get());
		marker.setFill(toPaint(color));
	}

	public Ellipse getDisplayMarker() {
		return this.marker;
	}
}
```

- [ ] **Step 5: Make detection emit `ScaledShot`, and the view build the marker**

In `CameraView.java`, replace `import com.shootoff.camera.shot.DisplayShot;` with `import com.shootoff.camera.shot.ScaledShot;`, and replace `	public void addShot(DisplayShot shot, boolean isMirroredShot);` with:

```java
	/**
	 * Receives a newly detected shot on a non-UI thread. The shot is already offset for the
	 * projection bounds and scaled to the display; the view creates its own marker for it.
	 */
	public void addShot(ScaledShot shot);
```

Keep a copy of the current file (`cp javafx-app/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java build/ShotDetector.before.java`), then replace its whole content with:

```java
package com.shootoff.camera.shotdetection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.CameraManager;
import com.shootoff.camera.CameraView;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ScaledShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Settings;
import com.shootoff.geom.Rect;

/**
 * This interface is implemented by classes that act as the entry point to some
 * implementation of shot detection.
 */
public abstract class ShotDetector {
	private static final Logger logger = LoggerFactory.getLogger(ShotDetector.class);

	private final CameraManager cameraManager;
	private final Settings config = Settings.getSettings();
	private final CameraView cameraView;

	public static boolean isSystemSupported() {
		return false;
	}

	public ShotDetector(final CameraManager cameraManager, final CameraView cameraView) {
		this.cameraManager = cameraManager;
		this.cameraView = cameraView;
	}

	public void reset() {}

	/**
	 * Notify the shot detector of the dimensions of webcam frames (e.g. the
	 * webcam's resolution). This method may be called at any time if the
	 * webcam's resolution is changed at runtime.
	 * 
	 * @param width
	 *            the width of frames in pixels
	 * @param height
	 *            the height of frames in pixels
	 */
	public abstract void setFrameSize(final int width, final int height);

	/**
	 * Alert the canvas tied to the camera the instantiation of this detector is
	 * tied to of a new shot. This method preprocesses the shot by ensuring it
	 * is not of an ignored color, it has the appropriate translation for
	 * projectors if it's a shot on the arena, it has the appropriate
	 * translation if the display resolution differs from the camera resolution,
	 * and it is not a duplicate shot.
	 * 
	 * @param color
	 *            the color of the detected shot (red or green)
	 * @param x
	 *            the exact x coordinate of the shot in the video frame it was
	 *            detected in
	 * @param y
	 *            the exact y coordinate of the shot in the video frame it was
	 *            detected in
	 * @param timestamp
	 *            the timestamp of the shot not adjusted for the shot timer
	 * @param scaleShot
	 *            <code>true</code> if the shot needs to be scaled if the
	 *            display resolution differs from the webcam's resolution. This
	 *            is always <code>false</code> for click-to-shoot.
	 * @return <code>true</code> if the shot wasn't rejected during
	 *         preprocessing
	 */
	public boolean addShot(ShotColor color, double x, double y, long timestamp, boolean scaleShot) {
		if (!checkIgnoreColor(color)) return false;

		final ScaledShot shot = new ScaledShot(color, x, y, cameraManager.cameraTimeToShotTime(timestamp),
				cameraManager.getFrameCount());

		if (config.isAdjustingPOI())
		{
			if (logger.isTraceEnabled())
			{
				logger.trace("POI Adjustment: x {} y {}", config.getPOIAdjustmentX().get(), config.getPOIAdjustmentY().get());
				logger.trace("Adjusting offset via POI setting, coords were {} {} now {} {}", x, y, x+config.getPOIAdjustmentX().get(), y+config.getPOIAdjustmentY().get());
			}
			
			shot.adjustPOI(config.getPOIAdjustmentX().get(), config.getPOIAdjustmentY().get());

		}

		if (scaleShot && (cameraManager.isLimitingDetectionToProjection() || cameraManager.isCroppingFeedToProjection())
				&& cameraManager.getProjectionBounds().isPresent()) {
			final Rect b = cameraManager.getProjectionBounds().get();

			if (handlesBounds()) {
				shot.adjustBounds(b.getMinX(), b.getMinY());
			} else {
				if (cameraManager.isLimitingDetectionToProjection() && !b.contains(x, y)) return false;
			}
		}

		// If the shot didn't come from click to shoot (cameFromCanvas) and the
		// resolution of the display and feed differ, translate shot coordinates
		if (scaleShot && (config.getDisplayWidth() != cameraManager.getFeedWidth()
				|| config.getDisplayHeight() != cameraManager.getFeedHeight())) {
			shot.setDisplayVals(config.getDisplayWidth(), config.getDisplayHeight(), cameraManager.getFeedWidth(),
					cameraManager.getFeedHeight());
		}

		if (!checkDuplicate(shot)) return false;

		submitShot(shot);

		return true;
	}

	protected void submitShot(final ScaledShot shot) {
		if (logger.isInfoEnabled()) logger.info("Suspected shot accepted: Center ({}, {}), cl {} fr {}", shot.getX(),
				shot.getY(), shot.getColor(), cameraManager.getFrameCount());

		// Notify of new shot on a non-shot detection thread because most
		// training exercises do shot processing on whatever thread submits
		// the shot
		new Thread(() -> cameraView.addShot(shot), "Shot Notifier").start();
	}

	protected boolean checkDuplicate(final Shot shot) {
		if (!cameraManager.getDeduplicationProcessor().processShot(shot)) {
			if (logger.isDebugEnabled()) logger.debug("Processing Shot: Shot Rejected By {}",
					cameraManager.getDeduplicationProcessor().getClass().getName());
			return false;
		}
		return true;
	}

	protected boolean checkIgnoreColor(ShotColor color) {
		if (config.ignoreLaserColor() && config.getIgnoreLaserColor().isPresent()
				&& color.equals(config.getIgnoreLaserColor().get())) {
			if (logger.isDebugEnabled()) logger.debug("Processing Shot: Shot rejected by ignoreLaserColor {}",
					config.getIgnoreLaserColor().get());
			return false;
		}
		return true;
	}

	/**
	 * 
	 * @return True if this shot detector only returns shots in bounds and
	 *         offset within the bounds according to the settings in
	 *         CameraManager
	 */
	protected abstract boolean handlesBounds();
}
```

This is the previous `Shot` → `BoundsShot` → `DisplayShot` chain collapsed into one `ScaledShot` with the same steps in the same order: POI, then bounds offset, then display scaling. `ScaledShot` behaves exactly as those steps did.

Now `diff build/ShotDetector.before.java javafx-app/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java` must show only these changes:
- the imports (`BoundsShot`/`DisplayShot` → `ScaledShot`)
- the `Shot`/`BoundsShot`/`DisplayShot` locals becoming the single `shot`
- the `submitShot` parameter type
- the `cameraView.addShot(shot)` call

If the diff shows anything else, the earlier tasks left the file different from what this plan expects. Keep that difference and re-apply only the changes listed above.

In `CanvasManager.java`:
- Add `import com.shootoff.camera.shot.ScaledShot;` after `import com.shootoff.camera.shot.DisplayShot;`.
- Delete the `@Override` line directly above `	public void addShot(DisplayShot shot, boolean isMirroredShot) {`.
- Add this method directly above that one:

```java
	@Override
	public void addShot(ScaledShot shot) {
		addShot(new DisplayShot(shot, config.getMarkerRadius()), false);
	}
```

`MirroredCanvasManager` and `MockCanvasManager` override `addShot(DisplayShot, boolean)`. The new method calls that one, so detected shots still reach their overrides.

Check:

```bash
./gradlew :javafx-app:compileJava :javafx-app:compileTestJava --console=plain
grep -rn "DisplayShot\|ArenaShot\|javafx" javafx-app/src/main/java/com/shootoff/camera --include=*.java | grep -v "camera/shot/DisplayShot.java\|camera/shot/ArenaShot.java\|camera/cameratypes/PS3EyeCamera.java"
```

Expected: `BUILD SUCCESSFUL`, and the `grep` prints nothing. The PS3 Eye is handled in Task 9.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.camera.*' --tests 'com.shootoff.gui.*' --tests 'com.shootoff.session.*' --console=plain`
Expected: all PASS, including:
- `TestScaledShot` (3) and `TestShotDetector` (4)
- the new `TestShot`/`TestCanvasManager` methods
- every `TestCameraManager*` video test, whose shots now arrive through `addShot(ScaledShot)`
- the session tests

- [ ] **Step 7: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 9.

```bash
git add javafx-app/src/main/java/com/shootoff/camera/shot/ScaledShot.java javafx-app/src/main/java/com/shootoff/camera/shot/DisplayShot.java \
  javafx-app/src/main/java/com/shootoff/camera/shotdetection/ShotDetector.java javafx-app/src/main/java/com/shootoff/camera/CameraView.java \
  javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java \
  javafx-app/src/test/java/com/shootoff/camera/RecordingCameraView.java \
  javafx-app/src/test/java/com/shootoff/camera/shot/TestScaledShot.java javafx-app/src/test/java/com/shootoff/camera/shot/TestShot.java \
  javafx-app/src/test/java/com/shootoff/camera/shotdetection/TestShotDetector.java \
  javafx-app/src/test/java/com/shootoff/gui/TestCanvasManager.java
git commit -m "Shot detection emits ScaledShot data; views create their own markers"
git log -1 --format=%B
```

---

### Task 9: The PS3 Eye settings window moves to the JavaFX app

**Files:**
- Modify: `javafx-app/src/main/java/com/shootoff/camera/cameratypes/PS3EyeCamera.java` (JavaFX imports ~65–76; static `Label`/`Stage` ~95–96; `launchCameraSettings()` ~175–345; `close()` ~370; `run()` ~443; `getExposure`/`setExposure` ~461–467)
- Create: `javafx-app/src/main/java/com/shootoff/gui/PS3EyeSettingsWindow.java`
- Modify: `javafx-app/src/main/java/com/shootoff/camera/CameraManager.java` (`launchCameraSettings()` ~795; `PS3EyeCamera` import)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java` (~688–691)
- Create: `javafx-app/src/test/java/com/shootoff/camera/cameratypes/TestPS3EyeCameraSettingsListener.java` (moves to `core` in Task 10)

**Interfaces:**
- Produces on `PS3EyeCamera` (no JavaFX left):
  - nested `public interface SettingsListener { void fpsUpdated(double fps); void cameraClosing(); }`
  - `setSettingsListener(SettingsListener)` (`null` clears it)
  - `int getGain()`, `void setGain(int)`
  - `int getExposure()`, `void setExposure(int)` (now public)
  - `boolean isAutoGain()`, `void setAutoGain(boolean)`

  The camera reports FPS to the listener from its capture thread. `close()` tells the listener once and then forgets it.
- Removes: `PS3EyeCamera.launchCameraSettings()`. `CameraManager.launchCameraSettings()` now only handles `SarxosCaptureCamera`.
- Produces: `com.shootoff.gui.PS3EyeSettingsWindow.show(PS3EyeCamera)` (call on the JavaFX thread; one window at a time, as before). `ShootOFFController`'s "Configure Camera" menu item (Windows only) calls it for a PS3 Eye.

- [ ] **Step 1: Write the failing test**

Create `javafx-app/src/test/java/com/shootoff/camera/cameratypes/TestPS3EyeCameraSettingsListener.java`:

```java
package com.shootoff.camera.cameratypes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class TestPS3EyeCameraSettingsListener {
	// Without the eyeCam native library (every non-Windows machine) the camera only logs that
	// the driver is missing, and close() has no device to release

	@Test
	void closingTheCameraClosesTheSettingsWindowOnce() {
		final PS3EyeCamera camera = new PS3EyeCamera();
		final AtomicInteger closings = new AtomicInteger();
		camera.setSettingsListener(new PS3EyeCamera.SettingsListener() {
			@Override
			public void fpsUpdated(double fps) {}

			@Override
			public void cameraClosing() {
				closings.incrementAndGet();
			}
		});

		camera.close();
		camera.close();

		assertEquals(1, closings.get());
	}

	@Test
	void closingWithoutASettingsWindowIsFine() {
		assertDoesNotThrow(() -> new PS3EyeCamera().close());
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :javafx-app:compileTestJava --console=plain`
Expected: compilation FAILS with `cannot find symbol: class SettingsListener` and `method setSettingsListener`.

- [ ] **Step 3: Strip the UI from `PS3EyeCamera`**

In `PS3EyeCamera.java`:
- Delete the twelve `javafx.*` imports and add `import java.util.Optional;` if it isn't already imported (it is, for `origExposure`).
- Delete the fields `	private boolean configIsOpen = false;`, `	private static Label fpsValue = new Label("0");` and `	private static Stage ps3eyeSettingsStage = new Stage();`.
- Add these members after the constructor `public PS3EyeCamera()`:

```java
	/**
	 * Receives updates for an open settings window.
	 */
	public interface SettingsListener {
		void fpsUpdated(double fps);

		void cameraClosing();
	}

	private volatile Optional<SettingsListener> settingsListener = Optional.empty();

	/**
	 * @param listener
	 *            the open settings window, or <tt>null</tt> when it closes
	 */
	public void setSettingsListener(SettingsListener listener) {
		settingsListener = Optional.ofNullable(listener);
	}

	public int getGain() {
		return eyecamLib.ps3eye_get_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_GAIN);
	}

	public void setGain(int gain) {
		eyecamLib.ps3eye_set_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_GAIN, gain);
	}

	public boolean isAutoGain() {
		return eyecamLib.ps3eye_get_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_AUTO_GAIN) != 0;
	}

	public void setAutoGain(boolean autoGain) {
		eyecamLib.ps3eye_set_parameter(ps3ID, eyecam.ps3eye_parameter.PS3EYE_AUTO_GAIN, autoGain ? 1 : 0);
	}
```

- Delete the whole method `public void launchCameraSettings()`, from `	public void launchCameraSettings() {` through its closing `	}// end launchcamerasettings`. Before deleting it, copy its body into `build/ps3eye-settings-ui.txt` for Step 4 (`sed -n '/public void launchCameraSettings/,/end launchcamerasettings/p' <file> > build/ps3eye-settings-ui.txt`).
- In `close()`, replace

  ```java
  		if (configIsOpen) {
  			configIsOpen = false;
  			ps3eyeSettingsStage.close();
  		}
  ```

  with

  ```java
  		final Optional<SettingsListener> listener = settingsListener;
  		settingsListener = Optional.empty();
  		if (listener.isPresent()) listener.get().cameraClosing();
  ```

- In `run()`, replace

  ```java
  			if (configIsOpen) {
  				Platform.runLater(() -> {
  					final String theFPS = Double.toString(getFPS());
  					if (theFPS.length() >= 6) fpsValue.setText((Double.toString(getFPS()).substring(0, 5)));
  				});
  			}
  ```

  with

  ```java
  			final Optional<SettingsListener> listener = settingsListener;
  			if (listener.isPresent()) listener.get().fpsUpdated(getFPS());
  ```

- Change `	private int getExposure() {` to `	public int getExposure() {` and `	private void setExposure(int exposure) {` to `	public void setExposure(int exposure) {`.

Check: `grep -n "javafx\|Platform\|configIsOpen\|fpsValue\|Stage" javafx-app/src/main/java/com/shootoff/camera/cameratypes/PS3EyeCamera.java` prints nothing.

- [ ] **Step 4: Create the settings window**

Create `javafx-app/src/main/java/com/shootoff/gui/PS3EyeSettingsWindow.java` (GPL header, then). The layout code is `PS3EyeCamera.launchCameraSettings()` moved unchanged, except that native calls go through the camera's new methods:

```java
package com.shootoff.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.cameratypes.PS3EyeCamera;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/**
 * The PS3 Eye's gain, exposure and auto-gain window. At most one is open at a time.
 */
public final class PS3EyeSettingsWindow implements PS3EyeCamera.SettingsListener {
	private static final Logger logger = LoggerFactory.getLogger(PS3EyeSettingsWindow.class);

	private static PS3EyeSettingsWindow openWindow = null;

	private final PS3EyeCamera camera;
	private final Stage stage = new Stage();
	private final Label fpsValue = new Label("0");

	private PS3EyeSettingsWindow(PS3EyeCamera camera) {
		this.camera = camera;
		buildScene();
	}

	/**
	 * Opens the settings window for <tt>camera</tt>, replacing any open one. Must be called on
	 * the JavaFX application thread.
	 */
	public static void show(PS3EyeCamera camera) {
		logger.trace("Launch camera settings called");

		if (openWindow != null) openWindow.close();

		final PS3EyeSettingsWindow window = new PS3EyeSettingsWindow(camera);
		openWindow = window;
		camera.setSettingsListener(window);
		window.stage.setOnCloseRequest((e) -> window.detach());
		window.stage.show();
	}

	@Override
	public void fpsUpdated(double fps) {
		Platform.runLater(() -> {
			final String theFPS = Double.toString(fps);
			if (theFPS.length() >= 6) fpsValue.setText(theFPS.substring(0, 5));
		});
	}

	@Override
	public void cameraClosing() {
		Platform.runLater(() -> {
			if (openWindow == this) openWindow = null;
			stage.close();
		});
	}

	private void detach() {
		camera.setSettingsListener(null);
		if (openWindow == this) openWindow = null;
	}

	private void close() {
		detach();
		stage.close();
	}

	private void buildScene() {
		final CheckBox autoGain = new CheckBox("AutoGain");
		final Color textColor = Color.BLACK;

		final Slider gain = new Slider(0, 63, camera.getGain());
		final Slider exposure = new Slider(0, 255, camera.getExposure());

		final Label gainCaption = new Label("Gain:");
		final Label exposureCaption = new Label("Exposure:");
		final Label autoGainCaption = new Label("Auto Gain:");
		final Label fpsCaption = new Label("FPS: ");

		final Label gainValue = new Label(Integer.toString((int) gain.getValue()));
		final Label exposureValue = new Label(Integer.toString((int) exposure.getValue()));

		gain.setShowTickLabels(true);
		gain.setShowTickMarks(true);
		gain.setMajorTickUnit(9);// 63
		gain.setMinorTickCount(9);
		gain.setBlockIncrement(1);
		gain.setSnapToTicks(true);

		exposure.setShowTickLabels(true);
		exposure.setShowTickMarks(true);
		exposure.setMajorTickUnit(50);// 255
		exposure.setMinorTickCount(25);
		exposure.setBlockIncrement(1);
		exposure.setSnapToTicks(true);

		final Group root = new Group();
		final Scene scene = new Scene(root, 425, 200);
		stage.setScene(scene);
		stage.setTitle("PS3EYE Configuration");
		scene.setFill(Color.WHITESMOKE);

		final GridPane grid = new GridPane();
		grid.setPadding(new Insets(10, 10, 10, 10));
		grid.setVgap(10);
		grid.setHgap(70);

		scene.setRoot(grid);

		gainCaption.setTextFill(textColor);
		GridPane.setConstraints(gainCaption, 0, 1);
		grid.getChildren().add(gainCaption);

		exposureCaption.setTextFill(textColor);
		GridPane.setConstraints(exposureCaption, 0, 2);
		grid.getChildren().add(exposureCaption);

		GridPane.setConstraints(autoGainCaption, 0, 4);
		grid.getChildren().add(autoGainCaption);

		GridPane.setConstraints(fpsCaption, 0, 5);
		grid.getChildren().add(fpsCaption);

		GridPane.setConstraints(gain, 1, 1);
		grid.getChildren().add(gain);

		GridPane.setConstraints(exposure, 1, 2);
		grid.getChildren().add(exposure);

		gainValue.setTextFill(textColor);
		GridPane.setConstraints(gainValue, 2, 1);
		grid.getChildren().add(gainValue);

		exposureValue.setTextFill(textColor);
		GridPane.setConstraints(exposureValue, 2, 2);
		grid.getChildren().add(exposureValue);

		GridPane.setConstraints(fpsValue, 1, 5);
		grid.getChildren().add(fpsValue);

		gain.valueProperty().addListener(new ChangeListener<Number>() {
			@Override
			public void changed(ObservableValue<? extends Number> ov, Number old_val, Number new_val) {
				if (logger.isTraceEnabled()) logger.trace("gain set to: {}", Math.round(new_val.doubleValue()));
				camera.setGain((int) Math.round(new_val.doubleValue()));
				gainValue.setText(String.format("%d", (int) Math.round(new_val.doubleValue())));
			}
		});

		exposure.valueProperty().addListener(new ChangeListener<Number>() {
			@Override
			public void changed(ObservableValue<? extends Number> ov, Number old_val, Number new_val) {
				camera.setExposure((int) Math.round(new_val.doubleValue()));
				if (logger.isTraceEnabled())
					logger.trace("exposure level set to: {}", Math.round(new_val.doubleValue()));
				exposureValue.setText(String.format("%d", (int) Math.round(new_val.doubleValue())));
			}
		});

		final boolean isAutoGainSet = camera.isAutoGain();
		if (!isAutoGainSet) {
			autoGain.setText("Off");
			gain.setDisable(false);
			exposure.setDisable(false);
		} else {
			autoGain.setText("On");
			gain.setDisable(true);
			exposure.setDisable(true);
		}

		autoGain.setSelected(isAutoGainSet);

		GridPane.setConstraints(autoGain, 1, 4);
		grid.getChildren().add(autoGain);

		autoGain.selectedProperty().addListener(new ChangeListener<Boolean>() {
			@Override
			public void changed(ObservableValue<? extends Boolean> ov, Boolean old_val, Boolean new_val) {
				if (new_val) {
					autoGain.setText("On");
					gain.setValue(camera.getGain());
					gain.setDisable(true);
					exposure.setDisable(true);
					camera.setAutoGain(true);
				} else {
					camera.setAutoGain(false);
					autoGain.setText("Off");
					gain.setValue(camera.getGain());
					gain.setDisable(false);
					exposure.setDisable(false);
				}
			}
		});
	}
}
```

Compare the layout against `build/ps3eye-settings-ui.txt`. Every slider, label, grid position and listener must be the same, apart from:
- `ps3eyeSettingsStage` → `stage`
- `eyecamLib.ps3eye_get/set_parameter(ps3ID, ...GAIN/EXPOSURE/AUTO_GAIN...)` → the camera methods
- `configIsOpen` → the listener registration

- [ ] **Step 5: Open the window from the controller**

In `CameraManager.java`, replace

```java
	public void launchCameraSettings() {
		if (camera instanceof SarxosCaptureCamera) {
			((SarxosCaptureCamera) camera).launchCameraSettings();
		} else if (camera instanceof PS3EyeCamera) {
			((PS3EyeCamera) camera).launchCameraSettings();
		}
	}
```

with

```java
	/**
	 * Opens the camera driver's own settings dialog, if it has one. The PS3 Eye's settings window
	 * is part of the UI (com.shootoff.gui.PS3EyeSettingsWindow).
	 */
	public void launchCameraSettings() {
		if (camera instanceof SarxosCaptureCamera) {
			((SarxosCaptureCamera) camera).launchCameraSettings();
		}
	}
```

and delete `import com.shootoff.camera.cameratypes.PS3EyeCamera;`.

In `ShootOFFController.java`, add `import com.shootoff.camera.cameratypes.PS3EyeCamera;` and `import com.shootoff.gui.PS3EyeSettingsWindow;` to the `com.shootoff` imports, and replace

```java
				cameraManager.launchCameraSettings();
```

with

```java
				if (cameraManager.getCamera() instanceof PS3EyeCamera) {
					PS3EyeSettingsWindow.show((PS3EyeCamera) cameraManager.getCamera());
				} else {
					cameraManager.launchCameraSettings();
				}
```

Check that the camera packages are JavaFX-free now:

```bash
./gradlew :javafx-app:compileJava :javafx-app:compileTestJava --console=plain
grep -rln "javafx" javafx-app/src/main/java/com/shootoff/camera javafx-app/src/main/java/com/shootoff/util javafx-app/src/main/java/com/shootoff/config javafx-app/src/main/java/com/shootoff/Closeable.java javafx-app/src/main/java/com/shootoff/ObservableCloseable.java
```

Expected: `BUILD SUCCESSFUL`. The `grep` prints only `camera/shot/DisplayShot.java`, `camera/shot/ArenaShot.java` and `config/Configuration.java`, which stay in `javafx-app`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.camera.cameratypes.*' --console=plain`
Expected: `TestPS3EyeCameraSettingsListener` 2 PASS, and the other camera-type tests PASS.

- [ ] **Step 7: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 2.

```bash
git add javafx-app/src/main/java/com/shootoff/camera/cameratypes/PS3EyeCamera.java javafx-app/src/main/java/com/shootoff/camera/CameraManager.java \
  javafx-app/src/main/java/com/shootoff/gui/PS3EyeSettingsWindow.java javafx-app/src/main/java/com/shootoff/gui/controller/ShootOFFController.java \
  javafx-app/src/test/java/com/shootoff/camera/cameratypes/TestPS3EyeCameraSettingsListener.java
git commit -m "Move the PS3 Eye settings window out of the camera into the JavaFX app"
git log -1 --format=%B
```

---

### Task 10: Move the neutral code and its tests into `core`

**Files:**
- Move to `core/src/main/java/com/shootoff/` (`git mv`):
  - `Closeable.java`, `ObservableCloseable.java`
  - `util/**`
  - `camera/**` except `camera/shot/DisplayShot.java` and `camera/shot/ArenaShot.java`
  - `config/Settings.java`, `config/ConfigurationException.java`, `config/CalibrationOption.java`
- Move to `core/src/test/java/com/shootoff/`:
  - `camera/cameratypes/TestSarxosCaptureCamera.java`, `TestV4l2Controls.java`, `TestPS3EyeCameraSettingsListener.java`
  - `camera/recorders/TestRollingRecorder.java`
  - `camera/video/TestVideoIO.java`
  - `camera/perspective/TestPerspectiveManager.java`
  - `camera/TestDeduplicationProcessor.java`, `TestMalfunctionsProcessor.java`, `TestVirtualMagazineProcessor.java`, `TestCameraManagerDiagnostics.java`, `RecordingCameraView.java`
  - `camera/shotdetection/TestShotDetector.java`
  - `camera/shot/TestScaledShot.java`
  - `config/TestConfiguration.java`, `config/TestSettings.java`
  - `util/TestHardwareData.java`
- Move to `core/src/testFixtures/java/com/shootoff/camera/`: `MockCamera.java`, `MockCameraManager.java`, `VideoFinishedListener.java`
- Move to `core/src/test/resources/`: `test.properties`, `perspective/`. Copy `logback-test.xml`.
- Modify: `core/build.gradle.kts` (`java-test-fixtures`), `javafx-app/build.gradle.kts` (`testImplementation(testFixtures(project(":core")))`)
- Modify (moved tests): `MockCameraManager.java` (constructor parameter), `TestPerspectiveManager.java` (resource anchor), and `Configuration` → `Settings` in five tests
- Stay in `javafx-app` (GUI fixtures or `DisplayShot`):
  - `TestShot`, `ShotDetectionTestor`, the seven `TestCameraManager*` classes, `TestAutoCalibration`
  - `TestConfigurationSingleton`, `TestFxGeometry`
  - everything under `gui`, `plugins`, `session`, `courses`, `targets` and `testutil`, plus `TestJWS`

**Interfaces:**
- Consumes: everything from Tasks 2–9. After this task:
  - `core` contains every `com.shootoff.camera.*` type except `DisplayShot`/`ArenaShot`.
  - `core` also contains `com.shootoff.config.{Settings, ConfigurationException, CalibrationOption}`, `com.shootoff.util.*`, `com.shootoff.{Closeable, ObservableCloseable}`, `com.shootoff.geom.*` and `com.shootoff.sound.*`.
- Produces:
  - `core` test fixtures `com.shootoff.camera.MockCamera`, `MockCameraManager` (the protected constructor now takes a `CameraView`, not a `CanvasManager`) and `VideoFinishedListener`, used by `javafx-app` tests through `testFixtures(project(":core"))`
  - published `com.shootoff:core:5.0.0-SNAPSHOT` now carries those classes. The old-style plugin build must add it, see Step 9.

- [ ] **Step 1: Move the main sources**

```bash
cd /home/bfears/projects/ShootOFF
move() { mkdir -p "$(dirname "$2")"; git mv "$1" "$2"; }

for f in $(git ls-files javafx-app/src/main/java/com/shootoff/camera javafx-app/src/main/java/com/shootoff/util); do
	case "$f" in
	*/camera/shot/DisplayShot.java|*/camera/shot/ArenaShot.java) continue ;;
	esac
	move "$f" "core/${f#javafx-app/}"
done

for f in Closeable.java ObservableCloseable.java config/Settings.java config/ConfigurationException.java config/CalibrationOption.java; do
	move "javafx-app/src/main/java/com/shootoff/$f" "core/src/main/java/com/shootoff/$f"
done

git ls-files javafx-app/src/main/java/com/shootoff/camera javafx-app/src/main/java/com/shootoff/config
```

Files are moved one at a time because `core` already has `com/shootoff/camera` (`DiagnosticMessage`) and `com/shootoff/util` (`UserNotifier`). Moving whole directories would nest them.

Expected from the last command: exactly `.../camera/shot/ArenaShot.java`, `.../camera/shot/DisplayShot.java` and `.../config/Configuration.java`.

- [ ] **Step 2: Move the core-clean tests, the camera fixtures and their resources**

```bash
cd /home/bfears/projects/ShootOFF
move() { mkdir -p "$(dirname "$2")"; git mv "$1" "$2"; }   # same helper as Step 1 (each step may run in a fresh shell)
T=src/test/java/com/shootoff
for f in camera/cameratypes/TestSarxosCaptureCamera.java camera/cameratypes/TestV4l2Controls.java \
		camera/cameratypes/TestPS3EyeCameraSettingsListener.java camera/recorders/TestRollingRecorder.java \
		camera/video/TestVideoIO.java camera/perspective/TestPerspectiveManager.java \
		camera/TestDeduplicationProcessor.java camera/TestMalfunctionsProcessor.java \
		camera/TestVirtualMagazineProcessor.java camera/TestCameraManagerDiagnostics.java camera/RecordingCameraView.java \
		camera/shotdetection/TestShotDetector.java camera/shot/TestScaledShot.java \
		config/TestConfiguration.java config/TestSettings.java util/TestHardwareData.java; do
	move "javafx-app/$T/$f" "core/$T/$f"
done

for f in MockCamera.java MockCameraManager.java VideoFinishedListener.java; do
	move "javafx-app/$T/camera/$f" "core/src/testFixtures/java/com/shootoff/camera/$f"
done

move javafx-app/src/test/resources/test.properties core/src/test/resources/test.properties
move javafx-app/src/test/resources/perspective core/src/test/resources/perspective
cp javafx-app/src/test/resources/logback-test.xml core/src/test/resources/logback-test.xml
```

`/test.properties` is used only by `TestConfiguration`, and `/perspective/*.png` only by `TestPerspectiveManager`. Check:

```bash
grep -rln "test.properties\|/perspective/" javafx-app/src/test/java
```

Expected: nothing.

- [ ] **Step 3: Wire the test fixtures into the builds**

In `core/build.gradle.kts`, replace

```kotlin
plugins {
    `java-library`
    `maven-publish`
}
```

with

```kotlin
plugins {
    `java-library`
    // MockCamera/MockCameraManager are shared with javafx-app's shot-detection tests
    `java-test-fixtures`
    `maven-publish`
}
```

In `javafx-app/build.gradle.kts`, add `testImplementation(testFixtures(project(":core")))` after `implementation(project(":plugin-api"))`.

- [ ] **Step 4: Adjust the moved tests and fixtures**

`MockCameraManager` only needs a `CameraView`. In `core/src/testFixtures/java/com/shootoff/camera/MockCameraManager.java`, delete `import com.shootoff.gui.CanvasManager;` and replace

```java
	protected MockCameraManager(MockCamera camera, CanvasManager canvas,
```

with

```java
	protected MockCameraManager(MockCamera camera, CameraView canvas,
```

`javafx-app`'s `ShotDetectionTestor` still passes a `MockCanvasManager`, which is a `CameraView`. It is in the same package, so the protected constructor stays accessible.

In `core/src/test/java/com/shootoff/camera/perspective/TestPerspectiveManager.java`, delete `import com.shootoff.camera.TestAutoCalibration;` and replace both `TestAutoCalibration.class.getResourceAsStream(` with `TestPerspectiveManager.class.getResourceAsStream(`.

Core has `Settings`, not `Configuration`. `\bConfiguration\b` leaves `ConfigurationException` and the class name `TestConfiguration` alone:

```bash
cd /home/bfears/projects/ShootOFF
C=core/src/test/java/com/shootoff
perl -pi -e 's/\bConfiguration\b/Settings/g' \
  $C/camera/TestMalfunctionsProcessor.java $C/camera/TestVirtualMagazineProcessor.java \
  $C/camera/TestCameraManagerDiagnostics.java $C/camera/shotdetection/TestShotDetector.java \
  $C/config/TestConfiguration.java
grep -rnw "Configuration" core/src
```

Expected: the `grep` prints only comments and string literals (e.g. `"Configuration File Unwritable!"` and the Javadoc that names the JavaFX app's `Configuration` subclass), with no code references. Compilation in Step 5 proves it, because `core` cannot see `Configuration`.

- [ ] **Step 5: Build `core` and prove it has no JavaFX**

```bash
./gradlew :core:compileJava :core:compileTestFixturesJava :core:compileTestJava :javafx-app:compileTestJava --console=plain
./gradlew :core:dependencies --configuration runtimeClasspath --console=plain | grep -ci openjfx
./gradlew :core:dependencies --configuration testRuntimeClasspath --console=plain | grep -ci openjfx
grep -rn "^import javafx" core/src
./gradlew :core:test --tests 'com.shootoff.TestNoJavaFxInCore' --console=plain
```

Expected:
- `BUILD SUCCESSFUL`
- both counts are `0`, and the `grep` prints nothing
- `TestNoJavaFxInCore` passes, now scanning the whole camera pipeline

If `:core:compileJava` fails because a moved class references a `javafx-app` type, the earlier tasks missed a coupling. Stop, find which task owns that code, and fix it there in this commit, noting it in the report. Don't move the class back.

- [ ] **Step 6: Run the gate and confirm nothing was dropped**

Run the gate. Expected:
- `0 regressions; 0 new failures`
- `N/M passing` **identical** to Task 9's. This task only moves tests, so any change means a test class stopped running.

Also:

```bash
ls core/build/test-results/test | grep -c "TEST-"
ls javafx-app/build/test-results/test | grep -E "TestShot\.|TestAutoCalibration|TestCameraManagerBright|TestConfigurationSingleton"
```

Expected:
- `20` result files in `core`: the 15 moved test classes (`RecordingCameraView` is a helper) plus `TestPoint`, `TestSize`, `TestRect`, `TestNoJavaFxInCore` and `TestSoundPlayer`
- the second command still lists those four `javafx-app` classes

- [ ] **Step 7: Run the app from the new layout**

```bash
./gradlew installDist --console=plain
ls javafx-app/build/install/shootoff/lib | grep -E "^(core|plugin-api|javafx-app)-"
timeout 60 ./gradlew run --console=plain > build/run-smoke.log 2>&1; grep -nE "Exception|NoClassDefFoundError|Could not find or load main class" build/run-smoke.log
```

Expected: the install's `lib/` has the `core`, `plugin-api` and `javafx-app` jars, the window opens, and the `grep` prints nothing. Skip the `run` line without a display and say so.

- [ ] **Step 8: Check the published jars**

```bash
./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain
unzip -l build/m2/com/shootoff/core/5.0.0-SNAPSHOT/core-5.0.0-SNAPSHOT.jar | grep -E "camera/Shot.class|config/Settings.class|util/NamedThreadFactory.class"
unzip -l build/m2/com/shootoff/shootoff/5.0.0-SNAPSHOT/shootoff-5.0.0-SNAPSHOT.jar | grep -E "camera/shot/DisplayShot.class|config/Configuration.class|plugins/TrainingExerciseBase.class"
```

Expected: the three classes in each jar.

- [ ] **Step 9: Check what an old-style plugin build needs**

The owner's `RandomTargetParDrill` compiles against `com.shootoff:shootoff` with `isTransitive = false`. `Shot`, `ShotColor`, `NamedThreadFactory` and `Settings` (the superclass of `Configuration`) are now in `core`, so its build needs one more line. Prove it on a scratch copy:

```bash
rm -rf build/drill-check && rsync -a --exclude build --exclude .gradle /home/bfears/projects/RandomTargetParDrill/ build/drill-check/
(cd build/drill-check && ./gradlew compileJava -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain) 2>&1 | grep -E "error:|BUILD" | head -5
perl -0pi -e 's/(    compileOnly\("com\.shootoff:shootoff:5\.0\.0-SNAPSHOT"\) \{ isTransitive = false \}\n)/$1    compileOnly("com.shootoff:core:5.0.0-SNAPSHOT") { isTransitive = false }\n/' build/drill-check/build.gradle.kts
grep -n "com.shootoff" build/drill-check/build.gradle.kts
(cd build/drill-check && ./gradlew compileJava -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain) 2>&1 | tail -3
```

Expected:
- the first build FAILS with `error: package com.shootoff.camera does not exist` (or similar for `Shot`/`ShotColor`/`NamedThreadFactory`)
- the build file then shows both `compileOnly` lines
- the second build prints `BUILD SUCCESSFUL`

Record the result in the report. The owner adds that same line to the real plugin repo. This plan does not change it. The already-built `exercises/RandomTargetParDrill.jar` needs no change: Task 11 checks it at runtime.

- [ ] **Step 10: Commit**

```bash
git add core/build.gradle.kts javafx-app/build.gradle.kts core/src/test/resources/logback-test.xml \
  core/src/testFixtures/java/com/shootoff/camera/MockCameraManager.java \
  core/src/test/java/com/shootoff/camera/perspective/TestPerspectiveManager.java \
  core/src/test/java/com/shootoff/camera/TestMalfunctionsProcessor.java core/src/test/java/com/shootoff/camera/TestVirtualMagazineProcessor.java \
  core/src/test/java/com/shootoff/camera/TestCameraManagerDiagnostics.java core/src/test/java/com/shootoff/camera/shotdetection/TestShotDetector.java \
  core/src/test/java/com/shootoff/config/TestConfiguration.java
git status --short | grep -v "^R  " | head -20
git commit -m "Move the camera pipeline, settings and utilities into core"
git log -1 --format=%B
```

The `git mv`s are already staged. The filtered `git status` must show only ` M shootoff.properties` and no untracked `core/`/`javafx-app/` files. Stage any it does show by path.

---

### Task 11: Hardware check with the owner

**Files:** none. If a check fails, stop and debug with superpowers:systematic-debugging before changing code. The fix belongs in the task that owns the code, as a new commit.

- [ ] **Step 1: Launch**

Run `./gradlew run --args="-d" --console=plain > build/plan1-run.log 2>&1` in the background. With the webcam and projector attached, the owner:
1. confirms the camera feed shows and click-to-shoot places a marker where clicked
2. fires the laser at the feed: markers appear where the dot was, in the laser's color, and the shot timer row is added. Shot scaling, `ScaledShot` → `DisplayShot`.
3. opens **Preferences**, changes the marker radius, saves, and reopens. The value persisted. `Settings` writes `shootoff.properties`, which is the owner's own file; it stays unstaged.
4. opens the **Projector Arena**, moves it to the projector, and auto-calibrates. Shots on the arena land on the right spot, including near the edges of the calibrated area. `Rect` bounds.
5. turns on **Record Session**, fires a few shots, stops, and replays in **View Sessions**
6. starts **RandomTargetParDrill** (the installed v1 jar, compiled before the split) and plays two rounds: random placement, sounds, par timing, and the summary with shot replay
7. starts one built-in exercise, e.g. Shoot Don't Shoot, for a round
8. if the room lights allow it, triggers the "Excessive brightness"/"Excessive motion" warning. It appears in red and disappears after about a second. `DiagnosticMessage`.

- [ ] **Step 2: Check the log**

```bash
grep -nE "Exception|NoClassDefFoundError|NoSuchMethodError|AbstractMethodError" build/plan1-run.log
```

Expected: nothing. A `NoSuchMethodError`/`AbstractMethodError` naming a `CameraView` or `Shot` method means a v1 plugin used an API this plan changed. Report it; don't paper over it.

- [ ] **Step 3: Installed distribution**

```bash
./gradlew installDist --console=plain
(cd /tmp && /home/bfears/projects/ShootOFF/javafx-app/build/install/shootoff/bin/shootoff)
```

Launching from `/tmp` checks the start-script `cd` patch. The window opens with targets available in the target list. The owner closes it.

The PS3 Eye settings window (Windows-only menu item) and the IP-camera error alerts can't be exercised on this Linux machine. Note them as unchecked in the report.
