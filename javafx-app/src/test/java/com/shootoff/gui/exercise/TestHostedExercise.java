package com.shootoff.gui.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.shootoff.camera.Shot;
import com.shootoff.exercise.Exercise;
import com.shootoff.exercise.ExerciseHost;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.ShootForScore;
import com.shootoff.plugins.SteelChallenge;
import com.shootoff.plugins.engine.V2ExerciseEntry;
import com.shootoff.targets.model.Hit;

class TestHostedExercise {
	public static final class Drill implements Exercise {
		private final boolean projectorOnly;

		public Drill() {
			this(true);
		}

		Drill(boolean projectorOnly) {
			this.projectorOnly = projectorOnly;
		}

		@Override
		public ExerciseMetadata metadata() {
			return new ExerciseMetadata("Drill", "2.0", "ShootOFF tests", "A v2 drill", projectorOnly);
		}

		@Override
		public void start(ExerciseHost host) {}

		@Override
		public void onShot(Shot shot, Optional<Hit> hit) {}

		@Override
		public void onReset() {}

		@Override
		public void stop() {}
	}

	@Test
	void hostedExercisesAreProjectorExercisesWhenTheirMetadataSaysSo() {
		final HostedExercise projectorItem = new HostedExercise(new V2ExerciseEntry(Drill.class, new Drill(true).metadata()));
		final HostedExercise feedItem = new HostedExercise(new V2ExerciseEntry(Drill.class, new Drill(false).metadata()));

		assertTrue(HostedExercise.isProjectorExercise(projectorItem));
		assertFalse(HostedExercise.isProjectorExercise(feedItem));
		assertTrue(HostedExercise.isProjectorExercise(new SteelChallenge()));
		assertFalse(HostedExercise.isProjectorExercise(new ShootForScore()));

		// A menu item has no host: its callbacks do nothing
		projectorItem.init();
		projectorItem.shotListener(new Shot(null, 0, 0, 0), Optional.empty());
		projectorItem.reset(List.of());
		projectorItem.destroy();
		assertEquals("Drill", projectorItem.getInfo().getName());
	}
}
