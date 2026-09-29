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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;

/**
 * Merge of ParForScore and RandomShoot: each round calls out a random subtarget of the first target that
 * has subtargets, instead of the beep, and only hits on it score. Without such a target it warns and runs
 * no rounds until Reset finds one.
 *
 * @author Edward Kort
 */
public class ParRandomShot extends ParForScore {
	private final List<String> subtargets = new ArrayList<>();
	private final Random rng;
	private boolean foundTarget;
	private int currentSubtarget;

	public ParRandomShot() {
		this(new Random());
	}

	/**
	 * @param random
	 *            for tests, one with a known seed; it picks both the delays and the subtargets
	 */
	ParRandomShot(Random random) {
		super(random);
		rng = random;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("PAR Drill with a random Subtarget", "1.0", "Edward Kort",
				"This exercise works with targets that have subtarget tags "
						+ "assigned to some regions. When the exercise is started you "
						+ "are asked to enter a range for randomly delayed starts, and "
						+ "for the interval (PAR time) in which those scores will be "
						+ "counted. You are then given 10 seconds to position yourself. "
						+ "After a random wait (within the entered range), a randomly "
						+ "selected subtarget is called out, telling you to draw the "
						+ "pistol from its holster and fire at your target; a chime "
						+ "signals the end of the Par time, to finally re-holster. The "
						+ "score for each shot, performed during the PAR time and hitting "
						+ "the subtarget, is points assigned to that subtarget (or 1 if "
						+ "there is no assignment). This process is repeated as long as " + "this exercise is on.");
	}

	@Override
	protected boolean canRun() {
		return foundTarget;
	}

	// The call-out replaces the beep
	@Override
	protected void doRound() {
		pickSubtarget();
		saySubtarget();
		host.pauseShotDetection(false);
		startRoundTimer();
		startParTime();
	}

	// Rows keep their plain shading here, as in the JavaFX app
	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		setLength();

		if (!foundTarget || hit.isEmpty() || !countScore) return;

		final String subtarget = subtargets.get(currentSubtarget);
		final Region region = hit.get().region();
		if (region.tag("subtarget").equals(Optional.of(subtarget))) {
			setPoints(shot.getColor(), region.tag("points").orElse("1"));
		}
	}

	@Override
	protected void resetValues() {
		super.resetValues();
		fetchSubtargets(host.targets());
	}

	/**
	 * @return every subtarget of the called-out target, for tests
	 */
	List<String> getSubtargets() {
		return subtargets;
	}

	/**
	 * @return the subtarget called out last, for tests
	 */
	String getCurrentSubtarget() {
		return subtargets.get(currentSubtarget);
	}

	/**
	 * @see RandomShoot
	 */
	private void fetchSubtargets(List<TargetHandle> targets) {
		subtargets.clear();

		foundTarget = false;
		for (final TargetHandle target : targets) {
			for (final Region region : target.definition().regions()) {
				if (region.tags().containsKey("subtarget")) {
					subtargets.add(region.tags().get("subtarget"));
					foundTarget = true;
				}
			}

			if (foundTarget) break;
		}

		if (!foundTarget) host.playSound(RandomShoot.WARNING_SOUND);
	}

	private void pickSubtarget() {
		if (foundTarget) currentSubtarget = rng.nextInt(subtargets.size());
	}

	private void saySubtarget() {
		if (!foundTarget) return;

		final String subValue = subtargets.get(currentSubtarget);
		final String targetNameSound = String.format("sounds/voice/shootoff-%s.wav", subValue);

		if (host.hasSound(targetNameSound)) {
			host.playSound(targetNameSound);
		} else {
			// No voice actor's recording for this subtarget: text to speech instead
			host.say(subValue);
		}
	}
}
