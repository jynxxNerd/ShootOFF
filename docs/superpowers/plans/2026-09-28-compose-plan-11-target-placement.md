# Compose Plan 11: Target Placement — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Compose app a Targets screen on which the owner adds, moves, resizes, nudges and removes targets on the projector arena (and on the camera feed), picks a background, loads and saves courses, clears with Undo, and finds the arena as it was left after a restart.

**Architecture:**
- **Task 1: who owns a target.** `SurfaceTargets.add` takes a `TargetOwner` (`USER` or `EXERCISE`), known to every `TargetSet` listener as it hears of the target, and carried on `DrawnTarget` with the target's bounds. The exercise host adds with `EXERCISE`.
- **Task 2: `TargetEditor`**, no UI: the selection on one surface and the gestures (click, drag, corner resize with an aspect lock, arrow nudges, delete), as `TargetSet` placement changes. It refuses an exercise's targets and keeps a 10-unit strip of every target on the surface.
- **Task 3: `ArenaLayout`**, the arena's lasting part: its `SurfaceTargets`, the shooter's background and the arena's size. `AppState` keeps one for the session and hands it to each `ArenaModel` (one per arena window, as now). The shooter's background shows under calibration's and an exercise's own background.
- **Task 4: `CourseLoader`**: applies a `Course` to the layout (the shooter's targets and background, scaled from the course's resolution as the JavaFX `ProjectorArenaPane.setCourse` did) and builds one from it; `core`'s `XMLCourseWriter` escapes its attribute values.
- **Task 5: `LayoutMemory`**: saves the layout to `arena-layout.course` 500 ms after the shooter's last change, on a timer thread, and restores it through `CourseLoader`.
- **Task 6: the Targets screen**, enabled on the rail: the arena drawn from `ArenaLayout` (open or closed, calibrating or not) with an `EditingOverlay` for the mouse and keys.
- **Task 7: `TargetsModel`'s actions**, no UI: add a target, backgrounds, load and save courses, Clear and Undo, on the arena or the camera feed; `ArenaFiles` says where the files are.
- **Task 8: the toolbar and its panels**, and the Arena / Camera switch with the live feed.
- **Task 9: the layout at launch**: `AppState` starts `LayoutMemory`, restores the layout the first time an arena is in place, and saves a waiting change on close; `Main` points it at ShootOFF's folder.
- **Task 10: the owner's hardware check.**

**Tech Stack:**
- Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`); Java 21 (`core`).
- Gradle 8.14 wrapper (Kotlin DSL).
- Compose Multiplatform 1.12.1 (desktop, Linux x64), Material 3 1.9.0, kotlinx.coroutines (from Compose).
- JUnit 5 for unit tests; Compose UI tests on JUnit 4 (`v2.createComposeRule`) through the vintage engine; JUnit 4 in `core`'s `TestCourseIO`, which already uses it.

**Spec:** `docs/superpowers/specs/2026-09-28-compose-target-placement-design.md` (commit `8f6852c1`). Where the spec and this plan disagree, the spec wins.

**Prototype.**
- The whole plan was built in a scratch clone of `compose-ui` at `8f6852c1`, one commit per task.
- Every task compiled and its `core` and `compose-app` tests passed at its commit; the full gate ran green at the last one, **891/891**.
- The code blocks below are the prototype's edits, extracted from those commits by a script. The script checked that each "Replace … with …" pair matches exactly once in the file at the point it is applied, and that applying the pairs in order gives the task's commit byte for byte.
- The GUI apps were not run. Task 10 is the owner's.
- The JavaFX reference numbers in Task 4's test were printed by a throwaway JavaFX test in the clone (a `ProjectorArenaPane` in a 1280×720 scene, `setCourse` on `accelerator.course`), not kept.

**What only the hardware can confirm** (Task 10): that dragging with the real mouse feels right at the preview's scale; that Ctrl keeps the shape (Compose's test mouse can't hold Ctrl); that the projector follows each change at once; that the arena comes back after a restart on the real projector; that a feed target is hit by a real shot.

## Plan-author rulings

Where the spec leaves room, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong.

### What a course's x and y mean (spec §5)

**Finding.** A course target's `x` and `y` are the target's `Placement` position, JavaFX's `layoutX`/`layoutY`: where the target's own (0, 0) would be if it were unscaled. They are not its bounds' top-left corner.
- *The code.* `ProjectorArenaPane.getCourse` (`javafx-app/.../gui/pane/ProjectorArenaPane.java:369-390`) saves `t.getPosition()`, which `TargetView.getPosition` (`TargetView.java:414`) reads from `Placement.x()/y()`, and `getDimension()`, the bounds' size. `setCourse` (`:399-445`) calls `setPosition(x, y)` (a `TargetSet.move`), then `setDimensions(width, height)` (a `TargetSet.resize`), then, when the course's resolution differs from the arena's by more than 0.0001 on either axis, `TargetView.scale(widthFactor, heightFactor)` (`TargetView.java:442-457`), which moves the target so that its bounds end up scaled about the arena's origin.
- *Why the bundled courses have negative values.* `PlacedTarget` scales a target about the centre of its own bounds (`PlacedTarget.transform`), so `tx = x + pivotX − pivotX·scaleX`. The Steel Challenge targets are drawn at 150–300 units in their own coordinates (`Steel_Challenge_Circle.target`'s ellipse is centred at (160.5, 157.5)) and shown at a third of that, so their position sits well left of and above their bounds: `accelerator.course`'s last target has x = −93 and lands at bounds x ≈ 30 on the 640×483 arena it was saved on.
- *The check.* A throwaway JavaFX test in the clone put `accelerator.course` on a 1280×720 `ProjectorArenaPane`. Its five targets' bounds, which Task 4's `aBundledSteelChallengeCourseLandsWhereTheJavaFxAppPutIt` pins, were (316.24, 365.22, 103.51, 274.29), (1144.36, 52.92, 79.27, 159.50), (623.00, 238.51, 148.00, 211.68), (929.44, 60.37, 107.13, 156.52) and (60.34, 411.43, 149.31, 229.57); the first target's position was (254.0, 280.36). `CourseLoader` repeats `setCourse`'s three steps and matches every figure to 0.01. `everyBundledCourseLandsWhollyOnTheOwnersArena` checks all eight bundled courses land wholly on 1280×720.

*Cost:* none; it is the old app's arithmetic, step for step.

### Decisions

1. **The arena's lasting part is an `ArenaLayout`, and `ArenaModel` stays one per window.** Spec §6 asks for editing with the arena closed. Today `ArenaModel` is made at `openArena` and dropped at `closeArena`, and several guards (`arenaState.value !== arena` in `watchArenaForPattern`, the pattern run, the full-screen watch) tell a new arena from an old one by identity. Keeping one `ArenaModel` for the session would let a late callback from a closed window act on the next one. So the targets, the shooter's background and the size move into `ArenaLayout`, which `AppState` keeps and passes to each `ArenaModel`. `closeArena` now removes only an exercise's leftover targets. *Cost:* a second class for "the arena"; its KDoc says which is which.
2. **Two backgrounds: the shooter's under the arena's own.** Calibration (its white screen and pattern) and exercises save, set and restore `ArenaModel.background`. If the shooter's pick went into the same state, a calibration or a drill ending would put back the background it saved and undo a pick made meanwhile (spec §6: "your edits appear when it's done"). So `ArenaLayout.background` is the shooter's, and the arena draws `ArenaModel.background ?: (the shooter's, unless covered)`. An exercise's background covers the shooter's while it runs, as the old app's exercises replaced it. *Cost:* while a drill shows its own background, a new pick shows on the projector only when the drill stops.
3. **The Targets screen draws the layout itself** (`ArenaLayoutView`), not `ArenaCanvas`: `ArenaCanvas` hides the targets while the pattern covers the arena, and spec §6 keeps the screen usable then. It shows the background the arena returns to, every target, and the editing layer. *Cost:* none.
4. **When the remembered layout comes back** ("at launch, once the arena is open"). The arena window opens at 640×480 and reaches the projector's 1280×720 a second or more later, so restoring as it opens would scale a 1280×720 layout down to 640×480. The layout is restored the first time an arena this session fills its projector screen (the same test `watchArenaForPattern` uses), or at once when there is no projector screen and the arena is a window. It is restored once a session, and not at all once the shooter has changed the layout this session: their change has already replaced the file. *Cost:* a session begun without the projector, where the owner edits before opening the arena, replaces the remembered layout, as any edit does.
5. **`TargetOwner` defaults to `USER`** in `SurfaceTargets.add`, so the 25 existing test call sites stay as they are; the exercise host passes `EXERCISE`, and Task 1's test pins it. The owner is recorded by `SurfaceTargets`' own `TargetSet` listener, the set's first, from a thread-local set in `add`, so every later listener (`LayoutMemory`, the exercise host) knows it. A target added to the `TargetSet` directly counts as an exercise's. *Cost:* a new `SurfaceTargets.add` call that forgets the owner makes a shooter's target.
6. **Clicks go through an exercise's targets, and through hidden targets,** to the shooter's topmost shown target under the pointer. "Clicking them selects nothing" (spec §4) holds; a target an exercise hid can't be grabbed unseen. *Cost:* none known.
7. **Editing details.** Hit-testing and handles use the target's bounds. A corner drag keeps the opposite corner still; with Ctrl it keeps the shape, following whichever side changed more. Shift+arrow resizes about the centre, as the JavaFX app did. A resize stops at 10 units a side (or the target's size, if smaller). A drag is placed from where it began by the pointer's whole movement, as the calibration box learned in Plan 8. The clamp (spec §4) shifts the target back after every change. *Cost:* the numbers are the owner's to change (`TargetEditor.KEEP_ON`, `STEP`, `MIN_SIZE`).
8. **A new target lands at its natural size in the surface's middle and is selected**, except one whose `.target` asks to fill the canvas (the POI adjustment target), which fills it, as it always has. Add target lists the `.target` files at the top of `targets/` (not its subfolders), as the JavaFX app's Add Target did. *Cost:* none.
9. **The toolbar's dialogs are panels beside the surface,** not windows: they can be tested, and don't stack windows on the projector's desktop. The one exception is "An image file…", which opens AWT's file dialog. Load course lists the `.course` files in `courses/` and its subfolders (grouped by folder, as the JavaFX slide did), leaving out hidden files. Save course takes a name, saves to `courses/<name>.course`, and asks "There is already a course named X. Replace it?" before replacing one. *Cost:* a course elsewhere on disk can't be loaded from the screen.
10. **A course without a background keeps the current one (owner's ruling, 2026-09-28), as the JavaFX app did.** A course's background that can't be read is reported and also keeps the current one. Only a course with a readable background replaces it. *Cost:* none; the owner chose it.
11. **Messages go in the screen's own banner** (the feed's `BannerView`, dismissible), not in the app-wide snackbars: a target or course that can't be read (ERROR), a course loaded without some targets (WARNING, naming them), a save (INFO or ERROR). The launch restore only logs (spec §6). *Cost:* none.
12. **Clear's Undo is offered for 8 s** (`UNDO_MILLIS`), and the offer ends at the next toolbar action. Clear with nothing to clear offers nothing. *Cost:* the length is the owner's to change.
13. **Files are written safely.** `CourseLoader.save` writes a hidden `.name.course` beside the file and moves it over, so a failed write leaves the old file (the remembered layout included) whole. `core`'s `XMLCourseWriter` now escapes `&`, `<`, `>` and `"` in its attribute values: a background picked from a folder named "Range & Bay" would otherwise make the whole layout unreadable, and the arena would start empty. The format is unchanged; the JavaFX app's courses gain the same fix.
14. **Where the files are.** `ArenaFiles` names ShootOFF's folder, `targets/`, `courses/` and the layout file. `AppState`'s default, `ArenaFiles.scratch()`, reads ShootOFF's own targets, has a fresh empty courses folder and remembers no layout, so no test can write the owner's files; `Main` passes `ArenaFiles.inHome(home)`. The file is `arena-layout.course` in ShootOFF's folder (spec §5).
15. **The arena's size while it has never opened** is `ArenaLayout`'s default, 640×480, the size `ArenaModel` started at. A layout saved at that size is scaled when it comes back on the projector. *Cost:* none known.
16. **The rail's hint** now reads "Sessions: in the JavaFX app for now". *Cost:* none.

**Changes to JavaFX behaviour** (it shares `core`): course files escape `&`, `<`, `>` and `"` in the target path and background URL. Courses it wrote before still load.

**For the owner to decide** (none blocks the plan):
- The numbers in ruling 7 and the 8 s Undo.

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** plain sentences in the repo's style (e.g. "Hold the exposure at one frame for the whole pattern search"), with **no `Co-Authored-By` or any other trailer**. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage or modify `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- **The owner's files.** Never modify, move, delete or stage:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - anything under `exercises/` or `exercise-data/`
  - `arena-layout.course` in the ShootOFF folder, and any file under `courses/` or `targets/`
  - the owner's Java preferences
  Checksums are in `build/plan8-owner-files.sha256`: `sha256sum -c build/plan8-owner-files.sha256` must print OK for every line.
- **Tests and the owner's settings:** tests use scratch directories (`@TempDir`, or `ArenaFiles.scratch()`, `AppState`'s default) and `ScratchConfig`, never the owner's `shootoff.properties`, `shootoff.home`, `courses/` or `arena-layout.course`. Tests read the bundled `targets/` and `courses/steel_challenge/` only.
- **Code style:**
  - Kotlin: the official style (4 spaces, trailing commas). A new main-source `.kt` file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java`, then a blank line (the blocks below include it).
  - Java: tabs; match the surrounding code.
  - Test files have no header. Keep imports sorted as each file sorts them.
- **Packages:** `com.shootoff.*`. New packages: `com.shootoff.compose.courses`.
- **Dependencies:** none added anywhere. No OpenJFX in `compose-app` (`TestNoJavaFxInComposeApp` keeps passing).
- **Formats:** `.target`, `.course`, the session formats and `shootoff.properties` keys are unchanged (Task 4 only escapes attribute values, which XML readers already unescape). No settings key is added.
- **The exercise API is unchanged.** `plugin-api` has no edit.
- **UI copy** is the spec's where it gives it, verbatim: the calibrating note is exactly `Calibrating — the projector shows the pattern; your edits appear when it's done.` (an em dash, a straight apostrophe). Toolbar labels: `Add target`, `Background…`, `Load course…`, `Save course…`, `Clear`; the switch: `Arena`, `Camera`.
- **Publishing:** none. Never publish to `~/.m2`.
- Implementers never run the GUI apps (`./gradlew run`, `:compose-app:run`); Task 10 is the owner's.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper.
- **Test gate** (run in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10`; Bash timeout 600000 ms):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. The count starts at **816** (end of Plan 10); each task gives the count it ends at. A lower count means tests silently stopped running.
  - `core`'s `TestMalfunctionsProcessor.testManyMalfunctions` is probabilistic. If it alone fails, run the gate again.
  - The Compose UI tests need a display; the gate runs them on this machine's `DISPLAY=:0`.
  - After the gate, `git status --short` shows only ` M shootoff.properties` plus the task's own files, and `sha256sum -c build/plan8-owner-files.sha256` prints all OK.

## Review Focus

1. **A background picked from a folder whose name has `&` or a quote in it.** Expected: the layout still saves and comes back; before this plan the written course was unreadable and the arena would start empty.
   - *Test:* `core` `TestCourseIO.aPathOrUrlWithXmlSpecialCharactersRoundTrips` (Task 4).
2. **The app closed within half a second of the last edit** (drag, then quit at once). Expected: that edit is remembered.
   - *Tests:* `TestLayoutMemory.aSaveWaitingWhenTheAppClosesIsMadeAtOnce`, `TestRememberedLayout.aChangeIsRememberedEvenIfTheAppClosesAtOnce` (Task 9).
3. **A disk that refuses the write** (full, or a read-only folder). Expected: the old layout or course file is left whole, and Save course says it couldn't save.
   - *Tests:* `TestCourseLoader.aCourseThatCantBeWrittenLeavesTheOldFileAlone` (Task 4), `TestTargetsModel.aCourseIsSavedUnderItsNameAndAnExistingOneOnlyOnceTheShooterSaysSo` (Task 7, the messages).
4. **A drill running that hides or moves the shooter's targets** (Random Shoot hides them between rounds). Expected: a hidden target can't be grabbed unseen, and the exercise's own targets are never grabbed, saved or cleared.
   - *Tests:* `TestTargetEditor.aTargetAnExerciseHidIsNotClickedOn`, `anExercisesTargetCantBeSelectedAndClicksGoThroughIt` (Task 2); `TestCourseLoader.anExercisesTargetsAreLeftOutOfACourseAndLeftAloneByALoad` (Task 4); `TestLayoutMemory.anExercisesTargetsComingAndMovingAreNoReasonToSave` (Task 5).
5. **The arena window closed and reopened mid-session** (or F11 out and back). Expected: the shooter's targets and background are still there, an exercise's are gone, and the remembered layout isn't loaded a second time over the edits.
   - *Tests:* `TestAppState.theShootersTargetsAndBackgroundOutliveTheArenaWindowButAnExercisesTargetsDont` (Task 3), `TestRememberedLayout.theLayoutComesBackOnlyOnceASession` (Task 9).

Task 10 covers what no unit test reaches: the real mouse (and Ctrl), the projector following each change, and a real shot on a feed target.

## Module and file map

| Where | What | Task |
|---|---|---|
| `…/compose/targets/SurfaceTargets.kt`, `…/compose/drill/ComposeExerciseHost.kt` | `TargetOwner`; owner and bounds on `DrawnTarget`; the host adds `EXERCISE` targets | 1 |
| `…/compose/targets/TargetEditor.kt` (new) | selection, move, corner resize, nudges, delete, the clamp | 2 |
| `…/compose/arena/{ArenaLayout (new), ArenaModel, ArenaCanvas}.kt`, `…/compose/app/AppState.kt` | the layout outlives the window; the shooter's background under the arena's own | 3 |
| `…/compose/courses/CourseLoader.kt` (new), `core/…/courses/io/XMLCourseWriter.java` | apply, build and save courses; escaping | 4 |
| `…/compose/courses/LayoutMemory.kt` (new), `…/compose/arena/ArenaLayout.kt` | remember and restore the layout | 5 |
| `…/compose/app/{TargetsModel, TargetsScreen} (new), ShootOffApp, AppState}.kt`, `…/compose/targets/EditingOverlay.kt` (new), `…/compose/arena/ArenaCanvas.kt`, `…/compose/shell/Rail.kt` | the Targets screen and its editing layer | 6 |
| `…/compose/app/{ArenaFiles (new), TargetsModel, AppState}.kt` | the toolbar's actions | 7 |
| `…/compose/app/TargetsScreen.kt` | the toolbar, its panels, the camera tab | 8 |
| `…/compose/app/AppState.kt`, `…/compose/courses/LayoutMemory.kt`, `…/compose/Main.kt` | the layout at launch and on close | 9 |
| none | the owner's hardware check | 10 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | Test methods added | Gate |
|---|---|---|
| 1 | `targets/TestSurfaceTargets` (+3), `drill/TestExerciseRunner` (+1) | 820 |
| 2 | `targets/TestTargetEditor` (+14, new) | 834 |
| 3 | `app/TestAppState` (+1), `arena/TestArenaViews` (+1) | 836 |
| 4 | `courses/TestCourseLoader` (+9, new), `core` `courses/io/TestCourseIO` (+1) | 846 |
| 5 | `courses/TestLayoutMemory` (+8, new) | 854 |
| 6 | `app/TestTargetsScreen` (+7, new); `app/TestAppState` and `shell/TestAppRail` one test each renamed | 861 |
| 7 | `app/TestTargetsModel` (+17, new) | 878 |
| 8 | `app/TestTargetsToolbar` (+7, new) | 885 |
| 9 | `app/TestRememberedLayout` (+5, new), `courses/TestLayoutMemory` (+1) | 891 |

**Suggested models** (implementer / reviewer): Task 1 sonnet / sonnet; Task 2 haiku / sonnet; Task 3 sonnet / opus (the window lifecycle); Task 4 sonnet / opus (the owner's files); Task 5 sonnet / opus (threads and the owner's files); Task 6 sonnet / sonnet; Task 7 sonnet / sonnet; Task 8 haiku / sonnet; Task 9 sonnet / opus (launch and close).

---

### Task 1: Who owns a target

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/targets/SurfaceTargets.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt` (`addTarget`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestSurfaceTargets.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/drill/TestExerciseRunner.kt`

**Interfaces:**
- Consumes: `core`'s `TargetSet`, `TargetSetListener`, `PlacedTarget.getBounds()`.
- Produces:
  - `enum class TargetOwner { USER, EXERCISE }` (package `com.shootoff.compose.targets`).
  - `SurfaceTargets.add(definition: TargetDefinition, resolver: ResourceResolver, placement: Placement = Placement.ORIGIN, owner: TargetOwner = TargetOwner.USER): PlacedTarget`.
  - `SurfaceTargets.owner(id: TargetId): TargetOwner?` (null once the target has gone), known to every `TargetSet` listener from `targetAdded` on.
  - `SurfaceTargets.targetsOf(owner: TargetOwner): List<PlacedTarget>`, bottom to top.
  - `DrawnTarget` gains `owner: TargetOwner` and `bounds: Rect` (in that order, after `regionVisible`).

**Why.** Spec §5: the Targets screen, course loading and the layout's memory must tell the shooter's targets from a running exercise's. Ruling 5 says how the owner reaches every listener.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/drill/TestExerciseRunner.kt`:

Replace:

```kotlin
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
```

with:

```kotlin
import com.shootoff.compose.shots.ShotReceiver
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
```

Replace:

```kotlin
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
```

with:

```kotlin
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
```

Replace:

```kotlin
        }
    }

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val drill = DrillState()
    private val timer = ShotTimerModel()
```

with:

```kotlin
        }
    }

    /** A projector drill that puts a target of its own on the arena */
    class TargetDrill : Exercise {
        override fun metadata() = ExerciseMetadata("Target drill", "1.0", "ShootOFF tests", "Adds a target", true)

        override fun start(host: ExerciseHost) {
            host.addTarget("box.target", 300.0, 200.0)
            heard += "start"
        }

        override fun onShot(shot: Shot, hit: Optional<Hit>) {}

        override fun onReset() {}

        override fun stop() {}
    }

    private val settings = Settings(ScratchConfig.emptyFile().path, arrayOf())
    private val drill = DrillState()
    private val timer = ShotTimerModel()
```

Replace:

```kotlin
        assertEquals(listOf("Pause"), drill.buttons.value.map { it.label })
    }

    @Test
    fun withoutTheArenaAProjectorDrillDoesntStart() {
        arenaOpen = false
```

with:

```kotlin
        assertEquals(listOf("Pause"), drill.buttons.value.map { it.label })
    }

    // Spec §5: what an exercise adds is its own, and goes when it stops; the shooter's targets stay
    @Test
    fun aDrillsTargetsAreTheExercisesAndGoWhenItStopsWhileTheShootersStay(@TempDir temp: Path) {
        Files.writeString(temp.resolve("box.target"), "<target><rectangle x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"red\"/></target>")
        resources = URLClassLoader(arrayOf(temp.toUri().toURL()), null)
        val shooters = arena.targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf()))),
            ResourceResolver.files(),
        )

        runner.start(V2ExerciseEntry(TargetDrill::class.java, TargetDrill().metadata()))
        awaitHeard("start")
        val added = arena.targets.set.targets.single { it.id != shooters.id }
        assertEquals(TargetOwner.EXERCISE, arena.targets.owner(added.id))

        runner.stop()

        assertEquals(listOf(shooters.id), arena.targets.set.targets.map { it.id })
        assertEquals(TargetOwner.USER, arena.targets.owner(shooters.id))
    }

    @Test
    fun withoutTheArenaAProjectorDrillDoesntStart() {
        arenaOpen = false
```

`compose-app/src/test/kotlin/com/shootoff/compose/targets/TestSurfaceTargets.kt`:

Replace:

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
```

with:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.targets.model.ImageRegion
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetSetListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
```

Replace:

```kotlin
        assertEquals(emptyList<DrawnTarget>(), targets.drawn.value)
    }

    @Test
    fun anImageThatCantBeReadIsLeftOutButTheTargetStays() {
        val broken = TargetDefinition(Optional.empty(), mapOf(), listOf(ImageRegion(0, 0.0, 0.0, "targets/no_such.png", 10, 10, mapOf())))
```

with:

```kotlin
        assertEquals(emptyList<DrawnTarget>(), targets.drawn.value)
    }

    @Test
    fun aTargetIsTheShootersUnlessAnExerciseAddedIt() {
        val shooters = targets.add(box(), ResourceResolver.files())
        val exercises = targets.add(box(), ResourceResolver.files(), Placement(50.0, 0.0, 1.0, 1.0, true), TargetOwner.EXERCISE)

        assertEquals(TargetOwner.USER, targets.owner(shooters.id))
        assertEquals(TargetOwner.EXERCISE, targets.owner(exercises.id))
        assertEquals(listOf(TargetOwner.USER, TargetOwner.EXERCISE), targets.drawn.value.map { it.owner })
        assertEquals(listOf(shooters.id), targets.targetsOf(TargetOwner.USER).map { it.id })
        assertEquals(listOf(exercises.id), targets.targetsOf(TargetOwner.EXERCISE).map { it.id })

        targets.remove(exercises.id)
        assertNull(targets.owner(exercises.id))
    }

    // The layout's memory hears of each new target and must know whether it is the shooter's
    @Test
    fun theSetsListenersKnowANewTargetsOwnerAsTheyHearOfIt() {
        val heard = mutableListOf<TargetOwner?>()
        targets.set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) {
                heard += targets.owner(target.id)
            }

            override fun targetRemoved(target: PlacedTarget) {}

            override fun targetChanged(target: PlacedTarget) {}
        })

        targets.add(box(), ResourceResolver.files())
        targets.add(box(), ResourceResolver.files(), owner = TargetOwner.EXERCISE)

        assertEquals(listOf(TargetOwner.USER, TargetOwner.EXERCISE), heard)
    }

    @Test
    fun theSnapshotCarriesEachTargetsBoundsOnTheSurface() {
        val target = targets.add(box(), ResourceResolver.files(), Placement(5.0, 6.0, 2.0, 1.0, true))

        // Scaled about its center (5, 10): 20 wide from x = 0
        assertEquals(Rect(0.0, 6.0, 20.0, 20.0), targets.drawn.value.single().bounds)
        assertEquals(target.bounds, targets.drawn.value.single().bounds)
    }

    @Test
    fun anImageThatCantBeReadIsLeftOutButTheTargetStays() {
        val broken = TargetDefinition(Optional.empty(), mapOf(), listOf(ImageRegion(0, 0.0, 0.0, "targets/no_such.png", 10, 10, mapOf())))
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.targets.TestSurfaceTargets --tests com.shootoff.compose.drill.TestExerciseRunner --console=plain`

Expected: compilation fails, `Unresolved reference 'TargetOwner'` (and `'owner'`, `'targetsOf'`, `'bounds'`).

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt`:

Replace:

```kotlin
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.shots.Marker
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.config.Settings
import com.shootoff.exercise.ButtonHandle
import com.shootoff.exercise.Cancellable
```

with:

```kotlin
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.shots.Marker
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.Settings
import com.shootoff.exercise.ButtonHandle
import com.shootoff.exercise.Cancellable
```

Replace:

```kotlin
        if (support.isStopped) return Optional.empty()

        val (definition, resolver) = loadTarget(targetFile) ?: return Optional.empty()
        val target = targets.add(definition, resolver, Placement(x, y, 1.0, 1.0, true))
        context.surface.placeNewTarget(target)

        if (support.track(target.id)) return Optional.of(Handle(target))
```

with:

```kotlin
        if (support.isStopped) return Optional.empty()

        val (definition, resolver) = loadTarget(targetFile) ?: return Optional.empty()
        // The exercise's own: the shooter can't grab it on the Targets screen, and it is never saved
        val target = targets.add(definition, resolver, Placement(x, y, 1.0, 1.0, true), TargetOwner.EXERCISE)
        context.surface.placeNewTarget(target)

        if (support.track(target.id)) return Optional.of(Handle(target))
```

`compose-app/src/main/kotlin/com/shootoff/compose/targets/SurfaceTargets.kt`:

Replace:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.ResourceResolver
```

with:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.ResourceResolver
```

Replace:

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * A target as the views draw it: a snapshot of its placed target.
 *
 * @param origin where the target's (0, 0) is on the surface
 */
data class DrawnTarget(
    val id: TargetId,
```

with:

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Who put a target on its surface. The shooter's targets are theirs to move, resize, remove and save; an
 * exercise's are shown but left alone, and go when the exercise stops.
 */
enum class TargetOwner { USER, EXERCISE }

/**
 * A target as the views draw it: a snapshot of its placed target.
 *
 * @param origin where the target's (0, 0) is on the surface
 * @param bounds the target's bounds on the surface
 */
data class DrawnTarget(
    val id: TargetId,
```

Replace:

```kotlin
    val placement: Placement,
    val origin: Point,
    val regionVisible: List<Boolean>,
)

/**
```

with:

```kotlin
    val placement: Placement,
    val origin: Point,
    val regionVisible: List<Boolean>,
    val owner: TargetOwner,
    val bounds: Rect,
)

/**
```

Replace:

```kotlin
class SurfaceTargets(val set: TargetSet = TargetSet(), clock: AnimationClock = AnimationClock.background) {
    val animations = RegionAnimations(set, clock)
    private val images = ConcurrentHashMap<TargetId, Map<Int, RegionImage>>()
    private val drawnState = MutableStateFlow<List<DrawnTarget>>(emptyList())

    /** The targets, bottom to top, as the views draw them */
```

with:

```kotlin
class SurfaceTargets(val set: TargetSet = TargetSet(), clock: AnimationClock = AnimationClock.background) {
    val animations = RegionAnimations(set, clock)
    private val images = ConcurrentHashMap<TargetId, Map<Int, RegionImage>>()
    private val owners = ConcurrentHashMap<TargetId, TargetOwner>()

    // Who is adding the target [add] is adding on this thread: the set tells its listeners before it returns
    private val adding = ThreadLocal<TargetOwner>()
    private val drawnState = MutableStateFlow<List<DrawnTarget>>(emptyList())

    /** The targets, bottom to top, as the views draw them */
```

Replace:

```kotlin

    init {
        set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) = publish()

            override fun targetRemoved(target: PlacedTarget) {
                images.remove(target.id)
                animations.unregister(target.id)
                publish()
            }
```

with:

```kotlin

    init {
        set.addListener(object : TargetSetListener {
            // This listener is the set's first, so every other one already knows the new target's owner
            override fun targetAdded(target: PlacedTarget) {
                owners[target.id] = adding.get() ?: TargetOwner.EXERCISE
                publish()
            }

            override fun targetRemoved(target: PlacedTarget) {
                images.remove(target.id)
                owners.remove(target.id)
                animations.unregister(target.id)
                publish()
            }
```

Replace:

```kotlin

    /**
     * Adds a target on top, with its images read through [resolver] (the exercise's jar for "@" paths).
     */
    fun add(definition: TargetDefinition, resolver: ResourceResolver, placement: Placement = Placement.ORIGIN): PlacedTarget {
        val loaded = RegionImages.load(definition, resolver)
        val target = set.add(definition, placement)
        images[target.id] = loaded
        animations.register(target.id, loaded)
        publish()
```

with:

```kotlin

    /**
     * Adds a target on top, with its images read through [resolver] (the exercise's jar for "@" paths).
     *
     * @param owner who added it: the exercise host passes [TargetOwner.EXERCISE]
     */
    fun add(
        definition: TargetDefinition,
        resolver: ResourceResolver,
        placement: Placement = Placement.ORIGIN,
        owner: TargetOwner = TargetOwner.USER,
    ): PlacedTarget {
        val loaded = RegionImages.load(definition, resolver)
        adding.set(owner)
        val target = try {
            set.add(definition, placement)
        } finally {
            adding.remove()
        }
        images[target.id] = loaded
        animations.register(target.id, loaded)
        publish()
```

Replace:

```kotlin

    fun remove(id: TargetId) = set.remove(id)

    fun image(id: TargetId, region: Int): RegionImage? = images[id]?.get(region)

    // Synchronized so two concurrent mutations' publishes can't complete out of order and leave a
```

with:

```kotlin

    fun remove(id: TargetId) = set.remove(id)

    /**
     * Who added the target, known to the set's listeners from [TargetSetListener.targetAdded] on; null once it
     * has left the surface. A target added to [set] directly counts as an exercise's: nothing grabs or saves it.
     */
    fun owner(id: TargetId): TargetOwner? = owners[id]

    /** The targets [owner] added, bottom to top */
    fun targetsOf(owner: TargetOwner): List<PlacedTarget> = set.targets.filter { owners[it.id] == owner }

    fun image(id: TargetId, region: Int): RegionImage? = images[id]?.get(region)

    // Synchronized so two concurrent mutations' publishes can't complete out of order and leave a
```

Replace:

```kotlin
                target.placement,
                target.localToParent(0.0, 0.0),
                target.definition.regions().indices.map { target.isRegionVisible(it) },
            )
        }
    }
```

with:

```kotlin
                target.placement,
                target.localToParent(0.0, 0.0),
                target.definition.regions().indices.map { target.isRegionVisible(it) },
                owners[target.id] ?: TargetOwner.EXERCISE,
                target.bounds,
            )
        }
    }
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.targets.TestSurfaceTargets --tests com.shootoff.compose.drill.TestExerciseRunner --console=plain`

Expected: PASS, 7 tests in `TestSurfaceTargets` (3 new) and 7 in `TestExerciseRunner` (1 new).

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `820/820 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/drill/ComposeExerciseHost.kt compose-app/src/main/kotlin/com/shootoff/compose/targets/SurfaceTargets.kt compose-app/src/test/kotlin/com/shootoff/compose/drill/TestExerciseRunner.kt compose-app/src/test/kotlin/com/shootoff/compose/targets/TestSurfaceTargets.kt
git commit -m "Tag each target with who added it, the shooter or an exercise"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 2: TargetEditor

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetEditor.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetEditor.kt`

**Interfaces:**
- Consumes (Task 1): `SurfaceTargets.owner`, `TargetOwner`; `core`'s `PlacedTarget.boundsAt(Placement)`, `TargetSet.place`, `Rect.contains`.
- Produces (package `com.shootoff.compose.targets`):
  - `enum class Corner(val left: Boolean, val top: Boolean) { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }`, `enum class Arrow { LEFT, RIGHT, UP, DOWN }`.
  - `class TargetEditor(targets: SurfaceTargets, size: () -> Size)` with `companion` constants `KEEP_ON = 10.0`, `STEP = 1.0`, `MIN_SIZE = 10.0`; `val selected: StateFlow<TargetId?>`; `fun targetAt(point: Point): TargetId?`; `fun click(point: Point)`; `fun select(id: TargetId): Boolean`; `fun deselect()`; `fun startDrag(): Boolean`; `fun endDrag()`; `fun dragMove(dx: Double, dy: Double)`; `fun dragResize(corner: Corner, dx: Double, dy: Double, keepAspect: Boolean)`; `fun nudge(arrow: Arrow, resize: Boolean)`; `fun delete()`.
  - Top-level `fun keepOn(bounds: Rect, surface: Size): Pair<Double, Double>`.

**Why.** Spec §5: the gestures live in a class without Compose UI, so each is unit-tested; the screen (Task 6) is a thin layer over it. Rulings 6 and 7 give the details.

- [ ] **Step 1: Write the failing tests**

Create `compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetEditor.kt`:

```kotlin
package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

class TestTargetEditor {
    private val targets = SurfaceTargets(clock = ManualClock())
    private val editor = TargetEditor(targets) { Size(640.0, 480.0) }

    // 100 x 50, its top left corner at (x, y)
    private fun box(x: Double = 100.0, y: Double = 100.0, width: Double = 100.0, height: Double = 50.0, owner: TargetOwner = TargetOwner.USER): TargetId =
        targets.add(
            TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, width, height, "red", mapOf()))),
            ResourceResolver.files(),
            Placement(x, y, 1.0, 1.0, true),
            owner,
        ).id

    private fun bounds(id: TargetId): Rect = targets.set.get(id).get().bounds

    private fun assertBounds(expected: Rect, id: TargetId) {
        val actual = bounds(id)
        val close = listOf(
            expected.minX to actual.minX,
            expected.minY to actual.minY,
            expected.width to actual.width,
            expected.height to actual.height,
        ).all { (e, a) -> Math.abs(e - a) < 1e-6 }
        assertTrue(close, "expected $expected but was $actual")
    }

    @Test
    fun aClickSelectsTheShootersTopmostTargetAndEmptySpaceSelectsNothing() {
        val below = box()
        val above = box(150.0, 120.0)

        editor.click(Point(160.0, 130.0))
        assertEquals(above, editor.selected.value)

        editor.click(Point(110.0, 110.0))
        assertEquals(below, editor.selected.value)

        editor.click(Point(500.0, 400.0))
        assertNull(editor.selected.value)
    }

    @Test
    fun anExercisesTargetCantBeSelectedAndClicksGoThroughIt() {
        val shooters = box()
        val exercises = box(owner = TargetOwner.EXERCISE)

        editor.click(Point(110.0, 110.0))
        assertEquals(shooters, editor.selected.value)

        targets.remove(shooters)
        editor.click(Point(110.0, 110.0))
        assertNull(editor.selected.value)
        assertFalse(editor.select(exercises))
        assertNull(editor.selected.value)
    }

    @Test
    fun aTargetAnExerciseHidIsNotClickedOn() {
        val target = box()
        targets.set.setVisible(target, false)

        editor.click(Point(110.0, 110.0))

        assertNull(editor.selected.value)
    }

    @Test
    fun aDragMovesTheTargetFromWhereTheDragBeganByThePointersWholeMovement() {
        val target = box()
        editor.select(target)

        assertTrue(editor.startDrag())
        editor.dragMove(10.0, 5.0)
        editor.dragMove(30.0, 20.0)
        editor.endDrag()

        assertBounds(Rect(130.0, 120.0, 100.0, 50.0), target)
    }

    @Test
    fun aCornerDragResizesTheTargetAndTheOppositeCornerStaysPut() {
        val target = box()
        editor.select(target)

        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, 50.0, 10.0, keepAspect = false)
        editor.endDrag()
        assertBounds(Rect(100.0, 100.0, 150.0, 60.0), target)

        editor.startDrag()
        editor.dragResize(Corner.TOP_LEFT, -20.0, -10.0, keepAspect = false)
        editor.endDrag()
        assertBounds(Rect(80.0, 90.0, 170.0, 70.0), target)
    }

    @Test
    fun withCtrlHeldACornerDragKeepsTheTargetsShape() {
        val target = box()
        editor.select(target)

        editor.startDrag()
        // The width changed more: it leads, and the height follows
        editor.dragResize(Corner.BOTTOM_RIGHT, 100.0, 10.0, keepAspect = true)
        editor.endDrag()
        assertBounds(Rect(100.0, 100.0, 200.0, 100.0), target)

        editor.startDrag()
        editor.dragResize(Corner.TOP_LEFT, 10.0, 50.0, keepAspect = true)
        editor.endDrag()
        assertBounds(Rect(200.0, 150.0, 100.0, 50.0), target)
    }

    @Test
    fun aResizeStopsAtTheSmallestSize() {
        val target = box()
        editor.select(target)

        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, -500.0, -500.0, keepAspect = false)
        editor.endDrag()

        assertBounds(Rect(100.0, 100.0, TargetEditor.MIN_SIZE, TargetEditor.MIN_SIZE), target)
    }

    @Test
    fun arrowsMoveTheSelectedTargetByOneAndShiftArrowsResizeItAboutItsCenter() {
        val target = box()
        editor.select(target)

        editor.nudge(Arrow.RIGHT, resize = false)
        editor.nudge(Arrow.DOWN, resize = false)
        assertBounds(Rect(101.0, 101.0, 100.0, 50.0), target)
        editor.nudge(Arrow.LEFT, resize = false)
        editor.nudge(Arrow.UP, resize = false)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)

        // Right and Down grow, Left and Up shrink
        editor.nudge(Arrow.RIGHT, resize = true)
        assertBounds(Rect(99.5, 100.0, 101.0, 50.0), target)
        editor.nudge(Arrow.DOWN, resize = true)
        assertBounds(Rect(99.5, 99.5, 101.0, 51.0), target)
        editor.nudge(Arrow.LEFT, resize = true)
        editor.nudge(Arrow.UP, resize = true)
        assertBounds(Rect(100.0, 100.0, 100.0, 50.0), target)
    }

    @Test
    fun deleteRemovesTheSelectedTargetAndSelectsNothing() {
        val target = box()
        editor.select(target)

        editor.delete()

        assertFalse(targets.set.get(target).isPresent)
        assertNull(editor.selected.value)
    }

    @Test
    fun aSelectedTargetRemovedElsewhereIsNoLongerSelected() {
        val target = box()
        editor.select(target)

        targets.remove(target)

        assertNull(editor.selected.value)
    }

    @Test
    fun aDragKeepsAStripOfTheTargetOnTheSurfaceAtEveryEdge() {
        val target = box()
        editor.select(target)

        for ((move, expected) in listOf(
            Point(-1000.0, 0.0) to Rect(-90.0, 100.0, 100.0, 50.0),
            Point(1000.0, 0.0) to Rect(630.0, 100.0, 100.0, 50.0),
            Point(0.0, -1000.0) to Rect(100.0, -40.0, 100.0, 50.0),
            Point(0.0, 1000.0) to Rect(100.0, 470.0, 100.0, 50.0),
        )) {
            editor.startDrag()
            editor.dragMove(move.x, move.y)
            editor.endDrag()
            assertBounds(expected, target)
            editor.startDrag()
            editor.dragMove(100.0 - expected.minX, 100.0 - expected.minY)
            editor.endDrag()
        }
    }

    @Test
    fun aTargetSmallerThanTheStripStaysWhollyOnTheSurface() {
        val target = box(width = 6.0, height = 4.0)
        editor.select(target)

        editor.startDrag()
        editor.dragMove(-1000.0, 1000.0)
        editor.endDrag()

        assertBounds(Rect(0.0, 476.0, 6.0, 4.0), target)
    }

    @Test
    fun nudgesAndResizesKeepTheStripOnTheSurfaceToo() {
        val target = box(x = -90.0)
        editor.select(target)

        editor.nudge(Arrow.LEFT, resize = false)
        assertBounds(Rect(-90.0, 100.0, 100.0, 50.0), target)

        // Narrower from the right, it would leave only 5 units on: it moves back in
        editor.startDrag()
        editor.dragResize(Corner.BOTTOM_RIGHT, -5.0, 0.0, keepAspect = false)
        editor.endDrag()
        assertBounds(Rect(-85.0, 100.0, 95.0, 50.0), target)
    }

    @Test
    fun keepOnSaysHowFarBoundsMustMove() {
        val surface = Size(640.0, 480.0)
        assertEquals(0.0 to 0.0, keepOn(Rect(10.0, 10.0, 50.0, 50.0), surface))
        assertEquals(5.0 to 0.0, keepOn(Rect(-45.0, 10.0, 50.0, 50.0), surface))
        assertEquals(0.0 to -5.0, keepOn(Rect(10.0, 475.0, 50.0, 50.0), surface))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.targets.TestTargetEditor --console=plain`

Expected: compilation fails, `Unresolved reference 'TargetEditor'`.

- [ ] **Step 3: Implement**

Create `compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetEditor.kt`:

```kotlin
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

package com.shootoff.compose.targets

import com.shootoff.geom.Point
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.TargetId
import com.shootoff.targets.model.TargetSetListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The corner of a target a resize drags; the opposite corner stays put. */
enum class Corner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true),
    TOP_RIGHT(false, true),
    BOTTOM_LEFT(true, false),
    BOTTOM_RIGHT(false, false),
}

/** An arrow key */
enum class Arrow { LEFT, RIGHT, UP, DOWN }

/**
 * The Targets screen's editing of one surface's targets, without any UI: which target is selected, and the
 * gestures that move, resize, nudge and remove it, as changes to the surface's [SurfaceTargets]. Only the
 * shooter's targets ([TargetOwner.USER]) can be selected; an exercise's are left alone. Every change keeps
 * part of the target on the surface: a strip [KEEP_ON] units deep, or the whole target if it is smaller.
 *
 * The gestures come on the UI thread; the surface's targets may change on other threads meanwhile (an
 * exercise's, a course loading), and the selection goes if its target does.
 *
 * @param size the surface's size, in its own units
 */
class TargetEditor(private val targets: SurfaceTargets, private val size: () -> Size) {
    companion object {
        /** How much of a target always stays on the surface, in surface units */
        const val KEEP_ON = 10.0

        /** How far an arrow key moves a target, and how much Shift+arrow resizes it, in surface units */
        const val STEP = 1.0

        /** The smallest a resize makes a target, in surface units, unless it was already smaller */
        const val MIN_SIZE = 10.0
    }

    private val selectedState = MutableStateFlow<TargetId?>(null)

    // The selected target's placement and bounds as the drag under way began
    private var dragStart: Placement? = null
    private var dragBounds: Rect? = null

    /** The selected target, or null */
    val selected: StateFlow<TargetId?> = selectedState.asStateFlow()

    init {
        targets.set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) {}

            override fun targetRemoved(target: PlacedTarget) {
                selectedState.compareAndSet(target.id, null)
            }

            override fun targetChanged(target: PlacedTarget) {}
        })
    }

    /**
     * The shooter's topmost shown target whose bounds hold [point]. Clicks go through an exercise's targets,
     * and through a target an exercise has hidden.
     */
    fun targetAt(point: Point): TargetId? = targets.set.targets.asReversed().firstOrNull { target ->
        target.isVisible && targets.owner(target.id) == TargetOwner.USER && target.bounds.contains(point.x, point.y)
    }?.id

    /** A click at [point]: selects the shooter's target there, or nothing */
    fun click(point: Point) {
        selectedState.value = targetAt(point)
    }

    /** @return false, selecting nothing, if [id] isn't one of the shooter's targets on the surface */
    fun select(id: TargetId): Boolean {
        if (targets.owner(id) != TargetOwner.USER) return false
        selectedState.value = id
        return true
    }

    fun deselect() {
        selectedState.value = null
    }

    /**
     * A drag of the selected target begins. Each move or resize after it is placed from where the target
     * was now, by the pointer's whole movement since, so moves the UI coalesces are never lost.
     *
     * @return false if nothing is selected
     */
    fun startDrag(): Boolean {
        val target = selectedTarget() ?: return false
        dragStart = target.placement
        dragBounds = target.bounds
        return true
    }

    fun endDrag() {
        dragStart = null
        dragBounds = null
    }

    /** Moves the selected target by ([dx], [dy]) from where the drag began */
    fun dragMove(dx: Double, dy: Double) {
        val target = selectedTarget() ?: return
        val start = dragStart ?: return
        place(target, start.withPosition(start.x() + dx, start.y() + dy))
    }

    /**
     * Resizes the selected target by dragging [corner] ([dx], [dy]) from where the drag began, the opposite
     * corner staying put. With [keepAspect] (Ctrl held) the target keeps its shape, following whichever
     * side the pointer changed more.
     */
    fun dragResize(corner: Corner, dx: Double, dy: Double, keepAspect: Boolean) {
        val target = selectedTarget() ?: return
        val start = dragStart ?: return
        val from = dragBounds ?: return
        val minWidth = min(MIN_SIZE, from.width)
        val minHeight = min(MIN_SIZE, from.height)
        var width = if (corner.left) from.width - dx else from.width + dx
        var height = if (corner.top) from.height - dy else from.height + dy
        if (keepAspect) {
            val factor = max(
                if (abs(width - from.width) / from.width >= abs(height - from.height) / from.height) width / from.width else height / from.height,
                max(minWidth / from.width, minHeight / from.height),
            )
            width = from.width * factor
            height = from.height * factor
        }
        width = max(width, minWidth)
        height = max(height, minHeight)
        val minX = if (corner.left) from.maxX - width else from.minX
        val minY = if (corner.top) from.maxY - height else from.minY
        place(target, fitted(target, start, Rect(minX, minY, width, height)))
    }

    /**
     * An arrow key: moves the selected target by [STEP], or with [resize] (Shift held) makes it [STEP]
     * wider (Right) or narrower (Left), taller (Down) or shorter (Up), about its center, as the JavaFX app did.
     */
    fun nudge(arrow: Arrow, resize: Boolean) {
        val target = selectedTarget() ?: return
        val p = target.placement
        if (!resize) {
            val (dx, dy) = when (arrow) {
                Arrow.LEFT -> -STEP to 0.0
                Arrow.RIGHT -> STEP to 0.0
                Arrow.UP -> 0.0 to -STEP
                Arrow.DOWN -> 0.0 to STEP
            }
            place(target, p.withPosition(p.x() + dx, p.y() + dy))
            return
        }
        val bounds = target.bounds
        var width = bounds.width
        var height = bounds.height
        when (arrow) {
            Arrow.LEFT -> width = max(width - STEP, min(MIN_SIZE, width))
            Arrow.RIGHT -> width += STEP
            Arrow.UP -> height = max(height - STEP, min(MIN_SIZE, height))
            Arrow.DOWN -> height += STEP
        }
        val center = Point(bounds.minX + bounds.width / 2, bounds.minY + bounds.height / 2)
        place(target, fitted(target, p, Rect(center.x - width / 2, center.y - height / 2, width, height)))
    }

    /** Removes the selected target */
    fun delete() {
        val id = selectedState.value ?: return
        if (targets.owner(id) != TargetOwner.USER) return
        selectedState.value = null
        targets.remove(id)
    }

    private fun selectedTarget(): PlacedTarget? {
        val id = selectedState.value ?: return null
        if (targets.owner(id) != TargetOwner.USER) return null
        return targets.set.get(id).orElse(null)
    }

    // The placement that gives [target] the bounds [wanted]: scaled from [from] by the bounds' change, then
    // moved so its corner lands where asked (a region that keeps its size can leave it a little off)
    private fun fitted(target: PlacedTarget, from: Placement, wanted: Rect): Placement {
        val was = target.boundsAt(from)
        val scaled = from.withScale(from.scaleX() * wanted.width / was.width, from.scaleY() * wanted.height / was.height)
        val now = target.boundsAt(scaled)
        return scaled.withPosition(scaled.x() + wanted.minX - now.minX, scaled.y() + wanted.minY - now.minY)
    }

    // Places [target] at [placement], moved back as far as it takes to keep part of it on the surface
    private fun place(target: PlacedTarget, placement: Placement) {
        val bounds = target.boundsAt(placement)
        val surface = size()
        val (dx, dy) = keepOn(bounds, surface)
        targets.set.place(target.id, placement.withPosition(placement.x() + dx, placement.y() + dy))
    }
}

/**
 * How far [bounds] must move to keep part of it on a [surface]-sized surface: a strip [TargetEditor.KEEP_ON]
 * deep on each axis, or the whole of a smaller target.
 */
fun keepOn(bounds: Rect, surface: Size): Pair<Double, Double> {
    fun shift(low: Double, high: Double, length: Double, extent: Double): Double {
        val strip = min(TargetEditor.KEEP_ON, length)
        return when {
            high < strip -> strip - high
            low > extent - strip -> extent - strip - low
            else -> 0.0
        }
    }
    return shift(bounds.minX, bounds.maxX, bounds.width, surface.width) to shift(bounds.minY, bounds.maxY, bounds.height, surface.height)
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.targets.TestTargetEditor --console=plain`

Expected: PASS, 14 tests.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `834/834 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/targets/TargetEditor.kt compose-app/src/test/kotlin/com/shootoff/compose/targets/TestTargetEditor.kt
git commit -m "Add the target editor: select, move, resize, nudge and remove the shooter's targets"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 3: ArenaLayout: the shooter's arena outlives the window

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaLayout.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt` (`ArenaBackground`, the constructor, `size`, `setSize`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt` (the background it draws)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`arenaLayout`, `openArena`, `closeArena`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt`

**Interfaces:**
- Consumes (Task 1): `SurfaceTargets.targetsOf`, `TargetOwner`.
- Produces:
  - `class ArenaLayout(clock: AnimationClock = AnimationClock.background)` (package `com.shootoff.compose.arena`): `val targets: SurfaceTargets`, `val background: StateFlow<ArenaBackground?>` (the shooter's), `val size: StateFlow<Size>` (starts 640×480), `fun setBackground(background: ArenaBackground?)`, `fun setSize(size: Size)`.
  - `ArenaBackground(image: ImageBitmap, name: String, source: CourseBackground? = null)`, and `ArenaBackground.read(stream, name, source: CourseBackground? = null)`.
  - `ArenaModel(settings, receiver, commands, clock = …, layout: ArenaLayout = ArenaLayout(clock))`, with `val layout`; `targets` is `layout.targets`; `size` is `layout.size`.
  - `AppState.arenaLayout: ArenaLayout`, the one every arena window this session draws.

**Why.** Spec §6 ("Arena closed: editing works on the model; the projector shows the layout when the arena opens"), and rulings 1 and 2.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

Replace:

```kotlin
package com.shootoff.compose.app

import com.shootoff.camera.Shot
import com.shootoff.compose.feed.CalibrationStatus
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.ManualClock
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Point
```

with:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.graphics.ImageBitmap
import com.shootoff.camera.Shot
import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.feed.CalibrationStatus
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.geom.Point
```

Replace:

```kotlin
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
```

with:

```kotlin
import com.shootoff.plugins.ExerciseMetadata
import com.shootoff.plugins.engine.V2ExerciseEntry
import com.shootoff.targets.model.Hit
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
```

Replace:

```kotlin
    @AfterEach
    fun close() = app.close()

    @Test
    fun theCatalogListsTheV2DrillsByName() {
        assertEquals(listOf("Feed drill", "Projector drill"), app.catalog.entries.value.map { it.metadata().name })
```

with:

```kotlin
    @AfterEach
    fun close() = app.close()

    // Spec §6: editing works with the arena closed, and the next window shows the layout
    @Test
    fun theShootersTargetsAndBackgroundOutliveTheArenaWindowButAnExercisesTargetsDont() {
        app.openArena()
        val targets = app.arena.value!!.targets
        val box = TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 10.0, 10.0, "red", mapOf())))
        val shooters = targets.add(box, ResourceResolver.files())
        targets.add(box, ResourceResolver.files(), owner = TargetOwner.EXERCISE)
        val background = ArenaBackground(ImageBitmap(2, 2), "indoor_range.gif")
        app.arenaLayout.setBackground(background)

        app.closeArena()

        assertEquals(listOf(shooters.id), app.arenaLayout.targets.set.targets.map { it.id })
        assertSame(background, app.arenaLayout.background.value)

        app.openArena()

        assertSame(app.arenaLayout.targets, app.arena.value!!.targets)
        assertEquals(listOf(shooters.id), app.arena.value!!.targets.set.targets.map { it.id })
    }

    @Test
    fun theCatalogListsTheV2DrillsByName() {
        assertEquals(listOf("Feed drill", "Projector drill"), app.catalog.entries.value.map { it.metadata().name })
```

`compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt`:

Replace:

```kotlin
        assertEquals(Color.Blue, pixels[637, 357])
    }

    @Test
    fun theArenaSaysItNeedsCalibrationUntilItIsCalibrated() {
        showBothViews()
```

with:

```kotlin
        assertEquals(Color.Blue, pixels[637, 357])
    }

    // The shooter's background sits under calibration's and an exercise's, and the pattern's cover hides it
    @Test
    fun theShootersBackgroundShowsUnlessCalibrationOrAnExerciseHasPutUpItsOwn() {
        arena.layout.setBackground(ArenaBackground(solid(0x0000FF).toComposeImageBitmap(), "blue"))
        arena.setCalibrationLabelVisible(false)
        showBothViews()
        assertEquals(Color.Blue, pixels("projector")[2, 2])
        assertEquals(Color.Blue, pixels("in-app")[2, 2])

        arena.setBackground(ArenaBackground(solid(0x00FF00).toComposeImageBitmap(), "exercise's"))
        compose.waitForIdle()
        assertEquals(Color.Green, pixels("projector")[2, 2])

        arena.setBackground(null)
        compose.waitForIdle()
        assertEquals(Color.Blue, pixels("projector")[2, 2])

        // Calibrating, between its white screen and its pattern
        arena.cover(true)
        compose.waitForIdle()
        assertEquals(Color(0xFF333333), pixels("projector")[2, 2])

        arena.cover(false)
        compose.waitForIdle()
        assertEquals(Color.Blue, pixels("projector")[2, 2])
    }

    private fun solid(rgb: Int) = BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until 10) for (y in 0 until 10) setRGB(x, y, rgb)
    }

    @Test
    fun theArenaSaysItNeedsCalibrationUntilItIsCalibrated() {
        showBothViews()
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestAppState --tests com.shootoff.compose.arena.TestArenaViews --tests com.shootoff.compose.arena.TestArenaModel --console=plain`

Expected: compilation fails, `Unresolved reference 'arenaLayout'` and `Unresolved reference 'layout'`.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
import com.shootoff.camera.autocalibration.PatternDetector
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
```

with:

```kotlin
import com.shootoff.camera.autocalibration.PatternDetector
import com.shootoff.camera.cameratypes.Camera
import com.shootoff.camera.shot.ScaledShot
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.arena.ArenaModel
import com.shootoff.compose.arena.ArenaPlacement
import com.shootoff.compose.arena.ArenaScreens
```

Replace:

```kotlin
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.SavedCalibration
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
```

with:

```kotlin
import com.shootoff.compose.shots.ShotTimerModel
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.SavedCalibration
import com.shootoff.config.Settings
import com.shootoff.exercise.Exercise
```

Replace:

```kotlin
        newHost = ::newHost,
    )

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
```

with:

```kotlin
        newHost = ::newHost,
    )

    /** The shooter's arena targets and background, which every arena window this session draws */
    val arenaLayout = ArenaLayout(clock)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
```

Replace:

```kotlin
        placementState.value = arenaPlacementNow()

        lateinit var arena: ArenaModel
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock)
        arenaState.value = arena

        cameraState.value?.let { makeCalibratable(arena, it) }
```

with:

```kotlin
        placementState.value = arenaPlacementNow()

        lateinit var arena: ArenaModel
        arena = ArenaModel(settings, { runner }, { arenaCommands(arena) }, clock, arenaLayout)
        arenaState.value = arena

        cameraState.value?.let { makeCalibratable(arena, it) }
```

Replace:

```kotlin
        }
    }

    /** The arena window closed: calibration or a check ends, a projector drill stops, and Range asks for setup again. */
    fun closeArena() {
        val arena = arenaState.value ?: return
        stopCheckQuietly()
```

with:

```kotlin
        }
    }

    /**
     * The arena window closed: calibration or a check ends, a projector drill stops, and Range asks for setup
     * again. The shooter's targets and background stay in [arenaLayout] for the next window.
     */
    fun closeArena() {
        val arena = arenaState.value ?: return
        stopCheckQuietly()
```

Replace:

```kotlin
        runner.stopProjectorExercise()
        placementState.value = null
        promptSkippedState.value = false
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }

    // A calibration that ended abruptly (the arena closing, the camera lost) leaves no times behind for the next one
```

with:

```kotlin
        runner.stopProjectorExercise()
        placementState.value = null
        promptSkippedState.value = false
        // The shooter's targets stay for the next arena window; anything an exercise left goes
        arena.targets.targetsOf(TargetOwner.EXERCISE).forEach { arena.targets.remove(it.id) }
    }

    // A calibration that ended abruptly (the arena closing, the camera lost) leaves no times behind for the next one
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`:

Replace:

```kotlin
private val CALIBRATION_ORANGE = Color(0xFFF5A807)

/**
 * The arena as both of its views draw it, fitted to the space it is given: the background stretched over
 * the arena, its targets and shot markers, the "Needs calibration" label, then [overlay] (the exercise's
 * texts and markers) in arena coordinates. While the arena is covered (a calibration pattern showing), only
 * the background is drawn.
 */
```

with:

```kotlin
private val CALIBRATION_ORANGE = Color(0xFFF5A807)

/**
 * The arena as both of its views draw it, fitted to the space it is given: the background (calibration's or
 * an exercise's, else the shooter's) stretched over the arena, its targets and shot markers, the "Needs calibration" label, then [overlay] (the exercise's
 * texts and markers) in arena coordinates. While the arena is covered (a calibration pattern showing), only
 * the background is drawn.
 */
```

Replace:

```kotlin
fun ArenaCanvas(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    val size by arena.size.collectAsState()
    val background by arena.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val grid by arena.grid.collectAsState()
    val covered by arena.covered.collectAsState()
```

with:

```kotlin
fun ArenaCanvas(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    val size by arena.size.collectAsState()
    val background by arena.background.collectAsState()
    val shooters by arena.layout.background.collectAsState()
    val label by arena.needsCalibrationLabel.collectAsState()
    val grid by arena.grid.collectAsState()
    val covered by arena.covered.collectAsState()
```

Replace:

```kotlin
            val topLeft = transform.toView(0.0, 0.0)
            val areaSize = Size((size.width * transform.scale).toFloat(), (size.height * transform.scale).toFloat())
            drawRect(ARENA_GRAY, topLeft, areaSize)
            val image = background?.image ?: return@Canvas
            drawImage(
                image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
```

with:

```kotlin
            val topLeft = transform.toView(0.0, 0.0)
            val areaSize = Size((size.width * transform.scale).toFloat(), (size.height * transform.scale).toFloat())
            drawRect(ARENA_GRAY, topLeft, areaSize)
            // Calibration's or an exercise's own background, else the shooter's, which a calibration pattern covers
            val image = (background ?: shooters.takeUnless { covered })?.image ?: return@Canvas
            drawImage(
                image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
```

Create `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaLayout.kt`:

```kotlin
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

package com.shootoff.compose.arena

import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.geom.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the arena holds whether or not its window is open: its targets, the background the shooter picked,
 * and its size. Each arena window's [ArenaModel] draws it, so the shooter's layout survives the window
 * closing, and the Targets screen edits it while the window is closed.
 */
class ArenaLayout(clock: AnimationClock = AnimationClock.background) {
    /** Every target on the arena, the shooter's and a running exercise's */
    val targets = SurfaceTargets(clock = clock)

    private val backgroundState = MutableStateFlow<ArenaBackground?>(null)
    private val sizeState = MutableStateFlow(Size(640.0, 480.0))

    /**
     * The shooter's background: what the arena shows unless calibration or an exercise has put up one of its
     * own ([ArenaModel.background])
     */
    val background: StateFlow<ArenaBackground?> = backgroundState.asStateFlow()

    /** The arena's size, in its own units: the open window's, or the last one's while it is closed */
    val size: StateFlow<Size> = sizeState.asStateFlow()

    fun setBackground(background: ArenaBackground?) {
        backgroundState.value = background
    }

    fun setSize(size: Size) {
        sizeState.value = size
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt`:

Replace:

```kotlin
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget
```

with:

```kotlin
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.config.Settings
import com.shootoff.courses.CourseBackground
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.PlacedTarget
```

Replace:

```kotlin
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
 * The projector arena: one model that both the projector window and Setup's preview draw, so
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
```

with:

```kotlin
 * An arena background.
 *
 * @param name where it came from (a resource name), for logs
 * @param source where a course finds it again: set on the backgrounds the shooter picks, which are saved
 */
class ArenaBackground(val image: ImageBitmap, val name: String, val source: CourseBackground? = null) {
    companion object {
        fun read(stream: InputStream, name: String, source: CourseBackground? = null): ArenaBackground? =
            stream.use { ImageIO.read(it) }?.let { ArenaBackground(it.toComposeImageBitmap(), name, source) }
    }
}

/**
 * The projector arena, while its window is open: one model that both the projector window and Setup's
 * preview draw, so whatever an exercise does to it (a target hidden, a background set) shows on both.
 *
 * @param layout the shooter's targets and background, and the arena's size, which outlive the window
 */
class ArenaModel(
    private val settings: Settings,
    receiver: () -> ShotReceiver,
    commands: () -> RegionCommandRunner,
    clock: AnimationClock = AnimationClock.background,
    val layout: ArenaLayout = ArenaLayout(clock),
) {
    private val logger = LoggerFactory.getLogger(ArenaModel::class.java)

    val targets: SurfaceTargets = layout.targets
    val markers = ShotMarkers().also { it.setVisible(settings.showArenaShotMarkers()) }

    private val backgroundState = MutableStateFlow<ArenaBackground?>(null)
    private val projectionState = MutableStateFlow<Rect?>(null)
    private val fullScreenState = MutableStateFlow(false)
```

Replace:

```kotlin
    private val coveredState = MutableStateFlow(false)

    /** The arena window's size, in dp: the arena's coordinates */
    val size: StateFlow<Size> = sizeState.asStateFlow()

    val background: StateFlow<ArenaBackground?> = backgroundState.asStateFlow()

    /** The arena's projection on the calibrating camera feed's canvas; null until calibrated */
```

with:

```kotlin
    private val coveredState = MutableStateFlow(false)

    /** The arena window's size, in dp: the arena's coordinates */
    val size: StateFlow<Size> = layout.size

    /**
     * The background calibration (its pattern, its white screen) or a running exercise has put up, over the
     * shooter's ([ArenaLayout.background]); null when neither has one up
     */
    val background: StateFlow<ArenaBackground?> = backgroundState.asStateFlow()

    /** The arena's projection on the calibrating camera feed's canvas; null until calibrated */
```

Replace:

```kotlin
    @Volatile
    var perspective: PerspectiveManager? = null

    val surface = ArenaSurface(settings, targets, markers, { sizeState.value }, receiver, commands)

    fun setSize(size: Size) {
        sizeState.value = size
    }

    fun setFullScreen(fullScreen: Boolean) {
        fullScreenState.value = fullScreen
```

with:

```kotlin
    @Volatile
    var perspective: PerspectiveManager? = null

    val surface = ArenaSurface(settings, targets, markers, { layout.size.value }, receiver, commands)

    fun setSize(size: Size) = layout.setSize(size)

    fun setFullScreen(fullScreen: Boolean) {
        fullScreenState.value = fullScreen
```

Replace:

```kotlin
        }

        if (target.definition.fillsCanvas()) {
            val arena = sizeState.value
            targets.set.resize(target.id, arena.width, arena.height)
            val origin = targets.set.get(target.id).map { it.localToParent(0.0, 0.0) }.orElse(null) ?: return
            targets.set.move(target.id, -origin.x, -origin.y)
```

with:

```kotlin
        }

        if (target.definition.fillsCanvas()) {
            val arena = layout.size.value
            targets.set.resize(target.id, arena.width, arena.height)
            val origin = targets.set.get(target.id).map { it.localToParent(0.0, 0.0) }.orElse(null) ?: return
            targets.set.move(target.id, -origin.x, -origin.y)
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestAppState --tests com.shootoff.compose.arena.TestArenaViews --tests com.shootoff.compose.arena.TestArenaModel --console=plain`

Expected: PASS, the two new tests among them.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `836/836 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaLayout.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaModel.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt compose-app/src/test/kotlin/com/shootoff/compose/arena/TestArenaViews.kt
git commit -m "Keep the shooter's arena layout across arena windows"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 4: CourseLoader, and escaped course files

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/courses/CourseLoader.kt`
- Modify: `core/src/main/java/com/shootoff/courses/io/XMLCourseWriter.java`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/courses/TestCourseLoader.kt`
- Test: `core/src/test/java/com/shootoff/courses/io/TestCourseIO.java`

**Interfaces:**
- Consumes (Tasks 1, 3): `TargetOwner`, `SurfaceTargets.targetsOf`, `ArenaLayout`, `ArenaBackground.read(…, source)`; `core`'s `Course`, `CourseTarget`, `CourseBackground`, `CourseIO`, `TargetDefinitions.load(Path)`, `TargetSet.resize`/`move`.
- Produces (package `com.shootoff.compose.courses`):
  - `data class CourseApplied(val missing: List<String>, val backgroundMissing: String?)`.
  - `class CourseLoader(home: File)`: `fun apply(course: Course, layout: ArenaLayout): CourseApplied`; `fun course(layout: ArenaLayout): Course`; `fun save(course: Course, file: File): Boolean`; `fun readBackground(source: CourseBackground): ArenaBackground?`.

**Why.** Spec §5 and §7; the stored x/y finding is set out under "What a course's x and y mean". Rulings 10 and 13.

- [ ] **Step 1: Write the failing tests**

Create `compose-app/src/test/kotlin/com/shootoff/compose/courses/TestCourseLoader.kt`:

```kotlin
package com.shootoff.compose.courses

import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.Course
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.CourseTarget
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Optional

class TestCourseLoader {
    private val home = File(System.getProperty("user.dir"))
    private val loader = CourseLoader(home)
    private val layout = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }

    private fun shooters() = layout.targets.targetsOf(TargetOwner.USER)

    private fun add(file: String, x: Double, y: Double, owner: TargetOwner = TargetOwner.USER) =
        layout.targets.add(TargetDefinitions.load(File(home, file).toPath()), ResourceResolver.files(), Placement(x, y, 1.0, 1.0, true), owner)

    private fun assertBounds(expected: Rect, actual: Rect, tolerance: Double = 0.01) {
        val close = listOf(
            expected.minX to actual.minX,
            expected.minY to actual.minY,
            expected.width to actual.width,
            expected.height to actual.height,
        ).all { (e, a) -> Math.abs(e - a) < tolerance }
        assertTrue(close, "expected $expected but was $actual")
    }

    // A course's x and y are the target's Placement position (JavaFX's layoutX/layoutY), not its bounds' corner.
    // These are the bounds the JavaFX app's ProjectorArenaPane.setCourse gave accelerator.course (saved at
    // 640x483) on a 1280x720 arena, printed from a JavaFX test at this plan's commit.
    @Test
    fun aBundledSteelChallengeCourseLandsWhereTheJavaFxAppPutIt() {
        val course = CourseIO.loadCourse(File(home, "courses/steel_challenge/accelerator.course")).get()

        val applied = loader.apply(course, layout)

        assertEquals(CourseApplied(emptyList(), null), applied)
        val bounds = shooters().map { it.bounds }
        val javaFx = listOf(
            Rect(316.24, 365.22, 103.51, 274.29),
            Rect(1144.36, 52.92, 79.27, 159.50),
            Rect(623.0, 238.51, 148.0, 211.68),
            Rect(929.44, 60.37, 107.13, 156.52),
            Rect(60.34, 411.43, 149.31, 229.57),
        )
        assertEquals(javaFx.size, bounds.size)
        for ((expected, actual) in javaFx.zip(bounds)) assertBounds(expected, actual)
        // Its first target's position, as JavaFX gave it: well off the target's bounds
        assertEquals(254.0, shooters()[0].placement.x(), 0.01)
        assertEquals(280.36, shooters()[0].placement.y(), 0.01)
    }

    @Test
    fun everyBundledCourseLandsWhollyOnTheOwnersArena() {
        val folder = File(home, "courses/steel_challenge")
        val files = folder.listFiles { f -> f.name.endsWith(".course") }!!.sorted()
        assertEquals(8, files.size)
        for (file in files) {
            loader.apply(CourseIO.loadCourse(file).get(), layout)
            for (target in shooters()) {
                val b = target.bounds
                assertTrue(b.minX >= 0 && b.minY >= 0 && b.maxX <= 1280 && b.maxY <= 720, "${file.name}: $b")
            }
        }
    }

    @Test
    fun aCourseSavedAtTheArenasSizeKeepsEachTargetsPositionAndSize() {
        val course = Course(
            Optional.empty(),
            listOf(CourseTarget(File("targets/IPSC.target"), 200.0, 50.0, 90.0, 114.0)),
            Optional.of(Size(1280.0, 720.0)),
        )

        loader.apply(course, layout)

        val target = shooters().single()
        assertEquals(200.0, target.placement.x(), 0.001)
        assertEquals(50.0, target.placement.y(), 0.001)
        assertEquals(90.0, target.bounds.width, 0.001)
        assertEquals(114.0, target.bounds.height, 0.001)
    }

    @Test
    fun whatIsSavedLoadsBackAsItWas(@TempDir temp: Path) {
        val ipsc = add("targets/IPSC.target", 100.0, 80.0)
        layout.targets.set.resize(ipsc.id, 45.0, 57.0)
        add("targets/Reset.target", 900.0, 600.0)
        val source = CourseBackground("/arena/backgrounds/indoor_range.gif", true)
        layout.setBackground(loader.readBackground(source))
        val before = shooters().map { it.bounds }
        val file = temp.resolve("mine.course").toFile()

        assertTrue(loader.save(loader.course(layout), file))
        val again = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }
        loader.apply(CourseIO.loadCourse(file).get(), again)

        for ((expected, target) in before.zip(again.targets.targetsOf(TargetOwner.USER))) assertBounds(expected, target.bounds, 0.001)
        assertEquals(source, again.background.value!!.source)
        // Named as the JavaFX app named them, relative to ShootOFF's folder
        assertEquals(listOf(File("targets/IPSC.target"), File("targets/Reset.target")), CourseIO.loadCourse(file).get().targets.map { it.file() })
        assertEquals(Optional.of(Size(1280.0, 720.0)), CourseIO.loadCourse(file).get().resolution)
    }

    @Test
    fun aMissingTargetFileIsSkippedAndNamedAndTheRestLoad() {
        val course = Course(
            Optional.empty(),
            listOf(
                CourseTarget(File("targets/no_such_target.target"), 0.0, 0.0, 10.0, 10.0),
                CourseTarget(File("targets/Reset.target"), 10.0, 100.0, 10.0, 1.0),
            ),
            Optional.of(Size(1280.0, 720.0)),
        )

        val applied = loader.apply(course, layout)

        assertEquals(listOf("targets/no_such_target.target"), applied.missing)
        assertEquals(1, shooters().size)
    }

    @Test
    fun anExercisesTargetsAreLeftOutOfACourseAndLeftAloneByALoad() {
        add("targets/Reset.target", 10.0, 10.0)
        val exercises = add("targets/IPSC.target", 300.0, 300.0, TargetOwner.EXERCISE)

        assertEquals(listOf(File("targets/Reset.target")), loader.course(layout).targets.map { it.file() })

        loader.apply(Course(Optional.empty(), listOf(CourseTarget(File("targets/IPSC.target"), 0.0, 0.0, 90.0, 114.0)), Optional.empty()), layout)

        assertEquals(listOf(exercises.id), layout.targets.targetsOf(TargetOwner.EXERCISE).map { it.id })
        assertEquals(Placement(300.0, 300.0, 1.0, 1.0, true), layout.targets.set.get(exercises.id).get().placement)
        assertEquals(listOf("targets/IPSC.target"), shooters().map { home.toPath().relativize(it.definition.file().get().toPath()).toString() })
    }

    @Test
    fun aCourseWithoutAReadableBackgroundKeepsTheCurrentOneAndOneItCantReadIsNamed() {
        val current = loader.readBackground(CourseBackground("/arena/backgrounds/indoor_range.gif", true))
        layout.setBackground(current)

        loader.apply(Course(Optional.empty(), emptyList(), Optional.empty()), layout)
        assertSame(current, layout.background.value)

        val applied = loader.apply(Course(Optional.of(CourseBackground("/arena/backgrounds/no_such.gif", true)), emptyList(), Optional.empty()), layout)
        assertSame(current, layout.background.value)
        assertEquals("/arena/backgrounds/no_such.gif", applied.backgroundMissing)

        loader.apply(Course(Optional.of(CourseBackground("/arena/backgrounds/outdoor_range.gif", true)), emptyList(), Optional.empty()), layout)
        assertEquals("/arena/backgrounds/outdoor_range.gif", layout.background.value?.source?.url())
    }

    @Test
    fun aBackgroundFileIsReadFromItsUrl(@TempDir temp: Path) {
        val file = temp.resolve("range.png").toFile()
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", file)
        val source = CourseBackground(file.toURI().toString(), false)

        val background = loader.readBackground(source)!!

        assertEquals(source, background.source)
        assertEquals(4, background.image.width)
    }

    @Test
    fun aCourseThatCantBeWrittenLeavesTheOldFileAlone(@TempDir temp: Path) {
        add("targets/Reset.target", 10.0, 10.0)
        val file = temp.resolve("mine.course").toFile()
        file.writeText("old")

        assertFalse(loader.save(loader.course(layout), temp.resolve("no_such_folder/mine.course").toFile()))
        assertEquals("old", file.readText())
        assertTrue(loader.save(loader.course(layout), file))
        assertTrue(CourseIO.loadCourse(file).isPresent)
        assertFalse(File(temp.toFile(), ".mine.course").exists())
    }
}
```

`core/src/test/java/com/shootoff/courses/io/TestCourseIO.java`:

Replace:

```java
		if (!tempXMLCourse.delete()) System.err.println("Failed to delete " + tempXMLCourse.getPath());
	}

	@Test
	public void testCourseDoesntExist() {
		assertEquals(Optional.empty(), CourseIO.loadCourse(new File("does_not_exist.course")));
```

with:

```java
		if (!tempXMLCourse.delete()) System.err.println("Failed to delete " + tempXMLCourse.getPath());
	}

	// A background the owner picked from a folder named "Range & Bay" made the whole course unreadable
	@Test
	public void aPathOrUrlWithXmlSpecialCharactersRoundTrips() throws IOException {
		final Course special = new Course(Optional.of(new CourseBackground("file:/tmp/Range%20&%20\"Bay\"/<a>.png", false)),
				List.of(new CourseTarget(new File("targets/A & \"B\" <c>.target"), 10, 100, 10, 1)),
				Optional.of(new Size(1280, 720)));
		final File file = File.createTempFile("special", ".course");
		file.deleteOnExit();

		CourseIO.saveCourse(special, file);
		final Optional<Course> loaded = CourseIO.loadCourse(file);

		assertTrue(loaded.isPresent());
		assertEquals(special.getBackground(), loaded.get().getBackground());
		assertEquals(special.getTargets(), loaded.get().getTargets());
	}

	@Test
	public void testCourseDoesntExist() {
		assertEquals(Optional.empty(), CourseIO.loadCourse(new File("does_not_exist.course")));
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.courses.io.TestCourseIO :compose-app:test --tests com.shootoff.compose.courses.TestCourseLoader --console=plain`

Expected: `compose-app` compilation fails, `Unresolved reference 'CourseLoader'`. For `core`: `./gradlew :core:test --tests com.shootoff.courses.io.TestCourseIO --console=plain` fails `aPathOrUrlWithXmlSpecialCharactersRoundTrips` (the course written with a raw `"` in an attribute can't be read back).

- [ ] **Step 3: Implement**

Create `compose-app/src/main/kotlin/com/shootoff/compose/courses/CourseLoader.kt`:

```kotlin
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

package com.shootoff.compose.courses

import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.Course
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.CourseTarget
import com.shootoff.courses.io.CourseIO
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetFormatException
import com.shootoff.targets.model.TargetId
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Optional
import kotlin.math.abs

/**
 * What applying a course did.
 *
 * @param missing the course's target files that couldn't be loaded, as the course names them
 * @param backgroundMissing the course's background, if it couldn't be read
 */
data class CourseApplied(val missing: List<String>, val backgroundMissing: String?)

/**
 * Applies courses to the arena's layout and makes courses from it, as the JavaFX arena's setCourse and
 * getCourse did. Only the shooter's targets ([TargetOwner.USER]) are touched or saved: a running
 * exercise's stay where they are and are never part of a course.
 *
 * A course target's x and y are its [Placement] position (JavaFX's layoutX and layoutY): where the
 * target's own (0, 0) would be unscaled. A target is scaled about its center, so a small target made from
 * a big image has a position well off its bounds, even a negative one, as in the bundled Steel Challenge
 * courses. Its width and height are its bounds' size on the arena.
 *
 * @param home ShootOFF's folder: course target files are named relative to it
 */
class CourseLoader(private val home: File) {
    private val logger = LoggerFactory.getLogger(CourseLoader::class.java)

    /**
     * Replaces the shooter's targets with [course]'s, and the background too when the course has one it can
     * read (a course without one, or with one that can't be read, keeps the current background, as the JavaFX
     * app did). A course saved at another arena size is scaled to [layout]'s, by width and height separately.
     * A target file that can't be loaded is skipped, and a background that can't be read is reported.
     */
    fun apply(course: Course, layout: ArenaLayout): CourseApplied {
        val targets = layout.targets
        for (target in targets.targetsOf(TargetOwner.USER)) targets.remove(target.id)

        val background = course.background.orElse(null)
        val image = background?.let(::readBackground)
        if (image != null) layout.setBackground(image)

        val arena = layout.size.value
        val resolution = course.resolution.orElse(null)
        val scale = resolution != null && (abs(resolution.width - arena.width) > .0001 || abs(resolution.height - arena.height) > .0001)
        val widthFactor = if (scale) arena.width / resolution!!.width else 1.0
        val heightFactor = if (scale) arena.height / resolution!!.height else 1.0

        val missing = mutableListOf<String>()
        for (courseTarget in course.targets) {
            val file = resolve(courseTarget.file())
            val definition = try {
                TargetDefinitions.load(file.toPath())
            } catch (e: TargetFormatException) {
                logger.warn("Can't load the course's target {}: {}", courseTarget.file().path, e.message)
                missing += courseTarget.file().path
                continue
            }
            val placed = targets.add(definition, ResourceResolver.files(), Placement(courseTarget.x(), courseTarget.y(), 1.0, 1.0, true), TargetOwner.USER)
            targets.set.resize(placed.id, courseTarget.width(), courseTarget.height())
            if (scale) scale(placed.id, layout, widthFactor, heightFactor)
        }

        return CourseApplied(missing, if (background != null && image == null) background.url() else null)
    }

    /** The shooter's targets and background, and the arena's size, as a course */
    fun course(layout: ArenaLayout): Course {
        val targets = layout.targets.targetsOf(TargetOwner.USER).mapNotNull { target ->
            val file = target.definition.file().orElse(null) ?: return@mapNotNull null
            val bounds = target.bounds
            CourseTarget(relative(file), target.placement.x(), target.placement.y(), bounds.width, bounds.height)
        }
        return Course(Optional.ofNullable(layout.background.value?.source), targets, Optional.of(layout.size.value))
    }

    /**
     * Writes [course] to [file], replacing it whole: it is written beside it first, so a failure leaves the
     * old file as it was.
     *
     * @return false if it couldn't be written
     */
    fun save(course: Course, file: File): Boolean {
        // Hidden, and still ending in "course", which CourseIO insists on
        val part = File(file.absoluteFile.parentFile, ".${file.name}")
        part.delete()
        CourseIO.saveCourse(course, part)
        if (!part.isFile || part.length() == 0L) {
            logger.error("Couldn't write the course {}", file)
            return false
        }
        return try {
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            logger.error("Couldn't write the course {}", file, e)
            part.delete()
            false
        }
    }

    /** A course's background, or null if it can't be read */
    fun readBackground(source: CourseBackground): ArenaBackground? {
        val image = try {
            val stream = if (source.isResource()) {
                ArenaBackground::class.java.getResourceAsStream(source.url())
            } else {
                URI(source.url()).toURL().openStream()
            }
            stream?.let { ArenaBackground.read(it, source.url(), source) }
        } catch (e: Exception) {
            logger.warn("Can't read the arena background {}", source.url(), e)
            return null
        }
        if (image == null) logger.warn("Can't read the arena background {}", source.url())
        return image
    }

    // TargetView.scale, step for step: the target's bounds scale about the arena's origin
    private fun scale(id: TargetId, layout: ArenaLayout, widthFactor: Double, heightFactor: Double) {
        val set = layout.targets.set
        val target = set.get(id).orElse(null) ?: return
        val bounds = target.bounds
        val newWidth = bounds.width * widthFactor
        val deltaX = bounds.minX * widthFactor - bounds.minX + (newWidth - bounds.width) / 2
        val newHeight = bounds.height * heightFactor
        val deltaY = bounds.minY * heightFactor - bounds.minY + (newHeight - bounds.height) / 2
        set.move(id, target.placement.x() + deltaX, target.placement.y() + deltaY)
        set.resize(id, newWidth, newHeight)
    }

    private fun resolve(file: File): File = if (file.isAbsolute) file else File(home, file.path)

    // Relative to ShootOFF's folder when inside it, as the JavaFX app saved them
    private fun relative(file: File): File {
        val path = file.absoluteFile.toPath().normalize()
        val base = home.absoluteFile.toPath().normalize()
        return if (path.startsWith(base)) base.relativize(path).toFile() else path.toFile()
    }
}
```

`core/src/main/java/com/shootoff/courses/io/XMLCourseWriter.java`:

Replace:

```java

	@Override
	public void visitBackground(String url, boolean isResource) {
		xmlBody.append(String.format("\t<background url=\"%s\" isResource=\"%b\" />%n", url, isResource));
	}

	@Override
	public void visitTarget(File targetFile, double x, double y, double width, double height) {
		xmlBody.append(
				String.format(Locale.US, "\t<target file=\"%s\" x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" />%n",
						targetFile.getPath(), x, y, width, height));
	}

	@Override
```

with:

```java

	@Override
	public void visitBackground(String url, boolean isResource) {
		xmlBody.append(String.format("\t<background url=\"%s\" isResource=\"%b\" />%n", escape(url), isResource));
	}

	@Override
	public void visitTarget(File targetFile, double x, double y, double width, double height) {
		xmlBody.append(
				String.format(Locale.US, "\t<target file=\"%s\" x=\"%f\" y=\"%f\" width=\"%f\" height=\"%f\" />%n",
						escape(targetFile.getPath()), x, y, width, height));
	}

	// An attribute value as XML: a path or URL with &, <, > or a quote in it would otherwise make the whole
	// course unreadable
	private static String escape(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	@Override
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :core:test --tests com.shootoff.courses.io.TestCourseIO :compose-app:test --tests com.shootoff.compose.courses.TestCourseLoader --console=plain`

Expected: PASS, 5 tests in `TestCourseIO` and 9 in `TestCourseLoader`.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `846/846 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/courses/CourseLoader.kt compose-app/src/test/kotlin/com/shootoff/compose/courses/TestCourseLoader.kt core/src/main/java/com/shootoff/courses/io/XMLCourseWriter.java core/src/test/java/com/shootoff/courses/io/TestCourseIO.java
git commit -m "Load and save courses on the Compose arena, scaled as the JavaFX app scaled them"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 5: LayoutMemory

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/courses/LayoutMemory.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaLayout.kt` (a background listener)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt`

**Interfaces:**
- Consumes (Tasks 1, 3, 4): `SurfaceTargets.owner`, `ArenaLayout`, `CourseLoader.apply`/`course`/`save`; `core`'s `CalibrationFlow.Scheduler` (`Future<?> schedule(Runnable task, long delayMillis)`).
- Produces:
  - `ArenaLayout.addBackgroundListener(listener: Runnable)`: runs on the caller's thread whenever the shooter's background is set.
  - `class LayoutMemory(layout: ArenaLayout, loader: CourseLoader, file: File, timers: CalibrationFlow.Scheduler, quietMillis: Long = QUIET_MILLIS)` with `companion` `FILE_NAME = "arena-layout.course"`, `QUIET_MILLIS = 500L`; `fun start()`; `fun restore(): Boolean`; `val changedThisSession: Boolean`. (Task 9 adds `fun flush()`.)

**Why.** Spec §5 ("after 500 ms without further changes, written off the UI thread") and §6 (a missing or unreadable file, or a missing target file, leaves the arena empty or without that target, with a log line). The timers are injected, so the quiet period is tested without waiting. Ruling 4 says why a restore is skipped once the shooter has changed the layout.

- [ ] **Step 1: Write the failing tests**

Create `compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt`:

```kotlin
package com.shootoff.compose.courses

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

class TestLayoutMemory {
    /** Timers that run only when told to */
    private class ManualTimers : CalibrationFlow.Scheduler {
        val scheduled = mutableListOf<Pair<Long, FutureTask<Unit>>>()

        override fun schedule(task: Runnable, delayMillis: Long): Future<*> = FutureTask(task, Unit).also { scheduled += delayMillis to it }

        fun live() = scheduled.filterNot { it.second.isCancelled }

        fun runLive() = live().forEach { it.second.run() }
    }

    @TempDir
    lateinit var temp: Path

    private val home = File(System.getProperty("user.dir"))
    private val loader = CourseLoader(home)
    private val timers = ManualTimers()

    private fun layout() = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }

    private fun file() = temp.resolve(LayoutMemory.FILE_NAME).toFile()

    private fun memory(layout: ArenaLayout) = LayoutMemory(layout, loader, file(), timers).also { it.start() }

    private fun add(layout: ArenaLayout, owner: TargetOwner = TargetOwner.USER) = layout.targets.add(
        TargetDefinitions.load(File(home, "targets/Reset.target").toPath()),
        ResourceResolver.files(),
        Placement(10.0, 20.0, 1.0, 1.0, true),
        owner,
    )

    @Test
    fun theLayoutIsSavedOnceTheChangesStopNotOncePerChange() {
        val layout = layout()
        memory(layout)

        val target = add(layout)
        layout.targets.set.move(target.id, 30.0, 40.0)
        layout.targets.set.move(target.id, 50.0, 60.0)

        assertEquals(listOf(500L, 500L, 500L), timers.scheduled.map { it.first })
        assertEquals(1, timers.live().size)
        assertFalse(file().exists())

        timers.runLive()

        val saved = CourseIO.loadCourse(file()).get()
        assertEquals(listOf(50.0 to 60.0), saved.targets.map { it.x() to it.y() })
        assertEquals(Size(1280.0, 720.0), saved.resolution.get())
    }

    @Test
    fun aNewBackgroundIsSaved() {
        val layout = layout()
        memory(layout)
        val source = CourseBackground("/arena/backgrounds/indoor_range.gif", true)

        layout.setBackground(loader.readBackground(source))
        timers.runLive()

        assertEquals(source, CourseIO.loadCourse(file()).get().background.get())
    }

    @Test
    fun anExercisesTargetsComingAndMovingAreNoReasonToSave() {
        val layout = layout()
        memory(layout)

        val target = add(layout, TargetOwner.EXERCISE)
        layout.targets.set.move(target.id, 30.0, 40.0)

        assertTrue(timers.scheduled.isEmpty())
    }

    @Test
    fun theRememberedLayoutComesBackAndBringingItBackIsNoChange() {
        val first = layout()
        memory(first)
        add(first)
        first.setBackground(loader.readBackground(CourseBackground("/arena/backgrounds/indoor_range.gif", true)))
        timers.runLive()
        timers.scheduled.clear()

        val next = layout()
        val memory = memory(next)
        assertTrue(memory.restore())

        assertEquals(listOf(Placement(10.0, 20.0, 1.0, 1.0, true)), next.targets.targetsOf(TargetOwner.USER).map { it.placement })
        assertEquals("/arena/backgrounds/indoor_range.gif", next.background.value!!.source!!.url())
        assertTrue(timers.scheduled.isEmpty())
        assertFalse(memory.changedThisSession)
    }

    @Test
    fun withoutARememberedLayoutTheArenaStartsEmpty() {
        val layout = layout()

        assertFalse(memory(layout).restore())

        assertTrue(layout.targets.set.targets.isEmpty())
        assertNull(layout.background.value)
    }

    @Test
    fun aRememberedLayoutThatCantBeReadLeavesTheArenaEmpty() {
        file().writeText("<course><target file=")
        val layout = layout()

        assertFalse(memory(layout).restore())

        assertTrue(layout.targets.set.targets.isEmpty())
    }

    @Test
    fun aTargetTheRememberedLayoutNamesThatIsGoneIsLeftOut() {
        file().writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <course>
            	<target file="targets/no_such_target.target" x="0" y="0" width="10" height="10" />
            	<target file="targets/Reset.target" x="10" y="20" width="10" height="1" />
            	<resolution width="1280" height="720" />
            </course>
            """.trimIndent(),
        )
        val layout = layout()

        assertTrue(memory(layout).restore())

        assertEquals(1, layout.targets.set.targets.size)
    }

    @Test
    fun onceTheShooterHasChangedTheLayoutTheRememberedOneIsLeftAlone() {
        val first = layout()
        memory(first)
        add(first)
        timers.runLive()

        val next = layout()
        val memory = memory(next)
        next.setBackground(null)

        assertFalse(memory.restore())
        assertTrue(next.targets.set.targets.isEmpty())
        assertTrue(memory.changedThisSession)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.courses.TestLayoutMemory --console=plain`

Expected: compilation fails, `Unresolved reference 'LayoutMemory'`.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaLayout.kt`:

Replace:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the arena holds whether or not its window is open: its targets, the background the shooter picked,
```

with:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the arena holds whether or not its window is open: its targets, the background the shooter picked,
```

Replace:

```kotlin

    private val backgroundState = MutableStateFlow<ArenaBackground?>(null)
    private val sizeState = MutableStateFlow(Size(640.0, 480.0))

    /**
     * The shooter's background: what the arena shows unless calibration or an exercise has put up one of its
```

with:

```kotlin

    private val backgroundState = MutableStateFlow<ArenaBackground?>(null)
    private val sizeState = MutableStateFlow(Size(640.0, 480.0))
    private val backgroundListeners = CopyOnWriteArrayList<Runnable>()

    /**
     * The shooter's background: what the arena shows unless calibration or an exercise has put up one of its
```

Replace:

```kotlin

    fun setBackground(background: ArenaBackground?) {
        backgroundState.value = background
    }

    fun setSize(size: Size) {
```

with:

```kotlin

    fun setBackground(background: ArenaBackground?) {
        backgroundState.value = background
        backgroundListeners.forEach(Runnable::run)
    }

    /** [listener] runs, on the caller's thread, whenever the shooter's background is set */
    fun addBackgroundListener(listener: Runnable) {
        backgroundListeners += listener
    }

    fun setSize(size: Size) {
```

Create `compose-app/src/main/kotlin/com/shootoff/compose/courses/LayoutMemory.kt`:

```kotlin
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

package com.shootoff.compose.courses

import com.shootoff.calibration.CalibrationFlow
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.io.CourseIO
import com.shootoff.targets.model.PlacedTarget
import com.shootoff.targets.model.TargetSetListener
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Future

/**
 * Remembers the shooter's arena between sessions: after a change to their targets or background, and
 * [quietMillis] without another, it saves the layout as an ordinary course, on [timers]' thread; [restore]
 * loads it back. An exercise's targets are neither saved nor, as they come and go, a reason to save.
 *
 * @param file the course the layout is kept in, `arena-layout.course` in ShootOFF's folder
 * @param timers runs the save off the UI thread once the changes stop
 */
class LayoutMemory(
    private val layout: ArenaLayout,
    private val loader: CourseLoader,
    private val file: File,
    private val timers: CalibrationFlow.Scheduler,
    private val quietMillis: Long = QUIET_MILLIS,
) {
    companion object {
        const val FILE_NAME = "arena-layout.course"
        const val QUIET_MILLIS = 500L
    }

    private val logger = LoggerFactory.getLogger(LayoutMemory::class.java)
    private val lock = Any()

    // Guarded by lock: the save waiting for the changes to stop
    private var pending: Future<*>? = null

    // Set while restore applies the remembered layout, whose own changes aren't the shooter's
    @Volatile
    private var restoring = false

    @Volatile
    private var changed = false

    /** Whether the shooter has changed the layout this session: once they have, [restore] leaves it alone */
    val changedThisSession: Boolean get() = changed

    /** Starts watching the layout for the shooter's changes */
    fun start() {
        layout.targets.set.addListener(object : TargetSetListener {
            override fun targetAdded(target: PlacedTarget) {
                if (layout.targets.owner(target.id) == TargetOwner.USER) changedByTheShooter()
            }

            // Its owner is already gone; an exercise's leaving costs a save that changes nothing
            override fun targetRemoved(target: PlacedTarget) = changedByTheShooter()

            override fun targetChanged(target: PlacedTarget) {
                if (layout.targets.owner(target.id) == TargetOwner.USER) changedByTheShooter()
            }
        })
        layout.addBackgroundListener { changedByTheShooter() }
    }

    /**
     * Loads the remembered layout through [CourseLoader], unless the shooter has changed this session's
     * already (their changes have replaced it). A missing or unreadable file leaves the arena empty, and a
     * target file it names that can't be loaded is left out; each is logged, with no word to the shooter.
     *
     * @return whether the layout was loaded
     */
    fun restore(): Boolean {
        if (changed) {
            logger.info("The arena layout changed before it could be restored: {} is left as it is", file)
            return false
        }
        if (!file.isFile) {
            logger.info("No remembered arena layout ({}): the arena starts empty", file)
            return false
        }
        val course = CourseIO.loadCourse(file).orElse(null)
        if (course == null) {
            logger.warn("Can't read the remembered arena layout {}: the arena starts empty", file)
            return false
        }
        restoring = true
        val applied = try {
            loader.apply(course, layout)
        } finally {
            restoring = false
        }
        for (missing in applied.missing) logger.warn("The remembered arena layout's target {} can't be loaded: it is left out", missing)
        applied.backgroundMissing?.let { logger.warn("The remembered arena layout's background {} can't be read: it is left out", it) }
        logger.info("Restored the arena layout from {}", file)
        return true
    }

    private fun changedByTheShooter() {
        if (restoring) return
        changed = true
        synchronized(lock) {
            pending?.cancel(false)
            pending = timers.schedule(::save, quietMillis)
        }
    }

    // One save at a time: a slow one still running when the next is due finishes first
    @Synchronized
    private fun save() {
        if (loader.save(loader.course(layout), file)) logger.debug("Saved the arena layout to {}", file)
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.courses.TestLayoutMemory --console=plain`

Expected: PASS, 8 tests.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `854/854 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaLayout.kt compose-app/src/main/kotlin/com/shootoff/compose/courses/LayoutMemory.kt compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt
git commit -m "Remember the shooter's arena layout between sessions"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 6: The Targets screen: placing and editing the arena's targets

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/targets/EditingOverlay.kt`
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsModel.kt` (Task 7 grows it)
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsScreen.kt` (Task 8 grows it)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt` (new `ArenaLayoutView`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`targetsModel`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsScreen.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/shell/TestAppRail.kt` (`targetsAndSessionsAreShownButDisabled` becomes `sessionsIsShownButDisabled`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt` (`targetsAndSessionsCantBeNavigatedTo` becomes `targetsCanBeNavigatedToButSessionsCant`)

**Interfaces:**
- Consumes (Tasks 1–3): `DrawnTarget.owner`/`bounds`, `TargetEditor`, `Corner`, `Arrow`, `ArenaLayout`, `AppState.arenaLayout`; `SurfaceTransform.fit`/`toView`/`toSurface`.
- Produces:
  - `@Composable fun EditingOverlay(targets: SurfaceTargets, editor: TargetEditor, transform: SurfaceTransform, modifier: Modifier = Modifier)`, test tags `editing-surface`, `handle-<CORNER>`, `exercise-badge`; `fun editKey(editor: TargetEditor, event: KeyEvent): Boolean`.
  - `@Composable fun ArenaLayoutView(layout: ArenaLayout, arena: ArenaModel?, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {})`.
  - `const val CALIBRATING_NOTE`; `class TargetsModel(val layout: ArenaLayout)` with `val arenaEditor: TargetEditor` (Task 7 replaces the class).
  - `@Composable fun TargetsScreen(app: AppState, modifier: Modifier = Modifier)`, test tags `targets-screen`, `targets-arena`, `calibrating-note`.
  - `AppState.targetsModel: TargetsModel`; `Destination.TARGETS` enabled.

**Why.** Spec §4 (the screen, mouse, keyboard, exercise targets, the calibrating note) and §6. Ruling 3 says why the screen draws the layout itself.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt`:

Replace:

```kotlin
    }

    @Test
    fun targetsAndSessionsCantBeNavigatedTo() {
        app.navigate(Destination.TARGETS)
        app.navigate(Destination.SESSIONS)

        assertEquals(Destination.RANGE, app.destination.value)
    }

    @Test
```

with:

```kotlin
    }

    @Test
    fun targetsCanBeNavigatedToButSessionsCant() {
        app.navigate(Destination.TARGETS)
        assertEquals(Destination.TARGETS, app.destination.value)

        app.navigate(Destination.SESSIONS)
        assertEquals(Destination.TARGETS, app.destination.value)
    }

    @Test
```

Create `compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsScreen.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.shootoff.compose.arena.ArenaLayoutView
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.EditingOverlay
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.RectangleRegion
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.Optional

@OptIn(ExperimentalTestApi::class)
class TestTargetsScreen {
    @get:Rule
    val compose = createComposeRule()

    private val app = AppFixture.app()
    private val layout = app.arenaLayout
    private val editor = app.targetsModel.arenaEditor

    @After
    fun close() = app.close()

    // A red square, 100 arena units a side, with its top left corner at (x, y)
    private fun square(x: Double, y: Double, owner: TargetOwner = TargetOwner.USER): TargetId = layout.targets.add(
        TargetDefinition(Optional.empty(), mapOf(), listOf(RectangleRegion(0, 0.0, 0.0, 100.0, 100.0, "red", mapOf("opacity" to "1")))),
        ResourceResolver.files(),
        Placement(x, y, 1.0, 1.0, true),
        owner,
    ).id

    private fun bounds(id: TargetId): Rect = layout.targets.set.get(id).get().bounds

    // The arena (1280x720) at half size, as on the Targets screen, at one pixel per dp
    private fun showEditor() {
        layout.setSize(Size(1280.0, 720.0))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                RangeTheme(dark = true) {
                    ArenaLayoutView(layout, app.arena.value, Modifier.size(640.dp, 360.dp).testTag("view")) { transform ->
                        EditingOverlay(layout.targets, editor, transform)
                    }
                }
            }
        }
    }

    @Test
    fun theRailLeadsToTheTargetsScreenWhichWorksWithTheArenaClosed() {
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

        compose.onNodeWithTag("rail-TARGETS").performClick()

        compose.onNodeWithTag("targets-screen").assertExists()
        compose.onNodeWithTag("targets-arena").assertExists()
        compose.onAllNodesWithTag("calibrating-note").assertCountEquals(0)
    }

    @Test
    fun aClickSelectsATargetAndShowsItsHandlesAndAClickOnEmptySpaceDeselects() {
        val target = square(200.0, 100.0)
        showEditor()

        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }
        assertEquals(target, editor.selected.value)
        compose.onAllNodesWithTag("handle-TOP_LEFT").assertCountEquals(1)

        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(500f, 300f)) }
        assertNull(editor.selected.value)
        compose.onAllNodesWithTag("handle-TOP_LEFT").assertCountEquals(0)
    }

    @Test
    fun draggingATargetMovesItOnTheArena() {
        val target = square(200.0, 100.0)
        showEditor()

        compose.onNodeWithTag("editing-surface").performMouseInput {
            moveTo(Offset(125f, 75f))
            press()
            moveBy(Offset(20f, 10f))
            moveBy(Offset(20f, 10f))
            release()
        }
        compose.waitForIdle()

        // 40 x 20 view pixels are 80 x 40 arena units
        assertEquals(Rect(280.0, 140.0, 100.0, 100.0), bounds(target))
    }

    @Test
    fun draggingACornerHandleResizesTheTarget() {
        val target = square(200.0, 100.0)
        showEditor()
        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }

        compose.onNodeWithTag("handle-BOTTOM_RIGHT").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(25f, 10f))
            release()
        }
        compose.waitForIdle()

        assertEquals(Rect(200.0, 100.0, 150.0, 120.0), bounds(target))
    }

    @Test
    fun theKeysNudgeResizeRemoveAndDeselectTheSelectedTarget() {
        val target = square(200.0, 100.0)
        showEditor()
        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }
        val surface = compose.onNodeWithTag("editing-surface")

        surface.performKeyInput { pressKey(Key.DirectionRight) }
        surface.performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(Rect(201.0, 101.0, 100.0, 100.0), bounds(target))

        surface.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionRight) } }
        assertEquals(101.0, bounds(target).width, 1e-9)

        surface.performKeyInput { pressKey(Key.Escape) }
        assertNull(editor.selected.value)

        compose.onNodeWithTag("editing-surface").performMouseInput { click(Offset(125f, 75f)) }
        surface.performKeyInput { pressKey(Key.Delete) }
        assertFalse(layout.targets.set.get(target).isPresent)
    }

    @Test
    fun anExercisesTargetIsBadgedAndCantBeGrabbed() {
        val target = square(200.0, 100.0, TargetOwner.EXERCISE)
        showEditor()

        compose.onAllNodesWithTag("exercise-badge").assertCountEquals(1)
        compose.onNodeWithTag("editing-surface").performMouseInput {
            moveTo(Offset(125f, 75f))
            press()
            moveBy(Offset(40f, 20f))
            release()
        }
        compose.waitForIdle()

        assertNull(editor.selected.value)
        assertEquals(Rect(200.0, 100.0, 100.0, 100.0), bounds(target))
    }

    // Spec §6: the projector shows the pattern, and the Targets screen goes on showing the targets
    @Test
    fun whileCalibratingTheScreenSaysSoAndStillShowsTheTargets() {
        square(200.0, 100.0)
        app.openArena()
        app.arena.value!!.cover(true)
        app.navigate(Destination.TARGETS)
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }

        compose.onNodeWithTag("calibrating-note").assertExists()
        val pixels = compose.onNodeWithTag("targets-arena").captureToImage().toPixelMap()
        var red = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) if (pixels[x, y] == Color.Red) red++
        assertFalse("the target isn't drawn", red == 0)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/shell/TestAppRail.kt`:

Replace:

```kotlin
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
```

with:

```kotlin
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
```

Replace:

```kotlin
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
```

with:

```kotlin
    val compose = createComposeRule()

    @Test
    fun sessionsIsShownButDisabled() {
        compose.setContent { RangeTheme(dark = true) { AppRail(Destination.RANGE, {}) } }

        compose.onNodeWithTag("rail-RANGE").assertIsEnabled().assertIsSelected()
        compose.onNodeWithTag("rail-DRILLS").assertIsEnabled()
        compose.onNodeWithTag("rail-SETTINGS").assertIsEnabled()
        compose.onNodeWithTag("rail-TARGETS").assertIsEnabled()
        compose.onNodeWithTag("rail-SESSIONS").assertIsNotEnabled()
        compose.onNodeWithTag("rail-hint").assertTextEquals("Sessions: in the JavaFX app for now")
    }

    @Test
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestTargetsScreen --tests com.shootoff.compose.shell.TestAppRail --tests com.shootoff.compose.app.TestAppState --console=plain`

Expected: compilation fails, `Unresolved reference 'targetsModel'`, `'ArenaLayoutView'`, `'EditingOverlay'`.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    /** The shooter's arena targets and background, which every arena window this session draws */
    val arenaLayout = ArenaLayout(clock)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
```

with:

```kotlin
    /** The shooter's arena targets and background, which every arena window this session draws */
    val arenaLayout = ArenaLayout(clock)

    /** The Targets screen's state */
    val targetsModel = TargetsModel(arenaLayout)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt`:

Replace:

```kotlin
                    Destination.RANGE -> RangeScreen(app)
                    Destination.SETUP -> SetupScreen(app)
                    Destination.DRILLS -> DrillsScreen(app)
                    Destination.SETTINGS -> SettingsScreen(app)
                    // Disabled on the rail
                    Destination.TARGETS, Destination.SESSIONS -> RangeScreen(app)
                }
            }
        }
```

with:

```kotlin
                    Destination.RANGE -> RangeScreen(app)
                    Destination.SETUP -> SetupScreen(app)
                    Destination.DRILLS -> DrillsScreen(app)
                    Destination.TARGETS -> TargetsScreen(app)
                    Destination.SETTINGS -> SettingsScreen(app)
                    // Disabled on the rail
                    Destination.SESSIONS -> RangeScreen(app)
                }
            }
        }
```

Create `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsModel.kt`:

```kotlin
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

package com.shootoff.compose.app

import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.targets.TargetEditor

/** What the Targets screen says while calibration's pattern covers the arena (spec §6) */
const val CALIBRATING_NOTE = "Calibrating — the projector shows the pattern; your edits appear when it's done."

/**
 * The Targets screen's state, apart from its composables: the editor of the arena's targets.
 *
 * @param layout the shooter's arena, open or not
 */
class TargetsModel(val layout: ArenaLayout) {
    val arenaEditor = TargetEditor(layout.targets) { layout.size.value }
}
```

Create `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsScreen.kt`:

```kotlin
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

package com.shootoff.compose.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.shootoff.compose.arena.ArenaLayoutView
import com.shootoff.compose.targets.EditingOverlay
import com.shootoff.compose.theme.Range

/**
 * The Targets screen (spec §4): the shooter's arena, drawn from the same targets the projector draws, with
 * every change showing on the projector as it is made.
 */
@Composable
fun TargetsScreen(app: AppState, modifier: Modifier = Modifier) {
    val model = app.targetsModel
    val arena by app.arena.collectAsState()
    val covered = arena?.covered?.collectAsState()?.value == true
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp).testTag("targets-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Targets", fontSize = 22.sp, color = colors.text)
        if (covered) Text(CALIBRATING_NOTE, color = colors.warning, modifier = Modifier.testTag("calibrating-note"))
        Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = Modifier.weight(1f).fillMaxWidth()) {
            ArenaLayoutView(model.layout, arena, Modifier.fillMaxSize().testTag("targets-arena")) { transform ->
                EditingOverlay(model.layout.targets, model.arenaEditor, transform)
            }
        }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt`:

Replace:

```kotlin
fun ArenaView(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    ArenaCanvas(arena, modifier.testTag("arena-view"), overlay)
}
```

with:

```kotlin
fun ArenaView(arena: ArenaModel, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    ArenaCanvas(arena, modifier.testTag("arena-view"), overlay)
}

/**
 * The Targets screen's view of the arena, open or not: the arena's area with the background the projector
 * shows once nothing covers it ([arena]'s exercise background if one is up, else the shooter's), every
 * target, then [overlay] in arena coordinates. Unlike [ArenaCanvas] it shows the targets while calibration's
 * pattern covers the arena, so the shooter can go on placing them.
 */
@Composable
fun ArenaLayoutView(layout: ArenaLayout, arena: ArenaModel?, modifier: Modifier = Modifier, overlay: @Composable (SurfaceTransform) -> Unit = {}) {
    val size by layout.size.collectAsState()
    val shooters by layout.background.collectAsState()
    val exercises = arena?.background?.collectAsState()?.value
    val covered = arena?.covered?.collectAsState()?.value == true

    BoxWithConstraints(modifier.background(Color.Black)) {
        val transform = SurfaceTransform.fit(size, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Canvas(Modifier.fillMaxSize().testTag("layout-canvas")) {
            val topLeft = transform.toView(0.0, 0.0)
            val areaSize = Size((size.width * transform.scale).toFloat(), (size.height * transform.scale).toFloat())
            drawRect(ARENA_GRAY, topLeft, areaSize)
            // Under the pattern, the background the arena goes back to
            val image = (exercises.takeUnless { covered } ?: shooters)?.image ?: return@Canvas
            drawImage(
                image,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(areaSize.width.roundToInt(), areaSize.height.roundToInt()),
            )
        }
        TargetLayer(layout.targets, transform)
        overlay(transform)
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt`:

Replace:

```kotlin
import com.shootoff.compose.theme.Range

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
}
```

with:

```kotlin
import com.shootoff.compose.theme.Range

/**
 * The places the rail leads to, in the rail's order (spec §8). Sessions is in the JavaFX app for now.
 */
enum class Destination(val label: String, val icon: ImageVector, val enabled: Boolean) {
    RANGE("Range", Icons.Filled.Home, true),
    SETUP("Setup", Icons.Filled.Build, true),
    DRILLS("Drills", Icons.Filled.PlayArrow, true),
    TARGETS("Targets", Icons.Filled.Place, true),
    SESSIONS("Sessions", Icons.AutoMirrored.Filled.List, false),
    SETTINGS("Settings", Icons.Filled.Settings, true),
}
```

Replace:

```kotlin
            )
        }
        Spacer(Modifier.height(16.dp))
        // Targets and Sessions are greyed out; say why
        Text(
            "Targets and Sessions: ${DISABLED_HINT.replaceFirstChar { it.lowercase() }}",
            color = colors.muted,
            fontSize = 10.sp,
            lineHeight = 12.sp,
```

with:

```kotlin
            )
        }
        Spacer(Modifier.height(16.dp))
        // Sessions is greyed out; say why
        Text(
            "Sessions: ${DISABLED_HINT.replaceFirstChar { it.lowercase() }}",
            color = colors.muted,
            fontSize = 10.sp,
            lineHeight = 12.sp,
```

Create `compose-app/src/main/kotlin/com/shootoff/compose/targets/EditingOverlay.kt`:

```kotlin
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

package com.shootoff.compose.targets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.theme.Range
import kotlin.math.roundToInt

/** A resize handle's size, in dp */
private const val HANDLE_DP = 12

/**
 * The Targets screen's editing layer over a surface's view: it outlines the selected target with a handle
 * at each corner, dims an exercise's targets and badges them, and turns the mouse and keys into
 * [TargetEditor] gestures. A click selects (or, on empty space, deselects); dragging a target moves it;
 * dragging a handle resizes it, keeping its shape with Ctrl held. With the layer focused and a target
 * selected, arrows move it by one unit, Shift+arrows resize it, Delete removes it and Esc deselects it.
 */
@Composable
fun EditingOverlay(targets: SurfaceTargets, editor: TargetEditor, transform: SurfaceTransform, modifier: Modifier = Modifier) {
    val drawn by targets.drawn.collectAsState()
    val selected by editor.selected.collectAsState()
    val colors = Range.colors
    val focus = remember { FocusRequester() }

    Box(
        modifier
            .fillMaxSize()
            .testTag("editing-surface")
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event -> event.type == KeyEventType.KeyDown && editKey(editor, event) }
            .pointerInput(transform) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    focus.requestFocus()
                    editor.click(transform.toSurface(down.position))
                    if (!editor.startDrag()) return@awaitEachGesture
                    down.consume()
                    followDrag(down.id, transform) { dx, dy -> editor.dragMove(dx, dy) }
                    editor.endDrag()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (target in drawn) {
                if (!target.placement.visible()) continue
                val bounds = target.bounds
                val topLeft = transform.toView(bounds.minX, bounds.minY)
                val size = Size((bounds.width * transform.scale).toFloat(), (bounds.height * transform.scale).toFloat())
                if (target.owner == TargetOwner.EXERCISE) drawRect(Color.Black.copy(alpha = 0.55f), topLeft, size)
                if (target.id == selected) drawRect(colors.accent, topLeft, size, style = Stroke(2.dp.toPx()))
            }
        }
        for (target in drawn) {
            if (!target.placement.visible()) continue
            val bounds = target.bounds
            if (target.owner == TargetOwner.EXERCISE) {
                val topLeft = transform.toView(bounds.minX, bounds.minY)
                Text(
                    "exercise",
                    color = colors.text,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                        .background(colors.card.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp)
                        .testTag("exercise-badge"),
                )
            }
            if (target.id == selected) {
                for (corner in Corner.entries) {
                    key(corner) {
                        val at = transform.toView(if (corner.left) bounds.minX else bounds.maxX, if (corner.top) bounds.minY else bounds.maxY)
                        Box(
                            Modifier
                                .offset { IntOffset(at.x.roundToInt() - (HANDLE_DP.dp.toPx() / 2).roundToInt(), at.y.roundToInt() - (HANDLE_DP.dp.toPx() / 2).roundToInt()) }
                                .size(HANDLE_DP.dp)
                                .background(colors.accent)
                                .testTag("handle-${corner.name}")
                                .pointerInput(transform, corner) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()
                                        focus.requestFocus()
                                        if (!editor.startDrag()) return@awaitEachGesture
                                        followDrag(down.id, transform) { dx, dy ->
                                            editor.dragResize(corner, dx, dy, keepAspect = currentEvent.keyboardModifiers.isCtrlPressed)
                                        }
                                        editor.endDrag()
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

// Follows a drag to its end, giving [moved] the pointer's whole movement since it went down, in surface units:
// a fast mouse sends several moves between two frames, and each is placed from where the drag began
private suspend fun AwaitPointerEventScope.followDrag(
    pointer: PointerId,
    transform: SurfaceTransform,
    moved: AwaitPointerEventScope.(dx: Double, dy: Double) -> Unit,
) {
    var total = Offset.Zero
    drag(pointer) { change ->
        total += change.positionChange()
        change.consume()
        moved((total.x / transform.scale).toDouble(), (total.y / transform.scale).toDouble())
    }
}

/**
 * A key on the editing layer: arrows move the selected target, Shift+arrows resize it, Delete (or
 * Backspace) removes it and Esc deselects it.
 *
 * @return false if the key isn't one of these, or nothing is selected
 */
fun editKey(editor: TargetEditor, event: KeyEvent): Boolean {
    if (editor.selected.value == null) return false
    val arrow = when (event.key) {
        Key.DirectionLeft -> Arrow.LEFT
        Key.DirectionRight -> Arrow.RIGHT
        Key.DirectionUp -> Arrow.UP
        Key.DirectionDown -> Arrow.DOWN
        else -> null
    }
    when {
        arrow != null -> editor.nudge(arrow, resize = event.isShiftPressed)
        event.key == Key.Delete || event.key == Key.Backspace -> editor.delete()
        event.key == Key.Escape -> editor.deselect()
        else -> return false
    }
    return true
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestTargetsScreen --tests com.shootoff.compose.shell.TestAppRail --tests com.shootoff.compose.app.TestAppState --console=plain`

Expected: PASS, 7 tests in `TestTargetsScreen`.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `861/861 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/ShootOffApp.kt compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsModel.kt compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/arena/ArenaCanvas.kt compose-app/src/main/kotlin/com/shootoff/compose/shell/Rail.kt compose-app/src/main/kotlin/com/shootoff/compose/targets/EditingOverlay.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestAppState.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/shell/TestAppRail.kt
git commit -m "Place and edit the arena's targets on the Targets screen"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 7: The Targets screen's actions

**Files:**
- Create: `compose-app/src/main/kotlin/com/shootoff/compose/app/ArenaFiles.kt`
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsModel.kt` (replaced whole)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`arenaFiles` and `imagePicker` parameters)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsModel.kt`

**Interfaces:**
- Consumes (Tasks 1–6): `TargetOwner`, `SurfaceTargets.targetsOf`, `TargetEditor.select`/`deselect`, `ArenaLayout`, `CourseLoader`, `LayoutMemory.FILE_NAME`; `feed.Banner`, `BannerKind`.
- Produces:
  - `ArenaFiles(home: File, targets: File, courses: File, layout: File?)` with `ArenaFiles.inHome(home: File, courses: File = File(home, "courses"))`, `ArenaFiles.scratch()`, `fun targetChoices(): List<TargetChoice>`, `fun courseChoices(): List<CourseChoice>`; `data class TargetChoice(val file: File, val name: String)`; `data class CourseChoice(val file: File, val name: String, val group: String)`; `data class BundledBackground(val name: String, val resource: String)`; `val BUNDLED_BACKGROUNDS: List<BundledBackground>`; `fun interface ImagePicker { fun pick(): File? }` with `ImagePicker.Awt`.
  - `enum class EditedSurface(val label: String) { ARENA, CAMERA }`; `class Cleared(val surface, val targets: List<Pair<TargetDefinition, Placement>>, val background: ArenaBackground?)`; `enum class SaveOutcome { SAVING, EXISTS, BAD_NAME }`.
  - `class TargetsModel(val layout: ArenaLayout, val feedTargets: SurfaceTargets, feedSize: Size, val files: ArenaFiles, picker: ImagePicker, scope: CoroutineScope, io: CoroutineDispatcher)` with `val loader: CourseLoader`, `arenaEditor`, `cameraEditor`, `surface: StateFlow<EditedSurface>`, `message: StateFlow<Banner?>`, `undo: StateFlow<Cleared?>`, and `show(surface)`, `editor(surface)`, `targets(surface)`, `dismissMessage()`, `addTarget(choice: TargetChoice)`, `useBackground(background: BundledBackground?)`, `pickBackground()`, `loadCourse(choice: CourseChoice)`, `saveCourse(name: String, replace: Boolean = false): SaveOutcome`, `clear()`, `undoClear()`, `dropUndo()`.
  - `AppState(…, arenaFiles: ArenaFiles = ArenaFiles.scratch(), imagePicker: ImagePicker = ImagePicker { null })`, its last two parameters.

**Why.** Spec §4's toolbar and §6's errors, as plain unit tests on a model that runs its file work on an injected dispatcher (the tests pass `Dispatchers.Unconfined`). Rulings 8–12 and 14.

- [ ] **Step 1: Write the failing tests**

Create `compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsModel.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

class TestTargetsModel {
    @TempDir
    lateinit var temp: Path

    private val home = File(System.getProperty("user.dir"))
    private val layout = ArenaLayout(ManualClock()).apply { setSize(Size(1280.0, 720.0)) }
    private val feed = SurfaceTargets(clock = ManualClock())
    private var picked: File? = null

    // Everything runs at once, on the calling thread
    private val model by lazy {
        TargetsModel(
            layout,
            feed,
            Size(640.0, 480.0),
            ArenaFiles(home, File(temp.toFile(), "targets").apply { mkdirs() }, File(temp.toFile(), "courses"), null),
            { picked },
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
        )
    }

    private fun targetFile(name: String, xml: String = BOX): TargetChoice {
        val file = File(temp.toFile(), "targets/$name.target")
        file.writeText(xml)
        return TargetChoice(file, name)
    }

    private fun shooters(targets: SurfaceTargets = layout.targets) = targets.targetsOf(TargetOwner.USER)

    private fun reset() = layout.targets.add(TargetDefinitions.load(File(home, "targets/Reset.target").toPath()), ResourceResolver.files(), Placement(5.0, 6.0, 1.0, 1.0, true))

    companion object {
        const val BOX = """<target><rectangle x="0" y="0" width="100" height="50" fill="red"/></target>"""
    }

    @Test
    fun anAddedTargetGoesInTheMiddleOfTheArenaAtItsOwnSizeSelected() {
        model.addTarget(targetFile("box"))

        val target = shooters().single()
        assertEquals(Rect(590.0, 335.0, 100.0, 50.0), target.bounds)
        assertEquals(target.id, model.arenaEditor.selected.value)
    }

    @Test
    fun onTheCameraATargetGoesInTheMiddleOfTheFeed() {
        model.show(EditedSurface.CAMERA)

        model.addTarget(targetFile("box"))

        assertTrue(layout.targets.set.targets.isEmpty())
        val target = shooters(feed).single()
        assertEquals(Rect(270.0, 215.0, 100.0, 50.0), target.bounds)
        assertEquals(target.id, model.cameraEditor.selected.value)
    }

    @Test
    fun aTargetThatAsksToFillTheCanvasFillsTheArena() {
        model.addTarget(targetFile("poi", """<target fillCanvas="true"><rectangle x="10" y="10" width="100" height="50" fill="red"/></target>"""))

        assertEquals(Rect(0.0, 0.0, 1280.0, 720.0), shooters().single().bounds)
    }

    @Test
    fun aTargetFileThatCantBeReadIsNamedAndNothingIsAdded() {
        model.addTarget(targetFile("broken", "<target><rectangle"))

        assertTrue(layout.targets.set.targets.isEmpty())
        assertEquals(BannerKind.ERROR, model.message.value!!.kind)
        assertTrue(model.message.value!!.text.startsWith("Couldn't load the target broken"), model.message.value!!.text)
    }

    @Test
    fun addTargetOffersTheTargetFilesInTheTargetsFolderByName() {
        val folder = ArenaFiles.inHome(home)

        val choices = folder.targetChoices()

        assertTrue(choices.any { it.name == "Steel Challenge Circle" && it.file == File(home, "targets/Steel_Challenge_Circle.target") })
        assertEquals(choices.map { it.name.lowercase() }.sorted(), choices.map { it.name.lowercase() })
        // Not the ones in its subfolders
        assertFalse(choices.any { it.file.parentFile != File(home, "targets") })
    }

    @Test
    fun loadCourseOffersTheCoursesInTheCoursesFolderAndItsSubfolders() {
        val courses = File(temp.toFile(), "courses/steel_challenge").apply { mkdirs() }
        File(courses, "five_to_go.course").writeText("")
        File(courses.parentFile, "mine.course").writeText("")
        File(courses.parentFile, ".arena-layout.course").writeText("")

        val choices = model.files.courseChoices()

        assertEquals(listOf("" to "mine", "steel challenge" to "five to go"), choices.map { it.group to it.name })
    }

    @Test
    fun aBundledBackgroundGoesUpAndNoneTakesItDown() {
        model.useBackground(BUNDLED_BACKGROUNDS.first { it.name == "Indoor Range" })
        assertEquals(CourseBackground("/arena/backgrounds/indoor_range.gif", true), layout.background.value!!.source)

        model.useBackground(null)
        assertNull(layout.background.value)
    }

    @Test
    fun everyBundledBackgroundCanBeRead() {
        for (background in BUNDLED_BACKGROUNDS) {
            assertTrue(model.loader.readBackground(CourseBackground(background.resource, true)) != null, background.name)
        }
    }

    @Test
    fun anImageFileTheShooterPicksGoesUpAndCancellingChangesNothing() {
        val file = temp.resolve("range.png").toFile()
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", file)
        picked = file

        model.pickBackground()
        assertEquals(CourseBackground(file.toURI().toString(), false), layout.background.value!!.source)

        picked = null
        model.pickBackground()
        assertEquals(CourseBackground(file.toURI().toString(), false), layout.background.value!!.source)
    }

    @Test
    fun aPickedFileThatIsntAnImageIsNamed() {
        picked = temp.resolve("notes.png").toFile().apply { writeText("not an image") }

        model.pickBackground()

        assertNull(layout.background.value)
        assertEquals("Couldn't read the background notes.png.", model.message.value!!.text)
    }

    @Test
    fun aCourseLoadsAndItsMissingTargetFilesAreNamed() {
        val file = File(temp.toFile(), "courses/mine.course").apply { parentFile.mkdirs() }
        file.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <course>
            	<target file="targets/no_such_target.target" x="0" y="0" width="10" height="10" />
            	<target file="targets/Reset.target" x="10" y="20" width="10" height="1" />
            	<resolution width="1280" height="720" />
            </course>
            """.trimIndent(),
        )

        model.loadCourse(CourseChoice(file, "mine", ""))

        assertEquals(1, shooters().size)
        assertEquals(BannerKind.WARNING, model.message.value!!.kind)
        assertEquals("Loaded mine, but these target files are missing, so they were left out: targets/no_such_target.target.", model.message.value!!.text)
    }

    @Test
    fun aCourseThatCantBeReadLeavesTheArenaAsItWas() {
        val kept = reset()
        val file = File(temp.toFile(), "courses/broken.course").apply { parentFile.mkdirs() }
        file.writeText("<course><target")

        model.loadCourse(CourseChoice(file, "broken", ""))

        assertEquals(listOf(kept.id), layout.targets.set.targets.map { it.id })
        assertEquals("Couldn't read the course broken; the arena is as it was.", model.message.value!!.text)
    }

    @Test
    fun aCourseIsSavedUnderItsNameAndAnExistingOneOnlyOnceTheShooterSaysSo() {
        reset()
        val file = File(temp.toFile(), "courses/Mine.course")

        assertEquals(SaveOutcome.BAD_NAME, model.saveCourse("  "))
        assertEquals(SaveOutcome.BAD_NAME, model.saveCourse("a/b"))
        assertEquals(SaveOutcome.SAVING, model.saveCourse(" Mine "))
        assertEquals("Saved the course Mine.", model.message.value!!.text)
        assertEquals(1, CourseIO.loadCourse(file).get().targets.size)

        reset()
        assertEquals(SaveOutcome.EXISTS, model.saveCourse("Mine"))
        assertEquals(1, CourseIO.loadCourse(file).get().targets.size)
        assertEquals(SaveOutcome.SAVING, model.saveCourse("Mine", replace = true))
        assertEquals(2, CourseIO.loadCourse(file).get().targets.size)
    }

    @Test
    fun clearTakesTheShootersTargetsAndBackgroundOffAndUndoPutsThemBack() {
        val shooters = reset()
        layout.targets.set.resize(shooters.id, 40.0, 4.0)
        val placement = layout.targets.set.get(shooters.id).get().placement
        val exercises = layout.targets.add(TargetDefinitions.load(File(home, "targets/Reset.target").toPath()), ResourceResolver.files(), owner = TargetOwner.EXERCISE)
        model.useBackground(BUNDLED_BACKGROUNDS.first())
        val background = layout.background.value

        model.clear()

        assertEquals(listOf(exercises.id), layout.targets.set.targets.map { it.id })
        assertNull(layout.background.value)
        assertTrue(model.undo.value != null)

        model.undoClear()

        assertEquals(listOf(placement), shooters().map { it.placement })
        assertEquals(background, layout.background.value)
        assertNull(model.undo.value)
    }

    @Test
    fun theUndoOfferEndsWithTheNextChange() {
        reset()
        model.clear()

        model.addTarget(targetFile("box"))

        assertNull(model.undo.value)
    }

    @Test
    fun clearOnTheCameraLeavesTheArenaAlone() {
        reset()
        model.show(EditedSurface.CAMERA)
        model.addTarget(targetFile("box"))

        model.clear()

        assertTrue(shooters(feed).isEmpty())
        assertEquals(1, shooters().size)
        model.undoClear()
        assertEquals(1, shooters(feed).size)
    }

    @Test
    fun clearingNothingOffersNoUndo() {
        model.clear()

        assertNull(model.undo.value)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestTargetsModel --tests com.shootoff.compose.app.TestTargetsScreen --console=plain`

Expected: compilation fails, `Unresolved reference 'ArenaFiles'` (and `'EditedSurface'`, `'BUNDLED_BACKGROUNDS'`, …).

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check shows
 *   the pattern, or an automatic calibration starts
 * @param calibrationTimers runs calibration's and the check's timers (auto-calibration's timeout among them)
 */
class AppState(
    val settings: Settings,
```

with:

```kotlin
 * @param patternSettleMillis how long the arena must have filled the projector's screen before a check shows
 *   the pattern, or an automatic calibration starts
 * @param calibrationTimers runs calibration's and the check's timers (auto-calibration's timeout among them)
 * @param arenaFiles where the Targets screen finds targets and courses, and the arena's layout is remembered
 * @param imagePicker asks the shooter for a background image file
 */
class AppState(
    val settings: Settings,
```

Replace:

```kotlin
    private val reconnectRetryMillis: Long = RECONNECT_RETRY_MILLIS,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
    private val calibrationTimers: CalibrationFlow.Scheduler = TIMER_POOL,
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check, or calibration starts */
```

with:

```kotlin
    private val reconnectRetryMillis: Long = RECONNECT_RETRY_MILLIS,
    private val patternSettleMillis: Long = PATTERN_SETTLE_MILLIS,
    private val calibrationTimers: CalibrationFlow.Scheduler = TIMER_POOL,
    arenaFiles: ArenaFiles = ArenaFiles.scratch(),
    imagePicker: ImagePicker = ImagePicker { null },
) : CalibrationViews {
    companion object {
        /** How long the arena settles on the projector before a pattern shows for a check, or calibration starts */
```

Replace:

```kotlin
    val arenaLayout = ArenaLayout(clock)

    /** The Targets screen's state */
    val targetsModel = TargetsModel(arenaLayout)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
```

with:

```kotlin
    val arenaLayout = ArenaLayout(clock)

    /** The Targets screen's state */
    val targetsModel = TargetsModel(arenaLayout, feedTargets, displaySize, arenaFiles, imagePicker, scope, io)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
```

Create `compose-app/src/main/kotlin/com/shootoff/compose/app/ArenaFiles.kt`:

```kotlin
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

package com.shootoff.compose.app

import com.shootoff.compose.courses.LayoutMemory
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

/** A target file Add target offers, by its name */
data class TargetChoice(val file: File, val name: String)

/**
 * A course file Load course offers.
 *
 * @param group the folder it is in under the courses folder ("steel challenge"), or "" at its top
 */
data class CourseChoice(val file: File, val name: String, val group: String)

/** One of ShootOFF's own arena backgrounds */
data class BundledBackground(val name: String, val resource: String)

/** The backgrounds ShootOFF comes with, by name, as the JavaFX app offered them */
val BUNDLED_BACKGROUNDS = listOf(
    BundledBackground("Hickok45 Autumn", "/arena/backgrounds/hickok45_autumn.gif"),
    BundledBackground("Hickok45 Summer", "/arena/backgrounds/hickok45_summer.gif"),
    BundledBackground("Indoor Range", "/arena/backgrounds/indoor_range.gif"),
    BundledBackground("Kiang West Savanna", "/arena/backgrounds/kiang_west_savanna.gif"),
    BundledBackground("Oradour-sur-Glane", "/arena/backgrounds/oradour-sur-glane.gif"),
    BundledBackground("Outdoor Range", "/arena/backgrounds/outdoor_range.gif"),
    BundledBackground("Steel Range Bay", "/arena/backgrounds/steel_range_bay.gif"),
    BundledBackground("Subterranean Parking Lot", "/arena/backgrounds/subterranean_parking_lot.gif"),
)

/** Asks the shooter for an image file for the arena's background */
fun interface ImagePicker {
    /** @return the file, or null if they cancelled */
    fun pick(): File?

    companion object {
        /** AWT's file dialog, on the UI thread */
        val Awt = ImagePicker {
            val dialog = FileDialog(null as Frame?, "Arena background", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".png") || it.endsWith(".gif") || it.endsWith(".jpg") || it.endsWith(".jpeg") } }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        }
    }
}

/**
 * Where the Targets screen finds and keeps its files.
 *
 * @param home ShootOFF's folder: a course names its target files relative to it
 * @param targets the folder whose target files Add target offers
 * @param courses the folder (with its subfolders) Load course offers and Save course writes to
 * @param layout the file the arena's layout is remembered in; null remembers nothing
 */
class ArenaFiles(val home: File, val targets: File, val courses: File, val layout: File?) {
    companion object {
        /** ShootOFF's own folders, as the app uses them */
        fun inHome(home: File, courses: File = File(home, "courses")) =
            ArenaFiles(home, File(home, "targets"), courses, File(home, LayoutMemory.FILE_NAME))

        /**
         * The default, which touches none of the shooter's files: ShootOFF's own targets (read only), an empty
         * courses folder of its own, and no remembered layout. The app passes [inHome].
         */
        fun scratch(): ArenaFiles {
            val home = File(System.getProperty("user.dir"))
            val courses = Files.createTempDirectory("shootoff-courses").toFile().apply { deleteOnExit() }
            return ArenaFiles(home, File(home, "targets"), courses, null)
        }
    }

    /** The target files in [targets] (not its subfolders, as in the JavaFX app), by name */
    fun targetChoices(): List<TargetChoice> =
        (targets.listFiles { file -> file.isFile && file.name.endsWith(".target") } ?: emptyArray())
            .map { TargetChoice(it, displayName(it)) }
            .sortedBy { it.name.lowercase() }

    /** The course files in [courses] and its subfolders, by group then name; hidden files are left out */
    fun courseChoices(): List<CourseChoice> {
        if (!courses.isDirectory) return emptyList()
        return courses.walkTopDown()
            .onEnter { it == courses || !it.isHidden }
            .filter { it.isFile && !it.isHidden && it.name.endsWith(".course") }
            .map { file ->
                val folder = file.parentFile.relativeTo(courses).path
                CourseChoice(file, displayName(file), folder.replace('_', ' ').replace(File.separatorChar, '/'))
            }
            .sortedWith(compareBy({ it.group.lowercase() }, { it.name.lowercase() }))
            .toList()
    }

    // "Steel_Challenge_Circle.target" is "Steel Challenge Circle"
    private fun displayName(file: File) = file.nameWithoutExtension.replace('_', ' ')
}
```

Replace the whole of `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsModel.kt` with:

```kotlin
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

package com.shootoff.compose.app

import com.shootoff.compose.arena.ArenaBackground
import com.shootoff.compose.arena.ArenaLayout
import com.shootoff.compose.courses.CourseLoader
import com.shootoff.compose.feed.Banner
import com.shootoff.compose.feed.BannerKind
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetEditor
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.courses.CourseBackground
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinition
import com.shootoff.targets.model.TargetDefinitions
import com.shootoff.targets.model.TargetFormatException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** What the Targets screen says while calibration's pattern covers the arena (spec §6) */
const val CALIBRATING_NOTE = "Calibrating — the projector shows the pattern; your edits appear when it's done."

/** The surface the Targets screen edits */
enum class EditedSurface(val label: String) {
    ARENA("Arena"),
    CAMERA("Camera"),
}

/** What Clear took off a surface, for its Undo */
class Cleared(val surface: EditedSurface, val targets: List<Pair<TargetDefinition, Placement>>, val background: ArenaBackground?)

/** How a Save course went, as far as the screen needs to know at once (the file is written in the background) */
enum class SaveOutcome {
    /** Being written; the screen hears how it went in [TargetsModel.message] */
    SAVING,

    /** A course of that name exists: the screen asks before replacing it */
    EXISTS,

    /** The name is empty, or has a folder separator in it */
    BAD_NAME,
}

/**
 * The Targets screen's state and actions, apart from its composables (spec §4 and §5): which surface it
 * edits, each surface's [TargetEditor], and the toolbar's actions. Only the shooter's targets
 * ([TargetOwner.USER]) are added, cleared, loaded or saved; a running exercise's are left alone.
 *
 * The actions are called on the UI thread; reading files and images happens on [io].
 *
 * @param layout the shooter's arena, open or not
 * @param feedTargets the camera feed's targets, kept for the session only
 * @param feedSize the camera feed's canvas size
 * @param picker asks for an image file for the background
 */
class TargetsModel(
    val layout: ArenaLayout,
    val feedTargets: SurfaceTargets,
    private val feedSize: Size,
    val files: ArenaFiles,
    private val picker: ImagePicker,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    val loader = CourseLoader(files.home)
    val arenaEditor = TargetEditor(layout.targets) { layout.size.value }
    val cameraEditor = TargetEditor(feedTargets) { feedSize }

    private val surfaceState = MutableStateFlow(EditedSurface.ARENA)
    private val messageState = MutableStateFlow<Banner?>(null)
    private val undoState = MutableStateFlow<Cleared?>(null)
    private val nextMessage = AtomicLong()

    /** The surface being edited */
    val surface: StateFlow<EditedSurface> = surfaceState.asStateFlow()

    /** What the screen's banner says: how the last action that read or wrote a file went; null for nothing */
    val message: StateFlow<Banner?> = messageState.asStateFlow()

    /** What the last Clear took off, while its Undo is offered */
    val undo: StateFlow<Cleared?> = undoState.asStateFlow()

    fun show(surface: EditedSurface) {
        surfaceState.value = surface
    }

    fun editor(surface: EditedSurface): TargetEditor = if (surface == EditedSurface.ARENA) arenaEditor else cameraEditor

    fun targets(surface: EditedSurface): SurfaceTargets = if (surface == EditedSurface.ARENA) layout.targets else feedTargets

    fun dismissMessage() {
        messageState.value = null
    }

    /**
     * Adds [choice] to the surface being edited, at its own size in the surface's middle (a target that asks
     * to fill the canvas fills it), and selects it.
     */
    fun addTarget(choice: TargetChoice) {
        val surface = surfaceState.value
        dropUndo()
        scope.launch(io) {
            val definition = try {
                TargetDefinitions.load(choice.file.toPath())
            } catch (e: TargetFormatException) {
                say("Couldn't load the target ${choice.name}: ${e.message}", BannerKind.ERROR)
                return@launch
            }
            val targets = targets(surface)
            val size = if (surface == EditedSurface.ARENA) layout.size.value else feedSize
            val target = targets.add(definition, ResourceResolver.files(), Placement.ORIGIN, TargetOwner.USER)
            if (definition.fillsCanvas()) {
                targets.set.resize(target.id, size.width, size.height)
            }
            val bounds = targets.set.get(target.id).get().bounds
            val (x, y) = if (definition.fillsCanvas()) {
                0.0 to 0.0
            } else {
                (size.width - bounds.width) / 2 to (size.height - bounds.height) / 2
            }
            val placement = targets.set.get(target.id).get().placement
            targets.set.move(target.id, placement.x() + x - bounds.minX, placement.y() + y - bounds.minY)
            editor(surface).select(target.id)
        }
    }

    /** Puts up one of ShootOFF's backgrounds, or none for null */
    fun useBackground(background: BundledBackground?) {
        dropUndo()
        if (background == null) {
            layout.setBackground(null)
            return
        }
        readBackground(CourseBackground(background.resource, true), background.name)
    }

    /** Asks for an image file and puts it up as the background; cancelling leaves the background as it was */
    fun pickBackground() {
        val file = picker.pick() ?: return
        dropUndo()
        readBackground(CourseBackground(file.toURI().toString(), false), file.name)
    }

    /**
     * Replaces the shooter's arena targets and background with [choice]'s. A course that can't be read leaves
     * the arena as it was; one with missing target files loads the rest and names them.
     */
    fun loadCourse(choice: CourseChoice) {
        dropUndo()
        arenaEditor.deselect()
        scope.launch(io) {
            val course = CourseIO.loadCourse(choice.file).orElse(null)
            if (course == null) {
                say("Couldn't read the course ${choice.name}; the arena is as it was.", BannerKind.ERROR)
                return@launch
            }
            val applied = loader.apply(course, layout)
            val problems = buildList {
                if (applied.missing.isNotEmpty()) add("these target files are missing, so they were left out: ${applied.missing.joinToString(", ")}")
                applied.backgroundMissing?.let { add("its background $it couldn't be read") }
            }
            if (problems.isNotEmpty()) say("Loaded ${choice.name}, but ${problems.joinToString("; ")}.", BannerKind.WARNING)
        }
    }

    /**
     * Saves the shooter's arena targets and background as the course [name] in the courses folder.
     *
     * @param replace whether a course of that name may be replaced (the shooter said yes)
     */
    fun saveCourse(name: String, replace: Boolean = false): SaveOutcome {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\')) return SaveOutcome.BAD_NAME
        val file = File(files.courses, "$trimmed.course")
        if (file.exists() && !replace) return SaveOutcome.EXISTS
        val course = loader.course(layout)
        scope.launch(io) {
            files.courses.mkdirs()
            if (loader.save(course, file)) {
                say("Saved the course $trimmed.", BannerKind.INFO)
            } else {
                say("Couldn't save the course $trimmed.", BannerKind.ERROR)
            }
        }
        return SaveOutcome.SAVING
    }

    /** Takes the shooter's targets (and, on the arena, the background) off the surface being edited, offering Undo */
    fun clear() {
        val surface = surfaceState.value
        val targets = targets(surface)
        val shooters = targets.targetsOf(TargetOwner.USER)
        val background = if (surface == EditedSurface.ARENA) layout.background.value else null
        if (shooters.isEmpty() && background == null) return
        undoState.value = Cleared(surface, shooters.map { it.definition to it.placement }, background)
        for (target in shooters) targets.remove(target.id)
        if (surface == EditedSurface.ARENA) layout.setBackground(null)
    }

    /** Puts back what the last Clear took off, while its Undo is offered */
    fun undoClear() {
        val cleared = undoState.value ?: return
        undoState.value = null
        if (cleared.surface == EditedSurface.ARENA) layout.setBackground(cleared.background)
        scope.launch(io) {
            val targets = targets(cleared.surface)
            for ((definition, placement) in cleared.targets) targets.add(definition, ResourceResolver.files(), placement, TargetOwner.USER)
        }
    }

    /** The Undo offer ran out */
    fun dropUndo() {
        undoState.value = null
    }

    private fun readBackground(source: CourseBackground, name: String) {
        scope.launch(io) {
            val background = loader.readBackground(source)
            if (background == null) {
                say("Couldn't read the background $name.", BannerKind.ERROR)
            } else {
                layout.setBackground(background)
            }
        }
    }

    private fun say(text: String, kind: BannerKind) {
        messageState.value = Banner(nextMessage.incrementAndGet(), text, kind)
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestTargetsModel --tests com.shootoff.compose.app.TestTargetsScreen --console=plain`

Expected: PASS, 17 tests in `TestTargetsModel`; `TestTargetsScreen` still passes.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `878/878 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/app/ArenaFiles.kt compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsModel.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsModel.kt
git commit -m "Add the Targets screen's actions: add a target, backgrounds, courses, and Clear with Undo"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 8: The toolbar, its panels, and the camera feed

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsScreen.kt` (replaced whole)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsToolbar.kt`

**Interfaces:**
- Consumes (Tasks 6, 7): everything `TargetsModel` produces; `EditingOverlay`, `ArenaLayoutView`, `feed.CameraFeedView`, `feed.BannerView`, `TargetLayer`.
- Produces: `const val UNDO_MILLIS = 8000L`; test tags `surface-ARENA`, `surface-CAMERA`, `add-target`, `background`, `load-course`, `save-course`, `clear`, `panel`, `target-list`, `target-<name>`, `background-none`, `background-file`, `background-<name>`, `course-list`, `course-<name>`, `course-name`, `save`, `name-hint`, `replace-question`, `replace`, `undo`, `cleared`.

**Why.** Spec §4's toolbar (Arena: Add target, Background…, Load course…, Save course…, Clear; Camera: Add target and Clear) and the Arena / Camera switch; spec §3's "shots on a feed target register". Ruling 9 says why the dialogs are panels.

- [ ] **Step 1: Write the failing tests**

Create `compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsToolbar.kt`:

```kotlin
package com.shootoff.compose.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.shootoff.compose.shell.Destination
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.compose.theme.RangeTheme
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TestTargetsToolbar {
    @get:Rule
    val compose = createComposeRule()

    private val app = AppFixture.app()
    private val model = app.targetsModel
    private val layout = app.arenaLayout

    @Before
    fun show() {
        layout.setSize(Size(1280.0, 720.0))
        app.navigate(Destination.TARGETS)
        compose.setContent { RangeTheme(dark = true) { ShootOffApp(app) } }
    }

    @After
    fun close() = app.close()

    private fun shooters() = layout.targets.targetsOf(TargetOwner.USER)

    private fun reset() = layout.targets.add(
        TargetDefinitions.load(File("targets/Reset.target").toPath()),
        ResourceResolver.files(),
        Placement(5.0, 6.0, 1.0, 1.0, true),
    )

    private fun waitUntil(what: String, condition: () -> Boolean) = compose.waitUntil(what, 5000, condition)

    @Test
    fun addTargetListsTheTargetsAndAddsTheChosenOneSelected() {
        compose.onNodeWithTag("add-target").performClick()
        compose.onNodeWithTag("panel").assertExists()

        compose.onNodeWithTag("target-list").performScrollToNode(hasTestTag("target-Reset"))
        compose.onNodeWithTag("target-Reset").performClick()

        waitUntil("the target is added") { shooters().size == 1 }
        compose.onAllNodesWithTag("panel").assertCountEquals(0)
        waitUntil("the target is selected") { model.arenaEditor.selected.value == shooters().single().id }
    }

    @Test
    fun theBackgroundPanelPutsUpABundledBackgroundOrNone() {
        compose.onNodeWithTag("background").performClick()
        compose.onNodeWithTag("background-Indoor Range").performClick()
        waitUntil("the background is up") { layout.background.value?.name == "/arena/backgrounds/indoor_range.gif" }

        compose.onNodeWithTag("background").performClick()
        compose.onNodeWithTag("background-none").performClick()
        waitUntil("the background is down") { layout.background.value == null }
    }

    @Test
    fun aCourseIsSavedByNameAndReplacingOneAsksFirst() {
        reset()
        compose.onNodeWithTag("save-course").performClick()
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithTag("name-hint").assertExists()

        compose.onNodeWithTag("course-name").performTextInput("Mine")
        compose.onNodeWithTag("save").performClick()
        val file = File(model.files.courses, "Mine.course")
        waitUntil("the course is saved") { file.isFile }
        waitUntil("the save is said") { model.message.value?.text == "Saved the course Mine." }

        reset()
        compose.onNodeWithTag("save-course").performClick()
        compose.onNodeWithTag("course-name").performTextInput("Mine")
        compose.onNodeWithTag("save").performClick()
        compose.onNodeWithTag("replace-question").assertExists()
        compose.onNodeWithTag("replace").performClick()
        waitUntil("the course is replaced") { CourseIO.loadCourse(file).map { it.targets.size }.orElse(0) == 2 }
    }

    @Test
    fun aSavedCourseIsOfferedAndLoads() {
        reset()
        model.saveCourse("Mine")
        val file = File(model.files.courses, "Mine.course")
        waitUntil("the course is saved") { file.isFile }
        model.clear()

        compose.onNodeWithTag("load-course").performClick()
        compose.onNodeWithTag("course-Mine").performClick()

        waitUntil("the course is loaded") { shooters().size == 1 }
    }

    @Test
    fun clearOffersUndoForAWhile() {
        reset()
        compose.onNodeWithTag("clear").performClick()
        assertTrue(shooters().isEmpty())

        compose.onNodeWithTag("undo").performClick()
        waitUntil("the target is back") { shooters().size == 1 }
        compose.onAllNodesWithTag("cleared").assertCountEquals(0)

        compose.onNodeWithTag("clear").performClick()
        compose.onNodeWithTag("cleared").assertExists()
        compose.mainClock.advanceTimeBy(UNDO_MILLIS + 100)
        compose.waitForIdle()
        assertNull(model.undo.value)
        compose.onAllNodesWithTag("cleared").assertCountEquals(0)
    }

    @Test
    fun aProblemShowsInTheBannerUntilDismissed() {
        val file = File(model.files.courses, "broken.course")
        file.writeText("<course><target")
        compose.onNodeWithTag("load-course").performClick()
        compose.onNodeWithTag("course-broken").performClick()

        waitUntil("the problem is said") { model.message.value != null }
        val banner = model.message.value!!
        compose.onNodeWithTag("banner-${banner.id}").assertExists()
        compose.onNodeWithTag("dismiss-${banner.id}").performClick()
        assertNull(model.message.value)
    }

    @Test
    fun theCameraHasOnlyAddTargetAndClearAndItsTargetsAreHit() {
        compose.onNodeWithTag("surface-CAMERA").performClick()

        compose.onNodeWithTag("camera-feed").assertExists()
        compose.onAllNodesWithTag("background").assertCountEquals(0)
        compose.onAllNodesWithTag("load-course").assertCountEquals(0)
        compose.onAllNodesWithTag("save-course").assertCountEquals(0)

        compose.onNodeWithTag("add-target").performClick()
        compose.onNodeWithTag("target-list").performScrollToNode(hasTestTag("target-Reset"))
        compose.onNodeWithTag("target-Reset").performClick()
        waitUntil("the target is on the feed") { model.feedTargets.targetsOf(TargetOwner.USER).size == 1 }
        assertTrue(layout.targets.set.targets.isEmpty())

        // A shot on it hits it, as the feed's shot pipeline tests it
        val bounds = model.feedTargets.targetsOf(TargetOwner.USER).single().bounds
        val hit = app.feedSurface.hitTest(bounds.minX + bounds.width / 2, bounds.minY + bounds.height / 2)
        assertEquals(model.feedTargets.targetsOf(TargetOwner.USER).single().id, hit.get().targetId())
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestTargetsToolbar --tests com.shootoff.compose.app.TestTargetsScreen --console=plain`

Expected: compilation fails, `Unresolved reference 'UNDO_MILLIS'`.

- [ ] **Step 3: Implement**

Replace the whole of `compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsScreen.kt` with:

```kotlin
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

package com.shootoff.compose.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shootoff.compose.arena.ArenaLayoutView
import com.shootoff.compose.feed.BannerView
import com.shootoff.compose.feed.CameraFeedView
import com.shootoff.compose.surface.SurfaceTransform
import com.shootoff.compose.targets.AnimationClock
import com.shootoff.compose.targets.EditingOverlay
import com.shootoff.compose.targets.SurfaceTargets
import com.shootoff.compose.targets.TargetLayer
import com.shootoff.compose.theme.Range
import com.shootoff.geom.Size
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

/** How long Clear's Undo is offered */
const val UNDO_MILLIS = 8000L

/** The toolbar's panels, beside the surface */
private enum class Panel { ADD_TARGET, BACKGROUND, LOAD_COURSE, SAVE_COURSE }

/**
 * The Targets screen (spec §4): the arena, or the camera feed, with the shooter's targets to add, move,
 * resize and remove, and on the arena its background and courses. Every change shows on the projector (or
 * the feed) as it is made.
 */
@Composable
fun TargetsScreen(app: AppState, modifier: Modifier = Modifier) {
    val model = app.targetsModel
    val surface by model.surface.collectAsState()
    val arena by app.arena.collectAsState()
    val covered = arena?.covered?.collectAsState()?.value == true
    val message by model.message.collectAsState()
    val undo by model.undo.collectAsState()
    var panel by remember { mutableStateOf<Panel?>(null) }
    val colors = Range.colors

    Column(modifier.fillMaxSize().padding(16.dp).testTag("targets-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Targets", fontSize = 22.sp, color = colors.text)
            Spacer(Modifier.width(16.dp))
            for (choice in EditedSurface.entries) {
                FilterChip(
                    selected = surface == choice,
                    onClick = {
                        model.show(choice)
                        panel = null
                    },
                    label = { Text(choice.label) },
                    modifier = Modifier.testTag("surface-${choice.name}"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { panel = Panel.ADD_TARGET }, modifier = Modifier.testTag("add-target")) { Text("Add target") }
            // Backgrounds and courses are the arena's (spec §4)
            if (surface == EditedSurface.ARENA) {
                FilledTonalButton(onClick = { panel = Panel.BACKGROUND }, modifier = Modifier.testTag("background")) { Text("Background…") }
                FilledTonalButton(onClick = { panel = Panel.LOAD_COURSE }, modifier = Modifier.testTag("load-course")) { Text("Load course…") }
                FilledTonalButton(onClick = { panel = Panel.SAVE_COURSE }, modifier = Modifier.testTag("save-course")) { Text("Save course…") }
            }
            OutlinedButton(onClick = model::clear, modifier = Modifier.testTag("clear")) { Text("Clear") }
        }
        if (covered && surface == EditedSurface.ARENA) {
            Text(CALIBRATING_NOTE, color = colors.warning, modifier = Modifier.testTag("calibrating-note"))
        }
        message?.let { BannerView(it, onDismiss = model::dismissMessage) }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.feedEdge, modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (surface) {
                    EditedSurface.ARENA -> ArenaLayoutView(model.layout, arena, Modifier.fillMaxSize().testTag("targets-arena")) { transform ->
                        EditingOverlay(model.layout.targets, model.arenaEditor, transform)
                    }
                    EditedSurface.CAMERA -> CameraFeedView(app.feed, Modifier.fillMaxSize()) { transform ->
                        TargetLayer(model.feedTargets, transform)
                        EditingOverlay(model.feedTargets, model.cameraEditor, transform)
                    }
                }
            }
            panel?.let { shown ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.card,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.width(300.dp).fillMaxHeight().testTag("panel"),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(panelTitle(shown), color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { panel = null }, modifier = Modifier.testTag("close-panel")) { Text("Close") }
                        }
                        val close = { panel = null }
                        when (shown) {
                            Panel.ADD_TARGET -> AddTargetPanel(model, close)
                            Panel.BACKGROUND -> BackgroundPanel(model, close)
                            Panel.LOAD_COURSE -> LoadCoursePanel(model, close)
                            Panel.SAVE_COURSE -> SaveCoursePanel(model, close)
                        }
                    }
                }
            }
        }
        undo?.let { cleared ->
            // Offered for a while, then gone
            LaunchedEffect(cleared) {
                delay(UNDO_MILLIS)
                model.dropUndo()
            }
            Snackbar(
                action = { TextButton(onClick = model::undoClear, modifier = Modifier.testTag("undo")) { Text("Undo") } },
                modifier = Modifier.testTag("cleared"),
            ) { Text("Cleared the ${cleared.surface.label.lowercase()}") }
        }
    }
}

private fun panelTitle(panel: Panel) = when (panel) {
    Panel.ADD_TARGET -> "Add target"
    Panel.BACKGROUND -> "Background"
    Panel.LOAD_COURSE -> "Load course"
    Panel.SAVE_COURSE -> "Save course"
}

@Composable
private fun AddTargetPanel(model: TargetsModel, close: () -> Unit) {
    val choices = remember { model.files.targetChoices() }
    val colors = Range.colors
    if (choices.isEmpty()) Text("No targets in ${model.files.targets.path}", color = colors.muted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("target-list")) {
        items(choices, key = { it.file.path }) { choice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    model.addTarget(choice)
                    close()
                }.padding(4.dp).testTag("target-${choice.name}"),
            ) {
                TargetThumbnail(choice.file, Modifier.size(48.dp))
                Text(choice.name, color = colors.text)
            }
        }
    }
}

/** A target drawn small, read off the UI thread; still, and blank if it can't be read */
@Composable
private fun TargetThumbnail(file: File, modifier: Modifier = Modifier) {
    val thumbnail by produceState<SurfaceTargets?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // A clock that never ticks: thumbnails don't animate
                SurfaceTargets(clock = AnimationClock { _, _ -> }).also { targets ->
                    val target = targets.add(TargetDefinitions.load(file.toPath()), ResourceResolver.files())
                    val bounds = target.bounds
                    targets.set.move(target.id, -bounds.minX, -bounds.minY)
                }
            }.getOrNull()
        }
    }
    BoxWithConstraints(modifier) {
        val targets = thumbnail ?: return@BoxWithConstraints
        val bounds = targets.set.targets.firstOrNull()?.bounds ?: return@BoxWithConstraints
        TargetLayer(targets, SurfaceTransform.fit(Size(bounds.width, bounds.height), constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
    }
}

@Composable
private fun BackgroundPanel(model: TargetsModel, close: () -> Unit) {
    val colors = Range.colors
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            PanelRow("None", "background-none") {
                model.useBackground(null)
                close()
            }
        }
        item {
            PanelRow("An image file…", "background-file") {
                model.pickBackground()
                close()
            }
        }
        items(BUNDLED_BACKGROUNDS, key = { it.resource }) { background ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    model.useBackground(background)
                    close()
                }.padding(4.dp).testTag("background-${background.name}"),
            ) {
                val image by produceState<ImageBitmap?>(null, background) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { BackgroundThumbnails::class.java.getResourceAsStream(background.resource)?.use { ImageIO.read(it) }?.toComposeImageBitmap() }.getOrNull()
                    }
                }
                Box(Modifier.size(64.dp, 36.dp)) {
                    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Text(background.name, color = colors.text)
            }
        }
    }
}

// Where the bundled backgrounds' thumbnails are read from
private object BackgroundThumbnails

@Composable
private fun LoadCoursePanel(model: TargetsModel, close: () -> Unit) {
    val choices = remember { model.files.courseChoices() }
    val colors = Range.colors
    if (choices.isEmpty()) Text("No courses in ${model.files.courses.path}", color = colors.muted)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("course-list")) {
        for ((group, courses) in choices.groupBy { it.group }) {
            if (group.isNotEmpty()) item(key = "group-$group") { Text(group.replaceFirstChar { it.uppercase() }, color = colors.muted, fontSize = 12.sp) }
            items(courses, key = { it.file.path }) { choice ->
                PanelRow(choice.name, "course-${choice.name}") {
                    model.loadCourse(choice)
                    close()
                }
            }
        }
    }
}

@Composable
private fun SaveCoursePanel(model: TargetsModel, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<SaveOutcome?>(null) }
    val colors = Range.colors
    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            outcome = null
        },
        label = { Text("Course name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("course-name"),
    )
    Text("Saved in ${model.files.courses.path}", color = colors.muted, fontSize = 12.sp)
    when (outcome) {
        SaveOutcome.EXISTS -> {
            Text("There is already a course named ${name.trim()}. Replace it?", color = colors.warning, modifier = Modifier.testTag("replace-question"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        model.saveCourse(name, replace = true)
                        close()
                    },
                    modifier = Modifier.testTag("replace"),
                ) { Text("Replace") }
                TextButton(onClick = { outcome = null }) { Text("Cancel") }
            }
        }
        else -> {
            if (outcome == SaveOutcome.BAD_NAME) {
                Text("Give the course a name, without / or \\", color = colors.warning, modifier = Modifier.testTag("name-hint"))
            }
            FilledTonalButton(
                onClick = {
                    outcome = model.saveCourse(name)
                    if (outcome == SaveOutcome.SAVING) close()
                },
                modifier = Modifier.testTag("save"),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun PanelRow(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = Range.colors.text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp).testTag(tag),
    )
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestTargetsToolbar --tests com.shootoff.compose.app.TestTargetsScreen --console=plain`

Expected: PASS, 7 tests in `TestTargetsToolbar`.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `885/885 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/TargetsScreen.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestTargetsToolbar.kt
git commit -m "Add the Targets screen's toolbar, its panels, and the camera feed's targets"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 9: The layout at launch and on close

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/courses/LayoutMemory.kt` (`flush`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`layoutMemory`, `restoreLayoutOnceInPlace`, `closeArena`, `close`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedLayout.kt`
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt`

**Interfaces:**
- Consumes (Tasks 3, 5, 7): `AppState.arenaLayout`, `targetsModel.loader`, `ArenaFiles.layout`, `LayoutMemory`, `AppState.TIMER_POOL`, `AppFixture.putOnTheProjector`.
- Produces: `LayoutMemory.flush()`; `AppState` restores the remembered layout once a session and saves a waiting change in `close()`; `Main` passes `ArenaFiles.inHome(File(home))` and `ImagePicker.Awt`.

**Why.** Spec §5 ("At launch, once the arena is open, it loads that file through `CourseLoader`") and ruling 4 on when; Review Focus 2 on closing at once.

- [ ] **Step 1: Write the failing tests**

Create `compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedLayout.kt`:

```kotlin
package com.shootoff.compose.app

import com.shootoff.compose.targets.ManualClock
import com.shootoff.compose.targets.TargetOwner
import com.shootoff.config.ScratchConfig
import com.shootoff.config.Settings
import com.shootoff.courses.io.CourseIO
import com.shootoff.geom.Rect
import com.shootoff.geom.Size
import com.shootoff.targets.model.Placement
import com.shootoff.targets.model.ResourceResolver
import com.shootoff.targets.model.TargetDefinitions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// Spec §5: the arena's layout is remembered in arena-layout.course and comes back at launch, once the arena is open
class TestRememberedLayout {
    @TempDir
    lateinit var temp: Path

    private val home = File(System.getProperty("user.dir"))
    private var app: AppState? = null

    @AfterEach
    fun close() {
        app?.close()
    }

    private fun layoutFile() = temp.resolve("arena-layout.course").toFile()

    private fun app(screens: List<Rect> = AppFixture.ownerScreens): AppState = AppState(
        Settings(ScratchConfig.emptyFile().path, arrayOf()),
        ExerciseCatalog(),
        CameraSource.None,
        { screens },
        ManualClock(),
        { it.run() },
        arenaFiles = ArenaFiles(home, File(home, "targets"), temp.resolve("courses").toFile(), layoutFile()),
    ).also { app = it }

    // A layout saved on the owner's 1280x720 arena, with one Reset target at (100, 200)
    private fun remember() {
        layoutFile().writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <course>
            	<target file="targets/Reset.target" x="100.000000" y="200.000000" width="50.000000" height="50.000000" />
            	<resolution width="1280.000000" height="720.000000" />
            </course>
            """.trimIndent(),
        )
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("never: $what")
            Thread.sleep(5)
        }
    }

    private fun shooters(app: AppState) = app.arenaLayout.targets.targetsOf(TargetOwner.USER)

    @Test
    fun theRememberedLayoutComesBackOnceTheArenaFillsTheProjector() {
        remember()
        val app = app()

        app.openArena()
        // Still the window's first size, on its way to the projector
        Thread.sleep(100)
        assertTrue(shooters(app).isEmpty())

        AppFixture.putOnTheProjector(app)

        awaitTrue("the layout is back") { shooters(app).size == 1 }
        assertEquals(Placement(100.0, 200.0, 1.0, 1.0, true), shooters(app).single().placement)
    }

    @Test
    fun withoutAProjectorScreenTheLayoutComesBackAsTheArenaOpens() {
        remember()
        val app = app(listOf(Rect(0.0, 0.0, 1920.0, 1080.0)))
        app.arenaLayout.setSize(Size(1280.0, 720.0))

        app.openArena()

        awaitTrue("the layout is back") { shooters(app).size == 1 }
    }

    @Test
    fun theLayoutComesBackOnlyOnceASession() {
        remember()
        val app = app()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue("the layout is back") { shooters(app).size == 1 }
        app.targetsModel.clear()

        app.closeArena()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        Thread.sleep(100)

        assertTrue(shooters(app).isEmpty())
    }

    @Test
    fun aChangeIsRememberedEvenIfTheAppClosesAtOnce() {
        val app = app()
        app.arenaLayout.setSize(Size(1280.0, 720.0))
        app.arenaLayout.targets.add(TargetDefinitions.load(File(home, "targets/Reset.target").toPath()), ResourceResolver.files(), Placement(10.0, 20.0, 1.0, 1.0, true))

        app.close()
        this.app = null

        assertEquals(listOf(10.0 to 20.0), CourseIO.loadCourse(layoutFile()).get().targets.map { it.x() to it.y() })
    }

    @Test
    fun theDefaultAppRemembersNothing() {
        assertNull(ArenaFiles.scratch().layout)
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt`:

Replace:

```kotlin
        assertEquals(Size(1280.0, 720.0), saved.resolution.get())
    }

    @Test
    fun aNewBackgroundIsSaved() {
        val layout = layout()
```

with:

```kotlin
        assertEquals(Size(1280.0, 720.0), saved.resolution.get())
    }

    @Test
    fun aSaveWaitingWhenTheAppClosesIsMadeAtOnce() {
        val layout = layout()
        val memory = memory(layout)
        add(layout)

        memory.flush()

        assertTrue(file().isFile)
        assertTrue(timers.live().isEmpty())
    }

    @Test
    fun aNewBackgroundIsSaved() {
        val layout = layout()
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestRememberedLayout --tests com.shootoff.compose.courses.TestLayoutMemory --console=plain`

Expected: compilation fails, `Unresolved reference 'flush'`.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/Main.kt`:

Replace:

```kotlin
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.shootoff.compose.app.AppState
import com.shootoff.compose.app.CameraSource
import com.shootoff.compose.app.ExerciseCatalog
import com.shootoff.compose.app.PrefsStore
import com.shootoff.compose.app.UiPrefs
import com.shootoff.compose.app.WindowBounds
```

with:

```kotlin
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.shootoff.compose.app.AppState
import com.shootoff.compose.app.ArenaFiles
import com.shootoff.compose.app.CameraSource
import com.shootoff.compose.app.ExerciseCatalog
import com.shootoff.compose.app.ImagePicker
import com.shootoff.compose.app.PrefsStore
import com.shootoff.compose.app.UiPrefs
import com.shootoff.compose.app.WindowBounds
```

Replace:

```kotlin
    val plugins = PluginEngine(catalog, listOf(V2ExerciseLoader()), emptyList())
    plugins.startWatching()

    val app = AppState(settings, catalog, CameraSource.System, prefs = UiPrefs(PrefsStore.User()))
    Settings.setUserNotifier(app.notices)
    // The camera opens in the background; the arena opens on the projector, if there is one, once the main
    // window below reports where it really landed. The window shows at once and nothing calibrates (spec §8
```

with:

```kotlin
    val plugins = PluginEngine(catalog, listOf(V2ExerciseLoader()), emptyList())
    plugins.startWatching()

    val app = AppState(
        settings,
        catalog,
        CameraSource.System,
        prefs = UiPrefs(PrefsStore.User()),
        // The arena's layout is remembered in ShootOFF's folder, beside the courses (spec §5)
        arenaFiles = ArenaFiles.inHome(File(home)),
        imagePicker = ImagePicker.Awt,
    )
    Settings.setUserNotifier(app.notices)
    // The camera opens in the background; the arena opens on the projector, if there is one, once the main
    // window below reports where it really landed. The window shows at once and nothing calibrates (spec §8
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.calibration.work
import com.shootoff.compose.drill.ArenaHostSurface
import com.shootoff.compose.drill.ComposeExerciseHost
import com.shootoff.compose.drill.DrillState
```

with:

```kotlin
import com.shootoff.compose.calibration.savedCalibrationMismatch
import com.shootoff.compose.calibration.showsPattern
import com.shootoff.compose.calibration.work
import com.shootoff.compose.courses.LayoutMemory
import com.shootoff.compose.drill.ArenaHostSurface
import com.shootoff.compose.drill.ComposeExerciseHost
import com.shootoff.compose.drill.DrillState
```

Replace:

```kotlin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
```

with:

```kotlin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
```

Replace:

```kotlin
    /** The Targets screen's state */
    val targetsModel = TargetsModel(arenaLayout, feedTargets, displaySize, arenaFiles, imagePicker, scope, io)

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
```

with:

```kotlin
    /** The Targets screen's state */
    val targetsModel = TargetsModel(arenaLayout, feedTargets, displaySize, arenaFiles, imagePicker, scope, io)

    // Remembers the arena's layout between sessions (spec §5), if there is a file to keep it in
    private val layoutMemory = arenaFiles.layout?.let { LayoutMemory(arenaLayout, targetsModel.loader, it, TIMER_POOL).also(LayoutMemory::start) }

    // Whether the remembered layout has been restored, or tried, this session: it comes back once
    private var layoutRestored = false
    private var layoutWatch: Job? = null

    private val arenaState = MutableStateFlow<ArenaModel?>(null)
    private val placementState = MutableStateFlow<ArenaPlacement?>(null)
    private val calibrationState = MutableStateFlow<CalibrationController?>(null)
```

Replace:

```kotlin

        cameraState.value?.let { makeCalibratable(arena, it) }
        if (settings.rememberCalibration()) calibrateOrCheck(arena, "the arena opened")
    }

    // With the option on, what an uncalibrated arena does (spec §8 Revision 3): a remembered manual box is
```

with:

```kotlin

        cameraState.value?.let { makeCalibratable(arena, it) }
        if (settings.rememberCalibration()) calibrateOrCheck(arena, "the arena opened")
        restoreLayoutOnceInPlace(arena)
    }

    // The remembered layout comes back the first time an arena this session is where it stays (spec §5): filling
    // the projector's screen, so a course saved at another size is scaled to the arena's real one; or, with no
    // projector screen, at once in its window. Restored off the UI thread: its target images are read.
    private fun restoreLayoutOnceInPlace(arena: ArenaModel) {
        val memory = layoutMemory ?: return
        if (layoutRestored) return
        val screen = placementState.value?.screen
        if (screen == null) {
            layoutRestored = true
            scope.launch(io) { memory.restore() }
            return
        }
        layoutWatch?.cancel()
        layoutWatch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(arena.fullScreen, arena.size) { fullScreen, size -> fullScreen && fills(size, screen) }.first { it }
            uiThread(
                Runnable {
                    if (arenaState.value !== arena || layoutRestored) return@Runnable
                    layoutRestored = true
                    scope.launch(io) { memory.restore() }
                },
            )
        }
    }

    // With the option on, what an uncalibrated arena does (spec §8 Revision 3): a remembered manual box is
```

Replace:

```kotlin
        calibrationState.value?.arenaClosing()
        forgetCalibrationTimes()
        fullScreenWatch?.cancel()
        calibrationState.value = null
        // The arena goes first, so no projector drill can start on it from here on (newHost finds none);
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
```

with:

```kotlin
        calibrationState.value?.arenaClosing()
        forgetCalibrationTimes()
        fullScreenWatch?.cancel()
        layoutWatch?.cancel()
        calibrationState.value = null
        // The arena goes first, so no projector drill can start on it from here on (newHost finds none);
        // then the one running, if any, stops under the runner's lock, so one started just before can't slip by
```

Replace:

```kotlin
        stopWatchingForReturn()
        runner.stop()
        closeArena()
        cameras.closeAll()
        scope.cancel()
    }
```

with:

```kotlin
        stopWatchingForReturn()
        runner.stop()
        closeArena()
        // A change still waiting out its quiet time is saved before the app goes
        layoutMemory?.flush()
        cameras.closeAll()
        scope.cancel()
    }
```

`compose-app/src/main/kotlin/com/shootoff/compose/courses/LayoutMemory.kt`:

Replace:

```kotlin
        return true
    }

    private fun changedByTheShooter() {
        if (restoring) return
        changed = true
```

with:

```kotlin
        return true
    }

    /** Saves at once if a save is waiting for the changes to stop: the app is closing */
    fun flush() {
        val waiting = synchronized(lock) {
            val cancelled = pending?.cancel(false) == true
            pending = null
            cancelled
        }
        if (waiting) save()
    }

    private fun changedByTheShooter() {
        if (restoring) return
        changed = true
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestRememberedLayout --tests com.shootoff.compose.courses.TestLayoutMemory --console=plain`

Expected: PASS, 5 tests in `TestRememberedLayout` and 9 in `TestLayoutMemory`.

- [ ] **Step 5: Run the gate**

Run the test gate (Global Constraints). Expected: `891/891 passing; 0 regressions; 0 new failures`. Then `sha256sum -c build/plan8-owner-files.sha256` (all OK) and `git status --short` (only ` M shootoff.properties` and this task's files).

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/Main.kt compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/courses/LayoutMemory.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestRememberedLayout.kt compose-app/src/test/kotlin/com/shootoff/compose/courses/TestLayoutMemory.kt
git commit -m "Restore the arena's layout at launch and keep it in arena-layout.course"
git log -1 --format=%B
```

Expected: the message alone, no trailer.

### Task 10: The owner's hardware check

Not for a subagent: the owner runs `./gradlew :compose-app:run` with the projector and the C270, and reports.

- [ ] **1. Place.** On the Targets screen (Arena), Add target → IPSC. It appears in the middle of the wall, selected. Drag it; it follows the mouse on the wall. Drag a corner; it resizes, the opposite corner still. Hold Ctrl and drag a corner; it keeps its shape. Click it, then arrows (moves 1 unit), Shift+arrows (resizes), Esc (deselects), click and Delete (gone). Drag one far off an edge: a strip stays on.
- [ ] **2. Background.** Background… → Steel Range Bay; the wall shows it. Background… → An image file…, pick a PNG; it shows. Background… → None.
- [ ] **3. Courses.** Load course… → steel challenge → accelerator. Five plates land on the wall where the JavaFX app put them (all on the wall, none cut off). Save course… as "Mine"; save again as "Mine" and see the Replace question.
- [ ] **4. Clear and Undo.** Clear; the wall empties. Undo within 8 s; the targets and background come back.
- [ ] **5. Calibrate.** Recalibrate on Setup, then go to Targets while it runs: the note "Calibrating — the projector shows the pattern; your edits appear when it's done." shows, the targets stay on the screen, and a move made meanwhile shows on the wall once the pattern goes.
- [ ] **6. Restart.** Quit straight after a drag. Start again: once the arena is full screen on the projector, the layout is back, the last drag included.
- [ ] **7. A drill.** Start a projector drill that adds its own targets: on the Targets screen they are dimmed with an "exercise" badge and can't be grabbed; Clear leaves them; stopping the drill removes them.
- [ ] **8. The camera.** Targets → Camera. Add target → a target over the live feed; shoot it with the laser; the hit registers (the shot timer row, and the target's hit animation if it has one). Restart: the feed's targets are gone (session only).
- [ ] **9. The files.** `sha256sum -c build/plan8-owner-files.sha256` still prints OK for every line; `arena-layout.course` is in the ShootOFF folder; `courses/Mine.course` is the owner's own now.
