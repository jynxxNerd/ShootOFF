# Compose target placement — design

Date: 2026-09-28. Branch: `compose-ui`. Roadmap step 2, first sub-project (the exercise port is the second and gets its own spec).

## 1. Why

Since the Compose trial's Revision 1 there is no in-app arena view, so there is no way to add, move, resize or remove targets. Eight of the ten built-in exercises score or time whatever targets the shooter placed (Shoot for Score, Par for Score, Par Random Shot, Random Shoot, Timed Holster, ISSF, Dueling Tree, Steel Challenge), so none of them can be ported until placement exists. Steel Challenge also expects one of the bundled courses in `courses/steel_challenge/` to be loaded.

## 2. What the owner said

- The owner shoots projector targets only, but camera-feed targets (drawn over a real target the camera sees) must be supported too, since ShootOFF may be re-released open source.
- Editing happens on a Targets screen in the main window, not on the projector window.
- Scope includes named courses (save/load) and the background picker, matching the old app.
- The arena layout is restored at launch, as long as that makes starting fresh no harder than an empty arena.
- Live editing of the arena's own targets (approach 1), with the rule that targets an exercise placed are not the shooter's to edit and are not saved.

## 3. Success

- On the Targets screen the owner can add any target from `targets/`, move, resize (optionally keeping the aspect ratio), nudge and delete it, and see each change on the projector as it happens.
- The owner can pick a background, save the arena as a named course, load a course (including the bundled Steel Challenge courses, correctly scaled to the owner's 1280×720 arena), and clear the arena with a one-click undo.
- After a restart the arena comes back as it was left.
- The owner can do the same on the camera feed (except courses and backgrounds) and shots on a feed target register.
- Targets a running exercise placed are shown but cannot be grabbed, and never end up in a saved course.

## 4. The Targets screen

- Enable the rail's existing `Destination.TARGETS` entry (today disabled with "In the JavaFX app for now"); it keeps its place after Drills.
- An **Arena / Camera** switch at the top picks the surface being edited. Arena shows a preview of the arena at the projector's aspect ratio, drawn from the same `SurfaceTargets` the projector window draws. Camera shows the live feed with its own `SurfaceTargets`.
- **Add target** opens a list of the `.target` files in `targets/`, each with a thumbnail and its name. The chosen target is added at the centre of the surface at its natural size and is selected.
- **Mouse:** click selects (outline plus corner handles), click on empty space deselects; drag the body to move; drag a corner handle to resize; with Ctrl held, a corner drag keeps the aspect ratio.
- **Keyboard (when the screen has focus and a target is selected):** arrow keys move by 1 surface unit; Shift+arrow resizes by 1 unit (Right/Down grow, Left/Up shrink); Delete removes; Esc deselects.
- **Clamp:** a target may hang partly off the surface but at least part of it (a strip of 10 surface units, or the whole target if smaller) always stays on it, for moves, resizes and nudges.
- **Exercise targets** are drawn dimmed with a small "exercise" badge; clicking them selects nothing and they get no handles.
- **Toolbar (Arena):** Add target, Background…, Load course…, Save course…, Clear.
  - Background… offers the bundled backgrounds (`core/src/main/resources/arena/backgrounds`), an image file of the owner's choosing, or none.
  - Load course… replaces the shooter's arena targets and the background with the course's.
  - Save course… writes the shooter's arena targets and background to a named `.course` file; saving over an existing file asks first.
  - Clear removes the shooter's arena targets and the background; a snackbar offers Undo for a few seconds, which restores both.
- **Toolbar (Camera):** Add target and Clear only (Clear with the same Undo).
- Every change shows on the projector (or feed) immediately; there is no Apply step.

## 5. Parts and data flow

- **Ownership.** `SurfaceTargets.add` takes an owner, `USER` or `EXERCISE`, kept on the Compose side (core's `TargetSet` is unchanged; hit-testing doesn't care). `DrawnTarget` carries it so views can dim exercise targets. The Compose exercise host adds with `EXERCISE`; the Targets screen, course loading and layout restore add with `USER`. The exercise host keeps removing its own targets when the exercise stops.
- **`TargetEditor`** (no Compose UI): holds the selection for one surface and turns gestures into `TargetSet` placement changes and removals. It refuses `EXERCISE` targets and applies the clamp. The Compose screen is a thin layer over it: drawing handles, hit-testing clicks against target bounds (topmost first), and key handling.
- **`CourseLoader`:** applies a `Course` (read by core's `CourseIO`) to the `ArenaModel` — the background and `USER` targets — and builds a `Course` from the arena's `USER` targets and background for saving. It removes only `USER` targets before loading. When the course's resolution differs from the arena's size, positions and sizes scale by width and height factors, as the JavaFX `ProjectorArenaPane.setCourse` does. The plan confirms, against that JavaFX code, what a course's stored x/y mean (the bundled Steel Challenge courses have negative values) so bundled courses land where they did in the old app.
- **`LayoutMemory`:** after any change to the arena's `USER` targets or its background, it saves the layout as an ordinary course file, `arena-layout.course` in ShootOFF's home (`shootoff.home`), after 500 ms without further changes, written off the UI thread. At launch, once the arena is open, it loads that file through `CourseLoader`. Loading a course or clearing is just another change, so it is remembered the same way.
- **Camera-feed targets** are kept for the session only, as in the old app: a camera that moves between sessions makes a saved feed layout wrong.
- Tests give `LayoutMemory` and the course dialogs' default directory a scratch directory, so no test writes the owner's files.

## 6. Edge cases and errors

- **Calibrating:** the arena hides targets while its pattern shows (as today, via `ArenaModel.covered`). The Targets screen stays usable and shows: "Calibrating — the projector shows the pattern; your edits appear when it's done."
- **Arena closed:** editing works on the model; the projector shows the layout when the arena opens.
- **Running drill:** the shooter's edits apply immediately. Load course and Clear never touch `EXERCISE` targets.
- **Unreadable course or target file:** a short message in the screen's banner (the UiErrors style used elsewhere); the arena is left as it was. A course whose target files are partly missing loads the rest and names the missing ones in the message.
- **`arena-layout.course` missing, unreadable or naming a missing target file:** the arena starts empty (or without that target) and a log line says why; no dialog at launch.

## 7. Testing

- `TargetEditor`: select, move, resize with and without the aspect lock, nudge and Shift-nudge, delete, the clamp on each edge, refusing `EXERCISE` targets.
- `CourseLoader`: save/load round trip; a bundled Steel Challenge course scaled onto 1280×720; a missing target file skipped and reported; `EXERCISE` targets left out of saves and untouched by loads.
- `LayoutMemory`: writes after changes (after the quiet period, not per change), restores at launch, falls back to empty for a missing or corrupt file.
- Owner tagging: exercise-added targets are `EXERCISE` and are removed when the drill stops; `USER` targets stay.
- Hardware check by the owner: place, move and resize targets watching the wall; restart and see the layout return; load a Steel Challenge course; Clear then Undo; add a target on the camera feed and shoot it.

## 8. Out of scope

- Porting the ten built-in exercises (next sub-project, own spec).
- The session viewer and the Sessions screen.
- Saving camera-feed layouts; multiple-camera layouts.
- Rotating targets and editing a target's regions (the old target editor).
