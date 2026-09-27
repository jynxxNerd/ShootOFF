package com.shootoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.shootoff.calibration.CalibrationFlow;
import com.shootoff.plugins.TextToSpeech;

class TestBundledImages {
	// Every image a user interface shows from ShootOFF's own resources: the calibration pattern, the
	// exposure step's white screen and the arena backgrounds
	private static final List<String> IMAGES = List.of("/pattern.png", "/white.png",
			"/arena/backgrounds/hickok45_autumn.gif", "/arena/backgrounds/hickok45_summer.gif",
			"/arena/backgrounds/indoor_range.gif", "/arena/backgrounds/kiang_west_savanna.gif",
			"/arena/backgrounds/oradour-sur-glane.gif", "/arena/backgrounds/outdoor_range.gif",
			"/arena/backgrounds/steel_range_bay.gif", "/arena/backgrounds/subterranean_parking_lot.gif");

	@Test
	void theCalibrationPatternAndBackgroundsAreCoreResources() throws Exception {
		for (final String name : IMAGES) {
			final URL url = CalibrationFlow.class.getResource(name);
			assertNotNull(url, name + " is not on core's class path");
			assertTrue(url.toString().contains("/core/"), name + " comes from " + url);

			try (InputStream in = url.openStream()) {
				final BufferedImage image = ImageIO.read(in);
				assertNotNull(image, name + " is not an image");
				assertTrue(image.getWidth() > 0, name);
			}
		}
	}

	@Test
	void textToSpeechIsInCore() throws Exception {
		final URL location = TextToSpeech.class.getProtectionDomain().getCodeSource().getLocation();
		assertTrue(location.toString().contains("/core/"), "TextToSpeech comes from " + location);
		assertEquals("com.shootoff.plugins.TextToSpeech", TextToSpeech.class.getName());
	}
}
