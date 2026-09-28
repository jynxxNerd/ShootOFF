# Compose trial: open follow-ups

Collected from the Plan 5 to 10 ledgers (branch `compose-ui`, HEAD f028b8e5) and re-checked against the code at that point. Items fixed by later plans, or about code that no longer exists (the post-calibration Measuring flash, the held drill restart, the arenaFilledAt carry-over, the stale 3 s log, the Show-grid reason), are left out. Sizes: S under an hour, M a few hours, L a plan of its own.

## Owner-level

1. **Four-corner manual calibration box** (L). Manual calibration is a rectangle only, with bounds and no perspective, so it cannot match an angled projector. The owner deferred it.
2. **Is manual calibration needed at all?** The owner is unsure now that auto calibration works well. If not, removing it would also remove the saved-box check (`checkRemembered` in `AppState.kt`, `RememberedCalibration.kt`) and much of the calibration overlay.
3. **Target placement design for roadmap step 3.** Compose has no way to add, move or resize targets on the arena. JavaFX did this on the arena view, and Compose dropped that view. Targets and Sessions are greyed out. The question is where these actions live.
4. **Update-check error in JavaFX.** `javafx-app/.../Main.java:495` logs "Couldn't parse <stableRelease" at startup. It predates the branch (S).
5. **`TestMalfunctionsProcessor.testManyMalfunctions`** in core is probabilistic (at least 80 of N at 90%) and fails by chance now and then. It predates the branch (S).
6. **Session viewer does not load `@` targets.** It predates the branch.
7. **Pepper_Popper in JavaFX**: about 2 hits before one registers, and it falls only on head hits, although head and body both carry animate+play_sound. This may come from the HitTester move to core, or it may be old behaviour. The owner offered a comparison against master (S to M).
8. **Round x/x label** is a drill-side change (separate repo). The label only overlaps the v1.1 summary.

## Calibration and exposure

- **Cancel vs the exposure step** (M). A frame in flight when Cancel arrives can shift the restored exposure (a decrease or a reset after the restore). Effect: rare and small. Fix by applying the restore on the camera thread after the in-flight frame. Related: an in-flight frame between Cancel and Calibrate can release the new enable-time hold (it is retaken next look), and `autoCalibrateSuccess` in `AppState`/`CalibrationController` checks `isAutoCalibrating`, not the manager that reported (microsecond window). Merge these into one small fix.
- **A hold taken in the dark pins `origExposure`** (S). `SarxosCaptureCamera.limitExposureToFramePeriod` (line ~478) records e.g. 20724. `resetExposure` then writes it before switching to auto, which may stall the feed for 1 to 3 s in a lit room. Fix: write `origExposure` only if the camera was in manual mode. Unconfirmed on hardware.
- **Cancel during an unattended calibration** (S). Turning the option off after an unattended calibration started does not stop it. Leaving full screen mid-unattended re-arms it (inherited). `CalibrationFlow.arenaClosing()` does not reset `unattendedTimeout` itself (safe today).
- **Pattern found in the last 2 s of the 30 s unattended timeout** is lost to the timer (inherited, small).
- **`generation` guard** in `CalibrationController` cannot reject a request that reads the generation after a sub-second Cancel then Calibrate. `start()` at line ~169 bumps it, but a detection in flight during Cancel can calibrate a restarted session (benign, same setup).
- **Recalibrating with Remember on over a saved projector calibration**: a window calibration (no projector screen) erases it (line ~208 in `AppState`). Per the Plan 6 ledger this was for the owner's list (S).
- **Exposure edge cases** (S each, hardware): `supportsExposureAdjustment` skips its probe checks while the hold is taken. Exposure restore is Linux-only, and a lowered exposure is not saved on non-Linux. `CAP_PROP_EXPOSURE` units differ on non-C270 cameras. `blankMean` may be taken before the paper step's blank frame arrives (falls back to the 2 s timeout). "Settled" can fire on a mid-rise plateau. An attended timeout during the grace period leaves a partly lowered exposure behind the box.
- **Un-observed**: the auto-cal success path in JavaFX was never seen with exposure-step logging, because JavaFX logging does not enable INFO for `AutoCalibrationManager` (S, config).

## Camera and reconnect

- **Launch path opens the camera by list position** (M). `CameraFactory.getDefault()` (line ~98) and the Settings exact-name webcam match are not node-aware. A camera renumbered at boot opens wrongly, and the owner then picks it by hand. Rare on the single C270.
- **Camera identity**: `settings.webcams` and `SavedCalibration` are keyed by exact name, so a re-enumerated camera may miss its saved calibration. A stripped-path match can also open an identical twin silently (no serial). Both are multi-camera only.
- **Stale pick releases the working camera** before `current()` says "not connected" (multi-camera only). A `current()` instance that differs from the one held makes the next stale pick close and reopen needlessly.
- **Reconnect watch** in `AppState` (lines ~589 to 640):
  - `listed` is set even when the UI guard declines, so a reappearance can be used up without a try.
  - A back-off on try 2 or 3 uses up the appearance (the owner picked another).
  - The owner's pick is ignored while a try is opening (window of about 2 s).
  - Persistent enumeration failure logs a WARN with a stack every 2 s (line ~619).
  - Reconnect latency is up to about 5 s (sarxos 3 s rescan plus 2 s poll).
  - The discard branch never calls `then`, so reopen's await could hang (unreachable today).
  - Setup does not show the "waiting for camera" state (`NoCameraPanel` does).
- **`CameraFactory` / `SarxosCaptureCamera`**: the capture-node query assumes gapless `/dev/videoN` numbering (same as the OpenCV open). The test-only no-arg constructor leaves `name` null (equals/hashCode NPE) (S).
- **UiErrors** (`UiErrors.kt`): the recomposer rebuilds on any error, even one that did not kill it. A persistent arena render error flaps full screen every 3 s (`REBUILD_FLOOR_MILLIS`). The report also swallows `VirtualMachineError`, and the app-level composition in `Main.kt` is outside the net (KDoc should say so) (S).

## UI and Setup/Range

- **DrillsScreen subtitle** "Pick a drill to run on the Range screen." (`DrillsScreen.kt:55`) reads as if rows are pickable (S, wording).
- **Setup "No projector screen found" hint** (`SetupScreen.kt:190`) is recomputed only when the arena changes, so it goes stale if a projector is plugged in while on Setup (S).
- **Not-found text** dropped "calibrate on Setup" (Plan 9 Revision 3 wording). The owner is to confirm the wording. Completion text after automatic calibration and the Remember checkbox wording are also the owner's call (S).
- **Resume enabled on an uncalibrated arena** after not-found or camera loss (existing behaviour; owner's call).
- **Recomposition helpers**: `RangeControls` re-derives `projectorReady()` by hand. `AppState.fills` (line ~828) duplicates `fillsItsScreen` (`ArenaWindow.kt:50`) against a different screen source. `SetupScreen` has two `LaunchedEffect` polling loops with no shared helper. `calibrationSucceeded` sets `destinationState` directly rather than through `navigate()`. Skip survives a camera switch when no arena is open (S each, tidy-up).
- **`saveSettings()`** (`AppState.kt:922`) runs on the UI thread, a small file write (S).
- **`CalibrationController.toggle()`** (line 201) has no production callers, only 8 test uses (S).
- **Memory**: RSS is about 0.9 to 1.1 GB, with 1.4 GB spikes at calibration. It drifted up about 200 MB over 20 min of heavy calibrate/unplug activity. Skia bitmaps are freed by GC, not deterministically. Worth watching (M to investigate).
- **Status-strip FPS** counts frames handed over, not frames painted (S).
- **Log noise**: a log4j `./log/server.log` "Stream Closed" ERROR at shutdown (S). Setup step wording: "Checking" shows the pattern under the "Needs Calibration" label (cosmetic).

## Drill host

- **Drill Pause vs calibration**: F3 is now blocked while calibrating, but `pauseDrill` checks the label, which is updated asynchronously. F3 then F6 within milliseconds can press Pause twice and resume the drill under calibration (S).
- **Par drill buzzer**: F6 after the last round, or camera loss at the summary, sounds the buzzer, because the drill's Pause does that there (drill-side, S).
- **`RegionCommandRunner`** lets a command `RuntimeException` skip delivery (JavaFX parity). The runner holds its lock up to 2 s during `host.stop`, so the UI can wait. Also `animate(false)` edges and a POI 200 ms sleep on the queue (S each).
- **`ShotReceiver`** returns true for a wrong-surface shot (S). `SurfaceTargets.add` registers masks after `set.add`, leaving a brief bbox-hit window (S).
- **Drill card details**: `WebColors` has 33 entries against JavaFX's 147 names (only white/transparent/coral are used today). Delay input truncates fractions, and card text tags by index. The drill picker matches by display name (S each).
- **Auto-cal timeout** can pull the user to Setup mid feed-drill. No projector-drill overlay on the Range feed. No drill-card Stop (use Drills then Stop).
- **A projector drill can start mid-calibration** (Plan 5 final review, Important). Plan 7/8 gating covers the pause path, but starting a drill while calibrating was not re-checked (S to verify).

## Tests

- No unit tests for the `SarxosCaptureCamera` refused-write, `origExposure` and `freshExposure` paths (hardware).
- Missing tests:
  - `detachCamera` and `forgetCalibrationTimes`.
  - `CalibrationCheck.tick` through the 3 to 4 detection paths.
  - Tolerance and 2x-tolerance boundaries.
  - The outcome-vs-stop race (`finish(Kept)` then Cancel).
  - Camera loss mid-calibration with the arena kept open.
  - Switching cameras with Remember on.
  - The grid during a projector drill.
  - A check turning the grid off.
  - A Setup camera click (`setup-camera-<name>` to `pickCamera`).
  - Skip reset on camera loss.
  - An `AppState`-level reconnect with a paused projector drill through an automatic calibration.
  - F6 during unattended calibration.
  - The 3-tries reconnect panel.
  - The exact-before-stripped match.
  - A pick during retries.
  - `SavedCalibration` with `paper = Optional.empty()` round trip.
  - Placement wait loop and size-clamp branch (`MainWindowPlacement.kt`).
  - The Pepper_Popper hit path.
- Sleep-based tests are fragile: the negative test in Task 4/8, the logging test, the settle test, and `closingTheArenaMidCheckStopsIt`, which proves only that the tap was removed.
- The plan-mandated `CalibrationFixture` ignores `byCamera`. `PatternMeasurement` accepts detections <= 0 (a median of an empty list throws; no caller passes one).

## Other

- **`Settings` unknown-key merge** (`Settings.java:462`) relies on `KNOWN_KEYS` staying in sync with the keys it reads. A `containsKey` guard would enforce that itself (S).
- **`ArenaModel` / background**: a corrupt background resource clears to null instead of keeping the previous one (S).
- **`placeMainWindow`** clamps onto the first overlapping screen, not the largest overlap. `autoPlaceArena` logs an extra error if the main window is off-screen (S).
- **App exit** leaves the device open (JavaFX parity). A stale `OpenView` stays live until its IO job hits the generation check (unreachable in production).
- **`onlyIf` path match** for compose-app run from inside the module dir (build script, S). `openArenaAtLaunch` is not `@Volatile` (`AppState.kt:699`). Non-volatile `frameCount` in `CalculatedFPSCamera`. Stale or overlong comments and KDoc: the `RememberedCalibration` POLL_MILLIS wording, the "release re-check" comment, `calibratedAt` doc vs a remembered calibration, and the "within 2 px" prose vs the 5 px `DEFAULT_TOLERANCE`.
