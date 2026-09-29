package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import com.shootoff.geom.Point;
import com.shootoff.targets.model.Hit;
import com.shootoff.targets.model.Region;
import com.shootoff.targets.model.TargetDefinitions;
import com.shootoff.targets.model.TargetFormatException;
import com.shootoff.targets.model.TargetId;

class TestParForScore {
	private static final String MAKE_READY = TimedHolsterDrill.MAKE_READY_SOUND;
	private static final String BEEP = TimedHolsterDrill.BEEP_SOUND;
	private static final String CHIME = ParForScore.CHIME_SOUND;

	@TempDir Path temp;
	private Locale previousLocale;
	private FakeExerciseHost host;
	private Hit tenRegionHit;

	@BeforeEach
	void setUp() throws IOException, TargetFormatException {
		previousLocale = Locale.getDefault();
		Locale.setDefault(Locale.US);
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.resolve("data"));

		for (final Region region : TargetDefinitions.load(Paths.get("targets", "SimpleBullseye_score.target")).regions()) {
			if (region.tag("points").equals(Optional.of("10"))) tenRegionHit = new Hit(new TargetId(1), region, new Point(0, 0));
		}
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
	}

	// Starts the drill with a 1 s delay and a 2 s par time, and runs it to its first beep
	private void startDrillToTheBeep() {
		host.start(new ParForScore(new Random(7)));
		host.changeDelayedStart(new DelayRange(1, 1));
		host.changeParTime(2.0);
		advanceSeconds(11);
	}

	private void advanceSeconds(double seconds) {
		host.advance(Duration.ofMillis(Math.round(seconds * 1000)));
	}

	private boolean shootTheTen(ShotColor color) {
		return host.shoot(new Shot(color, 0, 0, host.currentTimeMillis()), Optional.of(tenRegionHit));
	}

	private Map<String, String> lastRow() {
		final List<FakeExerciseHost.Row> rows = host.rows();
		return rows.get(rows.size() - 1).values();
	}

	@Test
	void itShowsBothColumnsAndTheSharedParTimeAtTheJavaFxDefault() {
		host.start(new ParForScore());

		assertEquals(List.of("Length", "Score"), host.columns());
		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		assertEquals(2.0, host.parTime(), 0);
		assertTrue(host.isListeningForParTime());
		assertEquals(List.of("score: 0"), host.messages());
	}

	@Test
	void theParTimeScoresFromTheBeepToTheChime() {
		startDrillToTheBeep();
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());

		// The first round scores too
		advanceSeconds(0.5);
		assertTrue(shootTheTen(ShotColor.RED));
		assertEquals(Map.of("Length", "0.50", "Score", "10"), lastRow());
		assertTrue(shootTheTen(ShotColor.GREEN));
		assertEquals(List.of("score: 0", "red score: 10", "red score: 10\ngreen score: 10"), host.messages());

		// The chime ends the par time and shot detection until the next beep
		advanceSeconds(1.5);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME), host.sounds());
		assertTrue(host.isShotDetectionPaused());
		assertFalse(shootTheTen(ShotColor.RED));

		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME, BEEP), host.sounds());
		assertFalse(host.isShotDetectionPaused());
	}

	@Test
	void theUsersParTimeTakesEffectFromTheNextRound() {
		startDrillToTheBeep();

		host.changeParTime(3.0);
		advanceSeconds(2);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME), host.sounds());
		advanceSeconds(1);
		advanceSeconds(2.9);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME, BEEP), host.sounds());
		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY, BEEP, CHIME, BEEP, CHIME), host.sounds());
	}

	@Test
	void pausingInTheParTimeEndsItWithoutTheChime() {
		startDrillToTheBeep();

		host.click("Pause");
		advanceSeconds(30);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		assertTrue(host.isShotDetectionPaused());

		host.click("Resume");
		advanceSeconds(6);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY, BEEP), host.sounds());
		assertTrue(shootTheTen(ShotColor.RED));
		assertEquals("10", lastRow().get("Score"));
	}

	@Test
	void resetZeroesTheScores() {
		startDrillToTheBeep();
		shootTheTen(ShotColor.RED);

		host.reset();

		assertEquals(List.of("score: 0", "red score: 10", "score: 0"), host.messages());
		advanceSeconds(11);
		assertTrue(shootTheTen(ShotColor.RED));
		assertEquals("red score: 10", host.messages().get(3));
	}
}
