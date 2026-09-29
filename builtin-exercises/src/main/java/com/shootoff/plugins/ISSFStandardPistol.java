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
import java.util.HashMap;
import java.util.Map;
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
 * The ISSF 25 m standard pistol event: four series of five shots in 150 s, then four in 20 s, then four in
 * 10 s. Each series starts with a beep and ends at its time or its fifth shot. Pause abandons the series
 * under way (its points come off) and Resume shoots it again after "make ready". It runs everywhere: on
 * the camera feed's targets and the arena's.
 */
public class ISSFStandardPistol implements Exercise {
	static final String SCORE_COL_NAME = "Score";
	static final String ROUND_COL_NAME = "Round";
	static final String PAUSE = "Pause";
	static final String RESUME = "Resume";
	static final Duration START_DELAY = Duration.ofSeconds(10);
	static final Duration RESUME_DELAY = Duration.ofMillis(1500);
	/** The shared delay controls' values when the event starts, as the JavaFX app's own controls had them */
	static final DelayRange DEFAULT_DELAY = new DelayRange(4, 8);
	static final RowStyle SERIES_SHADING = new RowStyle("lightgray");
	static final String MAKE_READY_SOUND = "sounds/voice/shootoff-makeready.wav";
	static final String BEEP_SOUND = "sounds/beep.wav";
	static final String ROUND_OVER_SOUND = "sounds/voice/shootoff-roundover.wav";
	private static final int[] ROUND_TIMES = { 150, 20, 10 };

	private final Random random;
	private ExerciseHost host;
	private ButtonHandle pauseResumeButton;
	private int roundTimeIndex = 0;
	private int round = 1;
	private int shotCount = 0;
	private int runningScore = 0;
	// The points of the series under way, which a pause takes off
	private int seriesScore = 0;
	private final Map<Integer, Integer> sessionScores = new HashMap<>();
	private int delayMin = DEFAULT_DELAY.minSeconds();
	private int delayMax = DEFAULT_DELAY.maxSeconds();
	// false while paused
	private boolean repeatExercise = true;
	private boolean coloredRows = false;
	private boolean seriesShaded = false;
	private boolean eventOver = false;
	// Make ready or the next series' start, and the end of the series under way
	private Optional<Cancellable> nextStep = Optional.empty();
	private Optional<Cancellable> endRound = Optional.empty();

	public ISSFStandardPistol() {
		this(new Random());
	}

	/**
	 * @param random
	 *            for tests, one with a known seed
	 */
	ISSFStandardPistol(Random random) {
		this.random = random;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("ISSF 25M Standard Pistol", "1.0", "phrack",
				"This exercise implements the ISSF event describe at: "
						+ "http://www.pistol.org.au/events/disciplines/issf. You "
						+ "can use any scored target with this exercise, but use "
						+ "the ISSF target for the most authentic experience.");
	}

	private void setInitialValues() {
		roundTimeIndex = 0;
		round = 1;
		shotCount = 0;
		runningScore = 0;
		seriesScore = 0;
		eventOver = false;

		for (final int time : ROUND_TIMES) {
			sessionScores.put(time, 0);
		}
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		setInitialValues();

		host.pauseShotDetection(true);
		host.setDelayedStart(DEFAULT_DELAY);
		host.onDelayedStartChanged(range -> {
			delayMin = range.minSeconds();
			delayMax = range.maxSeconds();
		});
		pauseResumeButton = host.addButton(PAUSE, this::pauseOrResume);
		host.addColumn(SCORE_COL_NAME);
		host.addColumn(ROUND_COL_NAME);

		scheduleNext(this::setupWait, START_DELAY);
	}

	private void setupWait() {
		if (!repeatExercise) return;

		host.playSound(MAKE_READY_SOUND);
		scheduleNext(this::startRound, Duration.ofSeconds(randomDelay()));
	}

	private void startRound() {
		shotCount = 0;
		seriesScore = 0;

		if (!repeatExercise) return;

		seriesShaded = coloredRows;
		coloredRows = !coloredRows;

		host.playSound(BEEP_SOUND);
		host.pauseShotDetection(false);
		endRound = Optional.of(host.schedule(this::endRound, Duration.ofSeconds(ROUND_TIMES[roundTimeIndex])));
	}

	private void endRound() {
		endRound = Optional.empty();
		if (!repeatExercise) return;

		host.pauseShotDetection(true);
		host.playSound(ROUND_OVER_SOUND);

		final int randomDelay = randomDelay();

		if (round < 4) {
			// Go to next round
			round++;
			scheduleNext(this::startRound, Duration.ofSeconds(randomDelay));
		} else if (roundTimeIndex < ROUND_TIMES.length - 1) {
			// Go to round 1 for next time
			round = 1;
			roundTimeIndex++;
			scheduleNext(this::startRound, Duration.ofSeconds(randomDelay));
		} else {
			host.say("Event over... Your score is " + runningScore);
			host.pauseShotDetection(false);
			// The event is over: Reset starts it again
			eventOver = true;
		}
	}

	private int randomDelay() {
		return random.nextInt((delayMax - delayMin) + 1) + delayMin;
	}

	private void scheduleNext(Runnable step, Duration delay) {
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.of(host.schedule(step, delay));
	}

	private void cancelPending() {
		nextStep.ifPresent(Cancellable::cancel);
		nextStep = Optional.empty();
		endRound.ifPresent(Cancellable::cancel);
		endRound = Optional.empty();
	}

	private void pauseOrResume() {
		if (repeatExercise) {
			pauseResumeButton.setLabel(RESUME);
			repeatExercise = false;
			host.pauseShotDetection(true);
			if (endRound.isPresent()) abandonSeries();
			cancelPending();
		} else {
			pauseResumeButton.setLabel(PAUSE);
			repeatExercise = true;
			if (eventOver) {
				host.pauseShotDetection(false);
			} else {
				scheduleNext(this::setupWait, RESUME_DELAY);
			}
		}
	}

	// The series under way is shot again after the pause: its points come off
	private void abandonSeries() {
		final int time = ROUND_TIMES[roundTimeIndex];
		sessionScores.put(time, sessionScores.get(time) - seriesScore);
		runningScore -= seriesScore;
		seriesScore = 0;
		shotCount = 0;
		// It keeps its shading when shot again
		coloredRows = seriesShaded;
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		if (seriesShaded) host.styleLastRow(SERIES_SHADING);

		shotCount++;

		int hitScore = 0;

		if (hit.isPresent()) {
			final Optional<String> points = hit.get().region().tag("points");

			if (points.isPresent()) {
				hitScore = Integer.parseInt(points.get());
				sessionScores.put(ROUND_TIMES[roundTimeIndex], sessionScores.get(ROUND_TIMES[roundTimeIndex]) + hitScore);
				runningScore += hitScore;
				seriesScore += hitScore;
			}

			final StringBuilder message = new StringBuilder();

			for (final Integer time : ROUND_TIMES) {
				message.append(String.format("%ss score: %d\n", time, sessionScores.get(time)));
			}

			host.showMessage(message.toString() + "total score: " + runningScore);
		}

		final String currentRound = String.format("R%d (%ds)", round, ROUND_TIMES[roundTimeIndex]);
		host.setColumnValue(SCORE_COL_NAME, String.valueOf(hitScore));
		host.setColumnValue(ROUND_COL_NAME, currentRound);

		// The fifth shot ends the series early
		if (shotCount == 5 && endRound.isPresent()) {
			host.pauseShotDetection(true);
			endRound.get().cancel();
			endRound();
		}
	}

	@Override
	public void onReset() {
		host.pauseShotDetection(true);
		cancelPending();

		setInitialValues();
		seriesShaded = false;
		host.showMessage("");

		repeatExercise = true;
		pauseResumeButton.setLabel(PAUSE);
		scheduleNext(this::setupWait, START_DELAY);
	}

	@Override
	public void stop() {
		// The host cancels the pending steps and removes what the exercise added
		repeatExercise = false;
	}
}
