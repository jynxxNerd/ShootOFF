# Compose Trial (Sub-project 1b) — Design

- **Date:** 2026-09-26
- **Status:** Draft, awaiting review
- **Branch:** `compose-ui` (long-lived; merged only if the owner decides to keep Compose)
- **Builds on:** sub-project 1a (`docs/superpowers/specs/2026-09-26-core-split-and-exercise-api-design.md`), done: modules `core`, `plugin-api`, `javafx-app`; UI-neutral target model, sessions, courses, plugin engine and exercise API; RandomTargetParDrill ported to the v2 API (drill repo, branch `plugin-api-v2`).

## 1. Intent

The owner wants to replace ShootOFF's JavaFX UI with Compose Multiplatform (Desktop), mostly for look and feel. 1b is the trial that lets them decide: a Compose app they can use for a real practice session with their RandomTargetParDrill, finished to shipping polish, so the decision is made on the real look rather than a rough cut.

### Owner decisions

| Question | Decision |
|---|---|
| How complete is the trial? | A **polished working slice**: pick the camera, open the arena, calibrate, run the drill end to end, with shipping-level polish (themes, animation, status strip, resizable tray). |
| Look | **Range dark**: a custom Material 3 theme, dark slate with an orange accent and high-contrast numbers. A matching light variant exists behind a light/dark switch. |
| Range screen layout | **Big feed with a bottom tray**: the view fills the screen, the running drill's card floats over it, and the shot timer and drill settings sit in a tray along the bottom. |
| Arena on the range screen | A **Camera \| Arena segmented switch** over the feed flips the big view (one segment per camera). No corner preview. |
| Other destinations | Rail shows **Range, Targets, Drills, Sessions, Settings**. In the trial: Range as above; Drills picks and starts v2 exercises; Settings is slim (camera choice, marker size, arena display); **Targets and Sessions are visible but disabled**, labeled "in the JavaFX app for now". |
| Architecture | **Extract first, then build**: UI-neutral pieces that the Compose app would otherwise copy move from `javafx-app` into `core` / `plugin-api`, and the JavaFX app switches onto them before `compose-app` is built. |

Mockups from the design session are in `.superpowers/brainstorm/156968-1790456357/content/` (`theme.html` option B, `layout-v2.html` option A).

### Success criteria

1. `./gradlew :compose-app:run` opens the Compose app in Range dark with the status strip, the Camera | Arena switch and a working light/dark switch.
2. The owner picks the camera, opens the arena on the projector and auto-calibrates, adjusting the box by hand if needed.
3. RandomTargetParDrill (v2) runs end to end: placement, cues and sounds, par timing, points, the coral par-miss row, pause/resume, the summary with hit factor and personal best, the shot replay, and shoot-to-reset.
4. `./gradlew run` still starts the JavaFX app, which works as before, and the full test suite passes.
5. The owner decides whether to keep Compose.

## 2. Modules and extraction

### `compose-app` (new)

- Kotlin (2.x), Compose Multiplatform Desktop, Material 3. Depends on `core` and `plugin-api` only; no OpenJFX.
- Launched with `./gradlew :compose-app:run`. `./gradlew run` keeps launching the JavaFX app. Both use the repository root as the working directory and share `shootoff.properties`, `targets/`, `sounds/`, `exercises/` and `exercise-data/`.
- Kotlin, the Compose Gradle plugin and Compose libraries are new dependencies, added to the version catalog. `core`, `plugin-api` and `javafx-app` gain no new dependencies.

### Moved into `core` / `plugin-api` first (Plan 4)

Each move is its own step. The JavaFX app switches onto the moved code in the same step, and the existing suite (371 tests at the end of 1a) keeps passing.

1. **Arena geometry.** Camera ↔ arena coordinate conversion using the calibrated projection bounds (today `CanvasManager.scaleShotToArenaBounds` and the translate helpers).
2. **Shot pipeline.** Everything between a detected `ScaledShot` and the exercise: shot processors (malfunctions, virtual magazine), the ignored laser color, hit-testing against the right `TargetSet` (camera or arena, by projection bounds), region commands (e.g. `poi_adjust`), session recording, the shot-timer rows, and delivery to the running exercise's host (today spread through `CanvasManager.addShot` / `addArenaShot` / `processShot`). Rows are appended on one thread in order, which also fixes the existing race where two shots in the same camera frame corrupt the JavaFX shot-timer list.
3. **Calibration flow.** A UI-neutral state machine: show pattern → auto-detect → success or timeout → manual adjust → save bounds; it pauses the running exercise and restarts it afterwards. `CalibrationManager` keeps only the JavaFX drawing and input.
4. **Exercise host support.** The UI-neutral part of `JavaFxExerciseHost`: resource resolution (exercise jar first), background save/restore, tracking what the exercise added and removing it on stop, timer rows, par/delay listeners. It lives in `plugin-api`, next to `ExerciseExecutor`, because it drives an `Exercise`. `JavaFxExerciseHost` becomes a thin JavaFX layer over it.

### Inside `compose-app` (Plan 5)

- **App state:** a Kotlin state holder for the selected camera, arena, calibration, running drill and settings.
- **Camera feed:** implements `core`'s `CameraView`; converts `BufferedImage` frames for Compose; draws shot markers and diagnostic banners over the feed.
- **Target layer:** draws a `TargetSet` from the model (ellipses, rectangles, polygons, images, animated GIF frames), honoring visibility and region visibility.
- **Arena window:** a second window, full screen on the detected projector screen (same screen detection as the JavaFX app).
- **Calibration overlay:** auto-detect progress, then a draggable, resizable box.
- **Drill panel:** renders what the exercise asked for (buttons, number settings, texts, timer columns and rows) as Material components.
- **Screens:** Range (with the view switch), Drills, Settings; Targets and Sessions disabled.
- **`ComposeExerciseHost`:** `ExerciseHost` on the shared host support.

## 3. Data flow and threading

- **Camera frames.** `CameraManager` runs on its own thread. The Compose `CameraView` keeps only the latest frame (conflated); Compose draws the newest frame each refresh, so frames never queue. Diagnostic warnings arrive through the same interface and show as dismissible banners.
- **Shots.** A detected shot goes to the shared pipeline, which hit-tests it against the camera's or the arena's `TargetSet`, records it, appends a row and hands it to the host. Rows and markers are published as observable lists that Compose reads.
- **Arena: one model, two views.** The arena has a single `TargetSet`. The projector window and the in-app Arena view both render it. There is no mirrored second copy in the Compose app, so the class of bug found in the Plan 3 hardware check (a host holding the wrong copy) cannot occur there. The JavaFX app keeps its mirrored canvases.
- **Exercises.** `ComposeExerciseHost` keeps the §4 contract of the 1a spec: one exercise thread per running exercise, host methods callable from any thread, `stop()` cancels and removes everything. Host calls update observable state that Compose redraws; no host call waits on the UI thread.
- **Calibration.** The shared calibration flow drives the arena window (pattern), `CameraManager` auto-calibration and the overlay; bounds are saved through `Settings`.
- **Settings.** Read and written through `core`'s `Settings`; one `shootoff.properties` for both apps.

## 4. Errors, edge cases and polish

**Errors and edge cases**

- **No camera, or it drops out:** the feed shows a "No camera" panel with a camera picker, never a frozen frame. Automatic reconnect stays out of scope (an existing follow-up).
- **No projector screen:** the Arena segment is disabled with a hint; Drills marks projector-only drills "Needs the projector arena" instead of failing.
- **Auto-calibration times out:** the overlay goes straight to the manual box.
- **An exercise throws:** logged; the host stops the exercise and removes what it added; a banner says the drill stopped and why.
- **Broken or mismatched plugin jars:** skipped, the rest loads (1a behavior).
- **Missing target/course files and other `UserNotifier` messages:** a snackbar or dialog.

**Polish**

- Status strip on the feed: camera, FPS, resolution, calibration state, session recording.
- Light/dark switch; Range dark by default, with a matching slate-and-orange light variant.
- Animated transitions: the view switch, drill card updates, new shot-timer rows, banners.
- Resizable, collapsible bottom tray; window size, position and last view remembered.
- HiDPI scaling.
- Keyboard shortcuts: switch view, pause drill, clear shots, start calibration.

**Out of scope for 1b**

- The ten built-in exercises (sub-project 2); only v2 exercises run in the Compose app.
- Targets, Sessions, Courses and the target editor in Compose (sub-project 3).
- The phone remote.
- The auto-calibration drift seen in the Plan 3 hardware check (pre-existing, same on master).
- Changes to the `.target`, `.course`, session or `shootoff.properties` formats.

## 5. Testing

- **Plan 4 moves:** new `core` unit tests per piece — arena geometry against the old `CanvasManager` math across placements and scales; the shot pipeline (processors, camera and arena hits, recording, row order, two shots in one frame); the calibration flow's transitions with a fake camera and arena; host support (resolution order, stop cleanup, timer rows). Existing JavaFX tests stay unchanged and pass.
- **Shared host contract tests:** one `ExerciseHost` contract suite (one exercise thread; stop cleanup; timer rows; hiding and removing targets on the surface the exercise sees) runs against both `JavaFxExerciseHost` and `ComposeExerciseHost`.
- **Compose UI tests** (Compose desktop test kit): the drill panel renders the host's buttons, settings, texts and rows; the Camera | Arena switch; the calibration overlay's states; Targets and Sessions disabled; error banners.
- **Boundary test:** no `compose-app` class references `javafx.*`.
- **Owner hardware check:** the full practice session in the Compose app (success criteria 1–3), plus a short JavaFX regression check.

## 6. Delivery

Two plans, each leaving the build and suite green after every task:

- **Plan 4 — extraction:** the four moves into `core` / `plugin-api`, with the JavaFX app switched onto them and the host contract suite running against `JavaFxExerciseHost`. Shippable on its own.
- **Plan 5 — `compose-app`:** the Compose module, `ComposeExerciseHost` (joining the contract suite), the screens and polish, and the owner hardware check.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Moving the shot pipeline changes live shot behavior in the JavaFX app | Existing shot, hit, session and exercise tests unchanged; new pipeline tests; JavaFX hardware check in Plan 5 |
| Camera frames too slow to draw in Compose | Conflated latest-frame state; convert frames off the UI thread; measure FPS on the status strip at 640×480/30 FPS |
| Target rendering differs from JavaFX | Hit-testing is the shared model; rendering checked by the owner on the projector; animated frames driven by the model's region state |
| Kotlin and Compose toolchain in a Java build | Isolated to `compose-app`; versions pinned in the catalog; other modules untouched |
| Branch drift from `master` | Merge `master` into `compose-ui` regularly, as in 1a |

## 8. Revision 1 (2026-09-27): setup separated from training

The owner's first hardware session with the Plan 5 app found that the Camera | Arena switch, and the app's flow in general, didn't match how a session goes. Camera and projector setup (including calibration) is done once per session, and again only when the camera or projector moves. Drills (and later courses) are training and shouldn't share a screen with setup. Mockups: `.superpowers/brainstorm/527451-1790488215/content/flow.html`; the owner chose a combination of options A and B.

This section supersedes the conflicting parts of §1 (layout and destinations), §2 "Inside compose-app" (screens) and §4 (shortcuts).

### Destinations

The rail shows **Range, Setup, Drills, Targets, Sessions, Settings**. Targets and Sessions stay disabled as before.

### Setup (new)

- One screen with three ordered steps, each showing its state (✓ when done, the next step highlighted):
  1. **Camera**: pick the camera; shows the live feed with FPS and resolution.
  2. **Projector**: open (or close) the arena on the projector screen; shows a small live preview of the arena.
  3. **Calibrate**: auto-calibration, falling back to the draggable manual box (Task 8's overlay), over the camera feed shown on this screen.
- The rail's Setup item is always available, so the owner can recalibrate or change the camera whenever they want.
- When calibration succeeds from Setup, the app returns to Range.
- **Remember calibration** checkbox, off by default:
  - Off: calibration starts whenever the arena opens (current behavior).
  - On: the calibration (projection bounds, the calibrated feed behavior, and the camera and projector screen it was made with) is saved. At launch or arena open, if the same camera and the same projector screen resolution are present, the saved calibration is applied and no calibration runs; otherwise the Calibrate step asks for a recalibration.
  - Stored through `core`'s `Settings` (new keys, additive), so the JavaFX app preserves them when it rewrites `shootoff.properties`. Tests use `ScratchConfig`.
- **Automatic check of a remembered calibration.** Before reusing a saved calibration (at launch or arena open), the app briefly shows the calibration pattern on the projector (about a second), detects it with the existing auto-calibration pattern detection, and compares the detected projection bounds with the saved ones.
  - If every edge is within the tolerance (5 camera pixels by default), the saved calibration is kept and the pattern is replaced by the arena's background.
  - Otherwise the app keeps the arena uncalibrated and the Calibrate step (and Range's prompt) says how far it moved, for example "The projection moved about 14 px — recalibrate", offering auto-calibration or the manual box.
  - If the pattern can't be detected at all (lighting, camera covered), it says so and offers recalibration; it never silently keeps a calibration it couldn't verify.
- **Calibration view.** The Calibrate step shows the current calibration visually:
  - The camera feed with the calibrated projection rectangle drawn over it (orange outline, corner handles only while adjusting).
  - A **Show grid** toggle projects a grid on the arena (evenly spaced lines plus the arena's corners and center marked). With the rectangle drawn on the feed, the owner can see at a glance whether the projected grid still sits inside the rectangle.
  - The grid and the rectangle are only shown on Setup; Range and running drills are never affected. Turning the grid off, leaving Setup, or starting a drill restores the arena's background.

### Range (training only)

- The camera feed (the big view), the drill card with **Start / Pause / Stop**, a **drill picker** next to it, **Clear shots** (replacing Reset), the shot timer and settings tray, the status strip, and the feed banners.
- A status chip (for example "✓ Calibrated 01:12", or what is missing) opens Setup.
- **Not-ready prompt:** when a projector drill can't run (no camera, no arena, or not calibrated), Range shows a card over the feed listing the setup steps and their state, with **Set up** (opens Setup) and **Skip** (hides it for camera-only use). It comes back if the camera drops or the arena closes during the session.
- Starting a projector drill still requires an open, calibrated arena (Plan 5's guard stays).

### Removed

- The Camera | Arena segmented switch and the in-app Arena view as a main view. The arena preview exists only on Setup.
- F2 (switch view). F6 now opens Setup and starts calibrating; F3 (pause) and F4 (clear shots) stay; F11 in the arena window stays.
- Starting drills from the Drills page: Drills becomes a library (name, description, "needs the projector arena"); starting happens from Range's picker.

### Unchanged

The engine (core, plugin-api), the exercise host and runner, the drill, the target layer, the shot pipeline, calibration's flow and overlay, the theme, and the JavaFX app.

### Success criteria (revision)

Adds to §1's criteria:

6. A session reads top to bottom: open the app → (prompt) → Setup: camera, projector, calibrate → back on Range → pick the drill → Start → shoot → Stop, without visiting the Drills page.
7. With "Remember calibration" on, relaunching with the same camera and projector verifies the saved calibration automatically (about a second of pattern) and skips calibration when it still fits; after the camera or projector is moved, the check reports the drift and asks for recalibration.
8. On Setup, the calibration rectangle is drawn over the camera feed, and Show grid projects a grid whose alignment with the rectangle can be judged by eye.

### Delivery

**Plan 6** implements this revision on `compose-ui`, then the owner repeats the hardware check.
