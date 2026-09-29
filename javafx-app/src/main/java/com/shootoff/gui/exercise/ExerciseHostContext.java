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
import java.util.function.Supplier;

import com.shootoff.camera.CamerasSupervisor;
import com.shootoff.config.Configuration;
import com.shootoff.gui.CanvasManager;
import com.shootoff.gui.pane.ProjectorArenaPane;
import com.shootoff.plugins.TrainingExerciseView;

/**
 * What a {@link JavaFxExerciseHost} works with.
 *
 * @param canvas
 *            the surface: the arena's canvas, or a camera feed's
 * @param arena
 *            the projector arena, for a projector exercise
 * @param feeds
 *            the canvases that show {@link JavaFxExerciseHost#showMessage} banners
 * @param resources
 *            the exercise's class loader; for a plugin, its jar's
 * @param everywhere
 *            for a camera exercise that runs everywhere (exercise port spec §5): the canvases whose targets
 *            it sees, as they are when it asks (every camera's, and the arena window's while the arena is
 *            open). It takes arena shots too.
 */
public record ExerciseHostContext(Configuration config, CamerasSupervisor cameras, TrainingExerciseView view,
		CanvasManager canvas, Optional<ProjectorArenaPane> arena, List<CanvasManager> feeds, ClassLoader resources,
		SoundOutput sounds, Optional<Supplier<List<CanvasManager>>> everywhere) {
	public ExerciseHostContext {
		feeds = List.copyOf(feeds);
	}

	/**
	 * A context for an exercise that sees only its own canvas's targets and shots.
	 */
	public ExerciseHostContext(Configuration config, CamerasSupervisor cameras, TrainingExerciseView view,
			CanvasManager canvas, Optional<ProjectorArenaPane> arena, List<CanvasManager> feeds, ClassLoader resources,
			SoundOutput sounds) {
		this(config, cameras, view, canvas, arena, feeds, resources, sounds, Optional.empty());
	}
}
