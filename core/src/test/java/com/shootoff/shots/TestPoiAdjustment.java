package com.shootoff.shots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.shootoff.config.ConfigurationException;
import com.shootoff.config.ScratchConfig;
import com.shootoff.config.Settings;
import com.shootoff.geom.Point;
import com.shootoff.sound.SoundPlayer;

class TestPoiAdjustment {
	private Settings settings;
	private boolean wasSilenced;
	private String workingTreeConfig;

	@BeforeEach
	void setUp() throws ConfigurationException, IOException {
		workingTreeConfig = ScratchConfig.workingTreeFingerprint();
		// Five hits write the configuration: never to the owner's shootoff.properties
		settings = new Settings(ScratchConfig.emptyFile().getPath(), new String[0]);
		wasSilenced = SoundPlayer.isSilenced();
		SoundPlayer.silence(true);
	}

	@AfterEach
	void tearDown() {
		SoundPlayer.silence(wasSilenced);
		assertEquals(workingTreeConfig, ScratchConfig.workingTreeFingerprint());
	}

	@Test
	void theOffsetIsFromTheRegionsCenterInTheTargetsScale() {
		assertEquals(new Point(-7, 14), PoiAdjustment.offset(new Point(200, 100), new Point(193, 114), 1, 1));
		assertEquals(new Point(-3.5, 7), PoiAdjustment.offset(new Point(200, 100), new Point(193, 114), 2, 2));
	}

	@Test
	void fiveHitsTurnTheAdjustmentOnAndASixthTurnsItOff() {
		for (int hit = 0; hit < 4; hit++) {
			PoiAdjustment.apply(settings, new Point(10, -4));
			assertFalse(settings.isAdjustingPOI());
		}

		PoiAdjustment.apply(settings, new Point(10, -4));

		assertTrue(settings.isAdjustingPOI());
		assertEquals(Optional.of(-10.0), settings.getPOIAdjustmentX());
		assertEquals(Optional.of(4.0), settings.getPOIAdjustmentY());

		PoiAdjustment.apply(settings, new Point(1, 1));

		assertFalse(settings.isAdjustingPOI());
		assertEquals(Optional.empty(), settings.getPOIAdjustmentX());
	}
}
