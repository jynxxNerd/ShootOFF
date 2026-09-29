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
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.targets.model.Hit;

/**
 * Merge of TimedHolsterDrill and ShootForScore, with the addition of a PAR interval during which scores are
 * counted: the beep, then a chime at the end of the par time, when shot detection stops until the next
 * round.
 *
 * @author Edward Kort
 */
public class ParForScore extends TimedHolsterDrill {
	static final String POINTS_COL_NAME = "Score";
	/** The shared par time when the drill starts, as the JavaFX app's own control had it */
	static final double DEFAULT_PAR_TIME = 2.0;
	static final String CHIME_SOUND = "sounds/chime.wav";

	protected double parTime = DEFAULT_PAR_TIME;

	private int redScore = 0;
	private int greenScore = 0;

	// While the par time runs
	protected boolean countScore = false;

	public ParForScore() {
		super();
	}

	ParForScore(Random random) {
		super(random);
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("PAR Drill with Score", "1.0", "Edward Kort",
				"This exercise does not require a target, but one may be used "
						+ "to give the shooter something to shoot at. If a target with "
						+ "score areas is used, the scores are displayed and tracked. "
						+ "When the exercise is started you are asked to enter a range "
						+ "for randomly delayed starts, and for the interval (PAR time) "
						+ "in which those scores will be counted. You are then given 10 "
						+ "seconds to position yourself. After a random wait (within "
						+ "the entered range) a beep tells you to draw the pistol from "
						+ "its holster and fire at your target; a chime signals the end "
						+ "of the Par time, to finally re-holster. This process is "
						+ "repeated as long as this exercise is on.");
	}

	@Override
	protected void initUI() {
		super.initUI();
		host.addColumn(POINTS_COL_NAME);

		host.setParTime(DEFAULT_PAR_TIME);
		host.onParTimeChanged(seconds -> parTime = seconds);
	}

	@Override
	protected void doRound() {
		host.playSound(BEEP_SOUND);
		host.pauseShotDetection(false);
		startRoundTimer();
		startParTime();
	}

	/**
	 * Counts the scores until the chime at the end of the par time.
	 */
	protected void startParTime() {
		countScore = true;
		scheduleNext(this::endParTime, Duration.ofMillis(Math.round(parTime * 1000)));
	}

	private void endParTime() {
		host.playSound(CHIME_SOUND);
		host.pauseShotDetection(true);
		countScore = false;
		nextRound();
	}

	@Override
	protected void paused() {
		countScore = false;
	}

	/*
	 * This method merges shotListener for TimedHolsterDrill and ShootForScore.
	 */
	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		super.onShot(shot, hit);

		if (hit.isEmpty() || !countScore) return;

		final Optional<String> points = hit.get().region().tag("points");
		if (points.isPresent()) setPoints(shot.getColor(), points.get());
	}

	protected void setPoints(ShotColor shotColor, String points) {
		host.setColumnValue(POINTS_COL_NAME, points);

		if (shotColor.equals(ShotColor.RED) || shotColor.equals(ShotColor.INFRARED)) {
			redScore += Integer.parseInt(points);
		} else if (shotColor.equals(ShotColor.GREEN)) {
			greenScore += Integer.parseInt(points);
		}

		host.showMessage(ShootForScore.scoreMessage(redScore, greenScore));
	}

	@Override
	protected void resetValues() {
		redScore = 0;
		greenScore = 0;
		host.showMessage("score: 0");
	}
}
