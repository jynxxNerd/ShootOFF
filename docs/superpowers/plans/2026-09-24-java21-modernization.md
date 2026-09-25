# ShootOFF Java 21 Modernization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ShootOFF builds, tests, and runs on Java 21 with OpenJFX, OpenCV 4 and FFmpeg via JavaCV, and the owner's RandomTargetParDrill plugin loads and plays.

**Architecture:** Swap dead or native-fragile libraries one at a time while still on Java 8, gating each swap on the existing 154-test suite (38 real-webcam videos drive the shot-detection tests). Then switch the build to Gradle Kotlin DSL with a Java 21 toolchain, and finally update the plugin in its own repository. Video I/O goes behind a small `com.shootoff.camera.video` adapter so recorders, the video player, and tests don't touch FFmpeg directly.

**Tech Stack:** Java 21, OpenJFX 21, Gradle 8.14 (Kotlin DSL, version catalog), JavaCV 1.5.14 (OpenCV 4.14, FFmpeg 8.1), MaryTTS 5.2.1, webcam-capture 0.3.12, Gson, OSHI 6, logback 1.5 / slf4j 2, JUnit 5 (vintage engine for existing JUnit 4 tests).

**Spec:** `docs/superpowers/specs/2026-09-24-java21-modernization-design.md`

## Global Constraints

- Work on branch `modernize/java21` in `/home/bfears/projects/ShootOFF`. Never commit to `master`.
- Commit messages: no `Co-Authored-By` trailer (owner preference).
- Do not commit `shootoff.properties`. It has local, uncommitted edits with absolute paths. Stage files explicitly and never use `git add -A` or `git add .`.
- Tasks 0–10 build with the system default JDK (Corretto 8, which bundles JavaFX) and the Groovy `build.gradle`. From Task 11 on, the build uses a Java 21 toolchain and Kotlin DSL.
- All dependency versions are pinned exactly. No dynamic versions (`1.+`).
- The application stays on the classpath (no JPMS `module-info.java`).
- Native libraries are bundled for `linux-x86_64` only.
- **Test gate:** "matches baseline" means `python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt` exits 0. That holds when every test that passed in all three baseline runs still passes. New tests must pass too.
- Recorded video is MPEG-4 Part 2 (`AV_CODEC_ID_MPEG4`) in `.mp4`. This deviates from the spec's "H.264" line: the stock JavaCV FFmpeg build has no H.264 encoder (that needs the GPL build). The shot-video recorder already used MPEG-4.
- Repositories: the spec said "Maven Central plus DFKI". Probing showed MaryTTS 5.2.1 itself is on Maven Central, but its dependency `de.dfki.lt.jtok:jtok-core` is only at DFKI and `gov.nist.math:Jampack:1.0` is only at `nexus.terrestris.de`. The final build keeps those two repositories, each content-filtered to what it provides.

## Review Focus

These are inputs the spec implies but no happy-path test exercises. Each has a test in the owning task.

1. **Cropped or odd-sized frames being recorded.** Cropping the feed to the projector area produces `getSubimage` views with odd dimensions. Recording must not fail and must record the visible region, not the parent image. *Test:* `TestVideoIO.testSubimageWithOddSizeIsRecorded` (Task 8).
2. **A shot right after recording starts, and recordings that roll over.** With less than 5 s of footage, the shot video keeps everything. Rolling at 15 s keeps timestamps continuous and deletes the rolling files. *Tests:* `TestRollingRecorder.testShotRightAfterStartKeepsAllFrames` and `testRolledRecordingStillProducesShotVideoAndCleansUp` (Task 10).
3. **Frames arriving with equal or backwards timestamps** (clock jitter, two frames in the same millisecond). The writer must keep going. *Test:* `TestVideoIO.testNonIncreasingTimestampsAreAccepted` (Task 8).
4. **Session files written by older versions**: integer-valued coordinates, named colors ("RED"/"INFRARED"), no `videos` key. They must still load. *Test:* `TestSessionIO.testReadsIntegerCoordinatesAndNamedColors` (Task 3).
5. **A broken plugin jar, or another `shootoff.xml` on ShootOFF's classpath.** A bad jar is rejected with `IllegalArgumentException`, and a plugin's own descriptor always wins. *Tests:* `TestPlugin.testJarWithoutDescriptorIsRejected` and the `src/test/resources/shootoff.xml` decoy in Task 13.

Also handled in code without dedicated tests: a missing session video skips that player tab instead of crashing (Task 10), and a failed shot-video fork skips that shot's video string instead of throwing an NPE (Task 10).

## File Map

| Area | Files |
|---|---|
| Tooling | Create `scripts/test_summary.py`, `docs/superpowers/baseline/java8-tests.txt` |
| Build | Modify then delete `build.gradle`. Create `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/gradle-daemon-jvm.properties`. Modify `.gitignore` |
| Headless removal | Delete `src/main/java/com/shootoff/headless/**`. Modify `Main.java`, `config/Configuration.java`, `gui/pane/ProjectorArenaPane.java`, `camera/CameraManager.java` |
| Sessions | Modify `session/io/JSONSessionReader.java`, `session/io/JSONSessionWriter.java`, `test/.../session/io/TestSessionIO.java`. Create `src/test/resources/sessions/legacy_session.json` |
| Hardware check | Rewrite `util/HardwareData.java`. Modify `Main.java`. Create `test/.../util/TestHardwareData.java` |
| Parallelism | Modify `camera/shotdetection/JavaShotDetector.java`, `gui/controller/ShootOFFController.java` |
| JavaFX Swing bridge | Delete `util/SwingFXUtils.java`. Change imports in 8 files |
| OpenCV | Modify `Main.java`, `camera/cameratypes/SarxosCaptureCamera.java`, `camera/CameraFactory.java`, `camera/autocalibration/AutoCalibrationManager.java`, `camera/shotdetection/JavaShotDetector.java`, and files using `Core.line/circle/rectangle`, plus 6 tests. Create `test/.../camera/cameratypes/TestSarxosCaptureCamera.java` |
| Video adapter | Create `camera/video/VideoWriter.java`, `VideoReader.java`, `TimedFrame.java`, `BgrImages.java` and `test/.../camera/video/TestVideoIO.java` |
| Recording and playback | Rewrite `camera/recorders/RollingRecorder.java`, `ShotRecorder.java`, `test/.../camera/MockCamera.java`. Modify `camera/CameraManager.java`, `camera/cameratypes/Camera.java`, `gui/CanvasManager.java`, `gui/controller/VideoPlayerController.java`. Create `test/.../camera/recorders/TestRollingRecorder.java` |
| Launch and plugins | Create `com/shootoff/Launcher.java`, `test/.../plugins/engine/TestPlugin.java`, `src/test/resources/shootoff.xml`, `test/.../plugins/TestTextToSpeechEngine.java`. Modify `plugins/engine/Plugin.java`, `src/main/resources/version.properties` |
| Plugin repo | `~/projects/RandomTargetParDrill`: `build.gradle` → `build.gradle.kts`, `settings.gradle` → `settings.gradle.kts`, wrapper files |

All `src/main/java/...` paths below are relative to `src/main/java/com/shootoff/` unless written in full.

---

### Task 0: Baseline and repository hygiene

**Files:**
- Create: `scripts/test_summary.py`
- Create: `docs/superpowers/baseline/java8-tests.txt`
- Modify: `.gitignore`
- Add to git: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Produces: `scripts/test_summary.py summarize|combine|compare`, used as the test gate by every later task.

- [ ] **Step 1: Write the test summary script**

Create `scripts/test_summary.py`:

```python
#!/usr/bin/env python3
"""Summarize JUnit XML results and compare them against a saved baseline.

  summarize RESULTS_DIR            print "PASS|FAIL|SKIP class.method" lines
  combine FILE...                  merge summaries; PASS only if PASS in every file
  compare RESULTS_DIR BASELINE     exit 1 if any baseline PASS test no longer passes
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def collect(results_dir):
    outcomes = {}
    for path in glob.glob(os.path.join(results_dir, "**", "TEST-*.xml"), recursive=True):
        for case in ET.parse(path).getroot().iter("testcase"):
            name = f"{case.get('classname')}.{case.get('name')}"
            if case.find("skipped") is not None:
                outcome = "SKIP"
            elif case.find("failure") is not None or case.find("error") is not None:
                outcome = "FAIL"
            else:
                outcome = "PASS"
            outcomes[name] = outcome
    if not outcomes:
        sys.exit(f"No test results found in {results_dir}")
    return outcomes


def read_summary(path):
    outcomes = {}
    with open(path) as f:
        for line in f:
            if line.strip():
                outcome, name = line.rstrip("\n").split(" ", 1)
                outcomes[name] = outcome
    return outcomes


def print_summary(outcomes):
    for name in sorted(outcomes):
        print(f"{outcomes[name]} {name}")


def main(argv):
    if len(argv) >= 2 and argv[0] == "summarize":
        print_summary(collect(argv[1]))
    elif len(argv) >= 2 and argv[0] == "combine":
        runs = [read_summary(p) for p in argv[1:]]
        names = set().union(*runs)
        print_summary({n: "PASS" if all(r.get(n) == "PASS" for r in runs) else "FAIL" for n in names})
    elif len(argv) == 3 and argv[0] == "compare":
        current = collect(argv[1])
        baseline = read_summary(argv[2])
        regressions = sorted(n for n, o in baseline.items() if o == "PASS" and current.get(n) != "PASS")
        new_failures = sorted(n for n, o in current.items() if o == "FAIL" and n not in baseline)
        for n in regressions:
            print(f"REGRESSION {n}: {current.get(n, 'MISSING')}")
        for n in new_failures:
            print(f"NEW TEST FAILING {n}")
        passed = sum(1 for o in current.values() if o == "PASS")
        print(f"{passed}/{len(current)} passing; {len(regressions)} regressions; {len(new_failures)} new failures")
        return 1 if regressions or new_failures else 0
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 2: Run the suite three times on Java 8 and record each run**

The Gradle exit code is non-zero because of the known failure, so ignore it.

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p docs/superpowers/baseline
for i in 1 2 3; do
  ./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1
  python3 scripts/test_summary.py summarize build/test-results/test > build/baseline-run$i.txt
done
python3 scripts/test_summary.py combine build/baseline-run{1,2,3}.txt > docs/superpowers/baseline/java8-tests.txt
grep -c '^PASS' docs/superpowers/baseline/java8-tests.txt; grep '^FAIL' docs/superpowers/baseline/java8-tests.txt
```

Expected: about 153 PASS lines. The FAIL lines include `com.shootoff.camera.TestCameraManagerVeryBright.testMSHD3000MinBrightnessMinContrastWhiteBalanceOff`, plus any test that was flaky across the three runs. Note the flaky ones in the commit message.

- [ ] **Step 3: Verify the gate passes against itself**

Run: `python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt; echo exit=$?`
Expected: `0 regressions; 0 new failures`, `exit=0`.

- [ ] **Step 4: Stop ignoring the wrapper jar**

`.gitignore` has `*.jar` under `# Package Files #`. Add directly below that line:

```
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 5: Commit**

```bash
git add scripts/test_summary.py docs/superpowers/baseline/java8-tests.txt .gitignore gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
git commit -m "Record Java 8 test baseline and commit Gradle wrapper"
```

---

### Task 1: Remove headless mode

**Files:**
- Delete: `src/main/java/com/shootoff/headless/` (whole directory, including `protocol/`)
- Modify: `Main.java:48,557-561,578-582`
- Modify: `config/Configuration.java:147,525,541,979-981`
- Modify: `gui/pane/ProjectorArenaPane.java:121-125,252-256`
- Modify: `camera/CameraManager.java:595-601`
- Modify: `build.gradle` (dependencies)

**Interfaces:**
- Produces: `Configuration.isHeadless()` no longer exists. `ProjectorArenaPane` always uses `MirroredCanvasManager`.

- [ ] **Step 1: Delete the package**

```bash
git rm -r -q src/main/java/com/shootoff/headless
```

- [ ] **Step 2: Remove the option from `Configuration`**

Delete the field `private boolean headless = false;`. Delete the line `options.addOption("h", "headless", false, "run without the main GUI and immediately open the projector arena");`. Delete the line `if (cmd.hasOption("h")) headless = true;`. Delete the whole method:

```java
	public boolean isHeadless() {
		return headless;
	}
```

- [ ] **Step 3: Remove the headless branches in `Main`**

Delete `import com.shootoff.headless.HeadlessController;`. Replace

```java
			if (config.isHeadless()) {
				config.setUseErrorReporting(false);
			} else {
				config.setUseErrorReporting(showFirstRunMessage());
			}
```

with

```java
			config.setUseErrorReporting(showFirstRunMessage());
```

and replace

```java
		if (config.isHeadless()) {
			new HeadlessController();
		} else {
			startGui(config);
		}
```

with

```java
		startGui(config);
```

- [ ] **Step 4: Remove the headless branches in `ProjectorArenaPane`**

Replace

```java
		if (config.isHeadless()) {
			canvasManager = new CanvasManager(arenaCanvasGroup, resetter, "arena", shotTimerModel);
		} else {
			canvasManager = new MirroredCanvasManager(arenaCanvasGroup, resetter, "arena", shotTimerModel, this);
		}
```

with

```java
		canvasManager = new MirroredCanvasManager(arenaCanvasGroup, resetter, "arena", shotTimerModel, this);
```

In the screen-detection method, replace the head of the chain

```java
		if (config.isHeadless()) {
			logger.debug("Headless, assuming smallest display is projector");

			projector = findSmallestScreen();
		} else if (Screen.getScreens().size() == 2) {
```

with

```java
		if (Screen.getScreens().size() == 2) {
```

Then run `grep -n "findSmallestScreen" src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java`. If only the method definition remains, delete that method.

- [ ] **Step 5: Remove the headless branch in `CameraManager.processFrame`**

Replace

```java
		if (!config.isHeadless()) {
			if (cropFeedToProjection && projectionBounds.isPresent()) {
				cameraView.updateBackground(currentImage, projectionBounds);
			} else {
				cameraView.updateBackground(currentImage, Optional.empty());
			}
		}
```

with

```java
		if (cropFeedToProjection && projectionBounds.isPresent()) {
			cameraView.updateBackground(currentImage, projectionBounds);
		} else {
			cameraView.updateBackground(currentImage, Optional.empty());
		}
```

- [ ] **Step 6: Drop the Bluetooth and QR dependencies**

In `build.gradle`, delete these lines and the comments directly above them:

```groovy
    implementation 'net.sf.bluecove:bluecove:2.1.0'
    implementation 'net.sf.bluecove:bluecove-gpl:2.1.0'
    implementation 'com.google.zxing:core:3.3.0'
```

Keep `com.google.code.gson:gson` (sessions use it from Task 3).

- [ ] **Step 7: Verify nothing references headless code, then run the gate**

```bash
grep -rn "isHeadless\|com.shootoff.headless\|bluecove\|zxing" src build.gradle
./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt
```

Expected: grep prints nothing. The gate reports `0 regressions; 0 new failures`.

- [ ] **Step 8: Commit**

```bash
git add -u src build.gradle
git commit -m "Remove headless Bluetooth mode and its BlueCove/zxing dependencies"
```

---

### Task 2: Clean up and pin the Groovy build (still Java 8)

**Files:**
- Modify: `build.gradle` (full rewrite)

**Interfaces:**
- Produces: a build with only `compileJava`/`test` plumbing and exact versions. `fxJar`, `zipRelease`, release, JWS, MSI and signing tasks are gone.

- [ ] **Step 1: Replace `build.gradle` entirely**

```groovy
apply plugin: 'java'

sourceCompatibility = 1.8
targetCompatibility = 1.8

repositories {
    mavenCentral()
    // de.dfki.lt.jtok:jtok-core (a MaryTTS dependency) is only published by DFKI
    maven { url "https://raw.githubusercontent.com/DFKI-MLT/Maven-Repository/main/" }
    // gov.nist.math:Jampack (a MaryTTS dependency) is not on Maven Central
    maven { url "https://nexus.terrestris.de/repository/public/" }
    // xuggle; removed together with Xuggle
    maven { url "https://maven.dcm4che.org/" }
    // com.twmacinta:fast-md5 (an OpenIMAJ dependency); removed together with OpenIMAJ
    maven { url "https://nrgxnat.jfrog.io/artifactory/libs-release/" }
}

configurations {
    // webcam-capture and MaryTTS pull in slf4j bindings that conflict with logback
    implementation.exclude group: 'org.slf4j', module: 'slf4j-log4j12'
}

dependencies {
    // Annotations to suppress SpotBugs warnings (same package as the old FindBugs annotations)
    implementation 'com.github.spotbugs:spotbugs-annotations:4.10.4'

    implementation 'ch.qos.logback:logback-core:1.2.12'
    implementation 'ch.qos.logback:logback-classic:1.2.12'

    // webcam-capture fetches bridj 0.6.2, which does not play nicely with
    // stackguard in newer JVMs
    implementation 'com.nativelibs4java:bridj:0.7.0'
    implementation 'com.github.sarxos:webcam-capture:0.3.12'
    implementation 'com.github.sarxos:webcam-capture-driver-ipcam:0.3.12'
    implementation 'com.github.sarxos:webcam-capture-driver-v4l4j:0.3.12'

    implementation 'commons-cli:commons-cli:1.11.0'

    // Text-to-speech
    implementation 'de.dfki.mary:marytts-runtime:5.2.1'
    implementation 'de.dfki.mary:marytts-lang-en:5.2.1'
    implementation 'de.dfki.mary:voice-cmu-slt-hsmm:5.2.1'

    implementation 'xuggle:xuggle-xuggler:5.4'
    implementation 'com.googlecode.json-simple:json-simple:1.1.1'
    implementation('org.openimaj:core:1.3.10') {
        // Transitive dependency that is not needed and no longer exists in any repository
        exclude group: 'vigna.dsi.unimi.it'
    }
    implementation 'org.openpnp:opencv:2.4.13-0'
    implementation 'com.github.dblock:oshi-core:3.4.0'
    implementation 'org.jsoup:jsoup:1.23.2'
    implementation 'com.google.code.gson:gson:2.8.0'

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.hamcrest:hamcrest-core:1.3'
}

test {
    testLogging {
        exceptionFormat = 'full'
    }
}
```

This drops `com.google.code.findbugs:annotations` (replaced by SpotBugs, same `edu.umd.cs.findbugs.annotations` package) and the unused `httpclient`/`httpmime`.

- [ ] **Step 2: Verify dependencies resolve with no dynamic versions**

Run: `./gradlew -q dependencies --configuration runtimeClasspath | grep -E '^[+\\]--- .*[.:]\+( |$)'`
Expected: no output. No top-level dependency uses a dynamic `+` version.

Run: `./gradlew compileJava --console=plain -q`
Expected: succeeds, with the same 5 `IntegerComponentRaster` warnings as before.

- [ ] **Step 3: Run the gate**

Run: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`.

- [ ] **Step 4: Commit**

```bash
git add build.gradle
git commit -m "Remove JavaFX Ant, Web Start and release tasks; pin all dependency versions"
```

---

### Task 3: Sessions: legacy fixture and json-simple → Gson

**Files:**
- Create: `src/test/resources/sessions/legacy_session.json`
- Modify: `src/test/java/com/shootoff/session/io/TestSessionIO.java`
- Rewrite: `session/io/JSONSessionWriter.java`, `session/io/JSONSessionReader.java` (keep license headers)
- Modify: `build.gradle`

**Interfaces:**
- Consumes: `SessionIO.saveSession(SessionRecorder, File)`, `SessionIO.loadSession(File) → Optional<SessionRecorder>`, `new JSONSessionReader(File).load() → Map<String, List<Event>>` (all unchanged).

- [ ] **Step 1: Generate the legacy fixture with the current json-simple writer**

Temporarily add this method to `TestSessionIO`:

```java
	@Test
	public void writeLegacyFixture() {
		SessionIO.saveSession(sessionRecorder, new File("src/test/resources/sessions/legacy_session.json"));
	}
```

Run:

```bash
mkdir -p src/test/resources/sessions
./gradlew test --tests com.shootoff.session.io.TestSessionIO.writeLegacyFixture --console=plain -q
head -c 300 src/test/resources/sessions/legacy_session.json
```

Expected: JSON beginning `{"cameras":[{`. Now delete the `writeLegacyFixture` method.

- [ ] **Step 2: Add characterization and edge-case tests**

Add these imports to `TestSessionIO`:

```java
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
```

Add these members:

```java
	private static final File LEGACY_SESSION = new File("src/test/resources/sessions/legacy_session.json");

	@Test
	public void testReadsLegacyJSONSession() {
		checkSession(SessionIO.loadSession(LEGACY_SESSION));
	}

	@Test
	public void testRewritingLegacyJSONSessionKeepsItsStructure() throws IOException {
		final File rewritten = new File("temp_rewritten_session.json");
		try {
			SessionIO.saveSession(SessionIO.loadSession(LEGACY_SESSION).get(), rewritten);
			assertEquals(camerasByName(LEGACY_SESSION), camerasByName(rewritten));
		} finally {
			if (!rewritten.delete()) System.err.println("Failed to delete " + rewritten.getPath());
		}
	}

	// Camera order in the file follows map iteration order, so compare cameras by name
	private static Map<String, JsonElement> camerasByName(File sessionFile) throws IOException {
		final Map<String, JsonElement> cameras = new HashMap<>();
		try (Reader reader = Files.newBufferedReader(sessionFile.toPath(), StandardCharsets.UTF_8)) {
			for (final JsonElement camera : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cameras")) {
				final JsonObject cameraObject = camera.getAsJsonObject();
				cameras.put(cameraObject.get("name").getAsString(), cameraObject.get("events"));
			}
		}
		return cameras;
	}

	@Test
	public void testReadsIntegerCoordinatesAndNamedColors() throws IOException {
		final File session = new File("temp_handwritten_session.json");
		final String json = "{\"cameras\":[{\"name\":\"Default\",\"events\":["
				+ "{\"type\":\"shot\",\"timestamp\":5,\"color\":\"RED\",\"x\":10,\"y\":11,\"shotTimestamp\":3,"
				+ "\"markerRadius\":2,\"isMalfunction\":false,\"isReload\":false,\"targetIndex\":-1,\"hitRegionIndex\":-1},"
				+ "{\"type\":\"shot\",\"timestamp\":6,\"color\":\"INFRARED\",\"x\":12.5,\"y\":15,\"shotTimestamp\":4,"
				+ "\"markerRadius\":5,\"isMalfunction\":false,\"isReload\":true,\"targetIndex\":0,\"hitRegionIndex\":1},"
				+ "{\"type\":\"targetResized\",\"timestamp\":7,\"index\":0,\"newWidth\":10,\"newHeight\":20}"
				+ "]}]}";
		Files.write(session.toPath(), json.getBytes(StandardCharsets.UTF_8));

		try {
			final List<Event> events = new JSONSessionReader(session).load().get("Default");

			assertEquals(3, events.size());
			final ShotEvent red = (ShotEvent) events.get(0);
			assertEquals(ShotColor.RED, red.getShot().getColor());
			assertEquals(10, red.getShot().getX(), 0.001);
			assertFalse(red.getTargetIndex().isPresent());
			assertFalse(red.getVideoString().isPresent());

			final ShotEvent infrared = (ShotEvent) events.get(1);
			assertEquals(ShotColor.INFRARED, infrared.getShot().getColor());
			assertEquals(12.5, infrared.getShot().getX(), 0.001);
			assertEquals(1, infrared.getHitRegionIndex().get().intValue());
			assertTrue(infrared.isReload());

			assertEquals(10, ((TargetResizedEvent) events.get(2)).getNewWidth(), 0.001);
		} finally {
			if (!session.delete()) System.err.println("Failed to delete " + session.getPath());
		}
	}
```

- [ ] **Step 3: Run the session tests and check that the right ones fail**

Run: `./gradlew test --tests 'com.shootoff.session.io.TestSessionIO' --console=plain`
Expected: `testReadsLegacyJSONSession` and `testRewritingLegacyJSONSessionKeepsItsStructure` PASS. They pin today's behavior. `testReadsIntegerCoordinatesAndNamedColors` FAILS with `ClassCastException` (`Long cannot be cast to Double`), which shows json-simple's strict casts.

- [ ] **Step 4: Rewrite `JSONSessionWriter` with Gson**

Keep the license header. Replace everything from `package` down:

```java
package com.shootoff.session.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.shootoff.camera.shot.DisplayShot;

public class JSONSessionWriter implements EventVisitor {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Logger logger = LoggerFactory.getLogger(JSONSessionWriter.class);

	private final File sessionFile;
	private final JsonArray cameras = new JsonArray();
	private JsonObject currentCamera;
	private JsonArray currentCameraEvents;

	public JSONSessionWriter(File sessionFile) {
		this.sessionFile = sessionFile;
	}

	@Override
	public void visitCamera(String cameraName) {
		currentCamera = new JsonObject();
		currentCamera.addProperty("name", cameraName);

		currentCameraEvents = new JsonArray();
	}

	@Override
	public void visitCameraEnd() {
		currentCamera.add("events", currentCameraEvents);
		cameras.add(currentCamera);
	}

	@Override
	public void visitShot(long timestamp, DisplayShot shot, boolean isMalfunction, boolean isReload,
			Optional<Integer> targetIndex, Optional<Integer> hitRegionIndex, Optional<String> videoString) {

		final JsonObject event = new JsonObject();
		event.addProperty("type", "shot");
		event.addProperty("timestamp", timestamp);
		event.addProperty("color", shot.getPaintColor().toString());
		event.addProperty("x", shot.getX());
		event.addProperty("y", shot.getY());
		event.addProperty("shotTimestamp", shot.getTimestamp());
		event.addProperty("markerRadius", (int) shot.getMarker().getRadiusX());
		event.addProperty("isMalfunction", isMalfunction);
		event.addProperty("isReload", isReload);
		event.addProperty("targetIndex", targetIndex.orElse(-1));
		event.addProperty("hitRegionIndex", hitRegionIndex.orElse(-1));

		if (videoString.isPresent()) {
			event.addProperty("videos", videoString.get());
		}

		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetAdd(long timestamp, String targetName) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetAdded");
		event.addProperty("timestamp", timestamp);
		event.addProperty("name", targetName);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetRemove(long timestamp, int targetIndex) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetRemoved");
		event.addProperty("timestamp", timestamp);
		event.addProperty("index", targetIndex);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetResize(long timestamp, int targetIndex, double newWidth, double newHeight) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetResized");
		event.addProperty("timestamp", timestamp);
		event.addProperty("index", targetIndex);
		event.addProperty("newWidth", newWidth);
		event.addProperty("newHeight", newHeight);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitTargetMove(long timestamp, int targetIndex, int newX, int newY) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "targetMoved");
		event.addProperty("timestamp", timestamp);
		event.addProperty("index", targetIndex);
		event.addProperty("newX", newX);
		event.addProperty("newY", newY);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitExerciseFeedMessage(long timestamp, String message) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "exerciseFeedMessage");
		event.addProperty("timestamp", timestamp);
		event.addProperty("message", message);

		currentCameraEvents.add(event);
	}

	@Override
	public void visitEnd() {
		final JsonObject session = new JsonObject();
		session.add("cameras", cameras);

		try (Writer file = new OutputStreamWriter(new FileOutputStream(sessionFile), StandardCharsets.UTF_8)) {
			GSON.toJson(session, file);
		} catch (final IOException | JsonIOException e) {
			logger.error("Error writing JSON session", e);
		}
	}
}
```

- [ ] **Step 5: Rewrite `JSONSessionReader` with Gson**

Keep the license header. Replace everything from `package` down:

```java
package com.shootoff.session.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.session.Event;
import com.shootoff.session.ExerciseFeedMessageEvent;
import com.shootoff.session.ShotEvent;
import com.shootoff.session.TargetAddedEvent;
import com.shootoff.session.TargetMovedEvent;
import com.shootoff.session.TargetRemovedEvent;
import com.shootoff.session.TargetResizedEvent;

public class JSONSessionReader {
	private final Logger logger = LoggerFactory.getLogger(JSONSessionReader.class);

	private final File sessionFile;

	public JSONSessionReader(File sessionFile) {
		this.sessionFile = sessionFile;
	}

	public Map<String, List<Event>> load() {
		final Map<String, List<Event>> events = new HashMap<>();

		try (Reader reader = new InputStreamReader(new FileInputStream(sessionFile), StandardCharsets.UTF_8)) {
			final JsonObject session = JsonParser.parseReader(reader).getAsJsonObject();

			for (final JsonElement cameraElement : session.getAsJsonArray("cameras")) {
				final JsonObject camera = cameraElement.getAsJsonObject();

				final String cameraName = camera.get("name").getAsString();
				final List<Event> cameraEvents = new ArrayList<>();
				events.put(cameraName, cameraEvents);

				for (final JsonElement eventElement : camera.getAsJsonArray("events")) {
					final JsonObject event = eventElement.getAsJsonObject();
					final long timestamp = event.get("timestamp").getAsLong();

					switch (event.get("type").getAsString()) {
					case "shot":
						final DisplayShot shot = new DisplayShot(parseColor(event.get("color").getAsString()),
								event.get("x").getAsDouble(), event.get("y").getAsDouble(),
								event.get("shotTimestamp").getAsLong(), event.get("markerRadius").getAsInt());

						final Optional<String> videoString = event.has("videos")
								? Optional.of(event.get("videos").getAsString()) : Optional.empty();

						cameraEvents.add(new ShotEvent(cameraName, timestamp, shot,
								event.get("isMalfunction").getAsBoolean(), event.get("isReload").getAsBoolean(),
								optionalIndex(event, "targetIndex"), optionalIndex(event, "hitRegionIndex"),
								videoString));
						break;

					case "targetAdded":
						cameraEvents.add(new TargetAddedEvent(cameraName, timestamp, event.get("name").getAsString()));
						break;

					case "targetRemoved":
						cameraEvents.add(new TargetRemovedEvent(cameraName, timestamp, event.get("index").getAsInt()));
						break;

					case "targetResized":
						cameraEvents.add(new TargetResizedEvent(cameraName, timestamp, event.get("index").getAsInt(),
								event.get("newWidth").getAsDouble(), event.get("newHeight").getAsDouble()));
						break;

					case "targetMoved":
						cameraEvents.add(new TargetMovedEvent(cameraName, timestamp, event.get("index").getAsInt(),
								event.get("newX").getAsInt(), event.get("newY").getAsInt()));
						break;

					case "exerciseFeedMessage":
						cameraEvents.add(
								new ExerciseFeedMessageEvent(cameraName, timestamp, event.get("message").getAsString()));
						break;
					}
				}
			}
		} catch (IOException | JsonParseException | IllegalStateException e) {
			logger.error("Error reading JSON session", e);
		}

		return events;
	}

	// Older sessions stored JavaFX paint strings instead of color names
	private static ShotColor parseColor(String color) {
		if ("0xff0000ff".equals(color) || "RED".equals(color)) {
			return ShotColor.RED;
		} else if ("0xffa500ff".equals(color) || "INFRARED".equals(color)) {
			return ShotColor.INFRARED;
		} else {
			return ShotColor.GREEN;
		}
	}

	private static Optional<Integer> optionalIndex(JsonObject event, String key) {
		final int index = event.get(key).getAsInt();
		return index == -1 ? Optional.empty() : Optional.of(index);
	}
}
```

- [ ] **Step 6: Update the build**

In `build.gradle`, keep `implementation 'com.googlecode.json-simple:json-simple:1.1.1'`. `util/HardwareData.java` still uses it until Task 4 removes it. Change `implementation 'com.google.code.gson:gson:2.8.0'` to `implementation 'com.google.code.gson:gson:2.13.2'`.

- [ ] **Step 7: Run the session tests, then the gate**

Run: `./gradlew test --tests 'com.shootoff.session.io.TestSessionIO' --console=plain`
Expected: all 5 tests PASS.

Run: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`.

- [ ] **Step 8: Commit**

```bash
git add src/test/resources/sessions/legacy_session.json src/test/java/com/shootoff/session/io/TestSessionIO.java src/main/java/com/shootoff/session/io/JSONSessionWriter.java src/main/java/com/shootoff/session/io/JSONSessionReader.java build.gradle
git commit -m "Read and write JSON sessions with Gson; pin legacy session compatibility with a fixture"
```

---

### Task 4: Hardware check: OSHI 6, drop the passmark.com scrape

**Files:**
- Rewrite: `util/HardwareData.java` (keep the ShootOFF license header; the yakka34 MIT block goes with the removed code)
- Modify: `Main.java` (`showFirstRunMessage`, `setHardwareMessage(Label, int)`, `MINIMUM_CPU_SCORE_*` constants)
- Create: `src/test/java/com/shootoff/util/TestHardwareData.java`
- Modify: `build.gradle`

**Interfaces:**
- Produces: `HardwareData.getCpuName() → String`, `HardwareData.getMegabytesOfRam() → long`. `getCpuScore()` no longer exists.

- [ ] **Step 1: Write the test**

```java
package com.shootoff.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestHardwareData {
	@Test
	public void testReportsInstalledRam() {
		assertTrue(HardwareData.getMegabytesOfRam() > 0);
	}

	@Test
	public void testReportsCpuName() {
		assertFalse(HardwareData.getCpuName().trim().isEmpty());
	}
}
```

- [ ] **Step 2: Run it against the current code**

Run: `./gradlew test --tests com.shootoff.util.TestHardwareData --console=plain`
Expected: PASS on OSHI 3. It pins behavior across the upgrade.

- [ ] **Step 3: Switch the dependency and rewrite `HardwareData`**

In `build.gradle`, replace `implementation 'com.github.dblock:oshi-core:3.4.0'` with `implementation 'com.github.oshi:oshi-core:6.12.0'`. Delete `implementation 'org.jsoup:jsoup:1.23.2'` and `implementation 'com.googlecode.json-simple:json-simple:1.1.1'`.

`HardwareData.java` below the license header:

```java
package com.shootoff.util;

import oshi.SystemInfo;

public class HardwareData {
	private static final SystemInfo si = new SystemInfo();
	private static final long BYTES_IN_MEGABYTE = 1048576;

	public static String getCpuName() {
		return si.getHardware().getProcessor().getProcessorIdentifier().getName();
	}

	public static long getMegabytesOfRam() {
		return si.getHardware().getMemory().getTotal() / BYTES_IN_MEGABYTE;
	}
}
```

- [ ] **Step 4: Rate hardware by RAM only in `Main.showFirstRunMessage`**

Replace the thread body

```java
		new Thread(() -> {
			final String cpuName = HardwareData.getCpuName();
			final Optional<Integer> cpuScore = HardwareData.getCpuScore();
			final long installedRam = HardwareData.getMegabytesOfRam();

			if (cpuScore.isPresent()) {
				if (logger.isDebugEnabled()) logger.debug("Processor: {}, Processor Score: {}, installed RAM: {} MB",
						cpuName, cpuScore.get(), installedRam);

				Platform.runLater(() -> setHardwareMessage(hardwareMessageLabel, cpuScore.get()));
			} else {
				if (logger.isDebugEnabled()) logger.debug("Processor: {}, installed RAM: {} MB", cpuName, installedRam);

				Platform.runLater(() -> setHardwareMessage(hardwareMessageLabel, installedRam));
			}
		}).start();
```

with

```java
		new Thread(() -> {
			final String cpuName = HardwareData.getCpuName();
			final long installedRam = HardwareData.getMegabytesOfRam();

			if (logger.isDebugEnabled()) logger.debug("Processor: {}, installed RAM: {} MB", cpuName, installedRam);

			Platform.runLater(() -> setHardwareMessage(hardwareMessageLabel, installedRam));
		}).start();
```

Delete the method `private void setHardwareMessage(Label hardwareMessageLabel, int cpuScore)` and the `MINIMUM_CPU_SCORE_PASSABLE` / `MINIMUM_CPU_SCORE_EXCELLENT` constants. If `Optional` is no longer used in `Main`, the compiler won't complain, so leave the import alone unless your IDE flags it.

- [ ] **Step 5: Verify**

Run: `grep -rn "jsoup\|org.json.simple\|getCpuScore\|MINIMUM_CPU_SCORE" src; ./gradlew test --tests com.shootoff.util.TestHardwareData --console=plain`
Expected: grep prints nothing, and the test PASSES.

Run the gate: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/shootoff/util/HardwareData.java src/main/java/com/shootoff/Main.java src/test/java/com/shootoff/util/TestHardwareData.java build.gradle
git commit -m "Use OSHI 6 for hardware info and drop the passmark.com CPU score scrape"
```

---

### Task 5: Replace OpenIMAJ parallelism with the JDK

**Files:**
- Modify: `camera/shotdetection/JavaShotDetector.java:30-32,107-111,359-393`
- Modify: `gui/controller/ShootOFFController.java:36,361`
- Modify: `build.gradle`

- [ ] **Step 1: Replace the parallel sector loop**

In `JavaShotDetector`, delete the imports `org.openimaj.util.function.Operation`, `org.openimaj.util.parallel.GlobalExecutorPool` and `org.openimaj.util.parallel.Parallel`, and add `import java.util.stream.IntStream;`. Delete from the constructor:

```java
		GlobalExecutorPool.getPool().setRejectedExecutionHandler((r, p) -> {
			if (!p.isShutdown()) {
				logger.error("Shot detection thread was rejected but GlobalExecutorPool was not shutdown");
			}
		});
```

In `findThresholdPixelsAndUpdateFilter`, replace the opening

```java
		Parallel.forIndex(0, (SECTOR_ROWS * SECTOR_COLUMNS), 1, new Operation<Integer>() {
			@Override
			public void perform(Integer sector) {
				final int sectorX = sector.intValue() % SECTOR_COLUMNS;
				final int sectorY = sector.intValue() / SECTOR_ROWS;
```

with

```java
		IntStream.range(0, SECTOR_ROWS * SECTOR_COLUMNS).parallel().forEach(sector -> {
				final int sectorX = sector % SECTOR_COLUMNS;
				final int sectorY = sector / SECTOR_ROWS;
```

Replace the closing `}\n\t\t});` of the anonymous class (the `}` closing `perform` followed by `});`) with a single `});`. The loop body, including its `return;` statements and the interrupt check, stays unchanged. Re-indent the body one tab less.

- [ ] **Step 2: Remove the pool shutdown**

In `ShootOFFController`, delete `import org.openimaj.util.parallel.GlobalExecutorPool;` and the line `GlobalExecutorPool.getPool().shutdownNow();`.

- [ ] **Step 3: Drop the dependency and its repository**

In `build.gradle`, delete the `implementation('org.openimaj:core:1.3.10') { ... }` block and the `nrgxnat.jfrog.io` repository with its comment.

- [ ] **Step 4: Verify with the shot-detection suite**

Run: `grep -rn openimaj src build.gradle`
Expected: no output.

Run the gate: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`. The `TestCameraManager*` classes exercise this loop on all 38 videos.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/shootoff/camera/shotdetection/JavaShotDetector.java src/main/java/com/shootoff/gui/controller/ShootOFFController.java build.gradle
git commit -m "Replace OpenIMAJ parallel loop with a parallel IntStream"
```

---

### Task 6: Use JavaFX's own SwingFXUtils

**Files:**
- Delete: `util/SwingFXUtils.java` (a copy of OpenJFX 8u60 code that imports `sun.awt.image.IntegerComponentRaster`)
- Modify: every file importing `com.shootoff.util.SwingFXUtils`

- [ ] **Step 1: Swap the imports and delete the copy**

```bash
grep -rl "import com.shootoff.util.SwingFXUtils;" src | xargs sed -i 's/import com.shootoff.util.SwingFXUtils;/import javafx.embed.swing.SwingFXUtils;/'
git rm -q src/main/java/com/shootoff/util/SwingFXUtils.java
grep -rn "com.shootoff.util.SwingFXUtils\|IntegerComponentRaster" src
```

Expected: the final grep prints nothing. Classes in package `com.shootoff.util` that used `SwingFXUtils` without an import would now fail to compile. Step 2 catches that, and the fix is to add `import javafx.embed.swing.SwingFXUtils;`.

- [ ] **Step 2: Compile and run the gate**

Run: `./gradlew compileJava compileTestJava --console=plain -q`
Expected: succeeds with no `internal proprietary API` warnings.

Run the gate: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`.

- [ ] **Step 3: Commit**

```bash
git add -u src
git commit -m "Use javafx.embed.swing.SwingFXUtils instead of a copy that needs sun.awt internals"
```

---

### Task 7: OpenCV 4 via JavaCPP presets, V4L2 + MJPG capture

**Files:**
- Modify: `build.gradle`
- Modify: `Main.java` (`main`, delete `showV4lWarning`, `closeNoV4lCompat`, `shouldShowV4lWarning`)
- Modify: `camera/cameratypes/SarxosCaptureCamera.java`
- Modify: `camera/CameraFactory.java`
- Modify: `camera/autocalibration/AutoCalibrationManager.java`, `camera/shotdetection/JavaShotDetector.java` (Highgui → Imgcodecs)
- Modify: every file calling `Core.line`, `Core.circle`, `Core.rectangle`
- Modify tests: `config/TestConfiguration.java`, `camera/TestDeduplicationProcessor.java`, `camera/perspective/TestPerspectiveManager.java`, `gui/TestCanvasManager.java`, `camera/TestAutoCalibration.java`, `camera/ShotDetectionTestor.java`
- Create: `src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java`

**Interfaces:**
- Produces: `SarxosCaptureCamera.applyCaptureSettings(VideoCapture)` (package-private static) and `SarxosCaptureCamera.fourccToString(double) → String` (package-private static).
- Produces: OpenCV native loading is `org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class)`.

- [ ] **Step 1: Swap the dependency**

In `build.gradle`, replace `implementation 'org.openpnp:opencv:2.4.13-0'` with:

```groovy
    // OpenCV 4 (the standard org.opencv.* API) from the JavaCPP presets.
    // Only Linux x86_64 natives are bundled for now.
    implementation 'org.bytedeco:javacpp:1.5.14'
    implementation 'org.bytedeco:javacpp:1.5.14:linux-x86_64'
    implementation 'org.bytedeco:opencv:4.14.0-1.5.14'
    implementation 'org.bytedeco:opencv:4.14.0-1.5.14:linux-x86_64'
    implementation 'org.bytedeco:openblas:0.3.34-1.5.14'
    implementation 'org.bytedeco:openblas:0.3.34-1.5.14:linux-x86_64'
```

Delete `implementation 'com.github.sarxos:webcam-capture-driver-v4l4j:0.3.12'`.

- [ ] **Step 2: Write the failing capture-settings test**

`src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java`:

```java
package com.shootoff.camera.cameratypes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opencv.videoio.VideoCapture;

public class TestSarxosCaptureCamera {
	@BeforeClass
	public static void loadOpenCV() {
		Loader.load(opencv_java.class);
	}

	@Test
	public void testFourccToStringDecodesMjpg() {
		assertEquals("MJPG", SarxosCaptureCamera.fourccToString(org.opencv.videoio.VideoWriter.fourcc('M', 'J', 'P', 'G')));
	}

	@Test
	public void testCaptureSettingsDoNotFailWhenCameraRejectsThem() {
		// An unopened capture rejects every property, like a camera that does not support MJPG
		final VideoCapture capture = new VideoCapture();

		SarxosCaptureCamera.applyCaptureSettings(capture);

		assertFalse(capture.isOpened());
	}
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests com.shootoff.camera.cameratypes.TestSarxosCaptureCamera --console=plain`
Expected: compilation FAILS on `applyCaptureSettings` / `fourccToString`, and the main code fails on `org.opencv.highgui`.

- [ ] **Step 4: Port `SarxosCaptureCamera`**

Replace the imports `org.opencv.highgui.Highgui` and `org.opencv.highgui.VideoCapture` with `org.opencv.videoio.VideoCapture` and `org.opencv.videoio.Videoio`, and add `import com.shootoff.util.SystemInfo;`. Replace the body of `open()` from `final boolean open = camera.open(cameraIndex);` through `return open;` with:

```java
		// V4L2 is the only backend that can negotiate MJPG on Linux
		final boolean open = SystemInfo.isLinux() ? camera.open(cameraIndex, Videoio.CAP_V4L2)
				: camera.open(cameraIndex);

		if (open) {
			applyCaptureSettings(camera);
			CameraFactory.openCamerasAdd(this);
		}

		return open;
```

Add these methods to the class:

```java
	static void applyCaptureSettings(final VideoCapture capture) {
		// Uncompressed YUYV at high resolutions exceeds USB 2.0 bandwidth, which
		// holds many webcams to a few FPS. MJPG avoids that. Cameras that do not
		// support MJPG keep their default format.
		capture.set(Videoio.CAP_PROP_FOURCC, org.opencv.videoio.VideoWriter.fourcc('M', 'J', 'P', 'G'));

		// Set the max FPS to 60. If we don't set this it defaults
		// to 30, which unnecessarily hampers higher end cameras
		capture.set(Videoio.CAP_PROP_FPS, 60);

		logger.info("Camera capture negotiated {}x{} {} at {} FPS", (int) capture.get(Videoio.CAP_PROP_FRAME_WIDTH),
				(int) capture.get(Videoio.CAP_PROP_FRAME_HEIGHT), fourccToString(capture.get(Videoio.CAP_PROP_FOURCC)),
				capture.get(Videoio.CAP_PROP_FPS));
	}

	static String fourccToString(final double fourcc) {
		final int code = (int) fourcc;
		return new String(new char[] { (char) (code & 0xFF), (char) ((code >> 8) & 0xFF),
				(char) ((code >> 16) & 0xFF), (char) ((code >> 24) & 0xFF) });
	}
```

In `setViewSize`, `getViewSize` and `launchCameraSettings`, rename:
- `Highgui.CV_CAP_PROP_FRAME_WIDTH` → `Videoio.CAP_PROP_FRAME_WIDTH`
- `Highgui.CV_CAP_PROP_FRAME_HEIGHT` → `Videoio.CAP_PROP_FRAME_HEIGHT`
- `Highgui.CV_CAP_PROP_SETTINGS` → `Videoio.CAP_PROP_SETTINGS`

`setViewSize` runs after `open`, so resolution is applied after the MJPG request. That's the order V4L2 needs.

- [ ] **Step 5: Mechanical OpenCV 2.4 → 4 renames**

```bash
grep -rl "Highgui.imwrite" src | xargs sed -i 's/Highgui\.imwrite/Imgcodecs.imwrite/g; s/import org\.opencv\.highgui\.Highgui;/import org.opencv.imgcodecs.Imgcodecs;/'
grep -rlE "Core\.(line|circle|rectangle)\(" src | xargs sed -i -E 's/Core\.(line|circle|rectangle)\(/Imgproc.\1(/g'
grep -rn "org.opencv.highgui\|Core\.\(line\|circle\|rectangle\)(" src
```

Expected: the last grep prints nothing. Files that now call `Imgproc.*` without importing it get fixed in Step 8 by adding `import org.opencv.imgproc.Imgproc;`. If `Core` becomes unused in a file, remove its import.

- [ ] **Step 6: Load OpenCV through JavaCPP and remove the v4l1compat workaround in `Main.main`**

Replace the whole block from `if (SystemInfo.isMacOsX()) {` through the standalone `nu.pattern.OpenCV.loadShared();` that follows it with:

```java
		Loader.load(opencv_java.class);

		// Check the comment at the top of the Camera class
		// for more information about this hack
		if (SystemInfo.isMacOsX()) CameraFactory.getDefault();
```

Add imports `org.bytedeco.javacpp.Loader` and `org.bytedeco.opencv.opencv_java`. Remove `@SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")` from `main`, since the hard-coded `/usr/lib/libv4l` path is gone. Delete the field `shouldShowV4lWarning`, the line `if (shouldShowV4lWarning) showV4lWarning();`, and the methods `showV4lWarning()` and `closeNoV4lCompat(File)`.

- [ ] **Step 7: Load OpenCV the same way in tests, and drop the v4l4j driver**

```bash
grep -rl "nu.pattern.OpenCV.loadShared();" src/test | xargs sed -i 's/nu\.pattern\.OpenCV\.loadShared();/org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java.class);/'
```

In `CameraFactory`, delete `import com.github.sarxos.webcam.ds.v4l4j.V4l4jDriver;`, and replace the `CompositeDriver` constructor body with:

```java
			super();
			add(new WebcamDefaultDriver());
			add(new IpCamDriver());
```

- [ ] **Step 8: Compile, fix leftovers, and run the new test**

Run: `./gradlew compileJava compileTestJava --console=plain -q`
If a file reports `cannot find symbol Imgproc`, add `import org.opencv.imgproc.Imgproc;` to it. For any other OpenCV 2.4-only symbol, look up its 4.x location with `unzip -l ~/.gradle/caches/modules-2/files-2.1/org.bytedeco/opencv/4.14.0-1.5.14/*/opencv-4.14.0-1.5.14.jar | grep -i <Name>`.

Run: `./gradlew test --tests com.shootoff.camera.cameratypes.TestSarxosCaptureCamera --console=plain`
Expected: both tests PASS.

- [ ] **Step 9: Run the gate (OpenCV changed; decoding is still Xuggle)**

Run: `grep -rn "nu.pattern\|v4l1compat\|V4l4j" src build.gradle; ./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: grep prints nothing, and the gate reports `0 regressions; 0 new failures`. If a shot-detection or autocalibration test regresses, stop and investigate that test (compare detected shots in its output) before changing any expectation. The spec forbids loosening expectations without a written reason.

- [ ] **Step 10: Commit**

```bash
git add -u src build.gradle
git add src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java
git commit -m "Move to OpenCV 4 via JavaCPP; capture with V4L2 and MJPG to fix low webcam FPS"
```

---

### Task 8: Video I/O adapter over JavaCV/FFmpeg

**Files:**
- Modify: `build.gradle`
- Create: `camera/video/TimedFrame.java`, `camera/video/BgrImages.java`, `camera/video/VideoWriter.java`, `camera/video/VideoReader.java`
- Create: `src/test/java/com/shootoff/camera/video/TestVideoIO.java`

**Interfaces:**
- Produces (package `com.shootoff.camera.video`):
  - `final class TimedFrame(BufferedImage image, long timestampMicros)` with `BufferedImage getImage()`, `long getTimestampMicros()`, `long getTimestampMs()`
  - `final class BgrImages` with `static BufferedImage toBgr(BufferedImage)` (returns the same instance if it is already a standalone `TYPE_3BYTE_BGR`, otherwise a copy) and `static BufferedImage copy(BufferedImage)` (always a new `TYPE_3BYTE_BGR` image)
  - `final class VideoWriter implements AutoCloseable`: `VideoWriter(File, int width, int height) throws IOException` (odd sizes round up to even), `void write(BufferedImage, long timestampMs) throws IOException` (non-increasing timestamps are bumped to last + 1), `boolean isOpen()`, `void close()`
  - `final class VideoReader implements AutoCloseable`: `VideoReader(File) throws IOException` (`FileNotFoundException` if missing), `long getDurationMs()`, `Optional<TimedFrame> next() throws IOException` (frames are `TYPE_3BYTE_BGR`, never reused), `void rewind() throws IOException`, `void close()`

- [ ] **Step 1: Add JavaCV and FFmpeg**

In `build.gradle`, after the OpenCV lines:

```groovy
    implementation('org.bytedeco:javacv:1.5.14') {
        // Only the OpenCV and FFmpeg presets are used
        ['flycapture', 'libdc1394', 'libfreenect', 'libfreenect2', 'librealsense', 'librealsense2',
         'videoinput', 'artoolkitplus', 'leptonica', 'tesseract'].each { exclude group: 'org.bytedeco', module: it }
    }
    implementation 'org.bytedeco:ffmpeg:8.1.2-1.5.14'
    implementation 'org.bytedeco:ffmpeg:8.1.2-1.5.14:linux-x86_64'
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/com/shootoff/camera/video/TestVideoIO.java`:

```java
package com.shootoff.camera.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestVideoIO {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	private static BufferedImage solid(int width, int height, Color color) {
		final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, width, height);
		g.dispose();
		return image;
	}

	private static List<TimedFrame> readAll(File file) throws IOException {
		final List<TimedFrame> frames = new ArrayList<>();
		try (VideoReader reader = new VideoReader(file)) {
			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent())
				frames.add(frame.get());
		}
		return frames;
	}

	@Test
	public void testRoundTripKeepsFrameCountSizeTimingAndColor() throws IOException {
		final File file = new File(folder.getRoot(), "roundtrip.mp4");

		try (VideoWriter writer = new VideoWriter(file, 640, 480)) {
			for (int i = 0; i < 30; i++)
				writer.write(solid(640, 480, Color.RED), i * 33);
		}

		final List<TimedFrame> frames = readAll(file);
		assertEquals(30, frames.size(), 1);

		long last = -1;
		for (final TimedFrame frame : frames) {
			assertEquals(BufferedImage.TYPE_3BYTE_BGR, frame.getImage().getType());
			assertEquals(640, frame.getImage().getWidth());
			assertEquals(480, frame.getImage().getHeight());
			assertTrue(frame.getTimestampMs() > last);
			last = frame.getTimestampMs();
		}
		assertEquals(29 * 33, last, 34);

		final Color center = new Color(frames.get(0).getImage().getRGB(320, 240));
		assertTrue("red channel " + center.getRed(), center.getRed() > 200);
		assertTrue("blue channel " + center.getBlue(), center.getBlue() < 60);

		try (VideoReader reader = new VideoReader(file)) {
			assertEquals(29 * 33, reader.getDurationMs(), 100);
		}
	}

	@Test
	public void testNonIncreasingTimestampsAreAccepted() throws IOException {
		final File file = new File(folder.getRoot(), "jitter.mp4");

		try (VideoWriter writer = new VideoWriter(file, 64, 48)) {
			for (final long timestamp : new long[] { 0, 0, 10, 5 })
				writer.write(solid(64, 48, Color.GREEN), timestamp);
		}

		final List<TimedFrame> frames = readAll(file);
		assertEquals(4, frames.size());
		for (int i = 1; i < frames.size(); i++)
			assertTrue(frames.get(i).getTimestampMs() > frames.get(i - 1).getTimestampMs());
	}

	@Test
	public void testSubimageWithOddSizeIsRecorded() throws IOException {
		final File file = new File(folder.getRoot(), "cropped.mp4");
		final BufferedImage parent = solid(640, 480, Color.BLACK);
		final Graphics2D g = parent.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(100, 100, 101, 75);
		g.dispose();

		try (VideoWriter writer = new VideoWriter(file, 101, 75)) {
			writer.write(parent.getSubimage(100, 100, 101, 75), 0);
		}

		final List<TimedFrame> frames = readAll(file);
		assertEquals(1, frames.size());
		assertEquals(102, frames.get(0).getImage().getWidth());
		assertEquals(76, frames.get(0).getImage().getHeight());
		// The cropped region is white; recording the parent's origin would be black
		assertTrue(new Color(frames.get(0).getImage().getRGB(50, 37)).getGreen() > 200);
	}

	@Test
	public void testRewindStartsFromTheFirstFrame() throws IOException {
		final File file = new File(folder.getRoot(), "rewind.mp4");
		try (VideoWriter writer = new VideoWriter(file, 64, 48)) {
			for (int i = 0; i < 10; i++)
				writer.write(solid(64, 48, Color.BLUE), i * 33);
		}

		try (VideoReader reader = new VideoReader(file)) {
			while (reader.next().isPresent()) {}
			reader.rewind();
			assertTrue(reader.next().get().getTimestampMs() < 50);
		}
	}

	@Test(expected = FileNotFoundException.class)
	public void testMissingVideoIsReported() throws IOException {
		new VideoReader(new File(folder.getRoot(), "missing.mp4")).close();
	}

	@Test
	public void testToBgrOnlyCopiesWhenNeeded() {
		final BufferedImage bgr = solid(10, 10, Color.RED);
		assertSame(bgr, BgrImages.toBgr(bgr));

		final BufferedImage sub = bgr.getSubimage(2, 2, 4, 4);
		final BufferedImage subCopy = BgrImages.toBgr(sub);
		assertNotSame(sub, subCopy);
		assertEquals(sub.getRGB(1, 1), subCopy.getRGB(1, 1));

		final BufferedImage rgb = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
		rgb.setRGB(3, 4, 0x123456);
		final BufferedImage converted = BgrImages.toBgr(rgb);
		assertEquals(BufferedImage.TYPE_3BYTE_BGR, converted.getType());
		assertEquals(0x123456, converted.getRGB(3, 4) & 0xFFFFFF);
	}
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew test --tests com.shootoff.camera.video.TestVideoIO --console=plain`
Expected: compilation FAILS because the `com.shootoff.camera.video` classes don't exist.

- [ ] **Step 4: Implement the adapter**

`camera/video/TimedFrame.java`:

```java
package com.shootoff.camera.video;

import java.awt.image.BufferedImage;

public final class TimedFrame {
	private final BufferedImage image;
	private final long timestampMicros;

	public TimedFrame(BufferedImage image, long timestampMicros) {
		this.image = image;
		this.timestampMicros = timestampMicros;
	}

	public BufferedImage getImage() {
		return image;
	}

	public long getTimestampMicros() {
		return timestampMicros;
	}

	public long getTimestampMs() {
		return timestampMicros / 1000;
	}
}
```

`camera/video/BgrImages.java`:

```java
package com.shootoff.camera.video;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

public final class BgrImages {
	private BgrImages() {}

	/**
	 * @return image itself if it is a standalone TYPE_3BYTE_BGR image, otherwise
	 *         a TYPE_3BYTE_BGR copy. Subimages are copied because their raster
	 *         shares the parent's pixel buffer.
	 */
	public static BufferedImage toBgr(BufferedImage image) {
		if (image.getType() == BufferedImage.TYPE_3BYTE_BGR && image.getRaster().getParent() == null) return image;

		return copy(image);
	}

	/**
	 * @return a TYPE_3BYTE_BGR copy of image that shares no pixel data with it
	 */
	public static BufferedImage copy(BufferedImage image) {
		final BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(),
				BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = copy.createGraphics();
		try {
			g.drawImage(image, 0, 0, null);
		} finally {
			g.dispose();
		}
		return copy;
	}
}
```

`camera/video/VideoWriter.java`:

```java
package com.shootoff.camera.video;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.FrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes frames with millisecond timestamps to an MPEG-4 video.
 */
public final class VideoWriter implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(VideoWriter.class);

	// Frames arrive at a variable rate with millisecond timestamps, so use a
	// millisecond time base and let each frame's timestamp set its position
	private static final int TIME_BASE = 1000;

	private final File file;
	private final FFmpegFrameRecorder recorder;
	private final Java2DFrameConverter converter = new Java2DFrameConverter();
	private long lastTimestamp = -1;
	private boolean open;

	public VideoWriter(File file, int width, int height) throws IOException {
		this.file = file;

		// The YUV 4:2:0 encoder needs even dimensions; frames are scaled to fit
		recorder = new FFmpegFrameRecorder(file, width + (width & 1), height + (height & 1), 0);
		recorder.setFormat("mp4");
		recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
		recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
		recorder.setFrameRate(TIME_BASE);
		recorder.setGopSize(30);
		recorder.setMaxBFrames(0);
		recorder.setVideoQuality(2);

		try {
			recorder.start();
		} catch (final FrameRecorder.Exception e) {
			throw new IOException("Could not start recording " + file.getPath(), e);
		}

		open = true;
	}

	/**
	 * Timestamps must increase; a timestamp at or before the previous one is
	 * recorded 1 ms after the previous frame instead.
	 */
	public synchronized void write(BufferedImage image, long timestampMs) throws IOException {
		if (!open) throw new IOException("Video writer is closed: " + file.getPath());

		final long timestamp = Math.max(timestampMs, lastTimestamp + 1);

		try {
			recorder.setTimestamp(timestamp * 1000);
			recorder.record(converter.convert(BgrImages.toBgr(image)));
		} catch (final FrameRecorder.Exception e) {
			throw new IOException("Could not record frame to " + file.getPath(), e);
		}

		lastTimestamp = timestamp;
	}

	public synchronized boolean isOpen() {
		return open;
	}

	@Override
	public synchronized void close() {
		if (!open) return;
		open = false;

		try {
			recorder.stop();
			recorder.release();
		} catch (final FrameRecorder.Exception e) {
			logger.error("Error closing video {}", file.getPath(), e);
		}
	}
}
```

`camera/video/VideoReader.java`:

```java
package com.shootoff.camera.video;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the video frames of a file in order as TYPE_3BYTE_BGR images.
 */
public final class VideoReader implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(VideoReader.class);

	private final File file;
	private final FFmpegFrameGrabber grabber;
	private final Java2DFrameConverter converter = new Java2DFrameConverter();

	public VideoReader(File file) throws IOException {
		if (!file.isFile()) throw new FileNotFoundException(file.getPath());

		this.file = file;
		grabber = new FFmpegFrameGrabber(file);
		grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

		try {
			grabber.start();
		} catch (final FrameGrabber.Exception e) {
			throw new IOException("Could not open video " + file.getPath(), e);
		}
	}

	public long getDurationMs() {
		return grabber.getLengthInTime() / 1000;
	}

	/**
	 * @return the next frame, or empty at the end of the video. The image is
	 *         never reused by later calls.
	 */
	public synchronized Optional<TimedFrame> next() throws IOException {
		try {
			final Frame frame = grabber.grabImage();

			if (frame == null) return Optional.empty();

			return Optional.of(new TimedFrame(Java2DFrameConverter.cloneBufferedImage(converter.convert(frame)),
					frame.timestamp));
		} catch (final FrameGrabber.Exception e) {
			throw new IOException("Could not read frame from " + file.getPath(), e);
		}
	}

	public synchronized void rewind() throws IOException {
		try {
			grabber.setTimestamp(0);
		} catch (final FrameGrabber.Exception e) {
			throw new IOException("Could not rewind " + file.getPath(), e);
		}
	}

	@Override
	public synchronized void close() {
		try {
			grabber.stop();
			grabber.release();
		} catch (final FrameGrabber.Exception e) {
			logger.error("Error closing video {}", file.getPath(), e);
		}
	}
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests com.shootoff.camera.video.TestVideoIO --console=plain`
Expected: all 6 PASS. If `testRoundTrip...` is off by one frame, check that `setMaxBFrames(0)` is present before touching the tolerance.

- [ ] **Step 6: Run the gate and commit**

Run: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`. Xuggle and JavaCV's FFmpeg coexist at this point.

```bash
git add build.gradle src/main/java/com/shootoff/camera/video src/test/java/com/shootoff/camera/video
git commit -m "Add a JavaCV/FFmpeg video reader and writer"
```

---

### Task 9: Decode test videos with FFmpeg (MockCamera)

**Files:**
- Rewrite: `src/test/java/com/shootoff/camera/MockCamera.java`
- Modify: `camera/cameratypes/Camera.java` (`bufferedImageToMat`)

**Interfaces:**
- Consumes: `VideoReader`, `TimedFrame`, `BgrImages.toBgr` from Task 8.
- Produces: `MockCamera` with the same public surface minus `processVideo(IMediaListener)` and `onVideoPicture`/`onClose`, which nothing outside the class used.

- [ ] **Step 1: Stop `Camera.bufferedImageToMat` using Xuggle**

In `Camera.java`, replace `import com.xuggle.xuggler.video.ConverterFactory;` with `import com.shootoff.camera.video.BgrImages;`. Replace

```java
		final BufferedImage transformedFrame = ConverterFactory.convertToType(frame, BufferedImage.TYPE_3BYTE_BGR);
```

with

```java
		final BufferedImage transformedFrame = BgrImages.toBgr(frame);
```

- [ ] **Step 2: Rewrite `MockCamera`**

Replace the class from `import` down (keep the `package` line):

```java
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.camera.cameratypes.Camera;
import com.shootoff.camera.cameratypes.CameraEventListener;
import com.shootoff.camera.shotdetection.JavaShotDetector;
import com.shootoff.camera.shotdetection.ShotDetector;
import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;

public class MockCamera implements Camera {
	protected static final Logger logger = LoggerFactory.getLogger(MockCamera.class);

	protected File videoFile;
	protected long lastVideoTimestamp = -1;
	protected static final int SECOND_IN_MICROSECONDS = 1000 * 1000;
	protected Optional<CameraEventListener> cameraEventListener = Optional.empty();

	public MockCamera() {}

	public MockCamera(File videoFile) {
		this.videoFile = videoFile;
	}

	@Override
	public boolean isOpen() {
		return true;
	}

	@Override
	public String getName() {
		return "MockCamera";
	}

	@Override
	public void run() {
		if (videoFile == null) return;

		logger.trace("opening {}", videoFile.getAbsolutePath());

		try (VideoReader reader = new VideoReader(videoFile)) {
			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent())
				onVideoFrame(frame.get());
		} catch (final IOException e) {
			logger.error("Error reading mock camera video {}", videoFile.getAbsolutePath(), e);
		}

		if (cameraEventListener.isPresent()) cameraEventListener.get().cameraClosed();
	}

	private long initialSystemTimeAtVideoStart = -1;
	protected long currentFrameTimestamp = -1;
	private int frameCount = 0;
	public static final int DEFAULT_FPS = 30;
	private double webcamFPS = 0.0;

	private void onVideoFrame(TimedFrame frame) {
		final BufferedImage currentFrame = frame.getImage();

		if (initialSystemTimeAtVideoStart == -1) initialSystemTimeAtVideoStart = System.currentTimeMillis();

		currentFrameTimestamp = frame.getTimestampMs() + initialSystemTimeAtVideoStart;

		if (frameCount == 0 && cameraEventListener.isPresent()) {
			setViewSize(new Dimension(currentFrame.getWidth(), currentFrame.getHeight()));
			cameraEventListener.get().setFeedResolution(currentFrame.getWidth(), currentFrame.getHeight());
		}

		if (lastVideoTimestamp > -1 && (frameCount % 30) == 0) {
			final double estimateFPS = (double) SECOND_IN_MICROSECONDS
					/ (double) (frame.getTimestampMicros() - lastVideoTimestamp);

			setFPS(estimateFPS);
		}
		lastVideoTimestamp = frame.getTimestampMicros();

		if (cameraEventListener.isPresent())
			cameraEventListener.get().newFrame(new Frame(Camera.bufferedImageToMat(currentFrame), currentFrameTimestamp));

		frameCount++;
	}

	protected void setFPS(double newFPS) {
		// This just tells us if it's the first FPS estimate
		if (getFrameCount() > DEFAULT_FPS)
			webcamFPS = ((webcamFPS * 4.0) + newFPS) / 5.0;
		else
			webcamFPS = newFPS;
	}

	@Override
	public Frame getFrame() {
		return null;
	}

	@Override
	public BufferedImage getBufferedImage() {
		return null;
	}

	@Override
	public boolean open() {
		return false;
	}

	@Override
	public void close() {}

	@Override
	public void setCameraEventListener(CameraEventListener cameraEventListener) {
		this.cameraEventListener = Optional.of(cameraEventListener);
	}

	@Override
	public int getFrameCount() {
		return frameCount;
	}

	@Override
	public ShotDetector getPreferredShotDetector(CameraManager cameraManager, CameraView cameraView) {
		if (JavaShotDetector.isSystemSupported())
			return new JavaShotDetector(cameraManager, cameraView);
		else
			return null;
	}

	@Override
	public boolean isLocked() {
		return false;
	}

	private Dimension size = null;

	@Override
	public void setViewSize(Dimension size) {
		this.size = size;
	}

	@Override
	public Dimension getViewSize() {
		return size;
	}

	@Override
	public double getFPS() {
		return webcamFPS;
	}

	@Override
	public boolean setState(CameraState state) {
		return true;
	}

	@Override
	public CameraState getState() {
		return CameraState.DETECTING;
	}

	@Override
	public boolean supportsExposureAdjustment() {
		return false;
	}

	@Override
	public boolean decreaseExposure() {
		return false;
	}

	@Override
	public void resetExposure() {}

	@Override
	public boolean limitsFrames() {
		return false;
	}
}
```

The old code called `cameraEventListener.get().setFeedResolution(...)` outside the `isPresent()` check because of a missing brace. The rewrite guards both calls.

- [ ] **Step 3: Run the gate (this changes how every test video is decoded)**

Run: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`.

If a `TestCameraManager*` test regresses:
1. Rerun just that class twice to rule out flakiness.
2. If it's consistent, dump the first frame of that video from both decoders and compare mean pixel values: modern FFmpeg's YUV→BGR conversion can differ by a few levels from Xuggle's 2012-era build.
3. Report the per-test detected versus expected shots to the owner before changing anything. Don't edit expectations to make the gate pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/shootoff/camera/MockCamera.java src/main/java/com/shootoff/camera/cameratypes/Camera.java
git commit -m "Decode test videos with FFmpeg instead of Xuggle"
```

---

### Task 10: Port recording and playback to FFmpeg; remove Xuggle

**Files:**
- Rewrite: `camera/recorders/RollingRecorder.java`, `camera/recorders/ShotRecorder.java` (keep license headers)
- Modify: `camera/CameraManager.java` (imports, fields at 115-118 and 481-485, `startRecordingStream`, `stopRecordingStream`, `notifyShot`, `startRecordingShots`, `startRecordingCalibratedArea`, frame recording at ~582-593 and ~640-652)
- Modify: `gui/CanvasManager.java` (`createVideoString`)
- Modify: `gui/controller/VideoPlayerController.java`
- Create: `src/test/java/com/shootoff/camera/recorders/TestRollingRecorder.java`
- Modify: `build.gradle`

**Interfaces:**
- Consumes: `VideoWriter`, `VideoReader`, `TimedFrame`, `BgrImages` (Task 8).
- Produces:
  - `RollingRecorder(String extension, String sessionName, String cameraName, int recordWidth, int recordHeight) throws IOException`
  - package-private `RollingRecorder(..., LongSupplier clock)`
  - `void recordFrame(BufferedImage)`, `Optional<ShotRecorder> fork()`, `void close()`
  - `ShotRecorder(File relativeVideoFile, File videoFile, long cutDuration, VideoWriter videoWriter, String cameraName, LongSupplier clock)` with its existing getters, `recordFrame`, `isComplete`, `close`

- [ ] **Step 1: Write the failing recorder tests**

`src/test/java/com/shootoff/camera/recorders/TestRollingRecorder.java`:

```java
package com.shootoff.camera.recorders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;

public class TestRollingRecorder {
	@Rule public TemporaryFolder folder = new TemporaryFolder();

	private static final int WIDTH = 320;
	private static final int HEIGHT = 240;
	private static final long FRAME_MS = 33;

	private final AtomicLong now = new AtomicLong(1_000_000);
	private File sessionFolder;

	@Before
	public void setUp() {
		System.setProperty("shootoff.sessions", folder.getRoot().getAbsolutePath());
		sessionFolder = new File(folder.getRoot(), "test");
		assertTrue(sessionFolder.mkdirs());
	}

	private static BufferedImage frame(int i) {
		final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = image.createGraphics();
		g.setColor(new Color((i * 7) % 256, 64, 128));
		g.fillRect(0, 0, WIDTH, HEIGHT);
		g.dispose();
		return image;
	}

	private RollingRecorder newRecorder() throws IOException {
		return new RollingRecorder(".mp4", "test", "cam", WIDTH, HEIGHT, now::get);
	}

	private void record(RollingRecorder rolling, Optional<ShotRecorder> shot, int frames) {
		for (int i = 0; i < frames; i++) {
			rolling.recordFrame(frame(i));
			if (shot.isPresent()) shot.get().recordFrame(frame(i));
			now.addAndGet(FRAME_MS);
		}
	}

	private static long[] frameTimestamps(File video) throws IOException {
		try (VideoReader reader = new VideoReader(video)) {
			final long[] timestamps = new long[10_000];
			int count = 0;
			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent())
				timestamps[count++] = frame.get().getTimestampMs();
			return Arrays.copyOf(timestamps, count);
		}
	}

	private static void assertIncreasing(long[] timestamps) {
		for (int i = 1; i < timestamps.length; i++)
			assertTrue("timestamps must increase at " + i, timestamps[i] > timestamps[i - 1]);
	}

	@Test
	public void testShotVideoHasRecordLengthBeforeShotPlusFollowingFrames() throws IOException {
		final RollingRecorder rolling = newRecorder();
		record(rolling, Optional.empty(), 200); // 6.6 s

		final Optional<ShotRecorder> shot = rolling.fork();
		assertTrue(shot.isPresent());
		record(rolling, shot, 30);
		shot.get().close();
		rolling.close();

		final long[] timestamps = frameTimestamps(shot.get().getVideoFile());
		assertIncreasing(timestamps);
		assertEquals(ShotRecorder.RECORD_LENGTH / FRAME_MS + 30, timestamps.length, 3);
		assertEquals(ShotRecorder.RECORD_LENGTH + 30 * FRAME_MS, timestamps[timestamps.length - 1], 150);
	}

	@Test
	public void testShotRightAfterStartKeepsAllFrames() throws IOException {
		final RollingRecorder rolling = newRecorder();
		record(rolling, Optional.empty(), 10);

		final Optional<ShotRecorder> shot = rolling.fork();
		record(rolling, shot, 5);
		shot.get().close();
		rolling.close();

		final long[] timestamps = frameTimestamps(shot.get().getVideoFile());
		assertIncreasing(timestamps);
		assertEquals(15, timestamps.length, 1);
	}

	@Test
	public void testRolledRecordingStillProducesShotVideoAndCleansUp() throws IOException {
		final RollingRecorder rolling = newRecorder();
		record(rolling, Optional.empty(), 500); // 16.5 s, rolls once at 15 s

		final Optional<ShotRecorder> shot = rolling.fork();
		record(rolling, shot, 30);
		shot.get().close();
		rolling.close();

		final long[] timestamps = frameTimestamps(shot.get().getVideoFile());
		assertIncreasing(timestamps);
		assertEquals(ShotRecorder.RECORD_LENGTH / FRAME_MS + 30, timestamps.length, 3);

		// Only the shot video remains; rolling files are deleted
		final String[] remaining = sessionFolder.list();
		assertEquals(Arrays.toString(remaining), 1, remaining.length);
		assertEquals(shot.get().getVideoFile().getName(), remaining[0]);
	}
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests com.shootoff.camera.recorders.TestRollingRecorder --console=plain`
Expected: compilation FAILS because no `RollingRecorder` constructor takes `(String, String, String, int, int, LongSupplier)`.

- [ ] **Step 3: Rewrite `ShotRecorder`**

Below the license header:

```java
package com.shootoff.camera.recorders;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.Closeable;
import com.shootoff.camera.video.VideoWriter;

public class ShotRecorder implements Closeable {
	// The number of milliseconds before and after a shot to record
	public static final long RECORD_LENGTH = 5000; // ms

	private static final Logger logger = LoggerFactory.getLogger(ShotRecorder.class);

	private final LongSupplier clock;
	private final long startTime;
	private final long timeOffset;
	private final File relativeVideoFile;
	private final File videoFile;
	private final String cameraName;
	private final VideoWriter videoWriter;

	public ShotRecorder(File relativeVideoFile, File videoFile, long cutDuration, VideoWriter videoWriter,
			String cameraName, LongSupplier clock) {
		this.relativeVideoFile = relativeVideoFile;
		this.videoFile = videoFile;
		this.videoWriter = videoWriter;
		this.cameraName = cameraName;
		this.clock = clock;

		startTime = clock.getAsLong();
		timeOffset = cutDuration;

		logger.debug("Started recording shot video: {}, cut duration = {} ms", videoFile.getName(), cutDuration);
	}

	public void recordFrame(BufferedImage frame) {
		final long timestamp = (clock.getAsLong() - startTime) + timeOffset;

		try {
			videoWriter.write(frame, timestamp);
		} catch (final IOException e) {
			logger.error("Failed to record shot video frame to {}", videoFile.getPath(), e);
		}
	}

	public File getRelativeVideoFile() {
		return relativeVideoFile;
	}

	public File getVideoFile() {
		return videoFile;
	}

	public String getCameraName() {
		return cameraName;
	}

	public boolean isComplete() {
		return clock.getAsLong() - startTime > RECORD_LENGTH;
	}

	@Override
	public void close() {
		videoWriter.close();

		logger.debug("Stopped recording shot video: {}, timeOffset = {}", relativeVideoFile.getPath(), timeOffset);
	}
}
```

- [ ] **Step 4: Rewrite `RollingRecorder`**

Below the license header:

```java
package com.shootoff.camera.recorders;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.Closeable;
import com.shootoff.camera.video.BgrImages;
import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;
import com.shootoff.camera.video.VideoWriter;

public class RollingRecorder implements Closeable {
	private final Logger logger = LoggerFactory.getLogger(RollingRecorder.class);

	private final String extension;
	private final String sessionName;
	private final String cameraName;
	private final int recordWidth;
	private final int recordHeight;
	private final LongSupplier clock;

	private long startTime;
	private long timeOffset = 0;
	private File relativeVideoFile;
	private File videoFile;
	private VideoWriter videoWriter;
	private final Object videoWriterLock = new Object();
	private volatile boolean forking = false;
	private volatile boolean recording = true;

	// Frames that arrive while the video is being forked, stamped with the
	// clock time they arrived at
	private final List<TimedFrame> bufferedFrames = new ArrayList<>();

	public RollingRecorder(String extension, String sessionName, String cameraName, int recordWidth,
			int recordHeight) throws IOException {
		this(extension, sessionName, cameraName, recordWidth, recordHeight, System::currentTimeMillis);
	}

	RollingRecorder(String extension, String sessionName, String cameraName, int recordWidth, int recordHeight,
			LongSupplier clock) throws IOException {
		this.extension = extension;
		this.sessionName = sessionName;
		this.cameraName = cameraName;
		this.recordWidth = recordWidth;
		this.recordHeight = recordHeight;
		this.clock = clock;

		startTime = clock.getAsLong();
		relativeVideoFile = newRelativeVideoFile(true);
		videoFile = toSessionsFile(relativeVideoFile);
		videoWriter = new VideoWriter(videoFile, recordWidth, recordHeight);

		logger.debug("Started recording new rolling video: {}", videoFile.getName());
	}

	private File newRelativeVideoFile(boolean rolling) {
		return new File(sessionName + File.separator + (rolling ? "rolling" : "") + System.nanoTime() + extension);
	}

	private static File toSessionsFile(File relativeVideoFile) {
		return new File(System.getProperty("shootoff.sessions") + File.separator + relativeVideoFile.getPath());
	}

	public void recordFrame(BufferedImage frame) {
		if (!recording) return;

		final long now = clock.getAsLong();

		if (forking) {
			synchronized (bufferedFrames) {
				bufferedFrames.add(new TimedFrame(BgrImages.copy(frame), now * 1000));
			}
			return;
		}

		final long timestamp = (now - startTime) + timeOffset;

		synchronized (videoWriterLock) {
			write(videoWriter, frame, timestamp);
		}

		if (timestamp >= ShotRecorder.RECORD_LENGTH * 3) {
			logger.debug("Rolling video file {}, timestamp = {} ms", relativeVideoFile.getPath(), timestamp);

			try {
				fork(false);
			} catch (final IOException e) {
				logger.error("Failed to roll video file {}; recording stopped", videoFile.getPath(), e);
				recording = false;
			}
		}
	}

	private void write(VideoWriter writer, BufferedImage frame, long timestamp) {
		try {
			writer.write(frame, timestamp);
		} catch (final IOException e) {
			logger.error("Failed to record frame to {}", videoFile.getPath(), e);
		}
	}

	/**
	 * Cut the last RECORD_LENGTH of the current video into a new file.
	 *
	 * @param keepOld
	 *            true when forking for a shot: the cut becomes the shot video
	 *            and rolling continues in a copy of the current video. false
	 *            when rolling: the cut becomes the new rolling video.
	 */
	private ForkContext fork(boolean keepOld) throws IOException {
		forking = true;

		try {
			synchronized (videoWriterLock) {
				videoWriter.close();
			}

			final File forkRelativeVideoFile = newRelativeVideoFile(!keepOld);
			final File forkVideoFile = toSessionsFile(forkRelativeVideoFile);

			final Cut cut;
			try (VideoReader reader = new VideoReader(videoFile)) {
				final long startCutTimestamp = reader.getDurationMs() - ShotRecorder.RECORD_LENGTH;

				logger.debug("Forking video file {} to {}, keepOld = {}, start cutting at = {} ms",
						relativeVideoFile.getPath(), forkRelativeVideoFile.getPath(), keepOld, startCutTimestamp);

				cut = cut(reader, forkVideoFile, startCutTimestamp);
			}

			final ForkContext context = new ForkContext(forkRelativeVideoFile, forkVideoFile, cut.lastTimestamp,
					cut.writer);

			final VideoWriter nextWriter;
			if (keepOld) {
				final File rollingRelativeVideoFile = newRelativeVideoFile(true);
				final File rollingVideoFile = toSessionsFile(rollingRelativeVideoFile);

				final Cut copy;
				try (VideoReader reader = new VideoReader(videoFile)) {
					copy = cut(reader, rollingVideoFile, -1);
				}

				deleteExpiredVideo();
				relativeVideoFile = rollingRelativeVideoFile;
				videoFile = rollingVideoFile;
				timeOffset = copy.lastTimestamp;
				nextWriter = copy.writer;
			} else {
				deleteExpiredVideo();
				relativeVideoFile = forkRelativeVideoFile;
				videoFile = forkVideoFile;
				timeOffset = cut.lastTimestamp;
				nextWriter = cut.writer;
			}

			synchronized (bufferedFrames) {
				// Frames that arrived during the fork continue the new video's timeline
				startTime = bufferedFrames.isEmpty() ? clock.getAsLong() : bufferedFrames.get(0).getTimestampMs();

				for (final TimedFrame f : bufferedFrames)
					write(nextWriter, f.getImage(), (f.getTimestampMs() - startTime) + timeOffset);

				bufferedFrames.clear();
			}

			synchronized (videoWriterLock) {
				videoWriter = nextWriter;
			}

			return context;
		} finally {
			forking = false;
		}
	}

	private void deleteExpiredVideo() {
		if (!videoFile.delete()) logger.warn("Failed to delete expired rolling video file: {}", videoFile.getPath());
	}

	/**
	 * Copy the frames at or after startingTimestamp into a new video whose
	 * timestamps start at 0. A negative startingTimestamp copies every frame
	 * (the source has less than RECORD_LENGTH of footage). The returned writer
	 * is left open.
	 */
	private Cut cut(VideoReader reader, File newVideoFile, long startingTimestamp /* ms */) throws IOException {
		final VideoWriter writer = new VideoWriter(newVideoFile, recordWidth, recordHeight);

		try {
			long firstTimestamp = -1;
			long lastTimestamp = 0;

			Optional<TimedFrame> frame;
			while ((frame = reader.next()).isPresent()) {
				final long timestamp = frame.get().getTimestampMs();

				if (startingTimestamp >= 0 && timestamp < startingTimestamp) continue;

				if (firstTimestamp == -1) firstTimestamp = timestamp;

				lastTimestamp = timestamp - firstTimestamp;
				writer.write(frame.get().getImage(), lastTimestamp);
			}

			return new Cut(writer, lastTimestamp);
		} catch (final IOException e) {
			writer.close();
			throw e;
		}
	}

	public Optional<ShotRecorder> fork() {
		if (!recording) return Optional.empty();

		try {
			final ForkContext context = fork(true);
			return Optional.of(new ShotRecorder(context.relativeVideoFile, context.videoFile, context.lastTimestamp,
					context.videoWriter, cameraName, clock));
		} catch (final IOException e) {
			logger.error("Failed to fork video file {} for a shot; recording stopped", videoFile.getPath(), e);
			recording = false;
			return Optional.empty();
		}
	}

	private static class Cut {
		private final VideoWriter writer;
		private final long lastTimestamp;

		private Cut(VideoWriter writer, long lastTimestamp) {
			this.writer = writer;
			this.lastTimestamp = lastTimestamp;
		}
	}

	private static class ForkContext {
		private final File relativeVideoFile;
		private final File videoFile;
		private final long lastTimestamp;
		private final VideoWriter videoWriter;

		private ForkContext(File relativeVideoFile, File videoFile, long lastTimestamp, VideoWriter videoWriter) {
			this.relativeVideoFile = relativeVideoFile;
			this.videoFile = videoFile;
			this.lastTimestamp = lastTimestamp;
			this.videoWriter = videoWriter;
		}
	}

	@Override
	public void close() {
		recording = false;

		synchronized (videoWriterLock) {
			videoWriter.close();
		}

		if (!videoFile.delete()) {
			logger.warn("Failed to delete expired rolling video file on close: {}", videoFile.getPath());
		}
	}
}
```

- [ ] **Step 5: Run the recorder tests**

Run: `./gradlew test --tests com.shootoff.camera.recorders.TestRollingRecorder --console=plain`
Expected: `compileJava` still FAILS, because `CameraManager` and `VideoPlayerController` use Xuggle. Do Steps 6–8, then rerun: all 3 PASS.

- [ ] **Step 6: Port `CameraManager`**

Delete every `import com.xuggle...` line and add `import java.io.IOException;` (if missing) and `import com.shootoff.camera.video.VideoWriter;`.

Replace the fields

```java
	protected boolean isFirstStreamFrame = true;
	protected IMediaWriter videoWriterStream;
```

with `protected VideoWriter videoWriterStream;`. Delete `private boolean isFirstCalibratedAreaFrame;` and change `private IMediaWriter videoWriterCalibratedArea;` to `private VideoWriter videoWriterCalibratedArea;`.

Replace `startRecordingStream`:

```java
	public void startRecordingStream(File videoFile) {
		if (logger.isDebugEnabled()) logger.debug("Writing Video Feed To: {}", videoFile.getAbsoluteFile());

		try {
			videoWriterStream = new VideoWriter(videoFile, getFeedWidth(), getFeedHeight());
		} catch (final IOException e) {
			logger.error("Could not start recording the video feed to {}", videoFile.getAbsolutePath(), e);
			return;
		}

		recordingStartTime = System.currentTimeMillis();
		recordingStream = true;
	}
```

This writes to `videoFile` itself. The old code wrote to `videoFile.getName()` in the working directory, which ignored the chosen folder.

Replace the notify method:

```java
	public void notifyShot(final Shot shot) {
		if (rollingRecorder != null) rollingRecorder.fork().ifPresent(recorder -> shotRecorders.put(shot, recorder));
	}
```

In `startRecordingShots`, replace

```java
		rollingRecorder = new RollingRecorder(ICodec.ID.CODEC_ID_MPEG4, ".mp4", sessionName, cameraName, this);
		recordingShots = true;
```

with

```java
		try {
			rollingRecorder = new RollingRecorder(".mp4", sessionName, cameraName, getFeedWidth(), getFeedHeight());
			recordingShots = true;
		} catch (final IOException e) {
			logger.error("Could not start recording shots for camera {}", cameraName, e);
			setDetecting(true);
		}
```

Replace `startRecordingCalibratedArea`:

```java
	public void startRecordingCalibratedArea(File videoFile, int width, int height) {
		if (logger.isDebugEnabled()) logger.debug("Writing Video Feed To: {}", videoFile.getAbsoluteFile());

		try {
			videoWriterCalibratedArea = new VideoWriter(videoFile, width, height);
		} catch (final IOException e) {
			logger.error("Could not start recording the calibrated area to {}", videoFile.getAbsolutePath(), e);
			return;
		}

		recordingCalibratedAreaStartTime = System.currentTimeMillis();
		recordingCalibratedArea = true;
	}
```

In `processFrame`, replace the `if (recordingStream) { ... }` block with:

```java
		if (recordingStream) {
			try {
				videoWriterStream.write(currentImage, System.currentTimeMillis() - recordingStartTime);
			} catch (final IOException e) {
				logger.error("Failed to record video feed frame", e);
			}
		}
```

Replace the `if (recordingCalibratedArea) { ... }` block with:

```java
			if (recordingCalibratedArea) {
				try {
					videoWriterCalibratedArea.write(Camera.matToBufferedImage(submatFrameBGR),
							System.currentTimeMillis() - recordingCalibratedAreaStartTime);
				} catch (final IOException e) {
					logger.error("Failed to record calibrated area frame", e);
				}
			}
```

`stopRecordingStream` and `stopRecordingCalibratedArea` already call `close()`, which `VideoWriter` has, so leave them unchanged.

- [ ] **Step 7: Guard against missing shot recorders in `CanvasManager.createVideoString`**

Replace the loop and return

```java
			for (final CameraManager cm : config.getRecordingManagers()) {
				final ShotRecorder r = cm.getRevelantRecorder(shot);

				if (sb.length() > 0) {
```

with

```java
			for (final CameraManager cm : config.getRecordingManagers()) {
				final ShotRecorder r = cm.getRevelantRecorder(shot);

				// No recorder when forking the shot video failed
				if (r == null) continue;

				if (sb.length() > 0) {
```

and change the `return Optional.of(sb.toString());` that follows the loop to

```java
			return sb.length() == 0 ? Optional.empty() : Optional.of(sb.toString());
```

- [ ] **Step 8: Port `VideoPlayerController`**

Replace the Xuggle imports with:

```java
import java.io.IOException;
import java.util.Optional;

import com.shootoff.camera.video.TimedFrame;
import com.shootoff.camera.video.VideoReader;
```

Remove the `java.awt.image.BufferedImage` and `java.util.concurrent.TimeUnit` imports if unused. Replace the whole `PlaybackContext` class with:

```java
	private static class PlaybackContext {
		private final VideoReader reader;
		private final PlaybackListener listener;
		private final long duration;
		private volatile boolean isPlaying = false;
		private final ImageView imageView = new ImageView();
		private long lastTimestamp = 0;

		public PlaybackContext(File videoFile, PlaybackListener listener) throws IOException {
			this.listener = listener;

			reader = new VideoReader(videoFile);
			duration = reader.getDurationMs();
		}

		public long getDuration() {
			return duration;
		}

		public long getTimestamp() {
			return lastTimestamp;
		}

		/**
		 * @return false at the end of the video
		 */
		private boolean showNextFrame(boolean doDelay) {
			final Optional<TimedFrame> frame;
			try {
				frame = reader.next();
			} catch (final IOException e) {
				logger.error("Error while reading video frames", e);
				return false;
			}

			if (!frame.isPresent()) return false;

			final long currentTimestamp = frame.get().getTimestampMs();

			if (doDelay) {
				try {
					Thread.sleep(Math.max(0, currentTimestamp - lastTimestamp));
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}

			lastTimestamp = currentTimestamp;
			final Image image = SwingFXUtils.toFXImage(frame.get().getImage(), null);
			Platform.runLater(() -> {
				imageView.setImage(image);
				listener.frameUpdated(currentTimestamp);
			});

			return true;
		}

		private void playVideo() {
			new Thread(() -> {
				boolean moreFrames = true;
				while (isPlaying && (moreFrames = showNextFrame(true))) {}

				// moreFrames is still true if playback was paused
				if (!moreFrames) {
					isPlaying = false;
					lastTimestamp = getDuration();
					Platform.runLater(() -> listener.frameUpdated(getDuration()));
				}
			}, "PlayVideo").start();
		}

		private void playFromBeginning() {
			lastTimestamp = 0;

			try {
				reader.rewind();
			} catch (final IOException e) {
				logger.error("Error rewinding video", e);
				return;
			}

			playVideo();
		}

		public void nextFrame() {
			showNextFrame(false);
		}

		public void pausePlayback() {
			isPlaying = false;
		}

		public void togglePlayback() {
			isPlaying = !isPlaying;

			if (isPlaying) {
				if (lastTimestamp != getDuration()) {
					playVideo();
				} else {
					playFromBeginning();
				}
			}
		}

		public boolean isPlaying() {
			return isPlaying;
		}

		public ImageView getImageView() {
			return imageView;
		}
	}
```

Replace `createTabs`:

```java
	private void createTabs(Map<String, File> videos) {
		for (final Entry<String, File> video : videos.entrySet()) {
			final PlaybackContext context;
			try {
				context = new PlaybackContext(video.getValue(), this);
			} catch (final IOException e) {
				logger.error("Skipping video {} for camera {}", video.getValue(), video.getKey(), e);
				continue;
			}

			final Tab videoTab = new Tab(video.getKey());
			videoTabPane.getTabs().add(videoTab);
			videoTab.setContent(context.getImageView());
			contexts.put(video.getKey(), context);
		}
	}
```

In `init`, directly after `createTabs(videos);`, add:

```java
		if (contexts.isEmpty()) {
			timeSlider.setDisable(true);
			togglePlaybackButton.setDisable(true);
			return;
		}
```

- [ ] **Step 9: Remove Xuggle and verify**

In `build.gradle`, delete `implementation 'xuggle:xuggle-xuggler:5.4'` and the `maven.dcm4che.org` repository with its comment.

Run: `grep -rn "xuggle" src build.gradle`
Expected: no output.

Run: `./gradlew test --tests com.shootoff.camera.recorders.TestRollingRecorder --console=plain`
Expected: all 3 PASS.

Run the gate: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`.

- [ ] **Step 10: Commit**

```bash
git add -u src build.gradle
git add src/test/java/com/shootoff/camera/recorders/TestRollingRecorder.java
git commit -m "Record and play back session videos with FFmpeg; remove Xuggle"
```

---

### Task 11: Switch to Java 21 and Gradle Kotlin DSL

**Files:**
- Delete: `build.gradle`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/gradle-daemon-jvm.properties`
- Create: `Launcher.java`
- Create: `src/test/java/com/shootoff/plugins/TestTextToSpeechEngine.java`
- Modify: `src/main/resources/version.properties`

**Interfaces:**
- Produces: entry point `com.shootoff.Launcher.main(String[])`; Maven coordinates `com.shootoff:shootoff:5.0.0-SNAPSHOT`; the `libs.*` version catalog used by Task 12.

- [ ] **Step 1: Add a TTS smoke test (on Java 8 first)**

`src/test/java/com/shootoff/plugins/TestTextToSpeechEngine.java`:

```java
package com.shootoff.plugins;

import static org.junit.Assert.assertTrue;

import javax.sound.sampled.AudioInputStream;

import org.junit.Test;

import marytts.LocalMaryInterface;
import marytts.MaryInterface;

public class TestTextToSpeechEngine {
	@Test
	public void testSynthesizesSpeech() throws Exception {
		final MaryInterface mary = new LocalMaryInterface();
		final AudioInputStream audio = mary.generateAudio("Bad shoot!");
		assertTrue(audio.getFrameLength() > 0);
	}
}
```

Run: `./gradlew test --tests com.shootoff.plugins.TestTextToSpeechEngine --console=plain`
Expected: PASS on Java 8.

- [ ] **Step 2: Create the version catalog**

`gradle/libs.versions.toml`:

```toml
[versions]
javafx = "21.0.12"
javacpp = "1.5.14"
opencv = "4.14.0-1.5.14"
ffmpeg = "8.1.2-1.5.14"
openblas = "0.3.34-1.5.14"
marytts = "5.2.1"
webcam-capture = "0.3.12"
logback = "1.5.38"
slf4j = "2.0.20"
junit = "5.14.4"

[libraries]
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
spotbugs-annotations = "com.github.spotbugs:spotbugs-annotations:4.10.4"
bridj = "com.nativelibs4java:bridj:0.7.0"
webcam-capture = { module = "com.github.sarxos:webcam-capture", version.ref = "webcam-capture" }
webcam-capture-driver-ipcam = { module = "com.github.sarxos:webcam-capture-driver-ipcam", version.ref = "webcam-capture" }
commons-cli = "commons-cli:commons-cli:1.11.0"
marytts-runtime = { module = "de.dfki.mary:marytts-runtime", version.ref = "marytts" }
marytts-lang-en = { module = "de.dfki.mary:marytts-lang-en", version.ref = "marytts" }
marytts-voice = { module = "de.dfki.mary:voice-cmu-slt-hsmm", version.ref = "marytts" }
javacv = { module = "org.bytedeco:javacv", version.ref = "javacpp" }
javacpp = { module = "org.bytedeco:javacpp", version.ref = "javacpp" }
opencv = { module = "org.bytedeco:opencv", version.ref = "opencv" }
ffmpeg = { module = "org.bytedeco:ffmpeg", version.ref = "ffmpeg" }
openblas = { module = "org.bytedeco:openblas", version.ref = "openblas" }
oshi-core = "com.github.oshi:oshi-core:6.12.0"
gson = "com.google.code.gson:gson:2.13.2"
jna = "net.java.dev.jna:jna:5.19.1"
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-vintage-engine = { module = "org.junit.vintage:junit-vintage-engine" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
junit4 = "junit:junit:4.13.2"
hamcrest-core = "org.hamcrest:hamcrest-core:1.3"

[bundles]
marytts = ["marytts-runtime", "marytts-lang-en", "marytts-voice"]

[plugins]
javafx = { id = "org.openjfx.javafxplugin", version = "0.1.0" }
```

- [ ] **Step 3: Create the settings and build scripts**

`settings.gradle.kts`:

```kotlin
rootProject.name = "shootoff"
```

`gradle/gradle-daemon-jvm.properties` makes Gradle run its daemon on an installed JDK 21, so the system default can stay Java 8:

```properties
toolchainVersion=21
```

`build.gradle.kts`:

```kotlin
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    alias(libs.plugins.javafx)
}

group = "com.shootoff"
// Keep in sync with src/main/resources/version.properties
version = "5.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls", "javafx.fxml", "javafx.swing")
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
}

configurations.implementation {
    // webcam-capture and MaryTTS pull in slf4j bindings that conflict with logback
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
}

// Native libraries are only bundled for this platform for now
val javacppPlatform = "linux-x86_64"

dependencies {
    compileOnly(libs.spotbugs.annotations)

    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // webcam-capture fetches bridj 0.6.2, which does not play nicely with
    // stackguard in newer JVMs
    implementation(libs.bridj)
    implementation(libs.webcam.capture)
    implementation(libs.webcam.capture.driver.ipcam)
    implementation(libs.jna)

    implementation(libs.commons.cli)
    implementation(libs.bundles.marytts)
    implementation(libs.oshi.core)
    implementation(libs.gson)

    implementation(libs.javacv) {
        // Only the OpenCV and FFmpeg presets are used
        listOf(
            "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense", "librealsense2",
            "videoinput", "artoolkitplus", "leptonica", "tesseract",
        ).forEach { exclude(group = "org.bytedeco", module = it) }
    }
    for (preset in listOf(libs.javacpp, libs.opencv, libs.ffmpeg, libs.openblas)) {
        implementation(preset)
        implementation(variantOf(preset) { classifier(javacppPlatform) })
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.hamcrest.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}
```

The `application`, `maven-publish` and distribution setup are added in Task 12.

- [ ] **Step 4: Add the classpath launcher**

`src/main/java/com/shootoff/Launcher.java` (add the GPL license header copied from `Main.java`):

```java
package com.shootoff;

/**
 * Entry point for running ShootOFF from the classpath. The JVM refuses to
 * launch a main class that extends javafx.application.Application unless
 * JavaFX is on the module path, so this class delegates to Main.
 */
public final class Launcher {
	private Launcher() {}

	public static void main(String[] args) {
		Main.main(args);
	}
}
```

- [ ] **Step 5: Bump the version and remove the Groovy build**

Set `src/main/resources/version.properties` to:

```properties
version=5.0.0-SNAPSHOT
```

```bash
git rm -q build.gradle
```

The `git rm` already stages the deletion.

- [ ] **Step 6: Compile on Java 21 and fix what breaks**

Run: `./gradlew compileJava compileTestJava --console=plain 2>&1 | grep -E "error:|BUILD" | head -40`

Expected: `BUILD SUCCESSFUL`, possibly after fixes. If there are errors, fix each at its source. Likely cases:
- `Class.newInstance()` is deprecated but still compiles, so leave it (Task 13 changes the plugin loader).
- `javax.annotation.*`, `javax.xml.bind.*`: none are used in this codebase (checked). If one appears, stop and report it.
- Any `sun.*` import: none should remain after Task 6.

Deprecation warnings are acceptable. Do not add `-Werror`.

- [ ] **Step 7: Run the full suite on Java 21**

Run: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`, including `TestTextToSpeechEngine`. JUnit 4 tests run through the vintage engine with the same class and method names, so the baseline file still matches. If every test shows as `MISSING`, check `build/test-results/test/*.xml` naming before anything else.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml gradle/gradle-daemon-jvm.properties src/main/java/com/shootoff/Launcher.java src/main/resources/version.properties src/test/java/com/shootoff/plugins/TestTextToSpeechEngine.java
git commit -m "Build on Java 21 with OpenJFX 21, Gradle Kotlin DSL and a version catalog"
```

---

### Task 12: Runnable distribution and local publishing

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: `./gradlew run`, `./gradlew installDist` → `build/install/shootoff/`, `./gradlew publishToMavenLocal` → `~/.m2/repository/com/shootoff/shootoff/5.0.0-SNAPSHOT/`.

- [ ] **Step 1: Add the plugins**

Change the `plugins` block to:

```kotlin
plugins {
    java
    application
    `maven-publish`
    alias(libs.plugins.javafx)
}
```

- [ ] **Step 2: Configure the application, distribution and publication**

Append to `build.gradle.kts`:

```kotlin
application {
    mainClass = "com.shootoff.Launcher"
}

tasks.named<JavaExec>("run") {
    // ShootOFF and its plugins resolve targets/, sounds/, courses/ and
    // exercises/ against the working directory
    workingDir = projectDir
}

distributions {
    main {
        contents {
            from(projectDir) {
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
            from(components["java"])
        }
    }
}
```

- [ ] **Step 3: Verify the distribution**

```bash
./gradlew installDist --console=plain -q
ls build/install/shootoff; ls build/install/shootoff/lib | grep -cE "opencv|ffmpeg|javafx"
grep -n 'cd "$APP_HOME"' build/install/shootoff/bin/shootoff
```

Expected: `bin courses lib LICENSE shootoff.properties sounds targets` (plus the DLLs), a non-zero jar count, and one matching `cd` line placed right before `exec`.

- [ ] **Step 4: Verify it launches from another directory**

```bash
cd "$(mktemp -d)" && timeout 25 /home/bfears/projects/ShootOFF/build/install/shootoff/bin/shootoff -d > /home/bfears/projects/ShootOFF/build/launch.log 2>&1; cd /home/bfears/projects/ShootOFF
grep -iE "exception|error" build/launch.log | head -20
```

Expected: the ShootOFF window opens and is killed by `timeout` after 25 s. The log shows no `Exception` about missing `targets`/`sounds` or JavaFX runtime components. Camera-not-found messages are fine with no webcam attached. If the first-run dialog blocks, that's fine too.

- [ ] **Step 5: Verify `run` and local publishing**

Run: `timeout 25 ./gradlew run --args="-d" --console=plain > build/run.log 2>&1; grep -iE "JavaFX runtime components are missing|ClassNotFound|UnsatisfiedLink" build/run.log`
Expected: no output.

Run: `./gradlew publishToMavenLocal --console=plain -q && ls ~/.m2/repository/com/shootoff/shootoff/5.0.0-SNAPSHOT/`
Expected: `shootoff-5.0.0-SNAPSHOT.jar`, `.pom`, and `.module` files.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts
git commit -m "Add run, installDist with working-directory start scripts, and Maven local publishing"
```

---

### Task 13: Plugin loader cleanup with a real plugin-jar test

**Files:**
- Modify: `plugins/engine/Plugin.java:25-33,52-75,142-146`
- Create: `src/test/java/com/shootoff/plugins/engine/TestPlugin.java`
- Create: `src/test/resources/shootoff.xml` (decoy descriptor)

**Interfaces:**
- Consumes: `new Plugin(Path)`, `Plugin.getExercise() → TrainingExercise`, `Plugin.getType() → PluginType`, `TrainingExercise.getInfo() → ExerciseMetadata`, `ExerciseMetadata.getName()`.

- [ ] **Step 1: Add the decoy descriptor**

`src/test/resources/shootoff.xml`. It sits on ShootOFF's test classpath the way a stray descriptor might sit on its runtime classpath. A plugin must never pick it up.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<shootoffExercise exerciseClass="com.example.decoy.DoesNotExist" />
```

- [ ] **Step 2: Write the test**

`src/test/java/com/shootoff/plugins/engine/TestPlugin.java`:

```java
package com.shootoff.plugins.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.plugins.TrainingExerciseBase;

class TestPlugin {
	private static final String EXERCISE_CLASS = "com.example.testplugin.TestExercise";

	private static final String EXERCISE_SOURCE = String.join("\n",
			"package com.example.testplugin;",
			"import java.util.List;",
			"import java.util.Optional;",
			"import com.shootoff.camera.Shot;",
			"import com.shootoff.plugins.ExerciseMetadata;",
			"import com.shootoff.plugins.TrainingExercise;",
			"import com.shootoff.plugins.TrainingExerciseBase;",
			"import com.shootoff.targets.Hit;",
			"import com.shootoff.targets.Target;",
			"public class TestExercise extends TrainingExerciseBase implements TrainingExercise {",
			"  @Override public void init() {}",
			"  @Override public void targetUpdate(Target target, TargetChange change) {}",
			"  @Override public ExerciseMetadata getInfo() {",
			"    return new ExerciseMetadata(\"Test Exercise\", \"1.0\", \"ShootOFF tests\", \"Loaded from a jar\");",
			"  }",
			"  @Override public void shotListener(Shot shot, Optional<Hit> hit) {}",
			"  @Override public void reset(List<Target> targets) {}",
			"  @Override public void destroy() {}",
			"}");

	private static final String DESCRIPTOR = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
			+ "<shootoffExercise exerciseClass=\"" + EXERCISE_CLASS + "\" />";

	@TempDir Path tempDir;

	private Path buildPluginJar(boolean includeDescriptor) throws IOException {
		final Path source = tempDir.resolve("src/com/example/testplugin/TestExercise.java");
		Files.createDirectories(source.getParent());
		Files.write(source, EXERCISE_SOURCE.getBytes(StandardCharsets.UTF_8));

		final Path classes = tempDir.resolve("classes");
		Files.createDirectories(classes);
		// Compile against ShootOFF's classes the way a plugin author would
		final String classpath = System.getProperty("java.class.path") + File.pathSeparator
				+ new File(TrainingExerciseBase.class.getProtectionDomain().getCodeSource().getLocation().getPath());
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", classes.toString(),
				source.toString()), "test plugin failed to compile");

		final Path jar = tempDir.resolve(includeDescriptor ? "plugin.jar" : "no-descriptor.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			if (includeDescriptor) addEntry(out, "shootoff.xml", DESCRIPTOR.getBytes(StandardCharsets.UTF_8));
			addEntry(out, "com/example/testplugin/TestExercise.class",
					Files.readAllBytes(classes.resolve("com/example/testplugin/TestExercise.class")));
		}
		return jar;
	}

	private static void addEntry(JarOutputStream out, String name, byte[] contents) throws IOException {
		out.putNextEntry(new JarEntry(name));
		out.write(contents);
		out.closeEntry();
	}

	@Test
	void testLoadsExerciseFromJarUsingItsOwnDescriptor() throws Exception {
		final Plugin plugin = new Plugin(buildPluginJar(true));

		assertEquals(EXERCISE_CLASS, plugin.getExercise().getClass().getName());
		assertEquals("Test Exercise", plugin.getExercise().getInfo().getName());
		assertEquals(PluginType.STANDARD, plugin.getType());
	}

	@Test
	void testJarWithoutDescriptorIsRejected() throws Exception {
		final Path jar = buildPluginJar(false);

		assertThrows(IllegalArgumentException.class, () -> new Plugin(jar));
	}
}
```

- [ ] **Step 3: Run it to verify the decoy makes it fail**

Run: `./gradlew test --tests com.shootoff.plugins.engine.TestPlugin --console=plain`
Expected: both tests FAIL. `getResourceAsStream` searches the parent classpath first, so it finds the decoy. That makes the first test throw ("Could not fetch main class") and the second one not throw.

- [ ] **Step 4: Fix the loader**

In `Plugin.java`, remove the imports `java.security.AccessController` and `java.security.PrivilegedAction`, and add `java.net.URLConnection`. Replace the constructor body from `loader = AccessController...` through `saxParser.parse(pluginSettings, handler);` with:

```java
		try {
			loader = new URLClassLoader(new URL[] { jarPath.toUri().toURL() },
					Thread.currentThread().getContextClassLoader());
		} catch (final MalformedURLException e) {
			throw new IllegalArgumentException(
					String.format("The jarPath %s does not represent a valid ShootOFF plugin", jarPath), e);
		}

		// findResource only searches this plugin's jar. getResourceAsStream
		// would search ShootOFF's classpath first and could find another
		// plugin descriptor.
		final URL pluginSettingsUrl = loader.findResource("shootoff.xml");

		if (pluginSettingsUrl == null) {
			throw new IllegalArgumentException(
					String.format("The jarPath %s does not represent a valid ShootOFF plugin", jarPath));
		}

		final SAXParser saxParser = SAXParserFactory.newInstance().newSAXParser();
		final PluginSettingsXMLHandler handler = new PluginSettingsXMLHandler();

		// Don't cache the jar connection, otherwise the jar stays open and
		// can't be replaced or deleted while ShootOFF runs
		final URLConnection connection = pluginSettingsUrl.openConnection();
		connection.setUseCaches(false);
		try (InputStream pluginSettings = connection.getInputStream()) {
			saxParser.parse(pluginSettings, handler);
		}
```

Replace

```java
				try {
					exercise = (TrainingExercise) exerciseClass.newInstance();
				} catch (InstantiationException | IllegalAccessException e) {
```

with

```java
				try {
					exercise = (TrainingExercise) exerciseClass.getDeclaredConstructor().newInstance();
				} catch (final ReflectiveOperationException e) {
```

- [ ] **Step 5: Verify**

Run: `./gradlew test --tests com.shootoff.plugins.engine.TestPlugin --console=plain`
Expected: both PASS.

Run the gate: `./gradlew cleanTest test --continue --console=plain > /dev/null 2>&1; python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt`
Expected: `0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/shootoff/plugins/engine/Plugin.java src/test/java/com/shootoff/plugins/engine/TestPlugin.java src/test/resources/shootoff.xml
git commit -m "Read plugin descriptors from the plugin jar only; drop deprecated reflection and AccessController"
```

---

### Task 14: Update RandomTargetParDrill (separate repository)

**Files (in `~/projects/RandomTargetParDrill`):**
- Delete: `build.gradle`, `settings.gradle`
- Create: `build.gradle.kts`, `settings.gradle.kts`, `gradle/gradle-daemon-jvm.properties`
- Replace: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` (copied from ShootOFF)

**Interfaces:**
- Consumes: `com.shootoff:shootoff:5.0.0-SNAPSHOT` from `mavenLocal` (Task 12). The ShootOFF classes the plugin uses are unchanged by Tasks 1–13. Spec §5.2 lists them.

- [ ] **Step 1: Clone and branch**

```bash
git clone https://github.com/jynxxNerd/RandomTargetParDrill /home/bfears/projects/RandomTargetParDrill
cd /home/bfears/projects/RandomTargetParDrill && git switch -c modernize/java21
```

- [ ] **Step 2: Bring over the Gradle 8.14 wrapper and daemon JVM setting**

```bash
cp /home/bfears/projects/ShootOFF/gradlew /home/bfears/projects/ShootOFF/gradlew.bat .
cp /home/bfears/projects/ShootOFF/gradle/wrapper/gradle-wrapper.jar /home/bfears/projects/ShootOFF/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
cp /home/bfears/projects/ShootOFF/gradle/gradle-daemon-jvm.properties gradle/
git rm -q build.gradle settings.gradle
```

- [ ] **Step 3: Write the Kotlin build**

`settings.gradle.kts`:

```kotlin
rootProject.name = "RandomTargetParDrill"
```

`build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.openjfx.javafxplugin") version "0.1.0"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // ShootOFF itself: run `./gradlew publishToMavenLocal` in the ShootOFF project first
    mavenLocal()
    mavenCentral()
}

javafx {
    version = "21.0.12"
    modules("javafx.controls")
    // ShootOFF provides JavaFX at runtime
    configuration = "compileOnly"
}

dependencies {
    // Provided by ShootOFF at runtime, so none of it goes into the plugin jar
    compileOnly("com.shootoff:shootoff:5.0.0-SNAPSHOT") { isTransitive = false }
    compileOnly("org.slf4j:slf4j-api:2.0.20")
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

- [ ] **Step 4: Build and inspect the jar**

```bash
./gradlew build --console=plain
unzip -l build/libs/RandomTargetParDrill.jar
```

Expected: `BUILD SUCCESSFUL`. The jar lists only `shootoff.xml`, `version.properties`, `targets/ISSF.target`, `sounds/buzzer.wav`, `backgrounds/blackBG.png`, and classes under `com/shootoff/plugins/RandomTargetParDrill*.class` and `com/shootoff/gui/RoundLimitListener.class`. No `org/` or `javafx/` entries.

If compilation fails on a missing third-party type referenced by a ShootOFF signature, remove `{ isTransitive = false }` and rebuild. If it fails on a ShootOFF API change, fix the plugin source to match the current ShootOFF API. The owner allows plugin changes (spec §1).

- [ ] **Step 5: Install and load it in ShootOFF**

```bash
./gradlew installPlugin --console=plain -q && ls ../ShootOFF/exercises
cd /home/bfears/projects/ShootOFF && timeout 40 ./gradlew run --args="-d" --console=plain > build/plugin-run.log 2>&1
grep -iE "plugin|RandomTargetParDrill" build/plugin-run.log | head; grep -iE "Error creating new plugin|ClassNotFound|NoSuchMethod|NoClassDefFound" build/plugin-run.log
```

Expected: `RandomTargetParDrill.jar` is in `exercises/`. The second grep prints nothing. The plugin's appearance in the Training → Projector exercises menu is confirmed by hand in Task 15.

- [ ] **Step 6: Commit (plugin repository)**

```bash
cd /home/bfears/projects/RandomTargetParDrill
git add build.gradle.kts settings.gradle.kts gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties gradle/gradle-daemon-jvm.properties
git commit -m "Build against ShootOFF 5 on Java 21 with Gradle 8.14"
```

---

### Task 15: Hands-on verification with the webcam (owner and agent together)

**Files:** none. If a check fails, stop and use superpowers:systematic-debugging before changing code.

- [ ] **Step 1: Confirm the webcam is visible to the system**

Ask the owner to plug in the webcam. Then run:

```bash
ls /dev/video*; v4l2-ctl --list-devices; v4l2-ctl -d /dev/video0 --list-formats-ext | grep -E "MJPG|YUYV|Size" | head -20
```

Expected: a `/dev/video*` device, and an `MJPG` format listed. If `v4l2-ctl` is missing, ask the owner to run `! sudo apt install v4l-utils`.

- [ ] **Step 2: Measure FPS**

Run: `./gradlew run --args="-d" --console=plain 2>&1 | tee build/webcam-run.log`

Ask the owner to open the stream debugger for the camera and read the FPS in its title bar. Also run `grep "Camera capture negotiated" build/webcam-run.log`.
Expected: the log shows `MJPG` and the title bar shows roughly 25–30 FPS, with no "Webcam FPS is too low" dialog. If it still shows `YUYV` or low FPS, compare against Step 1's format list before changing code (spec §6.3).

- [ ] **Step 3: Owner checklist**

The owner confirms each item:
1. Shots are detected on the webcam feed.
2. Projector arena calibration completes.
3. RandomTargetParDrill appears in the projector exercises, runs a full drill, plays its beep and buzzer, and shows the score summary.
4. With session recording on, a recorded session plays back in the session viewer with video.
5. RandomShoot speaks its announcements.

- [ ] **Step 4: Record the outcome**

Append the measured FPS, the negotiated format and checklist results to the spec under a new `## 10. Verification results` heading. Then commit:

```bash
git add docs/superpowers/specs/2026-09-24-java21-modernization-design.md
git commit -m "Record hands-on verification results"
```
