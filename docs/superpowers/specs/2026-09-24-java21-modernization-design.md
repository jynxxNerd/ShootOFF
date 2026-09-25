# ShootOFF Java 21 Modernization — Design

- **Date:** 2026-09-24
- **Status:** Draft, awaiting review
- **Branch:** `modernize/java21`
- **Scope:** Sub-project 1 of 2 ("runs on modern Java"). Sub-project 2 ("revival": CI, installers, releases, docs) is out of scope and gets its own design.

## 1. Intent

**Stated by the owner**

- Primary goal: ShootOFF runs reliably on a modern Java on the owner's Linux machine, with the owner's plugin [RandomTargetParDrill](https://github.com/jynxxNerd/RandomTargetParDrill).
- Motivating problem: on the current system, ShootOFF shows "Webcam FPS is too low" warnings with an ordinary USB webcam.
- Nice to have: the result is a credible base for reviving ShootOFF as a maintained community project.
- Keep session video recording/playback and text-to-speech. Drop headless/Bluetooth mode.
- The plugin may be updated alongside ShootOFF; it must not hold back ShootOFF's modernization.

**Assumptions**

- Target platform for this sub-project is Linux x86_64. Windows/macOS support is preserved in code where it exists today but is not packaged or verified until sub-project 2.
- Camera of record is a standard USB (UVC) webcam via V4L2. PS3 Eye, OptiTrack, and IP cameras are kept compiling but are not verified.

**Success criteria**

1. `./gradlew build` passes on JDK 21, and the test suite matches the Java 8 baseline (section 6.1).
2. `./gradlew run` launches ShootOFF.
3. RandomTargetParDrill, built against the new ShootOFF, loads from `exercises/` and plays through a full drill including sounds and the score summary.
4. The owner's USB webcam delivers a usable frame rate (at or above `CameraManager.MIN_SHOT_DETECTION_FPS`, target ~30 FPS) without the FPS warning.

## 2. Current state (as of commit `bb89d0c6`)

- ~30k lines, 233 Java files, JavaFX 8. Compiles today only because the active JDK is Corretto 8, which bundles JavaFX.
- Baseline tests on Java 8: **154 run, 153 pass, 1 fails** (`TestCameraManagerVeryBright.testMSHD3000MinBrightnessMinContrastWhiteBalanceOff`, missing shot at (403, 363)). 38 real-webcam `.mp4` fixtures drive shot-detection tests.
- Build relies on `ant-javafx.jar` (removed after JDK 8), Java Web Start, GitHub password auth, Travis CI, and dead Bintray repositories.
- Frames are captured through OpenCV 2.4's `VideoCapture` (`SarxosCaptureCamera`), not webcam-capture. `Main` contains a `v4l1compat.so` `LD_PRELOAD` workaround, indicating the old OpenCV build lacks proper V4L2 support. This is the leading hypothesis for the FPS problem.

## 3. Build and runtime

- **JDK:** Java 21 LTS via Gradle toolchain (the system default JDK can remain 8).
- **Gradle:** keep the 8.14 wrapper already added; rewrite the build as `build.gradle.kts` + `settings.gradle.kts` with a version catalog at `gradle/libs.versions.toml`. All versions pinned; no dynamic (`1.+`) versions.
- **JavaFX:** OpenJFX 21 via `org.openjfx.javafxplugin`, modules `javafx.controls`, `javafx.fxml`, `javafx.swing`.
- **Classpath, not JPMS.** The application is not modularized. Plugins are loaded by `URLClassLoader` and may share packages with ShootOFF.
- **Launcher:** new `com.shootoff.Launcher` with a `main` that calls `Main.main`. Required because a class extending `javafx.application.Application` cannot be the classpath entry point on JDK 11+.
- **`application` plugin:**
  - `mainClass = com.shootoff.Launcher`.
  - `./gradlew run` uses the project root as working directory, so `targets/`, `sounds/`, `courses/`, `exercises/` resolve as today.
  - `installDist` produces `build/install/shootoff/` with `bin/shootoff`, `lib/`, and the resource directories `targets/`, `sounds/`, `courses/`, `exercises/` and `shootoff.properties`. The generated Unix start script is customized to `cd "$APP_HOME"` before launching, because ShootOFF and plugins resolve relative paths (e.g. `sounds/beep.wav`) against the working directory.
- **`maven-publish`:** publish `com.shootoff:shootoff:5.0.0-SNAPSHOT` to `mavenLocal` so plugins can compile against it.
- **Version:** `src/main/resources/version.properties` becomes `5.0.0-SNAPSHOT` (major bump: Java 21 required, headless removed).
- **Removed from the build:** all ant-javafx tasks (`fxJar`, `fxSignedJar`, `fxWebstartSignedJar`, `fxRelease`, `fxJarWritableResources`, `msiRelease`), all GitHub release and gh-pages tasks, `updateJars`, the `buildscript` block (gpars, github-api), the `eclipse` plugin, and repositories other than Maven Central, the DFKI repo, and `nexus.terrestris.de`. MaryTTS 5.2.1 itself is on Maven Central, but its dependencies `de.dfki.lt.jtok:jtok-core` (DFKI only) and `gov.nist.math:Jampack:1.0` (terrestris only) are not. Both extra repositories are content-filtered to those artifacts.
- **Repository hygiene:** commit the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`). `.gitignore` ignores `*.jar`, so add a `!gradle/wrapper/gradle-wrapper.jar` exception (`build/` and `log/` are already ignored).
- **Unchanged:** `eyeCam32.dll`/`eyeCam64.dll` stay in the repo and are copied into the distribution as today.

## 4. Dependency changes

### 4.1 Vision and capture: OpenCV 2.4 → OpenCV 4 via JavaCV

- Replace `org.openpnp:opencv:2.4.+` with `org.bytedeco:javacv-platform` (pinned), whose bundled `opencv` artifact provides the standard `org.opencv.*` Java API.
- Restrict native classifiers to `linux-x86_64` via the JavaCPP `javacpp.platform` mechanism to keep the build size reasonable. Multi-OS natives are a sub-project 2 concern.
- Mechanical API changes:
  - `org.opencv.highgui.Highgui` → `org.opencv.imgcodecs.Imgcodecs` / `org.opencv.videoio.Videoio` constants.
  - `org.opencv.highgui.VideoCapture` → `org.opencv.videoio.VideoCapture`.
  - `nu.pattern.OpenCV.loadShared()` (in `Main`) → `org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class)`, also invoked in test setup.
- **FPS fix in `SarxosCaptureCamera.open()`:** on Linux, open with the `Videoio.CAP_V4L2` backend and set `CAP_PROP_FOURCC` to MJPG before setting resolution. Log the negotiated width, height, FOURCC, and FPS at INFO.
- Remove the `v4l1compat` detection, the `shouldShowV4lWarning` flag and `showV4lWarning()` from `Main`.

### 4.2 Recording and playback: Xuggle → JavaCV FFmpeg

- `RollingRecorder`, `ShotRecorder` → `org.bytedeco.javacv.FFmpegFrameRecorder`, writing MPEG-4 Part 2 in `.mp4` (what the shot recorder already used). The stream and calibrated-area debug recordings move from H.264 to MPEG-4 as well: the stock JavaCV FFmpeg build has no H.264 encoder without the GPL variant.
- `VideoPlayerController`, test `MockCamera` → `org.bytedeco.javacv.FFmpegFrameGrabber`.
- `Camera`, `CameraManager` Xuggle type references are replaced accordingly.
- Remove the `xuggle:xuggle-xuggler` dependency.

### 4.3 Camera discovery

- Keep `com.github.sarxos:webcam-capture` pinned at `0.3.12` for enumeration and `IpCamera`. Keep `webcam-capture-driver-ipcam` pinned.
- Remove `webcam-capture-driver-v4l4j` and the `V4l4jDriver` registration in `CameraFactory`.
- Keep `bridj` pinned at `0.7.0`.
- **Fallback, only if webcam-capture discovery fails on JDK 21:** a small Linux enumerator reading `/sys/class/video4linux/video*/name`. Not built unless needed.

### 4.4 Smaller changes

| Current | Where | Change |
|---|---|---|
| OpenIMAJ `core` | `JavaShotDetector` (`Parallel.forIndex`), `ShootOFFController` (`GlobalExecutorPool` shutdown) | `java.util.concurrent` / `IntStream.parallel()`; remove dependency |
| `com.shootoff.util.SwingFXUtils` (uses `sun.awt.image.IntegerComponentRaster`) | 9 files | Delete; use `javafx.embed.swing.SwingFXUtils` |
| json-simple | `JSONSessionWriter`, `JSONSessionReader`, `HardwareData` | Gson; session files remain readable and written in the same structure |
| OSHI 3 + jsoup passmark.com scrape | `HardwareData`, `Main.showFirstRunMessage` | OSHI 6 for CPU name and RAM; remove `getCpuScore` and jsoup; first-run message uses the existing RAM-based rating |
| logback 1.2 / slf4j 1.7 | logging | logback 1.5 / slf4j 2 |
| `com.google.code.findbugs:annotations` | `Main`, `ShootOFFController` | `com.github.spotbugs:spotbugs-annotations` |
| commons-cli `1.+` | `Configuration` | pin latest 1.x |
| httpclient, httpmime | unused | remove |
| MaryTTS 5.2 (`marytts-runtime`, `marytts-lang-en`, `voice-cmu-slt-hsmm`) | `TextToSpeech` | keep, pin `5.2.1` from the DFKI repo |
| JNA (transitive) | `PS3EyeCamera` | explicit, current pinned version |
| JUnit 4 + hamcrest | tests | JUnit 5 platform with `junit-vintage-engine`; existing tests unchanged; new tests use Jupiter |

### 4.5 Removed feature: headless mode

- Delete package `com.shootoff.headless` (including `protocol`), the `-h/--headless` option and `isHeadless()` in `Configuration`, the headless branches in `Main`, and the BlueCove and zxing dependencies.
- Gson stays (used for sessions after 4.4).
- Rationale: BlueCove is abandoned and its Linux native targets obsolete BlueZ interfaces; no maintained client exists; `HeadlessController` duplicates every GUI-facing interface. Git history preserves the code. A browser-based WebSocket remote is a candidate for sub-project 2.

### 4.6 Left as-is

- `PS3EyeCamera` (JNA + Windows DLLs), `OptiTrackCamera` and `NativeShotDetector` (JNI, natives not in repo). They must compile and continue to fail gracefully on Linux.

## 5. Plugins

### 5.1 Plugin loader

- `Plugin`:
  - Replace `AccessController.doPrivileged` with direct `new URLClassLoader(...)` construction.
  - Replace `Class.newInstance()` with `getDeclaredConstructor().newInstance()`.
  - Read the descriptor with `loader.findResource("shootoff.xml")`, so the plugin's own descriptor is used rather than one found first on ShootOFF's classpath.
- Parent classloader remains ShootOFF's; delegation stays parent-first.

### 5.2 Plugin API

The APIs RandomTargetParDrill uses are listed below. This sub-project does not intend to change them. They are **not** frozen, though: if modernization calls for changing one, change it and update the plugin in the same step.

- `ProjectorTrainingExerciseBase`: `getArenaPane()`, `setArenaBackground`, `addTarget`, `getArenaWidth`, `getArenaHeight`, `showTextOnFeed`, `addShootOFFButton`, `addShotTimerColumn`, `playSound` (both overloads), `clearShots`
- `CanvasManager.getCanvasGroup()`, `CanvasManager.addShot(DisplayShot, boolean)`
- `ArenaShot.getMarker()`, `Shot.getArenaX()`, `Shot.getArenaY()`, `DisplayShot`, `ShotColor`, `Hit`, `Target`, `TargetRegion`
- `ParListener`, `DelayedStartListener`, `LocatedImage`, `NamedThreadFactory`, `ExerciseMetadata`
- Conventions: `@`-prefixed target paths load through the plugin classloader; relative file paths resolve against the ShootOFF working directory.

### 5.3 RandomTargetParDrill updates (separate repository)

- Work in a real checkout (e.g. `~/projects/RandomTargetParDrill`), committed to that repo.
- Gradle wrapper 4.10.2 → 8.14; `build.gradle` → `build.gradle.kts`; Java 21 toolchain.
- Dependencies: `mavenLocal()` + `mavenCentral()`; `compileOnly("com.shootoff:shootoff:5.0.0-SNAPSHOT")`; JavaFX `compileOnly` via `org.openjfx.javafxplugin`; drop `slf4j-log4j12`.
- The plugin jar contains only the plugin's classes and resources.
- `copyPlugin` → `installPlugin`: copies the jar to `../ShootOFF/exercises/` by default, overridable with `-PshootoffHome=<dir>`.
- Java source changes only if a ShootOFF API change requires them.

## 6. Testing and verification

### 6.1 Baseline

- Run the suite 3× on Java 8 at the start of work and record per-test results. This determines whether the known failure is deterministic or flaky.
- "Matches baseline" means: every test that passed in all baseline runs passes, and no new failures appear.

### 6.2 New tests

- **Session round-trip:** no session fixture exists today. Before swapping json-simple, generate a session JSON fixture with the current writer and commit it under `src/test/resources/sessions/`. After the swap, read it with the Gson-based reader, write it with the new writer, and assert structural equality with the fixture.
- **Recorder round-trip:** write N synthetic frames with `FFmpegFrameRecorder` through the recorder classes, read them back with `FFmpegFrameGrabber`, and assert frame count (±1 for codec behavior) and dimensions.
- **Plugin loading:** during the test, build a minimal plugin jar containing a `TrainingExercise` implementation and a `shootoff.xml`, load it with `Plugin`, and assert the exercise instantiates.

### 6.3 Manual verification (owner's webcam attached)

1. Run `./gradlew run`, open the stream debugger, and note the FPS in its title bar. Confirm no FPS warning.
2. Confirm shots are detected from the webcam feed.
3. Confirm projector arena calibration completes.
4. Run RandomTargetParDrill end to end: sounds play, score summary is shown.
5. Record a session and play it back.
6. Confirm RandomShoot speaks via TTS.

If FPS is still low after the OpenCV change, diagnose before redesigning: compare `v4l2-ctl --list-formats-ext` output with the logged negotiated format.

## 7. Order of work

Each step is one or more commits on `modernize/java21` and leaves `test` green against the baseline before the next step begins.

0. Baseline (6.1). Commit the Gradle wrapper.
1. Remove headless, BlueCove, zxing, httpclient; remove dead build tasks and repositories; pin all versions. Still Java 8.
2. Generate the session fixture with the current writer (6.2). Then the small swaps from 4.4 that work on Java 8: OpenIMAJ, json-simple, `SwingFXUtils`, HardwareData/OSHI/jsoup, SpotBugs annotations. Add the session round-trip test.
3. OpenCV 4 via JavaCV and the FPS change (4.1); Xuggle → FFmpeg (4.2); remove v4l4j (4.3). Still Java 8. Gate: shot-detection video tests match baseline. Add the recorder round-trip test.
4. Switch to JDK 21: Kotlin DSL + version catalog, toolchain, OpenJFX 21, `Launcher`, `application` plugin with start-script `cd`, `maven-publish`, JUnit 5 + vintage, logback 1.5/slf4j 2, plugin loader cleanup (5.1), version bump. Add the plugin loading test.
5. Update RandomTargetParDrill (5.3); publish ShootOFF to `mavenLocal`; install and load the plugin.
6. Manual verification (6.3).

## 8. Risks

| Risk | Mitigation |
|---|---|
| OpenCV 4 changes shot-detection results on fixture videos (decoding or algorithm differences) | Step 3 gate against baseline; investigate per-test before adjusting any expectation, and never loosen an expectation without documenting why |
| MJPG/V4L2 does not fix the FPS problem | Diagnose per 6.3 before further changes |
| webcam-capture/BridJ misbehaves on JDK 21 | `/sys/class/video4linux` enumerator fallback (4.3) |
| MaryTTS DFKI repository becomes unavailable | Versions are pinned; if resolution fails, vendor the three jars into the repo, or fall back to pre-recorded audio (separate decision) |
| JavaCV native bundle size | Linux-only classifiers for now |

## 9. Out of scope (sub-project 2 or later)

CI, `jpackage` installers, multi-OS native bundles, GitHub releases, README/docs refresh, browser-based remote control, fixing absolute paths written into the tracked `shootoff.properties`, JPMS modularization.
