# Session Recording and Viewer Fix — Design

- **Date:** 2026-09-26
- **Status:** Draft, awaiting review
- **Branch:** `fix-session-recording`

## 1. Problem

Replaying a session recorded with the projector arena open (for example, the Shoot Don't Shoot exercise) makes the session viewer throw on the JavaFX thread. In one session there were 95 exceptions:

- 83 `IndexOutOfBoundsException: Index -1`
- 9 `NullPointerException` (`oldTarget` is null)
- 3 `IllegalArgumentException: duplicate children added`

All come from `SessionCanvasManager.doEvent`/`undoEvent`/`animateTarget`. JavaFX logs each exception and carries on, so the failing events are skipped and the replay looks wrong. Shot markers replay; targets don't move, resize or disappear correctly.

**Root cause (recording side).** With the arena open, every arena target exists twice:

- one `MirroredTarget` on the projector window's `MirroredCanvasManager`
- one on the arena tab's `MirroredCanvasManager`

Both canvases are named `"arena"`, and both record target events into the same session list. So:

- Each target is recorded as added twice. The recorded session has 132 `targetAdded` events for 66 targets.
- The projector-side copy is sized and positioned (`mirrorSetDimensions`/`mirrorSetPosition` in `MirroredCanvasManager.addTarget(Target)`) before it's registered in its canvas's target list. `TargetView.getTargetIndex()` is -1 at that point, and `SessionRecorder.checkTarget` then synthesizes an extra "added" plus move/resize events with index -1.
- `MirroredCanvasManager.removeTarget` calls `CanvasManager.removeTarget` on both canvases, so each removal is recorded twice.

Shots don't have this problem: the mirrored copy of a shot passes `isMirroredShot = true`, and `CanvasManager.addArenaShot` skips recording it. Targets never got the equivalent treatment. The viewer code itself was not changed by the Java 21 modernization.

## 2. Goals

1. New sessions record each arena target exactly once, with valid indexes only: one add (with position and size), its moves/resizes, and one remove.
2. The viewer never throws while replaying any session, including already-recorded ones that contain duplicate adds and -1 indexes. Events it can't apply are skipped symmetrically for do and undo, and logged once per session.
3. Camera-tab (non-mirrored) recording behaves as today, except that targets entering through `addTarget(Target)` (e.g. loaded courses) now get an explicit add event. Today they only appear through the recorder's late-add fallback.

## 3. Design

### 3.1 One recording canvas per mirrored pair

- `CanvasManager` gets `private boolean recordsSessionEvents = true` with `setRecordsSessionEvents(boolean)` and `recordsSessionEvents()`.
- `ProjectorSlide`, where the two arena canvases are linked with `setMirroredManager`, calls `tabCanvasManager.setRecordsSessionEvents(false)`. Only the projector window's canvas records target events.

### 3.2 Adds recorded where targets enter a canvas

- `CanvasManager.addTarget(Target)` is the single path by which every target, mirrored or not, enters a canvas's `targets` list. After `targets.add(newTarget)`, if a session recorder is present and this canvas records, it records:
  - `recordTargetAdded(cameraName, newTarget)`
  - `recordTargetMoved` with the target's current position
  - `recordTargetResized` with the target's current size

  This captures any sizing or positioning done before registration.
- The existing `recordTargetAdded` call in `CanvasManager.addTarget(File, boolean)` is removed. It recorded the returned target, which for the arena is the tab copy.

### 3.3 Moves, resizes and removes guarded

- `TargetView` records `recordTargetMoved`/`recordTargetResized` only when its parent canvas is present, the parent records session events, and `getTargetIndex() >= 0` (registered). This applies to every existing record call site in `TargetView` (about 12), via one private helper so the condition lives in one place.
- `CanvasManager.removeTarget` records `recordTargetRemoved` only when this canvas records. The record happens before `targets.remove`, as today, so the index is valid.

### 3.4 Recorder safety net

`SessionRecorder.recordTargetMoved`, `recordTargetResized` and `recordTargetRemoved` return without recording, and without calling `checkTarget`, when `target.getTargetIndex() < 0`.

### 3.5 Tolerant viewer (`SessionCanvasManager`)

- **Skipped events.** A `Set<Event> skippedEvents` holds events that couldn't be applied. `doEvent` checks the target index against `targetViews` for:
  - `TARGET_REMOVED`, `TARGET_RESIZED`, `TARGET_MOVED`
  - `SHOT` with a target index (the animation path)

  An out-of-range index adds the event to `skippedEvents` and returns without applying it. For `SHOT`, the marker is still shown and only the target animation is skipped.
- **Symmetric undo.** `undoEvent` returns immediately for an event in `skippedEvents`, and for a `SHOT` it skips only the animation part. `undoEvent` also handles missing bookkeeping entries (e.g. no stored container, dimension or position for the event) by skipping instead of throwing a `NullPointerException`.
- **No duplicate nodes.** Adding a shot marker or re-adding a removed target's group first checks `canvas.getChildren().contains(node)`.
- **Failed target load.** If the target file for a `TARGET_ADDED` event can't be loaded, the event is skipped.
- **Logging.** One WARN line per session when the first event is skipped: `"Some session events could not be applied and were skipped"`. Each skipped event is logged at DEBUG.

## 4. Testing

- **Real-data regression test.**
  - The owner's recorded session with the bug, `sessions/2026-09-25 21.04.07.xml` (Shoot Don't Shoot; 132 adds, -1 indexes), is copied to `src/test/resources/sessions/arena_duplicate_targets.xml`.
  - A test loads it with the existing XML session reader, replays every event with `doEvent` in order, then `undoEvent` in reverse order, and asserts no exception.
  - It must fail before the viewer changes, reproducing the exceptions, and pass after.
- **Mirrored-pair recording test.**
  - Build a projector/tab `MirroredCanvasManager` pair as `ProjectorSlide` does, with the tab set not to record and a `SessionRecorder` in the configuration.
  - Add a target through each canvas, move it, and remove it.
  - Assert: exactly one `TARGET_ADDED` per target (followed by its move and resize), no event with index -1, and exactly one `TARGET_REMOVED` per target.
  - **Fallback if the pair can't be built in a test** (it needs `ProjectorArenaPane`): test the pieces on a plain `CanvasManager`/`MockCanvasManager`:
    - a canvas with `recordsSessionEvents=false` records no target events
    - `addTarget(Target)` on a recording canvas records add, move and resize
    - moves of a target not registered in its canvas record nothing

    The report must say which approach was used.
- **Recorder safety-net test.** Events for a target whose index is -1 produce no events and no synthetic add.
- **Existing tests.** The existing session tests (`TestSessionIO`, `TestSessionRecorder`) and the full suite still pass. Gate: `python3 scripts/test_summary.py compare build/test-results/test docs/superpowers/baseline/java8-tests.txt` shows 0 regressions / 0 new failures.
- **Hardware check (owner).** With the arena open and a session recording, run Shoot Don't Shoot, stop, and replay in View Sessions:
  - no exceptions in the log
  - targets appear, move and disappear correctly
  - the saved XML has one `targetAdded` per target

## 5. Out of scope

- Changes to the session file formats (XML/JSON) or the event model.
- Rewriting sessions that were already recorded. They replay best-effort through §3.5.
- Session video playback (VideoPlayerController), which was already reworked in the Java 21 work.
- Mirrored-target behavior other than recording (dragging, key handling).
