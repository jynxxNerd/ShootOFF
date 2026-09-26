# Core Split Plan 2: Target Model, Sessions and Courses into Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a UI-neutral target model (definitions, a parser, placed targets in target sets, and a hit tester) into `core`, make the JavaFX app render and hit-test targets from that model, and move sessions and courses into `core` on top of it. File formats stay unchanged, v1 plugins keep working, and the JavaFX app behaves as before.

**Architecture:**
- **Tasks 1–2: the model in `core`** (package `com.shootoff.targets.model`):
  - `TargetDefinitions` parses the unchanged `.target` XML into an immutable `TargetDefinition` of `Region`s.
  - A `TargetSet` per canvas owns `PlacedTarget`s: a position, a scale about the center of the target's visible regions, visibility, per-region visibility, and each image region's current frame as an `AlphaMask`.
  - `HitTester` finds the topmost region under a point. Its geometry reproduces JavaFX's float rounding and edge rules, so it agrees exactly with today's JavaFX hit test.
- **Task 3: parity.** A javafx-app test compares `HitTester` with a verbatim copy of today's JavaFX hit test, over a grid of points on every bundled target at three placements. It records the JavaFX results in `hit-parity.txt`, which keeps guarding the model after the JavaFX hit path is deleted.
- **Tasks 4–6: the JavaFX app on the model.**
  - `TargetIO` builds the v1 region nodes (`EllipseRegion extends Ellipse`, and so on) from the model, and `XMLTargetReader` is deleted.
  - `TargetView` keeps every interaction, but its position, scale and visibility live in the canvas's `TargetSet`. The JavaFX group follows the set's change events.
  - Hits come from `HitTester`, and are converted to v1 `Hit`s with the same region nodes and impact offsets as before.
- **Task 7: sessions into `core`.** `SessionRecorder` takes `TargetRef(TargetSet, TargetId)` and converts to indexes at record time. `ShotEvent` carries the core `Shot` and a marker radius. The XML/JSON writers produce byte-identical output. `SessionCanvasManager` stays in javafx-app and makes the markers.
- **Task 8: courses into `core`.** `Course` becomes data: target files with positions and sizes, a background reference, and a resolution. `CourseIO` reads and writes it with no arena. Applying a course to the arena (`ProjectorArenaPane.setCourse`) stays in javafx-app.
- **Task 9: owner check.** An automated v1 binary-compatibility check, then the owner's hardware check.

**Tech Stack:** Java 21 toolchain (records, sealed interfaces, pattern-matching `switch`), Gradle 8.14 wrapper (Kotlin DSL), OpenJFX 21 (javafx-app only), SAX and `javax.imageio` for parsing, Gson for JSON sessions, JUnit 5 + JUnit 4 (vintage).

**Spec:** `docs/superpowers/specs/2026-09-26-core-split-and-exercise-api-design.md`. This plan is §7 "Plan 2", steps 1–4. Plan 1 (`docs/superpowers/plans/2026-09-26-core-split-plan-1-modules-and-camera.md`) is done. Plan 3 is out of scope.

## Plan-author rulings

Where the spec is silent or disagrees with the code, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

1. **Package.** The model lives in `com.shootoff.targets.model`. The spec's names `Hit`, `EllipseRegion`, `RectangleRegion`, `PolygonRegion` and `ImageRegion` already exist as v1 classes in `com.shootoff.targets`, which must stay. *Cost if wrong:* a mechanical rename before Plan 3 publishes the API.
2. **Image hits use the current frame's alpha, not only the bounds.** Spec §3 says images use their axis-aligned bounds, "matching today's JavaFX behavior". But today's `TargetView.isHit` skips fully transparent pixels, smooth-scaling the image first when the target is resized. Parity (§8) requires that test. The UI therefore gives each image region's current frame to the model as an `AlphaMask`. Without a mask (a UI that doesn't supply frames), the model falls back to the bounds, as the spec describes. *Cost:* every UI, including 1b's Compose host, must push masks when a frame changes.
3. **A region hidden by its `visible` tag is still hit.** The spec says "checks visible regions". But `POI_Offset_Adjustment.target` puts its `poi_adjust` command on five hidden rectangles, and today's hit test ignores region visibility. Hidden regions still don't count toward the target's bounds, as in JavaFX. *Cost:* none known.
4. **A hidden target is not hit.** This follows the spec. Today's `isHit` ignored visibility, so hidden targets took shots: during calibration (`ProjectorArenaPane.setTargetsVisible(false)`), and in the drill between rounds. *Cost if wrong:* a v1 exercise that counts shots on a hidden target now sees a miss. None of the ten built-ins hides targets. The owner check (Task 9) plays the drill.
5. **Float-faithful geometry.** JavaFX computes shape bounds, containment and inverse transforms in `float`. It treats rectangles as half-open (`x < x + width`), ellipses as strict, and polygons by non-zero winding. It scales a group about the center of its visible children's bounds. The model reproduces each step (`FloatGeometry`, `FloatBox`, `PlacedTarget`), so the parity test can demand exact equality. *Cost:* intricate geometry code, pinned by the parity test.
6. **Position and size.** Position is the JavaFX layout translation. Size is the natural bounds times the scale, and the scale pivots about the center of the natural bounds. Session files (`TARGET_MOVED` = `layoutX/Y`) and course files (x/y = layout, width/height = bounds) already store exactly these values. *Cost:* none; it is today's meaning.
7. **Selection decorations aren't part of the target.** Today a selected target's `getDimension()`, bounds and resize zones included the 1-px selection stroke and the resize anchors' overhang. The model measures only the regions, so recorded or saved sizes of a selected target shrink by up to about 1 px × scale. `getRegions()` no longer lists the anchors. `CalibrationManager` keeps reading the JavaFX group's bounds, so calibration is unchanged. *Cost:* sizes in new session or course files differ by up to a pixel from what the old app wrote for a selected target.
8. **Unresizable regions** (`isResizable="false"`) are scaled by exactly 1/target scale about their own center. Today that was exact in `setDimensions` but approximate during drags. They are also hit where they are drawn, whereas today's hit test ignored their own scale. No bundled target uses the tag.
9. **`TargetView` draws the model's scale with an explicit `Scale` transform** whose pivot comes from the model. It no longer uses the group's `scaleX`/`scaleY` properties, so drawing and hit-testing can't drift apart when decorations change the group's bounds. *Cost:* code that reads `getTargetGroup().getScaleX()` now sees 1. That method is not part of the v1 `Target` API, and the drill and built-ins don't call it.
10. **Region tags are read from the model.** Hit-testing (`ignoreHit`) uses the tags as loaded. A later change to a region node's map through v1 `setTags`/`getAllTags().put` doesn't reach the model. Nothing in the built-ins or the drill changes tags at runtime. The target editor edits its own nodes.
11. **Parse errors fail the target.** Malformed XML, a missing or non-numeric attribute, a polygon without points, or a missing or unreadable image makes `TargetDefinitions.load` throw `TargetFormatException`, whose message names the source and line. `TargetIO.loadTarget` then returns `Optional.empty()`, which every caller already handles. Today such a file loaded as an empty target, or a corrupted one: a missing image re-added the previous region. `<tag>` elements outside a region, and unknown elements, stay ignored.
12. **What the parity test compares against.** It uses `LegacyFxHitTest`, a verbatim copy of today's `TargetView.isHit` operating on JavaFX nodes, in the test tree, rather than `TargetView.isHit` itself. It therefore keeps comparing the model with JavaFX geometry after the switch, as well as with the recorded file. The grids are 16×16, 12×12 and 12×12 per placement, which keeps the run near a minute: the legacy path smooth-scales an image on every shot.
13. **`TargetView` and canvases take `TargetIO.TargetComponents`** (definition plus nodes) instead of a bare `Group` and tag map. See Sanctioned breaks.
14. **Session events.** `SessionRecorder` identifies targets by `TargetRef(TargetSet, TargetId)` and converts them to indexes at record time. `ShotEvent` keeps a reference to the core `Shot`, as it kept the `DisplayShot` before, plus the marker radius that used to be read off the JavaFX marker. The JavaFX paint strings the files contain (`0xff0000ff`, `0x008000ff`, `0xffa500ff`) come from a core table, pinned against JavaFX by a test.
15. **Courses are data.** A course's target files are loaded when it is applied (`ProjectorArenaPane.setCourse`), not when it is read. Missing targets are reported once per apply through `Settings.getUserNotifier()` (new getter). Today there was one `Alert` per target, and it could never fire, because a missing target loaded as an empty one. `SteelChallenge.init(Course)`, a test hook, becomes `init(List<Target>)`.
16. **Not fixed here:** the session viewer still can't show `@` (exercise-jar) targets. `SessionRecorder` records their names as before.

Nothing in the spec's Plan 2 steps is infeasible. The one direct conflict, image hits, is ruling 2.

## Global Constraints

- Work on branch `compose-ui` in `/home/bfears/projects/ShootOFF`. Never switch branches and never commit to `master`.
- No `Co-Authored-By` trailer in commit messages. Verify after every commit with `git log -1 --format=%B`.
- Never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- Use `git mv` for every move or rename so history follows. Never delete and re-create a moved file.
- Code style: tabs; match surrounding conventions. Every **new main-source** Java file starts with the GPL header, copied verbatim from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file). New test files follow the repo's test style, which has no header.
- Package names stay `com.shootoff.*`. A package may be split across modules: the app runs on the classpath, not as JPMS modules.
- `core` and `plugin-api` must not depend on OpenJFX. No compiled class in `core` may reference `javafx.*`, which `TestNoJavaFxInCore` enforces. `java.awt.image` and `javax.imageio` are allowed in `core`, which already uses `BufferedImage`.
- Unchanged formats: `.target`, `.course`, the session formats (XML/JSON), and `shootoff.properties`.
- Existing test classes and methods keep their package, class and method names, because the baseline compares tests by `class.method`. A test that moves modules keeps its name.
- No new third-party dependencies. The version catalog stays as it is.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- `javafx-app/src/test/resources/targets/hit-parity.txt` is written only by Task 3 Step 3's recording command. Never hand-edit it. If it ever needs re-recording, `TestHitParity.modelMatchesJavaFxForEveryBundledTarget` must pass in the same run.
- **Test gate** (unchanged from Plan 1):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  It must print `0 regressions; 0 new failures`. A full run takes several minutes, so use a Bash timeout of 600000 ms. The `N/M passing` count starts at **248** (end of Plan 1). Each task states "passing = previous + N", where N is the number of test methods the task adds. A lower count means tests silently stopped running.
- **v1 plugins keep loading and running.** The public API of `com.shootoff.targets.Target`, `TargetRegion` and its four region classes, `Hit`, `TrainingExerciseBase` and `ProjectorTrainingExerciseBase` stays source- and binary-compatible, and so does every member in "The v1 surface" below. Task 9 Step 1 checks the drill's members with `javap`.
- Publishing: verification publishes only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`.

## The v1 surface

`javap -c -p` of the installed `exercises/RandomTargetParDrill.jar`, and of the ten compiled built-in exercises (`BouncingTargets`, `DuelingTree`, `ISSFStandardPistol`, `ParForScore`, `ParRandomShot`, `RandomShoot`, `ShootDontShoot`, `ShootForScore`, `SteelChallenge`, `TimedHolsterDrill`), shows which `targets`/`gui`/`session`/`courses` members they reference.

**RandomTargetParDrill.jar**:
- `targets.Target`: `getDimension()Ljavafx/geometry/Dimension2D;`, `getPosition()Ljavafx/geometry/Point2D;`, `setPosition(DD)V`, `setVisible(Z)V`
- `targets.TargetRegion`: `getTag(Ljava/lang/String;)Ljava/lang/String;`, `tagExists(Ljava/lang/String;)Z`. The drill also casts the hit region to `javafx.scene.Node` and calls `getBoundsInParent()`, so the v1 region classes must stay JavaFX nodes.
- `targets.Hit`: `getHitRegion()`, `getImpactX()I`, `getImpactY()I`, `getShot()`
- `gui.CanvasManager`: `getCanvasGroup()Ljavafx/scene/Group;`, `addShot(Lcom/shootoff/camera/shot/DisplayShot;Z)V`
- `gui.pane.ProjectorArenaPane.getCanvasManager()`; `gui.LocatedImage.<init>(Ljava/io/InputStream;Ljava/lang/String;)V`
- implements `gui.ParListener` and `gui.DelayedStartListener`
- `plugins.ProjectorTrainingExerciseBase`: `<init>()`, `<init>(Ljava/util/List;)`, `addTarget(Ljava/io/File;DD)Ljava/util/Optional;`, `destroy()`, `getArenaWidth()D`, `getArenaHeight()D`
- `plugins.TrainingExerciseBase`: `clearShots()`, `pauseShotDetection(Z)`, `playSound(Ljava/io/File;)V`, `playSound(Ljava/io/InputStream;)V`
- `plugins.TrainingExercise` (the callbacks it implements), `plugins.ExerciseMetadata.<init>(4 × String)`
- camera and util members (Plan 1's domain, untouched here): `Shot`, `ShotColor`, `DisplayShot`, `ArenaShot`, `Configuration.getMarkerRadius`, `NamedThreadFactory`
- no `session.*` and no `courses.*`
- its target is `@targets/ISSF.target`, loaded from the jar through the plugin's class loader

**The ten built-ins**:
- `Target`: `equals`, `getBoundsInParent()Ljavafx/geometry/Bounds;`, `getDimension`, `getPosition`, `getRegions`, `hasRegion`, `setPosition`
- `TargetRegion`: `getAllTags`, `getTag`, `tagExists`
- `Hit`: `getHitRegion`, `getTarget`
- `courses.Course.getTargets()`, used only by `SteelChallenge.init(Course)`, a test hook (ruling 15)
- inherited base-class methods: `addExercisePane`, `addShotTimerColumn`, `addTarget`, `clearShots`, `destroy`, `getArenaHeight`, `getArenaWidth`, `getCurrentTargets`, `getDelayedStartInterval`, `getInstance`, `pauseShotDetection`, `playSound`, `playSounds`, `removeTarget`, `reset`, `setShotTimerColumnText`, `setShotTimerRowColor`, `showTextOnFeed`

**Sanctioned breaks.** None of these is in the v1 list, and neither the drill nor any built-in calls them (the built-ins are adapted in-tree):
1. `com.shootoff.targets.io.XMLTargetReader` and `TargetReader` are deleted. `TargetIO.TargetComponents`'s constructor becomes `(TargetDefinition, Group)` (Task 4).
2. The `TargetView` and `MirroredTarget` constructors take `TargetComponents` instead of `(File, Group, Map…)`. `CanvasManager.addTarget(File, Group, Map, boolean)` becomes `addTarget(TargetComponents, boolean)`, and `MirroredCanvasManager` follows (Task 5).
3. `com.shootoff.session`: `SessionRecorder`'s record methods take `TargetRef` and `Shot` plus a marker radius instead of `Target`/`DisplayShot`, `ShotEvent.getShot()` returns `Shot`, and `EventVisitor.visitShot` changes to match (Task 7). `recordExerciseFeedMessage(String)`, the method `TrainingExerciseBase` and exercises call, is unchanged.
4. `com.shootoff.courses`: `Course` holds `CourseTarget`s, a `CourseBackground` and a `Size`. `CourseIO.saveCourse(Course, File)` and `loadCourse(File)` take no arena. `XMLCourseReader(File)`. `ProjectorArenaPane.setCourse` now returns the `List<Target>` it added. `SteelChallenge.init(Course)` becomes `init(List<Target>)` (Task 8). `ProjectorTrainingExerciseBase.setCourse(File)` keeps its signature.

## Review Focus

1. **A shot on a region's edge, or on a resized image near its transparent pixels.** The model must pick exactly the region JavaFX picked: rectangles include their left and top edges but not their right and bottom ones, and a scaled image is smooth-scaled before its alpha is read. *Tests:* `TestHitTester.rectangleEdgesAreHalfOpenLikeJavaFx`, `scaledImageUsesTheScaledAlpha` (Task 2); `TestHitParity` (Task 3).
2. **A hidden target versus a region hidden by its tag.** Shots on a target hidden with `setVisible(false)` miss it (ruling 4). The POI adjustment target's hidden rectangles still take shots, so POI adjustment keeps working (ruling 3). *Tests:* `TestHitTester.regionHiddenByTagIsStillHit`, `hiddenTargetIsNotHit` (Task 2); `TestCanvasManagerHits.hiddenTargetsTakeNoShots` (Task 6); the existing `TestTargetCommands.testPOIAdjust`, which runs through the model from Task 6.
3. **An animated region after its frame changes (a fallen popper).** A shot where the popper stood before it fell must miss once the last frame shows, and hit again after a reset. *Test:* `TestCanvasManagerHits.imageHitsFollowTheCurrentAnimationFrame` (Task 6).
4. **A target from an exercise jar** (`@` paths: the drill's `@targets/ISSF.target` and images inside a jar). The target and its images must resolve through the plugin's class loader, and must not resolve without it. *Tests:* `TestTargetDefinitions.atPathsResolveThroughTheClassLoader` (Task 1); the existing `TestTargetIO.testXMLSerializationExerciseStream`.
5. **A missing or malformed target or course file.** The app must not crash. It skips or reports the target with a message naming the file and line (ruling 11). *Tests:* `TestTargetDefinitions.malformedXmlReportsFileAndLine`, `missingAttributeIsNamed`, `missingImageIsNamed` (Task 1); `TestTargetIO.testMissingTargetFileLoadsNothing`, `testMalformedTargetLoadsNothing` (Task 4); the existing `TestSessionCanvasManagerReplay.missingTargetFileIsSkipped`; `TestArenaCourse.missingCourseTargetIsSkippedAndReported` (Task 8).

The owner's check (Task 9) covers what no unit test reaches: drawing, dragging on real hardware, the editor's save and reload, animations, and the installed v1 drill jar.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/.../targets/model/{TargetDefinition,DefaultPerception,Region,EllipseRegion,RectangleRegion,PolygonRegion,ImageRegion,RegionCommand}.java` | the immutable model | 1 |
| `core/.../targets/model/{TargetDefinitions,TargetFormatException,ResourceResolver,ImageSizes}.java` | parser and writer, image resolution | 1 |
| `core/.../targets/io/{RegionVisitor,XMLTargetWriter}.java` | moved from javafx-app (already neutral) | 1 |
| `core/.../targets/model/{TargetId,Placement,PlacedTarget,TargetSet,TargetSetListener,AlphaMask,Hit,HitTester,FloatBox,FloatGeometry}.java` | placed targets and hit-testing | 2 |
| `javafx-app/.../gui/targets/FxAlphaMasks.java` | JavaFX `Image` → `AlphaMask` | 3 |
| `javafx-app/src/test/.../targets/{LegacyFxHitTest,TestHitParity}.java`, `javafx-app/src/test/resources/targets/hit-parity.txt` | parity test and recording | 3 |
| `javafx-app/.../targets/io/TargetIO.java` (rewritten); `XMLTargetReader.java`, `TargetReader.java` deleted | nodes built from the model | 4 |
| `javafx-app/.../gui/targets/TargetView.java` (rewritten), `MirroredTarget.java`, `gui/CanvasManager.java`, `gui/MirroredCanvasManager.java`, `gui/CalibrationManager.java`, `gui/SessionCanvasManager.java`, `gui/FxGeometry.java`, `courses/io/XMLCourseReader.java` | TargetView on the model | 5 |
| `TargetView.java`, `CanvasManager.java` | hits from `HitTester` | 6 |
| `core/.../session/**` (moved), `core/.../session/TargetRef.java`, `core/.../session/io/SessionColors.java` | sessions in core | 7 |
| `core/.../courses/**` (moved), `core/.../courses/{CourseTarget,CourseBackground}.java`, `javafx-app/.../gui/pane/{ProjectorArenaPane,ArenaCoursesSlide}.java` | courses in core; applying stays in javafx-app | 8 |

After Task 8, javafx-app keeps `targets/{Target,TargetRegion,…Region,Hit,MockHit,RegionType,CameraViews}`, `targets/animation/**`, `targets/io/TargetIO`, `gui/**` and `plugins/**`. `core` gains `targets/model/**`, `targets/io/{RegionVisitor,XMLTargetWriter}`, `session/**` and `courses/**`.

---
### Task 1: The target model and its parser in `core`

**Files:**
- Create in `core/src/main/java/com/shootoff/targets/model/`:
  - `TargetDefinition.java`, `DefaultPerception.java`
  - `Region.java`, `EllipseRegion.java`, `RectangleRegion.java`, `PolygonRegion.java`, `ImageRegion.java`, `RegionCommand.java`
  - `TargetDefinitions.java`, `TargetFormatException.java`, `ResourceResolver.java`, `ImageSizes.java`
- Move (`git mv`) `javafx-app/src/main/java/com/shootoff/targets/io/RegionVisitor.java` and `XMLTargetWriter.java` → `core/src/main/java/com/shootoff/targets/io/` (unchanged).
- Test: `core/src/test/java/com/shootoff/targets/model/TestTargetDefinitions.java`

**Interfaces:**
- Consumes: `com.shootoff.geom.Point` (core), `com.shootoff.targets.io.XMLTargetWriter` (moved here).
- Produces (all in `com.shootoff.targets.model`):
  - `record TargetDefinition(Optional<File> file, Map<String,String> tags, List<Region> regions)`: immutable. The tags are the `<target>` attributes in file order, and `regions.get(i).index() == i`. Also `withFile(File)`, `fillsCanvas()`, `defaultPerception() → Optional<DefaultPerception>`, and the constants `TAG_FILL_CANVAS`, `TAG_DEFAULT_PERCEIVED_WIDTH`, `TAG_DEFAULT_PERCEIVED_HEIGHT`, `TAG_DEFAULT_DISTANCE`.
  - `record DefaultPerception(int width, int height, int distance)`
  - `sealed interface Region permits EllipseRegion, RectangleRegion, PolygonRegion, ImageRegion`:
    - `int index()`, `Map<String,String> tags()`, `Optional<String> tag(String)`
    - `boolean isVisibleByDefault()`, `boolean isResizable()`, `boolean ignoresHits()`, `List<RegionCommand> commands()`
    - constants `TAG_VISIBLE="visible"`, `TAG_IGNORE_HIT="ignoreHit"`, `TAG_RESIZABLE="isResizable"`, `TAG_OPACITY="opacity"`, `TAG_COMMAND="command"`
  - the regions:
    - `record EllipseRegion(int index, double centerX, double centerY, double radiusX, double radiusY, String fill, Map<String,String> tags)`
    - `record RectangleRegion(int index, double x, double y, double width, double height, String fill, Map<String,String> tags)`
    - `record PolygonRegion(int index, List<Point> points, String fill, Map<String,String> tags)`, with at least one point
    - `record ImageRegion(int index, double x, double y, String imagePath, int imageWidth, int imageHeight, Map<String,String> tags)`. `imagePath` is as written in the file. The size is the one the JavaFX app shows: a GIF's logical screen size, otherwise the first frame's size.
  - `record RegionCommand(String name, List<String> args)`; `static List<RegionCommand> parse(String commandTag)`
  - `final class TargetDefinitions`:
    - `static TargetDefinition load(Path file) throws TargetFormatException`. The result's `file()` is `file.toFile()`, and images resolve through `ResourceResolver.files()`.
    - `static TargetDefinition load(InputStream in, ResourceResolver resolver) throws TargetFormatException`. The result's `file()` is empty.
    - `static void write(TargetDefinition, File)`
  - `class TargetFormatException extends Exception`. Its message is `<source>:<line>: <problem>`, or `<source>: <problem>` when there is no line. The source is the path, or `target stream`.
  - `@FunctionalInterface interface ResourceResolver`:
    - `Optional<InputStream> open(String path) throws IOException`
    - `static ResourceResolver files()`: absolute paths as they are; relative paths against `shootoff.home`, falling back to `user.dir`; `@` paths never found
    - `static ResourceResolver classLoader(ClassLoader loader)`: `@` paths from the loader with the `@` dropped and `\` turned into `/`; other paths as `files()`; `null` means `files()`

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/com/shootoff/targets/model/TestTargetDefinitions.java`:

```java
package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.geom.Point;

class TestTargetDefinitions {
	@TempDir Path temp;

	private static TargetDefinition parse(String xml) throws TargetFormatException {
		return TargetDefinitions.load(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
				ResourceResolver.files());
	}

	@Test
	void everyBundledTargetParses() throws IOException, TargetFormatException {
		final List<Path> files;
		try (Stream<Path> paths = Files.walk(Paths.get("targets"))) {
			files = paths.filter(p -> p.toString().endsWith(".target")).sorted().collect(Collectors.toList());
		}

		assertEquals(26, files.size());
		for (final Path file : files) {
			final TargetDefinition definition = TargetDefinitions.load(file);
			assertFalse(definition.regions().isEmpty(), file + " has no regions");
			assertEquals(Optional.of(file.toFile()), definition.file());
		}
	}

	@Test
	void regionsKeepFileOrderGeometryAndTags() throws TargetFormatException {
		final TargetDefinition definition = parse("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<target>\n"
				+ "\t<image x=\"139.5\" y=\"1.5\" file=\"targets/duel_tree_stand.gif\">\n"
				+ "\t\t<tag name=\"subtarget\" value=\"stand\" />\n"
				+ "\t</image>\n"
				+ "\t<rectangle x=\"1\" y=\"2\" width=\"3\" height=\"4\" fill=\"black\">\n"
				+ "\t\t<tag name=\"visible\" value=\"false\" />\n"
				+ "\t\t<tag name=\"a\" value=\"b\" />\n"
				+ "\t</rectangle>\n"
				+ "\t<ellipse centerX=\"5\" centerY=\"6\" radiusX=\"7\" radiusY=\"8\" fill=\"#ff00ff\" />\n"
				+ "\t<polygon fill=\"red\">\n"
				+ "\t\t<point x=\"0\" y=\"0\" />\n"
				+ "\t\t<point x=\"10\" y=\"0\" />\n"
				+ "\t\t<point x=\"5\" y=\"9\" />\n"
				+ "\t</polygon>\n"
				+ "</target>\n");

		assertEquals(4, definition.regions().size());
		// duel_tree_stand.gif's logical screen is 209x589
		assertEquals(new ImageRegion(0, 139.5, 1.5, "targets/duel_tree_stand.gif", 209, 589,
				Map.of("subtarget", "stand")), definition.regions().get(0));
		final RectangleRegion rectangle = (RectangleRegion) definition.regions().get(1);
		assertEquals(new RectangleRegion(1, 1, 2, 3, 4, "black", Map.of("visible", "false", "a", "b")), rectangle);
		assertEquals(List.of("visible", "a"), List.copyOf(rectangle.tags().keySet()));
		assertFalse(rectangle.isVisibleByDefault());
		assertEquals(new EllipseRegion(2, 5, 6, 7, 8, "#ff00ff", Map.of()), definition.regions().get(2));
		assertEquals(new PolygonRegion(3, List.of(new Point(0, 0), new Point(10, 0), new Point(5, 9)), "red", Map.of()),
				definition.regions().get(3));
		assertEquals(Optional.empty(), definition.file());
	}

	@Test
	void commandsAreParsedFromTheCommandTag() throws TargetFormatException {
		final Region region = parse("<target><ellipse centerX=\"1\" centerY=\"1\" radiusX=\"1\" radiusY=\"1\" fill=\"red\">"
				+ "<tag name=\"command\" value=\"animate(pepper_popper);"
				+ "play_sound(sounds/steel_sound_1.wav,pepper_popper);reverse\" />"
				+ "</ellipse></target>").regions().get(0);

		assertEquals(List.of(new RegionCommand("animate", List.of("pepper_popper")),
				new RegionCommand("play_sound", List.of("sounds/steel_sound_1.wav", "pepper_popper")),
				new RegionCommand("reverse", List.of())), region.commands());
	}

	@Test
	void targetAttributesKeepFillCanvasAndPerception() throws TargetFormatException {
		final TargetDefinition definition = parse("<target fillCanvas=\"true\" defaultPerceivedWidth=\"450\" "
				+ "defaultPerceivedHeight=\"570\" defaultDistance=\"10000\">"
				+ "<rectangle x=\"0\" y=\"0\" width=\"1\" height=\"1\" fill=\"black\" /></target>");

		assertTrue(definition.fillsCanvas());
		assertEquals(Optional.of(new DefaultPerception(450, 570, 10000)), definition.defaultPerception());
		assertEquals(List.of("fillCanvas", "defaultPerceivedWidth", "defaultPerceivedHeight", "defaultDistance"),
				List.copyOf(definition.tags().keySet()));

		final TargetDefinition plain = parse(
				"<target><rectangle x=\"0\" y=\"0\" width=\"1\" height=\"1\" fill=\"black\" /></target>");
		assertFalse(plain.fillsCanvas());
		assertFalse(plain.defaultPerception().isPresent());
	}

	@Test
	void malformedXmlReportsFileAndLine() throws IOException {
		final Path file = temp.resolve("broken.target");
		Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<target>\n\t<ellipse centerX=\"1\"\n</target>\n");

		final TargetFormatException e = assertThrows(TargetFormatException.class, () -> TargetDefinitions.load(file));
		assertTrue(e.getMessage().matches(Pattern.quote(file.toString()) + ":\\d+: .+"), e.getMessage());
	}

	@Test
	void missingAttributeIsNamed() {
		final TargetFormatException e = assertThrows(TargetFormatException.class,
				() -> parse("<target>\n<ellipse centerX=\"1\" centerY=\"2\" radiusX=\"3\" fill=\"red\" />\n</target>"));
		assertEquals("target stream:2: <ellipse> is missing attribute radiusY", e.getMessage());
	}

	@Test
	void missingImageIsNamed() {
		final TargetFormatException e = assertThrows(TargetFormatException.class,
				() -> parse("<target>\n<image x=\"0\" y=\"0\" file=\"targets/no_such_image.png\" />\n</target>"));
		assertEquals("target stream:2: image targets/no_such_image.png not found", e.getMessage());
	}

	@Test
	void atPathsResolveThroughTheClassLoader() throws IOException, TargetFormatException {
		final Path jarRoot = temp.resolve("jar");
		final Path dir = Files.createDirectories(jarRoot.resolve("sub"));
		ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png", dir.resolve("dot.png").toFile());
		Files.writeString(dir.resolve("at.target"), "<target><image x=\"4\" y=\"5\" file=\"@sub/dot.png\" /></target>");

		try (URLClassLoader loader = new URLClassLoader(new URL[] { jarRoot.toUri().toURL() }, null)) {
			final TargetDefinition definition = TargetDefinitions.load(loader.getResourceAsStream("sub/at.target"),
					ResourceResolver.classLoader(loader));
			assertEquals(new ImageRegion(0, 4, 5, "@sub/dot.png", 3, 2, Map.of()), definition.regions().get(0));
		}

		// Without the exercise's class loader the image can't be found
		assertThrows(TargetFormatException.class, () -> TargetDefinitions
				.load(Files.newInputStream(dir.resolve("at.target")), ResourceResolver.files()));
	}

	@Test
	void writtenDefinitionParsesBackEqual() throws TargetFormatException {
		for (final String name : List.of("targets/Duel_Tree.target", "targets/IPSC.target",
				"targets/POI_Offset_Adjustment.target")) {
			final TargetDefinition original = TargetDefinitions.load(Paths.get(name));
			final File copy = temp.resolve("copy.target").toFile();

			TargetDefinitions.write(original, copy);
			final TargetDefinition reread = TargetDefinitions.load(copy.toPath());

			assertEquals(original.tags(), reread.tags(), name);
			assertEquals(original.regions(), reread.regions(), name);
		}
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.targets.model.*' --console=plain`
Expected: FAIL. `compileTestJava` reports `package com.shootoff.targets.model does not exist` (or `cannot find symbol TargetDefinitions`).

- [ ] **Step 3: Move the target writer into core**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p core/src/main/java/com/shootoff/targets/io
git mv javafx-app/src/main/java/com/shootoff/targets/io/RegionVisitor.java core/src/main/java/com/shootoff/targets/io/RegionVisitor.java
git mv javafx-app/src/main/java/com/shootoff/targets/io/XMLTargetWriter.java core/src/main/java/com/shootoff/targets/io/XMLTargetWriter.java
```

Neither file references JavaFX. `javafx-app`'s `TargetIO` keeps using them from the same package.

- [ ] **Step 4: Create the model**

Create each file with the GPL header (Global Constraints) followed by the code below.

`core/src/main/java/com/shootoff/targets/model/TargetFormatException.java`:

```java
package com.shootoff.targets.model;

/**
 * A .target file that can't be read: malformed XML, a missing or non-numeric attribute, a polygon
 * without points, or an image that can't be found or read. The message names the file (or
 * "target stream") and, when known, the line.
 */
public class TargetFormatException extends Exception {
	private static final long serialVersionUID = 1L;

	public TargetFormatException(String message) {
		super(message);
	}

	public TargetFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
```

`core/src/main/java/com/shootoff/targets/model/ResourceResolver.java`:

```java
package com.shootoff.targets.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Opens the files a target refers to (its images). Paths are as written in the .target file:
 * absolute, relative to the ShootOFF home folder, or starting with <tt>@</tt> for a resource in
 * an exercise's jar.
 */
@FunctionalInterface
public interface ResourceResolver {
	/**
	 * @return the resource's contents, or empty if there is no such resource
	 */
	Optional<InputStream> open(String path) throws IOException;

	/**
	 * Resolves absolute paths as they are and relative paths against the <tt>shootoff.home</tt>
	 * system property (the working directory if it isn't set). <tt>@</tt> paths are never found.
	 */
	static ResourceResolver files() {
		return path -> {
			if (path.isEmpty() || path.charAt(0) == '@') return Optional.empty();

			File file = new File(path);
			if (!file.isAbsolute()) {
				file = new File(System.getProperty("shootoff.home", System.getProperty("user.dir")), path);
			}

			return file.isFile() ? Optional.of(new FileInputStream(file)) : Optional.empty();
		};
	}

	/**
	 * Resolves <tt>@</tt> paths as resources of <tt>loader</tt> (the <tt>@</tt> dropped and
	 * backslashes turned into slashes, as the JavaFX app always did), and every other path like
	 * {@link #files()}.
	 *
	 * @param loader
	 *            an exercise's class loader, or <tt>null</tt> for {@link #files()}
	 */
	static ResourceResolver classLoader(ClassLoader loader) {
		final ResourceResolver files = files();
		if (loader == null) return files;

		return path -> {
			if (!path.isEmpty() && path.charAt(0) == '@') {
				return Optional.ofNullable(loader.getResourceAsStream(path.substring(1).replace('\\', '/')));
			}

			return files.open(path);
		};
	}
}
```

`core/src/main/java/com/shootoff/targets/model/RegionCommand.java`:

```java
package com.shootoff.targets.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One command from a region's <tt>command</tt> tag, e.g. <tt>animate(pepper_popper)</tt>. Each UI
 * carries commands out itself (animations, sounds, reset, POI adjustment).
 */
public record RegionCommand(String name, List<String> args) {
	public RegionCommand {
		args = List.copyOf(args);
	}

	/**
	 * Parses a command tag the way the JavaFX app always has: commands are separated by ";",
	 * arguments by ",", and "name()" has one empty argument. A missing ")" ends the arguments at
	 * the end of the command.
	 *
	 * @param commandTag
	 *            the tag's value, or <tt>null</tt> for no commands
	 */
	public static List<RegionCommand> parse(String commandTag) {
		if (commandTag == null) return List.of();

		final List<RegionCommand> commands = new ArrayList<>();
		for (final String command : commandTag.split(";")) {
			final int openParen = command.indexOf('(');

			if (openParen > 0) {
				int closeParen = command.indexOf(')', openParen);
				if (closeParen < 0) closeParen = command.length();

				commands.add(new RegionCommand(command.substring(0, openParen),
						Arrays.asList(command.substring(openParen + 1, closeParen).split(","))));
			} else {
				commands.add(new RegionCommand(command, List.of()));
			}
		}

		return commands;
	}
}
```

`core/src/main/java/com/shootoff/targets/model/Region.java`:

```java
package com.shootoff.targets.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One shape or image of a target, in target coordinates. A target lists its regions in paint
 * order: a later region is drawn over, and hit before, an earlier one.
 */
public sealed interface Region permits EllipseRegion, RectangleRegion, PolygonRegion, ImageRegion {
	String TAG_VISIBLE = "visible";
	String TAG_IGNORE_HIT = "ignoreHit";
	String TAG_RESIZABLE = "isResizable";
	String TAG_OPACITY = "opacity";
	String TAG_COMMAND = "command";

	/**
	 * @return this region's position in its target's region list
	 */
	int index();

	/**
	 * @return the region's tags in file order (unmodifiable)
	 */
	Map<String, String> tags();

	default Optional<String> tag(String name) {
		return Optional.ofNullable(tags().get(name));
	}

	/**
	 * @return <tt>false</tt> only if the region has a <tt>visible</tt> tag that isn't "true"
	 */
	default boolean isVisibleByDefault() {
		return !tags().containsKey(TAG_VISIBLE) || Boolean.parseBoolean(tags().get(TAG_VISIBLE));
	}

	/**
	 * @return <tt>false</tt> only if the region has an <tt>isResizable</tt> tag that isn't "true";
	 *         such a region keeps its size when its target is resized
	 */
	default boolean isResizable() {
		return !tags().containsKey(TAG_RESIZABLE) || Boolean.parseBoolean(tags().get(TAG_RESIZABLE));
	}

	/**
	 * @return <tt>true</tt> if shots pass through this region to the ones below it
	 */
	default boolean ignoresHits() {
		return Boolean.parseBoolean(tags().get(TAG_IGNORE_HIT));
	}

	default List<RegionCommand> commands() {
		return RegionCommand.parse(tags().get(TAG_COMMAND));
	}
}
```

`core/src/main/java/com/shootoff/targets/model/EllipseRegion.java`:

```java
package com.shootoff.targets.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An <tt>&lt;ellipse&gt;</tt> region. <tt>fill</tt> is the color as written in the file (a name
 * such as "black" or a "#rrggbb" code).
 */
public record EllipseRegion(int index, double centerX, double centerY, double radiusX, double radiusY, String fill,
		Map<String, String> tags) implements Region {
	public EllipseRegion {
		Objects.requireNonNull(fill, "fill");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}
}
```

`core/src/main/java/com/shootoff/targets/model/RectangleRegion.java`:

```java
package com.shootoff.targets.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A <tt>&lt;rectangle&gt;</tt> region. <tt>fill</tt> is the color as written in the file.
 */
public record RectangleRegion(int index, double x, double y, double width, double height, String fill,
		Map<String, String> tags) implements Region {
	public RectangleRegion {
		Objects.requireNonNull(fill, "fill");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}
}
```

`core/src/main/java/com/shootoff/targets/model/PolygonRegion.java`:

```java
package com.shootoff.targets.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.shootoff.geom.Point;

/**
 * A <tt>&lt;polygon&gt;</tt> region: its <tt>&lt;point&gt;</tt>s in order, closed back to the
 * first. <tt>fill</tt> is the color as written in the file.
 */
public record PolygonRegion(int index, List<Point> points, String fill, Map<String, String> tags) implements Region {
	public PolygonRegion {
		Objects.requireNonNull(fill, "fill");
		points = List.copyOf(points);
		if (points.isEmpty()) throw new IllegalArgumentException("A polygon needs at least one point");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}
}
```

`core/src/main/java/com/shootoff/targets/model/ImageRegion.java`:

```java
package com.shootoff.targets.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An <tt>&lt;image&gt;</tt> region with its top-left corner at (x, y). <tt>imagePath</tt> is as
 * written in the file (see {@link ResourceResolver}). The size is the one the JavaFX app shows: a
 * GIF's logical screen size, otherwise the first frame's size. The pixels, and an animation's
 * current frame, belong to the UI; it hands the current frame to the hit tester as an alpha mask.
 */
public record ImageRegion(int index, double x, double y, String imagePath, int imageWidth, int imageHeight,
		Map<String, String> tags) implements Region {
	public ImageRegion {
		Objects.requireNonNull(imagePath, "imagePath");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
	}
}
```

`core/src/main/java/com/shootoff/targets/model/DefaultPerception.java`:

```java
package com.shootoff.targets.model;

/**
 * The real-world size (in mm) and distance a target is meant to appear at on a perspective
 * calibrated projector arena.
 */
public record DefaultPerception(int width, int height, int distance) {}
```

`core/src/main/java/com/shootoff/targets/model/TargetDefinition.java`:

```java
package com.shootoff.targets.model;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A target as a .target file describes it: its tags (the <tt>&lt;target&gt;</tt> element's
 * attributes, in file order) and its regions in paint order.
 *
 * @param file
 *            the file the target was loaded from, empty for targets read from a stream or made in
 *            code (e.g. the manual calibration rectangle)
 */
public record TargetDefinition(Optional<File> file, Map<String, String> tags, List<Region> regions) {
	public static final String TAG_FILL_CANVAS = "fillCanvas";
	public static final String TAG_DEFAULT_PERCEIVED_WIDTH = "defaultPerceivedWidth";
	public static final String TAG_DEFAULT_PERCEIVED_HEIGHT = "defaultPerceivedHeight";
	public static final String TAG_DEFAULT_DISTANCE = "defaultDistance";

	public TargetDefinition {
		Objects.requireNonNull(file, "file");
		tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
		regions = List.copyOf(regions);

		for (int i = 0; i < regions.size(); i++) {
			if (regions.get(i).index() != i) {
				throw new IllegalArgumentException("Region " + i + " has index " + regions.get(i).index());
			}
		}
	}

	public TargetDefinition withFile(File file) {
		return new TargetDefinition(Optional.ofNullable(file), tags, regions);
	}

	/**
	 * @return <tt>true</tt> if the target should be stretched over its whole canvas (the POI
	 *         adjustment target)
	 */
	public boolean fillsCanvas() {
		return Boolean.parseBoolean(tags.get(TAG_FILL_CANVAS));
	}

	/**
	 * @return the default perceived width, height and distance, if all three tags are present and
	 *         are integers
	 */
	public Optional<DefaultPerception> defaultPerception() {
		try {
			return Optional.of(new DefaultPerception(Integer.parseInt(tags.get(TAG_DEFAULT_PERCEIVED_WIDTH)),
					Integer.parseInt(tags.get(TAG_DEFAULT_PERCEIVED_HEIGHT)),
					Integer.parseInt(tags.get(TAG_DEFAULT_DISTANCE))));
		} catch (final NumberFormatException e) {
			return Optional.empty();
		}
	}
}
```

`core/src/main/java/com/shootoff/targets/model/ImageSizes.java`:

```java
package com.shootoff.targets.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.NodeList;

/**
 * Reads an image's size as the JavaFX app shows it, without decoding its pixels.
 */
final class ImageSizes {
	private ImageSizes() {}

	/**
	 * @return <tt>true</tt> if the JavaFX app animates the image: the part of the file name after
	 *         its first "." ends with "gif" (the test TargetIO has always used)
	 */
	static boolean isGif(String path) {
		final String name = new File(path).getName();
		return name.substring(name.indexOf('.') + 1).endsWith("gif");
	}

	/**
	 * @return {width, height}: for a GIF its logical screen size (the size of every frame
	 *         GifAnimation makes), otherwise the first image's size
	 */
	static int[] read(InputStream in, boolean gif) throws IOException {
		try (ImageInputStream imageInput = ImageIO.createImageInputStream(in)) {
			if (imageInput == null) throw new IOException("no image reader input");

			final Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
			if (!readers.hasNext()) throw new IOException("not an image format ShootOFF can read");

			final ImageReader reader = readers.next();
			try {
				reader.setInput(imageInput);

				if (gif) {
					final IIOMetadata metadata = reader.getStreamMetadata();
					if (metadata != null) {
						final IIOMetadataNode root = (IIOMetadataNode) metadata
								.getAsTree(metadata.getNativeMetadataFormatName());
						final NodeList descriptors = root.getElementsByTagName("LogicalScreenDescriptor");

						if (descriptors.getLength() > 0) {
							final IIOMetadataNode descriptor = (IIOMetadataNode) descriptors.item(0);
							return new int[] { Integer.parseInt(descriptor.getAttribute("logicalScreenWidth")),
									Integer.parseInt(descriptor.getAttribute("logicalScreenHeight")) };
						}
					}
				}

				return new int[] { reader.getWidth(0), reader.getHeight(0) };
			} finally {
				reader.dispose();
			}
		}
	}
}
```

`core/src/main/java/com/shootoff/targets/model/TargetDefinitions.java`:

```java
package com.shootoff.targets.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.shootoff.geom.Point;
import com.shootoff.targets.io.XMLTargetWriter;

/**
 * Reads and writes the .target format (unchanged since ShootOFF 3): a <tt>&lt;target&gt;</tt>
 * element whose attributes are the target's tags, holding <tt>&lt;ellipse&gt;</tt>,
 * <tt>&lt;rectangle&gt;</tt>, <tt>&lt;polygon&gt;</tt> (with <tt>&lt;point x y/&gt;</tt>
 * children) and <tt>&lt;image&gt;</tt> regions, each with optional
 * <tt>&lt;tag name value/&gt;</tt> children. Unknown elements, and tags outside a region, are
 * ignored as they always were.
 */
public final class TargetDefinitions {
	private TargetDefinitions() {}

	/**
	 * Loads a target file. Images resolve through {@link ResourceResolver#files()}.
	 */
	public static TargetDefinition load(Path file) throws TargetFormatException {
		try (InputStream in = Files.newInputStream(file)) {
			return parse(in, ResourceResolver.files(), file.toString()).withFile(file.toFile());
		} catch (final NoSuchFileException e) {
			throw new TargetFormatException(file + ": no such target file", e);
		} catch (final IOException e) {
			throw new TargetFormatException(file + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Loads a target from a stream, e.g. a resource in an exercise's jar. The definition has no
	 * file; see {@link TargetDefinition#withFile(File)}.
	 */
	public static TargetDefinition load(InputStream in, ResourceResolver resolver) throws TargetFormatException {
		return parse(in, resolver, "target stream");
	}

	/**
	 * Writes a target in the .target format.
	 */
	public static void write(TargetDefinition definition, File targetFile) {
		final XMLTargetWriter writer = new XMLTargetWriter(targetFile);

		for (final Region region : definition.regions()) {
			switch (region) {
			case ImageRegion image -> writer.visitImageRegion(image.x(), image.y(), new File(image.imagePath()),
					image.tags());
			case RectangleRegion rectangle -> writer.visitRectangleRegion(rectangle.x(), rectangle.y(),
					rectangle.width(), rectangle.height(), rectangle.fill(), rectangle.tags());
			case EllipseRegion ellipse -> writer.visitEllipse(ellipse.centerX(), ellipse.centerY(), ellipse.radiusX(),
					ellipse.radiusY(), ellipse.fill(), ellipse.tags());
			case PolygonRegion polygon -> {
				final Double[] points = new Double[polygon.points().size() * 2];
				for (int i = 0; i < polygon.points().size(); i++) {
					points[2 * i] = polygon.points().get(i).getX();
					points[2 * i + 1] = polygon.points().get(i).getY();
				}
				writer.visitPolygonRegion(points, polygon.fill(), polygon.tags());
			}
			}
		}

		writer.visitEnd(definition.tags());
	}

	private static TargetDefinition parse(InputStream in, ResourceResolver resolver, String source)
			throws TargetFormatException {
		final Handler handler = new Handler(resolver);

		try {
			SAXParserFactory.newInstance().newSAXParser().parse(in, handler);
		} catch (final SAXParseException e) {
			throw new TargetFormatException(source + ":" + e.getLineNumber() + ": " + e.getMessage(), e);
		} catch (SAXException | ParserConfigurationException | IOException e) {
			throw new TargetFormatException(source + ": " + e.getMessage(), e);
		}

		if (!handler.sawTarget) throw new TargetFormatException(source + ": no <target> element");

		return new TargetDefinition(Optional.empty(), handler.targetTags, handler.regions);
	}

	private static final class Handler extends DefaultHandler {
		private final ResourceResolver resolver;
		private final Map<String, String> targetTags = new LinkedHashMap<>();
		private final List<Region> regions = new ArrayList<>();
		private Locator locator;
		private boolean sawTarget = false;

		// The region element being read, or null between regions
		private String regionElement;
		private Map<String, String> regionAttributes;
		private Map<String, String> regionTags;
		private List<Point> polygonPoints;

		Handler(ResourceResolver resolver) {
			this.resolver = resolver;
		}

		@Override
		public void setDocumentLocator(Locator locator) {
			this.locator = locator;
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			switch (qName) {
			case "target":
				sawTarget = true;
				for (int i = 0; i < attributes.getLength(); i++) {
					targetTags.put(attributes.getQName(i), attributes.getValue(i));
				}
				break;

			case "ellipse":
			case "rectangle":
			case "polygon":
			case "image":
				if (regionElement != null) throw error("<" + qName + "> inside <" + regionElement + ">");

				regionElement = qName;
				regionAttributes = new HashMap<>();
				for (int i = 0; i < attributes.getLength(); i++) {
					regionAttributes.put(attributes.getQName(i), attributes.getValue(i));
				}
				regionTags = new LinkedHashMap<>();
				polygonPoints = new ArrayList<>();
				break;

			case "point":
				if (!"polygon".equals(regionElement)) throw error("<point> outside a <polygon>");

				polygonPoints.add(new Point(number(attributes.getValue("x"), "point", "x"),
						number(attributes.getValue("y"), "point", "y")));
				break;

			case "tag":
				// Tags outside a region have always been ignored
				if (regionElement == null) break;

				regionTags.put(required(attributes.getValue("name"), "tag", "name"),
						required(attributes.getValue("value"), "tag", "value"));
				break;

			default:
				// Unknown elements have always been ignored
				break;
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			if (regionElement == null || !regionElement.equals(qName)) return;

			regions.add(region(qName, regions.size()));
			regionElement = null;
		}

		private Region region(String element, int index) throws SAXException {
			switch (element) {
			case "ellipse":
				return new EllipseRegion(index, number("centerX"), number("centerY"), number("radiusX"),
						number("radiusY"), attribute("fill"), regionTags);
			case "rectangle":
				return new RectangleRegion(index, number("x"), number("y"), number("width"), number("height"),
						attribute("fill"), regionTags);
			case "polygon":
				if (polygonPoints.isEmpty()) throw error("<polygon> has no <point>");
				return new PolygonRegion(index, polygonPoints, attribute("fill"), regionTags);
			default:
				return image(index);
			}
		}

		private ImageRegion image(int index) throws SAXException {
			final String path = attribute("file");
			final double x = number("x");
			final double y = number("y");

			try {
				final Optional<InputStream> in = resolver.open(path);
				if (in.isEmpty()) throw error("image " + path + " not found");

				try (InputStream imageStream = in.get()) {
					final int[] size = ImageSizes.read(imageStream, ImageSizes.isGif(path));
					return new ImageRegion(index, x, y, path, size[0], size[1], regionTags);
				}
			} catch (final IOException e) {
				throw error("image " + path + " can't be read: " + e.getMessage());
			}
		}

		private String attribute(String name) throws SAXException {
			return required(regionAttributes.get(name), regionElement, name);
		}

		private double number(String name) throws SAXException {
			return number(regionAttributes.get(name), regionElement, name);
		}

		private String required(String value, String element, String name) throws SAXException {
			if (value == null) throw error("<" + element + "> is missing attribute " + name);
			return value;
		}

		private double number(String value, String element, String name) throws SAXException {
			try {
				return Double.parseDouble(required(value, element, name));
			} catch (final NumberFormatException e) {
				throw error("<" + element + "> attribute " + name + " is not a number: " + value);
			}
		}

		private SAXParseException error(String message) {
			return new SAXParseException(message, locator);
		}
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.targets.model.*' --tests 'com.shootoff.TestNoJavaFxInCore' --console=plain`
Expected: `BUILD SUCCESSFUL`. All 9 `TestTargetDefinitions` tests pass, and `TestNoJavaFxInCore` still passes.

If `malformedXmlReportsFileAndLine` fails because the parser reports the error as a plain `SAXException`, without a line, the message has no `:<line>:`. In that case, change only the `catch (SAXParseException e)` branch so that it also runs for a `SAXException` whose cause is a `SAXParseException`. Don't loosen the test.

- [ ] **Step 6: Check that javafx-app still builds**

Run: `./gradlew :javafx-app:compileJava :javafx-app:compileTestJava --console=plain`
Expected: `BUILD SUCCESSFUL`. `TargetIO` finds `XMLTargetWriter` and `RegionVisitor` in `core`.

- [ ] **Step 7: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 9 (**257**).

```bash
git add core/src/main/java/com/shootoff/targets/model core/src/test/java/com/shootoff/targets/model
git status --short | command grep -v '^R  '
git commit -m "Add a UI-neutral target model and .target parser to core"
git log -1 --format=%B
```

The two `git mv`s are already staged. The filtered status must show only ` M shootoff.properties`.

---
### Task 2: Placed targets, target sets and the hit tester in `core`

The geometry here follows JavaFX's own code (OpenJFX 21: `Shape.computeShapeContains`, `Ellipse2D.contains`, `RoundRectangle2D.contains`, `Path2D.pointCrossings`, `Shape.computeBounds`, `Parent.doComputeGeomBounds`, `Node.updateLocalToParentTransform`, `AffineBase.inverseTransform`). Each rounding step is deliberate (ruling 5). Task 3 proves the result against JavaFX.

**Files:**
- Create in `core/src/main/java/com/shootoff/targets/model/`:
  - `TargetId.java`, `Placement.java`, `Hit.java`
  - `AlphaMask.java`
  - `FloatBox.java`, `FloatGeometry.java` (package-private)
  - `PlacedTarget.java`, `TargetSetListener.java`, `TargetSet.java`
  - `HitTester.java`
- Test: `core/src/test/java/com/shootoff/targets/model/TestTargetSet.java`, `TestHitTester.java`

**Interfaces:**
- Consumes: `TargetDefinition`, `Region` and the four region records (Task 1); `com.shootoff.geom.{Point, Rect, Size}`.
- Produces (package `com.shootoff.targets.model`):
  - `record TargetId(long value)`
  - `record Placement(double x, double y, double scaleX, double scaleY, boolean visible)`: `ORIGIN` = (0, 0, 1, 1, visible); `withPosition(x, y)`, `withScale(sx, sy)`, `withVisible(v)`
  - `record Hit(TargetId targetId, Region region, Point impact)`, where `impact` is in target coordinates
  - `final class AlphaMask`: `static of(BufferedImage)`, `getWidth()`, `getHeight()`, `isOpaque(int x, int y)` (true unless fully transparent), `scaledTo(int w, int h)` (java.awt `SCALE_SMOOTH` into `TYPE_INT_ARGB`, the last size cached)
  - `final class PlacedTarget` (mutated only by its `TargetSet`):
    - identity and state: `getId()`, `getDefinition()`, `getPlacement()`, `getPosition() → Point`, `getScaleX()`, `getScaleY()`, `isVisible()`, `isRegionVisible(int)`, `getImageMask(int) → Optional<AlphaMask>`
    - geometry: `getSize() → Size`, `getLocalBounds() → Rect` (JavaFX `layoutBounds`), `getPivot() → Point`, `getBounds() → Rect` (JavaFX `boundsInParent`), `boundsAt(Placement) → Rect`
    - conversions: `parentToLocal(x, y) → Point`, `localToParent(x, y) → Point`, `regionBounds(Region) → Rect` (the region's bounds on the canvas)
  - `interface TargetSetListener`: default no-op methods `targetAdded(PlacedTarget)`, `targetRemoved(PlacedTarget)`, `targetChanged(PlacedTarget)`
  - `final class TargetSet` (thread-safe; listeners are called after the change, on the calling thread):
    - adding and finding: `add(TargetDefinition) → PlacedTarget` (at `Placement.ORIGIN`, on top), `add(TargetDefinition, Placement) → PlacedTarget`, `remove(TargetId)`, `getTargets() → List<PlacedTarget>` (bottom to top), `get(TargetId) → Optional<PlacedTarget>`, `indexOf(TargetId) → int` (-1 if absent), `size()`
    - changing placement: `place(TargetId, Placement)`, `move(TargetId, x, y)`, `scale(TargetId, sx, sy)`, `resize(TargetId, width, height)` (the JavaFX app's `setDimensions` semantics: an axis changes only if it differs by more than 0.001), `setVisible(TargetId, boolean)`
    - region state: `setRegionVisible(TargetId, int, boolean)`, `setImageMask(TargetId, int, AlphaMask)` (listeners aren't told about masks)
    - `addListener`/`removeListener(TargetSetListener)`
    - Updates to an id that isn't in the set are ignored.
  - `final class HitTester`:
    - `static Optional<Hit> hit(TargetSet, double x, double y)`: visible targets only, topmost first
    - `static Optional<Hit> hit(PlacedTarget, double x, double y)`: ignores the target's own visibility, like the v1 `Target.isHit`

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/com/shootoff/targets/model/TestTargetSet.java`:

```java
package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

class TestTargetSet {
	private static TargetDefinition rectangles(RectangleRegion... regions) {
		return new TargetDefinition(Optional.empty(), Map.of(), List.of(regions));
	}

	private static RectangleRegion rect(int index, double x, double y, double width, double height,
			Map<String, String> tags) {
		return new RectangleRegion(index, x, y, width, height, "black", tags);
	}

	@Test
	void addAppendsOnTopWithUniqueIds() {
		final TargetSet set = new TargetSet();
		final PlacedTarget a = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		final PlacedTarget b = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		final PlacedTarget c = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())), new Placement(5, 6, 2, 3, false));

		assertEquals(List.of(a, b, c), set.getTargets());
		assertNotEquals(a.getId(), b.getId());
		assertEquals(2, set.indexOf(c.getId()));
		assertEquals(new Placement(5, 6, 2, 3, false), c.getPlacement());
		assertEquals(Placement.ORIGIN, a.getPlacement());

		set.remove(b.getId());
		assertEquals(1, set.indexOf(c.getId()));
		assertEquals(-1, set.indexOf(b.getId()));
		assertEquals(Optional.empty(), set.get(b.getId()));
		assertEquals(2, set.size());
	}

	@Test
	void listenersHearAddsChangesAndRemoves() {
		final TargetSet set = new TargetSet();
		final List<String> heard = new ArrayList<>();
		set.addListener(new TargetSetListener() {
			@Override
			public void targetAdded(PlacedTarget target) {
				heard.add("added");
			}

			@Override
			public void targetChanged(PlacedTarget target) {
				heard.add("changed " + target.getPosition() + " " + target.isVisible());
			}

			@Override
			public void targetRemoved(PlacedTarget target) {
				heard.add("removed");
			}
		});

		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		set.move(target.getId(), 3, 4);
		set.setVisible(target.getId(), false);
		set.remove(target.getId());

		assertEquals(List.of("added", "changed " + new Point(3, 4) + " true", "changed " + new Point(3, 4) + " false",
				"removed"), heard);
	}

	@Test
	void boundsScaleAboutTheCenterLikeJavaFx() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 100, 50, Map.of())));

		set.place(target.getId(), new Placement(10, 20, 2, 3, true));

		// Pivot (50, 25): x = 10 + 50 - 50 * 2 = -40, y = 20 + 25 - 25 * 3 = -30
		assertEquals(new Rect(-40, -30, 200, 150), target.getBounds());
		assertEquals(new Size(200, 150), target.getSize());
		assertEquals(new Point(10, 20), target.getPosition());
		assertEquals(new Rect(0, 0, 100, 50), target.getLocalBounds());
		assertEquals(new Point(50, 25), target.getPivot());
	}

	@Test
	void resizeFollowsSetDimensions() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 100, 50, Map.of())));

		// Within 0.001 of the current width: unchanged; the height doubles
		set.resize(target.getId(), 100.0005, 100);
		assertEquals(1, target.getScaleX());
		assertEquals(2, target.getScaleY(), 1e-12);

		set.resize(target.getId(), 250, 100);
		assertEquals(2.5, target.getScaleX(), 1e-12);
		assertEquals(250, target.getSize().getWidth(), 1e-9);
	}

	@Test
	void updatesToRemovedTargetsAreIgnored() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of())));
		final List<String> heard = new ArrayList<>();
		set.remove(target.getId());
		set.addListener(new TargetSetListener() {
			@Override
			public void targetChanged(PlacedTarget changed) {
				heard.add("changed");
			}
		});

		set.move(target.getId(), 1, 2);
		set.resize(target.getId(), 30, 40);
		set.place(target.getId(), Placement.ORIGIN);
		set.remove(target.getId());

		assertTrue(heard.isEmpty());
		assertEquals(Placement.ORIGIN, target.getPlacement());
	}

	@Test
	void hiddenRegionsDontCountTowardTheBounds() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(rectangles(rect(0, 0, 0, 10, 10, Map.of()),
				rect(1, 0, 0, 100, 100, Map.of(Region.TAG_VISIBLE, "false"))));

		assertFalse(target.isRegionVisible(1));
		assertEquals(new Rect(0, 0, 10, 10), target.getLocalBounds());

		set.setRegionVisible(target.getId(), 1, true);
		assertEquals(new Rect(0, 0, 100, 100), target.getLocalBounds());
	}
}
```

Create `core/src/test/java/com/shootoff/targets/model/TestHitTester.java`:

```java
package com.shootoff.targets.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.geom.Point;

class TestHitTester {
	private static TargetDefinition definition(Region... regions) {
		return new TargetDefinition(Optional.empty(), Map.of(), List.of(regions));
	}

	private static RectangleRegion rect(int index, double x, double y, double width, double height,
			Map<String, String> tags) {
		return new RectangleRegion(index, x, y, width, height, "black", tags);
	}

	private static int regionAt(PlacedTarget target, double x, double y) {
		return HitTester.hit(target, x, y).map(hit -> hit.region().index()).orElse(-1);
	}

	// A 4x4 image whose left two columns are opaque
	private static AlphaMask leftHalfOpaque() {
		final BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 2; x++) {
				image.setRGB(x, y, 0xff000000);
			}
		}
		return AlphaMask.of(image);
	}

	@Test
	void topmostTargetWins() {
		final TargetSet set = new TargetSet();
		final PlacedTarget bottom = set.add(definition(rect(0, 0, 0, 100, 100, Map.of())));
		final PlacedTarget top = set.add(definition(rect(0, 50, 50, 100, 100, Map.of())));

		assertEquals(top.getId(), HitTester.hit(set, 75, 75).get().targetId());
		assertEquals(bottom.getId(), HitTester.hit(set, 25, 25).get().targetId());
		assertFalse(HitTester.hit(set, 175, 25).isPresent());
	}

	@Test
	void topmostRegionWins() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of()),
				new EllipseRegion(1, 50, 50, 10, 10, "red", Map.of())));

		assertEquals(1, regionAt(target, 50, 50));
		assertEquals(0, regionAt(target, 5, 5));
	}

	@Test
	void rectangleEdgesAreHalfOpenLikeJavaFx() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 10, 20, 30, 40, Map.of())));

		assertEquals(0, regionAt(target, 10, 20));
		assertEquals(0, regionAt(target, 39.99, 59.99));
		assertEquals(-1, regionAt(target, 40, 30));
		assertEquals(-1, regionAt(target, 20, 60));
	}

	@Test
	void regionHiddenByTagIsStillHit() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of()),
				rect(1, 40, 40, 20, 20, Map.of(Region.TAG_VISIBLE, "false"))));

		assertFalse(target.isRegionVisible(1));
		assertEquals(1, regionAt(target, 50, 50));
	}

	@Test
	void hiddenTargetIsNotHit() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of())));

		set.setVisible(target.getId(), false);

		assertFalse(HitTester.hit(set, 50, 50).isPresent());
		// The single-target test ignores visibility, like the v1 Target.isHit
		assertTrue(HitTester.hit(target, 50, 50).isPresent());
	}

	@Test
	void ignoreHitRegionsAreSkipped() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of()),
				rect(1, 40, 40, 20, 20, Map.of(Region.TAG_IGNORE_HIT, "true"))));

		assertEquals(0, regionAt(target, 50, 50));
	}

	@Test
	void imageUsesItsBoundsWithoutAMaskAndItsAlphaWithOne() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(new ImageRegion(0, 0, 0, "unused.png", 4, 4, Map.of())));

		assertEquals(0, regionAt(target, 3.5, 1));

		set.setImageMask(target.getId(), 0, leftHalfOpaque());
		assertEquals(0, regionAt(target, 1.5, 1));
		assertEquals(-1, regionAt(target, 3.5, 1));
	}

	@Test
	void scaledImageUsesTheScaledAlpha() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(new ImageRegion(0, 0, 0, "unused.png", 4, 4, Map.of())));
		set.setImageMask(target.getId(), 0, leftHalfOpaque());

		// Scaled 2x about its center (2, 2), the image covers -2..6: columns 0-3 are opaque
		set.scale(target.getId(), 2, 2);

		assertEquals(0, regionAt(target, -0.5, 1));
		assertEquals(-1, regionAt(target, 4.5, 1));
	}

	@Test
	void impactIsInTargetCoordinates() {
		final TargetSet set = new TargetSet();
		final PlacedTarget target = set.add(definition(rect(0, 0, 0, 100, 100, Map.of())));

		set.move(target.getId(), 10, 20);
		assertEquals(new Point(5, 10), HitTester.hit(target, 15, 30).get().impact());

		// Scaled 2x about (50, 50) at position (0, 0), the canvas point (50, 50) is still the center
		set.place(target.getId(), new Placement(0, 0, 2, 2, true));
		assertEquals(new Point(50, 50), HitTester.hit(target, 50, 50).get().impact());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test --tests 'com.shootoff.targets.model.TestTargetSet' --tests 'com.shootoff.targets.model.TestHitTester' --console=plain`
Expected: FAIL. `compileTestJava` reports `cannot find symbol` for `TargetSet`, `PlacedTarget` and `HitTester`.

- [ ] **Step 3: Implement**

Create each file with the GPL header followed by the code below.

`core/src/main/java/com/shootoff/targets/model/TargetId.java`:

```java
package com.shootoff.targets.model;

/**
 * Identifies a placed target for as long as it stays in its {@link TargetSet}.
 */
public record TargetId(long value) {}
```

`core/src/main/java/com/shootoff/targets/model/Placement.java`:

```java
package com.shootoff.targets.model;

/**
 * Where a target is drawn: its position (the translation of its target coordinates, JavaFX's
 * layoutX/layoutY), its scale about the center of its visible regions' bounds, and whether it is
 * shown.
 */
public record Placement(double x, double y, double scaleX, double scaleY, boolean visible) {
	public static final Placement ORIGIN = new Placement(0, 0, 1, 1, true);

	public Placement withPosition(double x, double y) {
		return new Placement(x, y, scaleX, scaleY, visible);
	}

	public Placement withScale(double scaleX, double scaleY) {
		return new Placement(x, y, scaleX, scaleY, visible);
	}

	public Placement withVisible(boolean visible) {
		return new Placement(x, y, scaleX, scaleY, visible);
	}
}
```

`core/src/main/java/com/shootoff/targets/model/Hit.java`:

```java
package com.shootoff.targets.model;

import com.shootoff.geom.Point;

/**
 * A shot that landed on a region of a placed target.
 *
 * @param impact
 *            where the shot landed, in the target's own coordinates
 */
public record Hit(TargetId targetId, Region region, Point impact) {}
```

`core/src/main/java/com/shootoff/targets/model/AlphaMask.java`:

```java
package com.shootoff.targets.model;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * The transparency of an image region's current frame, which the UI gives the hit tester (see
 * {@link TargetSet#setImageMask}). A shot on a fully transparent pixel of an image misses it.
 */
public final class AlphaMask {
	private final BufferedImage image;
	private volatile AlphaMask lastScaled;

	private AlphaMask(BufferedImage image) {
		this.image = image;
	}

	public static AlphaMask of(BufferedImage image) {
		return new AlphaMask(Objects.requireNonNull(image, "image"));
	}

	public int getWidth() {
		return image.getWidth();
	}

	public int getHeight() {
		return image.getHeight();
	}

	/**
	 * @return <tt>true</tt> unless the pixel is fully transparent
	 */
	public boolean isOpaque(int x, int y) {
		return (image.getRGB(x, y) >> 24) != 0;
	}

	/**
	 * @return the mask scaled to the given size exactly as the JavaFX app always scaled a resized
	 *         target's image before testing a hit: java.awt smooth scaling, drawn into an ARGB
	 *         image. The most recent size is cached.
	 */
	public AlphaMask scaledTo(int width, int height) {
		final AlphaMask cached = lastScaled;
		if (cached != null && cached.getWidth() == width && cached.getHeight() == height) return cached;

		final java.awt.Image scaled = image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH);
		final BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g2d = resized.createGraphics();
		g2d.drawImage(scaled, 0, 0, null);
		g2d.dispose();

		final AlphaMask result = new AlphaMask(resized);
		lastScaled = result;
		return result;
	}
}
```

`core/src/main/java/com/shootoff/targets/model/FloatBox.java`:

```java
package com.shootoff.targets.model;

import com.shootoff.geom.Rect;

/**
 * A bounding box stored the way JavaFX stores one (RectBounds): float edges, empty when a maximum
 * is below its minimum.
 */
record FloatBox(float minX, float minY, float maxX, float maxY) {
	static final FloatBox EMPTY = new FloatBox(0, 0, -1, -1);

	boolean isEmpty() {
		return maxX < minX || maxY < minY;
	}

	FloatBox union(FloatBox other) {
		if (other.isEmpty()) return this;
		if (isEmpty()) return other;

		return new FloatBox(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.max(maxX, other.maxX),
				Math.max(maxY, other.maxY));
	}

	/**
	 * @return the box as JavaFX turns RectBounds into a BoundingBox: the width and height are the
	 *         float differences of the edges
	 */
	Rect toRect() {
		return new Rect(minX, minY, maxX - minX, maxY - minY);
	}

	/**
	 * @return the box through <tt>x' = x * scaleX + tx</tt> (and likewise for y), rounded to
	 *         float as JavaFX's bounds transforms do
	 */
	FloatBox transform(double scaleX, double scaleY, double tx, double ty) {
		if (isEmpty()) return this;

		final float x1 = (float) (minX * scaleX + tx);
		final float x2 = (float) (maxX * scaleX + tx);
		final float y1 = (float) (minY * scaleY + ty);
		final float y2 = (float) (maxY * scaleY + ty);

		return new FloatBox(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2));
	}
}
```

`core/src/main/java/com/shootoff/targets/model/FloatGeometry.java`:

```java
package com.shootoff.targets.model;

import java.util.List;

import com.shootoff.geom.Point;

/**
 * Region bounds and containment computed with JavaFX's float rounding and edge rules, so that the
 * hit tester agrees with the JavaFX app's hit test to the pixel (TestHitParity). Each method
 * names the OpenJFX code it follows.
 */
final class FloatGeometry {
	private FloatGeometry() {}

	/**
	 * @return the region's bounds in target coordinates, as the region node's boundsInParent
	 *         (Ellipse/Rectangle: Shape.computeBounds; Polygon: Path2D bounds of its float points;
	 *         ImageView at layoutX/Y: Translate2D.transform of (0, 0, width, height))
	 */
	static FloatBox box(Region region) {
		return switch (region) {
		case EllipseRegion e -> {
			final double x = e.centerX() - e.radiusX();
			final double y = e.centerY() - e.radiusY();
			final double width = 2.0 * e.radiusX();
			final double height = 2.0 * e.radiusY();

			yield width < 0 || height < 0 ? FloatBox.EMPTY
					: new FloatBox((float) x, (float) y, (float) (width + x), (float) (height + y));
		}
		case RectangleRegion r -> r.width() < 0 || r.height() < 0 ? FloatBox.EMPTY
				: new FloatBox((float) r.x(), (float) r.y(), (float) (r.width() + r.x()),
						(float) (r.height() + r.y()));
		case PolygonRegion p -> polygonBox(p.points());
		case ImageRegion i -> new FloatBox((float) i.x(), (float) i.y(), (float) (i.imageWidth() + i.x()),
				(float) (i.imageHeight() + i.y()));
		};
	}

	// Polygon.doComputeGeomBounds: fewer than two points (one coordinate pair) has empty bounds
	private static FloatBox polygonBox(List<Point> points) {
		if (points.size() < 2) return FloatBox.EMPTY;

		float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
		for (final Point point : points) {
			final float x = (float) point.getX();
			final float y = (float) point.getY();
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
		}

		return new FloatBox(minX, minY, maxX, maxY);
	}

	/**
	 * @return <tt>true</tt> if the shape contains the point (target coordinates, already rounded
	 *         to float). Images always contain points in their bounds; their alpha is tested
	 *         separately.
	 */
	static boolean contains(Region region, float x, float y) {
		return switch (region) {
		case EllipseRegion e -> ellipseContains(e, x, y);
		case RectangleRegion r -> rectangleContains(r, x, y);
		case PolygonRegion p -> polygonContains(p.points(), x, y);
		case ImageRegion i -> true;
		};
	}

	// Ellipse.doConfigShape + com.sun.javafx.geom.Ellipse2D.contains
	private static boolean ellipseContains(EllipseRegion e, float px, float py) {
		final float x = (float) (e.centerX() - e.radiusX());
		final float y = (float) (e.centerY() - e.radiusY());
		final float w = (float) (e.radiusX() * 2.0);
		final float h = (float) (e.radiusY() * 2.0);

		if (w <= 0) return false;
		final float normx = (px - x) / w - 0.5f;
		if (h <= 0) return false;
		final float normy = (py - y) / h - 0.5f;

		return (normx * normx + normy * normy) < 0.25f;
	}

	// Rectangle.doConfigShape + RoundRectangle2D.contains with no arcs: left/top edges in,
	// right/bottom edges out
	private static boolean rectangleContains(RectangleRegion r, float px, float py) {
		final float x0 = (float) r.x();
		final float y0 = (float) r.y();
		final float w = (float) r.width();
		final float h = (float) r.height();

		if (w <= 0f || h <= 0f) return false;

		return px >= x0 && py >= y0 && px < x0 + w && py < y0 + h;
	}

	// Polygon.doConfigShape (moveTo, lineTo..., closePath) + Path2D.contains/pointCrossings with
	// the non-zero winding rule
	private static boolean polygonContains(List<Point> points, float px, float py) {
		if (!(px * 0f + py * 0f == 0f)) return false; // NaN or infinite

		final float movx = (float) points.get(0).getX();
		final float movy = (float) points.get(0).getY();
		float curx = movx;
		float cury = movy;
		int crossings = 0;

		for (int i = 1; i < points.size(); i++) {
			final float endx = (float) points.get(i).getX();
			final float endy = (float) points.get(i).getY();
			crossings += pointCrossingsForLine(px, py, curx, cury, endx, endy);
			curx = endx;
			cury = endy;
		}

		if (cury != movy) crossings += pointCrossingsForLine(px, py, curx, cury, movx, movy);

		return crossings != 0;
	}

	// com.sun.javafx.geom.Shape.pointCrossingsForLine
	private static int pointCrossingsForLine(float px, float py, float x0, float y0, float x1, float y1) {
		if (py < y0 && py < y1) return 0;
		if (py >= y0 && py >= y1) return 0;
		if (px >= x0 && px >= x1) return 0;
		if (px < x0 && px < x1) return (y0 < y1) ? 1 : -1;

		final float xintercept = x0 + (py - y0) * (x1 - x0) / (y1 - y0);
		if (px >= xintercept) return 0;

		return (y0 < y1) ? 1 : -1;
	}
}
```

`core/src/main/java/com/shootoff/targets/model/PlacedTarget.java`:

```java
package com.shootoff.targets.model;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;

/**
 * A target on a canvas: its definition plus where and how it is drawn. Only its
 * {@link TargetSet} changes it.
 *
 * The geometry reproduces JavaFX's Group: the local bounds are the float union of the visible
 * regions' bounds, the scale pivots about their center, and points map to target coordinates
 * through the same double-then-float steps as Node.parentToLocal.
 */
public final class PlacedTarget {
	private final TargetId id;
	private final TargetDefinition definition;
	private volatile Placement placement;
	private final AtomicIntegerArray regionVisible;
	private final AtomicReferenceArray<AlphaMask> imageMasks;

	PlacedTarget(TargetId id, TargetDefinition definition, Placement placement) {
		this.id = id;
		this.definition = definition;
		this.placement = placement;

		final List<Region> regions = definition.regions();
		regionVisible = new AtomicIntegerArray(regions.size());
		for (int i = 0; i < regions.size(); i++) {
			regionVisible.set(i, regions.get(i).isVisibleByDefault() ? 1 : 0);
		}
		imageMasks = new AtomicReferenceArray<>(regions.size());
	}

	public TargetId getId() {
		return id;
	}

	public TargetDefinition getDefinition() {
		return definition;
	}

	public Placement getPlacement() {
		return placement;
	}

	public Point getPosition() {
		final Placement p = placement;
		return new Point(p.x(), p.y());
	}

	public double getScaleX() {
		return placement.scaleX();
	}

	public double getScaleY() {
		return placement.scaleY();
	}

	public boolean isVisible() {
		return placement.visible();
	}

	public boolean isRegionVisible(int regionIndex) {
		return regionVisible.get(regionIndex) != 0;
	}

	/**
	 * @return the current frame of an image region, if the UI has given it
	 */
	public Optional<AlphaMask> getImageMask(int regionIndex) {
		return Optional.ofNullable(imageMasks.get(regionIndex));
	}

	/**
	 * @return the width and height of {@link #getBounds()}
	 */
	public Size getSize() {
		final Rect bounds = getBounds();
		return new Size(bounds.getWidth(), bounds.getHeight());
	}

	/**
	 * @return the union of the visible regions' bounds in target coordinates (JavaFX's
	 *         layoutBounds of the target's group); empty (negative size) if no region is visible
	 */
	public Rect getLocalBounds() {
		return localBox(placement).toRect();
	}

	/**
	 * @return the center of {@link #getLocalBounds()}, which the target is scaled about
	 */
	public Point getPivot() {
		final Rect local = getLocalBounds();
		return new Point(local.getMinX() + local.getWidth() / 2, local.getMinY() + local.getHeight() / 2);
	}

	/**
	 * @return the target's bounds on its canvas (JavaFX's boundsInParent)
	 */
	public Rect getBounds() {
		return boundsAt(placement);
	}

	/**
	 * @return the bounds the target would have on its canvas with another placement
	 */
	public Rect boundsAt(Placement placement) {
		final Transform t = transform(placement);
		return localBox(placement).transform(t.scaleX(), t.scaleY(), t.tx(), t.ty()).toRect();
	}

	/**
	 * @return the canvas point (x, y) in target coordinates, rounded to float like
	 *         Node.parentToLocal
	 */
	public Point parentToLocal(double x, double y) {
		final Transform t = transform(placement);
		final double px = (float) x;
		final double py = (float) y;

		return new Point((float) ((px - t.tx()) / t.scaleX()), (float) ((py - t.ty()) / t.scaleY()));
	}

	/**
	 * @return the target point (x, y) on the canvas, rounded to float like Node.localToParent
	 */
	public Point localToParent(double x, double y) {
		final Transform t = transform(placement);
		return new Point((float) ((float) x * t.scaleX() + t.tx()), (float) ((float) y * t.scaleY() + t.ty()));
	}

	/**
	 * @return the region's bounds on the canvas, as the JavaFX app computed them for a hit: the
	 *         region node's bounds through the target's transform, in double
	 */
	public Rect regionBounds(Region region) {
		final Placement p = placement;
		final FloatBox box = regionBox(region, p);
		if (box.isEmpty()) return new Rect(0, 0, -1, -1);

		final Rect local = box.toRect();
		final Transform t = transform(p);
		final double x1 = local.getMinX() * t.scaleX() + t.tx();
		final double x2 = local.getMaxX() * t.scaleX() + t.tx();
		final double y1 = local.getMinY() * t.scaleY() + t.ty();
		final double y2 = local.getMaxY() * t.scaleY() + t.ty();

		return new Rect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1));
	}

	/**
	 * @return <tt>true</tt> if the region's shape contains the point given in target coordinates
	 */
	boolean regionContains(Region region, double localX, double localY) {
		float x = (float) localX;
		float y = (float) localY;
		final Placement p = placement;

		if (!region.isResizable() && isScaled(p)) {
			// Undo the unresizable region's own inverse scale about its center
			final Rect box = FloatGeometry.box(region).toRect();
			final double cx = box.getMinX() + box.getWidth() / 2;
			final double cy = box.getMinY() + box.getHeight() / 2;
			x = (float) (cx + (x - cx) * p.scaleX());
			y = (float) (cy + (y - cy) * p.scaleY());
		}

		return FloatGeometry.contains(region, x, y);
	}

	void setPlacement(Placement placement) {
		this.placement = placement;
	}

	void setRegionVisible(int regionIndex, boolean visible) {
		regionVisible.set(regionIndex, visible ? 1 : 0);
	}

	void setImageMask(int regionIndex, AlphaMask mask) {
		imageMasks.set(regionIndex, mask);
	}

	private static boolean isScaled(Placement p) {
		return p.scaleX() != 1 || p.scaleY() != 1;
	}

	// The region's bounds in target coordinates. An unresizable region keeps its size: it is
	// scaled by the inverse of the target's scale about its own center.
	private FloatBox regionBox(Region region, Placement p) {
		final FloatBox box = FloatGeometry.box(region);
		if (region.isResizable() || box.isEmpty() || !isScaled(p)) return box;

		final double sx = 1 / p.scaleX();
		final double sy = 1 / p.scaleY();
		final Rect r = box.toRect();
		final double cx = r.getMinX() + r.getWidth() / 2;
		final double cy = r.getMinY() + r.getHeight() / 2;

		return box.transform(sx, sy, cx - cx * sx, cy - cy * sy);
	}

	private FloatBox localBox(Placement p) {
		FloatBox union = FloatBox.EMPTY;
		final List<Region> regions = definition.regions();
		for (int i = 0; i < regions.size(); i++) {
			if (isRegionVisible(i)) union = union.union(regionBox(regions.get(i), p));
		}
		return union;
	}

	// Node.updateLocalToParentTransform: layout translation only when unscaled, otherwise
	// translate(layout + pivot) * scale * translate(-pivot), which leaves
	// tx = (layoutX + pivotX) - pivotX * scaleX
	private Transform transform(Placement p) {
		if (!isScaled(p)) return new Transform(1, 1, p.x(), p.y());

		final Rect local = localBox(p).toRect();
		final double pivotX = local.getMinX() + local.getWidth() / 2;
		final double pivotY = local.getMinY() + local.getHeight() / 2;

		return new Transform(p.scaleX(), p.scaleY(), (p.x() + pivotX) - pivotX * p.scaleX(),
				(p.y() + pivotY) - pivotY * p.scaleY());
	}

	private record Transform(double scaleX, double scaleY, double tx, double ty) {}
}
```

`core/src/main/java/com/shootoff/targets/model/TargetSetListener.java`:

```java
package com.shootoff.targets.model;

/**
 * Hears about changes to a {@link TargetSet}. It is called on the thread that made the change,
 * after the change; a UI marshals onto its own thread if it needs to.
 */
public interface TargetSetListener {
	default void targetAdded(PlacedTarget target) {}

	default void targetRemoved(PlacedTarget target) {}

	/**
	 * The target's placement or a region's visibility changed.
	 */
	default void targetChanged(PlacedTarget target) {}
}
```

`core/src/main/java/com/shootoff/targets/model/TargetSet.java`:

```java
package com.shootoff.targets.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.shootoff.geom.Rect;

/**
 * The targets on one canvas, bottom to top. Every change to a placed target goes through its set,
 * which tells its listeners. Updates to a target that isn't in the set (for example one another
 * thread just removed) are ignored.
 */
public final class TargetSet {
	private static final AtomicLong NEXT_ID = new AtomicLong(1);

	private final List<PlacedTarget> targets = new ArrayList<>();
	private final List<TargetSetListener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * Adds a target on top at {@link Placement#ORIGIN}.
	 */
	public PlacedTarget add(TargetDefinition definition) {
		return add(definition, Placement.ORIGIN);
	}

	/**
	 * Adds a target on top.
	 */
	public PlacedTarget add(TargetDefinition definition, Placement placement) {
		final PlacedTarget target = new PlacedTarget(new TargetId(NEXT_ID.getAndIncrement()), definition, placement);

		synchronized (this) {
			targets.add(target);
		}

		for (final TargetSetListener listener : listeners) {
			listener.targetAdded(target);
		}

		return target;
	}

	public void remove(TargetId id) {
		final PlacedTarget removed;

		synchronized (this) {
			final int index = indexOf(id);
			if (index < 0) return;
			removed = targets.remove(index);
		}

		for (final TargetSetListener listener : listeners) {
			listener.targetRemoved(removed);
		}
	}

	/**
	 * @return the targets, bottom (first added) to top
	 */
	public synchronized List<PlacedTarget> getTargets() {
		return List.copyOf(targets);
	}

	public synchronized Optional<PlacedTarget> get(TargetId id) {
		final int index = indexOf(id);
		return index < 0 ? Optional.empty() : Optional.of(targets.get(index));
	}

	/**
	 * @return the target's position in the set (0 is the bottom), or -1 if it isn't in the set
	 */
	public synchronized int indexOf(TargetId id) {
		for (int i = 0; i < targets.size(); i++) {
			if (targets.get(i).getId().equals(id)) return i;
		}
		return -1;
	}

	public synchronized int size() {
		return targets.size();
	}

	public void place(TargetId id, Placement placement) {
		update(id, target -> target.setPlacement(placement));
	}

	public void move(TargetId id, double x, double y) {
		update(id, target -> target.setPlacement(target.getPlacement().withPosition(x, y)));
	}

	public void scale(TargetId id, double scaleX, double scaleY) {
		update(id, target -> target.setPlacement(target.getPlacement().withScale(scaleX, scaleY)));
	}

	/**
	 * Scales the target to a width and height on the canvas, as the JavaFX app's setDimensions
	 * always did: an axis whose size is within 0.001 of the request is left alone, and an axis
	 * without a size can't be scaled.
	 */
	public void resize(TargetId id, double width, double height) {
		update(id, target -> {
			final Placement p = target.getPlacement();
			final Rect bounds = target.getBounds();
			double scaleX = p.scaleX();
			double scaleY = p.scaleY();

			if (bounds.getWidth() > 0 && Math.abs(bounds.getWidth() - width) > .001) {
				scaleX = scaleX * (1.0 + ((width - bounds.getWidth()) / bounds.getWidth()));
			}

			if (bounds.getHeight() > 0 && Math.abs(bounds.getHeight() - height) > .001) {
				scaleY = scaleY * (1.0 + ((height - bounds.getHeight()) / bounds.getHeight()));
			}

			target.setPlacement(p.withScale(scaleX, scaleY));
		});
	}

	public void setVisible(TargetId id, boolean visible) {
		update(id, target -> target.setPlacement(target.getPlacement().withVisible(visible)));
	}

	public void setRegionVisible(TargetId id, int regionIndex, boolean visible) {
		update(id, target -> target.setRegionVisible(regionIndex, visible));
	}

	/**
	 * Gives the hit tester an image region's current frame. Masks change no geometry, so
	 * listeners aren't told.
	 */
	public void setImageMask(TargetId id, int regionIndex, AlphaMask mask) {
		get(id).ifPresent(target -> target.setImageMask(regionIndex, mask));
	}

	public void addListener(TargetSetListener listener) {
		listeners.add(listener);
	}

	public void removeListener(TargetSetListener listener) {
		listeners.remove(listener);
	}

	private void update(TargetId id, Consumer<PlacedTarget> change) {
		final PlacedTarget target;

		synchronized (this) {
			final int index = indexOf(id);
			if (index < 0) return;
			target = targets.get(index);
			change.accept(target);
		}

		for (final TargetSetListener listener : listeners) {
			listener.targetChanged(target);
		}
	}
}
```

`core/src/main/java/com/shootoff/targets/model/HitTester.java`:

```java
package com.shootoff.targets.model;

import java.util.List;
import java.util.Optional;

import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;

/**
 * Finds the region a shot hits, exactly as the JavaFX app's hit test did.
 */
public final class HitTester {
	private HitTester() {}

	/**
	 * @return the topmost region under (x, y) of the topmost visible target that has one. A
	 *         hidden target is never hit; a region hidden by its <tt>visible</tt> tag still is.
	 */
	public static Optional<Hit> hit(TargetSet targets, double x, double y) {
		final List<PlacedTarget> all = targets.getTargets();

		for (int i = all.size() - 1; i >= 0; i--) {
			final PlacedTarget target = all.get(i);
			if (!target.isVisible()) continue;

			final Optional<Hit> hit = hit(target, x, y);
			if (hit.isPresent()) return hit;
		}

		return Optional.empty();
	}

	/**
	 * @return the topmost region of one target under (x, y), whether or not the target is
	 *         visible. Shapes are exact; an image is hit where its current frame isn't fully
	 *         transparent, or anywhere in its bounds if the UI hasn't given its frame. Regions
	 *         tagged <tt>ignoreHit</tt> let shots through.
	 */
	public static Optional<Hit> hit(PlacedTarget target, double x, double y) {
		if (!target.getBounds().contains(x, y)) return Optional.empty();

		final List<Region> regions = target.getDefinition().regions();
		for (int i = regions.size() - 1; i >= 0; i--) {
			final Region region = regions.get(i);
			final Rect regionBounds = target.regionBounds(region);

			if (!regionBounds.contains(x, y)) continue;
			if (region.ignoresHits()) continue;

			if (region instanceof ImageRegion) {
				final Optional<AlphaMask> mask = target.getImageMask(i);
				if (mask.isPresent() && !isOpaqueAt(mask.get(), regionBounds, x, y)) continue;
			} else {
				final Point local = target.parentToLocal(x, y);
				if (!target.regionContains(region, local.getX(), local.getY())) continue;
			}

			return Optional.of(new Hit(target.getId(), region, target.parentToLocal(x, y)));
		}

		return Optional.empty();
	}

	// The pixel under the shot, in the image scaled to the region's size on the canvas when that
	// differs from the image's own size (TargetView.isHit before the model)
	private static boolean isOpaqueAt(AlphaMask mask, Rect regionBounds, double x, double y) {
		final int adjustedX = (int) (x - regionBounds.getMinX());
		final int adjustedY = (int) (y - regionBounds.getMinY());

		AlphaMask frame = mask;
		if (Math.abs(mask.getWidth() - regionBounds.getWidth()) > .0000001
				|| Math.abs(mask.getHeight() - regionBounds.getHeight()) > .0000001) {
			final int width = (int) regionBounds.getWidth();
			final int height = (int) regionBounds.getHeight();
			if (width <= 0 || height <= 0) return false;

			frame = mask.scaledTo(width, height);
		}

		return adjustedX < frame.getWidth() && adjustedY < frame.getHeight() && frame.isOpaque(adjustedX, adjustedY);
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.targets.model.*' --tests 'com.shootoff.TestNoJavaFxInCore' --console=plain`
Expected: `BUILD SUCCESSFUL`. `TestTargetSet` (6) and `TestHitTester` (9) pass, and so do Task 1's tests and `TestNoJavaFxInCore`.

- [ ] **Step 5: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 15 (**272**).

```bash
git add core/src/main/java/com/shootoff/targets/model core/src/test/java/com/shootoff/targets/model
git commit -m "Add placed targets, target sets and a JavaFX-exact hit tester to core"
git log -1 --format=%B
```

---
### Task 3: Hit-testing parity test and its recorded expectations

This is spec §8's parity test, written while the JavaFX hit path still exists. It must pass before Task 4 starts.

**Files:**
- Create: `javafx-app/src/main/java/com/shootoff/gui/targets/FxAlphaMasks.java`
- Create (tests): `javafx-app/src/test/java/com/shootoff/targets/LegacyFxHitTest.java`, `javafx-app/src/test/java/com/shootoff/targets/TestHitParity.java`
- Create (recorded by Step 3): `javafx-app/src/test/resources/targets/hit-parity.txt`

**Interfaces:**
- Consumes: `TargetDefinitions.load(Path)` (Task 1); `TargetSet`, `Placement`, `PlacedTarget.{getBounds, regionBounds}`, `HitTester.hit(PlacedTarget, x, y)`, `AlphaMask` (Task 2); today's `TargetIO.loadTarget(File, boolean)`, which builds the JavaFX nodes.
- Produces:
  - `com.shootoff.gui.targets.FxAlphaMasks.of(javafx.scene.image.Image) → AlphaMask`, cached per `Image`. Task 6's `TargetView` uses it.
  - `hit-parity.txt`: a line per target, placement and grid row, `targets/<file>|<placement>|<row>|<region index per column, -1 for a miss>`. After this task it is the regression reference.

- [ ] **Step 1: Create the alpha-mask adapter**

`javafx-app/src/main/java/com/shootoff/gui/targets/FxAlphaMasks.java` (GPL header, then):

```java
package com.shootoff.gui.targets;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.shootoff.targets.model.AlphaMask;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

/**
 * Turns the JavaFX image an image region shows into the alpha mask the core hit tester reads.
 * Animation frames repeat, so masks are cached per image.
 */
public final class FxAlphaMasks {
	private static final Map<Image, AlphaMask> MASKS = Collections.synchronizedMap(new WeakHashMap<>());

	private FxAlphaMasks() {}

	public static AlphaMask of(Image image) {
		return MASKS.computeIfAbsent(image, i -> AlphaMask.of(SwingFXUtils.fromFXImage(i, null)));
	}
}
```

- [ ] **Step 2: Write the parity test**

`javafx-app/src/test/java/com/shootoff/targets/LegacyFxHitTest.java`. The algorithm is `TargetView.isHit` as it is today (before Task 6), copied verbatim; only the `Hit` construction is replaced by a record:

```java
package com.shootoff.targets;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Optional;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;

/**
 * The JavaFX hit test the app used before hits came from the core model (TargetView.isHit up to
 * Plan 2 Task 6), kept so TestHitParity can keep comparing the model with JavaFX geometry.
 */
final class LegacyFxHitTest {
	record FxHit(int regionIndex, int impactX, int impactY) {}

	private LegacyFxHitTest() {}

	static Optional<FxHit> hit(Group targetGroup, double x, double y) {
		if (targetGroup.getBoundsInParent().contains(x, y)) {
			// Target was hit, see if a specific region was hit
			for (int i = targetGroup.getChildren().size() - 1; i >= 0; i--) {
				final Node node = targetGroup.getChildren().get(i);

				if (!(node instanceof TargetRegion)) continue;

				final Bounds nodeBounds = targetGroup.getLocalToParentTransform().transform(node.getBoundsInParent());

				final int adjustedX = (int) (x - nodeBounds.getMinX());
				final int adjustedY = (int) (y - nodeBounds.getMinY());

				if (nodeBounds.contains(x, y)) {
					final TargetRegion region = (TargetRegion) node;

					// Ignore regions where ignoreHit tag is true
					if (region.tagExists(Target.TAG_IGNORE_HIT)
							&& Boolean.parseBoolean(region.getTag(Target.TAG_IGNORE_HIT)))
						continue;

					if (region.getType() == RegionType.IMAGE) {
						final Image currentImage = ((ImageRegion) region).getImage();

						if (adjustedX < 0 || adjustedY < 0) return Optional.empty();

						if (Math.abs(currentImage.getWidth() - nodeBounds.getWidth()) > .0000001
								|| Math.abs(currentImage.getHeight() - nodeBounds.getHeight()) > .0000001) {

							final BufferedImage bufferedOriginal = SwingFXUtils.fromFXImage(currentImage, null);

							final java.awt.Image tmp = bufferedOriginal.getScaledInstance((int) nodeBounds.getWidth(),
									(int) nodeBounds.getHeight(), java.awt.Image.SCALE_SMOOTH);
							final BufferedImage bufferedResized = new BufferedImage((int) nodeBounds.getWidth(),
									(int) nodeBounds.getHeight(), BufferedImage.TYPE_INT_ARGB);

							final Graphics2D g2d = bufferedResized.createGraphics();
							g2d.drawImage(tmp, 0, 0, null);
							g2d.dispose();

							if (adjustedX >= bufferedResized.getWidth() || adjustedY >= bufferedResized.getHeight()
									|| bufferedResized.getRGB(adjustedX, adjustedY) >> 24 == 0) {
								continue;
							}
						} else {
							if (adjustedX >= currentImage.getWidth() || adjustedY >= currentImage.getHeight()
									|| currentImage.getPixelReader().getArgb(adjustedX, adjustedY) >> 24 == 0) {
								continue;
							}
						}
					} else {
						final Point2D localCoords = targetGroup.parentToLocal(x, y);
						if (!node.contains(localCoords)) continue;
					}

					return Optional.of(new FxHit(i, adjustedX, adjustedY));
				}
			}
		}

		return Optional.empty();
	}
}
```

`javafx-app/src/test/java/com/shootoff/targets/TestHitParity.java`:

```java
package com.shootoff.targets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.geom.Rect;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.targets.FxAlphaMasks;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetSet;

import javafx.scene.Group;
import javafx.scene.Node;

/**
 * Hit-testing parity (spec §8). For every bundled target, over a grid of points across its bounds
 * at three placements, the core HitTester must find the same region as the JavaFX hit test the app
 * used before (LegacyFxHitTest), with the same impact offsets. The JavaFX results are recorded in
 * hit-parity.txt, which keeps checking the model after the JavaFX hit path is gone.
 */
public class TestHitParity {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private static final Path EXPECTATIONS = Paths.get("javafx-app/src/test/resources/targets/hit-parity.txt");
	private static final String RECORD_VARIABLE = "SHOOTOFF_RECORD_HIT_PARITY";

	// Unscaled at the origin; shrunk and moved; stretched unevenly from a negative position
	private static final Placement[] PLACEMENTS = { new Placement(0, 0, 1, 1, true),
			new Placement(37.25, 81.5, 0.63, 0.63, true), new Placement(-12.75, 20.125, 1.71, 0.88, true) };
	// Points per row and per column for each placement
	private static final int[] GRID = { 16, 12, 12 };
	// How far past the target's bounds the grid reaches
	private static final double MARGIN = 3;

	@Before
	public void setUp() {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
	}

	@Test
	public void modelMatchesJavaFxForEveryBundledTarget() throws IOException, TargetFormatException {
		final List<String> mismatches = new ArrayList<>();
		final List<String> recorded = new ArrayList<>();

		for (final Path file : bundledTargets()) {
			for (int p = 0; p < PLACEMENTS.length; p++) {
				final Placement placement = PLACEMENTS[p];
				final Group group = TargetIO.loadTarget(file.toFile(), false).get().getTargetGroup();
				group.setLayoutX(placement.x());
				group.setLayoutY(placement.y());
				group.setScaleX(placement.scaleX());
				group.setScaleY(placement.scaleY());
				final PlacedTarget placed = place(file, group, placement);

				for (int row = 0; row < GRID[p]; row++) {
					final StringBuilder line = new StringBuilder(key(file, p, row)).append('|');

					for (int col = 0; col < GRID[p]; col++) {
						final double x = gridX(placed, p, col);
						final double y = gridY(placed, p, row);
						final Optional<LegacyFxHitTest.FxHit> fx = LegacyFxHitTest.hit(group, x, y);
						final Optional<com.shootoff.targets.model.Hit> model = HitTester.hit(placed, x, y);
						final int fxRegion = fx.map(LegacyFxHitTest.FxHit::regionIndex).orElse(-1);
						final int modelRegion = model.map(hit -> hit.region().index()).orElse(-1);

						if (fxRegion != modelRegion) {
							mismatches.add(String.format("%s at (%.6f, %.6f): JavaFX region %d, model region %d",
									key(file, p, row), x, y, fxRegion, modelRegion));
						} else if (fx.isPresent()) {
							final Rect bounds = placed.regionBounds(model.get().region());
							final int impactX = (int) (x - bounds.getMinX());
							final int impactY = (int) (y - bounds.getMinY());

							if (impactX != fx.get().impactX() || impactY != fx.get().impactY()) {
								mismatches.add(String.format("%s at (%.6f, %.6f): JavaFX impact (%d, %d), model (%d, %d)",
										key(file, p, row), x, y, fx.get().impactX(), fx.get().impactY(), impactX,
										impactY));
							}
						}

						if (col > 0) line.append(' ');
						line.append(fxRegion);
					}

					recorded.add(line.toString());
				}
			}
		}

		if (System.getenv(RECORD_VARIABLE) != null) {
			final List<String> lines = new ArrayList<>();
			lines.add("# Hit-parity expectations recorded from the JavaFX hit test by TestHitParity.");
			lines.add("# target|placement|row|region index hit at each grid column (-1 = miss). Never edit by hand.");
			lines.addAll(recorded);
			Files.createDirectories(EXPECTATIONS.getParent());
			Files.write(EXPECTATIONS, lines, StandardCharsets.UTF_8);
		}

		assertTrue(mismatches.size() + " mismatches:\n"
				+ mismatches.stream().limit(20).collect(Collectors.joining("\n")), mismatches.isEmpty());
	}

	@Test
	public void modelMatchesRecordedExpectations() throws IOException, TargetFormatException {
		final Map<String, String> expected = new LinkedHashMap<>();
		for (final String line : Files.readAllLines(EXPECTATIONS, StandardCharsets.UTF_8)) {
			if (line.isEmpty() || line.startsWith("#")) continue;

			final int split = line.lastIndexOf('|');
			expected.put(line.substring(0, split), line.substring(split + 1));
		}

		final List<String> mismatches = new ArrayList<>();
		final List<String> keys = new ArrayList<>();

		for (final Path file : bundledTargets()) {
			for (int p = 0; p < PLACEMENTS.length; p++) {
				final Group group = TargetIO.loadTarget(file.toFile(), false).get().getTargetGroup();
				final PlacedTarget placed = place(file, group, PLACEMENTS[p]);

				for (int row = 0; row < GRID[p]; row++) {
					final String key = key(file, p, row);
					keys.add(key);

					final StringBuilder actual = new StringBuilder();
					for (int col = 0; col < GRID[p]; col++) {
						final int region = HitTester.hit(placed, gridX(placed, p, col), gridY(placed, p, row))
								.map(hit -> hit.region().index()).orElse(-1);
						if (col > 0) actual.append(' ');
						actual.append(region);
					}

					if (!actual.toString().equals(expected.get(key))) {
						mismatches.add(key + ": recorded " + expected.get(key) + ", model " + actual);
					}
				}
			}
		}

		assertEquals("rows in hit-parity.txt", new HashSet<>(keys), new HashSet<>(expected.keySet()));
		assertTrue(mismatches.size() + " mismatches:\n"
				+ mismatches.stream().limit(20).collect(Collectors.joining("\n")), mismatches.isEmpty());
	}

	private static List<Path> bundledTargets() throws IOException {
		try (Stream<Path> paths = Files.walk(Paths.get("targets"))) {
			return paths.filter(path -> path.toString().endsWith(".target")).sorted().collect(Collectors.toList());
		}
	}

	private static String key(Path file, int placement, int row) {
		return file.toString().replace(File.separatorChar, '/') + "|" + placement + "|" + row;
	}

	// The model of the target in group, placed the same way, with each image region's current
	// frame as its alpha mask
	private static PlacedTarget place(Path file, Group group, Placement placement) throws TargetFormatException {
		final TargetSet targets = new TargetSet();
		final PlacedTarget placed = targets.add(TargetDefinitions.load(file), placement);

		for (int i = 0; i < group.getChildren().size(); i++) {
			final Node node = group.getChildren().get(i);
			if (node instanceof ImageRegion image) {
				targets.setImageMask(placed.getId(), i, FxAlphaMasks.of(image.getImage()));
			}
		}

		return placed;
	}

	private static double gridX(PlacedTarget placed, int placement, int col) {
		final Rect bounds = placed.getBounds();
		return bounds.getMinX() - MARGIN + (bounds.getWidth() + 2 * MARGIN) * (col + 0.5) / GRID[placement];
	}

	private static double gridY(PlacedTarget placed, int placement, int row) {
		final Rect bounds = placed.getBounds();
		return bounds.getMinY() - MARGIN + (bounds.getHeight() + 2 * MARGIN) * (row + 0.5) / GRID[placement];
	}
}
```

- [ ] **Step 3: Compare with JavaFX and record the expectations**

The recording flag is an environment variable, which Gradle passes to the test JVM. `cleanTest` makes the test run even if Gradle thinks it is up to date (timeout 600000 ms):

```bash
cd /home/bfears/projects/ShootOFF
SHOOTOFF_RECORD_HIT_PARITY=1 ./gradlew :javafx-app:cleanTest :javafx-app:test \
  --tests 'com.shootoff.targets.TestHitParity.modelMatchesJavaFxForEveryBundledTarget' --console=plain 2>&1 | tail -15
command grep -vc '^#' javafx-app/src/test/resources/targets/hit-parity.txt
command grep -c ' [0-9]' javafx-app/src/test/resources/targets/hit-parity.txt
```

Expected:
- `BUILD SUCCESSFUL` with the test passing
- `1040` data lines: 26 targets × (16 + 12 + 12) rows
- a non-zero count of rows with hits

If the test reports mismatches, the model's geometry differs from JavaFX's. Don't loosen the test and don't record. Each mismatch message gives the target, placement and point:
- a region index mismatch at a point on or next to an edge means one of `FloatGeometry`'s or `PlacedTarget`'s rounding steps doesn't match the OpenJFX method its comment names. Compare the two and fix the model in this task, noting the fix in the report.
- an impact mismatch means `PlacedTarget.regionBounds` differs from `targetGroup.getLocalToParentTransform().transform(node.getBoundsInParent())`.

Rerun this step after any fix. If a mismatch can't be explained within an hour, stop and report it (BLOCKED) with the first 20 mismatch lines.

- [ ] **Step 4: Run both parity tests without recording**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.targets.TestHitParity' --console=plain`
Expected: both tests pass. `git status --short` shows the new `hit-parity.txt` and no change to it from this run.

- [ ] **Step 5: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 2 (**274**).

```bash
git add javafx-app/src/main/java/com/shootoff/gui/targets/FxAlphaMasks.java \
  javafx-app/src/test/java/com/shootoff/targets/LegacyFxHitTest.java \
  javafx-app/src/test/java/com/shootoff/targets/TestHitParity.java \
  javafx-app/src/test/resources/targets/hit-parity.txt
git commit -m "Add the hit-testing parity test and record JavaFX's hits for every bundled target"
git log -1 --format=%B
```

---
### Task 4: JavaFX target nodes are built from the model

`TargetIO` keeps its public load methods, but builds the v1 region nodes from a `TargetDefinition` instead of from its own XML reading. `XMLTargetReader` and `TargetReader` are deleted. `TargetComponents` now carries the definition next to the nodes. Nothing else changes in this task: `TargetView` and the canvases still take the group and tags.

**Files:**
- Modify (rewrite): `javafx-app/src/main/java/com/shootoff/targets/io/TargetIO.java`
- Delete (`git rm`): `javafx-app/src/main/java/com/shootoff/targets/io/XMLTargetReader.java`, `TargetReader.java`
- Test: `javafx-app/src/test/java/com/shootoff/targets/io/TestTargetIO.java` (3 new tests)

**Interfaces:**
- Consumes: `TargetDefinitions.load(Path)`, `TargetDefinitions.load(InputStream, ResourceResolver)`, `ResourceResolver.files()`/`classLoader(ClassLoader)`, `TargetDefinition`, the model regions (Task 1).
- Produces (`com.shootoff.targets.io.TargetIO`):
  - `public static class TargetComponents`:
    - `TargetComponents(TargetDefinition definition, Group targetGroup)`: the group holds exactly one region node per model region, in order
    - `static TargetComponents empty(File targetFile)`: `null` means no file
    - `TargetDefinition getDefinition()`, `File getTargetFile()` (or `null`), `Group getTargetGroup()`
    - `Map<String,String> getTargetTags()`: a new modifiable `HashMap` copy on each call, as the reader's map used to be
    - `TargetComponents withTargetFile(File)`
  - `static TargetComponents buildTarget(TargetDefinition, ResourceResolver, boolean playAnimations) throws IOException`: the nodes the old reader built, with the same types, fills, tags, visibility, opacity (the `opacity` tag, else `DEFAULT_OPACITY` for shapes), image files, and GIF animations (played once when asked)
  - unchanged signatures: `loadTarget(File)`, `loadTarget(File, boolean)`, `loadTarget(InputStream, ClassLoader)`, `loadTarget(InputStream, boolean, ClassLoader)`, `saveTarget(Map, List<Node>, File)`, `DEFAULT_OPACITY`. The loaders now return `Optional.empty()` for a missing or malformed target (ruling 11) and log the parser's message.

- [ ] **Step 1: Write the failing tests**

In `javafx-app/src/test/java/com/shootoff/targets/io/TestTargetIO.java`, add these imports:

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
```

(`java.util.List`, `javafx.scene.Node` and the static `org.junit.Assert.*` are already imported.) Then add these three tests before the final `}`:

```java
	@Test
	public void testBundledTargetsBuildOneNodePerRegion() throws IOException {
		final List<Path> files;
		try (Stream<Path> paths = Files.walk(Paths.get("targets"))) {
			files = paths.filter(p -> p.toString().endsWith(".target")).sorted().toList();
		}

		for (final Path file : files) {
			final TargetComponents tc = TargetIO.loadTarget(file.toFile(), false).get();
			final List<com.shootoff.targets.model.Region> regions = tc.getDefinition().regions();

			assertEquals(file.toString(), regions.size(), tc.getTargetGroup().getChildren().size());
			assertEquals(file.toFile(), tc.getTargetFile());

			for (int i = 0; i < regions.size(); i++) {
				final com.shootoff.targets.model.Region region = regions.get(i);
				final Node node = tc.getTargetGroup().getChildren().get(i);

				assertEquals(region.tags(), ((TargetRegion) node).getAllTags());
				assertEquals(region.isVisibleByDefault(), node.isVisible());

				if (region instanceof com.shootoff.targets.model.ImageRegion image) {
					final ImageRegion imageNode = (ImageRegion) node;
					assertEquals(image.x(), imageNode.getLayoutX(), 0);
					assertEquals(image.y(), imageNode.getLayoutY(), 0);
					assertEquals(image.imageWidth(), imageNode.getImage().getWidth(), 0);
					assertEquals(image.imageHeight(), imageNode.getImage().getHeight(), 0);
				} else {
					assertEquals(Double.parseDouble(region.tags().getOrDefault("opacity", "0.5")), node.getOpacity(), 0);
				}
			}
		}
	}

	@Test
	public void testMissingTargetFileLoadsNothing() {
		assertFalse(TargetIO.loadTarget(new File("targets/no_such_target.target")).isPresent());
	}

	@Test
	public void testMalformedTargetLoadsNothing() throws IOException {
		final File malformed = new File("temp_malformed.target");
		Files.writeString(malformed.toPath(), "<target><ellipse centerX=\"1\"");

		try {
			assertFalse(TargetIO.loadTarget(malformed).isPresent());
		} finally {
			if (!malformed.delete()) System.err.println("Failed to delete " + malformed.getPath());
		}
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.targets.io.TestTargetIO' --console=plain`
Expected: FAIL. Compilation reports `cannot find symbol: method getDefinition()` and `getTargetFile()`.

- [ ] **Step 3: Rewrite `TargetIO` on the model**

Replace everything after the license header of `javafx-app/src/main/java/com/shootoff/targets/io/TargetIO.java` with:

```java
package com.shootoff.targets.io;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.gui.controller.TargetEditorController;
import com.shootoff.targets.EllipseRegion;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.PolygonRegion;
import com.shootoff.targets.RectangleRegion;
import com.shootoff.targets.TargetRegion;
import com.shootoff.targets.animation.GifAnimation;
import com.shootoff.targets.animation.SpriteAnimation;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.ResourceResolver;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;

import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;

/**
 * Loads targets into JavaFX nodes (one v1 region node per model region, built from the core
 * target model) and saves the target editor's nodes.
 */
public class TargetIO {
	private static final Logger logger = LoggerFactory.getLogger(TargetIO.class);

	public static final double DEFAULT_OPACITY = 0.5;

	/**
	 * A target's model and the JavaFX nodes built from it: the group holds one region node per
	 * model region, in the same order.
	 */
	public static class TargetComponents {
		private final TargetDefinition definition;
		private final Group targetGroup;

		public TargetComponents(TargetDefinition definition, Group targetGroup) {
			this.definition = definition;
			this.targetGroup = targetGroup;
		}

		/**
		 * @param targetFile
		 *            the target's file, or <tt>null</tt>
		 * @return a target with no regions, e.g. a stand-in for a file that can't be loaded
		 */
		public static TargetComponents empty(File targetFile) {
			return new TargetComponents(new TargetDefinition(Optional.ofNullable(targetFile), Map.of(), List.of()),
					new Group());
		}

		public TargetDefinition getDefinition() {
			return definition;
		}

		/**
		 * @return the file the target was loaded from, or <tt>null</tt>
		 */
		public File getTargetFile() {
			return definition.file().orElse(null);
		}

		public Group getTargetGroup() {
			return targetGroup;
		}

		/**
		 * @return a modifiable copy of the target's tags
		 */
		public Map<String, String> getTargetTags() {
			return new HashMap<>(definition.tags());
		}

		public TargetComponents withTargetFile(File targetFile) {
			return new TargetComponents(definition.withFile(targetFile), targetGroup);
		}
	}

	public static void saveTarget(final Map<String, String> targetTags, final List<Node> regions,
			final File targetFile) {
		RegionVisitor visitor;

		if (targetFile.getName().endsWith("target")) {
			visitor = new XMLTargetWriter(targetFile);
		} else {
			logger.error("Unknown target file type.");
			return;
		}

		final URI baseURI = new File(System.getProperty("user.dir")).toURI();

		for (final Node node : regions) {
			final TargetRegion region = (TargetRegion) node;

			switch (region.getType()) {
			case IMAGE: {
				final ImageRegion img = (ImageRegion) node;

				// Make image path relative to cwd so that image files can be
				// found on different machines
				final URI imgURI = new File(img.getImageFile().getAbsolutePath()).toURI();
				final File relativeImageFile = new File(baseURI.relativize(imgURI).getPath());

				visitor.visitImageRegion(img.getBoundsInParent().getMinX(), img.getBoundsInParent().getMinY(),
						relativeImageFile, img.getAllTags());
			}
			break;
			case RECTANGLE: {
				final RectangleRegion rec = (RectangleRegion) node;
				visitor.visitRectangleRegion(rec.getBoundsInParent().getMinX(), rec.getBoundsInParent().getMinY(),
						rec.getWidth(), rec.getHeight(), TargetEditorController.getColorName((Color) rec.getFill()),
						rec.getAllTags());
			}
			break;
			case ELLIPSE: {
				final EllipseRegion ell = (EllipseRegion) node;
				final double absoluteCenterX = ell.getBoundsInParent().getMinX() + ell.getRadiusX();
				final double absoluteCenterY = ell.getBoundsInParent().getMinY() + ell.getRadiusY();
				visitor.visitEllipse(absoluteCenterX, absoluteCenterY, ell.getRadiusX(), ell.getRadiusY(),
						TargetEditorController.getColorName((Color) ell.getFill()), ell.getAllTags());
			}
			break;
			case POLYGON: {
				final PolygonRegion pol = (PolygonRegion) node;

				final Double[] points = new Double[pol.getPoints().size()];

				for (int i = 0; i < pol.getPoints().size(); i += 2) {
					final Point2D p = pol.localToParent(pol.getPoints().get(i), pol.getPoints().get(i + 1));

					points[i] = p.getX();
					points[i + 1] = p.getY();
				}

				visitor.visitPolygonRegion(points, TargetEditorController.getColorName((Color) pol.getFill()),
						pol.getAllTags());
			}
			break;
			}
		}

		visitor.visitEnd(targetTags);
	}

	public static Optional<TargetComponents> loadTarget(final File targetFile) {
		return loadTarget(targetFile, true);
	}

	// Used for loading targets from resource files for modular exercises
	public static Optional<TargetComponents> loadTarget(final InputStream targetStream, final ClassLoader loader) {
		return loadTarget(targetStream, true, loader);
	}

	public static Optional<TargetComponents> loadTarget(final File targetFile, boolean playAnimations) {
		if (!targetFile.getName().endsWith("target")) {
			logger.error("Unknown target file type.");
			return Optional.empty();
		}

		try {
			return Optional.of(buildTarget(TargetDefinitions.load(targetFile.toPath()), ResourceResolver.files(),
					playAnimations));
		} catch (TargetFormatException | IOException e) {
			logger.error("Can't load target: {}", e.getMessage());
			return Optional.empty();
		}
	}

	// Used for loading targets from resource files for modular exercises
	public static Optional<TargetComponents> loadTarget(final InputStream targetStream, boolean playAnimations,
			final ClassLoader loader) {
		final ResourceResolver resolver = ResourceResolver.classLoader(loader);

		try (InputStream in = targetStream) {
			return Optional.of(buildTarget(TargetDefinitions.load(in, resolver), resolver, playAnimations));
		} catch (TargetFormatException | IOException e) {
			logger.error("Can't load target from an exercise: {}", e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Builds the JavaFX nodes for a target: one v1 region node per model region, in order, with
	 * the fill, tags, visibility and opacity the target file asks for.
	 *
	 * @param resolver
	 *            opens the target's images
	 * @param playAnimations
	 *            <tt>true</tt> to play each animated image once as the target appears
	 */
	public static TargetComponents buildTarget(TargetDefinition definition, ResourceResolver resolver,
			boolean playAnimations) throws IOException {
		final Group targetGroup = new Group();

		for (final Region region : definition.regions()) {
			final Node node = buildRegion(region, resolver, playAnimations);
			((TargetRegion) node).setTags(region.tags());

			if (!region.isVisibleByDefault()) node.setVisible(false);

			if (!(region instanceof com.shootoff.targets.model.ImageRegion)) {
				if (region.tag(Region.TAG_OPACITY).isPresent()) {
					node.setOpacity(Double.parseDouble(region.tag(Region.TAG_OPACITY).get()));
				} else {
					node.setOpacity(DEFAULT_OPACITY);
				}
			}

			targetGroup.getChildren().add(node);
		}

		return new TargetComponents(definition, targetGroup);
	}

	private static Node buildRegion(Region region, ResourceResolver resolver, boolean playAnimations)
			throws IOException {
		// Shape.setFill, not the region's own setFill(Color), which defers to the FX thread
		return switch (region) {
		case com.shootoff.targets.model.EllipseRegion e -> {
			final Shape ellipse = new EllipseRegion(e.centerX(), e.centerY(), e.radiusX(), e.radiusY());
			ellipse.setFill(TargetEditorController.createColor(e.fill()));
			yield ellipse;
		}
		case com.shootoff.targets.model.RectangleRegion r -> {
			final Shape rectangle = new RectangleRegion(r.x(), r.y(), r.width(), r.height());
			rectangle.setFill(TargetEditorController.createColor(r.fill()));
			yield rectangle;
		}
		case com.shootoff.targets.model.PolygonRegion p -> {
			final double[] points = new double[p.points().size() * 2];
			for (int i = 0; i < p.points().size(); i++) {
				points[2 * i] = p.points().get(i).getX();
				points[2 * i + 1] = p.points().get(i).getY();
			}

			final Shape polygon = new PolygonRegion(points);
			polygon.setFill(TargetEditorController.createColor(p.fill()));
			yield polygon;
		}
		case com.shootoff.targets.model.ImageRegion i -> buildImage(i, resolver, playAnimations);
		};
	}

	private static ImageRegion buildImage(com.shootoff.targets.model.ImageRegion region, ResourceResolver resolver,
			boolean playAnimations) throws IOException {
		final String path = region.imagePath();
		final File savedFile = new File(path);

		final File imageFile;
		if (savedFile.isAbsolute() || '@' == path.charAt(0)) {
			imageFile = savedFile;
		} else {
			imageFile = new File(System.getProperty("shootoff.home") + File.separator + path);
		}

		final ImageRegion imageRegion;
		try (InputStream imageStream = open(resolver, path)) {
			imageRegion = new ImageRegion(region.x(), region.y(), imageFile, imageStream);
		}

		final int firstDot = imageFile.getName().indexOf('.') + 1;
		if (imageFile.getName().substring(firstDot).endsWith("gif")) {
			try (InputStream gifStream = open(resolver, path)) {
				final GifAnimation gif = new GifAnimation(imageRegion, gifStream);
				imageRegion.setImage(gif.getFirstFrame());
				if (gif.getFrameCount() > 1) imageRegion.setAnimation(gif);
			} catch (final IOException e) {
				logger.error("Error reading animation from XML target", e);
			}

			if (imageRegion.getAnimation().isPresent() && playAnimations) {
				final SpriteAnimation animation = imageRegion.getAnimation().get();
				animation.setCycleCount(1);

				animation.setOnFinished((e) -> {
					animation.reset();
					animation.setOnFinished(null);
				});

				animation.play();
			}
		}

		return imageRegion;
	}

	private static InputStream open(ResourceResolver resolver, String path) throws IOException {
		return resolver.open(path).orElseThrow(() -> new FileNotFoundException(path));
	}
}
```

Then delete the old reader:

```bash
git rm javafx-app/src/main/java/com/shootoff/targets/io/XMLTargetReader.java javafx-app/src/main/java/com/shootoff/targets/io/TargetReader.java
command grep -rn "XMLTargetReader\|TargetReader\b" javafx-app/src core/src
```

Expected from the `grep`: nothing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.targets.*' --tests 'com.shootoff.gui.*' --tests 'com.shootoff.plugins.*' --tests 'com.shootoff.courses.*' --tests 'com.shootoff.session.*' --console=plain`
Expected: all pass, including:
- `TestTargetIO`: 3 old and 3 new tests
- both `TestHitParity` tests, which now compare the model with nodes built from the model
- `TestTarget`, `TestTargetCommands` and `TestCanvasManager`, whose targets now load through the model
- `TestSessionCanvasManagerReplay.missingTargetFileIsSkipped`, which now takes the stand-in path because a missing target loads as empty

- [ ] **Step 5: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 3 (**277**).

```bash
git add javafx-app/src/main/java/com/shootoff/targets/io/TargetIO.java javafx-app/src/test/java/com/shootoff/targets/io/TestTargetIO.java
git status --short | command grep -v '^D  '
git commit -m "Build JavaFX target nodes from the core target model and delete XMLTargetReader"
git log -1 --format=%B
```

The filtered status must show only ` M shootoff.properties`.

---
### Task 5: `TargetView` keeps its state in the canvas's `TargetSet`

A target's position, scale and visibility now live in a `PlacedTarget`. Each canvas owns a `TargetSet`. A `TargetView` sits in a private set until a canvas adds it, joins the canvas's set, and returns to a private set when removed, keeping its placement throughout. Every mutation (`setPosition`, `setDimensions`, `setVisible`, drag, resize, arrow keys) goes through the set. The JavaFX group follows the set's change events: layout from the position, and a `Scale` transform from the scale about the model's pivot (ruling 9).

Hits still come from the old JavaFX `isHit` in this task (it now reads the group that the model positions); Task 6 switches them. Session recording is unchanged apart from reading positions and sizes from the model.

**Files:**
- Modify (rewrite): `javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java`
- Modify:
  - `javafx-app/src/main/java/com/shootoff/gui/targets/MirroredTarget.java` (constructor)
  - `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` (`TargetSet`, `addTarget`/`removeTarget`)
  - `javafx-app/src/main/java/com/shootoff/gui/MirroredCanvasManager.java` (three `addTarget` variants)
  - `javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java` (`createCalibrationTarget`)
  - `javafx-app/src/main/java/com/shootoff/gui/SessionCanvasManager.java` (`addTarget`)
  - `javafx-app/src/main/java/com/shootoff/courses/io/XMLCourseReader.java` (one constructor call)
  - `javafx-app/src/main/java/com/shootoff/gui/FxGeometry.java` (`toBounds`)
- Modify (tests; constructor calls only): `gui/MockCanvasManager.java`, `gui/targets/TestTarget.java`, `gui/targets/TestTargetCommands.java`, `gui/TestCanvasManagerSessionRecording.java`, `session/TestSessionRecorder.java`, `session/io/TestSessionIO.java`, `courses/io/TestCourseIO.java`, `plugins/TestDuelingTree.java`, `plugins/TestShootForScore.java`, `plugins/TestISSFStandardPistol.java`, `plugins/TestRandomShoot.java`, all under `javafx-app/src/test/java/com/shootoff/`
- Create (test): `javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetViewPlacement.java`

**Interfaces:**
- Consumes: `TargetComponents` with `getDefinition()`, `getTargetFile()` and `empty(File)`, and `TargetIO.buildTarget` (Task 4); `TargetSet`, `PlacedTarget`, `Placement`, `TargetSetListener` and the `RectangleRegion` model record (Tasks 1–2).
- Produces:
  - `TargetView(TargetComponents, CanvasManager parent, boolean userDeletable)` and `TargetView(TargetComponents, List<Target> targets)` (viewer and tests). The old `(File, Group, Map, CanvasManager, boolean)` and `(Group, Map, List<Target>)` constructors are gone.
  - `TargetView` methods:
    - model access: `getComponents()`, `getTargetSet()`, `getPlacedTarget()`, `getPlacement()`
    - `final setPlacement(Placement)`: no mirroring, no session events
    - canvas membership: `joinTargetSet(TargetSet)` (keeps the placement), `leaveTargetSet()`
  - `MirroredTarget(TargetComponents, Configuration, CanvasManager parent, boolean userDeletable)`
  - `CanvasManager`:
    - `getTargetSet() → TargetSet`, in the same order as `getTargets()`
    - `addTarget(TargetComponents, boolean userDeletable) → Target` replaces `addTarget(File, Group, Map, boolean)`
    - `addTarget(Target)` joins the view to the set; `removeTarget(Target)` records, removes, then leaves the set
  - `MirroredCanvasManager.mirrorAddTarget(TargetComponents, boolean)` replaces the `(File, Group, Map, boolean)` form
  - `FxGeometry.toBounds(Rect) → javafx.geometry.Bounds`
  - v1 `Target` methods backed by the model:
    - `getPosition()`, `getDimension()`, `getBoundsInParent()`, `getScaleX()`/`getScaleY()` (the placement's scale), `parentToLocal`, `isVisible()`
    - `getRegions()`: the region nodes in model order; anchors aren't included (ruling 7)
    - signatures unchanged

- [ ] **Step 1: Write the failing tests**

Create `javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetViewPlacement.java`:

```java
package com.shootoff.gui.targets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import java.io.File;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.TargetSet;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

public class TestTargetViewPlacement {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private MockCanvasManager canvas;
	private TargetView ipsc;

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		canvas = new MockCanvasManager(new Configuration(new String[0]));
		ipsc = new TargetView(TargetIO.loadTarget(new File("targets/IPSC.target"), false).get(), canvas, true);
	}

	// A mouse event at a canvas point; JavaFX converts it to the target group's coordinates
	private void fireMouse(EventType<MouseEvent> type, double x, double y) {
		Event.fireEvent(ipsc.getTargetGroup(), new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1, false, false,
				false, false, true, false, false, false, false, false, null));
	}

	@Test
	public void placementLivesInTheTargetSet() {
		canvas.addTarget(ipsc);
		final TargetSet set = canvas.getTargetSet();
		assertSame(set, ipsc.getTargetSet());
		assertEquals(0, set.indexOf(ipsc.getPlacedTarget().getId()));

		ipsc.setPosition(12, 34);
		assertEquals(new Point(12, 34), ipsc.getPlacedTarget().getPosition());
		assertEquals(12, ipsc.getTargetGroup().getLayoutX(), 0);

		set.move(ipsc.getPlacedTarget().getId(), 56, 78);
		assertEquals(56, ipsc.getTargetGroup().getLayoutX(), 0);
		assertEquals(78, ipsc.getPosition().getY(), 0);

		ipsc.setVisible(false);
		assertFalse(ipsc.getPlacedTarget().isVisible());
		assertFalse(ipsc.getTargetGroup().isVisible());
	}

	@Test
	public void setDimensionsScalesAboutTheCenter() {
		final Rect before = ipsc.getPlacedTarget().getBounds();

		ipsc.setDimensions(before.getWidth() * 2, before.getHeight());

		final Rect after = ipsc.getPlacedTarget().getBounds();
		assertEquals(before.getWidth() * 2, after.getWidth(), 0.001);
		assertEquals(before.getHeight(), after.getHeight(), 0.001);
		assertEquals(before.getMinX() + before.getWidth() / 2, after.getMinX() + after.getWidth() / 2, 0.001);

		// The JavaFX nodes are drawn where the model says the target is
		final Bounds drawn = ipsc.getTargetGroup().getBoundsInParent();
		assertEquals(after.getMinX(), drawn.getMinX(), 0.01);
		assertEquals(after.getWidth(), drawn.getWidth(), 0.01);
	}

	@Test
	public void joiningAndLeavingACanvasKeepsThePlacement() {
		ipsc.setPosition(10, 20);
		ipsc.setDimensions(100, 150);

		canvas.addTarget(ipsc);
		assertEquals(10, ipsc.getPosition().getX(), 0);
		assertEquals(100, ipsc.getDimension().getWidth(), 0.001);

		canvas.removeTarget(ipsc);
		assertNotSame(canvas.getTargetSet(), ipsc.getTargetSet());
		assertEquals(-1, canvas.getTargetSet().indexOf(ipsc.getPlacedTarget().getId()));
		assertEquals(20, ipsc.getPosition().getY(), 0);
		assertEquals(150, ipsc.getDimension().getHeight(), 0.001);
	}

	@Test
	public void draggingMovesTheTargetWithTheMouse() {
		canvas.addTarget(ipsc);
		ipsc.setDimensions(ipsc.getDimension().getWidth() * 2, ipsc.getDimension().getHeight() * 2);
		final Placement start = ipsc.getPlacement();
		final Rect bounds = ipsc.getPlacedTarget().getBounds();
		final double x = bounds.getMinX() + bounds.getWidth() / 2;
		final double y = bounds.getMinY() + bounds.getHeight() / 2;

		fireMouse(MouseEvent.MOUSE_MOVED, x, y);
		fireMouse(MouseEvent.MOUSE_PRESSED, x, y);
		fireMouse(MouseEvent.MOUSE_DRAGGED, x + 10, y + 5);

		assertEquals(start.x() + 10, ipsc.getPosition().getX(), 0.001);
		assertEquals(start.y() + 5, ipsc.getPosition().getY(), 0.001);
	}

	@Test
	public void draggingTheRightEdgeWidensTheTargetAndKeepsItsLeftEdge() {
		canvas.addTarget(ipsc);
		final Rect before = ipsc.getPlacedTarget().getBounds();
		final double y = before.getMinY() + before.getHeight() / 2;
		final double edge = before.getMaxX() - 1; // inside the 5-pixel resize margin

		fireMouse(MouseEvent.MOUSE_MOVED, edge, y);
		fireMouse(MouseEvent.MOUSE_PRESSED, edge, y);
		fireMouse(MouseEvent.MOUSE_DRAGGED, edge + 20, y);

		final Rect after = ipsc.getPlacedTarget().getBounds();
		assertEquals(before.getMinX(), after.getMinX(), 0.01);
		assertEquals(before.getWidth() + 19, after.getWidth(), 0.01);
		assertEquals(before.getHeight(), after.getHeight(), 0.01);
	}
}
```

The drag tests fire events at canvas points. JavaFX turns each point into the group's local coordinates, as it does for a real mouse. So dragging the center moves the target by the mouse's movement, and dragging the right edge 19 px past it widens the target by 19 px about a moving center, which leaves the left edge where it was. The old `TargetView` behaves the same way; these tests pin the rewrite to it.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :javafx-app:compileTestJava --console=plain`
Expected: FAIL with `cannot find symbol` for `getTargetSet()`, `getPlacedTarget()` and `getPlacement()`.

- [ ] **Step 3: Rewrite `TargetView`**

Replace everything after the license header of `javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java` with the code below. Several parts are unchanged from today and are marked by comments:
- `isHit`, whose body is the current body verbatim
- `parseCommandTag`, `getTargetRegionByName`, `animate`, `reverseAnimation`
- the selection code
- `mousePressed`, `mouseMoved`, `mouseReleased`

```java
package com.shootoff.gui.targets;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.config.Configuration;
import com.shootoff.geom.Point;
import com.shootoff.geom.Rect;
import com.shootoff.geom.Size;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.FxGeometry;
import com.shootoff.targets.Hit;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.RectangleRegion;
import com.shootoff.targets.RegionType;
import com.shootoff.targets.Target;
import com.shootoff.targets.TargetRegion;
import com.shootoff.targets.animation.SpriteAnimation;
import com.shootoff.targets.io.TargetIO.TargetComponents;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.Placement;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetSet;
import com.shootoff.targets.model.TargetSetListener;

import javafx.animation.Animation.Status;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.transform.Scale;

/**
 * Shows a target and lets the user select, move and resize it. The target's position, scale and
 * visibility live in a {@link PlacedTarget} owned by a {@link TargetSet}: its canvas's set once
 * {@link CanvasManager#addTarget(Target)} adds it, a private one before that and after it is
 * removed. Every change goes through the set, and the JavaFX nodes follow the set's change
 * events. The scale is drawn with a {@link Scale} transform about the model's pivot; the group's
 * own scaleX/scaleY stay 1.
 *
 * @author phrack
 */
public class TargetView implements Target {
	private static final Logger logger = LoggerFactory.getLogger(TargetView.class);

	private static final double ANCHOR_WIDTH = 10;
	private static final double ANCHOR_HEIGHT = ANCHOR_WIDTH;

	protected static final int MOVEMENT_DELTA = 1;
	protected static final int SCALE_DELTA = 1;
	private static final int RESIZE_MARGIN = 5;

	private final TargetComponents components;
	private final Group targetGroup;
	private final List<Node> regionNodes;
	private final Map<String, String> targetTags;
	private final Scale scale = new Scale(1, 1, 0, 0);
	private final Set<Node> resizeAnchors = new HashSet<>();
	private final Optional<Configuration> config;
	private final Optional<CanvasManager> parent;
	private final Optional<List<Target>> targets;
	private final boolean userDeletable;
	private final String cameraName;
	private final TargetSetListener placementListener = new TargetSetListener() {
		@Override
		public void targetChanged(PlacedTarget target) {
			if (target.getId().equals(membership.placed().getId())) applyPlacement(target);
		}
	};

	// The set this target is in and its entry there, swapped together when it joins or leaves a
	// canvas
	private volatile Membership membership;
	private boolean keepInBounds = false;
	private boolean isSelected = false;
	private boolean move;
	private boolean resize;
	private boolean top;
	private boolean bottom;
	private boolean left;
	private boolean right;
	private double x;
	private double y;

	private TargetSelectionListener selectionListener;

	private record Membership(TargetSet set, PlacedTarget placed) {}

	public TargetView(TargetComponents components, CanvasManager parent, boolean userDeletable) {
		this(components, Optional.of(parent), Optional.empty(), userDeletable);

		targetGroup.setOnMouseClicked((event) -> {
			// Skip target selection if click to shoot is being used
			if (config.isPresent() && config.get().inDebugMode() && (event.isShiftDown() || event.isControlDown()))
				return;

			parent.toggleTargetSelection(Optional.of(this));
			targetGroup.requestFocus();
			event.consume();
		});
	}

	// Used by the session viewer, target pane, and for testing
	public TargetView(TargetComponents components, List<Target> targets) {
		this(components, Optional.empty(), Optional.of(targets), false);
	}

	private TargetView(TargetComponents components, Optional<CanvasManager> parent, Optional<List<Target>> targets,
			boolean userDeletable) {
		this.components = components;
		targetGroup = components.getTargetGroup();

		final int regionCount = components.getDefinition().regions().size();
		if (targetGroup.getChildren().size() < regionCount) {
			throw new IllegalArgumentException("The target group has fewer nodes than the target has regions");
		}
		regionNodes = List.copyOf(targetGroup.getChildren().subList(0, regionCount));

		targetTags = components.getTargetTags();
		config = parent.isPresent() ? Optional.ofNullable(Configuration.getConfig()) : Optional.empty();
		this.parent = parent;
		this.targets = targets;
		this.userDeletable = userDeletable;
		cameraName = parent.map(CanvasManager::getCameraName).orElse(null);

		// The model's scale is drawn by this transform. setAll: a group shared with an earlier view
		// (MirroredCanvasManager) keeps one scale.
		targetGroup.setScaleX(1);
		targetGroup.setScaleY(1);
		targetGroup.getTransforms().setAll(scale);

		final TargetSet privateSet = new TargetSet();
		membership = new Membership(privateSet, privateSet.add(components.getDefinition()));
		privateSet.addListener(placementListener);
		applyPlacement(membership.placed());

		mousePressed();
		mouseDragged();
		mouseMoved();
		mouseReleased();
		keyPressed();
	}

	public boolean isUserDeletable() {
		return userDeletable;
	}

	@Override
	public File getTargetFile() {
		return components.getTargetFile();
	}

	public Group getTargetGroup() {
		return targetGroup;
	}

	public TargetComponents getComponents() {
		return components;
	}

	/**
	 * @return the set this target is in: its canvas's set once added to a canvas, otherwise a
	 *         private one
	 */
	public TargetSet getTargetSet() {
		return membership.set();
	}

	public PlacedTarget getPlacedTarget() {
		return membership.placed();
	}

	public Placement getPlacement() {
		return membership.placed().getPlacement();
	}

	/**
	 * Sets the position, scale and visibility at once, without mirroring or session events (used
	 * to copy one view's placement to another).
	 */
	public final void setPlacement(Placement placement) {
		final Membership m = membership;
		m.set().place(m.placed().getId(), placement);
	}

	/**
	 * Moves this target's model into <tt>set</tt>, keeping its placement. CanvasManager calls this
	 * when it adds the target.
	 */
	public void joinTargetSet(TargetSet set) {
		final Membership old = membership;
		if (old.set() == set) return;

		final PlacedTarget placed = set.add(components.getDefinition(), old.placed().getPlacement());
		old.set().removeListener(placementListener);
		old.set().remove(old.placed().getId());
		membership = new Membership(set, placed);
		set.addListener(placementListener);
		applyPlacement(placed);
	}

	/**
	 * Moves this target's model back into a private set, keeping its placement. CanvasManager
	 * calls this when it removes the target.
	 */
	public void leaveTargetSet() {
		joinTargetSet(new TargetSet());
	}

	// The JavaFX side of a placement: layout from the position, the Scale transform from the scale
	// about the model's pivot, and unresizable regions scaled back to their own size
	private void applyPlacement(PlacedTarget target) {
		final Placement p = target.getPlacement();
		final Point pivot = target.getPivot();

		targetGroup.setLayoutX(p.x());
		targetGroup.setLayoutY(p.y());
		scale.setPivotX(pivot.getX());
		scale.setPivotY(pivot.getY());
		scale.setX(p.scaleX());
		scale.setY(p.scaleY());
		targetGroup.setVisible(p.visible());

		final List<Region> regions = target.getDefinition().regions();
		for (int i = 0; i < regions.size(); i++) {
			if (!regions.get(i).isResizable()) {
				regionNodes.get(i).setScaleX(1 / p.scaleX());
				regionNodes.get(i).setScaleY(1 / p.scaleY());
			}
		}
	}

	// Only the canvas that records session events records this target, and only once the
	// target is registered on it (index -1 means it isn't yet)
	private boolean shouldRecordSessionEvents() {
		return config.isPresent() && config.get().getSessionRecorder().isPresent() && parent.isPresent()
				&& parent.get().recordsSessionEvents() && getTargetIndex() >= 0;
	}

	// For resizes applied through a mirror (see MirroredTarget.mirrorSetDimensions), whose own
	// handlers ran on the non-recording canvas
	protected void recordResize(double newWidth, double newHeight) {
		if (shouldRecordSessionEvents()) {
			config.get().getSessionRecorder().get().recordTargetResized(cameraName, this, newWidth, newHeight);
		}
	}

	private void recordMoved() {
		if (shouldRecordSessionEvents()) {
			final Placement p = getPlacement();
			config.get().getSessionRecorder().get().recordTargetMoved(cameraName, this, (int) p.x(), (int) p.y());
		}
	}

	private void recordResized() {
		if (shouldRecordSessionEvents()) {
			final Size size = membership.placed().getSize();
			config.get().getSessionRecorder().get().recordTargetResized(cameraName, this, size.getWidth(),
					size.getHeight());
		}
	}

	@Override
	public int getTargetIndex() {
		if (parent.isPresent())
			return parent.get().getTargets().indexOf(this);
		else
			return -1;
	}

	@Override
	public void fillParent() {
		if (parent.isPresent()) {
			final Bounds b = parent.get().getCanvasGroup().getBoundsInParent();
			setDimensions(b.getWidth(), b.getHeight());
			final Point p = membership.placed().localToParent(0, 0);
			setPosition(p.getX() * -1, p.getY() * -1);
		}
	}

	// Drawn only: children added here aren't regions of the target's model
	@Override
	public void addTargetChild(Node child) {
		getTargetGroup().getChildren().add(child);
	}

	@Override
	public void removeTargetChild(Node child) {
		getTargetGroup().getChildren().remove(child);
	}

	@Override
	public List<TargetRegion> getRegions() {
		final List<TargetRegion> regions = new ArrayList<>();

		for (final Node n : regionNodes) {
			regions.add((TargetRegion) n);
		}

		return regions;
	}

	@Override
	public boolean hasRegion(TargetRegion region) {
		return regionNodes.contains(region);
	}

	@Override
	public void setVisible(boolean isVisible) {
		final Membership m = membership;
		m.set().setVisible(m.placed().getId(), isVisible);
	}

	@Override
	public boolean isVisible() {
		return getPlacement().visible();
	}

	@Override
	public void setPosition(double x, double y) {
		final Membership m = membership;
		m.set().move(m.placed().getId(), x, y);
		recordMoved();
	}

	@Override
	public Point2D getPosition() {
		final Placement p = getPlacement();
		return new Point2D(p.x(), p.y());
	}

	@Override
	public void setDimensions(double newWidth, double newHeight) {
		final Membership m = membership;
		m.set().resize(m.placed().getId(), newWidth, newHeight);
	}

	@Override
	public Dimension2D getDimension() {
		final Size size = membership.placed().getSize();
		return new Dimension2D(size.getWidth(), size.getHeight());
	}

	@Override
	public double getScaleX() {
		return getPlacement().scaleX();
	}

	@Override
	public double getScaleY() {
		return getPlacement().scaleY();
	}

	@Override
	public void scale(double widthFactor, double heightFactor) {
		final double newWidth = getDimension().getWidth() * widthFactor;
		final double widthDelta = newWidth - getDimension().getWidth();
		final double newX = getBoundsInParent().getMinX() * widthFactor;
		final double deltaX = newX - getBoundsInParent().getMinX() + (widthDelta / 2);

		final double newHeight = getDimension().getHeight() * heightFactor;
		final double heightDelta = newHeight - getDimension().getHeight();
		final double newY = getBoundsInParent().getMinY() * heightFactor;
		final double deltaY = newY - getBoundsInParent().getMinY() + (heightDelta / 2);

		setPosition(getPosition().getX() + deltaX, getPosition().getY() + deltaY);

		setDimensions(newWidth, newHeight);
	}

	@Override
	public Bounds getBoundsInParent() {
		return FxGeometry.toBounds(membership.placed().getBounds());
	}

	@Override
	public Point2D parentToLocal(double x, double y) {
		final Point p = membership.placed().parentToLocal(x, y);
		return new Point2D(p.getX(), p.getY());
	}

	@Override
	public void setClip(Rectangle clip) {
		getTargetGroup().setClip(clip);
	}

	/**
	 * Sets whether or not the target should stay in the bounds of its parent.
	 * 
	 * @param keepInBounds
	 *            <tt>true</tt> if the target should stay in bounds,
	 *            <tt>false</tt> otherwise.
	 */
	public void setKeepInBounds(boolean keepInBounds) {
		this.keepInBounds = keepInBounds;
	}

	public boolean getKeepInBounds() {
		return keepInBounds;
	}

	// Unchanged from here to toggleSelected
	public static void parseCommandTag(TargetRegion region, CommandProcessor commandProcessor) {
		if (!region.tagExists("command")) return;

		final String commandsSource = region.getTag("command");
		final List<String> commands = Arrays.asList(commandsSource.split(";"));

		for (final String command : commands) {
			final int openParen = command.indexOf('(');
			String commandName;
			List<String> args;

			if (openParen > 0) {
				commandName = command.substring(0, openParen);
				args = Arrays.asList(command.substring(openParen + 1, command.indexOf(')')).split(","));
			} else {
				commandName = command;
				args = new ArrayList<>();
			}

			commandProcessor.process(commands, commandName, args);
		}
	}

	public static Optional<TargetRegion> getTargetRegionByName(List<Target> targets, TargetRegion region, String name) {
		for (final Target target : targets) {
			if (target.hasRegion(region)) {
				for (final TargetRegion r : target.getRegions()) {
					if (r.tagExists("name") && r.getTag("name").equals(name)) return Optional.of(r);
				}
			}
		}

		return Optional.empty();
	}

	@Override
	public void animate(TargetRegion region, List<String> args) {
		ImageRegion imageRegion;

		boolean resetAfterAnimation = false;

		if (args.size() == 0) {
			imageRegion = (ImageRegion) region;
		} else if (args.get(0).equals("true")) {
			imageRegion = (ImageRegion) region;
			resetAfterAnimation = true;
		} else {
			Optional<TargetRegion> r;

			if (targets.isPresent()) {
				r = getTargetRegionByName(targets.get(), region, args.get(0));
			} else if (parent.isPresent()) {
				r = getTargetRegionByName(parent.get().getTargets(), region, args.get(0));
			} else {
				r = Optional.empty();
			}

			if (r.isPresent()) {
				imageRegion = (ImageRegion) r.get();
			} else {
				logger.error("Request to animate region named {}, but it doesn't exist.", args.get(0));
				return;
			}
		}

		// Don't repeat animations for fallen targets
		if (!imageRegion.onFirstFrame()) return;

		if (imageRegion.getAnimation().isPresent()) {
			final SpriteAnimation animation = imageRegion.getAnimation().get();
			animation.play();

			if (resetAfterAnimation) {
				animation.setOnFinished((e) -> {
					animation.reset();
					animation.setOnFinished(null);
				});
			}
		} else {
			logger.error("Request to animate region, but region does not contain an animation.");
		}
	}

	@Override
	public void reverseAnimation(TargetRegion region) {
		if (region.getType() != RegionType.IMAGE) {
			logger.error("A reversal was requested on a non-image region.");
			return;
		}

		final ImageRegion imageRegion = (ImageRegion) region;
		if (imageRegion.getAnimation().isPresent()) {
			final SpriteAnimation animation = imageRegion.getAnimation().get();

			if (animation.getStatus() == Status.RUNNING) {
				animation.setOnFinished((e) -> {
					animation.reverse();
					animation.setOnFinished(null);
				});
			} else {
				animation.reverse();
			}
		} else {
			logger.error("A reversal was requested on an image region that isn't animated.");
		}
	}

	public void toggleSelected() {
		isSelected = !isSelected;

		final Color stroke = isSelected ? TargetRegion.SELECTED_STROKE_COLOR : TargetRegion.UNSELECTED_STROKE_COLOR;

		for (final Node node : getTargetGroup().getChildren()) {
			if (!(node instanceof TargetRegion)) continue;

			final TargetRegion region = (TargetRegion) node;
			if (region.getType() != RegionType.IMAGE) {
				((Shape) region).setStroke(stroke);
			}
		}

		if (isSelected) {
			addResizeAnchors();
		} else {
			getTargetGroup().getChildren().removeAll(resizeAnchors);
			resizeAnchors.clear();
		}

		if (selectionListener != null) selectionListener.targetSelected(this, isSelected);
	}

	@Override
	public void setTargetSelectionListener(TargetSelectionListener selectionListener) {
		this.selectionListener = selectionListener;
	}

	public interface TargetSelectionListener {
		void targetSelected(Target target, boolean isSelected);
	}

	public boolean isSelected() {
		return isSelected;
	}

	private void addResizeAnchors() {
		final Bounds localBounds = getTargetGroup().getBoundsInLocal();
		final double horizontalMiddle = localBounds.getMinX() + (localBounds.getWidth() / 2) - (ANCHOR_WIDTH / 2);
		final double verticleMiddle = localBounds.getMinY() + (localBounds.getHeight() / 2) - (ANCHOR_HEIGHT / 2);

		// Top left
		addAnchor(localBounds.getMinX(), localBounds.getMinY());
		// Top middle
		addAnchor(horizontalMiddle, localBounds.getMinY());
		// Top right
		addAnchor(localBounds.getMaxX() - ANCHOR_WIDTH, localBounds.getMinY());
		// Middle left
		addAnchor(localBounds.getMinX(), verticleMiddle);
		// Middle right
		addAnchor(localBounds.getMaxX() - ANCHOR_WIDTH, verticleMiddle);
		// Bottom left
		addAnchor(localBounds.getMinX(), localBounds.getMaxY() - ANCHOR_HEIGHT);
		// Bottom middle
		addAnchor(horizontalMiddle, localBounds.getMaxY() - ANCHOR_HEIGHT);
		// Bottom right
		addAnchor(localBounds.getMaxX() - ANCHOR_WIDTH, localBounds.getMaxY() - ANCHOR_HEIGHT);
	}

	private RectangleRegion addAnchor(final double x, final double y) {
		final RectangleRegion anchor = new RectangleRegion(x, y, ANCHOR_WIDTH, ANCHOR_HEIGHT);

		// Make the anchor regions unshootable and unresizable
		final Map<String, String> regionTags = ((TargetRegion) anchor).getAllTags();
		regionTags.put(TargetView.TAG_IGNORE_HIT, "true");
		regionTags.put(TargetView.TAG_RESIZABLE, "false");

		anchor.setFill(Color.GOLD);
		anchor.setStroke(Color.BLACK);

		getTargetGroup().getChildren().add(anchor);

		// Ensure anchors appear the intended visual size even if the target
		// has been scaled
		final Placement p = getPlacement();

		if (p.scaleX() != 1.0f) {
			final double scaledPercentChange = (ANCHOR_WIDTH / (ANCHOR_WIDTH * p.scaleX()));
			anchor.setScaleX(scaledPercentChange);
		}

		if (p.scaleY() != 1.0f) {
			final double scaledPercentChange = (ANCHOR_HEIGHT / (ANCHOR_HEIGHT * p.scaleY()));
			anchor.setScaleY(scaledPercentChange);
		}

		resizeAnchors.add(anchor);

		return anchor;
	}

	// Unchanged until Task 6: the JavaFX hit test, on the group the model now positions
	@Override
	public Optional<Hit> isHit(double x, double y) {
		if (targetGroup.getBoundsInParent().contains(x, y)) {
			// Target was hit, see if a specific region was hit
			for (int i = targetGroup.getChildren().size() - 1; i >= 0; i--) {
				final Node node = targetGroup.getChildren().get(i);

				if (!(node instanceof TargetRegion)) continue;

				final Bounds nodeBounds = targetGroup.getLocalToParentTransform().transform(node.getBoundsInParent());

				final int adjustedX = (int) (x - nodeBounds.getMinX());
				final int adjustedY = (int) (y - nodeBounds.getMinY());

				if (nodeBounds.contains(x, y)) {
					// If we hit an image region on a transparent pixel,
					// ignore it
					final TargetRegion region = (TargetRegion) node;

					// Ignore regions where ignoreHit tag is true
					if (region.tagExists(TargetView.TAG_IGNORE_HIT)
							&& Boolean.parseBoolean(region.getTag(TargetView.TAG_IGNORE_HIT)))
						continue;

					if (region.getType() == RegionType.IMAGE) {
						// The image you get from the image view is its
						// original size. We need to resize it if it has
						// changed size to accurately determine if a pixel
						// is transparent
						final Image currentImage = ((ImageRegion) region).getImage();

						if (adjustedX < 0 || adjustedY < 0) {
							logger.debug(
									"An adjusted pixel is negative: Adjusted ({}, {}), Original ({}, {}), "
											+ " nodeBounds.getMin ({}, {})",
									adjustedX, adjustedY, x, y, nodeBounds.getMaxX(),
									nodeBounds.getMinY());
							return Optional.empty();
						}

						if (Math.abs(currentImage.getWidth() - nodeBounds.getWidth()) > .0000001
								|| Math.abs(currentImage.getHeight() - nodeBounds.getHeight()) > .0000001) {

							final BufferedImage bufferedOriginal = SwingFXUtils.fromFXImage(currentImage, null);

							final java.awt.Image tmp = bufferedOriginal.getScaledInstance((int) nodeBounds.getWidth(),
									(int) nodeBounds.getHeight(), java.awt.Image.SCALE_SMOOTH);
							final BufferedImage bufferedResized = new BufferedImage((int) nodeBounds.getWidth(),
									(int) nodeBounds.getHeight(), BufferedImage.TYPE_INT_ARGB);

							final Graphics2D g2d = bufferedResized.createGraphics();
							g2d.drawImage(tmp, 0, 0, null);
							g2d.dispose();

							try {
								if (adjustedX >= bufferedResized.getWidth() || adjustedY >= bufferedResized.getHeight()
										|| bufferedResized.getRGB(adjustedX, adjustedY) >> 24 == 0) {
									continue;
								}
							} catch (final ArrayIndexOutOfBoundsException e) {
								final String message = String.format(
										"Index out of bounds while trying to find adjusted coordinate (%d, %d) "
												+ "from original (%.2f, %.2f) in adjusted BufferedImage for target %s "
												+ "with width = %d, height = %d",
										adjustedX, adjustedY, x, y, getTargetFile().getPath(),
										bufferedResized.getWidth(), bufferedResized.getHeight());
								logger.error(message, e);
								return Optional.empty();
							}
						} else {
							if (adjustedX >= currentImage.getWidth() || adjustedY >= currentImage.getHeight()
									|| currentImage.getPixelReader().getArgb(adjustedX, adjustedY) >> 24 == 0) {
								continue;
							}
						}
					} else {
						// The shot is in the bounding box but make sure it
						// is in the shape's
						// fill otherwise we can get a shot detected where
						// there isn't actually
						// a region showing
						final Point2D localCoords = targetGroup.parentToLocal(x, y);
						if (!node.contains(localCoords)) continue;
					}

					return Optional.of(new Hit(this, (TargetRegion) node, adjustedX, adjustedY));
				}
			}
		}

		return Optional.empty();
	}

	// Unchanged
	private void mousePressed() {
		targetGroup.setOnMousePressed((event) -> {
			if (!isInResizeZone(event)) {
				move = true;

				return;
			}

			resize = true;
			top = isTopZone(event);
			bottom = isBottomZone(event);
			left = isLeftZone(event);
			right = isRightZone(event);
		});
	}

	// The old handler's arithmetic, step for step, on the model's placement and bounds: the
	// candidate placement is checked against the display, then applied through the set once
	private void mouseDragged() {
		targetGroup.setOnMouseDragged((event) -> {

			if (!resize && !move) return;

			final Membership m = membership;
			final PlacedTarget placed = m.placed();
			final Placement start = placed.getPlacement();

			if (move) {
				if (config.isPresent() && config.get().inDebugMode() && (event.isControlDown() || event.isShiftDown()))
					return;

				final double deltaX = event.getX() - x;
				final double deltaY = event.getY() - y;
				final Rect bounds = placed.getBounds();
				double newX = start.x();
				double newY = start.y();

				if (!keepInBounds || (bounds.getMinX() + deltaX >= 0
						&& bounds.getMaxX() + deltaX <= config.get().getDisplayWidth())) {

					newX = start.x() + (deltaX * start.scaleX());
				}

				if (!keepInBounds || (bounds.getMinY() + deltaY >= 0
						&& bounds.getMaxY() + deltaY <= config.get().getDisplayHeight())) {

					newY = start.y() + (deltaY * start.scaleY());
				}

				m.set().move(placed.getId(), newX, newY);
				recordMoved();

				return;
			}

			final boolean fixedAspectRatioResize = (top || bottom) && (left || right) && event.isControlDown();
			double aspectScaleDelta = 0.0;
			final Rect local = placed.getLocalBounds();
			Placement current = start;

			if (left || right) {
				// The gap between the mouse and nearest target edge
				final double gap;

				if (right) {
					gap = (event.getX() - local.getMaxX()) * current.scaleX();
				} else {
					gap = (event.getX() - local.getMinX()) * current.scaleX();
				}

				final Rect bounds = placed.boundsAt(current);
				final double currentWidth = bounds.getWidth();
				final double newWidth = currentWidth + gap;

				double scaleDelta = (newWidth - currentWidth) / currentWidth;

				if (fixedAspectRatioResize) aspectScaleDelta = scaleDelta;

				final double currentOriginX = bounds.getMinX();
				final double newOriginX;

				if (right) {
					scaleDelta *= -1.0;
					newOriginX = currentOriginX - ((newWidth - currentWidth) / 2);
				} else {
					newOriginX = currentOriginX + ((newWidth - currentWidth) / 2);
				}

				double originXDelta = newOriginX - currentOriginX;

				if (right) originXDelta *= -1.0;

				final double newScaleX = current.scaleX() * (1.0 - scaleDelta);

				// If we scale too small the target can do weird things
				if (newScaleX < 0.001 || Double.isNaN(newScaleX) || Double.isInfinite(newScaleX)) return;

				final Placement resized = new Placement(current.x() + originXDelta, current.y(), newScaleX,
						current.scaleY(), current.visible());
				final Rect resizedBounds = placed.boundsAt(resized);

				// If the target would go out of bounds, it keeps its old size
				if (!keepInBounds || !(resizedBounds.getMinX() <= 0
						|| resizedBounds.getMaxX() >= config.get().getDisplayWidth())) {
					current = resized;
				}
			}

			if (top || bottom) {
				final double gap;

				if (bottom) {
					gap = (event.getY() - local.getMaxY()) * current.scaleY();
				} else {
					gap = (event.getY() - local.getMinY()) * current.scaleY();
				}

				final Rect bounds = placed.boundsAt(current);
				final double currentHeight = bounds.getHeight();
				double newHeight = currentHeight + gap;

				if (fixedAspectRatioResize) {
					if ((left && bottom) || (right && top)) aspectScaleDelta *= -1.0;

					newHeight = currentHeight + (currentHeight * aspectScaleDelta);
				}

				double scaleDelta = (newHeight - currentHeight) / currentHeight;

				final double currentOriginY = bounds.getMinY();
				final double newOriginY;

				if (bottom) {
					scaleDelta *= -1.0;
					newOriginY = currentOriginY - ((newHeight - currentHeight) / 2);
				} else {
					newOriginY = currentOriginY + ((newHeight - currentHeight) / 2);
				}

				double originYDelta = newOriginY - currentOriginY;

				if (bottom) originYDelta *= -1.0;

				final double newScaleY = current.scaleY() * (1.0 - scaleDelta);

				// If we scale too small the target can do weird things; a width change above still
				// applies, as it always did
				if (newScaleY < 0.001 || Double.isNaN(newScaleY) || Double.isInfinite(newScaleY)) {
					m.set().place(placed.getId(), current);
					return;
				}

				final Placement resized = new Placement(current.x(), current.y() + originYDelta, current.scaleX(),
						newScaleY, current.visible());
				final Rect resizedBounds = placed.boundsAt(resized);

				if (!keepInBounds || !(resizedBounds.getMinY() <= 0
						|| resizedBounds.getMaxY() >= config.get().getDisplayHeight())) {
					current = resized;
				}
			}

			m.set().place(placed.getId(), current);
			recordMoved();
			recordResized();
		});
	}

	// Unchanged
	private void mouseMoved() {
		targetGroup.setOnMouseMoved((event) -> {
			x = event.getX();
			y = event.getY();

			if (isTopZone(event) && isLeftZone(event)) {
				targetGroup.setCursor(Cursor.NW_RESIZE);
			} else if (isTopZone(event) && isRightZone(event)) {
				targetGroup.setCursor(Cursor.NE_RESIZE);
			} else if (isBottomZone(event) && isLeftZone(event)) {
				targetGroup.setCursor(Cursor.SW_RESIZE);
			} else if (isBottomZone(event) && isRightZone(event)) {
				targetGroup.setCursor(Cursor.SE_RESIZE);
			} else if (isTopZone(event)) {
				targetGroup.setCursor(Cursor.N_RESIZE);
			} else if (isBottomZone(event)) {
				targetGroup.setCursor(Cursor.S_RESIZE);
			} else if (isLeftZone(event)) {
				targetGroup.setCursor(Cursor.W_RESIZE);
			} else if (isRightZone(event)) {
				targetGroup.setCursor(Cursor.E_RESIZE);
			} else {
				targetGroup.setCursor(Cursor.DEFAULT);
			}
		});
	}

	// Unchanged
	private void mouseReleased() {
		targetGroup.setOnMouseReleased((event) -> {
			resize = false;
			move = false;
			targetGroup.setCursor(Cursor.DEFAULT);
		});
	}

	// The old handler's arithmetic on the model's placement and bounds
	private void keyPressed() {
		targetGroup.setOnKeyPressed((event) -> {
			final Membership m = membership;
			final Placement p = m.placed().getPlacement();
			final Rect bounds = m.placed().getBounds();
			final double currentWidth = bounds.getWidth();
			final double currentHeight = bounds.getHeight();

			switch (event.getCode()) {
			case DELETE:
			case BACK_SPACE:
				if (userDeletable && parent.isPresent()) parent.get().removeTarget(this);
				break;

			case LEFT: {
				if (event.isShiftDown()) {
					final double newWidth = currentWidth - SCALE_DELTA;
					final double scaleDelta = (newWidth - currentWidth) / currentWidth;

					m.set().scale(m.placed().getId(), p.scaleX() * (1.0 - scaleDelta), p.scaleY());
					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinX() - MOVEMENT_DELTA >= 0
							&& bounds.getMaxX() - MOVEMENT_DELTA <= config.get().getDisplayWidth())) {

						m.set().move(m.placed().getId(), p.x() - MOVEMENT_DELTA, p.y());
					}

					recordMoved();
				}
			}

				break;

			case RIGHT: {
				if (event.isShiftDown()) {
					final double newWidth = currentWidth + SCALE_DELTA;
					final double scaleDelta = (newWidth - currentWidth) / currentWidth;

					if (!keepInBounds || (bounds.getMinX() + (SCALE_DELTA / 2) >= 0
							&& bounds.getMaxX() + (SCALE_DELTA / 2) <= config.get().getDisplayWidth())) {
						m.set().scale(m.placed().getId(), p.scaleX() * (1.0 - scaleDelta), p.scaleY());
					}

					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinX() + MOVEMENT_DELTA >= 0
							&& bounds.getMaxX() + MOVEMENT_DELTA <= config.get().getDisplayWidth())) {

						m.set().move(m.placed().getId(), p.x() + MOVEMENT_DELTA, p.y());
					}

					recordMoved();
				}
			}

				break;

			case UP: {
				if (event.isShiftDown()) {
					final double newHeight = currentHeight - SCALE_DELTA;
					final double scaleDelta = (newHeight - currentHeight) / currentHeight;
					double scaleX = p.scaleX();

					// Scale up proportionally if ctrl is down
					if (event.isControlDown()) {
						final double newWidth = currentWidth - (SCALE_DELTA * (currentWidth / currentHeight));
						final double widthDelta = (newWidth - currentWidth) / currentWidth;

						scaleX = scaleX * (1.0 - widthDelta);
					}

					m.set().scale(m.placed().getId(), scaleX, p.scaleY() * (1.0 - scaleDelta));
					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinY() - MOVEMENT_DELTA >= 0
							&& bounds.getMaxY() - MOVEMENT_DELTA <= config.get().getDisplayHeight())) {

						m.set().move(m.placed().getId(), p.x(), p.y() - MOVEMENT_DELTA);
					}

					recordMoved();
				}
			}

				break;

			case DOWN: {
				if (event.isShiftDown()) {
					final double newHeight = currentHeight + SCALE_DELTA;
					final double scaleDelta = (newHeight - currentHeight) / currentHeight;

					if (!keepInBounds || (bounds.getMinY() + (SCALE_DELTA / 2) >= 0
							&& bounds.getMaxY() + (SCALE_DELTA / 2) <= config.get().getDisplayHeight())) {
						double scaleX = p.scaleX();

						// Scale down proportionally if ctrl is down
						if (event.isControlDown()) {
							final double newWidth = currentWidth + (SCALE_DELTA * (currentWidth / currentHeight));
							final double widthDelta = (newWidth - currentWidth) / currentWidth;

							scaleX = scaleX * (1.0 - widthDelta);
						}

						m.set().scale(m.placed().getId(), scaleX, p.scaleY() * (1.0 - scaleDelta));
					}

					recordResized();
				} else {
					if (!keepInBounds || (bounds.getMinY() + MOVEMENT_DELTA >= 0
							&& bounds.getMaxY() + MOVEMENT_DELTA <= config.get().getDisplayHeight())) {

						m.set().move(m.placed().getId(), p.x(), p.y() + MOVEMENT_DELTA);
					}

					recordMoved();
				}
			}

				break;

			default:
				break;
			}
			event.consume();
		});
	}

	// The resize zones are measured on the target's regions, not on selection decorations
	private Rect localBounds() {
		return membership.placed().getLocalBounds();
	}

	private boolean isTopZone(MouseEvent event) {
		return event.getY() < (localBounds().getMinY() + RESIZE_MARGIN);
	}

	private boolean isBottomZone(MouseEvent event) {
		return event.getY() > (localBounds().getMaxY() - RESIZE_MARGIN);
	}

	private boolean isLeftZone(MouseEvent event) {
		return event.getX() < (localBounds().getMinX() + RESIZE_MARGIN);
	}

	private boolean isRightZone(MouseEvent event) {
		return event.getX() > (localBounds().getMaxX() - RESIZE_MARGIN);
	}

	private boolean isInResizeZone(MouseEvent event) {
		return isTopZone(event) || isBottomZone(event) || isLeftZone(event) || isRightZone(event);
	}

	@Override
	public boolean tagExists(String name) {
		return targetTags.containsKey(name);
	}

	@Override
	public String getTag(String name) {
		return targetTags.get(name);
	}

	@Override
	public Map<String, String> getAllTags() {
		return targetTags;
	}
}
```

Before moving on, diff the kept parts against the old file to make sure nothing drifted:

```bash
git diff -U0 javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java | command grep -E '^[-+].*(parseCommandTag|getTargetRegionByName|reverseAnimation|toggleSelected|addResizeAnchors|mousePressed|mouseMoved|mouseReleased)' | head
```

Expected: only the `// Unchanged` comment lines and the `animate`/`isHit` context. Those method bodies must not appear as changed.

- [ ] **Step 4: Update `MirroredTarget`**

In `javafx-app/src/main/java/com/shootoff/gui/targets/MirroredTarget.java`, replace

```java
	public MirroredTarget(File targetFile, Group target, Map<String, String> targetTags, Configuration config,
			CanvasManager parent, boolean userDeletable) {
		super(targetFile, target, targetTags, parent, userDeletable);
	}
```

with

```java
	public MirroredTarget(TargetComponents components, Configuration config, CanvasManager parent,
			boolean userDeletable) {
		super(components, parent, userDeletable);
	}
```

Replace the imports `java.io.File`, `java.util.Map` and `javafx.scene.Group` with `com.shootoff.targets.io.TargetIO.TargetComponents`.

- [ ] **Step 5: Give each canvas a `TargetSet`**

In `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java`:

1. Add `import com.shootoff.targets.model.TargetSet;` after `import com.shootoff.targets.io.TargetIO.TargetComponents;`.
2. After `private final List<Target> targets = new ArrayList<>();` add:

   ```java
   	// The model of targets, in the same order
   	private final TargetSet targetSet = new TargetSet();
   ```

3. Replace the whole `addTarget(File targetFile, boolean playAnimations)` method and `addTarget(File targetFile, Group targetGroup, Map<String, String> targetTags, boolean userDeletable)` method with:

   ```java
   	public Optional<Target> addTarget(File targetFile, boolean playAnimations) {
   		final Optional<TargetComponents> targetComponents = loadTarget(targetFile, playAnimations);

   		if (targetComponents.isPresent()) {
   			return Optional.of(addTarget(targetComponents.get().withTargetFile(targetFile), true));
   		}

   		return Optional.empty();
   	}
   ```

   and, after `addTarget(File targetFile)`:

   ```java
   	public Target addTarget(TargetComponents components, boolean userDeletable) {
   		final TargetView newTarget;

   		if (this instanceof MirroredCanvasManager) {
   			newTarget = new MirroredTarget(components, config, this, userDeletable);
   		} else {
   			newTarget = new TargetView(components, this, userDeletable);
   		}

   		return addTarget(newTarget);
   	}
   ```

4. In `addTarget(Target newTarget)`, replace

   ```java
   		targets.add(newTarget);
   ```

   with

   ```java
   		((TargetView) newTarget).joinTargetSet(targetSet);
   		targets.add(newTarget);
   ```

5. In `removeTarget(Target target)`, replace

   ```java
   		targets.remove(target);
   ```

   with

   ```java
   		targets.remove(target);
   		((TargetView) target).leaveTargetSet();
   ```

6. After `getTargets()` add:

   ```java
   	/**
   	 * @return the model of this canvas's targets, in the same order as {@link #getTargets()}
   	 */
   	public TargetSet getTargetSet() {
   		return targetSet;
   	}
   ```

The removal is still recorded before the target leaves the list and the set, so its index is valid when recorded.

- [ ] **Step 6: Update the other target creators**

`javafx-app/src/main/java/com/shootoff/gui/MirroredCanvasManager.java`: replace the two methods

```java
	@Override
	public Target addTarget(File targetFile, Group targetGroup, Map<String, String> targetTags, boolean userDeletable) {
		final Optional<TargetComponents> targetComponents = super.loadTarget(targetFile, false);

		if (targetComponents.isPresent()) {
			final TargetComponents tc = targetComponents.get();
			return mirroredManager.mirrorAddTarget(targetFile, tc.getTargetGroup(), tc.getTargetTags(), userDeletable);
		}

		return null;
	}

	public Target mirrorAddTarget(File targetFile, Group targetGroup, Map<String, String> targetTags,
			boolean userDeletable) {
		return super.addTarget(targetFile, targetGroup, targetTags, userDeletable);
	}
```

with

```java
	@Override
	public Target addTarget(TargetComponents components, boolean userDeletable) {
		final File targetFile = components.getTargetFile();
		final Optional<TargetComponents> targetComponents = super.loadTarget(targetFile, false);

		if (targetComponents.isPresent()) {
			return mirroredManager.mirrorAddTarget(targetComponents.get().withTargetFile(targetFile), userDeletable);
		}

		return null;
	}

	public Target mirrorAddTarget(TargetComponents components, boolean userDeletable) {
		return super.addTarget(components, userDeletable);
	}
```

In `addTarget(Target newTarget)` of the same file, replace

```java
		if (newTarget instanceof MirroredTarget) {
			target = (MirroredTarget) newTarget;
		} else {
			target = new MirroredTarget(newTarget.getTargetFile(), ((TargetView) newTarget).getTargetGroup(),
					newTarget.getAllTags(), config, this, ((TargetView) newTarget).isUserDeletable());
		}

		final Optional<TargetComponents> targetComponents = super.loadTarget(newTarget.getTargetFile(), false);

		if (targetComponents.isPresent()) {
			final TargetComponents tc = targetComponents.get();
			final MirroredTarget t = new MirroredTarget(newTarget.getTargetFile(), tc.getTargetGroup(),
					tc.getTargetTags(), config, mirroredManager, ((TargetView) newTarget).isUserDeletable());
```

with

```java
		if (newTarget instanceof MirroredTarget) {
			target = (MirroredTarget) newTarget;
		} else {
			// Wrap the view's nodes and keep where it was placed (e.g. a course target)
			final TargetView source = (TargetView) newTarget;
			target = new MirroredTarget(source.getComponents(), config, this, source.isUserDeletable());
			target.setPlacement(source.getPlacement());
		}

		final Optional<TargetComponents> targetComponents = super.loadTarget(newTarget.getTargetFile(), false);

		if (targetComponents.isPresent()) {
			final TargetComponents tc = targetComponents.get().withTargetFile(newTarget.getTargetFile());
			final MirroredTarget t = new MirroredTarget(tc, config, mirroredManager,
					((TargetView) newTarget).isUserDeletable());
```

The rest of the method is unchanged. Remove the now-unused imports `java.util.Map` and `javafx.scene.Group` if nothing else in the file uses them (`command grep -n "Map<\|Group" javafx-app/src/main/java/com/shootoff/gui/MirroredCanvasManager.java`).

`javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java`: replace the body of `createCalibrationTarget(double x, double y, double width, double height)` with

```java
		// Purple (Color.PURPLE) at the default target opacity, as before
		final TargetDefinition definition = new TargetDefinition(Optional.empty(), Map.of(), List.of(
				new com.shootoff.targets.model.RectangleRegion(0, x, y, width, height, "#800080", Map.of())));

		final TargetComponents components;
		try {
			components = TargetIO.buildTarget(definition, ResourceResolver.files(), false);
		} catch (final IOException e) {
			// A rectangle reads no files
			throw new UncheckedIOException(e);
		}

		calibrationTarget = Optional.of((TargetView) calibratingCanvasManager.addTarget(components, false));
		calibrationTarget.get().setKeepInBounds(true);
```

Add the imports `java.io.IOException`, `java.io.UncheckedIOException`, `java.util.List`, `java.util.Map`, `com.shootoff.targets.io.TargetIO.TargetComponents`, `com.shootoff.targets.model.ResourceResolver` and `com.shootoff.targets.model.TargetDefinition` (skip any the file already has). The old code's click handler on the group was always replaced by `TargetView`'s own, so dropping it changes nothing. `calibrate(FxGeometry.toRect(calibrationTarget.get().getTargetGroup().getBoundsInParent()), …)` stays as it is (ruling 7).

`javafx-app/src/main/java/com/shootoff/gui/SessionCanvasManager.java`: replace the body of `addTarget(final TargetAddedEvent e)` with

```java
		final Optional<TargetComponents> targetComponents = TargetIO.loadTarget(
				new File(System.getProperty("shootoff.home") + File.separator + "targets/" + e.getTargetName()));

		final TargetComponents components;
		if (targetComponents.isPresent()) {
			components = targetComponents.get();
		} else {
			// An empty stand-in keeps this target's slot so later target indexes in the session
			// still line up
			logSkipped(e, "target " + e.getTargetName() + " could not be loaded; using an empty stand-in");
			components = TargetComponents.empty(null);
		}

		addToCanvas(components.getTargetGroup());
		final TargetView targetContainer = new TargetView(components, targets);
		eventToContainer.put(e, targetContainer);
		targetViews.add(targetContainer);
		targets.add(targetContainer);
```

`javafx-app/src/main/java/com/shootoff/courses/io/XMLCourseReader.java`: replace

```java
					final TargetView t = new TargetView(targetFile, tc.getTargetGroup(), tc.getTargetTags(),
							arenaPane.getCanvasManager(), true);
```

with

```java
					final TargetView t = new TargetView(tc, arenaPane.getCanvasManager(), true);
```

`javafx-app/src/main/java/com/shootoff/gui/FxGeometry.java`: add `import javafx.geometry.BoundingBox;` and the method

```java
	public static Bounds toBounds(Rect rect) {
		return new BoundingBox(rect.getMinX(), rect.getMinY(), rect.getWidth(), rect.getHeight());
	}
```

- [ ] **Step 7: Update the tests' target construction**

`javafx-app/src/test/java/com/shootoff/gui/MockCanvasManager.java`: in `addTarget(Target newTarget)` replace

```java
		super.getTargets().add(newTarget);
```

with

```java
		((TargetView) newTarget).joinTargetSet(super.getTargetSet());
		super.getTargets().add(newTarget);
```

The rest are mechanical rewrites of the old constructor calls:

```bash
cd /home/bfears/projects/ShootOFF
T=javafx-app/src/test/java/com/shootoff
# (tc.getTargetGroup(), tc.getTargetTags(), list) -> (tc, list)
perl -pi -e 's/new TargetView\((\w+)\.getTargetGroup\(\), \1\.getTargetTags\(\), /new TargetView($1, /g' \
  $T/plugins/TestDuelingTree.java $T/plugins/TestShootForScore.java $T/plugins/TestISSFStandardPistol.java \
  $T/plugins/TestRandomShoot.java $T/gui/targets/TestTarget.java $T/gui/targets/TestTargetCommands.java
# (tc.getTargetGroup(), tc.getTargetTags(), canvas, deletable) in TestCourseIO -> (tc, canvas, deletable)
perl -0pi -e 's/new TargetView\(targetFile, tc\.getTargetGroup\(\), tc\.getTargetTags\(\),\s*/new TargetView(tc, /' \
  $T/courses/io/TestCourseIO.java
# (new File(...) or null, new Group(), new HashMap<String, String>(), ...) -> (TargetComponents.empty(...), ...)
perl -0pi -e 's/new (TargetView|MirroredTarget)\((new File\([^()]*\)|null), new Group\(\),\s*new HashMap<String, String>\(\),\s*/new $1(TargetComponents.empty($2), /g' \
  $T/session/TestSessionRecorder.java $T/session/io/TestSessionIO.java $T/gui/TestCanvasManagerSessionRecording.java
# Those three files need the TargetComponents import
perl -pi -e 's/^(import com\.shootoff\.gui\.targets\.TargetView;)$/$1\nimport com.shootoff.targets.io.TargetIO.TargetComponents;/' \
  $T/session/TestSessionRecorder.java $T/session/io/TestSessionIO.java $T/gui/TestCanvasManagerSessionRecording.java
command grep -rn "getTargetGroup(), .*getTargetTags()\|new Group(), *new HashMap\|new Group(),$" $T
```

Expected from the last `grep`: nothing.

Check the MirroredTarget call in `TestCanvasManagerSessionRecording` now reads `new MirroredTarget(TargetComponents.empty(new File("targets/shoot.target")), config, canvas, false)`. Unused `Group`/`HashMap` imports left behind compile fine; remove them if you like.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.*' --tests 'com.shootoff.targets.*' --tests 'com.shootoff.session.*' --tests 'com.shootoff.courses.*' --tests 'com.shootoff.plugins.*' --console=plain`
Expected: all pass, including:
- `TestTargetViewPlacement` (5)
- the key tests in `TestTarget`: arrow keys move by 1 px; shift+arrows resize; ctrl+shift+up/down resize in proportion
- `TestCanvasManagerSessionRecording`: add/move/resize/remove events, the non-recording canvas, the file-less target, and the mirrored resize
- `TestTargetCommands.testPOIAdjust`, whose POI target's `setDimensions(640, 360)` now goes through the model
- `TestCourseIO` and `TestSessionCanvasManagerReplay`
- both `TestHitParity` tests

If `draggingTheRightEdgeWidensTheTargetAndKeepsItsLeftEdge` is off by a fraction of a pixel, check that the zone and gap use `placed.getLocalBounds()`. The group's JavaFX bounds now include the `Scale` transform and are not the local bounds.

- [ ] **Step 9: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 5 (**282**).

```bash
git add javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java javafx-app/src/main/java/com/shootoff/gui/targets/MirroredTarget.java \
  javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java javafx-app/src/main/java/com/shootoff/gui/MirroredCanvasManager.java \
  javafx-app/src/main/java/com/shootoff/gui/CalibrationManager.java javafx-app/src/main/java/com/shootoff/gui/SessionCanvasManager.java \
  javafx-app/src/main/java/com/shootoff/gui/FxGeometry.java javafx-app/src/main/java/com/shootoff/courses/io/XMLCourseReader.java \
  javafx-app/src/test/java/com/shootoff/gui/MockCanvasManager.java javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetViewPlacement.java \
  javafx-app/src/test/java/com/shootoff/gui/targets/TestTarget.java javafx-app/src/test/java/com/shootoff/gui/targets/TestTargetCommands.java \
  javafx-app/src/test/java/com/shootoff/gui/TestCanvasManagerSessionRecording.java \
  javafx-app/src/test/java/com/shootoff/session/TestSessionRecorder.java javafx-app/src/test/java/com/shootoff/session/io/TestSessionIO.java \
  javafx-app/src/test/java/com/shootoff/courses/io/TestCourseIO.java \
  javafx-app/src/test/java/com/shootoff/plugins/TestDuelingTree.java javafx-app/src/test/java/com/shootoff/plugins/TestShootForScore.java \
  javafx-app/src/test/java/com/shootoff/plugins/TestISSFStandardPistol.java javafx-app/src/test/java/com/shootoff/plugins/TestRandomShoot.java
git status --short
git commit -m "Keep each target's position, scale and visibility in its canvas's TargetSet"
git log -1 --format=%B
```

`git status --short` must show only ` M shootoff.properties` after the commit.

---
### Task 6: Hits come from `HitTester`

`CanvasManager.checkHit` and the v1 `TargetView.isHit` ask the core `HitTester`. The result is turned into a v1 `Hit` with the same region node, and with the same whole-pixel impact offsets relative to the region's bounds on the canvas. `TargetView` gives the model each image region's current frame as an `AlphaMask`, and updates it whenever the frame changes (animations and resets). The JavaFX hit code is deleted from `TargetView`. `LegacyFxHitTest`, in the test tree, keeps the old algorithm for the parity test.

**Files:**
- Modify: `javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java` (image masks, `isHit`, `toHit`)
- Modify: `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java` (`checkHit`)
- Create (test): `javafx-app/src/test/java/com/shootoff/gui/TestCanvasManagerHits.java`

**Interfaces:**
- Consumes:
  - from Task 2: `HitTester.hit(TargetSet, x, y)` and `hit(PlacedTarget, x, y)`, `PlacedTarget.regionBounds(Region)`, `TargetSet.setImageMask`
  - from Task 3: `FxAlphaMasks.of(Image)`
  - from Task 5: `TargetView.getPlacedTarget()`/`getTargetSet()`, `CanvasManager.getTargetSet()`
- Produces:
  - `TargetView.toHit(com.shootoff.targets.model.Hit hit, double x, double y) → com.shootoff.targets.Hit`: the v1 hit, whose region is `getRegions().get(hit.region().index())` and whose impact is `(int) (x - regionBounds.minX)`, `(int) (y - regionBounds.minY)`
  - `TargetView.isHit(x, y)` now backed by `HitTester.hit(PlacedTarget, …)`; it ignores the target's visibility, as before
  - `CanvasManager.checkHit` now backed by `HitTester.hit(getTargetSet(), …)`; hidden targets are skipped (ruling 4). Its signature and its session recording are unchanged: the region index recorded is the model region's index.

- [ ] **Step 1: Write the failing tests**

Create `javafx-app/src/test/java/com/shootoff/gui/TestCanvasManagerHits.java`:

```java
package com.shootoff.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.List;
import java.util.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.geom.Rect;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.targets.Hit;
import com.shootoff.targets.ImageRegion;
import com.shootoff.targets.animation.SpriteAnimation;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.HitTester;
import com.shootoff.targets.model.PlacedTarget;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.image.Image;

public class TestCanvasManagerHits {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private MockCanvasManager canvas;

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		canvas = new MockCanvasManager(new Configuration(new String[0]));
	}

	private TargetView add(String targetFile) {
		return (TargetView) canvas
				.addTarget(new TargetView(TargetIO.loadTarget(new File(targetFile), false).get(), canvas, true));
	}

	private Optional<Hit> shoot(double x, double y) {
		return canvas.checkHit(new DisplayShot(ShotColor.RED, x, y, 0, 2), Optional.empty(), false);
	}

	private int regionHitAt(TargetView target, double x, double y) {
		return shoot(x, y).map(hit -> target.getRegions().indexOf(hit.getHitRegion())).orElse(-1);
	}

	@Test
	public void hitsComeFromTheModelWithTheLegacyImpactOffsets() {
		final TargetView ipsc = add("targets/IPSC.target");
		ipsc.setPosition(40, 30);
		final double x = 190.37;
		final double y = 180.61;

		final Optional<Hit> hit = shoot(x, y);
		final com.shootoff.targets.model.Hit modelHit = HitTester.hit(canvas.getTargetSet(), x, y).get();

		assertTrue(hit.isPresent());
		assertSame(ipsc, hit.get().getTarget());
		assertSame(ipsc.getRegions().get(modelHit.region().index()), hit.get().getHitRegion());

		// The impact is relative to the hit region's bounds on the canvas, as before
		final Node regionNode = (Node) hit.get().getHitRegion();
		final Bounds regionBounds = ipsc.getTargetGroup().getLocalToParentTransform()
				.transform(regionNode.getBoundsInParent());
		assertEquals((int) (x - regionBounds.getMinX()), hit.get().getImpactX());
		assertEquals((int) (y - regionBounds.getMinY()), hit.get().getImpactY());
	}

	@Test
	public void hiddenTargetsTakeNoShots() {
		final TargetView ipsc = add("targets/IPSC.target");
		assertTrue(shoot(150.37, 150.61).isPresent());

		ipsc.setVisible(false);

		assertFalse(shoot(150.37, 150.61).isPresent());
		// The v1 per-target test still ignores visibility
		assertTrue(ipsc.isHit(150.37, 150.61).isPresent());
	}

	@Test
	public void imageHitsFollowTheCurrentAnimationFrame() {
		for (final String targetFile : List.of("targets/Pepper_Popper.target",
				"targets/IPSC_Classic_Falling_Popper.target")) {
			final TargetView target = add(targetFile);
			final PlacedTarget placed = target.getPlacedTarget();

			for (int i = 0; i < target.getRegions().size(); i++) {
				if (!(target.getRegions().get(i) instanceof ImageRegion image) || image.getAnimation().isEmpty())
					continue;

				final SpriteAnimation animation = image.getAnimation().get();
				final Image first = animation.getFrame(0);
				final Image last = animation.getFrame(animation.getFrameCount() - 1);
				final Optional<double[]> point = pointOnlyTheFirstFrameCovers(placed, i, first, last);
				if (point.isEmpty()) continue;

				final double x = point.get()[0];
				final double y = point.get()[1];
				assertEquals(i, regionHitAt(target, x, y));

				// The popper falls: where it stood is now transparent
				image.setImage(last);
				assertNotEquals(i, regionHitAt(target, x, y));

				// And stands again after a reset
				image.setImage(first);
				assertEquals(i, regionHitAt(target, x, y));
				return;
			}

			canvas.removeTarget(target);
		}

		fail("No animated region has a pixel that only its first frame covers");
	}

	// A canvas point on an opaque pixel of the first frame that is transparent in the last, and
	// that no other region of the target covers
	private static Optional<double[]> pointOnlyTheFirstFrameCovers(PlacedTarget placed, int regionIndex, Image first,
			Image last) {
		final Rect bounds = placed.regionBounds(placed.getDefinition().regions().get(regionIndex));

		for (int py = 0; py < (int) first.getHeight(); py++) {
			for (int px = 0; px < (int) first.getWidth(); px++) {
				if ((first.getPixelReader().getArgb(px, py) >>> 24) == 0) continue;
				if ((last.getPixelReader().getArgb(px, py) >>> 24) != 0) continue;

				final double x = bounds.getMinX() + px + 0.5;
				final double y = bounds.getMinY() + py + 0.5;

				final boolean coveredByAnother = placed.getDefinition().regions().stream()
						.filter(region -> region.index() != regionIndex)
						.anyMatch(region -> placed.regionBounds(region).contains(x, y));
				if (!coveredByAnother) return Optional.of(new double[] { x, y });
			}
		}

		return Optional.empty();
	}
}
```

Before this task `checkHit` still uses the JavaFX hit test, which already follows the frame, so `imageHitsFollowTheCurrentAnimationFrame` passes at first. It guards the mask code. Step 3 shows it failing between the `checkHit` change and the mask change.

- [ ] **Step 2: Run the tests to see the starting point**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.TestCanvasManagerHits' --console=plain`
Expected:
- `hitsComeFromTheModelWithTheLegacyImpactOffsets` and `imageHitsFollowTheCurrentAnimationFrame` pass: the JavaFX path agrees with the model, as Task 3 proved.
- `hiddenTargetsTakeNoShots` FAILS at `assertFalse(shoot(...))`, because the JavaFX path still hits hidden targets.

- [ ] **Step 3: Implement**

In `javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java`:

1. Add imports `com.shootoff.targets.model.HitTester` and `com.shootoff.targets.model.TargetId`.
2. Replace the whole `checkHit` method with:

```java
	protected Optional<Hit> checkHit(DisplayShot shot, Optional<String> videoString, boolean isMirroredShot) {
		// An ArenaShot's getX/getY are its arena coordinates
		final double x = shot.getX();
		final double y = shot.getY();

		// The model checks visible targets topmost (last added) first, so shots register for the
		// top target when targets overlap
		final Optional<com.shootoff.targets.model.Hit> modelHit = HitTester.hit(targetSet, x, y);
		final Optional<TargetView> target = modelHit.flatMap(h -> targetFor(h.targetId()));

		if (modelHit.isPresent() && target.isPresent()) {
			final Hit hit = target.get().toHit(modelHit.get(), x, y);
			hit.setShot(shot);

			final TargetRegion region = hit.getHitRegion();

			if (config.inDebugMode()) {
				final Map<String, String> tags = region.getAllTags();

				final StringBuilder tagList = new StringBuilder();
				for (final Iterator<Entry<String, String>> it = tags.entrySet().iterator(); it.hasNext();) {
					final Entry<String, String> entry = it.next();
					tagList.append(entry.getKey());
					tagList.append(":");
					tagList.append(entry.getValue());
					if (it.hasNext()) tagList.append(", ");
				}

				logger.debug("Processing Shot: Found Hit Region For Shot ({}, {}), Type ({}), Tags ({})",
						shot.getX(), shot.getY(), region.getType(), tagList.toString());
			}

			if (!isMirroredShot && config.getSessionRecorder().isPresent()) {
				config.getSessionRecorder().get().recordShot(cameraName, shot, false, false,
						Optional.of(target.get()), Optional.of(modelHit.get().region().index()), videoString);
			}

			return Optional.of(hit);
		}

		logger.debug("Processing Shot: Did Not Find Hit For Shot ({}, {})", shot.getX(), shot.getY());

		if (!isMirroredShot && config.getSessionRecorder().isPresent()) {
			config.getSessionRecorder().get().recordShot(cameraName, shot, false, false, Optional.empty(),
					Optional.empty(), videoString);
		}

		return Optional.empty();
	}

	// The view of a target in this canvas's set; empty if another thread removed it meanwhile
	private Optional<TargetView> targetFor(TargetId id) {
		for (final Target target : new ArrayList<>(targets)) {
			final TargetView view = (TargetView) target;
			if (view.getPlacedTarget().getId().equals(id)) return Optional.of(view);
		}

		return Optional.empty();
	}
```

`ListIterator` may now be unused; remove its import if nothing else uses it.

Run `./gradlew :javafx-app:test --tests 'com.shootoff.gui.TestCanvasManagerHits' --console=plain` now. Expected: `hiddenTargetsTakeNoShots` passes, but `imageHitsFollowTheCurrentAnimationFrame` FAILS at `assertNotEquals`, because without masks the model hits an image anywhere in its bounds. That is the RED state for the mask code.

In `javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java`:

1. Add the imports `com.shootoff.targets.model.HitTester`. Remove `java.awt.Graphics2D`, `java.awt.image.BufferedImage` and `javafx.embed.swing.SwingFXUtils`: after this step only the deleted `isHit` body used them.
2. In the private constructor, after `applyPlacement(membership.placed());`, add:

   ```java
   		watchImageFrames();
   		pushImageMasks();
   ```

3. In `joinTargetSet`, after `applyPlacement(placed);`, add:

   ```java
   		pushImageMasks();
   ```

4. Add these methods after `applyPlacement`:

   ```java
   	// The hit tester reads each image region's current frame, which animations and resets change
   	private void watchImageFrames() {
   		for (int i = 0; i < regionNodes.size(); i++) {
   			if (regionNodes.get(i) instanceof ImageRegion imageRegion) {
   				final int regionIndex = i;
   				imageRegion.imageProperty()
   						.addListener((observable, oldImage, newImage) -> pushImageMask(regionIndex, newImage));
   			}
   		}
   	}

   	private void pushImageMasks() {
   		for (int i = 0; i < regionNodes.size(); i++) {
   			if (regionNodes.get(i) instanceof ImageRegion imageRegion) pushImageMask(i, imageRegion.getImage());
   		}
   	}

   	private void pushImageMask(int regionIndex, Image image) {
   		if (image == null) return;

   		final Membership m = membership;
   		m.set().setImageMask(m.placed().getId(), regionIndex, FxAlphaMasks.of(image));
   	}
   ```

5. Replace the whole `isHit` method (from the `// Unchanged until Task 6` comment through the method's closing brace) with:

   ```java
   	/**
   	 * The v1 hit test for this target alone: the core hit tester, ignoring whether the target is
   	 * visible, as this method always has.
   	 */
   	@Override
   	public Optional<Hit> isHit(double x, double y) {
   		return HitTester.hit(membership.placed(), x, y).map(hit -> toHit(hit, x, y));
   	}

   	/**
   	 * @return a model hit on this target as the v1 API reports it: the region's JavaFX node, and
   	 *         the impact relative to the region's bounds on the canvas, in whole pixels
   	 */
   	public Hit toHit(com.shootoff.targets.model.Hit hit, double x, double y) {
   		final Rect regionBounds = membership.placed().regionBounds(hit.region());

   		return new Hit(this, (TargetRegion) regionNodes.get(hit.region().index()),
   				(int) (x - regionBounds.getMinX()), (int) (y - regionBounds.getMinY()));
   	}
   ```

Check that no JavaFX hit test is left in main code:

```bash
command grep -rn "getPixelReader\|getScaledInstance\|node.contains(" javafx-app/src/main/java/com/shootoff/gui javafx-app/src/main/java/com/shootoff/targets
```

Expected: nothing. `LegacyFxHitTest` is in the test tree.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :javafx-app:test --tests 'com.shootoff.gui.*' --tests 'com.shootoff.targets.*' --tests 'com.shootoff.plugins.*' --tests 'com.shootoff.camera.*' --console=plain`
Expected: all pass, including:
- `TestCanvasManagerHits` (3)
- `TestCanvasManager`'s hit tests
- `TestTargetCommands.testPOIAdjust`: the POI rectangles hidden by their tag are still hit (ruling 3)
- both `TestHitParity` tests
- the plugin tests
- the `TestCameraManager*` video tests, whose shots now hit through the model

- [ ] **Step 5: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 3 (**285**).

```bash
git add javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java \
  javafx-app/src/test/java/com/shootoff/gui/TestCanvasManagerHits.java
git commit -m "Hit-test shots with the core HitTester instead of JavaFX node geometry"
git log -1 --format=%B
```

---
### Task 7: Sessions move to `core`

The `session` and `session.io` packages move to `core`. Events keep core `Shot` data, and `SessionRecorder` identifies targets by `TargetRef(TargetSet, TargetId)`, converting them to indexes at record time (ruling 14). The files written are byte-for-byte what the app wrote before, and the JavaFX paint strings they contain come from `SessionColors`. `SessionCanvasManager` stays in javafx-app and makes the `DisplayShot` markers from the events.

**Files:**
- Move (`git mv`) every file under `javafx-app/src/main/java/com/shootoff/session/` to `core/src/main/java/com/shootoff/session/` (keeping `io/`), then modify: `SessionRecorder.java` (rewrite), `ShotEvent.java` (rewrite), `io/EventVisitor.java` (rewrite), `io/SessionIO.java`, `io/XMLSessionWriter.java`, `io/JSONSessionWriter.java`, `io/XMLSessionReader.java`, `io/JSONSessionReader.java`
- Create: `core/src/main/java/com/shootoff/session/TargetRef.java`, `core/src/main/java/com/shootoff/session/io/SessionColors.java`
- Move (`git mv`) and modify tests:
  - `javafx-app/src/test/java/com/shootoff/session/TestSessionRecorder.java` → `core/src/test/java/com/shootoff/session/`
  - `javafx-app/src/test/java/com/shootoff/session/io/TestSessionIO.java` → `core/src/test/java/com/shootoff/session/io/`
  - `javafx-app/src/test/resources/sessions/legacy_session.json` → `core/src/test/resources/sessions/`
- Create (tests): `core/src/test/java/com/shootoff/session/io/TestSessionFormat.java`, `javafx-app/src/test/java/com/shootoff/session/io/TestSessionColors.java`
- Modify: `javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java`, `gui/CanvasManager.java`, `gui/SessionCanvasManager.java`; test `javafx-app/src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java`
- Stays in javafx-app: `arena_duplicate_targets.xml`, `TestSessionCanvasManagerReplay`, `TestCanvasManagerSessionRecording` (they need `SessionCanvasManager`/`CanvasManager`)

**Interfaces:**
- Consumes: `TargetSet.indexOf/get`, `PlacedTarget.getPosition/getSize/getDefinition`, `TargetId` (Task 2); `TargetView.getTargetSet/getPlacedTarget` (Task 5); core `com.shootoff.camera.Shot`.
- Produces (in `core`):
  - `record com.shootoff.session.TargetRef(TargetSet targets, TargetId id)`: `index()` (-1 if absent), `target() → Optional<PlacedTarget>`, `file() → Optional<File>` (empty if absent or file-less)
  - `SessionRecorder`:
    - `recordShot(String camera, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload, Optional<TargetRef> target, Optional<Integer> hitRegionIndex, Optional<String> videoString)`
    - `recordTargetAdded/Removed(String, TargetRef)`, `recordTargetResized(String, TargetRef, double, double)`, `recordTargetMoved(String, TargetRef, int, int)`
    - unchanged: `recordExerciseFeedMessage(String)`, `getEvents()`, `getCameraEvents(String)`, `addEvents(Map)`, `getSessionName()`
    - the same guards as before: file-less targets are never recorded; moves, resizes and removals of a target not in its set are ignored
  - `ShotEvent(String camera, long timestamp, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload, Optional<Integer> targetIndex, Optional<Integer> hitRegionIndex, Optional<String> videoString)`; `getShot() → Shot`, `getMarkerRadius() → int`, other getters unchanged
  - `EventVisitor.visitShot(long timestamp, Shot shot, int markerRadius, boolean, boolean, Optional<Integer>, Optional<Integer>, Optional<String>)`
  - `SessionColors.paintString(ShotColor)` (`"0xff0000ff"`, `"0x008000ff"`, `"0xffa500ff"`); `SessionColors.parse(String) → ShotColor` (paint strings or names; anything else is green, as before)
- Produces (javafx-app):
  - `TargetView.getTargetRef() → TargetRef`
  - `SessionCanvasManager.getMarker(ShotEvent) → DisplayShot`: one marker per event, made on first use

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/com/shootoff/session/io/TestSessionFormat.java`:

```java
package com.shootoff.session.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.session.Event;
import com.shootoff.session.SessionRecorder;
import com.shootoff.session.ShotEvent;

class TestSessionFormat {
	@TempDir Path temp;

	private static SessionRecorder twoShots() {
		final List<Event> events = new ArrayList<>();
		events.add(new ShotEvent("Default", 5, new Shot(ShotColor.RED, 10, 11.5, 3), 2, false, true, Optional.of(0),
				Optional.of(1), Optional.of("cam:a/b.mp4")));
		events.add(new ShotEvent("Default", 6, new Shot(ShotColor.INFRARED, 1, 2, 4), 5, true, false,
				Optional.empty(), Optional.empty(), Optional.empty()));

		final SessionRecorder recorder = new SessionRecorder();
		recorder.addEvents(Map.of("Default", events));
		return recorder;
	}

	@Test
	void xmlShotsKeepTheirFormat() throws IOException {
		final String sessions = System.getProperty("shootoff.sessions");
		System.setProperty("shootoff.sessions", temp.toString());

		try {
			final File file = temp.resolve("session.xml").toFile();
			SessionIO.saveSession(twoShots(), file);

			// With videos the color is JavaFX's paint string, without them the color's name, as ever
			assertEquals(List.of("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<session>",
					"\t<camera name=\"Default\">",
					"\t\t<shot timestamp=\"5\" color=\"0xff0000ff\" x=\"10.000000\" y=\"11.500000\" shotTimestamp=\"3\""
							+ " markerRadius=\"2\" isMalfunction=\"false\" isReload=\"true\" targetIndex=\"0\""
							+ " hitRegionIndex=\"1\" videos=\"cam:a/b.mp4\" />",
					"\t\t<shot timestamp=\"6\" color=\"INFRARED\" x=\"1.000000\" y=\"2.000000\" shotTimestamp=\"4\""
							+ " markerRadius=\"5\" isMalfunction=\"true\" isReload=\"false\" targetIndex=\"-1\""
							+ " hitRegionIndex=\"-1\" />",
					"\t</camera>", "</session>"), Files.readAllLines(file.toPath()));
		} finally {
			if (sessions == null) {
				System.clearProperty("shootoff.sessions");
			} else {
				System.setProperty("shootoff.sessions", sessions);
			}
		}
	}

	@Test
	void jsonShotsKeepTheJavaFxColorStrings() throws IOException {
		final File file = temp.resolve("session.json").toFile();
		SessionIO.saveSession(twoShots(), file);

		final JsonArray events;
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
			events = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cameras").get(0)
					.getAsJsonObject().getAsJsonArray("events");
		}

		final JsonObject red = events.get(0).getAsJsonObject();
		assertEquals("0xff0000ff", red.get("color").getAsString());
		assertEquals(2, red.get("markerRadius").getAsInt());
		assertEquals("cam:a/b.mp4", red.get("videos").getAsString());

		final JsonObject infrared = events.get(1).getAsJsonObject();
		assertEquals("0xffa500ff", infrared.get("color").getAsString());
		assertEquals(5, infrared.get("markerRadius").getAsInt());
		assertEquals(-1, infrared.get("targetIndex").getAsInt());
		assertFalse(infrared.has("videos"));
	}
}
```

Create `javafx-app/src/test/java/com/shootoff/session/io/TestSessionColors.java`:

```java
package com.shootoff.session.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;

class TestSessionColors {
	@Test
	void paintStringsMatchJavaFxColors() {
		for (final ShotColor color : ShotColor.values()) {
			// The strings JavaFX's Color.toString() gave the markers, which session files contain
			assertEquals(DisplayShot.toPaint(color).toString(), SessionColors.paintString(color));
			assertEquals(color, SessionColors.parse(SessionColors.paintString(color)));
			assertEquals(color, SessionColors.parse(color.name()));
		}
	}
}
```

In `javafx-app/src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java`, replace the tests `shotOnUnknownTargetStillShowsMarker` and `redoingAnEventAfterUndoDoesNotDuplicateNodes` with:

```java
	@Test
	void shotOnUnknownTargetStillShowsMarker() {
		final ShotEvent event = new ShotEvent("arena", 0, new Shot(ShotColor.RED, 5, 5, 0), 2, false, false,
				Optional.of(7), Optional.of(0), Optional.empty());

		assertDoesNotThrow(() -> viewer.doEvent(event));
		final Node marker = viewer.getMarker(event).getMarker();
		assertTrue(canvas.getChildren().contains(marker));

		assertDoesNotThrow(() -> viewer.undoEvent(event));
		assertTrue(!canvas.getChildren().contains(marker));
	}

	@Test
	void redoingAnEventAfterUndoDoesNotDuplicateNodes() {
		final ShotEvent event = new ShotEvent("arena", 0, new Shot(ShotColor.RED, 5, 5, 0), 2, false, false,
				Optional.empty(), Optional.empty(), Optional.empty());

		viewer.doEvent(event);
		assertDoesNotThrow(() -> viewer.doEvent(event)); // re-applied without an undo
		final Node marker = viewer.getMarker(event).getMarker();
		final long markers = canvas.getChildren().stream().filter((Node n) -> n == marker).count();
		assertEquals(1, markers);
	}

	@Test
	void replaysLegacyJsonSession() {
		final Optional<SessionRecorder> session = SessionIO
				.loadSession(new File("core/src/test/resources/sessions/legacy_session.json"));
		assertTrue(session.isPresent());

		for (final String camera : session.get().getEvents().keySet()) {
			final SessionCanvasManager cameraViewer = new SessionCanvasManager(new Group(), config);
			final List<Event> events = session.get().getCameraEvents(camera);
			assertTrue(!events.isEmpty());

			assertDoesNotThrow(() -> events.forEach(cameraViewer::doEvent));
			final List<Event> reversed = new ArrayList<>(events);
			Collections.reverse(reversed);
			assertDoesNotThrow(() -> reversed.forEach(cameraViewer::undoEvent));
		}
	}
```

and remove its now-unused import `com.shootoff.camera.shot.DisplayShot`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:compileTestJava :javafx-app:compileTestJava --console=plain`
Expected: FAIL. `core` reports `package com.shootoff.session does not exist`, and `javafx-app` reports `cannot find symbol SessionColors` and `getMarker`.

- [ ] **Step 3: Move the session code and its tests**

```bash
cd /home/bfears/projects/ShootOFF
move() { mkdir -p "$(dirname "$2")"; git mv "$1" "$2"; }
for f in $(git ls-files javafx-app/src/main/java/com/shootoff/session); do
	move "$f" "core/${f#javafx-app/}"
done
move javafx-app/src/test/java/com/shootoff/session/TestSessionRecorder.java core/src/test/java/com/shootoff/session/TestSessionRecorder.java
move javafx-app/src/test/java/com/shootoff/session/io/TestSessionIO.java core/src/test/java/com/shootoff/session/io/TestSessionIO.java
move javafx-app/src/test/resources/sessions/legacy_session.json core/src/test/resources/sessions/legacy_session.json
git ls-files core/src/main/java/com/shootoff/session | wc -l
```

Expected: `15` files. `javafx-app/src/test/resources/sessions/arena_duplicate_targets.xml` stays where it is.

- [ ] **Step 4: Rewrite the session code without JavaFX**

Create each new file with the GPL header followed by the code below. In a rewritten file, keep its existing license header and replace everything after it.

`core/src/main/java/com/shootoff/session/TargetRef.java` (new):

```java
package com.shootoff.session;

import java.io.File;
import java.util.Optional;

import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.TargetId;
import com.shootoff.targets.model.TargetSet;

/**
 * How session events refer to a target: the set it is in (its canvas's targets) and its id there.
 * Events store the target's index in the set at the moment they are recorded.
 */
public record TargetRef(TargetSet targets, TargetId id) {
	/**
	 * @return the target's index in its set, or -1 if it isn't in the set
	 */
	public int index() {
		return targets.indexOf(id);
	}

	public Optional<PlacedTarget> target() {
		return targets.get(id);
	}

	/**
	 * @return the target's file; empty if the target isn't in its set or has no file (e.g. the
	 *         manual calibration rectangle)
	 */
	public Optional<File> file() {
		return target().flatMap(t -> t.getDefinition().file());
	}
}
```

`core/src/main/java/com/shootoff/session/SessionRecorder.java` (rewrite after the license header):

```java
package com.shootoff.session;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.shootoff.camera.Shot;
import com.shootoff.geom.Point;
import com.shootoff.geom.Size;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.TargetId;

public class SessionRecorder {
	private final long startTime;
	private final String sessionName;
	private final Map<String, List<Event>> events = new HashMap<>();
	private final Map<String, Set<TargetId>> seenTargets = new HashMap<>();
	private final AtomicBoolean ignoreTargetCheck = new AtomicBoolean(false);

	public SessionRecorder() {
		final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss");
		sessionName = dateFormat.format(new Date());
		startTime = System.currentTimeMillis();
	}

	public void addEvents(Map<String, List<Event>> events) {
		this.events.putAll(events);
	}

	public Map<String, List<Event>> getEvents() {
		return events;
	}

	public String getSessionName() {
		return sessionName;
	}

	public List<Event> getCameraEvents(String cameraName) {
		if (events.containsKey(cameraName)) {
			return events.get(cameraName);
		} else {
			final List<Event> eventList = new ArrayList<>();
			events.put(cameraName, eventList);
			return eventList;
		}
	}

	// This method ensures we have an add event for a target that is being used,
	// if not the user started recording after adding the target so we should
	// artificially add the target add event then ensure it gets moved and
	// resized to wherever it already is and to however big it already is.
	private void checkTarget(String cameraName, TargetRef target) {
		final Optional<PlacedTarget> placed = target.target();
		if (placed.isEmpty() || placed.get().getDefinition().file().isEmpty()) return;

		if (!seenTargets.computeIfAbsent(cameraName, name -> new HashSet<>()).contains(target.id())) {
			ignoreTargetCheck.set(true);
			recordTargetAdded(cameraName, target);
			final Point p = placed.get().getPosition();
			recordTargetMoved(cameraName, target, (int) p.getX(), (int) p.getY());
			final Size d = placed.get().getSize();
			recordTargetResized(cameraName, target, d.getWidth(), d.getHeight());
			ignoreTargetCheck.set(false);
		}
	}

	/**
	 * @param markerRadius
	 *            the radius of the shot's marker, which session files store
	 */
	public void recordShot(String cameraName, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload,
			Optional<TargetRef> target, Optional<Integer> hitRegionIndex, Optional<String> videoString) {
		Optional<Integer> targetIndex = Optional.empty();
		if (target.isPresent()) {
			targetIndex = Optional.of(target.get().index());
			if (!ignoreTargetCheck.get()) checkTarget(cameraName, target.get());
		}

		final long timestamp = System.currentTimeMillis() - startTime;

		getCameraEvents(cameraName).add(new ShotEvent(cameraName, timestamp, shot, markerRadius, isMalfunction,
				isReload, targetIndex, hitRegionIndex, videoString));
	}

	public void recordTargetAdded(String cameraName, TargetRef target) {
		final Optional<File> file = target.file();
		if (file.isEmpty()) return;

		seenTargets.computeIfAbsent(cameraName, name -> new HashSet<>()).add(target.id());

		String targetName;
		if (file.get().isAbsolute()) {
			targetName = file.get().getPath()
					.replace(System.getProperty("shootoff.home") + File.separator + "targets" + File.separator, "");
		} else {
			targetName = file.get().getPath().replace("targets" + File.separator, "");
		}

		getCameraEvents(cameraName)
				.add(new TargetAddedEvent(cameraName, System.currentTimeMillis() - startTime, targetName));
	}

	public void recordTargetRemoved(String cameraName, TargetRef target) {
		// A target that isn't in its set has no index to replay against, and one
		// without a file (e.g. the manual calibration rectangle) isn't a session target
		if (target.index() < 0 || target.file().isEmpty()) return;

		if (!ignoreTargetCheck.get()) checkTarget(cameraName, target);

		getCameraEvents(cameraName)
				.add(new TargetRemovedEvent(cameraName, System.currentTimeMillis() - startTime, target.index()));
	}

	private void collapseTargetEvents(String cameraName, EventType type, int targetIndex) {
		final ListIterator<Event> it = getCameraEvents(cameraName).listIterator(getCameraEvents(cameraName).size());

		while (it.hasPrevious()) {
			final Event e = it.previous();

			if (e.getType() != EventType.TARGET_RESIZED && e.getType() != EventType.TARGET_MOVED) {
				break;
			}

			if (e.getType() == type) {
				if (type == EventType.TARGET_RESIZED && ((TargetResizedEvent) e).getTargetIndex() == targetIndex) {
					it.remove();
				} else if (type == EventType.TARGET_MOVED && ((TargetMovedEvent) e).getTargetIndex() == targetIndex) {
					it.remove();
				}
			}
		}
	}

	public void recordTargetResized(String cameraName, TargetRef target, double newWidth, double newHeight) {
		// A target that isn't in its set has no index to replay against, and one
		// without a file (e.g. the manual calibration rectangle) isn't a session target
		if (target.index() < 0 || target.file().isEmpty()) return;

		if (!ignoreTargetCheck.get()) checkTarget(cameraName, target);

		// Remove all resize events immediately before this one
		collapseTargetEvents(cameraName, EventType.TARGET_RESIZED, target.index());

		getCameraEvents(cameraName).add(new TargetResizedEvent(cameraName, System.currentTimeMillis() - startTime,
				target.index(), newWidth, newHeight));
	}

	public void recordTargetMoved(String cameraName, TargetRef target, int newX, int newY) {
		// A target that isn't in its set has no index to replay against, and one
		// without a file (e.g. the manual calibration rectangle) isn't a session target
		if (target.index() < 0 || target.file().isEmpty()) return;

		if (!ignoreTargetCheck.get()) checkTarget(cameraName, target);

		// Remove all move events immediately before this one
		collapseTargetEvents(cameraName, EventType.TARGET_MOVED, target.index());

		getCameraEvents(cameraName).add(new TargetMovedEvent(cameraName, System.currentTimeMillis() - startTime,
				target.index(), newX, newY));
	}

	public void recordExerciseFeedMessage(String message) {
		// Add an event for this message to each camera
		for (final String cameraName : seenTargets.keySet()) {
			getCameraEvents(cameraName)
					.add(new ExerciseFeedMessageEvent(cameraName, System.currentTimeMillis() - startTime, message));
		}
	}
}
```

`core/src/main/java/com/shootoff/session/ShotEvent.java` (rewrite after the license header):

```java
package com.shootoff.session;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;

public class ShotEvent implements Event {
	private final String cameraName;
	private final long timestamp;
	private final Shot shot;
	private final int markerRadius;
	private final boolean isMalfunction;
	private final boolean isReload;
	private final Optional<Integer> targetIndex;
	private final Optional<Integer> hitRegionIndex;
	private final Optional<String> videoString;
	private final Map<String, File> videos = new HashMap<>();

	public ShotEvent(String cameraName, long timestamp, Shot shot, int markerRadius, boolean isMalfunction,
			boolean isReload, Optional<Integer> targetIndex, Optional<Integer> hitRegionIndex,
			Optional<String> videoString) {
		this.cameraName = cameraName;
		this.timestamp = timestamp;
		this.shot = shot;
		this.markerRadius = markerRadius;
		this.isMalfunction = isMalfunction;
		this.isReload = isReload;
		this.targetIndex = targetIndex;
		this.hitRegionIndex = hitRegionIndex;
		this.videoString = videoString;

		if (videoString.isPresent()) {
			final String[] videoSet = videoString.get().split(",");

			for (final String video : videoSet) {
				final String[] v = video.split(":");
				videos.put(v[0], new File("sessions" + File.separator + v[1]));
			}
		}
	}

	@Override
	public String getCameraName() {
		return cameraName;
	}

	public Shot getShot() {
		return shot;
	}

	/**
	 * @return the radius of the shot's marker when it was recorded
	 */
	public int getMarkerRadius() {
		return markerRadius;
	}

	public boolean isMalfunction() {
		return isMalfunction;
	}

	public boolean isReload() {
		return isReload;
	}

	public Optional<Integer> getTargetIndex() {
		return targetIndex;
	}

	public Optional<Integer> getHitRegionIndex() {
		return hitRegionIndex;
	}

	public Optional<String> getVideoString() {
		return videoString;
	}

	public Map<String, File> getVideos() {
		return videos;
	}

	@Override
	public EventType getType() {
		return EventType.SHOT;
	}

	@Override
	public long getTimestamp() {
		return timestamp;
	}

	@Override
	public String toString() {
		String colorName;

		if (shot.getColor().equals(ShotColor.RED)) {
			colorName = "red";
		} else if (shot.getColor().equals(ShotColor.INFRARED)) {
			colorName = "infrared";
		} else {
			colorName = "green";
		}

		return String.format("%s shot (%.2f, %.2f)", colorName, shot.getX(), shot.getY());
	}
}
```

`core/src/main/java/com/shootoff/session/io/EventVisitor.java` (rewrite after the license header):

```java
package com.shootoff.session.io;

import java.util.Optional;

import com.shootoff.camera.Shot;

public interface EventVisitor {
	public void visitCamera(String cameraName);

	public void visitCameraEnd();

	public void visitShot(long timestamp, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload,
			Optional<Integer> targetIndex, Optional<Integer> hitRegionIndex, Optional<String> videoString);

	public void visitTargetAdd(long timestamp, String targetName);

	public void visitTargetRemove(long timestamp, int targetIndex);

	public void visitTargetResize(long timestamp, int targetIndex, double newWidth, double newHeight);

	public void visitTargetMove(long timestamp, int targetIndex, int newX, int newY);

	public void visitExerciseFeedMessage(long timestamp, String message);

	public void visitEnd();
}
```

`core/src/main/java/com/shootoff/session/io/SessionColors.java` (new):

```java
package com.shootoff.session.io;

import com.shootoff.camera.shot.ShotColor;

/**
 * Shot colors as session files hold them. Files have always stored the JavaFX marker color's
 * Color.toString() (red, green, and orange for infrared), and XML files without videos the
 * ShotColor's name.
 */
public final class SessionColors {
	private SessionColors() {}

	public static String paintString(ShotColor color) {
		return switch (color) {
		case RED -> "0xff0000ff";
		case GREEN -> "0x008000ff";
		case INFRARED -> "0xffa500ff";
		};
	}

	/**
	 * Reads a color written by any ShotOFF version: a paint string or a ShotColor name. Anything
	 * else is green, as it always was.
	 */
	public static ShotColor parse(String color) {
		if ("0xff0000ff".equals(color) || "RED".equals(color)) {
			return ShotColor.RED;
		} else if ("0xffa500ff".equals(color) || "INFRARED".equals(color)) {
			return ShotColor.INFRARED;
		} else {
			return ShotColor.GREEN;
		}
	}
}
```

`core/src/main/java/com/shootoff/session/io/SessionIO.java`: in `saveSession`, replace

```java
					visitor.visitShot(se.getTimestamp(), se.getShot(), se.isMalfunction(), se.isReload(),
```

with

```java
					visitor.visitShot(se.getTimestamp(), se.getShot(), se.getMarkerRadius(), se.isMalfunction(), se.isReload(),
```

`core/src/main/java/com/shootoff/session/io/XMLSessionWriter.java`: replace `import com.shootoff.camera.shot.DisplayShot;` with `import com.shootoff.camera.Shot;`, and replace the whole `visitShot` method with:

```java
	@Override
	public void visitShot(long timestamp, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload,
			Optional<Integer> targetIndex, Optional<Integer> hitRegionIndex, Optional<String> videoString) {

		int targIndex;
		if (targetIndex.isPresent()) {
			targIndex = targetIndex.get();
		} else {
			targIndex = -1;
		}

		int hitRegIndex;
		if (hitRegionIndex.isPresent()) {
			hitRegIndex = hitRegionIndex.get();
		} else {
			hitRegIndex = -1;
		}

		if (videoString.isPresent()) {
			xmlBody.append(String.format(Locale.US,
					"\t\t<shot timestamp=\"%d\" color=\"%s\""
							+ " x=\"%f\" y=\"%f\" shotTimestamp=\"%d\" markerRadius=\"%d\" isMalfunction=\"%b\""
							+ " isReload=\"%b\" targetIndex=\"%d\" hitRegionIndex=\"%d\" videos=\"%s\" />%n",
					timestamp, SessionColors.paintString(shot.getColor()), shot.getX(), shot.getY(),
					shot.getTimestamp(), markerRadius, isMalfunction, isReload, targIndex, hitRegIndex,
					videoString.get()));
		} else {
			xmlBody.append(String.format(Locale.US,
					"\t\t<shot timestamp=\"%d\" color=\"%s\""
							+ " x=\"%f\" y=\"%f\" shotTimestamp=\"%d\" markerRadius=\"%d\" isMalfunction=\"%b\""
							+ " isReload=\"%b\" targetIndex=\"%d\" hitRegionIndex=\"%d\" />%n",
					timestamp, shot.getColor().toString(), shot.getX(), shot.getY(), shot.getTimestamp(),
					markerRadius, isMalfunction, isReload, targIndex, hitRegIndex));
		}
	}
```

`core/src/main/java/com/shootoff/session/io/JSONSessionWriter.java`: replace `import com.shootoff.camera.shot.DisplayShot;` with `import com.shootoff.camera.Shot;`, and replace the whole `visitShot` method with:

```java
	@Override
	public void visitShot(long timestamp, Shot shot, int markerRadius, boolean isMalfunction, boolean isReload,
			Optional<Integer> targetIndex, Optional<Integer> hitRegionIndex, Optional<String> videoString) {
		final JsonObject event = new JsonObject();
		event.addProperty("type", "shot");
		event.addProperty("timestamp", timestamp);
		event.addProperty("color", SessionColors.paintString(shot.getColor()));
		event.addProperty("x", shot.getX());
		event.addProperty("y", shot.getY());
		event.addProperty("shotTimestamp", shot.getTimestamp());
		event.addProperty("markerRadius", markerRadius);
		event.addProperty("isMalfunction", isMalfunction);
		event.addProperty("isReload", isReload);
		event.addProperty("targetIndex", targetIndex.orElse(-1));
		event.addProperty("hitRegionIndex", hitRegionIndex.orElse(-1));
		if (videoString.isPresent()) {
			event.addProperty("videos", videoString.get());
		}
		currentCameraEvents.add(event);
	}
```

`core/src/main/java/com/shootoff/session/io/XMLSessionReader.java`: replace the imports `com.shootoff.camera.shot.DisplayShot` and `com.shootoff.camera.shot.ShotColor` with `com.shootoff.camera.Shot`. In `startElement`, replace everything from `case "shot":` up to (not including) `case "targetAdded":` with:

```java
			case "shot":
				final Shot shot = new Shot(SessionColors.parse(attributes.getValue("color")),
						Double.parseDouble(attributes.getValue("x")), Double.parseDouble(attributes.getValue("y")),
						Long.parseLong(attributes.getValue("shotTimestamp")));
				final int markerRadius = Integer.parseInt(attributes.getValue("markerRadius"));

				final boolean isMalfunction = Boolean.parseBoolean(attributes.getValue("isMalfunction"));

				final boolean isReload = Boolean.parseBoolean(attributes.getValue("isReload"));

				Optional<Integer> targetIndex;
				int index = Integer.parseInt(attributes.getValue("targetIndex"));
				if (index == -1) {
					targetIndex = Optional.empty();
				} else {
					targetIndex = Optional.of(index);
				}

				Optional<Integer> hitRegionIndex;
				index = Integer.parseInt(attributes.getValue("hitRegionIndex"));
				if (index == -1) {
					hitRegionIndex = Optional.empty();
				} else {
					hitRegionIndex = Optional.of(index);
				}

				final Optional<String> videoString = Optional.ofNullable(attributes.getValue("videos"));

				events.get(currentCameraName)
						.add(new ShotEvent(currentCameraName, Long.parseLong(attributes.getValue("timestamp")), shot,
								markerRadius, isMalfunction, isReload, targetIndex, hitRegionIndex, videoString));

				break;
```

`core/src/main/java/com/shootoff/session/io/JSONSessionReader.java`: replace the imports `com.shootoff.camera.shot.DisplayShot` and `com.shootoff.camera.shot.ShotColor` with `com.shootoff.camera.Shot`. Replace the `case "shot":` block (up to its `break;`) with:

```java
					case "shot":
						final Shot shot = new Shot(SessionColors.parse(event.get("color").getAsString()),
								event.get("x").getAsDouble(), event.get("y").getAsDouble(),
								event.get("shotTimestamp").getAsLong());
						final Optional<String> videoString = event.has("videos")
								? Optional.of(event.get("videos").getAsString()) : Optional.empty();
						cameraEvents.add(new ShotEvent(cameraName, timestamp, shot,
								event.get("markerRadius").getAsInt(), event.get("isMalfunction").getAsBoolean(),
								event.get("isReload").getAsBoolean(), optionalIndex(event, "targetIndex"),
								optionalIndex(event, "hitRegionIndex"), videoString));
						break;
```

Delete its `parseColor` method (its comment and body). `SessionColors.parse` does the same.

Check that nothing in the session code references JavaFX or the JavaFX app:

```bash
command grep -rn "javafx\|DisplayShot\|com.shootoff.gui\|targets.Target\b" core/src/main/java/com/shootoff/session
```

Expected: nothing.

- [ ] **Step 5: Adapt the moved tests**

`core/src/test/java/com/shootoff/session/TestSessionRecorder.java`:
1. Replace everything from the first `import` line through the closing brace of `setUp()` with:

```java
import static org.junit.Assert.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.targets.model.PlacedTarget;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetSet;

public class TestSessionRecorder {
	private static final int MARKER_RADIUS = 2;

	private SessionRecorder sessionRecorder;
	private String cameraName;
	private Shot shot;
	private String targetName1;
	private TargetRef target1;
	private int targetIndex1;
	private String targetName2;
	private TargetRef target2;
	private int hitRegionIndex;
	private String exerciseMessage;

	private static TargetRef addTarget(TargetSet targets, String fileName) {
		final PlacedTarget placed = targets
				.add(new TargetDefinition(Optional.of(new File(fileName)), Map.of(), List.of()));
		return new TargetRef(targets, placed.getId());
	}

	@Before
	public void setUp() {
		sessionRecorder = new SessionRecorder();
		cameraName = "Default";
		shot = new Shot(ShotColor.RED, 0, 0, 0);

		final TargetSet targets = new TargetSet();
		targetName1 = "bullseye.target";
		target1 = addTarget(targets, targetName1);

		targetName2 = "shoot_dont_shoot" + File.separator + " shoot.target";
		target2 = addTarget(targets, targetName2);

		targetIndex1 = target1.index();

		hitRegionIndex = 0;
		exerciseMessage = "This is a test";
	}
```

2. Replace the whole `testUnregisteredTargetEventsAreIgnored` method with:

```java
	@Test
	public void testUnregisteredTargetEventsAreIgnored() {
		final TargetSet otherTargets = new TargetSet();
		final TargetRef unregistered = addTarget(otherTargets, "unregistered.target");
		otherTargets.remove(unregistered.id());

		sessionRecorder.recordTargetMoved(cameraName, unregistered, 1, 2);
		sessionRecorder.recordTargetResized(cameraName, unregistered, 3, 4);
		sessionRecorder.recordTargetRemoved(cameraName, unregistered);

		assertTrue(sessionRecorder.getCameraEvents(cameraName).isEmpty());
	}
```

3. Pass the marker radius in every `recordShot` call:

```bash
perl -pi -e 's/recordShot\(cameraName, shot, /recordShot(cameraName, shot, MARKER_RADIUS, /g' core/src/test/java/com/shootoff/session/TestSessionRecorder.java
```

`core/src/test/java/com/shootoff/session/io/TestSessionIO.java`:
1. Replace the imports of `com.shootoff.camera.shot.DisplayShot`, `com.shootoff.config.Configuration`, `com.shootoff.config.ConfigurationException`, `com.shootoff.gui.MockCanvasManager`, `com.shootoff.gui.targets.TargetView`, `com.shootoff.targets.io.TargetIO.TargetComponents` and `javafx.scene.Group` with:

   ```java
   import com.shootoff.session.TargetRef;
   import com.shootoff.targets.model.TargetDefinition;
   import com.shootoff.targets.model.TargetSet;
   ```

2. Replace everything from `public class TestSessionIO {` through the closing brace of `setUp()` with:

```java
public class TestSessionIO {
	private static final File LEGACY_SESSION = new File("core/src/test/resources/sessions/legacy_session.json");
	private static final int RED_MARKER_RADIUS = 2;
	private static final int GREEN_MARKER_RADIUS = 5;

	private SessionRecorder sessionRecorder;
	private String cameraName1;
	private String cameraName2;
	private String videoString;
	private Shot redShot;
	private Shot greenShot;
	private String targetName;
	private int hitRegionIndex;
	private String exerciseMessage;

	@Before
	public void setUp() {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		System.setProperty("shootoff.sessions", System.getProperty("shootoff.home") + File.separator + "sessions");

		sessionRecorder = new SessionRecorder();
		cameraName1 = "Default";
		cameraName2 = "Another Camera";
		videoString = "camera1:test/file.mp4,camera2:what/ax.vid";
		redShot = new Shot(ShotColor.RED, 10, 11, 3);
		greenShot = new Shot(ShotColor.GREEN, 12, 15, 3);
		targetName = "bullseye.target";
		exerciseMessage = "This is a\n\t test";

		final TargetSet targets = new TargetSet();
		final TargetRef target = new TargetRef(targets,
				targets.add(new TargetDefinition(Optional.of(new File(targetName)), Map.of(), List.of())).getId());

		hitRegionIndex = 0;

		sessionRecorder.recordTargetAdded(cameraName1, target);
		sessionRecorder.recordTargetAdded(cameraName2, target);
		sessionRecorder.recordTargetResized(cameraName1, target, 10, 20);
		sessionRecorder.recordTargetMoved(cameraName1, target, 4, 3);
		sessionRecorder.recordShot(cameraName1, redShot, RED_MARKER_RADIUS, false, false, Optional.of(target),
				Optional.of(hitRegionIndex), Optional.of(videoString));
		sessionRecorder.recordShot(cameraName1, greenShot, GREEN_MARKER_RADIUS, true, false, Optional.of(target),
				Optional.of(hitRegionIndex), Optional.of(videoString));
		sessionRecorder.recordTargetRemoved(cameraName1, target);
		sessionRecorder.recordShot(cameraName1, greenShot, GREEN_MARKER_RADIUS, false, true, Optional.empty(),
				Optional.empty(), Optional.empty());
		sessionRecorder.recordExerciseFeedMessage(exerciseMessage);
	}
```

3. The three marker-radius assertions read the event's radius now:

```bash
perl -0pi -e 's/assertEquals\((red|green)Shot\.getMarker\(\)\.getRadiusX\(\),\s*\(\(ShotEvent\) events\.get\((\w+)\)\)\.getShot\(\)\.getMarker\(\)\.getRadiusX\(\), 1\);/assertEquals(\U$1\E_MARKER_RADIUS, ((ShotEvent) events.get($2)).getMarkerRadius());/g' \
  core/src/test/java/com/shootoff/session/io/TestSessionIO.java
command grep -n "getMarker\|MARKER_RADIUS, ((ShotEvent" core/src/test/java/com/shootoff/session/io/TestSessionIO.java
```

Expected: three `assertEquals(RED_MARKER_RADIUS|GREEN_MARKER_RADIUS, ((ShotEvent) …).getMarkerRadius());` lines and no `getMarker()`.

- [ ] **Step 6: Record sessions through the model in the JavaFX app**

`javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java`:
1. Add `import com.shootoff.session.TargetRef;`.
2. Add after `getPlacement()`:

   ```java
   	/**
   	 * @return how session events refer to this target: its set and its id there
   	 */
   	public TargetRef getTargetRef() {
   		final Membership m = membership;
   		return new TargetRef(m.set(), m.placed().getId());
   	}
   ```

3. Pass the reference instead of the view to the recorder:

   ```bash
   perl -pi -e 's/(recordTarget(?:Moved|Resized))\(cameraName, this, /$1(cameraName, getTargetRef(), /g' \
     javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java
   command grep -n "cameraName, this" javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java
   ```

   Expected from the `grep`: nothing.

`javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java`:
1. Add `import com.shootoff.session.TargetRef;`.
2. Every shot is recorded with its marker's radius:

   ```bash
   perl -pi -e 's/recordShot\(cameraName, shot, /recordShot(cameraName, shot, markerRadius(shot), /g' \
     javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java
   ```

   and add the helper after `recordRejectedShot`:

   ```java
   	// Session files store each shot's marker radius
   	private static int markerRadius(DisplayShot shot) {
   		return (int) shot.getMarker().getRadiusX();
   	}
   ```

3. In `checkHit`, replace `Optional.of(target.get()), Optional.of(modelHit.get().region().index())` with `Optional.of(target.get().getTargetRef()), Optional.of(modelHit.get().region().index())`.
4. In `addTarget(Target newTarget)`, replace

   ```java
   			final SessionRecorder recorder = config.getSessionRecorder().get();
   			recorder.recordTargetAdded(cameraName, newTarget);
   			final Point2D position = newTarget.getPosition();
   			recorder.recordTargetMoved(cameraName, newTarget, (int) position.getX(), (int) position.getY());
   			final Dimension2D dimension = newTarget.getDimension();
   			recorder.recordTargetResized(cameraName, newTarget, dimension.getWidth(), dimension.getHeight());
   ```

   with

   ```java
   			final SessionRecorder recorder = config.getSessionRecorder().get();
   			final TargetRef ref = ((TargetView) newTarget).getTargetRef();
   			recorder.recordTargetAdded(cameraName, ref);
   			final Point2D position = newTarget.getPosition();
   			recorder.recordTargetMoved(cameraName, ref, (int) position.getX(), (int) position.getY());
   			final Dimension2D dimension = newTarget.getDimension();
   			recorder.recordTargetResized(cameraName, ref, dimension.getWidth(), dimension.getHeight());
   ```

5. In `removeTarget`, replace `recordTargetRemoved(cameraName, target);` with `recordTargetRemoved(cameraName, ((TargetView) target).getTargetRef());`.

`javafx-app/src/main/java/com/shootoff/gui/SessionCanvasManager.java`:
1. Add `import com.shootoff.camera.shot.DisplayShot;`.
2. After `private final Set<Event> skippedAnimations = new HashSet<>();` add:

   ```java
   	private final Map<ShotEvent, DisplayShot> markers = new HashMap<>();
   ```

3. Add this method before `doEvent`:

   ```java
   	/**
   	 * @return the marker this viewer shows for a shot event, made the first time it is needed
   	 */
   	public DisplayShot getMarker(ShotEvent event) {
   		return markers.computeIfAbsent(event, e -> new DisplayShot(e.getShot(), e.getMarkerRadius()));
   	}
   ```

4. Make every use of a shot's marker go through it:

   ```bash
   perl -pi -e 's/se\.getShot\(\)\.getMarker\(\)/getMarker(se).getMarker()/g' javafx-app/src/main/java/com/shootoff/gui/SessionCanvasManager.java
   command grep -c "getMarker(se).getMarker()" javafx-app/src/main/java/com/shootoff/gui/SessionCanvasManager.java
   ```

   Expected: `6`. The six uses are: add to canvas, the malfunction fill, the reload fill, `setVisible`, `setOnMouseClicked`, and the removal in `undoEvent`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.session.*' --tests 'com.shootoff.TestNoJavaFxInCore' :javafx-app:test --tests 'com.shootoff.gui.*' --tests 'com.shootoff.session.*' --tests 'com.shootoff.plugins.*' --console=plain`
Expected: all pass, including:
- `TestSessionRecorder` (11) and `TestSessionIO` (5) in core, among them `testReadsLegacyJSONSession` and `testRewritingLegacyJSONSessionKeepsItsStructure`
- `TestSessionFormat` (2)
- `TestSessionColors` (1)
- `TestSessionCanvasManagerReplay`, including `replaysRecordedArenaSessionWithDuplicateTargetsAndBadIndexes` (the `arena_duplicate_targets.xml` fixture) and the new `replaysLegacyJsonSession`
- `TestCanvasManagerSessionRecording`
- `TestNoJavaFxInCore`, now scanning the session code

- [ ] **Step 8: Gate and commit**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 4 (**289**). `TestSessionRecorder` and `TestSessionIO` now report from `core/build/test-results`, under the same names.

```bash
git add core/src/main/java/com/shootoff/session core/src/test/java/com/shootoff/session \
  javafx-app/src/test/java/com/shootoff/session/io/TestSessionColors.java \
  javafx-app/src/main/java/com/shootoff/gui/targets/TargetView.java javafx-app/src/main/java/com/shootoff/gui/CanvasManager.java \
  javafx-app/src/main/java/com/shootoff/gui/SessionCanvasManager.java \
  javafx-app/src/test/java/com/shootoff/gui/TestSessionCanvasManagerReplay.java
git status --short | command grep -v '^R  '
git commit -m "Move sessions into core, recording targets by TargetSet and id"
git log -1 --format=%B
```

The filtered status must show only ` M shootoff.properties`.

---
### Task 8: Courses become data and move to `core`

A `Course` holds target files with positions and sizes, a background reference and the arena's size (ruling 15). `CourseIO` reads and writes the unchanged `.course` format without an arena. The arena side stays in javafx-app: `ProjectorArenaPane.getCourse()` describes what is on the arena, and `setCourse(Course)` loads the course's targets onto it.

**Files:**
- Move (`git mv`) `javafx-app/src/main/java/com/shootoff/courses/Course.java` and `courses/io/{CourseIO,CourseVisitor,XMLCourseReader,XMLCourseWriter}.java` to `core/src/main/java/com/shootoff/courses/…`. Then rewrite `Course.java`, `io/CourseIO.java` and `io/XMLCourseReader.java`. `CourseVisitor` and `XMLCourseWriter` are unchanged.
- Create: `core/src/main/java/com/shootoff/courses/CourseTarget.java`, `CourseBackground.java`
- Modify: `core/src/main/java/com/shootoff/config/Settings.java` (add `getUserNotifier()`)
- Modify (javafx-app main):
  - `gui/pane/ProjectorArenaPane.java` (`getCourse`, `setCourse`, `toLocatedImage`)
  - `gui/pane/ArenaCoursesSlide.java`
  - `plugins/ProjectorTrainingExerciseBase.java` (`setCourse`)
  - `plugins/SteelChallenge.java` (`init(List<Target>)`)
- Move and rewrite test: `javafx-app/src/test/java/com/shootoff/courses/io/TestCourseIO.java` → `core/src/test/java/com/shootoff/courses/io/TestCourseIO.java`
- Create (test): `javafx-app/src/test/java/com/shootoff/courses/TestArenaCourse.java`
- Modify (test): `javafx-app/src/test/java/com/shootoff/plugins/TestSteelChallenge.java`

**Interfaces:**
- Consumes: `TargetIO.loadTarget(File)`/`loadTarget(File, boolean)`, `TargetView(TargetComponents, CanvasManager, boolean)`, `TargetView(TargetComponents, List<Target>)`, `CanvasManager.addTarget(Target)` (Tasks 4–5); `Settings.setUserNotifier` (Plan 1); `com.shootoff.geom.Size`.
- Produces (in `core`):
  - `record com.shootoff.courses.CourseTarget(File file, double x, double y, double width, double height)`, where x/y is the target's position and width/height its size
  - `record com.shootoff.courses.CourseBackground(String url, boolean isResource)`
  - `class com.shootoff.courses.Course(Optional<CourseBackground>, List<CourseTarget>, Optional<Size> resolution)`: `getBackground()`, `getTargets()`, `getResolution()`
  - `CourseIO.saveCourse(Course, File)` and `CourseIO.loadCourse(File) → Optional<Course>` (empty for a missing, unreadable or non-`.course` file)
  - `XMLCourseReader(File).load() → Optional<Course>`
  - `static UserNotifier Settings.getUserNotifier()`
- Produces (javafx-app):
  - `Course ProjectorArenaPane.getCourse()`: target files relative to `shootoff.home`, the arena's size, and the background
  - `List<Target> ProjectorArenaPane.setCourse(Course)`: returns the targets now on the arena; missing targets are skipped and reported once
  - `static LocatedImage ProjectorArenaPane.toLocatedImage(CourseBackground)`
  - `SteelChallenge.init(List<Target>)`, a test hook

- [ ] **Step 1: Write the failing tests**

Create `javafx-app/src/test/java/com/shootoff/courses/TestArenaCourse.java`:

```java
package com.shootoff.courses;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.config.Settings;
import com.shootoff.geom.Size;
import com.shootoff.gui.JavaFXThreadingRule;
import com.shootoff.gui.LocatedImage;
import com.shootoff.gui.MockCanvasManager;
import com.shootoff.gui.controller.MockProjectorArenaController;
import com.shootoff.targets.Target;

public class TestArenaCourse {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private static final String BACKGROUND_URL = "/arena/backgrounds/indoor_range.gif";

	private MockProjectorArenaController arenaPane;
	private final List<String> notices = new ArrayList<>();

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		final Configuration config = new Configuration(new String[0]);
		arenaPane = new MockProjectorArenaController(config, new MockCanvasManager(config));
		Settings.setUserNotifier((title, header, message) -> notices.add(message));
	}

	@After
	public void tearDown() {
		Settings.setUserNotifier(null);
	}

	@Test
	public void currentCourseKeepsTargetsBackgroundAndResolution() {
		arenaPane.setArenaBackground(
				new LocatedImage(TestArenaCourse.class.getResourceAsStream(BACKGROUND_URL), BACKGROUND_URL));
		final Target target = arenaPane.getCanvasManager().addTarget(new File("targets/Reset.target")).get();
		target.setPosition(10, 100);
		target.setDimensions(10, 1);

		final Course course = arenaPane.getCourse();

		assertEquals(Optional.of(new CourseBackground(BACKGROUND_URL, true)), course.getBackground());
		assertEquals(1, course.getTargets().size());
		final CourseTarget saved = course.getTargets().get(0);
		assertEquals(new File("targets/Reset.target"), saved.file());
		assertEquals(10, saved.x(), 1);
		assertEquals(100, saved.y(), 1);
		assertEquals(10, saved.width(), 1);
		assertEquals(1, saved.height(), 1);

		// Default arena dimensions (this will fail if you change the dimensions in
		// MockProjectorArenaController)
		assertTrue(course.getResolution().isPresent());
		assertEquals(640, course.getResolution().get().getWidth(), 1);
		assertEquals(360, course.getResolution().get().getHeight(), 1);
	}

	@Test
	public void appliedCourseKeepsEachTargetsPositionAndSize() {
		final List<Target> added = arenaPane.setCourse(new Course(Optional.empty(),
				List.of(new CourseTarget(new File("targets/Reset.target"), 10, 100, 10, 1),
						new CourseTarget(new File("targets/IPSC.target"), 200, 50, 90, 114)),
				Optional.of(new Size(arenaPane.getWidth(), arenaPane.getHeight()))));

		assertEquals(2, added.size());
		assertEquals(added, arenaPane.getCanvasManager().getTargets());
		assertEquals(10, added.get(0).getPosition().getX(), 0.001);
		assertEquals(100, added.get(0).getPosition().getY(), 0.001);
		assertEquals(10, added.get(0).getDimension().getWidth(), 0.001);
		assertEquals(1, added.get(0).getDimension().getHeight(), 0.001);
		assertEquals(200, added.get(1).getPosition().getX(), 0.001);
		assertEquals(90, added.get(1).getDimension().getWidth(), 0.001);
		assertEquals(114, added.get(1).getDimension().getHeight(), 0.001);
		assertTrue(notices.isEmpty());
	}

	@Test
	public void appliedCourseScalesToTheArena() {
		final List<Target> added = arenaPane.setCourse(new Course(Optional.empty(),
				List.of(new CourseTarget(new File("targets/IPSC.target"), 200, 50, 90, 114)),
				Optional.of(new Size(arenaPane.getWidth() * 2, arenaPane.getHeight() * 2))));

		assertEquals(45, added.get(0).getDimension().getWidth(), 0.01);
		assertEquals(57, added.get(0).getDimension().getHeight(), 0.01);
	}

	@Test
	public void missingCourseTargetIsSkippedAndReported() {
		final List<Target> added = arenaPane.setCourse(new Course(Optional.empty(),
				List.of(new CourseTarget(new File("targets/no_such_target.target"), 0, 0, 10, 10),
						new CourseTarget(new File("targets/Reset.target"), 10, 100, 10, 1)),
				Optional.of(new Size(arenaPane.getWidth(), arenaPane.getHeight()))));

		assertEquals(1, added.size());
		assertEquals(1, notices.size());
		assertTrue(notices.get(0), notices.get(0).contains("no_such_target.target"));
	}
}
```

Replace everything after the package line of `javafx-app/src/test/java/com/shootoff/courses/io/TestCourseIO.java` with the core version below. It keeps its three test names (the arena half of the old `testXMLSerialization` is now `TestArenaCourse.currentCourseKeepsTargetsBackgroundAndResolution`) and adds one:

```java
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;

import com.shootoff.courses.Course;
import com.shootoff.courses.CourseBackground;
import com.shootoff.courses.CourseTarget;
import com.shootoff.geom.Size;

public class TestCourseIO {
	private static final String BACKGROUND_URL = "/arena/backgrounds/indoor_range.gif";

	private Course course;

	@Before
	public void setUp() {
		course = new Course(Optional.of(new CourseBackground(BACKGROUND_URL, true)),
				List.of(new CourseTarget(new File("targets/Reset.target"), 10, 100, 10, 1)),
				Optional.of(new Size(640, 360)));
	}

	@Test
	public void testXMLSerialization() {
		final File tempXMLCourse = new File("temp_course.course");
		CourseIO.saveCourse(course, tempXMLCourse);

		final Optional<Course> loaded = CourseIO.loadCourse(tempXMLCourse);

		assertTrue(loaded.isPresent());
		assertEquals(course.getBackground(), loaded.get().getBackground());
		assertEquals(course.getTargets(), loaded.get().getTargets());
		assertEquals(course.getResolution(), loaded.get().getResolution());

		if (!tempXMLCourse.delete()) System.err.println("Failed to delete " + tempXMLCourse.getPath());
	}

	@Test
	public void testCourseDoesntExist() {
		assertEquals(Optional.empty(), CourseIO.loadCourse(new File("does_not_exist.course")));
	}

	@Test
	public void testUnknownCourseExtension() {
		assertEquals(Optional.empty(), CourseIO.loadCourse(new File("does_not_exist.watisthis")));
	}

	@Test
	public void testEveryBundledCourseParses() throws IOException {
		final List<Path> files;
		try (Stream<Path> paths = Files.walk(Paths.get("courses"))) {
			files = paths.filter(p -> p.toString().endsWith(".course")).collect(Collectors.toList());
		}

		assertEquals(8, files.size());
		for (final Path file : files) {
			final Optional<Course> loaded = CourseIO.loadCourse(file.toFile());

			assertTrue(file.toString(), loaded.isPresent());
			assertFalse(file.toString(), loaded.get().getTargets().isEmpty());
			assertTrue(file.toString(), loaded.get().getResolution().isPresent());
			for (final CourseTarget target : loaded.get().getTargets()) {
				assertTrue(target.file() + " exists", target.file().isFile());
			}
		}
	}
}
```

Then move it to core:

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p core/src/test/java/com/shootoff/courses/io
git mv javafx-app/src/test/java/com/shootoff/courses/io/TestCourseIO.java core/src/test/java/com/shootoff/courses/io/TestCourseIO.java
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:compileTestJava :javafx-app:compileTestJava --console=plain`
Expected: FAIL. `core` reports `package com.shootoff.courses does not exist`, and `javafx-app` reports `cannot find symbol` for `CourseTarget`, `CourseBackground` and `getCourse`.

- [ ] **Step 3: Move the course code and make it data**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p core/src/main/java/com/shootoff/courses/io
git mv javafx-app/src/main/java/com/shootoff/courses/Course.java core/src/main/java/com/shootoff/courses/Course.java
for f in CourseIO CourseVisitor XMLCourseReader XMLCourseWriter; do
	git mv javafx-app/src/main/java/com/shootoff/courses/io/$f.java core/src/main/java/com/shootoff/courses/io/$f.java
done
```

Create the new files with the GPL header. For the three rewritten files, keep their license header and replace everything after it.

`core/src/main/java/com/shootoff/courses/CourseTarget.java`:

```java
package com.shootoff.courses;

import java.io.File;

/**
 * A target in a course: its .target file and where it sits on the arena.
 *
 * @param x
 *            the target's position (see Placement) on the arena
 * @param width
 *            the target's width on the arena
 */
public record CourseTarget(File file, double x, double y, double width, double height) {}
```

`core/src/main/java/com/shootoff/courses/CourseBackground.java`:

```java
package com.shootoff.courses;

/**
 * A course's arena background: a URL, or a resource path in the ShootOFF jar when
 * <tt>isResource</tt>.
 */
public record CourseBackground(String url, boolean isResource) {}
```

`core/src/main/java/com/shootoff/courses/Course.java`:

```java
package com.shootoff.courses;

import java.util.List;
import java.util.Optional;

import com.shootoff.geom.Size;

/**
 * A saved projector arena: its background, its targets with their positions and sizes, and the
 * arena's size when it was saved.
 */
public class Course {
	private final Optional<CourseBackground> background;
	private final List<CourseTarget> targets;
	private final Optional<Size> resolution;

	public Course(Optional<CourseBackground> background, List<CourseTarget> targets, Optional<Size> resolution) {
		this.background = background;
		this.targets = List.copyOf(targets);
		this.resolution = resolution;
	}

	public Optional<CourseBackground> getBackground() {
		return background;
	}

	public List<CourseTarget> getTargets() {
		return targets;
	}

	/**
	 * The dimensions of the arena when the course was saved.
	 * 
	 * @return Optional.empty for courses saved prior to 3.7
	 */
	public Optional<Size> getResolution() {
		return resolution;
	}
}
```

`core/src/main/java/com/shootoff/courses/io/CourseIO.java`:

```java
package com.shootoff.courses.io;

import java.io.File;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.shootoff.courses.Course;
import com.shootoff.courses.CourseTarget;

public class CourseIO {
	private static final Logger logger = LoggerFactory.getLogger(CourseIO.class);

	public static void saveCourse(Course course, final File courseFile) {
		CourseVisitor visitor;

		if (courseFile.getName().endsWith("course")) {
			visitor = new XMLCourseWriter(courseFile);
		} else {
			logger.error("Unknown course file type.");
			return;
		}

		if (course.getBackground().isPresent()) {
			visitor.visitBackground(course.getBackground().get().url(), course.getBackground().get().isResource());
		}

		for (final CourseTarget t : course.getTargets()) {
			visitor.visitTarget(t.file(), t.x(), t.y(), t.width(), t.height());
		}

		if (course.getResolution().isPresent()) {
			visitor.visitResolution(course.getResolution().get().getWidth(), course.getResolution().get().getHeight());
		}

		visitor.visitEnd();
	}

	public static Optional<Course> loadCourse(final File courseFile) {
		if (!courseFile.getName().endsWith("course")) {
			logger.error("Unknown course file type.");
			return Optional.empty();
		}

		return new XMLCourseReader(courseFile).load();
	}
}
```

`core/src/main/java/com/shootoff/courses/io/XMLCourseReader.java`:

```java
package com.shootoff.courses.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.shootoff.courses.Course;
import com.shootoff.courses.CourseBackground;
import com.shootoff.courses.CourseTarget;
import com.shootoff.geom.Size;

/**
 * Reads a .course file. Target files are only named here; the arena loads them when it applies
 * the course.
 */
public class XMLCourseReader {
	private static final Logger logger = LoggerFactory.getLogger(XMLCourseReader.class);

	private final File courseFile;

	public XMLCourseReader(File courseFile) {
		this.courseFile = courseFile;
	}

	public Optional<Course> load() {
		try (InputStream xmlInput = new FileInputStream(courseFile)) {
			final CourseXMLHandler handler = new CourseXMLHandler();
			SAXParserFactory.newInstance().newSAXParser().parse(xmlInput, handler);

			return Optional.of(new Course(handler.background, handler.targets, handler.resolution));
		} catch (IOException | ParserConfigurationException | SAXException | NumberFormatException e) {
			logger.error("Error reading XML course", e);
		}

		return Optional.empty();
	}

	private static class CourseXMLHandler extends DefaultHandler {
		private Optional<CourseBackground> background = Optional.empty();
		private final List<CourseTarget> targets = new ArrayList<>();
		private Optional<Size> resolution = Optional.empty();

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes)
				throws SAXException {
			switch (qName) {
			case "background":
				background = Optional.of(new CourseBackground(attributes.getValue("url"),
						Boolean.parseBoolean(attributes.getValue("isResource"))));
				break;

			case "target":
				targets.add(new CourseTarget(new File(attributes.getValue("file")),
						Double.parseDouble(attributes.getValue("x")), Double.parseDouble(attributes.getValue("y")),
						Double.parseDouble(attributes.getValue("width")),
						Double.parseDouble(attributes.getValue("height"))));
				break;

			case "resolution":
				resolution = Optional.of(new Size(Double.parseDouble(attributes.getValue("width")),
						Double.parseDouble(attributes.getValue("height"))));
				break;
			}
		}
	}
}
```

In `core/src/main/java/com/shootoff/config/Settings.java`, add after `setUserNotifier`:

```java
	/**
	 * @return who tells the user about problems found by code without a UI of its own
	 */
	public static UserNotifier getUserNotifier() {
		return userNotifier;
	}
```

- [ ] **Step 4: Apply courses to the arena in the JavaFX app**

In `javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java`:
1. Add the imports:
   - `java.util.ArrayList`, `java.util.List`
   - `com.shootoff.config.Settings`
   - `com.shootoff.courses.CourseBackground`, `com.shootoff.courses.CourseTarget`
   - `com.shootoff.gui.targets.TargetView`
   - `com.shootoff.targets.io.TargetIO`, `com.shootoff.targets.io.TargetIO.TargetComponents`
2. Replace the whole `setCourse(final Course course)` method with:

```java
	/**
	 * @return the arena as a course: its background, its targets (files relative to the ShootOFF
	 *         home folder) and its size
	 */
	public Course getCourse() {
		final Optional<CourseBackground> courseBackground = background
				.map(b -> new CourseBackground(b.getURL(), b.isResource()));

		final List<CourseTarget> targets = new ArrayList<>();
		for (final Target t : canvasManager.getTargets()) {
			if (t.getTargetFile() == null) continue;

			final File relativeTargetFile = new File(t.getTargetFile().getAbsolutePath()
					.replace(System.getProperty("shootoff.home") + File.separator, ""));
			targets.add(new CourseTarget(relativeTargetFile, t.getPosition().getX(), t.getPosition().getY(),
					t.getDimension().getWidth(), t.getDimension().getHeight()));
		}

		return new Course(courseBackground, targets, Optional.of(new Size(getWidth(), getHeight())));
	}

	/**
	 * Replaces the arena's targets (and its background, if the course has one) with a course's,
	 * scaled from the arena size the course was saved at. Targets whose files are missing are
	 * skipped and reported once.
	 *
	 * @return the course's targets now on the arena
	 */
	public List<Target> setCourse(final Course course) {
		if (course.getBackground().isPresent()) {
			setArenaBackground(toLocatedImage(course.getBackground().get()));
		}

		canvasManager.clearTargets();

		final boolean scaleCourse = course.getResolution().isPresent()
				&& (Math.abs(course.getResolution().get().getWidth() - getWidth()) > .0001
						|| Math.abs(course.getResolution().get().getHeight() - getHeight()) > .0001);

		double widthScaleFactor = 1;
		double heightScaleFactor = 1;

		if (scaleCourse) {
			widthScaleFactor = getWidth() / course.getResolution().get().getWidth();
			heightScaleFactor = getHeight() / course.getResolution().get().getHeight();
		}

		final List<Target> added = new ArrayList<>();
		final List<String> missing = new ArrayList<>();

		for (final CourseTarget courseTarget : course.getTargets()) {
			final Optional<TargetComponents> components = TargetIO.loadTarget(courseTarget.file());

			if (!components.isPresent()) {
				missing.add(courseTarget.file().getPath());
				continue;
			}

			final TargetView t = new TargetView(components.get(), canvasManager, true);
			t.setPosition(courseTarget.x(), courseTarget.y());
			t.setDimensions(courseTarget.width(), courseTarget.height());

			if (scaleCourse) {
				t.scale(widthScaleFactor, heightScaleFactor);
			}

			added.add(canvasManager.addTarget(t));
		}

		if (!missing.isEmpty()) {
			Settings.getUserNotifier().showError("Missing Target", "Missing Required Target File",
					"This course requires these target files, but they are missing, so they will not appear in "
							+ "your projector arena:\n" + String.join("\n", missing));
		}

		return added;
	}

	/**
	 * @return the image for a course's background
	 */
	public static LocatedImage toLocatedImage(CourseBackground courseBackground) {
		if (courseBackground.isResource()) {
			return new LocatedImage(ProjectorArenaPane.class.getResourceAsStream(courseBackground.url()),
					courseBackground.url());
		}

		return new LocatedImage(courseBackground.url());
	}
```

In `javafx-app/src/main/java/com/shootoff/gui/pane/ArenaCoursesSlide.java`:
1. Replace `CourseIO.saveCourse(arenaPane, courseFile);` with `CourseIO.saveCourse(arenaPane.getCourse(), courseFile);`.
2. In `onItemClicked`, replace `CourseIO.loadCourse(arenaPane, courseFile)` with `CourseIO.loadCourse(courseFile)`.
3. Replace the whole `getCourseThumbnail(File courseFile)` method with:

```java
	private ImageView getCourseThumbnail(File courseFile) {
		final Optional<Course> course = CourseIO.loadCourse(courseFile);

		if (course.isPresent()) {
			final Group courseGroup = new Group();
			final Course c = course.get();

			if (c.getBackground().isPresent()) {
				final Size courseDimensions;

				if (c.getResolution().isPresent()) {
					courseDimensions = c.getResolution().get();
				} else {
					courseDimensions = new Size(arenaPane.getWidth(), arenaPane.getWidth());
				}

				final ImageView backgroundImageView = new ImageView(
						ProjectorArenaPane.toLocatedImage(c.getBackground().get()));
				backgroundImageView.setFitWidth(courseDimensions.getWidth());
				backgroundImageView.setFitHeight(courseDimensions.getHeight());
				backgroundImageView.setSmooth(true);

				courseGroup.getChildren().add(backgroundImageView);
			}

			for (final CourseTarget t : c.getTargets()) {
				final Optional<TargetComponents> components = TargetIO.loadTarget(t.file(), false);
				if (!components.isPresent()) continue;

				final TargetView view = new TargetView(components.get(), new ArrayList<Target>());
				view.setPosition(t.x(), t.y());
				view.setDimensions(t.width(), t.height());
				courseGroup.getChildren().add(view.getTargetGroup());
			}

			final Image courseThumbnail = courseGroup.snapshot(new SnapshotParameters(), null);
			final ImageView courseImageView = new ImageView(courseThumbnail);

			courseImageView.setFitWidth(60);
			courseImageView.setFitHeight(60);
			courseImageView.setPreserveRatio(true);
			courseImageView.setSmooth(true);

			return courseImageView;
		}

		return null;
	}
```

Replace its import `javafx.geometry.Dimension2D` with `com.shootoff.geom.Size`, and add `java.util.ArrayList`, `com.shootoff.courses.CourseTarget`, `com.shootoff.targets.io.TargetIO` and `com.shootoff.targets.io.TargetIO.TargetComponents`. (`width` twice in the no-resolution case is today's code, kept as is.)

In `javafx-app/src/main/java/com/shootoff/plugins/ProjectorTrainingExerciseBase.java`, replace the body of `setCourse(File courseFile)` with:

```java
		final Optional<Course> newCourse = CourseIO.loadCourse(courseFile);
		return arenaPane.setCourse(newCourse.get());
```

In `javafx-app/src/main/java/com/shootoff/plugins/SteelChallenge.java`, replace

```java
	// For testing
	public void init(final Course course) {
		testing = true;
		thisSuper = super.getInstance();

		targets = new ArrayList<>();
		targets.addAll(course.getTargets());
```

with

```java
	// For testing
	public void init(final List<Target> courseTargets) {
		testing = true;
		thisSuper = super.getInstance();

		targets = new ArrayList<>();
		targets.addAll(courseTargets);
```

and delete `import com.shootoff.courses.Course;`.

In `javafx-app/src/test/java/com/shootoff/plugins/TestSteelChallenge.java`:
1. Add `import java.util.List;`.
2. Replace `private Course course;` with `private List<Target> courseTargets;`.
3. Replace

   ```java
   		Optional<Course> course = CourseIO.loadCourse(pac,
   				new File("courses/steel_challenge/accelerator.course".replace("/", File.separator)));
   		targetsSC.init(config, cs, null, null, pac);
   		targetsSC.init(course.get());
   		this.course = course.get();

   		for (Target t : course.get().getTargets()) {
   ```

   with

   ```java
   		Optional<Course> course = CourseIO
   				.loadCourse(new File("courses/steel_challenge/accelerator.course".replace("/", File.separator)));
   		courseTargets = pac.setCourse(course.get());
   		targetsSC.init(config, cs, null, null, pac);
   		targetsSC.init(courseTargets);

   		for (Target t : courseTargets) {
   ```

4. Replace `noTargetsSC.init(new Course(new ArrayList<Target>()));` with `noTargetsSC.init(new ArrayList<Target>());`.
5. Replace every `course.getTargets().size()` with `courseTargets.size()`:

   ```bash
   perl -pi -e 's/course\.getTargets\(\)\.size\(\)/courseTargets.size()/g' javafx-app/src/test/java/com/shootoff/plugins/TestSteelChallenge.java
   command grep -n "course\b" javafx-app/src/test/java/com/shootoff/plugins/TestSteelChallenge.java
   ```

   Expected: only the lines that load `course` and pass `course.get()` to `setCourse`.

Check that nothing left uses the old course API or keeps course code in javafx-app:

```bash
command grep -rn "loadCourse(arenaPane\|loadCourse(pac\|saveCourse(arenaPane\|new Course(new" core/src javafx-app/src
command grep -rn "javafx" core/src/main/java/com/shootoff/courses
git ls-files javafx-app/src/main/java/com/shootoff/courses
```

Expected: nothing from any of the three commands.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:test --tests 'com.shootoff.courses.*' --tests 'com.shootoff.TestNoJavaFxInCore' :javafx-app:test --tests 'com.shootoff.courses.*' --tests 'com.shootoff.plugins.*' --tests 'com.shootoff.gui.*' --console=plain`
Expected: all pass, including:
- `TestCourseIO` (4) in core
- `TestArenaCourse` (4)
- `TestSteelChallenge`, whose course now goes through `ProjectorArenaPane.setCourse`
- `TestNoJavaFxInCore`

- [ ] **Step 6: Gate**

Run the gate. Expected: `0 regressions; 0 new failures`, and passing = previous + 5 (**294**). Also:

```bash
ls core/build/test-results/test | command grep -cE "TestTargetDefinitions|TestTargetSet|TestHitTester|TestSessionRecorder|TestSessionIO|TestSessionFormat|TestCourseIO"
```

Expected: `7`.

- [ ] **Step 7: Check the published jars and the drill's build**

```bash
cd /home/bfears/projects/ShootOFF
./gradlew publishToMavenLocal -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain
unzip -l build/m2/com/shootoff/core/5.0.0-SNAPSHOT/core-5.0.0-SNAPSHOT.jar | command grep -cE "targets/model/HitTester.class|targets/io/XMLTargetWriter.class|session/SessionRecorder.class|courses/Course.class"
unzip -l build/m2/com/shootoff/shootoff/5.0.0-SNAPSHOT/shootoff-5.0.0-SNAPSHOT.jar | command grep -cE "session/SessionRecorder.class|courses/Course.class|targets/io/XMLTargetReader.class"
unzip -l build/m2/com/shootoff/shootoff/5.0.0-SNAPSHOT/shootoff-5.0.0-SNAPSHOT.jar | command grep -cE "targets/Target.class|targets/Hit.class|gui/targets/TargetView.class|targets/io/TargetIO.class"
```

Expected: `4`, `0`, `4`.

The drill's source uses only the v1 API. A scratch copy must still compile against the published jars, with the `core` line Plan 1 Task 10 Step 9 showed it needs:

```bash
rm -rf build/drill-check && rsync -a --exclude build --exclude .gradle /home/bfears/projects/RandomTargetParDrill/ build/drill-check/
command grep -q 'com.shootoff:core' build/drill-check/build.gradle.kts || perl -0pi -e 's/(    compileOnly\("com\.shootoff:shootoff:5\.0\.0-SNAPSHOT"\) \{ isTransitive = false \}\n)/$1    compileOnly("com.shootoff:core:5.0.0-SNAPSHOT") { isTransitive = false }\n/' build/drill-check/build.gradle.kts
(cd build/drill-check && ./gradlew compileJava -Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2 --console=plain) 2>&1 | tail -3
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add core/src/main/java/com/shootoff/courses core/src/test/java/com/shootoff/courses core/src/main/java/com/shootoff/config/Settings.java \
  javafx-app/src/main/java/com/shootoff/gui/pane/ProjectorArenaPane.java javafx-app/src/main/java/com/shootoff/gui/pane/ArenaCoursesSlide.java \
  javafx-app/src/main/java/com/shootoff/plugins/ProjectorTrainingExerciseBase.java javafx-app/src/main/java/com/shootoff/plugins/SteelChallenge.java \
  javafx-app/src/test/java/com/shootoff/courses/TestArenaCourse.java javafx-app/src/test/java/com/shootoff/plugins/TestSteelChallenge.java
git status --short | command grep -v '^R  '
git commit -m "Move courses into core as data; the JavaFX arena applies them"
git log -1 --format=%B
```

The filtered status must show only ` M shootoff.properties`.

---
### Task 9: v1 compatibility check and hardware check with the owner

**Files:** none. If a check fails, stop and debug with superpowers:systematic-debugging before changing code. The fix belongs in the task that owns the code, as a new commit.

- [ ] **Step 1: Check the drill's v1 members are still there**

Every member the installed drill jar calls (see "The v1 surface") must exist with the same descriptor:

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :javafx-app:classes --console=plain -q
CP=core/build/classes/java/main:javafx-app/build/classes/java/main
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
check com.shootoff.plugins.TrainingExercise shotListener "(Lcom/shootoff/camera/Shot;Ljava/util/Optional;)V"
check com.shootoff.plugins.TrainingExercise targetUpdate "(Lcom/shootoff/targets/Target;Lcom/shootoff/plugins/TrainingExercise\$TargetChange;)V"
check com.shootoff.plugins.TrainingExercise reset "(Ljava/util/List;)V"
javap -p -cp "$CP" com.shootoff.targets.EllipseRegion com.shootoff.targets.RectangleRegion com.shootoff.targets.PolygonRegion com.shootoff.targets.ImageRegion | command grep -E "^public class"
echo "missing=$missing"
```

Expected:
- no `MISSING` line and `missing=0`
- the four region classes still extend `javafx.scene.shape.Ellipse`, `Rectangle`, `Polygon` and `javafx.scene.image.ImageView`, since the drill casts a hit region to `Node`

- [ ] **Step 2: Launch**

Run `./gradlew run --args="-d" --console=plain > build/plan2-run.log 2>&1` in the background. With the webcam and projector attached, the owner checks:
1. **Targets render and hit.**
   - Add IPSC, ISSF and Duel Tree from the Targets pane to the webcam feed.
   - They look as before: fills, 50 % opacity, images, no stray outlines until selected.
   - Click-to-shoot (shift+click) on a scoring zone logs a hit and shows the region's points in exercises that score.
   - A shot on a transparent part of an image target (between Duel Tree's paddles) misses.
2. **Drag and resize.**
   - Select a target: gold outline and anchors.
   - Drag it; drag each edge and a corner; ctrl+drag a corner (fixed aspect).
   - Use the arrow keys and shift/ctrl+shift+arrows; press Delete.
   - All behave as before. After a resize, shots still land on the region under the dot.
3. **Target editor.** Open a copy of a bundled target in the editor, move a region, change a color, add a tag, and save. Reopen it: the changes are there. Add it to the feed: it renders and is hit where drawn.
4. **An animated-target exercise.**
   - Run **Duel Tree** (or **Bouncing Targets**) on the projector arena.
   - Paddles fall and swing back when shot, with sounds.
   - A shot where a fallen paddle used to be doesn't count it again.
   - Bouncing targets move and bounce off each other and the edges.
5. **The drill.**
   - Start the installed `RandomTargetParDrill` (v1 jar) and play two rounds: random placement, sounds, par timing, and hits scored with points.
   - Between rounds, while the target is hidden, a shot doesn't score (ruling 4).
   - The summary shows the hit factor and replays shots on the summary target.
6. **Session record and replay.** Turn on **Record Session** with a target on the arena; move and resize it, fire hits and misses, stop. Replay in **View Sessions**: the target is added, moved and resized as it was, markers appear in their colors, and animations replay.
7. **Courses on the arena.**
   - Save the arena with two targets as a course; clear it; load it again. The targets come back at the same places and sizes.
   - Load a Steel Challenge course from the list. Its thumbnail looks right, and the course scales to the arena.
8. **POI adjustment.** Add the POI adjustment target on the arena and shoot its corner and center zones. It beeps and adjusts, as before.

- [ ] **Step 3: Check the log**

```bash
command grep -nE "Exception|NoClassDefFoundError|NoSuchMethodError|AbstractMethodError|Can't load target" build/plan2-run.log
```

Expected: nothing.
- A `NoSuchMethodError`/`AbstractMethodError` naming a `targets`, `gui`, `session` or `courses` member means a v1 plugin used an API this plan changed. Report it; don't paper over it.
- A `Can't load target` line names a target file the model couldn't parse, with its line (ruling 11).

- [ ] **Step 4: Installed distribution**

```bash
./gradlew installDist --console=plain
(cd /tmp && /home/bfears/projects/ShootOFF/javafx-app/build/install/shootoff/bin/shootoff)
```

The window opens with the targets listed in the Targets pane, their thumbnails drawn. Adding one to the feed works. The owner closes it.

Things this Linux machine can't exercise: the PS3 Eye camera, and Windows paths in `@`-target resources. Note them as unchecked in the report.

---

## Spec coverage

| Spec | Where |
|---|---|
| §3 `TargetDefinition`, `Region` sealed hierarchy with geometry, fill, tags, index; `ImageRegion` keeps only the path (plus its size) | Task 1 |
| §3 `TargetDefinitions.load(Path)` / `load(InputStream, ResourceResolver)`, descriptive failures | Task 1 (ruling 11) |
| §3 `PlacedTarget` (id, position, size, visibility, per-region visibility), `TargetSet` with add/remove/move/resize/visibility and listeners | Task 2 |
| §3 `HitTester.hit(TargetSet, x, y)`: topmost visible target, topmost region, exact shapes, image bounds, `Hit(TargetId, Region, impact)` | Task 2 (rulings 2–5) |
| §3 animation commands stay data on `Region` | Task 1 (`Region.commands()`) |
| §5 `TargetView` built from the model, interactions kept, changes through the `TargetSet`; XML-to-JavaFX building replaced | Tasks 4–5 |
| §5 hits from `HitTester` | Task 6 |
| §5 sessions: core `Shot` data and target references, same XML/JSON, ids converted to indexes at record time, `SessionCanvasManager` stays | Task 7 |
| §7 Plan 2 step 4: course data model in core, applying a course stays in javafx-app | Task 8 |
| §8 parity test over every bundled target, grid, placements and scales, with a recorded expectation file | Task 3 |
| §8 parser tests: every bundled target parses; malformed input reports an error | Task 1 |
| §8 session compatibility: `legacy_session.json`, `arena_duplicate_targets.xml` load and replay | Task 7 |
| §8 boundary: no `javafx.*` in core | `TestNoJavaFxInCore`, run in Tasks 1, 2, 7, 8 |
| §9 animated targets: parity covers image bounds and frames; owner checks an animated exercise | Tasks 3, 6, 9 |
| §1 criterion 6: v1 plugins still load and run | Global Constraints, Task 9 Steps 1 and 2 |
