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

package com.shootoff.exercise;

import java.util.List;
import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.targets.model.Hit;

/**
 * A training exercise that runs on any ShootOFF user interface. It only talks to its
 * {@link ExerciseHost}. The host calls every method here on the exercise's own thread, one call at a
 * time, so an exercise needs no locking.
 * <p>
 * A plugin jar declares its exercise in <tt>shootoff.xml</tt>:
 * <tt>&lt;shootoffExercise apiVersion="2" exerciseClass="..." /&gt;</tt>. The class needs a public
 * no-argument constructor that does no work: ShootOFF creates one instance to read its metadata, and
 * a fresh instance every time the exercise starts.
 */
public interface Exercise {
	ExerciseMetadata metadata();

	/**
	 * Called once when the exercise starts. Keep <tt>host</tt>: it is the exercise's only way to
	 * show things, play sounds and schedule work.
	 */
	void start(ExerciseHost host);

	/**
	 * Called for every detected shot.
	 *
	 * @param shot
	 *            the shot, in the surface's coordinates (arena coordinates on the projector)
	 * @param hit
	 *            the topmost visible region of a visible target under the shot, if any
	 */
	void onShot(Shot shot, Optional<Hit> hit);

	/**
	 * Called when a target is added to or removed from the surface, by the exercise or the user.
	 */
	default void onTargetsChanged(List<TargetHandle> targets) {}

	/**
	 * Called when the user presses Reset or shoots a reset target.
	 */
	void onReset();

	/**
	 * Called once when the exercise ends. The host then cancels its scheduled tasks and removes
	 * everything it added, so an exercise only has to stop its own threads, if it made any.
	 */
	void stop();
}
