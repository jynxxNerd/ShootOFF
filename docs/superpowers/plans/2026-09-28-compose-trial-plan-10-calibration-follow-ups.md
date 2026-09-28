# Compose Trial Plan 10: Calibration Follow-ups — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Carry out spec §8 Revision 5, the small follow-ups from Plan 9's hardware check:
- The exposure is held to a frame as soon as the search for the pattern starts, on a fresh reading of the camera's exposure.
- The "3 s after opening" log line gives the real frame rate and exposure.
- "Since the pattern first showed" counts from when the pattern showed, and an arena closed mid-calibration leaves no times behind.
- Show grid says why it is disabled.
- Cancel can't be undone by a frame still in the exposure step, or by a pattern found as Cancel lands.

The JavaFX app works as before and gets the `core` fixes.

**Architecture:**
- **Task 1: the hold from the start** (`core`).
  - `CameraManager.enableAutoCalibration` calls `camera.limitExposureToFramePeriod()` itself, before the exposure step's first probe of the camera. Each look still calls it too.
  - `SarxosCaptureCamera` reads the exposure through a new `freshExposure()`, which writes `exposure_dynamic_framerate` back with its own value first so the Linux UVC driver asks the camera. A hold also records the exposure it replaced as `origExposure`, so the exposure step's probe doesn't switch the camera back to auto and end the hold.
- **Task 2: the 3 s line** (`core`). `SarxosCaptureCamera` counts the frames since it opened (`describeFrameRate`), and `exposureState()` reads the exposure through `freshExposure()`.
- **Task 3: the pattern's time** (`compose-app`).
  - `CalibrationViews` gains `patternShown()`, which `CalibrationController.calibrationStarted()` calls whenever the pattern goes up.
  - `AppState`'s `arenaFilledAt` becomes `patternShownAt`, set by `patternShown()` rather than when the arena fills the projector's screen.
  - The arena closing and the camera going both forget the times (`forgetCalibrationTimes`).
- **Task 4: Show grid's reason** (`compose-app`). A pure `gridUnavailableReason(…)` in `SetupSteps.kt` gives the reason, and Setup shows it beside the switch, whose `enabled` now follows it.
- **Task 5: Cancel against a frame in flight** (`core`).
  - `AutoCalibrationManager.stop()` marks a calibration ended, under the camera's monitor. After that its exposure step and its hold make no camera call, and it reports no pattern.
  - `CameraManager.disableAutoCalibration()` calls it, under the same monitor. `autoCalibrateSuccess` checks and sets its flags under that monitor too.
  - `CalibrationFlow.arenaClosing()` clears `unattendedTimeout`.
- **Task 6: the owner's short hardware re-check.**

**Tech Stack:**
- Java 21 (`core`, `plugin-api`, `javafx-app`) and Kotlin 2.3.21 on the JVM 21 toolchain (`compose-app`).
- Gradle 8.14 wrapper (Kotlin DSL).
- Compose Multiplatform 1.12.1 (desktop, Linux x64), Material 3 1.9.0, kotlinx.coroutines (from Compose).
- OpenCV 4 through bytedeco's `opencv_java`; V4L2 controls through JNA (`V4l2Controls`).
- JUnit 5 for unit tests (JUnit 4 where a file already uses it); Compose UI tests on JUnit 4 (`v2.createComposeRule`) through the vintage engine.

**Spec:** `docs/superpowers/specs/2026-09-26-compose-trial-design.md`. This plan implements §8 "Revision 5 (2026-09-28): tightening after the Plan 9 hardware check" (commit `cf85576d`). Where the spec and this plan disagree, the spec wins.

The evidence behind each decision is in two places:
- Plan 9's ledger (`.superpowers/sdd/2026-09-27-compose-trial-plan-9-calibration-hardening/progress.md`, gitignored): its "Task 7" lines, the "Final:" minors, and the follow-ups line at its end. The Plan 8 and Plan 7 ledgers beside it hold the older deferred minors.
- The run log, `build/plan9-compose-run.log`.

Plan 9 is done and pushed (`0edd2102`): the gate stands at **803**.

**Prototype.**
- The whole plan was built in a scratch clone of `compose-ui` at the spec commit, one commit per task.
- Every task compiled, and the gate ran green at each task's commit, with the counts given below, ending at **817/817**.
- The code blocks below are the prototype's edits, extracted from those commits by a script. The script checked two things:
  - each "Replace … with …" pair matches exactly once in the file, at the point it is applied;
  - applying the pairs in order gives the task's commit byte for byte.
- Each task's failing-test step was checked against the commit before it.
- The GUI apps were not run. Task 6 is the owner's.

**What only the hardware can confirm.** V4L2, the UVC driver and the C270 can't be unit-tested. The tests pin the logic with fake cameras, and Task 6 checks the rest:
- that writing `exposure_dynamic_framerate` back with its own value makes the next exposure read come from the camera (Task 1). The driver's source says so (below); the C270 hasn't been asked;
- that the hold now comes within about a second of the search starting, with the lens covered (Task 1);
- that the 3 s line's frame rate and exposure look right, covered and uncovered (Task 2);
- that Show grid's reason reads well on Setup during a drill (Task 4).

## Plan-author rulings

Where Revision 5 leaves room, the plan decides as follows. Each ruling gives the decision, the reason, and the cost if it is wrong. Line numbers are at `cf85576d`.

### Root causes

**Item 1: the hold came 6–10 s after the search started.** Two causes, the second the larger.
- *Where the hold was decided.* Only inside each look for the pattern: `AutoCalibrationManager.java:235`, in `StepFindBounds.process`. With frames about 2 s apart, a look can wait up to 2 s for its frame. That accounts for a second or two, not six to ten.
- *What each look read.* `SarxosCaptureCamera.java:457` read the exposure with `camera.get(Videoio.CAP_PROP_EXPOSURE)`, and so did the 3 s line (`exposureState`). On Linux that is `VIDIOC_G_CTRL` on `exposure_time_absolute`, which the UVC driver answers from a cache (`drivers/media/usb/uvc/uvc_ctrl.c`):
  - `__uvc_ctrl_load_cur` returns at once `if (ctrl->loaded)`, without asking the camera.
  - The cache is dropped only in `uvc_ctrl_commit_entity`, for every auto-update control of the unit a control is written on (`exposure_time_absolute` has `UVC_CTRL_FLAG_AUTO_UPDATE`), or when the camera sends a status event (`uvc_ctrl_status_event`).
- *The run log shows it.* Both covered launches (00:48:29 and 00:50:50) read 336 at the 3 s line, a second after the exposure step's probe had read 1002 ("Initial camera exposure 1002.0") and switched the camera to manual and back. The FPS warning then fired at 4.8 FPS, and the hold came only when a reading of 20724 finally arrived. So the looks between were reading a stale 336, under the 366 that triggers the hold.
- *Nothing wrote to the unit in between.* `disableDynamicFramerate` writes only when the control isn't already 0 (`SarxosCaptureCamera.java:221`), and it was 0.

**Item 2: the 3 s line said "30.0 FPS, exposure 336 (auto)".**
- *The frame rate.* The line printed `getFPS()` (`SarxosCaptureCamera.java:180`). That is `CalculatedFPSCamera.webcamFPS`, which starts at `DEFAULT_FPS`, 30 (`CalculatedFPSCamera.java:24-25`). Its first estimate needs two samples five frames apart (`SarxosCaptureCamera.java:374`, `CalculatedFPSCamera.java:89`): ten frames. At about 2 s a frame, the covered camera hadn't given ten frames by 3 s, so the line printed the starting 30.0.
- *Not the CALIBRATING skip.* The brief's suspect was `cameraState != CameraState.CALIBRATING` at `:374`. But `CameraManager.setCalibrating(true)` sets CALIBRATING (`CameraManager.java:343`) and then calls `setDetecting(false)` (`:344`), which sets NORMAL at once (`:335`). The estimate does run while calibrating: the replug lines at 00:46:55 and 00:47:22 give 29.6 and 29.0 FPS mid-calibration. The skip is dead in practice and is left alone.
- *The exposure.* It is item 1's stale cache.

**Item 3: "7261 ms since the pattern first showed" against "2217 ms since it started".**
- The time came from `arenaFilledAt`, set when the arena filled the projector's screen (`AppState.kt:813`), not when the pattern showed.
- The calibration start logged "Calibrating automatically" and set `calibrationStartedAt` in the watch's `start` (`:766`), after `delay(patternSettleMillis)` (500 ms, `:814`) and a hop to the UI thread.
- The ledger's reading was a carry-over across the close and reopen. It fits the 00:33:21 case, but not the next one: the success at 00:33:24 cleared `arenaFilledAt` (`:1018`), yet at 00:33:39 the log again said "2548 ms since it started, 7949 ms since the pattern first showed". So `arenaFilledAt` was set by the new arena's watch about 5.4 s before its calibration started. What held the start back those 5 s is not in the log; the UI thread, busy with the newly opened window, is the likeliest.
- A true carry-over also exists: `closeArena` (`:951`) and the camera going (`detachCamera`, `:681`) end a calibration through `arenaClosing`, which reports no cancel, so its times were never cleared.

**Item 5: Cancel against a frame still being processed** (Plan 9's final review, minor 1).
- *The exposure.* Cancel runs `flow.cancel()` (`CalibrationController.kt:180`), which calls `camera.disableAutoCalibration()` (`CalibrationFlow.java:348`) and so clears `isAutoCalibrating` (`CameraManager.java:805`). Then it calls `restoreCalibration` (`CalibrationController.kt:192` to `CameraManager.java:826-836`).
- But the camera's thread checked `isAutoCalibrating` only once per frame, at `CameraManager.java:627`, before `acm.processFrame` (`:631`). A frame already past that check went on into `StepAdjustExposure.process`. It called `camera.decreaseExposure()` (`AutoCalibrationManager.java:482`) or `camera.resetExposure()` (`:499`), possibly after the restore, and so shifted the exposure put back.
- *The success.* `autoCalibrateSuccess` checked `isAutoCalibrating` (`CameraManager.java:778`), cleared it (`:779`), and only then set `cameraAutoCalibrated = true` (`:785`). A restore landing between the check and the set had its `cameraAutoCalibrated` overwritten.
- *The same gap one level up.* `AutoCalibrationManager.processFrame` reports a finished calibration to the manager (`:183`) whether or not that calibration has ended. After a Cancel then a Calibrate within one look (400–500 ms), the cancelled calibration's last look could report its pattern as the new calibration's, since `isAutoCalibrating` is true again.

### Decisions

1. **The hold is decided as the search starts, and again at each look** (spec decision 1).
   - `CameraManager.enableAutoCalibration` calls `camera.limitExposureToFramePeriod()` after it creates the new `AutoCalibrationManager` and before `fireAutoCalibration()`.
   - *Why before the probe.* `fireAutoCalibration()` resets the steps, and `StepAdjustExposure.enabled()` then probes the camera, the first time only (`supportsExposureAdjustment`: manual, lower, back to auto). The probe's switch back to auto is what left the cached 336 behind. Reading before it gets the camera's own value; the probe read 1002 at exactly that moment in both covered launches.
   - *The probe after a hold.* The probe would switch the camera back to auto, and so end the hold it found. So a hold records the exposure it replaced as `origExposure`. That makes the probe return at once (`if (origExposure.isPresent()) return true`), rightly: a camera that took a manual exposure can be adjusted. It also folds Plan 9's Task 3 minor: the probe recorded 333 as the original exposure when a look's hold came first.
   - The per-look call stays. The exposure can still grow after the search starts, for example when a camera just plugged in settles.
   - It stays Linux (V4L2) only, for an open camera, only while auto exposure is on, and only over 1.1 frame periods. All of that is `limitExposureToFramePeriod`'s existing checks.

   *Cost:* one more V4L2 read per calibration start, on the UI thread, as the probe already does.
2. **The exposure is read fresh.**
   - `freshExposure()` writes `exposure_dynamic_framerate` back with the value it has (it is 0 on the owner's C270), and then reads the exposure. `exposure_dynamic_framerate` and `exposure_time_absolute` are both controls of the camera terminal, so the write drops the cached exposure (root cause, item 1). `uvc_ctrl_set` marks a control dirty even when its value is unchanged, so the write reaches `uvc_ctrl_commit_entity`.
   - Used by the hold and by `exposureState()` (the 3 s line and the FPS warning's line).
   - A camera without the control (no `exposure_dynamic_framerate`) reads the exposure as before.
   - *Why not a direct write of the exposure itself.* Under auto exposure V4L2 won't take one.

   *Cost:* two more ioctls per reading. If the C270 turns out to ignore a write of an unchanged value, Task 6 shows the hold still late; see "For the owner to decide".
3. **A hold the camera refuses is undone.** If `camera.set(CAP_PROP_EXPOSURE, …)` fails after the switch to manual, auto exposure comes back and a WARN says so. This is Plan 9's Task 3 minor, "camera.set failure ignored": a camera left in manual at its old exposure would be worse than no hold. *Cost:* none known.
4. **The 3 s line counts frames** (spec decision 4). It gives "N FPS measured (M frames in T ms)" from the frames since `open()` over the time since, not `getFPS()`. The first second or so of a fresh open is in the count, so it reads a little low in normal light (about 27 to 30). *Cost:* none known.
5. **The pattern's time is taken when the pattern shows.**
   - `CalibrationController.calibrationStarted()`, which `CalibrationFlow.startAutoCalibration` calls as it puts the pattern up, now tells `CalibrationViews.patternShown()`. `AppState` keeps the first such time as `patternShownAt`.
   - A calibration that leaves full screen and comes back keeps its first time: "first showed".
   - The watch no longer notes when the arena filled the screen.
   - A calibration the owner starts now logs its own pattern time too. Plan 8's review had dropped it after a timeout, because it was stale; it is now fresh by construction. `TestCalibrationLogging`'s test for that case changes accordingly.
   - The 5 s gap between the arena filling the screen and the start is left unexplained (root cause, item 3). The log no longer hides it inside "since the pattern first showed", but doesn't show it either.

   *Cost:* none known.
6. **An arena closed, or a camera lost, mid-calibration forgets both times** (`forgetCalibrationTimes()`, in `closeArena` and `detachCamera`), as a Cancel, a success and a not-found already do. *Cost:* none.
7. **Show grid's reason** (spec decision 3).
   - `gridUnavailableReason(arenaOpen, calibrating, check, projectorDrill)` returns, in this order:
     - "Not while calibrating", while calibrating or while an automatic calibration waits for the projector (`CheckState.WaitingToCalibrate`);
     - "Not while checking the saved calibration", during the manual box's check (`CheckState.Checking`);
     - "Stop the drill to show the grid", while a projector drill runs.
   - It returns null when the grid can be turned on, and when there is no arena: Setup's Projector step already says so.
   - Calibrating comes before the drill, because calibration pauses the drill and the drill still counts as running.
   - The switch's `enabled` is `arena != null && reason == null`, the same condition as before, now in one place.
   - The reason sits in the switch's row, after "Show grid", in the muted 12 sp text Setup uses for hints. Plan 6's line under the row, "Stop the drill to show the grid", which the owner didn't notice, goes.

   *Cost:* the words are the owner's to change.
8. **Cancel against a frame in flight: the camera's monitor, not a pending restore** (spec decision 2).
   - The chosen fix serialises the exposure step's camera calls with the end of auto-calibration, under the camera object's own monitor. `SarxosCaptureCamera`'s exposure methods are already `synchronized` on it.
     - `AutoCalibrationManager` gains a `stopped` flag, set by `stop()` under the monitor.
     - The exposure step's calls (`decreaseExposure`, `resetExposure`) and the look's `limitExposureToFramePeriod` run under the monitor, and only while `!stopped`.
     - `CameraManager.disableAutoCalibration()` clears `isAutoCalibrating`, stops the manager and releases the hold, all under the monitor. Every end of calibration goes through it before any restore.
     - `autoCalibrateSuccess` checks and clears `isAutoCalibrating` and sets `cameraAutoCalibrated` under the monitor. A Cancel either ends auto-calibration first, and the success does nothing, or comes after it, and its restore overwrites it.
     - `processFrame` reports a finished calibration only if it isn't stopped. That closes the Cancel-then-Calibrate case too.
   - *Why not the reviewer's pending restore* (the restore applied on the camera's thread once the in-flight frame ends):
     - It would hold the restore back for up to a look, 400–500 ms. A Calibrate pressed in that window would save the un-restored state as "before", and the late restore would then land in the middle of the new calibration, swapping its manager for the old one. Guarding against that needs more state than the problem.
     - Anything on the UI thread reading the camera just after Cancel would see the cancelled calibration's state.
     - The pending restore alone doesn't stop a cancelled calibration's late success, which the stop flag also covers.
   - *What the UI thread waits for.* Only a camera call already under way (a few V4L2 ioctls), never a look for the pattern: the monitor is held only around those calls. `disableAutoCalibration` already took the same monitor through `releaseExposureLimit()`, so the UI thread waits no more often than before.
   - Plan 9's re-check after `acm.processFrame` (`if (!isAutoCalibrating.get()) camera.releaseExposureLimit()`) stays, as the brief asks. It is now belt and braces: a stopped look can't take the hold.
   - *Deadlock.* The new code takes no other lock while it holds the monitor: the exposure calls inside it reach only V4L2 and the log, and `autoCalibrateSuccess` calls the listener after releasing it.

   *Cost:* a Cancel can wait a few milliseconds for a V4L2 call to finish.
9. **Deferred minors considered** (the Plan 9, 8 and 7 ledgers):
   - Folded in:
     - Plan 9, Task 3: the probe recording 333 as the original exposure (ruling 1); "camera.set failure ignored", for the hold (ruling 3); "3 s log FPS stale while CALIBRATING" (Task 2).
     - Plan 9, Task 2: `Core.mean` computed twice on the settling frame (Task 5, which edits the same lines).
     - Plan 9's final review: minor 1, both halves (Task 5).
     - Plan 8, Task 1: `CalibrationFlow.arenaClosing()` doesn't reset `unattendedTimeout` (Task 5).
     - Plan 8, Task 2: the unwrapped 192-character KDoc line in `RememberedCalibration.kt` (Task 4, the other `compose-app` UI task).
     - Plan 8's final review: the generation guard can't reject a request after a sub-second Cancel then Calibrate. The camera's half is folded (a stopped calibration reports nothing, Task 5); the UI's half already holds through the controller's `generation` and `flow.isCalibrating`.
     - Plan 8's final review residual: the stale "since the pattern first showed" time is now fresh by construction (Task 3).
     - Found while prototyping: `TestCalibrationLogging.messages()` read logback's `ListAppender` list while other threads appended to it, and failed once with `ConcurrentModificationException` in `theLostCameraReopeningIsLoggedAsComingBack`. It now reads under the appender's own lock (Task 3).
   - Left, because this plan doesn't touch their code, or has no evidence they matter:
     - Plan 9, Task 1: `cameraBefore` kept until the next `beginSession`; the arena closing mid-calibration doesn't restore the warp or the exposure; the exposure restore is Linux-only; `saveCalibration` reads `projectionBounds` without the lock; no hardware test for `manualExposure`.
     - Plan 9, Task 2: "settled" on a mid-rise plateau; `blankMean` taken before the blank arrives; `patternFound` reading a non-volatile field; the test gaps.
     - Plan 9, Task 3: an interrupted exposure step starting from 333; V4L2 calls on the UI thread; no `SarxosCaptureCamera` state tests.
     - Plan 9, Task 4: the reconnect's minors.
     - Plan 9's final review: the not-found text without "— calibrate on Setup". The owner passed the wording at Task 7.
     - Plan 9's declined-to-judge list, which is hardware or pre-existing.
     - Plan 8: turning the option off doesn't stop a running unattended calibration; `attend()`'s microsecond window; leaving full screen mid-unattended re-arms; `CalibrationFixture` ignores `byCamera`.
     - Plan 7: the WARN every 2 s for a persistent listing failure; "Waiting for <raw name>"; `ArenaModel.setTargetsVisible` dead outside tests; the drill's buzzer after its last round.
10. **Existing tests that change**, each in the task that changes the behaviour:
    - Task 1: `TestCameraManagerCalibration.ExposureCamera` records the probe. `theExposureLimitEndsWhenAutoCalibrationStops` expects the hold taken at the start, then the probe. `aLimitTakenAsDisableRunsInTheMiddleOfTheFrameIsStillReleased` clears the record after enabling.
    - Task 3: `TestCalibrationLogging.aCalibrationTheOwnerStartsAfterAnAutomaticOneTimedOutLogsNoStalePatternTime` becomes `…LogsItsOwnPatternTime`, and `messages()` reads under the appender's lock.
    - Task 5: `TestStepAdjustExposure` keeps its manager in a field. `TestCameraManagerCalibration.ExposureCamera` can be made adjustable, and runs a hook while lowering.

    *Cost:* none. The baseline's Java 8 tests are all unchanged.

**Changes to JavaFX behavior** (it shares `core`), all deliberate:
- On Linux the hold is decided as the search starts, on a fresh reading (rulings 1–3).
- The 3 s line's frame rate is counted (ruling 4).
- A calibration's end waits for an exposure call under way, and the ended calibration touches the camera no more (ruling 8). The JavaFX app has no Cancel, but it ends calibrations the same way.

Its saved keys and its UI are unchanged.

**For the owner to decide** (none blocks the plan):
- **The fresh reading** (ruling 2). It rests on the UVC driver's source, not on a test with the C270. If Task 6 item 1 still shows the hold late, the fallback is to hold the exposure to a frame whenever auto exposure is on while the pattern is looked for. That drops "only when auto exposure exceeds the limit", which you asked to keep, so it is your call.
- **The 5 s after a reopen** (root cause, item 3). The arena filled the projector's screen about 5 s before its automatic calibration started, twice. It costs 5 s per reopen. Chasing it would start with an INFO line when the arena fills the screen. Say if you want that.
- **The grid's words** (ruling 7): "Not while calibrating", "Not while checking the saved calibration", "Stop the drill to show the grid".

## Global Constraints

- **Repository:** `/home/bfears/projects/ShootOFF`, branch `compose-ui`. Never switch branches, never merge to `master`, never push.
- **Commits:** no `Co-Authored-By` or any other trailer in commit messages. Verify after every commit with `git log -1 --format=%B`: the message alone.
- **Staging:** never stage `shootoff.properties` (it has the owner's local edits). Stage files by path. Never use `git add -A` or `git add .`.
- **Code style:**
  - Java: tabs; match the surrounding code. A new main-source Java file starts with the GPL header from lines 1–17 of `core/src/main/java/com/shootoff/camera/CameraManager.java` (`head -17` of that file), then a blank line.
  - Kotlin: the official style (4 spaces, trailing commas). A new main-source `.kt` file starts with the same 17 lines, then a blank line.
  - Test files have no header.
  - Keep each file's imports sorted as the file already sorts them.
  - This plan creates no files: every task modifies existing main and test files.
- **Packages:** `com.shootoff.*`.
- **Dependencies:** none added anywhere. `compose-app` still depends on `core`, `plugin-api` and Plan 5's catalog entries only; no OpenJFX in `compose-app` (`TestNoJavaFxInComposeApp` keeps passing).
- **Formats:**
  - `.target`, `.course`, the session formats, `shootoff.xml` descriptors and `shootoff.properties` are unchanged.
  - Plan 6's `shootoff.arena.calibration.*` keys and Plan 8's `…manual` key are reused as they are. No key is added.
- **The exercise API is unchanged.** `plugin-api` has no edit in this plan.
- **Tests and the owner's settings:**
  - Tests use `ScratchConfig` and never write the owner's `shootoff.properties`: every `Settings` a test builds is on `ScratchConfig.emptyFile()` (or `new Settings(new String[0])` in `core`, as its tests already do). No test reads or writes the working tree's `shootoff.properties`.
  - `compose-app` tests never read a saved `Settings` back (Plan 6's ruling 16); they read the file as `Properties`.
  - `UiPrefs` in tests is `PrefsStore.Memory`.
- **The owner's files.** Never modify, move, delete or stage:
  - `RandomTargetParDrill-bests.properties` at the ShootOFF root
  - anything under `exercises/` (the owner's `RandomTargetParDrill.jar` and `RandomTargetParDrill-v2.jar`)
  - anything under `exercise-data/`
  - any file the owner added under `targets/` or `courses/`
  - the owner's Java preferences
  - the drill's own repository (`/home/bfears/projects/RandomTargetParDrill`)

  No test runs the drill. Their checksums are in `build/plan8-owner-files.sha256` (`sha256sum -c build/plan8-owner-files.sha256`; `shootoff.properties`, which both apps save to, may differ).
- **The JavaFX app behaves as before**, apart from the shared `core` fixes listed under "Changes to JavaFX behavior". `./gradlew run` starts only the JavaFX app; `./gradlew :compose-app:run` starts the Compose app.
- **Publishing:** only to `build/m2` (`-Dmaven.repo.local=/home/bfears/projects/ShootOFF/build/m2`), never to the owner's `~/.m2`. No task here publishes.
- In verification steps use `command grep`: the interactive `grep` may be a ugrep wrapper with different options.
- Implementers never run the GUI apps; Task 6 is the owner's.
- **Test gate** (unchanged from Plans 1–9; run it in ShootOFF with `JAVA_HOME=/home/bfears/.jdks/corretto-21.0.10` if the shell doesn't already point at a Java 21):

  ```
  mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
  ```

  - It must print `0 regressions; 0 new failures`. Use a Bash timeout of 600000 ms.
  - The `N/M passing` count starts at **803** (end of Plan 9). Each task gives the count it ends at. A lower count means tests silently stopped running.
  - `core`'s `TestMalfunctionsProcessor.testManyMalfunctions` is probabilistic (a baseline test this plan doesn't touch). If it alone fails, run the gate again.
  - The Compose UI tests need a display, as the JavaFX tests do. The gate runs them on this machine's `DISPLAY=:0`.
  - The gate leaves `shootoff.properties` unchanged.

## Review Focus

1. **Cancel pressed while the camera's thread is lowering the exposure.** The exposure step's frames come every 100 ms for up to six tries, so a Cancel in the second after the pattern is found usually lands during one. Expected: the exposure Cancel puts back is the one from before calibration, never lowered or reset after it.
   - *Tests:* `TestCameraManagerCalibration.anExposureStepCallUnderWayAtCancelEndsBeforeTheExposureIsPutBack` and `TestStepAdjustExposure.theStepOfAStoppedCalibrationLeavesTheExposureAlone` (Task 5).
2. **Cancel, then Calibrate at once**, within one look (400–500 ms). Expected: the new calibration finds its own pattern; what the cancelled one's last look finds is dropped, and nothing marks the camera calibrated as Cancel lands.
   - *Tests:* `TestCameraManagerCalibration.aStoppedCalibrationReportsNothingItFindsLater` and `aPatternFoundAsCancelLandsWaitsForCancelAndThenChangesNothing` (Task 5).
3. **The camera already exposing long when the search starts** (the lens covered at launch, or a replug into a dark room). Expected: the hold comes before any frame is looked at, and before the exposure step's probe can undo it.
   - *Tests:* `TestCameraManagerCalibration.theExposureIsHeldAsSoonAsAutoCalibrationStartsBeforeAnyFrame` and `theExposureIsHeldBeforeTheExposureStepProbesTheCamera` (Task 1); Task 6 item 1 on the C270.
4. **The arena closed, or the camera lost, mid-calibration, then a new calibration.** Expected: the new calibration's log times are its own.
   - *Tests:* `TestCalibrationLogging.anArenaClosedMidCalibrationLeavesNoTimesForTheNextOne` (Task 3). The camera's loss goes through the same `forgetCalibrationTimes()`.
5. **A projector drill paused by a calibration.** Expected: Show grid says "Not while calibrating" while it calibrates, "Stop the drill to show the grid" after, and nothing once the grid can be turned on.
   - *Tests:* `TestSetupSteps.showGridSaysWhyItIsOff` and `TestSetupScreen.showGridHasNoReasonWhenItCanBeTurnedOn` (Task 4).

The owner's check (Task 6) covers what no unit test reaches: the UVC driver's cache on the C270, the real frame rate with the lens covered, and the reason's place on the real Setup screen.

## Module and file map

| Where | What | Task |
|---|---|---|
| `core/…/camera/CameraManager.java`, `core/…/camera/cameratypes/SarxosCaptureCamera.java` | the hold from the start of the search, on a fresh reading | 1 |
| `core/…/camera/cameratypes/SarxosCaptureCamera.java` | the 3 s line's counted frame rate and fresh exposure | 2 |
| `…/compose/app/AppState.kt`, `…/compose/calibration/CalibrationController.kt` | the pattern's time; times forgotten when the arena closes or the camera goes | 3 |
| `…/compose/app/{SetupSteps,SetupScreen}.kt`, `…/compose/calibration/RememberedCalibration.kt` | Show grid's reason; a KDoc line wrapped | 4 |
| `core/…/camera/autocalibration/AutoCalibrationManager.java`, `core/…/camera/CameraManager.java`, `core/…/calibration/CalibrationFlow.java` | Cancel against a frame in flight; `arenaClosing` ends unattendedness | 5 |
| none | the owner's re-check | 6 |

`…/compose/` is `compose-app/src/main/kotlin/com/shootoff/compose/`; tests mirror it under `compose-app/src/test/kotlin/com/shootoff/compose/`. `core/…/` is `core/src/main/java/com/shootoff/`, with tests under `core/src/test/java/com/shootoff/`.

**New tests and the gate after each task:**

| Task | Test methods added | Gate |
|---|---|---|
| 1 | `core` `camera/TestCameraManagerCalibration` (+2) | 805 |
| 2 | `core` `camera/cameratypes/TestSarxosCaptureCamera` (+2) | 807 |
| 3 | `app/TestCalibrationLogging` (+2; one renamed) | 809 |
| 4 | `app/TestSetupSteps` (+1), `app/TestSetupScreen` (+2) | 812 |
| 5 | `core` `camera/TestCameraManagerCalibration` (+3), `camera/autocalibration/TestStepAdjustExposure` (+1), `calibration/TestCalibrationFlow` (+1) | 817 |

---

### Task 1: The exposure held as soon as the search starts, on a fresh reading

**Files:**
- Modify: `core/src/main/java/com/shootoff/camera/CameraManager.java` (`enableAutoCalibration`)
- Modify: `core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java` (`limitExposureToFramePeriod`; new `freshExposure`)
- Test: `core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`

**Interfaces:**
- Consumes:
  - Plan 9's `Camera.limitExposureToFramePeriod()`, `SarxosCaptureCamera.switchToManualExposure()`, `resetExposure()` and its `origExposure`, `exposureLimited` and `manualExposureActive` fields.
  - `V4l2Controls.getControl` and `setControl`, and `V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE`.
  - `TestCameraManagerCalibration.ExposureCamera` (its `exposure` record and `onLimit` hook).
- Produces:
  - `CameraManager.enableAutoCalibration` calls `camera.limitExposureToFramePeriod()` before `fireAutoCalibration()`.
  - `private synchronized double SarxosCaptureCamera.freshExposure()`, which Task 2's `exposureState()` uses.
  - `TestCameraManagerCalibration.ExposureCamera.supportsExposureAdjustment()` records `"probe"` and returns `false`. Task 5 makes the return value a field.

**The cause** is set out under "Root causes, item 1" above. The tests pin the order: the hold is taken as auto-calibration starts, before any frame, and before the exposure step's probe. The fresh reading itself is V4L2 and is checked on the hardware (Task 6 item 1).

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`:

Replace:

```java
		public void releaseExposureLimit() {
			exposure.add("limit released");
		}
	}
```

with:

```java
		public void releaseExposureLimit() {
			exposure.add("limit released");
		}

		@Override
		public boolean supportsExposureAdjustment() {
			exposure.add("probe");
			return false;
		}
	}
```

Replace:

```java
		manager.disableAutoCalibration();

		assertEquals(List.of("limit released"), camera.exposure);
	}

```

with:

```java
		manager.disableAutoCalibration();

		assertEquals(List.of("limit taken", "probe", "limit released"), camera.exposure);
	}

	// Plan 9's hardware check: with the lens covered, frames came about 2 s apart and the hold came 6-10 s into the
	// search. It is taken as the search starts, before any frame (spec §8 Revision 5, decision 1).
	@Test
	void theExposureIsHeldAsSoonAsAutoCalibrationStartsBeforeAnyFrame() {
		manager.enableAutoCalibration(false);

		assertTrue(camera.exposure.contains("limit taken"), camera.exposure.toString());
	}

	// The exposure step's probe switches the exposure to manual and back to auto, after which the driver's reading
	// can lag the camera's by seconds: the hold reads the exposure first
	@Test
	void theExposureIsHeldBeforeTheExposureStepProbesTheCamera() {
		manager.enableAutoCalibration(false);

		assertEquals(List.of("limit taken", "probe"), camera.exposure);
	}

```

Replace:

```java
	void aLimitTakenAsDisableRunsInTheMiddleOfTheFrameIsStillReleased() throws IOException {
		manager.enableAutoCalibration(false);
		camera.onLimit = manager::disableAutoCalibration;

```

with:

```java
	void aLimitTakenAsDisableRunsInTheMiddleOfTheFrameIsStillReleased() throws IOException {
		manager.enableAutoCalibration(false);
		camera.exposure.clear();
		camera.onLimit = manager::disableAutoCalibration;

```


- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.camera.TestCameraManagerCalibration --console=plain`

Expected: FAIL, 3 of 11: `theExposureIsHeldAsSoonAsAutoCalibrationStartsBeforeAnyFrame`, `theExposureIsHeldBeforeTheExposureStepProbesTheCamera` (`expected: <[limit taken, probe]> but was: <[probe]>`) and `theExposureLimitEndsWhenAutoCalibrationStops`.

- [ ] **Step 3: Implement**

`core/src/main/java/com/shootoff/camera/CameraManager.java`:

Replace:

```java
		cameraAutoCalibrated = false;

		fireAutoCalibration();
	}
```

with:

```java
		cameraAutoCalibrated = false;

		// Held from the start of the search, not from its first look, which in a dark scene waits for a frame up to
		// 2 s away (spec §8 Revision 5, decision 1). And before the exposure step's probe (fireAutoCalibration),
		// which switches the exposure to manual and back
		camera.limitExposureToFramePeriod();

		fireAutoCalibration();
	}
```

`core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java`:

Replace:

```java
		if (!SystemInfo.isLinux() || !isOpen() || manualExposureActive) return false;

		final double exposure = camera.get(Videoio.CAP_PROP_EXPOSURE);
		final OptionalDouble limit = exposureLimit(exposure, camera.get(Videoio.CAP_PROP_FPS));
		if (limit.isEmpty() || !switchToManualExposure()) return false;

		camera.set(Videoio.CAP_PROP_EXPOSURE, limit.getAsDouble());
		exposureLimited = true;

		logger.info("{} auto exposure was {}, longer than a frame: held at {} by hand while looking for the pattern",
```

with:

```java
		if (!SystemInfo.isLinux() || !isOpen() || manualExposureActive) return false;

		final double exposure = freshExposure();
		final OptionalDouble limit = exposureLimit(exposure, camera.get(Videoio.CAP_PROP_FPS));
		if (limit.isEmpty() || !switchToManualExposure()) return false;

		if (!camera.set(Videoio.CAP_PROP_EXPOSURE, limit.getAsDouble())) {
			logger.warn("{} wouldn't take a manual exposure of {}; auto exposure again", getName(), limit.getAsDouble());
			resetExposure();
			return false;
		}
		exposureLimited = true;
		// The camera took a manual exposure, so it can be adjusted: the exposure step's probe (supportsExposureAdjustment),
		// which would switch the exposure back to auto and so end the hold, isn't needed
		if (origExposure.isEmpty()) origExposure = Optional.of(exposure);

		logger.info("{} auto exposure was {}, longer than a frame: held at {} by hand while looking for the pattern",
```

Replace:

```java
				getName(), exposure, limit.getAsDouble());

		return true;
	}

	@Override
```

with:

```java
				getName(), exposure, limit.getAsDouble());

		return true;
	}

	// The exposure as the camera has it now. The Linux UVC driver caches exposure_time_absolute and asks the camera
	// again only after a control on the same unit is written, or when the camera reports a change: the C270's auto
	// exposure read 336 for seconds while it was really 20724 (spec §8 Revision 5, decision 1). Writing
	// exposure_dynamic_framerate, on the same unit, back with its own value makes the next read ask the camera.
	private synchronized double freshExposure() {
		if (SystemInfo.isLinux()) {
			final OptionalInt dynamicFramerate = V4l2Controls.getControl(device(),
					V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE);
			if (dynamicFramerate.isPresent())
				V4l2Controls.setControl(device(), V4l2Controls.EXPOSURE_DYNAMIC_FRAMERATE, dynamicFramerate.getAsInt());
		}

		return camera.get(Videoio.CAP_PROP_EXPOSURE);
	}

	@Override
```


- [ ] **Step 4: Run the tests**

Run the Step 2 command, adding `--tests 'com.shootoff.camera.cameratypes.*'`. Expected: PASS.

- [ ] **Step 5: The gate**

Expected: `805/805 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/camera/CameraManager.java core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java
git commit -m "Hold the exposure to a frame as soon as the search starts, on a fresh reading"
git log -1 --format=%B
```

---

### Task 2: The 3 s line counts its frames and reads the exposure fresh

**Files:**
- Modify: `core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java` (new `openedAt`, `framesAtOpen` and `describeFrameRate`; `open`, `logCaptureState` and `exposureState`; imports)
- Test: `core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java` (JUnit 4, as the file is)

**Interfaces:**
- Consumes: Task 1's `freshExposure()`; `CalculatedFPSCamera.getFrameCount()`.
- Produces: `static String SarxosCaptureCamera.describeFrameRate(int frames, long millis)`, package-private, giving for example `"30.0 FPS measured (90 frames in 3000 ms)"`. Nothing downstream consumes it.

**The cause** is set out under "Root causes, item 2" above. The log line becomes, for example: "UVC Camera (046d:0825) /dev/video0 3 s after opening: 0.7 FPS measured (2 frames in 3004 ms), exposure 20724 (auto, mode 3), exposure_dynamic_framerate 0". With Task 1's hold in place, a covered launch should instead read about 30 FPS and "exposure 333 (manual)".

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java`:

Replace:

```java
				SarxosCaptureCamera.describeExposure(333, 1, OptionalInt.empty()));
	}
}
```

with:

```java
				SarxosCaptureCamera.describeExposure(333, 1, OptionalInt.empty()));
	}

	// Plan 9's hardware check: the line said 30.0 FPS, the estimate's starting value, while the covered C270 gave a
	// frame every 2 s. It counts the frames since the camera opened instead (spec §8 Revision 5, decision 4).
	@Test
	public void testTheFrameRateIsMeasuredFromTheFramesSinceTheCameraOpened() {
		assertEquals("30.0 FPS measured (90 frames in 3000 ms)", SarxosCaptureCamera.describeFrameRate(90, 3000));
		assertEquals("0.7 FPS measured (2 frames in 3000 ms)", SarxosCaptureCamera.describeFrameRate(2, 3000));
	}

	@Test
	public void testNoTimeSinceOpeningIsNoFrameRate() {
		assertEquals("0.0 FPS measured (0 frames in 0 ms)", SarxosCaptureCamera.describeFrameRate(0, 0));
	}
}
```


- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.camera.cameratypes.TestSarxosCaptureCamera --console=plain`

Expected: FAIL to compile: `cannot find symbol` (`describeFrameRate`).

- [ ] **Step 3: Implement**

`core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java`:

Replace:

```java
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
```

with:

```java
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
```

Replace:

```java
	// Cancelled on close() so a close and reopen within CAPTURE_STATE_LOG_DELAY doesn't log early or twice
	private ScheduledFuture<?> captureStateLogFuture;

	// setViewSize is called before open() by CameraManager/CheckableImageListCell, and
```

with:

```java
	// Cancelled on close() so a close and reopen within CAPTURE_STATE_LOG_DELAY doesn't log early or twice
	private ScheduledFuture<?> captureStateLogFuture;
	// When the camera last opened, and its frame count then, for the frame rate logged CAPTURE_STATE_LOG_DELAY later
	private volatile long openedAt = 0;
	private volatile int framesAtOpen = 0;

	// setViewSize is called before open() by CameraManager/CheckableImageListCell, and
```

Replace:

```java

			// What the frame rate and the exposure settle into (spec §8 Revision 4, decision 3)
			captureStateLogFuture = TimerPool.schedule(this::logCaptureState, CAPTURE_STATE_LOG_DELAY);
		}
```

with:

```java

			// What the frame rate and the exposure settle into (spec §8 Revision 4, decision 3)
			openedAt = System.currentTimeMillis();
			framesAtOpen = getFrameCount();
			captureStateLogFuture = TimerPool.schedule(this::logCaptureState, CAPTURE_STATE_LOG_DELAY);
		}
```

Replace:

```java
		if (!isOpen() || closing.get()) return;

		logger.info("{} {} s after opening: {} FPS, {}", getName(), CAPTURE_STATE_LOG_DELAY / 1000,
				String.format("%.1f", getFPS()), exposureState());
	}

```

with:

```java
		if (!isOpen() || closing.get()) return;

		// Counted here rather than getFPS(), which starts at 30 and needs ten frames for its first estimate: a dark
		// scene gives fewer in 3 s (spec §8 Revision 5, decision 4)
		logger.info("{} {} s after opening: {}, {}", getName(), CAPTURE_STATE_LOG_DELAY / 1000,
				describeFrameRate(getFrameCount() - framesAtOpen, System.currentTimeMillis() - openedAt),
				exposureState());
	}

	static String describeFrameRate(int frames, long millis) {
		final double fps = millis > 0 ? frames * 1000.0 / millis : 0;
		return String.format(Locale.ROOT, "%.1f FPS measured (%d frames in %d ms)", fps, frames, millis);
	}

```

Replace:

```java
				: OptionalInt.empty();

		return describeExposure(camera.get(Videoio.CAP_PROP_EXPOSURE), camera.get(Videoio.CAP_PROP_AUTO_EXPOSURE),
				dynamicFramerate);
	}

```

with:

```java
				: OptionalInt.empty();

		return describeExposure(freshExposure(), camera.get(Videoio.CAP_PROP_AUTO_EXPOSURE), dynamicFramerate);
	}

```


- [ ] **Step 4: Run the tests**

Run the Step 2 command. Expected: PASS. `TestSarxosCaptureCamera` runs 18 tests.

- [ ] **Step 5: The gate**

Expected: `807/807 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/camera/cameratypes/SarxosCaptureCamera.java core/src/test/java/com/shootoff/camera/cameratypes/TestSarxosCaptureCamera.java
git commit -m "Log the measured frame rate and a fresh exposure 3 s after opening"
git log -1 --format=%B
```

---

### Task 3: The pattern timed from when it shows, and the times forgotten when the arena closes

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt` (`CalibrationViews.patternShown`; `calibrationStarted`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt` (`arenaFilledAt` becomes `patternShownAt`; `watchArenaForPattern`, `calibrateOnTheProjector`, `detachCamera`, `closeArena`, `startCalibration`, `calibrationCancelled` and `calibrationSucceeded`; new `patternShown` and `forgetCalibrationTimes`)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationLogging.kt`

**Interfaces:**
- Consumes:
  - `CalibrationFlow.startAutoCalibration`, which calls `View.calibrationStarted()` as it shows the pattern.
  - `AppFixture.appWithCamera(checkClock = …, patternSettleMillis = …, calibrationTimers = …)` and `AppFixture.putOnTheProjector`.
- Produces:
  - `fun CalibrationViews.patternShown() {}`, a default no-op. `CalibrationFixture`'s views keep the default.
  - `AppState.patternShown()` keeps the first time per calibration.
  - The success line's "N ms since the pattern first showed" counts from `patternShown()`.

**The cause** is set out under "Root causes, item 3" above. The first new test replays the reopen: the arena fills the screen at 0, and its calibration starts, with the pattern, at 5000.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationLogging.kt`:

Replace:

```kotlin
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
```

with:

```kotlin
import com.shootoff.geom.Rect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
```

Replace:

```kotlin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
```

with:

```kotlin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
```

Replace:

```kotlin
    }

    private fun messages() = appender.list.map { it.formattedMessage }

    @Test
```

with:

```kotlin
    }

    // The appender adds under its own lock (AppenderBase.doAppend), from whichever thread logs
    private fun messages() = synchronized(appender) { appender.list.map { it.formattedMessage } }

    @Test
```

Replace:

```kotlin

    // Plan 8's final review: after an automatic calibration timed out, a Calibrate the owner pressed logged the
    // automatic one's stale "ms since the pattern first showed"
    @Test
    fun aCalibrationTheOwnerStartsAfterAnAutomaticOneTimedOutLogsNoStalePatternTime() {
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        app.close()
        app = AppFixture.appWithCamera(calibrationTimers = { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
```

with:

```kotlin

    // Plan 8's final review: after an automatic calibration timed out, a Calibrate the owner pressed logged the
    // automatic one's stale "ms since the pattern first showed". It logs its own pattern's time.
    @Test
    fun aCalibrationTheOwnerStartsAfterAnAutomaticOneTimedOutLogsItsOwnPatternTime() {
        val now = AtomicLong(0)
        val timers = CopyOnWriteArrayList<Pair<Long, Runnable>>()
        app.close()
        app = AppFixture.appWithCamera(checkClock = now::get, calibrationTimers = { task, delay ->
            timers += delay to task
            CompletableFuture<Void>()
```

Replace:

```kotlin
        AppFixture.putOnTheProjector(app)
        awaitTrue { timers.any { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED } }
        timers.filter { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED }.forEach { it.second.run() }

        app.startCalibration()
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertFalse(success.contains("since the pattern first showed"), success)
    }

```

with:

```kotlin
        AppFixture.putOnTheProjector(app)
        awaitTrue { timers.any { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED } }
        now.set(60_000)
        timers.filter { it.first == CalibrationFlow.AUTO_CALIBRATION_TIMEOUT_UNATTENDED }.forEach { it.second.run() }

        app.startCalibration()
        now.set(61_500)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertTrue(success.endsWith("1500 ms since it started, 1500 ms since the pattern first showed"), success)
    }

    // Plan 9's hardware check: after an arena reopen the log said "2217 ms since it started, 7261 ms since the
    // pattern first showed". The arena had filled the projector's screen about 5 s before its calibration started;
    // the pattern showed only then (spec §8 Revision 5, decision 4).
    @Test
    fun thePatternTimeCountsFromWhenThePatternShowedNotFromWhenTheArenaFilledTheScreen() {
        val now = AtomicLong(0)
        app.close()
        app = AppFixture.appWithCamera(checkClock = now::get, patternSettleMillis = 300)
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)

        // The arena filled the screen at 0; the calibration starts, and the pattern shows, at 5000
        Thread.sleep(100)
        now.set(5000)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        now.set(7000)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertTrue(success.endsWith("2000 ms since it started, 2000 ms since the pattern first showed"), success)
    }

    @Test
    fun anArenaClosedMidCalibrationLeavesNoTimesForTheNextOne() {
        val now = AtomicLong(0)
        app.close()
        app = AppFixture.appWithCamera(checkClock = now::get)
        app.setRememberCalibration(true)
        app.openStartCamera()
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        now.set(1000)
        app.closeArena()

        now.set(10_000)
        app.openArena()
        AppFixture.putOnTheProjector(app)
        awaitTrue { app.calibration.value?.state?.value?.message == Message.AUTO_CALIBRATING }
        now.set(12_000)
        app.calibration.value!!.calibrate(Rect(100.0, 80.0, 400.0, 300.0), Optional.empty(), false, 0)

        val success = messages().single { it.startsWith("Calibration succeeded") }
        assertTrue(success.endsWith("2000 ms since it started, 2000 ms since the pattern first showed"), success)
    }

```


- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestCalibrationLogging --console=plain`

Expected: FAIL, 2 of 8:
- `thePatternTimeCountsFromWhenThePatternShowedNotFromWhenTheArenaFilledTheScreen` ("… 2000 ms since it started, 7000 ms since the pattern first showed").
- `aCalibrationTheOwnerStartsAfterAnAutomaticOneTimedOutLogsItsOwnPatternTime` ("… 1500 ms since it started", with no pattern time).

`anArenaClosedMidCalibrationLeavesNoTimesForTheNextOne` passes already: the old watch set a fresh time when the reopened arena filled the screen. It pins the `closeArena` half of the fix. In the prototype, with `forgetCalibrationTimes()` left out of `closeArena`, it fails ("… 2000 ms since it started, 12000 ms since the pattern first showed").

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt`:

Replace:

```kotlin
    /** The owner cancelled a calibration (Setup's Cancel, or the feed's), which left everything as it was. */
    fun calibrationCancelled() {}
}

```

with:

```kotlin
    /** The owner cancelled a calibration (Setup's Cancel, or the feed's), which left everything as it was. */
    fun calibrationCancelled() {}

    /** The pattern shows on the arena, and the camera looks for it: each time it (re)starts looking. */
    fun patternShown() {}
}

```

Replace:

```kotlin
        arena.cover(true)
        arena.setCalibrationLabelVisible(false)
    }

```

with:

```kotlin
        arena.cover(true)
        arena.setCalibrationLabelVisible(false)
        views.patternShown()
    }

```

`compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt`:

Replace:

```kotlin
    private var fullScreenWatch: Job? = null

    // When the arena last filled the projector's screen (watchArenaForPattern), by checkClock: roughly when
    // the pattern first showed, for the elapsed time in the success log (spec §8 Revision 3, decision 11)
    @Volatile
    private var arenaFilledAt: Long? = null

    // When the calibration now running (or the one that just ended) started, by checkClock, for the same log
```

with:

```kotlin
    private var fullScreenWatch: Job? = null

    // When the calibration now running first showed its pattern, by checkClock, for the elapsed time in the success
    // log (spec §8 Revision 3, decision 11). Not when the arena filled the projector's screen: after a reopen the
    // calibration started about 5 s after that (spec §8 Revision 5, decision 4)
    @Volatile
    private var patternShownAt: Long? = null

    // When the calibration now running (or the one that just ended) started, by checkClock, for the same log
```

Replace:

```kotlin
        // For calibration the camera going is the arena going: calibration ends, and both projections go
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
```

with:

```kotlin
        // For calibration the camera going is the arena going: calibration ends, and both projections go
        calibrationState.value?.arenaClosing()
        forgetCalibrationTimes()
        fullScreenWatch?.cancel()
        calibrationState.value = null
```

Replace:

```kotlin
                checkState.value = CheckState.NotFound
                calibrationStartedAt = null
                arenaFilledAt = null
            }
        }
```

with:

```kotlin
                checkState.value = CheckState.NotFound
                calibrationStartedAt = null
                patternShownAt = null
            }
        }
```

Replace:

```kotlin
                .distinctUntilChanged()
                .collectLatest { onTheProjector ->
                    if (onTheProjector) {
                        arenaFilledAt = checkClock()
                        delay(patternSettleMillis)
                    }
                    uiThread(Runnable {
                        if (arenaState.value !== arena || !checkState.value.showsPattern) return@Runnable
```

with:

```kotlin
                .distinctUntilChanged()
                .collectLatest { onTheProjector ->
                    if (onTheProjector) delay(patternSettleMillis)
                    uiThread(Runnable {
                        if (arenaState.value !== arena || !checkState.value.showsPattern) return@Runnable
```

Replace:

```kotlin
        currentCalibration = null
        calibrationState.value?.arenaClosing()
        fullScreenWatch?.cancel()
        calibrationState.value = null
```

with:

```kotlin
        currentCalibration = null
        calibrationState.value?.arenaClosing()
        forgetCalibrationTimes()
        fullScreenWatch?.cancel()
        calibrationState.value = null
```

Replace:

```kotlin
        promptSkippedState.value = false
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }

```

with:

```kotlin
        promptSkippedState.value = false
        arena.targets.set.targets.forEach { arena.targets.remove(it.id) }
    }

    // A calibration that ended abruptly (the arena closing, the camera lost) leaves no times behind for the next one
    private fun forgetCalibrationTimes() {
        calibrationStartedAt = null
        patternShownAt = null
    }

```

Replace:

```kotlin
        } else {
            calibrationStartedAt = checkClock()
            // When the arena reached the projector says nothing about a calibration the owner starts later
            arenaFilledAt = null
        }
        // A check under way stops first, putting the arena's background back before calibration saves it
```

with:

```kotlin
        } else {
            calibrationStartedAt = checkClock()
            patternShownAt = null
        }
        // A check under way stops first, putting the arena's background back before calibration saves it
```

Replace:

```kotlin
        logger.info("Calibration cancelled, {} ms after it started", calibrationStartedAt?.let { checkClock() - it })
        calibrationStartedAt = null
        arenaFilledAt = null
    }

```

with:

```kotlin
        logger.info("Calibration cancelled, {} ms after it started", calibrationStartedAt?.let { checkClock() - it })
        calibrationStartedAt = null
        patternShownAt = null
    }

    override fun patternShown() {
        if (patternShownAt == null) patternShownAt = checkClock()
    }

```

Replace:

```kotlin
        val nowClock = checkClock()
        val sinceStart = calibrationStartedAt?.let { nowClock - it }
        val sincePattern = arenaFilledAt?.let { nowClock - it }
        logger.info(
            "Calibration succeeded: found by {}, bounds {}, {} ms since it started{}",
```

with:

```kotlin
        val nowClock = checkClock()
        val sinceStart = calibrationStartedAt?.let { nowClock - it }
        val sincePattern = patternShownAt?.let { nowClock - it }
        logger.info(
            "Calibration succeeded: found by {}, bounds {}, {} ms since it started{}",
```

Replace:

```kotlin
        )
        calibrationStartedAt = null
        arenaFilledAt = null
        calibratedAtState.value = now
        // The user stays where they are (Setup, usually) and is told it worked; they go back to Range when
```

with:

```kotlin
        )
        calibrationStartedAt = null
        patternShownAt = null
        calibratedAtState.value = now
        // The user stays where they are (Setup, usually) and is told it worked; they go back to Range when
```


- [ ] **Step 4: Run the tests**

Run the Step 2 command. Expected: PASS, 8 tests.

- [ ] **Step 5: The gate**

Expected: `809/809 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/AppState.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/CalibrationController.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestCalibrationLogging.kt
git commit -m "Time the pattern from when it shows, and forget the times when the arena closes"
git log -1 --format=%B
```

---

### Task 4: Show grid says why it is off

**Files:**
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt` (new `gridUnavailableReason`)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt` (`CalibrateStep`'s Show grid row)
- Modify: `compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt` (`PatternRun.stop`'s KDoc, wrapped)
- Test: `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt`, `compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`

**Interfaces:**
- Consumes: `CheckState` (`WaitingToCalibrate`, `Checking`); `AppFixture.appWithCamera()`, `AppFixture.setUpForProjectorDrills(app)`, `AppFixture.projectorDrill` and `AppState.startDrill`.
- Produces:
  - `fun gridUnavailableReason(arenaOpen: Boolean, calibrating: Boolean, check: CheckState, projectorDrill: Boolean): String?`, top level in `SetupSteps.kt`.
  - The test tag `grid-reason` on the reason's `Text`. The switch keeps its tag `show-grid`.

**Why** (spec decision 3): Plan 6 put "Stop the drill to show the grid" on a line of its own under the switch, for the drill only, and the owner didn't see it (Plan 9's hardware check). Calibrating and the check had no reason at all.

- [ ] **Step 1: Write the failing tests**

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt`:

Replace:

```kotlin
        assertEquals("No arena", notReadyCalibrateDetail(false, false, false, null, CheckState.Idle))
    }
}
```

with:

```kotlin
        assertEquals("No arena", notReadyCalibrateDetail(false, false, false, null, CheckState.Idle))
    }

    // Plan 9's hardware check: the owner had to guess that the drill kept Show grid off (spec §8 Revision 5, decision 3)
    @Test
    fun showGridSaysWhyItIsOff() {
        val idle = CheckState.Idle
        assertEquals("Not while calibrating", gridUnavailableReason(true, true, idle, false))
        assertEquals("Not while calibrating", gridUnavailableReason(true, false, CheckState.WaitingToCalibrate, false))
        assertEquals("Not while checking the saved calibration", gridUnavailableReason(true, false, CheckState.Checking, false))
        assertEquals("Stop the drill to show the grid", gridUnavailableReason(true, false, idle, true))
        // Calibration pauses the drill: calibrating is the reason then
        assertEquals("Not while calibrating", gridUnavailableReason(true, true, idle, true))
        // It can be turned on; or there is no arena, which the Projector step already says
        assertEquals(null, gridUnavailableReason(true, false, idle, false))
        assertEquals(null, gridUnavailableReason(true, false, CheckState.NotFound, false))
        assertEquals(null, gridUnavailableReason(false, false, idle, false))
    }
}
```

`compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt`:

Replace:

```kotlin
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
```

with:

```kotlin
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
```

Replace:

```kotlin

    @Test
    fun theCalibratedProjectionIsOutlinedOverTheFeed() {
        // A 640x480 canvas shown at 320x240
```

with:

```kotlin

    @Test
    fun showGridSaysBesideItThatTheDrillKeepsItOff() {
        app.close()
        app = AppFixture.appWithCamera()
        AppFixture.setUpForProjectorDrills(app)
        assertTrue(app.startDrill(AppFixture.projectorDrill))
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("show-grid").assertIsNotEnabled()
        compose.onNodeWithTag("grid-reason").assertTextEquals("Stop the drill to show the grid")
    }

    @Test
    fun showGridHasNoReasonWhenItCanBeTurnedOn() {
        app.openArena()
        app.navigate(Destination.SETUP)
        showApp()

        compose.onNodeWithTag("show-grid").assertIsEnabled()
        compose.onNodeWithTag("grid-reason").assertDoesNotExist()
    }

    @Test
    fun theCalibratedProjectionIsOutlinedOverTheFeed() {
        // A 640x480 canvas shown at 320x240
```


- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :compose-app:test --tests com.shootoff.compose.app.TestSetupSteps --tests com.shootoff.compose.app.TestSetupScreen --console=plain`

Expected: FAIL to compile: `Unresolved reference 'gridUnavailableReason'`.

- [ ] **Step 3: Implement**

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt`:

Replace:

```kotlin

/**
 * What Range's not-ready card says after its "Calibrate —" step (it shows only with a camera): the summary, except
 * that a pattern not found says to press the card's own Set up button.
```

with:

```kotlin

/**
 * Why Setup's Show grid is off, said beside its disabled switch (spec §8 Revision 5, decision 3): calibration
 * (including an automatic one waiting for the projector), the saved box's check, or a projector drill needs the
 * arena. Null when the grid can be turned on, and with no arena, which the Projector step already says.
 */
fun gridUnavailableReason(arenaOpen: Boolean, calibrating: Boolean, check: CheckState, projectorDrill: Boolean): String? = when {
    !arenaOpen -> null
    calibrating || check == CheckState.WaitingToCalibrate -> "Not while calibrating"
    check == CheckState.Checking -> "Not while checking the saved calibration"
    projectorDrill -> "Stop the drill to show the grid"
    else -> null
}

/**
 * What Range's not-ready card says after its "Calibrate —" step (it shows only with a camera): the summary, except
 * that a pattern not found says to press the card's own Set up button.
```

`compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt`:

Replace:

```kotlin
        }
    }
    val projectorDrill = running?.host?.isProjector == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = grid,
            onCheckedChange = app::showGrid,
            enabled = arena != null && !calibrating && !check.showsPattern && !projectorDrill,
            modifier = Modifier.testTag("show-grid"),
        )
        Text("Show grid", color = colors.text)
    }
    if (projectorDrill) Text("Stop the drill to show the grid", color = colors.muted, fontSize = 12.sp)
}
```

with:

```kotlin
        }
    }
    val gridReason = gridUnavailableReason(arena != null, calibrating, check, running?.host?.isProjector == true)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = grid,
            onCheckedChange = app::showGrid,
            enabled = arena != null && gridReason == null,
            modifier = Modifier.testTag("show-grid"),
        )
        Text("Show grid", color = colors.text)
        // Beside the switch, where the owner looks (Plan 9's hardware check: they had to guess)
        gridReason?.let { Text(it, color = colors.muted, fontSize = 12.sp, modifier = Modifier.testTag("grid-reason")) }
    }
}
```

`compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt`:

Replace:

```kotlin

    /**
     * Stops the run; on the UI thread. Never touches the work itself: a check's `offer` is `synchronized` and can hold its lock for as long as a detection takes (hundreds of milliseconds when
     * the pattern isn't found), which would freeze the UI thread here (spec §8 rule 6). [finish]'s
     * compare-and-set makes the first end final, so a result arriving after this is ignored.
     */
```

with:

```kotlin

    /**
     * Stops the run; on the UI thread. Never touches the work itself: a check's `offer` is `synchronized` and
     * can hold its lock for as long as a detection takes (hundreds of milliseconds when the pattern isn't
     * found), which would freeze the UI thread here (spec §8 rule 6). [finish]'s
     * compare-and-set makes the first end final, so a result arriving after this is ignored.
     */
```


- [ ] **Step 4: Run the tests**

Run the Step 2 command. Expected: PASS: `TestSetupSteps` 4 tests, `TestSetupScreen` 10.

- [ ] **Step 5: The gate**

Expected: `812/812 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add compose-app/src/main/kotlin/com/shootoff/compose/app/SetupSteps.kt compose-app/src/main/kotlin/com/shootoff/compose/app/SetupScreen.kt compose-app/src/main/kotlin/com/shootoff/compose/calibration/RememberedCalibration.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupSteps.kt compose-app/src/test/kotlin/com/shootoff/compose/app/TestSetupScreen.kt
git commit -m "Say beside Show grid why it is off"
git log -1 --format=%B
```

---

### Task 5: Cancel waits for an exposure call under way, and the ended calibration touches the camera no more

**Files:**
- Modify: `core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java` (new `stopped` and `stop()`; `processFrame`, `StepFindBounds.process`, `StepAdjustExposure.process`)
- Modify: `core/src/main/java/com/shootoff/camera/CameraManager.java` (`autoCalibrateSuccess`, `disableAutoCalibration`)
- Modify: `core/src/main/java/com/shootoff/calibration/CalibrationFlow.java` (`arenaClosing`)
- Test: `core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`, `core/src/test/java/com/shootoff/camera/autocalibration/TestStepAdjustExposure.java`, `core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`

**Interfaces:**
- Consumes:
  - Task 1's `ExposureCamera` (its `"probe"` record).
  - Plan 9's `CameraManager.saveCalibration()`, `restoreCalibration(Saved)`, and the protected `acm`, `cameraAutoCalibrated` and `autoCalibrateSuccess`, which `TestCameraManagerCalibration` reaches from the same package, `com.shootoff.camera`.
  - `AutoCalibrationManager.WHITE_SCREEN_TIMEOUT` and the package-private `stepAdjustExposure`.
- Produces:
  - `public void AutoCalibrationManager.stop()`: under the camera's monitor, marks the calibration ended.
  - `CameraManager.disableAutoCalibration()` stops the current manager. It and `autoCalibrateSuccess` take `synchronized (camera)`.
  - `CalibrationFlow.arenaClosing()` clears `unattendedTimeout`, so `isUnattended()` is false after it.
  - `TestCameraManagerCalibration.ExposureCamera.adjustable` (whether the exposure step runs) and `onDecrease` (a hook run while lowering).

**The cause** is set out under "Root causes, item 5" above, and the choice of fix under ruling 8. The first test lands a Cancel, on another thread, in the middle of `decreaseExposure`. Before the fix, the restore ("auto") came before "lowered"; now it waits.

- [ ] **Step 1: Write the failing tests**

`core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java`:

Replace:

```java
	}

	@Test
	void anUnattendedCalibrationThatFindsThePatternEndsAsAnyOtherAndTheNextStartIsAttended() {
```

with:

```java
	}

	// Plan 8's Task 1 minor: the arena closing ends an unattended calibration's unattendedness itself, not only
	// through the cancel the Compose app runs first
	@Test
	void theArenaClosingEndsAnUnattendedCalibrationsTimeoutToo() {
		final CalibrationFlow flow = flow();
		flow.startUnattended(() -> events.add("not found"));

		flow.arenaClosing();

		assertFalse(flow.isUnattended());
	}

	@Test
	void anUnattendedCalibrationThatFindsThePatternEndsAsAnyOtherAndTheNextStartIsAttended() {
```

`core/src/test/java/com/shootoff/camera/autocalibration/TestStepAdjustExposure.java`:

Replace:

```java
	private final ExposureCamera camera = new ExposureCamera();
	private final List<String> backgrounds = new CopyOnWriteArrayList<>();
	private AutoCalibrationManager.AutoCalStep step;

```

with:

```java
	private final ExposureCamera camera = new ExposureCamera();
	private final List<String> backgrounds = new CopyOnWriteArrayList<>();
	private AutoCalibrationManager manager;
	private AutoCalibrationManager.AutoCalStep step;

```

Replace:

```java
	@BeforeEach
	void setUp() {
		final AutoCalibrationManager manager = new AutoCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {}
```

with:

```java
	@BeforeEach
	void setUp() {
		manager = new AutoCalibrationManager(new CameraCalibrationListener() {
			@Override
			public void calibrate(Rect arenaBounds, Optional<Size> paper, boolean calibratedFromCanvas, long delay) {}
```

Replace:

```java
		assertEquals(1, camera.decreases);
	}
}
```

with:

```java
		assertEquals(1, camera.decreases);
	}

	// Cancel (spec §8 Revision 5, decision 2): the step of a calibration that has ended no longer touches the exposure
	// Cancel put back, whatever frames still reach it
	@Test
	void theStepOfAStoppedCalibrationLeavesTheExposureAlone() {
		frame(0, 40);
		frame(33, 130);

		manager.stop();
		frame(66, 131);
		frame(166, 118);
		frame(AutoCalibrationManager.WHITE_SCREEN_TIMEOUT + 100, 40);

		assertEquals(0, camera.decreases);
		assertEquals(0, camera.resets);
	}
}
```

`core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java`:

Replace:

```java
		// Runs while the limit is being taken, to land a race with something else on the exposure in the middle
		Runnable onLimit = () -> {};

		@Override
```

with:

```java
		// Runs while the limit is being taken, to land a race with something else on the exposure in the middle
		Runnable onLimit = () -> {};
		// Whether the exposure step runs, and what runs while it lowers the exposure
		boolean adjustable = false;
		Runnable onDecrease = () -> {};

		@Override
```

Replace:

```java
		public boolean supportsExposureAdjustment() {
			exposure.add("probe");
			return false;
		}
	}
```

with:

```java
		public boolean supportsExposureAdjustment() {
			exposure.add("probe");
			return adjustable;
		}

		@Override
		public boolean decreaseExposure() {
			exposure.add("lowering");
			onDecrease.run();
			exposure.add("lowered");
			return true;
		}
	}
```

Replace:

```java
	}

	// Cancel pressed while the camera's thread is still looking at a frame (a look takes 400-500 ms): whatever that
	// look finds belongs to the cancelled calibration and can't change the warp put back
```

with:

```java
	}

	// A plain white camera frame, as the exposure step's white screen looks
	private static BufferedImage white() {
		final BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
		final Graphics2D g = frame.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 640, 480);
		g.dispose();
		return frame;
	}

	// Plan 9's final review, minor 1: a frame still in the exposure step as Cancel lands lowered the exposure just
	// after Cancel had put the old one back. Cancel now waits for a camera call under way, and none starts after it
	// (spec §8 Revision 5, decision 2).
	@Test
	void anExposureStepCallUnderWayAtCancelEndsBeforeTheExposureIsPutBack() throws Exception {
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		camera.adjustable = true;
		manager.enableAutoCalibration(false);
		final Thread[] cancel = new Thread[1];
		camera.onDecrease = () -> {
			// Cancel, on the UI thread, while the camera's thread is lowering the exposure
			cancel[0] = new Thread(() -> {
				manager.disableAutoCalibration();
				manager.restoreCalibration(saved);
			});
			cancel[0].start();
			try {
				cancel[0].join(200);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		};

		// The pattern, then the paper step's frames, then the exposure step's white screen until it lowers the exposure
		manager.processFrame(new Frame(frame(new Rect(100, 80, 420, 296)), 1000), true);
		final BufferedImage white = white();
		for (long timestamp = 1100; !camera.exposure.contains("lowering") && timestamp < 5000; timestamp += 100)
			manager.processFrame(new Frame(white, timestamp), true);
		cancel[0].join();

		// The exposure lowered, then put back ("auto": it exposed automatically before), never the other way round
		final List<String> exposure = camera.exposure;
		assertTrue(exposure.indexOf("lowered") < exposure.lastIndexOf("auto"), exposure.toString());
	}

	// The same gap in the success: the pattern found just as Cancel lands must not mark the camera calibrated again
	// after Cancel put the old calibration back
	@Test
	void aPatternFoundAsCancelLandsWaitsForCancelAndThenChangesNothing() throws Exception {
		final CalibrationCamera.Saved saved = manager.saveCalibration();
		manager.enableAutoCalibration(false);

		final Thread success = new Thread(
				() -> manager.autoCalibrateSuccess(new Rect(100, 80, 420, 296), Optional.empty(), 0));
		// Cancel holds the camera while it ends auto-calibration and puts the calibration back
		synchronized (camera) {
			success.start();
			success.join(200);
			manager.disableAutoCalibration();
			manager.restoreCalibration(saved);
		}
		success.join();

		assertFalse(manager.cameraAutoCalibrated);
		assertTrue(found.isEmpty(), found.toString());
	}

	// Cancel, then Calibrate again, while the cancelled calibration's last look is still under way: what that look
	// goes on to find is not the new calibration's
	@Test
	void aStoppedCalibrationReportsNothingItFindsLater() throws IOException {
		manager.enableAutoCalibration(false);
		final AutoCalibrationManager cancelled = manager.acm;
		manager.disableAutoCalibration();
		manager.enableAutoCalibration(false);

		final BufferedImage image = frame(new Rect(100, 80, 420, 296));
		for (long timestamp = 1000; timestamp < 5000; timestamp += 300)
			cancelled.processFrame(new Frame(image, timestamp));

		assertTrue(found.isEmpty(), found.toString());
		assertFalse(manager.cameraAutoCalibrated);
	}

	// Cancel pressed while the camera's thread is still looking at a frame (a look takes 400-500 ms): whatever that
	// look finds belongs to the cancelled calibration and can't change the warp put back
```


- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew :core:test --tests com.shootoff.camera.TestCameraManagerCalibration --tests com.shootoff.camera.autocalibration.TestStepAdjustExposure --tests com.shootoff.calibration.TestCalibrationFlow --console=plain`

Expected: FAIL to compile: `cannot find symbol` (`stop()` in `TestStepAdjustExposure`).

In the prototype, with an empty `public void stop() {}` added to let it compile, all five new tests fail:
- `anExposureStepCallUnderWayAtCancelEndsBeforeTheExposureIsPutBack` (`[… lowering, limit released, auto, lowered, …]`).
- `aPatternFoundAsCancelLandsWaitsForCancelAndThenChangesNothing` and `aStoppedCalibrationReportsNothingItFindsLater` (a `Rect` found).
- `theStepOfAStoppedCalibrationLeavesTheExposureAlone` (`expected: <0> but was: <2>`).
- `theArenaClosingEndsAnUnattendedCalibrationsTimeoutToo`.

- [ ] **Step 3: Implement**

`core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java`:

Replace:

```java
	}

	protected AutoCalStep stepFindBounds = null;
	protected AutoCalStep stepFindDelay = null;
```

with:

```java
	}

	// Set once auto-calibration ends (CameraManager.disableAutoCalibration), under the camera's monitor: from then on
	// this calibration neither changes the camera's exposure nor reports a pattern it finds (spec §8 Revision 5,
	// decision 2)
	private volatile boolean stopped = false;

	protected AutoCalStep stepFindBounds = null;
	protected AutoCalStep stepFindDelay = null;
```

Replace:

```java

		return ((StepFindBounds) stepFindBounds).boundsResult;
	}

```

with:

```java

		return ((StepFindBounds) stepFindBounds).boundsResult;
	}

	/**
	 * Auto-calibration has ended (found, cancelled, timed out): a frame still being processed changes nothing on the
	 * camera from here on. Takes the camera's monitor, so it waits for an exposure call already under way; that is
	 * only ever a few V4L2 calls, never a look for the pattern.
	 */
	public void stop() {
		synchronized (camera) {
			stopped = true;
		}
	}

```

Replace:

```java
			}
		}
		if (isFinished()) calibrationListener.calibrate(((StepFindBounds) stepFindBounds).boundsResult,
				((StepFindPaperPattern) stepFindPaperPattern).paperDimensions, false,
				((StepFindDelay) stepFindDelay).frameDelayResult);
```

with:

```java
			}
		}
		if (isFinished() && !stopped) calibrationListener.calibrate(((StepFindBounds) stepFindBounds).boundsResult,
				((StepFindPaperPattern) stepFindPaperPattern).paperDimensions, false,
				((StepFindDelay) stepFindDelay).frameDelayResult);
```

Replace:

```java
			// A dark scene lengthens automatic exposure and drops the frame rate, slowing the search (spec §8
			// Revision 4, decision 3); held to a frame until auto-calibration stops (CameraManager)
			camera.limitExposureToFramePeriod();

			Imgproc.equalizeHist(frame.getOriginalMat(), frame.getOriginalMat());
```

with:

```java
			// A dark scene lengthens automatic exposure and drops the frame rate, slowing the search (spec §8
			// Revision 4, decision 3); held to a frame until auto-calibration stops (CameraManager)
			synchronized (camera) {
				if (!stopped) camera.limitExposureToFramePeriod();
			}

			Imgproc.equalizeHist(frame.getOriginalMat(), frame.getOriginalMat());
```

Replace:

```java
			}

			// The baseline is the white screen's brightness, so it waits for the white to reach the camera: the
			// arena, the projector and the camera can take several frames to show it (spec §8 Revision 4, decision 2)
			if (origMean == 0) {
				final double brightness = Core.mean(frame.getOriginalMat()).val[0];
				if (!whiteScreenSettled(brightness, frame.getTimestamp())) {
					lastMean = brightness;
```

with:

```java
			}

			final double brightness = Core.mean(frame.getOriginalMat()).val[0];

			// The baseline is the white screen's brightness, so it waits for the white to reach the camera: the
			// arena, the projector and the camera can take several frames to show it (spec §8 Revision 4, decision 2)
			if (origMean == 0) {
				if (!whiteScreenSettled(brightness, frame.getTimestamp())) {
					lastMean = brightness;
```

Replace:

```java
			if (frame.getTimestamp() - lastSample < SAMPLE_DELAY) return;

			final Scalar mean = Core.mean(frame.getOriginalMat());
			if (origMean == 0) origMean = mean.val[0];

			logger.trace("{} {}", mean.val[0], TARGET_THRESH);

			if (mean.val[0] > TARGET_THRESH) {
				if (!camera.decreaseExposure()) completed = true;
			} else {
				completed = true;
			}

			if (logger.isTraceEnabled()) {
				String filename = String.format("exposure-%d.png", lastSample);
				final File file = new File(filename);
				filename = file.toString();
				Imgcodecs.imwrite(filename, frame.getOriginalMat());
			}

			tries++;
			if (tries == NUM_TRIES) completed = true;

			if (completed) {
				if (mean.val[0] > origMean * .95 || mean.val[0] < .6 * TARGET_THRESH) {
					camera.resetExposure();
					logger.info("Failed to adjust exposure, mean originally {} lowest {}", origMean, mean.val[0]);
				} else {
					logger.info("Exposure lowered to {} mean from {}", mean.val[0], origMean);
				}
			}
```

with:

```java
			if (frame.getTimestamp() - lastSample < SAMPLE_DELAY) return;

			if (origMean == 0) origMean = brightness;

			logger.trace("{} {}", brightness, TARGET_THRESH);

			// Its calls on the camera take the camera's monitor, as the end of auto-calibration does, and are made only
			// while this calibration runs: Cancel ends it before putting the old exposure back, so a frame still here
			// can't lower or reset that exposure afterwards (spec §8 Revision 5, decision 2)
			synchronized (camera) {
				if (stopped) return;

				if (brightness > TARGET_THRESH) {
					if (!camera.decreaseExposure()) completed = true;
				} else {
					completed = true;
				}

				if (logger.isTraceEnabled()) {
					String filename = String.format("exposure-%d.png", lastSample);
					final File file = new File(filename);
					filename = file.toString();
					Imgcodecs.imwrite(filename, frame.getOriginalMat());
				}

				tries++;
				if (tries == NUM_TRIES) completed = true;

				if (completed) {
					if (brightness > origMean * .95 || brightness < .6 * TARGET_THRESH) {
						camera.resetExposure();
						logger.info("Failed to adjust exposure, mean originally {} lowest {}", origMean, brightness);
					} else {
						logger.info("Exposure lowered to {} mean from {}", brightness, origMean);
					}
				}
			}
```

`core/src/main/java/com/shootoff/camera/CameraManager.java`:

Replace:

```java

	protected void autoCalibrateSuccess(Rect arenaBounds, Optional<Size> paperDims, long delay) {
		if (isAutoCalibrating.get() && cameraCalibrationListener != null) {
			isAutoCalibrating.set(false);

			logger.debug("autoCalibrateSuccess {} {} {} {} paper {}", (int) arenaBounds.getMinX(),
					(int) arenaBounds.getMinY(), (int) arenaBounds.getWidth(), (int) arenaBounds.getHeight(),
					paperDims.isPresent());

			cameraAutoCalibrated = true;
			cameraCalibrationListener.calibrate(arenaBounds, paperDims, false, delay);

			if (recordCalibratedArea && !recordingCalibratedArea)
				startRecordingCalibratedArea(new File("calibratedArea.mp4"), (int) arenaBounds.getWidth(),
						(int) arenaBounds.getHeight());
		}
	}

```

with:

```java

	protected void autoCalibrateSuccess(Rect arenaBounds, Optional<Size> paperDims, long delay) {
		// Under the camera's monitor, as disableAutoCalibration is: a Cancel either ends auto-calibration first, and
		// this does nothing, or waits until the camera is marked calibrated, and then puts the old calibration back
		// over it (spec §8 Revision 5, decision 2)
		synchronized (camera) {
			if (!isAutoCalibrating.get() || cameraCalibrationListener == null) return;
			isAutoCalibrating.set(false);
			cameraAutoCalibrated = true;
		}

		logger.debug("autoCalibrateSuccess {} {} {} {} paper {}", (int) arenaBounds.getMinX(),
				(int) arenaBounds.getMinY(), (int) arenaBounds.getWidth(), (int) arenaBounds.getHeight(),
				paperDims.isPresent());

		cameraCalibrationListener.calibrate(arenaBounds, paperDims, false, delay);

		if (recordCalibratedArea && !recordingCalibratedArea)
			startRecordingCalibratedArea(new File("calibratedArea.mp4"), (int) arenaBounds.getWidth(),
					(int) arenaBounds.getHeight());
	}

```

Replace:

```java

	public void disableAutoCalibration() {
		isAutoCalibrating.set(false);
		// Whatever ended it (the pattern found, Cancel, the time limit), the search's frame-rate limit ends too
		camera.releaseExposureLimit();
	}

```

with:

```java

	public void disableAutoCalibration() {
		// Under the camera's monitor, so an exposure call the calibration has under way ends first, and it makes no
		// more: Cancel puts the old exposure back (restoreCalibration) only after this (spec §8 Revision 5, decision 2)
		synchronized (camera) {
			isAutoCalibrating.set(false);
			final AutoCalibrationManager acm = this.acm;
			if (acm != null) acm.stop();
			// Whatever ended it (the pattern found, Cancel, the time limit), the search's frame-rate limit ends too
			camera.releaseExposureLimit();
		}
	}

```

`core/src/main/java/com/shootoff/calibration/CalibrationFlow.java`:

Replace:

```java
	 */
	public void arenaClosing() {
		camera.setProjectionBounds(null);
	}
```

with:

```java
	 */
	public void arenaClosing() {
		unattendedTimeout = Optional.empty();
		camera.setProjectionBounds(null);
	}
```


- [ ] **Step 4: Run the tests**

Run the Step 2 command, adding `--tests com.shootoff.camera.autocalibration.TestPatternSearchExposure`. Expected: PASS. The two threaded tests passed 8 runs out of 8 in the prototype.

- [ ] **Step 5: The gate**

Expected: `817/817 passing; 0 regressions; 0 new failures`.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/shootoff/camera/autocalibration/AutoCalibrationManager.java core/src/main/java/com/shootoff/camera/CameraManager.java core/src/main/java/com/shootoff/calibration/CalibrationFlow.java core/src/test/java/com/shootoff/camera/TestCameraManagerCalibration.java core/src/test/java/com/shootoff/camera/autocalibration/TestStepAdjustExposure.java core/src/test/java/com/shootoff/calibration/TestCalibrationFlow.java
git commit -m "Cancel waits for an exposure call under way, and the ended calibration touches the camera no more"
git log -1 --format=%B
```

---

### Task 6: The owner's short hardware re-check

Runs in ShootOFF on `compose-ui`. **Files:** none. It covers Revision 5's criteria 17 (as revised) and 19, the 3 s line, and the log after an arena reopen.
- If a check fails, stop and debug with superpowers:systematic-debugging before changing code.
- The fix belongs in the task that owns the code, as a new commit with a test that reproduces it.
- The hardware: the Logitech C270 (640×480) and the 1280×720 projector at x=4480. "Calibrate automatically when the arena opens" stays on throughout.

- [ ] **Step 1: The gate and the boundary**

```bash
cd /home/bfears/projects/ShootOFF
mkdir -p build; ./gradlew cleanTest test --continue --console=plain > build/gate.log 2>&1; python3 scripts/test_summary.py compare */build/test-results/test docs/superpowers/baseline/java8-tests.txt
sha256sum -c build/plan8-owner-files.sha256
./gradlew :compose-app:dependencies --configuration runtimeClasspath --console=plain | command grep -c openjfx
git log -6 --format=%B | command grep -ci "co-authored-by"
```

Expected:
- `817/817 passing; 0 regressions; 0 new failures`.
- `OK` for the three owner files. `shootoff.properties` may say `FAILED`, since both apps save to it.
- `0`.
- `0`.

- [ ] **Step 2: Launch the Compose app**

With the webcam and the projector attached (the projector on), run in the background:

```bash
cd /home/bfears/projects/ShootOFF
./gradlew :compose-app:run --console=plain > build/plan10-compose-run.log 2>&1
```

Relaunch the same way for each item below, appending with `>> build/plan10-compose-run.log 2>&1`.

- [ ] **Step 3: The owner checks, on the hardware**

1. **The lens covered** (criterion 17). Cover the projector's lens and relaunch.
   - Within about a second of "Calibrating automatically: the camera opened" (or "…: the arena opened"), the log has "… auto exposure was E, longer than a frame: held at 333.0 by hand while looking for the pattern".
   - No "Current webcam FPS is … too low" line appears.
   - The "3 s after opening" line reads close to 30 FPS measured, with "exposure 333 (manual)". If the hold came first, it may read a little lower.
   - After 60 s: "The pattern wasn't found in 60 s: calibration ended", and no box.
   - Uncover and press Calibrate: it calibrates, and the log has "Exposure lowered …".
   - Do it twice.
2. **The 3 s line in normal light.** Relaunch uncovered. The line reads roughly 25–30 FPS measured, with an exposure near the owner's usual 336 (auto, mode 3) or, if the exposure step had already run, the manual value it set.
3. **Show grid's reason** (criterion 19).
   - Start the par drill on the projector, then open Setup. Show grid is off, with "Stop the drill to show the grid" beside it.
   - Press Recalibrate: while it runs, the reason reads "Not while calibrating". Once calibrated, the drill is paused and the reason is back to "Stop the drill to show the grid".
   - Stop the drill: the reason goes and the switch works.
4. **The log after an arena reopen.** On Setup, Close arena, then Open arena, three times, letting it calibrate each time.
   - Each "Calibration succeeded … N ms since it started, M ms since the pattern first showed" line has M no larger than N.
   - Once more, close the arena while it is still calibrating, then reopen it: the next success line's times are that calibration's own, a few seconds.
5. **Cancel still keeps the calibration** (Task 5 changed how it ends). With a good calibration, Show grid, then Recalibrate and Cancel about 2 s in, during the white screen, three times. Show grid each time: still lined up.
6. **The JavaFX app** (it shares `core`). `./gradlew run --console=plain > build/plan10-javafx-run.log 2>&1`, then calibrate once on the projector the JavaFX way. It calibrates, and the log has no new ERROR.

- [ ] **Step 4: Check the log**

```bash
cd /home/bfears/projects/ShootOFF
command grep -nE "Exception|Error" build/plan10-compose-run.log
command grep -nE "Calibrating automatically|held at|too low|after opening|Calibration succeeded|Calibration cancelled|wasn't found|Exposure lowered|Failed to adjust|wouldn't take" build/plan10-compose-run.log
```

Expected:
- The first prints nothing new. The pre-existing update-check ERROR ("Couldn't parse <stableRelease tag") may appear.
- The second tells the story of items 1, 2, 4 and 5 in order: in item 1, "held at" within about a second of "Calibrating automatically" and no "too low"; no "wouldn't take" anywhere.

- [ ] **Step 5: Report**

Report to the owner:
- which items passed, and any that failed with their fix commits;
- the time from "Calibrating automatically" to "held at" with the lens covered, and the 3 s line's values, covered and uncovered;
- the success lines after the reopens;
- the open decisions from "For the owner to decide".

---

## Spec coverage

| Spec §8 Revision 5 | Where |
|---|---|
| 1. The hold decided as the search starts, before the exposure step's probe, and at each look; on a fresh reading (`exposure_dynamic_framerate` written back); Linux only, only over a frame | Task 1 (rulings 1–3; `TestCameraManagerCalibration.theExposureIsHeld…`); Task 6 item 1 |
| 2. Cancel not undone by a frame in the exposure step, nor by a pattern found as it lands; waits only for a camera call under way | Task 5 (ruling 8; `TestCameraManagerCalibration`, `TestStepAdjustExposure`); Task 6 item 5 |
| 3. Show grid's reason beside the disabled switch, in the three wordings | Task 4 (ruling 7; `TestSetupSteps`, `TestSetupScreen`); Task 6 item 3 |
| 4. The 3 s line's counted frame rate and fresh exposure; the pattern's time from when it shows; nothing left behind by an arena closed mid-calibration | Task 2 (ruling 4), Task 3 (rulings 5, 6; `TestCalibrationLogging`); Task 6 items 2, 4 |
| Criterion 17 (revised) and 19 | Task 6 items 1 and 3 |
| Delivery: Plan 10 on `compose-ui`, then the owner's short hardware check | this plan; Task 6 |
