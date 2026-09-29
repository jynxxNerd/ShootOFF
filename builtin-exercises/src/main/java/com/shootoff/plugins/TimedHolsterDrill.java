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

package com.shootoff.plugins;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.ButtonHandle;
import com.shootoff.exercise.Cancellable;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.RowStyle;
import com.shootoff.targets.model.Hit;

/**
 * Ten seconds to get ready, then round after round: "make ready" once, and after a random delay a beep to
 * draw and fire; each shot's Length is its time since the beep. Rounds alternate their shot timer rows'
 * shading. Pause stops the rounds; Resume makes ready again a second and a half later. It runs everywhere: on the
 * camera feed's targets and the arena's.
 * <p>
 * Par for Score and Par Random Shot build on it: {@link #doRound} is a round's start, and
 * {@link #nextRound} schedules the next one.
 */
public class TimedHolsterDrill implements Exercise {
	static final String LENGTH_COL_NAME = "Length";
	static final String PAUSE = "Pause";
	static final String RESUME = "Resume";
	static final String CLEAR_SHOTS = "Clear Shots";
	static final Duration START_DELAY = Duration.ofSeconds(10);
	static final Duration RESUME_DELAY = Duration.ofMillis(1500);
	/** The shared delay controls' values when the drill starts, as the JavaFX app's own controls had them */
	static final DelayRange DEFAULT_DELAY = new DelayRange(4, 8);
	static final RowStyle ROUND_SHADING = new RowStyle("lightgray");
	static final String MAKE_READY_SOUND = "sounds/voice/shootoff-makeready.wav";
	static final String BEEP_SOUND = "sounds/beep.wav";

	protected ExerciseHost host;
	private final Random random;
	private int delayMin = DEFAULT_DELAY.minSeconds();
	private int delayMax = DEFAULT_DELAY.maxSeconds();
	// false while paused
	private boolean repeatExercise = true;
	private long beepTime = 0;
	private boolean hadShot = false;
	private boolean coloredRows = false;
	private ButtonHandle pauseResumeButton;
	// The drill's next step (make ready, a round, the end of a par time): at most one is pending
	private Optional<Cancellable> nextStep = Optional.empty();

	public TimedHolsterDrill() {
		this(new Random());
	}

	/**
	 * @param random
	 *            for tests, one with a known seed
	 */
	TimedHolsterDrill(Random random) {
		this.random = random;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("Timed Holster Drill", "1.0", "phrack",
				"This exercise does not require a target, but one may be used "
						+ "to give the shooter something to shoot at. When the exercise "
						+ "is started you are asked to enter a range for randomly "
						+ "delayed starts. You are then given 10 seconds to position "
						+ "yourself. After a random wait (within the entered range) a "
						+ "beep tells you to draw their pistol from it's holster, "
						+ "fire at your target, and finally re-holster. This process is "
						+ "repeated as long as this exercise is on.");
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		initUI();
		initService();
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		// The JavaFX app shaded every row added while a round's shading was on
		if (coloredRows) host.styleLastRow(ROUND_SHADING);

		if (repeatExercise) {
			hadShot = true;
			setLength();
		}
	}

	protected void setLength() {
		final float drawShotLength = (float) (host.currentTimeMillis() - beepTime) / (float) 1000; // s
		host.setColumnValue(LENGTH_COL_NAME, String.format("%.2f", drawShotLength));
	}

	@Override
	public void onReset() {
		host.pauseShotDetection(true);
		cancelNextStep();
		paused();
		repeatExercise = true;
		pauseResumeButton.setLabel(PAUSE);
		resetValues();
		if (canRun()) scheduleNext(this::setupWait, START_DELAY);
	}

	@Override
	public void stop() {
		// The host cancels the pending step and removes what the drill added
		repeatExercise = false;
	}

	/**
	 * @return whether the drill has what its rounds need (Par Random Shot: a target with subtargets)
	 */
	protected boolean canRun() {
		return true;
	}

	protected void initUI() {
		pauseResumeButton = host.addButton(PAUSE, this::pauseOrResume);
		host.addButton(CLEAR_SHOTS, host::clearShots);
		host.addColumn(LENGTH_COL_NAME);

		host.setDelayedStart(DEFAULT_DELAY);
		host.onDelayedStartChanged(range -> {
			delayMin = range.minSeconds();
			delayMax = range.maxSeconds();
		});
	}

	protected void initService() {
		host.pauseShotDetection(true);
		resetValues();
		if (canRun()) scheduleNext(this::setupWait, START_DELAY);
	}

	// Pause stops the rounds where they are; Resume makes ready again after RESUME_DELAY
	private void pauseOrResume() {
		if (repeatExercise) {
			pauseResumeButton.setLabel(RESUME);
			repeatExercise = false;
			host.pauseShotDetection(true);
			cancelNextStep();
			paused();
		} else {
			pauseResumeButton.setLabel(PAUSE);
			repeatExercise = true;
			if (canRun()) scheduleNext(this::setupWait, RESUME_DELAY);
		}
	}

	/**
	 * The drill was paused: what a round in progress must forget
	 */
	protected void paused() {}

	private void setupWait() {
		if (!repeatExercise) return;

		host.pauseShotDetection(true);
		host.playSound(MAKE_READY_SOUND);
		scheduleNext(this::round, Duration.ofSeconds(randomDelay()));
	}

	private void round() {
		if (!repeatExercise) return;

		doRound();
	}

	/**
	 * A round's start: the beep, shot detection on and the round's clock started, then the next round.
	 */
	protected void doRound() {
		host.playSound(BEEP_SOUND);
		host.pauseShotDetection(false);
		startRoundTimer();
		nextRound();
	}

	/**
	 * Schedules the next round after a random delay.
	 */
	protected void nextRound() {
		scheduleNext(this::round, Duration.ofSeconds(setupRound()));
	}

	protected void scheduleNext(Runnable step, Duration delay) {
		cancelNextStep();
		nextStep = Optional.of(host.schedule(step, delay));
	}

	private void cancelNextStep() {
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.empty();
	}

	/**
	 * @return the delay, in seconds, before the next round
	 */
	protected int setupRound() {
		// Only toggle the color if there was a shot in the last round, otherwise the colors get out of sync
		// if the user misses a round (thus you can have a string of shots that is all gray or white even
		// though they were different rounds)
		if (hadShot) {
			coloredRows = !coloredRows;
			hadShot = false;
		}

		return randomDelay();
	}

	protected int randomDelay() {
		return random.nextInt((delayMax - delayMin) + 1) + delayMin;
	}

	protected void startRoundTimer() {
		beepTime = host.currentTimeMillis();
	}

	/**
	 * What the drill sets up at its start and on Reset
	 */
	protected void resetValues() {}
}
