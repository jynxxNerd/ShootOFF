package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetDefinition;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;

// JUnit 4, as in the JavaFX app: the test names stay those of the Java 8 baseline
public class TestShootForScore {
	@Rule public TemporaryFolder temp = new TemporaryFolder();

	private FakeExerciseHost host;
	private ShootForScore sfs;
	private Hit tenRegionHit;
	private Hit fiveRegionHit;

	@Before
	public void setUp() throws IOException, TargetFormatException {
		final TargetDefinition bullseye = TargetDefinitions.load(Paths.get("targets", "SimpleBullseye_score.target"));
		for (final Region region : bullseye.regions()) {
			if (region.tag("points").equals(Optional.of("10"))) {
				tenRegionHit = new Hit(new TargetId(1), region, new Point(0, 0));
			} else if (region.tag("points").equals(Optional.of("5"))) {
				fiveRegionHit = new Hit(new TargetId(1), region, new Point(0, 0));
			}
		}

		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.newFolder("data").toPath());
		sfs = new ShootForScore();
		host.start(sfs);
	}

	private void shoot(ShotColor color, Optional<Hit> hit) {
		host.shoot(new Shot(color, 0, 0, 0, 2), hit);
	}

	// The messages shown since the last call
	private int seen = 0;

	private List<String> newMessages() {
		final List<String> messages = host.messages();
		final List<String> shown = messages.subList(seen, messages.size());
		seen = messages.size();
		return List.copyOf(shown);
	}

	@Test
	public void testReset() {
		host.reset();
		assertEquals(List.of("score: 0"), newMessages());
	}

	@Test
	public void testJustRed() {
		assertEquals(List.of("Score"), host.columns());

		// Miss
		shoot(ShotColor.RED, Optional.empty());
		assertEquals(List.of(), newMessages());

		// Hit ten
		shoot(ShotColor.RED, Optional.of(tenRegionHit));
		assertEquals(List.of("red score: 10"), newMessages());
		assertEquals(Map.of("Score", "10"), host.rows().get(1).values());

		// Hit five
		shoot(ShotColor.RED, Optional.of(fiveRegionHit));
		assertEquals(List.of("red score: 15"), newMessages());

		assertEquals(15, sfs.getRedScore());
		assertEquals(0, sfs.getGreenScore());

		host.reset();
		assertEquals(List.of("score: 0"), newMessages());

		assertEquals(0, sfs.getRedScore());
		assertEquals(0, sfs.getGreenScore());
	}

	@Test
	public void testJustGreen() {
		// Miss
		shoot(ShotColor.GREEN, Optional.empty());
		assertEquals(List.of(), newMessages());

		// Hit ten
		shoot(ShotColor.GREEN, Optional.of(tenRegionHit));
		assertEquals(List.of("green score: 10"), newMessages());

		// Hit five
		shoot(ShotColor.GREEN, Optional.of(fiveRegionHit));
		assertEquals(List.of("green score: 15"), newMessages());

		assertEquals(0, sfs.getRedScore());
		assertEquals(15, sfs.getGreenScore());

		host.reset();
		assertEquals(List.of("score: 0"), newMessages());

		assertEquals(0, sfs.getRedScore());
		assertEquals(0, sfs.getGreenScore());
	}

	@Test
	public void testRedAndGreen() {
		// Red hit ten
		shoot(ShotColor.RED, Optional.of(tenRegionHit));
		assertEquals(List.of("red score: 10"), newMessages());

		// Green hit five
		shoot(ShotColor.GREEN, Optional.of(fiveRegionHit));
		assertEquals(List.of("red score: 10\ngreen score: 5"), newMessages());

		assertEquals(10, sfs.getRedScore());
		assertEquals(5, sfs.getGreenScore());
	}
}
