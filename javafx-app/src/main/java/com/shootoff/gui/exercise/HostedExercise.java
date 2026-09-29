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

package com.shootoff.gui.exercise;

import java.util.List;
import java.util.Optional;

import com.shootoff.camera.Shot;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.ProjectorTrainingExerciseBase;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.targets.Hit;
import com.shootoff.targets.Target;

/**
 * A v2 exercise, seen by the JavaFX app as a {@link TrainingExercise}, so shots, target changes,
 * resets and exercise switching reach it through the same code as v1 exercises. Without a host it is
 * a Training menu item: picking it starts a fresh instance of its class on a new host.
 */
public final class HostedExercise implements TrainingExercise {
	private final V2ExerciseEntry entry;
	private final Optional<JavaFxExerciseHost> host;

	/**
	 * A Training menu item.
	 */
	public HostedExercise(V2ExerciseEntry entry) {
		this.entry = entry;
		host = Optional.empty();
	}

	/**
	 * A running exercise.
	 */
	public HostedExercise(V2ExerciseEntry entry, JavaFxExerciseHost host) {
		this.entry = entry;
		this.host = Optional.of(host);
	}

	/**
	 * @return <tt>true</tt> for exercises that only run on the projector arena, of either API version
	 */
	public static boolean isProjectorExercise(TrainingExercise exercise) {
		return exercise instanceof ProjectorTrainingExerciseBase
				|| (exercise instanceof HostedExercise hosted && hosted.isProjector());
	}

	public V2ExerciseEntry getEntry() {
		return entry;
	}

	public boolean isProjector() {
		return entry.isProjectorOnly();
	}

	@Override
	public void init() {
		host.ifPresent(JavaFxExerciseHost::start);
	}

	@Override
	public void targetUpdate(Target target, TargetChange change) {
		host.ifPresent(JavaFxExerciseHost::targetsChanged);
	}

	@Override
	public ExerciseMetadata getInfo() {
		return entry.metadata();
	}

	@Override
	public void shotListener(Shot shot, Optional<Hit> hit) {
		host.ifPresent(h -> h.deliverShot(shot, hit.flatMap(Hit::getModelHit)));
	}

	@Override
	public void reset(List<Target> targets) {
		host.ifPresent(JavaFxExerciseHost::reset);
	}

	/**
	 * @return whether this is a running exercise that has paused shot detection (false for a menu item)
	 */
	public boolean isShotDetectionPaused() {
		return host.map(JavaFxExerciseHost::isShotDetectionPaused).orElse(false);
	}

	@Override
	public void destroy() {
		host.ifPresent(JavaFxExerciseHost::stop);
	}
}
