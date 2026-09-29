package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.exercise.TargetHandle;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;

class TestParRandomShot {
	private static final String MAKE_READY = TimedHolsterDrill.MAKE_READY_SOUND;
	private static final String CHIME = ParForScore.CHIME_SOUND;

	@TempDir Path temp;
	private Locale previousLocale;
	private String previousHome;
	private FakeExerciseHost host;

	@BeforeEach
	void setUp() {
		previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.US);
		// The voice files are ShootOFF's own, in its sounds/voice folder
		previousHome = System.getProperty("shootoff.home");
		System.setProperty("shootoff.home", System.getProperty("user.dir"));
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
		if (previousHome == null) System.clearProperty("shootoff.home");
		else System.setProperty("shootoff.home", previousHome);
	}

	// Starts the drill with a 1 s delay and a 2 s par time, and runs it to its first call-out
	private ParRandomShot startDrillToTheCallOut() {
		final ParRandomShot drill = new ParRandomShot(new Random(3));
		host.start(drill);
		host.changeDelayedStart(new DelayRange(1, 1));
		host.changeParTime(2.0);
		advanceSeconds(11);
		return drill;
	}

	private void advanceSeconds(double seconds) {
		host.advance(Duration.ofMillis(Math.round(seconds * 1000)));
	}

	private static Hit hit(TargetHandle target, String subtarget) {
		for (final Region region : target.definition().regions()) {
			if (region.tag("subtarget").equals(Optional.of(subtarget))) {
				return new Hit(target.id(), region, new Point(0, 0));
			}
		}
		throw new AssertionError("No subtarget " + subtarget);
	}

	private boolean shoot(Hit hit) {
		return host.shoot(new Shot(ShotColor.RED, 0, 0, host.currentTimeMillis()), Optional.of(hit));
	}

	private Map<String, String> lastRow() {
		final List<FakeExerciseHost.Row> rows = host.rows();
		return rows.get(rows.size() - 1).values();
	}

	@Test
	void withoutATargetWithSubtargetsItWarnsAndRunsNoRoundsUntilResetFindsOne() {
		host.start(new ParRandomShot(new Random(3)));
		host.changeDelayedStart(new DelayRange(1, 1));

		assertEquals(List.of(RandomShoot.WARNING_SOUND), host.sounds());
		advanceSeconds(60);
		host.click("Pause");
		host.click("Resume");
		advanceSeconds(60);
		assertEquals(List.of(RandomShoot.WARNING_SOUND), host.sounds());
		assertEquals(0, host.pendingTasks());

		host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0);
		host.reset();
		advanceSeconds(10);
		assertEquals(List.of(RandomShoot.WARNING_SOUND, MAKE_READY), host.sounds());
	}

	@Test
	void eachRoundCallsOutARandomSubtargetInsteadOfTheBeep() {
		host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0);
		final ParRandomShot drill = startDrillToTheCallOut();

		assertEquals(5, drill.getSubtargets().size());
		assertEquals(List.of(MAKE_READY, "sounds/voice/shootoff-" + drill.getCurrentSubtarget() + ".wav"),
				host.sounds());

		advanceSeconds(2);
		assertEquals(CHIME, host.sounds().get(2));
		assertTrue(host.isShotDetectionPaused());
	}

	@Test
	void onlyHitsOnTheCalledSubtargetScoreItsPointsOrOne() {
		final TargetHandle target = host.addTarget("targets/SimpleBullseye_five_small.target", 0, 0).get();
		final ParRandomShot drill = startDrillToTheCallOut();
		final String called = drill.getCurrentSubtarget();
		final String other = drill.getSubtargets().stream().filter(name -> !name.equals(called)).findFirst().get();

		advanceSeconds(0.5);
		assertTrue(shoot(hit(target, other)));
		assertEquals(Map.of("Length", "0.50"), lastRow());
		assertEquals(List.of("score: 0"), host.messages());

		assertTrue(shoot(hit(target, called)));
		assertEquals(Map.of("Length", "0.50", "Score", "1"), lastRow());
		assertEquals(List.of("score: 0", "red score: 1"), host.messages());
	}

	@Test
	void aSubtargetWithoutAVoiceFileIsSpoken() throws URISyntaxException {
		host.addTarget(Paths.get(TestParRandomShot.class.getResource("/test_missing_sound_files.target").toURI())
				.toString(), 0, 0);
		final ParRandomShot drill = startDrillToTheCallOut();

		assertEquals(List.of(drill.getCurrentSubtarget()), host.spoken());
		assertEquals(List.of(MAKE_READY), host.sounds());
	}
}
