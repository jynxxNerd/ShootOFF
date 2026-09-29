# Built-in exercise port — design

Date: 2026-09-29. Branch: `compose-ui`. Roadmap step 2, second sub-project (the first was target placement, `2026-09-28-compose-target-placement-design.md`).

## 1. Why

The ten built-in exercises are JavaFX-era classes in `javafx-app` (`com.shootoff.plugins`, on `TrainingExerciseBase` / `ProjectorTrainingExerciseBase`, registered by `BuiltInExercises`). The Compose app can't see `javafx-app`, so it has no built-ins at all (`Main.kt` passes an empty list to `PluginEngine`). The UI-neutral exercise API ("v2", `plugin-api` `com.shootoff.exercise`) already runs the owner's RandomTargetParDrill in both apps. Porting the ten to v2 gives both apps the same built-ins and lets the JavaFX exercise classes go before the JavaFX app is retired (roadmap step 4).

## 2. What the owner said

- All ten, faithful to how they worked before.
- The six "standard" exercises get their targets and shots from everywhere, as in the old app (projector and camera feed alike), not only the camera feed.
- (From the design review) the new module, the API additions, pause on drills with rounds, deleting the JavaFX classes, and the split into two plans.

## 3. Success

- Both apps list the same ten built-ins and run them from the same code.
- Each behaves as its JavaFX version did: scoring, timings, rounds, sounds, spoken call-outs, shot timer columns and texts.
- The standard six score and time the owner's projector targets on the wall, and camera-feed targets too.
- The projector drills place their own targets, or use the shooter's course/tree target, as before.
- The JavaFX exercise classes and their base classes are gone; their tests live on against the v2 test host.

## 4. The exercises

| Exercise | Mode | Needs |
|---|---|---|
| Shoot for Score | everywhere | "points" tags |
| Random Shoot | everywhere | "subtarget" tags, voice files (G2) |
| Timed Holster Drill | everywhere | delayed start, Pause/Resume, Clear Shots button, row shading |
| Par for Score | everywhere | Timed Holster plus par time, Score column |
| Par Random Shot | everywhere | Par for Score plus random sub-target call-outs (G2) |
| ISSF Standard Pistol | everywhere | 150 s / 20 s / 10 s series, early end at 5 shots, TTS |
| Shoot/Don't Shoot | projector | its own targets |
| Bouncing Targets | projector | its own moving targets, its settings (G1) |
| Steel Challenge | projector | a Steel Challenge course loaded by the shooter, magazine reload (G5) |
| Dueling Tree | projector | the shooter's Duel Tree target, resetting its paddles (G4) |

## 5. Architecture

- **Module `builtin-exercises`** (Java 21), depending only on `plugin-api`. It holds the ten exercises and a registry of them. `compose-app` and `javafx-app` both depend on it and load its ten as v2 entries (`V2ExerciseEntry`), alongside plugin jars.
- **API additions** (`plugin-api`, implemented by both apps' hosts and by `FakeExerciseHost` in the test fixtures, with contract tests):
  - **G1** a yes/no setting and a choice setting, beside `addNumberSetting` (Bouncing Targets).
  - **G2** whether a sound resource exists (Random Shoot, Par Random Shot: a sub-target's voice file, else TTS).
  - **G4** an exercise-initiated reset of target animations (and the shot markers), without calling the exercise's own `onReset` (Dueling Tree).
  - **G5** a virtual magazine reload (Steel Challenge; a no-op when the virtual magazine is off).
- **Everywhere mode** for non-projector exercises. The Compose host, for such an exercise:
  - `targets()` is the shooter's and exercises' targets on the camera feed and on the arena together;
  - `onShot` gets every shot — feed shots and arena shots — once each, with its hit;
  - `showText` shows on the camera feed and on the arena; `showMessage` as today plus the arena;
  - `onTargetsChanged` fires for changes on either surface;
  - `addTarget` (none of the six uses it) adds to the camera feed.
  The JavaFX host already behaves this way for its standard exercises; it keeps doing so.
- **Projector mode** is unchanged: the exercise runs on the arena and gets arena shots only.
- **Pause.** Every exercise with rounds or timers has Pause/Resume buttons (labelled exactly `Pause` / `Resume`, which the Compose app's recalibration pause relies on), the four projector drills included, so a recalibration mid-drill pauses and resumes it rather than restarting it.
- **Removal.** Once each exercise is ported, the JavaFX app lists the v2 version; at the end, the JavaFX exercise classes, `TrainingExerciseBase`, `ProjectorTrainingExerciseBase` and `BuiltInExercises`' legacy entries are deleted. Legacy plugin jars (v1) keep loading as today.

## 6. Behaviour and edge cases

- **Faithful port.** The JavaFX class is the reference wherever this spec is silent. Mechanical differences only: timers (`host.schedule`) instead of `Thread.sleep`, handles instead of JavaFX nodes, and Pause buttons where they were missing.
- **Settings** keep their names and defaults; values persist as the host's settings already do.
- **Steel Challenge** without a Steel Challenge course on the arena says: "Load a Steel Challenge course on the Targets screen first." **Dueling Tree** without a Duel Tree target says: "Add a Duel Tree target on the Targets screen first."
- **The shooter's targets are never moved, resized or hidden** by any of the ten (none did before).
- **A camera target over the projection during a projector drill** takes the shots that land on it (target-placement spec §9), so the drill doesn't see them. When a projector drill starts while a camera-feed target overlaps the projected area, a banner says: "A camera target covers part of the projection; shots there go to it, not the drill."
- **Session recording** is unchanged.

## 7. Plans

- **Plan 13:** the module and registry in both apps, G1/G2/G4/G5 in the API and both hosts, everywhere mode, and the six standard exercises (Shoot for Score, Random Shoot, Timed Holster, Par for Score, Par Random Shot, ISSF), with their JavaFX classes removed.
- **Plan 14:** the four projector drills (Shoot/Don't Shoot, Bouncing Targets, Steel Challenge, Dueling Tree), the overlap banner, and removing the JavaFX base classes and `BuiltInExercises`' legacy entries.

## 8. Testing

- The seven existing JavaFX exercise tests (`TestShootForScore`, `TestRandomShoot`, `TestISSFStandardPistol`, `TestShootDontShoot`, `TestBouncingTargets`, `TestSteelChallenge`, `TestDuelingTree`) ported to `FakeExerciseHost`, their assertions kept.
- New tests for Timed Holster, Par for Score and Par Random Shot.
- Contract tests for G1/G2/G4/G5 against both hosts and the fake.
- Compose host tests for everywhere mode: targets from both surfaces, a feed shot and an arena shot each delivered once, text on both.
- Registry tests: both apps list the ten.
- The full gate with no regressions after each task.
- The owner's hardware check after each plan: each exercise run once on the wall.

## 9. Out of scope

- New exercises or changed behaviour beyond §6.
- The JavaFX app's retirement itself (roadmap step 4).
- A Compose session viewer.
