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

import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.targets.model.Hit;

/**
 * Adds up the points of the regions each laser color hits. It runs everywhere: on the camera feed's
 * targets and the arena's.
 */
public class ShootForScore implements Exercise {
	private static final String POINTS_COL_NAME = "Score";

	private ExerciseHost host;
	private int redScore = 0;
	private int greenScore = 0;

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("Shoot for Score", "1.0", "phrack",
				"This exercise works with targets that have score tags "
						+ "assigned to regions. Any time a target region is hit, "
						+ "the number of points assigned to that region are added " + "to your total score.");
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		host.addColumn(POINTS_COL_NAME);
	}

	/**
	 * @return red's score, for tests
	 */
	int getRedScore() {
		return redScore;
	}

	/**
	 * @return green's score, for tests
	 */
	int getGreenScore() {
		return greenScore;
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		if (hit.isEmpty()) return;

		final Optional<String> points = hit.get().region().tag("points");
		if (points.isPresent()) {
			host.setColumnValue(POINTS_COL_NAME, points.get());

			if (shot.getColor().equals(ShotColor.RED) || shot.getColor().equals(ShotColor.INFRARED)) {
				redScore += Integer.parseInt(points.get());
			} else if (shot.getColor().equals(ShotColor.GREEN)) {
				greenScore += Integer.parseInt(points.get());
			}
		}

		host.showMessage(scoreMessage(redScore, greenScore));
	}

	/**
	 * @return the scores as the JavaFX app showed them: each color that has scored, else "score: 0"
	 */
	static String scoreMessage(int redScore, int greenScore) {
		if (redScore > 0 && greenScore > 0) {
			return String.format("red score: %d\ngreen score: %d", redScore, greenScore);
		} else if (redScore > 0) {
			return String.format("red score: %d", redScore);
		} else if (greenScore > 0) {
			return String.format("green score: %d", greenScore);
		}

		return "score: 0";
	}

	@Override
	public void onReset() {
		redScore = 0;
		greenScore = 0;
		host.showMessage("score: 0");
	}

	@Override
	public void stop() {}
}
