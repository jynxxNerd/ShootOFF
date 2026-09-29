package com.shootoff.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.shootoff.camera.shot.ShotColor;
import com.shootoff.exercise.DelayRange;
import com.shootoff.exercise.FakeExerciseHost;
import com.shootoff.exercise.RowStyle;

class TestTimedHolsterDrill {
	private static final String MAKE_READY = TimedHolsterDrill.MAKE_READY_SOUND;
	private static final String BEEP = TimedHolsterDrill.BEEP_SOUND;

	@TempDir Path temp;
	private Locale previousLocale;
	private FakeExerciseHost host;

	@BeforeEach
	void setUp() {
		previousLocale = Locale.getDefault();
		// The drill formats lengths in the default locale, as it always has
		Locale.setDefault(Locale.US);
		host = new FakeExerciseHost(FakeExerciseHost.DEFAULT_SURFACE, false, temp.resolve("data"));
	}

	@AfterEach
	void tearDown() {
		Locale.setDefault(previousLocale);
	}

	// Starts the drill with a 1 s delay between rounds
	private void startDrill() {
		host.start(new TimedHolsterDrill(new Random(7)));
		host.changeDelayedStart(new DelayRange(1, 1));
	}

	private void advanceSeconds(double seconds) {
		host.advance(Duration.ofMillis(Math.round(seconds * 1000)));
	}

	private void shoot() {
		assertTrue(host.shoot(ShotColor.RED, 5, 5));
	}

	private String lastLength() {
		final List<FakeExerciseHost.Row> rows = host.rows();
		return rows.get(rows.size() - 1).values().get(TimedHolsterDrill.LENGTH_COL_NAME);
	}

	@Test
	void itShowsItsControlsAndTheSharedDelayAtTheJavaFxDefaults() {
		host.start(new TimedHolsterDrill());

		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		assertEquals(List.of("Length"), host.columns());
		assertEquals(new DelayRange(4, 8), host.delayedStart());
		assertTrue(host.isListeningForDelayedStart());
		assertFalse(host.isListeningForParTime());
		assertTrue(host.isShotDetectionPaused());
	}

	@Test
	void tenSecondsToGetReadyThenMakeReadyAndABeepAfterTheRandomDelay() {
		startDrill();

		advanceSeconds(9.9);
		assertEquals(List.of(), host.sounds());

		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY), host.sounds());
		assertTrue(host.isShotDetectionPaused());

		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		assertFalse(host.isShotDetectionPaused());

		// Round after round, with no new "make ready" and detection left on
		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP, BEEP), host.sounds());
		assertFalse(host.isShotDetectionPaused());
	}

	@Test
	void aShotsLengthIsItsTimeSinceTheBeep() {
		startDrill();
		advanceSeconds(11);

		advanceSeconds(0.25);
		shoot();
		assertEquals("0.25", lastLength());

		advanceSeconds(0.5);
		shoot();
		assertEquals("0.75", lastLength());
	}

	@Test
	void roundsAlternateTheirShadingOnlyAfterARoundWithAShot() {
		startDrill();
		advanceSeconds(11);

		shoot();
		// The next round is shaded; a round without a shot keeps the shading for the one after
		advanceSeconds(1);
		shoot();
		advanceSeconds(1);
		advanceSeconds(1);
		shoot();

		assertEquals(List.of(Optional.empty(), Optional.of(TimedHolsterDrill.ROUND_SHADING),
				Optional.<RowStyle> empty()), host.rows().stream().map(FakeExerciseHost.Row::style).toList());
	}

	@Test
	void pauseStopsTheRoundsAndResumeMakesReadyASecondAndAHalfLater() {
		startDrill();
		advanceSeconds(11);

		host.click("Pause");
		assertEquals(List.of("Resume", "Clear Shots"), host.buttonLabels());
		assertTrue(host.isShotDetectionPaused());
		advanceSeconds(60);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());

		host.click("Resume");
		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		advanceSeconds(1.4);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY), host.sounds());
		advanceSeconds(1);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY, BEEP), host.sounds());
		// One chain of rounds, not two
		assertEquals(1, host.pendingTasks());
	}

	@Test
	void resetStartsAfreshTenSecondsLater() {
		startDrill();
		advanceSeconds(11);
		host.click("Pause");

		host.reset();

		assertEquals(List.of("Pause", "Clear Shots"), host.buttonLabels());
		assertTrue(host.isShotDetectionPaused());
		advanceSeconds(9.9);
		assertEquals(List.of(MAKE_READY, BEEP), host.sounds());
		advanceSeconds(0.1);
		assertEquals(List.of(MAKE_READY, BEEP, MAKE_READY), host.sounds());
	}

	@Test
	void clearShotsClearsTheShotTimer() {
		startDrill();
		advanceSeconds(11);
		shoot();

		host.click("Clear Shots");

		assertEquals(List.of(), host.rows());
	}
}
