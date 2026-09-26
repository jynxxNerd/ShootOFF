package com.shootoff.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.DisplayShot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.config.Configuration;
import com.shootoff.config.ConfigurationException;
import com.shootoff.gui.targets.TargetView;
import com.shootoff.plugins.ExerciseMetadata;
import com.shootoff.plugins.TrainingExercise;
import com.shootoff.targets.Hit;
import com.shootoff.targets.Target;
import com.shootoff.targets.io.TargetIO;
import com.shootoff.targets.model.HitTester;

import javafx.collections.FXCollections;
import javafx.scene.Group;

/**
 * Region commands run before the exercise hears the shot: the exercise still hears the hit even when
 * a command removes the target that was hit.
 */
public class TestCanvasManagerRegionCommands {
	@Rule public JavaFXThreadingRule javafxRule = new JavaFXThreadingRule();

	private Configuration config;
	private CanvasManager canvas;
	private TargetView resetTarget;
	private final List<Optional<Hit>> heard = new CopyOnWriteArrayList<>();

	@Before
	public void setUp() throws ConfigurationException {
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		config = new Configuration(new String[0]);
		// The reset command removes the target that was hit
		canvas = new CanvasManager(new Group(), () -> canvas.removeTarget(resetTarget), "Camera 1",
				FXCollections.observableArrayList());
		config.setExercise(new TrainingExercise() {
			@Override
			public void init() {}

			@Override
			public void targetUpdate(Target target, TargetChange change) {}

			@Override
			public ExerciseMetadata getInfo() {
				return new ExerciseMetadata("Listening drill", "1.0", "ShootOFF tests", "Writes down each hit");
			}

			@Override
			public void shotListener(Shot shot, Optional<Hit> hit) {
				heard.add(hit);
			}

			@Override
			public void reset(List<Target> targets) {}

			@Override
			public void destroy() {}
		});
	}

	@After
	public void tearDown() {
		config.setExercise(null);
	}

	@Test
	public void theExerciseHearsAHitWhoseResetCommandRemovedTheTarget() {
		resetTarget = (TargetView) canvas.addTarget(
				new TargetView(TargetIO.loadTarget(new File("targets/Reset.target"), false).get(), canvas, true));
		final double x = 41.5;
		final double y = 300.5;
		final com.shootoff.targets.model.Hit modelHit = HitTester.hit(canvas.getTargetSet(), x, y).get();

		canvas.addShot(new DisplayShot(new Shot(ShotColor.RED, x, y, 1000), config.getMarkerRadius()), false);

		// The command ran: the target is gone
		assertFalse(canvas.getTargets().contains(resetTarget));
		assertEquals(1, heard.size());
		assertTrue("the exercise heard a miss", heard.get(0).isPresent());
		assertSame(resetTarget, heard.get(0).get().getTarget());
		assertSame(resetTarget.getRegions().get(modelHit.region().index()), heard.get(0).get().getHitRegion());
	}
}
