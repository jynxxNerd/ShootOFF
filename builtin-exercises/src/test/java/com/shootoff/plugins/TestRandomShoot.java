package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;

// JUnit 4, as in the JavaFX app: the test names stay those of the Java 8 baseline
public class TestRandomShoot {
	@Rule public TemporaryFolder temp = new TemporaryFolder();

	private String previousHome;
	private FakeExerciseHost host;
	private Random rng;
	private int soundsSeen = 0;
	private int spokenSeen = 0;

	@Before
	public void setUp() throws IOException {
		// The voice files are ShootOFF's own, in its sounds/voice folder
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.newFolder("data").toPath());
		rng = new Random(15); // Changing this seed will cause tests to fail
	}

	@After
	public void tearDown() {
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	// The sounds played since the last call
	private List<String> newSounds() {
		final List<String> sounds = host.sounds();
		final List<String> played = List.copyOf(sounds.subList(soundsSeen, sounds.size()));
		soundsSeen = sounds.size();
		return played;
	}

	// The sentences spoken since the last call
	private List<String> newSpoken() {
		final List<String> spoken = host.spoken();
		final List<String> said = List.copyOf(spoken.subList(spokenSeen, spoken.size()));
		spokenSeen = spoken.size();
		return said;
	}

	private void shoot(Optional<Hit> hit) {
		host.shoot(new Shot(ShotColor.GREEN, 0, 0, 0, 2), hit);
	}

	private static Hit hit(TargetHandle target, String subtarget) {
		for (final Region region : target.definition().regions()) {
			if (region.tag("subtarget").equals(Optional.of(subtarget))) {
				return new Hit(target.id(), region, new Point(0, 0));
			}
		}
		throw new AssertionError("No subtarget " + subtarget);
	}

	@Test
	public void testNoTarget() {
		host.start(new RandomShoot(rng));

		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());

		host.reset();

		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());
	}

	@Test
	public void testFiveSmallTarget() {
		final TargetHandle bullseyeFiveTarget = host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);

		// Make sure initial state makes sense

		assertEquals(5, rs.getSubtargets().size());

		assertTrue(rs.getSubtargets().contains("1"));
		assertTrue(rs.getSubtargets().contains("2"));
		assertTrue(rs.getSubtargets().contains("3"));
		assertTrue(rs.getSubtargets().contains("4"));
		assertTrue(rs.getSubtargets().contains("5"));

		final String firstSubtarget = rs.getSubtargets().get(rs.getCurrentSubtargets().peek());

		assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));

		// Simulate missing a shot

		shoot(Optional.empty());

		assertEquals(List.of(RandomShoot.SHOOT_SOUND, String.format("sounds/voice/shootoff-%s.wav", firstSubtarget)),
				newSounds());

		// Simulate a hit

		final int oldSize = rs.getCurrentSubtargets().size();

		shoot(Optional.of(hit(bullseyeFiveTarget, firstSubtarget)));

		if (oldSize > 1) {
			assertEquals(oldSize - 1, rs.getCurrentSubtargets().size());
		} else {
			// The round is over and the next one is called out
			assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));
		}
	}

	@Test
	public void testNoSoundFilesForSubtargetNames() throws URISyntaxException {
		final String missingSounds = Paths
				.get(TestRandomShoot.class.getResource("/test_missing_sound_files.target").toURI()).toString();
		host.addTarget(missingSounds, 0, 0).get();
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);

		// Make sure initial state makes sense

		assertEquals(5, rs.getSubtargets().size());

		final String firstSubtarget = rs.getSubtargets().get(rs.getCurrentSubtargets().peek());

		assertEquals(List.of("shoot subtarget undefined_region_name_5 then undefined_region_name_3"), newSpoken());

		// Simulate missing a shot

		shoot(Optional.empty());

		assertEquals(List.of("shoot " + firstSubtarget), newSpoken());
	}

	// A target with subtargets arriving while none is called out starts the call-outs
	@Test
	public void aTargetWithSubtargetsArrivingStartsTheCallOuts() {
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);
		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());

		final TargetHandle plain = host.addTarget("targets/SimpleBullseye_score.target", 0, 0).get();
		rs.onTargetsChanged(host.targets());
		assertEquals(List.of(), newSounds());

		host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		rs.onTargetsChanged(host.targets());

		assertEquals(5, rs.getSubtargets().size());
		assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));

		// Another target leaving changes nothing
		plain.remove();
		rs.onTargetsChanged(host.targets());
		assertEquals(List.of(), newSounds());
	}

	// The called-out target leaving starts the call-outs on another, or warns that there is none
	@Test
	public void theCalledOutTargetLeavingMovesOnOrWarns() {
		final TargetHandle first = host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		final TargetHandle second = host.addTarget("targets/SimpleBullseye_five_small.target", 300, 0).get();
		final RandomShoot rs = new RandomShoot(rng);
		host.start(rs);
		newSounds();

		first.remove();
		rs.onTargetsChanged(host.targets());
		assertEquals(RandomShoot.SHOOT_SOUND, newSounds().get(0));
		assertEquals(5, rs.getSubtargets().size());

		second.remove();
		rs.onTargetsChanged(host.targets());
		assertEquals(List.of(RandomShoot.WARNING_SOUND), newSounds());
		assertTrue(rs.getCurrentSubtargets().isEmpty());
	}
}
