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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Stack;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetId;

/**
 * Calls out a random sequence of a target's subtargets for the shooter to hit in order; a miss or a wrong
 * subtarget repeats the one due. It runs everywhere: on the camera feed's targets and the arena's.
 */
public class RandomShoot implements Exercise {
	static final String WARNING_SOUND = "sounds/voice/shootoff-subtargets-warning.wav";
	static final String SHOOT_SOUND = "sounds/voice/shootoff-shoot.wav";
	static final String AND_SOUND = "sounds/voice/shootoff-and.wav";

	private final Random rng;
	private ExerciseHost host;
	// The target whose subtargets are called out
	private Optional<TargetId> selectedTarget = Optional.empty();
	private final List<String> subtargets = new ArrayList<>();
	private final Stack<Integer> currentSubtargets = new Stack<>();

	public RandomShoot() {
		this(new Random());
	}

	/**
	 * @param rng
	 *            for tests, one with a known seed
	 */
	RandomShoot(Random rng) {
		this.rng = rng;
	}

	@Override
	public ExerciseMetadata metadata() {
		return new ExerciseMetadata("Random Shoot", "1.0", "phrack",
				"This exercise works with targets that have subtarget tags "
						+ "assigned to some regions. Subtargets are selected at random "
						+ "and the shooter is asked to shoot those subtargets in order. "
						+ "If a subtarget is shot out of order or the shooter misses, the "
						+ "name of the subtarget that should have been shot is repeated.");
	}

	@Override
	public void start(ExerciseHost host) {
		this.host = host;
		if (fetchSubtargets(host.targets())) startRound();
	}

	/**
	 * As the JavaFX app did: while no target is called out, a target with subtargets arriving starts the
	 * call-outs; the called-out target leaving starts them afresh on another, or warns that there is none.
	 */
	@Override
	public void onTargetsChanged(List<TargetHandle> targets) {
		if (selectedTarget.isPresent()) {
			final TargetId selected = selectedTarget.get();
			if (targets.stream().noneMatch(target -> target.id().equals(selected))) {
				selectedTarget = Optional.empty();
				if (fetchSubtargets(targets)) startRound();
			}
		} else if (targets.stream().anyMatch(RandomShoot::hasSubtargets)) {
			if (fetchSubtargets(targets)) startRound();
		}
	}

	private static boolean hasSubtargets(TargetHandle target) {
		return target.definition().regions().stream().anyMatch(region -> region.tags().containsKey("subtarget"));
	}

	private void startRound() {
		pickSubtargets();
		saySubtargets();
	}

	/**
	 * @return every subtarget of the called-out target, for tests
	 */
	List<String> getSubtargets() {
		return subtargets;
	}

	/**
	 * @return the subtargets still to hit, the next on top, for tests
	 */
	Stack<Integer> getCurrentSubtargets() {
		return currentSubtargets;
	}

	/**
	 * Finds the first target with subtargets and gets its regions. If there is none, warns the shooter.
	 *
	 * @return <tt>true</tt> if there are subtargets to call out
	 */
	private boolean fetchSubtargets(List<TargetHandle> targets) {
		subtargets.clear();
		currentSubtargets.clear();

		boolean foundTarget = false;
		for (final TargetHandle target : targets) {
			for (final Region region : target.definition().regions()) {
				if (region.tags().containsKey("subtarget")) {
					subtargets.add(region.tags().get("subtarget"));
					foundTarget = true;
				}
			}

			if (foundTarget) {
				selectedTarget = Optional.of(target.id());
				break;
			}
		}

		if (foundTarget && subtargets.size() > 0) {
			return true;
		} else {
			host.playSound(WARNING_SOUND);
			return false;
		}
	}

	private void pickSubtargets() {
		currentSubtargets.clear();

		final int count = rng.nextInt((subtargets.size() - 1) + 1) + 1;
		for (final int i : rng.ints(count, 0, subtargets.size()).toArray()) {
			currentSubtargets.push(Integer.valueOf(i));
		}
	}

	private static String voice(String subtarget) {
		return String.format("sounds/voice/shootoff-%s.wav", subtarget);
	}

	private void saySubtargets() {
		final List<String> sounds = new ArrayList<>();
		sounds.add(SHOOT_SOUND);

		final Stack<Integer> temp = new Stack<>();
		temp.addAll(currentSubtargets);
		Collections.reverse(temp);
		final Iterator<Integer> it = temp.iterator();

		while (it.hasNext()) {
			final Integer index = it.next();

			if (!it.hasNext() && currentSubtargets.size() > 1) sounds.add(AND_SOUND);

			final String targetNameSound = voice(subtargets.get(index));

			if (host.hasSound(targetNameSound)) {
				sounds.add(targetNameSound);
			} else {
				// No voice actor's recording for this subtarget: text to speech instead
				saySubtargetsTTS();
				return;
			}
		}

		host.playSounds(sounds);
	}

	private void saySubtargetsTTS() {
		final StringBuilder sentence = new StringBuilder("shoot subtarget ");

		sentence.append(subtargets.get(currentSubtargets.get(currentSubtargets.size() - 1)));

		for (int i = currentSubtargets.size() - 2; i >= 0; i--) {
			sentence.append(" then ");
			sentence.append(subtargets.get(currentSubtargets.get(i)));
		}

		host.say(sentence.toString());
	}

	private void sayCurrentSubtarget() {
		final int subtargetIndex = currentSubtargets.peek();

		if (subtargets.size() == 0 || subtargetIndex > subtargets.size()) {
			// There are no subtargets left, or the index is of one that doesn't exist: start again
			startRound();
			return;
		}

		final String targetNameSound = voice(subtargets.get(subtargetIndex));

		if (host.hasSound(targetNameSound)) {
			host.playSounds(List.of(SHOOT_SOUND, targetNameSound));
		} else {
			host.say("shoot " + subtargets.get(currentSubtargets.peek()));
		}
	}

	@Override
	public void onShot(Shot shot, Optional<Hit> hit) {
		if (currentSubtargets.isEmpty()) return;

		if (hit.isPresent()) {
			final Optional<String> subtargetValue = hit.get().region().tag("subtarget");
			if (subtargetValue.isPresent() && subtargetValue.get().equals(subtargets.get(currentSubtargets.peek()))) {
				currentSubtargets.pop();
			} else {
				sayCurrentSubtarget();
			}

			if (currentSubtargets.isEmpty()) startRound();
		} else {
			sayCurrentSubtarget();
		}
	}

	@Override
	public void onReset() {
		if (fetchSubtargets(host.targets())) startRound();
	}

	@Override
	public void stop() {}
}
