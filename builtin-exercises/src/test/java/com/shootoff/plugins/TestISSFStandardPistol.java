package com.shootoff.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.shootoff.camera.Shot;
import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;

// JUnit 4, as in the JavaFX app: the test names stay those of the Java 8 baseline
public class TestISSFStandardPistol {
	private static final String MAKE_READY = ISSFStandardPistol.MAKE_READY_SOUND;
	private static final String BEEP = ISSFStandardPistol.BEEP_SOUND;
	private static final String ROUND_OVER = ISSFStandardPistol.ROUND_OVER_SOUND;

	@Rule public TemporaryFolder temp = new TemporaryFolder();

	private FakeExerciseHost host;
	private Hit scoredRegionHit;
	private int regionScore;
	private int messagesSeen = 0;
	private int soundsSeen = 0;
	private int spokenSeen = 0;

	@Before
	public void setUp() throws IOException, TargetFormatException {
		final Region scored = TargetDefinitions.load(Paths.get("targets", "ISSF.target")).regions().get(0);
		scoredRegionHit = new Hit(new TargetId(1), scored, new Point(0, 0));
		regionScore = Integer.parseInt(scored.tag("points").get());

		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.newFolder("data").toPath());
		host.start(new ISSFStandardPistol(new Random(1)));
		// No random delays, as the JavaFX test's init(0, 0) had it
		host.changeDelayedStart(new DelayRange(0, 0));
		host.advance(ISSFStandardPistol.START_DELAY);
	}

	private List<String> newMessages() {
		final List<String> all = host.messages();
		final List<String> shown = List.copyOf(all.subList(messagesSeen, all.size()));
		messagesSeen = all.size();
		return shown;
	}

	private List<String> newSounds() {
		final List<String> all = host.sounds();
		final List<String> played = List.copyOf(all.subList(soundsSeen, all.size()));
		soundsSeen = all.size();
		return played;
	}

	private List<String> newSpoken() {
		final List<String> all = host.spoken();
		final List<String> said = List.copyOf(all.subList(spokenSeen, all.size()));
		spokenSeen = all.size();
		return said;
	}

	// A shot on the scored region; the next series, if this one ended, starts at once (no delay)
	private void shootScored() {
		assertTrue(host.shoot(new Shot(ShotColor.RED, 0, 0, 0, 2), Optional.of(scoredRegionHit)));
		host.advance(Duration.ZERO);
	}

	private static String getScoreString(int roundOne, int roundTwo, int roundThree) {
		return String.format("150s score: %d\n20s score: %d\n10s score: %d\ntotal score: %d", roundOne, roundTwo,
				roundThree, roundOne + roundTwo + roundThree);
	}

	private void assertShown(int roundOne, int roundTwo, int roundThree, boolean roundOver, boolean gameOver) {
		assertEquals(List.of(getScoreString(roundOne, roundTwo, roundThree)), newMessages());

		if (gameOver) {
			assertEquals(List.of(ROUND_OVER), newSounds());
			assertEquals(List.of("Event over... Your score is 60"), newSpoken());
		} else if (roundOver) {
			assertEquals(List.of(ROUND_OVER, BEEP), newSounds());
		} else {
			assertEquals(List.of(), newSounds());
		}
	}

	private void shootFullSeries150s(int i) {
		// (regionScore * 5 * i) = last round's score
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 2, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 3, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 4, 0, 0, false, false);
		shootScored();
		assertShown((regionScore * 5 * i) + regionScore * 5, 0, 0, true, false);
	}

	@Test
	public void testFullRound() {
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());

		// 150s round 1-4
		for (int i = 0; i < 4; i++) {
			shootFullSeries150s(i);
		}

		// 20s round 1-4
		for (int i = 0; i < 4; i++) {
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 2, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 3, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 4, 0, false, false);
			shootScored();
			assertShown(regionScore * 20, (regionScore * 5 * i) + regionScore * 5, 0, true, false);
		}

		// 10s round 1-4
		for (int i = 0; i < 4; i++) {
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore, false, false);
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 2, false, false);
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 3, false, false);
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 4, false, false);

			final boolean gameOver = i == 3;
			shootScored();
			assertShown(regionScore * 20, regionScore * 20, (regionScore * 5 * i) + regionScore * 5, true, gameOver);
		}

		// The event is over: shot detection stays on, and no series starts
		assertFalse(host.isShotDetectionPaused());
		assertEquals(0, host.pendingTasks());
	}

	@Test
	public void testFull150sThenReset() {
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());

		// 150s round 1-4
		for (int i = 0; i < 4; i++) {
			shootFullSeries150s(i);
		}

		host.reset();
		assertEquals(List.of(""), newMessages());
		assertTrue(host.isShotDetectionPaused());

		host.advance(ISSFStandardPistol.START_DELAY);
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());
		shootScored();
		assertEquals(List.of(getScoreString(regionScore, 0, 0)), newMessages());
	}

	@Test
	public void aSeriesEndsAtItsTimeAndEachShotsRowSaysItsScoreAndSeries() {
		newSounds();
		shootScored();
		assertEquals(Map.of("Score", String.valueOf(regionScore), "Round", "R1 (150s)"),
				host.rows().get(0).values());

		host.advance(Duration.ofSeconds(149));
		assertEquals(List.of(), newSounds());
		host.advance(Duration.ofSeconds(1));
		assertEquals(List.of(ROUND_OVER, BEEP), newSounds());

		shootScored();
		assertEquals("R2 (150s)", host.rows().get(1).values().get("Round"));
		assertEquals(Optional.of(ISSFStandardPistol.SERIES_SHADING), host.rows().get(1).style());
	}

	@Test
	public void pauseAbandonsTheSeriesUnderWayAndResumeShootsItAgain() {
		newSounds();
		shootScored();
		shootScored();
		newMessages();

		host.click("Pause");
		assertEquals(List.of("Resume"), host.buttonLabels());
		assertTrue(host.isShotDetectionPaused());
		host.advance(Duration.ofSeconds(200));
		assertEquals(List.of(), newSounds());

		host.click("Resume");
		host.advance(ISSFStandardPistol.RESUME_DELAY);
		assertEquals(List.of(MAKE_READY, BEEP), newSounds());

		// The same series from its start, without the points shot before the pause
		shootScored();
		assertEquals(List.of(getScoreString(regionScore, 0, 0)), newMessages());
		assertEquals("R1 (150s)", host.rows().get(host.rows().size() - 1).values().get("Round"));
	}
}
