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
  - Off: nothing is saved; the arena is uncalibrated until the owner calibrates on Setup.
  - On: the calibration (projection bounds, the calibrated feed behavior, and the camera and projector screen it was made with) is saved. When the owner opens the arena, if the same camera and the same projector screen resolution are present, the saved calibration is checked (below) and applied; otherwise the Calibrate step asks for a recalibration.
  - Stored through `core`'s `Settings` (new keys, additive), so the JavaFX app preserves them when it rewrites `shootoff.properties`. Tests use `ScratchConfig`.
- **Automatic check of a remembered calibration.** Before reusing a saved calibration (only when the owner opens the arena, never at launch), the app briefly shows the calibration pattern on the projector (about a second), detects it with the existing auto-calibration pattern detection, and compares the detected projection bounds with the saved ones.
  - If every edge is within the tolerance (5 camera pixels by default), the saved calibration is kept and the pattern is replaced by the arena's background.
  - Otherwise the app keeps the arena uncalibrated and the Calibrate step (and Range's prompt) says how far it moved, for example "The projection moved about 14 px — recalibrate", offering auto-calibration or the manual box.
  - If the pattern can't be detected at all (lighting, camera covered), it says so and offers recalibration; it never silently keeps a calibration it couldn't verify.
- **Calibration view.** The Calibrate step shows the current calibration visually:
  - The camera feed with the calibrated projection rectangle drawn over it (orange outline, corner handles only while adjusting).
  - A **Show grid** toggle projects a grid on the arena (evenly spaced lines plus the arena's corners and center marked). With the rectangle drawn on the feed, the owner can see at a glance whether the projected grid still sits inside the rectangle.
  - The grid and the rectangle are only shown on Setup; Range and running drills are never affected. Turning the grid off, leaving Setup, or starting a drill restores the arena's background.

### Nothing blocks

Hard rules, because the camera or projector may be missing, off or pointed elsewhere, especially when the app is launched for testing:

1. **Nothing starts on its own at launch.** The app opens on Range with the camera feed (or the no-camera panel). It never opens the arena, shows a pattern or calibrates by itself.
2. **Calibration runs only when the owner asks for it**: Calibrate on Setup, or F6. Opening the arena no longer starts calibration (unlike the JavaFX app and Plan 5).
3. **The remembered-calibration check runs only when the owner opens the arena**, in the background, time-limited to about 3 seconds. If it can't confirm the calibration (projector off, camera pointed elsewhere, pattern not seen), it gives up quietly and marks the arena "not verified — recalibrate on Setup". No dialog, no waiting.
4. **Missing hardware is a state, never a blocker.** No camera: Setup's Camera step and Range's no-camera panel say so, and the rest of the app works. No projector: the Projector step says "no projector screen found", and camera-only use is unaffected. Range's not-ready card always has Skip, which hides it for the rest of that session.
5. **Everything that runs can be stopped.** Auto-calibration and the check both show a visible Cancel. Auto-calibration's timeout (12 s) falls back to the manual box. Cancel leaves the arena exactly as it was (Plan 5's CalibrationFlow.cancel).
6. **The UI thread never waits** on the camera, the projector, calibration or the check.

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

### Revision 2 (2026-09-27): after the hardware check

The owner ran Plan 6's hardware check on the Logitech C270 (640×480) and a 1280×720 projector at x=4480 on a three-monitor Linux machine. Setup, the training-only Range and "Nothing blocks" held up; what didn't was how calibration ends, what happens to a drill around it, the remembered calibration's check, and losing the camera. The decisions below are the owner's. This revision supersedes the conflicting parts of Revision 1 above and of §4 ("No camera, or it drops out": automatic reconnect is now in scope).

#### Calibration

1. **Stay on Setup after calibrating.** A successful calibration no longer returns to Range: the app stays on Setup, and the Calibrate step shows a clear confirmation, "Calibration complete ✓ HH:mm". The owner goes back to Range when they choose.
   - *This reverses* Revision 1's "When calibration succeeds from Setup, the app returns to Range". The owner calibrates, then usually wants to look at the result (the outline, Show grid) before training; being moved away hid that and read as if something had gone wrong.
   - The manual box still brings the owner to Setup, where the feed is; F6 still opens Setup and starts calibrating, and now stays there too.
2. **Calibration pauses the drill instead of resetting it.**
   - Starting calibration during a drill (Calibrate on Setup, or F6) pauses the drill if it isn't already paused.
   - After calibration succeeds or is cancelled, the drill stays paused, with its targets, texts and state back as they were, ready to resume from where it was. It is never reset and never restarted.
   - *This reverses* Revision 1's F6 behavior ("the drill stops before the pattern shows … a fresh drill starts"), and the flow's restart of a stopped projector exercise, in the Compose app. A recalibration mid-session is a correction, not a new session: the owner lost their rounds, score and personal-best progress every time.
   - **How, without changing the exercise API.** The v2 API has no host-level pause: Pause is the drill's own button, and F3 presses it. Calibration presses the same button (the drill's "Pause"; a drill already showing "Resume" is already paused). The owner's RandomTargetParDrill v2 jar works unchanged; no `plugin-api` change is needed.
   - While the pattern shows, the arena covers everything but its background (targets, shot markers, the drill's texts, the "Needs Calibration" label) without changing any target's own visibility, so a target the paused drill had hidden stays hidden afterwards.
   - Shot detection comes back after calibration only if the paused drill hadn't turned it off, so the paused drill sees no shots until it is resumed.
   - A drill with no Pause button can't be paused: a projector drill of that kind is still stopped for calibration and started afresh after a success, as before. A camera (feed) drill doesn't use the arena and is left alone, as before.
3. **Remembered calibration: no false "moved".**
   - *Cause.* The saved bounds and the check used the same estimator (auto-calibration's `calibrateFrame` on one early frame, extrapolated from the inner chessboard corners and rounded to whole pixels), so each measurement jitters by 5–10 px from run to run; the owner's two calibrations of an unmoved setup were 95,59,450,262 and 93,56,442,262. The 5 px tolerance was inside that noise. Separately, the arena reported full screen as soon as full screen was requested, before the window manager had resized it, so the check (and auto-calibration) could measure a pattern still drawn in the 640×480 window.
   - **Both sides measure the same way: the median of five detections.** The check takes the per-edge median of five detections. After an auto-calibration, the pattern shows once more (about three seconds, on Setup, with Cancel) and the same median is measured; that median is what is saved. If it can't be measured, the calibration's own bounds are saved, as before. A manual-box calibration is saved as the box.
   - **The tolerance scales with the pattern:** the larger of 8 camera pixels and 2% of the saved pattern's larger side (9 px for the owner's 450 px pattern).
   - **Three outcomes.** Within the tolerance: kept, as saved. More than twice the tolerance: moved ("The projection moved about N px — recalibrate"). In between, which is measurement noise or a nudge too small to matter: the calibration is kept at the fresh median, which is saved in place of the old one.
   - **The pattern is measured only on the projector.** The arena reports full screen only once its window fills the screen it is on, and the check (and the measurement) starts only once the arena's size equals the projector screen's size and a short settle delay (half a second) has passed.
   - **Time limit.** Detection costs about 400–500 ms a frame, so five detections take two to three seconds. The time limit becomes 8 seconds from when the pattern shows; the check still gives up quietly as "not verified" when it runs out.
   - **Logged.** Each detection (its rectangle, its distance from the saved bounds, how long it took) and each outcome (the median, the distance, the tolerance) is logged at INFO.
   - Shot detection stays off while any pattern shows and comes back only after the last one has gone, so a pattern that comes and goes quickly (the arena leaving and returning to full screen) can't turn detection back on under the next one.

#### The arena at launch

4. **The arena opens at launch whenever a projector screen is found.**
   - At launch the camera opens in the background, as before, and, if a projector screen is found, the arena opens on it; with Remember calibration on, the saved calibration is checked then. With no projector screen, nothing opens and Setup's Projector step says so; the step looks for a projector again every couple of seconds while the arena is closed, so plugging one in is noticed.
   - It still never blocks or waits, and it still never calibrates by itself.
   - *This reverses* rule 1 of "Nothing blocks" ("It never opens the arena"). A session always starts with the arena on the projector, and with a remembered calibration the check needs it there; opening it by hand every launch was a step with nothing to decide.

#### The camera

5. **Only real cameras are listed.** On Linux a UVC webcam also has a metadata node (the C270 is `/dev/video0` for capture and `/dev/video1` for metadata, both under the same name); picking the metadata node failed with "Cannot open the webcam". The camera list keeps only devices that can capture video, so each camera appears once. A device whose capabilities can't be read is still listed.
6. **A lost camera reconnects by itself.**
   - When the camera the app was using is unplugged, the app watches in the background for a camera of the same name to come back, and reopens it when it does. The watch never blocks, and ends once any camera is open.
   - The picker stays available meanwhile, to choose another camera; it says the app is waiting for the lost one.
   - *This reverses* §4's "Automatic reconnect stays out of scope".
7. **Losing the camera keeps the arena open.**
   - The arena stays open, and a running drill pauses (as for calibration). The arena's calibration goes (it belonged to the camera that went), so the arena shows "Needs Calibration". Setup and the status chip say "No camera".
   - When a camera comes back, through the reconnect or a pick, the saved calibration is checked if Remember calibration is on; otherwise the arena stays uncalibrated and Setup's Calibrate step is highlighted.
   - Switching cameras while the arena is open works the same way: the arena stays open, uncalibrated, and the new camera is checked against the saved calibration if Remember is on.
   - *This reverses* Plan 5's rule that closing (or losing) the camera closes the arena it calibrated. With the arena opening at launch and the drill pausing instead of resetting, closing the arena threw away exactly what the owner wanted kept.
8. **A stale camera pick must never crash the app.**
   - *The crash.* After an unplug, picking the camera on the no-camera panel threw `IndexOutOfBoundsException` from `SarxosCaptureCamera.getName`, which looked the name up in the live webcam list by index; the exception escaped a click handler and Compose Desktop closed the window, and with it the app.
   - *The cause is fixed:* a camera keeps its own name from when it was found. Picking a camera that is no longer plugged in says it is not connected (the panel, and Setup's Camera step, show "… is not connected. Plug it in, or pick another camera.") instead of trying to open it; a camera that is plugged in again under a different device number is found by name.
   - *A safety net:* an exception that escapes the Compose app's event handling or composition is logged and shown as a notice ("Something went wrong …; ShootOFF kept running"), and never closes a window or exits the app.
   - The JavaFX app keeps working with the `core` changes (the cached name and the camera list); it gains the capture-only list too.

#### Nothing blocks (revised)

Rules 2, 4, 5 and 6 stand. Rules 1 and 3 now read:

1. **Nothing calibrates on its own at launch.** The app opens on Range; the camera opens in the background, and the arena opens on the projector if one is found. No calibration starts by itself.
3. **The remembered-calibration check runs only as the arena reaches the projector** (at launch, or when the owner opens it) **or when a camera comes back to an open arena**, in the background, time-limited to 8 seconds. If it can't confirm the calibration, it gives up quietly and marks the arena "not verified — recalibrate on Setup". No dialog, no waiting.

#### Deferred minors from Plan 6

Folded into this revision, because the changes above make them matter:
- Setup's "No projector screen found" hint went stale if a projector was plugged in while Setup was showing: it is looked for again every couple of seconds while the arena is closed (decision 4).
- The check showed its pattern under the "Needs Calibration" label: the arena's cover hides the label while any pattern shows (decisions 2 and 3).
- A fast full-screen flap (under 600 ms) could let a stopped check's delayed detection restart fire while the next check's pattern showed (N1): detection comes back only once no pattern shows (decision 3).
- `calibrationSucceeded` set the destination directly instead of through `navigate()`: calibration no longer changes the destination at all (decision 1).

Left, still deferred: the Drills screen's subtitle wording; `saveSettings()` on the UI thread; the dead `CalibrationController.toggle()`; "Checking…" having no time limit while the arena is windowed (Cancel is offered); `stopCheckQuietly` clearing a "not verified" or "doesn't fit" message; a windowed (no projector) calibration with Remember on replacing a saved projector calibration; and the untested cases listed in the Plan 6 ledger.

#### Success criteria (revision 2)

Criterion 6 now reads "… → Setup: camera, projector, calibrate (✓ Calibration complete) → back to Range by the owner's choice → …", and criterion 7 now includes "and with nothing moved, relaunching never reports it as moved". Adds:

9. Recalibrating mid-drill (Calibrate on Setup, or F6) leaves the drill paused where it was; Resume carries on with its rounds and score.
10. Unplugging the camera mid-session keeps the arena open and pauses the drill; plugging it back in reopens it without a click, and with Remember on the saved calibration is checked again.
11. No camera pick, stale or otherwise, closes the app.

#### Delivery

**Plan 7** implements this revision on `compose-ui`, then the owner repeats the hardware check, including the parts of Plan 6's check that were not reached (the rest of item 9, item 10 and the JavaFX regression check).

### Revision 3 (2026-09-27): calibrate at startup instead of checking

In Plan 7's hardware re-check the remembered calibration's check worked as designed: relaunched with nothing moved, it measured the projection within 4–5 px of the saved one and kept it (success criterion 7). But Show grid was then visibly "cocked" against the orange outline, where right after a fresh auto-calibration it lines up (criterion 8 failed after a restart).

*The cause* is Plan 6's ruling 5. A remembered calibration is applied through `CalibrationFlow.applySaved`, as a manual box would be, and that restores only the rectangle. A real auto-calibration does more:
- It sets up the camera's perspective warp. After `applySaved`, `CameraManager`'s `cameraAutoCalibrated` stays false, and `AutoCalibrationManager`'s perspective matrix (`perspMat`) is gone.
- It runs the exposure step.

Saving and restoring the warp was considered and dropped. The owner's decision is option B: "just do a full calibration on startup, its pretty quick anyway". This revision supersedes the conflicting parts of Revisions 1 and 2 above.

#### Calibrating when the arena opens

1. **With the option on, the arena calibrates itself when it opens.**
   - This is a full auto-calibration, the same as pressing Calibrate: the pattern, the perspective warp and the exposure step. It replaces applying a saved rectangle and checking it.
   - It starts only once the arena is on the projector: full screen, filling the projector screen, and settled for half a second (Revision 2, decision 3's condition). Until then, Setup and the status chip say "Calibrating once the arena is on the projector…", with Cancel.
   - It applies whenever the arena opens with a camera open (at launch, or by hand on Setup), and whenever a camera opens to an open, uncalibrated arena: the reconnect, a pick, or a switch (Revision 2, decision 7).
   - With no projector screen found, nothing starts. The arena is a window there, and auto-calibration needs full screen.
2. **A bumped setup is simply recalibrated.** For auto-calibrations there is no "moved" any more: whatever the camera and the projector look like now is what is calibrated.
3. **If the pattern isn't found, it ends quietly.**
   - A calibration the owner starts (Calibrate on Setup, or F6) still falls back to the manual box after 12 seconds.
   - An automatic one never shows the box, since no one may be there to drag it. After 30 seconds it ends as if cancelled: the arena is uncalibrated, showing "Needs Calibration", and Setup and the chip say "The pattern wasn't found: not calibrated — calibrate on Setup". Calibrate then works as ever, box included.
   - *Why 30 seconds, not 12.* A camera just plugged in resets its controls to their defaults, and for the first 10–15 seconds its exposure can wash the pattern out. In Plan 7's hardware check, the check found nothing for its full 8 seconds twice: once at a launch just after the camera was plugged in, and once right after an automatic reconnect. Both times the log showed the reset ("exposure_dynamic_framerate was 1, set to 0"); a Calibrate pressed 15 seconds later found the pattern at once. Auto-calibration's exposure step can't help here: it runs only after the pattern has been found. So the automatic calibration waits the settling out, still looking every quarter second. With no one there to press Calibrate again, a longer wait costs nothing.
   - This is the owner's ruling. A box popping up by itself on a screen nobody is watching reads as the app being stuck.
4. **Everything from Revisions 1 and 2 still holds for it:**
   - **The drill.** The drill pauses, as for any calibration (decision 2). At launch no drill is running, and a projector drill can't start until the arena is calibrated.
   - **The arena.** The arena is covered while the pattern shows.
   - **Controls.** F3 and the drill's Resume do nothing while it waits or calibrates.
   - **No blocking.** It runs off the UI thread (the camera looks for the pattern on its own thread) and never blocks. Cancel is on Setup the whole time.
   - **Where the owner is.** It never moves the owner to Setup. When it succeeds, Setup's "Calibration complete ✓ HH:mm" and the chip's "✓ Calibrated HH:mm" say so, as for any calibration.
   - **The camera.** Losing the camera keeps the arena open (decision 7). With the option on, the camera coming back calibrates it again.

#### What is remembered

5. **A manual box is still remembered and checked.**
   - A manual box can't be redone without the owner, so it is saved and checked at launch as in Revision 2. The check takes the median of five detections, measures against the tolerance, and ends kept, kept at the fresh median, moved, not verified or doesn't fit.
   - A box has no perspective warp to lose, so the cocked grid doesn't apply to it.
   - Keeping the existing check is simpler than dropping manual boxes from the option. Dropping them would still need a message for a box that can't be redone, and the check is already built and passed the hardware check.
6. **What is saved:**
   - The option itself is saved in `shootoff.arena.calibration.remember`, unchanged.
   - After an auto-calibration nothing else is saved, and any saved box is forgotten: the next launch calibrates afresh.
   - After a manual box the box is saved as before, with a new key, `shootoff.arena.calibration.manual=true`, that marks it as a box.
   - A calibration Plan 7 saved has no such key. It is an auto-calibration's, so the app ignores it and calibrates, and forgets it once that succeeds.
   - The keys stay additive: the JavaFX app carries the new key through its saves, and ignores it.
7. **The checkbox says what it does:**
   - The label is "Calibrate automatically when the arena opens".
   - Under it, in small type: "A box placed by hand is reused and checked instead".
   - The setting's key and the code's names stay as they are.

#### Also from Plan 7's hardware check

8. **Cancel never leaves the arena white.**
   - *The bug.* Once, cancelling a recalibration left the projector solid white until the arena was closed and reopened.
   - *The cause.* Auto-calibration's steps set the arena's background from the camera's own thread: the steps after the pattern is found blank it, and the exposure step shows white. That call went straight to the arena. The camera can be partway through a frame when Cancel runs on the UI thread and puts the arena back, so the white landed afterwards and stayed.
   - *The fix.* A background the camera asks for reaches the arena on the UI thread, and only while the calibration that asked is still running. Cancel at any moment (looking for the pattern, the exposure step, the manual box), the arena closing, or a success leaves the arena exactly as it was.
   - This matters more now that auto-calibration runs by itself.
9. **Range's status chip only when there is something to say.** The chip (top left of Range) is hidden once the range is ready: a camera, the arena calibrated, and nothing under way. The status strip at the bottom already says so. The chip still shows, and still opens Setup, whenever something is missing or under way ("No camera", "No arena", "Calibrating…", "Calibrating once the arena is on the projector…", "The pattern wasn't found…" and so on).
10. **The drill's "Round: x/x" label over its summary is the drill's own.** RandomTargetParDrill never clears its round text. It only overlaps now because plugin v1.1's summary (hit factor and personal best) sits in the same place. The host shows and clears drill texts correctly, so the change belongs in the drill's repository and is left to the owner: clear the round text when the summary shows, and restore it on reset.
11. **Logged.** Auto-calibration's own outcome, including whether the exposure step lowered the exposure or put it back, is logged at INFO, so the hardware check can see it.

#### Removed

- **The measurement after a calibration** (Revision 2, decision 3's "the pattern shows once more … that median is what is saved"): "Measuring the calibration for next time…".
- **The held drill restart.** It kept a drill with no Pause button from restarting under that measurement (Plan 7's Task 8 review fixes). The flow restarts such a drill at once again after a success, as before Plan 7.
- **What stays in `core`.** `PatternMeasurement` stays: the manual box's check still takes its median. `CalibrationFlow.applySaved` stays for the manual box too.

#### This reverses

- **Revision 1.** "Automatic check of a remembered calibration" and "Remember calibration: On … the saved calibration is checked and applied", for auto-calibrations. They now apply only to a manual box.
- **Revision 2, decision 3.** Measuring the pattern again after an auto-calibration and saving that median. The median, the tolerance and the three outcomes stay, for the manual box's check.
- **Revision 2, decision 4.** "It still never calibrates by itself". With the option on, it now does, once the arena is on the projector.
- **Revision 2, decision 7.** "When a camera comes back … the saved calibration is checked if Remember calibration is on": it is calibrated, or a saved manual box is checked.
- **Nothing blocks, rule 1.** "Nothing calibrates on its own at launch". It now reads: *Nothing calibrates on its own at launch unless the owner turned on "Calibrate automatically when the arena opens"; then only once the arena is on the projector, never blocking, always with Cancel, and never with the manual box.*
- **Nothing blocks, rule 2.** "Calibration runs only when the owner asks for it". The owner's standing choice, the checkbox, now counts as asking.
- **Nothing blocks, rule 3.** It now covers the manual box's check only; the automatic calibration is time-limited to 30 seconds (decision 3).
- **Plan 6's ruling 5** is resolved: an auto-calibration is never restored from a saved rectangle.

#### Success criteria (revision 3)

Criterion 7 now reads: "With 'Calibrate automatically when the arena opens' on, relaunching with the camera and projector attached calibrates the arena by itself once it is on the projector, and Show grid then lines up as after a calibration by hand. Moving the camera and relaunching just calibrates again. With the projector covered, it ends quietly as not calibrated. A remembered manual box is checked as in Revision 2." Criterion 10's "with Remember on the saved calibration is checked again" now reads "with the option on the arena is calibrated again". Adds:

12. After an automatic calibration, Show grid lines up with the outline exactly as after pressing Calibrate, including right after the camera has been plugged in.
13. Cancelling a calibration at any moment leaves the arena as it was, never white.
14. With everything set up, Range shows no status chip; it comes back when something needs attention.

#### Delivery

**Plan 8** implements this revision on `compose-ui`. The owner then repeats the parts of the hardware check it touches, together with what is left of Plan 7's Task 10.

### Revision 4 (2026-09-27): hardening after the Plan 8 hardware check

Plan 8's hardware re-check passed: the automatic calibration found the pattern at launch, after a relaunch and after a replug, and the grid lined up. It also found five weaknesses, all in calibration and the camera. The owner approved fixing each of them here. Plan 8's ledger (`.superpowers/sdd/2026-09-27-compose-trial-plan-8-calibrate-at-startup/progress.md`, its "Task 6" lines) and the run log (`build/plan8-compose-run.log`) have the evidence.

1. **Cancel puts the camera back exactly as it was.**
   - *The bug.* Cancelling a recalibration made over a good automatic calibration left the grid crooked. Cancel put back only the projection's rectangle (Plan 6's ruling 8). Starting calibration had also thrown away the camera's perspective warp, the paper size that auto-calibration found, and the exposure the exposure step had set, and Cancel didn't restore them.
   - *The fix.* As calibration starts, the camera's whole calibration is saved: the projection, the perspective warp with its paper size, and the exposure. Cancel puts all of it back, and so does an automatic calibration that ends because the pattern wasn't found. The arena and the camera end exactly as they were before calibration started.
   - *Why.* A Cancel is the owner saying "leave it as it was". A good calibration must survive one.
2. **The exposure step waits for the white screen before measuring.**
   - *The bug.* The exposure step asks the arena for a white screen, then takes its baseline brightness 100 ms later. On the C270 and projector the white often hadn't reached the camera by then: the baseline was 33–92 where the white measures about 130, and one log line shows the white arriving mid-step ("mean originally 91.7 … lowest 126.7"). So the step mostly ended "Failed to adjust exposure", and the exposure wasn't lowered.
   - *The fix.* After asking for white, the step waits until the camera's frame is clearly brighter than the blank arena and has stopped getting brighter, for at most 2 seconds, and only then takes its baseline. If the frame never brightens in that time, it measures anyway, as before. The step stays in `core`, so the JavaFX app gets the same fix.
   - *Also.* Calibration's time limit no longer ends a calibration whose pattern the camera has already found. The steps after it, now a little longer, get up to 5 seconds more. Before, a pattern found in the last seconds before the limit was thrown away (a Plan 8 review finding).
3. **The frame rate stays up while the camera looks for the pattern.**
   - *The bug.* In a dark scene (the projector's lens covered, or right after a replug), the C270 dropped to 3.8–4.9 FPS ("Current webcam FPS is 4.9 … too low"). One replug's automatic calibration then ran for about 30 seconds before the pattern was found. `exposure_dynamic_framerate` was already 0: at the covered launch the camera reported an exposure of 1002 (100 ms, three frame periods) with that control off. The C270's auto exposure lengthens the exposure past the frame period anyway, and the frame rate follows.
   - *The fix.* While auto-calibration looks for the pattern, a Linux (V4L2) camera's exposure is held to at most one frame period (1/FPS). If the camera's auto exposure goes longer, it is switched to manual exposure at that limit until calibration ends; then auto exposure comes back, unless the exposure step has set its own exposure. `exposure_dynamic_framerate` is set to 0 again after every switch between manual and auto exposure, in case the camera turns it back on.
   - *Logged.* A few seconds after every camera open, and when the frame-rate warning fires, the frame rate, the exposure, the exposure mode and `exposure_dynamic_framerate` are logged at INFO. The next hardware check can then tell a dark scene from a slow start.
   - *Why this way.* Under auto exposure V4L2 won't take an exposure limit, so manual exposure is the only way to hold one. The limit applies only when the camera would go longer than a frame period. In the owner's normal light the camera sits at about one frame period already (an exposure of 336), so nothing changes there.
4. **A camera that fails to reopen right after it appears is retried.**
   - *The bug.* On one replug the reopen failed ("Cannot open the webcam … /dev/video0"): the device node appeared a moment before the camera could be opened. The reconnect tries a camera once each time it appears (Revision 2, decision 6), so it gave up until the next replug.
   - *The fix.* A camera that has just appeared is tried up to three times, about a second apart. Only the last failure is shown to the owner; the earlier ones are logged. After three failures it isn't tried again until it is unplugged and plugged in again, so a camera another program holds still never produces an error every two seconds.
5. **An unattended calibration waits 60 seconds, not 30.**
   - *Why.* A camera just plugged in resets its controls, and in Plan 7's check it took 10–15 seconds to settle. Decision 4's retries can add a couple of seconds more before the camera is even open. In this check, one replug's automatic calibration had run for about 28 seconds without finding the pattern. Decision 3 keeps the search at full speed but doesn't make the camera settle faster. A longer wait costs nothing when no one is there: the calibration ends as soon as it finds the pattern, and Cancel ends it at any time. The only cost is that with the projector covered the pattern shows for 60 seconds before the quiet end.
   - *Why one limit.* A launch just after the camera was plugged in has the same settling as a reconnect, so a separate limit for reconnects would miss half the cases.
   - This replaces Revision 3's 30 seconds (decision 3). An attended calibration still falls back to the manual box after 12 seconds.

Plan 9 also fixes three smaller things from the same check:
- the manual box's handles, which moved about a tenth as far as the mouse;
- the not-ready card's wording when the pattern wasn't found ("Calibrate — the pattern wasn't found. Press Set up to calibrate.");
- two log details: a stale "since the pattern first showed" time after a timeout, and the automatic calibration's reason when the camera merely opened after the arena.

#### Success criteria (revision 4)

Adds:

15. Cancelling a recalibration made over a good calibration leaves Show grid lined up as before.
16. On the C270, the exposure step logs "Exposure lowered", from a baseline of about the white's brightness.
17. With the projector's lens covered, the frame rate stays near the camera's 30 FPS while the pattern is looked for.
18. After a replug, the camera reopens by itself even if the first try fails, and the arena recalibrates.

#### Delivery

**Plan 9** implements this revision on `compose-ui`. The owner then repeats a short hardware check of each item.

### Revision 5 (2026-09-28): tightening after the Plan 9 hardware check

Plan 9's hardware re-check passed every item. The owner approved four small follow-ups from it; Plan 9's ledger (its "Task 7" lines) and `build/plan9-compose-run.log` have the evidence. This revision supersedes the conflicting parts of Revision 4, decision 3.

1. **The exposure is held from the moment the search starts, on a fresh reading.**
   - *The finding.* With the lens covered, the C270's auto exposure reached 20724 (about 2 s a frame), and the hold at one frame period came 6–10 s after the search started, after "Current webcam FPS … too low" had fired.
   - *The cause.* The hold was decided only at each look for the pattern, and each look read an exposure the Linux UVC driver had cached: `exposure_time_absolute` is re-read from the camera only after a control on the same unit is written, or when the camera reports a change. The looks read the stale 336 until the real value arrived.
   - *The fix.* The hold is decided as soon as auto-calibration starts looking, before the exposure step's first probe of the camera, and again at each look, as before. Each decision reads the exposure afresh: `exposure_dynamic_framerate` (on the same unit) is written back with its own value first, which makes the driver ask the camera. It stays Linux (V4L2) only, and applies only when auto exposure is longer than a frame period.
2. **Cancel can't be undone by a frame still in the exposure step.** A frame the camera's thread was still processing could lower or reset the exposure just after Cancel had put the old one back, and a pattern found at that moment could mark the camera calibrated again. The exposure step's calls on the camera and the end of auto-calibration now take turns: once auto-calibration has ended, that calibration touches the camera no more. Nothing here waits for a look for the pattern, only for a camera call already under way.
3. **Show grid says why it is off.** When the switch is disabled, a short reason sits beside it: "Not while calibrating" (including while an automatic calibration waits for the projector), "Not while checking the saved calibration", or "Stop the drill to show the grid".
4. **The log is accurate.** The "3 s after opening" line gives the frame rate measured from the frames since the camera opened, and the exposure read afresh. "Since the pattern first showed" counts from when the pattern actually showed, and an arena closed mid-calibration leaves nothing behind for the next one.

#### Success criteria (revision 5)

Criterion 17 now reads: "With the projector's lens covered, the exposure is held within about a second of the search starting, and no low frame rate warning appears." Adds:

19. With Show grid disabled, Setup says why.

#### Delivery

**Plan 10** implements this revision on `compose-ui`. The owner then repeats a short hardware check.
